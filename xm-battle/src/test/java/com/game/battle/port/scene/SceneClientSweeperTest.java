package com.game.battle.port.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * battle → scene 直连客户端缓存的清扫（审计 OBX-11；做法同 xm-guild 的 {@code SceneEndpointSweeperTest}）：只登记发过调用的节点；按 zone 读目录；
 * 目录里消失 / 换实例 / 不再提供直连地址的逐个销毁；读失败的 zone 本轮不动；阻塞的目录读只落在自己的后台守护线程上；关闭后不再清扫。
 * 清扫线程与发调用线程之间唯一的并发保护是「登记没被更新过才摘」的条件摘除：用读目录途中的钩子把另一条线程的 {@code track} 插进
 * 「取快照」与「摘除」之间来钉住（评审 R63B-4）。
 */
class SceneClientSweeperTest {

    private final Map<Integer, List<SceneNodeInfo>> directory = new ConcurrentHashMap<>();
    private final List<Integer> zonesRead = new CopyOnWriteArrayList<>();
    private final List<String> readThreads = new CopyOnWriteArrayList<>();
    private final List<SceneAssetEndpoint> evicted = new CopyOnWriteArrayList<>();
    private volatile int brokenZone = -1;
    /** 非 null 时在下一次读目录途中执行一次：清扫这时已经取完登记快照、还没判定——模拟发调用线程恰在这一刻改登记。 */
    private volatile Runnable duringRead;
    private final IntFunction<List<SceneNodeInfo>> reader = zone -> {
        zonesRead.add(zone);
        readThreads.add(Thread.currentThread().getName());
        Runnable hook = duringRead;
        if (hook != null) {
            duringRead = null;
            hook.run();
        }
        if (zone == brokenZone) {
            throw new IllegalStateException("redis down");
        }
        return directory.getOrDefault(zone, List.of());
    };
    private final SceneClientSweeper sweeper = new SceneClientSweeper(reader, evicted::add);

    @AfterEach
    void close() {
        sweeper.close();
    }

    private static SceneNodeInfo info(int zone, int node, String instance, int port) {
        return SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(node).setInstanceId(instance).setRpcHost("10.0.0." + node)
                .setRpcPort(port).build();
    }

    private static SceneAssetEndpoint endpoint(int zone, int node, String instance) {
        return new SceneAssetEndpoint(zone, node, instance, "10.0.0." + node, 21100);
    }

    @Test
    void 不在目录_换了实例_不再提供直连地址的被逐个销毁_还在的留着() {
        sweeper.track(endpoint(1, 1, "a"));
        sweeper.track(endpoint(1, 2, "b"));
        sweeper.track(endpoint(1, 3, "c"));
        sweeper.track(endpoint(1, 4, "d"));
        sweeper.track(endpoint(1, 5, "e"));
        directory.put(1, List.of(info(1, 1, "a", 21100), info(1, 2, "b2", 21100), info(1, 3, "c", 0), info(1, 5, "e", 70_000)));

        assertThat(sweeper.sweep()).isEqualTo(4);

        assertThat(evicted).containsExactlyInAnyOrder(endpoint(1, 2, "b"), endpoint(1, 3, "c"), endpoint(1, 4, "d"), endpoint(1, 5, "e"));
        assertThat(sweeper.trackedCount()).as("节点 1 还在目录里").isEqualTo(1);
        assertThat(zonesRead).as("同一个 zone 一轮只读一次目录").containsExactly(1);

        assertThat(sweeper.sweep()).as("扫过的不再重复销毁").isZero();
        assertThat(evicted).hasSize(4);
    }

    @Test
    void 读目录失败的zone本轮跳过_不误删_别的zone照扫_恢复后下一轮再扫() {
        sweeper.track(endpoint(1, 1, "a"));
        sweeper.track(endpoint(3, 5, "e"));
        sweeper.track(endpoint(3, 6, "f"));
        brokenZone = 3;

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(evicted).containsExactly(endpoint(1, 1, "a"));
        assertThat(sweeper.trackedCount()).as("zone 3 的两个节点原样留着").isEqualTo(2);
        assertThat(zonesRead.stream().filter(z -> z == 3).count()).as("失败的 zone 一轮也只读一次").isEqualTo(1);

        brokenZone = -1;
        directory.put(3, List.of(info(3, 5, "e", 21100)));
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(evicted).containsExactly(endpoint(1, 1, "a"), endpoint(3, 6, "f"));
        assertThat(sweeper.trackedCount()).isEqualTo(1);
    }

    @Test
    void 只登记发过调用的节点_没登记的不读目录不销毁_同地址换实例以新的为准() {
        directory.put(1, List.of(info(1, 1, "a2", 21100)));

        assertThat(sweeper.sweep()).isZero();
        assertThat(zonesRead).as("没有登记就不读目录").isEmpty();

        sweeper.track(endpoint(1, 1, "a"));
        sweeper.track(endpoint(1, 1, "a2"));
        assertThat(sweeper.trackedCount()).as("同地址只记最近一次的实例").isEqualTo(1);
        assertThat(sweeper.sweep()).isZero();
        assertThat(evicted).isEmpty();
    }

    @Test
    void 清扫读目录期间同地址换了实例又发了调用_新登记不摘_旧实例不销毁_留给下一轮() {
        sweeper.track(endpoint(1, 1, "a"));
        // 清扫已按旧登记取了快照；读目录的这一刻，发调用线程对同地址的新实例发了调用（客户端缓存里已是 a2）
        duringRead = () -> sweeper.track(endpoint(1, 1, "a2"));
        directory.put(1, List.of(info(1, 1, "a2", 21100)));

        assertThat(sweeper.sweep()).as("快照里的旧实例 a 不在目录了，但登记已经换成 a2：条件摘除落空").isZero();

        assertThat(evicted).as("按旧实例销毁对客户端缓存是空操作，不该发生").isEmpty();
        assertThat(sweeper.trackedCount()).as("新实例的登记原样留着（摘掉它，a2 的客户端就再也扫不到）").isEqualTo(1);
        assertThat(zonesRead).containsExactly(1);

        assertThat(sweeper.sweep()).as("下一轮：a2 在目录里").isZero();
        assertThat(evicted).isEmpty();
        assertThat(sweeper.trackedCount()).isEqualTo(1);

        directory.put(1, List.of());
        assertThat(sweeper.sweep()).as("留下的登记就是新实例：它从目录消失时照常扫到").isEqualTo(1);
        assertThat(evicted).containsExactly(endpoint(1, 1, "a2"));
        assertThat(sweeper.trackedCount()).isZero();
    }

    @Test
    void 清扫读目录期间同一实例又发了调用_登记没变_节点已不在目录_照常摘除销毁() {
        sweeper.track(endpoint(1, 1, "a"));
        // 同地址同实例重复登记不算「更新过」：节点确实不在目录了，不能因为刚发过调用就一直留着
        duringRead = () -> sweeper.track(endpoint(1, 1, "a"));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(evicted).containsExactly(endpoint(1, 1, "a"));
        assertThat(sweeper.trackedCount()).isZero();
    }

    @Test
    void 清扫只在自己的后台守护线程上跑_不在调用方线程上读目录() {
        sweeper.track(endpoint(1, 4, "d"));

        sweeper.start(Duration.ofMillis(20));

        await().atMost(Duration.ofSeconds(5)).until(() -> !evicted.isEmpty());
        assertThat(evicted).containsExactly(endpoint(1, 4, "d"));
        assertThat(readThreads).isNotEmpty().allSatisfy(name -> assertThat(name).isEqualTo(SceneClientSweeper.THREAD_NAME));
        assertThat(readThreads).doesNotContain(Thread.currentThread().getName());
        Thread sweepThread = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals(SceneClientSweeper.THREAD_NAME) && t.isAlive()).findFirst().orElseThrow();
        assertThat(sweepThread.isDaemon()).as("守护线程，不挡进程退出").isTrue();
        assertThat(sweeper.running()).isTrue();
    }

    @Test
    void 一轮清扫抛出异常_下一轮照常() throws Exception {
        CountDownLatch secondRound = new CountDownLatch(2);
        SceneClientSweeper flaky = new SceneClientSweeper(zone -> List.of(), endpoint -> {
            secondRound.countDown();
            throw new IllegalStateException("销毁出错（测试）");
        });
        try {
            flaky.track(endpoint(1, 1, "a"));
            flaky.start(Duration.ofMillis(20));
            assertThat(secondRound.await(1, TimeUnit.SECONDS)).as("第一轮销毁抛异常后登记已摘，没有第二次").isFalse();
            flaky.track(endpoint(1, 2, "b"));
            assertThat(secondRound.await(5, TimeUnit.SECONDS)).as("周期任务没有因为异常停掉").isTrue();
        } finally {
            flaky.close();
        }
    }

    @Test
    void 关闭后不再清扫_重复关闭无害_关闭后start无效() throws Exception {
        sweeper.start(Duration.ofMillis(20));
        sweeper.track(endpoint(1, 4, "d"));
        await().atMost(Duration.ofSeconds(5)).until(() -> !evicted.isEmpty());

        sweeper.close();
        sweeper.close();
        assertThat(sweeper.running()).isFalse();
        sweeper.track(endpoint(1, 5, "e"));
        int readsAtClose = zonesRead.size();
        Thread.sleep(150);
        assertThat(zonesRead).as("线程已停，不再读目录").hasSize(readsAtClose);
        assertThat(evicted).hasSize(1);

        sweeper.start(Duration.ofMillis(20));
        assertThat(sweeper.running()).as("关闭之后不能再开").isFalse();
    }

    @Test
    void 重复start只有一条清扫线程_间隔必须为正() {
        sweeper.start(Duration.ofMinutes(10));
        sweeper.start(Duration.ofMinutes(10));

        assertThat(sweeper.running()).isTrue();
        assertThatThrownBy(() -> sweeper.start(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SceneClientSweeper.SWEEP_INTERVAL).isEqualTo(Duration.ofSeconds(60));
    }
}
