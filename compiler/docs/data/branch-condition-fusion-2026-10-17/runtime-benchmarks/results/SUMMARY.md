## Engine A/B (fresh JVM per sample; paired per-round ratio pr/upstream)

### steady-state execution (median of in-process reps)

| workload | n | upstream ms | PR ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 15 | 1,779.4 | 1,679.4 | -5.5 % | [-6.9 %, -4.3 %] | yes |
| sqlite-base | 10 | 15,323.7 | 15,345.1 | -0.2 % | [-2.3 %, +2.3 %] | no |
| quickjs-base | 5 | 99,510.7 | 100,567.1 | +1.0 % | [-2.6 %, +2.6 %] | no |
| lua-split | 15 | 262.8 | 252.8 | -4.1 % | [-7.0 %, -0.9 %] | yes |
| sqlite-split | 15 | 1,887.2 | 1,708.5 | -9.5 % | [-11.1 %, -7.7 %] | yes |
| quickjs-split | 10 | 8,306.7 | 7,696.2 | -6.4 % | [-8.6 %, -5.3 %] | yes |

### first execution in a fresh JVM

| workload | n | upstream ms | PR ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 15 | 1,816.1 | 1,727.1 | -4.8 % | [-7.7 %, -4.0 %] | yes |
| sqlite-base | 10 | 15,323.7 | 15,345.1 | -0.2 % | [-2.3 %, +2.3 %] | no |
| quickjs-base | 5 | 99,510.7 | 100,567.1 | +1.0 % | [-2.6 %, +2.6 %] | no |
| lua-split | 15 | 578.9 | 557.1 | -1.4 % | [-11.4 %, +0.0 %] | no |
| sqlite-split | 15 | 2,593.9 | 2,359.8 | -8.1 % | [-9.7 %, -6.3 %] | yes |
| quickjs-split | 10 | 8,389.2 | 7,805.7 | -6.9 % | [-7.8 %, -5.7 %] | yes |

### parse + runtime compile

| workload | n | upstream ms | PR ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 15 | 630.5 | 647.1 | +2.5 % | [+0.1 %, +3.7 %] | yes |
| sqlite-base | 10 | 1,683.0 | 1,709.8 | +2.2 % | [+0.3 %, +4.6 %] | yes |
| quickjs-base | 5 | 1,563.1 | 1,607.2 | +0.9 % | [-4.7 %, +5.7 %] | no |
| lua-split | 15 | 605.0 | 612.2 | -0.0 % | [-1.8 %, +2.4 %] | no |
| sqlite-split | 15 | 1,368.1 | 1,368.4 | +0.1 % | [-0.3 %, +1.2 %] | no |
| quickjs-split | 10 | 1,128.5 | 1,143.8 | +1.7 % | [-0.8 %, +3.9 %] | no |

## Endive JMH (3 forks per engine, order A B B A A B)

| benchmark | params | mode | unit | upstream (mean of forks) | PR | PR vs upstream | fork spread A / B |
| --- | --- | --- | --- | ---: | ---: | ---: | --- |
| BenchmarkDispatchChunkSize.dispatch | targetFunc=0 | avgt | us/op | 0.006773 | 0.007044 | -3.8 % slower | 0.4 % / 7.7 % |
| BenchmarkDispatchChunkSize.dispatch | targetFunc=1999 | avgt | us/op | 0.006937 | 0.006868 | +1.0 % faster | 7.6 % / 3.4 % |
| BenchmarkDispatchChunkSize.dispatch | targetFunc=999 | avgt | us/op | 0.006762 | 0.006947 | -2.7 % slower | 0.3 % / 7.8 % |
| BenchmarkFactorialExecution.benchmarkCompiled | input=1000 | thrpt | ops/s | 1.117e+06 | 1.11e+06 | -0.6 % slower | 1.3 % / 2.6 % |
| BenchmarkFactorialExecution.benchmarkCompiled | input=5 | thrpt | ops/s | 1.904e+08 | 1.96e+08 | +3.0 % faster | 2.9 % / 1.4 % |
| BenchmarkFactorialExecution.benchmarkInt | input=1000 | thrpt | ops/s | 1.135e+04 | 1.126e+04 | -0.7 % slower | 1.0 % / 0.9 % |
| BenchmarkFactorialExecution.benchmarkInt | input=5 | thrpt | ops/s | 1.474e+06 | 1.477e+06 | +0.2 % faster | 0.8 % / 1.2 % |
| BenchmarkSievePrimes.benchmark | limit=1000000,machineType=compiled,memoryType=ByteArrayMemory,numWorkers=4 | thrpt | ops/s | 61.37 | 61.67 | +0.5 % faster | 0.5 % / 14.8 % |
| BenchmarkSievePrimes.benchmark | limit=1000000,machineType=compiled,memoryType=ByteBufferMemory,numWorkers=4 | thrpt | ops/s | 34.35 | 35.42 | +3.1 % faster | 0.9 % / 3.5 % |
| BenchmarkWat2Wasm.benchmarkAot |  | thrpt | ops/s | 19.95 | 19.88 | -0.4 % slower | 2.5 % / 6.7 % |

