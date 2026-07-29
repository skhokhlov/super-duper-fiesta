package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import org.openrewrite.Recipe
import org.openrewrite.RecipeException
import org.openrewrite.config.ClasspathScanningLoader
import org.openrewrite.config.Environment
import org.openrewrite.config.YamlResourceLoader

/**
 * Loads OpenRewrite recipes from recipe JARs and/or a `rewrite.yaml` declarative config.
 *
 * Internally builds an OpenRewrite [org.openrewrite.config.Environment] that scans the
 * provided JARs (and, when no JARs are given, the tool's own classpath) for recipes,
 * styles, and categories. The [load] method returns the activated [org.openrewrite.Recipe]
 * ready for execution by [RecipeRunner].
 *
 * ## URLClassLoader lifecycle
 *
 * `RecipeLoader` implements [AutoCloseable]. When recipe JARs are supplied, [load] creates
 * a [URLClassLoader] over those JARs but intentionally **does not close it** before
 * returning — OpenRewrite visitor inner-classes are loaded lazily at
 * [org.openrewrite.Recipe.run] time, and closing the loader beforehand causes
 * `NoClassDefFoundError`.
 *
 * Callers must close this `RecipeLoader` **after** recipe execution completes. Use
 * try-with-resources (Java) or `use {}` (Kotlin):
 *
 * ```kotlin
 * RecipeLoader(logger).use { loader ->
 *     val recipe = loader.load(recipeJars, recipeName, rewriteYaml)
 *     recipe.run(sourceSet, ctx)   // visitor classes loaded here
 * }  // URLClassLoader closed here
 * ```
 *
 * When no recipe JARs are provided the thread context classloader is used and [close] is
 * a no-op (the shared parent classloader must not be closed).
 */
class RecipeLoader(val logger: RunnerLogger) : AutoCloseable {

    private var recipeClassLoader: URLClassLoader? = null

    /**
     * Close the [URLClassLoader] created over recipe JARs, freeing file descriptors and
     * allowing the JAR files to be garbage-collected.
     *
     * Safe to call after the recipe returned by [load] has finished executing. Idempotent:
     * subsequent calls are a no-op. When [load] was called with an empty `recipeJars` list
     * this method is always a no-op (no classloader was created).
     */
    override fun close() {
        recipeClassLoader?.close()
        recipeClassLoader = null
    }

    /**
     * Build an OpenRewrite [Environment] from the given recipe JARs and optional rewrite.yaml.
     * Returns the activated [Recipe] ready for execution.
     *
     * @param rewriteYaml Path to the rewrite.yaml file, or `null` to rely solely on
     *   classpath-scanned recipes.
     */
    fun load(recipeJars: List<Path>, activeRecipeName: String, rewriteYaml: Path?): Recipe {
        val yamlSource: YamlSource? =
            if (rewriteYaml != null && rewriteYaml.exists()) {
                logger.info("Loading rewrite.yaml: $rewriteYaml")
                YamlSource(
                    stream = { FileInputStream(rewriteYaml.toFile()) },
                    uri = rewriteYaml.toUri()
                )
            } else {
                null
            }
        return buildAndActivate(recipeJars, activeRecipeName, yamlSource)
    }

    /**
     * Overload that accepts raw YAML content as a [String] instead of a file path.
     * Use this when the `rewrite.yaml` content is already in memory and writing it to
     * disk would be unnecessary I/O.
     *
     * @param rewriteYamlContent Raw YAML string, or `null` to rely solely on
     *   classpath-scanned recipes.
     */
    fun load(
        recipeJars: List<Path>,
        activeRecipeName: String,
        rewriteYamlContent: String?
    ): Recipe {
        val yamlSource: YamlSource? =
            if (rewriteYamlContent != null) {
                logger.info("Loading rewrite.yaml from string content")
                YamlSource(
                    stream = {
                        ByteArrayInputStream(rewriteYamlContent.toByteArray(Charsets.UTF_8))
                    },
                    uri = URI("string:rewrite.yaml")
                )
            } else {
                null
            }
        return buildAndActivate(recipeJars, activeRecipeName, yamlSource)
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private data class YamlSource(val stream: () -> InputStream, val uri: URI)

    private fun buildAndActivate(
        recipeJars: List<Path>,
        activeRecipeName: String,
        yamlSource: YamlSource?
    ): Recipe {
        val props = Properties()
        val parentLoader = Thread.currentThread().contextClassLoader

        // When JARs are provided, create a URLClassLoader and store it so close() can
        // release it after recipe execution.  When no JARs are provided, fall back to the
        // thread context classloader — which must NOT be closed, so recipeClassLoader stays null.
        val classLoader =
            if (recipeJars.isNotEmpty()) {
                URLClassLoader(
                    recipeJars.map { it.toUri().toURL() }.toTypedArray(),
                    parentLoader
                ).also { recipeClassLoader = it }
            } else {
                parentLoader
            }

        val builder = Environment.builder()

        // Scan each recipe JAR for OpenRewrite recipes/styles/categories
        for (jar in recipeJars) {
            logger.debug("Scanning recipe JAR: $jar")
            try {
                builder.load(ClasspathScanningLoader(jar, props, emptyList(), classLoader))
            } catch (e: Exception) {
                logger.warn("Failed to scan recipe JAR $jar (skipping): ${e.message}")
            }
        }

        // Scan the tool's own classpath for built-in recipes only when no recipe JARs are provided.
        // When recipe JARs are present their transitive deps already include all OpenRewrite core
        // jars, so a blanket classpath scan would register the same recipes twice and cause
        // duplicate-key errors in Environment.activateRecipes().
        if (recipeJars.isEmpty()) {
            try {
                builder.load(ClasspathScanningLoader(props, classLoader))
            } catch (e: Exception) {
                logger.warn("Failed to scan tool classpath (skipping): ${e.message}")
            }
        }

        // Load rewrite.yaml if provided
        if (yamlSource != null) {
            yamlSource.stream().use { stream ->
                builder.load(YamlResourceLoader(stream, yamlSource.uri, props, classLoader))
            }
        }

        val env = builder.build()
        val recipe =
            try {
                env.activateRecipes(activeRecipeName)
            } catch (e: RecipeException) {
                throw IllegalArgumentException(
                    "Recipe '$activeRecipeName' not found. " +
                        "Verify the recipe name and that the correct recipe artifact is supplied via --recipe-artifact.",
                    e
                )
            }
        val unresolved = unresolvedRecipeNames(recipe)
        require(unresolved.isEmpty()) {
            "Recipe '$activeRecipeName' could not be fully resolved: " +
                "${unresolved.joinToString(", ")} " +
                (if (unresolved.size == 1) "does" else "do") +
                " not exist. Running only the steps that did resolve would silently produce an " +
                "incomplete migration. Verify the recipe names and that every recipe JAR they " +
                "need is supplied via --recipe-artifact."
        }
        return recipe
    }

    /**
     * Names of recipes that could not be resolved while initializing [recipe].
     *
     * `DeclarativeRecipe.initialize` does not fail on a `recipeList` entry it cannot find; it
     * drops the entry and records `recipe '<fqn>' does not exist.` into the validation state that
     * [org.openrewrite.Recipe.validate] returns. Entries nested inside sub-recipes accumulate onto
     * the root recipe's validation too, so a single [org.openrewrite.Recipe.validate] call covers
     * the whole recipe list at any depth.
     *
     * Returns an empty list for a healthy recipe, including non-declarative ones (whose default
     * validation reports option problems that are not this method's concern).
     */
    private fun unresolvedRecipeNames(recipe: Recipe): List<String> = recipe.validate()
        .failures()
        .mapNotNull { failure ->
            RECIPE_DOES_NOT_EXIST.find(failure.message.orEmpty())?.groupValues?.get(1)
        }
        .distinct()

    private companion object {
        /** Matches the marker `DeclarativeRecipe.initialize` records for a missing sub-recipe. */
        val RECIPE_DOES_NOT_EXIST = Regex("recipe '([^']+)' does not exist")
    }
}
