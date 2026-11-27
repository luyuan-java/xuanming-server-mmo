package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SessionContext;
import com.game.common.token.GateTokens;
import com.game.discovery.drain.GateDrainMarks;
import com.game.proto.GateTokenPayload;
import com.game.proto.login.GateMigrationResponse;
import com.game.proto.login.GateMigrationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 覆盖 gate 排空签票核对、不回落新一代优先选目标、以及进场会话 CAS 冲突有界 3 次重做。 */
class GateMigrationAndCommitRetryTest {

    private static GateNodeInfo gate(int nodeId, boolean draining, int players, int max, String build) {
        return GateNodeInfo.newBuilder()
                .setZoneId(1)
                .setNodeId(nodeId)
                .setInstanceId("inst-" + nodeId)
                .setClientHost("10.0.0." + nodeId)
                .setClientPort(7000 + nodeId)
                .setDraining(draining)
                .setPlayerCount(players)
                .setMaxConnections(max)
                .setDeploymentBuildId(build)
                .build();
    }

    private static SessionContext ctx(int nodeId, String instanceId, String account, long playerId) {
        return SessionContext.newBuilder()
                .setGateNodeId(nodeId)
                .setGateInstanceId(instanceId)
                .setAccount(account)
                .setPlayerId(playerId)
                .setZoneId(1)
                .build();
    }

    @Test
    void 签票核对排空标记_不回落且优先新一代_目标zone恒为0_游戏中绑playerId选角不绑() throws Exception {
        GateTokens tokens = GateTokens.ofUtf8("0123456789abcdef0123456789abcdef");
        InstantSource clock = InstantSource.fixed(Instant.ofEpochSecond(1_800_000_000L));
        List<GateNodeInfo> dir = List.of(
                gate(1, true, 100, 1000, "v1@r1"),
                gate(2, false, 100, 1000, "v1@r1"),
                gate(3, false, 120, 1000, "v1@r2"));
        GateDrainMarks.Mark mark1 = new GateDrainMarks.Mark(1_799_999_900L, "inst-1", "1799999900:inst-1");

        // 1. 开关关闭 -> DISABLED
        GateMigrationHandler disabled = new GateMigrationHandler(
                1, false, (z, ids) -> Map.of(1, mark1), z -> dir, tokens, null, clock, null, acc -> true);
        assertThat(disabled.evaluate(ctx(1, "inst-1", "acc", 500L)).getResult())
                .isEqualTo(GateMigrationResult.GATE_MIGRATION_RESULT_DISABLED);

        // 2. 调用方实例不符 -> NOT_DRAINING
        GateMigrationHandler active = new GateMigrationHandler(
                1, true, (z, ids) -> Map.of(1, mark1), z -> dir, tokens, null, clock, null, acc -> !acc.isBlank());
        assertThat(active.evaluate(ctx(1, "wrong-inst", "acc", 500L)).getResult())
                .isEqualTo(GateMigrationResult.GATE_MIGRATION_RESULT_NOT_DRAINING);

        // 3. 游戏中迁移：优先新一代 node 3，票据绑定 player_id=500 且 target_zone_id=0，签名可被目标 gate 验过
        GateMigrationResponse inGame = active.evaluate(ctx(1, "inst-1", "acc", 500L));
        assertThat(inGame.getResult()).isEqualTo(GateMigrationResult.GATE_MIGRATION_RESULT_OK);
        assertThat(inGame.getTargetGateIp()).isEqualTo("10.0.0.3");
        assertThat(inGame.getTargetGatePort()).isEqualTo(7003);
        GateTokenPayload inGamePayload = GateTokenPayload.parseFrom(inGame.getTokenPayload());
        assertThat(inGamePayload.getGateNodeId()).isEqualTo(3);
        assertThat(inGamePayload.getPlayerId()).isEqualTo(500L);
        assertThat(inGamePayload.getTargetZoneId()).isZero();
        assertThat(tokens.verify(inGame.getTokenPayload(), inGame.getTokenSignature(), 3, 1, 1_800_000_010L).failure()).isNull();

        // 4. 选角界面迁移：无登录账号拒，有效登录账号签 player_id=0 票据
        assertThat(active.evaluate(ctx(1, "inst-1", "", 0L)).getResult())
                .isEqualTo(GateMigrationResult.GATE_MIGRATION_RESULT_UNSPECIFIED);
        GateMigrationResponse lobby = active.evaluate(ctx(1, "inst-1", "acc", 0L));
        assertThat(lobby.getResult()).isEqualTo(GateMigrationResult.GATE_MIGRATION_RESULT_OK);
        assertThat(GateTokenPayload.parseFrom(lobby.getTokenPayload()).getPlayerId()).isZero();
    }

    @Test
    void 进场会话提交CAS冲突有界3次重做_其他失败与短重连冲突不重做() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SessionCommitRetrier retrier = new SessionCommitRetrier(meters);

        var ok = retrier.execute(attempt -> attempt < 3
                ? new SessionCommitRetrier.AttemptOutcome<>(SessionCommitRetrier.CommitStatus.STALE_VERSION_CONFLICT, null)
                : new SessionCommitRetrier.AttemptOutcome<>(SessionCommitRetrier.CommitStatus.COMMITTED, "epoch-3"));
        assertThat(ok.status()).isEqualTo(SessionCommitRetrier.CommitStatus.COMMITTED);
        assertThat(ok.value()).isEqualTo("epoch-3");
        assertThat(retrier.retryCount()).isEqualTo(2.0);

        var sr = retrier.execute(attempt ->
                new SessionCommitRetrier.AttemptOutcome<>(SessionCommitRetrier.CommitStatus.SHORT_RECONNECT_CONFLICT, null));
        assertThat(sr.status()).isEqualTo(SessionCommitRetrier.CommitStatus.SHORT_RECONNECT_CONFLICT);
        assertThat(retrier.retryCount()).isEqualTo(2.0);
    }
}
