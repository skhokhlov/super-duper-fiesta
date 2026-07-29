package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.lst.SpecializedOwnership
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PluginFirstIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("pfit-project-")
            cacheDir = Files.createTempDirectory("pfit-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // Installs a fake gradlew whose before/after content is derived from the scenario.
        // Assumes exactly one entry in scenario.expectedAfterFiles (single-file scenarios only).
        fun setupFakeGradlew(scenario: PluginScenario) {
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeGradlew(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent
            )
        }

        // Installs a simple fake mvnw (no flag protocol validation) derived from the scenario.
        // Assumes exactly one entry in scenario.expectedAfterFiles (single-file scenarios only).
        fun setupFakeMvnwSimple(scenario: PluginScenario) {
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeMvnwSimple(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent
            )
        }

        // Installs the protocol-validating fake mvnw derived from the scenario (see
        // Path.writeFakeMvnwWithProtocolChecks). Assumes exactly one entry in
        // scenario.expectedAfterFiles (single-file scenarios only).
        fun setupFakeMvnwWithProtocolChecks(scenario: PluginScenario) {
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeMvnwWithProtocolChecks(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent
            )
        }

        test(
            "plugin-first Gradle path formats raw diffs and applies changes"
        ).config(enabled = !isWindows) {
            setupFakeGradlew(PluginScenarios.gradleSingleFile)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.gradleSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--output",
                    "files"
                )

            assertEquals(0, result.exitCode)
            assertEquals("src/main/java/App.java", result.stdout.trim())
            assertEquals(
                PluginScenarios.gradleSingleFile.expectedAfterFiles["src/main/java/App.java"],
                projectDir.resolve("src/main/java/App.java").toFile().readText()
            )
            // Ordering: dry-run must precede apply, and apply must run exactly once.
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        test(
            "plugin-first Gradle path populates estimated time saved"
        ).config(enabled = !isWindows) {
            setupFakeGradlew(PluginScenarios.gradleSingleFile)

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(PluginScenarios.gradleSingleFile.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofSeconds(420),
                result.executionDiagnostics.estimatedTimeSaved
            )
            val pluginAttempts = result.executionDiagnostics.executorAttempts
                .filter { it.executor == LogicalExecutor.GRADLE_PLUGIN }
            assertEquals(
                listOf(ExecutorPhase.PLUGIN_DRY_RUN, ExecutorPhase.PLUGIN_APPLY),
                pluginAttempts.map { it.phase }
            )
            assertEquals(listOf(".", "."), pluginAttempts.map { it.workingDirectory })
        }

        test(
            "plugin-first Gradle path falls back when data table estimate is zero"
        ).config(enabled = !isWindows) {
            val scenario = PluginScenarios.gradleSingleFile
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeGradlewWithHeaderOnlyDataTablesAndEstimate(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent,
                estimate = "7m"
            )

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(scenario.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofMinutes(7),
                result.executionDiagnostics.estimatedTimeSaved
            )
        }

        test(
            "plugin-first Gradle path merges raw plugin diffs with specialized Docker results"
        ).config(enabled = !isWindows) {
            projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            projectDir.resolve("build.gradle.kts").writeText("plugins { java }\n")
            projectDir.resolve("src/main/java").toFile().mkdirs()
            val javaFile = projectDir.resolve("src/main/java/App.java")
            javaFile.writeText("class App{}\n")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "22.04")
            projectDir.writeFakeGradlewWithExclusionChecks(
                targetFile = "src/main/java/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n",
                requiredExclusions = SpecializedOwnership.stage0ExcludeGlobs
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.integration.FindAndReplace")
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            assertTrue((runResult.executionDiagnostics.parsedFileCount ?: 0) > 0)
            assertEquals(
                setOf(Path.of("src/main/java/App.java"), Path.of("Dockerfile")),
                runResult.rawDiffs.keys
            )
            assertTrue(runResult.results.isEmpty())
            assertEquals("class App { }\n", javaFile.readText())
            assertEquals("FROM ubuntu:22.04\n", dockerfile.readText())
            assertEquals(setOf(javaFile, dockerfile), runResult.changedFiles.toSet())
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        // #268: the plugin logs an unresolvable recipeList entry at ERROR, runs only the entries
        // it could resolve, and exits 0 with real patches. Stage 0 must not claim that as a win —
        // the LST stage resolves recipes from an independent classpath and may run the whole
        // recipe, so the run has to fall through to it.
        test(
            "an unresolved sub-recipe reported by the plugin falls through to the LST stage"
        ).config(enabled = !isWindows) {
            val scenario = PluginScenarios.gradleSingleFile
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeGradlewWithUnresolvedSubRecipe(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(scenario.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertNotEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            // rewriteRun must never have been reached: a partially initialized recipe must not
            // write a partial migration to disk before the fallback gets its turn.
            assertEquals(
                "rewriteDryRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
            // The LST stage resolves com.example.integration.FindAndReplace from rewrite.yaml and
            // completes the change the plugin never applied. Containment, not an exact key set:
            // unlike Stage 0 the fallback parses the whole tree, so this FindAndReplace also
            // rewrites its own `find:` literal inside rewrite.yaml and the fake wrapper script.
            assertEquals(afterContent, projectDir.resolve(relPath).readText())
            assertTrue(
                Path.of(relPath) in runResult.rawDiffs.keys,
                "rawDiffs=${runResult.rawDiffs.keys}"
            )
            // The reason has to survive into diagnostics, naming the recipe that did not resolve.
            val dryRunAttempt =
                runResult.executionDiagnostics.executorAttempts.single {
                    it.executor == LogicalExecutor.GRADLE_PLUGIN
                }
            assertEquals(ExecutorPhase.PLUGIN_DRY_RUN, dryRunAttempt.phase)
            assertEquals(ExecutorOutcome.FAILED, dryRunAttempt.outcome)
            assertTrue(
                MISSING_SUB_RECIPE in (dryRunAttempt.message ?: ""),
                "message=${dryRunAttempt.message}"
            )
        }

        test(
            "an excluded specialized file does not start a specialized worker after plugin success"
        ).config(enabled = !isWindows) {
            setupFakeGradlew(PluginScenarios.gradleSingleFile)
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:PLACEHOLDER\n")

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(PluginScenarios.gradleSingleFile.activeRecipe)
                    .cacheDir(cacheDir)
                    .excludePaths(listOf("Dockerfile"))
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertFalse(
                result.executionDiagnostics.executorAttempts.any {
                    it.executor == LogicalExecutor.LST_WORKER
                }
            )
        }

        test(
            "specialized pass recipe-load failure does not fail a successful plugin run"
        ).config(enabled = !isWindows) {
            // Stage 0 succeeds (the fake plugin patches the Java file) and the project
            // contains an owned Dockerfile, but the active recipe is known ONLY to the
            // project's own build — there is no rewrite.yaml and no recipe artifact, so the
            // in-process specialized pass cannot resolve it. The pass must degrade to
            // plugin-only results rather than throwing and failing an already-successful run.
            projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            projectDir.resolve("build.gradle.kts").writeText("plugins { java }\n")
            projectDir.resolve("src/main/java").toFile().mkdirs()
            val javaFile = projectDir.resolve("src/main/java/App.java")
            javaFile.writeText("class App{}\n")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFakeGradlew(
                targetFile = "src/main/java/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n"
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.only.known.to.plugin.Recipe")
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            assertEquals(setOf(Path.of("src/main/java/App.java")), runResult.rawDiffs.keys)
            assertTrue(runResult.results.isEmpty())
            // Dockerfile untouched because the specialized recipe never ran.
            assertEquals("FROM ubuntu:PLACEHOLDER\n", dockerfile.readText())
            assertEquals("class App { }\n", javaFile.readText())
        }

        test(
            "plugin-first Maven path with no owned files keeps plugin-only diagnostics"
        ).config(enabled = !isWindows) {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test</artifactId>
                  <version>1.0-SNAPSHOT</version>
                </project>
                """.trimIndent()
            )
            projectDir.resolve("src/main/java").toFile().mkdirs()
            val javaFile = projectDir.resolve("src/main/java/App.java")
            javaFile.writeText("class App{}\n")
            projectDir.writeFakeMvnwWithExclusionChecks(
                targetFile = "src/main/java/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n",
                requiredExclusions = SpecializedOwnership.stage0ExcludeGlobs
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.integration.FindAndReplace")
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            assertEquals(null, runResult.executionDiagnostics.parsedFileCount)
            assertTrue(runResult.results.isEmpty())
            assertEquals(setOf(Path.of("src/main/java/App.java")), runResult.rawDiffs.keys)
            assertEquals("class App { }\n", javaFile.readText())
            assertEquals(listOf(javaFile), runResult.changedFiles)
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        test(
            "plugin-first runs in an orphan subdir unit when the root has no build file"
        ).config(enabled = !isWindows) {
            // Root-less monorepo: no build file at the root, one Gradle build unit in svc-a/,
            // and a shared root gradlew wrapper (the unit carries none, so the root wrapper
            // is used). Stage 0 must discover svc-a, run the plugin there, and rebase the diff
            // to svc-a/ at the repository root.
            val unit = projectDir.resolve("svc-a")
            unit.resolve("src/main/java").toFile().mkdirs()
            unit.resolve("build.gradle.kts").writeText("plugins { java }\n")
            val javaFile = unit.resolve("src/main/java/App.java")
            javaFile.writeText("class App{}\n")
            projectDir.writeFakeGradlew(
                targetFile = "src/main/java/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n"
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.integration.FindAndReplace")
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            assertEquals(
                setOf(Path.of("svc-a/src/main/java/App.java")),
                runResult.rawDiffs.keys
            )
            assertEquals("class App { }\n", javaFile.readText())
            assertEquals(listOf(javaFile), runResult.changedFiles)
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
            val pluginAttempts = runResult.executionDiagnostics.executorAttempts
                .filter { it.executor == LogicalExecutor.GRADLE_PLUGIN }
            assertEquals(
                listOf(ExecutorPhase.PLUGIN_DRY_RUN, ExecutorPhase.PLUGIN_APPLY),
                pluginAttempts.map { it.phase }
            )
            assertEquals(listOf("svc-a", "svc-a"), pluginAttempts.map { it.workingDirectory })
        }

        test(
            "plugin diagnostics preserve the Gradle failure and actual Maven fallback"
        ).config(enabled = !isWindows) {
            setupFakeMvnwSimple(PluginScenarios.mavenSingleFile)
            projectDir.resolve("build.gradle.kts").writeText("plugins { java }\n")
            projectDir.writeFakeExitOneWrapper("gradlew")

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(PluginScenarios.mavenSingleFile.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            val pluginAttempts = result.executionDiagnostics.executorAttempts.filter {
                it.executor == LogicalExecutor.GRADLE_PLUGIN ||
                    it.executor == LogicalExecutor.MAVEN_PLUGIN
            }
            assertEquals(
                listOf(
                    LogicalExecutor.GRADLE_PLUGIN,
                    LogicalExecutor.MAVEN_PLUGIN,
                    LogicalExecutor.MAVEN_PLUGIN
                ),
                pluginAttempts.map { it.executor }
            )
            assertEquals(
                listOf(
                    ExecutorPhase.PLUGIN_DRY_RUN,
                    ExecutorPhase.PLUGIN_DRY_RUN,
                    ExecutorPhase.PLUGIN_APPLY
                ),
                pluginAttempts.map { it.phase }
            )
            assertEquals(listOf(".", ".", "."), pluginAttempts.map { it.workingDirectory })
            assertEquals(ExecutorOutcome.FAILED, pluginAttempts.first().outcome)
            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
        }

        // This test specifically tests CLI bypass behavior, not recipe execution.
        // It intentionally sets up a project WITHOUT a rewrite.yaml so the LST pipeline
        // cannot find `com.example.Recipe`, producing a non-zero exit code.
        test("--skip-plugin-run bypasses fake plugin path").config(enabled = !isWindows) {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("src").toFile().mkdirs()
            projectDir.resolve("src/App.java").writeText("class App{}\n")
            projectDir.writeFakeGradlew(
                targetFile = "src/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n"
            )

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.Recipe",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--skip-plugin-run"
                )

            assertTrue(result.exitCode != 0)
            assertEquals("class App{}\n", projectDir.resolve("src/App.java").toFile().readText())
        }

        test(
            "plugin-first Maven path applies patch via reportOutputDirectory and mutates source"
        ).config(enabled = !isWindows) {
            setupFakeMvnwSimple(PluginScenarios.mavenSingleFile)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.mavenSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--output",
                    "files"
                )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            assertEquals("src/main/java/App.java", result.stdout.trim())
            assertEquals(
                PluginScenarios.mavenSingleFile.expectedAfterFiles["src/main/java/App.java"],
                projectDir.resolve("src/main/java/App.java").toFile().readText()
            )
            // Order: dryRun discovers the patch, then run applies it. Exactly one of each.
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        test(
            "plugin-first Maven path populates estimated time saved"
        ).config(enabled = !isWindows) {
            setupFakeMvnwSimple(PluginScenarios.mavenSingleFile)

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(PluginScenarios.mavenSingleFile.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofSeconds(420),
                result.executionDiagnostics.estimatedTimeSaved
            )
        }

        test(
            "plugin-first Maven path falls back when data table estimates are zero"
        ).config(enabled = !isWindows) {
            val scenario = PluginScenarios.mavenSingleFile
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeMvnwWithHeaderOnlyDataTablesAndEstimate(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent,
                estimate = "9m"
            )

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(scenario.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofMinutes(9),
                result.executionDiagnostics.estimatedTimeSaved
            )
        }

        test(
            "plugin-first Maven dry-run produces diff without invoking rewrite:run"
        ).config(enabled = !isWindows) {
            setupFakeMvnwSimple(PluginScenarios.mavenSingleFile)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.mavenSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--dry-run",
                    "--output",
                    "diff"
                )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            // Source must be untouched on dry-run.
            assertEquals(
                "class App{}\n",
                projectDir.resolve("src/main/java/App.java").toFile().readText()
            )
            assertTrue(
                "-class App{}" in result.stdout && "+class App { }" in result.stdout,
                "expected dry-run diff in stdout, got:\n${result.stdout}"
            )
            // rewrite:run must not be invoked under --dry-run.
            assertEquals(
                "dryRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        // Validates that MavenPluginStrategy sends the correct flag format to the Maven wrapper:
        // - unprefixed `-DreportOutputDirectory=` (not `-Drewrite.reportOutputDirectory=`)
        // - `-Drewrite.runPerSubmodule=false`
        // - `-Drewrite.exportDatatables=true`
        // A regression to the wrong flag format causes the fake wrapper to exit 2 here.
        test(
            "maven fake-wrapper flag protocol: report directory, runPerSubmodule, and datatables"
        ).config(enabled = !isWindows) {
            setupFakeMvnwWithProtocolChecks(PluginScenarios.mavenSingleFile)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.mavenSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--output",
                    "files"
                )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            assertEquals("src/main/java/App.java", result.stdout.trim())
            // Ordering: dryRun must precede run, and run must run exactly once.
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }
    })
