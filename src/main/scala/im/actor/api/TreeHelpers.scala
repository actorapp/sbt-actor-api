package im.actor.api

import scala.collection.mutable
import treehugger.forest._, definitions._
import treehuggerDSL._

trait TreeHelpers {
  private val symCache: mutable.Map[Name, Symbol] = mutable.Map.empty

  protected def valueCache(name: Name): Symbol = {
    symCache.getOrElseUpdate(name, {
      if (name.isTypeName) RootClass.newClass(name.toTypeName)
      else RootClass.newModule(name.toTermName)
    })
  }

  def vectorType(arg: Type) = appliedType(VectorClass.typeConstructor, List(arg))
  val EmptyVector: Tree = REF("Vector") DOT "empty"

  protected def attrType(typ: Types.AttributeType): Type = typ match {
    case Types.Int32  ⇒ IntClass
    case Types.Int64  ⇒ LongClass
    case Types.Double ⇒ DoubleClass
    case Types.String ⇒ StringClass
    case Types.Bool   ⇒ BooleanClass
    case Types.Bytes  ⇒ arrayType(ByteClass)
    case struct @ Types.Struct(_) ⇒
      valueCache(f"Refs.${struct.name}%s")
    case enum @ Types.Enum(_) ⇒
      valueCache(f"Refs.${enum.name}%s")
    case Types.List(listTyp) ⇒
      vectorType(attrType(listTyp))
    case Types.Opt(optTyp) ⇒
      optionType(attrType(optTyp))
    case trai @ Types.Trait(_) ⇒
      valueCache(f"Refs.${trai.name}%s")
  }

  def XORRIGHT(right: Tree) = REF("Xor") DOT "right" APPLY right
  def XORLEFT(left: Tree) = REF("Xor") DOT "left" APPLY left

  def xorType(arg1: Type, arg2: Type) = typeRef(NoPrefix, valueCache("Xor"), List(arg1, arg2))

  def emptyVector = valueCache("Vector") DOT "empty"
}
