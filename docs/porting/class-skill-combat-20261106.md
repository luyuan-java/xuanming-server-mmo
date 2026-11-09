# 职业技能局内属性、治疗与 buff 规则

来源只读固定在 C++ `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 的 `cpp/libs/services/battle/system/turn_battle_engine.cpp` 与 `constants/turn_battle_constants.h`。Java 工作树是 `wt-port-next`，不是主工作区；没有修改 C++、同步协议或配置表，也没有增加第三方依赖。

## 当前实现

`ClassSkillCombat` 通过构造器接收 `BattleData`，只读取本局状态；`BattleContext` 提供有效物攻、防御、速度、治疗及无随机数的伤害估算方法。相关能力不依赖 Spring、网络或持久化服务。

| 规则 | Java 行为 | 固定源位置 |
|---|---|---|
| 有效属性 | buff 类型 10 / 11 / 13 分别作用于物攻 / 防御 / 速度；同属性取最强正幅度和最强负幅度，合成倍率最低 0.1；层数不放大幅度 | `ClassBuffStatMultiplier`、`EffectivePhysicalAttack/Defense/Speed`，1607–1644 |
| 无增减益 | 倍率等于 1 时直接返回 uint64 位模式，不先转换成 double；有倍率才走百万分比量化和整数向下取整 | 同上 |
| 治疗加成 | 技能重数 map 按无符号技能号排序后累加 `outgoingHeal` 被动，避免浮点求和顺序漂移 | `OutgoingHealBonus`，1592–1605 |
| 职业治疗 | 原始法攻 × 治疗倍率，加无符号等级 × 每级加值，再乘伤害重数系数和治疗被动；残血加成严格小于阈值时生效。最大气血为零按满血比率 1 | `ClassSkillHealAmount`，1493–1502；`HealthRatio`，93 |
| 伤害与随机数 | 所有调用最终伤害原语的路径用有效防御；装备抗性及暴击类型分流沿用原规则。估算不落血、不抽随机数；实际暴击率 0 不抽，100% 仍抽一次 | `CalculateFinalDamage`、`DamageBeforeCriticalRoll`、`CriticalChance`、`ExpectedDamage`，2319–2385 |
| 敌方速度 | 逃跑判定查询的敌方最高速度改取有效速度，仍无符号比较且忽略死亡和逃离单位 | `MaxAliveEnemySpeed` |
| 职业 buff 挂载 | 新增五参数入口仅给本次显式效果存幅度与施加回合；递归子 buff 使用原零幅度入口 | `AddBuffToActor`，2504–2508 |
| 刷新 | 同行按原施法者隔离 / `no_caster` 规则匹配，保留绝对值更强的幅度；弱非零值也刷新时长和施加回合，原施法者与实例号不变 | `StackOrRefreshExistingBuff`，2620–2649 |
| 持续与周期 | 非零幅度本回合新挂 / 刷新的条目不扣持续；周期效果先结算，瞬时条目仍立即移除。原零幅度 buff 保持旧时序 | `TickActorBuffs`，2045–2110 |
| 职业净化 | 按挂载顺序移除负幅度、包含精确 `Debuff` 键或毒 / 灼烧 / 眩晕 / 沉默 / 冰冻类型；数量按 uint32，不抽随机数 | `IsDebuffEntry` / `DispelDebuffs`，1646–1681 |
| 外部快照 | 在保留原表存在性、控制类、瞬时、施法者、剩余回合清洗规则时，额外清除 `stat_percent` 和 `applied_round` | `SanitizeSnapshotBuffs`，2243–2287 |

周期毒伤仍直接按 `interval_effect[0] × layer` 落血，不套有效攻击、防御、暴击或 PvP 系数；周期回血仍使用原表公式，不乘职业治疗被动。这些是固定源的实际行为。职业主动治疗共用 `ClassSkillExecution.landedHeal` 先按精确 uint64 缺口封顶再截断；旧通用/周期 `BattleUnit.applyHeal` 的高位精度与超上限行为仍待统一，见执行文档。

## 回归源码与验证状态

新增 `ClassSkillCombatTest` 共 15 个测试方法，覆盖无增减益 uint64 全域原位、强正强负叠加与下限、NaN / 正负无穷边界、无符号速度、按无符号技能号稳定求和、治疗阈值、有效防御进入伤害、分流暴击、旧公式逐位和后续随机序列，以及六组 buff 生命周期 / 刷新 / 子 buff / 周期 / 净化 / 快照清洗回归。未删改旧用例断言。

用户要求构建和测试等其他重型构建空闲，本轮没有编译、运行单测或启动服务。这 15 个方法是已写的源码数量，不能当作通过数。已逐项静态对照固定源、调用签名与 protobuf 字段；实际 Java 编译与完整 `clean verify` 仍待执行。

## 尚未闭环

本文件只登记局内规则。挂机选择器已在后续源码中接入，见 `class-skill-auto-20261106.md`；职业玩家数据持久化、学习 / 修习 / 属性重算和 scene 战斗快照仍未闭环，不能由这些计算方法推定已经完成。

本轮新职业技能路径写 `mana_delta`；旧通用技能耗蓝事件继续保留原来的字段 presence / 字节行为。因此尚不能宣称所有旧通用耗蓝事件已经与固定 C++ 的 `mana_delta` 新行为一致。职业路径对超出有符号 64 位显示范围的增减量采用有符号饱和，完整无符号数量仍在 `value`；该边界由主动执行部分记录和测试。
