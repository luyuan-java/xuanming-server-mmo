#!/usr/bin/env bash
# 本地拉起 Java 版竖切（local profile：Dubbo 直连，不需要 Nacos）。在仓库根目录执行：
#   export XM_MYSQL_PASSWORD=... XM_GATE_TOKEN_SECRET=... XM_LOGIN_DEV_PASSWORD=... XM_NODE_LINK_SECRET=... XM_DUBBO_SECRET=...
#   tools/local/start-slice.sh
# XM_NODE_LINK_SECRET 是 gate → scene 节点链路握手密钥，xm-gate 与 xm-scene 读同一个值（本脚本把同一环境传给两者）。
# XM_DUBBO_SECRET 是 Dubbo 调用方鉴权密钥，xm-scene-manager / xm-login / xm-friend / xm-chat / xm-team / xm-guild / xm-trade / xm-scene / xm-gate / xm-gateway / xm-battle
# 读同一个值（xm-scene 自 4.5 起是资产通道 SceneAssetOpService 的 Dubbo 提供方，缺密钥即暴露失败）。
# （xm-battle 是控制面 BattleNodeService 的 Dubbo 提供方，缺密钥即拒启。）
# XM_ASSET_OP_SECRET_GUILD 是帮会资产指令的请求体签名密钥（xm-guild 签、xm-scene 验，去首尾空白后至少 32 字节）；
# 没设时本脚本生成本机随机值写进 run/xm-asset-op-secret-guild，并把同一个值传给两者。
# XM_BATTLE_TOKEN_SECRET 是战斗直连票据的签名密钥（只有 xm-battle 读；去首尾空白后至少 32 字节、不得与 XM_GATE_TOKEN_SECRET 相同）；
# 没设时本脚本生成本机随机值写进 run/xm-battle-token-secret。xm-battle 的客户端直连面在 12000、控制面在 21200、管理端口 18112。
# 前置：MySQL 127.0.0.1:3306、Redis 127.0.0.1:6379、Kafka 127.0.0.1:9092（资产流水，xm-scene 生产、xm-data 消费）已就绪；已执行 ./mvnw -DskipTests install；
# 存量库已按 docs/design/db-migrations.md 迁移到最新结构（M2 起 player 表多了 owner_released / owner_lease_until）。
# 进程按依赖顺序启动，每个都等端口就绪再起下一个；日志在 run/logs/，PID 在 run/pids/。
#
# 场景节点数 XM_SCENE_NODES（批次 5.2 跨节点换图，scene-handoff-spec §10.7）：缺省 1（与以前相同）；=2 时再起第二个 xm-scene 实例
# （日志 / PID 名 xm-scene-2，链路 21001、资产通道 21101、管理端口 18114；节点号由 Redis 租约自动分到不同的号）。
# scene-manager 保持 per-node 覆盖，两个节点各有每张世界图一个频道：robot cross-node 场景据此判断「同图不同 scene_id = 不同节点」。
#   XM_SCENE_NODES=2 tools/local/start-slice.sh
# XM_SCENE_MANAGER_URL：scene → scene-manager 选跨节点目标的直连地址（xm.scene.scene-manager-url，local profile），缺省 tri://127.0.0.1:20882。
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

# 战斗直连票据签名密钥（xm-battle 签 177 / 补签里的票、直连握手时验签，battle-node-spec §7.5；全部 battle 实例共享，只有 xm-battle 读）：
# 没设就生成 32 字节本机随机密钥（64 个十六进制字符）写进 run/xm-battle-token-secret（只有本用户可读）。xm-battle 任何运行模式都拒绝空密钥；
# 太短或与 gate 令牌密钥相同时 dev 只告警、prod 拒启——本机切片两种模式都提前拦下（与 xm-battle 同口径：去首尾空白后比较、按字节数计）。
if [[ -z "${XM_BATTLE_TOKEN_SECRET:-}" ]]; then
  XM_BATTLE_TOKEN_SECRET=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$XM_BATTLE_TOKEN_SECRET" > run/xm-battle-token-secret)
  chmod 600 run/xm-battle-token-secret
fi
battle_secret_trimmed=$(printf "%s" "$XM_BATTLE_TOKEN_SECRET" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
gate_secret_trimmed=$(printf "%s" "$XM_GATE_TOKEN_SECRET" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
if (( $(printf "%s" "$battle_secret_trimmed" | LC_ALL=C wc -c) < 32 )); then
  echo "XM_BATTLE_TOKEN_SECRET 去首尾空白后不足 32 字节；取消这个环境变量让脚本生成，或换一个更长的值" >&2
  exit 1
fi
if [[ "$battle_secret_trimmed" == "$gate_secret_trimmed" ]]; then
  echo "XM_BATTLE_TOKEN_SECRET 不能与 XM_GATE_TOKEN_SECRET 相同（两把密钥分域：拿到大厅令牌密钥的人不能伪造战斗票据）；取消它让脚本生成" >&2
  exit 1
fi
unset battle_secret_trimmed gate_secret_trimmed
export XM_BATTLE_TOKEN_SECRET

# 登录排队：进程缺省关闭（同基线 Queue.Enabled=false）；本机切片打开，robot 全程走快速通道，queue 场景压容量验证排队与放行
export XM_GATEWAY_QUEUE_ENABLED="${XM_GATEWAY_QUEUE_ENABLED:-true}"
# 开服限流：进程缺省关闭（同基线 gate.rate-limit.enabled=false）；本机切片打开（缺省阈值），ratelimit 场景验证 IP 桶与冷却
export XM_GATEWAY_RATE_LIMIT_ENABLED="${XM_GATEWAY_RATE_LIMIT_ENABLED:-true}"

XM_SCENE_NODES="${XM_SCENE_NODES:-1}"
if [[ "$XM_SCENE_NODES" != "1" && "$XM_SCENE_NODES" != "2" ]]; then
  echo "XM_SCENE_NODES 只能是 1 或 2：$XM_SCENE_NODES" >&2
  exit 1
fi
XM_SCENE_MANAGER_URL="${XM_SCENE_MANAGER_URL:-tri://127.0.0.1:20882}"
echo "场景节点数 XM_SCENE_NODES=$XM_SCENE_NODES"

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
  "xm-scene"              # 场景节点：起 XM_SCENE_NODES 个实例，端口见下面的 SCENE_NODES
  "xm-gate 11000"
  "xm-gateway 18081"
  "xm-battle"             # 战斗节点：先等控制面 21200、直连面 12000，再等准入闸打开（见 wait_battle_ready）
)

# xm-battle 的端口（与 xm-battle application.yaml 的缺省一致）：Dubbo 控制面、客户端直连面、管理端口（actuator 与 dev 接口 /admin/battle/dev/*）
BATTLE_RPC_PORT=21200
BATTLE_CLIENT_PORT=12000
BATTLE_MGMT_PORT=18112

# 场景节点实例：实例名（日志 / PID 文件名） 节点链路 link-port  资产通道 asset-rpc-port  管理端口 server.port
# 资产通道是 Dubbo Triple（xm.scene.asset-rpc-port，同 XM_SCENE_ASSET_RPC_PORT；xm-guild 按节点目录直连）；管理端口同 SERVER_PORT（actuator 指标、GM 停机）。
# 同机多实例三个端口都必须各不相同（xm-scene application.yaml 的约定）；端口用命令行参数传入，优先级高于配置文件与环境变量。
SCENE_NODES=(
  "xm-scene   21000 21100 18104"
  "xm-scene-2 21001 21101 18114"
)

# 起一个进程：$1 实例名（日志 run/logs/<实例名>.log、PID run/pids/<实例名>.pid），$2 模块名（找可执行 jar），其余原样作为进程的命令行参数
launch() {
  local instance=$1 module=$2 jar
  shift 2
  jar=$(ls "$module"/target/"$module"-*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)
  if [[ -z "$jar" ]]; then
    echo "找不到 $module 的可执行 jar，先 ./mvnw -DskipTests install" >&2
    exit 1
  fi
  echo "启动 $instance ($jar)"
  java -jar "$jar" "$@" > "run/logs/$instance.log" 2>&1 &
  echo $! > "run/pids/$instance.pid"
}

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
# 每个 scene 实例各等各的（$2 是该实例的管理端口）：第二个节点的频道由领导者按 per-node 覆盖在它加入后的下一拍铺上。
wait_world_channels() {
  local name=$1 port=$2 deadline=$((SECONDS + 60)) active
  until active=$(curl -fsS "http://127.0.0.1:$port/actuator/prometheus" 2>/dev/null \
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

# 依次起 XM_SCENE_NODES 个场景节点，每个都等链路 / 资产通道端口与主世界频道就绪再起下一个
start_scene_nodes() {
  local i instance link rpc mgmt
  for ((i = 0; i < XM_SCENE_NODES; i++)); do
    read -r instance link rpc mgmt <<<"${SCENE_NODES[$i]}"
    launch "$instance" xm-scene --server.port="$mgmt" --xm.scene.link-port="$link" \
        --xm.scene.asset-rpc-port="$rpc" --xm.scene.scene-manager-url="$XM_SCENE_MANAGER_URL"
    wait_port "$link" "$instance"
    wait_port "$rpc" "$instance"
    wait_world_channels "$instance" "$mgmt"
    echo "  $instance 就绪（链路 $link、资产通道 $rpc、管理端口 $mgmt）"
  done
}

# xm-battle 就绪（batch 6.2）：管理端口的 Tomcat 先于节点就绪（/actuator/health 报 UP 比节点就绪早约 2.5 s），不能看 health。
# 节点按「导出 Dubbo（21200）→ 绑直连面（12000）→ 在逻辑线程上开准入闸 → 发布目录 → 打就绪日志」的顺序起来：两个端口都能连之后，
# 再等管理端口上的 xm_battle_admission_phase = 1（open）——之前进来的 dev 建房会回 NOT_ALLOCATABLE(not_started)。
wait_battle_ready() {
  local name=xm-battle deadline=$((SECONDS + 60)) phase
  wait_port "$BATTLE_RPC_PORT" "$name"
  wait_port "$BATTLE_CLIENT_PORT" "$name"
  until phase=$(curl -fsS "http://127.0.0.1:$BATTLE_MGMT_PORT/actuator/prometheus" 2>/dev/null \
      | grep -E '^xm_battle_admission_phase(\{| )' | awk '{print int($NF)}' | head -1) && [[ "${phase:-0}" -eq 1 ]]; do
    if ! kill -0 "$(cat "run/pids/$name.pid")" 2>/dev/null; then
      echo "[$name] 进程已退出，看 run/logs/$name.log" >&2
      return 1
    fi
    if (( SECONDS > deadline )); then
      echo "[$name] 60s 内准入闸没有打开（xm_battle_admission_phase=${phase:-?}，看 run/logs/$name.log 的「节点已就绪」）" >&2
      return 1
    fi
    sleep 1
  done
}

for entry in "${SERVICES[@]}"; do
  read -r name ports <<<"$entry"
  if [[ "$name" == "xm-scene" ]]; then
    start_scene_nodes
    continue
  fi
  if [[ "$name" == "xm-battle" ]]; then
    launch xm-battle xm-battle
    wait_battle_ready
    echo "  xm-battle 就绪（控制面 $BATTLE_RPC_PORT、直连面 $BATTLE_CLIENT_PORT、管理端口 $BATTLE_MGMT_PORT，准入闸已开）"
    continue
  fi
  launch "$name" "$name"
  for port in $ports; do
    wait_port "$port" "$name"
  done
  echo "  $name 就绪（端口 $ports）"
done

echo "全部就绪（场景节点 $XM_SCENE_NODES 个）。停止：tools/local/stop-slice.sh"
