# Validation

Executed on 2026-11-20 using Docker Linux containers, OpenJDK 17, Gradle 8.10.2, Kotlin 2.0.21, Android SDK 35 and Build Tools 35.0.0.

| Check | Result |
| --- | --- |
| Docker builder image | Built successfully, SDK licenses accepted and Gradle prewarmed |
| `assembleDebug assembleRelease testDebugUnitTest lintDebug` | Passed on final source, 2m 56s incremental run |
| Kotlin/JVM tests | 26 tests, zero failures/errors |
| Browser bridge JavaScript | 8 scenarios passed, including delayed response and failure propagation |
| Android Lint | Zero errors, 30 warnings |
| Debug APK signature | Verified with APK Signature Scheme v2 |
| Release signing configuration | Build and v2 verification passed using a temporary RSA key; key and test-signed APK deleted |
| Persistent debug signing identity | Docker Android config volume certificate matches the delivered debug APK |
| Resource/manifest checks | 62 matching strings in English, Spanish and German; XML parsed successfully |
| Compose configuration and build shell syntax | Passed |

The two initial LAN tests exposed a test fixture mismatch: its MockEngine client lacked the HttpTimeout plugin installed in production. The fixture now matches production and explicitly asserts LAN selection before exercising fallback/mutation behavior. All final tests pass.

Lint warnings primarily concern newer dependency versions, version catalog suggestions, API-specific attributes, required JavaScript support and a quantity label. Versions are pinned to this SDK 35 build. Reports are exported under `build-output/reports/`.

Final deliverables are `build-output/app-debug.apk` (debug signed) and `build-output/app-release-unsigned.apk` (unsigned). Release signing with a private distributor key remains a user configuration step. `SHA256SUMS.txt` records artifact hashes. The debug certificate SHA-256 is `8bd2688e381f4a1fdd43c01681874014ca779734fab2bf57f2edf366c53c8b46`.

No APK was installed on a phone and no real MyJDownloader account was used. Protocol tests exercise encrypted HTTP payloads with a fake transport; JavaScript tests use browser API doubles. These results establish a compiling, tested implementation, not end-to-end production acceptance. Configure an AppWork application key and complete the following live checks before distribution.

## Required live acceptance

- Real account authentication; relaunch restores the encrypted session; logout removes it.
- Select online/offline devices and change selected device during refresh.
- Force token expiry; verify reconnect and session rotation.
- Switch LAN/Wi-Fi/mobile data; verify encrypted direct ping, LAN badge, relay fallback and mutation uncertainty handling.
- Query packages/links, add URLs/passwords, prioritize, force/start, pause/resume, stop, remove and clean finished entries.
- CNL GET checks, plain POST, encrypted CNL2 including dynamic `jk`, invalid keys, oversize payloads and scripts that time out.
- Browser CNL via forms, programmatic submit, fetch and XMLHttpRequest on actual websites; external browser restrictions vary.
- Text sharing and DLC/CCF/RSDF imports before/after login, with URI permission and malformed files.
- Pending container imports currently survive configuration changes through the ViewModel, but not process death; re-share the file if the process is killed before import.
- Image captcha and supported token provider challenges with real site keys and origins; expired/unsupported jobs.
- All four themes, dynamic colors on Android 12+, runtime locale switch across API 26 and 33+, landscape and large font.
- Foreground notification Stop, notification permission denied, process death, service restart and WorkManager refresh.
- Signed release installation/upgrade with a privately supplied key.
