package im.actor.api

import scala.util.{ Try, Success, Failure }
import spray.json._

case class Alias(`type`: String, alias: String) {
  val typ = `type`
}

case class AttributeType(`type`: String, childType: Option[AttributeType]) {
  val typ = `type`
}
case class Attribute(`type`: AttributeType, id: Int, name: String) {
  val typ = `type`
}

trait RpcResponse
case class AnonymousRpcResponse(header: Int, attributes: Vector[Attribute]) extends RpcResponse
case class ReferenceRpcResponse(name: String) extends RpcResponse

case class RpcContent(header: Int, name: String, attributes: Vector[Attribute], response: RpcResponse)

case class Trait(name: String)
case class UpdateBox(name: String, header: Int, attributes: Vector[Attribute])

case class Update(name: String, header: Int, attributes: Vector[Attribute])

case class Struct(name: String, attributes: Vector[Attribute], `trait`: Option[Trait])

trait JsonFormats extends DefaultJsonProtocol {
  implicit val traitFormat = jsonFormat1(Trait)
  implicit object aliasFormat extends RootJsonFormat[Alias] {
    def write(typ: Alias): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case JsObject(fields) =>
        val optAlias = for {
          jsAlias <- fields.get("alias")
          jsTyp <- fields.get("type")
        } yield {
          jsAlias match {
            case JsString(alias) =>
              jsTyp match {
                case JsString(typ) =>
                  Alias(typ, alias)
                case _ =>
                  deserializationError("Alias type should be a JsString")
              }
            case _ =>
              deserializationError("Alias alias should be a JsString")
          }
        }

        optAlias getOrElse deserializationError("Both type and alias fields are required for an alias")
      case _ =>
        deserializationError("Alias should be a JsObject")
    }
  }
  implicit object attributeTypeFormat extends RootJsonFormat[AttributeType] {
    def write(typ: AttributeType): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case JsString(typName) => AttributeType(typName, None)
      case obj: JsObject =>
        val optAttributeType = for {
          jsTypName <- obj.fields.get("type")
          jsTyp <- obj.fields.get("childType")
        } yield {
          jsTypName match {
            case JsString(typName) =>
              AttributeType(typName, Some(read(jsTyp)))
            case _ =>
              deserializationError("Attribute type should be a JsString")
          }
        }

        optAttributeType getOrElse (deserializationError("Both type and childType fields are required for attribute type"))
      case _ =>
        deserializationError("Attribute type should be JsString or JsObject")
    }
  }
  implicit object attributeFormat extends RootJsonFormat[Attribute] {
    def write(attr: Attribute): JsValue = throw new NotImplementedError()

    def read(value: JsValue): Attribute = value match {
      case obj: JsObject =>
        val optAttribute = for {
          jsTyp <- obj.fields.get("type")
          jsId <- obj.fields.get("id")
          jsName <- obj.fields.get("name")
        } yield {
          jsId match {
            case JsNumber(id) =>
              jsName match {
                case JsString(name) =>
                  val typ = attributeTypeFormat.read(jsTyp)
                  Attribute(typ, id.toInt, name)
                case _ => deserializationError("Attribute name should be JsString")
              }
            case _ => deserializationError("Attribute type should be JsNumber")
          }
        }

        optAttribute getOrElse (deserializationError("Not enough fields for attribute"))
      case _ => deserializationError("Attribute should be a JsObject")
    }
  }

  implicit val anonymousRpcResponseFormat = jsonFormat2(AnonymousRpcResponse)
  implicit val referenceRpcResponseFormat = jsonFormat1(ReferenceRpcResponse)

  implicit object rpcResponseFormat extends RootJsonFormat[RpcResponse] {
    def write(attr: RpcResponse): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case obj: JsObject =>
        obj.fields.get("type") match {
          case Some(JsString(typ)) =>
            readTyped(typ, obj)
          case _ =>
            deserializationError("RpcResponse type should be present and be a JsString")
        }

      case _ =>
        deserializationError("RpcResponse should be a JsObject")
    }

    def readTyped(typ: String, obj: JsObject): RpcResponse = {
      typ match {
        case "anonymous" =>
          /*
          val optHeader = obj.fields.get("header") map {
            case JsNumber(header) => header.toInt
            case _ => deserializationError("RpcResponse header should be JsString")
          }

          val optAttributes: Option[Vector[Attribute]] = obj.fields.get("attributes") map {
            case JsArray(fields) =>
              fields map attributeFormat.read
            case _ => deserializationError("RpcResponse attributes should be JsArray")
          }

          AnonymousRpcResponse(optHeader, optAttributes getOrElse (Vector.empty))
           */
          anonymousRpcResponseFormat.read(obj)
        case "reference" =>
          referenceRpcResponseFormat.read(obj)
      }
    }
  }

  implicit object structFormat extends RootJsonFormat[Struct] {
    def write(attr: Struct): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case obj: JsObject =>
        obj.getFields("name", "attributes") match {
          case Seq(JsString(name), JsArray(jsAttributes)) =>
            val attributes = jsAttributes map attributeFormat.read

            val traitOpt: Option[Trait] = obj.fields.get("trait") map {
              case JsString(t) => Trait(t)
              case obj: JsObject => traitFormat.read(obj)
              case _ => deserializationError("Expecting trait to be a JsString or JsObject")
            }

            Struct(name, attributes, traitOpt)
          case _ => deserializationError("Both name and attributes are required for Struct")
        }

      case _ =>
        deserializationError("RpcResponse should be a JsObject")
    }
  }

  implicit val rpcContentFormat = jsonFormat4(RpcContent)
  implicit val updateBoxFormat = jsonFormat3(UpdateBox)
  implicit val updateFormat = jsonFormat3(Update)
}
