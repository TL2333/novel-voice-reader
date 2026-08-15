# ADR 0005: Dynamic Synthesis Buffer

Status: Accepted.

Prefetch is measured in playable wall time, not sentence count. Device class and p95 RTF select watermarks; speed converts wall targets to source-media duration. Generated audio is disk backed, with bounded temporary PCM. This supports 1.5x-2x playback without double acceleration and makes overload visible as buffer state rather than skips.
