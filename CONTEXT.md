# Context

## Glossary

- **Build unit**: A directory where rewrite-runner invokes Maven or Gradle for classpath resolution,
  paired with the build tool used there. Build units are non-exclusive: a root with both Maven and
  Gradle descriptors can produce one unit for each tool.
- **Build-tool identity**: The exclusive, root-level build-tool verdict used only for provenance
  markers. `detectBuildTool` returns Gradle, Maven, or None; Gradle wins with a warning when both
  Maven and Gradle root descriptors exist.
- **Apply**: The step that takes OpenRewrite `Result` objects and attempts to materialize them on
  the configured target, recording per-file successes and failures rather than treating recipe
  execution alone as proof that disk changes landed.
- **Change writer**: The seam responsible for applying recipe results to a target. Production uses
  the disk-backed writer. Custom/in-memory writers are explicit **in-process** only because rich
  `Result` graphs do not cross the worker boundary.
- **Classpath stage**: A `ClasspathStage` implementation in the ordered LST classpath-resolution
  chain. `resolve(projectDir, parseFailures)` returns a `ClasspathResolutionResult` to win or
  `null` to fall through to the next stage.
- **Stage 0 / Plugin-first execution**: The first sequence in a coordinated run, in which
  rewrite-runner shells out to the official OpenRewrite Gradle/Maven plugin. A successful Stage 0
  may be followed by one specialized worker pass. _Avoid_: "plugin mode", "external run".
- **LST worker**: A fork-per-run JVM that owns recipe resolution, LST construction, recipe execution,
  collision filtering, and LST-generated disk application. It returns transportable diffs and
  diagnostics, never LST graphs. See [[0010-fork-lst-worker-jvm]].
- **LST fallback**: The four-stage engine (`LstBuilder` + classpath stages) run by the LST worker by
  default when Stage 0 is skipped, fails, or is partial. It can run in-process only as an explicit
  compatibility mode.
- **Orphan unit**: A build unit in a [[#root-less-monorepo]] subdirectory that Stage 0 runs the
  plugin in when the root has no descriptor. Each orphan unit's diffs are rebased to the
  repository root.
- **Hybrid run**: A single run in which Stage 0 produced diffs for some orphan units while the
  LST fallback handled the remainder of the project. Stage 0 wins on any per-path collision, and
  the run still reports itself as Stage 0. _Avoid_: "partial run". See [[0006-plugin-first-execution]].
- **Estimated time saved**: A heuristic estimate of manual developer effort avoided by a recipe run,
  not measured wall-clock. It is the sum, over every changed file, of the effort OpenRewrite attributes
  to the change — for each file the total `getEstimatedEffortPerOccurrence()` (default 5 minutes) of the
  recipes that made it. This is OpenRewrite's own figure (`org.openrewrite.Result.getTimeSavings()`); it is
  never recomputed by rewrite-runner. Both execution paths surface the same number: the LST path sums it
  from in-process `Result` objects, and the Stage 0 plugin path reads the plugin-reported value from an
  exported `SourcesFileResults` data table when present, otherwise from the plugin's `Estimate time saved`
  output line.
- **Exclusion path**: A glob pattern from `--exclude-paths` / `Builder.excludePaths(...)` /
  `parse.excludePaths` selecting files to skip. In multi-module Stage 0 runs, upstream build
  plugins do the final matching; the real-plugin regression suite pins Gradle and Maven Java
  source exclusions as repository-root-relative for rewrite-runner's invocation shape. Prefer
  `**/`-anchored globs when a pattern must reach subprojects in both tools. See
  [[0007-exclusion-only-path-filtering]].
- **Plain-text mask**: A glob pattern (relative to project root) selecting files to parse with
  `PlainTextParser`. The configured list replaces the built-in default. Forwarded to both Stage 0
  and the LST fallback so both paths select the same files. Specialized parsers take precedence: a
  file a real parser claims, such as `Dockerfile*` for `DockerParser`, is never treated as plain
  text on the LST path. See also **Specialized ownership**.
- **Recipe artifact resolution**: Fetching the coordinates named by `--recipe-artifact` so their JARs
  exist locally. Who performs it depends on the stage and they do not share a cache: on the LST path
  rewrite-runner resolves them itself into its own recipe cache, while on
  [[#stage-0-plugin-first-execution]] the target project's own build tool resolves them from whatever
  repositories that build can see. _Avoid_: using "recipe discovery" for this step.
- **Recipe discovery**: Finding a named recipe among JARs that are already present, by scanning them
  for recipe classes and `META-INF/rewrite/*.yml` definitions. Distinct from **recipe artifact
  resolution**: a recipe is undiscoverable both when its artifact was never fetched and when the
  fetched artifact is unreadable, and the two are reported identically as "recipe not found".
- **Terminal execution failure**: A failure that ends a run after execution has begun. Always
  reported by returning a result that carries the failure, the record of every stage that ran, and
  any changes that legitimately landed before it — never by throwing. A run can fail and still have
  changed files, and the result must be able to say both. Contrast **precondition error**. See
  [[0012-uniform-failure-reporting]].
- **Precondition error**: An invalid request detected before any execution begins, such as a project
  directory that does not exist. Always thrown, never reported as a run outcome, because no run
  occurred.
- **Unresolved recipe**: A recipe a run needs but the executing environment cannot find — either the
  recipe the user requested, or one named inside a requested [[#declarative-recipe]]'s recipe list.
  Any unresolved recipe makes the run a failure; a run must never quietly execute the subset it
  happened to find. Because each stage resolves from its own sources, a recipe unresolved in one
  stage may be resolvable in another, so an unresolved recipe is grounds for falling through to a
  later stage rather than for abandoning the run. See [[0011-unresolved-recipes-are-failures]].
- **Declarative recipe**: A recipe defined in YAML rather than as a class, whose `recipeList` may name
  recipes belonging to other artifacts. Discovering one requires its own artifact; initializing one
  requires every artifact its list names, and a missing entry degrades the recipe silently instead of
  failing discovery.
- **Root descriptor**: A build descriptor at the project root: `pom.xml` for Maven, or
  `settings.gradle(.kts)` / `build.gradle(.kts)` for Gradle.
- **Root-less monorepo**: A repository whose project root has no build descriptor for a tool, but one
  or more subdirectories do.
- **Specialized ownership**: The Docker/HCL/protobuf file types rewrite-runner parses with its own
  classpath-free specialized parsers, partitioned out of Stage 0 by exclusion. A file type belongs
  here only when Stage 0 structurally cannot handle it and it is safe to parse in isolation.
- **Top-most discovery**: Build-unit discovery rule that selects the first descriptor directory found
  on a path and does not descend into that directory's children.
- **Write outcome**: The per-file result of the apply step: successful created/modified/deleted
  changes plus failures with their path, kind, and cause.
- **Coordinator JVM**: The JVM running `RewriteRunner` and `RunCoordinator`. It owns configuration,
  stage selection, process lifecycle, and aggregation. Its heap is caller-managed and does not host
  LST work in default forked mode.
- **Stage 0 subprocess JVM**: The `gradlew`/`mvnw` JVM where OpenRewrite runs during
  [[#stage-0-plugin-first-execution]]. Its heap is governed by **plugin JVM args**, never by the
  runner JVM's `-Xmx`.
- **Executor JVM arguments**: Shared `execution.executorJvmArgs` plus stage-specific
  `execution.plugin.jvmArgs` or `execution.lstWorker.jvmArgs`, exposed by the matching builder and
  flat repeatable CLI methods. Gradle receives nonempty arguments as complete
  `org.gradle.jvmargs`; Maven appends them to `MAVEN_OPTS`.
- **Automatic heap policy**: When runner arguments, inherited JDK options, and explicit project JVM
  settings are absent, rewrite-runner adds a conservative cgroup-aware `-Xmx` to runner-owned
  executors. This is a heap ceiling, not aggregate RSS enforcement.
