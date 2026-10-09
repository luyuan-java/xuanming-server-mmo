package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.RobotException;
import java.util.Locale;

/**
 * team / guild / trade 的<b>跨区步骤</b>（另一个号经 {@code --visit-zone} 登录）跑不跑的统一判定（批次 5.4，裁决 J6）。
 *
 * <p>为什么要显式三态而不是「看区服列表自动决定」：自动判定在切片没起对（只起了一个区）时会静默跳过，结果看起来是绿的。
 * 所以给一个开关 {@code --cross-zone auto|require|skip}：
 * <ul>
 *   <li>{@link Mode#AUTO}（缺省）：{@code --visit-zone} 与 {@code --zone} 不同、且它在区服列表里是 OPEN 才跑；否则跳过并给出原因；</li>
 *   <li>{@link Mode#REQUIRE}（验收用）：同样的条件，不满足即<b>失败</b>；</li>
 *   <li>{@link Mode#SKIP}：不跑，也不去读区服列表。</li>
 * </ul>
 * 跑没跑要写进报告与结果行：{@link Decision#field()} 给结果行用（纯 ASCII），{@link Decision#reason()} 给报告用。
 *
 * <p>本类只回答「该不该跑」。真跑之前场景还要自己核对一条前置：经 {@code --visit-zone} 登录的那个号，角色列表里的归属区
 * （{@code EnteredPlayer.homeZoneId()}）必须等于 {@code --visit-zone}——建角取会话 zone（X16）没生效时它会是 login 进程的区，
 * 这时硬跑跨区断言会假失败、还会造出脏数据。
 */
public final class CrossZoneGate {

    /** 区服列表里表示「开着」的显示状态（手工状态叠加健康探测：没有 gate 的区显示 MAINTENANCE）。 */
    static final String OPEN = "OPEN";

    private CrossZoneGate() {
    }

    /** {@code --cross-zone} 的取值。 */
    public enum Mode {
        AUTO, REQUIRE, SKIP;

        /** 命令行写法（小写）。 */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 按命令行写法找；不认识返回 null。 */
        public static Mode ofWire(String text) {
            for (Mode mode : values()) {
                if (mode.wire().equals(text)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** 判定结果。 */
    public sealed interface Decision permits Run, Skip, Fail {

        /** 跨区步骤要不要执行。 */
        default boolean runs() {
            return this instanceof Run;
        }

        /** 没跑 / 失败的原因（中文，进报告）；要跑时为空串。 */
        String reason();

        /** 结果行里的字段：{@code cross_zone=run} / {@code cross_zone=skip} / {@code cross_zone=fail}。 */
        String field();
    }

    /** 执行跨区步骤。 */
    public record Run() implements Decision {

        @Override
        public String reason() {
            return "";
        }

        @Override
        public String field() {
            return "cross_zone=run";
        }
    }

    /** 不执行，场景照常往下走；原因记进报告的观察记录。 */
    public record Skip(String reason) implements Decision {

        @Override
        public String field() {
            return "cross_zone=skip";
        }
    }

    /** {@code require} 而跑不了：场景记一条失败的检查。 */
    public record Fail(String reason) implements Decision {

        @Override
        public String field() {
            return "cross_zone=fail";
        }
    }

    /**
     * 纯判定，不碰网络。
     *
     * @param serverList {@code GET /api/server-list} 的应答；{@link Mode#SKIP} 或两个区相同时不读它，可以给 null
     * @param homeZone   {@code --zone}（场景里大多数号登录的区）
     * @param visitZone  {@code --visit-zone}（跨区步骤里另一个号登录的区）
     */
    public static Decision decide(Mode mode, JsonNode serverList, int homeZone, int visitZone) {
        if (mode == Mode.SKIP) {
            return new Skip("--cross-zone skip：按要求不跑跨区步骤");
        }
        String blocker;
        if (homeZone == visitZone) {
            blocker = "--visit-zone 与 --zone 都是 " + homeZone + "，没有第二个区可用";
        } else {
            blocker = notOpen(serverList, visitZone);
        }
        if (blocker == null) {
            return new Run();
        }
        return mode == Mode.REQUIRE
                ? new Fail("--cross-zone require，但" + blocker + "——跨区步骤需要 XM_ZONES=2 的切片（两个区各有自己的 gate 与 scene）")
                : new Skip(blocker + "（--cross-zone auto：跳过跨区步骤；要它必须跑就用 require）");
    }

    /**
     * 读区服列表后判定。{@link Mode#SKIP} 与两个区相同时不发请求。
     *
     * @throws RobotException 读区服列表失败（auto 下也照抛：读不到不等于「区没开」，不能当成跳过的理由）
     */
    public static Decision resolve(Mode mode, GatewayHttp gateway, int homeZone, int visitZone) throws RobotException {
        boolean needsList = mode != Mode.SKIP && homeZone != visitZone;
        return decide(mode, needsList ? gateway.get("/api/server-list") : null, homeZone, visitZone);
    }

    /** 区服列表里 {@code zone} 不是 OPEN 的说法；是 OPEN 返回 null。 */
    private static String notOpen(JsonNode serverList, int zone) {
        String status = null;
        if (serverList != null) {
            for (JsonNode item : serverList.path("zones")) {
                if (item.path("zone_id").asInt() == zone) {
                    status = item.path("status").asText("");
                }
            }
        }
        if (status == null) {
            return "区 " + zone + " 不在区服列表里";
        }
        return status.equals(OPEN) ? null : "区 " + zone + " 的状态是 " + (status.isEmpty() ? "（空）" : status) + "，不是 OPEN";
    }
}
