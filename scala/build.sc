import mill._
import mill.scalalib._
import mill.scalajslib._
import mill.scalajslib.api._
import mill.scalalib.publish._
import scalafmt._

def defaultPomSettings(desc: String) = PomSettings(
  description = desc,
  organization = "org.algebraicjulia",
  url = "https://github.com/AlgebraicJulia/Semagrams.jl",
  licenses = Seq(License.MIT),
  versionControl = VersionControl.github("AlgebraicJulia", "Semagrams.jl"),
  developers = Seq(
    Developer("olynch", "Owen Lynch", "https://owenlynch.org")
  )
)

trait Defaults extends ScalaJSModule with PublishModule with ScalafmtModule {
  def scalaVersion = "3.2.1"
  def scalaJSVersion = "1.13.0"
  def ammoniteVersion = "2.5.6"

  def scalacOptions = Seq("-deprecation", "-feature")

  def moduleKind = T { ModuleKind.ESModule }

  def ivyDeps = Agg(
    ivy"org.scala-js::scalajs-dom::2.2.0",
    ivy"com.raquo::laminar::0.14.2",
    ivy"com.lihaoyi::upickle::2.0.0",
    ivy"org.typelevel::cats-core::2.9.0",
    ivy"org.typelevel::cats-kernel::2.9.0",
    ivy"org.typelevel::cats-effect::3.4.8",
    ivy"com.github.japgolly.scalacss::core::1.0.0",
    ivy"dev.optics::monocle-core::3.2.0",
    ivy"dev.optics::monocle-macro::3.2.0"
  )

  def desc: String

  def pomSettings = defaultPomSettings(desc)

  def publishVersion = "0.3.0-SNAPSHOT"

  def sonatypeUri = "https://s01.oss.sonatype.org/service/local"

  def sonatypeSnapshotUri = "https://s01.oss.sonatype.org/content/repositories/snapshots"
}

object core extends Defaults {
  def desc = "A library for semantic diagrams"

  def artifactName = "semagrams"

  object test extends Tests with TestModule.Utest {
    def jsEnvConfig = T(JsEnvConfig.JsDom())

    def ivyDeps = Agg(ivy"com.lihaoyi::utest::0.8.0")
  }
}

trait SemagramsApp extends Defaults {
  def moduleDeps = Seq(core)
}

object apps extends Module {

  object simplepetri extends SemagramsApp {
    def desc = "simple petri editor"

    def artifactName = "semagrams-simplepetri"
  }
  
  object dwd extends SemagramsApp {
    def desc = "A string diagram editor"

    def artifactName = "semagrams-dwd"
  }



}
