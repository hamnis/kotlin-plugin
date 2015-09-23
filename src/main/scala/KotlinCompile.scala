package kotlinplugin

import java.io.File
import java.util.jar.JarEntry

import com.sampullara.cli.Args
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import sbt.Keys.{TaskStreams, Classpath}
import sbt._

import collection.JavaConverters._

/**
 * @author pfnguyen
 */
object KotlinCompile {
  def compilerArgs = new K2JVMCompilerArguments

  def grepjar(jarfile: File)(pred: JarEntry => Boolean): Boolean =
    jarfile.isFile && Using.jarFile(false)(jarfile) { in =>
      in.entries.asScala exists pred
    }

  def compile(options: Seq[String],
              sourceDirs: Seq[File],
              compileJava: Boolean,
              kotlinPluginOptions: Seq[String],
              classpath: Classpath,
              output: File, s: TaskStreams): Unit = {
    val args = compilerArgs
    val kotlinFiles = "*.kt" || "*.kts"
    val javaFiles = "*.java"

    val kotlinSources = sourceDirs.flatMap(d => (d ** kotlinFiles).get.distinct)
    val javaSources = if (compileJava) {
      sourceDirs.filterNot(f => sourceDirs.exists(f0 =>
        f0.relativeTo(f).isDefined && f != f0)) map (d =>
        (d, (d ** javaFiles).get.size)) filter (_._2 > 0)
    } else Nil
    val javaSourceCount = javaSources.map(_._2).sum
    if (kotlinSources.isEmpty && javaSourceCount == 0) {
      s.log.debug("No sources found, skipping kotlin compile")
    } else {
      def pluralizeSource(count: Int) =
        if (count == 1) "source" else "sources"
      val message = if (kotlinSources.nonEmpty && javaSourceCount > 0) {
        s"Compiling ${kotlinSources.size} Kotlin ${pluralizeSource(kotlinSources.size)} and $javaSourceCount Java ${pluralizeSource(javaSourceCount)}"
      } else if (kotlinSources.nonEmpty) {
        s"Compiling ${kotlinSources.size} Kotlin ${pluralizeSource(kotlinSources.size)}"
      } else if (javaSourceCount > 0) {
        s"Compiling $javaSourceCount Java ${pluralizeSource(javaSourceCount)}"
      } else {
        "Compiling nothing"
      }
      s.log.info(message)
      args.freeArgs = (kotlinSources.map(_.getAbsolutePath) ++ (
        if (javaSourceCount > 0) javaSources.map(_._1.getAbsolutePath) else Nil)).asJava

      args.noStdlib = true
      args.noJdkAnnotations = true
      Args.parse(args, options.toArray)
      val fcpjars = classpath.map(_.data.getAbsoluteFile)
      val (pluginjars, cpjars) = fcpjars.partition {
        grepjar(_)(_.getName.startsWith(
          "META-INF/services/org.jetbrains.kotlin.compiler.plugin"))
      }
      val cp = cpjars.mkString(File.pathSeparator)
      val pcp = pluginjars.map(_.getAbsolutePath).toArray
      args.classpath = Option(args.classpath).fold(cp)(_ + File.pathSeparator + cp)
      args.pluginClasspaths = Option(args.pluginClasspaths).fold(pcp)(_ ++ pcp)
      args.pluginOptions = Option(args.pluginOptions).fold(
        kotlinPluginOptions.toArray)(_ ++ kotlinPluginOptions.toArray[String])
      args.destination = output.getAbsolutePath
      // bug in scalac prevents calling directly, yuck
      KotlinCompileJava.compile(s.log, args)
    }
  }

}
