package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.matcher.GroupPicker.Group;
import com.game.match.matcher.GroupPicker.None;
import com.game.match.matcher.GroupPicker.Pick;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore.QueueSnapshot;
import com.game.match.ticket.TicketStore.SnapshotEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 挑组（match-spec §2.7、§9.5；基线 {@code popGroup} / {@code pickGroup}）：锚点按等待序、候选扫描全部成员（含排在锚点之前的）、
 * 评分模式按分差升序且同分保持等待序、按玩家号去重、最多 32 个有效锚点、退避未到点的票不参与、镜像缺分按票里的评分；
 * 以及<b>成员顺序</b>——锚点在前、其余按选中顺序，它决定站位，客户端可见。
 * 用一份手摆的快照与一个记录型的 {@link GroupPicker.Gate} 驱动，不碰任何存储。
 */
class GroupPickerTest {

    private static final QueueRef QUEUE = new QueueRef(3, 0);
    private static final QueueRef PVE = new QueueRef(5, 1);
    private static final long NOW = 1_800_000_100_000L;

    /** 手摆的队列快照与票据。 */
    private static final class Board {
        final QueueRef queue;
        final List<SnapshotEntry> entries = new ArrayList<>();
        final Map<Long, Ticket> tickets = new HashMap<>();

        Board(QueueRef queue) {
            this.queue = queue;
        }

        /** 一个正常的排队成员：镜像分 = 票里的评分，已等 {@code waitedSeconds} 秒。 */
        Board add(long playerId, long ratingCenti, long waitedSeconds) {
            entry(playerId, ratingCenti);
            return ticket(playerId, queued(queue, playerId, ratingCenti, NOW - waitedSeconds * 1000, 0));
        }

        /** 只放队列项（{@code score} 为 null = 镜像缺分）。 */
        Board entry(long playerId, Long score) {
            entries.add(new SnapshotEntry(Long.toUnsignedString(playerId), playerId, score == null ? OptionalLong.empty() : OptionalLong.of(score)));
            return this;
        }

        Board malformed(String member) {
            entries.add(new SnapshotEntry(member, 0, OptionalLong.empty()));
            return this;
        }

        Board ticket(long playerId, Ticket ticket) {
            tickets.put(playerId, ticket);
            return this;
        }

        GroupPicker picker(int required, boolean rated, Tolerance tolerance, GroupPicker.Gate gate) {
            return new GroupPicker(queue, required, rated, new QueueSnapshot(entries, NOW), tickets, tolerance, gate);
        }
    }

    /** 记下被问到的人；缺省全部放行。 */
    private static final class RecordingGate implements GroupPicker.Gate {
        final List<Long> admitted = new ArrayList<>();
        final List<String> evicted = new ArrayList<>();
        final List<Long> missingScores = new ArrayList<>();
        final Set<Long> refuse = new HashSet<>();
        RuntimeException failure;

        @Override
        public boolean admit(long playerId, Ticket ticket) {
            admitted.add(playerId);
            if (failure != null) {
                throw failure;
            }
            return !refuse.contains(playerId);
        }

        @Override
        public void evict(SnapshotEntry entry) {
            evicted.add(entry.member());
        }

        @Override
        public void missingScore(long playerId) {
            missingScores.add(playerId);
        }
    }

    private final RecordingGate gate = new RecordingGate();

    private static Ticket queued(QueueRef queue, long playerId, long ratingCenti, long enqueuedAtMs, long notBeforeMs) {
        return new Ticket("t-" + playerId, queue.mode(), queue.configId(), TicketState.QUEUED, enqueuedAtMs, 1, queue.queueKey(), ratingCenti, 0, 0,
                notBeforeMs);
    }

    private Pick rated(Board board, int required) {
        return board.picker(required, true, Tolerance.defaults(), gate).pick();
    }

    private static Group group(Pick pick) {
        assertThat(pick).isInstanceOf(Group.class);
        return (Group) pick;
    }

    private static None none(Pick pick) {
        assertThat(pick).isInstanceOf(None.class);
        return (None) pick;
    }

    // ================================================================ 成组与成员顺序

    @Test
    void 两个同分的人按等待序成组_锚点在前_票号按成员顺序() {
        Board board = new Board(QUEUE).add(1001, 150_000, 3).add(1002, 150_000, 1);

        Group group = group(rated(board, 2));

        assertThat(group.members()).containsExactly(1001L, 1002L);
        assertThat(group.tickets()).containsExactly(Map.entry(1001L, "t-1001"), Map.entry(1002L, "t-1002"));
        assertThat(group.anchorWaitSeconds()).isEqualTo(3);
        assertThat(group.toleranceCenti()).isEqualTo(10_000);
        assertThat(group.ratingsCenti()).containsExactly(150_000L, 150_000L);
        assertThat(group.spreadCenti()).isZero();
        assertThat(gate.admitted).containsExactly(1001L, 1002L);
        assertThat(gate.evicted).isEmpty();
    }

    @Test
    void 评分模式按与锚点的分差升序挑_分差相同的保持等待序_选中顺序就是成员顺序() {
        // 锚点 1500 已等 60 s（容差 1000）：候选分差 B=200、C=20、D=20（在 C 之后）、E=10
        Board board = new Board(QUEUE).add(1, 150_000, 60).add(2, 170_000, 0).add(3, 152_000, 0).add(4, 148_000, 0).add(5, 151_000, 0);

        Group four = group(rated(board, 4));

        assertThat(four.members()).as("锚点、然后 E(10)、C(20)、D(20，同分差按快照序排在 C 后)；B(200) 没轮到").containsExactly(1L, 5L, 3L, 4L);
        assertThat(four.tickets().keySet()).containsExactly(1L, 5L, 3L, 4L);
        assertThat(four.ratingsCenti()).containsExactly(150_000L, 151_000L, 152_000L, 148_000L);
        assertThat(four.spreadCenti()).isEqualTo(4_000);
        assertThat(gate.admitted).as("只校验用得上的候选").containsExactly(1L, 5L, 3L, 4L);
    }

    @Test
    void 就近取_分差更大的留下() {
        Board board = new Board(QUEUE).add(11201, 150_000, 60).add(11202, 170_000, 0).add(11203, 152_000, 0);

        assertThat(group(rated(board, 2)).members()).containsExactly(11201L, 11203L);
    }

    @Test
    void 非评分模式不看评分_纯等待序() {
        Board board = new Board(PVE).add(11401, 300_000, 0).add(11402, 100_000, 0).add(11403, 200_000, 0).add(11404, 300_000, 0);

        Group group = group(board.picker(3, false, Tolerance.defaults(), gate).pick());

        assertThat(group.members()).containsExactly(11401L, 11402L, 11403L);
        assertThat(group.toleranceCenti()).isEqualTo(Tolerance.UNBOUNDED);
        assertThat(group.spreadCenti()).isEqualTo(200_000);
    }

    @Test
    void 凑满人数为1时锚点自己成组() {
        Board board = new Board(PVE).add(7, 150_000, 0).add(8, 150_000, 0);

        assertThat(group(board.picker(1, false, Tolerance.defaults(), gate).pick()).members()).containsExactly(7L);
        assertThat(gate.admitted).containsExactly(7L);
    }

    // ================================================================ 锚点

    @Test
    void 队首凑不到候选不阻塞后面的锚点() {
        // A(1500) 与 B / C（1800 / 1820）超出起始容差；B 作锚点与 C 成组，A 继续等
        Board board = new Board(QUEUE).add(11301, 150_000, 0).add(11302, 180_000, 0).add(11303, 182_000, 0);

        assertThat(group(rated(board, 2)).members()).containsExactly(11302L, 11303L);
    }

    @Test
    void 候选扫描全部成员_排在锚点之前的也算() {
        // 回队首之后队列序 ≠ 入队序：队首 A 才等 0 s（容差 100），第二位 B 已等 60 s（容差 1000），两人差 300。
        // A 作锚点凑不到；B 作锚点时把排在它<b>前面</b>的 A 挑进来。「候选取锚点之后」的写法这里凑不出组
        Board board = new Board(QUEUE).add(1, 150_000, 0).add(2, 180_000, 60);

        Group group = group(rated(board, 2));

        assertThat(group.members()).as("锚点 B 在前，A 是候选").containsExactly(2L, 1L);
        assertThat(group.anchorWaitSeconds()).isEqualTo(60);
        assertThat(group.toleranceCenti()).isEqualTo(100_000);
    }

    @Test
    void 锚点的评分取票里的_候选的评分取镜像里的() {
        // A：票 1500、镜像被改成 1900；B：票与镜像都是 1520。A 作锚点按票里的 1500 比，B 在容差内
        Board anchorUsesTicket = new Board(QUEUE).entry(1, 190_000L).ticket(1, queued(QUEUE, 1, 150_000, NOW, 0)).add(2, 152_000, 0);
        assertThat(group(rated(anchorUsesTicket, 2)).members()).containsExactly(1L, 2L);

        // 反过来：B 的镜像是 1900、票是 1500。A 作锚点时按镜像把 B 滤掉；B 作锚点时按自己票里的 1500 比，A 的镜像 1500 在容差内
        RecordingGate second = new RecordingGate();
        Board candidateUsesMirror = new Board(QUEUE).add(1, 150_000, 0).entry(2, 190_000L).ticket(2, queued(QUEUE, 2, 150_000, NOW, 0));
        Pick pick = candidateUsesMirror.picker(2, true, Tolerance.defaults(), second).pick();
        assertThat(group(pick).members()).containsExactly(2L, 1L);
    }

    @Test
    void 分差超过曲线上限的两人_等满兜底秒数后成组() {
        Board waiting = new Board(QUEUE).add(16001, 150_000, 89).add(16002, 260_000, 89);
        assertThat(rated(waiting, 2)).isInstanceOf(None.class);

        Board bottomedOut = new Board(QUEUE).add(16001, 150_000, 90).add(16002, 260_000, 0);
        Group group = group(bottomedOut.picker(2, true, Tolerance.defaults(), new RecordingGate()).pick());
        assertThat(group.members()).containsExactly(16001L, 16002L);
        assertThat(group.toleranceCenti()).isEqualTo(Tolerance.UNBOUNDED);
        assertThat(group.spreadCenti()).isEqualTo(110_000);
    }

    @Test
    void 入队时刻晚于快照时刻时等待按0() {
        Board board = new Board(QUEUE).entry(1, 150_000L).ticket(1, queued(QUEUE, 1, 150_000, NOW + 60_000, 0)).add(2, 150_000, 0);

        Group group = group(rated(board, 2));

        assertThat(group.anchorWaitSeconds()).isZero();
        assertThat(group.toleranceCenti()).isEqualTo(10_000);
        assertThat(GroupPicker.waitSeconds(NOW, NOW - 4_999)).as("不足一秒的部分舍去").isEqualTo(4);
        assertThat(GroupPicker.waitSeconds(NOW, 0)).as("没有入队时刻的票按 0").isZero();
    }

    @Test
    void 一次最多试32个有效锚点_无效的不计次数() {
        // 前 3 个没有票（无效，不计次数）；之后 40 个有效成员两两相差 2000 分，谁也配不上谁
        Board board = new Board(QUEUE).entry(1, 5_000_000L).entry(2, 5_200_000L).entry(3, 5_400_000L);
        for (int i = 0; i < 40; i++) {
            board.add(2000 + i, 100_000 + i * 200_000L, 0);
        }

        None none = none(rated(board, 2));

        List<Long> firstThirtyTwo = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            firstThirtyTwo.add(2000L + i);
        }
        assertThat(gate.admitted).as("恰好 32 个有效锚点被校验").containsExactlyElementsOf(firstThirtyTwo);
        assertThat(gate.evicted).containsExactly("1", "2", "3");
        assertThat(none.starvedWaitSeconds()).isZero();
        assertThat(none.saturated()).isNull();
    }

    @Test
    void 第32个有效锚点还能成组_第33个轮不到() {
        // 第 32、33 个有效成员（下标 31、32）同分：第 32 个作锚点成组
        Board within = new Board(QUEUE);
        for (int i = 0; i < 40; i++) {
            within.add(3000 + i, i == 32 ? 100_000 + 31 * 200_000L : 100_000 + i * 200_000L, 0);
        }
        assertThat(group(rated(within, 2)).members()).containsExactly(3031L, 3032L);

        // 同分的一对挪到第 33、34 个（下标 32、33）：32 个锚点用完也试不到他们
        Board beyond = new Board(QUEUE);
        for (int i = 0; i < 40; i++) {
            beyond.add(3000 + i, i == 33 ? 100_000 + 32 * 200_000L : 100_000 + i * 200_000L, 0);
        }
        assertThat(beyond.picker(2, true, Tolerance.defaults(), new RecordingGate()).pick()).isInstanceOf(None.class);
    }

    // ================================================================ 去重、退避、缺分

    @Test
    void 同一玩家在队列里有两份_组内只取一份() {
        Board anchorTwice = new Board(QUEUE).add(8501, 150_000, 0).entry(8501, 150_000L).add(8502, 150_000, 0);
        Group group = group(rated(anchorTwice, 2));
        assertThat(group.members()).containsExactly(8501L, 8502L);
        assertThat(group.tickets()).hasSize(2);
        assertThat(gate.admitted).as("同一个人只校验一次").containsExactly(8501L, 8502L);

        // 候选重复：B 出现两次，凑 3 人时不能把 B 算两个
        Board candidateTwice = new Board(PVE).add(1, 150_000, 0).add(2, 150_000, 0).entry(2, 150_000L).add(3, 150_000, 0);
        assertThat(group(candidateTwice.picker(3, false, Tolerance.defaults(), new RecordingGate()).pick()).members()).containsExactly(1L, 2L, 3L);

        // 只有两个人（其中一个两份）凑不出 3 人
        Board short3 = new Board(PVE).add(1, 150_000, 0).entry(1, 150_000L).add(2, 150_000, 0);
        assertThat(short3.picker(3, false, Tolerance.defaults(), new RecordingGate()).pick()).isInstanceOf(None.class);
    }

    @Test
    void 退避未到点的票既不作锚点也不作候选_原位保留() {
        Board board = new Board(QUEUE).entry(1, 150_000L).ticket(1, queued(QUEUE, 1, 150_000, NOW - 30_000, NOW + 2_000))
                .add(2, 150_000, 0).add(3, 150_000, 0);

        Group group = group(rated(board, 2));

        assertThat(group.members()).as("队首在退避，第二位作锚点").containsExactly(2L, 3L);
        assertThat(gate.admitted).as("退避中的人不做外部检查").containsExactly(2L, 3L);
        assertThat(gate.evicted).as("也不摘出队列").isEmpty();

        // 只剩他和另一个人时凑不出组
        Board two = new Board(QUEUE).entry(1, 150_000L).ticket(1, queued(QUEUE, 1, 150_000, NOW - 30_000, NOW + 1)).add(2, 150_000, 0);
        None none = none(two.picker(2, true, Tolerance.defaults(), new RecordingGate()).pick());
        assertThat(none.starvedWaitSeconds()).as("锚点 2 凑不到候选").isZero();
    }

    @Test
    void 退避到点的那一刻起重新参与() {
        Board board = new Board(QUEUE).entry(1, 150_000L).ticket(1, queued(QUEUE, 1, 150_000, NOW - 30_000, NOW)).add(2, 150_000, 0);

        Group group = group(rated(board, 2));

        assertThat(group.members()).containsExactly(1L, 2L);
        assertThat(group.anchorWaitSeconds()).as("回队首不重置入队时刻").isEqualTo(30);
    }

    @Test
    void 镜像缺分的成员按票里的评分用_并报一次缺分() {
        Board board = new Board(QUEUE).add(1, 150_000, 0).entry(2, null).ticket(2, queued(QUEUE, 2, 153_000, NOW, 0));

        Group group = group(rated(board, 2));

        assertThat(group.members()).containsExactly(1L, 2L);
        assertThat(group.ratingsCenti()).containsExactly(150_000L, 153_000L);
        assertThat(gate.missingScores).containsExactly(2L);
    }

    @Test
    void 镜像缺分且票里的评分超出容差_不当候选() {
        Board board = new Board(QUEUE).add(1, 150_000, 0).entry(2, null).ticket(2, queued(QUEUE, 2, 190_000, NOW, 0));

        None none = none(rated(board, 2));

        assertThat(none.starvedWaitSeconds()).isZero();
        assertThat(gate.admitted).as("A 作锚点时 B 已按票里的分滤掉；B 自己作锚点时才被校验").containsExactly(1L, 2L);
        assertThat(gate.missingScores).containsExactly(2L);
    }

    @Test
    void 镜像缺分又没有票的残项_轮到时被摘掉_不挡后面的候选() {
        Board board = new Board(QUEUE).add(1, 150_000, 0).entry(999, null).add(2, 150_000, 0);

        Group group = group(rated(board, 2));

        assertThat(group.members()).containsExactly(1L, 2L);
        assertThat(gate.evicted).containsExactly("999");
        assertThat(gate.admitted).containsExactly(1L, 2L);
    }

    // ================================================================ 无效成员与外部检查

    @Test
    void 票据缺失_不是queued_属于别的队列_状态不认识的成员各摘一次_不进组() {
        QueueRef other = new QueueRef(3, 9);
        Board board = new Board(QUEUE)
                .entry(1, 150_000L)
                .entry(2, 150_000L).ticket(2, new Ticket("t-2", 3, 0, TicketState.MATCHED, NOW, 1, QUEUE.queueKey(), 150_000, 0, 0, 0))
                .entry(3, 150_000L).ticket(3, queued(other, 3, 150_000, NOW, 0))
                .entry(4, 150_000L).ticket(4, new Ticket("t-4", 3, 0, TicketState.UNKNOWN, NOW, 1, QUEUE.queueKey(), 150_000, 0, 0, 0))
                .entry(5, 150_000L).ticket(5, new Ticket("t-5", 3, 0, TicketState.READY, NOW, 1, QUEUE.queueKey(), 150_000, 0, 77, 0))
                .add(6, 150_000, 0).add(7, 150_000, 0);

        Group group = group(rated(board, 2));

        assertThat(group.members()).containsExactly(6L, 7L);
        assertThat(gate.evicted).as("每个无效成员恰好摘一次").containsExactly("1", "2", "3", "4", "5");
        assertThat(gate.admitted).as("票据无效的人不做外部检查").containsExactly(6L, 7L);
    }

    @Test
    void 非法成员串轮到当锚点时摘一次_不当候选() {
        Board board = new Board(QUEUE).malformed("abc").malformed("abc").add(1, 150_000, 0).malformed("0").add(2, 150_000, 0);

        Group group = group(rated(board, 2));

        assertThat(group.members()).containsExactly(1L, 2L);
        assertThat(gate.evicted).as("同一个非法串只摘一次；排在锚点之后的 \"0\" 这次没轮到").containsExactly("abc");
    }

    @Test
    void 外部检查不放行的人不进组_同一个人只问一次() {
        gate.refuse.add(2L);
        Board board = new Board(PVE).add(1, 150_000, 0).add(2, 150_000, 0).add(3, 150_000, 0);

        Pick pick = board.picker(3, false, Tolerance.defaults(), gate).pick();

        assertThat(pick).as("三个人里一个不可用，凑不满 3 人").isInstanceOf(None.class);
        assertThat(gate.admitted).as("B 先后是 A、C 的候选，也轮到过当锚点，但只问了一次").containsExactly(1L, 2L, 3L);
        assertThat(gate.evicted).isEmpty();
    }

    @Test
    void 外部检查不放行的候选被跳过_后面的人补上() {
        gate.refuse.add(2L);
        Board board = new Board(QUEUE).add(1, 150_000, 0).add(2, 150_000, 0).add(3, 150_000, 0);

        assertThat(group(rated(board, 2)).members()).containsExactly(1L, 3L);
    }

    @Test
    void 外部检查抛出的异常原样穿出_不吞成没人() {
        gate.failure = new Deadline.DependencyException("读战斗锁失败");
        Board board = new Board(QUEUE).add(1, 150_000, 0).add(2, 150_000, 0);

        assertThatThrownBy(() -> rated(board, 2)).isSameAs(gate.failure);
    }

    // ================================================================ 饥饿观测

    @Test
    void 没成组时报最久的饥饿等待_曲线到顶仍凑不到的锚点另报() {
        // 分差 1100 > 曲线上限 1000：A 已等 60 s（曲线已到顶）、B 等了 50 s（也到顶，但没 A 久）
        Board board = new Board(QUEUE).add(16001, 150_000, 60).add(16002, 260_000, 50);

        None none = none(rated(board, 2));

        assertThat(none.starvedWaitSeconds()).isEqualTo(60);
        assertThat(none.saturated()).isEqualTo(new GroupPicker.Starved(16001, 60, 100_000));
    }

    @Test
    void 曲线没到顶的饥饿只报秒数() {
        Board board = new Board(QUEUE).add(1, 150_000, 44).add(2, 260_000, 10);

        None none = none(rated(board, 2));

        assertThat(none.starvedWaitSeconds()).isEqualTo(44);
        assertThat(none.saturated()).as("44 s 时曲线还差一档到顶").isNull();
    }

    @Test
    void 非评分模式人数不够_只报秒数() {
        Board board = new Board(PVE).add(1, 150_000, 120).add(2, 150_000, 7);

        None none = none(board.picker(3, false, Tolerance.defaults(), gate).pick());

        assertThat(none.starvedWaitSeconds()).isEqualTo(120);
        assertThat(none.saturated()).isNull();
    }

    @Test
    void 一个有效锚点都没有时饥饿秒数是负1() {
        Board empty = new Board(QUEUE);
        assertThat(none(rated(empty, 2)).starvedWaitSeconds()).isEqualTo(-1);

        Board allInvalid = new Board(QUEUE).entry(1, 150_000L).malformed("x");
        None none = none(allInvalid.picker(2, true, Tolerance.defaults(), new RecordingGate()).pick());
        assertThat(none.starvedWaitSeconds()).isEqualTo(-1);
        assertThat(none.saturated()).isNull();
    }

    @Test
    void 凑满人数必须至少1() {
        Board board = new Board(QUEUE).add(1, 150_000, 0);

        assertThatThrownBy(() -> board.picker(0, true, Tolerance.defaults(), gate)).isInstanceOf(IllegalArgumentException.class);
    }
}
