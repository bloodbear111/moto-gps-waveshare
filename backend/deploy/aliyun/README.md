# 阿里云 ECS 部署（国内直连方案）

Cloudflare Workers 的 `*.workers.dev` 在国内会被 DNS 污染/阻断（本机实测：解析到
`108.160.172.200` 与 `2a03:2880:f127:283:face:b00c:0:25de`，443 连不上，而同一时刻
普通网站正常）。因此国内使用请走这套：**一台阿里云 ECS + nginx + systemd**。

## 1. 镜像与实例怎么选

**推荐：Ubuntu 24.04 LTS 64 位（x86_64）。** 备选：Ubuntu 22.04 LTS、Debian 12。

| 选项 | 建议 | 原因 |
| --- | --- | --- |
| 镜像 | **Ubuntu 24.04 LTS 64位** | 长期支持、文档最多；注意它自带 node 是 18（太旧），脚本会用 NodeSource 装 Node 22 LTS |
| 架构 | **x86_64** | 最省事。ARM（aarch64）也能跑（Node 有官方 arm64 包），但遇到第三方包时 x86 更少踩坑 |
| 规格 | **1 核 2 GB**（1 核 1 GB 也够） | 网关常驻内存约 80–150 MB；只有地图瓦片缓存会占磁盘 |
| 磁盘 | 40 GB 高效云盘起步 | 瓦片缓存默认上限 1 GiB |
| 带宽 | 按量或 1–5 Mbps | 路线/POI 响应只有几 KB；只有在线地图瓦片吃带宽 |
| 安全组 | **放行 80、443；不要放行 8787** | 网关代码里写死只监听 `127.0.0.1:8787`，只允许同机 nginx 访问 |

**不要选**：CentOS 7（已 EOL）、Windows Server（多花钱、没必要）、Alibaba Cloud Linux
（能用，但本文的脚本按 Debian/Ubuntu 的 apt 写）。

## 2. 先想清楚域名与备案

- 用**域名**访问国内 ECS 的 80/443，需要 **ICP 备案**（阿里云控制台有备案入口，通常 1–2 周）。
  没备案时代理商会拦截该域名的 80/443 访问。
- 不想等备案：可以把它放在**阿里云香港**区域（同样用 Ubuntu 24.04 镜像），不需要备案，
  国内访问一般可用；本文的步骤完全一样，只是区域不同。
- 也**不要**用公网 IP 直接跑 HTTPS：证书签不下来（Let's Encrypt 不给裸 IP 签发常规证书），
  用 HTTP 的话安卓默认禁止明文流量，还要额外改 App 配置。域名 + 证书是正路。

## 3. 安装（在 ECS 上执行）

```sh
# 1) 拉代码（用你的 fork 分支）
sudo apt-get update && sudo apt-get install -y git
git clone --depth 1 -b android/community-port-stage1 \
  https://github.com/bloodbear111/moto-gps-waveshare.git
cd moto-gps-waveshare

# 2) 安装（可重复执行；会装 Node 22、nginx、systemd 单元）
sudo bash backend/deploy/aliyun/install.sh

# 3) 填高德 Key（必须是“Web服务”平台的 Key）与你的域名
sudo nano /etc/moto-gps/gateway.env     # AMAP_WEB_SERVICE_KEY=...
sudo nano /etc/nginx/conf.d/moto-gps.conf   # server_name 换成你的域名
sudo systemctl restart moto-gps-gateway
sudo nginx -t && sudo systemctl reload nginx

# 4) 申请证书（会自动补 443 配置并续期）
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 你的域名
```

## 4. 验收（按顺序，别跳）

```sh
# 4.1 网关本身（绕过 nginx）
curl -sS http://127.0.0.1:8787/healthz
#     期望 provider=amap、ready_for_live_navigation=true

# 4.2 经 nginx 反代（前缀必须是 /moto-gps/api）
curl -sS http://127.0.0.1/moto-gps/api/healthz

# 4.3 真实 POI（会打高德，消耗配额）
curl -sS 'http://127.0.0.1/moto-gps/api/v1/places?keywords=%E6%B5%8E%E5%8D%97%E7%AB%99'

# 4.4 真实路线（POST，WGS84 输入）
curl -sS -X POST -H 'content-type: application/json' \
  --data-binary @backend/fixtures/route-request-v1.json \
  http://127.0.0.1/moto-gps/api/v1/route-options
#     期望 total_distance_m>0，polyline 坐标是 GCJ-02

# 4.5 从手机（关掉 WiFi 用流量、再开 WiFi 各一次）
#     https://你的域名/moto-gps/api/healthz
```

手机侧网关地址填：`https://你的域名/moto-gps/api`（**末尾不要斜杠**）。

## 5. 排错

| 现象 | 先看什么 |
| --- | --- |
| `/healthz` 里 `ready_for_live_navigation=false` | `/etc/moto-gps/gateway.env` 里 `MOTO_PROVIDER` 是不是 `amap`、Key 是不是空的 |
| 一切 503 且 `AMAP_*` | 高德返回的错误码：`USERKEY_PLAT_NOMATCH`(10009) 说明 Key 不是“Web服务”平台；`10003` 是当日配额用尽；`10005` 是 IP 白名单 |
| 404 | 反代前缀写错：`location /moto-gps/api/` 与 `proxy_pass http://127.0.0.1:8787/;` 末尾那个斜杠都必须有 |
| 手机连不上但服务器本机正常 | 安全组是否放行 443；域名是否已备案/解析到这台机器 |
| 限流把所有请求都拒了 | 反代必须覆盖 `X-Real-IP`（本仓库的 nginx 配置已经做了），否则所有请求会被算成同一个来源 |

## 6. 维护

```sh
sudo systemctl status moto-gps-gateway
sudo journalctl -u moto-gps-gateway -f     # 日志（注意：查询串里可能有搜索词，按需做轮转与隐私处理）
sudo systemctl restart moto-gps-gateway    # 改完 gateway.env 要重启
```

升级代码：`git pull` 后重新执行 `install.sh`（它会重新拷代码并 `npm ci --omit=dev`），再
`systemctl restart moto-gps-gateway`。

> 本文的 nginx/systemd 配置都是标准写法，但没有在本机（Windows）跑过；脚本与配置的实际执行
> 情况需要在这台 ECS 上确认，把输出贴回来我按报错改。
