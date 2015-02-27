package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._

trait RequestResponseCodecTrees extends TreeHelpers {
  def requestResponseCodecTrees(packages: Vector[(String, Vector[Item])]): Tree = {
    val (requests, responses) = packages.foldLeft[(Vector[(RpcContent, String)], Vector[(NamedRpcResponse, String)])]((Vector.empty, Vector.empty)) {
      case (acc, (packageName, items)) =>
        val newItems = items.foldLeft[(Vector[(RpcContent, String)], Vector[(NamedRpcResponse, String)])]((Vector.empty, Vector.empty)) {
          case ((rqAcc, rspAcc), r: RpcContent) =>
            val rq = (r, packageName)
            r.response match {
              case rsp: AnonymousRpcResponse => (rqAcc :+ rq, rspAcc :+ (rsp.toNamed(r.name), packageName))
              case _                         => (rqAcc :+ rq, rspAcc)
            }
          case ((rqAcc, rspAcc), r: RpcResponseContent) =>
            (rqAcc, rspAcc :+ (r, packageName))
          case (acc, r) =>
            acc
        }
        (
          acc._1 ++ newItems._1,
          acc._2 ++ newItems._2
        )
    }

    PACKAGEOBJECTDEF("codecs") := BLOCK(
      Vector(
        IMPORT("im.actor.server.mtproto.codecs._"),
        IMPORT("com.google.protobuf.CodedInputStream"),
        IMPORT("scodec.bits._"),
        IMPORT("scodec._"),
        IMPORT("scodec.codecs._"),

        DEF("protoPayload[A]") withParams (PARAM("payloadCodec", valueCache("Codec[A]"))) :=
          NEW(REF("PayloadCodec[A]")) APPLY (REF("payloadCodec"))
      ) ++ requestCodecTrees(requests) ++ responseCodecTrees(responses)
    )
  }

  private def requestCodecTrees(requests: Vector[(RpcContent, String)]): Vector[Tree] = {
    val rqCodecs = requests map {
      case (RpcContent(_, name, _, _), packageName) =>
        val rqType = f"$packageName%s.Request$name%s"

        codecTree(packageName, name, "request")
    }

    val rpcRqCodec = VAL("rpcRequestCodec") :=
    requests.foldLeft[Tree](REF("discriminated[RpcRequest]") DOT ("by") APPLY (REF("uint32"))) {
      case (acc, (request, packageName)) =>
        acc DOT ("typecase") APPLY (
          REF(f"$packageName%s.Request${request.name}%s.header.toLong"),
          REF("PayloadCodec") APPLY(
            REF(f"request${request.name}%sCodec")
          )
        )
    }

    rqCodecs :+ rpcRqCodec
  }

  private def responseCodecTrees(responses: Vector[(NamedRpcResponse, String)]): Vector[Tree] = {
    val rspCodecs = responses map {
      case (response, packageName) =>
        val rspType = f"$packageName%s.Response${response.name}%s"

        codecTree(packageName, response.name, "response")
    }

    val rpcRspCodec = VAL("rpcResponseCodef") :=
    responses.foldLeft[Tree](REF("discriminated[RpcResponse]") DOT ("by") APPLY (REF("uint32"))) {
      case (acc, (response, packageName)) =>
        acc DOT ("typecase") APPLY (
          REF(f"$packageName%s.Response${response.name}%s.header.toLong"),
          REF("PayloadCodec") APPLY(
            REF(f"response${response.name}%sCodec")
          )
        )
    }

    rspCodecs :+ rpcRspCodec
  }

  private def codecTree(packageName: String, name: String, prefix: String): Tree = {
    val typ = f"$packageName%s.${prefix.capitalize}%s$name%s"

    OBJECTDEF(f"$prefix%s$name%sCodec") withParents (f"Codec[$typ%s]") := BLOCK(
      DEF("sizeBound") := REF("SizeBound") DOT ("unknown"),
      DEF("encode") withParams (PARAM("r", valueCache(typ))) :=
        REF("Attempt") DOT ("successful") APPLY (
          REF("BitVector") APPLY (
            REF("r") DOT ("toByteArray")
          )
        ),
      DEF("decode") withParams (PARAM("bv", valueCache("BitVector"))) :=
        REF(typ) DOT ("parseFrom") APPLY (
          REF("CodedInputStream") DOT ("newInstance") APPLY (REF("bv") DOT ("toByteBuffer"))
        ) MATCH (
            CASE(REF("Left") APPLY (REF("partial"))) ==> (
              REF("Attempt") DOT ("failure") APPLY (REF("Err") APPLY (REF("partial") DOT ("toString")))
            ),
              CASE(REF("Right") APPLY (REF("r"))) ==> (
                REF("Attempt.successful") APPLY (REF("DecodeResult") APPLY (REF("r"), REF("BitVector") DOT ("empty")))
              )
          )
    )
  }
}
