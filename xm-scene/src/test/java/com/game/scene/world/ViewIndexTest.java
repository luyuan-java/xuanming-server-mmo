package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 视野索引：进出视野的判定与双向兴趣列表的一致性（不经过消息层）。 */
class ViewIndexTest {

    private ViewIndex view;
    private ViewChanges changes;

    @BeforeEach
    void setUp() {
        view = new ViewIndex();
        changes = new ViewChanges();
    }

    @Test
    void 进场_视野内双向建立_视野外不建立() {
        ScenePlayer a = player(1, 180, 200, 0);
        ScenePlayer far = player(2, 180, 230, 0);
        view.enter(a);
        view.enter(far);

        ScenePlayer b = player(3, 185, 200, 0);
        ViewIndex.Entered entered = view.enter(b);

        assertThat(entered.seen()).containsExactly(a);
        assertThat(entered.seers()).containsExactly(a);
        assertThat(view.watching(b)).containsExactly(a);
        assertThat(view.watchers(b)).containsExactly(a);
        assertThat(view.watching(a)).containsExactly(b);
        assertThat(view.watchers(far)).isEmpty();
    }

    @Test
    void 恰好10米可见_三维距离含高度() {
        ScenePlayer a = player(1, 180, 200, 0);
        view.enter(a);

        assertThat(view.enter(player(2, 190, 200, 0)).seen()).as("恰好 10 m，含等号").containsExactly(a);
        assertThat(view.enter(player(3, 174, 200, 8.1)).seen()).as("水平 6 m、高差 8.1 m，三维超过 10 m").isEmpty();
        assertThat(view.enter(player(4, 180, 210.000001, 0)).seen()).isEmpty();
    }

    @Test
    void 同一邻域内走近_下次刷新就进视野_双方各记一条() {
        ScenePlayer a = player(1, 180, 200, 0);
        ScenePlayer b = player(2, 180, 215, 0);
        view.enter(a);
        view.enter(b);
        assertThat(view.watching(a)).isEmpty();

        moveTo(b, 180, 205, 0);
        assertThat(view.pendingMoves()).isEqualTo(1);
        view.refresh(changes);

        assertThat(view.watching(a)).containsExactly(b);
        assertThat(view.watching(b)).containsExactly(a);
        assertThat(changes.of(b).added()).containsExactly(a);
        assertThat(changes.of(a).added()).containsExactly(b);
        assertThat(changes.of(a).removed()).isEmpty();
        assertThat(view.pendingMoves()).isZero();
    }

    @Test
    void 滞回_走出10米仍可见_超过20米才出视野_双方各记一条() {
        ScenePlayer a = player(1, 180, 200, 0);
        ScenePlayer b = player(2, 185, 200, 0);
        view.enter(a);
        view.enter(b);

        moveTo(b, 199, 200, 0);
        view.refresh(changes);
        assertThat(changes.isEmpty()).as("19 m：还在滞回带里").isTrue();
        assertThat(view.watching(a)).containsExactly(b);

        moveTo(b, 200, 200, 0);
        view.refresh(changes);
        assertThat(changes.isEmpty()).as("恰好 20 m 不出视野").isTrue();

        moveTo(b, 200.5, 200, 0);
        view.refresh(changes);
        assertThat(changes.of(b).removed()).containsExactly(a);
        assertThat(changes.of(a).removed()).containsExactly(b);
        assertThat(view.watching(a)).isEmpty();
        assertThat(view.watchers(a)).isEmpty();
        assertThat(view.watching(b)).isEmpty();
        assertThat(view.watchers(b)).isEmpty();
    }

    @Test
    void 一大步跨过好几格_旧邻域的人出视野_新邻域的人进视野() {
        ScenePlayer a = player(1, 0, 0, 0);
        ScenePlayer b = player(2, 5, 0, 0);
        ScenePlayer c = player(3, 100, 0, 0);
        view.enter(a);
        view.enter(b);
        view.enter(c);

        moveTo(b, 95, 0, 0);
        view.refresh(changes);

        assertThat(changes.of(b).removed()).containsExactly(a);
        assertThat(changes.of(b).added()).containsExactly(c);
        assertThat(changes.of(a).removed()).containsExactly(b);
        assertThat(changes.of(c).added()).containsExactly(b);
        // doesNotContainAnyElementsOf 遇到空的 removed 会直接抛 IllegalArgumentException（AssertJ 要求参照集非空），改用逐个判断。
        changes.forEach((watcher, delta) ->
                assertThat(delta.added()).as("同一次刷新里不会既加又删").noneMatch(delta.removed()::contains));
    }

    @Test
    void 两次刷新之间多次移动只登记一次_刷新后重新登记() {
        ScenePlayer a = player(1, 0, 0, 0);
        ScenePlayer b = player(2, 30, 0, 0);
        view.enter(a);
        view.enter(b);

        moveTo(b, 25, 0, 0);
        moveTo(b, 15, 0, 0);
        moveTo(b, 8, 0, 0);
        assertThat(view.pendingMoves()).isEqualTo(1);
        view.refresh(changes);
        assertThat(changes.of(a).added()).containsExactly(b);
        assertThat(view.pendingMoves()).isZero();

        moveTo(a, 1, 0, 0);
        assertThat(view.pendingMoves()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 重判节奏：移动中累计 1 m、静止立即

    @Test
    void 静止的人挪一点_下次刷新立即重判() {
        ScenePlayer a = player(1, 180, 200, 0);
        ScenePlayer b = player(2, 180, 210.2, 0);
        view.enter(a);
        view.enter(b);

        moveTo(b, 180, 209.9, 0);
        view.refresh(changes);

        assertThat(view.watching(a)).containsExactly(b);
        assertThat(view.watching(b)).containsExactly(a);
        assertThat(view.pendingMoves()).isZero();
    }

    @Test
    void 移动中累计位移不足1米_推迟重判并留在名单里_够1米再判() {
        ScenePlayer a = player(1, 180, 200, 0);
        ScenePlayer b = player(2, 180, 210.5, 0);
        view.enter(a);
        view.enter(b);
        b.setVelocity(new Vec3(0, -1, 0));

        moveTo(b, 180, 209.9, 0);
        view.refresh(changes);
        assertThat(view.watching(a)).as("已在 10 m 内，但只走了 0.6 m：推迟").isEmpty();
        assertThat(changes.isEmpty()).isTrue();
        assertThat(view.pendingMoves()).isEqualTo(1);

        moveTo(b, 180, 209.5, 0);
        view.refresh(changes);
        assertThat(view.watching(a)).as("累计 1 m").containsExactly(b);
        assertThat(view.watching(b)).containsExactly(a);
        assertThat(changes.of(a).added()).containsExactly(b);
        assertThat(changes.of(b).added()).containsExactly(a);
        assertThat(view.pendingMoves()).isZero();
    }

    @Test
    void 推迟中的人停下_即使位置不再变_下次刷新按停下的位置重判() {
        ScenePlayer a = player(1, 180, 200, 0);
        ScenePlayer b = player(2, 180, 210.3, 0);
        view.enter(a);
        view.enter(b);
        b.setVelocity(new Vec3(0, -1, 0));
        moveTo(b, 180, 209.9, 0);
        view.refresh(changes);
        assertThat(view.watching(a)).isEmpty();

        // 停步 / 挂机停推：速度清零，位置不动。
        b.setVelocity(Vec3.ORIGIN);
        view.refresh(changes);

        assertThat(view.watching(a)).containsExactly(b);
        assertThat(view.watching(b)).containsExactly(a);
        assertThat(view.pendingMoves()).isZero();
    }

    @Test
    void 出视野同样按1米节奏_推迟期间仍在表里_够1米就删_不漏删() {
        ScenePlayer a = player(1, 0, 0, 0);
        ScenePlayer b = player(2, 0, 5, 0);
        view.enter(a);
        view.enter(b);
        moveTo(b, 0, 19.5, 0);
        view.refresh(changes);
        assertThat(view.watching(a)).as("19.5 m 仍在滞回带内").containsExactly(b);
        b.setVelocity(new Vec3(0, 1, 0));

        moveTo(b, 0, 20.4, 0);
        view.refresh(changes);
        assertThat(view.watching(a)).as("超过 20 m，但只走了 0.9 m：推迟").containsExactly(b);

        moveTo(b, 0, 20.6, 0);
        view.refresh(changes);
        assertThat(view.watching(a)).isEmpty();
        assertThat(view.watching(b)).isEmpty();
        assertThat(changes.of(a).removed()).containsExactly(b);
        assertThat(changes.of(b).removed()).containsExactly(a);
    }

    @Test
    void 推迟中的人离开_待刷新名单一起清掉() {
        ScenePlayer a = player(1, 0, 0, 0);
        ScenePlayer b = player(2, 0, 12, 0);
        view.enter(a);
        view.enter(b);
        b.setVelocity(new Vec3(0, -1, 0));
        moveTo(b, 0, 11.5, 0);
        view.refresh(changes);
        assertThat(view.pendingMoves()).isEqualTo(1);

        view.leave(b);
        view.refresh(changes);

        assertThat(view.pendingMoves()).isZero();
        assertThat(changes.isEmpty()).isTrue();
    }

    @Test
    void 没人移动时刷新什么都不做() {
        view.enter(player(1, 0, 0, 0));
        view.enter(player(2, 1, 0, 0));

        view.refresh(changes);

        assertThat(changes.isEmpty()).isTrue();
    }

    @Test
    void 离开_返回看得见它的人_双方的表与格子都清干净() {
        ScenePlayer a = player(1, 0, 0, 0);
        ScenePlayer b = player(2, 1, 0, 0);
        ScenePlayer c = player(3, 2, 0, 0);
        view.enter(a);
        view.enter(b);
        view.enter(c);
        moveTo(b, 1, 1, 0);

        List<ScenePlayer> watchers = view.leave(b);

        assertThat(watchers).containsExactly(a, c);
        assertThat(view.contains(b)).isFalse();
        assertThat(view.watching(a)).containsExactly(c);
        assertThat(view.watchers(a)).containsExactly(c);
        assertThat(view.watching(c)).containsExactly(a);
        assertThat(view.pendingMoves()).as("离开者的待刷新标记一起清掉").isZero();
        assertThat(view.leave(b)).as("幂等").isEmpty();

        // 再进来按首次进场处理。
        assertThat(view.enter(b).seen()).containsExactly(a, c);
    }

    @Test
    void 表满不挤人_关系变成单向_腾出位置后再次移动补上() {
        view = new ViewIndex(1);
        ScenePlayer a = player(1, 0, 0, 0);
        ScenePlayer b = player(2, 1, 0, 0);
        view.enter(a);
        view.enter(b);
        ScenePlayer c = player(3, 2, 0, 0);

        ViewIndex.Entered entered = view.enter(c);

        assertThat(entered.seen()).as("c 的表只能放一个").containsExactly(a);
        assertThat(entered.seers()).as("a、b 的表都已满").isEmpty();
        assertThat(view.watchers(c)).isEmpty();
        assertThat(view.watchers(a)).containsExactlyInAnyOrder(b, c);

        view.leave(b);
        moveTo(c, 2.5, 0, 0);
        view.refresh(changes);

        assertThat(changes.of(a).added()).containsExactly(c);
        assertThat(view.watchers(c)).containsExactly(a);
    }

    @Test
    void 重复进场与未进场的移动是调用方bug() {
        ScenePlayer a = player(1, 0, 0, 0);
        view.enter(a);
        assertThatThrownBy(() -> view.enter(a)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> view.moved(player(9, 0, 0, 0))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 清空后什么都不剩() {
        ScenePlayer a = player(1, 0, 0, 0);
        view.enter(a);
        view.enter(player(2, 1, 0, 0));
        moveTo(a, 2, 0, 0);

        view.clear();

        assertThat(view.size()).isZero();
        assertThat(view.pendingMoves()).isZero();
        assertThat(view.watchers(a)).isEmpty();
    }

    private void moveTo(ScenePlayer player, double x, double y, double z) {
        player.setPosition(new Vec3(x, y, z));
        view.moved(player);
    }

    static ScenePlayer player(long playerId, double x, double y, double z) {
        return new ScenePlayer(playerId, 10_000 + playerId, new SessionKey(1, (int) playerId), 1, 3, 1, "", 1,
                List.of(), new Vec3(x, y, z), 0L);
    }
}
