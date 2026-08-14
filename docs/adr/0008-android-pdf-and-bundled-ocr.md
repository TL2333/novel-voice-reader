# ADR 0008: Android PDF Display, Text Extraction, and Bundled OCR

Status: Accepted.

PDF display and PDF narration are separate adapters. Display uses `android.graphics.pdf.PdfRenderer`, available from API 21. Embedded text extraction uses the API 35 `PdfRenderer.Page.getTextContents()` result and its `PdfPageTextContent.getBounds()` coordinates; these signatures were verified against the locally installed Android SDK 36 `android.jar`. Devices below API 35 use OCR for text extraction while retaining the platform renderer for display.

Likely scans are detected from consecutive low-text pages that also report image content. OCR uses exact-version `com.google.mlkit:text-recognition:16.0.1` and `com.google.mlkit:text-recognition-chinese:16.0.1`. These are the bundled artifacts documented by Google: the models are linked into the APK and are available without a first-run download. Both recognizers run locally and the result with the most recognized letters/digits is retained. No Play Services/downloading OCR artifact, cloud endpoint, document upload, or OCR network permission is used.

OCR and embedded text both produce positioned `PdfTextFragment` values. `PdfReadingOrderResolver` removes repeated page margins, orders simple two-column layouts by coordinate clusters, repairs ASCII hyphenation, and maps the result to `CanonicalDocument`. Exact bounding-box overlay is best-effort and cannot block page-level highlighting or narration.
