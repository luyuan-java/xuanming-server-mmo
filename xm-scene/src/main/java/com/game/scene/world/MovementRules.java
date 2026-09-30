package com.game.scene.world;

/**
 * 移动相关的常量与纯函数（契约文档 {@code docs/reference/mmorpg-client-contract-movement.md} §4 / §5）。
 */
final class MovementRules {

    /**
     * 客户端速度的信任上限（米/秒，基线 {@code kMaxTrustedClientSpeed}）：三维模长超过它就等比缩放到它。
     * 来历是客户端基础移速 9 m/s 加约 10% 余量。配置表里没有移速列（Class 表的 {@code init_speed} 是回合制出手序），
     * 基线也是常量，所以不从表读。
     */
    static final double MAX_TRUSTED_SPEED = 10.0;
    /** 纠偏阈值（米，基线 {@code kMoveCorrectionEpsilon}）：裁决位置与上报位置的<b>水平</b>距离超过它才给本人发 137。 */
    static final double CORRECTION_EPSILON = 0.5;
    /** 固定步长（秒）：20 FPS。 */
    static final double STEP_SECONDS = 0.05;
    /** 连续这么多帧（30 秒）没有任何客户端消息即视为挂机，停止外推（基线 AfkSystem 600 帧）。 */
    static final long AFK_FRAMES = 600;
    /**
     * 世界坐标范围（米）：上报位置任一分量的绝对值超过它，整条输入丢弃（基线不查）。取 1e7 m（一万公里），
     * 远大于任何地图（UE 默认世界约 ±10 km），正常客户端碰不到。它保证坐标差、水平距离、三维距离的平方都不会溢出：
     * 否则位移截断能从两个有限输入算出 ±Inf / NaN，有限但极大的高度也会让视野距离恒为「看不见」，并被写回、下次进场沿用。
     */
    static final double WORLD_LIMIT = 1e7;

    private MovementRules() {
    }

    /** 三个分量都有限且绝对值不超过 {@link #WORLD_LIMIT}（NaN / ±Inf 一律不在世界内）。 */
    static boolean insideWorld(Vec3 position) {
        return Math.abs(position.x()) <= WORLD_LIMIT && Math.abs(position.y()) <= WORLD_LIMIT
                && Math.abs(position.z()) <= WORLD_LIMIT;
    }

    /**
     * 三维模长超过 {@link #MAX_TRUSTED_SPEED} 时等比缩放到它，方向不变；否则原样返回。{@code velocity} 必须有限。
     * 分量极大（1e154 量级以上）时模长的平方会溢出成 Infinity，直接相除会把速度缩成 0、丢掉方向，
     * 所以先按最大分量归一（各分量落在 [-1, 1]），再缩放到上限。
     */
    static Vec3 clampSpeed(Vec3 velocity) {
        double speed = velocity.length();
        if (speed <= MAX_TRUSTED_SPEED) {
            return velocity;
        }
        double largest = Math.max(Math.abs(velocity.x()), Math.max(Math.abs(velocity.y()), Math.abs(velocity.z())));
        Vec3 normalized = velocity.scaled(1.0 / largest);
        return normalized.scaled(MAX_TRUSTED_SPEED / normalized.length());
    }

    /** 裁决位置是否偏离上报位置到需要给本人发 137 的程度（水平距离严格大于阈值）。 */
    static boolean needsCorrection(Vec3 accepted, Vec3 reported) {
        return accepted.horizontalDistance(reported) > CORRECTION_EPSILON;
    }
}
