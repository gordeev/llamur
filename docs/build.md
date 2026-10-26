# Build setup

## Pinned source

`upstream.lock.json` identifies the exact llama.cpp commit. Restore with
`bash scripts/fetch-upstream.sh`. The checkout is disposable only when clean;
the script refuses to overwrite local patches. Project code and any later patch
series live outside that checkout. Application changes are separate from the pinned upstream checkout.

## CPU command-line baseline

Use NDK r28c (`28.2.13676358`), CMake 4.2.1 and Ninja 1.13.2.
Set `ANDROID_NDK_ROOT` to the extracted NDK and run:

```sh
bash scripts/build-cli.sh
```

This is the portable CPU reference: ARM64, API 28, no global native ISA flags,
KleidiAI/OpenMP/GPU disabled. It is not the official Android app B0.
Build outputs go to `build/android-cpu`. All commands run from the project root.
Tools are `llama-completion`, `llama-bench`, `llama-tokenize` and
`test-backend-ops`. At this revision `llama-cli` depends on the server/UI; the
completion tool is the smaller standalone scripted-generation entrypoint.
This conservative CLI is diagnostic; B0 remains the performance comparison
reference, including its optimized CPU kernels.

## Official Android B0

The pinned upstream example currently requests NDK **r29 beta1**, CMake 3.31.6,
Gradle 8.14.3, AGP 8.13.2 and Java toolchain 17. Its ARM64 CMake enables
KleidiAI and OpenMP, and Gradle also requests x86_64. Preserve those defaults
for B0 and record actual ABI/build parameters with measurements.

This beta toolchain is an experimental baseline dependency, **not** the chosen
F-Droid distribution toolchain. The product will use a stable NDK and an
independent Android project. Run Gradle with JDK 17, not the installed JBR 25.
SDK/NDK license acceptance must be handled through the official tools before use.

## Models

```sh
python3 scripts/fetch-model.py gemma
python3 scripts/fetch-model.py ornith
```

URLs use immutable Hugging Face revisions. Partial downloads can resume; only
files matching the locked byte count and SHA-256 become usable `.gguf` files.
Both reference downloads have passed SHA-256 verification. Metadata and mixed
tensor types are recorded in bench/*-metadata.json. Run-specific compatibility
and limitations are described in docs/compatibility.md.

## Standalone application (current default)

The root project builds the chat/model-manager/API application with an isolated
foreground inference service. Diagnostic MainActivity is retained for public
benchmark fixtures. Default CPU variant is f32-recurrent-long-attention-v2,
KleidiAI/GPU off. Final release acceptance is recorded in progress.md. With verified tools:

```sh
export JAVA_HOME="$PWD/.tools/jdk17/Contents/Home"
export ANDROID_HOME="$PWD/.tools/android-sdk"
export ANDROID_NDK_ROOT="$PWD/.tools/android-ndk-r28c"
export MOTOAI_CMAKE_DIR="$PWD/.tools/cmake-3.31.6"
bash scripts/build-app.sh
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -n org.llamur.app.debug/org.motoai.runtime.ChatActivity
# Open CPU/GPU diagnostics in the UI to refresh capabilities.txt.
adb -d shell run-as org.llamur.app.debug cat files/capabilities.txt
```

For the official B0, use the same Java/SDK/CMake variables, set
`ANDROID_NDK_ROOT` to the verified `.tools/android-ndk-r29-beta1`, then run
`bash scripts/build-b0.sh`. It builds ARM64 only, explicitly recorded as a build
filter; upstream runtime source and runtime defaults remain unchanged.

## Native phone smoke

After `scripts/build-cli.sh` completes:

```sh
python3 scripts/deploy-cli.py
python3 scripts/fetch-model.py smoke
python3 scripts/run-phone-smoke.py
```

Deployment uses `/data/local/tmp/motoai/<upstream-prefix>/`, never system paths.
The smoke result includes command, model hash, exit status and output. It does
not validate the main models or app-UID execution.

## Exact B0 and compatibility variants

B0 runtime source is unmodified. Build script restricts the package to ARM64.
Its APK is at `third_party/llama.cpp/examples/llama.android/app/build/intermediates/apk/debug/app-debug.apk`;
install with `adb -d install -t -r <apk>` (the injected build is test-only).
The baseline driver is `:baseline-driver:assembleDebug`. Install its APK, stage
models with `scripts/stage-model.py <id> --target b0`, then run:

```sh
python3 scripts/run-b0.py gemma --variant B0 --foreground
```

Gemma B0 currently aborts because Jinja is disabled. Compatibility variants are
explicit patches in `patches/android-b1/series.json`, prepared by
`scripts/prepare-b1.py` into separate checkouts. Select a build with
`MOTOAI_BASELINE_VARIANT=B1-jinja` or `B1-jinja-no-mmap` when running
`scripts/build-b0.sh`. Install the selected APK and use the matching `--variant`.
`bench/android-variants.json` records actual APK/patch hashes; the runner refuses
an installed APK mismatch. These patch variants are not a speedup claim.

## Own app native/service harness

The native code uses stable NDK28, dynamic CPU ISA variants, KleidiAI, no OpenMP,
F16 KV, zero GPU layers. UI defaults are 4096 context, 4 threads, batch512 /
micro-batch128, ordinary weight reads, greedy temperature0/seed42. Context,
threads and output reserve are visible settings. The service rejects overflow;
it does not silently shift context or truncate the prompt.

Use the system file picker to import a single-file GGUF without ADB. Import
creates an app-private copy, checks GGUF magic/version, streams SHA-256, fsyncs
and atomically promotes the file. The local digest is not publisher verification.
Split files and resumable HF downloads remain later work. Imports survive
Activity recreation but not process death; unfinished copies are discarded on
the next import. No broad storage permission or INTERNET permission is requested.

For automated debug-only fixtures:

```sh
python3 scripts/stage-model.py smoke --target app
python3 scripts/run-app-smoke.py smoke --service --context 128 --tokens 16 --raw --prompt 'Once upon a time'
python3 scripts/stage-model.py gemma --target app
python3 scripts/run-app-smoke.py gemma --service --context 8192 --tokens 32
```

The runner checks installed APK SHA, unique result ID and model filename. Results
include config, public fixture, timing, raw output and immutable model lock.
`--service` exercises the shipped service/IPC; without it a debug Activity calls
JNI directly. These are different scopes and must remain labeled.

Host core tests (the tiny fixture must exist):

```sh
cmake -S native-runtime -B build/core-host -G Ninja -DGGML_METAL=OFF -DGGML_BLAS=OFF -DBUILD_SHARED_LIBS=ON
cmake --build build/core-host --target runtime-test -j4
ctest --test-dir build/core-host --output-on-failure
```

Android framework instrumentation (no AndroidX test library is bundled):

```sh
./gradlew --no-daemon --max-workers=4 :app:assembleDebug :app:assembleDebugAndroidTest
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -d shell am instrument -w -r org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation
adb -d exec-out run-as org.llamur.app.debug cat files/runtime-tests.json
```

Do not run transfers or other project inference concurrently with measurements.
Raw `bench/results/` is ignored; durable milestone summaries live in tracked
`bench/m1-results.json` and `docs/progress.md`.

For an explicit background/screen-off service check use `--background --screen-off`
with `--service`. The runner waits for generation to start, sends HOME/SLEEP,
wakes the display afterwards and stops only MotoAI. It does not change Doze,
thermal, governor or other application settings. A locked phone may need the
owner's normal unlock after waking.

## M2 fixtures, series and diagnostics

After building the host `runtime-run` target, freeze public requests and
vocabulary/template traces (full local model SHA is checked first):

```sh
python3 scripts/prepare-fixtures.py gemma --synthetic
python3 scripts/prepare-fixtures.py ornith --synthetic
python3 scripts/benchmark.py gemma short-code
```

`bench/PROTOCOL.md` defines the baseline and metrics. The runner verifies the
phone model SHA once per series, APK identity per trial, exact host/app prompt IDs,
output counts and repeated greedy output. It writes raw JSONL/CSV and a summary
with all five values, median/range and bootstrap interval. The resumed runner has
no inherited time cutoff. Pass `--cutoff HH:MM` to bound a particular session; both
`benchmark.py` and `run-m2-batch.py` check it between trials/fixtures.
It is not an automation or background schedule.

Pure coding fixtures are evaluated by `scripts/evaluate-code.py`: functions only,
restricted AST/builtins, bounded subprocess, deterministic test cases. Unsupported
solutions are reported as harness limitations. Raw reasoning/final output stays
in the result. A code fragment that passes unit tests but hits the generation
limit is not counted as a completed quality task.

Build diagnostic executables against the **same Android ABI build directory**
as the APK (choose the directory printed by Gradle under app/.cxx/Release):

```sh
cmake --build app/.cxx/Release/<build-id>/arm64-v8a --target motoai-numeric motoai-backend-ops -j4
python3 scripts/run-numeric.py
python3 scripts/run-backend-smoke.py
```

These executables link to the installed APK's verified native libraries. Results
are ADB-shell arithmetic/layout tests, not app performance measurements. The
independent scalar matmul test distinguishes native activation representation
from error against original F32 inputs. Its initial calibration failures remain
recorded; final gates are in `bench/numeric-correctness-gates.json`.
Upstream CPU-vs-CPU operation smoke is labeled as layout/sentinel coverage and
never substituted for the independent reference. Zero selected cases fail the
runner even if the upstream tool exits0.

## Resumed M2 diagnostics

Output reserve is bounded by the loaded context, rather than the old1024-token
Kotlin cap. Native admission still checks prompt+reserve; overflow is explicit.
New2048/4096 quality fixtures retain old fixtures and baseline artifacts.
`pp*-diag` fixtures request one output token solely to isolate prefill cost;
they do not replace the128-output long-context acceptance workloads.

Debug progress is atomic and includes prepare/reset/sampler/prefill/decode,
completed tokens, last block time, CPU time and process RSS/swap. The host runner
saves progress even on timeout, partial output and termination evidence before
stopping its own application. Diagnostics and all-logit finite checks perturb
timing: do not pool these trials with the original short baseline. Allocation
reports use the exact pinned upstream staging API, contained in runtime.cpp;
they are backend allocations, not resident physical memory.

Teacher-forcing compares a fixed owned continuation against host CPU and APK:

```sh
python3 scripts/prepare-fixtures.py gemma --only teacher-forcing-code-v1 teacher-forcing-ru-v1
python3 scripts/compare-score.py gemma teacher-forcing-code-v1
```

The inspector verifies that splitting prefix/continuation preserves full-text
BPE boundaries. Each continuation token is scored against raw logits from the
preceding actual prefix; it is then supplied as the next input. Reports label
`mode=teacher_forcing`, `finish_reason=scored`, token NLL and perplexity. This is
not model-generated output or a coding-task pass. `bench/logit-gates.json` fixes
initial cross-ISA comparison bounds before reference collection. Retain failures;
small owned corpora do not establish whole-model quality or dataset pass@1.

Run scoring repeats with `scripts/check-score-repeat.py MODEL FIXTURE`.
All workload runners share an inherited advisory phone lease; a second controller
fails before ADB. Do not bypass it for a concurrent manual test. Failed/invalidated
trials remain in raw artifacts and resumed ledgers. Runners now detect inference
PID death/replacement during load or generation and preserve exit-info promptly.

After installing the current APK, run framework tests serially with:

```sh
python3 scripts/run-runtime-tests.py
```

This installs the matching test APK, clears the stale test report, checks installed
app SHA, requires30 cycles plus service success, saves APK identities/raw output,
and force-stops only MotoAI. The recovery test checks Load is enabled and visible
after process death before clicking; programmatic clicks alone are insufficient.
Host harness suite currently has21 tests, including four cross-process lease tests
and process-death classification. Native ctest includes an actual BOS-only scoring
request to ensure an empty raw string still returns its token trace.

Declared coding/retrieval fixtures in benchmark.py now require the evaluator's
final-answer quality gate as well as valid collection. Evaluator CLI exits1 for
an incorrect or incomplete answer, including correct code that hit length.
The batch propagates failure instead of continuing or returning success. These
stricter semantics apply to new series; original collection-only series retain
their scope and get a separate saved quality assessment. Per-trial watchdog is
explicit (batch default1500s), not a relaxation of quality criteria.

`scripts/archive-build.py` preserves exact APK bytes by SHA and a post-build source
and tool-lock snapshot. This is not embedded compiler attestation. Older retained
APK artifacts without a source snapshot are explicitly labelled as such.

The macOS-only `scripts/run-libm-control.py --comparison <comparison.json>` is a
component investigation, not a new quality reference or benchmark. It compiles
`native-runtime/tools/trig-interpose.cpp` directly into an isolated dylib; the
runtime/APK never links it. Its table comes from the saved independent phone
math probe, not model logits. It handles only the original five-token Gemma
prefix/first score, rejects other unknown angles and records actual intercepted
calls. The measured negative control fixed the first RoPE node but did not fix
later-layer/NLL differences; the failed hypothesis is preserved.

## Current release candidate

For standalone fresh Linux setup, exact SDK archive locks, strict Maven checks,
the successful clean-pair hash, signing and evidence scopes, use
[release-build.md](release-build.md). Earlier sections retain research/baseline
commands and must not be confused with the default standalone application.
