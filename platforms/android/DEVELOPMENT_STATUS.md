# 开发进度与验证记录

> 语言：中文。English summary is at the bottom.

本文区分四类状态，**不把没做过的事情写成已完成**：

| 标记 | 含义 |
| --- | --- |
| ✅ 已实现 | 代码已写好并纳入工程 |
| 🧪 自动测试通过 | 在本次开发环境中实际执行并通过，附命令与结果 |
| 📱 真机通过 | 需要真机 / 真实圆屏才算通过 |
| ⬜ 尚未验证 | 没有相应条件或还没做 |

---

## 阶段一：工程与蓝牙连接（本次交付）

### ✅ 已实现

**工程与工具链**

* `platforms/android/` Kotlin + Jetpack Compose 工程，与 `platforms/ios`、`platforms/esp32`、`platforms/web` 平级。
* Gradle Wrapper 已提交（`gradlew`、`gradlew.bat`、`gradle/wrapper/*`）。
* 版本目录 `gradle/libs.versions.toml` 锁定全部依赖版本。
* CI：`.github/workflows/android.yml`，从干净检出安装固定工具链、拉取子模块、跑单元测试 / lint / 打包。

**复用上游 C++ 核心（通过 NDK + CMake + JNI）**

`app/src/main/cpp/CMakeLists.txt` 直接 `add_subdirectory` 上游的
`shared/coordinates`、`shared/nav_core`、`shared/nav_app`、`shared/ble_protocol`，
把它们和新增的 JNI 适配层一起编进 `libmoto_mobile.so`。应用里没有第二套协议实现。

新增的 C++ 只有两个文件（属于安卓适配层）：

| 文件 | 作用 |
| --- | --- |
| `moto_jni.cpp` | Kotlin ↔ 共享 C++ 的编组：编解码器、重组器、NavApp |
| `moto_golden_selftest.cpp` | 用仓库黄金字节驱动共享编解码器做自检 |

`shared/protocol/fixtures/ble-navigation-v1.golden.txt` 在 CMake 配置阶段被内嵌成
生成头文件，端上自检因此**直接读取仓库夹具**，不存在手工复制的副本。

**Kotlin 侧**

| 模块 | 内容 |
| --- | --- |
| `protocol/` | `MotoProtocolCodec`（JNI 门面）、`BleHandshakeGate`、`BleSessionHeartbeatClock`、`BleWritePumpPolicy`、协议枚举镜像 |
| `ble/` | 按服务 UUID 扫描、权限拆分、GATT 串行队列、`MotoBleCentral` 会话 |
| `navigation/` | `MotoNavCore`（共享 `NavApp` 的 JNI 门面）与快照缓冲 |
| `gateway/` | 网关地址规范化（HTTP 客户端属于阶段四） |
| `ui/` | 三个 Compose 页面：连接、自检、设置 |

**连接与协议行为**

* 蓝牙可用性与 LE 支持检查、按服务 UUID 过滤扫描、显式选择设备。
* 连接后：发现服务 → 请求 MTU → 订阅 TX Notify（写 CCCD）→ 握手。
* 两步握手门：只有设备**第二次** `Ready` 且参数一致才进入协议就绪。
* 帧大小按 `min(ATT_MTU - 3, 设备上报值, 512)` 计算，不假设 512。
* GATT 操作全部走串行队列，一次只允许一个未完成操作。
* 写队列 FIFO 且有节流，同一逻辑消息的分片不会交织。
* 心跳使用**本会话已用时间**（锚定在会话开始），不是手机开机时长。
* 断连清空写队列、重置入站重组状态、重新握手并复用同一编码器的发送序号。
* 设备换 `session_id` 时清空去重表与分片并重新握手。
* `MapScene` 使用应用级 ACK：750 ms 未收到则用**相同序号与相同分片**重发，最多 2 次，之后置降级。
* `DeviceCommand` 按 `(session_id, command_id)` 去重，成功/重复分别回 ACK。
* 链路存活看门狗：连续超过 `max(5000ms, 心跳×3)` 没有 CRC 正确帧即置降级并请求补发快照。

### 🧪 自动测试通过

实际执行环境：Windows 11，JDK 17.0.20.1，Gradle 8.14.5，AGP 8.13.2，
Kotlin 2.2.20，compileSdk/targetSdk 36，minSdk 26，NDK 27.3.13750724，CMake 3.22.1。

```text
> ./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-build-cache
BUILD SUCCESSFUL
64 actionable tasks: 64 executed        # 无缓存，逐任务执行
  tests=37 failures=0 skipped=0
    gateway.GatewayConfigurationTest                 tests=9  failures=0
    protocol.BleHandshakeGateTest                    tests=14 failures=0
    protocol.BleSessionHeartbeatClockTest            tests=3  failures=0
    protocol.BleWritePumpPolicyTest                  tests=5  failures=0
    protocol.MotoProtocolEnumsTest                   tests=6  failures=0
  lint: No issues found.
```

该命令在**刚从 GitHub 克隆的检出**上执行：`clean` 会同时清空 CMake 原生构建目录，
`--no-build-cache` 保证没有任务命中缓存。CMake 为 `arm64-v8a`、`armeabi-v7a`、
`x86_64` 三个 ABI 重新编译了共享 C++ 核心（`coordinates`、`nav_core`、`nav_app`、
`ble_protocol`），APK 内确认存在 `lib/<abi>/libmoto_mobile.so`。

产物：

| 项目 | 值 |
| --- | --- |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 21.97 MiB |
| SHA-256 | `64275fa262ea095a815d71df0928457bbc9688624014b1df5e2b54a3c087a2ab` |
| 签名 | **debug 签名，仅供测试**，不可作为发布包 |

说明：同一个源码在**不同绝对路径**下构建时，debug APK 的 SHA-256 会不同
（NDK 会把构建路径写进调试信息）。上面是同一次干净构建的对应值。

注意：以上是**构建与单元测试**证据。它证明代码能编译、策略逻辑正确、
共享 C++ 能被安卓工具链编译，**不证明**任何真机行为。

### 📱 真机通过

**已记录机型**

| 项目 | 值 |
| --- | --- |
| 设备型号 | Xiaomi 14 Pro（认证型号 23116PN5BC） |
| 系统 | HyperOS 3.0.308.0.WNBCNXM.C11 |
| Android 版本 | 16（安全更新 2026-08-01） |
| 内核 | 6.1.138-android14-11-g0c3d559bcd85-ab14529422 |
| 基带 | MPSS.DE.5.0-CN-1cb5074db8 |
| 芯片 / 内存 | 第三代骁龙 8 / 16 GB |

**已完成：协议自检 10/10 通过（真机，2026-09-29）**

在 Xiaomi 14 Pro 上安装 `app-debug.apk`，进入「自检」页运行，10 项全部通过：

```text
PASS  crc16:123456789              CRC 参数 poly 0x1021 / init 0xFFFF
PASS  gatt:uuids                   service / RX / TX / CCCD 与 v1 一致
PASS  frame:connection             黄金连接帧的帧头与长度
PASS  payload:connection-fields    角色 / 状态 / 能力 / 会话 / 帧大小 / 心跳
PASS  encode:connection-frame      用解析值重编码 == 黄金 payload 与 185 字节单帧
PASS  roundtrip:navigation.payload NavigationSnapshot 解码→重编码逐字节一致
PASS  roundtrip:geometry.payload   RouteGeometry（含 ZigZag varint）往返一致
PASS  roundtrip:command.payload    DeviceCommand 往返一致
PASS  fragments:command20          20 字节 GATT value 下的两分片与黄金分片一致
PASS  reassembly:missing-start     缺 START 的续帧被拒（MissingStart）
```

**这一条为什么重要：** 这 10 项不是用 Kotlin 重写的校验，而是**在真机上加载
`libmoto_mobile.so`，把仓库的 `ble-navigation-v1.golden.txt` 喂给上游同一份
C++ 编解码器**。它同时证明了：

* JNI 库在 Android 16 / arm64 上能加载，`MotoProtocolCodec` 能分配与释放；
* 共享 C++ 核心（`shared/ble_protocol`）被安卓工具链正确编译且行为与夹具一致；
* 端上字符串（`东长安街` 等 UTF-8）经 JNI 往返没有被破坏；
* 分片与重组规则在端上实现与固件相同。

因此下列项目从「未验证」升级为**已通过**：

* 在真实安卓机型上安装并启动；
* JNI 黄金字节自检（原 `androidTest` 中的同源断言）；
* 共享 C++ 在 arm64 真机上的运行行为。

**仍未验证**（需要圆屏或更长的观察）：

* 扫描到真实圆屏、配对/加密、服务发现、TX 订阅；
* 完成两步握手并进入协议就绪；
* 断连 → 重连 → 重新握手 → 状态补发；
* 息屏 / 锁屏 / 任务切换 / 进程回收下的行为；
* 实际功耗与长时间骑行稳定性；
* 与固件实际协商出的 MTU 与帧大小。

`androidTest` 中仍需设备执行的剩余断言（枚举与 C++ 编译值一致性、
`NavCore` 生命周期与导航行为）**尚未运行**。

已计划的真机步骤（需要用户配合）：

1. ~~`adb install -r app-debug.apk`，确认启动不崩溃。~~ **已完成**
2. ~~运行协议自检，记录每一项结果。~~ **已完成（10/10）**
3. 圆屏开机，进入 App「连接」页扫描，确认能发现设备。
4. 连接后确认状态依次经过 发现服务 → 握手 → 协议就绪，并记录协商帧大小。
5. 关闭圆屏再开机，确认自动恢复流程与状态补发。
6. 记录固件提交与复现步骤。**公开日志前删除地址、轨迹和设备标识。**

### 仍需设备执行的检查

开发机（Windows，无安卓设备）无法运行 `androidTest`，因为这些用例要加载
`libmoto_mobile.so`。手机已到位，因此可以补跑：

| 项目 | 状态 | 如何补齐 |
| --- | --- | --- |
| JNI 黄金字节自检 | ✅ 已由 App「自检」页在真机覆盖（10/10） | — |
| Kotlin 枚举与 C++ 编译值一致性 | ⬜ 未运行 | `./gradlew :app:connectedDebugAndroidTest` |
| `NavCore` 的 JNI 生命周期与导航行为 | ⬜ 未运行 | 同上（`MotoNavCoreInstrumentedTest`） |
| 模拟器 UI 检查 | ⬜ 未运行 | 安装 system image 后可补；与真机检查分开记录 |

尚未运行的两项**用例已经写好并且是真实断言**，在跑之前不算通过。

---

## 与文档不一致或需要说明的地方

阅读源码时发现以下与文档存在差异或值得上游补充的点，**没有按猜测实现**：

1. **能力交集的具体规则未写进规范正文。** §3 只说“取能力交集”，
   而固件与 iOS 的实际做法是：设备能力必须覆盖手机的**必需集合**
   （navigation、route geometry、traffic、touch commands、command ACK），
   `MapScene` 是可选位。安卓端按实现行为对齐，已在测试中固定。
   （本次记录的黄金连接向量能力位是 `0x7F`，恰好**不含** MapScene 位。）

2. **§4.3 的重发语义容易误读。** “用同一序号和完全相同的分片重发”意味着必须
   **缓存原始分片**并原样重发，而不是重新编码一次。安卓端按缓存原分片实现。

3. **`ble_navigation_v1.md` §2 的 `max_frame_size` 下限是 13，**
   与实现中的 `kFrameOverhead + 1`（12 + 1）一致；测试里用
   `kFrameHeaderSize + kFrameCrcSize + 1` 交叉校验，避免有人只改一处。

4. **iOS 的 `GatewayConfiguration` 把非 HTTPS 归为 `invalidAddress`，**
   安卓端拆成独立的 `Insecure` 错误，以便界面给出“必须使用 HTTPS”的准确提示。
   接受/拒绝的输入集合与 iOS 保持一致（含拒绝 `.invalid` 占位域名、账号密码、
   查询串、片段与 `/healthz`、`/v1/...` 端点地址）。

5. **`ANDROID_AI_GUIDE.md` 说明仓库当前没有安卓 App**，与本次新增的
   `platforms/android/` 一致；该指南本身就是需求来源。

---

## 阶段四：接入真实导航（进行中）

### ✅ 已实现：网关客户端（第一片）

只使用后端**现有**接口，没有虚构端点。字段、查询参数、信封与错误结构来自
`backend/src/gateway.js`、`backend/src/validation.js`、
`backend/src/amap-places.js`、`backend/src/amap-transformer.js`
与 `shared/protocol` 下的 schema。

| 文件 | 作用 |
| --- | --- |
| `gateway/MotoGatewayClient.kt` | `GET /healthz`、`GET /v1/places`、`POST /v1/route-options`、`POST /v1/routes` |
| `gateway/GatewayModels.kt` | 逐字段对照 schema 的 DTO；请求只含后端允许的键 |
| `gateway/GatewayTransport.kt` | 可替换的 HTTP 边界 + `HttpURLConnection` 实现（拒绝非 HTTPS） |
| `gateway/GatewayFailures.kt` | 类型化失败：未配置 / 不可达 / 服务错误 / 协议错误 / 过期响应 |
| `gateway/GatewayRouteMapper.kt` | `RouteBundle` → 共享 `NavCore` 的路线类型 |

本片落实的规则：

* **request_id 由共享 `NavCore` 分配**，客户端从不自己生成；响应的
  `request_id` 与发出值不一致时按 `Stale` 丢弃，**不让旧响应覆盖新路线**。
* **坐标系边界**：请求起终点标记 `WGS84`；路线几何要求 `GCJ-02`，
  否则直接拒绝而不是就地转换；`places[].location` 按网关输出视为 WGS84。
* **不虚构数据**：`speed_limit_kph` 恒为 0（schema 与高德 Route v2 都没有可信限速），
  未知的 maneuver/traffic 字符串降级为 `Unknown`；`/healthz` 的
  `road_speed_limits` 与 `traffic_light_countdown` 当前都是 false，客户端不据此宣称能力。
* **不截断**：超出 schema 上限（折线 8192 点、maneuver 512、traffic 2048、
  候选路线 3 条）的响应按协议错误拒绝，避免悄悄改变骑手看到的路线。
* 空配置返回 `NotConfigured`，**不指向任何默认主机**。

### 🧪 自动测试通过

```text
> ./gradlew :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL
TOTAL tests=63 failures=0
  gateway.GatewayConfigurationTest        tests=9  failures=0
  gateway.GatewayRouteMapperTest          tests=9  failures=0
  gateway.MotoGatewayClientTest           tests=17 failures=0
  protocol.BleHandshakeGateTest           tests=14 failures=0
  protocol.BleSessionHeartbeatClockTest   tests=3  failures=0
  protocol.BleWritePumpPolicyTest         tests=5  failures=0
  protocol.MotoProtocolEnumsTest          tests=6  failures=0
lint: No issues found.
```

新增用例覆盖：请求体**精确键集**（后端会拒绝未知字段）、可选字段省略、
路线与候选路线解析、过期 `request_id` 丢弃、非 GCJ-02 几何拒绝、超限折线拒绝、
速率限制与服务错误（含 `Retry-After`）、禁用网关错误、畸形响应、
传输失败、地点搜索的附近偏置与坐标、**能力位不得虚报限速与读秒**。

写测试时抓到的两个真实缺陷（已修）：`encodeDefaults=false` 会让必需的
`protocol_version`/`route_mode` 被省略；KDoc 里写 `*.schema.json` 会开启嵌套
注释导致文件无法编译。

### ⬜ 本阶段尚未完成

1. **定位源**：`FusedLocationProviderClient` 优先，无 Google Play 服务的机型回退
   `LocationManager`；保留精度、时间戳、速度与方向；陈旧/低精度定位交给 `NavCore`
   判定为不可用。
2. **导航会话**：把定位与路线命令接进 `NavCore`，下发 `NavigationSnapshot`、
   `RouteGeometry`（≤24 点窗口）与 `TrafficDeviation`；处理重算与路况刷新。
3. **界面**：目的地搜索、位置偏置、候选路线与时间/距离预览、开始/结束导航。
4. **演示模式**：明确标注为模拟；真实定位或网络失败时**不自动切换**到假数据。
5. **网关联调**：需要用户提供可公网访问的 HTTPS 网关地址；目前客户端只用
   schema 符合的样例与假传输验证，**没有对真实网关发过请求**。

阶段五（前台服务与锁屏导航）、阶段六（地图下载与可选音乐控制）、
阶段七（完整测试与交付）、阶段八（Fork 与 Release）见
[README.md](README.md) 与 [UPSTREAM_CONTRIBUTION.md](UPSTREAM_CONTRIBUTION.md)。

---

## 贡献者

| 角色 | 说明 |
| --- | --- |
| 上游项目、协议与共享 C++ 核心 | Maler X（[mx3353672833-debug](https://github.com/mx3353672833-debug)） |
| Android 移植实现 | [bloodbear111](https://github.com/bloodbear111) |

---

## English summary

**Stage 1 scope: project + BLE connectivity + protocol self-check.**

*Implemented*: a Kotlin + Compose project under `platforms/android/` that reuses
the upstream `shared/` C++ core (coordinates, NavCore, BLE v1 codec) through
NDK + CMake + JNI; BLE scanning by service UUID, MTU negotiation, TX
subscription, the two-step handshake, paced/serialised GATT writes, application
ACLs, session-scoped heartbeats, command de-duplication and disconnect teardown.

*Verified in this environment*: 37 JVM unit tests pass, `lintDebug` reports no
issues, and a cache-free clean checkout build produces a 21.97 MiB debug APK with
`lib/<abi>/libmoto_mobile.so` for arm64-v8a, armeabi-v7a and x86_64.

*Verified on a physical device*: a Xiaomi 14 Pro (HyperOS 3.0.308.0, Android 16)
installed the debug APK and passed all 10 protocol self-check vectors. That runs
the upstream C++ codec through JNI against
`shared/protocol/fixtures/ble-navigation-v1.golden.txt`, so the wire format,
CRC, fragmentation and reassembly behaviour are confirmed on real arm64 Android.

*Still not verified*: anything requiring the round display — scanning, pairing,
service discovery, the two-step handshake, reconnect, screen-off behaviour and
power use. The instrumented enum-parity and `NavCore` tests have not been
executed yet. The debug APK is test-only and is not a release build.
