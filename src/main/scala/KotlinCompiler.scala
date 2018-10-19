package kotlincompiler

import sbt._
import sbt.Def.Classpath

trait KotlinCompiler {
  def compile(options: Seq[String],
              sourceDirs: Seq[File],
              kotlinPluginOptions: Seq[String],
              classpath: Classpath,
              compilerClasspath: Classpath,
              output: File,
              )(implicit log: sbt.Logger): Unit
}
