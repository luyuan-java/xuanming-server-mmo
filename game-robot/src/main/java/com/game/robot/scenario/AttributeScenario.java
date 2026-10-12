package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocateAttributePointsResponse;
import com.game.proto.AttributeDimensionInfo;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.AttributePanelInfo;
import com.game.proto.AttributePoolInfo;
import com.game.proto.AttributeSchemeInfo;
import com.game.proto.AutoAllocateAttributePointsRequest;
import com.game.proto.AutoAllocateAttributePointsResponse;
import com.game.proto.CreateAttributeSchemeRequest;
import com.game.proto.CreateAttributeSchemeResponse;
import com.game.proto.DerivedAttributeInfo;
import com.game.proto.GetAttributePanelRequest;
import com.game.proto.GetAttributePanelResponse;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.GmSetPlayerLevelResponse;
import com.game.proto.ResetAttributePointsRequest;
import com.game.proto.ResetAttributePointsResponse;
import com.game.proto.SwitchAttributeSchemeRequest;
import com.game.proto.SwitchAttributeSchemeResponse;
import com.game.proto.TipInfoMessage;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 属性加点，步骤与断言对照 mmorpg robot {@code attribute_smoke_scenario.go}（新号起步，所以不需要基线的「等级归 1 / 洗点 / 切回首方案」预备）：
 * 面板形状 → GM 设 30 级（先收到 170 推送再收到应答，属性点恰好 +145）→ 自动加点只算不落 → 按建议确认后剩余归零、二级属性变大
 * → 幂等重发 25014 / 减点 25004 → 开方案精确扣金币、新方案干净、跨 60 秒冷却切回不串档 → 重登后等级 / 方案 / 已分配 / 二级属性原样
 * → 30 级洗点精确扣金币、全额返还。需要服务端放行 GM（dev / test）；{@code --expect-gm deny} 只核对 GM 设等级被 gate 拒绝、面板照常可查。
 */
public final class AttributeScenario {

    private static final String SERVICE = "SceneAttributeClientPlayer";
    private static final String CURRENCY_SERVICE = "SceneCurrencyClientPlayer";
    private static final int POOL = 1;
    private static final int GOLD = 0;
    private static final long GOLD_GRANT = 100_000;
    private static final int TIP_FEATURE_UNAVAILABLE = 1006;
    private static final int TIP_CANNOT_DECREASE = 25004;
    private static final int TIP_SWITCH_COOLDOWN = 25008;
    private static final int TIP_NOTHING_TO_CHANGE = 25014;
    /** 等冷却的上限：表定 60 秒，超过说明冷却字段单位错了（例如发成毫秒）。 */
    private static final long MAX_COOLDOWN_WAIT_SECONDS = 90;
    /** 重登前等离场写回落库。 */
    private static final Duration RELOGIN_PAUSE = Duration.ofSeconds(2);
    private static final String REF = "PARITY「属性加点」行；mmorpg robot attribute_smoke";

    private final PlayerFlow flow;
    private final String account;
    private final boolean expectGmAllowed;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    private final int getPanel;
    private final int allocate;
    private final int reset;
    private final int autoAllocate;
    private final int createScheme;
    private final int switchScheme;
    private final int notifyPanel;
    private final int gmSetLevel;
    private final int gmAddCurrency;
    private final int getCurrencyList;
    private final int sendTip;

    public AttributeScenario(PlayerFlow flow, MessageIdRegistry registry, int sendTip, String accountPrefix,
                             String runTag, boolean expectGmAllowed, Duration requestTimeout, Duration observeTimeout) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.expectGmAllowed = expectGmAllowed;
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
        this.getPanel = registry.requireId(SERVICE, "GetAttributePanel");
        this.allocate = registry.requireId(SERVICE, "AllocateAttributePoints");
        this.reset = registry.requireId(SERVICE, "ResetAttributePoints");
        this.autoAllocate = registry.requireId(SERVICE, "AutoAllocateAttributePoints");
        this.createScheme = registry.requireId(SERVICE, "CreateAttributeScheme");
        this.switchScheme = registry.requireId(SERVICE, "SwitchAttributeScheme");
        this.notifyPanel = registry.requireId(SERVICE, "NotifyAttributePanelChanged");
        this.gmSetLevel = registry.requireId(SERVICE, "GmSetPlayerLevel");
        this.gmAddCurrency = registry.requireId(CURRENCY_SERVICE, "GmAddCurrency");
        this.getCurrencyList = registry.requireId(CURRENCY_SERVICE, "GetCurrencyList");
        this.sendTip = sendTip;
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "at" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        EnteredPlayer player = null;
        try {
            player = flow.enter(account, new Timings());
            AttributePanelInfo initial = panel(player.connection());
            AttributePoolInfo pool = pool(initial);
            report.check(initial.getPoolsCount() > 0 && initial.getDimensionsCount() > 0 && initial.getSchemesCount() > 0
                            && initial.getDerived().getMaxHealth() != 0 && initial.getLevel() == 1
                            && pool.getRemaining() == pool.getTotal(),
                    "新号面板（167）", "level=" + initial.getLevel() + " pools=" + initial.getPoolsCount() + " dimensions="
                            + initial.getDimensionsCount() + " schemes=" + initial.getSchemesCount() + " max_health="
                            + initial.getDerived().getMaxHealth() + " 剩余/总量=" + pool.getRemaining() + "/" + pool.getTotal(),
                    REF);
            if (expectGmAllowed) {
                player = runAllowed(player, initial, report);
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

    private EnteredPlayer runAllowed(EnteredPlayer player, AttributePanelInfo initial, CheckReport report)
            throws RobotException {
        GameConnection c = player.connection();
        // GM 设 30 级：170 推送（信封 id 0）先于 175 应答
        int mark = c.inbox().size();
        GmSetPlayerLevelResponse leveled = call(c, gmSetLevel, GmSetPlayerLevelRequest.newBuilder().setLevel(30).build(),
                GmSetPlayerLevelResponse.parser());
        requireOk("GM 设等级（175）", leveled.getErrorMessage());
        AttributePanelInfo level30 = leveled.getPanel();
        Optional<Received> push = c.inbox().snapshot(mark).stream()
                .filter(r -> r.messageId() == notifyPanel).findFirst();
        Optional<Received> reply = c.inbox().snapshot(mark).stream()
                .filter(r -> r.messageId() == gmSetLevel).findFirst();
        report.check(push.isPresent() && push.get().requestId() == 0 && reply.isPresent()
                        && push.get().index() < reply.get().index()
                        && push.get().parse(AttributePanelChangedS2C.parser()).getPanel().equals(level30),
                "GM 设等级先推 170 再回应答", push.isEmpty() ? "没有收到 170" : "170 id=" + push.get().requestId(), REF);
        long growth = pool(level30).getTotal() - pool(initial).getTotal();
        report.check(level30.getLevel() == 30 && growth == 145, "1 → 30 级属性点恰好 +145",
                "level=" + level30.getLevel() + " 增加 " + growth, REF);

        // 自动加点：只算不落
        AutoAllocateAttributePointsResponse auto = call(c, autoAllocate,
                AutoAllocateAttributePointsRequest.newBuilder().setPoolId(POOL).build(),
                AutoAllocateAttributePointsResponse.parser());
        requireOk("自动加点（173）", auto.getErrorMessage());
        long delta = 0;
        for (Map.Entry<Integer, Integer> e : auto.getSuggestedMap().entrySet()) {
            delta += Integer.toUnsignedLong(e.getValue()) - allocatedOf(level30, e.getKey());
        }
        long remainingAfterAuto = pool(panel(c)).getRemaining();
        report.check(auto.getPoolId() == POOL && delta == pool(level30).getRemaining()
                        && remainingAfterAuto == pool(level30).getRemaining(),
                "自动加点只算不落", "pool_id=" + auto.getPoolId() + " 建议增量=" + delta + " 之后剩余=" + remainingAfterAuto, REF);

        // 确认加点
        AllocateAttributePointsRequest allocateAll = AllocateAttributePointsRequest.newBuilder().setPoolId(POOL)
                .putAllAllocated(auto.getSuggestedMap()).build();
        AllocateAttributePointsResponse allocated = call(c, allocate, allocateAll, AllocateAttributePointsResponse.parser());
        requireOk("确认加点（168）", allocated.getErrorMessage());
        AttributePanelInfo afterAllocate = allocated.getPanel();
        report.check(pool(afterAllocate).getRemaining() == 0 && derivedSum(afterAllocate) > derivedSum(level30),
                "按建议确认后剩余归零、二级属性变大", "剩余=" + pool(afterAllocate).getRemaining() + " 二级属性和 "
                        + derivedSum(level30) + " → " + derivedSum(afterAllocate), REF);
        int again = call(c, allocate, allocateAll, AllocateAttributePointsResponse.parser()).getErrorMessage().getId();
        int anyDim = auto.getSuggestedMap().entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new RobotException("自动加点建议全为 0"));
        long anyAllocated = allocatedOf(afterAllocate, anyDim);
        int decrease = call(c, allocate, AllocateAttributePointsRequest.newBuilder().setPoolId(POOL)
                .putAllocated(anyDim, (int) (anyAllocated - 1)).build(), AllocateAttributePointsResponse.parser())
                .getErrorMessage().getId();
        report.check(again == TIP_NOTHING_TO_CHANGE && decrease == TIP_CANNOT_DECREASE, "幂等重发 25014、减点 25004",
                "重发 tip=" + again + " 减点 tip=" + decrease, REF);

        // 开方案（精确扣金币）→ 新方案干净 → 跨冷却切回不串档
        requireOk("GM 发金币（37）", call(c, gmAddCurrency, GmAddCurrencyRequest.newBuilder().setCurrencyType(GOLD)
                .setAmount(GOLD_GRANT).build(), GmAddCurrencyResponse.parser()).getErrorMessage());
        long goldBefore = gold(c);
        CreateAttributeSchemeResponse created = call(c, createScheme, CreateAttributeSchemeRequest.getDefaultInstance(),
                CreateAttributeSchemeResponse.parser());
        requireOk("开方案（174）", created.getErrorMessage());
        long createCost = afterAllocate.getCreateSchemeCostGold();
        long spent = goldBefore - gold(c);
        int activeBefore = afterAllocate.getActiveSchemeId();
        List<AttributeSchemeInfo> schemes = created.getPanel().getSchemesList();
        int other = schemes.get(schemes.size() - 1).getSchemeId();
        report.check(spent == createCost && created.getSchemeId() == other && other != activeBefore,
                "开方案精确扣金币", "扣 " + spent + "（表 " + createCost + "）新方案 " + created.getSchemeId(), REF);
        AttributePanelInfo onOther = switchWaitingCooldown(c, created.getPanel(), other).panel();
        report.check(allocatedOf(onOther, anyDim) == 0 && pool(onOther).getRemaining() == pool(onOther).getTotal(),
                "新方案是干净的", "已分配=" + allocatedOf(onOther, anyDim) + " 剩余/总量=" + pool(onOther).getRemaining()
                        + "/" + pool(onOther).getTotal(), REF);
        Switched switchedBack = switchWaitingCooldown(c, onOther, activeBefore);
        AttributePanelInfo back = switchedBack.panel();
        report.check(switchedBack.hitCooldown() && allocatedOf(back, anyDim) == anyAllocated,
                "刚切过立刻切回先撞 60 秒冷却（25008），等过冷却切回、方案不串档",
                "撞冷却=" + switchedBack.hitCooldown() + " 已分配 " + allocatedOf(back, anyDim) + "（原 " + anyAllocated + "）",
                REF);

        // 重登：等级 / 方案 / 已分配 / 二级属性原样恢复（离场写回 + 加载重算）
        c.close();
        sleep(RELOGIN_PAUSE);
        EnteredPlayer again2 = flow.enter(account, new Timings());
        AttributePanelInfo restored = panel(again2.connection());
        report.check(restored.getLevel() == 30 && restored.getSchemesCount() == back.getSchemesCount()
                        && restored.getActiveSchemeId() == back.getActiveSchemeId()
                        && allocatedOf(restored, anyDim) == anyAllocated
                        && restored.getDerived().getMaxHealth() == back.getDerived().getMaxHealth()
                        && derivedSum(restored) == derivedSum(back),
                "重登后等级 / 方案 / 已分配 / 二级属性原样", "level=" + restored.getLevel() + " schemes="
                        + restored.getSchemesCount() + " active=" + restored.getActiveSchemeId() + " 已分配="
                        + allocatedOf(restored, anyDim) + " 二级属性和=" + derivedSum(restored), REF);

        // 30 级洗点：精确扣金币、全额返还
        GameConnection c2 = again2.connection();
        long resetCost = pool(restored).getResetCostGold();
        long goldBeforeReset = gold(c2);
        ResetAttributePointsResponse resetDone = call(c2, reset, ResetAttributePointsRequest.newBuilder().setPoolId(POOL)
                .build(), ResetAttributePointsResponse.parser());
        requireOk("洗点（172）", resetDone.getErrorMessage());
        long resetSpent = goldBeforeReset - gold(c2);
        report.check(resetCost != 0 && resetSpent == resetCost
                        && pool(resetDone.getPanel()).getRemaining() == pool(restored).getTotal()
                        && allocatedOf(resetDone.getPanel(), anyDim) == 0,
                "30 级洗点精确扣金币、全额返还", "扣 " + resetSpent + "（表 " + resetCost + "）剩余="
                        + pool(resetDone.getPanel()).getRemaining(), REF);
        return again2;
    }

    private void runDenied(EnteredPlayer player, CheckReport report) throws RobotException {
        GameConnection c = player.connection();
        int mark = c.inbox().size();
        long id = c.send(gmSetLevel, GmSetPlayerLevelRequest.newBuilder().setLevel(30).build());
        Optional<Received> tip = c.await(mark, r -> r.messageId() == sendTip, requestTimeout);
        int tipId = tip.isEmpty() ? -1 : tip.get().parse(TipInfoMessage.parser()).getId();
        Optional<Received> reply = c.await(mark, r -> r.messageId() == gmSetLevel && r.requestId() == id, observeTimeout);
        AttributePanelInfo after = panel(c);
        report.check(tipId == TIP_FEATURE_UNAVAILABLE && reply.isEmpty() && after.getLevel() == 1 && c.isOpen(),
                "生产模式 GM 设等级（175）：gate 推 23 {1006}、不转发、等级不变",
                "23 tip=" + tipId + " 应答=" + reply.isPresent() + " level=" + after.getLevel(), REF);
    }

    /** 切方案的结果：{@code hitCooldown} = 第一次撞上了冷却（25008）、等过之后才切成。 */
    private record Switched(AttributePanelInfo panel, boolean hitCooldown) {
    }

    /** 切方案；冷却中（25008）按切之前面板的冷却截止时刻等一次再切（同基线 robot，最多两次）。 */
    private Switched switchWaitingCooldown(GameConnection c, AttributePanelInfo before, int schemeId)
            throws RobotException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            SwitchAttributeSchemeResponse response = call(c, switchScheme,
                    SwitchAttributeSchemeRequest.newBuilder().setSchemeId(schemeId).build(),
                    SwitchAttributeSchemeResponse.parser());
            int tipId = response.getErrorMessage().getId();
            if (tipId == 0) {
                return new Switched(response.getPanel(), attempt > 1);
            }
            if (tipId != TIP_SWITCH_COOLDOWN || attempt == 2) {
                throw new RobotException("切换方案（171）scheme=" + schemeId + " 被拒 tip=" + tipId);
            }
            long wait = Math.max(1, before.getSwitchCooldownUntil() - System.currentTimeMillis() / 1000 + 1);
            if (wait > MAX_COOLDOWN_WAIT_SECONDS) {
                throw new RobotException("切换冷却过长：要等 " + wait + " 秒（switch_cooldown_until 单位应是 Unix 秒）");
            }
            sleep(Duration.ofSeconds(wait));
        }
        throw new IllegalStateException("不可达");
    }

    private AttributePanelInfo panel(GameConnection c) throws RobotException {
        GetAttributePanelResponse response = call(c, getPanel, GetAttributePanelRequest.getDefaultInstance(),
                GetAttributePanelResponse.parser());
        requireOk("查面板（167）", response.getErrorMessage());
        return response.getPanel();
    }

    private long gold(GameConnection c) throws RobotException {
        GetCurrencyListResponse response = call(c, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(),
                GetCurrencyListResponse.parser());
        if (response.getCurrency().getValuesCount() <= GOLD) {
            throw new RobotException("GetCurrencyList（54）没有金币槽");
        }
        return response.getCurrency().getValues(GOLD);
    }

    private <T extends com.google.protobuf.Message> T call(GameConnection c, int messageId,
                                                           com.google.protobuf.Message body,
                                                           com.google.protobuf.Parser<T> parser) throws RobotException {
        return c.call(messageId, body, parser, requestTimeout);
    }

    private static void requireOk(String step, TipInfoMessage tip) throws RobotException {
        if (tip.getId() != 0) {
            throw new RobotException(step + " 被拒 tip=" + tip.getId());
        }
    }

    private static AttributePoolInfo pool(AttributePanelInfo panel) throws RobotException {
        for (AttributePoolInfo pool : panel.getPoolsList()) {
            if (pool.getPoolId() == POOL) {
                return pool;
            }
        }
        throw new RobotException("面板里没有属性点池 pool_id=" + POOL);
    }

    private static long allocatedOf(AttributePanelInfo panel, int dimensionId) {
        for (AttributeDimensionInfo dimension : panel.getDimensionsList()) {
            if (dimension.getDimensionId() == dimensionId) {
                return Integer.toUnsignedLong(dimension.getAllocated());
            }
        }
        return 0;
    }

    /** 六项二级属性之和（不含当前气血 / 法力，同基线 robot derivedSum）。 */
    private static long derivedSum(AttributePanelInfo panel) {
        DerivedAttributeInfo d = panel.getDerived();
        return d.getMaxHealth() + d.getMaxMana() + d.getPhysicalAttack() + d.getMagicAttack() + d.getSpeed()
                + d.getDefense();
    }

    private static void sleep(Duration duration) throws RobotException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
