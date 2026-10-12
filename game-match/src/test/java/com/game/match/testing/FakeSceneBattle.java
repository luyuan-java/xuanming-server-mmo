package com.game.match.testing;

import com.game.api.SceneBattleService;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 一个假的 scene 节点的战斗入口（{@link SceneBattleService}）：按 6.3 的语义应答备战 / 取消，记下每次调用，并维护一张「谁被冻结着」的表——
 * 开局管线的测试靠它断言补偿把该解冻的人都解冻了（含备战结局不明的人，M14）。配 {@link FakeNodeCalls} 使用。
 *
 * <pre>
 * FakeSceneBattle scene = new FakeSceneBattle("scene-inst-a", events);   // events：与别的替身共用的事件序列（可省）
 * // 缺省：备战成功（冻结；回快照，角色名「角色&lt;pid&gt;」、指纹 "fp-1"、routing 齐全），取消成功（解冻）
 * scene.prepareTip(1002, 1006);                          // HANDLED 但被拒（零冻结痕迹）
 * scene.prepareStatus(1002, SceneBattleStatus.SCENE_BATTLE_OVERLOADED);   // NOT_HERE / OVERLOADED / UNSPECIFIED
 * scene.prepareFails(1002, () -> new TimeoutException("慢"), true);        // 传输失败；true = 其实已经冻结上了（结局不明）
 * scene.prepareHangs(1002);                              // 永不应答（由调用方自己的超时收场）
 * scene.fingerprint(1002, "fp-2").name(1002, "乙");       // 快照里的指纹与角色名
 * scene.cancelFails(1001, () -> new IllegalStateException("断连"));
 * assertThat(scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001");
 * assertThat(scene.frozen()).isEmpty();                  // 补偿之后没有人还冻结着
 * </pre>
 * 实例不符（{@code call.target_instance_id} 不是本节点的实例号）一律回 NOT_HERE，同真实现。{@code confirmBattle} / {@code applySettlement} 不归 match 调，
 * 调到即失败。线程安全。
 */
public final class FakeSceneBattle implements SceneBattleService {

    /**
     * 一次调用。
     *
     * @param method           {@code "prepare"} / {@code "cancel"}
     * @param targetInstanceId 调用方填的目标实例
     * @param prepare          备战请求（取消时为 null）
     * @param cancel           取消请求（备战时为 null）
     */
    public record Call(String method, long playerId, long battleId, String targetInstanceId, PrepareBattleRequest prepare,
                       CancelBattlePrepareRequest cancel) {

        /** {@code "prepare:1001"} / {@code "cancel:1001"}。 */
        public String describe() {
            return method + ":" + Long.toUnsignedString(playerId);
        }
    }

    /** 全部调用，按到达顺序。 */
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    /** 事件序列：{@code "scene.prepare:<pid>"} / {@code "scene.cancel:<pid>"}。 */
    public final List<String> events;
    private final String instanceId;
    private final Map<Long, Long> frozen = new ConcurrentHashMap<>();
    private final Map<Long, Supplier<CompletableFuture<SceneBattleReply>>> prepareScripts = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> freezeBeforeScript = new ConcurrentHashMap<>();
    private final Map<Long, Supplier<CompletableFuture<SceneBattleReply>>> cancelScripts = new ConcurrentHashMap<>();
    private final Map<Long, String> fingerprints = new ConcurrentHashMap<>();
    private final Map<Long, String> names = new ConcurrentHashMap<>();
    private final Map<Long, Integer> zones = new ConcurrentHashMap<>();

    public FakeSceneBattle(String instanceId) {
        this(instanceId, new CopyOnWriteArrayList<>());
    }

    public FakeSceneBattle(String instanceId, List<String> events) {
        this.instanceId = instanceId;
        this.events = events;
    }

    public String instanceId() {
        return instanceId;
    }

    // ---------------------------------------------------------------- 脚本

    /** 恢复成缺省的「备战成功」。 */
    public FakeSceneBattle prepareOk(long playerId) {
        prepareScripts.remove(playerId);
        freezeBeforeScript.remove(playerId);
        return this;
    }

    /** HANDLED 但带错误码（1004 不在本节点 / 1006 冻结中或已有战斗 / 1003 写锁出错……）：不冻结、没有快照。 */
    public FakeSceneBattle prepareTip(long playerId, int tipId) {
        return prepareScript(playerId, false, () -> CompletableFuture.completedFuture(handled(
                PrepareBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(tipId)).build())));
    }

    /** HANDLED、没有错误码、但也没有快照（异常应答：管线按备战失败处理）。<b>已冻结</b>——这种应答要按结局不明补发取消。 */
    public FakeSceneBattle prepareWithoutSnapshot(long playerId) {
        return prepareScript(playerId, true, () -> CompletableFuture.completedFuture(handled(PrepareBattleResponse.getDefaultInstance())));
    }

    /** 回一个没有 body 的状态：NOT_HERE / OVERLOADED（零副作用）或 UNSPECIFIED（按传输失败处理）。不冻结。 */
    public FakeSceneBattle prepareStatus(long playerId, SceneBattleStatus status) {
        return prepareScript(playerId, false, () -> CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(status).build()));
    }

    /**
     * 传输失败：future 以给定的异常完成。
     *
     * @param tookEffect true = 请求其实已经在 scene 生效（已冻结），只是应答没回来——管线必须对他补发取消
     */
    public FakeSceneBattle prepareFails(long playerId, Supplier<? extends Throwable> error, boolean tookEffect) {
        return prepareScript(playerId, tookEffect, () -> CompletableFuture.failedFuture(error.get()));
    }

    /** 永不应答（已冻结）：调用方按自己的超时收场。 */
    public FakeSceneBattle prepareHangs(long playerId) {
        return prepareScript(playerId, true, CompletableFuture::new);
    }

    private FakeSceneBattle prepareScript(long playerId, boolean freeze, Supplier<CompletableFuture<SceneBattleReply>> script) {
        prepareScripts.put(playerId, script);
        freezeBeforeScript.put(playerId, freeze);
        return this;
    }

    /** 取消的传输失败（冻结<b>不</b>解除：由 scene 的 reaper 兜底，测试里表现为他留在 {@link #frozen()} 里）。 */
    public FakeSceneBattle cancelFails(long playerId, Supplier<? extends Throwable> error) {
        cancelScripts.put(playerId, () -> CompletableFuture.failedFuture(error.get()));
        return this;
    }

    /** 取消回一个状态（NOT_HERE / OVERLOADED）：冻结不解除。 */
    public FakeSceneBattle cancelStatus(long playerId, SceneBattleStatus status) {
        cancelScripts.put(playerId, () -> CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(status).build()));
        return this;
    }

    /** 这名玩家的快照里带的配表指纹（缺省 {@code "fp-1"}；传空串 = 没有指纹）。 */
    public FakeSceneBattle fingerprint(long playerId, String fingerprint) {
        fingerprints.put(playerId, fingerprint);
        return this;
    }

    /** 这名玩家快照里的角色名（缺省 {@code "角色<pid>"}）。 */
    public FakeSceneBattle name(long playerId, String name) {
        names.put(playerId, name);
        return this;
    }

    /** 这名玩家快照 routing 里的 zone（缺省 1）。 */
    public FakeSceneBattle zone(long playerId, int zoneId) {
        zones.put(playerId, zoneId);
        return this;
    }

    /** 此刻被冻结的玩家 → battle_id。 */
    public Map<Long, Long> frozen() {
        return Map.copyOf(frozen);
    }

    // ---------------------------------------------------------------- SceneBattleService

    @Override
    public CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call) {
        PrepareBattleRequest request;
        try {
            request = PrepareBattleRequest.parseFrom(call.getBody());
        } catch (InvalidProtocolBufferException e) {
            return CompletableFuture.failedFuture(e);
        }
        long playerId = request.getPlayerId();
        calls.add(new Call("prepare", playerId, request.getBattleId(), call.getTargetInstanceId(), request, null));
        events.add("scene.prepare:" + Long.toUnsignedString(playerId));
        if (!instanceId.equals(call.getTargetInstanceId())) {
            return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_NOT_HERE).build());
        }
        Supplier<CompletableFuture<SceneBattleReply>> script = prepareScripts.get(playerId);
        if (script != null) {
            if (freezeBeforeScript.getOrDefault(playerId, false)) {
                frozen.put(playerId, request.getBattleId());
            }
            return script.get();
        }
        if (frozen.containsKey(playerId) && frozen.get(playerId) != request.getBattleId()) {
            // 已有在途战斗：同真实现回 1006、不留痕迹
            return CompletableFuture.completedFuture(handled(
                    PrepareBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1006)).build()));
        }
        frozen.put(playerId, request.getBattleId());
        String fingerprint = fingerprints.getOrDefault(playerId, "fp-1");
        BattlePlayerSnapshot snapshot = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName(names.getOrDefault(playerId, "角色" + Long.toUnsignedString(playerId)))
                .setLevel(1).setMaxHealth(100)
                .setTableFingerprint(fingerprint)
                .setRouting(BattleRouting.newBuilder().setSessionId((int) (playerId & 0x7FFF_FFFF)).setGateNodeId(1).setGateInstanceId("gate-inst-1")
                        .setSceneNodeId(7).setSceneInstanceId(instanceId).setZoneId(zones.getOrDefault(playerId, 1)))
                .build();
        return CompletableFuture.completedFuture(handled(
                PrepareBattleResponse.newBuilder().setSnapshot(snapshot).setTableFingerprint(fingerprint).build()));
    }

    @Override
    public CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call) {
        CancelBattlePrepareRequest request;
        try {
            request = CancelBattlePrepareRequest.parseFrom(call.getBody());
        } catch (InvalidProtocolBufferException e) {
            return CompletableFuture.failedFuture(e);
        }
        long playerId = request.getPlayerId();
        calls.add(new Call("cancel", playerId, request.getBattleId(), call.getTargetInstanceId(), null, request));
        events.add("scene.cancel:" + Long.toUnsignedString(playerId));
        if (!instanceId.equals(call.getTargetInstanceId())) {
            return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_NOT_HERE).build());
        }
        Supplier<CompletableFuture<SceneBattleReply>> script = cancelScripts.get(playerId);
        if (script != null) {
            return script.get();
        }
        // 幂等；battle_id 不符时忽略
        frozen.remove(playerId, request.getBattleId());
        return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).build());
    }

    @Override
    public CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("match 不调 confirmBattle"));
    }

    @Override
    public CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("match 不调 applySettlement"));
    }

    private static SceneBattleReply handled(PrepareBattleResponse body) {
        return SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).setBody(body.toByteString()).build();
    }
}
