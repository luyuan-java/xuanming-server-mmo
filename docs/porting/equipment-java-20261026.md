# 装备 2.11 Java 实现记录（2026-10-26）

## 基线与边界

- Java 工作区：`D:\luyuan\wt-port-next`，基于 `03a0708d` 后继续；不触碰 main 工作区。
- C++ 唯一证据版本：`92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`。通过 `git show` / `git grep <commit>` 阅读，不把活跃 C++ 工作区里的其他会话改动作为依据。
- 设计依据：固定版本 `docs/design/equipment-attributes.md`、`equipment-enhance-inlay.md`。事实以实现为准，例如第一份文档对后续强化与资产通道扩展有历史描述。
- 不修改受管客户端 proto、配置表、消息号、tip；只追加 Java 自有 `game/storage/player_state.proto` 字段。本批没有新增第三方依赖。

关键 C++ 证据位于 `cpp/libs/services/scene/player/system/player_equip.cpp`：

| 能力 | 固定版本锚点 |
|---|---|
| 启动校验、一次初始化 | `ValidateTables`（550）、`RollNewInstance`（857） |
| 穿脱、GM 发放 | `Equip`（925）、`Unequip`（1053）、`GmGrantItem`（1116） |
| 强化、镶嵌、取石 | `Enhance`（1143）、`InlayGem`（1286）、`RemoveGem`（1398） |
| 有效值与只读展示 | `AccumulateWornItem`（475）、`CollectBonus`（1514）、`FillItemDisplay`（1529）、`FillEquipSlots`（1710） |
| 未知材料保护 | `Enhance` 的 `CheckQuantities`、`InlayGem` 的 `CheckQuantities` |
| 规则、面板 | `equip_attribute_rules.h` 的 `RollAffixes` / `ApplyEffect`；`player_attribute.cpp` 的 `FillCombatPanel` |

## Java 组织与装配

`EquipmentTables` 是不可变配置视图；`EquipmentRules` 接收显式随机源，负责新铸和有效值规则；`EquipmentBonuses` 是一次重算得到的不可变值；`EquipmentService` 负责命令和候选提交；`EquipmentDisplay` 纯读组装客户端视图；`EquipmentFeature` 通过现有 `SceneFeature` 注册六个请求。没有 ECS 注册表、线程局部全局状态或 C++ 包结构的照搬。

生产装配已在 `SceneNode` 中共享 `BagTables.from(configTables)` 持有的同一个规则对象；下面展示其等价构造关系：

```java
EquipmentRules equipmentRules = new EquipmentRules(EquipmentTables.from(configTables), random::nextLong);
BagTables bagTables = BagTables.from(configTables, equipmentRules);
BagService bags = new BagService(bagTables, guids, audit, anomalies, metrics);
AttributeService attributes = new AttributeService(attributeTables, clock, currency, metrics, equipmentRules);
BagFeature bagFeature = new BagFeature(bags);
EquipmentService equipment = new EquipmentService(equipmentRules, bags, currency, attributes);
EquipmentFeature equipmentFeature = new EquipmentFeature(equipment, bagFeature, attributes, registry);
```

随机源由装配方持有，`nextLong(bound)` 必须返回 `[0,bound)`；装备逻辑只在所属场景逻辑线程调用。加载顺序继续是背包规整，然后属性重算。旧 `AttributeService` 构造器保留，用空装备规则维持旧组件测试；生产 `SceneNode` 已使用新五参数构造器，并注册共享 `BagFeature` 与 `EquipmentFeature`。

## 完成的行为

- `BagItemState.equipment = 7` 引用自有 `EquipmentState`；其 affixes、enhance_level、gems 都为追加字段。无段与空段严格不同：无段是历史未初始化物品，空段是已初始化但没有随机词条。
- 新铸经统一 `Bag.add` 路径执行一次掷值，因此发奖、任务、GM 和资产通道新建都经过同一规则。既有实例入包、穿脱、存盘恢复、跨节点快照不经过掷值。整批新实例先准备完再改背包，随机源异常不能留下半批变更。
- 蓝词条带权不放回，重复属性权重先合并；粉、黄分别带权放回。随机调用次序、零概率不调用、必出仍调用、上下界与 uint32 边界有测试。读路径按当前上限夹值，保留原始存档。
- 穿戴先检查存在、装备类型、等级、职业、可用部位；槽号升序选首个空槽，满槽替换最小槽；旧装备回到人物背包的首个空格。被替换物和卸下物必须可搬运。完整候选一次替换，保留实例与未知字段。
- GM 发放仍经过分发器的 dev/test 闸，单次数量范围为 1–99；prod 返回既有 1006。
- 强化等级取从 1 连续的表上限；无初始化、关闭或满级分别返回既有业务码。先查材料和金币，再掷一次成功率；成功与概率失败都扣费，失败按表掉级。只放大基础属性，随机词条和宝石不放大。
- 镶嵌只接受人物背包/装备栏目标，按存在、孔已占、类型顺序检查；最多 8 条原始宝石记录。取石免费退回第一颗非零记录，只有候选入包成功才清孔；同孔多余记录清理时逐条记错误日志，不重复退款。其他孔和未修改的未知字段保留。
- 材料与宝石按数量消费时只使用没有装备段、没有未知字段的普通单位；只有计入未知实例才够量时返回 1006。干净单位够量时跳过未知实例。具有未知字段或装备段的实例也不参与并堆，防止取石发回普通单位时吞掉特殊实例。
- tooltip 包含名称、要求、基础/随机属性、强化预览、宝石孔；装备槽升序去重，无名空槽隐藏，已占无名槽保留以便卸装。面板战斗属性显示行按 sort/id 选定，百分比显示封顶 100；实际战斗加成不做此显示夹取。
- 装备变化触发属性整块重算，当前血蓝按比例保持，不能靠穿脱治疗。战斗锁恢复尚未 READY 时也拒绝装备写入，避免重连恢复窗口绕过战斗冻结。
- `BattlePlayerSnapshot.combat` 使用现有字段 20，仅包含装备战斗加成；基础暴击和抗性仍在 `base_attributes`，避免叠加两次。全部 15 个字段有逐字段映射测试；零 combat 保持旧快照没有该段的 presence。

## 一致性、持久化与故障窗口

装备命令在单一场景逻辑线程完成纯校验、构造钱包/背包候选并提交。`CurrencyService.PreparedDeduction` 检查原背包快照、余额、冻结与重复提交；余额与背包一起完成后才调用旁路审计。审计抛异常不会把成功误报为可重试失败。没有在逻辑线程执行 SQL、Redis 或其他阻塞 I/O。

这是一笔**内存中的候选提交**。成功应答后，仍由现有周期存盘、最终存盘、跨节点交出和跨区快照路径落库；装备命令没有另开同步写库协议。若进程在应答后、快照持久化前崩溃，装备、材料和金币可能一起回到最后持久化快照。这与固定 C++ 装备操作写内存、依赖玩家存盘的能力边界一致。

全局 SQL `owner_epoch` 证明哪个 scene 有写者归属，阻止旧代写回覆盖新代；它**不证明每个装备操作 exactly-once**。现有装备客户端请求没有稳定业务 UUID，传输 `request_id` 也不是跨连接、跨节点的永久业务键，因此没有添加本地 UUID 集合伪装持久去重，也没有擅改共享协议。连接超时后重试强化仍可能再执行一次，这是当前协议和源版本的明确边界。

原有 AssetOp 请求账本、持久化确认和 owner_epoch 写回围栏保持原样。`BagService` 的整实例托管携带完整 `BagItemState`，持久化字段和嵌套未知字段不被抽成模板；装备不能降格成配置数量转移。周期/最终/交出快照继续携带完整 `PlayerState`，不加入新的进程本地证明替代原账本。

Java 现有生命、派生属性用非负 `long`，超界饱和到 `Long.MAX_VALUE`；装备数值沿用该领域约束。C++ 极端值饱和到 `UINT64_MAX`，这是超出正常配置范围时的显式数值边界差异，不能把 Java 的负 long 当成巨大加成。正常 uint32 配表值与实际装备数量范围内计算一致。

审计原因通过内部枚举对齐：`TX_EQUIP_ENHANCE=27`、`TX_EQUIP_INLAY=28`、`TX_EQUIP_GEM_REMOVE=29`。关联号是装备 guid；extra 包含等级变化/成功结果或孔位。资产审计仍不是可恢复装备所有随机字段的历史快照，固定 C++ 文档也明确未提供此能力。

## 验证记录

新增 34 个测试方法，集中于 `EquipmentRulesTest`、`EquipmentServiceTest`、`EquipmentFeatureTest`，覆盖：

- 加权随机顺序、uint32 边界、上限档、15 项 combat 映射与速度/防御区别、只读展示/槽位/百分比夹取。
- 一次初始化、穿脱替换、血蓝比例、强化成功/概率失败/纯校验失败、镶嵌与取石、满包零改动、脏孔记录、未知材料、候选过期、随机源故障、冻结及恢复闸。
- 真实六入口分发、170 在成功穿脱回复前发送、请求号、成功/失败回包形状、prod GM 拒绝。
- 整实例资产通道搬运及未知字段保留；真实 `freezeForHandOff` 生成旧 ownerEpoch 快照，经过 protobuf 字节往返后由另一 `BattleFixture` 的新 ownerEpoch 加载，属性重新计算且不重掷。

已有 `BagFeatureTest` 保留全部原断言；展示信息改变后扩充精确期望，原「装备未实现」测试改为明确只注册 BagFeature 的组件隔离测试，完整实现另有分发测试。

`git diff --check` 已执行，无空白问题。依照主代理对共享构建时段的要求，本实现者未启动 Maven、Java 编译或测试。新增自有 proto 尚待统一生成和构建，以上列的是已写测试，不是通过证据。

独立只读复核：`baseline_cpp` 核对 EquipmentService 与 PreparedDeduction 的候选提交、unknown 材料保护，未发现确定的部分提交缺陷；该结论不替代运行测试，也不声称整个模块完成独立审查。

2026-10-27 补充：装备服务通过构造器注入 `SceneMetrics`，六个装备写入口被战斗/恢复闸拒绝时各计一次固定 `equipment` 标签；冻结拒绝优先，不误记战斗闸。原战斗矩阵统一保留“恰好一次、其它闸不增”的断言，启动指标精确集合扩展为十项。新增配置夹具与这些观测回归尚待重跑。

## 源版本未提供的能力

固定 C++ 文档/实现仍未提供炼化重掷入口、宝石合成/升级、打孔/解锁、强化保护符/祝福/转移、进化星级、套装/共鸣、新的绑定限制或混乱状态本身。本次没有伪造这些业务或新增客户端协议。现有绿色 tier 可从存档读取，但本次不会生成绿色/套装效果。新的自然产出和数值平衡需要对应源表设计，不能把本批算法接入写成已补完经济配置。
