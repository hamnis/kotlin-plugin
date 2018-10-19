package kotlincompiler

import sbt._

/**
 * @author pfnguyen
 */
object Keys {
  sealed trait KotlinCompileOrder

  val Kotlin = config("kotlin")
  val KotlinInternal = config("kotlin-internal").hide

  val kotlinCompile = taskKey[Unit]("runs kotlin compilation, occurs before normal compilation")
  val kotlincPluginOptions = taskKey[Seq[String]]("kotlin compiler plugin options")
  val kotlinSource = settingKey[File]( "kotlin source directory")
  val kotlinVersion = settingKey[String]("version of kotlin to use for building")
  val kotlincOptions = taskKey[Seq[String]]("options to pass to the kotlin compiler")

  def kotlinLib(name: String) = sbt.Keys.libraryDependencies +=
    "org.jetbrains.kotlin" % ("kotlin-" + name) % kotlinVersion.value

  def kotlinPlugin(name: String) = sbt.Keys.libraryDependencies +=
    "org.jetbrains.kotlin" % ("kotlin-" + name) % kotlinVersion.value % "compile-internal"

  def kotlinClasspath(config: Configuration, classpathKey: Def.Initialize[sbt.Keys.Classpath]): Setting[_] =
    kotlincOptions in config ++= {
    "-cp" :: classpathKey.value.map(_.data.getAbsolutePath).mkString(
      java.io.File.pathSeparator) ::
      Nil
  }

  case class KotlinPluginOptions(pluginId: String) {
    def option(key: String, value: String) =
      s"plugin:$pluginId:$key=$value"
  }
}
