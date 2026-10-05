# 帮会经济（guild，批次 4.5）移植统一规格：资产指令账本、跨进程投递、捐献 / 升级 / 商店

> **基线**：mmorpg `26ceb70ca`（`git log -1` = `26ceb70 保存发布窗口结束前的进度记录补充`），与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。
> Java 侧以 `3b071ce` 加当前工作区为准：工作区里 `xm-guild/`（4.4 进行中，未提交）、`pom.xml`、`DubboGroups.java`、`NodeTypes.java`、`RedisKeys.java`、
> `tools/local/start-slice.sh` 等有未提交改动，这些文件的行号按当前工作区给出，提交后可能小幅漂移，引用时同时写出方法名。
>
> **范围**：路线图批次 4.5「帮会经济（资产指令账本与投递、捐献、升级、商店）」（`docs/porting/roadmap.md:62`），外加 2.9 留给 4.5 的
> 「跨进程传输与调用方 asset-channel、已落盘账本查询 asset-op-ledger-read」（`roadmap.md:36`；`PARITY.md:79`「Java 待做」）。
> 盘点 id：guild-asset-outbox、guild-asset-delivery、guild-asset-ops-tools、game-day-periods、guild-donate、guild-upgrade、guild-shop
> （`docs/porting/inventory/guild.md:217-299`）、asset-op-ledger-read（`inventory/data.md:263-272`）、asset-channel（`inventory/login.md:353-361`）。
>
> **路径怎么读**（不带目录的文件名按下面解析；行号一律是 mmorpg 的行号）
> - `economy_logic.go`、`economy_config.go`、`push.go`、`guild_logic.go`、`guild_manage_logic.go` 及同目录 `*_test.go`
>   （`economy_logic_test.go`、`economy_config_test.go`、`economy_flow_integration_test.go`）：mmorpg `go/guild/internal/logic/`。
> - `economy_repo.go`、`asset_store.go`、`asset_op_divergence_repo.go`、`tables.go`、`guild_manage_repo.go`、`guild_repo.go` 及其测试
>   （`economy_repo_test.go`、`asset_op_divergence_repo_test.go`、`asset_tables_shape_test.go`、`economy_lock_plan_mysql_test.go`）：`go/guild/internal/data/`。
> - `svc/asset_op.go`（及 `_test`）：`go/guild/internal/svc/`；`guild_internal_server.go`（及 `_test`）：`go/guild/internal/server/`；
>   `assetopfix/main.go`：`go/guild/cmd/`；`config.go`：`go/guild/internal/config/`；`constants.go`：`go/guild/internal/constants/`；
>   `session.go`：`go/guild/internal/session/`；`guild.go`：`go/guild/`；`guild.yaml`：`go/guild/etc/`。
> - `assetop/{types,seq,decide,caller,reconcile,classify,auth,metrics,ledger_dataservice}.go`：`go/shared/assetop/`；
>   `scenenode/{locator,conn,watcher,metrics}.go`：`go/shared/scenenode/`；`gameday.go`：`go/shared/gameday/`；
>   `guild_divergence.go`：`go/data_service/internal/guildcheck/`；`asset_op_ledger_logic.go`：`go/data_service/internal/logic/`。
> - `asset_op_system.cpp`：mmorpg `cpp/libs/services/scene/player/system/`；`scene_node_service.cpp`：`cpp/nodes/scene/handler/grpc/`；`node.cpp`：`cpp/libs/engine/core/node/system/node/`。
> - proto：`guild.proto`、`guild_db.proto`、`guild_internal.proto` 在 mmorpg `proto/guild/`（Java 同步副本在 `xm-proto/src/main/proto/proto/guild/`，
>   只多第 2–4 行 java option，所以 **Java 行号 = mmorpg 行号 + 3**）；`asset_op.proto(mm)` 指 `proto/common/asset/asset_op.proto`；
>   `transaction_log.proto` 在 `proto/common/rollback/`；`scene_node_service.proto` 在 `proto/scene_manager/`；`asset_op_ledger_comp.proto` 在 `proto/common/component/`。
> - 配表数据：mmorpg `generated/tables/{guildrule,guildlevel,guilddonate,guildshop,item,messagelimiter}.json`（Java 用 `config-data/tables/*.pb` 里的同一份）。
> - `GuildClient.cs` = `D:\work\mmorpg-client\Assets\Scripts\Game\Guild\GuildClient.cs`；robot = mmorpg `robot/guild_economy_smoke.go`。
> - 以 `xm-`、`docs/`、`config-data/`、`contract/`、`tools/` 开头的路径，以及 `PARITY.md`、`AGENTS.md`：在 `D:\work\xuanming-server-mmo` 下。
>   `guild-spec` 指 `docs/porting/guild-spec.md`（批次 4.4 统一规格）；`message_id.txt` 指 `xm-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**）。
> - mmorpg 设计稿 `docs/design/guild-phase2/*.md` 只作背景；与代码冲突时以代码为准（`assetop/types.go:13-15` 自己也这么约定）。
>
> **本稿的来历**：由两份分区规格合并（guild 侧经济 / scene 侧与传输）。分区稿之间矛盾、或与代码不符的地方都回到代码重新核对过，
> 更正列在 §9.4。分区稿里 SQL 与有意差异共用 E 编号，本稿改为：SQL 按表字母编号并**接续 guild-spec §1.9 的编号**（G11 起、M12 起、O1 起、Q、C），
> 有意差异用 **E** 系列（接在 4.4 的 D1–D18 之后）。本稿只读代码，没有改任何源文件。

---

## 0 概览与协议

### 0.1 链路、职责与边界

**基线**
- **请求**：客户端 → C++ gate → `client_rpc_router` → guild（go-zero zrpc，`Timeout: 4000`，整请求业务预算 = `Timeout − 500 = 3500 ms`，guild-spec §0.1）。
- **资产指令**（guild → scene，**同步 gRPC 点对点直连**，不经 Kafka、不经 pub/sub）：
  1. guild 在自己的 MySQL 事务里写一行 outbox（`guild_asset_op`），同事务拿到该玩家该流的 `(stream_epoch, seq)`；
  2. 读 `player:{id}:location`（scene_manager 写的 `PlayerLocation`）→ 查 etcd 前缀 `SceneNodeService.rpc/` 的节点镜像得 `grpcEndpoint`（`scenenode/locator.go:149-215`）；
  3. 调 `SceneNodeGrpc.AssetDebit / AssetCredit / AssetAbortDebit`（`scene_node_service.proto:31-36`；`assetop/caller.go:200-209`），请求体里带 HMAC 签名；
  4. 只有 `(APPLIED | REJECTED) && durable` 才把 outbox 行改离 PENDING（`assetop/types.go:181-187`；`decide.go:53-75`），并在同一事务里记对侧账（资金 / 帮贡 / 次数 / 限购）。
- **确认**：scene 应答里如实报 `durable`（结局已在最近一次成功写出的玩家数据里），不在调用里等落盘；调用方用**同一请求**按 100 / 200 / 400 ms 重查（`caller.go:28-32`、`:140-171`）。
- **自愈**：后台重投循环（每副本一个，`reconcile.go:368-476`）+ 离帮提前截止（`economy_repo.go:370-456`）+ 离线读已落盘账本（**基线帮会未接线**，§2.10）+ 人工终结 CLI（`assetopfix`）。
- **Kafka 不在投递链上**：scene 应用时按请求的 tx 记资产流水、关联号 = op_id，只供审计与人工对账（`PARITY.md:63`；`assetopfix/main.go:1-18`）。

**Java（本稿建议，细节见 §4、§7）**
- 请求：gate → Dubbo `ClientMessageService.handle` → xm-guild（group `guild`，端口 20886，guild-spec §7.1）。
- 资产指令：xm-guild 在 `xm_java` 写 outbox → 读 `xm:location:{pid}`（持有归属的 scene 写，状态 `o`）+ Redis 节点目录 `SceneNodeInfo`（新增 `rpc_host / rpc_port`）→
  **按节点直连**调 xm-scene 新增的 Dubbo Triple 异步提供方 `SceneAssetOpService`（debit / abortDebit / credit）→ scene 侧已有的 `AssetOpEndpoint`（2.9）。
- durable：Java scene 只看最近一次**确认落库到 MySQL** 的快照（`xm-scene/.../asset/AssetOpService.java:491-513`），介质与基线（Redis blob）不同、语义相同。
- 推送：`GuildPushes → PlayerPushes`（Redis pub/sub，`architecture.md` §4.3），与 4.4 相同。

**本批不做**：4.6 活动（`ACTIVITY_REWARD` 行、`ACTIVITY` 计数）只留形状；7.2 回档闸（`ListAppliedAssetOpsSince` 的消费方）；交易（TRADE_* 流）的调用方。

### 0.2 消息号、请求 / 应答、准入、限频

服务 `guildpb.GuildService` 的五个方法都在基线会话白名单 `ClientMethods` 里（`session.go:65-69`）。限频取 `messagelimiter.json`（Java 读同一份 `.pb`）。

| 号（`message_id.txt` 行） | 方法（`guild.proto` 行） | 请求 → 应答（字段号） | 限频 | 客户端（`GuildClient.cs`） |
|---|---|---|---|---|
| 120（:121） | GetGuildDonateOptions（:290-299） | `{}` → `{error_message=1, options=2[], pending_donations=3[], contribution_total=4, contribution_balance=5, next_daily_reset_ms=6, recent_results=7[]}` | 10/s | :460-480 |
| 53（:54） | DonateToGuild（:301-308） | `{donate_id=1}` → `{error_message=1, GuildDonationView donation=2, GuildInfo guild=3}` | 5/s | :483-519 |
| 76（:77） | UpgradeGuild（:312-318） | `{expected_level=1}` → `{error_message=1, GuildInfo guild=2}` | 5/s | :529-548 |
| 228（:229） | GetGuildShop（:320-329） | `{}` → `{error_message=1, goods=2[], contribution_balance=3, pending_orders=4[], next_daily_reset_ms=5, next_weekly_reset_ms=6, recent_orders=7[]}` | 10/s | :552-575 |
| 233（:234） | BuyGuildShopGoods（:331-339） | `{goods_id=1, count=2}` → `{error_message=1, GuildShopOrderView order=2, contribution_balance=3}` | 5/s | :578-618 |
| 224 / 225 / 227（:225 / :226 / :228） | SceneNodeGrpc AssetDebit / AssetCredit / AssetAbortDebit | 服务端内部（`asset_op.proto(mm):87-96`、`:138-147`）；只是生成器占号，不进客户端白名单 | — | — |
| 无 | GuildInternal.ListAppliedAssetOpsSince（`guild_internal.proto:49-51`） | 服务端内部，§3.6 | — | — |

- 请求不带 player_id / guild_id，身份一律取 gate 会话（`guild.proto:222-225`）。基线无会话 → `PermissionDenied "guild economy requires a client session"`
  （`economy_logic.go:209-212`），经路由服变信封 1003。Java 的 `ClientMessageService` 总带会话；会话 `player_id == 0` 照 guild-spec §7.3 回信封 1003。
- **经济 RPC 不查归属区**（`economy_logic.go:8-11`、`:189-192`）：入帮事务已保证「是成员 ⇒ 归属区一致」，合服窗口由事务内闸门兜底。Java 4.4 若在派发层统一做了归属区检查，经济五个号必须豁免。
- **PENDING 不是错误**：只出现在视图 `status`，不进 `error_message`（客户端遇到任何非 0 tip 都会停止后续刷新，`guild.proto:224-225`；`economy_logic.go:14-15`）。
- `guild_internal.proto:20` 注释说「占消息号」，但基线生成的 `go/guild/generated/pb/game/message_id.go` 与 Java 的 `message_id.txt` 里都没有它的号（注释陈旧，§9.1）。

### 0.3 视图消息与字段语义（`guild.proto:229-288`）

- **`GuildAssetOrderStatus`**（:229-236）：UNSPECIFIED 0、PENDING 1（结算中 / 待发放）、APPLIED 2、REJECTED 3、ABORTED 4（超时或人工撤销）、APPLIED_PARTIAL 5。
  数值与库枚举 `GuildAssetOpStatus` 逐项相同，但转换写**显式 switch**、不直接转型（`economy_logic.go:460-480`；proto 注释 :227-228 说「按值直接转换」，以代码为准）。
- **`GuildDonationView`**（:239-249）：`op_id=1, donate_id=2, status=3, currency_type=4（0 银两 / 1 灵石）, cost_amount=5, contribution_gain=6, funds_gain=7,
  reason_tip_id=8（PENDING：最近一次暂时原因；REJECTED：拒绝原因）, created_ms=9`。
- **`GuildDonateOptionView`**（:252-263）：`donate_id=1, name=2, currency_type=3, cost_amount=4, contribution_gain=5, funds_gain=6, daily_limit=7, used_today=8（含结算中的占用）,
  min_guild_level=9, unlocked=10`。
- **`GuildShopGoodsView`**（:265-278）：`goods_id=1, name=2, category=3（1 修行补给 / 2 帮会珍藏 / 3 节庆好礼）, item_id=4, item_count=5（每份物品数）, cost_contribution=6（每份帮贡）,
  required_guild_level=7, unlocked=8, limit_period=9（0 不限 / 1 每游戏日 / 2 每周）, limit_count=10, used_count=11（本周期已兑，含待发放）, max_buy_count=12`。
- **`GuildShopOrderView`**（:280-288）：`op_id=1, goods_id=2, count=3, status=4, cost_contribution=5（本单总帮贡）, reason_tip_id=6, created_ms=7`。
- **`GuildChangedS2C`**（:215-220）：`kind=1, guild_id=2, actor_player_id=3（系统触发 0）, target_player_id=4`；本批发出 FUNDS_CHANGED 9、LEVEL_UP 10、DELIVERY_DONE 13（:198-213）。
- **契约注释**：`DonateToGuildResponse.donation` 「已写入指令时必填（含 REJECTED）」、`guild`「请求者仍在帮时必填（含 PENDING）」（:306-307）；
  `UpgradeGuildResponse.guild` 「请求者仍在帮时必填（含业务失败）」（:317）；`BuyGuildShopGoodsResponse.contribution_balance` 「提交后的余额（REJECTED 时已退回）」（:338）。

### 0.4 tip 码表

每个 tip 是 `TipInfoMessage{id, parameters:[一句英文原因]}`（`guild_logic.go:120-122`）。码值见 `xm-table/src/main/proto/tip/guild_error_tip.proto:56-74`、
`asset_error_tip.proto:12-28`；文案见 `config-data/tables/tip_text.json`。

| 码 | 枚举 | 发生点与英文参数（出处） |
|---|---|---|
| 14022 | kGuildFundsInsufficient | 升级事务内资金不足 `"guild funds insufficient"`（`economy_logic.go:335-336`） |
| 14023 | kGuildMaxLevel | 当前等级行 `upgrade_cost_funds == 0` `"guild already at max level"`（`:333-334`） |
| 14024 | kGuildDonateLimit | 捐献事务内次数 upsert 达上限 `"daily donate limit reached"`（`:327-328`） |
| 14025 | kGuildCurrencyInsufficient | 捐献被 durable REJECTED 且 reason = 27000 `"currency insufficient"`（`:515-523`） |
| 14026 | kGuildAssetPending | ① 资产通道关闭 `"guild asset channel disabled"`（`:376-380`）；② `assetop.ErrTooManyPending` `"too many pending asset ops"`（`:337-340`）。两种都**没写任何行**，回包不带视图（`constants.go:124-126`） |
| 14027 | kGuildAssetRejected | ① 配表没有该 donate_id `"donate option not found"`（`:722-726`）；② 捐献 REJECTED 且原因不是 27000 `"asset op rejected"`（`:522`）；③ 兑换 REJECTED `"asset op rejected"`（`:987-991`） |
| 14028 | kGuildShopGoodsNotFound | `"shop goods not found"`（`:887-890`） |
| 14029 | kGuildShopLevelTooLow | 捐献的 min_guild_level 与商品的 required_guild_level **共用**：预判与事务内都是 `"guild level too low"`（`:324-326`、`:728-730`、`:905-907`） |
| 14030 | kGuildShopLimit | ① 份数超过 MaxBuyCount `"count exceeds max buy count"`（`:901-903`）；② 仓储纯判断 `count > limit_count` 或 upsert 达上限 `"shop purchase limit reached"`（`:329-330`） |
| 14031 | kGuildContributionInsufficient | 预判与事务内 `"contribution insufficient"`（`:331-332`、`:1021-1022`） |
| 14008 | kGuildIdGenUnavailable | op_id 发号器未接线 / 出错 / 返回 0 `"asset op id generator unavailable"`（`:386-401`）。in-band，Tip 表 fault 列为 1 |
| 14013 | kGuildZoneMerging | 事务内闸门 `"zone merging"`（`:322-323`） |
| 14016 | kGuildRankTooLow | 升级时 Rank 低于长老 `"rank too low"`（经 `mapWriteErr`，`guild_manage_logic.go:294-295`） |
| 14021 | kGuildBusyRetry | 写冲突 `"guild write conflict"`（`guild_manage_logic.go:317-321`） |
| 14001 | kGuildNotFound | 事务内 ErrGuildGone `"guild not found"`，并调 verifyMapping（`guild_manage_logic.go:285-288`） |
| 14002 | kGuildNotInGuild | 前置 `"not in any guild"`（`economy_logic.go:232`、`:272`；`guild_manage_logic.go:249`）；事务内 ErrNotGuildMember 或读页直读查不到成员行 `"not a member of the guild"`（`guild_manage_logic.go:289-291`；`economy_logic.go:1020`、`:1075`、`:1152`） |

- 14022–14031 全是业务拒绝，fault 列为空（`constants.go:110-114`）。scene 的 27xxx 原因码**只**进视图的 `reason_tip_id`，不直接做成 tip（同处）。
- scene 原因码（视图里可见）：27000 货币不足、27001 背包满（RETRY）、27002 战斗中、27003 冻结 / 交接 / owner_epoch 为 0、27004 包非法、27005 封禁 / 账本损坏、
  27006 不在本节点、27007 部分发放、27008 验签失败（`asset_error_tip.proto:12-28`；§4.2）。
- **故障（基线 gRPC 错误 → 客户端信封 1003；Java 照 guild-spec §7.3 回信封 1003、记 ERROR）**：
  经济依赖未装配 `Unavailable "guild economy not enabled"`（`economy_logic.go:165-167`，Java 不存在这一态）；捐献时 GuildRule 缺行 `Internal "GuildRule row 1 missing"`（`:731-734`）；
  兑换时 Item 缺行 `Internal "Item row %d of GuildShop[%d] missing"`（`:896-900`）；`limit_period` 非法 `Internal "GuildShop[%d].limit_period=%d invalid"`（`:913-916`）；
  `ErrGuildLevelConfigMissing` → `Internal "guild data or configuration is inconsistent"`（`guild_manage_logic.go:322-325`）；读 RPC 的 MySQL 读失败（`economy_logic.go:1070-1088`、`:1147-1165`）；
  其它未识别错误原样返回（`economy_logic_test.go:844` 的整表）。

### 0.5 配表与启动校验（`ValidateEconomyTables`，失败拒启，`guild.go:98-103`）

**当前数据**（`generated/tables/*.json`；Java schema `xm-table/src/main/proto/{guildrule,guildlevel,guilddonate,guildshop}_table.proto`）
- `GuildRule[1]`：`asset_op_deadline_seconds = 600`（第 5 列）、`asset_op_retry_base_ms = 1000`（第 6 列）（`guildrule_table.proto:42-47`；列注释写「会叠加抖动」，见 §9.1 第 2 条）。
- `GuildLevel` 1–10：`upgrade_cost_funds` = 20000 / 50000 / 100000 / 180000 / 300000 / 460000 / 680000 / 960000 / 1300000 / **0**（满级）；
  `max_members` = 30 / 35 / 40 / 45 / 50 / 60 / 70 / 80 / 90 / 100；`max_officers` = 2 / 2 / 3 / 3 / 4 / 4 / 5 / 5 / 6 / 6。
- `GuildDonate`：1 银两小捐（currency 0，花 10000，帮贡 10，资金 1000，每日 5 次，Lv1）；2 银两大捐（0，100000，120，12000，2 次，Lv1）；3 灵石捐献（currency 1，100，200，20000，1 次，Lv1）。
- `GuildShop` 11 件（`item_id ×item_count`，帮贡 / 份，等级，周期 × 份数）：101 培元丹（15×5，30，Lv1，日 10）、102 回灵散（16×5，30，Lv1，日 10）、103 精炼石（17×1，80，Lv2，日 5）、
  104 修行秘录残页（18×1，150，Lv3，周 5）、201 帮会令牌（19×1，300，Lv3，周 3）、202 玄铁护符（12×1，800，Lv4，周 1）、203 灵兽口粮（20×10，120，Lv2，日 3）、
  204 藏经阁手札（13×1，1500，Lv6，周 1）、301 花灯（21×1，50，Lv1，日 5）、302 月饼礼盒（22×1，100，Lv1，周 7）、303 同心结（23×1，200，Lv5，不限购）。
- `Item.max_stack_size`：12、13 为 1，15–23 为 999。所以单次上限 202 / 204 = 1，其余 = 20（`economy_config_test.go:216`）。

**校验规则**（`economy_config.go:87-226`；错误文案一律带表名、行 id、字段、实际值）
1. 先跑 4.4 的 `validateGuildTables`，最高级 = GuildLevel 行数（`:88-93`）。
2. `asset_op_deadline_seconds ∈ [60, 86400]`；`asset_op_retry_base_ms ∈ [100, 60000]`（`:40-50`、`:95-102`）。
3. 每级 `upgrade_cost_funds ≤ 1e12`（`:52`、`:103-108`）。
4. GuildDonate（`:116-160`）：行非 nil；`id ≠ 0` 且不重复；`currency_type ∈ {0, 1}`（`:29-32`，绑定灵石 2 不可捐）；`cost_amount ∈ [1, MaxInt64]`（scene 按有符号 64 位校验）；
   两项收益不同时为 0；`daily_limit ≥ 1`；`min_guild_level ∈ [1, 最高级]`；每种货币至多 2 行（`:56`）。
5. GuildShop（`:163-226`）：Item 查询函数非 nil；行非 nil；`id ≠ 0` 且不重复；`category ∈ {1,2,3}`（`:36`）；物品存在且堆叠 ≥ 1；`item_count ∈ [1, 堆叠]`；
   `MaxBuyCount ≥ 1`；`cost_contribution ∈ [1, 1e9]`（`:54`）；`required_guild_level ∈ [1, 最高级]`；`limit_period ∈ {0,1,2}`；`(period == 0) ⇔ (limit_count == 0)`；每个分类至少一行。
6. **`MaxBuyCount(row, maxStack)`**（`:243-255`）：行 nil 或堆叠 0 → 0；堆叠 1 → 1；`item_count == 0` → 0；否则 `min(maxStack / item_count, 20)`（`MaxShopBuyCount = 20`，`constants.go:179`）。**不看限购**。
7. 运行期查表：`assetOpDeadlineMs()` = 第 5 列 × 1000，缺行 → `ok=false`（`:305-311`）；`AssetOpRetryBase()` 缺行回 0，让循环构造失败（`:262-268`）；
   `upgradeLevelLookup(level) → (upgrade_cost_funds, max_members, ok)`（`:324-330`）；选项按 id 升序、商品按 (category, id) 升序，都先复制、跳过 nil 再排序（`:334-362`）。
8. **交叉校验 `ValidateAssetOpTiming(lease, reconcileInterval, maxBackoff)`**，只在通道开启时调用（`guild.go:260-270`；`economy_config.go:270-302`）：
   `lease + reconcileInterval < deadline`（否则同步投递被跳过的捐献在循环第一次领到前就已过截止、未扣款即被中止）；`retry_base ≤ maxBackoff`。

### 0.6 配置 `AssetOp.*`（`config.go:94-235`；`guild.yaml:117-143`）

| 键 | 缺省 / 本地值 | 合法区间（只在对应开关打开时校验） | 用途 |
|---|---|---|---|
| `Enabled` | 整段缺失 = false（fail-closed）；本地 true | — | 开启才建签名器、定位件、循环；关闭时捐献 / 兑换回 14026，升级与两个读 RPC 照常（`config.go:99-116`；`guild.go:256-281`） |
| `ReconcileIntervalMs` | 2000 | [200, 60000] | Tick 间隔 |
| `ReconcileBatch` | 100 | [Workers, 1000] | 每次 Tick 至多领多少行 |
| `Workers` | 8 | [1, 64] | **每个副本**同时在途的资产 RPC 上限；scene 的 sync poller 默认只有 8（`reconcile.go:229-241`；`node.cpp:730-749`） |
| `LeaseMs` | 10000 | [OpBudgetMs + 2000, 600000] | 循环的行租约，也是插行租约（基线裁决 G，`guild.go:287-288`） |
| `OpBudgetMs` | 2500 | [1000, 10000] | 单行预算，其中 700 ms 固定留给落库 |
| `MaxBackoffMs` | 60000 | [1000, 600000] | 退避封顶，也是 Alert 行的重排间隔 |
| `PoisonDelayMs` | 3600000 | [60000, 86400000] | 毒行推迟多久 |
| `LedgerReadMinAttempts` | 3 | ≥ 1 | 离线读账本门槛；**基线没接 Ledger，不生效**（`guild.yaml` 本段注释；§2.10） |
| `CleanupEnabled` | 缺省 false；本地 true | — | 与 Enabled 互相独立 |
| `CleanupIntervalMinutes` | 10 | [1, 1440] | 清理间隔 |
| `TerminalRetentionDays` | 30 | [7, 365] | 终态行保留天数；也是内部查询可证明窗口的下界 |
| `CounterRetentionDays` | 30 | [7, 365] | 计数行保留天数；清理按 max(此值, 8 天) 算（§2.11） |

- Enabled 时另要求 `IdSegment.Enabled = true` 且配了 DataServiceRpc 目标（op_id 只由号段发，`config.go:295-307`）；连接串不得显式 `clientFoundRows=true`（`:286-292`）。
- 签名密钥只从环境变量 `MMORPG_ASSET_OP_SECRET_GUILD` 读，去首尾空白后 ≥ 32 字节，缺失或过短**启动致命**；密钥值不进日志 / 指标 / 错误文本（`svc/asset_op.go:42-76`；`assetop/auth.go:57-67`）。
- 内部查询的保留期取 `svc.CleanupConfFrom(AssetOp).TerminalRetention`，**与 CleanupEnabled 无关**（`guild.go:366-367`）；AssetOp 整段缺失时它是 0 → 内部查询一律 Unavailable（§3.6）。

### 0.7 代码常量

| 常量 | 值 | 出处 |
|---|---|---|
| 同步投递预算上限 `economySyncBudget` / `defaultSyncBudget` | 2500 ms | `guild.go:64-68`；`economy_logic.go:66` |
| 同步投递后要留的尾巴 `syncTailReserve` | 1000 ms（700 落库 + 300 回读与编码） | `economy_logic.go:68-71` |
| `minSyncBudget` | 300 ms，低于它不做同步投递 | `:72-74` |
| 插行租约缺省 `defaultInsertLease` | 10 s（取 `LeaseMs`） | `:67`；`guild.go:288` |
| 最近结果：时间窗 / 扫描行数 / 返回条数 | 600000 ms / 20 / 5 | `economy_logic.go:76-80` |
| 待结算列表上限 = `assetop.DefaultLimits.MaxPending` | 16 | `:661-663` |
| 未决守卫 `MaxPending` / `MaxSpan` | 16 / 512 | `assetop/types.go:240-246` |
| 经济读子预算 `economyReadBudget` | 1000 ms | `economy_repo.go:81` |
| Store 子预算：读 / 领取 / 终结（含人工） | 1000 / 1000 / 2000 ms；循环传来的 settle ctx 只有 700 ms，取较小 | `asset_store.go:98-107` |
| 后台写事务尝试次数 `backgroundTxAttempts` | 3（清理每行 1 次） | `:109-112` |
| 后台事务退避 | 10 ms 起指数、±20% 抖动（`mathrand.Float64`）、封顶 200 ms | `assetop/seq.go:252-260`、`:316-324`、`:420-425` |
| 清理：批大小 / 批间隔 / 每类每轮批数 / 单行短事务上限 / 计数行最小龄 | 500 / 100 ms / 20 / 2 s / 8 天 | `asset_store.go:116-142` |
| 资产 RPC 单次超时 / 重查间隔 | 800 ms / 100, 200, 400 ms | `assetop/caller.go:23-32` |
| settle 预算 / 租约余量 / Workers 上限 / 最老未决刷新 | 700 ms / 2 s / 64 / 30 s | `assetop/reconcile.go:28-57` |
| `AwaitDurableDelay` | 500 ms（不进配置） | `reconcile.go:278`；`svc/asset_op.go:104` |
| `FreshAttemptLimit` | 3 | `reconcile.go:74` |
| 签名时间窗 / 密钥最短 | ±300000 ms / 32 字节 | `assetop/auth.go:24-36` |
| 离线读账本单次上限 | 300 ms | `svc/asset_op.go:53-56` |
| 内部查询：每次玩家数 / 每页行数 / 保留期安全余量 | 100 / 500 / 3600000 ms | `asset_op_divergence_repo.go:34-39`；`guild_internal_server.go:38` |
| 人工终结：最小龄 / 最少尝试 / list 缺省龄与条数 | 30 min / 10 / 60 min、50（1..500） | `assetopfix/main.go:53-71` |
| 审计列上限（按 rune） | resolved_by 64、resolve_reason 191 | `asset_store.go:612-615`；`reconcile.go:198-211` |
| scene 已见未 durable 的补存最小间隔 | 500 ms | `asset_op_system.h:51-52`；Java `AssetOpService.java:64` |

---

## 1 数据模型

### 1.1 三张表（`guild_db.proto:88-197`；Java 在 `xm-guild/src/main/proto/xm/guild/guild_tables.proto` **追加**，字段号与索引照抄，guild-spec §6.2）

每张表都带 `TiDBNonclusteredPK = true`、`ShardRowIDBits = 4`、`PreSplitRegions = 4`（`guild_db.proto:126-128`、`:147-149`、`:186-188`）。

**枚举**（`guild_db.proto:96-121`）。SQL 一律绑定生成常量，不写数字字面量（`:94`；`economy_repo.go:35`）。
- `GuildAssetOpStatus`：UNSPECIFIED 0（留给「漏写状态列」的行，不会被当成未决领走）、PENDING 1、APPLIED 2、REJECTED 3、ABORTED 4、APPLIED_PARTIAL 5（只终结、不做对侧账、不自动清理）。
- `GuildAssetOpKind`：UNSPECIFIED 0、DONATE 1（GUILD_DEBIT）、SHOP 2（GUILD_CREDIT）、ACTIVITY_REWARD 3（GUILD_CREDIT，4.6）。**没有退款类 kind**（用户决策 D2：离帮 / 被踢 / 解散后已扣款的捐献照记给帮会、不退款），4 号空着（`:112-113`）。
- `GuildDailyCounterKind`：UNSPECIFIED 0、DONATE 1、SHOP 2、ACTIVITY 3（4.6）。

**`guild_player_op_seq`（Q，锁序 5，`:123-136`）**：主键 (player_id, stream)，无二级索引（`asset_tables_shape_test.go:21`）。
列 `player_id uint64=1, stream uint32=2（AssetOpStream 数值）, next_seq uint64=3, updated_ms uint64=4, epoch uint64=5（建行毫秒，> 0）`。行建出后**永不删除**（`economy_repo.go:145`、`:772`）。
（`:135` 的注释「由 EnsureSeqRow 在事务外建」已陈旧：现在在事务内、成员行锁之下建，§1.2 第 7 条。）

**`guild_asset_op`（O，锁序 6，`:138-180`）**
- 主键 `op_id`；唯一键 `uk_guild_asset_op(player_id, stream, stream_epoch, seq)`；
  二级索引 `idx_guild_asset_op_0(status, next_attempt_ms)`（到期领取 / 终态清理 / 回档检查）、`idx_guild_asset_op_1(guild_id, op_id)`（按帮查）、
  `idx_guild_asset_op_2(player_id, stream, stream_epoch, status, seq)`（未决读、提前截止候选、待结算列表）。索引名从 0 编号，**组只能追加、不得调序**（`:142-146`）。
- 28 列（列序 = proto 字段序 = SQL 列清单序，`economy_repo.go:62-72`；单测 `TestAssetOpColumnsCoverEveryProtoField`，`economy_repo_test.go:58`）：

| # | 列 | DDL 类型（pbmysql，`xm-pbmysql/.../TableSchema.java:63-75`） | 语义 |
|---|---|---|---|
| 1 | op_id | bigint unsigned | 发号（基线号段 `biz_tag=guild_asset_op`）；同时作 correlation_id |
| 2 | player_id | bigint unsigned | |
| 3 | stream | int unsigned | 1 GUILD_DEBIT、2 GUILD_CREDIT |
| 4 | seq | bigint unsigned | 从 1 起 |
| 5 | guild_id | bigint unsigned | **发起时绑定的帮会**；结算一律记给它（D2） |
| 6 | kind | int（枚举列必须是 int，`asset_tables_shape_test.go`） | |
| 7 | status | int（枚举） | |
| 8 | durable | int unsigned | 0/1 |
| 9 | attempts | int unsigned | |
| 10 | next_attempt_ms | bigint unsigned | **双义**：未决 = 下次投递时刻；终态 = 终结时刻（`:160`） |
| 11 | deadline_ms | bigint unsigned | 只有 DONATE 非 0；0 = 永不中止 |
| 12 | payload | MEDIUMBLOB（可空） | AssetBundle 序列化字节 |
| 13 | ref_id | int unsigned | donate_id / goods_id / activity_id |
| 14 | ref_count | int unsigned | 捐献恒 1；商店为份数 |
| 15 | period_key | int unsigned | 占用的计数周期键；0 = 没占计数行 |
| 16 | contribution_delta | bigint unsigned | 捐献：应得帮贡；商店：已扣帮贡（拒绝时退回） |
| 17 | funds_delta | bigint unsigned | 捐献：应记资金 |
| 18 | reason_tip_id | int unsigned | 终结时写一次，**只有 REJECTED 非 0** |
| 19 | created_ms | bigint unsigned | |
| 20 | updated_ms | bigint unsigned | |
| 21 | lease_until_ms | bigint unsigned | 0 = 未被领取 |
| 22 | lease_token | bigint unsigned | 随机 64 位、最低位置 1（**约一半 ≥ 2^63**） |
| 23 | tx_type | int unsigned | 24 TX_GUILD_DONATE、25 TX_GUILD_SHOP、26 TX_GUILD_ACTIVITY_REWARD（`transaction_log.proto:76-78`） |
| 24 | last_outcome | int unsigned | 最近一次 AssetOpOutcome（诊断） |
| 25 | last_reason | int unsigned | 最近一次 scene 原因；PENDING 视图展示它；兼作部分发放粘性标记 |
| 26 | stream_epoch | bigint unsigned | = 分配 seq 时 Q 行的 epoch |
| 27 | resolved_by | MEDIUMTEXT（可空） | 人工终结操作人，≤ 64 字 |
| 28 | resolve_reason | MEDIUMTEXT（可空） | ≤ 191 字 |

  插入时 28 列全写；可空的 3 列写空串 / 空字节，不写 NULL（`economy_repo.go:74-77`）。

**`guild_daily_counter`（C，锁序 7，`:182-197`）**：主键 (player_id, counter_kind, ref_id, period_key)；`idx_guild_daily_counter_0(period_key)` 供清理。
列 `player_id uint64=1, counter_kind 枚举=2, ref_id uint32=3, period_key uint32=4（日键 8 位 / 周键 6 位，数值域不相交）, used_count uint32=5（含结算中的占用）, updated_ms uint64=6`。
计数一律用**带上限的 upsert**改写，不许「锁一个不存在的行再插入」（`:196`）。

### 1.2 不变量（及其强制点）

1. **同一 (玩家, 流, 纪元, seq) 至多一条指令**：唯一键兜底；seq 由 Q 行 `FOR UPDATE` 串行分配（`assetop/seq.go:172-238`）。
2. **未决守卫**：本纪元未决行数 < 16，且 `next_seq − 最小未决 seq < 512`，否则 `ErrTooManyPending`。这是 scene 1024 位账本窗口正确性证明的一部分，**不是可调业务数值**
   （`assetop/types.go:234-246`；`seq.go:215-220`）；只数本纪元的行（`seq.go:192-194`）。
3. **行只会离开 PENDING**：终结 CAS 条件是 `status = PENDING`，终态行此后没有任何路径再改（`asset_store.go:209-213`；`economy_repo.go:399`）。
4. **终态行 `next_attempt_ms` = 终结时刻**：Finalize 与 ResolveManually 都同写（`asset_store.go:9-12`）。清理判龄与回档检查都依赖它，漏写 = 回档检查漏行（fail-open，`asset_op_divergence_repo.go:12-14`）。
5. **资金与帮贡只在 scene durable 确认 APPLIED 之后才增加**；兑换的帮贡在预留事务里**先扣**（`economy_repo.go:733-744`）。
6. **插 PENDING DONATE 行之前必须先持有本人成员行 X 锁**：离帮提前截止的候选集因此完整（`economy_repo.go:547-550`、`:395-399`）。
7. **seq 行的每个建行者都先持本人成员行 X 锁**（死锁复核 C2，`economy_repo.go:763-770`）；今后新增任何调 `AllocateSeq` 的路径（4.6 活动发奖）也必须先锁同一成员行。
8. **计数行的每个悲观写者都先持同一 (p, stream) 的 Q 行**（C6，`economy_repo.go:29-31`；`asset_store.go:67-75`）；清理例外，靠「截止至少早 8 天」与 upsert 不相遇（C5）。
9. **预留事务必须是 READ COMMITTED**：`AllocateSeq` 的未决行用普通读，只有 RC 的语句级快照才看得见前一个分配者已提交的行（`assetop/seq.go:145-168`）。
10. **correlation_id = op_id**：同步首投与重投一致（`asset_store.go:481-484`；`economy_logic.go:786`、`:966`）。

### 1.3 锁序与 SQL 纪律

- **全序** G → S → M → A → Q → O → C → P（`tables.go:15-21`；`guild_db.proto:15-16`）。
- **捐献、兑换预留只普通读 G**（锁 G 会让同帮所有捐献在一把锁上排队，`economy_repo.go:17`）；**升级**才对 G `FOR UPDATE`（`:108`）。
- **锁定语句一律完整主键等值点操作**；对 guild_member 的锁定 SELECT 与 UPDATE 带 `FORCE INDEX (PRIMARY)`（`:18-22`、`:99-106`）。EXPLAIN 回归 `economy_lock_plan_mysql_test.go:97`。
- **按二级条件找行**：先普通读候选主键 → 按主键升序逐行点锁、点改，WHERE 带原条件做提交点复核（提前截止、清理，`:21-22`）。
- **TiDB 规则 V1**：对 op 行的每个悲观写者（重排、毒行、终结、人工终结、提前截止、清理）在带复核条件的写之前，先在同一事务里跑 O2（完整主键 `FOR UPDATE`、不带复核条件）
  （`asset_store.go:35-62`、`:190-194`）。**领取（Claim）保留自动提交，不许包进事务**（`:54-59`）。MySQL 下点锁不改变锁集（`:50`）；Java 首批只上 MySQL，照抄（同 guild-spec §1.5(e)）。
- **退款分支的 Q 守卫**（C6）：终结在要退次数 / 退限购时最后锁 Q(p, 行上的流)（`asset_store.go:779-819`）。缺行不算错（只管锁序）。
- 终结的取锁顺序：DONATE+APPLIED 为 G → M → O；SHOP+拒绝 为 M → Q → O → C；DONATE+拒绝 为 Q → O → C；都与预留事务同向（`asset_store.go:785-788`）。

### 1.4 SQL 目录（编号接续 guild-spec §1.9；参数一律绑定，状态 / 类型 / 流 / 计数类型绑定生成常量）

**请求路径事务（`inTx`，RC）**

| # | 语句 | 锁 / 断言 | 用于 | 出处 |
|---|---|---|---|---|
| G11 | `SELECT zone_id, level FROM guild WHERE guild_id = ?` | 事务内**普通读**；无行 → ErrGuildGone | T-D、T-S | `economy_repo.go:98`、`:312-321` |
| G12 | `SELECT level, funds, zone_id FROM guild WHERE guild_id = ? FOR UPDATE` | X；无行 → ErrGuildGone | T-U | `:108`、`:834-840` |
| G13 | `UPDATE guild SET level = ?, funds = funds - ?, max_members = ? WHERE guild_id = ? AND level = ? AND funds >= ?` | `execExactlyOneRow` | T-U | `:109-110`、`:875-878` |
| M1 | `SELECT role FROM guild_member FORCE INDEX (PRIMARY) WHERE guild_id = ? AND player_id = ? FOR UPDATE`（4.4 的 M1） | X；无行 → ErrNotGuildMember | T-D、T-U、终结锁对侧 | `guild_manage_repo.go:727`；`economy_repo.go:552`、`:842` |
| M10 | `SELECT player_id FROM guild_member WHERE guild_id = ? ORDER BY player_id`（4.4 的 M10，同一常量） | 普通读 | T-U 推送收件人 | `economy_repo.go:111`、`:899-918` |
| M12 | `SELECT contribution_balance FROM guild_member FORCE INDEX (PRIMARY) WHERE guild_id = ? AND player_id = ? FOR UPDATE` | X；无行 → ErrNotGuildMember | T-S | `:103-104`、`:680` |
| M13 | `UPDATE guild_member FORCE INDEX (PRIMARY) SET contribution_balance = contribution_balance - ? WHERE guild_id = ? AND player_id = ? AND contribution_balance >= ?` | 影响 ≠ 1 → ErrContributionInsufficient | T-S | `:105-106`、`:734-744` |
| Q1 | `SELECT 1 FROM guild_player_op_seq WHERE player_id = ? AND stream = ?` | 普通读（常态命中、不发写） | 建 seq 行前的预读 | `:146`、`:779` |
| Q2 | `INSERT IGNORE INTO guild_player_op_seq (player_id, stream, next_seq, epoch, updated_ms) VALUES (?, ?, 1, ?, ?)`，epoch = updated_ms = nowMs（> 0） | 新行 X | 缺行时在成员行锁之下建 | `:156`、`:777-794`；与 `assetop/seq.go:93` 逐字同义（`TestEnsureSeqRowTx_MatchesAssetopEnsureSeqRow`，`economy_repo_test.go:2258`） |
| Q3 | `SELECT next_seq, epoch FROM guild_player_op_seq WHERE player_id = ? AND stream = ? FOR UPDATE` | X；无行 → ErrSeqRowMissing；epoch = 0 → ErrSeqRowCorrupt | AllocateSeq | `assetop/seq.go:181-190` |
| O4 | `SELECT seq FROM guild_asset_op WHERE player_id = ? AND stream = ? AND stream_epoch = ? AND status = ? ORDER BY seq LIMIT ?`（LIMIT = 17） | **普通读**（不得带锁定子句，`seq_test.go` 钉住） | AllocateSeq 守卫 | `seq.go:136-138`、`:194` |
| Q4 | `UPDATE guild_player_op_seq SET next_seq = next_seq + 1, updated_ms = ? WHERE player_id = ? AND stream = ?` | 影响必须为 1 | AllocateSeq，返回 `{Epoch, Seq = 原 next_seq}` | `seq.go:222-237` |
| O5 | `INSERT INTO guild_asset_op (<28 列>) VALUES (<28 ?>)` | 新行 | T-D、T-S | `economy_repo.go:76-77`、`:253-258` |
| C1 | `INSERT INTO guild_daily_counter (player_id, counter_kind, ref_id, period_key, used_count, updated_ms) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE updated_ms = IF(used_count + ? <= ?, ?, updated_ms), used_count = IF(used_count + ? <= ?, used_count + ?, used_count)`；参数 (p, kind, ref, period, n, now, n, limit, now, n, limit, n) | 影响行数 1 = 新插、2 = 累加、**0 = 达上限**、其它 = 内部错误；**赋值顺序不能换**（updated_ms 必须先看旧 used_count） | T-D（n=1）、T-S（n=count，只在限购时） | `:83-95`、`:345-368` |
| O1 | `SELECT op_id FROM guild_asset_op WHERE player_id IN (<≤100>) AND stream = ? AND status = ? AND guild_id = ? AND kind = ? AND deadline_ms > ? ORDER BY op_id`，绑定 (GUILD_DEBIT, PENDING, G, DONATE, now) | 普通读，按 100 分块、每块读完关游标 | 提前截止候选（4.4 的 O1） | `:131-133`、`:432-456` |
| O2 | `SELECT op_id FROM guild_asset_op WHERE op_id = ? FOR UPDATE`（sqlLockAssetOp） | X；读不到 = 已被清理 | 提前截止、重排、毒行、终结、清理 | `asset_store.go:190-194` |
| O3 | `UPDATE guild_asset_op SET deadline_ms = ?, next_attempt_ms = LEAST(next_attempt_ms, ?), updated_ms = ? WHERE op_id = ? AND status = ? AND deadline_ms > ?`，绑定 (now, now, now, id, PENDING, now) | 影响 0 行就跳过，不自检 | 提前截止（4.4 的 O3） | `economy_repo.go:140-142`、`:420-423` |

**读（自动提交，各 1000 ms 子预算）**

| # | 语句 | 出处 |
|---|---|---|
| C2 | `SELECT ref_id, used_count FROM guild_daily_counter WHERE player_id = ? AND counter_kind = ? AND period_key = ?`（DONATE，日键） | `economy_repo.go:113-114`、`:923-945` |
| C3 | `SELECT ref_id, period_key, used_count FROM guild_daily_counter WHERE player_id = ? AND counter_kind = ? AND period_key IN (?, ?)`（SHOP，日键与周键） | `:115-116`、`:952-975` |
| M14 | `SELECT contribution_total, contribution_balance FROM guild_member WHERE guild_id = ? AND player_id = ?`（无行 → found=false） | `:117-118`、`:1001-1012` |
| O6 | `SELECT status, last_reason, reason_tip_id FROM guild_asset_op WHERE op_id = ?` | `:119`、`:1023-1039` |
| O7 | `SELECT <28 列> FROM guild_asset_op WHERE player_id = ? AND stream = ? AND status = ? ORDER BY stream_epoch ASC, seq ASC LIMIT ?`（PENDING，16） | `:123-124`、`:978-986` |
| O8 | `SELECT <28 列> FROM guild_asset_op WHERE player_id = ? AND stream = ? ORDER BY stream_epoch DESC, seq DESC LIMIT ?`（20，含 PENDING） | `:126-127`、`:990-998` |

**后台（`asset_store.go`；经 `assetop.WithTxRetry` 或自动提交）**

| # | 语句 | 用于 | 出处 |
|---|---|---|---|
| O9 | `SELECT op_id FROM guild_asset_op WHERE status = ? AND next_attempt_ms <= ? AND lease_until_ms < ? AND attempts < ? ORDER BY next_attempt_ms ASC, op_id ASC LIMIT ?`（attempts < 3） | ListDue 第一段（新行） | `:183-185` |
| O10 | 同上，条件改为 `attempts >= ?` | ListDue 第二段，只补缺口 | `:186-188` |
| O11 | `UPDATE guild_asset_op SET lease_until_ms = ?, lease_token = ?, updated_ms = ? WHERE op_id = ? AND status = ? AND lease_until_ms < ?`（**自动提交**） | Claim | `:196-197`、`:437` |
| O12 | `SELECT <28 列> FROM guild_asset_op WHERE op_id = ?` | Claim 回读、GetOp | `:233` |
| O13 | `UPDATE … SET last_outcome = ?(UNKNOWN), next_attempt_ms = ?(poisonUntil), lease_until_ms = 0, updated_ms = ? WHERE op_id = ? AND lease_token = ? AND status = ?`（事务内先 O2） | 毒行推迟 | `:201-203`、`:502-518` |
| O14 | `UPDATE … SET attempts = attempts + 1, next_attempt_ms = ?, lease_until_ms = 0, durable = ?, last_outcome = ?, last_reason = ?, updated_ms = ? WHERE op_id = ? AND status = ? AND lease_token = ?`（事务内先 O2；0 行 → ErrLeaseLost） | Reschedule | `:204-207`、`:538-565` |
| O15 | `SELECT player_id, guild_id, stream, kind, ref_id, ref_count, period_key, contribution_delta, funds_delta FROM guild_asset_op WHERE op_id = ?`（事务外读一次） | 终结前读不可变列 | `:223-224`、`:744-760` |
| G14 | `SELECT funds FROM guild WHERE guild_id = ? FOR UPDATE` | 终结 DONATE APPLIED | `:241` |
| Q5 | `SELECT next_seq FROM guild_player_op_seq WHERE player_id = ? AND stream = ? FOR UPDATE`（缺行不算错） | 退计数分支的守卫 | `:226-231`、`:813-817` |
| O16 | `UPDATE … SET status = ?, durable = 1, last_outcome = ?, last_reason = ?, reason_tip_id = ?, lease_until_ms = 0, next_attempt_ms = ?(now), updated_ms = ?(now) WHERE op_id = ? AND status = ?(PENDING)` | Finalize 的 CAS | `:210-213`、`:589-590` |
| O17 | `UPDATE … SET status = ?, resolved_by = ?, resolve_reason = ?, lease_until_ms = 0, next_attempt_ms = ?(now), updated_ms = ?(now) WHERE op_id = ? AND status = ?`（**不置 durable、不改 last_outcome**） | ResolveManually 的 CAS | `:214-219`、`:605-606` |
| G15 | `UPDATE guild SET funds = funds + ? WHERE guild_id = ?`（恰好一行） | 记资金 | `:242` |
| M15 | `UPDATE guild_member FORCE INDEX (PRIMARY) SET contribution_total = contribution_total + ?, contribution_balance = contribution_balance + ? WHERE guild_id = ? AND player_id = ?`（恰好一行） | 记帮贡 | `:243-245` |
| M16 | `UPDATE guild_member FORCE INDEX (PRIMARY) SET contribution_balance = contribution_balance + ? WHERE guild_id = ? AND player_id = ?`（恰好一行） | 兑换退帮贡 | `:246-248` |
| C4 | `UPDATE guild_daily_counter SET used_count = IF(used_count >= ?, used_count - ?, 0), updated_ms = ? WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ?`（不自检；兜到 0 防 1690） | 退次数 / 退限购 | `:249`、`:907-919` |
| O18 | `SELECT MIN(created_ms) FROM guild_asset_op WHERE status = ? AND stream = ?` | 最老未决行年龄 | `:236`、`:921-934` |
| O19 | `SELECT <28 列> FROM guild_asset_op WHERE status = ? AND created_ms < ? ORDER BY created_ms ASC, op_id ASC LIMIT ?` | assetopfix list | `:234-235`、`:949-957` |
| O20 | `SELECT op_id FROM guild_asset_op WHERE status IN (?, ?, ?) AND next_attempt_ms < ? ORDER BY op_id ASC LIMIT ?`（APPLIED / REJECTED / ABORTED，500） | 清理终态行候选 | `:268-269` |
| O21 | `DELETE FROM guild_asset_op WHERE op_id = ? AND status IN (?, ?, ?) AND next_attempt_ms < ?`（短事务内先 O2） | 清理终态行 | `:270-271` |
| C5 | `SELECT player_id, counter_kind, ref_id, period_key FROM guild_daily_counter WHERE period_key BETWEEN ? AND ? ORDER BY player_id, counter_kind, ref_id, period_key LIMIT ?` | 清理计数行候选 | `:274-275` |
| C6 | `SELECT period_key FROM guild_daily_counter WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ? FOR UPDATE` | 清理点锁 | `:284-285` |
| C7 | `DELETE FROM guild_daily_counter WHERE <4 列主键> AND period_key BETWEEN ? AND ?` | 清理计数行 | `:276-277` |
| O22 | ListAppliedAssetOpsSince 的拼接语句（§3.6） | 内部查询 | `asset_op_divergence_repo.go:57-103` |

**静态与计划回归（Java 照搬）**：`TestEconomyCandidateReadsTakeNoLocks`（候选读不带锁定子句，`economy_repo_test.go:232`）；
`TestEconomyLockingStatementsArePrimaryKeyPointLookups`（锁定语句 EXPLAIN 为 PRIMARY 点查，`economy_lock_plan_mysql_test.go:97`）；`TestAssetTablesShape`（`asset_tables_shape_test.go:21`）；
`TestListAppliedSinceStatementShape` / `SourceGuards`（O22 无锁、不开事务，`asset_op_divergence_repo_test.go:75`、`:112`）。

### 1.5 两套事务基座

- **请求路径**（T-D op=`donate`、T-S op=`shop`、T-U op=`upgrade`）走 4.4 的 `inTx`：RC、单次尝试子预算 1500 ms、1213 / 9007 整体重跑至多 3 次（10–50 ms 退避）；
  1205、子预算到期、COMMIT 结果不明一律 `ErrWriteConflict` → 14021（guild-spec §1.6；`economy_repo.go:13-14`）。
- **后台写**（Reschedule、毒行、Finalize、ResolveManually、清理）走 `assetop.WithTxRetry`：RC；**1205 也重试**（`isRetryableBackground` = 1213 / 9007 / errRetryTx / 1205，`asset_store.go:324-329`）；
  尝试 3 次（清理每行 1 次，`:109-112`、`:1169-1175`）；退避 `NextAttemptMs(0, i, 10ms, 200ms, mathrand)`（`assetop/seq.go:387-425`）。理由：没有玩家在等「稍后重试」（`asset_store.go:16-19`）。
- **重试契约**：闭包结果只在成功返回前交给外层，每次重跑从零开始（`economy_repo.go:884`；`asset_store.go:678-679`、`:1176`）。
- **会话**：`innodb_lock_wait_timeout = 1`、`transaction_isolation = 'READ-COMMITTED'`、`ClientFoundRows = false`（guild-spec §1.6）——C1 的 1/2/0、`execExactlyOneRow`、「加 0 跳过」都依赖后者。

### 1.6 缓存失效（提交之后；失败交后台重试，不影响 RPC 结果，guild-spec §1.11）

| 写 | 失效对象（op 标签） | 出处 |
|---|---|---|
| T-S 提交 | guild(G)（`shop`；成员帮贡在快照里） | `economy_repo.go:751-752` |
| T-U：`Changed`，**或** expected_level 对不上（staleView：上次升级已提交但 COMMIT 回执丢失） | guild(G)（`upgrade`） | `:821-827`、`:891-894` |
| T-D 提交 | 不失效（只改了 Q、O、C） | `:529-608` |
| 终结 / 人工终结且 `touched`（确实改了资金或帮贡） | guild(G) + player(p) 映射（`asset_finalize`） | `asset_store.go:729-731` |
| 只退了计数 / 只做 CAS | 不失效 | `:651`、`:898-903` |

### 1.7 游戏日（`gameday.go`）

- 固定时区 UTC+8（`FixedZone`，不依赖 tzdata、无夏令时），05:00 切日（`:22-31`）；进程时钟，副本 NTP 偏差须 < 1 s（`:15`）。
- `DayKey` = YYYYMMDD（8 位）；`WeekKey` = **ISO 周年** × 100 + ISO 周号（6 位），例 2027-01-01 → 202653（`:33-47`）。用自然年会让键不单调、清理误删。
- `NextDailyReset`：严格晚于 t 的下一个 05:00；`NextWeeklyReset`：严格晚于 t 的下一个周一 05:00（`:49-62`）。
- `PeriodKey(p, t)`：0 → (0, ok)；1 → DayKey；2 → WeekKey；其它 → !ok（`:64-86`）。两种键数值域不相交，可放进同一条 `IN`（`economy_repo.go:951`）。
- **一个请求只取一次 now**，周期键、截止、租约、created_ms 都从它算（`economy_logic.go:708-710`）。
- Java：xm-common 新增 `GameDay`（仓库里目前没有，grep 无结果）：`ZoneOffset.ofHours(8)`，平移 −5 h，周用 `IsoFields.WEEK_BASED_YEAR` / `WEEK_OF_WEEK_BASED_YEAR`；全部以 `Instant` 入参、时钟可注入（guild-spec §6.7）。

---

## 2 资产指令生命周期（账本 / 发件箱 / 投递 / 确认 / 自愈）

### 2.0 总览

```
请求线程（T-D / T-S）
  锁 M ─ 缺行建 Q ─ 锁 Q 分 seq（守卫 16/512）─ 插 O(PENDING, next_attempt = lease_until = start+Lease, token) ─ 占 C / 扣帮贡 ─ COMMIT
  deliverNow(min(2500, 剩余−1000)，<300 跳过) ──→ Caller：定位 → 现签 → Debit/Credit/Abort ──→ scene：验签 / 账本 / 扣发 / 请求存盘
                                               ←── outcome / reason / durable / partial ──────
                                               未 durable：同一请求按 100/200/400 ms 重查（每次重签）
  settle（700 ms，不随请求取消）：Finalize（CAS + 对侧账）或 Reschedule（带 token 的 CAS）
  回读 O6 → 视图（PENDING 不进 error_message）
后台（每副本）
  Tick 每 2 s：ListDue(O9 新行优先，O10 补缺口) → Claim(O11 自动提交 + O12 回读复核) → ProcessOne（同上）→ settle
  清理每 10 min；最老未决年龄每 30 s
离帮 / 被踢 / 解散：同事务把本帮 PENDING DONATE 的截止提到 now（下一轮改发 Abort）
人工：assetopfix list / resolve（与自动终结共用 terminate）
```

**状态机与结局**（`decide.go:53-117`）
```
PENDING ──Debit/Credit 回 APPLIED+durable────────────────→ APPLIED（本次 partial / 行上或本次 reason=27007 → APPLIED_PARTIAL）
        ──Debit/Credit 回 REJECTED+durable───────────────→ REJECTED（reason_tip_id = scene 原因）
        ──已过截止 → Abort 回 REJECTED+durable 且 reason=0 → ABORTED
        ──Abort 回 APPLIED+durable（中止前已扣）─────────→ APPLIED（照记资金，D2）
        ──人工 applied | aborted─────────────────────────→ APPLIED / ABORTED（不置 durable）
        ──RETRY / NOT_HERE / 未 durable / UNKNOWN / 传输失败 / 翻转 → 仍 PENDING，重排
```

### 2.1 seq 分配与发件箱写入

- `ensureSeqRowTx`（`economy_repo.go:756-794`）：在**已持本人成员行 X 锁**之后：Q1 读到就返回；缺行且 nowMs > 0 → Q2。理由（C2）：旧写法事务外 INSERT IGNORE，
  首插者回滚时排队者继承间隙 S、插入意向互挡 1213；成员行是全部建行者的守卫，首插者回滚时没有排队者。
- `AllocateSeq`（`assetop/seq.go:172-238`）：Q3 →（epoch 0 → ErrSeqRowCorrupt）→ O4 取本纪元未决 seq（≤ 17 行）→
  `len ≥ 16` 或 `next_seq − 最小未决 ≥ 512` → `ErrTooManyPending`（包装，`errors.Is` 可判）→ Q4 → 返回 `{Epoch, Seq = 原 next_seq}`。
  普通读在 RC 下正确的四条理由：同 (p, stream) 分配者在 Q 行上串行；PENDING 行只由持 Q 锁的分配者插入；读发生在拿到 Q 锁之后、RC 每语句新快照；行只会离开 PENDING（`seq.go:155-162`）。
- 插行（O5）：`next_attempt_ms = lease_until_ms = start + Lease`、`lease_token = 本请求令牌`——同步投递期间循环看不见这一行（`economy_repo.go:576`、`:586`）。
- 任何拒绝都整体回滚：seq 不前进、行不落、次数不占；首次建出的 seq 行也回滚，下次再建、纪元取那一次的时刻（`:528`）。
- 纪元：库恢复后由运维手册显式抬高；人工终结会让 next_seq 与 scene 的 max_seq 拉开，累计超过 511 后新 seq 被判 JUMP_TOO_FAR（fail-closed，`reconcile.go:709-710`）。

### 2.2 op_id 与令牌

- **op_id**：`OpIDs.Mint(ctx)`；发号器 nil / 出错 / 返回 0 都回 14008，**绝不自造 id**（`economy_logic.go:382-401`）。基线只由号段 `biz_tag=guild_asset_op` 发、不回退 snowflake
  （`svc/asset_op.go:33-36`、`:291-324`；`guild.go:247-254`），号段关闭时 OpIDs 必须是 **nil 接口**。Java 见 E6。
- **令牌**：`crypto/rand` 读 8 字节小端 `| 1`，保证非 0（0 表示「没有租约」）（`economy_logic.go:403-413`；循环的 `newLeaseToken` 同法，`reconcile.go:746-752`）。
  **令牌先于发号**：令牌失败不白烧号（`economy_logic.go:743-747`）。

### 2.3 同步投递 `deliverNow`（`economy_logic.go:438-456`；预算函数 `syncBudgetFor` / `clampSyncBudget` 在 `:419-436`）

1. 预算 `budget = min(SyncBudget 2500, ctx 剩余 − 1000)`；ctx 无截止时剩余按 `SyncBudget + 1000` 算；`budget < 300 ms` → 跳过，计 `guild_asset_sync_skipped_total{kind}`、打 Info，
   行在租约到期后由循环领走（`:419-449`）。单测 `TestClampSyncBudget`、`TestSyncBudgetFor`、`TestDeliverNowSkipsWhenBudgetTooSmall`（`economy_logic_test.go:1152-1197`）。
2. `deliverCtx = WithTimeout(withSyncDelivery(ctx), budget)`；`syncDeliveryKey` 穿过 `settleContext`（`WithoutCancel` 只丢取消与截止、保留值）一直传到 `OnFinalized`，后者据此**不推送**（`:169-185`）。
3. `Loop.ProcessOne(deliverCtx, op)`，op 由 logic 构造（不经 Claim）；错误只打日志，**结局一律以回读为准**（`:450-455`）。

| Op 字段 | 捐献（`:780-791`） | 兑换（`:960-972`） |
|---|---|---|
| OpID / CorrelationID | opID / opID | opID / opID |
| Stream | GUILD_DEBIT(1) | GUILD_CREDIT(2) |
| Seq / StreamEpoch | 预留返回值 | 同左 |
| TxType | 24 | 25 |
| Bundle | `{currencies:[{currency_type, cost_amount}]}` | `{items:[{config_id = item_id, count = item_count × count}]}` |
| DeadlineMs | start + deadline_seconds × 1000 | **0**（永不中止，设计 R7：物品属于玩家，背包满就等） |
| LeaseToken | 插行令牌 | 同左 |
| Attempts / LastReason | 0 / 0 | 0 / 0 |

- 同步路径用的是**内存里的** DeadlineMs；离帮提前截止改的是库里的值、且不抢租约，所以已在进行的同步投递照常发 Debit（`economy_repo.go:374-375`）——设计如此，钱照记给绑定帮会（D2）。
- 同步路径不 Claim；Finalize 的 CAS 只看 `status = PENDING`、不看令牌，所以即使循环在租约过期后也领到它，至多一方终结成功。

### 2.4 Caller：定位、签名、调用、重查（`assetop/caller.go:73-229`；`scenenode/locator.go:144-215`）

1. **入参守卫**：req nil、无 Resolver、无 Signer（`ErrNoSigner`，不允许不签名发包）、rpc 越界 → 错误（`caller.go:83-94`）。
2. **定位**（`locator.go:149-215`）：读 `player:{id}:location` 出错 → 故障；键不存在或空值 → `ErrNotOnline`；**反序列化失败 → 故障（不折成不在线）**（`:145-146`）；
   `node_id` 空：带 owner_epoch → `ErrAwaitingPlacement`，否则 `ErrNodeUnknown`；镜像未完成首次同步 / 节点不在镜像 / 身份歧义 → `ErrNodeUnknown`；拨号失败 → 故障。
   `IsNoHolder`（NotOnline / AwaitingPlacement / NodeUnknown）→ **本地合成 `NOT_HERE`（Local = true），不算错误**，计 `assetop_rpc_total{outcome="no_location"}`（`caller.go:96-111`；`locator.go:102-112`）。
3. **调用**（`invoke`，`:177-229`）：每次发包先 `proto.Clone` 再按当前时间**现签**（`:182-187`）；单次超时 `CallTimeout = 800 ms`（ctx 更早则以 ctx 为准）；
   传输错误计 `outcome="error"` 并原样返回（Decide → Retry）；`partial = true` 计 `partial_total`。
4. **NOT_HERE 换节点**（`:118-129`）：scene 回 NOT_HERE → 重新定位，**只有 endpoint 变了**才再调一次；重定位报错（含 no-holder）则返回 scene 的 NOT_HERE（Local = false，不触发离线读账本）。
5. **durable 重查**（`:131-171`）：APPLIED / REJECTED 且未 durable → 按 100、200、400 ms 用同一请求重查：睡眠被打断 → `timeout`；重查出错 → `error`；
   重查结局不是 APPLIED / REJECTED（换节点、账本被判损坏）→ `timeout`，**不算翻转**；结局变了 → `outcome_flip_total` + `ErrOutcomeFlip`（违反 I2）；拿到 durable → `durable`。
   任何「没等到」都返回**最后一次**结果、`err = nil`，由上层排 AwaitDurable（`:137-139`）。
6. **签名**（`assetop/auth.go:77-162`）：`auth = {caller="guild", timestamp_ms = 本次发包时刻, signature_hex = lowercase hex(HMAC-SHA256(trim(secret), canonical))}`；
   canonical 以 LF 分隔、末尾无换行：`mmorpg-asset-op/v1` / caller / rpc（`debit` | `credit` | `abort_debit`）/ player_id / stream（有符号十进制）/ stream_epoch / seq /
   correlation_id / tx_type / `c=<type:amount,…>;i=<cfg:count,…>;u=<uuid,…>;p=<pet_id>`（按请求顺序、不排序；帮会只用 c、i，`u=` 空、`p=0`）/ timestamp_ms。
   rpc 进串防「拿 Abort 的签名调 Credit」，bundle 进串防改金额。**Abort 的 bundle 照样带上并进签名**（`asset_op.proto(mm):93`）。
7. 连接缓存按 endpoint 惰性拨号；节点从 etcd 消失时关它的连接（`scenenode/conn.go:33-89`；`svc/asset_op.go:166-170`）。

### 2.5 Decide / FinalStatus / 退避（纯函数，`decide.go`；`types.go:114-187`）

- **Decide**（`:53-75`）：err 是 `ErrOutcomeFlip` → Alert；其他 err → Retry；UNKNOWN → Alert；APPLIED / REJECTED：durable → Finalize，否则 AwaitDurable；RETRY / NOT_HERE → Retry；其它值 → Alert（fail-closed）。
- **FinalStatus**（`:102-117`）：APPLIED 且（`res.Partial` 或 `op.LastReason == 27007` 或 `res.Reason == 27007`）→ APPLIED_PARTIAL，否则 APPLIED；
  REJECTED 且 rpc = Abort 且 reason = 0 → ABORTED，否则 REJECTED；其它 → Pending（调用方当 bug，按 Alert 重排）。
  **已知并接受的退化**：拒绝原因环（64 格）挤掉后，真业务拒绝在 Abort 重投时落成 ABORTED；账务相同（都退），只丢文案，不要为它加字段（`:79-92`）。
- **方向 `rpcFor`**（`reconcile.go:530-536`）：`deadline ≠ 0 && now ≥ deadline` → Abort；否则 `ApplyRPCOf`：*_DEBIT → Debit，*_CREDIT 与 SYSTEM_CREDIT → Credit，其余 → 坏行，按 Alert 重排、**绝不猜方向**（`:486-493`；`types.go:120-132`）。
- **退避** `NextAttemptMs(now, attempts, base, max, rnd) = now + min(base << min(attempts, 16), max) × (0.8 + 0.4·rnd)`；rnd nil 取 0.5，越界夹回 [0, 1)（`decide.go:119-156`）。
  **基线 `Loop.Rand` 从未赋值**（全仓非测试代码无 `.Rand =`），所以生产**没有抖动**：attempts 0..5 → 1 / 2 / 4 / 8 / 16 / 32 s，之后 60 s 封顶。
  AwaitDurable = now + 500 ms；Alert = now + MaxBackoff（`reconcile.go:507-527`、`:538-540`）。

### 2.6 ProcessOne 与落库预算（`reconcile.go:478-633`）

- 投递 `applyCtx = WithTimeout(ctx, OpBudget − 700 = 1800 ms)`（`:495-498`）；`res.Local && err == nil` → `tryPersistedLedger(applyCtx)`（§2.10）。
- finalize：`settle = WithTimeout(WithoutCancel(ctx), 700 ms)` → `Store.Finalize`；本次终结计 `finalize_total{stream,status}`；APPLIED_PARTIAL 打 ERROR 要求人工补偿（`:556-583`）。
- reschedule：同样 settle；写库前 `carryPartialReason`：行上已有 27007 且本次 reason 不是 27007 时强制写 27007（粘性，`:613-633`）；
  ErrLeaseLost → 计 `reschedule_lost_total`、返回 nil；其它错误计 `store_errors_total{op}` 并返回错误（`:594-611`）。
- **为什么 settle 必须脱离取消**：同步路径父 ctx 恰好是投递预算，跑满后再拿死 ctx 写库必失败——scene 已扣钱、行仍 PENDING、下一轮再投一次（`:36-56`、`:542-554`）。
- **预算账**（`:46-55`）：1800 ms 装不下最坏投递 800 + (100+800) + (200+800) + (400+800) = 3900 ms；快路径三轮重查全过、慢路径约只容一轮，之后 AwaitDurable 500 ms 重排。清醒取舍。

### 2.7 重投循环（`reconcile.go:364-476`；Store 实现 `asset_store.go:365-565`）

- **Run**：每 `Interval` 一次 Tick；另 30 s 刷新最老未决行年龄（O18，流 = `GuildAssetStreams()` = [1, 2]，`svc/asset_op.go:78-85`）；每轮各自 recover（`:368-384`、`:669-694`）。
- **Tick**（`:386-424`）：ListDue（O9 取满 `Batch` 就不发 O10；O10 只补缺口；按 op_id 去重，第一段排前；`asset_store.go:373-402`）→ 放进队列，`min(Workers, n)` 个 worker 并发 →
  **等全部 worker 结束才返回**，所以每副本同时在途的资产 RPC ≤ Workers。
- **ListDue 防饿死**（X-03）：老行退避封顶后「永远早就到期」，单条 `ORDER BY next_attempt_ms` 会让它们吃光名额；分两段给新行留独立通道。反向代价：老行无保底，靠最老未决年龄告警（`reconcile.go:83-119`）。
- **claim**（`:445-476`）：令牌 `crypto/rand | 1`；`leaseUntil = now + Lease`；`poisonUntil = now + PoisonDelay`；`Store.Claim`（`asset_store.go:425-491`）：
  1. O11 CAS（自动提交）；影响 ≠ 1 → 没领到（`claim_total{lost}`）；
  2. O12 回读；行消失 → 错误（`store_errors{claim}`）；
  3. **回读到的行必须仍是 PENDING 且令牌是自己的**，否则不下发（中间可能被人工终结——对 ABORTED 的捐献再扣一次 = 丢玩家的钱，`:457-465`）；
  4. payload 为空或解不开 → `markPoison`（短事务 O2 + O13，带令牌与 PENDING）→ `ErrPoisonRow`（`claim_total{poison}`、`store_errors{decode}`）；空 payload 也算毒行（`:430-432`）；
  5. 拼 Op：CorrelationID = op_id；DeadlineMs、Attempts、LastReason 取**库里的值**（所以能看到提前截止）。
- **Reschedule**：短事务 O2（读不到 → ErrLeaseLost）+ O14（0 行 → ErrLeaseLost）；子预算 1000 ms 与 settle 700 ms 取小（`asset_store.go:520-565`）。
- 单测：`TestListDue_FreshRowsFirst`、`TestClaim_*`、`TestClaimThenReschedule_LeaseLost`（`economy_repo_test.go:1230-1356`）；`TestEconomyFlowWorkersFinalizeEachOpExactlyOnce`（`economy_flow_integration_test.go:641`）。

### 2.8 终结与对侧账（`asset_store.go:574-919`）

**骨架 `terminate`**（Finalize 与 ResolveManually 共用，`:657-742`）
1. `dbCtx = WithTimeout(ctx, 2000)`（循环路径实际 700）；O15 事务外读不可变列；无行 → ERROR、返回 (false, nil)。
2. WithTxRetry 事务：`lockCounterparty`（`:790-819`：DONATE+APPLIED → G14，G 在才 M1；SHOP+REJECTED/ABORTED → M1；`counterRefund` 为真 → 最后 Q5）
   → O2 点锁（读不到 → 不终结）→ CAS（O16 或 O17）；影响 ≠ 1 → 已被别人终结，返回 false、不做对侧账 → `applyCounterparty`。
3. 提交后：orphan 计数 + Info；未知 kind 打 ERROR；`touched` → 失效 G 与 p 的映射；`OnFinalized` 非 nil 就调用（传入的是调用方 ctx，保留同步标记）。

**Finalize 参数**（`:574-591`）：`status = StatusToRecord(st)`，必须是四个终态之一（`:288-322`）；`reason_tip_id` 只在 REJECTED 时写 `res.Reason`；
`last_outcome` / `last_reason` 写本次结果；`next_attempt_ms = updated_ms = now`。

**对侧账**（判定只由 `counterRefund` 一处决定，`:762-777`；表见 `:834-844`）

| kind | 终态 | 动作 |
|---|---|---|
| 任意 | APPLIED_PARTIAL | 只 CAS；不退次数、不退帮贡，转人工补偿 |
| DONATE | APPLIED | G 不在 → orphan{donate, guild_gone}，资金帮贡都不记；G 在且 funds_delta > 0 → G15；M 不在 → orphan{donate, member_gone}（**资金照记**）；contribution_delta > 0 → M15（total 与 balance 同加） |
| DONATE | REJECTED / ABORTED | period_key ≠ 0 → C4 退 1 次（DONATE 计数） |
| SHOP | REJECTED / ABORTED | M 不在 → orphan{shop, refund_member_gone}；否则 contribution_delta > 0 → M16；另外 period_key ≠ 0 且 ref_count > 0 → C4 退 ref_count 份（**成员在不在都退**） |
| SHOP | APPLIED | 无 |
| ACTIVITY_REWARD | 任意 | 只 CAS（帮贡 / 资金在入队事务里已记，4.6） |
| 其它 | 任意 | 只 CAS，提交后 ERROR |

- 增量为 0 的列跳过：`ClientFoundRows = false` 下「加 0」影响 0 行，会被「恰好一行」自检误判（`:845`）。
- **D2**：结算一律记给发起时绑定的帮会，不看玩家此刻在哪个帮；没有退款指令（`guild_db.proto:112-113`、`:155`；`TestEconomyFlowLeaveAcceleratesAbortThatTurnsOutApplied`，`economy_flow_integration_test.go:557`）。
- 单测：`TestFinalize_*`（`economy_repo_test.go:1387-1627`）、`TestCounterRefundIsTheOnlyRefundRule`（`:279`）、`TestStatusToRecordMapsEverySemanticStatus`（`:84`）。

### 2.9 离帮、被踢、解散时提前截止（4.4 的 O 钩子；`economy_repo.go:370-456`）

- 入参 `(tx, G, playerIDs, now)`；`now == 0` → 错误（`deadline_ms = 0` 表示永不中止，`:401-405`）。
- 调用点：被踢 `[target]`、退帮 `[p]`、解散（全体成员），都在删成员行与删申请**之后**、删 G **之前**，成员行 X 锁持有到提交（`guild_manage_repo.go:1433-1435`、`:1639-1641`、`:2315-2320`）。
- 步骤：O1 按 100 分块普通读、每块读完关游标，合并去重升序 → 对每个 op_id：O2（读不到跳过）→ O3。
- **只改 deadline / next_attempt / updated 三列，不抢租约**；`LEAST` 把退避中的行拉回「现在」；只作用于 DONATE（商店 / 活动的物品属于玩家，照常投递）。
- 效果：循环领到它时 `now ≥ deadline` → 发 Abort（§2.5）；若 scene 早已扣款，Abort 回 APPLIED → 照记资金（帮会还在时）或 orphan。
- 候选集完整性依赖 §1.2 第 6 条。单测 `TestAccelerate_*`（`economy_repo_test.go:1746`、`:1817`）、`TestEconomyLockOrder_LeaveVersusReserveDonationCandidateComplete`（`:2425`）。

### 2.10 离线读已落盘账本（自愈；**基线帮会未接线**）

- **设计**（`reconcile.go:635-667`）：`res.Local && attempts ≥ LedgerReadMinAttempts && Ledger != nil` → 读已落盘账本 → `ClassifyPersisted`（`classify.go:143-161`）：
  只有 APPLIED / REJECTED（带 partial 与 reason）是结论，**天然 durable**，直接走 Finalize；未见 / 其他 → 继续等，**绝不在调用方判中止**（不变量 I7：scene 没记账就不能保证这个 seq 将来不会被晚到的请求应用）。
  指标 `assetop_ledger_read_total{finalized | unseen | absent | error}`。
- **读端**（data_service `GetPlayerAssetOpLedger`，`asset_op_ledger_logic.go:68-124`）：读 zone Redis 的 PlayerAllData blob，**不回落 MySQL**（MySQL 是异步 DBTask、可能更旧，`:25-30`）；
  键不存在 → `found=false`；解不开 / 不属于该玩家 / Redis 失败 → Internal；home zone 查不到 → Unavailable；`player_id = 0` → InvalidArgument；正常 → 空消息或账本。
  适配器 `DataServiceLedger` 不重试、单次 300 ms（`ledger_dataservice.go:25-65`；`svc/asset_op.go:53-56`）。
- **事实**：`AttachPersistedLedger` 有定义（`svc/asset_op.go:198-225`），但 `guild.go` 从未调用（全仓非测试代码只有定义处）；所以 `Loop.Ledger == nil`，`LedgerReadMinAttempts` 不生效。
- **后果**：长期离线玩家的行按 60 s 封顶一直退避；**离线时 Abort 也发不出去**（定位不到人同样是本地 NOT_HERE），捐献一直 PENDING、占着当日次数，直到玩家上线
  （`TestEconomyFlowShopBagFullStaysPending`，`economy_flow_integration_test.go:832` 为同类）。Java 建议接线，见 E8 与 §4.9。

### 2.11 清理（`asset_store.go:970-1202`；`guild.go:390-393`、`:426-443`）

- 每个副本都跑（删除幂等）；首轮随机延迟 [0, Interval)，之后每 Interval 一轮，每轮单独 recover（`:983-1007`）；`CleanupEnabled = false` 不启动。
- `CleanupOnce(now)`（`:1009-1040`）：保留期 ≤ 0 → 错误。三类各自分批（每批候选 ≤ 500、批间 100 ms、至多 20 批，候选不足 500 即停；按候选数而非删除数判，`:1050-1078`），
  每行一个 RC 短事务（点锁 → 带复核点删，1 次尝试，2 s 上限，`:1157-1202`），错误合并返回：
  1. 终态行：`cutoff = now − TerminalRetention`，O20 → 逐行 O2 + O21。**PENDING 与 APPLIED_PARTIAL 永不删**（`:265-267`）；
  2. 日键计数行：范围 [19700101, dayCutoff]，C5 → C6 + C7；
  3. 周键计数行：范围 [100000, weekCutoff]，同上。
  计数截止时刻 = `now − max(CounterRetention, 8 天)`（`:1042-1048`）：8 天保证上一周期在切周后至少再留 24 h，不与在途预留的 upsert 相遇（C5 补遗，`:129-141`）。
- 删掉的行计 `guild_asset_cleanup_deleted_total{table}`。单测 `TestCleanupOnce_KeepsPendingAndPartial`（`economy_repo_test.go:1890`）、`TestCounterCleanupCutoffsSparePreviousPeriod`（`:317`）。

### 2.12 人工终结 `assetopfix`（修复；`assetopfix/main.go`；裁决 D4：v1 只做 CLI，进程里不挂 `Loop.Manual`，`svc/asset_op.go:191-194`）

- 只连 MySQL（DSN 经 `WithLockWaitTimeout`）与缓存 Redis（终结会改资金 / 帮贡，必须失效缓存；Redis 连不上拒绝执行）；不 LoadTables、不连 scene / Kafka / etcd，**不推送**（OnFinalized nil）（`:9-15`）。
- `list [-min-age-min 60] [-limit 50 (1..500)]`：O19，表头只用 ASCII，`resolvable` 列 yes / force / no（`:253-296`）。
- `resolve -op <id> -as applied|aborted -operator <名> -reason <依据> -confirm <id> -txlog-checked [-force]`（`:310-365`）：
  `-as` 只能 applied / aborted（裁决 E：REJECTED 是 scene 的判定、PARTIAL 需逐件核对，`:367-378`）；operator 非空、不含控制字符（防伪造第二条审计行）；reason 非空；confirm 必须等于 op。
- 前置 `checkResolvable`（`:428-447`）：PENDING；创建早于 30 min 前；`last_outcome == UNKNOWN(0) && attempts ≥ 10`（`-force` 只跳过这一条）。没带 `-txlog-checked` → 打印核对清单（第 0 步：确认 scene 可达、循环仍在报 UNKNOWN）后退出（`:449-472`）。
- 执行：包级 `assetop.ResolveManually` 校验（`reconcile.go:711-733`）→ `Store.ResolveManually`（`validateManualAudit` 再校一次，`asset_store.go:593-634`）→ 与自动终结共用 terminate。
- 审计行 `[AssetOpManual] operator=… op_id=… player_id=… stream=… seq=… kind=… as=… reason=%q finalized=… force=…` 无论成败都以 ERROR 级打出并写 stdout（`main.go:408-413`）。
- 退出码：0 成功；1 执行出错；2 参数 / 前置不满足；3 行已不是 PENDING（`:46-51`）。
- **已知盲区**：传输失败 / 超时也记 `last_outcome = 0`（Caller 出错回 `Result{}`，Reschedule 原样落库），列上与真 UNKNOWN 分不开（`:62-67`）。Java 修法见 E12。

### 2.13 回档分歧检查的数据面

- data_service 回档之前问 guild「这些玩家自快照时刻以来，有没有已终结为已应用（APPLIED / APPLIED_PARTIAL）的帮会资产指令」，有就拒绝回档（`guild_internal_server.go:3-17`）。
  依据是不变量 §1.2 第 4 条（终态行 `next_attempt_ms` = 终结时刻）与 `TerminalRetentionDays`（可证明窗口下界）。RPC 细节见 §3.6。
- Java 没有回档功能（7.2），4.5 期间无消费方（`inventory/guild.md:248`）；但**这条不变量必须现在就保持**，否则 7.2 接入时历史行不可用。

### 2.14 预算与时序（同步捐献最坏情况）

```
请求预算 3500 ms（Timeout 4000 − 500；Java：受理时刻 + 3500 ms，guild-spec §7.4）
├─ economyCaller（缓存 / 映射；坏路径回源 MySQL）
├─ 令牌 → 发号
├─ T-D（单次子预算 1500 ms × 至多 3 次）
├─ deliverNow：budget = min(2500, 剩余 − 1000)，< 300 跳过
│   └─ ProcessOne：投递 min(budget, 1800)（一次 800 + 重查 100/200/400 各带一次 800）；落库 settle 700 ms（不随请求取消，会顺延到请求预算之后）
├─ 回读 O6（1000 ms 子预算，实际约剩 300 ms）
└─ freshGuildInfo（缓存 + 在线 / 名字装配）
```
后台单行同样 OpBudget 2500 = 投递 1800 + 落库 700；租约 10 s ≥ 2500 + 2000（`reconcile.go:284-320`）。

### 2.15 启动与关停（`guild.go`）

1. 先 4.4 配表校验，再 `ValidateEconomyTables`，失败拒启（`:95-103`）。
2. **无条件**建 `EconomyRepo` 与 `GuildAssetStore`：升级、读 RPC、清理、内部查询都不依赖通道开关（`:235-245`）。
3. op_id 发号器：号段关闭时必须保持 **nil 接口**（`:247-254`）。
4. 通道开启：`ValidateAssetOpTiming` → `NewAssetPipeline`（签名器 → watcher / 定位件 → Caller → `NewLoop`）→ 预热 op_id 号段；关闭：打一条 ERROR（`:256-281`；`svc/asset_op.go:146-196`）。
5. `EconomyDeps{Repo, OpIDs, Now, SyncBudget 2500, Lease = LeaseMs, Loop}` → `WithEconomy`；活动共用同一 Loop / OpIDs / Lease（`:282-313`、`:352-353`）。
6. 赋值 `assetStore.OnFinalized = guildLogic.OnAssetFinalized`，**必须在循环与 gRPC 启动之前**（无锁字段，`:355-357`）。
7. 注册 GuildService 与 GuildInternal → 预置内部查询指标（必须在 MustNewServer 之后）→ 拦截器（`:359-376`）。
8. 启动 `assetPipe`（watcher → 循环）与清理；它们的 stop 用 defer 写在 `s.Stop` 之后，退出时**最先**执行：先等 goroutine 退出（落库 700 ms 取消拦不住），再关 etcd、MySQL、Redis（`:378-393`；`svc/asset_op.go:259-289`）。

---

## 3 每个 RPC 的处理流程

### 3.0 公共件（logic 层）

**前置 `economyCaller`**（`economy_logic.go:189-238`，五个 RPC 共用，**不查归属区**）
1. 会话身份 `callerOf(ctx, 0)`；不是客户端来源 → PermissionDenied（`:209-212`）。
2. `operatorGuild`：读缓存映射；为 0 时 `VerifyPlayerGuildID(p, 0)` 回源 MySQL；仍 0 → 14002 `"not in any guild"`；出错 → 故障（`guild_manage_logic.go:239-253`）。
3. 读帮会快照 `GetGuild(guildID)`；出错 → 故障（`economy_logic.go:217-220`）。
4. 快照里有本人 → 返回 (p, g)（`:221-223`）。
5. 否则 `ResolvePlayerGuild(p)`（失效可疑快照、纠正映射、**绕过缓存**直读权威快照，`:224`）：
   出错 → `leftGuildWhileResolving`：`VerifyPlayerGuildID(p, cachedGuild)` 为 0 → 14002 `"not in any guild"`；非 0 或复核失败 → 原错误作故障（`:225-230`、`:261-273`）；
   返回 nil → 14002（`:231-233`）；否则用 fresh 快照，帮会 id 变了只打 Info（`:234-237`）。
- 单测：`TestEconomyRPCsRefuseWhenSnapshotLacksCaller`、`…MappedGuildGone`、`TestEconomyCallerHealsFromAuthoritativeSnapshot`、`TestEconomyRPCsKickedWhileResolvingIsNotAFault`、
  `TestEconomyRPCsFailClosedOnMembershipContradiction`（`economy_logic_test.go:337-456`）。

**闸门** `economyFence`（`:288-311`）+ `checkFence`（`economy_repo.go:323-335`）：事务内调用，zone 取**事务内读到的** `guild.zone_id`；未配置或 zone 0 → 放行；
读不出来 → `ErrZoneMerging`（fail-closed）；标记存在 → `ErrZoneMerging`；闸门其它错误也包成 `ErrZoneMerging`。Java 4.4 是 `MergeFence.NONE`（D4），14013 暂不出现，检查点保留。

**错误映射** `economyTip`（`:313-344`）：先认经济哨兵，余下交 `mapWriteErr(ctx, p, G, G, err)`（`guild_manage_logic.go:269-329`）；返回 (tip, 指标 result, fault)。

| 哨兵 | tip | result |
|---|---|---|
| `ErrZoneMerging`（含被包装的） | 14013 | fence |
| `ErrGuildLevelTooLow` | 14029 | level |
| `ErrDonateLimit` | 14024 | limit |
| `ErrShopLimit` | 14030 | limit |
| `ErrContributionInsufficient` | 14031 | insufficient |
| `ErrGuildMaxLevel` | 14023 | level |
| `ErrFundsInsufficient` | 14022 | insufficient |
| `assetop.ErrTooManyPending`（`errors.Is`） | 14026（记 Info） | pending_guard |
| → `ErrWriteConflict` | 14021 | busy_retry |
| → `ErrGuildGone` | 14001 + verifyMapping(p, G) | not_member |
| → `ErrNotGuildMember` | 14002 + verifyMapping(p, G) | not_member |
| → `ErrRankTooLow` | 14016 | rank |
| → `ErrGuildLevelConfigMissing` | Internal（故障） | error |
| → 其它 | 原样返回（故障） | error |

经济哨兵定义在 `guild_repo.go:58`、`:71-83`；整表单测 `TestEconomyTipMapping`（`economy_logic_test.go:844`）。故障 result 细分：PermissionDenied → denied，Unavailable → unavailable，其它 → error（`:364-374`）。

**视图与回读**
- `settledView(opID)`：O6 回读；读失败或行不存在都**按 PENDING 展示**并记 ERROR，不报 RPC 失败（玩家会以为没扣钱而再点，`:525-545`）。
- `orderViewOf(status, last_reason, reason_tip_id)`：PENDING → (PENDING, last_reason)；四个终态 → (对应值, reason_tip_id)；其它 → (UNSPECIFIED, 0)，调用方记 ERROR（`:460-480`）。
- `donationRejectTip`：只在 REJECTED 时填；reason = 27000 → 14025，其它 → 14027（`:513-523`）。
- `donationViewOf(row)`：currency / amount 从 payload 解（必须恰好一笔货币），解不开只缺这两个字段、记 ERROR（`:547-582`）；`shopOrderViewOf`：goods_id = ref_id、count = ref_count、cost = contribution_delta（`:597-609`）。
- `recentResults(rows, atMs, keep)`：按扫描顺序（新的在前）取 `isTerminal && updated_ms ≥ atMs − 600000 && keep(row)`，至多 5 条；atMs ≤ 600000 时 cutoff 0（`:611-632`）。
- `freshGuildInfo(G, p)`：写提交后经缓存重读装配；读失败或本人已不在帮 → nil，不把写报成失败（`:634-648`）。
- `shopUsedCount(row, usage, now)`：`PeriodKey(limit_period, now)`；不限购或非法 → 0；否则 `usage[{goods, periodKey}]`（`:584-595`）。

### 3.1 GetGuildDonateOptions（120）（`economy_logic.go:1046-1122`；**不查闸门**）

1. 经济未装配 → Unavailable（`:1053-1056`）。2. `now = Now()`、`readMs`（`:1057-1058`）。3. `economyCaller` → 故障原样；tip → `{error_message}`，result = not_member（`:1059-1065`）。
4. M14 直读帮贡（**不走缓存**）：出错 → 故障；查不到 → verifyMapping(p, G)、14002 `"not a member of the guild"`（`:1067-1076`）。
5. C2 `DonateUsage(p, DayKey(now))`（`:1077-1080`）。6. O7 `PendingOps(p, GUILD_DEBIT, 16)`（`:1081-1084`）。7. O8 `RecentOps(p, GUILD_DEBIT, 20)`（`:1085-1088`）。任一出错 → 故障。
8. 装配（`:1090-1121`）：total / balance 取第 4 步；`next_daily_reset_ms = NextDailyReset(now)`；`options` 配表按 id 升序，`used_today = usage[id]`，
   **`unlocked = g.Level ≥ min_guild_level`（缓存快照的等级）**；`pending_donations` 与 `recent_results` 只留 `kind == DONATE && guild_id == g.GuildID`（本帮的捐献）。result = ok。

### 3.2 DonateToGuild（53）（`economy_logic.go:693-810`；仓储 `economy_repo.go:480-608`）

判定顺序（命中即返回；没写行的拒绝都不带视图）：
1. 经济未装配 → Unavailable（`:704-707`）。2. `start = Now()`（`:710`）。3. `economyCaller` → 故障 / tip（not_member）（`:711-717`）。
4. 通道关闭（Loop == nil）→ 14026 `"guild asset channel disabled"`（disabled）（`:718-720`；`TestAssetChannelDisabledRefusesBeforeMinting`，`economy_logic_test.go:753`）。
5. `GuildDonate.FindById` 查不到 → 14027 `"donate option not found"`（not_found）（`:722-726`；`TestDonateUnknownOptionRefusesBeforeMinting`，`:820`）。
6. 缓存预判 `g.Level < min_guild_level` → 14029（level）（`:727-730`）。
7. GuildRule 缺行 → Internal（`:731-734`）。8. 编码 payload（`:735-742`）。
9. 令牌（失败 → 故障）→ 发号（失败 → 14008，id_unavailable）（`:743-751`）。
10. **T-D `ReserveDonation`**（op = `donate`）。入参校验先于碰库，失败回内部错误（`economy_repo.go:501-519`）：ids 非 0；now 与 token 非 0；lease 与 deadline 晚于 now；DailyLimit 与 PeriodKey 非 0；payload 非空。
    事务内依次（`economy_repo.go:536-603`）：G11（无行 → ErrGuildGone）→ `level < MinGuildLevel` → ErrGuildLevelTooLow → 闸门 → M1（无行 → ErrNotGuildMember）→
    `ensureSeqRowTx(p, DEBIT)` → `AllocateSeq`（守卫 16 / 512）→ O5 插 PENDING 行（kind DONATE、`next_attempt_ms = lease_until_ms = start + Lease`、`deadline_ms = start + 600 s`、
    ref_id = donate_id、ref_count = 1、`period_key = DayKey(start)`、`contribution_delta = contribution_gain`、`funds_delta = funds_gain`、created / updated = start、tx_type 24、
    `stream_epoch = alloc.Epoch`）→ C1（n = 1，limit = daily_limit；0 行 → ErrDonateLimit）。任何拒绝整体回滚。提交后**不失效缓存**。
11. 失败 → `economyTip`：业务拒绝回 `{error_message}`，故障原样（`economy_logic.go:772-778`）。
12. `deliverNow`（§2.3）。13. `settledView` → (order, reason)。
14. 回包（`economy_logic.go:793-809`）：`error_message = donationRejectTip(order, reason)`（只在 REJECTED；**PENDING / APPLIED / ABORTED / APPLIED_PARTIAL 都不填**）；
    `donation` 视图的 currency / cost / 两项收益取**配表行**（不是行上的值），`created_ms = start`；`guild = freshGuildInfo(G, p)`。
15. result = `resultOfOrder(order)`：pending / applied / rejected / aborted / applied_partial；未知值 error（`:495-511`）。

**客户端**（`GuildClient.cs:483-519`）：`guild` 有效先应用；`error_message` 非 0 → 只显示拒绝（14026 且捐献页拉过时带着拒绝文案重拉），**不读 donation 视图**；
APPLIED 显示成功并重拉背包；PENDING 且 reason = 27000 显示「余额不足，正在确认结算结果」（scene 回了 REJECTED 但未 durable 时出现），其余 PENDING 显示「结算中」+ 原因。

### 3.3 UpgradeGuild（76）（`economy_logic.go:812-858`；仓储 `economy_repo.go:796-896`，op = `upgrade`；**不经资产通道，通道关闭时照常**）

1. 经济未装配 → Unavailable；`economyCaller` → 故障 / tip（`:822-832`）。
2. T-U（`economy_repo.go:828-887`），职位、闸门、expected_level、花费**全部**在事务里按锁住的行判：
   G12（无行 → ErrGuildGone）→ M1（无行 → ErrNotGuildMember）→ `Rank(role) < RankOfficer` → ErrRankTooLow（比 Rank 不比 role 原值，`:849-852`）→ 闸门 →
   `expected_level ≠ 0 && ≠ level` → **不改任何行、提交**，`{Changed:false, NewLevel:level}`，标 staleView（`:856-859`）→
   当前等级行缺 → ErrGuildLevelConfigMissing；`cost == 0` → ErrGuildMaxLevel → 下一级行缺 → ErrGuildLevelConfigMissing → `funds < cost` → ErrFundsInsufficient →
   G13（level+1、扣**当前等级行**的 cost、`max_members = 下一级 max_members`，条件带 level 与 funds）→ M10 读成员 id。
3. 提交后 `Changed || staleView` → 失效 G（`:891-894`）。
4. 出错 → `economyTip`；业务拒绝回包**也带最新 GuildInfo**，只有 not_member（14001 / 14002）不带（`economy_logic.go:837-849`）：14016、14013、14023、14022、14021 都带 `guild`。
5. 成功：`Changed` → result ok，推 LEVEL_UP(10)（actor = 操作者，target = 0，收件人 = 全体成员 − 操作者，`:851-855`）；没变 → result unchanged，不推、不扣钱。两种都回 `{guild: freshGuildInfo}`（`:856-857`）。
6. `ErrGuildLevelConfigMissing` → 故障（信封 1003）。
7. 客户端发确认框里看到的等级，本地等级已变就先拦（`GuildClient.cs:529-548`）；`expected_level = 0`（proto 缺省）= 不比对，可能连升（§9.1）。
8. 单测：`TestUpgradeGuild_ChargesCurrentLevelCostAndRaisesCap`、`TestUpgradeGuild_ConcurrentSameExpectedLevelUpgradesOnce`（`economy_repo_test.go:1101`、`:1188`）；`TestEconomyFlowUpgrade*`（`economy_flow_integration_test.go:902-975`）。

### 3.4 GetGuildShop（228）（`economy_logic.go:1124-1207`；**不查闸门**）

1–3. 同 §3.1 第 1–3 步。
4. M14 读余额；查不到 → verifyMapping、14002 `"not a member of the guild"`（`:1146-1153`）。
5. C3 `ShopUsage(p, DayKey(now), WeekKey(now))`（`:1154-1157`）。6. O7 / O8 查 GUILD_CREDIT 流（`:1158-1165`）。
7. 只保留 `kind == SHOP` 的行，**不按帮会过滤**（物品属于玩家，离帮前买的照常到账，`:1167-1171`）。
8. 商品按 (category, id) 升序；Item 缺行 → `MaxBuyCount = 0`、记 ERROR，整页**不失败**（`:1177-1197`）；`unlocked = g.Level ≥ required`（缓存快照）；`used_count = shopUsedCount`。
9. 回包另带 `next_daily_reset_ms`、`next_weekly_reset_ms`、`pending_orders`（≤ 16）、`recent_orders`（≤ 5）；result = ok。

### 3.5 BuyGuildShopGoods（233）（`economy_logic.go:860-1041`；仓储 `economy_repo.go:610-754`）

1. 经济未装配 → Unavailable；`start = Now()`；`economyCaller`（`:871-882`）。
2. 通道关闭 → 14026 `"guild asset channel disabled"`（disabled）（`:883-885`）。
3. `GuildShop.FindById` 查不到 → 14028（not_found）（`:887-890`）。
4. `count == 0` 按 1 份（老客户端不填，`:891-895`）。
5. Item 缺行 → Internal（`:896-900`）。
6. `count > MaxBuyCount(row, maxStack)` → 14030 `"count exceeds max buy count"`（limit），**发号之前**（`:901-903`；`TestBuyGuildShopGoodsOverMaxBuyCountRefusesBeforeMinting`，`economy_logic_test.go:782`）。
7. 缓存预判 `g.Level < required` → 14029（`:904-907`）。
8. `cost = cost_contribution × count`（≤ 1e9 × 20，不溢出，`:908-909`）。
9. `contributionPrecheck`（`:999-1026`）：快照余额够 → 放行；不够 → M14 直读：出错 → 放行（交事务裁决）；查不到 → verifyMapping、14002；余额 < cost → 14031；否则放行。
10. `PeriodKey(limit_period, start)` 非法 → Internal（`:913-916`）。
11. payload `{items:[{item_id, item_count × count}]}`；令牌；发号（→ 14008）（`:917-933`）。
12. **T-S `ReserveShopOrder`**（op = `shop`）。入参校验（`economy_repo.go:629-647`）：ids、now、token、lease 合法；count 与 cost 非 0；**`(LimitCount == 0) ⇔ (PeriodKey == 0)`**；payload 非空。
    1. **事务前纯判断**：`LimitCount > 0 && Count > LimitCount` → ErrShopLimit（首次插入计数行时 C1 拦不住 count > limit；这一步在**发号之后**，会白烧一个号，`economy_repo.go:660-664`）；
    2. G11 → ErrGuildGone；3. `level < required` → ErrGuildLevelTooLow；4. 闸门；5. M12（无行 → ErrNotGuildMember）；6. `balance < cost` → ErrContributionInsufficient；
    7. `ensureSeqRowTx(p, CREDIT)`；8. `AllocateSeq`；9. O5（kind SHOP、`deadline_ms = 0`、ref_id = goods、ref_count = count、period_key、`contribution_delta = cost`、`funds_delta = 0`、tx_type 25）；
    10. 限购时 C1（n = count；0 行 → ErrShopLimit）；11. M13（影响 ≠ 1 → ErrContributionInsufficient）；12. 返回 `{Seq, Epoch, BalanceAfter = balance − cost}`。
    提交后失效 G（`economy_repo.go:751-752`）。
13. `deliverNow`，`settledView`（`economy_logic.go:960-974`）。
14. 回包（`:975-996`）：`order`（count、cost、`created_ms = start`）；`contribution_balance = BalanceAfter`；
    REJECTED → `error_message = 14027 "asset op rejected"`、余额改为 `refundedBalance`（M14 直读；读失败回 BalanceAfter；成员行不在 → 0，`:1028-1041`）；
    ABORTED → 只改余额、**不填 error_message**（兑换无截止，同步路径理论上到不了）；APPLIED_PARTIAL 不填。回包**没有 GuildInfo**。
15. 客户端：`error_message` 非 0 → 只显示拒绝（14026 时重拉商店）；否则用回包余额就地更新本人可用帮贡、标记捐献页过时、重拉商店页（`GuildClient.cs:578-618`）。

### 3.6 GuildInternal.ListAppliedAssetOpsSince（内部；`guild_internal.proto:23-51`；`guild_internal_server.go`；`asset_op_divergence_repo.go`）

- **准入**：不进客户端白名单，带会话来调一律 PermissionDenied；**没有凭据**，靠网络隔离（`guild_internal_server.go:11-13`）；注册与通道开关无关（`guild.go:362-367`）。拒绝一律 gRPC status，不回 tip。
- **请求**：`{zone_id=1（0 = 不收窄；data_service 恒传 0）, player_ids=2（1..100，不含 0、不重复）, since_ms=3（> 0）, after_op_id=4（游标）, limit=5（0 → 500；> 500 拒绝）}`。
- **应答**：`{ops=1[GuildAssetOpBrief{op_id, player_id, guild_id, stream, kind, status（只会是 APPLIED 或 APPLIED_PARTIAL 的数值）, funds_delta, contribution_delta, updated_ms}], next_after_op_id=2（0 = 查尽）}`。
- **判定顺序**（全部先于 SQL，`guild_internal_server.go:146-226`）：
  1. 入参：空 `"player_ids is required"` → 超 100 `"player_ids has %d entries, limit 100"` → 逐个 0 `"player_ids must not contain 0"` / 重复 `"player_ids must not contain duplicates"` →
     `"since_ms is required"` → `"limit %d exceeds 500"`；→ `InvalidArgument`，记 ERROR，result invalid；
  2. Store 未装配或保留期 ≤ 0 → `Unavailable "guild asset op store is not available"`（unavailable）；
  3. `cutoff = now + 3600000 − retentionMs`（下溢取 0，`:51-59`）；`since_ms < cutoff` → **`FailedPrecondition "since_ms older than terminal retention; cutoff_ms=<十进制>"`**（跨服务契约，
     样例 `cutoff_ms=1697411600000`，guild G8 与 data_service D15 各钉一次），打 Info，result retention；
  4. O22 查询：超时 → `DeadlineExceeded "list applied asset ops timed out"`；取消 → `Canceled "list applied asset ops canceled"`；其它 → `Internal "list applied asset ops failed"`；库错误原文不外发（`:228-238`）；
  5. 成功：result ok_rows / ok_empty，行数记直方图。
- **O22**（`asset_op_divergence_repo.go:57-103`）：
  ```
  SELECT o.op_id, o.player_id, o.guild_id, o.stream, o.kind, o.status, o.funds_delta, o.contribution_delta, o.updated_ms
  FROM guild_asset_op o [LEFT JOIN guild g ON g.guild_id = o.guild_id]
  WHERE o.status IN (?, ?) AND o.next_attempt_ms > ? AND o.player_id IN (<n>) AND o.op_id > ?
  [AND (g.zone_id = ? OR g.guild_id IS NULL)]
  ORDER BY o.op_id ASC LIMIT ?(limit+1)
  ```
  两个状态依次绑 APPLIED、APPLIED_PARTIAL；取回 limit+1 行 = 还有下一页，丢掉多取的一行、游标 = 第 limit 行的 op_id；LEFT JOIN 保留已解散帮会的行；
  普通读、不开事务、不加锁、**不写索引提示**（验收只认 EXPLAIN key ∈ {idx_0, idx_2}）；无自己的子预算（靠整请求预算）；Store 层再兜一次入参（`:105-159`）。不保证跨页快照一致，由消费方的写后复查兜底。
- **消费方**（data_service，`guild_divergence.go`）：按 100 人分块串行，块内 since 取最小值、翻完所有页；首页 FailedPrecondition → 解析 cutoff，块内 since < cutoff 的玩家记「不可证明」、
  用 cutoff 重查一次；结果按各人自己的 since 用 `updated_ms` 过滤；游标不前进 / 返回块外玩家报错；超过 10000 行报错（`:27-50`、`:136-241`）。
- 单测：G1–G6 与 G5b（`asset_op_divergence_repo_test.go:42-371`，含「经真实 ResolveManually / Finalize 终结的行看得到」）；server 侧（`guild_internal_server_test.go:84-251`）。

### 3.7 重放与幂等速查

| 场景 | 结果 |
|---|---|
| 同一请求重复点击捐献 | 每次新 op_id、新 seq：各占一次每日次数，到上限回 14024（不是幂等写；靠次数上限兜） |
| 升级重复点击（同 expected_level） | 第二次 staleView：不扣钱、回最新 GuildInfo、result unchanged |
| 同一 seq 重投（同步 + 循环、多副本） | scene 只读答复已见结局；Finalize CAS 只成功一次 |
| 重查期间 scene 换节点 | 新节点 NOT_HERE / 账本 RETRY → 按「没等到」处理，不算翻转 |
| 落库失败（settle 超时） | 行仍 PENDING，租约到期后循环再投同一 seq，scene 只读答复 |
| 人工终结与循环同时下手 | 同一把 `status = PENDING` CAS，只有一个赢家；输家不做对侧账 |
| 回读失败 | 视图显示 PENDING（行已提交，最终会被循环终结） |

---

## 4 scene 侧与传输

### 4.1 基线协议（`asset_op.proto(mm)`；`scene_node_service.proto:13`、`:31-36`）

| 项 | 内容 | 出处 |
|---|---|---|
| 服务 | `scene_node.SceneNodeGrpc`：`AssetDebit / AssetAbortDebit / AssetCredit`，都是 `(AssetOpRequest) → AssetOpResponse`；结局在 `outcome`，gRPC status 恒 OK | `scene_node_service.proto:31-36` |
| 流 `AssetOpStream` | 0 UNSPECIFIED、1 GUILD_DEBIT、2 GUILD_CREDIT、3 TRADE_DEBIT、4 TRADE_CREDIT、5 SYSTEM_CREDIT（预留）；每条流只有一个服务分配 seq（I6） | `:27-34` |
| 结局 `AssetOpOutcome` | 0 UNKNOWN（畸形 / 验签失败 / 纪元过期 / 跳号 / 滑出窗口）、1 APPLIED（可带 partial）、2 REJECTED、3 RETRY、4 NOT_HERE；后三者与 UNKNOWN **不记账** | `:36-42` |
| 资产包 | `currencies[{currency_type, amount}]`、`items[{config_id, count}]`、`item_uuids`（3）、`pet_id`（4）；3、4 帮会不用，v1 scene 一律记 REJECTED 27004 | `:44-77` |
| 请求 | `player_id=1, stream=2, seq=3（≥ 1）, correlation_id=4, tx_type=5, bundle=6, stream_epoch=7（≥ 1）, auth=8` | `:87-96` |
| 签名 | `AssetOpAuth{caller, timestamp_ms, signature_hex}` 在**请求体**里（生成的 gRPC 包装读不到 metadata） | `:79-85`；`assetop/auth.go:15-22` |
| 应答 | `outcome=1, reason=2（TipInfoMessage）, durable=3, partial=4, snapshot=5（预留，零读写方）` | `:98-108`、`:138-147` |
| 账本存档 | `PlayerAssetOpLedgerComp.streams[]`：watermark、seen / applied 各 16 个 fixed64、rejections ≤ 64、max_seq、stream_epoch、partial_seqs ≤ 64；与 currency / bag **同记录、同一次写出**（I3） | `asset_op_ledger_comp.proto:1-31` |

不变量（背景，`docs/design/guild-phase2/04-asset-channel.md:44-53`）：I2 结局固定、I3 同记录、I4 终结须 durable（REJECTED 也要）、I5 未决在窗、I6 每流一个分配者、I7 只在线应用 / 读不到不判中止、I9 须签名、I10 纪元单调。

### 4.2 基线 scene 接收端（C++，背景）

- **线程**：gRPC sync 线程把请求 `runInLoop` 投到 muduo loop，再 `future.get()` 同步等（`scene_node_service.cpp:344-396`）；每条在途请求占一条 poller，整个进程默认 8 条
  （`node.cpp:730-749`，可用 `GRPC_SERVER_MAX_POLLERS` 覆盖）——这就是调用方 `Workers ≤ 8` 的来历（`reconcile.go:229-241`）。端口 `InsecureServerCredentials`（`node.cpp:728`），鉴权全靠请求体 HMAC。
- **判定顺序**（`asset_op_system.cpp:629-833`）：
  1. `player_id / seq / stream_epoch` 任一为 0、未知流、非 Abort 时流方向或 tx 白名单不符（GUILD_DEBIT {24}，GUILD_CREDIT {25, 26}，TRADE_DEBIT {3}，TRADE_CREDIT {4, 1}，SYSTEM_CREDIT {8, 2, 9}，`:80-92`；**Abort 收全部流、不校验 tx**）→ UNKNOWN 27004；
  1b. 验签失败 → UNKNOWN 27008；2. 玩家不在本节点 → NOT_HERE 27006；3. 账本加载时判损坏 → RETRY 27005；3'. 实体无账本组件（加载未完成）→ RETRY 27006（`:693-701`）；
  4. 分类：INVALID → UNKNOWN 27004；STALE_EPOCH / BEHIND_WINDOW / JUMP_TOO_FAR → UNKNOWN 0；APPLIED / REJECTED → 只读答复；UNSEEN / AHEAD → 继续；
  5. 冻结或交接在途 → RETRY 27003；退出存盘在途 → RETRY 27006；`owner_epoch == 0` → RETRY 27003；6. Abort → 记 REJECTED reason 0（中止占位）；
  7. 战斗中 → RETRY 27002；缺 Currency / Bags 组件 → RETRY 27006；8. 包内容非法 → 记 REJECTED 27004；9 / 10. 扣款（余额不足记 REJECTED 27000）/ 发放（部分发放 → APPLIED + partial + 27007）。
- **durable**：只看 `PlayerLastPersistedSnapshotComp`（刚写进 Redis 的 PlayerAllData）；盘上结局与内存不一致报 ERROR、不报 durable（`:185-217`）；
  记账后 `PersistAndProbeDurable` 立刻 `SavePlayerToRedis` 并如实回报（`:232-248`）；已见 seq 的补存只在可变且距上次 ≥ 500 ms 时触发（`:251-282`）。

### 4.3 Java 2.9 已实现（scene 侧，提交 `eab1952`；`architecture.md` §4.12；`PARITY.md:79`）

| 能力 | Java 位置 | 与基线 |
|---|---|---|
| 内部契约 | `xm-api/src/main/proto/xm/api/asset_op.proto:13-83`（`AssetStream / AssetOutcome / AssetBundle / AssetAuth / AssetOpRequest / AssetOpResponse`；**reason 是 uint32，没有 snapshot**） | 字段语义与数值同基线，Java 自有格式（`:1-4`） |
| 进程内入口 | `AssetOpEndpoint`：任意线程调，投到逻辑线程，future 带回；future 异常完成 = 传输失败（`xm-scene/.../asset/AssetOpEndpoint.java:11-56`）；`SceneNode.assetOps()`（`SceneNode.java:154`、`:267-268`、`:399-401`） | 「跨进程传输随 4.5 接在这一层外面」（`AssetOpEndpoint.java:13`）。**future 在逻辑线程上 complete**（`:45`） |
| 判定顺序 | `AssetOpService.decide`（`AssetOpService.java:141-229`）：信封 / 流方向 / tx 白名单（`:90-102`：GUILD_DEBIT {24}、GUILD_CREDIT {25, 26}）→ 验签 → 找人 → 账本损坏 RETRY 27005 → 分类 → 归属围栏 RETRY 27003 → 中止占位 → 包内容 → 应用 | 同 C++；冻结 / 交接 / 战斗三道闸恒放行（随 5.x / 6.x） |
| 验签 | `AssetOpAuth`：`CANONICAL_VERSION`、300 s、≥ 32 字节、白名单先于取密钥、常数时间比对；密钥 `XM_ASSET_OP_SECRET_GUILD / _TRADE`（`AssetOpAuth.java:37-53`、`:96-150`、`:158-184`） | 规范串逐字节相同（`AssetOpAuthTest`，golden 同输入） |
| 账本 | `AssetOpLedger`（1024 位窗口、跳号、纪元、拒绝环、部分发放名单；`restore` 校验，坏账本原样带回）（`AssetOpLedger.java:25-35`、`:82-93`、`:127`） | 同基线 |
| durable | 只看 `ScenePlayer.persistedState()`（最近一次**确认落库到 MySQL** 的快照，`AssetOpService.java:491-513`）；记账后 `SceneWorld.requestSave`（`:477-488`）；已见未 durable 的重查 500 ms 内至多补存一次（`:64`、`:452-474`） | 介质 MySQL（基线 Redis） |
| 接管守卫 | `SceneWorld` 进场（`PARITY.md:38`） | Java 自有修复 |
| 流水 | 按请求 tx 记，关联号 = correlation_id（`PARITY.md:63`） | 同基线数值 |
| Java 自有差异 | 发放货币段先预检封禁与溢出（溢出记 REJECTED 27004）；物品整批原子、物品段不会部分发放；退出存盘在途 = NOT_HERE；损坏账本原样写回（`PARITY.md:79`；`AssetOpService.java:30-49`） | 调用方按通用规则处理即可 |

### 4.4 4.5 要补的缺口

| # | 缺口 | 现状证据 |
|---|---|---|
| G1 | scene 没有任何跨进程入口：xm-scene 不依赖 Dubbo（xm-api 的 dubbo 依赖是 `optional`，`xm-api/pom.xml:33-38`），只有 Netty 节点链路与管理端口 | `xm-scene/pom.xml`；`xm-scene/src/main/resources/application.yaml` |
| G2 | 节点目录没有 RPC 地址：`SceneNodeInfo` 只有 `link_host / link_port` | `xm-api/src/main/proto/xm/api/node_directory.proto:22-30`；`SceneNode.java:332-338` |
| G3 | 没有「按玩家定位持有者节点」的严格读法：`PlayerLocationDirectory.find` 对 `o` 与 `l` 都返回位置、对损坏 / 键不符返回空（把数据面事故吞成「不在线」），且阻塞；`statusesAsync` 只回状态 | `PlayerLocationDirectory.java:108-115`、`:212-246`、`:262-281` |
| G4 | 调用方全部缺失：签名器、Caller、Decide / FinalStatus / NextAttemptMs、循环、Store、seq 分配 | xm-guild 只有 4.4 骨架 |
| G5 | 规范串只在 xm-scene（`AssetOpAuth.canonical / sign` 是 public static），xm-guild 不能依赖 xm-scene | `AssetOpAuth.java:96`、`:142` |
| G6 | 已落盘账本只读查询（asset-op-ledger-read）：没有读端，也没有调用方可用的分类器（`AssetOpLedger` 在 xm-scene，依赖 xm-api 的 `AssetStream` 与 xm-player-store 的状态 proto） | `PARITY.md:79`；`AssetOpLedger.java:3-6`、`:300-304` |
| G7 | `ListAppliedAssetOpsSince` 的 Java 接口 | guild-spec §6.6 |
| G8 | 本机脚本没注入 `XM_ASSET_OP_SECRET_GUILD`；scene 跑 Dubbo 提供方后也需要 `XM_DUBBO_SECRET`（提供方过滤器缺密钥即暴露失败），但 scene 不在读者名单 | `tools/local/start-slice.sh:3-17`；`DubboAuthProviderFilter.java:24-41` |
| G9 | scene 侧没有在途上限：逻辑线程 `DefaultEventLoop` 任务队列无界，异步提供方会把突发全部堆进去 | `SceneNode.java:223` |
| G10 | scene 侧资产通道没有指标（只有 INFO / WARN 日志） | `AssetOpService.java:128-137` |

### 4.5 Java 传输：接口（xm-api）

```java
// com.game.api.SceneAssetOpService —— xm-scene 提供；每个 scene 节点一份，按节点直连（不进注册中心）
public interface SceneAssetOpService {
    CompletableFuture<AssetOpResponse> debit(AssetOpRequest request);
    CompletableFuture<AssetOpResponse> abortDebit(AssetOpRequest request);
    CompletableFuture<AssetOpResponse> credit(AssetOpRequest request);
}
```
- 参数 / 返回用现成的 `xm.api.AssetOpRequest / AssetOpResponse`，Triple 走 protobuf；异步签名有先例 `ClientMessageService`（`xm-api/.../ClientMessageService.java:36`）。
- 三个方法对应 `AssetOpService.Rpc.DEBIT / ABORT_DEBIT / CREDIT`；规范串第 3 行用 `wireName()`（debit / abort_debit / credit），**与 Dubbo 方法名无关**（`AssetOpService.java:69-83`）。
- `DubboGroups` 新增一个常量（例如 `SCENE_ASSET = "scene-asset"`）只作分组标识。

### 4.6 scene 提供方（xm-scene）

1. **依赖与协议**：加 `dubbo-spring-boot-starter`；`dubbo.protocol.name = tri`；`registry.address = N/A`；服务 `@DubboService(register = false)`——地址只来自 Redis 节点目录
   （与 gate 连 scene 同一个发现源，不造第二份真相）。Dubbo 不接受回环绑定（`architecture.md` §4.1），端口对内网可达。
2. **端口**：同机多实例必须各不相同（同 `link-port` / `SERVER_PORT` 的规则）。建议 `xm.scene.asset-rpc-port`，缺省 21100（link 21000 旁边；现有 Dubbo 端口 20881–20886 已占）；见 Q2。
3. **鉴权两层**：Dubbo 调用方 MAC（`DubboAuthProviderFilter` SPI 自动生效，管 `com.game.*`，缺 `XM_DUBBO_SECRET` 暴露失败）+ 请求体 HMAC（已有 `AssetOpAuth`）。
   前者不覆盖请求体（`architecture.md` §4.1 残余风险），后者覆盖全部字段含 `;u=;p=`，互补。
4. **实现**：`assetOps()` 为 null（未就绪）→ 失败的 future（= 传输失败，调用方重投）；否则调 `AssetOpEndpoint`，并**在 future 上切出逻辑线程**
   （`whenCompleteAsync` / `thenApplyAsync` 到专用执行器）：Dubbo 的回写不应跑在场景逻辑线程上（`AGENTS.md` §3）。
5. **在途上限**（补 G9）：`xm.scene.asset-op-max-inflight`（建议缺省 256），Semaphore 控制，超出直接回失败的 future（「过载」）——调用方当传输失败、走 Retry，不会误记账。
   基线的上限是 8 条 poller 隐式给的；Java 异步提供方没有这层天然闸，必须显式加。上限应 ≥ 副本数 × Workers + 同步投递并发。
6. **生命周期**：启动时只在 `assetOps` 建好、Dubbo 导出成功之后，才在 `SceneNodeInfo` 带上 `rpc_host / rpc_port` 再发布目录（`SceneNode.java:332-345`）；
   停服 `release` 第一步摘目录（调用方随之找不到它），逻辑线程停后 `AssetOpEndpoint` 回 `RejectedExecutionException`（`AssetOpEndpoint.java:52-54`）= 传输失败；
   停服写回之后再到的请求是 NOT_HERE，此前已记账、已落库的结局可经 §4.9 的离线读终结。Dubbo 反导出排在摘目录之后、逻辑线程停之前。
7. **目录字段**：`SceneNodeInfo` 追加 `string rpc_host = 7; uint32 rpc_port = 8;`（Java 内部 proto，不是同步产物）。`rpc_port = 0` = 这个节点不提供资产通道（滚动升级期间的旧版本），调用方按「没人持有」处理。
8. **指标**（补 G10）：`xm_scene_asset_ops_total{rpc, outcome}`（outcome 含 `overloaded`、`error`）、`xm_scene_asset_ops_inflight`。

### 4.7 定位（xm-discovery；供 xm-guild 与以后的交易用）

`SceneAssetLocator.resolve(playerId)`，判定顺序照 `scenenode/locator.go:149-215`：

| 步 | 情形 | 结果 |
|---|---|---|
| 1 | 读 `xm:location:{pid}` 出错 | **故障**（Retry 并告警），不算「没人持有」 |
| 2 | 键不存在 / `s = x`（登出墓碑）/ `s = l`（重连租约） | 没人持有：本地合成 NOT_HERE。Java 断线即最终写回并释放归属，**租约期间没有任何节点持有该玩家**（`PlayerLocationDirectory.java:24-33`；`PARITY.md:87` 有意差异 ①） |
| 3 | 值解析失败 / `player_id` 与键不符 / 状态值不认识 | **故障**（照 `locator.go:145-146`；现有 `find` 会吞掉，所以要新增读法） |
| 4 | `SceneNodeInfo`（zone = 记录里的 zone_id）里没有 `scene_node_id`，或该条目 `rpc_port = 0` | 没人持有（node_unknown / no_rpc_port） |
| 5 | 目录读失败 | 故障 |
| 6 | 找到 `rpc_host:rpc_port`（+ instance_id） | 取（或建）这个地址的 Dubbo 客户端 |

- **新读法**：`PlayerLocationDirectory.findHolderAsync(pid) → {ONLINE(location) | NO_HOLDER(MISSING / LEASED / LOGGED_OUT) | ERROR}`（一段只读 Lua 同时取 `s` 与 `v`，异步）；
  `NodeDirectory` 增加按 (zone, nodeId) 的异步单条读（现在只有阻塞的 `list(zone)`，`xm-discovery/.../NodeDirectory.java:52-63`）。
- **基线有、Java 没有的状态**：AwaitingPlacement（Java 跨区随 5.4）、同身份歧义（Redis map 按节点号做键，天然唯一）、镜像未同步（每次现读目录；可加 1 s 本地缓存，陈旧只多一次 NOT_HERE 或传输失败，正确性在 scene 账本与围栏）。
- 节点号按区租约、实例退出即交还（`architecture.md` §6），位置记录可能指向「同号新实例」：新实例回 NOT_HERE，Caller 重定位，不影响正确性。
- **客户端缓存**：按 (host, port, instance_id) 缓存编程式 `ReferenceConfig<SceneAssetOpService>`（直连 `tri://host:port`、**`retries = 0`**、`check = false`、`timeout = 800`），
  条目在目录里消失或实例变了就 `destroy`（对应 `ConnCache.Remove`，`scenenode/conn.go:69-79`）。仓库里还没有编程式 ReferenceConfig 的先例（grep 无结果），实现时以测试为准；
  Dubbo 3 的「按调用指定地址」是备选。**`retries` 必须是 0**：Dubbo 缺省 failover 会重发，打乱预算与指标；重投由 Caller 与循环负责。
- **不读 `xm:presence`**：在线目录由 gate 写；资产通道要找的是**数据持有者**（持有归属的 scene 写的位置记录）。

### 4.8 签名共用件（xm-api）

- 把 `canonical / sign / CANONICAL_VERSION` 与「去首尾空白后 ≥ 32 字节」的密钥规则从 xm-scene 的 `AssetOpAuth` 挪到 **xm-api**（例如 `com.game.api.asset.AssetOpSignatures`）：
  `AssetOpRequest` 在 xm-api（`com.game.api.proto`），xm-api 已有 `com.game.api.auth` 鉴权代码；xm-common 不依赖 xm-api（`xm-common/pom.xml`），放不进去。
- scene 的 `verify` 与 xm-guild 的签名器都用它；golden 用例跟着搬、两侧各钉一次。对应基线「本包唯一允许出现串格式知识的地方」（`assetop/auth.go:72-74`）。
- 签名器写法：先在克隆的请求上设 `auth{caller = "guild", timestamp_ms = now}`，再按 canonical（它读 auth 里的 caller 与 timestamp）算 HMAC 写 `signature_hex`。
  trim 规则两侧必须同一个函数（Go `strings.TrimSpace` 与 Java `String.trim` 对 Unicode 空白不同；两侧都是 Java 后只要共用同一份代码即可）。

### 4.9 已落盘账本只读查询（Java）

- **介质**：Java 的 durable 就是 `xm_java.player_state` 里已提交、带 owner_epoch 围栏的那份记录（scene 读的 `persistedState` 是它的镜像），与基线「Redis 是 durable、MySQL 可能更旧」口径不同
  （`asset_op_ledger_logic.go:25-30`）。所以 Java **直接读 MySQL**，正好读到 durable 的那份，不需要 data_service 式中转。
- **读法**：`SELECT data FROM player_state WHERE player_id = ?`（`xm-player-store/src/main/resources/db/xm-player-schema.sql:37-40`）：没有行 → absent；解析失败 → error；
  没有 `asset_ledger` 段（`player_state.proto:34`）→ 空账本 → unseen；单次上限 300 ms；阻塞 JDBC，只在循环 worker 线程上调。
- **分类**：`restore` + 校验 + `classify` + `isPartial` + `rejectionReason`，与 scene 的 `AssetOpLedger` **同一份代码或同一张用例表**（基线是 Go / C++ 两份实现跑同一张表，`classify_test.go`）。放哪里见 Q3。
- **触发**：只有本地合成 NOT_HERE、`attempts ≥ LedgerReadMinAttempts(3)`、接了读端时才读（`reconcile.go:503-505`、`:641-667`）；读到未见**绝不判中止**（I7）。
- **安全性**：读到的是已提交、带围栏的记录，按 I2 已记账结局不变，所以可当 durable；唯一能让它「消失」的是回档（7.2），见 §9.2。

### 4.10 Kafka 的角色

只做审计：scene 应用时按请求 tx 记流水、关联号 = op_id（`PARITY.md:63`），给人工对账与 assetopfix 核对用。投递、确认、推送都不走 Kafka；基线同样不走（资产指令没有 Kafka 生产者，帮会的 Kafka 只用于 gate-cmd 推送，guild-spec §0.1）。

### 4.11 Java scene 契约对调用方的影响

- 应答 `reason` 是 uint32（不是 TipInfoMessage），没有 snapshot（`xm-api/.../asset_op.proto:76-83`）。
- 流水原因白名单：GUILD_DEBIT 只收 24，GUILD_CREDIT 收 25、26（`AssetOpService.java:90-94`）——帮会请求必须带对 tx_type，否则 UNKNOWN 27004（Alert，不终结）。
- 物品整批原子，物品段不会部分发放；账本损坏 RETRY 27005；退出存盘在途是 NOT_HERE（`AssetOpService.java:30-49`）。
- durable 要等 MySQL 落库确认；`SceneWorld.requestSave` 回 `IN_FLIGHT` 时不会自动追加存盘（`SceneWorld.java:795-833`），要靠 ≥ 500 ms 后的重查或下个周期才追平，
  所以同步路径更常走 AwaitDurable（指标上体现为 `requery{timeout}` 与 `reschedule{await_durable}` 偏高），符合预期。

---

## 5 推送

| kind | 何时 | actor / target | 收件人 | 出处 |
|---|---|---|---|---|
| LEVEL_UP 10 | 升级 `Changed` | 操作者 / 0 | 全体成员 − 操作者 | `economy_logic.go:851-855` |
| FUNDS_CHANGED 9 | DONATE 在**后台**被本次终结（任何终态，含拒绝 / 中止：客户端据此重拉、看到次数已退） | 0 / p | [p] | `:667-691` |
| DELIVERY_DONE 13 | SHOP 或 ACTIVITY_REWARD 在后台被本次终结 | 0 / p | [p] | 同上 |

- 同步投递里终结的不推（调用方自己拿着回包，`syncDeliveryKey`）；assetopfix 不推；未知 kind 记 ERROR、不推（`:676-691`）。
- **只推本人**：不广播全帮，避免 100 人帮会的推送风暴；其他成员打开界面时自己拉（终结已失效缓存）。
- 推送至多一次、失败不影响结果（`push.go:5-15`）；指标 kind label `funds_changed` / `level_up` / `delivery_done`（`push.go:240-266`）。
- **客户端**（`GuildClient.cs:195-203`）：收到 FUNDS_CHANGED / DELIVERY_DONE 且 `target == 自己` → 重拉背包，并给拉过的捐献页 / 商店页排队重拉；这一判断先于「是否本帮」，离帮后才结算的那一笔也能刷新。
- Java：`OnFinalized` 回调用**显式参数** `DeliveryOrigin.SYNC / LOOP / MANUAL` 代替 ctx 值（E9）；推送经 `GuildPushes → PlayerPushes`，与 4.4 同（guild-spec §4.5）。

---

## 6 与 4.4 的接口

### 6.1 4.4 留下的钩子 → 4.5 怎么填（对照 guild-spec §6）

| 4.4 钩子 | 4.5 填法 | 基线出处 |
|---|---|---|
| `guild.funds / level / max_members`、`guild_member.contribution_total / balance` 已建，写 0 或初值 | 捐献终结加资金与帮贡；兑换预留扣 balance、拒绝退回；升级改 level / funds / max_members | `guild_db.proto:31`、`:34`、`:37`、`:68-69`；`economy_repo.go:105-110`；`asset_store.go:241-248` |
| GuildInfo 装配已填 `funds`、成员帮贡、`max_officers`、`upgrade_cost_funds` | 4.5 不改视图代码；快照格式不变 | `guild_logic.go:747-784`；guild-spec §6.1 |
| 锁序 G<S<M<A<**Q(5)<O(6)<C(7)**<P(8) | 4.5 在 `guild_tables.proto` 追加 Q、O、C 三个 message（声明顺序 = 锁序）；捐献 / 兑换普通读 G，升级 G `FOR UPDATE` | `tables.go:15-34`；guild-spec §6.2 |
| `MemberExitHooks.accelerateDonationDeadlines`（4.4 空操作） | 实现 §2.9（O1 → O2 → O3） | `economy_repo.go:370-456`；guild-spec §6.3 |
| op 标签 `upgrade / asset_finalize / donate / shop` 已预注册 | 直接用（Java `GuildTxOp`、`InvalidationOp` 已有） | `guild_manage_repo.go:410-421`；`xm-guild/.../store/GuildTxOp.java:26-32` |
| 推送 kind 9 / 10 / 13 已预留 | 本批发出 | `push.go:240-266` |
| 事务基座 `inTx` / `invalidateAfterCommit` / `mapWriteErr` / `verifyMapping` / `operatorGuild` / `ResolvePlayerGuild` / `VerifyPlayerGuildID` / `leftGuildWhileResolving` | 请求路径复用；后台写**不用** inTx（§1.5） | `economy_repo.go:168-188`；`asset_store.go:16-19`；guild-spec §6.4 |
| 派发器 53 / 76 / 120 / 228 / 233 回 in-band 1006（D13） | 4.5 只换处理器，不动白名单与启动校验 | guild-spec §6.5 |
| 发号失败 14008（D8） | op_id 发号失败沿用 14008 | `economy_logic.go:386-401`；guild-spec §6.6 |
| `GuildReject` 注释已预告经济哨兵 | 追加 `LEVEL_TOO_LOW、DONATE_LIMIT、SHOP_LIMIT、CONTRIBUTION_INSUFFICIENT、MAX_LEVEL、FUNDS_INSUFFICIENT、TOO_MANY_PENDING`；`GuildTips.forReject` 穷举 §3.0 的表 | `xm-guild/.../rules/GuildReject.java:10-12` |

### 6.2 `MemberExitHooks` 的契约（4.4 调用方必须满足）

- 三个调用方在调用前**已持有**这些 (G, p) 的 guild_member X 锁（被踢：`lockMemberPair` 锁目标；退帮：锁本人；解散：锁全体），且 `now > 0` 由调用方传入（guild-spec §6.3）。
- 位置：删成员行与删申请之后、快照或删 G 之前；成员行锁持有到提交，捐献预留（先锁同一成员行）被挡在外面。
- Java 实现要求：候选读不带锁定子句；点锁与点改按 `Long.compareUnsigned` 升序；影响 0 行跳过。

### 6.3 与 4.6 的共享件（4.5 落地时按可复用的形状写）

- 同一个 `AssetOpLoop`、同一个 op_id 发号器、同一个插行租约（`guild.go:300-313`）。
- `GUILD_CREDIT` 流上还有 `ACTIVITY_REWARD` 行：终结只 CAS，推 DELIVERY_DONE（`asset_store.go:893-894`；`economy_logic.go:684-685`）；商店页按 kind 过滤（§3.4）。
- 计数行 `ACTIVITY` 的写者同样要先持 Q 行（§1.2 第 8 条）；新增调 `AllocateSeq` 的路径要先锁同一成员行（第 7 条）。
- 通道关闭时带物品的活动在发号前回 14026，不带物品的照常（`guild.go:300-301`）。

---

## 7 Java 落地映射

### 7.1 模块与类（照 guild-spec §7.1 的分层；领域对象 + XxxService，不用 ECS 命名）

| 组件 | Java | 说明 |
|---|---|---|
| 表 proto | `guild_tables.proto` 追加 `GuildPlayerOpSeqRow`、`GuildAssetOpRow`、`GuildDailyCounterRow` 与三个枚举 | §7.2 |
| 经济仓储 | `store/JdbcEconomyStore`（T-D、T-S、T-U、读 C2 / C3 / M14 / O6 / O7 / O8、提前截止） | SQL 逐字照搬 §1.4；请求路径用 4.4 的 `GuildTx` |
| 资产 Store | `asset/JdbcGuildAssetStore`（ListDue、Claim、Reschedule、毒行、Finalize、ResolveManually、最老未决、清理、ListApplied） | 后台写用新的 `BackgroundTx`（§7.3） |
| 纯规则 | `asset/AssetOpDecisions`（Decide、FinalStatus、NextAttemptMs、ApplyRpcOf、counterRefund、StatusToRecord）；`service/EconomyTables`（校验、MaxBuyCount、查表）；xm-common `GameDay` | 纯函数整表单测 |
| 通道客户端 | `asset/AssetOpSigner`（用 xm-api 共用件）、`asset/AssetOpCaller`（定位、调用、换节点、重查、翻转）、`asset/AssetOpLoop`（Tick、claim、processOne、settle） | 照 `go/shared/assetop` 写；放 xm-guild，交易接入时再抽（Q4） |
| 定位 | xm-discovery `location/SceneAssetLocator` + `PlayerLocationDirectory.findHolderAsync` + `NodeDirectory` 单条读 | §4.7 |
| 账本读端 | `PersistedLedgerReader`（读 `player_state.data`，解出 `asset_ledger`，交共享分类器） | §4.9；Q1 / Q3 |
| 服务 | `service/GuildEconomyService`（五个 RPC）；`service/EconomyViews`（视图与最近结果） | §3 |
| 推送 | `push/GuildPushes` 增加 9 / 10 / 13 | §5 |
| 钩子 | `MemberExitHooks.accelerateDonationDeadlines` | §2.9 |
| 内部查询 | xm-api `GuildInternalService.listAppliedAssetOpsSince`（group `guild`），xm-guild 提供 | §7.11；Q7 |
| 人工终结 | xm-guild 内 CLI 主类 `asset/fix/AssetOpFixMain`（list / resolve；前置、退出码、审计行照搬） | §7.10 |
| scene 提供方 | xm-scene `asset/SceneAssetOpProvider implements SceneAssetOpService` | §4.6 |

### 7.2 表 proto（`xm-guild/src/main/proto/xm/guild/guild_tables.proto` 追加）

- 字段号、`primary_key`、`unique_key = "player_id,stream,stream_epoch,seq"`（→ `uk_guild_asset_op`）、`index = "status,next_attempt_ms;guild_id,op_id;player_id,stream,stream_epoch,status,seq"`
  （→ idx_0/1/2，**顺序不能变**）、`GuildDailyCounterRow` 的 `index = "period_key"`、TiDB 三个选项，全部照抄 `guild_db.proto:96-197`；message 声明顺序 = 锁序。
- pbmysql 类型映射与 Go 逐字相同：枚举 → `int NOT NULL DEFAULT 0`、`bytes` → MEDIUMBLOB（可空）、`string` → MEDIUMTEXT（可空）、`uint64` → `bigint unsigned NOT NULL DEFAULT 0`
  （`xm-pbmysql/.../TableSchema.java:60-75`；键列里的 string / bytes 才特殊处理，`:228-236`，本批的键列都是整数 / 枚举）。
- Java 测试复刻 `asset_tables_shape_test.go`（索引名、枚举列是 int、Q 无二级索引）与 `TestAssetOpColumnsCoverEveryProtoField`（28 列清单 = proto 字段序）。

### 7.3 仓储与事务

- **请求路径**：4.4 的 `GuildTx`（原始 JDBC、RC、子预算 1500 ms、1213 重跑 3 次、1205 不重试 → `WRITE_CONFLICT`，guild-spec §7.6）。T-U 的 staleView 分支「提交且失效缓存」要能从闭包里带出。
- **后台写 `BackgroundTx`**：RC；1213 / 9007 / **1205** 都重跑；3 次（清理 1 次）；退避 10 ms 起指数、±20% 抖动（`ThreadLocalRandom`）、封顶 200 ms；子预算 = min(自身, 调用方给的 settle Deadline)。
- **结果类型**：Finalize 返回 `(finalized, counterpartyOutcome)`；副作用（orphan 计数、日志、缓存失效、回调）只在提交后发（闭包可能重跑）。
- **无符号**：`op_id, seq, stream_epoch, lease_token, next_seq, epoch, funds, contribution_*, created_ms…` 全部按无符号绑定与读取（`Long.toUnsignedString` / `BigInteger`）。
  **`lease_token` 约一半 ≥ 2^63**，按有符号 long 绑定会被严格模式拒（1264）；O1 / O20 / 锁序里的升序用 `Long.compareUnsigned`（guild-spec §7.5）。
- **影响行数**：连接池必须 `useAffectedRows=true`（guild-spec §7.5 已要求）：C1 的 1/2/0、`execExactlyOneRow`、「加 0 跳过」都依赖它。
- **TiDB 规则照抄**：O2 点锁先于带复核写；Claim 自动提交、不进事务；清理逐行短事务（语句同一套，回归测试可直接复用）。
- 计数周期键只用请求开头 `start` 算（C5 前提，`economy_repo.go:343-344`）。

### 7.4 线程、预算、超时

- **请求路径**：`economyCaller`、预留事务在 guild-worker 上（阻塞 JDBC，guild-spec §7.4，缺省 16 线程）。**同步投递不占 worker**：`AssetOpLoop.processOne(op, DeliveryOrigin.SYNC, budget)`
  返回 `CompletableFuture<Processed>`——Dubbo 异步调用 → 重查等待用调度器定时（不 sleep）→ settle 在有界的 `guild-asset-settle` 执行器上跑阻塞 JDBC（700 ms 自有 Deadline，不随请求取消）
  → 回到 guild-worker 做 O6 回读与装配，最后完成 `ClientReply` future（Q5）。
- **预算**：`budget = min(2500, 请求剩余 − 1000)`，< 300 ms 跳过并计数；单次调用 `min(800 ms, 剩余)`；投递总额 `OpBudget − 700`；回读 O6 1000 ms 子预算。
- **后台循环**：一个调度线程按 Interval 跑 Tick；`Workers`（8）条专用线程的有界池；worker 内可以阻塞等待 Dubbo future（专用池、上限即并发上限，与基线 goroutine 等价）；Tick 等本批结束。
- **清理**：单独调度线程，首轮随机延迟；与循环独立开关。
- **定位读**：同步路径用异步 Redis 读；循环 worker 上可阻塞。
- **时钟**：注入 `InstantSource`；一个请求只取一次 now。

### 7.5 通道客户端与循环（照 `go/shared/assetop`）

| Java 组件 | 对应基线 | 要点 |
|---|---|---|
| `AssetOpSigner` | `auth.go:57-67`、`:151-162` | 读 `XM_ASSET_OP_SECRET_GUILD`，去空白后 ≥ 32 字节；caller 恒 `"guild"`；**通道开启时缺失即拒启**；每次发包克隆并现签；密钥值不进日志 |
| `AssetOpCaller.deliver(rpc, request)` | `caller.go:73-229` | 本地合成 NOT_HERE；**只有换了节点**才重调；100 / 200 / 400 ms 重查；翻转检测；单次 `min(800 ms, 剩余)` |
| `AssetOpDecisions` | `decide.go`；`types.go:114-187` | 整表单测照 `decide_test.go` |
| `AssetOpLoop` | `reconcile.go` | 缺省值与区间完全照 §0.6；settle 700 ms 从 OpBudget 切出、不随取消；27007 粘性；最老未决每 30 s；令牌 = `SecureRandom.nextLong()` 再把最低位置 1 |
| `JdbcGuildAssetStore` | `asset_store.go`；`economy_repo.go` | SQL 逐字照搬；Finalize / ResolveManually 同写 `next_attempt_ms = now` |

### 7.6 配置项

**xm-guild `xm.guild.asset-op.*`**（键与缺省照 §0.6；Spring 属性缺省值，`enabled` 缺省 false = fail-closed，本机切片 yaml 设 true）

| 项 | 缺省 | 校验 |
|---|---|---|
| `enabled` | false | 开启时才校验下面的循环参数、建签名器 / 定位 / 循环 |
| `reconcile-interval` / `reconcile-batch` / `workers` | 2s / 100 / 8 | [200 ms, 60 s] / [workers, 1000] / [1, 64] |
| `lease` / `op-budget` | 10s / 2500ms | [op-budget + 2 s, 600 s] / [1 s, 10 s] |
| `max-backoff` / `poison-delay` / `ledger-read-min-attempts` | 60s / 1h / 3 | [1 s, 600 s] / [60 s, 24 h] / ≥ 1 |
| `cleanup-enabled` / `cleanup-interval` | false / 10m | [1 min, 1440 min] |
| `terminal-retention` / `counter-retention` | 30d / 30d | [7 d, 365 d] |
| 交叉校验 | — | `lease + reconcile-interval < GuildRule.asset_op_deadline_seconds`；`asset_op_retry_base_ms ≤ max-backoff`（开启时） |
| 代码常量（不开放） | 同步预算 2500 / 尾巴 1000 / 下限 300 ms；单次调用 800 ms；重查 100/200/400；settle 700；AwaitDurable 500；FreshAttemptLimit 3；守卫 16 / 512；清理 500 / 100 ms / 20 / 2 s / 8 d；离线读 300 ms | §0.7 |

**xm-scene**：`xm.scene.asset-rpc-port`（缺省 21100，同机多实例必须覆盖）、`xm.scene.asset-op-max-inflight`（缺省 256）；`XM_DUBBO_SECRET` 变成必填；`XM_ASSET_OP_SECRET_GUILD` 照旧。

### 7.7 启动 / 关停顺序（照 `guild.go:85-393`）

1. 配表校验：4.4 `GuildTableRules` + `EconomyTables`（含 Item 存在与 MaxBuyCount），失败拒启。
2. pbmysql `syncAll`（4.4 的四张 + Q / O / C）。3. 版本检查、插入守卫（guild-spec §7.11）。
4. 发号租约（guild_id 与 op_id 共用，E6）。
5. `asset-op.enabled`：交叉校验 → 签名器（缺密钥拒启）→ 定位器 → Caller → Loop（未启动）→（Q1 采纳时）挂账本读端。关闭：打一条 ERROR。
6. 挂 `onFinalized`（推送），**先于**循环与 Dubbo 暴露。
7. 重建排行（4.4）。8. 暴露 Dubbo（GuildService、GuildInternalService）。9. 启动循环与清理。
- 关停：停 Dubbo 并排空在途请求 → 停循环（等 worker 结束；落库 700 ms 不受取消）与清理 → 关 Dubbo 客户端缓存 → 关数据源与 Redis。用 `SmartLifecycle` phase 保证顺序。

### 7.8 本机脚本（`tools/local/start-slice.sh`）

- `XM_ASSET_OP_SECRET_GUILD` 没设时生成本机随机值写进 `run/xm-asset-op-secret-guild`（权限 600，先例 `run/xm-admin-token` / `run/xm-gm-admin-secret`，`:24-39`），**同一个值**传给 xm-scene 与 xm-guild。
- 第 6 行的 `XM_DUBBO_SECRET` 读者名单补上 xm-scene。启动顺序里 xm-guild 先于 xm-scene（`:47-59`）无妨：循环找不到节点只会本地 NOT_HERE、退避。

### 7.9 `GuildReject` / `GuildTips`

- 追加的拒绝与 tip 对照见 §3.0；`TOO_MANY_PENDING` 记 Info、result pending_guard；`LEVEL_CONFIG_MISSING` 仍是故障。
- 通道关闭（14026 `"guild asset channel disabled"`）、配表缺行（故障）、发号失败（14008）是 service 层的答复，不经 `GuildReject`。
- 经济五个号的派发：去掉 4.4 的 in-band 1006 占位（D13），PARITY「帮会核心」行的 D13 临时差异随之撤销。

### 7.10 人工终结 CLI

- 必须复用 xm-guild 的 `JdbcGuildAssetStore.resolveManually`（与自动终结同一 terminate，DRY）与缓存失效器，所以放在 xm-guild 内作为第二个主类（`java -cp` 或 Spring Boot `PropertiesLauncher -Dloader.main=…`，非 web、不暴露 Dubbo、不启动循环）。
- 只连 MySQL 与 Redis；不推送；子命令、前置条件、`-txlog-checked` 核对清单、审计行格式、退出码 0/1/2/3 全部照搬 §2.12。若采纳 E12，核对清单第 0 步可简化为「last_outcome = 0 即 scene 真回了 UNKNOWN」。

### 7.11 内部查询

- xm-api `GuildInternalService.listAppliedAssetOpsSince(ListAppliedAssetOpsSinceRequest) → CompletableFuture<…Response>`（Java 自有 `xm.api` 消息，形状同 `guild_internal.proto`；先例：资产通道也没复用同步来的内部 proto，`xm-api/.../asset_op.proto:1-4`）。
- 入参上限、排序、游标、limit+1、普通读、LEFT JOIN 全部照基线；错误语义改成应答内结果码 `OK / INVALID_ARGUMENT / UNAVAILABLE / RETENTION_REJECTED（带 cutoff_ms 字段）/ ERROR`（E11）。
- Spring 缺省值让 `terminal-retention` 恒为 30 d（基线 AssetOp 整段缺失时为 0 → Unavailable），所以 Java 通道关闭时内部查询照常可用（无客户端可见影响）。
- PARITY 注明「Java 无消费方，随 7.2 回档接入」。

---

## 8 指标

### 8.1 基线（label 一律不放 id）

| 名称 | 类型 / label | 出处 |
|---|---|---|
| `guild_economy_requests_total{rpc, result}` | rpc ∈ get_donate_options / donate / upgrade / get_shop / buy_shop_goods；result ∈ ok, unchanged, applied, pending, rejected, aborted, applied_partial, limit, insufficient, pending_guard, busy_retry, fence, not_member, level, rank, not_found, disabled, id_unavailable, denied, unavailable, other_reject, error | `economy_logic.go:83-133` |
| `guild_asset_sync_skipped_total{kind=donate\|shop}` | 剩余预算不足跳过同步投递 | `:135-141` |
| `guild_asset_orphan_total{kind, what}` | kind ∈ donate / shop；what ∈ guild_gone / member_gone / refund_member_gone | `asset_store.go:144-164` |
| `guild_asset_cleanup_deleted_total{table}` | guild_asset_op / guild_daily_counter | `:166-172` |
| `guild_internal_list_applied_total{result}`（ok_empty / ok_rows / invalid / retention / unavailable / error，启动预置）、`guild_internal_list_applied_rows`（桶 0,1,5,20,100,500） | 内部查询 | `guild_internal_server.go:67-118` |
| `guild_tx_*{op=donate\|shop\|upgrade}`、`guild_cache_invalidate_failed_total{op=…\|asset_finalize}` | 复用 4.4 | `guild_manage_repo.go:358-391` |
| `assetop_*`（ConstLabels `service="guild"`）：`rpc_total{stream,rpc,outcome}`（outcome 含 error、no_location）、`rpc_seconds{rpc}`（桶 .005–2.5 s）、`requery_total{rpc,result}`、`finalize_total{stream,status}`、`reschedule_total{stream,reason}`、`reschedule_lost_total{stream}`、`unknown_total{stream}`、`outcome_flip_total{stream}`、`partial_total{stream}`、`claim_total{result}`、`ledger_read_total{result}`、`manual_resolve_total{status}`、`store_errors_total{op}`、`pending_oldest_age_seconds{stream}` | 资产通道 | `assetop/metrics.go:41-119`；`svc/asset_op.go:347-359` |
| `scenenode_nodes{service,kind}`、`scenenode_resolve_total{service,result}`（found / not_online / awaiting_placement / node_unknown / mirror_unsynced / node_ambiguous / error） | 定位 | `scenenode/metrics.go:9-59` |

### 8.2 Java 建议（Micrometer，低基数；player_id / op_id / seq 一律不进 label；label 取值启动时预注册）

| 指标 | 标签 | 对应 |
|---|---|---|
| `xm_guild_economy_requests_total` | `rpc`、`result`（集合同基线，去掉 Java 不存在的 denied / unavailable） | `guild_economy_requests_total`（4.4 的 `xm_guild_requests_seconds{method,result}` 照常） |
| `xm_guild_asset_sync_skipped_total` | `kind` | 同名基线 |
| `xm_guild_asset_orphans_total` | `kind`、`what` | `guild_asset_orphan_total` |
| `xm_guild_asset_cleanup_deleted_total` | `table` | 同名 |
| `xm_guild_internal_list_applied_total`、`xm_guild_internal_list_applied_rows` | `result` | 同名 |
| `xm_guild_assetop_*` | 与基线 `assetop_*` 全集相同的名字与 label（`rpc_seconds` 用 Timer） | `assetop_*` |
| `xm_guild_scene_resolve_total` | `result` = found / not_online / lease / logged_out / node_unknown / no_rpc_port / error | `scenenode_resolve_total` |
| `xm_scene_asset_ops_total`、`xm_scene_asset_ops_inflight`（xm-scene） | `rpc`、`outcome`（含 overloaded / error） | Java 增项（E13） |
| `executor_*{name="guild-asset-settle" \| "guild-asset-worker"}` | Micrometer 标准 | 同 friend |

- `architecture.md` §11 的指标总表补 guild 经济与 scene 资产两组；告警建议：`xm_guild_assetop_pending_oldest_age_seconds > 600`、`unknown_total` / `outcome_flip_total` / `partial_total` 出现即告警、`orphans_total` 持续上升。

---

## 9 隐患与边界

### 9.1 基线自身（移植时要么照搬，要么显式偏离并登记 PARITY）

1. **离线账本读没接线**：`LedgerReadMinAttempts` 不生效；离线玩家的捐献连 Abort 都发不出去，一直占着当日次数（§2.10）。Java 建议接线（E8）。
2. **退避没有抖动**：`Loop.Rand` 从未赋值（§2.5）；而配表列注释（`guildrule_table.proto:44-47`）与 `NextAttemptMs` 的注释都写着「叠加抖动、防惊群」。Java 建议启用（E10）。
3. **同步投递用内存里的截止**：提交之后、投递之前被踢的玩家仍会被扣款，钱照记原帮、帮贡记 orphan（§2.3）。设计如此（D2），照搬。
4. **兑换的 `count > limit_count` 在发号之后才判**，白烧一个 op_id；`count > MaxBuyCount` 在发号之前判（`economy_logic.go:901-903`；`economy_repo.go:660-664`）。照搬（号不稀缺）。
5. **待结算列表先截 16 条、再按 kind / 帮会过滤**：4.6 的活动奖励与离帮前的旧捐献会挤占名额（`economy_logic.go:1081`、`:1113-1117`、`:1158`、`:1198-1202`）；守卫已保证本纪元未决 ≤ 16，影响很小。照搬。
6. **两个读页的 `unlocked` 用缓存快照等级**：失效失败时最多陈旧一个 TTL；写入时事务复核。照搬。
7. **回读失败一律显示 PENDING**，哪怕行已终结（`economy_logic.go:525-545`）；客户端随后的拉取自愈。照搬。
8. **DONATE APPLIED 但帮会已解散**：钱扣了，资金帮贡都不记，次数也不退，只计 orphan（D2，接受，`asset_store.go:856-860`）。
9. **拒绝原因环满了以后，Abort 重投时真业务拒绝显示成 ABORTED**（账务相同，接受，`decide.go:82-92`）。
10. **assetopfix 分不清 UNKNOWN 与传输失败**（都记 `last_outcome = 0`，`assetopfix/main.go:62-67`）。Java 建议修（E12）。
11. **内部 RPC 无凭据**，只靠网络隔离（`guild_internal_server.go:11-13`）。Java 的 Dubbo 调用方 MAC 覆盖了这一点。
12. **ListDue 老行无保底**：新行持续占满时故障期积压的老行排很久，靠最老未决年龄告警（`reconcile.go:114-118`）。照搬（改要先改 X-03）。
13. **`expected_level = 0` 表示不比对**（proto 缺省），可能连升；客户端总会带值（`economy_repo.go:856`；`GuildClient.cs:529-536`）。照搬。
14. **捐献回包的数值取请求时的配表**而不是行上的值，热更换表瞬间可能不一致（`economy_logic.go:796-806`），可忽略。
15. **最坏投递 3900 ms 装不进 1800 ms**：慢路径必然 AwaitDurable（`reconcile.go:46-55`），清醒取舍。
16. **注释与代码不符**（Java 不要照抄注释）：
    - `asset_store.go:267` 说「op_id 是雪花号、随创建时刻单调增」——基线实际来自号段（多副本之间只近似按时间排序，清理「按 op_id 取老行」只是近似；Java 用雪花反而成立）；
    - `guild_db.proto:135` 说 seq 行「在事务外 INSERT IGNORE 建」、`:144` 说 AllocateSeq「对未决行加锁读」——现在都已改为事务内建行与普通读；
    - `caller.go:23-25` 说「800 + 700 ms 留在 2500 ms 内」——最坏 3900 ms（`reconcile.go:48-55` 已更正）；
    - `guild_internal.proto:20` 说「占消息号」——生成的 message_id 里没有它；
    - `guild.proto:227-228` 说「服务端按值直接转换」——代码是显式 switch。

### 9.2 Java 移植时会踩的

1. **scene 在途上限必须有**（§4.6 第 5 条）：异步提供方 + 无界逻辑队列，一次积压回放（scene 恢复后各副本的老行同时到期）会把移动 / 视野帧挤在后面——与基线「poller 被占满」同类事故（`reconcile.go:229-241`）。
2. **`retries = 0`、每次重签、`correlation_id = op_id`、同一 seq 重投、令牌 CAS**：任何一条做错，幂等就只剩 Finalize 的 CAS 一道防线。
3. **定位读失败不能当成离线**：会触发离线读与本地 NOT_HERE 退避，把数据面事故藏进看似正常的计数（`locator.go:145-146`）。现有 `PlayerLocationDirectory.find` 正是这种写法（G3），不能复用。
4. **scene 的 future 在逻辑线程上 complete**（`AssetOpEndpoint.java:45`）：提供方不切线程，Dubbo 序列化与回写会跑在逻辑线程上。
5. **无符号绑定**：`lease_token` 随机 64 位，必须按无符号绑定；`Long.compareUnsigned` 排序（§7.3）。
6. **`useAffectedRows=true` 漏掉**：C1 达上限会被当成成功，捐献次数与限购被突破、帮贡多发。
7. **把同步投递放进 guild-worker 阻塞**：16 线程 × 2.5 s，scene 一慢整个帮会服务排队（§7.4；Q5）。
8. **settle 用请求 Deadline**：请求预算跑满后终局写不回、下一轮重投；settle 必须自带 700 ms 且不随取消。
9. **回档（7.2）会让已 durable 的结局从 `player_state` 里消失**：在回档闸接入 `ListAppliedAssetOpsSince` 之前，Java 不得提供回档入口（I4 与 fail-closed）；离线读账本（E8）也依赖这一点。
10. **xm-guild 依赖 xm-player-store 会带进 `PlayerStoreAutoConfiguration`（MyBatis）**（`xm-player-store/.../PlayerStoreAutoConfiguration.java`）：要么排除自动配置，要么把只读类放在不触发它的位置（Q3）。
11. **4.4 若在派发层统一检查归属区**：经济五个号要豁免（§0.2）。
12. **Spring 缺省值与 Go「整段缺失 = 零值」不同**：`asset-op.enabled` 必须显式缺省 false；保留期等缺省值在关闭时也有值（§7.11，无客户端可见影响）。

### 9.3 边界速查

| 情形 | 结果 |
|---|---|
| 通道关闭 | 53 / 233 → 14026，不写行；76 / 120 / 228 照常；清理按自己的开关；内部查询照常 |
| 未决 ≥ 16 或跨度 ≥ 512 | 14026，不写行 |
| scene 不可达（传输错误） | PENDING，按退避重投；捐献超过 600 s 后改发 Abort（仍要 scene 可达才能终结） |
| 玩家离线 | 本地 NOT_HERE，退避；Java 接 E8 后 attempts ≥ 3 时读已落盘账本，已记账的提前终结 |
| 背包满（兑换） | RETRY 27001，一直 PENDING（永不中止），视图 reason 27001 |
| 余额不足（捐献） | durable REJECTED 27000 → 14025、视图 REJECTED、次数退回 |
| 部分发放 | APPLIED_PARTIAL：只终结、不做对侧账、不自动清理、ERROR 日志 |
| scene 回 UNKNOWN | Alert：60 s 重排、永不终结，人工 assetopfix |
| 结局翻转 | Alert，同上，计 outcome_flip |
| 离帮后捐献被 Abort 却回 APPLIED | 资金照记原帮，帮贡 orphan{member_gone} |
| 兑换被拒但已离帮 | 帮贡退不回去（orphan{refund_member_gone}），限购照退 |
| 帮会已解散后捐献 APPLIED | orphan{guild_gone}，什么都不记 |
| 毒行（payload 解不开 / 为空） | 推迟 1 h，计 decode 错误 |
| 升级 expected_level 过期 | 不扣钱、回最新 GuildInfo、失效缓存、不推送 |

### 9.4 对分区规格的勘误

1. 分区一写 `deliverNow` 在 `economy_logic.go:419-456`：`:419-436` 是 `syncBudgetFor / clampSyncBudget`，`deliverNow` 本身在 `:438-456`。
2. 分区一 E2 写「位置记录状态为 o 或 l 时有 scene_node_id」并按此定位：Java 断线即写回并释放归属（`PARITY.md:87` 有意差异 ①），`l` 状态下没有任何节点持有该玩家，应本地 NOT_HERE。采用分区二的处理。
3. 分区一 E4 建议把规范串挪到 xm-common：xm-common 不依赖 xm-api（`xm-common/pom.xml` 只依赖 xm-proto），而 `AssetOpRequest` 在 xm-api。改为 xm-api（同分区二）。
4. 分区一说「锁序回归六组」：实际 `TestEconomyLockOrder_*` 有 7 个（`economy_repo_test.go:2023`、`:2110`、`:2307`、`:2383`、`:2425`、`:2498`、`:2596`），另有 `TestReserveDonation_FirstSeqRowCreatorsSerializeOnMember`（`:2206`）。
5. 分区一说内部查询「不占消息号」与 `guild_internal.proto:20` 注释「占消息号」矛盾：以生成物为准（基线 `message_id.go`、Java `message_id.txt` 都没有它的号）；注释陈旧，列入 §9.1 第 16 条。
6. 分区一的 SQL 编号 E1–E46 与有意差异 E1–E15 撞号：本稿 SQL 改按表字母并接续 guild-spec §1.9（G11–G15、M12–M16、O1–O22、Q1–Q5、C1–C7），差异用 E1 起。
7. 分区一 E1 给了「目录直连」与「Dubbo tag 路由」两种做法：local profile 没有注册中心（`architecture.md` §6），tag 路由只在 nacos profile 可用；本稿定为目录直连（E1）。
8. 补充分区二：scene 回 NOT_HERE 后重定位若是 no-holder 错误，返回的是 scene 的 NOT_HERE（Local = false），**不会**触发离线读账本（`caller.go:120-129`）。
9. 补充两份分区稿都没写的：GuildRule 列注释与 `NextAttemptMs` 注释都把「叠加抖动」写成设计意图（§9.1 第 2 条），这是 E10 的依据；
   AssetOp 整段缺失时内部查询的保留期为 0、一律 Unavailable（`guild.go:366-367`；§0.6）；客户端在 `error_message` 非 0 时不读 donation / order 视图（`GuildClient.cs:491-498`、`:584-589`）。

---

## 10 建议的有意差异（E 系列，接在 4.4 D1–D18 之后）

### 10.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| E1 | **传输**：scene 新增 Dubbo Triple 异步提供方 `SceneAssetOpService`（debit / abortDebit / credit），`register = false`，调用方按节点直连 URL，`retries = 0`、单次 800 ms；scene 显式在途上限（缺省 256），回调切出逻辑线程 | Java 服务间统一用 Dubbo（`architecture.md` §4.1）；基线是同步 gRPC、scene 线程阻塞在 `future.get()`、poller 隐式限流 | 否 | 否 |
| E2 | **定位**：`xm:location:{pid}`（只认 `o`；`l` / `x` / 缺失 → 本地 NOT_HERE；损坏 / 键不符 / Redis 错 → 故障）+ Redis `NodeDirectory<SceneNodeInfo>` 新增 `rpc_host / rpc_port`（0 = 不提供）；代替 `player:{id}:location` + etcd `grpcEndpoint` 镜像 | Java 的发现源本来就是 Redis 目录（`architecture.md` §6）；定位错只会得到 NOT_HERE，正确性在 scene 账本 | 否 | 否 |
| E3 | **鉴权两层**：请求体 HMAC + Dubbo 调用方 MAC（`XM_DUBBO_SECRET`） | Java 的 Dubbo 端口无法只绑本机；基线端口 insecure、只靠请求体 HMAC | 否 | 否 |
| E4 | **密钥环境变量** `XM_ASSET_OP_SECRET_GUILD`（scene 侧 2.9 已是这个名字） | 两版各自的环境变量命名空间（基线 `MMORPG_ASSET_OP_SECRET_GUILD`） | 否 | 否 |
| E5 | **规范串单一出处**挪到 xm-api，scene 验签与 guild 签名共用，golden 两侧钉 | 两份实现分叉 = 验签失败 → UNKNOWN → 行卡死（`assetop/auth.go:116-117`） | 否 | 否 |
| E6 | **op_id 来自 xm-guild 的全服雪花**（`NodeTypes.GUILD` 租约，与 guild_id 共用生成器；租约无效 / 时钟回拨 / 返回 0 → 14008）；基线只用号段、禁止回退 snowflake | Java 没有号段服务（D8）；雪花 63 位、随时间单调（`xm-common/.../id/Snowflake.java:17-72`），清理「按 op_id 取老行」反而精确；correlation_id 在流水里时间有序 | 否（op_id 只当 uint64 展示） | 否 |
| E7 | **durable 介质 = MySQL `player_state` 围栏写提交成功**（2.9 已如此，登记在资产通道行） | Java 没有 Redis 存档层 | 否 | 否 |
| E8 | **离线读已落盘账本：Java 接线**，xm-guild 直读 MySQL `player_state.asset_ledger`，分类与 scene 共用（基线帮会未接线） | 介质就是 durable 本身，无需中转；离线玩家已记账的行可提前终结、不再一直占次数；路线图把 asset-op-ledger-read 排在 4.5（`roadmap.md:36`） | 否（只影响「结算中」持续时长） | 否（mmorpg 可选接线） |
| E9 | 同步投递标记用显式参数 `DeliveryOrigin`，代替 ctx 值 | Java 没有 ctx | 否 | 否 |
| E10 | **重投退避启用 ±20% 抖动**（基线 `Loop.Rand` 未设 → 不抖动） | 配表与代码注释的设计意图；scene 恢复后同一批行不在同一毫秒一起重投 | 否 | 否（mmorpg 可选：给 Loop.Rand 赋值） |
| E11 | **`ListAppliedAssetOpsSince`**：xm-api 类型化 Dubbo 接口，错误语义改为应答内结果码 + `cutoff_ms` 字段，不解析 message 前缀；4.5 只交付提供方与测试，消费方随 7.2 | Dubbo 异常类型不够细，解析字符串是脆弱的跨服务契约（`guild_divergence.go:43-46`、`:222-241`） | 否 | 否 |
| E12 | **传输失败时 Reschedule 不覆盖 `last_outcome / last_reason / durable`**（只推进 attempts、next_attempt_ms、清租约）；本地 NOT_HERE 与 scene 真实答复照常覆盖 | 让 assetopfix 的「last_outcome = UNKNOWN」前置可信（基线作者在 `assetopfix/main.go:62-67` 明确想要这一修法）；27007 粘性自然保留 | 否（PENDING 视图在断连期间保留上一个真实原因，而不是清成 0） | 否（mmorpg 可选） |
| E13 | scene 侧新增资产通道指标 `xm_scene_asset_ops_*` | 可观测性（基线 scene 侧只有日志） | 否 | 否 |
| E14 | **重连租约期间**（`l`）资产操作一律本地 NOT_HERE，玩家上线后再投 | 3.3 已登记的差异（Java 断线即写回释放、不复用内存实例）的推论；基线租约内实体还在、可以应用 | 否（到账时机） | 否 |

### 10.2 列出但不建议首批采纳（或需要两版同改）

- **EN1：兑换的 `count > limit_count` 提前到发号之前**（§9.1 第 4 条）：只省一个号，客户端不可见，改它要两版一致才好对拍；不改。
- **EN2：ListDue 给老行保底名额**：要先改基线裁决 X-03；不改，靠最老未决年龄告警。
- **EN3：捐献回包数值取行上的值**：热更换表瞬间才有差别；不改。
- **EN4：待结算列表先过滤再截断**：需要改 SQL 形状（按 kind 过滤），守卫已保证影响很小；不改。
- **EN5：把调用方库（签名器 / Caller / Loop / Decide）现在就做成共享模块**：交易（4.7 只读面）暂不需要，YAGNI；交易接入资产通道时再抽（Q4）。
- **EN6：scene 应答在调用里等落盘**：会让 scene 侧在途时间拉长、背离基线「不在调用里等落盘、调用方重查」的契约；不改。

### 10.3 待拍板 / 开放问题

1. **Q1（E8）离线读账本**：Java 先接线（推荐），还是与基线一致不启用、只交付读端？
2. **Q2 scene 资产 RPC 端口**：固定配置 `xm.scene.asset-rpc-port`（推荐，缺省 21100，同机多实例覆盖，同 link-port 惯例），还是 `-1` 自动选、从导出 URL 回填目录？
3. **Q3 持久化账本分类器放哪里**：(a) 把 `AssetOpLedger` 的只读部分（restore / validate / classify / isPartial / rejectionReason）下沉到 xm-player-store（它拥有状态 proto），流号合法性改成常量集合，
   在 xm-scene 测试里与 `AssetStream` 枚举对拍；xm-guild 依赖 xm-player-store 时排除 `PlayerStoreAutoConfiguration`（推荐）。(b) 在 xm-guild 另写只读镜像、与 scene 共用用例表。(c) 新建共享模块。
4. **Q4 调用方库**：先放 xm-guild、交易接入时再抽（推荐），还是现在做共享模块？
5. **Q5 同步投递线程模型**：异步链（推荐，§7.4），还是保持阻塞、把 guild-worker 扩到能承受「并发捐献 × 2.5 s」？
6. **Q6（E6）op_id 发号源**：复用 `NodeTypes.GUILD` 雪花（推荐），还是为 op_id 另起一个 `NodeTypes`？（guild-spec §6.6 留到 4.5 定。）
7. **Q7（E11）内部查询**：4.5 交付类型化接口 + 仓储（推荐，接口小、不变量可测），还是只交付仓储与测试、接口随 7.2？
8. **Q8（E12）**是否采纳、是否同步建议给 mmorpg？
9. **Q9 scene 在途上限缺省值**：256 是否合适（需 ≥ 副本数 × Workers + 同步投递并发）；超限回「过载」还是排队等待？推荐直接失败（调用方会重投）。
10. **Q10 Workers 缺省 8 的理由**在 Java 不再成立（scene 没有 poller 限制，限流改由 scene 在途上限承担）：保持 8（推荐，与基线对拍），还是放宽？
11. **Q11 assetopfix 的形态**：xm-guild 内第二个主类（推荐），还是放到 xm-data 运维面做成带令牌的 HTTP 接口（偏离基线裁决 D4）？

---

## 11 测试计划

### 11.1 纯函数单测

- **`EconomyTablesTest`**：整表校验逐条造坏样例（`economy_config_test.go:77`）；真实配表能通过（`:71`）；`MaxBuyCount`（`:188`）与默认商店行单次上限（202 / 204 = 1，其余 20，`:216`）；
  时序交叉校验（`:149`：lease + interval ≥ deadline 拒、base > maxBackoff 拒）。
- **`GameDayTest`**：05:00 切点前后、ISO 周年（2027-01-01 → 202653）、`Next*Reset` 严格晚于 t（恰在切点返回下一个）、`PeriodKey` 非法值。
- **`AssetOpDecisionsTest`**：Decide 全表、FinalStatus（含 LastReason / Reason 27007、Abort reason 0 → ABORTED）、NextAttemptMs（指数、封顶、移位 16、rnd 越界夹回）、ApplyRpcOf、
  `counterRefund` 是唯一退款规则（`economy_repo_test.go:279`）、`StatusToRecord`（`:84`）、carryPartialReason（照 `decide_test.go`、`reconcile_test.go`）。
- **视图与映射**：`orderViewOf`、`resultOfOrder`、`donationRejectTip`、`donationViewOf`（payload 解不开只缺两字段）、`shopOrderViewOf`、`recentResults`（窗口、上限 5、keep）、`exceptPlayer`、
  令牌非 0、`clampSyncBudget` / `syncBudgetFor`（`economy_logic_test.go:949-1197`）；`economyTip` / `GuildTips.forReject` 整表（`:844`）。
- **签名**：规范串 golden（与 `auth_test.go` 的 `TestCanonicalGolden`、Java 现有 `AssetOpAuthTest` 同输入，搬到 xm-api 后两侧各钉一次）；`;u=;p=` 篡改失败；每次发包重签。
- **内部查询边界**：保留期判定与下溢（`guild_internal_server_test.go:170-204`）；入参校验顺序与文案（`:84`）。

### 11.2 服务与调用方单测（假 Store / 假定位 / 假提供方 / 假时钟）

- **`GuildEconomyServiceTest`**，覆盖 §3 每个 RPC 的完整顺序：通道关闭先于发号（`economy_logic_test.go:753`）；未知 donate_id 先于发号（`:820`）；MaxBuyCount 超限先于发号（`:782`）；
  前置的五种坏路径（`:337-456`）；PENDING 不进 error_message；兑换 REJECTED 回 14027 且余额为退回后直读值；升级业务拒绝带 GuildInfo、not_member 不带；staleView 不推送；
  同步终结不推送、后台终结推 9 / 13 只推本人、未知 kind 不推（`:1228-1276`）。
- **`AssetOpCallerTest`**（照 `caller_test.go`）：重查到 durable；预算到期停重查返回最后一次；NOT_HERE 换节点重调、同节点不重调；重定位 no-holder 时返回 scene 的 NOT_HERE；结局翻转；
  没人持有时本地 NOT_HERE；定位故障透传；传输错误透传；不改调用方请求；每次重签。
- **`AssetOpLoopTest`**（照 `reconcile_test.go`、`reconcile_budget_test.go`、`reconcile_manual_test.go`）：过截止改发 Abort；settle 在父预算耗尽 / 取消后仍可用；租约丢失只计数；
  离线读只在 attempts ≥ 3 且 Local 时发生、读到未见不终结、读到 APPLIED 直接终结；27007 粘性；流号非法按 Alert；Tick 并发不超过 Workers；毒行跳过；E12 的「传输失败不覆盖 last_outcome」。
- **`SceneAssetLocatorTest`**（照 `locator_test.go` 改成 Java 状态）：`o` / `l` / `x` / 缺失 / 损坏 / 键不符 / Redis 错 / 目录缺节点 / `rpc_port = 0` / 同号新实例。
- **scene 提供方**：未就绪回失败 future；超过在途上限回过载并计数；逻辑线程关闭后失败；回调不在逻辑线程上；`register = false`；缺 `XM_DUBBO_SECRET` 时暴露失败。

### 11.3 真 MySQL（`-Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306`，缺省跳过）

- **建表**：pbmysql 生成的 Q / O / C DDL 与 Go 逐字相同（`uk_guild_asset_op`、idx_0/1/2、`idx_guild_daily_counter_0`）；表形状（`asset_tables_shape_test.go:21`）；28 列清单。
- **语句形状**：锁定语句 EXPLAIN 为 PRIMARY 点查（`economy_lock_plan_mysql_test.go:97`）；候选读无锁定子句（`economy_repo_test.go:232`）；O22 无锁、不开事务、EXPLAIN key ∈ {idx_0, idx_2}。
- **业务**（移植 `economy_repo_test.go:754-2596`）：T-D 写 PENDING 并占次数、达上限回滚、各种拒绝、未决守卫、同玩家并发守住每日上限、相邻新成员并发都成功、首次建 seq 行在成员行上串行、
  事务内建行与 `EnsureSeqRow` 等价；T-S 扣帮贡与占限购、各种拒绝；升级扣当前等级花费并改上限、同 expected_level 并发只升一次；ListDue 新行优先；Claim 返回整行并持租约、毒行推迟、
  租约丢失；Finalize 全部分支（含三种 orphan、PARTIAL 只 CAS、活动只 CAS）；人工终结写审计列并同写 next_attempt；提前截止（拉到 now、分块、跳过不匹配）；清理保留 PENDING 与 PARTIAL、
  计数截止不碰上一周期；最近结果按 (epoch, seq) 倒序。
- **锁序并发**（判据：死锁钩子零记录、不变量成立）：7 个 `TestEconomyLockOrder_*`（预留 ‖ 终结拒绝；退帮 ‖ 清理 ‖ 终结；同流预留 ‖ 清理；成员锁挡住预留；退帮 ‖ 预留候选完整；
  后台 CAS 排在悲观写者之后；兑换 orphan 退款 ‖ 别处预留）。
- **内部查询**：G1–G6、G5b（只返回 APPLIED / PARTIAL、since 严格、按 op_id 翻页、zone 过滤保留已解散帮、看得到人工终结与 Store 终结的行，`asset_op_divergence_repo_test.go:167-371`）。
- **Java 增项**：`lease_token ≥ 2^63` 与 `op_id` 大值全流程；`useAffectedRows` 缺失时 C1 自检报错（防回归）；E12 的 Reschedule 变体；离线读账本（造 `player_state` 行：absent / 坏 blob / 无 asset_ledger / 已应用 / 已拒绝 / 部分发放 / 旧纪元）。

### 11.4 真 Redis（`-Dxm.it.redis=redis://127.0.0.1:6379`）

- `findHolderAsync` 的各状态与乱序写；`NodeDirectory` 单条异步读与 TTL 过期；`rpc_host / rpc_port` 发布与摘除。

### 11.5 跨进程端到端（真 Redis + MySQL + xm-scene + xm-guild）

- 照基线 `assetop/scene_smoke_test.go`：每次一个新纪元；扣 100、发 100；余额不足 27000；跳号 5000 → UNKNOWN；验签错 27008；durable 的最终确认；同 seq 重放只读答复。
- 强杀 xm-guild 于同步投递之后、落库之前 → 重启后循环用同一 seq 重投，资金只加一次（基线 robot 的「可选手动项」）。
- scene 停服期间的 PENDING：摘目录后本地 NOT_HERE → 玩家离线 → attempts ≥ 3 时离线读账本终结（E8）。

### 11.6 robot `guild-economy`（xm-robot 新增 `GuildEconomyScenario`，`RobotOptions.Scenario` 加 `GUILD_ECONOMY`）

- 前置：xm-guild `asset-op.enabled = true`、两侧注入同一个 `XM_ASSET_OP_SECRET_GUILD`、`XM_RUN_MODE = dev`（GM 加币 37 放行，`PARITY.md:57`）。
- 账号用 run-tag 新账号（guild-spec D18），每次都是基线的 **full** 模式（计数按玩家、按游戏日，新账号恒为 0）；期望值写死，照 `guild_economy_smoke.go:63-101`：
  1. A 建帮（Lv1、资金 0、上限 30），B 申请、A 批准；A 读捐献页（3 个选项、无结算中）与商店（11 件）；
  2. GM 给 A 加 300000 银两、100 灵石；A 大捐 → 资金 12000、A 帮贡 120（结算中就轮询捐献页，至多 10 s）；再大捐 → 24000；第三次 → 14024、资金不变；
  3. A 灵石捐 → 资金 44000、可用帮贡 440；A 银两 = 初值 + 300000 − 200000、灵石 = 初值 + 100 − 100；
  4. B 灵石捐 → 14025、视图 REJECTED；B 该项今日用量仍 0，最近结果是货币不足；
  5. B 升级 → 14016 且带 GuildInfo；A 升级 → Lv2、资金 24000、上限 35；A 带旧等级再升 → 受理、仍 Lv2 / 24000；
  6. A 读商店 → 103 已解锁、104 未解锁、帮贡 440、202 单次最多 1 份；A 兑换 101 → 帮贡 410、背包物品 15 数量 +5（191 GetBag）；
  7. A 兑换 104 → 14029；B 兑换 101 → 14031；A 一次兑 2 份 202 → 14030；A 连兑 5 次 301（帮贡 160），第 6 次 → 14030；
  8. 打印 `CheckReport`，引用 PARITY「帮会经济」行；A 解散。
- **Java 增项**：通道关闭时 53 / 233 回 14026、76 / 120 / 228 照常；GuildRule 截止 600 s 的 Abort 路径由服务单测覆盖（robot 不等 10 分钟）。

---

**PARITY 登记**：新增「帮会经济（捐献 / 升级 / 商店 / 资产指令账本与投递）」行与「通用资产通道调用方与跨进程传输」更新（`PARITY.md:79` 的 Java 待做中「跨进程传输与调用方」「已落盘账本只读查询」划掉），
有意差异按 §10.1 的 E1–E14 写；撤销 4.4 行里的 D13 临时差异；交付说明写明 mmorpg 侧状态（基线已全部实现，E8 / E10 / E12 为 mmorpg 可选跟进）。
