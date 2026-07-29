# Architecture

## Overview

Fat JAR CLI that runs OpenRewrite recipes against arbitrary project directories **without requiring a working build system**. Also usable as a library (`core` module).

**Entry point**: `Main.kt` → `CommandLine(RunCommand()).execute(*args)`.
`RunCommand` is the **root command** (not a subcommand) — all options are passed directly without a "run" prefix.

## Execution Pipeline

`RewriteRunner.run()` delegates to `RunCoordinator`. The coordinator owns configuration, Stage 0
routing, diagnostics, and process lifecycle; it does not retain LSTs in default forked mode.
Its `PostPluginExecutor` seam selects either `ForkedPostPluginExecutor` (the default) or
`InProcessLstExecutor` (explicit compatibility mode), while the shared LST engine remains the
single implementation of full fallback and specialized-only work.

```text
Coordinator JVM
  ├─ official Gradle/Maven plugin process tree
  └─ one LST worker JVM after the plugin process exits
       ├─ FULL_FALLBACK after a skipped, failed, or partial plugin attempt
       └─ SPECIALIZED_ONLY after a successful plugin attempt
```

The fair coordinator gate serializes heavy runner-owned execution. Nested project-owned classpath
builds remain descendants of the active worker; rewrite-runner does not change their JVM policy.

| Step | Owner | Description |
|------|-------|-------------|
| 0 | Coordinator | Resolve YAML/programmatic/CLI configuration and record the memory plan |
| 1 | Official plugin executor | Try Gradle or Maven OpenRewrite plugin for build-owned files |
| 2 | LST worker or explicit in-process engine | Resolve recipes, build LSTs, run recipes, filter Stage 0-owned paths, and apply changes |
| 3 | Coordinator | Merge raw diffs, changed paths, write outcomes, and compact executor attempts |
| 4 | `ResultFormatter` | Format diff/files/report output |

## Stage 0: Plugin-First Execution

Stage 0 is the default path and the in-process LST pipeline is the fallback; see [ADR 0006](adr/0006-plugin-first-execution.md) for why.

`PluginRecipeRunner` checks the project root for Gradle or Maven build files and tries the official OpenRewrite plugin before the LST executor:

| Build tool | Strategy | Patch path |
|------------|----------|------------|
| Gradle | Generate a temporary init script, apply `org.openrewrite.gradle.RewritePlugin`, enable `exportDatatables`, run `rewriteDryRun`, then `rewriteRun` when not in dry-run mode | `build/reports/rewrite/rewrite.patch` in each changed project |
| Maven | Invoke `org.openrewrite.maven:rewrite-maven-plugin:<version>:dryRun`, then `:run` when not in dry-run mode; pass `-Drewrite.exportDatatables=true` and pin patch output with `-DreportOutputDirectory=<temp>` | Private temp report directory managed by rewrite-runner |

The dry-run goal always runs first so `PatchParser` can split `rewrite.patch` files into `RunResult.rawDiffs`. If the plugin returns non-empty patches and the apply goal succeeds (or the user requested `dryRun`), `RewriteRunner.run()` returns immediately with empty `results` and populated `rawDiffs`. Gradle project patches and Maven module patches are rebased to project-root-relative paths before formatting. `ResultFormatter.format(RunResult)` handles this raw-diff path for `diff`, `files`, and `report` output.

Stage 0 also populates `ExecutionDiagnostics.estimatedTimeSaved` when OpenRewrite reports it. The structured path reads the latest exported `SourcesFileResults` data table; current plugin versions may only log the same value as `Estimate time saved: ...`, so the runner captures plugin stdout/stderr and falls back to that line. The value is not derived from patch contents.

When Stage 0 succeeds, rewrite-runner still runs a lightweight `SPECIALIZED_ONLY` pass over its
specialized owned set: Dockerfile/Containerfile, HCL/Terraform, and protobuf files. In default mode
that pass is a worker JVM. These formats are excluded from the plugin invocation up front, parsed
classpath-free by rewrite-runner, and merged with the plugin raw diffs. If the specialized worker
fails after Stage 0 success, the plugin result is preserved with a diagnostic warning.
See [ADR 0005](adr/0005-stage0-specialized-parser-ownership.md).

If plugin execution is skipped or fails (no build file, non-zero exit, process start failure, timeout, missing recipe/plugin), the coordinator starts a `FULL_FALLBACK` worker. A full-fallback worker failure is terminal and is never retried in-process. `--skip-plugin-run` / `Builder.skipPluginRun(true)` bypasses Stage 0.

**Stage 0 verifies its own success.** Exit code 0 is not proof the plugin ran the whole recipe: when
a declarative recipe's `recipeList` names a recipe that is not on the plugin's classpath, upstream
logs the gap at ERROR (`failOnInvalidActiveRecipes` defaults to `false`), executes only the entries
it could resolve, and exits 0 with real patches. `DirectPluginExecutor` therefore inspects captured
plugin output for upstream's unresolved-recipe markers immediately after the dry-run — **before**
the diff check and before the apply goal — and returns `PluginRunResult.Failed` naming the
unresolved recipes. Because the two stages resolve recipe artifacts from independent sources, that
routes to the LST fallback, which may hold the artifact Stage 0 could not reach; failing before
apply also keeps a partial migration off disk. The check runs even when Stage 0 produced diffs — a
sub-recipe gap coexists with real patches, so "diffs exist" is not evidence of a complete run.
`PluginOutputReader.unresolvedRecipeFailure` owns the marker set; it is anchored on exact upstream
strings and is therefore version-coupled to the pinned plugin versions in `gradle/libs.versions.toml`.

Path exclusions and plain-text masks are resolved once by `RewriteRunner` and forwarded to Stage 0
and to the LST fallback so both paths select the same files. Stage 0 also receives the specialized
owned-set exclusions unconditionally; the specialized pass receives only user/YAML exclusions so it
can own those files beside a successful plugin run.

## Executor JVM memory

Forked execution is the default. The coordinator heap remains caller-managed; memory-intensive LST
work never runs in that JVM unless `execution.mode: in-process` is selected explicitly. Stage 0 and
the LST worker are sequenced, not overlapped.

Runner-owned arguments compose as `execution.executorJvmArgs + stage-specific jvmArgs`; stage
arguments are appended last and passed as `ProcessBuilder` tokens. CLI list options are flat and
repeatable: `--executor-jvm-arg`, `--plugin-jvm-arg`, and `--lst-worker-jvm-arg`.

When no runner arguments, inherited `JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS`, or explicit target
build JVM configuration exists, rewrite-runner calculates only `-Xmx` from the container-aware JDK
memory total: 50% below 1 GiB; otherwise `min(70%, M - 512 MiB, 16 GiB)`, rounded down to MiB. It
does not infer host memory when the JDK cannot report a valid total. Any supplied runner argument
opts out of automatic sizing, even if it is not an `-Xmx` flag.

For Gradle, nonempty runner arguments become the complete `org.gradle.jvmargs` command-line value.
For Maven, they are appended to inherited `MAVEN_OPTS`, after project `.mvn/jvm.config` arguments.
Inherited `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS` are never removed and are reported as external
policy. `-Xmx` is a heap ceiling, not a hard RSS/container limit.

Default forked library results intentionally contain `results = emptyList()` and the worker's
transportable `rawDiffs`. Select `ExecutionMode.IN_PROCESS` when a library caller needs rich
OpenRewrite `Result` objects or a custom `ChangeWriter`.

### CPU

Orphan build units run **sequentially** (`PluginRecipeRunner` iterates units one at a time), and
Gradle is invoked with `--no-daemon --no-parallel`. There is no parallel fan-out in plugin or LST
execution, so CPU and memory are bounded to one active subprocess at a time. No scheduling knob is
provided or needed.

## Maven Local Repository Strategy

Two separate `AetherContext` instances are created per `RewriteRunner.run()` invocation, each with a distinct local Maven repository:

| Context | Local repository | Used by |
|---------|-----------------|---------|
| `recipeContext` | `<cacheDir>/repository` | `RecipeArtifactResolver` — recipe JARs |
| `projectContext` | `~/.m2/repository` (Maven default) | `DependencyResolutionStage` — project deps |

Recipe JARs are cached under the tool's own `cacheDir` so they never pollute the user's Maven local repository. Project dependencies resolve against `~/.m2/repository` so artifacts already downloaded by the project's own build (Maven/Gradle) are reused without re-downloading.

`AetherContext.build(localRepoDir)` accepts the local repository path directly — callers are responsible for choosing between the tool cache and the Maven default.

## 4-Stage LST Classpath Resolution

`LstBuilder` runs an ordered list of `ClasspathStage` implementations. Each stage receives the
project directory plus the parse-failure accumulator and returns either a
`ClasspathResolutionResult` to terminate resolution or `null` to fall through to the next stage:

| Stage | Class | Maven | Gradle |
|---|---|---|---|
| 1 | `ProjectBuildStage` | `mvnw dependency:build-classpath` | Gradle init script |
| 2 | `DependencyResolutionStage` | `mvn dependency:tree` subprocess | `gradle dependencies` subprocess |
| 3 | `BuildFileParseStage` | Static `pom.xml` parse + POM traversal | Static `build.gradle(.kts)` + version catalog parse + POM traversal |
| 4 | `LocalRepositoryStage` | Local `~/.m2` cache scan | Local `~/.gradle/caches` scan |

- **Stage 1** (`ProjectBuildStage`): Runs the project's own build tool to extract the exact compile classpath. It iterates discovered build units, so root-less monorepos with module-local build files can still use build-tool classpaths. On success, it also attempts compilation when no project class dirs exist yet and collects Gradle project data for markers.
- **Stage 2** (`DependencyResolutionStage`): Runs `mvn dependency:tree` / `gradle dependencies` subprocesses per discovered build unit and resolves downloaded JARs directly via Aether. Supports Maven-only, Gradle-only, mixed, and root-less projects. Falls through when subprocesses fail or resolve no JARs.
- **Stage 3** (`BuildFileParseStage`): Parses `pom.xml` and `build.gradle(.kts)` statically (no subprocess) for all discovered modules, then resolves via full Maven Resolver POM traversal to obtain transitive dependencies. Falls through when no build files exist or resolution fails.
- **Stage 4** (`LocalRepositoryStage` adapter): Scans `~/.m2` and `~/.gradle/caches` for already-cached JARs matching Stage 3's declared coordinates. Falls through when no cached JARs are found, leaving the orchestrator to produce the single empty result.

Stages 1 and 2 use the build-unit model recorded in
[`docs/adr/0001-build-unit-classpath-resolution.md`](adr/0001-build-unit-classpath-resolution.md).
A build unit is a Maven or Gradle invocation directory. Root descriptors preserve the historical
single-root invocation for that tool; when a root descriptor is absent, top-most subdirectory build
files are discovered to depth 3, default excluded directories are skipped, and at most 25 units are
processed before warning and relying on later fallback stages.

The resolved classpath is **shared across all language parsers** — `JavaParser`, `KotlinParser`, and `GroovyParser` all receive the same project classpath so cross-language type references resolve correctly.

`LstBuilder` appends project class directories (`target/classes`, `build/classes/java/main`, etc.)
to the winning stage's classpath once, after the `ClasspathStage` list has selected a result. When
Stage 1 triggered compilation, this single append picks up any class dirs it produced.

Gradle project data belongs only to the winning stage. Stage 1 collects it via Stage 2's metadata
collector, and Stage 2 includes it when Stage 2 itself wins. If Stage 2 gathers Gradle data but
falls through with an empty classpath, a later Stage 3 or Stage 4 win does not inherit that data, so
GradleProject markers are not attached on that path. See
[`docs/adr/0003-classpath-stage-seam.md`](adr/0003-classpath-stage-seam.md).

When `--exclude-paths` (or `parse.excludePaths`) removes every JVM source file (`.java`, `.kt`, `.kts`, `.groovy`, `.gradle`) from scope, all four classpath stages are **skipped entirely** — there is no `mvn`/`gradle` subprocess, no POM walk, no local-repo scan. The build emits a single `INFO` line (`"No JVM source files in scope — skipping classpath resolution stages."`) and proceeds directly to the language parsers, which run with an empty classpath. This optimization keeps non-JVM workflows (e.g. running a YAML-only recipe) fast.

Path filtering is exclusion-only by design (no include/allowlist), matching upstream OpenRewrite's only native primitive so Stage 0 and the LST fallback filter identically; see [ADR 0007](adr/0007-exclusion-only-path-filtering.md).

## LST Module Structure

`LstBuilder` is an orchestrator that delegates each concern to a focused helper class:

| Class | Responsibility |
|-------|---------------|
| `LstBuilder` | Orchestration, `ClasspathStage` list, parser dispatch |
| `ClasspathStage` | Unified classpath-stage seam; `resolve(...)` returns null to fall through |
| `FileCollector` | NIO walk, excluded-dir filtering, glob exclusions, plain-text masks; extension set is fixed (`DEFAULT_EXTENSIONS`) |
| `VersionDetector` | Java/Kotlin JVM-version walk-up, `normalizeJvmVersion`, `parseGradleVersionFromWrapper` |
| `GradleDslClasspathResolver` | Locate Gradle installation (`GRADLE_HOME`, wrapper, `~/.gradle/wrapper/dists/`) |
| `MarkerFactory` | `BuildTool`, `GitProvenance`, `OperatingSystemProvenance`, `BuildEnvironment`, `GradleProject` markers |

`ProjectBuildStage`, `DependencyResolutionStage`, and `BuildFileParseStage` remain injected into
`LstBuilder` as `open` `ClasspathStage` implementations; the helper classes above are internal and
are instantiated by `LstBuilder`.

`ProcessRunner.kt` owns two separate build-tool concepts. `discoverBuildUnits` is non-exclusive and
feeds classpath resolution; `detectBuildTool` is the exclusive marker verdict used by
`MarkerFactory`. When both Maven and Gradle root descriptors exist, the marker verdict is Gradle
with a warning, while Stages 1 and 2 still resolve both tools through build units.

External process timeouts are configurable:

| Timeout | Default | Applies to |
|---------|---------|------------|
| `pluginTimeout` | `10m` | Stage 0 `rewriteDryRun` / `rewriteRun` plugin invocations |
| `processTimeout` | `120s` | Stage 1/2 build-tool subprocesses, compile attempts, and build-tool metadata commands |
| `resolverConnectTimeout` | `30s` | Maven Resolver TCP connections |
| `resolverRequestTimeout` | `60s` | Maven Resolver socket reads / requests |

Stage 0 plugin versions are also configurable via `rewriteGradlePluginVersion`
and `rewriteMavenPluginVersion`.

## File Routing by Extension

`LstBuilder` delegates file collection to `FileCollector`. Excludes `build/`, `target/`, `node_modules/`, `.git/`, `.gradle/`, `.idea/`, `out/`, `dist/` by default.

| Extension | Parser | Classpath |
|-----------|--------|-----------|
| `.java` | `JavaParser` | Project classpath |
| `.kt` | `KotlinParser` | Project classpath |
| `*.gradle.kts` | `KotlinParser` | Project classpath + Gradle DSL |
| `.kts` (other) | `KotlinParser` | Project classpath |
| `.groovy` | `GroovyParser` | Project classpath |
| `.gradle` | `GroovyParser` | Project classpath + Gradle DSL |
| `.yaml` / `.yml` | `YamlParser` | — |
| `.json` | `JsonParser` | — |
| `pom.xml` | `MavenParser` | Fully resolved (parent POMs, property interpolation, BOM imports); adds `MavenResolutionResult` marker, enabling `rewrite-maven` recipes |
| `*.xml` (other) | `XmlParser` | — |
| `.properties` | `PropertiesParser` | — |
| `.toml` | `TomlParser` | — |
| `.hcl` / `.tf` / `.tfvars` | `HclParser` | — |
| `.proto` | `ProtoParser` | — |
| `.dockerfile` / `.containerfile` / `Dockerfile*` / `Containerfile*` | `DockerParser` | — (matched by extension **and** by filename prefix) |
| Plain text (mask-matched, e.g. `CODEOWNERS`, `*.md`, `*.sh`, `*.txt`) | `PlainTextParser` | — |

Plain-text masks are a fallback only. If a file is claimed by a specialized parser, that parser wins
even when the path also matches a plain-text mask; for example `Dockerfile*` stays with
`DockerParser`, and `*.qute.java` stays with `JavaParser`. Files larger than the 10 MB plain-text
threshold are skipped.

## Parse Failure Handling

`LstBuilder` wraps every parser invocation in a `parseAndRecord` helper. The build
does not abort on a per-file parse failure — the following are recorded into
`ExecutionDiagnostics.parseFailures` and execution continues with whatever was
successfully parsed:

- A `org.openrewrite.tree.ParseError` returned in a parser's output (the ParseError
  stub stays in the LST so callers can still inspect it).
- A file that the parser silently dropped from its output.
- A thrown `Exception` from `parser.parse(...)` — one entry is recorded for every
  file in the batch that threw. Fatal `Error`s (`OutOfMemoryError`,
  `StackOverflowError`, …) are deliberately **not** caught; they propagate so the
  run fails fast on an invalid JVM state.

The LST build also records `ExecutionDiagnostics.parsedFileCount`, counting only real
parsed source files and excluding `ParseError` stubs. This lets callers distinguish a
total parse wipeout from a clean no-op recipe run or an empty/excluded project.

Two paths preserve their existing fallback behaviour on top of recording:

- **Maven POMs** — only `MavenParser` throws whose cause chain contains a
  `URISyntaxException` fall back to `XmlParser`. Both attempts record their own
  `ParseFailure` if they fail. **Any other `MavenParser` exception is rethrown**
  and aborts the LST build by design: silently downgrading an unrelated
  `MavenParser` regression to `XmlParser` would hide the failure and produce
  misleading recipe results. This contract is locked in by
  `LstBuilderTest`'s `non-URI MavenParser exceptions still bubble up` test.
- **Gradle DSL** — `GradleParser` failures on `.gradle` / `*.gradle.kts` fall back to
  `GroovyParser` / `KotlinParser`; the `GradleParser` failure is recorded before the
  fallback runs, and the fallback parser's failures (if any) are also recorded.

See [`library-api.md`](library-api.md#parse-failures) for the consumer-facing shape
and the canonical `parser` names.

## Gradle DSL Classpath

Resolved from the Gradle installation:
`GRADLE_HOME` env var → project Gradle wrapper → `~/.gradle/wrapper/dists/` best-effort fallback.

Only JARs in `lib/` (not `lib/plugins/` or `lib/agents/`) are included — provides core Gradle API types needed to parse build scripts. Applied only to `.gradle` and `*.gradle.kts` files.

## Java / Kotlin Version Detection

`VersionDetector` attaches a `JavaVersion` marker to each source file using a **walk-up mechanism**: starts at the file's directory and walks up to project root, so subproject-specific config overrides root config.

**For `.java` files** — reads from nearest build file:
- Maven: `<source>`, `<target>`, `<release>` in `maven-compiler-plugin`
- Gradle: `jvmToolchain()`, `sourceCompatibility`

**For `.kt` / `.kts` files** — Kotlin-specific settings take priority, shared settings are fallback:
- Priority: `kotlin-maven-plugin <jvmTarget>`, `kotlinOptions.jvmTarget`, `JvmTarget.JVM_N`
- Fallback: `jvmToolchain`, `sourceCompatibility`, `maven-compiler-plugin`

## Module Structure

```
settings.gradle.kts
buildSrc/                          # Convention plugins (shared build config)
│   └── src/main/kotlin/
│       ├── kotlin-convention.gradle.kts      # Kotlin JVM 21, JaCoCo, ktlint tasks
│       ├── publishing-convention.gradle.kts  # maven-publish, signing, Dokka, POM
│       └── dokka-convention.gradle.kts       # Dokka HTML docs
core/                              # Library module (no embedded deps)
│   └── src/main/kotlin/.../
│       ├── RewriteRunner.kt       # Library facade / builder API
│       ├── RunResult.kt
│       ├── config/ToolConfig.kt
│       ├── lst/                   # LstBuilder (orchestrator) + FileCollector, VersionDetector, GradleDslClasspathResolver, MarkerFactory + 4 stage classes
│       ├── output/ResultFormatter.kt
│       └── recipe/                # RecipeArtifactResolver, RecipeLoader, RecipeRunner
cli/                               # Fat JAR module
│   └── src/main/kotlin/.../
│       ├── Main.kt
│       └── cli/RunCommand.kt
```
