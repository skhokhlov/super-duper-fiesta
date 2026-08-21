package io.github.skhokhlov.rewriterunner.plugin

import io.kotest.core.spec.style.FunSpec
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginOutputReaderTest :
    FunSpec({
        test("reads estimate time saved duration from plugin output") {
            assertEquals(
                Duration.ofHours(1).plusMinutes(2).plusSeconds(3),
                PluginOutputReader.estimatedTimeSaved(
                    """
                    [WARNING] Results:
                    [WARNING] Estimate time saved: 1h 2m 3s
                    """.trimIndent()
                )
            )
        }

        test("uses the last estimate when dry-run and apply output are both present") {
            assertEquals(
                Duration.ofMinutes(7),
                PluginOutputReader.estimatedTimeSaved(
                    """
                    Estimate time saved: 5m
                    Please review and commit the results.
                    Estimate time saved: 7m
                    """.trimIndent()
                )
            )
        }

        test("returns null when no plugin estimate is present") {
            assertNull(PluginOutputReader.estimatedTimeSaved("No changes produced."))
        }

        // The marker strings below are pinned literals lifted from the plugin versions this
        // project ships against — `recipe '<fqn>' does not exist.` and `Recipe(s) not found: %s`
        // from rewrite-core's DeclarativeRecipe/Environment, and the activateRecipe(s) /
        // "No recipes were activated." lines from AbstractRewriteBaseRunMojo (Maven) and
        // DefaultProjectParser (Gradle). Anchoring on the exact upstream text is what keeps
        // ordinary verbose build output from tripping the check.
        test("reports a declarative sub-recipe that upstream could not resolve") {
            val reason =
                PluginOutputReader.unresolvedRecipeFailure(
                    """
                    [INFO] Using active recipe(s) [demo.BrokenComposite]
                    [ERROR] Recipe validation error in demo.BrokenComposite for property demo.BrokenComposite.recipeList[1]: recipe 'com.example.TotallyMissingRecipe' does not exist.
                    [ERROR] Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.
                    [INFO] BUILD SUCCESS
                    """.trimIndent(),
                    "Maven rewrite:dryRun"
                )

            assertNotNull(reason)
            assertTrue("Maven rewrite:dryRun" in reason, reason)
            assertTrue("com.example.TotallyMissingRecipe" in reason, reason)
        }

        test("reports every distinct missing sub-recipe exactly once") {
            val reason =
                PluginOutputReader.unresolvedRecipeFailure(
                    """
                    [ERROR] recipe 'com.example.MissingOne' does not exist.
                    [ERROR] recipe 'com.example.MissingTwo' does not exist.
                    [ERROR] recipe 'com.example.MissingOne' does not exist.
                    """.trimIndent(),
                    "Gradle rewriteDryRun"
                )

            assertNotNull(reason)
            assertEquals(
                "Gradle rewriteDryRun exited 0 but did not resolve recipe(s): " +
                    "com.example.MissingOne, com.example.MissingTwo",
                reason
            )
        }

        test("reports names from Recipe(s) not found without the Did you mean suggestion") {
            val reason =
                PluginOutputReader.unresolvedRecipeFailure(
                    """
                    Recipe(s) not found: com.example.Missing, com.example.AlsoMissing
                    Did you mean: com.example.Present, com.example.AlsoPresent
                    """.trimIndent(),
                    "Gradle rewriteDryRun"
                )

            assertNotNull(reason)
            assertTrue("com.example.Missing" in reason, reason)
            assertTrue("com.example.AlsoMissing" in reason, reason)
            assertTrue("com.example.Present" !in reason, reason)
        }

        // "No recipes were activated." is a real upstream marker but is deliberately excluded:
        // `gradlew rewriteDryRun` matches that task in every project that has it, so a repository
        // whose own build applies the rewrite plugin to subprojects without an activeRecipe would
        // log it and permanently lose Stage 0. See the constant's KDoc and issue #181.
        test("ignores the no-recipes-activated warning that other projects' tasks can emit") {
            assertNull(
                PluginOutputReader.unresolvedRecipeFailure(
                    """
                    > Task :sub:rewriteDryRun
                    No recipes were activated. Activate a recipe with rewrite.activeRecipe(...)
                    BUILD SUCCESSFUL
                    """.trimIndent(),
                    "Gradle rewriteDryRun"
                )
            )
        }

        test("reports validation errors even when no recipe name is quoted") {
            val reason =
                PluginOutputReader.unresolvedRecipeFailure(
                    "[ERROR] Recipe validation errors detected as part of one or more " +
                        "activeRecipe(s). Execution will continue regardless.",
                    "Gradle rewriteDryRun"
                )

            assertNotNull(reason)
        }

        test("returns null for verbose plugin output that resolved every recipe") {
            assertNull(
                PluginOutputReader.unresolvedRecipeFailure(
                    """
                    > Task :rewriteDryRun
                    Using active recipe(s) [com.example.integration.FindAndReplace]
                    Validating active recipes
                    Scanning sources in project test
                    Report available: build/reports/rewrite/rewrite.patch
                    Estimate time saved: 5m
                    BUILD SUCCESSFUL
                    """.trimIndent(),
                    "Gradle rewriteDryRun"
                )
            )
        }
    })
