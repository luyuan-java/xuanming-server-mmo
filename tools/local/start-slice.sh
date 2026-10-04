#!/usr/bin/env bash
# 本地拉起 Java 版竖切（local profile：Dubbo 直连，不需要 Nacos）。在仓库根目录执行：
#   export XM_MYSQL_PASSWORD=... XM_GATE_TOKEN_SECRET=... XM_LOGIN_DEV_PASSWORD=... XM_NODE_LINK_SECRET=... XM_DUBBO_SECRET=...
#   tools/local/start-slice.sh
# XM_NODE_LINK_SECRET 是 gate → scene 节点链路握手密钥，xm-gate 与 xm-scene 读同一个值（本脚本把同一环境传给两者）。
# XM_DUBBO_SECRET 是 Dubbo 调用方鉴权密钥，xm-scene-manager / xm-login / xm-gate / xm-gateway 读同一个值。
# 前置：MySQL 127.0.0.1:3306、Redis 127.0.0.1:6379、Kafka 127.0.0.1:9092（资产流水，xm-scene 生产、xm-data 消费）已就绪；已执行 ./mvnw -DskipTests install；
# 存量库已按 docs/design/db-migrations.md 迁移到最新结构（M2 起 player 表多了 owner_released / owner_lease_until）。
# 进程按依赖顺序启动，每个都等端口就绪再起下一个；日志在 run/logs/，PID 在 run/pids/。
set -euo pipefail

cd "$(dirname "$0")/../.."
: "${XM_MYSQL_PASSWORD:?需要环境变量 XM_MYSQL_PASSWORD}"
: "${XM_GATE_TOKEN_SECRET:?需要环境变量 XM_GATE_TOKEN_SECRET}"
: "${XM_LOGIN_DEV_PASSWORD:?需要环境变量 XM_LOGIN_DEV_PASSWORD}"
: "${XM_NODE_LINK_SECRET:?需要环境变量 XM_NODE_LINK_SECRET（gate → scene 链路密钥）}"
: "${XM_DUBBO_SECRET:?需要环境变量 XM_DUBBO_SECRET（Dubbo 调用方鉴权密钥）}"

# 运行模式：本机切片缺省 dev（放行 Gm* / Debug* / Test* 客户端指令，同基线 tools/scripts/start_game.ps1）；
# 进程自身缺省 prod，部署链不设它即拒绝。要在本机验证生产行为：XM_RUN_MODE=prod tools/local/start-slice.sh
export XM_RUN_MODE="${XM_RUN_MODE:-dev}"
echo "运行模式 XM_RUN_MODE=$XM_RUN_MODE"

# xm-data 运维接口令牌：没设就生成一个本机随机令牌写进 run/xm-admin-token（run/ 不进仓库；robot audit 场景从这里读）
mkdir -p run
if [[ -z "${XM_ADMIN_TOKEN:-}" ]]; then
  XM_ADMIN_TOKEN=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  printf "%s" "$XM_ADMIN_TOKEN" > run/xm-admin-token
fi
export XM_ADMIN_TOKEN

# 登录排队：进程缺省关闭（同基线 Queue.Enabled=false）；本机切片打开，robot 全程走快速通道，queue 场景压容量验证排队与放行
export XM_GATEWAY_QUEUE_ENABLED="${XM_GATEWAY_QUEUE_ENABLED:-true}"

mkdir -p run/logs run/pids

# 模块名 就绪端口
SERVICES=(
  "xm-scene-manager 20882"
  "xm-login 20881"
  "xm-data 18106"
  "xm-scene 21000"
  "xm-gate 11000"
  "xm-gateway 18081"
)

wait_port() {
  local port=$1 name=$2 deadline=$((SECONDS + 90))
  until (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; do
    if ! kill -0 "$(cat "run/pids/$name.pid")" 2>/dev/null; then
      echo "[$name] 进程已退出，看 run/logs/$name.log" >&2
      return 1
    fi
    if (( SECONDS > deadline )); then
      echo "[$name] 90s 内端口 $port 未就绪" >&2
      return 1
    fi
    sleep 1
  done
}

for entry in "${SERVICES[@]}"; do
  read -r name port <<<"$entry"
  jar=$(ls "$name"/target/"$name"-*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)
  if [[ -z "$jar" ]]; then
    echo "找不到 $name 的可执行 jar，先 ./mvnw -DskipTests install" >&2
    exit 1
  fi
  echo "启动 $name ($jar)"
  java -jar "$jar" > "run/logs/$name.log" 2>&1 &
  echo $! > "run/pids/$name.pid"
  wait_port "$port" "$name"
  echo "  $name 就绪（端口 $port）"
done

echo "全部就绪。停止：tools/local/stop-slice.sh"
