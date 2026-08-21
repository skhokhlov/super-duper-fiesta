package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DRY_RUN_MARKER = "dry-run"
private const val APPLY_MARKER = "apply"

/**
 * Exact upstream text emitted when a declarative recipe's `recipeList` names a recipe that is
 * not on the plugin's classpath. The plugin logs it at ERROR, then exits 0 having run only the
 * entries it could resolve.
 */
private val UNRESOLVED_SUB_RECIPE_OUTPUT =
    """
    [ERROR] Recipe validation error in demo.BrokenComposite for property demo.BrokenComposite.recipeList[1]: recipe 'com.example.TotallyMissingRecipe' does not exist.
    [ERROR] Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.
    [INFO] BUILD SUCCESS
    """.trimIndent()

class DirectPluginExecutorTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("dpe-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        fun writePatch(): List<DirectPluginPatchFile> {
            val patch = projectDir.resolve("rewrite.patch")
            patch.writeText(
                """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App{}
                +class App { }
                """.trimIndent()
            )
            return listOf(DirectPluginPatchFile(file = patch, baseDir = projectDir))
        }

        fun invocation(
            patchFiles: () -> List<DirectPluginPatchFile>,
            unresolvedRecipeFailure: (String) -> String? = { null }
        ): DirectPluginInvocation = DirectPluginInvocation(
            dryRunCommand = listOf(DRY_RUN_MARKER),
            applyCommand = listOf(APPLY_MARKER),
            patchFiles = patchFiles,
            estimatedTimeSaved = { null },
            dryRunFailureMessage = { "dry run failed with $it" },
            applyFailureMessage = { "apply failed with $it" },
            unresolvedRecipeFailure = unresolvedRecipeFailure
        )

        // The crux of #268: an unresolved sub-recipe coexists with real patches. Gating the
        // check on "no diffs" would miss the entire defect, so this scenario deliberately
        // produces diffs alongside the marker.
        test("exit 0 with an unresolved recipe fails the stage even though diffs were produced") {
            val commands = mutableListOf<List<String>>()
            val executor =
                DirectPluginExecutor(
                    projectDir = projectDir,
                    dryRun = false,
                    execute = { _, command, output ->
                        commands += command
                        output?.append(UNRESOLVED_SUB_RECIPE_OUTPUT)
                        0
                    },
                    executor = LogicalExecutor.MAVEN_PLUGIN
                )

            val result =
                executor.run(
                    invocation(
                        patchFiles = { writePatch() },
                        unresolvedRecipeFailure = { output ->
                            PluginOutputReader.unresolvedRecipeFailure(
                                output,
                                "Maven rewrite:dryRun"
                            )
                        }
                    )
                )

            val failed = assertIs<PluginRunResult.Failed>(result)
            assertTrue("com.example.TotallyMissingRecipe" in failed.reason, failed.reason)
            // The apply goal must not run: a partially initialized recipe must never write a
            // partial migration to disk before the fallback gets its turn.
            assertEquals(listOf(listOf(DRY_RUN_MARKER)), commands)
        }

        test("exit 0 with an unresolved recipe fails the stage when no diffs were produced") {
            val executor =
                DirectPluginExecutor(
                    projectDir = projectDir,
                    dryRun = true,
                    execute = { _, _, output ->
                        output?.append(UNRESOLVED_SUB_RECIPE_OUTPUT)
                        0
                    },
                    executor = LogicalExecutor.GRADLE_PLUGIN
                )

            val result =
                executor.run(
                    invocation(
                        patchFiles = { emptyList() },
                        unresolvedRecipeFailure = { output ->
                            PluginOutputReader.unresolvedRecipeFailure(
                                output,
                                "Gradle rewriteDryRun"
                            )
                        }
                    )
                )

            assertIs<PluginRunResult.Failed>(result)
        }

        test("an unresolved recipe is recorded as a failed dry-run attempt") {
            val attempts = mutableListOf<PluginProcessAttempt>()
            val executor =
                DirectPluginExecutor(
                    projectDir = projectDir,
                    dryRun = false,
                    execute = { _, _, output ->
                        output?.append(UNRESOLVED_SUB_RECIPE_OUTPUT)
                        0
                    },
                    executor = LogicalExecutor.MAVEN_PLUGIN,
                    attemptCollector = attempts::add
                )

            executor.run(
                invocation(
                    patchFiles = { writePatch() },
                    unresolvedRecipeFailure = { output ->
                        PluginOutputReader.unresolvedRecipeFailure(output, "Maven rewrite:dryRun")
                    }
                )
            )

            assertEquals(1, attempts.size)
            assertEquals(ExecutorPhase.PLUGIN_DRY_RUN, attempts.single().phase)
            assertEquals(ExecutorOutcome.FAILED, attempts.single().outcome)
            assertEquals(0, attempts.single().exitCode)
            assertNotNull(attempts.single().message)
        }

        test("clean plugin output still yields a success carrying the diffs") {
            val commands = mutableListOf<List<String>>()
            val executor =
                DirectPluginExecutor(
                    projectDir = projectDir,
                    dryRun = false,
                    execute = { _, command, output ->
                        commands += command
                        output?.append("BUILD SUCCESS")
                        0
                    },
                    executor = LogicalExecutor.MAVEN_PLUGIN
                )

            val result =
                executor.run(
                    invocation(
                        patchFiles = { writePatch() },
                        unresolvedRecipeFailure = { output ->
                            PluginOutputReader.unresolvedRecipeFailure(
                                output,
                                "Maven rewrite:dryRun"
                            )
                        }
                    )
                )

            val success = assertIs<PluginRunResult.Success>(result)
            assertEquals(setOf(Path.of("src/App.java")), success.diffs.keys)
            assertEquals(listOf(listOf(DRY_RUN_MARKER), listOf(APPLY_MARKER)), commands)
        }
    })
