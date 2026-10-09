package com.game.discovery.zone;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 合服围栏的读侧：某个 zone 此刻是不是正在合服 / 撤销合服（基线 {@code merge:in_progress:{zone}}，只看键在不在；
 * zone-travel-spec §3.5、zone-merge-spec §2.6）。包名与接口名是给批次 7.3 冻结的（{@code RedisZoneMergeFence implements
 * ZoneMergeFence}），不许改。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>回 {@code true} = 该 zone 被围栏封锁，调用方拒绝这次写 / 进场；</li>
 *   <li><b>读不到一律按封锁处理（fail-closed）</b>：同步版抛异常，异步版以异常完成；调用方不得把「读不到」当成放行；</li>
 *   <li>zone 为 0（内部调用、没有 zone 可判）实现一律回 {@code false}——「zone 为 0 该不该拒绝」是调用方自己的规则
 *       （例：建角的会话 zone 为 0 回 2020，不问围栏）；</li>
 *   <li>同步版会阻塞（7.3 的实现读 Redis，等待上限 500 ms），不得在 Netty I/O 线程或场景逻辑线程上调用；
 *       那些线程用异步版，结果投递回所属线程。</li>
 * </ul>
 *
 * <p><b>检查点</b>（判哪个 zone → 命中或读不到时回什么）：F1 login 建角，发号之前（会话 zone → 14 {2020}）；
 * F2 login 进游戏的落地路径，分配与夺权之前（角色的归属区 → 26 {3023}）；F4 scene-manager 给 226 选目标（归属区 → 23 {3027}）；
 * F5 帮会写 RPC（14013，现由 xm-guild 自己的 {@code MergeFence} 表达，7.3 改为适配本接口）。
 *
 * <p>批次 7.3 之前 Java 版没有合服：只有恒放行的 {@link #OPEN}，检查点照样保留，接上真实现时不动调用方。
 */
public interface ZoneMergeFence {

    /**
     * @param zoneId 要判的 zone（uint32；0 = 没有 zone 可判，回 false）
     * @return 该 zone 是否正在合服
     * @throws Exception 围栏状态读不出来（调用方按合服中处理，fail-closed）
     */
    boolean inProgress(int zoneId) throws Exception;

    /**
     * {@link #inProgress} 的异步版，不阻塞调用线程。
     *
     * @return 该 zone 是否正在合服；读不出来时<b>以异常完成</b>（调用方按合服中处理）。回调跑在哪条线程由实现决定，
     *         调用方自己投递回所属线程
     */
    CompletionStage<Boolean> inProgressAsync(int zoneId);

    /** 批次 7.3 之前：没有合服，恒放行（同步版恒 false；异步版回一个已完成的 false）。 */
    ZoneMergeFence OPEN = new ZoneMergeFence() {
        @Override
        public boolean inProgress(int zoneId) {
            return false;
        }

        @Override
        public CompletionStage<Boolean> inProgressAsync(int zoneId) {
            // 每次回一个新的只读阶段：调用方拿它转成 future 再改写，也污染不到别的调用方。
            return CompletableFuture.completedStage(Boolean.FALSE);
        }

        @Override
        public String toString() {
            return "ZoneMergeFence.OPEN";
        }
    };
}
