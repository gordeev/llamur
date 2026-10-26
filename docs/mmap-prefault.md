# Native mmap prefault control

M3 investigation,25 October2026. Pinned upstream stays clean; patch0008 is applied
after the existing corrected arithmetic/selective-repack patches in the separate
`f32-recurrent-native-demand` variant. No tensor values, scales, quantization,
F16 KV, templates, inputs or output reserves change. CPU extras remain disabled
for both arms of this comparison. Native prefault=true remains the default.

`config.mmap_prefault=false` explicitly passes false to model-loader
`init_mappings`. On Android this avoids the initial source MAP_POPULATE and
POSIX_MADV_WILLNEED. It does not evict pages, disable kernel readahead, alter
global caches or change Gemma's existing runtime per-layer embedding prefetch.
The runtime permits this control only with explicit mmap/native buffers; builds
without the API reject it before clearing an already loaded model. Load telemetry
confirms the requested flag and actual no-extra-buffer allocation policy.

The question is whether the change improves total loading/first-request behavior
and memory, or merely postpones PTE population into the first inference. Lower
loaded PSS alone is insufficient. Compare load, first nonempty piece, load+TTFT,
actual output cadence and completed PSS; record sampled RSS/anonymous/file peaks,
process swap and system memory. Clean mapped pages and swapped anonymous pages
must not be described as equivalent RAM savings.

The phone controller serializes every workload under the project lease. For
each model: code/Russian corpora, complete code answer, same-APK long pairs,
then an exact APK/policy/config quality lock and warmup+five alternating short
speed pairs. Ornith long input is7680 with512 output reserve; EOS after39 tokens
is not full8192-boundary generation. Gemma's8064+128 reaches that boundary but
is a mechanical length-stop case; its separate code task completes to EOS.
Long quality-pair timings are descriptive and screened per pair for power,
temperature and process/screen state, not promoted to five-pair/endurance speed
acceptance. Old native references are reused only for completed-task quality,
never timing ratios. App-UID model SHA checks read the files before each series:
fresh process does not mean cold storage or a cold file cache.

Debug-only counters at existing chunk callbacks include `/proc/self/stat`
(minor/major faults), `/proc/self/io`, process CPU time/status, and selected
`/proc/meminfo` fields. Missing counters stay unavailable, never zero. Polling can
miss the final prefill boundary; report the observed token interval. CPU time
includes busy-wait/barriers, and process VmSwap does not measure stall duration.
A separate synthetic2048-token app-UID sampling run is prepared to locate CPU
self-time, without root/global profiling settings; its timings are excluded from
speed evidence. Before sampling, all13 unstripped libraries must match the APK
by ELF build ID and strip-normalized bytes. Recorded CPU DSO build IDs are checked
again, and missing/nonzero loss accounting rejects qualification. Offline reports
include per-thread self time. Cleanup failures preserve the report and remain failures. It does not by itself prove late8K behavior.

An additional source/metadata hypothesis concerns the earlier Q6-only swap:
Ornith's834,355,200-byte Q6 output head was committed in CPU_REPACK at load time,
while the runtime requests logits only for the final prompt token. Earlier
prefill chunks do not consume that tensor. A cold anonymous packed head may be
reclaimed into swap; native leaves its original mapping clean. The tensor-level
swap attribution is unproven: aggregate VmSwap does not identify those pages.
An output-excluding packing control would require its own quality/load/speed
qualification; it is not silently substituted into this mmap experiment.

```sh
python3 scripts/prepare-cpu-variant.py f32-recurrent-native-demand
./gradlew --offline --no-daemon --max-workers=4 \
  -PmotoaiCpuVariant=f32-recurrent-native-demand -PmotoaiCpuKleidiAI=false \
  :app:assembleDebug :app:assembleDebugAndroidTest
# Install current APK; the controller verifies identity through framework/child runners.
python3 scripts/run-mmap-prefault-screen.py ornith gemma --cutoff HH:MM
```

The controller completes its active stage and starts no new stage after the
explicit local cutoff, if one is supplied; later invocations inherit no cutoff.
The25 October invocation originally recorded15:00 at launch. The user's later
12:00 departure overrides it: only the dispatcher was suspended while the fourth
long Ornith request finished, then terminated after its child exited. The original
controller report is preserved unchanged; completed child evidence is collected
separately in `bench/results/m3-native-demand-20261025T093522Z/pause-checkpoint.json`.
No short-speed, Gemma or profiling workload started. Do not resume until the user
returns, or overlap sampling with a comparison trial.

Current APK: `5761d83665d629aeed98af08c03dbcf2cb5ca572dd1b1c0827fbee2a5ad49be7`.
Current ledger: `bench/m3-native-demand-investigation-20261025.json`.
Controller: `bench/results/m3-native-demand-20261025T093522Z/report.json`.
Paused at12:16 local time after all four long requests completed. Both opposite-order
pairs passed with identical generated IDs and first-top8 error0; native allocation
plans match. See `bench/m3-native-demand-long-analysis-20261025.json`. Native
prefill1373.47/1378.30s versus demand1375.69/1373.50s gives no demonstrated gain.
Process VmHWM fell from5.69–5.74GiB to5.23GiB; sampled process swap peaks fell
from90.9–103.6MiB to16.9–17.0MiB. Cache state is uncontrolled and reduced mapped
process residency is not equivalent to freed system RAM. CPU sampling is pending.
M3/product/endurance,16K/32K and actual16KiB-page execution remain unaccepted.


## Resume after the user returns

Do not rerun the completed Ornith corpora, code task or four long requests. Verify
the phone is ready and the installed/build APK is still the exact5761d836… above.
The existing quality lock binds that APK, model SHA, policy and configuration.
Changing any of those requires fresh matching evidence.

```sh
python3 scripts/compare-weight-buffers.py ornith short-code-mmap-v1 \
  --candidate-policy native_no_prefault --repeat 5 --warmup 1 \
  --quality-gate bench/results/m3-native-demand-20261025T093522Z/ornith-quality-gate.json
# New aggregate for Gemma only; do not restart the old ornith+gemma dispatcher.
python3 scripts/run-mmap-prefault-screen.py gemma
```

Then perform the separate bounded sampling run as described above. Aggregate
completed reports explicitly across these controller files; do not mark the old
incomplete dispatcher report passed. No phone job or automatic resumption remains.

25 October evening: user resumed; same installed APK verified. Initial battery3%
was charging, below the20% start guard. While charging, the profiling collector was
hardened as above;51 host tests passed. This tooling change does not alter the APK
or invalidate the completed Ornith long quality pairs. See
`bench/m3-native-demand-resume-20261025.json` for the active continuation.


Control completed for both models on APK5761d836…; saved morning Ornith long pairs
were reused without rerunning. `bench/m3-native-demand-analysis-20261025.json`
contains completed quality/memory/timing evidence. Lower initial PSS and swap did
not yield useful long-prefill speed. No default promotion. The next experiment is
bounded transient CPU matmul workspace; UI/API and larger contexts remain later.
