package im.actor.api

import com.google.protobuf.WireFormat
import scala.language.postfixOps
import treehugger.forest._, definitions._
import treehuggerDSL._

trait DeserializationTrees extends TreeHelpers with Hacks {
  protected val parseExceptionDef: Tree = CLASSDEF("ParseException") withParents(REF("Exception")) withParams(
    PARAM("partialMessage", valueCache("Any"))
  )

  private def partialTypeDef(packageName: String, name: String, attributes: Vector[Attribute]): Option[Vector[Tree]] = {
    def partialAttrType(typ: Types.AttributeType): Type = typ match {
      case Types.Int32 => optionType(IntClass)
      case Types.Int64 => optionType(LongClass)
      case Types.Double => optionType(DoubleClass)
      case Types.String => optionType(StringClass)
      case Types.Bool => optionType(BooleanClass)
      case Types.Bytes => optionType(arrayType(ByteClass))
      case Types.Struct(structName) =>
        eitherType(f"Refs.$structName%s.Partial", f"Refs.$structName%s")
      case Types.Enum(enumName) =>
        optionType(f"Refs.$enumName%s")
      case Types.List(typ @ (Types.Struct(_) | Types.Enum(_))) =>
        vectorType(partialAttrType(typ))
      case Types.List(typ) =>
        vectorType(attrType(typ))
      case Types.Opt(typ) =>
        optionType(partialAttrType(typ))
      case Types.Trait(traitName) =>
        eitherType("Any", f"Refs.$traitName%s")
    }

    val (params, forExprs) = attributes.sortBy(_.id).foldLeft[
      (Vector[ValDef], Vector[ForValFrom])
    ]((Vector.empty, Vector.empty)) {
      case ((params, forExprs), attr) =>
        attr.typ match {
          case typ @ Types.List(listType @ (Types.Struct(_) | Types.Enum(_))) =>
            val eithersAttr = f"eithers${attr.name}%s"

            (
              params :+ PARAM(eithersAttr, partialAttrType(attr.typ)).tree,
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
          case typ @ Types.List(_) =>
            (
              params :+ PARAM(attr.name, partialAttrType(attr.typ)).tree,
              forExprs
            )
          case typ @ (Types.Struct(_) | Types.Trait(_)) =>
            val eitherAttr = f"either${attr.name}%s"
            (
              params :+ PARAM(eitherAttr, partialAttrType(typ)).tree,
              forExprs :+ (VALFROM(attr.name) := REF(eitherAttr) DOT("right") DOT("toOption"))
            )
          case typ @ Types.Opt(optType @ (Types.Struct(_) | Types.Trait(_))) =>
            val opteitherAttr = f"opteither${attr.name}%s"
            (
              params :+ PARAM(opteitherAttr, optionType(partialAttrType(typ))).tree,
              forExprs :+ (
                VALFROM(attr.name) := BLOCK(
                  REF(opteitherAttr) MATCH(
                    CASE(REF("None")) ==> REF("None"),

                    CASE(REF("Some") APPLY(
                      REF("Some") APPLY(REF("Left") APPLY(WILDCARD)))
                    ) ==>
                        REF("None"),

                    CASE(REF("Some") APPLY(REF("None"))) ==> (
                      REF("Some") APPLY(REF("None"))
                    ),

                    CASE(REF("Some") APPLY(
                      REF("Some") APPLY(REF("Right") APPLY(REF("msg"))))
                    ) ==>
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
              params :+ PARAM(optAttr, partialAttrType(attr.typ)).tree,
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

      def emptyValue(attr: Attribute) = attr.typ match {
        case Types.List(_) => EmptyVector
        case Types.Opt(_) => REF("Some") APPLY(REF("None"))
        case Types.Struct(structName) =>
          REF("Left") APPLY(
            REF("Refs") DOT(structName) DOT("Partial") DOT("empty")
          )
        case Types.Trait(_) =>
          REF("Left") APPLY(UNIT)
        case _ => REF("None")
      }

      val objDef = OBJECTDEF(className) := BLOCK(
        VAL("empty") := REF(className) DOT("apply") APPLY(attributes.sortBy(_.id) map { attr =>
          emptyValue(attr)
        })
      )

      Some(Vector(classDef, objDef))
    } else {
      None
    }
  }

  protected def traitDeserializationTrees(traitName: String, children: Vector[NamedItem]): Vector[Tree] = {
    if (children.length > 0) {
      val parseFromDef = DEF("parseFrom", eitherType("Any", valueCache(traitName))) withParams(
        PARAM("in", valueCache("com.google.protobuf.CodedInputStream")),
        PARAM("ext", IntClass)
      ) := BLOCK(
        REF("ext") MATCH (
          children map(c => (c, c.traitExt)) map {
            case (child, Some(TraitExt(_, ext))) =>
            CASE(LIT(ext)) ==> (
              REF("Refs") DOT(child.name) DOT("parseFrom") APPLY(REF("in"))
            )

            case _ =>
              throw new Exception("No trait ext in trait child")
          }
        )
      )

      Vector(parseFromDef)
    } else {
      Vector.empty
    }
  }

  protected def deserializationTrees(packageName: String, name: String, attributes: Vector[Attribute]): Vector[Tree] = {
    def simpleReader(fn: String) = REF("in") DOT(fn) APPLY()

    def reader(typ: Types.AttributeType): Tree = typ match {
      case Types.Int32 => simpleReader("readInt32")
      case Types.Int64 => simpleReader("readInt64")
      case Types.Bool => simpleReader("readBool")
      case Types.Double => simpleReader("readDouble")
      case Types.String => simpleReader("readString")
      case Types.Bytes => simpleReader("readByteArray")
      case Types.Opt(optAttrType) =>
        SOME(reader(optAttrType))
      case Types.List(Types.Struct(structName)) =>
        BLOCK(
          VAL("length") := REF("in") DOT("readRawVarint32") APPLY(),
          VAL("oldLimit") := REF("in") DOT("pushLimit") APPLY(REF("length")),

          VAL("res") := REF("Refs") DOT(structName) DOT("parseFrom") APPLY(REF("in")),

          REF("in") DOT("checkLastTagWas") APPLY(LIT(0)),
          REF("in") DOT("popLimit") APPLY(REF("oldLimit")),

          REF("res")
        )
      case Types.List(listAttrType) =>
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
      case Types.Struct(structName) =>
        BLOCK(
          VAL("length") := REF("in") DOT("readRawVarint32") APPLY(),
          VAL("oldLimit") := REF("in") DOT("pushLimit") APPLY(REF("length")),

          VAL("message") := REF("Refs") DOT(structName) DOT("parseFrom") APPLY(REF("in")),

          REF("in") DOT("checkLastTagWas") APPLY(LIT(0)),
          REF("in") DOT("popLimit") APPLY(REF("oldLimit")),

          REF("message")
        )
      case Types.Enum(enumName) =>
        BLOCK(
          REF("Refs") DOT(enumName) APPLY(
            REF("in") DOT("readEnum") APPLY()
          )
        )
      case Types.Trait(traitName) =>
        val extTypeField = extTypeName(name)

        REF("partialMessage") DOT(f"opt$extTypeField%s") MATCH(
          CASE(REF("Some") APPLY(REF("extType"))) ==> BLOCK(
            VAL("bytes") := REF("in") DOT("readByteArray") APPLY(),

            VAL("stream") := valueCache("com.google.protobuf.CodedInputStream") DOT("newInstance") APPLY(REF("bytes")),

            REF("Refs") DOT(traitName) DOT("parseFrom") APPLY(REF("stream"), REF("extType"))
          ),
          CASE(REF("None")) ==> THROW(NEW(REF("ParseException") APPLY(LIT("Trying to parse trait but extType is missing"))))
        )
      case attr =>
        BLOCK().withComment(attr.toString)
    }

    def readCaseBody(attrName: String, attrType: Types.AttributeType, appendOp: Option[String]): Tree = {
      REF("doParse") APPLY(
        REF("partialMessage") DOT("copy") APPLY(
          appendOp match {
            case Some(op) =>
              attrType match {
                case Types.List(Types.Struct(_)) =>
                  val eithersName = f"eithers$attrName%s"
                  REF(eithersName) := REF("partialMessage") DOT(eithersName) INFIX(op) APPLY(reader(attrType))
                case _ => REF(attrName) := REF("partialMessage") DOT(attrName) INFIX(op) APPLY(reader(attrType))
              }
            case None =>
              attrType match {
                case Types.Struct(_) | Types.Trait(_) =>
                  REF(f"either$attrName%s") := reader(attrType)
                case Types.Opt(Types.Struct(_) | Types.Trait(_)) =>
                  REF(f"opteither$attrName%s") := SOME(reader(attrType))
                case Types.List(Types.Struct(_)) =>
                  REF(f"eithers$attrName%s") := reader(attrType)
                case _ =>
                  REF(f"opt$attrName%s") := REF("Some") APPLY(reader(attrType))
              }
          }
        )
      )
    }

    @annotation.tailrec
    def wireType(attrType: Types.AttributeType): Int = {
      attrType match {
        case Types.Int32 | Types.Int64 | Types.Bool => WireFormat.WIRETYPE_VARINT
        case Types.String | Types.Bytes => WireFormat.WIRETYPE_LENGTH_DELIMITED
        case Types.Double => WireFormat.WIRETYPE_FIXED64
        case Types.Opt(optAttrType) =>
          wireType(optAttrType)
        case Types.List(listAttrType) =>
          wireType(listAttrType)
        case Types.Enum(_) =>
          WireFormat.WIRETYPE_VARINT
        case Types.Struct(_) | Types.Trait(_) =>
          WireFormat.WIRETYPE_LENGTH_DELIMITED
        case unsupported => throw new Exception(f"Unsupported wire type: $unsupported%s")
      }
    }

    val optPartialTypeDef = partialTypeDef(packageName, name, attributes)

    val parseDef =
      optPartialTypeDef match {
        case Some(_) =>
          val fieldCases: Vector[Vector[CaseDef]] = attributes map { attr =>
            val baseCaseExpr = CASE(LIT((attr.id << 3) | wireType(attr.typ)))

            attr.typ match {
              case typ @ Types.List(listAttrType) =>
                if (listAttrType.isInstanceOf[Types.Struct]) {
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
                    REF("doParse") APPLY()
                  ) ELSE BLOCK()
                )
          )

          val parseDefType = eitherType("Unit", valueCache(name))

          DEF("parseFrom", parseDefType) withParams(PARAM("in", valueCache("com.google.protobuf.CodedInputStream"))) := BLOCK(
            DEF("doParse", valueCache("Unit"))
              withParams() := BLOCK(
                (REF("in") DOT("readTag()")) MATCH (cases)
              ),

            REF("doParse") APPLY(),

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
