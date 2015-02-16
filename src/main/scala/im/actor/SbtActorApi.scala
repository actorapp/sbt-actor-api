package im.actor

import im.actor.api._
import sbt._, Keys._

object SbtActorApi extends AutoPlugin {
  val path = SettingKey[File]("actor-schema-path", "The path that contains actor.json file")
  val outputPath = SettingKey[File]("actor-schema-output-path", "The paths where to save the generated *.scala files.")

  lazy val generate = TaskKey[Unit]("actor-schema-generate", "Compile json schema to scala code")

  lazy val settings: Seq[Setting[_]] = Seq(
    path <<= (sourceDirectory in Compile) { _ / "actor-api" },
    outputPath <<= (sourceDirectory in Compile) { _ / "scala" },
    generate <<= generateTask
  )

  private def generate(srcDir: File, targetDir: File, log: Logger): Unit = {
    log.info(f"Generating actor schema for $srcDir%s")

    val schemaFile = srcDir / "actor.json"
    if (!schemaFile.exists) {
      log.info(f"Nothing to generate in $srcDir%s")
    } else {
      val tree = (new Json2Tree(IO.read(schemaFile))).convert()
      println(tree)
    }
  }

  private def generateTask: Def.Initialize[Task[Unit]] = (streams, path, outputPath) map {
    (out, sourceDir, targetDir) =>
    generate(sourceDir, targetDir, out.log)
  }
}
