package im.actor.api

import com.google.protobuf.WireFormat
import scala.language.postfixOps
import treehugger.forest._, definitions._
import treehuggerDSL._

trait SerializationTrees extends TreeHelpers with Hacks {
  protected val parseExceptionDef: Tree = CLASSDEF("ParseException") withParents(REF("Exception")) withParams(
    PARAM("partialMessage", valueCache("Any"))
  )

  private def partialTypeDef(packageName: String, name: String, attributes: Vector[Attribute], aliases: Aliases): Option[Vector[Tree]] = {
    def partialAttrType(typ: AttributeType, aliases: Aliases): Type = typ match {
      case AttributeType("int32", None) => optionType(IntClass)
      case AttributeType("int64", None) => optionType(LongClass)
      case AttributeType("double", None) => optionType(DoubleClass)
      case AttributeType("string", None) => optionType(StringClass)
      case AttributeType("bool", None) => optionType(BooleanClass)
      case AttributeType("bytes", None) => optionType(arrayType(ByteClass))
      case AttributeType("struct", Some(child)) =>
        partialAttrType(child, aliases)
      case AttributeType("enum", Some(AttributeType(enumName, _))) =>
        optionType(f"Refs.$enumName%s")
      case AttributeType("list", Some(t @ AttributeType("struct" | "enum", _))) =>
        vectorType(partialAttrType(t, aliases))
      case AttributeType("list", Some(child)) =>
        vectorType(attrType(child, aliases))
      case AttributeType("opt", Some(child)) =>
        optionType(partialAttrType(child, aliases))
      case AttributeType("alias", Some(AttributeType(aliasName, None))) =>
        aliases.get(aliasName) match {
          case Some(typ) => partialAttrType(AttributeType(typ, None), aliases)
          case None => throw new Exception(f"Alias $aliasName%s is missing")
        }
      case AttributeType("trait", Some(AttributeType(traitName, None))) =>
        optionType(valueCache(traitName))
      case AttributeType(name, None) =>
        eitherType(f"Refs.$name%s.Partial", f"Refs.$name%s")
    }

    val (params, forExprs) = attributes.sortBy(_.id).foldLeft[
      (Vector[ValDef], Vector[ForValFrom])
    ]((Vector.empty, Vector.empty)) {
      case ((params, forExprs), attr) =>
        attr.typ match {
          case typ @ AttributeType("list", Some(AttributeType("struct" | "enum", _))) =>
            val eithersAttr = f"eithers${attr.name}%s"

            (
              params :+ PARAM(eithersAttr, partialAttrType(attr.typ, aliases)).tree,
              forExprs :+ (
                // TODO: optimize here
                VALFROM(attr.name) := BLOCK(
                  VAL("eitherMsgsView") := REF(eithersAttr) DOT("partition") APPLY(
                    WILDCARD DOT("isLeft")
                  ) MATCH (
                    CASE(TUPLE(REF("Vector") APPLY(), REF("rights"))) ==> (
                        REF("Right") APPLY(
                          FOR(VALFROM("Right(msg)") := REF("rights") DOT("view")) YIELD REF("msg")
                        )
                    ),
                    CASE(TUPLE(REF("lefts"), WILDCARD)) ==> (
                        REF("Left") APPLY(
                          FOR(VALFROM("Left(partialMsg)") := REF("lefts") DOT("view")) YIELD REF("partialMsg")
                        )
                    )
                  ),
                  REF("eitherMsgsView") MATCH (
                    CASE(REF("Right") APPLY(REF("msgs"))) ==> (REF("Some") APPLY(REF("msgs") DOT("force") DOT("toVector"))),
                    CASE(REF("Left") APPLY(REF("partialMsgs"))) ==> REF("None")
                  )
                )
              )
            )
          case typ @ AttributeType("list", _) =>
            (
              params :+ PARAM(attr.name, partialAttrType(attr.typ, aliases)).tree,
              forExprs
            )
          case typ @ AttributeType("struct", _) =>
            val eitherAttr = f"either${attr.name}%s"
            (
              params :+ PARAM(eitherAttr, partialAttrType(typ, aliases)).tree,
              forExprs :+ (VALFROM(attr.name) := REF(eitherAttr) DOT("right") DOT("toOption"))
            )
          case typ @ AttributeType("opt", Some(AttributeType("struct", _))) =>
            val opteitherAttr = f"opteither${attr.name}%s"
            (
              params :+ PARAM(opteitherAttr, partialAttrType(typ, aliases)).tree,
              forExprs :+ (
                VALFROM(attr.name) := BLOCK(
                  REF(opteitherAttr) MATCH(
                    CASE(REF("None")) ==>
                        (REF("Some") APPLY(REF("None"))),
                    CASE(REF("Some") APPLY(REF("Left") APPLY(WILDCARD))) ==>
                        REF("None"),
                    CASE(REF("Some") APPLY(REF("Right") APPLY(REF("msg")))) ==>
                        (REF("Some") APPLY(
                          REF("Some") APPLY(REF("msg"))
                        ))
                  )
                )
              )
            )
          case typ =>
            val optAttr = f"opt${attr.name}%s"
            (
              params :+ PARAM(optAttr, partialAttrType(attr.typ, aliases)).tree,
              forExprs :+ (VALFROM(attr.name) := REF(optAttr))
            )
        }
    }

    val className = "Partial"

    if (params.length > 0) {
      val completeBuilder = valueCache(name) APPLY(attributes.sortBy(_.id) map { attr =>
        REF(attr.name)
      })

      val toCompleteTree = if (forExprs.isEmpty) { // there are lists only
        REF("Some") APPLY(completeBuilder)
      } else {
        FOR(forExprs) YIELD (
          completeBuilder
        )
      }

      val classDef = CASECLASSDEF(className) withParams(params) := BLOCK(
        DEF("toComplete", optionType(name)) := BLOCK(
          toCompleteTree
        )
      )

      val objDef = OBJECTDEF(className) := BLOCK(
        VAL("empty") := REF(className) DOT("apply") APPLY(attributes.sortBy(_.id) map { attr =>
          attr.typ match {
            case AttributeType("list", _) => EmptyVector
            case AttributeType("struct", Some(AttributeType(structName, _))) =>
              REF("Left") APPLY(
                REF("Refs") DOT(structName) DOT("Partial") DOT("empty")
              )
            case _ => REF("None")
          }
        })
      )

      Some(Vector(classDef, objDef))
    } else {
      None
    }
  }

  protected def serializationTrees(packageName: String, name: String, attributes: Vector[Attribute], aliases: Aliases): Vector[Tree] = {
    def reader(typ: AttributeType): Tree = typ match {
      case AttributeType(t, None) =>
        val fn = t match {
          case "int32" => "readInt32"
          case "int64" => "readInt64"
          case "bool" => "readBool"
          case "double" => "readDouble"
          case "string" => "readString"
          case "bytes" => "readBytes().toByteArray"
        }

        REF("in") DOT(fn) APPLY()
      case AttributeType("opt", Some(optAttrType)) =>
        SOME(reader(optAttrType))
      case AttributeType("list", Some(AttributeType("struct", Some(AttributeType(structName, _))))) =>
        BLOCK(
          REF("Refs") DOT(structName) DOT("parseFrom") APPLY(REF("in"))
        )
      case AttributeType("list", Some(listAttrType)) =>
        BLOCK(
          VAL("length") := REF("in") DOT("readRawVarint32") APPLY(),
          VAL("limit") := REF("in") DOT("pushLimit") APPLY(REF("length")),

          VAL("values") := (REF("Iterator").DOT("continually"). APPLY(
            reader(listAttrType)
          ).DOT("takeWhile").APPLY( LAMBDA(PARAM(WILDCARD)) ==>
            (REF("in") DOT("getBytesUntilLimit") APPLY()) INT_> LIT(0)
          ).DOT("toVector")),

          REF("in") DOT("popLimit") APPLY(REF("limit")),

          REF("values")
        )
      case AttributeType("struct", Some(AttributeType(structName, _))) =>
        BLOCK(
          VAL("length") := REF("in") DOT("readRawVarint32") APPLY(),
          VAL("oldLimit") := REF("in") DOT("pushLimit") APPLY(REF("length")),

          VAL("message") := REF("Refs") DOT(structName) DOT("parseFrom") APPLY(REF("in")),

          REF("in") DOT("checkLastTagWas") APPLY(LIT(0)),
          REF("in") DOT("popLimit") APPLY(REF("oldLimit")),

          REF("message")
        )
      case AttributeType("enum", Some(AttributeType(enumName, _))) =>
        BLOCK(
          REF("Refs") DOT(enumName) APPLY(
            REF("in") DOT("readEnum") APPLY()
          )
        )
      case AttributeType("alias", Some(AttributeType(aliasName, _))) =>
        aliases.get(aliasName) match {
          case Some(typ) => reader(AttributeType(typ, None))
          case None => throw new Exception(f"Alias $aliasName%s is missing")
        }
      case AttributeType("trait", Some(AttributeType(traitName, None))) =>
        val extTypeField = extTypeName(name)

        REF("partialMessage") DOT(f"opt$extTypeField%s") MATCH(
          CASE(REF("Some") APPLY(REF("extType"))) ==> BLOCK(
            REF("Refs") DOT(f"$traitName%s__") DOT("parseFrom") APPLY(REF("in"))
          ),
          CASE(REF("None")) ==> THROW(NEW(REF("ParseException") APPLY(LIT("Trying to parse trait but extType is missing"))))
        )
      case attr =>
        BLOCK().withComment(attr.toString)
    }

    def readCaseBody(attrName: String, attrType: AttributeType, appendOp: Option[String]): Tree = {
      REF("doParse") APPLY(
        REF("partialMessage") DOT("copy") APPLY(
          appendOp match {
            case Some(op) =>
              attrType match {
                case AttributeType("list", Some(AttributeType("struct", _))) =>
                  val eithersName = f"eithers$attrName%s"
                  REF(eithersName) := REF("partialMessage") DOT(eithersName) INFIX(op) APPLY(reader(attrType))
                case _ => REF(attrName) := REF("partialMessage") DOT(attrName) INFIX(op) APPLY(reader(attrType))
              }
            case None =>
              attrType match {
                case AttributeType("struct", _) =>
                  REF(f"either$attrName%s") := reader(attrType)
                case AttributeType("opt", Some(AttributeType("struct", _))) =>
                  REF(f"opteither$attrName%s") := reader(attrType)
                case AttributeType("list", Some(AttributeType("struct", _))) =>
                  REF(f"eithers$attrName%s") := reader(attrType)
                case _ =>
                  REF(f"opt$attrName%s") := REF("Some") APPLY(reader(attrType))
              }
          }
        )
      )
    }

    @annotation.tailrec
    def wireType(attrType: AttributeType): Int = {
      attrType match {
        case AttributeType(t, None) =>
          t match {
            case "int32" | "int64" | "bool" => WireFormat.WIRETYPE_VARINT
            case "string" | "bytes" => WireFormat.WIRETYPE_LENGTH_DELIMITED
            case "double" => WireFormat.WIRETYPE_FIXED64
            case unsupported => throw new Exception(f"Unsupported wire type: $unsupported%s")
          }
        case AttributeType("alias", Some(AttributeType(aliasName, None))) =>
          aliases.get(aliasName) match {
            case Some(typ) => wireType(AttributeType(typ, None))
            case None => throw new Exception(f"Alias $aliasName%s is missing")
          }
        case AttributeType("opt", Some(optAttrType)) =>
          wireType(optAttrType)
        case AttributeType("list", Some(listAttrType)) =>
          wireType(listAttrType)
        case AttributeType("struct" | "enum" | "trait", Some(_)) =>
          WireFormat.WIRETYPE_LENGTH_DELIMITED
      }
    }

    val optPartialTypeDef = partialTypeDef(packageName, name, attributes, aliases)

    val parseDef =
      optPartialTypeDef match {
        case Some(_) =>
          val fieldCases: Vector[Vector[CaseDef]] = attributes map { attr =>
            val baseCaseExpr = CASE(LIT((attr.id << 3) | wireType(attr.typ)))

            attr.typ match {
              case typ @ AttributeType("list", Some(listAttrType)) =>
                if (listAttrType.typ == "struct") {
                  val baseCase = baseCaseExpr ==> BLOCK(
                    readCaseBody(attr.name, typ, Some(":+"))
                  )

                  Vector(baseCase)
                } else {
                  val baseCase = baseCaseExpr ==> BLOCK(
                    readCaseBody(attr.name, listAttrType, Some(":+"))
                  )

                  val listCase = CASE(LIT((attr.id << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED)) ==> BLOCK(
                    readCaseBody(attr.name, attr.typ, Some("++"))
                  )

                  Vector(baseCase, listCase)
                }

              case attrType =>
                Vector(baseCaseExpr ==> BLOCK(
                  readCaseBody(attr.name, attr.typ, None)
                ))
            }
          }

          val cases = fieldCases.flatten ++ Vector(
            CASE(LIT(0)) ==> REF("partialMessage"),
            CASE(REF("default")) ==>
                (
                  IF (REF("in") DOT("skipField") APPLY(REF("default")) ANY_== LIT(true)) THEN (
                    REF("doParse") APPLY(REF("partialMessage"))
                  ) ELSE REF("partialMessage")
                )
          )

          val partialTypeRef = valueCache("Partial")
          val parseDefType = eitherType("Partial", valueCache(name))

          DEF("parseFrom", parseDefType) withParams(PARAM("in", valueCache("com.google.protobuf.CodedInputStream"))) := BLOCK(
            DEF("doParse", partialTypeRef)
              withParams(PARAM("partialMessage", partialTypeRef)) := BLOCK(
                (REF("in") DOT("readTag()")) MATCH (cases)
              ),

            VAL("partialMessage") := REF("doParse") APPLY(partialTypeRef DOT("empty")),

            REF("partialMessage") DOT("toComplete") DOT("map") APPLY(REF("Right") APPLY(WILDCARD)) DOT("getOrElse") APPLY(BLOCK(
              REF("Left") APPLY(REF("partialMessage"))
            ))
          )
        case None =>
          val cases = Vector(
            CASE(LIT(0)) ==> BLOCK(),
            CASE(REF("default")) ==>
                (
                  IF (REF("in") DOT("skipField") APPLY(REF("default")) ANY_== LIT(true)) THEN (
                    REF("doParse")
                  ) ELSE BLOCK()
                )
          )

          val parseDefType = eitherType("Unit", valueCache(name))

          DEF("parseFrom", parseDefType) withParams(PARAM("in", valueCache("com.google.protobuf.CodedInputStream"))) := BLOCK(
            DEF("doParse", valueCache("Unit"))
              withParams() := BLOCK(
                (REF("in") DOT("readTag()")) MATCH (cases)
              ),

            REF("doParse"),

            REF("Right") APPLY(REF(name))
          )
      }

    optPartialTypeDef match {
      case Some(trees) =>
        trees :+ parseDef
      case None =>
        Vector(parseDef)
    }
    //optPartialTypeDef.getOrElse((Vector.empty, Vector.empty)) ++ Vector(parseDef)// ++ witnesses :+ parseDef
  }
}
