# Narration corpus

Synthetic, non-private fixtures for segmentation and format regression. `segments.jsonl` covers narration, dialogue, short/long sentences, numbers, dates, time, money, percentages, English, mixed scripts, questions, exclamations, ellipses, em dashes, headings, and an extreme paragraph. `sample-utf8.txt`, `sample-gb18030.base64`, `static-article.html`, and `dynamic-snapshot.html` cover text/web import inputs.

Binary EPUB comes from the repository-owned `app/src/main/assets/testbooks/storyvoice-test.epub`. DOCX is constructed as a real OOXML ZIP by `DocxParserTest`; text and scan PDF objects are constructed by the PDF instrumentation/manual fixture workflow because platform `PdfRenderer` cannot run in a host JVM. Legacy DOC remains explicitly isolated and unsupported until a verified, stable binary parser is selected.
