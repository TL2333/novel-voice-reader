# Dependency Lock Report

| Component | Version / commit | Official source | SHA256 |
|---|---|---|---|
| Readium Kotlin Toolkit | 3.2.0 / fe4c32b97f4745971facfbdfbde31520553c770e | https://github.com/readium/kotlin-toolkit/tree/3.2.0 | Git tag/commit verified |
| sherpa-onnx Android AAR | 1.13.4 / 142807252687d81b40d6315f23470a1512a00de3 | https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar | 03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780 |
| Kokoro model archive | Kokoro-82M-v1.1-zh INT8 / HF 155831f1b4ba23b1f5c058be6a61df90cefb2a37 | https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2 | a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6 |
| model.int8.onnx | Kokoro-82M-v1.1-zh INT8 | Embedded official archive | bda15858163726a492d02a9a727bc263551b86ac77f90812c4b30ff41d380e26 |
| lexicon-zh.txt | Same model archive | Embedded official archive | 11111d8cd695fba2ace1367a1d0a708b586e6ef5c1f9be91da5d7eef129b651c |

Gradle resolves application dependencies only from google() and mavenCentral() with pinned catalog versions. Models, AARs, APKs, local SDK paths, signing material, and user content are not committed.