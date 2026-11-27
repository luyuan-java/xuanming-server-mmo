package com.game.match.report;

import com.game.common.token.NetworkProbes;
import com.game.pbmysql.PbMysql;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleReplayCodec;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.match.BattleReportMember;
import com.game.proto.match.BattleReportSummary;
import com.game.proto.match.CommitBattleOutcomeRequest;
import com.game.proto.match.MatchMode;
import com.google.protobuf.InvalidProtocolBufferException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 战报回放权威存取（对齐 Go {@code go/match/internal/authority/battle_report.go}）。
 *
 * <p>终局事务内落战报正文与每人最近 {@value #KEPT_PER_PLAYER} 条列表，坏回放外壳只丢回放不挡终局；
 * 读路径先鉴权本人列表再读大正文，禁止越权遍历。
 */
public final class BattleReportStore {

    private static final Logger log = LoggerFactory.getLogger(BattleReportStore.class);

    public static final int KEPT_PER_PLAYER = 100;
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_RAW_BYTES = 1 << 20;
    public static final int MAX_PAYLOAD_BYTES = 512 << 10;
    static final int EVICT_BATCH = 16;

    public static final String DROP_INVALID_ENVELOPE = "invalid_envelope";
    public static final String DROP_TOO_LARGE = "too_large";
    public static final String DROP_NOT_REQUESTED = "not_requested";

    private final DataSource dataSource;
    private final java.util.function.LongPredicate publicReplayAllowed;

    public BattleReportStore(DataSource dataSource) {
        this(dataSource, battleId -> false);
    }

    public BattleReportStore(DataSource dataSource, java.util.function.LongPredicate publicReplayAllowed) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.publicReplayAllowed = publicReplayAllowed == null ? battleId -> false : publicReplayAllowed;
    }

    /** 校验回放外壳，合格返回空串；null 由调用方按「未录制」处理。 */
    public static String replayDropReason(BattleReplayEnvelope env) {
        if (env == null) {
            return "";
        }
        if (env.getFormatVersion() != FORMAT_VERSION
                || env.getCodec() != eBattleReplayCodec.BATTLE_REPLAY_CODEC_GZIP
                || env.getRawBytes() == 0
                || env.getPayload().isEmpty()) {
            return DROP_INVALID_ENVELOPE;
        }
        if (Integer.compareUnsigned(env.getRawBytes(), MAX_RAW_BYTES) > 0
                || env.getPayload().size() > MAX_PAYLOAD_BYTES) {
            return DROP_TOO_LARGE;
        }
        return "";
    }

    /** 构建战报摘要：成员按 (team_index, player_id) 无符号升序，全员列表逐字节一致。 */
    public static BattleReportSummary buildSummary(CreateBattleRequest original, BattleResultEvent event, boolean hasReplay) {
        List<BattleReportMember> members = new ArrayList<>(original.getPlayersCount());
        for (BattlePlayerSnapshot p : original.getPlayersList()) {
            members.add(BattleReportMember.newBuilder()
                    .setPlayerId(p.getPlayerId())
                    .setTeamIndex(p.getTeamIndex())
                    .setName(p.getPlayerName())
                    .setLevel(p.getLevel())
                    .setClassId(p.getClassId())
                    .build());
        }
        members.sort(Comparator.comparingInt(BattleReportMember::getTeamIndex)
                .thenComparingLong(BattleReportMember::getPlayerId));
        MatchMode mode = MatchMode.forNumber(event.getMatchMode());
        return BattleReportSummary.newBuilder()
                .setBattleId(event.getBattleId())
                .setMode(mode == null ? MatchMode.MATCH_MODE_UNSPECIFIED : mode)
                .setBattleConfigId(event.getBattleConfigId())
                .setFinishedAtMs(event.getFinishedAtMs())
                .setOutcome(event.getOutcome())
                .setTotalRounds(event.getTotalRounds())
                .addAllMembers(members)
                .setHasReplay(hasReplay)
                .build();
    }

    /** 在终局事务内写战报正文与全员列表，并按批挤出超额旧行与减引用计数。 */
    public static WriteResult writeInTransaction(Connection c, CreateBattleRequest original, BattleResultEvent event,
                                                 BattleReplayEnvelope replay, long stampMs) throws SQLException {
        long battleId = event.getBattleId();
        boolean hasEnvelope = replay != null && !replay.equals(BattleReplayEnvelope.getDefaultInstance());
        if (!original.getRecordReplay()) {
            if (hasEnvelope) {
                log.error("metric=match_battle_report_dropped reason={} battle_id={}",
                        DROP_NOT_REQUESTED, Long.toUnsignedString(battleId));
            }
            return WriteResult.NONE;
        }
        boolean hasReplay = false;
        if (hasEnvelope) {
            String reason = replayDropReason(replay);
            if (!reason.isEmpty()) {
                log.error("metric=match_battle_report_dropped reason={} battle_id={}",
                        reason, Long.toUnsignedString(battleId));
            } else {
                hasReplay = true;
            }
        }
        List<Long> players = new ArrayList<>(event.getSettlementsCount());
        for (BattleSettlementData s : event.getSettlementsList()) {
            players.add(s.getPlayerId());
        }
        players.sort(Long::compareUnsigned);
        byte[] summaryBytes = NetworkProbes.bytes(buildSummary(original, event, hasReplay));

        boolean stored = false;
        if (hasReplay) {
            byte[] envBytes = NetworkProbes.bytes(replay);
            byte[] digest = sha256(envBytes);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO match_battle_report(battle_id,created_ms,match_mode,battle_config_id,envelope,envelope_digest,ref_count) VALUES(?,?,?,?,?,?,?)")) {
                ps.setObject(1, PbMysql.uint64(battleId));
                ps.setObject(2, PbMysql.uint64(stampMs));
                ps.setLong(3, Integer.toUnsignedLong(event.getMatchMode()));
                ps.setLong(4, Integer.toUnsignedLong(event.getBattleConfigId()));
                ps.setBytes(5, envBytes);
                ps.setBytes(6, digest);
                ps.setInt(7, players.size());
                ps.executeUpdate();
            }
            stored = true;
        }

        Map<Long, Integer> released = new TreeMap<>(Long::compareUnsigned);
        int evictedTotal = 0;
        for (long playerId : players) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO match_player_battle_report(player_id,created_ms,battle_id,summary) VALUES(?,?,?,?)")) {
                ps.setObject(1, PbMysql.uint64(playerId));
                ps.setObject(2, PbMysql.uint64(stampMs));
                ps.setObject(3, PbMysql.uint64(battleId));
                ps.setBytes(4, summaryBytes);
                ps.executeUpdate();
            }
            evictedTotal += evictPlayerExcess(c, playerId, released);
        }
        releaseBodies(c, released);
        return new WriteResult(stored, evictedTotal);
    }

    /** 终局重放时核对回放摘要，不一致仅告警，不改库不改回执。 */
    public static void checkDuplicateReplay(Connection c, CommitBattleOutcomeRequest req) {
        if (!req.hasReplay() || !replayDropReason(req.getReplay()).isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT envelope_digest FROM match_battle_report WHERE battle_id=?")) {
            ps.setObject(1, PbMysql.uint64(req.getBattleId()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                byte[] stored = rs.getBytes(1);
                byte[] candidate = sha256(NetworkProbes.bytes(req.getReplay()));
                if (!Arrays.equals(stored, candidate)) {
                    log.error("metric=match_battle_report_replay_mismatch battle_id={}",
                            Long.toUnsignedString(req.getBattleId()));
                }
            }
        } catch (SQLException e) {
            log.error("[battle_report] replay check failed battle_id={}", Long.toUnsignedString(req.getBattleId()), e);
        }
    }

    /** 只读查询本人最近战报列表（新的在前，最多 100 条）。 */
    public List<BattleReportSummary> listReports(long playerId) throws SQLException {
        List<BattleReportSummary> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT summary FROM match_player_battle_report WHERE player_id=? ORDER BY created_ms DESC,battle_id DESC LIMIT ?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            ps.setInt(2, KEPT_PER_PLAYER);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] raw = rs.getBytes(1);
                    try {
                        out.add(BattleReportSummary.parseFrom(raw));
                    } catch (InvalidProtocolBufferException e) {
                        throw new SQLException("corrupt battle report summary row", e);
                    }
                }
            }
        }
        return out;
    }

    /** 只读查询单局回放正文：先验本人列表归属，再读正文。 */
    public Optional<BattleReplayEnvelope> getReport(long playerId, long battleId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement auth = c.prepareStatement(
                    "SELECT 1 FROM match_player_battle_report WHERE player_id=? AND battle_id=? LIMIT 1")) {
                auth.setObject(1, PbMysql.uint64(playerId));
                auth.setObject(2, PbMysql.uint64(battleId));
                try (ResultSet rs = auth.executeQuery()) {
                    if (!rs.next() && !publicReplayAllowed.test(battleId)) {
                        return Optional.empty();
                    }
                }
            }
            try (PreparedStatement body = c.prepareStatement(
                    "SELECT envelope FROM match_battle_report WHERE battle_id=?")) {
                body.setObject(1, PbMysql.uint64(battleId));
                try (ResultSet rs = body.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of(BattleReplayEnvelope.parseFrom(rs.getBytes(1)));
                    } catch (InvalidProtocolBufferException e) {
                        throw new SQLException("corrupt battle report body", e);
                    }
                }
            }
        }
    }

    /** 删角收尾：删除已删除角色的全部战报列表行并同步减正文引用计数。 */
    public static int purgePlayerReports(Connection c, long playerId) throws SQLException {
        Map<Long, Integer> released = new TreeMap<>(Long::compareUnsigned);
        List<long[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT created_ms,battle_id FROM match_player_battle_report WHERE player_id=?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new long[]{Long.parseUnsignedLong(rs.getString(1)), Long.parseUnsignedLong(rs.getString(2))});
                }
            }
        }
        int deleted = 0;
        for (long[] row : rows) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM match_player_battle_report WHERE player_id=? AND created_ms=? AND battle_id=?")) {
                del.setObject(1, PbMysql.uint64(playerId));
                del.setObject(2, PbMysql.uint64(row[0]));
                del.setObject(3, PbMysql.uint64(row[1]));
                if (del.executeUpdate() == 1) {
                    released.merge(row[1], 1, Integer::sum);
                    deleted++;
                }
            }
        }
        TrialChallengeCardStore.purgePlayerCards(c, playerId, released);
        releaseBodies(c, released);
        return deleted;
    }

    private static int evictPlayerExcess(Connection c, long playerId, Map<Long, Integer> released) throws SQLException {
        List<long[]> victims = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT created_ms,battle_id FROM match_player_battle_report WHERE player_id=? ORDER BY created_ms DESC,battle_id DESC LIMIT ? OFFSET ?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            ps.setInt(2, EVICT_BATCH);
            ps.setInt(3, KEPT_PER_PLAYER);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    victims.add(new long[]{Long.parseUnsignedLong(rs.getString(1)), Long.parseUnsignedLong(rs.getString(2))});
                }
            }
        }
        int evicted = 0;
        for (long[] v : victims) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM match_player_battle_report WHERE player_id=? AND created_ms=? AND battle_id=?")) {
                del.setObject(1, PbMysql.uint64(playerId));
                del.setObject(2, PbMysql.uint64(v[0]));
                del.setObject(3, PbMysql.uint64(v[1]));
                if (del.executeUpdate() == 1) {
                    released.merge(v[1], 1, Integer::sum);
                    evicted++;
                }
            }
        }
        return evicted;
    }

    static void releaseBodies(Connection c, Map<Long, Integer> released) throws SQLException {
        for (Map.Entry<Long, Integer> e : released.entrySet()) {
            long id = e.getKey();
            int n = e.getValue();
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE match_battle_report SET ref_count=CASE WHEN ref_count>? THEN ref_count-? ELSE 0 END WHERE battle_id=?")) {
                upd.setInt(1, n);
                upd.setInt(2, n);
                upd.setObject(3, PbMysql.uint64(id));
                upd.executeUpdate();
            }
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM match_battle_report WHERE battle_id=? AND ref_count=0")) {
                del.setObject(1, PbMysql.uint64(id));
                del.executeUpdate();
            }
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record WriteResult(boolean stored, int evicted) {
        static final WriteResult NONE = new WriteResult(false, 0);
    }
}
