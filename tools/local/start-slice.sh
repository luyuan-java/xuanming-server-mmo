#!/usr/bin/env bash
# 本地拉起 Java 版竖切（local profile：Dubbo 直连，不需要 Nacos）。在仓库根目录执行：
#   export XM_MYSQL_PASSWORD=... XM_GATE_TOKEN_SECRET=... XM_LOGIN_DEV_PASSWORD=... XM_NODE_LINK_SECRET=... XM_DUBBO_SECRET=...
#   tools/local/start-slice.sh
# XM_NODE_LINK_SECRET 是 gate → scene 节点链路握手密钥，xm-gate 与 xm-scene 读同一个值（本脚本把同一环境传给两者）。
# XM_DUBBO_SECRET 是 Dubbo 调用方鉴权密钥，xm-scene-manager / xm-login / xm-friend / xm-chat / xm-team / xm-guild / xm-trade / xm-scene / xm-gate / xm-gateway
# 读同一个值（xm-scene 自 4.5 起是资产通道 SceneAssetOpService 的 Dubbo 提供方，缺密钥即暴露失败）。
# XM_ASSET_OP_SECRET_GUILD 是帮会资产指令的请求体签名密钥（xm-guild 签、xm-scene 验，去首尾空白后至少 32 字节）；
# 没设时本脚本生成本机随机值写进 run/xm-asset-op-secret-guild，并把同一个值传给两者。
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

# 运行模式：本机切片缺省 dev（放行 Gm* / Debug* / Test* 客户端指令，同基线 tools/scripts/start_game.ps1；xm-trade 只在 dev / test 下开放播种）；
# 进程自身缺省 prod，部署链不设它即拒绝。要在本机验证生产行为：XM_RUN_MODE=prod tools/local/start-slice.sh
export XM_RUN_MODE="${XM_RUN_MODE:-dev}"
echo "运行模式 XM_RUN_MODE=$XM_RUN_MODE"

# 运维令牌（xm-data 运维接口与 xm-trade 播种接口 POST /admin/trade/seed-listing 共用，头 X-Xm-Admin-Token）：没设就生成一个本机随机令牌
# 写进 run/xm-admin-token（run/ 不进仓库；robot audit / trade 等场景从这里读）
mkdir -p run
if [[ -z "${XM_ADMIN_TOKEN:-}" ]]; then
  XM_ADMIN_TOKEN=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$XM_ADMIN_TOKEN" > run/xm-admin-token)
  chmod 600 run/xm-admin-token
fi
export XM_ADMIN_TOKEN

# GM 签名停机密钥（xm-gate / xm-scene 的 /gm/graceful-shutdown，tools/GmShutdown.java 签名用）：没设就生成本机随机密钥写进 run/xm-gm-admin-secret（只有本用户可读）
if [[ -z "${XM_GM_ADMIN_SECRET:-}" ]]; then
  XM_GM_ADMIN_SECRET=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$XM_GM_ADMIN_SECRET" > run/xm-gm-admin-secret)
  chmod 600 run/xm-gm-admin-secret
fi
export XM_GM_ADMIN_SECRET

# 帮会资产指令签名密钥（xm-guild → xm-scene 资产通道，guild-economy-spec §7.8）：没设就生成 32 字节本机随机密钥（64 个十六进制字符）
# 写进 run/xm-asset-op-secret-guild（只有本用户可读）；导出后 xm-scene 与 xm-guild 继承同一个值。单独重启其中一个时从这个文件读回同一个值。
if [[ -z "${XM_ASSET_OP_SECRET_GUILD:-}" ]]; then
  XM_ASSET_OP_SECRET_GUILD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$XM_ASSET_OP_SECRET_GUILD" > run/xm-asset-op-secret-guild)
  chmod 600 run/xm-asset-op-secret-guild
fi
# 与两侧的校验同口径（去首尾空白后按字节数 ≥ 32）提前拦下过短的外部值：否则 xm-guild 开着资产通道时拒启、xm-scene 验签一律失败
asset_secret_trimmed=$(printf "%s" "$XM_ASSET_OP_SECRET_GUILD" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
if (( $(printf "%s" "$asset_secret_trimmed" | LC_ALL=C wc -c) < 32 )); then
  echo "XM_ASSET_OP_SECRET_GUILD 去首尾空白后不足 32 字节；取消这个环境变量让脚本生成，或换一个更长的值" >&2
  exit 1
fi
unset asset_secret_trimmed
export XM_ASSET_OP_SECRET_GUILD

# 登录排队：进程缺省关闭（同基线 Queue.Enabled=false）；本机切片打开，robot 全程走快速通道，queue 场景压容量验证排队与放行
export XM_GATEWAY_QUEUE_ENABLED="${XM_GATEWAY_QUEUE_ENABLED:-true}"
# 开服限流：进程缺省关闭（同基线 gate.rate-limit.enabled=false）；本机切片打开（缺省阈值），ratelimit 场景验证 IP 桶与冷却
export XM_GATEWAY_RATE_LIMIT_ENABLED="${XM_GATEWAY_RATE_LIMIT_ENABLED:-true}"

mkdir -p run/logs run/pids

# 模块名 就绪端口…（一个进程可以列多个端口，逐个等到可连再起下一个）
SERVICES=(
  "xm-scene-manager 20882"
  "xm-login 20881"
  "xm-friend 20883"
  "xm-chat 20884"
  "xm-team 20885"
  "xm-guild 20886"
  "xm-trade 20887"        # 聚宝斋；播种接口在管理端口 18111（Tomcat 先于 Dubbo 暴露就绪，等 20887 即可）
  "xm-data 18106"
  "xm-scene 21000 21100"   # 节点链路 link-port；资产通道 Dubbo Triple（xm.scene.asset-rpc-port，xm-guild 按节点目录直连）
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

# 主世界频道就绪（批次 5.1，scene-channels-spec §5.2）：scene 节点启动时只建计划里已有的本节点频道，计划还没有本节点（冷 Redis、
# 重启时旧记录已被领导者按死节点删掉）就不带频道起来，等 scene-manager 领导者下一拍（≤ 5 s）铺上、节点下一次拉取（≤ 1 s）建出、
# 立即补发目录后才分得到。端口就绪不代表能进游戏：等管理端口上的 xm_scene_channels{state="active"} ≥ 1 再起 gate / gateway。
wait_world_channels() {
  local name=$1 deadline=$((SECONDS + 60)) active
  until active=$(curl -fsS "http://127.0.0.1:18104/actuator/prometheus" 2>/dev/null \
      | grep -E '^xm_scene_channels\{.*state="active"' | awk '{print int($NF)}' | head -1) && [[ "${active:-0}" -ge 1 ]]; do
    if ! kill -0 "$(cat "run/pids/$name.pid")" 2>/dev/null; then
      echo "[$name] 进程已退出，看 run/logs/$name.log" >&2
      return 1
    fi
    if (( SECONDS > deadline )); then
      echo "[$name] 60s 内没有铺好主世界频道（看 run/logs/xm-scene-manager.log 的 world 领导者日志）" >&2
      return 1
    fi
    sleep 1
  done
  # 节点应用计划后立即补发目录；再给一拍余量让 scene-manager 读到
  sleep 1
}

for entry in "${SERVICES[@]}"; do
  read -r name ports <<<"$entry"
  jar=$(ls "$name"/target/"$name"-*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)
  if [[ -z "$jar" ]]; then
    echo "找不到 $name 的可执行 jar，先 ./mvnw -DskipTests install" >&2
    exit 1
  fi
  echo "启动 $name ($jar)"
  java -jar "$jar" > "run/logs/$name.log" 2>&1 &
  echo $! > "run/pids/$name.pid"
  for port in $ports; do
    wait_port "$port" "$name"
  done
  if [[ "$name" == "xm-scene" ]]; then
    wait_world_channels "$name"
  fi
  echo "  $name 就绪（端口 $ports）"
done

echo "全部就绪。停止：tools/local/stop-slice.sh"
