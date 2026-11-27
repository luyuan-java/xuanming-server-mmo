package com.game.match.script;

import com.game.match.feature.pb.MatchBattleScriptLoadoutRow;
import com.game.match.feature.pb.MatchBattleScriptRow;
import com.game.pbmysql.PbMysql;
import com.game.proto.BattleScript;
import com.game.proto.eBattleScriptOrigin;
import com.game.proto.match.BattleScriptReviewState;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * 战斗剧本与运营审核三表权威存取（对齐 Go {@code go/match/internal/authority/battle_script.go}）。
 *
 * <p>表：{@code match_battle_script}、{@code match_battle_script_review}、{@code match_battle_script_loadout}。
 */
public final class BattleScriptStore implements BattleScriptResolver.PlayerScripts {

    private static final String SCRIPT_COLS = "script_id,owner_player_id,revision,content,review_state,approved_revision,approved_content,reject_reason,submitted_ms,reviewed_ms";
    private static final String LOADOUT_COLS = "player_id,match_mode,battle_config_id,origin,official_script_id,player_script_id,updated_ms";

    private static final int REVIEW_PENDING = BattleScriptReviewState.BATTLE_SCRIPT_REVIEW_PENDING_VALUE;
    private static final int REVIEW_APPROVED = BattleScriptReviewState.BATTLE_SCRIPT_REVIEW_APPROVED_VALUE;
    private static final int REVIEW_REJECTED = BattleScriptReviewState.BATTLE_SCRIPT_REVIEW_REJECTED_VALUE;

    public enum Status {
        OK, NOT_FOUND, REVISION_CONFLICT, LIMIT_REACHED, NOT_APPROVED, LOADOUT_FULL, REVIEW_STALE, REVIEW_INVALID
    }

    public record SubmitResult(Status status, MatchBattleScriptRow row) {
    }

    public record LoadoutResult(Status status, List<MatchBattleScriptLoadoutRow> rows) {
    }

    public record ReviewCursor(long reviewedMs, long scriptId, int revision) {
        public static final ReviewCursor START = new ReviewCursor(0L, 0L, 0);
    }

    public record PurgeResult(ReviewCursor next, long deleted) {
    }

    private final DataSource dataSource;
    private final LongSupplier clockMs;

    public BattleScriptStore(DataSource dataSource, LongSupplier clockMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    public List<MatchBattleScriptRow> listScripts(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + SCRIPT_COLS + " FROM match_battle_script WHERE owner_player_id=? ORDER BY submitted_ms DESC,script_id")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            return readScripts(ps);
        }
    }

    public List<MatchBattleScriptLoadoutRow> listLoadout(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return queryLoadout(c, playerId);
        }
    }

    @Override
    public List<BattleScriptResolver.Choice> loadout(long playerId) throws SQLException {
        List<MatchBattleScriptLoadoutRow> rows = listLoadout(playerId);
        List<BattleScriptResolver.Choice> out = new ArrayList<>(rows.size());
        for (MatchBattleScriptLoadoutRow r : rows) {
            eBattleScriptOrigin origin = eBattleScriptOrigin.forNumber(r.getOrigin());
            out.add(new BattleScriptResolver.Choice(
                    r.getMatchMode(), r.getBattleConfigId(),
                    origin == null ? eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_UNSPECIFIED : origin,
                    r.getOfficialScriptId(), r.getPlayerScriptId()));
        }
        return out;
    }

    @Override
    public Optional<BattleScriptResolver.ApprovedScript> approved(long scriptId, long ownerPlayerId)
            throws SQLException, InvalidProtocolBufferException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT owner_player_id,approved_revision,approved_content FROM match_battle_script WHERE script_id=?")) {
            ps.setObject(1, PbMysql.uint64(scriptId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long actualOwner = Long.parseUnsignedLong(rs.getString(1));
                int approvedRev = rs.getInt(2);
                byte[] content = rs.getBytes(3);
                if (actualOwner != ownerPlayerId || approvedRev == 0 || content == null || content.length == 0) {
                    return Optional.empty();
                }
                return Optional.of(new BattleScriptResolver.ApprovedScript(approvedRev, BattleScript.parseFrom(content)));
            }
        }
    }

    public SubmitResult submitScript(long playerId, long scriptId, long newScriptId, int expectedRevision,
                                     byte[] content, int maxScripts) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                long now = clockMs.getAsLong();
                if (scriptId == 0L) {
                    int count;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT COUNT(*) FROM match_battle_script WHERE owner_player_id=?")) {
                        ps.setObject(1, PbMysql.uint64(playerId));
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            count = rs.getInt(1);
                        }
                    }
                    if (count >= maxScripts) {
                        c.rollback();
                        return new SubmitResult(Status.LIMIT_REACHED, null);
                    }
                    if (newScriptId == 0L) {
                        throw new IllegalArgumentException("new script id is required");
                    }
                    MatchBattleScriptRow row = MatchBattleScriptRow.newBuilder()
                            .setScriptId(newScriptId)
                            .setOwnerPlayerId(playerId)
                            .setRevision(1)
                            .setContent(ByteString.copyFrom(content))
                            .setReviewState(REVIEW_PENDING)
                            .setApprovedRevision(0)
                            .setApprovedContent(ByteString.EMPTY)
                            .setRejectReason("")
                            .setSubmittedMs(now)
                            .setReviewedMs(0L)
                            .build();
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO match_battle_script(" + SCRIPT_COLS + ") VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                        ins.setObject(1, PbMysql.uint64(row.getScriptId()));
                        ins.setObject(2, PbMysql.uint64(row.getOwnerPlayerId()));
                        ins.setInt(3, row.getRevision());
                        ins.setBytes(4, content);
                        ins.setInt(5, REVIEW_PENDING);
                        ins.setInt(6, 0);
                        ins.setBytes(7, new byte[0]);
                        ins.setString(8, "");
                        ins.setObject(9, PbMysql.uint64(now));
                        ins.setObject(10, PbMysql.uint64(0L));
                        ins.executeUpdate();
                    }
                    c.commit();
                    return new SubmitResult(Status.OK, row);
                }

                MatchBattleScriptRow current = lockScript(c, scriptId);
                if (current == null || current.getOwnerPlayerId() != playerId) {
                    c.rollback();
                    return new SubmitResult(Status.NOT_FOUND, null);
                }
                if (current.getRevision() != expectedRevision) {
                    c.rollback();
                    return new SubmitResult(Status.REVISION_CONFLICT, null);
                }
                int nextRev = current.getRevision() + 1;
                MatchBattleScriptRow updated = current.toBuilder()
                        .setRevision(nextRev)
                        .setContent(ByteString.copyFrom(content))
                        .setReviewState(REVIEW_PENDING)
                        .setRejectReason("")
                        .setSubmittedMs(now)
                        .build();
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE match_battle_script SET revision=?,content=?,review_state=?,reject_reason='',submitted_ms=? WHERE script_id=?")) {
                    upd.setInt(1, nextRev);
                    upd.setBytes(2, content);
                    upd.setInt(3, REVIEW_PENDING);
                    upd.setObject(4, PbMysql.uint64(now));
                    upd.setObject(5, PbMysql.uint64(scriptId));
                    upd.executeUpdate();
                }
                c.commit();
                return new SubmitResult(Status.OK, updated);
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public Status deleteScript(long playerId, long scriptId, int expectedRevision) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                MatchBattleScriptRow current = lockScript(c, scriptId);
                if (current == null || current.getOwnerPlayerId() != playerId) {
                    c.rollback();
                    return Status.NOT_FOUND;
                }
                if (current.getRevision() != expectedRevision) {
                    c.rollback();
                    return Status.REVISION_CONFLICT;
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM match_battle_script WHERE script_id=?")) {
                    del.setObject(1, PbMysql.uint64(scriptId));
                    del.executeUpdate();
                }
                try (PreparedStatement delLoadout = c.prepareStatement(
                        "DELETE FROM match_battle_script_loadout WHERE player_id=? AND player_script_id=?")) {
                    delLoadout.setObject(1, PbMysql.uint64(playerId));
                    delLoadout.setObject(2, PbMysql.uint64(scriptId));
                    delLoadout.executeUpdate();
                }
                c.commit();
                return Status.OK;
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public LoadoutResult setLoadout(long playerId, int matchMode, int battleConfigId, eBattleScriptOrigin origin,
                                    int officialScriptId, long playerScriptId, int maxEntries) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                if (origin == eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_UNSPECIFIED) {
                    try (PreparedStatement del = c.prepareStatement(
                            "DELETE FROM match_battle_script_loadout WHERE player_id=? AND match_mode=? AND battle_config_id=?")) {
                        del.setObject(1, PbMysql.uint64(playerId));
                        del.setLong(2, Integer.toUnsignedLong(matchMode));
                        del.setLong(3, Integer.toUnsignedLong(battleConfigId));
                        del.executeUpdate();
                    }
                } else {
                    if (origin == eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_PLAYER) {
                        MatchBattleScriptRow script = lockScript(c, playerScriptId);
                        if (script == null || script.getOwnerPlayerId() != playerId) {
                            c.rollback();
                            return new LoadoutResult(Status.NOT_FOUND, List.of());
                        }
                        if (script.getApprovedRevision() == 0) {
                            c.rollback();
                            return new LoadoutResult(Status.NOT_APPROVED, List.of());
                        }
                    }
                    int others;
                    try (PreparedStatement cnt = c.prepareStatement(
                            "SELECT COUNT(*) FROM match_battle_script_loadout WHERE player_id=? AND NOT (match_mode=? AND battle_config_id=?)")) {
                        cnt.setObject(1, PbMysql.uint64(playerId));
                        cnt.setLong(2, Integer.toUnsignedLong(matchMode));
                        cnt.setLong(3, Integer.toUnsignedLong(battleConfigId));
                        try (ResultSet rs = cnt.executeQuery()) {
                            rs.next();
                            others = rs.getInt(1);
                        }
                    }
                    if (others >= maxEntries) {
                        c.rollback();
                        return new LoadoutResult(Status.LOADOUT_FULL, List.of());
                    }
                    long now = clockMs.getAsLong();
                    try (PreparedStatement del = c.prepareStatement(
                            "DELETE FROM match_battle_script_loadout WHERE player_id=? AND match_mode=? AND battle_config_id=?")) {
                        del.setObject(1, PbMysql.uint64(playerId));
                        del.setLong(2, Integer.toUnsignedLong(matchMode));
                        del.setLong(3, Integer.toUnsignedLong(battleConfigId));
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO match_battle_script_loadout(" + LOADOUT_COLS + ") VALUES(?,?,?,?,?,?,?)")) {
                        ins.setObject(1, PbMysql.uint64(playerId));
                        ins.setLong(2, Integer.toUnsignedLong(matchMode));
                        ins.setLong(3, Integer.toUnsignedLong(battleConfigId));
                        ins.setLong(4, Integer.toUnsignedLong(origin.getNumber()));
                        ins.setLong(5, Integer.toUnsignedLong(officialScriptId));
                        ins.setObject(6, PbMysql.uint64(playerScriptId));
                        ins.setObject(7, PbMysql.uint64(now));
                        ins.executeUpdate();
                    }
                }
                List<MatchBattleScriptLoadoutRow> all = queryLoadout(c, playerId);
                c.commit();
                return new LoadoutResult(Status.OK, all);
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public List<MatchBattleScriptRow> listPending(int limit) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + SCRIPT_COLS + " FROM match_battle_script WHERE review_state=? ORDER BY submitted_ms,script_id LIMIT ?")) {
            ps.setInt(1, REVIEW_PENDING);
            ps.setInt(2, limit);
            return readScripts(ps);
        }
    }

    public Status reviewScript(long scriptId, int revision, boolean approve, String reviewer, String reason,
                               Consumer<byte[]> validatorOnApprove) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                MatchBattleScriptRow current = lockScript(c, scriptId);
                if (current == null || current.getRevision() != revision || current.getReviewState() != REVIEW_PENDING) {
                    c.rollback();
                    return Status.REVIEW_STALE;
                }
                if (approve && validatorOnApprove != null) {
                    try {
                        validatorOnApprove.accept(current.getContent().toByteArray());
                    } catch (RuntimeException invalid) {
                        c.rollback();
                        return Status.REVIEW_INVALID;
                    }
                }
                long now = clockMs.getAsLong();
                if (approve) {
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE match_battle_script SET review_state=?,approved_revision=?,approved_content=?,reject_reason='',reviewed_ms=? WHERE script_id=?")) {
                        upd.setInt(1, REVIEW_APPROVED);
                        upd.setInt(2, current.getRevision());
                        upd.setBytes(3, current.getContent().toByteArray());
                        upd.setObject(4, PbMysql.uint64(now));
                        upd.setObject(5, PbMysql.uint64(scriptId));
                        upd.executeUpdate();
                    }
                } else {
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE match_battle_script SET review_state=?,reject_reason=?,reviewed_ms=? WHERE script_id=?")) {
                        upd.setInt(1, REVIEW_REJECTED);
                        upd.setString(2, reason);
                        upd.setObject(3, PbMysql.uint64(now));
                        upd.setObject(4, PbMysql.uint64(scriptId));
                        upd.executeUpdate();
                    }
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO match_battle_script_review(script_id,revision,owner_player_id,approved,reviewer,reason,reviewed_ms,content) VALUES(?,?,?,?,?,?,?,?)")) {
                    ins.setObject(1, PbMysql.uint64(current.getScriptId()));
                    ins.setInt(2, current.getRevision());
                    ins.setObject(3, PbMysql.uint64(current.getOwnerPlayerId()));
                    ins.setBoolean(4, approve);
                    ins.setString(5, reviewer);
                    ins.setString(6, reason);
                    ins.setObject(7, PbMysql.uint64(now));
                    ins.setBytes(8, current.getContent().toByteArray());
                    ins.executeUpdate();
                }
                c.commit();
                return Status.OK;
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    /** 按 (reviewed_ms, script_id, revision) 游标分批清理过期审核流水（幂等）。 */
    public PurgeResult purgeExpiredReviews(Duration retention, ReviewCursor after, int limit) throws SQLException {
        if (retention == null || !retention.isPositive()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        long now = clockMs.getAsLong();
        long retMs = retention.toMillis();
        if (Long.compareUnsigned(now, retMs) < 0) {
            return new PurgeResult(ReviewCursor.START, 0L);
        }
        long cutoff = now - retMs;
        ReviewCursor cur = after == null ? ReviewCursor.START : after;
        List<ReviewCursor> page = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT reviewed_ms,script_id,revision FROM match_battle_script_review "
                            + "WHERE reviewed_ms<? AND reviewed_ms>=? AND (reviewed_ms>? OR (reviewed_ms=? AND script_id>?) OR (reviewed_ms=? AND script_id=? AND revision>?)) "
                            + "ORDER BY reviewed_ms,script_id,revision LIMIT ?")) {
                ps.setObject(1, PbMysql.uint64(cutoff));
                ps.setObject(2, PbMysql.uint64(cur.reviewedMs()));
                ps.setObject(3, PbMysql.uint64(cur.reviewedMs()));
                ps.setObject(4, PbMysql.uint64(cur.reviewedMs()));
                ps.setObject(5, PbMysql.uint64(cur.scriptId()));
                ps.setObject(6, PbMysql.uint64(cur.reviewedMs()));
                ps.setObject(7, PbMysql.uint64(cur.scriptId()));
                ps.setInt(8, cur.revision());
                ps.setInt(9, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        page.add(new ReviewCursor(
                                Long.parseUnsignedLong(rs.getString(1)),
                                Long.parseUnsignedLong(rs.getString(2)),
                                rs.getInt(3)));
                    }
                }
            }
            if (page.isEmpty()) {
                return new PurgeResult(ReviewCursor.START, 0L);
            }
            long deleted = 0L;
            for (ReviewCursor row : page) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM match_battle_script_review WHERE script_id=? AND revision=? AND reviewed_ms<?")) {
                    del.setObject(1, PbMysql.uint64(row.scriptId()));
                    del.setInt(2, row.revision());
                    del.setObject(3, PbMysql.uint64(cutoff));
                    deleted += del.executeUpdate();
                }
            }
            ReviewCursor next = page.size() == limit ? page.getLast() : ReviewCursor.START;
            return new PurgeResult(next, deleted);
        }
    }

    /** 按时间索引取最老一条审核流水的审核时刻（空表返回 0），用于计算逾期告警年龄。 */
    public long oldestReviewMs() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT reviewed_ms FROM match_battle_script_review ORDER BY reviewed_ms ASC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Long.parseUnsignedLong(rs.getString(1)) : 0L;
        }
    }

    private static MatchBattleScriptRow lockScript(Connection c, long scriptId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + SCRIPT_COLS + " FROM match_battle_script WHERE script_id=? FOR UPDATE")) {
            ps.setObject(1, PbMysql.uint64(scriptId));
            List<MatchBattleScriptRow> rows = readScripts(ps);
            return rows.isEmpty() ? null : rows.getFirst();
        }
    }

    private static List<MatchBattleScriptRow> readScripts(PreparedStatement ps) throws SQLException {
        List<MatchBattleScriptRow> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] content = rs.getBytes(4);
                byte[] approvedContent = rs.getBytes(7);
                String rejectReason = rs.getString(8);
                out.add(MatchBattleScriptRow.newBuilder()
                        .setScriptId(Long.parseUnsignedLong(rs.getString(1)))
                        .setOwnerPlayerId(Long.parseUnsignedLong(rs.getString(2)))
                        .setRevision(rs.getInt(3))
                        .setContent(content == null ? ByteString.EMPTY : ByteString.copyFrom(content))
                        .setReviewState(rs.getInt(5))
                        .setApprovedRevision(rs.getInt(6))
                        .setApprovedContent(approvedContent == null ? ByteString.EMPTY : ByteString.copyFrom(approvedContent))
                        .setRejectReason(rejectReason == null ? "" : rejectReason)
                        .setSubmittedMs(Long.parseUnsignedLong(rs.getString(9)))
                        .setReviewedMs(Long.parseUnsignedLong(rs.getString(10)))
                        .build());
            }
        }
        return out;
    }

    private static List<MatchBattleScriptLoadoutRow> queryLoadout(Connection c, long playerId) throws SQLException {
        List<MatchBattleScriptLoadoutRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + LOADOUT_COLS + " FROM match_battle_script_loadout WHERE player_id=? ORDER BY match_mode,battle_config_id")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(MatchBattleScriptLoadoutRow.newBuilder()
                            .setPlayerId(Long.parseUnsignedLong(rs.getString(1)))
                            .setMatchMode(rs.getInt(2))
                            .setBattleConfigId(rs.getInt(3))
                            .setOrigin(rs.getInt(4))
                            .setOfficialScriptId(rs.getInt(5))
                            .setPlayerScriptId(Long.parseUnsignedLong(rs.getString(6)))
                            .setUpdatedMs(Long.parseUnsignedLong(rs.getString(7)))
                            .build());
                }
            }
        }
        return out;
    }

    private static void rollbackQuietly(Connection c, Exception primary) {
        try {
            c.rollback();
        } catch (SQLException rollback) {
            primary.addSuppressed(rollback);
        }
    }
}
