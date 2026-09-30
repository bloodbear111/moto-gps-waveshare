#!/usr/bin/env bash
# MOTO GPS 网关安装脚本（Ubuntu 22.04 / 24.04，Debian 12 亦可）
#
# 在已经 git clone 好的仓库根目录执行：
#   sudo bash backend/deploy/aliyun/install.sh
#
# 它做这些事，且可以重复执行：
#   1. 装 Node.js 22 LTS（发行版自带的 node 在 Ubuntu 24.04 是 18，太旧）
#   2. 建系统用户 moto-gps 与目录 /opt/moto-gps
#   3. 把 backend/ 拷到 /opt/moto-gps/backend 并 npm ci --omit=dev
#   4. 生成 /etc/moto-gps/gateway.env 模板（已存在则不动，里面放着你的 Key）
#   5. 装 systemd 单元与 nginx 站点
#   6. 起服务并打印自检命令
#
# 它不会替你填高德 Key，也不会申请证书——那两步见 README.md。
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "请用 sudo 运行" >&2
  exit 1
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
deploy_dir="${repository_root}/backend/deploy/aliyun"
service_user=moto-gps
install_root=/opt/moto-gps

echo "==> 安装 Node.js 22 LTS"
if ! command -v node >/dev/null 2>&1 || [[ "$(node -p 'process.versions.node.split(".")[0]')" -lt 20 ]]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
node --version

echo "==> 安装 nginx"
apt-get install -y nginx

echo "==> 建立用户与目录"
id -u "${service_user}" >/dev/null 2>&1 || useradd --system --home "${install_root}" --shell /usr/sbin/nologin "${service_user}"
install -d -o "${service_user}" -g "${service_user}" "${install_root}"

echo "==> 拷贝后端代码"
install -d -o "${service_user}" -g "${service_user}" "${install_root}/backend"
cp -a "${repository_root}/backend/." "${install_root}/backend/"
rm -rf "${install_root}/backend/node_modules" "${install_root}/backend/cloudflare/node_modules"
chown -R "${service_user}:${service_user}" "${install_root}"

echo "==> 安装依赖（只装运行时依赖）"
sudo -u "${service_user}" bash -lc "cd ${install_root}/backend && npm ci --omit=dev"

echo "==> 配置环境变量文件"
install -d -m 0750 -o root -g "${service_user}" /etc/moto-gps
if [[ ! -f /etc/moto-gps/gateway.env ]]; then
  cat >/etc/moto-gps/gateway.env <<'ENV'
# 只有 root 可读；不要提交到任何仓库。
# 实时导航需要 amap + 一把“Web服务”平台的高德 Key，缺任何一个都会返回 503，
# 网关不会偷偷回退成演示路线。
MOTO_PROVIDER=amap
AMAP_WEB_SERVICE_KEY=
# 周边地图瓦片源：disabled 表示先只做导航；auto 需要服务器能访问 PMTiles 源。
MOTO_MAP_PMTILES_URL=disabled
# 只服务原生 App，网页跨域一律拒绝。
WEB_ORIGIN=
# 手机侧网关地址就是 https://<域名>/moto-gps/api ，末尾不要斜杠。
MOTO_BASE_PATH=/moto-gps/api
# 瓦片缓存（1 GiB 上限），目录由 systemd 的 StateDirectory 保证可写。
MOTO_MAP_CACHE_DIR=/var/lib/moto-gps/map-cache
ENV
  chmod 0600 /etc/moto-gps/gateway.env
  echo "已生成 /etc/moto-gps/gateway.env —— 请填入 AMAP_WEB_SERVICE_KEY"
else
  echo "/etc/moto-gps/gateway.env 已存在，保持不动"
fi

echo "==> 安装 systemd 单元"
install -m 0644 "${deploy_dir}/moto-gps-gateway.service" /etc/systemd/system/moto-gps-gateway.service
systemctl daemon-reload
systemctl enable moto-gps-gateway.service

echo "==> 安装 nginx 站点"
install -m 0644 "${deploy_dir}/nginx-moto-gps.conf" /etc/nginx/conf.d/moto-gps.conf
nginx -t
systemctl reload nginx

echo
echo "==> 之后的步骤"
echo "1) 填 Key：   sudo nano /etc/moto-gps/gateway.env   （AMAP_WEB_SERVICE_KEY=...）"
echo "2) 改域名：   sudo nano /etc/nginx/conf.d/moto-gps.conf （server_name ...）"
echo "3) 起服务：   sudo systemctl restart moto-gps-gateway"
echo "4) 办证书：   sudo apt install -y certbot python3-certbot-nginx && sudo certbot --nginx -d <你的域名>"
echo "5) 本机自检： curl -sS http://127.0.0.1:8787/healthz              # 直连网关"
echo "              curl -sS http://127.0.0.1/moto-gps/api/healthz     # 经 nginx 反代"
echo "6) 手机自检： https://<你的域名>/moto-gps/api/healthz"
