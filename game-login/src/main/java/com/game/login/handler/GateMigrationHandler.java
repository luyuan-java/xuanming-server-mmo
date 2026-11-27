package com.game.login.handler;

import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SessionContext;
import com.game.common.token.GateTokens;
import com.game.discovery.drain.GateDrainMarks;
import com.game.discovery.gate.GateInflight;
import com.game.discovery.gate.GatePicker;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.proto.GateTokenPayload;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.GateMigrationRequest;
import com.game.proto.login.GateMigrationResponse;
import com.game.proto.login.GateMigrationResult;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端内部 RPC {@code ClientPlayerLogin.RequestGateMigration} 处理器（对齐 {@code docs/design/gate-drain-session-migration.md}）：
 * <ol>
 *   <li>开关默认开启，关闭时回 {@code GATE_MIGRATION_RESULT_DISABLED}；</li>
 *   <li>直读 Redis {@link GateDrainMarks} 校验调用方 (zone, node_id, instance_id) 正在排空，读不到或不匹配拒签；</li>
 *   <li>调 {@link GatePicker#pickForMigration} 剔除自身/排空/满载且绝不回落、优先新一代构建；</li>
 *   <li>游戏中连接（{@code session.getPlayerId() != 0}）票据绑定本人，选角连接核对登录态后 {@code player_id = 0}；
 *       {@code target_zone_id = 0}（到达走本区短重连、回原场景）。</li>
 * </ol>
 */
public final class GateMigrationHandler implements ClientMessageHandler<GateMigrationRequest> {

    private static final Logger log = LoggerFactory.getLogger(GateMigrationHandler.class);

    public static final boolean DEFAULT_ENABLED = true;
    public static final Duration DEFAULT_TICKET_TTL = Duration.ofSeconds(60);

    @FunctionalInterface
    public interface DrainMarkReader {
        Map<Integer, GateDrainMarks.Mark> draining(int zoneId, List<Integer> nodeIds);
    }

    private final int zoneId;
    private final boolean enabled;
    private final DrainMarkReader drainMarks;
    private final Function<Integer, List<GateNodeInfo>> gateDirectory;
    private final GateTokens tokens;
    private final GateInflight inflight;
    private final InstantSource clock;
    private final Duration ticketTtl;
    private final Predicate<String> lobbyAccountChecker;

    public GateMigrationHandler(int zoneId, boolean enabled, DrainMarkReader drainMarks,
                                Function<Integer, List<GateNodeInfo>> gateDirectory,
                                GateTokens tokens, GateInflight inflight,
                                InstantSource clock, Duration ticketTtl,
                                Predicate<String> lobbyAccountChecker) {
        this.zoneId = zoneId;
        this.enabled = enabled;
        this.drainMarks = Objects.requireNonNull(drainMarks, "drainMarks");
        this.gateDirectory = Objects.requireNonNull(gateDirectory, "gateDirectory");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.inflight = inflight;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticketTtl = ticketTtl == null ? DEFAULT_TICKET_TTL : ticketTtl;
        this.lobbyAccountChecker = lobbyAccountChecker == null ? acc -> acc != null && !acc.isBlank() : lobbyAccountChecker;
    }

    @Override
    public String methodName() {
        return "RequestGateMigration";
    }

    @Override
    public Class<GateMigrationRequest> requestType() {
        return GateMigrationRequest.class;
    }

    @Override
    public Class<GateMigrationResponse> responseType() {
        return GateMigrationResponse.class;
    }

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, GateMigrationRequest request) {
        return CompletableFuture.completedFuture(HandlerReply.of(evaluate(session)));
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.of(GateMigrationResponse.newBuilder()
                .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_UNSPECIFIED)
                .build());
    }

    public GateMigrationResponse evaluate(SessionContext session) {
        if (!enabled) {
            return GateMigrationResponse.newBuilder()
                    .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_DISABLED)
                    .build();
        }
        int callerNodeId = session.getGateNodeId();
        String callerInstanceId = session.getGateInstanceId();
        if (callerNodeId == 0 || callerInstanceId == null || callerInstanceId.isBlank()) {
            return GateMigrationResponse.newBuilder()
                    .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_NOT_DRAINING)
                    .build();
        }
        Map<Integer, GateDrainMarks.Mark> marks;
        try {
            marks = drainMarks.draining(zoneId, List.of(callerNodeId));
        } catch (RuntimeException e) {
            log.warn("gate_migration_drain_mark_read_failed zone={} node={} err={}", zoneId, callerNodeId, e.toString());
            return GateMigrationResponse.newBuilder()
                    .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_NOT_DRAINING)
                    .build();
        }
        GateDrainMarks.Mark mark = marks == null ? null : marks.get(callerNodeId);
        if (mark == null || !callerInstanceId.equals(mark.instanceId())) {
            return GateMigrationResponse.newBuilder()
                    .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_NOT_DRAINING)
                    .build();
        }
        long playerId = session.getPlayerId();
        if (playerId == 0L && !lobbyAccountChecker.test(session.getAccount())) {
            return GateMigrationResponse.newBuilder()
                    .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_UNSPECIFIED)
                    .build();
        }
        List<GateNodeInfo> gates;
        try {
            gates = gateDirectory.apply(zoneId);
        } catch (RuntimeException e) {
            log.warn("gate_migration_directory_read_failed zone={} err={}", zoneId, e.toString());
            return GateMigrationResponse.newBuilder()
                    .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_NO_TARGET)
                    .build();
        }
        Optional<GateNodeInfo> targetOpt = GatePicker.pickForMigration(zoneId, callerNodeId, gates, inflight);
        if (targetOpt.isEmpty()) {
            return GateMigrationResponse.newBuilder()
                    .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_NO_TARGET)
                    .build();
        }
        GateNodeInfo target = targetOpt.get();
        long nowSec = clock.instant().getEpochSecond();
        long expSec = nowSec + ticketTtl.toSeconds();
        // target_zone_id 恒为 0：客户端重连后走本区短重连、回原场景
        ByteString payloadBytes = GateTokenPayload.newBuilder()
                .setGateNodeId(target.getNodeId())
                .setZoneId(zoneId)
                .setExpireTimestamp(expSec)
                .setPlayerId(playerId)
                .setTargetZoneId(0)
                .build()
                .toByteString();
        ByteString sigBytes = tokens.sign(payloadBytes);
        return GateMigrationResponse.newBuilder()
                .setResult(GateMigrationResult.GATE_MIGRATION_RESULT_OK)
                .setTargetGateIp(target.getClientHost())
                .setTargetGatePort(target.getClientPort())
                .setTokenPayload(payloadBytes)
                .setTokenSignature(sigBytes)
                .setTokenDeadline(expSec)
                .setTargetGateWsUrl(target.getClientWsUrl())
                .build();
    }
}
