#!/usr/bin/env bash
# 交互式写入高德 Key 并自检（在 ECS 上执行）：
#   sudo bash backend/deploy/aliyun/configure.sh
#
# 为什么要有这个脚本：Key 不能进仓库、不能进 systemd 单元、也不该出现在
# shell 历史里（用 argv 传参会留在 history）。这里用隐藏输入读一遍，
# 只写进 /etc/moto-gps/gateway.env（0600），然后重启服务并跑验收。
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "请用 sudo 运行" >&2
  exit 1
fi

env_file=/etc/moto-gps/gateway.env
install -d -m 0750 -o root -g moto-gps /etc/moto-gps

if [[ ! -f "${env_file}" ]]; then
  echo "未找到 ${env_file}，先生成一份默认配置"
  cat >"${env_file}" <<'ENV'
MOTO_PROVIDER=amap
AMAP_WEB_SERVICE_KEY=
MOTO_MAP_PMTILES_URL=disabled
WEB_ORIGIN=
MOTO_BASE_PATH=/moto-gps/api
MOTO_MAP_CACHE_DIR=/var/lib/moto-gps/map-cache
ENV
fi

read -r -s -p "粘贴高德【Web服务】平台的 Key（输入不回显）: " amap_key
echo
if [[ -z "${amap_key}" ]]; then
  echo "Key 为空，已放弃" >&2
  exit 1
fi
if [[ ! "${amap_key}" =~ ^[0-9a-fA-F]{32}$ ]]; then
  echo "提示：这不是常见的 32 位十六进制 Key，仍会写入，但请核对是不是贴错了" >&2
fi

if grep -q '^AMAP_WEB_SERVICE_KEY=' "${env_file}"; then
  sed -i "s|^AMAP_WEB_SERVICE_KEY=.*|AMAP_WEB_SERVICE_KEY=${amap_key}|" "${env_file}"
else
  echo "AMAP_WEB_SERVICE_KEY=${amap_key}" >>"${env_file}"
fi
# provider 必须是 amap，否则写了 Key 也不会用。
sed -i 's|^MOTO_PROVIDER=.*|MOTO_PROVIDER=amap|' "${env_file}"
chown root:moto-gps "${env_file}"
chmod 0600 "${env_file}"
echo "已写入 ${env_file}（0600，root:moto-gps）"

systemctl restart moto-gps-gateway
sleep 2

echo
echo "=== 1) 直连网关（绕过 nginx）==="
curl -sS --max-time 10 http://127.0.0.1:8787/healthz || echo "（直连失败，先看 journalctl -u moto-gps-gateway）"
echo
echo "=== 2) 真实 POI 搜索 ==="
curl -sS --max-time 15 'http://127.0.0.1:8787/v1/places?keywords=%E6%B5%8E%E5%8D%97%E7%AB%99' | head -c 400 || true
echo
echo "=== 3) 经 nginx（域名与证书配好之后再看）==="
curl -sS --max-time 10 -k https://www.bloodbear.xin/moto-gps/api/healthz || echo "（还连不上属正常：先加 DNS、放行 443、跑 certbot）"
echo
echo "判定标准：healthz 里 provider=amap 且 ready_for_live_navigation=true，第 2 步能列出济南站。"
