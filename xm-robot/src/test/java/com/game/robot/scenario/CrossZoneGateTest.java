package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.robot.client.FakeGateway;
import com.game.robot.client.GateEndpoint;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.RobotException;
import com.game.robot.scenario.CrossZoneGate.Decision;
import com.game.robot.scenario.CrossZoneGate.Mode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * team / guild / trade 的跨区步骤跑不跑（批次 5.4 裁决 J6）：三态开关 × 区服列表里访客区的状态。要钉住的是
 * 「auto 跳过时说得出原因」「require 跑不了必须失败、不能静默跳过」「skip 连区服列表都不读」。
 */
class CrossZoneGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode serverList(String zone2Status) throws Exception {
        String zone2 = zone2Status == null ? "" : ",{\"zone_id\":2,\"name\":\"二区\",\"status\":\"" + zone2Status + "\"}";
        return JSON.readTree("{\"zones\":[{\"zone_id\":1,\"name\":\"一区\",\"status\":\"OPEN\"}" + zone2 + "]}");
    }

    @Test
    void 访客区是OPEN_auto与require都跑_结果行字段是run() throws Exception {
        for (Mode mode : new Mode[] {Mode.AUTO, Mode.REQUIRE}) {
            Decision decision = CrossZoneGate.decide(mode, serverList("OPEN"), 1, 2);

            assertThat(decision).as(mode.wire()).isEqualTo(new CrossZoneGate.Run());
            assertThat(decision.runs()).isTrue();
            assertThat(decision.reason()).isEmpty();
            assertThat(decision.field()).isEqualTo("cross_zone=run");
        }
    }

    @Test
    void 访客区在维护_auto跳过并说明原因_require失败() throws Exception {
        Decision auto = CrossZoneGate.decide(Mode.AUTO, serverList("MAINTENANCE"), 1, 2);
        assertThat(auto).isInstanceOf(CrossZoneGate.Skip.class);
        assertThat(auto.runs()).isFalse();
        assertThat(auto.reason()).contains("区 2 的状态是 MAINTENANCE，不是 OPEN", "--cross-zone auto", "require");
        assertThat(auto.field()).isEqualTo("cross_zone=skip");

        Decision require = CrossZoneGate.decide(Mode.REQUIRE, serverList("MAINTENANCE"), 1, 2);
        assertThat(require).as("验收用的 require：跑不了就是失败，不能当成跳过").isInstanceOf(CrossZoneGate.Fail.class);
        assertThat(require.runs()).isFalse();
        assertThat(require.reason()).contains("--cross-zone require", "区 2 的状态是 MAINTENANCE，不是 OPEN", "XM_ZONES=2");
        assertThat(require.field()).isEqualTo("cross_zone=fail");
    }

    @Test
    void 访客区不在区服列表里_或者状态为空_同样不是OPEN() throws Exception {
        assertThat(CrossZoneGate.decide(Mode.AUTO, serverList(null), 1, 2).reason()).contains("区 2 不在区服列表里");
        assertThat(CrossZoneGate.decide(Mode.REQUIRE, serverList(null), 1, 2)).isInstanceOf(CrossZoneGate.Fail.class);
        assertThat(CrossZoneGate.decide(Mode.REQUIRE, serverList(""), 1, 2).reason()).contains("区 2 的状态是 （空），不是 OPEN");
        assertThat(CrossZoneGate.decide(Mode.AUTO, serverList("PREVIEW"), 1, 2)).isInstanceOf(CrossZoneGate.Skip.class);
        assertThat(CrossZoneGate.decide(Mode.REQUIRE, JSON.readTree("{}"), 1, 2).reason()).as("应答里没有 zones").contains("区 2 不在区服列表里");
    }

    @Test
    void 只看访客区_出发区的状态不在这里判() throws Exception {
        // 出发区没开的话场景自己第一步登录就失败了；这里判的是「第二个区能不能用」
        JsonNode homeDown = JSON.readTree("{\"zones\":[{\"zone_id\":1,\"status\":\"MAINTENANCE\"},{\"zone_id\":2,\"status\":\"OPEN\"}]}");

        assertThat(CrossZoneGate.decide(Mode.REQUIRE, homeDown, 1, 2).runs()).isTrue();
        assertThat(CrossZoneGate.decide(Mode.REQUIRE, homeDown, 2, 1)).as("反过来：从区 2 出发、访客区是区 1").isInstanceOf(CrossZoneGate.Fail.class);
    }

    @Test
    void 两个区相同_没有第二个区_auto跳过_require失败_不看区服列表() throws Exception {
        // 区服列表里这个区明明是 OPEN：相同的区登录出来的不是「别区的号」，硬跑会把同区的成功当成跨区的失败
        Decision auto = CrossZoneGate.decide(Mode.AUTO, serverList("OPEN"), 2, 2);
        assertThat(auto).isInstanceOf(CrossZoneGate.Skip.class);
        assertThat(auto.reason()).contains("--visit-zone 与 --zone 都是 2");

        Decision require = CrossZoneGate.decide(Mode.REQUIRE, null, 2, 2);
        assertThat(require).isInstanceOf(CrossZoneGate.Fail.class);
        assertThat(require.reason()).contains("--visit-zone 与 --zone 都是 2");
    }

    @Test
    void skip_不跑_不读区服列表() {
        Decision skip = CrossZoneGate.decide(Mode.SKIP, null, 1, 2);

        assertThat(skip).isInstanceOf(CrossZoneGate.Skip.class);
        assertThat(skip.reason()).contains("--cross-zone skip");
        assertThat(skip.field()).isEqualTo("cross_zone=skip");
    }

    @Test
    void 取值的命令行写法() {
        assertThat(Mode.ofWire("auto")).isEqualTo(Mode.AUTO);
        assertThat(Mode.ofWire("require")).isEqualTo(Mode.REQUIRE);
        assertThat(Mode.ofWire("skip")).isEqualTo(Mode.SKIP);
        assertThat(Mode.ofWire("AUTO")).as("只认小写").isNull();
        assertThat(Mode.ofWire("required")).isNull();
        assertThat(Mode.REQUIRE.wire()).isEqualTo("require");
    }

    @Test
    void resolve_读区服列表后判定_skip与两个区相同时不发请求_读不到照抛() throws Exception {
        try (FakeGateway gateway = new FakeGateway().assign(1, new GateEndpoint("127.0.0.1", 1), "t", "s").status(2, "MAINTENANCE")) {
            GatewayHttp http = new GatewayHttp(gateway.baseUrl(), Duration.ofSeconds(3));

            assertThat(CrossZoneGate.resolve(Mode.AUTO, http, 1, 2)).isInstanceOf(CrossZoneGate.Skip.class);
            assertThat(CrossZoneGate.resolve(Mode.REQUIRE, http, 1, 2)).isInstanceOf(CrossZoneGate.Fail.class);
            assertThat(gateway.serverListCalls()).isEqualTo(2);

            gateway.status(2, "OPEN");
            assertThat(CrossZoneGate.resolve(Mode.REQUIRE, http, 1, 2)).isEqualTo(new CrossZoneGate.Run());
            assertThat(gateway.serverListCalls()).isEqualTo(3);

            assertThat(CrossZoneGate.resolve(Mode.SKIP, http, 1, 2)).isInstanceOf(CrossZoneGate.Skip.class);
            assertThat(CrossZoneGate.resolve(Mode.AUTO, http, 2, 2)).isInstanceOf(CrossZoneGate.Skip.class);
            assertThat(CrossZoneGate.resolve(Mode.REQUIRE, http, 2, 2)).isInstanceOf(CrossZoneGate.Fail.class);
            assertThat(gateway.serverListCalls()).as("这三次都不该去读区服列表").isEqualTo(3);
        }
        // gateway 不通：auto 也照抛——读不到不等于「区没开」，不能拿它当跳过的理由
        GatewayHttp nowhere = new GatewayHttp("http://127.0.0.1:1", Duration.ofSeconds(3));
        assertThatThrownBy(() -> CrossZoneGate.resolve(Mode.AUTO, nowhere, 1, 2)).isInstanceOf(RobotException.class)
                .hasMessageContaining("xm-gateway");
        assertThat(CrossZoneGate.resolve(Mode.SKIP, nowhere, 1, 2)).isInstanceOf(CrossZoneGate.Skip.class);
    }
}
