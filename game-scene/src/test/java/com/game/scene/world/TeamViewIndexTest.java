package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static com.game.scene.world.ViewIndexTest.player;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 4.3b D8：固定容量、队友替换、双向索引和异步提示到达顺序。 */
class TeamViewIndexTest {

    @Test
    void 满100个普通实体_队友进入替换最早普通实体_双方仍不超过100() {
        ViewIndex view = new ViewIndex();
        ScenePlayer leader = player(1, 0, 0, 0);
        leader.applyTeamMembership(99, 1);
        List<ScenePlayer> all = new ArrayList<>(List.of(leader));
        view.enter(leader);
        for (int i = 2; i <= 101; i++) {
            ScenePlayer normal = player(i, 1, 0, 0);
            all.add(normal);
            view.enter(normal);
        }
        ScenePlayer teammate = player(102, 1, 0, 0);
        teammate.applyTeamMembership(99, 1);
        all.add(teammate);
        ViewIndex.Entered entered = view.enter(teammate);

        assertThat(view.watching(leader)).hasSize(100).contains(teammate).doesNotContain(all.get(1));
        assertThat(entered.displaced().of(leader).removed()).containsExactly(all.get(1));
        assertThat(entered.seen()).hasSize(100).contains(leader);
        assertMirror(view, all);
    }

    @Test
    void 进场本人先暂收普通实体再遇队友_只返回最终创建集合_不销毁未创建实体() {
        ViewIndex view = new ViewIndex(1);
        ScenePlayer normal = player(1, 0, 0, 0), leader = player(2, 0, 0, 0), member = player(3, 0, 0, 0);
        leader.applyTeamMembership(99, 1);
        member.applyTeamMembership(99, 1);
        view.enter(normal);
        view.enter(leader);
        ViewIndex.Entered entered = view.enter(member);
        assertThat(entered.seen()).containsExactly(leader);
        assertThat(entered.displaced().of(member)).isNull();
        assertThat(entered.displaced().of(leader).removed()).containsExactly(normal);
        assertThat(view.watching(leader)).containsExactly(member);
        assertMirror(view, List.of(normal, leader, member));
    }

    @Test
    void 满表时第二份成员关系到达即补双向边_不需要移动_两种到达顺序一致() {
        for (boolean leaderFirst : List.of(true, false)) {
            ViewIndex view = new ViewIndex(1);
            ScenePlayer normal = player(1, 0, 0, 0), leader = player(2, 0, 0, 0), member = player(3, 0, 0, 0);
            view.enter(normal); view.enter(leader); view.enter(member);
            ScenePlayer first = leaderFirst ? leader : member, second = leaderFirst ? member : leader;
            ViewChanges changes = new ViewChanges();
            first.applyTeamMembership(99, 1);
            view.refreshTeam(first, changes);
            assertThat(view.watching(leader)).doesNotContain(member);
            changes.clear();
            second.applyTeamMembership(99, 1);
            view.refreshTeam(second, changes);
            assertThat(view.watching(leader)).containsExactly(member);
            assertThat(view.watching(member)).containsExactly(leader);
            assertThat(changes.of(leader).removed()).containsExactly(normal);
            assertThat(changes.of(member).removed()).containsExactly(normal);
            assertMirror(view, List.of(normal, leader, member));
        }
    }

    @Test
    void 队友不放宽三维距离_滞回带仍按原规则_离队后普通实体不能挤队友() {
        ViewIndex view = new ViewIndex(1);
        ScenePlayer leader = player(1, 0, 0, 0), member = player(2, 0, 0, 11), normal = player(3, 1, 0, 0);
        leader.applyTeamMembership(99, 1); member.applyTeamMembership(99, 1);
        view.enter(leader); view.enter(member); view.enter(normal);
        assertThat(view.watching(leader)).containsExactly(normal);
        member.setPosition(new Vec3(0, 0, 10)); view.moved(member);
        ViewChanges changes = new ViewChanges(); view.refresh(changes);
        assertThat(view.watching(leader)).containsExactly(member);
        member.setPosition(new Vec3(0, 0, 20)); view.moved(member); view.refresh(changes);
        assertThat(view.watching(leader)).containsExactly(member);
        member.setPosition(new Vec3(0, 0, 20.001)); view.moved(member); view.refresh(changes);
        assertThat(view.watching(leader)).doesNotContain(member);
        assertMirror(view, List.of(leader, member, normal));
    }

    @Test
    void 只能驱逐普通实体_离队降级后才可被新队友替换() {
        ViewIndex view = new ViewIndex(1);
        ScenePlayer a = player(1, 0, 0, 0), b = player(2, 0, 0, 0), c = player(3, 0, 0, 0);
        for (ScenePlayer p : List.of(a, b, c)) p.applyTeamMembership(99, 1);
        view.enter(a); view.enter(b); view.enter(c);
        assertThat(view.watching(a)).containsExactly(b);
        b.applyTeamMembership(0, 2);
        ViewChanges changes = new ViewChanges();
        view.refreshTeam(c, changes);
        assertThat(view.watching(a)).containsExactly(c);
        assertThat(changes.of(a).removed()).containsExactly(b);
        assertMirror(view, List.of(a, b, c));
    }

    @Test
    void 同批新加普通实体又被驱逐_净变化不创建也不销毁它() {
        ScenePlayer a = player(1, 0, 0, 0), b = player(2, 0, 0, 0), c = player(3, 0, 0, 0);
        ViewChanges changes = new ViewChanges();
        changes.added(a, b); changes.removed(a, b); changes.added(a, c);
        assertThat(changes.of(a).added()).containsExactly(c);
        assertThat(changes.of(a).removed()).isEmpty();
        changes.removed(a, c); changes.removed(a, b); changes.added(a, b);
        assertThat(changes.of(a).added()).isEmpty();
        assertThat(changes.of(a).removed()).isEmpty();
    }

    @Test
    void 成员版本无符号单调_旧版本与同版本换队拒绝_重新进场不继承缓存() {
        ScenePlayer p = player(1, 0, 0, 0);
        assertThat(p.applyTeamMembership(99, Long.MAX_VALUE)).isTrue();
        assertThat(p.applyTeamMembership(0, Long.MIN_VALUE)).isTrue();
        assertThat(p.applyTeamMembership(99, Long.MAX_VALUE)).isFalse();
        assertThat(p.applyTeamMembership(99, Long.MIN_VALUE)).isFalse();
        assertThat(p.applyTeamMembership(0, 0)).isFalse();
        assertThat(p.teamId()).isZero();
        ScenePlayer next = player(1, 0, 0, 0);
        assertThat(next.teamMembershipKnown()).isFalse();
        assertThat(next.teamId()).isZero();
    }

    private static void assertMirror(ViewIndex view, List<ScenePlayer> all) {
        for (ScenePlayer watcher : all) {
            assertThat(view.watching(watcher).size()).isLessThanOrEqualTo(100);
            for (ScenePlayer target : all) {
                assertThat(view.watching(watcher).contains(target)).isEqualTo(view.watchers(target).contains(watcher));
            }
        }
    }
}
