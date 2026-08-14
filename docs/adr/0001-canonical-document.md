# ADR 0001: Canonical Document

Status: Accepted.

Every importer produces a format-neutral `CanonicalDocument` with sections, blocks, metadata, stable content hash, and source anchors. Narration consumes only this model. Format-native objects remain inside importer/reader adapters. This prevents parser behavior from leaking into speech planning and allows every format to share one narration pipeline.
