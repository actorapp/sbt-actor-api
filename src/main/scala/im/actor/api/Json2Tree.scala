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
          case JsString("rpc") => rpcItemTrees(packageName, obj.fields("content"), aliases)
          case JsString("response") => responseItemTrees(packageName, obj.fields("content"), aliases)
          case JsString("update") => updateItemTrees(packageName, obj.fields("content"), aliases)
          case JsString("update_box") => updateBoxItemTrees(packageName, obj.fields("content"), aliases)
          case JsString("struct") => structItemTrees(packageName, obj.fields("content"), aliases)
          case JsString("enum") => enumItemTrees(packageName, obj.fields("content"), aliases)
          case JsString("trait") => traitItemTrees(packageName, obj.fields("content"), aliases)
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

  private def rpcItemTrees(packageName: String, obj: JsValue, aliases: Aliases): (Vector[Tree], Vector[Tree]) = obj match {
    case obj: JsObject =>
      val rpc = obj.convertTo[RpcContent]
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
            anonymousResponseItemTrees(packageName, rpc.name, resp, aliases)
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
    case _ => throw new Exception("rpc item is not a JsObject")
  }

  /**
    *
    *  @returns response builder ref and response definition trees
    */
  private def anonymousResponseItemTrees(
    packageName: String,
    name: String,
    resp: AnonymousRpcResponse,
    aliases: Aliases
  ): (Vector[Tree], Vector[Tree]) = {
    val className = f"Response$name%s"

    val params = paramsTrees(resp.attributes, aliases)

    val headerDef = dec2headerDef(resp.header)
    val serTrees = serializationTrees(packageName, className, resp.attributes, aliases)

    classWithCompanion(packageName, className, Vector(valueCache("RpcResponse")), params, Vector(headerDef) ++ serTrees)
  }

  private def responseItemTrees(packageName: String, value: JsValue, aliases: Aliases): (Vector[Tree], Vector[Tree]) = {
    value match {
      case obj @ JsObject(fields) =>
        fields.get("name") match {
          case Some(JsString(name)) =>
            val response = obj.convertTo[AnonymousRpcResponse]

            anonymousResponseItemTrees(packageName, name, response, aliases)
          case _ => deserializationError("Response attribute should have a name of type JsString")
        }
      case _ => deserializationError("Response attribute should be a JsObject")
    }
  }

  private def traitItemTrees(packageName: String, value: JsValue, aliases: Aliases): (Vector[Tree], Vector[Tree]) =
    value match {
      case obj: JsObject =>
        val trai = obj.convertTo[Trait]

        val globalRef = TYPEVAR(trai.name) := REF(f"$packageName%s.${trai.name}%s")

        (
          Vector(globalRef),
          Vector(TRAITDEF(trai.name))
        )
      case _ =>
        deserializationError("Trait item content should be a JsObject")
    }

  private def updateItemTrees(packageName: String, value: JsValue, aliases: Aliases): (Vector[Tree], Vector[Tree]) = value match {
    case obj: JsObject =>
      val update = obj.convertTo[Update]

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
    case _ => deserializationError("Update item should be a JsObject")
  }

  private def updateBoxItemTrees(packageName: String, value: JsValue, aliases: Aliases): (Vector[Tree], Vector[Tree]) = value match {
    case obj: JsObject =>
      val ub = obj.convertTo[UpdateBox]

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
    case _ => deserializationError("Update item should be a JsObject")
  }

  private def structItemTrees(packageName: String, value: JsValue, aliases: Aliases): (Vector[Tree], Vector[Tree]) = value match {
    case obj: JsObject =>
      val struct = obj.convertTo[Struct]

      val params = paramsTrees(struct.attributes, aliases)

      val serTrees = serializationTrees(
        packageName,
        struct.name,
        struct.attributes,
        aliases
      )

      val parents = struct.`trait` match {
        case Some(Trait(traitName)) =>
          Vector(typeRef(valueCache(traitName)))
        case None => Vector.empty
      }

      classWithCompanion(packageName, struct.name, parents, params, serTrees)
    case _ => deserializationError("Struct item should be a JsObject")
  }

  // TODO: use custom json formatters here
  private def enumItemTrees(packageName: String, value: JsValue, aliases: Aliases): (Vector[Tree], Vector[Tree]) = value match {
    case obj: JsObject =>
      obj.withField("name", "Enum") {
        case JsString(name) =>
          obj.withField("values") {
            case JsArray(jsValues) if (jsValues.length > 0) =>
              val values = jsValues map {
                case obj: JsObject =>
                  obj.withField("id") {
                    case JsNumber(id) =>
                      obj.withField("name") {
                        case JsString(valName) =>
                          VAL(valName) withType(valueCache(name)) := Apply(valueCache("Value"), LIT(id.toInt))
                        case _ => deserializationError("Enum value name should be a JsString")
                      }
                    case _ => deserializationError("Enum value id should be a JsNumber")
                  }
                case _ => deserializationError("Enum values should be JsObject")
              }

              val traitDef = TRAITDEF(name)

              val typeAlias = (TYPEVAR(name) := typeRef(valueCache("Value")))

              val objDef = OBJECTDEF(name) withParents("Enumeration", name) := BLOCK(
                Seq(typeAlias) ++ values
              )

              (
                Vector(
                  TYPEVAR(name) := REF(f"$packageName%s.$name%s.$name%s"),
                  VAL(name) := REF(f"$packageName%s.$name%s")
                ),
                Vector(traitDef, objDef)
              )
            case _ => deserializationError("Enum attributes should be a non-empty JsArray")
          }
        case _ =>
          deserializationError("Enum name should be a JsString")
      }
    case _ =>
      deserializationError("Enum item should be a JsObject")
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
