# ADR 0006: Plugin-First (Stage 0) Execution

## Status

Accepted

## Context

rewrite-runner contains a full in-process recipe engine: a four-stage LST classpath
pipeline (`LstBuilder` + the `ClasspathStage` chain) that resolves a classpath, parses every
source file into an OpenRewrite LST, runs the recipe, and emits results. That engine exists
because not every target project is buildable in our environment, and we want recipes to run
even when `mvn`/`gradle` cannot.

But for the common case — a project whose own Gradle or Maven build already works — the
official OpenRewrite plugins (`org.openrewrite:plugin` for Gradle, `rewrite-maven-plugin` for
Maven) can apply the same recipe directly, using the project's own build system for
high-fidelity classpath resolution. Reproducing that fidelity in-process is exactly what the
four LST stages struggle with (BOM-managed versions, `dependencyManagement`, resolved
transitive scopes, Gradle version catalogs). Running the official plugin sidesteps all of it
and stays closest to upstream OpenRewrite behavior.

## Decision

`RewriteRunner.run()` tries the official plugin first, as "Stage 0", before building any
in-process LST. The in-process four-stage pipeline becomes the **fallback**, not the primary
path.

- Stage 0 is default-on and gated by a single opt-out: `--skip-plugin-run` /
  `Builder.skipPluginRun(true)`.
- `PluginRecipeRunner` dispatches to a `PluginBuildStrategy` per build tool. When both root
  descriptors are present it is **try-with-fallback, Gradle then Maven** — if the Gradle
  plugin invocation fails, it tries the Maven plugin before giving up. (This is deliberately
  *not* routed through the exclusive Gradle-first marker verdict of [ADR 0001](0001-build-unit-classpath-resolution.md);
  forcing it through that verdict would drop the Maven fallback.)
- Plugin outcomes are a sealed result: `Success` (short-circuit, return the plugin's patch
  diffs), `NoChanges` (short-circuit, empty result), `Partial` (orphan-monorepo hybrid; see
  below), `Failed`, or `Skipped` (no build tool). `Failed` and `Skipped` **fall through
  silently** to the LST pipeline — a plugin that cannot resolve, times out, or finds no build
  tool must never abort the run.
- **`Success` and `NoChanges` require evidence, not just exit code 0.** A declarative recipe whose
  `recipeList` names a recipe absent from the plugin's classpath is logged at ERROR and then
  *survives*: upstream runs the entries it could resolve and exits 0 with real patches. Stage 0
  therefore inspects captured plugin output for upstream's unresolved-recipe markers after the
  dry-run and classifies that run as `Failed`, which falls through as above. Since the stages
  resolve recipe artifacts independently, the LST pipeline may run the whole recipe. The check
  precedes both the diff check and the apply goal, so a sub-recipe gap is caught whether or not
  patches were produced and no partial migration reaches disk. See issue #268.
- **Orphan (root-less monorepo) units.** When the project root has *no* build descriptor,
  Stage 0 no longer gives up. It reuses `discoverBuildUnits` ([ADR 0001](0001-build-unit-classpath-resolution.md))
  to find orphan build units in subdirectories and runs the plugin in each (distinct dir,
  Gradle-then-Maven per dir), rebasing every diff to the repository root via the existing
  `DirectPluginExecutor` base-dir rebasing and the 2-arg wrapper resolvers. Because the plugin
  runs from each unit dir rather than the root, the other root-relative inputs are rebased per
  unit so Stage 0 stays equivalent to the LST fallback (which runs from the root): an implicit
  root `rewrite.yaml`/`rewrite.yml` is resolved to its absolute path and forwarded explicitly
  (a subdir run would otherwise never see it), and `--exclude-paths` / `--plain-text-masks`
  globs anchored to a unit's own path (e.g. `svc-a/generated/**` for unit `svc-a`) have that
  prefix stripped. The root path is unchanged whenever any root descriptor exists. Aggregation:
  - all discovered units covered without failure → `Success` / `NoChanges`;
  - some units produced diffs while others failed (or discovery was truncated) → `Partial`,
    carrying the successful units' rebased diffs;
  - nothing usable → `Failed` / `Skipped`.
- **Hybrid merge for `Partial`.** `RewriteRunner` keeps the `Partial` diffs and falls through
  to the LST pipeline over the **whole project** (no exclusions — recipes are idempotent, so
  LST is a no-op over already-applied subtrees). The two are merged with **Stage 0 winning on
  any path collision** (an LST `Result` for a path already in the Stage 0 diffs is dropped
  before writing), and `stageUsed` stays `PLUGIN` because the plugin contributed. This keeps
  Stage 0's higher-fidelity results for the units where it worked while still covering the
  remainder.
- Diagnostics record `stageUsed = UsedExecutionStage.PLUGIN` so a consumer can tell the
  plugin path produced the run (see [ADR 0004](0004-estimated-time-saved-source.md) and the
  `ExecutionDiagnostics` work).

The plugin path returns a git-format patch rather than in-process `Result` objects, which is
why `RunResult` carries both `rawDiffs` (Stage 0 patches) and `results` (LST/specialized-pass
results), and why metrics like estimated-time-saved had to be sourced differently per path.
The same `rawDiffs` + `results` split carries the orphan hybrid merge: `Partial` diffs land in
`rawDiffs`, the leftover LST `results` in `results`, and the estimated-time-saved is the sum of
the successful units' estimates plus the LST run's.

## Consequences

Stage 0 is the fastest and most faithful path for buildable JVM projects, and it is what most
real runs use. The substantial LST engine still earns its keep: it is the fallback for
non-buildable projects, offline scenarios, and `--skip-plugin-run` debugging.

The cost is that rewrite-runner now has **two execution paths that must stay observably
consistent** — every cross-cutting feature (path exclusions, plain-text masks, estimated time
saved, specialized Docker/HCL/protobuf parsing) has to be implemented twice or forwarded to
both paths, and several later ADRs (0002, 0004, 0005) exist precisely to reconcile a Stage 0
gap against LST-path behavior. The silent fall-through also means a misconfigured plugin run
can be masked by a successful LST fallback; this is mitigated by the real-plugin test lane,
which asserts `stageUsed == PLUGIN` so an accidental fallback fails CI.
