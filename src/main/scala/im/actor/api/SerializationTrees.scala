package im.actor.api

import com.google.protobuf.WireFormat
import scala.language.postfixOps
import treehugger.forest._, definitions._
import treehuggerDSL._

trait SerializationTrees extends TreeHelpers {
  def serializationTrees(name: String, attributes: Vector[Attribute]): Vector[Tree] = {
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
        case AttributeType("opt", Some(optAttrType)) =>
          wireType(optAttrType)
        case AttributeType("list", Some(listAttrType)) =>
          wireType(listAttrType)
        case AttributeType("struct" | "enum", Some(_)) =>
          WireFormat.WIRETYPE_LENGTH_DELIMITED
      }
    }

    val cases: Vector[Vector[CaseDef]] = attributes map { attr =>
      val baseCase = CASE(LIT((attr.id << 3) | wireType(attr.typ))) ==> BLOCK()

      if (attr.typ.typ == "list") {
        val listCase = CASE(LIT((attr.id << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED)) ==> BLOCK()
        Vector(baseCase, listCase)
      } else {
        Vector(baseCase)
      }
    }

    val (witnesses, selectors): (Vector[ValDef], Vector[ValDef]) = attributes map { attr =>
      val w = f"w${attr.id}%d"
      val s = f"s${attr.id}%d"

      (
        VAL(w) := REF("Witness") APPLY (LIT(attr.id)),
        PARAM(s, typeRef(NoPrefix, valueCache("Selector"), List(TYPE_REF("L"), TYPE_REF(f"$w%s.T"))))
          withFlags(Flags.IMPLICIT): ValDef
      )
    } unzip

    val parseDef = DEF("parseFrom") withParams(PARAM("in", valueCache("CodedOutputStream"))) := BLOCK(
      DEF("doParse", valueCache("L"))
        withTypeParams(TYPEVAR("L") UPPER valueCache("HList"))
        withParams(PARAM("values", valueCache("L")))
        withParams(selectors) := BLOCK(
          (REF("in") DOT("readTag()")) MATCH (cases.flatten)
        ),
      REF("doParse") APPLY()
    )

    Vector.empty// ++ witnesses :+ parseDef
  }
}
