package com.game.battle.edge;

import java.time.Duration;

/**
 * 客户端直连面的生命周期（{@code BattleNode} ↔ edge 的接口；battle-node-spec §7.4、§7.11）。实现 {@link BattleEdgeServer}。
 *
 * <p>启停顺序（{@code BattleNode} 负责）：
 * <ol>
 *   <li>启动第 7 步 {@link #start()}：Dubbo 导出成功之后绑定直连端口；之后才在逻辑线程上开准入闸、发布目录。</li>
 *   <li>停机第 2 步（逻辑线程上的同一个任务）：关准入闸 → {@code BattleRoomService.abortAll("node_shutdown")}——房间里的直连全部走优雅关闭。</li>
 *   <li>停机第 3 步 {@link #stopAccepting()} → {@link #drainAndClose(Duration)}（修基线 F1：观众的 166 不会被强关吞掉，§11 N16）。</li>
 *   <li>之后控制面才反导出 Dubbo、停逻辑线程（逻辑线程组归 {@code BattleNode} 所有，直连面不关它）。</li>
 * </ol>
 */
public interface DirectEdge {

    /**
     * 绑定 {@code xm.battle.client-bind-host : client-port} 并开始接受连接（阻塞到绑定完成）。
     *
     * @throws IllegalStateException 绑定失败（端口被占用等）：节点拒绝启动
     */
    void start();

    /** 关监听 channel，不再接受新连接；已有连接不动。幂等。可在任何线程调用（不得阻塞逻辑线程）。 */
    void stopAccepting();

    /**
     * 有界等待正在优雅关闭的连接排空（终局包与 FIN 写完），至多 {@code timeout}；然后强关剩余的全部连接（未验证、空闲），释放 accept 线程。
     * <b>阻塞调用线程，不得在逻辑线程上调用</b>（它要等逻辑线程把输出写完）。幂等。
     */
    void drainAndClose(Duration timeout);

    /** 当前直连数（含未握手）。任何线程可调（原子量），目录发布与指标从别的线程读。 */
    int connectionCount();
}
