package com.game.robot;

import com.game.contract.MessageIdRegistry;
import com.game.robot.client.AdminClient;
import com.game.robot.client.LoginHttpClient;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.scenario.AttributeScenario;
import com.game.robot.scenario.AuditScenario;
import com.game.robot.scenario.BagScenario;
import com.game.robot.scenario.CheckReport;
import com.game.robot.scenario.CurrencyScenario;
import com.game.robot.scenario.FeaturesScenario;
import com.game.robot.scenario.GuardScenario;
import com.game.robot.scenario.MovementScenario;
import com.game.robot.scenario.PetScenario;
import com.game.robot.scenario.SkillScenario;
import com.game.robot.scenario.SmokeScenario;
import com.game.robot.scenario.TokenScenario;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * 端到端探针入口：{@code java -jar xm-robot.jar <smoke|movement|currency|attribute|audit> [选项]}。
 * 打印中文汇总；退出码 0 = 全部检查通过，1 = 有检查失败或流程中断，2 = 参数错误。
 * 探针只是客户端：不启动、不停止任何服务端进程。
 */
public final class RobotMain {

    static final int EXIT_PASS = 0;
    static final int EXIT_FAIL = 1;
    static final int EXIT_USAGE = 2;

    private RobotMain() {
    }

    public static void main(String[] args) {
        System.exit(run(List.of(args), System.getenv(), System.out, System.err));
    }

    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        RobotOptions options;
        try {
            options = RobotOptions.parse(args, env, System.currentTimeMillis());
        } catch (UsageException e) {
            if (e.isHelp()) {
                out.print(RobotOptions.usage());
                return EXIT_PASS;
            }
            err.println("参数错误：" + e.getMessage());
            err.print(RobotOptions.usage());
            return EXIT_USAGE;
        }

        try {
            MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
            MessageIds ids = MessageIds.resolve(registry);
            try (RobotClient client = new RobotClient(options.gatewayUrl(), options.zoneId(), ids,
                    options.connectTimeout(), options.requestTimeout())) {
                PlayerFlow flow = new PlayerFlow(client, options.password(), options.requestTimeout(),
                        options.enterSceneTimeout());
                String target = "gateway=" + options.gatewayUrl() + " zone=" + options.zoneId();
                CheckReport report;
                String title;
                if (options.scenario() == RobotOptions.Scenario.SMOKE) {
                    title = "xm-robot smoke：" + options.count() + " 个账号（前缀 " + options.accountPrefix() + "），" + target;
                    out.println("== " + title + " 开始 ==");
                    report = new SmokeScenario(flow, options.accountPrefix(), options.count()).run(out);
                } else if (options.scenario() == RobotOptions.Scenario.AUDIT) {
                    AuditScenario scenario = new AuditScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.requestTimeout(), options.dataUrl(), options.sceneMetricsUrl(),
                            AuditScenario.resolveAdminToken(env.get("XM_ADMIN_TOKEN")));
                    title = "xm-robot audit：" + scenario.account() + "，" + target + " data=" + options.dataUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.GUARD) {
                    GuardScenario scenario = new GuardScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.requestTimeout(), new AdminClient(options.dataUrl(),
                            AdminClient.resolveToken(env.get("XM_ADMIN_TOKEN")), options.requestTimeout()),
                            options.sceneMetricsUrl());
                    title = "xm-robot guard：" + scenario.account() + "，" + target + " data=" + options.dataUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.TOKEN) {
                    TokenScenario scenario = new TokenScenario(client,
                            new LoginHttpClient(options.gatewayUrl(), options.connectTimeout()), registry,
                            options.zoneId(), options.password(), options.accountPrefix(), options.runTag(),
                            options.requestTimeout());
                    title = "xm-robot token：" + scenario.account() + "，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.PET) {
                    PetScenario scenario = new PetScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.requestTimeout(), options.observeTimeout());
                    title = "xm-robot pet：" + scenario.account() + "，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.SKILL) {
                    SkillScenario scenario = new SkillScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.requestTimeout(), options.observeTimeout());
                    title = "xm-robot skill：" + scenario.accountA() + " 等，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.FEATURES) {
                    FeaturesScenario scenario = new FeaturesScenario(flow, registry, options.accountPrefix(),
                            options.runTag(), options.requestTimeout());
                    title = "xm-robot features：" + scenario.account() + "，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.BAG) {
                    BagScenario scenario = new BagScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.requestTimeout());
                    title = "xm-robot bag：" + scenario.account() + "，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.ATTRIBUTE) {
                    AttributeScenario scenario = new AttributeScenario(flow, registry, ids.sendTip(),
                            options.accountPrefix(), options.runTag(), options.expectGmAllowed(),
                            options.requestTimeout(), options.observeTimeout());
                    title = "xm-robot attribute：" + scenario.account() + "（期望 GM "
                            + (options.expectGmAllowed() ? "放行" : "拒绝") + "），" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.CURRENCY) {
                    CurrencyScenario scenario = new CurrencyScenario(flow, registry, ids.sendTip(),
                            options.accountPrefix(), options.runTag(), options.expectGmAllowed(),
                            options.requestTimeout(), options.observeTimeout());
                    title = "xm-robot currency：" + scenario.account() + "（期望 GM "
                            + (options.expectGmAllowed() ? "放行" : "拒绝") + "），" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else {
                    MovementScenario scenario = new MovementScenario(flow, ids, options.accountPrefix(),
                            options.runTag(), options.observeTimeout(), options.requestTimeout(), options.expectJump());
                    title = "xm-robot movement：A=" + scenario.accountA() + " B=" + scenario.accountB() + "，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                }
                out.print(report.render(title));
                out.flush();
                return report.passed() ? EXIT_PASS : EXIT_FAIL;
            }
        } catch (RuntimeException e) {
            err.println("探针内部异常：" + e);
            e.printStackTrace(err);
            return EXIT_FAIL;
        }
    }
}
