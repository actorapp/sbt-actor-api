package im.actor.api

import scala.collection.mutable
import treehugger.forest._, definitions._

trait TreeHelpers {
  private val symCache: mutable.Map[String, TermSymbol] = mutable.Map.empty

  protected def newOrCachedSym(name: String): TermSymbol = {
    symCache.getOrElseUpdate(name, {
      RootClass.newValue(name)
    })
  }
}
