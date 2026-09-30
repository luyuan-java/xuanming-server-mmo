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

    private MovementRules() {
    }

    /** 三维模长超过 {@link #MAX_TRUSTED_SPEED} 时等比缩放到它，方向不变；否则原样返回。 */
    static Vec3 clampSpeed(Vec3 velocity) {
        double speed = velocity.length();
        if (speed <= MAX_TRUSTED_SPEED) {
            return velocity;
        }
        return velocity.scaled(MAX_TRUSTED_SPEED / speed);
    }

    /** 裁决位置是否偏离上报位置到需要给本人发 137 的程度（水平距离严格大于阈值）。 */
    static boolean needsCorrection(Vec3 accepted, Vec3 reported) {
        return accepted.horizontalDistance(reported) > CORRECTION_EPSILON;
    }
}
