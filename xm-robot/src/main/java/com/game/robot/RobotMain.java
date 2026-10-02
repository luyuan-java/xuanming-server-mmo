package com.game.robot;

import com.game.contract.MessageIdRegistry;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.scenario.CheckReport;
import com.game.robot.scenario.CurrencyScenario;
import com.game.robot.scenario.MovementScenario;
import com.game.robot.scenario.SmokeScenario;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * 端到端探针入口：{@code java -jar xm-robot.jar <smoke|movement|currency> [选项]}。
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
