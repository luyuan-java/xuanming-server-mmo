package com.game.discovery.zone;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 合服围栏读侧的接口与恒放行实现（zone-travel-spec §3.5）。批次 7.3 按这里钉住的包名、接口名与两个方法的形状接真实现
 * （zone-merge-spec §2.6 的 {@code RedisZoneMergeFence implements ZoneMergeFence}），改名会让那边对不上。
 */
class ZoneMergeFenceTest {

    /** 普通 zone、没有 zone 可判的 0、以及 uint32 的大值（Java 里是负数）。 */
    private static final int[] ZONES = {0, 1, 2, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE, -1};

    @Test
    void 恒放行的同步版对任何zone都回false() throws Exception {
        for (int zone : ZONES) {
            assertThat(ZoneMergeFence.OPEN.inProgress(zone)).as("zone=%s", Integer.toUnsignedString(zone)).isFalse();
        }
    }

    @Test
    void 恒放行的异步版回一个已完成的false() throws Exception {
        for (int zone : ZONES) {
            CompletableFuture<Boolean> result = ZoneMergeFence.OPEN.inProgressAsync(zone).toCompletableFuture();
            // 已完成：调用方在自己的线程上挂回调会当场执行，不依赖别的线程。
            assertThat(result.isDone()).as("zone=%s", Integer.toUnsignedString(zone)).isTrue();
            assertThat(result.get(1, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void 恒放行的异步结果不会被上一个调用方改写() throws Exception {
        // OPEN 是全进程共用的单例：一个调用方把拿到的结果转成 future 后强行改值，不能影响之后的调用。
        CompletionStage<Boolean> first = ZoneMergeFence.OPEN.inProgressAsync(1);
        first.toCompletableFuture().obtrudeValue(Boolean.TRUE);
        first.toCompletableFuture().obtrudeException(new IllegalStateException("调用方改写"));

        assertThat(first.toCompletableFuture().get(1, TimeUnit.SECONDS)).isFalse();
        assertThat(ZoneMergeFence.OPEN.inProgressAsync(1).toCompletableFuture().get(1, TimeUnit.SECONDS)).isFalse();
        assertThat(ZoneMergeFence.OPEN.inProgress(1)).isFalse();
    }

    @Test
    void 给7点3冻结的包名接口名与方法形状() throws Exception {
        assertThat(ZoneMergeFence.class.getName()).isEqualTo("com.game.discovery.zone.ZoneMergeFence");
        assertThat(ZoneMergeFence.class.isInterface()).isTrue();

        // 同步版：读不到用受检异常表达（调用方必须处理，按封锁走），所以签名上声明 Exception。
        Method sync = ZoneMergeFence.class.getMethod("inProgress", int.class);
        assertThat(sync.getReturnType()).isEqualTo(boolean.class);
        assertThat(sync.getExceptionTypes()).containsExactly(Exception.class);
        assertThat(Modifier.isAbstract(sync.getModifiers())).isTrue();

        // 异步版：读不到以异常完成，签名上不声明受检异常；两个方法都要由实现给出（不提供会掩盖阻塞的缺省实现）。
        Method async = ZoneMergeFence.class.getMethod("inProgressAsync", int.class);
        assertThat(async.getReturnType()).isEqualTo(CompletionStage.class);
        assertThat(async.getExceptionTypes()).isEmpty();
        assertThat(Modifier.isAbstract(async.getModifiers())).isTrue();

        assertThat(ZoneMergeFence.class.getField("OPEN").get(null)).isSameAs(ZoneMergeFence.OPEN);
        assertThat(ZoneMergeFence.OPEN).hasToString("ZoneMergeFence.OPEN");
    }
}
