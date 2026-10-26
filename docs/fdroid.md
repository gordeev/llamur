# F-Droid preparation

Release **0.6.6 / versionCode 14** has local reproducibility evidence. No GitLab
issue, merge request, F-Droid submission or private-key upload has been made.
The official F-Droid build server has not run this recipe yet.

## Recipe and source

Copy `fdroid/org.llamur.app.yml` to `metadata/org.llamur.app.yml` in fdroiddata,
and `fdroid/llamacpp.yml` to `srclibs/llamacpp.yml`. The active 0.6.6 build pins:

- Source commit `2313c2b39552595568934a3038069c0f7d860210` (tag `v0.6.6`).
- GitHub binary `llamur-0.6.6-arm64-v8a.apk`.
- The existing GitHub release certificate through `AllowedAPKSigningKeys`.
- llama.cpp commit `701fd25b3a55a90297dec0d925204b29084054c8`, archived from a srclib
  and prepared with hash-locked numerical/runtime patches.

No blanket scanner exclusions are used. Prebuild removes only the unused
executable-bit upstream Keynote document `idea-arch.key` from temporary source
copies, after source verification. Pristine upstream remains unchanged.

Toolchain: JDK 17, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.3.0, Android 36,
build-tools 36.1.0, NDK r28c (`28.2.13676358`), CMake 3.31.6. The recipe installs
SDK packages and checks the locked android.jar SHA-256. Local offline builds
use pre-provisioned hash-locked tools, not networked sdkmanager. SDK licenses
must already be accepted in the build environment.

## Signing and verification

The recipe uses upstream-signature-only publication (`binary` plus
`AllowedAPKSigningKeys`). Certificate SHA-256:

```text
ff394b76589fd401784d319c9534db37412240b27301e6b878bd05fd1106cf85
```

F-Droid must verify its rebuild against the GitHub APK before publishing with
our signature. A mismatch blocks that version; there is no fallback to another
key. Private signing material stays with the owner. The actual F-Droid server
comparison remains a separate gate. [Official workflow](https://f-droid.org/docs/Reproducible_Builds/).

Compile with 36.1.0 but **sign with apksigner 34.0.0**. Our real unsigned-APK
comparison failed with signer 36.1 and passed with 34.0 using the same release
key. Tool/JAR hashes are in `bench/apksigner 34.lock.json`; see [signing](signing.md).

The historical 0.6.5 recipe stays disabled: its APK contains checkout-dependent
Git metadata and native paths. Release 0.6.6 disables release VCS metadata,
normalizes source and NDK paths and pins native build identity. Model weights,
inference source, numerical patches and generation defaults are unchanged.
The published 0.6.5 APK/tag is preserved.

## Actual local results

[Version 0.6.6 verification](releases/0.6.6-verification.json) records source,
artifact, environment, scanner and signature evidence. Full local logs are in
ignored `build/llamur-0.6.6/`.

- Two clean offline Linux release+lint builds used different project, SDK and
  NDK roots, with public Git metadata present in one and absent in the other.
  Their complete unsigned APKs are byte-identical. No project/native outputs
  were reused; downloaded tool/dependency caches were shared.
- The release APK's signature verified when copied onto **each independent
  unsigned APK**. The builder never received the private key/password.
- Official fdroidserver 2.4.5 source and binary scanners reported zero fatal
  findings. Recipe parsing/lint and RU/EN fastlane ingestion passed.
- APK audit passed: non-debuggable, ARM64-only, ELF 16 KiB/alignment, expected
  permissions/notices, no models/keys, no checkout VCS/native build paths.
- 80 Python tests passed. Native scalar-reference checks on API 36 ARM64/16KiB
  emulator: 81 passed; 27 unsupported optional buffer combinations skipped.
  Numerical tolerances were not relaxed.
- The actual final APK updated public 0.6.5 in place on that emulator. UID,
  encrypted permanent history, profile and no-copy SAF access survived;
  another native tiny-GGUF response completed using the restored history.
  This is not new physical-phone/main-model quality or throughput qualification.

Both locales include current descriptions, icons, versionCode 14 changelogs and
8 real English UI screenshots covering both themes. [Screenshot provenance](../fastlane/README.md).
Earlier preparation evidence remains in
[the dated report](releases/fdroid-preparation-20261026.json).

## Before submitting

1. Run the exact final recipe in F-Droid's official environment and reproduce
   the GitHub APK there. Keep any failing build disabled.
2. Review dependencies, notices, artwork provenance and maintainer eligibility.
   The previously reviewed inclusion policy normally requests three months of
   active development, with listed exceptions; our short public history does
   not itself establish one. Optional proprietary provider presets are
   conservatively declared `NonFreeNet`; reviewers decide the final label.
3. Model licenses remain separate; the APK does not redistribute model weights.
4. Submit only after the owner's explicit instruction.

[Submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/),
[metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/).
