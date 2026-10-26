# Model storage

Android10+ imports and Hugging Face downloads write directly into pending
`MediaStore.Downloads` rows under **Downloads / MotoAI / Models**. Android9
uses app-private storage. Only single-file GGUF is supported in this candidate; split/sharded GGUF is
rejected explicitly. No broad storage permission is requested. Private
registry/metadata are small; there is no permanent second private weight copy.

A new file becomes usable only after size, SHA-256 reread and bounded GGUF
metadata/tensor-payload checks, fsync, publication and atomic registry commit.
Interrupted imports discard their own pending row; HF partials are retained for
explicit resume/delete. AtomicFile backup recovery is supported. Existing
identical registered models are rehashed before deduplication.

Independent import preserves the selected source. After successful verification,
the UI can offer **separate explicit deletion** of that original, identifying it
and warning about cloud providers. The source is rehashed immediately before
deletion; a changed source is never deleted. If the provider does not allow safe
deletion, the app explains manual removal. Models must be unloaded before source
deletion. There is no automatic deletion of user originals.

Alternatively, **Без копии** retains a persistable SAF grant to a regular seekable
GGUF. No weight copy is created. Disconnect only removes the app reference and
releases its grant; it never deletes that external file. Moving/deleting/changing
it or revoking access causes an explicit load error. A fresh import remains
available when a provider cannot grant stable access or returns a nonseekable pipe.

The service holds a PFD for the full native model lifetime and rechecks the whole
source SHA before loading. Android FUSE can allow reading/mapping a granted FD
while denying reopening `/proc/self/fd/N`; the native boundary duplicates the
actual descriptor and uses upstream's FILE-pointer loader. It never resolves a
raw shared-storage path or requests permission bypasses. `dup`/FILE ownership is
scoped, mmap outlives loader IO, and the original PFD closes after unload.

Hugging Face preview resolves an immutable revision and lists size/license before
transfer. HTTPS uses ordinary certificate validation. Optional tokens are sent
only to the exact huggingface.co host, never forwarded to signed CDN redirects.
Range/If-Range resumes validate Content-Range; an ordinary200 restarts safely.
Optional saved tokens use AndroidKeyStore AES-GCM and can be forgotten.
No credentials are included in model registry, intents, exports or API results.

The main phone models each have one canonical copy, SHA-locked in
`bench/models.lock.json`: Gemma4,977,171,584B and Ornith5,780,090,816B.
`MotoAI-stories260K.gguf` / stories260K is a1,185,376B developer fixture,
**not** either chat model, never bundled or distributed with the application.
The list marks this tiny fixture as a test model. “Загрузить в память” means
loading an existing model for inference, not downloading it from the Internet.

## Evidence

- Historical private→Downloads migration/PFD hash verification:
  `bench/model-storage-pfd-verification-20261024.json`.
- Real storage framework tests: atomic backup recovery, interrupted moves,
  bad digest, missing file, mmap/read parity, repeated FD cycles:
  `bench/results/storage-tests-20261024T211942Z/`.
- Current public HF HTTPS/cancel/resume/Keystore acceptance:
  `bench/results/downloads-emulator-20261026T045817Z/report.json`.
  No valid private/gated account token was supplied, so that access is unclaimed.
- Direct granted-FD no-copy mmap/deletion-safety test:
  `bench/results/reference-emulator-20261026T045009Z/report.json`.
  Actual DocumentsUI grant and reboot qualification is recorded separately in
  `docs/progress.md`; the direct test alone does not establish reboot persistence.

Final phone cleanup: `fixture-cleanup-phone-20261026T121803Z/report.json` removed
only the SHA-verified own old private tiny fixture. Both original main GGUF SHA
values were reread and matched; unknown/external files were preserved.
