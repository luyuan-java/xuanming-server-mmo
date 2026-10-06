package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.common.RunMode;
import com.game.contract.MessageMethod;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.PlayerState;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocatePetPointsRequest;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.AutoAllocateAttributePointsRequest;
import com.game.proto.AutoAllocatePetPointsRequest;
import com.game.proto.CreateAttributeSchemeRequest;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.GetActivityListRequest;
import com.game.proto.GetAttributePanelRequest;
import com.game.proto.GetBagRequest;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GetMissionListRequest;
import com.game.proto.GetPetListRequest;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.ListSkillsRequest;
import com.game.proto.MessageContent;
import com.game.proto.MissionActionRequest;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.MoveSyncC2S;
import com.game.proto.RecallPetRequest;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.RenameAttributeSchemeRequest;
import com.game.proto.RenamePetRequest;
import com.game.proto.ResetAttributePointsRequest;
import com.game.proto.ResetPetPointsRequest;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoRequest;
import com.game.proto.SortBagRequest;
import com.game.proto.SortBagResponse;
import com.game.proto.SummonPetRequest;
import com.game.proto.SwitchAttributeSchemeRequest;
import com.game.proto.TipInfoMessage;
import com.game.proto.Vector3;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpService;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.player.BagType;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerData;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneFeature;
import com.game.scene.world.SceneMessageIds;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 回合制战斗在途闸矩阵（scene-battle-spec §7.13 闸表、§2.2 / §2.3；§13.2 InBattleGateMatrixTest 一行逐条）。全功能装配（同 SceneNode，
 * {@link BattleFixture}），<b>遍历注册表</b>：
 * <ul>
 *   <li>每个已注册方法的 {@code BattlePolicy} 与 §7.13 的表逐项相等；新注册的方法不在表里（或没有探测）即失败，表里有而没注册的也失败；
 *       夹具的功能清单必须覆盖主代码里全部 {@code SceneFeature} 实现类（新功能类绕不过这张表）；</li>
 *   <li>每个已注册方法 × PREPARING / FIGHTING 各打一遍：GATED 回基线码且状态不变、DROP 静默丢且位置不变、STOP_ONLY 只清速度、
 *       ALLOW「照常」= 对照玩家（配置相同、不在战斗）办得成，在途玩家得到同样的结果（钉住不闸）；每条请求在
 *       {@code gate_rejects{gate}} 上恰好计在自己那个闸上一次（ALLOW 的不计）；解冻后同一条请求恢复成对照玩家的结果；</li>
 *   <li>表格之外的口径：63 的 3023 先于换图在途的 3014；84 的 7004 在 1001 之后、目标 7002 与 7001 分开；192 类型合法后才判；
 *       资产通道 debit / credit 回 RETRY 27002 且账本不变、abort 照常；没声明策略的方法缺省 REJECT（应答内 1005）。</li>
 * </ul>
 * 交出冻结（FREEZING）与战斗的先后（27003 先于 27002、3014 等）在 {@code HandOffBattleExclusionTest}。
 */
class InBattleGateMatrixTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final int TWIN_SESSION = 12;
    private static final long TWIN = 1002;
    private static final long BATTLE = 7;
    private static final int LEVEL = 30;
    private static final Vec3 START = new Vec3(5, 5, 0);
    private static final String SECRET = "in-battle-gate-test-secret-0123456789";

    private static final int INVALID_TABLE_ID = 1001;
    private static final int INVALID_PARAMETER = 1005;
    private static final int FEATURE_UNAVAILABLE = 1006;
    private static final int ENTER_FAILED = 3023;
    private static final int CHANGING_SCENE = 3014;
    private static final int SKILL_INVALID_TARGET_ID = 7001;
    private static final int SKILL_INVALID_TARGET = 7002;
    private static final int SKILL_CANNOT_CAST = 7004;
    private static final int ATTRIBUTE_IN_BATTLE = 25011;
    private static final int PET_IN_BATTLE = 26008;
    private static final int ASSET_IN_BATTLE = 27002;

    /** 角色属性池与其中的力量维度、宝宝池里的一个维度（正式配表）。 */
    private static final int PLAYER_POOL = 1;
    private static final int STRENGTH = 103;
    private static final int PET_DIMENSION = 403;
    /** 任务 12（击杀 1 号怪一只，奖励是物品）：探测玩家已做完、待领；任务 13：还没接。 */
    private static final int CLAIMABLE_MISSION = 12;
    private static final int FRESH_MISSION = 13;
    private static final int MISSION_MONSTER = 1;

    private static final String SCENE = "SceneSceneClientPlayer";
    private static final String MOVE = "SceneMovementClientPlayer";
    private static final String SKILL = "SceneSkillClientPlayer";
    private static final String CURRENCY = "SceneCurrencyClientPlayer";
    private static final String ATTRIBUTE = "SceneAttributeClientPlayer";
    private static final String BAG = "SceneBagClientPlayer";
    private static final String MISSION = "SceneMissionClientPlayer";
    private static final String ACTIVITY = "SceneActivityClientPlayer";
    private static final String PET = "ScenePetClientPlayer";

    /** 战斗中的预期（除了正数 = 应答里的拒绝码之外的三种）。 */
    private static final int SAME_AS_NOT_IN_BATTLE = -1;
    private static final int DROPPED = -2;
    private static final int STOPPED_ONLY = -3;

    /** 规格 §7.13 闸表的一行：按消息号列（号与名一并钉住），{@code inBattle} 是战斗中的预期。 */
    private record Row(int specId, String service, String method, BattlePolicy policy, int inBattle) {

        String key() {
            return service + method;
        }
    }

    /** §7.13 的表，逐行照抄（顺序同规格）。 */
    private static final List<Row> TABLE = List.of(
            new Row(134, MOVE, "MoveStart", BattlePolicy.DROP, DROPPED),
            new Row(132, MOVE, "MoveSync", BattlePolicy.DROP, DROPPED),
            new Row(131, MOVE, "MoveStop", BattlePolicy.STOP_ONLY, STOPPED_ONLY),
            new Row(63, SCENE, "EnterScene", BattlePolicy.GATED, ENTER_FAILED),
            new Row(84, SKILL, "ReleaseSkill", BattlePolicy.GATED, SKILL_CANNOT_CAST),
            new Row(168, ATTRIBUTE, "AllocateAttributePoints", BattlePolicy.GATED, ATTRIBUTE_IN_BATTLE),
            new Row(172, ATTRIBUTE, "ResetAttributePoints", BattlePolicy.GATED, ATTRIBUTE_IN_BATTLE),
            new Row(174, ATTRIBUTE, "CreateAttributeScheme", BattlePolicy.GATED, ATTRIBUTE_IN_BATTLE),
            new Row(171, ATTRIBUTE, "SwitchAttributeScheme", BattlePolicy.GATED, ATTRIBUTE_IN_BATTLE),
            new Row(169, ATTRIBUTE, "RenameAttributeScheme", BattlePolicy.GATED, ATTRIBUTE_IN_BATTLE),
            new Row(175, ATTRIBUTE, "GmSetPlayerLevel", BattlePolicy.GATED, ATTRIBUTE_IN_BATTLE),
            new Row(183, PET, "SummonPet", BattlePolicy.GATED, PET_IN_BATTLE),
            new Row(185, PET, "RecallPet", BattlePolicy.GATED, PET_IN_BATTLE),
            new Row(186, PET, "AllocatePetPoints", BattlePolicy.GATED, PET_IN_BATTLE),
            new Row(182, PET, "ResetPetPoints", BattlePolicy.GATED, PET_IN_BATTLE),
            new Row(189, PET, "RenamePet", BattlePolicy.GATED, PET_IN_BATTLE),
            new Row(187, PET, "GmGrantPet", BattlePolicy.GATED, PET_IN_BATTLE),
            new Row(192, BAG, "SortBag", BattlePolicy.GATED, INVALID_PARAMETER),
            // ALLOW：只读
            new Row(43, SCENE, "SceneInfoC2S", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(77, SKILL, "ListSkills", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(54, CURRENCY, "GetCurrencyList", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(167, ATTRIBUTE, "GetAttributePanel", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(181, PET, "GetPetList", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(190, ACTIVITY, "GetActivityList", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(191, BAG, "GetBag", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(193, MISSION, "GetMissionList", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            // ALLOW：只给建议
            new Row(173, ATTRIBUTE, "AutoAllocateAttributePoints", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(188, PET, "AutoAllocatePetPoints", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            // ALLOW：GM 货币
            new Row(37, CURRENCY, "GmAddCurrency", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(49, CURRENCY, "GmDeductCurrency", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(94, CURRENCY, "GmBlockCurrency", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(95, CURRENCY, "GmUnblockCurrency", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            // ALLOW：任务
            new Row(194, MISSION, "AcceptMission", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE),
            new Row(195, MISSION, "ClaimMissionReward", BattlePolicy.ALLOW, SAME_AS_NOT_IN_BATTLE));

    private static final Map<String, Row> ROWS = rowsByKey();

    private static Map<String, Row> rowsByKey() {
        Map<String, Row> out = new LinkedHashMap<>();
        for (Row row : TABLE) {
            if (out.put(row.key(), row) != null) {
                throw new IllegalStateException("表里重复：" + row.key());
            }
        }
        return out;
    }

    /** 被探测的一方：所在的那套装配、玩家、出战中的宝宝、没出战的宝宝。 */
    private record Subject(BattleFixture world, ScenePlayer player, long activePet, long sparePet) {
    }

    /** 一条请求引出的全部可见结果：发给本人的消息号序列、应答里的码（没有应答为 null）、所在场景、位置、速度。 */
    private record Outcome(List<Integer> messageIds, Integer tip, long sceneId, Vec3 position, Vec3 velocity) {
    }

    private final BattleFixture f = new BattleFixture();
    /**
     * 对照组：另一套一模一样的装配（场景号、实体号、宝宝号的发号起点都相同），里面是一名配置完全相同、但<b>不在战斗</b>的玩家。
     * 同一条请求在两边引出的结果可以逐项相等地比（放在同一个世界里会互相看见，多出 47 / 70 之类的旁观推送）。
     */
    private final BattleFixture control = new BattleFixture();

    // ================================================================== 注册表

    /** 全功能装配下注册了处理器的全部方法（键 + 消息号），按消息号升序。 */
    static List<MessageMethod> registeredMethods() {
        ClientRequestHandler handler = new BattleFixture().handler;
        return Contracts.REGISTRY.all().stream().filter(m -> handler.battlePolicy(m.messageId()) != null)
                .sorted(Comparator.comparingInt(MessageMethod::messageId)).toList();
    }

    static Stream<Arguments> registered() {
        return registeredMethods().stream().map(m -> Arguments.of(m.key(), m.messageId()));
    }

    static Stream<Arguments> registeredInEachPhase() {
        List<MessageMethod> methods = registeredMethods();
        return Stream.of(Phase.PREPARING, Phase.FIGHTING)
                .flatMap(phase -> methods.stream().map(m -> Arguments.of(phase, m.key())));
    }

    // ================================================================== 策略表

    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("registered")
    void 每个已注册方法的战斗策略与规格闸表一致_新方法不在表里即失败(String key, int messageId) {
        Row row = ROWS.get(key);

        assertThat(row).as("%s（%s）已注册，但不在 scene-battle-spec §7.13 的闸表里：先在规格里定它的战斗策略，再补这张表与探测", key, messageId)
                .isNotNull();
        assertThat(f.handler.battlePolicy(messageId)).as("%s 的战斗策略", key).isEqualTo(row.policy());
        assertThat(messageId).as("%s 的消息号与规格按号列的表对得上", key).isEqualTo(row.specId());
    }

    @Test
    void 规格闸表与注册表一一对应_不多不少() {
        Map<String, BattlePolicy> registered = new TreeMap<>();
        for (MessageMethod method : registeredMethods()) {
            registered.put(method.key(), f.handler.battlePolicy(method.messageId()));
        }
        Map<String, BattlePolicy> expected = new TreeMap<>();
        TABLE.forEach(row -> expected.put(row.key(), row.policy()));

        assertThat(registered).containsExactlyInAnyOrderEntriesOf(expected);
        // 规格按号列：逐类再用号核一遍
        assertThat(List.of(134, 132)).allSatisfy(id -> assertThat(f.handler.battlePolicy(id)).isEqualTo(BattlePolicy.DROP));
        assertThat(f.handler.battlePolicy(131)).isEqualTo(BattlePolicy.STOP_ONLY);
        assertThat(List.of(63, 84, 168, 172, 174, 171, 169, 175, 183, 185, 186, 182, 189, 187, 192))
                .allSatisfy(id -> assertThat(f.handler.battlePolicy(id)).as("%s", id).isEqualTo(BattlePolicy.GATED));
        assertThat(List.of(43, 77, 54, 167, 181, 190, 191, 193, 173, 188, 37, 49, 94, 95, 194, 195))
                .allSatisfy(id -> assertThat(f.handler.battlePolicy(id)).as("%s", id).isEqualTo(BattlePolicy.ALLOW));
        assertThat(f.handler.battlePolicy(136)).as("没有处理器的方法没有策略（战斗与否都回 1006）").isNull();
        assertThat(registered).as("现有方法逐个显式声明，没有一个落到缺省 REJECT（D11：没有行为变化）")
                .doesNotContainValue(BattlePolicy.REJECT);
    }

    /**
     * 「遍历注册表」遍历的是夹具装配出来的那张表，所以夹具得把主代码里的玩法功能装全：扫一遍主代码编译产物里全部 {@link SceneFeature} 实现类，
     * 必须个个都在夹具的功能清单里。以后新加一个功能类而没放进夹具（它的方法就绕过了上面两条核对）会先红在这里；放进去之后它的方法又必须进
     * §7.13 的表与探测。（{@code SceneNode} 的清单是手写的同样七个，那一份没有单测，靠 robot 端到端。）
     */
    @Test
    void 夹具装配覆盖主代码里全部玩法功能类_新功能不进夹具即失败() throws Exception {
        java.nio.file.Path classes = java.nio.file.Path.of(SceneFeature.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(classes).as("主代码编译产物目录").isDirectory();
        java.util.Set<String> implementors = new java.util.TreeSet<>();
        try (Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(classes)) {
            for (java.nio.file.Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "");
                // 只加载不初始化；每个类都得加载得起来（加载不了的会抛出来，不悄悄跳过）
                Class<?> type = Class.forName(name, false, SceneFeature.class.getClassLoader());
                if (SceneFeature.class.isAssignableFrom(type) && !type.isInterface() && !type.isSynthetic()
                        && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                    implementors.add(type.getName());
                }
            }
        }

        assertThat(implementors).as("扫到了主代码里的功能类").hasSizeGreaterThanOrEqualTo(7);
        assertThat(f.features.stream().map(feature -> feature.getClass().getName()).toList())
                .as("夹具的功能清单 = 主代码里全部 SceneFeature 实现类").containsExactlyInAnyOrderElementsOf(implementors);
    }

    @Test
    void 玩法功能声明DROP或STOP_ONLY或不给战斗策略_启动即失败() {
        for (BattlePolicy moveOnly : List.of(BattlePolicy.DROP, BattlePolicy.STOP_ONLY)) {
            SceneFeature feature = r -> r.on(CURRENCY, "GetCurrencyList", GetCurrencyListRequest.class, FreezePolicy.READ_ONLY,
                    moveOnly, (call, req) -> { });
            assertThatThrownBy(() -> new ClientRequestHandler(f.world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(feature)))
                    .as("%s 只给场景核心的移动上行", moveOnly).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(moveOnly.name());
            // 应答是 Empty 的方法（静默丢不会让客户端干等）同样不行：这两种策略在分发处按「移动」计数，玩法功能一律不得声明
            SceneFeature silent = r -> r.on(ATTRIBUTE, "NotifyAttributePanelChanged", AttributePanelChangedS2C.class,
                    FreezePolicy.READ_ONLY, moveOnly, (call, req) -> { });
            assertThatThrownBy(() -> new ClientRequestHandler(f.world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(silent)))
                    .as("%s：应答是 Empty 的玩法方法也不行", moveOnly).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(moveOnly.name()).hasMessageContaining("移动上行");
        }
        // 对照：同一个 Empty 应答的方法声明 ALLOW / GATED / REJECT 都注册得上
        for (BattlePolicy ok : List.of(BattlePolicy.ALLOW, BattlePolicy.GATED, BattlePolicy.REJECT)) {
            SceneFeature silent = r -> r.on(ATTRIBUTE, "NotifyAttributePanelChanged", AttributePanelChangedS2C.class,
                    FreezePolicy.READ_ONLY, ok, (call, req) -> { });
            ClientRequestHandler handler = new ClientRequestHandler(f.world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(silent));
            assertThat(handler.battlePolicy(Contracts.REGISTRY.requireId(ATTRIBUTE, "NotifyAttributePanelChanged"))).isEqualTo(ok);
        }
        SceneFeature undeclared = r -> r.on(CURRENCY, "GetCurrencyList", GetCurrencyListRequest.class, FreezePolicy.READ_ONLY,
                null, (call, req) -> { });
        assertThatThrownBy(() -> new ClientRequestHandler(f.world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(undeclared)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("战斗策略");
    }

    /**
     * 四参数重载（两种策略都不声明）得到缺省 REJECT：战斗中回<b>应答内</b> {@code error_message{1005}}、不进处理器；应答是 Empty 的静默丢；
     * 每条各计一次 {@code gate_rejects{default}}；解冻后照常进处理器。
     */
    @Test
    void 没声明策略的方法缺省REJECT_战斗中回应答内1005不进处理器_无应答的静默丢_解冻后照常() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SceneFeature undeclared = r -> {
            r.on(CURRENCY, "GetCurrencyList", GetCurrencyListRequest.class, (call, req) -> {
                calls.incrementAndGet();
                call.reply(GetCurrencyListResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(0)).build());
            });
            r.on(ATTRIBUTE, "NotifyAttributePanelChanged", AttributePanelChangedS2C.class, (call, req) -> calls.incrementAndGet());
        };
        ClientRequestHandler handler = new ClientRequestHandler(f.world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(undeclared));
        int getCurrencyList = Contracts.REGISTRY.requireId(CURRENCY, "GetCurrencyList");
        int notify = Contracts.REGISTRY.requireId(ATTRIBUTE, "NotifyAttributePanelChanged");
        assertThat(handler.battlePolicy(getCurrencyList)).isEqualTo(BattlePolicy.REJECT);
        assertThat(handler.battlePolicy(notify)).isEqualTo(BattlePolicy.REJECT);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, BATTLE);
        f.sink.clear();

        forward(handler, player, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 41);
        forward(handler, player, notify, AttributePanelChangedS2C.getDefaultInstance(), 42);

        assertThat(calls).as("都没进处理器").hasValue(0);
        assertThat(f.sink.to(BattleFixture.LINK, SESSION)).singleElement().satisfies(reply -> {
            assertThat(reply.getMessageId()).isEqualTo(getCurrencyList);
            assertThat(reply.getId()).isEqualTo(41L);
            assertThat(reply.hasErrorMessage()).as("拒绝码在应答体里，不在信封").isFalse();
            assertThat(GetCurrencyListResponse.parseFrom(reply.getSerializedMessage()).getErrorMessage().getId())
                    .isEqualTo(INVALID_PARAMETER);
        });
        assertThat(gateRejects("default")).isEqualTo(2);

        f.cancel(PLAYER, BATTLE);
        f.drain();
        assertThat(player.inBattle()).isFalse();
        forward(handler, player, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 43);
        forward(handler, player, notify, AttributePanelChangedS2C.getDefaultInstance(), 44);

        assertThat(calls).hasValue(2);
        assertThat(tipOf(f.sink.to(BattleFixture.LINK, SESSION).getLast())).isZero();
        assertThat(gateRejects("default")).isEqualTo(2);
    }

    /**
     * 没有处理器的方法排在战斗闸之前（§7.13：闸在 GM 闸与「有没有处理器」之后）：战斗与否都回信封 / 应答内 1006、应答是 Empty 的忽略，
     * 不计任何闸。其中 226（跨 zone 传送，5.4 还没做）落地时必须先进 §7.13 的表（基线 3025 kZoneTravelInBattle），由上面的
     * 「新方法不在表里即失败」把关；这里钉住的是现状。
     */
    @Test
    void 没有处理器的方法_排在战斗闸之前_战斗中照旧回1006_不计闸拒() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, BATTLE);
        f.sink.clear();
        Map<String, Double> before = gateRejectsByGate();

        for (int messageId : List.of(136, 226)) {
            MessageMethod method = Contracts.REGISTRY.byId(messageId).orElseThrow();
            assertThat(f.handler.battlePolicy(messageId)).as("%s 没有处理器", method.key()).isNull();
            f.request(player, messageId, method.requestPrototype().getDefaultInstanceForType());
            List<MessageContent> replies = f.pushes(player, messageId);
            if (method.responsePrototype() instanceof com.game.proto.Empty) {
                assertThat(replies).as("%s 没有应答：忽略", method.key()).isEmpty();
            } else {
                assertThat(replies).as("%s", method.key()).hasSize(1);
                assertThat(tipOf(replies.get(0))).as("%s：功能未开放，不是在途闸的码", method.key()).isEqualTo(FEATURE_UNAVAILABLE);
            }
        }

        assertThat(f.messageIds(player)).as("除了应答没有别的下行").allMatch(id -> id == 136 || id == 226);
        assertThat(gateRejectsSince(before)).as("没有处理器的方法不计战斗闸").isEmpty();
        assertThat(player.inBattle()).isTrue();
    }

    // ================================================================== 在途玩家逐方法

    /**
     * 每个已注册的方法在 PREPARING / FIGHTING 各打一遍。对照玩家（配置完全相同、不在战斗）先发同一条请求，得到「照常」的结果：
     * <ul>
     *   <li>GATED：对照得 0（请求本身合法），在途玩家得基线码、只回这一条应答、可持久化状态 / 场景 / 位置都不变；</li>
     *   <li>DROP / STOP_ONLY：对照的位置变了，在途玩家什么都不收、位置不变、速度为 0，计 {@code moves{in_battle}}；</li>
     *   <li>ALLOW：在途玩家与对照的结果相同（钉住不闸）。</li>
     * </ul>
     * 然后解冻（PREPARING 取消备战、FIGHTING 结算应用），同一条请求得到与对照相同的结果。
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("registeredInEachPhase")
    void 在途玩家逐方法按闸表处理_解冻后恢复(Phase phase, String key) throws Exception {
        Row row = ROWS.get(key);
        assertThat(row).as("%s 已注册，但不在 §7.13 的闸表里", key).isNotNull();
        Function<Subject, Message> request = requests().get(key);
        assertThat(request).as("%s 没有探测请求（新加方法要在 requests() 里补一行）", key).isNotNull();
        Subject subject = subject(f);
        Subject twin = subject(control);
        f.advance(1_000);
        control.advance(1_000);
        Outcome normal = send(twin, row, request);
        freeze(subject.player(), phase);
        ScenePlayer player = subject.player();
        PlayerState stateBefore = BattleFixture.persistentState(player);
        Scene sceneBefore = player.scene();
        double movesBefore = moves("in_battle");
        Map<String, Double> gatesBefore = gateRejectsByGate();

        Outcome inBattle = send(subject, row, request);

        Map<String, Double> gateRejected = gateRejectsSince(gatesBefore);
        switch (row.inBattle()) {
            case SAME_AS_NOT_IN_BATTLE -> {
                if (normal.tip() != null) {
                    // 「照常」不是「照常被别的原因拒」：对照这条请求本身办得成，战斗中同样办成才说明没加闸（应答是 Empty 的没有码可看）
                    assertThat(normal.tip()).as("对照：%s 这条请求不在战斗时是成功的", key).isZero();
                }
                assertThat(inBattle).as("%s 在战斗中照常（不得加闸）", key).isEqualTo(normal);
                assertThat(gateRejected).as("%s 不闸：gate_rejects 的哪个闸都不计", key).isEmpty();
            }
            case DROPPED, STOPPED_ONLY -> {
                assertThat(normal.position()).as("对照：不在战斗时这条移动被收下").isNotEqualTo(START);
                assertThat(inBattle.messageIds()).as("静默：不回包、不纠偏").isEmpty();
                assertThat(inBattle.position()).as("位置不变").isEqualTo(START);
                assertThat(inBattle.velocity()).as("速度为 0").isEqualTo(Vec3.ORIGIN);
                assertThat(BattleFixture.persistentState(player)).isEqualTo(stateBefore);
                assertThat(moves("in_battle") - movesBefore).as("每条移动上行恰好计一次").isEqualTo(1);
                assertThat(gateRejected).as("恰好计一次 gate_rejects{move}，别的闸不计").isEqualTo(Map.of("move", 1.0));
            }
            default -> {
                assertThat(normal.tip()).as("对照：%s 这条请求不在战斗时是成功的（战斗中的拒绝码才说明是闸拒的）", key).isZero();
                assertThat(inBattle.tip()).as("%s 战斗中的码", key).isEqualTo(row.inBattle());
                assertThat(inBattle.messageIds()).as("只回这一条应答，不带任何推送").containsExactly(row.specId());
                assertThat(BattleFixture.persistentState(player)).as("被闸拒的请求不改任何可持久化状态").isEqualTo(stateBefore);
                assertThat(player.scene()).isSameAs(sceneBefore);
                assertThat(inBattle.position()).isEqualTo(START);
                assertThat(gateRejected).as("%s 恰好计一次 gate_rejects{%s}，别的闸不计（规格 §9）", key, gateOf(row))
                        .isEqualTo(Map.of(gateOf(row), 1.0));
            }
        }
        assertThat(player.battle().freeze()).as("打完一遍冻结还在").isNotNull();
        assertThat(player.battle().freeze().phase()).isEqualTo(phase);

        unfreeze(player, phase);
        Outcome after = send(subject, row, request);

        if (row.inBattle() == SAME_AS_NOT_IN_BATTLE) {
            // 第一次在战斗中已经照常办过了：解冻后只核对没有被闸住（码不是任何一种在途拒绝码）
            assertThat(after.tip()).as("%s 解冻后", key).isNotIn(INVALID_PARAMETER, ATTRIBUTE_IN_BATTLE, PET_IN_BATTLE, ENTER_FAILED);
        } else {
            assertThat(after).as("%s 解冻后同一条请求恢复成不在战斗时的结果", key).isEqualTo(normal);
        }
    }

    /** 每个已注册方法一条探测请求：尽量是「不在战斗就会成」的（战斗中的码才说明是闸拒的，不是参数错）。 */
    private Map<String, Function<Subject, Message>> requests() {
        Map<String, Function<Subject, Message>> out = new LinkedHashMap<>();
        out.put(MOVE + "MoveStart", s -> MoveStartC2S.newBuilder().setStartLocation(new Vec3(6, 5, 0).toLocation())
                .setVelocity(new Vec3(1, 0, 0).toVelocity()).setInputSeq(1).build());
        out.put(MOVE + "MoveSync", s -> MoveSyncC2S.newBuilder().setLocation(new Vec3(6, 5, 0).toLocation())
                .setVelocity(new Vec3(1, 0, 0).toVelocity()).setInputSeq(2).build());
        out.put(MOVE + "MoveStop", s -> MoveStopC2S.newBuilder().setEndLocation(new Vec3(6, 5, 0).toLocation()).setInputSeq(3).build());
        out.put(SCENE + "EnterScene", s -> EnterSceneC2SRequest.newBuilder().setSceneInfo(SceneInfoComp.newBuilder()
                .setSceneId(s.world().scene2.sceneId()).setSceneConfigId(s.world().scene2.configId())).build());
        out.put(SCENE + "SceneInfoC2S", s -> SceneInfoRequest.getDefaultInstance());
        // 技能 2：范围技能、无前摇无冷却（正式配表），不在战斗时一定放得出
        out.put(SKILL + "ReleaseSkill", s -> ReleaseSkillRequest.newBuilder().setSkillTableId(2).setTargetId(s.player().entity())
                .setPosition(Vector3.newBuilder().setX(1).setY(2).setZ(3)).build());
        out.put(SKILL + "ListSkills", s -> ListSkillsRequest.getDefaultInstance());

        out.put(CURRENCY + "GetCurrencyList", s -> GetCurrencyListRequest.getDefaultInstance());
        out.put(CURRENCY + "GmAddCurrency", s -> GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(10).build());
        out.put(CURRENCY + "GmDeductCurrency", s -> GmDeductCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(1).build());
        out.put(CURRENCY + "GmBlockCurrency", s -> GmBlockCurrencyRequest.newBuilder().setCurrencyType(1).build());
        out.put(CURRENCY + "GmUnblockCurrency", s -> GmUnblockCurrencyRequest.newBuilder().setCurrencyType(2).build());

        out.put(ATTRIBUTE + "GetAttributePanel", s -> GetAttributePanelRequest.getDefaultInstance());
        out.put(ATTRIBUTE + "AllocateAttributePoints", s -> AllocateAttributePointsRequest.newBuilder().setPoolId(PLAYER_POOL)
                .putAllocated(STRENGTH, 2).build());
        out.put(ATTRIBUTE + "ResetAttributePoints", s -> ResetAttributePointsRequest.newBuilder().setPoolId(PLAYER_POOL).build());
        out.put(ATTRIBUTE + "CreateAttributeScheme", s -> CreateAttributeSchemeRequest.newBuilder().setName("战斗方案").build());
        out.put(ATTRIBUTE + "SwitchAttributeScheme", s -> SwitchAttributeSchemeRequest.newBuilder().setSchemeId(2).build());
        out.put(ATTRIBUTE + "RenameAttributeScheme", s -> RenameAttributeSchemeRequest.newBuilder().setSchemeId(1).setName("改名").build());
        out.put(ATTRIBUTE + "GmSetPlayerLevel", s -> GmSetPlayerLevelRequest.newBuilder().setLevel(LEVEL + 1).build());
        out.put(ATTRIBUTE + "AutoAllocateAttributePoints", s -> AutoAllocateAttributePointsRequest.newBuilder().setPoolId(PLAYER_POOL).build());

        out.put(BAG + "GetBag", s -> GetBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build());
        out.put(BAG + "SortBag", s -> SortBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build());

        out.put(MISSION + "GetMissionList", s -> GetMissionListRequest.getDefaultInstance());
        // 接一条还没接过的任务；领的是已经做完、待领的那条（见 subject）
        out.put(MISSION + "AcceptMission", s -> MissionActionRequest.newBuilder().setMissionId(FRESH_MISSION).build());
        out.put(MISSION + "ClaimMissionReward", s -> MissionActionRequest.newBuilder().setMissionId(CLAIMABLE_MISSION).build());
        out.put(ACTIVITY + "GetActivityList", s -> GetActivityListRequest.getDefaultInstance());

        out.put(PET + "GetPetList", s -> GetPetListRequest.getDefaultInstance());
        out.put(PET + "SummonPet", s -> SummonPetRequest.newBuilder().setPetId(s.sparePet()).build());
        out.put(PET + "RecallPet", s -> RecallPetRequest.getDefaultInstance());
        out.put(PET + "AllocatePetPoints", s -> AllocatePetPointsRequest.newBuilder().setPetId(s.activePet())
                .putAllocated(PET_DIMENSION, 2).build());
        out.put(PET + "ResetPetPoints", s -> ResetPetPointsRequest.newBuilder().setPetId(s.activePet()).build());
        out.put(PET + "RenamePet", s -> RenamePetRequest.newBuilder().setPetId(s.activePet()).setName("小冻").build());
        out.put(PET + "GmGrantPet", s -> GmGrantPetRequest.newBuilder().setPetTableId(1).build());
        out.put(PET + "AutoAllocatePetPoints", s -> AutoAllocatePetPointsRequest.newBuilder().setPetId(s.activePet()).build());
        return out;
    }

    @Test
    void 探测请求覆盖全部已注册的方法_不多不少() {
        assertThat(requests().keySet()).containsExactlyInAnyOrderElementsOf(registeredMethods().stream().map(MessageMethod::key).toList());
    }

    // ================================================================== 表格之外的口径

    /**
     * 63：战斗在途 → 3023，判在换图在途（3014）之前（基线 {@code psh.cpp:54-61}）。两种「在途」同时成立的唯一来路是选目标中（RESOLVING，不冻结）
     * 被迟到确认按锁重建了冻结。
     */
    @Test
    void 换场景63_战斗在途回3023_同时换图在途仍回3023不是3014() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.resolveRemote(player);
        assertThat(enterSceneTip(player, f.scene2)).as("对照：只有换图在途时是 3014").isEqualTo(CHANGING_SCENE);
        // 选目标中，迟到的确认按锁重建 FIGHTING
        f.locks.putLock(PLAYER, BATTLE, BattleFixture.BATTLE_NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.confirm(PLAYER, BATTLE, f.deadline());
        f.drain();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);

        assertThat(enterSceneTip(player, f.scene2)).as("两种在途同时成立：3023 先于 3014").isEqualTo(ENTER_FAILED);

        assertThat(player.scene()).isSameAs(f.scene1);
        assertThat(gateRejects("enter_scene")).isEqualTo(1);
        assertThat(f.targets.pendingCount()).as("没有再发起选目标").isZero();

        // 63 的每个分支都排在战斗闸之后：镜像分支（mirror_config_id ≠ 0）、参数全 0（本该 3005）、就是当前场景（本该 3008）一律先回 3023
        assertThat(tipOf(reply(player, SCENE, "EnterScene", EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setMirrorConfigId(1)).build()))).as("镜像进场").isEqualTo(ENTER_FAILED);
        assertThat(tipOf(reply(player, SCENE, "EnterScene", EnterSceneC2SRequest.getDefaultInstance()))).as("参数全 0").isEqualTo(ENTER_FAILED);
        assertThat(enterSceneTip(player, f.scene1)).as("就是当前场景").isEqualTo(ENTER_FAILED);
        assertThat(gateRejects("enter_scene")).isEqualTo(4);
        assertThat(player.switchPhase()).as("原来的选目标在途不受影响").isEqualTo(SwitchPhase.RESOLVING);
    }

    /** 131：只把速度清零（旁观者下一个同步帧收到速度 0 的 66），<b>不收</b>上报的位置；134 / 132 连速度也不碰。 */
    @Test
    void 移动131_只把残留的速度清零_不收位置_134与132连速度也不动() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        ScenePlayer watcher = f.enter(TWIN_SESSION, TWIN);
        f.fighting(PLAYER, BATTLE);
        f.world.step();
        f.world.step();
        Vec3 before = player.position();
        // 备战时已停步，正常流程里战斗中的速度恒为 0；这里硬摆一个残留速度，才分得出「只清速度」与「整条丢」
        Vec3 residual = new Vec3(1, 0, 0);
        WorldTestAccess.setVelocity(player, residual);
        assertThat(WorldTestAccess.velocityDirty(player)).isFalse();
        f.sink.clear();

        f.request(player, MOVE, "MoveStart", MoveStartC2S.newBuilder().setStartLocation(new Vec3(9, 9, 0).toLocation())
                .setVelocity(new Vec3(0, 1, 0).toVelocity()).build());
        f.request(player, MOVE, "MoveSync", MoveSyncC2S.newBuilder().setLocation(new Vec3(9, 9, 0).toLocation())
                .setVelocity(new Vec3(0, 1, 0).toVelocity()).build());

        assertThat(player.position()).as("134 / 132 不收位置").isEqualTo(before);
        assertThat(player.velocity()).as("134 / 132 静默丢：速度原样（既不收上报的速度，也不清）").isEqualTo(residual);
        assertThat(WorldTestAccess.velocityDirty(player)).isFalse();

        f.request(player, MOVE, "MoveStop", MoveStopC2S.newBuilder().setEndLocation(new Vec3(9, 9, 0).toLocation()).build());

        assertThat(player.velocity().isOrigin()).as("131 只把速度清零").isTrue();
        assertThat(WorldTestAccess.velocityDirty(player)).as("置速度脏位：旁观者收到速度 0 的 66").isTrue();
        assertThat(player.position()).as("131 不收上报的终点").isEqualTo(before);
        f.world.step();
        f.world.step();
        List<MessageContent> synced = f.pushes(watcher, Contracts.IDS.syncBaseAttribute());
        assertThat(synced).as("旁观者恰好收到一条 66").hasSize(1);
        com.game.proto.ActorBaseAttributesS2C attributes = com.game.proto.ActorBaseAttributesS2C.parseFrom(synced.get(0).getSerializedMessage());
        assertThat(attributes.getVelocity()).as("速度全零 = 停了").isEqualTo(Vec3.ORIGIN.toVelocity());
        assertThat(attributes.hasTransform()).as("位置没动，不带 transform").isFalse();
        assertThat(f.messageIds(player)).as("三条都不回包").isEmpty();
        assertThat(moves("in_battle")).isEqualTo(3);
        assertThat(gateRejects("move")).isEqualTo(3);
        assertThat(moves("accepted") + moves("clamped") + moves("corrected")).as("没有一条进了位移裁决").isZero();

        // 速度已经是 0（备战时已停步，正常流程里的常态）：131 是空操作——不再置脏位，旁观者收不到第二条 66（在途的客户端刷 131 换不来广播）
        f.sink.clear();
        assertThat(WorldTestAccess.velocityDirty(player)).as("上一条 66 发出后脏位已清").isFalse();
        f.request(player, MOVE, "MoveStop", MoveStopC2S.newBuilder().setEndLocation(new Vec3(9, 9, 0).toLocation()).build());
        assertThat(WorldTestAccess.velocityDirty(player)).as("速度本来就是 0：不置脏位").isFalse();
        f.world.step();
        f.world.step();
        assertThat(f.pushes(watcher, Contracts.IDS.syncBaseAttribute())).as("没有第二条 66").isEmpty();
        assertThat(player.position()).isEqualTo(before);
        assertThat(moves("in_battle")).as("照样计数").isEqualTo(4);
        assertThat(gateRejects("move")).isEqualTo(4);
    }

    /**
     * 第二轮修正 R2-e：服务器的帧外推整个跳过战斗在途的玩家（基线 {@code movement.cpp:49-52} 的 {@code exclude<InBattleComp>}：进战时的残留速度
     * 不能让玩家在战斗里一路飘走）。备战时已停步、在途的移动上行也不收速度，正常流程里在途玩家的速度恒为 0——这里硬摆一个残留速度，
     * 才看得出外推这一层自己也挡着（纵深防御）：位置不动、速度原样、不置位置脏位；同场景不在战斗的对照玩家照常被外推；解冻后照常外推。
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Phase.class)
    void 帧外推_战斗在途的玩家带残留速度也不动_速度与脏位原样_解冻后照常外推(Phase phase) {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        ScenePlayer idle = f.enter(TWIN_SESSION, TWIN);
        freeze(player, phase);
        f.world.step();
        f.world.step();
        Vec3 residual = new Vec3(2, -4, 1);
        WorldTestAccess.setVelocity(player, residual);
        WorldTestAccess.setVelocity(idle, residual);
        Vec3 before = player.position();
        Vec3 idleExpected = idle.position();
        assertThat(WorldTestAccess.transformDirty(player)).as("前提：位置脏位是干净的").isFalse();

        for (int frame = 0; frame < 5; frame++) {
            f.world.step();
            // 固定步长 0.05 s（20 FPS）
            idleExpected = idleExpected.plusScaled(residual, 0.05);
        }

        assertThat(player.position()).as("在途：一帧都没有外推").isEqualTo(before);
        assertThat(player.velocity()).as("外推不碰在途玩家的速度").isEqualTo(residual);
        assertThat(WorldTestAccess.transformDirty(player)).as("位置没动，不置位置脏位").isFalse();
        assertThat(idle.position()).as("对照：不在战斗的玩家同样的速度照常被外推 5 帧").isEqualTo(idleExpected).isNotEqualTo(before);

        unfreeze(player, phase);
        assertThat(player.velocity()).as("解冻不动速度").isEqualTo(residual);
        f.world.step();

        assertThat(player.position()).as("解冻后照常外推").isEqualTo(before.plusScaled(residual, 0.05));
    }

    /**
     * 84：施法者在途 → 7004，排在「技能不存在 / 未拥有」1001 之后、目标之前；别人打在途的目标 → 7002，与「目标不是本节点玩家」的 7001 分开。
     * 目标解冻后照常命中。
     */
    @Test
    void 放技能84_施法者在途回7004且在1001之后_打在途目标回7002与7001分开_目标解冻后照常() throws Exception {
        ScenePlayer inBattle = f.enter(SESSION, PLAYER);
        ScenePlayer other = f.enter(TWIN_SESSION, TWIN);
        f.prepared(PLAYER, BATTLE);

        assertThat(releaseTip(inBattle, 99, other.entity())).as("技能不存在先回 1001").isEqualTo(INVALID_TABLE_ID);
        assertThat(releaseTip(inBattle, 1, other.entity())).as("施法者在途").isEqualTo(SKILL_CANNOT_CAST);
        assertThat(releaseTip(inBattle, 1, 0)).as("7004 判在目标之前（目标号为 0 本该是 7001）").isEqualTo(SKILL_CANNOT_CAST);
        assertThat(f.count("xm.scene.skill.releases", "result", "caster_in_battle")).isEqualTo(2);
        assertThat(f.count("xm.scene.skill.releases", "result", "unknown_skill")).isEqualTo(1);
        assertThat(gateRejects("skill")).as("1001 不算闸拒").isEqualTo(2);

        // 技能 1 指定目标：打在途的玩家 → 7002；打不存在的实体 → 7001
        assertThat(releaseTip(other, 1, inBattle.entity())).as("目标在途").isEqualTo(SKILL_INVALID_TARGET);
        assertThat(releaseTip(other, 1, 0x7FFF_FFFFL)).as("目标不是本节点玩家").isEqualTo(SKILL_INVALID_TARGET_ID);
        assertThat(f.count("xm.scene.skill.releases", "result", "target_in_battle")).isEqualTo(1);
        assertThat(f.count("xm.scene.skill.releases", "result", "invalid_target")).isEqualTo(1);
        assertThat(gateRejects("skill")).isEqualTo(3);
        assertThat(f.pushes(inBattle, Contracts.IDS.notifySkillUsed())).as("被挡的技能没有广播 70").isEmpty();

        f.cancel(PLAYER, BATTLE);
        f.drain();
        assertThat(releaseTip(other, 1, inBattle.entity())).as("目标解冻后照常命中").isZero();
        assertThat(releaseTip(inBattle, 2, inBattle.entity())).as("施法者解冻后照常放").isZero();

        // 目标闸只管「指定目标」的技能：范围技能（技能 2）在目标方式处就放行了，目标号指向在途的玩家也照常放（基线 ValidateTarget
        // 对 AOE 先返回，skill.cpp:386-389；在途玩家不受实时技能影响由施法点 / buff 那一层保证，两版都未生效，PARITY）
        f.prepared(TWIN, BATTLE + 1);
        f.advance(10_000);
        double rejects = gateRejects("skill");
        assertThat(releaseTip(inBattle, 2, other.entity())).as("范围技能不看目标是否在途").isZero();
        assertThat(releaseTip(inBattle, 1, other.entity())).as("对照：同一个目标，指定目标的技能被拒").isEqualTo(SKILL_INVALID_TARGET);
        assertThat(gateRejects("skill") - rejects).isEqualTo(1);
    }

    /**
     * 192：类型合法之后才判战斗（1005，计 {@code gate_rejects{bag_sort}}）；类型不合法本来就是 1005、不算闸拒。背包确实没被整理
     * （对照玩家同样的背包整理后 {@code changed = true}）。191 照常；{@code BagService} 本身不闸（D48）——战斗中照常入包。
     */
    @Test
    void 整理背包192_类型合法后才判战斗_背包没被整理_BagService不闸() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        ScenePlayer twin = f.enter(TWIN_SESSION, TWIN);
        for (ScenePlayer p : List.of(player, twin)) {
            // 两堆同一种物品：整理会把它们合并
            assertThat(f.bags.addItems(p, BagType.INVENTORY, Map.of(11, 2L), Reason.GM_GRANT, 0, "").tip()).isZero();
            assertThat(f.bags.addItems(p, BagType.INVENTORY, Map.of(10, 3L), Reason.GM_GRANT, 0, "").tip()).isZero();
        }
        SortBagResponse normal = SortBagResponse.parseFrom(reply(twin, BAG, "SortBag",
                SortBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build()).getSerializedMessage());
        assertThat(normal.getErrorMessage().getId()).isZero();
        assertThat(normal.getChanged()).as("对照：这只背包整理后确实会变").isTrue();
        f.fighting(PLAYER, BATTLE);
        PlayerState before = BattleFixture.persistentState(player);

        int illegal = tipOf(reply(player, BAG, "SortBag", SortBagRequest.newBuilder().setBagType(99).build()));
        assertThat(illegal).isEqualTo(INVALID_PARAMETER);
        assertThat(gateRejects("bag_sort")).as("类型不合法先回 1005，不算战斗闸").isZero();

        SortBagResponse gated = SortBagResponse.parseFrom(reply(player, BAG, "SortBag",
                SortBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build()).getSerializedMessage());
        assertThat(gated.getErrorMessage().getId()).isEqualTo(INVALID_PARAMETER);
        assertThat(gated.hasBag()).isFalse();
        assertThat(gateRejects("bag_sort")).isEqualTo(1);
        assertThat(BattleFixture.persistentState(player)).as("背包没被整理").isEqualTo(before);
        assertThat(tipOf(reply(player, BAG, "GetBag", GetBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build())))
                .as("191 照常").isZero();

        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(10, 1L), Reason.GM_GRANT, 0, "").tip())
                .as("BagService 不闸战斗（结算自己要扣药 / 发掉落）").isZero();
        assertThat(BattleFixture.persistentState(player)).isNotEqualTo(before);
    }

    /**
     * 资产通道（§7.13；基线 {@code asset.cpp:750-796}）：战斗在途时 debit / credit 回 RETRY 27002、<b>不记账</b>（账本与钱都不变，
     * 同一个 seq 解冻后照常办）；中止占位（abort）排在战斗闸之前，战斗中照常记下。PREPARING 与 FIGHTING 同样。
     */
    @ParameterizedTest
    @MethodSource("phases")
    void 资产通道_debit与credit回RETRY27002且账本不变_abort照常_解冻后同一个seq照常办(Phase phase) {
        AssetOpService assetOps = new AssetOpService(f.world, f.currency, f.bags, new AssetOpAuth(caller -> SECRET), f.clock,
                f.sceneMetrics);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(f.currency.add(player, Wallet.GOLD, 1000, Reason.GM_GRANT).ok()).isTrue();
        freeze(player, phase);
        PlayerState before = BattleFixture.persistentState(player);
        int audits = f.audit.currencies.size();

        AssetOpResponse debit = assetOps.handle(AssetRpc.DEBIT, signed(AssetRpc.DEBIT, debit(1, 30)));
        AssetOpResponse credit = assetOps.handle(AssetRpc.CREDIT, signed(AssetRpc.CREDIT, credit(1, 40)));

        for (AssetOpResponse response : List.of(debit, credit)) {
            assertThat(response.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
            assertThat(response.getReason()).isEqualTo(ASSET_IN_BATTLE);
        }
        assertThat(BattleFixture.persistentState(player)).as("不记账：账本、钱包都不变").isEqualTo(before);
        assertThat(f.gold(player)).isEqualTo(1000);
        assertThat(f.audit.currencies).hasSize(audits);
        assertThat(gateRejects("asset")).isEqualTo(2);

        // 中止占位照常：给 debit 流的 seq 2 记一个拒绝占位（此后这条 seq 永远拒绝）
        AssetOpResponse abort = assetOps.handle(AssetRpc.ABORT_DEBIT, signed(AssetRpc.ABORT_DEBIT, debit(2, 30)));
        assertThat(abort.getOutcome()).as("中止占位排在战斗闸之前").isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(abort.getReason()).isZero();
        assertThat(BattleFixture.persistentState(player)).as("占位进了账本").isNotEqualTo(before);
        assertThat(gateRejects("asset")).as("abort 不计闸拒").isEqualTo(2);
        assertThat(assetOps.handle(AssetRpc.DEBIT, signed(AssetRpc.DEBIT, debit(2, 30))).getOutcome())
                .as("已见的 seq 只读答复原结局，不受战斗闸影响").isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(f.gold(player)).isEqualTo(1000);

        unfreeze(player, phase);
        long gold = f.gold(player);

        assertThat(assetOps.handle(AssetRpc.DEBIT, signed(AssetRpc.DEBIT, debit(1, 30))).getOutcome())
                .as("被 RETRY 的 seq 没有被记成拒绝，解冻后照常扣").isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(assetOps.handle(AssetRpc.CREDIT, signed(AssetRpc.CREDIT, credit(1, 40))).getOutcome())
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(f.gold(player)).isEqualTo(gold - 30 + 40);
    }

    static Stream<Phase> phases() {
        return Stream.of(Phase.PREPARING, Phase.FIGHTING);
    }

    /** GM 货币（37 / 49）在战斗中不只是「回 0」：钱确实动了、流水照记（基线不闸，交接冻结另有 27003）。 */
    @Test
    void GM货币37与49_战斗中照常入账出账() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, BATTLE);

        assertThat(tipOf(reply(player, CURRENCY, "GmAddCurrency",
                GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(10).build()))).isZero();
        assertThat(tipOf(reply(player, CURRENCY, "GmDeductCurrency",
                GmDeductCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(3).build()))).isZero();

        assertThat(f.gold(player)).isEqualTo(7);
        assertThat(f.audit.currencies).hasSize(2);
        assertThat(player.inBattle()).isTrue();
    }

    /**
     * 任务（194 / 195）在战斗中不只是「回 0」：任务确实接上了；领奖确实把奖励物品放进了背包、记了 {@code QUEST_REWARD} 流水
     * （发奖走的 {@code BagService} 不闸战斗，D48），待领清掉、完成保留。
     */
    @Test
    void 任务194与195_战斗中照常接取与领奖_奖励物品照常入包() throws Exception {
        ScenePlayer player = subject(f).player();
        f.fighting(PLAYER, BATTLE);
        PlayerState before = BattleFixture.persistentState(player);
        int audited = f.audit.items.size();

        assertThat(tipOf(reply(player, MISSION, "AcceptMission", MissionActionRequest.newBuilder().setMissionId(FRESH_MISSION).build())))
                .isZero();
        assertThat(tipOf(reply(player, MISSION, "ClaimMissionReward",
                MissionActionRequest.newBuilder().setMissionId(CLAIMABLE_MISSION).build()))).isZero();

        assertThat(player.missions().isAccepted(FRESH_MISSION)).as("194：任务接上了").isTrue();
        assertThat(player.missions().isClaimable(CLAIMABLE_MISSION)).as("195：待领已清").isFalse();
        assertThat(player.missions().isComplete(CLAIMABLE_MISSION)).isTrue();
        assertThat(f.audit.items.subList(audited, f.audit.items.size())).as("奖励物品入包，每件一条任务奖励流水").isNotEmpty()
                .allSatisfy(item -> {
                    assertThat(item.gained()).isTrue();
                    assertThat(item.playerId()).isEqualTo(PLAYER);
                    assertThat(item.reason()).isEqualTo(Reason.QUEST_REWARD);
                });
        assertThat(BattleFixture.persistentState(player).getBag()).as("背包确实变了").isNotEqualTo(before.getBag());
        assertThat(player.inBattle()).isTrue();
        assertThat(gateRejects("default")).isZero();
    }

    // ================================================================== 工具

    /**
     * 一名 {@value #LEVEL} 级玩家，摆成「每条写请求都办得成」的样子：一百万金币；力量已加 1 点（洗点有东西可洗）；第二套加点方案（可切换）；
     * 两只宝宝，第一只出战并已加 1 点；背包里两堆物品；任务 {@value #CLAIMABLE_MISSION} 已做完待领、任务 {@value #FRESH_MISSION} 还没接。
     * 出站记录与 Redis 调用记录已清。
     */
    private static Subject subject(BattleFixture w) {
        w.repo.put(new PlayerData(PLAYER, 1, 3, 1, "look-" + PLAYER, LEVEL, w.scene1.configId(), START,
                PlayerState.getDefaultInstance(), "玩家" + PLAYER));
        ScenePlayer player = w.reenter(SESSION, PLAYER, 1, w.scene1);
        w.drain();
        assertThat(w.currency.add(player, Wallet.GOLD, 1_000_000, Reason.GM_GRANT).ok()).isTrue();
        assertThat(w.attributes.allocate(player, PLAYER_POOL, Map.of(STRENGTH, 1))).isZero();
        assertThat(w.attributes.createScheme(player, "备用").tipId()).isZero();
        long active = w.pets.grant(player, 1).petId();
        long spare = w.pets.grant(player, 1).petId();
        assertThat(active).isNotZero();
        assertThat(spare).isNotZero();
        assertThat(w.pets.summon(player, active)).isZero();
        assertThat(w.pets.allocate(player, active, Map.of(PET_DIMENSION, 1L))).isZero();
        assertThat(w.bags.addItems(player, BagType.INVENTORY, Map.of(10, 3L), Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(w.missions.accept(player, 0, CLAIMABLE_MISSION)).isZero();
        w.missions.onMonsterKilled(player, MISSION_MONSTER, 1);
        assertThat(player.missions().isClaimable(CLAIMABLE_MISSION)).as("任务 %s 已做完、待领", CLAIMABLE_MISSION).isTrue();
        assertThat(player.missions().isAccepted(FRESH_MISSION)).isFalse();
        w.sink.clear();
        w.locks.clearCalls();
        return new Subject(w, player, active, spare);
    }

    private void freeze(ScenePlayer player, Phase phase) {
        if (phase == Phase.PREPARING) {
            f.prepared(player.playerId(), BATTLE);
        } else {
            f.fighting(player.playerId(), BATTLE);
        }
        assertThat(player.battle().freeze().phase()).isEqualTo(phase);
    }

    /** 解冻：备战中的取消备战（删锁）；战斗中的由结算应用摘掉。 */
    private void unfreeze(ScenePlayer player, Phase phase) {
        if (phase == Phase.PREPARING) {
            f.cancel(player.playerId(), BATTLE);
        } else {
            f.deliver(BattleFixture.settlement(player.playerId(), BATTLE, 5).build());
        }
        f.drain();
        assertThat(player.inBattle()).as("已解冻").isFalse();
    }

    private static Outcome send(Subject who, Row row, Function<Subject, Message> request) throws InvalidProtocolBufferException {
        ScenePlayer player = who.player();
        BattleFixture w = who.world();
        int messageId = Contracts.REGISTRY.requireId(row.service(), row.method());
        int before = w.sink.to(player.session().linkId(), player.session().sessionId()).size();
        long requestId = w.request(player, messageId, request.apply(who));
        List<MessageContent> all = w.sink.to(player.session().linkId(), player.session().sessionId());
        List<Integer> ids = new ArrayList<>();
        Integer tip = null;
        for (MessageContent sent : all.subList(before, all.size())) {
            ids.add(sent.getMessageId());
            if (sent.getMessageId() == messageId && sent.getId() == requestId) {
                tip = tipOf(sent);
            }
        }
        return new Outcome(ids, tip, player.scene().sceneId(), player.position(), player.velocity());
    }

    /** 发一条请求，返回它的应答（没有就失败）。 */
    private MessageContent reply(ScenePlayer player, String service, String method, Message body) {
        int messageId = Contracts.REGISTRY.requireId(service, method);
        long requestId = f.request(player, messageId, body);
        return f.pushes(player, messageId).stream().filter(m -> m.getId() == requestId).findFirst()
                .orElseThrow(() -> new AssertionError(service + method + " 没有应答 request_id=" + requestId));
    }

    private int enterSceneTip(ScenePlayer player, Scene target) throws InvalidProtocolBufferException {
        return tipOf(reply(player, SCENE, "EnterScene", EnterSceneC2SRequest.newBuilder().setSceneInfo(SceneInfoComp.newBuilder()
                .setSceneId(target.sceneId()).setSceneConfigId(target.configId())).build()));
    }

    private int releaseTip(ScenePlayer caster, int skill, long target) throws InvalidProtocolBufferException {
        return tipOf(reply(caster, SKILL, "ReleaseSkill", ReleaseSkillRequest.newBuilder().setSkillTableId(skill).setTargetId(target)
                .setPosition(Vector3.newBuilder().setX(1).setY(2).setZ(3)).build()));
    }

    private static void forward(ClientRequestHandler handler, ScenePlayer player, int messageId, Message body, long requestId) {
        handler.onClientForward(player.session().linkId(), com.game.api.proto.ClientForward.newBuilder()
                .setSessionId(player.session().sessionId()).setPlayerId(player.playerId()).setMessageId(messageId)
                .setBody(body.toByteString()).setRequestId(requestId).build());
    }

    /** 应答体里的 {@code error_message}（没有这个字段的应答取信封上的）。 */
    private static int tipOf(MessageContent reply) throws InvalidProtocolBufferException {
        MessageMethod method = Contracts.REGISTRY.byId(reply.getMessageId()).orElseThrow();
        Message response = method.responsePrototype().getParserForType().parseFrom(reply.getSerializedMessage());
        FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
        if (field == null) {
            return reply.getErrorMessage().getId();
        }
        return ((TipInfoMessage) response.getField(field)).getId();
    }

    private double gateRejects(String gate) {
        return f.count("xm.scene.battle.gate.rejects", "gate", gate);
    }

    /** 规格 §9 的八个闸（{@code xm_scene_battle_gate_rejects_total{gate}}）。 */
    private static final List<String> GATES = List.of("enter_scene", "skill", "attribute", "pet", "bag_sort", "asset", "move", "default");

    private Map<String, Double> gateRejectsByGate() {
        Map<String, Double> out = new TreeMap<>();
        GATES.forEach(gate -> out.put(gate, gateRejects(gate)));
        return out;
    }

    /** 自那份快照以来各闸的增量（只留非零的）。 */
    private Map<String, Double> gateRejectsSince(Map<String, Double> before) {
        Map<String, Double> out = new TreeMap<>();
        GATES.forEach(gate -> {
            double delta = gateRejects(gate) - before.get(gate);
            if (delta != 0) {
                out.put(gate, delta);
            }
        });
        return out;
    }

    /** 一条 GATED 的方法被拒时计在哪个闸上（按系统分：63 / 84 / 属性写 / 宝宝写 / 192）。 */
    private static String gateOf(Row row) {
        return switch (row.service()) {
            case SCENE -> "enter_scene";
            case SKILL -> "skill";
            case ATTRIBUTE -> "attribute";
            case PET -> "pet";
            case BAG -> "bag_sort";
            default -> throw new AssertionError(row.key() + " 是 GATED，但没有登记它计在哪个闸上（规格 §9 的 gate 取值）");
        };
    }

    private double moves(String result) {
        return f.count("xm.scene.moves", "result", result);
    }

    private static AssetOpRequest debit(long seq, long amount) {
        return AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT).setSeq(seq)
                .setStreamEpoch(1_700_000_000_000L).setTxType(24).setCorrelationId(500 + seq)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(amount)))
                .build();
    }

    private static AssetOpRequest credit(long seq, long amount) {
        return AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(AssetStream.ASSET_STREAM_GUILD_CREDIT).setSeq(seq)
                .setStreamEpoch(1_700_000_000_000L).setTxType(25).setCorrelationId(700 + seq)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(amount)))
                .build();
    }

    private AssetOpRequest signed(AssetRpc rpc, AssetOpRequest request) {
        return AssetOpSignatures.sign(rpc, request, AssetOpSignatures.CALLER_GUILD, SECRET, f.clock.epochMillis());
    }
}
