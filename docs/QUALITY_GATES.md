# Quality Gates

Each stage compiles and runs its focused tests before commit. The final gate is `clean`, `testDebugUnitTest`, `lintDebug`, and `assembleDebug` without globally disabling lint.

Architecture tests reject sherpa callback calls, sherpa imports outside the TTS service, network clients outside `content/web`, playback speed in cache keys, and multiple formal players. Algorithm tests cover 0.75x, 1x, 1.25x, 1.5x, 1.75x, and 2x.

Device evidence is reported separately from unit/build evidence. Passing compilation never implies device stability. Release claims require format import/read/narrate/progress checks, speed changes during playback, zero duplicate/skipped/out-of-order segments, zero SIGABRT, and a 30-minute run when the named device is connected.
