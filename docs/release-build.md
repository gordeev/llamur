# Rebuilding the standalone release candidate

The current application version is 0.3.0-dev, versionCode3, application ID
`org.motoai.runtime`, ARM64/API28+. No model, token, signing key or local `.tools/`
cache is needed in the source package. Original upstream files and hash-locked
patches are included; do not modify the pristine vendor copy.

Current 0.3 evidence lives in `docs/progress.md` and
`build/release-candidate-0.3.0-20261026`. The older reproducibility/0.1 artifact
sections below describe the frozen native baseline. The 0.3 Linux build reuses
verified caches; it is not claimed as another clean reproducibility pair.

## Toolchain

Use Linux x86_64, JDK17, Gradle8.14.3, AGP8.13.2, Kotlin2.3.0, SDK platform36,
build-tools36.1.0, stable NDK28.2.13676358/r28c and CMake3.31.6. The Gradle wrapper
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
match. The draft recipe has no Binaries verification promise or submission.

## Signing and installation

Unsigned APKs cannot be installed. The delivered local development-signed release
uses the existing Android development certificate, **not a production identity**.
Its Android debuggable flag is false. The separate Linux test-enabled APK is for
instrumentation only; all14 native libraries match the actual release byte for
byte, including JNI. The test-enabled E UI lacks only the later5-line base URL
copy button; that button was checked in the actual G release. Do not distribute that test-enabled APK as a release.

Before publication choose an owner-controlled application ID/version/update-key
strategy or the normal F-Droid signing workflow. No production key, tag, commit,
upload or catalog submission was created. Never put private signing keys or HF/
API tokens into sources. Changing signing keys normally prevents an in-place
update; plan migration before a public release.

## Evidence and scope

Actual signed non-debug release loaded a public tiny GGUF, answered through
its real UI and copied the API base URL with verified real paste on a16KiB
ARM64 Android16 emulator. Full lifecycle/background and
contract checks use the native-identical test-enabled build. Real main-model,
context, Aider and performance evidence with exact APK/model hashes is listed in
progress.md. Tiny developer fixtures are not bundled or distributed as models.
Known limitations: large contexts are slow/experimental, arbitrary GGUFs/devices
are unqualified, the current app UI is Russian, and valid gated/private HF access
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
