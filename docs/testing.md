# Testing

## TDD Requirement

**Test-Driven Development is required.** For every feature or bug fix:
1. Write a failing test that covers the desired behavior
2. Implement the minimum code to make it pass
3. Refactor if needed, keeping tests green

This applies at all layers: unit tests in `core/`, integration tests in `cli/`.

## Test Conventions

- **JUnit 5** with `@TempDir` for temporary directories
- **`kotlin.test` assertions**: `assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotEquals`
- **No mocks** — subclass `ClasspathStage` implementations and override `resolve(projectDir, parseFailures)` to drive classpath behavior in tests
- Critical orchestration tests assert the exact executor and outcome; they never treat a plugin success and a fallback success as equivalent.

## Integration Tests

`BaseIntegrationTest.runCli()` is useful for CLI parsing and formatting, but it is not proof of
forked execution. Worker acceptance tests launch a real child JVM and assert its PID, handshake, and
observed maximum heap.

```kotlin
class MyIntegrationTest : BaseIntegrationTest() {
    @Test
    fun `some behavior`(@TempDir projectDir: Path) {
        // set up project files in projectDir
        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "org.openrewrite.java.format.AutoFormat",
            "--dry-run"
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("..."))
    }
}
```

## Unit Test Patterns

### Creating OpenRewrite Result objects

Calling `PlainText.withText()` outside a visitor context throws `UnknownSourceFileChangeException`.
**Always** create `Result` objects by running through a real recipe pipeline:

```kotlin
// Use PlainTextVisitor inside Recipe.run() — do NOT call withText() directly
val results = Recipe.run(InMemoryLargeSourceSet(listOf(sourceFile)), executionContext)
// InMemoryLargeSourceSet is in org.openrewrite.internal (not top-level)
```

### Overriding LST stages in tests

```kotlin
class MyProjectBuildStage : ProjectBuildStage(NoOpRunnerLogger) {
    override fun resolve(
        projectDir: Path,
        parseFailures: MutableList<ParseFailure>
    ): ClasspathResolutionResult {
        return ClasspathResolutionResult(listOf(Path.of("/tmp/fake.jar")))
    }
}
```

### Capturing log output in tests

```kotlin
val logger = LoggerFactory.getLogger(MyClass::class.java) as ch.qos.logback.classic.Logger
val appender = ch.qos.logback.core.read.ListAppender<ILoggingEvent>()
appender.start()
logger.addAppender(appender)
try {
    // ... exercise code
    assertTrue(appender.list.any { it.message.contains("expected log") })
} finally {
    logger.detachAppender(appender)
}
```

## Test File Locations

```
core/src/test/kotlin/.../
├── config/ToolConfigTest.kt
├── lst/
│   ├── LstBuilderTest.kt           ← parser routing, 4-stage pipeline, compile-on-demand, Gradle DSL classpath
│   ├── ProjectBuildStageTest.kt
│   ├── ProjectBuildStageBranchTest.kt
│   ├── DependencyResolutionStageTest.kt
│   ├── DependencyResolutionStageResolveClasspathTest.kt
│   ├── BuildFileParseStageTest.kt
│   ├── LocalRepositoryStageTest.kt
│   ├── GatherDeclaredCoordinatesTest.kt
│   ├── GradleVersionParsingTest.kt
│   ├── JavaVersionDetectionTest.kt
│   ├── JavaVersionParsingTest.kt
│   ├── KotlinVersionDetectionTest.kt
│   ├── MultiModuleJavaVersionTest.kt
│   └── utils/
│       └── FileCollectorTest.kt    ← extension filtering, directory exclusion, glob patterns
└── output/ResultFormatterTest.kt

cli/src/test/kotlin/.../
├── cli/RunCommandTest.kt
└── integration/
    ├── BaseIntegrationTest.kt
    ├── JavaProjectIntegrationTest.kt
    ├── KotlinProjectIntegrationTest.kt
    ├── YamlProjectIntegrationTest.kt
    ├── JsonProjectIntegrationTest.kt
    ├── XmlProjectIntegrationTest.kt
    ├── PropertiesProjectIntegrationTest.kt
    └── MultiLanguageProjectIntegrationTest.kt
```

## Test Lanes

Tests are split into four lanes by Gradle `Test.filter` class-name pattern. The offline lanes run
under root `check`; live-plugin and container evidence use explicit CI jobs.

| Lane | Gradle task | Scope | When |
|------|-------------|-------|------|
| Unit + forked acceptance | `:core:test`, `:cli:test` | Configuration, protocol, diagnostics, and real core worker JVM tests; excludes `*IntegrationTest` | Root `check` |
| Integration (offline) | `:cli:testIntegration` | Ordinary `*IntegrationTest` classes: fake wrappers, per-language coverage, and real nested-Gradle fallback attribution; no network or toolchain download | Root `check` |
| Integration (real plugins) | `:cli:testRealPlugin` | `PluginRealExecutionIntegrationTest` only; downloads Maven/Gradle distributions and pulls live plugin artifacts from Maven Central | `plugin-real` CI job |
| Container acceptance | `:cli:testContainer` | Built CLI fat JAR running under a real Docker `--memory=2g --memory-swap=2g` cgroup | `container` CI job |

Root `check` includes the offline integration lane. `productionCheck` additionally runs
the real-plugin and container lanes and builds the release fat JAR. Both external-environment lanes
are authoritative once selected: missing network, toolchain, Docker, or image prerequisites fail the
task rather than producing a green skip. Tag publication runs `productionCheck` before publication.

Class-name conventions:
- Every integration test class name ends with `IntegrationTest` (enforced by `failOnNoMatchingTests = true` on all dedicated integration tasks).
- Add a new offline integration test by simply creating an `*IntegrationTest` class under `cli/src/test/kotlin/.../integration/`.

## Forked worker tests

The core suite exercises the worker through public `RewriteRunner` behavior: default forked result
shape, a distinct child PID, worker-observed explicit `-Xmx`, disk application, and rejection of a
custom change writer in forked mode. Unit tests also cover automatic heap boundaries and configuration
precedence. Worker failures must not retry the same work in-process.

## LST fallback type-attribution tests

`FallbackTypeAttributionIntegrationTest` proves that the Stage 1 and Stage 2 classpaths affect
type-sensitive recipe behavior, not just diagnostics. It compiles `com.acme.External` into a real
JAR, publishes it to a temporary Maven-layout file repository, and runs `ChangeType` through the
public `RewriteRunner` path. A direct negative control first proves the recipe produces no result
without that JAR.

Both recovery scenarios attempt Stage 0 with a deliberately unavailable Gradle plugin from the
file repository. The fixture reuses the Gradle distribution already running the test task, forces
all nested invocations offline, and gives the project, rewrite-runner cache, Gradle user home,
runner user home, and Maven repository temporary directories. Stage 2 adds an unresolved
`runtimeOnly` dependency and records real wrapper exit codes to prove Stage 1 failed while the
`dependencies` task remained usable. Both scenarios assert the winning stage and the exact
`External` to `Replacement` source changes.

## Stage 0 Plugin Tests

Stage 0 (plugin-first execution) is covered by two complementary test tiers:

| Tier | Class | How | Lane |
|------|-------|-----|------|
| Fake-wrapper (fast) | `PluginFirstIntegrationTest` | Shell scripts simulate plugin output | `:cli:testIntegration` |
| Real-wrapper (slow) | `PluginRealExecutionIntegrationTest` | Real Maven/Gradle distributions downloaded by `ToolchainCache` | `:cli:testRealPlugin` |

The fake-wrapper tier gives fast feedback on orchestration logic and CLI flag wiring. The real-wrapper tier verifies that the pinned plugin versions actually exist on Maven Central, that the generated Gradle init-script DSL is accepted by the live plugin (including Gradle 9 compatibility, exercised via the version pulled from the project's `gradle-wrapper.properties`), that `PatchParser` can parse the real plugin's output for both single-project and multi-module builds, and that Stage 0 still exposes a positive `ExecutionDiagnostics.estimatedTimeSaved` on real non-dry-run changes.

**License envelope** — the real-wrapper suite only loads recipes from `rewrite-core` (Apache 2.0). The `org.openrewrite.recipe.*` artifacts (`rewrite-static-analysis`, `rewrite-spring`, `rewrite-migrate-java`, …) ship under the Moderne Source Available license, which is incompatible with this project; do not add a scenario that pulls one in via `recipeArtifacts`. The `--recipe-artifact` coordinate-resolution code path is covered by `RecipeArtifactResolver` unit tests against permissive coordinates.

**Stage 0 proof** — the real-wrapper suite calls `RewriteRunner` directly (not the CLI) and asserts that `RunResult.executionDiagnostics.stageUsed == UsedExecutionStage.PLUGIN`. Non-dry-run real plugin scenarios also assert positive `estimatedTimeSaved`. Without these assertions, a Stage 0 regression that silently fell through to the LST pipeline or lost the plugin-reported estimate could still satisfy file-content checks and stay green.

**Actual plugin JVM proof** — a test-only recipe is packaged into a temporary local Maven artifact
and loaded by both official plugins. It records its executing PID, `Runtime.maxMemory()`, and JVM
input arguments. The suite asserts that the PID is not the coordinator and that each plugin really
observed the configured `-Xmx`, rather than merely checking the rendered Gradle/Maven command.

**Stage 0 self-verification** — `DirectPluginExecutorTest` drives `DirectPluginExecutor` with a fake
`execute` lambda that emits upstream's unresolved-recipe markers and returns exit 0, asserting
`PluginRunResult.Failed`. The load-bearing case produces patch files *and* a marker, because a
missing sub-recipe coexists with real diffs; a `diffs.isEmpty()` guard would pass a broken
implementation. `PluginFirstIntegrationTest` covers the same defect end to end through a fake
`gradlew` and asserts the run reaches the LST stage. The marker literals are pinned to the plugin
versions in `gradle/libs.versions.toml` — re-verify them when bumping either plugin.

**Scenario shape** — both tiers consume `PluginScenario` objects from `PluginScenarios.kt`. Each scenario defines the project layout, recipe, and expected outcomes so a layout change is a one-place edit.

**Task partitioning** — all three test tasks share one source set; Gradle `Test.filter` selects which class runs in each lane (see the Test Lanes table above). There is no Kotest tag involved.

**Running locally:**

```bash
# Unit only (fast):
./gradlew :cli:test

# Full offline verification (unit + offline integration):
./gradlew check :cli:testIntegration

# Real-plugin lane (downloads toolchains + plugins on first run; ~5 min warm):
./gradlew :cli:testRealPlugin

# Release fat JAR under a real 2 GiB Docker cgroup (Docker required):
./gradlew :cli:testContainer

# Single scenario smoke:
./gradlew :cli:testRealPlugin --tests "*maven multi-module*"
```

**Platform boundary for the real-wrapper suite:** Windows is not selected because its fixture
uses POSIX wrapper shims. On supported platforms, an unreachable Maven Central is a failure: once
`:cli:testRealPlugin` is selected it never turns an unavailable prerequisite into a green skip.

## Container acceptance

`ContainerForkedDistributionIntegrationTest` runs the built `-all` JAR in
`eclipse-temurin:21-jre` with `--memory=2g --memory-swap=2g`. It checks the worker's actual
handshake heap against the documented automatic 1433 MiB policy, then verifies that an explicit
`--executor-jvm-arg=-Xmx768m` wins. The test invokes Docker directly and uses `--rm`, so it fails
on unavailable Docker/image prerequisites and does not retain the container after either run.

**Toolchain cache** — Maven and Gradle distributions are cached under `cli/build/test-cache/toolchains/` (`./gradlew clean` clears them). The Gradle distribution version tracks the project's own `gradle/wrapper/gradle-wrapper.properties` automatically — `cli/build.gradle.kts` reads it at configuration time and forwards it to the test JVM as `-Drewriterunner.test.gradleVersion=<v>`. Maven is pinned in `ToolchainCache.kt` (`MAVEN_VERSION`) and is the only manual bump knob.

## Internal API Access for Tests

- `DependencyResolutionStage.parseMavenDependencies` and `parseGradleDependencies` are `internal` — accessible from test code in the same module
- `ProjectBuildStage`, `DependencyResolutionStage`, and `BuildFileParseStage` are `open` `ClasspathStage` implementations — subclass instead of mocking
- `VersionDetector.parseGradleVersionFromWrapper` and `LstBuilder.parseGradleVersionFromWrapper` are `internal` — the `LstBuilder` method is a thin delegation to `VersionDetector`; `GradleVersionParsingTest` calls it via `LstBuilder` for backward compatibility
- `LstBuilder.resolveGradleDslClasspath` is `internal` — thin delegation to `GradleDslClasspathResolver`; tested via `LstBuilderTest`
- `FileCollector` is `internal` — tested directly in `FileCollectorTest`
