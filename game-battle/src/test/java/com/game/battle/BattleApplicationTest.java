package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.IsolatedDubboModule;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.battle.rpc.BattleRpcServer;
import com.game.battle.testing.StubBattleRoomService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.config.DubboShutdownHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 进程入口的 Dubbo 关停钩子开关（battle-node-spec §7.11 第 4 步）：Dubbo 3.3.6 导出时无条件往 JVM 注册 {@code DubboShutdownHook}，SIGTERM 时
 * 它与 Spring 的关停钩子并行、抢在关准入闸与作废房间之前销毁控制面。入口必须在起 Spring 之前关掉它；这里按真导出路径（{@link BattleRpcServer}）
 * 核对开关真的生效，并留一组对照证明探测方法本身有效。
 */
class BattleApplicationTest {

    private final DefaultEventLoop loop = new DefaultEventLoop((ThreadFactory) r -> new Thread(r, "test-battle-logic"));
    private final ExecutorService replies = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-battle-rpc-reply"));
    private String saved;

    @BeforeEach
    void saveProperty() {
        saved = System.getProperty(BattleApplication.DUBBO_SHUTDOWN_HOOK_IGNORE);
    }

    @AfterEach
    void restoreProperty() {
        if (saved == null) {
            System.clearProperty(BattleApplication.DUBBO_SHUTDOWN_HOOK_IGNORE);
        } else {
            System.setProperty(BattleApplication.DUBBO_SHUTDOWN_HOOK_IGNORE, saved);
        }
        replies.shutdownNow();
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @Test
    void 入口关掉Dubbo自己的JVM关停钩子_导出控制面后不注册_不关则注册() throws Exception {
        // 对照：不关时，导出控制面就往 JVM 注册了 Dubbo 的钩子（探测方法有效）
        System.clearProperty(BattleApplication.DUBBO_SHUTDOWN_HOOK_IGNORE);
        DubboShutdownHook control;
        try (BattleRpcServer server = export()) {
            control = shutdownHook(server);
            assertThat(control.getRegistered()).as("不关时 Dubbo 3.3.6 导出即注册 JVM 钩子").isTrue();
        }
        assertThat(control.getRegistered()).as("销毁模型时 Dubbo 撤掉钩子，测试 JVM 不留残余").isFalse();

        BattleApplication.ignoreDubboShutdownHook();

        assertThat(System.getProperty("dubbo.shutdownHook.listenIgnore")).isEqualTo("true");
        try (BattleRpcServer server = export()) {
            assertThat(shutdownHook(server).getRegistered()).as("关掉之后停机只由 BattleNode 的顺序驱动").isFalse();
        }
    }

    private BattleRpcServer export() throws IOException {
        BattleNodeServiceImpl provider = new BattleNodeServiceImpl(new AdmissionGate(), new EventLoopBattleScheduler(loop),
                new StubBattleRoomService(), replies, 8, new BattleMetrics(new SimpleMeterRegistry()));
        return BattleRpcServer.export(provider, "127.0.0.1", freePort());
    }

    /** 导出所在 Dubbo 应用的部署器持有的 JVM 关停钩子（Dubbo 不公开它，只能反射）。 */
    private static DubboShutdownHook shutdownHook(BattleRpcServer server) throws ReflectiveOperationException {
        Field dubboField = BattleRpcServer.class.getDeclaredField("dubbo");
        dubboField.setAccessible(true);
        IsolatedDubboModule dubbo = (IsolatedDubboModule) dubboField.get(server);
        ApplicationDeployer deployer = dubbo.module().getApplicationModel().getDeployer();
        Field hookField = deployer.getClass().getDeclaredField("dubboShutdownHook");
        hookField.setAccessible(true);
        return (DubboShutdownHook) hookField.get(deployer);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
