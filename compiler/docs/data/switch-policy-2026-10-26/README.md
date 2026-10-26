# Data: switch-policy remeasure, 2026-10-26 (audit F05)

Fork `fuse-branch-conditions` `c5219baf` (switch policy `744036e2`) against upstream `bce75ae6`,
on the two interpreted `br_table` dispatchers of the 2026-10-18 A/B: `rustvm-base`, which the
compact switch encoding had made 12.6 % slower, and `z80-base`, the control. Same protocol as
[../workload-ab-2026-10-18/](../workload-ab-2026-10-18/): fresh JVM per sample, seeded permuted
arm order (`20261026`), 12 rounds, `taskset -c 4-7`, OpenJDK 21.0.12.1. Both arms run the class
files built for [../jdk17-jdk27-2026-10-26/](../jdk17-jdk27-2026-10-26/) (`classes-up`,
`classes-fork` there).

| path | content |
| --- | --- |
| `arms.sh`, `campaign.sh` | arms and runner: `campaign.sh 12`, then `campaign.sh 12 rustvm-base` for the rerun |
| `results/` | the reported cells: `z80-base` from the first run, `rustvm-base` from the rerun; per arm one JSON line per round, stderr, round orders, module hash, guest output of every sample |
| `results-disturbed/` | the first run's `rustvm-base`, set aside: reproducer JVMs and file copies of the same session ran on other cores during it, and its paired ratios spread from −6 to +18 % (the rerun: −6 to +8 %) |
| `campaign.log`, `campaign-rustvm-rerun.log` | per-sample timings of both runs |
| `summarize.py`, `summary.md` | paired ratios with bootstrap CIs; `python3 summarize.py` reproduces `summary.md`, `python3 summarize.py results-disturbed` the set-aside run (+2.0 % [+0.2, +10.5]) |
