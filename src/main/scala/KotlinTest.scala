package sbt

import sbt.Keys._
import sbt.internal.inc.{ClassToAPI, Lookup, NoopExternalLookup}
import sbt.internal.inc.classfile.Analyze
import sbt.internal.inc.{Analysis, IncrementalCompile}
import xsbti.compile._

object KotlinTest {
  val kotlinTests = Def.task {
    val out = (target in Test).value / "test-classes"
    val srcs = ((sourceDirectory in Test).value ** "*.kt").get.toList
    val xs = (out ** "*.class").get.toList
    val classpath = (fullClasspath in Test).value
    val loader = new java.net.URLClassLoader(classpath.map(_.data.toURI.toURL).toArray)
    val log = streams.value.log
    val a0 = IncrementalCompile(
      srcs.toSet,
      new Lookup with NoopExternalLookup {
        override def changedClasspathHash: Option[Vector[FileHash]] = None

        override def analyses: Vector[CompileAnalysis] = Vector.empty

        override def lookupOnClasspath(binaryClassName: String): Option[File] = None

        override def lookupAnalysis(binaryClassName: String): Option[CompileAnalysis] = None
      },
      (fs, changs, callback, manger) => {
        def readAPI(source: File, classes: Seq[Class[_]]): Set[(String, String)] = {
          val (api, mainclasses, inherits) = ClassToAPI.process(classes)
          api.foreach(p => callback.api(source, p))
          inherits.map{case (c1, c2) => c1.getName -> c2.getName}
        }
        Analyze(xs, srcs, log)(callback, loader, readAPI)
      },
      Analysis.Empty,
      new SingleOutput {
        def getOutputDirectory = out
      },
      log,
      IncOptions.of()
    )._2
    val frameworks = (loadedTestFrameworks in Test).value.values.toList
    log.info(s"Compiling ${srcs.length} Kotlin source to ${out}...")
    Tests.discover(frameworks, a0, log)._1
  }
}
