# ADR 0012: Uniform Failure Reporting — Execution Failures Are Returned, Not Thrown

## Status

Proposed

## Context

[ADR 0011](0011-unresolved-recipes-are-failures.md) establishes that an unresolved recipe fails the
stage that could not resolve it, and that the run fails only when no stage can resolve every required
recipe. Implementing that exposed how inconsistently the terminal case was handled.

`runFullFallback` had no error handling around LST execution, so a worker failure propagated and no
result was ever constructed. Three consequences followed:

- **Diagnostics were discarded exactly when they mattered.** The accumulated executor attempts — by
  then holding Stage 0's own record of why it failed — were lost, leaving the user only the last
  message. Since the stages resolve from independent sources, knowing that *both* failed, and why
  each failed, is what distinguishes an unreachable repository from a bad coordinate.
- **A failed run could silently modify the repository.** In a hybrid run, Stage 0 has already applied
  changes in the units it handled before the LST stage runs. A propagating exception meant the run
  exited non-zero reporting no changed files, while the working tree genuinely had changed.
- **The same error presented differently per execution mode.** The forked path produced an
  `IllegalStateException` carrying a message; the in-process path produced the original
  `IllegalArgumentException`. The CLI routes those to different branches, so one mode presented a
  plain user-facing error and the other logged an "unhandled exception" with a stack trace.

Partial disk application already had a returned-failure shape (`writeOutcome`), so the codebase had
two contradictory conventions for reporting failure at once.

## Decision

**Failure detection and reporting are uniform across every failure mode.** Every terminal execution
failure — unresolved recipe, timeout, heap exhaustion, protocol failure, worker crash — is:

1. classified with a typed `ExecutorOutcome`, extending the existing set rather than encoding the
   kind in a message string;
2. reported by **returning** a result carrying the failure, the executor attempts from every stage
   that ran, and any changes that legitimately landed before the failure;
3. reflected in the CLI exit code through a single aggregate check.

`RunResult` gains an aggregate accessor covering every failure kind, so callers have exactly one
thing to check, plus an `orThrow()` escape hatch for callers who prefer fail-fast. The CLI uses the
aggregate.

**Precondition errors keep throwing.** An invalid request detected before any execution begins — a
`projectDir` that is not a directory, a custom `ChangeWriter` supplied in forked mode — is a
programmer error, not a run outcome. No run happened, so there is no result to return, and returning
one would be less consistent rather than more.

## Consequences

- The library contract changes: `run()` no longer throws for execution failures. Callers that relied
  on exceptions to detect timeouts or heap exhaustion must check the aggregate or call `orThrow()`.
- A returned failure is one a caller can forget to check. The aggregate accessor and `orThrow()`
  mitigate this; the compiler does not enforce it.
- Reporting a failure and changed files in the same result is deliberate. Partial success is real
  here — a hybrid run can fail overall while having legitimately applied changes — and the result
  must be able to say so.

## Considered Alternatives

**Return only unresolved-recipe failures; keep throwing for everything else.** Would confine the
contract change to the case ADR 0011 forced. Rejected: it leaves two failure conventions in the
codebase, and the diagnostics-discarded and dirty-tree problems above are not specific to recipe
resolution — they affect timeouts and heap exhaustion identically.

**A sealed `Success | Failed` result.** The only compiler-enforced option. Rejected because it models
this system badly: a run can fail *and* have written files, so the sealed type needs a third partial
case, which reintroduces most of the complexity while breaking every caller.

**Distinguish failure kinds by matching the message text.** Avoids extending the outcome enum.
Rejected as fragile — it would mean matching upstream strings once inside the plugin subprocess and
again across our own process boundary, with silent degradation whenever a message is reworded.

**A builder flag to opt into returned failures.** Preserves the existing contract for current
callers. Rejected: it makes behaviour depend on configuration, which is the inconsistency this ADR
exists to remove.
