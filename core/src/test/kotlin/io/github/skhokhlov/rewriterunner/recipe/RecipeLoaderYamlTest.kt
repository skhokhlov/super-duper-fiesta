package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the rewrite.yaml loading path in [RecipeLoader], which was previously uncovered.
 */
class RecipeLoaderYamlTest :
    FunSpec({
        var tempDir: Path = Path.of("")

        beforeEach { tempDir = Files.createTempDirectory("rlyt-") }

        afterEach { tempDir.toFile().deleteRecursively() }

        test("load reads composite recipe from rewrite_yaml when present") {
            val yamlFile = tempDir.resolve("rewrite.yaml")
            yamlFile.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.FindTxtFiles
                recipeList:
                  - org.openrewrite.FindSourceFiles:
                      filePattern: "**/*.txt"
                """.trimIndent()
            )

            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.FindTxtFiles",
                    rewriteYaml = yamlFile
                )

            assertNotNull(recipe)
            assertEquals("com.example.test.FindTxtFiles", recipe.name)
        }

        test("load skips rewrite_yaml when path is null") {
            // Built-in recipe loaded with null yaml path — should work fine
            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "org.openrewrite.FindSourceFiles",
                    rewriteYaml = null
                )
            assertNotNull(recipe)
        }

        test("load skips rewrite_yaml when file does not exist") {
            val nonExistentYaml = tempDir.resolve("nonexistent-rewrite.yaml")

            // File does not exist; RecipeLoader should silently skip it and
            // fall back to scanning the classpath for the built-in recipe.
            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "org.openrewrite.FindSourceFiles",
                    rewriteYaml = nonExistentYaml
                )
            assertNotNull(recipe)
        }

        test("load composite recipe from yaml is executable") {
            val yamlFile = tempDir.resolve("rewrite.yaml")
            yamlFile.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.DeleteProperties
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )

            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.DeleteProperties",
                    rewriteYaml = yamlFile
                )

            // Recipe should be non-null and have at least one child in its list
            assertNotNull(recipe)
            assertEquals("com.example.test.DeleteProperties", recipe.name)
        }

        // ─── Unresolved sub-recipes (issue #269) ─────────────────────────────────

        test("load fails when a recipeList entry names a recipe that does not exist") {
            // A declarative recipe whose recipeList references a missing recipe is only
            // partially initialized by OpenRewrite: the resolvable entries run and the
            // missing one is silently dropped.  Executing that subset is a wrong answer
            // presented as a complete migration, so the load must fail instead.
            val yamlFile = tempDir.resolve("rewrite.yaml")
            yamlFile.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.PartiallyResolvable
                recipeList:
                  - org.openrewrite.FindSourceFiles:
                      filePattern: "**/*.txt"
                  - com.example.test.TotallyMissingRecipe
                """.trimIndent()
            )

            val result = runCatching {
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.PartiallyResolvable",
                    rewriteYaml = yamlFile
                )
            }

            val ex = result.exceptionOrNull()
            assertTrue(
                ex is IllegalArgumentException,
                "Expected IllegalArgumentException; got ${ex?.javaClass?.name}: ${ex?.message}"
            )
            val msg = ex.message ?: ""
            assertTrue(
                msg.contains("com.example.test.TotallyMissingRecipe"),
                "Message should name the unresolved recipe: $msg"
            )
            assertTrue(
                msg.contains("com.example.test.PartiallyResolvable"),
                "Message should name the requested recipe: $msg"
            )
        }

        test("load fails when a nested declarative recipe has an unresolved entry") {
            // The unresolved entry is one level down, inside a sub-recipe of the
            // requested recipe.  ADR 0011 makes that a failure too.
            val yamlFile = tempDir.resolve("rewrite.yaml")
            yamlFile.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.OuterComposite
                recipeList:
                  - com.example.test.InnerComposite
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.InnerComposite
                recipeList:
                  - org.openrewrite.FindSourceFiles:
                      filePattern: "**/*.txt"
                  - com.example.test.MissingLeafRecipe
                """.trimIndent()
            )

            val result = runCatching {
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.OuterComposite",
                    rewriteYaml = yamlFile
                )
            }

            val ex = result.exceptionOrNull()
            assertTrue(
                ex is IllegalArgumentException,
                "Expected IllegalArgumentException; got ${ex?.javaClass?.name}: ${ex?.message}"
            )
            assertTrue(
                (ex.message ?: "").contains("com.example.test.MissingLeafRecipe"),
                "Message should name the unresolved nested recipe: ${ex.message}"
            )
        }

        test("load succeeds when every recipeList entry resolves") {
            // Guard against the validation check rejecting a healthy declarative recipe.
            val yamlFile = tempDir.resolve("rewrite.yaml")
            yamlFile.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.FullyResolvable
                recipeList:
                  - org.openrewrite.FindSourceFiles:
                      filePattern: "**/*.txt"
                  - com.example.test.InnerResolvable
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.InnerResolvable
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )

            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.FullyResolvable",
                    rewriteYaml = yamlFile
                )

            assertEquals("com.example.test.FullyResolvable", recipe.name)
            assertEquals(2, recipe.recipeList.size)
        }
    })
