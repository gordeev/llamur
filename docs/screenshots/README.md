# English UI screenshots

These PNGs are direct, unedited Android UiAutomation screenshots of the running
llamur0.6.4-dev debug app, on the isolated API36 ARM64 emulator at1080×2400.
The UI is English; both light and dark themes are captured. No private phone
screenshots, external notification content or real account credentials are used.

Conversation content is public, scripted demonstration text. It travels through
the real provider client, inference-service chat store, IPC and UI using a local
loopback fixture. The visible source says **Demo provider**. No main model or cloud
account generated these demo replies. They illustrate the UI, not model quality,
performance or a downloaded model's current loaded state. The fixture is Android
instrumentation code only and is absent from the normal release APK.

Files include chat list/folders, conversation, settings, About with repository
link, storage modes and Hugging Face download form in both themes. PNG hashes,
APK/test identity and successful capture reports are in `capture.json`.

Reproduce with the built separate debug app and instrumentation APK:

```sh
python3 scripts/run-readme-screenshots.py \
  --apk build/your-snapshot/llamur-debug.apk \
  --tests build/your-snapshot/llamur-tests.apk
```

The script requires the public emulator, sets English temporarily, captures
screens, removes its demo chats/provider/folders, restores theme and language,
and stops the debug app. It never captures the owner's normal application data.
