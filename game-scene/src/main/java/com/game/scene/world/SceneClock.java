package com.game.scene.world;

/**
 * 场景逻辑用到的两种时钟，显式注入以便单测控制时间。
 */
public interface SceneClock {

    /** 单调时钟（纳秒），只用来算间隔：位移校验的额度累积、固定步长累加器。 */
    long nanoTime();

    /** UTC Unix 毫秒：137 {@code MoveAckS2C.server_time_ms}（基线 TimeSystem::NowMilliseconds）。 */
    long epochMillis();

    SceneClock SYSTEM = new SceneClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long epochMillis() {
            return System.currentTimeMillis();
        }
    };
}
