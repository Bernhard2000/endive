# Evidence for the Endive fork audit, 25 October 2026

See [the report](../../../../FORK_AUDIT_2026-10-25.md) for severity, attribution, proposed changes,
and acceptance criteria. These are diagnostic reproducers, not production changes.

- `probe-results.txt`: recorded output of the focused diagnostic runner.
- `upstream-control.txt`: the loop fixture under the pinned upstream compiler sources, with the
  current parser/runtime and identical ASM dependencies. This isolates compiler behavior.
- `directory-cache-default-failure.txt`: the failing default test section from the expanded reactor.
- `validation-summary.json`: per-run outcomes, commands, test totals, and complete-log hashes.
- `manifest.json`: repository/environment identity and hashes of this evidence and relevant sources.
- `*.wat` and `*.wat.wasm`: readable source and the corresponding small binary inputs, originally
  assembled and validated with this checkout's `Wat2Wasm` implementation.
- `*.java`, `summary-probe.py`, `run-probes.sh`: reproducer sources; all writes go to a temporary
  output directory when the runner is invoked.

Build the compiler and its dependencies first, using an isolated Maven repository. Then run:

```sh
ENDIVE_AUDIT_M2=/path/to/isolated/maven/repository bash \
  compiler/docs/data/fork-audit-2026-10-25/run-probes.sh
```

The runner needs Java/Javac 11+, Python 3 with `ast` support for `type_ignores`, Bash, and GNU
`timeout`; it uses ASM 9.10.1, matching the audited POM. Java source is compiled with `--release 11`
to tolerate a newer `javac` than `java`. No native Wasm assembler is needed to run the saved binaries.
Set `ENDIVE_AUDIT_OUT` to retain results in a chosen scratch directory.

The cancellation probe deliberately uses daemon workers and a process timeout. Ordinary loops
stop; failed tail-cycle cancellation is observable without leaving persistent guest workers. A
zero runner exit code means the diagnostics completed, not that the observed defects are fixed.

The wrapper probe uses a fake Maven executable. It prints forwarded arguments and performs no
installation. The reducer probe loads only the reducer's function definitions and required imports;
it does not run the reducer's top-level code or overwrite historical benchmark summaries.

Full Maven logs were kept in the scratch directory recorded in the JSON. Their hashes identify
them, but they are not bundled here. The important results and the default-cache failure section
are retained. This audit did not change or republish historical benchmark evidence.
