# 装备战斗接入（Java 6.7，2026-10-26）

本批以 C++ 固定提交 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5` 为只读依据，核对
`docs/design/equipment-attributes.md` §4.5、§10.3、`cpp/libs/services/battle/system/turn_battle_engine.cpp`、
`constants/turn_battle_constants.h` 与 `combat_damage_rules.h`。不按正在编辑的 C++ 工作区取值，不改共享协议或配置表。

实现采用现有 Java 领域模型：`BattleUnit` 保存不可变快照对应的状态，`BattleContext` 负责一次结算与唯一 RNG，
`EquipmentCombatRules` 是无状态数值规则。没有新增依赖、ECS、宏或 Go/C++ 目录层次。

## 输入与数值

scene 的 `BattleSnapshots.build` 输出 `BattlePlayerSnapshot.combat`（field 20）；来源是装备加成，基础
critchance / resistance 仍在 `base_attributes`。引擎只在 `hasCombat()` 为真时复制到 actor field 27，
保留所有 15 字段；宝宝和怪物不继承主人装备。缺字段不会因为读默认值而出现子消息 presence。

`CombatAttributes` 的 uint64 在 Java 中按 long 位模式解释。基础与加成先做无符号饱和加法，不能因高半区值
变负而丢失概率，也不能回绕成低值。概率消费点再钳到 100。技能等级只对伤害表达式使用
`uint64_saturating(uint32(level) + skill_level_bonus)` 转 double，不改演员等级、目标等级系数、buff 回血或耗蓝。

15 字段对应：物理必杀、法术必杀、连击、反击、反震、技能等级、忽视所有异常、抗毒、抗冻、抗昏睡、抗遗忘、
抗混乱、所有抗异常、法术抗性、物理抗性。抗混乱按原版仅贯通快照；没有虚构混乱 buff 类型。

## 结算定案

| 原版决定 | Java 行为 |
|---|---|
| D3 连击 | 普攻首段命中后最多追加 1 段，双方仍在场才掷。追加段独立普攻公式与必杀后乘 50%，向上取整、至少 1、封顶当前气血。同 group、hit_index=1。有效技能不触发，失效技能降级普攻可触发。 |
| D4 反击 | 整次普攻结束后最多一次，双方存活未逃且防守者能行动；眩晕 / 冰冻禁止。另开组且 hit_index=0，发 ATTACK 与 DAMAGE，能必杀但不再连击、反击或反震。 |
| D5 反震 | 普攻每段实扣后、连击续段前判；dealt=0 或任一方已不在场不掷。受击者死亡不反震。按实扣 50% 精确整数向上取整、至少 1、封顶出手者血量；不再次过抗性、必杀或防御姿态。反杀走原死亡与击杀归属。 |
| D6 必杀 | 物理 / 法术分别加对应装备率，未知伤害类型只保留基础。倍率 2。满率仍掷一次，保持原有 RNG 契约。 |
| D7 抗性 | 基础抗性加对应物 / 法装备抗性，经现有 `CombatDamageRules` 与护甲、防御共用 60% 常驻减伤封顶；未改实时共用公式。 |
| D8 异常 | 中毒 50、冰冻 52、眩晕 30→抗昏睡、沉默 31→抗遗忘。灼烧 / 未知类型不抵抗。只判敌方施加，有效值为饱和的（单项+全抗）减施加者忽视，减到 0 为止。 |

异常抵抗位于已有免疫之后、驱散 / 叠层 / 子 buff 之前；成功发 RESIST，父 buff 的其他副作用均不执行。
target_sub_buff 回挂时以原目标为施加者，读取原目标的忽视抗性。自己、队友或找不到施加者不抵抗；
开局带入的合法 buff 仍按既有清洗规则保留，不重新判抵抗。

一次普攻的 RNG 顺序是：首段必杀→首段反震→连击判定→追加段必杀→追加段反震→反击判定→反击必杀。
未触发 / 不满足条件的分支不消耗后续骰。新概率 0 与 ≥100 都短路；必杀继续沿旧行为：0 短路，任何正值含 100 均掷一次。
普通 hit_kind=0 不上线字段，普通段不增加浮点乘除，零加成不改变旧事件或 RNG 序列。

## 隐私出口

`TurnBattleEngine.stripEngineOnlyState` 只修改调用方传入的状态副本 builder，清所有演员的 combat，包括本人。
`BattleViews` 在统一视角裁剪时调用它，保留原冷却 / 道具规则和所有 buff。引擎全知状态保持完整。

实际房间出口为开局 143、参战回合 139、补拉 140、观战首帧 161、观战回合 158；都使用 `ViewerState`。
`EquipmentVisibilityTest` 以真房间和真引擎走过这五条出口，包含发生连击的回合，防止只测裁剪函数而漏调用路径。

## 验证边界

新增 `EquipmentCombatRulesTest`、`TurnBattleEquipmentTest`、`EquipmentAilmentTest`、`EquipmentVisibilityTest`，
并给已有 `BattleViewsTest` 追加 combat 断言；旧 Trace / Wire / RNG 金样与所有原断言没有删除或改期望。
测试覆盖字段边界、饱和/钳位、概率分流、连击/反击/反震各停止条件、死亡归属、异常映射与副作用次序、
32 个种子的完整新增骰序对账、3 个种子的全零 presence 对照、五个下发出口。

原 `TurnBattleTraceTest` 金样仍是 Java 手算 / 草稿复算依据，不能称为已在当前 C++ 运行取得的跨语言金样。
截至源码交付时，本代理按统一构建协调要求未运行 Maven、Java 编译、单测、真栈或 C++；最终结果由主控构建记录补充。
本批是装备领域行为接线，不代表职业技能全量、全球集群故障或水平扩展容量已验收。
