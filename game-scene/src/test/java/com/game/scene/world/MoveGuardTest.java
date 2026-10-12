package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MoveGuardTest {

    private static final long T0 = TimeUnit.SECONDS.toNanos(100);

    @Test
    void 取值_速率12米每秒_封顶24米() {
        assertThat(MoveGuard.RATE).isEqualTo(12.0);
        assertThat(MoveGuard.CAPACITY).isEqualTo(24.0);
    }

    @Test
    void 正常行走_每250ms走2米25_一直被接受() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        Vec3 at = new Vec3(0, 0, 0);
        for (int i = 1; i <= 400; i++) {
            at = new Vec3(i * 2.25, 0, 0);
            assertThat(admit(guard, at, T0 + ms(250L * i))).isEqualTo(at);
        }
        assertThat(guard.anchor()).isEqualTo(at);
    }

    @Test
    void 网络抖动_包挤在一起到达_用攒下的额度接受() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        admit(guard, new Vec3(0, 0, 0), T0 + ms(5_000));
        // 1 秒的 4 条 MoveSync 在同一时刻到达。
        long burst = T0 + ms(6_000);
        for (int i = 1; i <= 4; i++) {
            Vec3 at = new Vec3(i * 2.25, 0, 0);
            assertThat(admit(guard, at, burst)).isEqualTo(at);
        }
    }

    @Test
    void 瞬移_水平分量沿上报方向截断到额度_高度取上报值_额度清零() {
        MoveGuard guard = new MoveGuard(new Vec3(180, 200, 0), T0);

        Vec3 accepted = admit(guard, new Vec3(180 + 300, 200 + 400, 50), T0);

        // 额度满格 24 m，水平方向 (300,400) 的单位向量 (0.6,0.8)；高度不校验，原样取上报的 50。
        assertThat(accepted.x()).isCloseTo(180 + 14.4, within(1e-9));
        assertThat(accepted.y()).isCloseTo(200 + 19.2, within(1e-9));
        assertThat(accepted.z()).isEqualTo(50);
        assertThat(guard.allowance()).isZero();
        assertThat(guard.anchor()).isEqualTo(accepted);
    }

    @Test
    void 加速外挂_额度耗尽后每条都被截断() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        double reportedX = 0;
        Vec3 accepted = Vec3.ORIGIN;
        // 以 20 m/s 上报，每 250ms 一条：前几秒吃掉满格额度，之后每条最多走 3 m。
        for (int i = 1; i <= 40; i++) {
            reportedX = accepted.x() + 5;
            accepted = admit(guard, new Vec3(reportedX, 0, 0), T0 + ms(250L * i));
        }
        assertThat(accepted.x()).as("10 秒最多 24 + 12×10 m").isLessThanOrEqualTo(144 + 1e-9);
        assertThat(reportedX - accepted.x()).as("最后一条被截掉 2 m").isCloseTo(2.0, within(1e-9));
    }

    @Test
    void 站着攒额度也最多透支封顶值() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);

        Vec3 accepted = admit(guard, new Vec3(1_000, 0, 0), T0 + ms(600_000));

        assertThat(accepted.x()).isCloseTo(MoveGuard.CAPACITY, within(1e-9));
    }

    @Test
    void 只校验水平位移_高度变化不耗额度() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);

        Vec3 up = new Vec3(0, 0, 500);
        assertThat(admit(guard, up, T0)).isEqualTo(up);
        assertThat(guard.allowance()).isEqualTo(MoveGuard.CAPACITY);
    }

    @Test
    void 服务器落位后以新位置为锚点_额度回满() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        admit(guard, new Vec3(1_000, 0, 0), T0);

        guard.reset(new Vec3(500, 500, 0), T0 + ms(10));
        Vec3 near = new Vec3(510, 500, 0);

        assertThat(admit(guard, near, T0 + ms(10))).isEqualTo(near);
    }

    @Test
    void 时钟不前进时不累积额度() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        admit(guard, new Vec3(24, 0, 0), T0);

        Vec3 accepted = admit(guard, new Vec3(30, 0, 0), T0 - ms(1_000));

        assertThat(accepted).isEqualTo(new Vec3(24, 0, 0));
    }

    // ------------------------------------------------------------------ 极端输入：结果永远有限

    @Test
    void 回归_锚点高度极大时截断_高度取上报值_不会溢出成负无穷或NaN() {
        MoveGuard guard = new MoveGuard(new Vec3(180, 200, 0), T0);
        // 第一条：水平几乎不动、高度 1.7e308，额度内原样接受。
        assertThat(admit(guard, new Vec3(180.5, 200, 1.7e308), T0)).isEqualTo(new Vec3(180.5, 200, 1.7e308));

        // 第二条：水平远 800 m 超额、高度 -1.7e308。旧实现按比例插值高度：1.7e308 + (-1.7e308 - 1.7e308) × f = -Inf。
        Vec3 accepted = admit(guard, new Vec3(980.5, 200, -1.7e308), T0);

        assertThat(accepted.isFinite()).isTrue();
        assertThat(accepted.z()).isEqualTo(-1.7e308);
        assertThat(accepted.x()).isCloseTo(180.5 + MoveGuard.CAPACITY - 0.5, within(1e-9));
        assertThat(guard.anchor()).isEqualTo(accepted);
    }

    @Test
    void 回归_上报坐标大到水平距离溢出_停在锚点的水平位置_结果有限() {
        MoveGuard guard = new MoveGuard(new Vec3(180, 200, 3), T0);

        // hypot(1.5e308, 1.5e308) 溢出成 Infinity，截断比例 = 24 / Inf = 0。
        Vec3 accepted = admit(guard, new Vec3(1.5e308, 1.5e308, 4), T0);

        assertThat(accepted).isEqualTo(new Vec3(180, 200, 4));
        assertThat(accepted.isFinite()).isTrue();
        assertThat(guard.allowance()).isZero();
    }

    @Test
    void 回归_额度为零时同一时刻再次截断_比例为零_不产生NaN() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        admit(guard, new Vec3(1_000, 0, 0), T0);
        assertThat(guard.allowance()).isZero();

        Vec3 accepted = admit(guard, new Vec3(2_000, 0, 7), T0);

        assertThat(accepted).isEqualTo(new Vec3(MoveGuard.CAPACITY, 0, 7));
        assertThat(accepted.isFinite()).isTrue();
    }

    @Test
    void 候选位置不有限_整条拒绝_锚点与额度都不动() {
        Vec3 anchor = new Vec3(-1.7e308, 0, 0);
        MoveGuard guard = new MoveGuard(anchor, T0);

        // 坐标差 3.4e308 溢出成 Infinity，截断比例 0，Inf × 0 = NaN。
        assertThat(guard.admit(new Vec3(1.7e308, 0, 0), T0)).isEmpty();
        assertThat(guard.admit(new Vec3(Double.NaN, 0, 0), T0)).isEmpty();
        assertThat(guard.admit(new Vec3(0, 0, Double.POSITIVE_INFINITY), T0)).isEmpty();

        assertThat(guard.anchor()).isEqualTo(anchor);
        assertThat(guard.allowance()).isEqualTo(MoveGuard.CAPACITY);
    }

    private static Vec3 admit(MoveGuard guard, Vec3 reported, long nowNanos) {
        return guard.admit(reported, nowNanos).orElseThrow();
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
