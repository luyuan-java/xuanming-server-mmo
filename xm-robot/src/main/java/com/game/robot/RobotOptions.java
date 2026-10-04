package com.game.robot;

import com.game.robot.scenario.AttributeScenario;
import com.game.robot.scenario.AuditScenario;
import com.game.robot.scenario.BagScenario;
import com.game.robot.scenario.CurrencyScenario;
import com.game.robot.scenario.ExpectJump;
import com.game.robot.scenario.FeaturesScenario;
import com.game.robot.scenario.GuardScenario;
import com.game.robot.scenario.MovementScenario;
import com.game.robot.scenario.SkillScenario;
import com.game.robot.scenario.SmokeScenario;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 探针的运行参数。取值优先级：命令行 {@code --名字 值}（或 {@code --名字=值}）&gt; 环境变量 &gt; 缺省值。
 * 开发口令只从环境变量 {@value #PASSWORD_ENV} 读（不进命令行、不进 shell 历史、不打印）。
 *
 * @param runTag          移动 / 货币 / 属性场景的账号标签：账号为 {@code 前缀 + mv + 标签 + _a / _b}、{@code 前缀 + cur / at + 标签}；
 *                        缺省按当前时间生成，每次都是新号
 * @param expectGmAllowed 货币场景：服务端运行模式放行 GM 指令（allow）还是拒绝（deny）
 */
public record RobotOptions(
        Scenario scenario,
        String gatewayUrl,
        int zoneId,
        String accountPrefix,
        int count,
        String runTag,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration enterSceneTimeout,
        Duration observeTimeout,
        ExpectJump expectJump,
        boolean expectGmAllowed,
        String dataUrl,
        String sceneMetricsUrl,
        String password) {

    public static final String PASSWORD_ENV = "XM_LOGIN_DEV_PASSWORD";
    /** xm-login 账号列 VARCHAR(64)，超长账号按认证失败拒绝。 */
    static final int MAX_ACCOUNT_CHARS = 64;
    static final int MAX_COUNT = 200;
    private static final long MAX_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final Pattern RUN_TAG = Pattern.compile("[a-z0-9]{1,16}");

    public enum Scenario {
        SMOKE, MOVEMENT, CURRENCY, ATTRIBUTE, AUDIT, GUARD, BAG, FEATURES, SKILL
    }

    /** 可配置项：命令行名、环境变量名、缺省值、说明。 */
    enum Opt {
        GATEWAY("gateway", "XM_ROBOT_GATEWAY", "http://127.0.0.1:18081", "xm-gateway 地址（assign-gate 入口）"),
        ZONE("zone", "XM_ROBOT_ZONE", "1", "区号（assign-gate 的 zone_id，≥ 1）"),
        PREFIX("prefix", "XM_ROBOT_ACCOUNT_PREFIX", "robot_java_", "账号前缀（须在 xm-login 的开发账号前缀白名单里）"),
        COUNT("count", "XM_ROBOT_COUNT", "3", "smoke 的账号数（1–" + MAX_COUNT + "）"),
        RUN_TAG("run-tag", "XM_ROBOT_RUN_TAG", null, "smoke 以外各子命令的账号标签 [a-z0-9]{1,16}；缺省按当前时间生成（每次新号）"),
        CONNECT_TIMEOUT("connect-timeout-ms", "XM_ROBOT_CONNECT_TIMEOUT_MS", "5000", "HTTP 与 TCP 建连超时"),
        REQUEST_TIMEOUT("request-timeout-ms", "XM_ROBOT_REQUEST_TIMEOUT_MS", "15000",
                "握手、登录、建角、进游戏、ListSkills 各自等应答的上限；movement 里也是 A 断开后等 51 的上限"),
        ENTER_SCENE_TIMEOUT("enter-scene-timeout-ms", "XM_ROBOT_ENTER_SCENE_TIMEOUT_MS", "15000", "发出进游戏后等 79 的上限"),
        OBSERVE_TIMEOUT("observe-timeout-ms", "XM_ROBOT_OBSERVE_TIMEOUT_MS", "2000",
                "movement：每条移动输入后 B 收到 66、跳跃后 A 收到 137、等 21 / 47 的上限"),
        EXPECT_JUMP("expect-jump", "XM_ROBOT_EXPECT_JUMP", "auto",
                "movement 跳跃检查的期望：auto（纠偏或 fail-open 都接受）/ correct（必须回 137）/ accept（必须原样接受）"),
        EXPECT_GM("expect-gm", "XM_ROBOT_EXPECT_GM", "allow",
                "currency / attribute：服务端运行模式的期望：allow（dev / test，GM 指令生效）/ deny（prod，gate 推 23 {1006} 且不转发）"),
        DATA_URL("data-url", "XM_ROBOT_DATA_URL", "http://127.0.0.1:18106", "audit / guard：xm-data 管理端口（运维接口）"),
        SCENE_METRICS_URL("scene-metrics-url", "XM_ROBOT_SCENE_METRICS_URL", "http://127.0.0.1:18104",
                "audit / guard：xm-scene 管理端口（抓指标）");

        final String arg;
        final String env;
        final String defaultValue;
        final String help;

        Opt(String arg, String env, String defaultValue, String help) {
            this.arg = arg;
            this.env = env;
            this.defaultValue = defaultValue;
            this.help = help;
        }
    }

    /**
     * @param args      命令行（第一个非选项参数是子命令 smoke / movement）
     * @param env       环境变量
     * @param nowMillis 当前毫秒，用来生成缺省运行标签
     */
    public static RobotOptions parse(List<String> args, Map<String, String> env, long nowMillis) throws UsageException {
        Map<Opt, String> given = new EnumMap<>(Opt.class);
        Scenario scenario = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-h") || arg.equals("--help") || arg.equals("help")) {
                throw UsageException.help();
            }
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                String value = null;
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    value = name.substring(eq + 1);
                    name = name.substring(0, eq);
                }
                Opt opt = byArg(name);
                if (value == null) {
                    if (i + 1 >= args.size()) {
                        throw new UsageException("选项 --" + name + " 缺少取值");
                    }
                    value = args.get(++i);
                }
                given.put(opt, value);
            } else if (scenario == null) {
                scenario = parseScenario(arg);
            } else {
                throw new UsageException("多余的参数：" + arg);
            }
        }
        if (scenario == null) {
            throw new UsageException("缺少子命令（smoke / movement / currency / attribute / audit / guard / bag / features / skill）");
        }

        String gateway = value(Opt.GATEWAY, given, env);
        if (!gateway.startsWith("http://") && !gateway.startsWith("https://")) {
            throw new UsageException("--gateway 必须以 http:// 或 https:// 开头：" + gateway);
        }
        while (gateway.endsWith("/")) {
            gateway = gateway.substring(0, gateway.length() - 1);
        }
        int zone = intValue(Opt.ZONE, given, env, 1, Integer.MAX_VALUE);
        String prefix = value(Opt.PREFIX, given, env);
        if (prefix.isEmpty() || !prefix.equals(prefix.strip()) || prefix.chars().anyMatch(Character::isWhitespace)) {
            throw new UsageException("--prefix 不能为空、不能含空白：\"" + prefix + "\"");
        }
        int count = intValue(Opt.COUNT, given, env, 1, MAX_COUNT);
        String runTag = value(Opt.RUN_TAG, given, env);
        if (runTag == null) {
            runTag = Long.toString(nowMillis, 36);
        }
        if (!RUN_TAG.matcher(runTag).matches()) {
            throw new UsageException("--run-tag 只能是 1–16 个小写字母或数字：" + runTag);
        }
        ExpectJump expectJump;
        try {
            expectJump = ExpectJump.parse(value(Opt.EXPECT_JUMP, given, env));
        } catch (IllegalArgumentException e) {
            throw new UsageException("--expect-jump 只能是 auto / correct / accept：" + value(Opt.EXPECT_JUMP, given, env));
        }
        String expectGm = value(Opt.EXPECT_GM, given, env);
        if (!expectGm.equals("allow") && !expectGm.equals("deny")) {
            throw new UsageException("--expect-gm 只能是 allow / deny：" + expectGm);
        }
        String password = env.get(PASSWORD_ENV);
        if (password == null || password.isEmpty()) {
            throw new UsageException("需要环境变量 " + PASSWORD_ENV + "（与 xm-login 相同的开发口令）");
        }

        String longest = switch (scenario) {
            case SMOKE -> SmokeScenario.accountName(prefix, count);
            case MOVEMENT -> MovementScenario.accountName(prefix, runTag, "a");
            case CURRENCY -> CurrencyScenario.accountName(prefix, runTag);
            case ATTRIBUTE -> AttributeScenario.accountName(prefix, runTag);
            case AUDIT -> AuditScenario.accountName(prefix, runTag);
            case GUARD -> GuardScenario.accountName(prefix, runTag);
            case BAG -> BagScenario.accountName(prefix, runTag);
            case FEATURES -> FeaturesScenario.accountName(prefix, runTag);
            case SKILL -> SkillScenario.accountName(prefix, runTag, "a");
        };
        if (longest.codePointCount(0, longest.length()) > MAX_ACCOUNT_CHARS) {
            throw new UsageException("账号 " + longest + " 超过 " + MAX_ACCOUNT_CHARS + " 个字符，缩短 --prefix / --run-tag");
        }

        return new RobotOptions(scenario, gateway, zone, prefix, count, runTag,
                millis(Opt.CONNECT_TIMEOUT, given, env), millis(Opt.REQUEST_TIMEOUT, given, env),
                millis(Opt.ENTER_SCENE_TIMEOUT, given, env), millis(Opt.OBSERVE_TIMEOUT, given, env),
                expectJump, expectGm.equals("allow"), stripSlash(value(Opt.DATA_URL, given, env)),
                stripSlash(value(Opt.SCENE_METRICS_URL, given, env)), password);
    }

    /** 帮助文本。 */
    public static String usage() {
        StringBuilder out = new StringBuilder();
        out.append("用法：java -jar xm-robot.jar <smoke|movement|currency|attribute|audit|guard|bag|features|skill> [选项]\n");
        out.append("  smoke     N 个账号：登录 → 没角色就建角 → 进游戏 → 79 → ListSkills 非空 → 断开\n");
        out.append("  movement  A、B 同场景：A 移动（134/132/131），B 收 66；A 重登核对位置；超速跳跃负向检查\n");
        out.append("  currency  新号查余额（54）；GM 加 / 扣 / 封禁 / 解封（37/49/94/95）后重登核对余额，"
                + "或按 --expect-gm deny 核对生产模式的拒绝\n");
        out.append("  attribute 属性加点：面板 → GM 设 30 级（先推 170）→ 自动加点 → 确认 → 幂等 / 只增不减 → 开方案扣金币 → "
                + "跨 60 秒冷却切方案 → 重登原样 → 洗点扣金币（约 70 秒）；--expect-gm deny 只核对 GM 设等级被拒\n");
        out.append("  audit     资产流水：GM 加 / 扣（含被拒的）→ 经 xm-data 运维查询核对恰好成功的几笔落库、顺序与字段；"
                + "需要 Kafka、xm-data 与运维令牌（XM_ADMIN_TOKEN 或 run/xm-admin-token）\n");
        out.append("  guard     资产防护：经 xm-data 运维接口全服封禁绑钻 → GM 加绑钻被拒 27005 → 解封恢复；"
                + "一次加钻石 100001 → scene 获取异常告警恰好 +1；需要 Redis、xm-data 与运维令牌\n");
        out.append("  bag       背包（191/192）：四个包的空包布局、非法 bag_type 回 1005、不可整理的包回 1005、整理幂等、"
                + "背包里的货币同 54、重登原样\n");
        out.append("  features  任务 / 活动（193/194/195/190）：新号列表与不可接原因、三个未排期活动、接取拒绝码、接 12 进行中、"
                + "同类型 5000、未完成领奖 5002、重登列表原样\n");
        out.append("  skill     放技能（84/70/33）：A、B 两个新号同场景，拒绝码 1001 / 7001、70 广播、前摇 7000、打断推 33、"
                + "后摇 7000、冷却 7003（约 8 秒）\n");
        out.append("必需环境变量：").append(PASSWORD_ENV).append("（开发口令，不接受命令行传入）\n");
        out.append("选项（命令行优先于环境变量）：\n");
        for (Opt opt : Opt.values()) {
            out.append(String.format(Locale.ROOT, "  --%-24s %-32s %s%s%n", opt.arg + " <值>", opt.env, opt.help,
                    opt.defaultValue == null ? "" : "，缺省 " + opt.defaultValue));
        }
        out.append("退出码：0 = 全部检查通过；1 = 有检查失败或流程中断；2 = 参数错误\n");
        return out.toString();
    }

    /** 口令不进日志。 */
    @Override
    public String toString() {
        return "RobotOptions[scenario=" + scenario + ", gateway=" + gatewayUrl + ", zone=" + zoneId + ", prefix="
                + accountPrefix + ", count=" + count + ", runTag=" + runTag + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout + ", enterSceneTimeout=" + enterSceneTimeout
                + ", observeTimeout=" + observeTimeout + ", expectJump=" + expectJump + ", expectGmAllowed=" + expectGmAllowed
                + ", dataUrl=" + dataUrl + ", sceneMetricsUrl=" + sceneMetricsUrl
                + ", password=***]";
    }

    private static Scenario parseScenario(String arg) throws UsageException {
        try {
            return Scenario.valueOf(arg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException("未知子命令：" + arg + "（只有 smoke / movement / currency / attribute / audit / guard / bag / features / skill）");
        }
    }

    private static Opt byArg(String name) throws UsageException {
        for (Opt opt : Opt.values()) {
            if (opt.arg.equals(name)) {
                return opt;
            }
        }
        throw new UsageException("未知选项：--" + name);
    }

    private static String stripSlash(String url) {
        String out = url;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String value(Opt opt, Map<Opt, String> given, Map<String, String> env) {
        String value = given.get(opt);
        if (value == null) {
            value = env.get(opt.env);
        }
        if (value == null || value.isBlank()) {
            value = opt.defaultValue;
        }
        return value == null ? null : value.strip();
    }

    private static int intValue(Opt opt, Map<Opt, String> given, Map<String, String> env, int min, int max)
            throws UsageException {
        String text = value(opt, given, env);
        try {
            int v = Integer.parseInt(text);
            if (v < min || v > max) {
                throw new UsageException("--" + opt.arg + " 应在 " + min + "–" + max + " 之间：" + text);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + opt.arg + " 不是整数：" + text);
        }
    }

    private static Duration millis(Opt opt, Map<Opt, String> given, Map<String, String> env) throws UsageException {
        String text = value(opt, given, env);
        try {
            long v = Long.parseLong(text);
            if (v <= 0 || v > MAX_TIMEOUT_MS) {
                throw new UsageException("--" + opt.arg + " 应在 1–" + MAX_TIMEOUT_MS + " 毫秒之间：" + text);
            }
            return Duration.ofMillis(v);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + opt.arg + " 不是整数毫秒：" + text);
        }
    }
}
