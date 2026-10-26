# Weight buffer policy

Pinned source: llama.cpp `701fd25b3a55a90297dec0d925204b29084054c8`.

M2 source review found a non-lossless path in
`ggml/src/ggml-cpu/kleidiai/kleidiai.cpp`, around lines1537–1596:
Q6_K blocks are dequantized to float; a new scale `max_abs / 127` is selected per
row and weights are rounded/clamped into int8. Q8_0 blocks also get a new per-row
scale. Identical GGUF bytes therefore do not prove identical represented weights
inside the selected backend. A passing loose numeric tolerance would not turn
this operation into a lossless repack.

The initial M1 runs and first M2 series used upstream defaults. They remain
mechanical integration evidence, not proof of weight-preserving computation.
Their performance is excluded from the strict baseline. APK hashes are recorded
in `bench/excluded-profiles.json`; do not pool their numbers with a new profile.

A reviewed attempt to force individual tensors to `ggml_backend_cpu_buffer_type()`
was insufficient: `src/llama-model-loader.cpp`, lines1247–1251, treats this override
as permission to reconsider extra CPU buffer types. The tied Gemma output tensor
keeps the original tensor name, but that does not solve the CPU override semantics.
The attempted profile was never accepted and is also excluded by APK hash.

The default conservative runtime sets `model_params.use_extra_bufts = false`.
CPU dispatch remains enabled; all original weight types and F16 KV stay explicit.
The load result records `all_extra_buffers_disabled=true`. This is a correctness
baseline, not a claim that all compute is exact FP32: ordinary quantized CPU ops
have their documented internal activation conversion and finite arithmetic.

The dedicated Q4_K KleidiAI packer consumes original blocks and may be a useful
candidate. Re-enable selective packing only after a supported selection policy,
independent numeric/representation tests, fixed-input model quality checks and
real APK measurements. Preserve the pristine upstream B0 and retain any future
upstream changes as an explicit patch series. Do not silently relax this policy
for a higher tokens/s number.

For M3, enabling all extras and overriding “CPU” is still unsafe: upstream treats
that override as permission to reselect extras. Prefer an explicit allowlist with
CPU_REPACK only, checked against actual selected buffer names/bytes. Its27 numeric
cases are arithmetic evidence, not a direct inverse-pack proof for all weight
representations. Verify packed scales/codes or a full inverse-pack comparison
before calling the model's representation preserved.

Repacked weights use committed buffers. They cannot inherit the native mmap
admission estimate's1/2GiB clean working window. Account for packed allocations,
original tensors still mapped, tied embeddings/output copies and peak transient
source/packing memory; qualify a new admission profile before enabling it. Keep
the corrected no-extra-buffers candidate as the comparison reference.

## Initial M3 feasibility (25 October)

`bench/m3-cpu-repack-preflight-20261025.json` records the first bounded screening.
The independent inverse-layout audit recovered every original Q4_K/Q6_K byte in
22 cases per CPU: random/zero/all-one bit patterns across row/block boundaries,
plus samples of each type from both GGUFs. Host used8x4 packing; APK libraries
used8x8. This covers the two types selected for packing in these model plans,
not every GGML format, tensor shape or model output. Matmul/NLL/task gates remain
separate; no optimized model generation or speed win has been accepted.

Metadata-only read-layout simulation predicts packed allocations2,746,060,800
bytes for Gemma and5,041,766,400 for Ornith. The illustrative mapped-phase budgets
before headroom are5,304,070,144 /6,076,848,672 bytes. These are **not measured RSS
peaks or a qualified mmap admission policy**. The pinned upstream asserts for
no_alloc+mmap; the tool explicitly uses no_alloc read-layout and marks this limit.
The current APK native no_alloc model/context/compute totals match measured
native-budget profiles. It correctly refuses the requested CPU_REPACK-only policy
while KleidiAI extras are present. A later experimental APK must exclude them,
verify the repack budget and pressure cancellation, then run paired tests.

Diagnostic targets (EXCLUDE_FROM_ALL, never packaged in the APK):

```sh
.tools/cmake-3.31.6/bin/cmake --build build/cpu-recurrent-host \
  --target motoai-load-plan motoai-repack-audit --parallel 4
build/cpu-recurrent-host/motoai-load-plan models/Ornith-1.5-9B-Q4_K_M.gguf \
  bench/fixtures/generated/ornith/pp8064-full-mmap-v1.json cpu_repack
build/cpu-recurrent-host/motoai-repack-audit build/cpu-recurrent-host/bin \
  models/gemma-4-E4B-it-Q4_K_M.gguf models/Ornith-1.5-9B-Q4_K_M.gguf
```

Both first tool failures are retained: no_alloc+mmap assertion, and an audit read
through CPU_REPACK's null generic get_tensor callback. The corrected inverse
audit reads the pinned CPU backing allocation with explicit range checks; this
does not add a callback or otherwise patch upstream. See the preflight ledger.

## Explicit M3 runtime experiment

Current investigation: `bench/m3-repack-investigation-20261025.json`. This is an
opt-in experimental configuration; it is not a promoted product/speed profile.

```sh
python3 scripts/prepare-cpu-variant.py f32-recurrent-repack-order
./gradlew --offline --no-daemon --max-workers=4 \
  -PmotoaiCpuVariant=f32-recurrent-repack-order -PmotoaiCpuKleidiAI=false \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

In a debug request, set `config.weight_buffers` to `cpu_repack` and `load_mode`
to `mmap`. Omission retains `native`. The runtime checks the active CPU registry
before loading: every extra must be CPU_REPACK, and at least one must exist.
It rejects absent/mixed extras and verifies actual packed model buffers after
context allocation. The collector additionally checks exact planned packed bytes.
No KleidiAI requantizer is compiled into this experimental APK. Ordinary native
and repack comparisons run in the same APK, with the same tokenizer/template,
context, input/output IDs, thinking, F16 KV and generation settings.

Admission accounts for packed committed weights, the largest mapped native tensor,
the largest source tensor, context, compute and headroom. Unpatched builds add
the native/source windows. Only a compile-time flag tied to patch0004's source
variants permits phase accounting: `packed + max(source, native + context + compute)`.
Packing drops source PTEs per tensor, and Runtime allocates context/compute only
after model loading. The reserve stays768MiB or twice the system threshold,
whichever is larger. A final-APK framework test checks the phase budget, unchanged
headroom and refusal when memory is still insufficient.
Unknown model/geometry, context above8192 and read/auto repack are refused. The
profile stays explicitly unqualified as an RSS bound. The100ms load watcher saves
sampled RSS/anonymous/mapped peaks and refuses pressure below at least768MiB;
debug load progress survives a killed process. Samples can miss brief peaks.

Initial repack preserved weight bits but failed model gates. Two independent
runtime problems were investigated without widening numeric tolerances:

- **Mapped source residency:** Ornith was killed by LOW_MEMORY at reported7.2GB
  RSS. Android-only patch0004 avoids whole-source prefaulting for repack and calls
  MADV_DONTNEED on page-aligned interiors of source tensors after synchronous
  packing. It removes clean PTE residency, not mappings or file bytes; tied
  tensors may refault unchanged data. Actual OS page size is queried. The next
  fresh load/score completed at sampled5.56GB peak load RSS, without establishing
  endurance or an across-run causal memory ratio.
- **GEMV arithmetic order:** on target8x8 Q4_K, subblock FP32 accumulation differed
  from the native full-block integer sum and bias-first FMA. Q6_K separately
  multiplied/added rather than fusing like native. Patch0005 restores that order.
  Before it, tiny fifth-row GEMV differences amplified through Gemma layers while
  the first four GEMM rows stayed exact. After it, all120 extended repack outputs
  matched native bits, and all four code/Russian NLL/top8 pairs had zero error.

Original failed collectors/model trials remain in the ledger. Component/model
checks are complementary: a reversible weight layout and a passing scalar matmul
test alone did not establish model quality. The8x4 fallback and other formats are
not qualified by this target8x8 fix.

Tools (with the corresponding installed APK and matching diagnostic build):

```sh
python3 scripts/run-repack-preflight.py --binary-directory <matching-obj-directory>
python3 scripts/run-numeric.py --binary <matching-motoai-numeric> \
  --extended --require-repack-bitwise
python3 scripts/compare-weight-buffers.py gemma teacher-forcing-code-v1
```

`compare-weight-buffers.py` refuses a speed fixture until an evidence lock covers
both NLL corpora, a completed functional task and the long-context check for the
same APK/model/config. `accept-repack-screening.py` validates and hashes those
reports. Historical native artifacts may be reused for quality with exact input
and configuration identity; their timings are never reused in speed ratios.
Speed screening uses fresh processes, a warm-up and alternating native/repack
order; power state, temperature, generated work and thermal gates must match.

## Selective Ornith candidate and rejected full mode

Full Ornith repack was not repeatably loadable. Even after streamed source PTEs,
a near8K fresh attempt received LOW_MEMORY with several GiB MemAvailable; the
next file-cache-advice control also failed repeatability. Patch0006 is retained
as an unsuccessful control, not carried into the new candidate. A successful
code score/task never overrides a later load failure. The current Android service
refuses full Ornith repack with `unsupported_profile` **before loading**, without
silently changing to another representation or context.

`f32-recurrent-repack-selective` uses patches0001–0005 plus0007. Patch0007 adds a
uint64 CPU_REPACK type allowlist to the experimental pinned model parameters;
zero retains original selection. `cpu_repack_q6` sets only the Q6_K bit. Both the
ordinary selector and CPU-override selector honor it while still checking each
operation/shape. The runtime checks actual packed tensor types. This is a layout
choice, not converting Q4_K to Q6_K or requantizing anything. Other weight types
remain in their original native buffers. Builds without the explicit API fail
closed on this mode; pristine upstream/default native API regression is tested.

Host/phone metadata plans agree on1,839,022,080 packed bytes for Ornith. Admission
retains the1GiB native mmap working window for remaining Q4_K tensors, original
state/compute budgets and768MiB minimum headroom. Initial three paired fresh
load/scores and Russian NLL/top8 were exact, with about2.1–2.3GB anonymous load
peaks. This is bounded screening, not yet whole-model/endurance acceptance.

```sh
python3 scripts/prepare-cpu-variant.py f32-recurrent-repack-selective
./gradlew --offline --no-daemon --max-workers=4 \
  -PmotoaiCpuVariant=f32-recurrent-repack-selective -PmotoaiCpuKleidiAI=false \
  :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/compare-weight-buffers.py ornith teacher-forcing-code-v1 \
  --candidate-policy cpu_repack_q6 --plan-lock bench/m3-selective-repack-plans-20261025.json
```

Quality/speed evidence locks bind the exact policy as well as APK/model/config;
a full-repack pass cannot qualify a selective profile or vice versa. Gemma's
completed short speed screening belongs to its separately identified full-repack
APK; do not transfer those timing ratios to a different build/policy.

The final selective APK completed the1096-token+EOS code answer (134 cases) and
7680-input/512-reserve retrieval (39+EOS); every generated ID and first top8
matched the preserved corrected native Android result. Long prefill took1261s
and sampled VmSwap reached1.04GiB, so this is not interactive/endurance acceptance.
Same-APK short-speed results and startup tradeoffs are recorded in
[performance.md](performance.md); no product default is promoted.
