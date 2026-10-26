# Target device

Initial observation: `bench/device-initial.json`, captured through ADB shell.
Recreate with `python3 scripts/device-info.py > bench/device-initial.json`.
No serial number is stored by that collector. The snapshot includes OS/driver
fingerprint and public capability/thermal/memory observations, not user files.

Motorola Edge 40 Pro: SM8550, Adreno 740, Android 16/API 36, 4K pages.
CPU reports FP16/DOTPROD/I8MM/BF16. GPU OpenCL and DSP RPC libraries are in
the OEM public-library list. App-UID enumeration has passed; actual GPU/DSP computation remains untested.
Available RAM and temperatures are transient; read the snapshot timestamp and
recapture before every performance session.

## App UID check (M1)

`bench/device-app-capabilities.txt` was obtained from the installed diagnostic APK.
OpenCL `clGetPlatformIDs` returned success and one platform; Vulkan discovered
Adreno 740 and API 1.3.128. CPU affinity allowed 0-7 in this observation.
This proves enumeration/access, not kernel execution, model support or speed.
