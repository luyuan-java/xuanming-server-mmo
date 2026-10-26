package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BagInfo;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.guild.*;
import com.game.robot.client.BattleIds;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.table.GuildErrorTip;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 普通239–243入口的活动验收；真实本人同意开战，终局后验计数、帮贡和背包，不能由dev开战代替。 */
final class GuildActivitySteps {
    static final String REF = "guild-spec §11.6、4.6a/4.6b；普通客户端239–243";
    static final Duration REWARD_TIMEOUT = Duration.ofSeconds(60);
    private static final int ALREADY_CLAIMED = GuildErrorTip.guild_error.kGuildActivityAlreadyClaimed_VALUE;
    private static final int DECLINED = GuildErrorTip.guild_error.kGuildTrialInviteDeclined_VALUE;
    private final RobotClient client;
    private final MessageIdRegistry registry;
    private final CheckReport report;
    private final Duration requestTimeout;
    private final BattleIds battleIds;
    private final int activities, lantern, reunion, startTrial, respond, getGuild, getBag, create, apply, review, disband, notify;
    private final List<BattleSupport.Direct> directs = new ArrayList<>();

    GuildActivitySteps(RobotClient client, MessageIdRegistry registry, CheckReport report, Duration requestTimeout) {
        this.client = client;
        this.registry = registry;
        this.report = report;
        this.requestTimeout = requestTimeout;
        battleIds = BattleIds.resolve(registry);
        activities = registry.requireId("GuildService", "GetGuildActivities");
        lantern = registry.requireId("GuildService", "LightGuildLantern");
        reunion = registry.requireId("GuildService", "ClaimGuildReunion");
        startTrial = registry.requireId("GuildService", "StartGuildTrial");
        respond = registry.requireId("GuildService", "RespondGuildTrialInvite");
        getGuild = registry.requireId("GuildService", "GetPlayerGuild");
        getBag = registry.requireId("SceneBagClientPlayer", "GetBag");
        create = registry.requireId("GuildService", "CreateGuild");
        apply = registry.requireId("GuildService", "ApplyJoinGuild");
        review = registry.requireId("GuildService", "ReviewGuildApplication");
        disband = registry.requireId("GuildService", "DisbandGuild");
        notify = registry.requireId("GuildService", "NotifyGuildChanged");
    }

    void run(List<GuildScenario.Bot> bots) {
        long guildId = 0;
        var leader = bots.getFirst();
        try {
            var created = leader.call(create, CreateGuildRequest.newBuilder().setName("活动验收" + GuildScenario.nonce()).build(),
                    CreateGuildResponse.parser());
            requireOk(created, "活动验收建帮");
            guildId = created.getGuild().getGuildId();
            must(guildId != 0, "活动验收取得独立帮会", "guild_id=" + Long.toUnsignedString(guildId));
            for (var bot : bots.subList(1, bots.size())) {
                requireOk(bot.call(apply, ApplyJoinGuildRequest.newBuilder().setGuildId(guildId).build(), ApplyJoinGuildResponse.parser()),
                        bot.name + "申请活动测试帮");
                requireOk(leader.call(review, ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(bot.id()).setApprove(true).build(),
                        ReviewGuildApplicationResponse.parser()), bot.name + "入帮");
            }
            var views = list(leader);
            for (int type : List.of(1, 2, 3)) {
                var view = activity(views, type);
                must(view.getStateValue() == 3 && view.getJoinMinHours() == 0 && view.getMyUsedCount() == 0,
                        "241 活动" + type + "开放且新号可参与", describe(view) + "；测试配表须join_min_hours=0、最低帮级1");
                must(view.getRewardItemsCount() > 0, "活动" + type + "配置真实物品奖励", describe(view));
            }
            var reunionView = activity(views, 2);
            must(reunionView.getGuildThreshold() > 0 && reunionView.getGuildThreshold() <= bots.size(),
                    "团圆在线门槛在五人夹具范围", describe(reunionView));
            ordinary(leader, 1);
            ordinary(leader, 2);
            trial(bots, activity(list(leader), 3), guildId);
        } catch (RobotException | RuntimeException failure) {
            report.fail("帮会活动流程中断", failure.getMessage() == null ? failure.toString() : failure.getMessage(), REF);
        } finally {
            directs.forEach(BattleSupport.Direct::close);
            if (guildId != 0) {
                try {
                    requireOk(leader.call(disband, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser()), "活动测试帮收尾解散");
                } catch (RobotException | RuntimeException failure) {
                    report.fail("活动测试帮收尾解散", failure.getMessage() == null ? failure.toString() : failure.getMessage(), REF);
                }
            }
        }
    }

    private void ordinary(GuildScenario.Bot bot, int type) throws RobotException {
        var before = snapshot(bot, type);
        var view = before.activity();
        long count = Integer.toUnsignedLong(view.getDailyLimit()) - Integer.toUnsignedLong(view.getMyUsedCount());
        must(count > 0 && count <= 8, "活动" + type + "日限在有界验收范围1..8", describe(view));
        for (int i = 0; i < count; i++) {
            GuildActivityView after;
            if (type == 1) {
                var reply = bot.call(lantern, LightGuildLanternRequest.newBuilder().setActivityId(view.getActivityId()).build(),
                        LightGuildLanternResponse.parser());
                requireOk(reply, "239 点灯");
                after = reply.getActivity();
            } else {
                var reply = bot.call(reunion, ClaimGuildReunionRequest.newBuilder().setActivityId(view.getActivityId()).build(),
                        ClaimGuildReunionResponse.parser());
                requireOk(reply, "240 团圆领奖");
                after = reply.getActivity();
            }
            must(after.getActivityId() == view.getActivityId() && after.getMyUsedCount() == view.getMyUsedCount() + i + 1,
                    (type == 1 ? "239" : "240") + "本人次数逐次加一", describe(after));
        }
        Message duplicate = type == 1
                ? bot.call(lantern, LightGuildLanternRequest.newBuilder().setActivityId(view.getActivityId()).build(), LightGuildLanternResponse.parser())
                : bot.call(reunion, ClaimGuildReunionRequest.newBuilder().setActivityId(view.getActivityId()).build(), ClaimGuildReunionResponse.parser());
        must(GuildScenario.tipOf(duplicate) == ALREADY_CLAIMED, "活动" + type + "超日限拒绝且不重复发奖", GuildScenario.describeTip(duplicate));
        long funds = before.guild().getFunds();
        if (!view.getFundsGranted() && (type == 2 || Integer.toUnsignedLong(view.getProgress()) + count >= Integer.toUnsignedLong(view.getGuildThreshold())))
            funds += view.getGuildFunds();
        awaitReward(bot, before, (int) count, funds, false);
    }

    private void trial(List<GuildScenario.Bot> bots, GuildActivityView view, long guildId) throws RobotException {
        int size = view.getTeamSizeMin();
        must(size >= 2 && size <= bots.size() && size <= view.getTeamSizeMax(), "历练人数在2..5人夹具范围", describe(view));
        List<GuildScenario.Bot> members = List.copyOf(bots.subList(0, size));
        var a = members.getFirst();
        var b = members.get(1);
        int markA = a.mark(), markB = b.mark();
        var invited = a.call(startTrial, startRequest(view.getActivityId(), a.id(), members.stream().map(GuildScenario.Bot::id).toList()),
                StartGuildTrialResponse.parser());
        requireOk(invited, "242 A发真实历练邀请");
        var lobby = invited.getActivity().getTrialLobby();
        String problem = lobbyProblem(lobby, a.id(), members.stream().map(GuildScenario.Bot::id).toList(), List.of(a.id()), 1);
        must(problem.isEmpty(), "242 仅发起者同意且未开战", problem);
        var seen = activity(list(b), 3).getTrialLobby();
        must(seen.getLobbyId() == lobby.getLobbyId() && seen.getAcceptedPlayerIdsList().equals(List.of(a.id())),
                "241 被邀请人查到同一房间和原同意名单", seen.toString());
        var push = b.connection().await(markB, r -> {
            var change = r.messageId() == notify && r.requestId() == 0 ? r.parseOrNull(GuildChangedS2C.parser()) : null;
            return change != null && change.getGuildId() == guildId && change.getKind() == GuildChangeKind.GUILD_CHANGE_KIND_ACTIVITY_CHANGED;
        }, GuildScenario.PUSH_TIMEOUT);
        must(push.isPresent(), "被邀请人收到220活动变化提醒", b.describeSince(markB));
        var declined = b.call(respond, RespondGuildTrialInviteRequest.newBuilder().setLobbyId(lobby.getLobbyId()).setAccept(false).build(),
                RespondGuildTrialInviteResponse.parser());
        requireOk(declined, "243 B本人拒绝");
        var ended = declined.getActivity().getTrialLobby();
        must(ended.getLobbyId() == lobby.getLobbyId() && ended.getStateValue() == 4 && ended.getEndTipId() == DECLINED && ended.getBattleId() == 0,
                "243 拒绝使原邀请终结", ended.toString());
        must(noAssignment(a, markA) && noAssignment(b, markB), "未全员本人同意绝不进入战斗", "拒绝房间不能收到177或143");

        // 换尚未发起过的B建新房，避免把A的合法邀请冷却误判为故障。未知写应答不重发；恢复观察只读241。
        // 全球SQL成局需要每位参与者当前会话的已签名探测档案；在全员同意前完成，避免留下无法开战的ACTIVE原件。
        for (var bot : members) {
            PveNetworkProbe.prepare(bot, registry, requestTimeout);
            report.pass(bot.name + "完成真实机房探测并提交回执", "本人当前会话profile已确认", REF);
        }
        Map<Long, Snapshot> before = new HashMap<>();
        for (var bot : members) before.put(bot.id(), snapshot(bot, 3));
        Map<Long, Integer> marks = new HashMap<>();
        for (var bot : members) marks.put(bot.id(), bot.mark());
        List<Long> roster = members.stream().map(GuildScenario.Bot::id).toList();
        var started = b.call(startTrial, startRequest(view.getActivityId(), b.id(), roster), StartGuildTrialResponse.parser());
        requireOk(started, "242 B重新发真实邀请");
        var pending = started.getActivity().getTrialLobby();
        must(pending.getLobbyId() != lobby.getLobbyId()
                        && lobbyProblem(pending, b.id(), roster, List.of(b.id()), 1).isEmpty(),
                "新操作取得新邀请原件且仅B已同意", pending.toString());
        List<Long> accepted = new ArrayList<>(List.of(b.id()));
        for (var bot : members) {
            if (bot.id() == b.id()) continue;
            var response = bot.call(respond, RespondGuildTrialInviteRequest.newBuilder().setLobbyId(pending.getLobbyId()).setAccept(true).build(),
                    RespondGuildTrialInviteResponse.parser());
            requireOk(response, "243 " + bot.name + "本人同意");
            accepted.add(bot.id());
            var current = response.getActivity().getTrialLobby();
            must(current.getLobbyId() == pending.getLobbyId() && new HashSet<>(current.getAcceptedPlayerIdsList()).equals(new HashSet<>(accepted))
                            && current.getAcceptedPlayerIdsCount() == accepted.size(),
                    "每份本人同意只推进自己的原名单项", current.toString());
            if (accepted.size() < size)
                must(current.getStateValue() == 1 && noAssignment(bot, marks.get(bot.id())), "未全员确认保持PENDING且没有开战公告", current.toString());
            else
                must(current.getStateValue() == 2 || current.getStateValue() == 3, "全员确认后才进入LAUNCHING或LAUNCHED", current.toString());
        }
        long battleId = 0;
        List<BattleSupport.Direct> battleConnections = new ArrayList<>();
        for (var bot : members) {
            var start = MatchSupport.awaitBattle(bot.name, bot.connection(), marks.get(bot.id()), battleId, battleIds, MatchSupport.BATTLE_START_TIMEOUT);
            if (battleId == 0) battleId = start.battleId();
            String sides = BattleSmokeChecks.sidesProblem(start.start().getState(), roster, java.util.Collections.nCopies(size, 0));
            must(start.battleId() == battleId && start.assignedFirst() && sides == null,
                    bot.name + "收到同一历练177→143且全员在0队", sides == null ? "battle_id=" + Long.toUnsignedString(battleId) : sides);
            var direct = MatchSupport.connect(client, bot.name, start.assigned(), battleIds, requestTimeout);
            directs.add(direct);
            battleConnections.add(direct);
        }
        List<MatchSupport.AutoRequest> autos = new ArrayList<>();
        for (var direct : battleConnections) autos.add(MatchSupport.enableAuto(direct, battleId, battleIds));
        for (int i = 0; i < members.size(); i++) {
            var end = MatchSupport.awaitEnd(battleConnections.get(i), battleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autos.get(i));
            must(end.end().getOutcomeValue() == 1 && end.turns() > 0 && end.autoAccepted(),
                    members.get(i).name + "真实历练自动战斗胜利并收到150", "outcome=" + end.end().getOutcome() + " turns=" + end.turns() + end.autoNote());
            battleConnections.get(i).close();
        }
        var first = before.get(a.id());
        long funds = first.guild().getFunds() + (view.getProgress() < view.getGuildThreshold() ? view.getGuildFunds() : 0);
        for (var bot : members) awaitReward(bot, before.get(bot.id()), 1, funds, true);
        // 再读一遍：结果重投/补拉不能把已结算的次数、帮贡或物品重复增加。
        BattleSupport.sleep(Duration.ofSeconds(2));
        for (var bot : members) {
            String repeat = rewardProblem(before.get(bot.id()), snapshot(bot, 3), bot.id(), 1, funds, true);
            must(repeat.isEmpty(), bot.name + "终局补拉保持一次奖励", repeat);
        }
        report.note("GUILD_ACTIVITY_SMOKE_OK guild_id=" + Long.toUnsignedString(guildId) + " battle_id=" + Long.toUnsignedString(battleId)
                + " players=" + size + " entry=239-243");
    }

    private boolean noAssignment(GuildScenario.Bot bot, int mark) throws RobotException {
        return bot.connection().await(mark, r -> r.requestId() == 0
                && (r.messageId() == battleIds.battleAssigned() || r.messageId() == battleIds.battleStart()), Duration.ofMillis(500)).isEmpty();
    }
    static StartGuildTrialRequest startRequest(int activity, long initiator, List<Long> members) {
        if (activity == 0 || initiator == 0 || !members.contains(initiator) || new HashSet<>(members).size() != members.size())
            throw new IllegalArgumentException("历练验收名单缺本人或有重复");
        var ordered = new ArrayList<Long>();
        ordered.add(initiator);
        members.stream().filter(id -> id != initiator).forEach(ordered::add);
        return StartGuildTrialRequest.newBuilder().setActivityId(activity).addAllMemberPlayerIds(ordered).build();
    }
    static String lobbyProblem(GuildTrialLobbyView lobby, long initiator, List<Long> roster, List<Long> accepted, int state) {
        if (lobby.getLobbyId() == 0 || lobby.getInitiatorPlayerId() != initiator || lobby.getStateValue() != state
                || lobby.getMemberPlayerIdsCount() != roster.size() || !new HashSet<>(lobby.getMemberPlayerIdsList()).equals(new HashSet<>(roster))
                || lobby.getMemberPlayerIds(0) != initiator || lobby.getAcceptedPlayerIdsCount() != accepted.size()
                || !new HashSet<>(lobby.getAcceptedPlayerIdsList()).equals(new HashSet<>(accepted)) || lobby.getBattleId() != 0)
            return "邀请原件、同意名单或状态不符：" + lobby;
        return "";
    }
    static GuildActivityView activity(GetGuildActivitiesResponse response, int type) throws RobotException {
        var views = response.getActivitiesList().stream().filter(v -> v.getTypeValue() == type).toList();
        if (GuildScenario.tipOf(response) != 0 || views.size() != 1 || views.getFirst().getActivityId() == 0)
            throw new RobotException("241缺少唯一活动type=" + type + "，" + GuildScenario.describeTip(response));
        return views.getFirst();
    }
    private GetGuildActivitiesResponse list(GuildScenario.Bot bot) throws RobotException {
        return bot.call(activities, GetGuildActivitiesRequest.getDefaultInstance(), GetGuildActivitiesResponse.parser());
    }
    record Snapshot(GuildActivityView activity, GuildInfo guild, BagInfo bag) {}
    private Snapshot snapshot(GuildScenario.Bot bot, int type) throws RobotException {
        var activity = activity(list(bot), type);
        var guild = bot.call(getGuild, GetPlayerGuildRequest.getDefaultInstance(), GetPlayerGuildResponse.parser());
        var bag = bot.call(getBag, GetBagRequest.newBuilder().setBagType(0).build(), GetBagResponse.parser());
        requireOk(guild, bot.name + "查询本人帮会");
        requireOk(bag, bot.name + "查询真实背包");
        must(guild.hasGuild() && bag.hasBag(), bot.name + "活动奖励基线完整", "帮会和背包快照都必须存在");
        return new Snapshot(activity, guild.getGuild(), bag.getBag());
    }
    private void awaitReward(GuildScenario.Bot bot, Snapshot before, int count, long funds, boolean trial) throws RobotException {
        long until = System.nanoTime() + REWARD_TIMEOUT.toNanos();
        String problem;
        do {
            problem = rewardProblem(before, snapshot(bot, before.activity().getTypeValue()), bot.id(), count, funds, trial);
            if (problem.isEmpty()) break;
            BattleSupport.sleep(Duration.ofMillis(500));
        } while (System.nanoTime() < until);
        must(problem.isEmpty(), bot.name + "活动" + before.activity().getTypeValue() + "终局次数、帮贡和真实物品到账且无待发", problem);
    }
    static String rewardProblem(Snapshot before, Snapshot after, long player, int count, long funds, boolean trial) {
        var old = before.activity();
        var view = after.activity();
        if (view.getActivityId() != old.getActivityId() || view.getPeriodKey() != old.getPeriodKey()
                || view.getGuildPeriodKey() != old.getGuildPeriodKey()) return "活动或周期变更，不能以新一期掩盖旧结果";
        if (view.getMyUsedCount() != old.getMyUsedCount() + count || view.getMyPendingRewardCount() != 0
                || view.getMyPendingReasonTipId() != 0 || view.getMyLastRewardRejectTipId() != 0) return "次数或待发奖励未就绪：" + describe(view);
        if (trial && (view.getMyTrialBattleId() != 0 || view.hasTrialLobby() && view.getTrialLobby().getStateValue() != 4))
            return "终局后仍有活动战斗或未关闭邀请";
        var oldMember = GuildEconomyScenario.member(before.guild(), player);
        var member = GuildEconomyScenario.member(after.guild(), player);
        if (oldMember == null || member == null || after.guild().getGuildId() != before.guild().getGuildId()) return "本人帮会成员快照不完整";
        long contribution = old.getPersonalContribution() * count;
        if (member.getContributionTotal() != oldMember.getContributionTotal() + contribution
                || member.getContributionBalance() != oldMember.getContributionBalance() + contribution) return "累计或可用帮贡不是恰好一次奖励";
        if (after.guild().getFunds() != funds) return "帮会资金=" + Long.toUnsignedString(after.guild().getFunds()) + "，期望=" + Long.toUnsignedString(funds);
        for (var reward : old.getRewardItemsList()) {
            long expected = GuildEconomyScenario.itemCount(before.bag(), reward.getItemId()) + Integer.toUnsignedLong(reward.getCount()) * count;
            long actual = GuildEconomyScenario.itemCount(after.bag(), reward.getItemId());
            if (actual != expected) return "背包物品" + Integer.toUnsignedString(reward.getItemId()) + "=" + actual + "，期望=" + expected;
        }
        if (old.getTypeValue() == 1 && view.getProgress() != old.getProgress() + count) return "点灯进度未逐人次增加";
        if (old.getTypeValue() == 2 && !view.getThresholdReached()) return "团圆领奖后没有锁存在线阈值";
        return "";
    }
    private void requireOk(Message response, String name) throws RobotException {
        if (GuildScenario.tipOf(response) != 0) throw new RobotException(name + "：" + GuildScenario.describeTip(response));
    }
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) throw new RobotException(name + "：" + detail);
    }
    private static String describe(GuildActivityView view) {
        return "activity=" + Integer.toUnsignedString(view.getActivityId()) + " type=" + view.getTypeValue() + " state=" + view.getStateValue()
                + " used=" + view.getMyUsedCount() + "/" + view.getDailyLimit() + " blocked=" + view.getBlockedTipId()
                + " pending=" + view.getMyPendingRewardCount() + " reason=" + view.getMyPendingReasonTipId() + " reject=" + view.getMyLastRewardRejectTipId();
    }
}
