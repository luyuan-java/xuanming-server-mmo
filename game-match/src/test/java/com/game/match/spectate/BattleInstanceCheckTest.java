package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.match.testing.FakeBattleNodes;
import org.junit.jupiter.api.Test;

/** 「实例换掉了」只认目录里同节点号已是别的实例这一条正面证据；大厅档每名观众都走这里，所以目录读有短缓存。 */
class BattleInstanceCheckTest {

    private final FakeBattleNodes directory = new FakeBattleNodes();
    private long nanos = 1_000;
    private final BattleInstanceCheck check = new BattleInstanceCheck(directory, () -> nanos);

    private boolean replaced() {
        return check.replaced(7, "inst-a", Deadline.after(1000));
    }

    @Test
    void 同节点号已是别的实例才算换了_还是它或查不到都不算() {
        assertThat(replaced()).as("目录里没有：丢了租约、条目刚过期都不代表实例已死").isFalse();

        nanos += BattleInstanceCheck.CACHE_TTL_NANOS;
        directory.add(FakeBattleNodes.node(7, "inst-a", 21200));
        assertThat(replaced()).isFalse();

        nanos += BattleInstanceCheck.CACHE_TTL_NANOS;
        directory.set(FakeBattleNodes.node(7, "inst-b", 21200));
        assertThat(replaced()).isTrue();
    }

    @Test
    void 目录读失败不判() {
        directory.add(FakeBattleNodes.node(7, "inst-b", 21200));
        directory.readFailed = true;

        assertThat(replaced()).isFalse();
    }

    @Test
    void 实例号为空无从比对_不判_也不读目录() {
        directory.add(FakeBattleNodes.node(7, "inst-b", 21200));

        assertThat(check.replaced(7, "", Deadline.after(1000))).isFalse();
        assertThat(directory.lookups).isEmpty();
    }

    @Test
    void 缓存期内不重复读目录_到期重读_不同实例各算各的() {
        directory.add(FakeBattleNodes.node(7, "inst-b", 21200));

        assertThat(replaced()).isTrue();
        nanos += BattleInstanceCheck.CACHE_TTL_NANOS - 1;
        assertThat(replaced()).isTrue();
        assertThat(directory.lookups).as("缓存期内：一次目录读").hasSize(1);

        assertThat(check.replaced(7, "inst-b", Deadline.after(1000))).as("另一个实例是另一条缓存").isFalse();
        assertThat(directory.lookups).hasSize(2);

        nanos += 1;
        assertThat(replaced()).isTrue();
        assertThat(directory.lookups).as("到期重读").hasSize(3);
    }

    @Test
    void 目录读带着调用方的截止_到点不判() {
        directory.add(FakeBattleNodes.node(7, "inst-b", 21200));

        assertThat(check.replaced(7, "inst-a", Deadline.after(0))).as("截止已过：不读目录，不能证明任何事").isFalse();
        assertThat(directory.deadlineLookups).hasSize(1);
    }

    @Test
    void 条目数有上限_到顶整表清掉() {
        directory.add(FakeBattleNodes.node(7, "inst-live", 21200));
        for (int i = 0; i <= BattleInstanceCheck.MAX_ENTRIES; i++) {
            check.replaced(7, "inst-" + i, Deadline.after(1000));
        }
        int reads = directory.lookups.size();

        check.replaced(7, "inst-0", Deadline.after(1000));

        assertThat(directory.lookups).as("最早的那条已随整表清掉：重新读目录").hasSize(reads + 1);
    }
}
