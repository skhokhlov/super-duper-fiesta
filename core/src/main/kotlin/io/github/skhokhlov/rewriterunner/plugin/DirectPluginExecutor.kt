package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText

internal data class DirectPluginPatchFile(val file: Path, val baseDir: Path)

internal data class DirectPluginInvocation(
    val dryRunCommand: List<String>,
    val applyCommand: List<String>,
    val patchFiles: () -> List<DirectPluginPatchFile>,
    val estimatedTimeSaved: (String) -> Duration?,
    val dryRunFailureMessage: (Int?) -> String,
    val applyFailureMessage: (Int?) -> String,
    /**
     * Verdict on captured plugin output: a failure reason when the build tool reported that it
     * could not resolve a recipe it was asked to run, `null` when it reported no such gap.
     *
     * Per-strategy, mirroring [estimatedTimeSaved], so tool-specific wording (and, should the
     * plugins ever diverge, tool-specific markers) stays with the strategy that owns the tool.
     * Both strategies currently delegate to [PluginOutputReader.unresolvedRecipeFailure] because
     * the markers originate in shared rewrite-core code and are byte-identical across the two
     * plugins; duplicating the literals per tool would only invite drift.
     */
    val unresolvedRecipeFailure: (String) -> String?
)

/**
 * Runs an official OpenRewrite build plugin through the target project's build tool.
 *
 * The build-tool-specific strategies only construct commands and patch locations; this
 * class owns the shared execution contract:
 * 1. run dry-run to generate a patch,
 * 2. parse generated patch files for formatted output,
 * 3. run apply when changes exist and the caller is not in dry-run mode.
 *
 * @property projectDir Repository root used to rebase patch paths. Diffs and result keys are
 *   reported relative to this directory.
 * @property runDir Working directory the build command actually runs in. Equals [projectDir]
 *   for a root-level invocation; for an orphan (root-less monorepo) build unit it is the
 *   subdirectory of the unit, so patches produced there are rebased to [projectDir].
 */
internal class DirectPluginExecutor(
    private val projectDir: Path,
    private val dryRun: Boolean,
    private val execute: (Path, List<String>, StringBuilder?) -> Int?,
    private val runDir: Path = projectDir,
    private val executor: LogicalExecutor,
    private val attemptCollector: (PluginProcessAttempt) -> Unit = {}
) {
    fun run(invocation: DirectPluginInvocation): PluginRunResult {
        val pluginOutput = StringBuilder()
        val dryRunStartedAt = System.nanoTime()
        val dryRunExit = execute(runDir, invocation.dryRunCommand, pluginOutput)
        val dryRunDurationMillis = elapsedMillis(dryRunStartedAt)
        if (dryRunExit != 0) {
            val message = invocation.dryRunFailureMessage(dryRunExit)
            record(
                phase = ExecutorPhase.PLUGIN_DRY_RUN,
                durationMillis = dryRunDurationMillis,
                outcome = failureOutcome(dryRunExit, message),
                exitCode = dryRunExit,
                message = message
            )
            return PluginRunResult.Failed(message)
        }

        // Exit 0 is not evidence that the plugin ran the whole recipe: a declarative recipe whose
        // recipeList names a missing recipe logs the gap at ERROR, runs the entries it could
        // resolve, and still exits 0 with real patches. Verify the stage's own success from the
        // captured output instead of trusting the exit code, and do it before the diff check so a
        // gap is caught whether or not patches were produced. Failing here (rather than after
        // apply) also keeps a partial migration off disk; the caller falls through to the LST
        // stage, whose recipe classpath is resolved independently and may hold what was missing.
        invocation.unresolvedRecipeFailure(pluginOutput.toString())?.let { message ->
            record(
                phase = ExecutorPhase.PLUGIN_DRY_RUN,
                durationMillis = dryRunDurationMillis,
                outcome = ExecutorOutcome.FAILED,
                exitCode = dryRunExit,
                message = message
            )
            return PluginRunResult.Failed(message)
        }

        val diffs = parseDiffs(invocation.patchFiles())
        if (diffs.isEmpty()) {
            record(
                phase = ExecutorPhase.PLUGIN_DRY_RUN,
                durationMillis = dryRunDurationMillis,
                outcome = ExecutorOutcome.NO_CHANGES,
                exitCode = dryRunExit
            )
            return PluginRunResult.NoChanges
        }
        record(
            phase = ExecutorPhase.PLUGIN_DRY_RUN,
            durationMillis = dryRunDurationMillis,
            outcome = ExecutorOutcome.SUCCESS,
            exitCode = dryRunExit
        )

        if (!dryRun) {
            val applyStartedAt = System.nanoTime()
            val applyExit = execute(runDir, invocation.applyCommand, pluginOutput)
            val applyDurationMillis = elapsedMillis(applyStartedAt)
            if (applyExit != 0) {
                val message = invocation.applyFailureMessage(applyExit)
                record(
                    phase = ExecutorPhase.PLUGIN_APPLY,
                    durationMillis = applyDurationMillis,
                    outcome = failureOutcome(applyExit, message),
                    exitCode = applyExit,
                    message = message
                )
                return PluginRunResult.Failed(message)
            }
            record(
                phase = ExecutorPhase.PLUGIN_APPLY,
                durationMillis = applyDurationMillis,
                outcome = ExecutorOutcome.SUCCESS,
                exitCode = applyExit
            )
        }

        return PluginRunResult.Success(
            changedFiles = if (dryRun) emptyList() else diffs.keys.map(projectDir::resolve),
            diffs = diffs,
            estimatedTimeSaved = invocation.estimatedTimeSaved(pluginOutput.toString())
        )
    }

    private fun record(
        phase: ExecutorPhase,
        durationMillis: Long,
        outcome: ExecutorOutcome,
        exitCode: Int?,
        message: String? = null
    ) {
        attemptCollector(
            PluginProcessAttempt(
                executor = executor,
                phase = phase,
                workingDirectory = relativeWorkingDirectory(),
                durationMillis = durationMillis,
                outcome = outcome,
                exitCode = exitCode,
                message = message
            )
        )
    }

    private fun relativeWorkingDirectory(): String {
        val root = projectDir.toAbsolutePath().normalize()
        val workingDirectory = runDir.toAbsolutePath().normalize()
        if (!workingDirectory.startsWith(root)) return "."
        return root.relativize(workingDirectory).toString().replace('\\', '/').ifEmpty { "." }
    }

    private fun failureOutcome(exitCode: Int?, message: String): ExecutorOutcome = when {
        message.contains("OutOfMemoryError", ignoreCase = true) ->
            ExecutorOutcome.CONFIRMED_HEAP_OOM

        exitCode == 137 || message.contains("137") -> ExecutorOutcome.LIKELY_OOM

        else -> ExecutorOutcome.FAILED
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun parseDiffs(patchFiles: List<DirectPluginPatchFile>): Map<Path, String> = patchFiles
        .filter { it.file.exists() }
        .flatMap { patchFile ->
            val baseRelative = relativeToProject(patchFile.baseDir)
            PatchParser.parse(patchFile.file.readText()).map { (path, diff) ->
                val rebasedPath = relativeToProject(patchFile.baseDir.resolve(path))
                rebasedPath to rebaseDiff(diff, baseRelative)
            }
        }.toMap()

    private fun relativeToProject(path: Path): Path {
        val normalizedProject = projectDir.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        return normalizedProject.relativize(normalizedPath)
    }

    private fun rebaseDiff(diff: String, baseRelative: Path): String {
        if (baseRelative.toString().isEmpty()) return diff

        val prefix = baseRelative.toString().replace('\\', '/')
        return diff.lineSequence()
            .joinToString("\n") { line ->
                when {
                    line.startsWith("diff --git ") -> rebaseGitHeader(line, prefix)
                    line.startsWith("--- ") -> rebasePatchHeader(line, prefix)
                    line.startsWith("+++ ") -> rebasePatchHeader(line, prefix)
                    else -> line
                }
            } + if (diff.endsWith("\n")) "\n" else ""
    }

    private fun rebaseGitHeader(line: String, prefix: String): String {
        val parts = line.split(" ")
        if (parts.size != 4) return line
        return listOf(
            parts[0],
            parts[1],
            parts[2].prefixDiffPath(prefix),
            parts[3].prefixDiffPath(prefix)
        )
            .joinToString(" ")
    }

    private fun rebasePatchHeader(line: String, prefix: String): String {
        val marker = line.take(4)
        val rest = line.drop(4)
        val path = rest.substringBefore('\t')
        val suffix = rest.removePrefix(path)
        return marker + path.prefixDiffPath(prefix) + suffix
    }

    private fun String.prefixDiffPath(prefix: String): String = when {
        this == "/dev/null" -> this
        startsWith("a/") -> "a/$prefix/${removePrefix("a/")}"
        startsWith("b/") -> "b/$prefix/${removePrefix("b/")}"
        else -> "$prefix/$this"
    }
}
