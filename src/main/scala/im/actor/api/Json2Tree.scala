package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._
import scala.language.postfixOps
import scala.collection.mutable
import spray.json._, DefaultJsonProtocol._

object Json2Tree extends JsonFormats with JsonHelpers with SerializationTrees {
  def convert(jsonString: String): String = {
    val jsonAst = jsonString.parseJson
    val rootObj = jsonAst.convertTo[JsObject]

    rootObj.withField("aliases") {
      case JsArray(jsAliases) =>
        val aliases: Aliases = jsAliases.map { jsAlias =>
          val alias = aliasFormat.read(jsAlias)
          (alias.alias, alias.typ)
        }.toMap

        val refsTreesSeq: Vector[(Vector[Tree], Tree)] = rootObj.fields("sections").convertTo[JsArray].elements map {
          case obj: JsObject =>
            obj.fields("package") match {
              case JsString(packageName) =>
                val (globalRefs, block) = itemsBlock(packageName, obj.fields("items").convertTo[JsArray].elements, aliases)

                (globalRefs, PACKAGE(packageName) := block)
              case _ =>
                throw new Exception("package field is not a JsString")
            }
          case _ =>
            throw new Exception("section is not a JsObject")
        }

        val (globalRefsV, packageTrees) = refsTreesSeq.unzip

        val globalRefsTree = OBJECTDEF("Refs") := BLOCK(globalRefsV.flatten)

        val updateBoxDef = TRAITDEF("UpdateBox").tree
        val updateDef = TRAITDEF("Update").tree
        val rpcRequestDef = TRAITDEF("RpcRequest").tree
        val rpcResponseDef = TRAITDEF("RpcResponse").tree

        val tree = PACKAGE("im.actor.api") := BLOCK(
          packageTrees ++ Vector(
            globalRefsTree, parseExceptionDef, updateBoxDef, updateDef, rpcRequestDef, rpcResponseDef
          ))
        treeToString(tree)
      case _ => deserializationError("Aliases should be JsArray")
    }
  }

  private def itemsBlock(packageName: String, jsonElements: Vector[JsValue], aliases: Aliases): (Vector[Tree], Tree) = {
    val refsTreesSeq: Vector[(Vector[Tree], Vector[Tree])] = jsonElements map {
      case obj: JsObject =>
        obj.fields("type") match {
          case JsString("rpc") =>
            obj.withObjectField("content") { o =>
              val rpc = o.convertTo[RpcContent]
              rpcItemTrees(packageName, rpc, aliases)
            }
          case JsString("response") =>
            obj.withObjectField("content") { o =>
              val response = o.convertTo[NamedRpcResponse]
              namedResponseItemTrees(packageName, response, aliases)
            }
          case JsString("update") =>
            obj.withObjectField("content") { o =>
              val update = o.convertTo[Update]
              updateItemTrees(packageName, update, aliases)
            }
          case JsString("update_box") =>
            obj.withObjectField("content") { o =>
              val ub = o.convertTo[UpdateBox]
              updateBoxItemTrees(packageName, ub, aliases)
            }
          case JsString("struct") =>
            obj.withObjectField("content") { o =>
              val struct = o.convertTo[Struct]
              structItemTrees(packageName, struct, aliases)
            }
          case JsString("enum") =>
            obj.withObjectField("content") { o =>
              val enum = o.convertTo[Enum]
              enumItemTrees(packageName, enum, aliases)
            }
          case JsString("trait") =>
            obj.withObjectField("content") { o =>
              val trai = o.convertTo[Trait]
              traitItemTrees(packageName, trai, aliases)
            }
          case JsString("comment") => (Vector.empty, Vector.empty)
          case JsString("empty") => (Vector.empty, Vector.empty)
          case JsString(typ) => throw new Exception(f"Unsupported item: $typ%s")
          case _ => throw new Exception("Item type is not a JsString")
        }
      case _ =>
        throw new Exception("item is not a JsObject")
    }

    val (globalRefsV, elementsV) = refsTreesSeq.unzip

    (globalRefsV.flatten, BLOCK(elementsV.flatten))
  }

  // TODO: hex
  private def dec2headerDef(decHeader: Int): Tree =
    VAL("header") := LIT(decHeader)

  private def rpcItemTrees(packageName: String, rpc: RpcContent, aliases: Aliases): (Vector[Tree], Vector[Tree]) = {
    val className = f"Request${rpc.name}%s"

    val params = paramsTrees(rpc.attributes, aliases)

    val (responseRef, (globalResponseRefs, responseTrees)) = rpc.response match {
      case ReferenceRpcResponse(name) =>
        (
          REF(f"Refs.Response$name%s"),
          (Vector.empty, Vector.empty)
        )
      case resp: AnonymousRpcResponse =>
        (
          REF(f"Refs.Response${rpc.name}%s"),
          namedResponseItemTrees(packageName, resp.toNamed(rpc.name), aliases)
        )
    }

    val headerDef = dec2headerDef(rpc.header)
    val responseRefDef = VAL("Response") := responseRef

    val serTrees = serializationTrees(
      packageName,
      className,
      rpc.attributes,
      aliases
    )

    val objectTrees = Vector(headerDef, responseRefDef) ++ serTrees

    val (globalRequestRefs, requestTrees) = classWithCompanion(packageName, className, Vector(valueCache("RpcRequest")), params, objectTrees)

    (
      globalRequestRefs ++ globalResponseRefs,
      requestTrees ++ responseTrees
    )
  }

  /**
    *
    *  @returns response builder ref and response definition trees
    */
  private def namedResponseItemTrees(
    packageName: String,
    resp: NamedRpcResponse,
    aliases: Aliases
  ): (Vector[Tree], Vector[Tree]) = {
    val className = f"Response${resp.name}%s"

    val params = paramsTrees(resp.attributes, aliases)

    val headerDef = dec2headerDef(resp.header)
    val serTrees = serializationTrees(packageName, className, resp.attributes, aliases)

    classWithCompanion(packageName, className, Vector(valueCache("RpcResponse")), params, Vector(headerDef) ++ serTrees)
  }

  private def traitItemTrees(packageName: String, trai: Trait, aliases: Aliases): (Vector[Tree], Vector[Tree]) = {
    val globalRef = TYPEVAR(trai.name) := REF(f"$packageName%s.${trai.name}%s")

    (
      Vector(globalRef),
      Vector(TRAITDEF(trai.name))
    )
  }

  private def updateItemTrees(packageName: String, update: Update, aliases: Aliases): (Vector[Tree], Vector[Tree]) = {
    val className = f"Update${update.name}%s"
    val params = paramsTrees(update.attributes, aliases)
    val headerDef = dec2headerDef(update.header)

    val serTrees = serializationTrees(
      packageName,
      className,
      update.attributes,
      aliases
    )

    classWithCompanion(packageName, className, Vector(valueCache("Update")), params, Vector(headerDef) ++ serTrees)
  }

  private def updateBoxItemTrees(packageName: String, ub: UpdateBox, aliases: Aliases): (Vector[Tree], Vector[Tree]) = {
    val params = paramsTrees(ub.attributes, aliases)

    if (params.isEmpty) {
      (
        Vector(VAL(ub.name) := REF(f"$packageName%s.${ub.name}%s")),
        Vector(CASEOBJECTDEF(ub.name) withParents(valueCache("UpdateBox")))
      )
    } else {
      (
        Vector(TYPEVAR(ub.name) := REF(f"$packageName%s.${ub.name}%s")),
        Vector(CASECLASSDEF(ub.name) withParents(valueCache("UpdateBox")) withParams(params))
      )
    }
  }

  private def structItemTrees(packageName: String, struct: Struct, aliases: Aliases): (Vector[Tree], Vector[Tree]) = {
    val params = paramsTrees(struct.attributes, aliases)

    val serTrees = serializationTrees(
      packageName,
      struct.name,
      struct.attributes,
      aliases
    )

    val parents = struct.`trait` match {
      case Some(TraitExt(traitName, traitKey)) =>
        Vector(typeRef(valueCache(traitName)))
      case None => Vector.empty
    }

    classWithCompanion(packageName, struct.name, parents, params, serTrees)
  }

  // TODO: use custom json formatters here
  private def enumItemTrees(packageName: String, enum: Enum, aliases: Aliases): (Vector[Tree], Vector[Tree]) = {
    if (enum.values.length > 0) {
      val valuesTrees = enum.values map {
        case EnumValue(id, name) =>
          VAL(name) withType(valueCache(enum.name)) := Apply(valueCache("Value"), LIT(id))
      }

      val traitDef = TRAITDEF(enum.name)

      val typeAlias = (TYPEVAR(enum.name) := typeRef(valueCache("Value")))

      val objDef = OBJECTDEF(enum.name) withParents("Enumeration", enum.name) := BLOCK(
        Seq(typeAlias) ++ valuesTrees
      )

      (
        Vector(
          TYPEVAR(enum.name) := REF(f"$packageName%s.${enum.name}%s.${enum.name}%s"),
          VAL(enum.name) := REF(f"$packageName%s.${enum.name}%s")
        ),
        Vector(traitDef, objDef)
      )
    } else {
      deserializationError("Enum attributes should be a non-empty JsArray")
    }
  }

  private def paramsTrees(attributes: Vector[Attribute], aliases: Aliases): Vector[ValDef] = {
    attributes.sortBy(_.id) map { attr =>
      PARAM(attr.name, attrType(attr.typ, aliases)).tree
    }
  }

  private def classWithCompanion(
    packageName: String,
    name: String,
    parents: Vector[Type],
    params: Vector[ValDef],
    objectTrees: Vector[Tree]
  ): (Vector[Tree], Vector[Tree]) = {
    val ref = REF(f"$packageName%s.$name%s")
    val objRef = VAL(name) := ref

    val objBlock = BLOCK(objectTrees)

    if (params.isEmpty) {
      (
        Vector(objRef),
        Vector(
          TRAITDEF(name) withParents(parents),
          CASEOBJECTDEF(name) withParents(valueCache(name)) := objBlock
        )
      )
    } else {
      (
        Vector(
          TYPEVAR(name) := ref, objRef
        ),
        Vector(
          CASECLASSDEF(name) withParents(parents) withParams(params),
          OBJECTDEF(name) := objBlock
        )
      )
    }
  }
}
