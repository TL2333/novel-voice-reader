# Dependency Policy

All production dependencies use exact versions in `gradle/libs.versions.toml` and are summarized in `config/dependencies.lock.json`. Wildcards, `latest`, snapshots, and branch names are forbidden.

Before changing a third-party integration: identify the resolved version; inspect that version's source or official example; inspect the packaged JAR/AAR signature when needed; make the smallest compiling experiment; then change production code. A dependency upgrade is an isolated commit and updates the lock file and relevant ADR.

Offline model and native artifacts retain verified source URLs and SHA-256 values. Licenses are packaged. No cloud TTS, cloud OCR, analytics, ads, account, upload, or remote crash SDK is permitted.

PDF display/text APIs are locked to the compile SDK signatures recorded in ADR 0008. OCR uses only the ML Kit `com.google.mlkit` bundled artifacts; similarly named `com.google.android.gms:play-services-mlkit-*` download-on-demand artifacts are forbidden. The official Android guide was checked for version 16.0.1, API 23 minimum, bundled availability, and Chinese/Latin script support before integration.
