package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import org.junit.jupiter.api.Test;

class ChannelChecksTest {
    private static SceneChannelInfo channel(long id, int number, SceneChannelState state) {
        return SceneChannelInfo.newBuilder().setSceneId(id).setChannelNo(number).setState(state).build();
    }

    private static SceneChannelDirectory.Builder directory(SceneChannelInfo... entries) {
        var builder = SceneChannelDirectory.newBuilder().setZoneId(2).setSceneConfigId(7).setUpdatedAtMs(100)
                .setMaxPlayersPerChannel(2000).setSwitchEnabled(true).setSwitchCooldownSeconds(10);
        for (var entry : entries) builder.addChannels(entry);
        return builder;
    }

    @Test
    void 高半区无符号场景号和线号仍有效_且校验区地图与当前线() {
        var value = directory(channel(-1, 1, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH),
                channel(Long.MIN_VALUE, -1, SceneChannelState.SCENE_CHANNEL_STATE_BUSY)).build();
        assertThat(ChannelChecks.directory(value, 2, 7, -1)).isEmpty();
        assertThat(ChannelChecks.directory(value, 3, 8, 99)).contains("目录区/地图不匹配", "目录没有当前线路");
    }

    @Test
    void 重号乱序缺号及未知状态不能当作可用目录() {
        var value = directory(channel(9, 2, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH),
                channel(9, 2, SceneChannelState.SCENE_CHANNEL_STATE_BUSY),
                channel(10, 1, SceneChannelState.SCENE_CHANNEL_STATE_CLOSING),
                SceneChannelInfo.newBuilder().setStateValue(99).build()).setUpdatedAtMs(0).setMaxPlayersPerChannel(0).build();
        assertThat(ChannelChecks.directory(value, 2, 7, 9)).contains("场景号为空/重复", "线号为空/重复", "未按无符号线号严格升序",
                "未知线路状态", "缺少发布时间", "人数软上限为0");
    }

    @Test
    void 仅流畅与繁忙可选_回收满员不可用均不可选() {
        for (var state : SceneChannelState.values()) {
            if (state == SceneChannelState.UNRECOGNIZED) continue;
            assertThat(ChannelChecks.selectable(channel(1, 1, state))).as(state.name())
                    .isEqualTo(state == SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH || state == SceneChannelState.SCENE_CHANNEL_STATE_BUSY);
        }
        assertThat(ChannelChecks.selectable(SceneChannelInfo.newBuilder().setStateValue(99).build())).isFalse();
    }

    @Test
    void 存活实例不能重编号_回收后新实例可复用旧号() {
        var first = directory(channel(9, 1, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH),
                channel(10, 2, SceneChannelState.SCENE_CHANNEL_STATE_CLOSING)).build();
        var valid = directory(channel(9, 1, SceneChannelState.SCENE_CHANNEL_STATE_BUSY),
                channel(11, 2, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH)).build();
        assertThat(ChannelChecks.stable(first, valid)).isTrue();
        var wrong = directory(channel(9, 2, SceneChannelState.SCENE_CHANNEL_STATE_BUSY)).build();
        assertThat(ChannelChecks.stable(first, wrong)).isFalse();
    }
}
