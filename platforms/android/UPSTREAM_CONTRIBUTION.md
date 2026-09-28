# 可回馈上游的改动清单

上游的 [ANDROID_AI_GUIDE.md](../../docs/ANDROID_AI_GUIDE.md) 明确表示欢迎安卓移植，
也说明当前仓库还没有可安装的安卓 App。下面按“好合并 → 需要讨论”排序，
列出本移植中适合送回上游的部分。

## 一、可以直接提 PR 的部分

这些改动不改变任何现有平台的运行时行为：

1. **`shared/ble_protocol` 的 Android 编译验证**
   现有 CMake 已经能在宿主机上构建 `ble_protocol`，但 CI 只在 Linux 上跑。
   增加一个 Android NDK 交叉编译目标可以证明共享 C++ 在 `__ANDROID__` 下同样成立
   （本移植已验证：`-Wall -Wextra -fno-exceptions -fno-rtti` 通过）。

2. **黄金向量的机器可读投影**
   本移植让 CMake 在配置阶段把
   `shared/protocol/fixtures/ble-navigation-v1.golden.txt` 内嵌进一个生成头文件，
   供端上自检使用。这个做法可以直接搬到上游的网页/固件测试里，避免任何一方
   手工复制字节。

3. **协议文档的两处澄清**（不改字节）
   * §4.3 的“重发必须使用相同序号与完全相同的分片”在实现中容易被理解成
     “重新编码一次”。建议补一句：重发应缓存并复用原始分片。
   * §3 的第 5 步提到手机要核对“版本、能力、帧大小和心跳间隔”，但没有说
     是否允许**子集**能力。固件行为是要求设备能力覆盖手机的必需集合，
     建议把这条写进规范正文。

## 二、需要上游先决定的部分

4. **共享的“手机侧会话策略”是否值得抽出**
   `BLEHandshakeGate`、`BLESessionHeartbeatClock`、`BLEWritePumpPolicy` 目前是
   Swift；本移植用 Kotlin 按同一语义重写，并用同样的用例验证。
   如果把这三者挪进 `shared/` 的纯逻辑层（不依赖平台 API），iOS 与 Android
   就能真正共用一份实现，而不是靠测试保证一致。

5. **是否接受一个 `platforms/android/` 目录**
   本移植目前放在自己的 Fork 的 `platforms/android/`，与
   `platforms/ios`、`platforms/esp32`、`platforms/web` 平级，
   沿用仓库既有的分层方式。如果上游更希望安卓版独立成库，
   共享层需要先发布成可引用的形式。

6. **CI 的安卓任务**
   本移植新增 `.github/workflows/android.yml`：从干净检出安装固定工具链、
   拉取子模块、运行 Gradle 构建、单元测试与 lint。
   如果上游接受，建议只跑 `assembleDebug` + `testDebugUnitTest` + `lintDebug`
   （不需要真机，也不会接触任何签名材料）。

## 三、明确不做的部分

* 不修改圆屏协议、UUID、字段顺序、单位或枚举数值。
* 不要求用户刷写“安卓专用固件”。
* 不把仓库许可证改成 MIT/Apache，也不移除非商业限制。
* 不把高德 Web 服务 Key 放进客户端。

## 四、贡献者

| 角色 | 说明 |
| --- | --- |
| 上游项目与协议 | Maler X（[mx3353672833-debug](https://github.com/mx3353672833-debug)） |
| Android 移植 | [bloodbear111](https://github.com/bloodbear111)（基于上游 `ANDROID_AI_GUIDE.md` 的公开提示词开发） |

如果你参与了这个移植，欢迎在 PR 里把自己的名字补进上表。
