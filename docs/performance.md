# Performance and limits

The current candidate uses CPU4/native mmap/no full prefault/F16KV/full SWA,
4K default, cacheOFF/transientOFF. Original GGUFs are unchanged. Final product
evidence and limitations are at [Current standalone product](#current-standalone-product26october);
older sections retain their original APK/profile scope and rejected experiments.
Do not pool timings across these checkpoints or treat partial thinking as a
completed answer. [User-facing description](app-description.md).

## Initial CPU baseline — 2026-10-24

Motorola Edge40Pro / SM8550 / Android16, foreground product APK. See
`bench/m2-baseline-20261024.json` for every value, APK hashes, thermal/battery
observations and raw artifact references. This is not a speedup or sustained claim.

Profile: same locked Q4_K_M files; configured8192, batch512/micro-batch128,
4threads, temperature0/seed42, thinking=true, F16KV, ordinary reads. Extra weight
buffers disabled because upstream KleidiAI can requantize Q6_K/Q8_0. One warmup
excluded, then five fresh-process trials per model. Storage cache was not reset.

| Model | Prompt/output tokens | First target token, median (range), ms | Backend decode, median (range), tok/s | Debug.getPss incl. swapped PSS, median, GiB |
| --- | --- | --- | --- | --- |
| gemma | 31/128 | 3366 (2954–3816) | 6.32 (5.70–6.80) | 5.54 |
| ornith | 30/128 | 6250 (6240–6347) | 3.42 (3.28–3.43) | 5.65 |

The128-token outputs are partial reasoning. Their timings are not completion
times for the coding task. First target token is measured from native request
entry; it is not first visible final-answer latency. Decode uses actual evaluator
counts (127 evaluations for these128 emitted length-limited tokens). PSS is an
endpoint, not peak resident RAM. VmHWM and VmSwap observations are in the raw trials.

All six greedy token traces per model matched (warmup + five measurements).
Host/app rendered prompts and input IDs matched exactly. Five requests do not
support a stable TTFT p95 estimate; observed variation is too large to infer a
small speedup. Long-context, complete-answer and thermal/endurance results must
be evaluated separately.

Earlier default-Kleidi/attempted-override profiles are excluded: see
`bench/excluded-profiles.json` and `docs/weight-buffers.md`. Do not pool their
numbers with this profile.


Observed scheduling/memory limits: all short-run endpoints reported importance100
and interactive=true, but the inference process allowed CPUs0–6. The earlier UI
capability probe allowed0–7. This is an observation, not an attribution to process
isolation: recheck online CPUs, thermal policy and binding/scheduling together.
One measured Gemma trial reported about1.7GiB VmSwap. Do not hide it or describe
this table as a fully memory-resident controlled benchmark; pressure contributes
noise. PSS and status snapshots are not simultaneous. Further profiling should
separate resident PSS, swapped PSS/RSS and their timing.


Memory metric clarification: AOSP's native `Debug.getPss()` adds swapped PSS to
resident PSS (and memtrack). The historical JSON field `completed_pss_kib` retains
that API value; do not treat it as resident PSS. See the
[AOSP implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/jni/android_os_Debug.cpp)
and [public API](https://developer.android.com/reference/android/os/Debug#getPss()).
The observed report/RSS differences prompted this review; a later collector
should expose resident PSS, swap PSS, RSS and snapshots with their own timestamps.


The first8064-input/128-output Gemma request timed out after900seconds without a
final report. It is not a passed8K or throughput result. Its diagnostics are
referenced in `bench/m2-correctness-20261024.json`. Short medians must not be
extrapolated to long-context latency. Completed add-function answers passed153
checks on each model; the original binary-search responses were incomplete
at1024 tokens. New longer-budget results below preserve those old failures.

## Resumed M2: completed answers and prefill diagnostics

The2048-budget lower_bound task reached EOS on both models: Gemma1919 emitted
tokens, Ornith1460. Each final-channel implementation passed134 functional cases
plus logarithmic access checks. Thinking remains enabled. This is one owned
coding task, not a benchmark-dataset accuracy claim.

| Gemma actual prompt | Prefill, seconds | Output budget | Scope |
| --- | --- | --- | --- |
|128 |10.788 |1 | Prefill diagnostic |
|512 |46.696 |1 | Prefill diagnostic |
|2048 |197.940 |1 | Prefill diagnostic |
|4096 |397.760 |1 | Prefill diagnostic |
|8064 |882.030 |128 | Filled8K mechanical pass; decode31.929s |

These are single trials on explicitly identified diagnostic APKs, with progress
and finite-logit checks. They are not pooled with the five-trial baseline.
The8K trial's first token arrived at882.107s; total native request about914s.
At903s it was decoding token86/128. Thus a900s watchdog can terminate a working
request. The original run's exact phase is unknown. No usable interactive8K
profile or sustained/long-context quality acceptance follows from one completion.
See `bench/m2-resume-20261024.json` and `docs/m2-investigation.md`.

A separate10-second user-mode simpleperf sample inside the first512-token
prefill call contained15862 samples, zero lost. Self samples: Q4_K dot61.73%,
Q6_K dot15.98%, BF16 dot12.15%, Flash Attention3.59%, F32 dot2.62%.
The selected library was `libggml-cpu-android_armv8.6_1.so`; extra weight buffers
were disabled. These window-specific fractions identify CPU matmul candidates;
they are not whole-request fractions or an optimization result.
Raw/profile summary: `bench/cpu-prefill-profile-20261024.json`.

The separate7680-input beginning-needle task returned the exact identifier and
EOS (12 emitted tokens,13 finite checks): prefill783.530s, decode2.626s.
Raw: `bench/results/app-service-gemma-20261024T200043Z-0f7234c0/`.
This one retrieval point and its diagnostic APK are separate from the8064+128
mechanical run and original repeat baseline. No multi-position or interactive
8K acceptance follows from it.

Later repeatability collection also exposed an Ornith load killed by Android
LOW_MEMORY, despite an earlier successful complete answer. Read mode is therefore
not accepted as a reliably loadable profile under varying memory pressure.
Neither increasing the watchdog nor a previous successful run resolves this.

## Recovered-phone corrected CPU checks

The one-shot-guard APK (bfc9cab0…) shares native bytes with the corrected numeric
candidate43bd53a9…. Fresh lower_bound answers completed to EOS: Ornith1096,
Gemma1861 emitted tokens, all raw-logit scans finite and134 function/complexity
cases passed each. These are single quality runs with diagnostics, not paired
speed or sustained acceptance. Exact artifacts and GELU branch control are in
`docs/m2-investigation.md`.

The inference process reports `Cpus_allowed_list: 0-6` in these app-service
trials. MainActivity currently binds with BIND_AUTO_CREATE. This observation is
not proof that a particular binding flag can enable CPU7, or that using it would
be faster. M3 should record allowed CPUs and actual scheduling for each foreground
or background scenario before selecting affinity; never attempt a disallowed CPU
or change global scheduling policy.


## Corrected-reference short baseline (25 October)

`bench/m2-cpu-reference-baseline-20261025.json` retains one warm-up and five
fresh-process measurements per model, current APK bfc9cab0…, ctx8192/t4/b512/
ub128/F16 KV/mmap, no extra weight buffers, original mixed GGUF weights.

| Model | Median ready-model TTFT | Median backend decode | Median fresh-process load |
| --- | --- | --- | --- |
| Gemma |3.425s |5.886 tok/s |1.307s |
| Ornith |6.203s |3.346 tok/s |1.306s |

These are the declared short-code128-budget workload, not complete-answer
quality or sustained/deep-context speed. Cache state is unknown; fresh process
is not cold storage. Raw trials include power/thermal conditions, token traces,
controller/collector source snapshots and bootstrap median intervals. Do not pool
with earlier B0/B1 or claim a speedup from these different-APK diagnostic series.
Full/deep input remains slow: corrected Gemma8064+128 prefill1001s/decode50s;
Ornith7680+512 beginning-fact retrieval prefill1377s/decode18s,39 emitted tokens.
The latter changed charging state2→4 and is excluded from comparable-power speed
by `bench/recovery-power-audit-20261025.json`; correctness remains passed.

## M3 Gemma CPU_REPACK: paired APK screening (25 October)

APK `a02b4206b5e7f53bbdf16768f45066ed5151c3ccba3108c7685cbbaa223adede`,
variant `f32-recurrent-repack-order`, KleidiAI compiled OFF. Same-APK native
vs CPU_REPACK, one warm-up pair then five measured pairs, alternating order.
Same locked GGUF, ctx8192/t4/b512/ub128/F16 KV/mmap, short-code128-budget fixture,
thinking and every generated ID/finish reason unchanged. Model checksum was
verified under app UID. Stable power, thermal status below2 and starting battery
temperatures within2°C were required per pair. No other phone workload ran.

| Median measured value | Native CPU | CPU_REPACK |
| --- | --- | --- |
| Actual token emission after the first token |5.853 tok/s |6.184 tok/s |
| Backend decode |5.875 tok/s |6.207 tok/s |
| Client request to first nonempty piece, loaded model |3.352s |2.116s |
| Native load |2.154s |5.584s |
| Native load + native TTFT (excludes app startup) |5.470s |7.476s |
| Completed process PSS |5.189GiB |3.397GiB |

Median **paired** emission speed ratio is1.08245 (about+8.2%), bootstrap95%
median interval1.0360–1.1753. Paired first-piece ratio is1.6164, interval
1.2096–1.9932. These are medians of the five pair ratios; they differ from ratios
of the separately computed profile medians in the table. This is a small short
screening series, not sustained/deep-context acceptance. Loading is materially
slower; do not advertise this as a faster one-off startup. Cache state remains
unknown, and fresh process does not mean cold storage.

Token cadence uses measured emission intervals, including sampling and prior
output-callback work. First-piece timing includes Messenger/worker/JNI transport
and counts thinking output. Backend-only throughput is listed separately.
The profile also changes source-page residency during loading; its RAM/load
differences cannot be attributed to packed layout alone. Native mmap without
full prefault is a planned separate control.

Quality screening covered code/Russian NLL/top8 (zero difference), the complete
Gemma lower_bound answer (1861+EOS,134 cases, exact original IDs) and8064+128
(128 finite scans, exact original IDs/first top8). The long comparison reuses a
preserved corrected native Android artifact **only for quality**, never speed.
This is not whole-model/corpus,16K/32K or endurance acceptance.

Evidence: `bench/m3-gemma-repack-screening-20261025.json` and
`bench/results/weight-buffers-gemma-short-code-mmap-v1-20261025T081520Z/report.json`.
All measured trials used one stable device-collector source hash. No speed
profile/default has been promoted. Full Ornith repack failed fresh loading and
is excluded; passing teacher-forcing and one completed task did not prove its
repeatability. The separate selective candidate is described below.

## M3 selective Ornith correctness checkpoint

APK `9500cbaf528b30d6dce92ab8287c99161e191dc847876ce7ea0908980251e45b`,
variant `f32-recurrent-repack-selective`, `weight_buffers=cpu_repack_q6`.
Only eligible original Q6_K tensors are packed; all other types remain native.
Actual packed allocation is1,839,022,080 bytes. Three fresh native/selective
load/score pairs and Russian corpus passed with zero NLL/top8 difference.
The final APK's complete lower_bound task emitted1096+EOS, passed134 cases and
matched every original generated ID/first top8 exactly.

The7680-input/512-reserve exact-fact retrieval also passed:39 emitted tokens+EOS,
40 finite scans, exact identifier and all generated IDs/first top8 identical to
the preserved native Android result. Prefill1261.438s, decode18.198s are **single
correctness-run timings**, not a paired speed comparison or interactive acceptance.
This retrieval does not fill the whole8192 boundary with512 emitted tokens.

During long prefill, sampled process VmSwap peaked at1,119,436,800 bytes (about
1.04GiB); final VmSwap was110708KiB (about108MiB). The process remained alive,
but this is a real long-context memory/performance limit. Do not describe lower
RSS caused by swapping as memory freed. The Gemma full-repack long correctness
case had prefill583.152s/decode42.866s and sampled peak swap about15.8MiB. Those
different-model/older-APK results are not a before/after speed pair.

Memory audit: `bench/m3-long-context-memory-20261025.json`. Selective quality lock:
`bench/results/m3-selective-qualification-final-20261025T083830Z/quality-gate.json`.

### Selective Ornith: completed paired short-speed screening

Same final APK and Q6-only policy above, one warm-up pair then five measured
alternating pairs, ctx8192/t4/b512/ub128/mmap/F16 KV, short-code128-budget fixture.
All generated IDs and finish reasons matched within and across pairs. Each pair
passed power/thermal and starting-temperature gates. All ten measured trials
ended screen-interactive with process importance100; one device-collector source
hash was used. Starting battery temperatures spanned41–43°C over the series.

| Median measured value | Native CPU | Q6-only CPU_REPACK |
| --- | --- | --- |
| Actual token emission after the first token |2.837 tok/s |2.812 tok/s |
| Backend decode |2.844 tok/s |2.818 tok/s |
| Client request to first nonempty piece, loaded model |7.435s |8.162s |
| Native load |2.431s |5.363s |
| Native load + native TTFT (excludes app startup) |9.788s |13.440s |
| Completed process PSS |5.650GiB |5.121GiB |

Median paired emission ratio is0.98891 (about−1.1%), bootstrap95% median interval
0.89468–1.11633: **no demonstrated speed gain**, and insufficient evidence to claim
a reproducible decode regression of that exact size. Paired first-piece ratio
is0.91512, interval0.86820–1.32722. Native-load-plus-native-TTFT ratio is0.71792,
interval0.66136–0.98275. Loading/startup is worse in this bounded series.
As above, pair-ratio medians differ from ratios of the table's separate medians.

Sampled process swap peaked at about17.0MiB for native and up to81.3MiB for Q6-only
across these short measured trials. PSS alone is not a total memory-win claim,
and source-residency effects remain combined with repack. Storage cache state is
unknown. These temperatures/results do not establish sustained battery or heat
behavior; the bootstrap interval from five pairs does not cover all workloads.

Evidence: `bench/results/weight-buffers-ornith-short-code-mmap-v1-20261025T090958Z/report.json`.
The final controller passed its bounded checks; its guard-result metadata path
was corrected transparently, preserving `report-collected.json` and all raw child
artifacts. Full Ornith repack stays excluded; selective remains an experiment.
Native remains the default. No sustained, whole-corpus,16K/32K or product-profile
acceptance follows. Next isolate native mmap without whole-file prefault before
attributing loading/RAM differences to repack or selecting a profile.


## Native mmap prefault control — paused25 October2026

Same APK `5761d83665d629aeed98af08c03dbcf2cb5ca572dd1b1c0827fbee2a5ad49be7`,
variant `f32-recurrent-native-demand`, KleidiAI OFF, actual native buffers for
both arms; only initial mmap prefault/WILLNEED differs. GGUF/F16 KV unchanged.
Ornith input7680, context8192, output reserve512, t4/b512/ub128; all four fresh
process requests completed39 tokens+EOS. Opposite pair orders were true/false,
then false/true. Both pairs passed bounded quality and power/thermal/screen gates,
starting battery temperature40C; token IDs match across every trial and first-top8
shared logit error is0. Native allocation plans match. Corpus NLLs and completed
code task also passed the same control. SHA checks precede series: file-cache
state is uncontrolled, not cold storage.

| Measured value, in pair order | Native prefault=true | Native prefault=false |
| --- | --- | --- |
| Native load |1.410 /2.607s |0.923 /0.844s |
| Prefill |1373.471 /1378.299s |1375.690 /1373.499s |
| Process VmHWM over request |5.738 /5.686GiB |5.226 /5.228GiB |
| Sampled peak process swap |90.90 /103.63MiB |16.98 /16.93MiB |
| Completed process PSS |5.339 /5.191GiB |5.141 /5.142GiB |

**No demonstrated prefill speed gain.** Lower loaded residency/peak process RSS
is useful evidence about this control, not equivalent system RAM savings. No
warmup+five short speed pairs, Gemma control or endurance acceptance yet. One
cannot promote load timing from these two long quality pairs to a release profile.

Slow ~23-minute prefill persists without the earlier Q6-only1GiB process swap.
Observed body CPU/wall near4 includes barriers/spin; fault/read deltas vary by
chunk and final-output-head access. Aggregate swap cannot identify tensor pages
or stall time. Separate CPU sampling is required before naming the hot operation.
Counters and observed token coverage are retained in the analysis; the final
prefill-to-first-decode bridge includes one generated token, not pure prefill.

The user requested a pause after12:00: the active long series completed at12:16,
the dispatcher terminated, and the inference process stopped. No later workload
started. Original controller report is preserved incomplete; the explicit
[checkpoint](../bench/results/m3-native-demand-20261025T093522Z/pause-checkpoint.json)
collects its completed child reports without fabricating controller acceptance.
[Analysis](../bench/m3-native-demand-long-analysis-20261025.json),
[method and resume commands](mmap-prefault.md),
[phone cleanup](../bench/device-health-after-native-demand-20261025.json).


### Evening continuation: Ornith short series

Same APK/policy/config quality lock reused; completed long requests were not
repeated. Warmup+five alternating pairs passed unchanged token/work, numeric,
power/thermal and process/screen gates. Battery was charging throughout; measured
pair starting battery temperatures37–43C. These conditions differ from the morning
series and no timings are pooled across sessions/APKs.

Median paired emission ratio (demand/native)0.97984, bootstrap95% median interval
0.85921–1.04281: **no demonstrated decode gain**. Separate emission medians were
2.891/2.901tok/s (native/demand); separate medians do not equal the median of ratios.
Native load medians2.123/1.004s; native-load-plus-native-TTFT10.494/9.167s.
The paired load+TTFT speed ratio1.17523 has interval0.71464–1.28646, so the apparent
startup improvement is not established across this small noisy series. Client
first-piece ratio1.02534, interval0.60511–1.10924, likewise inconclusive.

Pair4 is retained: both arms slowed and sampled process swap reached360–362MiB.
The demand arm's prefill-start to decode-phase callback bridge read5,055,041,536B
with27,813 major faults, versus23,273,472B/906 faults for native. This bridge is not
the exact native prefill interval; short fixtures do not enable per-token debug
callbacks. Counters suggest substantial refault/read traffic in this outlier,
not proof of swap causality or a constant cold-storage cost. No outlier exclusion
or selective retesting. Actual transient peaks can exceed sparsely sampled values.

Evidence: `bench/results/weight-buffers-ornith-short-code-mmap-v1-20261025T161939Z/report.json`,
its `pair4-phase-diagnostics.json`, `bench/m3-native-demand-ornith-analysis-20261025.json`.
Gemma control and separate matching-APK Ornith sampling are still pending/running;
M3, default-profile and endurance acceptance remain incomplete.


### Matching-APK Ornith CPU profile

Separate2048-input diagnostic completed;90s task-clock:u sample after512 tokens,
71,059 target-process samples, zero kernel/userspace loss, verified APK/source/
recorded DSO build IDs. Selected `libggml-cpu-android_armv8.6_1.so`. Window self
fractions: Q4_K dot71.36%, Q6_K dot20.13%, Flash Attention2.36%, graph-compute
thread1.66%, gated-delta1.24%. This supports quantized matmul as the primary
candidate in this window. It does not measure whole8K fractions or tensor-level
swap/stall causality; sampling timings remain excluded from speed acceptance.
Per-thread reports retain sequential worker TIDs; distinct IDs across graph
thread-pool lifetimes do not establish simultaneous oversubscription.

Evidence: `bench/m3-ornith-prefill-profile-20261025.json`,
`bench/results/prefill-profile-ornith-20261025T163211Z/report.json`.
A bounded allocation+repack+GEMM+free kernel feasibility helper is built separately;
it changes neither installed APK nor model weights. Subsequently qualified within the bounded kernel scope below.


### Frequency-cap audit of earlier M3 series

Later inspection found varying CPU `scaling_max_freq` ceilings despite public
Thermal Status0. Current same-boot policy topology maps CPUs0–2 to policy0,
CPUs3–6 to policy3 and excluded CPU7 to policy7. Only policies intersecting the
recorded app allowed set are compared; unused policy7 changes do not disqualify it.
Missing readings remain unknown. Boundary equality is not clock residency and
cannot establish that a cap never changed inside a request.

The post-hoc audit found equal relevant ceilings at all four A/B boundary
snapshots in4/5 Gemma full-repack pairs,4/5 Ornith Q6-only pairs and3/5 evening
Ornith demand-paging pairs. Raw trials, ratios and bootstrap intervals remain
unchanged; no subset-only gain or recomputed confidence interval is promoted.
In particular, the earlier observed Gemma+8.2% is a promising bounded observation,
with a frequency-confounded pair, requiring a fresh controlled repeat before
causal speed/default-profile acceptance. Quality results are unaffected.

Evidence: `bench/m3-frequency-cap-audit-20261025-v2.json` and
`bench/cpu-policy-topology-20261025.json`. The initial audit lacked a fallback to
preserved allowed-CPU fields in older raw results; its missing-data count is
explicitly corrected in `bench/m3-frequency-cap-audit-20261025-correction.json`.
The future transient-pack phone helper prospectively checks relevant ceiling
snapshots as well as power/temperature/boot; no governor or global setting changes.


### Completed native-demand control (25 October evening)

Both-model control on APK5761d836… is complete:
`bench/m3-native-demand-analysis-20261025.json`. All bounded quality comparisons
passed; it is not M3/endurance acceptance. Gemma filled8192 used8064 input+128
output: prefill1201.281s(native prefault) vs1198.746s(demand), no useful gain.
Sampled process swap180.8→18.6MiB; loaded PSS4859→589MiB, end PSS3.421→3.389GiB.
These process figures do not establish equal freed system RAM.
Gemma short emission ratio1.00099 (95%CI0.97902–1.00848), no decode gain.
Load+native-TTFT ratio1.10659(CI1.06729–1.15575), while loaded-model client first
piece ratio0.97194(CI0.95948–0.97767), about3% slower.5/5 relevant frequency-boundary
caps matched. Cache remained uncontrolled after app-UID SHA reads; no cold claim.
Ornith short/long controls likewise do not establish useful prefill/decode gain.

Standalone transient-pack feasibility on phone passed16 synthetic cases; Q4_K
N128/N512 native-to-candidate ratios1.189/1.799, Q6_K1.061/1.551, including
allocation/packing/free. Not an app gain. Persistent Q4-only Ornith was rejected
on first scored request: Android LOW_MEMORY, RSS4.8GB. Evidence:
`bench/m3-q4-repack-investigation-20261025.json`. Next experimental path bounds
per-op workspace rather than retaining3.2GB of Q4 packed weights.


### Reused bounded workspace (operator screen)

`bench/m3-transient-workspace-screening-20261025.json` passed16 phone cases on
APK6cfbc207…: exact output bits, original F64 gates, source bytes/metadata unchanged,
power/thermal/boot/relevant-frequency boundary checks. Largest actual CPU scratch
48,464,128B (~46.2MiB). Big Q4 N128/N512 within-series paired ratios1.873/2.018;
Q6 1.611/1.775. No application gain follows from those kernel ratios.
Patch0010 now connects this path to an explicit default-false context option.
Model APK a41f7fd3… passed framework, code/Russian scored corpora with zero error,
and completed code with1096 exact reference IDs/134 cases. Long context remains
in progress; no matching-APK speed screen has started. Scratch receives a separate
81MiB admission reserve and actual-byte telemetry; native weights/F16 KV remain.


### Model policy: pack only at24+ activation rows

Two further bounded phone kernel curves preserve every sample and outlier:
`bench/m3-transient-amortization-20261025.json`. Selected paired medians below
come from separate controlled runs and are kernel-only, not an app-speed curve.

| Activation rows | Q4_K native/workspace | Q6_K native/workspace |
| --- | ---: | ---: |
|4|0.425|0.415|
|8|0.660|0.637|
|16|0.994|0.940|
|24|1.200|1.115|
|32|1.366|1.224|
|64|1.611|1.455|

The Q4 N20 minimum0.604 is retained; choosing a threshold must not hide it.
At24, all five ratios exceed1.05 for both types in the follow-up. Patch0011
therefore keeps the model's smaller matmuls native while the numeric kernel API
still supports4+ rows for its own correctness tests. App/core reject a configured
microbatch below the compiled minimum before replacing the existing model.

Current APKe520cc2e… passed tiny native4/5-token scoring(1,179,904B scratch),
real packed29/43-token scoring(~42MB), completed code1096+EOS/134 cases,8K
retrieval39 exact tokens and zero measured NLL/top8 errors. Stop-to-ready98ms,
identical recovery output. Long quality prefill928.115s is descriptive only.
The separate current-APK screen has now completed; see the result below. Older
a41f7fd3…82.274→49.935s/median1.641 was not transferred to this APK. Decode remains
unqualified because stock frequency ceilings changed during the short screens.


### Current24-row APK: accepted bounded prefill screen

APKe520cc2e… completed the separate native2048 preconditioning, excluded warmup
and all five counterbalanced512-input/one-output pairs. Median prefill native
82.579678s vs workspace49.574804s. Median paired ratio1.649215, bootstrap95%
interval1.621229–1.679072. Loaded-model client first piece82.646915→49.638886s;
paired ratio1.648306. Native load+TTFT84.215550→51.021679s, paired ratio1.635892.
All measured IDs/top8, relevant frequency endpoints, power/temperature and
foreground states passed. Source weights/F16 KV unchanged; scratch reserve81MiB,
observed43,081,984B. Original native default is unchanged.

`bench/m3-transient-threshold-screening-20261025.json` freezes the report/quality
lock hashes. Scope is warm/preconditioned foreground CPU on the tested phone;
no cold-storage, clock-residency, decode,8K-speed, default/product or full-M3 claim.
8K quality prefill remains928.115s. Decode screens stopped at stock frequency
changes and are retained without speed acceptance. Next: decode/threads/affinity,
remaining backends and sustained validation before UI/API promotion.


## M3 CPU scheduling controls (25 October, ongoing screening)

`threads` controls decode. Optional `threads_batch` defaults to0 (follow decode);
`cpu_affinity` is an optional sorted CPU-index array. Empty preserves the Android
scheduler. Nonempty requires an exact subset of the caller's current allowed set
and enough CPUs for both pools. No implicit intersection, prime-core assumption,
priority/governor change, or weight/precision change. An explicit batch count or
mask creates CPU pools through the pinned backend registry; one shared pool when
settings match, separate paused/resumed pools otherwise. Their lifetime surrounds
the borrowing context. The calling executor's affinity is restored at API exit.

`cpu_scheduling` reports the allowed caller CPUs and post-request masks for the
caller plus each newly created pool worker. Ambiguous worker inventory or a mask
mismatch fails closed. This verifies placement, not CPU clock residency.

Runner: `python3 scripts/run-decode-screen.py`. Prospective configurations are
in `bench/decode-profiles-20261025.json`; fixture preparation remains reproducible
through `scripts/prepare-fixtures.py`. Each configuration scores the frozen code
and Russian continuations before paired timing. Screening: one excluded A/B
warmup, then one B/A measured pair. All IDs/finish reason and the unchanged numeric
gates must agree. Confounded short-screen timings are retained, never promoted.
`--strict-speed --repeat 5 --only PROFILE --fixture decode128-short-v1` requires
five comparable measured pairs; bootstrap statistics remain scoped to that
request, APK and phone. `--transient` explicitly uses bounded prefill scratch in
both arms and must satisfy the existing experimental admission bounds.

Short/512-input decode fixtures use64/128 output tokens solely to standardize
measured work; these are not product response limits. Endurance and broader
quality/context checks remain separate from this bounded scheduling screen.


## Thread-independent F32 decode attention candidate

Changing decode workers4→7 originally changed KV partition count once padded
KV reached512. Isolated host and installed-phone tensors reproduced up to5.96e-8
roundoff while all independent F64 gates passed. Completed Ornith code shared179
tokens then branched (1096→1215 outputs); both functions passed134 cases. That
trajectory gate remains failed; there is no broad quality or speed acceptance.

Patch0013 in `f32-recurrent-stable-attention-v2` keeps the validated4 F32 KV
chunks/reduction order, dispatching independent(head,chunk) work across workers.
The planner reserves enough partial slots for at least4 chunks even with1 worker.
Other precisions keep their original grouping. Prefill4 math is preserved; no
claim that arbitrary prefill-thread changes are bitwise invariant. GGUF bytes,
quantization and F16 KV stay unchanged. Load metadata identifies the policy as
`attention_f32_kv_chunks=4`; this is a compiled policy, not a user-facing knob.

Before install: `bench/results/attention-thread-check-20261025T221114Z/report.json`
passed128 replay runs/16 cases, including real257/padded512, odd513,8192 keys,
128 query heads and unequal DK/DV. Old4 phone results are exact, and all tested
1–7 decode outputs match; original F64 limits are unchanged. The rejected0012
prototype failed host F64 due to incorrect partial-pointer indexing and was
never installed. Its variant/report stay immutable; corrected0013 passes.

Current candidate APK24be50e1… has13 symbol/APK strip matches and static16KiB
alignment; actual16KiB-device execution remains untested. Full-model4 old/new
and7/new4 controls plus a fresh speed series must pass before any tuning claim.
Earlier single-pair and frequency-confounded series do not qualify this APK.


### Final scheduling checkpoint: quality passed, speed unqualified

On24be50e1… both new4 vs accepted old4 and new7 vs fresh new4 completed exactly
1096 generated IDs/EOG, first-top8 error0 and134 functional cases. The current
framework passed30 default +30 explicit7/4 cycles, returning14 tasks/118 FD after
each sampled unload. No original GGUF/quantization/F16-KV changes.

The fresh current-APK warm128-output series stopped at measured pair3. Comparable
pairs1/2 were1.04203 and1.05345 emission ratios; pair3's1.13770 was rejected because
candidate policy3 cap1286400→1171200 and subsequent baseline1171200. No bootstrap
interval or accepted gain is reported with only2 comparable pairs. Default4 stays.
All failed pairs, warmups and earlier APK data remain separate. Details:
`bench/m3-decode-screening-20261025.json`; raw
`bench/results/decode-screen-20261025T222930Z/report.json`. This is not sustained
acceptance; next experiment must prospectively compare matched-start long runs
under natural OEM adaptation, rather than retrying short pairs to a favorable cap.

## Current standalone product:26October

M3's bounded comparison is closed on this Edge40Pro/two original GGUFs: CPU4
retained; no sustained7-worker promotion, Vulkan OEM compiler crash and OpenCL
NLL regression excluded those GPU paths. See `bench/m3-comparison-acceptance-20261026.json`.
Current app default: native mmap/no full prefault, F16 KV, full SWA,4workers,
4K context, cacheOFF and transient workspaceOFF. No weight requantization.

Actual16K retrieval controls used **explicit experimental bounded transient
packing ON**, cacheOFF, CPU4 and frozen731a APK. Ornith15,872input+106output/EOS:
prefill2117.77s/decode66.67s, completedPSS5,691,527KiB/swap107,544KiB. Gemma15,872
input+27output/EOS:1628.75s/14.00s, PSS4,036,925KiB/swap18,792KiB. Both exact
ordered3-depth identifiers and finite logits. These are one filled fixture per
model, not arbitrary QA/context quality, a default-OFF timing, interactive speed
or cold-load guarantee.32K actual trials are recorded separately in progress.md.

Actual Aider0.86.2 two-turn coding on original Ornith passed via phone LAN TLS,
110.33s and616.87s, no cloud fallback, code regression checks passed. This is
functional acceptance with slow turns, not a benchmark against desktop inference.
`bench/results/aider-phone-20261026T034552Z/report.json`.

Final-kernel Aider repeat also passed on d281 via explicit developerUSB opaque
TLS relay:156.30s/651.09s,0reflections, both edits applied and networkless code
checks passed (`aider-phone-20261026T120319Z`).8K/CPU4/cache128MiB/transientOFF/
thinkingON/2048reserve, not4K default timing or paired speed comparison. Current
Wi-Fi subnets differed; no fresh direct-LAN reachability claim. Prior real Wi-Fi
acceptance is retained. See api.md for transport boundaries and failed preflights.

Final same-engine B/P/P/B product UI observation **passed for both models**
on d281 (all14 native libraries byte-identical to final release3af31). Both arms
kept the same unlocked, interactive Activity with actual window focus, one loaded
CPU4/4K model,128-token public request/cacheOFF/transientOFF. Only product uses
real composer/RAM-dialog/render. One16-token warmup excluded. Raw output, counts
and finish were identical.128 is deliberate measured work, not a product limit
or a completed-answer quality gate.

| Model | Baseline wall seconds | Product wall seconds | Paired product/baseline | Median ratio |
| --- | --- | --- | --- | --- |
| Ornith |35.801 /34.157 |35.187 /35.823 |0.9829 /1.0488 |1.0158 |
| Gemma |21.976 /22.649 |22.772 /22.787 |1.0362 /1.0061 |1.0212 |

Reports:`ui-performance-ornith-20261026T115653Z` and
`ui-performance-gemma-20261026T120028Z`. Two short pairs are an overhead
observation; they cannot qualify a speed/default promotion or arbitrarily long
histories. Notification grant/deny with real phone SystemUI tap also passed
`background-phone-20261026T115321Z`; the owner unlocked the secure keyguard
normally. Earlier keyguard setup failures are retained.

Ornith32K PASS: `context-ornith-32768-20261026T040306Z/report.json`. Actual32256
input+102generated/EOS; exact final ordered identifiers, finite logits. Prefill
6092.27s, decode100.95s (~1.01token/s during decode), completedPSS6,225,044KiB
(~5.94GiB), swap115,864KiB. Frozen731a, transientON/cacheOFF/F16KV, phone battery
39→40°C, AC connected/adaptive80%, unchanged boot. This one very slow filled
retrieval is not arbitrary long-context quality or an interactive product default.

Gemma32K PASS: `context-gemma-32768-20261026T055945Z/report.json`. Actual32256
input+27generated/EOS; exact final ordered3 identifiers, finite logits, original
GGUF/F16KV, frozen731a/transientON/cacheOFF/fullSWA. Prefill4585.58s (~76.4min),
decode21.13s, completedPSS4,959,819KiB, VmRSS4,695,704KiB, swap352,440KiB.
Battery35→40°C, AC/adaptive80%, same boot. Like Ornith32K this is one very slow
retrieval, not arbitrary QA quality, interactive speed or a default promotion.
Earlier setup failures were install/late-package-commit launch failures and are
preserved separately; the stable run verified the installed APKs without reinstall.

## Final native kernels: default4K point,26October

All14 libraries in actual G release3af31 match instrumented Linux d281 exactly.
Independent phone replay passed12 unchanged short attention controls plus12long
configurations/20runs (`attention-long-phone-20261026T071652537070Z`); extended
matmul passed240 cases with120 repack bit comparisons,0nonidentical
(`numeric-20261026T072246Z`). Original fixed gates, no weight/activation-policy
substitution. These operator controls do not certify arbitrary whole-model quality.

Filled4K **default settings** (transientOFF/cacheOFF, CPU4/native mmap/no-prefault,
fullSWA, original GGUF/F16KV) both returned exact ordered3 identifiers andEOS:

| Model | Input/output | Prefill | Decode | completedPSS KiB | VmSwap KiB |
| --- | --- | --- | --- | --- | --- |
| Ornith |3584/74 |562.96s |27.78s |5,251,917 |112,040 |
| Gemma |3584/136 |423.28s |43.91s |3,310,876 |192,508 |

Reports:`context-ornith-4096-20261026T072252Z` and
`context-gemma-4096-20261026T073309Z`. Each is one actual filled retrieval, not
five-pair speed acceptance or arbitrary dialogue quality. PSS retains Android's
Debug.getPss interpretation explained above; these are endpoints, not peak RAM.
Do not compare these default-OFF timings directly with16K/32K packingON as if
context length were the sole changed variable. Large prefill remains slow.
Owner resumed after the morning pause. Actual foreground UI and phone background
checks passed as recorded above; final-kernel Aider/release/source handoff continue
with live state in progress.md.
