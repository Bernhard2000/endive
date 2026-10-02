# Local validation artifacts

Baseline compiler revision: `854e77ca8e26476c2f9090f1b890ed26129bfd24`.
See [the results report](../../split-function-optimization-results.md) for scope and limitations.

- `dispatch-*-verified-jmh.json`: final dispatch implementation and a freshly repeated baseline
  4,096-entry trial. The final trial precedes the baseline. Two forks, three one-second warmups,
  five one-second measurements, 512 MiB heap, and the JMH GC profiler.
- `dispatch-guarded-prototype-jmh.json` and `dispatch-prototype-baseline-jmh.json`: earlier
  dispatch experiment that also changed the default of small tables. That small-table rewrite
  is excluded from the final implementation.
- `prototype-*-jmh.json` and `tuple-forwarding-aba.json`: excluded tuple-forwarding experiment.
  These synthetic helper chains do not establish performance for actual split workloads.
- `python-method-size.json` and `unicode-equivalence.txt`: final CPython bytecode-size and pure
  numeric-function equivalence checks. `CodeProbe.java`, `parse_class_methods.py`, and
  `CompareUnicode.java` are the standalone utilities used for those checks.
- `spec-validation.txt`: final selected cached specification run, using `RunSpecs.java`.
- `fixture-provenance.json`: original generation pins. `fixture-regeneration-verification.json`
  records the final byte-identical regeneration and its tool/library pins.

All timing files are exploratory local diagnostics. Their confidence intervals do not cover
host drift, run order, or differences between synthetic and actual split functions.
