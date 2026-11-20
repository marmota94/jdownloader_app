# JD Remote

Native Android client for MyJDownloader: Kotlin 2, Compose Material 3, Hilt, Ktor, DataStore and Android Keystore. Minimum Android 8 (API 26), target/compile SDK 35. Independent project, not affiliated with AppWork or JDownloader.

## Build without Android Studio

Install Docker with Linux containers, then run from this directory:

```powershell
docker compose build android-builder
./scripts/build.ps1 debug
./scripts/build.ps1 test
./scripts/build.ps1 release
```

On Linux/macOS: `make build-debug`, `make test`, `make build-release`. Direct commands also work:

```sh
docker compose run --rm android-builder ./gradlew --no-daemon assembleDebug
docker compose run --rm android-builder ./gradlew --no-daemon testDebugUnitTest lintDebug
```

APKs from successful requested assemble tasks and test/lint reports are copied to `build-output/`. Initial SDK/Gradle downloads take time; named volumes preserve Gradle caches and the Android debug keystore across container runs, allowing debug APK upgrades without changing their signing identity. Source files are bind-mounted at `/app`; a separate read-only `local.properties` mount points to the container SDK and hides any Windows SDK path. Android Studio is optional. The Gradle wrapper distribution has a pinned SHA-256 checksum.

## Versioning

Set the APK version in `gradle.properties`. Use a semantic version for `appVersionName` and increase the integer `appVersionCode` for every distributed update. Debug and release APKs read the same values.

After verifying the build, commit the version change and create an annotated Git tag matching the version name, such as `v1.0.0`.

## Release signing

Without signing variables, release produces an unsigned APK. To create a signed release, place an existing keystore in a private folder under the project (ignored by Git), then set environment variables before building:

```powershell
$env:SIGNING_STORE_FILE='/app/private/release.jks'
$env:SIGNING_STORE_PASSWORD='your-store-password'
$env:SIGNING_KEY_ALIAS='release'
$env:SIGNING_KEY_PASSWORD='your-key-password'
./scripts/build.ps1 release
```

These are runtime container variables. Keystores are excluded from Docker image context. Keep the signing key and its passwords backed up outside the repository. Never commit `.env` files or keys. Docker image rebuilding is unnecessary when signing variables change.

## Use

1. Enable MyJDownloader in your desktop JDownloader installation and sign in there.
2. Open JD Remote, sign in with the same account and select an online device. To enable 30-day recovery, select **Keep me signed in for 30 days** before signing in.
3. Downloads shows packages and links; Start/Pause/Stop operate on the remote downloader. LinkGrabber accepts URLs, optional package name and passwords. Row actions start, prioritize or remove links/packages. Removing list entries keeps files on disk.
4. Share text URLs to JD Remote. Open `.dlc`, `.ccf` or `.rsdf` files with it to forward the actual container contents. Imports wait until a device is selected. The limit is 5 MB per container.
5. In Settings choose System/Light/Dark/AMOLED, dynamic colors, or English/Spanish/German. The application language integrates with Android per-app locale settings.
6. Enable Click and Load while using trusted websites. A foreground notification exposes a Stop action. The receiver binds only to `127.0.0.1:9666`. The built-in browser intercepts CNL requests and supplies an asynchronous POST bridge because Android's `shouldInterceptRequest` does not expose request bodies. External browsers can still block HTTPS-to-HTTP requests.

## Protocol and architecture

`domain/` defines the repository contract and models, `data/` implements encrypted RPC and persistence, and `presentation/` exposes StateFlow through an MVVM ViewModel. The foreground service runs the receiver; WorkManager periodically refreshes the session while network is available. UI polling follows the visible activity lifecycle.

The client uses account domain-separated SHA-256 secrets, HMAC-SHA256 signed server requests, AES-128-CBC encrypted payloads, response request-ID verification, and token rotation on reconnect. Request IDs are reserved atomically in DataStore to remain monotonic across process restarts and clock changes. Session data including the device secret is encrypted with an Android Keystore AES-GCM key before DataStore persistence. The password is not persisted. Backup is disabled.

The optional 30-day recovery window starts when you enter your credentials. If MyJDownloader rejects a reconnect, the app can establish a new session using an encrypted sign-in key derived from your password. Automatic recovery does not extend the window. Signing out clears the key, and the app removes expired recovery data the next time it checks the session. Existing users can sign out and sign back in to enable it.

The contemporary LinkCollector API is exposed as `/linkgrabberv2`, which is used for the typed v2 query schema. `LegacyLinkCollectorClient` separately implements the requested deprecated `/linkcollector` calls with their positional parameters and `infoMap` response schema. Do not substitute its namespace into v2 calls. LAN candidates come from the encrypted device discovery response and must be private literal IPv4 addresses. Read calls fall back to relay if LAN fails. A timed-out mutation is reported as uncertain instead of being replayed and potentially adding/removing data twice. Refresh before repeating an uncertain command.

Primary protocol reference: [official MyJDownloader API](https://my.jdownloader.org/developers/index.html). Implementation cross-check: [MyJDownloader Java client source mirror](https://github.com/akhsaul/jdownloader2-mirror/tree/master/MyJDownloaderClient).

Set `MYJD_APP_KEY` to your AppWork-issued application key before creating a distributed release (the Java client documentation directs developers to contact AppWork). The default `org.jdownloader.remote` identifies this development build and has not been registered or validated with the live service. The application key is public client identification, not an account password.

## Validation and acceptance

See `VALIDATION.md` for actual build/test results and outstanding acceptance. A compiling APK does not prove live authentication, LAN behavior or captcha provider compatibility. Do not distribute as a validated production release until those checks pass against a real account, desktop device and Android phone. No account password or signing key is bundled.

Additional bridge verification: `node scripts/test-cnl-browser.cjs` (Node 18+). This executes the actual injected JavaScript against browser API doubles, including delayed native responses and errors; it does not replace Android WebView acceptance.

Run `python scripts/source-delivery.py` to regenerate `SOURCE_DELIVERY.md`, containing the complete file tree followed by the full text sources. The Gradle wrapper JAR is supplied separately.
