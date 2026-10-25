# Upstream sync, 25 October 2026

The integration branch `fuse-branch-conditions` was rebased onto upstream `main`
`bce75ae684c56d7f240fd00892f863d34d3071b3` (`bytecodealliance/endive`, 23 October 2026). Before,
it branched off `1580f534`. Upstream's five newer commits touch no file the fork changed, and the
rebase replayed every fork commit, both topic-branch merges included, without conflicts: the
rebased tip `3883b7ef` has the tree of a merge of the old tip with upstream. Since then the branch
carries the audit fixes listed in [FORK_CHANGES.md](../../FORK_CHANGES.md#11-audit-fixes-2026-10-25).

What the sync brings in: upstream `1cd6d40d` (#242) records the defining instance as owner of
table-initialiser entries, so an importer no longer runs its own function at that index (audit
F13), and runs Redline's cross-instance `call_indirect` in the owning instance; `7ba980b9` (#248),
a `MemoryTest` that cannot hang and a 60-minute CI job timeout; dependency bumps
(`jackson-databind` 2.22.4, central-publishing plugin 0.12.0, Android JUnit 5 plugin).

## Old and new commit ids

Notes, results and thesis records written before the sync name commits of the old history,
which the tag `archive/fuse-branch-conditions-pre-sync-2026-10-25` (`907750cf`) keeps reachable.
`origin/fuse-branch-conditions` still points into it (`44f68a6f`) until the branch is pushed. Each
old commit has the same patch as its new counterpart (`git range-diff`); the ids measured and
cited before, such as `44f68a6f` in the workload A/B, remain the ids of what was measured.

| old | new | commit |
| --- | --- | --- |
| `f5225324` | `444198a8` | Fuse comparisons into the branches and selects that consume them |
| `b4d973e1` | `9a8136b5` | Fix the boxed-argument ABI for functions with many parameters |
| `854e77ca` | `3b29285b` | Suppress EnumOrdinal on BranchCondition.id() |
| `82f39301` | `0fe9b7f3` | Optimize split-function dispatch and fix wide result packing |
| `5a02a7dd` | `39f47cb9` | Extend branch-condition fusion to constants, unsigned i64 and null tests |
| `bc021732` | `6d9e739b` | Document the branch-condition fusion extensions and the upstream PR |
| `e533eb13` | `5ea17f76` | Add local runtime benchmarks of branch-condition fusion against upstream |
| `4e8b372a` | `fb0b9ebe` | Add implementation plan for the remaining fork improvements |
| `b7458f6e` | `599e1d69` | Build the fork against its own local Maven repository |
| `33b0aeba` | `a0fcfa51` | Honour -Dendive.memCopyWorkaround in MemCopyWorkaround itself |
| `88ed85b3` | `365f2cab` | Route i32.gt_u and i32.le_u through the JIT bug workaround |
| `75249a83` | `cb0504dd` | Key the downloaded wasm-tools binary on its version |
| `1ec98224` | `ee115466` | Render the lots-of-args approval file on every run |
| `a7034aba` | `51b9dd77` | Apply Spotless formatting to the device-tests build script |
| `7238a8b6` | `5cba8930` | Merge branch 'fix/memcopy-workaround-property' into fuse-branch-conditions |
| `a5b98f41` | `f2c7de7c` | Merge branch 'fix/build-sharp-edges' into fuse-branch-conditions |
| `f00d3bc9` | `f48aacae` | Update the lots-of-args approval template for the boxed-argument ABI |
| `17542744` | `4dcb4424` | Compiler: narrow i64 work that is wrapped to i32, emit trivial helpers inline |
| `50a7c320` | `4537a111` | Compiler: sound result types for select_t and block exits, then fuse ref.eq |
| `9e613f66` | `801eb72f` | Compiler: unsigned i32 compare fusion and branchless booleans as options |
| `7ef91208` | `a49aeefd` | JMH: benchmark a large generated method with hundreds of comparisons |
| `507608d5` | `e994b33f` | Docs: design notes for the narrowing pass, ref.eq fusion and the opt-in lowerings; inlining census |
| `962d4ca8` | `09097a38` | Docs: upstream PR package with drafts, checks and the AI policy steps |
| `fa661588` | `15cf06c2` | Compiler: run narrowing and fusion while the instruction list is built |
| `c379bde9` | `48327caa` | Docs: large-method benchmark results and the branchless-booleans decision |
| `7e743d80` | `a9a12997` | Compiler: keep the helper call where the inline form is larger |
| `1b934deb` | `bf6528e8` | Docs: FORK_CHANGES for the improvement plan |
| `01741902` | `e305b17a` | Docs: LEB128 PR branch checks |
| `20b89d57` | `43689d64` | Docs: implementation status of the improvement plan |
| `44f68a6f` | `1b6f6e6f` | Docs: plan status records the author's hand-over items |
| `2bfc626a` | `6e356c68` | Docs: fork against upstream on 31 workload cells; switch-encoding regression |
| `4581782a` | `d3afb15f` | Docs: figures for the 31-cell workload A/B |
| `f5940ed5` | `bd66d0f8` | Docs: correct the split speed-up range in the workload A/B caption |
| `aae1e18f` | `f34d6974` | Docs: workload A/B without synthetic cells, plus AWFY on unsplit Lua and QuickJS |
| `907750cf` | `3883b7ef` | Docs: retest Lua NBody split in the workload A/B with a longer cell |
