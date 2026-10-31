#!/usr/bin/env bash
# 只供一次性 CI 项目：每个信任域独立随机值，交给 runner 的环境文件并 mask。
set -euo pipefail
: "${GITHUB_ENV:?只允许在 CI 环境中生成}"
for key in GAME_MYSQL_PASSWORD GAME_DUBBO_SECRET GAME_GATE_TOKEN_SECRET GAME_LOGIN_DEV_PASSWORD MAIL_PRODUCER_SECRET_RANK GAME_ASSET_OP_SECRET_MAIL GAME_ASSET_OP_SECRET_TRADE RANK_PRODUCER_SECRET_MATCH GAME_ADMIN_TOKEN GAME_ASSET_OP_SECRET_GUILD GAME_MALL_GATEWAY_SECRET GAME_MALL_PAYMENT_SECRET GAME_MALL_DELIVERY_SECRET GAME_MALL_ADMIN_SECRET MMORPG_INTERNAL_SECRET_SCENE GAME_NODE_LINK_SECRET GAME_GM_ADMIN_SECRET GAME_BATTLE_TOKEN_SECRET GAME_SPECTATE_TOKEN_SECRET; do
  value=$(openssl rand -hex 32)
  printf '::add-mask::%s\n' "$value"
  printf '%s=%s\n' "$key" "$value" >> "$GITHUB_ENV"
done
commit=$(git rev-parse HEAD)
contract=$(sed -n 's/^mmorpg.commit=//p' contract/SOURCE.properties | tr -d '\r')
[[ "$commit" =~ ^[0-9a-f]{40}$ && "$contract" =~ ^[0-9a-f]{40}$ ]] || exit 1
generation=$(python3 -c 'import uuid; print(uuid.uuid4())')
printf 'GAME_NAMESPACE=cistack\nGAME_BUILD_COMMIT=%s\nGAME_BUILD_CONTRACT=%s\nGAME_IMAGE_TAG=%s\nGAME_ROUTE_GENERATION=%s\n' \
  "$commit" "$contract" "${commit:0:12}" "$generation" >> "$GITHUB_ENV"
