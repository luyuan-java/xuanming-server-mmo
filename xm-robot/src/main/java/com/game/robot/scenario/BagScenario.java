package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BagInfo;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.SortBagRequest;
import com.game.proto.SortBagResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.time.Duration;

/**
 * 背包读取 / 整理端到端（191 GetBag、192 SortBag）。2.4 还没有任何物品来源（任务奖励随 2.5），所以只核对空包的契约：
 * 四个包的布局总在、bag_type 回显、容量 100 / 200 / 10 / 200、只有人物背包与仓库可整理、没有物品；bag_type 4 与 2^32−1 回 1005；
 * 装备栏与临时格整理回 1005；人物背包连整理两次都 changed=false 且整包相同；背包里的货币与 54 一致；重登后背包原样。
 */
public final class BagScenario {

    private static final String SERVICE = "SceneBagClientPlayer";
    private static final int TIP_INVALID_PARAMETER = 1005;
    private static final int[] CAPACITIES = {100, 200, 10, 200};
    private static final String REF = "PARITY「背包」行";
    private static final Duration CALL_SPACING = Duration.ofMillis(400);

    private final PlayerFlow flow;
    private final String account;
    private final Duration requestTimeout;
    private final int getBag;
    private final int sortBag;
    private final int getCurrencyList;

    public BagScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                       Duration requestTimeout) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.requestTimeout = requestTimeout;
        this.getBag = registry.requireId(SERVICE, "GetBag");
        this.sortBag = registry.requireId(SERVICE, "SortBag");
        this.getCurrencyList = registry.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "bag" + runTag;
    }

    public String account() {
        return account;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        EnteredPlayer player = null;
        try {
            player = flow.enter(account, new Timings());
            GameConnection c = player.connection();
            BagInfo[] bags = new BagInfo[4];
            for (int type = 0; type < 4; type++) {
                GetBagResponse response = get(c, type);
                BagInfo bag = response.getBag();
                bags[type] = bag;
                report.check(response.getErrorMessage().getId() == 0 && bag.hasLayout()
                                && bag.getLayout().getBagType() == type && bag.getLayout().getCapacity() == CAPACITIES[type]
                                && bag.getLayout().getCanSort() == (type <= 1) && bag.getItemsCount() == 0
                                && bag.getLayout().getSlotsCount() == 0,
                        "GetBag(" + type + ")：空包、布局在、容量 " + CAPACITIES[type] + "、可整理=" + (type <= 1),
                        "tip=" + response.getErrorMessage().getId() + " layout=" + bag.getLayout().toString().replace('\n', ' '),
                        REF);
            }
            for (int type : new int[] {4, -1}) {
                GetBagResponse response = get(c, type);
                report.check(response.getErrorMessage().getId() == TIP_INVALID_PARAMETER && !response.hasBag(),
                        "GetBag(" + Integer.toUnsignedString(type) + ") 回 1005 不带 bag",
                        "tip=" + response.getErrorMessage().getId() + " has_bag=" + response.hasBag(), REF);
            }
            for (int type : new int[] {2, 3}) {
                SortBagResponse response = sort(c, type);
                report.check(response.getErrorMessage().getId() == TIP_INVALID_PARAMETER && !response.hasBag(),
                        "SortBag(" + type + ") 回 1005", "tip=" + response.getErrorMessage().getId(), REF);
            }
            SortBagResponse first = sort(c, 0);
            SortBagResponse second = sort(c, 0);
            report.check(first.getErrorMessage().getId() == 0 && !first.getChanged() && !second.getChanged()
                            && first.getBag().equals(second.getBag()) && first.getBag().equals(bags[0]),
                    "SortBag(0) 两次：changed=false、整包不变", "changed=" + first.getChanged() + "/" + second.getChanged(), REF);
            GetCurrencyListResponse currency = c.call(getCurrencyList, GetCurrencyListRequest.getDefaultInstance(),
                    GetCurrencyListResponse.parser(), requestTimeout);
            report.check(bags[0].getCurrency().equals(currency.getCurrency()), "背包里的货币与 54 一致",
                    "bag=" + bags[0].getCurrency().getValuesList() + " 54=" + currency.getCurrency().getValuesList(), REF);

            c.close();
            player = flow.enter(account, new Timings());
            GetBagResponse again = get(player.connection(), 0);
            report.check(again.getBag().equals(bags[0]), "重登后背包原样", "items=" + again.getBag().getItemsCount(), REF);
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), REF);
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    private GetBagResponse get(GameConnection c, int type) throws RobotException {
        pause();
        return c.call(getBag, GetBagRequest.newBuilder().setBagType(type).build(), GetBagResponse.parser(), requestTimeout);
    }

    private SortBagResponse sort(GameConnection c, int type) throws RobotException {
        pause();
        return c.call(sortBag, SortBagRequest.newBuilder().setBagType(type).build(), SortBagResponse.parser(),
                requestTimeout);
    }

    /** 每次调用前歇一下：gate 按消息号限频（191 / 192 不在表里，取缺省每秒 3 条，同基线），连发会被回 1008。 */
    private static void pause() throws RobotException {
        try {
            Thread.sleep(CALL_SPACING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
