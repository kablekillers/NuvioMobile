# Kable Killers Mobile — 0.6.3-beta upgrade audit

Source: https://github.com/NuvioMedia/NuvioMobile/releases/tag/0.6.3-beta
Target branch: `kable-killers-mobile-update` (branched from `cmp-rewrite`).

## Confirmed findings

- Existing fork `iosApp/Configuration/Version.xcconfig`: MARKETING_VERSION=0.5.9, CURRENT_PROJECT_VERSION=141.
- Official `0.6.3-beta` tag: MARKETING_VERSION=0.6.3, CURRENT_PROJECT_VERSION=146.
- Existing fork `androidApp/build.gradle.kts` uses `applicationId = "com.kablekillers.mobile"`; retain this package ID.
- Upstream tag has `composeApp/src/commonMain/kotlin/com/nuvio/app/features/party/PartyRepository.kt`; this path is absent in the fork.
- Upstream `ServerConfiguration.kt` defines `ServerCapabilities.watchParty` and enables it for the official backend. Fork lacks this capability.

## Integration sequence (not yet implemented)

1. Compare upstream 0.6.3-beta source to fork, including all watch-party models, screens, navigation, dependencies, and server API compatibility.
2. Port complete Watch Party feature rather than just toggling capability; test create/join/leave and phone-to-TV interoperability.
3. Preserve Kable Killers Android package ID, branding resources, splash/intro, and signing identity.
4. Adapt TV cache preferences and safe cleanup to mobile's Kotlin Multiplatform architecture; never clear user settings, addons, history, or active playback data.
5. Review update-check behavior and ensure APK distribution points to Kable Killers releases, not upstream.
6. Set version metadata only once actual upstream feature integration is complete. Build `:androidApp:assembleFullDebug` and test before release.

No production branch, app store, or customer APK should be modified until tests pass.
