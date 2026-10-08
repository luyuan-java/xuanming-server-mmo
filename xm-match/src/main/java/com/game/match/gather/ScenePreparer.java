package com.game.match.gather;

import com.game.api.SceneBattleService;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.match.MatchBudgets;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.rpc.NodeRpcClients;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.match.port.NodeCalls;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gather 对 scene 的两件事（match-spec §9.6 第 3 步与 fail 行、§9.7.2；基线 {@code preparePlayer} / {@code cancelPrepared}，
 * {@code gather.go:399-449}、{@code :532-557}）：
 *
 * <ol>
 *   <li><b>备战</b>：定位持有这名玩家的 scene 节点（位置记录只认在线 {@code o} → 按<b>位置记录里的 (zone, 节点号)</b> 读 scene 目录 → 直连地址；
 *       判定顺序即 {@link SceneAssetLocator}），再调 {@code SceneBattleService.prepareBattle}。按 (zone, 节点号) 找而不是只按节点号：
 *       节点号按 zone 租约，两个 zone 可以有同号节点。</li>
 *   <li><b>取消</b>：发往<b>备战时用的那个端点</b>（{@link Endpoint}），不按位置记录重新解析——Java 断线即移除实体、位置随之变成重连租约，
 *       重新解析会找不到持有者而漏发取消；scene 的取消对「玩家已不在本节点」有专门的分支（按锁值条件清锁）。
 *       端点是记下来的，实例号可能已经过时（scene 原地重启）：经 {@link NodeCalls#callRemembered} 发，不顶掉别的 gather 正在用的客户端。</li>
 * </ol>
 *
 * <p><b>备战应答的映射</b>（{@link Prepare}；谁需要补发取消是这张表的要点，M14）：
 * <table>
 *   <caption>scene 的应答 → gather 的处理</caption>
 *   <tr><th>情形</th><th>结局</th><th>补发取消</th></tr>
 *   <tr><td>定位不到持有者 / 读位置或目录出错 / 定位超时</td><td>{@code no_location}，该玩家是肇事者</td><td>否（请求没发出）</td></tr>
 *   <tr><td>{@code HANDLED}，错误码为 0，快照在且是本人</td><td>成功（已冻结）</td><td>gather 失败时要</td></tr>
 *   <tr><td>{@code HANDLED}，错误码非 0（不在本节点 1004、冻结中或已有战斗 1006、写锁出错 1003……）</td><td>{@code prepare_failed}</td>
 *       <td>否（scene 保证拒绝不留冻结痕迹）</td></tr>
 *   <tr><td>{@code NOT_HERE}（实例不符）/ {@code OVERLOADED}（在途超限）</td><td>{@code prepare_failed}</td><td>否（保证没进逻辑线程）</td></tr>
 *   <tr><td>传输失败、超时、{@code UNSPECIFIED} 等别的状态、应答体解析不了、错误码为 0 却没有快照或快照不是本人</td><td>{@code prepare_failed}</td>
 *       <td><b>要</b>：结局不明，请求可能已在 scene 生效</td></tr>
 * </table>
 * 备战超时是 3 s（{@link MatchBudgets#PREPARE_BATTLE_TIMEOUT_MS}），小于 scene 侧一条写锁脚本的最坏耗时：超时<b>不等于</b>失败，按结局不明处理。
 *
 * <p>线程：两个方法都阻塞（只在 future 上等，不持锁），在 gather 的虚拟线程上调。<b>都不抛异常</b>。无状态、线程安全。
 */
public final class ScenePreparer {

    private static final Logger log = LoggerFactory.getLogger(ScenePreparer.class);

    /** 定位（一次位置读 + 一次目录读）的等待上限；超出按定位不到处理。算在 matched TTL 公式给 Redis 小操作留的余量里。 */
    static final long LOCATE_BUDGET_MS = 4_500;
    /** 本地等待比调用超时多给的余量：出站口自己会先以超时失败，这里只防 future 永不完成。 */
    static final long LOCAL_WAIT_GRACE_MS = 250;

    /**
     * 备战时用的 scene 端点：补偿的取消发回这里。
     *
     * @param zoneId 位置记录里的 zone
     * @param nodeId scene 节点号（在该 zone 内唯一）
     * @param target 直连地址与实例号（目录里读到的那一刻）
     */
    public record Endpoint(int zoneId, int nodeId, NodeRpcClients.Target target) {

        @Override
        public String toString() {
            return "z" + Integer.toUnsignedString(zoneId) + "/n" + Integer.toUnsignedString(nodeId) + "@" + target.address() + "#" + target.instanceId();
        }
    }

    /** 一名成员的备战结局（二选一，调用方穷举）。 */
    public sealed interface Prepare {

        /** 已冻结：{@code response} 带快照（非空且是本人）与配表指纹。 */
        record Ok(Endpoint endpoint, PrepareBattleResponse response) implements Prepare {
        }

        /**
         * 没备成，该玩家是肇事者。
         *
         * @param outcome      {@link GatherOutcome#NO_LOCATION}（没定位到，请求没发出）或 {@link GatherOutcome#PREPARE_FAILED}
         * @param endpoint     发往的端点；没定位到时为 null
         * @param cancelNeeded true = 结局不明（请求可能已在 scene 生效）：补偿时要对他补发取消。为真时 {@code endpoint} 一定非 null
         * @param detail       原因（只进日志）
         */
        record Failed(GatherOutcome outcome, Endpoint endpoint, boolean cancelNeeded, String detail) implements Prepare {
        }
    }

    private final SceneAssetLocator locator;
    private final NodeCalls<SceneBattleService> calls;
    private final Duration prepareTimeout;
    private final Duration cancelTimeout;

    /** 生产装配：备战 / 取消各 3 s。 */
    public ScenePreparer(SceneAssetLocator locator, NodeCalls<SceneBattleService> calls) {
        this(locator, calls, Duration.ofMillis(MatchBudgets.PREPARE_BATTLE_TIMEOUT_MS), Duration.ofMillis(MatchBudgets.CANCEL_PREPARE_TIMEOUT_MS));
    }

    /** 测试用：可以把两跳超时收短（生产值出现在跨进程不等式里，不开放配置）。 */
    ScenePreparer(SceneAssetLocator locator, NodeCalls<SceneBattleService> calls, Duration prepareTimeout, Duration cancelTimeout) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.calls = Objects.requireNonNull(calls, "calls");
        this.prepareTimeout = Objects.requireNonNull(prepareTimeout, "prepareTimeout");
        this.cancelTimeout = Objects.requireNonNull(cancelTimeout, "cancelTimeout");
    }

    /**
     * 定位并备战一名成员。
     *
     * @param request 备战请求（{@code player_id}、{@code battle_id}、首选 battle 节点号、两个期限）
     */
    public Prepare prepare(PrepareBattleRequest request) {
        long playerId = request.getPlayerId();
        Endpoint endpoint;
        Resolution where = locate(playerId);
        switch (where) {
            case NoHolder none -> {
                return new Prepare.Failed(GatherOutcome.NO_LOCATION, null, false, "此刻没有 scene 节点持有该玩家（" + none.result().label() + "）");
            }
            case Failure failure -> {
                return new Prepare.Failed(GatherOutcome.NO_LOCATION, null, false, "定位失败: " + failure.reason());
            }
            case Found found -> {
                SceneAssetEndpoint at = found.endpoint();
                try {
                    endpoint = new Endpoint(at.zoneId(), at.nodeId(), new NodeRpcClients.Target(at.host(), at.port(), at.instanceId()));
                } catch (IllegalArgumentException e) {
                    return new Prepare.Failed(GatherOutcome.NO_LOCATION, null, false, "scene 节点目录条目的直连地址不合法: " + e.getMessage());
                }
            }
        }
        SceneBattleCall call = SceneBattleCall.newBuilder().setTargetInstanceId(endpoint.target().instanceId()).setPlayerId(playerId)
                .setBody(request.toByteString()).build();
        Reply reply = invoke(endpoint, prepareTimeout, scene -> scene.prepareBattle(call), false);
        if (reply.error() != null) {
            return new Prepare.Failed(GatherOutcome.PREPARE_FAILED, endpoint, true, "备战调用失败（结局不明）: " + reply.error());
        }
        SceneBattleReply got = reply.reply();
        SceneBattleStatus status = got.getStatus();
        if (status == SceneBattleStatus.SCENE_BATTLE_NOT_HERE || status == SceneBattleStatus.SCENE_BATTLE_OVERLOADED) {
            return new Prepare.Failed(GatherOutcome.PREPARE_FAILED, endpoint, false, "scene 应答 " + status + "（零副作用）");
        }
        if (status != SceneBattleStatus.SCENE_BATTLE_HANDLED) {
            return new Prepare.Failed(GatherOutcome.PREPARE_FAILED, endpoint, true, "scene 应答 " + status + "（按结局不明处理）");
        }
        PrepareBattleResponse response;
        try {
            response = PrepareBattleResponse.parseFrom(got.getBody());
        } catch (InvalidProtocolBufferException e) {
            return new Prepare.Failed(GatherOutcome.PREPARE_FAILED, endpoint, true, "备战应答体解析失败（按结局不明处理）");
        }
        int tip = response.getErrorMessage().getId();
        if (tip != 0) {
            return new Prepare.Failed(GatherOutcome.PREPARE_FAILED, endpoint, false, "scene 拒绝备战 tip_id=" + Integer.toUnsignedString(tip));
        }
        if (!response.hasSnapshot() || response.getSnapshot().getPlayerId() != playerId) {
            return new Prepare.Failed(GatherOutcome.PREPARE_FAILED, endpoint, true, "备战成功但快照缺失或不是本人（按结局不明处理）");
        }
        return new Prepare.Ok(endpoint, response);
    }

    /**
     * 取消一名成员的备战（幂等；scene 按 battle_id 守护，迟到的取消不会解掉别的局）。至多等 3 s，失败只记日志——冻结与锁由 scene 的 reaper
     * （备战期限）与锁 TTL 收尾。
     *
     * @param endpoint 备战时用的端点
     * @return true = scene 已处理；false = 没确认（传输失败 / 超时 / 实例不符 / 过载），只用于日志与测试
     */
    public boolean cancel(long playerId, long battleId, Endpoint endpoint) {
        String battle = Long.toUnsignedString(battleId);
        String player = Long.toUnsignedString(playerId);
        CancelBattlePrepareRequest body = CancelBattlePrepareRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId).build();
        SceneBattleCall call = SceneBattleCall.newBuilder().setTargetInstanceId(endpoint.target().instanceId()).setPlayerId(playerId)
                .setBody(body.toByteString()).build();
        Reply reply = invoke(endpoint, cancelTimeout, scene -> scene.cancelBattlePrepare(call), true);
        if (reply.error() != null) {
            log.error("取消备战失败（scene 的 reaper 会按备战期限解冻） battle_id={} player={} scene={}: {}", battle, player, endpoint, reply.error());
            return false;
        }
        SceneBattleStatus status = reply.reply().getStatus();
        if (status != SceneBattleStatus.SCENE_BATTLE_HANDLED) {
            log.warn("取消备战没有被处理（scene 的 reaper 会按备战期限解冻） battle_id={} player={} scene={} status={}", battle, player, endpoint, status);
            return false;
        }
        log.info("已解冻 battle_id={} player={} scene={}", battle, player, endpoint);
        return true;
    }

    private Resolution locate(long playerId) {
        try {
            Resolution found = locator.resolveAsync(playerId).get(LOCATE_BUDGET_MS, TimeUnit.MILLISECONDS);
            return found != null ? found : new Failure("定位没有返回结果");
        } catch (TimeoutException e) {
            return new Failure("定位超过 " + LOCATE_BUDGET_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure("定位时被中断");
        } catch (ExecutionException | RuntimeException e) {
            return new Failure("定位出错: " + (e.getCause() == null ? e : e.getCause()));
        }
    }

    /** 一次调用的结局：应答，或失败原因（传输失败 / 超时 / 应答为空）。 */
    private record Reply(SceneBattleReply reply, String error) {
    }

    /**
     * 发一次调用并在超时内等结果；出站口同步抛出的异常也收成失败。
     *
     * @param remembered true = 端点是先前记下来的（取消发回备战时的端点）：不因它的实例号过时而顶掉这个地址上现有的客户端
     */
    private Reply invoke(Endpoint endpoint, Duration timeout, Function<SceneBattleService, CompletableFuture<SceneBattleReply>> invocation,
                         boolean remembered) {
        CompletableFuture<SceneBattleReply> future;
        try {
            future = remembered ? calls.callRemembered(endpoint.target(), timeout, invocation) : calls.call(endpoint.target(), timeout, invocation);
        } catch (RuntimeException e) {
            return new Reply(null, String.valueOf(e));
        }
        if (future == null) {
            return new Reply(null, "出站口没有返回 future");
        }
        try {
            SceneBattleReply reply = future.get(Math.max(0, timeout.toMillis()) + LOCAL_WAIT_GRACE_MS, TimeUnit.MILLISECONDS);
            return reply != null ? new Reply(reply, null) : new Reply(null, "应答为空");
        } catch (TimeoutException e) {
            return new Reply(null, "超时 " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(null, "被中断");
        } catch (ExecutionException | RuntimeException e) {
            return new Reply(null, String.valueOf(e.getCause() == null ? e : e.getCause()));
        }
    }
}
