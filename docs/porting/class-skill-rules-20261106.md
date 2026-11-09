# 职业技能共享纯规则移植

日期：2026-11-06。实现仅限 `game-battle-engine` 的 `ClassSkillRules`、独立测试及本文，不代表职业技能生产链或挂机自动选技已经移植完成。未编译、未运行项目测试，未修改 C++、同步契约、scene 存储或其他引擎文件。

## 固定来源与 API

行为来源是 C++/Go 仓库已提交对象 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 的 `cpp/libs/services/battle/system/class_skill_rules.h`；通过 `git show` 读取，没有引用移动工作树。规则用例参考同提交 `cpp/tests/turn_battle_engine_test/class_skill_test.cpp:32` 至 `:137`，另外补充 Java 无符号边界。

Java 入口为 `com.game.battle.engine.ClassSkillRules`，无表、I/O、状态或随机数依赖，不新增第三方库。

| API | 语义 |
|---|---|
| `rankLimit(int level,int unlock,int max)` | 未到解锁等级为零；达到时一重，此后每两级一重，封顶 max。 |
| `effectiveRank(int stored,int level,int unlock,int max)` | 存储重数与上述生效上限取小，未学零仍为零；不改存储值。 |
| `damageRankFactor(int)` / `supportRankFactor(int)` / `passiveRankFactor(int)` | 每多一重分别加 2% / 2% / 5%；算式中零重按一重，调用方负责先判已学。 |
| `ceilRatio(long,double)` / `floorRatio(long,double)` | 先量化百万分比，再做向上/下取整的整数乘除，uint64 饱和。 |
| `manaCost(long,double)` | 正比例至少一点，其余详见下面边界。 |
| `parsePassiveStat(String)` | 精确识别八个源名字，返回封闭 `PassiveStat`；未知、空串或 null 为 NONE。 |
| `upgradeCost(int nextRank,int unlockLevel)` | 单重费用 `100×n²+10×unlock`。 |
| `isValidUpgradeCount(int)` | 只允许 1 或 10。 |
| `planUpgrade(int currentRank,int maxRank,int rankLimit,int unlockLevel,long balance,int count)` | 不扣钱，返回 `UpgradePlan(steps,finalRank,totalCost,stop)`；先满重、再等级、再余额；按剩余余额逐重计划。 |

`PassiveStat` 包括 NONE、DEFENSE、PHYSICAL_ATTACK、MAGIC_ATTACK、MAX_HEALTH、MAX_MANA、SPEED、CRIT、OUTGOING_HEAL。`UpgradeStop` 只有 NONE、MAX_RANK、LEVEL_LIMIT、GOLD；没有移植 C++ 的计数哨兵或名字映射结构。用 Java enum、record 表达领域值，纯规则使用静态方法。

## 数值语义

所有协议 int/long 参数与整数返回值都是 uint32/uint64 位模式，不能把负的 Java 值当成非法协议值：例如 UINT32_MAX 等级从零解锁时可得 2³¹ 重数上限；UINT64_MAX 的一半向上取整为 Long.MIN_VALUE，向下取整为 Long.MAX_VALUE。

源 `RatioToPpm` 把有限正比例乘一百万后四舍五入，半数向上；百万分比最多为 `floor(UINT64_MAX/1_000_000)`。Java 用正数 `Math.round` 保留这一语义，以现有 `Unsigned.minUnsigned` 夹取比例上限，再用 `BigInteger` 做精确乘除与 uint64 饱和。没有把原始 value 转 double，也没有用 double 直接乘法后向上取整；因此 `ceilRatio(100,0.07)=7`，不会受 `7.000000000000001` 影响多收一点。

- 非正比例、NaN、正负无穷的 ceil/floor 都为零。
- 极大但有限的比例即使乘百万溢成浮点正无穷，也按百万分比上限处理。这与原比例本身是正无穷的情况不同。
- `manaCost` 保留源判断顺序：非正/NaN 为零；正数至少一点。故零法力上限、正比例量化为零以及 **正无穷** 的耗蓝都是一点，未擅自统一成零。
- 超出 uint64 的倍率结果饱和为 `-1L` 位模式。修习单价的源算式则是 uint64 普通算术：正式表最高十重，不溢出；极端 uint32 输入仍按源回绕，不能把倍率的饱和政策套到单价。
- 修习计划在当前存储重数超过表上限时，原值保留、零费用、MAX_RANK；不会先夹存储值，也不会先加一导致 UINT32_MAX 回绕。
- 恰好修完请求次数返回 NONE，即使再修一次会触发满重或余额不足，也不提前报告下一次的停止原因。

Java API 明确增加一个边界：`planUpgrade` 自身要求 count 为 1 或 10，非法次数抛 `IllegalArgumentException`；源生产调用先做同一校验，而源纯函数本身不校验。这里避免未经检查的 uint32 次数造成数十亿次循环。对合法生产输入的逐重结果不变；scene 调用方应先用 `isValidUpgradeCount` 返回契约错误码，不能让异常替代客户端错误应答。

## 测试与接入边界

新增 `ClassSkillRulesTest` 的 18 个测试方法，覆盖解锁/满重/降级/未学、uint32 高位、三个重数系数、八个被动名字、百万分比正中间及相邻浮点数、NaN/Infinity/零/负比例、uint64 高位精确取整与饱和、极大有限比例上限、正耗蓝至少一点、逐重费用与回绕、部分修习/停因优先、正好完成、原存储值保留、unsigned 余额和非法次数。续接复核增加十次修习总价边界：38,600 银两恰好修完十重，少一银两只修九重并收 28,590，防止把总价或最后一步扣款算错。

测试期望包括手算的高位/极值与固定来源的业务例子，未启动完整引擎或外部服务。当前机器有其他重型编译，本轮没有执行 Java、Maven、protoc 或任何项目测试；这些只是已编写、待执行的断言，不是测试通过证据。

后续引擎应复核已有协议 `skill_ranks`；scene 必须接真实持久等级、学习/修习和被动属性来源，不能因技能 ID 已存在而虚构一级。这里只交付共同算式；目标选择、职业技能效果、快照生产和自动选技由各自后续改动验收，完整缺口见 `class-skill-gap-20261106.md`。

## scene 生产链只读复核

2026-11-06 续接复核没有修改 scene 或持久 proto。当前 Java 的实际链路如下，不能把本批引擎可接受职业技能快照等同于客户端已经能修习和使用职业技能。

| 边界 | Java 当前代码 | 必须补齐的行为 |
|---|---|---|
| 加载与持久化 | `SceneWorld:1636` 每次从 `SceneTables.initialSkills()` 取技能；`ConfigSceneTables:53` 合并全部职业的种子；`ScenePlayer:56,141` 保存不可变 ID 列表；Java 自有 `player_state.proto:18` 至 PlayerState 结束没有技能字段 | 建立独立持久技能聚合，保存真实条目和存储重数；只按本职业种子幂等补发，先检查当前等级。重连、跨节点交出、周期保存共用现有 owner_epoch 围栏和整份 PlayerState 事务。 |
| 技能运行态 | `PlayerSkillState` 仅有施法阶段、冷却及行为状态，明确不持久化 | 不把已学重数塞进运行态；持久技能与瞬时施法状态各自建模。 |
| 技能书与修习 | `ClientRequestHandler:364` 的 77 仅下发技能 ID；`SkillFeature:33` 只注册 ReleaseSkill；同步契约已有 catalog、UpgradeSkill 和 expected_rank | 77 返回当前职业完整目录、存储重数、生效上限、余额和阻断原因；注册修习入口，按参数→技能/职业→忙碌→expected_rank→计划→扣费→原地改重数处理，所有出口回完整状态。 |
| 被动属性 | `AttributeService:149` 从职业、等级、加点和装备重算，未汇总技能；`PlayerAttributes:105` 的 combat 直接返回装备战斗属性 | 六项派生属性在加法后、取整前应用被动比例；暴击百分点同时加进物理和法术必杀率；重算从原始输入开始。技能变更按比例保持当前 HP/MP，不能重复回满。治疗加成只在 battle 消费。 |
| 战斗快照 | `BattleSnapshots:79` 只筛选技能 ID，没有 `putSkillRanks` | 从已持久条目取真实重数，按职业及等级筛选；被动也进 rank map，只有可主动施放技能进 ID 列表。快照的六项属性与暴击已经包含 scene 被动，battle 不再重复叠加。 |

固定源证据为 `player_skill.cpp` 的 `GrantInitialSkills:189`、`SanitizeSkillList:346`、`BuildSkillBook:422`、`UpgradeSkill:529`、`FillBattleSnapshot:642`、`CollectPassiveBonus:671`，以及 `player_attribute.cpp:819` 起的被动重算。旧 `docs/reference/mmorpg-client-contract-scene.md` 的“遍历所有职业、没有重数”描述是历史基线；当前同步 `player_skill.proto` 与 `player_skill_comp.proto` 已有新字段，不能继续把旧行为当作本次目标。

最小完整接入应以“持久技能聚合 → 按职业补种子及生效视图 → 属性/快照 → 技能书和修习”作为一个验收单元；无需改客户端契约或新增中间件。Java 自有 PlayerState 可以新增专用技能字段，但应同时纳入持久 schema 台账、未知字段往返与跨节点保存测试。不能只把 `List<Integer>` 转为 rank=1 map：现有列表混合了全部职业的种子，且不含购买、未学习、零重数和未来版本高重数信息。

持久值域要求：表里不存在的技能、其他职业技能、超过当前 max_rank 的重数和 rank=0 条目均原样携带，读取时只生效当前版本允许的部分；通用技能的存储 rank 也不能清零。源仅清除与版本无关的 ID=0 和重复 ID（保留首条）。初始种子补发可以将本职业、已到解锁级的 rank=0 条目原地升为 1，但这是独立、可审计的发放行为，不是加载器普遍改写重数。条目与聚合的未知字段都应保留。修习扣款和改重数必须同一场景回调、同一 PlayerState 保存，重复 expected_rank 请求不得再次扣款。

这部分只完成源码审计和接入设计，没有宣称 scene 功能已移植或测试已通过。
