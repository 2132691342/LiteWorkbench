#!/usr/bin/env bash
# 应用发布：在云主机上拉取新镜像、重启应用容器并做就绪自检
# 用法：bash deploy-app.sh <镜像:tag>，工作目录为 docker-compose.app.yaml 所在目录
set -euo pipefail

IMAGE="${1:?用法: deploy-app.sh <镜像:tag>}"
CONTAINER=liteworkbench
COMPOSE_FILE=docker-compose.app.yaml
PORT=24399

# podman-compose 1.0.6 的 up -d 只比对配置哈希不比对镜像 ID，先删容器才能保证新镜像生效
# podman 3.x rootless 反复重建同名容器会在 CNI-HOSTPORT-DNAT 留下指向已删容器 IP 的历史规则，
# 导致新容器端口被转发到不存在的地址，重建前先清理同端口残留
drop_stale_dnat() {
  local dropped=0 ln
  while true; do
    ln=$(iptables -t nat -L CNI-HOSTPORT-DNAT -n --line-numbers 2>/dev/null | grep "$PORT" | head -1 | awk '{print $1}')
    [ -z "$ln" ] && break
    iptables -t nat -D CNI-HOSTPORT-DNAT "$ln" 2>/dev/null || break
    dropped=$((dropped + 1))
    [ "$dropped" -gt 30 ] && break
  done
  echo "dropped stale dnat rules on :$PORT -> $dropped"
}

export LITEWORKBENCH_IMAGE="$IMAGE"
podman-compose -f "$COMPOSE_FILE" pull
podman rm -f "$CONTAINER" 2>/dev/null || true
drop_stale_dnat
podman-compose -f "$COMPOSE_FILE" up -d

sleep 5
echo "=== 端口映射 ==="
podman port "$CONTAINER" || true

ready=0
for _ in $(seq 1 36); do
  if curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 5
done

if [ "$ready" != "1" ]; then
  echo "=== 应用未就绪，打印容器与端口现场 ==="
  podman ps --format '{{.Names}} | {{.Status}} | {{.Ports}}'
  podman logs --tail 80 "$CONTAINER" || true
  ss -lntp 2>/dev/null | grep "$PORT" || echo "$PORT 未监听"
  exit 1
fi

echo "liteworkbench ready on :$PORT"
podman image prune -f
