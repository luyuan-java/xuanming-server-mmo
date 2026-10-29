#!/usr/bin/env bash
# 停止本地竖切进程，给每个进程 50s 优雅退出。先停 game-battle、game-match，其他按启动逆序。
# 场景节点按实例名停：game-scene-2（GAME_SCENE_NODES=2 时才有）先于 game-scene；没有 PID 文件的实例跳过。
# 区 2 的两个实例（GAME_ZONES=2 时才有，批次 6.5）紧挨着区 1 的同类先停：game-gate-z2 在 game-gate 之前，game-scene-z2 在 game-scene-2 之前
# ——仍是启动的逆序（启动时它们各排在区 1 的同类之后）。本脚本不读 GAME_ZONES / GAME_SCENE_NODES：只看 PID 文件，哪种切片都用同一条命令停。
# 全部停完之后删掉 start-slice.sh 记下的切片命名空间 run/game-namespace（见文件末尾）。
#
# game-battle 先关闭准入，再有界补交原待确认终局；SQL v1 的提交口在 game-match，故此时必须保持 match 可用。
# 关闭准入后的新 gather 被拒绝并按原事务规则收尾。随后停 game-match，最后再关 scene，保留取消备战与归属恢复路径。
# 50s 包含进程各自的有界排空与最终写回；超时被强停仍属未确认，不能拿“进程退出”冒充事务成功。
# Battle 预算耗尽仍记录逐局未知，个人奖励由 SQL v1 持久发件箱继续恢复。
set -uo pipefail

cd "$(dirname "$0")/../.."

# 停一个实例（$1 实例名 = PID 文件名）：没有 PID 文件的跳过；发 SIGTERM 后给50s，包含scene先疏散再写回。
stop_one() {
  local name=$1 pidfile pid
  pidfile="run/pids/$name.pid"
  [[ -f "$pidfile" ]] || return 0
  pid=$(cat "$pidfile")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    for _ in $(seq 1 50); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -0 "$pid" 2>/dev/null && { echo "$name 未在 50s 内退出，强制结束"; kill -9 "$pid" 2>/dev/null; }
  fi
  rm -f "$pidfile"
  echo "已停止 $name"
}

# 下面这一行的名单与次序由 game-gate 的 LocalSliceOrderTest 逐项钉着（= start-slice.sh 的 SERVICES），区 2 的实例不写进去，在循环体里带上
for name in game-battle game-match game-gateway game-gate game-scene-2 game-scene game-inbox game-data game-rank game-mail game-trade game-mall game-guild game-team game-chat game-friend game-login game-scene-manager; do
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
