package im.actor.api

import scala.collection.mutable
import treehugger.forest._, definitions._
import treehuggerDSL._

trait TreeHelpers {
  type Aliases = Map[String, String]

  private val symCache: mutable.Map[Name, Symbol] = mutable.Map.empty

  protected def valueCache(name: Name): Symbol = {
    symCache.getOrElseUpdate(name, {
      if (name.isTypeName) RootClass.newClass(name.toTypeName)
      else RootClass.newModule(name.toTermName)
    })
  }

  def vectorType(arg: Type) = appliedType(VectorClass.typeConstructor, List(arg))
  val EmptyVector: Tree = REF("Vector") DOT("empty")

  protected def attrType(typ: AttributeType, aliases: Aliases): Type = typ match {
    case AttributeType("int32", None) => IntClass
    case AttributeType("int64", None) => LongClass
    case AttributeType("double", None) => DoubleClass
    case AttributeType("string", None) => StringClass
    case AttributeType("bool", None) => BooleanClass
    case AttributeType("bytes", None) => arrayType(ByteClass)
    case AttributeType("struct", Some(child)) =>
      attrType(child, aliases)
    case AttributeType("enum", Some(child)) =>
      attrType(child, aliases)
    case AttributeType("list", Some(child)) =>
      vectorType(attrType(child, aliases))
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
}
