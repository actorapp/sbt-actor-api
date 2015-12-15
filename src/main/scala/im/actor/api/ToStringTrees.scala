package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._

import scala.language.postfixOps

trait ToStringTrees extends TreeHelpers {

  protected val stringHelpersTree: Tree = OBJECTDEF("StringHelpers") withFlags PRIVATEWITHIN("api") := BLOCK(
    DEF("escapeSpecial", StringClass).withParams(PARAM("source", StringClass)) :=
      REF("source")
      DOT "replace" APPLY (LIT("\n"), LIT("\\n"))
      DOT "replace" APPLY (LIT("\r"), LIT("\\r"))
      DOT "replace" APPLY (LIT("\t"), LIT("\\t"))
      DOT "replace" APPLY (LIT("\f"), LIT("\\f"))
      DOT "replace" APPLY (LIT("\""), LIT("\\\""))
  )

  protected def generateToString(name: String, attributes: Vector[Attribute], attribureDocs: Vector[AttributeDoc]): DefDef = {
    val docMapped = attribureDocs.map(e ⇒ e.argument → e).toMap
    val body = (className(name) +: (attributes foldLeft Vector.empty[Vector[Tree]]) { (acc, elem) ⇒
      val show = docMapped.get(elem.name) map (ad ⇒ Categories.Category.isVisible(ad.category)) getOrElse true
      if (show) acc :+ ((wrapQoutes(LIT(elem.name)) :+ LIT(": ")) ++ wrapAttr(elem.typ, elem.name)) else acc
    }) match {
      case Vector() ⇒ Vector.empty[Tree]
      case v        ⇒ v reduce ((a, b) ⇒ (a :+ LIT(",")) ++ b)
    }
    val json = (LIT("{ ") +: body) :+ LIT(" }")
    DEF("toString", StringClass) withFlags Flags.OVERRIDE := INFIX_CHAIN("+", json)
  }

  private def className(name: String): Vector[Tree] =
    (wrapQoutes(LIT("_name")) :+ LIT(": ")) ++ wrapQoutes(LIT(name))

  private def wrapQoutes(e: Tree): Vector[Tree] = Vector(LIT("\""), e, LIT("\""))

  private def wrapAttr(attrType: Types.AttributeType, attrName: String): Vector[Tree] = {
    attrType match {
      case Types.Int32 | Types.Int64 | Types.Bool | Types.Double | Types.Bytes ⇒ Vector(REF(attrName))
      case Types.String ⇒ wrapQoutes(REF("StringHelpers") DOT "escapeSpecial" APPLY REF(attrName))
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
