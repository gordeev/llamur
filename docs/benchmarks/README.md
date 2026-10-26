# Public benchmark extracts

These extracts expose the numbers used in the README without raw device dumps,
private workstation paths, credentials or unrelated run content. They are local
project measurements on one Motorola Edge 40 Pro (Snapdragon 8 Gen 2, 12 GB,
Android 16), not an independent cross-device benchmark.

- `ornith-prefill-512.json`: all six pairs, including excluded warm-up, from the
  frozen five-measurement prefill experiment; exact APK/model hashes, trial order,
  input/output counts, timings, workspace and paired ratios. Source-report SHA-256
  matches `bench/m3-transient-threshold-screening-20261025.json`. Frequencies were
  checked at relevant endpoints; this does not prove identical clock residency.
  Ratios describe native versus experimental packing in that APK. No decode,
  default-on, cold-storage or generic latest-release gain follows.
- `short-ui-requests.json`: four same-engine baseline/product/product/baseline
  rows per model, retaining request counts/times and exact artifact hashes.
  One earlier warm-up is excluded by the original observation. Product means real
  composer/RAM chat/render; baseline means the app's direct harness, not the
  untouched upstream Android sample. This measures integration overhead on two
  short pairs and cannot establish sustained speed or arbitrary conversation quality.

Prefill speedup is the median of five paired ratios, not the ratio of pooled runs.
README prefill throughput is 512 divided by the separate median prefill seconds.
Short-request wall time is the mean of two product rows. Backend decode rate is
127 evaluations divided by each product decode duration, averaged over those two
rows; 128 emitted tokens include the sampled first token. Those outputs ended at
the chosen length budget, not EOS, and may contain reasoning.

The initial upstream Android sample could not supply a valid apples-to-apples
before/after throughput result for both exact models: Gemma hit a template abort,
and Ornith's initial Jinja-enabled mmap profile hit Android low-memory termination.
See `docs/compatibility.md` and `docs/performance.md` for the scope of those historical
runs, later numeric corrections, excluded trials and full-context limitations.
