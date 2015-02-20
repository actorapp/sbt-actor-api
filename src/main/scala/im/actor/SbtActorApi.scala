package im.actor

import im.actor.api._
import java.io.File
import sbt._, Keys._

object SbtActorApi extends AutoPlugin {
  val ActorApi = config("actorapi").hide

  val path = SettingKey[File]("actor-schema-path", "The path that contains actor.json file")
  val outputPath = SettingKey[File]("actor-schema-output-path", "The paths where to save the generated *.scala files.")

  lazy val actorapi = TaskKey[Seq[File]]("actorapi", "Compile json schema to scala code")

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

    sourceGenerators in Compile <+= (actorapi).task
  )

  private def generate(srcDir: File, targetDir: File, classpath: Classpath, javaHome: Option[File], streams: TaskStreams): Seq[File] = {
    val log = streams.log

    log.info(f"Generating actor schema for $srcDir%s")

    val input = srcDir / "actor-api"

    if (!input.exists) {
      log.info(f"$input%s does not exists")
      Nil
    } else {
      val output = targetDir / "scala" //"ActorApi.scala"

      println(streams.cacheDirectory / "actor-api")

      val cached = FileFunction.cached(streams.cacheDirectory / "actor-api", FilesInfo.lastModified, FilesInfo.exists) {
        (in: Set[File]) => {
          IO.delete(output)
          IO.createDirectory(output)

          val src = input / "actor.json"
          if (src.exists()) {
            val tree = (new Json2Tree(IO.read(src))).convert()
            val targetFile = output / "ActorApi.scala"

            log.info(f"Generated ActorApi.scala $targetFile%s")

            IO.write(targetFile, tree)
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
