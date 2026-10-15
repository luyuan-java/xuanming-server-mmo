package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmBlockCurrencyResponse;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmDeductCurrencyResponse;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.GmUnblockCurrencyResponse;
import com.game.proto.TipInfoMessage;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 货币与 GM 闸：一个新账号进场后查余额（54），再按服务端运行模式的期望走一遍 GM 指令。
 * <ul>
 *   <li><b>allow</b>（dev / test）：加钻石 500（37）→ 扣 120（49）→ 超扣回 27000 → 封金币（94）后加金币回 27005 → 解封（95）
 *       → 未知币种回 1005；断开重登，余额与封禁状态（这里已解封）按离场写回恢复。</li>
 *   <li><b>deny</b>（prod）：发 37，gate 回带请求 id 的信封 1006（2026-10-15 起，原为推 23 {1006}），没有 37 的业务应答；随后查余额仍为 0（没转发到 scene），连接仍在。</li>
 * </ul>
 * 消息号按「服务 + 方法」从契约解析，不写死。
 */
public final class CurrencyScenario {

    private static final String SERVICE = "SceneCurrencyClientPlayer";
    private static final int GOLD = 0;
    private static final int DIAMOND = 1;
    private static final int UNKNOWN_TYPE = 99;
    private static final int TIP_INVALID_PARAMETER = 1005;
    private static final int TIP_FEATURE_UNAVAILABLE = 1006;
    private static final int TIP_INSUFFICIENT = 27000;
    private static final int TIP_BLOCKED = 27005;
    private static final String REF = "PARITY「货币」「GM 类客户端指令闸」行；architecture.md §4.4";

    private final PlayerFlow flow;
    private final String account;
    private final boolean expectGmAllowed;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    private final int getCurrencyList;
    private final int gmAdd;
    private final int gmDeduct;
    private final int gmBlock;
    private final int gmUnblock;
    private final int sendTip;

    public CurrencyScenario(PlayerFlow flow, MessageIdRegistry registry, int sendTip, String accountPrefix,
                            String runTag, boolean expectGmAllowed, Duration requestTimeout, Duration observeTimeout) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.expectGmAllowed = expectGmAllowed;
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
        this.getCurrencyList = registry.requireId(SERVICE, "GetCurrencyList");
        this.gmAdd = registry.requireId(SERVICE, "GmAddCurrency");
        this.gmDeduct = registry.requireId(SERVICE, "GmDeductCurrency");
        this.gmBlock = registry.requireId(SERVICE, "GmBlockCurrency");
        this.gmUnblock = registry.requireId(SERVICE, "GmUnblockCurrency");
        this.sendTip = sendTip;
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "cur" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        EnteredPlayer player = null;
        try {
            player = flow.enter(account, new Timings());
            GetCurrencyListResponse initial = list(player.connection());
            report.check(tipOf(initial.hasErrorMessage(), initial.getErrorMessage()) == 0
                            && initial.getCurrency().getValuesList().stream().allMatch(v -> v == 0),
                    "新号查余额（54）", "tip=" + tipOf(initial.hasErrorMessage(), initial.getErrorMessage())
                            + " values=" + initial.getCurrency().getValuesList(), REF);
            if (expectGmAllowed) {
                runAllowed(player, report);
            } else {
                runDenied(player, report);
            }
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), REF);
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    private void runAllowed(EnteredPlayer player, CheckReport report) throws RobotException {
        GameConnection c = player.connection();
        GmAddCurrencyResponse added = call(c, gmAdd, add(DIAMOND, 500), GmAddCurrencyResponse.parser());
        report.check(added.getErrorMessage().getId() == 0 && added.getBalanceAfter() == 500,
                "GM 加钻石 500（37）", "tip=" + added.getErrorMessage().getId() + " balance_after=" + added.getBalanceAfter(), REF);

        GmDeductCurrencyResponse deducted = call(c, gmDeduct, deduct(DIAMOND, 120), GmDeductCurrencyResponse.parser());
        report.check(deducted.getErrorMessage().getId() == 0 && deducted.getBalanceAfter() == 380,
                "GM 扣钻石 120（49）", "tip=" + deducted.getErrorMessage().getId() + " balance_after="
                        + deducted.getBalanceAfter(), REF);

        GmDeductCurrencyResponse tooMuch = call(c, gmDeduct, deduct(DIAMOND, 1000), GmDeductCurrencyResponse.parser());
        report.check(tooMuch.getErrorMessage().getId() == TIP_INSUFFICIENT && tooMuch.getBalanceAfter() == 0,
                "超额扣币回 27000、不回余额", "tip=" + tooMuch.getErrorMessage().getId(), REF);

        GmBlockCurrencyResponse blocked = call(c, gmBlock,
                GmBlockCurrencyRequest.newBuilder().setCurrencyType(GOLD).build(), GmBlockCurrencyResponse.parser());
        GmAddCurrencyResponse rejected = call(c, gmAdd, add(GOLD, 1), GmAddCurrencyResponse.parser());
        report.check(blocked.getErrorMessage().getId() == 0 && rejected.getErrorMessage().getId() == TIP_BLOCKED,
                "封禁金币（94）后加金币回 27005", "封禁 tip=" + blocked.getErrorMessage().getId() + " 加币 tip="
                        + rejected.getErrorMessage().getId(), REF);

        GmUnblockCurrencyResponse unblocked = call(c, gmUnblock,
                GmUnblockCurrencyRequest.newBuilder().setCurrencyType(GOLD).build(), GmUnblockCurrencyResponse.parser());
        GmAddCurrencyResponse unknown = call(c, gmAdd, add(UNKNOWN_TYPE, 1), GmAddCurrencyResponse.parser());
        report.check(unblocked.getErrorMessage().getId() == 0 && unknown.getErrorMessage().getId() == TIP_INVALID_PARAMETER,
                "解封（95）；未知币种回 1005", "解封 tip=" + unblocked.getErrorMessage().getId() + " 未知币种 tip="
                        + unknown.getErrorMessage().getId(), REF);

        c.close();
        EnteredPlayer again = flow.enter(account, new Timings());
        try {
            if (again.playerId() != player.playerId()) {
                throw new RobotException("重登后角色变了：" + player.playerId() + " → " + again.playerId());
            }
            GetCurrencyListResponse restored = list(again.connection());
            List<Long> values = restored.getCurrency().getValuesList();
            report.check(values.size() > DIAMOND && values.get(DIAMOND) == 380 && values.get(GOLD) == 0
                            && restored.getCurrency().getBlockedTypesCount() == 0,
                    "重登后余额恢复（离场写回）", "values=" + values + " blocked=" + restored.getCurrency().getBlockedTypesList(), REF);
        } finally {
            again.connection().close();
        }
    }

    private void runDenied(EnteredPlayer player, CheckReport report) throws RobotException {
        GameConnection c = player.connection();
        int mark = c.inbox().size();
        long id = c.send(gmAdd, add(DIAMOND, 500));
        // gate 的 GM 闸 2026-10-15 起回带请求 id 的信封 1006（原为推 23 {1006}；mmorpg client-request-ordering.md O6）
        Optional<Received> rejected = c.await(mark, r -> r.messageId() == gmAdd && r.requestId() == id, requestTimeout);
        int tipId = rejected.map(Received::envelopeTipId).orElse(-1);
        report.check(tipId == TIP_FEATURE_UNAVAILABLE, "生产模式发 GM 加币（37）：gate 回带请求 id 的信封 1006",
                rejected.isEmpty() ? "没有收到 37 的信封" + c.describeSince(mark) : "信封 tip=" + tipId, REF);
        // 观察窗口内这条请求只该有那一条信封：再来一条（业务应答）就说明被转发了
        c.await(mark, r -> false, observeTimeout);
        long repliesForRequest = c.inbox().snapshot(mark).stream()
                .filter(r -> r.messageId() == gmAdd && r.requestId() == id).count();
        boolean pushedOldShape = c.inbox().snapshot(mark).stream().anyMatch(r -> r.messageId() == sendTip && r.requestId() == 0
                && Optional.ofNullable(r.parseOrNull(TipInfoMessage.parser())).map(TipInfoMessage::getId).orElse(-1)
                        == TIP_FEATURE_UNAVAILABLE);
        report.check(repliesForRequest == 1 && !pushedOldShape, "生产模式 GM 指令不转发（37 只有那条信封、没有业务应答，也不推 23）",
                "37 回执数=" + repliesForRequest + " 23 推送=" + pushedOldShape, REF);
        GetCurrencyListResponse after = list(c);
        report.check(after.getCurrency().getValuesList().stream().allMatch(v -> v == 0) && c.isOpen(),
                "被拒后余额不变、连接仍在", "values=" + after.getCurrency().getValuesList() + " open=" + c.isOpen(), REF);
    }

    private GetCurrencyListResponse list(GameConnection c) throws RobotException {
        return call(c, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), GetCurrencyListResponse.parser());
    }

    private <T extends Message> T call(GameConnection c, int messageId, Message body, Parser<T> parser)
            throws RobotException {
        return c.call(messageId, body, parser, requestTimeout);
    }

    private static GmAddCurrencyRequest add(int type, long amount) {
        return GmAddCurrencyRequest.newBuilder().setCurrencyType(type).setAmount(amount).build();
    }

    private static GmDeductCurrencyRequest deduct(int type, long amount) {
        return GmDeductCurrencyRequest.newBuilder().setCurrencyType(type).setAmount(amount).build();
    }

    private static int tipOf(boolean present, TipInfoMessage tip) {
        return present ? tip.getId() : -1;
    }
}
