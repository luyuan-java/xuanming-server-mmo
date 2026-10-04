package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AllocatePetPointsRequest;
import com.game.proto.AllocatePetPointsResponse;
import com.game.proto.AttributeDimensionInfo;
import com.game.proto.AttributePanelInfo;
import com.game.proto.AttributePoolInfo;
import com.game.proto.AutoAllocatePetPointsRequest;
import com.game.proto.AutoAllocatePetPointsResponse;
import com.game.proto.GetAttributePanelRequest;
import com.game.proto.GetAttributePanelResponse;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GetPetListRequest;
import com.game.proto.GetPetListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmGrantPetResponse;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.GmSetPlayerLevelResponse;
import com.game.proto.PetDimensionInfo;
import com.game.proto.PetInfo;
import com.game.proto.PetListChangedS2C;
import com.game.proto.PetListInfo;
import com.game.proto.RecallPetRequest;
import com.game.proto.RecallPetResponse;
import com.game.proto.RenamePetRequest;
import com.game.proto.RenamePetResponse;
import com.game.proto.ResetPetPointsRequest;
import com.game.proto.ResetPetPointsResponse;
import com.game.proto.SummonPetRequest;
import com.game.proto.SummonPetResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 宝宝系统端到端（对应 mmorpg robot pet_smoke，Java 用新号、另加改名 / 洗点扣费检查）：181 列表、187 GM 发放、183 出战、185 收回、
 * 186 加点、182 洗点、188 自动加点、189 改名、184 主人升级推送。需要 dev 运行模式（GM 方法放行）。
 * <ol>
 *   <li>新号列表为空（可携带 10、改名 200 金币）→ 发放灵狐（种类 1）：1 级、总点 5、四个维度都属宝宝池 4、资质在表区间内、成长率 = 资质均值、满血；</li>
 *   <li>池隔离：角色面板里没有宝宝池与宝宝维度；发放不存在的种类 26001；金猊（种类 3，要主人 10 级）出战 26003；</li>
 *   <li>GM 设 30 级：先收到 184 再收到 175 应答、宝宝 30 级、总点 +145；</li>
 *   <li>洗点没分配过 26015 → 自动加点只算不落（建议增量和 = 剩余点）→ 按建议加点、剩余归零、气血上限变大 → 重发 26015、减点 26010、
 *       不存在的宝宝 26000；</li>
 *   <li>出战（active_pet_id 与 is_active）→ 重复出战 26004；GM 加金币 → 洗点扣 300、改名扣 200（按 54 余额前后核对）
 *       → 同名 26015、全空格 26007；</li>
 *   <li>重登列表逐字段相同 → 收回 → 再收回 26005。</li>
 * </ol>
 */
public final class PetScenario {

    private static final String SERVICE = "ScenePetClientPlayer";
    private static final String ATTRIBUTE_SERVICE = "SceneAttributeClientPlayer";
    private static final String REF = "PARITY「宝宝」行";
    private static final Duration CALL_SPACING = Duration.ofMillis(400);
    private static final int PET_POOL = 4;
    private static final int GOLD = 0;
    private static final long[][] FOX_APTITUDE = {{8000, 11000}, {10000, 13000}, {7000, 10000}, {11000, 14000}};

    private final PlayerFlow flow;
    private final String account;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    private final int getList;
    private final int summon;
    private final int recall;
    private final int allocate;
    private final int reset;
    private final int autoAllocate;
    private final int rename;
    private final int grant;
    private final int notifyList;
    private final int getPanel;
    private final int gmSetLevel;
    private final int gmAddCurrency;
    private final int getCurrencyList;

    public PetScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                       Duration requestTimeout, Duration observeTimeout) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
        this.getList = registry.requireId(SERVICE, "GetPetList");
        this.summon = registry.requireId(SERVICE, "SummonPet");
        this.recall = registry.requireId(SERVICE, "RecallPet");
        this.allocate = registry.requireId(SERVICE, "AllocatePetPoints");
        this.reset = registry.requireId(SERVICE, "ResetPetPoints");
        this.autoAllocate = registry.requireId(SERVICE, "AutoAllocatePetPoints");
        this.rename = registry.requireId(SERVICE, "RenamePet");
        this.grant = registry.requireId(SERVICE, "GmGrantPet");
        this.notifyList = registry.requireId(SERVICE, "NotifyPetListChanged");
        this.getPanel = registry.requireId(ATTRIBUTE_SERVICE, "GetAttributePanel");
        this.gmSetLevel = registry.requireId(ATTRIBUTE_SERVICE, "GmSetPlayerLevel");
        this.gmAddCurrency = registry.requireId("SceneCurrencyClientPlayer", "GmAddCurrency");
        this.getCurrencyList = registry.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "pet" + runTag;
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

            PetListInfo empty = list(c);
            report.check(empty.getPetsCount() == 0 && empty.getMaxPets() == 10 && empty.getRenameCostGold() == 200,
                    "新号列表为空，可携带 10、改名 200 金币", empty.toString().replace('\n', ' '), REF);

            pause();
            GmGrantPetResponse granted = c.call(grant, GmGrantPetRequest.newBuilder().setPetTableId(1).build(),
                    GmGrantPetResponse.parser(), requestTimeout);
            long petId = granted.getPetId();
            PetInfo fox = find(granted.getPets(), petId);
            report.check(granted.getErrorMessage().getId() == 0 && fox != null, "GM 发放灵狐回新号与全量列表",
                    "tip=" + granted.getErrorMessage().getId() + " pet_id=" + Long.toUnsignedString(petId), REF);
            if (fox == null) {
                return report;
            }
            checkFreshFox(report, fox);
            checkPoolIsolation(report, c);

            pause();
            GmGrantPetResponse unknown = c.call(grant, GmGrantPetRequest.newBuilder().setPetTableId(99).build(),
                    GmGrantPetResponse.parser(), requestTimeout);
            expect(report, unknown.getErrorMessage().getId(), 26001, "发放不存在的种类 99");
            pause();
            long lion = c.call(grant, GmGrantPetRequest.newBuilder().setPetTableId(3).build(), GmGrantPetResponse.parser(),
                    requestTimeout).getPetId();
            expect(report, summon(c, lion).getErrorMessage().getId(), 26003, "金猊要主人 10 级，1 级出战");

            PetInfo at30 = levelUp(report, c, petId, fox);

            expect(report, resetPet(c, petId).getErrorMessage().getId(), 26015, "没分配过就洗点");
            pause();
            AutoAllocatePetPointsResponse auto = c.call(autoAllocate,
                    AutoAllocatePetPointsRequest.newBuilder().setPetId(petId).build(),
                    AutoAllocatePetPointsResponse.parser(), requestTimeout);
            long delta = 0;
            for (Map.Entry<Integer, Integer> entry : auto.getSuggestedMap().entrySet()) {
                delta += entry.getValue() - allocated(at30, entry.getKey());
            }
            PetInfo afterAuto = find(list(c), petId);
            report.check(auto.getErrorMessage().getId() == 0 && delta == at30.getRemainingPoints()
                            && afterAuto != null && afterAuto.getRemainingPoints() == at30.getRemainingPoints(),
                    "自动加点只算不落：建议增量和 = 剩余点 " + at30.getRemainingPoints(),
                    "suggested=" + auto.getSuggestedMap() + " delta=" + delta, REF);

            AllocatePetPointsResponse allocated = allocatePet(c, petId, auto.getSuggestedMap());
            PetInfo afterAllocate = find(allocated.getPets(), petId);
            report.check(allocated.getErrorMessage().getId() == 0 && afterAllocate != null
                            && afterAllocate.getRemainingPoints() == 0
                            && afterAllocate.getDerived().getMaxHealth() > at30.getDerived().getMaxHealth(),
                    "按建议加点：剩余归零、气血上限变大",
                    "tip=" + allocated.getErrorMessage().getId(), REF);
            expect(report, allocatePet(c, petId, auto.getSuggestedMap()).getErrorMessage().getId(), 26015, "重发同一份目标值");
            Map<Integer, Integer> lower = new HashMap<>();
            auto.getSuggestedMap().forEach((dimension, value) -> {
                if (value > 0 && lower.isEmpty()) {
                    lower.put(dimension, value - 1);
                }
            });
            expect(report, allocatePet(c, petId, lower).getErrorMessage().getId(), 26010, "提交比已分配更小的值");
            expect(report, allocatePet(c, petId + 999_999, auto.getSuggestedMap()).getErrorMessage().getId(), 26000,
                    "不存在的宝宝号");

            SummonPetResponse summoned = summon(c, petId);
            PetInfo active = find(summoned.getPets(), petId);
            report.check(summoned.getErrorMessage().getId() == 0 && summoned.getPets().getActivePetId() == petId
                            && active != null && active.getIsActive(),
                    "出战：active_pet_id 与 is_active 都指向这只", "tip=" + summoned.getErrorMessage().getId(), REF);
            expect(report, summon(c, petId).getErrorMessage().getId(), 26004, "重复出战同一只");

            pause();
            GmAddCurrencyResponse gold = c.call(gmAddCurrency, GmAddCurrencyRequest.newBuilder().setCurrencyType(GOLD)
                    .setAmount(1000).build(), GmAddCurrencyResponse.parser(), requestTimeout);
            report.check(gold.getErrorMessage().getId() == 0, "GM 加 1000 金币", "tip=" + gold.getErrorMessage().getId(), REF);
            long goldBeforeReset = gold(c);
            ResetPetPointsResponse resetDone = resetPet(c, petId);
            long resetSpent = goldBeforeReset - gold(c);
            PetInfo afterReset = find(resetDone.getPets(), petId);
            report.check(resetDone.getErrorMessage().getId() == 0 && afterReset != null
                            && afterReset.getRemainingPoints() == afterReset.getTotalPoints() && resetSpent == 300,
                    "30 级洗点扣 300 金币、点数全部返还",
                    "tip=" + resetDone.getErrorMessage().getId() + " 扣 " + resetSpent, REF);
            long goldBeforeRename = gold(c);
            RenamePetResponse renamed = renamePet(c, petId, "小狐");
            long renameSpent = goldBeforeRename - gold(c);
            PetInfo afterRename = find(renamed.getPets(), petId);
            report.check(renamed.getErrorMessage().getId() == 0 && afterRename != null
                            && afterRename.getName().equals("小狐") && renameSpent == empty.getRenameCostGold(),
                    "改名扣 200 金币", "tip=" + renamed.getErrorMessage().getId() + " 扣 " + renameSpent, REF);
            expect(report, renamePet(c, petId, "小狐").getErrorMessage().getId(), 26015, "改成同一个名字");
            expect(report, renamePet(c, petId, "   ").getErrorMessage().getId(), 26007, "全空格的名字");
            PetListInfo before = list(c);

            c.close();
            player = flow.enter(account, new Timings());
            c = player.connection();
            PetListInfo again = list(c);
            report.check(again.equals(before), "重登后宝宝列表逐字段相同（出战、等级、资质、名字、二级属性）",
                    "pets=" + again.getPetsCount() + " active=" + Long.toUnsignedString(again.getActivePetId()), REF);

            pause();
            RecallPetResponse recalled = c.call(recall, RecallPetRequest.getDefaultInstance(), RecallPetResponse.parser(),
                    requestTimeout);
            report.check(recalled.getErrorMessage().getId() == 0 && recalled.getPets().getActivePetId() == 0,
                    "收回", "tip=" + recalled.getErrorMessage().getId(), REF);
            pause();
            expect(report, c.call(recall, RecallPetRequest.getDefaultInstance(), RecallPetResponse.parser(),
                    requestTimeout).getErrorMessage().getId(), 26005, "没有出战宝宝时再收回");
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), REF);
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    private void checkFreshFox(CheckReport report, PetInfo fox) {
        List<PetDimensionInfo> dimensions = fox.getDimensionsList();
        boolean dimensionsOk = dimensions.size() == 4;
        long aptitudeSum = 0;
        for (int i = 0; dimensionsOk && i < 4; i++) {
            PetDimensionInfo dimension = dimensions.get(i);
            dimensionsOk = dimension.getDimensionId() == 401 + i && dimension.getPoolId() == PET_POOL
                    && dimension.getAptitude() >= FOX_APTITUDE[i][0] && dimension.getAptitude() <= FOX_APTITUDE[i][1];
            aptitudeSum += dimension.getAptitude();
        }
        report.check(fox.getLevel() == 1 && fox.getTotalPoints() == 5 && fox.getRemainingPoints() == 5
                        && dimensionsOk && fox.getGrowth() == aptitudeSum / 4 && fox.getDerived().getMaxHealth() > 0
                        && fox.getDerived().getHealth() == fox.getDerived().getMaxHealth() && fox.getName().equals("灵狐"),
                "灵狐：1 级、总点 5、维度 401–404 属宝宝池、资质在表区间内、成长率 = 资质均值、满血",
                fox.toString().replace('\n', ' '), REF);
    }

    private void checkPoolIsolation(CheckReport report, GameConnection c) throws RobotException {
        pause();
        GetAttributePanelResponse panel = c.call(getPanel, GetAttributePanelRequest.getDefaultInstance(),
                GetAttributePanelResponse.parser(), requestTimeout);
        AttributePanelInfo info = panel.getPanel();
        boolean isolated = info.getPoolsList().stream().noneMatch(pool -> pool.getPoolId() == PET_POOL)
                && info.getDimensionsList().stream().noneMatch(dimension -> dimension.getPoolId() == PET_POOL);
        report.check(isolated, "池隔离：角色面板里没有宝宝池 4 与宝宝维度",
                "pools=" + info.getPoolsList().stream().map(AttributePoolInfo::getPoolId).toList()
                        + " dimensions=" + info.getDimensionsList().stream().map(AttributeDimensionInfo::getDimensionId).toList(),
                REF);
    }

    private PetInfo levelUp(CheckReport report, GameConnection c, long petId, PetInfo before) throws RobotException {
        pause();
        int mark = c.inbox().size();
        GmSetPlayerLevelResponse level = c.call(gmSetLevel, GmSetPlayerLevelRequest.newBuilder().setLevel(30).build(),
                GmSetPlayerLevelResponse.parser(), requestTimeout);
        Optional<Received> pushed = c.await(mark, r -> r.messageId() == notifyList, observeTimeout);
        Optional<Received> reply = c.inbox().snapshot(mark).stream().filter(r -> r.messageId() == gmSetLevel).findFirst();
        PetListInfo pushedList = pushed.isPresent() ? pushed.get().parse(PetListChangedS2C.parser()).getPets() : null;
        PetInfo at30 = pushedList == null ? null : find(pushedList, petId);
        boolean ordered = pushed.isPresent() && reply.isPresent() && pushed.get().index() < reply.get().index();
        report.check(level.getErrorMessage().getId() == 0 && at30 != null && ordered && at30.getLevel() == 30
                        && at30.getTotalPoints() - before.getTotalPoints() == 145,
                "GM 设 30 级：先收到 184 再回应答、宝宝跟着到 30 级、总点 +145",
                at30 == null ? "没收到 184" : "level=" + at30.getLevel() + " total=" + at30.getTotalPoints(), REF);
        return at30 != null ? at30 : before;
    }

    private static int allocated(PetInfo pet, int dimensionId) {
        for (PetDimensionInfo dimension : pet.getDimensionsList()) {
            if (dimension.getDimensionId() == dimensionId) {
                return dimension.getAllocated();
            }
        }
        return 0;
    }

    private static PetInfo find(PetListInfo list, long petId) {
        return list.getPetsList().stream().filter(pet -> pet.getPetId() == petId).findFirst().orElse(null);
    }

    private static void expect(CheckReport report, int actual, int expected, String name) {
        report.check(actual == expected, name + " 回 " + expected, "tip=" + actual, REF);
    }

    private PetListInfo list(GameConnection c) throws RobotException {
        pause();
        GetPetListResponse response = c.call(getList, GetPetListRequest.getDefaultInstance(), GetPetListResponse.parser(),
                requestTimeout);
        return response.getPets();
    }

    private SummonPetResponse summon(GameConnection c, long petId) throws RobotException {
        pause();
        return c.call(summon, SummonPetRequest.newBuilder().setPetId(petId).build(), SummonPetResponse.parser(),
                requestTimeout);
    }

    private AllocatePetPointsResponse allocatePet(GameConnection c, long petId, Map<Integer, Integer> target)
            throws RobotException {
        pause();
        return c.call(allocate, AllocatePetPointsRequest.newBuilder().setPetId(petId).putAllAllocated(target).build(),
                AllocatePetPointsResponse.parser(), requestTimeout);
    }

    private ResetPetPointsResponse resetPet(GameConnection c, long petId) throws RobotException {
        pause();
        return c.call(reset, ResetPetPointsRequest.newBuilder().setPetId(petId).build(), ResetPetPointsResponse.parser(),
                requestTimeout);
    }

    private RenamePetResponse renamePet(GameConnection c, long petId, String name) throws RobotException {
        pause();
        return c.call(rename, RenamePetRequest.newBuilder().setPetId(petId).setName(name).build(),
                RenamePetResponse.parser(), requestTimeout);
    }

    /** 当前金币（54 GetCurrencyList 的金币槽）。 */
    private long gold(GameConnection c) throws RobotException {
        pause();
        GetCurrencyListResponse response = c.call(getCurrencyList, GetCurrencyListRequest.getDefaultInstance(),
                GetCurrencyListResponse.parser(), requestTimeout);
        if (response.getCurrency().getValuesCount() <= GOLD) {
            throw new RobotException("GetCurrencyList（54）没有金币槽");
        }
        return response.getCurrency().getValues(GOLD);
    }

    /**
     * 每次调用前歇一下：gate 按消息号限频（宝宝 181–189 在 MessageLimiter 表里是每秒 5–10 条，37 / 54 取缺省每秒 3 条），
     * 连发会被回 1008。
     */
    private static void pause() throws RobotException {
        try {
            Thread.sleep(CALL_SPACING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
