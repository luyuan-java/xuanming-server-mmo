package com.game.robot.scenario;

import com.game.proto.ListSkillsResponse;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 冒烟：N 个账号并发走「登录 → 没角色就建角 → 进游戏 → 15s 内收到带 scene_info 的 79 → ListSkills 非空 → 断开」
 * （robot 契约 §0、§4 步骤 A–F；scene 契约 §3.1 与「Java 必须做到」ListSkills 条）。
 * 与 Go robot_smoke 的差异：判定看每一步的应答而不是统计行，退出码即结论；不跑 AI 循环；断开只关 TCP
 * （TCP 关闭才是权威的离线信号，robot 契约 §7.1）。
 */
public final class SmokeScenario {

    public static final String STEP_SKILLS = "技能";
    public static final String STEP_TOTAL = "总计";

    /** 账号之间的启动间隔（Go robot 50ms，robot 契约 §8）。 */
    private static final long STAGGER_MILLIS = 50;

    private static final List<String> COLUMNS = List.of(PlayerFlow.STEP_ASSIGN, PlayerFlow.STEP_CONNECT,
            PlayerFlow.STEP_LOGIN, PlayerFlow.STEP_CREATE, PlayerFlow.STEP_ENTER, PlayerFlow.STEP_SCENE, STEP_SKILLS,
            STEP_TOTAL);

    private final PlayerFlow flow;
    private final String accountPrefix;
    private final int count;

    public SmokeScenario(PlayerFlow flow, String accountPrefix, int count) {
        this.flow = flow;
        this.accountPrefix = accountPrefix;
        this.count = count;
    }

    /** 账号名：前缀 + 4 位序号，从 1 开始（与 Go robot 的 {@code robot_%04d} 同形，robot 契约 §1.1）。 */
    public static String accountName(String prefix, int index) {
        return prefix + String.format(Locale.ROOT, "%04d", index);
    }

    /** 一个账号的结果；{@code failure} 为 null 即通过。 */
    record AccountResult(String account, long playerId, long sceneId, int sceneConfigId, int skills, boolean created,
                         Timings timings, String failure) {
    }

    public CheckReport run(PrintStream out) {
        List<AccountResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<AccountResult>> futures = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                String account = accountName(accountPrefix, i);
                long delay = (i - 1) * STAGGER_MILLIS;
                futures.add(executor.submit(() -> {
                    Thread.sleep(delay);
                    return runOne(account);
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                results.add(await(futures.get(i), accountName(accountPrefix, i + 1)));
            }
        }

        CheckReport report = new CheckReport();
        out.println("-- 各账号耗时（毫秒；\"-\" 表示没走到或不需要这一步）--");
        for (AccountResult r : results) {
            out.println(formatRow(r));
            if (r.failure() == null) {
                report.pass(r.account() + " 登录进场与技能列表",
                        "player_id=" + r.playerId() + (r.created() ? "（新建角）" : "") + " scene_config_id="
                                + r.sceneConfigId() + " scene_id=" + r.sceneId() + " 技能 " + r.skills() + " 个",
                        "robot 契约 §4 步骤 A–F；scene 契约 §3.1、ListSkills 条");
            } else {
                report.fail(r.account() + " 登录进场与技能列表", r.failure(), "robot 契约 §4 步骤 A–F；scene 契约 §3.1、ListSkills 条");
            }
        }
        return report;
    }

    private AccountResult runOne(String account) {
        Timings timings = new Timings();
        long start = System.nanoTime();
        EnteredPlayer player = null;
        try {
            player = flow.enter(account, timings);
            long skillsStart = System.nanoTime();
            ListSkillsResponse skills = flow.listSkills(player);
            timings.record(STEP_SKILLS, skillsStart);
            // 成功时基线也带 error_message（id=0 的空子消息，scene 契约 §0.3），所以只看 id。
            if (skills.hasErrorMessage() && skills.getErrorMessage().getId() != 0) {
                throw new RobotException("ListSkills（77）被拒：error_message.id=" + skills.getErrorMessage().getId());
            }
            if (!skills.hasSkillList() || skills.getSkillList().getSkillListCount() == 0) {
                throw new RobotException("ListSkills（77）的 skill_list 为空（新号应有 Class 表技能，scene 契约「Java 必须做到」ListSkills 条）");
            }
            if (!player.connection().isOpen()) {
                throw new RobotException("流程走完前连接被关闭：" + player.connection().inbox().closedReason());
            }
            timings.record(STEP_TOTAL, start);
            return new AccountResult(account, player.playerId(), player.sceneInfo().getSceneId(),
                    player.sceneInfo().getSceneConfigId(), skills.getSkillList().getSkillListCount(), player.created(),
                    timings, null);
        } catch (RobotException e) {
            timings.record(STEP_TOTAL, start);
            return new AccountResult(account, player == null ? 0 : player.playerId(), 0, 0, 0, false, timings,
                    e.getMessage());
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
    }

    private static AccountResult await(Future<AccountResult> future, String account) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            return new AccountResult(account, 0, 0, 0, 0, false, new Timings(), "探针内部异常：" + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AccountResult(account, 0, 0, 0, 0, false, new Timings(), "等待被中断");
        }
    }

    static String formatRow(AccountResult r) {
        StringBuilder row = new StringBuilder();
        row.append(r.account()).append("  ").append(r.failure() == null ? "通过" : "失败");
        for (String column : COLUMNS) {
            Long ms = r.timings().get(column);
            row.append("  ").append(column).append('=').append(ms == null ? "-" : ms.toString());
        }
        return row.toString();
    }
}
