#!/usr/bin/env bash
# battle-settle 的故障变体（docs/porting/scene-battle-spec.md §13.8「故障变体」）：对**正在跑的本机切片** kill -9 一个进程，再按 start-slice.sh 的方式
# 单独把它重启，前后各调一次 robot。robot 只做客户端（打到断点、重登核对），杀进程与重启都在这里。在仓库根目录执行：
#
#   tools/local/battle-crash-window.sh scene-after-150      # 大厅收到 150 之后立即 kill -9 xm-scene → 重启 → 重登：金币恰好增一次、150 至多一份
#   tools/local/battle-crash-window.sh battle-after-store   # 结算落库之后、大厅 150 之前 kill -9 xm-battle → 期限 + 10 s 时 scene 的 rescue 到账
#                                                           # → 重启 xm-battle → 重登核对（D21）
#   tools/local/battle-crash-window.sh <变体> --dry-run     # 只检查前置、打印将要做的事；不跑 robot、不杀进程、不重启
#   tools/local/battle-crash-window.sh <变体> --no-restart  # 杀掉之后不重启、不跑重登核对（留着现场看日志；之后要自己把切片整片重启）
#   tools/local/battle-crash-window.sh <变体> -- --run-tag t1 --gateway http://127.0.0.1:18081   # 「--」之后的参数原样传给 robot 的两次调用
#
# 前置（缺一样就在动手之前退出，什么都不杀）：
#   1. 切片是 tools/local/start-slice.sh 起的、正在跑（run/pids/ 里有 PID 文件），dev 运行模式（robot 要用 dev gather 与 GM 94 / 95），
#      xm-scene 的 reaper 间隔是切片缺省的 2s（battle-after-store 等的就是它；生产缺省 30s 时要多等半分钟）。
#   2. 在**启动切片的同一个 shell**里执行，或导出同一组环境变量：XM_MYSQL_PASSWORD、XM_GATE_TOKEN_SECRET、XM_LOGIN_DEV_PASSWORD、
#      XM_NODE_LINK_SECRET、XM_DUBBO_SECRET，以及当时显式设过的任何一个 XM_*（运行模式、reaper 间隔……）。重启出来的进程读的是本脚本的环境，
#      环境不同它就和被杀的那个不是同一种配置。start-slice.sh 自己生成的四个秘密（运维令牌、GM 停机密钥、帮会资产签名密钥、战斗票据密钥）
#      当时没设环境变量的话，本脚本从 run/ 下的文件读回同一个值；当时是从环境变量来的，这里也必须有同一个环境变量。
#   3. 已执行 ./mvnw -DskipTests install（要 xm-robot、xm-scene / xm-battle 的可执行 jar）。xm-robot 的旧包会被拒绝，三道检查：帮助里认
#      --crash-window；帮助里报的故障变体判定版本 [crash-window-rev=N] 等于本脚本的 ROBOT_CRASH_REVISION（下面两段承诺的行为是这一版才有的）；
#      jar 不比 xm-robot 的源码 / pom 旧（改了源码没重新打包）。
#   4. scene-after-150 只支持单 scene 切片（XM_SCENE_NODES=1）：两个节点时不知道玩家落在哪一个，杀错了节点结论是假的，所以直接拒绝。
#      battle-after-store 两种切片都行：第二个 scene 节点在跑时，本脚本把两个节点的管理端口都传给 robot 的 --scene-metrics-url
#      （rescues{applied} 按节点之和判定；「--」之后显式给了 --scene-metrics-url 或设了 XM_ROBOT_SCENE_METRICS_URL 的以它为准）。
#
# 做的事（两个变体相同的骨架）：
#   a. 后台跑 robot 的 arm 阶段（battle-settle --crash-window <变体> --crash-phase arm），等它在断点处原子地写出状态文件
#      run/battle-crash-window.state；robot 没到断点就退出 → 打印它的日志、退出，**不杀任何进程**。等断点超时（300 s）或脚本中途退出时
#      先结束 robot 的 JVM 并等它退出，再删掉它可能刚写出的状态文件：没有人按它杀过进程的断点不留给 verify 或下一轮。
#   b. 状态文件一出现就 kill -9 目标进程（PID 取自 run/pids/），确认进程号不在、端口连不上。
#   c. 等 robot 的 arm 阶段退出（scene-after-150 约 8 s；battle-after-store 要留在线上看完 rescue，约 2–3 分钟）。
#   d. 按 start-slice.sh 的同一套命令行与就绪判断重启目标进程（launch / wait_port / wait_world_channels / wait_battle_ready 与它逐字相同，
#      xm-robot 的 SliceScriptsTest 钉住两份脚本不走样）。被杀进程的日志先另存为 run/logs/<实例名>.before-kill.log。
#   e. 跑 robot 的 verify 阶段（--crash-phase verify）：重登核对。
# 退出码：0 = 两个阶段都通过；1 = 有检查失败 / 流程中断；2 = 用法错误或前置不满足。robot 的输出在 run/logs/battle-crash-window-{arm,verify}.log。
#
# 两个窗口各自能说明什么（robot 的汇总行 BATTLE_CRASH_OK … outcome=… 里写明落在了哪一种）：
#   scene-after-150     kill 落在「已应用、还没落盘」→ outcome=recovered（重登后进场恢复按待结算记录重放，恰好一条 150）；
#                       落在落盘之后 → outcome=durable（重登不重发）。两种都要求金币恰好增一次。落在哪一种由时序决定，脚本只保证尽快杀。
#   battle-after-store  「落库之后、投递之前」在 battle 进程里只有几毫秒，从进程外杀不中。robot 先用 GM 封禁玩家自己的金币获取，让首投被 scene 延后
#                       （零副作用），把「已落库、未应用」的状态一直撑到 battle 被杀；然后解封，等 scene 的 reaper 在期限 + 10 s 时读到记录并应用。
#                       outcome=rescued 才算通过：arm 看到 early / missing 时自己失败，verify 读到这样的状态文件也停在 state 步（不会报 OK）。
# 两个变体都拿金币当「恰好一次」的见证：这一局必须打赢且 gold_gain > 0（打输、平局、本人阵亡时 gold_gain = 0，durable 与整笔丢失就分不开）。
# 不满足时 robot 的 arm 停在 fight 步、不写状态文件，本脚本按「没到断点就退出」处理，不杀任何进程；verify 读到 gold_gain=0 的状态文件也停在 state 步。
#
# Windows（Git Bash）：run/pids/ 里记的是 MSYS 进程号，kill -9 杀的是它；万一原生的 java 进程还活着（端口还连得上），再按
# /proc/<pid>/winpid 记下的 Windows 进程号 taskkill 一次。robot 的 JVM 选项经 XM_ROBOT_JAVA_OPTS 传（如 -Dstdout.encoding=UTF-8）。
set -euo pipefail

cd "$(dirname "$0")/../.."

usage() {
  sed -n '2,10p' "$0" | sed -e 's/^# \{0,1\}//'
}

VARIANT=""
DRY_RUN=0
NO_RESTART=0
ROBOT_ARGS=()
while (( $# > 0 )); do
  case "$1" in
    scene-after-150|battle-after-store)
      if [[ -n "$VARIANT" ]]; then
        echo "只能给一个变体：已有 $VARIANT，又给了 $1" >&2
        exit 2
      fi
      VARIANT=$1
      ;;
    --dry-run) DRY_RUN=1 ;;
    --no-restart) NO_RESTART=1 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; ROBOT_ARGS=("$@"); break ;;
    *)
      echo "不认识的参数：$1（传给 robot 的参数写在「--」之后）" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done
if [[ -z "$VARIANT" ]]; then
  echo "缺少变体：scene-after-150 或 battle-after-store" >&2
  usage >&2
  exit 2
fi

# ---------------------------------------------------------------- 环境：与 start-slice.sh 同一套（重启出来的进程读这里的环境）

# start-slice.sh 必需的五个（它用 : "${名字:?}" 检查）；这里缺了按「前置不满足」退出 2。robot 也读 XM_LOGIN_DEV_PASSWORD
for required in XM_MYSQL_PASSWORD XM_GATE_TOKEN_SECRET XM_LOGIN_DEV_PASSWORD XM_NODE_LINK_SECRET XM_DUBBO_SECRET; do
  if [[ -z "${!required:-}" ]]; then
    echo "需要环境变量 $required（与启动切片时相同的值；在启动切片的同一个 shell 里执行本脚本）" >&2
    exit 2
  fi
done

# 下面这些的缺省值与 start-slice.sh 逐个相同；启动切片时显式设过别的值，这里也要设成那个值
export XM_RUN_MODE="${XM_RUN_MODE:-dev}"
export XM_SCENE_MIRROR_IDLE_TIMEOUT="${XM_SCENE_MIRROR_IDLE_TIMEOUT:-5s}"
export XM_SCENE_INSTANCE_RECLAIM_GRACE="${XM_SCENE_INSTANCE_RECLAIM_GRACE:-10s}"
export XM_SCENE_BATTLE_REAPER_INTERVAL="${XM_SCENE_BATTLE_REAPER_INTERVAL:-2s}"
export XM_DATA_OPS_ENABLED="${XM_DATA_OPS_ENABLED:-true}"
export XM_DATA_OPS_MIN_TARGET_AGE="${XM_DATA_OPS_MIN_TARGET_AGE:-5s}"
export XM_DATA_ROLLBACK_SETTLE="${XM_DATA_ROLLBACK_SETTLE:-3s}"
export XM_DATA_ROLLBACK_RECHECK_DELAY="${XM_DATA_ROLLBACK_RECHECK_DELAY:-2s}"
export XM_GATEWAY_QUEUE_ENABLED="${XM_GATEWAY_QUEUE_ENABLED:-true}"
export XM_GATEWAY_RATE_LIMIT_ENABLED="${XM_GATEWAY_RATE_LIMIT_ENABLED:-true}"
XM_SCENE_MANAGER_URL="${XM_SCENE_MANAGER_URL:-tri://127.0.0.1:20882}"

if [[ "$XM_RUN_MODE" != "dev" && "$XM_RUN_MODE" != "test" ]]; then
  echo "XM_RUN_MODE=$XM_RUN_MODE：故障变体要 dev / test 运行模式（dev gather 与 GM 指令在 prod 下关闭）" >&2
  exit 2
fi

# start-slice.sh 生成的秘密：环境变量优先（与它相同的口径），否则从它写下的文件读回同一个值；两头都没有就不是它起的切片。
# 只判断有没有、读进环境，不打印取值。
load_secret() {
  local name=$1 file=$2
  if [[ -n "${!name:-}" ]]; then
    export "$name"
    return 0
  fi
  if [[ ! -s "$file" ]]; then
    echo "没有环境变量 $name，也没有 $file：切片不是 tools/local/start-slice.sh 起的，或者当时这个值是从环境变量来的（那就在这里导出同一个值）" >&2
    return 1
  fi
  export "$name=$(cat "$file")"
}
load_secret XM_ADMIN_TOKEN run/xm-admin-token || exit 2
load_secret XM_GM_ADMIN_SECRET run/xm-gm-admin-secret || exit 2
load_secret XM_ASSET_OP_SECRET_GUILD run/xm-asset-op-secret-guild || exit 2
load_secret XM_BATTLE_TOKEN_SECRET run/xm-battle-token-secret || exit 2

# ---------------------------------------------------------------- 与 start-slice.sh 逐字相同的部分（端口表、启动与就绪判断）

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

# xm-battle 就绪（batch 6.2）：管理端口的 Tomcat 先于节点就绪（/actuator/health 报 UP 比节点就绪早约 2.5 s），不能看 health。
# 节点按「导出 Dubbo（21200）→ 绑直连面（12000）→ 在逻辑线程上开准入闸 → 发布目录 → 打就绪日志」的顺序起来：两个端口都能连之后，
# 再等管理端口上的 xm_battle_admission_phase = 1（open），并且日志里出现「节点已就绪」——准入闸打开之后节点还要同步发布一次目录才置
# running，在那之前 dev 管理接口一律回 503（robot battle / battle-edge 靠它建房），只看准入闸会偶发早到（评审意见）。
wait_battle_ready() {
  local name=xm-battle deadline=$((SECONDS + 60)) phase
  wait_port "$BATTLE_RPC_PORT" "$name"
  wait_port "$BATTLE_CLIENT_PORT" "$name"
  until phase=$(curl -fsS "http://127.0.0.1:$BATTLE_MGMT_PORT/actuator/prometheus" 2>/dev/null \
      | grep -E '^xm_battle_admission_phase(\{| )' | awk '{print int($NF)}' | head -1) && [[ "${phase:-0}" -eq 1 ]] \
      && grep -q "节点已就绪" "run/logs/$name.log" 2>/dev/null; do
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

# 重启第一个场景节点：命令行与 start-slice.sh 的 start_scene_nodes 相同，等链路 / 资产通道端口与主世界频道就绪
restart_scene_node() {
  local instance link rpc mgmt
  read -r instance link rpc mgmt <<<"${SCENE_NODES[0]}"
  launch "$instance" xm-scene --server.port="$mgmt" --xm.scene.link-port="$link" \
      --xm.scene.asset-rpc-port="$rpc" --xm.scene.scene-manager-url="$XM_SCENE_MANAGER_URL"
  wait_port "$link" "$instance"
  wait_port "$rpc" "$instance"
  wait_world_channels "$instance" "$mgmt"
  echo "  $instance 就绪（链路 $link、资产通道 $rpc、管理端口 $mgmt）"
}

# 重启 xm-battle：与 start-slice.sh 的主循环相同
restart_battle() {
  launch xm-battle xm-battle
  wait_battle_ready
  echo "  xm-battle 就绪（控制面 $BATTLE_RPC_PORT、直连面 $BATTLE_CLIENT_PORT、管理端口 $BATTLE_MGMT_PORT，准入闸已开）"
}

# ---------------------------------------------------------------- 本脚本自己的部分

STATE="run/battle-crash-window.state"
ARM_LOG="run/logs/battle-crash-window-arm.log"
VERIFY_LOG="run/logs/battle-crash-window-verify.log"
# robot 的 arm 阶段从启动到写出断点的上限（登录 + 挂机打完一局；battle-after-store 的战斗期限是 120 s）
ARM_BREAKPOINT_TIMEOUT=300
# 本脚本按 xm-robot 故障变体的这一版判定编写（= BattleCrashChecks.STATE_VERSION，xm-robot 的 SliceScriptsTest 钉住两边相等）。robot 的帮助里报
# [crash-window-rev=N]，对不上就在动手之前拒绝：脚本头承诺的行为（gold_gain = 0 不写断点、verify 只认 outcome=rescued）是第 2 版才有的
ROBOT_CRASH_REVISION=2

# 目标进程的实例名与「确认它死了」要看的端口
read -r SCENE_INSTANCE SCENE_LINK_PORT SCENE_RPC_PORT SCENE_MGMT_PORT <<<"${SCENE_NODES[0]}"
read -r SECOND_SCENE_INSTANCE _ _ SECOND_SCENE_MGMT_PORT <<<"${SCENE_NODES[1]}"
if [[ "$VARIANT" == "scene-after-150" ]]; then
  TARGET=$SCENE_INSTANCE
  TARGET_PORTS="$SCENE_LINK_PORT $SCENE_RPC_PORT $SCENE_MGMT_PORT"
else
  TARGET=xm-battle
  TARGET_PORTS="$BATTLE_RPC_PORT $BATTLE_CLIENT_PORT $BATTLE_MGMT_PORT"
fi

# PID 文件里的进程还在不在（没有 PID 文件算不在）
alive() {
  local pidfile="run/pids/$1.pid"
  [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null
}

port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# 状态文件里某个键的值（文件是「键=值」逐行文本，robot 原子写出）；没有这个键输出空串
state_field() {
  local key=$1 line
  line=$(grep -m1 "^$key=" "$STATE" 2>/dev/null || true)
  printf "%s" "${line#*=}"
}

# kill -9 之后确认目标进程真的没了：进程号不在，而且它的端口一个都连不上（$1 实例名，$2 进程号，$3 Windows 进程号或空串，其余是端口）。
# 20 s 内没死透就失败（Windows 上探一个已关闭的本机端口要 2 s 左右，三个端口探一遍就是 6 s）。
confirm_dead() {
  local name=$1 pid=$2 winpid=$3 deadline port still
  shift 3
  deadline=$((SECONDS + 20))
  while true; do
    still=""
    if kill -0 "$pid" 2>/dev/null; then
      still="进程号 $pid 还在"
    else
      for port in "$@"; do
        if port_open "$port"; then
          still="端口 $port 还连得上"
          break
        fi
      done
    fi
    if [[ -z "$still" ]]; then
      return 0
    fi
    if [[ -n "$winpid" ]] && command -v taskkill >/dev/null 2>&1; then
      # Git Bash：MSYS 的包装进程死了，原生进程还在 → 按 Windows 进程号再杀一次（只试一次）
      taskkill //F //PID "$winpid" >/dev/null 2>&1 || true
      winpid=""
    fi
    if (( SECONDS > deadline )); then
      echo "[$name] kill -9 之后 20s 仍没有死透：$still" >&2
      return 1
    fi
    sleep 0.2
  done
}

# 断点信号：状态文件已出现并且写着 stage=armed（robot 先写临时文件再原子改名，出现时内容已完整；battle-after-store 的 arm 阶段后面还会把它
# 改写成 observed）。只用 bash 内建，不起子进程：Git Bash 里起一个 grep 要几十毫秒，都算在「断点 → kill」的延迟里。
at_breakpoint() {
  local content
  [[ -f "$STATE" ]] || return 1
  content=$'\n'"$(<"$STATE")"$'\n'
  [[ "$content" == *$'\nstage=armed\n'* || "$content" == *$'\nstage=observed\n'* ]]
}

# 等 robot 的 arm 阶段写出断点（$1 robot 的进程号），**一到断点立刻 kill -9 目标进程**（先杀、后说话；$2 目标的进程号）。
# 返回 0 = 断点已到、已发出 kill -9；1 = robot 没到断点就退出了；2 = 超时。后两种什么都没杀。
# KILLED 在发 kill -9 之前置位（不等返回之后）：脚本恰好在这之间被打断时，on_exit 仍知道目标已经被杀——要提醒重启切片，也不能删状态文件。
kill_at_breakpoint() {
  local robot_pid=$1 target_pid=$2 deadline=$((SECONDS + ARM_BREAKPOINT_TIMEOUT))
  while true; do
    if at_breakpoint; then
      KILLED=1
      kill -9 "$target_pid" 2>/dev/null || true
      return 0
    fi
    if ! kill -0 "$robot_pid" 2>/dev/null; then
      # 写文件与退出之间有先后：robot 刚退出时再看一眼
      if at_breakpoint; then
        KILLED=1
        kill -9 "$target_pid" 2>/dev/null || true
        return 0
      fi
      return 1
    fi
    if (( SECONDS > deadline )); then
      return 2
    fi
    sleep 0.05
  done
}

# 把 robot 的命令行（$1 阶段 arm / verify）放进数组 ROBOT_CMD；口令走环境变量 XM_LOGIN_DEV_PASSWORD，运维令牌走上面导出的 XM_ADMIN_TOKEN。
# 故意不做成「跑一次 robot」的函数：arm 要放到后台跑，而函数放后台时 $! 是包着函数的子 shell、java 是它的子进程——kill "$ROBOT_PID"
# 只杀掉子 shell，JVM 被过继后继续跑，脚本已经报「没有杀任何进程」退出了它还在打，之后写出的断点会被下一轮脚本当成自己的。
# （本机 bash 5.3 实测：函数体是「local …; java …」两条时 bash 恰好省掉这次 fork，$! 就是 JVM；函数体只有一条命令时不省。
# 这是随 bash 版本与函数写法变的优化，不能指望。）调用处直接 "${ROBOT_CMD[@]}" … &，$! 在哪一版 bash 上都是 JVM 自己。
# SCENE_METRICS_ARGS 排在「--」之后的参数（ROBOT_ARGS）之前：robot 对同一个选项取最后一次，调用者显式给的仍然算数。
robot_cmd() {
  local phase=$1
  # shellcheck disable=SC2206
  ROBOT_CMD=(java ${XM_ROBOT_JAVA_OPTS:-} -jar "$ROBOT_JAR" battle-settle --crash-window "$VARIANT" --crash-phase "$phase" --crash-state "$STATE"
      ${SCENE_METRICS_ARGS[@]+"${SCENE_METRICS_ARGS[@]}"} ${ROBOT_ARGS[@]+"${ROBOT_ARGS[@]}"})
}

# 结束还在跑的 robot（arm 等断点超时 / 脚本中途退出）并等它退出：返回之后不会再有人写状态文件。ROBOT_PID 是 JVM 自己（见 robot_cmd）。
# 只发 SIGTERM（Git Bash 下对原生进程即终止）；10 s 内没退出就返回 1，由调用方照常收尾。
stop_robot() {
  local pid=$ROBOT_PID deadline=$((SECONDS + 10))
  if [[ -z "$pid" ]]; then
    return 0
  fi
  ROBOT_PID=""
  kill "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null; do
    if (( SECONDS > deadline )); then
      echo "robot（PID $pid）收到 SIGTERM 后 10s 内没有退出：手工结束它；它之后写出的 $STATE 不可信" >&2
      return 1
    fi
    sleep 0.2
  done
  wait "$pid" 2>/dev/null || true
}

# ---------------------------------------------------------------- 前置检查（这一段之前没有动过任何东西）

ROBOT_JAR=$(ls xm-robot/target/xm-robot-*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)
problems=0
if [[ -z "$ROBOT_JAR" ]]; then
  echo "找不到 xm-robot 的可执行 jar，先 ./mvnw -DskipTests install" >&2
  problems=1
else
  # 旧包有两种，现在就说清楚：
  #   不认故障变体选项的——arm 阶段会以「未知选项」退出，看起来像没到断点；
  #   认这个选项、判定却已经过期的——跑得起来，但脚本头承诺的行为它没有（2026-10-06 实例：jar 比 BattleCrashChecks / BattleCrashScenario
  #   早半小时，缺少「打赢且 gold_gain > 0 才写断点」「verify 只认 rescued」，verify 会写出 BATTLE_CRASH_OK … outcome=early）。
  # 帮助里的两个标记都是纯 ASCII：robot 的标准输出编码不定，中文可能是乱码，不能按中文措辞匹配。
  # shellcheck disable=SC2086
  robot_help=$(java ${XM_ROBOT_JAVA_OPTS:-} -jar "$ROBOT_JAR" --help 2>/dev/null || true)
  if [[ "$robot_help" != *"--crash-window"* ]]; then
    echo "xm-robot 的 jar（$ROBOT_JAR）不认 --crash-window：是旧包（或 java 跑不起来），重新 ./mvnw -DskipTests install" >&2
    problems=1
  elif [[ "$robot_help" != *"[crash-window-rev=$ROBOT_CRASH_REVISION]"* ]]; then
    echo "xm-robot 的 jar（$ROBOT_JAR）的故障变体判定不是本脚本要的第 $ROBOT_CRASH_REVISION 版（帮助里没有 [crash-window-rev=$ROBOT_CRASH_REVISION]）：是旧包，重新 ./mvnw -DskipTests install" >&2
    problems=1
  fi
  unset robot_help
  # 改了 xm-robot 的源码 / pom 而没有重新打包：jar 里跑的不是眼前这份判定（判定版本没来得及加一的改动靠这一条拦）。
  # 只看文件（目录的修改时间会被编辑器的临时文件带动）。find 自己失败（例如 PATH 里先找到的不是 POSIX 的 find）按「查不了」拒绝，
  # 不当成「不旧」放行
  if ! robot_newer=$(find xm-robot/src/main xm-robot/pom.xml -type f -newer "$ROBOT_JAR" 2>/dev/null); then
    echo "查不了 xm-robot 的 jar 与源码谁新谁旧（find 失败；Git Bash 下确认 PATH 里先找到的是 /usr/bin/find）" >&2
    problems=1
  elif [[ -n "$robot_newer" ]]; then
    echo "xm-robot 的 jar（$ROBOT_JAR）比源码旧（如 ${robot_newer%%$'\n'*}）：改了源码没有重新打包，重新 ./mvnw -DskipTests install" >&2
    problems=1
  fi
  unset robot_newer
fi
for module in xm-scene xm-battle; do
  if [[ -z "$(ls "$module"/target/"$module"-*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)" ]]; then
    echo "找不到 $module 的可执行 jar，先 ./mvnw -DskipTests install" >&2
    problems=1
  fi
done
for name in "$SCENE_INSTANCE" xm-battle xm-gate xm-gateway; do
  if ! alive "$name"; then
    echo "[$name] 没在运行（run/pids/$name.pid 不存在或进程已退出）：先 tools/local/start-slice.sh" >&2
    problems=1
  fi
done
if [[ "$VARIANT" == "scene-after-150" ]] && alive "$SECOND_SCENE_INSTANCE"; then
  echo "[$SECOND_SCENE_INSTANCE] 在运行：scene-after-150 只支持单 scene 切片（两个节点时不知道玩家落在哪一个）。用 XM_SCENE_NODES=1 重起切片" >&2
  problems=1
fi
if (( problems != 0 )); then
  exit 2
fi

# battle-after-store 的 arm 要抓 scene 的 rescues{applied}（robot 的 --scene-metrics-url，缺省只有第一个节点的管理端口）：玩家落在哪个节点
# 不确定，第二个节点在跑就把两个管理端口都给 robot（它按节点之和判定），否则玩家落在第二个节点时这条指标必然「没有增长」。
# 调用者用环境变量 XM_ROBOT_SCENE_METRICS_URL 指定过的不动（命令行优先于环境变量，这里再给就盖掉它了）。
SCENE_METRICS_ARGS=()
if [[ -z "${XM_ROBOT_SCENE_METRICS_URL:-}" ]] && alive "$SECOND_SCENE_INSTANCE"; then
  SCENE_METRICS_ARGS=(--scene-metrics-url "http://127.0.0.1:$SCENE_MGMT_PORT,http://127.0.0.1:$SECOND_SCENE_MGMT_PORT")
fi

echo "故障变体 $VARIANT：目标进程 $TARGET（PID $(cat "run/pids/$TARGET.pid")，端口 $TARGET_PORTS），运行模式 $XM_RUN_MODE，reaper 间隔 $XM_SCENE_BATTLE_REAPER_INTERVAL"
echo "  robot：$ROBOT_JAR battle-settle --crash-window $VARIANT --crash-phase <arm|verify> --crash-state $STATE ${SCENE_METRICS_ARGS[*]+${SCENE_METRICS_ARGS[*]}} ${ROBOT_ARGS[*]+${ROBOT_ARGS[*]}}"
echo "  状态文件 $STATE；robot 输出 $ARM_LOG、$VERIFY_LOG"
if (( NO_RESTART != 0 )); then
  echo "  --no-restart：杀掉 $TARGET 之后不重启、不跑 verify"
fi
if (( DRY_RUN != 0 )); then
  echo "--dry-run：前置满足。没有跑 robot，没有杀进程。"
  exit 0
fi

# ---------------------------------------------------------------- a. arm：打到断点

mkdir -p run/logs
rm -f "$STATE" "$STATE.tmp"
# 目标的进程号现在就读好：到断点时只剩一条内建的 kill -9
TARGET_PID=$(cat "run/pids/$TARGET.pid")
TARGET_WINPID=""
if [[ -r "/proc/$TARGET_PID/winpid" ]]; then
  TARGET_WINPID=$(cat "/proc/$TARGET_PID/winpid" 2>/dev/null || true)
fi
ROBOT_PID=""
KILLED=0
RESTARTED=0
# 没有按断点杀过进程（KILLED = 0）时，robot 留下的状态文件不能留着：它写着 stage=armed，而目标进程其实没被杀，
# 拿它手工跑 verify 会得出「重登不重发、金币恰好一次」的假结论（scene-after-150 的 outcome=durable）。先确认 robot 已退出再删。
discard_unused_breakpoint() {
  if (( KILLED == 0 )); then
    rm -f "$STATE" "$STATE.tmp"
  fi
}
on_exit() {
  if [[ -n "$ROBOT_PID" ]]; then
    # 脚本中途退出（被打断、confirm_dead 失败……）而 robot 还在跑：结束 JVM 并等它退出，不留一个之后还会写状态文件的游离进程
    if stop_robot; then
      discard_unused_breakpoint
    fi
  fi
  if (( KILLED != 0 && RESTARTED == 0 && NO_RESTART == 0 )); then
    echo "注意：$TARGET 已被 kill -9、还没有重启成功。把切片整片重启：tools/local/stop-slice.sh && tools/local/start-slice.sh" >&2
  fi
}
trap on_exit EXIT

echo "a. robot arm 阶段（后台），等断点；断点一到立刻 kill -9 $TARGET……"
robot_cmd arm
"${ROBOT_CMD[@]}" > "$ARM_LOG" 2>&1 &
ROBOT_PID=$!
breakpoint=0
kill_at_breakpoint "$ROBOT_PID" "$TARGET_PID" || breakpoint=$?
if (( breakpoint != 0 )); then
  if (( breakpoint == 2 )); then
    echo "robot 的 arm 阶段 ${ARM_BREAKPOINT_TIMEOUT}s 内没有到断点，结束它。没有杀任何进程。" >&2
    # robot 可能恰好在超时与被结束之间写出断点：等它退出之后再删
    if stop_robot; then
      discard_unused_breakpoint
    fi
  else
    echo "robot 的 arm 阶段没到断点就退出了。没有杀任何进程。" >&2
    wait "$ROBOT_PID" 2>/dev/null || true
    ROBOT_PID=""
  fi
  cat "$ARM_LOG" >&2 || true
  exit 1
fi

# ---------------------------------------------------------------- b. kill -9（已在断点那一刻发出），确认死透

KILLED=1
echo "b. 断点已到（battle_id=$(state_field battle_id) account=$(state_field account)），已 kill -9 $TARGET（PID $TARGET_PID）"
# shellcheck disable=SC2086
confirm_dead "$TARGET" "$TARGET_PID" "$TARGET_WINPID" $TARGET_PORTS
echo "  $TARGET 已死（进程号不在，端口 $TARGET_PORTS 都连不上）"

# ---------------------------------------------------------------- c. 等 arm 阶段退出

if [[ "$VARIANT" == "battle-after-store" ]]; then
  echo "c. 等 robot 看完 rescue 的结局（战斗期限 + 10 s 宽限 + reaper 间隔，约 2–3 分钟）……"
else
  echo "c. 等 robot 的 arm 阶段退出……"
fi
arm_status=0
wait "$ROBOT_PID" || arm_status=$?
ROBOT_PID=""
cat "$ARM_LOG" || true
echo "  arm 阶段退出码 $arm_status"

if (( NO_RESTART != 0 )); then
  echo "--no-restart：$TARGET 留在被杀的状态，没有跑 verify。之后把切片整片重启：tools/local/stop-slice.sh && tools/local/start-slice.sh"
  if (( arm_status != 0 )); then
    exit 1
  fi
  exit 0
fi

# ---------------------------------------------------------------- d. 重启

echo "d. 重启 $TARGET"
# launch 会从头写 run/logs/<实例名>.log：被杀的那个进程的日志先另存一份
if [[ -f "run/logs/$TARGET.log" ]]; then
  mv -f "run/logs/$TARGET.log" "run/logs/$TARGET.before-kill.log"
  echo "  被杀进程的日志另存为 run/logs/$TARGET.before-kill.log"
fi
rm -f "run/pids/$TARGET.pid"
if [[ "$VARIANT" == "scene-after-150" ]]; then
  restart_scene_node
else
  restart_battle
fi
RESTARTED=1

# ---------------------------------------------------------------- e. verify：重登核对

echo "e. robot verify 阶段"
verify_status=0
robot_cmd verify
"${ROBOT_CMD[@]}" > "$VERIFY_LOG" 2>&1 || verify_status=$?
cat "$VERIFY_LOG" || true
echo "  verify 阶段退出码 $verify_status"

if (( arm_status != 0 || verify_status != 0 )); then
  echo "故障变体 $VARIANT：失败（arm=$arm_status verify=$verify_status）" >&2
  exit 1
fi
echo "故障变体 $VARIANT：通过（arm 与 verify 的汇总行见上面的 BATTLE_CRASH_OK）"
