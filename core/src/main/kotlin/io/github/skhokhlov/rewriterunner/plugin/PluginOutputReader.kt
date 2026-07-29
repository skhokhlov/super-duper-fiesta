package io.github.skhokhlov.rewriterunner.plugin

import java.time.Duration

internal object PluginOutputReader {
    private val estimateLine = Regex("""Estimate time saved:\s*([0-9hmsHMS ]+)""")
    private val durationPart = Regex("""(\d+)\s*([hmsHMS])""")

    /**
     * `recipe '<fqn>' does not exist.` — emitted by `DeclarativeRecipe.initialize` (rewrite-core)
     * for every entry of a declarative recipe's `recipeList` that is not on the classpath. The
     * plugins log it at ERROR and, because `failOnInvalidActiveRecipes` defaults to `false`,
     * carry on and exit 0 having run only the entries they could resolve.
     */
    private val missingSubRecipe = Regex("""recipe '([^']+)' does not exist\.""")

    /**
     * `Recipe(s) not found: %s` — thrown by `Environment.activateRecipes` (rewrite-core) with a
     * comma-separated list of names. The following `Did you mean: %s` line carries *suggestions*,
     * which must never be reported as unresolved; anchoring on this literal keeps them out.
     */
    private val recipesNotFound = Regex("""Recipe\(s\) not found:[ \t]*(.+)""")

    /**
     * A resolution gap that names no recipe. This literal appears verbatim in
     * `AbstractRewriteBaseRunMojo` (rewrite-maven-plugin) and `DefaultProjectParser`
     * (rewrite-gradle-plugin), and is only ever logged when a validation actually failed, so it
     * cannot be produced by a healthy run.
     *
     * `No recipes were activated.` was **deliberately not** included. It is a genuine upstream
     * marker, but it adds no detection here — rewrite-runner always passes an active recipe, and
     * an unknown recipe *name* hard-fails with a non-zero exit that already falls through — while
     * it does introduce a false positive: `gradlew rewriteDryRun` matches that task in every
     * project that has it, so a repository whose own build applies the rewrite plugin to
     * subprojects without configuring `activeRecipe` would log it on each such subproject and
     * permanently lose Stage 0. A Stage 0 run that activates nothing and reports no changes is
     * issue #181's subject, not this one.
     */
    private const val VALIDATION_ERRORS_MARKER =
        "Recipe validation errors detected as part of one or more activeRecipe(s)."

    /**
     * Decides whether captured plugin output shows that Stage 0 failed to resolve every recipe it
     * was asked to run — including a sub-recipe named inside a requested declarative recipe's
     * `recipeList`, which upstream reports at ERROR and then survives with exit code 0.
     *
     * The caller must treat a non-null result as a stage failure even when the plugin also
     * produced patches: a partially initialized recipe emits real diffs for the entries it could
     * resolve, so "diffs exist" is not evidence that the whole recipe ran.
     *
     * Matching is anchored on exact upstream strings, which couples it to the pinned plugin
     * versions in `gradle/libs.versions.toml`. That is deliberate — loose matching would trip on
     * ordinary verbose build output (Gradle runs with `-i`).
     *
     * @param output Combined stdout/stderr captured from the plugin invocation.
     * @param action Human-readable label for the invocation (e.g. `Maven rewrite:dryRun`), used to
     *   attribute the failure when both build tools are tried in turn.
     * @return A failure reason naming the unresolved recipes, or `null` when the output carries no
     *   unresolved-recipe marker.
     */
    fun unresolvedRecipeFailure(output: String, action: String): String? {
        val names = unresolvedRecipeNames(output)
        if (names.isEmpty() && VALIDATION_ERRORS_MARKER !in output) return null
        return if (names.isEmpty()) {
            "$action exited 0 but reported unresolved recipes"
        } else {
            "$action exited 0 but did not resolve recipe(s): ${names.joinToString(", ")}"
        }
    }

    private fun unresolvedRecipeNames(output: String): List<String> {
        val fromValidation = missingSubRecipe.findAll(output).map { it.groupValues[1] }
        val fromNotFound =
            recipesNotFound.findAll(output).flatMap { match ->
                match.groupValues[1].split(",").asSequence().map(String::trim)
            }
        return (fromValidation + fromNotFound).filter { it.isNotEmpty() }.distinct().toList()
    }

    fun estimatedTimeSaved(output: String): Duration? {
        val estimate = estimateLine.findAll(output).lastOrNull()?.groupValues?.get(1)
            ?: return null
        return parseEstimate(estimate)
    }

    private fun parseEstimate(estimate: String): Duration? {
        val parts = durationPart.findAll(estimate).toList()
        if (parts.isEmpty()) return null

        val leftover = durationPart.replace(estimate, "").trim()
        if (leftover.isNotEmpty()) return null

        return parts.fold(Duration.ZERO) { total, match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            when (match.groupValues[2].lowercase()) {
                "h" -> total.plusHours(amount)
                "m" -> total.plusMinutes(amount)
                "s" -> total.plusSeconds(amount)
                else -> return null
            }
        }
    }
}
