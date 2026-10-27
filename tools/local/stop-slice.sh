#!/usr/bin/env bash
# 停止本地竖切进程，给每个进程 20s 优雅退出。次序：先停 game-match、game-battle（理由见下），其余按启动的逆序（gateway / gate …… 最后 scene-manager）。
# 场景节点按实例名停：game-scene-2（GAME_SCENE_NODES=2 时才有）先于 game-scene；没有 PID 文件的实例跳过。
# 区 2 的两个实例（GAME_ZONES=2 时才有，批次 6.5）紧挨着区 1 的同类先停：game-gate-z2 在 game-gate 之前，game-scene-z2 在 game-scene-2 之前
# ——仍是启动的逆序（启动时它们各排在区 1 的同类之后）。本脚本不读 GAME_ZONES / GAME_SCENE_NODES：只看 PID 文件，哪种切片都用同一条命令停。
# 全部停完之后删掉 start-slice.sh 记下的切片命名空间 run/game-namespace（见文件末尾）。
#
# game-match 最先停（批次 6.4）：它是 game-battle（建房 / 销毁 / 补签）与 game-scene（备战 / 取消）的调用方，先停它，后面的进程停下时就不会有
# 开到一半的局——它停机时先停凑单、撤 Dubbo，再有界等在途的 gather（至多 10 s）跑完，这段时间 game-battle 与 game-scene 都还在。
# 它在启动次序里并不在最后（它是 game-team 与 game-gate 的 Dubbo 提供方，必须先于两者启动，见 start-slice.sh 文件头），所以这里不是简单的逆序。
# 它停了之后 game-gate 上 MatchService 的号回信封 1003、game-team 的整队开战按传输失败回 4030（match-spec §7.6），直到这两个进程也停下。
# 20s 在没有在途 gather 时足够；正开着局就停（robot 还在跑）时，Dubbo 的停服等待加上等在途 gather 可能超过 20s 而被强制结束——
# 留下的票据按 matched TTL（≤ 96 s）自愈、scene 按备战期限解冻、发号租约按 TTL 过期，不需要手工清理。
# game-battle 第二个停：除 game-match 之外没有别的进程调它（6.3 的结算是它调 game-scene），停机时作废全部房间、给观众推 166 后关直连。
set -uo pipefail

cd "$(dirname "$0")/../.."

# 严格维护分支不走下方TERM→强杀兼容路径。原普通停止语义及固定顺序保留。
# 每issuer的受控env只含PID/start/exe/source/frontier路径；v2工具核正规IssuerClosure正文。
strict_stop_and_report() {
  local plan_dir=${1:?缺少固定issuer计划目录} output=${2:?缺少永久输出目录}
  local issuer pid expected_start expected_exe expected_sha report signal_path signal_sha stage role shares_relay now deadline actual key value
  [[ -d "$plan_dir" && ! -L "$plan_dir" && ! -e "$output" ]] || { echo '严格停止计划/输出状态不符' >&2; return 1; }
  local fence=${MMORPG_PUBLICATION_FENCE_DIR:-run/maintenance/publication-fences}
  local operation=${MMORPG_MAINTENANCE_OPERATION_ID:?缺少维护operation}
  [[ "$operation" =~ ^[a-f0-9]{32}$ ]] || return 1
  mkdir -p -- "$fence"
  # 原子noclobber永久围栏；存在则保留，绝不TTL或删除。
  if [[ ! -e "$fence/$operation.closed.json" ]]; then
    (umask 077; set -o noclobber; printf '{"operationId":"%s","closed":true}\n' "$operation" > "$fence/$operation.closed.json") || return 1
  fi
  mkdir -m 700 -- "$output" || return 1
  deadline=$((SECONDS + 60))
  local count=0
  for issuer in "$plan_dir"/*.env; do
    [[ -f "$issuer" && ! -L "$issuer" ]] || { echo '缺少具名issuer计划' >&2; return 1; }
    count=$((count+1)); ((count<=1024)) || { echo 'issuer超过既有预算' >&2; return 1; }
    pid='' expected_start='' expected_exe='' expected_sha='' report='' signal_path='' signal_sha='' stage='' role='' shares_relay=''
    local -A seen=()
    while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
      [[ -z "$key" || "$key" == \#* ]] && continue
      [[ -z "${seen[$key]:-}" && -n "$value" ]] || return 1
      seen[$key]=1
      case "$key" in
        pid) pid=$value;; startTime) expected_start=$value;; executable) expected_exe=$value;; executableSha256) expected_sha=$value;; applicationReport) report=$value;; signalScript) signal_path=$value;; signalScriptSha256) signal_sha=$value;; stage) stage=$value;; logicalRole) role=$value;; sharesRelay) shares_relay=$value;;
        *) echo 'issuer计划有未知字段' >&2; return 1;;
      esac
    done < "$issuer"
    [[ "$pid" =~ ^[1-9][0-9]*$ && "$expected_sha" =~ ^[a-f0-9]{64}$ && -n "$expected_start" && -f "$expected_exe" && -n "$report" ]] || return 1
    [[ "$stage" == P1 || "$stage" == P3 ]] || { echo '严格停止必须显式P1/P3' >&2; return 1; }
    [[ "$role" == SOURCE || "$role" == RELAY ]] || return 1
    [[ "$shares_relay" == true || "$shares_relay" == false ]] || return 1
    if [[ "$stage" == P1 && ( "$role" != SOURCE || "$shares_relay" != false ) ]]; then
      echo 'P1不能停共享relay进程，先由应用logical close SOURCE给正式原件' >&2; return 1
    fi
    # 要求真实进程启动身份，不能按PID缺席当成功。Linux/proc是本分支受支持的实际身份源。
    [[ -r "/proc/$pid/stat" && -e "/proc/$pid/exe" ]] || { echo '没有受支持的进程身份源，HOLD' >&2; return 1; }
    actual=$(readlink "/proc/$pid/exe") || return 1
    [[ "$actual" == "$expected_exe" ]] || { echo 'PID可执行来源不符' >&2; return 1; }
    actual=$(sha256sum -- "$expected_exe") || return 1
    [[ "${actual%% *}" == "$expected_sha" ]] || return 1
    actual=$(awk '{print $22}' "/proc/$pid/stat") || return 1
    [[ "$actual" == "$expected_start" ]] || { echo 'PID启动世代变化' >&2; return 1; }
    case "$(uname -s)" in
      MINGW*|MSYS*|CYGWIN*)
        # Git Bash的kill可能直接终止JVM；只允许固定SHA的现有控制台优雅信号脚本，不隐式编译新helper。
        [[ -n "$signal_path" && -f "$signal_path" && "$signal_sha" =~ ^[a-f0-9]{64}$ ]] || { echo '缺确定Windows优雅信号owner，HOLD' >&2; return 1; }
        actual=$(sha256sum -- "$signal_path") || return 1
        [[ "${actual%% *}" == "$signal_sha" ]] || return 1
        pwsh -NoProfile -File "$signal_path" -Command signal-only -SignalProcessId "$pid" || return 1;;
      Linux*) kill -TERM "$pid" || return 1;;
      *) echo '未支持的严格停止平台，HOLD' >&2; return 1;;
    esac
    while kill -0 "$pid" 2>/dev/null; do
      ((SECONDS < deadline)) || { echo '严格停止超时；不强杀、不删除PID、不签排空' >&2; return 1; }
      sleep 0.1
    done
    [[ -f "$report" && ! -L "$report" ]] || { echo '进程退出但没有正式app frontier原件，HOLD' >&2; return 1; }
    [[ $(wc -c < "$report") -le 16777216 ]] || return 1
    cp -- "$report" "$output/$(basename "$issuer" .env).issuer.json" || return 1
    sha256sum -- "$output/$(basename "$issuer" .env).issuer.json" >> "$output/sha256sums.txt" || return 1
  done
  ((count>0)) || return 1
  # 只收集真实app原件，不构造角色proof或冒ROOTS_CLOSED/P4。J CLI核完整typed事实后才写SQL阶段：
  # --command phase --authority-schema <schema> --input <完整原请求> --output <新收据>。
  # P1完整roots或P3完整relay永久事实确认后，另按原RETIRE请求退休物理writer。
  printf '正式app原件已收集；尚未记录SQL阶段或执行RETIRE\n'
}

if [[ "${1:-}" == '--stop-and-report' ]]; then
  strict_stop_and_report "${2:-}" "${3:-}"
  exit $?
fi

# 停一个实例（$1 实例名 = PID 文件名）：没有 PID 文件的跳过；发 SIGTERM 后给 20s，到点强制结束
stop_one() {
  local name=$1 pidfile pid
  pidfile="run/pids/$name.pid"
  [[ -f "$pidfile" ]] || return 0
  pid=$(cat "$pidfile")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -0 "$pid" 2>/dev/null && { echo "$name 未在 20s 内退出，强制结束"; kill -9 "$pid" 2>/dev/null; }
  fi
  rm -f "$pidfile"
  echo "已停止 $name"
}

# 下面这一行的名单与次序由 game-gate 的 LocalSliceOrderTest 逐项钉着（= start-slice.sh 的 SERVICES），区 2 的实例不写进去，在循环体里带上
for name in game-match game-battle game-gateway game-gate game-scene-2 game-scene game-inbox game-data game-rank game-mail game-trade game-guild game-team game-chat game-friend game-login game-scene-manager; do
  case "$name" in
    game-gate) stop_one game-gate-z2 ;;
    game-scene-2) stop_one game-scene-z2 ;;
  esac
  stop_one "$name"
done

# 全部停完（run/pids 下没有 PID 文件了）才删切片的命名空间记录 run/game-namespace（start-slice.sh 写的）：停掉的切片不再有「生效的命名空间」，
# battle-crash-window.sh 不会拿它去重启进程；下一次 start-slice.sh 按它自己的 GAME_NAMESPACE 重新记。还有 PID 文件（不在上面名单上的实例）就留着
if ! compgen -G "run/pids/*.pid" > /dev/null; then
  rm -f run/game-namespace
fi
