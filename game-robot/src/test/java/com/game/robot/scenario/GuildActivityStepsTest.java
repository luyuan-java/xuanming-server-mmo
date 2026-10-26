package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.*;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.*;
import com.game.robot.client.RobotException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 终局验收判据同时盯住原邀请、同意集合、周期、真实背包与帮贡；计数或待发标志单独变绿不能通过。 */
class GuildActivityStepsTest {
    private static final long PLAYER = 0x8000_0000_0000_0001L;

    private GuildActivityView view(int type) {
        return GuildActivityView.newBuilder().setActivityId(100 + type).setTypeValue(type).setStateValue(3)
                .setPeriodKey(20261026).setGuildPeriodKey(20261026).setDailyLimit(3).setPersonalContribution(15)
                .setGuildFunds(50).setGuildThreshold(3).addRewardItems(GuildRewardItem.newBuilder().setItemId(9).setCount(2)).build();
    }
    private GuildInfo guild(long contribution, long funds) {
        return GuildInfo.newBuilder().setGuildId(11).setFunds(funds).addMembers(GuildMember.newBuilder().setPlayerId(PLAYER)
                .setContributionTotal(contribution + 100).setContributionBalance(contribution)).build();
    }
    private BagInfo bag(int count) {
        return BagInfo.newBuilder().addItems(BagItemInfo.newBuilder().setConfigId(9).setCount(count)).build();
    }
    private GuildActivitySteps.Snapshot before(int type) {
        return new GuildActivitySteps.Snapshot(view(type), guild(20, 100), bag(7));
    }
    private GuildActivitySteps.Snapshot after(int type) {
        var activity = view(type).toBuilder().setMyUsedCount(1).setProgress(1).setThresholdReached(true)
                .setTrialLobby(GuildTrialLobbyView.newBuilder().setLobbyId(31).setStateValue(4)).build();
        return new GuildActivitySteps.Snapshot(activity, guild(35, 150), bag(9));
    }

    @Test void 五个真实客户端活动入口从契约解析_没有内部dev入口替代() {
        var registry = MessageIdRegistry.loadFromClasspath();
        // 方法顺序是查询、点灯、团圆、发起、应邀；消息号不按这个业务顺序递增。
        assertThat(List.of("GetGuildActivities", "LightGuildLantern", "ClaimGuildReunion", "StartGuildTrial", "RespondGuildTrialInvite")
                .stream().map(name -> registry.requireId("GuildService", name)).toList()).containsExactly(241, 239, 240, 242, 243);
    }

    @Test void 每次邀请名单保持本人首位_不伪造内部launch身份() {
        var request = GuildActivitySteps.startRequest(103, PLAYER, List.of(7L, PLAYER, 9L));
        assertThat(request.getMemberPlayerIdsList()).containsExactly(PLAYER, 7L, 9L);
        assertThat(request.getActivityId()).isEqualTo(103);
        assertThatThrownBy(() -> GuildActivitySteps.startRequest(103, PLAYER, List.of(7L, 9L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuildActivitySteps.startRequest(103, PLAYER, List.of(PLAYER, 7L, 7L))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 邀请必须原名单唯一且只能有实际本人同意_提前开战会失败() {
        var roster = List.of(PLAYER, 7L);
        var lobby = GuildTrialLobbyView.newBuilder().setLobbyId(31).setInitiatorPlayerId(PLAYER)
                .addAllMemberPlayerIds(roster).addAcceptedPlayerIds(PLAYER).setStateValue(1).build();
        assertThat(GuildActivitySteps.lobbyProblem(lobby, PLAYER, roster, List.of(PLAYER), 1)).isEmpty();
        for (var invalid : List.of(lobby.toBuilder().addAcceptedPlayerIds(7).build(), lobby.toBuilder().addAcceptedPlayerIds(PLAYER).build(),
                lobby.toBuilder().setMemberPlayerIds(1, PLAYER).build(), lobby.toBuilder().setInitiatorPlayerId(7).build(),
                lobby.toBuilder().setBattleId(99).build(), lobby.toBuilder().setStateValue(2).build(), lobby.toBuilder().clearLobbyId().build()))
            assertThat(GuildActivitySteps.lobbyProblem(invalid, PLAYER, roster, List.of(PLAYER), 1)).isNotEmpty();
    }

    @Test void 查询缺活动或重复类型或业务拒绝都不能静默跳过() throws Exception {
        var response = GetGuildActivitiesResponse.newBuilder().addActivities(view(1)).addActivities(view(2)).addActivities(view(3)).build();
        assertThat(GuildActivitySteps.activity(response, 3)).isEqualTo(view(3));
        assertThatThrownBy(() -> GuildActivitySteps.activity(response.toBuilder().clearActivities().build(), 3)).isInstanceOf(RobotException.class);
        assertThatThrownBy(() -> GuildActivitySteps.activity(response.toBuilder().addActivities(view(3)).build(), 3)).isInstanceOf(RobotException.class);
        assertThatThrownBy(() -> GuildActivitySteps.activity(response.toBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(14031)).build(), 3))
                .isInstanceOf(RobotException.class);
    }

    @Test void 真实奖励必须次数帮贡资金物品全部恰好一次_重复发奖也失败() {
        var before = before(3);
        var after = after(3);
        assertThat(GuildActivitySteps.rewardProblem(before, after, PLAYER, 1, 150, true)).isEmpty();
        for (var invalid : List.of(
                new GuildActivitySteps.Snapshot(after.activity(), after.guild(), bag(7)),
                new GuildActivitySteps.Snapshot(after.activity(), after.guild(), bag(11)),
                new GuildActivitySteps.Snapshot(after.activity(), guild(20, 150), after.bag()),
                new GuildActivitySteps.Snapshot(after.activity(), guild(50, 150), after.bag()),
                new GuildActivitySteps.Snapshot(after.activity(), guild(35, 100), after.bag()),
                new GuildActivitySteps.Snapshot(after.activity(), guild(35, 200), after.bag()),
                new GuildActivitySteps.Snapshot(after.activity().toBuilder().setMyUsedCount(2).build(), after.guild(), after.bag())))
            assertThat(GuildActivitySteps.rewardProblem(before, invalid, PLAYER, 1, 150, true)).isNotEmpty();
    }

    @Test void 待发失败跨期或ACTIVE未终结均不能拿到账标志冒充完成() {
        var before = before(3);
        var after = after(3);
        for (var invalid : List.of(after.activity().toBuilder().setMyPendingRewardCount(1).build(),
                after.activity().toBuilder().setMyPendingReasonTipId(3001).build(),
                after.activity().toBuilder().setMyLastRewardRejectTipId(3002).build(),
                after.activity().toBuilder().setPeriodKey(20261027).build(),
                after.activity().toBuilder().setGuildPeriodKey(20261027).build(),
                after.activity().toBuilder().setMyTrialBattleId(99).build(),
                after.activity().toBuilder().setTrialLobby(after.activity().getTrialLobby().toBuilder().setStateValue(3)).build()))
            assertThat(GuildActivitySteps.rewardProblem(before, new GuildActivitySteps.Snapshot(invalid, after.guild(), after.bag()),
                    PLAYER, 1, 150, true)).isNotEmpty();
        assertThat(GuildActivitySteps.rewardProblem(before, new GuildActivitySteps.Snapshot(after.activity().toBuilder().clearTrialLobby().build(),
                after.guild(), after.bag()), PLAYER, 1, 150, true)).isEmpty();
    }

    @Test void 灯会进度和团圆锁存都不能只靠个人奖励推断() {
        var lantern = after(1);
        assertThat(GuildActivitySteps.rewardProblem(before(1), lantern, PLAYER, 1, 150, false)).isEmpty();
        assertThat(GuildActivitySteps.rewardProblem(before(1), new GuildActivitySteps.Snapshot(lantern.activity().toBuilder().setProgress(0).build(),
                lantern.guild(), lantern.bag()), PLAYER, 1, 150, false)).contains("点灯进度");
        var reunion = after(2);
        assertThat(GuildActivitySteps.rewardProblem(before(2), reunion, PLAYER, 1, 150, false)).isEmpty();
        assertThat(GuildActivitySteps.rewardProblem(before(2), new GuildActivitySteps.Snapshot(reunion.activity().toBuilder().setThresholdReached(false).build(),
                reunion.guild(), reunion.bag()), PLAYER, 1, 150, false)).contains("团圆");
    }
}
