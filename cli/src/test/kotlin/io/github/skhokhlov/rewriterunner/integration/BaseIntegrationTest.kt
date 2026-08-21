package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.cli.RunCommand
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.writeText
import picocli.CommandLine

val isWindows: Boolean = System.getProperty("os.name", "").lowercase().contains("windows")

// Kotlin raw strings interpolate $identifier; embed a literal `$` for shell vars via `${D}`.
const val D = "$"

val posixExecutable: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    )

/**
 * Shared helpers for CLI integration tests.
 *
 * [runCli] captures stdout/stderr and returns the exit code, mirroring how the
 * OpenRewrite test framework separates "before" input from "after" assertions —
 * but at the CLI boundary instead of the recipe API boundary.
 *
 * [Path.writeFindAndReplaceRecipe] and [Path.writeRewriteYaml] write composite
 * recipe YAML files into a project directory for use in tests.
 */

data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

fun runCli(vararg args: String): CliResult {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exitCode =
        CommandLine(RunCommand())
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
            .execute(*args)
    return CliResult(exitCode, out.toString(), err.toString())
}

/**
 * Writes a `FindAndReplace` composite recipe to `rewrite.yaml` in this directory.
 *
 * Uses a self-contained `trimIndent()` block so indentation is always correct
 * regardless of the surrounding call-site indentation level.
 */
fun Path.writeFindAndReplaceRecipe(
    name: String = "com.example.integration.FindAndReplace",
    find: String,
    replace: String
) {
    resolve("rewrite.yaml").writeText(
        """
        ---
        type: specs.openrewrite.org/v1beta/recipe
        name: $name
        recipeList:
          - org.openrewrite.text.FindAndReplace:
              find: "$find"
              replace: "$replace"
        """.trimIndent()
    )
}

/**
 * Writes a composite recipe to `rewrite.yaml` with arbitrary [body] content.
 *
 * [body] must be a YAML block sequence whose items start with `- ` at column 0
 * (call `.trimIndent()` on multi-line bodies before passing). The helper adds
 * 2 spaces of indentation so items align correctly under `recipeList:`.
 *
 * Example:
 * ```kotlin
 * projectDir.writeRewriteYaml("com.example.MyRecipe", """
 *     - org.openrewrite.properties.ChangePropertyValue:
 *         propertyKey: server.port
 *         newValue: "9090"
 * """.trimIndent())
 * ```
 */
fun Path.writeRewriteYaml(name: String, body: String) {
    val indented = body.trimIndent().lines().joinToString("\n") { "  $it" }
    resolve("rewrite.yaml").writeText(
        "---\ntype: specs.openrewrite.org/v1beta/recipe\nname: $name\nrecipeList:\n$indented\n"
    )
}

/**
 * Writes a fake `gradlew` that simulates the OpenRewrite Gradle plugin for a single-line change.
 *
 * On `rewriteDryRun` → writes a unified-diff patch at `build/reports/rewrite/rewrite.patch` and
 * an exported `SourcesFileResults` data table.
 * On `rewriteRun` → overwrites [targetFile] with [newContent] and writes a later data table.
 * Every invocation appends the task name (`$1`) to `wrapper-calls.log`.
 * Exits 1 for any other argument.
 *
 * [oldLine] / [newLine] are the single changed lines in the diff (no leading `-`/`+`).
 * [newContent] is the file content to write on `rewriteRun`; newlines are converted to `\n`
 * escape sequences for the shell `printf` call.
 */
fun Path.writeFakeGradlew(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val gradlew = resolve("gradlew")
    gradlew.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"
        if [ "${D}1" = "rewriteDryRun" ]; then
          echo "${D}1" >> "${D}LOG"
          mkdir -p build/reports/rewrite
          cat > build/reports/rewrite/rewrite.patch <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          mkdir -p build/reports/rewrite/datatables/2024-01-01T00-00-00Z
          cat > build/reports/rewrite/datatables/2024-01-01T00-00-00Z/org.openrewrite.table.SourcesFileResults.csv <<'CSV'
        sourcePath,estimatedTimeSaving
        $targetFile,300
        CSV
          exit 0
        fi
        if [ "${D}1" = "rewriteRun" ]; then
          echo "${D}1" >> "${D}LOG"
          mkdir -p build/reports/rewrite/datatables/2024-01-01T00-00-01Z
          cat > build/reports/rewrite/datatables/2024-01-01T00-00-01Z/org.openrewrite.table.SourcesFileResults.csv <<'CSV'
        sourcePath,estimatedTimeSaving
        $targetFile,420
        CSV
          printf '$printfContent' > $targetFile
          exit 0
        fi
        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(gradlew, posixExecutable)
}

/**
 * Writes a fake `gradlew` that reproduces a plugin estimate source conflict: exported
 * `SourcesFileResults` tables exist but carry no per-row estimate, while plugin output reports
 * a positive `Estimate time saved` value.
 */
fun Path.writeFakeGradlewWithHeaderOnlyDataTablesAndEstimate(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String,
    estimate: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val gradlew = resolve("gradlew")
    gradlew.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"
        if [ "${D}1" = "rewriteDryRun" ]; then
          echo "${D}1" >> "${D}LOG"
          mkdir -p build/reports/rewrite
          cat > build/reports/rewrite/rewrite.patch <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          mkdir -p build/reports/rewrite/datatables/2024-01-01T00-00-00Z
          cat > build/reports/rewrite/datatables/2024-01-01T00-00-00Z/org.openrewrite.table.SourcesFileResults.csv <<'CSV'
        sourcePath,estimatedTimeSaving
        CSV
          echo "Estimate time saved: $estimate"
          exit 0
        fi
        if [ "${D}1" = "rewriteRun" ]; then
          echo "${D}1" >> "${D}LOG"
          mkdir -p build/reports/rewrite/datatables/2024-01-01T00-00-01Z
          cat > build/reports/rewrite/datatables/2024-01-01T00-00-01Z/org.openrewrite.table.SourcesFileResults.csv <<'CSV'
        sourcePath,estimatedTimeSaving
        CSV
          echo "Estimate time saved: $estimate"
          printf '$printfContent' > $targetFile
          exit 0
        fi
        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(gradlew, posixExecutable)
}

/**
 * Fully-qualified name of the sub-recipe the fake wrappers report as unresolvable.
 * Shared with the assertions so the marker text and the expectation cannot drift apart.
 */
const val MISSING_SUB_RECIPE = "com.example.TotallyMissingRecipe"

/**
 * Writes a fake `gradlew` that reproduces the upstream "missing sub-recipe" degradation from #268:
 * `rewriteDryRun` logs the validation errors at ERROR, still writes a real patch, and **exits 0**.
 *
 * The literals below are the exact strings emitted by `DeclarativeRecipe` (rewrite-core) and
 * `DefaultProjectParser` (rewrite-gradle-plugin) for a `recipeList` entry that is not on the
 * plugin's classpath. `rewriteRun` remains functional on purpose: if Stage 0 ever stops failing
 * on these markers, the apply goal runs and `wrapper-calls.log` records it.
 */
fun Path.writeFakeGradlewWithUnresolvedSubRecipe(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val gradlew = resolve("gradlew")
    gradlew.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"
        if [ "${D}1" = "rewriteDryRun" ]; then
          echo "${D}1" >> "${D}LOG"
          mkdir -p build/reports/rewrite
          cat > build/reports/rewrite/rewrite.patch <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          echo "Recipe validation error in com.example.Composite for property com.example.Composite.recipeList[1]: recipe '$MISSING_SUB_RECIPE' does not exist."
          echo "Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless."
          exit 0
        fi
        if [ "${D}1" = "rewriteRun" ]; then
          echo "${D}1" >> "${D}LOG"
          printf '$printfContent' > $targetFile
          exit 0
        fi
        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(gradlew, posixExecutable)
}

/**
 * Writes a fake `gradlew` that validates the generated init script contains every
 * [requiredExclusions] entry before simulating a single-file plugin change.
 */
fun Path.writeFakeGradlewWithExclusionChecks(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String,
    requiredExclusions: List<String>
) {
    val printfContent = newContent.replace("\n", "\\n")
    val checks =
        requiredExclusions.joinToString("; ") { exclusion ->
            "grep -F 'exclusion(\"$exclusion\")' \"${D}init_script\" >/dev/null || " +
                "{ echo \"missing Gradle exclusion: $exclusion\" 1>&2; exit 2; }"
        }
    val gradlew = resolve("gradlew")
    gradlew.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"
        init_script=""
        previous=""
        for arg in "$D@"; do
          if [ "${D}previous" = "--init-script" ]; then
            init_script="${D}arg"
          fi
          previous="${D}arg"
        done
        if [ "${D}1" = "rewriteDryRun" ]; then
          if [ -z "${D}init_script" ]; then
            exit 2
          fi
          $checks
          echo "${D}1" >> "${D}LOG"
          mkdir -p build/reports/rewrite
          cat > build/reports/rewrite/rewrite.patch <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          exit 0
        fi
        if [ "${D}1" = "rewriteRun" ]; then
          if [ -z "${D}init_script" ]; then
            exit 2
          fi
          $checks
          echo "${D}1" >> "${D}LOG"
          printf '$printfContent' > $targetFile
          exit 0
        fi
        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(gradlew, posixExecutable)
}

/**
 * Writes a simplified fake `mvnw` for non-JVM plugin-first tests.
 *
 * Detects the goal suffix (`:dryRun` / `:run`), logs `dryRun` or `run` to `wrapper-calls.log`,
 * extracts `-DreportOutputDirectory=<dir>`, and on `dryRun` writes a unified-diff patch and
 * exported `SourcesFileResults` table there; on `run` overwrites [targetFile] with [newContent]
 * and writes a later data table.
 *
 * Does **not** validate Maven flag details — see
 * `PluginFirstIntegrationTest.writeFakeMvnwWithProtocolChecks` for that level of protocol
 * verification.
 */
fun Path.writeFakeMvnwSimple(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val mvnw = resolve("mvnw")
    mvnw.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"

        goal=""
        report_dir=""

        for arg in "$D@"; do
          case "${D}arg" in
            *:dryRun) goal=dryRun ;;
            *:run)    goal=run ;;
            -DreportOutputDirectory=*) report_dir="$D{arg#-DreportOutputDirectory=}" ;;
          esac
        done

        if [ -n "${D}goal" ]; then
          echo "${D}goal" >> "${D}LOG"
        fi

        if [ "${D}goal" = "dryRun" ] && [ -n "${D}report_dir" ]; then
          mkdir -p "${D}report_dir"
          cat > "${D}report_dir/rewrite.patch" <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          mkdir -p "${D}report_dir/datatables/2024-01-01T00-00-00Z"
          cat > "${D}report_dir/datatables/2024-01-01T00-00-00Z/org.openrewrite.table.SourcesFileResults.csv" <<'CSV'
        sourcePath,estimatedTimeSaving
        $targetFile,300
        CSV
          exit 0
        fi

        if [ "${D}goal" = "run" ]; then
          if [ -n "${D}report_dir" ]; then
            mkdir -p "${D}report_dir/datatables/2024-01-01T00-00-01Z"
            cat > "${D}report_dir/datatables/2024-01-01T00-00-01Z/org.openrewrite.table.SourcesFileResults.csv" <<'CSV'
        sourcePath,estimatedTimeSaving
        $targetFile,420
        CSV
          fi
          printf '$printfContent' > $targetFile
          exit 0
        fi

        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(mvnw, posixExecutable)
}

/**
 * Writes a fake `mvnw` that reproduces Maven's full estimate source chain conflict: both exported
 * data-table locations exist but carry no per-row estimate, while plugin output reports a positive
 * `Estimate time saved` value.
 */
fun Path.writeFakeMvnwWithHeaderOnlyDataTablesAndEstimate(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String,
    estimate: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val mvnw = resolve("mvnw")
    mvnw.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"

        goal=""
        report_dir=""

        for arg in "$D@"; do
          case "${D}arg" in
            *:dryRun) goal=dryRun ;;
            *:run)    goal=run ;;
            -DreportOutputDirectory=*) report_dir="$D{arg#-DreportOutputDirectory=}" ;;
          esac
        done

        if [ -n "${D}goal" ]; then
          echo "${D}goal" >> "${D}LOG"
        fi

        if [ "${D}goal" = "dryRun" ] && [ -n "${D}report_dir" ]; then
          mkdir -p "${D}report_dir"
          cat > "${D}report_dir/rewrite.patch" <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          mkdir -p "${D}report_dir/datatables/2024-01-01T00-00-00Z"
          cat > "${D}report_dir/datatables/2024-01-01T00-00-00Z/org.openrewrite.table.SourcesFileResults.csv" <<'CSV'
        sourcePath,estimatedTimeSaving
        CSV
          mkdir -p target/rewrite/datatables/2024-01-01T00-00-00Z
          cat > target/rewrite/datatables/2024-01-01T00-00-00Z/org.openrewrite.table.SourcesFileResults.csv <<'CSV'
        sourcePath,estimatedTimeSaving
        CSV
          echo "Estimate time saved: $estimate"
          exit 0
        fi

        if [ "${D}goal" = "run" ]; then
          if [ -n "${D}report_dir" ]; then
            mkdir -p "${D}report_dir/datatables/2024-01-01T00-00-01Z"
            cat > "${D}report_dir/datatables/2024-01-01T00-00-01Z/org.openrewrite.table.SourcesFileResults.csv" <<'CSV'
        sourcePath,estimatedTimeSaving
        CSV
          fi
          mkdir -p target/rewrite/datatables/2024-01-01T00-00-01Z
          cat > target/rewrite/datatables/2024-01-01T00-00-01Z/org.openrewrite.table.SourcesFileResults.csv <<'CSV'
        sourcePath,estimatedTimeSaving
        CSV
          echo "Estimate time saved: $estimate"
          printf '$printfContent' > $targetFile
          exit 0
        fi

        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(mvnw, posixExecutable)
}

/**
 * Writes a fake `mvnw` that validates `-Drewrite.exclusions=` contains all
 * [requiredExclusions] before simulating a single-file plugin change.
 */
fun Path.writeFakeMvnwWithExclusionChecks(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String,
    requiredExclusions: List<String>
) {
    val printfContent = newContent.replace("\n", "\\n")
    val checks =
        requiredExclusions.joinToString("; ") { exclusion ->
            "echo \"${D}exclusions\" | grep -F '$exclusion' >/dev/null || " +
                "{ echo \"missing Maven exclusion: $exclusion in ${D}exclusions\" 1>&2; exit 2; }"
        }
    val mvnw = resolve("mvnw")
    mvnw.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"

        goal=""
        report_dir=""
        exclusions=""

        for arg in "$D@"; do
          case "${D}arg" in
            *:dryRun) goal=dryRun ;;
            *:run)    goal=run ;;
            -DreportOutputDirectory=*) report_dir="$D{arg#-DreportOutputDirectory=}" ;;
            -Drewrite.exclusions=*) exclusions="$D{arg#-Drewrite.exclusions=}" ;;
          esac
        done

        if [ -n "${D}goal" ]; then
          echo "${D}goal" >> "${D}LOG"
        fi

        if [ "${D}goal" = "dryRun" ] && [ -n "${D}report_dir" ]; then
          $checks
          mkdir -p "${D}report_dir"
          cat > "${D}report_dir/rewrite.patch" <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          exit 0
        fi

        if [ "${D}goal" = "run" ]; then
          $checks
          printf '$printfContent' > $targetFile
          exit 0
        fi

        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(mvnw, posixExecutable)
}

/**
 * Writes a fake `mvnw` that validates the Maven flag protocol on top of the simple
 * dry-run/run behaviour, asserting that MavenPluginStrategy sends:
 *  - the unprefixed `-DreportOutputDirectory=` (not `-Drewrite.reportOutputDirectory=`), and
 *  - `-Drewrite.runPerSubmodule=false`.
 *  - `-Drewrite.exportDatatables=true`.
 *
 * A regression to the wrong flag format makes the wrapper exit 2. Patch paths and content are
 * derived from [targetFile]/[oldLine]/[newLine]/[newContent] so the asserted file tracks the
 * scenario layout instead of being hard-coded.
 */
fun Path.writeFakeMvnwWithProtocolChecks(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val mvnw = resolve("mvnw")
    mvnw.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"

        goal=""
        report_dir=""
        has_run_per_submodule=0
        has_export_datatables=0
        has_wrong_prefix=0
        has_unprefixed=0

        for arg in "$D@"; do
          case "${D}arg" in
            *:dryRun)
              if [ -z "${D}goal" ]; then goal=dryRun; fi
              ;;
            *:run)
              if [ -z "${D}goal" ]; then goal=run; fi
              ;;
            -DreportOutputDirectory=*)
              report_dir="$D{arg#-DreportOutputDirectory=}"
              has_unprefixed=1
              ;;
            -Drewrite.reportOutputDirectory=*)
              has_wrong_prefix=1
              ;;
            -Drewrite.runPerSubmodule=false)
              has_run_per_submodule=1
              ;;
            -Drewrite.exportDatatables=true)
              has_export_datatables=1
              ;;
          esac
        done

        if [ -n "${D}goal" ]; then
          echo "${D}goal" >> "${D}LOG"
        fi

        if [ "${D}has_wrong_prefix" = "1" ]; then
          echo "FAIL: prefixed -Drewrite.reportOutputDirectory must not be used" 1>&2
          exit 2
        fi
        if [ "${D}has_unprefixed" = "0" ]; then
          echo "FAIL: -DreportOutputDirectory missing" 1>&2
          exit 2
        fi
        if [ "${D}has_run_per_submodule" = "0" ]; then
          echo "FAIL: -Drewrite.runPerSubmodule=false missing" 1>&2
          exit 2
        fi
        if [ "${D}has_export_datatables" = "0" ]; then
          echo "FAIL: -Drewrite.exportDatatables=true missing" 1>&2
          exit 2
        fi

        if [ "${D}goal" = "dryRun" ]; then
          mkdir -p "${D}report_dir"
          cat > "${D}report_dir/rewrite.patch" <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          mkdir -p "${D}report_dir/datatables/2024-01-01T00-00-00Z"
          cat > "${D}report_dir/datatables/2024-01-01T00-00-00Z/org.openrewrite.table.SourcesFileResults.csv" <<'CSV'
        sourcePath,estimatedTimeSaving
        $targetFile,300
        CSV
          exit 0
        fi

        if [ "${D}goal" = "run" ]; then
          if [ -n "${D}report_dir" ]; then
            mkdir -p "${D}report_dir/datatables/2024-01-01T00-00-01Z"
            cat > "${D}report_dir/datatables/2024-01-01T00-00-01Z/org.openrewrite.table.SourcesFileResults.csv" <<'CSV'
        sourcePath,estimatedTimeSaving
        $targetFile,420
        CSV
          fi
          printf '$printfContent' > $targetFile
          exit 0
        fi

        exit 1
        """.trimIndent()
    )
    if (!isWindows) Files.setPosixFilePermissions(mvnw, posixExecutable)
}

/**
 * Writes a minimal wrapper script (gradlew or mvnw) that exits 1 for every invocation.
 *
 * Used in stage-1 failure tests where the build tool must be present but must not produce
 * any classpath output, forcing the LST pipeline to fall through to later stages.
 * Uses [File.setExecutable] which works on all platforms (unlike POSIX permissions).
 */
fun Path.writeFakeExitOneWrapper(name: String) {
    val script = resolve(name).toFile()
    script.writeText("#!/bin/sh\nexit 1\n")
    script.setExecutable(true)
}

/** Retained for source compatibility; all helpers are now top-level functions. */
abstract class BaseIntegrationTest
