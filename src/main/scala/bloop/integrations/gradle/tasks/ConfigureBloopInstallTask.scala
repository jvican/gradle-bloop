package bloop.integrations.gradle.tasks

import scala.collection.JavaConverters._

import bloop.integrations.gradle.syntax._

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.Action
import org.gradle.api.artifacts.ArtifactView.ViewConfiguration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.specs.Spec
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Attribute
import org.gradle.api.artifacts.Configuration

/**
 * Task to set the bloopInstall tasks's inputs
 *
 * The bloopInstall task depends on the dependency resolution, so it cannot
 * decide its inputs (used by the up-to-date checker) in configuration time.
 * This task always runs and in build time uses the resolved artifacts and the
 * source sets to set up the associated bloopInstall tasks's input
 * dependencies.
 */
class ConfigureBloopInstallTask extends DefaultTask with PluginUtils with TaskLogging {
  override val project: Project = getProject

  /**
   * The install task to set runtime, automatically set at plugin application
   */
  var installTask: Option[Task] = None

  @TaskAction
  def run(): Unit = {
    installTask match {
      case Some(task) =>
        if (hasJavaScalaPlugin) {
          // Guard to avoid accessing java-related information (source sets) for non-Java projects
          project.allSourceSets.foreach(addSourceSetAsInputs(task, _))
        }
      case None =>
        throw new GradleException(
          "installTask property must be specified on configureBloopInstall task"
        )
    }

    ()
  }

  /**
   * Adds both the sources and the source set's configuration's resolved
   * dependencies to the given tasks' inputs
   */
  private def addSourceSetAsInputs(task: Task, sourceSet: SourceSet): Unit = {
    val artifactType = Attribute.of("artifactType", classOf[String])

    def resolveNonProjectConfigurationArtifacts(config: Configuration) = config.getIncoming
      .artifactView(new Action[ViewConfiguration] {
        override def execute(viewConfig: ViewConfiguration): Unit = {
          viewConfig
            .lenient(true)
            .componentFilter(new Spec[ComponentIdentifier] {
              def isSatisfiedBy(id: ComponentIdentifier): Boolean = {
                // Filter out projects
                !id.isInstanceOf[ProjectComponentIdentifier]
              }
            })
            .attributes(new Action[AttributeContainer] {
              override def execute(
                  attributeContainer: AttributeContainer
              ): Unit = attributeContainer.attribute(artifactType, "jar")
            })
          ()
        }
      })
      .getArtifacts
      .asScala
      .toList

    val compileConfiguration =
      project.getConfiguration(sourceSet.getCompileClasspathConfigurationName)
    val runtimeConfiguration =
      project.getConfiguration(sourceSet.getRuntimeClasspathConfigurationName)

    val nonProjectArtifacts =
      resolveNonProjectConfigurationArtifacts(compileConfiguration) ++
        resolveNonProjectConfigurationArtifacts(runtimeConfiguration)

    // This will add both compile and runtime
    for (artifact <- nonProjectArtifacts) {
      debug(s"[Bloop] Artifact added as input: ${artifact.getFile.getAbsolutePath}")
      task.getInputs.file(artifact.getFile)
    }

    for (source <- sourceSet.getAllSource.asScala) {
      debug(s"[Bloop] Source added as input: ${source.getAbsolutePath}")
      task.getInputs.file(source)
    }
  }
}
