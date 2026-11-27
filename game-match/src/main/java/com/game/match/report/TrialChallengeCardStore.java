package com.game.match.report;

import com.game.common.token.NetworkProbes;
import com.game.pbmysql.PbMysql;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleOutcome;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.match.BattleReportMember;
import com.game.proto.match.BattleReportSummary;
import com.game.proto.match.MatchMode;
import com.game.proto.match.TrialChallengeCardView;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * 五行试炼志 V3 好友约战帖与受控 PVE 录像播放权威存取（对齐 Go {@code go/match/internal/authority/trial_challenge_card.go}）。
 *
 * <p>仅允许本人有效通关的试炼志 PVE 战报签发约战帖；同一 {@code (owner_player_id, battle_id)} 未过期时幂等复用；
 * 新签发时在同事务内给 {@code match_battle_report.ref_count +1}，超出 {@value #MAX_CARDS_PER_PLAYER} 张按最旧 FIFO 淘汰并同步释放引用。
 */
public final class TrialChallengeCardStore {

    /** 每名玩家最多同时保有的有效试炼约战帖数。 */
    public static final int MAX_CARDS_PER_PLAYER = 10;
    /** 试炼约战帖有效期（14 天，毫秒）。 */
    public static final long CARD_TTL_MS = 14L * 24L * 60L * 60L * 1000L;

    private final DataSource dataSource;
    private final LongSupplier clockMs;

    public TrialChallengeCardStore(DataSource dataSource, LongSupplier clockMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    private record ExistingCard(long cardId, long battleId, long expiresMs, byte[] viewBytes) {
    }

    static boolean isTrialVictoryForPlayer(BattleResultEvent event, BattleSettlementData settlement) {
        if (event == null || settlement == null || settlement.getFled() || event.getTotalRounds() == 0) {
            return false;
        }
        eBattleOutcome outcome = event.getOutcome();
        int team = settlement.getPlayerTeamIndex();
        return (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && team == 0)
                || (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && team == 1);
    }

    /**
     * 签发试炼约战帖；若目标对局不属于本人有效通关的试炼志 PVE 战报，返回 {@link Optional#empty()}。
     */
    public Optional<TrialChallengeCardView> createCard(long ownerPlayerId, long battleId, LongSupplier nextCardId)
            throws SQLException {
        if (ownerPlayerId == 0L || battleId == 0L || nextCardId == null) {
            return Optional.empty();
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                Optional<TrialChallengeCardView> out = createCardInTx(c, ownerPlayerId, battleId, nextCardId);
                if (out.isPresent()) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return out;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
    }

    private Optional<TrialChallengeCardView> createCardInTx(Connection c, long ownerPlayerId, long battleId,
                                                            LongSupplier nextCardId) throws SQLException {
        long nowMs = clockMs.getAsLong();

        // 1. 核验本人参战战报列表包含该局，防越权把他人对局签成约战帖。
        byte[] summaryBytes = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT summary FROM match_player_battle_report WHERE player_id=? AND battle_id=? LIMIT 1")) {
            ps.setObject(1, PbMysql.uint64(ownerPlayerId));
            ps.setObject(2, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                summaryBytes = rs.getBytes(1);
            }
        }

        // 2. 锁定本人当前全部约战帖（最多 10 张），先做幂等复用，再收集待淘汰项。
        List<ExistingCard> allCards = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT card_id,battle_id,expires_ms,card_view FROM match_trial_challenge_card "
                        + "WHERE owner_player_id=? ORDER BY created_ms ASC,card_id ASC FOR UPDATE")) {
            ps.setObject(1, PbMysql.uint64(ownerPlayerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    allCards.add(new ExistingCard(
                            Long.parseUnsignedLong(rs.getString(1)),
                            Long.parseUnsignedLong(rs.getString(2)),
                            Long.parseUnsignedLong(rs.getString(3)),
                            rs.getBytes(4)));
                }
            }
        }

        List<ExistingCard> liveCards = new ArrayList<>();
        List<ExistingCard> evictCards = new ArrayList<>();
        for (ExistingCard card : allCards) {
            if (Long.compareUnsigned(card.expiresMs(), nowMs) <= 0) {
                evictCards.add(card);
                continue;
            }
            if (card.battleId() == battleId) {
                try {
                    return Optional.of(TrialChallengeCardView.parseFrom(card.viewBytes()));
                } catch (InvalidProtocolBufferException e) {
                    throw new SQLException("corrupt trial challenge card " + Long.toUnsignedString(card.cardId()), e);
                }
            }
            liveCards.add(card);
        }

        // 3. 读取终局原件，严格核验为试炼志 PVE 胜局。
        byte[] rawCompletion;
        int decision;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT original,terminal_decision FROM match_battle_completion WHERE battle_id=?")) {
            ps.setObject(1, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                rawCompletion = rs.getBytes(1);
                decision = rs.getInt(2);
            }
        }
        if (decision != 1 || rawCompletion == null || rawCompletion.length == 0) {
            return Optional.empty();
        }
        BattleResultEvent event;
        try {
            event = BattleResultEvent.parseFrom(rawCompletion);
        } catch (InvalidProtocolBufferException e) {
            throw new SQLException("corrupt completion payload for battle " + Long.toUnsignedString(battleId), e);
        }
        MatchMode mode = MatchMode.forNumber(event.getMatchMode());
        if (mode != MatchMode.MATCH_MODE_PVE_SOLO && mode != MatchMode.MATCH_MODE_PVE_TEAM) {
            return Optional.empty();
        }
        BattleSettlementData mySettlement = null;
        for (BattleSettlementData item : event.getSettlementsList()) {
            if (item.getPlayerId() == ownerPlayerId) {
                mySettlement = item;
                break;
            }
        }
        if (!isTrialVictoryForPlayer(event, mySettlement)
                || !mySettlement.hasTrialFacts()
                || mySettlement.getTrialFacts().getJournalEntryId() == 0) {
            return Optional.empty();
        }

        String ownerName = resolveOwnerName(c, battleId, ownerPlayerId, summaryBytes);

        // 4. 锁定录像正文行确认存在，确保 ref_count 与卡片绑定一致。
        boolean hasReplay = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM match_battle_report WHERE battle_id=? FOR UPDATE")) {
            ps.setObject(1, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                hasReplay = rs.next();
            }
        }

        // 5. 超出上限按最旧 FIFO 淘汰，并同步回收已钉住的录像引用。
        while (liveCards.size() >= MAX_CARDS_PER_PLAYER) {
            evictCards.add(liveCards.removeFirst());
        }
        Map<Long, Integer> released = new TreeMap<>(Long::compareUnsigned);
        for (ExistingCard victim : evictCards) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM match_trial_challenge_card WHERE card_id=? AND owner_player_id=?")) {
                del.setObject(1, PbMysql.uint64(victim.cardId()));
                del.setObject(2, PbMysql.uint64(ownerPlayerId));
                if (del.executeUpdate() == 1) {
                    try {
                        TrialChallengeCardView v = TrialChallengeCardView.parseFrom(victim.viewBytes());
                        if (v.getHasReplay()) {
                            released.merge(victim.battleId(), 1, Integer::sum);
                        }
                    } catch (InvalidProtocolBufferException ignored) {
                        // 坏行已删，不阻断新卡签发
                    }
                }
            }
        }

        long cardId = nextCardId.getAsLong();
        if (cardId == 0L) {
            throw new IllegalStateException("allocated trial challenge card_id is 0");
        }
        BattleTrialJournalFacts facts = mySettlement.getTrialFacts();
        List<Integer> artifacts = new ArrayList<>(facts.getUsedArtifactEffectIdsList());
        artifacts.sort(Integer::compareUnsigned);
        TrialChallengeCardView view = TrialChallengeCardView.newBuilder()
                .setCardId(cardId)
                .setOwnerPlayerId(ownerPlayerId)
                .setOwnerName(ownerName)
                .setBattleId(battleId)
                .setJournalEntryId(facts.getJournalEntryId())
                .setTrialRuleVersion(facts.getTrialRuleVersion())
                .setVowMask(facts.getChallengeVowMask())
                .setTotalRounds(event.getTotalRounds())
                .setPartySize(facts.getInitialHumanCount())
                .setPetTableId(facts.getUsedPetTableId())
                .addAllArtifactEffectIds(artifacts)
                .setSeasonSealId(facts.getUsedSeasonSealId())
                .setHasReplay(hasReplay)
                .setCreatedMs(nowMs)
                .setExpiresMs(nowMs + CARD_TTL_MS)
                .build();
        byte[] viewBytes = NetworkProbes.bytes(view);

        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_trial_challenge_card("
                        + "card_id,owner_player_id,battle_id,journal_entry_id,vow_mask,total_rounds,created_ms,expires_ms,card_view) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ins.setObject(1, PbMysql.uint64(cardId));
            ins.setObject(2, PbMysql.uint64(ownerPlayerId));
            ins.setObject(3, PbMysql.uint64(battleId));
            ins.setLong(4, Integer.toUnsignedLong(view.getJournalEntryId()));
            ins.setLong(5, Integer.toUnsignedLong(view.getVowMask()));
            ins.setLong(6, Integer.toUnsignedLong(view.getTotalRounds()));
            ins.setObject(7, PbMysql.uint64(view.getCreatedMs()));
            ins.setObject(8, PbMysql.uint64(view.getExpiresMs()));
            ins.setBytes(9, viewBytes);
            ins.executeUpdate();
        }
        if (hasReplay) {
            try (PreparedStatement pin = c.prepareStatement(
                    "UPDATE match_battle_report SET ref_count=ref_count+1 WHERE battle_id=?")) {
                pin.setObject(1, PbMysql.uint64(battleId));
                pin.executeUpdate();
            }
        }
        if (!released.isEmpty()) {
            BattleReportStore.releaseBodies(c, released);
        }
        return Optional.of(view);
    }

    private static String resolveOwnerName(Connection c, long battleId, long ownerPlayerId, byte[] summaryBytes)
            throws SQLException {
        // 优先从 match_global_formation.create_request 读取建房冻结名，表或行缺失时回退战报摘要成员名。
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT create_request FROM match_global_formation WHERE battle_id=?")) {
            ps.setObject(1, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] rawCreate = rs.getBytes(1);
                    if (rawCreate != null && rawCreate.length > 0) {
                        CreateBattleRequest original = CreateBattleRequest.parseFrom(rawCreate);
                        for (BattlePlayerSnapshot p : original.getPlayersList()) {
                            if (p.getPlayerId() == ownerPlayerId && !p.getPlayerName().isEmpty()) {
                                return p.getPlayerName();
                            }
                        }
                    }
                }
            }
        } catch (SQLException | InvalidProtocolBufferException ignored) {
            // 非全局成局或精简测试库不含 match_global_formation 时回退战报摘要
        }
        if (summaryBytes != null && summaryBytes.length > 0) {
            try {
                BattleReportSummary summary = BattleReportSummary.parseFrom(summaryBytes);
                for (BattleReportMember m : summary.getMembersList()) {
                    if (m.getPlayerId() == ownerPlayerId) {
                        return m.getName();
                    }
                }
            } catch (InvalidProtocolBufferException ignored) {
                // 回退空串
            }
        }
        return "";
    }

    /** 只读查询未过期的约战帖详情。 */
    public Optional<TrialChallengeCardView> getCard(long cardId) throws SQLException {
        if (cardId == 0L) {
            return Optional.empty();
        }
        long nowMs = clockMs.getAsLong();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT card_view FROM match_trial_challenge_card WHERE card_id=? AND expires_ms>?")) {
            ps.setObject(1, PbMysql.uint64(cardId));
            ps.setObject(2, PbMysql.uint64(nowMs));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(TrialChallengeCardView.parseFrom(rs.getBytes(1)));
                } catch (InvalidProtocolBufferException e) {
                    throw new SQLException("corrupt trial challenge card " + Long.toUnsignedString(cardId), e);
                }
            }
        }
    }

    /** 仅凭有效未过期约战帖 card_id 读取对应试炼回放正文，不放松 GetBattleReport(battle_id) 的 BR-9 隐私边界。 */
    public Optional<BattleReplayEnvelope> getSharedReplay(long cardId) throws SQLException {
        if (cardId == 0L) {
            return Optional.empty();
        }
        long nowMs = clockMs.getAsLong();
        try (Connection c = dataSource.getConnection()) {
            long battleId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT battle_id FROM match_trial_challenge_card WHERE card_id=? AND expires_ms>?")) {
                ps.setObject(1, PbMysql.uint64(cardId));
                ps.setObject(2, PbMysql.uint64(nowMs));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    battleId = Long.parseUnsignedLong(rs.getString(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT envelope FROM match_battle_report WHERE battle_id=?")) {
                ps.setObject(1, PbMysql.uint64(battleId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of(BattleReplayEnvelope.parseFrom(rs.getBytes(1)));
                    } catch (InvalidProtocolBufferException e) {
                        throw new SQLException("corrupt shared trial replay body for card "
                                + Long.toUnsignedString(cardId), e);
                    }
                }
            }
        }
    }

    /** 删角收尾：删除该玩家名下全部约战帖，并把已钉住的录像引用并入 released 交由调用方统一释放。 */
    public static int purgePlayerCards(Connection c, long playerId, Map<Long, Integer> released) throws SQLException {
        List<ExistingCard> victims = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT card_id,battle_id,expires_ms,card_view FROM match_trial_challenge_card "
                        + "WHERE owner_player_id=? ORDER BY card_id")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    victims.add(new ExistingCard(
                            Long.parseUnsignedLong(rs.getString(1)),
                            Long.parseUnsignedLong(rs.getString(2)),
                            Long.parseUnsignedLong(rs.getString(3)),
                            rs.getBytes(4)));
                }
            }
        }
        int deleted = 0;
        for (ExistingCard v : victims) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM match_trial_challenge_card WHERE card_id=? AND owner_player_id=?")) {
                del.setObject(1, PbMysql.uint64(v.cardId()));
                del.setObject(2, PbMysql.uint64(playerId));
                if (del.executeUpdate() == 1) {
                    deleted++;
                    try {
                        TrialChallengeCardView view = TrialChallengeCardView.parseFrom(v.viewBytes());
                        if (view.getHasReplay()) {
                            released.merge(v.battleId(), 1, Integer::sum);
                        }
                    } catch (InvalidProtocolBufferException ignored) {
                        // 坏行已删
                    }
                }
            }
        }
        return deleted;
    }
}
