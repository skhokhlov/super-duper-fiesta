package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
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

    /**
     * Reject unreadable recipe classpath entries *before* they reach OpenRewrite's scanner.
     *
     * OpenRewrite's `ClasspathScanningLoader` opens every regular classpath entry with
     * `new JarFile(...)` and swallows the resulting `IOException`, so a truncated or
     * otherwise corrupt JAR silently contributes zero recipes. Combined with
     * `CHECKSUM_POLICY_IGNORE` on the resolver session (a deliberate choice for corporate
     * proxies that omit checksum files) and `UPDATE_POLICY_DAILY`, a half-written JAR in
     * the local cache survives indefinitely and can only be inferred from a downstream
     * symptom: a misleading "Recipe '…' not found", or — when a `rewrite.yaml` composes
     * recipes the corrupt JAR was supposed to supply — the unresolved-recipe failure
     * raised by [unresolvedRecipeNames], which blames the recipe names rather than the file.
     *
     * This probe mirrors what the scanner will do — directories are scanned as exploded
     * class/resource trees and are therefore accepted as-is; every regular file must open
     * as a ZIP/JAR archive and yield readable recipe definitions — and fails loudly,
     * naming the offending path.
     *
     * It must stay the **first** statement of [buildAndActivate]: both downstream failures
     * above are real diagnoses of their own conditions, so whichever check runs first
     * decides which cause the user is told about, and the corrupt file is the root cause.
     *
     * ## Known gap: corrupt `.class` entries are not detected
     *
     * Only the `.yml` and `.yaml` entries under `META-INF/rewrite/` are drained — exactly what
     * upstream reads eagerly in `addYamlResourcesFromJar` to discover **declarative** recipes. Damage
     * confined to a `.class` entry is not caught here, and upstream swallows it per entry
     * as well (`catch (IOException | IllegalArgumentException ignored)` around
     * `jarFile.getInputStream(entry)` in `buildSuperclassMapFromPath`), so an **imperative**
     * recipe can still be dropped silently.
     *
     * That is a deliberate cost trade rather than an oversight. Decompressing every entry of
     * every recipe JAR — a realistic run resolves ~95 of them, thousands of class files each,
     * plus multi-megabyte resources such as `rewrite-spring`'s `classpath.tsv.gz` — would add
     * seconds to every invocation to catch a failure mode that truncation cannot even produce:
     * truncation destroys the central directory, which is written last, and that is already
     * caught by the [JarFile] constructor.
     *
     * Draining is in any case not a complete check: [java.util.zip.ZipFile] does not verify
     * entry CRCs, so corruption that still inflates without error is invisible no matter how
     * much of the archive is read.
     */
    private fun verifyReadableArchives(recipeJars: List<Path>) {
        for (jar in recipeJars) {
            val problem = archiveProblem(jar) ?: continue
            val message =
                "Recipe classpath entry is unusable: $jar ($problem). " +
                    "The cached artifact is missing or corrupt — delete it and re-run to " +
                    "fetch a fresh copy."
            logger.error(message)
            throw IllegalArgumentException(message)
        }
    }

    /**
     * @return a human-readable description of why [entry] cannot be scanned, or `null`
     *   when it is usable.
     */
    private fun archiveProblem(entry: Path): String? {
        // Existence and readability are checked before the directory short-circuit: an
        // exploded recipe directory that exists but cannot be read is just as unusable as
        // an unreadable JAR, and upstream's Files.walk over it would silently yield nothing.
        if (!entry.exists()) return "file does not exist"
        if (!entry.isReadable()) return "file is not readable"
        // ClasspathScanningLoader walks directories of class files / META-INF resources,
        // so an exploded classpath entry is legitimate and needs no archive probe.
        if (entry.isDirectory()) return null
        return try {
            JarFile(entry.toFile()).use { jar ->
                // The JarFile constructor reads the ZIP central directory, which is precisely
                // the structure a truncated download destroys.
                //
                // Draining the recipe definitions then covers damage confined to the
                // entry-data region, which leaves that central directory intact — a local
                // header or deflate stream broken by a partial overwrite rather than a
                // truncation. See the "Known gap" note on verifyReadableArchives for what
                // this deliberately does not cover.
                jar.entries()
                    .asSequence()
                    .filter { candidate -> isRecipeDefinition(candidate) }
                    .forEach { definition ->
                        jar.getInputStream(definition).use { stream -> stream.readBytes() }
                    }
            }
            null
        } catch (e: IOException) {
            "not a readable archive: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Mirrors the entry filter in upstream `ClasspathScanningLoader.addYamlResourcesFromJar`,
     * which reads exactly these entries eagerly to discover declarative recipes.
     */
    private fun isRecipeDefinition(entry: JarEntry): Boolean = !entry.isDirectory &&
        entry.name.startsWith(RECIPE_RESOURCE_PREFIX) &&
        (entry.name.endsWith(".yml") || entry.name.endsWith(".yaml"))

    private fun buildAndActivate(
        recipeJars: List<Path>,
        activeRecipeName: String,
        yamlSource: YamlSource?
    ): Recipe {
        verifyReadableArchives(recipeJars)

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

        // Scan each recipe JAR for OpenRewrite recipes/styles/categories.
        //
        // No try/catch here on purpose: the 4-arg ClasspathScanningLoader constructor only
        // stores lambdas and performs no I/O, so a guard around it would catch nothing —
        // the actual scan runs lazily inside Environment.activateRecipes(), well outside
        // any block we could wrap here. Unreadable archives are rejected up-front by
        // verifyReadableArchives() instead; see [verifyReadableArchives].
        for (jar in recipeJars) {
            logger.debug("Scanning recipe JAR: $jar")
            builder.load(ClasspathScanningLoader(jar, props, emptyList(), classLoader))
        }

        // Scan the tool's own classpath for built-in recipes only when no recipe JARs are provided.
        // When recipe JARs are present their transitive deps already include all OpenRewrite core
        // jars, so a blanket classpath scan would register the same recipes twice and cause
        // duplicate-key errors in Environment.activateRecipes().
        // (Same reasoning as above: the 2-arg constructor performs no I/O either, so a
        // try/catch around it could never fire.)
        if (recipeJars.isEmpty()) {
            builder.load(ClasspathScanningLoader(props, classLoader))
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
     * `DeclarativeRecipe.initialize` does not fail on a recipe-list entry it cannot find; it drops
     * the entry and records a [org.openrewrite.Validated.Invalid] into the validation state that
     * [org.openrewrite.Recipe.validate] returns:
     *
     * ```java
     * invalid(name + ".recipeList[" + i + "] (in " + source + ")", recipeFqn, "recipe '…' …", null)
     * ```
     *
     * The unresolved name is therefore available structurally as
     * [org.openrewrite.Validated.Invalid.getInvalidValue], with the property naming the entry it
     * came from — no message parsing, so the check does not drift when upstream rewords the text.
     * Note it is `invalidValue`, not `getValue()`, which throws on an `Invalid`.
     *
     * Two properties of upstream this relies on:
     * - Entries nested inside sub-recipes accumulate onto the **root** recipe's validation, because
     *   `initializeDeclarativeRecipe` re-initializes a sub-recipe through the outer instance. One
     *   [org.openrewrite.Recipe.validate] call therefore covers the whole list at any depth.
     * - The same private helper initializes `preconditions` and hardcodes `.recipeList` in the
     *   property for both, so an unresolved precondition is reported here too — as it should be.
     *
     * Deliberately narrow: only unresolved recipes fail the load (proposed ADR 0011). The
     * companion `initialization` failure that accompanies a dropped entry, and unrelated option
     * validations, keep their existing non-fatal behaviour. Filtering on a [String] invalid value
     * also excludes `YamlResourceLoader`'s malformed-entry failure, which shares the property shape
     * but carries the offending YAML node rather than a recipe name.
     */
    private fun unresolvedRecipeNames(recipe: Recipe): List<String> = recipe.validate()
        .failures()
        .filter { failure -> RECIPE_LIST_ENTRY_PROPERTY in failure.property }
        .mapNotNull { failure -> failure.invalidValue as? String }
        .distinct()

    private companion object {
        /**
         * Property fragment upstream builds for a recipe-list (or precondition) entry:
         * `<recipe name>.recipeList[<index>] (in <source>)`.
         */
        const val RECIPE_LIST_ENTRY_PROPERTY = ".recipeList["

        /** JAR path prefix under which upstream looks for declarative recipe definitions. */
        const val RECIPE_RESOURCE_PREFIX = "META-INF/rewrite/"
    }
}
