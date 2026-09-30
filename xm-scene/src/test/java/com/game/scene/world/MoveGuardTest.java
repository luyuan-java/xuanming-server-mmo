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
            assertThat(guard.admit(at, T0 + ms(250L * i))).isEqualTo(at);
        }
        assertThat(guard.anchor()).isEqualTo(at);
    }

    @Test
    void 网络抖动_包挤在一起到达_用攒下的额度接受() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        guard.admit(new Vec3(0, 0, 0), T0 + ms(5_000));
        // 1 秒的 4 条 MoveSync 在同一时刻到达。
        long burst = T0 + ms(6_000);
        for (int i = 1; i <= 4; i++) {
            Vec3 at = new Vec3(i * 2.25, 0, 0);
            assertThat(guard.admit(at, burst)).isEqualTo(at);
        }
    }

    @Test
    void 瞬移_沿上报方向截断到额度_三个分量同比例_额度清零() {
        MoveGuard guard = new MoveGuard(new Vec3(180, 200, 0), T0);

        Vec3 accepted = guard.admit(new Vec3(180 + 300, 200 + 400, 50), T0);

        // 额度满格 24 m，水平方向 (300,400) 的单位向量 (0.6,0.8)；高度按同比例 24/500。
        assertThat(accepted.x()).isCloseTo(180 + 14.4, within(1e-9));
        assertThat(accepted.y()).isCloseTo(200 + 19.2, within(1e-9));
        assertThat(accepted.z()).isCloseTo(50 * 24.0 / 500, within(1e-9));
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
            accepted = guard.admit(new Vec3(reportedX, 0, 0), T0 + ms(250L * i));
        }
        assertThat(accepted.x()).as("10 秒最多 24 + 12×10 m").isLessThanOrEqualTo(144 + 1e-9);
        assertThat(reportedX - accepted.x()).as("最后一条被截掉 2 m").isCloseTo(2.0, within(1e-9));
    }

    @Test
    void 站着攒额度也最多透支封顶值() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);

        Vec3 accepted = guard.admit(new Vec3(1_000, 0, 0), T0 + ms(600_000));

        assertThat(accepted.x()).isCloseTo(MoveGuard.CAPACITY, within(1e-9));
    }

    @Test
    void 只校验水平位移_高度变化不耗额度() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);

        Vec3 up = new Vec3(0, 0, 500);
        assertThat(guard.admit(up, T0)).isEqualTo(up);
        assertThat(guard.allowance()).isEqualTo(MoveGuard.CAPACITY);
    }

    @Test
    void 服务器落位后以新位置为锚点_额度回满() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        guard.admit(new Vec3(1_000, 0, 0), T0);

        guard.reset(new Vec3(500, 500, 0), T0 + ms(10));
        Vec3 near = new Vec3(510, 500, 0);

        assertThat(guard.admit(near, T0 + ms(10))).isEqualTo(near);
    }

    @Test
    void 时钟不前进时不累积额度() {
        MoveGuard guard = new MoveGuard(new Vec3(0, 0, 0), T0);
        guard.admit(new Vec3(24, 0, 0), T0);

        Vec3 accepted = guard.admit(new Vec3(30, 0, 0), T0 - ms(1_000));

        assertThat(accepted).isEqualTo(new Vec3(24, 0, 0));
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
