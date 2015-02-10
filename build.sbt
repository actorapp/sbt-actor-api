sbtPlugin := true

organization := "im.actor"

name := "sbt-actor-api"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.4"

scalacOptions ++= Seq("-deprecation", "-feature")

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases")
)

libraryDependencies ++= Seq(
  "com.eed3si9n" %% "treehugger" % "0.3.0",
  "com.google.protobuf" % "protobuf-java" % "2.6.1",
  "io.spray" %%  "spray-json" % "1.3.1",
  "com.chuusai" % "shapeless_2.10.4" % "2.1.0",
  "org.specs2" %% "specs2-core" % "2.4.15" % "test",
  compilerPlugin("org.scalamacros" % "paradise_2.10.4" % "2.0.1")
)

publishMavenStyle := false
