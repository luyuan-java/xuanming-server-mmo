#!/usr/bin/env bash
# 按启动的逆序停止本地竖切进程（先 gateway / gate，最后 scene-manager），给每个进程 20s 优雅退出。
set -uo pipefail

cd "$(dirname "$0")/../.."
for name in xm-gateway xm-gate xm-scene xm-login xm-scene-manager; do
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
