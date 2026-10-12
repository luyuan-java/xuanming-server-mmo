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
for name in game-match game-battle game-gateway game-gate game-scene-2 game-scene game-data game-trade game-guild game-team game-chat game-friend game-login game-scene-manager; do
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
