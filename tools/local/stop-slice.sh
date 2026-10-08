#!/usr/bin/env bash
# 停止本地竖切进程，给每个进程 20s 优雅退出。次序：先停 xm-match、xm-battle（理由见下），其余按启动的逆序（gateway / gate …… 最后 scene-manager）。
# 场景节点按实例名停：xm-scene-2（XM_SCENE_NODES=2 时才有）先于 xm-scene；没有 PID 文件的实例跳过。
#
# xm-match 最先停（批次 6.4）：它是 xm-battle（建房 / 销毁 / 补签）与 xm-scene（备战 / 取消）的调用方，先停它，后面的进程停下时就不会有
# 开到一半的局——它停机时先停凑单、撤 Dubbo，再有界等在途的 gather（至多 10 s）跑完，这段时间 xm-battle 与 xm-scene 都还在。
# 它在启动次序里并不在最后（它是 xm-team 与 xm-gate 的 Dubbo 提供方，必须先于两者启动，见 start-slice.sh 文件头），所以这里不是简单的逆序。
# 它停了之后 xm-gate 上 MatchService 的号回信封 1003、xm-team 的整队开战按传输失败回 4030（match-spec §7.6），直到这两个进程也停下。
# 20s 在没有在途 gather 时足够；正开着局就停（robot 还在跑）时，Dubbo 的停服等待加上等在途 gather 可能超过 20s 而被强制结束——
# 留下的票据按 matched TTL（≤ 96 s）自愈、scene 按备战期限解冻、发号租约按 TTL 过期，不需要手工清理。
# xm-battle 第二个停：除 xm-match 之外没有别的进程调它（6.3 的结算是它调 xm-scene），停机时作废全部房间、给观众推 166 后关直连。
set -uo pipefail

cd "$(dirname "$0")/../.."
for name in xm-match xm-battle xm-gateway xm-gate xm-scene-2 xm-scene xm-data xm-trade xm-guild xm-team xm-chat xm-friend xm-login xm-scene-manager; do
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
