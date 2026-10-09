package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.robot.RobotOptions;
import com.game.robot.TravelOptions;
import com.game.robot.client.GatewayHttp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * travel 场景的<b>骨架</b>（批次 5.4 先行件）：三个子命令各有自己的结果行标记，账号带 {@code tv} 标签，步骤还没有实现时必须失败——
 * 不能让「什么都没验」看起来是通过。骨架不碰网络，所以这里不起假服务端（客户端与流程传 null）。
 */
class TravelScenarioTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final Map<String, String> ENV = Map.of(RobotOptions.PASSWORD_ENV, "dev-secret");

    private static TravelScenario scenario(String... args) throws Exception {
        List<String> all = List.of(args);
        RobotOptions options = RobotOptions.parse(all, ENV, 0L);
        return new TravelScenario(null, null, null, null, new GatewayHttp("http://127.0.0.1:1", Duration.ofSeconds(1)), REGISTRY, options,
                TravelOptions.parse(all, ENV));
    }

    @Test
    void 骨架_步骤没有实现时失败_结果行是TRAVEL_SMOKE_FAIL_step_not_implemented() throws Exception {
        TravelScenario scenario = scenario("travel", "--run-tag", "t1");

        CheckReport report = scenario.run();

        assertThat(report.passed()).as("没有验任何东西，不能算通过").isFalse();
        assertThat(report.failures()).isEqualTo(1);
        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=not-implemented reason=");
        assertThat(scenario.accountA()).isEqualTo("robot_java_tvt1_a");
        assertThat(scenario.mode()).isEqualTo(TravelScenario.Mode.ROUND_TRIP);
    }

    @Test
    void 两个子模式各有自己的结果行标记() throws Exception {
        TravelScenario abandon = scenario("travel-abandon", "--run-tag", "t1");
        abandon.run();
        assertThat(abandon.mode()).isEqualTo(TravelScenario.Mode.ABANDON);
        assertThat(abandon.resultLine()).startsWith("TRAVEL_ABANDON_FAIL step=not-implemented reason=");

        TravelScenario longStay = scenario("travel-long", "--run-tag", "t1", "--zone", "2", "--visit-zone", "1");
        longStay.run();
        assertThat(longStay.mode()).isEqualTo(TravelScenario.Mode.LONG);
        assertThat(longStay.resultLine()).startsWith("TRAVEL_LONG_FAIL step=not-implemented reason=");
    }

    @Test
    void 子模式与标记的对应_标记是大写字母与下划线_不是travel的子命令不认() {
        assertThat(TravelScenario.Mode.of(RobotOptions.Scenario.TRAVEL).marker()).isEqualTo("TRAVEL_SMOKE");
        assertThat(TravelScenario.Mode.of(RobotOptions.Scenario.TRAVEL_ABANDON).marker()).isEqualTo("TRAVEL_ABANDON");
        assertThat(TravelScenario.Mode.of(RobotOptions.Scenario.TRAVEL_LONG).marker()).isEqualTo("TRAVEL_LONG");
        for (TravelScenario.Mode mode : TravelScenario.Mode.values()) {
            assertThat(mode.marker()).matches("[A-Z0-9_]+");
        }
        assertThatThrownBy(() -> TravelScenario.Mode.of(RobotOptions.Scenario.TEAM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 两个区相同时构造即拒绝_226与124的消息号按名字解析得到() throws Exception {
        RobotOptions sameZone = RobotOptions.parse(List.of("smoke", "--zone", "2"), ENV, 0L);
        assertThatThrownBy(() -> new TravelScenario(null, null, null, null, null, REGISTRY, sameZone, TravelOptions.parse(List.of(), ENV)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("两个区不能相同");

        assertThat(REGISTRY.byId(REGISTRY.requireId(TravelScenario.TRAVEL_SERVICE, TravelScenario.TRAVEL_METHOD)).orElseThrow()
                .requestPrototype().getDescriptorForType().getName()).isEqualTo("TravelToZoneRequest");
        assertThat(TravelScenario.accountName("p_", "x9", "b")).isEqualTo("p_tvx9_b");
    }
}
