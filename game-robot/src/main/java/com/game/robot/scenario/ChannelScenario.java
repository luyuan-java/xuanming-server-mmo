package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneInfoComp;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotException;
import com.game.robot.flow.PlayerFlow;
import java.time.Duration;

/** 5.6：只用客户端43/31与63/79验收主动切线，不写Redis、不调用dev搬人。 */
public final class ChannelScenario {
    private static final String REF = "cpp-continuation-20261026 §4；world-channel-switch §3/§7";
    private final SceneMoves moves;
    private final String account;
    private final int zone;
    private final CheckReport report = new CheckReport();
    private String step = "C1-login";
    private boolean singleLine;
    private String cooldownStatus = "skipped";

    public ChannelScenario(PlayerFlow flow, MessageIdRegistry registry, String prefix, String runTag, int zone,
                           Duration requestTimeout, Duration observeTimeout) {
        moves = new SceneMoves(flow, MessageIds.resolve(registry), registry, requestTimeout, observeTimeout);
        account = accountName(prefix, runTag);
        this.zone = zone;
    }

    public static String accountName(String prefix, String runTag) { return prefix + "ch" + runTag; }
    public String account() { return account; }

    public CheckReport run() {
        try { runChecks(); }
        catch (RobotException | RuntimeException failure) {
            report.fail(step, failure.getMessage() == null ? failure.toString() : failure.getMessage(), REF);
        } finally { moves.closeAll(); }
        return report;
    }

    public String resultLine() {
        if (!report.passed()) return "CHANNEL_SMOKE_FAIL step=" + step;
        if (singleLine) return "CHANNEL_SMOKE_SKIP reason=single_line directory_checks=passed";
        return "CHANNEL_SMOKE_OK cooldown=" + cooldownStatus;
    }

    private void runChecks() throws RobotException {
        ProbeBot bot = moves.login(account, "A");
        report.pass(step, "角色已进场", REF);
        step = "C2-directory";
        require(!moves.querySceneInfo(bot, "普通43").hasChannelDirectory(), "未带开关不附目录");
        SceneChannelDirectory before = query(bot);
        require(before.getSwitchEnabled(), "主动切线已开启");
        int config = bot.scene().getSceneConfigId();
        long source = bot.scene().getSceneId();

        // 当前线同步拒绝不依赖是否有第二条线路，也不应消耗切线冷却。
        step = "C7-current";
        SceneMoves.Sent same = moves.send(bot, pick(config, source), "切当前线");
        require(same.tip() == 3008 && moves.quietEnters(bot.connection(), same.mark()) == 0,
                "当前线同步3008，且没有79");
        var alternatives = before.getChannelsList().stream().filter(ChannelChecks::selectable)
                .filter(c -> c.getSceneId() != source).toList();
        if (alternatives.isEmpty()) {
            require(before.getChannelsCount() == 1, "多线路部署应至少有一条可选目标");
            singleLine = true;
            report.note("SKIP C3–C6：当前部署只有一条线路，未验收切换、稳定性和冷却");
            return;
        }
        long cooldown = Integer.toUnsignedLong(before.getSwitchCooldownSeconds());
        require(cooldown <= 60, "探针等待上限60秒；完整冷却验收需10–60秒及足够观测窗口");
        SceneChannelInfo target = alternatives.getFirst();
        step = "C3-pick";
        long firstSent = System.nanoTime(); // 包含发送节拍等待，作为最早可能起算时刻的保守下界。
        SceneMoves.Sent sent = moves.send(bot, pick(config, target.getSceneId()), "主动切目标线");
        require(sent.tip() == 0, "63同步受理");
        step = "C4-arrival";
        SceneMoves.Arrival arrival = moves.arrive(bot, sent.mark(), "主动切目标线");
        require(arrival.info().getSceneId() == target.getSceneId() && arrival.info().getSceneConfigId() == config
                && sent.reply().index() < arrival.enterNotify().index(), "受理应答先于目标79且地图/实例正确");
        bot = bot.movedTo(arrival.info(), arrival.entity(), arrival.at(), arrival.enterNotify().index());

        step = "C6-cooldown";
        ChannelTiming.Window timing = cooldown == 0 ? null
                : ChannelTiming.window(firstSent, arrival.enterNotify().receivedNanos(), cooldown);
        boolean rejectionChecked = false;
        boolean alreadyReturned = false;
        long rejectionSent = 0;
        int rejectionMark = -1;
        if (cooldown > 0) {
            // 先判定计划窗口，短冷却/慢到达不用多发一次可能已经合法的切回。
            long planned = System.nanoTime();
            if (planned - timing.probeAt() < 0) planned = timing.probeAt();
            if (timing.canProbe(planned)) {
                waitUntil(timing.probeAt());
                rejectionSent = System.nanoTime();
                if (timing.canProbe(rejectionSent)) {
                    SceneMoves.Sent denied = moves.send(bot, pick(config, source), "首次冷却写入完成后切回");
                    require(denied.tip() == 0, "冷却探测63同步受理");
                    var outcome = moves.awaitOutcome(bot.connection(), denied.mark(), moves.requestTimeout())
                            .orElseThrow(() -> new RobotException("冷却探测已受理但结果未知；不创建第二次切回意图"));
                    if (outcome.messageId() == moves.ids().notifyEnterScene()) {
                        require(!timing.beforeExpiry(outcome.receivedNanos()), "最早可能过期之前不得切回成功");
                        SceneMoves.Arrival returned = moves.arrive(bot, denied.mark(), "迟到冷却探测的实际切回");
                        require(returned.info().getSceneId() == source && returned.info().getSceneConfigId() == config
                                        && denied.reply().index() < returned.enterNotify().index(),
                                "迟到探测仍须63应答先于原线路79且地图正确");
                        bot = bot.movedTo(returned.info(), returned.entity(), returned.at(), returned.enterNotify().index());
                        alreadyReturned = true;
                        skipCooldown("切回结果到达时可能已过期，拒绝与不续期子项均未验证；已确认实际返回原线");
                    } else {
                        require(CrossNodeScenario.tipId(outcome) == InstanceChecks.ENTER_FAILED
                                        && moves.quietEnters(bot.connection(), denied.mark()) == 0,
                                "冷却探测只收到23{3023}且没有到达79");
                        rejectionMark = denied.mark();
                        rejectionChecked = timing.beforeExpiry(outcome.receivedNanos());
                        if (!rejectionChecked) skipCooldown("拒绝结果晚于最早可能过期时刻，无法证明冷却内拒绝");
                    }
                } else {
                    skipCooldown("等待期间耗尽可信拒绝窗口，未发送拒绝探测");
                }
            } else {
                skipCooldown("冷却短于10秒或首次到达较慢，首次写入完成后不足2秒可信拒绝窗口");
            }
        } else {
            cooldownStatus = "disabled";
            report.note("SKIP 冷却拒绝：目录明确配置0秒；仍验证切回成功");
        }
        step = "C5-stable";
        SceneChannelDirectory after = query(bot);
        require(ChannelChecks.stable(before, after), "仍在役的场景保持原线号");
        require(after.getChannelsList().stream().anyMatch(c -> c.getSceneId() == source && ChannelChecks.selectable(c)),
                "原线路仍可供冷却结束后切回");

        step = "C6-expired";
        if (alreadyReturned) return; // 已观察到迟到探测合法返回，不能再次“切当前线”冒充冷却验收。
        if (timing != null) waitUntil(timing.returnAt()); // 固定锚点，拒绝/再次列目录都不能重启完整TTL等待。
        if (rejectionMark >= 0) require(CrossNodeScenario.countOf(bot.connection().inbox().snapshot(rejectionMark),
                moves.ids().notifyEnterScene()) == 0, "拒绝后的整个等待期没有迟到79");
        SceneMoves.Sent back = moves.send(bot, pick(config, source), "冷却后切回");
        require(back.tip() == 0, "冷却后63受理");
        SceneMoves.Arrival returned = moves.arrive(bot, back.mark(), "冷却后切回");
        require(returned.info().getSceneId() == source && returned.info().getSceneConfigId() == config
                        && back.reply().index() < returned.enterNotify().index(),
                "冷却结束后到达原线路");
        if (timing != null && rejectionChecked) {
            if (timing.excludesRenewal(rejectionSent, returned.enterNotify().receivedNanos())) {
                cooldownStatus = "checked";
                report.pass("C6-no-renew", "原冷却固定截止之后、拒绝续期最早截止之前已返回，拒绝未续期", REF);
            } else {
                skipCooldown("切回完成较晚，已验证过期后能返回，但无法排除拒绝时续期");
            }
        }
    }

    private SceneChannelDirectory query(ProbeBot bot) throws RobotException {
        // 刚开节点时允许等三次发布周期；只读重试不会创建第二份切线意图。
        for (int attempt = 0; attempt < 4; attempt++) {
            var response = moves.querySceneInfo(bot, "带目录43", true);
            require(response.getSceneInfoCount() == 1 && response.getSceneInfo(0).getSceneId() == bot.scene().getSceneId(),
                    "31仍描述当前场景");
            if (response.hasChannelDirectory()) {
                var directory = response.getChannelDirectory();
                var errors = ChannelChecks.directory(directory, zone, bot.scene().getSceneConfigId(), bot.scene().getSceneId());
                require(errors.isEmpty(), "目录有效：" + errors);
                return directory;
            }
            if (attempt < 3) SceneMoves.sleep(Duration.ofSeconds(2));
        }
        throw new RobotException("连续4次31没有目录，检查manager发布与scene读取");
    }

    private static EnterSceneC2SRequest pick(int config, long scene) {
        return EnterSceneC2SRequest.newBuilder().setSceneInfo(SceneInfoComp.newBuilder()
                .setSceneConfigId(config).setSceneId(scene)).build();
    }

    private void skipCooldown(String reason) {
        cooldownStatus = "skipped";
        report.note("SKIP C6 冷却子项：" + reason);
    }

    private static void waitUntil(long deadline) throws RobotException {
        long nanos;
        while ((nanos = ChannelTiming.waitNanos(deadline, System.nanoTime())) > 0)
            SceneMoves.sleep(Duration.ofMillis((nanos + 999_999) / 1_000_000));
    }

    private void require(boolean condition, String detail) throws RobotException {
        report.check(condition, step, detail, REF);
        if (!condition) throw new RobotException(detail);
    }
}
