package com.game.robot;

import com.game.proto.trade.MarketScope;
import com.game.robot.scenario.AttributeScenario;
import com.game.robot.scenario.AuditScenario;
import com.game.robot.scenario.BagScenario;
import com.game.robot.scenario.BattleEdgeScenario;
import com.game.robot.scenario.BattleScenario;
import com.game.robot.scenario.ChatScenario;
import com.game.robot.scenario.CrossNodeScenario;
import com.game.robot.scenario.CurrencyScenario;
import com.game.robot.scenario.DungeonScenario;
import com.game.robot.scenario.ExpectJump;
import com.game.robot.scenario.FeaturesScenario;
import com.game.robot.scenario.FriendScenario;
import com.game.robot.scenario.GuardScenario;
import com.game.robot.scenario.GuildEconomyScenario;
import com.game.robot.scenario.GuildScenario;
import com.game.robot.scenario.KillSwitchScenario;
import com.game.robot.scenario.MirrorScenario;
import com.game.robot.scenario.MovementScenario;
import com.game.robot.scenario.PetScenario;
import com.game.robot.scenario.RateLimitScenario;
import com.game.robot.scenario.ReconnectScenario;
import com.game.robot.scenario.SkillScenario;
import com.game.robot.scenario.SmokeScenario;
import com.game.robot.scenario.TeamScenario;
import com.game.robot.scenario.TokenScenario;
import com.game.robot.scenario.TradeScenario;
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
 * @param tradeAdminUrl   trade 场景：xm-trade 管理端口（播种接口 {@code POST /admin/trade/seed-listing}）
 * @param tradeScope      trade 场景：期望的市场范围（须与 xm-trade 的 {@code xm.trade.market.scope} 一致；缺省 zone，trade-spec Q9）
 * @param battleAdminUrl  battle / battle-edge 场景：xm-battle 管理端口（dev 接口 {@code POST /admin/battle/dev/*} 与指标）
 * @param expectDevAllowed battle / battle-edge 场景：xm-battle 的 dev 接口开放（allow，dev / test 运行模式）还是回 403（deny，prod）
 * @param slow            battle-edge 场景：也跑慢用例（连上不握手、等 10 s 握手期限）
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
        String tableDir,
        String tradeAdminUrl,
        MarketScope tradeScope,
        String battleAdminUrl,
        boolean expectDevAllowed,
        boolean slow,
        String password) {

    public static final String PASSWORD_ENV = "XM_LOGIN_DEV_PASSWORD";
    /** xm-login 账号列 VARCHAR(64)，超长账号按认证失败拒绝。 */
    static final int MAX_ACCOUNT_CHARS = 64;
    static final int MAX_COUNT = 200;
    private static final long MAX_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final Pattern RUN_TAG = Pattern.compile("[a-z0-9]{1,16}");

    public enum Scenario {
        SMOKE, MOVEMENT, CURRENCY, ATTRIBUTE, AUDIT, GUARD, BAG, FEATURES, SKILL, PET, TOKEN, RECONNECT, ZONES, QUEUE, RATELIMIT, DRAIN, FRIEND, CHAT, KILLSWITCH, TEAM, GUILD,
        /** 子命令写作 {@code guild-economy}（连字符按下划线认）。 */
        GUILD_ECONOMY,
        /** 聚宝斋只读面（196 / 197 / 198 / 200 + 播种）。 */
        TRADE,
        /** 跨节点换场景与归属交接（批次 5.2）；子命令写作 {@code cross-node}，需要两个 scene 节点。 */
        CROSS_NODE,
        /**
         * 镜像场景（批次 5.3，dungeon-mirror-spec §12.6）：A、B、C 三个新号；A 在默认主世界发 63 镜像 → 79 / 43 逐字段、隔离、镜像里再建 3005、
         * B 按号加入、A 断线重连回镜像、只带地图离开回频道、连发 3014、等空置回收后按号进入被拒、表外 M 3005、指标增长。
         * 附加选项 {@code --mirror-config-id / --instance-wait-ms / --expect-mirror-validation / --scene-manager-metrics-url}，
         * scene 指标取 {@code --scene-metrics-url}。
         */
        MIRROR,
        /**
         * 副本（批次 5.3，dungeon-mirror-spec §12.6）：经 xm-scene dev 实例管理口（{@code --scene-admin-url}，运维令牌同 audit）建副本 →
         * A、B 按号进入（79 逐字段、出生点）→ 3008 / 只带副本地图被拒 → 管理口销毁后两人落默认主世界出生点 → 按号再进被拒 → 管理口拒绝码 → 指标；
         * {@code --expect-dev deny} 只核对管理口 403（prod）。
         */
        DUNGEON,
        /** battle 节点（批次 6.2）：经 xm-battle dev 接口建房 → 大厅 177 / 143 → 直连握手 → 提交 / 拉状态 / 挂机 / 收尾 / 观战。 */
        BATTLE,
        /** battle 直连面的负面用例（批次 6.2）；子命令写作 {@code battle-edge}。 */
        BATTLE_EDGE
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
                "握手、登录、建角、进游戏、ListSkills 各自等应答的上限；movement 里也是 A 断开后等 51 的上限；"
                        + "cross-node 里也是 63 受理后等 79 / 23 {3023} 的上限"),
        ENTER_SCENE_TIMEOUT("enter-scene-timeout-ms", "XM_ROBOT_ENTER_SCENE_TIMEOUT_MS", "15000", "发出进游戏后等 79 的上限"),
        OBSERVE_TIMEOUT("observe-timeout-ms", "XM_ROBOT_OBSERVE_TIMEOUT_MS", "2000",
                "movement：每条移动输入后 B 收到 66、跳跃后 A 收到 137、等 21 / 47 的上限；cross-node：等 21 / 47 / 51 / 66 的上限"),
        EXPECT_JUMP("expect-jump", "XM_ROBOT_EXPECT_JUMP", "auto",
                "movement 跳跃检查的期望：auto（纠偏或 fail-open 都接受）/ correct（必须回 137）/ accept（必须原样接受）"),
        EXPECT_GM("expect-gm", "XM_ROBOT_EXPECT_GM", "allow",
                "currency / attribute：服务端运行模式的期望：allow（dev / test，GM 指令生效）/ deny（prod，gate 推 23 {1006} 且不转发）"),
        DATA_URL("data-url", "XM_ROBOT_DATA_URL", "http://127.0.0.1:18106", "audit / guard / zones / queue / drain / killswitch：xm-data 管理端口（运维接口）"),
        SCENE_METRICS_URL("scene-metrics-url", "XM_ROBOT_SCENE_METRICS_URL", "http://127.0.0.1:18104",
                "audit / guard：xm-scene 管理端口（抓指标）"),
        TABLE_DIR("table-dir", "XM_ROBOT_TABLE_DIR", "config-data/tables",
                "reconnect / team：配置表目录（读 World / BaseScene：选第二张世界地图、核对出生点；team 选换图目标）"),
        TRADE_ADMIN_URL("trade-admin-url", "XM_ROBOT_TRADE_ADMIN_URL", "http://127.0.0.1:18111",
                "trade：xm-trade 管理端口（播种接口 POST /admin/trade/seed-listing，运维令牌同 audit）"),
        TRADE_SCOPE("trade-scope", "XM_ROBOT_TRADE_SCOPE", "zone",
                "trade：期望的市场范围 zone / global（须与 xm-trade 的 xm.trade.market.scope 一致，换范围要重启 xm-trade）"),
        MIRROR_CONFIG_ID("mirror-config-id", "XM_ROBOT_MIRROR_CONFIG_ID", "1",
                "mirror / dungeon 子命令（批次 5.3）。mirror：建镜像用的 Mirror 表 id（缺省 Mirror 第一行，main_scene_id = 默认主世界）"),
        INSTANCE_WAIT("instance-wait-ms", "XM_ROBOT_INSTANCE_WAIT_MS", "90000",
                "mirror：等空置镜像被回收（xm.scene.instance.mirror-idle-timeout + reclaim-grace，缺省 30 + 30 s）的上限；切片调短超时后可调小"),
        EXPECT_MIRROR_VALIDATION("expect-mirror-validation", "XM_ROBOT_EXPECT_MIRROR_VALIDATION", "strict",
                "mirror：表外 mirror_config_id 的期望：strict（同步 3005，Java 先查 Mirror 表）/ lenient（{0} + 79，不查表的实现）"),
        SCENE_ADMIN_URL("scene-admin-url", "XM_ROBOT_SCENE_ADMIN_URL", "http://127.0.0.1:18104",
                "dungeon：xm-scene 管理端口（dev 实例管理口 POST /admin/scene/instance/*，运维令牌同 audit；副本建在这个节点上，指标也抓这里）"),
        SCENE_MANAGER_METRICS_URL("scene-manager-metrics-url", "XM_ROBOT_SCENE_MANAGER_METRICS_URL", "http://127.0.0.1:18102",
                "mirror / dungeon：xm-scene-manager 管理端口（抓 xm_scene_manager_instance_seconds）"),
        BATTLE_ADMIN_URL("battle-admin-url", "XM_ROBOT_BATTLE_ADMIN_URL", "http://127.0.0.1:18112",
                "battle / battle-edge：xm-battle 管理端口（dev 接口 POST /admin/battle/dev/*、指标；运维令牌同 audit）"),
        EXPECT_DEV("expect-dev", "XM_ROBOT_EXPECT_DEV", "allow",
                "battle / battle-edge：xm-battle dev 接口的期望：allow（dev / test 运行模式）/ deny（prod：403，只跑不需要建房的步骤）"),
        SLOW("slow", "XM_ROBOT_SLOW", "false",
                "battle-edge：也跑慢用例（连上不握手 10 ± 1 s 被关）；写 --slow 即 true，也接受 --slow true / false");

        /** 布尔开关：命令行可以只写 {@code --名字}（下一个参数不是 true / false 时不吃掉它）。 */
        boolean isFlag() {
            return this == SLOW;
        }

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
                if (value == null && opt.isFlag()) {
                    boolean explicit = i + 1 < args.size() && (args.get(i + 1).equals("true") || args.get(i + 1).equals("false"));
                    value = explicit ? args.get(++i) : "true";
                }
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
            throw new UsageException("缺少子命令（smoke / movement / currency / attribute / audit / guard / bag / features / skill / pet）");
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
        String tradeAdminUrl = stripSlash(value(Opt.TRADE_ADMIN_URL, given, env));
        if (!tradeAdminUrl.startsWith("http://") && !tradeAdminUrl.startsWith("https://")) {
            throw new UsageException("--trade-admin-url 必须以 http:// 或 https:// 开头：" + tradeAdminUrl);
        }
        MarketScope tradeScope = switch (value(Opt.TRADE_SCOPE, given, env)) {
            case "zone" -> MarketScope.MARKET_SCOPE_ZONE;
            case "global" -> MarketScope.MARKET_SCOPE_GLOBAL;
            default -> throw new UsageException("--trade-scope 只能是 zone / global：" + value(Opt.TRADE_SCOPE, given, env));
        };
        String battleAdminUrl = stripSlash(value(Opt.BATTLE_ADMIN_URL, given, env));
        if (!battleAdminUrl.startsWith("http://") && !battleAdminUrl.startsWith("https://")) {
            throw new UsageException("--battle-admin-url 必须以 http:// 或 https:// 开头：" + battleAdminUrl);
        }
        String expectDev = value(Opt.EXPECT_DEV, given, env);
        if (!expectDev.equals("allow") && !expectDev.equals("deny")) {
            throw new UsageException("--expect-dev 只能是 allow / deny：" + expectDev);
        }
        String slow = value(Opt.SLOW, given, env);
        if (!slow.equals("true") && !slow.equals("false")) {
            throw new UsageException("--slow 只能是 true / false：" + slow);
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
            case PET -> PetScenario.accountName(prefix, runTag);
            case TOKEN -> TokenScenario.accountName(prefix, runTag);
            case RECONNECT -> ReconnectScenario.accountName(prefix, runTag);
            case ZONES -> prefix + runTag;
            case QUEUE -> prefix + runTag;
            case RATELIMIT -> RateLimitScenario.accountName(prefix, runTag);
            case DRAIN -> prefix + runTag;
            case FRIEND -> FriendScenario.accountName(prefix, runTag, "a");
            case CHAT -> ChatScenario.accountName(prefix, runTag, "a");
            case KILLSWITCH -> KillSwitchScenario.accountName(prefix, runTag);
            case TEAM -> TeamScenario.accountName(prefix, runTag, "a");
            case GUILD -> GuildScenario.accountName(prefix, runTag, "a");
            case GUILD_ECONOMY -> GuildEconomyScenario.accountName(prefix, runTag, "a");
            case TRADE -> TradeScenario.accountName(prefix, runTag, "a");
            case CROSS_NODE -> CrossNodeScenario.accountName(prefix, runTag, "3");
            case MIRROR -> MirrorScenario.accountName(prefix, runTag, "c");
            case DUNGEON -> DungeonScenario.accountName(prefix, runTag, "b");
            case BATTLE -> BattleScenario.accountName(prefix, runTag, "a");
            case BATTLE_EDGE -> BattleEdgeScenario.accountName(prefix, runTag, "a");
        };
        if (longest.codePointCount(0, longest.length()) > MAX_ACCOUNT_CHARS) {
            throw new UsageException("账号 " + longest + " 超过 " + MAX_ACCOUNT_CHARS + " 个字符，缩短 --prefix / --run-tag");
        }

        return new RobotOptions(scenario, gateway, zone, prefix, count, runTag,
                millis(Opt.CONNECT_TIMEOUT, given, env), millis(Opt.REQUEST_TIMEOUT, given, env),
                millis(Opt.ENTER_SCENE_TIMEOUT, given, env), millis(Opt.OBSERVE_TIMEOUT, given, env),
                expectJump, expectGm.equals("allow"), stripSlash(value(Opt.DATA_URL, given, env)),
                stripSlash(value(Opt.SCENE_METRICS_URL, given, env)), value(Opt.TABLE_DIR, given, env), tradeAdminUrl,
                tradeScope, battleAdminUrl, expectDev.equals("allow"), slow.equals("true"), password);
    }

    /** 帮助文本。 */
    public static String usage() {
        StringBuilder out = new StringBuilder();
        out.append("用法：java -jar xm-robot.jar <smoke|movement|currency|attribute|audit|guard|bag|features|skill|pet|token|reconnect|zones|queue|ratelimit|drain|friend|chat|killswitch|guild|guild-economy|trade|cross-node|mirror|dungeon|battle|battle-edge|team> [选项]\n");
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
        out.append("  pet       宝宝（181–189、184）：GM 发放、池隔离、主人升级带动与推送、自动加点 / 加点 / 拒绝码、出战、"
                + "洗点扣 300 / 改名扣 200（按 54 余额核对）、重登原样、收回；需要 dev 运行模式\n");
        out.append("  token     令牌与 HTTP 登录：/api/login 口令登录拿令牌（错口令 401、未知区 500）→ TCP access token 登录不签新令牌 → "
                + "127 / HTTP 刷新轮换、用过的作废 → 旧 access 仍可用 → 同账号第 4 个未进游戏的连接 2024、断开一个后放行\n");
        out.append("  reconnect 落点：首登落默认主世界 → 63 换图 → 断开立即重连回原实例原位置 → 顶号时旧连接收 23 {2017}、"
                + "新连接回原实例 → LeaveGame 后再进按首登落默认主世界出生点；读 --table-dir 的 World / BaseScene 表\n");
        out.append("  zones     区服目录 / 健康探测 / 公告：经 xm-data 运维接口建临时区（预告 → 维护 → 开放 → 删），核对区服列表与"
                + " assign-gate 跟着变、本区显示负载档；白名单增删；公告只下发生效中的；需要 xm-data 与运维令牌\n");
        out.append("  queue     登录排队：经 xm-data 把本区容量压到 1 → assign-gate 回 100 + 排队令牌 → 轮询仍排 → 放开容量后轮询拿到"
                + " gate 令牌并连 gate → 令牌只取一次（410）；需要排队已打开（XM_GATEWAY_QUEUE_ENABLED）与运维令牌\n");
        out.append("  ratelimit 开服限流：同账号连登第二次 429 ACCOUNT_COOLDOWN、assign-gate 不撞 login 的冷却 → 连发 assign-gate 到 429"
                + " IP_RATE_LIMIT → 等桶补满后照常；需要限流已打开（XM_GATEWAY_RATE_LIMIT_ENABLED，缺省阈值）\n");
        out.append("  drain     gate 排空：经 xm-data 给本区 gate 打排空标记（最后一台不带 force 409）→ 全部排空时 assign-gate 照常 →"
                + " 没人在线时判定为 drained → 撤销；需要运维令牌\n");
        out.append("  friend / chat  好友 / 聊天端到端（两三个机器人互相申请、私聊、拉历史）\n");
        out.append("  killswitch 按方法热关停：经 xm-data 写规则 → 信封 1003、精确豁免、全局 * 豁免、Dubbo 层关停场景分配 → 删规则恢复；"
                + "需要运维令牌。<只在隔离的本机切片上跑>：规则全服共享，演练期间几秒内会关掉整个区的客户端请求与进游戏\n");
        out.append("  team      组队（A / B / D 三个新号 + 一个已登出的 E）：建队 / 申请 / 同意 / 重放 / 邀请 / 拒绝 / 接受 / 踢人 / 转让 / "
                + "拒绝申请与各推送 → A 换图 B 跟随（读 --table-dir 的 World 表）→ 开战 4027（4.3 不开战）→ 解散；"
                + "另钉 4003 / 4005 / 4006 / 4007 / 4013 / 4017、MEMBER_ONLINE、上行 213 不回包；跨区步骤单 zone 时跳过\n");
        out.append("  guild     帮会核心（A / B / D / E / F 五个新号）：建帮落归属区 / 重复建帮 14000 / 本区榜 / 改公告 / 申请 → 审批 → "
                + "各推送 / 伪造 player_id 退帮按会话受理 / 上行 8 与 220 信封 1003 / 任命长老 / 长老踢人边界 / 两次转让 / 退帮 / 解散后 "
                + "14002 / 14007 且删申请；另钉 14009 / 14010 / 14006 / 14001 / 14018 / 14015 / 14014 / 14016 / 14004 / 14005、"
                + "待审数只对管理者可见、榜单页长回显、DonateToGuild(0) 14027；跨区步骤单 zone 时跳过\n");
        out.append("  guild-economy 帮会经济（A / B 两个新号）：建帮 → GM 给 A 发银两 / 灵石 → 大捐两次（资金 / 帮贡）、第三次 14024 → 灵石捐 → "
                + "B 余额不足 14025 + REJECTED → 升级（B 14016、A 升 Lv.2、B 收 LEVEL_UP、旧等级重升不扣钱）→ 商店解锁 → 兑换入包 → "
                + "14029 / 14031 / 14030 → 限购 → 解散；结算中的单轮询到落定，后台终结的推 FUNDS_CHANGED / DELIVERY_DONE；"
                + "需要 xm-guild 资产通道已开、两侧同一个 XM_ASSET_OP_SECRET_GUILD、dev 运行模式\n");
        out.append("  trade     聚宝斋只读面（A / B / C 三个新号）：经 xm-trade 播种接口造种子（market_zone = 卖家归属区）→ 经 gate 发 199 无回包 → "
                + "寄售 / 公示页签与本轮种子逐字段一致 → 单区范围断言（zone_filter 在 zone 下被忽略、global 下生效）→ 页长 50 回显 20、"
                + "超末页钳到末页 → 拍卖 20003 → 详情（不存在 20000、卖家看已结束的、买家 20000）→ 收藏 / 只看收藏 / 取消 → 货架；"
                + "另钉参数非法 1005、LIKE 通配按字面量、按编号搜索。需要 dev 运行模式与运维令牌；--trade-scope 须与 xm-trade 一致，"
                + "跨区步骤单 zone 时跳过\n");
        out.append("  cross-node 跨节点换图（三个新号；需要 XM_SCENE_NODES=2 起两个 scene 节点、per-node 覆盖、每图每节点一个频道）：落位到同图"
                + "两个频道（同场景就登出等 6 s 重登，有上限）→ A 63 {scene_id = B 的场景} 应答 {0}、79 / 新实体号的 21 / 含 B 的 47、"
                + "B 收 A 的 21、留在原场景的 C 收 A 旧实体的 51 → 目标节点上移动（B 收 66）与 77 → 断开重连回原实例原位 → 63 换回 → "
                + "63 不存在的 scene_id 应答 {0} 后 23 {3023} → 连发 63 第二条 3014 → 顶号旧连接 23 {2017} → "
                + "跨节点按号加入镜像（批次 5.3）\n");
        out.append("  mirror    镜像场景（A / B / C 三个新号，批次 5.3）：A 发 63 {mirror_config_id} 应答 {0} 先于 79、79 逐字段、坐标保留、"
                + "源频道的 B 收 51 → 43 / 31 → 镜像存在时新登录不落进镜像 → 镜像里再建 3005 → B 按号加入（creators 仍只有 A）→ "
                + "A 断线重连回镜像 → 只带地图离开回频道 → 连发第二条 3014 → 等空置回收（--instance-wait-ms）后按号进入被拒 → 指标 → "
                + "表外号按 --expect-mirror-validation 核对；scene 指标取 --scene-metrics-url\n");
        out.append("  dungeon   副本（A / B 两个新号，批次 5.3；需要 dev 运行模式与运维令牌）：经 xm-scene dev 实例管理口"
                + "（--scene-admin-url）的拒绝码 → 建副本 → A、B 按号进入（79 带 dungeon_config_id、落副本出生点）→ 3008 / 只带副本地图被拒 → "
                + "管理口销毁后两人落默认主世界出生点、无 23 → 再进被拒 → 指标；--expect-dev deny 只核对管理口 403\n");
        out.append("  battle    battle 节点（A / B / C 三个新号；需要切片带 xm-battle、dev 运行模式与运维令牌）：大厅发 149 回 23 {1003} 不断连 → "
                + "dev 建 PVE 房 → 大厅先 177 后 143 → 凭票直连、握手应答是首帧、补拉 140 → 非法行动 1005 → 合法行动 139 先于应答 → "
                + "同票重连顶替旧连接 → 挂机打到 150 后 FIN、大厅没有 150 → 终局后旧票被拒 → PVP 两人都交才结算、超时 6 s 结算、视角裁剪 → "
                + "补签与开局票逐字节相同、非成员 1005 → 幂等建房不重推 → 销毁只见 FIN → 强制平局 150 DRAW → 观战 161 / 158 / 166、"
                + "165 应答后 FIN 无 166、清退 166 REMOVED → 指标增长；--expect-dev deny 只核对 dev 接口 403 与大厅拒绝\n");
        out.append("  battle-edge battle 直连面负面用例（A / B 两个新号，每条新建连接）：握手前发请求 / 发大厅握手类型 / 坏校验和 → 无回包被关；"
                + "签名翻转 / 大写 / payload 被改 → invalid ticket signature；过期票 → ticket rejected: expired；已验证后 157 → 信封 1005、"
                + "1025 B → 1010、1 秒 4 条 140 → 1008、50 个非法包断开、再握手回原 battle_id、合法请求后跟坏帧先应答再断开；"
                + "--slow 另跑「连上不握手 10 s 被关」\n");
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
                + ", dataUrl=" + dataUrl + ", sceneMetricsUrl=" + sceneMetricsUrl + ", tableDir=" + tableDir
                + ", tradeAdminUrl=" + tradeAdminUrl + ", tradeScope=" + tradeScope + ", battleAdminUrl=" + battleAdminUrl
                + ", expectDevAllowed=" + expectDevAllowed + ", slow=" + slow + ", password=***]";
    }

    private static Scenario parseScenario(String arg) throws UsageException {
        try {
            return Scenario.valueOf(arg.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new UsageException("未知子命令：" + arg + "（只有 smoke / movement / currency / attribute / audit / guard / bag / features / skill / pet / token / reconnect / zones / queue / ratelimit / drain / friend / chat / killswitch / team / guild / guild-economy / trade / cross-node / mirror / dungeon / battle / battle-edge）");
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
