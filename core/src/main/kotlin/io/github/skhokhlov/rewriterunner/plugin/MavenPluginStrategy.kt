package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.LogicalExecutor
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.lst.utils.resolveMavenCommand
import io.github.skhokhlov.rewriterunner.lst.utils.runProcess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

/**
 * Maven-side [PluginBuildStrategy] implementation.
 *
 * Pins the plugin's patch output to a private temp directory via
 * `-DreportOutputDirectory=<dir>` (note: the rewrite-maven-plugin's documented user property
 * is unprefixed — `-Drewrite.reportOutputDirectory=` is silently ignored). This sidesteps
 * per-version default-path drift (older docs say `target/site/rewrite/`, current plugin
 * defaults to `target/rewrite/`). Also pins `-Drewrite.runPerSubmodule=false` so user pom
 * configuration cannot redirect the plugin into per-submodule mode that would
 * race/overwrite the shared output file.
 *
 * Forwards `excludePaths` to the upstream `rewrite-maven-plugin` via the
 * `-Drewrite.exclusions=<csv>` system property, joined into a comma-separated list of globs.
 * Forwards `plainTextMasks` the same way via `-Drewrite.plainTextMasks=<csv>`.
 */
internal open class MavenPluginStrategy(
    private val logger: RunnerLogger,
    private val timeout: Duration,
    private val rewritePluginVersion: String,
    private val pluginJvmArgs: List<String> = emptyList(),
    private val attemptCollector: (PluginProcessAttempt) -> Unit = {}
) : PluginBuildStrategy {
    override fun run(
        projectDir: Path,
        rootDir: Path,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        rewriteConfigContent: String?,
        dryRun: Boolean,
        includeMavenCentral: Boolean,
        artifactRepositories: List<RepositoryConfig>,
        excludePaths: List<String>,
        plainTextMasks: List<String>
    ): PluginRunResult {
        val effectiveRewriteConfig =
            createRewriteConfigFile(rewriteConfigContent)
                ?: rewriteConfig
        val reportDir = createPrivateTempDirectory("rewrite-runner-report-")
        return try {
            DirectPluginExecutor(
                projectDir = rootDir,
                dryRun = dryRun,
                execute = ::execute,
                runDir = projectDir,
                executor = LogicalExecutor.MAVEN_PLUGIN,
                attemptCollector = attemptCollector
            ).run(
                DirectPluginInvocation(
                    dryRunCommand = buildCommand(
                        projectDir = projectDir,
                        rootDir = rootDir,
                        goal = "dryRun",
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = effectiveRewriteConfig,
                        reportOutputDirectory = reportDir,
                        excludePaths = excludePaths,
                        plainTextMasks = plainTextMasks
                    ),
                    applyCommand = buildCommand(
                        projectDir = projectDir,
                        rootDir = rootDir,
                        goal = "run",
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = effectiveRewriteConfig,
                        reportOutputDirectory = reportDir,
                        excludePaths = excludePaths,
                        plainTextMasks = plainTextMasks
                    ),
                    patchFiles = { findPatchFiles(projectDir, reportDir) },
                    estimatedTimeSaved = { output ->
                        EstimatedTimeSavedResolver.resolve(
                            listOf(
                                {
                                    DataTableReader.sumEstimatedTimeSaved(
                                        reportDir.resolve("datatables")
                                    )
                                },
                                {
                                    DataTableReader.sumEstimatedTimeSaved(
                                        projectDir.resolve("target/rewrite/datatables")
                                    )
                                },
                                { PluginOutputReader.estimatedTimeSaved(output) }
                            )
                        )
                    },
                    dryRunFailureMessage = { pluginFailureMessage("Maven rewrite:dryRun", it) },
                    applyFailureMessage = { pluginFailureMessage("Maven rewrite:run", it) },
                    unresolvedRecipeFailure = { output ->
                        PluginOutputReader.unresolvedRecipeFailure(output, "Maven rewrite:dryRun")
                    }
                )
            )
        } finally {
            try {
                deleteRecursively(reportDir)
            } catch (e: Exception) {
                logger.warn("Maven plugin: failed to clean up report dir $reportDir: ${e.message}")
            }
            if (rewriteConfigContent != null && effectiveRewriteConfig != null) {
                Files.deleteIfExists(effectiveRewriteConfig)
            }
        }
    }

    fun buildCommand(
        projectDir: Path,
        rootDir: Path = projectDir,
        goal: String,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        reportOutputDirectory: Path,
        excludePaths: List<String> = emptyList(),
        plainTextMasks: List<String> = emptyList()
    ): List<String> = buildList {
        add(resolveMavenCommand(projectDir, rootDir))
        add("-U")
        add("--no-transfer-progress")
        add("--batch-mode")
        add(
            "org.openrewrite.maven:rewrite-maven-plugin:" +
                "$rewritePluginVersion:$goal"
        )
        add("-Drewrite.activeRecipes=$activeRecipe")
        // The rewrite-maven-plugin's documented user property for reportOutputDirectory is
        // unprefixed (see https://openrewrite.github.io/rewrite-maven-plugin/dryRun-mojo.html);
        // -Drewrite.reportOutputDirectory is silently ignored. runPerSubmodule, by contrast, is
        // exposed as `rewrite.runPerSubmodule`.
        add("-DreportOutputDirectory=${reportOutputDirectory.toAbsolutePath()}")
        add("-Drewrite.exportDatatables=true")
        add("-Drewrite.runPerSubmodule=false")
        if (recipeArtifacts.isNotEmpty()) {
            add("-Drewrite.recipeArtifactCoordinates=${recipeArtifacts.joinToString(",")}")
        }
        if (excludePaths.isNotEmpty()) {
            add("-Drewrite.exclusions=${excludePaths.joinToString(",")}")
        }
        if (plainTextMasks.isNotEmpty()) {
            add("-Drewrite.plainTextMasks=${plainTextMasks.joinToString(",")}")
        }
        rewriteConfig?.let {
            add("-Drewrite.configLocation=${it.toAbsolutePath()}")
        }
    }

    open fun execute(projectDir: Path, command: List<String>, output: StringBuilder? = null): Int? =
        runProcess(
            workDir = projectDir,
            command = command,
            captureOutput = output,
            timeout = timeout,
            timeoutName = "pluginTimeout",
            env = buildEnv(),
            logger = logger
        )

    /**
     * Build the subprocess environment overrides for the Maven plugin invocation.
     *
     * When [pluginJvmArgs] is non-empty, our args are appended **after** any inherited
     * [existingMavenOpts] so that on a conflicting flag (e.g. `-Xmx`) ours wins (last value
     * wins) while the user's other options are preserved. The standard Maven 3.x launcher
     * (`bin/mvn` and `bin/mvn.cmd`) places a project `.mvn/jvm.config` **before** `MAVEN_OPTS` on
     * the `java` command line, so our `MAVEN_OPTS` also wins over `.mvn/jvm.config` for conflicting
     * flags; the project's non-conflicting `jvm.config` entries still apply (they remain on the
     * command line). Returns an empty map (no override) when no args are configured, leaving the
     * inherited environment untouched.
     */
    internal fun buildEnv(
        existingMavenOpts: String? = System.getenv("MAVEN_OPTS")
    ): Map<String, String> {
        if (pluginJvmArgs.isEmpty()) return emptyMap()
        val ours = pluginJvmArgs.joinToString(" ")
        val merged = listOfNotNull(existingMavenOpts?.takeIf { it.isNotBlank() }, ours)
            .joinToString(" ")
        return mapOf("MAVEN_OPTS" to merged)
    }

    private fun findPatchFiles(projectDir: Path, reportDir: Path): List<DirectPluginPatchFile> {
        val patch = reportDir.resolve("rewrite.patch")
        if (!patch.exists()) return emptyList()
        return listOf(DirectPluginPatchFile(file = patch, baseDir = projectDir))
    }
}
