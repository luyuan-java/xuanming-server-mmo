package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleRouting;
import com.game.proto.GetSpectateChatSettingRequest;
import com.game.proto.GetSpectateChatSettingResponse;
import com.game.proto.MessageContent;
import com.game.proto.SetSpectateChatSettingRequest;
import com.game.proto.SetSpectateChatSettingResponse;
import com.game.proto.eSpectateChatPreference;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.BattleSnapshots;
import com.game.scene.testing.Contracts;
import com.game.table.CommonErrorTip;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 589/590 个人局外人观战交流偏好读写、存档往返与战斗快照透传回归测试。 */
class SpectateChatSettingTest {

    @Test
    void 默认允许_写入禁止后持久化并透传至战斗快照_非法枚举拒绝() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 4001L);
        f.sink.clear();

        // 589 默认未设置 -> effective_allow=true
        f.request(player, Contracts.IDS.getSpectateChatSetting(), GetSpectateChatSettingRequest.getDefaultInstance());
        MessageContent getReply = f.pushes(player, Contracts.IDS.getSpectateChatSetting()).getLast();
        GetSpectateChatSettingResponse initial = GetSpectateChatSettingResponse.parseFrom(getReply.getSerializedMessage());
        assertThat(initial.getErrorMessage().getId()).isZero();
        assertThat(initial.getPreference()).isEqualTo(eSpectateChatPreference.SPECTATE_CHAT_PREF_UNSPECIFIED);
        assertThat(initial.getEffectiveAllow()).isTrue();
        f.sink.clear();

        // 590 写入禁止
        f.request(player, Contracts.IDS.setSpectateChatSetting(), SetSpectateChatSettingRequest.newBuilder()
                .setPreference(eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID).build());
        SetSpectateChatSettingResponse setResp = SetSpectateChatSettingResponse.parseFrom(
                f.pushes(player, Contracts.IDS.setSpectateChatSetting()).getLast().getSerializedMessage());
        assertThat(setResp.getErrorMessage().getId()).isZero();
        assertThat(setResp.getPreference()).isEqualTo(eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID);
        assertThat(setResp.getEffectiveAllow()).isFalse();
        assertThat(player.persistentState().getSpectateChatPref())
                .isEqualTo(eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID_VALUE);

        // 战斗快照透传
        var snap = BattleSnapshots.build(player, f.tables, List.of(), BattleRouting.getDefaultInstance());
        assertThat(snap.getSpectateChatPref()).isEqualTo(eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID);
        f.sink.clear();

        // 590 非法枚举 -> kInvalidParameter
        f.request(player, Contracts.IDS.setSpectateChatSetting(), SetSpectateChatSettingRequest.newBuilder()
                .setPreferenceValue(99).build());
        SetSpectateChatSettingResponse badResp = SetSpectateChatSettingResponse.parseFrom(
                f.pushes(player, Contracts.IDS.setSpectateChatSetting()).getLast().getSerializedMessage());
        assertThat(badResp.getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);
        assertThat(player.spectateChatPref()).isEqualTo(eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID_VALUE);
    }
}
