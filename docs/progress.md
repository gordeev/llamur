# Progress and evidence —2026-10-26

## 0.6.4 public README, English screenshots and project link — verified

English README and Russian README.ru describe the app, the pinned Android sample
comparison, unchanged original weights/F16KV, features, tested GGUFs, device scope
and measured benefits with explicit limits. The controlled Ornith512 prefill
82.58→49.57s/1.649× result is separated from default-OFF operation, rejected Gemma
speed promotion and same-engine short UI observations. Public extracts retain all
six prefill pairs including warm-up, trial order, timings, artifact hashes and
intervals; short B/P/P/B rows are also exposed under `docs/benchmarks/`. Arithmetic
and local README links/images checked. Candidate Samsung/OnePlus/Sony devices
are labelled untested hardware-based inferences with manufacturer sources.

`docs/screenshots/` contains twelve direct unedited English UI screenshots,
six in each theme, and capture identities/hashes. `readme-ui-20261026T221435Z`
passed four real provider/service requests per theme to a public scripted loopback
fixture. Text is expressly demo content, not generated main-model quality evidence.
Normal APK contains no ReadmeScreenshots or other instrumentation classes.

Settings → About llamur / О llamur now shows version, brief purpose and the
clickable https://github.com/gordeev/llamur project link. RU/EN resource parity
is826 pairs. API36 ARM64 emulator `localization-20261026T221504Z` passed1686 checks
with font1.3, including both themes, source/draft recreation and About URL visibility.
Release build/lint, Python80 tests and whitespace PASS. All14 native libraries
remain byte-identical to0.6.0; no weights/engine computation edits this iteration.
Normal0.6.4-dev/version12 APK SHA256
`985af80924e81484ba360857df185904bb975fcce0d5fb7bf6dfaf40a46dae25`.

Owner explicitly authorized a fresh public history and push to gordeev/llamur
after checks. Publication uses a separate clean source checkout with a single
root commit; the old private Git directory is retained locally, never imported.
Commit/tree/remote verification and installation reports are retained separately
under `build/llamur-0.6.4-20261026/`. No production signing identity, GitHub binary
release or F-Droid submission is created by this source publication.


## 0.6.3 form spacing and public source preparation — verified

Shared provider forms now space inputs and give checkboxes/spinners52–56dp
minimum heights. HF uses density-correct padding, spaced fields and styled action
buttons. Local API/context fields share8dp outer spacing; model profiles reuse
that field style with a48dp seekbar and56dp checkbox targets. Composer input and
message content retain their existing layout. No network or inference logic edit.

Expanded the isolated public UI fixture to capture21 views/scroll positions per
theme, including home/chat/context, settings/storage/theme/language, models/device
storage, HF, profile, local API, provider presets/editor and model activation.
Actual geometry assertions cover adjacent fields and action buttons. Both themes
were visually inspected with normal/1.3 font scaling on API36 ARM64/16KiB emulator:

- `bench/results/localization-20261026T220535Z`:1678 checks PASS, font1.0.
- `bench/results/localization-20261026T220609Z`:1678 checks PASS, font1.3.
- `bench/results/provider-updates-20261026T220641Z`:60 checks PASS; public HTTP,
  consent, catalog/attachments/settings regression; no live/paid provider call.
- Python80 tests, shell syntax, release build/lint and whitespace PASS.

Normal non-debug0.6.3-dev/version11 SHA256
`6300a0e26b60681bd0e062cedf112adcc7a491cd7d3e06d2a368eca77dc20a35`.
All14 native libraries remain byte-identical to0.6.0. No weights changed or new
speed/semantic inference claim. Artifacts: `build/llamur-0.6.3-20261026/`.

Public source cleanup adds ignore rules for assistant/skill/orchestrator state,
IDE/config files, environment credentials, signing keys and generated artifacts.
Already-tracked agent files, local plan/task and placeholder refs are removed
from the Git index and retained locally. Three audit topology paths changed from
absolute workstation paths to the same repo-relative evidence path; measured
values/hashes of the referenced topology remain unchanged. Offline build exporter
no longer selects local assistant instructions/plans.

`scripts/check-public-source.py` checks Git-visible tracked and unignored source,
common credential/private-key patterns, private paths and symlinks, with redacted
findings; optionally checks reachable Git history or exports the current clean
files without history. Current tree passes; no token/key-pattern or high-entropy
credential assignment found. The old private history intentionally fails its
artifact/home-path check: it still contains agent state and former local paths.
It has not been rewritten and must not be pushed as public history. Fresh source
export: `build/llamur-public-source-0.6.3/`; see `docs/public-source.md`.
Pattern scanning is bounded evidence, not proof against every possible secret.
No commit, remote creation, push or publication performed.


## 0.6.2 button spacing — verified

Owner screenshot showed Activate/Load catalogue touching. ProviderUi's shared
button builder and ChatActivity's vertical action builder now give actions12dp
top margin. Explicit composer attachment-chip layout remains unchanged. This
also separates adjacent context/API action buttons using the same builder;
no handlers, settings, network protocol or inference behaviour changed.
`bench/results/provider-updates-20261026T215133Z/report.json`:60 checks PASS,
including actual rendered adjacent action separation; `model-actions.png`
visually inspected. Public API36 ARM64 emulator; no live catalogue/paid calls.
Release build/lint PASS. Normal non-debug0.6.2-dev/version10 APK SHA256
`7ddeed6456e2b40bf3be2a8d1e54560081a606cbb1a680c07a6036294b5ec933`.
All14 native libraries still match0.6.0 byte-for-byte. Artifacts and phone
installation metadata: `build/llamur-0.6.2-20261026/`. Full language/model/background
regressions not repeated for a layout-only change. No commit, push or publication.


## 0.6.1 arbitrary HTTP providers — verified and installed

Owner explicitly requested any entered HTTP base URL, rather than a fixed
Tailscale-host exception. Saving a new HTTP address opens a warning naming that
exact endpoint and explaining exposure of messages/files/credentials. Accepting
continues the save; cancelling leaves the edit unsaved. Consent is stored in the
profile and cleared when host, port or API path changes. Legacy opted-in loopback
profiles keep compatibility. No hardcoded server list or new dependency.

Provider lists and chat model choices start with ⚠ HTTP, before long model IDs.
The editor puts a readable amber warning first, above the endpoint input; actions,
active models and catalogue also identify the transport. RU/EN strings added.
Android platform cleartext policy permits dynamic hosts, while ProviderStore and
RemoteNetwork require the profile's exact approved base before opening a socket
or applying credentials. Standard HTTPS certificate validation, no redirects and
HF's HTTPS-only policy remain. Incoming LAN API TLS policy is unchanged.

Final API36 ARM64/16KiB emulator reports (same frozen debug/test APK pair):

- `bench/results/provider-updates-20261026T214625Z`:61 checks PASS, real nonloopback
  Android HTTP catalogue/chat, no-grant and stale-grant rejection before network,
  persisted consent/rename, warning/cancel/reconfirm UI, public OpenRouter468-model
  HTTPS catalogue and existing attachment/service fixtures.
- `bench/results/providers-emulator-20261026T214542Z`:91 checks PASS, three protocol
  adapters, two-turn context, cancellation, screen-off completion and real SystemUI
  notification tap back to the correct chat. Previous213809Z failed because the
  test tapped coordinates during the shade animation; the test now waits700ms
  after expanding it, with no notification/product code change in this iteration.
- `bench/results/localization-20261026T214646Z`:1666 checks PASS, all823 RU/EN pairs,
  formatting and existing locale/theme/navigation/draft retention checks.
- Python77 tests, release build/lint and diff whitespace PASS. Host read-only
  catalogue probe of the owner's provided endpoint returned `qwen3.8-flash-next`;
  no private generation, credentials or paid calls used in this iteration.

Frozen debug SHA256 `c4a0d587511846481a2588edd49a94c11ad6b97356d1ae47be5e401d90023a82`;
final test SHA256 `4b5a4f1d0e48848e5b1e4e7e03222ce428bf0fa279753370292cbeb4059a9dac`.
Normal non-debug0.6.1-dev/version9 SHA256
`f983ac113467e9eddf7bbb3d07b9dbab6983c9c5e757324243c135d8e300f97a`.
Installed over0.6.0 in Android owner0 with unchanged UID and no data clear; main
process launched. No fixtures installed into the owner's package. All14 native
libraries match0.6.0 byte-for-byte; no weights changed or speed claim. Artifacts,
source hashes and installation metadata: `build/llamur-0.6.1-20261026/`.
HTTP itself provides no encryption; the app cannot verify a Tailscale/VPN route.
No commit, push or publication.


## 0.6 Russian/English UI and settings styling — verified and installed

Owner requested Russian and English UI plus visual polish while retaining the
existing UX flow; Spanish/German were optional and remain unadvertised. Extracted
815resource pairs into normal Android `values`/`values-ru`, including main chat,
providers, HF/import, notifications and errors. Language picker offers system,
Русский and English. API33+ uses Android LocaleManager/locale-config; API28–32
uses a local preference and explicit inference-process resource update. No SDK or
translation network service added. User messages, model IDs, URLs/credentials,
original chat bodies and canonical file-input markers are never translated.
Chat date labels use the selected UI locale. Technical native diagnostic data
retain their original labels. Older-OS legacy execution was not tested here.

Storage, expiry, theme and language use shared full-screen selectable cards with
descriptions, clear selected states, touch targets and per-card separation.
Remaining short model/folder/chat/file/license menus share styled rows/dividers.
Native dialogs now use matching light/dark surfaces, rounded backgrounds and
blue controls; main settings have consistent icon tiles/spacing. A theme/locale
relaunch explicitly reapplies status-bar contrast after attachment. Visual reference:
https://dribbble.com/shots/25035188-DUI-007-Settings-Screen (inspiration only; no assets copied).

The compaction regression exposed a real race: a late page refresh after undo
could reopen Context over Settings. SettingsPages now owns a navigation revision;
compaction/history replies refresh a page only while the initiating navigation is
still current. Added immediate navigation after undo to the regression; original
chat messages and compaction state remain backend-owned.

Final frozen debug/test hashes:
`cad1d4ad171b9b1f95189dc70ea03ac4857da44824a3b9d1fcafa09266f20c44`
/ `6619ae274d64d4e9738a8efd1b0aaa3681479b060be9cec1e71559cb55af8727`.
API36 ARM64 emulator, actual16KiB pages:

- `bench/results/localization-20261026T205349Z` and
  `bench/results/localization-20261026T205358Z`:1650checks each PASS with font
  scales1.0/1.3. All815RU/EN resource pairs formatted by Android; actual locale/theme
  relaunch; source/chat/composer preserved; retention confirmation cancel; menu
  divider/callback; status-bar contrast. Public light/dark screenshots inspected.
- `bench/results/providers-emulator-20261026T205408Z`:84checks PASS, three
  protocols, screen-off response and actual completion-notification tap.
- `bench/results/provider-updates-20261026T205436Z`:42checks PASS, file transport,
  encrypted large body reopen, provider/model/HF/settings UI.
- `bench/results/compaction-20261026T205454Z`:50checks PASS including the new
  late-reply navigation case. Tiny native summary still correctly fails its
  incomplete-output quality gate; no new semantic native/cloud quality claim.
- History40checks on the final APK, Python77tests, release lint/build, shell syntax
  and diff whitespace PASS.

Normal release SHA256:
`7d0e31adf77755444f45da45d556170b013a94172b30aa553e8669b986f616ca`.
`build/llamur-0.6.0-20261026/` retains APK, reports, source identity and installation
metadata. Installed over0.5 in owner0 with the same UID/certificate and no data
clear; main process launched. No fixtures installed into the owner's package.
All14native libraries are byte-identical to0.5; no weights/native source edited,
no inference speed claim. No commit, push or publication.

## 0.5 context compaction — verified and installed

Owner requested manual and opt-in automatic compaction, original history following
chat retention, inspectable/undoable summaries, checks and phone installation.
Added one separate working-context layer to ChatStore. Original message objects,
attachments and later turns remain unchanged. Permanent original/summary bodies
share encrypted SQLite/retention; temporary data stays in RAM. Preview/discard,
apply/undo and per-chat pause/resume have explicit UI. Auto defaults off; it runs
before sending around80% of known context including output reserve, then admits
the original question once. Rolling same-source summaries have a16-call bound.
No native source, weights, quantization, sampling of ordinary answers or low-level
inference kernels changed. All14release native libraries match0.4 byte-for-byte.

GGUF uses the loaded tokenizer even on overflow. API uses known catalogue/explicit
window plus usage/conservative text estimates, visibly approximate. Hidden/signed
reasoning is not summarized or replayed across new context epochs. Cancellation,
restart, incomplete/empty/non-reducing summaries and stale sources fail without
truncating original history or silently retrying paid work. Undo restores all
originals plus subsequent turns and pauses auto for that chat. Summary generation
uses the selected model; it is lossy and model-dependent, not a guarantee that all
facts survive or that an arbitrary dialogue fits unlimited context.

Final frozen debug/test APKs: `4c22634e0a2124b1f2093c1a876038a0ac63cb9a232b522dec4cb9b292a5aa2b`
/ `9603672efd0bf99a16eee069432547f1159475faa5646a15f9a8eb0f7b536a11`.
API36 ARM64 emulator, actual16KiB pages:

- `bench/results/compaction-20261026T200228Z`:49checks PASS: bounded multi-call
  HTTP fixture, original immutability, preview/apply/undo after later turns,
  reasoning epochs, encrypted reopen/temp exclusion/restart, default-off UI,
  auto continuation once, manual/auto cancellation, truncated/error no-retry,
  unknown API window and source-change rejection; real native tokenizer/overflow.
  Tiny stories GGUF local summary hit its output limit and was correctly rejected;
  no successful semantic local compaction on Ornith/Qwen is claimed by this test.
- `bench/results/providers-emulator-20261026T200234Z`:84checks PASS, all three
  protocols, actual screen-off completion and notification tap, dark messenger UI.
- `bench/results/provider-updates-20261026T200303Z`:42checks PASS, files/settings
  regressions; no new live catalogue/account call in this iteration.
- History instrumentation:40checks PASS; Python73tests PASS; debug/release build,
  release lint and diff whitespace PASS. Public preview/active/settings screenshots
  inspected. No fixture class is present in release DEX.

`build/llamur-0.5.0-20261026/` contains reports, source identity, APK/hash and
installation evidence. Signed normal release SHA256:
`9d881d2b56e20bd1785e072c8696a8677147e62e43c7a626c16f01237c807f2a`.
Installed over0.4 with `adb install -r --user0`, same appId/certificate, no data
clear; owner0 main process launched. Phone used only for update/open, no fixture
chats or inference tests. Existing development certificate retained; no commit,
push or publication.

## 0.4 provider/files/settings update — verified and installed

Owner requested OpenRouter fixes, full-screen settings/model/provider flows,
working thinking toggle, explicit attachments/downloadable files, and a checkpoint.
Pre-change0.3.2 saved as initial local commit `967243f` by direct owner request;
no push/publication. New fixes remain in the working tree; no further commits
under the refreshed repository worker rules.

OpenRouter's live public catalogue had38280JSON values, exceeding the former
32768 default. Discovery now has a separate8MiB/262144-node bound and retains only
compact capabilities. Standard public OpenRouter discovery is anonymous; inference
still requires the configured credential. A one-off authorized temporary-key
request for `nvidia/nemotron-3.5-super-vl:free` returned HTTP404: free model training
endpoint excluded by the account's data policy. This is not model-ID absence.
No account setting changed, no paid fallback, key never persisted in files/reports.

Main home brand heading removed. Full-screen in-activity back stack now hosts
provider list/editor/catalogue, local models/profile/HF/storage. Thinking is a
Switch that updates in place. Separate `.debug` applicationId prevents replacing
the owner's release or creating fixture chats there. Attachments and downloads
are bounded/platform-native; details/privacy limits are in docs/providers.md.
No native runtime source, quantization or weights changed.

Final evidence:
- `provider-updates-20261026T191843Z`:44checks PASS on final debug APK
  `f73afe4699a424de8dc256f5e02bfda0d93595454fe118db0043b88cc52b3d4d`.
  Live public OpenRouter catalogue468models through service/cache/IPC with no key;
  catalogue UI/search, files/SAF save byte equality and encrypted reopen all pass.
- `providers-emulator-20261026T192252Z`:84checks PASS, dark theme, three protocols,
  credentials/reasoning/Stop/unchanged-source guard, screen-off answer and actual
  system-notification tap opens correct chat. Test-only label migrated from
  hardcoded MotoAI to PackageManager's actual label. Prior `191959Z` completed
  screen-off answer/notification but timed out looking for the historical name.
- Python73tests PASS; final debug/release assembly and full Android lint PASS33s.
- Ordinary signed non-debug release installed in owner profile with `adb install
  -r --user 0`, Android appId unchanged; no data clear. Android16 dumps `appId`
  rather than the old `userId` field. Phone19%: only the light install/open ran;
  no model load or phone fixture tests. Saved notification permission preserved.
- APK `build/llamur-0.4.0-20261026/llamur-0.4.0-dev-arm64.apk`, SHA256
  `8e47258b140c131a532c5dc0a895a333a4241050bce5bad8ee1f22aa22678feb`.
  All14 native libraries byte-identical to0.3.2; no weights/native-source changes.
  Release DEX contains no instrumentation/fixture classes. Source identity,
  lint/build logs, reports, acceptance and installation receipts are beside APK.
  Secret-pattern scan of changed sources found no temporary API/private key.

Earlier attempts (retained, not promoted):
- Python73tests PASS (`/tmp/llamur-python-regression.log`).
- `provider-updates-20261026T185652Z`:33checks PASS, live catalogue468models,
  three attachment encoders, large SSE/image and encrypted SQLite reopen, basic UI.
- `provider-updates-20261026T190247Z`: failed driver looked for dialog text in
  the activity-only view tree; actual received image/card was present.
- `provider-updates-20261026T191046Z`:41checks PASS, including service/IPC send,
  generated file receive, duplicate admission guard, real DocumentsUI save and
  byte equality, follow-up preserves input image, settings/thinking/screens.
- `provider-updates-20261026T191452Z`: added catalogue UI test exposed a nested
  `runOnMainSync` in the test driver. Fixed driver; not a production crash.
- Initial full lint caught a literal0 where Typeface.NORMAL is required. Fixed
  source constant; final build/lint passed. No successful real-account generation
  or paid image inference inferred from fixture acceptance.

## Adaptive/themed icon correction: 0.3.2-dev installed

Owner pointed out the small nested icon on the physical home screen. The0.3.1
foreground incorrectly contained a pre-rounded tile. Original retained; built-in
imagegen extracted the character to `docs/branding/llamur-foreground.png` (exact
prompt alongside it). Adaptive layers are108dp with a transparent60dp character,
essential details inside the66dp safe circle, full-bleed dark background, and a
dedicated vector monochrome layer on API33+. Circle/rounded-square/square color
and themed previews inspected. No broad inference/UI tests requested or run.

Release assemble PASS29s; signature/package/non-debug metadata verified. All14
native libraries byte-identical to0.3.1. `adb install -r` preserves app data.
Physical Nova Launcher retained its themed substitute even after a restart;
the icon picker showed both the wrapped theme icon and the correct native icon.
Selected the native icon for this shortcut only and saved. Final actual home
screen crop confirms the large lemur without the nested light circle. No launcher
data cleared, no other icons/layout/settings changed.

APK: `build/llamur-0.3.2-20261026-adaptive-icon/llamur-0.3.2-dev-arm64.apk`, SHA256
`80e98a182f66047806ad89efb0b3d7f7550b304fab858ce1ca717cded14713af`.
Installation receipt, mask previews and icon-only physical screenshot crop are
beside the APK. Full private home screenshots remain outside the repository.

## Owner-supplied launcher icon: 0.3.1-dev installed

Owner supplied the lemur artwork and requested another build. Original preserved
in `docs/branding/llamur-source.png`; density-specific legacy and adaptive launcher
resources generated by `scripts/generate-launcher-icons.sh`. Dark adaptive
background replaces the old green background. Package remains `org.llamur.app`;
versionCode4 / 0.3.1-dev installed with `adb install -r`, preserving app data.
Release assembly PASS27s; APK signature/package/non-debug metadata verified.
All14 native libraries are byte-identical to the previous llamur release;
weights/runtime behavior unchanged. No additional test suites run.
APK: `build/llamur-0.3.1-20261026-icon/llamur-0.3.1-dev-arm64.apk`, SHA256
`39756b9bd911ed876fdffe80a33a12bcff3576c5eb82dc57c34aac43f5ee9bac`.
Build log, package metadata, hashes and installation receipt are beside the APK.

## Latest owner instruction: llamur rename, build/install only

Owner explicitly stopped additional tests and requested a quick rename to `llamur`,
applicationId `org.llamur.app`, normal release build/install for field use.
Visible app/notifications/download directory now llamur; new files go to
Downloads/llamur/Models. Existing Downloads/MotoAI/Models files are preserved.
Internal Kotlin/JNI namespace stays compatible; public Android package is new.
Native sources/weights untouched. Android treats the new package as a separate
app: old private data/URI grants cannot simply be inherited. Keep old package
with its data; reconnect existing files through SAF without making another copy.

Mac `assembleRelease` PASS32s, no additional test suites after rename. APK:
`build/llamur-0.3.0-20261026/llamur-0.3.0-dev-arm64.apk`, normal non-debug
release signed with the existing development certificate. First install attempt
was blocked before any phone state change: ADB reported no physical device.
After owner reconnected the phone, install and launch succeeded. Installed APK
SHA256: `0d3af30af249b01e2fd26ba1ba0ff2b86d7d1defceca25d9dcfc2aee84dd6880`.
Installation metadata is recorded in `build/llamur-0.3.0-20261026/installation.json`.
Old application/data and original models are preserved; old test APK removed.
No additional tests were run after rename.

Pre-rename checks below remain explicitly tied to their old package/builds.
Additional final evidence: phone background81checks PASS
`providers-phone-20261026T160213Z` (screen-off answer + actual system notification
tap opens correct chat), dark/granted emulator84PASS `providers-emulator-20261026T160042Z`,
light/font130/denied emulator83PASS `providers-emulator-20261026T161017Z`,
local/API/reopen-loaded-chat checks PASS `product-emulator-20261026T160724Z`.
These are not presented as post-rename tests.


## Active owner redesign: messenger home, folders and opt-in persistent chats (M10)

Owner rejected the0.2 sidebar-first UI and provided two Telegram screenshots as
visual references. Do NOT copy their private contacts/messages/assets into app or
reports. New home is a chat list with search, horizontally swipeable folder tabs,
floating centered new-chat icon, bottom tabs Dialogs/Local API/Settings. Empty
home offers Create chat. Chat screen has Back, AI avatar/source info, search,
pinned messages, composer arrow→Stop. AI info shows local/API and reported cost
only if supplied. Local API tab selects local served model and public id/name.
Settings controls Never save / Keep permanent / Auto-delete; creation chooses
temporary or permanent. **This explicitly supersedes earlier RAM-only policy for
opted-in permanent chats. Default stays Never save.** Local models/native math/
original GGUF are untouched. No agents requested/allowed; no commits/publish.

Design/implementation/verification plan: docs/messenger-design.md. Root0.3.0-dev /
versionCode3 implements encrypted SQLite owner/lazy bounded cache, temporary RAM
isolation, policy/expiry/folders/pins/answer index, native messenger screens and
tail-only polling. Draft writes serialized and scoped to chat/request; retained
composer survives reconnection/recreation without replay. Final-update polling
coalesces events arriving while an older response is being read. API public
ID/name has native-source/session admission guard and rapid same-port restart.
Deletion removes that chat's own notifications; delayed completion checks the
answer still exists. No native/weights changes; no publish/commit.

Verified so far:
- History unique SQLite/Keystore fixtures:40checks PASS,
  `history-emulator-20261026T151815Z` (encryption, opaque state/restart, draft,
  pins/folders/TTL/active guard/Never, disk-write failure,40MiB lazy cache,
  absent plaintext/lost-key explicit recovery and delta poll bytes).
- Local/API contract + tiny native generation + actual composer/draft recreation
  PASS `product-emulator-20261026T153049Z`. Later Java UI/runtime fixes need the
  final regression below; no native promotion inferred.
- Physical phone77checks PASS `providers-phone-20261026T155015Z`, APKf14bc5…:
  actual new chat/model selection, Enter/ShiftEnter/Stop, search/pin/model card,
  home/settings/API/folder navigation, public three-protocol/key/error/no-retry
  fixtures. Permanent-history UI branch is emulator-only; no owner history wipe.
- Current normal Linux development-signed APK419351a7… in
  `build/release-candidate-0.3.0-20261026-final`: release+lint PASS,115root production
  inputs match export, all14native libraries byte-identical to accepted0.1.
  Incremental pinned-image/cache build, NOT a new clean reproducibility pair.
  APK/ELF16KiB/ZIP/notices/permissions audit PASS; official offline F-Droid2.4.5
  scanner444SUSS signatures/0fatal PASS. This does not imply catalog approval.

Retained failures and fixes: SQLite row-returning PRAGMA now read via cursor;
obsolete fake answer IDs and stale-ready test correlation corrected without
weakening production guards; API TIME_WAIT restart fixed with SO_REUSEADDR
(no SO_REUSEPORT); actual empty-folder ListView selection crash on both devices
fixed with empty-list selection guard/invalid-row ID. Previous04209f5d normal
candidate is explicitly REJECTED, retained separately. Fixture names now unique;
recover only this suite's named loopback fixtures after a main-thread crash.
Notification tests diagnosed Android16 SystemUI grouping/accessibility/input:
normal Instrumentation pointer injection is limited to target UID; real system
notification taps use UiAutomation. Final tap/normal-APK/UI/font/phone-idle gates
are still being completed; do not present incomplete whole runs as accepted.

## M9 iteration0.2 baseline before new owner steering

Normal non-debug Linux APK **5153f0da47f396d15a89ec3e749353165c8d6f5def94f2704dfcc74901339cf8**:
`build/release-candidate-0.2.0-20261026/MotoAI-0.2.0-dev-arm64-development-signed.apk`.
Existing development certificate, not production. Installed on physical phone,
own test package removed; idle proof `provider-release-phone-idle-20261026T135537Z`
(verify actual folder name in bench/results).27%,30°C,AC connected/status4 NOT
charging; no own held wake lock/foreground service/notice. Original models untouched.
No phone workload active. New owner now wants a different UI; this is an iteration,
NOT accepted as the final messenger design. Source archive/acceptance package0.2
not finished; do not run old0.1 closer against new root or overwrite old artifacts.

Linux source export `build/linux-m9-20261026`, pinned image
aca8e32761619c66f030d3092b42bf9a91fcb0ba212561ef83fce3825bd6c8f8,
network none,4CPU/6GB, caches copied from old G into a separate folder; old G
untouched. First build3m48, final `/tmp/motoai-m9-linux-final-build.log`2m16s,
release+lint PASS. **Incremental, not a new clean reproducibility pair.** All14
packaged native libraries byte-identical to old accepted0.1 Linux release3af31,
including JNI. Root110production inputs match this Linux export. Proofs inside
new release dir: native-bridge.json / production-source-identity.json. Signed
APK audit `release-audit-m9-20261026`PASS, SBOM/signature proof present. Official
F-Droid2.4.5 binary scan444cached SUSS err signatures/0fatal PASS:
`fdroid-binary-scan-m9-configured-20261026`; prior config java_paths=None setup
failure retained in `fdroid-binary-scan-m9-20261026`. New draft NonFreeNet marker
for optional proprietary presets, not catalog submission/approval.

M9 production adds ProviderStore(AES-GCM optional Keystore/session RAM credentials),
RemoteProtocol(Chat/Responses/Anthropic signed/encrypted state replay), RemoteNetwork
bounded strict UTF8/SSE/no redirect/explicit app retry/fallback/TLS bypass,
dataSync ProviderRequestService, RuntimeService IPC/ChatStore and ProviderUi.
Cloud profiles/history isolated from incoming local API. No real provider key or
paid inference supplied/tested. HTTP works only Android-permitted127.0.0.1/localhost;
ProviderStore also currently accepts IPv6 loopback but network config denies its
cleartext, so do not promise IPv6 HTTP. Native HTTPS remains platform trust.

Latest Mac debug APK **ca2e9f71827a757414da6f57a765076ad132593d6331f05ad0c0b1b602e9b1b3**.
Latest frozen test source7dd20946… in same build archive; test APK digest recorded
in its source manifest. Phone65checks PASS `providers-phone-20261026T134953Z`:
UI new+/active model/Enter/ShiftEnter/arrow→Stop/sidebar/settings;3native protocols
real loopback HTTP,2turn opaque replay, JSON fallback, encrypted dummy key/catalog,
auth/error/redirect/EOF/invalidUTF8/Stop/stale revision, no GGUF needed. Strict
raw-line UTF8 reader fixes lost valid SSE prefix when a later line is malformed;
no relaxed checks. All body/credentials fixtures public and loopback only.

Emulator latest production68checks PASS `providers-emulator-20261026T134952Z`,
**DENIED notifications**, screen-off completed, dark/font130%, captures inspected.
Earlier67checks likewise denied. **Important correction: no granted-notification
actual tap acceptance for M9 yet.** Grant runs135638Z failed notification target
helper;135910Z then failed initial capture because SystemUI shade was left open.
Latest140145Z fails again at granted notification tap (ProviderChecks line180).
Notification EXISTS and background completion passed; target selection test helper
needs diagnosis. It matches known own title+MotoAI ancestor and touches bounds;
no private phone shade dumps. Headless public emulator diagnostic is allowed.
Do NOT silently retry/claim actual tap passed. Ordinary old0.1 native notification
tap remains accepted in old scope. Retain all failed reports.

New test-only branch abortBeforeHeaders checks no duplicate posted body;140145Z
reached later BG step, so protocol checks including that branch passed, but whole
run FAILED. Test finally collapses shade/wakes screen. No production changed
since ca2 and normal release5153. M10 will require adapting these UI tests anyway.

Local product regression PASS `product-emulator-20261026T134505Z` (APK4c8… same
ChatActivity/ChatStore/native as final; only later RemoteNetwork differs): local
RAM multi-turn/API/TLS/Unicode192KiB pipe/dedup/recreation/draft restore. First two
attempts133819Z/134202Z correctly rejected tiny model by memory guard on2.5GB
emulator. No guard changed. Restarted same project AVD with `-memory4096`, actual
MemTotal4067872KiB/pages16384, then passed. Headless emulator-5580 running session
93028/log `/tmp/motoai-m9-emulator-4g.log`. Old session63403 was killed.

Actual normal non-debug Linux release UI PASS
`release-ui-emulator-20261026T135223Z`: new native model selection, tiny reply,
actual soft keyboard Send icon, API URL copy/paste and start/stop, provider presets
and OpenRouter form. Public screenshots keyboard/completed/provider-form inspected.
Mac lint passed. Final phone has normal5153, no tests. Emulator has debugca2 and
latest test installed; notification permission restored to previous false, font1.0,
shade collapsed by finalizer. No paid cloud requests, no native/weight changes.

Existing root docs/providers.md, privacy-policy/app-description/README/api,
fastlane EN/RU, PLAN and fdroid template updated for0.2 but NOW need new opt-in
persistence/privacy/UI updates for M10. Old0.1 release folder remains immutable.

## Previous M8 completed checkpoint (0.1.0-dev, historical sources)

**M0–M8 bounded release candidate ready for owner review**, one physical
MotorolaEdge40Pro/12GB/Android16, two original GGUFs and16KiB ARM64 emulator.
Final non-debug3af31 UI/composer/main-model screen-off/Dozing completion reached
EOS; real notification tap after normal owner unlock opened the expected math
answer (`release-ui-phone-20261026T123200Z`). Test instrumentation removed, model
unloaded/APIoff, app reopened idle;35%,34°C. Idle proof
`release-phone-idle-20261026T123539Z`. No MotoAI held wake lock or active notice.

Numeric/current kernels/default filled4K/background grant+deny/UIboth/Aider pass
are retained below. CPU4/native mmap/no-prefault/F16KV/fullSWA/4K defaults,
cache/transientOFF; original GGUF SHA both rechecked.16K/32K are single very slow
experimental filled retrieval points, not general-context/task certification.
Final delivery combines immutable reports and unchanged105production inputs
with source/acceptance/checksums in build/release-candidate-20261026. No commit/push/publish, production
signing identity or F-Droid submission. M9 external providers is planned only.

## Final release phone checkpoint —13:35Europe/Belgrade /12:35UTC

Owner confirmed available, normal unlock performed; repeated only short final
release UI scenario after prior timeout. Before release reinstall, exact d281
was temporarily restored under PhoneLease solely for independent PFD modelSHA.
`release-owner-ready-setup-20261026T123157Z` records setup; successful
controller report above is authoritative. Prior121900Z timeout is preserved,
not changed into a pass or discarded. No model/native/profile gate relaxed.
All phone workloads now finished; no more heavy tests queued.

## New owner scope — external provider connections (M9)

Owner asks for OpenCode-style connections to OpenRouter/OpenAI/Claude and custom
compatible endpoints. Code inspection confirms **not implemented** in current
3af31: it only serves local inference. Detailed M9/§17 added to PLAN; Chat
Completions/Responses/Messages adapters, connection/model UI, optional Keystore
keys, RAM-only chat, source/privacy isolation, streaming/Stop/background and
fixture/real-provider acceptance. This turn changes docs only, no provider calls,
keys, native sources, APK or production identity. M8 notification tap subsequently passed in the normal-release checkpoint above;
M9 remains outside this APK and its acceptance.

## Historical resumed checkpoint —12:26UTC /13:26Europe/Belgrade; then waiting for owner unlock

Owner is nearby and explicitly agreed to unlock the handset normally during
screen-off notification tests. No PIN collection or security-setting changes.
Final production105file identity/native bridge unchanged; test-only APK now
05e2c050241273b72ba0adfb2cae950872d795e59c781a90649ef6e2ff441605
(frozen beside d281). It adds a bounded owner-unlock wait and actual unlocked,
interactive Activity-window-focus assertions to avoid timing an obscured UI.

**Background grant/deny PASS**:
`bench/results/background-phone-20261026T115321Z/report.json`, including actual
SystemUI tap and stale deleted-dialog link refusal. Earlier attempts113625Z and
114028Z failed because the secure keyguard hid the notification shade; safe
structural diagnosis is retained in `background-keyguard-diagnosis-20261026`.
They are not discarded or counted as successful notification acceptance.

Ornith actual foreground B/P/P/B **PASS**
`bench/results/ui-performance-ornith-20261026T115653Z/report.json`: raw output,
token counts/finish identical (deliberate128-token overhead fixture). Product/
baseline wall ratios0.9829 and1.0488, median1.0158. Two short pairs only, no
speed promotion or long-history certification. Gemma B/P/P/B also **PASS**
`bench/results/ui-performance-gemma-20261026T120028Z/report.json`: ratios1.0362 /
1.0061, median1.0212, raw/count/finish identical. Final Aider **PASS** `aider-phone-20261026T120319Z`,156.30/651.09s,
0reflections, no cloud, code checks in networkless read-only container passed. Corrected explicit USB TLS
preflight passed (client started). Android rejected prior `nc -q 0` because
minimum is1; diagnosed without a model request, omitted option. Evidence
`usb-relay-option-diagnosis-20261026`. All failed preflights retained. Final
fixture cleanup **PASS** `fixture-cleanup-phone-20261026T121803Z`: only old own
private tiny copy existed and was removed; main original SHA both match. Actual
non-debug3af31phone UI/background trial `release-ui-phone-20261026T121900Z`
completed a main-model answer while Dozing, then **FAILED owner-unlock timeout**
after180s before SystemUI tap. Model unloaded/APIoff, app reopened idle; phone37%,
38°C. Failure report retained; no security setting changed or quality gate widened.
Owner availability question is pending. No active phone workload. Need owner
unlocked before repeating this short real-release scenario; briefly restore frozen
d281 test-enabled APK under PhoneLease for the existing pre-install full PFD SHA
check, then rerun `check-release-phone-ui.py` to install normal3af31 again. Matching
05e2c test package remains installed. Do not uninstall it before this check.
Final SHA/source/acceptance packaging remains gated on actual tap acceptance. Do not install
or launch another phone workload concurrently. No new departure cutoff.

Final Aider Wi-Fi repeat114607Z failed at preflight because phone and Mac have
different subnets; no model request ran. Explicit developer USB/TLS experiment
115119Z failed TLS EOF before the client; it is not accepted, no certificate
verification disabled. Both failed reports retained. Prior real Wi-Fi two-turn
Aider034552Z remains a historical pass on its recorded APK, not a final-kernel
repeat. Diagnose the transport and preserve that distinction.

## Paused checkpoint —08:43 local /07:43UTC

**Phone is idle and available**: MotoAI app+inference processes absent, model
unloaded, APIoff, current system Wake Locks size=0, PhoneLease reacquired/released.
Battery81%,41°C, AC connected/adaptive charging, same boot. Proof:
`bench/results/owner-pause-20261026T074236Z/report.json`. Initial power diagnostic
matched a historical REL(released) record; current active section correctly has0.

Completed bounded checkpoint:
`bench/m4-m5-quality-checkpoint-20261026.json`. Final release3af31 native bytes
match d281 test APK in all14 libraries. Final phone attention12shortbits+12long/
20runsPASS; matmul240PASS,120repack bit comparisons/0nonidentical. Default4K
(transientOFF/cacheOFF) exact3-depth/EOS both: Ornith3584+74,prefill562.96s/
decode27.78s; Gemma3584+136,423.28s/43.91s. Reports:
`context-ornith-4096-20261026T072252Z` and
`context-gemma-4096-20261026T073309Z`. Both16K/32K earlier single filled retrievals
passed on731a/**transientON**; very slow experimental, not default profile timing
or arbitrary-context quality. Matrix/app description/performance reflect scopes.

Sequencer34151 was deliberately suspended **only at the host parent**, while
Gemma4K child finished normally. After successful report/child exit, parent
terminated and lease exit verified. No inference cancellation, quality retry or
failed numerical check. `final-phone-sequence-20261026T071652Z/report.json` has
`paused_by_owner:true`, `passed:false` because remaining4jobs were NOT run; this
is not a failed quality trial or final acceptance. `pause-control.json` and
`report-before-pause.json` preserve control details. Watcher63981 finished.
**Do not restart /tmp/motoai-final-phone-sequence.py**: it would repeat all jobs.
No next phone test starts automatically. Device still has test-enabled d281 app
and adbf instrumentation installed; actual non-debug3af31 is built/archived but
not yet installed on the physical phone. Models/fixtures remain untouched.

### Resume only after explicit owner return

First check connected handset/power and acquire normal PhoneLease. Frozen paths:

```sh
MOTOAI_ACCEPT_APK=bench/results/builds/d281a711f30249cbd0282393a313cbb222fd366ab03f4597b389553fa4e23980/app-debug.apk
MOTOAI_ACCEPT_TESTS=bench/results/builds/d281a711f30249cbd0282393a313cbb222fd366ab03f4597b389553fa4e23980/tests-05e2c050241273b72ba0adfb2cae950872d795e59c781a90649ef6e2ff441605.apk
python3 scripts/run-phone-background-check.py --apk "$MOTOAI_ACCEPT_APK" --tests "$MOTOAI_ACCEPT_TESTS"
python3 scripts/run-ui-performance.py ornith --reuse-installation --apk "$MOTOAI_ACCEPT_APK" --tests "$MOTOAI_ACCEPT_TESTS"
python3 scripts/run-ui-performance.py gemma --reuse-installation --apk "$MOTOAI_ACCEPT_APK" --tests "$MOTOAI_ACCEPT_TESTS"
python3 scripts/run-aider-phone-check.py --reuse-installation --apk "$MOTOAI_ACCEPT_APK" --tests "$MOTOAI_ACCEPT_TESTS"
python3 scripts/cleanup-own-phone-fixture.py
python3 scripts/check-release-phone-ui.py --apk build/release-candidate-20261026/MotoAI-0.1.0-dev-arm64-development-signed.apk
```

Run sequentially, halt/diagnose any failure, no hidden quality retry. First runner
expects exact installed d281+05e2c; if owner changed/reinstalled them, explicitly
install frozen pair once and verify hashes before `--reuse-installation`. Tiny
public fixture is still present; its cleanup MUST follow all tiny-fixture tests.
Cleanup preserves unknown/external files, rehashes original large GGUFs, removes
only verified owned stories fixture. Actual non-debug phone UI controller needs
matching model-info tests **before** its release install; do not uninstall tests
early. It checks main-model screen-off completion, real notification tap, restores
notification permission, clears only its RAM test chat and finishes engine/APIoff.
Then remove own test package if desired, verify health/idle and final production
identity105files/pristine vendor. No phone-user private screenshots/security changes.

Finally aggregate completed gates **with the paused first5jobs**, without claiming
an observed parent return code for Gemma (its passed final app report is retained).
Prepared `/tmp/motoai-close-release-acceptance.py` needs adaptation to that explicit
pause/remaining-run evidence before use; it currently expects an uninterrupted
9job aggregate. No final `bench/release-acceptance-20261026.json` exists yet.
`scripts/package-release-source.py` refuses to package until final acceptance
passed; then create source archive, inspect for secrets/model payloads, final
README/SHA256SUMS and update PLAN/docs. Current release directory:
`build/release-candidate-20261026`, normal APK3af31…; unsignedd1ffdb… from clean
G/H byte-identical pair. Test-enabled APK is internal only. Signing is existing
development key, not production identity. Public repo/tag/signing/eligibility
remain owner decisions; no publish/commit/push. More provenance in older
checkpoints below and docs/release-build.md.

## Active checkpoint — 07:29 UTC

Final phone sequence34151 passed actual3af31 attention replay
`attention-long-phone-20261026T071652537070Z` (12short bit controls+12long/20runs),
product on d281 native-identical kernels `product-phone-20261026T072238Z`,
numericextended `numeric-20261026T072246Z`: **240pass,120repack bit comparisons,
0nonidentical, all_paths_passed=true**. Current actual default4KOrnith
`context-ornith-4096-20261026T072252Z`, artifact
`app-service-ornith-20261026T072303Z-12e3e197`, CPU4/cacheOFF/transientOFF;
last2048/3584 input~295s, no final pass yet. NextGemma4K→background→UIboth→Aider.
Queue root still `final-phone-sequence-20261026T071652Z`, log /tmp as below.
Deadline09:40Belgrade/08:40UTC; keep15minmargin, stop at logical completed block.

Prepared **not run** after sequence/freelease:
`scripts/cleanup-own-phone-fixture.py` removes only SHA-verified owned tiny fixture
through actual manager UI/knownprivatecopy, preserves external/unknown files,
rehashes both original large GGUFs. Then `scripts/check-release-phone-ui.py`
actual3af31 nondebug main-model screen-off completion+notificationtap, restores
notification permission and leaves engine/API idle. TestAPK can be uninstalled
only AFTER this final controller's pre-install model SHA check. Source/acceptance
package must wait actual final checks; do not invent a pass. Prepared final
acceptance closer `/tmp/motoai-close-release-acceptance.py` requires all9jobs+
actualnormalphonebackground+fixturecleanup and productionidentity before writing
bench/release-acceptance-20261026.json; currently no such acceptance file.

## Owner cutoff — 09:40 Europe/Belgrade (08:40 UTC), 26 October

Owner leaves with the phone at09:40; message arrived around08:15 local. Finish
an atomic logical verification block and **pause before departure**, leaving
phone idle/APIoff/model unloaded. Do not start a new long stage near departure;
keep at least15min margin. Existing final-phone sequence34151 is active; monitor
its named phases and stop at a completed boundary if it cannot safely finish.
No automatic continuation after pause; resume only when owner returns.
Question answered: Linux is the reproducible cross-build/F-Droid environment;
output remains the AndroidARM64APK, no Linux runtime needed on handset.

## Active checkpoint — 07:17 UTC

**Gemma32K PASS** `context-gemma-32768-20261026T055945Z/report.json`:
32256+27/EOS, exact ordered3 final identifiers, finite logits; prefill4585.58s,
decode21.13s, completedPSS4,959,819KiB, VmRSS4,695,704KiB, swap352,440KiB.
Original locked GGUF/F16KV/fullSWA, frozen731a/transientON/cacheOFF. AC/adaptive80%,
35→40°C, boot unchanged. Both16K/32K points now completed for both models, only
single experimental slow retrieval fixtures, no arbitrary-context/default/speed
promotion. Matrix/app description/README/performance updated. Stable74538 finished.

Final phone sequence **34151 active**, `/tmp/motoai-final-phone-sequence.log`,
report dir `final-phone-sequence-20261026T071652Z`. Attention exact3af31release
started. Then product(d281 install)→numericextended→defaultfilled4Kboth→background
permissiongrantdeny→UI B/P/P/Bboth→actual Aider, halt anyfailure. All14nativebytes
match final3af31G release. No other phone installation/workload until leasefree.
After sequence, own fixture cleanup (only proven testfiles, never originals),
actual nondebug3af31phone UI/main-model screenoff answer+notificationtap, final
source/acceptance/checksums/docs. FinalpairG/H passed, no publish/commit/key.

## Active checkpoint — 07:13 UTC

Final clean Linux G/H pair **PASS, byte-identical** unsigned
**d1ffdb8329d7d883b3af5e2e1c0ef50d8eeabdcbed70e15188191875d106f14d**;
`linux-product-url-copy-pair-20261026/report.json`, both59tasks/all executed,
strict Maven SHA verification, lintPASS, networknone, samecanonical/work.
Actual non-debug development-signed release **3af31ad0…** already archived,
current production105file identityG/H; all14libs identical d281E instrumented
kernel bridge. Actual nondebug16KiB UI send+APIURLcopy/pastePASS070445Z,
release audit/currentSUSSbinaryscan/SBOMschemaPASS. Source and artifacts remain
local, no production key/publish/commit. Phone Gemma32Kstill active74538,
last30,720/32,256 prefill, no final pass. Sequential finalphone34151 waits its
passed report+freelease; current long-attention command will use new3af31 actual
release automatically, native tests/UIperformance/Aider use frozen d281 exact
kernels. See07:07/07:00 details, then finish actual3af31 phoneUI+background,
source archive, final acceptance/checksums/docs. Do not stop here.

## Active checkpoint — 07:07 UTC

Gemma32K29,696/32,256 input (~4022s), same stable74538. Final phone queue34151
still waiting; no other handset workload. New final G non-debug release is
**3af31ad02535f1a4e08516d64f5715b1c1c5623dcca8fbf41f8fac3faf065c30**;
unsigned **d1ffdb8329d7d883b3af5e2e1c0ef50d8eeabdcbed70e15188191875d106f14d**.
G passed clean strict build+lint; H still running under45448, no pair pass yet.
All14 G packaged native libs equal d281 **byte for byte**, exact same kernels
for queued phone tests (`linux-url-copy-native-bridge-20261026`). Only5-line base
URL copy UI differs from previously instrumented E/d281 (static scope report).
New actual signed non-debug16KiB emulator UI/native send+API URLcopy→actualpaste
PASS `release-ui-emulator-20261026T070445Z`. Actual signed release auditPASS
`release-audit-url-copy-signed-20261026`, official binary scanner0 findings
`fdroid-binary-scan-url-copy-signed-20261026`, current SBOM official schemaPASS
`release-sbom-url-copy-schema-20261026`. Production identity105files now matchesG;
old E release/SBOM preserved in `build/release-candidate-before-url-copy-20261026`.
Final actual release phone controller `scripts/check-release-phone-ui.py` now
also sends a short main-model math request, minimizes+screen-off, requires own
completion notification while Asleep/Dozing, taps exact MotoAI notice, restores
original notification permission and finishes model/API unloaded. Run ONLY after
queued native-identical phone acceptance frees its lease. No secure-keyguard
bypass, power-policy change or private screenshot. Still no publish/commit/key.

## Active checkpoint — 07:00 UTC

Gemma32K stable74538 remains active:27,648/32,256 prefill (~3594s), exact final
answer still pending. Same original GGUF/F16KV/frozen731a, transientON/cacheOFF.
No concurrent handset workload/install. Queued controller terminal34151,
`/tmp/motoai-final-phone-sequence.py` / `.log`, waits for **passed** Gemma report
and released PhoneLease, then serially attention→product(d281 install)→numeric
extended→filled4Kboth→background grant/deny→UI B/P/P/Bboth→real Aider. Stops at
first failure; inspect and continue intentionally, never silently retry quality.
Each job has its own frozen inputs/report; final non-debug phone install follows.

PLAN demanded an explicit base-URL copy button, absent from the selectable URL.
Added exactly5 lines to ChatActivity; only this one of105 production files differs
from E/F (`url-copy-change-scope-20261026`). No engine/config/dependency change.
Mac build+strict lintPASS, APK10c1ec1c…; tests stilladbf. Product16KiB emulatorPASS
`product-emulator-20261026T065732Z`. Current sources frozen again.
New fresh clean Linux G/H pair terminal45448, `/tmp/motoai-linux-copy-url-pair.log`,
report `linux-product-url-copy-pair-20261026` (G running, H next). Networknone,
canonical/work, exactsame pinned image/tools and strict hashes; no native outputs
shared. Earlier E/F artifacts preserved under
`build/release-candidate-before-url-copy-20261026`; d281 remains frozen handset
native test bridge, not a claim of the new UI button. Need14-lib G↔d281 byte bridge,
new signed actual release/audit/SBOM/schema/binary scan/actual-release emulator
UI including copying URL and pasting it, then final phone non-debug UI.
Current new-source actual joined F-Droid prebuild+scannerPASS
`fdroid-prebuild-url-copy-executed-20261026` (zero warnings/fatals).
New privacy-policy.md documents RAM-only dialogs/IME/clipboard/Downloads/HF/API.
No publish/commit/production signing identity. Do not stop at this checkpoint.

## Active checkpoint — 06:47 UTC

Gemma32K stable controller terminal74538, `/tmp/motoai-gemma32k-stable.log`,
`context-gemma-32768-20261026T055945Z` /
`app-service-gemma-20261026T055954Z-6079158e`. Frozen731a/test4e4c,
transientON/cacheOFF, timeout7200. Latest >22K/32256 prefill; no pass yet.
Two earlier setup failures are retained: context054703 adb install90s timeout;
context055020 late completion of that pending install killed the newly launched
app (see interrupted-launch.json), not a context/numerical failure. Stable run
verified installed app+test hashes after package settled, with no reinstall.
No other phone install/workload until it releases PhoneLease.

Final Linux native-identical test APK d281 +tests adbf passed full product emulator
054803 and30-cycle/background/framework emulator054809. Production sources frozen;
105 files still match E/F. CycloneDX1.6 actual release inventory (37 components)
validated against official schema (`release-sbom-schema-20261026`). Actual joined
F-Droid prebuild and official source scanner passed
`fdroid-prebuild-executed-20261026`; public URLs/commit intentionally unresolved.
Remaining handset steps: exact release attention replay, product/numeric,
filled4K both, actual background grant/deny, UI B/P/P/B both, repeated real Aider,
actual non-debug main-model UI install and safe idle. Then final acceptance,
source archive/checksums/docs. No publish/commit/production key. Do not stop here.

## Latest checkpoint — 05:49 UTC

**Ornith32K PASS**, `context-ornith-32768-20261026T040306Z/report.json`:
32256input+102output/EOS, exact final ordered3 IDs, finite logits, original GGUF/
F16KV, frozen731a/transientON/cacheOFF. Prefill6092.27s,decode100.95s,
PSS6,225,044KiB(~5.94GiB),swap115,864KiB, battery39→40°C,AC/adaptive80%,sameboot.
Very slow experimental single retrieval; no broad32K/speed/default promotion.
**Gemma32K active terminal31861**, `/tmp/motoai-gemma32k.log`, frozen731a/test4e4c,
passed Gemma same-APK prefix gate025946Z;timeout7200. No other phone install/workload.

**Final clean strict Linux E/F byte-identical** unsigned APK
**e413ffa5470cae6b1ea1887a9bbfb78d79b05fadf7bbfd7dc949caf467feda65**,
`linux-product-final-pair-20261026/report.json`; lintPASSboth,networknone,
226-component full SHA verification. Signed **non-debug** actual release:
`build/release-candidate-20261026/MotoAI-0.1.0-dev-arm64-development-signed.apk`,
SHA **f2fd1bca5207c53453740450001e4ef0486cf078f27bcdc6ff4eee30f83ac303**,
existing Android development key, NOT production key. Actual release16KiB UI
load/context2K/reserve16/real composer→native reply PASS
`release-ui-emulator-20261026T053902Z/report.json`; first controller ambiguous
selector hit subtitle instead of Model button, retained053707failure.
Final release ELF16KiB/ZIP/manifest/notices auditPASS`release-audit-linux-e-20261026`
and signedauditPASS. Official current-SUSS binary scan zero findings
`fdroid-binary-scan-linux-e-20261026`; prepared source scanner zero fatal/zero
warnings`fdroid-prepared-source-scan-20261026`, no blanket exclusions.

Linux **test-enabled APK** built from E production source after release archived,
re-signed same development certificate for Mac-built instrumentation. **All14
packaged native libraries (including JNI) exactly byte-identical to non-debug
release**, no ignored buildID sections:
`linux-release-test-kernel-bridge-20261026/report.json`.
Frozen test-enabled APK **d281a711f30249cbd0282393a313cbb222fd366ab03f4597b389553fa4e23980**,
tests **adbf36a93b3d6a64aa2fb3ee049ecb5da4033beac88b24937cfdf8e889763d84**.
Archive post-build source capture891c00fb… is root working-source capture, not
exact E compiler attestation; native bridge and E source manifest are actual proof.
E contains production code identical to root; newer AndroidTest/controllers/docs
are not compiled into production. Replay Android executable frozen at
`bench/results/linux-release-test-kernel-bridge-20261026/motoai-attention`.
Complete host long+short controls:
`bench/results/attention-long-host-20261026T015200718993Z` (12+12PASS).

Linux test-enabled product +full30cycle/background regression running under
`/tmp/motoai-linux-emulator-final.log` (latest tool session, emulator only).
After Gemma32K lease frees, planned phone tasks: (1) exact RELEASE long-attention
numeric replay against731a(shortbits) using frozen executable/hostcontrols;
(2) filled4096 controls both models on d281APK with matched tests (run-context-check
now accepts4096); (3) real-phone product + background grant/deny, permission
restored; (4) UI performance B/P/P/B both models via run-ui-performance.py;
(5) install non-debug actual release, verify actual phone UI/native main-model
launch, finish source/docs/acceptance/provenance bundle and checksums.
No unsolicited new main app changes: production code frozen6cc5/Mac andE/F/Linux.
No commits/push/publish. Do not end at this checkpoint.

## Follow-up checkpoint — 05:29 UTC

Production APK unchanged **6cc5c6c7…**. Build33/lintPASS; latest frozen tests under
that archive include **UiPerformanceChecks.kt**, source snapshot3a9b1fa74c12be2fef43a90a5c8dfec807830a5e37428912b1365ee85596bd26.
Prepared `scripts/run-ui-performance.py` (do not run until M5 phone lease frees):
one loaded original model, CPU4/4K, native mmap/no-prefault, no cache/transient,
short public128-token request, excluded16-token warmup, B/P/P/B same foreground
Activity, real product composer vs baseline ordinary service, exact raw output
parity. Run both models. An observation, not5-pair/general-history speed acceptance.
Prepared `scripts/run-phone-background-check.py` for later leased real-phone
screen-off product notification grant/deny and SystemUI tap, permission restored.
`run-context-check.py` now also accepts4096 for final-model loader regression.
Visual review PASS `ui-visual-20261026T051816Z/report.json`: actual font130+keyboard,
composer/actions visible and draft preserved. Own light/dark screenshots added to
ru-RU fastlane assets. Import/copy/change-refusal/explicit-original-delete PASS051733Z.

**C strict Linux failed**, retained in`linux-product-strict-c-failed-20261026`:
aapt2 Linux JAR/POM were not resolved by the earlier incremental verification
metadata command. No verification bypass. Copied those exact public cached files
from the networkless Docker volume, recorded macOS+Linux aapt2 hashes and POM
license. Inventory now**226** components, no missing binary licenses/cache hashes.
E/F are new pristine source roots (no C outputs), same production code, full strict
metadata. **E running terminal82376**, `/tmp/motoai-linux-product-e.log`, source
`build/linux-product-e-20261026`; identical untouched F at`build/linux-product-f-20261026`.
Run F after E, then final APK byte compare/audit/scanner and installable release.
A/B pilot byte parity remains valid only for prior UI code. C/D not final.

Phone Ornith32K around28K/32256 input/~4976s, same controller7652, timeout7200.
No other phone job/install. Next Gemma32K frozen731a with025946Z gate. Keep going
through all acceptance/release tasks; no cutoff/commits/push/publishing.

## Latest checkpoint — 05:20 UTC

Physical phone: **Ornith32K still active**, terminal7652/log `/tmp/motoai-ornith32k.log`.
Artifact `app-service-ornith-20261026T040317Z-7c759032`, latest26112/32256 input,
~4455s; timeout7200. No phone install/second workload. Gemma32K next after lease
release, same frozen731a/test4e4c and passed same-APK gate025946Z. Do not treat
partial prefill as a passed context. Both16K retrieval controls previously PASS.

Current product APK **6cc5c6c75dae3a36c83d01b4b36e5f267c8bbdefe4f8342b6e7ff5c24e1b6554**.
Latest instrumentation tests **fe26df8fba59164e55bd31e21570475314adc9495e7e1107f4dc2dbaae488135**
(build32 source7077239634a78033496e46a2b9cc6bbd044792c2ee020a8336cc06c0ee4ae4b7).
Build/lint/strict offline Maven SHA verification PASS.225 build/test components,
all binary POM licenses declared and all cache hashes matched; no runtime
Google/Play dependency (only Kotlin stdlib/annotations). Hashes bootstrapped from
cache; not independently authenticated provenance.

Actual UI recreation test found first-draft loss; fixed RAM retained composer
(no text Bundle), and service draft restoration on fresh Activity. Expanded test
then exposed first double-tap creating two dialogs before async admission. Fixed
`submitting` covers creation+acceptance; late ack cannot erase following text or
select another dialog. Failure reports050614/050857/051057 preserved. Product
PASS051245 and **051603**, including exact dialog count, Unicode first send,
retained draft, fresh Activity service-RAM draft without replay, API/TLS/thermal/
busy/cancel/disconnect. Full30-cycle16KiB framework + legacy/product screen-off
notification grant/deny PASS **`emulator-16k-20261026T051608Z/report.json`**.

Actual system picker independent import + changed-original deletion refusal +
explicit UI deletion of restored original + canonical-copy SHA/no private duplicate
PASS **`saf-copy-delete-emulator-20261026T051733Z/report.json`**. Own public fixture only.
No-copy and real reboot persisted-grant proof PASS045541Z (see older checkpoint).
Latest visual/font130 keyboard audit running `/tmp/motoai-ui-final.log`.

Linux pilot clean pair A/B **byte-identical** SHAa837275b57e23590c5949d71f81f14373bbff4181d314713dc79f11a0a81e53f,
`linux-product-pilot-pair-20261026/report.json`; before subsequent UI fixes, so not
final. Both lintPASS, networknone. Actual pilot APK ELF16KiB/ZIP/notices/manifest
auditPASS `release-audit-linux-a-20261026/report.json`. Official fdroidserver2.4.5
with current SUSS: source zero fatal, one unused upstream Keynote executable-bit
warning; binary zero signatures. Source scanner auto-removes Gradle wrappers as
usual. Draft recipe uses pinned srclib, no blanket scanignore; removes exactly
that non-build Keynote file from prepared copies. Not an actual F-Droid build
server/inclusion decision. Frozen tool requirements in bench/.

**Final clean Linux C active terminal54459**, `/tmp/motoai-linux-product-c.log`;
source`build/linux-product-c-20261026`, identical pristine D copied before C build
at`build/linux-product-d-20261026`. Both include final production code and strict
verification metadata; later changes are test/controller/docs only. Do not mutate
C/D while building. Run D after C, compare unsigned APKs, final audit + binary
scanner, locally signed installable release and source/provenance bundle. No
production signing key or publishing. Regress final phone product/background and
main-model loading after M5 lease frees. Aider actual accepted evidence remains
034552Z on earlier same-kernel product build, not repeated after UI-only fixes.

## Current checkpoint — 04:58 UTC

**Continue all M0–M8; do not stop at this checkpoint.** No cutoff, no commits/push/publication.
Physical phone lease: Ornith32K controller terminal7652, `/tmp/motoai-ornith32k.log`;
artifact `app-service-ornith-20261026T040317Z-7c759032`, last observed20480/32256.
Frozen731a854c APK/tests4e4c, timeout7200s. No other phone load/install. Gemma32K
is next after lease release, same-APK passed prefix gate025946Z.

Current product APK **e8bb0542743bf14fb8c24234d02e2fda3b8c9ac6d30ac7201a580075325e126e**,
tests**a6198d79fb633ce1a77b66471bbf7d4e92125409d5a382a1d37b6c8dd604a1ab**,
post-build source snapshot**fe51fe8a974c960246c535420df456bb22a3def28e4afff2b626602f99d225a7**.
Default long-attention-v2/native mmap/no-prefault CPU4/KleidiAI OFF. Build24/lintPASS.
Native host lifecycle/cancel/overflow/reload PASS;73Python testsPASS.
Source-file grant fix: Android FUSE may deny fopen(`/proc/self/fd/N`) although
its granted FD is readable/mappable. `model-file.h` duplicates that actual FD
and uses upstream FILE-pointer metadata/load API, with scoped FILE lifetime.
Weights/kernel code unchanged. Initial reference failures preserved044153/044759/044907.
Direct no-copy/mmap/source-preserving disconnect PASS045009Z.
Real DocumentsUI no-copy + **actual emulator reboot with differing boot_id**,
persistable read grant and identical mmap output PASS:
`bench/results/saf-reboot-20261026T045541Z/report.json`. Initial immediate reboot/
package-replacement attempt045339 failed; correct controller lets Android async
grant persistence settle15s and reuses verified identical installed packages.

Full16KiB framework/background regression now running in `/tmp/motoai-emulator-final24.log`.
Current product contract/download and later phone regressions still needed on this APK.
Linux clean releaseA runs terminal57862, `/tmp/motoai-linux-product-a.log`, source
`build/linux-product-a-20261026`; pristine identical sourceB copied before build
at `build/linux-product-b-20261026`. Imageaca8e327..., networknone,4CPUs/6GiB.
Do not modify sourceA/B while building. RunB afterA, then compare unsigned APKs.
Gradle SHA verification metadata generated from offline resolved artifacts;
`licenses/build-components.json`:207 build/test components, every binary POM
license present and cached artifact SHA matched. This is cached declaration/hash
inventory, not independent provenance or F-Droid catalog approval.
README/app-description/model-storage now reflect implemented UI/API/HF and actual16K
rather than stale harness-only status. More release/docs/source-bundle work remains.

## Active work — 2026-10-26 (owner now requests all M0–M8, including product)

The owner explicitly requested continuing through the full plan, including UI/UX,
ephemeral dialogs, OpenAI-compatible API, and background answers with completion
notifications. No new time cutoff. Preserve weights/quantization and quality.
M3/M4 are still in progress; no product or new speed profile is accepted.

**Latest live state (03:00 UTC 26 October):** Ornith 16K finished PASS:
`bench/results/context-ornith-16384-20261026T021721Z/report.json`.
Actual 15,872 input + 106 generated tokens, EOS and exact ordered three-depth
identifiers, finite logits, original GGUF/F16 KV. Prefill 2,117.77 s; decode
66.67 s, completed PSS 5,691,527 KiB, swap 107,544 KiB. This is a slow
experimental context point, not an interactive-speed or general-quality claim.
Gemma joint prefix/transient same-APK gate is running under the phone lease,
terminal 23288, log `/tmp/motoai-gemma-prefix-finalist.log`; frozen 731a854c APK
and tests4e4c. Do not install or run another phone workload concurrently.
User product work starts alongside serialized long-context validation. No
untested backend/default promotion, commits, or publication.

**Product implementation (03:25 UTC):** M6/M7 work is now code, no longer only
an M1 UI. Launcher `ChatActivity`, service-owned RAM dialogs/drafts, multi-turn
reasoning/content parsing, large framed SHA-checked PFD transport, model profiles,
fixed context points, themes, explicit import-source deletion offer after reread,
foreground HF LFS/immutable-revision download, shared engine HTTP/SSE API and
Wi-Fi TLS/Bearer controls. Source is still under active testing, not M6/M7/M8
acceptance. No publication or commits. Current product APK d86f2644… / tests in
its frozen archive. Product contract PASS on 16KiB ARM64 emulator:
`product-emulator-20261026T032404Z/report.json` (multi-turn/dedup, >190KiB Unicode
draft, HTTP/SSE parity, usage, stop, overflow-before-generation, recovery, real
P256 AndroidKeyStore TLS trust/IP verification). Failed candidates preserved.
Full product background/notification/real-phone/HF/Aider acceptance still pending.
RSA/Conscrypt raw-padding candidates were rejected; no TLS bypass was used.

Host structured parser on/off has identical prompt IDs/generated IDs/output and
first logits for both actual 3584-token controls, exact three-depth answer/EOS:
`product-parser-host-auto-format-20261026/report.json`. NONE reasoning format
failure retained separately; AUTO fixes channel parsing without altering input
IDs. Per-token monotonic replay check is being added. Gemma16K finished PASS (`context-gemma-16384-20261026T030548Z/report.json`):
15,872 input +27 generated/EOS, exact three-depth retrieval; prefill1628.75s,
decode14.00s, PSS4,036,925KiB, swap18,792KiB. Experimental slow point, not a
speed/default/general-context acceptance. Product real-phone test on04434cda
caught an OEM early WindowInsetsController/DecorView NPE; fixed by deferring
access until after content attachment. Retained failure033532 and crash log.
Product real-phone contract PASS on17b313ab (`product-phone-20261026T033831Z`),
including real AndroidKeyStore TLS trust and API. No active long phone job now;
next controlled task is Aider over actual LAN TLS. All phone jobs remain leased. Aider0.86.2 installed in `.tools/aider-env`,
requirements frozen; local coding/TLS test still pending, no cloud model calls.

**04:00 UTC: actual Aider PASS**, `aider-phone-20261026T034552Z/report.json`,
frozen product APK17b313ab…; original Ornith GGUF full SHA rechecked. Aider0.86.2,
same coder two turns, whole-file edits, no Git/commits, no auto-tests or cloud
fallback, local main/weak/editor roles. Both edits applied with zero reflections;
networkless/read-only/pid+RAM-bounded Docker regression checks PASS. Turn wall
110.33s /616.87s (second had~1.5K reasoning+answer tokens). This is real but slow,
not an interactive-speed claim or all-client/model acceptance. Actual direct
Wi-Fi IP/certificate checked with normal TLS trust, then macOS sandbox client
used only a localhost opaque TCP relay fixed to that phone; TLS still end-to-end.
Initial SBPL-host and weak-model=None setup failures preserved in earlier runs.
No TLS verification bypass, external model call or publication.

HF Android emulator PASS: `downloads-emulator-20261026T034408Z/report.json` on
APKab043dfb…: immutable public test-only1.18MB GGUF, real anonymous HTTPS/LFS,
explicit cancellation retains seeded256KiB, ordinary foreground transfer completes,
SHA re-read and canonical Downloads registry, no full private duplicate. Optional
HF token Keystore AES-GCM roundtrip/plaintext absence/forget verified. Valid
private/gated account credentials were not supplied; no such access claimed.
New imports/HF now stage directly in canonical MediaStore, not private+Downloads.

Latest lint-enabled offline build PASS: product APK06390ac4… / sourceae27a3d…
(archive post-build capture, not compiler attestation). Product15 phone test owns
lease in its controller (latest tool session), see `/tmp/motoai-product15-phone.log` and
current controller state. Independent product15 emulator test also running.
Source after0639 adds incremental per-message rendering and cached dialog byte
accounting; not yet compiled/tested. Do not represent it as the tested APK.
Remaining:32K actual filled points, current-product regression/UI/lifecycle/privacy,
final Linux clean pair and release preparation. No commits or publication.

**04:21 UTC live:** Ornith32K trial owns the phone lease, terminal7652,
`/tmp/motoai-ornith32k.log`. Frozen731a APK/tests4e4c, context32768,
actual32256 input+512 reserve, transientON/cacheOFF/native mmap/no-prefault,
watchdog7200s. Progress in `app-service-ornith-20261026T040317Z-7c759032`.
Do not install/run another handset task while active. First3K had~430MiB VmSwap;
no completed result/capacity claim. Gemma32K still pending.

Phone and16KiB emulator expanded product contract PASS on06390ac4:
`product-phone-20261026T040056Z`, `product-emulator-20261026T040100Z`.
Includes strict JSON, count/overflow, thermal injection cancellation,429 busy,
streaming reset/disconnect cancellation and recovery. RealAider earlierPASS kept.
New incremental render +cached byte accounting +bounded GGUF metadata/payload
validation passed full emulator30cycles/storage/background on9788595e:
`emulator-16k-20261026T041124Z`. Latest compiled/lint-pass product APK a0b8da2b,
sourcee82f2739; actual composer-first-send/double-tap test now running on emulator,
log `/tmp/motoai-product17-emulator.log`. Source after that archive additionally
adds SHA verification beforeload and safer certificate export; not yet compiled.

**Closed M3 bounded comparison:** CPU4-auto selected; all1..7 scheduling/affinity
screens and4/7/7/4 sustained already exist. Tiny affinity/no sustained7-thread gain
not promoted. Tested Vulkan crashes inOEM shadercompiler; both OpenCL8 profiles
failunchangednumericgates. No furtherGPUoffload after failure. Alt/DSP free/exact
import feasibility filter retained. `bench/m3-comparison-acceptance-20261026.json`.
M4/M5 still open; UI/API still M1 harness and must follow profile acceptance.

**M4 accepted bounded evidence:**0016/18 exact physical KV snapshot negative
controls bothhostmodels; progressive boundedcache nineRuntime trials/model;
phoneGemma+Ornith exactcache/cancel/session/unload; coupledtransient+cache passes
bothmodels (`prefix-service-20261026T014912Z` Ornith,
`...T020606Z` Gemma). Gemma fullprefill88.76s vs63.30s candidate and1.488s cachehit
in thisfixture; no generalized timing/defaultclaim. Longattention0019/20 CPU
F64 corrections passed12shapes/36hostruns plus12shortbaseline-bit controls;
phone12shapes/20runs +12shortcontrols, worstNMSE1.23e-11 vs1e-10 bound,
`attention-long-phone-20261026T015739884793Z/report.json`. Original failures retained.

**CompactSWA rejected beforephone:** upstreamswa_full=false onGemma3083 input
failedmeanNLL0.0852/max0.6597/top8; report
`bench/results/compact-swa-host-rejected-20261026/report.json`. Native+admission
now reject false; true remainsreference. ExperimentalAPK42f731b8… savedONLYfor
reproduction, never installed. Do not enable it orwiden gates. Likewise Gemma
tiedembedding source-order idea has no demonstratedmmap saving: actualmapped
model4961343656B already sharespayload; no unneededpatch added.

**M5 groundwork:** own-UID CPU plans4/8/16/32K bothmodels PASS; logicalKV+state
Ornith186.9/321.1/589.6/1126.4MB, Gemma234.9/469.8/939.5/1879.0MB; notRSScapacity.
`gpu-plan-cpu-20261026T020516Z/report.json`, `bench/m5-context-matrix-20261026.json`.
Generation memorywatch cancels withoutshrinkinginput; fault-injected abort and
exactnextanswer recovery passedphone731a (`runtime-tests-20261026T021612Z`) and
full16KiB emulator (`emulator-16k-20261026T021547Z`). Earlierprecondition-only
frameworkfailure021544 (APKnotinstalled) retained, then installedunderlease.
Host4Kcontrol ofsamethree-depthretrieval completedcorrectly onbothmodels:
`context-short-host-control-20261026T022453285428Z/report.json` (hostonly).

**Independent source/buildwork:** Linux metadata-free CPU release withlint and
networkNONE PASS onlong-attention-v2; `linux-m5-release-20261026/report.json`.
Source exportpredatespackagednotices andfullUI/API; notfinalM8/reproacceptance.
RuntimeMaven graph onlyKotlinstdlib2.3.0+annotations13.0; source/JAR/POM hashes
andfullnotices in`licenses/runtime-components.json`, assets/licenses. Builddeps/
finalbinarySBOM/reproducibility/signing/publicnamespace stillpending. Dependency
proxyremainsCLOSED. No commits/publish/automation; no currentcutoff.

**New completed results:**
- Phone prefix-service seven trials PASS, report
  `bench/results/prefix-service-20261026T004834Z/report.json`: actual1024 reuse,
  cancellation/recovery/namespace/unload, exact NLL/tokens. Disabled by default;
  snapshot86,270,772B, PSS residency variation is not an isolated cache RSS bound.
- GPU no_alloc plan4/8/16/24/128 layers PASS + CPU recovery, report
  `bench/results/gpu-plan-opencl-20261026T010527Z/report.json`. Conservative own-worker
  admission includes GPU weights/upload duplication, CPU live window, context,
  compute, driver reserve and unchanged system headroom. Planning has no weight
  data allocation; small context/recurrent state may allocate.
- Actual Ornith OpenCL8 all-op profile FAIL, report
  `bench/results/gpu-model-screen-20261026T012157Z/report.json`: meanabsNLL0.03416
  >0.02, worst0.13738>0.1, top8 shared-logit0.17768>0.1. Same original GGUF,
  same-APK CPU reference. Failed fast before16/24. CPU recovery PASS, boot unchanged.
  No GPU speed/profile promotion. Matmul-only is a separate bounded candidate.
- Background notification acceptance reopened after later emulator failures to
  locate/tap own completion notice in SystemUI. Earlier passes retained, no final
  reliability claim. Test now captures public-fixture emulator pixels/accessibility
  at failure; never phone screenshots. UTF8 final-output decoder now flushes.
- Optional state-layout-v2 adds separate snapshot magic and rejects incompatible
  PARTIAL/ON_DEVICE combinations before interpreting data. Tree
  `ee0e92c86bc8ad5090df73e3a1163610bdbcde2b`; new host negative controls pending.
  Not integrated into Android/Gemma runtime cache.

**Completed sustained controller40054:** all4 arms4/7/7/4 passed quality and current
stricter post-validation; durations918.8/950.8/910.2/935.5s. Full emission
3.0801/3.1710/3.1398/3.0312tok/s; final5min2.8737/2.8910/2.9162/2.8655.
7-worker paired tail gains0.60%/1.77% below declared3%; start caps differ
(policy3 first2188800, others1920000). `matched_start_conditions=false`, no
speed/default promotion. CPU4 retained. Source report:
`bench/results/sustained-decode-20261025T225449Z/report.json`,4 post-validation
files; updated`bench/m3-decode-screening-20261025.json`. No pruning/retries.

**Actual GPU app-UID results on f1f research APK:**

- Vulkan`bench/results/gpu-numeric-vulkan-20261026T004249Z/report.json`: physical
  Adreno IGPU initialized; MUL_MAT graph crashes in OEM libllvm-qgl.so while
  vkCreateComputePipelines. Symbols in`/tmp/motoai-vulkan-crash-symbols.txt` point
  to ggml_vk_mul_mat_vec_q_f16 / lazy shader creation. CPU recovery full suite
  PASSED, phone boot unchanged. No GPU numerical or speed acceptance. Investigate
  an explicit fp32 shader profile / driver workaround without relaxing gates.
- OpenCL`bench/results/gpu-numeric-opencl-20261026T004432Z/report.json`:45/45
  F32/F16/Q4_K/Q5_K/Q6_K numeric+exact weight-byte roundtrips PASS; all required
  Ornith dtypes pass.9 BF16 cases correctly forbidden (backend downcasts BF16→F16),
  so overall report is NOT QUALIFIED for all models. Gemma OpenCL remains blocked.
  CPU recovery full suite PASSED. No large model/end-to-end/speed acceptance yet.
- **Before large GPU loads, add/use a GPU memory plan/admission. Current service
  admission is CPU-oriented and must not be used as a GPU RSS budget.** Pinned
  no_alloc model/context support can plan selected buffers without reading weights.
  Start bounded partial offload; no full9B GPU trial without conservative budget.
- Earlier GPU controller attempts retained:003642 failed before compute because
  PackageManager returns owner/work-profile UID CSV; fixed query to owner0.
  003843 loaded Vulkan but filtered IGPU as non-GPU; fixed both native selection
  and numeric JNI to allow GPU/IGPU. CPU suite then hit a test-only empty imported
  prompt. Fixed test to explicitly set publicHello, not app default. Neither old
  attempt is evidence that Vulkan arithmetic is unsupported. GPU runner now
  verifies/installs candidate before CPU recovery and freezes explicit APK paths.

**M4 cache:** default0/OFF. `docs/prefix-cache.md` and
`bench/results/prefix-cache-host-runtime-20261026/report.json`:9 host Runtime
trials exact NLL/top8/IDs/output, actual1024-token reuse, namespaces/prefix misses,
stale-ticket recovery/unload. Snapshot86270772 bytes. Current phone test adds
cancellation after real restore via ordinary service. No speed profile yet.
Only Ornith CPU/native ctx<=8192,b512/ub128,<=4threads,no transient is allowed.
App separately reserves selected64..256MiB. Ordinary requests need an opaque
adapter-owned cache_session; no namespace means no snapshot/reuse. Metadata
separates fullprompt/processed/reused tokens; no inflated matmul throughput.

**New optional hash-locked0016, HOST ONLY:**
`f32-recurrent-state-layout`, `.tools/llama-state-layout`, full tree
`30baead022a741b22df6956b718a7359026887e0`, patchSHA in series.json. Flag4
KEEP_KV_LAYOUT saves physical slots/head and SWA-masked occupied cells for one
full seq0, disallows partial/on-device/mirrored combinations. Flag0 unchanged.
Host Gemma1024 and3072 checkpoints now preserve every raw logit and27 NLLs exactly,
byte roundtrip exact; old flag0 still reproduces raw1.96508/NLL0.332225 failure
on the SAME build/backend. Evidence`bench/results/state-layout-host-20261026/`.
Host build`build/state-layout-host`, NativeOFF/BLASAppleON/MetalOmpKleidiOFF;
lifecycle passes. First NativeON attempt stopped before probe on a test feature
string that ignored spaces; test now normalizes whitespace. This variant is NOT
mapped into Gradle or phone cache yet; Gemma remains rejected in production cache.
Next: malformed/flag/geometry controls, Ornith regression, then integrate/ext API
and Android correctness before enabling any Gemma cache profile.

**APK/source tools:** `archive-build.py --tests` now keeps immutable tests-<SHA>.apk
next to archived app. `check-apk-native.py` ties all16 packaged .so to stripped
local symbols, ELF16KiB and ZIP alignment, excludes OEM loaders; f1f passed.
85bb APK before IGPU fix passed independent16KiB emulator full CPU/RAM/count/
overflow/background/permission/deep-link suite:
`bench/results/emulator-16k-20261026T002622Z/report.json`. Exact PREPARE IPC is on
native owning worker, no KV/recurrent mutation; overflow carries numeric details.
Current phoneCPU recovery on f1f also passed actual imported-model UI branch.

**Linux build work finished, relay CLOSED:** first Linux metadata-free source
CPU debug build PASS:`bench/results/linux-build-20261026-v3/report.json`.
Unsigned CPU release with lint + network-disabled offline incremental repeat PASS:
`bench/results/linux-release-build-20261026-v4/report.json`, APK686e2241... .
Frozen v4 source predates newest context/IGPU changes. This is toolchain evidence,
not clean-pair reproducibility or final F-Droid/product acceptance. Initial offline
release missing lint dependency retained; fetched normally then repeated offline.
Explicit Build Tools36.1.0 now pinned in both modules/toolchain lock.
Docker desktop-linux image/volumes retained, no active builder. Temporary proxy
terminal60837 was stopped by Ctrl-C after dependencies; do not describe as active.
16KiB emulator remains running in terminal24324, use ONLYserial emulator-5580.
No publication or commits.73 Python tests passed before newest diagnostic scripts.

Below are earlier chronological research notes; the live state above takes precedence.

**New research APK:** `9ff86e87cb37f649436dbe47633cf3de01a1021bb295ce7f7215f3f030d89c56`
currently at `build/gpu-app/outputs/apk/debug/app-debug.apk`, archived with source
`05296fa0a76018f4fdd995dc68114f3fb1624ca2583ac35367186d076d7911cf`.
Includes renewing wake lease, actual JNI page size/registry metadata, RAM IPC,
exact count/overflow metadata, explicit GPU controls and new background-completion
notification helper. Completion notices have generic content and answer-ID routing;
old/replaced answer IDs never display a different answer or replay a request.
MainActivity is still the M1 harness; full multi-chat/UI/HF/API remain to implement.
Completion notification background/permission routing is not yet fully tested.

**16KiB emulator now running independently:** terminal24324,
`/tmp/motoai-emulator-16k.log`, AVD MotoAI_API36_16K, serial emulator-5580,
`ANDROID_AVD_HOME=$PWD/.tools/avd`. All emulator commands MUST use `adb -s
emulator-5580`, never `-d`; the phone lease is still held by40054. Full
framework/JNI/RAM/Stop-notification CPU suite passed on9ff:
`bench/results/emulator-16k-20261025T234809Z/report.json`. Actual page size inside
JNI16384, registry exactly[CPU], no GPU driver initialization.30 standard and30
pooled cycles, cancellation/recovery and exact RAM-vs-file response transport.
Tiny public1.1MiB fixture only, not heavy-model/handset/GPU performance acceptance.
Two prior failed emulator reports preserved: first lacked the fixture, second
reached service tests with notifications denied (generation/RAM worked but the
existing Stop-notification test needed an explicit permission precondition).
Runner now stages/verifies only the tiny fixture, grants/restores the emulator's
notification permission and deletes stale reports before each run.

**Linux/build compatibility work (parallel host only):**

- Docker Desktop started; local context `desktop-linux` works with8GiB VM. Default
  `/var/run/docker.sock` context is absent; always explicitly use desktop-linux.
  Direct VM egress to DockerHub and Debian timed out. Host HTTPS fetched the exact
  Debian amd64 OCI manifest/layers, all compressed/uncompressed/config hashes
  verified before `docker load`; provenance `.tools/linux-base/provenance.json`.
- Temporary public-dependency proxy is ACTIVE, terminal60837(tty),
  `python3 scripts/linux/build-proxy.py`, binds127.0.0.1:18789 only, strict public
  build-domain allowlist, no path/header/credential logging. Docker uses
  `http://host.docker.internal:18789`. Stop with Ctrl-C on60837 when build work
  ends; never leave an unrequested background proxy. No global network settings
  changed. Not an automation.
- Linux amd64 image `motoai-linux-builder:20261026` successfully built via that
  relay. Log `/tmp/motoai-linux-builder-proxied-v2.log`. Image config982fdd04...
  includes JDK17, git, Python3, Ninja, curl/unzip; dpkg versions in
  `/builder-packages.txt`. Initial failed/cached-empty apt attempts preserved;
  Dockerfile now uses `APT::Update::Error-Mode=any`. This builder alone is not
  clean-APK/reproducibility/F-Droid acceptance.
- Linux tool downloads ACTIVE, terminal31976, log `/tmp/motoai-linux-tools.log`:
  `python3 scripts/linux/fetch-tools.py`. Pinned repository XML selects
  NDK28c(722MB), build-tools36.1.0(64MB), CMake3.31.6(31MB), validates original
  Google SHA1/size and records SHA256, unpacks under `.tools/linux-tools`.
  Copies platform36's OS-neutral jar/licenses. It must finish before Linux build.
- Metadata-free upstream source exports now supported, checking exact raw Git
  tree + locked full patched tree. `upstream.lock.json.tree` and patched_tree
  fields for the3 current variants. Git clone and exported patched trees match
  `990a04b18b67779b631bde36c6597c9bad877481` for literal-chat. Evidence
  `bench/portable-source-preparation-20261026.json`;73 Python tests pass.
- Fresh validated export `build/linux-source-20261026-v2`, generated via
  `scripts/export-build-source.py`. It predates the newest notification/page
  metadata changes, so use a new export before accepting current-source builds.
  Original `build/linux-source-20261026` was INVALIDATED: ancestor Git discovery
  silently skipped patch0008. Full-tree comparison caught this before any Linux
  compile; corrected script sets GIT_CEILING_DIRECTORIES and compares to the
  locked expected patched-tree digest, not merely its own self-recorded digest.
  Old export remains preserved; never build/relabel it as accepted.

**Active phone controller:** `python3 scripts/run-sustained-decode.py`, terminal
session40054, log `/tmp/motoai-sustained-decode.log`, report
`bench/results/sustained-decode-20261025T225449Z/report.json`. It owns the phone
lease and uses the unchanged installed/local24be APK. Do not install another APK
or launch another phone workload until it exits. Order4/7/7/4,900s each, one load
and one excluded warmup per arm, natural cooling<=37C (max1800s), final5min
separately, no forced clocks/pruning/retries. Arm0 completed19 requests/918.8s:
3.0801 emission tok/s, last5min2.8737; quality passed, samePID, sampledPSS
5488201→5361242KiB. Cooling before arm1 is in progress. This is one fixture/arm,
not a speed comparison or product endurance acceptance. Frozen runner sources
are copied in its result folder; stricter post-analysis checks were added to the
working `sustained_checks.py` after launch (indices/order/power) and need to run
against the raw JSONL afterwards; do not pretend they were the launched code.

**Host work completed while phone is busy:**

- Source-only Vulkan+OpenCL standalone tools build passed:
  `bench/results/gpu-build-20261025T230236Z/report.json`.
  Pinned Khronos dependencies in `bench/gpu-dependencies.lock.json`, reproducible
  `scripts/build-gpu-candidate.py`; no Qualcomm binary kernels/OEM loaders bundled.
- New hash-locked0014 exports selective CPU/GPU loading. Runtime explicitly selects
  CPU or one GPU registry/layer count, rejects unavailable/mixed policies before
  touching the live model, verifies actual selected GPU weight buffer. CPU devices
  explicitly exclude every registered GPU. OpenCL BF16 models reject because the
  pinned backend converts BF16→F16. Source filter for MNN/LiteRT/Hexagon is in
  `bench/m3-backend-feasibility-20261026.json`; no alternate models exported.
- New0015 protects input-originating byte ranges during special-token partitioning,
  keeping ordinary text/BPE intact across Jinja part boundaries. Host lifecycle
  passes. Independent properties plus old frozen prompt IDs passed for both
  models: `bench/results/literal-tokenization-20261025T231322Z/report.json`
  (976+652 properties,37+31 fixtures, host model SHA verified). This does not yet
  attest to phone inference after the patch. Variants immutable, pristine B0 clean.
- Isolated GPU/CPU research APK2563a7bd1449321a617b20dbc8e6f4dfcb973352823f096ce5fdbb2bbb7a5bac
  built and archived, NOT installed. `build/gpu-app/outputs/apk/debug/app-debug.apk`;
  source snapshotd64441d2a2847169234b0e977fc9ee78ef6905e4ce4a4076e2ef8eb568828e81.
  Static16KiB ELF and16 library/symbol identities pass in
  `bench/m3-gpu-research-apk-identity-20261026.json`; actual16KiB execution untested.
  Do not copy it over app/build output while the old controller is active.
- APK2563 includes GPU numeric instrumentation (`scope=gpu-numeric`, backend,
  gatesFile, runId gpu-UUID): independent F64 original-F32 activation accuracy with
  unchanged M2 gates plus byte-exact weight readback. BF16 OpenCL is explicitly
  forbidden, never passed. GPU computation remains untested on phone.
- Ordinary service outputs now use bounded paged RAM and32KiB Binder chunks;
  debug benchmark reports retain explicit file output. MainActivity drafts stay
  in main-process RAM instead of saved instance Bundle. Exact native prompt-count
  API and overflow numbers added. These integration changes need phone tests.
- After the2563 archive, added renewing timed `InferenceWakeLease` for >30min
  operations and `EphemeralChecks` transport/wake lifecycle tests. Working sources
  are newer than2563 and need an isolated rebuild before those tests are claimed.
- CPU numeric case extraction preserves the complete old-source result on this
  host (direct old/new executable comparison). Both preserve one pre-existing
  hostF16 seed17 failure(NMSE1.25284e-6 vs1e-6) and unsupported extra paths; NOT a
  numeric PASS. CPU lifecycle passes;72 Python tests pass. No tolerances widened.

Next: let the predeclared sustained controller finish (no concurrency on phone),
post-validate its raw arms with stricter checks, then install/test the isolated
research APK under a lease: framework/RAM/cancel recovery, GPU actual app-UID
numeric tests, explicit CPU recovery, model/corpus/task gates for any GPU finalist.
Continue M3–M8 after justified runtime decisions; do not stop at this handoff or
mark milestones accepted prematurely. New foreground completion notification,
full UI/chat/model downloads and API are still to implement/test.

## Current checkpoint: CPU scheduling and stable attention quality completed

Installed APK `24be50e13a9b9f05d4538b7c5cf4e34b1cac187279df87a534c0c9f0ba677f76`,
variant `f32-recurrent-stable-attention-v2`, CPU/KleidiAI OFF. Original GGUF,
quantization and F16 KV unchanged. Default remains native4 workers, batch follows
4, Android scheduler; no speed profile/default/M3 promotion. UI/API remains later.

Implemented optional separate `threads_batch` and exact group `cpu_affinity`.
Caller placement is restored on return/exception; created CPU pool workers are
verified and freed after their borrowing context. Runtime rejects workers above
the current allowed CPU count before replacing a model and rechecks each request.
Eight workers on7 allowed CPUs previously suffered severe slowdown; failed raw
run/manual stop remain preserved, not a numeric or performance pass.

Changing4→7 workers exposed F32 split-KV grouping-dependent rounding (up to5.96e-8
on identical host/phone tensors). Ornith code branched after179 common tokens,
1096→1215 outputs; both functions pass134 cases. The strict old trajectory check
remains failed. Patch0013 now fixes the validated4 F32 KV chunks/reduction order,
distributing independent(head,chunk) jobs across workers; the planner budgets at
least4 partial chunks for1 worker. Other precisions keep original grouping.
Load metadata `attention_f32_kv_chunks=4` identifies this compiled policy.

**Current-APK quality passed:**

- Before install,128 replay runs/16 shapes: old phone4 bits preserved; decode1/4/7 everywhere and all1–7 on512-key/128-head cases; original independent F64 gates unchanged. Includes8192 KV,257 valid/512 padded, odd513 and unequal DK/DV. Prefill4 preserved; arbitrary prefill-thread invariance is not claimed.
- New4 full code matches accepted old4; new7 matches fresh same-APK4: all1096 generated IDs, EOG finish, first-top8 error0,134 functional cases. This confirms the observed attention trajectory issue is fixed on this workload.
- Code/Russian NLL checks before speed: all errors0; all128-token timing trajectories match.
- Framework30 default cycles plus30 explicit7/4 cycles; tiny outputs/exception/cancellation recovery exact, caller masks restored. After pooled unload:14 tasks/118 FD every recorded sample, no accumulation.
- 13 APK libraries match symbols/strip-normalized bytes and static16KiB LOAD alignment; actual16KiB-device execution untested. CPU host lifecycle and66 Python tests pass. Pristine B0 remains untouched.

**Current-APK speed not accepted:** after the two full code tasks, an excluded
warmup plus attempted five-pair128-output series stopped on measured pair3.
First two comparable emission ratios1.0420 and1.0535 (~2.99/3.03 vs2.87 tok/s).
Third ratio1.1377 is confounded: candidate policy3 ceiling1286400→1171200;
following baseline used1171200. Original strict gate remains unchanged, no pair
pruning/combination/retry or confidence interval. Earlier~14% exploratory results
belong to other conditions/APKs and are not promoted. Default4 is retained.

Canonical summary: `bench/m3-decode-screening-20261025.json`.
Ledger: `bench/m3-decode-investigation-20261025.json`.
Controller (quality stages PASS, speed stage FAIL):
`bench/results/stable-decode-validation-20261025T221517Z/report.json`.
Operator gate: `bench/results/attention-thread-check-20261025T221114Z/report.json`.
Coding controls: `bench/results/decode-task-20261025T221547Z/report.json` and
`bench/results/decode-task-20261025T222200Z/report.json`.
Framework: `bench/results/runtime-tests-20261025T221519Z/report.json`.
Speed: `bench/results/decode-screen-20261025T222930Z/report.json`.
APK identity: `bench/m3-stable-attention-v2-apk-identity-20261025.json`.
Source snapshot `b5f36935ca7fab788ee309c56707ff861eeab9839f3086825fe07a064287c67f`.

No phone workload left. Final health:81%, AC connected/not-charging status4,43°C,
same boot, no inference PID: `bench/m3-decode-final-health-20261025.json`.
Only our small standalone diagnostic temporary folder was removed. No weights,
governor/clock/power settings, commits, pushes, publication or automations changed.

**Next M3:** predeclare matched-start sustained4/7 A/B and B/A comparisons
(>=15min, final5min separately, natural OEM adaptation, no forced clocks), then
combined bounded prefill/decode profile, deeper contexts/battery/backends. Do not
retry short series until a lucky cap pattern. No need to repeat completed long
mmap/no-prefault controls. UI/API and large-context promotion remain later.

Rejected0012 host prototype is preserved and was never installed: wrong partial
pointer double-counted the head stride; independent F64 gate caught it. Corrected
0013 uses a chunk-base pointer because one_chunk adds head stride internally.
The initial host lifecycle configured Metal unintentionally; headless Metal
allocation failed. CPU-only configuration passed, without arithmetic gate changes.
The named-APK historical experiments below remain evidence, not current activity.

## Earlier scheduling investigation (superseded APK3a)

Current guarded APK `3a853c98225fca30f966c6b8aabd3cb54edecdd1305399f2539dd7deb25a8f09`
adds optional separate `threads_batch` and exact `cpu_affinity` group placement.
Defaults remain implicit/native4 threads. All12 non-JNI shared libraries,
including numerical kernels/llama, are byte-identical to the preceding accepted
bounded prefill APK; no weight/quantization/F16-KV change. Identity record:
`bench/m3-decode-kernel-identity-20261025.json`.

Caller mask is applied before each request and restored on return/exception.
Requests outside the current caller allowed set fail before replacing a loaded
model; no silent affinity intersection or oversubscription of an explicit group.
Actual caller/new CPU-pool worker masks are verified after generation. Priority
and poll stay upstream defaults. Separate pools are owned beyond their context.

Host lifecycle passed. Phone framework30 cycles and8 affinity request checks
(shared and distinct pools, repeated requests, unload, unavailable CPU rejection)
passed: `bench/results/runtime-tests-20261025T210014Z/report.json`.
Python66 tests pass. On start81%, AC/adaptive not-charging,33°C, same boot.
Prospective profiles: `bench/decode-profiles-20261025.json`; frozen64/128-output
requests include short and512-token input. Runner `scripts/run-decode-screen.py`
first scores code/Russian NLL per configuration, then counterbalanced warmup and
short screening pairs. One-pair screening is not performance acceptance; strict
finalists require5 measured pairs and unchanged relevant frequency boundaries.

A first preliminary series on APK7f840d4a… was stopped during baseline scoring
after source review found that upstream running pools do not re-apply the main
thread mask after API restoration. Its raw artifacts are retained in
`bench/results/decode-screen-20261025T205852Z/`; no scheduling/speed acceptance.
The current APK explicitly fixes this and tests shared-pool repeated requests.

The unguarded5928 matrix completed44 requests:16 NLL checks with exact0 error,
14 paired64-token trajectories all identical. Comparable screening ratios vs
implicit4 workers:1 worker0.294;2 workers0.502;6 workers1.064;4 workers on3–6
1.007;2 on3–6=0.536;3 on3–6=0.764. Auto3 measured pair was frequency-confounded.
One-pair screens remain descriptive, never accepted speed profiles.

Range refinement on5928: reusable4 pool0.997,5 workers1.034,7 workers1.141,
all comparable measured pairs and exact NLL/tokens. Eight workers on actual7
allowed CPUs suffered severe progress slowdown (10/26 scored tokens after186s,
CPU1252s, battery42C). Manually force-stopped to reject it; original raw failure
and exit records retained. The overall refinement report stays failed, not
rewritten into a pass: `bench/results/decode-screen-20261025T212510Z/report.json`.
Pinned polling/barrier oversubscription is a hypothesis, not an isolated cause.

The guarded APK rejects excess decode/batch worker counts before replacing the
model and rechecks allowed CPUs before each request. Framework30 cycles plus11
scheduling/recovery checks pass, including overflow/cancellation mask restoration
and loaded-model preservation after unavailable/oversubscribed load:
`bench/results/runtime-tests-20261025T214037Z/report.json`.
Fresh quality and strict warmup+five128-output pairs are running on the guarded
APK, with7 decode/4 batch workers vs the original implicit4 control. Selection
uses earlier results only; no old timing transfer. Completed coding task to EOG
with exact reference trajectory/functional checks follows a passing speed stage.
Ledger: `bench/m3-decode-investigation-20261025.json`.

## Completed checkpoint: bounded CPU prefill optimization (preceding APK)

Prefill-qualified APK `e520cc2ea0ce59be1be32f610f624f27cdcc2c08943874870d0f9127191d19a9`,
variant `f32-recurrent-transient-threshold`, CPU-only/KleidiAI OFF. Original GGUF,
quantization and F16 KV unchanged. Native remains default; the new mode is an
explicit experiment, not a promoted product profile. M3 remains open.

The engine now reuses bounded CPU scratch for parallel lossless Q4_K/Q6_K packing
and existing I8MM GEMM. Model tensors remain native/mapped. The model graph packs
only at24+ activation rows; tiny requests stay native. Separate admission reserve
81MiB, observed model scratch peak43,081,984B (~41.1MiB). No persistent full-model
packed copy. Full/Q4-only Ornith packing stays blocked after LOW_MEMORY failures.

**Current-APK speed:** fixed512 input+one output token, ctx8192/t4/b512/ub128/mmap,
separate native2048-token preconditioning and one excluded warmup, then five
counterbalanced pairs. Median native prefill82.580s, candidate49.575s. Paired
median speed ratio1.649, bootstrap95% interval1.621–1.679. All five measured pairs
matched IDs/top8 and relevant frequency endpoints, power/thermal/foreground gates.
Endpoints are not clock residency; cache was not cold. No decode-rate claim.

**Current-APK quality and lifecycle:**
- Tiny4/5-token code/Russian scoring stays native with1,179,904B CPU scratch.
- Expanded29/43-token scoring exercises ~42MB packed scratch; all NLL/top8 errors0.
- Completed code matches all1096 IDs+EOS/top8 and passes134 functional cases.
- 8K retrieval:7680 input+39 emitted tokens, exact reference IDs/top8 and identifier.
- Mixed128+23 and128+24 prefill boundaries both match; marker counts741/986 verify the policy boundary.
- Stop-to-ready98ms; same PID, identical recovery tokens/bytes, successful unload.
- 61 Python tests; patched and pristine-API host lifecycle pass; framework30 cycles pass.
- All13 APK libraries match CMake symbols/stripped bytes and static16KiB LOAD alignment. Actual16KiB-device execution remains untested.

The8K quality prefill928.115s (~15m28s) is still slow and is not a paired speed
measurement. Two earlier short-code speed attempts remain unaccepted because
stock frequency ceilings changed. No broad coding/context/endurance, other-device,
GPU, default-profile or whole-M3 acceptance follows.

Canonical ledger: `bench/m3-transient-threshold-investigation-20261025.json`.
Bounded result: `bench/m3-transient-threshold-screening-20261025.json`.
Controller: `bench/results/transient-workspace-screen-20261025T200803Z/report.json`.
Speed: `bench/results/weight-buffers-ornith-pp512-prefill-speed-v1-20261025T201358Z/report.json`.
Final health: `bench/m3-transient-threshold-final-health-20261025.json`: same boot,
81%, AC connected/adaptive not-charging status4,40°C, no inference process left.
No global phone settings, weights, commits, pushes or publication changed.

**Next M3:** decode/threads/affinity and stable frequency evidence, then the remaining
CPU/backend matrix (Vulkan/OpenCL and bounded alternative-runtime feasibility).
Do not repeat completed long pairs merely to resume; changed APK/config still
needs its own required checks. UI/API and larger-context promotion stay later.

Both-model native mmap/no-prefault control is complete with no useful long-prefill
gain: `bench/m3-native-demand-analysis-20261025.json`. Historical experiments and
failed attempts are retained, each scoped to its named APK; older active-sounding
notes below describe those earlier sessions.

## Resumed after phone recovery (25 October local date)

The owner confirmed that Android booted and charging resumed. Read-only health
observed completed boot,48% battery, charging status2,38°C and a new recorded boot
identity. The prepared one-shot-guard APK was installed and framework validation
passed:30 JNI cycles, legacy/reused debug-run suppression, explicit process-death
recovery, load-pressure cancellation and prefill Stop-to-ready60ms. Exact report:
`bench/results/runtime-tests-20261024T220801Z/report.json`. Artifact names use the
host UTC clock; the owner's local session date is25 October.

Recovered M2 checkpoint APK SHA256 (superseded by the explicit M3 candidate below):
`bfc9cab0229525076aec6541b16dbc30b2600ff8a48e8e9b242c5238cacf2866`.
Its native libraries are byte-identical to the preceding numerically tested APK.
The long quality trials below are being resumed as fresh, separately identified
requests; the interrupted run is not replayed or converted to a pass.

Recovered Ornith lower_bound passed at1096 tokens plus EOS,1097 finite scans,
134 final-code cases and logarithmic reads. Its entire greedy token trajectory
also matched corrected host CPU exactly, beyond the earlier short scored corpus.
Phone: `bench/results/app-service-ornith-20261024T220836Z-82257c5b/`;
host/comparison: `bench/results/host-quality-ornith-20261024T221348Z/`.
Gemma also passed to EOS at1861 emitted tokens,1862 finite scans,134 cases and
logarithmic reads. Its corrected host reference emitted1741 tokens; the common
prefix is319 generated tokens, then IDs43653/12808 diverge. Both final functions
pass. A host-only control replacing the single differing GELU table entry with
the measured Android value restored **all1861 token IDs exactly**, with table
identity checked at exit: `bench/results/host-generated-gelu-control-20261024T222747Z/`.
This explains the branch on this task; no interposer is included in the APK.
Phone:
`bench/results/app-service-gemma-20261024T221603Z-2398c2cc/`; host/comparison:
`bench/results/host-quality-gemma-20261024T221511Z/`.
These are two generated tasks, not broad coding/sustained acceptance. The
corrected-profile Gemma filled8K also passed:8064 input+128 output,128 finite
scans, prefill1001.072s/decode50.383s. Raw:
`bench/results/app-service-gemma-20261024T222655Z-29fcd6dc/`.
It is mechanical boundary evidence, not retrieval/interactive/endurance acceptance;
do not compare this single diagnostic timing as a paired speed regression/win.
All128 generated IDs match corrected host, but the first top8 maximum logit
difference is0.409069, exceeding the unchanged0.1 gate. This is a **failed long
numeric comparison**, despite matching text. GELU-table control is next; no
numeric/M2 acceptance follows from the mechanical pass alone. Comparison:
`bench/results/host-filled8k-gemma-20261024T222346Z/phone-comparison.json`.
The completed GELU-only long control changed one entry and reduced the top8
error to1.9073486e-6, retaining all128 token IDs and passing the unchanged0.1
bound. Artifact: `bench/results/host-filled8k-gelu-control-20261024T224925Z/`.
The original comparison remains failed; CPU-reference table semantics must be
explicit. No new APK math or speedup follows from this host-only control.
Ornith long retrieval v2 passed:7680 prompt+512 reserve,39 output tokens then
EOS,40 finite scans, exact identifier in the final channel. All39 token IDs and
first-top8 logits match host **exactly**. Raw:
`bench/results/app-service-ornith-20261024T224511Z-a92c72c6/`;
comparison: `bench/results/host-retrieval8k-v2-ornith-20261024T223941Z/phone-comparison.json`.
Prefill1377.462s/decode17.992s is not interactive-speed acceptance. The original
v1 host format failure is retained; v2 makes the exact-answer instruction explicit.

The current installed CPU library's complete65536-entry GELU table was recaptured
and matches the frozen reference input exactly:
`bench/results/activation-tables-current-20261024T230931Z/report.json`.
`bench/cpu-reference.lock.json` identifies the host-only table semantics and
patches; no table replacement is bundled in the APK. Independent8192-key attention
matrix passed all8 configurations/16 CPU executions with unchanged gates:
`bench/results/attention-variant-matrix-20261024T230939Z/report.json`.
A fresh corrected-reference short-code mmap baseline (warm-up+5 runs/model) is
next, before any speed comparison. Host cleanup/power-state timing gates were hardened;
36 Python tests pass. A later audit excludes the Ornith retrieval timing from
comparable power because charging status changed2→4 near81%:
`bench/recovery-power-audit-20261025.json`. Original collector fields and its
correctness pass remain unchanged. Static ELF alignment passed all13 current
APK libraries; actual16KiB-page device execution is unverified.

## Initial M3 preflight

### Native mmap without initial prefault: current investigation

Evening continuation authorized: same installed APK verified; initial charge3%,
charging, below20% start guard. No new phone load until ready. Profiling now
verifies all13 native symbol files and sampled DSO build IDs, requires complete
zero-loss record statistics, saves per-thread self time and preserves cleanup
failures.51 host tests passed; existing APK/native arithmetic unchanged.
`bench/m3-native-demand-resume-20261025.json` tracks this continuation.
After reaching20% charge, Ornith warmup+five short pairs passed. Emission ratio
0.97984 (95% interval0.85921–1.04281): no decode gain; load+TTFT interval also spans1.
Pair4 had360–362MiB sampled process swap in both arms and5.06GB prefill-phase reads
in demand mode; retained in statistics. `bench/m3-native-demand-ornith-analysis-20261025.json`.
Separate matching-APK Ornith sampling completed:71,059 samples, zero lost; Q4_K
dot71.36%, Q6_K dot20.13%, gated-delta1.24% in the90s window after512 tokens.
`bench/m3-ornith-prefill-profile-20261025.json`. Gemma control has since completed; see latest checkpoint.
A separate bounded per-matrix allocation/repack/GEMM/free helper is built, waiting
for its phone turn; host Q4_K/DOTPROD strict-bit failures are preserved. No new
matmul implementation or measured app gain follows from this feasibility work.

Current APK `5761d83665d629aeed98af08c03dbcf2cb5ca572dd1b1c0827fbee2a5ad49be7`,
variant `f32-recurrent-native-demand`, KleidiAI OFF. Patch0008 adds an explicit
`mmap_prefault` model-load control, default true. Debug config false is allowed
only with native buffers and explicit mmap; an unsupported build rejects it before
discarding an existing model. No weight, KV or arithmetic change. The control
skips initial MAP_POPULATE/WILLNEED, retaining ordinary demand paging/readahead
and Gemma's existing runtime per-layer embedding prefetch.

Framework30 cycles,48 Python tests and both new/pristine-API host lifecycle tests
passed. All13 native APK libraries match their stripped CMake outputs and have
16KiB ELF LOAD alignment (actual16KiB execution remains untested). Ornith code/
Russian NLL/top8 pairs had zero differences; demand-paged completed code task
1096+EOS/134 cases exactly matched the saved corrected native Android reference.

**Paused by user after12:00 departure:** the active four-trial Ornith long series
finished at12:16 local time. Both opposite-order pairs passed: identical39 output
tokens+EOS and zero first-top8 difference, unchanged native allocation plans.
Prefill remains1373–1378s; native VmHWM5.69–5.74GiB versus demand5.23GiB.
Sampled peak process swap was90.9–103.6MiB versus16.9–17.0MiB. This is a
bounded long quality comparison, not five-pair speed/endurance acceptance.
Ornith corpus/task/context quality lock is ready. Short-speed, Gemma and sampling
were not started. Dispatcher is terminated, inference process is absent, lease
is free; phone health remains ready on the same boot. Original incomplete
controller report is preserved unchanged; the completed-child collection and
exact resume instructions are in:
`bench/results/m3-native-demand-20261025T093522Z/pause-checkpoint.json`,
`bench/m3-native-demand-long-analysis-20261025.json` and `docs/mmap-prefault.md`.
New debug chunk-boundary counters record process stat/io and system memory along
with CPU time/RSS/swap. CPU time includes spin/barriers; high CPU/wall ratio alone
does not identify a hot kernel. Separate bounded sampling is prepared for after
the comparison, excluded from speed evidence. Details/reproduction:
`docs/mmap-prefault.md`; ledger: `bench/m3-native-demand-investigation-20261025.json`.

### Continued M3: explicit CPU_REPACK candidate

**Previous selective checkpoint:** APK
`9500cbaf528b30d6dce92ab8287c99161e191dc847876ce7ea0908980251e45b`,
variant `f32-recurrent-repack-selective`, KleidiAI OFF. Patch0007 adds an explicit
CPU_REPACK tensor-type mask to the pinned experimental API. `cpu_repack_q6`
packs eligible original Q6_K only; native runtime validates actual selected
tensor types and packed bytes. Original Q4_K and all other types remain native;
no weights, scales/codes or F16 KV are changed. Baseline builds without this API
explicitly reject the selective mode, preserving their existing model.

Ornith's selective no_alloc/phone plan is1,839,022,080 committed packed bytes,
compared with5,041,766,400 for full repack. It retains the original1GiB native
mmap working window and the full834,355,200-byte largest source window. Initial
three native/selective pairs plus Russian all passed exactly; anonymous load
peaks were about2.1–2.3GB. Final-APK completed/deep and paired short-speed tests passed
their bounded checks (this does not mean a speed gain):
`bench/results/m3-selective-qualification-final-20261025T083830Z/report.json`.
The pre-guard and final APK's13 native libraries match directly. Current framework
passed30 cycles and the unsafe full-Ornith request was explicitly refused before
loading: `bench/results/app-service-ornith-20261025T083848Z-6d0daa4a/result.json`.
The full model repack is not silently substituted with selective/native.

The attempted patch0006 source-file-cache advice control did not qualify:
one pair passed then the next fresh load process exited. Its immediate exit-info
did not identify that PID, so the precise cause is not asserted. It is retained
as a failed control and **not included** in the selective patch series.
Separate native regression builds passed both original upstream API and selective
API lifecycle tests;44 Python tests passed. Frozen CPU-reference binaries remain
untouched, and upstream B0 stays clean.

Final selective APK passed three code NLL pairs and Russian exactly, then completed
the1096+EOS task/134 cases and7680-input+512-reserve retrieval. Every generated ID
and first top8 matched the saved corrected native Android reference exactly; the
retrieval emitted39+EOS with40 finite scans. Its prefill1261.438s/decode18.198s
remain single correctness timings, not comparable speed/endurance acceptance.
Process swap peaked at sampled1.04GiB and ended near108MiB: record this limit,
not an RSS-only memory win. `bench/m3-long-context-memory-20261025.json` contains
both long correctness runs. The selective Ornith five-pair speed series completed
under its own final APK/policy quality lock. Median paired emission ratio0.98891,
bootstrap95% interval0.89468–1.11633: no demonstrated gain. Separate profile
medians were2.837→2.812 tok/s, first-piece7.435→8.162s, load2.431→5.363s.
Raw results: `bench/results/weight-buffers-ornith-short-code-mmap-v1-20261025T090958Z/report.json`.
The full Gemma series below showed a bounded+8.2% paired emission gain but slower
startup; neither profile is promoted. See `docs/performance.md` for attribution,
memory and uncertainty limits. The final controller's guard-result path metadata
was corrected with its original aggregate preserved as `report-collected.json`.

### Earlier full-repack screening checkpoint

Live ledger: `bench/m3-repack-investigation-20261025.json`. Native remains the
default; no product/sustained profile is promoted. Full-screening APK:
`a02b4206b5e7f53bbdf16768f45066ed5151c3ccba3108c7685cbbaa223adede`, variant
`f32-recurrent-repack-order`, built with `-PmotoaiCpuKleidiAI=false`.
It permits `weight_buffers=cpu_repack` only with CPU_REPACK-only registry extras
and explicit mmap; actual packed allocations must match the model plan. Admission
uses a separate committed/native/source budget and a stricter pressure
watch; unknown models/geometry/read-mode repack are refused. Default native still
disables every extra buffer. No GGUF, weight quantization or F16 KV change.

The initial full repack failed Gemma code/Russian NLL gates; original failures
are preserved. Ornith's first code attempt was killed by LOW_MEMORY (PID31778,
reported RSS7.2GB; only a later exit-info snapshot identifies it). Patch0004 skips
whole-file prefaulting when packing and discards clean, page-aligned mapped source
PTEs after synchronous CPU packing on Android. Mappings remain valid for tied
weights. A fresh Ornith load/score then completed, sampled peak load RSS5.56GB,
but still failed NLL; this load fix alone is not quality acceptance.

Gemma probes isolated tiny GEMV differences in the fifth prompt row: first four
GEMM rows stayed exact. Q4_K used subblock FP32 accumulation instead of the native
integer-block/bias-first order; Q6_K used separate multiply/add instead of FMA.
Patch0005 preserves the native order in target8x8 GEMV. Extended same-APK testing
then passed240 operator cases; all120 CPU_REPACK outputs matched native bits.
Both models' code and Russian teacher-forcing pairs passed with **zero** NLL/top8
error. The subsequent generated/context tests and Gemma paired series are recorded
below. No inference runs are scheduled outside the active controller.

The first complete Gemma candidate task passed1861 tokens+EOS, all134 cases and
the exact original token trajectory. A subsequent Ornith task was explicitly
refused by the additive memory budget (not killed); the failure is retained.
For patched streaming builds only, admission now accounts for model-load and
context/decode phases separately while retaining the same headroom and full
source/native windows. This follows actual allocation order, not a reduced
context/output or a smaller guessed source window. All13 native libraries are
unchanged across the APK budget update. Final APK passed30 framework cycles,
phase-budget/pressure checks, inverse weight audit and all four exact NLL/top8
pairs again; generated/long tests were rerun under that APK identity.
The current expanded operator audit compares actual byte vectors directly:
`bench/results/numeric-20261025T074752Z/report.json` (240 cases/120 exact pairs).

Final APK completed both code tasks again: Gemma1861/Ornith1096+EOS,134 cases each,
exact original generated IDs and first top8. Gemma8064+128 also passed with every
output ID and first top8 exactly matching the saved Android native reference:
`bench/results/weight-buffers-gemma-pp8064-full-mmap-v1-20261025T080038Z/report.json`.
These historical reference timings are not included in performance ratios.
Gemma's bounded screening lock is `bench/m3-gemma-repack-screening-20261025.json`.
Five same-APK measured pairs plus warmup completed: median paired emission gain
8.2%, with slower loading. Exact results and scope are in `docs/performance.md`.

**Ornith repack stability is not accepted.** A fresh near8K attempt was killed
during loading, PID11629, LOW_MEMORY despite the last sampled4.19GB MemAvailable.
The preserved load telemetry shows sampled peak RSS3.41GB before termination;
Android's exit-info RSS is a different/stale2.2GB observation. Do not present
either as a measured whole-load peak. Artifact:
`bench/results/app-service-ornith-20261025T081125Z-9ca666c1/`. Load telemetry had
no run_id field and was ignored by the collector, but its uniquely named file
was recovered separately. The current source includes run_id in that debug schema.

A separately hash-locked patch0006 was tested to advise away consumed clean
source-file-cache ranges after synchronous packing, in addition to dropping PTEs.
It affected only the model FD, never global caches/model bytes. Its second fresh
load failed, so the control was rejected and excluded from the selective series.

### Historical metadata-only checkpoint

`bench/m3-cpu-repack-preflight-20261025.json`: four host metadata plans, current
APK native plans and explicit rejection of mixed KleidiAI extras passed. Independent
inverse layout recovered every original Q4_K/Q6_K byte in22 cases per CPU (44
combined), including both GGUFs: host8x4 / APK8x8. A later model candidate must
still pass numerical/task/context gates and actual paired speed tests.

The model runtime and installed APK remain unchanged: all extra weight buffers
are disabled. The two new EXCLUDE_FROM_ALL diagnostic executables are not APK
assets. Host no_alloc read-layout packed allocation estimates are2.746GB Gemma /
5.042GB Ornith; phase budgets are hypotheses, not measured RSS or a qualified
admission profile. See `docs/weight-buffers.md` for reproduction, failed tool
attempts and the required next steps.

## Previous checkpoint (system interruption at21:28 UTC)

Bounded numeric gates now pass on the corrected candidate, including real APK/JNI:
Ornith code/Russian NLL/top8 bit exact; Gemma maximum errors about1.91e-6. Gates
unchanged. F32 attention, scalar libm and recurrent-convolution fixes are explicit
patches in `patches/cpu-numeric/series.json`; B0 remains pristine. Summary:
`bench/m2-numeric-fix-20261024.json`. Build this candidate explicitly with
`-PmotoaiCpuVariant=f32-recurrent-scalar-math` after
`python3 scripts/prepare-cpu-variant.py f32-recurrent-scalar-math`.
The generic Gradle default is still the unmodified-source baseline, not promotion
of this candidate. No runtime speedup is claimed.

Both phone GGUFs now have a single canonical copy in **Downloads/MotoAI/Models**.
MediaStore PFD read/mmap, lifetime, SHA, repeat import without a new copy and .bak
transaction recovery passed. See `docs/model-storage.md`. Four fresh Ornith mmap
load/scoring processes passed with exact scores. Read and mmap have separate
admission budgets; a100ms pressure watcher cancels safely, and unused allocator
pages are purged on native clear. Unknown/large profiles remain explicitly
unqualified. Runtime30 cycles and normal UI/Stop/recovery checks passed.

Both newly generated add answers reached EOS and passed153 functional cases.
The long Ornith lower_bound trial stopped at229 decoded tokens, approximately76s.
Immediately afterward Android battery/thermal/SurfaceFlinger services were absent;
ADB subsequently disconnected. Before the trial battery was4%, status NOT_CHARGING
although AC-connected. Do not label this as an isolated native crash or LMKD:
exact shutdown/restart cause is not established. Partial output and all evidence
are retained in `bench/ornith-long-system-exit-20261024.json`.

Phone tests were stopped awaiting power/recovery; the owner was asked to connect
reliable charging. New runners require completed boot, valid battery service and
at least20% before starting; active app trials abort below10% or on boot identity
change. These checks never modify device power state. Low-battery timings are
flagged in `bench/low-battery-audit-20261024.json`; they cannot establish normal
powered performance. Numeric/function correctness passes remain separate.
At that checkpoint M2 was still open and M3 had not started. The later recovery
results above supersede the pending-work list, without changing those raw trials.

Local follow-up after the system interruption: new host power/boot guards passed
31 Python tests. A one-shot, fsynced debug-run marker now prevents a restored
benchmark Intent from automatically running the same request again, including
Activity recreation; a focused framework check was added. The follow-up APK
build passed; installation/framework validation subsequently passed after recovery.
See `bench/one-shot-build-20261024.json` for exact local/tested APK hashes and
native-library identity. Install the new candidate and run the lifecycle check
first after recovery (now completed). The guard also recognizes legacy progress/result files,
so interrupted requests from older APKs are not auto-replayed after update.
The previous unavailable-device health snapshot is historical; see the recovery
checkpoint above for the current state.

## Current implementation

- Kotlin UI, service and host runner no longer cap answers at1024. The loaded
  context bounds the reserve; native checks prompt+template+reserve and rejects
  overflow without truncation. Harness default reserve2048; not a release default.
- Optional raw-logit finite scans, per-prefill-block timing, reset time,
  prepare/reset/sampler/prefill/decode progress, backend model/context/compute
  allocations and read-only native FPCR observation.
- Teacher forcing scores an explicit continuation with stable log-sum-exp NLL,
  exact token traces and explicit scored mode. It is never counted as a generated
  quality answer. Optional tensor observer is a separate diagnostic profile.
- Runner saves atomic progress, partial output and exit evidence; stops only
  MotoAI in cleanup. It now detects an observed inference PID disappearing or
  being replaced during load/generation, rather than waiting for a watchdog.
- All project phone workload entrypoints acquire an inherited advisory lease
  before ADB. Four cross-process tests cover mutual exclusion and child lifetime.
  Two accidentally overlapped diagnostics were stopped and invalidated; they
  are not evidence. See bench/overlap-aborted-20261024.json.
- Explicit Load is queued while Binder reconnects; no prompt replay. A test audit
  caught that performClick alone bypassed a disabled button. Load availability
  is now fixed and checked before clicking; dead bindings rebind on explicit Load.
- A transport audit separates UI counters from diagnostic token/timing/probe
  arrays and raw requests. Full debug reports remain intact. On-device test:
  >1MiB full diagnostic retained, UI status parcel208 bytes; no new output cap.

Previous numerically tested APK SHA256 (native bytes unchanged in current APK):
43bd53a972fc617dcdc267f7c931d1542c33d9bbdaac7d7f39c9def3cf0e2be5.
Exact APK and post-build owned-source/tool-lock snapshot:
bench/results/builds/<sha>/. This is not embedded compiler attestation.
Older measured APKs without a source snapshot remain identified as such.

## Earlier M2 results (before the numeric correction)

Completed lower_bound answers with thinking enabled and reserve2048:

| Model | Emitted tokens before EOS | Finite scans incl. EOS | Final code |
| --- | --- | --- | --- |
| Gemma |1919 |1920 |134 cases + logarithmic reads passed |
| Ornith |1460 |1461 |134 cases + logarithmic reads passed |

The evaluator scores final-channel code, never a code block inside reasoning.
Original1024-limited results stay incomplete; no historical baseline was rewritten.
Earlier add answers reached EOS and passed153 cases each. These two owned coding
tasks do not establish HumanEval/MBPP or whole-model quality.

Gemma prefill diagnostics, one trial per depth:128=10.788s,512=46.696s,
2048=197.940s,4096=397.760s. The one-output-token diagnostics isolate prefill;
they are not replacements for a full-context output-reserve trial.

**Filled8064+128 completed:** prefill882.030s, first token882.107s,
decode31.929s, total native request about914s;128 finite scans, thermal status0.
At903s it was decoding token86/128. A900s watchdog therefore cuts this working
CPU workload. The original afternoon timeout had no block telemetry, so its exact
phase cannot be proven retrospectively. No hang/crash in the new run.
Raw: bench/results/app-service-gemma-20261024T190703Z-8485f0aa/.
A single mechanical pass is not long-context retrieval, sustained-memory or
usable interactive-performance acceptance.16K/32K remains unverified.

The separate beginning-needle task also passed:7680 actual prompt tokens,
512-token reserve, exact final identifier,12 emitted tokens then EOS,13 finite
scans; prefill783.530s/decode2.626s. Thinking stayed enabled; the model chose no
thought channel for this simple answer. Raw:
bench/results/app-service-gemma-20261024T200043Z-0f7234c0/.
This began before automatic quality gating was integrated into benchmark.py;
the saved quality.json is a separate explicit assessment. One fact at the start
is not the full multi-position/multi-fact quality matrix or an interactive profile.

A separate10s simpleperf window inside512 prefill found15862 samples, none lost:
self samples Q4_K dot61.73%, Q6_K dot15.98%, BF16 dot12.15%, Flash Attention3.59%.
Selected CPU variant android_armv8.6_1; no extra weight buffers. These are
window-specific fractions, not whole-request fractions or a measured speedup.
bench/cpu-prefill-profile-20261024.json.

## Earlier numeric investigation (original failures retained)

- Existing independent native CPU scalar-reference matmul54 cases passed.
  CPU_REPACK diagnostic27 cases passed; unsupported/unqualified cases stay explicit.
  bench/numeric-correctness-phone-20261024.json.
- Expanded upstream operation smoke passed12 filters/15 real model-sized cases:
  RMSNorm, Softmax, SSM Scan/conv, Gated Delta, Flash Attention and RoPE.
  bench/operation-smoke-phone-20261024-expanded.json. CPU-vs-CPU layout/sentinels
  are not independent mathematical proof.
- Fixed code teacher-forcing host/phone gates FAILED: Gemma mean/max absolute
  token NLL difference0.07287/0.37685, first-top8 max logit difference0.54067;
  Ornith0.03112/0.16928 and0.12982. Top8 sets matched, raw logits finite.
  No bounds were widened.
- Portable host build (Accelerate/native ISA/Metal/BLAS/Kleidi/OpenMP off,
  ARMv8.2 FP16/DOTPROD) gave bit-identical Gemma host scores. FPCR0 on both CPUs.
- Two fresh phone Gemma processes gave exactly equal NLL/top8; opt-in observer
  also preserved the same trajectory. Initial embeddings/Q projection and layer
  outputs0–5 matched whole fingerprints. First observed node difference was
  Qcur_pos-0 after RoPE; first differing layer output was6, sampled endpoints only
  showed larger drift from layer17. Sampling endpoints alone can miss differences.
  Latest full-layer report:
  bench/results/score-gemma-teacher-forcing-code-probe-v1-20261024T195743Z/.
- Separate runtime-angle libm probe found float sinf/cosf bit differences on the
  same angles across Android/macOS; double cos/sin cast to float matched in all
  measured640 local/1280 global rows. No runtime math was changed. Consistent
  with the observed early RoPE difference, not proof of the only NLL cause.
- Corrected BOS-only control passed unchanged gates: NLL difference6.55e-7,
  top8 max difference1.91e-6 and all captured layer outputs bit matched.
  bench/results/score-gemma-teacher-forcing-bos-probe-v1-20261024T194953Z/.
  This does not turn the failed code corpus into a pass.
- Host-only substitution of measured Android float trig fixed Qcur_pos-0 but
  left the layer6 and later mismatch (first-token NLL error0.32016). The trig-only
  hypothesis failed. This control never changes the APK or comparison bounds.
  bench/results/host-libm-control-20261024T200257Z/.
  The subsequent layer6 comparison is described below.
- New72-node observer was measured on both CPUs. Two normal Gemma phone runs on
  the current APK exactly matched it in27-token NLL/top8; host observer likewise
  preserved the trajectory. With measured Android trig substituted on host,
  layer6 Q/K/V and RoPE fingerprints match; first remaining mismatch is kqv_out-6,
  the attention output before output projection. Investigate attention exp/reduction
  before attributing the whole gap to trig. bench/layer6-investigation-20261024.json.
- Actual exported CPU activation tables were read without model inference.
  GELU differs at one of65536 inputs (x=-5.15625, host=-1.788e-7, phone=-0.0);
  Quick GELU matches entirely, FPCR0. Replacing the host's complete GELU table
  with the phone table alone left the27-token score unchanged and still failed
  the original gates. This is another rejected sole-cause hypothesis, not an APK
  math change. bench/activation-tables-20261024.json and
  bench/results/host-gelu-control-gemma-20261024T201626Z/.

## Memory and stability

A later serial Ornith read-mode scoring load was killed by Android LOW_MEMORY,
pid26075, reported RSS5.7GB. Before load MemAvailable6761532KiB. The service
restarted empty with no prompt replay; the old collector waited300s. This is a
real stability failure despite the earlier successful8-minute complete answer.
bench/memory-load-investigation-20261024.json. A file-size/total-RAM check is
not sufficient admission. No foreign apps or system LMKD/thermal/governor policies
were changed. Separate same-weight mmap repeats are being checked; read remains
the unchanged historical baseline, not an accepted stable profile under all RAM
conditions. Two fresh Ornith mmap scoring loads subsequently passed and exactly
matched each other and the previous successful read score (26 tokens/top8).
This is limited evidence on different diagnostic/lifecycle APKs, not an isolated
memory/speed A/B or a universal load guarantee. Exact artifacts and hashes are
in the memory investigation summary. No default profile switch.

Latest framework/JNI instrumentation passed on the APK above:
bench/runtime-tests-resume-ipc-final-20261024.json.30 cycles; bounded IPC, actual enabled/
visible Load after process kill, no prompt replay; SAF-imported model loaded/
generated via ordinary UI;1536 reserve admitted and prompt+2048 rejected in2048
context; load cancellation and prefill cancellation (79ms stop-to-ready in this
case), notification Stop, repeated Stop/unload, callback errors, Unicode JNI.
Python harness21 tests passed. Reference-host native ctest1/1 passed, including
an actual BOS-only request trace regression. Shell syntax and Python compile passed.
New coding/retrieval series fail their gate on incorrect/incomplete final answers;
the evaluator and batch return failure. Correct function + length is explicitly
tested as failure. Original collection-only series remain separately scoped.

## Earlier milestones, pins and restrictions

M0: upstream701fd25b3a55a90297dec0d925204b29084054c8 remains pristine.
Toolchain/upstream/model locks are retained. Both main GGUF payload SHA256 values
are verified; models are not bundled. No release or F-Droid submission.
Ornith model card LICENSE link returned404; resolve provenance before distribution.

M1: own Kotlin/JNI APK, SAF import with streaming SHA/atomic completion; serialized
native ownership in unexported :inference foreground service, Stop/unload,
bounded wake lock, RAII/cancellation/overflow/UTF-8 transport; screen-off/background
mechanical test passed. App UID enumerates Adreno740 OpenCL/Vulkan; GPU compute
has not yet been tested. See bench/m1-results.json and docs/compatibility.md.

Pinned KleidiAI Q6_K/Q8_0 extra weight buffers requantize to new int8 row scales.
The conservative core disables all extra buffers, preserving original GGUF
weights and F16 KV. A plain CPU override was insufficient. Old/default-Kleidi APKs
are excluded, never pooled. docs/weight-buffers.md.

Original short baseline: five fresh-process trials plus one excluded warmup;
Gemma6.32 / Ornith3.42 evaluator tok/s, partial128-token reasoning. These are not
complete-answer or sustained speeds. Debug.getPss includes swapped PSS, not just
resident RAM. CPU allowed0–6 for inference vs0–7 observed for UI, effect unmeasured.

Source candidate only: Gemma tied output allocation is created before embedding,
so reuse may fail; GGUF tied embedding360MiB. No patch or measured win yet.
bench/gemma-tied-embedding-investigation.json.

## Continue M3 here

1. Preserve the frozen M2 CPU-reference APK/locks and GELU reference semantics.
   Keep each M3 result attached to its exact APK, policy, model and configuration;
   historical Android references qualify matching quality checks only, never speed.
2. CPU_REPACK is implemented as an explicit experiment with KleidiAI compiled OFF.
   Current selective APK and full Gemma screening APK are identified above. Native
   remains the default; full Ornith repack is rejected before loading. Do not retry
   rejected full/cache controls without new evidence or enable all CPU extras.
3. Resume native mmap control from the paused checkpoint above, only after the
   user returns. Ornith corpus/task/two long quality pairs are complete; reuse
   their exact APK/policy/config quality lock for warmup+five short speed pairs.
   Then run Gemma control and separate CPU sampling. Do not rerun the90-minute
   Ornith long series or present its two pairs as short-speed acceptance.
4. Broader coding/seeds, context positions/depths, state/reuse/cancel, endurance and
   battery are still required before a product profile. Ornith's long-context swap
   and ~21-minute prefill remain unresolved; passing a retrieval is insufficient.
   Actual16KiB execution and16K/32K context are unverified. No silent output/context
   reduction or weight/KV requantization to make a result pass.
5. Continue CPU threads/batch/affinity, Vulkan/OpenCL and limited DSP/alternative
   feasibility per PLAN. Measure on the APK before claiming a speed improvement.

Full product UI/UX, ephemeral multi-turn chat, HF token/no-token downloads, source
deletion after verified import, model profiles/context controls, and mandatory
OpenAI-compatible API/Aider are later M6/M7. Phone control remains a separate
future direction. Harness debug request/output files are not the no-history release
policy; remove/bound that persistence before product chat/API.

Reproduction: docs/build.md, bench/PROTOCOL.md, locks and raw ignored
bench/results/. Resumed ledger: bench/m2-resume-20261024.json; original baseline/
correctness ledgers remain preserved. Do not repeat M0 downloads or invalidate
older measurements by relabelling them with a newer APK. Runners accept an explicit
--cutoff HH:MM for a bounded session; no inherited cutoff. No concurrent transfers.

Previous selective M3 verification:44 Python tests; compileall/shell syntax; selective and
pristine-upstream-API host lifecycle CTest1/1 each; final APK30 framework cycles;
240 extended operator cases/120 direct byte-equal native/repack pairs; original
weight inverse audit and APK native-library identity/16KiB ELF alignment checks.
Actual16KiB device execution remains untested. See the current M3 ledger for raw
reports. Phone health after the controller passed, same boot,81% with AC connected
and adaptive not-charging status4; no inference process remained
(`bench/device-health-after-m3-20261025.json`). No background phone workload remains.
No commits, pushes, deployment or automation were created.
