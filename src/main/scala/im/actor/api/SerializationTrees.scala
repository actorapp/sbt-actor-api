package im.actor.api

import scala.language.postfixOps
import treehugger.forest._, definitions._
import treehuggerDSL._

trait SerializationTrees extends TreeHelpers {
  def companionObjectTree(name: String, attributes: Vector[Attribute], extraTrees: Vector[Tree] = Vector.empty): Tree = {
    def typeValue(attr: AttributeType): Tree = attr match {
      case AttributeType(t, None) => LIT(t)
      case AttributeType(t, Some(_)) => REF(t)
    }

    val attrs = attributes map { attr =>
      PAREN(LIT(attr.id) INFIX("-->", typeValue(attr.typ)))
    }

    val tree = INFIX_CHAIN("::", attrs :+ REF("HNil"))


    OBJECTDEF(name) := BLOCK(
      extraTrees :+ tree
    )
  }
}
