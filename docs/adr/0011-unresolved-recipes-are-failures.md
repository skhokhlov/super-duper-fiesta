# ADR 0011: Unresolved Recipes Are Failures, Not No-Ops

## Status

Proposed

## Context

A recipe can be missing for two unrelated reasons that are reported identically as "recipe not
found": its artifact was never fetched, or the artifact was fetched but is unreadable. Worse, the
two execution stages resolve recipe artifacts from **independent sources** — Stage 0 delegates to
the target project's own build tool and its repositories, while the LST worker resolves through
rewrite-runner's own Aether context and cache. A recipe that one stage cannot find may be perfectly
resolvable by the other.

Before this decision, every path between "we could not obtain the recipe" and what the user is told
was a silent downgrade:

- A **missing sub-recipe degrades a declarative recipe silently**. Verified against the pinned
  plugin: a recipe list naming an absent recipe logs `recipe '<fqn>' does not exist.` followed by
  `Recipe validation errors detected … Execution will continue regardless.`, then exits `0` having
  executed the remaining entries. `failOnInvalidActiveRecipes` governs this and defaults to `false`.
  A 35-step migration therefore runs 34 steps and reports success.
- `DirectPluginExecutor` classifies Stage 0 purely on exit code and patch-file presence, so that
  degraded run is a **Stage 0 win** carrying real patches. The stage that could have run the whole
  recipe is never reached.
- On a Stage 0 win, a recipe-load failure in the follow-up specialized pass was caught and
  downgraded to a warning.
- `RecipeLoader` accepted a partially initialized declarative recipe: `DeclarativeRecipe.initialize`
  records `recipe '<fqn>' does not exist.` into its validation, but the loader only asserted that the
  recipe list was non-empty. A 35-step migration would happily run 34 steps.
- Recipe artifact resolution returned local JAR paths without checking they were readable. Combined
  with `CHECKSUM_POLICY_IGNORE` and `UPDATE_POLICY_DAILY`, a truncated download is accepted, cached,
  never re-fetched, and contributes zero recipes — because the upstream classpath scanner swallows
  the resulting `IOException`. This was reproduced end to end: the same command reported
  `Resolved 95 JAR(s)` including the recipe artifact, then failed with `Recipe(s) not found` and a
  nearest-match suggestion drawn from the other, healthy artifacts.

Two neighbouring cases were checked against the pinned plugin and are **not** affected, which
narrows this ADR's scope: an unresolvable recipe artifact and an unknown recipe name both produce a
hard `BUILD FAILURE` and already fall through to the LST stage correctly. Silent degradation is
specific to sub-recipes.

The combined effect is a run that reports success, changes nothing or only part of what was asked,
and gives no indication which happened.

## Decision

**Any unresolved recipe fails the stage that could not resolve it.** This covers both the recipe the
user requested and any recipe named inside a requested declarative recipe's `recipeList` — a
migration that silently executes the subset it happened to find is a wrong answer, not a partial
success.

Because the stages have independent classpaths, an unresolved recipe is grounds for **falling
through to a later stage**, not for aborting the run. Stage 0 reporting unresolved recipes yields
`PluginRunResult.Failed` and routes to the full LST fallback, which may hold the artifact Stage 0
could not reach. Only when no stage can resolve every required recipe does the run fail.

Consequences of enforcing this:

- Stage 0 must verify its own success rather than trusting the plugin's exit code, which means
  inspecting captured plugin output for upstream's unresolved-recipe markers. This is deliberate: we
  delegate execution to the official plugins but do **not** delegate the judgement of whether they
  did the job.
- The check runs even when Stage 0 produced diffs, because a missing sub-recipe coexists with real
  patches. Good Stage 0 diffs are discarded in that case in favour of a fallback that can run the
  whole recipe.
- Output-marker matching is anchored on exact upstream strings and is therefore version-coupled to
  the pinned plugin versions; a plugin bump can silently weaken detection, so the marker set is
  covered by tests in the real-plugin lane.

## Considered Alternatives

**Trust the plugin's exit code.** Simplest, and it keeps Stage 0 free of output parsing. Rejected
because the exit code does not distinguish a complete run from a partially initialized one: a
declarative recipe missing a sub-recipe exits `0` and emits patches, which is exactly the case this
ADR targets.

**Force `-Drewrite.failOnInvalidActiveRecipes=true`.** Would make the Maven plugin fail hard without
any output parsing. Rejected as insufficient and asymmetric: it governs validation errors rather than
the not-found path, has no Gradle equivalent, and lets a project's own plugin configuration override
it.

**Tolerate sub-recipe gaps when diffs exist.** Would preserve useful Stage 0 output and avoid
re-running work on the slower path. Rejected because it institutionalizes exactly the failure this
ADR exists to remove: an incomplete migration that looks complete.

**Have rewrite-runner resolve recipe artifacts for Stage 0 too.** Would give one resolution path and
one cache across both stages. Rejected for now — it overrides the project's own mirrors and
credentials, which are often the only route to internal recipe artifacts, and it discards the
independent-classpath property that makes falling through worthwhile. See
[ADR 0006](0006-plugin-first-execution.md).

## Relationship to ADR 0012

When no stage can resolve every required recipe, the run fails. *How* that failure reaches the
caller — a returned result carrying both stages' diagnostics rather than a thrown error — is
governed by [ADR 0012](0012-uniform-failure-reporting.md), which covers every terminal execution
failure uniformly rather than special-casing recipe resolution.

## Relationship to ADR 0006

[ADR 0006](0006-plugin-first-execution.md) established that `Failed` and `Skipped` fall through
silently to the LST pipeline. This ADR narrows what may be classified as a Stage 0 success:
`NoChanges` now requires evidence that every required recipe actually resolved.
