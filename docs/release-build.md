# Rebuilding the standalone release

Release 0.6.6 uses versionCode 14, package `org.llamur.app`, ARM64/API28+.
Its two clean Linux builds used different project/SDK/NDK paths and Git presence;
unsigned APKs matched exactly and the existing certificate verified against both.
Source commit: `2313c2b39552595568934a3038069c0f7d860210`. See
[the complete verification report](releases/0.6.6-verification.json).
Compilation uses 36.1.0, signing uses apksigner 34.0.0. No F-Droid submission or
official build-server acceptance is claimed. Earlier 0.1/0.6.5 sections below
retain their historical artifact scopes.

The first public preview is0.6.5, versionCode13, application ID
`org.llamur.app`, ARM64/API28+. GitHub release assets contain a dedicated-key
signed, non-debug APK, `SHA256SUMS` and the public signing-certificate fingerprint.
See [signing and update channels](signing.md). Models, credentials, private signing
keys and downloaded toolchains are excluded from the public repository.

Source changes and current checks are in `docs/progress.md`. The older Linux
reproducibility and0.1 artifact sections below describe their frozen baseline;
they are not a new byte-reproducibility claim for0.6.5. Native libraries remain
unchanged from the accepted engine; packaging/version/signing are checked anew.

## Toolchain

Use Linux x86_64, JDK17, Gradle8.14.3, AGP8.13.2, Kotlin2.3.0, SDK platform36,
build-tools 36.1.0, stable NDK28.2.13676358/r28c and CMake3.31.6. The Gradle wrapper
pins its distribution SHA. `toolchain.lock.json`, `bench/linux-tools.lock.json`
and `bench/linux-platform.lock.json` provide exact versions/archive hashes;
the platform archive's android.jar matches the actual release JAR SHA.

Install the official Android command-line tools and accept their licenses via
`sdkmanager --licenses` before building. Then install:

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.1.0' \
  'ndk;28.2.13676358' 'cmake;3.31.6'
```

A newer revision under the same SDK package name can differ. For an exact rebuild,
use the immutable Google archive URLs and SHA-256 values in the two Linux lock
files and verify extracted android.jar. SDK licenses are a separate developer
setup step; this project does not create acceptance markers for a new developer.
The older scripts/linux/fetch-tools.py is a convenience for the original lab
setup with an existing verified host SDK/repository XML, not a fresh-machine
bootstrap requirement.

## Build from exported sources

Set JAVA_HOME to JDK17 and ANDROID_SDK_ROOT to your SDK. From the source root:

```sh
python3 scripts/configure-android.py --sdk "$ANDROID_SDK_ROOT" \
  --ndk "$ANDROID_SDK_ROOT/ndk/28.2.13676358" \
  --cmake "$ANDROID_SDK_ROOT/cmake/3.31.6"
python3 scripts/prepare-cpu-variant.py f32-recurrent-long-attention-v2
./gradlew --no-daemon --max-workers=4 \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleRelease :app:lintRelease
```

The first build needs ordinary HTTPS access to the configured trusted Maven
repositories and Gradle distribution, or a previously populated cache. Keep
`gradle/verification-metadata.xml` verification enabled. Do not regenerate hashes
to hide an unexpected mismatch. For subsequent offline checks add`--offline`.
Output:`app/build/outputs/apk/release/app-release-unsigned.apk`.

Default build: portable ARM64 runtime dispatch,CPU4/native weights/F16KV,
no full mmap prefault, full SWA, KleidiAI/GPU/DSP/Rust accelerator paths disabled.
No executable engine/model is downloaded by the application. Optional bounded
packing/RAM-prefix features require explicit user selection and qualified model
hashes/geometries. They are off by default.

## Exact recorded Linux pair

Two fresh identical source directories were mounted at the **same canonical
path /work** in isolated amd64 Linux containers, network disabled,4CPU/6GiB.
Final G/H (including explicit API base URL copy button) both ran prepare-cpu-variant then the offline Gradle commands above; all59 tasks
executed in each. Only downloaded dependency/tool caches were shared, no native
or Gradle project outputs. Both lint passed, and unsigned APK SHA-256 was exactly:

`d1ffdb8329d7d883b3af5e2e1c0ef50d8eeabdcbed70e15188191875d106f14d`

Evidence:`bench/results/linux-product-url-copy-pair-20261026/report.json`, logs,
source manifest and build-environment.json in the delivered artifact package.
Builder image ID:aca8e32761619c66f030d3092b42bf9a91fcb0ba212561ef83fce3825bd6c8f8.
`scripts/linux/Dockerfile` and the recorded Debian/JDK package inventory describe
its origin. Building that Dockerfile later can resolve newer apt packages;
only the recorded image/tool environment and canonical path were tested for
byte reproducibility. Cross-host/path/toolchain equality is not implied.
F-Droid builds/signing are a separate pipeline, not a claimed upstream-signature
match. The current recipe pins the upstream signing certificate and APK, with the non-reproducible historical 0.6.5 build disabled. See [current F-Droid preparation](fdroid.md).

## Signing and installation

Unsigned APKs cannot be installed. The public GitHub APK is signed with the
owner's dedicated release key; the private key/password stay outside Git and
uploads. Build from source with your own signing identity, or use the attached
public APK and verify its SHA-256/certificate as described in [signing.md](signing.md).
The signature must be preserved for normal updates within this channel. Earlier
local development APKs used another certificate and were not production identities.

Before signing, align the unsigned APK with `zipalign -P 16 4`; after signing,
verify with `apksigner verify --verbose --print-certs --min-sdk-version 28` and
`zipalign -c -P 16 4`. The release APK must have `debuggable=false`, ARM64-only
native libraries, no instrumentation classes, no model/key assets and the exact
version/package intended for its source tag. Keep all dependency notices.

No private signing material, HF/API token or local assistant state belongs in
sources. F-Droid builds/signing and submission remain a separate pipeline.

## Evidence and scope

Actual signed non-debug release loaded a public tiny GGUF, answered through
its real UI and copied the API base URL with verified real paste on a16KiB
ARM64 Android16 emulator. Full lifecycle/background and
contract checks use the native-identical test-enabled build. Real main-model,
context, Aider and performance evidence with exact APK/model hashes is listed in
progress.md. Tiny developer fixtures are not bundled or distributed as models.
Known limitations: large contexts are slow/experimental, arbitrary GGUFs/devices
are unqualified, the app UI supports Russian and English, and valid gated/private HF access
was not exercised without an authorized account token.


## Final physical-phone acceptance

`bench/results/release-ui-phone-20261026T123200Z/report.json` passed the actual
normal non-debug3af31 APK, original Ornith, CPU4/4K/2048reserve/cacheOFF/
transientOFF: actual composer, answer completed while Dozing, normal owner unlock,
real SystemUI notification tap, EOS and expected public math answer in its card.
Earlier owner-unlock timeout121900Z is retained separately. Own test APK removed;
model/APIoff and no MotoAI wake lease/active notification confirmed in
`release-phone-idle-20261026T123539Z`. No PIN/security/thermal policy changes.

`bench/release-acceptance-20261026.json` aggregates immutable report hashes via
`scripts/close-release-acceptance.py`; failed setup attempts and scope limits are
preserved. Source package includes build-input manifest and curated evidence
under `release-review/`, not raw device dumps or local model weights. The supplied
SHA256SUMS identifies the delivered files. External-provider M9 is documentation
only in this candidate and requires its own implementation/build/acceptance.

For future F-Droid-compatible releases, compile with36.1.0 but sign with
**apksigner 34.0.0** using the existing release key. The actual Linux proof passed
signature copying with34 and failed with36.1 on the same unsigned APK. See
[signing instructions and tool hashes](signing.md#signing-future-f-droid-compatible-releases).
