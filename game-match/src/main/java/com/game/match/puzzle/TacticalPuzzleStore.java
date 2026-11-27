package com.game.match.puzzle;

import com.game.common.token.NetworkProbes;
import com.game.pbmysql.PbMysql;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TacticalPuzzleBattleContext;
import com.game.proto.TacticalPuzzleSpec;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.TacticalPuzzleListTab;
import com.game.proto.match.TacticalPuzzleProfileView;
import com.game.proto.match.TacticalPuzzleState;
import com.game.proto.match.TacticalPuzzleSummaryView;
import com.game.table.MatchErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * 万象残局坊 V1（UGC 回合解谜工坊）SQL 权威存取（对齐 Go {@code go/match/internal/authority/tactical_puzzle.go}）。
 *
 * <p>表结构：
 * <ul>
 *   <li>{@code match_tactical_puzzle}：残局主表（每人最多 10 题，编辑递增 {@code spec_revision} 并回落草稿释放旧标准解引用）；</li>
 *   <li>{@code match_tactical_puzzle_clear}：玩家单题挑战/破局/最少回合/点赞合并表（主键 {@code (puzzle_id, player_id)}）。</li>
 * </ul>
 */
public final class TacticalPuzzleStore {

    private static final String SELECT_COLS =
            "puzzle_id,author_player_id,author_name,state,spec_revision,title,hint_text,tag_mask,"
                    + "spec_payload,verified_battle_id,author_best_rounds,attempt_count,clear_count,"
                    + "global_best_rounds,like_count,created_ms,published_ms,updated_ms";

    public static final class StoreException extends Exception {
        private final int errorTip;

        public StoreException(int errorTip, String message) {
            super(message);
            this.errorTip = errorTip;
        }

        public int errorTip() {
            return errorTip;
        }
    }

    public record ListPage(List<TacticalPuzzleSummaryView> items, int totalCount, TacticalPuzzleProfileView profile) {
    }

    public record LikeResult(int likeCount, boolean selfLiked) {
    }

    private record PuzzleRow(
            long puzzleId, long authorPlayerId, String authorName, int state, int specRevision,
            String title, String hintText, int tagMask, byte[] specPayload, long verifiedBattleId,
            int authorBestRounds, int attemptCount, int clearCount, int globalBestRounds,
            int likeCount, long createdMs, long publishedMs, long updatedMs) {
    }

    private record ClearRow(
            long puzzleId, long playerId, int attempts, boolean cleared,
            int bestRounds, boolean liked, long firstClearedMs, long updatedMs) {
    }

    private final DataSource dataSource;
    private final LongSupplier clockMs;

    public TacticalPuzzleStore(DataSource dataSource, LongSupplier clockMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    private static PuzzleRow readPuzzleRow(ResultSet rs) throws SQLException {
        return new PuzzleRow(
                Long.parseUnsignedLong(rs.getString(1)),
                Long.parseUnsignedLong(rs.getString(2)),
                rs.getString(3),
                rs.getInt(4),
                rs.getInt(5),
                rs.getString(6),
                rs.getString(7),
                rs.getInt(8),
                rs.getBytes(9),
                Long.parseUnsignedLong(rs.getString(10)),
                rs.getInt(11),
                rs.getInt(12),
                rs.getInt(13),
                rs.getInt(14),
                rs.getInt(15),
                Long.parseUnsignedLong(rs.getString(16)),
                Long.parseUnsignedLong(rs.getString(17)),
                Long.parseUnsignedLong(rs.getString(18)));
    }

    private static TacticalPuzzleSummaryView toSummaryView(PuzzleRow row, ClearRow clear) throws SQLException {
        TacticalPuzzleSpec spec;
        try {
            spec = row.specPayload() == null || row.specPayload().length == 0
                    ? TacticalPuzzleSpec.getDefaultInstance()
                    : TacticalPuzzleSpec.parseFrom(row.specPayload());
        } catch (InvalidProtocolBufferException e) {
            throw new SQLException("corrupt tactical puzzle spec " + Long.toUnsignedString(row.puzzleId()), e);
        }
        TacticalPuzzleSummaryView.Builder b = TacticalPuzzleSummaryView.newBuilder()
                .setPuzzleId(row.puzzleId())
                .setAuthorPlayerId(row.authorPlayerId())
                .setAuthorName(row.authorName() == null ? "" : row.authorName())
                .setState(TacticalPuzzleState.forNumber(row.state()) == null
                        ? TacticalPuzzleState.TACTICAL_PUZZLE_STATE_DRAFT
                        : TacticalPuzzleState.forNumber(row.state()))
                .setSpecRevision(row.specRevision())
                .setTitle(row.title() == null ? "" : row.title())
                .setHintText(row.hintText() == null ? "" : row.hintText())
                .setTagMask(row.tagMask())
                .setSpec(spec)
                .setAuthorBestRounds(row.authorBestRounds())
                .setAttemptCount(row.attemptCount())
                .setClearCount(row.clearCount())
                .setGlobalBestRounds(row.globalBestRounds())
                .setLikeCount(row.likeCount())
                .setHasSolutionReplay(row.verifiedBattleId() != 0L)
                .setPublishedMs(row.publishedMs())
                .setUpdatedMs(row.updatedMs());
        if (clear != null) {
            b.setSelfCleared(clear.cleared())
                    .setSelfBestRounds(clear.bestRounds())
                    .setSelfLiked(clear.liked());
        }
        return b.build();
    }

    public TacticalPuzzleSummaryView saveDraft(
            long authorPlayerId,
            String authorName,
            long puzzleId,
            String title,
            String hint,
            int tagMask,
            TacticalPuzzleSpec spec,
            LongSupplier nextPuzzleId) throws SQLException, StoreException {
        if (authorPlayerId == 0L || nextPuzzleId == null
                || !TacticalPuzzleSpecValidator.isValidSpec(spec)
                || !TacticalPuzzleSpecValidator.isValidTagMask(tagMask)) {
            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleSpecInvalid_VALUE, "spec invalid");
        }
        byte[] specBytes = NetworkProbes.bytes(spec);
        String safeName = authorName == null ? "" : authorName;
        long nowMs = clockMs.getAsLong();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                PuzzleRow saved;
                if (puzzleId == 0L) {
                    int count;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT COUNT(*) FROM match_tactical_puzzle WHERE author_player_id=? FOR UPDATE")) {
                        ps.setObject(1, PbMysql.uint64(authorPlayerId));
                        try (ResultSet rs = ps.executeQuery()) {
                            count = rs.next() ? rs.getInt(1) : 0;
                        }
                    }
                    if (count >= TacticalPuzzleSpecValidator.MAX_PUZZLES_PER_PLAYER) {
                        throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleLimitReached_VALUE, "limit reached");
                    }
                    long newId = nextPuzzleId.getAsLong();
                    if (newId == 0L) {
                        throw new SQLException("failed to allocate tactical puzzle id");
                    }
                    saved = new PuzzleRow(newId, authorPlayerId, safeName,
                            TacticalPuzzleSpecValidator.STATE_DRAFT, 1, title, hint, tagMask, specBytes,
                            0L, 0, 0, 0, 0, 0, nowMs, 0L, nowMs);
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO match_tactical_puzzle(" + SELECT_COLS
                                    + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        ps.setObject(1, PbMysql.uint64(saved.puzzleId()));
                        ps.setObject(2, PbMysql.uint64(saved.authorPlayerId()));
                        ps.setString(3, saved.authorName());
                        ps.setInt(4, saved.state());
                        ps.setInt(5, saved.specRevision());
                        ps.setString(6, saved.title());
                        ps.setString(7, saved.hintText());
                        ps.setInt(8, saved.tagMask());
                        ps.setBytes(9, saved.specPayload());
                        ps.setObject(10, PbMysql.uint64(0L));
                        ps.setInt(11, 0);
                        ps.setInt(12, 0);
                        ps.setInt(13, 0);
                        ps.setInt(14, 0);
                        ps.setInt(15, 0);
                        ps.setObject(16, PbMysql.uint64(nowMs));
                        ps.setObject(17, PbMysql.uint64(0L));
                        ps.setObject(18, PbMysql.uint64(nowMs));
                        ps.executeUpdate();
                    }
                } else {
                    PuzzleRow existing;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT " + SELECT_COLS + " FROM match_tactical_puzzle WHERE puzzle_id=? FOR UPDATE")) {
                        ps.setObject(1, PbMysql.uint64(puzzleId));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
                            }
                            existing = readPuzzleRow(rs);
                        }
                    }
                    if (existing.authorPlayerId() != authorPlayerId) {
                        throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotOwner_VALUE, "not owner");
                    }
                    int nextRev = existing.specRevision() + 1;
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE match_tactical_puzzle SET author_name=?,state=?,spec_revision=?,title=?,hint_text=?,"
                                    + "tag_mask=?,spec_payload=?,verified_battle_id=0,author_best_rounds=0,updated_ms=? "
                                    + "WHERE puzzle_id=?")) {
                        ps.setString(1, safeName);
                        ps.setInt(2, TacticalPuzzleSpecValidator.STATE_DRAFT);
                        ps.setInt(3, nextRev);
                        ps.setString(4, title);
                        ps.setString(5, hint);
                        ps.setInt(6, tagMask);
                        ps.setBytes(7, specBytes);
                        ps.setObject(8, PbMysql.uint64(nowMs));
                        ps.setObject(9, PbMysql.uint64(puzzleId));
                        ps.executeUpdate();
                    }
                    if (existing.verifiedBattleId() != 0L) {
                        releaseBattleReportsInTx(c, Map.of(existing.verifiedBattleId(), 1));
                    }
                    saved = new PuzzleRow(existing.puzzleId(), existing.authorPlayerId(), safeName,
                            TacticalPuzzleSpecValidator.STATE_DRAFT, nextRev, title, hint, tagMask, specBytes,
                            0L, 0, existing.attemptCount(), existing.clearCount(), existing.globalBestRounds(),
                            existing.likeCount(), existing.createdMs(), existing.publishedMs(), nowMs);
                }
                c.commit();
                return toSummaryView(saved, null);
            } catch (SQLException | StoreException | RuntimeException e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public void deletePuzzle(long authorPlayerId, long puzzleId) throws SQLException, StoreException {
        if (authorPlayerId == 0L || puzzleId == 0L) {
            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                long ownerId;
                long verifiedBattleId;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT author_player_id,verified_battle_id FROM match_tactical_puzzle WHERE puzzle_id=? FOR UPDATE")) {
                    ps.setObject(1, PbMysql.uint64(puzzleId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
                        }
                        ownerId = Long.parseUnsignedLong(rs.getString(1));
                        verifiedBattleId = Long.parseUnsignedLong(rs.getString(2));
                    }
                }
                if (ownerId != authorPlayerId) {
                    throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotOwner_VALUE, "not owner");
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM match_tactical_puzzle_clear WHERE puzzle_id=?")) {
                    ps.setObject(1, PbMysql.uint64(puzzleId));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM match_tactical_puzzle WHERE puzzle_id=? AND author_player_id=?")) {
                    ps.setObject(1, PbMysql.uint64(puzzleId));
                    ps.setObject(2, PbMysql.uint64(authorPlayerId));
                    ps.executeUpdate();
                }
                if (verifiedBattleId != 0L) {
                    releaseBattleReportsInTx(c, Map.of(verifiedBattleId, 1));
                }
                c.commit();
            } catch (SQLException | StoreException | RuntimeException e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public TacticalPuzzleSummaryView getPuzzle(long viewerId, long puzzleId) throws SQLException, StoreException {
        if (puzzleId == 0L) {
            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
        }
        try (Connection c = dataSource.getConnection()) {
            PuzzleRow row;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SELECT_COLS + " FROM match_tactical_puzzle WHERE puzzle_id=?")) {
                ps.setObject(1, PbMysql.uint64(puzzleId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
                    }
                    row = readPuzzleRow(rs);
                }
            }
            if (row.state() != TacticalPuzzleSpecValidator.STATE_PUBLISHED && row.authorPlayerId() != viewerId) {
                throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "draft hidden");
            }
            ClearRow clear = readClearRow(c, puzzleId, viewerId);
            return toSummaryView(row, clear);
        }
    }

    public ListPage listPuzzles(long viewerId, TacticalPuzzleListTab tab, int tagFilterMask, int offset, int limit)
            throws SQLException {
        TacticalPuzzleListTab effectiveTab = (tab == null || tab == TacticalPuzzleListTab.TACTICAL_PUZZLE_LIST_TAB_UNSPECIFIED)
                ? TacticalPuzzleListTab.TACTICAL_PUZZLE_LIST_TAB_HOT
                : tab;
        int effLimit = limit <= 0 ? TacticalPuzzleSpecValidator.DEFAULT_LIST_LIMIT
                : Math.min(limit, TacticalPuzzleSpecValidator.MAX_LIST_LIMIT);
        int effOffset = Math.max(0, offset);
        int effTagMask = tagFilterMask & TacticalPuzzleSpecValidator.MAX_TAG_MASK;

        try (Connection c = dataSource.getConnection()) {
            List<PuzzleRow> all = new ArrayList<>();
            String sql = switch (effectiveTab) {
                case TACTICAL_PUZZLE_LIST_TAB_MY_CREATED ->
                        "SELECT " + SELECT_COLS + " FROM match_tactical_puzzle WHERE author_player_id=? "
                                + "ORDER BY updated_ms DESC,puzzle_id DESC LIMIT 200";
                case TACTICAL_PUZZLE_LIST_TAB_MY_CLEARED ->
                        "SELECT p.puzzle_id,p.author_player_id,p.author_name,p.state,p.spec_revision,p.title,p.hint_text,"
                                + "p.tag_mask,p.spec_payload,p.verified_battle_id,p.author_best_rounds,p.attempt_count,"
                                + "p.clear_count,p.global_best_rounds,p.like_count,p.created_ms,p.published_ms,p.updated_ms "
                                + "FROM match_tactical_puzzle p INNER JOIN match_tactical_puzzle_clear c ON p.puzzle_id=c.puzzle_id "
                                + "WHERE c.player_id=? AND c.cleared=1 ORDER BY c.updated_ms DESC,p.puzzle_id DESC LIMIT 200";
                case TACTICAL_PUZZLE_LIST_TAB_NEWEST ->
                        "SELECT " + SELECT_COLS + " FROM match_tactical_puzzle WHERE state=? "
                                + "ORDER BY published_ms DESC,puzzle_id DESC LIMIT 200";
                default ->
                        "SELECT " + SELECT_COLS + " FROM match_tactical_puzzle WHERE state=? "
                                + "ORDER BY like_count DESC,clear_count DESC,puzzle_id DESC LIMIT 200";
            };
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (effectiveTab == TacticalPuzzleListTab.TACTICAL_PUZZLE_LIST_TAB_MY_CREATED
                        || effectiveTab == TacticalPuzzleListTab.TACTICAL_PUZZLE_LIST_TAB_MY_CLEARED) {
                    ps.setObject(1, PbMysql.uint64(viewerId));
                } else {
                    ps.setInt(1, TacticalPuzzleSpecValidator.STATE_PUBLISHED);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PuzzleRow row = readPuzzleRow(rs);
                        if (effTagMask != 0 && (row.tagMask() & effTagMask) == 0) {
                            continue;
                        }
                        all.add(row);
                    }
                }
            }
            if (effectiveTab == TacticalPuzzleListTab.TACTICAL_PUZZLE_LIST_TAB_HARDEST) {
                all.sort((a, b) -> {
                    boolean aTried = a.attemptCount() > 0;
                    boolean bTried = b.attemptCount() > 0;
                    if (aTried != bTried) {
                        return aTried ? -1 : 1;
                    }
                    long lhs = Integer.toUnsignedLong(a.clearCount()) * Math.max(1L, Integer.toUnsignedLong(b.attemptCount()));
                    long rhs = Integer.toUnsignedLong(b.clearCount()) * Math.max(1L, Integer.toUnsignedLong(a.attemptCount()));
                    int cmp = Long.compareUnsigned(lhs, rhs);
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Integer.compareUnsigned(b.attemptCount(), a.attemptCount());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Long.compareUnsigned(b.puzzleId(), a.puzzleId());
                });
            }

            int total = all.size();
            int from = Math.min(effOffset, total);
            int to = Math.min(from + effLimit, total);
            List<PuzzleRow> page = all.subList(from, to);

            Map<Long, ClearRow> clearMap = new HashMap<>();
            if (viewerId != 0L) {
                for (PuzzleRow row : page) {
                    ClearRow cr = readClearRow(c, row.puzzleId(), viewerId);
                    if (cr != null) {
                        clearMap.put(row.puzzleId(), cr);
                    }
                }
            }
            List<TacticalPuzzleSummaryView> views = new ArrayList<>(page.size());
            for (PuzzleRow row : page) {
                views.add(toSummaryView(row, clearMap.get(row.puzzleId())));
            }
            TacticalPuzzleProfileView profile = readProfile(c, viewerId);
            return new ListPage(views, total, profile);
        }
    }

    public LikeResult likePuzzle(long playerId, long puzzleId) throws SQLException, StoreException {
        if (playerId == 0L || puzzleId == 0L) {
            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                long authorId;
                int state;
                int likeCount;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT author_player_id,state,like_count FROM match_tactical_puzzle WHERE puzzle_id=? FOR UPDATE")) {
                    ps.setObject(1, PbMysql.uint64(puzzleId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
                        }
                        authorId = Long.parseUnsignedLong(rs.getString(1));
                        state = rs.getInt(2);
                        likeCount = rs.getInt(3);
                    }
                }
                if (state != TacticalPuzzleSpecValidator.STATE_PUBLISHED) {
                    throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotPublished_VALUE, "not published");
                }
                if (authorId == playerId) {
                    throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleCannotLikeOwn_VALUE, "cannot like own");
                }
                boolean cleared;
                boolean liked;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT cleared,liked FROM match_tactical_puzzle_clear WHERE puzzle_id=? AND player_id=? FOR UPDATE")) {
                    ps.setObject(1, PbMysql.uint64(puzzleId));
                    ps.setObject(2, PbMysql.uint64(playerId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleClearRequiredToLike_VALUE, "not cleared");
                        }
                        cleared = rs.getBoolean(1);
                        liked = rs.getBoolean(2);
                    }
                }
                if (!cleared) {
                    throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleClearRequiredToLike_VALUE, "not cleared");
                }
                if (liked) {
                    c.commit();
                    return new LikeResult(likeCount, true);
                }
                long nowMs = clockMs.getAsLong();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE match_tactical_puzzle_clear SET liked=1,updated_ms=? WHERE puzzle_id=? AND player_id=?")) {
                    ps.setObject(1, PbMysql.uint64(nowMs));
                    ps.setObject(2, PbMysql.uint64(puzzleId));
                    ps.setObject(3, PbMysql.uint64(playerId));
                    ps.executeUpdate();
                }
                int updatedLikes = likeCount + 1;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE match_tactical_puzzle SET like_count=?,updated_ms=? WHERE puzzle_id=?")) {
                    ps.setInt(1, updatedLikes);
                    ps.setObject(2, PbMysql.uint64(nowMs));
                    ps.setObject(3, PbMysql.uint64(puzzleId));
                    ps.executeUpdate();
                }
                c.commit();
                return new LikeResult(updatedLikes, true);
            } catch (SQLException | StoreException | RuntimeException e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public BattleReplayEnvelope getSolutionReplay(long viewerId, long puzzleId) throws SQLException, StoreException {
        if (puzzleId == 0L) {
            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
        }
        try (Connection c = dataSource.getConnection()) {
            long authorId;
            int state;
            long verifiedBattleId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT author_player_id,state,verified_battle_id FROM match_tactical_puzzle WHERE puzzle_id=?")) {
                ps.setObject(1, PbMysql.uint64(puzzleId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
                    }
                    authorId = Long.parseUnsignedLong(rs.getString(1));
                    state = rs.getInt(2);
                    verifiedBattleId = Long.parseUnsignedLong(rs.getString(3));
                }
            }
            if (state != TacticalPuzzleSpecValidator.STATE_PUBLISHED && authorId != viewerId) {
                throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotPublished_VALUE, "not published");
            }
            if (verifiedBattleId == 0L) {
                throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleSolutionReplayNotFound_VALUE, "no replay");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT envelope FROM match_battle_report WHERE battle_id=?")) {
                ps.setObject(1, PbMysql.uint64(verifiedBattleId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleSolutionReplayNotFound_VALUE, "replay missing");
                    }
                    byte[] raw = rs.getBytes(1);
                    if (raw == null || raw.length == 0) {
                        throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleSolutionReplayNotFound_VALUE, "replay empty");
                    }
                    try {
                        return BattleReplayEnvelope.parseFrom(raw);
                    } catch (InvalidProtocolBufferException e) {
                        throw new SQLException("corrupt tactical puzzle solution replay " + Long.toUnsignedString(verifiedBattleId), e);
                    }
                }
            }
        }
    }

    public TacticalPuzzleBattleContext prepareLaunch(long playerId, long puzzleId, boolean isAuthorVerify)
            throws SQLException, StoreException {
        if (playerId == 0L || puzzleId == 0L) {
            throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
        }
        try (Connection c = dataSource.getConnection()) {
            PuzzleRow row;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SELECT_COLS + " FROM match_tactical_puzzle WHERE puzzle_id=?")) {
                ps.setObject(1, PbMysql.uint64(puzzleId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotFound_VALUE, "not found");
                    }
                    row = readPuzzleRow(rs);
                }
            }
            if (isAuthorVerify) {
                if (row.authorPlayerId() != playerId) {
                    throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotOwner_VALUE, "not owner");
                }
            } else if (row.state() != TacticalPuzzleSpecValidator.STATE_PUBLISHED) {
                throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleNotPublished_VALUE, "not published");
            }
            TacticalPuzzleSpec spec;
            try {
                spec = TacticalPuzzleSpec.parseFrom(row.specPayload());
            } catch (InvalidProtocolBufferException e) {
                throw new SQLException("corrupt tactical puzzle spec " + Long.toUnsignedString(puzzleId), e);
            }
            if (!TacticalPuzzleSpecValidator.isValidSpec(spec)) {
                throw new StoreException(MatchErrorTip.match_error.kMatchPuzzleSpecInvalid_VALUE, "spec invalid");
            }
            return TacticalPuzzleBattleContext.newBuilder()
                    .setPuzzleId(row.puzzleId())
                    .setSpecRevision(row.specRevision())
                    .setIsAuthorVerify(isAuthorVerify)
                    .setPuzzleTitle(row.title() == null ? "" : row.title())
                    .setAuthorPlayerId(row.authorPlayerId())
                    .setAuthorName(row.authorName() == null ? "" : row.authorName())
                    .setSpec(spec)
                    .build();
        }
    }

    /**
     * 在 {@code GlobalMatchStore.commitOutcome} 唯一终局 SQL 事务内原子结算残局自证发布与破局统计。
     * 与 Go {@code recordTacticalPuzzleBattleCompletionTx} 逐条同源。
     */
    public static void recordBattleCompletionInTx(
            Connection c, CreateBattleRequest create, BattleResultEvent event, long stamp) throws SQLException {
        if (create == null || !create.hasTacticalPuzzleContext() || event == null) {
            return;
        }
        TacticalPuzzleBattleContext pctx = create.getTacticalPuzzleContext();
        if (pctx.getPuzzleId() == 0L) {
            return;
        }
        BattleSettlementData settlement = null;
        for (BattleSettlementData st : event.getSettlementsList()) {
            if (st.getPlayerId() != 0L) {
                settlement = st;
                break;
            }
        }
        if (settlement == null) {
            return;
        }
        long playerId = settlement.getPlayerId();
        boolean won = event.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                && settlement.getPlayerTeamIndex() == 0
                && !settlement.getFled()
                && event.getTotalRounds() > 0;
        int rounds = event.getTotalRounds();

        PuzzleRow row;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + SELECT_COLS + " FROM match_tactical_puzzle WHERE puzzle_id=? FOR UPDATE")) {
            ps.setObject(1, PbMysql.uint64(pctx.getPuzzleId()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                row = readPuzzleRow(rs);
            }
        }

        if (pctx.getIsAuthorVerify()) {
            if (!won || playerId != row.authorPlayerId() || pctx.getSpecRevision() != row.specRevision()) {
                return;
            }
            boolean hasReport = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM match_battle_report WHERE battle_id=? FOR UPDATE")) {
                ps.setObject(1, PbMysql.uint64(create.getBattleId()));
                try (ResultSet rs = ps.executeQuery()) {
                    hasReport = rs.next();
                }
            }
            long oldVerified = row.verifiedBattleId();
            long newVerified = hasReport ? create.getBattleId() : oldVerified;
            if (hasReport && newVerified != oldVerified) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE match_battle_report SET ref_count=ref_count+1 WHERE battle_id=?")) {
                    ps.setObject(1, PbMysql.uint64(newVerified));
                    ps.executeUpdate();
                }
            }
            int authorBest = (row.authorBestRounds() == 0 || rounds < row.authorBestRounds()) ? rounds : row.authorBestRounds();
            int globalBest = (row.globalBestRounds() == 0 || rounds < row.globalBestRounds()) ? rounds : row.globalBestRounds();
            long pubMs = row.publishedMs() == 0L ? stamp : row.publishedMs();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE match_tactical_puzzle SET state=?,verified_battle_id=?,author_best_rounds=?,"
                            + "global_best_rounds=?,published_ms=?,updated_ms=? WHERE puzzle_id=?")) {
                ps.setInt(1, TacticalPuzzleSpecValidator.STATE_PUBLISHED);
                ps.setObject(2, PbMysql.uint64(newVerified));
                ps.setInt(3, authorBest);
                ps.setInt(4, globalBest);
                ps.setObject(5, PbMysql.uint64(pubMs));
                ps.setObject(6, PbMysql.uint64(stamp));
                ps.setObject(7, PbMysql.uint64(row.puzzleId()));
                ps.executeUpdate();
            }
            if (hasReport && oldVerified != 0L && oldVerified != newVerified) {
                releaseBattleReportsInTx(c, Map.of(oldVerified, 1));
            }
            return;
        }

        if (row.state() != TacticalPuzzleSpecValidator.STATE_PUBLISHED) {
            return;
        }
        boolean rowExists = false;
        int curAttempts = 0;
        boolean curCleared = false;
        int curBest = 0;
        long curFirstClearedMs = 0L;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT attempts,cleared,best_rounds,first_cleared_ms FROM match_tactical_puzzle_clear "
                        + "WHERE puzzle_id=? AND player_id=? FOR UPDATE")) {
            ps.setObject(1, PbMysql.uint64(row.puzzleId()));
            ps.setObject(2, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rowExists = true;
                    curAttempts = rs.getInt(1);
                    curCleared = rs.getBoolean(2);
                    curBest = rs.getInt(3);
                    curFirstClearedMs = Long.parseUnsignedLong(rs.getString(4));
                }
            }
        }
        int newAttempts = curAttempts + 1;
        boolean newlyCleared = won && !curCleared;
        boolean newCleared = curCleared || won;
        int newBest = (won && (curBest == 0 || rounds < curBest)) ? rounds : curBest;
        long newFirstClearedMs = newlyCleared ? stamp : curFirstClearedMs;
        if (!rowExists) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO match_tactical_puzzle_clear(puzzle_id,player_id,attempts,cleared,best_rounds,liked,first_cleared_ms,updated_ms) "
                            + "VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, PbMysql.uint64(row.puzzleId()));
                ps.setObject(2, PbMysql.uint64(playerId));
                ps.setInt(3, newAttempts);
                ps.setBoolean(4, newCleared);
                ps.setInt(5, newBest);
                ps.setBoolean(6, false);
                ps.setObject(7, PbMysql.uint64(newFirstClearedMs));
                ps.setObject(8, PbMysql.uint64(stamp));
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE match_tactical_puzzle_clear SET attempts=?,cleared=?,best_rounds=?,first_cleared_ms=?,updated_ms=? "
                            + "WHERE puzzle_id=? AND player_id=?")) {
                ps.setInt(1, newAttempts);
                ps.setBoolean(2, newCleared);
                ps.setInt(3, newBest);
                ps.setObject(4, PbMysql.uint64(newFirstClearedMs));
                ps.setObject(5, PbMysql.uint64(stamp));
                ps.setObject(6, PbMysql.uint64(row.puzzleId()));
                ps.setObject(7, PbMysql.uint64(playerId));
                ps.executeUpdate();
            }
        }
        int newAttemptCount = row.attemptCount() + 1;
        int newClearCount = row.clearCount() + (newlyCleared ? 1 : 0);
        int newGlobalBest = (won && (row.globalBestRounds() == 0 || rounds < row.globalBestRounds()))
                ? rounds : row.globalBestRounds();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match_tactical_puzzle SET attempt_count=?,clear_count=?,global_best_rounds=?,updated_ms=? WHERE puzzle_id=?")) {
            ps.setInt(1, newAttemptCount);
            ps.setInt(2, newClearCount);
            ps.setInt(3, newGlobalBest);
            ps.setObject(4, PbMysql.uint64(stamp));
            ps.setObject(5, PbMysql.uint64(row.puzzleId()));
            ps.executeUpdate();
        }
    }

    /** 删角收尾清理：删除被删角色创作的全部残局与个人破局记录，并按 battle_id 升序释放标准解录像引用。 */
    public static void purgeRemovedPlayerInTx(Connection c, long playerId) throws SQLException {
        List<long[]> owned = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT puzzle_id,verified_battle_id FROM match_tactical_puzzle WHERE author_player_id=? ORDER BY puzzle_id")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owned.add(new long[]{
                            Long.parseUnsignedLong(rs.getString(1)),
                            Long.parseUnsignedLong(rs.getString(2))
                    });
                }
            }
        }
        Map<Long, Integer> released = new TreeMap<>(Long::compareUnsigned);
        for (long[] pair : owned) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM match_tactical_puzzle_clear WHERE puzzle_id=?")) {
                ps.setObject(1, PbMysql.uint64(pair[0]));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM match_tactical_puzzle WHERE puzzle_id=? AND author_player_id=?")) {
                ps.setObject(1, PbMysql.uint64(pair[0]));
                ps.setObject(2, PbMysql.uint64(playerId));
                if (ps.executeUpdate() == 1 && pair[1] != 0L) {
                    released.merge(pair[1], 1, Integer::sum);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM match_tactical_puzzle_clear WHERE player_id=?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            ps.executeUpdate();
        }
        releaseBattleReportsInTx(c, released);
    }

    private static void releaseBattleReportsInTx(Connection c, Map<Long, Integer> released) throws SQLException {
        if (released == null || released.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(released.keySet());
        ids.sort(Long::compareUnsigned);
        for (long battleId : ids) {
            int dec = released.getOrDefault(battleId, 0);
            if (dec <= 0) {
                continue;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE match_battle_report SET ref_count=CASE WHEN ref_count>? THEN ref_count-? ELSE 0 END WHERE battle_id=?")) {
                ps.setInt(1, dec);
                ps.setInt(2, dec);
                ps.setObject(3, PbMysql.uint64(battleId));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM match_battle_report WHERE battle_id=? AND ref_count=0")) {
                ps.setObject(1, PbMysql.uint64(battleId));
                ps.executeUpdate();
            }
        }
    }

    private static ClearRow readClearRow(Connection c, long puzzleId, long playerId) throws SQLException {
        if (playerId == 0L) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT puzzle_id,player_id,attempts,cleared,best_rounds,liked,first_cleared_ms,updated_ms "
                        + "FROM match_tactical_puzzle_clear WHERE puzzle_id=? AND player_id=?")) {
            ps.setObject(1, PbMysql.uint64(puzzleId));
            ps.setObject(2, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ClearRow(
                        Long.parseUnsignedLong(rs.getString(1)),
                        Long.parseUnsignedLong(rs.getString(2)),
                        rs.getInt(3),
                        rs.getBoolean(4),
                        rs.getInt(5),
                        rs.getBoolean(6),
                        Long.parseUnsignedLong(rs.getString(7)),
                        Long.parseUnsignedLong(rs.getString(8)));
            }
        }
    }

    private static TacticalPuzzleProfileView readProfile(Connection c, long playerId) throws SQLException {
        if (playerId == 0L) {
            return TacticalPuzzleProfileView.getDefaultInstance();
        }
        int published = 0;
        int drafts = 0;
        int likesReceived = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state,like_count FROM match_tactical_puzzle WHERE author_player_id=?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt(1) == TacticalPuzzleSpecValidator.STATE_PUBLISHED) {
                        published++;
                        likesReceived += rs.getInt(2);
                    } else {
                        drafts++;
                    }
                }
            }
        }
        int cleared = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM match_tactical_puzzle_clear WHERE player_id=? AND cleared=1")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cleared = rs.getInt(1);
                }
            }
        }
        TacticalPuzzleSpecValidator.HonorInfo honor =
                TacticalPuzzleSpecValidator.deriveHonor(published, cleared, likesReceived);
        return TacticalPuzzleProfileView.newBuilder()
                .setPublishedCount(published)
                .setDraftCount(drafts)
                .setTotalClearedCount(cleared)
                .setTotalLikesReceived(likesReceived)
                .setHonorTier(honor.tier())
                .setHonorTitle(honor.title())
                .build();
    }

    private static void rollbackQuietly(Connection c, Throwable primary) {
        try {
            c.rollback();
        } catch (SQLException suppressed) {
            primary.addSuppressed(suppressed);
        }
    }
}
