# 职业技能底座与挂机选技的移植缺口

日期：2026-11-06。本文是只读源码复核与后续实现依据，**不表示已移植**。未编译、未运行测试，未改 C++、同步契约或业务源码；本次不修改 PARITY。

## 固定来源与结论

基线为 C++/Go 仓库已提交对象 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1`，以下 C++ 行号均来自该对象，不以移动工作树为依据。

- `cpp/libs/services/battle/system/turn_battle_engine.cpp:1687` 已有 `SelectAutoClassSkill`，`:956` 已在 `FillDefaultActions` 调用。PARITY 末行的“工作区未提交”已过时；“Java 待做”仍然成立。
- `git log --all --reverse -S SelectAutoClassSkill -- cpp` 找到首次引入该标识的提交为 `c2d8e5b77530ac4efab8cdbbb87808ff754d3072`（2026-11-01，保存服务端当前改动）。不必把移植基线改成仓库最新 HEAD。
- Java 不只缺一个自动选择器，还缺其依赖的职业技能执行底座。不能直接在 `fillDefaultActions` 生成职业 SKILL，然后沿用旧通用技能执行路径。
- 固定来源 `docs/design/class-skills.md:149` 的 §6.5 明确记录该实现未编译、23 项自动选技测试未运行；旧原生测试证据不能覆盖新增代码。移植时要以生产语义和独立测试核验，不能把来源源码当作已验证实现。

## 已有契约与当前 Java 接缝

同步契约已提供 `game-table/src/main/proto/skill_table.proto:89` 开始的职业字段（class、重数、目标阵营、耗蓝比例、治疗、回蓝、驱散、buff 幅度等），以及 `game-proto/src/main/proto/proto/battle/battle_data.proto` 的 `skill_ranks`、`stat_percent`、`applied_round`。这不等于相应 Java 玩法已消费它们；本次不手改这些文件。

| Java 位置 | 当前行为 | 职业技能所缺语义 |
|---|---|---|
| `game-scene/.../battle/BattleSnapshots.java:58`、`:79` | 快照带 class_id，技能只复制 ID 并按可施放类型过滤 | 技能重数的有效值、被动重数及职业匹配没有放入 skill_ranks；须连接真实持久技能等级来源，不能假定 ID 存在就学会一级。 |
| `game-battle-engine/.../TurnBattleEngine.java:208`、`:231` | 复制 class_id、过滤 skill ID | 未复制或复核 skill_ranks，未按职业与有效重数过滤职业主动技能。 |
| 同文件 `:549` | 玩家和怪物缺省普攻，宠物走 `defaultPetAction` | 未对 is_auto 玩家选择职业技能。 |
| `game-battle-engine/.../ActionChecks.java:86` 起的 `checkSkill` | 所有技能共用旧 targeting_mode、cooldown_id、cost_resource | 缺职业重数/阵营校验、独立技能冷却与 maxMana 比例耗蓝；target_id=0 的职业选目标语义不能沿用旧零目标闸。 |
| `game-battle-engine/.../TurnBattleEngine.java:715` 的 `executeSkill` | AOE 取全部敌人，否则单目标/随机敌人；取 Cooldown 表毫秒；旧公式伤害与 effect buff | 缺职业目标排序与 max_targets、治疗/回蓝/定量驱散、重数增幅、技能独立回合冷却；友方技能重验失败不可降级随机攻击。 |
| `game-battle-engine/.../BuffEngine.java:251` 的 tick 与现有挂载路径 | 旧通用 buff 行为 | 尚未消费职业 stat_percent/applied_round，缺同属性同方向取最强、刷新与生效回合语义。 |
| `game-battle-engine/.../BattleContext.java:190` 的 `calculateFinalDamage` | 现有物/法伤公式并消耗真实暴击 RNG | 自动估算需要抽出与真实结算共享的纯公式，不能调用这个有随机副作用的方法。 |

表中省略号分别表示模块内的 `src/main/java/com/game/scene` 或 `src/main/java/com/game/battle/engine`，不是新建的包。引擎目前已有装备战斗属性与通用宠物技能，应复用并保护其行为。`BattleRules.isTurnBattleCastableSkill` 已按 `passive_stat` 排除职业被动，这仅是过滤保护，并未提供职业执行能力。

scene 现有 `SkillService` 仍是实时释放 84 的服务，不是职业技能书/修习服务。持久等级如何保留、技能书消息 317/修习接口、属性被动的生产入口须在后续底座工作中专门盘点；本文不把这些外围链路误报为已完成，也不要求先实现实时命中系统才能做纯回合引擎。

## 固定 C++ API 与必须保持的语义

文件前缀均为 `cpp/libs/services/battle/system/`。

| 文件/位置 | API 或能力 | 移植要点 |
|---|---|---|
| `class_skill_rules.h:72`、`:80`、`:95`、`:104`、`:140` | RankLimit、EffectiveRank、等级系数、整数比例、ManaCost | 生效重数按等级/解锁/max_rank 夹取，存储值另行原样保留。无符号值、百万分比量化、向上/下取整和饱和边界须用 Java 明确表达，不照抄 C++ 类型。 |
| `turn_battle_engine.cpp:353`、`:653`、`:1352` | 初始化重数、CheckActionPrerequisites、ClassSkillRank | 职业匹配与正重数双重校验；快照外来值不能直接信任。宠物不接职业技能。 |
| 同文件 `:782`、`:1316` | CheckCooldown、SkillManaCost | 职业技能按自身 ID 冷却，旧技能 cooldown_id 分组时排除职业行；职业耗蓝由最大法力及 mana_percent 决定。 |
| 同文件 `:1360`、`:1385` | ValidateClassSkillTarget、SelectClassSkillTargets | 友方含本人/宝宝；自身目标只允许 0/自己。阵营校验不把已死目标误当其他阵营。目标只选存活未逃；指定主目标优先，再治疗缺血比例、阵位、无符号 actor ID；max_targets 至少 1。选择不耗 RNG。 |
| 同文件 `:1440`、`:1493`、`:1504` | ExecuteClassSkill、ClassSkillHealAmount、ApplyClassSkillToTarget | 出手前重验；敌方技能失败可普攻，友方/自身失败不攻击。没有目标不扣蓝、不进冷却、不发事件。冷却设置 cooldown_rounds+1 抵消本轮末衰减；先 SKILL、一次耗蓝，再逐目标效果。 |
| 同文件 `:1579`、`:1592`、`:1607`、`:1631` | ActionInitiative、OutgoingHealBonus、ClassBuffStatMultiplier、有效属性 | 支援先手及属性增减益与既有回合排序联动；同向只取最强，正负相加，倍率下限 0.1；没有职业 buff 时直接返回原整数，保护旧对局。治疗被动由引擎读取，其余属性被动应已在 scene 快照里，不能重复叠加。 |
| 同文件 `:1646`、`:1669` | IsDebuffEntry、DispelDebuffs | 减益包含负属性百分比、debuff 标签与毒/灼烧/眩晕/沉默/冰冻；按既有条目顺序移除，不随机。 |
| 同文件 `:1687` 至 `:1927` | SelectAutoClassSkill 与各类收益估算 | 纯读候选选择；只对自动玩家、无已提交动作时使用。没有正收益回落普攻。 |
| 同文件 `:2340`、`:2367`、`:2380`、`:2387` | DamageBeforeCriticalRoll、CriticalChance、ExpectedDamage、LandedDamage | 真实结算与估算共享物/法抗性、护甲/有效防御、PVP 系数、暴击期望、落血夹限；估算不掷骰。普攻基线还算连击期望、按存活敌人取平均。 |

自动选择优先级固定为危急治疗（气血 <30%）→ 驱散 → 普通治疗（≤70%）→ 回蓝（法力 ≤30% 且扣掉本次耗蓝后净收益为正）→ 属性增减益 → 输出。防御增益限气血 ≤60%；速度变化须能翻转与至少一个敌人的先后次序；输出有效伤害必须严格高于普攻。各用途内部先比收益降序，再耗蓝、冷却、技能号、目标号升序。不能用技能列表或 protobuf map 顺序决定平手。

治疗选中时目标为 0，出手时按最新气血重选；通过驱散入选的治疗技能显式指定减益目标。已有等强/更强的同向属性 buff 不重复施放，物攻为零的对象不因减物攻而获得虚假的收益。免疫、剩余血蓝夹限、不同伤害类型抗性也须进入估算。

## 建议的 Java 实现顺序与边界

1. 先核清 scene 技能持久等级、技能书/修习及属性被动来源，以同一组 Java 纯规则表达有效重数、比例与费用；缺字段时不得硬编码重数或修改同步契约。保持未知技能和超版本存储值的既有保护。
2. 在纯引擎建立职业重数初始化、ActionChecks 分流、目标选择及职业执行。把共享公式放入现有规则/上下文的适当职责，不复制整个 C++ 大类；可用 package-private 辅助类与 record 描述不可变候选，避免另造可变状态树。
3. 补职业 buff、有效属性、initiative、持续回合和治疗被动。将真实伤害的纯公式与掷骰拆开，保持旧 RNG 消费位置及事件次序。
4. scene 构造完整快照并补端到端入口证据后，接入 `fillDefaultActions` 的自动玩家分支。选择器的接口可为 `Optional<BattleAction> select(BattleUnit actor)`，从注入的当前战斗上下文只读取数；它不扣血蓝、不发事件、不创建冷却、不耗随机数。
5. 最后更新 PARITY 和回放/观战文档。is_auto 已成为对局语义输入；“切挂机仍与普攻同事件流”的旧假设仅能用于没有可用职业技能的旧夹具。协议无需为自动选择新增字段。

这是跨 scene 快照、引擎校验、执行、buff 和数值规则的中等以上工作量，不能按一次默认动作修补估算。选择器本身对每个技能枚举合法主目标，并对实际目标集合计收益；朴素实现包含每次目标排序（约每施法者 O(S·N² log N)）及 buff/速度比较成本，需结合人数与技能上限评估。无需为当前小规模回合引擎引入网络服务或新依赖，也不应为追求缩短循环而改变确定性排序。

## 回归依据与验收范围

固定来源 `cpp/tests/turn_battle_engine_test/class_skill_test.cpp` 的职业底座测试与 `AutoClassSkillEngineTest` 23 项为行为用例来源，不能把其未运行状态改称通过。后续至少应验证：

- 快照外来职业/未学/零重数/超等级重数；旧通用技能、宠物、怪物路径不变；职业与旧 cooldown_id=0 不互锁。
- 目标阵营、主动指定/缺省目标、主目标出手前死亡、多人缺血排序与群体上限；友方技能重验失败不攻击；没有目标不扣费。
- 治疗、回蓝、驱散、重数、耗蓝精度、满血满蓝无收益、持续回合、属性最强值与 immunity；initiative/速度只在约定回合影响排序。
- 自动/非自动、手动动作优先、非法/冷却/沉默/不足蓝候选过滤；所有六类优先级与阈值边界；输出和普攻的暴击/连击期望、PVP/抗性/残血夹限。
- 技能顺序置换与无符号大 ID 的稳定平手；选择前后 RNG 状态一致，真实执行事件/随机序列仍符合现有旧技能及装备测试。
- 现有 `TurnBattleSkillTest`、`TurnBattlePetSkillTest`、初始化/回合/伤害/buff/确定性测试断言保留；新规则只能有依据地补充条件或期望，不以删断言消除行为差异。

以上为开始实施前的差距审计。2026-11-06 续接已补入纯规则、引擎职业执行、属性/buff 和挂机选择源码及回归，见 [执行续移植](class-skill-execution-20261106.md)；尚无编译/测试通过证据。scene 的真实重数、持久化、修习和属性链仍未闭合，不能标记客户端职业技能或全部 C++ 功能移植完成。
