package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.match.precheck.MemberPrecheck;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link MemberPrecheck} 的测试替身：按脚本给预检结论，记下收到的名单。给整队开战 / 活动开战两个入口测「怎么翻译预检结论」用
 * （同一种 {@code Reason} 两个入口的应答不同）。
 *
 * <pre>
 * FakeMemberPrecheck precheck = new FakeMemberPrecheck();          // 缺省：全员通过，每人 zone = 1
 * precheck.zone(1002, 7);                                           // 通过时这个人的 zone
 * precheck.fail(MemberPrecheck.Reason.LOCK_READ_FAILED, 1002);      // 之后一律：查到 1002 时读锁失败
 * precheck.pass();                                                  // 恢复成通过
 * assertThat(precheck.rosters).containsExactly(List.of(1001L, 1002L));
 * </pre>
 */
public final class FakeMemberPrecheck implements MemberPrecheck {

    /** 每次 check 收到的名单，按调用顺序。 */
    public final List<List<Long>> rosters = new CopyOnWriteArrayList<>();
    private final Map<Long, Integer> zones = new LinkedHashMap<>();
    private volatile Result failure;

    /** 通过时这名成员的 zone（缺省 1）。 */
    public synchronized FakeMemberPrecheck zone(long playerId, int zoneId) {
        zones.put(playerId, zoneId);
        return this;
    }

    public FakeMemberPrecheck fail(Reason reason, long offender) {
        this.failure = Result.failed(reason, offender);
        return this;
    }

    public FakeMemberPrecheck pass() {
        this.failure = null;
        return this;
    }

    @Override
    public synchronized Result check(List<Long> roster, Deadline d) {
        rosters.add(List.copyOf(roster));
        Result failed = failure;
        if (failed != null) {
            return failed;
        }
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (Long playerId : roster) {
            out.put(playerId, zones.getOrDefault(playerId, 1));
        }
        return Result.ok(out);
    }
}
