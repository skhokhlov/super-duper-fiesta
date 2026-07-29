# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Deep-dive docs** (read these before working on the relevant area):
- [`docs/architecture.md`](docs/architecture.md) — execution pipeline, 4-stage LST, file routing, version detection, module layout
- [`docs/dependency-resolution.md`](docs/dependency-resolution.md) — how/why recipe vs project deps are resolved, Aether session settings, scope pruning, local repo strategy
- [`docs/library-api.md`](docs/library-api.md) — `RewriteRunner` builder, `RunResult`, `ToolConfig` YAML schema, exit codes
- [`docs/testing.md`](docs/testing.md) — TDD requirement, test patterns, gotchas, test file map
- [`docs/build.md`](docs/build.md) — `buildSrc` convention plugins, dependency versions, CI/CD, known issues

---

## Breaking changes in vNEXT

`--include-extensions` and `--exclude-extensions` have been removed; the `Builder.includeExtensions(...)`, `Builder.excludeExtensions(...)`, and the YAML `parse.includeExtensions` / `parse.excludeExtensions` keys are gone too. The new surface aligns with the upstream OpenRewrite Gradle/Maven plugins' native exclusion primitive:

| Before | After |
|--------|-------|
| `--exclude-extensions .md,.json` | `--exclude-paths "**/*.md,**/*.json"` |
| `--include-extensions .java` | (no replacement — exclusion is the only filter, matching upstream) |
| `Builder.includeExtensions(...)` / `Builder.excludeExtensions(...)` | (removed; use `Builder.excludePaths(...)`) |
| `parse.includeExtensions` / `parse.excludeExtensions` in YAML | (removed; use `parse.excludePaths`) |

`--exclude-paths` is forwarded both to the Stage 0 plugin invocation (Maven: `-Drewrite.exclusions=…`; Gradle: `exclusion(...)` lines in the generated init script) and to the LST fallback pipeline, so the two paths apply identical filtering. When the exclusion list eliminates every JVM source file from scope, classpath resolution stages 1–4 are skipped entirely.

---

## Quick Commands

```bash
./gradlew shadowJar              # Build fat JAR → cli/build/libs/cli-1.0-SNAPSHOT-all.jar
./gradlew test                   # UNIT tests only (excludes *IntegrationTest)
./gradlew check                  # unit tests + ktlintCheck + offline integration
./gradlew :cli:testIntegration   # Offline integration (fake wrappers + real fallback Gradle)
./gradlew :cli:testRealPlugin    # Real-plugin integration tests (downloads Maven/Gradle)
./gradlew :cli:testContainer     # Fat JAR in a Docker 2 GiB cgroup
./gradlew productionCheck        # every production lane before release publication
./gradlew ktlintFormat           # Auto-fix formatting

# Run a single test class
./gradlew test --tests "io.github.skhokhlov.rewriterunner.output.ResultFormatterTest"

# Run a single test method (use backtick-quoted name for Kotlin)
./gradlew test --tests "io.github.skhokhlov.rewriterunner.cli.RunCommandTest.default output mode is diff"

# Run the tool locally
java -jar cli/build/libs/cli-1.0-SNAPSHOT-all.jar --help
```

## CLI Options (`RunCommand`)

| Flag | Short | Description | Default |
|------|-------|-------------|---------|
| `--project-dir` | `-p` | Project root directory | `.` |
| `--active-recipe` | `-r` | Recipe name to run (required) | — |
| `--recipe-artifact` | | Maven coordinates (repeatable) | — |
| `--rewrite-config` | | Path to custom `rewrite.yaml` | — |
| `--output` | `-o` | Output mode: `diff`, `files`, `report` | `diff` |
| `--cache-dir` | | JAR cache directory | `~/.rewriterunner/cache` |
| `--config` | | Path to `rewriterunner.yml` | `<projectDir>/rewriterunner.yml`, then `~/.rewriterunner/rewriterunner.yml` |
| `--dry-run` | | Run without writing to disk | `false` |
| `--skip-plugin-run` | | Skip official plugin-first execution and use the LST pipeline directly | `false` |
| `--subprocess-run-timeout` | | Build-tool subprocess timeout for the fallback LST pipeline | `120s` |
| `--plugin-run-timeout` | | Stage 0 Gradle/Maven plugin timeout | `10m` |
| `--resolver-connect-timeout` | | Maven Resolver TCP connection timeout | `30s` |
| `--resolver-request-timeout` | | Maven Resolver socket/request timeout | `60s` |
| `--exclude-paths` | | Comma-separated glob patterns of files to skip (e.g. `**/generated/**,**/*.md`); forwarded to Stage 0 plugin and LST fallback alike | — |
| `--plain-text-masks` | | Comma-separated glob patterns of otherwise-unhandled files to parse as plain text; replaces default masks when specified | upstream defaults |
| `--execution-mode` | | `forked` (default) or `in-process` LST execution | `forked` |
| `--executor-jvm-arg` | | Repeatable JVM arg shared by runner-owned executors; use `=` for values beginning with `-` | — |
| `--plugin-jvm-arg` | | Repeatable JVM arg appended for the Stage 0 plugin executor | — |
| `--lst-worker-jvm-arg` | | Repeatable JVM arg appended for the LST worker | — |
| `--lst-worker-timeout` | | Optional whole-worker timeout | unlimited |
| `--info` | | Enable INFO-level logging to stderr | `false` |
| `--debug` | | Enable DEBUG-level logging (overrides `--info`) | `false` |
| `--no-maven-central` | | Disable Maven Central; use only repos from config | `false` |

**Output modes**: `diff` (unified diffs) · `files` (one path per line) · `report` (JSON to `openrewrite-report.json`)

## Directory Structure

```
buildSrc/src/main/kotlin/          # Convention plugins (kotlin-convention, publishing-convention, dokka-convention)
core/src/
├── main/kotlin/.../rewriterunner/
│   ├── RewriteRunner.kt            # Library facade — builder API, orchestrates the full pipeline
│   ├── RunResult.kt
│   ├── config/ToolConfig.kt        # YAML config + env var interpolation
│   ├── plugin/                     # Stage 0 official Gradle/Maven plugin execution + patch parsing
│   ├── lst/
│   │   ├── LstBuilder.kt           # Orchestrates 4-stage pipeline + multi-language parsing
│   │   ├── ClasspathStage.kt       # Unified stage seam; resolve() returns null to fall through
│   │   ├── ProjectBuildStage.kt       # Stage 1: Maven/Gradle subprocess (build-classpath/init-script)
│   │   ├── DependencyResolutionStage.kt  # Stage 2: mvn dependency:tree / gradle dependencies subprocess
│   │   ├── BuildFileParseStage.kt      # Stage 3: Static build file parse + POM traversal
│   │   ├── LocalRepositoryStage.kt     # Stage 4: Local cache scan
│   │   └── utils/
│   │       ├── FileCollector.kt        # NIO walk, excluded-dir filtering, glob exclusions, extension resolution
│   │       ├── ProjectClassDirs.kt     # Shared compiled-output class directory scan
│   │       ├── ProcessRunner.kt        # Build-unit discovery, wrapper command resolution, subprocess runner
│   │       ├── VersionDetector.kt      # Java/Kotlin JVM-version walk-up + parseGradleVersionFromWrapper
│   │       ├── GradleDslClasspathResolver.kt  # Locate Gradle installation for DSL classpath
│   │       ├── MarkerFactory.kt        # BuildTool, GitProvenance, OperatingSystem, GradleProject markers
│   │       └── StaticBuildFileParser.kt      # Shared static parser for pom.xml, build.gradle, version catalogs
│   ├── output/ResultFormatter.kt   # diff/files/report output modes
│   └── recipe/
│       ├── RecipeArtifactResolver.kt
│       ├── RecipeLoader.kt
│       └── RecipeRunner.kt
└── test/kotlin/.../rewriterunner/
    ├── config/ToolConfigTest.kt
    ├── lst/                        # LstBuilderTest, FileCollectorTest, stage tests, version detection tests
    └── output/ResultFormatterTest.kt

cli/src/
├── main/kotlin/.../
│   ├── Main.kt                     # Entry point; setLogLevel() adjusts Logback at runtime
│   └── cli/RunCommand.kt           # Picocli root command; delegates to RewriteRunner
└── test/kotlin/.../
    ├── cli/RunCommandTest.kt
    └── integration/                # BaseIntegrationTest + per-language integration tests
```

## Development Approach

**TDD is required**: write a failing test first, then implement. See [`docs/testing.md`](docs/testing.md).

## Important Implementation Notes

- `InMemoryLargeSourceSet` is in `org.openrewrite.internal` (not the top-level package)
- `ProjectBuildStage`, `DependencyResolutionStage`, and `BuildFileParseStage` implement `ClasspathStage`; subclass and override `resolve(projectDir, parseFailures)` in tests instead of mocking. `ProjectBuildStage.extractClasspath` / `tryCompile` remain internal helpers for focused Stage 1 tests.
- Stages 1 and 2 use `discoverBuildUnits` from `ProcessRunner.kt`: root descriptors keep one root invocation per tool, while root-less monorepos discover top-most subdirectory build units to depth 3, skip default excluded dirs, sort candidates before the 25-unit cap, and require full discovered-unit coverage before a stage is considered complete. Build units are non-exclusive, so a root with both Maven and Gradle descriptors resolves both. Partial or capped coverage falls through to later fallback stages. See `CONTEXT.md` and `docs/adr/0001-build-unit-classpath-resolution.md`.
- Classpath stages return `null` to fall through and a `ClasspathResolutionResult` to terminate. `LstBuilder` appends project class dirs once after the winning stage. Gradle project data is attached only by a winning Stage 1 or Stage 2 result; Stage 3/4 wins do not inherit metadata from a Stage 2 fall-through. See `docs/adr/0003-classpath-stage-seam.md`.
- Build-tool provenance markers use `detectBuildTool` from `ProcessRunner.kt`, which is an exclusive root-level verdict: Gradle wins over Maven with a warning when both descriptors are present, Maven is used for pom-only roots, and no marker is attached when no root descriptor exists. This is marker-only; do not route classpath resolution or `PluginRecipeRunner` through it. `PluginRecipeRunner` intentionally keeps its Gradle-then-Maven try-with-fallback behavior.
- `ResultFormatter` has a secondary constructor accepting `PrintWriter`; `RunCommand` passes picocli's `@Spec` output writer
- `RecipeLoader` rejects a partially initialized recipe. `DeclarativeRecipe.initialize` drops a `recipeList` entry it cannot resolve and only records a `Validated.Invalid` for it, so `RecipeLoader` consults `Recipe.validate()` and throws an `IllegalArgumentException` naming every unresolved recipe. Detection is **structural, not message-based**: failures whose `property` contains `.recipeList[` carry the missing name in `invalidValue` (`getValue()` throws on an `Invalid`). Entries nested inside sub-recipes accumulate onto the root recipe's validation, so one `validate()` call covers the whole list at any depth, and upstream hardcodes `.recipeList` for `preconditions` too, so an unresolved precondition fails the load as well. Only unresolved recipes are fatal — unrelated option validations keep their existing behaviour. Running the subset that did resolve would report success for an incomplete migration (per proposed ADR 0011).
- Disk application of LST results lives in `core/.../rewriterunner/apply/`; see ADR 0008 for the `ChangeWriter` seam and `ExecutionDiagnostics.writeOutcome`.
- Stage 0 is the default path; the in-process LST pipeline is the fallback (see `docs/adr/0006-plugin-first-execution.md`). Stage 0 tries the official Gradle/Maven OpenRewrite plugins first. On plugin success, Docker/HCL/protobuf files are partitioned out by unconditional Stage 0 exclusions and handled by a restricted classpath-free LST pass, then merged into the same `RunResult` (`rawDiffs` for plugin patches, `results` for specialized parser changes). The specialized pass is best-effort: if the recipe is unresolvable from rewrite-runner's artifacts/`rewrite.yaml`, it degrades to plugin-only results with a warning rather than failing the run. Stage 0 also forwards data-table export (Maven `-Drewrite.exportDatatables=true`; Gradle `exportDatatables = true`) and reads `ExecutionDiagnostics.estimatedTimeSaved` through `EstimatedTimeSavedResolver`, which tries exported `SourcesFileResults` data-table sources before the plugin's own `Estimate time saved` output line, accepts only strictly positive durations, and returns `null` when no source is positive. Use `--skip-plugin-run` / `Builder.skipPluginRun(true)` when testing or debugging the full in-process LST pipeline directly.
- Stage 0 orphan (root-less monorepo) support: when the project root has no build descriptor, `PluginRecipeRunner` reuses `discoverBuildUnits` to find orphan build units in subdirectories and runs the plugin in each distinct dir (Gradle-then-Maven per dir), rebasing diffs to the repo root via `DirectPluginExecutor`'s base-dir rebasing (`runDir` ≠ rebase root) and the 2-arg `resolveGradleCommand`/`resolveMavenCommand` wrapper resolvers. Because the plugin runs from each unit dir, other root-relative inputs are rebased per unit (`rebaseGlobsForUnit`): an implicit root `rewrite.yaml`/`rewrite.yml` is resolved and forwarded explicitly (a subdir run would otherwise never see it), and exclude/plain-text globs anchored to a unit's path (e.g. `svc-a/generated/**`) lose that prefix. The root path is unchanged when any root descriptor exists. Aggregation returns `Success`/`NoChanges` when all units are covered, `PluginRunResult.Partial` when some units produced diffs but others failed or discovery was truncated, else `Failed`/`Skipped`. On `Partial`, `RewriteRunner` keeps the Stage 0 diffs (`rawDiffs`) and falls through to the LST pipeline over the whole project, merging with **Stage 0 winning per-path** (LST `Result`s for paths Stage 0 already produced are dropped); `stageUsed` stays `PLUGIN` and `estimatedTimeSaved` is summed. See ADR 0006.
- Path exclusion: `--exclude-paths` (CLI) overrides `parse.excludePaths` (YAML) when non-empty. The resolved value is forwarded to Stage 0 and to the LST fallback so both apply identical filtering. When no JVM source survives the exclusion, classpath resolution stages 1–4 are skipped. Filtering is exclusion-only by design — no `--include-paths`/allowlist (see `docs/adr/0007-exclusion-only-path-filtering.md`).
- Plain-text masks: `--plain-text-masks` (CLI) overrides `parse.plainTextMasks` (YAML) when non-empty; if both are empty, the upstream default mask list is used. The resolved value is always forwarded to Stage 0 (Maven `-Drewrite.plainTextMasks=…`; Gradle `plainTextMasks.clear()` + `plainTextMask(...)`) and to the LST fallback. Specialized parsers take precedence on the LST path, so `Dockerfile*` still uses `DockerParser`; parsing every unmatched text file is a future direction, not current behavior.
- Forked execution is the default: `RunCoordinator` keeps the coordinator small, waits for Stage 0
  to exit, and then starts one LST worker when needed. Shared `executorJvmArgs` compose before
  stage-specific plugin/worker arguments. When no explicit runner, JDK-environment, or target-build
  policy exists, a conservative container-aware `-Xmx` is added to runner-owned children. Use
  `ExecutionMode.IN_PROCESS` only for rich `Result` objects or a custom `ChangeWriter`; forked runs
  return `rawDiffs` and an empty `results` list. See `docs/architecture.md#executor-jvm-memory` and
  ADR 0010.
- Dockerfile/Containerfile files are collected both by extension (`.dockerfile`, `.containerfile`) and by filename prefix (`Dockerfile*`, `Containerfile*`) — all go into the `.dockerfile` bucket for `DockerParser`
- `pom.xml` files are routed to `MavenParser` (full resolution — parent POMs, property interpolation, BOM imports); all other `.xml` files use `XmlParser`. This split happens in the `.xml` routing block inside `LstBuilder.build()` by filtering on `file.name == "pom.xml"`. `MavenParser` adds `MavenResolutionResult` marker, enabling the full `rewrite-maven` recipe catalog. Resolution uses `~/.m2/settings.xml` and local repo; artifacts already cached require no network access.
- `LstBuilder` delegates to `FileCollector` (file walk/filtering), `VersionDetector` (Java/Kotlin version detection), `GradleDslClasspathResolver` (Gradle installation lookup), and `MarkerFactory` (provenance/build-tool markers). These are internal helpers instantiated inside `LstBuilder`'s constructor body.
- `LstBuilder.parseGradleVersionFromWrapper` and `LstBuilder.resolveGradleDslClasspath` are `internal` thin delegations to `VersionDetector` / `GradleDslClasspathResolver` preserved for test backward compatibility.
- Parsers requiring external runtimes (Python via RPC, JavaScript/TypeScript via Node.js, C# via .NET) are **not** included — they need out-of-process services
- Upstream `rewrite-gradle-plugin` and `rewrite-maven-plugin` versions live in `gradle/libs.versions.toml` (`rewrite-gradle-plugin`, `rewrite-maven-plugin` keys). The `generatePluginVersions` task in `core/build.gradle.kts` emits a generated `BuildPluginVersions` object that `ToolConfigDefaults.REWRITE_*_PLUGIN_VERSION` reads from. Bump in the TOML — never edit the generated file.
- Tests are split into three lanes by Gradle `Test.filter` class-name pattern (no Kotest tags):
  - `:cli:test` — unit tests only, excludes `*IntegrationTest`. Wired into `check`.
  - `:cli:testIntegration` — offline integration suite: per-language tests, fake-wrapper Stage 0 coverage, and real nested-Gradle fallback attribution using the current distribution. Includes `*IntegrationTest`, excludes `PluginRealExecutionIntegrationTest`.
  - `:cli:testRealPlugin` — real OpenRewrite Maven/Gradle plugins (downloads distributions, hits Maven Central). The Gradle distribution under test tracks the project's own `gradle-wrapper.properties` (forwarded via `-Drewriterunner.test.gradleVersion`).
- Stage 0 plugin execution is covered both by fake-wrapper tests (`PluginFirstIntegrationTest`, in the `testIntegration` lane) and real-wrapper tests (`PluginRealExecutionIntegrationTest`, in the `testRealPlugin` lane). The real-wrapper suite calls `RewriteRunner` directly and asserts `executionDiagnostics.stageUsed == UsedExecutionStage.PLUGIN`, and non-dry-run real plugin scenarios assert positive `estimatedTimeSaved`, so an accidental LST-fallback success or plugin-output drift cannot mask a Stage 0 regression.
- CI runs offline checks first; Windows worker and real-plugin jobs follow, then container acceptance follows the real-plugin lane. See [`docs/testing.md`](docs/testing.md) and [`docs/build.md`](docs/build.md).

## Logging

SLF4J + Logback. `logback.xml` in `cli/src/main/resources/` (root OFF by default, pattern `[%-5level] %message%n` on stderr). `logback-test.xml` in `core/src/test/resources/` (WARN only). See [`docs/testing.md`](docs/testing.md) for log capture patterns.

## Commit Message Convention

Format: `<type>(<scope>): <description>` (lowercase, imperative, no trailing period)

**Types**: `feat` `fix` `docs` `style` `refactor` `test` `chore` `perf` `ci`
**Scopes**: `cli` `core` `lst` `recipe` `config` `output` `deps`

```
feat(lst): add Scala source file parser
fix(cli): correct default output mode when --output is omitted
chore(deps): upgrade OpenRewrite BOM to 3.11.0
```

Breaking changes: append `!` after type/scope, add `BREAKING CHANGE:` footer. See `CONTRIBUTING.md`.

## Code Style

ktlint (`com.pinterest.ktlint:ktlint-cli:1.8.0`) with **Google Android** code style (`.editorconfig`: `ktlint_code_style = android_studio`). CI fails on violations — run `./gradlew ktlintFormat` before pushing.

## Keeping CLAUDE.md and docs/ Current

Update the relevant file whenever:
- A new feature, parser, CLI flag, or output mode is added
- An architectural or dependency decision changes
- A new test pattern or convention is established
- A recurring bug or workaround is discovered

Prefer updating existing entries over appending. Keep CLAUDE.md as an index — details belong in `docs/`.
