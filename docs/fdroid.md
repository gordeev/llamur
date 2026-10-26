# F-Droid preparation

This is local packaging work, not a submitted or accepted F-Droid application.
No GitLab issue/MR, store upload or signing-key transfer has been made.

## Recipe and catalog

`fdroid/org.llamur.app.yml` replaces the old unresolved template. It pins the
0.6.6 source tag `v0.6.6` (versionCode 14), APK download and release
certificate. The final packaging update pins the full source commit after
local cross-path Linux verification. Copy it to
`metadata/org.llamur.app.yml` in fdroiddata; its companion `fdroid/llamacpp.yml`
belongs in `srclibs/llamacpp.yml`. Do not submit the disabled historical build
as a working release.

The recipe fetches pinned llama.cpp as a srclib, archives its exact revision and
applies the hash-locked CPU patches. Upstream remains pristine. It removes only
the unused executable-bit upstream Keynote presentation `idea-arch.key` from
the temporary build copies, after source verification. This is not a signing
key. No blanket scanner exclusions or proprietary accelerator SDKs are used.

Tool versions: JDK 17, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.3.0, Android 36,
build-tools 36.1.0, NDK r28c (`28.2.13676358`), CMake 3.31.6. SDK packages are
explicitly installed in the recipe; android.jar SHA-256 is checked against the
locked platform archive to reject silently changed SDK revisions. Local offline validation uses pre-provisioned,
hash-locked tools rather than running networked sdkmanager inside the container.
SDK licenses must be accepted in the build environment.

Both `fastlane/metadata/android/en-US` and `ru-RU` now include current catalog
copy, icon, versionCode 14 changelog and eight real English UI screenshots,
covering both themes. See [asset provenance](../fastlane/README.md). No models,
private chats, tokens or fabricated device screenshots are bundled. These
assets are included in the 0.6.6 source revision. Changing the working tree does
not change an existing public tag.

## Preserve GitHub signing identity

The recipe deliberately uses **upstream-signature-only** publication: per-build
`binary` and `AllowedAPKSigningKeys`. Certificate SHA-256:

```text
ff394b76589fd401784d319c9534db37412240b27301e6b878bd05fd1106cf85
```

F-Droid rebuilds unsigned source and verifies it against the GitHub APK using
signature copying. It publishes only a matching APK. The private keystore and
password stay with the owner. This workflow preserves normal updates between
GitHub and F-Droid; installing an APK with a different signer cannot do that.
[Official reproducible-build workflow](https://f-droid.org/docs/Reproducible_Builds/).

The published 0.6.5 APK contains an app Git revision from the earlier local
checkout in `META-INF/version-control-info.textproto`, rather than the public
tag's revision. That alone prevents byte-for-byte reproduction. The actual comparison also
found host/path differences in all 14 native libraries; other APK payload
entries matched. Native diagnostic strings contain checkout paths, which the
pending compiler prefix-map removes. The existing
GitHub APK/tag is preserved, and its historical recipe entry is disabled.
`apksigcopier 1.1.1` successfully stripped/copied the actual APK signatures back
into a byte-identical APK. That round trip alone did not prove that the original
unsigned APK was compatible: signing a fresh Linux APK with 36.1 failed the real
unsigned comparison. Signing those same bytes with 34.0 passed. Future releases
must use apksigner 34, while compilation stays on 36.1. See [signing](signing.md).

The 0.6.6 source disables release VCS metadata and pins llama build identity
to `upstream.lock.json` and normalizes compiler source/NDK/debug paths. Native
source archives cannot inherit the enclosing app's Git hash or clone depth. These changes concern build metadata, not model
weights, numerical kernels or generation settings. Runtime sources/patches are
otherwise unchanged. Release 0.6.6 uses versionCode 14 and a new source tag;
do not replace 0.6.5 in place or activate its disabled recipe.

## Earlier local preparation checks

The working artifacts and full logs are under ignored
`build/fdroid-preparation-20261026/`. See the
[curated result](releases/fdroid-preparation-20261026.json) for hashes and scopes.

- Official fdroidserver 2.4.5 parser and metadata lint passed on the concrete
  certificate-pinned recipe.
- Official fastlane ingestion and image processing passed for both locales;
  descriptions, summaries and changelogs fit their limits, with 8 screenshots
  and an icon per locale. Catalog PNGs match their original captures exactly.
- The actual source prebuild commands ran, with the SDK installation step
  replaced by the recorded pre-provisioned tools. Official source scanner:
  zero fatal findings, warnings or errors; no scanignore/scandelete.
- Clean offline Linux release+lint passed for both the exact public 0.6.5 source
  and the pending fixed source. Their outputs are compared explicitly; neither
  build reused project/native outputs from the other.
- The fixed APK contains no checkout VCS metadata or absolute project/NDK/home
  paths in its 14 packaged native libraries. ELF 16 KiB/alignment/notice audit and
  official binary scanner passed.
- Signing that APK with the existing release key using apksigner 34 and copying
  its signature onto the actual Gradle unsigned APK passed. No key was mounted
  in the builder or uploaded. This is not an independent server rebuild.
- 80 repository Python tests and shell syntax checks passed.

These are local checks, not a run on F-Droid's official build server. The exact
source snapshot, APK hashes, binary scans, Linux build/lint and comparison
outcomes are in the curated result; historical 0.1 Linux equality is not used as
proof that the published 0.6.5 matches.

## Release 0.6.6 verification

Two fresh offline Linux builds from the exact source commit must agree across
different project/SDK/NDK paths, then the signed GitHub APK must verify against
both unsigned APKs using apksigcopier. The result and exact immutable revision
are added to `docs/releases/0.6.6-verification.json` on the public main branch
after verification. The source freeze itself is not a claim that those checks
have already passed. No submission is authorized by this release task.

## Before submitting

1. Publish a new release from the fixed public source, with a fresh versionCode,
   immutable commit and Linux-built APK signed by the existing release key.
2. Add that exact revision/version/APK to the recipe and reproduce it with
   apksigcopier. Keep a mismatch disabled; do not switch to an unrelated signer.
3. Repeat the actual recipe/scanner/build checks with the final public checkout
   and F-Droid build environment. Review dependencies, notices and artwork
   provenance. Model licenses remain separate; no model is redistributed.
4. Confirm maintainer eligibility. Current policy normally requests three
   months of active development, with specified exceptions; this short public
   history does not itself prove an exception. Optional proprietary cloud
   presets are conservatively declared `NonFreeNet`; reviewers decide the label.
5. Only after the owner's instruction, open a metadata MR in fdroiddata.

[Submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/),
[metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/).
