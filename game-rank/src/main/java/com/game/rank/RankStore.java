package com.game.rank;

import static com.game.discovery.sql.AuthorityJdbc.u64;

import com.game.api.asset.MailGrants;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.rank.*;
import com.game.table.CommonErrorTip;
import com.game.table.RankErrorTip;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/** 生产者游标、分数、封榜快照、发信意图同一TiDB权威；不从Redis排序推导资产奖励。 */
public final class RankStore {
    public static final class Reject extends RuntimeException {
        final int tip;
        Reject(int tip) {
            this.tip = tip;
        }
    }
    static final int INVALID = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int NOT_FOUND = RankErrorTip.rank_error.kRankBoardNotFound_VALUE;
    static final int CLOSED = RankErrorTip.rank_error.kRankGenerationClosed_VALUE;
    static final int CONFLICT = RankErrorTip.rank_error.kRankRequestConflict_VALUE;
    static final int CURSOR = RankErrorTip.rank_error.kRankSettlementPending_VALUE;
    private final AuthorityJdbc sql;
    private final AuthorityIds ids;
    private final IntPredicate items;
    private final LongSupplier clock;
    public RankStore(AuthorityJdbc sql, AuthorityIds ids, IntPredicate items, LongSupplier clock) {
        this.sql = sql;
        this.ids = ids;
        this.items = items;
        this.clock = clock;
    }
    private static Object U(long v) {
        return u64(v);
    }
    private static void require(boolean condition, int tip) {
        if (!condition)
            throw new Reject(tip);
    }
    private static final String BC = "board_id,board_type,scope,scope_id,period,generation,producer,source_epoch,committed_cursor,"
                                     + "ascending,state,settlement_id,open_request_hash,deleted_ms";
    private record Board(long id, int type, int scope, long scopeId, String period, long generation, String producer, long epoch,
            long cursor, boolean ascending, int state, long settlement, String openHash, long deleted) {
        RankBoardKey key() {
            return RankBoardKey.newBuilder()
                    .setBoardType(type)
                    .setScopeValue(scope)
                    .setScopeId(scopeId)
                    .setPeriod(period)
                    .setGeneration(generation)
                    .build();
        }
        String order() {
            return "score " + (ascending ? "ASC" : "DESC") + ",achieved_ms ASC,entity_id ASC";
        }
    }
    private static Board board(ResultSet r) throws SQLException {
        return new Board(u64(r, 1), r.getInt(2), r.getInt(3), u64(r, 4), r.getString(5), u64(r, 6), r.getString(7), u64(r, 8), u64(r, 9),
                r.getBoolean(10), r.getInt(11), u64(r, 12), r.getString(13), u64(r, 14));
    }
    private static boolean valid(RankBoardKey key, boolean open) {
        return key.getBoardType() != 0 && key.getScopeValue() >= 1 && key.getScopeValue() <= 4 && key.getPeriod().length() <= 64
                && (!open || (!key.getPeriod().isEmpty() && key.getGeneration() != 0));
    }
    private static Board owned(AuthorityJdbc.Session s, long id, String caller, long generation, long epoch) throws SQLException {
        var b = s.one("SELECT " + BC + " FROM rank_board WHERE board_id=? FOR UPDATE", RankStore::board, U(id));
        require(b != null, NOT_FOUND);
        require(b.producer.equals(caller) && b.generation == generation && (epoch == 0 || b.epoch == epoch), CONFLICT);
        return b;
    }
    public OpenRankGenerationResponse open(String caller, OpenRankGenerationRequest in, Deadline d) throws SQLException {
        require(valid(in.getBoard(), true) && caller.equals(in.getProducer()) && in.getSourceEpoch() != 0 && in.getRequestId() != 0,
                INVALID);
        long id = ids.next(d);
        String hash = ServiceCallSignatures.digest(in);
        var k = in.getBoard();
        return sql.tx(d, s -> {
            s.update("INSERT IGNORE INTO rank_board "
                     + "(board_id,board_type,scope,scope_id,period,generation,producer,source_epoch,committed_cursor,ascending,state,"
                     + "settlement_id,open_request_id,open_request_hash,created_ms,sealed_ms,deleted_ms) VALUES "
                     + "(?,?,?,?,?,?,?,?,0,?,1,0,?,?,?,0,0)",
                    U(id), k.getBoardType(), k.getScopeValue(), U(k.getScopeId()), k.getPeriod(), U(k.getGeneration()), caller,
                    U(in.getSourceEpoch()), in.getAscending() ? 1 : 0, U(in.getRequestId()), hash, clock.getAsLong());
            var b = s.one("SELECT " + BC
                            + " FROM rank_board WHERE board_type=? AND scope=? AND scope_id=? AND period=? AND generation=? FOR UPDATE",
                    RankStore::board, k.getBoardType(), k.getScopeValue(), U(k.getScopeId()), k.getPeriod(), U(k.getGeneration()));
            require(b != null && b.openHash.equals(hash) && b.deleted == 0, CONFLICT);
            return OpenRankGenerationResponse.newBuilder().setBoardId(b.id).setDuplicated(b.id != id).build();
        });
    }
    public SubmitRankScoresResponse submit(String caller, SubmitRankScoresRequest in, Deadline d) throws SQLException {
        require(in.getBoardId() != 0 && in.getGeneration() != 0 && in.getSourceEpoch() != 0 && in.getSourceCursor() != 0
                        && in.getRequestId() != 0 && in.getEntriesCount() > 0 && in.getEntriesCount() <= 128 && in.getModeValue() >= 1
                        && in.getModeValue() <= 3,
                INVALID);
        var sorted = new ArrayList<>(in.getEntriesList());
        sorted.sort((a, b) -> Long.compareUnsigned(a.getEntityId(), b.getEntityId()));
        long prior = 0;
        for (var e : sorted) {
            require(e.getEntityId() != 0 && e.getEntityId() != prior && e.getAchievedMs() != 0, INVALID);
            prior = e.getEntityId();
        }
        String hash = ServiceCallSignatures.digest(in);
        return sql.tx(d, s -> {
            var b = owned(s, in.getBoardId(), caller, in.getGeneration(), in.getSourceEpoch());
            require(b.deleted == 0, CONFLICT);
            if (Long.compareUnsigned(in.getSourceCursor(), b.cursor) <= 0) {
                var old = s.one("SELECT request_hash FROM rank_input_receipt WHERE board_id=? AND generation=? AND source_cursor=?",
                        r -> r.getString(1), U(b.id), U(b.generation), U(in.getSourceCursor()));
                require(hash.equals(old), CONFLICT);
                return SubmitRankScoresResponse.newBuilder().setCommittedCursor(b.cursor).setDuplicated(true).build();
            }
            require(b.state == 1, CLOSED);
            require(b.cursor != -1L && in.getSourceCursor() == b.cursor + 1, CURSOR);
            for (var e : sorted) {
                var old = s.one("SELECT score,achieved_ms FROM rank_score WHERE board_id=? AND generation=? AND entity_id=?",
                        r -> new long[] {r.getLong(1), u64(r, 2)}, U(b.id), U(b.generation), U(e.getEntityId()));
                long value = e.getScore(), achieved = e.getAchievedMs();
                if (in.getModeValue() == 3)
                    try {
                        value = Math.addExact(old == null ? 0 : old[0], value);
                    } catch (ArithmeticException overflow) {
                        throw new Reject(INVALID);
                    }
                if (in.getModeValue() == 1 && old != null && (b.ascending ? value >= old[0] : value <= old[0]))
                    continue;
                if (old != null && value == old[0])
                    achieved = old[1];
                s.update("INSERT INTO rank_score (board_id,generation,entity_id,score,achieved_ms,source_cursor) VALUES (?,?,?,?,?,?) ON "
                         + "DUPLICATE KEY UPDATE score=VALUES(score),achieved_ms=VALUES(achieved_ms),source_cursor=VALUES(source_cursor)",
                        U(b.id), U(b.generation), U(e.getEntityId()), value, U(achieved), U(in.getSourceCursor()));
            }
            s.update("INSERT INTO rank_input_receipt (board_id,generation,source_cursor,request_id,request_hash,committed_ms) VALUES "
                     + "(?,?,?,?,?,?)",
                    U(b.id), U(b.generation), U(in.getSourceCursor()), U(in.getRequestId()), hash, clock.getAsLong());
            s.update("UPDATE rank_board SET committed_cursor=? WHERE board_id=?", U(in.getSourceCursor()), U(b.id));
            return SubmitRankScoresResponse.newBuilder().setCommittedCursor(in.getSourceCursor()).build();
        });
    }
    public FreezeRankGenerationResponse freeze(String caller, FreezeRankGenerationRequest in, Deadline d) throws SQLException {
        int top = in.getTopN() == 0 ? 100 : in.getTopN();
        require(in.getBoardId() != 0 && in.getGeneration() != 0 && in.getSourceEpoch() != 0 && in.getRequestId() != 0 && top > 0
                        && top <= 1000 && in.getRewardsCount() <= 100,
                INVALID);
        var rewarded = new BitSet(top + 1);
        for (var tier : in.getRewardsList()) {
            require(tier.getFirstRank() > 0 && tier.getLastRank() >= tier.getFirstRank() && tier.getLastRank() <= top
                            && tier.hasAttachments(),
                    INVALID);
            var grants = MailGrants.bundle(tier.getAttachments(), items);
            require(grants.getCurrenciesCount() + grants.getItemsCount() > 0, INVALID);
            for (int p = tier.getFirstRank(); p <= tier.getLastRank(); p++) {
                require(!rewarded.get(p), INVALID);
                rewarded.set(p);
            }
        }
        String hash = ServiceCallSignatures.digest(in);
        long settlement = ids.reserve(d, top + 1);
        long[] rewardIds = new long[top];
        for (int i = 0; i < top; i++)
            if (rewarded.get(i + 1))
                rewardIds[i] = settlement + i + 1;
        return sql.tx(d, s -> {
            var b = owned(s, in.getBoardId(), caller, in.getGeneration(), in.getSourceEpoch());
            require(b.deleted == 0, CONFLICT);
            if (b.settlement != 0) {
                var old = s.one("SELECT request_hash,winner_count FROM rank_settlement WHERE settlement_id=?",
                        r -> new Object[] {r.getString(1), u64(r, 2)}, U(b.settlement));
                require(old != null && hash.equals(old[0]), CONFLICT);
                return FreezeRankGenerationResponse.newBuilder()
                        .setSettlementId(b.settlement)
                        .setWinnerCount((Long) old[1])
                        .setDuplicated(true)
                        .build();
            }
            require(b.state == 1, CLOSED);
            require(b.cursor == in.getExpectedSourceCursor(), CURSOR);
            var winners = s.list("SELECT entity_id,score,achieved_ms FROM rank_score WHERE board_id=? AND generation=? ORDER BY "
                            + b.order() + " LIMIT ?",
                    r -> new long[] {u64(r, 1), r.getLong(2), u64(r, 3)}, U(b.id), U(b.generation), top);
            long now = clock.getAsLong();
            s.update("INSERT INTO rank_settlement "
                     + "(settlement_id,board_id,generation,source_epoch,final_cursor,request_id,request_hash,winner_count,sealed_ms) "
                     + "VALUES (?,?,?,?,?,?,?,?,?)",
                    U(settlement), U(b.id), U(b.generation), U(b.epoch), U(b.cursor), U(in.getRequestId()), hash, winners.size(), now);
            for (int i = 0; i < winners.size(); i++) {
                var w = winners.get(i);
                int place = i + 1;
                s.update("INSERT INTO rank_snapshot (settlement_id,`rank`,entity_id,score,achieved_ms) VALUES (?,?,?,?,?)", U(settlement),
                        place, U(w[0]), w[1], U(w[2]));
                for (var tier : in.getRewardsList())
                    if (place >= tier.getFirstRank() && place <= tier.getLastRank())
                        s.update("INSERT INTO rank_mail_reward "
                                 + "(reward_id,settlement_id,player_id,`rank`,attachments,status,mail_id,next_attempt_ms,lease_until_ms,"
                                 + "lease_token,attempts,created_ms,updated_ms) VALUES (?,?,?,?,?,1,0,?,0,0,0,?,?)",
                                U(rewardIds[i]), U(settlement), U(w[0]), place, tier.getAttachments().toByteArray(), now, now, now);
            }
            s.update("UPDATE rank_board SET state=2,settlement_id=?,sealed_ms=? WHERE board_id=?", U(settlement), now, U(b.id));
            return FreezeRankGenerationResponse.newBuilder().setSettlementId(settlement).setWinnerCount(winners.size()).build();
        });
    }
    public void remove(String caller, RemoveRankEntryRequest in, boolean hide, Deadline d) throws SQLException {
        require(in.getBoardId() != 0 && in.getGeneration() != 0 && in.getRequestId() != 0
                        && (hide ? in.getEntityId() == 0 : in.getEntityId() != 0),
                INVALID);
        String hash = (hide ? "hide:" : "remove:") + ServiceCallSignatures.digest(in);
        sql.tx(d, s -> {
            var b = owned(s, in.getBoardId(), caller, in.getGeneration(), 0);
            var old = s.one("SELECT request_hash FROM rank_mutation WHERE board_id=? AND generation=? AND request_id=?",
                    r -> r.getString(1), U(b.id), U(b.generation), U(in.getRequestId()));
            if (old != null) {
                require(old.equals(hash), CONFLICT);
                return null;
            }
            if (hide)
                s.update("UPDATE rank_board SET deleted_ms=? WHERE board_id=?", clock.getAsLong(), U(b.id));
            else {
                require(b.state == 1 && b.deleted == 0, CLOSED);
                s.update("DELETE FROM rank_score WHERE board_id=? AND generation=? AND entity_id=?", U(b.id), U(b.generation),
                        U(in.getEntityId()));
            }
            s.update("INSERT INTO rank_mutation (request_hash,board_id,generation,request_id,created_ms) VALUES (?,?,?,?,?)", hash, U(b.id),
                    U(b.generation), U(in.getRequestId()), clock.getAsLong());
            return null;
        });
    }
    private Board lookup(AuthorityJdbc.Session s, RankBoardKey key) throws SQLException {
        require(valid(key, false), INVALID);
        String query = "SELECT " + BC + " FROM rank_board WHERE board_type=? AND scope=? AND scope_id=? AND deleted_ms=0";
        var args = new ArrayList<Object>(List.of(key.getBoardType(), key.getScopeValue(), U(key.getScopeId())));
        if (!key.getPeriod().isEmpty()) {
            query += " AND period=?";
            args.add(key.getPeriod());
        }
        if (key.getGeneration() != 0) {
            query += " AND generation=?";
            args.add(U(key.getGeneration()));
        }
        query += key.getPeriod().isEmpty() ? " ORDER BY created_ms DESC,board_id DESC LIMIT 1" : " ORDER BY generation DESC LIMIT 1";
        var b = s.one(query, RankStore::board, args.toArray());
        require(b != null, NOT_FOUND);
        return b;
    }
    private static RankEntry entry(ResultSet r) throws SQLException {
        return RankEntry.newBuilder().setEntityId(u64(r, 1)).setScore(r.getLong(2)).setAchievedMs(u64(r, 3)).setRank(u64(r, 4)).build();
    }
    public GetRankPageResponse page(long player, GetRankPageRequest in, Deadline d) throws SQLException {
        require(player != 0, INVALID);
        int size = in.getPageSize() == 0 ? 20 : Math.min(in.getPageSize(), 50);
        long requested = in.getPage() == 0 ? 1 : Integer.toUnsignedLong(in.getPage());
        require(size > 0, INVALID);
        return sql.snapshot(d, s -> {
            var b = lookup(s, in.getBoard());
            long count = s.count("SELECT COUNT(*) FROM rank_score WHERE board_id=? AND generation=?", U(b.id), U(b.generation)),
                 pages = Math.max(1, (count + size - 1) / size), page = Math.min(requested, pages);
            var out = GetRankPageResponse.newBuilder()
                              .setBoard(b.key())
                              .setStateValue(b.state)
                              .setTotalCount(count)
                              .setPage((int) page)
                              .setPageSize(size)
                              .setPageCount(pages);
            var values = s.list("WITH numbered AS (SELECT entity_id,score,achieved_ms,ROW_NUMBER() OVER (ORDER BY " + b.order()
                            + (") AS place FROM rank_score WHERE board_id=? AND generation=?) SELECT entity_id,score,achieved_ms,place "
                               + "FROM numbered WHERE place BETWEEN ? AND ? OR entity_id=? ORDER BY place"),
                    RankStore::entry, U(b.id), U(b.generation), (page - 1) * size + 1, page * size, U(player));
            for (var e : values) {
                if (e.getEntityId() == player)
                    out.setSelf(e);
                if (e.getRank() >= (page - 1) * size + 1 && e.getRank() <= page * size)
                    out.addEntries(e);
            }
            return out.build();
        });
    }
    public GetRankAroundResponse around(long player, GetRankAroundRequest in, Deadline d) throws SQLException {
        require(player != 0, INVALID);
        int radius = in.getRadius() == 0 ? 10 : Math.min(in.getRadius(), 25);
        require(radius > 0, INVALID);
        return sql.snapshot(d, s -> {
            var b = lookup(s, in.getBoard());
            var out = GetRankAroundResponse.newBuilder().setBoard(b.key());
            var values = s.list("WITH numbered AS (SELECT entity_id,score,achieved_ms,ROW_NUMBER() OVER (ORDER BY " + b.order()
                            + (") AS place FROM rank_score WHERE board_id=? AND generation=?), mine AS (SELECT place FROM numbered WHERE "
                               + "entity_id=?) SELECT entity_id,score,achieved_ms,place FROM numbered WHERE place BETWEEN "
                               + "GREATEST(1,(SELECT place FROM mine)-?) AND (SELECT place FROM mine)+? ORDER BY place"),
                    RankStore::entry, U(b.id), U(b.generation), U(player), radius, radius);
            for (var e : values) {
                out.addEntries(e);
                if (e.getEntityId() == player)
                    out.setSelf(e);
            }
            return out.build();
        });
    }
    public record Reward(long id, long player, long settlement, long rank, byte[] attachments, long token, long created) {}
    public List<Long> due(Deadline d, long now) throws SQLException {
        return sql.read(d,
                s
                -> s.list("SELECT reward_id FROM rank_mail_reward WHERE status=1 AND next_attempt_ms<=? AND lease_until_ms<? ORDER BY "
                          + "next_attempt_ms,reward_id LIMIT 32",
                        r -> u64(r, 1), now, now));
    }
    public Reward lease(Deadline d, long id, long now, long token) throws SQLException {
        return sql.tx(d, s -> {
            if (s.update("UPDATE rank_mail_reward SET lease_until_ms=?,lease_token=? WHERE reward_id=? AND status=1 AND lease_until_ms<?",
                        now + 10000, U(token), U(id), now)
                    != 1)
                return null;
            return s.one("SELECT reward_id,player_id,settlement_id,`rank`,attachments,lease_token,created_ms FROM rank_mail_reward WHERE "
                         + "reward_id=?",
                    r -> new Reward(u64(r, 1), u64(r, 2), u64(r, 3), u64(r, 4), r.getBytes(5), u64(r, 6), u64(r, 7)), U(id));
        });
    }
    public void settle(Deadline d, Reward reward, long mail) throws SQLException {
        long now = clock.getAsLong();
        sql.tx(d, s -> {
            var lease = s.one("SELECT status,lease_token FROM rank_mail_reward WHERE reward_id=? FOR UPDATE",
                    r -> new long[] {r.getLong(1), u64(r, 2)}, U(reward.id));
            if (lease == null || lease[0] != 1 || lease[1] != reward.token)
                return null;
            s.update("UPDATE rank_mail_reward SET status=?,mail_id=?,next_attempt_ms=?,lease_until_ms=0,attempts=attempts+1,updated_ms=? "
                     + "WHERE reward_id=?",
                    mail == 0 ? 1 : 2, U(mail), now + 2000, now, U(reward.id));
            return null;
        });
    }
}
