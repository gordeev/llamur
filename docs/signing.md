# GitHub APK signing

The first public APK is0.6.5, package `org.llamur.app`, versionCode13, ARM64,
minimum Android9/API28. The normal release is not debuggable and contains no
instrumentation fixtures, model weights or credentials. APK Signature Scheme v3
is verified for the supported API28+ range using Android build-tools36.1.0.

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
signers is provided. The owner's existing phone installation is left untouched.

F-Droid normally signs its own builds; that may also require choosing one update
channel rather than installing over an APK with another certificate. F-Droid
publication/signing is a separate task; no F-Droid listing is claimed here.

For maintainers: the private signing identity is stored locally, outside source
control and release assets, in an owner-only ignored signing directory. Back up
the keystore and its password separately and securely. Losing the identity prevents
ordinary updates for people who installed this GitHub APK. Never commit signing
material, passwords, API tokens or local assistant state. Builds from source can
use a developer's own key and will then belong to a different signing identity.
