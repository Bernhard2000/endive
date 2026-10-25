# Second pass: upstream re-check and additional probes, 25 October 2026

Extends [the audit report](../../../../../FORK_AUDIT_2026-10-25.md) (findings F13 and F14, and the
"Second pass" notes on F01–F04 and F09). Diagnostic reproducers, not production changes.

Three trees were built from git objects into a scratch directory, each with its own isolated Maven
repository (`-Dquickly ... install`, OpenJDK 21.0.12.1, Maven 3.10.0):

| Tree | Revision |
| --- | --- |
| fork | `907750cfddb112c635d1af2e5375dbecc79de262` (`fuse-branch-conditions`, audited HEAD) |
| upstream | `bce75ae684c56d7f240fd00892f863d34d3071b3` (`bytecodealliance/endive` `main`, fetched 2026-10-25) |
| merged | `git merge-tree --write-tree HEAD upstream/main` → tree `b77f0eae9212559a9267d60cb361f5eb56bee0bb` (no conflicts) |

- `recheck-results.txt`: recorded output for all three trees, including the original audit runner
  (`../run-probes.sh`) on fork and merged, its engine probes on upstream, and the Maven totals of the
  merged tree.
- `TableOwnerProbe.java`, `table_init_owner-*.wat[.wasm]`: F13. The inputs are upstream's corpus files
  from commit `1cd6d40d` (#242); the probe mirrors upstream's
  `MachinesTest.shouldCallTableInitialiserInItsDefiningInstance` for all four engine pairings.
- `none*.wat[.wasm]`, `ParseOnly.java`: F14. Each module is accepted by wasm-tools' validator
  (assembled through this checkout's `run.endive.tools.wasm.Wat2Wasm`, which validates).
- `ManyParamsMixedProbe.java`, `many_params_cross_module-*`: boxed (>251 slot) `call_indirect`
  across instances for every interpreter/compiler pairing; the existing fork test covers only
  same-engine pairs. Passes.
- `gen.py` → `fuse.wasm`, `FuseDiff.java`: differential test of numeric condition fusion and the
  narrowing peephole: 1,952 exports (every i32/i64/f32/f64 comparison; constants 0, ±1, ±2, 255, 256,
  2^16, 2^30, 2^31−1, −2^31, −2^31+1 on either side; `if`, `if (eqz)`, `br_if`, double `eqz`,
  `select`, `select (i64.eqz (extend))`, extend/wrap and unfused consumers; every narrowed binary op
  and load), boundary inputs including NaN, ±0, ±∞ and memory-bounds traps, against the interpreter,
  for all four `unsignedCompareFusion` × `branchlessBooleans` settings. Passes (0 of 286,448 differ).
  `fuse.wat` is not kept (277 kB); `python3 gen.py > fuse.wat` regenerates it byte for byte.
- `gc.wat[.wasm]`: reference-fusion merge shapes (block result via `br_if`, if/else merge,
  `select (result eqref)`, `br_table`, `br_on_cast`, `br_on_cast_fail`, `br_on_null`,
  `br_on_non_null`, mutable global with `local.tee`, typed nulls, `ref.is_null`) plus the F01 loop
  shape `i_loop`. Only `i_loop` differs. `gc.wat` avoids `(ref.null none)` because of F14.

Run, after building `wasm`, `runtime` and `compiler` of the checkout this directory sits in:

```sh
ENDIVE_AUDIT_M2=/path/to/isolated/maven/repository bash \
  compiler/docs/data/fork-audit-2026-10-25/upstream-recheck/run-recheck.sh
```

`FuseDiff` needs the fork's compiler options and is skipped on an upstream checkout. A zero exit code
means the diagnostics completed, not that the observed defects are fixed.

Network use in this pass: read-only `git fetch` of `upstream` (bytecodealliance) and `origin`
(the GitHub fork), and Maven Central downloads into the scratch repositories for upstream's new
`jackson-databind` 2.22.4. Nothing was pushed or installed into a shared Maven repository.

`../manifest.json` and `../validation-summary.json` describe the first pass; their report hash
predates the second-pass edits. `SHA256SUMS` here covers this directory.
