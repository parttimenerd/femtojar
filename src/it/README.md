# Integration Tests for femtojar

This directory contains Maven Invoker fixture projects used by the `run-its` profile.

Fixtures:
- `basic-maven-plugin`: baseline executable JAR repackaging and runtime verification.
- `bundle-resources`: verifies `bundleResources=true` and runtime resource access via `getResourceAsStream`.
- `deflater-fallback`: verifies `zopfli=false` path still yields a runnable JAR.
- `idempotency`: runs femtojar twice in the same build and verifies no duplicate internal entries.
- `out-file`: verifies `outJars` writes optimized output to a different file and leaves the original JAR untouched.

Run from project root:

```bash
mvn -Prun-its verify
```
