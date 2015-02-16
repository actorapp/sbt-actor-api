# sbt-actor-api

SBT plugin to generate code from json [Actor API Schema](https://github.com/actorapp/actor-api-schema)

## Usage

### Adding the plugin dependency
In your project, create a file for plugin library dependencies `project/plugins.sbt` and add the following lines:

    addSbtPlugin("im.actor" % "sbt-actor-api" % "0.1.0-SNAPSHOT")

The dependency to `"com.google.protobuf" % "protobuf-java"` **is not** automatically added to the any of scopes.

### Importing sbt-actor-api settings
To actually "activate" the plugin, its settings need to be included in the build.

##### build.sbt

    import im.actor.SbtActorApi

    Seq(SbtActorApi.settings: _*)

##### build.scala
    import sbt._

    import im.actor.SbtActorApi

    object MyBuild extends Build {
      lazy val myproject = MyProject(
        id = "myproject",
        base = file("."),
        settings = Defaults.defaultSettings ++ SbtActorApi.settings ++ Seq(
            /* custom settings here */
        )
      )
    }


### Compiling schema

Execute `actor-schema-generate` in sbt.
