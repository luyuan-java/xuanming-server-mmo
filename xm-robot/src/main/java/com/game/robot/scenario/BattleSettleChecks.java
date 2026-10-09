package com.game.robot.scenario;

import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamView;
import com.game.robot.client.AdminClient;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * battle-settle 场景的纯函数件（scene-battle-spec §13.8）：按时间递增的 battle_id、快照核对、结算落地后的期望值（气血 / 宝宝气血 / 背包增量 / 金币）、
 * 大厅 150 与直连 150 的比对、第 13 步逐闸的指标增量、汇总行与失败步骤登记、第 11 步的跨节点目标、第 12 步的队伍视图读取。不碰网络，单测覆盖。
 */
final class BattleSettleChecks {

    /** 战斗配表指纹：sha256 前 16 字节的小写 hex（engine-spec §10.1）。 */
    static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{32}");
    /** 单条数量上限（scene 把结算里的数量夹到 uint32，§7.11 h / i 步）。 */
    static final long UINT32_MAX = 0xFFFFFFFFL;
    /** 不可施放的技能类型位（被动 / 持续施法 / 开关，同 {@code BattleRules.isTurnBattleCastableSkill}）。 */
    private static final Set<Integer> NOT_CASTABLE_TYPE_BITS = Set.of(0, 2, 3);

    /** scene 结算应用计数（scene-battle-spec §9；第 13 步看 {@code result="applied"}）。 */
    static final String SETTLEMENTS = "xm_scene_battle_settlements_total";
    /** scene 销账计数（scene-battle-spec §9；第 13 步看 {@code result="released"}：销账并放锁）。 */
    static final String ACKS = "xm_scene_battle_acks_total";
    /** 在途闸拒绝计数（scene-battle-spec §9；标签键 {@code gate}）。 */
    static final String GATE_REJECTS = "xm_scene_battle_gate_rejects_total";
    /**
     * 本场景第 4 步会打到的在途闸（§13.8 第 13 步逐个断言有增长）。取值 = xm-scene {@code SceneMetrics.BattleGate} 的枚举名小写
     * （{@code SceneMetrics.counters} 用 {@code tagValue}）：63 → enter_scene、168 → attribute、185 / 187 → pet、192 → bag_sort、
     * 84（施法者 7004 与目标 7002）→ skill、134 → move。
     */
    static final List<String> EXERCISED_GATES = List.of("enter_scene", "attribute", "pet", "bag_sort", "skill", "move");
    /**
     * 本场景打不到的三个闸：asset（资产通道，要 xm-guild 发指令）、default（缺省 REJECT 的方法）与 zone_travel
     * （226 跨 zone 传送被战斗在途拒回 3025，批次 5.4 加的取值；本场景第 4 步不发 226，它由 travel 场景与 scene 的闸表单测覆盖）。
     * 只进观察记录，不断言。
     */
    static final List<String> UNEXERCISED_GATES = List.of("asset", "default", "zone_travel");

    private BattleSettleChecks() {
    }

    /** Prometheus 标签串 {@code gate="…"}（配合 {@link AdminClient#sum}）。 */
    static String gateLabel(String gate) {
        return "gate=\"" + gate + "\"";
    }

    /** 两次抓取之间某序列的增量（标签全部包含才计入；多个节点的抓取文本拼在一起即按节点求和）。 */
    static double delta(String before, String after, String metric, String... labels) {
        return AdminClient.sum(after, metric, labels) - AdminClient.sum(before, metric, labels);
    }

    /** {@code gates} 里每个闸的拒绝计数增量（按给定顺序）。 */
    static Map<String, Double> gateRejectDeltas(String before, String after, Collection<String> gates) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (String gate : gates) {
            deltas.put(gate, delta(before, after, GATE_REJECTS, gateLabel(gate)));
        }
        return deltas;
    }

    /**
     * 第 13 步 scene 侧的一条下限：{@code metric{label}} 在场景前后至少增长 {@code atLeast}（§13.8 第 13 步）。
     *
     * @param label Prometheus 标签串（如 {@code result="applied"}），配合 {@link AdminClient#sum}
     */
    record MetricFloor(String metric, String label, long atLeast) {

        double delta(String before, String after) {
            return BattleSettleChecks.delta(before, after, metric, label);
        }

        /** {@code metric{label}}。 */
        String series() {
            return metric + "{" + label + "}";
        }
    }

    /**
     * 第 13 步 scene 侧的全部下限，按断言的先后：结算应用 ≥ 2（第 6 步在线一局、第 9 步离线一局）、销账放锁 ≥ 2、
     * {@link #EXERCISED_GATES} 逐个 ≥ 1（只看合计的话，某个服务闸漏了计数也照样通过）。
     */
    static List<MetricFloor> sceneMetricFloors() {
        List<MetricFloor> floors = new ArrayList<>();
        floors.add(new MetricFloor(SETTLEMENTS, "result=\"applied\"", 2));
        floors.add(new MetricFloor(ACKS, "result=\"released\"", 2));
        for (String gate : EXERCISED_GATES) {
            floors.add(new MetricFloor(GATE_REJECTS, gateLabel(gate), 1));
        }
        return List.copyOf(floors);
    }

    /**
     * {@link #sceneMetricFloors} 里没有达到下限的序列，每条形如 {@code xm_…{result="applied"} 0.0 → 0.0（要 ≥ 2）}（数值是前后两次抓取的合计）；
     * 空 = 全部达到。
     */
    static List<String> sceneMetricShortfalls(String before, String after) {
        List<String> shortfalls = new ArrayList<>();
        for (MetricFloor floor : sceneMetricFloors()) {
            if (floor.delta(before, after) < floor.atLeast()) {
                shortfalls.add(floor.series() + " " + AdminClient.sum(before, floor.metric(), floor.label()) + " → "
                        + AdminClient.sum(after, floor.metric(), floor.label()) + "（要 ≥ " + floor.atLeast() + "）");
            }
        }
        return shortfalls;
    }

    /**
     * 第 13 步的 scene 指标是不是「只抓了一部分节点」才不达标：robot 自己见过第二个 scene 节点（第 11 步找到了另一个节点上的频道，
     * 并会把 A 换过去），而 {@code --scene-metrics-url} 不足两个地址。这时 A 先后在两个节点上跑步骤（A 原在抓的那个节点 → 第 6、9 步的结算
     * 应用与销账落在没抓的节点上；原在没抓的节点 → 第 4 步的六个闸计数落在没抓的节点上），只看一个节点的增量必然缺一截。
     * 逐条报 {@code 0.0 → 0.0} 看不出原因，调用方改记一条写明原因的失败——<b>仍是失败</b>：这一步没有判成，不能算过；
     * 抓到的那个节点上的增量碰巧全都达标时（「同图不同频道 = 不同节点」的推断在这个切片上不成立，例如单节点开了自动扩频道）照常逐条断言。
     *
     * @param urlCount   {@code --scene-metrics-url} 给了几个地址
     * @param secondNode 第 11 步见到的另一个节点上的频道（描述文字）；没见到第二个节点为 null
     * @param shortfalls {@link #sceneMetricShortfalls} 的结果
     * @return 写进失败细节的原因；null = 不是这种情形
     */
    static String partialScrapeProblem(int urlCount, String secondNode, List<String> shortfalls) {
        if (secondNode == null || urlCount >= 2 || shortfalls.isEmpty()) {
            return null;
        }
        return "这是双 scene 切片（第 11 步见到另一个节点上的频道 " + secondNode + "），而 --scene-metrics-url 只给了 " + urlCount
                + " 个地址：A 所在的节点没有全抓到（第 11 步成功后 A 换到了另一个节点），没抓的节点上的增量看不到，只抓一个节点判不了。差的是 "
                + String.join("；", shortfalls)
                + "。重跑时把两个节点的管理端口都给上（逗号分隔，如 --scene-metrics-url http://127.0.0.1:18104,http://127.0.0.1:18114）";
    }

    /**
     * {@link #sceneMetricFloors} 的序列是不是一条都没有增长。只抓了一个节点而全都没涨时，A 多半一直在另一个没抓的 scene 节点上
     * （双 scene 切片、A 与 B 登录时恰好同频道：robot 见不到第二个节点，{@link #partialScrapeProblem} 帮不上），调用方据此补一条提示。
     */
    static boolean sceneMetricsFlat(String before, String after) {
        for (MetricFloor floor : sceneMetricFloors()) {
            if (floor.delta(before, after) != 0) {
                return false;
            }
        }
        return true;
    }

    /** {@link #judgeSceneMetrics} 在「只抓了一部分节点」时记的那一条失败的名字。 */
    static final String PARTIAL_SCRAPE_CHECK = "第 13 步 scene 指标要抓全两个 scene 节点（--scene-metrics-url 给两个地址）";

    /**
     * 第 13 步 scene 侧的判定（前后两次抓取都拿到了才调用），结果记进 {@code report}：
     * <ul>
     *   <li>robot 见过第二个 scene 节点、却只抓了一个、增量又不达标（{@link #partialScrapeProblem}）→ 只记<b>一条</b>写明原因的失败
     *       {@link #PARTIAL_SCRAPE_CHECK}，不再逐条报看不出原因的 {@code 0.0 → 0.0}；</li>
     *   <li>否则 {@link #sceneMetricFloors} 逐条断言；只抓了一个节点而一条都没涨时另补一条提示（观察记录，不改结论）。</li>
     * </ul>
     *
     * @param urlCount   {@code --scene-metrics-url} 给了几个地址
     * @param secondNode 第 11 步见到的另一个节点上的频道（描述文字）；没见到第二个节点为 null
     */
    static void judgeSceneMetrics(CheckReport report, String before, String after, int urlCount, String secondNode) {
        String partial = partialScrapeProblem(urlCount, secondNode, sceneMetricShortfalls(before, after));
        if (partial != null) {
            report.fail(PARTIAL_SCRAPE_CHECK, partial, "scene-battle-spec §13.8 第 13 步、§9");
            return;
        }
        for (MetricFloor floor : sceneMetricFloors()) {
            double b0 = AdminClient.sum(before, floor.metric(), floor.label());
            double a0 = AdminClient.sum(after, floor.metric(), floor.label());
            report.check(a0 - b0 >= floor.atLeast(), "第 13 步 指标 " + floor.series() + " 增长 ≥ " + floor.atLeast(), b0 + " → " + a0,
                    "scene-battle-spec §9");
        }
        if (urlCount < 2 && sceneMetricsFlat(before, after)) {
            report.note("第 13 步 提示：抓的这个 scene 节点上三组指标一条都没有增长。如果这是双 scene 切片，A 多半一直在另一个没抓的节点上"
                    + "（A、B 登录时恰好同频道，robot 见不到第二个节点）：把两个节点的管理端口都传给 --scene-metrics-url（逗号分隔）再跑");
        }
    }

    /**
     * {@code --scene-metrics-url} 的取值拆成地址列表：逗号分隔（双 scene 切片把两个节点的管理端口都给上，第 13 步按各节点之和判定），
     * 去首尾空白与结尾斜杠、丢掉空项、去重保序。
     */
    static List<String> metricsUrls(String option) {
        Set<String> urls = new LinkedHashSet<>();
        for (String part : option.split(",")) {
            String url = part.strip();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.isEmpty()) {
                urls.add(url);
            }
        }
        return List.copyOf(urls);
    }

    /**
     * 失败步骤的登记（§13.8 第 14 步的 {@code BATTLE_SETTLE_FAIL step=…}）。每一步进出各报一次报告里的失败条数，期间新增的失败记到这一步名下；
     * 嵌在别的步骤里、但属于另一步的检查（第 12 步的队伍视图要在第 6–7 步的战斗中途采样）用 {@link #attribute} 记到它自己名下，外层步骤不背。
     * 只在场景线程上用。
     */
    static final class StepFailures {

        private final List<String> steps = new ArrayList<>();
        /** 已经用 {@link #attribute} 记到别的步骤名下的失败条数。 */
        private long attributed;

        /** 进入一步：返回基线（原样传给 {@link #leave}）。 */
        long enter(long failuresNow) {
            return failuresNow - attributed;
        }

        /** 离开一步：期间有新增失败（不含已另行归属的）就登记这一步。 */
        void leave(String step, long baseline, long failuresNow) {
            if (failuresNow - attributed > baseline) {
                add(step);
            }
        }

        /** 刚发生的 {@code count} 条失败属于 {@code step}（不是当前所在的步骤）。 */
        void attribute(String step, long count) {
            if (count > 0) {
                attributed += count;
                add(step);
            }
        }

        /** 直接登记一步（流程中断）。重复登记只留一次，按第一次失败的先后排。 */
        void add(String step) {
            if (!steps.contains(step)) {
                steps.add(step);
            }
        }

        List<String> steps() {
            return List.copyOf(steps);
        }
    }

    /**
     * 汇总行（§13.8 第 14 步）。全部检查通过且没有登记失败步骤才是 OK；失败时带出失败的步骤号（逗号分隔，按第一次失败的先后）。
     * 有失败却没有登记到任何步骤（不该发生：所有检查都包在步骤里）时写 {@code step=?}。
     *
     * @param gold 完整一局的 gold_gain（没打到为 −1）
     */
    static String summaryLine(boolean passed, List<String> failedSteps, long battleId, long gold, int mission, boolean reloginOk,
                              boolean offlineOk) {
        if (passed && failedSteps.isEmpty()) {
            return "BATTLE_SETTLE_OK battle_id=" + Long.toUnsignedString(battleId) + " gold=" + gold + " mission=" + mission
                    + " relogin=" + (reloginOk ? "ok" : "skip") + " offline=" + (offlineOk ? "ok" : "skip");
        }
        return "BATTLE_SETTLE_FAIL step=" + (failedSteps.isEmpty() ? "?" : String.join(",", failedSteps));
    }

    /**
     * 第 11 步的跨节点目标：{@code candidates} 里第一个与 {@code here} 同图、不同 scene_id 的场景（scene-manager 是 per-node 覆盖、每图每节点一个
     * 频道，所以它必在另一个节点上——判据同 {@link CrossNodeScenario#onDifferentChannels}）。没有返回 null（null 候选跳过）。
     */
    static SceneInfoComp remoteChannel(SceneInfoComp here, Collection<SceneInfoComp> candidates) {
        for (SceneInfoComp candidate : candidates) {
            if (candidate != null && CrossNodeScenario.onDifferentChannels(here, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 第 12 步：队伍视图里 {@code playerId} 这名成员的 {@code in_battle}；视图里没有他、或出现不止一次（服务端缺陷）时为 null。
     */
    static Boolean inBattle(TeamView view, long playerId) {
        TeamMemberView member = TeamScenario.onlyMember(view, playerId);
        return member == null ? null : member.getInBattle();
    }

    /**
     * robot 自己发的 battle_id：毫秒时间戳 × 1000 + 序号，严格递增、跨次运行不重复（D13 的局序依赖 battle_id 随时间递增，§10.4）。
     * 只在场景线程上用。
     */
    static final class BattleIdSequence {

        private long last;

        long next(long nowMillis) {
            last = Math.max(last + 1, nowMillis * 1000);
            return last;
        }
    }

    /** 技能能否在回合制战斗里施放（技能类型位不含被动 0 / 持续施法 2 / 开关 3）。 */
    static boolean castable(SkillTable row) {
        for (int bit : row.getSkillTypeList()) {
            if (NOT_CASTABLE_TYPE_BITS.contains(bit)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 只备战（PREPARE_ONLY）回来的快照核对（§13.8 第 3 步）；返回全部问题，空 = 通过。
     *
     * @param knownSkills  玩家的技能（77 ListSkills），快照技能必须是它的子集
     * @param castable     技能表里可施放的判据（表里没有的技能按不可施放）
     * @param battleUsable 物品表里 battle_usable 的判据
     * @param petId        出战宝宝（0 = 没有出战，快照不该带宝宝）
     */
    static List<String> snapshotProblems(PrepareBattleResponse response, long playerId, Collection<Integer> knownSkills,
                                         IntPredicate castable, IntPredicate battleUsable, long petId) {
        List<String> problems = new ArrayList<>();
        if (!response.hasSnapshot()) {
            problems.add("没有快照");
            return problems;
        }
        BattlePlayerSnapshot s = response.getSnapshot();
        if (s.getPlayerId() != playerId) {
            problems.add("player_id=" + Long.toUnsignedString(s.getPlayerId()));
        }
        if (s.getLevel() < 1) {
            problems.add("level=" + s.getLevel());
        }
        if (s.getBaseAttributes().getSpeed() <= 0) {
            problems.add("speed=" + s.getBaseAttributes().getSpeed());
        }
        if (s.getMaxHealth() <= 0) {
            problems.add("max_health=" + s.getMaxHealth());
        }
        if (s.getRouting().getSceneNodeId() == 0 || s.getRouting().getSceneInstanceId().isEmpty()) {
            problems.add("routing 的 scene 节点 / 实例为空");
        }
        if (s.getRouting().getGateInstanceId().isEmpty()) {
            problems.add("routing 的 gate 实例为空");
        }
        if (s.getTeamIndex() != 0) {
            problems.add("team_index=" + s.getTeamIndex());
        }
        if (!FINGERPRINT.matcher(response.getTableFingerprint()).matches()
                || !response.getTableFingerprint().equals(s.getTableFingerprint())) {
            problems.add("指纹 response=" + response.getTableFingerprint() + " snapshot=" + s.getTableFingerprint());
        }
        for (BattleItemEntry item : s.getItemsList()) {
            if (!battleUsable.test(item.getItemTableId()) || item.getCount() == 0) {
                problems.add("道具 " + item.getItemTableId() + "×" + item.getCount() + " 不该进快照");
            }
        }
        if (petId == 0) {
            if (s.getPetsCount() != 0) {
                problems.add("没有出战宝宝却带了 " + s.getPetsCount() + " 只");
            }
        } else if (s.getPetsCount() != 1) {
            problems.add("宝宝 " + s.getPetsCount() + " 只（应 1 只）");
        } else {
            BattlePetSnapshot pet = s.getPets(0);
            if (pet.getPetId() != petId || pet.getOwnerPlayerId() != playerId || pet.getMaxHealth() == 0) {
                problems.add("宝宝 pet_id=" + Long.toUnsignedString(pet.getPetId()) + " owner=" + Long.toUnsignedString(pet.getOwnerPlayerId())
                        + " max_health=" + pet.getMaxHealth());
            }
        }
        for (int skill : s.getSkillTableIdsList()) {
            if (!knownSkills.contains(skill) || !castable.test(skill)) {
                problems.add("技能 " + skill + " 不是玩家可施放的技能");
            }
        }
        return problems;
    }

    /** 结算回写后的气血：夹到上限；夹后为 0（阵亡）回满（§7.11 e 步：只在气血 0 时复活，is_dead 但气血 > 0 不复活）。 */
    static long expectedHealth(long settled, long maxHealth) {
        long clamped = Math.min(settled, maxHealth);
        return clamped == 0 ? maxHealth : clamped;
    }

    /** 宝宝的结算回写（§5.3）：夹到上限；is_dead 或夹后为 0 → 回满。 */
    static long expectedPetHealth(BattlePetSettlementData settled, long maxHealth) {
        long clamped = Math.min(settled.getHealth(), maxHealth);
        return settled.getIsDead() || clamped == 0 ? maxHealth : clamped;
    }

    /** 几个包的物品按配置号合计（主包 + 临时格：掉落进主包失败的余量进临时格）。 */
    static Map<Integer, Long> bagTotals(Collection<BagInfo> bags) {
        Map<Integer, Long> totals = new TreeMap<>();
        for (BagInfo bag : bags) {
            for (BagItemInfo item : bag.getItemsList()) {
                totals.merge(item.getConfigId(), Integer.toUnsignedLong(item.getCount()), Long::sum);
            }
        }
        return totals;
    }

    /**
     * 结算应用后背包的期望增量（按配置号）：消耗按应用前的持有夹紧（没有不报错），掉落全部进包（主包满了进临时格）；数量先夹到 uint32。
     * 0 号配置与 0 数量跳过。
     */
    static Map<Integer, Long> expectedBagDelta(BattleSettlementData settlement, Map<Integer, Long> before) {
        Map<Integer, Long> delta = new TreeMap<>();
        Map<Integer, Long> consumed = new TreeMap<>();
        for (BattleItemEntry item : settlement.getItemsConsumedList()) {
            if (item.getItemTableId() != 0 && item.getCount() != 0) {
                consumed.merge(item.getItemTableId(), Math.min(item.getCount(), UINT32_MAX), Long::sum);
            }
        }
        consumed.forEach((config, count) -> delta.merge(config, -Math.min(count, before.getOrDefault(config, 0L)), Long::sum));
        for (BattleItemEntry item : settlement.getItemsGainedList()) {
            if (item.getItemTableId() != 0 && item.getCount() != 0) {
                delta.merge(item.getItemTableId(), Math.min(item.getCount(), UINT32_MAX), Long::sum);
            }
        }
        delta.values().removeIf(v -> v == 0);
        return delta;
    }

    /** 实际增量（after − before，按配置号，0 去掉）。 */
    static Map<Integer, Long> actualBagDelta(Map<Integer, Long> before, Map<Integer, Long> after) {
        Map<Integer, Long> delta = new TreeMap<>();
        after.forEach((config, count) -> delta.merge(config, count, Long::sum));
        before.forEach((config, count) -> delta.merge(config, -count, Long::sum));
        delta.values().removeIf(v -> v == 0);
        return delta;
    }

    /** 这一局击杀了几只某配置号的怪。 */
    static long killed(BattleSettlementData settlement, int monsterConfigId) {
        long count = 0;
        for (BattleMonsterDefeat defeat : settlement.getDefeatedMonstersList()) {
            if (defeat.getMonsterConfigId() == monsterConfigId) {
                count += Integer.toUnsignedLong(defeat.getCount());
            }
        }
        return count;
    }

    /**
     * 大厅上的 150（scene 结算落地后推）与直连上的 150（battle 终局帧）是否一致：battle_id、outcome、settlement 逐字段相同（§6.1：settlement = 引擎原值）。
     * 不一致返回原因。
     */
    static String lobbyEndMismatch(BattleEndS2C lobby, BattleEndS2C direct) {
        if (lobby.getBattleId() != direct.getBattleId()) {
            return "battle_id 大厅 " + Long.toUnsignedString(lobby.getBattleId()) + " / 直连 " + Long.toUnsignedString(direct.getBattleId());
        }
        if (lobby.getOutcome() != direct.getOutcome()) {
            return "outcome 大厅 " + lobby.getOutcome() + " / 直连 " + direct.getOutcome();
        }
        if (!lobby.getSettlement().equals(direct.getSettlement())) {
            return "settlement 不同：大厅 " + oneLine(lobby.getSettlement()) + " / 直连 " + oneLine(direct.getSettlement());
        }
        return null;
    }

    static String oneLine(Object message) {
        return String.valueOf(message).replace('\n', ' ').strip();
    }
}
