# Bounded transient packing feasibility

M3 experiment,25 October2026. This is a standalone synthetic kernel helper,
not an accepted inference profile. The first completed helper used APK5761d836…; model files are unchanged.
Matching-APK Ornith sampling found Q4_K/Q6_K dot91.49% self samples in one90s
window after512 tokens (`bench/m3-ornith-prefill-profile-20261025.json`).

The hypothesis is that prefill can reuse existing lossless CPU_REPACK GEMM kernels
with a temporary single-matrix copy instead of keeping the whole model in an
anonymous packed allocation. The helper measures allocation+pack+compute+free
against native, in alternating order, one warmup plus five pairs. Input generation,
activation/output allocation and readback are excluded; this is not whole-model
RAM, APK latency or end-to-end speed. The two paths also use distinct source
allocations in this synthetic harness. Integration has separate memory, lifecycle, accuracy, task, long-context and
app-speed gates. The later opt-in model integration below has completed its
bounded current-APK checks; native remains default.

Copies are limited to64MiB; actual buffer bytes are checked and reported. The
largest model linear shapes come from descriptors only, not inferred from byte
counts: Q4_K gate K4096/M12288 (28,311,552B), Q6_K down K12288/M4096 (41,287,680B).
Metadata/lock/header identity: `bench/prefill-pack-shapes-20261025.json`. Embedding
and output head are excluded. No GGUF payload values are used; synthetic original
Q4_K/Q6_K bytes are preserved across paths. Small batches1/3/4/5/16/128 exercise
GEMV/GEMM tails; the large matrices use128/512 activation rows to screen packing amortization.
The current app microbatch remains128;512 is an isolated kernel experiment.

Every full output is required bit-equal and finite. Up to64 unique output cells
are checked against independent F64 accumulation of dequantized weights and
native Q8_K represented activations using the existing numeric gates, unchanged.
A strict-bit failure is retained and never used for speed acceptance. It does not
by itself measure a whole-model quality regression. On host DOTPROD/8x4, Q4_K
showed small bit differences up to3.82e-6; Q6_K small cases passed. Phone I8MM
qualification is separate; host timing is not phone evidence.

The phone runner serializes under the workload lease, checks power/temperature/
boot, matches the installed APK, identifies the selected CPU library and verifies
its bytes against the APK. Only its own affinity is intersected with allowed
CPUs0–6; shell lifecycle/cgroup still differs from the app. The helper has a240s
own-process alarm even if ADB disappears; temporary files use a unique directory.
These are bounded feasibility conditions, not sustained/endurance acceptance.

```sh
.tools/cmake-3.31.6/bin/cmake --build app/.cxx/Release/84e6626c/arm64-v8a \
  --target motoai-prefill-pack-bench -j 4
python3 scripts/run-prefill-pack-bench.py \
  --binary app/build/intermediates/cxx/Release/84e6626c/obj/arm64-v8a/motoai-prefill-pack-bench
```

Build only this EXCLUDE_FROM_ALL helper target; do not rebuild/install the APK
while same-APK controls run. All13 existing native libraries were verified
unchanged after helper compilation. The original phone run completed successfully.


## Completed phone feasibility and memory rejection

`bench/results/prefill-pack-20261025T174235Z/report.json` passed all16 cases on
I8MM, including strict whole-output bit identity, unchanged F64 gates and power/
thermal/boot/relevant-frequency boundary checks. Median paired native/total ratios:
Q4_K N128=1.1892,N512=1.7990; Q6_K N128=1.06095,N512=1.55126. Original per-call
packing cost was about53ms(Q4) /74–76ms(Q6). Distinct source allocations and shell
lifecycle are limitations; these ratios are not application speed.

A separate APK with persistent Q4-only Ornith packing passed inverse-layout and
metadata preflight but its first scored request died with Android LOW_MEMORY,
RSS4.8GB. It is blocked in app admission. No retry or lowered memory guard.
`bench/m3-q4-repack-investigation-20261025.json` preserves the failed run.

Patch0009 now prototypes explicit per-op CPU workspace packing. It partitions
complete eight-row groups among the existing worker threads, reads native source
weights without mutation, and reuses the existing lossless repack GEMM. Graph
work_size includes packed weights plus Q8_K activation workspace; CPU backend
allocates one reusable maximum workspace rather than retaining every matrix.
Per-op packed bytes are capped64MiB and the request80MiB; prototype telemetry
reports actual pooled bytes separately from llama graph compute buffers.
It requires I8MM/NEON, contiguous2D Q4_K/Q6_K×F32,4–512 input rows and bounded
shapes. Unsupported/small/decode operations are never marked. The helper checks
source bytes/extra metadata after every pair and measures graph compute including
parallel repacking. No graph marker is set by model inference yet.


## Reusable CPU workspace screen and explicit model integration

The reusable-workspace operator screen passed on APK6cfbc207…:
`bench/m3-transient-workspace-screening-20261025.json` and
`bench/results/prefill-pack-20261025T180932Z/report.json`. All16 outputs were
bit-identical, F64 gates and source-byte checks passed. Actual pooled scratch
peaked48,464,128B (~46.2MiB). Big-matrix paired median native/candidate ratios:
Q4 N128=1.873,N512=2.018; Q6 N128=1.611,N512=1.775. These are within-series
kernel ratios; do not compare absolute timings against the earlier helper run.

Patch0010 introduces a default-false `cpu_transient_repack` context parameter.
Graph build marks eligible CPU matmul nodes before scheduling/reservation;
unchanged/decode/unsupported operations retain their native path. No global
switch. Graph-node counters include reservation/rebuilds and do not claim executed
kernel counts. Actual CPU scratch is reported separately from llama's managed
graph buffers. Runtime/app reserve81MiB (80MiB request cap plus padding), require
native explicit mmap, I8MM, <=8K/t4/b512/ub128; app experiment initially Ornith only.

Current model APK a41f7fd3… passed framework/JNI30 cycles. Code and Russian scored
corpora have zero token-NLL/top8 errors; completed code passed1096 tokens plus EOS,
134 cases and exact reference IDs/top8.8K retrieval passed with39 exact IDs and
zero top8 error; prefill912.586s is descriptive quality timing only. Current
operator guards passed10 metadata conditions plus12 small numeric cases. The
first extra guard attempt used an incorrectly classified contiguous block view;
it is preserved with its fixture diagnosis. No engine/numeric gate was changed.
No app-speed or default-profile acceptance. `bench/m3-transient-model-investigation-20261025.json`.


## Current measured policy and completed bounded app screen

Patch0011 keeps model matmuls below24 activation rows on native. The CPU numeric
API still admits4+ for independent operator tests. New APKe520cc2e… confirms tiny
4/5-token scoring with native1,179,904B scratch and real29/43-token packed scoring
with ~42MB scratch; all original NLL/top8 gates have zero error. Completed code,
8K retrieval and mixed128+23 /128+24 tails match the reference. Cancellation98ms,
identical subsequent output. Microbatches below the compiled minimum refuse
before model replacement.

The current-APK warm/preconditioned512-token prefill screen passed five measured
pairs:82.580→49.575s, median paired ratio1.649 (95%1.621–1.679). All relevant
frequency endpoints and numeric/power/foreground gates passed. This is not a
decode, cold-storage,8K-speed or product/default acceptance. Raw/aggregate:
`bench/m3-transient-threshold-screening-20261025.json`.
