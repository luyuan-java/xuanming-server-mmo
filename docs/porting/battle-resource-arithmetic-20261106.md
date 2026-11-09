# 战斗资源数值与法力事件续移植

日期：2026-11-06。仅修改 Java 工作树 `wt-port-next`；C++ 只读，未改同步契约，没有新增依赖。本项代码与测试已经写入，尚未编译或运行项目测试。

## 固定来源与实际变化

对照 C++ 已提交对象 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 的 `cpp/libs/services/battle/system/turn_battle_engine.cpp`，并只读确认该文件和 `class_skill_rules.h` 到当前 `9aaedde23` 没有差异。

源 `LandedHeal` / `ApplyHeal` 已统一先算实际缺口，Java 前批只有职业技能采用；本批把该规则放回单位资源边界 `BattleUnit`：

- `landedHeal(double)` 只预测实际回复量；`applyHeal(double)` 在同一规则上更新状态。职业治疗、药品、周期 buff、挂机治疗收益都复用它。
- 当前 uint64 气血不转 double，避免高于 2^53 时低位丢失。正的有限本次治疗量截断后与精确缺口取小，结果等价于先夹到整数缺口再截断；NaN、无穷、非正数和死亡目标回复零。
- 满血或超上限时保持当前值，不把超出的气血作为负治疗回绕。普通受伤仍按原规则减血，治疗不能再把它拉低到上限。
- `restoreMana(long)` 给职业回蓝和药品共用：以 uint64 比较求真实缺口，先取小再相加，不改变原始最大值，也不复活死亡单位。
- `EventLog.signedManaDelta` 统一表现层转换。通用技能、职业技能扣蓝填负增量，职业及药品回蓝填正增量；配置回蓝而本次实际为零的药品保留 optional 字段 presence，纯回血药不写该字段。

同步 `BattleEventItem.mana_delta = 15` 已存在，不需要修改 protobuf 契约。`value` 与 `target_mana_after` 保持精确 uint64 位模式；显示增量无法容纳的正量饱和为 `Long.MAX_VALUE`，负量饱和为 `Long.MIN_VALUE`，避免极大回复被显示成消耗。无耗蓝技能继续不出 MANA 事件。双效药的 `value` 仍优先表示实际回血，`mana_delta` 单独表示实际回蓝。

## 明确保留与有意差异

治疗对齐固定源的当前实现，早期 Java 规格中“超上限治疗把气血拉低、收益回绕”已过期。对应旧用例改为继续检查完整行动事件链、最终气血不降到上限、没有虚假治疗事件；没有删除该场景。

两项数值边界与固定 C++ 不同：

1. 固定源药品回蓝仍使用 `min(maxMana, before + healMp)`，加法或差值可能 uint64 回绕。Java 与职业回蓝统一为先夹缺口，使回蓝只增加或保持法力；普通范围行为一致。
2. 固定源将 uint64 实际量强转 int64 再变号，超出 signed 表示范围的结果依赖 C++ 实现，部分负号操作越界。Java 明确采用上述显示饱和，真实状态与 `value` 不截断。

周期 buff 传给公式的损血参数仍保持固定源 `maxHealth - health` 的 uint64 位模式；超上限时即使公式得到大值，统一落血原语仍返回零。本项没有更改公式、随机数、消耗账、道具次数、结算存储或 owner 围栏。

药品的整数回血表值仍按固定源转为 double 后交给通用治疗公式；超高表值的浮点舍入不是本项承诺消除的差异。当前气血和实际缺口始终保持整数精度。

## 验证范围

新增 `BattleResourceArithmeticTest` 的 10 个测试方法，覆盖高于 2^53 的治疗与只读预测、uint64 最大值、NaN/无穷/非正数、死亡/满值/超上限、回蓝加法回绕、signed 显示饱和、双效药与 optional presence，以及真实药品、周期 buff、用药消耗账。通用耗蓝和旧回蓝药的既有用例追加增量断言；职业旧断言只随共用方法迁移，期望值保留。旧超上限 buff 用例按固定源更新，并新增没有 BUFF_TICK 的断言。

`git diff --check -- game-battle-engine` 通过；这只是静态空白检查。未启动 Maven、Java、protoc、C++ 或任何项目测试，运行结果由统一构建补充。此前报告的源码方法数不等于本批测试通过数，职业 scene 完整链由独立工作继续验收。
