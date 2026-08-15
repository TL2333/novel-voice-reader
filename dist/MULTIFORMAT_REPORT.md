# Multiformat Report

All statuses below distinguish build-verified implementation from physical-device verification. No device was connected for runtime acceptance.

| Format | Import | Reading | Narration | Progress / bookmark | Highlight / follow | Known limitation |
|---|---|---|---|---|---|---|
| EPUB | Existing Readium EPUB 2/3 SAF import preserved | Existing Readium reader | Kokoro through the Readium/Media3 bridge and isolated `:tts` backend | Readium Locator JSON | Sentence decoration and auto-follow | Not yet converted into the canonical chunk queue; DRM EPUB is unsupported |
| TXT | Streaming UTF-8/BOM, UTF-16 LE/BE, GB18030, GBK, best-effort Big5; chapter detection | Compose canonical reader with shared DataStore appearance | Canonical segment/chunk/cache/ExoPlayer pipeline | `DocumentLocation` block/range JSON and bookmarks | Current block and auto-follow | Canonical JSON/blocks are memory-resident after streaming import |
| DOCX | Safe bounded OOXML ZIP + hardened SAX; title, heading, list, table text, page breaks | Compose canonical reader | Same canonical narration pipeline | Paragraph-index anchors and bookmarks | Current block and auto-follow | Complex fields, drawings, tracked changes, headers/footers, and footnote bodies are not fully represented |
| DOC | Explicitly detected and rejected with conversion guidance | Not available | Not available | Not available | Not available | No verified Android-safe legacy binary DOC parser was selected |
| PDF | Android PDF text extraction on SDK 35+, scan detection, bundled Chinese+Latin local OCR | Separate `PdfRenderer` page viewer | Extracted/OCR text enters the canonical pipeline | Page/block/bounds anchors and page bookmarks | Auto-follow to current page; current page emphasized | Precise sentence bounds overlay is not implemented; pre-35 text PDFs use OCR |
| Web | HTTP(S) validation, public-destination policy, max five redirects, content-type/magic routing, Jsoup scoring, guarded WebView fallback, immutable snapshot | Canonical snapshot reader, not a live browser | Snapshot enters the canonical pipeline | Snapshot/block/content-hash anchors and bookmarks | Current block and auto-follow | 20 MiB response cap; fallback restricts main-frame redirects to the original origin |

Direct `.pdf`, `.txt`, `.docx`, and `.epub` URLs are detected by header/magic/path and routed to the matching importer. HTML and direct canonical documents are persisted before reading or narration. Local formats, TTS, and OCR remain offline; only `content/web` contains network code.

Verification: 64/64 unit tests passed, including charset/chapter, DOCX parser, PDF order, article extraction, URL/content detection, snapshot atomicity, architecture boundaries, speed/cache rules, and narration algorithms. APK static verification confirmed arm64-only native libraries, bundled Kokoro, and bundled Chinese/Latin OCR assets.
