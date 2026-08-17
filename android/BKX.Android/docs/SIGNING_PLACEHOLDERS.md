
# BKX Android Signing Placeholders

This repo intentionally tracks only placeholder signing instructions.

Tracked placeholder:

```text
gradle/bkx-release-signing.properties.example
```

Do not commit:

```text
gradle/bkx-release-signing.properties
*.jks
*.keystore
```

The release seal checks that the example exists and that local signing secrets are not present inside the source tree. Real signing material should live outside the repository and be supplied by the release environment.
