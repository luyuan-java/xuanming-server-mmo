package com.game.match.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.api.MatchInternalService;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.MatchProperties;
import com.game.match.activity.ActivityBattleService;
import com.game.match.activity.MatchInternalServiceImpl;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.ranked.RankedService;
import com.game.match.ranked.RankedStore;
import com.game.match.ranked.RankedTables;
import com.game.match.rating.RatingTestDatabase;
import com.game.match.report.BattleReportHandlers;
import com.game.match.report.BattleReportStore;
import com.game.match.script.BattleScriptCatalog;
import com.game.match.script.BattleScriptResolver;
import com.game.match.script.BattleScriptService;
import com.game.match.script.BattleScriptStore;
import com.game.match.script.BattleScriptValidator;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.BattleScript;
import com.game.proto.BattleScriptRole;
import com.game.proto.BattleScriptRule;
import com.game.proto.BattleScriptTarget;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTeamScript;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleReplayCodec;
import com.game.proto.eBattleScriptOrigin;
import com.game.proto.eBattleScriptTargetSide;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.match.BattleScriptLoadoutEntry;
import com.game.proto.match.BattleScriptReviewReject;
import com.game.proto.match.BattleScriptReviewState;
import com.game.proto.match.DeleteBattleScriptRequest;
import com.game.proto.match.GetBattleReportRequest;
import com.game.proto.match.GetBattleReportResponse;
import com.game.proto.match.GetRankedBattleResultRequest;
import com.game.proto.match.GetRankedBattleResultResponse;
import com.game.proto.match.GetRankedProfileRequest;
import com.game.proto.match.GetRankedProfileResponse;
import com.game.proto.match.ListBattleReportsRequest;
import com.game.proto.match.ListBattleReportsResponse;
import com.game.proto.match.ListBattleScriptsResponse;
import com.game.proto.match.ListPendingBattleScriptsRequest;
import com.game.proto.match.ListPendingBattleScriptsResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.RankedCosmeticLoadout;
import com.game.proto.match.RankedMatchRatingStatus;
import com.game.proto.match.RankedTier;
import com.game.proto.match.ReviewBattleScriptRequest;
import com.game.proto.match.ReviewBattleScriptResponse;
import com.game.proto.match.SaveRankedPresetRequest;
import com.game.proto.match.SaveRankedPresetResponse;
import com.game.proto.match.SetBattleScriptLoadoutRequest;
import com.game.proto.match.SetBattleScriptLoadoutResponse;
import com.game.proto.match.SubmitBattleScriptRequest;
import com.game.proto.match.SubmitBattleScriptResponse;
import com.game.proto.match.UpdateRankedCosmeticRequest;
import com.game.proto.match.UpdateRankedCosmeticResponse;
import com.game.table.ConfigTables;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 战报回放（379/380）、战斗剧本与审核（387–392）、论剑排位预设档案（448–451）综合回归。 */
class MatchReportsScriptsAndRankedTest {

    private static ConfigTables config;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
    }

    @Test
    void 战报回放_落库查询鉴权_超百条挤出并回收正文引用() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            BattleReportStore store = new BattleReportStore(db.dataSource);
            MatchMethodHandler listHandler = BattleReportHandlers.listHandler(store);
            MatchMethodHandler getHandler = BattleReportHandlers.getHandler(store);

            BattleReplayEnvelope validEnv = BattleReplayEnvelope.newBuilder()
                    .setFormatVersion(1)
                    .setCodec(eBattleReplayCodec.BATTLE_REPLAY_CODEC_GZIP)
                    .setRawBytes(128)
                    .setPayload(ByteString.copyFromUtf8("gzip-bytes"))
                    .build();

            try (Connection c = db.dataSource.getConnection()) {
                c.setAutoCommit(false);
                // 连续写入 101 局给玩家 1001 & 1002：第 1 局应被两人同时挤出并删除正文
                for (long bid = 1; bid <= 101; bid++) {
                    CreateBattleRequest create = CreateBattleRequest.newBuilder()
                            .setBattleId(bid)
                            .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                            .setBattleConfigId(1)
                            .setRecordReplay(true)
                            .addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(1001L).setTeamIndex(0).setPlayerName("甲").setLevel(30).setClassId(1))
                            .addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(1002L).setTeamIndex(1).setPlayerName("乙").setLevel(30).setClassId(2))
                            .build();
                    BattleResultEvent ev = BattleResultEvent.newBuilder()
                            .setBattleId(bid)
                            .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                            .setBattleConfigId(1)
                            .setFinishedAtMs(1_800_000_000_000L + bid * 1000L)
                            .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                            .setTotalRounds(3)
                            .addSettlements(BattleSettlementData.newBuilder().setPlayerId(1001L).setPlayerTeamIndex(0))
                            .addSettlements(BattleSettlementData.newBuilder().setPlayerId(1002L).setPlayerTeamIndex(1))
                            .build();
                    BattleReportStore.WriteResult wr = BattleReportStore.writeInTransaction(c, create, ev, validEnv, 1_800_000_000_000L + bid);
                    assertThat(wr.stored()).isTrue();
                    if (bid == 101) {
                        assertThat(wr.evicted()).isEqualTo(2);
                    }
                }
                c.commit();
            }

            // 1001 列表恰为 100 条（最新的 101 在最前，1 已被挤出）
            MatchMethodHandler.Reply.Body listReply = (MatchMethodHandler.Reply.Body) listHandler.handle(
                    SessionContext.newBuilder().setPlayerId(1001L).build(),
                    ListBattleReportsRequest.getDefaultInstance().toByteString(), Deadline.after(3000));
            ListBattleReportsResponse listResp = ListBattleReportsResponse.parseFrom(listReply.bytes());
            assertThat(listResp.getErrorMessage().getId()).isZero();
            assertThat(listResp.getReportsCount()).isEqualTo(100);
            assertThat(listResp.getReports(0).getBattleId()).isEqualTo(101L);
            assertThat(listResp.getReports(99).getBattleId()).isEqualTo(2L);

            // 1001 可读 101 的回放；非参战者 9999 读 101 与 1001 读已被挤出的 1 统一回 kMatchBattleReportNotFound
            MatchMethodHandler.Reply.Body okBody = (MatchMethodHandler.Reply.Body) getHandler.handle(
                    SessionContext.newBuilder().setPlayerId(1001L).build(),
                    GetBattleReportRequest.newBuilder().setBattleId(101L).build().toByteString(), Deadline.after(3000));
            assertThat(GetBattleReportResponse.parseFrom(okBody.bytes()).getReplay()).isEqualTo(validEnv);

            MatchMethodHandler.Reply.Body stranger = (MatchMethodHandler.Reply.Body) getHandler.handle(
                    SessionContext.newBuilder().setPlayerId(9999L).build(),
                    GetBattleReportRequest.newBuilder().setBattleId(101L).build().toByteString(), Deadline.after(3000));
            assertThat(GetBattleReportResponse.parseFrom(stranger.bytes()).getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchBattleReportNotFound_VALUE);

            MatchMethodHandler.Reply.Body evicted = (MatchMethodHandler.Reply.Body) getHandler.handle(
                    SessionContext.newBuilder().setPlayerId(1001L).build(),
                    GetBattleReportRequest.newBuilder().setBattleId(1L).build().toByteString(), Deadline.after(3000));
            assertThat(GetBattleReportResponse.parseFrom(evicted.bytes()).getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchBattleReportNotFound_VALUE);
            assertThat(db.count("match_battle_report")).isEqualTo(100L);
        }
    }

    @Test
    void 战斗剧本_官方内置目录校验_提交审校选用与评分局过滤玩家剧本() throws Exception {
        BattleScriptValidator.Tables tables = BattleScriptValidator.tablesFrom(config);
        BattleScriptCatalog catalog = BattleScriptCatalog.loadOfficial(tables);
        assertThat(catalog.officialScripts()).hasSize(2);
        assertThat(catalog.official(1)).isPresent();
        assertThat(catalog.official(2)).isPresent();

        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_800_000_000_000L);
            AtomicLong ids = new AtomicLong(50_000L);
            BattleScriptStore store = new BattleScriptStore(db.dataSource, now::get);
            BattleScriptService service = new BattleScriptService(store, catalog, tables, ids::incrementAndGet);
            BattleScriptResolver resolver = new BattleScriptResolver(catalog, tables, store);

            BattleScript playerScript = BattleScript.newBuilder()
                    .setSchemaVersion(1)
                    .setName("我的集火剧本")
                    .addRoles(BattleScriptRole.newBuilder()
                            .setRoleId(1).setOrder(10).setName("输出")
                            .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                            .addRules(BattleScriptRule.newBuilder()
                                    .setPriority(10).setAction(eBattleActionType.BATTLE_ACTION_ATTACK)
                                    .setTarget(BattleScriptTarget.newBuilder().setSide(eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY))))
                    .build();

            // 1. 提交新剧本 -> PENDING，未审前不能选用
            SubmitBattleScriptResponse sub = service.submit(2001L, SubmitBattleScriptRequest.newBuilder()
                    .setScript(playerScript).build());
            assertThat(sub.getErrorMessage().getId()).isZero();
            long scriptId = sub.getScript().getScriptId();
            assertThat(sub.getScript().getRevision()).isEqualTo(1);
            assertThat(sub.getScript().getReviewState()).isEqualTo(BattleScriptReviewState.BATTLE_SCRIPT_REVIEW_PENDING);

            SetBattleScriptLoadoutResponse notApproved = service.setLoadout(2001L, SetBattleScriptLoadoutRequest.newBuilder()
                    .setEntry(BattleScriptLoadoutEntry.newBuilder()
                            .setMatchMode(0).setBattleConfigId(0)
                            .setOrigin(eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_PLAYER)
                            .setPlayerScriptId(scriptId))
                    .build());
            assertThat(notApproved.getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchBattleScriptNotApproved_VALUE);

            // 2. MatchInternal 运营审核（387/388）：未签名拒 DENIED，ops 签名后拉列表并审核通过
            String opsSecret = "ops-script-review-secret-0123456789";
            MatchInternalServiceImpl internal = new MatchInternalServiceImpl(
                    mock(ActivityBattleService.class), Runnable::run,
                    new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null),
                    service, opsSecret);

            ListPendingBattleScriptsResponse denied = internal.listPendingBattleScripts(
                    ListPendingBattleScriptsRequest.newBuilder().setLimit(10).build()).get(3, TimeUnit.SECONDS);
            assertThat(denied.getReject()).isEqualTo(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_DENIED);

            ListPendingBattleScriptsRequest signedList = ServiceCallSignatures.sign(
                    MatchInternalService.LIST_PENDING_SCRIPTS_METHOD,
                    ListPendingBattleScriptsRequest.newBuilder().setLimit(10).build(),
                    MatchInternalService.OPS_CALLER, opsSecret, System.currentTimeMillis());
            ListPendingBattleScriptsResponse pending = internal.listPendingBattleScripts(signedList).get(3, TimeUnit.SECONDS);
            assertThat(pending.getReject()).isEqualTo(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_NONE);
            assertThat(pending.getScriptsList()).hasSize(1);

            ReviewBattleScriptRequest signedApprove = ServiceCallSignatures.sign(
                    MatchInternalService.REVIEW_SCRIPT_METHOD,
                    ReviewBattleScriptRequest.newBuilder().setScriptId(scriptId).setRevision(1)
                            .setApprove(true).setReviewer("gm_alice").build(),
                    MatchInternalService.OPS_CALLER, opsSecret, System.currentTimeMillis());
            ReviewBattleScriptResponse approved = internal.reviewBattleScript(signedApprove).get(3, TimeUnit.SECONDS);
            assertThat(approved.getReject()).isEqualTo(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_NONE);

            // 重复审同版 -> STALE
            ReviewBattleScriptResponse stale = internal.reviewBattleScript(signedApprove).get(3, TimeUnit.SECONDS);
            assertThat(stale.getReject()).isEqualTo(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_STALE);

            // 3. 审核通过后：显式评分模式绑定玩家剧本被拒；通用模式（mode=0）绑定成功
            SetBattleScriptLoadoutResponse ratedDenied = service.setLoadout(2001L, SetBattleScriptLoadoutRequest.newBuilder()
                    .setEntry(BattleScriptLoadoutEntry.newBuilder()
                            .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE).setBattleConfigId(0)
                            .setOrigin(eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_PLAYER)
                            .setPlayerScriptId(scriptId))
                    .build());
            assertThat(ratedDenied.getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchBattleScriptLoadoutInvalid_VALUE);

            SetBattleScriptLoadoutResponse boundOk = service.setLoadout(2001L, SetBattleScriptLoadoutRequest.newBuilder()
                    .setEntry(BattleScriptLoadoutEntry.newBuilder()
                            .setMatchMode(0).setBattleConfigId(0)
                            .setOrigin(eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_PLAYER)
                            .setPlayerScriptId(scriptId))
                    .build());
            assertThat(boundOk.getErrorMessage().getId()).isZero();
            assertThat(boundOk.getLoadoutList()).hasSize(1);

            // 4. 建房解析：PVE 选用玩家剧本 + 怪物方官方绑定（副本 3）；评分 PVP_TEAM 跳过玩家剧本
            List<BattleTeamScript> pveScripts = resolver.resolveForBattle(9001L, MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 3,
                    List.of(BattlePlayerSnapshot.newBuilder().setPlayerId(2001L).setTeamIndex(0).build()));
            assertThat(pveScripts).hasSize(2);
            assertThat(pveScripts.get(0).getOrigin()).isEqualTo(eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_PLAYER);
            assertThat(pveScripts.get(0).getPlayerScriptId()).isEqualTo(scriptId);
            assertThat(pveScripts.get(1).getTeamIndex()).isEqualTo(1);
            assertThat(pveScripts.get(1).getScript().getScriptId()).isEqualTo(2);

            List<BattleTeamScript> ratedScripts = resolver.resolveForBattle(9002L, MatchMode.MATCH_MODE_PVP_TEAM_VALUE, 5,
                    List.of(BattlePlayerSnapshot.newBuilder().setPlayerId(2001L).setTeamIndex(0).build(),
                            BattlePlayerSnapshot.newBuilder().setPlayerId(2002L).setTeamIndex(1).build()));
            assertThat(ratedScripts).isEmpty();

            // 5. 删除剧本同步清选用 + 审核流水游标清理
            assertThat(service.delete(2001L, DeleteBattleScriptRequest.newBuilder()
                    .setScriptId(scriptId).setExpectedRevision(1).build()).getErrorMessage().getId()).isZero();
            ListBattleScriptsResponse afterDel = service.list(2001L);
            assertThat(afterDel.getMyScriptsList()).isEmpty();
            assertThat(afterDel.getLoadoutList()).isEmpty();

            now.addAndGet(Duration.ofDays(31).toMillis());
            BattleScriptStore.PurgeResult purged = store.purgeExpiredReviews(Duration.ofDays(30), BattleScriptStore.ReviewCursor.START, 10);
            assertThat(purged.deleted()).isEqualTo(1L);
        }
    }

    @Test
    void 论剑排位_默认预设与版本校验_未拥有饰品拒绝_四场里程碑解锁后可佩戴与查战果() throws Exception {
        RankedTables tables = RankedTables.from(config);
        assertThat(tables.presetViews()).hasSize(6);
        assertThat(tables.spreadPoints(10)).isEqualTo(100);
        assertThat(tables.spreadPoints(45)).isEqualTo(200);
        assertThat(tables.spreadPoints(180)).isEqualTo(300);

        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_800_000_000_000L);
            RankedStore store = new RankedStore(db.dataSource, tables, now::get);
            RankedService service = new RankedService(store, tables, now::get);

            // 1. 首次拉档案：默认预设 101 + 203，未亮段
            GetRankedProfileResponse initial = service.getProfile(3001L,
                    GetRankedProfileRequest.newBuilder().setIncludeHistory(true).build());
            assertThat(initial.getErrorMessage().getId()).isZero();
            assertThat(initial.getCurrentSeason().getSeasonId()).isEqualTo(1);
            assertThat(initial.getCurrentProgress().getQualified()).isFalse();
            assertThat(initial.getCurrentProgress().getCurrentTier()).isEqualTo(RankedTier.RANKED_TIER_UNSPECIFIED);
            assertThat(initial.getSelectedPresets().getCharacterPresetId()).isEqualTo(tables.rule().getDefaultCharacterPresetId());
            assertThat(initial.getSelectedPresets().getPetPresetId()).isEqualTo(tables.rule().getDefaultPetPresetId());
            assertThat(initial.getPresetsCount()).isEqualTo(6);

            // 2. 改默认预设：版本不符回 16036，角色/宝宝填反回 16035，合法保存成功
            SaveRankedPresetResponse badVer = service.savePreset(3001L, SaveRankedPresetRequest.newBuilder()
                    .setCharacterPresetId(102).setPetPresetId(201).setRuleVersion(tables.ruleVersion() + 99).build());
            assertThat(badVer.getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchRankedRuleVersionMismatch_VALUE);

            SaveRankedPresetResponse badPreset = service.savePreset(3001L, SaveRankedPresetRequest.newBuilder()
                    .setCharacterPresetId(201).setPetPresetId(102).setRuleVersion(tables.ruleVersion()).build());
            assertThat(badPreset.getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchRankedPresetInvalid_VALUE);

            SaveRankedPresetResponse saved = service.savePreset(3001L, SaveRankedPresetRequest.newBuilder()
                    .setCharacterPresetId(102).setPetPresetId(201).setRuleVersion(tables.ruleVersion()).build());
            assertThat(saved.getErrorMessage().getId()).isZero();
            assertThat(saved.getSelectedPresets().getCharacterPresetId()).isEqualTo(102);
            assertThat(saved.getSelectedPresets().getPetPresetId()).isEqualTo(201);

            // 3. 未拥有饰品尝试佩戴 -> 拒绝 16035
            UpdateRankedCosmeticResponse unowned = service.updateCosmetic(3001L, UpdateRankedCosmeticRequest.newBuilder()
                    .setLoadout(RankedCosmeticLoadout.newBuilder().setEquippedAvatarPatternCode("s1_avatar_pattern"))
                    .build());
            assertThat(unowned.getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchRankedPresetInvalid_VALUE);

            // 4. 连续结算 4 场有效排位：首场 1516 亮段为初试、第 2 场 1532 破冰升青锋、第 4 场解锁头像纹样里程碑并可查战果与佩戴
            for (long bid = 7001; bid <= 7004; bid++) {
                now.addAndGet(60_000L);
                var settled = store.recordBattleSettlement(new RankedStore.SettlementInput(
                        3001L, bid, 1, 3, RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED,
                        eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, 6, true, false, 1600L, 102, 201));
                if (bid == 7001) {
                    assertThat(settled.getNewlyQualified()).isTrue();
                    assertThat(settled.getTierBefore()).isEqualTo(RankedTier.RANKED_TIER_UNSPECIFIED);
                    assertThat(settled.getTierAfter()).isEqualTo(RankedTier.RANKED_TIER_CHUSHI);
                }
                if (bid == 7002) {
                    assertThat(settled.getTierBefore()).isEqualTo(RankedTier.RANKED_TIER_CHUSHI);
                    assertThat(settled.getTierAfter()).isEqualTo(RankedTier.RANKED_TIER_QINGFENG);
                }
                if (bid == 7004) {
                    assertThat(settled.getSeasonValidMatchesAfter()).isEqualTo(4);
                    assertThat(settled.getNewlyUnlockedRewardsCount()).isEqualTo(1);
                    assertThat(settled.getNewlyUnlockedRewards(0).getItemCode()).isEqualTo("s1_avatar_pattern");
                }
            }

            UpdateRankedCosmeticResponse equipped = service.updateCosmetic(3001L, UpdateRankedCosmeticRequest.newBuilder()
                    .setLoadout(RankedCosmeticLoadout.newBuilder().setEquippedAvatarPatternCode("s1_avatar_pattern"))
                    .build());
            assertThat(equipped.getErrorMessage().getId()).isZero();
            assertThat(equipped.getLoadout().getEquippedAvatarPatternCode()).isEqualTo("s1_avatar_pattern");

            GetRankedBattleResultResponse latest = service.getBattleResult(3001L,
                    GetRankedBattleResultRequest.newBuilder().setBattleId(0L).build());
            assertThat(latest.getErrorMessage().getId()).isZero();
            assertThat(latest.getResult().getBattleId()).isEqualTo(7004L);
            assertThat(latest.getResult().getRatingAfterCenti()).isEqualTo(156_400L);
            assertThat(latest.getResult().getTierAfter()).isEqualTo(RankedTier.RANKED_TIER_QINGFENG);
        }
    }
}
