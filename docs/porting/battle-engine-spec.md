# 回合制战斗引擎（批次 6.1）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。
> Java 侧以 `58afbba` 为准。工作区里有其他批次未提交的改动，本稿引用的 Java 文件都不在其中；生成类引用的是当前编译产物。
>
> **路径缩写**（mmorpg 侧，相对 `D:\work\mmorpg`）
> - `engine.cpp` / `engine.h`：`cpp/libs/services/battle/system/turn_battle_engine.{cpp,h}`
> - `constants.h`：`cpp/libs/services/battle/constants/turn_battle_constants.h`
> - `rules.h`：`cpp/libs/services/battle/system/combat_damage_rules.h`
> - `provider.h`：`cpp/libs/services/battle/data/battle_data_provider.h`；`table_provider.cpp`：同目录 `table_battle_data_provider.cpp`
> - `fp.h` / `fp.cpp`：同目录 `battle_table_fingerprint.{h,cpp}`
> - `test.cpp`：`cpp/tests/turn_battle_engine_test/turn_battle_engine_test.cpp`。同目录还有：
>   `mem.h` = `memory_battle_data_provider.h`、`fp_test.cpp` = `battle_table_fingerprint_test.cpp`、`tdp_test.cpp` = `table_battle_data_provider_test.cpp`
> - `room.cpp`：`cpp/nodes/battle/logic/battle_room_manager.cpp`；`pb.cpp`：`cpp/libs/services/scene/battle/system/player_battle.cpp`
> - `expr.h`：`cpp/libs/engine/config/table_expression.h`；`sha256.cpp`：`cpp/libs/engine/core/utils/encode/sha256.cpp`；
>   `cpp_config.h.j2`：`tools/data_table_exporter/templates/cpp_config.h.j2`
> - `battle_data.proto` / `battle_node.proto` / `player_battle.proto`：mmorpg `proto/battle/` 下，**行号用 mmorpg 的**。
>   Java 同步副本在 `xm-proto/src/main/proto/proto/battle/`，只多第 2–4 行 java option，所以 **Java 行号 = mmorpg 行号 + 3**（去掉这几行后逐行 diff 为空）。
>
> **路径缩写**（Java 侧，相对 `D:\work\xuanming-server-mmo`）
> - 以 `xm-`、`docs/`、`config-data/`、`contract/` 开头的路径，以及 `PARITY.md`、`AGENTS.md`。
> - 表 schema 是同步产物 `xm-table/src/main/proto/<sheet>_table.proto`。真表数据 `config-data/tables/*.pb` 里七张战斗表与 mmorpg
>   `generated/tables/*.pb` 的 sha256 逐一相同（本稿核对过）。
> - 「生成的 `SkillRows.java`」等指 `xm-table/target/generated-sources/annotations/com/game/table/` 下的编译期产物（`docs/design/config-tables.md`）。
>
> **本稿的来历**：由三份分区稿合并：
> - 核心：状态、初始化、行动、回合、普攻 / 防御 / 逃跑、死亡、胜负、快照、RNG；
> - 技能、buff、道具、掉落与结算、数据供给、配表指纹；
> - Java 落地映射。
>
> 分区稿之间不一致、或与代码不符的地方，都回到代码重新核对过。对分区稿的勘误在 §11.5，对 inventory 的勘误在 §11.6。
> 本稿只读代码，没有改任何源文件。
>
> **黄金值分级**（§13 每条都标）
> - 【C++ 断言】`test.cpp` / `fp_test.cpp` / `tdp_test.cpp` 里写死的期望值，Java 必须逐条复现。
> - 【标准】C++ 标准 [rand.predef] 规定的 mt19937_64 校验值，与任何实现无关。
> - 【复核】两种独立手段得到同一值（手算 + 草稿复算器，或 `sha256sum` 直接对字节求哈希），但没有在 C++ 里实跑。
> - 【派生】只由草稿复算器得出。复算器是按本稿语义逐行移植的 Java 小程序，其 mt19937_64 已用【标准】值校验。
>
> 冻结成 Java 回放测试之前，【复核】和【派生】两级建议在 mmorpg 跑一次 C++ 确认（§12.3 Q1）。

---

## 0 概览与范围

### 0.1 本批覆盖什么

| inventory 条目（`docs/porting/inventory/combat.md`） | 基线 | 本稿 |
|---|---|---|
| turn-battle-engine-core（:161-171） | `engine.{h,cpp}` 的开局、行动、回合、普攻 / 防御 / 逃跑、死亡、胜负、快照、RNG；`constants.h`；`provider.h`；`table_provider.cpp` | §1–§3、§7、§8、§9.1–§9.4 |
| turn-battle-skills-buffs（:173-183） | `engine.cpp` 的校验链、技能、冷却、buff、快照 buff 清洗 | §4、§5 |
| turn-battle-items-drops-rewards（:185-195） | `engine.cpp` 的道具、击杀簿、掉落、结算 | §6 |
| battle-table-fingerprint（:197-207） | `fp.{h,cpp}` | §9.5–§9.8 |

路线图 6.1 的定义是「回合制战斗引擎（纯库：回合、技能、buff、道具、掉落）」（`docs/porting/roadmap.md:80`）。

**不在本批**
- `cpp/libs/services/battle/system/battle_result_activity.h`、`cpp/libs/services/battle/settlement/settlement_outbox.h`：
  虽在战斗库目录下，盘点归 6.2 / 6.3（`docs/porting/inventory/combat.md:269-279`）。
- `test.cpp:1787-1846` 的 4 个 `SettlementApplicationCacheTest`：归 6.3。
- `tdp_test.cpp:132-159` 的两个 Class 表用例：属于属性与复活（2.x，已移植）。
- 同目录的其他单测：`combat_damage_rules_test.cpp` 属 2.6（已移植，`PARITY.md:77`）；`player_revive_rule_test.cpp` 属 2.7；
  `attribute_allocation_rules_test.cpp` 属 2.x；`battle_result_activity_test.cpp` 属 6.3。

### 0.2 引擎在链路中的位置

```
scene（6.3）出 BattlePlayerSnapshot（带 table_fingerprint）
  → match（6.4）凑局、改写 team_index、生成 seed、比对指纹
  → battle 节点（6.2）CreateBattle：指纹闸 → 建引擎（本批）
  → 每回合：ValidateAction / SubmitAction / SetActorAuto → ResolveCurrentRound
            → 节点回填 deadline 与 action_order、按视角裁剪后推 139
  → 结束：节点对每个参战玩家 BuildSettlement → outbox → scene 唯一应用（6.3）
```

- **引擎是纯库**：没有网络、时钟、计时器、ECS。唯一的日志是建房违规时打的 4 处 ERROR（`engine.cpp:19-20`）。
- **表数据**经数据源接口注入（`provider.h:12-16`）。
- **随机数**只走成员 RNG，相同输入必须产出逐字节相同的事件流（`engine.h:9-11`）。

### 0.3 与相邻批次的边界

**6.2 battle 节点**

基线事实：
- **建房**：一房一引擎。`Initialize` 失败回 1002 并打 ERROR（`room.cpp:536-548`）。
- **指纹闸**：在建引擎之前。enforce 模式下不一致回 1006 kFeatureUnavailable，`parameters[0]` 写说明（`room.cpp:525-534`、`:408-451`）。
- **提交**：先 `ValidateAction`；不是 1000 就回 tip，其中 0 兜底改成 1005；然后 `SubmitAction`，返回 true 就立即结算（`room.cpp:709-734`）。
- **挂机**：先采样 `AllPlayersReady`。引擎返回 **0 = 成功**。只有在「未就绪 → 就绪」翻转时立即结算（`room.cpp:999-1020`）。
- **回合窗口**：就绪时 2000 ms，否则 6000 ms（`room.cpp:1029-1030`）。
- **结算后**：节点回填 `action_deadline_ms`、`battle_id`，把 `LastActionOrder()` 抄进 `action_order`（`room.cpp:1050-1071`）。
- **整场期限**：缺省 `now + (30+2)×6000`（`room.cpp:559-565`）；到期仍是 ONGOING 时，节点盖 DRAW（`room.cpp:1092-1096`、`:1146-1148`）。
- **视角裁剪**：清掉非本人、非本人宝宝的冷却；`self_items` 只给本人（`room.cpp:1250-1281`）。

Java 的做法：
- 引擎只出「全知」快照，不碰时钟、视角和指纹闸。
- `action_order` 仍由节点填。引擎返回的那份恒为空（`test.cpp:1571-1572`）。
- 开局失败的原因由 `BattleStart.Rejected` 带出，节点照样回 1002 并打日志（D1）。
- 挂机开关成功时 Java 返回 1000（D2）。

**6.3 scene 冻结与结算应用**

基线事实（快照怎么出）：
- 等级至少为 1（`pb.cpp:919`）；速度为 0 时回落到 120（`pb.cpp:102`、`:921-928`）。
- max 气血 / 法力缺失时取 `max(当前, 1)`（`pb.cpp:930-941`）。
- 技能用同一份黑名单过滤（`pb.cpp:959-991`）。
- buff：剔除控制类和瞬时类；层数至少为 1；施法者只在「是自己」时映射；剩余回合取表的全量时长（`pb.cpp:994-1048`）。
- 道具：只取主背包里 battle_usable 的，按 config id 升序合并（`pb.cpp:1057-1077`）。
- `team_index` 先填 0，交给 match 改写（`pb.cpp:1097`）。
- 指纹写进快照和应答（`pb.cpp:1101`、`:1185`）。0 血玩家拒绝开战（`pb.cpp:1147-1153`）。
- 结算只由 scene 应用（`engine.h:70-71`；`pb.cpp:683-800`、`:1750-1760`）。

Java 的做法：
- 引擎对快照**只做兜底、不信任**：技能再过滤一次（`engine.cpp:159-168`），buff 再清洗一次（`engine.cpp:1245-1285`）。
- 6.3 的 scene 侧过滤直接调用本批的 `BattleRules.isTurnBattleCastableSkill`（D7）。
- Java 玩家身上永远没有实时 buff（`PARITY.md:80`），所以 6.3 出的快照 buff 恒为空。清洗逻辑照样实现：单测要用，局内也要用。

**6.4 match**

- 基线：指纹模式取 off / warn / enforce，缺省 warn（`go/match/etc/match_service.yaml:77-80`；`go/match/internal/config/config.go:82-90`）；seed 由 match 生成（`battle_node.proto:23`）。
- Java：本批只提供 `BattleTableFingerprint`。

**2.6 伤害公式（已移植）**

- 基线：`rules.h:36-81` 是实时与回合共用的纯公式。暴击 ×2、PvP ×0.3、防御 ×0.5 都不在公式里（`rules.h:6-7`）。
- Java：直接复用 `com.game.common.combat.CombatDamageRules`（`xm-common/src/main/java/com/game/common/combat/CombatDamageRules.java:38-94`；`PARITY.md:77`）。
  它的无符号换算 `unsignedToDouble` / `unsignedFromDouble` 是包私有的（`:97`、`:105`），要抽成公共件（§10.7）。

**2.7 当前气血 / 复活（已移植部分）**

- 基线：结算复活与 0 血拒绝开战都在 scene（`pb.cpp:1147-1153`、`:1707`；`PARITY.md:79`）。
- Java：不在引擎内，由 6.3 调用 `PlayerRevive`。

**1.5 表达式列（已移植）**

- 基线：C++ 用 exprtk，每次求值都重新编译；`random()` 走全局 `rand()`，值域 [0,1]（`expr.h:6-11`、`:59-63`）。
  调用方式是两步：先设参数，再取值（`table_provider.cpp:61-72`；`cpp_config.h.j2:118-133`）。
- Java：`TableExpression` 在加载期编译、求值无状态（`xm-table/src/main/java/com/game/table/load/TableExpression.java:39-64`；`PARITY.md:53`）。
  生成的 `evalDamage(row, level)` 缺省用 `ThreadLocalRandom`（生成的 `SkillRows.java:80-88`；`docs/design/config-tables.md:72`）。
- **引擎必须调用带 `RandomGenerator` 参数的重载，并禁用 `random()`**（§9.3、D4）。

### 0.4 常量（`constants.h`）

| 常量 | 值 | 行 | 用途 / 备注 |
|---|---|---|---|
| kRoundDurationMs | 6000 | :15 | 时间换回合 |
| kDefaultMaxRounds | 30 | :18 | 回合上限的缺省值 |
| kMatchModePveSolo / PveTeam | 4 / 5 | :22-23 | 判定 PVE（`engine.cpp:1312-1315`）。其余值一律按非 PVE 处理，包括 0、1 = 5V5、3 = 1V1、6 = 切磋（`xm-proto/src/main/proto/proto/match/match_service.proto:56-62`） |
| kMaxBattleTeamSize | 5 | :30 | 每队玩家上限 |
| kAutoRoundIntervalMs | 2000 | :35 | 只有节点用 |
| 技能类型位号 Passive / General / Channel / Toggle / Activate / BasicAttack | 0 / 1 / 2 / 3 / 4 / 5 | :40-45 | `skill_type` 存的是**位号**，不是掩码 |
| 目标模式 NoTarget / Targeted / AOE | `1<<0` / `1<<1` / `1<<2` | :49-51 | `targeting_mode` 存位号，比较的是 `1u << 位号` |
| buff 类型 Stun / Silence / Invincibility / Immunity / Dispel | 30 / 31 / 32 / 34 / 35 | :55-59 | 32、34 引擎从不引用 |
| buff 类型 HealthRegen / ManaRegen / RegenByLostHealth | 40 / 41 / 42 | :60-62 | 41 引擎从不引用 |
| buff 类型 Poison / Burn / Freeze | 50 / 51 / 52 | :63-65 | |
| kCombatStateSilence | 1 | :70 | SkillPermission 的行 id |
| kBasicAttackBaseDamage | 10.0 | :76 | 普攻的基础伤害 |
| kPvpDamageScale | 0.3 | :84 | 非 PVE 时乘在直接伤害上；周期伤害不乘 |
| kFleeBaseChance / kFleeSpeedFactor / Min / Max | 0.5 / `0.01 / 12.0` / 0.05 / 0.95 | :89-92 | `0.01/12.0` 编译期求值为 `8.333333333333334E-4`，Java 写同一表达式即逐位相同 |
| kDropRateDenominator | 10000 | :99 | |
| kMaxItemUsesPerBattlePvp | 5 | :104 | |
| kMaxSubBuffDepth | 8 | :107 | 判定条件是 `depth > 8`，所以深度 0..8 共 9 层都生效 |
| kBaseHitRate | 100 | :116 | 命中率 ≥ 100 时短路，不耗 RNG |
| kSkillCostResourceMana | 1 | :121 | |
| kFormationFrontRowSize | 5 | :125 | 只出现在注释里（`engine.cpp:301`） |
| kEngineLocalActorIdFlag | `1<<63` | :140 | |
| kMonsterActorIdBase / kPetActorIdBase | `0x8000000100000000` / `0x8000000200000000` | :141-142 | |
| kMonsterDefault Health / Strength / Armor / Resistance / CritChance / Speed | 300 / 5 / 24 / 0 / 0 / 60 | :143-148 | |

**时间换回合**（`constants.h:151-162`）
- `RoundsFromMilliseconds(ms)`：
  - 先算 `r = (ms + 5999) / 6000`，这是 uint64 运算，加法会回绕；
  - 若 `r < 1`（无符号，即 `r == 0`），返回 1；
  - 否则返回 `(uint32) r`，截断，**可能截成 0**。
- `RoundsFromSeconds(s)`：
  - `s <= 0` 时返回 1；
  - 否则返回 `RoundsFromMilliseconds((uint64)(s × 1000.0))`，截断取整。
  - 例：12.0→2，6.0→1，5.0→1，2.0→1，1.0→1，7.0→2，12.5→3，1800→300，0.0005→1。

### 0.5 tip 码（引擎会回出的全部值）

| 码 | 名 | 出处 | Java 取法 |
|---|---|---|---|
| 1000 | kSuccess | `xm-table/src/main/proto/tip/common_error_tip.proto:12` | `CommonErrorTip.common_error.kSuccess_VALUE`（包 `com.game.table`，参照 `xm-scene/src/main/java/com/game/scene/skill/SkillRules.java:22-25`） |
| 1001 | kInvalidTableId | 同上 :14 | 同上 |
| 1002 | kInvalidTableData | 同上 :16 | 同上 |
| 1005 | kInvalidParameter | 同上 :22 | 同上 |
| 1009 | kThisEntityIsInvalid | 同上 :30 | 同上 |
| 6007 | kBagInsufficientItems | `xm-table/src/main/proto/tip/bag_error_tip.proto:26` | `BagErrorTip.bag_error.kBagInsufficientItems_VALUE` |
| 7001 | kSkillInvalidTargetId | `xm-table/src/main/proto/tip/skill_error_tip.proto:14` | `SkillErrorTip.skill_error.*_VALUE` |
| 7002 | kSkillInvalidTarget | 同上 :16 | 同上 |
| 7003 | kSkillCooldownNotReady | 同上 :18 | 同上 |
| 7004 | kSkillCannotBeCastInCurrentState（法力不足也借用这个码） | 同上 :20 | 同上 |
| 7005 | kSkillCannotBeCastSilenceRestriction（只会作为 SkillPermission 的格值原样回出） | 同上 :22 | 同上 |
| 7006 | kSkillCannotBeCastStunRestriction | 同上 :24 | 同上 |
| 0 | `SetActorAuto` 成功（基线专用，与 tip 码不同域） | `engine.cpp:363`；`engine.h:48-50` | Java 改回 1000（D2） |

另外，SkillPermission 的格值会被原样回出（`engine.cpp:586-588`）。加载期 tip 引用校验保证它是 0 或现存的码（`docs/design/config-tables.md` §3 第 6 条）。

### 0.6 数值口径（全文适用）

- **类型映射**：proto 的 uint64 / uint32 字段，Java 分别用 `long` / `int` 承载，一律按无符号解释。具体做法：
  - 比较用 `Long.compareUnsigned` / `Integer.compareUnsigned`；
  - 取模、除法用 `remainderUnsigned` / `divideUnsigned`；
  - 换成 double 用 `Unsigned.toDouble`（§10.7）。
  - 加减法直接用 `+` / `-`，因为补码位模式与 C++ 的回绕结果相同。
  - 逐条对照见 §10.5。
- **浮点**：只用普通 `double` 运算，**禁止** `Math.fma`，运算顺序照源码写，不许「化简」。基线两种构建都不会产生 FMA：
  - Linux：`cpp/libs/services/battle/CMakeLists.txt:8` 只有 `-O0 -g -ggdb`，没有 `-march`，x86-64 基线指令集里没有 FMA 指令；
  - Windows：`cpp/libs/engine/core/core.vcxproj:220` 是 `FloatingPointModel=Precise`；`battle.vcxproj` 与 `turn_battle_engine_test.vcxproj` 不设 `FloatingPointModel`，用 MSVC 缺省的 `/fp:precise`；
    也没设 `EnableEnhancedInstructionSet`，即没有 AVX2。
  - Java 17 起全部 strictfp，所以两边逐位一致。
- **clamp**：`std::clamp(v, lo, hi)` 的语义是 `v < lo ? lo : (hi < v ? hi : v)`；`std::max(x, 0.0)` 的语义是 `x < 0.0 ? 0.0 : x`。
  Java 照写，不用 `Math.clamp` / `Math.max`，以免 NaN、`-0.0` 时的行为不同。

---

## 1 状态模型与初始化

### 1.1 引擎字段（`engine.h:216-236`）

| C++ 字段 | 语义 | Java |
|---|---|---|
| `dataProvider`（:216） | 只读表数据源。C++ 的默认构造读全局表管理器（`engine.cpp:33`） | 构造时注入，不能为 null，不提供「读全局」的构造（§9.4） |
| `createRequest`（:219） | 开局请求的副本。**道具余量就地扣在这份副本上**（`engine.cpp:1680-1695`） | 原请求保留为不可变；道具余量另建账本（§6.3） |
| `defeatedMonsters`（:220） | 击杀簿，按实际击杀顺序记，每条 `count = 1` | `ArrayList<BattleMonsterDefeat>` |
| `actors`（:221） | 全部单位，按插入序稳定排列：玩家 → 宝宝 → 怪物。死亡、逃跑的单位也不删除 | `ArrayList<BattleUnit>`。**顺序即语义**：目标候选、buff tick、快照、`FindActor` 都按它走 |
| `pendingActions`（:222） | actor_id → 本回合行动（`std::map`） | `TreeMap<Long, BattleAction>(Long::compareUnsigned)`。现在只做查找，用有序容器防止日后有人遍历它 |
| `settlements`（:223） | player_id → 结算累积，InitPlayers 时建（`engine.cpp:171-173`） | `TreeMap<Long, BattleSettlementData.Builder>(Long::compareUnsigned)`。**掉落按 player_id 升序遍历**（`engine.cpp:1203`） |
| `itemUseCounts`（:224） | PVP 下每人已用道具次数 | `HashMap<Long, Integer>`，只做查找 |
| `rng`（:226） | `std::mt19937_64` | 自写 `MersenneTwister64`（§8） |
| `roundIndex = 1`（:227） | 正在收集的回合号 | `int`（uint32） |
| `maxRounds = 30`（:228） | 回合上限 | `int` |
| `outcome`（:229） | 胜负 | `eBattleOutcome` |
| `nextBuffInstanceId = 1`（:230） | 局内 buff 实例号，自增 | `long`，无符号比较，允许回绕 |
| `initialized`（:231） | 一次性闸 | 不需要：工厂只产出初始化成功的实例（D1） |
| `lastActionOrder`（:234） | 最近一回合的出手序 | `long[]`；对外返回不可变副本 |
| `currentGroupId` / `currentHitIndex`（:235-236） | 事件分组游标（§3.4） | `int` |

**单位状态的承载**

C++ 把每个单位的状态直接放在 `BattleActorState` proto 里（`engine.h:218` 的注释）。Java 用 `BattleUnit` 包一个 `BattleActorState.Builder` 作为权威状态（§10.4），不另写平行结构。理由：快照里有些字段规则从不读，但必须原样带出：
- `name`、`appearance_id`、`class_id`、`gender`、`pet_table_id`、`pet_id`、`owner_player_id`、`monster_table_id`、`formation_slot`、`is_auto`；
- `attributes.stamina`：从快照透传进来，引擎从不读；
- `skill_cooldown_rounds` 里**值为 0 的条目**：衰减只减不删（`engine.cpp:1169-1178`），快照里会一直留着 `{skill: 0}`。

### 1.2 actor_id 命名空间（`constants.h:130-142`）

- **玩家**：直接用 `player_id`，必须小于 2^63。InitPlayers 遇到 bit63 置位的 id 直接拒绝（`engine.cpp:124-128`）。
- **怪物**：`kMonsterActorIdBase + 局内序号`，即 `0x8000000100000000 + i`。
- **宝宝**：`kPetActorIdBase + 局内序号`，即 `0x8000000200000000 + i`。宝宝的真实 pet_id 另存在 `pet_id` 字段里。
- **对 Java 的意义**：怪物与宝宝的 actor_id 在 `long` 里是**负数**。凡是比较、排序、取模都必须用无符号运算（§3.3、§13.5 的 G-TIE）。

### 1.3 `BattleActorState` 各字段的来源（`battle_data.proto:90-117`）

| 字段 | 玩家（`engine.cpp:133-168`） | 宝宝（`:198-233`） | 怪物（`:263-294`） |
|---|---|---|---|
| actor_id | `player_id` | `kPetActorIdBase + petIndex` | `kMonsterActorIdBase + monsterIndex` |
| actor_type | PLAYER(1) | PET(3) | MONSTER(2) |
| team_index | 快照值（≤ 1） | 主人快照的 team | 固定 1 |
| name | `player_name` | `pet_name` | 固定 `"野怪"` |
| level | 快照值，原样保留，可能为 0 | 宝宝快照值 | 参考等级（§1.7） |
| attributes | `base_attributes` 整份拷贝 | `base_attributes` 整份拷贝 | 表行属性或默认值；stamina 与 mana 都是 0 |
| max_health | `FallbackMax(max_health, health)`：声明值 > 0 用声明值，否则用当前值（`:27-29`） | 同玩家 | 表行 health，或 300 |
| max_mana | 同上，按 mana 算 | 同上 | 不设置，即 0 |
| physical_attack / magic_attack / defense | 快照值 | 快照值 | 0 |
| formation_slot | `NextFormationSlot(team)` | 同左 | 同左 |
| buffs | 快照原样拷贝，之后清洗（§5.8） | 无（宝宝快照没有这个字段，`battle_data.proto:64-80`） | 无 |
| skill_table_ids | 过滤：表里有该行，且可施放（§4.1） | **不过滤**，原样拷贝 | 无 |
| is_auto | false | **true**（`:230`） | false |
| owner_player_id / pet_table_id / pet_id | — | 所在快照的 player_id / 快照值 / 真实 pet_id | — |
| monster_table_id | — | — | 表 id；兜底怪为 0 |
| appearance_id / class_id / gender | 快照值 | 不设置 | 不设置 |

### 1.4 `Initialize`（`engine.cpp:42-115`），步骤顺序固定

1. 已初始化、或数据源为 null，返回 false（`:44-46`）。引擎一次性使用。
2. `battle_id == 0` 或没有玩家，返回 false（`:47-49`）。
3. **队伍人数**：只统计 `team_index ≤ 1` 的快照，任一队超过 5 人就打 ERROR 并返回 false（`:53-67`）。宝宝不计入。
4. 拷贝请求；`rng.seed(request.seed())`，种子是完整的 uint64；`roundIndex = 1`；`outcome = ONGOING`（`:69-72`）。
5. **回合上限**：`FindDungeon(battle_config_id)` 查到行、且 `time_limit > 0` 时，`maxRounds = RoundsFromMilliseconds((uint64) time_limit × 1000)`；否则为 30（`:75-79`）。
   - Java 写成 `Integer.toUnsignedLong(timeLimit) * 1000L`，不会溢出。
   - 真表：Dungeon 1 / 3 是 1800 s，即 300 回合；Dungeon 2 是 3600 s，即 600 回合。
6. `InitPlayers`，失败返回 false（`:81-83`，§1.5）。
7. `InitPets`，失败返回 false（`:86-88`，§1.6）。**必须在全部玩家之后**：阵位按插入序排，主人先占位。
8. 只有 PVE（match_mode 为 4 或 5）才 `InitMonsters`（`:90-94`，§1.7）。
9. 两边都至少要有一个单位，否则返回 false（`:97-104`）。判定时 team 0 记 A 方，**其余一律记 B 方**。
10. 只对 PLAYER 单位做快照 buff 清洗（`:107-111`，§5.8）。**必须等全部单位就位之后**，因为 caster 改写要能查到怪物和宝宝。
11. `initialized = true`（`:113`）。

**失败语义**
- 第 4 步之后失败时，`createRequest`、部分 `actors`、`settlements`、`nextBuffInstanceId` 都已被改写，但 `initialized` 仍是 false。
- C++ 不阻止再调一次 `Initialize`，再调会撞上上次残留的单位。节点在失败时直接丢弃引擎（`room.cpp:536-548`）。
- Java 用工厂 `TurnBattleEngine.start(...)`，失败时不产生实例（D1）。拒绝原因 `InitRejection` 见 §10.3。
- 各项检查的先后只影响报出哪个原因，不影响「收或拒」的结果集合。

### 1.5 `InitPlayers`（`engine.cpp:117-176`）

按快照顺序逐个处理：
- `player_id == 0` 或 `team_index > 1`，返回 false（`:119-121`）。
- bit63 置位，打 ERROR 并返回 false（`:124-128`）。
- `FindActor(player_id)` 已存在，即重复参战，返回 false（`:129-131`）。
- 按 §1.3 填字段；`formation_slot = NextFormationSlot(team)`（`:150`）。
- 快照 buff **原样追加**（`:152-158`）。每追加一条执行一次 `if (buff_id >= next) next = buff_id + 1`：
  - 这一步发生在清洗**之前**，所以被清洗丢掉的 buff 也会推高计数器（§5.1）；
  - 比较是 uint64 无符号比较；
  - `buff_id == UINT64_MAX` 时 `next` 回绕成 0。
- 技能：按快照顺序检查，表里有该行、且 `IsTurnBattleCastableSkill` 为真才加入（`:162-168`，§4.1）。**不去重**。
- 建结算条目：`settlements[player_id]` 填入 `battle_id`、`player_id`（`:171-173`）。
- 不做任何夹紧：`health > max_health` 也照收（隐患见 §11.1）。

### 1.6 `InitPets`（`engine.cpp:178-238`）

- `petIndex` 跨所有快照全局递增，从 0 开始（`:185`、`:202-203`）。遍历顺序：先按快照，再按快照内 `pets` 的顺序。
- 以下情况返回 false：
  - `pet_id == 0`（`:188-190`）；
  - 同一只宝宝已在本局出现过，比较对象是已加入宝宝的 `pet_id`（`:179-183`、`:192-196`，打 ERROR）；
  - `owner_player_id != 0` 且不等于所在快照的 `player_id`（`:211-216`，打 ERROR）。
- 归属只认所在快照：`owner_player_id = snapshot.player_id`（`:217`）。
- 宝宝**不建**结算条目。它的终值随主人结算带回（§6.6）。

### 1.7 `InitMonsters` / `AppendMonsterActor`（`engine.cpp:240-297`）

- **参考等级** = `max(1, 所有玩家快照的 level)`（`:242-245`）。宝宝不参与。
- **怪物列表** `monsterIds = GetDungeonMonsterIds(battle_config_id)`（`:247-251`）：
  - 为空时改成 `players_size()` 个 0，即每个玩家快照对应一只兜底怪；
  - 生产实现读 `DungeonTable.monster` 并**跳过 0**（`table_provider.cpp:44-59`）；测试实现原样返回（`mem.h:112-115`）。
- **第 i 只怪的字段**：
  - `actor_id = kMonsterActorIdBase + i`；team 1；名字 `"野怪"`；level 取参考等级；`monster_table_id` 取表 id；`formation_slot = NextFormationSlot(1)`（`:264-272`）。
  - **表行存在且 `health > 0`** 时：health / strength / armor / resistance / critchance 取表值；speed 取表值，为 0 时回落 60；`max_health = health`（`:276-285`）。
  - 否则全部用默认值 300 / 5 / 24 / 0 / 0 / 60，`max_health = 300`（`:286-294`）。
    注意：表行存在但 health 为 0 时也走默认值，此时 `monster_table_id` 仍是表 id，**击杀会被记账**（§6.4）。
- 追加怪物**不查重**（`:261-297`），靠号段隔离保证不撞号。`InitMonsters` 总是返回 true（`:258`）。

### 1.8 `NextFormationSlot`（`engine.cpp:299-309`）

结果 = 该队现有单位数，玩家、宝宝、怪物都算。效果：
- 插入序就是阵位序；
- 两名玩家各带一只宝宝时，A=0、B=1、A 的宝宝=2、B 的宝宝=3；
- 只用于演出，不参与任何判定。

---

## 2 行动提交与校验

### 2.1 `SubmitAction`（`engine.cpp:315-331`）

```
if (!initialized || outcome != ONGOING) return false;             // 不再调 AllPlayersReady
actor = FindActor(actorId)
if (actor 存在 && 是 PLAYER && 存活且未逃跑 && CheckActionPrerequisites(actor, action) == kSuccess)
    pendingActions[actorId] = action;                             // 覆盖旧值，整条 action 原样保存
return AllPlayersReady();                                         // 无论收没收，都返回就绪态
```

- **只收 PLAYER**。对宝宝或怪物提交会被静默忽略（`test.cpp:1598-1600`）。
- **最后一次合法提交生效**：后一次不合法的提交**不会清掉**此前已收下的合法行动，函数照样返回就绪态。
  - 节点先调 `ValidateAction`，把错误 tip 回给客户端后就不再调 `SubmitAction`（`room.cpp:709-724`）。
  - 结果是：客户端看到错误，但先前排队的行动仍会执行。这是基线行为，照搬（§11.1）。
- **挂机玩家也能手动提交**，并且手动行动优先：`FillDefaultActions` 只补缺（`engine.cpp:678-680`）。

### 2.2 `ValidateAction`（`engine.cpp:333-346`，零副作用）

1. 未初始化或已结束 → 1005。
2. 单位不存在，或不是 PLAYER → 1005。
3. 已死或已逃 → 1009。
4. 其余情况返回 `CheckActionPrerequisites`（§2.5）。

与 `SubmitAction` 的接受条件逐条同源：`ValidateAction == 1000` 等价于这次提交会被收下（`engine.h:43-46`）。

### 2.3 `SetActorAuto`（`engine.cpp:348-364`）

1. 未初始化或已结束 → 1005。
2. 不存在或不是 PLAYER → 1005（`test.cpp:762-763`、`:1600`）。
3. 已死或已逃 → 1009。
4. 否则写入 `is_auto = enabled`，**返回 0**（Java 返回 1000，D2）。

全程不耗 RNG，也不触发结算。

### 2.4 `AllPlayersReady`（`engine.cpp:366-380`）

- 遍历全部单位，跳过：非 PLAYER、已死或已逃、`is_auto` 的单位。剩下的只要有一个没有 pending，就返回 false。
- 没有任何需要提交的玩家时返回真（空真）。例如 A 方玩家全灭、只剩宝宝时，节点会按 2 s 节奏推进（`room.cpp:1029-1030`）。
- 不检查 `initialized` 和 `outcome`。

### 2.5 `CheckActionPrerequisites` 顶层（`engine.cpp:386-440`）

**第一步：`CheckState`**（`:593-602`）
- 已死或已逃 → 1009。
- 身上有眩晕(30) 或冰冻(52) 的 buff → **7006**。

  buff 类型按表行判断，表行缺失的条目忽略（`ActorHasBuffOfType`，`:1726-1734`）。

**第二步：按 `action_type` 分派**（取 proto 的 int 值。Java 用 `getActionTypeValue()`，未知值走 default，与 C++ 开放枚举一致）：

| action_type | 结果 |
|---|---|
| ATTACK(1) / DEFEND(3) | 1000。**不校验目标**：普攻目标失效、甚至指向队友，都由结算期重选（§3.6） |
| FLEE(5) | PVE 为 1000，否则 1005 |
| ITEM(4) | `CheckItemUse`（§6.1） |
| SKILL(2) | 技能校验链（§4.2） |
| NONE(0) 及未知值 | 1005 |

**基线隐患（照搬）**：眩晕或冰冻中的手动玩家，提交任何行动都会被 7006 拒绝，所以永远到不了「就绪」，回合只能等满 6 s 窗口。`SetActorAuto` 不检查眩晕，开挂机可以绕开（§11.1）。

---

## 3 回合结算

### 3.1 `ResolveCurrentRound`（`engine.cpp:608-671`）

```
result = new TurnResultS2C
if (!initialized) return result                                      // 空包（Java 不存在这种实例）
result.battle_id = createRequest.battle_id; result.round_index = roundIndex
if (outcome != ONGOING) { result.state = BuildStateSnapshot(); return result }   // 无事件，lastActionOrder 不变，不耗 RNG
1. FillDefaultActions()                                              // §3.2
   currentGroupId = 0; currentHitIndex = 0
   lastActionOrder = BuildTurnOrder()                                // §3.3
2. for id in lastActionOrder:
       actor = FindActor(id); 已死或已逃 → continue                  // 回合中途死亡 / 逃跑的不占组号
       没有 pending → continue                                       // 实际不会发生
       BeginEventGroup()                                             // 先开组再执行
       ExecuteAction(actor, pending)                                 // §3.5
3. TickBuffsAtRoundEnd(result)                                       // §5.5
4. DecayCooldowns()                                                  // §4.4
5. 所有单位 is_defending = false
6. UpdateOutcome()                                                   // §3.11
7. RollDrops()                                                       // §6.5，只在刚判出 SIDE_A_WIN 时掷点
pendingActions.clear(); if (outcome == ONGOING) ++roundIndex
result.state = BuildStateSnapshot()                                  // §7.1
return result                                                        // 不填 action_order
```

对应行号：`:610-612`、`:614-615`、`:617-620`、`:623`、`:627-629`、`:632-644`、`:647`、`:650`、`:653-655`、`:658`、`:662`、`:664-667`、`:669`。

- `result.round_index` 是**本次结算的回合号**。
- `result.state.round_index` 在未结束时是**下一回合号**，已结束时是**同一回合号**（`test.cpp:713-725`）。

### 3.2 `FillDefaultActions`（`engine.cpp:673-687`）

按 `actors` 插入序，给每个存活、未逃、还没有 pending 的单位补一条 `{ATTACK, target_id = 0}`。
- 覆盖对象：未提交的玩家、挂机玩家、全部怪物、全部宝宝，也包括被眩晕的单位（它们在执行时被跳过）。
- 这一步不耗 RNG。

挂机等价于「手动提交 ATTACK，目标 0」，两者的事件流与结算必须逐字节相同（`test.cpp:815-845`）。

### 3.3 `BuildTurnOrder`（`engine.cpp:689-711`）

- 只纳入回合开始时存活且未逃的单位。
- 排序键：`speed` 降序，平手时 `actor_id` 升序。
- C++ 用不稳定的 `std::sort`，但 actor_id 唯一，键构成全序，所以结果唯一。
- **Java 两个键都必须用无符号比较**：

```java
static final Comparator<BattleUnit> TURN_ORDER = (l, r) -> l.speed() != r.speed()
        ? Long.compareUnsigned(r.speed(), l.speed())          // 速度降序
        : Long.compareUnsigned(l.actorId(), r.actorId());     // actor_id 升序（怪物 / 宝宝是负数 long）
```

  用有符号比较时，同速的怪物或宝宝会排到玩家前面；C++ 的结果是玩家在前（G-TIE，§13.5）。
- `lastActionOrder` 包含回合中途被跳过的单位（`engine.h:62-65`）。下一次结算前它保持不变；战斗结束后再调结算，它也不变。

### 3.4 事件分组（表现规格 D2；`engine.cpp:1772-1788`）

- **`AppendEvent`**：只写 `event_type`、`source_id`、`target_id`，并盖上当前的 `group_id` / `hit_index`。其余字段由调用方补。
- **`BeginEventGroup`**：执行 `++group; hit = 0`。每回合从 1 开始。
- **开组时机**
  1. 每个**实际轮到出手**的单位在 `ExecuteAction` 之前开一组（`engine.cpp:642`）。即使最终没有产出任何事件，组号也照样被占用：
     - 被眩晕或冰冻跳过（`:716-718`）；
     - 普攻时敌方已清场（`:747-749`）；
     - AOE 技能没有可打的目标（`:805-807`）；
     - 道具落空（`:958-964`）。

     所以事件流里的 group 号可能不连续。
  2. 回合末，每个存活、未逃且 **buff 列表非空**的单位开一组（`:1055-1060`），即使这一组不产出事件。buff 为空的单位不开组。
- **`hit_index`**：只有技能逐目标落地时设为目标序 0..n-1，结束后归 0（`:847-855`）；其余情况都是 0。目标 i 引起的全部事件的 hit_index 都是 i，包括：
  - DAMAGE、DEATH；
  - 挂在目标身上的 BUFF_ADD / BUFF_REMOVE；
  - 经 `target_sub_buff` 挂回施法者身上的事件。

### 3.5 `ExecuteAction`（`engine.cpp:713-739`）

- 身上有眩晕或冰冻 buff 时**直接 return**，不发事件（组号已占）。
- 否则按类型分派到 Attack(§3.6) / Skill(§4.7) / Defend(§3.8) / Item(§6.2) / Flee(§3.9)。未知类型什么也不做。

### 3.6 普攻 `ExecuteAttack(actor, targetId)`（`engine.cpp:741-783`）

1. `target = FindActor(targetId)`。以下情况重选目标（`:744-755`）：目标为 null、已死、已逃，或**与自己同队**。重选方法：
   - `enemyIds = CollectAliveEnemyIds(actor)`：按 `actors` 插入序，取异队、存活、未逃的单位（`:1747-1756`）；
   - 为空则 return，不发事件；
   - 否则 `targetId = enemyIds[RandIndex(size)]`。**每次都消耗一次 RNG，只有 1 个候选也照样消耗**（R1，§8.4）。

   补充：
   - 默认行动的目标是 0，而 0 号单位不存在（player_id 0 已被拒），所以默认行动必然走重选。
   - 显式指定了存活的敌方目标时，**不耗 RNG**。
2. 追加 `ATTACK` 事件：source = 出手者，target = 目标（`:757`）。
3. `RollHit` 恒为真，不耗 RNG（`:1790-1802`）。`MISS` 分支（`:760-766`）不可达，但**保留骨架**：二期接表时会在暴击之前加一次掷骰（`constants.h:109-116`）。
4. `finalDamage = CalculateFinalDamage(actor, target, 10.0, actor.physical_attack, 1.0)`（§3.7）。
5. `dealt = ApplyDamage(target, finalDamage)`。
6. 追加 `DAMAGE` 事件（`:774-778`）：
   - `value = dealt`，**为 0 也发**；
   - 带 `is_critical`、`target_health_after`、`target_mana_after`；
   - 不带 skill_table_id。
7. 目标 `health == 0` 时调 `HandleDeath(target, actor.actor_id)`（§3.10）。

   注意：快照带进来的「0 血但活着」的单位，被打一下（伤害为 0）也会走到这里判死。

### 3.7 伤害、治疗原语

**`CalculateFinalDamage(caster, target, base, attack, mult)`**（`engine.cpp:1317-1343`）

```
critChance = clamp(toDouble(caster.critchance) / 100.0, 0.0, 1.0)     // critchance 是 uint64
d = CombatDamageRules.damageBeforeCritical(base, caster.strength, attack, mult,
        target.armor, target.defense, target.resistance, target.level)   // rules.h:63-73
if (!IsPveMatch()) d *= 0.3                                           // 先乘 PvP 系数
if (critChance > 0.0 && Rand01() < critChance) { d *= 2; crit = true }  // 暴击率为 0 时不耗 RNG；严格小于
return d < 0.0 ? 0.0 : d                                              // std::max(d, 0.0)
```

目标等级的取法：玩家与宝宝取快照等级，怪物取参考等级（`:1326`）。

**`ApplyDamage(target, raw)`**（`engine.cpp:1345-1359`）

```
if (raw <= 0) return 0                       // NaN 不进这个分支，由 damageToHealth 对非有限值返回 0
if (target.is_defending) raw *= 0.5
dmg = CombatDamageRules.damageToHealth(raw, health)    // ceil，封顶到当前气血，非有限 → 0（rules.h:76-81）
health -= dmg; return dmg
```

**`ApplyHeal(target, raw)`**（`engine.cpp:1361-1371`）

```
if (raw <= 0 || target.is_dead) return 0
after = minUnsigned(max_health, (uint64)(toDouble(health) + raw))   // 先加再截断
health = after; return after - health_before                        // health_before > max_health 时回绕成巨大值
```

- 回绕是基线行为，照搬。
- C++ 的 NaN、无穷、结果 ≥ 2^64 都是 UB；Java 口径见 D5：非有限的 raw 按 0 处理，和 ≥ 2^64 时饱和到 UINT64_MAX。

### 3.8 防御 `ExecuteDefend`（`engine.cpp:942-945`）

- 执行时置 `is_defending = true`，追加 `DEFEND` 事件：source = target = 自己，value 为 0。
- **生效时点**：只在该单位**执行 DEFEND 的那一刻**才置位，一直保持到回合末 tick 之后（`:653-655`）。由此：
  - 速度更快的敌人在防御者出手**之前**打出的伤害**不减半**；
  - 回合末的毒、灼烧伤害**会减半**（`test.cpp:548-573`）；
  - 防御者阵亡时 `is_defending` 会被清掉（`engine.cpp:1392`）。
- 快照里的 `is_defending` 恒为 false，因为快照只在回合之间产生。

### 3.9 逃跑 `ExecuteFlee`（`engine.cpp:1014-1031`）

```
success = false
if (PVE) {
    diff   = toDouble(speed) - toDouble(MaxAliveEnemySpeed(actor))     // 没有存活敌人时最大值为 0（:1736-1745，无符号 max）
    chance = clamp(0.5 + (0.01/12.0) * diff, 0.05, 0.95)               // 先乘后加，不得融合
    success = Rand01() < chance                                        // 只在 PVE 时消耗 1 次 RNG（R5）
}
追加 FLEE 事件（source = target = 自己，success）
if (success) actor.fled = true
```

- PVP 下 FLEE 在提交时就被拒（§2.5）。默认行动是 ATTACK，所以执行期的 PVP 分支不可达。
- 逃走的单位保留 buff 和冷却，但之后不再 tick buff（`:1040-1042`），也不会再被选为目标。

### 3.10 死亡 `HandleDeath(target, sourceActorId)`（`engine.cpp:1373-1396`）

1. 目标已死，或 `health != 0`，直接 return。只在「活着 → 死亡」的转移上处理一次。
2. **击杀簿**（`:1379-1390`）：
   - `source = FindActor(sourceId)`；
   - `owner`：source 是 PET 时取 `FindActor(source.owner_player_id)`，否则就是 source；
   - 下列条件**全部**满足才追加 `{monster_config_id = target.monster_table_id, count = 1}`：
     - target 是 MONSTER，team 为 1，且 `monster_table_id != 0`（兜底怪永远不记）；
     - source 存在且 team 为 0；
     - owner 存在、是 PLAYER、team 为 0。

   补充：
   - 来源已死或已逃仍然算，例如施毒者先阵亡。
   - 来源为 0、来源是怪物，或者友伤，都不算（`test.cpp:1754-1784`）。
3. `is_dead = true`，`is_defending = false`，**清空 buff，不发 BUFF_REMOVE**（`:1391-1394`）。
4. 追加 `DEATH` 事件，**source = target = 死者**，不是凶手（`:1395`）。

调用点有三处：
- 普攻（`:780-782`）；
- 技能，逐目标调用（`:889-891`）；
- 回合末毒、灼烧（`:1158-1160`），来源取 buff 的 `caster_id`。周期伤害致死后，该单位本轮的 tick 立即结束（`:1096-1099`）。

### 3.11 胜负 `UpdateOutcome`（`engine.cpp:1180-1194`）与回合计数

```
A 灭 = SideWiped(0); B 灭 = SideWiped(1)        // 该队没有存活未逃的单位，宝宝也算（:1758-1765）
if (A 灭 && B 灭)                 outcome = DRAW
else if (A 灭)                    outcome = SIDE_B_WIN
else if (B 灭)                    outcome = SIDE_A_WIN
else if (roundIndex >= maxRounds) outcome = SIDE_B_WIN    // 打满回合进攻方判负，PVP 也一样（uint32 比较）
```

- `roundIndex` 是正在结算的回合号，判定时还没有加 1。`maxRounds = 2` 时，第 2 回合结算后判 B 胜，`total_rounds = 2`（`test.cpp:527-546`）。
- **`CompletedRounds()`**：进行中为 `roundIndex - 1`，已结束为 `roundIndex`（`:1767-1770`）。
- 全员逃跑同样算该方覆灭。单人逃跑成功会让 A 方判负（`test.cpp:628-630`）。
- A 方玩家全灭、只剩宝宝时，战斗继续。宝宝若打赢，判 SIDE_A_WIN，但阵亡的玩家拿不到奖励（§6.6）。

---

## 4 技能

### 4.1 可施放过滤（`IsTurnBattleCastableSkill`，`engine.cpp:482-493`）

- **规则**：`skill_type` 里任一**原始取值**等于 0（Passive）、2（Channel）或 3（Toggle），即不可施放。比较的是原始值，不做移位。
  `skill_type` 为空，或只含其它值（包括 ≥ 6 的值），一律放行。
- **用在两处**：
  - InitPlayers 入场过滤（`:162-168`）；
  - 校验链第 5 步（§4.2）。
- scene 出快照时用同一规则再过滤一遍（`pb.cpp:973-988`）。注释要求两处同改（`engine.h:111-116`），Java 合成一处（D7）。
- **宝宝的技能不过滤**（`:231-233`）。宝宝恒为挂机，永远只普攻，技能列表从不被使用。

### 4.2 SKILL 校验链（`CheckActionPrerequisites`，`engine.cpp:386-440`）

提交期（`SubmitAction` / `ValidateAction`）和出手期（`ExecuteSkill` 开头）共用这一条链。**顺序固定，命中第一条即返回**：

| # | 检查 | 失败码 | 行 |
|---|---|---|---|
| 1 | `CheckState`：已死或已逃 | 1009 | :593-596 |
| 2 | `CheckState`：有眩晕(30) 或冰冻(52) 类型的 buff（表行缺失的条目不算） | 7006 | :597-600 |
| 3 | `FindSkill(skill_table_id)` 为空，包括 id 为 0 | 1001 | :404-407 |
| 4 | 不在 `actor.skill_table_ids` 里（线性查找） | 1005 | :409-413 |
| 5 | 黑名单（§4.1） | 7004 | :416-418 |
| 6 | `ValidateSkillTarget`（§4.3） | 7001 | :419-422 |
| 7 | `CheckCooldown`（§4.4） | 7003 | :423-425 |
| 8 | `CheckPlayerLevel`：恒通过（桩，与实时侧同口径） | — | :552-558 |
| 9 | `CheckBuff`：沉默许可（§4.5） | 1002 或格值 | :429-431 |
| 10 | `CheckSkillCost`：法力（§4.6） | 7004 | :495-503 |

### 4.3 目标校验 `ValidateSkillTarget`（`engine.cpp:505-533`）

1. **零目标闸**：`targeting_mode` 非空、且 `target_id == 0` → 7001（`:510-512`）。无目标技能、AOE 技能也会被拦。
2. 按 `targeting_mode` 的顺序逐个处理位号，令 `mode = 1u << 位号`（`:514-515`）：
   - `mode` 为 NoTarget(1) 或 AOE(4)：立即通过，**不检查目标是否存在**（`:518-520`）。
   - `mode` 不是 Targeted(2)：跳过，看下一个位号（`:521-523`）。
   - `mode` 是 Targeted：`FindActor(target_id)` 为空，或目标已死、已逃 → 7001；否则通过（`:525-529`）。**不查阵营，也不禁止选自己**。
3. 所有位号都认不出（例如 `[3]`）：通过（`:532`）。此时只受第 1 步零目标闸的约束。
4. 位号 ≥ 32 时，`1u << 位号` 在 C++ 里是 UB。x86 运行时会按低 5 位截断，Java 的 `1 << 位号` 同样按低 5 位截断，两边一致，无需登记。

**AOE 的判定与校验不同源**

- `IsAreaSkill`（`:903-911`）：`targeting_mode` 里**任一**位号移位后等于 AOE，就算群攻。
- 校验：只看**第一个认得出的位号**。
- 例子：真表技能 1 的 `targeting_mode = [1, 2]`。
  - 校验按指向性走，要求有一个存在且存活的目标，阵营不限；
  - 出手时按 AOE 打全体存活敌方；
  - 若这个名义目标在出手前死了，重验会失败，整个技能**降级为普攻**（§4.7 第 1 步）。

### 4.4 冷却

**检查 `CheckCooldown`**（`engine.cpp:535-550`）

遍历 `skill_cooldown_rounds` 的每一项 `(skillId, rounds)`，只要有一项同时满足下面三条，就返回 7003：
- `rounds != 0`；
- 该 `skillId` 在 Skill 表里有行；
- 该行的 `cooldown_id` 等于待施放技能的 `cooldown_id`。

结果只是「存在与否」的布尔值，与遍历顺序无关。

**开冷却**（`:828-832`）
- 时机：目标集确定之后、SKILL 事件之前。
- `GetCooldownDurationMs(cooldown_id) > 0` 时，执行 `map[action.skill_table_id] = RoundsFromMilliseconds(ms)`，**覆盖**原值。
- `cooldown_id` 没有对应的 Cooldown 行，或 duration 为 0，就不写。

**衰减 `DecayCooldowns`**（`:1169-1178`）
- 时机：回合末，buff tick 之后（`:650`）。
- 范围：**全部单位，包括已死和已逃的**。每项 `> 0` 的值减 1。
- **减到 0 的项不删除**，`{skillId: 0}` 会一直留在快照里。
- Java 写法：先把键值对拷贝出来，再逐个 `put`，不在 map 视图上边迭代边改。

**时间线**（冷却 12000 ms = 2 回合）：
- 第 1 回合施放，写入 2，回合末衰减为 1；
- 第 2 回合仍在冷却，提交不会落账；回合末衰减为 0；
- 第 3 回合可以再放（`test.cpp:324-348`）。

500 ms、2000 ms 都换算成 1 回合，施放当回合末就衰减到 0，**实际等于没有冷却**。

### 4.5 沉默许可 `CheckBuff`（`engine.cpp:560-591`）

1. 施法者身上没有类型 31 的 buff（表行缺失的条目跳过）→ 通过。
2. `FindSkillPermission(1)` 为空 → **1002**。
   - 注意：Java 实时侧 `SkillRules.checkSkillPermission` 在行缺失时回 1001（`xm-scene/src/main/java/com/game/scene/skill/SkillRules.java:78-80`）。口径不同，**不能复用**。
3. 对技能的每个 `skill_type` 取值 `b` 依次判断：
   - `(int32) b ≥ 行宽` → 1002（`:575-578`）；
   - 格值为 0 → 1002（`:583-585`）；
   - 格值不是 1000 → 原样返回这个格值（`:586-588`）。
4. 全部通过，或 `skill_type` 为空 → 1000。
5. C++ 遇到 `b ≥ 2^31` 时，`static_cast<int32_t>` 会变成负下标（UB）。Java 用 `Integer.compareUnsigned(b, size) >= 0` 判为 1002，与 xm-scene 一致（`SkillRules.java:82-84`；D5）。

### 4.6 耗蓝

- **`SkillManaCost`**（`engine.cpp:913-922`）：对 `cost_resource[]` 里 `cost_resource_id == 1` 的各项，把 `cost_resource_cost`（uint32）累加成 uint64。其它资源 id 一律忽略，例如真表技能 1 的 `{2: 20}`。
- **校验**：`cost > attributes.mana`（无符号）→ 7004（`:499-501`）。
- **扣除 `ConsumeSkillMana`**（`:924-940`）：
  - 时机：SKILL 事件之后、算伤害之前。
  - cost 为 0 时不出事件。
  - `after = before > cost ? before - cost : 0`。
  - 出 MANA 事件：source = target = 施法者，带 `skill_table_id`，`value = before - after`，`target_health_after = 施法者气血`，`target_mana_after = after`。

### 4.7 出手 `ExecuteSkill`（`engine.cpp:785-856`）

1. **重跑整条校验链**（`:789-792`）。失败则降级为 `ExecuteAttack(actor, action.target_id)`，事件落在同一组。表行查不到同样降级（`:794-798`）。
   - 出手期真正可能触发的失败只有两种：被本回合先出手的单位挂上沉默；指向性目标已死或已逃。
   - 眩晕、冰冻已在 `ExecuteAction` 里提前返回。
   - 降级的普攻遇到已死、已逃或同队的目标会重选，消耗一次 RandIndex（R3）。
2. **目标集**：
   - **AOE**：`CollectAliveEnemyIds`，即全体存活、未逃的异队单位，按插入序排列，**不耗 RNG**（`:803-807`）。为空则直接 return：不出 SKILL 事件、不开冷却、不扣蓝。
   - **单体**：`FindActor(target_id)` 存在且存活未逃，就用它。**不查阵营，可以打队友或自己**。否则从存活敌方里 `RandIndex(n)` 选一个（R2，`:808-822`）；没有敌方就直接 return。
     - 指向性技能重验通过后，目标必然有效，所以「目标无效 → 随机」只对以下单体技能可达：首个位号是无目标的、位号认不出的、模式为空的。
3. **开冷却**（§4.4）。
4. 出 SKILL 事件：source = 施法者，`target = targetIds[0]`，带 `skill_table_id`（`:834-835`）。
5. 耗蓝（§4.6，`:838`）。
6. `baseDamage = GetSkillDamage(skill_table_id, (double) 施法者等级)`。等级按 uint32 换成 double。**只求值一次**，所有目标共用（`:843-844`）。
7. **逐目标落地**（`:847-855`）：`currentHitIndex = 下标`；`FindActor` 为空就跳过，但下标照常递增；调 `ApplySkillToTarget`。循环结束后 hit 归 0。

**`ApplySkillToTarget`**（`engine.cpp:858-901`）
1. `RollHit` 恒为真，不耗 RNG。
2. `baseDamage > 0` 时：
   - `attack = selectAttack(damage_type, 物伤, 法伤)`；
   - `final = CalculateFinalDamage(施法者, 目标, base, attack, attack_multiplier)`，暴击掷骰 R4 发生在这里；
   - `dealt = ApplyDamage(目标, final)`；
   - 出 DAMAGE 事件：带 `skill_table_id`、`value = dealt`、`is_critical`，以及两个 after 字段；
   - 气血为 0 时调 `HandleDeath(目标, 施法者)`。

   `baseDamage` 为 0、负数或 NaN 时，整段跳过：没有 DAMAGE 事件，也不掷暴击（`rules.h:67-68`）。
3. 目标没死，才按 `effect[]` 的表序逐个调用 `AddBuffToActor(目标, buffId, caster = 施法者, depth = 0)`（`:896-900`）。effect 里的 0 会因查表失败自然跳过。

---

## 5 buff

### 5.1 承载与实例号

- buff 条目存在 `BattleActorState.buffs` 里，类型是 `BattleBuffEntry{buff_id, buff_table_id, layer, remain_rounds（0 = 无限）, caster_id}`（`battle_data.proto:22-28`）。
- **实例号**：
  - 开局时按全部快照 buff_id 推高计数器，在清洗之前进行（§1.5）。
  - 新建条目时取 `id = next++`（`engine.cpp:1432`）。
  - **只有新建条目消耗实例号**。叠层、驱散、免疫拦截、纯驱散 buff 都不消耗。

### 5.2 挂载 `AddBuffToActor(target, buffTableId, casterId, depth)`（`engine.cpp:1402-1480`）

严格按以下顺序执行：

1. `depth > 8`，或目标已死 → return（`:1405-1407`）。
2. 查不到 buff 行 → return（`:1409-1412`）。
3. **免疫**（§5.3）→ return，不出任何事件（`:1415-1417`）。
4. **驱散**（§5.3，`:1420`）。
5. `buff_type == 35`（纯驱散）→ return：不落地，不出 ADD 事件（`:1423-1425`）。
6. **叠层 / 刷新**（§5.3）。命中则 return（`:1428-1430`）。
7. **新建条目**（`:1432-1447`）：`layer = 1`，`caster_id = casterId`。`remain_rounds` 的取值：
   - `infinite_duration != 0` → 0，表示无限；
   - 否则 `duration > 0` → `RoundsFromSeconds(duration)`；
   - 否则为 1（瞬时 buff）。
8. 出 BUFF_ADD 事件：`source = casterId`，`target = 持有者`，带 `buff_table_id`，`value = 1`（`:1453-1455`）。
9. `sub_buff[]` 按表序递归：`AddBuffToActor(target, sub, casterId, depth + 1)`，挂给持有者本人（`:1458-1460`）。
10. `target_sub_buff` 非空，且 `FindActor(casterId)` 存在、**未死**（不检查是否已逃）时，按表序递归 `AddBuffToActor(施法者, tsub, caster = target.actor_id, depth + 1)`（`:1464-1471`）。
    方向是对调的；自己对自己施放时，就挂回自己身上。
11. **瞬时条目**（`infinite == 0 && duration <= 0`）：按 newBuffId 重新查找下标，找到就 `RemoveBuffAt`，出 BUFF_REMOVE 事件（`:1474-1479`）。

**Java 注意**：第 9–10 步的递归可能把刚建的条目驱散掉，**不能持有 builder 引用跨过递归**，之后一律按实例号重新查找（`:1448-1451`）。

### 5.3 免疫、驱散、叠层、移除

- **免疫 `IsImmuneToBuff`**（`engine.cpp:1482-1497`）：目标身上任一现存条目（其表行存在）的 `immune_tag`，包含新 buff 任一 `tag` 的**键**，就算免疫。
  - 只看键，用 `containsKey`，忽略 bool 值。
  - 免疫靠的是**任意类型** buff 的 `immune_tag`。类型 34 本身没有特殊逻辑。
- **驱散 `DispelBuffsByTag`**（`engine.cpp:1499-1523`）：新 buff 的 `dispel_tag` 非空时执行。
  - 先按下标升序收集：表行存在、且其 `tag` 含 `dispel_tag` 任一键的现存条目；
  - 再**按下标降序**逐个调 `RemoveBuffAt`，所以 BUFF_REMOVE 事件按插入序的逆序产出；
  - 不分施法者，也能驱掉提供免疫的 buff。
- **叠层 / 刷新 `StackOrRefreshExistingBuff`**（`engine.cpp:1525-1551`）：按列表顺序找**第一个**同时满足以下条件的条目：
  `buff_table_id` 相同；并且 `no_caster != 0` 或 `existing.caster_id == casterId`。找到后：
  - `layer < max_layer`（uint32 比较）时 `layer + 1`。`max_layer = 0` 时永不加层；新建条目的 layer=1 不受 max_layer 约束；
  - `existing.remain_rounds > 0` 且 `row.duration > 0` 时，刷新为 `RoundsFromSeconds(duration)`，即重挂满时长。无限条目不刷新；
  - 出 BUFF_ADD 事件：`source = 本次 casterId`，带 `buff_table_id`，`value = 加层后的层数`；
  - **不触发子 buff**，也不改条目原来的 `caster_id`。
- **`RemoveBuffAt`**（`engine.cpp:1553-1564`）：删除该下标的条目，出 BUFF_REMOVE 事件：`source = 该条目的 caster_id`，`target = 持有者`，带 `buff_table_id`，不填 value。

### 5.4 buff 类型的作用（只有这几种）

| buff_type | 作用点 |
|---|---|
| 30 眩晕、52 冰冻 | 校验链回 7006（`engine.cpp:597-600`）；出手时行动作废，不出事件（`:716-718`） |
| 31 沉默 | 只限制 SKILL，走 SkillPermission（§4.5）；普攻、防御、道具、逃跑都不受影响 |
| 40、42 回血 | 回合末周期回血（§5.6） |
| 50、51 毒、灼烧 | 回合末周期伤害（§5.6） |
| 35 驱散 | 纯驱散，不落地（§5.2 第 5 步） |
| 其余全部（0–21、32 无敌、33、34、36、41 回蓝、43、100 等） | **在回合引擎里没有任何效果**。类型 32 不免伤，类型 41 不回蓝；`kBuffTypeInvincibility` / `kBuffTypeImmunity` / `kBuffTypeManaRegeneration` 三个常量没有任何读者（`constants.h:57-61`） |

另外，任何 buff 的 `tag` / `immune_tag` / `dispel_tag` 都会参与 §5.3 的判定。

### 5.5 回合末 tick

**`TickBuffsAtRoundEnd`**（`engine.cpp:1037-1045`）：按 actors 插入序（玩家 → 宝宝 → 怪物）逐个处理，只处理存活未逃的单位。

**`TickActorBuffs`**（`engine.cpp:1047-1116`）

1. 先拍下全部 buff_id 的快照。列表为空就 return，**不开组**；否则开一组（`:1050-1060`）。
2. 按快照里的 id 顺序逐个处理：
   - 按 id 找下标，找不到就跳过（`:1063-1066`）。
   - 复制条目，查表行。**行缺失就跳过，而且不递减**（`:1067-1071`）。正常数据下不可达：清洗会丢掉缺行的条目，引擎内又只新建有行的条目。
   - **周期效果**（`interval > 0` 时，`:1075-1094`），全部是 uint32 运算：
     - 周期回合数 `k = RoundsFromSeconds(interval)`；
     - 已持续回合数 `elapsed`：有限条目（`remain > 0`）令 `total = RoundsFromSeconds(duration)`，这是**表时长**，不是条目实际的初始剩余；
       `elapsed = total >= remain ? total - remain + 1 : 1`。无限条目（`remain == 0`）取 `elapsed = roundIndex`，即**全局回合序号**；
     - 当 `elapsed % k == 0`，且 `interval_count == 0 || elapsed / k <= interval_count` 时，调 `ApplyBuffIntervalEffect`（§5.6）。
     - Java 用 `Integer.remainderUnsigned` / `divideUnsigned`。k 被截断成 0 时的口径见 D5。
   - 结算后若该单位已死，**结束本单位的 tick**，此时 buff 已被死亡清空（`:1097-1099`）。
   - 按 id 重新查找下标：`remain == 0` 不动；`remain == 1` 调 `RemoveBuffAt`（出 BUFF_REMOVE）；否则 `remain - 1`（`:1102-1114`）。

**推论**
- 有限 buff 剩 N 回合、k = 1 时，共 tick N 次，**挂上的当回合末就 tick 第一次**。
- 叠层刷新会把 elapsed 重置为 1，计数重新开始。
- 无限 buff 的 `interval_count` 按全局回合序号计数，开局很晚才挂上的 buff 也是这样。
- 回合末的固定顺序：tick → 冷却衰减 → 撤掉全体防御 → 判胜负 → 掷掉落（`engine.cpp:646-662`）。

### 5.6 周期效果 `ApplyBuffIntervalEffect`（`engine.cpp:1118-1167`）

**类型 40 / 42：回血**
- `lost = toDouble(max_health - health)`。这是 **uint64 减法**，`health > max_health` 时会回绕成巨大值。
- `heal = GetBuffHealthRegeneration(row.id, (double) level, lost)`。
- `healed = ApplyHeal(...)`（§3.7）。
- `healed > 0` 时出 BUFF_TICK 事件：`source = 条目的 caster_id`，`target = 本单位`，带 `buff_table_id`、`value = healed`，以及两个 after 字段。

**类型 50 / 51：毒、灼烧**
- `interval_effect` 为空时没有效果。
- `raw = interval_effect[0] × (double) max(1, layer)`。层数为 uint32；0 层按 1 层算。**不乘 PvP 系数，不掷暴击，不过伤害公式**。
- `dealt = ApplyDamage(...)`，防御中减半，再向上取整并封顶。
- `dealt > 0` 时出 BUFF_TICK 事件，字段同回血。
- 然后**只要气血为 0**（包括本来就是 0 的单位）就调 `HandleDeath(本单位, 条目的 caster_id)`。

**其余类型**：没有效果。

### 5.7 死亡清空

`HandleDeath` 执行 `clear_buffs()`，**不出任何 BUFF_REMOVE 事件**（`engine.cpp:1393-1394`）。

### 5.8 快照 buff 清洗 `SanitizeSnapshotBuffs`（`engine.cpp:1245-1285`）

- 时机：全部单位就位之后，只对 PLAYER 执行（`engine.cpp:106-111`）。
- 按快照顺序处理，保留的条目保持原来的相对顺序：
  1. 查不到表行 → 丢弃；
  2. 类型是 30、52、31 → 丢弃；
  3. `infinite == 0 && duration <= 0`，即瞬时 buff → 丢弃；
  4. `caster_id != 0` 且 `FindActor(caster)` 为空 → `caster_id` 置 0。查找范围包括怪物和宝宝；
  5. 有限条目：`tableRounds = RoundsFromSeconds(duration)`；`remain == 0 || remain > tableRounds` 时改成 tableRounds。
     **无限条目保留快照里的 remain**，非 0 的照样会递减、到期；
  6. layer **不夹**：0 层在毒伤里按 1 层算，超过 max_layer 的层数原样保留。
- scene 侧的第一道过滤与这里同源，并且只把「施法者是自己」映射成 player_id、remain 直接取表全量时长、layer 至少为 1（`pb.cpp:1004-1047`）。

---

## 6 道具、掉落与结算

### 6.1 `CheckItemUse`（`engine.cpp:442-480`，提交期和出手期共用）

调用前已经过了 `CheckState`（1009 / 7006）。之后按以下顺序检查：

| # | 条件 | 码 |
|---|---|---|
| 1 | `FindItem(item_table_id)` 为空 | 1001 |
| 2 | `battle_usable == 0` | 1005 |
| 3 | `battle_heal_hp == 0 && battle_heal_mp == 0` | 1005 |
| 4 | 本人副本里没有 `item_table_id` 相同且 `count > 0` 的条目（`FindItemEntry`） | 6007 |
| 5 | 非 PVE，且 `itemUseCounts[本人] >= 5` | 1005 |
| 6 | `target_id` 不是 0 也不是自己时：目标不存在，或已死 / 已逃 | 7001 |
| 7 | 同上，目标是敌方 | 7002 |

- 沉默不限制道具。目标可以是同队的玩家或宝宝。
- `itemUseCounts` 记的是**实际执行次数**，不是提交次数（`:976`）。

### 6.2 出手 `ExecuteItem`（`engine.cpp:947-1012`）

1. 用 `effective = action` 跑 `CheckItemUse`（`:953-965`）。不通过时：
   - 原目标是 0 或自己 → 落空 return，**不出事件**，组号已被占用；
   - 否则把目标改成自己，再验一次，仍不通过也落空。
   - **从不降级为普攻**，也不耗 RNG。
2. 取表行、副本条目（第一个 count > 0 的）、目标（target 为 0 时取自己）。任一为空就 return，属于最后防线（`:966-972`）。
3. 扣副本：`count - 1`，`itemUseCounts[本人]++`（`:975-976`）。
4. 记消耗账：在 `settlements[本人].items_consumed` 里找第一条同 item id 的条目，找不到就追加；`count + 1`（`:978-991`）。**记在用药者名下**，给队友用药也一样。
5. 回血：`healed = heal_hp > 0 ? ApplyHeal(target, toDouble(heal_hp)) : 0`（`:994-996`）。`heal_hp` 是 uint64（`xm-table/src/main/proto/item_table.proto:28`）。
6. 回蓝：`heal_mp > 0` 且目标未死时（`:998-1004`）：
   - `after = minUnsigned(max_mana, before + heal_mp)`，加法按 uint64，可能回绕；
   - `restored = after - before`，`before > max_mana` 时回绕成巨大值；
   - 写回 mana。
7. 出 ITEM 事件（`:1007-1011`）：`source = 用药者`，`target = 实际目标`，带 `item_table_id`，`value = healed > 0 ? healed : restored`，以及两个 after 字段。
   满血时用药会出 value=0 的事件，药照样消耗。

### 6.3 道具余量与 `SelfItems`

- **余量只存在于引擎私有的开局请求副本里**（`engine.cpp:1680-1695`），`BattleActorState` 不承载道具。
- **`FindItemEntry`**（`:1680-1711`）：只看本人的那份快照，取第一个 id 相同且 `count > 0` 的条目。
- **`SelfItems(playerId)`**（`:1287-1306`）：
  - 取本人快照副本中 `count != 0` 的条目，按 item_table_id 升序排序；
  - C++ 用的是不稳定的 `std::sort`，同 id 多条时相对顺序不定。Java 用稳定排序（D6）；
  - 玩家不在本局时返回空；
  - 节点用它回填本人的 `self_items`（`room.cpp:1269-1281`）。
- **Java 的承载**：`LinkedHashMap<Long, List<BattleItemEntry.Builder>>`，按快照里玩家和道具的原顺序。原请求保持不可变。

### 6.4 击杀簿

- 见 §3.10。每条记录 count 恒为 1，按实际死亡转移的顺序追加（`engine.h:220`）。
- 毒和灼烧的击杀算在施加者名下（`test.cpp:1719-1752`）。

### 6.5 掉落 `RollDrops`（`engine.cpp:1196-1243`）

**执行时机**
- 每回合结算都会调用，在 `UpdateOutcome` 之后（`:662`）。
- 只在 `outcome == SIDE_A_WIN` 且击杀簿非空时才执行。
- 战斗结束后 `ResolveCurrentRound` 会提前返回（`:617-620`），所以**整局恰好执行一次**，并且是在本回合全部战斗随机数之后。

**遍历顺序（三层都固定）**
1. 遍历 `settlements`，按 player_id 升序。跳过不合格的人：actor 不存在、team ≠ 0、已逃、已死（`:1203-1208`）。
2. 遍历击杀簿，按击杀顺序。查不到怪物表行就跳过这一只（`:1209-1213`）。
3. 遍历 `MonsterTable.drop[]`，按槽序：
   - `drop_item`、`drop_count`、`drop_rate` 任一为 0，即视为空槽，**不掷骰**（`:1219-1221`）；
   - 非空槽**一律掷一次 `Rand01`**，`drop_rate ≥ 10000` 也照样掷（R6，`:1224-1227`）。当 `Rand01() × 10000.0 >= toDouble(drop_rate)` 时不掉；
   - 掉落时，在 `items_gained` 里找第一条同 item id 的条目，找不到就追加；`count += drop_count`（`:1228-1239`）。

**口径**
- 每人、每只怪、每个槽各掷一次：组队时人人独立掷骰，不做分赃（`:1222-1223`）。
- 击杀簿的 count 恒为 1，**不按 count 放大**（`:1214-1216`）。

### 6.6 `BuildSettlement(playerId)`（`engine.cpp:1570-1627`）

这个函数是 const，可以重复读，因为节点的 outbox 会重投（`test.cpp:1716`）。

1. 从 `settlements[playerId]` 拷贝一份（带上 `items_consumed`、`items_gained`）；没有就从空对象开始。
   Java 必须先 `build()` 出副本再修改，不能动存着的 builder。
2. 写入 `battle_id`、`player_id`、`outcome`（全局胜负，不换视角）、`total_rounds = CompletedRounds()`；然后 `clear_defeated_monsters`（`:1576-1581`）。
3. 玩家不在本局 → 返回到此为止的内容（`:1583-1586`）。
4. 写入 `player_team_index`、`health`、`mana`、`is_dead`、`fled`。
5. 该玩家名下的全部宝宝（`owner_player_id == playerId`，按 actors 插入序），不论死活，各追加一条 `pets{pet_id = 真实 pet_id, health, mana, is_dead}`（`:1594-1603`）。
6. 仅当 `SIDE_A_WIN`、本人 team 为 0、未逃、未死时（`:1611-1625`）：
   - 把击杀簿逐条拷进 `defeated_monsters`；
   - `exp_gain` / `gold_gain` 分别是各怪物表行 `exp_reward` / `gold_reward` 的 uint64 和。查不到行的怪物照样拷贝，但不计数值；
   - 组队时人人全额。
7. 不合格的玩家：经验、金币为 0；`defeated_monsters` 为空；`items_gained` 为空（RollDrops 没给他写）；`items_consumed` 照常保留。

节点在强制平局时会覆盖 `outcome` 与 `battle_id`（`room.cpp:1146-1149`）。

---

## 7 快照与输出

### 7.1 `BuildStateSnapshot`（`engine.cpp:1629-1656`）

- `battle_id`、`round_index = roundIndex`、`outcome`；`action_deadline_ms = 0`，由节点回填（`:1634-1635`）。
- `actors`：全部单位**按插入序整份拷贝**，包括死亡和逃跑的单位、全员冷却、buff。这是「全知」版本，下发前必须由节点裁剪（`engine.h:73-77`）。
  - Java 对每个单位调用 `builder.build()` 得到不可变副本，不能把可变 builder 交给调用方。
- `pending_actor_ids`：只在 ONGOING 时填。按插入序，取 PLAYER、存活未逃、非挂机、还没有 pending 的单位（`:1641-1654`）。
  结算刚结束时 pending 已清空，所以这里就是下一回合全部需要提交的玩家。
- `self_items` 不填，由节点按视角回填。

### 7.2 `TurnResultS2C`（`player_battle.proto:42-48`）

| 字段 | 引擎填 | 节点填 |
|---|---|---|
| battle_id | `createRequest.battle_id` | 再写一次（`room.cpp:1063`） |
| round_index | 本次结算的回合号 | — |
| events | 按结算顺序 | — |
| state | `BuildStateSnapshot()` | `action_deadline_ms`、`battle_id`（`room.cpp:1053-1064`） |
| action_order | **不填** | `LastActionOrder()`（`room.cpp:1065-1071`） |

### 7.3 事件字段表（`battle_data.proto:137-169`）

| 事件 | source | target | 其它字段 | 出处 |
|---|---|---|---|---|
| ATTACK 1 | 出手者 | 目标 | — | `engine.cpp:757` |
| SKILL 2 | 施法者 | 首目标 | skill_table_id | `:834-835` |
| DAMAGE 3（普攻） | 出手者 | 目标 | value = 实扣（可为 0）、is_critical、hp_after、mana_after | `:774-778` |
| DAMAGE 3（技能） | 施法者 | 目标 | 再加 skill_table_id | `:881-887` |
| BUFF_ADD 5 | 本次 casterId | 持有者 | buff_table_id；value = 层数（新建为 1，叠层为当前层数） | `:1453-1455`、`:1545-1547` |
| BUFF_REMOVE 6 | 条目的 caster_id | 持有者 | buff_table_id | `:1562-1563` |
| BUFF_TICK 7 | 条目的 caster_id | 持有者 | buff_table_id、value、hp_after、mana_after | `:1131-1136`、`:1151-1156` |
| DEATH 8 | 死者 | 死者 | — | `:1395` |
| DEFEND 9 | 自己 | 自己 | — | `:944` |
| ITEM 10 | 用药者 | 实际目标 | item_table_id、value、hp_after、mana_after | `:1007-1011` |
| FLEE 11 | 自己 | 自己 | success | `:1025-1026` |
| MANA 14 | 施法者 | 施法者 | skill_table_id、value = 实耗、hp_after = 施法者气血、mana_after | `:935-939` |
| MISS 12 | — | — | 有代码（`:761`、`:864`），但命中率 100，**不可达** | |
| HEAL 4 / BLOCK 13 | — | — | **从不产出** | |

所有事件都带 `group_id` / `hit_index`（§3.4）。

### 7.4 线上字节与 map

- `BattleEventItem` 全是标量字段（`battle_data.proto:155-169`）。`BattleSettlementData` 只有 repeated 消息，没有 map（`:185-202`）。
  proto3 按字段号顺序编码、省略零值，所以**同值的事件与结算，跨语言字节完全相同**（向量见 §13.6）。
- `BattleActorState.skill_cooldown_rounds` 是 `map<uint32,uint32>`（`battle_data.proto:105`）。缺省序列化时：
  - C++ 按哈希序输出；
  - Java 的 `MapField` 按插入序输出。

  所以快照做跨语言字节比对时，两边都要开确定性序列化。即使开了，键 ≥ 2^31 时 Java 按有符号排序、C++ 按无符号排序，仍会不同（技能 id 远小于这个值）。
  建议回放测试对快照做**结构化**比对。

---

## 8 随机数

### 8.1 算法与播种

- **生成器**：`std::mt19937_64`（`engine.h:226`），参数取标准值：
  - w=64，n=312，m=156，r=31；
  - a=`0xB5026F5AA96619E9`；
  - u=29，d=`0x5555555555555555`；s=17，b=`0x71D67FFFEDA60000`；t=37，c=`0xFFF7EEE000000000`；l=43；
  - f=`6364136223846793005`。
- **播种**：在 `Initialize` 里执行 `rng.seed(request.seed())`（`engine.cpp:70`）。
  即 `mt[0] = seed`，`mt[i] = f × (mt[i-1] ^ (mt[i-1] >> 62)) + i`，都按 2^64 取模。
  - 种子是 `CreateBattleRequest.seed`，完整的 uint64（`battle_node.proto:23`）。
  - 成员 rng 默认构造时的种子是 5489，但 `Initialize` 之前没有任何消耗点。
- **不用** `std::uniform_*_distribution`（`engine.h:209-213`、`engine.cpp:1809`）。
- **选型**：JDK 21 的 `RandomGenerator` 家族里没有 MT19937-64；commons-rng 远低于 2 万 star 的门槛（`AGENTS.md:29`）。所以自写，约 40 行，用 §13.1 的【标准】值钉住。
  这不算新增依赖，`docs/design/tech-stack.md` 不用登记，类注释写明出处即可。

### 8.2 两个原语

- **`RandIndex(count) = rng() % count`**（`engine.cpp:1808-1811`）：count 是 uint64，调用方保证大于 0；**count = 1 也会消耗一次**。
- **`Rand01() = (double)(rng() >> 11) × (1.0 / 9007199254740992.0)`**（`engine.cpp:1813-1816`）：结果等于 k / 2^53，计算精确，值域 [0, 1)。

### 8.3 Java 实现（按字面照写）

```java
/** std::mt19937_64 的逐位移植（C++ 标准 [rand.predef]）；next() 返回 uint64 位模式。 */
public final class MersenneTwister64 {
    private static final int NN = 312, MM = 156;
    private static final long MATRIX_A = 0xB5026F5AA96619E9L, UM = 0xFFFFFFFF80000000L, LM = 0x7FFFFFFFL;
    private final long[] mt = new long[NN];
    private int mti;

    public MersenneTwister64(long seed) {          // seed 按 uint64 位模式解释
        mt[0] = seed;
        for (int i = 1; i < NN; i++) {
            mt[i] = 6364136223846793005L * (mt[i - 1] ^ (mt[i - 1] >>> 62)) + i;
        }
        mti = NN;
    }

    public long next() {
        if (mti >= NN) {
            int i;
            for (i = 0; i < NN - MM; i++) {
                long x = (mt[i] & UM) | (mt[i + 1] & LM);
                mt[i] = mt[i + MM] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
            }
            for (; i < NN - 1; i++) {
                long x = (mt[i] & UM) | (mt[i + 1] & LM);
                mt[i] = mt[i + (MM - NN)] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
            }
            long x = (mt[NN - 1] & UM) | (mt[0] & LM);
            mt[NN - 1] = mt[MM - 1] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
            mti = 0;
        }
        long x = mt[mti++];
        x ^= (x >>> 29) & 0x5555555555555555L;
        x ^= (x << 17) & 0x71D67FFFEDA60000L;
        x ^= (x << 37) & 0xFFF7EEE000000000L;
        x ^= (x >>> 43);
        return x;
    }
}

final class BattleRandom {                          // 包私有；带抽数计数器，供测试核对 §8.4 的账
    private final MersenneTwister64 mt;
    private long draws;
    BattleRandom(long seed) { mt = new MersenneTwister64(seed); }
    long randIndex(long count) { draws++; return Long.remainderUnsigned(mt.next(), count); }   // 不能用 %
    double rand01() { draws++; return (double) (mt.next() >>> 11) * 0x1.0p-53; }             // 不能用 >>
    long draws() { return draws; }
}
```

`0x1.0p-53` 与 `1.0 / 9007199254740992.0` 是同一个 double。

### 8.4 全部消耗点（顺序即语义）

| # | 位置 | 何时消耗 | 次数 |
|---|---|---|---|
| R1 | `ExecuteAttack` 重选目标（`engine.cpp:745-750`） | 目标为 null、已死、已逃或**同队**（包括默认目标 0） | 1 次 RandIndex(候选数) |
| R2 | `ExecuteSkill` 单体重选（`:809-820`） | 目标为 null、已死或已逃。**同队目标不重选** | 1 次 RandIndex |
| R3 | `ExecuteSkill` 降级为普攻（`:789-798`） | 走 R1 | 同 R1 |
| R4 | `CalculateFinalDamage` 暴击（`:1337`） | 施法者暴击率 > 0，并且这次有伤害要算：普攻每次一次；技能逐目标各一次，且仅在 `baseDamage > 0` 时（`:873-878`） | 1 次 Rand01 |
| R5 | `ExecuteFlee`（`:1016-1023`） | PVE | 1 次 Rand01 |
| R6 | `RollDrops`（`:1224`） | 刚判出 SIDE_A_WIN 时，按「合格玩家（player_id 升序）× 击杀簿（击杀序）× 非空掉落槽（表序）」逐个掷 | 每槽 1 次 Rand01 |
| — | `RollHit`（`:1798-1800`） | 命中率 100，短路返回 | 0 |
| — | AOE 选目标（`:803-807`） | 按插入序取全部敌人 | 0 |
| — | `FillDefaultActions`、`BuildTurnOrder`、`SetActorAuto`、`ValidateAction`、buff 挂载与 tick、道具、防御 | — | 0 |

**单次出手内部的先后**
- 普攻：R1 → R4。
- 技能：R2 → 开冷却 → SKILL 事件 → MANA 事件 → 表达式求值一次 → 对每个目标依次：命中（不耗）→ R4 → 死亡 → effect buff。
- 降级普攻：R1 → R4。

**真表上的提醒**
- Monster 5–16 的 `critchance` 是 5–25，所以怪物普攻会掷暴击。
- 怪物 2 的掉落是 `[10×1@5000, 11×1@2000]`，掉落掷点会实际影响结果（§9.9）。

### 8.5 表达式里的 `random()`

- C++ 的 `random()` 走全局 `rand()`，不经过引擎 RNG，本身就破坏确定性（`expr.h:6-11`）。
- 当前数据里没有用到它，见 §9.9。
- **Java 要求（D4）**
  - 引擎的数据源只调用带 `RandomGenerator` 的重载，传入 `FORBID_RANDOM`：一个 `nextDouble()` / `nextLong()` 一被调用就抛 `IllegalStateException` 的实现。
  - 不能用缺省重载，那里用的是 `ThreadLocalRandom`。
  - 也不能把引擎 RNG 注入进去，否则会插进额外的消耗，平移整条序列。
  - 加一道加载期闸：构造 `TableBattleData` 时，发现战斗公式里调用了 `random()` 就抛 `TableLoadException`（§9.3）。

### 8.6 引擎内禁用的东西

引擎代码里不得出现：
- `Random`、`SplittableRandom`、`ThreadLocalRandom`、`Math.random`；
- `UUID.randomUUID`、`System.nanoTime` / `currentTimeMillis`；
- 依赖 `HashMap` / `HashSet` 迭代序、并且会影响结果的逻辑。

---

## 9 配表读取与指纹

### 9.1 数据源接口（`provider.h:20-53`）与生产实现

```java
public interface BattleData {
    Optional<SkillTable> skill(int skillTableId);                    // FindSkill            provider.h:26
    Optional<BuffTable> buff(int buffTableId);                       // FindBuff             :27
    Optional<SkillPermissionTable> skillPermission(int stateId);     // FindSkillPermission  :28（行 id = 战斗状态号，沉默 = 1）
    Optional<DungeonTable> dungeon(int dungeonTableId);              // FindDungeon          :29
    Optional<MonsterTable> monster(int monsterTableId);              // FindMonster          :30
    Optional<ItemTable> item(int itemTableId);                       // FindItem             :33
    long cooldownDurationMs(int cooldownTableId);                    // :36；缺行返回 0；uint64（无符号）
    List<Integer> dungeonMonsterIds(int dungeonTableId);             // :40；生产实现去掉 0，缺行返回空
    double skillDamage(int skillTableId, double casterLevel);        // :45
    double buffHealthRegeneration(int buffTableId, double level, double lostHealth);   // :49
    // 不移植 GetBuffBonusDamage（:52）：引擎里没有任何调用（D8）
}
```

| C++ 生产实现（`table_provider.cpp`） | `TableBattleData` 的写法 | 注意 |
|---|---|---|
| 各表 `FindByIdSilent(id).first`（:13-36） | `tables.skill().find(id)` 等（生成的 `SkillRows.java:67-69`；`ConfigTables.java:164-269`） | 一律用 `find`。`get` 遇到缺行会抛 `NoSuchElementException`（`SkillRows.java:71-78`） |
| `GetCooldownDurationMs`（:38-42） | `cooldown().find(id).map(r -> Integer.toUnsignedLong(r.getDuration())).orElse(0L)` | `duration` 是 uint32 毫秒（`xm-table/src/main/proto/cooldown_table.proto:21`） |
| `GetDungeonMonsterIds`（:44-59，只收 `monster(i) != 0`） | `getMonsterList()` 过滤掉 0，保持表内顺序 | |
| `GetSkillDamage`（:61-66，先 `SetDamageParam({level})` 再 `GetDamage`） | `find(id).map(r -> tables.skill().evalDamage(r, level, FORBID_RANDOM)).orElse(0.0)` | 行不存在时 C++ 返回 0.0（`cpp_config.h.j2:119-128`），但引擎只在查到行之后才调用。行对象必须来自同一份快照，否则抛 `IllegalArgumentException`（`SkillRows.java:86-96`） |
| `GetBuffHealthRegeneration`（:68-72，参数 `{level, lostHealth}`） | `tables.buff().evalHealthRegeneration(r, level, lostHealth, FORBID_RANDOM)`（生成的 `BuffRows.java:102-104`） | schema 里第二个参数叫 `health`（`buff_table.proto:92`），但引擎传的是**已损失气血**（`engine.cpp:1125-1128`），按位置传即可 |

### 9.2 测试用内存实现 `MemoryBattleData`（放在测试源码里）

逐项照 `mem.h:14-147` 实现：
- 行直接塞进内存。`addX(id)` 遇到已存在的 id 返回已有的那一行，用例靠这一点「改一列」（例：`test.cpp:1472`、`:1723`、`:1908`）。
- 三个表达式方法返回预设的定值，**忽略等级与损血参数**（`mem.h:117-128`）；没设过的返回 0.0。
- `dungeonMonsterIds` **不过滤 0**（`mem.h:112-115`）。

内存手搭的行做不到「属于某份快照」，所以不能走生成的 `evalXxx`。这与 C++ 单测的口径一致：引擎只关心求值结果。

### 9.3 表达式列

- **当前数据**（读 mmorpg `generated/tables/{skill,buff}.json`，与 `config-data/tables` 同批）：
  - `Skill.damage` 只有 `100*level`、`1000*level`、`10000*level`；
  - `Buff.health_regeneration` 只有 `0` 和 `0.013*level*health`（buff 17）；
  - **都不含 `random()`**。另外 `Buff.bonus_damage` 有一行 `66`（buff 19），引擎不读。
- **空串**：Java 编译成常量 0（`TableExpression.java:40-42`）。基础伤害为 0、负数或 NaN 时，经 `damageBeforeCritical` 都变成 0，即纯 buff 技能（`rules.h:67-68`）。
  基线对空串的返回值未定义，这一点已登记（`PARITY.md:53`）。
- **求值顺序**：`0.013*level*health` 在 Java 里是左结合的 `(0.013*level)*health`（`TableExpression.java:199-212`）。
  - 向量：`(10, 100) → 13.0`，`(85, 12130) → 13403.65`。
  - exprtk 是否保持同一运算顺序，没有核对（exprtk 源码不在本机稀疏检出里），见 §12.3 Q7。
- **`random()` 闸（D4）**
  - 构造 `TableBattleData` 时，遍历 `Skill.damage` 与 `Buff.health_regeneration` 的全部行，任一公式调用了 `random()` 就抛 `TableLoadException`。
  - 生成的 `SkillRows` / `BuffRows` **不暴露**每行的 `TableExpression`（`SkillRows.java:37`，私有）。所以只给 `TableExpression` 加一个 `usesRandom()` 不够，三种做法任选其一（§12.3 Q5）：
    - (a) 加 `usesRandom()`，在 `TableBattleData` 里按声明的参数名重新编译一遍再查；
    - (b) 对原串 `row.getDamage()` / `row.getHealthRegeneration()` 做大小写不敏感的正则 `\brandom\s*\(` 检查。函数名大小写不敏感、名字与括号之间允许空白（`TableExpression.java:250`、`:302`）；
    - (c) 改 codegen，暴露每行的表达式对象。
  - `Buff.bonus_damage` 引擎不读，不纳入检查。

### 9.4 表快照绑定

- **基线**：每次调用都读全局表管理器（`table_provider.cpp`）。热重载一旦接线，进行中的战斗会在中途读到新表；目前 `ReloadTables` 无人调用（`fp.h:17-19`）。
- **Java**：每个引擎实例在开局时绑定**同一份** `ConfigTables` 快照，整局不换。快照不可变、整体替换（`docs/design/config-tables.md:55`）。热更只影响之后新开的局（D3）。

### 9.5 指纹算法（`fp.cpp:34-86`；`fp.h:38`）

```
buffer = ""
for (name, table) in [("skill",Skill), ("buff",Buff), ("cooldown",Cooldown), ("skillpermission",SkillPermission),
                      ("dungeon",Dungeon), ("monster",Monster), ("item",Item)]:   // 顺序是契约，只能往尾部追加（fp.cpp:76-83）
    bytes = 确定性序列化(<Sheet>TableData{ repeated rows = 1 })      // map 按键排序（:42）；失败则 bytes = ""（:44-47）
    buffer += name + '\0' + uint64_be(len(bytes)) + bytes            // :49-56
return lowercase_hex(sha256(buffer))[0:32]                          // :85；sha256.cpp:136-148
```

- 行顺序就是表管理器里的数据顺序，即 Excel 表序（C++ 的 `FindAll()`，`fp.cpp:88-97`）。
- 缓存：`Current()` 首次调用时计算，`Refresh()` 重算（`fp.cpp:99-114`）。scene 与 battle 在表加载完成后各 Refresh 一次（`cpp/nodes/scene/main.cpp:56`；`cpp/nodes/battle/main.cpp:219`）。

### 9.6 Java 实现

```java
public static String compute(ConfigTables t) {
    return computeFrom(t.skill().all(), t.buff().all(), t.cooldown().all(), t.skillPermission().all(),
            t.dungeon().all(), t.monster().all(), t.item().all());
}
static String computeFrom(List<SkillTable> skill, List<BuffTable> buff, List<CooldownTable> cooldown,
        List<SkillPermissionTable> permission, List<DungeonTable> dungeon, List<MonsterTable> monster, List<ItemTable> item) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    section(buf, "skill", skill); section(buf, "buff", buff); section(buf, "cooldown", cooldown);
    section(buf, "skillpermission", permission); section(buf, "dungeon", dungeon);
    section(buf, "monster", monster); section(buf, "item", item);             // 表序是契约
    return HexFormat.of().formatHex(sha256(buf.toByteArray())).substring(0, 32);
}
private static void section(ByteArrayOutputStream buf, String name, List<? extends Message> rows) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(body);
    out.useDeterministicSerialization();                                     // map 按键排序
    for (Message row : rows) out.writeMessage(1, row);                       // ≡ 序列化外层 <Sheet>TableData
    out.flush();
    buf.writeBytes(name.getBytes(StandardCharsets.US_ASCII)); buf.write(0);
    long n = body.size(); for (int s = 56; s >= 0; s -= 8) buf.write((int) (n >>> s) & 0xFF);
    buf.writeBytes(body.toByteArray());
}
```

- Java 没有外层 `<Sheet>TableData` 类。`TableSource` 按字段 1 逐条解析（`xm-table/src/main/java/com/game/table/load/TableSource.java:35-36`），所以逐行写字段 1，得到的字节与序列化外层消息相同。
- 行里不会有未知字段，加载期就拒绝了（`TableSource.java:132-137`）。
- **按快照计算、挂在 `TableBattleData` 上**：构造时就算好，以便提前暴露问题。热更产生新快照时自然重算，不存在 C++「热重载后指纹与内存表脱节」的隐患（`fp.h:17-19`；D3）。
- 已核对：对当前 `config-data/tables`，Java 确定性重序列化后求出的指纹，与直接对七个 `.pb` 原始字节按同一格式求哈希的结果相同（§13.2）。

### 9.7 消费方（属于 6.2 / 6.3 / 6.4）

- **scene**：写进 `BattlePlayerSnapshot.table_fingerprint` 和 `PrepareBattleResponse.table_fingerprint`（`pb.cpp:1101`、`:1185`）。
- **match**：模式 off / warn / enforce，缺省 warn。全员非空且一致才透传；enforce 下不一致者出局（`go/match/etc/match_service.yaml:77-80`；`go/match/internal/config/config.go:82-90`）。
- **battle 节点 `CheckTableFingerprint`**（`room.cpp:406-451`）：
  - 模式取自 game_config.yaml 的 `battle_table_fingerprint_mode` 键，或环境变量 `BATTLE_TABLE_FINGERPRINT_MODE`，缺省 warn（`room.cpp:354-397`）；
  - 比对 request 和每份快照里的指纹，空值不比；
  - enforce 下不一致时回 1006，`parameters[0]` 写入 `"battle table fingerprint mismatch: node=… request=…"`（`room.cpp:525-533`）。
- Java 只需在 Java 节点之间自洽，因为不与 C++ 混部（`AGENTS.md` §1）。

### 9.8 跨语言的边角（只在想做跨语言比对时有意义）

- Java 确定性序列化对整数键的 map 按**有符号**排序，C++ 按无符号排序。只有 ≥ 2^31 的 uint32 键会不同；七张战斗表都只有 `map<string,bool>`（`buff_table.proto:66-70`），不涉及。
- 字符串键：Java 按 UTF-16 码元排序，C++ 按 UTF-8 字节排序。只有辅助平面字符与 U+E000–U+FFFF 范围内的字符混用时才会不同。现有 tag 全是 ASCII。

### 9.9 当前正式表下的实际行为

数据来自 `config-data/tables`（与 mmorpg `generated/tables` 字节相同）。正式表下玩家技能恒为 1 / 2 / 13（`PARITY.md:75`）。

**玩家技能**

| 技能 | skill_type | targeting_mode | 校验 | 出手 | 冷却 | 耗蓝 | 伤害 | effect |
|---|---|---|---|---|---|---|---|---|
| 1 | [1] | [1, 2] | 指向性：要求目标存在且存活，阵营不限 | **AOE**：全体存活敌方 | cd 1 = 500 ms → 1 回合，实际没有冷却 | 40（资源 2 的 20 忽略） | `100*level`，法术，倍率 1 | buff 1 ×8 |
| 2 | [1] | [2, 2] | AOE：只要 target ≠ 0，不查是否存在 | AOE | cd 0 → 无 | 0 | `100*level` | buff 1 ×8 |
| 13 | [1, 1] | [3] | 位号认不出：只要 target ≠ 0 | **单体**：目标存活就用它，**包括队友和自己**；无效时随机选一个敌方 | cd 5 = 2000 ms → 1 回合 | 0 | `10000*level` | 14, 16, 19, 20 |

- 技能 3–12 不是玩家技能。其中类型含 2 或 3 的（3、4、6、12）在黑名单里。

**buff 1**
- 瞬时 buff：duration 0，非无限。tag 是 {Control, Metal}，dispel_tag 与 immune_tag 都是 {Control, None, Wood}。
- 技能 1 / 2 每命中一个存活目标，产生 8 对 `BUFF_ADD(v1)` + `BUFF_REMOVE`，共 16 条事件，消耗 8 个实例号。
- 每次挂载前，都会驱掉目标身上 tag 含 Control、None 或 Wood 的现存 buff。

**技能 13 命中存活目标 T（施法者 C）时**
1. buff 14：类型 35，驱散 tag MovementSpeedReduction。纯驱散，没有 ADD 事件。
2. buff 16：类型 13，2 s → 1 回合，immune_tag MovementSpeedReduction。产出 `BUFF_ADD(C→T, 16)`。
3. buff 19：类型 36，5 s → 1 回合，interval 1 s。产出 `BUFF_ADD(C→T, 19)`。它的 target_sub_buff 20 挂到 C 身上：`BUFF_ADD(T→C, 20)`。
4. buff 20：类型 36，5 s → 1 回合。产出 `BUFF_ADD(C→T, 20)`。

这些条目都是 1 回合，当回合末 tick（类型 13、36 没有效果）后移除，各出一条 BUFF_REMOVE（source 为条目的 caster）。buff 20 的 tag 叫「Silence」，但类型是 36，**不是沉默**。

**其它数据现状**
- 表里没有类型 30 / 31 / 52 的 buff 行，快照又会剔除控制类，所以沉默、眩晕、冰冻在现有数据下**不可达**。
  若将来出现沉默：SkillPermission 第 1 行宽 6、全是 1000，技能 13（类型 [1,1]）会放行；类型 ≥ 6 的技能会得到 1002。
- 回血 buff 17：类型 42，无限，interval 1 s，`0.013*level*health`。它只能由快照带入，或作为 buff 18 的子 buff 挂上，而没有技能引用 buff 18。
- **道具**：10 回血 300，11 回蓝 120，其余 26 种都不能在战斗中使用。
- **掉落**：怪物 1 必掉物品 10×1（drop_rate 10000）。怪物 2 掉物品 10×1（5000，即 50%）和物品 11×1（2000）。其余怪物没有掉落。
- **副本**：1 → [1, 2]，2 → [6, 7]，3 → [11, 12, 16]；time_limit 1800 / 3600 / 1800 s（`tdp_test.cpp:94-100` 钉住了分组）。
- **怪物**：怪物 1–4 的暴击率为 0，5–16 为 5–25。所有怪物的 hp、str、speed、exp、gold 都 > 0（`tdp_test.cpp:103-129`）。
- 行数（skill / buff / cooldown / skillpermission / dungeon / monster / item）：13 / 20 / 9 / 3 / 3 / 16 / 28。

---

## 10 Java 落地映射

### 10.1 模块与依赖

| 项 | 取值 | 依据 |
|---|---|---|
| 模块 | 新建纯库模块 **`xm-battle-engine`**（jar） | `docs/porting/inventory/combat.md:167` |
| 包名 | **`com.game.battle.engine`**；6.2 的节点用 `com.game.battle.*` 下的其他子包 | `AGENTS.md:34`；先例 `xm-player-store` → `com.game.player.store` |
| 编译依赖 | `xm-proto`（战斗消息、`BaseAttributesComp`、`com.game.proto.match.MatchMode`）；`xm-table`（`ConfigTables`、行类、tip 枚举）；`xm-common`（`CombatDamageRules` 与新抽出的 `Unsigned`） | `xm-common/pom.xml:20` 已依赖 xm-proto |
| 不依赖 | Spring、Netty、Redis、日志框架 | 纯库（`engine.cpp:19-20`） |
| 测试依赖 | `spring-boot-starter-test`（test scope），与其他库模块一致 | `docs/design/tech-stack.md:27` |
| 新增第三方依赖 | 无。属性测试**不引入 jqwik**（star 远低于门槛），用 JUnit 参数化 + 测试侧随机场景生成 | `AGENTS.md:29` |
| 根 pom | `<modules>` 里加在 `xm-common` 之后、第一个进程模块之前（`pom.xml:15-37`） | 依赖方向单向（`docs/design/architecture.md:55`） |
| 消费方 | 6.2 battle 节点进程（模块名随 6.2 定）；6.3 `xm-scene`（只用 `BattleRules`、`BattleTableFingerprint`） | |

### 10.2 类清单

| Java 类（可见性） | 职责 | C++ 来源 |
|---|---|---|
| `TurnBattleEngine`（public final） | 一场战斗一个实例；API 见 §10.3 | `engine.h:27-237` |
| `BattleStart`（public sealed：`Started(engine)` / `Rejected(InitRejection, String detail)`） | 开局结果，替代 `bool Initialize` | `engine.cpp:42-115` |
| `InitRejection`（public enum） | 开局拒绝原因，节点据此打日志（§10.3） | 同上 |
| `BattleConstants`（public final） | 镜像 `constants.h` 全部常量 | `constants.h:15-148` |
| `BattleRules`（public final，纯静态） | `roundsFromMillis`、`roundsFromSeconds`、`isTurnBattleCastableSkill`、`skillManaCost`、`isAreaSkill`；6.3 的 scene 出快照时共用 | `constants.h:151-162`；`engine.cpp:482-493`、`:903-922` |
| `MersenneTwister64`（public final） | `std::mt19937_64` 的逐位移植 | §8.3 |
| `BattleRandom`（包私有） | `randIndex` / `rand01`，带抽数计数 | `engine.cpp:1808-1816` |
| `BattleData`（public interface）/ `TableBattleData`（public final） | 表数据供给；生产实现绑定一份 `ConfigTables` 快照，构造时做 random 闸并算好指纹 | `provider.h`；`table_provider.cpp` |
| `BattleTableFingerprint`（public final） | 指纹 | `fp.{h,cpp}` |
| `BattleUnit`（包私有） | 包着 `BattleActorState.Builder`，提供无符号访问器 | `engine.h:221` |
| `EventLog`（包私有） | `group_id` / `hit_index` 计数与 `append` | `engine.cpp:1772-1788` |
| `ActionChecks`、`BuffEngine`、`ItemLedger`（包私有） | 行动校验链；buff 增删与 tick；道具副本与消耗账。只是按职责拆文件，共用同一份状态与同一个 RNG，调用顺序严格照 C++ | `engine.cpp:386-602`、`:1037-1167`、`:1402-1564`、`:947-1012`、`:1680-1711` |
| `com.game.common.math.Unsigned`（xm-common，新增） | 无符号 long 与 double 互转（含饱和变体）、`minUnsigned`。`CombatDamageRules` 改为委托它，行为不变 | `CombatDamageRules.java:96-111` |

### 10.3 API

```java
package com.game.battle.engine;

public final class TurnBattleEngine {
    /** 校验 + 初始化 + 播种；失败不产生实例（D1）。data 不得为 null。 */
    public static BattleStart start(CreateBattleRequest request, BattleData data);

    public long battleId();
    /** 零副作用；返回 1000 或 tip（§2.2）。 */
    public int validateAction(long actorId, BattleAction action);
    /** 合法则落账（同回合内后到的合法提交覆盖先到的）；返回 allPlayersReady()（§2.1）。 */
    public boolean submitAction(long actorId, BattleAction action);
    /** 返回 1000 / 1005 / 1009（基线成功返回 0，见 D2）。 */
    public int setActorAuto(long actorId, boolean enabled);
    public boolean allPlayersReady();
    /** 结算一回合；action_order 不填，由节点从 lastActionOrder() 透传。 */
    public TurnResultS2C resolveCurrentRound();
    public List<Long> lastActionOrder();          // 不可变副本
    public eBattleOutcome outcome();
    /** 纯读，可重复调用，每次结果逐字节相同。 */
    public BattleSettlementData buildSettlement(long playerId);
    /** 全知快照：含所有人的冷却，节点下发前必须按收信人裁剪。 */
    public BattleStateS2C buildStateSnapshot();
    public List<BattleItemEntry> selfItems(long playerId);

    /** 测试钩子（包私有），对应 test.cpp:26-38 的友元：直接给单位挂 buff，产出的事件丢弃。 */
    boolean addBuffForTest(long targetId, int buffTableId, long sourceId);
}
```

`InitRejection` 的取值与对应位置（C++ 打 ERROR 的四处已标出）：

| 取值 | C++ 位置 |
|---|---|
| `MISSING_BATTLE_ID` | `engine.cpp:47` |
| `NO_PLAYERS` | `engine.cpp:47` |
| `TEAM_OVERSIZE`（ERROR） | `engine.cpp:59-66` |
| `INVALID_PLAYER`（player_id 为 0，或 team > 1） | `engine.cpp:119-121` |
| `RESERVED_PLAYER_ID`（ERROR） | `engine.cpp:124-128` |
| `DUPLICATE_PLAYER` | `engine.cpp:129-131` |
| `PET_ID_ZERO` | `engine.cpp:188-190` |
| `DUPLICATE_PET`（ERROR） | `engine.cpp:192-196` |
| `PET_OWNER_MISMATCH`（ERROR） | `engine.cpp:211-216` |
| `ONE_SIDED` | `engine.cpp:102-104` |

`detail` 带出 battle_id、team、人数、pet_id 等。

### 10.4 状态承载

- **单位**：`ArrayList<BattleUnit>`。每个 `BattleUnit` 持有一个 `BattleActorState.Builder`，作为权威状态。
  - 快照时调 `builder.build()`，不会漏掉 `appearance_id`、`stamina` 这类规则从不读的字段。
  - `findActor` 线性扫描，与 `engine.cpp:1662-1678` 相同。
  - 不违反「不写 ECS」约定：这是领域对象 + proto 载体，与 C++「宪法 §3」一致。
- **buff**：用 `getBuffsBuilderList()` / `removeBuffs(i)` / `addBuffs(...)` 操作。按实例号查下标。
- **冷却 map**：读用 `getSkillCooldownRoundsMap()`，写用 `putSkillCooldownRounds`。衰减时先拷贝出键值对，再逐个 put（§4.4）。
- **其余容器**：见 §1.1。道具账本见 §6.3。

### 10.5 C++ → Java 数值翻译表（实现与评审都按这张表查）

| C++ | Java | 用到的地方 |
|---|---|---|
| `uint64 a < b` | `Long.compareUnsigned(a, b) < 0` | 速度排序（`engine.cpp:699-701`）；actor_id 升序（`:702`）；`MaxAliveEnemySpeed`（`:1742`）；法力比较（`:499`、`:932`）；`std::min<uint64_t>`（`:1001`、`:1366-1367`）；`buff_id >= next`（`:155`） |
| `uint32 a < b` | `Integer.compareUnsigned` | 层数与 max_layer（`:1537`）；`elapsed` / `ticksDone`（`:1080-1090`）；`roundIndex >= maxRounds`（`:1190`）；SkillPermission 列下标（`:576`）；`SelfItems` 排序（`:1303`） |
| `static_cast<double>(uint64)` | `Unsigned.toDouble(x)` | 速度差（`:1018-1019`）；损血（`:1125-1126`）；暴击率（`:1325`）；`healthBefore + heal`（`:1368`）；道具回血量（`:995`）；drop_rate（`:1225`） |
| `static_cast<double>(uint32)` | `(double) Integer.toUnsignedLong(x)` | 等级（`:844`、`:1128`）；层数（`:1148`） |
| `static_cast<uint64_t>(double)` | `Unsigned.fromDouble(d)`；越界与 NaN 的口径见 D5 | `ApplyHeal`（`:1368`）；`RoundsFromSeconds`（`constants.h:161`） |
| `uint64 % n` | `Long.remainderUnsigned` | `RandIndex`（`:1810`） |
| `(ms + 5999) / 6000` | `Long.divideUnsigned(ms + 5999, 6000)`，加法按 64 位回绕 | `constants.h:152` |
| `static_cast<uint32_t>(uint64 rounds)` | `(int) rounds`，取低 32 位 | `constants.h:153` |
| `uint64 a - b`（回绕） | `a - b`，补码位模式相同 | 损血（`:1126`）；`healed`（`:1370`）；`restoredMana`（`:1002`）；伤害后的气血（`:1357`） |
| `std::max(1u, layer)` | `layer == 0 ? 1 : layer`（无符号语义） | 周期伤害（`:1148`） |
| `std::clamp` / `std::max(x, 0.0)` | 照写三元表达式，不用 `Math.clamp` / `Math.max` | 逃跑（`:1020-1021`）；暴击率（`:1324-1325`）；`:1342` |
| `1u << modeBit` | `1 << modeBit` | `:515`、`:906`（§4.3 第 4 条） |
| `static_cast<int32_t>(bit)` 当下标 | `Integer.compareUnsigned(bit, size) >= 0 → 1002` | `:575-578`（D5） |
| proto3 枚举的未知值 | 用 `getXxxValue()` 的 int 做 switch | `action_type`（`:392`、`:720`） |
| 浮点运算 | 普通 `double`，禁止 `Math.fma`，不重排运算顺序 | 全部 |

### 10.6 线程模型

- 引擎**不是线程安全的**，与 C++ 一样是单线程对象。由 6.2 的房间把它限定在单个串行执行上下文里（`AGENTS.md:36` 的线程所有权纪律）。
- 引擎内部没有阻塞 I/O、时钟和日志。
- `BattleData` / `ConfigTables` 是不可变快照，可以跨线程共享。

### 10.7 需要改动的既有模块（小改动）

- **xm-common**：新增 `com.game.common.math.Unsigned`：
  - `toDouble(long)`：照 `CombatDamageRules.java:97-102`；
  - `fromDouble(double)`：照 `:105-111`，前置条件 `0 ≤ d < 2^64`；
  - 饱和变体 `fromDoubleSaturating`：NaN → 0，≥ 2^64 → UINT64_MAX；
  - `minUnsigned`。

  `CombatDamageRules` 改为委托它，现有 `CombatDamageRulesTest` 必须仍然全绿。也可以只把这两个方法改成 public（§12.3 Q6）。
- **xm-table**（手写运行时，不是同步产物，可以改）：按 §9.3 选定的 random 检测方式改动，或者不改。

### 10.8 文档与 PARITY 交付

- **PARITY.md**：
  - 新增两行：「回合制战斗确定性引擎（纯库）」「战斗配表指纹」。基线路径即本稿开头列出的文件，模块 `xm-battle-engine`。状态写「已对齐（有意差异见 battle-engine-spec §12）」，跨语言金样未落地前注明「跨语言金样待 mmorpg」。
  - 更新三行：`PARITY.md:53`（表达式列）和 `:77`（伤害公式）补「6.1 消费方已接入」；`:80`（实时 buff）补「回合制 buff 镜像随 6.1 已移植」。
- **docs/design/architecture.md**：§2 模块表加一行（库：回合制战斗确定性引擎、战斗配表指纹），并调整依赖方向（`:29-55`）。
- **docs/porting/roadmap.md:80**：完成时标 ✅ 与 commit。
- **mmorpg 待做**（登记为「mmorpg 待做」，需用户同意改 mmorpg）：
  - 金样导出 gtest，以及 `MakeTables` 指纹断言（§13.9）；
  - 可选：把 scene 与引擎的可施放过滤合成一处（D7）；
  - §12.2 中选定要修的基线问题。

---

## 11 隐患与边界

### 11.1 基线行为（Java 照搬，建议在 PARITY 备注；mmorpg 侧是否修由用户定）

1. **单体技能可以打队友或自己**（`engine.cpp:505-533`、`:808-822`）。真表技能 13 的伤害是 `10000*level`，组队 PVE 里对队友施放会把队友秒掉。这是客户端可见的友伤（N1）。
2. **眩晕或冰冻中的手动玩家无法提交任何行动**，房间只能等满 6 s 才结算（§2.5）。
3. **不合法的重提交不会清掉旧行动**：客户端收到错误 tip，但先前排队的行动仍会执行（§2.1）。
4. **技能 1 按指向性校验、按 AOE 结算**；名义目标死后，整个技能降级为普攻（§4.3）。
5. **无敌(32)、免疫(34)、回蓝(41) 没有效果**；`GetBuffBonusDamage` 没有调用方；HEAL / BLOCK 事件从不产出；MISS 不可达（§5.4、§7.3）。
6. **PVP 打满回合也判进攻方负**（§3.11）。
7. **宝宝的技能不过滤，也永远只普攻**（§4.1）。
8. **气血或法力超过上限时，回血 / 回蓝量回绕成巨大的 uint64**（§3.7、§6.2）：
   - 事件 value 是这个巨大值，当前值被拉低到上限；
   - 只有快照带进来的当前值超过上限时才会出现。scene 出快照时只在上限缺失时回落，不夹当前值（`pb.cpp:930-941`），引擎也不夹（§1.5）。
9. **满血时用药**也扣一个药，并出 value=0 的事件（§6.2）。
10. **`CheckPlayerLevel` 是桩**（`engine.cpp:552-558`）。
11. **无限 buff 的 `interval_count` 按全局回合计数**；叠层会刷新时长，但不触发子 buff（§5.3、§5.5）。
12. **「0 血但活着」的快照单位**：被打一下就判死，即使伤害为 0（§3.6）。被周期伤害 tick 到时也一样（§5.6）。
13. **`RoundsFromMilliseconds` 的截断**：结果 ≥ 2^32 时截成 0。若出现在新建 buff 的剩余回合上，0 会被当成「无限」（§0.4）。现有数据远够不着。

### 11.2 C++ UB / 回绕点（Java 口径见 D5；现有数据都触发不到）

- `RoundsFromSeconds` 与 `ApplyHeal` 对 NaN、无穷、≥ 2^64 的值做 `static_cast<uint64_t>`（`constants.h:161`；`engine.cpp:1368`）。
- 周期回合数被截成 0 时执行 `% 0`（`engine.cpp:1086`）。在 x86 上，无符号除以 0 会触发 SIGFPE。
- `CheckBuff` 的 int32 负下标（`engine.cpp:575-579`）。
- 位号 ≥ 32 时的 `1u << bit`（`engine.cpp:515`、`:906`）。Java 与 x86 运行时行为一致，不登记。
- `buff_id == UINT64_MAX` 时，`next` 回绕为 0（§1.5）。照搬即可，不是 UB。

### 11.3 Java 移植时会踩的

1. **无符号比较**：出手序的 speed 与 actor_id（§3.3，G-TIE）、实例号计数器、法力、层数。
2. **无符号取模与移位**：`RandIndex` 必须用 `Long.remainderUnsigned`（seed 7 的第 1 抽用 `%` 得 -1，用 `Math.floorMod` 得 2，正确值是 0）；`Rand01` 必须用 `>>>`。
3. **uint64 换 double** 一律用 `Unsigned.toDouble`（§10.5）。
4. **不融合乘加，不重排运算顺序**（§0.6）。
5. **有序容器**：`actors`、击杀簿用 `ArrayList`；`settlements` 用无符号 `TreeMap`。顺序敏感的路径不许遍历 `HashMap`。
6. **冷却 map 里值为 0 的条目要保留**（§4.4）。
7. **快照、结算都要深拷贝**：对 builder 调 `build()` 后再交出去（§7.1、§6.6）。
8. **`AddBuffToActor` 的递归**之后，一律按实例号重新查找下标，不持有 builder 引用（§5.2）。
9. **事件分组**：被跳过、落空的出手也要先开组（§3.4）。
10. **表达式**：只用带 `RandomGenerator` 的重载，传入 `FORBID_RANDOM`；行对象必须来自同一份快照（§9.1）。
11. **不复用 xm-scene 的 `SkillRules` / `SkillTables`**：缺行时的回码（1001 对 1002）、状态集合、目标语义都不同，而且引擎是纯库，不应依赖 xm-scene。
12. **`ValidateAction` 与 `SubmitAction` 必须走同一份校验代码**，否则会出现「客户端收到成功、实际没落账」（`engine.h:43-46`）。

### 11.4 边界速查

| 场景 | 结果 | 出处 |
|---|---|---|
| 结束后再调 `ResolveCurrentRound` | 只有 battle_id、round_index、state；无事件；不耗 RNG | `engine.cpp:617-620` |
| 结束后 `SubmitAction` / `ValidateAction` / `SetActorAuto` | false / 1005 / 1005 | `:316`、`:335`、`:351` |
| 对宝宝 / 怪物 / 不存在的 id 提交 | 不收，返回就绪态 | `:321-330` |
| PVP 提交 FLEE | 1005；到时走默认普攻 | `:397-400`；`test.cpp:642-658` |
| 普攻指向队友 | 提交通过；出手时重选，耗 RNG | `:393-396`、`:745` |
| 道具指向敌方 / 不存在 / 已死 | 7002 / 7001 / 7001；出手时若失效，改为对自己用 | `:470-478`、`:958-964` |
| 技能 1 的名义目标先死 | 降级为普攻 | `:789-792` |
| 毒杀怪物时施毒者已死 | 照样记击杀 | `:1382-1383` |
| 两方在同一回合 tick 中都死光 | DRAW | `:1184-1185`；`test.cpp:548-573` |
| A 方玩家全灭只剩宝宝 | 继续；宝宝打赢判 A 胜，阵亡玩家无奖励 | `:1758-1765`、`:1611-1612` |
| 兜底怪被杀 | 不记击杀、不掉落、无经验 | `:1385` |
| 表行存在但 health 为 0 的怪 | 用默认属性；击杀记账；经验金币取表值（可能为 0） | `:278`、`:1385` |
| 回合上限 | `roundIndex >= maxRounds` 时判 B 胜；节点的整场期限通常更早触发并盖 DRAW | `:1190`；`room.cpp:559-565` |

### 11.5 对三份分区稿的勘误

1. 技能分区稿把 `Buff.health_regeneration` 写成 `buff_table.proto:179`：实际在 `:92`（本文件只有约 100 行）。
2. 技能分区稿说 `fp_test.cpp` 有四个用例，Java 分区稿说五个：实际是**四个**（`fp_test.cpp:87`、`:99`、`:112`、`:162`）。第五个「map 插入顺序不影响结果」是 Java 侧可以追加的用例，C++ 只在夹具注释里提到（`fp_test.cpp:55`）。
3. 核心分区稿把 FP 模型的出处写成 `turn_battle_engine_test.vcxproj:33`：那一行是 `PlatformToolset v145`，该工程并不设 `FloatingPointModel`，用的是 MSVC 缺省 `/fp:precise`。显式写 Precise 的是 `core.vcxproj:220`。结论（不会融合乘加）不变，见 §0.6。
4. Java 分区稿只建议给 `TableExpression` 加 `usesRandom()`：生成的 `SkillRows` / `BuffRows` 不暴露每行的表达式对象（`SkillRows.java:37`），光加这个方法用不上，需要另选一种接法（§9.3、§12.3 Q5）。
5. **分歧裁定**：
   - 挂机开关的成功码，核心稿主张照搬 0，Java 稿主张 1000。本稿取 1000（D2，§12.3 Q3）。
   - 模块 / 包名，核心稿留待编者定。本稿取 `xm-battle-engine` / `com.game.battle.engine`（§10.1）。
   - 单位状态的承载，核心稿说可以用领域对象 + `toProto()`，Java 稿主张包 builder。本稿取 builder（§10.4），理由是快照字段保真。
6. 技能分区稿写「怪物 2 掉物品 10×1（5000 万分比）」：drop_rate 是万分比，5000 即 50%。
7. 核心分区稿说 scene「max 气血 / 法力缺失时回落」，没有写回落值：scene 回落到 `max(当前, 1)`（`pb.cpp:932-940`）；引擎的 `FallbackMax` 回落到当前值，可能为 0（`engine.cpp:27-29`）。
8. Java 分区稿说 Buff 公式只有 `0`、`0.013*level*health`、`66`：`66` 是 buff 19 的 `bonus_damage` 列，不是 `health_regeneration`。
9. 技能分区稿写技能 13 的 buff 19 / 20「1 回合」，但没写出处：两者 duration 都是 5 s，`RoundsFromSeconds(5) = 1`；buff 16 是 2 s → 1。核对过真表 JSON。
10. 三份稿都没写，本稿补充：
    - 技能 13 对队友施放会造成真实的友伤秒杀（§11.1 第 1 条）；
    - AOE 技能没有可打目标、道具落空时同样占组号（§3.4）；
    - 表行存在但 health 为 0 的怪，击杀要记账（§1.7）；
    - 冷却 map 遍历时 Java 不能边迭代边改（§4.4）。

### 11.6 对 `docs/porting/inventory/combat.md` 的勘误

- **:166「the latest submission before resolve wins; invalid ones are not recorded」**：准确说法是**最后一次合法提交生效**，不合法的后续提交不会清掉已收下的行动（§2.1）。
- **:166「Units stunned or frozen mid-round lose their action without an event」**：补充：被跳过的出手**仍占一个 group_id**（§3.4）。
- **:166「records the kill only for a team-0 player or pet killing a team-1 monster」**：补充三点：
  - 还要求 `monster_table_id != 0`，兜底怪不记；
  - 宝宝的主人必须是 team 0 的 PLAYER；
  - DEATH 事件的 source = target = 死者；死亡清空 buff 时不发 BUFF_REMOVE（§3.10）。
- **:166「Snapshot buffs are sanitized …」**：补充：表行缺失的 buff 也丢；`nextBuffInstanceId` 在清洗**之前**就已按全部快照 buff_id 推高（§1.5）。
- **:166「DEFEND halves damage until round end」**：补充：从防御者**出手那一刻**才生效，速度更快的敌人打出的伤害不减半（§3.8）。
- **:171「An exact mt19937_64 port is needed only for cross-language replay parity」**：要拿 C++ 金样验收 Java，精确移植是唯一手段，而且只需约 40 行（§8.3）。建议照做。
- **:171 新增风险点**：怪物、宝宝的 actor_id 在 Java 里是负数，出手序同速平手必须用无符号比较（§3.3）。
- **:178「Silenced → SkillPermission[1].skill_type[bit]; a cell of 0 → 1002」**：补充：许可行缺失、类型位号越界也是 1002（与实时侧缺行回 1001 不同）。
- **:178 补充**：
  - AOE 判定与校验不同源（技能 1，§4.3）；
  - 子 buff 是「depth > 8 截断」，深度 0..8 共 9 层都生效；
  - 周期伤害是 `interval_effect[0] × max(1, layer)`，防御中减半，不乘 PvP 系数；
  - 有 buff 但本回合没有任何事件的单位，回合末同样占一组。
- **:190「Drops are rolled … with drop_rate/10000」**：补充：**每个非空槽都掷骰**，`drop_rate ≥ 10000` 也一样（§6.5）。
- **:207「Java protobuf deterministic serialization may not byte-match C++ for map fields」**：对现有数据不成立。七张表的 map 只有 `map<string,bool>`，Java 确定性重序列化与导表器字节一致，空表指纹与语言无关。只有 §9.8 列出的两种边角才会不同（向量见 §13.2）。

---

## 12 建议的有意差异

### 12.1 建议采纳

| 编号 | 差异 | 基线 | Java | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|---|
| D1 | 开局 API | `bool Initialize` + `initialized` 一次性标志；失败后残留半填状态；引擎内打 4 处 ERROR（`engine.cpp:42-115`） | 工厂 `start(...)` 返回 `BattleStart`，失败带 `InitRejection`，不产生实例；引擎不打日志，由节点打 | 否（节点照样回 1002） | 否 |
| D2 | 挂机开关的成功码 | 返回 **0**，与 tip 不同域；节点专门注释过这个坑（`room.cpp:1002-1004`） | 返回 1000，与 `validateAction` 同域；节点成功时不填 `error_message` | 否 | 否 |
| D3 | 表快照绑定 | 每次读全局表管理器；指纹进程内缓存，靠 `Refresh` 保持同步（`fp.cpp:99-114`） | 每局绑定开局时的 `ConfigTables` 快照；指纹按快照计算、随快照替换 | 否（基线也没有热更） | 否 |
| D4 | 战斗公式里的 `random()` | exprtk 的 `random()` 走 C 的 `rand()`，破坏确定性（`expr.h:6-11`） | 构造 `TableBattleData` 时拒绝（`TableLoadException`）；求值时传入的随机源一被调用就抛异常 | 否（现有数据不含） | 建议 mmorpg 导表期也拒绝（N11） |
| D5 | C++ UB 改为有定义（现有数据都触达不到） | 见 §11.2 | ① `roundsFromSeconds`：`!(s > 0)`（含 NaN）→ 1；`s × 1000 ≥ 2^64`（含 +∞）→ 毫秒数按 UINT64_MAX 处理，结果为 1。② `ApplyHeal`：非有限 raw → 0（不回血、不出事件）；和 ≥ 2^64 → 饱和到 UINT64_MAX 再与上限取小。③ 周期回合数为 0 → 不 tick（不抛异常），递减照常。④ SkillPermission 位号 ≥ 2^31 → 1002，同 xm-scene（`SkillRules.java:82-84`） | 否 | 否 |
| D6 | `SelfItems` 排序 | 不稳定的 `std::sort` | 稳定排序，同 id 时保持快照顺序 | 否（scene 已按 id 合并，`pb.cpp:1059`） | 否 |
| D7 | 可施放技能过滤 | 引擎与 scene 两处各写一份（`engine.h:115`；`pb.cpp:973-988`） | 都调用 `BattleRules.isTurnBattleCastableSkill` | 否 | 可选（mmorpg 合并一处） |
| D8 | `BattleData` 没有 `bonus_damage` | 接口有，引擎不调用（`provider.h:52`） | 不移植 | 否 | 否 |

**不是差异**：其余行为全部逐位照搬，包括 §11.1 列出的基线怪行为。理由是要与 C++ 金样逐字节一致。如需修改，先改 mmorpg，再两版同批改。

### 12.2 列出但不建议 Java 单方面改（需两版同改；建议登记「mmorpg 待做（可选）」）

- **N1**：单体技能加阵营检查，禁止对队友或自己施放伤害技能（§11.1 第 1 条）。客户端可见；会改变真表技能 13 的行为。
- **N2**：眩晕、冰冻中的手动玩家允许提交，或者让节点视其为就绪（§11.1 第 2 条）。
- **N3**：非法的重提交清掉旧行动，或者回包里说明旧行动仍有效（§11.1 第 3 条）。
- **N4**：技能 1 的校验与结算同源，例如按 `IsAreaSkill` 跳过目标校验（§11.1 第 4 条）。
- **N5**：让无敌、免疫、回蓝生效；或者从表与常量里删掉这三种类型（§11.1 第 5 条）。
- **N6**：PVP 打满回合改判平局（§11.1 第 6 条）。
- **N7**：回血、回蓝前先把当前值夹到上限，消除回绕（§11.1 第 8 条）。
- **N8**：`interval_count` 对无限 buff 改用「自身经过回合数」计数（§11.1 第 11 条）。
- **N9**：宝宝技能过滤与使用：二期设计。
- **N10**：C++ `Initialize` 失败后禁止重调。Java 已经用 D1 消除了这个问题，基线可以顺手修。
- **N11**：mmorpg 导表器拒绝战斗公式里的 `random()`，与 D4 对齐。

### 12.3 待用户拍板 / 开放问题

1. **Q1：派生金样要不要用 C++ 实跑核对？**
   §13.5 的【复核】【派生】轨迹在 C++ 里没有跑过。推荐在 mmorpg 加一个导出金样的 gtest（§13.9），把事件 hex 与结算 hex 导成夹具，确认后冻结成 Java 回放测试。这需要改 mmorpg，按 mmorpg 自己的规则提交（`AGENTS.md` §1、§4）。
   在此之前，Java 金样只起回归作用，PARITY 注明「跨语言金样待 mmorpg」。
2. **Q2：金样夹具怎么进 Java 仓库？**
   方案 A：由 `tools/ContractSync.java` 新增一个「测试夹具」类别同步过来，`--check` 一并覆盖，不许手改（与契约同一纪律）。方案 B：人工拷贝，在文件头记录源 commit。推荐 A。
3. **Q3：D2 挂机开关返回 1000 还是照搬 0？**
   推荐 1000：内部 API 统一 tip 域，基线节点里专门注释过这个坑（`room.cpp:1002-1004`）；客户端看不出差别。
4. **Q4：模块名、包名**：`xm-battle-engine` / `com.game.battle.engine`（推荐），还是并入 6.2 的进程模块？
   推荐独立库：6.3 的 scene 也要用 `BattleRules` 与指纹，不应依赖战斗进程。
5. **Q5：`random()` 检测的接法**（§9.3 的 a / b / c）。
   推荐 (b) 正则：不改 xm-table 与 codegen，判定口径与 `TableExpression` 的词法一致。若希望语法级精确，选 (a)。
6. **Q6：无符号工具放哪？**
   新建 `com.game.common.math.Unsigned`、`CombatDamageRules` 改为委托（推荐），还是只把 `CombatDamageRules` 的两个方法改成 public。
7. **Q7：exprtk 是否改写 `0.013*level*health` 的运算顺序？**
   若 exprtk 把 `(c*v0)*v1` 优化成 `c*(v0*v1)`，个别输入下两版会差 1 ulp，进而在 `ApplyHeal` 的截断边界上差 1 点气血。
   现有数据只有快照带入或子 buff 才能触发 buff 17，Java 玩家身上又永远没有快照 buff。建议在 Q1 的 C++ 金样里加一组 buff 17 的边界值。
8. **Q8：§12.2 的 N1–N11 哪些登记为「mmorpg 待做」？**
   推荐至少登记 N1（真表可达的友伤秒杀）与 N11。

---

## 13 测试计划

**命令**
- `./mvnw -B -pl xm-battle-engine -am test`。
- 改了 xm-common（`Unsigned`）之后：`./mvnw -B -pl xm-common,xm-battle-engine -am test`，`CombatDamageRulesTest` 必须仍然全绿。
- 同步契约之后照 `AGENTS.md` §4 做 `clean install`。

测试方法名用中文，与 `CombatDamageRulesTest` 一致。

### 13.1 RNG 向量（`MersenneTwister64Test` / `BattleRandomTest`）

- 【标准】默认种子 5489：第 1 个输出 `14514284786278117030`，**第 10000 个输出 `9981545732273789042`**。后者是 C++ 标准 [rand.predef] 规定的校验值。
- 【复核】下表的值由两个独立写成的 Java 实现分别算出，结果一致，并且都通过了上面的标准值：
  - #N 是播种后第 N 次 `next()` 的无符号值；
  - `Rand01#1`、`RandIndex(k)#1` 都基于第 1 抽。

| seed | #1 | #2 | #3 | #4 | Rand01#1 | RandIndex(2)#1 | RandIndex(3)#1 |
|---|---|---|---|---|---|---|---|
| 0 | 2947667278772165694 | 18301848765998365067 | 729919693006235833 | | 0.1597933633704608 | 0 | 0 |
| 1 | 2469588189546311528 | 2516265689700432462 | 8323445853463659930 | 387828560950575246 | 0.13387664401253263 | 0 | 2 |
| 7 | 13915952638675311015 | 17511516338625233250 | 2165911192842364878 | 16452894106784333046 | 0.754385304152858 | 1 | **0** |
| 21 | 5263790498402004422 | 11409222558240126990 | 8042491938685489068 | | 0.2853506546938004 | 0 | 2 |
| 33 | 5141232079998836263 | 4479202188031934533 | 12050777948115226479 | | 0.27870674951934526 | 1 | 1 |
| 42 | 13930160852258120406 | 11788048577503494824 | 13874630024467741450 | 2513787319205155662 | 0.755155532954539 | 0 | 0 |
| 1234 | 17473339210090333472 | 963351229459618018 | 17972999874122035550 | | 0.9472316166078043 | 0 | 2 |
| 20260815 | 5760554920487847109 | 17868157564151766807 | 8699233025562771549 | 8455420907625438291 | 0.31228030797574924 | 1 | 1 |
| 20260831 | 7339334672052659764 | 18022099805066839321 | 15070275510894644899 | | 0.39786612980188396 | 0 | 1 |
| 2^64−1 | 478026398904862820 | 13243134898385798468 | 709236020254955927 | | 0.025913863009903726 | 0 | 2 |

- 后续几抽的 Rand01（【复核】）：
  - seed 1：#2–#4 为 0.13640703636619722 / 0.4512149038445381 / 0.02102422841672702；
  - seed 7：#2–#4 为 0.9493012028926442 / 0.11741428103451801 / 0.8919131767124763；
  - seed 42：#2–#4 为 0.6390313938546974 / 0.7521452007480266 / 0.13627268363243705；
  - seed 20260815：#2–#4 为 0.9686347624683321 / 0.4715863672636402 / 0.45836928586634273。
- **无符号取模**：seed 7 的 #1 超过 2^63，`randIndex(3)` 必须为 0（有符号 `%` 得 -1，`Math.floorMod` 得 2）。seed 42、1234 的 #1 也超过 2^63。
- **抽数计数**：用 `BattleRandom.draws()` 逐类钉住 §8.4 的账，例如 R1 只有 1 个候选时也计 1 次。
- **逃跑概率**【复核】：
  - 600 对 60：恰好 0.95（`(0.01/12.0) × 540` 恰为 0.45）；
  - 1440 对 12：1.6900000000000002，夹到 0.95；
  - 120 对 60：0.55；120 对 240：0.4；60 对 60：0.5；0 对 1200：夹到 0.05。

### 13.2 指纹向量（`BattleTableFingerprintTest`）

| 输入 | 指纹 | 级别 |
|---|---|---|
| 七张空表 | `fad2935e5c749630621c18c616048e32` | 【复核】与语言无关：每段是表名 + NUL + 8 个零字节；已用 `printf` + `sha256sum` 独立算出，C++ 必然同值 |
| `fp_test.cpp:39-85` 的 `MakeTables()` | `11ca1a2d9b625cceb3ce199148d03133` | 【派生】Java 计算；需要在 mmorpg 的 `fp_test.cpp` 加一条断言确认（§13.9） |
| 只有 skill{id 1} / 只有 buff{id 1}（`fp_test.cpp:166-170`） | `e1c97a64990a49b717cf32748fe0e84f` / `4635b07869db163d304ed141dba96c19` | 【派生】 |
| 当前 `config-data/tables` | `9382fd045e8ceacd121d06e514bf3b13` | 【复核】Java 重序列化的结果 = 直接对七个 `.pb` 原始字节按同一格式求哈希的结果。**不要钉进单测**，每次同步表都会变；可与 mmorpg scene 启动日志里的指纹对照（`cpp/nodes/scene/main.cpp:56`） |

- **逐条移植 `fp_test.cpp` 的四个用例**：
  1. 同表同值，且重复计算稳定（`:87-97`）；
  2. 输出 32 位小写 hex；空表有确定值，且与非空不同（`:99-110`）；
  3. 七张表任一字段变化都会改变指纹，覆盖 9 种改法：skill 的 cooldown_id、item 的 heal_hp、monster 掉落槽、buff 的 max_layer、buff 的 tag 加键、cooldown 的 duration、permission 的 id、dungeon 追加怪物、monster 新增行（`:112-160`）；
  4. 只有 skill 一行 ≠ 只有 buff 一行（`:162-171`）。
- **Java 追加**：
  - map 插入顺序不影响结果；
  - 上表前三个向量；
  - 可选：「对加载后的 `ConfigTables` 计算的指纹 = 对原始 `.pb` 字节计算的指纹」。这条依赖导表器输出是规范编码，失败时先查导表器。

### 13.3 规则与工具单测

- **`BattleRulesTest`**：
  - 毫秒换回合：0→1，6000→1，6001→2，12000→2，500→1，2000→1；
  - 截断边界：`6000 × 2^32 → 0`（截成 uint32 后为 0，必须复现）；`2^64−1 → 1`（加法回绕后为 0，再取下限 1）；
  - 秒换回合：0→1，−1→1，0.0005→1，2.0→1，5.0→1，6.0→1，7.0→2，12.0→2，12.5→3，1800→300；NaN→1、+∞→1（D5）；
  - 可施放：[0]、[2]、[3]、[5,3]、[11,3] 不可；[]、[1]、[1,1]、[4]、[6]…[10] 可；
  - `isAreaSkill`：[1,2] 与 [2,2] 为真，[3]、[1,1]、[] 为假；
  - 法力消耗：只认资源 1，真表技能 1 的 `[1:40, 2:20, 0:0, 0:0]` → 40。
- **`UnsignedTest`**（xm-common）：
  - `toDouble(-1L) = 1.8446744073709552E19`；
  - `fromDouble` 在 2^63 附近的值；
  - 饱和变体对 NaN、+∞、2^64 的处理。

### 13.4 逐条移植 C++ 引擎单测（`test.cpp`，全部用 `MemoryBattleData` + 同一套标准表）

**标准表**（照 `test.cpp:64-139`）

| 表 | 内容 |
|---|---|
| 技能 101 | 指向性，类型 1，cd 组 9 = 12000 ms，伤害 50 |
| 技能 102 | 挂毒：effect = 201，伤害 0 |
| 技能 103 | 伤害 1000 |
| 技能 104 | effect = 220（驱散） |
| 道具 301 | battle_usable 1，回血 100 |
| buff 201 | 类型 50（毒），12 s，interval 6 s，`interval_effect=[10]`，max_layer 3，tag `poison_tag` |
| buff 210 | 类型 31（沉默），12 s |
| buff 220 | 类型 35，dispel_tag `poison_tag` |
| buff 230 | 无限，immune_tag `poison_tag` |
| buff 240 | 类型 42，12 s，interval 6 s，回血定值 25 |
| 许可行 1 | `[1000, 7005, 7005, 1000, 1000, 1000]` |

- **玩家**：`AddPlayer(id, team, hp, max, str, armor, crit, speed)`，固定 level 10、名字「测试玩家」，带技能 101–104（`test.cpp:155-175`）。A / B / C = 5001 / 5002 / 5003。
- **怪物号**：`kMonsterId = kMonsterActorIdBase`。副本 7 在标准表里没有行，所以默认是兜底怪：300 hp、str 5、armor 24、speed 60。
- **模式**：3 是 PVP 1V1，4 / 5 是 PVE 单人 / 组队。

| C++ 用例（行） | 种子 / 模式 | 必须复现的断言【C++ 断言】 |
|---|---|---|
| SameSeedSameCommands（221-245） | 42、20260815 / 4；A 1000/1000 str4 armor100 crit50 spd120；每回合提交 SKILL 101 | 同种子跑两次：每条事件 `toByteArray()` 加 `'\|'` 拼接，再加结算字节，完全相等 |
| AppearanceIdentity（247-262） | 42 / 4 | 快照里 `appearance_id = "04_mountain_guardian_boy"`、`class_id = 3`、`gender = 1` |
| TurnOrderIsSpeedDescending（268-282） | 1 / 3；A spd120，B spd240 | B 提交后返回 true；`events[0]` 是 ATTACK、source B |
| TieBreaksByActorIdAscending（284-297） | 1 / 3；同速 120 | `events[0].source = 5001` |
| TimeoutFillsDefaultBasicAttack（303-318） | 1 / 4；不提交 | 2 个 ATTACK；`events[0]` = ATTACK A → kMonsterId |
| Cooldown（324-348） | 1 / 4 | 第 1 回合 SKILL = 1；第 2 回合提交技能返回 false、提交普攻返回 true，SKILL = 0；第 3 回合 SKILL = 1 |
| PoisonTicksAndExpires（354-383） | 1 / 4 | 第 1 回合 BUFF_ADD = 1，首条 BUFF_TICK = {201, value 10, target 怪}；第 2 回合 TICK = 1、REMOVE = 1；第 3 回合 TICK = 0，怪身上 0 个 buff |
| BuffStacksUpToMaxLayer（385-405） | 1 / 4 | 每回合首条 BUFF_ADD 的 value 与条目层数依次为 1、2、3、3；buffs 恒为 1 条 |
| DispelSkill（407-426） | 1 / 4 | 驱散回合 REMOVE = 1、ADD = 0、TICK = 0；怪身上 0 个 buff |
| ImmuneTagBlocksPoison（428-450） | 1 / 3；B 带 `{900, 230, layer 1, remain 0}` | ADD = 0；B 身上只剩 230 |
| SnapshotRegenBuff（452-481） | 1 / 4；A 100/200，带 `{901, 240, 1, remain 2}` | 首条 TICK = {240, 25}；终血 = 100 − 本回合受到的伤害 + 25 |
| SilenceBlocksGeneralSkill（487-502） | 1 / 4；测试钩子挂 210，来源为怪 | 提交 101 返回 false；提交普攻返回 true。**Java 另加**：`validateAction(101) = 7005` |
| KillingAllMonstersWinsSideA（508-525） | 1 / 4；SKILL 103 | DEATH = 1；SIDE_A_WIN；`total_rounds = 1`；未阵亡；health = 1000 |
| MaxRoundsExhaustion（527-546） | 1 / 4；副本 7 的 time_limit = 12；DEFEND | 第 1 回合后 ONGOING；第 2 回合后 SIDE_B_WIN；`total_rounds = 2` |
| SimultaneousPoisonDeathIsDraw（548-573） | 1 / 3；双方 15/15 | 第 1 回合首条 TICK = 10，ONGOING；第 2 回合双方防御，首条 TICK = 5，DEATH = 2，DRAW |
| DamageFormula（579-601） | 1 / 4；str 4 | DAMAGE = **69**，非暴击；`hp_after` 与快照中怪的血量都是 231 |
| FleeIsDeterministic（607-640） | 7、1234 / 4；spd 600 | 同种子 FLEE 事件字节相等；`fled == success`；成功时 SIDE_B_WIN，结算里 `fled = true` |
| FleeIsRejectedInPvp（642-658） | 1 / 3 | 提交 FLEE 返回 false；FLEE = 0；首个 ATTACK 的 source = A |
| ItemHealsAndConsumption（660-701） | 1 / 4；A 50/200，301×2 | ITEM {301, 100, hp 150}；第二次 value = 200 − 回合初血量，hp 200；第三次提交返回 false；`items_consumed = [{301, 2}]` |
| StateSnapshotTracksPending（707-726） | 1 / 4 | 初始 round 1，pending = [A]；提交后为空；结算后 round 2，pending 1 个 |
| AutoActorCountsAsReady（732-753） | 1 / 4 | 开挂机前不就绪；`setActorAuto` 成功后就绪（基线返回 0，Java 返回 1000）；`events[0]` = ATTACK A → 怪；关掉挂机后不就绪 |
| SetActorAutoRejects（755-813） | 1 / 4；1 / 3；1..32 / 5 | 对怪、对 999999 都是 1005；PVP 里 nuke 秒掉 B 后，B → 1009，C → 成功；逃跑成功的 A → 1009 |
| AutoModeMatchesManual（815-845） | 42、20260831 / 4 | 挂机与手动提交 `ATTACK 0` 的字节流完全相等 |
| SnapshotMarksAuto（847-872） | 1 / 3 | pending 2 → A 挂机后 `is_auto`、pending = [B] → 关掉后 pending 2 |
| TeamSizeLimit（878-898） | 1 / 5 | 单队 6 人被拒，5 人放行 |
| MonsterAttributesFromTable（902-945） | 7 / 4；怪 7000：200 hp、str10、armor60、spd96、exp50、gold25 | 快照 health = max = 200；回合数 ≥ 3；SIDE_A_WIN；exp 50、gold 25 |
| PlayerDefeatYieldsNoReward（948-969） | 7 / 4；怪 str1000、spd1200 | SIDE_B_WIN；exp 0、gold 0 |
| DerivedPhysicalAttack（975-994） | 1 / 4；physical_attack 50 | 普攻 DAMAGE = **64** |
| DerivedMagicAttack（996-1012） | 1 / 4；magic_attack 20 | 技能 DAMAGE = **89** |
| DerivedDefense（1014-1045） | 1 / 4；spd 12；defense 0 / 1560 / 1e6 | 剩余气血 985 / 992 / 994 |
| PvpDirectDamageIsScaled（1048-1066） | 1 / 3；A str21 | A 提交返回 false、B 提交返回 true；首条 DAMAGE 来自 A，value = **10** |
| PhysicalSkillMultiplier（1069-1094） | 1 / 4；技能 105：物理，倍率 2；physical_attack 30 | DAMAGE = **129** |
| MonsterRowWithoutStats（1096-1128） | 11 / 4；怪 7002 只有 id | 300 / 300 / 5 / 24 / 60，`monster_table_id = 7002`；SIDE_A_WIN；**正好 10 回合** |
| MonsterZeroSpeed（1131-1152） | 12 / 4 | 150 / 8 / 1 / 速度 60 |
| RewardsAccumulate（1155-1206） | 13 / 4；X 7004、Y 7005 | **正好 4 回合**；exp 25、gold 12；`defeated = [7004, 7005]`，每条 count 1；重复读结果一致 |
| FledPlayerOnWinningTeam（1210-1259） | 21 / 5 | A：exp 40、gold 20，`defeated = [7006]`；B：`fled`，defeated 为空，exp 与 gold 为 0 |
| DeadPlayerOnWinningTeam（1263-1312） | 33 / 5；B 1/1 | A：exp 60、gold 30，`defeated = [7007]`；B：`is_dead`，defeated 为空，exp 与 gold 为 0 |
| EveryQualifyingMember（1316-1348） | 7 / 5 | A、B 各得 exp 80、gold 40 |
| MaxRoundsFallsBack（1352-1398） | 7 / 4；副本缺行 / time_limit 0 / time_limit 12 | 分别打 30 / 30 / 2 回合，都判 SIDE_B_WIN |
| PresentationGroupId（1412-1446） | 1 / 3；A spd120，B spd240 | 出手序 [B, A]；事件依次为 ATTACK、DAMAGE、ATTACK、DAMAGE；同组同 id、跨组不同 id、id 不为 0；`hit_index` 全为 0；MISS 0 个；DAMAGE 2 个 |
| FormationSlot（1449-1466） | 1 / 5 | A = 0，B = 1，怪 = 0 |
| ManaCost（1470-1509） | 1 / 4；101 耗蓝 `{1: 30}`；A mana 100/100 | MANA {A→A, 101, value 30, mana_after 70}，与 SKILL 同组，只有 1 条；快照 mana 70；下一回合放 102 时 MANA = 0 |
| PetJoinsOwnerTeam（1551-1585） | 31337 / 4；宝宝 spd360 | 宝宝 `actor_id = kPetActorIdBase`，team 0，主人 A，`is_auto`；`lastActionOrder[0]` 是宝宝；宝宝出了 ATTACK |
| PetNotControllable（1587-1606） | 4242 / 4 | 对宝宝提交返回 false；`setActorAuto(宝宝)` 不成功（1005）；宝宝仍是 `is_auto` |
| PetFinalState（1608-1623） | 55 / 4 | 结算 `pets` 1 条，`pet_id = 700001`，health ≤ 400，未阵亡 |
| PetWithSameIdAsOwner（1629-1653） | 7 / 4；玩家 5 带宝宝 5 | 两者 actor_id 不同；结算 `pets[0].pet_id = 5` |
| LegacyMonsterBaseId（1655-1675） | 7 / 4；玩家 1000000 | 持有该 id 的单位只有 1 个；`kMonsterId` 是 MONSTER |
| PlayerIdInEngineLocalNamespace（1677-1683） | 7 / 4；`bit63 \| 42` | 基线 `Initialize` 返回 false；Java 得到 `Rejected(RESERVED_PLAYER_ID)` |
| KillOrder（1686-1717） | 7 / 4；X 7701、Y 7702，hp 8 | 先打 M1 再打 M0；`defeated = [7702, 7701]`；gold 12、exp 25；重复读字节相等 |
| PoisonBurnKillOrder（1719-1752） | 7 / 4；201 的类型分别改为 50、51 | 第 1 回合 DEATH = 1；`defeated = [7712, 7711]`；gold 6；重复读字节相等 |
| UnknownOrMonsterSource（1754-1784） | 7 / 4；钩子挂毒，来源 0 或怪 | `defeated = [7721]`；gold 3 |
| VictoryRollsDrops（1884-1904） | 42 / 4；副本 62，怪 61 必掉 301×2 | `items_gained = [{301, 2}]`；重复读一致 |
| DropRateZeroNeverDrops（1906-1920） | 42 / 4 | `items_gained` 为空 |
| ItemManaPotion（1922-1945） | 7 / 4；302 回蓝 30；mana 10/100 | ITEM value 30，mana_after 40 |
| ValidateRejectsItems（1947-1969） | 7 / 4 | 9999 → 1001；不可战斗使用 → 1005；提交返回 false |
| ItemTeammateNotEnemy（1971-2007） | 7 / 5 | 对队友 1000，对怪 7002，对 123456 → 7001；事件 A → B，value 100；A 的消耗 1 条、count 1；B 的消耗为空 |
| PvpItemCap（2009-2031） | 7 / 3 | 前 5 次 `validateAction` 都是 1000；第 6 次 1005；`selfItems` 非空 |
| SelfItems（2033-2054） | 7 / 4 | 用前 `[{301, 1}]`；用后为空；不在本局的 C 为空 |
| SnapshotBuffsSanitize（2056-2096） | 7 / 4；带眩晕、瞬时、未知、合法毒（caster 777777）四条 | 只剩 201，且 `caster_id = 0` |
| PassiveSkillNotListed（2098-2120） | 7 / 4；401 为被动 | 401 不在 `skill_table_ids` 里；`validateAction` → 1005 |

**真表单测**（`TableBattleDataTest`，读 `config-data/tables`；移植 `tdp_test.cpp:94-129`）
- 副本分组：1 → [1, 2]，2 → [6, 7]，3 → [11, 12, 16]，999 → 空。
- 每只怪的 hp、str、speed、exp、gold 都 > 0；副本引用的怪都存在；怪 0 和 999999 不存在。
- Java 追加：
  - `skillDamage(1, 10) = 1000.0`；`skillDamage(13, 10) = 100000.0`；
  - `buffHealthRegeneration(17, 10, 100) = 13.0`；
  - random 闸：构造一份带 `random()*level` 的公式，必须抛 `TableLoadException`；
  - 用现有快照构造 `TableBattleData` 不抛异常。

### 13.5 派生的逐事件轨迹（Java 回放基线）

**记法**
- `g` 后面是 group_id，`h` 后面是 hit_index（省略即为 0）。
- M0 = `0x8000000100000000`，M1 = M0 + 1。
- `hp` / `mp` 指事件里的 target_health_after / target_mana_after。
- 「抽数」是本场 RNG 的总消耗次数。

**G-SS42**：SameSeed，种子 42【派生】，共 11 抽。

| 回合 | 事件 |
|---|---|
| R1 | g1 SKILL 5001→M0 skill 101；g1 DAMAGE 5001→M0 skill 101 v69 hp231；g2 ATTACK M0→5001；g2 DAMAGE v15 hp985 |
| R2（技能冷却中，提交被拒，走默认普攻） | g1 ATTACK 5001→M0；g1 DAMAGE v28 **暴击** hp203；g2 ATTACK / DAMAGE v15 hp970 |
| R3 | g1 SKILL；g1 DAMAGE skill 101 v138 **暴击** hp65；g2 ATTACK / DAMAGE v15 hp955 |
| R4 | g1 ATTACK；g1 DAMAGE v28 **暴击** hp37；g2 ATTACK / DAMAGE v15 hp940 |
| R5 | g1 SKILL；g1 DAMAGE skill 101 v37 **暴击**（封顶到剩余气血） hp0；g1 DEATH M0→M0 |

结算：SIDE_A_WIN，`total_rounds = 5`，health 940，mana 0；exp、gold 为 0；击杀簿为空、不掷掉落（兜底怪）。

**G-SS0815**：种子 20260815【派生】，6 抽，3 回合，A 剩 970。
- R1：SKILL，DAMAGE 138 暴击，怪剩 162；怪还击 15，A 剩 985。
- R2：普攻 28 暴击，怪剩 134；怪还击 15，A 剩 970。
- R3：SKILL，DAMAGE 134 暴击，DEATH。

**G-AUTO**：种子 42 和 20260831 各跑 10 回合【派生】。
- 每回合 3 抽：A 选目标、A 暴击、怪选目标。
- 10 回合后两局都仍是 ONGOING，A 都剩 850。
- A 的伤害序列：
  - 种子 42：14, 14, 28c, 28c, 14, 14, 28c, 28c, 28c, 14，怪剩 90；
  - 种子 20260831：14, 14, 14, 14, 28c, 14, 14, 28c, 28c, 28c，怪剩 104。
- 数值来源：普攻 `10 × 1.4 × 1560/1584 = 13.787878787878789` → 14，暴击 28；怪物伤害 `15 × 1560/1660 = 14.096385542168676` → 15。

**G-FLEE**：种子 7 与 1234【派生】。
- 两者都只有 `g1 FLEE 5001→5001 success`（Rand01 = 0.754 和 0.9472 都 < 0.95）。
- 怪物随后找不到敌人：占用 g2，没有事件，也不耗 RNG。
- 结果 SIDE_B_WIN，共 1 抽。
- 种子 1234 离阈值只差 0.0028，能有效抓出逃跑公式或 `rand01` 的错误。

**G-SCAN**：SetActorAutoRejects 第三段，种子 1 就成功【派生】。
- 出手序 [5001, 5002, M0, M1]。
- 事件：g1 FLEE 成功；g2 DEFEND 5002；g3 ATTACK M0→5002，DAMAGE 8，B 剩 992；g4 ATTACK M1→5002，DAMAGE 8，B 剩 984。

**G-DEAD33**：DeadPlayer，种子 33【复核】。
- R1：
  - 出手序 [5001, 5002, M0]；g1 DEFEND A；g2 DEFEND B；
  - g3 ATTACK M0→5002：RandIndex(2) 的第 1 抽为奇数，选中 B；
  - g3 DAMAGE v1 hp0（30 × 0.5 = 15，封顶为 1）；g3 DEATH 5002→5002。
- 之后 A 每回合打 30，第 15 回合打 10 把怪打死。
- 结果：SIDE_A_WIN，`total_rounds = 15`，A 剩 610，击杀簿 [7007]。

**G-FLED21**：FledPlayer，种子 21【复核】。
- R1：
  - 出手序 [5002, 5001, M0]；g1 FLEE 成功（0.2854）；g2 DEFEND；
  - g3 ATTACK M0→5001，DAMAGE 6（`11 × 1560/1570 × 0.5` 向上取整），A 剩 994。
- 之后打 5 回合。
- 结果：`total_rounds = 6`，A 剩 950，B 剩 1000，击杀簿 [7006]。

**buff 与道具轨迹**【复核】（手算，并用草稿复算器核对过）

- **G-POISON**（PoisonTicksAndExpires，种子 1）：
  - R1：g1 SKILL A→M0 102；g1 BUFF_ADD A→M0 201 v1（实例 1）；g2 ATTACK M0→A；g2 DAMAGE v15 hp985；g3 BUFF_TICK A→M0 201 v10 hp290。
  - R2（A 防御）：g1 DEFEND；g2 ATTACK；g2 DAMAGE v8 hp977；g3 BUFF_TICK v10 hp280；g3 BUFF_REMOVE A→M0 201。
  - R3：g1 DEFEND；g2 ATTACK；g2 DAMAGE v8 hp969。没有 g3，因为怪身上已经没有 buff。
- **G-DISPEL**（DispelSkill）的第 2 回合：g1 SKILL A→M0 104；g1 BUFF_REMOVE A→M0 201（source 是毒的施法者）；g2 ATTACK；g2 DAMAGE v15 hp970。
- **G-IMMUNE**（ImmuneTagBlocksPoison）：
  - 事件只有 g1 SKILL A→B 102、g2 DEFEND B。
  - B 的回合末组 g3 被占用，但没有事件。
  - 实例号计数器在开局时已被推到 901。
- **G-DRAW**（SimultaneousPoisonDeath）：
  - R1：g1 SKILL A→B；g1 BUFF_ADD A→B 201 v1（实例 1）；g2 SKILL B→A；g2 BUFF_ADD B→A 201 v1（实例 2）；g3 BUFF_TICK B→A v10 hp5；g4 BUFF_TICK A→B v10 hp5。
  - R2：g1 DEFEND A；g2 DEFEND B；g3 BUFF_TICK B→A v5 hp0；g3 DEATH A→A；g4 BUFF_TICK A→B v5 hp0；g4 DEATH B→B。没有 BUFF_REMOVE；结果 DRAW。
- **G-ITEM**（ItemHeals）：
  - R1：g1 ITEM A→A 301 v100 hp150；g2 DAMAGE v15 hp135。
  - R2：g1 ITEM v65 hp200；g2 DAMAGE v15 hp185。
- **G-MANA**（ManaCost）：
  - g1 SKILL A→M0 101；g1 MANA A→A 101 v30 hp1000 mp70；
  - g1 DAMAGE A→M0 101 v148 hp152（`150 × 1560/1584 = 147.727` → 148）；
  - g2 ATTACK M0→A；g2 DAMAGE v15 hp985（`15 × 1560/1570 = 14.904` → 15）；
  - 回合末快照里 A 的冷却为 `{101: 1}`。
- **G-REGEN**（SnapshotRegen）：
  - g1 DEFEND；g2 ATTACK；g2 DAMAGE v8 hp92；g3 BUFF_TICK 0→A 240 v25 hp117（source 是快照条目的 caster_id = 0）。
  - 终血 117。
- **G-DROP**（VictoryRollsDrops，种子 42）：
  - 第 1 抽：RandIndex(1)，普攻目标是 0。
  - 事件：g1 ATTACK A→M0；g1 DAMAGE v1 hp0（510 封顶为 1）；g1 DEATH。
  - 判 SIDE_A_WIN 后 RollDrops 用第 2 抽：0.6390 × 10000 = 6390.3 < 10000，掉落。
  - 结算：`items_gained = [{301, 2}]`，exp 7，gold 3，`defeated = [{61, 1}]`，total_rounds 1，health 1000。
  - **比较方向示意**（不是一个真实场景）：单人、击杀簿为 [真表怪物 1, 怪物 2]，依次掷「怪物 1 槽（10000）→ 怪物 2 槽 1（5000）→ 怪物 2 槽 2（2000）」。
    若这三次恰好是种子 42 的 #2、#3、#4：6390.3 < 10000，掉；7521.5 ≥ 5000，不掉；1362.7 < 2000，掉。真实场景见 §13.9 的 G7。

**其它【派生】结果**
- **G-TIMEOUT**：Timeout，种子 1。A→M0 伤害 14，怪剩 286；M0→A 伤害 15，A 剩 985；2 抽。
- **G-KILLALL**：KillingAllMonsters。DAMAGE 300（984.85 封顶）。
- **G-PRES**：PresentationGroupId。双方各 9 伤（`30 × 1560/1570 × 0.3 = 8.942675159235668`），都剩 991；组号正好是 1 和 2。
- **G-STALE**：MaxRoundsFallsBack 第一段。30 回合，A 剩 820（每回合 6 伤）。
- **G-FALLBACK**：MonsterRowWithoutStats。A 剩 865。
- **G-MONTAB**：MonsterAttributesFromTable。正好 **7 回合**（每刀 29，6 × 29 = 174 < 200）。

**G-TIE**（C++ 没有覆盖，Java 必须补上）【派生】
- 设置：PVE solo，种子 1；`AddPlayer(5001, 0, 1000, 1000, str 0, armor 100, crit 0, spd 60)`，与兜底怪同速；玩家 DEFEND。
- 出手序必须是 **[5001, M0]**：按无符号比较，5001 < 2^63 + 2^32。
- 事件：g1 DEFEND；g2 ATTACK M0→5001；g2 DAMAGE v8 hp992。
- 如果误用有符号比较，怪物会先出手，伤害变成 15。

### 13.6 事件线上字节【复核】

由 protobuf-java 4.35.1（`pom.xml:49`）生成，并按 proto3 编码规则手工逐字节核对过；C++ 会产出相同的字节。

| 事件 | hex |
|---|---|
| SKILL 5001→M0 skill 101 g1 | `0802108927188080808090808080800120655801` |
| DAMAGE 5001→M0 skill 101 v69 hp231 g1 | `080310892718808080809080808080012065304548e7015801` |
| ATTACK M0→5001 g2 | `080110808080809080808080011889275802` |
| DAMAGE M0→5001 v15 hp985 g2 | `08031080808080908080808001188927300f48d9075802` |
| FLEE 5001 success g1 | `080b10892718892740015801` |

- M0 编成 10 字节 varint，因为 bit63 置位。
- 零值字段（`hit_index = 0`、`is_critical = false`、`mana_after = 0`）不上线。

### 13.7 不变量测试（`TurnBattleInvariantTest`，JUnit 参数化，2000 个随机局）

**场景生成**
- 只在测试侧用 `SplittableRandom` 生成场景，引擎内照样禁用。
- 覆盖：PVE 单人 / 组队，PVP 1V1 / 5V5 / 切磋。
- 随机属性；随机技能与 buff 表，包括群攻、瞬时、无限、叠层、驱散、免疫、子 buff、target_sub_buff、毒、灼烧、回血。
- 随机道具与宝宝；每回合随机提交、随机开关挂机。

**不变量**
1. 同一输入跑两遍：每回合事件字节、确定性序列化后的快照字节、结算字节都相同。
2. 挂机与手动提交 `ATTACK 0` 等价。
3. 除快照本身就超过上限的单位外，气血始终 ≤ 上限（无符号比较）。
4. 已阵亡的单位没有 buff、不在防御中。已阵亡或已逃的单位，之后不再作为任何行动组的出手者出现。
5. 每回合 `group_id` 从 1 开始、单调不减；`hit_index` 小于该组的目标数。
6. 战斗结束以后，再结算不产生事件、不耗 RNG；`total_rounds` 等于实际结算过的回合数。
7. 击杀簿条目数 = 满足记账条件的怪物 DEATH 数。
8. 只有 A 方胜才有掉落；每种物品的掉落数 ≤ 对应槽 drop_count 之和 × 合格人数 × 击杀次数。
9. 结算重复读取，字节相同。
10. `validateAction == 1000` ⇔ 提交后该玩家从 pending 名单里消失。
11. `BattleRandom.draws()` 等于按 §8.4 的账逐项累计的次数。

### 13.8 测试钩子

- C++ 用友元 `TurnBattleEngineDeathTestAccess::AddBuff`（`test.cpp:26-38`），直接对单位调 `AddBuffToActor(depth 0)`，产出的事件丢弃。
- Java 用包私有的 `addBuffForTest`（§10.3），不增加任何公开的业务接口。

### 13.9 跨语言金样（同批双版本，mmorpg 侧待做，需用户同意）

**mmorpg 侧**：新增 `turn_battle_golden_test.cpp`。
- 对场景目录里的每个场景输出三部分：
  - 输入摘要：`sha256(request.SerializeAsString())`，以及每张内存表每一行的 sha256；真表场景改为输出指纹；
  - 每回合：`LastActionOrder`、每条事件的 hex、确定性序列化后快照的 hex；
  - 终局：每个玩家结算的 hex，以及 `SelfItems`。
- 环境变量 `TURN_BATTLE_GOLDEN_UPDATE=1` 时写出金样，否则比对。
- 同时在 `fp_test.cpp` 加一条 `EXPECT_EQ(MakeTables 指纹, "11ca1a2d9b625cceb3ce199148d03133")`。

**Java 侧**：`TurnBattleGoldenTest` 用移植过来的场景构造器建出相同的输入。**先比对输入摘要**（防止两边场景搭建走样），再逐回合比对输出。金样放在 `xm-battle-engine/src/test/resources/golden/turn_battle/`，来源见 Q2。

**场景目录**（每个场景固定种子）

| 编号 | 场景 | 种子 |
|---|---|---|
| G1 | 高暴击连发技能 | 42 / 20260815 |
| G2 | 挂机等同手动 | 42 / 20260831 |
| G3 | 逃跑；组队时扫种子 1..32 | 7 / 1234 |
| G4 | PVP 毒死平局 | — |
| G5 | buff 全套：叠层、驱散、免疫、回血 | — |
| G6 | 道具全套：回血、回蓝、给队友、PVP 次数上限 | — |
| G7 | **真表**副本 1，两人挂机打到结束：怪物 2 的 5000 / 2000 掉落掷点 | — |
| G8 | **真表**副本 3，五人挂机：怪物 11 / 12 / 16 的暴击率 15 / 15 / 25 产生暴击抽数；同时提交技能 1（AOE + 8 个瞬时 buff + 耗蓝 40 + 冷却 1 回合）与技能 13（effect 14 / 16 / 19 / 20，target_sub_buff 20 挂回施法者） | — |
| G9 | 宝宝 | — |
| G10 | 毒 / 灼烧的击杀顺序 | — |
| G11 | 打满回合上限 | — |
| G12 | PVP 5V5 挂机 3 回合（模式 1） | — |
| G13 | 快照带入 buff 17（无限回血）的边界值，用来回答 Q7 | — |
| G14 | §13.5 的 G-TIE | — |

**在 mmorpg 金样落地之前**：Java 金样由 Java 实现生成并冻结，只起回归作用；PARITY 注明「跨语言金样待 mmorpg」。

### 13.10 robot

6.1 不涉及 robot。battle_smoke 等随 6.2 / 6.4 推进（`docs/porting/inventory/combat.md:170`）。
