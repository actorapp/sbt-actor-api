package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._

trait ApiServiceTrees extends TreeHelpers {
  lazy val ScalazEitherType = definitions.getClass("\\/")

  def apiServiceTree = {
    TRAITDEF("ApiService") withTypeParams (
      TYPEVAR("T") UPPER (valueCache("RpcRequest"))
    ) := BLOCK(
        TYPEVAR("HandleResult") := REF("\\/") APPLYTYPE (
          "RpcError",
          "(RpcOk[T], Vector[Update])"
        ),
        DEF("handleRequest", valueCache("HandleResult")) withParams (
          PARAM("request", valueCache("T"))
        )
      )
  }

  def packageApiServiceTrees(packageName: String, items: Vector[Item]): Vector[Tree] = {
    val rpcs = items.filter(_.isInstanceOf[RpcContent])

    if (rpcs.isEmpty) {
      Vector.empty
    } else {
      val handlers: Vector[Tree] = rpcs map {
        case RpcContent(_, name, attributes, _) =>
          val params: Vector[ValDef] = attributes map { attr =>

            def scalaTyp(typ: Types.AttributeType): Type = typ match {
              case Types.Int32              => IntClass
              case Types.Int64              => LongClass
              case Types.Bool               => BooleanClass
              case Types.Double             => DoubleClass
              case Types.String             => StringClass
              case Types.Bytes              => arrayType(ByteClass)
              case Types.Enum(enumName)     => valueCache(enumName)
              case Types.Opt(optAttrType)   => optionType(scalaTyp(optAttrType))
              case Types.List(listAttrType) => vectorType(scalaTyp(listAttrType))
              case Types.Struct(structName) => valueCache(f"Refs.$structName%s")
              case Types.Trait(traitName)   => valueCache(f"Refs.$traitName%s")
            }

            PARAM(attr.name, scalaTyp(attr.typ)): ValDef
          }

          if (params.isEmpty) {
            DEF(f"handle$name%s()", valueCache("HandleResult")).tree
          } else {
            (DEF(f"handle$name%s", valueCache("HandleResult")) withParams (
              params
            )).tree
         }
      }

      val handleRequestDef = DEF("handleRequest", valueCache("HandleResult")) withParams(
        PARAM("request", valueCache(f"${packageName.capitalize}%sRpcRequest"))
      ) := BLOCK(
        REF("request") MATCH(
          rpcs map {
            case RpcContent(_, name, attributes, _) =>
              CASE(REF("r") withType(valueCache(f"Request$name%s"))) ==> (
                REF(f"handle$name%s") APPLY(
                  attributes map { attr =>
                    REF("r") DOT(attr.name)
                  }
                )
              )
          }
        )
      )

      Vector(
        TRAITDEF(f"${packageName.capitalize}ApiService")
          withParents (f"ApiService[${packageName.capitalize}%sRpcRequest]") := BLOCK(
            Vector(handleRequestDef) ++
              handlers
          )
      )
    }
  }
}
