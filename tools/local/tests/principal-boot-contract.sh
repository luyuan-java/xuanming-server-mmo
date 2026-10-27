#!/usr/bin/env bash
# shell启动接缝纯合同；不启动Java/SQL，正式proto/registry/O_EXCL测试由Java AuthorityDataSource测试承担。
set -euo pipefail
repo=$(cd "$(dirname "$0")/../../.." && pwd)
GAME_SQL_BOOT_LIBRARY_ONLY=1 source "$repo/tools/local/start-slice.sh"
task=$(mktemp -d "${TMPDIR:-/tmp}/mmorpg-principal-boot.XXXXXX")
cleanup() { [[ "$task" == "${TMPDIR:-/tmp}/mmorpg-principal-boot."* ]] && rm -rf -- "$task"; }
trap cleanup EXIT
mkdir -m 700 "$task/catalog" "$task/consume" "$task/fences"
# 该文件故意不是有效proto grant；只检验shell传递及SHA，不能据此声称可获数据库资格。
printf '{}\n' > "$task/grant.fixture.json"
sha=$(sha256sum -- "$task/grant.fixture.json");sha=${sha%% *}
printf 'spring: {}\n' > "$task/runtime.fixture.yaml"
config_sha=$(sha256sum -- "$task/runtime.fixture.yaml");config_sha=${config_sha%% *}
export MMORPG_SQL_BOOT_CATALOG_DIR="$task/catalog" MMORPG_PUBLICATION_FENCE_DIR="$task/fences"
cat > "$task/catalog/game-match.env" <<EOF
MMORPG_SQL_WRITER_GRANT_FILE=$task/grant.fixture.json
MMORPG_SQL_WRITER_GRANT_SHA256=$sha
MMORPG_SQL_WRITER_CONSUME_DIR=$task/consume
MMORPG_SQL_DEPLOYMENT_ID=fixture-boot
MMORPG_SQL_PHYSICAL_AUTHORITY_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
MMORPG_SQL_RUNTIME_CONFIG_FILE=$task/runtime.fixture.yaml
MMORPG_SQL_RUNTIME_CONFIG_SHA256=$config_sha
EOF
export MMORPG_SQL_REGISTRAR_DSN=fixture-control-not-secret MMORPG_SQL_SCHEMA_DSN=fixture-schema-not-secret
export MMORPG_SQL_OPERATOR_JDBC_URL=fixture-control-not-secret MMORPG_SQL_OPERATOR_USER=fixture MMORPG_SQL_OPERATOR_PASSWORD=fixture-not-secret MMORPG_SQL_OPERATOR_PRINCIPAL=fixture
sql_boot_environment game-match
[[ "$MMORPG_SQL_DEPLOYMENT_ID" == fixture-boot && "$SPRING_CONFIG_ADDITIONAL_LOCATION" == "file:$task/runtime.fixture.yaml" && -z "${MMORPG_SQL_REGISTRAR_DSN:-}" && -z "${MMORPG_SQL_SCHEMA_DSN:-}" ]] || exit 1
[[ -z "${MMORPG_SQL_OPERATOR_JDBC_URL:-}${MMORPG_SQL_OPERATOR_USER:-}${MMORPG_SQL_OPERATOR_PASSWORD:-}${MMORPG_SQL_OPERATOR_PRINCIPAL:-}" ]] || { echo 'normal继承了operator凭据' >&2; exit 1; }
if sql_boot_environment game-match game-match prepare 2>/dev/null; then echo '尚无真实prepared接缝的Match被放行' >&2; exit 1; fi
cat > "$task/catalog/game-battle.env" <<EOF
MMORPG_SQL_RUNTIME_CONFIG_FILE=$task/runtime.fixture.yaml
MMORPG_SQL_RUNTIME_CONFIG_SHA256=$config_sha
EOF
sql_boot_environment game-battle game-battle
[[ -z "${MMORPG_SQL_WRITER_GRANT_FILE:-}" && -z "${MMORPG_SQL_DEPLOYMENT_ID:-}" ]] || { echo '非SQL boot继承了前一SQL主体' >&2; exit 1; }
if sql_boot_environment game-data 2>/dev/null; then echo '缺独立boot文件未拒绝' >&2; exit 1; fi
printf 'UNTRUSTED_FIELD=1\n' >> "$task/catalog/game-match.env"
if sql_boot_environment game-match 2>/dev/null; then echo '未知字段未拒绝' >&2; exit 1; fi
sed -i '$d' "$task/catalog/game-match.env"
printf '{"closed":true}\n' > "$task/fences/fixture.closed.json"
if sql_boot_environment game-match 2>/dev/null; then echo '永久发布围栏被普通start绕过' >&2; exit 1; fi
echo 'shell boot接缝纯合同完成；未启动Java、未执行SQL、未证明I3资格'
