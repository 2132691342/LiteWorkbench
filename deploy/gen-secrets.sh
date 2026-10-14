#!/usr/bin/env sh
# 生成 .env.infra：字段清单以 .env.infra.example 为唯一来源，值随机填充，不打印到终端。
# 用法：./gen-secrets.sh [目标文件]，默认同目录 .env.infra；已存在则拒绝覆盖。
set -eu

HERE=$(dirname "$0")
TARGET="${1:-$HERE/.env.infra}"
EXAMPLE="$HERE/.env.infra.example"

if [ -e "$TARGET" ]; then
  echo "已存在 $TARGET，拒绝覆盖。如需重建请先移走旧文件（重建会与运行中的中间件口令不一致）。" >&2
  exit 1
fi
if [ ! -f "$EXAMPLE" ]; then
  echo "缺少字段清单 $EXAMPLE，无法生成。" >&2
  exit 1
fi
if [ "$TARGET" = "$EXAMPLE" ]; then
  echo "目标文件不能是字段清单本身。" >&2
  exit 1
fi

# 24 字节随机 hex：规避 compose 展开会吃掉的字符（$ ` " \）
rand() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

# 逐行照抄示例文件，只把等号右边换成随机值；固定值与留空字段沿用示例里的值
# 用法：value_of <字段名> <示例中的值>
value_of() {
  case "$1" in
    LITEWORKBENCH_STORAGE_ACCESS_KEY) printf 'minio%s' "$(rand | cut -c1-12)" ;;
    LITEWORKBENCH_SECURITY_SECRET)    printf '%s%s' "$(rand)" "$(rand)" ;;
    LITEWORKBENCH_MQ_USERNAME|LITEWORKBENCH_ADMIN_PASSWORD|LITEWORKBENCH_BIND_ADDR) printf '%s' "$2" ;;
    *) printf '%s' "$(rand)" ;;
  esac
}

umask 077
while IFS= read -r line; do
  case "$line" in
    ''|'#'*) printf '%s\n' "$line" ;;
    *=*) printf '%s=%s\n' "${line%%=*}" "$(value_of "${line%%=*}" "${line#*=}")" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < "$EXAMPLE" > "$TARGET"

echo "已生成 $TARGET（权限 600）。下一步："
echo "  中间件首次部署：set -a && . $TARGET && set +a && podman-compose -f docker-compose.yaml up -d"
echo "  应用发布：      bash deploy-app.sh <镜像:tag>    # 脚本自动 source 本文件"
