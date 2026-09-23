# BKX permanent personal signing

BKX supports one permanent personal signing certificate for both debug and release APKs. This is intentional for the personal-use distribution model: every APK with package `com.mikeyphw.bkx.android` can keep the same Android update identity and the same Google Android OAuth certificate fingerprint.

No real signing material belongs in the repository.

The default external locations are:

```text
~/.config/devtool/bkx/android-signing.properties
~/.config/devtool/bkx/bkx-personal.p12
```

`BKX_ANDROID_SIGNING_PROPERTIES` can override the properties path. `BKX_ANDROID_KEYSTORE` can override the keystore path when running the setup helper.

Create the permanent key once:

```bash
cd ~/Code/bkx/android/BKX.Android
bash ./tools/setup-personal-signing.sh
```

The helper:

- creates a 4096-bit RSA PKCS#12 key with a long validity period;
- asks for the password without echoing it;
- keeps the properties file and keystore owner-only (`0600`);
- refuses to overwrite an existing key or properties file;
- stores both files outside the repository;
- prints SHA-1 and SHA-256 certificate fingerprints for Google Cloud registration.

When the external properties file exists, Gradle assigns the `bkxPersonal` signing config to **both** `debug` and `release`. When it is absent, normal source validation remains possible, but permanent personal signing is not active.

Check Gradle status without printing any password:

```bash
gradle :app:bkxSigningStatus
```

Print the SHA-1/SHA-256 again when needed (the helper prompts for the keystore password):

```bash
./tools/print-personal-signing-fingerprint.sh
```

Fail closed when a permanent build is required:

```bash
gradle :app:verifyBkxPersonalSigningConfigured
```

Build explicitly with the permanent identity:

```bash
gradle :app:assemblePersonalDebug
gradle :app:assemblePersonalRelease
```

Devtool's developer-facing build, release, and package aliases are also fail-closed on this personal identity:

```bash
devtool -r ~/Code/bkx --target bkx_android build
devtool -r ~/Code/bkx --target bkx_android release
devtool -r ~/Code/bkx --target bkx_android package
```

The split validation aggregates remain signing-key-independent so CI and reproducible source validation do not require access to the developer's private key.

The tracked schema example remains at:

```text
gradle/bkx-release-signing.properties.example
```

Do not commit real `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, password property files, or OAuth secrets. Losing the permanent keystore means future APKs cannot update an installation signed by that key, so back up the keystore and its properties file to a private durable location.
