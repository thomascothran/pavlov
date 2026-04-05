---
name: clj-paren-repair
description: Repair Clojure reader syntax errors caused by unbalanced delimiters ((), [], {}) to fix errors like "EOF while reading", "Unexpected EOF", "Unmatched delimiter", or "Unbalanced delimiter".
---

## Unbalanced delimiters repair (Clojure)

### Purpose
Clojure source must have balanced delimiters: parentheses `()`, brackets `[]`, and braces `{}`. When a file is unbalanced, the reader commonly fails with messages like:

- `EOF while reading`
- `Unexpected EOF`
- `Unmatched delimiter`
- `Unbalanced delimiter`
- `Syntax error reading source` / `ReaderException`

This skill prescribes a single remediation: run the automated repair tool `clj-paren-repair` via `devenv shell`.

### Hard rules
1. **Do NOT manually repair delimiter errors.**
   Do not add/remove/move delimiters “by inspection”, even if it looks obvious.
2. **Only run the tool on the specific file(s) implicated by the error**, unless the user explicitly requests broader scope.
3. **If `clj-paren-repair` fails or the reader error persists after running it, stop and report that the user must fix the delimiter error manually.** Do not attempt further automated or manual delimiter edits.

### Command
Run:

```bash
devenv shell clj-paren-repair <files...>
```

Examples:

```bash
devenv shell clj-paren-repair src/my/app/core.clj
```

## Workflow

Follow these steps in order:

1. Identify the exact file(s) to repair
   - Prefer files explicitly mentioned in error output/stack traces.
   - If multiple files are implicated, start with the topmost file referenced at the failure point.
   - Do not guess. If no file is identifiable, ask the user for the error output (or for the file they were editing).
   - Run the repair tool on just those files

2. Acknowledge formatting side-effect
   - clj-paren-repair also runs cljfmt on processed files.
   - Expect whitespace/formatting changes in addition to delimiter repairs.

3. Inspect the result
4. Re-run the failing action
   - Re-run the prior evaluation/test/build command that produced the reader error.
   - If the reader error is gone, proceed with whatever task was blocked.
   - If repair did not resolve the issue
      - If clj-paren-repair errors, exits non-zero, or the original reader error persists:
         + Do not attempt manual delimiter edits.
         + Report to the user: the delimiter error requires manual repair (and include the tool’s output if available).

## When NOT to use this skill

Do not run clj-paren-repair for:

- Type errors, unresolved symbols, arity errors, spec failures, runtime exceptions, failing tests not involving reader errors.
- Non-Clojure delimiter issues (e.g., EDN embedded in strings) unless the reader error points to a concrete file and the user wants you to try.

## Notes / edge cases

- Multi-extension codebases: the tool may be useful for .clj, .cljs, .cljc, and sometimes .edn if supported in your environment—only run it on file types the user is working with.

- If the user is mid-merge or has large unrelated diffs, consider recommending they commit/stash first (do not do it yourself unless your environment supports it and the user asked).

- If the tool makes no changes and the error remains, that is a hard stop per the rules above.
