package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.robot.RobotOptions;
import com.game.robot.TravelOptions;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.RedirectTarget;
import com.game.robot.client.RobotClient;
import com.game.robot.flow.PlayerFlow;

/**
 * 跨 zone 传送场景（批次 5.4，zone-travel-spec §11.11；基线 {@code robot/travel_smoke_scenario.go}）。三个子命令共用这一个类：
 * <ul>
 *   <li>{@code travel}（{@link Mode#ROUND_TRIP}）：从 {@code --zone} 传送到 {@code --visit-zone} 再回来，结果行 {@code TRAVEL_SMOKE_OK …}；</li>
 *   <li>{@code travel-abandon}（{@link Mode#ABANDON}）：拿到 124 不跟随，分别经两个区的入口重登；</li>
 *   <li>{@code travel-long}（{@link Mode#LONG}）：在访客区断线、等过重连租约，再经出发区的入口重登。</li>
 * </ul>
 *
 * <p><b>现在只有骨架</b>：子命令、选项、两个区的客户端与登录流程都已接好（{@code RobotMain}），步骤还没有写——{@link #run} 以
 * {@code step=not-implemented} 失败，免得「什么都没验」被当成通过。客户端件在别处已经就绪：解 124（{@link RedirectTarget}）、
 * 跟随（{@code RedirectFollower}）、严格重登与「79 或 124」（{@link PlayerFlow}）、不握手的裸连接与不抛异常的握手
 * （{@link RobotClient#openRaw}、{@code GameConnection.tryVerifyToken}）。
 */
public final class TravelScenario {

    static final String REF = "zone-travel-spec §11.11";
    /** 226 所在的服务与方法；124 的见 {@link RedirectTarget#messageId}。两个号都按名字解析，不写死数字。 */
    static final String TRAVEL_SERVICE = "SceneSceneClientPlayer";
    static final String TRAVEL_METHOD = "TravelToZone";

    /** 子模式，各有自己的结果行标记（大写字母与下划线，外层脚本按子串匹配）。 */
    public enum Mode {
        /** 子命令 {@code travel}。 */
        ROUND_TRIP("TRAVEL_SMOKE"),
        /** 子命令 {@code travel-abandon}。 */
        ABANDON("TRAVEL_ABANDON"),
        /** 子命令 {@code travel-long}。 */
        LONG("TRAVEL_LONG");

        private final String marker;

        Mode(String marker) {
            this.marker = marker;
        }

        /** 结果行的前缀：{@code <标记>_OK …} / {@code <标记>_FAIL step=… reason=…}。 */
        public String marker() {
            return marker;
        }

        /** 子命令对应的子模式。 */
        public static Mode of(RobotOptions.Scenario scenario) {
            return switch (scenario) {
                case TRAVEL -> ROUND_TRIP;
                case TRAVEL_ABANDON -> ABANDON;
                case TRAVEL_LONG -> LONG;
                default -> throw new IllegalArgumentException("不是 travel 的子命令：" + scenario);
            };
        }
    }

    private final Mode mode;
    private final RobotClient homeClient;
    private final PlayerFlow homeFlow;
    private final RobotClient visitClient;
    private final PlayerFlow visitFlow;
    private final GatewayHttp gateway;
    private final RobotOptions options;
    private final TravelOptions travel;
    private final int travelToZone;
    private final int redirectToGate;
    private final String accountA;
    private final CheckReport report = new CheckReport();
    private final StepTrack steps;

    /**
     * @param homeClient  {@code --zone}（出发区，也是新号的归属区）的客户端
     * @param visitClient {@code --visit-zone}（访客区）的客户端
     * @param gateway     xm-gateway 的 HTTP 接口（区服列表）
     * @param options     全部通用选项：子命令（定子模式）、两个区号、账号前缀与标签、各种超时、配置表目录
     * @param travel      travel 的附加选项（停留、去程地图）
     */
    public TravelScenario(RobotClient homeClient, PlayerFlow homeFlow, RobotClient visitClient, PlayerFlow visitFlow, GatewayHttp gateway,
                          MessageIdRegistry registry, RobotOptions options, TravelOptions travel) {
        if (options.zoneId() == options.visitZoneId()) {
            throw new IllegalArgumentException("travel 的两个区不能相同：" + options.zoneId());
        }
        this.mode = Mode.of(options.scenario());
        this.homeClient = homeClient;
        this.homeFlow = homeFlow;
        this.visitClient = visitClient;
        this.visitFlow = visitFlow;
        this.gateway = gateway;
        this.options = options;
        this.travel = travel;
        this.travelToZone = registry.requireId(TRAVEL_SERVICE, TRAVEL_METHOD);
        this.redirectToGate = RedirectTarget.messageId(registry);
        this.accountA = accountName(options.accountPrefix(), options.runTag(), "a");
        this.steps = new StepTrack(mode.marker());
    }

    /**
     * 账号：{@code 前缀 + tv + 标签 + _ + 后缀}（每轮新号、首登即在出发区建角，所以归属区一定是出发区）。三个子模式共用 {@code tv}，
     * 靠后缀区分；后缀取<b>一个字符</b>——{@code RobotOptions} 按它核对账号不超长。
     */
    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "tv" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public Mode mode() {
        return mode;
    }

    public CheckReport run() {
        steps.step("not-implemented", report);
        report.fail("travel 场景的步骤还没有实现", "子命令、选项（--visit-zone " + options.visitZoneId() + "、--dwell-ms "
                + travel.dwell().toMillis() + "、--travel-scene-config " + travel.sceneConfigId() + "）与客户端件已经接好；"
                + "步骤 T0–T11 与两个子模式尚未落地，不能把它当成通过", REF);
        return report;
    }

    /** 结果行（跑完 {@link #run} 之后取）：{@code TRAVEL_SMOKE_OK …} 或 {@code TRAVEL_SMOKE_FAIL step=… reason=…}（子模式换成各自的标记）。 */
    public String resultLine() {
        return steps.line(report, "");
    }
}
