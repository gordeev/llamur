# Compatibility ledger

Current user-facing requirements/device list: [app-description.md](app-description.md).
The detailed findings below include historical checkpoints; current bounded M2
acceptance and final M3–M8 product evidence are recorded in [progress.md](progress.md).

Source inspection: llama.cpp `701fd25b3a55a90297dec0d925204b29084054c8`.
Source findings and measured device outcomes are separated below. Short smoke
passes do not establish quality, sustained performance or maximum context.

## Reference models

`bench/*-metadata.json` was read from the downloaded GGUF headers while tensor
payloads were still downloading. Metadata alone does not attest to payload
integrity. Both complete files have since passed the locked SHA-256 through
`scripts/fetch-model.py`.

- Ornith: architecture `qwen35`, 33 blocks including one next-token-prediction
  layer, context metadata 262144; F32/Q4_K/Q6_K tensors. MTP availability is an
  experiment, not an enabled feature.
- Gemma: `gemma4`, 42 blocks, metadata context 131072, shared KV layers 18,
  sliding window 512. Tensor types include F32/Q4_K/Q5_K/Q6_K/BF16. Test the
  complete mixed-type graph, including that BF16 tensor, on each backend.

## Official Android example: integration constraints

Source: `examples/llama.android/lib/src/main/cpp/ai_chat.cpp` and Kotlin
`internal/InferenceEngineImpl.kt` at the pinned revision.

- Default context is fixed at 8192, batch/ubatch 512, CPU threads capped at 4,
  temperature 0.3. Record these settings for B0.
- `processUserPrompt` truncates long input and `shift_context` discards history.
  This violates the product's explicit-overflow requirement. Do not use such a
  run as evidence of full long-context support.
- `chat_add_and_format` passes `use_jinja=false`; actual template correctness
  for the two model families must be tested before quality comparisons.
- Generation stop position includes the user prompt size after current position
  has already advanced by that size. Verify actual output token counts rather
  than assuming `n_predict` means an exact upper bound in B0.
- Kotlin marks prompt processing uninterruptible. Product cancellation must
  reach chunked prefill/native abort handling, not only the next decode step.
- Prompts are logged by the sample. Use only public fixtures for B0; never
  inherit prompt logging into production diagnostics.

Preserve B0 as the upstream reference. Implement integration corrections in
the project-owned B1 layer with explicit tests, and report those changes apart
from compute optimizations.

## Confirmed B0 failure: Gemma

2026-10-24 13:07 on the target phone: load/prepare reached system prompt, then
SIGABRT from an uncaught `std::runtime_error`: custom template unsupported,
try `--jinja`. Backtrace points to `common_chat_format_single` called from
`processSystemPrompt` with Jinja disabled. Result and isolated crash trace:
`bench/results/b0-gemma-20261024T110723Z-2366227d/`.
This is an integration failure, not a model-weight checksum or speed result.

## Reproduced compatibility variants

- `B1-jinja` changes only the official example's `use_jinja` argument. Foreground
  Gemma loads configured8192 and generates32 tokens. Background instrumentation
  was terminated by LOW_MEMORY, separately from the template crash.
- Foreground Ornith with the Jinja patch and default mmap is killed by LOW_MEMORY
  (reported RSS about7.5GB). The journal's `before_load` phase combines native
  weight loading and context preparation; it cannot locate the exact failing allocation.
- `B1-jinja-no-mmap` adds `model_params.load_mode = LLAMA_LOAD_MODE_NONE` only.
  Same Ornith bytes, configured8192, 4threads, batch/ubatch512 and CPU kernels:
  32 tokens complete; finalPSS6416199KiB. This supports ordinary reads as a useful
  memory candidate, but does not establish peak reduction statistically or
  quality equivalence. A dense mmap prefetched alongside repack is a hypothesis
  for the earlier peak, not a proven allocation trace.

Patch hashes, exact source diffs and APK hashes are locked under
`patches/android-b1/series.json` and `bench/android-variants.json`. B0 stays clean.
The historical names `B1-jinja*` identify these minimal example compatibility
patches; the independent MotoAI harness is a separate integration profile.

## Product-owned core and service

Native core performs full Jinja rendering, explicit prompt+output reserve checks,
chunked prefill, serial model ownership and cancellable native work. It keeps the
original GGUF and F16 KV. The app uses stableNDK28, dynamic CPU variants/KleidiAI,
OpenMP off and batch512/micro-batch128. Its default greedy seed42 differs from
B0's sampling. Performance comparisons must normalize these settings first.

On-device framework tests passed30 tiny-model load/generate/unload cycles,
queued/decode cancellation, invalid JSON types, Java callback exception handling,
Unicode transport, overflow/recovery, real Gemma load cancellation, Messenger
service recovery and isolated native-process termination/reload.
`bench/runtime-tests-20261024.json` contains the memory observations. Native heap
stabilized at about6.5MiB after warmup; PSS rose modestly during the short test.
This is not a complete leak or prolonged-load certification.

Remaining acceptance work includes filled context, real completed model answers,
template/token fixtures, exact token comparisons, long-session thermals, prolonged screen-off,
16K compatibility and GPU operation correctness.


## M2 policy correction

Earlier statements about unchanged GGUF bytes are file-integrity statements,
not proof of a lossless backend representation. The pinned KleidiAI extra-buffer
path can requantize Q6_K/Q8_0; the current runtime disables extra weight buffers.
See `docs/weight-buffers.md`. M1 mechanical successes remain valid within their
stated scope; old performance data is excluded from the strict baseline.

The conservative profile's repeated baseline is in `docs/performance.md`.
Independent scalar matmul correctness passes54 native-CPU cases with represented
activations;27 CPU_REPACK diagnostic cases also pass. Nine KleidiAI cases have no
approved reference/policy and are not passed;72 unsupported cases are explicit.
Original F32-activation accuracy and calibration failures remain recorded.
This does not certify whole-model accuracy or all operation/backend combinations.
