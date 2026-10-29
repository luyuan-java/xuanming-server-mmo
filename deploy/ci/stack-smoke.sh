#!/usr/bin/env bash
# 独立的 CI compose 项目；退出时只清理本项目，不触碰已有开发或其他会话资源。
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${GITHUB_RUN_ID:?此入口只用于一次性 CI}"
: "${GITHUB_RUN_ATTEMPT:?}"
project="java-stack-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
compose=(docker compose -p "$project" -f deploy/compose/stack.yaml)
mkdir -p ci-logs
cleanup() {
  local result=$?
  trap - EXIT
  "${compose[@]}" ps -a > ci-logs/stack-ps.txt 2>&1 || true
  "${compose[@]}" logs --no-color --timestamps > ci-logs/stack.log 2>&1 || true
  "${compose[@]}" down -v --remove-orphans > ci-logs/stack-down.log 2>&1 || result=1
  if (( result != 0 )); then echo "::error title=Java整栈冒烟失败::见 stack 日志与逐场景 robot 输出"; fi
  exit "$result"
}
trap cleanup EXIT
"${compose[@]}" --profile tools config --format json | python3 deploy/ci/check-stack.py
# 必填命名空间与秘密不得默默回落到本机默认配置。
if env -u GAME_NAMESPACE docker compose -p "$project" -f deploy/compose/stack.yaml config -q >/dev/null 2>&1; then
  echo "::error::缺失部署命名空间仍通过配置检查"; exit 1
fi
if env -u GAME_NODE_LINK_SECRET docker compose -p "$project" -f deploy/compose/stack.yaml config -q >/dev/null 2>&1; then
  echo "::error::缺失节点链路密钥仍通过配置检查"; exit 1
fi
"${compose[@]}" --profile tools build --pull > ci-logs/stack-build.log 2>&1
"${compose[@]}" up -d --wait --wait-timeout 600 > ci-logs/stack-up.log 2>&1
for scenario in smoke movement reconnect bag currency attribute; do
  "${compose[@]}" --profile tools run --rm --no-deps game-robot "$scenario" \
    --gateway http://game-gateway:18081 --scene-admin-url http://game-scene:18104 \
    --scene-metrics-url http://game-scene:18104 --data-url http://game-data:18106 \
    > "ci-logs/robot-$scenario.log" 2>&1
done
# 运行过真实客户端后再看状态，不能只以端口曾开放一次证明整栈仍存活。
"${compose[@]}" ps --format json > ci-logs/stack-status.jsonl
python3 - <<'PY'
import json
from pathlib import Path
raw=Path("ci-logs/stack-status.jsonl").read_text()
rows=json.loads(raw) if raw.lstrip().startswith("[") else [json.loads(line) for line in raw.splitlines() if line.strip()]
apps=[row for row in rows if row["Service"].startswith("game-")]
if len(apps)!=17 or any(row.get("State")!="running" or row.get("Health")!="healthy" for row in apps):
    raise SystemExit("robot 后仍应有17个健康服务")
PY
echo "JAVA_STACK_OK services=17 scenarios=6"
