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

这**不代表全机型兼容**。API 分支（`Build.VERSION` 判断写队列、CCCD、MTU 与通知
回调的老/新重载）目前只有文档与代码层面的确认，**没有逐版本真机矩阵**。

已记录的真机：Xiaomi 14 Pro（HyperOS 3.0.308.0.WNBCNXM.C11，Android 16）——
安装、启动与**协议自检 10/10 通过**。扫描、配对、握手、断连恢复等依赖圆屏的
项目仍未验证。完整记录见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。

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

## 发布签名与证书指纹（高德等需要绑定包名的 SDK 用）

包名（`applicationId`）：`io.github.bloodbear111.motogps`

| 用途 | 证书 SHA1 | 证书 SHA256 |
| --- | --- | --- |
| debug（本机调试密钥） | `6E:16:34:4A:CA:72:68:5D:68:BF:1B:E9:32:9A:0B:B9:C0:81:DB:F7` | `96:93:9E:5E:3D:73:CB:97:B5:5F:22:F1:D6:A8:B1:8B:BB:1D:37:14:36:D5:FE:85:5A:C9:26:1F:A9:EF:31:BF` |
| release（维护者发布密钥） | `A3:7F:D3:B3:F8:F3:52:CD:D3:CD:82:A3:DA:94:43:D3:78:E3:73:97` | `16:8B:32:78:BC:0B:B3:AA:86:D1:B0:12:E6:1D:2E:45:7B:75:98:84:48:2E:99:47:BC:D3:D0:DE:46:DF:BE:A6` |

- debug 指纹来自 Android 调试密钥库（`%USERPROFILE%\.android\debug.keystore`，别名 `androiddebugkey`）。它只能用于本机调试，**不能对外发布**。
- release 密钥库位于本机受保护目录（`%USERPROFILE%\.moto-gps-release\`），别名 `moto-gps-release`，PKCS12，有效期 10000 天。**密钥库与口令文件必须离线备份**：丢了就无法用同一包名更新，按此 SHA1 注册的 SDK Key 也会失效。
- 仓库不含任何签名材料：`keystore.properties`、`*.jks`、`*.keystore` 都在 `platforms/android/.gitignore` 里；`app/build.gradle.kts` 只在本地存在 `keystore.properties` 时才套用 release 签名，干净检出、CI 与 PR 构建拿不到也签不了发布包。

```sh
# 本机发布构建（需要 platforms/android/keystore.properties，该文件不入库）
cd platforms/android
./gradlew :app:assembleRelease

# 校验成品证书指纹是否与上表一致
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

## 权限说明

| 权限 | 用途 | 说明 |
| --- | --- | --- |
| `BLUETOOTH_SCAN`（`neverForLocation`） | 按服务 UUID 扫描圆屏 | 明确声明不用扫描结果推断位置 |
| `BLUETOOTH_CONNECT` | 连接、发现服务、读写特征 | API 31+ |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | 旧系统上的蓝牙访问 | 仅 `maxSdkVersion="30"` |
| `ACCESS_FINE_LOCATION` | 导航定位 | **与扫描权限互相独立**；扫描用 `neverForLocation` 并不取消它 |
| `INTERNET` | 访问自建 HTTPS 网关 | |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | 高德定位 SDK 的 Wi‑Fi / 基站网络定位 | 仅在启用高德定位时需要 |
| `ACCESS_LOCATION_EXTRA_COMMANDS` | 高德定位 SDK 的 GNSS 控制命令 | |

手机只在意模糊定位时，界面会明确提示导航需要精确定位，而不会把模糊定位当成可用位置。
后台定位属于后续阶段，需要单独说明与验证，不作为默认前提。

## 定位来源（系统定位 / 高德定位 SDK）

App 有两种定位来源，**都是真实定位，也都可以配置为不使用**；不会因为失败就退回假数据。

| 来源 | 何时使用 | 说明 |
| --- | --- | --- |
| 系统 `LocationManager` | 默认。未配置高德 Key 或未同意隐私政策时 | 订阅所有可用 provider（含 `fused`/`passive`），不假设有 Google Play 服务；国内无 GMS 的手机同样可用 |
| 高德定位 SDK（`com.amap.api:location`） | 在「设置」里勾选同意 + 填入 Android Key 后 | 自带 Wi‑Fi / 基站网络定位，室内也能出位置；属于第三方服务，位置会发送给高德 |

真机实测（小米 14 Pro / HyperOS）系统定位在这台手机上**拿不到任何回调**：
`perm fine=true coarse=true`、`appOps allow`、`locationEnabled=true`、
四个 provider 全部订阅成功，但 20 秒内 0 回调、`lastKnown` 全空，重订阅后仍为空。
因此补上高德定位这条路径；`LocationSourceRouter` 在选择来源时会打印 `source: …`，
高德失败时回退系统定位并打印原因，绝不伪造位置。

### 坐标系边界（重要）

* 手机交给共享核心、以及交给网关的一律是 **WGS84**；
* 高德定位返回 **GCJ-02**（SDK 通过 `AMapLocation.getCoordType()` 自报），
  由 `AmapFixMapper` 决定是否需要转换，转换本体在 `moto_jni.cpp` 里用上游
  `shared/coordinates` 的**正算函数迭代求逆**，不存在第二套坐标数学；
* 网关仍然是**唯一**把 WGS84 转成 GCJ-02 给高德 Web 服务的地方；
* 坐标类型缺失或未知时**拒绝该定位**，而不是赌一个方向转换。

### 高德 Android Key 的申请与填写

1. 到高德开放平台新建 Key，平台选 **Android**，绑定：
   * 包名 `io.github.bloodbear111.motogps`
   * 签名 SHA1（debug / release 两张指纹见上一节；App 的「设置」页也会直接显示当前安装包的包名与 SHA1，可直接复制）
2. 在 App「设置 → 高德定位」里勾选同意隐私政策并粘贴 Key（32 位十六进制）。
3. 点「重新定位」或重新开始导航即生效，**不需要重新安装**。

网关用的是**Web 服务** Key，与这里的 Android Key 不是同一个产品；把 Web 服务 Key 填进来会得到
`USERKEY_PLAT_NOMATCH` / Key 校验失败，界面会把 SDK 的错误码原样显示出来（含 `35/36` 的提示）。

许可证：高德定位 SDK 是专有第三方 SDK，见 [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md)；
仓库不附带其二进制，由 Gradle 从 Maven 解析。未同意隐私政策时不会初始化该 SDK。

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
