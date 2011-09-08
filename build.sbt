import scalariform.formatter.preferences._
import ScalariformPlugin.formatPreferences

scalaVersion := "2.9.1"

crossScalaVersions := Seq("2.9.0-1", "2.9.1")

name := "fyrie-redis"

organization := "net.fyrie"

version := "1.2-SNAPSHOT"

resolvers += "Akka Repo" at "http://akka.io/repository"

libraryDependencies ++= Seq("se.scalablesolutions.akka" % "akka-actor" % "1.2-RC6" % "compile",
                            "org.scalatest" % "scalatest_2.9.0" % "1.6.1" % "test")

autoCompilerPlugins := true

libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % v) }

scalacOptions += "-P:continuations:enable"

parallelExecution in Test := false

seq((ScalariformPlugin.settings ++ Seq(formatPreferences in Compile := (FormattingPreferences()
                                                                        .setPreference(RewriteArrowSymbols, true)
                                                                        .setPreference(AlignParameters, true)
                                                                        .setPreference(AlignSingleLineCaseStatements, true)),
                                       formatPreferences in Test :=    (FormattingPreferences()
                                                                        .setPreference(RewriteArrowSymbols, true)
                                                                        .setPreference(AlignParameters, true)
                                                                        .setPreference(AlignSingleLineCaseStatements, true)))): _*)

publishTo <<= (version) { version: String =>
  val repo = (s: String) =>
    Resolver.ssh(s, "repo.fyrie.net", "/home/repo/" + s + "/") as("derek", file("/home/derek/.ssh/id_rsa")) withPermissions("0644")
  Some(if (version.trim.endsWith("SNAPSHOT")) repo("snapshots") else repo("releases"))
}
