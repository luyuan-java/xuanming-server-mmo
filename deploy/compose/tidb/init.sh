#!/usr/bin/env bash
# 只初始化本次开发部署的库和账号；标准输入送 SQL，不把口令写进日志或命令行。
set -euo pipefail
if [[ ! "${GAME_NAMESPACE:-}" =~ ^[a-z][a-z0-9]{0,27}$ ]] || [[ "$GAME_NAMESPACE" =~ ^(mysql|sys|public)$ ]]; then
  echo "部署命名空间非法" >&2; exit 2
fi
if [[ ! "${GAME_MYSQL_PASSWORD:-}" =~ ^[a-fA-F0-9]{64}$ ]]; then
  echo "开发夹具数据库口令必须为 openssl rand -hex 32 生成的 64 位十六进制值" >&2; exit 2
fi
ready=false
for ((attempt=0; attempt<120; attempt++)); do
  if mysql --connect-timeout=2 -h tidb -P 4000 -u root -e 'SELECT 1' >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo "TiDB SQL 未在预算内就绪" >&2; exit 1; }
mysql --connect-timeout=3 -h tidb -P 4000 -u root <<SQL
CREATE DATABASE IF NOT EXISTS \`$GAME_NAMESPACE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS '${GAME_NAMESPACE}_app'@'%' IDENTIFIED BY '$GAME_MYSQL_PASSWORD';
ALTER USER '${GAME_NAMESPACE}_app'@'%' IDENTIFIED BY '$GAME_MYSQL_PASSWORD';
GRANT ALL PRIVILEGES ON \`$GAME_NAMESPACE\`.* TO '${GAME_NAMESPACE}_app'@'%';
SET GLOBAL tidb_txn_mode='pessimistic';
SET GLOBAL tidb_read_consistency='strict';
SQL
# scene-manager 启动即核路由结构；冷库先执行同一受版本管理的结构，避免等 login 的依赖环。
mysql -h tidb -P 4000 -u root "$GAME_NAMESPACE" < /route-schema.sql
mysql -h tidb -P 4000 -u root "$GAME_NAMESPACE" < /player-schema.sql
# 商城只允许显式迁移。开发冷库采用已跟踪的全新安装脚本，已有版本交给应用完整结构核验。
version_table=$(mysql -N -B -h tidb -P 4000 -u root -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$GAME_NAMESPACE' AND table_name='commerce_schema_version'")
if [[ "$version_table" == 0 ]]; then
  mysql -h tidb -P 4000 -u root "$GAME_NAMESPACE" < /mall-fresh-install.sql
fi
echo "TiDB 开发库已就绪"
