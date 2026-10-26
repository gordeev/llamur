<p align="center"><img src="docs/branding/llamur-foreground.png" width="104" alt="llamur lemur icon"></p>
<h1 align="center">llamur</h1>
<p align="center"><strong>Your models. Your phone. Your conversations.</strong><br>Local GGUF inference, a messenger-style chat app, and an OpenAI-compatible server for Android.</p>
<p align="center"><strong>English</strong> · <a href="README.ru.md">Русский</a> · <a href="docs/providers.md">Providers</a> · <a href="docs/api.md">API &amp; Aider</a> · <a href="docs/build.md">Build</a></p>

Run a downloaded model without a cloud account, root or Termux. Organize chats,
keep sensitive conversations temporary, get replies while the app is minimized,
and connect desktop tools to the model running on your phone. You can also use
OpenRouter, OpenAI, Claude or your own compatible server from the same interface.

**Built around a quality-first, patched llama.cpp runtime:** original GGUF weights,
original quantization and F16 KV stay intact. The engine has model-specific numeric
and memory corrections, cancellable inference, and optional bounded prefill packing.

Development preview · Android 9+ · ARM64 · English / Русский · Apache-2.0 application.
F-Droid preparation is included; the app is not listed in F-Droid yet.

## Download and install

[Download the ARM64 APK — 0.6.5 public preview](https://github.com/gordeev/llamur/releases/download/v0.6.5/llamur-0.6.5-arm64-v8a.apk) · [Release notes and checksums](https://github.com/gordeev/llamur/releases/tag/v0.6.5)

Requires Android 9+ with a 64-bit ARM64 OS. Open the downloaded APK to install;
model weights are downloaded separately. This is an early public preview.
Public GitHub builds use a dedicated release certificate. Earlier local test
builds and future F-Droid builds may use different signers and cannot necessarily
be updated in place. Keep an existing installation's data until you have chosen
your update channel. [Signing and verification](docs/signing.md).


## A real Android app

<table>
<tr><td align="center"><img src="docs/screenshots/home-dark.png" width="230" alt="English dark chat list with folders"><br>Chats and folders</td><td align="center"><img src="docs/screenshots/chat-dark.png" width="230" alt="English dark conversation"><br>Conversations</td><td align="center"><img src="docs/screenshots/settings-dark.png" width="230" alt="English dark settings"><br>Models and settings</td></tr>
<tr><td align="center"><img src="docs/screenshots/chat-light.png" width="230" alt="English light conversation"><br>Light theme</td><td align="center"><img src="docs/screenshots/storage-light.png" width="230" alt="English chat storage choices"><br>Your retention policy</td><td align="center"><img src="docs/screenshots/hugging-face-light.png" width="230" alt="English Hugging Face model download form"><br>Bring your own models</td></tr>
</table>

Actual captures of the running Android app on an API 36 ARM64 emulator, with
English UI and public scripted demo conversations. These are interface screenshots,
not mockups or evidence of a particular model's answers. [Capture details](docs/screenshots/README.md).

## What you get

- **Offline local chat.** Import a GGUF, link a supported on-device file, or download
  from Hugging Face. Choose a context window and output budget for each model.
- **A familiar messenger.** Chat search, folders, message search, pins, model details,
  streaming replies, and a Send/Stop composer. Light, dark and system themes.
- **Background answers.** Ask, minimize, and return from a completion notification.
  Android battery restrictions still apply; notification text contains no conversation body.
- **History on your terms.** Never save by default; temporary chats stay in RAM.
  Permanent chats use encrypted storage with optional automatic deletion.
- **Manual or automatic context compaction.** Auto is opt-in and off by default.
  Inspect a summary before applying it, view it later, or undo it. Original messages
  follow the chat's retention policy. Summaries can lose details.
- **Cloud and self-hosted providers.** OpenRouter, OpenAI Chat Completions/Responses,
  Anthropic Messages, and compatible endpoints; model discovery, multiple connections,
  per-provider settings, protected credentials and explicit HTTP consent.
- **Attachments when supported by the provider.** Text files, images and PDFs;
  save supported returned files/images from a conversation. Local GGUF chat is text-only.
- **Your phone as an API server.** `/v1/models` and `/v1/chat/completions`, streaming,
  usage and cancellation. Loopback uses a Bearer token; Wi-Fi uses TLS plus Bearer.
  Aider completed two real coding edits through the phone. [Setup and supported subset](docs/api.md).
- **Considerate model storage.** Downloads in `Downloads/llamur/Models` on Android 10+,
  integrity checks, resume/cancel, duplicate detection, and an explicit option to
  delete an imported source after verifying the copy.

Local chat never silently falls back to a cloud provider. API keys are not bundled.
HF tokens are optional for public repositories and required where the repository's
access rules require them. See the [privacy policy](docs/privacy-policy.md).

## What changes compared with stock llama.cpp?

The comparison is against **the pinned upstream Android example**, commit
[`701fd25`](https://github.com/ggml-org/llama.cpp/tree/701fd25b3a55a90297dec0d925204b29084054c8/examples/llama.android),
plus matched native-path experiments. It is not a claim about every llama.cpp
frontend or later upstream revision.

| Area | Pinned Android example / unpatched path | llamur |
| --- | --- | --- |
| Tested model templates | Gemma's custom template aborted in the official example with Jinja disabled | Full Jinja rendering and explicit template/token checks |
| Original model representation | Some enabled backend paths can convert extra weight buffers | Original GGUF quantization; conversion paths that violated the policy are excluded |
| Attention and state correctness | Investigated rounding, reduction-order and long-sum discrepancies | Explicit F32/recurrent fixes, stable decode reduction partitioning and long-attention accumulation corrections |
| Loading and memory | Ornith's early example profile was killed under Android memory pressure | Native mmap without full prefault; model/context/workspace admission and one engine owner |
| Prompt handling | Example truncates/rolls input | Explicit context-plus-answer budget; overflow is reported; compaction is visible and reversible |
| Cancellation and lifecycle | Example marks prompt processing uninterruptible | Chunked prefill, native abort, separate inference process, recovery and background completion |
| Prefill compute | Native quantized matrix multiplication | Optional bounded, lossless workspace packing; no persistent full packed weight copy |
| Daily use | Demonstration app | Chats, folders, retention, downloads, providers, notifications and local API |

The memory changes address observed loading failures; they do not eliminate Android
low-memory kills or make long prefill fast. Vulkan/OpenCL experiments that failed
quality or stability checks are excluded from the shipping profile. CPU4 remains
the conservative default. [Compatibility findings](docs/compatibility.md) ·
[Patch series](patches/cpu-numeric/series.json).

## Measured performance

All phone measurements below are from **Motorola Edge 40 Pro, Snapdragon 8 Gen 2,
12 GB RAM, Android 16**, using the original locked Q4_K_M files and F16 KV.

### A demonstrated prefill improvement

A controlled Ornith 512-input-token experiment compared native execution with
bounded workspace packing: one excluded warm-up and five counterbalanced pairs,
CPU4, batch 512 / micro-batch 128, 8K configured window, warm/preconditioned model.

| Ornith 1.5 9B | Native path | Experimental packing |
| --- | ---: | ---: |
| Median prefill time | 82.58 s | 49.57 s |
| Input tokens processed per second | 6.20 | 10.33 |
| Loaded-model time to first piece | 82.65 s | 49.64 s |

**1.65× median paired prefill speedup**, with a bootstrap 95% interval of
1.62–1.68×: about **40% less prefill wait** for this experiment. Source weights
and F16 KV were unchanged; observed workspace was about 41 MiB, with an 81 MiB
admission reserve. The option is experimental and **off by default**.

These are development-build, short warm-prefill results, not a universal current
release speedup, cold-load benchmark or decode gain. A comparable accepted Gemma
before/after speedup has not been established. Rejected/confounded experiments are
retained rather than advertised as wins. [Paired timings and methodology](docs/benchmarks/README.md).

### Short requests in the app

A later same-engine B/P/P/B observation used CPU4, a loaded model, 4K context,
packing/cache off, 30–31 prompt tokens and 128 emitted tokens. The app's two
measured requests per model produced these approximate averages:

| Tested model | App request wall time | Backend decode rate |
| --- | ---: | ---: |
| Ornith 1.5 9B Q4_K_M | 35.5 s | 4.2 tokens/s |
| Gemma 4 E4B-it Q4_K_M | 22.8 s | 6.5 tokens/s |

The output budget made these **partial, length-limited outputs**, including any
reasoning; they are not complete-answer latency. Decode rate counts 127 evaluations
for 128 emitted tokens. Two pairs do not establish sustained speed or p95.
The product UI's median wall ratio to the same-engine harness was about 1.016
for Ornith and 1.021 for Gemma. This is small observed integration overhead, not
an acceleration claim against stock. [Exact rows](docs/benchmarks/short-ui-requests.json).

**Large context is available, but slow.** Default is 4K; choices are 2/4/8/16/32K.
Both models passed one actual filled 16K and 32K ordered-retrieval control with
experimental packing enabled. At 32K, prefill took about **101.5 minutes for
Ornith** and **76.4 minutes for Gemma**. Those are compatibility/quality controls,
not an interactive experience or proof of arbitrary long-context quality.
[Full results and limits](docs/performance.md).

## Tested models

| Exact GGUF | Architecture | File size | Verified scope |
| --- | --- | ---: | --- |
| [Ornith-1.5-9B-Q4_K_M](https://huggingface.co/ornith-ai/Ornith-1.5-9B-GGUF) | `qwen35` | 5.38 GiB | Numeric/scored controls, completed-code checks, retrieval, API/Aider, background and recovery |
| [gemma-4-E4B-it-Q4_K_M](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF) | `gemma4` | 4.64 GiB | Numeric/scored controls, completed-code checks, retrieval, background and recovery |

Exact revisions, SHA-256, tokenizer/template hashes and model-license provenance
are in [models.lock.json](bench/models.lock.json). Weights are downloaded separately.
The tiny `stories260K` file is a test fixture, not evidence of main-model speed.
Ornith coverage does not certify all Qwen models; Gemma has separate attention/state paths.
Other GGUF models may work but have not received these same checks.

## Devices and requirements

**Build minimum:** Android 9 / API 28+, a 64-bit ARM64 OS. For the two models above,
**12 GB RAM or more and fast storage are the sensible starting point**. Their files
alone occupy roughly 10 GiB together; runtime state and Android need additional memory.
Smaller models may fit lower-memory phones, but 6/8 GB configurations have not been qualified.

| Device/platform | Status |
| --- | --- |
| Motorola Edge 40 Pro · Snapdragon 8 Gen 2 · 12 GB · Android 16 | Physical inference, quality, lifecycle, API and background tests |
| ARM64 Android 16 emulator · API 36 · actual 16 KiB pages | UI, IPC, storage, locale/theme, lifecycle and page-size compatibility; not a phone speed test |

**Likely candidates, not tested devices:** [Galaxy S23 Ultra, 12 GB variant](https://www.samsungmobilepress.com/media-assets/galaxy-s23-ultra?tab=specs),
[Galaxy S24 Ultra, 12 GB](https://news.samsung.com/us/enter-new-era-of-mobile-ai-samsung-galaxy-s24-series/),
[OnePlus 11, 16 GB variant](https://www.oneplus.in/11/specs), and
[Xperia 1 V, 12 GB](https://www.sony.com/electronics/support/mobile-phones-tablets-mobile-phones/xperia-1-v-256gb/specifications).
This is a hardware-based inference: their RAM and Snapdragon 8 Gen 2/3 platforms
make them reasonable candidates for CPU inference, not a verified performance promise.
Other Snapdragon, Exynos and MediaTek devices need independent validation. Cooling,
free RAM, firmware and battery policy matter; brand or “flagship” alone is insufficient.

## Build and contribute

```sh
git clone https://github.com/gordeev/llamur.git
cd llamur
bash scripts/fetch-upstream.sh
```

Use JDK 17, Android SDK/API 36, stable NDK r28c (`28.2.13676358`) and Android CMake
3.31.6. Follow [Android setup](docs/build.md) and [release build instructions](docs/release-build.md).
The checked-in locks and patch series identify the upstream and toolchain; downloaded
tools, weights, credentials, local agent state and build outputs are ignored.

For bugs, use [GitHub Issues](https://github.com/gordeev/llamur/issues). Useful reports
include the app version, phone/SoC/RAM, Android version, model filename/quantization,
context and relevant sanitized diagnostics. Do not attach keys or private chats.

[Apache-2.0](LICENSE) covers the application; third-party notices are included.
Models have their own licenses. [Privacy](docs/privacy-policy.md) ·
[F-Droid preparation](docs/fdroid.md) · [Detailed measurements](docs/performance.md).
