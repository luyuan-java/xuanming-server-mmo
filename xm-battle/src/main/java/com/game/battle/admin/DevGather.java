package com.game.battle.admin;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.api.proto.DevGatherMember;
import com.game.api.proto.DevGatherMode;
import com.game.api.proto.DevGatherPrepareResult;
import com.game.api.proto.DevGatherRequest;
import com.game.api.proto.DevGatherResponse;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * dev gather 的编排（scene-battle-spec §7.18、D27、Q12；6.4 的 match gather 落地之前验收 6.3 的唯一入口）：按 player_id 无符号升序逐人
 * 「定位（位置记录只认 {@code o} + scene 目录）→ {@code SceneBattleService.prepareBattle}」，任一人失败就按升序给已备战（以及结局不明）的人补发
 * {@code cancelBattlePrepare}；{@code PREPARE_ONLY} 到此为止、回快照；{@code CREATE} 用 scene 出的快照（{@code team_index} 按请求改写）组
 * {@code CreateBattleRequest}，经进程内控制面以 {@code origin = DEV_GATHER} 建房，不可分配 / 业务错误 / 结局不明都全部取消（结局不明先发一次幂等的
 * destroy）。这样建出的房间照常确认、照常结算（快照来自 scene，不是调用方伪造）。
 *
 * <p><b>线程</b>：只在管理 Tomcat 线程上用，阻塞等待；主路径（定位、备战、建房）整体上限 {@link #BUDGET}，补偿取消另有
 * {@link #COMPENSATION_BUDGET}（不让主路径超时把冻结留到备战期限）。不碰房间（建房走控制面同一条准入与投递路径）、不碰逻辑线程。线程安全（无可变状态）。
 */
public final class DevGather {

    private static final Logger log = LoggerFactory.getLogger(DevGather.class);

    /** 主路径整体上限（§7.18「整体等待上限 10 s」）。 */
    static final Duration BUDGET = Duration.ofSeconds(10);
    /** 单次 scene 调用的上限：必须大于 scene 侧最坏 4.2 s（一条 Redis 脚本的 Redisson 最坏耗时，§7.3、§10.4）。 */
    static final Duration SCENE_CALL_TIMEOUT = Duration.ofSeconds(5);
    /** 等控制面建房结果的上限（同 dev/create）。 */
    static final Duration CREATE_TIMEOUT = Duration.ofSeconds(5);
    /** 失败后补偿（destroy + 逐人取消）的整体上限，独立于主路径。 */
    static final Duration COMPENSATION_BUDGET = Duration.ofSeconds(6);
    /** 一次 gather 的成员上限（10 人 = 5V5，同基线最大队伍）。 */
    static final int MAX_MEMBERS = 10;

    /** scene 侧的三种操作（生产：{@code SceneAssetLocator} + {@code NodeRpcClients<SceneBattleService>}）。 */
    public interface Scenes {

        /** 定位玩家此刻的持有者节点（future 不异常完成；异常完成也按故障处理）。 */
        CompletableFuture<Resolution> locate(long playerId);

        /** 备战；future 异常完成 = 传输失败（结局未知）。 */
        CompletableFuture<SceneBattleReply> prepare(SceneAssetEndpoint endpoint, SceneBattleCall call, Duration timeout);

        /** 取消备战；future 异常完成 = 传输失败。 */
        CompletableFuture<SceneBattleReply> cancel(SceneAssetEndpoint endpoint, SceneBattleCall call, Duration timeout);
    }

    /** 控制面（进程内 {@code BattleNodeServiceImpl}，以 DEV_GATHER 来源建房）。 */
    public interface Plane {

        /** 本 battle 节点号（写进 {@code PrepareBattleRequest.battle_node_id}）。 */
        int battleNodeId();

        CompletableFuture<CreateBattleResult> create(CreateBattleRequest request);

        CompletableFuture<?> destroy(DestroyBattleRequest request);
    }

    /**
     * 一次 gather 的结论。
     *
     * @param ok              全员备战成功（CREATE 时还要建房受理且无业务错误）→ HTTP 200；否则 422
     * @param response        应答体（{@code failure} 非空 ⇔ {@code !ok}）
     * @param createAttempted 全员备战成功、走到了建房（失败时据此区分 prepare_failed / create_failed）
     */
    public record Outcome(boolean ok, DevGatherResponse response, boolean createAttempted) {
    }

    /** 取消的结论：{@code status} 为 HTTP 状态（204 / 422 / 503），{@code message} 给人看。 */
    public record CancelOutcome(int status, String message) {
    }

    private final Scenes scenes;
    private final LongSupplier clockMillis;

    public DevGather(Scenes scenes, LongSupplier clockMillis) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** 请求的形状校验（接口回 400）；合法为 null。 */
    public static String invalidReason(DevGatherRequest request) {
        if (request.getMode() != DevGatherMode.DEV_GATHER_PREPARE_ONLY && request.getMode() != DevGatherMode.DEV_GATHER_CREATE) {
            return "mode 只能是 PREPARE_ONLY / CREATE";
        }
        if (request.getBattleId() == 0) {
            return "battle_id 为 0";
        }
        if (request.getDeadlineMs() == 0) {
            return "deadline_ms 为 0";
        }
        if (request.getMembersCount() == 0) {
            return "members 为空";
        }
        if (request.getMembersCount() > MAX_MEMBERS) {
            return "members 超过 " + MAX_MEMBERS + " 人";
        }
        Set<Long> seen = new HashSet<>();
        for (DevGatherMember member : request.getMembersList()) {
            if (member.getPlayerId() == 0) {
                return "成员 player_id 为 0";
            }
            if (!seen.add(member.getPlayerId())) {
                return "成员 player_id 重复: " + Long.toUnsignedString(member.getPlayerId());
            }
            if (member.getTeamIndex() > 1) {
                return "成员 team_index 只能是 0 / 1: " + Integer.toUnsignedString(member.getTeamIndex());
            }
        }
        return null;
    }

    /** 按 player_id 无符号升序的成员表（player_id → team_index）。 */
    static TreeMap<Long, Integer> ascending(DevGatherRequest request) {
        TreeMap<Long, Integer> members = new TreeMap<>(Long::compareUnsigned);
        for (DevGatherMember member : request.getMembersList()) {
            members.put(member.getPlayerId(), member.getTeamIndex());
        }
        return members;
    }

    /** 跑一次 gather（请求已过 {@link #invalidReason}）。阻塞，至多约 {@link #BUDGET} + {@link #COMPENSATION_BUDGET}。 */
    public Outcome gather(DevGatherRequest request, Plane plane) {
        long deadline = System.nanoTime() + BUDGET.toNanos();
        DevGatherResponse.Builder response = DevGatherResponse.newBuilder();
        // 需要补偿取消的人（已备战成功，或结局不明）→ 当时发往的节点；升序
        TreeMap<Long, SceneAssetEndpoint> toCancel = new TreeMap<>(Long::compareUnsigned);
        Map<Long, PrepareBattleResponse> prepared = new TreeMap<>(Long::compareUnsigned);
        TreeMap<Long, Integer> members = ascending(request);
        String failure = null;
        boolean createAttempted = false;

        for (Map.Entry<Long, Integer> member : members.entrySet()) {
            long playerId = member.getKey();
            Step step = prepareOne(request, plane.battleNodeId(), playerId, deadline);
            response.addPrepareResults(step.result());
            if (step.maybeFrozen()) {
                toCancel.put(playerId, step.endpoint());
            }
            if (step.failure() != null) {
                failure = step.failure();
                break;
            }
            prepared.put(playerId, step.response());
        }
        if (failure == null) {
            failure = fingerprintMismatch(prepared);
        }
        if (failure == null && request.getMode() == DevGatherMode.DEV_GATHER_CREATE) {
            createAttempted = true;
            CreateBattleRequest create = createRequest(request, members, prepared, clockMillis.getAsLong());
            Created created = create(plane, create, deadline);
            if (created.result() != null) {
                response.setCreateResult(created.result());
            }
            failure = created.failure();
            if (failure != null && created.unknown()) {
                destroyQuietly(plane, request.getBattleId());
            }
        }
        if (failure != null) {
            compensate(request.getBattleId(), toCancel, response);
            response.setFailure(failure);
            log.warn("dev gather 失败，已补发取消 battle_id={} cancelled={} 原因={}", Long.toUnsignedString(request.getBattleId()),
                    response.getCancelledPlayerIdsList().stream().map(Long::toUnsignedString).toList(), failure);
            return new Outcome(false, response.build(), createAttempted);
        }
        log.info("dev gather 完成 battle_id={} mode={} members={}", Long.toUnsignedString(request.getBattleId()), request.getMode(),
                members.keySet().stream().map(Long::toUnsignedString).toList());
        return new Outcome(true, response.build(), createAttempted);
    }

    /** dev/cancel-prepare：定位玩家此刻的节点后取消（幂等）。 */
    public CancelOutcome cancelPrepare(CancelBattlePrepareRequest request) {
        long playerId = request.getPlayerId();
        if (playerId == 0 || request.getBattleId() == 0) {
            return new CancelOutcome(400, "player_id / battle_id 为 0");
        }
        long deadline = System.nanoTime() + SCENE_CALL_TIMEOUT.toNanos() + Duration.ofSeconds(2).toNanos();
        Resolution where = locate(playerId, deadline);
        switch (where) {
            case NoHolder none -> {
                return new CancelOutcome(422, "player_id=" + Long.toUnsignedString(playerId) + " 此刻没有持有者节点（" + none.result().label()
                        + "），无从取消");
            }
            case Failure failure -> {
                return new CancelOutcome(503, "定位失败：" + failure.reason());
            }
            case Found found -> {
                SceneBattleCall call = SceneBattleCall.newBuilder().setTargetInstanceId(found.endpoint().instanceId())
                        .setPlayerId(playerId).setBody(request.toByteString()).build();
                Reply reply = await(scenes.cancel(found.endpoint(), call, remaining(deadline, SCENE_CALL_TIMEOUT)), deadline);
                if (reply.error() != null) {
                    return new CancelOutcome(503, "取消调用失败（结局未知）：" + reply.error());
                }
                if (reply.reply().getStatus() != SceneBattleStatus.SCENE_BATTLE_HANDLED) {
                    return new CancelOutcome(503, "scene 应答 " + reply.reply().getStatus());
                }
                return new CancelOutcome(204, "");
            }
        }
    }

    // ---------------------------------------------------------------- 备战一人

    /**
     * 一人的备战结局。
     *
     * @param result      进应答的结局
     * @param response    备战成功时的契约应答（快照 + 指纹）
     * @param endpoint    发往的节点（定位到时）
     * @param maybeFrozen 需要补偿取消（已冻结，或结局不明）
     * @param failure     非 null = 这一人失败（gather 到此为止）
     */
    private record Step(DevGatherPrepareResult result, PrepareBattleResponse response, SceneAssetEndpoint endpoint, boolean maybeFrozen,
                        String failure) {
    }

    private Step prepareOne(DevGatherRequest request, int battleNodeId, long playerId, long deadline) {
        String who = "player_id=" + Long.toUnsignedString(playerId);
        DevGatherPrepareResult.Builder result = DevGatherPrepareResult.newBuilder().setPlayerId(playerId);
        Resolution where = locate(playerId, deadline);
        SceneAssetEndpoint endpoint;
        switch (where) {
            case NoHolder none -> {
                String error = who + " 此刻没有持有者节点（" + none.result().label() + "）：先登录进场再 gather";
                return new Step(result.setError(error).build(), null, null, false, error);
            }
            case Failure failure -> {
                String error = who + " 定位失败：" + failure.reason();
                return new Step(result.setError(error).build(), null, null, false, error);
            }
            case Found found -> endpoint = found.endpoint();
        }
        result.setSceneNodeId(endpoint.nodeId()).setSceneInstanceId(endpoint.instanceId());
        PrepareBattleRequest body = PrepareBattleRequest.newBuilder()
                .setPlayerId(playerId)
                .setBattleId(request.getBattleId())
                .setBattleNodeId(battleNodeId)
                .setDeadlineMs(request.getDeadlineMs())
                .setPrepareDeadlineMs(request.getPrepareDeadlineMs())
                .build();
        SceneBattleCall call = SceneBattleCall.newBuilder().setTargetInstanceId(endpoint.instanceId()).setPlayerId(playerId)
                .setBody(body.toByteString()).build();
        Duration timeout = remaining(deadline, SCENE_CALL_TIMEOUT);
        if (timeout.isZero()) {
            String error = who + " 备战之前已用完 gather 的整体预算 " + BUDGET.toSeconds() + " s";
            return new Step(result.setError(error).build(), null, endpoint, false, error);
        }
        Reply reply = await(scenes.prepare(endpoint, call, timeout), deadline);
        if (reply.error() != null) {
            String error = who + " 备战调用失败（结局未知，按可能已冻结补发取消）：" + reply.error();
            return new Step(result.setError(error).build(), null, endpoint, true, error);
        }
        SceneBattleReply got = reply.reply();
        result.setStatus(got.getStatus()).setResponse(got.getBody());
        switch (got.getStatus()) {
            case SCENE_BATTLE_HANDLED -> {
                // 往下解析业务结论
            }
            case SCENE_BATTLE_NOT_HERE, SCENE_BATTLE_OVERLOADED -> {
                // 这两种保证没进逻辑线程、零副作用
                String error = who + " scene 应答 " + got.getStatus() + "（零副作用；NOT_HERE = 实例不符或玩家已不在该节点）";
                return new Step(result.setError(error).build(), null, endpoint, false, error);
            }
            default -> {
                String error = who + " scene 应答 " + got.getStatus() + "（按结局不明处理，补发取消）";
                return new Step(result.setError(error).build(), null, endpoint, true, error);
            }
        }
        PrepareBattleResponse parsed;
        try {
            parsed = PrepareBattleResponse.parseFrom(got.getBody());
        } catch (InvalidProtocolBufferException e) {
            String error = who + " 备战应答体不是 PrepareBattleResponse（按结局不明处理，补发取消）";
            return new Step(result.setError(error).build(), null, endpoint, true, error);
        }
        if (parsed.getErrorMessage().getId() != 0) {
            // 业务拒绝：零副作用（scene 的备战拒绝不留冻结、不写锁）
            String error = who + " 备战被拒 tip=" + parsed.getErrorMessage().getId();
            return new Step(result.build(), null, endpoint, false, error);
        }
        if (!parsed.hasSnapshot() || parsed.getSnapshot().getPlayerId() != playerId) {
            String error = who + " 备战成功但快照缺失或 player_id 不符（补发取消）";
            return new Step(result.setError(error).build(), null, endpoint, true, error);
        }
        return new Step(result.build(), parsed, endpoint, true, null);
    }

    /** 全员指纹一致（match 的同一条比对，gather.go）；不一致返回原因。 */
    static String fingerprintMismatch(Map<Long, PrepareBattleResponse> prepared) {
        Set<String> fingerprints = new HashSet<>();
        prepared.values().forEach(p -> fingerprints.add(p.getTableFingerprint()));
        return fingerprints.size() > 1 ? "成员的战斗配表指纹不一致 " + fingerprints + "：各 scene 节点的配表版本不同" : null;
    }

    /** 组建房请求：快照来自 scene（team_index 按请求改写），按 player_id 升序；指纹取全员一致的那个。 */
    static CreateBattleRequest createRequest(DevGatherRequest request, Map<Long, Integer> members, Map<Long, PrepareBattleResponse> prepared,
                                             long nowMillis) {
        CreateBattleRequest.Builder create = CreateBattleRequest.newBuilder()
                .setBattleId(request.getBattleId())
                .setBattleConfigId(request.getBattleConfigId())
                .setSeed(request.getSeed())
                .setMatchMode(request.getMatchMode())
                .setCreatedAtMs(nowMillis)
                .setDeadlineMs(request.getDeadlineMs());
        String fingerprint = "";
        for (Map.Entry<Long, Integer> member : members.entrySet()) {
            PrepareBattleResponse response = prepared.get(member.getKey());
            BattlePlayerSnapshot snapshot = response.getSnapshot().toBuilder().setTeamIndex(member.getValue()).build();
            create.addPlayers(snapshot);
            fingerprint = response.getTableFingerprint();
        }
        return create.setTableFingerprint(fingerprint).build();
    }

    // ---------------------------------------------------------------- 建房

    /**
     * @param result  控制面结果（拿到时）
     * @param failure 非 null = 建房失败
     * @param unknown 结局不明（传输失败 / 超时 / UNSPECIFIED）：补偿前先 destroy
     */
    private record Created(CreateBattleResult result, String failure, boolean unknown) {
    }

    private static Created create(Plane plane, CreateBattleRequest request, long deadline) {
        CompletableFuture<CreateBattleResult> future;
        try {
            future = plane.create(request);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        long remainingNanos = Math.max(deadline - System.nanoTime(), 0);
        long waitMillis = Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), CREATE_TIMEOUT.toMillis());
        CreateBattleResult result;
        try {
            result = future.get(Math.max(waitMillis, 1), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Created(null, "建房 " + waitMillis + " ms 内没有结果（结局不明，已 destroy 并取消）", true);
        } catch (ExecutionException e) {
            return new Created(null, "建房调用失败（结局不明，已 destroy 并取消）：" + (e.getCause() == null ? e : e.getCause()), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Created(null, "等建房结果被中断（结局不明，已 destroy 并取消）", true);
        }
        if (result.getAdmission() == BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE) {
            return new Created(result, "建房不可分配 reason=" + result.getReason(), false);
        }
        if (result.getAdmission() != BattleAdmission.BATTLE_ADMISSION_ADMITTED) {
            return new Created(result, "建房结果 admission=" + result.getAdmission() + "（结局不明，已 destroy 并取消）", true);
        }
        CreateBattleResponse response;
        try {
            response = CreateBattleResponse.parseFrom(result.getResponse());
        } catch (InvalidProtocolBufferException e) {
            return new Created(result, "建房应答体不是 CreateBattleResponse（结局不明，已 destroy 并取消）", true);
        }
        if (response.getErrorMessage().getId() != 0) {
            // 业务错误 = 没建房、零副作用（没推送、没发确认）
            return new Created(result, "建房被拒 tip=" + response.getErrorMessage().getId()
                    + (response.getErrorMessage().getParametersCount() > 0 ? " " + response.getErrorMessage().getParametersList() : ""), false);
        }
        return new Created(result, null, false);
    }

    private static void destroyQuietly(Plane plane, long battleId) {
        try {
            plane.destroy(DestroyBattleRequest.newBuilder().setBattleId(battleId).setReason("dev_gather_compensate").build())
                    .get(CREATE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("dev gather 补偿 destroy 失败（忽略） battle_id={}: {}", Long.toUnsignedString(battleId), e.toString());
        }
    }

    // ---------------------------------------------------------------- 补偿

    /** 按 player_id 升序补发取消（尽力而为，失败只记日志；取消在 scene 侧幂等，battle_id 不符时忽略）。 */
    private void compensate(long battleId, TreeMap<Long, SceneAssetEndpoint> toCancel, DevGatherResponse.Builder response) {
        long deadline = System.nanoTime() + COMPENSATION_BUDGET.toNanos();
        for (Map.Entry<Long, SceneAssetEndpoint> entry : toCancel.entrySet()) {
            long playerId = entry.getKey();
            CancelBattlePrepareRequest body = CancelBattlePrepareRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId).build();
            SceneBattleCall call = SceneBattleCall.newBuilder().setTargetInstanceId(entry.getValue().instanceId()).setPlayerId(playerId)
                    .setBody(body.toByteString()).build();
            Duration timeout = remaining(deadline, SCENE_CALL_TIMEOUT);
            if (timeout.isZero()) {
                log.warn("dev gather 补偿取消的预算用完，剩下的人等备战期限解冻 battle_id={} player_id={}", Long.toUnsignedString(battleId),
                        Long.toUnsignedString(playerId));
                break;
            }
            Reply reply = await(scenes.cancel(entry.getValue(), call, timeout), deadline);
            response.addCancelledPlayerIds(playerId);
            if (reply.error() != null || reply.reply().getStatus() != SceneBattleStatus.SCENE_BATTLE_HANDLED) {
                log.warn("dev gather 补偿取消没有确定结论 battle_id={} player_id={} 结果={}", Long.toUnsignedString(battleId),
                        Long.toUnsignedString(playerId), reply.error() != null ? reply.error() : reply.reply().getStatus());
            }
        }
    }

    // ---------------------------------------------------------------- 等待

    private record Reply(SceneBattleReply reply, String error) {
    }

    private Resolution locate(long playerId, long deadline) {
        CompletableFuture<Resolution> future;
        try {
            future = scenes.locate(playerId);
        } catch (RuntimeException e) {
            return new Failure("定位抛出异常: " + e);
        }
        long waitMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(deadline - System.nanoTime(), 0));
        try {
            Resolution resolution = future.get(Math.max(waitMillis, 1), TimeUnit.MILLISECONDS);
            return resolution == null ? new Failure("定位结果为空") : resolution;
        } catch (TimeoutException e) {
            return new Failure("定位超时（" + waitMillis + " ms）");
        } catch (ExecutionException e) {
            return new Failure("定位出错: " + (e.getCause() == null ? e : e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure("等定位被中断");
        }
    }

    private static Reply await(CompletableFuture<SceneBattleReply> future, long deadline) {
        // scene 调用自带超时（NodeRpcClients 的本地兜底比它多 200 ms）；这里再多留 500 ms，免得先于调用自己的超时放弃
        long waitMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(deadline - System.nanoTime(), 0)) + 500;
        try {
            SceneBattleReply reply = future.get(waitMillis, TimeUnit.MILLISECONDS);
            return reply == null ? new Reply(null, "应答为空") : new Reply(reply, null);
        } catch (TimeoutException e) {
            future.cancel(false);
            return new Reply(null, "超时（" + waitMillis + " ms）");
        } catch (ExecutionException e) {
            return new Reply(null, String.valueOf(e.getCause() == null ? e : e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(null, "等待被中断");
        }
    }

    /** min(上限, 离截止还剩的时间)；已过截止为 0。 */
    static Duration remaining(long deadlineNanos, Duration cap) {
        long left = deadlineNanos - System.nanoTime();
        if (left <= 0) {
            return Duration.ZERO;
        }
        return left < cap.toNanos() ? Duration.ofNanos(left) : cap;
    }
}
