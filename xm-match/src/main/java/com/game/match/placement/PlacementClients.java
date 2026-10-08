package com.game.match.placement;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.match.MatchBudgets;
import com.game.api.rpc.NodeRpcClients;
import com.game.match.port.IdleSweep;
import com.game.match.port.NodeCalls;
import com.game.match.port.NodeClientCache;
import java.time.Duration;
import java.util.Objects;

/**
 * 按落点记录直拨 battle（补签 179、6.5 的观众 RPC）专用的直连客户端缓存——<b>与 gather 建房 / 销毁用的那一份分开</b>。
 *
 * <p>为什么分开：底层缓存的键只有地址，同一地址上实例号不同就销毁旧引用再建。gather 的目标取自目录（现在的实例），直拨的目标取自落点记录
 * （建房那一刻的实例，记录活 360 s）。battle 在原地址重启之后（固定端口的部署与本机切片都是原地址），共用一份缓存的话：每来一条旧局的 179，
 * 就把 gather 正在用的新实例引用销毁重建，gather 的下一次建房又反过来销毁重建一次——被销毁的引用上在途的建房变成「结局不明」、
 * 在途的补签白回一次 1003，而 battle 重启恢复后的头几秒正是旧局客户端集中补签、凑单把积压的组发给新实例的时候。
 * 分成两份之后两边互不相干；直拨这一份还把目标的实例段固定成空串（{@link DirectPlacementDialer}），永不因实例号重建。
 *
 * <p>单独成一个类型（而不是再登记一个同类型的缓存 bean）只为按类型注入时不与 gather 的那一份混淆；它不以 {@code NodeCalls<BattleNodeService>}
 * 的身份出现在容器里。空闲的引用由 {@code NodeClientSweeper} 定时清（{@link IdleSweep}）。线程安全。
 */
public final class PlacementClients implements IdleSweep, AutoCloseable {

    /** 空闲多久清掉：一条落点记录的寿命。超过它，这个地址上不会再有哪一局的直拨了（还有的话重建一次引用即可）。 */
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(MatchBudgets.PLACEMENT_TTL_SECONDS);

    private final NodeClientCache<BattleNodeService> cache;

    /** 生产装配：自己的 Dubbo 模型与建连线程（{@code match-placement-connect}）；引用上的缺省超时是补签的 3 s，每次调用再按次给。 */
    public PlacementClients() {
        this(new NodeClientCache<>("battle-placement", new NodeRpcClients<>("xm-match-battle-placement", BattleNodeService.class,
                DubboGroups.BATTLE_NODE, Duration.ofMillis(MatchBudgets.ISSUE_TICKET_TIMEOUT_MS), "match-placement-connect"), IDLE_TIMEOUT));
    }

    PlacementClients(NodeClientCache<BattleNodeService> cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /** 直拨用的出站口。 */
    public NodeCalls<BattleNodeService> calls() {
        return cache.calls();
    }

    @Override
    public String name() {
        return cache.name();
    }

    @Override
    public int sweepIdle() {
        return cache.sweepIdle();
    }

    /** 当前缓存着的地址数（测试用）。 */
    int size() {
        return cache.size();
    }

    @Override
    public void close() {
        cache.close();
    }
}
