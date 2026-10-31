package com.game.battle.room;

import com.game.battle.metrics.BattleMetrics;
import com.game.proto.MessageContent;
import java.util.Objects;

/**
 * 一条<b>已验证</b>的客户端直连在房间一侧的视图（room ↔ edge 的接口；battle-node-spec §7.3、§7.4、§7.6）。由直连面
 * （{@code com.game.battle.edge}）实现、在握手第 5 步经 {@link BattleRoomService#attachDirect} 交给房间；房间把它放进
 * {@code player_id → DirectLink} 的直连槽（参战与观战共用一个槽）。房间不接触 Netty {@code Channel}。
 *
 * <p><b>全部方法只在逻辑线程上调用</b>（直连 I/O 与房间同一条线程）。身份按引用比较：{@link BattleRoomService#detachDirect} 只在槽里
 * 仍是<b>这一个</b>对象时才摘除（R7，旧连接迟到的断开不得摘掉新连接）。
 *
 * <p>写出顺序（实现方保证，评审逐条核对）：
 * <ul>
 *   <li>{@link #send}：立刻 {@code writeAndFlush}，同一条连接上的帧按调用顺序上线；</li>
 *   <li>R2：直连面在处理器（{@code BattleRoomService.submit / setAuto / ...}）<b>返回之后</b>才写这次请求的应答，所以处理器里推出的
 *       139 / 150 / 166 先于应答；</li>
 *   <li>R3：{@link #closeGracefully} 之后，本次请求的应答（若有）仍会写出，然后才是 FIN；</li>
 *   <li>R7：{@link #closeNow} 立即关，不再发任何帧。</li>
 * </ul>
 */
public interface DirectLink {

    /** 优雅关闭的强关兜底延迟（基线 {@code room.cpp:1614} {@code forceCloseWithDelay(1.0)}）。 */
    long GRACEFUL_FORCE_CLOSE_MS = 1000;

    /**
     * 「活直连」（基线 {@code room.cpp:1357-1376}）：已验证、底层连接仍 active、且没有进入关闭流程。
     * {@code PushPolicy.decide(category, link != null && link.isLive())} 据此决定直发 / 回落 / 丢弃。
     * 注意：{@link BattleRoomService#attachDirect} 执行期间它可能还是 false（直连面在挂接成功之后才置为已验证），挂接时不得写帧。
     */
    boolean isLive();

    /**
     * 写一帧 {@code MessageContent} 并 flush。不是 {@link #isLive()} 时静默丢弃（不抛异常）。
     * 同一个 {@code MessageContent} 对象可以写给多条直连（观众版回合帧每回合只构造一次，§11 N15）。
     */
    void send(MessageContent frame);

    /**
     * 一帧只编码一次、写给同一次扇出里多条直连的共享帧（mmorpg battle-spectate-tiered-fanout.md §10 第 3 条：观众版 158 与收尾 166）。
     * 由扇出里第一条活直连的 {@link DirectLink#share} 产出，交给每条直连的 {@link DirectLink#send(SharedFrame)}，扇出结束由调用方 {@link #close()} 一次。
     * 只在逻辑线程上用，不跨扇出保留：编码出的字节的引用计数由直连面实现管理，房间不接触 Netty。
     */
    interface SharedFrame extends AutoCloseable {

        /** 原帧：不支持共享编码的实现（测试替身）逐条写它。 */
        MessageContent content();

        /** 释放原件（幂等）；已经交给各连接的那几份引用由各自的写出流程释放，写成功、写失败都一样。 */
        @Override
        void close();

        /** 不编码的共享帧：{@link DirectLink#send(SharedFrame)} 的缺省实现据此逐条写原帧，{@link #close()} 什么也不做。 */
        static SharedFrame unencoded(MessageContent frame) {
            Objects.requireNonNull(frame, "frame");
            return new SharedFrame() {
                @Override
                public MessageContent content() {
                    return frame;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    /**
     * 为一次扇出把 {@code frame} 编码一次（用本连接的分配器）；调用方用完必须 {@link SharedFrame#close()}。
     * 缺省实现不编码：{@link #send(SharedFrame)} 退回逐条写原帧（测试替身用）。
     */
    default SharedFrame share(MessageContent frame) {
        return SharedFrame.unencoded(frame);
    }

    /**
     * 写一份共享帧并 flush（同 {@link #send(MessageContent)} 的顺序保证）；不是 {@link #isLive()} 时静默丢弃。
     * 缺省实现退回 {@link #send(MessageContent)}。
     */
    default void send(SharedFrame frame) {
        send(frame.content());
    }

    /**
     * 优雅关闭（R3；终局、退出观战、观众被清退、销毁、停机作废）：
     * <ol>
     *   <li>立刻脱离房间：{@link #isLive()} 变 false，{@link #send} 一律丢弃；当前正在处理的那条请求的应答照常写出；</li>
     *   <li>当前这次读处理完、或排在当前逻辑任务之后，先到者让连接进入关闭中，之后到达的上行帧一律忽略——同一次读里已经到达的帧照常分发、
     *       各自的应答照常写出（基线 {@code ShutdownDirectConnAfterThisLoop} 只 {@code queueInLoop}，{@code battle_room_manager.cpp:1602-1617}）；</li>
     *   <li>排在当前逻辑任务之后：等此前写出的全部字节进内核后发 FIN（{@code shutdownOutput}）；</li>
     *   <li>{@value #GRACEFUL_FORCE_CLOSE_MS} ms 后强关兜底（对端不读时空写永远不完成）。</li>
     * </ol>
     * 调用方（房间）必须<b>先</b>把它从直连槽里摘掉再调用（槽位立刻摘除，{@code room.cpp:1632-1634}）。幂等。
     *
     * @param reason 计入 {@code game_battle_disconnects_total{reason}}（{@code BATTLE_CLOSED} / {@code SHUTDOWN}）
     */
    void closeGracefully(BattleMetrics.Disconnect reason);

    /**
     * 立即强关，不发任何帧（R7 重连顶替：同一玩家的新直连挂接成功时，旧连接走这里）。幂等。
     *
     * @param reason 计入 {@code game_battle_disconnects_total{reason}}（{@code REPLACED}）
     */
    void closeNow(BattleMetrics.Disconnect reason);

    /** 日志用的对端描述（{@code ip:port}）；不得进指标标签。 */
    String peer();
}
