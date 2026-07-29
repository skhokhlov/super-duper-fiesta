# Library API

## RewriteRunner

Programmatic entry point. Use `RewriteRunner.builder()` to configure, `.build().run()` to execute.

```kotlin
import java.time.Duration

val result = RewriteRunner.builder()
    .projectDir(Paths.get("/path/to/project"))
    .activeRecipe("org.openrewrite.java.format.AutoFormat")
    .recipeArtifact("org.openrewrite.recipe:rewrite-java:LATEST")
    .processTimeout(Duration.ofSeconds(120))
    .dryRun(true)
    .build()
    .run()
```

### Builder Methods

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `projectDir(Path)` | `Path` | `"."` | Project root — must exist when `run()` is called |
| `activeRecipe(String)` | `String` | **required** | Fully-qualified recipe name (e.g. `org.openrewrite.java.format.AutoFormat`) |
| `recipeArtifact(String)` | `String` | — | Add one Maven coordinate (`groupId:artifactId:version`); may be called multiple times; `LATEST` accepted as version |
| `recipeArtifacts(List<String>)` | `List` | `[]` | Replace all recipe coordinates at once |
| `rewriteConfig(Path)` | `Path?` | `<projectDir>/rewrite.yaml` | Path to a `rewrite.yaml` for custom composite recipes |
| `rewriteConfigContent(String)` | `String?` | — | Raw `rewrite.yaml` content as a string; takes precedence over `rewriteConfig` when both are set |
| `cacheDir(Path)` | `Path?` | from config / `~/.rewriterunner/cache` | Recipe JAR cache directory; stored under `<cacheDir>/repository`. Project deps always use `~/.m2/repository`. |
| `configFile(Path)` | `Path?` | `<projectDir>/rewriterunner.yml`, then `~/.rewriterunner/rewriterunner.yml` | `rewriterunner.yml` tool config (case-insensitive name match). Pass `null` to use auto-discovery. |
| `dryRun(Boolean)` | `Boolean` | `false` | Run without writing files to disk |
| `skipPluginRun(Boolean)` | `Boolean` | `false` | Bypass official Gradle/Maven plugin execution and use the selected LST executor directly |
| `processTimeout(Duration)` | `Duration?` | from config / `120s` | Timeout for build-tool subprocesses in the fallback LST pipeline |
| `pluginTimeout(Duration)` | `Duration?` | from config / `10m` | Timeout for official Gradle/Maven plugin invocations in Stage 0 |
| `resolverConnectTimeout(Duration)` | `Duration?` | from config / `30s` | TCP connection timeout for Maven Resolver artifact downloads |
| `resolverRequestTimeout(Duration)` | `Duration?` | from config / `60s` | Socket read/request timeout for Maven Resolver artifact downloads |
| `executionMode(ExecutionMode)` | `ExecutionMode` | `FORKED` | `FORKED` isolates post-plugin LST work; `IN_PROCESS` preserves rich `Result` values and permits custom writers. |
| `executorJvmArgs(List<String>)` | `List<String>` | YAML / `[]` | JVM arguments shared by runner-owned plugin and worker executors. Supplying any args disables automatic heap sizing. |
| `pluginExecutorJvmArgs(List<String>)` | `List<String>` | YAML / `[]` | JVM arguments appended for the official Gradle/Maven plugin executor. |
| `lstWorkerJvmArgs(List<String>)` | `List<String>` | YAML / `[]` | JVM arguments appended for the forked LST worker. |
| `lstWorkerTimeout(Duration?)` | `Duration?` | YAML / unlimited | Whole-worker timeout; inner build-tool timeouts remain separate. |
| `workerCommandFactory(WorkerCommandFactory)` | `WorkerCommandFactory` | default Java/classpath launch | Advanced structured launcher seam for nonstandard packaging. |
| `excludePaths(List<String>)` | `List` | `[]` | Glob patterns (relative to project root) to skip during parsing; overrides `parse.excludePaths` from config file. Forwarded both to Stage 0 (Maven `-Drewrite.exclusions=…` / Gradle `exclusion(...)` DSL) and to the LST fallback pipeline. |
| `plainTextMasks(List<String>)` | `List` | `[]` | Glob patterns (relative to project root) for otherwise-unhandled files to parse as plain text; overrides `parse.plainTextMasks` from config file. Both empty falls back to the upstream OpenRewrite default mask list. Forwarded both to Stage 0 (Maven `-Drewrite.plainTextMasks=…` / Gradle `plainTextMask(...)` DSL) and to the LST fallback pipeline. |
| `includeMavenCentral(Boolean)` | `Boolean?` | from config / `true` | Include Maven Central as a remote repository. Set `false` for air-gapped or enterprise environments. |
| `repository(RepositoryConfig)` | — | — | Add one extra Maven repository; accumulated, combined with config file repos |
| `repositories(List<RepositoryConfig>)` | `List` | `[]` | Replace all extra Maven repositories; combined with config file repos |

> **Production timeout guidance:** forked execution holds a JVM-wide, fair gate from the Stage 0
> plugin attempt through the LST worker. `lstWorkerTimeout` is unlimited by default, so a hung
> worker blocks every concurrent `RewriteRunner.run()` call in that JVM. Set a finite
> `lstWorkerTimeout(...)` in production. Stage 0 is governed separately by `pluginTimeout`
> (10 minutes by default), so its timeout must elapse before fallback work can begin.

### Throws
- `IllegalArgumentException` — the requested recipe could not be loaded in full. Raised when:
  - a resolved recipe classpath entry is missing, unreadable, or not a readable archive (e.g. a
    truncated JAR left in the cache) — the message names the offending path; delete it and re-run
    to fetch a fresh copy;
  - the recipe is not found in the loaded JARs or on the classpath;
  - a requested declarative recipe names a recipe in its `recipeList` that cannot be resolved
    (running only the entries that did resolve would report success for an incomplete migration).
- `IllegalStateException` — `activeRecipe` not set when `build()` is called

### Resource management

`RewriteRunner.run()` automatically closes the `URLClassLoader` created over recipe JARs
once the recipe has finished executing and all file writes are complete. No manual cleanup
is required by callers. Each call to `run()` creates and promptly releases its own
classloader, so calling `run()` multiple times on the same instance is safe and does not
accumulate file descriptors.

## RunResult

Return type of `RewriteRunner.run()`.

| Property | Type | Description |
|----------|------|-------------|
| `results` | `List<Result>` | Rich OpenRewrite results in explicit `IN_PROCESS` mode. Default forked runs intentionally return an empty list. |
| `changedFiles` | `List<Path>` | Files written to disk during this run. Empty when `dryRun = true` or no changes. |
| `projectDir` | `Path` | Resolved project directory (same as `Builder.projectDir`) |
| `rawDiffs` | `Map<Path, String>` | Unified diffs from Stage 0 and from the forked worker. This is the default programmatic result surface. |
| `hasChanges` | `Boolean` | `true` when the recipe produced at least one change, regardless of `dryRun` |
| `changeCount` | `Int` | Number of changed source files (`results.size + rawDiffs.size`) |
| `executionDiagnostics` | `ExecutionDiagnostics` | Which pipeline stage produced the run, how many JARs were on the classpath, parser failures, write outcome, and OpenRewrite's estimated time saved |

## ExecutionDiagnostics

Structured signal about which execution path produced the run. Useful for detecting blind runs (where all JVM type information is missing) and for metrics/telemetry.

```kotlin
data class ExecutionDiagnostics(
    val stageUsed: UsedExecutionStage?,
    val resolvedJarCount: Int,
    val parseFailures: List<ParseFailure> = emptyList(),
    val parsedFileCount: Int? = null,
    val estimatedTimeSaved: Duration? = null,
    val writeOutcome: WriteOutcome = WriteOutcome.EMPTY,
    val executorAttempts: List<ExecutorAttempt> = emptyList(),
)

data class ParseFailure(val path: String, val reason: String, val parser: String)
data class WriteOutcome(
    val successes: List<AppliedChange> = emptyList(),
    val failures: List<ApplyFailure> = emptyList(),
)
```

| Property | Description |
|----------|-------------|
| `stageUsed` | The stage that produced the classpath, or `null` when every LST stage produced an empty classpath (recipe ran semantically blind) |
| `resolvedJarCount` | Number of `.jar` entries on the LST classpath (project class directories excluded). `0` when `stageUsed` is `PLUGIN` or `null` |
| `parseFailures` | Per-file parse failures across every parser the LST pipeline ran (see [Parse failures](#parse-failures) below). Empty when every file parsed cleanly. |
| `parsedFileCount` | Count of successfully parsed source files in the in-process LST path, excluding `ParseError` stubs. `null` when the plugin path ran because no in-process LST was built. |
| `estimatedTimeSaved` | OpenRewrite's estimate of manual effort avoided by the run, summed across changed files. `null` means the value was not measured or could not be read; `Duration.ZERO` means the run completed and genuinely produced no estimated saving. |
| `writeOutcome` | Per-file disk apply outcome for LST results. Successes and failures include `ChangeKind` (`CREATED`, `MODIFIED`, `DELETED`) plus project-relative path; failures also include a cause. Dry-run, plugin-only, and no-change runs use `WriteOutcome.EMPTY`. |
| `executorAttempts` | Compact plugin/worker attempt records: logical executor, phase, PID when available, JVM-policy source, requested/observed heap, duration, outcome, exit code, and sanitized message. |

### Write outcomes

On the in-process LST path, rewrite-runner attempts every create, modify, and delete result and
collects failures instead of failing fast or silently swallowing them. `changedFiles` contains only
successfully applied non-delete paths; inspect `executionDiagnostics.writeOutcome` for deleted files
and any apply failures.

The CLI exits `1` when `writeOutcome.failed` is true and prints a concise stderr summary before
returning. Library callers should make the same check when partial disk application must fail their
own workflow.

### Detecting a blind run

```kotlin
val result = RewriteRunner.builder()...build().run()
if (result.executionDiagnostics.stageUsed == null) {
    error("Classpath resolution failed — recipe ran without type information")
}
```

### UsedExecutionStage values

| Value | Description |
|-------|-------------|
| `PLUGIN` | Stage 0 — official Gradle/Maven OpenRewrite plugin handled the recipe; classpath not observed |
| `BUILD_TOOL` | Stage 1 — project's own build tool extracted the compile classpath |
| `DEPENDENCY_RESOLUTION` | Stage 2 — `mvn dependency:tree` / `gradle dependencies` + Maven Resolver |
| `DIRECT_PARSE` | Stage 3 — static build-file parse + POM traversal via Maven Resolver |
| `LOCAL_REPOSITORY` | Stage 4 — local Maven/Gradle cache scan, no network |

### Parse failures

The LST pipeline never aborts on per-file parse failures. Instead, every signal of
trouble — from any parser the pipeline ran — is collected into
`executionDiagnostics.parseFailures` so callers can decide whether to log, ignore, or
fail their own build.

Use `parsedFileCount` with `parseFailures` and `results` to classify LST-path runs:

| Signal | Meaning |
|--------|---------|
| `parsedFileCount == 0` and `parseFailures.isNotEmpty()` | Total parse failure; the recipe ran over an empty LST |
| `parsedFileCount == 0` and `parseFailures.isEmpty()` | Nothing was in scope, usually because the project was empty or every file was excluded |
| `parsedFileCount > 0` and `results.isEmpty()` | The recipe ran and found nothing to change |
| `parsedFileCount > 0` and `results.isNotEmpty()` | The recipe made changes |

When `stageUsed == PLUGIN` and `parsedFileCount == null`, the run was plugin-only; use `hasChanges`
and `rawDiffs` instead. When `stageUsed == PLUGIN` and `parsedFileCount` is non-null, Stage 0
succeeded and rewrite-runner also ran the restricted Docker/HCL/protobuf specialized pass.

Three signals end up here:

1. **`org.openrewrite.tree.ParseError` SourceFiles** in the parser output — the parser
   produced a stub instead of a real LST node. The stub still appears in the LST so it
   can be inspected; the `ParseFailure` carries the message from the attached
   `ParseExceptionResult` marker.
2. **Silently dropped files** — the parser was given a file but returned nothing for
   it. The reason is the literal string `"silently dropped by <parser>"`.
3. **Thrown exceptions from `parser.parse(...)`** — caught and recorded one
   `ParseFailure` per file in the batch that threw. The exception does **not** abort
   the LST build. Fatal `Error`s (`OutOfMemoryError`, `StackOverflowError`, …) are
   **not** caught; they propagate so the run fails fast on an invalid JVM state.

The Maven POM path is special:

- When `MavenParser` throws an `IllegalArgumentException` whose cause chain contains
  a `URISyntaxException` (the `MavenPomDownloader` URI-failure mode), the pom is
  retried individually and any pom that still fails falls back to `XmlParser`. The
  `MavenParser` failure is recorded, and any failure of the `XmlParser` fallback is
  recorded too — so a single pom can produce two entries with the same `path` and
  different `parser` values.
- Any other `MavenParser` throw — i.e. a non-URI exception — is **rethrown** and
  aborts the LST build. This is deliberate: it keeps unrelated `MavenParser`
  regressions visible instead of silently downgrading them to `XmlParser`. Recipe
  results would otherwise be misleading. If you need to keep going past such a
  failure, the caller is responsible for catching and recovering.

```kotlin
val result = RewriteRunner.builder()...build().run()

result.executionDiagnostics.parseFailures.forEach { failure ->
    println("${failure.parser} could not handle ${failure.path}: ${failure.reason}")
}

// Group by parser to spot systematic failures
val byParser = result.executionDiagnostics.parseFailures.groupBy { it.parser }
byParser.forEach { (parser, failures) ->
    println("$parser failed on ${failures.size} file(s)")
}
```

Canonical `parser` values today: `JavaParser`, `KotlinParser`, `GroovyParser`,
`GradleParser`, `YamlParser`, `JsonParser`, `MavenParser`, `XmlParser`,
`PropertiesParser`, `TomlParser`, `HclParser`, `ProtoParser`, `DockerParser`,
`PlainTextParser`.

`ParseFailure` entries with `parser = "DependencyResolutionStage"` or
`parser = "BuildFileParseStage"` mark malformed Maven coordinate strings that were
skipped during classpath resolution (e.g. a coord with an illegal URI character
parsed out of `mvn dependency:tree` or a `build.gradle` file). The corresponding
`path` field carries the rejected coordinate string itself, not a file path —
classpath resolution does not point at a single source file.

Recipe artifact resolution skips malformed coordinates rather than aborting the
run: `RewriteRunner.builder().recipeArtifacts(...)` filters each entry up-front,
logs the offender at WARN, and resolves the remaining well-formed coordinates.
A single bad `--recipe-artifact` (or programmatic entry) therefore cannot fail
the whole execution. If every coordinate was malformed, the recipe classpath ends
up empty and any subsequent failure (e.g. "recipe not found") surfaces as the
usual `IllegalArgumentException` from `run()`.

In `--output report` mode the same data is serialized as top-level
`parsedFileCount` and `parseFailures` fields in `openrewrite-report.json` (see
[README](../README.md#output-modes) for the JSON schema). `estimatedTimeSaved`
is intentionally not part of the report JSON surface; read it from
`RunResult.executionDiagnostics` in library code.

Each `org.openrewrite.Result` in `results` exposes:
- `before` — the source file before the recipe (`null` for newly created files)
- `after` — the source file after the recipe (`null` for deleted files)
- `diff()` — a precomputed unified diff string
- `before?.sourcePath` / `after?.sourcePath` — relative path within the project

```kotlin
result.results.forEach { r ->
    println("=== ${r.after?.sourcePath ?: r.before?.sourcePath} ===")
    if (r.before == null) println("(new file)")
    if (r.after == null) println("(deleted)")
    println(r.diff())
}
```

## ResultFormatter and OutputMode

`ResultFormatter` formats a `RunResult` in the same three modes available in the CLI. Library consumers that only need to inspect results programmatically can skip this class and work with `RunResult.results` directly.

```kotlin
import io.github.skhokhlov.rewriterunner.output.OutputMode
import io.github.skhokhlov.rewriterunner.output.ResultFormatter

val result = runner.run()

// Print unified diffs to stdout
ResultFormatter(OutputMode.DIFF).format(result)

// Print one changed-file path per line to stdout
ResultFormatter(OutputMode.FILES).format(result)

// Write openrewrite-report.json to the project directory
ResultFormatter(OutputMode.REPORT).format(result)
```

### OutputMode

| Value | Behaviour |
|-------|-----------|
| `DIFF` | Prints a unified diff for each changed file to stdout |
| `FILES` | Prints one changed-file path per line to stdout |
| `REPORT` | Writes `openrewrite-report.json` to the directory passed as `reportDir` (defaults to `.`) |

### Constructors

```kotlin
// Primary constructor — writes to System.out
ResultFormatter(outputMode: OutputMode, out: PrintStream = System.out)

// Secondary constructor — accepts a PrintWriter (used by the CLI's picocli @Spec writer)
ResultFormatter(outputMode: OutputMode, writer: PrintWriter)
```

### format()

```kotlin
fun format(results: List<Result>, reportDir: Path? = null)
fun format(runResult: RunResult)
```

- `results` — the list from `RunResult.results`. May be empty; prints `"No changes produced."` / `"No files changed."` for `DIFF` / `FILES` modes.
- `reportDir` — directory where `openrewrite-report.json` is written. Ignored for `DIFF` and `FILES` modes. Defaults to the current directory (`.`).

Use `format(runResult)` when you want formatted output that supports Stage 0 plugin results stored
in `RunResult.rawDiffs`, in-process `RunResult.results`, or both in the same run.

## ToolConfig YAML

Config file (`rewriterunner.yml`) loaded via `--config` CLI flag or `configFile()` builder method. File name matching is case-insensitive. Auto-discovered from `<projectDir>/rewriterunner.yml` (project-level) then `~/.rewriterunner/rewriterunner.yml` (global fallback) when not explicitly provided. Supports `${ENV_VAR}` interpolation and `~` expansion in all string fields. Duration fields require units such as `30000ms`, `120s`, `10m`, `2h`, or ISO-8601 values like `PT2M`.

```yaml
cacheDir: ~/.rewriterunner/cache   # default

repositories:
  - url: https://nexus.example.com/repository/maven-public
    username: ${NEXUS_USER}         # env var interpolated at load time
    password: ${NEXUS_PASS}

parse:
  excludePaths:
    - "**/generated/**"
    - "**/*.md"
  # CLI --exclude-paths overrides this list when non-empty.
  plainTextMasks:
    - "**/CODEOWNERS"
    - "**/*.txt"
  # CLI --plain-text-masks replaces this list when non-empty.

processTimeout: 120s
pluginTimeout: 10m
rewriteGradlePluginVersion: 7.32.1
rewriteMavenPluginVersion: 6.40.0
resolverConnectTimeout: 30s
resolverRequestTimeout: 60s
execution:
  mode: forked
  executorJvmArgs:
    - "-Xmx4g"
  plugin:
    jvmArgs:
      - "-XX:MaxMetaspaceSize=1g"
  lstWorker:
    jvmArgs:
      - "-XX:+HeapDumpOnOutOfMemoryError"
    timeout: 30m
# CLI list options replace the corresponding YAML list when supplied.
```

### ToolConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `cacheDir` | `String` | `~/.rewriterunner/cache` | Recipe JAR cache root; `~` and env vars expanded. Recipes are stored under `<cacheDir>/repository`. Project dependencies always resolve from `~/.m2/repository`. |
| `repositories` | `List<RepositoryConfig>` | `[]` | Extra Maven repos for resolution |
| `parse` | `ParseConfig` | defaults | File exclusion and plain-text mask config |
| `includeMavenCentral` | `Boolean` | `true` | Include Maven Central as a remote repository. Set `false` to restrict to only the repositories listed in `repositories`. |
| `processTimeout` | `Duration` | `120s` | Timeout for Stage 1/2 build-tool subprocesses, compile attempts, and build-tool metadata commands. |
| `pluginTimeout` | `Duration` | `10m` | Timeout for Stage 0 official OpenRewrite plugin invocations. |
| `rewriteGradlePluginVersion` | `String` | `7.32.1` | Version of `org.openrewrite:plugin` used for Stage 0 Gradle plugin execution. |
| `rewriteMavenPluginVersion` | `String` | `6.40.0` | Version of `org.openrewrite.maven:rewrite-maven-plugin` used for Stage 0 Maven plugin execution. |
| `resolverConnectTimeout` | `Duration` | `30s` | TCP connection timeout for Maven Resolver artifact downloads. |
| `resolverRequestTimeout` | `Duration` | `60s` | Socket read/request timeout for Maven Resolver artifact downloads. |
| `execution.mode` | `forked` / `in-process` | `forked` | Default forked mode isolates LST memory. In-process mode is for rich results and custom writers. |
| `execution.executorJvmArgs` | `List<String>` | `[]` | Shared runner-owned executor arguments. |
| `execution.plugin.jvmArgs` | `List<String>` | `[]` | Arguments appended for the official plugin executor. |
| `execution.lstWorker.jvmArgs` | `List<String>` | `[]` | Arguments appended for the LST worker. |
| `execution.lstWorker.timeout` | `Duration?` | unlimited | Optional whole-worker timeout. |

### Migration from pluginJvmArgs

`pluginJvmArgs`, `Builder.pluginJvmArgs(...)`, and `--plugin-jvm-args` were intentionally removed.
Move shared arguments to `execution.executorJvmArgs`, plugin-only arguments to
`execution.plugin.jvmArgs`, and worker-only arguments to `execution.lstWorker.jvmArgs`. A YAML file
using the removed field fails with a message naming `execution.plugin.jvmArgs`; the removed CLI
option reports `--plugin-jvm-arg`.

### RepositoryConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | `""` | Full repository URL |
| `username` | `String?` | `null` | HTTP basic-auth username |
| `password` | `String?` | `null` | HTTP basic-auth password |

To provide extra repositories programmatically (without a `rewriterunner.yml` file):

```kotlin
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig

val runner = RewriteRunner.builder()
    .projectDir(Paths.get("/path/to/project"))
    .activeRecipe("org.openrewrite.java.format.AutoFormat")
    .recipeArtifact("org.openrewrite.recipe:rewrite-static-analysis:LATEST")
    .repository(RepositoryConfig(
        url = "https://nexus.example.com/repository/maven-public",
        username = System.getenv("NEXUS_USER"),
        password = System.getenv("NEXUS_PASS")
    ))
    .includeMavenCentral(false)   // use only the Nexus repository above
    .build()
```

### ParseConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `excludePaths` | `List<String>` | `[]` | Glob patterns (relative to project root) to skip |
| `plainTextMasks` | `List<String>` | upstream defaults | Glob patterns (relative to project root) for otherwise-unhandled files to parse with `PlainTextParser` |

**Precedence**: CLI flag `--exclude-paths` (or `Builder.excludePaths(...)`) overrides `parse.excludePaths` from the config file when non-empty. CLI flag `--plain-text-masks` (or `Builder.plainTextMasks(...)`) overrides `parse.plainTextMasks` from the config file when non-empty; if both are empty, rewrite-runner uses the upstream OpenRewrite default plain-text mask list. Both resolved lists are forwarded to the Stage 0 plugin invocation and to the LST fallback pipeline, so both code paths apply identical filtering. Stage 0 also receives rewrite-runner's Docker/HCL/protobuf ownership exclusions unconditionally. Exclusions win over plain-text masks.

Plain-text masks are a fallback allowlist, not a catch-all for every unhandled file. In the LST path, specialized parsers take precedence; for example `Dockerfile*` routes to `DockerParser` and `*.qute.java` routes to `JavaParser` even though both are in the default plain-text mask list. Future work may add a broader opt-in for parsing every unmatched text file.

### Automatically excluded directories

The following directories are **always** skipped during the file-system walk, regardless of `excludePaths` configuration:

`.git`, `build`, `target`, `node_modules`, `.gradle`, `.idea`, `out`, `dist`

Use `parse.excludePaths` (or `excludePaths()` in the builder) to skip additional directories or path patterns.

## Exit Codes (CLI)

| Code | Meaning |
|------|---------|
| `0` | Success (recipe ran; changes may or may not exist) |
| `1` | Error (invalid args, unknown recipe, unknown output mode, unhandled exception, or one or more LST results could not be applied to disk) |

## KotlinDoc Coverage

KDoc is present on all public API classes and methods:
`RewriteRunner`, `RunResult`, `RecipeArtifactResolver`, `RecipeLoader`, `RecipeRunner`, `LstBuilder`, `ToolConfig`, `ParseConfig`, `RepositoryConfig`, `ResultFormatter`, `OutputMode`.
