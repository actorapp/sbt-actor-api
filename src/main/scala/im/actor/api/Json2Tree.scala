package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._
import scala.language.postfixOps
import scala.collection.mutable
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

        val tree = PACKAGE("im.actor.api") := BLOCK(packageTrees :+ globalRefsTree)
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
            REF(f"Response$name%s"),
            (Vector.empty, Vector.empty)
          )
        case resp: AnonymousRpcResponse =>
          (
            REF(f"Response${rpc.name}%s"),
            anonymousResponseItemTrees(packageName, rpc.name, resp, aliases)
          )
      }

      val headerDef = dec2headerDef(rpc.header)
      val responseRefDef = VAL("Response") := responseRef

      val objectTrees = Vector(headerDef, responseRefDef) ++ serializationTrees(
        className,
        rpc.attributes
      )

      val (globalRequestRefs, requestTrees) = classWithCompanion(packageName, className, params, objectTrees)

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
    val serTrees = serializationTrees(className, resp.attributes)

    classWithCompanion(packageName, className, params, Vector(headerDef) ++ serTrees)
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
        className,
        update.attributes
      )

      classWithCompanion(packageName, className, params, Vector(headerDef) ++ serTrees)
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

      val traitOpt = struct.`trait` map {
        case Trait(traitName) =>
          valueCache(traitName)
      }

      if (params.isEmpty) {
        val obj = CASEOBJECTDEF(struct.name)
        traitOpt match {
          case Some(t) => obj.withParents(t)
          case None => obj
        }

        (
          Vector(VAL(struct.name) := REF(f"$packageName%s.${struct.name}%s")),
          Vector(obj)
        )
      } else {
        val cls = CASECLASSDEF(struct.name) withParams(params)
        traitOpt match {
          case Some(t) => cls.withParents(t)
          case None => cls
        }
        (
          Vector(TYPEVAR(struct.name) := REF(f"$packageName%s.${struct.name}%s")),
          Vector(cls)
        )
      }
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

              val typeAlias = (TYPEVAR(name) := typeRef(valueCache("Value")))

              val obj = OBJECTDEF(name) withParents("Enumeration") := BLOCK(
                Seq(typeAlias) ++ values
              )

              (
                Vector(
                  TYPEVAR(name) := REF(f"$packageName%s.$name%s.$name%s"),
                  VAL(name) := REF(f"$packageName%s.$name%s")
                ),
                Vector(obj)
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
      valueCache(traitName)
    case AttributeType(name, None) =>
      valueCache(f"Refs.$name%s")
  }

  private def paramsTrees(attributes: Vector[Attribute], aliases: Aliases): Vector[ValDef] = {
    attributes.sortBy(_.id) map { attr =>
      PARAM(attr.name, attrType(attr.typ, aliases)).tree
    }
  }

  private def classWithCompanion(
    packageName: String,
    name: String,
    params: Vector[ValDef],
    objectTrees: Vector[Tree]
  ): (Vector[Tree], Vector[Tree]) = {
    val ref = REF(f"$packageName%s.$name%s")
    val objRef = VAL(name) := ref

    val objBlock = BLOCK(objectTrees)

    if (params.isEmpty) {
      (
        Vector(objRef),
        Vector(CASEOBJECTDEF(name) := objBlock)
      )
    } else {
      (
        Vector(
          TYPEVAR(name) := ref, objRef
        ),
        Vector(
          CASECLASSDEF(name) withParams(params),
          OBJECTDEF(name) := objBlock
        )
      )
    }
  }
}
