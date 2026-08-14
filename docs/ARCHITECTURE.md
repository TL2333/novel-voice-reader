# Architecture

Novel Voice Reader is an offline-first Android document reader. Local formats never require a network. Only `content/web` may fetch a user-supplied HTTP(S) URL, and fetched content is frozen into a `WebSnapshot` before it reaches reading or narration.

The target dependency direction is:

`content source -> importer -> CanonicalDocument -> speech planner -> NarrationController -> TtsClient -> :tts -> AudioArtifact -> AudioCache -> ChunkAssembler -> PlaybackQueue -> Media3`

Reader presentation is a parallel adapter layer: canonical source anchors flow through `ReaderSync` to the EPUB, text, Word, PDF, or Web reader. EPUB rendering continues to use Readium. PDF display is independent of PDF text extraction. Format parsers never enter narration or playback packages.

The main process owns UI, Room, DataStore, importers, readers, `NarrationController`, the formal Media3 player, MediaSession, queues, cache policy, and traces. The `:tts` process owns sherpa/Kokoro and one serialized inference worker. IPC contains identifiers, text, profile metadata, and file paths only.

See `ARCHITECTURE_RULES.md` for enforceable boundaries and `adr/` for accepted decisions.
