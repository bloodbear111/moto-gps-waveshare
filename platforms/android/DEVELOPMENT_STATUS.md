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

### ✅ 已实现：定位源与核心↔网关桥接（第二片）

| 文件 | 作用 |
| --- | --- |
| `navigation/NavigationSource.kt` | 平台无关的定位接口 + `LocationQuality`（可用性/陈旧性判定与拒绝原因） |
| `navigation/AndroidLocationSource.kt` | `FusedLocationProviderClient` 优先，**无 Google Play 服务时回退平台 `LocationManager`** |
| `navigation/NavCommandExecutor.kt` | 把 `NavCommand` 变成网关请求，再把结果转回核心事件 |

本片落实的规则：

* **不假定有 Google Play 服务**：`GoogleApiAvailability` 返回 SUCCESS 才用融合定位，
  否则直接用平台 GNSS 提供者；两者都是正式路径，不是"降级凑合"。
* **只认精确定位**：只有 `ACCESS_FINE_LOCATION` 算可用于导航；只给了模糊定位时报
  `PermissionDenied`，不把模糊位置当位置用。
* **保留完整定位元数据**：精度、速度、方向、时间戳全部传给核心；时间戳用
  `elapsedRealtimeNanos`（单调时钟），避免系统调时被误判为"陈旧"或"来自未来"。
* **陈旧与低精度分开表达**：`Stale`（等一会就好）与 `PoorAccuracy` /
  `UnknownAccuracy`（需要换位置或换环境）是不同原因，界面因此能说清发生了什么。
  阈值与 `NavCoreConfig` 对齐（50 m / 5000 ms），并用测试锁住。
* **路况刷新复用原路线端点**（对齐 iOS `SharedNavigationRuntime`）：核心发出的
  traffic 命令**不带起终点**，若改用骑手当前位置重新规划会得到不同几何；而路况
  偏移量是从完整路线起点算的，套到新几何上会把拥堵画错路。因此刷新时使用当前
  路线的原始起终点，并校验 `route_id`、折线逐点一致与总长度容差。
* **失败按可重试性分类**：传输失败可重试；`NotConfigured`、协议错误、过期响应
  **不重试**，避免无意义地消耗网关配额。

### 🧪 自动测试通过

```text
> ./gradlew :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL
TOTAL tests=85 failures=0
  gateway.GatewayConfigurationTest        tests=9  failures=0
  gateway.GatewayRouteMapperTest          tests=9  failures=0
  gateway.MotoGatewayClientTest           tests=17 failures=0
  navigation.LocationQualityTest          tests=8  failures=0
  navigation.NavCommandExecutorTest       tests=14 failures=0
  protocol.BleHandshakeGateTest           tests=14 failures=0
  protocol.BleSessionHeartbeatClockTest   tests=3  failures=0
  protocol.BleWritePumpPolicyTest         tests=5  failures=0
  protocol.MotoProtocolEnumsTest          tests=6  failures=0
lint: No issues found.
```

新用例覆盖：路线请求使用命令自带的 WGS84 端点、重算带上 `previous_route_id`、
首次规划不带；**路况刷新必须使用原路线端点**（测试故意让命令携带 0,0 并断言请求体
仍是原始端点）、路线 id 不符拒绝、几何变化拒绝、未配置/不可达/服务错误的可重试性
分类、未知命令不阻塞核心；以及定位可用性、陈旧边界、NaN 精度、非法坐标的**区分**。

### ⬜ 本阶段尚未完成

1. **导航会话组装**：把 `NavigationSource` + `NavCommandExecutor` + `NavCore` +
   BLE 串成一个会话对象，处理重算、路况节流与状态补发。
2. **界面**：目的地搜索、位置偏置、候选路线与时间/距离预览、开始/结束导航。
3. **演示模式**：明确标注为模拟；真实定位或网络失败时**不自动切换**到假数据。
4. **网关联调**：需要用户提供可公网访问的 HTTPS 网关地址；目前所有验证都用
   schema 符合的样例与假传输，**没有对真实网关发过请求**。
5. **真机定位验证**：`AndroidLocationSource` 的两条分支都**没有在真机上跑过**，
   包括 Xiaomi 14 Pro 上是否有可用 Play 服务、息屏后定位是否继续。

阶段五（前台服务与锁屏导航）、阶段六（地图下载与可选音乐控制）、
阶段七（完整测试与交付）、阶段八（Fork 与 Release）见
[README.md](README.md) 与 [UPSTREAM_CONTRIBUTION.md](UPSTREAM_CONTRIBUTION.md)。

---

## 固件侧新增：车载 G 值仪页面

**需求**：在圆屏固件里加一个加速度计 G 值界面——同心圆 + 十字 + 小球，
加速度偏向哪边小球往哪边移动，没 G 值时回正，底部用数字显示各向 G 值。

### 设计决定

**没有改协议。** 这个页面由板上 QMI8658 自己驱动，不需要手机给任何数据，
所以它**不属于 BLE v1 的 `display_page` 枚举**（该枚举保持 4 个值不变）：

* 划进/划出该页是**设备本地**的页面切换；
* 切到该页时设备**不发送** `PageSelected`，因为 page 值 4 在线上不存在；
* 手机快照（最高 5 Hz）**不能把页面抢回地图**，否则骑行中一转弯就被拽走；
* 因为不依赖手机，**断开蓝牙也能用**。

### 改动文件

| 文件 | 改动 |
| --- | --- |
| `platforms/esp32/main/accel_gmeter.hpp` | 新增。纯 C++17 小球/读数逻辑，无 LVGL、无 ESP-IDF |
| `platforms/esp32/main/motion_heading_sensor.{h,cpp}` | 复用已有的 125 Hz QMI8658 采样，新增约 40 Hz 的加速度回调；**唯一的 IMU→屏幕轴映射点**，带注释便于上台架校正 |
| `shared/nav_ui/include/moto_nav_ui.h` | 新增 `MOTO_UI_PAGE_ACCEL`、`moto_gmeter_state_t`、两个 setter |
| `shared/nav_ui/src/moto_nav_ui.cpp` | 新增页面绘制、`page_available()` 统一可用性判定、手势跳过不可用页、点阵居中改为按页数计算 |
| `platforms/esp32/main/phone_nav_bridge.cpp` | 加速度采样入桥接（只存状态、由渲染任务写 LVGL）；本地页不被快照抢走；不向手机上报该页 |
| `platforms/esp32/main/app_main.cpp` | 把加速度回调接到桥接 |
| `tests/native/accel_gmeter_tests.cpp` + `tests/native/CMakeLists.txt` | 新增 11 项 host 测试并入 CI |

### 界面（美化部分）

360×360 设计空间内：顶部「加速度仪」标题；中央三层同心圆（外圈较亮，内圈渐隐）
加十字准线、中心轴点；小球带一层柔光晕；底部三行读数——
大号**合成 G 值**（`1.02`，用整数运算格式化）、`G RESULTANT` 说明、以及 `X / Y / Z` 三轴 G 值。

数值不变，**颜色随受力程度变化**：<0.35 g 冰蓝 → <0.75 g 白 → <1.2 g 琥珀
→ 更高红。阈值只影响配色，不影响读数。没有传感器时显示 `NO SENSOR` 而不是
一个停在中心的球（停在中心会被误读成"车是水平的"）。

### 验证状态（重要）

| 项目 | 状态 |
| --- | --- |
| 纯逻辑编译 + 链接（aarch64，项目的 `-Wall -Wextra -Wpedantic -fno-exceptions -fno-rtti`） | ✅ 已执行，0 警告 |
| 编译进安卓 JNI 库（三个 ABI） | ✅ `assembleDebug` 通过 |
| host 测试实际运行（`ctest`） | ⬜ **未运行**——本机没有 host C++ 工具链，也未装 ESP-IDF |
| **固件本体编译（ESP-IDF 5.5.5）** | ✅ **编译 + 链接通过**，见下节 |
| **刷机后在圆屏上实看** | ⬜ **未验证**——没有硬件 |

因此：新页面的 LVGL 代码与页面切换**已经过真实编译**（见下节），但
**没有上屏看过**，IMU 轴映射方向仍未验证。轴映射
（`kDisplayXSign` / `kDisplayYSign`）是最可能需要上台架调整的一处。

### 固件编译验证（已执行）

环境：ESP-IDF **v5.5.5**（`v5.5.5` 发布包）、LVGL **9.5.0**（子模块固定提交
`85aa60d1`）、Python 3.11.9、xtensa-esp-elf / esp-clang 工具链，目标 `esp32s3`。

```text
idf.py -C platforms/esp32 -B D:\mgbuild set-target esp32s3   # 配置 + 解析组件
idf.py -C platforms/esp32 -B D:\mgbuild build                # [2147/2147] 全部完成
Project build complete.
  moto_gps_esp32.bin   1,296,384 bytes   app 分区占用 15%（85% 空闲）
  bootloader.bin          22,272 bytes
  partition-table.bin      3,072 bytes
  idf.py exit=0
```

* 解析到的组件包含 `waveshare/esp32_s3_touch_amoled_1_75c`、`waveshare/qmi8658`、
  `espressif/esp_lvgl_adapter`、`lvgl/lvgl 9.5.0`（用仓库子模块路径）。
* **`加速度仪` / `NO SENSOR` / `G  RESULTANT` 与轴值格式串都能在 `moto_gps_esp32.elf`
  里找到**，说明新页面确实被编进了固件，而不是被条件编译跳过。
* 强制重编我改动的四个文件后，**告警全部来自 ESP-IDF / 第三方头文件**
  （`include_next`、`qmi8658.h` 的 `M_PI` 重定义、IDF 的匿名结构体等），
  `moto_nav_ui.cpp`、`motion_heading_sensor.cpp`、`phone_nav_bridge.cpp`、
  `app_main.cpp`、`accel_gmeter.hpp` **零告警**。

**编译中发现的仓库级问题（不是我引入的，值得上游知道）：**

1. **`platforms/esp32/dependencies.lock` 不可复现。** 即使按提交的锁重新解析，
   组件管理器仍会把 `espressif/esp_lcd_co5300` 从锁定的 **2.1.0 升到 2.2.0**，
   并把本地 LVGL 组件路径写成**绝对路径**。此次编译因此用的是 co5300 **2.2.0**，
   不是锁里的 2.1.0。我没有把改动后的锁提交，已还原为仓库版本。
   上游 CI 目前只跑 native / backend / Swift 测试，**不编译固件**，所以这个漂移
   不会被发现。
2. **对象文件路径接近上限。** 把构建目录放在仓库默认位置时 CMake 报
   "object file directory has 219 characters … maximum is 250"。构建改用
   `-B D:\mgbuild`（短路径）后消失。仓库路径较深时用户会撞到这个问题。

### 一个可以立刻做的验证

固件无法在本机编译，所以把 `accel_gmeter.hpp` 的**同一份头文件**编进了安卓
JNI 库，并在 App 自检里新增 6 项 G 值仪行为检查（静止回正、侧推偏移、
松手回正、极限值贴边不越界、振动死区、颜色阈值）。

**自检项数因此从 10 项变成 16 项。** 请重新跑一次自检并把结果告诉我——
这是目前唯一能在真机上执行这段固件逻辑的途径。

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
