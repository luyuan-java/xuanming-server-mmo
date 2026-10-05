# 聚宝斋只读面（trade，批次 4.7）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。Java 侧以当前工作区为准（HEAD `49af941`，外加未提交的
> xm-guild 及其接线改动）。`MessageRoutes.java`、`GateConfiguration.java`、`DubboGroups.java`、`NodeTypes.java`、`start-slice.sh`、`stop-slice.sh`、
> 根 `pom.xml`、`RobotOptions.java`、`RobotMain.java` 与 xm-guild 下的文件都在未提交改动里，行号提交后可能小幅漂移，所以引用时同时写出类名 / 方法名。
>
> **路径怎么读**
> - 不带目录的 Go 文件：
>   - `jubaozhai_logic.go`、`phase.go`、`home_zone.go`、`admin_logic.go` 及同名 `*_test.go`：mmorpg `go/trade/internal/logic/`；
>   - `listing_repo.go`、`tables.go`、`asset_op_repo.go`、`listing_repo_test.go`、`listing_repo_integration_test.go`：`go/trade/internal/data/`；
>   - `constants.go` / `constants_test.go`：`internal/constants/`；`config.go` / `config_test.go`：`internal/config/`；
>   - `servicecontext.go`、`assetchannel.go`：`internal/svc/`；`session.go` / `session_test.go`：`internal/session/`；
>   - `jubaozhai_server.go`、`admin_server.go`：`internal/server/`；`pipeline.go`：`internal/reconcile/`；`lifecycle.go`：`internal/lifecycle/`；
>   - `trade.go`：`go/trade/`；`trade.yaml`：`go/trade/etc/`。
> - `seq.go`、`decide.go`：`go/shared/assetop/`；`metrics.go`：`go/shared/serverbase/`；`forwardlogic.go`：`go/client_rpc_router/internal/logic/`；
>   `trade_step.go`：`tools/merge_zone/`；`trade_smoke_scenario.go`、`etc/trade_smoke.yaml`、`main.go`：mmorpg `robot/`（`robot/config/config.go` 在稀疏克隆里没有检出，
>   按 `git show HEAD:robot/config/config.go` 的行号）；`messagelimiter.json`：mmorpg `generated/tables/`。
> - `jubaozhai.proto`、`trade_admin.proto`、`trade_table.proto`：mmorpg `proto/trade/`，行号用 mmorpg 的。Java 同步副本在 `xm-proto/src/main/proto/proto/trade/`，
>   只多第 2–4 行 java option，所以 **Java 行号 = mmorpg 行号 + 3**。
> - 设计稿 `docs/design/jubaozhai-market.md`（简称「设计稿」）只作背景，与代码冲突时以代码为准。
> - 客户端（`D:\work\mmorpg-client`）：`JubaozhaiClient.cs`、`JubaozhaiModels.cs` 在 `Assets/Scripts/Game/Jubaozhai/`；`GameClient.cs` 在 `Assets/Scripts/Game/`；
>   `JubaozhaiWindow.cs` 在 `Assets/Scripts/UI/Ugui/Jubaozhai/`；`MessageIds.cs` 在 `Assets/Scripts/Net/`。
> - 以 `xm-`、`docs/`、`config-data/`、`contract/`、`tools/` 开头的路径，以及 `PARITY.md`、`AGENTS.md`、`pom.xml`：在 `D:\work\xuanming-server-mmo` 下。
>   `message_id.txt` 指 `xm-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**）；`tip_text.json` 指 `config-data/tables/tip_text.json`；
>   `trade_error_tip.proto` / `common_error_tip.proto` 指 `xm-table/src/main/proto/tip/` 下的同名文件。
>
> **本稿的来历**：由两份分区稿合并而成——一份讲数据层与逻辑层（表、SQL、阶段、可见性、四个 RPC 的判定顺序、播种），一份讲客户端契约、robot、写侧边界与
> Java 落地映射。两份互相矛盾、或与代码不符的地方，都回到代码重新核对过；对分区稿的更正列在 §7.4，对 inventory / 设计稿的更正列在 §7.5。本稿只读代码，
> 没有改任何源文件。
>
> **与 pbmysql 的关系**：阶段 4 起「形状由 proto 消息决定的 MySQL 表」一律接 pbmysql（`docs/porting/roadmap.md:50-53`）。聚宝斋的两张表正是这类表：
> Java 版用自有的 `trade_tables.proto` 经 xm-pbmysql 建表，DDL 与 Go proto2mysql 逐字节相同（§1.5），业务 SQL 全部手写（§1.6）。

---

## 0 概览与协议

### 0.1 链路、范围与依赖

**范围**：批次 4.7「聚宝斋只读面」= 浏览 196、详情 197、收藏 198、货架 200（`docs/porting/roadmap.md:64`），外加它们需要的全部东西：两张表与索引、
阶段 / 可见性规则、市场范围与归属区、分页 / 搜索 / 排序、Market 配置、tip（20000 段）、指标，以及**端到端造数据的 dev 播种**（§4.8；基线 robot 第 1 步依赖它）。
写侧（上架托管、下单、支付、交付、回退）不在 4.7，见 §4。

**基线链路**
- **请求路径**：客户端 → C++ gate（路由服模式）→ `client_rpc_router` → trade 进程（go-zero zrpc，`Name: trade.rpc`，`trade.yaml:4-5`）。trade 是全局服务、全服一份、
  多副本、进程无状态（`trade.go:1-10`；`trade.yaml:2`）。
- **路由服**：缺会话元数据直接 Unauthenticated（`forwardlogic.go:95-102`）；上游任何 gRPC 错误或超时、没有可用实例、拨号失败，都翻成带请求号的**信封**
  `MessageContent{id, message_id, error_message{1003}}`，不带 parameters（`forwardlogic.go:142-155`、`:158-164`、`:170-183`；`rejected` 见 `:226-232`）；
  成功时把应答字节原样装进 `serialized_message`（`:186-191`）。
- **服务端超时**：zrpc `Timeout: 4000`（`trade.yaml:11`），必须在 [1000, 4000] ms（`config.go:25-39`、`:269-272`）；整请求业务预算 = `Timeout − 500 = 3500 ms`
  （`config.go:30-36`、`:219-221`），由逻辑层每个方法入口套上（`jubaozhai_logic.go:79-91`）。
- **两个服务挂在同一个 gRPC server 上**：客户端协议 `ClientPlayerJubaozhai` 与内部 `TradeAdmin`（`trade.go:194-205`）；dev / test 档另注册 reflection（`:199-201`）。

**基线依赖**

| 依赖 | 用途 | 出处 |
|---|---|---|
| MySQL 独占库 `mmorpg_trade` | 两张业务表（另有 P2 的两张，默认不用） | `tables.go:14-31`；`config.go:283-293`；`trade.yaml:46-56` |
| data_service（gRPC） | `BatchGetPlayerHomeZone`（归属区）、`AllocateIdSegment`（listing_id 号段，biz_tag `trade_listing`） | `config.go:84-90`；`servicecontext.go:30-32`、`:270-305`；`home_zone.go:22-84` |
| etcd | 节点注册、热关停规则 | `trade.go:186-190`、`:236-261` |
| SharedRedis | **只在资产通道打开时才拨**（P2，默认关闭）；P1 浏览 / 详情 / 收藏不读写任何 Redis | `config.go:57-59`、`:95-109`；`servicecontext.go:99-133`；`trade_step.go:27-28` |

**Java 侧的对应**
- **入口**：gate 经 Dubbo 调 `ClientMessageService.handle(ClientCall) → CompletableFuture<ClientReply>`，group 为 `trade`（`docs/design/architecture.md:76-104`；
  `xm-api/src/main/java/com/game/api/ClientMessageService.java:34-46`）。
- **进程**：新模块 **xm-trade**（Spring Boot + Dubbo Triple），结构照 xm-friend / xm-guild（`architecture.md:351-391`；「每个服务一个进程」，`roadmap.md:48`）。
- **存储**：MySQL `xm_java` 库（`AGENTS.md` §3）两张表经 xm-pbmysql 建；4.7 **不新增任何业务 Redis 键**（Redis 只用于发号租约与热关停规则同步）。
- **归属区**：读 `xm_java.player.zone_id`（`xm-common/src/main/java/com/game/common/player/PlayerProfiles.java:18-37`；同 guild D3，`docs/porting/guild-spec.md:1715`）。
- **在线目录与推送**：都不需要。基线 trade 不推送（`docs/porting/inventory/social.md:37`），不读会话键；xm-trade 不读 `xm:presence`、不用 `PlayerPushes`（`architecture.md:129-149`）。

### 0.2 消息号、准入、限频、客户端调用点

服务全名 `trade.ClientPlayerJubaozhai`，标了 `OptionIsClientProtocolService`、没标 `OptionIsPlayerService`（`jubaozhai.proto:7`、`:154-161`），
所以 Java gate 会把它的 4 个号路由到同一个后端（`xm-gate/src/main/java/com/game/gate/session/MessageRoutes.java:43-54`、`:62-67`）。消息号只能取自
`MessageIdRegistry`（`message_id.txt`），代码里不写数字。

| 号（`message_id.txt` 行） | 方法（`jubaozhai.proto` 行） | 客户端可发 | 限频（`messagelimiter.json`） | 客户端调用点（`JubaozhaiClient.cs`） | 基线 robot 步骤 |
|---|---|---|---|---|---|
| 196（:197） | `BrowseListings`（:157；请求 :94-105，应答 :107-116） | ✓ | 10/s（:183-188） | `:122-134` | 3、4、5、6、8 |
| 197（:198） | `GetListingDetail`（:158；:118-126） | ✓ | 10/s（:189-194） | `:171-187` | 7 |
| 198（:199） | `SetFavorite`（:159；:128-137） | ✓ | 5/s（:195-200） | `:149-169` | 8 |
| 200（:201） | `GetMyShelf`（:160；:139-152） | ✓ | 10/s（:201-206） | `:107-120` | 9 |
| 199（:200） | `TradeAdmin.SeedListing`（`trade_admin.proto:43-45`；请求 :23-35，应答 :37-41） | **✗**：刻意不标客户端服务、单独成文件（`trade_admin.proto:13-18`） | 表里没有，也走不到限频（gate 先按「不认识的号」丢弃） | 不调用 | 1（gRPC 直连）、2（经 gate 必须无回包） |

- **客户端号常量**：`MessageIds.cs:48-51`。
- **基线准入白名单**：`session.go:40-47` 的 `ClientMethods` 只含这 4 个方法；`TradeAdmin` 的任何方法都不在里面，单测遍历两个 ServiceDesc 守住（`session_test.go:104`）。
- **Java gate 自动生效的部分**：限频读同一份 `config-data/tables/messagelimiter.pb`（已核对其中 196/197/200 为 10 次/秒、198 为 5 次/秒，与 mmorpg 一致）；
  表里没有的号按缺省每秒 3 条（`xm-gate/.../session/MessageLimit.java:13-14`）；超频回信封 1008 并计非法包（`ClientDispatcher.java:229-236`）。
- **热关停规则键**：基线 `trade.ClientPlayerJubaozhai/<Method>`（`trade.yaml:39-44`）；Java gate 用 `route.rpcPath()` = `"/trade.ClientPlayerJubaozhai/<Method>"`
  （`MessageRoutes.java:49`），规范化去掉开头斜杠后同名（`architecture.md:407-426`）。

### 0.3 消息形状与字段语义

| message（`jubaozhai.proto` 行） | 字段（号：类型 名） |
|---|---|
| `ListingSummary`（:71-87） | 1 u64 `listing_id`；2 `ListingCategory category`；3 u32 `subcategory`（0 = 无子类）；4 `title`；5 u32 `level`；6 u64 `price_fen`（分）；7 `ListingPhase phase`；8 u64 `notice_end_ms`；9 u64 `sale_end_ms`；10 u32 `market_zone`；11 bool `is_favorite`；12 bool `is_mine`；13 `summary`；14 `icon_key`。**没有卖家 id 与账号**（`jubaozhai_logic.go:399-418`；单测 `jubaozhai_logic_test.go:874-884` 钉住字段名里不含 `seller`） |
| `ListingDetail`（:89-92） | 1 `ListingSummary summary`；2 `description` |
| `BrowseListingsRequest`（:94-105） | 1 `tab`；2 `section`；3 `category`（必填）；4 u32 `subcategory`（0 = 全部）；5 `search`（≤ 64 字符）；6 `sort`；7 u32 `page`（0 视为 1）；8 u32 `page_size`（0 取缺省）；9 u32 `zone_filter`（只在 GLOBAL 下生效）；10 bool `favorites_only` |
| `BrowseListingsResponse`（:107-116） | 1 `TipInfoMessage error_message`；2 `repeated ListingSummary listings`；3 u32 `total_count`；4 u32 `page`（钳制后）；5 u32 `page_size`（钳制后）；6 u32 `page_count`（≥ 1）；7 `MarketScope market_scope`；8 u64 `server_now_ms` |
| `GetListingDetailRequest` / `Response`（:118-126） | 请求：1 u64 `listing_id`。应答：1 `error_message`、2 `detail`、3 u64 `server_now_ms` |
| `SetFavoriteRequest` / `Response`（:128-137） | 请求：1 u64 `listing_id`、2 bool `favorite`。应答：1 `error_message`、2 `listing_id`、3 `favorite`（服务端确认后的状态） |
| `GetMyShelfRequest` / `Response`（:139-152） | 请求：1 `page`、2 `page_size`。应答：1 `error_message`、2 `listings`、3 `total_count`、4 `page`、5 `page_size`、6 `page_count`、7 `server_now_ms`（**没有** `market_scope`） |
| `SeedListingRequest` / `Response`（`trade_admin.proto:23-41`，内部） | 请求：1 u64 `seller_player_id`、2 `category`、3 `subcategory`、4 `title`、5 `level`、6 u64 `price_fen`、7 `summary`、8 `description`、9 `icon_key`、10 u64 `notice_duration_ms`（0 = 无公示期）、11 u64 `sale_duration_ms`（必须 > 0）。**刻意没有 `market_zone`**（`:22`）。应答：1 `error_message`、2 `listing_id`、3 `market_zone`（服务端写入的分区，供冒烟断言） |

**应答的形状纪律**（Java 必须逐项照做）
- 成功时**不设** `error_message`（`jubaozhai_logic.go:170-178`、`:212-219`、`:251-257`、`:277-279`）。设成空子消息也会被客户端当成功（`JubaozhaiClient.cs:286` 判 `Id != 0`），
  但不设才与基线字节一致。
- 业务拒绝与故障都**只设** `error_message`，其余字段全是零值（`jubaozhai_logic.go:101-103`、`:186-188`、`:228-230`）。唯一例外是 `SetFavorite`：拒绝时也回填请求里的
  `listing_id`（`:273-276`）。
- tip **永不带 parameters**（`tipOf`，`jubaozhai_logic.go:420-422`）。Java friend 的 `Route.failure` 会塞英文原因串（`xm-friend/.../dispatch/FriendDispatcher.java:74-80`），
  guild 照基线带英文原因（guild-spec §7.3，`docs/porting/guild-spec.md:1408`）——trade 两样都不能照抄。

**客户端实际读了哪些字段**（只有 robot 比它更严格）
- 浏览与货架：`ErrorMessage`、`Listings`、`TotalCount`、`Page`、`PageCount`、`ServerNowMs`（`JubaozhaiClient.cs:114-119`、`:131-133`、`:251-264`）；**不读** `page_size` 与 `market_scope`。
- 详情：只读 `Detail.Description`（`:184`）。收藏：只读 `Favorite`（`:164`），`listing_id` 用请求闭包里的值（`:151`、`:164`）。
- 摘要：读 `listing_id / category / subcategory / title / level / price_fen / phase`（只判 PUBLIC_NOTICE）、`notice_end_ms` 或 `sale_end_ms`、`summary / icon_key / is_favorite / is_mine`
  （`:330-361`）；**不读** `market_zone`。编号按 `ulong` 解析、按十进制串显示（`:288-289`、`:343`）。

### 0.4 枚举与编码

| 枚举（`jubaozhai.proto` 行） | 取值 | 服务端校验 |
|---|---|---|
| `ListingCategory`（:17-30） | 1 角色 / 2 宠物 / 3 武器 / 4 防具 / 5 套装 / 6 法宝 / 7 首饰 / 8 召唤令 / 9 游戏币 | 1..9（`phase.go:157-161` → `constants.go:81-105`）；**0 非法** |
| `ListingTab`（:32-37） | 1 公示 / 2 寄售 | 只认 1、2，0 非法（`phase.go:163-166`） |
| `ListingSection`（:39-44） | 1 一口价寄售 / 2 竞价 | 只认 1、2，0 非法（`phase.go:168-172`）；2 在全部校验通过后回 20003 |
| `ListingSort`（:46-53） | 0 新上架在前 / 1 价格升 / 2 价格降 / 3 等级降 / 4 剩余时间升 | 只认 0..4（`phase.go:174-186`） |
| `ListingPhase`（:55-62） | 1 公示 / 2 寄售 / 3 锁定 / 4 结束 | 只出现在应答里，服务端按存储状态与同一个 now 推导，不落库（`phase.go:17-38`） |
| `MarketScope`（:64-69） | 1 zone / 2 global | 只由配置决定，客户端不能指定（`config.go:204-214`） |

- **类目**：客户端值 = 协议值 − 1（`jubaozhai.proto:17`；发：`JubaozhaiClient.cs:300-301`；收：`:333-336`）。9 在客户端没有页签，收到就跳过（`:333-335`），客户端也永远不发 9。
- **子类上限**（`constants.go:81-105`；0 = 全部 / 无子类，合法条件 `subcategory ≤ 上限`）：角色 4（破军 玄霄 逐风 丹心）、宠物 6（普通 灵兽 变异 神兽 元灵 其他）、
  武器 5（枪 爪 剑 扇 锤）、防具 5（男帽 女帽 男衣 女衣 鞋子）、召唤令 2（神兽召唤令 元灵召唤令）；套装 / 法宝 / 首饰 / 游戏币为 0。
  编码 k = 客户端 `JubaozhaiCatalog.SubcategoriesFor` 的第 k 个标签（`constants.go:81-84`；`JubaozhaiModels.cs:59-68`；`JubaozhaiClient.cs:311-319`）。
- **页签 / 分区 / 排序的客户端映射**：`JubaozhaiClient.cs:298-299`、`:321-328`。「货架」不是分区，客户端在 Shelf 时改发 200（`:107-121`；`jubaozhai.proto:39`）。

### 0.5 tip 码表

码值来自 `trade_error_tip.proto:9-20` 与 `common_error_tip.proto:18`、`:22`；文案来自 `tip_text.json:6`、`:8`、`:88-91`；fault 定性来自 `constants_test.go:24-33`。

| 码 | 基线常量（`constants.go`） | Java 枚举 | 文案 | fault | 服务端何时回 | 客户端文案（`JubaozhaiClient.cs:138-147`） | 市场仍可用（`:282-284`） |
|---|---|---|---|---|---|---|---|
| 1003 | `ErrServiceUnavailable`（:24-26） | `CommonErrorTip.common_error.kServiceUnavailable` | 服务不可用 | **是** | MySQL / 归属区查询 / 发号故障；home_zone 查询没接线；scope 非法；整请求预算到期 | 「聚宝斋服务暂时不可用，请稍后重试。」 | **否** |
| 1005 | `ErrInvalidParameter`（:20-22） | `CommonErrorTip.common_error.kInvalidParameter` | 参数无效 | 否 | 参数非法；逻辑层取不到会话（只有内部直连会走到，§0.8） | 「请求参数有误，请调整筛选条件后重试。」 | 是 |
| 20000 | `ErrListingNotFound`（:28-30） | `TradeErrorTip.trade_error.kTradeListingNotFound` | 商品不存在或已下架 | 否 | 不存在、已结束，或 zone 范围下属于别区 | 「商品已下架或不存在，请刷新列表。」 | 是 |
| 20001 | `ErrHomeZoneUnknown`（:32-33） | `kTradeHomeZoneUnknown` | 角色归属区服未确认，暂时无法使用聚宝斋 | 否 | 调用者（或种子卖家）没有归属区 | 「角色所属区服尚未确认…」 | **否** |
| 20002 | `ErrFavoriteLimitReached`（:35-36） | `kTradeFavoriteLimitReached` | 收藏数量已达上限，请先取消部分收藏 | 否 | 收藏数 ≥ 上限 | 「收藏数量已达上限…」 | 是 |
| 20003 | `ErrFeatureDisabled`（:38-39） | `kTradeFeatureDisabled` | 该功能尚未开放 | 否 | section = AUCTION | 浏览拍卖分区时特判成空页加「拍卖尚未开放」（`:125-130`） | 是 |

- 段：`//trade_error base=20000 width=1000`（设计稿 :24、:266）。码值一律引用生成的枚举，`TestNoHandWrittenTipCodes` 机械守住（`constants_test.go:99`）。
  Java 照 `GuildTips` 的写法（`xm-guild/.../rules/GuildTips.java:1-25`：引用 `com.game.table.*ErrorTip`，不写数字，测试扫描源文件）。
- **fault 集合**：Java 配置表没有 fault 列，trade 写死 `FAULTS = {1003}`（同 guild 的做法，`GuildTips.java:13-14`）。
- 设计稿规划的 P3 码约 20 个（设计稿 :269）**还没进** Tip.xlsx；Java 的 `trade_error_tip.proto:9-20` 也只有这 4 个，Java 不能提前加。

### 0.6 配置与常量

**Market 段**（`MarketConf`，`config.go:189-202`；取值 `trade.yaml:91-100`）

| 键 | 默认 | yaml | Validate |
|---|---|---|---|
| Scope | **无默认值** | zone | 只能是 `zone` / `global`，否则拒启（`config.go:305-307`） |
| DefaultPageSize | 20 | 20 | > 0，且 ≤ MaxPageSize（`:308-325`） |
| MaxPageSize | 20 | 20 | > 0 |
| MaxPage | 100 | 100 | > 0；作用是防深分页：OFFSET ≤ (MaxPage−1)×MaxPageSize = 1980（`:198`） |
| MaxFavoritesPerPlayer | 100 | 100 | > 0（软上限，§2.7） |

- `ScopeEnum` 把非法字符串映射成 UNSPECIFIED（`config.go:204-214`）；逻辑层遇到它回 1003（浏览）或判不可见（详情 / 收藏），都是 fail-closed。
- 单测钉住 yaml 必须是 zone 与 20 / 20 / 100 / 100（`config_test.go:57-63`），以及 Validate 的各种拒绝（`config_test.go:223-229`）。
- **代码常量**（`constants.go:49-79`）：

| 常量 | 值 | 计法 / 用途 |
|---|---|---|
| MaxSearchRunes | 64 | trim 后的字符（rune） |
| MaxTitleRunes / MaxSummaryRunes / MaxDescriptionRunes | 64 / 128 / 512 | 字符；只用于 SeedListing |
| MaxIconKeyLen | 64 | 字节，字符集 `[a-z0-9_]`；只用于 SeedListing |
| MaxLevel / MaxPriceFen | 1000 / 10_000_000_000 | 只用于 SeedListing |
| MaxNoticeDuration / MaxSaleDuration | 30 天 / 90 天 | 只用于 SeedListing |
| HomeZoneLookupTimeout | 1500 ms | 单次归属区查询上限 |
| StoreOpTimeout | 2000 ms | 单次 MySQL 调用上限（`InsertFavorite` 整个带重试的调用算一次，`listing_repo.go:302-305`） |

### 0.7 全局规则（四个方法都适用）

- **整请求预算**：每个方法入口先套 `RequestBudget = Timeout − 500ms`（3500 ms），归属区查询与全部 MySQL 共用这一个截止时间，再各自叠加单次上限
  （`jubaozhai_logic.go:11-14`、`:79-91`；`constants.go:69-78`）。单测钉住「所有 I/O 截止时间相同」与「预算到期仍 in-band 1003、且早于服务端 Timeout」
  （`jubaozhai_logic_test.go:923-948`、`:951-980`）。
- **一次请求只取一次时间**：`nowMillis(l.deps.Now)`（`jubaozhai_logic.go:424-431`，负值按 0）；阶段推导、SQL 时间窗、`server_now_ms` 都用这一个值（`:9`）。
- **业务结果一律 in-band**：拒绝与故障都 `return &Resp{ErrorMessage: {id}}, nil`（`jubaozhai_logic.go:3-6`）；故障只有 1003，并打 ERROR 日志，`player_id` 只进日志不进指标
  （`storeFault`，`:354-358`）。唯一回 gRPC 错误的是 SeedListing 在非 dev/test 下的 PermissionDenied（`:6`；`admin_logic.go:55-60`）。
- **身份只取会话**：`session.ClientPlayerID(ctx)`（`session.go:54-63`）；请求体里刻意没有 `player_id`（`jubaozhai.proto:13`）。
- **P1 不接合服闸、不读 SharedRedis、不移动任何资产**（`jubaozhai_logic.go:16`）。

### 0.8 会话、拦截器与信封层：基线 vs Java

**基线拦截器链**（顺序即执行顺序，`trade.go:328-349`）：grpcstats → killswitch → session → serverbase（in-band 故障定性）。
grpc-go 生成的 handler 先解码请求再调拦截器（生成代码在稀疏克隆里没有检出，按 grpc-go 生成器的固定形状推断），所以请求体解析失败先于会话判定。

**session 拦截器**（`session.go:65-87`、`:89-102`）
- 没有会话 metadata → 当内部调用放行（`:70-74`）；逻辑层取不到会话 → in-band 1005（`jubaozhai_logic.go:105-108` 等）。
- metadata 解不开**或 `player_id = 0`** → gRPC Unauthenticated（`:76-80`、`:98-100`；单测 `session_test.go:87-101`）。
- 带会话调白名单外的方法 → PermissionDenied（`:81-84`）。

| 情形 | 基线客户端所见 | Java 今天 → 4.7 之后 |
|---|---|---|
| 经 gate 发 199 | C++ gate 白名单不收，丢弃、计非法包（`trade_smoke_scenario.go:281-284`）；客户端表现为超时 | gate 走「路由为空」：计 `unknown_message_id` 非法包、不回包（`ClientDispatcher.java:217-221`、`:827-836`）。**与基线同形**，robot 第 2 步照常成立 |
| 196–200 后端未接入 | — | 今天：路由到 `BACKEND_UNSUPPORTED`（`MessageRoutes.java:62-67`），推 23 {1003}、不带请求号（`ClientDispatcher.java:313-319`）。4.7 之后消失 |
| trade 进程不在 / 调用失败 / 超时 | 路由服回**带请求号的信封 1003**（`forwardlogic.go:147-154`、`:170-183`）；gate 自己选不到路由服实例时推 23 {1003}（`trade_smoke_scenario.go:646-648`） | **推 23 {1003}、不带请求号**（`ClientDispatcher.java:389-393`）→ 客户端 15 s 后 `"rpc timeout"` → 隔离到重连。见 T8 |
| 会话坏头，或 `player_id = 0` | Unauthenticated → 路由服信封 1003 | xm-trade 回信封 1003（先例 `FriendDispatcher.java:151-156`） |
| 请求体解析失败（含 proto3 非法 UTF-8） | gRPC 解码错误 → 信封 1003 | 信封 1003（先例 `FriendDispatcher.java:173-178`） |
| 热关停 | killswitch 拦截器（`trade.go:335-336`）→ 信封 1003 | gate 按方法检查，回信封 1003（`ClientDispatcher.java:248-257`） |
| 超频 / 超长 | 信封 1008 / 1010 | 同（`ClientDispatcher.java:222-236`） |

- **客户端怎么对上回包**：先按请求号精确匹配，再按 `message_id` 先进先出匹配 `id == 0` 的回包（`GameClient.cs:1652-1670`）；信封 tip 交成 `onError("server tip=N")`
  （`:1558-1563`）；推 23 的 `message_id` 是 23，**对不上任何在途请求**，请求只能等到 15 s 超时（`:917`、`:1597`）。

### 0.9 客户端行为（Java 必须守住的前提，`JubaozhaiClient.cs`）

1. **同一时刻只有一个请求在途**（`:13-15`、`:194`）：查询变化只记脏、回包后补发一次（`:103`、`:245-249`）；收藏、详情不排队，直接提示「请稍候再试」（`:153`、`:174`）。
   Java gate 的按会话、按域队列（`ClientDispatcher.java:329-350`）本来就让同会话串行，不冲突。
2. **只有 `"rpc timeout"` 才进入需重连隔离**（`:16-18`、`:215-224`）：信封 tip、解析失败、未连接只提示「请求未完成」（`:225-229`）、不隔离。设计意图是「trade 作为可选服务缺席时，
   聚宝斋不该一直不可用直到断线重连」（设计稿 :289）。**所以 trade 缺席时，回包必须是带请求号或 message_id 的信封，而不是推 23**（T8）。
3. **in-band 拒绝**：浏览和货架清成空页、写文案（`FailPage`，`:266`；`ApplyEmptyPage`，`:268-274`）；20001 与 1003 把整个市场标为不可用（`:282-284`）；拍卖 20003 特判（`:125-130`）。
4. **浏览请求怎么拼**（`:293-309`）：页长 = `JubaozhaiState.PageSize`，缺省 **4**（`JubaozhaiModels.cs:144`；窗口用缺省构造，`JubaozhaiWindow.cs:47`）；`page = max(1, 页码)`；
   **从不发 `zone_filter`**（`:297`）；search 原样发送，去空白由服务端做。
5. **时钟**：剩余时间用 `server_now_ms / 1000` 校正本地钟（`:261-262`；`JubaozhaiModels.cs:332`）；公示中按 `notice_end_ms` 显示剩余，其余按 `sale_end_ms`（`:354-356`）。
   客户端把 `PageNumber` 钳到服务端回的 `page_count`（`JubaozhaiModels.cs:331`）。
6. **收藏不做乐观更新**，以回包的 `favorite` 为准（`:163-164`）；「只看收藏」时成功后重新浏览（`:166-167`）。
7. **写侧在客户端是桩**：「购买 · 尚未开放」按钮只弹提示（`JubaozhaiWindow.cs:321-322`；`JubaozhaiModels.cs:55`），没有任何写侧请求。

---

## 1 数据模型

### 1.1 库与建表路径

- **基线**：trade 独占库 `mmorpg_trade`（`tables.go:14-17`），`config.Validate` 断言库名必须是它（`config.go:286-289`）。表的唯一事实源是 `trade_table.proto`，
  由 schemamigrate 按 `Tables()` 建（`tables.go:19-31`），启动期 `ensureSchema`（`trade.go:166-170`、`:364-395`）或 `-migrate`（`trade.go:61-66`、`:397-420`）。
  DSN 带 `sql_mode='STRICT_TRANS_TABLES'` 与 `transaction_isolation='READ-COMMITTED'`（`servicecontext.go:225-243`）；严格模式在连接层兜底「超长文本被静默截断」。
- **Java**：`xm_java` 库，xm-trade 自有 `xm-trade/src/main/proto/xm/trade/trade_tables.proto` 经 xm-pbmysql 建表（§5.5）。4.7 只建 `trade_listing`、`trade_favorite`。

### 1.2 `trade_listing`（`TradeListingRecord`，`trade_table.proto:28-57`）

表选项（`:29-36`）：表名 `trade_listing`，主键 `listing_id`；三条普通索引 `market_zone,status,category,subcategory,price_fen` / `status,category,subcategory,price_fen` /
`seller_player_id,listing_id`（「本区浏览 / 全服浏览 / 卖家货架」，`:31-32`）；TiDB：NONCLUSTERED、`SHARD_ROW_ID_BITS=4`、`PRE_SPLIT_REGIONS=4`。

| # | 列 | proto 类型 | 列类型（pbmysql 规则） | 语义（`trade_table.proto` 行） | SeedListing 写入值（`admin_logic.go:95-117`） |
|---|---|---|---|---|---|
| 1 | listing_id | uint64 | bigint unsigned | 主键，基线号段发（biz_tag `trade_listing`，:38） | `ListingIDs.Next`；0 / 出错 / 没接线按故障（`:80-93`） |
| 2 | seller_player_id | uint64 | bigint unsigned | 卖家 | 请求值 |
| 3 | seller_account | string | MEDIUMTEXT | P3 判「同账号不能自买」用（:40） | `""`（`:100`） |
| 4 | market_zone | uint32 | int unsigned | 逻辑市场分区 = 上架时卖家 home_zone；合服时改写（:41） | 卖家 home_zone |
| 5 | seller_zone_at_listing | uint32 | int unsigned | 上架时 home_zone 原值；合服不改，审计用（:42） | 卖家 home_zone |
| 6 | category | enum ListingCategory | int（有符号） | 类目 | 请求值 |
| 7 | subcategory | uint32 | int unsigned | 0 = 无子类（:44） | 请求值 |
| 8 | title | string | MEDIUMTEXT | 标题，参与 LIKE 搜索 | 请求原值（不 trim） |
| 9 | level | uint32 | int unsigned | 等级 | 请求值 |
| 10 | price_fen | uint64 | bigint unsigned | 价格（分） | 请求值 |
| 11 | status | enum ListingStatus | int | 存储状态 | `LISTED`(2) |
| 12 | summary | string | MEDIUMTEXT | 列表「信息」列 | 请求值 |
| 13 | description | string | MEDIUMTEXT | 详情描述，**列表查询不取** | 请求值 |
| 14 | icon_key | string | MEDIUMTEXT | 客户端图标资源键 | 请求值 |
| 15 | notice_end_ms | uint64 | bigint unsigned | 公示结束；等于上架时刻 = 无公示期（:52） | `now + notice_duration_ms`（`:96`） |
| 16 | sale_end_ms | uint64 | bigint unsigned | 寄售结束 | `notice_end_ms + sale_duration_ms`（`:113`） |
| 17 | created_ms | uint64 | bigint unsigned | 创建时刻 | now |
| 18 | updated_ms | uint64 | bigint unsigned | 更新时刻 | now |
| 19 | version | uint64 | bigint unsigned | P3 起做状态迁移 CAS（:56） | 0（`:116`） |

**隐含不变量**：`created_ms ≤ notice_end_ms < sale_end_ms`。SeedListing 要求 `sale_duration_ms > 0`（`phase.go:207`），所以必然成立；公示列表的 SQL 不检查 `sale_end_ms`
（`listing_repo.go:361-363`）、robot 断言 `sale_end_ms > notice_end_ms`（`trade_smoke_scenario.go:848-849`），都依赖这条。

### 1.3 `trade_favorite`（`TradeFavoriteRecord`，`trade_table.proto:59-70`）

主键 `(player_id, listing_id)`，普通索引 `listing_id`，TiDB 三项同上；三列 `player_id`、`listing_id`、`created_ms` 全是 uint64。没有 zone 列，合服不动它（`trade_step.go:20`）。

### 1.4 存储状态 `ListingStatus`（`trade_table.proto:15-26`）

| 值 | 名字 | 说明 |
|---|---|---|
| 0 | UNSPECIFIED | — |
| 1 | ESCROWING | P3：托管扣出中 |
| 2 | LISTED | **P1 唯一会被写入的状态**；公示 / 寄售由时间推导（:15-16、:20） |
| 3 | LOCKED | P3：有未完成订单 |
| 4 | SOLD | P3 |
| 5 | RETURNING | P3：回退交付中 |
| 6 | RETURNED | P3 |
| 7 | ESCROW_REJECTED | P3 |

P1 没有任何代码把行改成 LISTED 以外的状态，但读路径已经按 LOCKED 写好：寄售列表包含 LOCKED（`listing_repo.go:364-368`），详情对 LOCKED 可见（`phase.go:43-48`）。
Java 原样保留这两处（P3 直接复用）。

### 1.5 DDL（按 xm-pbmysql 规则推导，**不是从真库里抄的**）

依据：类型映射 `xm-pbmysql/.../TableSchema.java:63-76`（uint32 → `int unsigned`，:65；enum → `int NOT NULL DEFAULT 0`，:72）、建表拼接 `:322-358`、索引名 `idx_<表>_<i>`
（`:307-309`）、列注释 `pb:N`（`:316-318`）、表排序规则 `:57`；格式与 `CreateTableSqlTest.java:310-341`、`xm-guild/.../store/GuildTablesTest.java:25-60` 一致；
pbmysql 的 DDL 与 Go proto2mysql 逐字节相同（`TableSchema.java:322`）。合服工具注释直接点名 `idx_trade_listing_0(market_zone, status, ...)`（`trade_step.go:16`），可印证索引名。

```sql
CREATE TABLE IF NOT EXISTS `trade_listing` (
  `listing_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
  `seller_player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',
  `seller_account` MEDIUMTEXT COMMENT 'pb:3',
  `market_zone` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',
  `seller_zone_at_listing` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:5',
  `category` int NOT NULL DEFAULT 0 COMMENT 'pb:6',
  `subcategory` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:7',
  `title` MEDIUMTEXT COMMENT 'pb:8',
  `level` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:9',
  `price_fen` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:10',
  `status` int NOT NULL DEFAULT 0 COMMENT 'pb:11',
  `summary` MEDIUMTEXT COMMENT 'pb:12',
  `description` MEDIUMTEXT COMMENT 'pb:13',
  `icon_key` MEDIUMTEXT COMMENT 'pb:14',
  `notice_end_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:15',
  `sale_end_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:16',
  `created_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:17',
  `updated_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:18',
  `version` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:19',
  PRIMARY KEY (`listing_id`) /*T![clustered_index] NONCLUSTERED */,
  INDEX `idx_trade_listing_0` (`market_zone`,`status`,`category`,`subcategory`,`price_fen`),
  INDEX `idx_trade_listing_1` (`status`,`category`,`subcategory`,`price_fen`),
  INDEX `idx_trade_listing_2` (`seller_player_id`,`listing_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='trade_listing';

CREATE TABLE IF NOT EXISTS `trade_favorite` (
  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
  `listing_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',
  `created_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',
  PRIMARY KEY (`player_id`,`listing_id`) /*T![clustered_index] NONCLUSTERED */,
  INDEX `idx_trade_favorite_0` (`listing_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='trade_favorite';
```

- `title` 是 MEDIUMTEXT、跟随表排序规则 `utf8mb4_unicode_ci`，所以 **LIKE 搜索不区分大小写**（其它 `_ci` 等价规则同理）。两版 DDL 相同，行为也相同。
- MEDIUMTEXT 列**可为 NULL**（DDL 没有 NOT NULL）。服务写入的永远是空串；但手工插入的 NULL 在 Go 里扫进 string 会报错（→ 1003），Java 的 `rs.getString` 会返回 null、
  protobuf setter 再抛 NPE。Java 读回时统一 null → `""`，不要依赖异常路径（§5.9）。
- `category` / `status` 在 Java 自有 proto 里声明成 `int32`（或 Java 自有枚举），都生成 `int NOT NULL DEFAULT 0`；**不能**声明成 `uint32`，否则生成 `int unsigned`，DDL 与基线不同
  （`TableSchema.java:64-65`、`:72`）。

### 1.6 SQL 全目录（`listing_repo.go`，Java 逐字照搬）

**通用约定**
- 每次调用自带 `StoreOpTimeout` 上限，与请求预算取先到者（`:44-50`、`:93-95`）。返回的 error 一律视为存储故障，唯一例外是 `ErrListingNotFound`（`:16-17`、`:48-49`）。
  分页参数由调用方钳制，存储层不再二次钳制（`:50`）。
- **列清单**（`:70-78`）：摘要列 18 列，**不含 description**：`listing_id, seller_player_id, seller_account, market_zone, seller_zone_at_listing, category, subcategory,
  title, level, price_fen, status, summary, icon_key, notice_end_ms, sale_end_ms, created_ms, updated_ms, version`；详情列 = 摘要列 + `description`（19 列）。列名一律加反引号
  （`level / status / description / version` 是关键字，`:71-72`）。单测钉住 18 / 19 列且覆盖 proto 全部字段（`listing_repo_test.go:140-158`）。
- `scanListing` 按列序扫，枚举列先扫进 int32 再转型（`:330-349`）。

| 编号 | 方法 | SQL（逐字） | 参数 |
|---|---|---|---|
| L1 | CountListings（`:97-110`） | `SELECT COUNT(*) FROM trade_listing` + where（§1.7） | 过滤参数 |
| L2 | QueryListings（`:112-127`） | `SELECT <摘要列> FROM trade_listing` + where + `" ORDER BY "` + 排序片段 + `" LIMIT ? OFFSET ?"` | 过滤参数…, limit, offset |
| L3 | CountSellerListings（`:129-139`） | ``SELECT COUNT(*) FROM trade_listing WHERE `seller_player_id` = ?`` | seller |
| L4 | QuerySellerListings（`:141-146`） | ``SELECT <摘要列> FROM trade_listing WHERE `seller_player_id` = ? ORDER BY `listing_id` DESC LIMIT ? OFFSET ?`` | seller, limit, offset |
| L5 | GetListing（`:171-185`） | ``SELECT <详情列> FROM trade_listing WHERE `listing_id` = ?`` | id；`ErrNoRows` → `ErrListingNotFound` |
| L6 | InsertListing（`:187-204`） | `INSERT INTO trade_listing (<详情列>) VALUES (?×19)` | 按列序；**主键冲突按故障**，刻意不用 IGNORE / ODKU（`:187-188`） |
| F1 | FavoriteIDs（`:206-238`） | ``SELECT `listing_id` FROM trade_favorite WHERE `player_id` = ? AND `listing_id` IN (?,…)`` | player, ids…；ids 为空不查库（`:209-211`） |
| F2 | FavoriteExists（`:240-254`） | ``SELECT 1 FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?`` | player, listing |
| F3 | CountFavorites（`:256-266`） | ``SELECT COUNT(*) FROM trade_favorite WHERE `player_id` = ?`` | player |
| F4 | InsertFavorite（`:285-286`、`:302-312`） | ``INSERT INTO trade_favorite (`player_id`, `listing_id`, `created_ms`) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE `created_ms` = `created_ms` `` | player, listing, created_ms；见 §1.9 |
| F5 | DeleteFavorite（`:314-323`） | ``DELETE FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?`` | player, listing；自动提交 |

### 1.7 过滤条件与排序映射

**`buildListingFilter`**（`listing_repo.go:351-401`）：条件拼接顺序固定，单测逐字钉住 SQL 与参数（`listing_repo_test.go:21-77`）：
1. 页签（`:360-371`）
   - 公示：`` `status` = ? `` AND `` `notice_end_ms` > ? ``，参数 LISTED(2)、now；
   - 寄售：`` `status` IN (?, ?) `` AND `` `notice_end_ms` <= ? `` AND `` `sale_end_ms` > ? ``，参数 LISTED(2)、LOCKED(3)、now、now；
   - 其他页签值返回错误（防御性，`listing_repo_test.go:79-86`）。
2. category 为 0 返回错误（`:372-374`）。
3. `MarketZone ≠ 0` → `` `market_zone` = ? ``（`:376-379`）。
4. `` `category` = ? ``（`:380-381`）。
5. `Subcategory ≠ 0` → `` `subcategory` = ? ``（`:382-385`）。
6. 搜索（`:386-394`）：有编号 → ``(`listing_id` = ? OR `title` LIKE ? ESCAPE '!')``（参数 id、pattern）；没编号 → ``…`title` LIKE ? ESCAPE '!'``（参数 pattern）。
7. 只看收藏（`:395-399`）：``EXISTS (SELECT 1 FROM trade_favorite f WHERE f.`player_id` = ? AND f.`listing_id` = trade_listing.`listing_id`)``，参数 player。
8. `" WHERE " + strings.Join(conds, " AND ")`（`:400`）。

**`listingOrderBy`**（`:403-424`）：固定映射，绝不拼用户输入；未知排序返回错误（`listing_repo_test.go:88-125`）。每种都以 `listing_id` 作最终决胜列，翻页不重不漏（`jubaozhai.proto:46`）。

| sort | ORDER BY |
|---|---|
| 0 DEFAULT | `` `listing_id` DESC `` |
| 1 PRICE_ASC | `` `price_fen` ASC, `listing_id` ASC `` |
| 2 PRICE_DESC | `` `price_fen` DESC, `listing_id` DESC `` |
| 3 LEVEL_DESC | `` `level` DESC, `listing_id` DESC `` |
| 4 REMAINING_ASC | 公示：`` `notice_end_ms` ASC, `listing_id` ASC ``；寄售：`` `sale_end_ms` ASC, `listing_id` ASC `` |

### 1.8 每条查询走哪条索引（按 SQL 形状推断，**没有在真库上跑 EXPLAIN 验证**）

| 查询 | 预期索引 | 命中前缀 | 其余条件 |
|---|---|---|---|
| zone 范围浏览；global 且 `zone_filter ≠ 0` | `idx_trade_listing_0` | `market_zone =`、`status =` 或 `IN (2,3)`、`category =`、可选 `subcategory =` | 时间窗、LIKE、收藏 EXISTS 回表后过滤；ORDER BY 一般要 filesort（`listing_id DESC` 不在索引里；寄售页 status 是两段 IN，按价格排也排不了） |
| global 且 `zone_filter = 0` | `idx_trade_listing_1` | 同上，少 market_zone | 同上 |
| 货架 COUNT 与分页 | `idx_trade_listing_2` | `seller_player_id =`，按 `listing_id DESC` 走索引序 | 无 |
| 收藏 F1–F5、「只看收藏」的 EXISTS | `trade_favorite` 主键 | `(player_id, listing_id)` 或前缀 `player_id` | 无 |
| `idx_trade_favorite_0 (listing_id)` | P1 没有读者，留给按商品反查收藏者 | — | — |

深分页靠 `MaxPage = 100` 封顶：OFFSET ≤ 1980（`config.go:198`；`trade.yaml:94`）。

### 1.9 收藏写入的幂等与重试

- **ODKU 空更新**：重复收藏幂等、不刷新原收藏时间（`listing_repo.go:268-286`）。不用 `INSERT IGNORE` 的理由（2026-09-21 死锁审计 #9）：收藏 → 取消 → 再收藏时，主键可能是尚未 purge
  的删除标记记录；有第三方持着它的 X 时，两个 INSERT IGNORE 的重复键检查同时拿到 S、再各自申请 X → 1213；ODKU 的重复键检查直接取 X，排队者只能逐个拿到
  （`:271-284`）。单测用纯文本钉住「必须是 ODKU、不得含 IGNORE」（`listing_repo_test.go:126-138`）；真库红绿对照与压力测试在 `listing_repo_integration_test.go:449`（INSERT IGNORE 红对照）、
  `:482`、`:529`，默认跳过（`TRADE_TEST_MYSQL_DSN`，`:60-68`）。
- **重试**：`assetop.WithTxRetry(ctx, db, 2, IsRetryableTxError, …)`，单语句的 RC 事务，只为复用有界重试与可取消退避（`listing_repo.go:288-312`）。可重试错误 1213 / 1205 / 9007
  （`asset_op_repo.go:873-895`）；2 次尝试之间退避一次，base 10 ms、±20% 抖动、封顶 200 ms、可被取消（`seq.go:253-259`、`:316-324`、`:356-361`、`:387-425`；`decide.go:144-154`）。
  ODKU 之后剩下的 1213 只可能来自 purge / 首插者回滚这类 InnoDB 固有情形（`listing_repo.go:290-301`）。
- **取消收藏**：DELETE，本身幂等，自动提交（`:314-323`）；会话级 RC（`servicecontext.go:229-235`）保证它不拿间隙锁。

### 1.10 4.7 不建的表

P2 资产通道的 `trade_player_op_seq` 与 `trade_asset_op`（`trade_table.proto:72-174`；登记在 `tables.go:25-29`）只读面不需要，也没有生产者（§4.3）。
Java 4.7 不声明它们；`trade_tables.proto` 留追加区（§4.7）。

### 1.11 Redis

基线 P1 不读写任何 Redis（`jubaozhai_logic.go:16`；`trade_step.go:27-28`）。Java 4.7 不新增业务键；只有 `NodeIdLease` 的租约键（`NodeTypes.TRADE`，§5.7）与热关停哈希的读取
（`architecture.md:407-426`）。

---

## 2 可见性与阶段规则

### 2.1 一次取时

`nowMs` 每个请求只取一次（`jubaozhai_logic.go:424-431`）：浏览与货架在查归属区 / 查库**之前**取（`:121`、`:196`）；详情在 `GetListing` 之前取（`:240`）；收藏只在「加收藏」分支取（`:296`）。

### 2.2 展示阶段 `Phase(rec, now)`（`phase.go:17-38`）

- `LISTED` 且 `now < notice_end_ms` → PUBLIC_NOTICE；
- `LISTED` 且 `now < sale_end_ms` → ON_SALE；
- `LISTED` 其余 → ENDED；
- `LOCKED` → LOCKED，**不看时间**（`phase_test.go:30`）；
- 其余状态（UNSPECIFIED / ESCROWING / SOLD / RETURNING / RETURNED / ESCROW_REJECTED）→ ENDED（`phase_test.go:31-33`）。

边界：`now == notice_end_ms` 算寄售，`now == sale_end_ms` 算结束（`phase_test.go:26`、`:28`）；`notice_end_ms = 0` 直接寄售（`:27`）。

### 2.3 浏览的时间窗（SQL，`listing_repo.go:360-371`）

- 公示列表：`status = 2 AND notice_end_ms > now`；
- 寄售列表：`status IN (2,3) AND notice_end_ms <= now AND sale_end_ms > now`。

SQL 与 `Phase` 在边界上一致。**浏览没有卖家豁免**：卖家自己在列表里也只看到时间窗内的、且受分区条件约束的商品；看自己全部商品走货架。

### 2.4 详情 / 收藏的可见性 `VisibleToBuyer` 与卖家豁免

- **非卖家**（`phase.go:40-60`）：`status ∈ {LISTED, LOCKED}` **且** `now < sale_end_ms` **且** 范围条件：GLOBAL 恒成立；ZONE 要求 `callerHomeZone ≠ 0` 且
  `market_zone == callerHomeZone`；scope 为 UNSPECIFIED 时恒不可见（fail-closed，`phase_test.go:57`、`:63`）。公示中的商品对买家可见（只是不可买，`phase_test.go:55`）。
- **卖家豁免**：`seller_player_id == caller` 直接可见，不看状态、时间、分区，也**不查归属区**（`jubaozhai_logic.go:334-336`）。
- 不可见的商品一律表现为 20000「不存在」，不泄露别区有哪些商品（`jubaozhai_logic.go:347-350`；`constants.go:28-29`）。
- 判定顺序（`loadVisibleListing`，`jubaozhai_logic.go:324-352`）：先 `GetListing`（不存在 → 20000；失败 → 1003）→ 卖家豁免 → ZONE 下查归属区（未映射 → 20001；失败 → 1003）
  → `VisibleToBuyer`。所以「查一件不存在的商品」不会去查归属区（`jubaozhai_logic_test.go:637-638`：`homeLookups = 0`），没有归属区的玩家查不存在的商品得到 20000 而不是 20001。

### 2.5 可见性总表

浏览另受分区条件约束（ZONE 按调用者归属区；GLOBAL 按 `zone_filter`）。

| 状态 / 时间 | 公示列表 | 寄售列表 | 详情 / 加收藏（非卖家） | 货架（卖家本人） | phase |
|---|---|---|---|---|---|
| LISTED，now < notice_end | ✓ | ✗ | ✓ | ✓ | PUBLIC_NOTICE |
| LISTED，notice_end ≤ now < sale_end | ✗ | ✓ | ✓ | ✓ | ON_SALE |
| LISTED，now ≥ sale_end | ✗ | ✗ | ✗（20000） | ✓ | ENDED |
| LOCKED，notice_end ≤ now < sale_end | ✗ | ✓ | ✓ | ✓ | LOCKED |
| LOCKED，now < notice_end（P1 到不了） | ✗ | ✗ | ✓ | ✓ | LOCKED |
| LOCKED，now ≥ sale_end | ✗ | ✗ | ✗ | ✓ | LOCKED |
| 其余状态 | ✗ | ✗ | ✗ | ✓ | ENDED |

### 2.6 市场范围与归属区

- **ZONE**：浏览的分区 = 调用者归属区，**`zone_filter` 一律忽略**（`jubaozhai_logic.go:131-137`；`jubaozhai_logic_test.go:407-421`）；详情 / 收藏要求 `market_zone == 调用者归属区`。
- **GLOBAL**：浏览的分区 = `zone_filter`，0 = 全部区，不查归属区（`:138-139`；`jubaozhai_logic_test.go:423-437`）；详情 / 收藏不看分区、不查归属区。
- **哪些地方查归属区**：浏览（只在 ZONE，`:130-137`）；详情与加收藏（只在 ZONE、只对非卖家、在 `GetListing` 命中之后，`:338-346`）；SeedListing（总是查卖家，`admin_logic.go:71-78`）。
  **货架与取消收藏从不查**。
- **基线取法**（`DataServiceHomeZone`，`home_zone.go:13-84`）：一个 id 调 `BatchGetPlayerHomeZone`，单次 1500 ms（`:77-79`）；map 缺 key 或值为 0 → `(0, nil)` 未映射（`:55-58`）；
  RPC 报错（**含 NotFound**）→ 故障（`home_zone_test.go:47`）；client 为 nil → 故障（`:46-48`；`home_zone_test.go:82`）；`player_id = 0` 过滤后为空 → 不发 RPC、按未映射（`:66-75`；
  `home_zone_test.go:71`）。每次查询计 `trade_home_zone_lookup_total{result=ok|unmapped|error}`（`:46-61`）。
- **逻辑层翻译**（`resolveHomeZone`，`jubaozhai_logic.go:360-377`）：lookup 接口本身为 nil（没接线）→ ERROR、1003（这条不计数）；查询报错 → ERROR、1003；查到 0 → INFO、20001。
- **market_zone 的来源**：上架时卖家的 home_zone，服务端写入、不接受客户端传值（`trade_admin.proto:22`；设计稿 :75）。合服时由 `tools/merge_zone` 按清单逐条主键点更新（§4.1）。
- **Java**：归属区 = `xm_java.player.zone_id`（`xm-player-store/src/main/resources/db/xm-player-schema.sql:11-32`，`zone_id` 在 :14），由建角的 login 按自己的 `xm.zone-id` 写入；
  缺行或 0 → 20001，读失败 / 预算用完 → **in-band 1003**（注意不是 guild 那种信封：trade 基线这里本来就是 in-band，`jubaozhai_logic.go:360-377`）。见 §5.6、T3。

### 2.7 搜索、分页、收藏的精确语义

**搜索**
- `NormalizeSearch`（`phase.go:147-155`）：先 `strings.TrimSpace`（Unicode White_Space）；再 `ValidText`（`:114-131`）：合法 UTF-8、`unicode.IsControl` 为假（只禁 U+0000–001F、
  U+007F–009F）、trim 后 ≤ 64 个 rune；trim 后为空 = 不搜（`phase_test.go:174-194`）。
- LIKE 转义 `EscapeLike`（`phase.go:103-112`）：单遍替换 `!`→`!!`、`%`→`!%`、`_`→`!_`；反斜杠原样保留；SQL 固定写 `ESCAPE '!'`，不依赖 `NO_BACKSLASH_ESCAPES`
  （`phase_test.go:112-128`）。真库验证「搜 `%_` 只命中标题里真含 `%_` 的商品」（`listing_repo_integration_test.go:181-187`）。
- pattern = `"%" + EscapeLike(search) + "%"`（`jubaozhai_logic.go:145-146`）。
- **按编号**：`strconv.ParseUint(search, 10, 64)` 成功时写 `SearchListingID`（`:147-150`）：只接受 ASCII 数字、**不接受 `+`**、可有前导零（`"007"` → 7）、超出 uint64 不按编号；
  解析出 0 时值也是 0、等于不按编号（`listing_repo.go:387`）。用例：`"  123  "` → 123；`"0"` → 只按标题；`"99999999999999999999"` → 只按标题（`jubaozhai_logic_test.go:525-554`）。
- **搜索与其他条件是 AND**：类目、页签窗口、分区、子类、收藏条件同时生效；按编号精确搜也找不到当前类目 / 页签以外的商品（`listing_repo.go:376-399`）。

**分页**
- `ClampPageSize`（`phase.go:62-74`）：0 → 缺省；> 上限 → 上限；结果仍是 0 → 1（只防除零）。
- `PageWindow`（`phase.go:76-101`）：`page_count = max(1, ceil(total/size))` 再饱和到 2^32−1；`page = min(max(page,1), page_count)`，`MaxPage > 0` 时再 `min(page, MaxPage)`；
  `offset = (page−1) × size`。`total_count` 饱和到 2^32−1（`jubaozhai_logic.go:433-438`）。

| total | page / size | MaxPage | page / page_count / offset | 出处 |
|---|---|---|---|---|
| 45 | 0 / 0 | 100 | 1 / 3 / 0（页长取缺省 20） | `jubaozhai_logic_test.go:472` |
| 45 | 9999 / 50 | 100 | 3 / 3 / 40（页长钳到 20） | `:473` |
| 9 | 2 / 4 | 100 | 2 / 3 / 4（客户端显式页长 4） | `:474` |
| 0 | 5 / 10 | 100 | 1 / 1 / 0 | `:475` |
| 100000 | 500 / 20 | 100 | 100 / **5000** / 1980 | `:476`；`phase_test.go:99` |
| 100000 | 500 / 20 | 0（不封顶） | 500 / 5000 / 9980 | `phase_test.go:100` |

- **`page_count` 不受 `MaxPage` 封顶**：客户端可能看到 5000 页，但最多翻到第 100 页（N1）。
- **COUNT 与 SELECT 是两条各自提交的语句，没有共同快照**：两者之间有并发写入时这一页可能偏短；基线接受（N2）。
- 空结果照样执行 L2（`jubaozhai_logic_test.go:475` 断言 `lastLimit`），空页不查收藏（`jubaozhai_logic.go:379-389`；`jubaozhai_logic_test.go:587-595`）。

**收藏**
- **软上限**：计数与写入之间没有锁，同一玩家的并发收藏最多超出「在途请求数」条（`jubaozhai_logic.go:260-268`）；上限默认 100（`trade.yaml:100`）。
- **计数包括已经看不见的收藏**：F3 不 join 商品表（`listing_repo.go:262`）。可是「只看收藏」浏览只列时间窗内的商品，过期商品的详情回 20000，客户端也没有「列出我的全部收藏」的入口
  ——**收藏全过期的玩家会一直卡在 20002**，除非客户端还记得那些 listing_id 并逐个取消（取消不查商品，§3.4）。P1 没有清理机制。这是基线行为（§7.1 第 2 条，Q8）。
- **卖家可以收藏自己任意状态的商品**（卖家豁免，`jubaozhai_logic.go:334-336`）。
- 已收藏再收藏：直接回 true，**不计数、不判上限**（`:300-306`；`jubaozhai_logic_test.go:728-742`）。

---

## 3 每个 RPC 的处理流程

### 3.0 入口外壳

- gRPC 入口是薄包装，每请求 new 一个 `JubaozhaiLogic`（`jubaozhai_server.go:13-43`）。
- 每个方法：套预算（`withRequestBudget`，`jubaozhai_logic.go:84-91`，返回副本，上一个方法 cancel 掉的 ctx 不漏给下一个）→ 定义 `reject` 闭包（只填 `error_message`；SetFavorite 另填 `listing_id`）→ 会话。

### 3.1 公共件

| 件 | 行为 | 出处 |
|---|---|---|
| `resolveHomeZone` | §2.6 | `jubaozhai_logic.go:360-377` |
| `loadVisibleListing` | §2.4 | `:324-352` |
| `favoriteSet` | 空页返回空集合、不查库；否则 F1 | `:379-389` |
| `toSummary` | 透传 `listing_id, category, subcategory, title, level, price_fen, notice_end_ms, sale_end_ms, market_zone, summary, icon_key`；`phase = Phase(rec, now)`；`is_favorite` 来自收藏集合；`is_mine = (seller_player_id == caller)` | `:391-418` |
| `storeFault` | ERROR 日志（含 player_id）→ 1003 | `:354-358` |

### 3.2 BrowseListings（196）（`jubaozhai_logic.go:93-179`）

1. 套预算（`:99-100`）。
2. 会话 → 取不到回 1005（`:105-108`）。
3. **纯校验，任一失败回 1005**，Go 短路顺序：`NormalizeSearch` → `ValidTab` → `ValidSection` → `ValidCategory(category, subcategory)` → `ValidSort`（`:109-113`）。
   这一步不查归属区、不碰库（`jubaozhai_logic_test.go:351-392`，含 tab 0/3、section 0/3、category 0/10、武器子类 6、套装子类 1、sort 5、search 65 字 / `"a\nb"` / 非法 UTF-8）。
4. `section == AUCTION` → 20003，同样零 I/O（`:114-116`；`jubaozhai_logic_test.go:394-405`）。**参数非法优先于 AUCTION**：竞价分区配非法 tab 回 1005（`jubaozhai_logic_test.go:374-376`）。
5. `scope = ScopeEnum()`、`pageSize = ClampPageSize(...)`、取 `nowMs`（`:118-121`）。
6. 组装查询：tab、category、subcategory、sort、now（`:123-129`）。
7. 定分区（`:130-144`）：ZONE → `resolveHomeZone`（20001 / 1003），`MarketZone = 归属区`；GLOBAL → `MarketZone = zone_filter`；其他 → ERROR、1003。
8. 搜索非空：pattern 与编号（`:145-151`）。
9. `favorites_only` → `FavoritesOf = caller`（`:152-154`）。
10. L1 → 失败回 1003（`:156-159`）。
11. `PageWindow(total, page, pageSize, MaxPage)`（`:160`）。
12. L2（`offset, pageSize`）→ 失败回 1003（`:161-164`）。
13. `favoriteSet` → 失败回 1003（`:165-168`）。
14. 应答 `{listings, total_count = min(total, 2^32−1), page, page_size, page_count, market_scope, server_now_ms}`（`:170-178`）。

单测：查询带上请求与时钟、REMAINING 的列由 data 按 tab 选（`jubaozhai_logic_test.go:499-523`）；摘要的 is_mine / is_favorite / phase（`:556-585`）；三类存储故障都 in-band（`:597-611`）。

### 3.3 GetListingDetail（197）（`jubaozhai_logic.go:222-258`）

1. 套预算。2. 会话 → 1005（`:232-235`）。3. `listing_id == 0` → 1005（`:236-239`）。4. 取 `nowMs`（`:240`）。
5. `loadVisibleListing` → 20000 / 20001 / 1003（`:242-245`）。
6. F2 → 失败回 1003（`:246-249`）。
7. 应答 `{detail{summary = toSummary(...), description}, server_now_ms}`（`:251-257`）。

单测矩阵 `jubaozhai_logic_test.go:617-690`：id 0、不存在、zone 同区可见（查 1 次归属区）、zone 别区 20000、卖家看已结束商品可见且不查归属区、已结束对买家 20000、global 别区可见且不查、
收藏标记、买家未映射 20001、归属区故障 1003、GetListing / FavoriteExists 故障 1003；拒绝时不带 detail（`:678-681`，断言在 `:679`）。

### 3.4 SetFavorite（198）（`jubaozhai_logic.go:260-322`）

先读 `listing_id`（`:273`）。**所有应答（含拒绝）都带回 `listing_id`**：拒绝 `{error_message, listing_id}`、`favorite = false`（`:274-279`）。
1. 套预算。2. 会话 → 1005（`:281-284`）。3. `listing_id == 0` → 1005，两个方向一样、不碰库（`:285-287`；`jubaozhai_logic_test.go:789-797`）。
4. **取消**（`favorite = false`）：直接 F5 → 失败 1003，否则回 `favorite = false`（`:289-294`）。不查商品、不查归属区、不取时间；下架了、看不见了也能取消（`jubaozhai_logic_test.go:696-710`）。
5. **加收藏**：取 `nowMs`（`:296`）→ `loadVisibleListing`（20000 / 20001 / 1003，`:297-299`）→ F2（失败 1003；已收藏直接回 true，`:300-306`）→ F3（失败 1003；
   `count ≥ MaxFavoritesPerPlayer` → 20002，`:307-313`）→ F4 `{player, listing, created_ms = nowMs}`（失败 1003，`:314-320`）→ 回 true（`:321`）。

单测：`jubaozhai_logic_test.go:712-798`（成功、重复、达到上限、低于上限一条、别区、不存在、id 0）；故障 in-band（`:800-823`）。

### 3.5 GetMyShelf（200）（`jubaozhai_logic.go:181-220`）

1. 套预算。2. 会话 → 1005（`:190-193`）。3. `ClampPageSize`、取 `nowMs`（`:194-196`）。4. L3 → 失败 1003（`:198-201`）。5. `PageWindow`，同样受 `MaxPage` 封顶（`:202`）。
6. L4（`listing_id DESC`）→ 失败 1003（`:203-206`）。7. `favoriteSet` → 失败 1003（`:207-210`）。
8. 应答 `{listings, total_count, page, page_size, page_count, server_now_ms}`（`:212-219`），**没有** `market_scope`（`jubaozhai.proto:144-152`）。

特点：不查归属区、不受市场范围约束、包含任意状态（已结束显示 ENDED）、`is_mine` 恒为 true（`jubaozhai_logic_test.go:829-856`）；故障 in-band（`:858-871`）。

### 3.6 TradeAdmin.SeedListing（199，内部；`admin_logic.go:41-127`）

协议见 §0.3。执行顺序：
1. 套预算（`:53-54`）。
2. `Mode ∉ {dev, test}` → gRPC **PermissionDenied**，计 `rejected`（`:55-60`；`config.go:228-232`）。放在方法里而不是「只在 dev 注册服务」，因为 TradeAdmin 以后还要放生产可用的运维方法（`:44-48`）。
3. `ValidSeedRequest` 不过 → in-band 1005，计 `rejected`（`:66-68`）。校验项（`phase.go:188-213`）：seller ≠ 0；类目与子类合法；`TrimSpace(title)` 非空且**原文**过 `ValidText(64)`；
   摘要 ≤ 128、描述 ≤ 512（都过 `ValidText`）；`icon_key` 为空或 ≤ 64 字节且只含 `[a-z0-9_]`（`:133-145`）；level ≤ 1000；price ∈ [1, 1e10]；寄售时长 ∈ [1 ms, 90 天]；公示时长 ≤ 30 天。
4. 查卖家归属区（`:71-78`）：未映射 → 20001（计 `rejected`）；故障 → 1003（计 `error`）。
5. 发号（`:80-93`）：没接线、出错或拿到 0 → 1003（计 `error`）。
6. 按 §1.2 组装行，L6；失败 → 1003（计 `error`，`:95-121`）。
7. 成功：计 `ok`，INFO 日志，回 `{listing_id, market_zone}`（`:123-126`）。

**不幂等**：每次调用都发新号（`:50`）；不移动任何资产。单测 `admin_logic_test.go:25-155`（非 dev/test 拒绝且零 I/O、test 档放行、校验 in-band 且零 I/O、成功、无公示期立即寄售、各类失败）。

**隔离**：会话拦截器拦住带会话调 TradeAdmin 的请求（`session.go:81-84`）；gate 不收 199（§0.8）；reflection 只在宽松模式注册（`trade.go:199-201`）。

### 3.7 重放与幂等速查

| 方法 | 重放结果 |
|---|---|
| 浏览 / 详情 / 货架 | 只读，可任意重放；结果随时间窗与他人写入变化 |
| 加收藏 | 幂等：已收藏回 true（不计数）；并发同键由 ODKU 收敛（§1.9） |
| 取消收藏 | 幂等：DELETE 不存在的行也回 false |
| SeedListing | **不幂等**：每次一条新商品（robot 按 nonce 标题找自己的商品） |

---

## 4 写侧边界（哪一批做、4.7 怎样造数据做端到端）

### 4.1 基线里谁写这两张表

- **`trade_listing` 的唯一插入**是 L6，唯一调用方是 `SeedListing`（`admin_logic.go:118`）。P1 **没有下架、过期回退或任何改状态的写路径**；种子商品逐轮累积
  （`trade_smoke_scenario.go:872-876`；`etc/trade_smoke.yaml:37`）。
- **合服工具**按清单逐条主键点更新 `market_zone`：`UPDATE trade_listing SET market_zone = dst WHERE listing_id = ? AND market_zone = src`（`trade_step.go:11-18`、`:178-195`）；
  `seller_zone_at_listing` 与收藏表不动（`:19-20`）。注释原话「P1 的 trade 服对 trade_listing 只有 INSERT」（`:17`）。Java 不做合服（`roadmap.md` 7.3）。
- **`trade_favorite` 只由 SetFavorite 写**（F4 / F5）。
- 集成测试直接调 `InsertListing`（`listing_repo_integration_test.go:136-139`）。
- **P2 不写商品表**：`EnqueueEscrowDebit` 只写 `trade_player_op_seq` 与 `trade_asset_op`（`pipeline.go:203-309`）。

### 4.2 `internal/lifecycle` 与 `internal/reconcile` 不是「商品生命周期」

- `internal/lifecycle` 管的是**进程停机**：注销 → 排空 → 关资源，全程有界（`lifecycle.go:1-30`；`trade.go:119-126`），与商品状态无关。
- `internal/reconcile` 是 P2 资产指令管线（§4.3），不是商品到期扫描；到期回退（RETURNING）属于 P3 的扫描任务（设计稿 :101）。

### 4.3 P2：trade 侧资产托管通道（已落码、默认关闭、没有生产调用方）

| 项 | 基线 |
|---|---|
| 开关 | `AssetOp.Enabled` 缺省 false，整段缺失也算关（`config.go:111-155`；`trade.yaml:115-142`）。关闭时不拨 SharedRedis、不建 scene 节点镜像、不起重投循环，浏览 / 详情 / 收藏照常（`servicecontext.go:99-133`）。开启时密钥缺失或短于 32 字节 → 拒启（`:114-122`） |
| 生产者 | `reconcile.Pipeline.EnqueueEscrowDebit`（`pipeline.go:203-240` 起），**全仓没有生产调用方**（`asset_op_repo.go:222`；全仓 grep 只在注释与定义处出现）；注释说调用方会是「P3 的 CreateListing」（`pipeline.go:101`） |
| 流与调用方 | 独占 `ASSET_OP_STREAM_TRADE_DEBIT` / `TRADE_CREDIT`（`pipeline.go:67-74`，`TradeStreams` 在 :69）；caller `"trade"`、密钥 `MMORPG_ASSET_OP_SECRET_TRADE`（`assetchannel.go:36-42`）；tx `TX_AUCTION_SELL`（`pipeline.go:292`）；op_id 号段 biz_tag `trade_asset_op`（`assetchannel.go:54`） |
| 能力 | v1 只托管游戏币：恰 1 条货币、0 件物品、不带 `item_uuids` / `pet_id`、金额 ∈ [1, INT64_MAX]（`pipeline.go:329-350`） |
| 重投循环 | 间隔 2 s、每批 100、8 worker、租约 10 s、单行预算 2.5 s、退避 1–60 s、等落盘 500 ms、读账本前至少 3 次、毒行推迟 1 h、单次 RPC 800 ms（`pipeline.go:36-65`） |
| 对侧账 | 只留 TODO：托管 APPLIED → LISTED；REJECTED / ABORTED → ESCROW_REJECTED；交付 → 订单 DELIVERED + 卖家入账；回退 → RETURNED；部分发放转人工（`asset_op_repo.go:711-717`） |
| 启用前的闸 | 设计稿 §12.3：J-A4 签名扩展与 B4c 对账闸门完成之前，任何共享环境不得开启（设计稿 :315-321、:334） |

### 4.4 P3–P6：只有设计（基线没有代码、proto、消息号、tip）

- **规划的 RPC**（设计稿 :264）：客户端 `CreateListing`（oneof 资产 + `price_fen` + `client_request_id`）、`CancelListing`、`CreateOrder`（返回支付意图 MOCK / URL / QR）、`GetOrder`；
  内部 `MockConfirmPayment`（dev）、`ReconcileNow`、`FreezeListing`；login 侧 `LockPlayerForTrade` / `UnlockPlayerForTrade` / `TransferPlayer`。**都还没有消息号**
  （`docs/porting/inventory/social.md:341-351`）。
- **状态机**：商品见设计稿 :86-101，订单 :103-115；公示期满不改状态，**寄售期满的回退要 P3 的扫描任务**（:101）。
- **表**（设计稿 :227-246）：`trade_order`、`trade_seller_ledger`、`trade_payment_event`、`trade_audit_log`；商品表增加 `kind / active_order_id / snapshot` 与 `(status, sale_end_ms)` 等索引（:231、:235）。
- **其他**：Item / Pet 表加 `tradable / market_category / market_subcategory`（:335）；合服围栏必须在 P3 正式上架前接上（`trade_step.go:29-34`）；P4 角色交易、P5 真实支付、P6 竞价（:327-343）。
- Java 现在只需保持「竞价回 20003」（`social.md:341-351`）。

### 4.5 Java 侧的依赖链

| 前置 | 状态 | 出处 |
|---|---|---|
| scene 侧资产通道（账本、验签、扣发、durable） | ✅ 2.9。TRADE 两条流已在白名单：DEBIT 收 tx 3（AUCTION_SELL），CREDIT 收 4（AUCTION_BUY）与 1（TRADE）；caller `trade`、密钥 `XM_ASSET_OP_SECRET_TRADE` | `xm-scene/.../asset/AssetOpService.java:95-98`；`AssetOpAuth.java:52-53`；`PARITY.md:83` |
| 跨进程传输 `SceneAssetOpService`、定位 `SceneAssetLocator`（读 `xm:location`，**不读 presence**）、签名共用件 | 4.5 引入 | `docs/porting/guild-economy-spec.md:841-900` |
| 调用方库（签名器、Caller、Loop、Decide） | 4.5 先放 xm-guild，**交易接入资产通道时再抽成共享件** | `guild-economy-spec.md:984`、`:1221`（EN5）、`:1230`（Q4）；`roadmap.md:36` |
| 背包 / 宠物交易原语 | 背包 2.4 已在；宠物交易原语「随聚宝斋资产托管，未排批次」 | `PARITY.md:81` |
| 账号角色持久化（基线 P0-b） | Java 天然满足：`player.account` 在 MySQL | `xm-player-schema.sql:11-32` |
| 合服围栏 | Java 不做合服（7.3）；可照 guild D4 留恒放行接口 | `guild-spec.md:1716` |
| **P3 客户端契约**（消息号、tip、配表列） | **mmorpg 还没定** | §4.4 |

### 4.6 批次归属建议

- **4.7 只做只读面 + dev 播种**，与基线 P1 对齐：只建两张表；不碰位置记录、不接资产通道、不读写业务 Redis。
- **路线图新增一行**（建议编号 4.8，「聚宝斋交易写侧」）：上架托管、下架、下单、mock 支付、交付、回退、到期扫描、货架里的订单、合服闸；**依赖 4.5 + mmorpg P3 契约**；
  **trade 侧 P2 与 P3 同批移植**。理由：P2 没有生产者、默认关闭、设计稿禁止在共享环境开启，单独移植只是多一份死代码（YAGNI）；P3 改契约必须两版同批（`AGENTS.md` §1）。
  P4–P6 另行排期。
- **PARITY 建议登记**：「聚宝斋资产托管（trade 侧 P2）」状态写「mmorpg 进行中（已落码未启用）／Java 待 mmorpg P3 定契约后同批」；「聚宝斋交易写侧（P3–P6）」状态写「mmorpg 待做」。

### 4.7 4.7 要留的钩子（只留形状，不做行为）

- `trade_tables.proto` 字段号照抄 1..19 与 1..3，并写明追加区：P2 两张表以后在文件末尾追加、字段号照 `trade_table.proto:85-147`；P3 新列从 20 起；index 组只能追加到末尾
  （`idx_<表>_<序号>` 按位置命名，先例 `xm-guild/src/main/proto/xm/guild/guild_tables.proto:8-10`）。
- 阶段推导与可见性规则**完整移植**，包括 LOCKED、ESCROWING 等 P3 状态（`phase.go:17-60`）。
- `seller_account` 写空串、`version` 写 0（`admin_logic.go:100`、`:116`）。
- 新增 `NodeTypes.TRADE`；P3 发 listing_id 时直接复用，op_id 是否共用由写侧批次定（同 guild-economy-spec Q6 的问题）。

### 4.8 4.7 怎样造数据做端到端

路线图 4.7 只列了 4 个读方法（`roadmap.md:64`），但基线 robot 第 1 步依赖播种（`trade_smoke_scenario.go:234-279`），所以**播种并进 4.7**（inventory 的
`trade-seed-listing`，`social.md:317-327`）。

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A** | xm-trade 管理端口 `POST /admin/trade/seed-listing`：请求体是 `trade.SeedListingRequest` 的 protobuf 二进制（`Content-Type: application/x-protobuf`），应答 200 带 `SeedListingResponse` 二进制（业务拒绝写在 `error_message`）。鉴权照 xm-data `AdminAuthFilter` 的语义：令牌 `XM_ADMIN_TOKEN`、头 `X-Xm-Admin-Token`、常数时间比较、必填 `X-Xm-Operator`、令牌没配回 503、每次调用一行审计日志（`xm-data/.../admin/AdminAuthFilter.java:16-74`）。接口总注册，非 dev/test 回 403（对应 PermissionDenied）。语义与 §3.6 完全相同 | 校验 → 查归属区 → 发号 → 插入全在 xm-trade 一处；能断言服务端写入的 `market_zone`；robot 已有带令牌的 HTTP 客户端，基地址可换（`xm-robot/.../client/AdminClient.java:22-36`、`:86-90`），令牌复用 `run/xm-admin-token`（`start-slice.sh:24-31`；`AdminClient.java:38-49`）；robot 不加 Dubbo；缺省只绑本机；protobuf 二进制不引入新 JSON 依赖、uint64 无歧义 | 第二个服务挂运维 HTTP 面；鉴权过滤器要在 xm-trade 复制一份（xm-common 没有 servlet 依赖） | **推荐** |
| B | xm-api 新增 `TradeAdminService.seedListing(SeedListingRequest) → CompletableFuture<SeedListingResponse>`，xm-trade `@DubboService(group = "trade")` 提供，非 dev/test 抛 `RpcException(FORBIDDEN)`；再由 xm-data 加 `POST /admin/trade/listings/seed` 转 Dubbo，或 robot 直连 Dubbo | 拓扑最接近基线 gRPC 直连 `TradeAdmin`；热关停提供方过滤器自动覆盖 | xm-data 目前**不是** Dubbo 消费方（只依赖 xm-api 的消息类型，`xm-data/pom.xml:24-28`，仓内无 `@DubboReference`），要加 Dubbo 运行时、`XM_DUBBO_SECRET` 与引用配置；robot 直连则要给 robot 加 Dubbo（`xm-robot/pom.xml` 没有） | 备选 |
| C | xm-data 运维接口直接写 `trade_listing` | robot 已对接 xm-data | 一张表两个写者；发号要另占雪花 worker；校验与归属区推导要复制一份 | 不采纳 |
| D | robot / 测试用 JDBC 直接 INSERT（SQL 夹具） | 不加服务端接口 | robot 要拿 MySQL 口令；绕过校验与「market_zone 来自卖家归属区」这条第 1 步断言；表要等 xm-trade 起来后才有 | 只用于 MySQL 集成测试 |
| E | dev 档启动时加载静态夹具 | 不写代码 | 时间窗会过期；run-tag 账号每次是新 player_id，夹具事先不知道 | 不采纳 |
| F（A 的变体） | 播种请求带 dev 专用 `market_zone` 覆盖，好在单区切片里造「别区商品」 | 单区也能覆盖跨区过滤 | 基线刻意没有这个字段（`trade_admin.proto:22`），会废掉第 1 步的推导断言 | 不推荐（跨区靠服务单测 / MySQL 测试 + `zone_filter` 断言补） |
| G | 让客户端消息 199 经 gate 可达 | — | 违反契约；基线第 2 步就是断言 199 经 gate 无回包 | **禁止** |

方案 A 的 HTTP 状态映射：令牌没配 503、令牌错 401、缺操作人 400（过滤器）；非 dev/test 403；请求体解析失败 400；其余（含参数非法、卖家无归属区、存储故障）一律 200 + in-band `error_message`。

### 4.9 基线 robot `trade-smoke`

**配置与前置**（`etc/trade_smoke.yaml:10-59`；结构与校验 `robot/config/config.go:244-267`、`:292-317`；入口 `main.go:177`）
- `cross_zone`（缺省 true）、`zone_a = 1`、`zone_b = 2`；`admin_addr = 127.0.0.1:50800`（trade gRPC 直连）；`expect_scope` 没有缺省、须与 `Market.Scope` 一致；`request_interval_ms = 1100`
  （按「聚宝斋号不在限频表」推的，已过时，§7.5）。
- 前置：gate 路由服模式；trade `Mode: dev`；`mmorpg_trade` 已建；BootstrapTags 含 `trade_listing`；`robot_9401/9402` 首次在 zone_a 建角、`robot_9403` 首次在 zone_b 建角
  （`trade_smoke_scenario.go:63-75`）。两种范围各跑一次，每次改 `Market.Scope` 并重启 trade（`etc/trade_smoke.yaml:30-32`）。
- 结果：`TRADE_SMOKE_OK scope=… listing_a=… listing_c=… zone_a=… zone_b=…`（`trade_smoke_scenario.go:532-535`）；失败 `TRADE_SMOKE_FAIL step=… reason=…`，退出码 1（`:176-181`）。

| 步 | 内容 | 断言 | 行 |
|---|---|---|---|
| 0 | A、B 登 zone_a，C 登 zone_b（`cross_zone = false` 时也登 zone_a），并发 | 跨区时 C 的 gate ≠ A 的 gate | `:195-232` |
| 1 | gRPC 直连 `SeedListing`、不带会话：武器 / 子类 1 / 60 级 / 123456 分 / `smoke_weapon` / 寄售 1 h：A 寄售（公示 0）、C 寄售、A 公示 1 h；标题 = nonce `SMK-<纳秒>` 加 `-A` / `-C` / `-AN` | A 的两条 `market_zone == zone_a`；跨区时 C 的 `== zone_b`，否则只要求非 0；`listing_id ≠ 0` | `:96-107`、`:234-279`、`:719-769`、`:872-876` |
| 2 | A 经 gate 发 199（标题 `nonce-GATE`） | **必须 10 s 内无回包**；信封 tip ≠ 0 或拿到业务回包都算失败；只发一次（非法包阈值 50） | `:77-79`、`:281-298` |
| 3 | 寄售页签、search = nonce、武器 / 子类 1、第 1 页 | 每次浏览：`market_scope == expect`、`server_now_ms ≠ 0`、`page_count == max(1, ceil(total/size))`、`1 ≤ page ≤ page_count`（`:693-713`、`:815-825`）。B 能看到 A，摘要逐字段与种子一致、ON_SALE、`is_mine = false`、`market_zone = zone_a`（`:827-852`）；gate 种子标题不得出现。**zone**：B 看不到 C；B 传 `zone_filter = zone_b` 仍看到 A、看不到 C；C 只看到 C。**global**：B 看到 C；`zone_filter = zone_b` 只剩 C | `:300-370` |
| 4 | 公示页签 | 公示商品不在寄售列表；在公示列表且 PUBLIC_NOTICE；寄售商品不在公示列表 | `:372-390` |
| 5 | `page_size = 50`；`page = 9999` 且 `page_size = 1` | 回显 20、条数 ≤ 20；`page == page_count` 且条数 ≤ 1 | `:87-94`、`:392-416` |
| 6 | section = AUCTION | in-band 20003 | `:418-424` |
| 7 | 详情 | B 看 A 受理、摘要一致、ON_SALE、描述一致；不存在的 id（`MaxInt64`）→ 20000；跨区时 zone 下 C 看 A → 20000、global 下受理；A 看自己 `is_mine = true` | `:109-111`、`:426-464`、`:854-870` |
| 8 | B 收藏 A → 只看收藏浏览 → 取消 → 再浏览 | 回包 `listing_id = A`、`favorite = true`；能看到且 `is_favorite`；取消回 false；之后看不到 | `:466-497` |
| 9 | 货架第 1 页 | A 的货架含两条种子且 `is_mine`、`server_now_ms ≠ 0`；B 的货架不含 | `:499-526` |

**robot 的辅助约定**：5 个 trade 号的回包（含信封）由场景自己认领，避免信封被通用分发器解成全零应答（`:583-607`、`:775-785`）；信封与超时一律当错误，不当作 trade 给的码
（`:655-668`）；gate 推 23 {1003} 立即判失败（`:646-649`）；20001 / 1003 / 1005 翻成可读的环境原因（`:670-691`）；「不存在」的 id 用 `MaxInt64` 而不是 uint64 上限，避免驱动差异（`:109-111`）。

### 4.10 Java robot `TradeScenario`

- **接线**：`RobotOptions.Scenario` 加 `TRADE`（`xm-robot/.../RobotOptions.java:62-63`）；`RobotMain` 照 GUILD 分支接线（`RobotMain.java:132` 一带）；新增选项
  `--trade-admin-url`（`XM_ROBOT_TRADE_ADMIN_URL`，缺省 `http://127.0.0.1:18111`）与 `--trade-scope`（`XM_ROBOT_TRADE_SCOPE`，`zone|global`，缺省 `zone`，与 xm-trade 的
  `application.yaml` 一致；见 Q9）。输出用 `CheckReport`，引用 PARITY「聚宝斋只读面」行。
- **账号**：`prefix + "td" + runTag + "_a|_b|_c"`，不用 9401–9403（同 guild D18，`guild-spec.md:1730`）。本机切片单 zone，`_c` 与 A 同区（相当于基线 `cross_zone = false`）。
- **单区下的范围断言**：zone 范围 → B 传 `zone_filter = 当前区 + 1` 仍看到 A（过滤被忽略）；global 范围 → 同样的过滤让 A 消失、`zone_filter = 当前区` 仍看到 A。
  `market_scope` 不符时第一次浏览就失败。
- **第 1 步**：经方案 A 播种，断言 `market_zone == 卖家 player.zone_id`（= robot 登录的区）。
- **第 2 步**：`send(199)` 后在限定时间内断言收不到任何同请求号的消息（Java gate 不回包，`ClientDispatcher.java:217-221`）。写法照 `FriendScenario.java:251-260` 的 `send + await`，断言方向相反；
  只发一次（非法包阈值 50，`xm-gate/src/main/resources/application.yaml:66`）。
- **间隔**：同号相邻请求 300 ms 即可（196 / 197 / 200 每秒 10 条、198 每秒 5 条，§0.2）。
- **gate 推 23 {1003}** 时立即判失败并提示「xm-trade 不可达」（对应基线 `:646-649`）。
- 基线第 0–9 步照跑（跨区分支只在配了第二个 zone 时跑，D18 同理），Java 增项见 §9.6。

---

## 5 Java 落地映射

### 5.1 进程与模块

| 组件 | Java | 依据 / 先例 |
|---|---|---|
| 进程 | 新模块 **xm-trade**；Dubbo 端口 **20887**，管理端口 **18111**（已占用：Dubbo 20881–20886；管理 18101–18110，已逐个核对各模块 `application.yaml`）；根 `pom.xml` 在 `<module>xm-guild</module>`（`pom.xml:31`）之后加 `<module>xm-trade</module>` | `tools/local/start-slice.sh:49-60` |
| Dubbo group | `DubboGroups.TRADE = "trade"`（proto 一级目录） | `xm-api/.../DubboGroups.java:3-25` |
| 提供方 | `TradeClientMessageService implements ClientMessageService`，`@DubboService(group = TRADE)`；`sessionClosed` / `abandonEnter` 直接回 Ack（无会话状态） | `xm-friend/.../FriendClientMessageService.java:15-41` |
| 鉴权 | 必须有 `XM_DUBBO_SECRET`，缺失拒启 | `architecture.md:91-102` |
| 派发 | `dispatch/TradeDispatcher` + `TradeWorkerPool`（固定线程 + 有界队列 + AbortPolicy） | `FriendDispatcher.java:53-224` |
| 预算 | `com.game.common.deadline.Deadline`，受理时刻 + 3500 ms | `xm-common/.../deadline/Deadline.java:12-58` |
| 规则（纯函数） | `rules/ListingRules`（`phase`、`visibleToBuyer`、`clampPageSize`、`pageWindow`、`escapeLike`、`validText`、`validIconKey`、`normalizeSearch`、`searchListingId`、各枚举校验、`validSeedRequest`、`maxSubcategory`）；`rules/TradeLimits`（§0.6 常量）；`rules/TradeTips`（码值 + `FAULTS`） | `phase.go`、`constants.go` |
| 服务 | `service/JubaozhaiService`（4 个方法）、`service/SeedListingService`；领域对象 + `XxxService`，不用 ECS 风格 | 同 friend / guild 分层 |
| 存储 | `store/ListingStore` 接口 + `JdbcListingStore`；`store/ListingQuery`（record）；`store/TradeTables`（pbmysql 注册）；表定义 `src/main/proto/xm/trade/trade_tables.proto` | §5.5 |
| 归属区 | 复用 xm-guild 的 `HomeZones` / `PlayerTableHomeZones` 形状（建议上移到 xm-common，Q6） | `xm-guild/.../zone/HomeZones.java:7-23`；`PlayerTableHomeZones.java:19-46` |
| 发号 | `id/ListingIds`（照 `GuildIds`）：雪花 + `NodeIdLease`，作用域 0；新增 `NodeTypes.TRADE = "trade"` | `xm-guild/.../id/GuildIds.java:6-39`；`NodeTypes.java:21-24` |
| 播种 | `admin/SeedListingController` + `admin/TradeAdminAuthFilter`（§4.8 方案 A） | §5.8 |
| 指标 | `metrics/TradeMetrics` | §6.2 |
| 不需要的 | 在线目录、推送、缓存、Kafka、合服闸、资产通道 | §0.1、§1.11 |

### 5.2 gate 接入

1. `MessageRoutes.SERVICE_BACKENDS` 加 `"ClientPlayerJubaozhai" → DubboGroups.TRADE`（`MessageRoutes.java:32-37`）。199 的服务 `TradeAdmin` 不是客户端服务，不进路由表（`:43-54` 只收 `clientService()`）。
   加完之后仍有未接入的非玩家客户端服务（`BattleClientPlayer`、`MatchService`），`MessageRoutesTest.Java版未接入的客户端服务路由到unsupported`（`MessageRoutesTest.java:57-67`）不受影响。
2. `GateConfiguration` 加 trade 引用并放进 backends Map（照 `GateConfiguration.java:113-122`、`:136-153`）：
   `@DubboReference(group = TRADE, check = false, url = "${xm.dubbo.trade-url:}", methods = @Method(name = "handle", retries = 0))`。
   不重试：读路径重试会在超时时把负载翻倍、Dubbo 内部重投也会打乱预算；SetFavorite 虽幂等，也没有重投的必要。
3. 配置：`xm-gate/src/main/resources/application.yaml` 在 `guild-url`（:58）之后加 `trade-url: tri://127.0.0.1:20887`；`application-nacos.yaml` 在 :12 之后加 `trade-url: ""`。
4. 本机脚本：`start-slice.sh` 服务清单加 `"xm-trade 20887"`（:49-60），第 6 行的 `XM_DUBBO_SECRET` 读者名单补上 xm-trade；`stop-slice.sh:6` 的停止顺序加 xm-trade（gate 之后、guild 之前）。
5. 队列与超时：每会话每后端一条在途队列，满 64 即断开（`ClientDispatcher.java:329-341`；`application.yaml:65`）；身份取**入队时**的会话快照（`ClientDispatcher.java:352-364`）；
   gate 的 Dubbo 超时 5000 ms（`application.yaml:83`），晚于 xm-trade 的 3500 ms 预算。
6. 后端调用失败的回法：见 T8 / Q1。

### 5.3 准入与错误映射（`TradeDispatcher`）

| 情形 | 基线客户端所见 | Java 返回 | 指标 result |
|---|---|---|---|
| 196 / 197 / 198 / 200 正常 | 应答体 | `ClientReply{body}`，`tip_id = 0`；业务拒绝写在 body 的 `error_message`，**不带 parameters** | `ok` / `business_error`（body 里是 1003 记 `internal_error`） |
| 请求体解析失败（含 proto3 非法 UTF-8） | gRPC 解码错误 → 信封 1003 | 信封 1003 | `bad_request` |
| 会话 `player_id == 0` | Unauthenticated → 信封 1003 | 信封 1003，打 ERROR | `unauthenticated` |
| 199（理论上走不到，gate 已丢弃） | PermissionDenied → 信封 1003 | 信封 1003 | `forbidden` |
| 不认识的号 | — | 契约里没有 → 1013；有但不归 trade → 1006（同 `FriendDispatcher.java:206-214`） | `unsupported` |
| 工作队列满，或排队已超预算 | 基线没有应用层队列；依赖变慢时预算到期 → in-band 1003（`jubaozhai_logic_test.go:951-980`） | **in-band 1003**（同 friend，`FriendDispatcher.java:159-165`、`:179-183`），**不带原因串**；客户端显示「服务暂时不可用」、不隔离 | `overloaded` |
| 处理器抛 RuntimeException、SQL 故障、归属区故障、预算到期 | in-band 1003（`jubaozhai_logic.go:354-377`） | in-band 1003 | `internal_error` |

- **判定顺序**：建议「解析 → 身份」，与基线一致（grpc-go 先解码再走拦截器，§0.8）。注意 friend 的实际顺序相反：身份检查在 `dispatch` 里（`FriendDispatcher.java:151-156`），解析在工作线程
  （`:173-178`）。两种顺序客户端所见都是信封 1003，只影响指标标签。
- **基线「无会话 → in-band 1005」在 Java 不可达**：`ClientMessageService` 只给 gate 用、总带会话（`ClientMessageService.java:34-46`）；基线里也只有无 metadata 的内部直连能走到它
  （`session.go:70-74`）。服务层仍保留这条判定（`player_id == 0` → 1005），单测覆盖（T6）。
- **SetFavorite 的派发层失败**（队列满）发生在解析之前，回不出 `listing_id`；客户端不读这个字段（`JubaozhaiClient.cs:157-164`），没有可见差异。解析之后的所有拒绝都要回填。
- **启动校验**（照 `FriendDispatcher.java:93-126`）：4 个方法名都在 `message_id.txt` 里；请求原型与处理器类型一致；每个应答都有 `error_message`；另登记 199（`TradeAdmin/SeedListing`）的号用于判 `forbidden`。

### 5.4 线程、预算、超时

- **线程**：Dubbo 线程只投递；JDBC 只在 `trade-worker` 有界池上做，缺省 16 线程、队列 1024（`AGENTS.md` §3；`xm-friend/src/main/resources/application.yaml:73-74`）。播种接口在
  Tomcat 线程上执行（只在 dev/test 生效、低频），同样受预算约束。
- **整请求预算 3500 ms** = 基线 `Timeout − 500`（`config.go:28-39`、`:219-221`），从受理时刻算起、含排队；启动时校验 ∈ [500, 3500] ms（照 friend `request-budget`，`application.yaml:70`）。
- **单次上限是代码常量**：归属区 `min(1500 ms, 剩余)`（`constants.go:71`；先例 `GuildLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS`，`GuildLimits.java:110`；`PlayerTableHomeZones.java:37-38`）；
  每条 SQL `min(2000 ms, 剩余)`（`constants.go:78`）；收藏写入整个带重试的调用一共 `min(2000 ms, 剩余)`（对应 `listing_repo.go:302-305` 的 `bounded(ctx)` 包住整个 `WithTxRetry`）。
- **超时怎么落到 JDBC**：SELECT 加 `/*+ MAX_EXECUTION_TIME(n) */`，`setQueryTimeout(ceil(秒))` 兜底网络停顿；取连接也只等剩余预算（写法照 `PlayerProfiles.java:106-129`）；
  预算用完不再发语句、不再重试。
- **时间**：每个请求只取一次 now（§2.1），时钟可注入，便于测试。

### 5.5 MySQL：自有表 proto + xm-pbmysql + 连接池

- **库**：`xm_java`，不建独占库（T2）。
- **表定义**：`xm-trade/src/main/proto/xm/trade/trade_tables.proto`，`package xm.trade`、`java_package com.game.trade.store.pb`、`option (proto2mysql.db) = true`；
  **不 import** 同步来的 `trade_table.proto` / `jubaozhai.proto`（契约产物，不许手改；先例 `xm-friend/src/main/proto/xm/friend/friend_tables.proto:1-15`、`xm-guild/.../guild_tables.proto:1-12`）。

  | message | 表选项（照 `trade_table.proto`） | 字段（字段号同基线） |
  |---|---|---|
  | `TradeListingRow` | `table_name = "trade_listing"`、`primary_key = "listing_id"`、`index = "market_zone,status,category,subcategory,price_fen;status,category,subcategory,price_fen;seller_player_id,listing_id"`（生成 `idx_trade_listing_0/1/2`）、`tidb_nonclustered_pk = true`、`tidb_shard_row_id_bits = 4`、`tidb_pre_split_regions = 4`（:29-36） | `uint64 listing_id=1; uint64 seller_player_id=2; string seller_account=3; uint32 market_zone=4; uint32 seller_zone_at_listing=5; int32 category=6; uint32 subcategory=7; string title=8; uint32 level=9; uint64 price_fen=10; int32 status=11; string summary=12; string description=13; string icon_key=14; uint64 notice_end_ms=15; uint64 sale_end_ms=16; uint64 created_ms=17; uint64 updated_ms=18; uint64 version=19;`（:38-56） |
  | `TradeFavoriteRow` | `table_name = "trade_favorite"`、`primary_key = "player_id,listing_id"`、`index = "listing_id"`，TiDB 三项同上（:60-65） | `uint64 player_id=1; uint64 listing_id=2; uint64 created_ms=3;`（:67-69） |

  `category` / `status` 用 `int32`（§1.5）；数值与契约枚举逐个相等，由单测对拍（`jubaozhai.proto:18-30`、`trade_table.proto:17-26`）。
- **DDL 对拍**：照 `GuildTablesTest.java:23-45`，拿同步来的 `com.game.proto.trade.TradeListingRecord` / `TradeFavoriteRecord` 生成的 DDL 与 Java 自有 proto 生成的逐字节比较，
  并与 §1.5 原文逐字比较。
- **建表**：`PbMysql.register(...)` 后在自动提交连接上 `syncAll`（`GET_LOCK` 保护、只扩不缩、结构漂移即拒启；`xm-friend/.../FriendConfiguration.java:69-81`；`PbMysql.java:120-123`；
  `architecture.md:536-556`）。与基线的差别：没有 `-migrate` / Job / `Schema.AutoMigrate` / 只读 plan（`trade.go:61-66`、`:364-395`），pbmysql 还会补建缺失的普通索引（同 guild D2）。
- **连接池**：Druid，URL 照 friend（`xm-friend/src/main/resources/application.yaml:15-32`）：会话 RC + `STRICT_TRANS_TABLES`（对应基线 DSN，`servicecontext.go:239-243`），
  `default-transaction-isolation: 2`；`innodb_lock_wait_timeout` 建议取 **2**，对齐单次 SQL 上限（基线不设锁等待，但每次调用被 2000 ms 的 ctx 截断；Q7）。
- **SQL 全部手写**，逐字照搬 §1.6 / §1.7；ORDER BY 只从固定映射取。
- **收藏写入**：F4 可以用自动提交语句 + 同样的重试（1213 / 1205 / 9007 → 再试一次，退避 8–12 ms，受预算约束）；单语句的 RC 事务与会话级 RC 下的自动提交语句语义相同。
  错误号沿 cause 链按 `SQLException.getErrorCode()` 取，不做文本匹配（同 friend / guild 先例，guild-spec §7.6，`guild-spec.md:1461`）。
- **无符号与位模式**：id、价格、毫秒字段绑定用 `PbMysql.uint64(..)`（`PbMysql.java:343-349`）或 `Long.toUnsignedString`；`level` / `subcategory` / `market_zone` / `zone_filter`
  按 `int unsigned` 绑 `PbMysql.uint32(..)`（`:351-352`）；`category` / `status` 绑 int。读回 `bigint unsigned` 不能直接 `rs.getLong`（≥ 2^63 时 Connector/J 报越界），
  按 `BigInteger` / 字符串读再转位模式。

### 5.6 归属区

- `HomeZones.homeZones(List.of(pid), deadline)`（`HomeZones.java:18-23`），生产实现 `PlayerTableHomeZones`：`PlayerProfiles.loadStrict`（`PlayerProfiles.java:76-87`），
  查询预算 `min(1500 ms, 剩余)`（`PlayerTableHomeZones.java:37-38`）。缺项或 `zone_id = 0` → 20001；`DependencyException` / 其它 RuntimeException → in-band 1003。
- 只在 §2.6 列的四处查；货架、取消收藏、global 范围都不查。
- 计数：`xm.trade.home.zone.lookups{result=ok|unmapped|error}`（§6.2）；「没接线」在 Java 是装配错误（Spring 起不来），不需要单独分支。

### 5.7 listing_id 发号

- 基线：号段 `biz_tag = trade_listing`，没有 snowflake 回退（`servicecontext.go:30-32`、`:270-291`；`config.go:298-303`；`trade.yaml:81-89`）；启动时只领一次首段、失败只告警
  （`servicecontext.go:293-305`；`trade.go:175-177`）。
- Java：`Snowflake`（41 位毫秒 / 10 位 worker / 12 位序号，符号位恒 0，所以恒 < 2^63；`Snowflake.java:14-20`、`:72`）+ `NodeIdLease.acquire(redis, scheduler, NodeTypes.TRADE, 0, 1,
  Snowflake.MAX_WORKER, …)`（`NodeIdLease.java:81`；写法照 `xm-guild/.../GuildConfiguration.java:175-186`）；每次发号前检查 `isValid()`（`NodeIdLease.java:142`）；租约无效、时钟回拨抛异常、
  或拿到 0 → 播种回 1003（`GuildIds.java:29-39` 同形）。
- **启动时申领失败**：建议同 guild / team（Spring bean，失败即拒启）；与基线「首段领不到只告警」不同（Q5）。
- **客户端可见的唯一差异**：编号变长。按 2026-01-01 的纪元（`Snowflake.java:17`），现在约 18 位十进制；客户端按 `ulong` 解析、按字符串显示（`JubaozhaiClient.cs:288-289`、`:343`），
  按编号搜索照常可用（T4）。`ORDER BY listing_id DESC` 仍大致等于「新上架在前」（基线多副本各领号段，同样只是近似）。robot 用 `MaxInt64` 当「不存在」的编号仍然安全。

### 5.8 播种接口（§4.8 方案 A）

- `POST /admin/trade/seed-listing`，挂在管理端口（`server.port: 18111`，`server.address: ${XM_MANAGEMENT_ADDRESS:127.0.0.1}`，同 friend `application.yaml:35-37`）。
- 过滤器 `TradeAdminAuthFilter`：语义照抄 `AdminAuthFilter`（令牌常数时间比较、操作人 1–64 字符不含控制字符、令牌没配 503、每次调用一行审计日志、作用范围由注册时的 URL 模式决定、
  指标的 op 标签只取已知接口）；只注册在 `/admin/*`。
- 控制器：先判 `RunMode.parse(xm.run-mode).allowsGmCommands()`（`RunMode.java:13-45`；本机切片缺省 dev，`start-slice.sh:19-22`）→ 否则 403、计 `rejected`；再解析
  `SeedListingRequest`（失败 400）→ `SeedListingService.seed(req, deadline)` → 200 + `SeedListingResponse`。
- `SeedListingService` 照 §3.6 第 3–7 步：校验（1005，计 `rejected`）→ 卖家归属区（20001 计 `rejected` / 1003 计 `error`）→ 发号（1003 计 `error`）→ L6（1003 计 `error`）→ 成功计 `ok`、
  INFO 日志 `listing_id / seller / market_zone / category / notice_end_ms / sale_end_ms`（`admin_logic.go:123-126`）。
- **不新增**类型化 Dubbo 接口（方案 B 的形状留到真有内部调用方时再加）。

### 5.9 Go 语义在 Java 里的边界（必踩点）

1. **uint32 / uint64 是位模式**：`page`、`page_size`、`subcategory`、`zone_filter`、`market_zone`、`level` 在 Java 是 int，`listing_id`、`price_fen`、各毫秒字段是 long。
   钳制和比较一律 `Integer.compareUnsigned` / `Long.compareUnsigned` 或 `toUnsignedLong`：否则 `page_size = 0xFFFFFFFF` 会变成 −1、「大于上限」判不出来；`subcategory = 0xFFFFFFFF`
   会变成 −1、「≤ 上限」误判为真。`page_count` 与 `total_count` 饱和到 `0xFFFFFFFF`。
2. **枚举一律用 `getXxxValue()` 比较整数**：proto3 未知值在 Java 是 `UNRECOGNIZED`，对它调 `getNumber()` 会抛异常；基线要求未知值与负值都回 1005（`jubaozhai_logic_test.go:357`、`:361`、
   `:365`、`:370`；负值见 `phase_test.go:210` 的 `ListingSort(-1)`）。写应答用 `setCategoryValue(int)`。
3. **trim 按 Go 语义**：`com.game.common.text.GoSpaces.trim`（`xm-common/.../text/GoSpaces.java:13-44`），**不用** `String.strip()` / `trim()`（Java 的空白集合不认 U+0085 / U+00A0、
   却把 U+001C–001F 算作空白，`GoSpaces.java:6-11`）。
4. **控制字符**：Go `unicode.IsControl` ⇔ `Character.isISOControl`（U+0000–001F、U+007F–009F；`xm-guild/.../rules/GuildNames.java:12`）。字符数按码点计（`codePointCount`），不按 char。
5. **按编号搜索不能直接 `Long.parseUnsignedLong`**：它接受开头的 `+` 与非 ASCII 数字（如全角 `１２３`），Go `ParseUint(s, 10, 64)` 都不接受。先校验全是 ASCII `[0-9]`，再解析；溢出就只按标题；
   解析出 0 不按编号。
6. **成功应答不设 `error_message`**；拒绝只填 `error_message`（SetFavorite 另填 `listing_id`）；tip 不带 parameters。
7. **请求体解析失败**：proto3 string 含非法 UTF-8 时 Go 与 Java 都在解析阶段失败，客户端看到的都是信封 1003（Java 先例 `architecture.md:360-361`）；服务层「非法 UTF-8 → 1005」的分支
   线上到不了，但纯函数仍照基线实现（单测直接打）。
8. **MEDIUMTEXT 读回 null → `""`**（§1.5）。
9. **LIKE 转义与 `ESCAPE '!'`** 照抄；不要改成反斜杠转义（受 `NO_BACKSLASH_ESCAPES` 影响，`phase.go:107-109`）。
10. **收藏写入不要改成 `INSERT IGNORE`**，移植 `listing_repo_test.go:126-138` 的守卫测试。

### 5.10 启动与关停（照 `trade.go:127-291`）

**启动**
1. 校验 `xm.trade.*`（scope 必填且合法、页长、预算），不合法拒启；缺 `XM_DUBBO_SECRET` 拒启。
2. pbmysql `syncAll` 建两张表，失败拒启（基线 `trade.go:166-170`）。
3. 申领 `NodeIdLease(TRADE)`（Q5）。
4. 打一行 INFO：scope、播种是否开放（运行模式）、库与用户（不含口令），对应基线横幅（`trade.go:263-282`，`:276-277`）。
5. 最后暴露 Dubbo（基线也是先起服务、再注册，`trade.go:212-261`）。

**关停**：反导出 Dubbo → 排空工作池 → 释放租约 → 关闭连接池（对应基线 `lifecycle.Shutdown` 的注销 → 排空 → 关资源，`trade.go:146-164`）。

### 5.11 配置项（`xm.trade.*`）

| 项 | 缺省 | 校验 / 出处 |
|---|---|---|
| `market.scope` | **代码无缺省**；`application.yaml` 显式写 `${XM_TRADE_MARKET_SCOPE:zone}`（同 `trade.yaml:96` 显式写 zone） | 不是 `zone` / `global` 即拒启（`config.go:191-193`、`:305-307`） |
| `market.default-page-size` / `max-page-size` / `max-page` / `max-favorites-per-player` | 20 / 20 / 100 / 100 | 都 > 0，缺省页长 ≤ 上限（`config.go:194-201`、`:308-325`；`trade.yaml:97-100`） |
| `request-budget` | 3500ms | ∈ [500, 3500] ms |
| `query-timeout` | 3s | 语句超时上限（同 friend `application.yaml:72`） |
| `worker-threads` / `worker-queue-capacity` | 16 / 1024 | 同 friend |
| `xm.run-mode` | 进程缺省 prod（本机切片设 dev） | 播种开关（`RunMode.java:13-45`；基线判据 `config.go:228-232`） |
| `xm.redis.*` | database 12 | 同所有进程（`xm-friend/.../application.yaml:58-60`） |
| `xm.killswitch.enabled` | true | 同 friend / guild / team（`xm-guild/.../application.yaml:60-61`）；客户端方法已在 gate 查过，提供方过滤器不查 `ClientMessageService.handle`（`architecture.md:422-424`） |
| 代码常量（不开放配置） | 归属区 1500 ms、单条 SQL 2000 ms、收藏写入 2 次尝试；文本 / 图标 / 等级 / 价格 / 时长上限 | `constants.go:49-79`；`asset_op_repo.go:873-874` |

### 5.12 本机切片、PARITY、路线图

- xm-trade `application.yaml`：`server.port: 18111`、绑定 `XM_MANAGEMENT_ADDRESS`（缺省 127.0.0.1）、`dubbo.protocol.port: 20887`、`registry: N/A`；另有 `application-nacos.yaml`（同 friend）。
  播种令牌读 `XM_ADMIN_TOKEN`（`start-slice.sh` 已导出，:24-31）。
- PARITY 登记「聚宝斋只读面（196 / 197 / 198 / 200 + dev 播种）」一行，有意差异列 T1–T13；`PARITY.md:55` 号段那一行的「不适用」说明补一句 `trade_listing` 改由 xm-trade 雪花发号；
  另登记 §4.6 的两行。
- 路线图 4.7 完成后打勾，并按 §4.6 加写侧一行。

---

## 6 指标

### 6.1 基线

- `trade_home_zone_lookup_total{result=ok|unmapped|error}`、`trade_seed_listing_total{result=ok|rejected|error}`（`servicecontext.go:336-368`）；`StartMetrics` 预建全部 result 序列，
  让「从没出错」与「指标不存在」能区分（`:377-386`）。label 一律不带 `player_id`（`:333-334`）。
- serverbase：`rpc_inband_fault_total{method,source,code}`、`rpc_inband_reject_total{method,source}`、`rpc_inband_unknown_code_total{method,source}`、`rpc_duration_seconds{method,status}`
  （`go/shared/serverbase/metrics.go:13-55`）；grpcstats 的流量统计；killswitch 的 `killswitch_blocked_total`（`trade.yaml:33-36`）。
- 浏览类方法没有专门的业务计数。

### 6.2 Java 建议（`TradeMetrics`，Micrometer，低基数；`AGENTS.md` §5）

| 指标 | 对应基线 | 标签 |
|---|---|---|
| `xm.trade.requests`（Timer，桶同 `FriendMetrics.java:43-47`） | grpcstats + serverbase 的 `rpc_inband_*` | `method` ∈ {4 个方法, `unrouted`}；`result` ∈ {ok, business_error, internal_error, overloaded, bad_request, unauthenticated, forbidden, unsupported}（同 `FriendMetrics.java:49-66`）；启动时全部预建 |
| `xm.trade.home.zone.lookups` | `trade_home_zone_lookup_total` | `result` ∈ {ok, unmapped, error} |
| `xm.trade.seed.listings` | `trade_seed_listing_total` | `result` ∈ {ok, rejected, error}（非 dev/test、参数非法、卖家无归属区记 rejected，同 `admin_logic.go:57`、`:66-77`） |
| `xm.trade.favorite.retries`（Java 增项） | — | 无标签，统计收藏写入的可重试错误重跑次数 |
| 播种接口的 HTTP 审计 | — | 审计日志行 + `xm.trade.admin.requests{op,status}`（op 只取已知路径，同 `AdminAuthFilter.java:32-36`、`:76-80`） |
| gate 已有 | `xm_gate_client_requests_total{result}`、后端调用计时（domain = trade，`ClientDispatcher.java:376-377`）、`xm_killswitch_blocked_total{method}` | — |

---

## 7 隐患与边界

### 7.1 基线自身的隐患（移植时照搬，要偏离必须登记 PARITY）

1. **`page_count` 不按 `MaxPage` 封顶**：客户端可能显示 5000 页，只能翻到 100（`phase.go:81-101`；`phase_test.go:99`）。客户端会把页码钳回（`JubaozhaiModels.cs:331`），没有错乱。
2. **过期收藏占名额且列不出来**：收藏全过期的玩家卡在 20002（§2.7）。P1 线上没有真实商品，只在 dev 能撞到。
3. **COUNT 与当页查询不在同一快照**：翻页偶尔偏短（§2.7）。
4. **收藏是软上限**（`jubaozhai_logic.go:260-268`）。
5. **种子商品逐轮累积**（P1 没有下架），dev 库会越来越大；断言一律按本轮 nonce / listing_id 找（`trade_smoke_scenario.go:872-876`）。
6. **浏览 ORDER BY 多半要 filesort**（§1.8），MaxPage 只限 OFFSET，不限匹配行数；类目必选缓解（设计稿 :245）。
7. **注释与代码不一致**：`pipeline.go:79-82` 说 `item_uuids` / `pet_id`「还没进签名串」，同文件 `:320-323` 说 2026-09-19 起已扩到 `;u=;p=`——以后者为准（只影响 P2，4.7 不涉及）。

### 7.2 Java 移植时会踩的

见 §5.9 的 10 条；另外：
- **gate 后端失败推 23**：xm-trade 一不在，客户端就等 15 s 然后隔离（§0.8、T8）。
- **单 zone 切片**：跨区可见性只能靠服务单测与 MySQL 测试覆盖（Q3）。
- **`NodeTypes.TRADE` 改值等于换键空间**（`NodeTypes.java:3-7`），上线后不要改名。
- **播种接口在 prod 也注册**：只回 403，但令牌仍是第一道闸；不要把它挂到对外端口。

### 7.3 边界速查

| 输入 | 结果 |
|---|---|
| search = `"   "` | 不搜（pattern 空），受理 |
| search = 64 个汉字 / 65 个汉字 | 受理 / 1005 |
| search = `"abc\n"` | trim 后 `"abc"`，受理（`\n` 是空白） |
| search = `"a\u001cb"` / `"\u001cabc"` | 1005（U+001C 不是 Go 空白、是控制字符） |
| search = `"007"` / `"+7"` / `"１２３"` | 按编号 7 + 标题 / 只按标题 / 只按标题 |
| search = `"%_"` | 只命中标题真含 `%_` 的 |
| tab = 0 或 3；section = 0 或 3；sort = 5；category = 0 或 10 | 1005 |
| 武器 subcategory = 6；套装 subcategory = 1 | 1005 |
| section = AUCTION（其余合法） | 20003；拍卖 + 非法 tab → 1005 |
| page = 0 / page_size = 0 | page 1 / 缺省页长 20 |
| page_size = 50 | 回显 20 |
| page = 9999 | 末页（再封顶 100） |
| ZONE 下 `zone_filter = 任意` | 忽略 |
| GLOBAL 下 `zone_filter = 0` | 全部区 |
| 详情 / 收藏 `listing_id = 0` | 1005（收藏回填 0） |
| 详情不存在的 id | 20000（不查归属区） |
| 卖家看自己已结束的商品 | 受理，phase = ENDED |
| 买家看已结束的商品 | 20000 |
| 取消收藏一个不存在的 id | 受理，`favorite = false` |
| 第 101 次收藏（前 100 条都有效或都过期） | 20002 |
| 已收藏再收藏（已满 100） | 受理 true（不判上限） |
| SeedListing `sale_duration_ms = 0` / `notice = 30 天 + 1 ms` / price = 0 / icon `A` | 1005 |

### 7.4 对分区稿的勘误

- **「Java 要复用 `MysqlSyntax.goTrimSpace`（包内可见）」**（数据层分区稿 §14 第 4 条）：已过时。`GoSpaces` 已在 xm-common（`xm-common/.../text/GoSpaces.java:13-44`，提交 `06fe1f0`）。
- **「解析先于身份检查（同 `FriendDispatcher.java:167-178`）」**（契约分区稿 §4.3）：friend 实际是先查身份（`FriendDispatcher.java:151-156`）、再在工作线程里解析（`:173-178`）。trade 建议「解析 → 身份」
  （与 grpc-go 一致），两种顺序客户端所见相同（§5.3）。
- **`PlayerTableHomeZones.java:42-69` / `:59-69`**（契约分区稿 §4.1、§4.6）：行号有误，类在 `:19-46`，`homeZones` 在 `:37-46`。
- **「客户端类目值换算在 `JubaozhaiClient.cs:299`」**（数据层分区稿 §2）：发送在 `:300-301`，接收在 `:333-336`。
- **tip 段的设计稿行号**（数据层分区稿 §9）：段定义在设计稿 :24（J-7）与 :266；P3 规划码在 :269（不是 :263 / :266）。
- **方案 A 的形状**：数据层分区稿建议「Dubbo `TradeAdminService` + xm-data HTTP 转发」，契约分区稿建议「xm-trade 管理口 HTTP」。核对后：xm-data 目前不是 Dubbo 消费方
  （仓内无 `@DubboReference`，`xm-data/pom.xml:24-28` 只为消息类型依赖 xm-api），走 xm-data 要多接一整套 Dubbo；本稿取后者为推荐（§4.8），前者列为备选 B。
- **播种请求体**（契约分区稿 §5）：用 proto3 JSON 要新用 `protobuf-java-util`（根 `pom.xml:81-85` 只管版本、无人使用），与 `tech-stack.md:25`、`:35`「JSON 一律用 Jackson」冲突；
  本稿改为 protobuf 二进制（Q2）。
- **写侧批次**：数据层分区稿说「trade 托管至少要等 4.5 之后」，契约分区稿说「等 mmorpg P3 契约、与 P3 同批」——两者不矛盾，合并为「依赖 4.5 + mmorpg P3，P2 与 P3 同批」（§4.6）。
- **Java 「category / status 必须是 enum」**（契约分区稿 §4.5）：`int32` 同样生成 `int NOT NULL DEFAULT 0`（`TableSchema.java:64`），本稿取 `int32`，更简单。
- **`admin_logic_test.go:25-120`**（契约分区稿 §7.2）：失败分支用例在 `:120-155`，范围应为 `:25-155`。

### 7.5 对 inventory / 设计稿 / 基线配置的勘误

- `docs/porting/inventory/social.md:13-14`：「Java 三块全部未开始，`ClientDispatcher.java:254`」已过时：friend、chat 已完成，gate 有按域的后端队列（`ClientDispatcher.java:258-262`、`:329-350`）；
  未接入域的分支现在在 `:313-319`。
- `social.md:28`：「会话缺失 → in-band 1005」只对无 metadata 的内部调用成立；带会话但 `player_id = 0` 是 Unauthenticated → 信封 1003（`session.go:98-100`）。
- `social.md:29`、`:367`（开放问题 1）：「可合成 xm-social + xm-trade」已被「每个服务一个进程」取代（`roadmap.md:48`）。
- `social.md:275`：「MyBatis 动态 SQL」已被 pbmysql 建表 + 手写 JDBC 取代（`roadmap.md:50-53`）。
- `social.md:293-303`（trade-favorite）：应补「卖家可以收藏自己任意状态的商品」与「过期收藏仍占名额、且列不出来」（§2.7）。
- `social.md:323`：「Java 可做成仅开发 profile 启用的 Dubbo 管理接口」——本稿推荐管理口 HTTP（§4.8）。
- `social.md:335-336`：「Java 版尚无货币 / 背包 / 宠物，也无 scene 资产账本；前置全部缺失」已过时：2.1 / 2.4 / 2.8 / 2.9 已完成（`PARITY.md:81`、`:83`）。
- `social.md:358`：合服改写已改成按清单逐条主键点更新（死锁审计 #17，`trade_step.go:11-18`）；设计稿 :81 同样过时。
- `social.md:375`（开放问题 9）：ID 已定为雪花（`architecture.md:635-646`；`PARITY.md:55`）。
- `docs/porting/inventory/contract-robot.md:612`：「Java 无 HomeZone 概念」已过时：Java 用 `player.zone_id`（`PlayerProfiles`；guild D3）。
- 设计稿 :235：P1 实际索引是 `(status,category,subcategory,price_fen)` 与 `(seller_player_id,listing_id)`（`trade_table.proto:32`），不是 `(status,sale_end_ms)` / `(seller_player_id,status)`。
- `etc/trade_smoke.yaml:21`、`robot/config/config.go:264-266`、`trade_smoke_scenario.go:139-141`：「聚宝斋号不在 MessageLimiter 表里」已过时，表里有（`messagelimiter.json:183-206`；
  设计稿 :343 也写明已配）。

---

## 8 建议的有意差异

### 8.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| T1 | 独立进程 xm-trade（Dubbo group `trade`，端口 20887 / 18111） | 每个服务一个进程（`roadmap.md:48`）；端口顺延 guild | 否 | 否 |
| T2 | 表在 `xm_java`，Java 自有 `trade_tables.proto` + xm-pbmysql；没有 `-migrate` / Job / `AutoMigrate` / 只读 plan；pbmysql 补建缺失的普通索引；4.7 只建两张表 | `roadmap.md:50-53`；同 guild D2；DDL 逐字节相同 | 否 | 否 |
| T3 | 归属区取 `player.zone_id`，代替 data_service 的 `player:zone` 映射；缺行或 0 → 20001，读失败 → in-band 1003 | Java 没有 data_service；同 guild D3、team D5 | 否 | 否 |
| T4 | `listing_id` 用雪花（`NodeTypes.TRADE`，作用域 0），代替号段 | Java 不做号段（`PARITY.md:55`；`architecture.md:635-646`） | **是**：编号位数变长 | 否 |
| T5 | 播种走 xm-trade 管理口 `POST /admin/trade/seed-listing`（令牌 + 操作人 + 审计，protobuf 二进制），代替 gRPC `TradeAdmin.SeedListing`；接口总注册、非 dev/test 回 403 | robot 不引入 Dubbo；`ClientMessageService` 没有「无会话内部调用」这条口 | 否（199 经 gate 照样无回包） | 否 |
| T6 | 会话 `player_id = 0` → 信封 1003；基线逻辑层「无会话 → 1005」在 Java 不可达（服务层仍保留判定） | 同基线 Unauthenticated → 路由服信封 1003；同 friend | 否 | 否 |
| T7 | 工作队列满、排队超预算 → in-band 1003（不带原因串） | friend 先例；与基线预算到期的 in-band 1003 同形、不隔离 | 只在过载时 | 否 |
| **T8** | **gate 对后端调用失败 / 超时 / 没有提供方，回带请求号的信封 1003**（`envelopeError`，`ClientDispatcher.java:861-868`），替代推 23 {1003}（`:389-393`）。**范围待定（Q1）** | 这是基线路由服的形状（`forwardlogic.go:147-154`、`:170-183`）；聚宝斋客户端按设计只在超时时隔离（`JubaozhaiClient.cs:16-18`；设计稿 :289），trade 是可选服务。不改的话 xm-trade 一不在，客户端就等 15 s 然后隔离 | **是**（让 Java 回到基线行为） | 否（只改 Java gate） |
| T9 | 热关停在 gate 按方法检查（Redis `xm:killswitch`），规则键与基线 etcd 键同名 | `architecture.md:407-426` | 否 | 否 |
| T10 | robot 用 run-tag 新账号；跨区步骤只在配了第二个 zone 时跑，单区用 `zone_filter` 断言补；`--trade-scope` 有缺省 `zone`（基线 `expect_scope` 必填）；两种范围各重启一次 | 同 guild D18、team D16 | 否 | 否 |
| T11 | 指标名按 Java 风格（§6.2） | `architecture.md` §11 | 否 | 否 |
| T12 | 播种开关读 `RunMode`（认 development / local / testing 等别名），基线只认字面 dev / test | `RunMode.java:36-38`；全仓统一的运行模式判据 | 否 | 否 |
| T13 | 发号租约在启动时申领、失败拒启（基线首段领不到只告警、读路径照常） | 同 guild / team；Redis 是 Java 所有进程的必备依赖（Q5） | 否 | 否 |

若 Q1 选择不改 gate，T8 改登记为「后端调用失败或超时时 gate 推 23 {1003}、不带请求号；客户端 15 s 后隔离到重连」，与 guild D9（`guild-spec.md:1721`）、team D14（`team-spec.md:1843`）同列。

### 8.2 列出但不建议采纳（或需两版同改）

- **N1**：`page_count` 按 MaxPage 封顶（`phase.go:81-101`）。客户端可见，需两版同改；收益小。
- **N2**：COUNT 与当页查询放进同一快照。基线是两条独立语句，偶发偏短可以接受。
- **N3**：收藏改成硬上限。基线有意做软上限（`jubaozhai_logic.go:260-268`）。
- **N4**：卖家不能收藏自己的商品。基线允许，不改。
- **N5**：tip 带 parameters。基线不带，客户端也不读。
- **N6**：播种时把 `seller_account` 填成 `player.account`。Java 零成本能做到，但基线写空串，P3 再定。
- **N7**：过期收藏不计入上限（或在 20002 时顺手清掉过期收藏）。改的是客户端可见的上限判定，要先改 mmorpg（Q8）。
- **N8**：现在就移植 trade 侧 P2。见 §4.6，不采纳。

### 8.3 开放问题（待用户拍板）

1. **Q1（T8）gate 后端失败的回法**：(a) 对所有非 login 后端（friend / chat / team / guild / trade）都改成带请求号的信封 1003（**推荐**：这本来就是基线路由服的行为；要同步更新 friend 行、guild D9、
   team D14 与 `architecture.md:85`，并改 `ClientDispatcherTest.java:486` 一带的断言）；(b) 只对 trade 域改；(c) 不改，登记为与 D9 / D14 同类的差异。
2. **Q2 播种方案与请求体格式**：A（xm-trade 管理口 HTTP，**推荐**）还是 B（类型化 Dubbo，经 xm-data 转发或 robot 直连）？请求体 protobuf 二进制（**推荐**）还是 JSON（要么用 Jackson 写 DTO，
   要么引入 `protobuf-java-util` 并登记 tech-stack）？
3. **Q3 跨区 robot 步骤**：本机切片要不要起第二个 zone（login 与 gate 都设 `xm.zone-id=2`）？不起的话，跨区可见性只能由服务单测与 MySQL 测试覆盖。
4. **Q4 写侧批次**：同意 trade 侧 P2 不单独移植、等 mmorpg P3 契约后与 P3 同批（**推荐**，路线图新增 4.8）？还是 4.5 之后就先移植 P2（无客户端入口、默认关闭）？
5. **Q5（T13）发号租约**：启动时申领、失败拒启（**推荐**，同 guild / team）；还是照基线只告警、读路径照常、播种时再懒申领？
6. **Q6 归属区查询件**：`HomeZones` / `PlayerTableHomeZones` 上移到 xm-common 给 guild 与 trade 共用（**推荐**），还是 xm-trade 复制一份？
7. **Q7 锁等待上限**：`innodb_lock_wait_timeout` 取 2（对齐单次 SQL 上限，**推荐**）、3（同 friend）还是 1（同 guild）？trade 只有收藏写入会等锁。
8. **Q8 过期收藏卡死 20002**：保持基线（**推荐**，P1 线上没有真实商品）还是向 mmorpg 提议两版同改（N7）？
9. **Q9 robot 的范围选项**：`--trade-scope` 给缺省 `zone`（与 `application.yaml` 一致，**推荐**）还是照基线必填？

---

## 9 测试计划

### 9.1 纯函数（`ListingRulesTest`、`TradeLimitsTest`、`TradeTipsTest`）

- **移植基线表驱动用例**（`phase_test.go`）：`Phase`（`:17-40`，含 `now == notice_end` 进寄售、`now == sale_end` 结束、LOCKED 不看时间、ESCROWING / SOLD / UNSPECIFIED → ENDED）；
  `VisibleToBuyer`（`:42-70`，含归属区 0 不可见、scope 未指定 fail-closed、LOCKED 寄售期内可见、LOCKED 过期不可见）；`ClampPageSize`（`:72-85`）；`PageWindow`（`:87-110`，含 page_count 不封顶、
  maxPage = 0）；`EscapeLike`（`:112-128`）；`ValidText`（`:130-153`）；`ValidIconKey`（`:155-172`）；`NormalizeSearch`（`:174-194`）；枚举校验（`:196-224`）；`ValidSeedRequest`（`:227-306`）。
- `maxSubcategory`（`constants_test.go:189`）。
- **Java 增项**：
  - 空白边界：U+0085、U+00A0、U+3000 被 trim；U+001C 不被 trim 且判控制字符；
  - 控制字符边界：U+001F、U+007F、U+009F 拒绝；U+00A0、U+200B 接受（不是控制字符）；
  - 64 个码点（含代理对）接受、65 个拒绝；
  - 编号搜索：`"+5"`、`"１２３"`、`"007"`、`"0"`、`"18446744073709551615"`（= uint64 上限，按编号）、`"18446744073709551616"`（溢出，只按标题）；
  - 无符号：`page_size = 0xFFFFFFFF` 钳到 20；`subcategory = 0xFFFFFFFF` → 非法；`page = 0xFFFFFFFF` 钳到末页；`total = 2^32` → `total_count = 0xFFFFFFFF`；
  - 未知枚举值（含负数）→ 非法，且不抛异常。
- `TradeTipsTest`：码值只引用生成枚举，源文件里不出现数字字面量（对应 `constants_test.go:99`）；`FAULTS = {1003}`；4 个 trade 码都在 20000 段且互不相同（`constants_test.go:50-78`）。
- **枚举对拍**：`trade_tables.proto` 里 `category` / `status` 的取值约定与契约枚举逐值相等。

### 9.2 服务与派发（假 Store、假归属区、假时钟、假发号）

- **`JubaozhaiServiceTest`**，移植 `jubaozhai_logic_test.go:316-980`：
  无会话不碰库、不查归属区（`:316`）；校验矩阵零 I/O（`:351`）；拍卖零 I/O、参数非法优先（`:394`、`:374-376`）；zone 忽略 `zone_filter`（`:407`）；global 遵守 `zone_filter`（`:423`）；
  归属区未映射 / 故障（`:439`）；分页矩阵（`:464`）；查询带上请求与时钟（`:499`）；搜索矩阵（`:525`）；摘要（`:556`）；空页不查收藏（`:587`）；存储故障 in-band（`:597`、`:800`、`:858`）；
  详情矩阵（`:617`）；取消收藏幂等且不查商品（`:696`）；加收藏七个分支（`:712-798`）；货架（`:829`）；摘要不带卖家字段（`:874`）；共用一个预算（`:923`）；预算到期 in-band 且早于 3.5 s（`:951`）。
- **`SeedListingServiceTest`**，移植 `admin_logic_test.go:25-155`：非 dev/test 拒绝且零 I/O、不消耗号；test 档放行；校验 in-band 且零 I/O；成功（行字段逐个断言，`market_zone = seller_zone_at_listing = 卖家归属区`、
  `seller_account = ""`、`version = 0`、`sale_end = notice_end + sale`）；无公示期立即寄售；归属区未映射 / 故障、发号失败 / 返回 0、插入失败；外加租约失效 → 1003。
- **`TradeDispatcherTest`**：4 个号的路由；199 → 信封 1003（forbidden）；`player_id = 0` → 信封 1003；解析失败 → 信封 1003（且先于身份判定）；队列满 / 排队超预算 → in-band 1003；处理器异常 → in-band 1003；
  1013 与 1006；启动时缺号即失败；**任何应答的 tip 都不带 parameters**；SetFavorite 服务层拒绝回填 `listing_id`；成功应答没有 `error_message`。
- **`TradePropertiesTest`**：scope 必填且合法；页长 / 上限约束；预算区间。

### 9.3 存储

- **不连库**：`buildListingFilter` / `listingOrderBy` 的 SQL 与参数逐字（`listing_repo_test.go:21-125`）；列清单 18 / 19 列且与读取顺序一致（`:140-158`）；收藏写入是 ODKU（`:126-138`）；
  DDL 与同步来的 `TradeListingRecord` / `TradeFavoriteRecord` 逐字节相同、与 §1.5 原文逐字相同（照 `GuildTablesTest`）。
- **真 MySQL**（`-Dxm.it.mysql=…`，缺省跳过，照 `JdbcFriendStoreMysqlTest.java:45-56`）：
  - 移植 `listing_repo_integration_test.go:120-247` 的全部子测：主键冲突报错（`:139`）、寄售含 LOCKED 且不含公示 / 过期 / 已售且新上架在前、列表不取 description、公示列表、分区 + 排序 + 分页、
    LIKE 通配按字面量、数字搜索、收藏全流程（幂等插入、计数、存在性、批量查、只看收藏、删除幂等）、货架任意状态、详情全列与不存在；
  - 收藏的删除标记复活不死锁（`:482`、`:529`）；`:449` 的 INSERT IGNORE 红对照可选；
  - Java 增项：大于 2^63 的无符号 `listing_id` / `player_id` 全流程（插入、按编号搜、收藏、详情）；排序决胜列保证逐页翻完不重不漏；MEDIUMTEXT 为 NULL 的行读回空串；
    `utf8mb4_unicode_ci` 下 LIKE 不区分大小写。

### 9.4 gate

- `MessageRoutesTest`：`ClientPlayerJubaozhai` 的 4 个号路由到 `trade`、`hasResponse = true`、`rpcPath = "/trade.ClientPlayerJubaozhai/<M>"`；199 `TradeAdminSeedListing` 的路由为 null。
- `ClientDispatcherTest`：199 → 计非法包、不回包；trade 域的队列与 login / scene / friend / chat / team / guild 互不阻塞；`/trade.ClientPlayerJubaozhai/*` 热关停回信封 1003；
  若采纳 T8：后端失败回带请求号的信封 1003（并更新 `:486` 一带 friend 的既有断言）。

### 9.5 播种接口

- prod → 403 且计 `rejected`、不碰库不发号；令牌错 → 401；缺操作人 → 400；令牌没配 → 503；请求体不是 `SeedListingRequest` → 400；
- 参数非法、卖家无归属区 → 200 + in-band 1005 / 20001；成功 → 200，`market_zone` 等于卖家 `player.zone_id`、`listing_id` 非 0；连调两次得到两个不同编号（不幂等）；每次调用一行审计日志。

### 9.6 robot `trade`

- 基线第 0–9 步照 §4.9 / §4.10 跑，`CheckReport` 引用 PARITY「聚宝斋只读面」行。
- **Java 增项**（钉住契约细节）：
  - search 65 字、category 0、武器子类 6、section 0、tab 0、sort 5 → 1005；拍卖 + tab 0 → 1005；
  - SetFavorite id 0 → 1005 且回填 0；详情 id 0 → 1005；收藏不存在的 id → 20000；取消不存在的 id → 受理并回 false；
  - search `"%"` 按字面量匹配（本轮种子不含 `%`，结果为空）；用 A 的编号做纯数字搜索能命中 A；
  - 已结束种子（`notice = 0`、`sale = 1 ms`）：卖家详情受理且 phase = ENDED；买家详情 20000；货架可见且 ENDED；寄售列表看不到；
  - 公示中的商品可收藏：B 收藏 AN → true；「只看收藏 + 公示页签」能看到；
  - 单区范围断言（§4.10）；
  - 经 gate 发 199 无回包，且之后同一连接照常可用（只计一次非法包）。
