package com.game.team.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.view.MemberDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 展示缓存（team-spec §6.7）：资料读 player 表、在线读在线目录、in_battle 恒 false；任何读失败只填零值，从不抛出。 */
class TeamDisplayTest {

    private static final long A = Long.MIN_VALUE + 1;
    private static final long B = 2;

    @Test
    void 资料与在线拼成展示缓存_去零去重() {
        List<List<Long>> asked = new ArrayList<>();
        TeamDisplay display = new TeamDisplay((ids, d) -> {
            asked.add(ids);
            return Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3));
        }, ids -> CompletableFuture.completedFuture(Map.of(B, PlayerPresence.newBuilder().setPlayerId(B).build())));
        Map<Long, MemberDisplay> dc = display.load(List.of(A, 0L, B, A), Deadline.after(1000));
        assertThat(asked).containsExactly(List.of(A, B));
        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(false, false, 31, 2, "甲", "ap", 1)),
                Map.entry(B, new MemberDisplay(true, false, 0, 0, "", "", 0)));
        assertThat(display.load(List.of(0L), Deadline.after(1000))).isEmpty();
    }

    @Test
    void 资料或在线读失败都只填零值() {
        TeamDisplay broken = new TeamDisplay((ids, d) -> {
            throw new IllegalStateException("mysql down");
        }, ids -> CompletableFuture.failedFuture(new IllegalStateException("redis down")));
        assertThat(broken.load(List.of(A), Deadline.after(1000))).containsExactly(Map.entry(A, MemberDisplay.NONE));
        TeamDisplay hang = new TeamDisplay((ids, d) -> Map.of(), ids -> new CompletableFuture<>());
        assertThat(hang.load(List.of(A), Deadline.after(20))).containsExactly(Map.entry(A, MemberDisplay.NONE));
        TeamDisplay throwing = new TeamDisplay((ids, d) -> Map.of(), ids -> {
            throw new IllegalStateException("closed");
        });
        assertThat(throwing.load(List.of(A), Deadline.after(1000))).containsExactly(Map.entry(A, MemberDisplay.NONE));
    }

    @Test
    void 字符串为null时按空串() {
        assertThat(new MemberDisplay(false, false, 0, 0, null, null, 0)).isEqualTo(MemberDisplay.NONE);
    }
}
