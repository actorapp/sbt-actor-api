package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._

trait ApiServiceTrees extends TreeHelpers {
  lazy val ScalazEitherType = definitions.getClass("\\/")

  val baseServiceTrees: Vector[Tree] = {
    Vector(
      TRAITDEF("Service") := BLOCK(
        TYPEVAR("HandleResult") := REF("\\/") APPLYTYPE(
          "RpcError",
          "RpcOk"),
        TYPEVAR("HandlerResult[A <: RpcResponse]") := REF("\\/") APPLYTYPE(
          "RpcError",
          "A"),
        VAL("handleRequestPartial", valueCache("PartialFunction[RpcRequest, ClientData => Future[HandleResult]]"))
      ),
      TRAITDEF("BaseClientData") := BLOCK(
        VAL("authId", LongClass)
      ),
      CASECLASSDEF("ClientData")
        withParams(PARAM("authId", LongClass), PARAM("optUserId", optionType(IntClass)))
        withParents (valueCache("BaseClientData")),
      CASECLASSDEF("AuthorizedClientData")
        withParams(PARAM("authId", LongClass), PARAM("userId", IntClass))
        withParents (valueCache("BaseClientData"))
    )
  }

  def packageApiServiceTrees(packageName: String, items: Vector[Item]): Vector[Tree] = {
    val rpcs = items.filter(_.isInstanceOf[RpcContent])

    if (rpcs.isEmpty) {
      Vector.empty
    } else {
      val handlers: Vector[Tree] = (rpcs map {
        case RpcContent(_, name, attributes, response) =>
          val params = attributes map { attr =>

            def scalaTyp(typ: Types.AttributeType): Type = typ match {
              case Types.Int32 => IntClass
              case Types.Int64 => LongClass
              case Types.Bool => BooleanClass
              case Types.Double => DoubleClass
              case Types.String => StringClass
              case Types.Bytes => arrayType(ByteClass)
              case Types.Enum(enumName) => valueCache(enumName)
              case Types.Opt(optAttrType) => optionType(scalaTyp(optAttrType))
              case Types.List(listAttrType) => vectorType(scalaTyp(listAttrType))
              case Types.Struct(structName) => valueCache(f"Refs.$structName%s")
              case Types.Trait(traitName) => valueCache(f"Refs.$traitName%s")
            }

            PARAM(attr.name, scalaTyp(attr.typ)): ValDef
          }

          val respType = response match {
            case _: AnonymousRpcResponse => f"Response$name%s"
            case named: NamedRpcResponse => f"Refs.Response${named.name}%s"
          }

          val hname = f"handle$name%s"

          val jhname = "j" + hname
          val htype = valueCache(f"Future[HandlerResult[$respType%s]]")

          // workaround for eed3si9n/treehugger#26
          val shname =
            if (params.isEmpty)
              hname + "()"
            else
              hname

          Vector(
            (DEF(jhname, htype).withParams(
              params.toVector :+ PARAM("clientData", valueCache("ClientData")).tree
            )).tree,
            DEF(shname, htype).withParams(
              params.toVector
            ).withParams(
                PARAM("clientData", valueCache("ClientData")).withFlags(Flags.IMPLICIT)
              ) := REF(jhname) APPLY (attributes.map(a => REF(a.name)) :+ REF("clientData"))
          )
      }).flatten

      val pfType = valueCache("PartialFunction[RpcRequest, ClientData => Future[HandleResult]]")
      val handleRequestDefPF = VAL("handleRequestPartial", pfType) withFlags(Flags.OVERRIDE) :=
        BLOCK(
        (rpcs map {
          case RpcContent(_, name, attributes, _) =>
            val rqParams: Vector[Tree] = attributes map { attr =>
              REF("r") DOT (attr.name): Tree
            }

            CASE(REF("r") withType (valueCache(f"Request$name%s"))) ==> (
              LAMBDA(PARAM("clientData", valueCache("ClientData"))) ==> BLOCK(
              VAL("f") := (if (rqParams.isEmpty)
                REF(f"jhandle$name%s") APPLY (REF("clientData"))
              else
                REF(f"jhandle$name%s") APPLY (rqParams :+ REF("clientData"))
                ),
              REF("f") DOT ("map") APPLY (BLOCK(
                CASE(REF("\\/-") APPLY (REF("rsp"))) ==> (
                  REF("\\/-") APPLY (REF("RpcOk") APPLY (REF("rsp")))
                  ),
                CASE(REF("err: -\\/[RpcError]")) ==> REF("err")
              ))
              )
            )

        }).toVector
      )

      val handleRequestDef = DEF("handleRequest", valueCache("Future[HandleResult]")) withParams(
        PARAM("clientData", valueCache("ClientData")),
        PARAM("request", valueCache(f"${packageName.capitalize}%sRpcRequest"))
        ) := BLOCK(
          REF("handleRequestPartial") APPLY(REF("request")) APPLY(REF("clientData"))
        )

      val ecDef: Tree = VAL("ec", valueCache("ExecutionContext")) withFlags (Flags.IMPLICIT)

      Vector(
        TRAITDEF(f"${packageName.capitalize}Service")
          withParents ("Service") := BLOCK(
          Vector(ecDef, handleRequestDefPF, handleRequestDef) ++
            handlers
        )
      )
    }
  }
}
