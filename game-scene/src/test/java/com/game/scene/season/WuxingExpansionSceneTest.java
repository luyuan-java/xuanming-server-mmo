package com.game.scene.season;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.engine.BattleConstants;
import com.game.battle.engine.WuxingExpansionRules;
import com.game.player.store.state.PlayerSeasonState;
import com.game.player.store.state.PlayerState;
import com.game.proto.AbandonWuxingTowerRunRequest;
import com.game.proto.AbandonWuxingTowerRunResponse;
import com.game.proto.BattleSettlementData;
import com.game.proto.DeleteBattleBuildBookmarkRequest;
import com.game.proto.DeleteBattleBuildBookmarkResponse;
import com.game.proto.EquipWuxingFormationRequest;
import com.game.proto.EquipWuxingFormationResponse;
import com.game.proto.GetSeasonStateRequest;
import com.game.proto.GetSeasonStateResponse;
import com.game.proto.GetWuxingTowerStateRequest;
import com.game.proto.GetWuxingTowerStateResponse;
import com.game.proto.MessageContent;
import com.game.proto.PickWuxingTowerSigilRequest;
import com.game.proto.PickWuxingTowerSigilResponse;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.SaveBattleBuildBookmarkRequest;
import com.game.proto.SaveBattleBuildBookmarkResponse;
import com.game.proto.SavedBattleBuildBookmarkView;
import com.game.proto.StartWuxingTowerRunRequest;
import com.game.proto.StartWuxingTowerRunResponse;
import com.game.proto.WuxingTowerSettlementEcho;
import com.game.proto.eBattleOutcome;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.PlayerSeason;
import com.game.scene.testing.Contracts;
import com.game.scene.world.PlayerData;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.Vec3;
import com.game.table.CommonErrorTip;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 五行玩法三大扩展 V1（515–521：五行阵法、五行幻境 10 层与周榜、战报配装收藏夹及备战权威注入）端到端单测。 */
class WuxingExpansionSceneTest {

    private static final String SERVICE = "SceneSeasonClientPlayer";

    private static ScenePlayer enterPlayer(BattleFixture f, int sessionId, long playerId, String name) {
        f.repo.put(new PlayerData(playerId, 1, 3, 1, "look-" + playerId, 30, f.scene1.configId(),
                new Vec3(5, 5, 0), PlayerState.getDefaultInstance(), name));
        ScenePlayer p = f.reenter(sessionId, playerId, 1, f.scene1);
        f.drain();
        f.sink.clear();
        f.locks.clearCalls();
        return p;
    }

    private static MessageContent lastReply(BattleFixture f, ScenePlayer p, String method, long reqId) {
        int msgId = Contracts.REGISTRY.requireId(SERVICE, method);
        return f.pushes(p, msgId).stream().filter(m -> m.getId() == reqId).findFirst().orElseThrow();
    }

    private static PrepareBattleResponse prepareSync(BattleFixture f, PrepareBattleRequest req) {
        CompletableFuture<PrepareBattleResponse> fut = f.battle.prepare(req);
        f.drain();
        return BattleFixture.done(fut);
    }

    @Test
    void 切换五行阵法515_校验边界_存档往返与备战快照注入() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = enterPlayer(f, 11, 1001L, "阵法修士");

        // 非法阵法 ID 拒绝
        long badId = f.request(player, SERVICE, "EquipWuxingFormation",
                EquipWuxingFormationRequest.newBuilder().setFormationId(6).build());
        EquipWuxingFormationResponse badResp = EquipWuxingFormationResponse.parseFrom(
                lastReply(f, player, "EquipWuxingFormation", badId).getSerializedMessage());
        assertThat(badResp.getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

        // 免费切换至 3 号风扬阵
        long okId = f.request(player, SERVICE, "EquipWuxingFormation",
                EquipWuxingFormationRequest.newBuilder().setFormationId(3).build());
        EquipWuxingFormationResponse okResp = EquipWuxingFormationResponse.parseFrom(
                lastReply(f, player, "EquipWuxingFormation", okId).getSerializedMessage());
        assertThat(okResp.getErrorMessage().getId()).isZero();
        assertThat(okResp.getEquippedFormationId()).isEqualTo(3);
        assertThat(okResp.getState().getEquippedFormationId()).isEqualTo(3);

        // 存档序列化/恢复往返保持阵法 ID
        PlayerSeasonState saved = player.season().toState();
        assertThat(PlayerSeason.restore(saved).equippedFormationId()).isEqualTo(3);

        // 备战快照权威注入 formation_id = 3
        long nowMs = f.clock.epochMillis();
        PrepareBattleResponse prep = prepareSync(f, PrepareBattleRequest.newBuilder()
                .setPlayerId(player.playerId())
                .setBattleId(7001L)
                .setBattleNodeId(1)
                .setDeadlineMs(nowMs + 120_000L)
                .setPrepareDeadlineMs(nowMs + 30_000L)
                .setBattleConfigId(101)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .build());
        assertThat(prep.hasErrorMessage()).isFalse();
        assertThat(prep.getSnapshot().getFormationId()).isEqualTo(3);
    }

    @Test
    void 五行幻境516至519完整爬塔推进_权威备战校验_符印抽选_放弃与周榜排序() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer p1 = enterPlayer(f, 21, 2001L, "青云甲");
        ScenePlayer p2 = enterPlayer(f, 22, 2002L, "蓬莱乙");

        // 1. 未开局时带 is_wuxing_tower 备战必须被拒
        long nowMs = f.clock.epochMillis();
        PrepareBattleResponse notStarted = prepareSync(f, PrepareBattleRequest.newBuilder()
                .setPlayerId(p1.playerId())
                .setBattleId(8001L)
                .setBattleNodeId(1)
                .setDeadlineMs(nowMs + 120_000L)
                .setPrepareDeadlineMs(nowMs + 30_000L)
                .setBattleConfigId(101)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setIsWuxingTower(true)
                .build());
        assertThat(notStarted.getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

        // 2. 517 开启第 1 轮，确认 floor=1 & dungeon=101
        long startReq = f.request(p1, SERVICE, "StartWuxingTowerRun", StartWuxingTowerRunRequest.getDefaultInstance());
        StartWuxingTowerRunResponse started = StartWuxingTowerRunResponse.parseFrom(
                lastReply(f, p1, "StartWuxingTowerRun", startReq).getSerializedMessage());
        assertThat(started.getErrorMessage().getId()).isZero();
        assertThat(started.getState().getRunActive()).isTrue();
        assertThat(started.getState().getRunId()).isEqualTo(1L);
        assertThat(started.getState().getCurrentFloor()).isEqualTo(1);
        assertThat(started.getState().getCurrentFloorDungeonId()).isEqualTo(101);
        int weekKey = started.getState().getWeekKey();

        // 错误副本号 103 备战被拒
        PrepareBattleResponse wrongDungeon = prepareSync(f, PrepareBattleRequest.newBuilder()
                .setPlayerId(p1.playerId())
                .setBattleId(8002L)
                .setBattleNodeId(1)
                .setDeadlineMs(nowMs + 120_000L)
                .setPrepareDeadlineMs(nowMs + 30_000L)
                .setBattleConfigId(103)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setIsWuxingTower(true)
                .build());
        assertThat(wrongDungeon.getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

        // 正确副本号 101 备战成功并注入 tower_context（即使角色 0 血也满血蓝隔离入场）
        p1.attributes().setHealth(0);
        PrepareBattleResponse okPrep = prepareSync(f, PrepareBattleRequest.newBuilder()
                .setPlayerId(p1.playerId())
                .setBattleId(8003L)
                .setBattleNodeId(1)
                .setDeadlineMs(nowMs + 120_000L)
                .setPrepareDeadlineMs(nowMs + 30_000L)
                .setBattleConfigId(101)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setIsWuxingTower(true)
                .build());
        assertThat(okPrep.hasErrorMessage()).isFalse();
        assertThat(okPrep.getSnapshot().hasTowerContext()).isTrue();
        assertThat(okPrep.getSnapshot().getTowerContext().getFloor()).isEqualTo(1);
        assertThat(okPrep.getSnapshot().getBaseAttributes().getHealth()).isPositive();
        f.cancel(p1.playerId(), 8003L);
        f.drain();

        // 3. 连续推进 1..10 层（每层结算 + 518 选印）直至通关第 10 层
        long battleSeq = 8100L;
        for (int floor = 1; floor <= WuxingExpansionRules.TOWER_MAX_FLOORS; floor++) {
            BattleSettlementData settle = BattleSettlementData.newBuilder()
                    .setBattleId(battleSeq++)
                    .setPlayerId(p1.playerId())
                    .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                    .setBattleConfigId(WuxingExpansionRules.towerFloorDungeonId(floor))
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setPlayerTeamIndex(0)
                    .setTotalRounds(2)
                    .setTowerSettlement(WuxingTowerSettlementEcho.newBuilder()
                            .setWeekKey(weekKey)
                            .setRunId(1L)
                            .setFloor(floor)
                            .setTotalRounds(2))
                    .build();
            f.season.creditBattlePoints(p1, settle, f.clock.epochMillis());

            if (floor < WuxingExpansionRules.TOWER_MAX_FLOORS) {
                var tower = p1.season().wuxingTower();
                assertThat(tower.getPendingOfferSigilIdsList()).hasSize(3).doesNotHaveDuplicates();
                // 未选符印前禁止进入下一层备战
                PrepareBattleResponse blocked = prepareSync(f, PrepareBattleRequest.newBuilder()
                        .setPlayerId(p1.playerId())
                        .setBattleId(battleSeq++)
                        .setBattleNodeId(1)
                        .setDeadlineMs(nowMs + 120_000L)
                        .setPrepareDeadlineMs(nowMs + 30_000L)
                        .setBattleConfigId(WuxingExpansionRules.towerFloorDungeonId(floor))
                        .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                        .setIsWuxingTower(true)
                        .build());
                assertThat(blocked.getErrorMessage().getId())
                        .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

                int chosenSigil = tower.getPendingOfferSigilIds(0);
                long pickReq = f.request(p1, SERVICE, "PickWuxingTowerSigil",
                        PickWuxingTowerSigilRequest.newBuilder()
                                .setExpectedRunId(1L)
                                .setSigilId(chosenSigil)
                                .build());
                PickWuxingTowerSigilResponse picked = PickWuxingTowerSigilResponse.parseFrom(
                        lastReply(f, p1, "PickWuxingTowerSigil", pickReq).getSerializedMessage());
                assertThat(picked.getErrorMessage().getId()).isZero();
                assertThat(picked.getState().getCurrentFloor()).isEqualTo(floor + 1);
                assertThat(picked.getState().getActiveSigilIdsList()).contains(chosenSigil);
                assertThat(picked.getState().getPendingOfferSigilIdsList()).isEmpty();
            }
        }
        // 第 10 层通关后轮次自动结束，总回合 20，首通位图 10 位全亮
        var p1Finished = p1.season().wuxingTower();
        assertThat(p1Finished.getRunActive()).isFalse();
        assertThat(p1Finished.getWeeklyClearedMask()).isEqualTo((1 << 10) - 1);
        assertThat(p1Finished.getWeeklyBestFloor()).isEqualTo(10);
        assertThat(p1Finished.getWeeklyBestRounds()).isEqualTo(20);

        // 4. p2 仅通关第 1 层后通过 519 放弃本轮；516 周榜中 p1 排第 1、p2 排第 2
        long p2Start = f.request(p2, SERVICE, "StartWuxingTowerRun", StartWuxingTowerRunRequest.getDefaultInstance());
        lastReply(f, p2, "StartWuxingTowerRun", p2Start);
        f.season.creditBattlePoints(p2, BattleSettlementData.newBuilder()
                .setBattleId(battleSeq++)
                .setPlayerId(p2.playerId())
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setBattleConfigId(101)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setPlayerTeamIndex(0)
                .setTotalRounds(1)
                .setTowerSettlement(WuxingTowerSettlementEcho.newBuilder()
                        .setWeekKey(weekKey).setRunId(1L).setFloor(1).setTotalRounds(1))
                .build(), f.clock.epochMillis());
        long abReq = f.request(p2, SERVICE, "AbandonWuxingTowerRun",
                AbandonWuxingTowerRunRequest.newBuilder().setExpectedRunId(1L).build());
        AbandonWuxingTowerRunResponse abResp = AbandonWuxingTowerRunResponse.parseFrom(
                lastReply(f, p2, "AbandonWuxingTowerRun", abReq).getSerializedMessage());
        assertThat(abResp.getErrorMessage().getId()).isZero();
        assertThat(abResp.getState().getRunActive()).isFalse();
        assertThat(abResp.getState().getWeeklyBestFloor()).isEqualTo(1);

        long getReq = f.request(p1, SERVICE, "GetWuxingTowerState", GetWuxingTowerStateRequest.getDefaultInstance());
        GetWuxingTowerStateResponse stateResp = GetWuxingTowerStateResponse.parseFrom(
                lastReply(f, p1, "GetWuxingTowerState", getReq).getSerializedMessage());
        assertThat(stateResp.getState().getWeeklyLeaderboardList()).hasSize(2);
        assertThat(stateResp.getState().getWeeklyLeaderboard(0).getPlayerId()).isEqualTo(p1.playerId());
        assertThat(stateResp.getState().getWeeklyLeaderboard(0).getRank()).isEqualTo(1);
        assertThat(stateResp.getState().getWeeklyLeaderboard(1).getPlayerId()).isEqualTo(p2.playerId());
        assertThat(stateResp.getState().getWeeklyLeaderboard(1).getRank()).isEqualTo(2);
    }

    @Test
    void 战报配装收藏夹520和521_上限12条_文本48字节截断_非法符印过滤与删除校验() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = enterPlayer(f, 31, 3001L, "配装收藏家");

        // 保存 12 条（第 1 条带超长标题、15 技能与含非法符印的列表）
        String longTitle = "青云剑诀无极阵法流派超长自定义标题测试文本一二三四五六七八九十";
        SavedBattleBuildBookmarkView.Builder firstBm = SavedBattleBuildBookmarkView.newBuilder()
                .setTitle(longTitle)
                .setFormationId(5)
                .setSeasonSealId(101)
                .addWuxingSigilIds(101)
                .addWuxingSigilIds(999)
                .addWuxingSigilIds(501);
        for (int i = 1; i <= 15; i++) {
            firstBm.addSkillTableIds(100 + i);
        }
        long save1 = f.request(player, SERVICE, "SaveBattleBuildBookmark",
                SaveBattleBuildBookmarkRequest.newBuilder().setBookmark(firstBm).build());
        SaveBattleBuildBookmarkResponse r1 = SaveBattleBuildBookmarkResponse.parseFrom(
                lastReply(f, player, "SaveBattleBuildBookmark", save1).getSerializedMessage());
        assertThat(r1.getErrorMessage().getId()).isZero();
        assertThat(r1.getSaved().getBookmarkId()).isEqualTo(1L);
        assertThat(r1.getSaved().getTitle().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(48);
        assertThat(r1.getSaved().getSkillTableIdsList()).hasSize(12);
        assertThat(r1.getSaved().getWuxingSigilIdsList()).containsExactly(101, 501);

        // 写满 12 条后第 13 条拒绝
        for (int i = 2; i <= 12; i++) {
            long rid = f.request(player, SERVICE, "SaveBattleBuildBookmark",
                    SaveBattleBuildBookmarkRequest.newBuilder()
                            .setBookmark(SavedBattleBuildBookmarkView.newBuilder().setTitle("方案" + i).setFormationId(1))
                            .build());
            SaveBattleBuildBookmarkResponse resp = SaveBattleBuildBookmarkResponse.parseFrom(
                    lastReply(f, player, "SaveBattleBuildBookmark", rid).getSerializedMessage());
            assertThat(resp.getErrorMessage().getId()).isZero();
        }
        long overReq = f.request(player, SERVICE, "SaveBattleBuildBookmark",
                SaveBattleBuildBookmarkRequest.newBuilder()
                        .setBookmark(SavedBattleBuildBookmarkView.newBuilder().setTitle("第13条").setFormationId(1))
                        .build());
        SaveBattleBuildBookmarkResponse overResp = SaveBattleBuildBookmarkResponse.parseFrom(
                lastReply(f, player, "SaveBattleBuildBookmark", overReq).getSerializedMessage());
        assertThat(overResp.getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);
        assertThat(overResp.getAllBookmarksList()).hasSize(12);

        // GetSeasonState 无论是否在赛季窗口内都带出 saved_builds
        long stateReq = f.request(player, SERVICE, "GetSeasonState", GetSeasonStateRequest.getDefaultInstance());
        GetSeasonStateResponse stateResp = GetSeasonStateResponse.parseFrom(
                lastReply(f, player, "GetSeasonState", stateReq).getSerializedMessage());
        assertThat(stateResp.getState().getSavedBuildsList()).hasSize(12);

        // 删除第 1 条成功，再删不存在的 ID 拒绝
        long delOk = f.request(player, SERVICE, "DeleteBattleBuildBookmark",
                DeleteBattleBuildBookmarkRequest.newBuilder().setBookmarkId(1L).build());
        DeleteBattleBuildBookmarkResponse delOkResp = DeleteBattleBuildBookmarkResponse.parseFrom(
                lastReply(f, player, "DeleteBattleBuildBookmark", delOk).getSerializedMessage());
        assertThat(delOkResp.getErrorMessage().getId()).isZero();
        assertThat(delOkResp.getAllBookmarksList()).hasSize(11);

        long delMiss = f.request(player, SERVICE, "DeleteBattleBuildBookmark",
                DeleteBattleBuildBookmarkRequest.newBuilder().setBookmarkId(9999L).build());
        DeleteBattleBuildBookmarkResponse delMissResp = DeleteBattleBuildBookmarkResponse.parseFrom(
                lastReply(f, player, "DeleteBattleBuildBookmark", delMiss).getSerializedMessage());
        assertThat(delMissResp.getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);
    }
}
