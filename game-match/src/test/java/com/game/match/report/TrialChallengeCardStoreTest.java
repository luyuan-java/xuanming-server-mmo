package com.game.match.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.token.NetworkProbes;
import com.game.match.completion.pb.BattleCompletionRow;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.match.ranked.RankedTables;
import com.game.match.rating.RatingTestDatabase;
import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableSchema;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleReplayCodec;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.match.CreateTrialChallengeCardRequest;
import com.game.proto.match.CreateTrialChallengeCardResponse;
import com.game.proto.match.GetBattleReportRequest;
import com.game.proto.match.GetBattleReportResponse;
import com.game.proto.match.GetSharedTrialReplayRequest;
import com.game.proto.match.GetSharedTrialReplayResponse;
import com.game.proto.match.GetTrialChallengeCardRequest;
import com.game.proto.match.GetTrialChallengeCardResponse;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 五行试炼志 V3 卷四目录/誓约支持、好友约战帖签发/幂等/FIFO淘汰/过期/受控共享回放与删角清理测试。 */
class TrialChallengeCardStoreTest {

    private static final int ERR_NOT_FOUND = MatchErrorTip.match_error.kMatchBattleReportNotFound_VALUE;

    private static void createCompletionAndFormationTables(RatingTestDatabase db) throws Exception {
        PbMysql extra = new PbMysql();
        extra.register(BattleCompletionRow.getDefaultInstance());
        extra.register(GlobalFormationRow.getDefaultInstance());
        try (Connection c = db.dataSource.getConnection(); Statement st = c.createStatement()) {
            // 通过 update 执行 DDL（AuthorityDataSource 正常连接不支持 DDL，走 schema 连接）
        }
        for (TableSchema table : extra.tables()) {
            String ddl = table.createTableSql()
                    .replace(" bigint unsigned ", " NUMERIC(20) ")
                    .replace(" int unsigned ", " BIGINT ")
                    .replaceAll(" CHARACTER SET [^ ]+ COLLATE [^ ]+", "")
                    .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
            db.update(ddl);
        }
    }

    private static BattleReplayEnvelope replay(String label) {
        return BattleReplayEnvelope.newBuilder()
                .setFormatVersion(1)
                .setCodec(eBattleReplayCodec.BATTLE_REPLAY_CODEC_GZIP)
                .setRawBytes(256)
                .setPayload(ByteString.copyFromUtf8("gz-" + label))
                .build();
    }

    private static void seedTrialBattle(RatingTestDatabase db, long battleId, long playerId, String playerName,
                                        int mode, int configId, int entryId, int vowMask, int totalRounds,
                                        eBattleOutcome outcome, boolean fled, long stampMs,
                                        BattleReplayEnvelope env) throws Exception {
        CreateBattleRequest create = CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(mode)
                .setBattleConfigId(configId)
                .setRecordReplay(env != null)
                .addPlayers(BattlePlayerSnapshot.newBuilder()
                        .setPlayerId(playerId).setTeamIndex(0).setPlayerName(playerName).setLevel(60).setClassId(1))
                .build();
        BattleSettlementData.Builder settle = BattleSettlementData.newBuilder()
                .setBattleId(battleId)
                .setPlayerId(playerId)
                .setPlayerTeamIndex(0)
                .setOutcome(outcome)
                .setFled(fled)
                .setTotalRounds(totalRounds);
        if (entryId != 0) {
            settle.setTrialFacts(BattleTrialJournalFacts.newBuilder()
                    .setJournalEntryId(entryId)
                    .setTrialRuleVersion(1)
                    .setInitialHumanCount(1)
                    .setUsedPetTableId(101)
                    .addUsedArtifactEffectIds(5)
                    .addUsedArtifactEffectIds(1)
                    .setUsedSeasonSealId(101)
                    .setChallengeVowMask(vowMask));
        }
        BattleResultEvent event = BattleResultEvent.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(mode)
                .setBattleConfigId(configId)
                .setFinishedAtMs(stampMs)
                .setOutcome(outcome)
                .setTotalRounds(totalRounds)
                .addSettlements(settle)
                .build();

        try (Connection c = db.dataSource.getConnection()) {
            c.setAutoCommit(false);
            if (env != null) {
                BattleReportStore.writeInTransaction(c, create, event, env, stampMs);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO match_battle_completion(battle_id,terminal_decision,original) VALUES(?,1,?)")) {
                ps.setObject(1, PbMysql.uint64(battleId));
                ps.setBytes(2, NetworkProbes.bytes(event));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO match_global_formation(battle_id,create_request) VALUES(?,?)")) {
                ps.setObject(1, PbMysql.uint64(battleId));
                ps.setBytes(2, NetworkProbes.bytes(create));
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    private static int refCountOf(RatingTestDatabase db, long battleId) throws Exception {
        try (Connection c = db.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT ref_count FROM match_battle_report WHERE battle_id=?")) {
            ps.setObject(1, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    @Test
    void 卷四三境明雷目录映射与试炼誓约支持白名单对齐() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        RankedTables tables = RankedTables.from(ConfigTables.load(dir));

        assertThat(tables.trialContextFor(MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 113, null, null))
                .isPresent().get().extracting(com.game.proto.TrialBattleContext::getJournalEntryId).isEqualTo(401);
        assertThat(tables.trialContextFor(MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 114, null, null))
                .isPresent().get().extracting(com.game.proto.TrialBattleContext::getJournalEntryId).isEqualTo(402);
        assertThat(tables.trialContextFor(MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 115, null, null))
                .isPresent().get().extracting(com.game.proto.TrialBattleContext::getJournalEntryId).isEqualTo(403);

        for (int supported : List.of(101, 112, 113, 114, 115)) {
            assertThat(tables.supportsTrialVows(supported)).as("dungeon " + supported).isTrue();
        }
        for (int unsupported : List.of(109, 110, 202)) {
            assertThat(tables.supportsTrialVows(unsupported)).as("dungeon " + unsupported).isFalse();
        }
        assertThat(RankedTables.isValidTrialVowMask(0)).isTrue();
        assertThat(RankedTables.isValidTrialVowMask(RankedTables.TRIAL_VOW_MASK_ALL)).isTrue();
        assertThat(RankedTables.isValidTrialVowMask(0x10)).isFalse();
    }

    @Test
    void 约战帖签发幂等_越权与败局拒绝_跨人受控共享回放_十张FIFO淘汰与过期和删角清理() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            createCompletionAndFormationTables(db);
            AtomicLong nowMs = new AtomicLong(1_900_000_000_000L);
            AtomicLong cardSeq = new AtomicLong(50_000L);
            TrialChallengeCardStore cardStore = new TrialChallengeCardStore(db.dataSource, nowMs::get);
            BattleReportStore reportStore = new BattleReportStore(db.dataSource);

            MatchMethodHandler createHandler = TrialChallengeCardHandlers.createHandler(cardStore, cardSeq::incrementAndGet);
            MatchMethodHandler getHandler = TrialChallengeCardHandlers.getHandler(cardStore);
            MatchMethodHandler replayHandler = TrialChallengeCardHandlers.sharedReplayHandler(cardStore);
            MatchMethodHandler rawReportHandler = BattleReportHandlers.getHandler(reportStore);

            SessionContext owner = SessionContext.newBuilder().setPlayerId(1001L).build();
            SessionContext friend = SessionContext.newBuilder().setPlayerId(2002L).build();

            // 1. 合法试炼胜局 7001 + 非试炼/败局 7002/7003
            BattleReplayEnvelope env1 = replay("b7001");
            seedTrialBattle(db, 7001L, 1001L, "风行剑修", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 113, 401,
                    0x05, 6, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, false, nowMs.get(), env1);
            seedTrialBattle(db, 7002L, 1001L, "风行剑修", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 113, 401,
                    0x05, 6, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, false, nowMs.get() + 1, replay("loss"));
            seedTrialBattle(db, 7003L, 1001L, "风行剑修", MatchMode.MATCH_MODE_1V1_VALUE, 1, 0,
                    0, 4, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, false, nowMs.get() + 2, replay("pvp"));

            assertThat(refCountOf(db, 7001L)).isEqualTo(1);

            // 签发成功 + ref_count 1 -> 2
            var c1Body = (MatchMethodHandler.Reply.Body) createHandler.handle(owner,
                    CreateTrialChallengeCardRequest.newBuilder().setBattleId(7001L).build().toByteString(), Deadline.after(3000));
            CreateTrialChallengeCardResponse c1 = CreateTrialChallengeCardResponse.parseFrom(c1Body.bytes());
            assertThat(c1.getErrorMessage().getId()).isZero();
            assertThat(c1.getCard().getCardId()).isEqualTo(50_001L);
            assertThat(c1.getCard().getOwnerName()).isEqualTo("风行剑修");
            assertThat(c1.getCard().getJournalEntryId()).isEqualTo(401);
            assertThat(c1.getCard().getVowMask()).isEqualTo(0x05);
            assertThat(c1.getCard().getArtifactEffectIdsList()).containsExactly(1, 5);
            assertThat(c1.getCard().getHasReplay()).isTrue();
            assertThat(refCountOf(db, 7001L)).isEqualTo(2);

            // 幂等复用：返回同一 card_id 且不重复加 ref_count
            var c1DupBody = (MatchMethodHandler.Reply.Body) createHandler.handle(owner,
                    CreateTrialChallengeCardRequest.newBuilder().setBattleId(7001L).build().toByteString(), Deadline.after(3000));
            CreateTrialChallengeCardResponse c1Dup = CreateTrialChallengeCardResponse.parseFrom(c1DupBody.bytes());
            assertThat(c1Dup.getCard().getCardId()).isEqualTo(50_001L);
            assertThat(refCountOf(db, 7001L)).isEqualTo(2);

            // 他人越权签发、败局、非试炼 PVP 均拒收
            for (var bad : List.of(
                    createHandler.handle(friend, CreateTrialChallengeCardRequest.newBuilder().setBattleId(7001L).build().toByteString(), Deadline.after(3000)),
                    createHandler.handle(owner, CreateTrialChallengeCardRequest.newBuilder().setBattleId(7002L).build().toByteString(), Deadline.after(3000)),
                    createHandler.handle(owner, CreateTrialChallengeCardRequest.newBuilder().setBattleId(7003L).build().toByteString(), Deadline.after(3000)))) {
                assertThat(CreateTrialChallengeCardResponse.parseFrom(((MatchMethodHandler.Reply.Body) bad).bytes())
                        .getErrorMessage().getId()).isEqualTo(ERR_NOT_FOUND);
            }

            // 2. 好友通过 card_id 查卡片与看共享回放均成功，而直接 GetBattleReport(battle_id) 仍按 BR-9 拒绝
            var gBody = (MatchMethodHandler.Reply.Body) getHandler.handle(friend,
                    GetTrialChallengeCardRequest.newBuilder().setCardId(50_001L).build().toByteString(), Deadline.after(3000));
            assertThat(GetTrialChallengeCardResponse.parseFrom(gBody.bytes()).getCard().getBattleId()).isEqualTo(7001L);

            var rBody = (MatchMethodHandler.Reply.Body) replayHandler.handle(friend,
                    GetSharedTrialReplayRequest.newBuilder().setCardId(50_001L).build().toByteString(), Deadline.after(3000));
            assertThat(GetSharedTrialReplayResponse.parseFrom(rBody.bytes()).getReplay()).isEqualTo(env1);

            var directBody = (MatchMethodHandler.Reply.Body) rawReportHandler.handle(friend,
                    GetBattleReportRequest.newBuilder().setBattleId(7001L).build().toByteString(), Deadline.after(3000));
            assertThat(GetBattleReportResponse.parseFrom(directBody.bytes()).getErrorMessage().getId()).isEqualTo(ERR_NOT_FOUND);

            // 3. 连续签发至第 11 张：最旧的 50001(battle 7001) 被 FIFO 淘汰，ref_count 从 2 降回 1
            List<Long> cardIds = new ArrayList<>();
            cardIds.add(50_001L);
            for (int i = 2; i <= 11; i++) {
                long bid = 7100L + i;
                nowMs.addAndGet(1000L);
                seedTrialBattle(db, bid, 1001L, "风行剑修", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 114, 402,
                        1, 5, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, false, nowMs.get(), replay("b" + bid));
                var resp = CreateTrialChallengeCardResponse.parseFrom(
                        ((MatchMethodHandler.Reply.Body) createHandler.handle(owner,
                                CreateTrialChallengeCardRequest.newBuilder().setBattleId(bid).build().toByteString(),
                                Deadline.after(3000))).bytes());
                assertThat(resp.getErrorMessage().getId()).isZero();
                cardIds.add(resp.getCard().getCardId());
            }
            assertThat(refCountOf(db, 7001L)).isEqualTo(1);
            assertThat(cardStore.getCard(50_001L)).isEmpty();
            assertThat(cardStore.getCard(cardIds.getLast())).isPresent();

            // 4. 删角收尾：清除 1001 全部战报与约战帖后，最新对局 7111 的 ref_count 归零且正文已删
            try (Connection c = db.dataSource.getConnection()) {
                c.setAutoCommit(false);
                BattleReportStore.purgePlayerReports(c, 1001L);
                c.commit();
            }
            assertThat(refCountOf(db, 7111L)).isEqualTo(-1);
            assertThat(cardStore.getCard(cardIds.getLast())).isEmpty();

            // 5. 14 天过期后 GetCard 与 GetSharedTrialReplay 统一返回空
            long expBid = 7999L;
            nowMs.addAndGet(1000L);
            seedTrialBattle(db, expBid, 2002L, "月汐行者", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 115, 403,
                    8, 4, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, false, nowMs.get(), replay("exp"));
            long expCardId = cardStore.createCard(2002L, expBid, cardSeq::incrementAndGet).orElseThrow().getCardId();
            assertThat(cardStore.getCard(expCardId)).isPresent();
            nowMs.addAndGet(TrialChallengeCardStore.CARD_TTL_MS + 1L);
            assertThat(cardStore.getCard(expCardId)).isEmpty();
            assertThat(cardStore.getSharedReplay(expCardId)).isEmpty();
        }
    }
}
