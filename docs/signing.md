# GitHub APK signing

The first public APK is0.6.5, package `org.llamur.app`, versionCode 13, ARM64,
minimum Android9/API28. The normal release is not debuggable and contains no
instrumentation fixtures, model weights or credentials. APK Signature Scheme v3
is verified for the supported API28+ range using Android build-tools 36.1.0.

Dedicated GitHub release certificate SHA-256:

```text
ff394b76589fd401784d319c9534db37412240b27301e6b878bd05fd1106cf85
```

Each release attaches `SHA256SUMS` for its APK and `SIGNING-CERTIFICATE.txt`.
Verify the downloaded APK with `sha256sum`/`shasum -a256`, and, if Android SDK
build-tools are available, `apksigner verify --verbose --print-certs app.apk`.
The file checksum changes per release; the signing certificate identifies the
update channel. A checksum downloaded beside an APK is an integrity check, not
independent proof of the publisher's identity.

Earlier local development builds used an Android Debug certificate. They are
not signed by this public release key and cannot be replaced by an ordinary
in-place install of the public APK. Do not uninstall an installation containing
chats or credentials you still need. No automatic data migration between these
signers is provided. Migrating from those local builds requires an explicit data backup/removal decision.

The prepared F-Droid recipe uses the upstream-signature-only workflow: its
`AllowedAPKSigningKeys` pins this same certificate and its `binary` points to the
GitHub APK. F-Droid must reproduce that APK from public source before publishing
it. This preserves cross-channel updates without giving F-Droid the private key
or password. A mismatch blocks that version; it must never silently fall back
to a different key. [Official workflow](https://f-droid.org/docs/Reproducible_Builds/).

Published 0.6.5 cannot pass that comparison because it contains an earlier local
Git revision in APK metadata. The historical build is disabled in the recipe.
The 0.6.6 source fixes remove checkout-dependent APK metadata and pin native
upstream build identity, and normalize source/NDK paths. VersionCode 14 and tag v0.6.6 separate this release
from 0.6.5. Activation requires matching clean Linux build and signature checks. Existing
0.6.5 users can update normally to a newer APK signed with this same key.
See [F-Droid preparation](fdroid.md) for the actual local checks and remaining
server-side gate. No F-Droid listing is claimed here.

For maintainers: the private signing identity is stored locally, outside source
control and release assets, in an owner-only ignored signing directory. Back up
the keystore and its password separately and securely. Losing the identity prevents
ordinary updates for people who installed this GitHub APK. Never commit signing
material, passwords, API tokens or local assistant state. Builds from source can
use a developer's own key and will then belong to a different signing identity.

## Signing future F-Droid-compatible releases

Keep compilation on build-tools 36.1.0, but sign with **apksigner 34.0.0**.
The local 36.1 signature failed apksigcopier against its actual unsigned APK;
34.0 passed on exactly the same unsigned bytes, with the same release key.
This matches the [documented F-Droid issue](https://f-droid.org/docs/Reproducible_Builds/#apksigner-from-build-tools--3500-rc1-outputs-unverifiable-apks).
`bench/apksigner 34.lock.json` records the official Google archive and JAR hashes.
Only the Java signing tool is needed; it is not an app dependency or APK asset.

Install `build-tools;34.0.0` alongside 36.1.0, then use its `apksigner`, or the
hash-verified extracted JAR. After aligning with zipalign 36.1.0:

```sh
"$JAVA_HOME/bin/java" -jar "$APKSIGNER_34_JAR" sign \
  --ks "$KEYSTORE" --ks-key-alias llamur-release \
  --ks-pass "file:$PASSWORD_FILE" --min-sdk-version 28 \
  --v4-signing-enabled false --out signed.apk aligned.apk
```

The private key/password paths are owner-local inputs. Do not put their
contents in environment variables, shell history, sources or release assets.
Verify the resulting signature and use apksigcopier against the **actual Gradle
unsigned APK**; a strip/copy round trip of one signed APK alone is insufficient.
The earlier versionCode13 proof is historical and was not published. Release
0.6.6 uses versionCode 14 and the existing certificate; its two-build/signature
and in-place upgrade results are in [the verification report](releases/0.6.6-verification.json).

0.6.7 uses versionCode15 and the same certificate. Its Linux build, signature-copy
check and installed-APK verification are in [the report](releases/0.6.7-verification.json).
No new two-clean-build reproducibility result is claimed for this version.
