# 战斗、匹配与持久结算移植库存（2026-10-26）

Java 以 `03a0708d` 加本分支当前源码为对象；C++ / Go 固定 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`，通过 `git show` 读取。本文核对生产调用链，不把旧 PARITY 的“Java 待做”当作当前事实，也不把找到类或测试文件当成测试通过。本轮新增代码尚未编译、运行单测或连接 TiDB / Redis / Kafka；后续统一构建及独占实环境结果应另附证据。

下文的 PARITY 行号指盘点时的原条目，标题才是稳定索引。Java 路径采用模块内包名缩写，例如 `game-match/global/GlobalMatchStore` 指 `game-match/src/main/java/com/game/match/global/GlobalMatchStore.java`，测试同理。

## 1. 已接通的生产主链

当前生产配置强制全球匹配和 TiDB 强读：`game-match/global/GlobalMatchConfiguration.globalProductionGate`、`game-battle/BattleConfiguration` 的运行模式门禁。全球成局的 `GlobalMatchService.execute` 先持久完整 Create 原件和能力证据，再发固定实例 Create，并请求 `outcome_protocol_version=1`。`CreationLedger` 区分支持能力与真实 adopted 回执，保留 UUID、实例和准入截止围栏。

已算出终局的 battle 房间经 `BattleConfiguration.SignedOutcomeSink` 签名调用 match。`GlobalMatchStore.commitOutcome` 在一笔 SQL 事务内校验原成局、账号 / revision、票据、固定实例和原字节，提交唯一 COMPLETE、完整全局来源及全员个人来源。`resolveTerminalAfterDeadline` 按原权威截止和固定宽限竞争 ABORT，不因工作租约续租而延后。

`RatingConfiguration` 真实装配并启动 `BattleCompletionReplay`。它分别重投原全局 Kafka 字节和个人 Inbox 来源；每轮截止、全局、首投个人及等待 APPLIED 各有独立限额和分页游标，坏行不永久霸占队头。`PlayerInboxProvider` / `InboxStore.battleSettlement` 核验签名、原请求摘要、账号 / revision、payload 摘要和同一 battle_id。SceneNode 装配 `InboxConsumers.withBattle`，`BattleSettlementService.applyInbox` 与 Inbox 水位在同一玩家存盘提交，真实 SAVED 之后才 ACK 与通知 150。

这一链路不会把 Redis 位置缺失、战斗锁消失、调用异常或 COMMIT 回包丢失当作玩家已领取、原局已死亡或确定回滚。它也不依赖旧 `SettlementOutbox` 的 Redis 待结算 TTL。

## 2. 逐项库存

| PARITY 条目 | 当前状态及生产接线证据 | 测试 / 验证入口与边界 |
| --- | --- | --- |
| 135 / 136 确定性战斗引擎与配表指纹 | 已有：`game-battle-engine/engine/TurnBattleEngine`、`BuffEngine`、`BattleRandom`；scene / battle 各自从真实表组装指纹，`FingerprintGuard` 与 gather 核验。 | `TurnBattleTraceTest`、`TurnBattleWireTest`、`TurnBattleRngAccountTest`、初始化 / 行动 / 技能 / 宠物 / 结算测试。原金样与断言保留；本轮装备增量仍待统一回归。 |
| 145 battle 节点、房间、票据、直连与观众 | 已有：`BattleNode`、`BattleRoomServiceImpl`、`BattleNodeServiceImpl`、`CreationLedger`、`BattleTicketIssuer`，真实控制面和直连面均走逻辑线程。 | `BattleNodeTest`、`FinishAndVoidTest`、房间 / RPC / edge 用例；真实 Redis 的 `BattleNodeShutdownEndToEndTest` 默认按开关执行。停服 v1 补提交是本轮新增，见 §3。 |
| 148 scene 战斗冻结、快照、重连、reaper | 已有：`game-scene/battle/PlayerBattleService`、`BattleSnapshots`、`SceneWorld` 的进场恢复与冻结判断。装备快照只从当前玩家装备加成输出 `combat`，基础属性仍独立。 | 备战 / 确认 / 冻结 / 重连 / Inbox 生命周期用例；本轮未重写所有权协议。旧 Redis 兼容投递的剩余另列 §4，不能据主链完成把它抹去。 |
| 149 结算链路；234 结果耐久确认先于可见；243 R07 全个人来源 | SQL v1 已替代“确认后逐人写 Redis”的生产全球路径：`GlobalMatchStore.commitOutcome`、`BattleCompletionStore`、`BattleCompletionReplay`、`InboxStore`、scene `applyInbox`。 | `GlobalResultCommitTest`、`BattleCompletionStoreCases` / `BattleCompletionStoreTest` / `BattleCompletionStoreTiDbTest`、`BattleInboxRecoveryTest`、`BattleInboxLifecycleTest`。必须额外实测 COMMIT 未知、Kafka ISR 不足、Inbox ACK 丢失及跨副本恢复，单测不等同集群验收。 |
| 150 活动结果持久通道“两版都不可达”；154 调用方随 4.6 | 旧状态已过时：`GuildActivityRuntime` 调用 `StartActivityBattle`；match `ActivityBattleService` 绑定原活动请求，全球结果与普通局共用 SQL 来源及 Kafka；`GuildActivityResults` / `GuildActivityStore` 消费并提交奖励来源。 | `ActivityBattleServiceTest`、`ActivityCancellationTest`、`GuildActivityRuntimeTest`、`GuildActivityResultsTest`、`GuildActivityStoreTiDbTest`。旧 `ActivityResultOutbox` 的故障重试缺口独立列出。 |
| 151 / 152 / 153 匹配、切磋、179 补签、落点 | 已有：`GlobalMatchService` / `GlobalMatchStore` 的原 Join、Challenge、Direct 和团队意图；`GatherPlan` / ScenePreparer 与固定节点；补签从原落点请求固定实例。 | `GlobalRequestIdentityTest`、`GlobalReadyRecoveryTest`、切磋和 gather 测试。已有 Create 的固定实例不可达仍保持未知，不能套用本轮“无原件出口”。 |
| 155 / 388 全球观战、满员、席位回收、计数、tip | 全球链已接：`GlobalWatchService`、`GlobalWatchAuthority`、`SqlSpectateStore`；battle `ObserverOperationLedger` / `BattleRoomServiceImpl` 按原操作准入与取消、登记 / 断线宽限回收，161 只数活直连，满员专用码。 | `GlobalWatchServiceTest`、`ObserverOperationRoomTest`、`ObserverOperationLedgerTest`、`ObserverTest`。**本地模式原操作尚未移植**，见 §4。分级转播与私密局扩大开放不能由本项推断。 |
| 156 / 201 跨区及区域优先等待扩区 | 已有：`GlobalRegionPolicy`、`GlobalRegionIndex`、`GlobalQueueUnits`，先逐人核完整探测身份 / 硬延迟 / 等待，再查候选和提交；不是只照旧 roadmap 的“两版待实现”。 | `GlobalRegionPolicyTest`、`GlobalQueueUnitsTest`、`GlobalMatchStoreIntegrationTest`；多机房故障与延迟真实性仍需部署验证。 |
| 161 装备战斗属性（6.7 部分） | 本轮源码已接：`EquipmentCombatRules`、`TurnBattleEngine`、`BuffEngine`，独立普攻 50% 连击、无连锁反击、存活反震实际伤害 50%、必杀 2 倍、总减伤 60%、技能等级、睡眠 30 / 遗忘 31；`BattleViews` 对外裁剪。 | `EquipmentCombatRulesTest`、`TurnBattleEquipmentTest`、`EquipmentAilmentTest`、`EquipmentVisibilityTest`。0 字段 presence 与旧 RNG / 金样保留；详见 `../equipment-battle-java-20261026.md`，本轮未跑构建。装备生产 / 合成 / 穿脱由对应装备库存负责。 |
| 163 PVE 人数档 | 已有：`GlobalJoinSizeTest` 对应的入队校验、`GlobalQueueUnits` 的目标人数与原团队单元；不能因旧条目重造。 | `GlobalJoinSizeTest`、`GlobalQueueUnitsTest`、真 TiDB 成局并发用例；客户端和多副本全链需统一验收。 |
| 165 / 232 / 236 帮会活动、SQL 原身份及强读 / RF3 | 已有：`GuildActivityRuntime`、`GuildActivityStore`、`TrialLaunchAuthority`、`ActivityStartupGate`。活动调用与奖励账不再只是接口占位。 | `GuildActivityStoreTiDbTest`、`ActivityStartupGateTest`、`TrialLaunchAuthorityTest`。涉及其他活动产品规则的部分由帮会库存覆盖。 |
| 174 缺省回合上限 300 | 已有引擎与评分来源校验；不需要本轮重复改。 | `TurnBattleRoundTest`、评分用例。 |
| 188 玩家持久收件箱 v1 | 已有完整独立模块、RPC、鉴权、数据库迁移、后台提醒 / 对账 / leader 循环：`InboxConfiguration`、`PlayerInboxProvider`、`InboxStore`、`InboxMaintenance`。scene `InboxService` 已真实装配，效果与水位随 owner_epoch 存盘。 | `InboxRouteFenceTest`、`InboxOwnerAdmissionTest`、`BattleInboxRecoveryTest`、scene `InboxServiceLifecycleTest` / `InboxFinalSaveLifecycleTest`。`InboxMaintenanceStore.sweepTerminal` 当前明确不删除永久原唯一收据，不能把配置保留期当成自动删单语义。 |
| 190 落地恢复 / 超窗处置的战斗部分 | 全球 battle v1 原来源永久留存，`BattleCompletionReplay` 查询实际 APPLIED，scene 进场拉 Inbox；普通 VOID / 强制 VOID 不允许处置战斗奖励（`InboxAdminStore`）。旧 Redis settlement 的 TTL 差距仍在 §4。 | `BattleInboxRecoveryTest`、`BattleInboxLifecycleTest` 已有 SQL 水位与旧环淘汰重放用例；本条 guild / trade 的 wake、180 天 ABANDONED 与人工处置是独立资产通道范围，本文不宣称全部完成。 |
| 192 ECS 初始化与缺组件传播 | C++ ECS 模板机制不适用于 Java。实际语义应看 `PlayerState` 恢复 / 验证、`PlayerBags` 整包候选、Inbox 坏数据拒绝、owner_epoch 存盘围栏。 | `BattleInboxLifecycleTest` 已覆盖未知组件、首效果前候选失败、异常后丢弃未持久实体；不以“Java 不用 ECS”掩盖业务缺组件时的 fail-closed 要求。 |
| 204 C++ Kafka broker 注册 / C++ 处理器生成 | 框架机制不适用：Java `KafkaBattleResultSink`、`RatingConfiguration` 用显式配置和 TopicAdmin；不能照搬 C++ Kafka.GroupID 初始化。 | Kafka 配置 / 耐久门禁及集成入口；真实 RF3 / minISR 故障另验。 |
| 288 结算三处丢失：①号段 ②金币封禁 ③个人来源先持久 | ①生产 Inbox 已用 `BagService.prepareBattle` 在首效果前构造整包候选并预留 GUID；②本轮只对 `Wallet.BLOCKED` 跳金币，其余奖励、水位照常提交，溢出 / 冻结仍延后；③全球 v1 来源和 COMPLETE 同事务，早于公开终局。 | `BattleInboxLifecycleTest` 的号段失败、opaque 包保留、本人 / 全服封禁、原 ID 重登重放、溢出拒绝；`GlobalResultCommitTest` / SQL 来源测试。旧 `applyOnce` 兼容路径不因本轮生产 Inbox 修复自动等价。 |
| 330 匹配到期票提交点；374 消费失败策略 | 已有：GlobalMatchStore 提交点复核原探测 / 队列 / 账号资格；`RatingConfiguration` 默认全球 SQL_CONFIRMED，异常不提交 Kafka 位点。 | `GlobalMatchStoreIntegrationTest`、`RatingConfigurationTest`、结果消费与来源测试。 |
| 391 无 Create 原件安全出口 | 本轮补 `GlobalMatchStore.finishUncreated` 和 `GlobalMatchService.abort`：同事务核工作代次、原账号票据、原件为空且无 completion，按原回队合同完成；返回持久 prepared 原端点供取消，不依赖目标 Battle 存活。 | `GlobalUncreatedExitTest` 和真 TiDB gated 的失租、原 ID 重放、原件 / completion 非空拒绝、owner 变更、Create vs 退出竞争用例。**畸形过期成局只读巡检尚缺**，见 §4。 |
| 392①全球 v1 SQL 终局 | 已有，旧条目的“仍是确认后落 pending”已过时；证据见 §1。 | `GlobalResultCommitTest`、SQL 来源 / replay / deadline 测试。 |
| 392②停机有界补提交 | 本轮新增 `BattleRoomService.beginShutdown` / `ShutdownReport`、真实房间保留原 v1 pending、节点等待后再拆除；不抢占在途提交，不把预算耗尽改成 ABORT。 | `GlobalResultCommitTest`、`BattleNodeTest` 新增确定性 Future / 虚拟时钟用例；原停服 / 166 顺序断言保留。 |
| 392③帮会历练占用释放 | 已有：`GuildTrialCancellation.Verified.terminal` 唯一判断；`GuildActivityStore.completeCancellation` 核签和完整 Start 原件，同事务关闭 launch、battle / lobby 及角色占用。零 battle 取消墓碑或 formation=5 且不是 COMPLETE 才能释放；矛盾回执拒绝。 | `GuildTrialCancellationTest`、`GuildTrialTerminalTest`、`GuildActivityStoreTiDbTest`；不能用帮会解散或 Battle 目录空替代终局。 |

## 3. 本轮三个修复的边界

1. `beginShutdown(reason, budget)` 只在 battle 逻辑线程启动，返回 `CompletableFuture<ShutdownReport>` 给节点生命周期线程等待。普通回合与直连先停止，后续建房、补签、观众加入与旧票重连均被拒绝。已算出的 v1 终局继续提交原 `CreateBattleRequest`、原事件和原 `ByteString`；空闲退避立即唤醒，在途失败后沿现有退避重试。到预算仍未知的每局记录 battle_id、operation_id、target_instance、失败次数与是否在途；这些标识只进日志 / 报告，不作指标标签。超时后迟到回包不再产生公开终局；SQL 已提交的 COMPLETE 仍由永久来源继续投递。逻辑线程完全停滞时节点外侧有额外等待上限并报错，不能承诺此时取到逐局快照。
2. `finishUncreated` 使用原 `finish` 的 owner→unit→unsigned ticket→formation→completion 锁序和 3 秒 SQL 预算。空内存快照只是选路条件，真正释放必须在同事务看见空 Create 与不存在 completion，并核本代工作租约。COMMIT 回包丢失可按原 ID 读到相同 state=5；不会生成伪 ABORT 来源，也不会回写后来新票。取消备战只使用返回的持久原端点和原 battle_id；失败或退出后尚未来得及通知时，沿既有 scene 备战期限 reaper 解冻。
3. `applyInbox` 在首效果前完成原有宠物 / 消耗 / 整包候选预检。金币封禁调用原货币入口得到 BLOCKED（保留全服封禁指标），只跳该子效果并记原因；背包、HP/MP、宠物、击杀任务和 Inbox 水位仍按一笔玩家快照推进。解封后原 ID 重放不能补发被明确跳过的金币。未知 uint64 大值、余额溢出或候选准备失败仍整笔 DEFERRED，不损失应补发的奖励。

## 4. 确认仍未移植的代码能力

这些都是现有兼容能力，全球生产门禁不构成删掉它们的理由；也不能把它们错误描述成当前全球 SQL v1 主链的丢奖缺陷。

| 剩余项 | 可证实证据 | 下一批最小完整方向 |
| --- | --- | --- |
| 本地观战原操作（PARITY 388 后半） | Java `WatchBattleService` 组 AddObserver 仅 battle / observer / routing / name；`DefaultObserverDialer.remove` 只组 battle / observer / reason。固定 Go `logic/spectate.go:newLocalObserverAdd` 已有 UUID、25 秒准入截止、固定实例；Java battle `ObserverOperationLedger` 已要求这些字段，因此真实本地路径被拒绝。 | 把完整原 Add 持久到本地观战标记，所有 Remove / 补偿 / 同局重看使用同一原件和取消证明；补真实房间 loopback，而非仅 FakeObserverDialer。不得只临时补三个 Add 字段而遗漏恢复。 |
| 旧 Redis 发件箱存储未知（367） | `SettlementOutbox.onStored` 与 `ActivityResultOutbox.onStored` 的异常 / 空回包仍只首次照投、不登记完整重试；固定 C++ 已要求不明时登记并由探测原件裁决。SQL v1 房间不调用这条个人直投路径。 | 沿原 ID / 原字节登记重试，探测只依据持久原件或真正销账墓碑；unknown 不能当 rollback，补 COMMIT 后丢回包故障用例。 |
| 旧 Redis 个人结算与锁证据（368；含 288 的旧 applyOnce） | `PlayerBattleService` 旧 deliver 分支仍在“冻结属于别局 / 锁不属本局”时 writeOff；旧 Outbox superseded、墓碑期限及 pending TTL 不能视为永久 Inbox 语义。旧 `BattleSettlementService.applyOnce` 仍是金币先行后分步处理。 | 独立按固定基线迁移兼容链的 ACK 证据、原件保存与未知重投；不能因只看 Redis 空便删除原记录。若产品决定退役，应有显式迁移 / 配置门禁及用户批准，不能在这次盘点中自行删除。 |
| 形状不合格的过期成局只读巡检（391 末段） | 固定 Go `logic/global_expired_audit.go` 每分钟调用 `CountStrandedExpiredFormations`，固定枚举 `recover/expired_unrecoverable`；Java 正常 v1 deadline 游标存在，但未发现对应异常形状计数扫描。 | Java SQL repository 有界只读计数 + 固定低基数指标 / 日志；不自动“修复”为 ABORT，不把能力 / 原件缺失当可释放证明。已有正常 deadline 恢复无需照搬 Go 的租约延期修正。 |

## 5. 不应冒充本轮代码缺口的产品与验收边界

- 通用玩家欠款入箱仍在 `InboxRegistry.V1_DISABLED` 明确禁用：欠款合并、撤销与额度产品语义尚未齐，不因 payload 已注册而自行开放。
- 固定 C++ 的 `docs/design/battle-spectate-tiered-fanout.md` 把私密自定义局 v1 观战排除，并要求扩大前补可见性闸。本文只确认现有观众席与全球观战原操作，不能以设计文档宣称两版已实现全服分级转播。
- 经验产品边界仍由现有结算代码明确记录，本文没有新增经验系统。完整产品矩阵须另核固定基线，不能只凭 `exp_gain` 字段认为 Java 丢失一个已存在系统。
- 多副本、水平扩展与强一致的源码围栏已存在，不等同生产 HA 演练完成。必须记录停节点、旧实例恢复、SQL COMMIT 未知、Kafka ISR 不足 / rebalance、Inbox ACK 丢失、owner/revision 变更以及同 ID 重放的最终 SQL 原件和客户端结果。普通异常不得解释为确定回滚，Redis 目录空不得解释为原节点绝无在途工作。

更广的 7.4 巡检 / 压测、7.5 发行契约、7.6 部署和演练库存见 `../remaining-ops-java-20261026.md`；其中本轮由其他代理继续实现的内容须以它们的最终提交和验收报告更新。

## 2026-10-27 接续实现记录

上文保留为盘点时的事实。本地观战原件现已在独立 Java 工作树接入，详见 `../local-watch-original-20261027.md`：完整 Add/固定端点持久标记、同场重看取消、严格取消证明、Unknown 与未知版本保留、迟到证明按原串删除；match 新增 17 个测试源码，另有 4 个真实 battle 房间 Triple 回环，尚未运行。本地 TTL/尽力 gather 的边界保持明确，不当作全球 SQL 强事务证明。

异常形状的过期成局已加入一次性只读巡检的 `match.expired_unrecoverable`，尚无周期调度/深读或真实环境验收。旧 Redis 发件箱 UNKNOWN 的有界恢复已按第二版候选接入，见 `../legacy-outbox-unknown-20261027.md`：逐轮主库核对原字节、缺失不算销账、保留全部未完成 store 的同 ID 字节约束；新增 15 个确定性测试与 2 个 Redis gated 用例尚未运行。跨写者 CAS、跨重启恢复、TTL、个人结算/锁证据仍在后续范围，不能因 SQL v1 主路径已具备永久原件而隐去。
