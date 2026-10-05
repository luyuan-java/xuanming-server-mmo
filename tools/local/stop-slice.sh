#!/usr/bin/env bash
# 按启动的逆序停止本地竖切进程（先 gateway / gate，最后 scene-manager），给每个进程 20s 优雅退出。
# 场景节点按实例名停：xm-scene-2（XM_SCENE_NODES=2 时才有）先于 xm-scene；没有 PID 文件的实例跳过。
# xm-battle 最先停：它是最后启动的叶子（6.2 没有别的进程调它），停机时作废全部房间、给观众推 166 后关直连，不依赖其它进程。
set -uo pipefail

cd "$(dirname "$0")/../.."
for name in xm-battle xm-gateway xm-gate xm-scene-2 xm-scene xm-data xm-trade xm-guild xm-team xm-chat xm-friend xm-login xm-scene-manager; do
  pidfile="run/pids/$name.pid"
  [[ -f "$pidfile" ]] || continue
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
done
