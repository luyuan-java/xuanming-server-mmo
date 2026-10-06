package com.game.match.precheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.port.PlayerStatusReader;
import com.game.match.precheck.MemberPrecheck.Reason;
import com.game.match.precheck.MemberPrecheck.Result;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FakeTicketHealing;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 成员预检（match-spec §7.4）：每人固定「在线 → 战斗锁 → 位置 → 票据」四项、按名单顺序交错着查、首个不满足即返回；十种结论各一例；
 * 截止检查的两个位置；只查不翻译（故障类结论也带上查到谁时出的错）。玩家状态与票据判定都是替身，读的次序由替身记下。
 */
class MemberPrecheckTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final long C = 1003;

    private final FakePlayerStatus players = new FakePlayerStatus().online(A, 1, 7).online(B, 2, 8).online(C, 1, 9);
    private final FakeTicketHealing healing = new FakeTicketHealing();
    private final DefaultMemberPrecheck precheck = new DefaultMemberPrecheck(players, healing);

    private static Deadline d() {
        return Deadline.after(3_000);
    }

    @Test
    void 全员通过_返回每人位置记录里的zone_按名单顺序_每人四项按固定顺序查() {
        Result result = precheck.check(List.of(B, A, C), d());

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isEqualTo(Reason.OK);
        assertThat(result.offender()).isZero();
        assertThat(result.zones()).containsExactly(entry(B, 2), entry(A, 1), entry(C, 1));
        assertThat(players.reads).containsExactly(
                "presence:1002", "lock:1002", "location:1002",
                "presence:1001", "lock:1001", "location:1001",
                "presence:1003", "lock:1003", "location:1003");
        assertThat(healing.calls).containsExactly(B, A, C);
    }

    @Test
    void 不在线_OFFLINE_后面的项与后面的人都不再查() {
        players.disconnected(A);

        Result result = precheck.check(List.of(A, B), d());

        assertThat(result).isEqualTo(Result.failed(Reason.OFFLINE, A));
        assertThat(result.zones()).isEmpty();
        assertThat(players.reads).containsExactly("presence:1001");
        assertThat(healing.calls).isEmpty();
    }

    @Test
    void 读在线目录出错_PRESENCE_READ_FAILED_带上查到谁时出的错() {
        players.failPresence(B);

        Result result = precheck.check(List.of(A, B, C), d());

        assertThat(result).isEqualTo(Result.failed(Reason.PRESENCE_READ_FAILED, B));
        assertThat(result.reason().fault()).isTrue();
        assertThat(healing.calls).as("B 之前的 A 已经查完四项").containsExactly(A);
        assertThat(players.reads).doesNotContain("presence:1003");
    }

    @Test
    void 有战斗锁_IN_BATTLE_读锁出错是LOCK_READ_FAILED_都不去碰票据() {
        players.inBattle(A, true);
        assertThat(precheck.check(List.of(A, B), d())).isEqualTo(Result.failed(Reason.IN_BATTLE, A));

        players.inBattle(A, false).failLock(A);
        assertThat(precheck.check(List.of(A, B), d())).isEqualTo(Result.failed(Reason.LOCK_READ_FAILED, A));

        assertThat(players.reads).containsExactly("presence:1001", "lock:1001", "presence:1001", "lock:1001");
        assertThat(healing.calls).as("票据的 ready 自愈以「没有战斗锁」为前提：锁没确认就不许调").isEmpty();
    }

    @Test
    void 位置不是在线状态_或节点号为0_NO_LOCATION() {
        players.location(A, LocationStatus.RECONNECT_LEASE);
        assertThat(precheck.check(List.of(A), d())).as("重连租约中").isEqualTo(Result.failed(Reason.NO_LOCATION, A));

        players.location(A, LocationStatus.LOGGED_OUT);
        assertThat(precheck.check(List.of(A), d())).as("登出墓碑").isEqualTo(Result.failed(Reason.NO_LOCATION, A));

        players.location(A, LocationStatus.MISSING);
        assertThat(precheck.check(List.of(A), d())).as("没有记录").isEqualTo(Result.failed(Reason.NO_LOCATION, A));

        players.location(A, 1, 0);
        assertThat(precheck.check(List.of(A), d())).as("在线但节点号为 0：此刻没有节点持有他").isEqualTo(Result.failed(Reason.NO_LOCATION, A));

        assertThat(healing.calls).isEmpty();
    }

    @Test
    void 读位置出错_LOCATION_READ_FAILED() {
        players.failLocation(B);

        assertThat(precheck.check(List.of(A, B), d())).isEqualTo(Result.failed(Reason.LOCATION_READ_FAILED, B));
        assertThat(healing.calls).containsExactly(A);
    }

    @Test
    void 票据仍在途_TICKET_IN_FLIGHT_读票或自愈出错_TICKET_READ_FAILED() {
        healing.inFlight(B, "t-1002");
        assertThat(precheck.check(List.of(A, B, C), d())).isEqualTo(Result.failed(Reason.TICKET_IN_FLIGHT, B));
        assertThat(healing.calls).containsExactly(A, B);

        healing.free(B).failFor(B);
        assertThat(precheck.check(List.of(A, B, C), d())).isEqualTo(Result.failed(Reason.TICKET_READ_FAILED, B));
        assertThat(players.reads).doesNotContain("presence:1003");
    }

    @Test
    void 交错顺序_成员1有在途票_成员2离线_结论是成员1() {
        healing.inFlight(A, "t-1001");
        players.disconnected(B);

        Result result = precheck.check(List.of(A, B), d());

        assertThat(result).as("四项按人交错着查，不是先把全员的在线查完").isEqualTo(Result.failed(Reason.TICKET_IN_FLIGHT, A));
        assertThat(players.reads).as("成员 2 根本没被查").containsExactly("presence:1001", "lock:1001", "location:1001");
    }

    @Test
    void 同一个人多项不满足_按固定顺序取第一项() {
        players.inBattle(A, true).location(A, LocationStatus.MISSING);
        healing.inFlight(A, "t-1001");
        assertThat(precheck.check(List.of(A), d()).reason()).as("锁先于位置先于票据").isEqualTo(Reason.IN_BATTLE);

        players.inBattle(A, false);
        assertThat(precheck.check(List.of(A), d()).reason()).isEqualTo(Reason.NO_LOCATION);

        players.location(A, 1, 7);
        assertThat(precheck.check(List.of(A), d()).reason()).isEqualTo(Reason.TICKET_IN_FLIGHT);

        players.disconnected(A);
        assertThat(precheck.check(List.of(A), d()).reason()).as("在线最先").isEqualTo(Reason.OFFLINE);
    }

    @Test
    void 请求预算一开始就用完_DEADLINE_EXPIRED_什么都不读() {
        Result result = precheck.check(List.of(A, B), Deadline.after(0));

        assertThat(result).isEqualTo(Result.failed(Reason.DEADLINE_EXPIRED, A));
        assertThat(result.reason().fault()).isTrue();
        assertThat(players.reads).isEmpty();
        assertThat(healing.calls).isEmpty();
    }

    @Test
    void 预算在读票据之前用完_不做有副作用的自愈() {
        // 读 B 的位置时把预算耗光（读本身成功）：随后不许再去读 / 自愈票据
        PlayerStatusReader slow = new PlayerStatusReader() {
            @Override
            public boolean inBattle(long playerId, Deadline d) {
                return players.inBattle(playerId, d);
            }

            @Override
            public Optional<PlayerPresence> presence(long playerId, Deadline d) {
                return players.presence(playerId, d);
            }

            @Override
            public HolderRead location(long playerId, Deadline d) {
                HolderRead read = players.location(playerId, d);
                if (playerId == B) {
                    while (!d.expired()) {
                        sleep(5);
                    }
                }
                return read;
            }
        };
        DefaultMemberPrecheck checked = new DefaultMemberPrecheck(slow, healing);

        Result result = checked.check(List.of(A, B, C), Deadline.after(400));

        assertThat(result).isEqualTo(Result.failed(Reason.DEADLINE_EXPIRED, B));
        assertThat(healing.calls).as("A 查完了；B 的票据没有被碰").containsExactly(A);
        assertThat(players.reads).doesNotContain("presence:1003");
    }

    @Test
    void 空名单_直接通过_不读任何东西() {
        Result result = precheck.check(List.of(), d());

        assertThat(result.passed()).isTrue();
        assertThat(result.zones()).isEmpty();
        assertThat(players.reads).isEmpty();
    }

    private static java.util.Map.Entry<Long, Integer> entry(long playerId, int zone) {
        return java.util.Map.entry(playerId, zone);
    }

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
