# Dependency Policy

All production dependencies use exact versions in `gradle/libs.versions.toml` and are summarized in `config/dependencies.lock.json`. Wildcards, `latest`, snapshots, and branch names are forbidden.

Before changing a third-party integration: identify the resolved version; inspect that version's source or official example; inspect the packaged JAR/AAR signature when needed; make the smallest compiling experiment; then change production code. A dependency upgrade is an isolated commit and updates the lock file and relevant ADR.

Offline model and native artifacts retain verified source URLs and SHA-256 values. Licenses are packaged. No cloud TTS, cloud OCR, analytics, ads, account, upload, or remote crash SDK is permitted.
