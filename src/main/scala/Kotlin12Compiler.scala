package kotlincompiler

import java.io.File
import java.lang.reflect.{Field, Method}
import java.nio.file.Files
import java.util.jar.JarEntry

import sbt.Keys.Classpath
import sbt._
import sbt.io._

import collection.JavaConverters._
import scala.util.Try
import sbt.internal.inc.classpath.ClasspathUtilities

/**
 * @author pfnguyen
 */
object Kotlin12Compiler extends KotlinCompiler {

  def grepjar(jarfile: File)(pred: JarEntry => Boolean): Boolean =
    jarfile.isFile && Using.jarFile(false)(jarfile) { in =>
      in.entries.asScala exists pred
    }

  lazy val kotlinMemo = scalaz.Memo.immutableHashMapMemo[Classpath, KotlinReflection](
    cp => KotlinReflection.fromClasspath(cp)
  )

  def compile(options: Seq[String],
              sourceDirs: Seq[File],
              kotlinPluginOptions: Seq[String],
              classpath: Classpath,
              compilerClasspath: Classpath,
              output: File,
              )(implicit log: sbt.Logger): Unit = {

    val stub = KotlinStub(log, kotlinMemo(compilerClasspath))

    val kotlinFiles = "*.kt" || "*.kts"

    val kotlinSources = sourceDirs.flatMap(d => (d ** kotlinFiles).get).distinct

    if (kotlinSources.isEmpty) {
      log.debug("No sources found, skipping kotlin compile")
    } else {
      log.debug(s"Compiling sources $kotlinSources")
      def pluralizeSource(count: Int) =
        if (count == 1) "source" else "sources"
      val message =
        s"Compiling ${kotlinSources.size} Kotlin ${pluralizeSource(kotlinSources.size)}"
      log.info(message)

      val compilerArgs = CompilerArgs(classpath, output, kotlinSources)

      val fcpjars = classpath.map(_.data.getAbsoluteFile)
      val (_, cpjars) = fcpjars.partition {
        grepjar(_)(_.getName.startsWith(
          "META-INF/services/org.jetbrains.kotlin.compiler.plugin"))
      }
      val cp = cpjars.mkString(":")

      //val pcp = pluginjars.map(_.getAbsolutePath).mkString(":")
      //args.pluginClasspaths = Option(args.pluginClasspaths[Array[String]]).fold(pcp)(_ ++ pcp)
      /*args.pluginOptions = Option(args.pluginOptions[Array[String]]).fold(
        kotlinPluginOptions.toArray)(_ ++ kotlinPluginOptions.toArray[String])*/

      Files.createDirectories(output.toPath)
      val args = options ++ compilerArgs.args
      val parsedArgs = stub.parse(args.toList)
      stub.compile(parsedArgs)
    }
  }

  object KotlinReflection {
    def fromClasspath(cp: Classpath): KotlinReflection = {
      val cl = ClasspathUtilities.toLoader(cp.map(_.data))
      val compilerClass = cl.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
      val servicesClass = cl.loadClass("org.jetbrains.kotlin.config.Services")
      val messageCollectorClass = cl.loadClass("org.jetbrains.kotlin.cli.common.messages.MessageCollector")
      val commonCompilerArgsClass = cl.loadClass("org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments")

      val compilerExec = Try(
        compilerClass.getMethod("exec",
          messageCollectorClass, servicesClass, commonCompilerArgsClass)
      ).toOption.getOrElse {

        val commonToolArguments = cl.loadClass(
          "org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments")
        val clitool = cl.loadClass(
          "org.jetbrains.kotlin.cli.common.CLITool")
        clitool.getMethod("exec",
          messageCollectorClass, servicesClass, commonToolArguments)
      }

      KotlinReflection(
        cl,
        servicesClass,
        compilerClass,
        cl.loadClass("org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments"),
        messageCollectorClass,
        commonCompilerArgsClass,
        compilerExec,
        servicesClass.getDeclaredField("EMPTY"))
    }
  }
  case class KotlinReflection(cl: ClassLoader,
                              servicesClass: Class[_],
                              compilerClass: Class[_],
                              compilerArgsClass: Class[_],
                              messageCollectorClass: Class[_],
                              commonCompilerArgsClass: Class[_],
                              compilerExec: Method,
                              servicesEmptyField: Field)
  case class KotlinStub(log: sbt.Logger, kref: KotlinReflection) {
    import language.reflectiveCalls
    import kref._

    def messageCollector: AnyRef = {
      type CompilerMessageLocation = {
        def getPath: String
        def getLine: Int
        def getColumn: Int
      }

      import java.lang.reflect.{Proxy, InvocationHandler}
      val messageCollectorInvocationHandler: InvocationHandler =
        (proxy: scala.Any, method: Method, args: Array[AnyRef]) => {
          if (method.getName == "report") {
            val Array(severity, message, location) = args
            val l = location.asInstanceOf[CompilerMessageLocation]
            val msg = Option(l)
              .map(x => x.getPath)
              .fold(message.toString)(
                loc => loc + ": " + l.getLine + ", " + l.getColumn + ": " + message
              )
            severity.toString match {
              case "INFO"                => log.info(msg)
              case "WARNING"             => log.warn(msg)
              case "STRONG_WARNING"      => log.warn(msg)
              case "ERROR" | "EXCEPTION" => log.error(msg)
              case "OUTPUT" | "LOGGING"  => log.debug(msg)
            }
          }
          null
        }

      Proxy.newProxyInstance(cl, Array(messageCollectorClass), messageCollectorInvocationHandler)
    }

    def parse(options: List[String]): CompilerOptions = {
      // TODO FIXME, this is much worse than it used to be, the parsing api has been
      // deeply in flux since 1.1.x
      val parser = kref.cl.loadClass(
        "org.jetbrains.kotlin.cli.common.arguments.ParseCommandLineArgumentsKt")
      val commonArgsType = cl.loadClass(
        "org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments")
      val argsType = cl.loadClass(
        "org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments")
      val parserMethod = parser.getMethod("parseCommandLineArguments", classOf[java.util.List[java.lang.String]], commonArgsType)

      val compilerArgs = argsType.newInstance().asInstanceOf[AnyRef]
      import collection.JavaConverters._
      parserMethod.invoke(null, options.asJava, compilerArgs)
      CompilerOptions(compilerArgs)
    }

    def compile(args: CompilerOptions): Unit = {
      val compiler = compilerClass.newInstance()
      val result = compilerExec.invoke(compiler,
        messageCollector, servicesEmptyField.get(null), args.obj: java.lang.Object)
      result.toString match {
        case "COMPILATION_ERROR" | "INTERNAL_ERROR" =>
          throw new MessageOnlyException("Compilation failed. See log for more details")
        case _ =>
      }
    }

  }

  case class CompilerArgs(classpath: Classpath,
                          outputDirectory: File,
                          sources: Seq[File]
                         ) {
    lazy val args: List[String] = List(
      "-no-stdlib",
      "-classpath", classpath.map(_.data).mkString(":"),
      "-d", outputDirectory.getAbsolutePath,
    ) ++ sources.map(_.getAbsolutePath)
  }

  case class CompilerOptions(obj: AnyRef)
}