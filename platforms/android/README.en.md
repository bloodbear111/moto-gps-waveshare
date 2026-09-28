# MOTO GPS — native Android companion app (community port)

> **Language / 语言:** English · [中文](README.md)

This is a **community Android port** of
[MOTO GPS · Waveshare Edition](https://github.com/mx3353672833-debug/moto-gps-waveshare).
It shares the same C++ core and the same BLE v1 protocol as the upstream iOS app,
and it targets the same hardware: a **Waveshare ESP32-S3-Touch-AMOLED-1.75C**
running the existing firmware.

**This is not an upstream-supported release.** The upstream repository currently
ships iOS, ESP32 and web targets. This port is community-maintained; the upstream
author has not reviewed, tested or endorsed it. Upstream original code stays under
[PolyForm Noncommercial 1.0.0](../../LICENSE.md), and this port does not relicense
any of it.

We are happy to take part in the [Glimpse co-creation effort](../../docs/ANDROID_AI_GUIDE.md).
The list of changes that would be useful to send upstream is in
[UPSTREAM_CONTRIBUTION.md](UPSTREAM_CONTRIBUTION.md).

## Design in one table

| Concern | Approach |
| --- | --- |
| Wire compatibility | Frame header, CRC, fragmentation, reassembly, ACK, coordinate conversion and route matching all call the **same** `shared/` C++ code through **NDK + CMake + JNI**. There is no second protocol implementation in the app. |
| Firmware compatibility | Same UUIDs, version negotiation, capability intersection and two-step Ready handshake. The round-display protocol is unchanged and no Android-specific firmware is required. |
| iOS parity | The handshake gate, session heartbeat clock and write-pump pacing follow `platforms/ios/Sources/MotoNavigationCore` case by case. |
| Platform rework | SwiftUI, CoreBluetooth, CoreLocation, MapKit and the Objective-C++ bridges are **not** portable; the UI, BLE transport, location source and foreground service are rewritten for Android. |

## Toolchain

| Component | Version |
| --- | --- |
| JDK | 17 |
| Gradle | 8.14.5 (wrapper committed) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.20 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| NDK | 27.3.13750724 |
| CMake | 3.22.1 |

`minSdk 26` keeps the BLE and Compose surface on APIs we can actually verify, and
avoids unverifiable branches for API 21–25. It does **not** claim all-device
support: no physical-device acceptance has been recorded yet. See
[DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

## Build

```sh
git clone --recurse-submodules https://github.com/bloodbear111/moto-gps-waveshare.git
cd moto-gps-waveshare
printf 'sdk.dir=/absolute/path/to/Android/Sdk\n' > platforms/android/local.properties
cd platforms/android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # needs a device or emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties`, `.env`, keystores, signing passwords and tokens are never
committed.

## Permissions

`BLUETOOTH_SCAN` is declared with `neverForLocation` because scan results are
never used to infer position. That promise does **not** remove the separate
`ACCESS_FINE_LOCATION` permission used for navigation positioning; the two are
requested for different purposes and neither replaces the other. Background
location is a later stage and is never assumed.

## Licence and attribution

PolyForm Noncommercial 1.0.0 for upstream original code; keep
[NOTICE](../../NOTICE), [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md),
[LICENSES/](../../LICENSES) and the OpenStreetMap attribution. The AMap **Web
Service** key lives on the server and never ships inside the APK.
