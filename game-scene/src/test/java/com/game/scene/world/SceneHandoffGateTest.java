package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.common.RunMode;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.ListSkillsRequest;
import com.game.proto.MoveSyncC2S;
import com.game.proto.ReleaseSkillRequest;
import com.game.scene.battle.BattleFixture;
import com.game.scene.testing.Contracts;
import com.game.table.CommonErrorTip;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 覆盖场景 lane 白名单（按服务/按方法）、旧场景消息闸（S13）及交接冻结 retry_later 信号。 */
class SceneHandoffGateTest {

    @Test
    void SceneServiceIsListedPerMethod_白名单精确覆盖七个整服务与技能场景白名单方法() {
        // 7 个整服务
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneBagClientPlayer", "SortBag")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneAttributeClientPlayer", "GetAttributePanel")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("ScenePetClientPlayer", "GetPetList")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneMissionClientPlayer", "GetMissionList")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneCurrencyClientPlayer", "GetCurrencyList")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneActivityClientPlayer", "GetActivityList")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneBattleResultClientPlayer", "GetRecentBattleResults")).isTrue();

        // 技能按方法：ListSkills / UpgradeSkill 进，ReleaseSkill 不进
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneSkillClientPlayer", "ListSkills")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneSkillClientPlayer", "UpgradeSkill")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneSkillClientPlayer", "ReleaseSkill")).isFalse();

        // 场景按方法：GetSyncDigest / SceneInfoC2S 进，EnterScene / TravelToZone 不进
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneSceneClientPlayer", "GetSyncDigest")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneSceneClientPlayer", "SceneInfoC2S")).isTrue();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneSceneClientPlayer", "EnterScene")).isFalse();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneSceneClientPlayer", "TravelToZone")).isFalse();

        // 移动、神秘商人不进
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneMovementClientPlayer", "MoveSync")).isFalse();
        assertThat(SceneHandoffWhitelist.isWhitelisted("SceneMysteryMerchantClientPlayer", "BuyMysteryMerchantItem")).isFalse();
    }

    @Test
    void MessageReceivedUnderAPreviousRouteIsStaleOnlyIfSceneBound() {
        BattleFixture f = new BattleFixture();
        ScenePlayer p = f.enter(11, 1001L);
        f.sink.clear();

        int enterSceneId = Contracts.REGISTRY.requireId("SceneSceneClientPlayer", "EnterScene");
        int releaseSkillId = Contracts.REGISTRY.requireId("SceneSkillClientPlayer", "ReleaseSkill");
        int moveSyncId = Contracts.REGISTRY.requireId("SceneMovementClientPlayer", "MoveSync");
        int listSkillsId = Contracts.REGISTRY.requireId("SceneSkillClientPlayer", "ListSkills");

        // 1. 旧路由上的场景绑定请求（EnterScene / ReleaseSkill）被旧场景闸拒绝并回信封 1003
        f.handler.onClientForward(1L, ClientForward.newBuilder()
                .setSessionId(11).setPlayerId(1001L).setRequestId(101L).setMessageId(enterSceneId)
                .setBody(EnterSceneC2SRequest.newBuilder()
                        .setSceneInfo(com.game.proto.SceneInfoComp.newBuilder().setSceneId(2)).build().toByteString())
                .setReceivedOnPreviousRoute(true).build());
        assertThat(f.sink.to(1L, 11)).singleElement().satisfies(m -> {
            assertThat(m.getId()).isEqualTo(101L);
            assertThat(m.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        });

        f.sink.clear();
        f.handler.onClientForward(1L, ClientForward.newBuilder()
                .setSessionId(11).setPlayerId(1001L).setRequestId(102L).setMessageId(releaseSkillId)
                .setBody(ReleaseSkillRequest.getDefaultInstance().toByteString())
                .setReceivedOnPreviousRoute(true).build());
        assertThat(f.sink.to(1L, 11)).singleElement().satisfies(m -> {
            assertThat(m.getId()).isEqualTo(102L);
            assertThat(m.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        });

        // 2. 旧路由上的单向消息（MoveSync）静默丢弃不回包
        f.sink.clear();
        f.handler.onClientForward(1L, ClientForward.newBuilder()
                .setSessionId(11).setPlayerId(1001L).setRequestId(0L).setMessageId(moveSyncId)
                .setBody(MoveSyncC2S.getDefaultInstance().toByteString())
                .setReceivedOnPreviousRoute(true).build());
        assertThat(f.sink.to(1L, 11)).isEmpty();

        // 3. 旧路由上的白名单请求（ListSkills）照常执行成功
        f.sink.clear();
        f.handler.onClientForward(1L, ClientForward.newBuilder()
                .setSessionId(11).setPlayerId(1001L).setRequestId(103L).setMessageId(listSkillsId)
                .setBody(ListSkillsRequest.getDefaultInstance().toByteString())
                .setReceivedOnPreviousRoute(true).build());
        assertThat(f.sink.to(1L, 11)).singleElement().satisfies(m -> {
            assertThat(m.getId()).isEqualTo(103L);
            assertThat(m.getErrorMessage().getId()).isZero();
        });
        assertThat(p).isNotNull();
    }

    @Test
    void 启用交接重投后_玩家未载入时白名单请求回retryLater_非白名单回1003() {
        List<Long> retried = new ArrayList<>();
        ClientSink captureSink = new ClientSink() {
            @Override public void send(long linkId, List<Integer> sessionIds, com.game.proto.MessageContent content) {}
            @Override public void enterResult(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {}
            @Override public void playerKicked(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {}
            @Override public boolean playerTransfer(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch,
                                                    int targetNodeId, long targetSceneId, Runnable onWriteFailed) { return true; }
            @Override public boolean playerRedirect(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch,
                                                    com.game.api.proto.ZoneRedirect redirect, Runnable onWriteFailed) { return true; }
            @Override public void retryLater(long linkId, int sessionId, long playerId, long requestId, int messageId) {
                retried.add(requestId);
            }
        };
        SceneWorld world = new SceneWorld(new com.game.scene.testing.FakeSceneTables(), Contracts.IDS, captureSink,
                new com.game.scene.testing.FakePlayerRepository(), new java.util.concurrent.atomic.AtomicLong(1)::incrementAndGet,
                new com.game.scene.testing.ManualClock(), com.game.scene.metrics.SceneMetrics.noop());
        ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.PROD, List.of())
                .withHandoffRetryLater(true);

        int getCurrencyId = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        int enterSceneId = Contracts.REGISTRY.requireId("SceneSceneClientPlayer", "EnterScene");

        handler.onClientForward(1L, ClientForward.newBuilder()
                .setSessionId(7).setPlayerId(999L).setRequestId(501L).setMessageId(getCurrencyId)
                .setBody(GetCurrencyListRequest.getDefaultInstance().toByteString()).build());
        handler.onClientForward(1L, ClientForward.newBuilder()
                .setSessionId(7).setPlayerId(999L).setRequestId(502L).setMessageId(enterSceneId)
                .setBody(ByteString.EMPTY).build());

        assertThat(retried).containsExactly(501L);
    }
}
