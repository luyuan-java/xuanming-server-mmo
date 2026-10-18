# 重要玩家消息：持久收件箱、接收端拉取与迁移窗口（2026-10-14 调研记录）

本文记录用户于 2026-10-14 提供的大厂公开资料调研、C++/Go 版对照及建议，并补充 Java 仓库的独立代码核对。本文只落文档，不实现新通道，不改变客户端契约。

**来源边界**：下文「共同做法」「迁移窗口」「与上一轮方案的差别」「C++/Go 版调研快照」保留用户提供的调研内容和研究意图；对本次已核对出的差异显式订正。原文称公开资料已逐条回源核对，但本次落档没有原始检索记录；未列核对来源的外部精确参数、默认值、版本状态及事故结论均按当时调研快照理解，采用前应按目标版本独立复核。原文中尚在补跑的游戏大厂资料（BigWorld、EVE、魔兽、国内中心服架构）没有结果，不作为已验证证据。

Java 的当前状态单列在文末，以本次查看的源码与既有设计文档为依据；不把 C++/Go 的目录、代码行号、待实现项或未编译状态套到 Java。本文没有新增编译、测试或线上运行证据。

## 1. 核心原则

> 重要消息存进按收件人身份组织的持久收件箱，接收方准备好之后按自己的进度去拉；推送只起提醒作用，丢了也不要紧。

寻址用稳定的 `player_id`，消息留在收件箱中；玩家从 A 迁到 B、再迁到 C，消息不跟着节点移动，也不依赖投递器追逐每次位置变化。C 完成接管后从持久进度恢复消费。

这里的「收件箱」是职责，可以由业务库、资产指令账本、战斗待结算记录承担，不要求再建设一套通用消息存储。推送、缓存和迁移期间的路由只能改善延迟，不能单独承担重要业务的可靠性。

这句话是本仓库采纳的架构建议，不是所有公司的唯一实现：Iris 推送实际更新，Temporal Signal 本身就是持久业务输入。两者支持的是「可靠性锚定稳定身份和持久进度」，不能据此断言它们的所有推送都只是提醒、所有接收方都只用拉取。

## 2. 大厂的共同做法（用户调研快照）

### 2.1 收件箱按人存，不按位置存

- Meta Messenger 的 Iris 是一条按用户全序排列的更新队列，每个消费者（手机端、存储层）各有一个指针，投递成功才前移。手机离线时指针停住，新消息照常入队。来源：[Meta 2014 Iris 设计](https://engineering.fb.com/2014/10/09/production-engineering/building-mobile-first-infrastructure-for-messenger/)。
- Telegram 给每个消息箱一个自增序号 `pts`。
- Temporal 按 Workflow Id 把 signal 写进持久历史，发送方不需要知道 worker 在哪台机器上。来源：[Temporal Workflow message passing](https://docs.temporal.io/develop/go/workflows/message-passing)。

### 2.2 推送只是提醒，准确数据靠接收方按进度拉

- Telegram 官方说明实时推送不是事实源。客户端在启动、发现序号断档、长时间没收到更新时，会主动调 `getDifference` 拉缺失的部分。
- Pusher 和 Roblox 官方都明说推送不保证送达。重要数据要应用自己存，重连后再拉。
- Riot Messaging Service 的消息本质上是通知，收到后再去业务服务拉最新状态。断线时消息只在内存里缓存几分钟，超时就让客户端全量重新同步。

### 2.3 去重靠序号或幂等键，确认要排在持久化之后

- Telegram 的判断规则：`local_pts + pts_count == pts` 才应用；`local_pts + pts_count > pts` 是已处理的重复，`local_pts + pts_count < pts` 表示缺口。
- 微信 seqsvr 给每个用户分配单调递增的序号，允许跳号，当作版本水位用。
- 发送方生成的幂等键要按具体服务核对保留规则。原文把 Telegram 的 `random_id` 和 Stripe 的 Idempotency-Key 一起归为「窗口有限」，此处订正：Telegram 当前官方文档明确服务端的 `random_id` 去重记录不会过期；有限窗口不能泛化到 Telegram。[Stripe API v1](https://docs.stripe.com/api/idempotent_requests) 的规则是至少 24 小时后可清理。来源：[Telegram Updates — Message ID generation](https://core.telegram.org/api/updates)。
- AWS 的 outbox 示例用 SQS FIFO 去重，[官方去重间隔](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/FIFO-key-terms.html)只有 5 分钟，所以接收端仍然要自己做持久去重。
- Cloudflare Durable Objects 的 output gate 在写入确认持久化前扣住对外消息（包括回包）。这一保证的边界是其存储与 output gate 语义。来源：[Durable Objects glossary — Output gates](https://developers.cloudflare.com/durable-objects/reference/glossary/)。

### 2.4 旧节点还能不能写，由存储在提交那一刻判断

- Orleans 默认的位置目录只保证最终一致，官方承认集群不稳定时偶尔会出现同一实体的两个实例。Akka 的单写者也依赖脑裂处理正确。所以不能把「同一时刻只有一个持有者」当作无需校验的前提。
- 真正起作用的是 fencing（围栏）：Kleppmann 提出的 fencing token、Google Chubby 的 sequencer。Kafka 的对应例子需限定为复用同一 `transactional.id` 的事务生产者：新实例初始化事务后，旧实例会被围栏拒绝；不能泛化为任意普通生产者一重启都能排斥旧实例。来源：[Kafka 4.3 KafkaProducer](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html)。
- 有些公司还加时间或流程约束：微信 seqsvr 旧节点 N 秒读不到配置就自己停止服务，新节点等 N 秒后才接管；Discord 在 2026 年 3 月语音故障之后加准入检查，交接没完成就不允许缩容（原文事故结论，待独立复核）。

围栏必须由存储在提交时执行；「请求里有 epoch」或应用层提前比过一次，不等于最终写入带围栏。交接、续租、排空和停机还需要各自的安全条件与失败恢复路径。

### 2.5 每条链路都有上限，超出后有明确退路

- 保留期限：原文记录 WhatsApp 未送达消息最多保存 30 天；Iris「大约一周」来自 [2014 年设计](https://engineering.fb.com/2014/10/09/production-engineering/building-mobile-first-infrastructure-for-messenger/)，而 [Meta 2018 年存储迁移文章](https://engineering.fb.com/2018/06/26/core-infra/migrating-messenger-storage-to-optimize-performance/) 描述为数周。后者说明参数随实现演进，不能把一周当作当前统一保证。
- 补发窗口：Ably 是 2 分钟，Discord 断线重连后的补发窗口是「几分钟」。
- 超窗后退回全量重建：[Discord Invalid Session](https://github.com/discord/discord-api-docs/blob/main/developers/events/gateway-events.mdx) 的 `d=false` 表示不可恢复，需要重新 Identify，`d=true` 则可能 Resume；Telegram 返回 `differenceTooLong` 后重新拉该消息箱状态；[FCM Android 非折叠待发消息](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types)超过 100 条会全部丢弃，并通知应用全量同步；LINE 落后太多时全量同步，并定期交换摘要做对账。

上述时长和条数是各产品快照，不能直接成为本项目的参数。应用应明确保存多久、最大未决数、消费者最大落后、增量失效条件、全量恢复入口及异常告警。

### 2.6 按消息类型分级：瞬时消息和场景内事件不进可靠链路

- Slack 的「正在输入」不写库。
- Xbox 实时活动服务（RTA）是进场景订阅、离开退订，退订后迟到的更新直接丢掉。重连后靠「订阅时直接返回当前值」恢复，不补发历史。

资产变更、结算、邮件等重要业务走持久记录；社交列表通知以业务库和重拉兜底；技能伤害、场景内事件和短暂状态按生命周期处理，不因「可靠投递」引入离场后的历史重放。

## 3. 迁移窗口里的在途消息（用户调研快照）

原文归纳以下三种迁移路由路径，强调其中的转发 / 缓冲可能丢失。这里讨论的是路由层，不包括另外配置的持久队列；例如 Akka 启用 `DurableProducerQueue` 的可靠投递不能一并概括为「纯内存」。下列框架默认值和使用状态依赖版本，采用前应再次核对。

| 做法 | 代表 | 原文记录的关键参数 / 语义 |
|---|---|---|
| 拒收，让发送方刷新位置后重试 | Dapr、Service Fabric | Service Fabric 官方说明为尽力而为，会出现重复 |
| 旧节点有限次数转发 | Orleans | 默认最多转发 2 次，超出后退回调用方；来源：[SiloMessagingOptions 源码](https://github.com/dotnet/orleans/blob/main/src/Orleans.Runtime/Configuration/Options/SiloMessagingOptions.cs) |
| 路由层缓冲 | Akka Cluster Sharding | 默认缓冲上限 10 万条，作用域是每个 ShardRegion；满了直接丢弃，交接时还会主动丢掉一段缓冲，避免乱序；来源：[Cluster Sharding](https://doc.akka.io/libraries/akka-core/current/typed/cluster-sharding.html) |

这一层只负责让消息尽快到达，可靠性由持久收件箱及恢复消费提供。[Orleans 投递保证](https://learn.microsoft.com/en-us/dotnet/orleans/implementation/messaging-delivery-guarantees)说明默认最多投递一次（at-most-once），不提供应用层持久去重保证；显式重试还需考虑重复执行。原文还记录 Akka Reliable Delivery 模块官方写明「暂不建议在生产中使用」；本次核对的 [Akka Reliable Delivery 当前文档](https://doc.akka.io/libraries/akka-core/current/typed/reliable-delivery.html)（页面版本 2.10.24）仍有该提示。它是该版本状态，不能作为所有后续版本的长期结论。

## 4. 与上一轮方案的差别

上一轮的大方向是对的：按固定 `player_id` 寻址、持久待办、幂等去重、归属代际校验、按类型分级。主要差三点：

1. **重心偏向推。** 上一轮流程是投递器查位置、推送、被拒、刷新、再重试。本次调研更强调消息留在收件箱里不动，等 C 接管完成后按进度拉；提醒失败不要求投递器一路追踪玩家。
2. **缺少超窗退路和对账。** 必须说明保留期限到了怎么办、待办超限怎么办、增量同步错误如何发现、全量重建和人工补偿如何触发。
3. **只靠代际号不够。** 除提交时围栏，还要明确交接完成前的下线 / 缩容限制，以及新节点接管所需条件。流程条件要与释放、租约到期、故障接管语义一致，不能变成故障后永远不能接管。

## 5. C++/Go 版调研快照（保留原始比较对象）

本节中的路径属于 [C++/Go 版仓库](https://github.com/luyuan-cpp/xuanming-server-mmo)，不是本 Java 仓库的相对路径。代码行号只保存原文定位，后续代码变化可能使其失效。下列实现状态来自用户原文；原文按设计文档自述提醒其中多数改动尚未编译和测试，本次也未重新证明该版本构建或测试通过。

### 5.1 已有能力

- **资产通道**（`go/shared/assetop` + C++ `PlayerAssetOpSystem`）：业务变更和待发记录在同一事务写入；用 `(stream, epoch, seq)` 和有上限的滑动窗口去重；目标回「不在这里」「稍后重试」时退避重投；结果持久化后才算完成。
- **战斗结算发件箱**（`cpp/libs/services/battle/settlement/settlement_outbox.h`）：先写 Redis 再投递；每次重投前重新查玩家位置；场景按 `battle_id` 条件删除记录，删除即确认；进场钩子负责补发。
- **归属围栏**：`owner_epoch`、存盘 Lua 条件写和 `go/db` epoch 守卫三层承担 fencing。
- **分级**：社交推送只发一次，客户端再拉权威列表；技能伤害打到正在交接的目标直接丢弃。

### 5.2 六项缺口与原文优先级

| 序号 | 原文缺口 | 原文依据 / 影响 | 原文优先级 |
|---|---|---|---|
| 1 | 「推送提醒 + 落地后拉取」只做一半 | `docs/design/cross-zone-scene-travel.md:94` 说明跨 zone 期间最坏约 60 秒的全局服务推送会丢、不补发，兜底为客户端每次落地后主动拉一次；原文称 Unity 这部分尚未写 | 优先补齐 |
| 2 | MySQL 写入没有行内围栏 | `docs/design/db-write-fencing-mysql.md` 仍为待授权设计；原文的「持久确认」指 Redis 落盘，MySQL 仍可能被迟到旧数据覆盖 | 优先补齐 |
| 3 | 进场入口没有 epoch 校验 | `cpp/nodes/scene/handler/rpc/scene_handler.cpp:178` 注释指出缺失 | 优先补齐 |
| 4 | 通用玩家消息 RPC 会静默丢消息 | 同文件 `SendMessageToPlayer:290` 找不到人只写日志、不回「不在这里」；`MESSAGE_PRIORITY_IMPORTANT` 仅影响日志级别，重要消息不能靠它保证投递（2026-10-16 补：基线 `Scene.SendMessageToPlayer`（1）与 `IMPORTANT` 的载体 `RoutePlayerStringMsg`（81）已于 RpcChannel 批 7 从 proto 删除；结论不变） | 通道使用约束 |
| 5 | 邮件服务未实现 | 原文称 `go/mail` 不存在；既有设计中业务库本身作为收件箱，带幂等键，方向一致 | 按业务补齐 |
| 6 | 没有超窗退路和对账 | 原文称玩家长期离线时资产待办一直挂着，仅靠未决条数上限挡住新操作 | 完善恢复与运维 |

原文建议：**不再新建一个通用投递器**，避免和资产通道、结算发件箱形成第三份重复实现；先补第 1–3 项，再完善超窗恢复及对账。该排序属于原文对 C++/Go 版的建议，Java 应按下一节真实差异安排。

> 2026-10-15 收窄修订（两版同口径）：用户在 mmorpg 侧拍板新建独立的玩家收件箱服务（mmorpg `docs/design/player-inbox-service.md` v1.0，其 D1）。上一段结论收窄为：资产、结算、邮件保持原分工，不新建能投任意消息的通用投递器；只有「没有任何持久渠道、不是资产、改的是 scene 持有状态」的玩家待办按域进入收件箱。第一个域是 GM 处置（封锁 / 解封货币获取、挂补缴欠款）。Java 版按设计 §12 同切片实现（新模块 `game-inbox`，scene 侧 `InboxHooks`）；欠款是否改走收件箱、不再走离线编辑，是该设计的 D13 推荐，**尚待用户确认**。状态见 PARITY.md「玩家持久收件箱 v1」一行，目前 Java 待做。

## 6. Java 仓库落点与当前状态

本次核对基于 `e8b9e0d` 工作区，初始无本地改动；只查看代码和文档，不表示重新执行过其中测试。现有详细设计仍以 [architecture.md](architecture.md)、[zone-travel-spec.md](../porting/zone-travel-spec.md)、[guild-economy-spec.md](../porting/guild-economy-spec.md)、[scene-battle-spec.md](../porting/scene-battle-spec.md) 为准。

| 主题 | 本仓库证据 | 本次核对结论 |
|---|---|---|
| 资产业务变更与持久待办 | [JdbcEconomyStore](../../game-guild/src/main/java/com/game/guild/store/JdbcEconomyStore.java) 的 `reserveDonation` / `reserveShopOrder`；[JdbcGuildAssetStore](../../game-guild/src/main/java/com/game/guild/asset/JdbcGuildAssetStore.java) | 帮会预留事务同时变更业务账并插入 `guild_asset_op`；后者按玩家 / 流保存，后台领取、重排及终结有条件写守卫，无需另造通用投递器（2026-10-15：资产仍走此通道；非资产的 GM 处置改由收件箱承接，见 §5 末的收窄修订） |
| 资产幂等与持久确认 | [AssetOpService](../../game-scene/src/main/java/com/game/scene/asset/AssetOpService.java)、[AssetOpLedger](../../game-scene/src/main/java/com/game/scene/asset/AssetOpLedger.java)、[PersistedAssetLedger](../../game-player-store/src/main/java/com/game/player/store/asset/PersistedAssetLedger.java) | 按 stream / epoch / seq 分类并记录结局；`durable` 以最近确认的落库快照为准，调用方复查到 `durable=true` 后终结；Java 玩家存储路径是 MySQL，不能复用原文「仅 Redis 落盘」判断 |
| 战斗结算持久记录与进场恢复 | [SettlementOutbox](../../game-battle/src/main/java/com/game/battle/outbox/SettlementOutbox.java)、[SettlementRetryRules](../../game-battle/src/main/java/com/game/battle/outbox/SettlementRetryRules.java)、[BattleRedis](../../game-discovery/src/main/java/com/game/discovery/battle/BattleRedis.java)、[SceneWorld](../../game-scene/src/main/java/com/game/scene/world/SceneWorld.java) 的 `battle.onEntered` | 先尝试持久存储，再按玩家定位投递并有限重投；Redis 提供按玩家读取待结算，进场恢复已有入口。落 Redis 失败时，代码仍会投一次并报告 `NOT_DURABLE`，明确存在丢失可能，不能称为无条件可靠 |
| MySQL 提交时围栏 | [PlayerMapper](../../game-player-store/src/main/java/com/game/player/store/PlayerMapper.java)、[PlayerStore](../../game-player-store/src/main/java/com/game/player/store/PlayerStore.java) 的 `saveStateHeld` / `saveStateAndRelease` / `handOffOwnership` | 玩家行写回和交出 SQL 的 `WHERE` 带 `owner_epoch` 及释放状态；玩法状态与玩家行在同一事务写。C++/Go 快照缺口 2 不适用于这一 Java 路径 |
| 进场 epoch 校验 | [SceneWorld](../../game-scene/src/main/java/com/game/scene/world/SceneWorld.java) 的 `onPlayerLoaded` | 拒绝库中 `ownerEpoch` 与请求不一致的进场，也拒绝本地已有更高 epoch 的实例。C++/Go 快照缺口 3 不适用于这一入口 |
| 全局推送与跨 zone 窗口 | [architecture.md §4.3](architecture.md)、[PlayerPushes](../../game-discovery/src/main/java/com/game/discovery/presence/PlayerPushes.java) | Redis pub/sub 至多一次；跨 zone 两腿之间撤在线目录，按离线丢弃推送。文档要求业务以客户端拉取兜底；Unity 独立仓库不在本次核对范围，不能在 Java 文档中宣称落地重拉已验收 |
| 上限、清理及恢复边界 | [BattleRedis](../../game-discovery/src/main/java/com/game/discovery/battle/BattleRedis.java)、[GuildAssetStore](../../game-guild/src/main/java/com/game/guild/asset/GuildAssetStore.java)、[AssetOpCleanup](../../game-guild/src/main/java/com/game/guild/asset/AssetOpCleanup.java) | 已有结算 7 天 TTL、每 10 秒最多 12 轮重投，次数用尽把记录留给进场恢复 / rescue；资产终态保留清理和未决年龄 / 人工处理入口也已存在。但这些不等于所有重要消息都具备统一的超窗重建与周期对账 |
| 邮件 | [architecture.md](architecture.md) 的玩法服务与模块清单 | 当前无 `game-mail` 模块；未来邮件应以业务库和幂等键为收件箱，不能把现有推送当邮件落账 |

Java 后续优先级据此调整为：

1. 对接 Unity 仓库核实并补齐登录、重连、每次跨 zone 落地后的权威数据拉取；各业务说明拉取范围、游标或版本、重复应用规则及失败重试。服务端已有查询接口不等于客户端恢复闭环完成。
2. 保持 MySQL 提交围栏和进场 epoch 校验；涉及新表、新写路径、新接管入口时审查是否沿用同一事务和围栏，不把 Java 已有能力登记成尚待实现。
3. 为各重要业务单列保留期、堆积上限、增量失效、全量重建 / 补偿、对账及告警；对结算超 TTL、未决资产长期离线、Redis 持久写失败给出可执行退路。不能把重要资产简单丢弃来「清队列」。
4. 在交出、排空及停机流程中明确安全收敛条件，结合 [scene-handoff-spec.md](../porting/scene-handoff-spec.md)、[scene-drain-spec.md](../porting/scene-drain-spec.md) 核对在途写、待发帧及结局探测均已收口，继续复用现有资产与结算通道。

以上为设计待办与审查方向，不代表本次已经实现、编译或验收。
