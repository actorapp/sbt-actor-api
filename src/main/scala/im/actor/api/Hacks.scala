package im.actor.api

import scala.collection.mutable

trait Hacks {
  def extTypeName(structName: String): String = structName match {
    case "MessageContent" => "type"
    case _ => "extType"
  }
}
