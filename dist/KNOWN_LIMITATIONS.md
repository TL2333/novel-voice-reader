# Known Limitations

- The requested Xiaomi device was disconnected. No installation, audible speech, RTF, RAM, thermal, speed-switching, Web network, OCR, or 30-minute stability result is claimed.
- Android VIEW/SEND and WeChat share routing are covered by pure routing/import tests, but the real provider grant/URI behavior remains `DEVICE_RUNTIME_PENDING` because no device is connected.
- The packaged Kokoro v1.1-zh model has verified Chinese and English lexicons/voices but no verified Japanese voice path. Japanese segments fail explicitly through the pluggable `JapaneseTtsBackend` boundary instead of being mispronounced as Chinese.
- Legacy binary `.doc` is detected but not parsed. The app gives explicit DOCX conversion guidance because no locked, verified Android-safe parser was selected.
- EPUB preserves the proven Readium reader and narration bridge. It uses the shared controller/speed boundary and isolated TTS backend, but it does not yet use the canonical disk-chunk playback queue.
- TXT/DOCX/PDF/Web canonical playback uses one activity-owned ExoPlayer. It is not yet routed through the existing `MediaSessionService`, so background and lock-screen playback for these formats remains pending.
- PDF sentence-level bounds overlays are not implemented. The reader follows and emphasizes the active page and exposes the current canonical segment; OCR/embedded bounds are retained for later overlay work.
- Android versions below API 35 cannot use `PdfRenderer` embedded-text APIs and therefore use bundled OCR for narration text.
- PDF reading order is heuristic. Dense tables, marginalia, unusual multi-column layouts, and handwriting may be imperfect.
- DOCX support focuses on readable paragraphs, titles/headings, lists, tables, and page breaks. Complex fields, drawings, tracked changes, and full header/footer/footnote semantics are limited.
- Web responses are capped at 20 MiB. Dynamic fallback blocks private destinations and cross-origin main-frame redirects; some complex sites will fail safely.
- The post-import canonical block model is loaded into memory by Compose readers. TXT ingestion itself is streaming, but extremely large documents can still require substantial memory while open.
- The deliverable is debug-signed and arm64-v8a only.
