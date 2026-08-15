# ADR 0007: Third-Party API Verification

Status: Accepted.

Integration code is written only after inspecting the exact locked dependency source, official example, or packaged class signature. The repository-provided Readium 3.2.0 and sherpa-onnx 1.13.4 sources are the immediate references. Guessing methods, silently substituting mocks, or deleting functionality to compile is forbidden.
