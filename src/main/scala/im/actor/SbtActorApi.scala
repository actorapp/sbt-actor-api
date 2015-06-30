package im.actor

import im.actor.api._
import java.io.File
import sbt._, Keys._

object SbtActorApi extends AutoPlugin {
  val ActorApi = config("actorapi").hide

  val path = SettingKey[File]("actor-schema-path", "The path that contains actor.json file")
  val outputPath = SettingKey[File]("actor-schema-output-path", "The paths where to save the generated *.scala files.")

  lazy val actorapi = TaskKey[Seq[File]]("actorapi", "Compile json schema to scala code")
  lazy val actorapiClean = TaskKey[Seq[File]]("actorapi-clean", "Clean generated code")

  lazy val actorapiMain = SettingKey[String]("actorapi-main", "ActorApi main class.")

  lazy val settings: Seq[Setting[_]] = Seq(
    sourceDirectory in ActorApi <<= (sourceDirectory in Compile),
    path <<= sourceDirectory in ActorApi,
    managedClasspath in ActorApi <<= (classpathTypes, update) map { (ct, report) =>
      Classpaths.managedJars(ActorApi, ct, report)
    },
    outputPath <<= sourceManaged in ActorApi,

    actorapi <<= (
      sourceDirectory in ActorApi,
      sourceManaged in ActorApi,
      managedClasspath in ActorApi,
      javaHome,
      streams
    ).map(generate),

    actorapiClean <<= (
      sourceManaged in ActorApi,
      streams).map(clean),

    sourceGenerators in Compile <+= actorapi
  )

  private def compiledFileDir(targetDir: File): File =
    targetDir / "main" / "scala"

  private def compiledFile(targetDir: File, name: String): File =
    compiledFileDir(targetDir) / s"${name}.scala"

  private def clean(targetDir: File, streams: TaskStreams): Seq[File] = {
    val log = streams.log

    log.info("Cleaning actor schema")

    IO.delete(targetDir)

    Seq(targetDir)
  }

  private def generate(srcDir: File, targetDir: File, classpath: Classpath, javaHome: Option[File], streams: TaskStreams): Seq[File] = {
    val log = streams.log

    log.info(f"Generating actor schema for $srcDir%s")

    val input = srcDir / "actor-api"

    if (!input.exists()) {
      log.info(f"$input%s does not exists")
      Nil
    } else {
      val output = compiledFileDir(targetDir)

      val cached = FileFunction.cached(streams.cacheDirectory / "actor-api", FilesInfo.lastModified, FilesInfo.exists) {
        (in: Set[File]) => {
          if (!output.exists())
            IO.createDirectory(output)

          val src = input / "actor.json"
          if (src.exists()) {
            val sources = (new Json2Tree(IO.read(src))).convert()

            sources foreach {
              case (name, source) =>
                val targetFile = compiledFile(targetDir, name)

                log.info(f"Generated ActorApi $targetFile%s")

                IO.write(targetFile, source)
            }
          } else {
            log.info(f"no actor.json file in $input%s")
          }

          (output ** ("*.scala")).get.toSet
        }
      }
      cached((input ** "actor.json").get.toSet).toSeq
    }
  }
}
