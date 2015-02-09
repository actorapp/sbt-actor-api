package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._
import scala.collection. mutable
import spray.json._, DefaultJsonProtocol._

object Json2Tree extends JsonFormats with JsonHelpers with SerializationTrees {
  type Aliases = Map[String, String]

  def convert(jsonString: String): String = {
    val jsonAst = jsonString.parseJson
    val rootObj = jsonAst.convertTo[JsObject]

    rootObj.withField("aliases") {
      case JsArray(jsAliases) =>
        val aliases: Aliases = jsAliases.map { jsAlias =>
          val alias = aliasFormat.read(jsAlias)
          (alias.alias, alias.typ)
        }.toMap

        val packageTrees: Vector[Tree] = rootObj.fields("sections").convertTo[JsArray].elements map {
          case obj: JsObject =>
            obj.fields("package") match {
              case JsString(packageName) =>
                PACKAGE(packageName) := itemsBlock(obj.fields("items").convertTo[JsArray].elements, aliases)
              case _ =>
                throw new Exception("package field is not a JsString")
            }
          case _ =>
            throw new Exception("section is not a JsObject")
        }

        val tree = PACKAGE("im.actor.api") := BLOCK(packageTrees)
        treeToString(tree)
      case _ => deserializationError("Aliases should be JsArray")
    }
  }

  private def itemsBlock(jsonElements: Vector[JsValue], aliases: Aliases): Tree = {
    val elements = jsonElements map {
      case obj: JsObject =>
        obj.fields("type") match {
          case JsString("rpc") => rpcItemTrees(obj.fields("content"), aliases)
          case JsString("response") => responseItemTrees(obj.fields("content"), aliases)
          case JsString("update") => updateItemTrees(obj.fields("content"), aliases)
          case JsString("update_box") => Vector(updateBoxItemTree(obj.fields("content"), aliases))
          case JsString("struct") => Vector(structItemTree(obj.fields("content"), aliases))
          case JsString("enum") => Vector(enumItemTree(obj.fields("content"), aliases))
          case JsString("trait") => Vector(traitItemTree(obj.fields("content"), aliases))
          case JsString("comment") => Vector.empty
          case JsString("empty") => Vector.empty
          case JsString(typ) => throw new Exception(f"Unsupported item: $typ%s")
          case _ => throw new Exception("Item type is not a JsString")
        }
      case _ =>
        throw new Exception("item is not a JsObject")
    }

    BLOCK(elements.flatten)
  }

  // TODO: hex
  private def dec2headerDef(decHeader: Int): Tree =
    VAL("header") := LIT(decHeader)

  private def rpcItemTrees(obj: JsValue, aliases: Aliases): Vector[Tree] = obj match {
    case obj: JsObject =>
      val rpc = obj.convertTo[RpcContent]
      val className = f"Request${rpc.name}%s"

      val params = paramsTrees(rpc.attributes, aliases)

      val requestTree = caseClassOrObject(className, params)
      val (responseRef, responseTrees) = rpc.response match {
        case ReferenceRpcResponse(name) =>
          (
            REF(f"Response$name%s"),
            Vector.empty
          )
        case resp: AnonymousRpcResponse =>
          (
            REF(f"Response${rpc.name}%s"),
            anonymousResponseItemTrees(rpc.name, resp, aliases)
          )
      }

      val headerDef = dec2headerDef(rpc.header)
      val responseRefDef = VAL("Response") := responseRef

      Vector(requestTree, companionObjectTree(
        className,
        rpc.attributes,
        Vector(headerDef, responseRefDef))
      ) ++ responseTrees
    case _ => throw new Exception("rpc item is not a JsObject")
  }

  /**
    *
    *  @returns response builder ref and response definition trees
    */
  private def anonymousResponseItemTrees(name: String, resp: AnonymousRpcResponse, aliases: Aliases): Vector[Tree] = {
    val className = f"Response$name%s"

    val params = paramsTrees(resp.attributes, aliases)
    val headerDef = dec2headerDef(resp.header)

    Vector(
      caseClassOrObject(className, params),
      companionObjectTree(className, resp.attributes, Vector(headerDef))
    )
  }

  private def responseItemTrees(value: JsValue, aliases: Aliases): Vector[Tree] = {
    value match {
      case obj @ JsObject(fields) =>
        fields.get("name") match {
          case Some(JsString(name)) =>
            val response = obj.convertTo[AnonymousRpcResponse]
            anonymousResponseItemTrees(name, response, aliases)
          case _ => deserializationError("Response attribute should have a name of type JsString")
        }
      case _ => deserializationError("Response attribute should be a JsObject")
    }
  }

  private def traitItemTree(value: JsValue, aliases: Aliases): Tree = value match {
    case obj: JsObject =>
      val trai = obj.convertTo[Trait]
      TRAITDEF(trai.name)
    case _ =>
      deserializationError("Trait item content should be a JsObject")
  }

  private def updateItemTrees(value: JsValue, aliases: Aliases): Vector[Tree] = value match {
    case obj: JsObject =>
      val update = obj.convertTo[Update]

      val className = f"Update${update.name}%s"
      val params = paramsTrees(update.attributes, aliases)
      val headerDef = dec2headerDef(update.header)

      Vector(
        caseClassOrObject(className, params),
        companionObjectTree(
          className,
          update.attributes,
          Vector(headerDef)
        )
      )
    case _ => deserializationError("Update item should be a JsObject")
  }

  private def updateBoxItemTree(value: JsValue, aliases: Aliases): Tree = value match {
    case obj: JsObject =>
      val ub = obj.convertTo[UpdateBox]

      val params = paramsTrees(ub.attributes, aliases)

      if (params.isEmpty) {
        CASEOBJECTDEF(ub.name) withParents(newOrCachedSym("UpdateBox"))
      } else {
        CASECLASSDEF(ub.name) withParents(newOrCachedSym("UpdateBox")) withParams(params)
      }
    case _ => deserializationError("Update item should be a JsObject")
  }

  private def structItemTree(value: JsValue, aliases: Aliases): Tree = value match {
    case obj: JsObject =>
      val struct = obj.convertTo[Struct]

      val params = paramsTrees(struct.attributes, aliases)

      val traitOpt = struct.`trait` map {
        case Trait(traitName) =>
          newOrCachedSym(traitName)
      }

      if (params.isEmpty) {
        val obj = CASEOBJECTDEF(struct.name)
        traitOpt match {
          case Some(t) => obj.withParents(t)
          case None => obj
        }
      } else {
        val cls = CASECLASSDEF(struct.name) withParams(params)
        traitOpt match {
          case Some(t) => cls.withParents(t)
          case None => cls
        }
      }
    case _ => deserializationError("Struct item should be a JsObject")
  }

  // TODO: use custom json formatters here
  private def enumItemTree(value: JsValue, aliases: Aliases): Tree = value match {
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
                          VAL(valName) withType(newOrCachedSym(name)) := Apply(newOrCachedSym("Value"), LIT(id.toInt))
                        case _ => deserializationError("Enum value name should be a JsString")
                      }
                    case _ => deserializationError("Enum value id should be a JsNumber")
                  }
                case _ => deserializationError("Enum values should be JsObject")
              }

              val typeAlias = (TYPEVAR(name) := typeRef(newOrCachedSym(name)))

              OBJECTDEF(name) withParents("Enumeration") := BLOCK(
                Seq(typeAlias) ++ values
              )
            case _ => deserializationError("Enum attributes should be a non-empty JsArray")
          }
        case _ =>
          deserializationError("Enum name should be a JsString")
      }
    case _ =>
      deserializationError("Enum item should be a JsObject")
  }

  private def attrType(typ: AttributeType, aliases: Aliases): Type = typ match {
    case AttributeType("int32", None) => IntClass
    case AttributeType("int64", None) => LongClass
    case AttributeType("double", None) => DoubleClass
    case AttributeType("string", None) => StringClass
    case AttributeType("bool", None) => BooleanClass
    case AttributeType("struct", Some(child)) =>
      attrType(child, aliases)
    case AttributeType("enum", Some(child)) =>
      attrType(child, aliases)
    case AttributeType("list", Some(child)) =>
      listType(attrType(child, aliases))
    case AttributeType("opt", Some(child)) =>
      optionType(attrType(child, aliases))
    case AttributeType("alias", Some(AttributeType(aliasName, None))) =>
      aliases.get(aliasName) match {
        case Some(typ) => attrType(AttributeType(typ, None), aliases)
        case None => throw new Exception(f"Alias $aliasName%s is missing")
      }
    case AttributeType("trait", Some(AttributeType(traitName, None))) =>
      newOrCachedSym(traitName)
    case AttributeType(name, None) =>
      newOrCachedSym(name)
  }

  private def paramsTrees(attributes: Vector[Attribute], aliases: Aliases): Vector[ValDef] = {
    attributes.sortBy(_.id) map { attr =>
      PARAM(attr.name, attrType(attr.typ, aliases)).tree
    }
  }

  private def caseClassOrObject(name: String, params: Vector[ValDef]): Tree = {
    if (params.length > 0) {
      CASECLASSDEF(name) withParams(params)
    } else {
      CASEOBJECTDEF(name)
    }
  }
}
