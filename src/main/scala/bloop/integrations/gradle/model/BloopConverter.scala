package bloop.integrations.gradle.model

import java.io.File
import java.nio.file.Path

import bloop.config.Config
import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.syntax._
import org.gradle.api
import org.gradle.api.{GradleException, Project}
import org.gradle.api.artifacts.{ProjectDependency, ResolvedArtifact}
import org.gradle.api.internal.tasks.compile.{DefaultJavaCompileSpec, JavaCompilerArgumentsBuilder}
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.{ScalaCompile, ScalaCompileOptions}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Define the conversion from Gradle's project model to Bloop's project model.
 * @param parameters Parameters provided by Gradle's user configuration
 */
final class BloopConverter(parameters: BloopParameters) {

  /**
   * Converts a project's given source set to a Bloop project
   *
   * Bloop analysis output will be targetDir/project-name/project[-sourceSet].bin
   *
   * Output classes are generated to projectDir/build/classes/scala/sourceSetName to
   * be compatible with Gradle.
   *
   * NOTE: Java classes will be also put into the above defined directory, not as with Gradle
   *
   * @param strictProjectDependencies Additional dependencies cannot be inferred from Gradle's object model
   * @param project The Gradle project model
   * @param sourceSet The source set to convert
   * @param targetDir Target directory for bloop files
   * @return Bloop configuration
   */
  def toBloopConfig(
      strictProjectDependencies: Set[String],
      project: Project,
      sourceSet: SourceSet,
      targetDir: File
  ): Try[Config.File] = {
    val configuration = project.getConfiguration(sourceSet.getCompileConfigurationName)

    val artifacts: Set[ResolvedArtifact] =
      configuration.getResolvedConfiguration.getResolvedArtifacts.asScala.toSet

    val projectDependencies: Set[ProjectDependency] =
      configuration.getAllDependencies.asScala.collect { case dep: ProjectDependency => dep }.toSet
    val projectDependenciesIds: Set[String] = projectDependencies.map { dep =>
      val project = dep.getDependencyProject
      getProjectName(project, project.getSourceSet(parameters.mainSourceSet))
    }

    // Strict project dependencies should have more priority than regular project dependencies
    val allDependencies = strictProjectDependencies.union(projectDependenciesIds)

    val dependencyClasspath: Set[ResolvedArtifact] = artifacts
      .filter(resolvedArtifact => !isProjectDependency(projectDependencies, resolvedArtifact))

    val classpath: Array[Path] = {
      val projectDependencyClassesDirs: Set[File] =
        projectDependencies.map(dep => getClassesDir(dep.getDependencyProject, sourceSet))
      dependencyClasspath.map(_.getFile).union(projectDependencyClassesDirs).map(_.toPath).toArray
    }

    for {
      scalaConfig <- getScalaConfig(project, sourceSet, artifacts)
      resolution = Config.Resolution(dependencyClasspath.map(artifactToConfigModule).toList)
      bloopProject = Config.Project(
        name = getProjectName(project, sourceSet),
        directory = project.getProjectDir.toPath,
        sources = getSources(sourceSet),
        dependencies = allDependencies.toArray,
        classpath = classpath,
        out = project.getBuildDir.toPath,
        analysisOut = getAnalysisOut(project, sourceSet, targetDir).toPath,
        classesDir = getClassesDir(project, sourceSet).toPath,
        `scala` = scalaConfig,
        java = getJavaConfig(project, sourceSet),
        sbt = Config.Sbt.empty,
        test = Config.Test(testFrameworks, defaultTestOptions), // TODO: make this configurable?
        platform = Config.Platform.default,
        compileSetup = Config.CompileSetup.empty, // TODO: make this configurable?
        resolution = resolution
      )
    } yield Config.File(Config.File.LatestVersion, bloopProject)
  }

  def getProjectName(project: Project, sourceSet: SourceSet): String = {
    if (sourceSet.getName == parameters.mainSourceSet) {
      project.getName
    } else {
      s"${project.getName}-${sourceSet.getName}"
    }
  }

  private def getClassesDir(project: Project, sourceSet: SourceSet): File =
    project.getBuildDir / "classes" / "scala" / sourceSet.getName

  private def getAnalysisOut(project: Project, sourceSet: SourceSet, targetDir: File): File = {
    val name = getProjectName(project, sourceSet)
    targetDir / project.getName / s"$name-analysis.bin"
  }

  private def getSources(sourceSet: SourceSet): Array[Path] =
    sourceSet.getAllSource.asScala.map(_.toPath).toArray

  private def isProjectDependency(
      projectDependencies: Set[ProjectDependency],
      resolvedArtifact: ResolvedArtifact
  ): Boolean = {
    projectDependencies.exists(
      dep =>
        dep.getGroup == resolvedArtifact.getModuleVersion.getId.getGroup &&
          dep.getName == resolvedArtifact.getModuleVersion.getId.getName &&
          dep.getVersion == resolvedArtifact.getModuleVersion.getId.getVersion
    )
  }

  private def artifactToConfigModule(artifact: ResolvedArtifact): Config.Module = {
    Config.Module(
      organization = artifact.getModuleVersion.getId.getGroup,
      name = artifact.getName,
      version = artifact.getModuleVersion.getId.getVersion,
      configurations = None,
      List(
        Config.Artifact(
          name = artifact.getModuleVersion.getId.getName,
          classifier = Option(artifact.getClassifier),
          checksum = None,
          path = artifact.getFile.toPath
        )
      )
    )
  }

  private def getScalaConfig(
      project: Project,
      sourceSet: SourceSet,
      artifacts: Set[ResolvedArtifact]
  ): Try[Config.Scala] = {
    // Finding the compiler group and version from the standard Scala library added as dependency
    val stdLibName = parameters.stdLibName
    val result = artifacts
      .find(_.getName == stdLibName) match {
      case Some(stdLibArtifact) =>
        val scalaVersion = stdLibArtifact.getModuleVersion.getId.getVersion
        val scalaGroup = stdLibArtifact.getModuleVersion.getId.getGroup

        val scalaCompileTaskName = sourceSet.getCompileTaskName("scala")
        val scalaCompileTask = project.getTask[ScalaCompile](scalaCompileTaskName)
        if (scalaCompileTask != null) {
          val compilerClasspath = scalaCompileTask.getScalaClasspath.asScala.map(_.toPath).toArray

          val opts = scalaCompileTask.getScalaCompileOptions
          val compilerOptions = optionSet(opts).toArray

          Success(Config.Scala(
            scalaGroup,
            parameters.compilerName,
            scalaVersion,
            compilerOptions,
            compilerClasspath
          ))
        } else {
          Failure(new GradleException(s"$scalaCompileTaskName task is missing from ${project.getName}"))
        }
      case None =>
        Failure(new GradleException(s"$stdLibName is not added as dependency to ${project.getName}/${sourceSet.getName}. Artifacts: ${artifacts.map(_.getName).mkString("\n")}"))
    }

    result.recoverWith { case failure =>
        val isJavaOnly = sourceSet.getAllSource.asScala.forall(!_.getName.endsWith(".scala"))

        if (isJavaOnly) {
          // For projects where we don't actually have any .scala files we go with an empty scala
          // configuration to be able to compile Java-only project dependencies
          Success(Config.Scala.empty)
        } else {
          Failure(failure)
        }
    }
  }

  private def getJavaConfig(project: Project, sourceSet: SourceSet): Config.Java = {
    val javaCompileTaskName = sourceSet.getCompileTaskName("java")
    val javaCompileTask = project.getTask[JavaCompile](javaCompileTaskName)
    val opts = javaCompileTask.getOptions

    val specs = new DefaultJavaCompileSpec()
    specs.setCompileOptions(opts)

    val builder = new JavaCompilerArgumentsBuilder(specs)
      .includeMainOptions(true)
      .includeClasspath(false)
      .includeSourceFiles(false)
      .includeLauncherOptions(false)
    val args = builder.build().asScala.toArray

    Config.Java(args)
  }

  private def ifEnabled[T](option: Boolean)(value: T): Option[T] =
    if (option) Some(value) else None

  private def optionSet(options: ScalaCompileOptions): Set[String] = {
    // based on ZincScalaCompilerArgumentsGenerator
    val baseOptions: Set[String] = Seq(
      ifEnabled(options.isDeprecation)("-deprecation"),
      ifEnabled(options.isUnchecked)("-unchecked"),
      ifEnabled(options.isOptimize)("-optimize"),
      ifEnabled(options.getDebugLevel == "verbose")("-verbose"),
      ifEnabled(options.getDebugLevel == "debug")("-Ydebug"),
      Option(options.getEncoding).map(encoding => s"-encoding $encoding"),
      Option(options.getDebugLevel).map(level => s"-g:$level")
    ).flatten.toSet

    val loggingPhases: Set[String] =
      Option(options.getLoggingPhases)
        .map(_.asScala.toSet)
        .getOrElse(Set.empty)
        .map(phase => s"-Ylog:$phase")

    val additionalOptions: Set[String] =
      mergeEncodingOption(options.getAdditionalParameters.asScala.toList).toSet

    baseOptions.union(loggingPhases).union(additionalOptions)
  }

  private def mergeEncodingOption(values: List[String]): List[String] =
    values match {
      case "-encoding" :: charset :: rest =>
        s"-encoding $charset" :: mergeEncodingOption(rest)
      case value :: rest =>
        value :: mergeEncodingOption(rest)
      case Nil =>
        Nil
    }

  private val scalaCheckFramework = Config.TestFramework(
    List(
      "org.scalacheck.ScalaCheckFramework"
    ))

  private val scalaTestFramework = Config.TestFramework(
    List(
      "org.scalatest.tools.Framework",
      "org.scalatest.tools.ScalaTestFramework"
    )
  )

  private val specsFramework = Config.TestFramework(
    List(
      "org.specs.runner.SpecsFramework",
      "org.specs2.runner.Specs2Framework",
      "org.specs2.runner.SpecsFramework"
    )
  )

  private val jUnitFramework = Config.TestFramework(
    List(
      "com.novocode.junit.JUnitFramework"
    )
  )

  private val testFrameworks: Array[Config.TestFramework] =
    Array(scalaCheckFramework, scalaTestFramework, specsFramework, jUnitFramework)
  private val defaultTestOptions =
    Config.TestOptions(Nil, List(Config.TestArgument(Array("-v", "-a"), Some(jUnitFramework))))
}
