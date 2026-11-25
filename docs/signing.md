# APK signing and updates

## 0.7.0a nightly: new signing identity

The original public release key used for 0.6.x was lost. Version **0.7.0a**
(versionCode **16**, package `org.llamur.app`) starts a new nightly signing
identity. It cannot be installed as an ordinary update over 0.6.x. No signing-key
rotation or automatic data migration from the old identity is provided.

Before installing this nightly, save anything needed from the old installation,
then uninstall 0.6.x. **Uninstalling deletes local chats, settings, saved
credentials and app-private model copies.** Model files stored in shared
Downloads remain and can be selected again after installation.

Nightly signing certificate SHA-256:

```text
8602fac88661f7eb7be62d0e3ceb91c5ef9250eec00af0e471b420f2eedf260f
```

The app's About screen identifies the nightly signing channel. This certificate
change is separate from the llama.cpp engine upgrade described in the
[0.7.0a release notes](releases/0.7.0a.md).

## Verify a downloaded APK

Download the APK, `SHA256SUMS` and `SIGNING-CERTIFICATE.txt` from the
[0.7.0a release](https://github.com/gordeev/llamur/releases/tag/v0.7.0a).
Check the APK hash with `sha256sum` or `shasum -a 256`. With Android SDK build-tools,
check its signature and compare the certificate SHA-256 with the value above:

```sh
apksigner verify --verbose --print-certs llamur-0.7.0a-arm64-v8a.apk
```

The APK checksum changes per build; the certificate identifies the signing
channel. A checksum published beside an APK checks file integrity and is not
independent proof of the publisher's identity. Android Debug builds use a separate
signer and package (`org.llamur.app.debug`).

This nightly has **no Linux reproducibility claim**. Signature verification and
APK packaging checks do not establish build reproducibility or model quality.

## Historical 0.6.x releases

Versions 0.6.5, 0.6.6 and 0.6.7 used this earlier certificate:

```text
ff394b76589fd401784d319c9534db37412240b27301e6b878bd05fd1106cf85
```

Those releases could update one another in place. Their old certificate does not
match the 0.7.0a nightly certificate. The loss of its private key prevents further
ordinary updates signed with the old identity.

The historical 0.6.6 build/signature checks are in
[its verification report](releases/0.6.6-verification.json); 0.6.7 Linux build,
signature-copy and installed-APK checks are in
[its report](releases/0.6.7-verification.json). Those results apply to their exact
APKs. They do not establish reproducibility or signature continuity for 0.7.0a.
The existing F-Droid preparation refers to the historical certificate and does
not qualify or publish this nightly.

## Maintainer key handling

Keep the nightly keystore and password outside source control and release
assets, with restricted access. Back them up separately and securely; losing this
identity would again prevent ordinary updates for its users. Never publish
private keys, keystores, passwords, API tokens or local development state.

Builds from source may use a developer's own key and then belong to a separate
signing identity. Signing and packaging checks must use the actual produced APK;
they cannot be inferred from a different build's report.
