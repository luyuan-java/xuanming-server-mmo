package com.game.discovery.world;

/**
 * 一次计划写入的结局（scene-channels-spec §4.4、§4.14）。
 *
 * @param status     结局
 * @param newVersion {@link Status#WRITTEN} 时为写入后的版本号（= {@link WorldPlanBatch#writtenVersion()}），否则为 0
 */
public record WorldPlanWriteResult(Status status, long newVersion) {

    public enum Status {
        /** 令牌与版本号都对上，整批已执行，ver 已置为写入后的版本号。 */
        WRITTEN,
        /** Lua 回 −1：领导锁的值不是本令牌（已被夺或已过期）——调用方放弃该 zone 的领导（{@code world_ticks_total{result=fenced}}）。 */
        FENCED,
        /**
         * Lua 回 −2：版本号与快照读到的不同（双领导窗口、运维手改，或 Redisson 在响应超时后重发了已成功的同一段 EVAL）——
         * 本拍作废，下拍重读重算（{@code result=conflict}）。
         */
        CONFLICT
    }

    public static final WorldPlanWriteResult FENCED = new WorldPlanWriteResult(Status.FENCED, 0);
    public static final WorldPlanWriteResult CONFLICT = new WorldPlanWriteResult(Status.CONFLICT, 0);

    public static WorldPlanWriteResult written(long newVersion) {
        return new WorldPlanWriteResult(Status.WRITTEN, newVersion);
    }

    public boolean isWritten() {
        return status == Status.WRITTEN;
    }
}
