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
  val EmptyVector: Tree = REF("Vector") DOT ("empty")

  protected def attrType(typ: Types.AttributeType): Type = typ match {
    case Types.Int32  ⇒ IntClass
    case Types.Int64  ⇒ LongClass
    case Types.Double ⇒ DoubleClass
    case Types.String ⇒ StringClass
    case Types.Bool   ⇒ BooleanClass
    case Types.Bytes  ⇒ arrayType(ByteClass)
    case Types.Struct(name) ⇒
      valueCache(f"Refs.$name%s")
    case Types.Enum(name) ⇒
      valueCache(f"Refs.$name%s")
    case Types.List(typ) ⇒
      vectorType(attrType(typ))
    case Types.Opt(typ) ⇒
      optionType(attrType(typ))
    case Types.Trait(name) ⇒
      valueCache(f"Refs.$name%s")
  }
}
