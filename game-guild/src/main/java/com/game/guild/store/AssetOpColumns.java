package com.game.guild.store;

import com.game.guild.store.pb.GuildAssetOpRow;
import com.google.protobuf.ByteString;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * guild_asset_op 的全列清单、插入参数与整行扫描（基线 assetOpColumns / assetOpInsertArgs / scanAssetOpRecord，economy_repo.go:62-77、
 * :213-251）。列序<b>只有这一个定义点</b> = {@link GuildAssetOpRow} 的 proto 字段序（1..28）：经济仓储（插行、待结算、最近结果）与资产 Store
 * （领取回读、GetOp、ListStuck）共用；加字段忘了同步三处，表现是「新列永远零值」或运行期列数不符，编译期看不出来——
 * {@code AssetOpColumnsTest} 拿 proto 描述符机械比对（基线 TestAssetOpColumnsCoverEveryProtoField，economy_repo_test.go:58）。
 *
 * <p>行在内存里就是 {@link GuildAssetOpRow}（与基线直接扫进 *pb.GuildAssetOpRecord 同法），整数一律是无符号位模式；
 * 插入时 28 列全写，三个可空列（payload / resolved_by / resolve_reason）写空字节 / 空串而不是 NULL（economy_repo.go:74-77）。
 */
public final class AssetOpColumns {

    /** 全列，顺序 = proto 字段序。 */
    public static final String COLUMNS = "`op_id`, `player_id`, `stream`, `seq`, `guild_id`, `kind`, `status`, `durable`, "
            + "`attempts`, `next_attempt_ms`, `deadline_ms`, `payload`, `ref_id`, `ref_count`, `period_key`, "
            + "`contribution_delta`, `funds_delta`, `reason_tip_id`, `created_ms`, `updated_ms`, "
            + "`lease_until_ms`, `lease_token`, `tx_type`, `last_outcome`, `last_reason`, `stream_epoch`, "
            + "`resolved_by`, `resolve_reason`";

    /** 列数（与 {@link #COLUMNS} 一致，由单测守住）。 */
    public static final int COUNT = 28;

    /** O5：插一行指令（28 列全写）。 */
    public static final String INSERT = "INSERT INTO " + GuildTables.ASSET_OP + " (" + COLUMNS + ") VALUES ("
            + JdbcGuildStore.placeholders(COUNT) + ")";

    private AssetOpColumns() {
    }

    /**
     * 按 {@link #COLUMNS} 的列序给出插入参数（GuildJdbc 的绑定约定：uint64 → Long、uint32 与枚举 → Integer、bytes → byte[]）。
     * 枚举列按整数绑定（列类型是 int）。
     */
    public static Object[] insertArgs(GuildAssetOpRow r) {
        return new Object[] {
                r.getOpId(), r.getPlayerId(), r.getStream(), r.getSeq(), r.getGuildId(),
                r.getKindValue(), r.getStatusValue(), r.getDurable(), r.getAttempts(),
                r.getNextAttemptMs(), r.getDeadlineMs(), r.getPayload().toByteArray(), r.getRefId(), r.getRefCount(),
                r.getPeriodKey(), r.getContributionDelta(), r.getFundsDelta(), r.getReasonTipId(),
                r.getCreatedMs(), r.getUpdatedMs(), r.getLeaseUntilMs(), r.getLeaseToken(), r.getTxType(),
                r.getLastOutcome(), r.getLastReason(), r.getStreamEpoch(),
                r.getResolvedBy(), r.getResolveReason()};
    }

    /**
     * 从第 {@code first} 列起按 {@link #COLUMNS} 的列序扫一整行。枚举列先读成整数再按值写进 builder（未知值原样保留，不因生成枚举不认识
     * 而丢失）；可空的三列读到 NULL 时给空值。
     */
    public static GuildAssetOpRow scan(ResultSet rs, int first) throws SQLException {
        int c = first;
        GuildAssetOpRow.Builder b = GuildAssetOpRow.newBuilder()
                .setOpId(GuildJdbc.u64(rs, c++))
                .setPlayerId(GuildJdbc.u64(rs, c++))
                .setStream(GuildJdbc.u32(rs, c++))
                .setSeq(GuildJdbc.u64(rs, c++))
                .setGuildId(GuildJdbc.u64(rs, c++))
                .setKindValue(rs.getInt(c++))
                .setStatusValue(rs.getInt(c++))
                .setDurable(GuildJdbc.u32(rs, c++))
                .setAttempts(GuildJdbc.u32(rs, c++))
                .setNextAttemptMs(GuildJdbc.u64(rs, c++))
                .setDeadlineMs(GuildJdbc.u64(rs, c++));
        byte[] payload = rs.getBytes(c++);
        b.setPayload(payload == null ? ByteString.EMPTY : ByteString.copyFrom(payload))
                .setRefId(GuildJdbc.u32(rs, c++))
                .setRefCount(GuildJdbc.u32(rs, c++))
                .setPeriodKey(GuildJdbc.u32(rs, c++))
                .setContributionDelta(GuildJdbc.u64(rs, c++))
                .setFundsDelta(GuildJdbc.u64(rs, c++))
                .setReasonTipId(GuildJdbc.u32(rs, c++))
                .setCreatedMs(GuildJdbc.u64(rs, c++))
                .setUpdatedMs(GuildJdbc.u64(rs, c++))
                .setLeaseUntilMs(GuildJdbc.u64(rs, c++))
                .setLeaseToken(GuildJdbc.u64(rs, c++))
                .setTxType(GuildJdbc.u32(rs, c++))
                .setLastOutcome(GuildJdbc.u32(rs, c++))
                .setLastReason(GuildJdbc.u32(rs, c++))
                .setStreamEpoch(GuildJdbc.u64(rs, c++));
        String resolvedBy = rs.getString(c++);
        String resolveReason = rs.getString(c);
        return b.setResolvedBy(resolvedBy == null ? "" : resolvedBy)
                .setResolveReason(resolveReason == null ? "" : resolveReason)
                .build();
    }

    /** 从第 1 列起扫（{@code SELECT <28 列> ...}）。 */
    public static GuildAssetOpRow scan(ResultSet rs) throws SQLException {
        return scan(rs, 1);
    }
}
