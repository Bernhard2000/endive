
### steady (exec_ms_median), Δ = median paired ratio − 1, 95 % bootstrap CI

| workload | fork vs upstream, Java 21 |
|---|---|
| rustvm-base | +1.6 % [-2.0, +3.3] n=12 |
| z80-base | +0.8 % [-0.6, +3.4] n=12 |

### first (first_exec_ms), Δ = median paired ratio − 1, 95 % bootstrap CI

| workload | fork vs upstream, Java 21 |
|---|---|
| rustvm-base | +1.6 % [-1.7, +2.4] n=12 |
| z80-base | **+1.3 %** [+0.0, +3.1] n=12 |

### compile (compile_ms), Δ = median paired ratio − 1, 95 % bootstrap CI

| workload | fork vs upstream, Java 21 |
|---|---|
| rustvm-base | -5.2 % [-17.5, +18.0] n=12 |
| z80-base | +3.3 % [-4.7, +6.4] n=12 |

### medians (ms) and guest output check

- rustvm-base: up21 6656, fork21 6649; distinct guest outputs: 1
- z80-base: up21 11107, fork21 11260; distinct guest outputs: 1
