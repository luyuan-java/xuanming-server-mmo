package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static com.game.scene.metrics.SceneMetrics.TeleportReason.TEAM_REGROUP;

import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 队友优先级真正驱动客户端创建 / 销毁，而不只是改变内存索引。 */
class TeamViewWorldTest extends MovementFixture {

    @Test
    void 强读刷新满表_给缺边方发47和被驱逐普通实体的64_没有超容量() throws Exception {
        ScenePlayer leader = enterNew(1, 1);
        for (int i = 2; i <= 101; i++) enterNew(i, i);
        ScenePlayer member = enterNew(102, 102);
        assertThat(world.mutuallyVisible(leader, member)).isFalse();
        world.applyTeamMembership(member, 99, 1);
        sink.clear();
        world.applyTeamMembership(leader, 99, 1);
        assertThat(world.mutuallyVisible(leader, member)).isTrue();
        assertThat(leader.scene().watching(leader)).hasSize(100);
        assertThat(member.scene().watching(member)).hasSize(100);
        assertThat(sink.messageIdsTo(LINK, 1)).containsExactly(47, 64);
        assertThat(ActorListCreateS2C.parseFrom(sink.to(LINK, 1).getFirst().getSerializedMessage()).getActorListList())
                .extracting(ActorCreateS2C::getEntity).containsExactly(member.entity());
        assertThat(ActorListDestroyS2C.parseFrom(sink.to(LINK, 1).getLast().getSerializedMessage()).getEntityList())
                .containsExactly(world.playerById(2).entity());
        sink.clear();
        world.applyTeamMembership(leader, 99, 1);
        assertThat(sink.events()).as("重复提示不制造创建销毁抖动").isEmpty();
    }

    @Test
    void 满表归队瞬移_本人130后47_队长先销毁被替换者再21_不再反复失去队长() throws Exception {
        ScenePlayer leader = enterNew(1, 1);
        for (int i = 2; i <= 101; i++) enterNew(i, i);
        ScenePlayer member = enterSaved(102, 102, SPAWN.plus(new Vec3(50, 0, 0)));
        world.applyTeamMembership(leader, 99, 1);
        world.applyTeamMembership(member, 99, 1);
        sink.clear();
        world.teleportWithinScene(member, leader.position(), FACING, TEAM_REGROUP);
        assertThat(sink.messageIdsTo(LINK, 102)).containsExactly(130, 47);
        assertThat(sink.messageIdsTo(LINK, 1)).containsExactly(64, 21);
        assertThat(ActorListDestroyS2C.parseFrom(sink.to(LINK, 1).getFirst().getSerializedMessage()).getEntityList())
                .containsExactly(world.playerById(2).entity());
        assertThat(ActorCreateS2C.parseFrom(sink.to(LINK, 1).getLast().getSerializedMessage()).getEntity())
                .isEqualTo(member.entity());
        assertThat(world.mutuallyVisible(leader, member)).isTrue();
        for (ScenePlayer p : List.of(leader, member)) assertThat(p.scene().watching(p)).hasSize(100);
        world.step();
        assertThat(world.mutuallyVisible(leader, member)).isTrue();
    }
}
