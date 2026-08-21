package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.text.PlainText
import org.openrewrite.text.PlainTextParser

/**
 * Whether "make this unreadable" is actually enforceable here. False on Windows (whose ACLs
 * ignore the POSIX-style bits `File.setReadable` sets) and when running as root, which bypasses
 * permission checks entirely — in both cases a test that asserts an unreadable path is rejected
 * would pass or fail for the wrong reason, so it is disabled rather than weakened.
 */
private val unreadablePathsAreEnforceable: Boolean by lazy {
    val probe = Files.createTempDirectory("rlt-perm-probe-")
    try {
        probe.toFile().setReadable(false, false)
        !Files.isReadable(probe)
    } catch (_: Exception) {
        false
    } finally {
        probe.toFile().setReadable(true, false)
        probe.toFile().deleteRecursively()
    }
}

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

        /**
         * Overwrite the compressed-data region of the single entry in [jar] with zeroes,
         * leaving its local header, the central directory and the end-of-central-directory
         * record byte-for-byte intact.
         *
         * This is the corruption a partial overwrite produces, and it is the complement of
         * truncation: the ZIP tail — written last, and what `JarFile`'s constructor reads —
         * still describes a perfectly well-formed archive, so the damage is only observable
         * by actually reading entry data.
         *
         * Verified empirically to raise `ZipException: invalid stored block lengths` on drain.
         * Not every mutation does: flipping the *first* byte of the deflate stream in this same
         * fixture still inflates cleanly, because `ZipFile` does not verify entry CRCs. Hence
         * the deterministic whole-region zeroing rather than a byte flip.
         */
        fun corruptEntryData(jar: Path) {
            val bytes = Files.readAllBytes(jar)
            fun u16(at: Int) =
                (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            // Local file header: 30 fixed bytes, then the name and extra fields.
            val dataStart = 30 + u16(26) + u16(28)
            val centralDirectory =
                (bytes.size - 4 downTo 0).firstOrNull { i ->
                    bytes[i] == 0x50.toByte() &&
                        bytes[i + 1] == 0x4B.toByte() &&
                        bytes[i + 2] == 0x01.toByte() &&
                        bytes[i + 3] == 0x02.toByte()
                } ?: error("no central directory file header found in $jar")
            for (i in dataStart until centralDirectory) bytes[i] = 0
            Files.write(jar, bytes)
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

        test("load fails when entry data is damaged but the central directory is intact") {
            // The JarFile constructor alone cannot see this: it reads the central directory,
            // which a partial overwrite leaves untouched. Only reading the recipe definitions
            // exposes it — and upstream's read is inside `catch (IOException ignored)`, so
            // without this check the JAR would again contribute zero recipes silently.
            val jar = writeRecipeJar(tempDir.resolve("damaged-entry.jar"))
            corruptEntryData(jar)

            // Guard that this fixture exercises the drain rather than the constructor: if the
            // archive were structurally broken the constructor would throw and the test would
            // pass without proving anything about entry data.
            JarFile(jar.toFile()).use { opened ->
                assertTrue(
                    opened.entries().asSequence().any { it.name.endsWith("test-recipes.yml") },
                    "Fixture must keep the central directory intact so the entry is still listed"
                )
            }

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

            assertNotNull(ex, "A JAR whose recipe definitions cannot be read must fail the run")
            assertTrue(
                (ex.message ?: "").contains("damaged-entry.jar"),
                "Failure must name the damaged JAR; got: ${ex.message}"
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

        test("load fails and names the directory when an exploded classpath entry is unreadable")
            .config(enabled = unreadablePathsAreEnforceable) {
                // A directory that exists but cannot be read is as unusable as a corrupt JAR:
                // upstream's Files.walk over it fails into `catch (IOException ignored)` and
                // contributes nothing. The probe must therefore check readability *before* it
                // short-circuits on isDirectory().
                val dir = tempDir.resolve("unreadable-exploded")
                val rewriteDir = dir.resolve("META-INF/rewrite")
                Files.createDirectories(rewriteDir)
                Files.writeString(rewriteDir.resolve("test-recipes.yml"), recipeYaml(jarRecipeName))
                dir.toFile().setReadable(false, false)

                try {
                    val ex =
                        runCatching {
                            RecipeLoader(NoOpRunnerLogger).use { loader ->
                                loader.load(
                                    recipeJars = listOf(dir),
                                    activeRecipeName = jarRecipeName,
                                    rewriteYaml = null as Path?
                                )
                            }
                        }.exceptionOrNull()

                    assertNotNull(ex, "An unreadable classpath directory must fail the run")
                    assertTrue(
                        (ex.message ?: "").contains("unreadable-exploded"),
                        "Failure must name the unreadable directory; got: ${ex.message}"
                    )
                } finally {
                    // Restore readability or afterEach cannot clean the temp directory up.
                    dir.toFile().setReadable(true, false)
                }
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
