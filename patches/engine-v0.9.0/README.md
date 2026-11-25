# llama.cpp v0.9.0 runtime port

The product variant `llamur-v0.9.0` pins commit
`cd959bae99ff4bed22bef2604f45241c09bbb8c8` in
`upstream-v0.9.0.lock.json`. Historical B0 and CPU/GPU experiments retain their
original `upstream.lock.json` and patch files. Old benchmark results do not
qualify the new engine.

`0001-preserve-llamur-runtime.patch` ports the 17 patches selected by the former
`f32-recurrent-long-attention-v2` product variant, without model conversion or
changes to weight bytes or the F16 KV representation. It preserves explicit
F32 attention, scalar math controls, bounded transient repack, native mmap
prefault control, selective backend loading, literal-control-token handling
and versioned exact-layout snapshots.

Merge adaptations:

- CPU attention now accepts indexed KV rows. F32 Q dot products use the mapped
  physical K row; masking and negative-padding handling remain upstream's.
- Scalar softplus retains upstream's improved `log1p` expression, using the
  existing double-to-float scalar math policy.
- Restoring an empty exact-layout snapshot removes existing sequence cells
  before restoring the saved head. A native regression reproduces the old
  stale-cell bug and verifies replacement and byte-exact reserialization.

The patch hash and complete patched tree are locked in
`patches/cpu-numeric/series.json`. Never edit materialized `.tools/` copies as a
source of truth. Existing cache architecture restrictions still apply: LFM3
prefix-state reuse requires its own qualification and is not enabled by this
port. Text-model support does not imply an integrated vision/audio pipeline.
