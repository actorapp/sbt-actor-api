package im.actor.api

import scala.util.{ Try, Success, Failure }
import spray.json._

case class Alias(`type`: String, alias: String) {
  val typ = `type`
}

object Types {
  trait AttributeType

  case object Int32 extends AttributeType
  case object Int64 extends AttributeType
  case object Double extends AttributeType
  case object String extends AttributeType
  case object Bool extends AttributeType
  case object Bytes extends AttributeType

  case class Struct(_name: String) extends AttributeType {
    val name = ApiPrefix + _name
  }
  case class Enum(_name: String) extends AttributeType {
    val name = ApiPrefix + _name
  }
  case class List(typ: AttributeType) extends AttributeType
  case class Opt(typ: AttributeType) extends AttributeType
  case class Trait(_name: String) extends AttributeType {
    val name = ApiPrefix + _name
  }

}

case class Attribute(`type`: Types.AttributeType, id: Int, name: String) {
  val typ = `type`

  def withType(typ: Types.AttributeType) = copy(`type` = typ)
}

trait Named {
  def name: String
}

trait Item {
  def traitExt: Option[TraitExt]
}

trait NamedItem extends Item with Named

trait RpcResponse
case class AnonymousRpcResponse(header: Int, attributes: Vector[Attribute]) extends Item with RpcResponse {
  def traitExt = None
  def toNamed(name: String) = RpcResponseContent(name, header, attributes)
}

trait NamedRpcResponse extends NamedItem with RpcResponse

case class ReferenceRpcResponse(name: String) extends NamedRpcResponse {
  def traitExt = None
}
case class RpcResponseContent(name: String, header: Int, attributes: Vector[Attribute]) extends NamedRpcResponse {
  def traitExt = None
}

case class RpcContent(header: Int, name: String, attributes: Vector[Attribute], response: RpcResponse) extends NamedItem {
  def traitExt = None
}

case class Trait(_name: String) extends NamedItem {
  val name = ApiPrefix + _name
  def traitExt = None
}
case class TraitExt(_name: String, key: Int) {
  val name = ApiPrefix + _name
}

case class UpdateBox(name: String, header: Int, attributes: Vector[Attribute]) extends NamedItem {
  def traitExt = None
}

case class Update(name: String, header: Int, attributes: Vector[Attribute]) extends NamedItem {
  def traitExt = None
}

case class Struct(_name: String, attributes: Vector[Attribute], `trait`: Option[TraitExt]) extends NamedItem {
  val name = ApiPrefix + _name
  def traitExt = `trait`
}

case class Enum(_name: String, values: Vector[EnumValue]) extends NamedItem {
  val name = ApiPrefix + _name
  def traitExt = None
}
case class EnumValue(id: Int, name: String)

trait JsonFormats extends DefaultJsonProtocol with Hacks {
  val aliases: Map[String, String]

  implicit val traitFormat = jsonFormat[String, Trait](Trait.apply, "name")
  implicit val traitExtFormat = jsonFormat[String, Int, TraitExt](TraitExt.apply, "name", "key")

  implicit val enumValueFormat = jsonFormat2(EnumValue)
  implicit val enumFormat = jsonFormat[String, Vector[EnumValue], Enum](Enum.apply, "name", "values")

  implicit object aliasFormat extends RootJsonFormat[Alias] {
    def write(typ: Alias): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case JsObject(fields) ⇒
        val optAlias = for {
          jsAlias ← fields.get("alias")
          jsTyp ← fields.get("type")
        } yield {
          jsAlias match {
            case JsString(alias) ⇒
              jsTyp match {
                case JsString(typ) ⇒
                  Alias(typ, alias)
                case _ ⇒
                  deserializationError("Alias type should be a JsString")
              }
            case _ ⇒
              deserializationError("Alias alias should be a JsString")
          }
        }

        optAlias getOrElse deserializationError("Both type and alias fields are required for an alias")
      case _ ⇒
        deserializationError("Alias should be a JsObject")
    }
  }

  implicit object attributeTypeFormat extends RootJsonFormat[Types.AttributeType] {
    def write(typ: Types.AttributeType): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case JsString(typName) ⇒ typName match {
        case "int32"     ⇒ Types.Int32
        case "int64"     ⇒ Types.Int64
        case "double"    ⇒ Types.Double
        case "string"    ⇒ Types.String
        case "bool"      ⇒ Types.Bool
        case "bytes"     ⇒ Types.Bytes
        case unsupported ⇒ deserializationError(s"Unknown type $unsupported%s")
      }

      case obj: JsObject ⇒
        obj.getFields("type", "childType") match {
          case Seq(JsString("struct"), JsString(structName)) ⇒
            Types.Struct("Api" + structName)
          case Seq(JsString("enum"), JsString(enumName)) ⇒
            Types.Enum("Api" + enumName)
          case Seq(JsString("list"), childType) ⇒
            Types.List(read(childType))
          case Seq(JsString("opt"), childType) ⇒
            Types.Opt(read(childType))
          case Seq(JsString("alias"), JsString(aliasName)) ⇒
            aliases.get(aliasName) map (t ⇒ read(JsString(t))) getOrElse deserializationError(f"Unknown alias $aliasName%s")
          case Seq(JsString("trait"), JsString(traitName)) ⇒
            Types.Trait("Api" + traitName)
        }
      case _ ⇒
        deserializationError("Attribute type should be JsString or JsObject")
    }
  }
  implicit object attributeFormat extends RootJsonFormat[Attribute] {
    def write(attr: Attribute): JsValue = throw new NotImplementedError()

    def read(value: JsValue): Attribute = value match {
      case obj: JsObject ⇒
        val optAttribute = for {
          jsTyp ← obj.fields.get("type")
          jsId ← obj.fields.get("id")
          jsName ← obj.fields.get("name")
        } yield {
          jsId match {
            case JsNumber(id) ⇒
              jsName match {
                case JsString(unhackedName) ⇒
                  val name = hackAttributeName(unhackedName)
                  val typ = attributeTypeFormat.read(jsTyp)
                  Attribute(typ, id.toInt, name)
                case _ ⇒ deserializationError("Attribute name should be JsString")
              }
            case _ ⇒ deserializationError("Attribute type should be JsNumber")
          }
        }

        optAttribute getOrElse (deserializationError("Not enough fields for attribute"))
      case _ ⇒ deserializationError("Attribute should be a JsObject")
    }
  }

  implicit val anonymousRpcResponseFormat = jsonFormat2(AnonymousRpcResponse)
  implicit val referenceRpcResponseFormat = jsonFormat1(ReferenceRpcResponse)
  implicit val rpcResponseContentFormat = jsonFormat3(RpcResponseContent)

  implicit object rpcResponseFormat extends RootJsonFormat[RpcResponse] {
    def write(attr: RpcResponse): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case obj: JsObject ⇒
        obj.fields.get("type") match {
          case Some(JsString(typ)) ⇒
            readTyped(typ, obj)
          case _ ⇒
            deserializationError("RpcResponse type should be present and be a JsString")
        }

      case _ ⇒
        deserializationError("RpcResponse should be a JsObject")
    }

    def readTyped(typ: String, obj: JsObject): RpcResponse = {
      typ match {
        case "anonymous" ⇒
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
        case "reference" ⇒
          referenceRpcResponseFormat.read(obj)
      }
    }
  }

  implicit object structFormat extends RootJsonFormat[Struct] {
    def write(attr: Struct): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case obj: JsObject ⇒
        obj.getFields("name", "attributes") match {
          case Seq(JsString(name), JsArray(jsAttributes)) ⇒
            val attributes = jsAttributes map attributeFormat.read

            val traitExtOpt: Option[TraitExt] = obj.fields.get("trait") map {
              case obj: JsObject ⇒ traitExtFormat.read(obj)
              case _             ⇒ deserializationError("Expecting traitExt to be a JsObject")
            }

            Struct(name, attributes, traitExtOpt)
          case _ ⇒ deserializationError("Both name and attributes are required for Struct")
        }

      case _ ⇒
        deserializationError("RpcResponse should be a JsObject")
    }
  }

  implicit val rpcContentFormat = jsonFormat4(RpcContent)
  implicit val updateBoxFormat = jsonFormat3(UpdateBox)
  implicit val updateFormat = jsonFormat3(Update)
}
