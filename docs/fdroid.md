# F-Droid preparation

Current release-candidate results are recorded below. The M0/M1 sections are
historical design checkpoints, not a statement that their completed checks are
still outstanding. Privacy policy: [privacy-policy.md](privacy-policy.md).

## Initial distribution decisions (M0)

- Main build: FLOSS source-built runtime, portable ARM64 CPU, optional GPU.
- Do not bundle Qualcomm QNN/QAIRT SDK binaries or Hexagon skels in the main build.
  Hexagon remains a separate feasibility experiment until toolchain, permissions,
  licensing and full model operation coverage are verified.
- The upstream Android B0 currently requires beta NDK r29. Keep this experimental
  baseline separate from the stable-NDK product build; do not reuse its release
  recipe as the F-Droid recipe.
- OEM OpenCL/Vulkan drivers stay on the device. Optional access must fail cleanly
  to CPU. Final inclusion suitability still requires an actual dependency audit.
- No models bundled in APK, no telemetry, mandatory account, Play Services or
  executable plugin download. Weights require their own provenance and licenses.
- Model catalog license declarations: Ornith MIT; selected Gemma Apache-2.0.
  These metadata declarations must be checked against the pinned license files
  before distribution. New app code uses Apache-2.0 (root LICENSE); signing remains open until release.

Policy: https://f-droid.org/en/docs/Inclusion_Policy/

The M1 foreground service uses `specialUse`, a documented subtype for explicit
local model loading/generation, and a Stop/unload notification. See the
[Android service type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
This is an implementation choice, not evidence of store acceptance.

Historical outstanding release work (superseded by the final candidate evidence): offline dependency/source recipe (including KleidiAI
1.32.0), complete license notices/SBOM, deterministic release builds and signing,
16K ELF/ZIP alignment and emulator/device launch, architecture parser admission,
model license provenance, accessibility/localization and sustained validation.
Do not submit the current debug harness to F-Droid as a finished release.

## Release preparation evidence (26 October)

The current source/component notice inventory is `licenses/runtime-components.json`;
verbatim notices are packaged under `app/src/main/assets/licenses/`. The actual
release runtime Maven graph contains only Kotlin stdlib2.3.0 and JetBrains
annotations13.0. Their cached POM license declarations and JAR/POM SHA-256 are
recorded. Native notices cover the pinned llama.cpp/GGML and bundled common
helpers, JSON, hashes, subprocess header, and official NDK LLVM runtime notices.
This inventory is still a candidate: final APK contents and every transitive
build dependency must be checked again after the product UI/API is complete.

The CPU release disables KleidiAI, GPU and Hexagon backends and optional Rust
llguidance. It does not need a Qualcomm SDK, proprietary shader binary, model,
API credential or executable runtime download. User model data is separate from
the app distribution; metadata license claims are not proof of model provenance.

F-Droid requires functional applications, public source and a FLOSS build chain.
Its current policy also normally requires three months of active development,
with listed exceptions for established users, existing maintainers or events.
The final public application ID, repository/tag, maintenance eligibility and
signing strategy therefore remain owner/reviewer release decisions; this task
prepares artifacts and never submits/publishes them. [Inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/).

Native build outputs can vary across host toolchains even at the same NDK
version. Final reproducibility must compare two clean Linux builds under the
recorded environment; an offline incremental rebuild alone is insufficient.
[Reproducible builds](https://f-droid.org/en/docs/Reproducible_Builds/).

## Product release candidate preparation

The launcher is now the standalone chat/model-manager app; OpenAI-compatible API
and HF downloader are implemented. See app-description.md and api.md. CPU is the
main build, GPU/Hexagon/KleidiAI disabled. No model or proprietary accelerator
binary is bundled. `fastlane/metadata/android/{en-US,ru-RU}` supplies catalog copy;
`fdroid/org.llamur.app.yml.in` is an explicitly unresolved review template,
not a submitted or automatically publishable recipe. Public source/issues URLs
and an immutable application commit cannot be fabricated from this private,
uncommitted workspace. Do not use blanket scanner exclusions to approve an
unknown binary: the reviewer must audit the pinned upstream tree and remove any
non-build fixtures flagged by the actual F-Droid scanner. The template alone implies no scanner result or catalog acceptance; actual
scanner and executed-prebuild results are recorded below.

`gradle/verification-metadata.xml` locks resolved Maven SHA-256 values.
`scripts/audit-maven-dependencies.py` inventories226 build/test components from
cached Maven POMs, including inherited licenses. Every binary component has a
license declaration and all cached artifacts match their recorded hashes;
metadata-only parent POMs are distinguished. These hashes were bootstrapped
from the existing dependency cache, not independently authenticated upstream
signatures. Packaged runtime is only Kotlin stdlib/JetBrains annotations plus
source-built native libraries and platform APIs. Full notices are in APK assets.

Two isolated Linux builds use the same metadata-free source snapshot, pinned
amd64 builder, stable NDK r28c and no network. Their final comparison and actual
APK16KiB ELF/ZIP/notice/manifest audit are recorded in progress.md and the release
artifacts. Read those reports before calling reproducibility complete.

Signing: locally installable development APKs use a development certificate;
**it is not an owner production signing identity**. Preserve the published
application ID/version/update-key strategy before any real release. F-Droid
normally signs its own built APK; a developer-signature/reproducible-upstream
workflow needs its explicit corresponding setup. This task creates no production
key, tag, commit, submission, upload or store publication.

The template fetches pinned llama.cpp as an F-Droid **srclib** using its companion
`fdroid/llamacpp.yml`, then copies source into the expected vendor location and
applies hash-locked patches. It has no blanket scanignore and performs no Git
network fetch in prebuild. Use the actual F-Droid scanner before submission;
SDK/CMake availability and the public application revision still need resolution.
[Build metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/).

Official fdroidserver2.4.5 source scanner ran with freshly downloaded SUSS
(SHA256 `34c673dcc1a44dde8e3a9a2bc2d16abd0fc969acfc3f6836dd0a83d7de17f959`)
and no scanignore/scandelete: zero fatal findings. One warning identifies an
unused executable-bit upstream **Keynote presentation** `idea-arch.key` (ZIP of
presentation assets, not a signing key). The draft prebuild removes this exact
non-build document from the two prepared source copies. The pristine repository
is unchanged. The binary scanner also found zero signatures in Linux pilot APK;
the final E product APK was subsequently scanned with zero findings
(`fdroid-binary-scan-linux-e-20261026`).

The final template also passed the actual fdroidserver2.4.5 metadata parser and
source scanner **using its own Build settings**, not a default empty Build:
`bench/results/fdroid-recipe-validation-20261026/report.json`. No scanignore or
scandelete is used. The initially drafted scandelete entries for root models and
bench/results were removed: neither path exists in the clean source export, and
F-Droid treats unused scanner exceptions as errors. Unresolved public URLs and
application commit remain explicit placeholders; this does not validate remote
fetches, maintainer eligibility, a build-server run or submission.

The joined prebuild shell script was then executed **as written, from app/**,
with the real local pinned Git srclib substituted, before scanning with its Build:
`bench/results/fdroid-prebuild-executed-20261026/report.json`. Parser, preparation
and scanner passed with zero warnings/errors. The Python preparation step uses a
subshell so its cd cannot affect the following exact-path document removal.
Original vendor checkout remains unchanged. This local execution still does not
resolve the future public app repository/tag or count as a F-Droid server build.

## Обновление0.2.0-dev

Добавлены необязательные пресеты OpenRouter/OpenAI/Anthropic и свой endpoint.
В шаблон метаданных внесён `NonFreeNet`: [определение F-Droid](https://f-droid.org/docs/Anti-Features/#Non-Free-Network-Services)
охватывает продвижение proprietary network services. Это консервативная маркировка
шаблона, окончательное решение принимает каталог. Локальный чат по-прежнему offline;
проприетарный SDK не добавлялся. Старые source/binary-scans и воспроизводимая пара
относятся к0.1.0; новая версия требует своей приёмки. Ничего не подано/опубликовано.
