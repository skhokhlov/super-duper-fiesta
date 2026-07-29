package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.text.PlainText
import org.openrewrite.text.PlainTextParser

class RecipeLoaderTest :
    FunSpec({
        var tempDir: Path = Path.of("")

        beforeEach { tempDir = Files.createTempDirectory("rlt-") }

        afterEach { tempDir.toFile().deleteRecursively() }

        val ctx: ExecutionContext = InMemoryExecutionContext {}

        // ─── AutoCloseable contract ───────────────────────────────────────────────

        test("close() is a no-op when no recipe JARs were loaded") {
            // Safety invariant: when no JARs are given the thread context classloader is used
            // and must NOT be closed.  close() must be harmless in this case.
            val loader = RecipeLoader(NoOpRunnerLogger)
            loader.load(
                recipeJars = emptyList(),
                activeRecipeName = "org.openrewrite.FindSourceFiles",
                rewriteYaml = null
            )
            loader.close() // must not throw
        }

        test("close() is idempotent") {
            val loader = RecipeLoader(NoOpRunnerLogger)
            loader.load(
                recipeJars = emptyList(),
                activeRecipeName = "org.openrewrite.FindSourceFiles",
                rewriteYaml = null
            )
            loader.close()
            loader.close() // second call must not throw
        }

        // ─── Built-in recipe loading (no JAR) ────────────────────────────────────

        test("load returns a usable recipe when no recipe JARs are provided") {
            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "org.openrewrite.java.format.AutoFormat",
                    rewriteYaml = null
                )
            assertNotNull(recipe, "load() should return a non-null recipe")
            assertEquals(
                "org.openrewrite.java.format.AutoFormat",
                recipe.name,
                "Recipe name should match the requested name"
            )
        }

        test("recipe returned by load is executable without ClassNotFoundException") {
            // Regression test: RecipeLoader previously closed the URLClassLoader immediately
            // after activateRecipes(), before the caller had a chance to run the recipe.
            // OpenRewrite recipes lazily load visitor inner-classes at execution time; closing
            // the class loader prematurely causes NoClassDefFoundError during recipe.run().
            //
            // This test uses a built-in recipe (no external JAR) and verifies the full
            // load → run cycle works.  The JAR-path is the critical path guarded by the fix
            // in RecipeLoader.load(): the URLClassLoader must NOT be closed before the caller
            // invokes the recipe.
            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "org.openrewrite.FindSourceFiles",
                    rewriteYaml = null
                )

            // Parse a simple plain-text file and run the recipe — this exercises visitor
            // class loading.  If the classloader had been closed too early this would throw.
            val sourceFiles: List<org.openrewrite.SourceFile> =
                PlainTextParser()
                    .parse(ctx, "hello world")
                    .map { sf ->
                        (sf as PlainText).withSourcePath(
                            Path.of("hello.txt")
                        ) as org.openrewrite.SourceFile
                    }
                    .toList()

            val result = runCatching {
                recipe.run(InMemoryLargeSourceSet(sourceFiles), ctx)
            }

            // The recipe may or may not change files; what matters is that execution
            // completes without a class-loading exception.
            assertEquals(
                true,
                result.isSuccess,
                "Recipe execution should complete without ClassNotFoundException: " +
                    "${result.exceptionOrNull()}"
            )
        }

        test("load with invalid recipe name throws IllegalArgumentException") {
            val result = runCatching {
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.recipe.ThatDefinitelyDoesNotExist",
                    rewriteYaml = null
                )
            }
            assertEquals(
                true,
                result.isFailure,
                "Loading a nonexistent recipe should throw an exception"
            )
        }

        test(
            "load converts RecipeException to IllegalArgumentException when recipe is not in provided JARs"
        ) {
            // Regression: when OpenRewrite's env.activateRecipes() throws RecipeException
            // (recipe name not found in the scanned JARs), RecipeLoader must convert it to
            // IllegalArgumentException so the CLI shows a clear, actionable error message
            // instead of "Unhandled exception: org.openrewrite.RecipeException".
            val result = runCatching {
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.nonexistent.FakeRecipeThatDoesNotExist",
                    rewriteYaml = null
                )
            }
            val ex = result.exceptionOrNull()
            assertTrue(
                ex is IllegalArgumentException,
                "Should throw IllegalArgumentException; got ${ex?.javaClass?.name}: ${ex?.message}"
            )
            val msg = ex.message ?: ""
            assertTrue(
                msg.contains("not found", ignoreCase = true),
                "Exception message should explain the recipe was not found: $msg"
            )
        }

        // ─── YAML content string overload ────────────────────────────────────────

        test("load with yaml content string activates composite recipe") {
            val yamlContent =
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.FindTxtFiles
                recipeList:
                  - org.openrewrite.FindSourceFiles:
                      filePattern: "**/*.txt"
                """.trimIndent()

            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.FindTxtFiles",
                    rewriteYamlContent = yamlContent
                )

            assertNotNull(recipe)
            assertEquals("com.example.test.FindTxtFiles", recipe.name)
        }

        test("load with null yaml content string falls back to classpath scan") {
            val recipe =
                RecipeLoader(NoOpRunnerLogger).load(
                    recipeJars = emptyList(),
                    activeRecipeName = "org.openrewrite.FindSourceFiles",
                    rewriteYamlContent = null
                )
            assertNotNull(recipe)
        }

        // ─── Corrupt / unusable recipe JARs (issue #270) ─────────────────────────

        val jarRecipeName = "com.example.test.PackagedInJar"

        fun recipeYaml(name: String) =
            """
            ---
            type: specs.openrewrite.org/v1beta/recipe
            name: $name
            displayName: Packaged in JAR
            description: Declarative recipe shipped inside a recipe JAR.
            recipeList:
              - org.openrewrite.FindSourceFiles:
                  filePattern: "**/*.txt"
            """.trimIndent()

        fun writeRecipeJar(target: Path): Path {
            JarOutputStream(Files.newOutputStream(target)).use { out ->
                out.putNextEntry(JarEntry("META-INF/rewrite/test-recipes.yml"))
                out.write(recipeYaml(jarRecipeName).toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            return target
        }

        test("load activates a declarative recipe packaged in a readable recipe JAR") {
            // Positive control for the readability probe: a well-formed JAR must still load.
            val jar = writeRecipeJar(tempDir.resolve("good-recipe.jar"))
            RecipeLoader(NoOpRunnerLogger).use { loader ->
                val recipe =
                    loader.load(
                        recipeJars = listOf(jar),
                        activeRecipeName = jarRecipeName,
                        rewriteYaml = null as Path?
                    )
                assertEquals(jarRecipeName, recipe.name)
            }
        }

        test("load fails and names the file when a recipe JAR is truncated") {
            // Regression for #270: a truncated JAR in the recipe cache used to yield zero
            // recipes with no warning anywhere — upstream ClasspathScanningLoader swallows
            // the IOException — so the run failed with a misleading "recipe not found".
            val jar = writeRecipeJar(tempDir.resolve("truncated-recipe.jar"))
            FileChannel.open(jar, StandardOpenOption.WRITE).use { it.truncate(64) }

            val ex =
                runCatching {
                    RecipeLoader(NoOpRunnerLogger).use { loader ->
                        loader.load(
                            recipeJars = listOf(jar),
                            activeRecipeName = jarRecipeName,
                            rewriteYaml = null as Path?
                        )
                    }
                }.exceptionOrNull()

            assertNotNull(ex, "A truncated recipe JAR must fail the run, not load zero recipes")
            val msg = ex.message ?: ""
            assertTrue(
                msg.contains("truncated-recipe.jar"),
                "Failure must name the offending JAR; got: $msg"
            )
            // Covers both downstream diagnoses, which name the requested recipe in this
            // exact form: the RecipeException conversion ("Recipe 'X' not found.") and the
            // unresolved-recipe check from #269 ("Recipe 'X' could not be fully resolved").
            assertTrue(
                !msg.contains("Recipe '$jarRecipeName'"),
                "Failure must blame the corrupt JAR, not the recipe name; got: $msg"
            )
        }

        test("a corrupt JAR is blamed as such even when a rewrite.yaml composes its recipes") {
            // Ordering guard for the #270 probe against the #269 unresolved-recipe check.
            // Both live in buildAndActivate — the probe at the top, the validation at the
            // bottom — and both are correct diagnoses of their own condition, so whichever
            // runs first decides what the user is told. A rewrite.yaml whose recipeList
            // references a recipe from a corrupt JAR would otherwise be reported as
            // "could not be fully resolved: <recipe> does not exist", blaming the recipe
            // names and never mentioning the unreadable file that actually caused it.
            val jar = writeRecipeJar(tempDir.resolve("composed-recipe.jar"))
            FileChannel.open(jar, StandardOpenOption.WRITE).use { it.truncate(64) }

            val composingYaml =
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.NeedsTheJar
                displayName: Needs the JAR
                description: Composes a recipe that only the recipe JAR supplies.
                recipeList:
                  - $jarRecipeName
                """.trimIndent()

            val ex =
                runCatching {
                    RecipeLoader(NoOpRunnerLogger).use { loader ->
                        loader.load(
                            recipeJars = listOf(jar),
                            activeRecipeName = "com.example.test.NeedsTheJar",
                            rewriteYamlContent = composingYaml
                        )
                    }
                }.exceptionOrNull()

            assertNotNull(ex, "A corrupt recipe JAR must fail the run")
            val msg = ex.message ?: ""
            assertTrue(
                msg.contains("composed-recipe.jar"),
                "Failure must name the unreadable JAR, not just the recipes it should have " +
                    "supplied; got: $msg"
            )
            assertTrue(
                !msg.contains("could not be fully resolved"),
                "The corrupt JAR must be reported as the root cause, not as unresolved " +
                    "recipe names; got: $msg"
            )
        }

        test("load fails and names the file when a recipe JAR path does not exist") {
            val missing = tempDir.resolve("never-downloaded.jar")

            val ex =
                runCatching {
                    RecipeLoader(NoOpRunnerLogger).use { loader ->
                        loader.load(
                            recipeJars = listOf(missing),
                            activeRecipeName = jarRecipeName,
                            rewriteYaml = null as Path?
                        )
                    }
                }.exceptionOrNull()

            assertNotNull(ex, "A missing recipe JAR must fail the run")
            assertTrue(
                (ex.message ?: "").contains("never-downloaded.jar"),
                "Failure must name the missing JAR; got: ${ex.message}"
            )
        }

        test("load accepts a directory classpath entry") {
            // ClasspathScanningLoader supports directories of class files / resources,
            // so the readability probe must not reject them as "not an archive".
            val dir = tempDir.resolve("exploded-recipe")
            val rewriteDir = dir.resolve("META-INF/rewrite")
            Files.createDirectories(rewriteDir)
            Files.writeString(
                rewriteDir.resolve("test-recipes.yml"),
                recipeYaml(jarRecipeName)
            )

            RecipeLoader(NoOpRunnerLogger).use { loader ->
                val recipe =
                    loader.load(
                        recipeJars = listOf(dir),
                        activeRecipeName = jarRecipeName,
                        rewriteYaml = null as Path?
                    )
                assertEquals(jarRecipeName, recipe.name)
            }
        }
    })
