# Messenger redesign — owner request26October2026 (M10)

The two attached Telegram images are visual references only. Do not copy private
contacts/messages/photos into app assets, logs or public reports. Build native
Android UI, no proprietary Telegram assets or new provider SDK dependencies.

## Product

- Home: virtualized conversation list, search, swipeable horizontal folder tabs,
  centered floating new-chat icon. Empty state offers Create. Bottom navigation:
  Dialogs / Local API / Settings. No sidebar as the primary navigation.
- New chat: explicit active local/API model, Temporary/Permanent, folder. Default
  temporary; Never-save guard stays default. Enabling permanent storage is an
  explicit product choice, with a clear confirmation if currently disabled.
- Chat: Back, model avatar/name/source, source info card, in-chat search, pin/unpin
  messages and jump to pin, message bubbles, centered Send→Stop icon, Enter/IME
  Send and hardware Shift+Enter. No automatic history transfer between sources.
- Model info: local/API, actual model/protocol/endpoint/context or unknown, usage,
  reported monetary cost only if supplied. No guessed tariffs or fake $0.
- Settings: local models/HF, provider connections and active models, theme,
  notifications, storage mode Never / Keep permanent / Auto-delete (1h/24h/7d/30d).
- Local API tab: select local model/profile, start/stop, served public id/name,
  URL/token/certificate controls. Incoming API still uses local engine only.

## Data ownership / lifecycle

RuntimeService process owns chats, store and mutations. Temporary chats never
serialize to disk (including draft/title/opaque state). Optional permanent chats
use Android SQLite with AES-GCM encrypted chat/catalog payloads and AndroidKeyStore
key; no plaintext transcript, backup/logging or cloud sync. Credentials remain in
ProviderStore; never serialize them into history. Preserve signed/encrypted native
reasoning payloads so valid multi-turn continuation survives process restart.

Bounded lazy cache: catalog metadata in RAM, selected permanent conversations
loaded as needed; evict only safely saved inactive permanent records, never
silently evict temporary/unsaved/active conversations. Maintain explicit message,
RAM, per-chat and disk budgets. Save admissions/completions/drafts/metadata, not
full history for every generated token. Disk failure leaves live answer visible
with an explicit unsaved warning. No automatic request replay on process death;
restored active records become interrupted. Native weights/math/quality unchanged.

Auto-delete from last activity, skip active request until completion; reconcile
on access/start and use inexact Android alarm for best-effort cleanup when process
is absent. State Android may delay background cleanup; do not promise exact-time
secure flash erasure. Never-save transition explicitly clears saved records and
converts still-open content to RAM-only. Deleting folder moves chats to All; chat
removal is a separate explicit action.

Re-entering a RAM API key or renaming a connection must not invalidate a permanent
chat if routing/protocol/model parameters are unchanged. Changing actual endpoint/
protocol/parameters still requires a new chat; no hidden destination switch.

## Verification before delivery

- Real storage tests: temporary plaintext absent in database, encryption, restart,
  interrupted-state/no replay, key re-entry, retention/expiry/delete/disable, folder
  assignment, pins, bounds/lazy eviction and disk failure visibility.
- Real UI: home/create/folder/search/back, permanent/temporary flows, active model
  choice, model info, pin jump, composer keyboard/Stop, Local API/custom id/name.
- Inspect public-fixture screenshots light/dark, large font, keyboard, empty and
  populated home/chat. Geometric vector icons remove font-baseline centering bugs.
- Phone leased light interaction/background tests; use owner unlock, no private
  screen dumps. Diagnose granted-notification tap test failure from prior M9.
- Existing local/API/provider protocol/lifecycle regression, native-library byte
  bridge; no unnecessary repeat of multi-hour context tests if native unchanged.
- Normal non-debug candidate, accurate privacy/docs/PLAN/source/checksum evidence.
  Never overwrite frozen0.1 or0.2 iterations. No commits/push/publish.

Current implementation checkpoint is in docs/progress.md. This is an execution
plan; do not treat unwritten features or unchecked gates as completed.
