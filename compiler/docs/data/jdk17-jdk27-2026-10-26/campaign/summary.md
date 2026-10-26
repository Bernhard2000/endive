
### steady (exec_ms_median), Δ = median paired ratio − 1, 95 % bootstrap CI

| workload | fork vs upstream, JDK 17 (workaround on) | workaround off vs on, fork, JDK 17 | fork vs upstream, JDK 27 | fork JDK 27 vs JDK 17 |
|---|---|---|---|---|
| awfy-js-split-Richards | **-6.5 %** [-7.2, -4.2] n=10 | -0.6 % [-1.3, +0.4] n=10 | **-14.6 %** [-16.7, -13.7] n=10 | **-15.4 %** [-16.5, -14.4] n=10 |
| awfy-lua-split-Richards | **-5.6 %** [-11.1, -3.6] n=10 | +0.5 % [-1.3, +1.6] n=10 | **-9.4 %** [-11.1, -6.8] n=10 | **-4.0 %** [-5.6, -2.6] n=10 |
| binjgb-split | **-10.8 %** [-12.1, -6.0] n=10 | -0.8 % [-4.9, +4.0] n=10 | **-10.9 %** [-11.8, -10.6] n=10 | **-2.7 %** [-5.5, -0.4] n=10 |
| lua-base | **-5.1 %** [-8.1, -3.4] n=10 | +1.4 % [-1.2, +2.4] n=10 | **-4.3 %** [-7.1, -2.9] n=10 | -1.3 % [-1.8, +1.6] n=10 |
| lua-split | **-9.0 %** [-9.8, -7.6] n=10 | -0.4 % [-1.1, +0.4] n=10 | **-13.0 %** [-14.3, -11.6] n=10 | **-1.4 %** [-2.5, -0.1] n=10 |
| sqlite-split | **-9.3 %** [-10.0, -7.2] n=10 | **-0.8 %** [-1.7, -0.5] n=10 | **-10.7 %** [-11.0, -9.9] n=10 | **-3.0 %** [-3.7, -1.9] n=10 |

### first (first_exec_ms), Δ = median paired ratio − 1, 95 % bootstrap CI

| workload | fork vs upstream, JDK 17 (workaround on) | workaround off vs on, fork, JDK 17 | fork vs upstream, JDK 27 | fork JDK 27 vs JDK 17 |
|---|---|---|---|---|
| awfy-js-split-Richards | **-11.8 %** [-16.5, -9.3] n=10 | -1.3 % [-4.3, +5.6] n=10 | **-11.0 %** [-15.1, -6.7] n=10 | **-10.2 %** [-11.0, -3.9] n=10 |
| awfy-lua-split-Richards | **-6.3 %** [-9.1, -3.0] n=10 | -0.5 % [-2.3, +2.7] n=10 | -3.1 % [-6.9, +2.0] n=10 | +0.4 % [-1.7, +7.1] n=10 |
| binjgb-split | **-10.4 %** [-15.0, -5.9] n=10 | -3.5 % [-5.4, +4.3] n=10 | **-12.7 %** [-15.6, -10.1] n=10 | -3.8 % [-9.0, +0.0] n=10 |
| lua-base | **-4.8 %** [-7.9, -2.5] n=10 | -0.4 % [-1.1, +1.0] n=10 | **-5.2 %** [-6.6, -4.1] n=10 | **-1.9 %** [-3.3, -0.6] n=10 |
| lua-split | **-13.6 %** [-23.7, -4.6] n=10 | +9.4 % [-0.7, +14.0] n=10 | -3.2 % [-9.8, +4.7] n=10 | **+9.4 %** [+6.5, +14.8] n=10 |
| sqlite-split | **-11.8 %** [-12.7, -9.1] n=10 | -0.2 % [-1.3, +2.2] n=10 | **-7.3 %** [-9.4, -6.4] n=10 | **-4.6 %** [-5.5, -2.7] n=10 |

### compile (compile_ms), Δ = median paired ratio − 1, 95 % bootstrap CI

| workload | fork vs upstream, JDK 17 (workaround on) | workaround off vs on, fork, JDK 17 | fork vs upstream, JDK 27 | fork JDK 27 vs JDK 17 |
|---|---|---|---|---|
| awfy-js-split-Richards | **+6.2 %** [+3.4, +8.2] n=10 | -0.5 % [-4.1, +1.6] n=10 | **+4.3 %** [+0.7, +5.4] n=10 | **-2.4 %** [-3.8, -0.9] n=10 |
| awfy-lua-split-Richards | **+7.6 %** [+3.7, +12.1] n=10 | -3.0 % [-4.8, +0.4] n=10 | **+3.4 %** [+2.8, +5.0] n=10 | **-3.8 %** [-5.2, -1.8] n=10 |
| binjgb-split | +3.8 % [-20.6, +14.8] n=10 | -0.3 % [-11.9, +16.9] n=10 | +5.5 % [-17.7, +49.8] n=10 | -3.5 % [-10.6, +50.4] n=10 |
| lua-base | **+7.0 %** [+3.7, +16.2] n=10 | -2.9 % [-3.7, +0.8] n=10 | +6.9 % [-3.1, +21.7] n=10 | -0.5 % [-3.3, +8.0] n=10 |
| lua-split | **+3.9 %** [+1.1, +7.2] n=10 | **-3.9 %** [-6.6, -2.7] n=10 | +2.8 % [-0.3, +5.4] n=10 | **-4.3 %** [-7.5, -1.5] n=10 |
| sqlite-split | **+4.9 %** [+3.6, +7.4] n=10 | +0.0 % [-1.2, +1.5] n=10 | **+2.4 %** [+0.8, +4.0] n=10 | **-8.1 %** [-8.9, -7.0] n=10 |

### medians (ms) and guest output check

- awfy-js-split-Richards: up17 2744, fork17 2569, fork17noWA 2551, up27 2537, fork27 2177; distinct guest outputs: 1
- awfy-lua-split-Richards: up17 2070, fork17 1976, fork17noWA 1973, up27 2080, fork27 1882; distinct guest outputs: 1
- binjgb-split: up17 4295, fork17 3898, fork17noWA 3815, up27 4273, fork27 3772; distinct guest outputs: 1
- lua-base: up17 1792, fork17 1691, fork17noWA 1702, up27 1758, fork27 1675; distinct guest outputs: 1
- lua-split: up17 258, fork17 235, fork17noWA 234, up27 268, fork27 232; distinct guest outputs: 1
- sqlite-split: up17 1949, fork17 1782, fork17noWA 1754, up27 1931, fork27 1728; distinct guest outputs: 1
