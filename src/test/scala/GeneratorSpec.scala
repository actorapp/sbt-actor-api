package im.actor.api

import org.specs2._

class GeneratorSpec extends Specification {
  def is = s2"""
  Generator sould generate valid scala code $e1
  """

  def e1 = {
    val source = scala.io.Source.fromURL(getClass.getResource("/actor.json"))
    val tree = Json2Tree.convert(source.getLines.mkString("\n"))
    println(tree)
    success
  }
}
