package com.typesafe.sbt.jse

import sbt._
import sbt.Keys._
import com.typesafe.jse._

import scala.collection.immutable
import com.typesafe.npm.Npm
import akka.util.Timeout

import scala.concurrent.{Await, ExecutionContext}
import com.typesafe.jse.Node
import com.typesafe.sbt.web.SbtWeb

import scala.concurrent.duration._
import scala.sys.process.Process
import scala.util.Try

object JsEngineImport {

  object JsEngineKeys {

    object EngineType extends Enumeration {
      val CommonNode, Node, PhantomJs, Javax, Rhino, Trireme,
      /**
       * Auto detect the best available engine to use for most common tasks - this will currently select node if
       * available, otherwise it will fall back to trireme
       */
      AutoDetect = Value
    }

    val command = SettingKey[Option[File]]("jse-command", "An optional path to the command used to invoke the engine.")
    val engineType = SettingKey[EngineType.Value]("jse-engine-type", "The type of engine to use.")
    val parallelism = SettingKey[Int]("jse-parallelism", "The number of parallel tasks for the JavaScript engine. Defaults to the # of available processors + 1 to keep things busy.")

    val npmTimeout = SettingKey[FiniteDuration]("jse-npm-timeout", "The maximum number amount of time for npm to do its thing.")
    val npmNodeModules = TaskKey[Seq[File]]("jse-npm-node-modules", "Node module files generated by NPM.")
    val npmUseIntegrated = SettingKey[Boolean]("jse-npm-use-integrated")
  }

}

import java.io.{ByteArrayOutputStream, PrintWriter}

object ConsoleCommand {

  import sys.process._

  def apply(cmd: Seq[String]): ExecutionResult = {
    val stdoutStream = new ByteArrayOutputStream
    val stderrStream = new ByteArrayOutputStream
    val stdoutWriter = new PrintWriter(stdoutStream)
    val stderrWriter = new PrintWriter(stderrStream)
    val exitValue = cmd.!(ProcessLogger(stdoutWriter.println, stderrWriter.println))
    stdoutWriter.close()
    stderrWriter.close()
    ExecutionResult(exitValue, stdoutStream.toString, stderrStream.toString)
  }

  case class ExecutionResult(exitValue: Int, stdOut: String, stdErr: String)
}

/**
 * Declares the main parts of a WebDriver based plugin for sbt.
 */
object SbtJsEngine extends AutoPlugin {

  override def requires = SbtWeb

  override def trigger = AllRequirements

  val autoImport = JsEngineImport

  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport._
  import JsEngineKeys._

  /**
   * Convert an engine type enum to an actor props.
   */
  def engineTypeToProps(engineType: EngineType.Value, command: Option[File], env: Map[String, String]) = {
    engineType match {
      case EngineType.CommonNode => CommonNode.props(command, stdEnvironment = env)
      case EngineType.Node => Node.props(command, stdEnvironment = env)
      case EngineType.PhantomJs => PhantomJs.props(command)
      case EngineType.Javax => JavaxEngine.props()
      case EngineType.Rhino => Rhino.props()
      case EngineType.Trireme => Trireme.props(stdEnvironment = env)
      case EngineType.AutoDetect => if (autoDetectNode) {
        Node.props(command, stdEnvironment = env)
      } else {
        Trireme.props(stdEnvironment = env)
      }
    }
  }

  private val NodeModules = "node_modules"
  private val PackageJson = "package.json"


  private lazy val autoDetectNode: Boolean = {
    val nodeExists = Try(Process("node --version").!!).isSuccess
    if (!nodeExists) {
      println("Warning: node.js detection failed, sbt will use the Rhino based Trireme JavaScript engine instead to run JavaScript assets compilation, which in some cases may be orders of magnitude slower than using node.js.")
    }
    nodeExists
  }

  private val jsEngineUnscopedSettings: Seq[Setting[_]] = Seq(
    npmNodeModules := Def.task {
      val npmDirectory = baseDirectory.value / NodeModules
      val npmPackageJson = baseDirectory.value / PackageJson
      val cacheDirectory = streams.value.cacheDirectory / "npm"
      implicit val timeout = Timeout(npmTimeout.value)
      val webJarsNodeModulesPath = (webJarsNodeModulesDirectory in Plugin).value.getCanonicalPath
      val nodePathEnv = LocalEngine.nodePathEnv(immutable.Seq(webJarsNodeModulesPath))
      val engineProps = engineTypeToProps(engineType.value, command.value, nodePathEnv)
      val nodeModulesDirectory = (webJarsNodeModulesDirectory in Plugin).value
      val logger = streams.value.log
      val currentState = state.value
      val useIntegrated = npmUseIntegrated.value
      val basePath = baseDirectory.value.getPath

      if (useIntegrated) {
        (if (npmPackageJson.exists) {
          val isWindows = System.getProperty("os.name").contains("Windows")
          val nodeWhere = ConsoleCommand(Seq(if (isWindows) "where" else "which", "node"))
          if (nodeWhere.exitValue != 0)
            sys.error("Problems with NPM resolution. Aborting build. Check if NPM can be found with which/where command")
          val npmPath = new File(new File(nodeWhere.stdOut).getParentFile, if (isWindows) "npm.cmd" else "npm").getCanonicalPath
          val commandBase = Seq(npmPath, "install", "--prefix", basePath)
          val command = if (isWindows) commandBase else Seq("node") ++ commandBase
          println(s"NPM command - ${command}")
          val result = ConsoleCommand(command)
          result.stdOut.split("\n").foreach(s => logger.info(s))
          result.stdErr.split("\n").foreach(s => if (result.exitValue == 0) logger.info(s) else logger.error(s))
          npmDirectory.**(AllPassFilter).get.toSet
        } else {
          IO.delete(npmDirectory)
          Set.empty
        }).toSeq
      } else {
        val runUpdate = FileFunction.cached(cacheDirectory, FilesInfo.hash) {
          _ =>
            if (npmPackageJson.exists) {
              val pendingExitValue = SbtWeb.withActorRefFactory(currentState, this.getClass.getName) {
                arf =>
                  val engine = arf.actorOf(engineProps)
                  val npm = new Npm(engine, nodeModulesDirectory / "npm" / "lib" / "npm.js")
                  import ExecutionContext.Implicits.global
                  for (
                    result <- npm.update(global = false, Seq("--prefix", baseDirectory.value.getPath))
                  ) yield {
                    // TODO: We need to stream the output and error channels. The js engine needs to change in this regard so that the
                    // stdio sink and sources can be exposed through the NPM library and then adopted here.
                    new String(result.output.toArray, "UTF-8").split("\n").foreach(s => logger.info(s))
                    new String(result.error.toArray, "UTF-8").split("\n").foreach(s => if (result.exitValue == 0) logger.info(s) else logger.error(s))
                    result.exitValue
                  }
              }
              if (Await.result(pendingExitValue, timeout.duration) != 0) {
                sys.error("Problems with NPM resolution. Aborting build.")
              }
              npmDirectory.**(AllPassFilter).get.toSet
            } else {
              IO.delete(npmDirectory)
              Set.empty
            }
        }
        runUpdate(Set(npmPackageJson)).toSeq
      }
    }.dependsOn(webJarsNodeModules in Plugin).value,

    nodeModuleGenerators += npmNodeModules.taskValue,
    nodeModuleDirectories += baseDirectory.value / NodeModules
  )

  private val defaultEngineType = EngineType.AutoDetect

  override def projectSettings: Seq[Setting[_]] = Seq(
    engineType := sys.props.get("sbt.jse.engineType").fold(defaultEngineType)(engineTypeStr =>
      Try(EngineType.withName(engineTypeStr)).getOrElse {
        println(s"Unknown engine type $engineTypeStr for sbt.jse.engineType. Resorting back to the default of $defaultEngineType.")
        defaultEngineType
      }),
    command := sys.props.get("sbt.jse.command").map(file),
    parallelism := java.lang.Runtime.getRuntime.availableProcessors() + 1,
    npmTimeout := 2.hours,
    npmUseIntegrated := false
  ) ++ inConfig(Assets)(jsEngineUnscopedSettings) ++ inConfig(TestAssets)(jsEngineUnscopedSettings)

}