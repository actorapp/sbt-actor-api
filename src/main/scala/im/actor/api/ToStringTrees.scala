package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._

trait ToStringTrees extends TreeHelpers {

  protected def generateToString(name: String, attributes: Vector[Attribute], attribureDocs: Vector[AttributeDoc]): DefDef = {
    val docMapped = attribureDocs.map(e ⇒ e.argument → e).toMap
    val body = (attributes foldLeft Vector.empty[Vector[Tree]]) { (acc, elem) ⇒
      val show = docMapped.get(elem.name) map (ad ⇒ Categories.Category.isVisible(ad.category)) getOrElse true
      if (show) acc :+ ((wrapQoutes(LIT(elem.name)) :+ LIT(": ")) ++ wrapAttr(elem.typ, elem.name)) else acc
    } match {
      case Vector() ⇒ Vector.empty[Tree]
      case v        ⇒ v reduce ((a, b) ⇒ (a :+ LIT(",")) ++ b)
    }
    val className = (wrapQoutes(LIT("_name")) :+ LIT(": ")) ++ wrapQoutes(LIT(name))
    val json = ((LIT("{ ") +: className) ++ body) :+ LIT(" }")
    DEF("toString", StringClass) withFlags Flags.OVERRIDE := INFIX_CHAIN("+", json)
  }

  private def wrapQoutes(e: Tree): Vector[Tree] = Vector(LIT("\\\""), e, LIT("\\\""))

  private def wrapAttr(attrType: Types.AttributeType, attrName: String): Vector[Tree] = {
    attrType match {
      case Types.Int32 | Types.Int64 | Types.Bool | Types.Double | Types.Bytes ⇒ Vector(REF(attrName))
      case Types.String ⇒ wrapQoutes(REF(attrName)) //todo: replace everything from string, like /n /t /r
      case Types.Enum(_) ⇒ Vector(REF(attrName) DOT "id")
      case Types.Opt(typ) ⇒
        val optMapToString = REF(attrName) MAP LAMBDA(PARAM("e")) ==> INFIX_CHAIN("+", wrapAttr(typ, "e"))
        Vector(optMapToString DOT "getOrElse" APPLY NULL)
      case Types.List(typ) ⇒
        val listMapToString =
          REF(attrName) MAP LAMBDA(PARAM("e")) ==> INFIX_CHAIN("+", wrapAttr(typ, "e")) DOT "mkString" APPLY LIT(", ")
        Vector(LIT("[ "), listMapToString, LIT(" ]"))
      case Types.Struct(_) | Types.Trait(_) ⇒ Vector(REF(attrName) TOSTRING)
      case Types.Alias(aliasName)           ⇒ wrapAttr(aliasesPrim.get(aliasName).get, attrName)
      case _                                ⇒ Vector(REF(attrName))
    }
  }

}
