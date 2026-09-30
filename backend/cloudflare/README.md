> **语言 / Language:** 中文 · [English](README.en.md)

# Cloudflare Workers + R2 部署

这是 [issue #2](https://github.com/mx3353672833-debug/moto-gps-waveshare/issues/2) 提出的新增部署方式。
原有 [Node.js + 自建服务器](../README.md) 方案继续保留，启动命令和默认行为不变。
两种部署共用搜索、路线、城市、地图转换和协议校验代码，iOS / ESP32 无需更换协议。

```text
iPhone → 你的 Worker HTTPS 地址 → 高德 Web 服务（搜索 / 路线 / 城市）
                              → Protomaps PMTiles（按需读取道路 / 建筑）
                              ↔ 私有 R2（转换后的地图瓦片 / 地图源元数据）
```

R2 在这里用于地图缓存，不会存高德 Key、用户搜索或路线响应，也不是 OTA 固件服务。
高德 Key 通过 Worker Secret 配置。此方案仍需自己的 Cloudflare 账号和高德服务权限，
仓库不提供公共导航接口。地图来源和 ODbL 署名沿用现有网关。

## 1. 本地验证

安装 Node.js 22+（推荐 24）和 npm。在仓库根目录运行：

```sh
npm ci --prefix backend
npm ci --prefix backend/cloudflare
npm test --prefix backend
npm test --prefix backend/cloudflare
```

Workers 测试在 Miniflare / workerd 中执行，使用本地 R2 和受控的上游响应，不需要账号或真实 Key。
覆盖路线与候选路线、搜索、城市、输入校验、限流、压缩 PMTiles 解码、R2 缓存、上游故障和定时刷新。
测试通过不代表国内网络或真实高德账号已经验收。

进入本目录，复制 `.dev.vars.example` 为 `.dev.vars`：

```sh
cd backend/cloudflare
cp .dev.vars.example .dev.vars
npm run dev
```

Windows 可在 PowerShell 中用 `Copy-Item .dev.vars.example .dev.vars`，其余 npm 命令相同。
然后打开 `http://localhost:8787/healthz`。示例是 **fixture 模式**：返回合成路线、空搜索结果，
`ready_for_live_navigation=false`，不会访问高德或在线地图。`.dev.vars` 只供本地使用，不会部署为远端配置。

## 2. 创建 R2 和配置 Worker

以下命令均在 `backend/cloudflare` 执行：

```sh
npx wrangler login
npx wrangler r2 bucket create moto-gps-map-cache
```

如桶名已被自己使用，选择其他名称并同步修改 `wrangler.jsonc` 的 `bucket_name`。
保持桶为私有，不需要公开访问域名。为桶添加生命周期规则：**只对 `tiles/` 前缀，30 天后删除**。
可在 Cloudflare 控制台的 R2 桶 Settings → Object lifecycle rules 配置。
缓存丢失时会重新取源；`metadata/` 保存最近可用地图源，不适用这条过期规则。
R2 不沿用 Node 方案的 1 GiB 磁盘 LRU，总存储量由访问范围和生命周期共同决定。

编辑 `wrangler.jsonc` 的 `vars`：

| 配置 | 用途 |
| --- | --- |
| `MOTO_PROVIDER` | 正式导航设为 `amap`；默认 `disabled`；`fixture` 仅用于演示测试 |
| `MOTO_MAP_PMTILES_URL` | `auto` 自动选择已完成的 Protomaps 构建；也可指定可信的 HTTPS `.pmtiles` 地址或 `disabled` |
| `WEB_ORIGIN` | 允许的网页 Origin，例如 `https://nav.example.com`；只用原生 App 可设空字符串 |
| `MOTO_BASE_PATH` | 默认空字符串，接口在 `/v1/...`；如设 `/moto-gps/api`，接口就在 `/moto-gps/api/v1/...`，不要加末尾 `/` |

`disabled` / `fixture` 模式强制关闭在线地图，避免测试意外访问外网。
地图开启时必须绑定 `MAP_CACHE`。私有 PMTiles 文件托管不是本版功能；自定义源必须支持 HTTPS Range 请求。

添加自己的高德 **Web 服务 Key**：

```sh
npx wrangler secret put AMAP_WEB_SERVICE_KEY
```

在提示中粘贴 Key，不要写入 `wrangler.jsonc`、Git 或 Issue。启用 `amap` 却没有 Key 时返回 503，
不会偷偷回退到演示路线。高德账号需具备 POI、驾车路线、行政区划权限和配额；实际请求失败时检查
高德控制台的限制及返回错误码。若配置了出口 IP 白名单，必须另行验证 Workers 的出口是否符合要求。

### 保持仓库默认值，用命令行覆盖部署变量（fork / CI 推荐）

`wrangler.jsonc` 的默认值是 `MOTO_PROVIDER=disabled`，而 `checks/runtime.mjs`
会断言这些默认值，并且它是用 `JSON.parse` 读这个文件的（**不支持
`//` 注释，尽管后缀是 `.jsonc`）。因此直接把它改成生产值并提交会让
CI 变红。实际部署时用 `--var` 在命令行上覆盖即可：

```sh
npx wrangler deploy \
  --var MOTO_PROVIDER:amap \
  --var MOTO_MAP_PMTILES_URL:auto \
  --var "WEB_ORIGIN:" \
  --var MOTO_BASE_PATH:/moto-gps/api
```

- `WEB_ORIGIN:` 留空表示只服务原生 App（浏览器跨域请求会被拒）。PowerShell 下请加引号，否则空值可能被丢掉。
- `MOTO_BASE_PATH=/moto-gps/api` 时，接口在 `/moto-gps/api/v1/...`，**不要加末尾斜杠**；手机侧网关地址就填 `https://<你的 Worker 域名>/moto-gps/api`。
- 密钥始终只走 `npx wrangler secret put AMAP_WEB_SERVICE_KEY`，不要进 `wrangler.jsonc`、仓库或 Issue。

### 本仓库实测记录（2026-10-01）

- 本机 Node 22.14 + wrangler 4.142：`npm test`（空白检出）**12/12 通过**。
  注意：`wrangler dev` 会把 R2 本地状态留在 `.wrangler/`，带着这份状态重跑图瓦相关检查会失败；请在干净检出上跑。
- 以上 `--var` 参数 + 真实高德 **Web 服务** Key 本地实测：
  - `GET /moto-gps/api/healthz` → 200，`ready_for_live_navigation=true`、`provider=amap`、`surrounding_map=true`、`storage=r2`；
  - `GET /moto-gps/api/v1/places?keywords=济南站` → 200，真实 POI（WGS84）；
  - `POST /moto-gps/api/v1/route-options`（北京 WGS84）→ 200，真实路线 `total_distance_m=3183`，几何坐标系 `GCJ-02`。
- 高德返回 `USERKEY_PLAT_NOMATCH`（10009）时，说明用的不是 **Web 服务** 平台的 Key（例如误用了 Android 平台 Key），服务端调用会全部被拒。


## 3. 部署及连接 App

```sh
npm run build
npm run deploy
```

`build` 仅打包检查，不上传。`deploy` 才会部署到自己的 Cloudflare 账号。
默认使用命令返回的 `https://moto-gps-gateway.<你的子域>.workers.dev/`。
也可按 [Cloudflare 自定义域名文档](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)
绑定自己的域名；自定义域名不会自动提供中国大陆网络质量保证。

在 App 的“网关设置”中填写部署后的 HTTPS 根地址并保存，即时生效。
也可以在源码构建时通过 `platforms/ios/project.yml` 的 `MOTOGPSGatewayBaseURL` 设置默认值。
没有 Mac 可使用 [IPA 安装方式](../../docs/IOS_SIDELOAD.md)。默认路径示例：

```text
https://moto-gps-gateway.YOUR-SUBDOMAIN.workers.dev/
```

若设置了 `MOTO_BASE_PATH=/moto-gps/api`，App 地址相应变为：

```text
https://nav.example.com/moto-gps/api/
```

同一个前缀要同时用于下面的验收请求。ESP32 固件不需要修改。
原服务器地址继续可用；回退时在 App 的“网关设置”中保存原地址。

## 4. 实际验收

下面占位地址必须换成自己的部署地址。Windows 使用 `curl.exe` 可避免 PowerShell 别名差异。

```sh
curl --fail-with-body https://YOUR-WORKER/healthz
curl --fail-with-body 'https://YOUR-WORKER/v1/places?keywords=%E6%B5%8E%E5%8D%97%E8%A5%BF%E7%AB%99'
curl --fail-with-body 'https://YOUR-WORKER/v1/map/cities?keywords=%E6%B5%8E%E5%8D%97'
curl --fail-with-body -H 'Content-Type: application/json' --data-binary @../fixtures/route-request-v1.json https://YOUR-WORKER/v1/routes
curl --fail-with-body -H 'Content-Type: application/json' --data-binary @../fixtures/route-request-v1.json https://YOUR-WORKER/v1/route-options
curl --fail-with-body https://YOUR-WORKER/v1/map/tiles/15/27044/12791
```

确认 `provider=amap`、真实搜索和路线成功，未缓存瓦片返回 `roads` / `buildings`，
R2 的 `tiles/` 出现对应 JSON。再次请求同一瓦片应能从缓存读取。
`/healthz` 只报告已配置的能力，不主动请求高德或地图源；它不证明上游在线。
限速和红绿灯倒计时仍报告未支持，不因更换部署方式而增加。

在实际使用地区用手机蜂窝网络测试搜索、路线、偏航重算和地图下载，记录超时和延迟。
Issue 中的 OTA 下载速度不能替代这些验证；本方案没有内置 IP 优选或测速承诺。
大瓦片解码可能触及 Workers CPU / 内存 / 子请求限制，应根据实际负载确认套餐配额与成本。

## 运行方式和限制

- 路线、搜索、城市和瓦片保留现有接口与 JSON 格式。Workers 使用官方 Node HTTP 适配器运行同一网关。
- 地图仍按需从 HTTPS PMTiles 源读取，不会把全球地图完整复制到 R2。
- 每个请求单独管理 PMTiles I/O，避免 Workers 跨请求共享未完成的读写。R2 缓存和地图源元数据跨请求保留。
- 自动地图源每日刷新；已缓存瓦片先返回，过期数据通过 `waitUntil` 后台更新。Cron 为 UTC 03:17。
  没有缓存且上游不可用时返回 503，不生成假道路；已有缓存可继续使用。
- R2 读写失败不会暴露密钥。写入失败时本次已取得的真实地图仍可返回，但后续请求可能需要重新下载。
- Cloudflare 限流按 IP 分为路线 30、搜索 60、城市 30、瓦片 600 次/分钟。
  这是边缘限流而非全账号严格配额；不同网关应使用独立的 `namespace_id`，避免互相占用额度。
  多人共用一个公网 IP 会共享限额。CORS 和限流都不是用户认证，公开服务需按自己的访问范围配置保护。
- 没有自动切换线上域名，也不修改 App 默认网关。仓库只新增可自行部署的代码和说明。

实现依据：[Workers Node HTTP](https://developers.cloudflare.com/workers/runtime-apis/nodejs/http/)、
[R2 Workers API](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/)、
[Workers 限流](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)。
