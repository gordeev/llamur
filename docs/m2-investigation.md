# M2 resumed — 24 October 2026

Current checkpoint: bounded M2 harness/reference accepted, M3 screening started.
See `bench/m2-acceptance-20261025.json` and `docs/progress.md`; older failures and
pending-work notes below are historical and remain evidence, not current status.

Explicitly resumed by the owner at20:35 Europe/Belgrade. The earlier15:00 cutoff
does not apply to this session. No UI/API product implementation or runtime
optimization is being substituted for baseline correctness work.

## Output budget and quality

Removed the Kotlin/service/runner1024 cap. UI uses the loaded context, service
checks the same bound, and native still rejects prompt+reserve overflow without
truncation. Ordinary UI default reserve is2048; debug requests remain explicit.
New2048/4096 fixtures preserve original request bytes and results.

Gemma `binary-search-quality-2048` reached EOS after1919 emitted tokens and
passed134 functional cases plus logarithmic access checks.1920 raw-logit scans
passed (the extra scan selects EOS). See
`bench/results/app-service-gemma-20261024T184228Z-1b7da561/`.
The final channel's function is scored; code inside reasoning does not count.
Raw reasoning is retained unchanged. This is one owned task, not a dataset pass@1.

Ornith also completed the same2048-budget task at1460 emitted tokens plus EOS,
with1461 finite scans and134 cases plus logarithmic reads passed:
`bench/results/app-service-ornith-20261024T193101Z-0f99082d/`.

The first instrumentation retry exposed OS-only service restart backoff and a
possible lost Load command while the Binder was absent. Explicit recovery starts
the service and queues only the user-requested Load until connection; prompts
are never replayed. Failed report is retained. The subsequent30-cycle report and
service checks passed, including1536-token reserve admission and2048-context
overflow, real load/prefill cancellation and process-kill/explicit restart:
`bench/runtime-tests-resume-final-20261024.json` (APK07dfc12a…). A later test audit
found that performClick bypassed button availability: Load remained disabled
after disconnect. Enabled/visible-button assertions and the corresponding UI
fix are now added; acceptance requires the new instrumentation rerun, rather
than treating that historical report as proof of an actually tappable button.
That rerun passed on the final APK in
`bench/runtime-tests-resume-ipc-final-20261024.json`:30 cycles, visible/enabled
Load recovery, no prompt replay and load/prefill cancellation. A synthetic full
diagnostic over1MiB remained intact while the UI status parcel was208 bytes.
Per-token arrays and raw requests are kept out of Binder status; no output cap
was added to solve the IPC limit.

## Prefill diagnosis

The native core now marks prepare/reset/sampler/prefill/decode, records every
prefill batch's time, and optionally checks every output logit for NaN/Inf.
Debug progress files are atomic; the runner retains progress/partial output and
exit evidence on timeout, then stops only MotoAI even if diagnostic collection
fails. A long diagnostic watchdog can explicitly be extended to1800 seconds;
the original900-second failed measurement remains a failure.

Initial diagnostic points (one trial each, one output token, configured8192;
not the original repeat baseline, no performance gain claim):

| Actual prompt | Prefill | Reset | Scope |
| --- | --- | --- | --- |
| Gemma128 |10.788 s |15.170 ms | Old diagnostic APK before allocation fields |
| Gemma512 |46.696 s | recorded in raw report | Allocation-report APK |
| Gemma2048 |197.940 s |31.063 ms | Four blocks,48.23–50.60s |
| Gemma4096 |397.760 s | recorded in raw report | Eight blocks,42.56–54.96s |
| Gemma8064 +128 output |882.030 s |13.545 ms |16 blocks, output decode31.929s |

The full8064+128 trial completed: native generation total approximately914s,
first target token882.107s, all128 raw-logit scans finite, thermal status0.
16 prefill blocks progressed from46.53s to66.93s for full512-token blocks; final
384-token block55.87s. The original900s watchdog would cut this working run
during decode. This explains a concrete timeout mechanism in the conservative
CPU profile; the original run had no phase telemetry, so its exact cause is not
retroactively proven. No backend hang/crash was observed in the new run.
Raw: `bench/results/app-service-gemma-20261024T190703Z-8485f0aa/`.
One filled-context mechanical pass is not retrieval-quality, sustained-memory or
interactive-performance acceptance. Length128 is the deliberate benchmark cap,
not a completed coding-task answer.

Gemma512 allocation report: CPU model5338832192 bytes, context469762048,
compute150478848. These are backend allocations from the pinned staging API,
not resident RAM. RSS/swap vary as Android pages memory; do not infer that swap
alone caused a timeout. Diagnostic checks and fsync/progress overhead are included
in these wall times; no pooling with the original short benchmark.

The coarse process-CPU counter increased3273.341 CPU-seconds over826.153 wall
seconds from0 to7680 prompt tokens in the full8K run (ratio3.962). Together with
the separate simpleperf sample this supports active CPU compute as a major
bottleneck; it does not prove absence of memory stalls. See
`bench/prefill-cpu-time-20261024.json`.

A separate beginning-needle task at7680 input tokens and512 reserved output
passed: exact identifier in the final answer,12 emitted tokens then EOS,
13 finite scans, prefill783.530s/decode2.626s. Thinking remained enabled.
`bench/results/app-service-gemma-20261024T200043Z-0f7234c0/quality.json`.
This is one fact/position/model, not the full matrix. Its controller began before
automatic quality gating was added; assessment is separately saved. New declared
quality/retrieval series enforce the final-answer/EOS gate before reporting PASS.

## Candidate source finding, not yet patched

`gemma4.cpp` creates a tied output from token_embd before creating tok_embd.
The loader's duplicate-reuse check only searches tensors already created.
The locked GGUF has no output.weight and a Q4_K token_embd of377487360 bytes
(360MiB). This is a concrete candidate for a redundant same-buffer allocation;
verify by a later small patch, allocation delta and identical numeric/model
results. Do not claim a measured RAM/speed win yet. Evidence:
`bench/gemma-tied-embedding-investigation.json`.

## Numeric and task gates

Corrected upstream operation smoke passed12 filters/15 real model-sized cases for
RMSNorm, SSM Scan, Gated Delta, Flash Attention, RoPE, convolution and Softmax.
`bench/operation-smoke-phone-20261024-expanded.json`. This CPU-vs-CPU layout/sentinel check is separate from the existing
independent54-case scalar matmul reference.

New owned code/Russian teacher-forcing fixtures verify full-text BPE boundaries
and compare token NLL/first logits between host CPU and APK. Supplied continuation
tokens are explicitly marked `teacher_forcing`/`scored`, never counted as a model
answer or ordinary generation timing. Initial comparison gates were written
before collection in `bench/logit-gates.json`. Full corpus/task/endurance gates
remain separate. M2 is not yet accepted as complete.

Cross-CPU code scoring failed the initial NLL/logit gates for both models.
Gemma mean absolute token NLL error0.07287/max0.37685 and top8 logit max0.54067;
Ornith0.03112/0.16928 and0.12982. Top8 sets agree. Portable reference build
(ARMv8.2 FP16/DOTPROD, no Accelerate/native ISA/Metal/BLAS/Kleidi/OpenMP) produced
bit-identical Gemma host scores; no tolerance was widened. Native ART FPCR0 and
a standalone host initial FPCR0 were observed. No tolerance was widened.

Two fresh Gemma processes on the same APK produced exactly equal token NLL and
first-top8 values (`score-repeat-gemma-teacher-forcing-code-v1-20261024T192816Z`).
Enabling the selected-node observer on that APK also left this scoring trajectory
exactly unchanged. The observer is opt-in and not a performance profile.
Initial embedding, RMSNorm, Q projection and first layer output matched whole
tensor fingerprints. The first observed mismatch was Qcur_pos-0 after RoPE;
its sampled endpoints matched, but the full fingerprint and L2 differed slightly.
Later layer outputs amplified the difference. This localizes an early difference,
without proving it is the only cause of the full NLL discrepancy.

A separate no-model math executable received identical runtime float angles on
host and phone. Across640 local-RoPE rows, cosf differed in2 and sinf in6 rows;
across1280 global-RoPE rows,1 and5 respectively. Computing double cos/sin then
casting to float matched across CPUs in all of these rows. No runtime math was
changed. Raw: `bench/results/math-libm-20261024/comparison.json`.

The corrected one-BOS-token control passed the original strict gates:
mean/max NLL difference6.55e-7 and top8 maximum logit difference1.91e-6;
all captured layer output fingerprints matched. This is a control, not a pass
for the failed multi-token code corpus. Raw:
`bench/results/score-gemma-teacher-forcing-bos-probe-v1-20261024T194953Z/`.
Cross-CPU multi-token reference qualification remains open.

The full-layer probe located the first differing layer output at6; outputs0–5
still matched whole fingerprints. A host-only component control substituted the
independently measured Android float trig values, including Apple's combined
sin/cos entry point. Unknown non-tiny inputs abort; proportional-RoPE tiny angles
use explicit correctly-rounded binary32 identities. This made Qcur_pos-0 match,
but layer6 and later outputs still differed (first-token NLL error0.32016).
Thus the trig-only explanation is insufficient. This negative control is retained
as failed, not used to claim equivalence or change any gate:
`bench/results/host-libm-control-20261024T200257Z/`.
Additional72-node observation was collected on the final APK. Two normal phone
scoring processes matched its27-token NLL/top8 exactly; host observer control also
matched. With measured Android trig on host, layer6 Q/K/V and rotated Q/K match
whole fingerprints; first remaining mismatch is kqv_out-6, attention output
before output projection. This narrows the next investigation to attention
arithmetic/exp/reduction, without identifying an exact cause yet.
`bench/layer6-investigation-20261024.json`. No interposer is linked into the APK.

The actual initialized exported CPU tables were also compared across platforms:
GELU differs at1/65536 entries, x=-5.15625 (host -1.788139e-7, phone -0.0).
Quick GELU is identical. Both processes FPCR0. Installing the complete phone
GELU table on host changed that entry, kept the table intact through exit, and
left the27-token code NLL unchanged: this sole-cause hypothesis failed too.
No runtime math was patched. `bench/activation-tables-20261024.json` and
`bench/results/host-gelu-control-gemma-20261024T201626Z/`.

## Collection integrity and memory failure

Two diagnostic controllers were accidentally overlapped before the earlier tool
session had completed. They were stopped; affected artifacts and derived reports
are explicitly invalidated (`bench/overlap-aborted-20261024.json`). They cannot be
reused as evidence. An inherited advisory file lease now rejects a second project
phone workload before any ADB state change. Four cross-process tests verify it,
including retaining protection while an inherited child is still active.

The subsequent serial Ornith scoring attempt was killed during load with
Android exit reason LOW_MEMORY (pid26075, RSS5.7GB). Before load MemAvailable
was6761532KiB, so checking the model's file size alone would not prevent this.
The service restarted empty and did not replay the prompt; the old collector
waited for its watchdog. A process-identity watcher now fails immediately when
the observed inference PID vanishes or is replaced, preserving exit evidence.
No governor/LMKD policies or foreign processes were changed. Memory-aware
admission and a stable model-loading profile remain required before acceptance.

Two independent Ornith mmap scoring loads subsequently succeeded with exact
NLL/top8 repeatability. Both also matched the earlier successful read-mode
26-token score exactly; model/request and config except load mode are identical.
The historical read reference used a different diagnostic/lifecycle APK, so this
is not an isolated memory/speed A/B. Load times5.388/1.203s include unknown storage
cache state. Endpoint swap about19MiB versus647MiB in the earlier complete read
answer is not a controlled peak-memory comparison or universal guarantee.
The canonical read baseline is unchanged. All artifacts/APK hashes and limits:
`bench/memory-load-investigation-20261024.json`.

Exact measured8K APK was retained in `bench/results/builds/` before replacement.
Future build helper captures APK plus post-build owned-source/tool-lock tar; the
runner retains exact APK bytes by SHA. Older trials without source snapshots
are not claimed to have retrospective source attestation. No weights or keys
are archived by the build helper.


## Corrected CPU numeric candidate (late M2)

The remaining attention boundary was reproduced from exact saved F32 Q / F16 KV /
mask bytes. Phone replay reproduced the captured output bit for bit. Mac replay
on those same bytes differed at246/10240 output values, maximum9.536743e-7.
One of81 traced expf inputs differs by one binary32 ULP between libm implementations.
Substituting the measured Android expf table made the attention output bit exact.
This causal diagnostic is standalone, never linked into the APK.
`bench/results/attention-exp-control-20261024/control.json`.

An independent double softmax/dot/value calculation also exposed the pinned
short-query FA path's F16 query conversion and F16 value accumulator even when
GGML_PREC_F32 is requested. Its pure-F32 reference error was0.006646 absolute,
NMSE2.83e-7. A separate patch honors F32 Q/accumulation while keeping the original
F16 KV and GGUF bytes; captured-case error fell to3.808e-6, NMSE3.91e-14.
No tolerance was widened. The original failed comparison is retained.

The explicit patch series is `patches/cpu-numeric/series.json`:

1. Honor F32 flash-attention query/accumulation, reuse existing conversion scratch.
2. Scalar exp/sin/cos through double then binary32; SIMD approximations unchanged.
3. Explicit fused F32 SSM convolution and matching scalar unary exp/sin/cos/log.

Patches1+2 passed Gemma's two corpora but failed Ornith's. A non-perturbing
first-prefix observer found identical embedding, norm and QKV projections, then
small differences at conv_output_raw, beta_sigmoid and a_softplus. Patch3 made
all supported observed layer outputs, first NLL and top8 bit exact for Ornith.
Scoped host assembly retains separate multiply/add in the old convolution and
uses explicit fmadd after the patch. See
`bench/results/conv-fma-assembly-20261024/`.

Both corrected host and APK then passed the unchanged code/Russian gates for
both models. Ornith26/27 scored targets match bit for bit; Gemma27/29 have maximum
NLL/logit errors about1.91e-6. This qualifies the corrected CPU reference on these
bounded corpora; it does not retrospectively pass the unmodified host baseline,
certify a broad dataset, or measure a speedup. B0 source remains pristine.

Independent F64 attention checks also passed24 configurations on both CPUs,
including the installed APK libraries: F16/F32 KV, non-F16-representable F32 Q,
DK/DV256/512 plus unequal dimensions, causal GQA, vec/tiled/split-KV paths and
1/4 threads. Actual APK trial:
`bench/results/attention-variant-matrix-20261024T211942Z/`.
Scalar matmul and expanded operation smoke passed on the same APK. Summary and
raw references: `bench/m2-numeric-fix-20261024.json`.

## Model load admission and canonical storage

Both large models were moved into `Downloads/MotoAI/Models`, with destination
SHA verification before deleting only app-private staging copies. JNI opens a
seekable MediaStore PFD and the service holds it through model/mmap lifetime.
Direct app-UID FUSE-path reads were denied; independent test hashing therefore
opens the actual URI/PFD. Details: `docs/model-storage.md`.

Four serial same-APK private read/mmap scoring loads passed before the move:
`bench/results/load-matrix-ornith-code-20261024T204332Z/`. Four fresh corrected-APK
mmap loads from Downloads also passed with exact NLL/top8 repeatability:
`bench/results/load-matrix-ornith-code-20261024T212144Z/`.
These are bounded load trials with unknown page-cache state, not sustained
pressure acceptance or a cold-load performance result.

An initial fixed8K admission budget falsely refused a128-context test and is
retained as a failed trial. Context forecasting now matches measured128/4096/8192
buffers with256-token padding and Ornith's fixed recurrent-state allocation.
Larger profiles are explicitly unqualified conservative extrapolations; increasing
context cannot bypass a refused smaller profile. Read reserves dense model memory.
For original mmap weights with extra buffers disabled, admission uses committed
state/compute plus a conservative largest-tensor working window (Ornith1GiB,
Gemma2GiB). It does not count the entire clean file mapping as a new anonymous
allocation or pretend the window is a peak-RSS limit. Largest pinned spans:
`bench/mapped-weight-window-20261024.json`. Paging and sustained speed still need
measurement. Unknown models retain only a file-size lower bound.

A100ms load watcher cancels through the normal ticket path on low system memory;
CAS prevents cancelling an already completed load. Refusal preserves an already
loaded model. After failure/unload, Android's API28 M_PURGE releases unused pages
from this process's allocator; no foreign apps, LMKD, governor or global caches
are changed. This is not a proof that concurrent external pressure can never kill
inference. No measured RAM-saving or universal stability claim is made.

Latest framework/JNI test passed30 cycles, typed preflight refusal, stricter
synthetic in-flight pressure cancellation, real Gemma load/prefill Stop,
notification Stop and process-death recovery. Prefill cancellation52ms.
`bench/results/runtime-tests-20261024T211927Z/`. Storage checks cover duplicate
import without a new copy, .bak recovery, interrupted moves, deleted files and
FD lifetime: `bench/results/storage-tests-20261024T211942Z/`.


## Power/system interruption during long output

Both generated add tasks completed to EOS and passed153 functional checks on the
corrected mmap candidate. The following Ornith lower_bound4096-budget trial
stopped after229 decoded tokens at~76s. Device-after capture found no battery,
thermal or SurfaceFlinger services; ADB later reported no devices. Before the
trial battery was4%, NOT_CHARGING despite AC-connected. No exit record or crash
stack could be recovered. This is suspected system shutdown/restart/power loss,
not proven app LMKD or an isolated native fault. Do not call the long task passed,
and do not automatically replay it. Retained evidence:
`bench/ornith-long-system-exit-20261024.json`.

A read-only audit flags13 recorded low-battery trials for performance use:
`bench/low-battery-audit-20261024.json`. Numerical/function passes remain valid
observations, but those timings do not establish normal powered speed. New
workload entrypoints screen boot/battery before starting; app runs monitor power
and boot identity, stopping below10% or on a changed boot. Start requires20%.
No battery properties, governors or other apps are modified. The old interrupted
trial lacked a pre-recorded boot ID, so its exact restart type remains unknown.

The failed run also motivated hardening the debug driver against restored
Intents: a fsynced one-shot run-ID marker suppresses automatic replay in both
Activities. That follow-up build passed; its added framework check subsequently
passed on the recovered phone (30 JNI cycles, suppression and explicit recovery):
`bench/results/runtime-tests-20261024T220801Z/report.json`. Native library bytes match the
last fully tested candidate, as recorded in `bench/one-shot-build-20261024.json`.
M2 remains open. M3 speed experiments have not started.

## Recovered-phone long-answer qualification

After completed boot and verified charging at48%, the new one-shot APK completed
Ornith lower_bound to EOS:1096 emitted tokens,1097 finite-logit scans,134 final-code
cases and logarithmic reads passed. Thinking remained enabled, context8192,
reserve4096, mmap, no extra weight buffers. Artifact:
`bench/results/app-service-ornith-20261024T220836Z-82257c5b/`.
The interrupted229-token trial remains a failure. This fresh run does not prove
its exact shutdown cause, or qualify real pressure/endurance across all loads.

Host cleanup also now records diagnostic/cleanup failures separately and attempts
each finalization action, preserving the original workload error. A collected
answer cannot pass a series when finalization fails.34 Python tests pass,
including disconnected diagnostics and failed force-stop cases.

Gemma lower_bound also reached EOS:1861 emitted tokens,1862 finite scans and the
same134 function/complexity checks passed. Corrected host emitted1741 tokens;
the first branch was generated index319 (IDs43653/12808). A new causal control
on the corrected host installed the previously measured complete Android GELU
table, changing exactly one entry (input-5.15625). It restored **all1861 generated
IDs exactly**, with the table verified unchanged at exit:
`bench/results/host-generated-gelu-control-20261024T222747Z/`.
Thus the remaining branch on this task is explained by the known scalar GELU
rounding discrepancy, rather than an unexplained attention or state difference.
No interposer/table replacement is included in the APK. Ordinary corrected
host/APK scored-corpus comparisons already passed the unchanged gates without
this control. It is a bounded causal explanation, not whole-model equivalence.

The corrected-host Ornith beginning-needle v1 returned the right fact but failed
the predeclared exact-answer format: final `The identifier is: **motoai_x7r4_n9q2**`,
55 tokens then EOS,56 finite scans. Retain this failure at
`bench/results/host-retrieval8k-ornith-20261024T223325Z/`; it is not missing-context
evidence or a passing strict-format result. A distinct v2 fixture explicitly asks
for only the identifier, no Markdown/explanation. Both versions keep the same
7680 actual prompt/512 reserve and thinking; v2 changes prompt bytes and must not
be pooled with v1 or used to rewrite its failure. The evaluator is unchanged.

Corrected APK Gemma8064+128 completed with128 finite scans: prefill1001.072s,
decode50.383s. Its16 prefill blocks progressed52.9→72.0s per512 tokens (last384
tokens55.3s), no hang. All128 generated IDs match host. However first-top8 maximum
absolute logit difference is0.409069, above the unchanged0.1 screening bound.
Mechanical boundary pass **does not pass the long numeric gate**. Preserve:
`bench/results/host-filled8k-gemma-20261024T222346Z/phone-comparison.json`.
The GELU-only causal control must be repeated at this depth before a reference
qualification decision. No speedup or full M2 acceptance is claimed.

The long GELU-only control completed: exactly one table entry changed and the
table remained intact at exit. Max first-top8 error fell from0.409069 to
1.9073486e-6; all128 generated IDs still match, the original0.1 gate passes:
`bench/results/host-filled8k-gelu-control-20261024T224925Z/`.
The unmodified-table long comparison remains a failure. This control attributes
the long drift to the same finite-precision activation-table difference; it does
not hide it with a wider tolerance or change production APK math. A future CPU
reference must explicitly identify its activation-table semantics; otherwise a
cross-platform kernel comparison includes this already explained difference.

Frozen host-only table input: `bench/fixtures/reference/android-gelu-f16-v1.json`,
SHA/provenance in `bench/cpu-reference.lock.json`. This is numeric fixture data,
not weights or an APK asset. Current APK table recapture is pending until the
serial phone workload finishes. The portable control tool now supports both
scored and generated artifacts, checks matching CPU math variants and preserves
failed comparisons. Reproduction (no phone work):

```sh
python3 scripts/run-gelu-control.py \
  --app-artifact bench/results/app-service-gemma-20261024T221603Z-2398c2cc \
  --host-runner build/cpu-recurrent-host/runtime-run --timeout 600
```

That command restored all1861 generated IDs with finite scans and unchanged
top8 gates: `bench/results/host-gelu-control-gemma-20261024T225624Z/`.
Scoring mode also passed: `bench/results/host-gelu-control-gemma-20261024T225620Z/`.
Earlier unmodified-table failures and standalone controls stay identified as such.
