package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.ReleaseSkillResponse;
import com.game.proto.SkillInterruptedS2C;
import com.game.proto.SkillUsedS2C;
import com.game.proto.Vector3;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 放技能端到端（84 ReleaseSkill、70 SkillUsed、33 SkillInterrupted）。两个新号 A、B 落在同一出生点（互相看得见），A 放技能：
 * <ul>
 *   <li>拒绝码：不存在的技能 1001；目标号 0 一律 7001；指定目标技能 1 打不存在的实体 7001；</li>
 *   <li>技能 2（范围）打任意非 0 目标成功，B 收到 70；</li>
 *   <li>技能 1 打 B 成功 → 400ms 后技能 2（不可打断）7000 → ≥800ms 时技能 1 打自己（冷却 500ms 已过、通常仍在前摇 1s 内，
 *       落进后摇同样可打断）打断：
 *       B 先收到 33 {entity=A, skill_table_id=1} 再收到 70；</li>
 *   <li>打断后的新施法：前摇里技能 13 7000、后摇里（1.5s）技能 2 7000、2.3s 后技能 13 成功，再放一次是冷却 7003。</li>
 * </ul>
 * 冷却、后摇是 Java 按设计意图生效的部分（基线线上只有前摇，PARITY「施法阶段与冷却」行）。时刻按发送时刻排：
 * 打断那一步离前摇结束不足 200ms（受发送间隔约束），但落进后摇结果一样；其余各步离阶段边界都留了 ≥ 300ms。
 */
public final class SkillScenario {

    private static final String SERVICE = "SceneSkillClientPlayer";
    private static final String REF = "PARITY「放技能」「施法阶段与冷却」行";
    /** gate 对 84 取缺省限频（每秒 3 条），相邻两次至少隔这么久，且任何 1 秒内不超过 3 条。 */
    private static final Duration MIN_SPACING = Duration.ofMillis(400);
    /** 不存在的实体号（场景实体号是雪花，不会是 1）。 */
    private static final long BOGUS_ENTITY = 1;

    private final PlayerFlow flow;
    private final String accountA;
    private final String accountB;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    private final int releaseSkill;
    private final int notifySkillUsed;
    private final int notifySkillInterrupted;
    private long lastSendNanos;
    /** 上一次发送的时刻（单调时钟毫秒）。 */
    private long lastSentMillis;

    public SkillScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                         Duration requestTimeout, Duration observeTimeout) {
        this.flow = flow;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
        this.releaseSkill = registry.requireId(SERVICE, "ReleaseSkill");
        this.notifySkillUsed = registry.requireId(SERVICE, "NotifySkillUsed");
        this.notifySkillInterrupted = registry.requireId(SERVICE, "NotifySkillInterrupted");
    }

    public static String accountName(String prefix, String runTag, String role) {
        return prefix + "sk" + runTag + role;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        List<GameConnection> opened = new ArrayList<>();
        try {
            EnteredPlayer a = flow.enter(accountA, new Timings());
            opened.add(a.connection());
            ActorCreateS2C selfA = flow.awaitSelfActor(a, observeTimeout);
            EnteredPlayer b = flow.enter(accountB, new Timings());
            opened.add(b.connection());
            ActorCreateS2C selfB = flow.awaitSelfActor(b, observeTimeout);
            if (a.sceneInfo().getSceneId() != b.sceneInfo().getSceneId()) {
                throw new RobotException("A、B 没有分到同一场景实例，无法验证广播；稍后重试或只起一个 scene 节点");
            }
            long entityA = selfA.getEntity();
            long entityB = selfB.getEntity();
            GameConnection c = a.connection();
            GameConnection watcher = b.connection();

            expect(report, release(c, 99, entityB), 1001, "不存在的技能 99");
            for (int skill : new int[] {1, 2, 13}) {
                expect(report, release(c, skill, 0), 7001, "技能 " + skill + " 目标号 0");
            }
            expect(report, release(c, 1, BOGUS_ENTITY), 7001, "指定目标技能 1 打不存在的实体");

            int mark = watcher.inbox().size();
            expect(report, release(c, 2, BOGUS_ENTITY), 0, "范围技能 2 打任意非 0 目标");
            Optional<Received> used = watcher.await(mark, r -> r.messageId() == notifySkillUsed, observeTimeout);
            SkillUsedS2C usedBody = used.isPresent() ? used.get().parseOrNull(SkillUsedS2C.parser()) : null;
            report.check(usedBody != null && usedBody.getEntity() == entityA && usedBody.getSkillTableId() == 2,
                    "B 收到 A 的 70（技能 2）", usedBody == null ? "没收到" : usedBody.toString().replace('\n', ' '), REF);

            expect(report, release(c, 1, entityB), 0, "技能 1 打 B（前摇 1s、后摇 1s、冷却 500ms）");
            long t0 = lastSentMillis;
            expect(report, release(c, 2, entityB, t0 + 400), 7000, "400ms：前摇中技能 2（不可打断）");
            mark = watcher.inbox().size();
            expect(report, release(c, 1, entityA, t0 + 800), 0,
                    "≥800ms（受 400ms 发送间隔约束）：技能 1 打自己打断（冷却已过；通常仍在前摇，落进后摇同样可打断）");
            long t1 = lastSentMillis;
            checkInterruptPushes(report, watcher, mark, entityA);
            expect(report, release(c, 13, BOGUS_ENTITY, t1 + 400), 7000, "打断后的新前摇里技能 13");
            expect(report, release(c, 2, BOGUS_ENTITY, t1 + 1_500), 7000, "1.5s：后摇里技能 2");
            expect(report, release(c, 13, BOGUS_ENTITY, t1 + 2_300), 0, "2.3s：前摇 + 后摇结束后技能 13");
            expect(report, release(c, 13, BOGUS_ENTITY), 7003, "技能 13 冷却 2s 内再放");
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), REF);
        } finally {
            for (GameConnection connection : opened) {
                connection.close();
            }
        }
        return report;
    }

    private void checkInterruptPushes(CheckReport report, GameConnection watcher, int mark, long entityA)
            throws RobotException {
        Optional<Received> interrupted = watcher.await(mark, r -> r.messageId() == notifySkillInterrupted,
                observeTimeout);
        Optional<Received> used = watcher.await(mark, r -> r.messageId() == notifySkillUsed, observeTimeout);
        SkillInterruptedS2C body = interrupted.isPresent()
                ? interrupted.get().parseOrNull(SkillInterruptedS2C.parser()) : null;
        boolean ordered = interrupted.isPresent() && used.isPresent()
                && interrupted.get().index() < used.get().index();
        report.check(body != null && ordered && body.getEntity() == entityA && body.getSkillTableId() == 1
                        && body.getTargetEntity() == 0 && body.getReasonCode() == 0 && body.getSkillId() == 0,
                "B 先收到 33 {entity=A, skill_table_id=1（打断者）} 再收到 70",
                body == null ? "没收到 33" : body.toString().replace('\n', ' ') + " 先于 70=" + ordered, REF);
    }

    private static void expect(CheckReport report, int actual, int expected, String name) {
        report.check(actual == expected, name + " 回 " + expected, "tip=" + actual, REF);
    }

    private int release(GameConnection c, int skill, long target) throws RobotException {
        return release(c, skill, target, 0);
    }

    /**
     * A 放一次技能：不早于 {@code notBeforeMillis}（单调时钟毫秒），且与上一次至少隔 {@link #MIN_SPACING}。
     * 发送时刻记在 {@link #lastSentMillis}。
     *
     * @return 应答的提示码
     */
    private int release(GameConnection c, int skill, long target, long notBeforeMillis) throws RobotException {
        long earliest = Math.max(lastSendNanos + MIN_SPACING.toNanos(), notBeforeMillis * 1_000_000L);
        sleepUntil(earliest);
        lastSendNanos = System.nanoTime();
        lastSentMillis = lastSendNanos / 1_000_000L;
        ReleaseSkillResponse response = c.call(releaseSkill, ReleaseSkillRequest.newBuilder()
                .setSkillTableId(skill)
                .setTargetId(target)
                .setPosition(Vector3.newBuilder().setX(1).setY(0).setZ(1))
                .build(), ReleaseSkillResponse.parser(), requestTimeout);
        return response.getErrorMessage().getId();
    }

    private static void sleepUntil(long deadlineNanos) throws RobotException {
        long wait = deadlineNanos - System.nanoTime();
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofNanos(wait));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
