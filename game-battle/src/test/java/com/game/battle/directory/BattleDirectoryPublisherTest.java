package com.game.battle.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.BattleNodeInfo;
import com.game.battle.directory.BattleDirectoryPublisher.Snapshot;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * battle 节点目录发布（battle-node-spec §7.10、§7.11）：条目字段与 {@code accepting = 准入闸 OPEN 且租约有效}、TTL 15 s、
 * 停止后不再发布并删条目、租约丢失后不发布且只删仍属于本实例的条目、发布失败不抛。
 */
class BattleDirectoryPublisherTest {

    /** 记录型目录。 */
    static final class RecordingDirectory implements BattleDirectoryPublisher.Directory {
        final List<BattleNodeInfo> published = new CopyOnWriteArrayList<>();
        final List<Duration> ttls = new CopyOnWriteArrayList<>();
        volatile int removes;
        volatile BattleNodeInfo current;
        volatile RuntimeException failure;

        @Override
        public void publish(BattleNodeInfo info, Duration ttl) {
            if (failure != null) {
                throw failure;
            }
            published.add(info);
            ttls.add(ttl);
            current = info;
        }

        @Override
        public Optional<BattleNodeInfo> find() {
            return Optional.ofNullable(current);
        }

        @Override
        public void remove() {
            removes++;
            current = null;
        }
    }

    private static final BattleNodeInfo IDENTITY = BattleNodeInfo.newBuilder()
            .setNodeId(3)
            .setInstanceId("inst-a")
            .setRpcHost("10.0.0.1")
            .setRpcPort(21200)
            .setClientHost("10.0.0.1")
            .setClientPort(12000)
            .setTableFingerprint("fp")
            .build();

    private final RecordingDirectory directory = new RecordingDirectory();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(true, true, false, 2, 5));
    private final BattleDirectoryPublisher publisher = new BattleDirectoryPublisher(directory, IDENTITY, snapshot::get);

    @Test
    void 条目带身份与可变部分_accepting是开闸且租约有效_TTL15秒() {
        assertThat(publisher.publishNow()).isTrue();
        snapshot.set(new Snapshot(false, true, false, 1, 0));
        publisher.publishNow();
        snapshot.set(new Snapshot(true, false, false, 0, 0));
        publisher.publishNow();

        BattleNodeInfo first = directory.published.get(0);
        assertThat(first.getNodeId()).isEqualTo(3);
        assertThat(first.getInstanceId()).isEqualTo("inst-a");
        assertThat(first.getRpcHost()).isEqualTo("10.0.0.1");
        assertThat(first.getRpcPort()).isEqualTo(21200);
        assertThat(first.getClientHost()).isEqualTo("10.0.0.1");
        assertThat(first.getClientPort()).isEqualTo(12000);
        assertThat(first.getTableFingerprint()).isEqualTo("fp");
        assertThat(first.getAccepting()).isTrue();
        assertThat(first.getRoomCount()).isEqualTo(2);
        assertThat(first.getConnectionCount()).isEqualTo(5);
        assertThat(directory.published.get(1).getAccepting()).as("准入闸关了").isFalse();
        assertThat(directory.published.get(2).getAccepting()).as("租约暂时无效").isFalse();
        assertThat(directory.ttls).containsOnly(Duration.ofSeconds(15));
        assertThat(BattleDirectoryPublisher.PERIOD).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void 正常停止_删条目_之后不再发布() {
        publisher.publishNow();
        publisher.stop(true);
        publisher.stop(true);

        assertThat(directory.removes).isEqualTo(1);
        assertThat(publisher.publishNow()).isFalse();
        assertThat(directory.published).hasSize(1);
        assertThat(publisher.stopped()).isTrue();
    }

    @Test
    void 租约已丢失_快照里看到就不发布() {
        snapshot.set(new Snapshot(true, false, true, 1, 1));
        assertThat(publisher.publishNow()).isFalse();
        assertThat(directory.published).isEmpty();
    }

    @Test
    void 租约丢失后停止_只删仍属于本实例的条目() {
        publisher.publishNow();
        publisher.stopAfterLeaseLost();
        assertThat(directory.removes).isEqualTo(1);
        assertThat(publisher.publishNow()).isFalse();

        RecordingDirectory taken = new RecordingDirectory();
        taken.current = IDENTITY.toBuilder().setInstanceId("inst-new-holder").build();
        BattleDirectoryPublisher other = new BattleDirectoryPublisher(taken, IDENTITY, snapshot::get);
        other.stopAfterLeaseLost();
        assertThat(taken.removes).as("条目已是同号新实例写的，不删").isZero();
        assertThat(taken.current.getInstanceId()).isEqualTo("inst-new-holder");
    }

    @Test
    void 发布失败只告警_下一轮照常() {
        directory.failure = new IllegalStateException("redis down");
        assertThat(publisher.publishNow()).isFalse();
        directory.failure = null;
        assertThat(publisher.publishNow()).isTrue();
        assertThat(directory.published).hasSize(1);
    }
}
