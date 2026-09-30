package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SceneTickerTest {

    private final AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(50));
    private final AtomicInteger steps = new AtomicInteger();

    @Test
    void 按实际流逝补帧_不足一帧的零头留到下次() {
        SceneTicker ticker = new SceneTicker(steps::incrementAndGet, now::get);

        advance(49);
        ticker.run();
        assertThat(steps).hasValue(0);

        advance(1);
        ticker.run();
        assertThat(steps).hasValue(1);

        advance(130);
        ticker.run();
        assertThat(steps).as("130ms = 2 帧余 30ms").hasValue(3);

        advance(20);
        ticker.run();
        assertThat(steps).as("零头 30 + 20 = 一帧").hasValue(4);
    }

    @Test
    void 每次最多5帧_多出的整帧时间扣掉不补() {
        SceneTicker ticker = new SceneTicker(steps::incrementAndGet, now::get);

        advance(730);
        ticker.run();
        assertThat(steps).hasValue(5);

        ticker.run();
        assertThat(steps).as("没有补跑积压的帧").hasValue(5);

        advance(20);
        ticker.run();
        assertThat(steps).as("730 的零头 30 保留").hasValue(6);
    }

    @Test
    void 卡住很久_累加器夹在1秒以内() {
        SceneTicker ticker = new SceneTicker(steps::incrementAndGet, now::get);

        advance(60_000);
        ticker.run();
        ticker.run();

        assertThat(steps).hasValue(5);
    }

    @Test
    void 帧内异常不外抛_后续帧照跑() {
        SceneTicker ticker = new SceneTicker(() -> {
            if (steps.incrementAndGet() == 1) {
                throw new IllegalStateException("第一帧出错");
            }
        }, now::get);

        advance(100);
        ticker.run();

        assertThat(steps).hasValue(2);
    }

    private void advance(long millis) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
