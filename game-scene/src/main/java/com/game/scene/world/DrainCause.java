package com.game.scene.world;

/**
 * 场景为什么在排空中（批次 5.3，dungeon-mirror-spec §6.4、§7.2「常量」）。{@link Scene#draining()} 的语义不变——不接新进入、在场者被改派、
 * 空了销毁；原因只决定<b>何时</b>销毁与能否复活：
 * <ul>
 *   <li>{@link #PLAN}：5.1 频道计划转排空（只对主世界频道）；计划改回承载中即恢复。</li>
 *   <li>{@link #IDLE}：实例空置满超时进入回收宽限（§6.10）：宽限满且仍空、没有在途进场才销毁；宽限内有在途进场到达即复活。</li>
 *   <li>{@link #CASCADE}：镜像的源频道已销毁（或兜底检查发现源不在本地 / 不是主世界频道，§6.11）：居民同图改派，空了即销毁，不复活。</li>
 *   <li>{@link #ADMIN}：dev 管理口显式销毁（§6.11、D17）：同上。</li>
 * </ul>
 */
public enum DrainCause {
    NONE,
    PLAN,
    IDLE,
    CASCADE,
    ADMIN
}
