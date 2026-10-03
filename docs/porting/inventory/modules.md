# 功能清单：玩法模块（cpp/libs/modules/**）

> 来源：mmorpg `26ceb70ca`（`cpp/libs/modules/{bag,currency,mission,reward,condition,gain_block,audit,transaction_log,snapshot,id_segment,scene}`，
> 接线点 `cpp/nodes/scene/handler/{rpc/player,event}`、`cpp/libs/services/scene/player/system/{player_feature_snapshot,player_mission,bag_marshal,mission_marshal,player_database_loader,asset_op_system,player_activity_schedule}`）；
> 客户端 `mmorpg-client/Assets/Scripts/Game/PlayerFeatures/PlayerFeaturesClient.cs`；Java 侧 grep `xuanming-server-mmo`（2026-10-02）。

## 区域概述

mmorpg 的玩法模块是 scene 进程内、单线程 ECS 上的一组无状态 System：背包（四个固定包 + 动态包，实例层 / 布局层 / 准入 / 淘汰四层，
严格「规划 → 预留 → 提交」）、货币（3 币种 + GM 补缴欠款）、任务（条件驱动的进度、链式接续、自动 / 手动领奖）、活动目录（任务表 mission_type=2 + 排期表），
以及横切的审计设施（全服 / 每玩家禁发、Kafka 资产流水、异常检测告警、登录 / 登出快照、永久 GUID 号段）。客户端可见面很小：
190 活动列表、191/192 背包读取 / 整理、193/194/195 任务列表 / 接取 / 领奖、54 货币列表，外加仅 dev/test 开放的 37/49/94/95 GM 货币指令；
**没有穿脱装备、使用物品、丢弃、拆分、移动格子、扩容的客户端 RPC**（物品只在回合制战斗里作为药品使用），物品入包只来自战斗掉落、任务奖励与通用资产通道。
持久化全部随 `player_database` 一条记录（`bag_component` / `mission_component` / `currency`）落盘。Java 版现状（2026-10-03）：
货币（54、37/49/94/95，批次 2.1）、资产流水 / 快照 / 全服禁发 / 异常检测（2.3）、背包（191/192，2.4）已做，玩家数据存 `player_state` 一份 protobuf；
任务 / 活动（190、193–195）随 2.5，`xm-scene` 目前对它们回 1006 kFeatureUnavailable。

## 功能

### bag-core-container — 背包核心容器（堆叠 / 格子 / 四个固定包）
- mmorpg: `cpp/libs/modules/bag/{bag_system.h/.cpp,item_store.h/.cpp,container_layout.h/.cpp,admission_policy.h,eviction_policy.h/.cpp,bag_profile_registry.*,comp/player_bags_comp.h,item_system.h}`；单测 `cpp/tests/bag_test/bag_test.cpp`、`bag_remove_by_guid_test.cpp`
- client messages: none（经 191/192 间接可见）
- tables: Item（`max_stack_size`、`equip_kind`）、EquipSlot（`id` = 槽位号，`equip_kind` = 部位）
- depends on: id-segment-client（物品 guid 铸号）、config tables
- behavior: 每个玩家固定 4 个包，`bag_type` 即持久化编号：0 人物背包（扁平格，起始容量 100）、1 仓库（扁平，200）、2 装备栏（具名槽 FixedSlotLayout，10 槽）、3 临时格（扁平，200，**满了先进先出淘汰最早入包的实例**，按 `acquire_seq`）。另有 `dynamicBags_`（按 bag_id，profile_id 经 `BagProfileRegistry` 还原；生产代码目前**没有任何注册与创建点**，只有 marshal 会读写）。
  堆叠：同 config 先填既有未满堆（`PlanStackIntoExistingStacks`），剩余按 `max_stack_size` 切新实例，每个新实例占 1 格（`FootprintFor` 恒 1×1，GridLayout 已写好但未用）。`max_stack_size<=1` 为不可叠加，每件一个实例。
  入包三段：plan（查表 + 准入，零副作用）→ reserve（`CanReserve`；装备栏按部位分桶：同部位可有多行槽位，找第一个「同 equip_kind、槽号 < 容量、空着」的槽；`equip_kind=0` 的东西进不了装备栏）→ commit。失败一定发生在 reserve 前，不留半写。淘汰只在临时格、只在给了回执向量时发生，且先算到不动点再销毁（被挤掉的可能正是要并入的未满堆）。
  扣除三种语义：`RemoveItems` 按 config 全或无（不足 6xxx `kBagInsufficientItems`）；`RemoveItemsClamped` 按实际持有夹紧、恒成功（战斗消耗）；`ReserveForBatchRemove` + `RemoveItem` 按 guid 全或无且销毁实例（只收不可叠加、size==1，否则 27xxx `kAssetInvalidBundle`）。
  整理 `MergeAndCompact`：合并同种零头 + 回收 size==0 实例；`kMergeOnly` 一格不挪，`kMergeAndReorder` 按 (config 升序, size 降序) 重铺 0..n-1；已最优时早退返回 changed=false；装备栏 / 格子布局永不重排。
  错误码：6004 `kBagAddItemInvalidParam`（零数量 / 查不到表 / 铸号源不可用 / 预设 guid 撞车）、6005 `kBagAddItemBagFull`、1002 `kInvalidTableData`。
- internal: 纯内存 ECS 组件，单线程。Java 需要：`PlayerBags`（4 个固定包 + 可选动态包）、实例表（guid → config/size/acquire_seq）、布局（扁平 / 具名槽）、准入 / 淘汰策略接口，规划-预留-提交的三段 API；所有写只在场景逻辑线程。
- java: done（2026-10-03，批次 2.4）— `com.game.scene.player.{Bag,BagItem,BagType,PlayerBags}`：四个固定包、堆叠、装备栏按部位分桶、临时格 FIFO 淘汰（不动点）、合并 + 重排；批量入包整批原子（含淘汰与铸号），遍历顺序确定（入包序号, guid）。未移植基线无生产调用方的单件 / 预设 guid 入包、各种扣除、扩容、准入策略、动态包、GridLayout；夹紧扣除随 6.3
- size: L
- robot: Go `robot/features_smoke_scenario.go`（只读 191 / 可选 192），`robot/features_battle_smoke.go`（掉落入包）；Java xm-robot none
- hazards: ① `RemoveItems` / `RemoveItemsClamped` 只扣数量、留下 size==0 的「僵尸堆」直到下一次整理，`GetBag` 不过滤，客户端会看到 count=0 的物品；② 临时格 FIFO 会**静默销毁**玩家最早的东西（只落 LogItemDestroy 流水，不通知客户端）；③ 批量入包「返回失败 ≠ 包未变」：临时格可能已淘汰、前几个 config 可能已写入（`mutated` 出参）；④ 装备栏槽号 = EquipSlot.id，若表里 id ≥ 容量 10 该槽永远不可用；⑤ 还原路径故意比活玩法宽松（位置可变、物品不丢，见 bag-persistence）；⑥ `ExpandCapacity` 存在但无生产调用（容量不可解锁）。

### bag-orchestration-service — 背包编排（禁发 / 冻结 / 流水 / 异常检测 / 战斗掉落溢出）
- mmorpg: `cpp/libs/modules/bag/bag_service.{h,cpp}`；调用方 `services/scene/player/system/player_mission.cpp`（任务奖励）、`services/scene/battle/system/player_battle.cpp:770-830`（战斗掉落 → 主包，失败余量 → 临时格；战斗消耗 `RemoveItemsClamped`）、`asset_op_system.cpp`（资产通道）
- client messages: none（间接）
- tables: Item
- depends on: bag-core-container, gain-block, transaction-log, anomaly-detector, 跨节点换图冻结（`PlayerLifecycleSystem::IsCrossZoneFrozen`）
- behavior: 每个入口先判跨 zone / 换节点冻结（AddItem(s) 回 1005 `kInvalidParameter`；RemoveItemsByGuid 回 27003 `kAssetFrozen`），再判全服禁发、每玩家 GM 禁发（都回 1005），然后调容器，最后按每个写入 / 淘汰 / 销毁 / 扣除的实例落流水（TX_SYSTEM_GRANT / TX_QUEST_REWARD / TX_ITEM_AWARD / TX_ITEM_DESTROY / TX_AUCTION_SELL，带 correlation_id 与来源 extra）并喂异常检测。`SortByPlayerRequest` = 玩家显式整理（唯一会重排的入口）；自动整理必须显式选 `kMergeOnly`。战斗掉落：先入主包；失败时按「入包前后持有量差」只把**没进去的余量**改投临时格（防复制），两处都失败只记 ERROR（物品丢失）。
- internal: Java 需要一个 `BagService` 门面：冻结 / 禁发闸、流水回执、`mutated` 语义（失败时包是否已改动，资产通道据此区分 RETRY 与 APPLIED+partial）。闸门（战斗中、冻结）**不得下沉**到容器层，否则战斗结算会被自己拦住。
- java: partial（2026-10-03，批次 2.4）— `com.game.scene.bag.BagService`：冻结闸（随 5.2，目前放行）→ 全服物品禁发 1005 → 容器 → 入包流水（每配置一条）+ 淘汰 / 整理退役销毁流水 + 物品获取异常检测；mutated 语义由整批原子取代（失败即未改动）。战斗掉落 / 任务奖励 / 资产通道入口随 6.x / 2.5 / 2.9
- size: M
- robot: Go `features_battle_smoke.go`（掉落 / 消耗）；Java none
- hazards: ① 物品禁发回 1005，而货币禁发回 27005 `kAssetBlocked`，两套口径不一致；② `AddItem` 流水只记 `PrimaryWrittenGuid`（一次拆成多堆时只记第一个 guid，数量记整批）；③ 冻结拒绝时背包回 1005（终局类），货币回 27003（RETRY 类）；④ 每玩家 `PlayerItemBlockList` 全仓**从未 emplace**，GM 物品禁发实际不可用（见 gain-block）。

### bag-persistence — 背包持久化与还原
- mmorpg: `services/scene/player/system/bag_marshal.{h,cpp}`、`player_database_loader.cpp:109/151`、`proto/common/database/bag_quest_mail_data.proto`（`BagAllData` / `ItemEntry`）、`proto/common/database/mysql_database_table.proto`（`player_database.bag_component = 13`）
- client messages: none
- tables: Item、EquipSlot（还原时按部位重新落位）
- depends on: bag-core-container
- behavior: 存：`capacities[4]` + 每个实例一条 `ItemEntry{item_uuid, config_id, stack_size, pos, bag_type, acquire_seq}` + 动态包 `{bag_id, capacity, profile_id, items}`。取：先 Reset → 恢复容量（拒绝压到已占格以下）→ 逐件 `InsertItemForRestore`：非法 guid / 重复 guid 跳过并 ERROR；装备栏优先按**当前表**的部位找空槽，其次用快照 pos，再不行自动选位；`bag_type` 越界的条目丢弃（WARN）。还原不跑堆叠、不落流水、不查禁发。`acquire_seq=0`（旧档）按重放顺序补盖。
- internal: 与位置、货币、任务同一条玩家记录、同一次写回（资产与账本一起落盘、一起丢）。Java：在 xm-player-store 加背包存储（建议 protobuf blob 列或子表），写回走现有 `owner_epoch` 围栏；客户端不可见，格式 Java 自定。
- java: done（2026-10-03，批次 2.4）— `player_state.bag`（Java 自有 `BagState` / `BagItemState`，按 (bag_type, 格子) 写出、未知字段带回）；构造时原样收下、进场前规整：结构性损坏拒绝进场（不丢物品）、格子问题重新落位、不认识的 bag_type 隔离写回、数量 0 丢弃、序号 0 补盖；容量只在非缺省时存（对应 hazard ①②③）
- size: M
- robot: none（Go `features_smoke` 只读当下状态）
- hazards: ① 还原时具名槽按**当前**表落位：策划改了部位，老装备会换槽甚至进不了装备栏而被「自动选位」塞进任意空槽；② 容量来自存档而非表，存档里的容量永远不会被新版本的起始容量抬高（`kBagMaxCapacity` 改大对老玩家无效）；③ bag_type 越界的条目被**丢弃**（与「物品不能丢」原则矛盾，只记 WARN）。

### bag-client-get-sort — 背包读取 / 整理（191 GetBag、192 SortBag）
- mmorpg: `cpp/nodes/scene/handler/rpc/player/player_bag_handler.cpp`、`services/scene/player/system/player_feature_snapshot.cpp`（`PlayerBagSystem::BuildSnapshot/Sort`）、`proto/scene/player_bag.proto`；客户端 `PlayerFeaturesClient.cs:60-125`
- client messages: 191 SceneBagClientPlayerGetBag (C2S, 应答 GetBagResponse)、192 SceneBagClientPlayerSortBag (C2S, 应答 SortBagResponse)
- tables: Item（`max_stack`、`equip_kind` 填进 BagItemInfo）
- depends on: bag-core-container, bag-orchestration-service, currency-core（BagInfo 顺带 CurrencyComp）
- behavior: GetBag 纯读取（不合堆、不整理、不创建组件）：`bag_type >= 4` → 1005；无背包组件 → 1003；两层不一致或容量 > u32 → 1002；实体无效 → 1009。成功时 `BagInfo{items（按 item_id 升序）, layout{bag_type, capacity, slots（按 slot 升序，width=height=1）, can_sort = bag_type∈{0,1}}, currency = 整个 CurrencyComp}`；`name/description/icon_key` 恒空（表无展示列）。
  SortBag：只允许 0 / 1（否则 1005）；**回合制战斗中（InBattleComp）→ 1005**；跨 zone 冻结 → BagService 回 1005；成功执行 `kMergeAndReorder`，回整包快照 + `changed`（已最优为 false）。任何失败都 `clear_bag()` 并把 tip 写进 `error_message`。客户端：错误显示「读取背包失败（错误码 N）」，任务领奖成功后自动重拉背包。
- internal: 应答在场景逻辑线程上直接构造；Java 只需在 `ClientRequestHandler` 加两个方法 + BagInfo 组装。
- java: done（2026-10-03，批次 2.4）— `com.game.scene.bag.BagFeature`：191 / 192 同基线校验顺序与应答形态（无符号 bag_type、布局总在、按 item_id / 格子排序、货币 = 54 的 wallet.toClient()，不带 debts——hazard ① 不存在；不留僵尸——hazard ②）；战斗中禁止整理随 6.3。robot `bag` 场景
- size: M
- robot: Go `robot/features_smoke_scenario.go`（`readBag`，`features_smoke.sort_bag` 选项）、`robot/logic/gameobject/player_features_test.go`；Java xm-robot none
- hazards: ① BagInfo.currency 是 ECS 里 `CurrencyComp` 的整份拷贝，含**加载时**的 `debts`（GM 操作员名、原因）与 `blocked_types`——泄露 GM 信息且是过期数据（运行期欠款在 `PlayerCurrencyComp`，见 currency-debt-clawback）；② 不过滤 size==0 僵尸实例；③ 战斗中拒绝整理用的是通用 1005，客户端无法区分「战斗中」与「参数错」。

### equip-slot-rules — 装备栏部位规则（无穿脱协议）
- mmorpg: `cpp/libs/modules/bag/bag_system.cpp:1046-1120,1256-1350`（`EquipKindFor` / `FindFreeSlotForKind` / `CountFreeSlotsForKind` / `PlaceInstance`）、`container_layout.h` `FixedSlotLayout`；`item_system.h` 末尾 `//todo equipment list with unique guids per piece`
- client messages: none —— **mmorpg 没有 Equip / Unequip / 换装 RPC**，`proto/` 全仓无此类消息；装备栏只能经通用入包接口（资产通道 / 测试）写入
- tables: Item.equip_kind、EquipSlot（3 行：`id` 槽号、`equip_kind` 部位；同部位多行 = 多个槽，如两只手镯）
- depends on: bag-core-container
- behavior: 进装备栏的物品必须 `equip_kind>0` 且有空的同部位槽；装备栏永不淘汰、永不整理重排；`GetBag(bag_type=2)` 可读出装备栏（can_sort=false）。没有穿戴后属性加成（属性系统不读装备栏）。
- internal: Java 实现装备栏时需同一套「部位分桶」reserve（不能用格子数判满，基线 2026 年修过「第 3 只手镯通过预检、commit 半批」的 bug）。
- java: done（2026-10-03，批次 2.4）— 装备栏按部位分桶的 reserve 与放置同一判据（第三只手镯整批拒绝、零写入）；还原优先用存档格子（本部位空槽），用不上的槽行加载时告警。穿脱 RPC 基线也没有
- size: S（只规则）；若要做真正的穿脱 + 属性加成需先在 mmorpg 定契约（XL，不在基线）
- robot: none
- hazards: 槽号直接用 EquipSlot.id，必须 < 装备栏容量 10；规则全在表里，C++ 没写死任何槽号。

### use-item — 使用物品（只在回合制战斗）
- mmorpg: `cpp/libs/services/battle/system/turn_battle_engine.cpp:449-1000`（`battle_usable` / `battle_heal_hp` / `battle_heal_mp`）、结算扣除 `player_battle.cpp`（`BagService::RemoveItemsClamped`）；`condition_type.h` 的 `kConditionUseItem=4` **无生产者**
- client messages: none 场景内；战斗内经 battle 协议（`proto/battle/battle_data.proto` `item_table_id`），属战斗区域
- tables: Item（battle_usable、battle_heal_hp、battle_heal_mp）
- depends on: 回合制战斗（另一区域）、bag-orchestration-service
- behavior: 场景里不能用物品；战斗中按快照账本用药，结算时按实际持有夹紧扣除（不足按 0、记日志），流水 TX_ITEM_DESTROY 带 battle_id。
- internal: 由战斗区域负责；本区域只提供 RemoveItemsClamped。
- java: not_applicable — 本区域无场景内使用物品功能；战斗用药随战斗区域移植
- size: S
- robot: Go `features_battle_smoke.go`
- hazards: 任务条件「使用物品」类别（4）、「对话 NPC」（2）、「交互」（5）在表里可配，但接取校验 `HasProgressSource` 只放行 1 / 6 / 8，其余一律 1003（任务永远接不了），不会卡死但策划配了会被静默挡掉。

### currency-core — 货币核心（加 / 扣 / 余额）
- mmorpg: `cpp/libs/modules/currency/{constants/currency.h,system/currency_system.{h,cpp},comp/player_currency_comp.h}`、`proto/common/component/currency_comp.proto`、持久化 `player_database_loader.cpp:124-163`（`player_database.currency = 8`）；单测 `cpp/tests/currency_test/currency_test.cpp`
- client messages: none 直接（54 / 191 / 37 / 49 读写它，见 currency-client-and-gm）
- tables: none（币种是代码枚举：0 金币、1 钻石、2 绑钻）
- depends on: gain-block, transaction-log, anomaly-detector, currency-debt-clawback, 跨节点冻结
- behavior: 唯一加币入口 `AddCurrency` 判定顺序（契约）：amount ≤ 0 → 1005；币种越界 → 1005；冻结 → 27003 `kAssetFrozen`（RETRY 类）；全服禁发 → 27005 `kAssetBlocked`；本人被 GM 封禁该币种 → 27005；然后先抵扣补缴欠款，再入账余额，落流水（记**请求额**与前后余额）、喂异常检测。`DeductCurrency`：同样两条参数校验 → 冻结 27003 → 余额不足 27000 `kAssetCurrencyInsufficient`（玩家可读的终局拒绝）。余额存 `CurrencyComp.values`（repeated uint64，下标 = 币种，首次访问时补齐到 3 个）。
  其它区域的生产调用方：战斗结算加金币（`player_battle.cpp:1682`）、属性洗点 / 宝宝洗点扣金币（`player_attribute.cpp:661/753`、`player_pet.cpp:587/680`）、资产通道。
- internal: Java 需要一个 `CurrencyLedger`（场景线程内）+ 持久化列（3 个 BIGINT 或 blob）+ 流水回执；纯参数校验必须排在冻结 / 封禁前（否则资产通道对编程错误无限重投）。
- java: missing — xm-scene / xm-player-store 无货币字段
- size: M
- robot: Go `robot/currency_crash_window_scenario.go`（`etc/robot.currency-crash.yaml`：读余额 → GmAddCurrency → 杀进程 → 重登比对，验证落盘窗口）；Java none
- hazards: ① `*balance += gain` **没有溢出检查**（uint64 回绕），GM 加币可传 int64 最大值；② 新角色 `values` 为空数组，读到的余额靠「缺省 0」；客户端看到空数组；③ 流水 `currency_delta` 记请求额而非实际入账额（被补缴抵扣的部分只在另一条 clawback 流水里）。

### currency-debt-clawback — 补缴欠款（GM 挂账 + 加币自动抵扣）
- mmorpg: `currency_system.cpp`（`AttachDebt/WaiveDebt/AdjustDebt/FreezeDebt/QueryDebts` 与 AddCurrency 内的抵扣段）、`comp/player_currency_comp.h`（`CurrencyDebt`、`LoadFromProto/SaveToProto`）、`proto/common/component/currency_comp.proto`（`CurrencyDebtEntry`）
- client messages: none（GM 入口 102 GmAttachDebt / 106 GmWaiveDebt / 107 GmQueryDebt / 109 GmFreezeDebt / 113 GmAdjustDebt 全是 TODO 空桩，见 gm-rollback-rpcs）
- tables: none
- depends on: currency-core, transaction-log
- behavior: 每币种一笔欠款 {owed, paid, frozen, expires_at, reason, gm_operator, created_at}；加币时若欠款未冻结、未过期，先扣 `min(收入, 剩余)` 进 paid 并落 clawback 流水，还清即删除；Adjust 减额夹到不低于 paid；Attach / Adjust 加额做 uint64 溢出拒绝。
- internal: 运行期在 `PlayerCurrencyComp`，持久化写进 `CurrencyComp.debts`（存盘时先 CopyFrom 再 SaveToProto，顺序反了会抹掉欠款）。
- java: missing
- size: S
- robot: none
- hazards: ① **生产代码没有任何调用方**能挂上欠款（GM RPC 是空桩），机制处于休眠；② 加载时把带 debts 的 `CurrencyComp` 原样 emplace 进 ECS，之后 54 / 191 把这份**加载时快照**（含 GM 操作员名）发给客户端；③ 过期用墙钟秒，不清理过期条目（永远留在存档里）。Java 建议：先不做，或只做持久化透传。

### currency-client-and-gm — 货币列表与 GM 货币指令（54、37、49、94、95）
- mmorpg: `cpp/nodes/scene/handler/rpc/player/player_currency_handler.cpp`、`player_gm_guard.h`（`SCENE_RUN_MODE`）、gate GM 闸（gate 区域）、`proto/scene/player_currency.proto`
- client messages: 54 SceneCurrencyClientPlayerGetCurrencyList (C2S)、37 GmAddCurrency (C2S)、49 GmDeductCurrency (C2S)、94 GmBlockCurrency (C2S)、95 GmUnblockCurrency (C2S)
- tables: none
- depends on: currency-core, gate GM 闸（gate 区域）
- behavior: 54 回 `GetCurrencyListResponse{currency = 整个 CurrencyComp}`，组件缺失时回空。GM 四条：先过 GM 闸（非 dev/test 运行模式回 1006 `kFeatureUnavailable`；gate 侧是第一道锁，scene 分发入口按 `Gm` 前缀是第三道），37 记 TX_GM_GRANT、49 记 TX_GM_DEDUCT，成功回 `balance_after`；94 / 95 冻结期回 1005，否则改 `CurrencyComp.blocked_types`（重复封禁幂等成功）。错误码同 currency-core。
- internal: Java 需 gate GM 闸（按运行模式放行）+ scene 四个 handler。
- java: partial — 37/49/94/95 在 Java 上回 1006，恰好等于 mmorpg 生产模式（GM 关闭）的行为；54 与 dev 模式 GM 全部 missing
- size: S
- robot: Go `currency_crash_window_scenario.go`（54 + 37）；Java none
- hazards: ① 54 的应答直接暴露 debts（含 `gm_operator`、`reason`）与 `blocked_types`；② GmAddCurrency 的 `amount` 是 int64，配合 ① 的无溢出检查可把余额回绕。

### gain-block — 获取封禁（全服 / 每玩家）
- mmorpg: `cpp/libs/modules/gain_block/gain_block_service.{h,cpp}`；判定点 `bag_service.cpp:93/168/250`、`currency_system.cpp:118`、`asset_op_system.cpp:489`；每玩家物品名单 `bag_service.h` `PlayerItemBlockList`（proto 载体 `proto/common/component/item_comp.proto` `PlayerItemBlockComp`）；每玩家币种 `CurrencyComp.blocked_types`
- client messages: none（94 / 95 改币种封禁，见 currency-client-and-gm）
- tables: none
- depends on: none
- behavior: 入口顺序：全服封禁 → 每玩家封禁 → 正常逻辑。币种被封 → 27005；物品被封 → 1005。全服名单 `BlockGlobal/UnblockGlobal/ClearAllGlobalBlocks` 设计为「紧急止血：所有人不得再获得物品 30045」。
- internal: 全服名单是 **thread_local** 集合，没有任何加载 / 热更 / GM 写入路径（`BlockGlobal` 只在单测里调用）；每玩家物品名单 `PlayerItemBlockList` 全仓没有 emplace，`PlayerItemBlockComp` 也不进存档。Java 若做：全服名单应来自配置中心（Nacos）或 Redis 并在所有场景节点生效，每玩家名单进玩家记录。
- java: partial（2026-10-03，批次 2.3c）— 全服币种名单在 Redis `xm:gain-block:currency`，xm-data 运维接口读写（令牌 + 操作人 + 原因），scene `GainBlockSync` 经 pub/sub + 10s 周期同步到逻辑线程；判定顺序同基线（全服 → 本人 → 入账，都是 27005）；物品名单 `xm:gain-block:item`（批次 2.4，被封回 1005 同基线口径）。每玩家物品禁发基线从未挂载，不做。对应 hazard ①（Java 有写入口、全节点生效）
- size: S
- robot: none
- hazards: ① 生产上**完全不可用**：全服名单无写入口且按线程隔离（多线程 scene 上只封一个线程）；每玩家物品名单从未挂载；只有币种的每玩家封禁（94 / 95）真正生效；② 币种 / 物品的拒绝码不一致（27005 vs 1005）。

### transaction-log — 资产流水（Kafka 审计）
- mmorpg: `cpp/libs/modules/transaction_log/transaction_log_system.{h,cpp}`、`cpp/libs/modules/audit/audit_topic.h`、`proto/common/rollback/transaction_log.proto`；消费者 `go/data_service/internal/kafka/consumer.go`（写全局库 `transaction_log` 表）、`go/data_service/etc/data_service.yaml:195-215`
- client messages: none
- tables: none
- depends on: id-segment-client（tx_id 走 `txlog` 号段）、Kafka
- behavior: 每次货币增减、补缴抵扣、物品创建 / 销毁 / 淘汰 / 整理退役 / 转移都发一条 `TransactionLogEntry{tx_id, timestamp(秒), tx_type(27 种), from/to_player, item_uuid, item_config_id, item_quantity, currency_type, currency_delta, balance_before/after, correlation_id, extra, zone_id}`；topic 有效名 = `transaction_log_topic_g<N>`（N 来自部署配置 `AuditTopicGeneration`，0 当 1，与 Go 消费端同一规则）；分区键 = from_player 否则 to_player（按玩家有序）；生产者盖 zone_id。fire-and-forget：拿不到 tx_id 时**整条丢弃**（fail-closed，记 ERROR），Kafka 发送失败只记日志。
- internal: Java 版有自己的存储且不与 Go data_service 混部，所以 Java 需要：自己的流水 topic（或直接异步批量写 MySQL `xm_java` 的流水表）、全局唯一 tx_id（Java 现成 `com.game.common.id.Snowflake` + 节点号租约即可，不必照抄号段）、按玩家分区有序。只是审计，不影响玩法结果。
- java: done（2026-10-03，批次 2.3a）— 货币变动经 `CurrencyService` → `KafkaAssetAudit` → `AuditPipeline` 发 `xm-transaction-log-g<N>`，xm-data 落 `transaction_log`；号用全服号段租约上的雪花（不照抄号段）；没被确认的完整写兜底日志。物品类流水随背包批次
- size: M
- robot: none（Go 侧只有单测）
- hazards: ① 流水是「尽力而为」：Kafka 不可用或号段耗尽时资产照改、流水丢失，回滚 / 追溯链会断；② `timestamp` 只到秒，同一秒多条要靠 tx_id 排序，而号段 tx_id 不保证时间序；③ 裸 topic 名会被 broker 自动建成 1 分区，消费端分区契约永久失配——世代后缀是硬约束。

### anomaly-detector — 获取异常检测（滑动窗口告警）
- mmorpg: `cpp/libs/modules/transaction_log/anomaly_detector.{h,cpp}`；调用 `CurrencySystem::AddCurrency`、`BagService::AddItem(s)`；`player_lifecycle.cpp:1800` 下线清桶
- client messages: none
- tables: none
- depends on: Kafka
- behavior: 每玩家 × 每币种 / 每物品 config 一个滑动窗口（默认 600s 内 > 50 次或累计 > 100000 即告警），告警打 WARN 并发 JSON 到 Kafka `anomaly_alert_topic`（无世代后缀）。只告警、不拦截、不影响玩法。
- internal: 阈值全是 thread_local 默认值，没有任何配置加载；Java 可用 Micrometer 计数 + 日志告警替代，**禁止**把 player_id 作为指标 label（AGENTS §5）。
- java: partial（2026-10-03，批次 2.3c）— `GainAnomalyDetector`：每玩家 × 每币种滑动窗口（挂在玩家实例上，hazard ③ 不存在），阈值来自配置、可按币种覆盖，越线只告警一次（hazard ②），告警走日志 `xm.audit.anomaly` + 指标（不带玩家号；hazard ① 不发 Kafka）；物品窗口随批次 2.4 接入（与币种分开存）
- size: S
- robot: none
- hazards: ① `anomaly_alert_topic` 在 Go / Java 全仓**没有消费者**，告警只在日志里有用；② 超阈值后每次获取都再告警一次（无去抖），刷怪 / 批量领奖时会刷屏；③ 桶按 `entt::entity` 而非 player_id 索引，实体复用依赖下线时 ClearPlayer。

### player-snapshot — 玩家快照（登录 / 登出，回滚兜底）
- mmorpg: `cpp/libs/modules/snapshot/snapshot_system.{h,cpp}`；调用 `player_lifecycle.cpp:2425`（SNAPSHOT_LOGIN）、`:1988`（SNAPSHOT_LOGOUT）；`proto/common/rollback/player_snapshot.proto`；消费者 `go/data_service/internal/store/snapshot_store.go`（全局库 `player_snapshot` 表）
- client messages: none（GM 103 GmCreateSnapshot / 115 GmListSnapshots / 116 GmPreviewRollback / 112 GmExecuteRollback 是空桩；DataService 96–101 是服务端内部 RPC）
- tables: none
- depends on: id-segment-client（`snapshot` 号段）、Kafka、完整玩家 marshal（含 bag / mission / currency）
- behavior: 登录完成与登出时把 `player_database` 与 `player_database_1` 两个 blob 整份序列化，包成 `PlayerSnapshotEntry{snapshot_id, player_id, snapshot_time, trigger, 两个 blob, schema_version="v1", zone_id, total_bytes}` 发 `player_snapshot_topic_g<N>`（键 = player_id）。号段没号或 Kafka 失败就跳过（fail-closed，记 ERROR），不影响正常存盘。
- internal: Java 若要回滚能力：在写回同一时刻把玩家记录整份拷到 `xm_java.player_snapshot`（异步、可丢），ID 用 Java Snowflake。回滚执行本身属于 data_service / GM 运维面（另一区域），基线 scene 侧也还没实现。
- java: done（2026-10-03，批次 2.3b）— `SceneWorld` 进场成功拍 LOGIN、离场 / 停服写回拍 LOGOUT（与写回同一份 `PlayerSave`），经 `AuditPipeline` 发 `xm-player-snapshot-g<N>`；号用全服雪花；序列化后超 `xm.scene.snapshot-max-bytes` 丢弃并计数（对应 hazard ③）；不记 total_bytes（hazard ①）
- size: S
- robot: none
- hazards: ① `total_bytes` 是第一次序列化时的长度，写进字段后再序列化长度会变（varint 位数），记录值比实际小几个字节；② 快照只在登录 / 登出两个时刻，长在线玩家没有中间点；③ 无压缩，整份玩家记录进 Kafka，玩家记录变大时单条消息可能超 broker 上限。

### id-segment-client — 永久 GUID 号段（item / txlog / snapshot）
- mmorpg: `cpp/libs/modules/id_segment/{guid_segment_client.{h,cpp},guid_segment_registry.{h,cpp}}`、`cpp/nodes/scene/id_segment_bootstrap.{h,cpp}`、`cpp/nodes/scene/main.cpp:323`（DependencyGate「id segments ready」挡玩家进入）；Go 端 `go/shared/idsegment/client.go`、DataService.AllocateIdSegment（全局库 `id_segment` 表 CAS）
- client messages: none（但 item guid 经 191 的 `item_id` 对客户端可见）
- tables: none（部署配置 `IdSegments`：Kind + initial/min/max step）
- depends on: data_service（另一区域）
- behavior: 每种 GUID 一个 Leaf-segment 客户端：双 buffer（当前段 + 预取段，剩余 ≤ 10% 预取，单飞）、两段都空 `TryNext` 立刻返回 false（事件循环不阻塞）；动态 step（一段撑不到 15 分钟翻倍、超过 30 分钟减半）；服务端范围校验 lo≥1、hi>lo、hi≤2^55、lo≥上一段 hi，违反即拒用；传输失败 500ms→5s 指数退避；不持久化游标（崩溃只留空洞）。号段从 1 起、< 2^55，与存量 snowflake 号（≥ 6.7e16）不相交。
- internal: 物品入包要先 `CanMintGuids(n)` 整批预检，铸号失败回 6004。Java 已有 `xm-common` `Snowflake` + `xm-discovery` `NodeIdLease`（租约失效即停发号），可直接作为 item / tx / snapshot 的 ID 源，不需要号段服务；要求同样「拿不到号就整批拒绝、不改状态」。
- java: partial — ID 源（`com.game.common.id.Snowflake`、`NodeIdLease.isValid()`）已有并用于 player_id（`xm-login/.../PlayerIdGenerator.java`）；scene 侧未接物品 / 流水 ID，也无「ID 源就绪才放人进场」的门禁
- size: M（照抄号段）/ S（Java 用现成 Snowflake）
- robot: none
- hazards: ① item guid 是**客户端可见**的 `BagItemInfo.item_id`（uint64），Java 用 Snowflake 时值域与基线不同但客户端只当不透明 ID，可接受；② 基线号段 ID 非时间序，不要依赖 guid 大小推断入包先后（用 `acquire_seq`）；③ 首段是硬依赖：data_service 不可用时 scene 不放玩家进入。

### gm-rollback-rpcs — GM 回滚 / 欠款 / 追溯指令（102–117 scene 侧）
- mmorpg: `cpp/nodes/scene/handler/rpc/player/player_rollback_handler.cpp`、`proto/scene/player_rollback.proto`（服务 `SceneRollbackClientPlayer`，只标 `OptionIsPlayerService`，**未标** `OptionIsClientProtocolService`）
- client messages: none（102 GmAttachDebt、103 GmCreateSnapshot、104 GmTraceItem、106 GmWaiveDebt、107 GmQueryDebt、109 GmFreezeDebt、110 GmClawbackItem、112 GmExecuteRollback、113 GmAdjustDebt、115 GmListSnapshots、116 GmPreviewRollback、117 GmQueryTransactionLog 有消息号但 gate 不放行）
- tables: none
- depends on: currency-debt-clawback, player-snapshot, transaction-log
- behavior: 十二条全部是 `TODO(P1-B)` 空桩：只做 GM 运行模式闸（非 dev/test → 1006）与写类的冻结 / 战斗中闸（→ 1005），然后返回空应答。gate 只放行客户端协议服务，所以客户端发这些号会被 gate 拒掉。
- internal: 真正的回滚 / 回收走 data_service 运维面（HMAC 签名 + 审计日志），属另一区域。
- java: done（等价）— Java gate `xm-gate/.../session/MessageRoutes.java` 同样只放行标了 `OptionIsClientProtocolService` 的方法，客户端发 102–117 被 gate 拒；scene 侧无需空桩
- size: S
- robot: none
- hazards: scene 的节点路由（`InvokePlayerService`）能绕过 gate 直达这些 handler，基线因此在每条前面加了 GM 闸；Java 若以后加 scene 内部 RPC 路由，同样要在 scene 侧再挡一次。

### mission-core — 任务核心（接取门禁 / 进度 / 完成 / 链式 / 领奖位）
- mmorpg: `cpp/libs/modules/mission/{system/mission.{h,cpp},comp/mission_comp.{h,cpp},comp/missions_config_comp.h}`、`services/scene/player/system/player_mission.{h,cpp}`（正式写入口）、`cpp/nodes/scene/handler/event/mission_event_handler.cpp`、`engine/core/utils/bit_index/bit_index_util.h`（`GetRewardAction`）；单测 `cpp/tests/missions_test/missions_test.cpp`、`cpp/tests/bag_test/player_mission_system_test.cpp`
- client messages: none 直接（193/194/195 见 mission-client-rpcs）；**没有任何任务进度 S2C 推送**，客户端只能重拉 193
- tables: Mission（`condition_id[6]`、`mission_type`、`mission_sub_type`、`condition_order`、`auto_reward`、`reward_id`、`next_mission_id[1]`、`target_count[2]`，id 有 bit index）、Condition、Reward、Item、Dungeon、Monster、ActivitySchedule
- depends on: condition-eval, mission-progress-sources, mission-reward-grant, activity-list（type 2 的开放窗口）, 跨节点冻结
- behavior: 只开放 scope 0（普通任务；1 成就、2 日常只保存不操作，其它 scope → 1005）。接取门禁 `CheckAccept`（顺序即错误码优先级）：实体 / guid 无效或冻结 → 1009 / 1005；无任务组件 → 1003；表无此 id → 1001；无 bit 位 / 无条件 / `condition_order>1` → 1002；已接 → 5004 `kMissionIdRepeated`；已完成或待领 → 5001；同 (type, sub_type) 已有进行中 → 5000 `kMissionTypeAlreadyExists`；条件查不到 / 比较符不是 ≥ > == / 目标为 0 → 1002；条件类别不是 1 击杀 / 6 等级 / 8 完成任务、`valid_duration≠0`、用了 condition2–4 槽、击杀类无「副本里真实可达的怪」、`quantity_type>1` 或非等级类用 quantity_type=1 → 1003；奖励表不可发 → 1002；type 2 不在开放窗口 → 1006。
  接取后为每个条件建一格进度并按条件类别建索引，记 `mission_begin_time`，立即回填当前等级与「已完成任务」历史事实（只推进新接的这一个，历史每个任务至多贡献一次）。完成：所有槽达标 → 从进行中移除、置完成位；有奖励时 `auto_reward=1` 先置待领位再投递自动发奖事件，否则只置待领位；投递 `next_mission_id` 的自动接取（同样走完整门禁）与「完成任务 X」条件事件。顺序目标（`condition_order=1`）一条事实只推进当前第一个未达标格。
- internal: 状态全在玩家内存，随玩家记录保存（见 mission-persistence）。Java 需要：场景线程内的任务状态（进行中 map + 完成 / 待领位集合 + (type,subtype) 占用集 + 条件类别 → 任务索引）。
- java: missing
- size: L
- robot: Go `robot/features_smoke_scenario.go`（`accept_mission_id` / `claim_mission_id` 显式选项）；Java none
- hazards: ① **生产上 `tlsEcs.dispatcher.update()` 从未被调用**（全仓只有单测调用；生产里仅有的三处 `enqueue` 都在 `mission.cpp:358/374/383`），所以 enqueue 的三类事件永远不派发：自动发奖退化成「待领、需玩家手动 195」，链式后续任务不会自动接取（玩家可在列表里手动接），「完成任务 X」条件对**已在进行中**的任务不会实时推进（只在接取时回填），且队列无限增长（内存泄漏）。Java 应按语义同步执行，并在 PARITY 记为有意差异；② `E_MISSION_TIME_OUT / E_MISSION_FAILD` 没有任何代码会设置；③ `AbandonMission` 存在但无客户端协议；④ `CompleteAllMissions`（GM 批量完成，无副作用）与正常完成路径不能合并。

### condition-eval — 条件判定（Condition 表）
- mmorpg: `cpp/libs/modules/condition/{condition_type.h,condition_util.{h,cpp}}`、`mission.cpp` `UpdateProgressIfConditionMatches`
- client messages: none（`MissionObjectiveInfo.category / target / progress / completed` 可见）
- tables: Condition（`condition_category`、`condition1..4`（5/4/2/2 槽）、`valid_duration`、`quantity_type`、`target_count`、`comparison_op`）、Mission.target_count（按目标下标覆盖）
- depends on: none
- behavior: 比较符 0 `>=`、1 `>`、2 `<=`、3 `<`、4 `==`，越界视为不满足；目标 = Mission.target_count[i]（>0 时）否则 Condition.target_count。槽匹配：每个**非空**槽 conditionK 必须包含事件的第 K 个 id（槽内 ANY、槽间 ALL），全空槽匹配任何事件，事件不带 id 一律不推进。等级类（6）特殊：condition1 是等级门槛表（事件等级 ≥ 任一即可），进度 = 当前等级（覆盖而非累加）；`quantity_type=1` 也是覆盖语义；累加型在目标处饱和（`>` 饱和到 target+1），封顶 u32。
- internal: 纯函数，Java 一个工具类即可。
- java: missing
- size: S
- robot: none
- hazards: ① 枚举里还有 2 对话 NPC、3 完成指定条件、4 使用物品、5 交互、7 自定义，均无事实来源，接取门禁直接挡掉；② `ClampIfFulfilled` 有实现无生产调用。

### mission-progress-sources — 任务进度事实来源（击杀 / 升级 / 完成任务）
- mmorpg: `services/scene/battle/system/player_battle.cpp:1755-1766`（回合制战斗最终结算的 `defeated_monsters`，每只怪一条事实）、`cpp/nodes/scene/handler/event/player_event_handler.cpp:58-63`（升级事件 → 等级事实）、`mission.cpp:378-383`（完成任务事实）、`player_mission.cpp:140-185`（接取时回填）
- client messages: none
- tables: Condition、Dungeon（`monster` 列）、Monster
- depends on: 回合制战斗结算（另一区域）、等级变化（属性区域的 175 GmSetPlayerLevel；经验系统未接）、mission-core
- behavior: 击杀只认战斗最终结算里的真实击杀（受结算幂等账本保护，重投不会重复推进）；升级事实带新等级；冻结中的玩家丢弃条件事件。
- internal: Java 需要一个场景线程内的「玩法事实」分发点（击杀 / 等级 / 任务完成），任务系统订阅。
- java: missing — Java 无战斗、无等级变化来源（`PlayerData.level` 只读不变）
- size: S
- robot: Go `features_battle_smoke.go`（打怪推进击杀任务）
- hazards: 实际可推进的只有击杀（需战斗）与等级（只有 GM 改等级）；Java 在战斗与等级系统落地前，任务只能接、不能完成。

### mission-client-rpcs — 任务列表 / 接取 / 领奖（193、194、195）
- mmorpg: `cpp/nodes/scene/handler/rpc/player/player_mission_handler.cpp`、`services/scene/player/system/player_feature_snapshot.cpp`（`PlayerMissionReadSystem::BuildList`、`FillMission`）、`proto/scene/player_mission.proto`；客户端 `PlayerFeaturesClient.cs:130-235`
- client messages: 193 SceneMissionClientPlayerGetMissionList (C2S)、194 SceneMissionClientPlayerAcceptMission (C2S, 应答 GetMissionListResponse)、195 SceneMissionClientPlayerClaimMissionReward (C2S, 应答 GetMissionListResponse)
- tables: Mission、Condition、Reward、ActivitySchedule
- depends on: mission-core, condition-eval, mission-reward-grant, activity-list
- behavior: 列表 = 任务表全部行（scope 0）∪ 存档里每个 scope 的进行中 / 完成 / 待领 id（含表里已删除的），按 (scope, id) 升序。每条 `PlayerMissionInfo`：状态 CLAIMABLE > COMPLETED > ACTIVE（或 FAILED）> NOT_ACCEPTED；`configured=false` 时 reason「任务配置暂不可用」；`can_accept / can_claim` 复用正式门禁的只读检查；不可操作时给中文 `unavailable_reason`（「完成任务目标后领取奖励」「任务已完成」「任务已失效」「当前状态暂不可领奖，请稍后重试」「请先完成同类型任务」、type 2 用排期原因、1003 →「任务所需玩法暂未开放」、其它「当前条件下暂不可接取」）；objectives 按目标下标逐格给出 category / target / progress / completed（已完成任务不伪造计数，只给 completed=true），`name / description` 恒空；`state_persistent` = 有任务组件。
  194 / 195 成功后回整份新列表；失败回 tip（错误码见 mission-core / mission-reward-grant），列表为空。客户端：领奖成功后自动重拉 191。
- internal: Java 在 `ClientRequestHandler` 加三个方法；时间用服务器 UTC 毫秒。
- java: missing — 当前回 1006
- size: M
- robot: Go `features_smoke_scenario.go`；Java none
- hazards: ① 失败时 194 / 195 不带列表，客户端须重拉 193；② 每次 193 都对**全表**跑一遍接取 / 领奖门禁（含遍历 Dungeon×Monster 判可达），表大时是热点；③ 字符串原因是客户端直接展示的文案，Java 必须逐字一致。

### mission-reward-grant — 任务奖励发放（Reward 表 → 背包）
- mmorpg: `player_mission.cpp` `BuildRewardItems / CheckClaim / ClaimReward`、`mission_event_handler.cpp` `OnMissionAwardEventHandler`；`cpp/libs/modules/reward/**`（`RewardClaimSystem` / `RewardComp` / `IRewardableConfig`，只有头文件，**生产无调用**）
- client messages: 195（与自动发奖）
- tables: Reward（`reward[2]{reward_item, reward_count}`）、Item、Mission.reward_id / auto_reward
- depends on: bag-orchestration-service, mission-core
- behavior: 奖励只解释为**物品**（不发货币）：每条 item / count 必须非 0 且物品存在、同物品累加不溢出，否则 1002；奖励为空 1002。领奖资格：不在待领 → 已完成回 12000 `kRewardAlreadyClaimed`，否则 5002 `kMissionIdNotInRewardList`；待领但完成位缺失（存档不一致）→ 1002；`reward_id=0` → 1002；无背包组件 → 1003。发放进人物背包（kInventory，不淘汰），流水 TX_QUEST_REWARD；整批预检失败（满包 6005 等）不改包、不消耗领取权；**发放成功后才清待领位**（同一事件循环内同步，无竞态窗口）。
- internal: Java 同样「先入包、后清领取位」，两者随同一次写回落盘。
- java: missing
- size: S
- robot: Go `features_smoke_scenario.go`（claim 选项）
- hazards: ① 批量入包失败可能已部分写入（`mutated`），但领奖路径不看 `mutated`，重试会重复发放已入包的那部分——人物背包不淘汰，部分写入只在多物品奖励中途铸号失败时出现，概率低但存在；② reward 模块的 bitset 工具是死代码，不需要移植。

### mission-persistence — 任务持久化与旧格式迁移
- mmorpg: `services/scene/player/system/mission_marshal.{h,cpp}`、`player_database_loader.cpp:110/152`、`proto/common/database/bag_quest_mail_data.proto`（`QuestAllData` / `QuestScopeData` / `QuestEntry`）、`proto/common/component/mission_comp.proto`
- client messages: none
- tables: Mission（还原旧格式时补齐进度槽数）
- depends on: mission-core
- behavior: 存：`scoped_state_present=true` + 每个 scope 一条 `{scope, mission_list（进行中 + begin_time，清掉 complete_missions 位图字节）, completed_mission_ids, claimable_mission_ids}`（id 列表升序，含表里已删除的任务 id，避免历史状态被静默丢弃）。取：新格式按 scope 还原；旧格式（`active` / `completed`）迁移：state 3 = 已领、state 2 = 已完成待领、其余为进行中并补齐进度槽；最后重建条件 / 类型索引并确保 scope 0 存在。
- internal: 与背包、货币同一条玩家记录同一次写回。
- java: missing
- size: S
- robot: none
- hazards: ① 完成状态以「任务 id 列表」持久化，运行时用 bit index（表里没有 bit 位的 id 进 unmapped 集合）；② 旧格式迁移只有 Go/C++ 历史存档需要，Java 新库不需要。

### activity-list — 活动目录（190 GetActivityList）
- mmorpg: `cpp/nodes/scene/handler/rpc/player/player_activity_handler.cpp`、`services/scene/player/system/{player_feature_snapshot.cpp (PlayerActivityReadSystem),player_activity_schedule.{h,cpp}}`、`proto/scene/player_activity.proto`；客户端 `PlayerFeaturesClient.cs:155-180`
- client messages: 190 SceneActivityClientPlayerGetActivityList (C2S)
- tables: Mission（`mission_type == 2` 的行即活动，activity_id = mission_id）、ActivitySchedule（`id` 外键到 Mission、`enabled`、`baseline_start_at_ms`、`baseline_end_at_ms`；3 行）
- depends on: mission-core（`CheckAccept` 决定 can_participate；194 对 type 2 也查开放窗口）
- behavior: 回 `{activities（按 activity_id 升序）, server_time_ms}`。状态：无排期行或 `enabled=false` → UNSCHEDULED「活动尚未排期，敬请期待」；now < start → UPCOMING「活动尚未开始」；now ≥ end → ENDED「活动已结束」（区间左闭右开）；否则 OPEN。排期配置非法（start=0 而 end≠0、end ≤ start、enabled 但窗口全 0、id 不匹配）→ 该条清空并给「活动配置异常，暂不可参与」。OPEN 但不能接：已接 / 已完成 →「请在任务页查看活动进度」、同类型 →「请先完成同类型活动任务」、其它 →「活动所需玩法或当前状态暂不满足参与条件」。name / description / icon_key 恒空。
- internal: 纯读，时间源用服务器 UTC 毫秒（Java 用 `SceneClock` 同源时钟以便单测）。
- java: missing
- size: S
- robot: Go `features_smoke_scenario.go`（读活动）
- hazards: ① 窗口用绝对毫秒（`baseline_*`），没有周期 / 每日重复语义；② 排期配置异常时该条目 `activity_id` 也被清成 0，客户端会看到一条 id=0 的活动。

### asset-op-channel — 通用资产通道（scene 侧 AssetDebit / AbortDebit / Credit）
- mmorpg: `services/scene/player/system/{asset_op_system.{h,cpp},asset_op_ledger.{h,cpp},asset_op_auth.{h,cpp}}`、`proto/common/asset/asset_op.proto`、SceneNodeGrpc（scene 节点 gRPC）；调用方 go guild / trade
- client messages: none（服务端 RPC；结果经帮会 / 聚宝斋协议间接可见）
- tables: Item
- depends on: bag-orchestration-service（AddItems / RemoveItemsByGuid 的 `mutated`）、currency-core、gain-block、transaction-log、玩家存盘与 durable 确认
- behavior: 按 (player, stream, stream_epoch, seq) 幂等：验 HMAC（调用方密钥来自环境变量 `MMORPG_ASSET_OP_SECRET_<CALLER>`，时钟偏差 ≤ 300s）→ 找人（不在本节点 NOT_HERE）→ 查账本（已办过只回原结局）→ 闸门（冻结 / 战斗中 / 退出中 / 背包满 → RETRY 不记账）→ 改资产 → 记账本 → 触发存盘，回 `durable`（重查限频 500ms）。结局 APPLIED（可 partial）/ REJECTED / RETRY / NOT_HERE / UNKNOWN。账本与资产同一条玩家记录同一次落盘；加载时账本损坏 → 该玩家资产通道 fail-closed。
- internal: 属帮会 / 聚宝斋区域的跨服务资产协议；Java 若做，用 Dubbo 接口 + 同样的账本幂等与 `owner_epoch` 写回围栏，密钥走环境变量。
- java: missing
- size: L
- robot: Go `robot/guild_economy_smoke.go`
- hazards: ① 闸门只能放在 RPC 入口层，不能下沉到 BagService / CurrencySystem（会卡死战斗结算）；② 「失败 ≠ 未改动」必须用 `mutated` 区分 RETRY 与 APPLIED+partial，否则重复发放或永久少发；③ 详细清单应由帮会 / 交易区域的清单覆盖，这里只登记对背包 / 货币模块的依赖。

### scene-module-comps — 场景模块组件（实体所属场景 / 场景注册表 / 节点状态）
- mmorpg: `cpp/libs/modules/scene/comp/{scene_comp.h,scene_node_comp.h}`；使用方 `player_scene.cpp`、`player_lifecycle.cpp`、`spatial/system/{aoi,grid,interest,nav_query,scene_spawn}.cpp`、`player_team*.cpp`
- client messages: none
- tables: none
- depends on: none
- behavior: `SceneEntityComp`（实体 → 所在场景实体）、`SceneRegistryComp`（config_id → 场景实例集合）、`NodeStateComp` / `NodePressureComp`（节点正常 / 压力状态；压力状态在 scene 内无读者）、节点类型标签。
- internal: 只是 ECS 数据定义。Java 的对应物已按 Java 方式实现：`xm-scene` `SceneWorld` / `Scene` / `ScenePlayer`（玩家所属场景、按配置查场景实例），场景选择在 `xm-scene-manager`。
- java: not_applicable — 等价能力已在 `xm-scene/src/main/java/com/game/scene/world/{SceneWorld,Scene,ScenePlayer}.java` 与 xm-scene-manager 中，无需单独移植
- size: S
- robot: none
- hazards: 无。

## Open questions

1. **dispatcher 队列从不派发**：生产代码没有 `tlsEcs.dispatcher.update()`（`generated/code/cpp` 未检出，需在完整检出里再确认）。若属实，基线的自动发奖、链式接取、「完成任务 X」实时推进都不生效。Java 按语义同步实现（自动发奖、自动接续）会与基线行为不同——需要和 mmorpg 定口径：是 mmorpg 修 bug，还是 Java 也故意不做？
2. **GetCurrencyList / GetBag 泄露 debts**：54 与 191 把加载时的 `CurrencyComp`（含 `debts.gm_operator / reason`、`blocked_types`）原样发给客户端。Java 是否照发（字节兼容），还是只发 `values`（客户端只读 values）？建议 mmorpg 一起改成只发 values。
3. **余额溢出**：`AddCurrency` 无 uint64 溢出检查。Java 应拒绝（建议回 1005），需登记为有意差异或推动 mmorpg 修。
4. **装备穿脱 / 使用物品 / 丢弃 / 拆分 / 扩容**：mmorpg 都没有客户端协议。用户需求里提到「装备 / 卸下、使用物品」——要做得先在 mmorpg 定 proto 与消息号，再经 ContractSync 同步，不能 Java 先行。
5. **GlobalVariable 表**：C++ 玩法模块不读它（只有导表产物）；Java 已有 `GlobalVariableRows`，没有需要移植的行为。确认是否有其它区域（Go / 战斗）在用。
6. **物品 / 流水 / 快照 ID 源**：Java 打算直接用现有 `Snowflake` + 节点号租约（推荐），还是照抄号段服务？前者需要 scene 节点加「ID 源就绪才放人进场」门禁（对应基线 DependencyGate）。
7. **审计落点**：Java 没有 Kafka。资产流水 / 快照是先引入 spring-kafka，还是直接异步写 `xm_java` 的流水 / 快照表？（两者都只是审计，不影响玩法结果。）
8. **全服禁发**：基线是 thread_local 且无写入口，等于不存在。Java 是否需要真正可用的全服禁发（Nacos 配置推送）？若做属于有意增强。
9. **任务表数据的可达性**：Mission 17 行里有多少行能通过接取门禁（依赖 Dungeon×Monster 可达与条件类别 1/6/8）？Java 在战斗 / 等级系统落地前，任务只能接不能完成，验收口径需要先定。
10. **领奖部分写入**：多物品奖励中途失败时基线不看 `mutated`，重试可能重复发放；Java 是否要先整体预检铸号（Snowflake 不会耗尽，问题基本消失）并在 PARITY 记录？

