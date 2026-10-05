package com.game.robot;

import com.game.contract.MessageIdRegistry;
import com.game.robot.client.AdminClient;
import com.game.robot.client.BattleAdminClient;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.LoginHttpClient;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.client.TradeAdminClient;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.scenario.AttributeScenario;
import com.game.robot.scenario.AuditScenario;
import com.game.robot.scenario.BagScenario;
import com.game.robot.scenario.BattleEdgeScenario;
import com.game.robot.scenario.BattleScenario;
import com.game.robot.scenario.ChatScenario;
import com.game.robot.scenario.CheckReport;
import com.game.robot.scenario.CrossNodeScenario;
import com.game.robot.scenario.CurrencyScenario;
import com.game.robot.scenario.DrainScenario;
import com.game.robot.scenario.FeaturesScenario;
import com.game.robot.scenario.FriendScenario;
import com.game.robot.scenario.GuardScenario;
import com.game.robot.scenario.GuildEconomyScenario;
import com.game.robot.scenario.GuildScenario;
import com.game.robot.scenario.KillSwitchScenario;
import com.game.robot.scenario.MovementScenario;
import com.game.robot.scenario.PetScenario;
import com.game.robot.scenario.QueueScenario;
import com.game.robot.scenario.RateLimitScenario;
import com.game.robot.scenario.ReconnectScenario;
import com.game.robot.scenario.SkillScenario;
import com.game.robot.scenario.SmokeScenario;
import com.game.robot.scenario.TeamScenario;
import com.game.robot.scenario.TokenScenario;
import com.game.robot.scenario.TradeScenario;
import com.game.robot.scenario.ZonesScenario;
import java.io.PrintStream;
import java.nio.file.Path;
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
                } else if (options.scenario() == RobotOptions.Scenario.QUEUE) {
                    QueueScenario scenario = new QueueScenario(client, new AdminClient(options.dataUrl(),
                            AdminClient.resolveToken(env.get("XM_ADMIN_TOKEN")), options.requestTimeout()),
                            new GatewayHttp(options.gatewayUrl(), options.requestTimeout()), options.zoneId());
                    title = "xm-robot queue：区 " + options.zoneId() + "，" + target + " data=" + options.dataUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.KILLSWITCH) {
                    KillSwitchScenario scenario = new KillSwitchScenario(flow, new AdminClient(options.dataUrl(),
                            AdminClient.resolveToken(env.get("XM_ADMIN_TOKEN")), options.requestTimeout()), registry,
                            options.accountPrefix(), options.runTag());
                    title = "xm-robot killswitch：" + scenario.account() + "，" + target + " data=" + options.dataUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.CHAT) {
                    ChatScenario scenario = new ChatScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.requestTimeout());
                    title = "xm-robot chat：" + scenario.accountA() + " 等，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.TEAM) {
                    TeamScenario scenario = new TeamScenario(flow, registry, Path.of(options.tableDir()),
                            options.accountPrefix(), options.runTag(), options.zoneId(), options.requestTimeout());
                    title = "xm-robot team：" + scenario.accountA() + " 等，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.GUILD) {
                    GuildScenario scenario = new GuildScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.zoneId(), options.requestTimeout());
                    title = "xm-robot guild：" + scenario.accountA() + " 等，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.GUILD_ECONOMY) {
                    GuildEconomyScenario scenario = new GuildEconomyScenario(flow, registry, options.accountPrefix(),
                            options.runTag(), options.requestTimeout());
                    title = "xm-robot guild-economy：" + scenario.accountA() + " 等，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.TRADE) {
                    TradeScenario scenario = new TradeScenario(flow, registry, new TradeAdminClient(options.tradeAdminUrl(),
                            AdminClient.resolveToken(env.get("XM_ADMIN_TOKEN")), options.requestTimeout()),
                            options.accountPrefix(), options.runTag(), options.zoneId(), options.tradeScope(),
                            options.requestTimeout());
                    title = "xm-robot trade：" + scenario.accountA() + " 等（期望范围 "
                            + TradeScenario.scopeName(options.tradeScope().getNumber()) + "），" + target
                            + " trade-admin=" + options.tradeAdminUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.FRIEND) {
                    FriendScenario scenario = new FriendScenario(flow, registry, options.accountPrefix(), options.runTag(),
                            options.requestTimeout());
                    title = "xm-robot friend：" + scenario.accountA() + " 等，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.DRAIN) {
                    DrainScenario scenario = new DrainScenario(new AdminClient(options.dataUrl(),
                            AdminClient.resolveToken(env.get("XM_ADMIN_TOKEN")), options.requestTimeout()),
                            new GatewayHttp(options.gatewayUrl(), options.requestTimeout()), options.zoneId());
                    title = "xm-robot drain：区 " + options.zoneId() + "，" + target + " data=" + options.dataUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.RATELIMIT) {
                    RateLimitScenario scenario = new RateLimitScenario(new GatewayHttp(options.gatewayUrl(),
                            options.requestTimeout()), options.zoneId(), options.accountPrefix(), options.runTag());
                    title = "xm-robot ratelimit：" + scenario.account() + "，" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.ZONES) {
                    ZonesScenario scenario = new ZonesScenario(new AdminClient(options.dataUrl(),
                            AdminClient.resolveToken(env.get("XM_ADMIN_TOKEN")), options.requestTimeout()),
                            options.gatewayUrl(), options.zoneId(), options.runTag(), options.requestTimeout());
                    title = "xm-robot zones：临时区 " + scenario.tempZone() + "，" + target + " data=" + options.dataUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.CROSS_NODE) {
                    CrossNodeScenario scenario = new CrossNodeScenario(flow, ids, registry, options.accountPrefix(),
                            options.runTag(), options.requestTimeout(), options.observeTimeout());
                    title = "xm-robot cross-node：" + scenario.firstAccount() + " 等（需要两个 scene 节点），" + target;
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.BATTLE) {
                    BattleScenario scenario = new BattleScenario(client, flow, registry, battleAdmin(options, env), options.accountPrefix(),
                            options.runTag(), options.expectDevAllowed(), options.requestTimeout());
                    title = "xm-robot battle：" + scenario.accountA() + " 等（期望 dev 接口 " + (options.expectDevAllowed() ? "开放" : "403")
                            + "），" + target + " battle-admin=" + options.battleAdminUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.BATTLE_EDGE) {
                    BattleEdgeScenario scenario = new BattleEdgeScenario(client, flow, registry, battleAdmin(options, env),
                            options.accountPrefix(), options.runTag(), options.expectDevAllowed(), options.slow(), options.requestTimeout());
                    title = "xm-robot battle-edge：" + scenario.accountA() + " 等（期望 dev 接口 " + (options.expectDevAllowed() ? "开放" : "403")
                            + (options.slow() ? "，含慢用例" : "") + "），" + target + " battle-admin=" + options.battleAdminUrl();
                    out.println("== " + title + " 开始 ==");
                    report = scenario.run();
                } else if (options.scenario() == RobotOptions.Scenario.RECONNECT) {
                    ReconnectScenario scenario = new ReconnectScenario(flow, ids, registry, Path.of(options.tableDir()),
                            options.accountPrefix(), options.runTag(), options.requestTimeout(), options.observeTimeout());
                    title = "xm-robot reconnect：" + scenario.account() + "，" + target;
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

    /** xm-battle dev 接口客户端（运维令牌同 audit：XM_ADMIN_TOKEN 或 run/xm-admin-token）。 */
    private static BattleAdminClient battleAdmin(RobotOptions options, Map<String, String> env) {
        return new BattleAdminClient(options.battleAdminUrl(), AdminClient.resolveToken(env.get("XM_ADMIN_TOKEN")), options.requestTimeout());
    }
}
