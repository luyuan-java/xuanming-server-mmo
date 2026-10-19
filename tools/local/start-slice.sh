#!/usr/bin/env bash
# 本地拉起 Java 版竖切（local profile：Dubbo 直连，不需要 Nacos）。在仓库根目录执行：
#   export GAME_MYSQL_PASSWORD=... GAME_GATE_TOKEN_SECRET=... GAME_LOGIN_DEV_PASSWORD=... GAME_NODE_LINK_SECRET=... GAME_DUBBO_SECRET=...
#   tools/local/start-slice.sh
# GAME_NODE_LINK_SECRET 是 gate → scene 节点链路握手密钥，game-gate 与 game-scene 读同一个值（本脚本把同一环境传给两者）。
# GAME_DUBBO_SECRET 是 Dubbo 调用方鉴权密钥，game-scene-manager / game-login / game-friend / game-chat / game-match / game-team / game-guild / game-trade / game-scene / game-gate / game-gateway / game-battle
# 读同一个值（game-scene 自 4.5 起是资产通道 SceneAssetOpService 的 Dubbo 提供方，缺密钥即暴露失败）。
# （game-battle 是控制面 BattleNodeService 的 Dubbo 提供方，缺密钥即拒启。game-data 在 GAME_DATA_OPS_ENABLED=true 时是 GuildInternalService
# 的调用方——回档的帮会检查——同样读这个值，缺了拒启。game-match（批次 6.4）既是提供方——客户端消息 MatchService、整队开战 MatchTeamService、
# 帮会活动开战 MatchInternalService——又是 game-scene 战斗通道与 game-battle 控制面的调用方，缺密钥即拒启。）
# GAME_ASSET_OP_SECRET_GUILD 是帮会资产指令的请求体签名密钥（game-guild 签、game-scene 验，去首尾空白后至少 32 字节）；
# 没设时本脚本生成本机随机值写进 run/game-asset-op-secret-guild，并把同一个值传给两者。
# GAME_BATTLE_TOKEN_SECRET 是战斗直连票据的签名密钥（只有 game-battle 读；去首尾空白后至少 32 字节、不得与 GAME_GATE_TOKEN_SECRET 相同）；
# 没设时本脚本生成本机随机值写进 run/game-battle-token-secret。game-battle 的客户端直连面在 12000、控制面在 21200、管理端口 18112。
# game-match（批次 6.4：排队、凑单、开局、评分、切磋、补签）的 Dubbo 在 20888（GAME_MATCH_RPC_PORT）、管理端口 18113（actuator 与 dev 管理口
# /admin/match/dev/*，令牌同 GAME_ADMIN_TOKEN）。它的评分表在 MySQL 库 <ns>（启动时自建），所以也读 GAME_MYSQL_PASSWORD。
# GAME_BATTLE_RESULT_TOPIC_GENERATION 是对局结果 topic game-battle-result-g<代次> 的代次（game-battle 生产、game-match 消费，两边必须一致），缺省 1；
# 本脚本导出后两个进程继承同一个值。topic 的分区数与预期不符时（进程会明说）换一个代次重起切片。
# 前置：MySQL 127.0.0.1:3306、Redis 127.0.0.1:6379、Kafka 127.0.0.1:9092（资产流水：game-scene 生产、game-data 消费；对局结果：game-battle 生产、
# game-match 消费——按 match-spec §5.4 / §9.8，Kafka 不可达不拦这两个进程启动，只是评分不更新）已就绪；已执行 ./mvnw -DskipTests install；
# 存量库已按 docs/design/db-migrations.md 迁移到最新结构（M2 起 player 表多了 owner_released / owner_lease_until）。
# 进程按依赖顺序启动，每个都等端口就绪再起下一个；日志在 run/logs/，PID 在 run/pids/。
#
# 部署命名空间 GAME_NAMESPACE（architecture.md「部署命名空间」）：Redis 键前缀 <ns>:、Kafka topic / 消费组 <ns>-、MySQL 库 <ns>。缺省 game
# （与引入命名空间之前完全相同，本文件里的 game: / game- / 库 game 都指缺省）；本脚本导出后全部进程继承同一个值。同一套 infra 上起第二个游戏 /
# 第二套部署时设不同的值：GAME_NAMESPACE=t1 tools/local/start-slice.sh。只在没设时取 game；设成空串或非法值时本脚本在起任何进程之前就退出。
# 生效的值写进 run/game-namespace，battle-crash-window.sh 重启进程时读回核对。本脚本的端口写死、run/ 下的 PID 与秘密文件共用，
# 同一个检出不能同时起两套（先 stop-slice.sh 停掉一套再起另一套，或用两个检出 / 两台机器各起一套、共用 infra）。
#
# 启动次序的一条硬约束：local profile 下静态直连（各进程 application.yaml 的 game.dubbo.*-url）的 Dubbo 提供方必须先于它的调用方启动。
# Dubbo 3.3.6 的 Triple 客户端建引用时没连上，下一次重连排在 60 s 之后（game-gate 的 BackendReconnectTest 钉住）：调用方先起的话，
# 提供方就绪后的头一分钟里调用一律失败（客户端看到信封 1003）。所以 game-match 排在 game-team（整队开战调它）与 game-gate（MatchService 的
# 10 个号转给它）之前，没有照 match-spec §15.4 的草案放在 game-battle 之后。它自己调 game-scene / game-battle 走节点目录、用到时才建引用，
# 不要求它们先起：game-battle 就绪之前凑单暂停、队列原样保留。停止次序见 stop-slice.sh（game-match 最先停）。
#
# 场景节点数 GAME_SCENE_NODES（批次 5.2 跨节点换图，scene-handoff-spec §10.7）：缺省 1（与以前相同）；=2 时再起第二个 game-scene 实例
# （日志 / PID 名 game-scene-2，链路 21001、资产通道 21101、管理端口 18114；节点号由 Redis 租约自动分到不同的号）。
# scene-manager 保持 per-node 覆盖，两个节点各有每张世界图一个频道：robot cross-node 场景据此判断「同图不同 scene_id = 不同节点」。
#   GAME_SCENE_NODES=2 tools/local/start-slice.sh
# GAME_SCENE_MANAGER_URL：scene → scene-manager 选跨节点目标的直连地址（game.scene.scene-manager-url，local profile），缺省 tri://127.0.0.1:20882。
#
# 区数 GAME_ZONES（批次 6.5 跨区 1V1，spectate-spec §10.6 / §10.8；脚本这一块按 zone-travel-spec §5.13 提前落地，不依赖 5.4 的任何生产代码）：
# 缺省 1（进程与端口同以前）；=2 时多起区 2 的一个场景节点与一个 gate，其余进程两个区共用（都不分 zone，或按会话 / 位置记录取 zone）：
#   game-scene-z2  --game.zone-id=2，链路 21010、资产通道 21110、管理端口 18115；排在区 1 的场景节点之后，同样等主世界频道铺好
#   game-gate-z2   --game.zone-id=2，客户端 11010、管理端口 18123；排在 game-gate 之后（全部 Dubbo 后端都已先起，上面那条硬约束对它同样成立），
#                Dubbo 后端地址用缺省：与区 1 的 gate 指向同一组服务（同一个 game-match、game-team……）
# 节点号按 zone 租约，区 2 的 scene / gate 也是 1 号——与区 1 同号是有意的：只按节点号寻址的代码在这个形态下会把区 2 玩家的消息
# 送到区 1 的同号节点（spectate-spec §2.8），robot battle-cross-zone 靠它暴露这类问题。可与 GAME_SCENE_NODES=2 同时用（game-scene-2 仍是区 1 的第二个节点）。
#   GAME_ZONES=2 tools/local/start-slice.sh
#   java -jar game-robot/target/game-robot-*.jar smoke --zone 2                                   # 区 2 能登录、进场
#   java -jar game-robot/target/game-robot-*.jar battle-cross-zone --zone 1 --visit-zone 2        # 跨区 1V1（spectate-spec §10.8；要 dev 运行模式）
# 区 2 在区服目录（MySQL zone_config）里的那一行由本脚本经 game-data 的运维接口管，状态跟随 GAME_ZONES（见 sync_zone2_status）：全部进程就绪之后
# =2 时把它置 OPEN（库里还没有就建出来），=1 时置维护（库里没有就什么都不做）——区 2 建过一次就留在库里，不这样做的话单 zone 切片上会留下
# 一个没有 gate 的 OPEN 区。等 GET /api/server-list 反映出来才报「全部就绪」。game-gateway 的命令行两种形态下都不变（它自己只播种配置文件里的一区）。
# Windows 上新占的五个端口不在保留端口段里（netsh int ipv4 show excludedportrange tcp 可查）。
set -euo pipefail

cd "$(dirname "$0")/../.."
: "${GAME_MYSQL_PASSWORD:?需要环境变量 GAME_MYSQL_PASSWORD}"
: "${GAME_GATE_TOKEN_SECRET:?需要环境变量 GAME_GATE_TOKEN_SECRET}"
: "${GAME_LOGIN_DEV_PASSWORD:?需要环境变量 GAME_LOGIN_DEV_PASSWORD}"
: "${GAME_NODE_LINK_SECRET:?需要环境变量 GAME_NODE_LINK_SECRET（gate → scene 链路密钥）}"
: "${GAME_DUBBO_SECRET:?需要环境变量 GAME_DUBBO_SECRET（Dubbo 调用方鉴权密钥）}"

# 运行模式：本机切片缺省 dev（放行 Gm* / Debug* / Test* 客户端指令，同基线 tools/scripts/start_game.ps1；game-trade 只在 dev / test 下开放播种，
# game-match 的 dev 管理口——读评分、活动开战——同样只在 dev / test 下开放，其余一律 403）；
# 进程自身缺省 prod，部署链不设它即拒绝。要在本机验证生产行为：GAME_RUN_MODE=prod tools/local/start-slice.sh
export GAME_RUN_MODE="${GAME_RUN_MODE:-dev}"
echo "运行模式 GAME_RUN_MODE=$GAME_RUN_MODE"

# 部署命名空间的校验（与 com.game.common.GameNamespace 同口径：正则、1–28 个字符、保留字），battle-crash-window.sh 有逐字相同的一份。
# 正则在 C 区域设置下匹配（别的区域设置里 [a-z] 可能连大写也算进去）。
validate_namespace() {
  local ns=$1
  if ! (LC_ALL=C; [[ "$ns" =~ ^[a-z][a-z0-9]{0,27}$ ]]) || [[ "$ns" =~ ^(mysql|sys|public)$ ]]; then
    echo "部署命名空间 GAME_NAMESPACE=\"$ns\" 不合法：必须匹配 [a-z][a-z0-9]{0,27}（小写字母开头，只含小写字母与数字，1–28 个字符），且不是保留字 mysql、sys、public；不设则为 game" >&2
    return 1
  fi
}

# 部署命名空间（见文件头）：显式导出，免得只给其中几个进程设了别的值（不一致不会串数据，但表现为彼此看不见：gate 找不到 scene 目录……）。
# 只在没设时取 game（${GAME_NAMESPACE-game}，不带冒号）：设了空串——例如 GAME_NAMESPACE=$TENANT 而 TENANT 恰好没设——原样交给校验拒绝，
# 不会静默换成 game、在共用的 infra 上加入缺省部署的键空间、节点号租约池与库 game。在起任何进程之前就校验，不必等第一个进程拒启。
export GAME_NAMESPACE="${GAME_NAMESPACE-game}"
validate_namespace "$GAME_NAMESPACE" || exit 1
echo "部署命名空间 GAME_NAMESPACE=$GAME_NAMESPACE"

# 本检出里已有切片在跑（run/pids/*.pid 里有活进程）就拒绝启动，在写 run/game-namespace 与任何 PID 文件之前。否则：t1 的切片正在跑时，
# 在一个新开、没设 GAME_NAMESPACE 的 shell 里误执行本脚本，记录先被改成 game，第一个进程随后因端口冲突退出（它的 PID 文件也被覆盖），
# 之后 battle-crash-window.sh 读到 game、以 game 重启 t1 切片的 scene / battle——跨进同一套 infra 上缺省部署的目录、租约池与库。
# 同一个命名空间重起也一样先停（端口与 PID 文件是同一套）。
refuse_if_slice_running() {
  local pidfile pid running="" recorded="（没有记录）"
  for pidfile in run/pids/*.pid; do
    [[ -f "$pidfile" ]] || continue
    pid=$(cat "$pidfile" 2>/dev/null || true)
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      running+=" $(basename "$pidfile" .pid)"
    fi
  done
  [[ -z "$running" ]] && return 0
  [[ -f run/game-namespace ]] && recorded="\"$(cat run/game-namespace)\""
  echo "本检出已有切片在运行（run/pids 里的活进程：${running# }），它的部署命名空间是 $recorded（run/game-namespace），本次要起的是 \"$GAME_NAMESPACE\"。\
拒绝启动，没有改 run/game-namespace 与 PID 文件：先 tools/local/stop-slice.sh 停掉，再按要的命名空间重起" >&2
  return 1
}
refuse_if_slice_running || exit 1

# 记下本切片生效的命名空间（不是秘密，照常权限）：battle-crash-window.sh 重启进程时读回核对——在新开的、没设 GAME_NAMESPACE 的 shell 里
# 对 t1 的切片执行它，重启出来的进程也不会以 game 起来、跨进另一个部署。stop-slice.sh 全部停完后删掉它
mkdir -p run
printf "%s" "$GAME_NAMESPACE" > run/game-namespace

# 镜像 / 副本实例的空闲回收（批次 5.3，dungeon-mirror-spec §7.3）：本机切片为 robot mirror 提速，缺省把镜像空置超时调到 5s、
# 回收宽限调到下限 10s（game-scene 进程缺省 30s / 30s，副本 300s 不动）；要按生产缺省验证就显式设这两个环境变量。
export GAME_SCENE_MIRROR_IDLE_TIMEOUT="${GAME_SCENE_MIRROR_IDLE_TIMEOUT:-5s}"
export GAME_SCENE_INSTANCE_RECLAIM_GRACE="${GAME_SCENE_INSTANCE_RECLAIM_GRACE:-10s}"
echo "实例回收 镜像空置 $GAME_SCENE_MIRROR_IDLE_TIMEOUT + 回收宽限 $GAME_SCENE_INSTANCE_RECLAIM_GRACE"

# 回合制战斗 reaper（批次 6.3，scene-battle-spec §8）：game-scene 进程缺省 30s（同基线）；本机切片调到 2s，robot battle-settle 的备战到期 /
# FIGHTING 判废（期限 + 10s 宽限）在秒级内可见。只许调小（≤ 30s）
export GAME_SCENE_BATTLE_REAPER_INTERVAL="${GAME_SCENE_BATTLE_REAPER_INTERVAL:-2s}"
echo "回合制战斗 reaper 间隔 $GAME_SCENE_BATTLE_REAPER_INTERVAL"

# 运维令牌（game-data 运维接口、game-trade 播种接口 POST /admin/trade/seed-listing、game-battle 的 dev 接口 /admin/battle/dev/* 与 game-match 的
# dev 管理口 /admin/match/dev/* 共用，头 X-Game-Admin-Token）：没设就生成一个本机随机令牌
# 写进 run/game-admin-token（run/ 不进仓库；robot audit / trade / battle / battle-smoke / match-activity 等场景从这里读）
mkdir -p run
if [[ -z "${GAME_ADMIN_TOKEN:-}" ]]; then
  GAME_ADMIN_TOKEN=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$GAME_ADMIN_TOKEN" > run/game-admin-token)
  chmod 600 run/game-admin-token
fi
export GAME_ADMIN_TOKEN

# GM 签名停机密钥（game-gate / game-scene 的 /gm/graceful-shutdown，tools/GmShutdown.java 签名用）：没设就生成本机随机密钥写进 run/game-gm-admin-secret（只有本用户可读）
if [[ -z "${GAME_GM_ADMIN_SECRET:-}" ]]; then
  GAME_GM_ADMIN_SECRET=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$GAME_GM_ADMIN_SECRET" > run/game-gm-admin-secret)
  chmod 600 run/game-gm-admin-secret
fi
export GAME_GM_ADMIN_SECRET

# 帮会资产指令签名密钥（game-guild → game-scene 资产通道，guild-economy-spec §7.8）：没设就生成 32 字节本机随机密钥（64 个十六进制字符）
# 写进 run/game-asset-op-secret-guild（只有本用户可读）；导出后 game-scene 与 game-guild 继承同一个值。单独重启其中一个时从这个文件读回同一个值。
if [[ -z "${GAME_ASSET_OP_SECRET_GUILD:-}" ]]; then
  GAME_ASSET_OP_SECRET_GUILD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$GAME_ASSET_OP_SECRET_GUILD" > run/game-asset-op-secret-guild)
  chmod 600 run/game-asset-op-secret-guild
fi
# 与两侧的校验同口径（去首尾空白后按字节数 ≥ 32）提前拦下过短的外部值：否则 game-guild 开着资产通道时拒启、game-scene 验签一律失败
asset_secret_trimmed=$(printf "%s" "$GAME_ASSET_OP_SECRET_GUILD" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
if (( $(printf "%s" "$asset_secret_trimmed" | LC_ALL=C wc -c) < 32 )); then
  echo "GAME_ASSET_OP_SECRET_GUILD 去首尾空白后不足 32 字节；取消这个环境变量让脚本生成，或换一个更长的值" >&2
  exit 1
fi
unset asset_secret_trimmed
export GAME_ASSET_OP_SECRET_GUILD

# 战斗直连票据签名密钥（game-battle 签 177 / 补签里的票、直连握手时验签，battle-node-spec §7.5；全部 battle 实例共享，只有 game-battle 读）：
# 没设就生成 32 字节本机随机密钥（64 个十六进制字符）写进 run/game-battle-token-secret（只有本用户可读）。game-battle 任何运行模式都拒绝空密钥；
# 太短或与 gate 令牌密钥相同时 dev 只告警、prod 拒启——本机切片两种模式都提前拦下（与 game-battle 同口径：去首尾空白后比较、按字节数计）。
if [[ -z "${GAME_BATTLE_TOKEN_SECRET:-}" ]]; then
  GAME_BATTLE_TOKEN_SECRET=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf "%s" "$GAME_BATTLE_TOKEN_SECRET" > run/game-battle-token-secret)
  chmod 600 run/game-battle-token-secret
fi
battle_secret_trimmed=$(printf "%s" "$GAME_BATTLE_TOKEN_SECRET" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
gate_secret_trimmed=$(printf "%s" "$GAME_GATE_TOKEN_SECRET" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
if (( $(printf "%s" "$battle_secret_trimmed" | LC_ALL=C wc -c) < 32 )); then
  echo "GAME_BATTLE_TOKEN_SECRET 去首尾空白后不足 32 字节；取消这个环境变量让脚本生成，或换一个更长的值" >&2
  exit 1
fi
if [[ "$battle_secret_trimmed" == "$gate_secret_trimmed" ]]; then
  echo "GAME_BATTLE_TOKEN_SECRET 不能与 GAME_GATE_TOKEN_SECRET 相同（两把密钥分域：拿到大厅令牌密钥的人不能伪造战斗票据）；取消它让脚本生成" >&2
  exit 1
fi
unset battle_secret_trimmed gate_secret_trimmed
export GAME_BATTLE_TOKEN_SECRET

# 对局结果 topic 的代次（批次 6.4，match-spec §5.4）：game-battle 往 game-battle-result-g<代次> 生产、game-match 从同一个 topic 消费后更新评分，
# 两边各自读这个变量、缺省都是 1；这里显式导出，免得只给其中一个进程设了别的值（两边不一致 = 结果发进一个没人消费的 topic，评分静默不更新）。
export GAME_BATTLE_RESULT_TOPIC_GENERATION="${GAME_BATTLE_RESULT_TOPIC_GENERATION:-1}"
echo "对局结果 topic 代次 GAME_BATTLE_RESULT_TOPIC_GENERATION=$GAME_BATTLE_RESULT_TOPIC_GENERATION"

# 运维写操作（批次 7.2b 回档，data-ops-spec §7.8）：game-data 进程缺省关闭（回档执行回 503 ops_disabled）；本机切片打开，
# 并把回档目标时刻下限与帮会检查的沉降 / 复查等待调小（仅本机，生产按缺省 5min / 30s / 10s）。帮会检查直连 game-guild 的 20886，
# 调用方鉴权用上面同一个 GAME_DUBBO_SECRET。robot rollback 场景依赖这几个值
export GAME_DATA_OPS_ENABLED="${GAME_DATA_OPS_ENABLED:-true}"
export GAME_DATA_OPS_MIN_TARGET_AGE="${GAME_DATA_OPS_MIN_TARGET_AGE:-5s}"
export GAME_DATA_ROLLBACK_SETTLE="${GAME_DATA_ROLLBACK_SETTLE:-3s}"
export GAME_DATA_ROLLBACK_RECHECK_DELAY="${GAME_DATA_ROLLBACK_RECHECK_DELAY:-2s}"
echo "运维写操作 GAME_DATA_OPS_ENABLED=$GAME_DATA_OPS_ENABLED（沉降 $GAME_DATA_ROLLBACK_SETTLE、复查等待 $GAME_DATA_ROLLBACK_RECHECK_DELAY）"

# 登录排队：进程缺省关闭（同基线 Queue.Enabled=false）；本机切片打开，robot 全程走快速通道，queue 场景压容量验证排队与放行
export GAME_GATEWAY_QUEUE_ENABLED="${GAME_GATEWAY_QUEUE_ENABLED:-true}"
# 开服限流：进程缺省关闭（同基线 gate.rate-limit.enabled=false）；本机切片打开（缺省阈值），ratelimit 场景验证 IP 桶与冷却
export GAME_GATEWAY_RATE_LIMIT_ENABLED="${GAME_GATEWAY_RATE_LIMIT_ENABLED:-true}"

GAME_SCENE_NODES="${GAME_SCENE_NODES:-1}"
if [[ "$GAME_SCENE_NODES" != "1" && "$GAME_SCENE_NODES" != "2" ]]; then
  echo "GAME_SCENE_NODES 只能是 1 或 2：$GAME_SCENE_NODES" >&2
  exit 1
fi
GAME_SCENE_MANAGER_URL="${GAME_SCENE_MANAGER_URL:-tri://127.0.0.1:20882}"
echo "场景节点数 GAME_SCENE_NODES=$GAME_SCENE_NODES"

GAME_ZONES="${GAME_ZONES:-1}"
if [[ "$GAME_ZONES" != "1" && "$GAME_ZONES" != "2" ]]; then
  echo "GAME_ZONES 只能是 1 或 2：$GAME_ZONES" >&2
  exit 1
fi
echo "区数 GAME_ZONES=$GAME_ZONES"

mkdir -p run/logs run/pids

# 模块名 就绪端口…（一个进程可以列多个端口，逐个等到可连再起下一个）
SERVICES=(
  "game-scene-manager 20882"
  "game-login 20881"
  "game-friend 20883"
  "game-chat 20884"
  "game-match"              # 匹配：先等 Dubbo 20888，再等凑单、观战清扫与评分消费起来（见 wait_match_ready）。它是 game-team 与 game-gate 的
                          # Dubbo 提供方，必须排在两者之前（文件头「启动次序的一条硬约束」）
  "game-team 20885"
  "game-guild 20886"
  "game-trade 20887"        # 聚宝斋；播种接口在管理端口 18111（Tomcat 先于 Dubbo 暴露就绪，等 20887 即可）
  "game-mail 20916"         # 邮件：先等 gate 直连的 Dubbo 端口
  "game-rank 20917"         # 排行榜：先于 gate 启动
  "game-data 18106"
  "game-inbox 20918"        # 收件箱：等待 Dubbo 就绪后，才启动各区的场景消费方
  "game-scene"              # 场景节点：起 GAME_SCENE_NODES 个实例，端口见下面的 SCENE_NODES；GAME_ZONES=2 时随后再起区 2 的 game-scene-z2
  "game-gate 11000"         # GAME_ZONES=2 时随后再起区 2 的 game-gate-z2（实例与端口见下面的 ZONE2_GATE）
  "game-gateway 18081"
  "game-battle"             # 战斗节点：先等控制面 21200、直连面 12000，再等准入闸打开（见 wait_battle_ready）
)

# game-battle 的端口（与 game-battle application.yaml 的缺省一致）：Dubbo 控制面、客户端直连面、管理端口（actuator 与 dev 接口 /admin/battle/dev/*）
BATTLE_RPC_PORT=21200
BATTLE_CLIENT_PORT=12000
BATTLE_MGMT_PORT=18112

# game-match 的端口（与 game-match application.yaml 的缺省一致）：Dubbo（GAME_MATCH_RPC_PORT）、管理端口（actuator 与 dev 管理口 /admin/match/dev/*）。
# game-gate（MatchService 的 12 个号）与 game-team（整队开战）按这个 Dubbo 端口直连，robot 的 --match-admin-url 缺省指着这个管理端口。
MATCH_RPC_PORT=20888
MATCH_MGMT_PORT=18113

# 场景节点实例：实例名（日志 / PID 文件名） 节点链路 link-port  资产通道 asset-rpc-port  管理端口 server.port
# 资产通道是 Dubbo Triple（game.scene.asset-rpc-port，同 GAME_SCENE_ASSET_RPC_PORT；game-guild 按节点目录直连）；管理端口同 SERVER_PORT（actuator 指标、GM 停机）。
# 同机多实例三个端口都必须各不相同（game-scene application.yaml 的约定）；端口用命令行参数传入，优先级高于配置文件与环境变量。
SCENE_NODES=(
  "game-scene   21000 21100 18104"
  "game-scene-2 21001 21101 18114"
)

# 区 2 的实例（GAME_ZONES=2 才起；端口按 zone-travel-spec §5.13：避开 game-scene-2 的 21001 / 21101 / 18114 与批次 5.1 规划的 21002 / 18124）。
# 没有并进上面的 SCENE_NODES / SERVICES：SCENE_NODES 是区 1 的节点表，battle-crash-window.sh 与它逐字相同、按「第 1 / 2 行 = 区 1 的两个节点」
# 取用；SERVICES 的十五项与次序由 game-gate 的 LocalSliceOrderTest 钉着。区 2 的两个实例在主循环里紧跟区 1 的同类起。
# 场景节点：实例名 节点链路 link-port 资产通道 asset-rpc-port 管理端口 server.port；gate：实例名 客户端端口 client-port 管理端口 server.port
ZONE2_ID=2
ZONE2_SCENE_NODE="game-scene-z2 21010 21110 18115"
ZONE2_GATE="game-gate-z2 11010 18123"

# 区服目录用到的两个 HTTP 口：game-gateway 的客户端接口（GET /api/server-list；与 SERVICES 里等它就绪的端口是同一个）、
# game-data 的管理端口（运维接口 /admin/zones；与 SERVICES 里等它就绪的端口是同一个）
GATEWAY_HTTP_PORT=18081
DATA_MGMT_PORT=18106

# 区 2 在区服目录里的那一行（sync_zone2_status 用）。建区的请求体：业务列与 game-gateway 播种一个区同口径（OPEN、容量缺省 5000、不推荐），
# 排在一区之后。置维护的文案会原样显示在客户端的区服列表里。
# 没有照 zone-travel-spec §5.13 的草案在 game-gateway 的命令行上给 seed-zones[0] / [1]：JDK 的启动器在 Windows 上按系统 ANSI 代码页取命令行，
# 代码页不是中文的机器上（本机实测 native.encoding = Cp1252）「一区」「二区」到达进程时已经是「??」，而播种只在库里没有这个区时写、写了就不再改。
# HTTP 请求体不经命令行（见 zone_admin_post），中文原样到达；game-gateway 的命令行因此两种形态下都不用动。
ZONE2_CREATE_BODY="{\"zone_id\":$ZONE2_ID,\"name\":\"二区\",\"manual_status\":0,\"sort_order\":2}"
ZONE2_MAINTENANCE_BODY='{"maintenance_msg":"本机切片 GAME_ZONES=1：没有起区 2 的 gate 与 scene（要进区 2 用 GAME_ZONES=2 重起切片）"}'

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
# 立即补发目录后才分得到。端口就绪不代表能进游戏：等管理端口上的 game_scene_channels{state="active"} ≥ 1 再起 gate / gateway。
# 每个 scene 实例各等各的（$2 是该实例的管理端口）：第二个节点的频道由领导者按 per-node 覆盖在它加入后的下一拍铺上。
wait_world_channels() {
  local name=$1 port=$2 deadline=$((SECONDS + 60)) active
  until active=$(curl -fsS "http://127.0.0.1:$port/actuator/prometheus" 2>/dev/null \
      | grep -E '^game_scene_channels\{.*state="active"' | awk '{print int($NF)}' | head -1) && [[ "${active:-0}" -ge 1 ]]; do
    if ! kill -0 "$(cat "run/pids/$name.pid")" 2>/dev/null; then
      echo "[$name] 进程已退出，看 run/logs/$name.log" >&2
      return 1
    fi
    if (( SECONDS > deadline )); then
      echo "[$name] 60s 内没有铺好主世界频道（看 run/logs/game-scene-manager.log 的 world 领导者日志）" >&2
      return 1
    fi
    sleep 1
  done
  # 节点应用计划后立即补发目录；再给一拍余量让 scene-manager 读到
  sleep 1
}

# 依次起 GAME_SCENE_NODES 个场景节点，每个都等链路 / 资产通道端口与主世界频道就绪再起下一个
start_scene_nodes() {
  local i instance link rpc mgmt
  for ((i = 0; i < GAME_SCENE_NODES; i++)); do
    read -r instance link rpc mgmt <<<"${SCENE_NODES[$i]}"
    launch "$instance" game-scene --server.port="$mgmt" --game.scene.link-port="$link" \
        --game.scene.asset-rpc-port="$rpc" --game.scene.scene-manager-url="$GAME_SCENE_MANAGER_URL"
    wait_port "$link" "$instance"
    wait_port "$rpc" "$instance"
    wait_world_channels "$instance" "$mgmt"
    echo "  $instance 就绪（链路 $link、资产通道 $rpc、管理端口 $mgmt）"
  done
}

# 起区 2 的场景节点（GAME_ZONES=2）：命令行比区 1 的多一个 --game.zone-id，就绪判断相同（链路 / 资产通道端口，再等主世界频道）。
# 区 2 第一次出现时 scene-manager 要先为它竞选频道计划的领导者：节点启动时把 zone 登记进 game:world:zones，领导者下一拍（≤ 5 s）看到、
# 当选的那一拍就铺频道，仍在 wait_world_channels 的 60 s 之内。
start_zone2_scene() {
  local instance link rpc mgmt
  read -r instance link rpc mgmt <<<"$ZONE2_SCENE_NODE"
  launch "$instance" game-scene --game.zone-id="$ZONE2_ID" --server.port="$mgmt" --game.scene.link-port="$link" \
      --game.scene.asset-rpc-port="$rpc" --game.scene.scene-manager-url="$GAME_SCENE_MANAGER_URL"
  wait_port "$link" "$instance"
  wait_port "$rpc" "$instance"
  wait_world_channels "$instance" "$mgmt"
  echo "  $instance 就绪（区 $ZONE2_ID：链路 $link、资产通道 $rpc、管理端口 $mgmt）"
}

# 起区 2 的 gate（GAME_ZONES=2）：只换 zone 与两个端口，其余（Dubbo 后端地址、密钥）与区 1 的 gate 相同。它只连本 zone 的场景节点，
# 所以排在 game-scene-z2 之后；就绪判断同 game-gate（等客户端端口）。
start_zone2_gate() {
  local instance client mgmt
  read -r instance client mgmt <<<"$ZONE2_GATE"
  launch "$instance" game-gate --game.zone-id="$ZONE2_ID" --game.gate.client-port="$client" --server.port="$mgmt"
  wait_port "$client" "$instance"
  echo "  $instance 就绪（区 $ZONE2_ID：客户端 $client、管理端口 $mgmt）"
}

# 调 game-data 的区服运维接口：POST $1（路径），请求体 $2（JSON）；输出 HTTP 状态码（连不上 / 没有应答时是 000）。
# 令牌、操作人与请求体写在标准输入上的 curl 配置里，不上命令行（进程列表里看不到令牌，请求体里的中文也不经命令行的代码页转换）。
# 操作人头是必填的（game-data 的 AdminAuthFilter），会进它的运维审计日志。
zone_admin_post() {
  local path=$1 body=$2 token
  # curl 配置里双引号串的转义只有反斜杠与双引号两样
  token=${GAME_ADMIN_TOKEN//\\/\\\\}
  token=${token//\"/\\\"}
  body=${body//\\/\\\\}
  body=${body//\"/\\\"}
  curl -sS -o /dev/null -w '%{http_code}' -K - 2>/dev/null <<EOF || true
url = "http://127.0.0.1:$DATA_MGMT_PORT$path"
header = "X-Game-Admin-Token: $token"
header = "X-Game-Operator: start-slice"
header = "Content-Type: application/json"
data-binary = "$body"
EOF
}

# 区服列表里某个区的那一项（$1 区号）：GET /api/server-list 应答里这个区的 JSON 片段。列表里没有这个区、gateway 没有应答都输出空串并返回非 0。
# 应答是一行紧凑 JSON、每个区一个不嵌套的对象：按「{」拆行后取含这个 zone_id 的那一行。
zone_list_entry() {
  curl -fsS "http://127.0.0.1:$GATEWAY_HTTP_PORT/api/server-list" 2>/dev/null \
      | tr '{' '\n' | grep -E "\"zone_id\":$1[,}]" | head -1
}

# 区 2 在区服目录里的那一行跟随 GAME_ZONES（批次 6.5 的裁决），全部进程就绪之后调一次，经 game-data 的运维接口改：
#   =2 → POST /admin/zones/2/open；回 404（库里还没有区 2：第一次起双 zone 切片）就 POST /admin/zones 把它建出来。
#        再等区服列表里区 2 显示 OPEN 并且带负载档 load_level：负载档只在 game-gateway 的健康探测（每 5 s 一轮）看到这个区有 gate 之后才下发，
#        所以它同时说明 game-gate-z2 已经进了节点目录（手工 OPEN 而探测不到 gate 时列表显示的是 MAINTENANCE）。
#        robot battle-cross-zone 的第一步就要求区 1、2 都是 OPEN。
#   =1 → POST /admin/zones/2/maintenance；回 404（没起过双 zone 切片）就什么都不做。再等区服列表里区 2 显示 MAINTENANCE（区服目录缓存 1 s）。
# 失败返回 1（脚本随之以非 0 退出、不报「全部就绪」）：这时进程都已经起来，只是区 2 的状态与 GAME_ZONES 不一致。
sync_zone2_status() {
  local want action code entry deadline note=""
  if [[ "$GAME_ZONES" == "2" ]]; then
    want=OPEN
    action="/admin/zones/$ZONE2_ID/open"
    code=$(zone_admin_post "$action" '{}')
    if [[ "$code" == "404" ]]; then
      action="/admin/zones"
      code=$(zone_admin_post "$action" "$ZONE2_CREATE_BODY")
      note="；库里原来没有这个区，已建"
    fi
  else
    want=MAINTENANCE
    action="/admin/zones/$ZONE2_ID/maintenance"
    code=$(zone_admin_post "$action" "$ZONE2_MAINTENANCE_BODY")
    if [[ "$code" == "404" ]]; then
      return 0
    fi
  fi
  if [[ "$code" != "200" ]]; then
    echo "[区服目录] game-data 的运维接口 POST $action 返回 $code（000 = 连不上，401 = 令牌不符，503 = game-data 没拿到 GAME_ADMIN_TOKEN；看 run/logs/game-data.log）。进程都已起来，停止：tools/local/stop-slice.sh" >&2
    return 1
  fi
  deadline=$((SECONDS + 30))
  until entry=$(zone_list_entry "$ZONE2_ID") && [[ "$entry" == *"\"status\":\"$want\""* ]] \
      && [[ "$want" != "OPEN" || "$entry" == *'"load_level":'* ]]; do
    if (( SECONDS > deadline )); then
      # 只留这个区自己的那一段（去掉对象结尾之后的部分）
      entry=${entry%%\}*}
      echo "[区服目录] 区 $ZONE2_ID 已置 $want，但 30s 内 GET /api/server-list 里它一直是「${entry:-没有这一项}」（=2 时要 status 是 OPEN 并且带 load_level：多半是 game-gateway 的健康探测没有看到区 $ZONE2_ID 的 gate，看 run/logs/game-gate-z2.log 与 run/logs/game-gateway.log）。进程都已起来，停止：tools/local/stop-slice.sh" >&2
      return 1
    fi
    sleep 1
  done
  echo "  区服目录：区 $ZONE2_ID 置 $want（GAME_ZONES=$GAME_ZONES$note），区服列表已反映"
}

# game-battle 就绪（batch 6.2）：管理端口的 Tomcat 先于节点就绪（/actuator/health 报 UP 比节点就绪早约 2.5 s），不能看 health。
# 节点按「导出 Dubbo（21200）→ 绑直连面（12000）→ 在逻辑线程上开准入闸 → 发布目录 → 打就绪日志」的顺序起来：两个端口都能连之后，
# 再等管理端口上的 game_battle_admission_phase = 1（open），并且日志里出现「节点已就绪」——准入闸打开之后节点还要同步发布一次目录才置
# running，在那之前 dev 管理接口一律回 503（robot battle / battle-edge 靠它建房），只看准入闸会偶发早到（评审意见）。
wait_battle_ready() {
  local name=game-battle deadline=$((SECONDS + 60)) phase
  wait_port "$BATTLE_RPC_PORT" "$name"
  wait_port "$BATTLE_CLIENT_PORT" "$name"
  until phase=$(curl -fsS "http://127.0.0.1:$BATTLE_MGMT_PORT/actuator/prometheus" 2>/dev/null \
      | grep -E '^game_battle_admission_phase(\{| )' | awk '{print int($NF)}' | head -1) && [[ "${phase:-0}" -eq 1 ]] \
      && grep -q "节点已就绪" "run/logs/$name.log" 2>/dev/null; do
    if ! kill -0 "$(cat "run/pids/$name.pid")" 2>/dev/null; then
      echo "[$name] 进程已退出，看 run/logs/$name.log" >&2
      return 1
    fi
    if (( SECONDS > deadline )); then
      echo "[$name] 60s 内准入闸没有打开（game_battle_admission_phase=${phase:-?}，看 run/logs/$name.log 的「节点已就绪」）" >&2
      return 1
    fi
    sleep 1
  done
}

# game-match 就绪（批次 6.4，match-spec §9.8）：进程按「密钥 / 配置表 / 预算检查 → 占发号租约 → 建评分表 → 导出 Dubbo（20888）→ 起凑单 →
# 起评分消费 → 打就绪日志」的顺序起来，任何一步失败即退出。只等端口不够：端口在导出 Dubbo 那一步就开了，而凑单或评分消费起不来时
# 进程随即关闭上下文退出——只看端口会把一个正在退出的进程报成就绪。所以端口能连之后再等日志里出现「match 已就绪」
# （game-match 的 MatchLifecycle 在凑单、观战清扫与评分消费都起来之后打这一行；改那行日志的措辞要同步这里）。
# 不等的两样：Kafka（不可达时评分消费后台每 30 s 重试，不拦启动）；game-battle（它排在后面，就绪之前凑单暂停、队列原样保留）。
wait_match_ready() {
  local name=game-match deadline
  wait_port "$MATCH_RPC_PORT" "$name"
  deadline=$((SECONDS + 60))
  until grep -q "match 已就绪" "run/logs/$name.log" 2>/dev/null; do
    if ! kill -0 "$(cat "run/pids/$name.pid")" 2>/dev/null; then
      echo "[$name] 进程已退出，看 run/logs/$name.log" >&2
      return 1
    fi
    if (( SECONDS > deadline )); then
      echo "[$name] 端口 $MATCH_RPC_PORT 已开，但 60s 内日志里没有出现「match 已就绪」（凑单 / 观战清扫 / 评分消费没起来？看 run/logs/$name.log）" >&2
      return 1
    fi
    sleep 1
  done
}

for entry in "${SERVICES[@]}"; do
  read -r name ports <<<"$entry"
  if [[ "$name" == "game-scene" ]]; then
    start_scene_nodes
    if [[ "$GAME_ZONES" == "2" ]]; then
      start_zone2_scene
    fi
    continue
  fi
  if [[ "$name" == "game-battle" ]]; then
    launch game-battle game-battle
    wait_battle_ready
    echo "  game-battle 就绪（控制面 $BATTLE_RPC_PORT、直连面 $BATTLE_CLIENT_PORT、管理端口 $BATTLE_MGMT_PORT，准入闸已开）"
    continue
  fi
  if [[ "$name" == "game-match" ]]; then
    launch game-match game-match
    wait_match_ready
    echo "  game-match 就绪（Dubbo $MATCH_RPC_PORT、管理端口 $MATCH_MGMT_PORT，凑单、观战清扫与评分消费已启动）"
    continue
  fi
  launch "$name" "$name"
  for port in $ports; do
    wait_port "$port" "$name"
  done
  echo "  $name 就绪（端口 $ports）"
  if [[ "$name" == "game-gate" && "$GAME_ZONES" == "2" ]]; then
    start_zone2_gate
  fi
done

sync_zone2_status

echo "全部就绪（场景节点 $GAME_SCENE_NODES 个、区 $GAME_ZONES 个）。停止：tools/local/stop-slice.sh"
