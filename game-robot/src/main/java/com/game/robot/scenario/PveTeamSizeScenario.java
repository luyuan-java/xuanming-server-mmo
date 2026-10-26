package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.match.*;
import com.game.robot.client.BattleIds;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 6.6普通全球排队验收：默认五人、档位隔离、原UUID撤销后重排、单人即时局及每人两只怪。 */
public final class PveTeamSizeScenario {
    static final String REF = "批次6.6；pve-team-size-matching设计；全球SQL原Join与网络探测契约";
    public static final int PLAYERS = 5;
    private final RobotClient client;
    private final PlayerFlow flow;
    private final MessageIdRegistry registry;
    private final MatchSupport.Ids ids;
    private final BattleIds battleIds;
    private final String prefix, runTag;
    private final Duration timeout;
    private final MatchSupport.Tempo tempo;
    private final CheckReport report = new CheckReport();
    private final StepTrack steps = new StepTrack("PVE_TEAM_SIZE");
    private final List<MatchSupport.Bot> bots = new ArrayList<>();
    private final List<BattleSupport.Direct> directs = new ArrayList<>();
    private final Map<Long, Intent> pending = new LinkedHashMap<>();
    private final List<Long> battles = new ArrayList<>();
    private record Intent(MatchSupport.Bot bot, JoinQueueRequest request, int mark, String ticket) {}

    public PveTeamSizeScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, String accountPrefix,
            String runTag, Duration requestTimeout) {
        this(client, flow, registry, accountPrefix, runTag, requestTimeout, MatchSupport.Tempo.STANDARD);
    }
    PveTeamSizeScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, String accountPrefix,
            String runTag, Duration requestTimeout, MatchSupport.Tempo tempo) {
        this.client = client;
        this.flow = flow;
        this.registry = registry;
        this.ids = MatchSupport.Ids.resolve(registry);
        this.battleIds = BattleIds.resolve(registry);
        this.prefix = accountPrefix;
        this.runTag = runTag;
        this.timeout = requestTimeout;
        this.tempo = tempo;
    }
    public static String accountName(String prefix, String runTag, int index) {
        if (index < 0 || index >= PLAYERS) throw new IllegalArgumentException("PVE人数档账号下标必须0..4");
        return prefix + "ps" + runTag + "_" + index;
    }
    public static String accountName(String prefix, String runTag, String suffix) {
        if (suffix == null || !suffix.matches("[0-4]")) throw new IllegalArgumentException("PVE人数档账号后缀必须0..4");
        return accountName(prefix, runTag, Integer.parseInt(suffix));
    }
    public String firstAccount() { return accountName(prefix, runTag, 0); }
    public String resultLine() { return steps.line(report, "battles=" + battles.stream().map(Long::toUnsignedString).toList() + " modes=5,2,1"); }
    public CheckReport run() {
        try { runChecks(); }
        catch (RobotException | RuntimeException failure) {
            report.fail("人数档流程中断", failure.getMessage() == null ? failure.toString() : failure.getMessage(), REF);
        } finally {
            directs.forEach(BattleSupport.Direct::close);
            for (var intent : List.copyOf(pending.values())) {
                try { cancel(intent); }
                catch (RobotException | RuntimeException failure) { report.fail("原排队意图收尾未确认", failure.getMessage(), REF); }
            }
            bots.forEach(bot -> bot.connection().close());
        }
        return report;
    }
    private void runChecks() throws RobotException {
        steps.step("login", report);
        for (int i = 0; i < PLAYERS; i++) {
            var player = flow.enter(accountName(prefix, runTag, i), new Timings());
            bots.add(new MatchSupport.Bot("P" + i, player, timeout, tempo));
        }
        steps.step("over-limit", report);
        var a = bots.get(0);
        for (int invalid : new int[] {6, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            var request = request(a, invalid);
            var intent = new Intent(a, request, a.mark(), "");
            pending.put(a.id(), intent);
            var response = a.call(ids.joinQueue(), request, JoinQueueResponse.parser());
            pending.put(a.id(), new Intent(a, request, intent.mark(), response.getQueueTicket()));
            String problem = PveTeamSizeChecks.overLimitProblem(response);
            must(problem.isEmpty(), "team_size=" + Integer.toUnsignedString(invalid) + "明确拒绝且无票", problem);
            pending.remove(a.id());
        }
        steps.step("zero-is-five", report);
        List<Intent> five = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            five.add(join(bots.get(i), i == 0 ? 0 : 5));
            if (i < PLAYERS - 1) queued(five.getFirst(), "0默认五人：不足五人仍排队");
        }
        fight(five, 10);

        steps.step("isolate-and-requeue", report);
        var two = join(a, 2);
        var three = join(bots.get(1), 3);
        queued(two, "两人档与三人档不能混组");
        queued(three, "三人档不能被两人档消费");
        BattleSupport.sleep(tempo.silence());
        must(noAssignment(two) && noAssignment(three), "不同人数档都没有错误开局公告", "2和3两个不同原队列");
        cancel(three);
        var requeued = join(bots.get(1), 2);
        must(!requeued.request().getRequestId().equals(three.request().getRequestId()), "取消得到SQL墓碑后用新UUID重排", "原意图永久撤销，新意图进入2人档");
        fight(List.of(two, requeued), 4);

        steps.step("one-immediate", report);
        var solo = join(bots.get(2), 1);
        fight(List.of(solo), 2);
        report.pass("1人档无需其他排队者即可开战", "实际名单1人、怪物2只", REF);
    }
    private JoinQueueRequest request(MatchSupport.Bot bot, int size) throws RobotException {
        String plan = PveNetworkProbe.prepare(bot, registry, timeout);
        return PveTeamSizeChecks.request(bot.id(), size, UUID.randomUUID().toString(), plan);
    }
    private Intent join(MatchSupport.Bot bot, int size) throws RobotException {
        var request = request(bot, size);
        var intent = new Intent(bot, request, bot.mark(), "");
        pending.put(bot.id(), intent); // 未知应答也只能撤销这份原UUID，不能直接换编号重试。
        var response = bot.call(ids.joinQueue(), request, JoinQueueResponse.parser());
        String problem = BattleSmokeChecks.joinAcceptedProblem(response);
        must(problem == null, bot.name + "以独立原UUID入人数档" + Integer.toUnsignedString(size), problem == null ? "已获排队票" : problem);
        intent = new Intent(bot, request, intent.mark(), response.getQueueTicket());
        pending.put(bot.id(), intent);
        return intent;
    }
    private GetQueueStatusResponse status(Intent intent) throws RobotException {
        return intent.bot().call(ids.queueStatus(), GetQueueStatusRequest.newBuilder().setPlayerId(intent.bot().id())
                .setRequestId(intent.request().getRequestId()).build(), GetQueueStatusResponse.parser());
    }
    private void queued(Intent intent, String name) throws RobotException {
        var status = status(intent);
        must(status.getState() == QueueState.QUEUE_STATE_QUEUED && status.getQueueTicket().equals(intent.ticket()) && status.getBattleId() == 0,
                name, "state=" + status.getState() + " battle=" + Long.toUnsignedString(status.getBattleId()));
    }
    private boolean noAssignment(Intent intent) {
        return intent.bot().connection().inbox().snapshot(intent.mark()).stream().noneMatch(r -> r.requestId() == 0
                && (r.messageId() == battleIds.battleAssigned() || r.messageId() == battleIds.battleStart()));
    }
    private void cancel(Intent intent) throws RobotException {
        intent.bot().send(ids.cancelQueue(), CancelQueueRequest.newBuilder().setPlayerId(intent.bot().id())
                .setQueueTicket(intent.ticket()).setRequestId(intent.request().getRequestId()).build());
        long until = System.nanoTime() + tempo.settleTimeout().toNanos();
        String problem;
        do {
            problem = PveTeamSizeChecks.cancelledProblem(status(intent));
            if (problem.isEmpty()) break;
            BattleSupport.sleep(tempo.retryInterval());
        } while (System.nanoTime() < until);
        must(problem.isEmpty(), intent.bot().name + "取消原UUID有永久墓碑且无票", problem);
        pending.remove(intent.bot().id());
    }
    private void fight(List<Intent> intents, int monsters) throws RobotException {
        long battle = 0;
        var players = intents.stream().map(intent -> intent.bot().id()).toList();
        List<BattleSupport.Direct> active = new ArrayList<>();
        for (var intent : intents) {
            var bot = intent.bot();
            var start = MatchSupport.awaitBattle(bot.name, bot.connection(), intent.mark(), battle, battleIds, tempo.battleStartTimeout());
            if (battle == 0) battle = start.battleId();
            String problem = PveTeamSizeChecks.formationProblem(start.start().getState(), players, monsters);
            must(start.battleId() == battle && start.assignedFirst() && problem.isEmpty(),
                    bot.name + "同局177→143：" + players.size() + "人/" + monsters + "怪", problem);
            var direct = MatchSupport.connect(client, bot.name, start.assigned(), battleIds, timeout);
            directs.add(direct);
            active.add(direct);
        }
        battles.add(battle);
        var autos = new ArrayList<MatchSupport.AutoRequest>();
        for (var direct : active) autos.add(MatchSupport.enableAuto(direct, battle, battleIds));
        int outcome = -1;
        for (int i = 0; i < active.size(); i++) {
            var result = MatchSupport.awaitEnd(active.get(i), battle, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autos.get(i));
            if (outcome == -1) outcome = result.end().getOutcomeValue();
            must(result.autoAccepted() && result.turns() > 0 && result.end().getOutcomeValue() == outcome,
                    intents.get(i).bot().name + "自动战斗到相同150终局", "outcome=" + outcome + result.autoNote());
            active.get(i).close();
        }
        // 已收到终局后等权威清票；它与主动取消是两条路径，不把NOT_QUEUED单独当撤销证明。
        for (var intent : intents) {
            long until = System.nanoTime() + tempo.settleTimeout().toNanos();
            GetQueueStatusResponse status;
            do {
                status = status(intent);
                if (status.getState() == QueueState.QUEUE_STATE_NOT_QUEUED) break;
                BattleSupport.sleep(tempo.retryInterval());
            } while (System.nanoTime() < until);
            must(status.getState() == QueueState.QUEUE_STATE_NOT_QUEUED && status.getQueueTicket().isEmpty() && status.getBattleId() == 0,
                    intent.bot().name + "已终局原票释放后才允许下一局", "state=" + status.getState());
            pending.remove(intent.bot().id());
        }
    }
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) throw new RobotException(name + "：" + detail);
    }
}
