package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.contract.MessageIdRegistry;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.EnterGameResponse;
import com.game.robot.RobotOptions;
import com.game.robot.client.Received;
import com.game.table.LoginErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** rollback 场景里不连服务端就能钉住的部分：子命令、账号名、用法说明，以及请求编码与判定用的纯函数。 */
class RollbackScenarioTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 子命令与账号名() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("rollback", "--run-tag", "x1"),
                Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.ROLLBACK);
        assertThat(RollbackScenario.accountName(options.accountPrefix(), options.runTag())).isEqualTo("robot_java_rbx1");
        assertThat(RobotOptions.usage()).contains("|rollback|", "  rollback  ");
    }

    // ------------------------------------------------------------------ 请求编码

    @Test
    void 回档请求体_按号与按时刻二选一_玩家号是无符号十进制字符串_预演才带dryRun() throws Exception {
        long pid = 0xF000_0000_0000_0001L; // ≥ 2^63：必须按无符号写
        JsonNode byId = JSON.readTree(RollbackScenario.rollbackBody(JSON, pid, "5001", null, "kick", false).toString());
        assertThat(byId.toString()).isEqualTo("{\"scope\":\"players\",\"players\":[\"17293822569102704641\"],"
                + "\"snapshotId\":\"5001\",\"ifOnline\":\"kick\",\"reason\":\"robot 回档演练（kick）\"}");

        JsonNode byTime = JSON.readTree(RollbackScenario.rollbackBody(JSON, 1001, null, 1_791_264_000_123L, "reject", false)
                .toString());
        assertThat(byTime.has("snapshotId")).isFalse();
        assertThat(byTime.get("targetTimeMs").isIntegralNumber()).isTrue();
        assertThat(byTime.get("targetTimeMs").asLong()).isEqualTo(1_791_264_000_123L);
        assertThat(byTime.get("players").get(0).asText()).isEqualTo("1001");
        assertThat(byTime.has("dryRun")).as("执行请求不带 dryRun").isFalse();

        JsonNode dryRun = JSON.readTree(RollbackScenario.rollbackBody(JSON, 1001, null, 5L, "reject", true).toString());
        assertThat(dryRun.get("dryRun").asBoolean()).isTrue();
        assertThat(dryRun.get("reason").asText()).isNotBlank();
        // 同一个请求两次编码逐字相同（同一个 Idempotency-Key 重提要算同参）
        assertThat(RollbackScenario.rollbackBody(JSON, 1001, "5001", null, "reject", false).toString())
                .isEqualTo(RollbackScenario.rollbackBody(JSON, 1001, "5001", null, "reject", false).toString());

        assertThatThrownBy(() -> RollbackScenario.rollbackBody(JSON, 1001, "5001", 5L, "reject", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RollbackScenario.rollbackBody(JSON, 1001, null, null, "reject", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 运维持有期间进游戏回 2005

    private static final int ENTER_GAME = 26;

    private static Received received(List<Received> log, int messageId, long requestId, Message body, int envelopeTip) {
        MessageContent.Builder content = MessageContent.newBuilder().setMessageId(messageId).setId(requestId);
        if (body != null) {
            content.setSerializedMessage(body.toByteString());
        }
        if (envelopeTip != 0) {
            content.setErrorMessage(TipInfoMessage.newBuilder().setId(envelopeTip));
        }
        Received r = new Received(log.size(), System.nanoTime(), content.build());
        log.add(r);
        return r;
    }

    private static EnterGameResponse rejected(int tip) {
        return EnterGameResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(tip)).build();
    }

    @Test
    void 进游戏应答的结局_只看26的应答_按到达顺序_2005被自动重试吞掉之后仍然翻得出来() {
        assertThat(RollbackScenario.ENTER_IN_PROGRESS).isEqualTo(2005)
                .isEqualTo(LoginErrorTip.login_error.kLoginInProgress_VALUE);
        List<Received> log = new ArrayList<>();
        received(log, 48, 1, null, 0);                                             // 登录应答
        received(log, ENTER_GAME, 2, rejected(2005), 0);                           // 第一次进游戏：运维持有 → 2005
        received(log, 23, 0, TipInfoMessage.newBuilder().setId(2005).build(), 0);  // 别的推送（id = 0），不是应答
        received(log, ENTER_GAME, 0, rejected(2005), 0);                           // 消息号相同但 id = 0：不是应答，不算
        received(log, ENTER_GAME, 3, EnterGameResponse.newBuilder().setPlayerId(7).build(), 0); // PlayerFlow 重试：成功
        received(log, 79, 0, null, 0);                                             // 进场通知

        List<Integer> outcomes = RollbackScenario.enterGameOutcomes(log, ENTER_GAME);

        assertThat(outcomes).containsExactly(2005, 0);
        assertThat(RollbackScenario.firstEnterRejectedInProgress(outcomes)).isTrue();
    }

    @Test
    void 第一次就进去了_或者被别的tip拒绝_都不算持有期间被挡() {
        List<Received> entered = new ArrayList<>();
        received(entered, ENTER_GAME, 2, EnterGameResponse.newBuilder().setPlayerId(7).build(), 0);
        assertThat(RollbackScenario.enterGameOutcomes(entered, ENTER_GAME)).containsExactly(0);
        assertThat(RollbackScenario.firstEnterRejectedInProgress(List.of(0))).isFalse();

        // 先成功、后面才出现 2005（不可能是「持有期间被挡」）
        assertThat(RollbackScenario.firstEnterRejectedInProgress(List.of(0, 2005))).isFalse();
        // 别的拒绝原因
        assertThat(RollbackScenario.firstEnterRejectedInProgress(List.of(2017, 0))).isFalse();
        // 一条应答都没有
        assertThat(RollbackScenario.firstEnterRejectedInProgress(List.of())).isFalse();
        // 一直是 2005（持有时间长于自动重试的窗口）也成立
        assertThat(RollbackScenario.firstEnterRejectedInProgress(List.of(2005, 2005, 2005, 2005))).isTrue();
    }

    @Test
    void 进游戏应答_信封层的错误取信封上的tip_消息体解析不了记负一() {
        List<Received> log = new ArrayList<>();
        received(log, ENTER_GAME, 2, null, 1006);                                   // 信封层：服务不可用
        Received garbage = new Received(log.size(), System.nanoTime(), MessageContent.newBuilder().setMessageId(ENTER_GAME)
                .setId(3).setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF})).build());
        log.add(garbage);
        // 成功时带了 id = 0 的空 tip：按「没被拒」算（是不是违约由 PlayerFlow 判）
        received(log, ENTER_GAME, 4, rejected(0), 0);

        assertThat(RollbackScenario.enterGameOutcomes(log, ENTER_GAME)).containsExactly(1006, -1, 0);
    }

    @Test
    void 自动重试到头仍是2005时的异常文案_只认进游戏的2005() {
        // PlayerFlow.requireNoError 的文案：step + " 被拒：error_message.id=" + id（带参数时后面还有 parameters=…）
        assertThat(RollbackScenario.isEnterInProgressFailure("进游戏（26） 被拒：error_message.id=2005")).isTrue();
        assertThat(RollbackScenario.isEnterInProgressFailure("进游戏（26） 被拒：error_message.id=2005 parameters=[x]")).isTrue();
        assertThat(RollbackScenario.isEnterInProgressFailure("进游戏（26） 被拒：error_message.id=20051")).isFalse();
        assertThat(RollbackScenario.isEnterInProgressFailure("进游戏（26） 被拒：error_message.id=2017")).isFalse();
        assertThat(RollbackScenario.isEnterInProgressFailure("登录（48） 被拒：error_message.id=2005")).isFalse();
        assertThat(RollbackScenario.isEnterInProgressFailure("发出进游戏后 15000 ms 内没有收到 79 NotifyEnterScene")).isFalse();
        assertThat(RollbackScenario.isEnterInProgressFailure(null)).isFalse();
    }

    @Test
    void 场景用到的消息号在契约注册表里() {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        assertThat(registry.requireId("ClientPlayerLogin", "EnterGame")).isEqualTo(ENTER_GAME);
        assertThat(registry.requireId("ClientPlayerLogin", "LeaveGame")).isPositive();
        assertThat(registry.requireId("SceneCurrencyClientPlayer", "GmAddCurrency")).isPositive();
    }

    // ------------------------------------------------------------------ 差异接口：有没有资产差异

    /** xm-data SnapshotDiffService 应答的形状（只留场景要看的几段）。 */
    private static JsonNode diff(String balances, String byConfig, String onlyInSnapshot, String onlyInCurrent,
                                 String stackChanged, String petsChanged) throws Exception {
        return JSON.readTree("{\"current\":{\"ownerReleased\":true},"
                + "\"currency\":{\"balances\":" + balances + ",\"blockedTypes\":{\"snapshot\":[],\"current\":[],\"changed\":false}},"
                + "\"items\":{\"byConfig\":" + byConfig + ",\"onlyInSnapshot\":" + onlyInSnapshot + ",\"onlyInCurrent\":"
                + onlyInCurrent + ",\"stackChanged\":" + stackChanged + ",\"evidenceTruncated\":false},"
                + "\"pets\":{\"onlyInSnapshot\":[],\"onlyInCurrent\":[],\"changed\":" + petsChanged + "}}");
    }

    private static final String SAME_BALANCES = "[{\"currencyType\":0,\"snapshot\":\"0\",\"current\":\"0\",\"delta\":\"0\"},"
            + "{\"currencyType\":1,\"snapshot\":\"1300\",\"current\":\"1300\",\"delta\":\"0\"}]";

    @Test
    void 差异应答_余额物品宝宝都一样_没有资产差异() throws Exception {
        assertThat(RollbackScenario.assetDifferences(diff(SAME_BALANCES, "[]", "[]", "[]", "[]", "[]"))).isEmpty();
        // 一个币种都没有（新号）也是无差异
        assertThat(RollbackScenario.assetDifferences(diff("[]", "[]", "[]", "[]", "[]", "[]"))).isEmpty();
    }

    @Test
    void 差异应答_余额或物品或宝宝有变_逐条列出() throws Exception {
        String changedBalances = "[{\"currencyType\":0,\"snapshot\":\"0\",\"current\":\"0\",\"delta\":\"0\"},"
                + "{\"currencyType\":1,\"snapshot\":\"1000\",\"current\":\"1300\",\"delta\":\"300\","
                + "\"transferAfterSnapshot\":false,\"transferTxIds\":[],\"restorable\":true}]";
        assertThat(RollbackScenario.assetDifferences(diff(changedBalances, "[]", "[]", "[]", "[]", "[]")))
                .containsExactly("货币 1：快照 1000 → 现在 1300");
        // 负的差值、超过 int64 的差值（服务端给的是十进制字符串）都算有差异
        assertThat(RollbackScenario.assetDifferences(diff("[{\"currencyType\":1,\"snapshot\":\"5\",\"current\":\"0\","
                + "\"delta\":\"-5\"}]", "[]", "[]", "[]", "[]", "[]"))).hasSize(1);

        List<String> items = RollbackScenario.assetDifferences(diff(SAME_BALANCES,
                "[{\"configId\":100,\"snapshot\":5,\"current\":7,\"delta\":2}]", "[]",
                "[{\"itemUuid\":\"2\",\"configId\":200,\"stack\":1}]", "[{\"itemUuid\":\"1\",\"delta\":2}]", "[]"));
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).startsWith("物品 byConfig：");
        assertThat(items.get(1)).startsWith("物品 onlyInCurrent：");
        assertThat(items.get(2)).startsWith("物品 stackChanged：");

        assertThat(RollbackScenario.assetDifferences(diff(SAME_BALANCES, "[]", "[]", "[]", "[]", "[{\"petId\":\"9001\"}]")))
                .singleElement().asString().startsWith("宝宝 changed：");
    }

    @Test
    void 差异应答缺了要看的段_不当成无差异() throws Exception {
        assertThat(RollbackScenario.assetDifferences(JSON.readTree("{}"))).singleElement().asString().contains("缺");
        assertThat(RollbackScenario.assetDifferences(JSON.readTree("{\"code\":\"snapshot_not_found\",\"message\":\"x\"}")))
                .isNotEmpty();
        // 物品段里少了一个列表（服务端改了字段名）
        JsonNode missingList = JSON.readTree("{\"currency\":{\"balances\":[]},\"items\":{\"byConfig\":[],\"onlyInSnapshot\":[],"
                + "\"onlyInCurrent\":[]},\"pets\":{\"onlyInSnapshot\":[],\"onlyInCurrent\":[],\"changed\":[]}}");
        assertThat(RollbackScenario.assetDifferences(missingList)).containsExactly("差异应答缺 items.stackChanged");
    }

    // ------------------------------------------------------------------ 等 LOGOUT 快照落库

    @Test
    void 快照列表里挑这次离场拍的那份_内容时刻不早于离场时刻的最新一份_没有为空() throws Exception {
        JsonNode list = JSON.readTree("[{\"snapshotId\":\"900\",\"timeMs\":5000,\"causeName\":\"LOGOUT\"},"
                + "{\"snapshotId\":\"800\",\"timeMs\":3000,\"causeName\":\"LOGOUT\"},"
                + "{\"snapshotId\":\"700\",\"timeMs\":1000,\"causeName\":\"LOGOUT\"}]");
        // 上一次离场的快照（3000）早于这次离场时刻 4000：不能拿它当这次的
        assertThat(RollbackScenario.newestAtOrAfter(list, 4000)).map(s -> s.get("snapshotId").asText()).contains("900");
        assertThat(RollbackScenario.newestAtOrAfter(list, 5000)).map(s -> s.get("snapshotId").asText()).contains("900");
        assertThat(RollbackScenario.newestAtOrAfter(list, 5001)).isEmpty();
        assertThat(RollbackScenario.newestAtOrAfter(JSON.readTree("[]"), 0)).isEmpty();
        // 不依赖列表的顺序；同毫秒取号大的（号按无符号比）
        JsonNode sameMs = JSON.readTree("[{\"snapshotId\":\"5\",\"timeMs\":7000},{\"snapshotId\":\"18446744073709551615\","
                + "\"timeMs\":7000},{\"snapshotId\":\"9\",\"timeMs\":6000}]");
        assertThat(RollbackScenario.newestAtOrAfter(sameMs, 0)).map(s -> s.get("snapshotId").asText())
                .contains("18446744073709551615");
    }
}
