### Steady-state execution (median of in-process reps)

| workload | n | upstream ms | fork ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 12 | 1,787.1 | 1,702.3 | -4.7 % | [-5.9 %, -3.4 %] | yes |
| lua-split | 12 | 264.8 | 240.5 | -7.6 % | [-11.7 %, -4.3 %] | yes |
| sqlite-split | 12 | 1,905.5 | 1,719.7 | -10.4 % | [-11.0 %, -8.0 %] | yes |
| quickjs-split | 12 | 8,614.7 | 7,974.4 | -7.5 % | [-8.8 %, -7.0 %] | yes |
| python-split | 12 | 381.1 | 364.2 | -6.1 % | [-12.3 %, +2.2 %] | no |
| binjgb-base | 12 | 4,464.9 | 4,174.5 | -6.5 % | [-7.2 %, -5.6 %] | yes |
| binjgb-split | 12 | 4,122.5 | 3,764.1 | -8.6 % | [-9.7 %, -7.5 %] | yes |
| i8080-base | 12 | 15,584.0 | 14,544.3 | -6.4 % | [-7.8 %, -3.7 %] | yes |
| i8080-split | 12 | 2,261.2 | 2,080.5 | -9.0 % | [-14.0 %, -3.0 %] | yes |
| z80-base | 12 | 11,120.2 | 11,106.8 | +1.0 % | [-1.1 %, +1.7 %] | no |
| z80-split | 12 | 2,760.2 | 2,646.2 | -4.7 % | [-5.2 %, -3.4 %] | yes |
| genesis-base | 12 | 2,573.2 | 2,387.2 | -7.3 % | [-8.1 %, -6.2 %] | yes |
| genesis-split | 12 | 940.2 | 809.3 | -6.1 % | [-15.3 %, -2.6 %] | yes |
| rustvm-base | 12 | 6,390.9 | 7,235.6 | +12.6 % | [+9.9 %, +14.8 %] | yes |
| rustvm-split | 12 | 891.6 | 882.1 | -2.2 % | [-4.2 %, +1.0 %] | no |
| fkernel-base | 12 | 4,533.2 | 3,547.7 | -22.0 % | [-24.9 %, -20.5 %] | yes |
| fkernel-split | 12 | 382.3 | 301.3 | -21.2 % | [-22.3 %, -19.0 %] | yes |
| rv32ima-base | 12 | 2,876.5 | 2,228.9 | -22.3 % | [-23.3 %, -2.7 %] | yes |
| sqlite-app-split | 12 | 1,917.2 | 1,865.6 | -4.1 % | [-7.5 %, +1.0 %] | no |
| speedtest1-split | 12 | 2,532.6 | 2,309.3 | -8.3 % | [-10.1 %, -7.0 %] | yes |
| awfy-js-Bounce | 12 | 395.1 | 350.3 | -14.2 % | [-18.7 %, +2.3 %] | no |
| awfy-js-CD | 12 | 790.2 | 747.0 | -7.7 % | [-10.3 %, -3.4 %] | yes |
| awfy-js-DeltaBlue | 12 | 268.2 | 228.9 | -16.6 % | [-22.3 %, -9.0 %] | yes |
| awfy-js-Json | 12 | 967.7 | 909.3 | -6.7 % | [-8.2 %, -3.8 %] | yes |
| awfy-js-NBody | 12 | 176.8 | 182.3 | -0.0 % | [-7.3 %, +9.8 %] | no |
| awfy-js-Richards | 12 | 2,766.5 | 2,603.2 | -6.4 % | [-7.1 %, -5.2 %] | yes |
| awfy-lua-CD | 12 | 11,071.5 | 10,395.9 | -6.1 % | [-8.5 %, -4.8 %] | yes |
| awfy-lua-DeltaBlue | 12 | 200.7 | 202.0 | -4.3 % | [-7.2 %, +3.2 %] | no |
| awfy-lua-Json | 12 | 928.1 | 925.9 | -5.9 % | [-8.7 %, +5.0 %] | no |
| awfy-lua-NBody | 12 | 135.2 | 202.2 | +23.5 % | [-2.4 %, +64.1 %] | no |
| awfy-lua-Richards | 12 | 2,148.8 | 1,924.9 | -11.2 % | [-16.3 %, -6.4 %] | yes |

### First execution in a fresh JVM

| workload | n | upstream ms | fork ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 12 | 1,828.3 | 1,728.3 | -5.2 % | [-7.7 %, -3.1 %] | yes |
| lua-split | 12 | 594.1 | 559.1 | -9.7 % | [-18.1 %, +9.1 %] | no |
| sqlite-split | 12 | 2,559.8 | 2,319.0 | -8.7 % | [-10.8 %, -7.3 %] | yes |
| quickjs-split | 12 | 8,529.2 | 7,888.5 | -7.5 % | [-10.1 %, -6.2 %] | yes |
| python-split | 12 | 862.2 | 809.0 | -5.6 % | [-10.3 %, -2.4 %] | yes |
| binjgb-base | 12 | 4,695.0 | 4,357.9 | -7.2 % | [-7.9 %, -6.0 %] | yes |
| binjgb-split | 12 | 4,403.2 | 4,033.8 | -8.3 % | [-9.5 %, -7.6 %] | yes |
| i8080-base | 12 | 15,336.5 | 14,506.5 | -5.9 % | [-7.9 %, -3.8 %] | yes |
| i8080-split | 12 | 2,287.2 | 2,175.8 | -5.1 % | [-10.8 %, -2.8 %] | yes |
| z80-base | 12 | 11,050.5 | 11,091.7 | +1.0 % | [-0.6 %, +1.5 %] | no |
| z80-split | 12 | 2,153.8 | 2,102.7 | -1.2 % | [-4.9 %, +0.3 %] | no |
| genesis-base | 12 | 2,820.3 | 2,626.0 | -6.7 % | [-7.6 %, -5.5 %] | yes |
| genesis-split | 12 | 1,785.3 | 1,600.6 | -8.4 % | [-14.1 %, -4.7 %] | yes |
| rustvm-base | 12 | 6,344.4 | 7,231.8 | +13.9 % | [+10.8 %, +15.2 %] | yes |
| rustvm-split | 12 | 1,046.2 | 1,037.2 | -1.1 % | [-3.0 %, +2.4 %] | no |
| fkernel-base | 12 | 4,499.8 | 3,532.6 | -21.8 % | [-23.7 %, -20.5 %] | yes |
| fkernel-split | 12 | 449.9 | 359.9 | -20.3 % | [-21.4 %, -13.3 %] | yes |
| rv32ima-base | 12 | 2,018.6 | 2,026.6 | +0.5 % | [-0.6 %, +3.0 %] | no |
| sqlite-app-split | 12 | 3,598.3 | 3,459.9 | -3.7 % | [-5.8 %, -0.7 %] | yes |
| speedtest1-split | 12 | 7,614.6 | 6,856.7 | -11.1 % | [-13.1 %, -9.4 %] | yes |
| awfy-js-Bounce | 12 | 1,039.4 | 1,038.5 | +3.0 % | [-4.9 %, +5.1 %] | no |
| awfy-js-CD | 12 | 1,888.8 | 1,694.9 | -10.1 % | [-12.3 %, -6.3 %] | yes |
| awfy-js-DeltaBlue | 12 | 784.4 | 708.9 | -7.9 % | [-14.5 %, -3.6 %] | yes |
| awfy-js-Json | 12 | 1,965.1 | 1,805.0 | -8.2 % | [-10.8 %, -5.8 %] | yes |
| awfy-js-NBody | 12 | 554.2 | 494.3 | -10.2 % | [-13.4 %, -9.1 %] | yes |
| awfy-js-Richards | 12 | 4,039.8 | 3,549.6 | -11.3 % | [-16.3 %, -4.3 %] | yes |
| awfy-lua-CD | 12 | 11,998.9 | 11,459.9 | -4.6 % | [-6.2 %, -3.6 %] | yes |
| awfy-lua-DeltaBlue | 12 | 1,077.5 | 1,034.8 | -4.6 % | [-5.3 %, -1.4 %] | yes |
| awfy-lua-Json | 12 | 1,918.7 | 1,838.0 | -4.6 % | [-9.4 %, +0.4 %] | no |
| awfy-lua-NBody | 12 | 428.7 | 432.3 | -5.2 % | [-11.5 %, +10.5 %] | no |
| awfy-lua-Richards | 12 | 2,677.5 | 2,443.7 | -7.7 % | [-11.0 %, -5.6 %] | yes |

### Parse + runtime compile

| workload | n | upstream ms | fork ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 12 | 611.0 | 635.5 | +4.1 % | [+1.4 %, +6.6 %] | yes |
| lua-split | 12 | 643.0 | 664.8 | +3.4 % | [+1.0 %, +10.5 %] | yes |
| sqlite-split | 12 | 1,417.3 | 1,481.7 | +3.3 % | [+2.6 %, +5.4 %] | yes |
| quickjs-split | 12 | 1,297.9 | 1,326.1 | +2.9 % | [-1.1 %, +6.2 %] | no |
| python-split | 12 | 5,787.2 | 6,057.2 | +4.7 % | [+1.8 %, +6.7 %] | yes |
| binjgb-base | 12 | 555.1 | 564.3 | -0.1 % | [-2.6 %, +7.6 %] | no |
| binjgb-split | 12 | 535.8 | 568.3 | +3.5 % | [+1.8 %, +6.7 %] | yes |
| i8080-base | 12 | 341.5 | 349.3 | +1.3 % | [-0.6 %, +6.5 %] | no |
| i8080-split | 12 | 332.6 | 339.2 | +2.8 % | [-2.9 %, +7.6 %] | no |
| z80-base | 12 | 389.9 | 402.8 | +2.1 % | [-1.7 %, +5.7 %] | no |
| z80-split | 12 | 398.4 | 415.0 | +3.7 % | [+1.9 %, +6.2 %] | yes |
| genesis-base | 12 | 1,778.4 | 1,829.4 | +4.3 % | [-0.8 %, +6.2 %] | no |
| genesis-split | 12 | 1,575.6 | 1,622.7 | +0.4 % | [-0.1 %, +5.8 %] | no |
| rustvm-base | 12 | 473.4 | 491.2 | +1.9 % | [-5.6 %, +6.3 %] | no |
| rustvm-split | 12 | 458.4 | 478.0 | +2.4 % | [-1.6 %, +7.3 %] | no |
| fkernel-base | 12 | 487.1 | 513.8 | +3.4 % | [+1.1 %, +7.5 %] | yes |
| fkernel-split | 12 | 496.2 | 507.3 | +2.9 % | [-1.7 %, +5.3 %] | no |
| rv32ima-base | 12 | 279.4 | 284.3 | +1.9 % | [-0.7 %, +7.6 %] | no |
| sqlite-app-split | 12 | 1,582.4 | 1,652.2 | +3.8 % | [+1.3 %, +5.8 %] | yes |
| speedtest1-split | 12 | 1,663.5 | 1,748.2 | +3.5 % | [+2.2 %, +5.0 %] | yes |
| awfy-js-Bounce | 12 | 1,270.5 | 1,315.5 | +2.6 % | [+0.7 %, +3.7 %] | yes |
| awfy-js-CD | 12 | 1,262.3 | 1,273.9 | +2.3 % | [-0.1 %, +3.2 %] | no |
| awfy-js-DeltaBlue | 12 | 1,266.3 | 1,308.1 | +3.8 % | [+1.8 %, +5.1 %] | yes |
| awfy-js-Json | 12 | 1,241.2 | 1,282.8 | +2.8 % | [+0.5 %, +7.7 %] | yes |
| awfy-js-NBody | 12 | 1,235.6 | 1,271.3 | +1.0 % | [+0.4 %, +4.3 %] | yes |
| awfy-js-Richards | 12 | 1,235.6 | 1,277.7 | +3.7 % | [+1.2 %, +5.3 %] | yes |
| awfy-lua-CD | 12 | 712.1 | 734.8 | +5.9 % | [+3.8 %, +9.4 %] | yes |
| awfy-lua-DeltaBlue | 12 | 720.7 | 726.4 | +2.6 % | [-1.1 %, +3.6 %] | no |
| awfy-lua-Json | 12 | 714.8 | 744.6 | +3.2 % | [+0.3 %, +5.1 %] | yes |
| awfy-lua-NBody | 12 | 701.0 | 724.9 | +3.6 % | [+0.8 %, +5.3 %] | yes |
| awfy-lua-Richards | 12 | 707.9 | 724.3 | +3.5 % | [-0.7 %, +5.8 %] | no |

### Guest output identical across arms and samples (timings masked)

| workload | identical (samples) |
| --- | --- |
| lua-base | yes (24) |
| lua-split | yes (24) |
| sqlite-split | yes (24) |
| quickjs-split | yes (24) |
| python-split | yes (24) |
| binjgb-base | yes (24) |
| binjgb-split | yes (24) |
| i8080-base | yes (24) |
| i8080-split | yes (24) |
| z80-base | yes (24) |
| z80-split | yes (24) |
| genesis-base | yes (24) |
| genesis-split | yes (24) |
| rustvm-base | yes (24) |
| rustvm-split | yes (24) |
| fkernel-base | yes (24) |
| fkernel-split | yes (24) |
| rv32ima-base | yes (24) |
| sqlite-app-split | yes (24) |
| speedtest1-split | yes (24) |
| awfy-js-Bounce | yes (24) |
| awfy-js-CD | yes (24) |
| awfy-js-DeltaBlue | yes (24) |
| awfy-js-Json | yes (24) |
| awfy-js-NBody | yes (24) |
| awfy-js-Richards | yes (24) |
| awfy-lua-CD | yes (24) |
| awfy-lua-DeltaBlue | yes (24) |
| awfy-lua-Json | yes (24) |
| awfy-lua-NBody | yes (24) |
| awfy-lua-Richards | yes (24) |

