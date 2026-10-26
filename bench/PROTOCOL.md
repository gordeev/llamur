# M2 protocol — frozen before collecting the repeat baseline

Revision: 2026-10-24. This defines an initial CPU baseline, not a performance claim.
Pinned model/upstream/toolchain locks apply. Product CPU profile: stable NDK28,
CPU dispatch/KleidiAI, OpenMP off, ordinary weight reads, F16 KV, batch512 / ubatch128,
Extra weight buffers disabled (`use_extra_bufts=false`) to prevent upstream
KleidiAI Q6_K/Q8_0 weight requantization. Selective lossless packing is an M3/M4
candidate; an ordinary CPU tensor override still reconsiders extra buffers here.
4 threads, configured8192, temperature0, seed42, thinking=true. No prefix reuse.
Trace/profiling baseline uses `no_perf=false`; earlier M1 timings had no backend
counters and are not pooled with this series. Trace enabled/disabled is recorded.

- One device run at a time. No transfers/parallel inference. Record charging,
  screen state, fingerprint, thermal status/battery temperature and available RAM.
- Per short workload/model: one warm-up excluded, five measured requests minimum.
  Process-cold model reloads do not imply cold storage cache. Warm requests in a
  resident model are a separate series. Preserve text/token IDs and actual EOS.
- Report all five values, median, min/max and bootstrap95% CI (fixed analysis seed).
  No TTFT p95 claim from five requests. No speedup claim without A/B alternation,
  ≥5% practically useful change above noise and passing correctness/quality gates.
- The first session stopped starting work at15:00. The owner later explicitly
  resumed M2; new session cutoffs are opt-in via `--cutoff HH:MM`. Interrupted
  series stay incomplete. Never silently shorten or replace a workload.

Metric definitions:

- `prepare_us`: validation, template, tokenization, KV clear and sampler creation.
- `prefill_us`: wall time processing every prompt token. No input truncation.
- `decode_us`: post-prefill wall time including sampling, JNI sink and N-1 decode
  evaluations for a length-limited N-token answer. With EOS, counts can differ.
- `backend_perf`: upstream evaluator counters and times; compute decode throughput
  using **decode_evals**, not emitted tokens. Keep zero/unavailable counters null.
- `first_target_token_us`: from native request entry to first sampled non-EOG
  target token; `first_nonempty_piece_us` is first nonempty raw byte piece. Neither
  is claimed to be first visible final answer when the model emits reasoning.
- `inter_token_us`: intervals between emitted target tokens, including callback
  and evaluation costs. No unused final token is evaluated merely for timing.
- Debug.getPss at load/completion is an endpoint API value including swapped PSS in AOSP; it is not resident PSS. `/proc/self/status` VmHWM is
  process RSS high-water, not peak PSS. Fresh-process series separates prior runs.
- End-to-end task completion requires EOS/final answer; hitting a token limit is
  explicit `length`, not a completed quality task.

Correctness gates before comparing configurations:

1. Same model SHA and fixed config/fixture bytes. Host/app rendered prompt and
   input IDs must agree exactly. Repeated greedy app outputs must agree within
   the baseline profile. Cross-ISA output differences need investigation; they
   alone do not establish model quality loss.
2. Unicode, code whitespace, multi-turn roles, explicit overflow and cancellation
   fixtures. Literal control-marker text currently fails explicitly: flattening
   marked Jinja parts would interpret it as syntax; segmented tokenization without
   whitespace/BPE changes remains a product limitation. Never silently escape it.
3. Finite backend operation/numeric tests on all relevant tensor types. Numerical
   tolerances must be fixed by workload before an optimization result is accepted.
4. Public completed coding tasks plus unit/property checks; preserve reasoning and
   final output, actual token counts and incomplete-budget outcomes.
5. Actual long prompts at multiple depths, explicit output reserve and boundary
   rejection. Allocating8192 alone does not satisfy an8192-context claim.

Initial workloads: fixed short coding request, model-specific synthetic128/512/
2048 prompt-token fixtures, Unicode/code/multi-turn templates, long4K/8K input.
Finalist thermal/endurance requires the separate ≥15min/two-run protocol in PLAN;
this initial M2 series does not certify a sustained profile or energy efficiency.

## Resumed diagnostic scope

Original short baseline fixtures and results are immutable references. New
`*-2048`/`*-4096` coding fixtures only raise the explicitly recorded reserve.
The evaluator scores complete final-channel code and EOS, never a code block
inside thinking. Retrieval likewise distinguishes a final exact answer from an
identifier merely appearing in reasoning. Known pinned Gemma/Qwen delimiters are
fixture-specific parsing, not a claim of arbitrary model/API support.

`pp*-diag` uses actual128/512/2048/4096/8064 prompts, one emitted token, finite
logit scans and atomic phase/block progress. These diagnose prefill and are not
the original128-output acceptance or a comparable speedup trial. Keep per-APK
hashes; allocation/progress diagnostic additions must not be silently pooled.
Backend allocations are separated from RSS/swap. Upstream perf clamps n_eval to
at least1, even when decode_ms is0; in that case do not compute decode throughput.

Teacher-forcing owned code/Russian corpora score a fixed continuation on host CPU
and APK. Full-text tokenization must preserve the prefix boundary; identical
prompt/continuation IDs are mandatory. Every raw logit is checked for finiteness.
Token NLL uses stable log-sum-exp. Initial limits in `logit-gates.json` were set
before collection; retain failure reports rather than widening gates afterward.
Scored continuation tokens are supplied inputs, never reported as sampled model
answers or a coding quality pass. Large corpora/task/endurance remain separate.

All project workload entrypoints acquire `scripts/phone_lease.py` before ADB.
Controllers explicitly pass an inherited locked descriptor to their child runner;
closing a child never unlocks a still-running parent. The OS releases the lease
when the last owning process closes it. A competing controller fails before
mutating phone state. Direct/manual ADB tests must obey the same serial rule.
Two earlier accidentally overlapped diagnostics were stopped and invalidated;
`invalidated.json` excludes an artifact even if it contains a collected result.

The collector observes inference PID from startup, including load. A disappearing
or replaced PID is `process_died`, not a successful empty-service restart and
not a reason to wait for the whole generation watchdog. Preserve exit-info before
force-stop; LOW_MEMORY and native crash must not be inferred from timeout alone.
No automatic request replay or unlabelled retry. Actual model admission/recovery
under varying RAM pressure is an independent stability gate.

Finalization attempts device diagnostics, screen restoration when requested and
MotoAI force-stop independently. A failed diagnostic or cleanup is saved in
`finalization-errors.json`; it must not hide the original workload exception.
If the workload collected a result but finalization failed, the runner exits
nonzero and the series cannot accept that trial.

For new series, declared coding/retrieval fixtures require final-answer/EOS
assessment inside benchmark.py before a trial is passed. The evaluator source
hash and target are saved in the series manifest; collection success is recorded
separately. Evaluator CLI and batch propagate incorrect/incomplete results as a
nonzero exit. Historical collection-only series remain unchanged and have a
separate quality.json assessment; do not retrofit their original accepted field
as proof that the later quality gate ran.

The new filled-boundary fixture declares `required_output_tokens=128` in its
golden metadata. All8064 prompt tokens and128 emitted tokens must be observed;
early EOS cannot be called a filled8192-boundary pass. This does not suppress EOS
or force generation. Ordinary completed-answer quality still requires EOS.
New series/collectors record their Python source hashes separately from the APK;
an APK source snapshot alone does not identify later host-harness changes.

New speed series also require unchanged charging state and AC/USB source between
their before/after snapshots, as well as>=20% battery. A transition such as
charging→NOT_CHARGING near81% excludes comparable-speed timing; it is not by
itself a correctness failure or evidence of a bad cable. Older collector results
retain their original fields, with later power audits recorded separately.
