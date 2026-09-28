# MOTO GPS — Android 原生配套 App（社区移植）

> **语言 / Language:** 中文 · [English](README.en.md)

这是 [MOTO GPS · Waveshare Edition](https://github.com/mx3353672833-debug/moto-gps-waveshare)
的**社区 Android 原生移植**。它与上游 iOS App 共用同一套共享 C++ 核心和同一套
BLE v1 协议，目标硬件仍是 **Waveshare ESP32-S3-Touch-AMOLED-1.75C** 与现有微雪固件。

**这不是上游官方支持的版本。** 上游仓库目前只有 iOS、ESP32 与网页端；本项目由
社区维护，上游作者未参与审阅、测试或背书。上游原创代码继续按
[PolyForm Noncommercial 1.0.0](../../LICENSE.md) 授权，本移植不做任何重新许可。

我们愿意参与 [Glimpse 共创](../../docs/ANDROID_AI_GUIDE.md)：欢迎上游作者、
其他安卓开发者和骑手一起审阅、纠错与合并；具体可回馈上游的改动清单见
[UPSTREAM_CONTRIBUTION.md](UPSTREAM_CONTRIBUTION.md)。

## 为什么值得用原生实现

| 关注点 | 做法 |
| --- | --- |
| 协议一致性 | BLE 帧头、CRC、分片、重组、ACK、坐标转换、路线匹配全部通过 **NDK + CMake + JNI** 调用 `shared/` 里的同一份 C++ 代码，应用里没有第二套协议实现 |
| 与固件兼容 | 沿用现有 UUID、版本协商、能力交集、两步 Ready 握手；不修改圆屏协议，不要求刷写安卓专用固件 |
| 与 iOS 行为一致 | 握手门、心跳时钟、写队列节流等策略逐条对照 `platforms/ios/Sources/MotoNavigationCore`，并用相同用例验证 |
| 平台差异 | SwiftUI、CoreBluetooth、CoreLocation、MapKit、Objective‑C++ 桥接**不能**直接搬；UI、BLE、定位、前台服务均按 Android 平台重写 |

## 工程结构

```text
platforms/android/
├── app/
│   ├── build.gradle.kts              # 编译 SDK 35 / minSdk 26 / NDK 27.3
│   └── src/
│       ├── main/cpp/                 # 新增的 Android 适配层（仅此层是新的 C++）
│       │   ├── CMakeLists.txt        # 直接 add_subdirectory 上游 shared/
│       │   ├── moto_jni.cpp          # Kotlin <-> shared C++ 的 JNI 编组
│       │   └── moto_golden_selftest.cpp
│       ├── main/java/…/protocol/     # 协议门面 + 握手/心跳/写队列策略
│       ├── main/java/…/ble/          # 扫描、连接、GATT 串行队列、会话
│       ├── main/java/…/navigation/   # NavApp / NavCore 的 JNI 门面
│       ├── main/java/…/gateway/      # 网关地址规范化（HTTP 客户端见阶段四）
│       ├── main/java/…/ui/           # Jetpack Compose 界面
│       ├── test/                     # JVM 单元测试（不需设备）
│       └── androidTest/              # 真机/模拟器测试（JNI、黄金字节）
├── gradle/libs.versions.toml         # 版本目录，锁定工具链
└── DEVELOPMENT_STATUS.md             # 已实现 / 自动测试通过 / 真机通过 / 尚未验证
```

UI、导航会话、定位、网关、BLE 与地图存储是分开的层：`MainActivity` 只负责请求权限，
连接状态由 `MotoBleCentral` 与 `ConnectionViewModel` 持有，旋转屏幕或切换任务不会
断开与圆屏的连接。

## 工具链

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17（Microsoft OpenJDK 17.0.20.1 LTS） | AGP 8.x 要求 JDK 17 |
| Gradle | 8.14.5 | 随仓库提交 Wrapper |
| Android Gradle Plugin | 8.13.2 | |
| Kotlin | 2.2.20 | 含 `org.jetbrains.kotlin.plugin.compose` |
| compileSdk / targetSdk | 35 | |
| minSdk | 26 | 见下文评估 |
| NDK | 27.3.13750724 | 上游 C++ 使用 C++17，无额外依赖 |
| CMake | 3.22.1 | 由 Android SDK 提供，供 AGP 调用 |
| Compose BOM | 2026.09.00 | |

具体可用版本以 `gradle/libs.versions.toml` 为准。

### minSdk 的评估

取 **26（Android 8.0）** 的理由是：`connectGatt(..., TRANSPORT_LE)`（API 23）、
`BluetoothGattCallback` 的现代重载、Compose 与前台服务类型都在此之上有稳定支持，
同时避免为 API 21–25 维护一套没有真机验证的分支。

这**不代表全机型兼容**。当前只在文档层面确认了 API 分支（`BUILD.VERSION` 判断
写队列、CCCD、MTU 与通知回调的老/新重载），尚未在任何真机上完成验收；
实测机型、系统版本与结论见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。

## 构建与安装

前置条件：JDK 17、Android SDK（platform 35、build-tools 35.0.0、
platform-tools、NDK 27.3.13750724、CMake 3.22.1）。仓库根目录必须包含完整的
`shared/` 源码。

```sh
# 1. 取得源码（含子模块，LVGL 只影响 ESP32 / 网页目标）
git clone --recurse-submodules https://github.com/bloodbear111/moto-gps-waveshare.git
cd moto-gps-waveshare

# 2. 指向本机 SDK（该文件不入库）
printf 'sdk.dir=/absolute/path/to/Android/Sdk\n' > platforms/android/local.properties

# 3. 构建 debug APK（会调用 CMake 编译 shared/ C++ 核心）
cd platforms/android
./gradlew :app:assembleDebug

# 4. 单元测试与 lint
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug

# 5. 安装到已连接的手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

仅有真机才能运行的检查：

```sh
./gradlew :app:connectedDebugAndroidTest   # 需要已连接设备或模拟器
```

`local.properties`、`.env`、keystore、签名密码与 token 都不入库。

## 权限说明

| 权限 | 用途 | 说明 |
| --- | --- | --- |
| `BLUETOOTH_SCAN`（`neverForLocation`） | 按服务 UUID 扫描圆屏 | 明确声明不用扫描结果推断位置 |
| `BLUETOOTH_CONNECT` | 连接、发现服务、读写特征 | API 31+ |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | 旧系统上的蓝牙访问 | 仅 `maxSdkVersion="30"` |
| `ACCESS_FINE_LOCATION` | 导航定位 | **与扫描权限互相独立**；扫描用 `neverForLocation` 并不取消它 |
| `INTERNET` | 访问自建 HTTPS 网关 | |

手机只在意模糊定位时，界面会明确提示导航需要精确定位，而不会把模糊定位当成可用位置。
后台定位属于后续阶段，需要单独说明与验证，不作为默认前提。

## 协议要点（实现必须遵守）

来自 [`shared/protocol/ble-navigation-v1.md`](../../shared/protocol/ble-navigation-v1.md)：

* ESP32 是 GATT server，手机是 central；UUID 固定不变。
* `max_frame_size = min(ATT_MTU - 3, 设备上报值, 512)`，不假设永远是 512；
  协议帧自带帧头与 CRC，**不能**把 JSON 直接写进特征。
* 必须等设备**第二次**回送相同参数的 `Ready` 才算握手完成；
  GATT 写成功不等于应用 ACK。
* 一个方向同一时刻只允许一个未完成逻辑消息，分片不得交织。
* 心跳的 `monotonic_ms` 是**本会话已用时间**，不是手机开机时长。
* 断连要清空发送队列并重新握手，随后补发最新状态。

## 许可与署名

* 上游原创代码：PolyForm Noncommercial 1.0.0，见 [LICENSE.md](../../LICENSE.md)。
* 必须保留：[NOTICE](../../NOTICE)、[THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)、
  [LICENSES/](../../LICENSES) 与 OSM 数据署名。
* 高德 **Web 服务** Key 只放在服务端；它不进入 APK。若将来必须使用端上地图 SDK，
  按 SDK 规则限制用途，并且不把 SDK Key 当作可保密凭据。

贡献者署名记录在 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。
