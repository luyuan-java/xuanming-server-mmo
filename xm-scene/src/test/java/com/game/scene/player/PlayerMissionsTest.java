package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.ActiveMission;
import com.game.player.store.state.MissionState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 玩家任务状态的存档形态：无损往返、确定性顺序（无符号）、重复条目第一个为准、不认识的字段保留。 */
class PlayerMissionsTest {

    private static final UnknownFieldSet UNKNOWN = UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build())
            .build();

    @Test
    void 新号是空的_存档时整段省略() {
        assertThat(PlayerMissions.empty().isPristine()).isTrue();
        assertThat(PlayerMissions.restore(MissionState.getDefaultInstance()).isPristine()).isTrue();
        assertThat(PlayerMissions.restore(MissionState.newBuilder().setUnknownFields(UNKNOWN).build()).isPristine())
                .as("只有不认识的字段也要写回").isFalse();
    }

    @Test
    void 往返无损_表里没有的任务与超过2的31次方的任务号_按无符号升序_重复写出字节一致() {
        MissionState state = MissionState.newBuilder()
                .addActive(ActiveMission.newBuilder().setMissionId(-5).addProgress(-1).setAcceptedAtMs(1L << 62)
                        .setUnknownFields(UNKNOWN))
                .addActive(ActiveMission.newBuilder().setMissionId(7).addProgress(5).setAcceptedAtMs(1_900_000_000_123L))
                .addActive(ActiveMission.newBuilder().setMissionId(999_998).addProgress(123))
                .addCompletedIds(-1).addCompletedIds(11).addCompletedIds(12).addCompletedIds(999_999)
                .addClaimableIds(999_999).addClaimableIds(12)
                .setUnknownFields(UNKNOWN)
                .build();

        PlayerMissions missions = PlayerMissions.restore(state);
        MissionState written = missions.toState();

        assertThat(written.getActiveList()).extracting(ActiveMission::getMissionId).containsExactly(7, 999_998, -5);
        assertThat(written.getCompletedIdsList()).containsExactly(11, 12, 999_999, -1);
        assertThat(written.getClaimableIdsList()).containsExactly(12, 999_999);
        assertThat(written.getUnknownFields()).isEqualTo(UNKNOWN);
        assertThat(written.getActive(2).getUnknownFields()).isEqualTo(UNKNOWN);
        assertThat(written.getActive(2).getProgress(0)).isEqualTo(-1);
        assertThat(missions.active(-5).progress(0)).isEqualTo(0xFFFF_FFFFL);
        assertThat(PlayerMissions.restore(written).toState().toByteArray()).isEqualTo(written.toByteArray());
        assertThat(missions.knownIds()).containsExactly(7, 11, 12, 999_998, 999_999, -5, -1);
    }

    @Test
    void 重复的进行中条目第一个为准() {
        PlayerMissions missions = PlayerMissions.restore(MissionState.newBuilder()
                .addActive(ActiveMission.newBuilder().setMissionId(7).addProgress(3))
                .addActive(ActiveMission.newBuilder().setMissionId(7).addProgress(9).addProgress(9))
                .build());
        assertThat(missions.active(7).slots()).isEqualTo(1);
        assertThat(missions.active(7).progress(0)).isEqualTo(3);
        assertThat(missions.toState().getActiveCount()).isEqualTo(1);
    }

    @Test
    void 接取与完成维护索引_完成腾出类型并注销关注() {
        PlayerMissions missions = PlayerMissions.empty();
        missions.accept(7, 1, 100, 1, 1, Set.of(1));
        missions.accept(14, 2, 100, 3, 1, Set.of(8));
        assertThat(missions.typeOccupied(1, 1)).isTrue();
        assertThat(missions.watchers(1)).containsExactly(7);
        assertThat(missions.watchers(8)).containsExactly(14);
        assertThat(missions.active(14).slots()).isEqualTo(2);

        missions.complete(7, 1, 1, Set.of(1));

        assertThat(missions.typeOccupied(1, 1)).isFalse();
        assertThat(missions.watchers(1)).isEmpty();
        assertThat(missions.isAccepted(7)).isFalse();
        assertThat(missions.isComplete(7)).isTrue();
        assertThat(missions.toState().getActiveList()).extracting(ActiveMission::getMissionId).containsExactly(14);
        missions.clearIndexes();
        assertThat(missions.watchers(8)).isEmpty();
        assertThat(missions.typeOccupied(3, 1)).isFalse();
    }
}
