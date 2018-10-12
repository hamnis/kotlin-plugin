//import ScriptedPlugin._
//import bintray.Keys._

name := "kotlin-plugin"

organization := "com.hanhuy.sbt"

version := "1.1-SNAPSHOT"

scalacOptions ++= Seq("-deprecation","-Xlint","-feature")

enablePlugins(SbtPlugin)

// build info plugin
buildInfoPackage := "kotlin"

// bintray
//bintrayPublishSettings

//repository in bintray := "sbt-plugins"

publishMavenStyle := false

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

//bintrayOrganization in bintray := None

// scripted
//scriptedSettings

libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.2.26"

scriptedLaunchOpts ++= "-Xmx1024m" :: "-Dplugin.version=" + version.value :: Nil
scriptedBufferLog := false
