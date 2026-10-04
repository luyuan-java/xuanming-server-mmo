# 好友服务（friend）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。上次契约同步之后，`go/friend` 和 `proto/friend` 都没有改动。
>
> **路径怎么读**
> - 以 `go/`、`proto/`、`cpp/`、`robot/`、`generated/` 开头的路径在 `D:\work\mmorpg` 下。
> - 以 `xm-`、`docs/`、`PARITY.md`、`AGENTS.md`、`contract/` 开头的路径在 `D:\work\xuanming-server-mmo` 下。
> - 以 `mmorpg-client/` 开头的路径在 `D:\work\mmorpg-client` 下。
> - 行号对应当前工作区。Redis 键里的 `xm:` 是键前缀，不是路径。
>
> **本稿的来历**：由三份分区规格（写路径 / 读路径与推送 / 推荐、在线目录、清理与管道）合并而成，每条结论都回到代码核对过。对三份原稿的更正见 §9.4。

---

## 0 概览与协议

### 0.1 链路与依赖

**基线链路**
- 请求路径：客户端 → C++ gate（路由模式）→ `client_rpc_router` → friend。friend 是 go-zero gRPC 服务，全服一份、多副本、进程无状态（`go/friend/etc/friend.yaml:1-2`）。
- gate 把会话身份放进 gRPC metadata `x-session-detail-bin`，值是 base64 编码的 `SessionDetails`。
  - 会话还没绑定玩家时 `player_id = 0`。
  - 只要令牌校验通过，gate 就照常转发，不管玩家是否已进游戏（`cpp/nodes/gate/handler/rpc/client_message_processor.cpp:111-132`、`:804-819`、`:858-889`）。
- 路由服原样转发请求，把任何上游 gRPC 错误都翻成**信封** `MessageContent{id, message_id, error_message{1003}}`，不带参数（`go/client_rpc_router/internal/logic/forwardlogic.go:170-183`、`:225-232`）。

**基线依赖**（`go/friend/internal/svc/servicecontext.go:52-100`）

| 依赖 | 用途 |
|---|---|
| MySQL 独占库 `mmorpg_friend` | 四张表 |
| FriendRedis（私有，可以是 Cluster） | 列表缓存、配额计数 |
| SharedRedis（只读） | `player:session:<id>`、`PlayerAllData:<id>` |
| data_service | 补名字、查 home zone |
| Kafka | `gate-cmd_g<N>` 推送 |

**Java 侧的对应**
- 入口：gate 经 Dubbo 调 `ClientMessageService.handle(ClientCall) → CompletableFuture<ClientReply>`（`xm-api/src/main/java/com/game/api/ClientMessageService.java:34-47`）。
  - Dubbo group 等于 proto 一级目录 `friend`（`xm-api/src/main/java/com/game/api/DubboGroups.java:3-13`）。
- 进程：路线图规定"每个服务一个 Spring Boot 进程模块"（`docs/porting/roadmap.md:48`），所以下文按新模块 **xm-friend** 书写。
- 表：放在 `xm_java` 库（`AGENTS.md` §3），接 pbmysql（`docs/porting/roadmap.md:50-53`）。
- 在线状态 = `xm:presence:{id}`；推送 = `PlayerPushes`（Redis pub/sub + 玩家栅栏）。见 `docs/design/architecture.md:125-144`。

### 0.2 消息号、请求 / 应答形状、gate 限频

**数据来源**
- 消息号只能取自 `MessageIdRegistry`（同步产物 `xm-proto/src/main/resources/contract/message_id.txt:3,8,12,13,120,231,233,235,236,237,239`），不要在代码里写数字。
- 形状来自 `proto/friend/friend.proto`，Java 生成类在 `com.game.proto.friend`（`docs/design/architecture.md:18-21`）。
- 限频数据在 `generated/tables/messagelimiter.json:350-410`，按会话、按消息号计。Java gate 已按同一份表限频，超频回信封 1008（`docs/design/architecture.md:351-353`；`xm-gate/src/main/java/com/game/gate/session/ClientDispatcher.java:204-211`）。

| 号 | 方法 | 方向 | 请求 | 应答 | gate 限频 |
|---|---|---|---|---|---|
| 234 | AddFriend | C2S | `AddFriendRequest{reserved 1; target_player_id=2}`（friend.proto:86-90） | `{TipInfoMessage error_message=1}`（:92-94） | 5/s |
| 238 | AcceptFriend | C2S | `{reserved 1; from_player_id=2}`（:96-100） | `{error_message=1}` | 5/s |
| 232 | RejectFriend | C2S | `{reserved 1; from_player_id=2}`（:106-110） | `{error_message=1}` | 5/s |
| 11 | RemoveFriend | C2S | `{reserved 1; target_player_id=2}`（:116-120） | `{error_message=1}` | 5/s |
| 12 | GetFriendList | C2S | `{reserved 1}`（:126-129） | `{error_message=1; repeated FriendEntry friends=2}`（:131-134） | 10/s |
| 230 | GetPendingRequests | C2S | `{reserved 1}`（:136-139） | `{error_message=1; repeated FriendRequest requests=2}`（:141-144） | 10/s |
| 7 | Block | C2S | `BlockRequest{target_player_id=1}`（**字段 1**，:152-154） | `{error_message=1}` | 5/s |
| 236 | Unblock | C2S | `{target_player_id=1}`（:160-162） | `{error_message=1}` | 5/s |
| 2 | ListBlocks | C2S | `{}`（无 reserved，:168-169） | `{error_message=1; repeated BlockEntry blocks=2}`（:177-180） | 10/s |
| 119 | RecommendFriends | C2S | `{uint32 limit=1; repeated uint64 exclude_player_ids=2; bool online_only=3; string cursor=4; string query=5}`（:189-197） | `{error_message=1; repeated RecommendEntry candidates=2; string next_cursor=3; bool online_directory=4}`（:212-217） | **1/s** |
| 235 | NotifyFriendEvent | **S2C** 推送 | `FriendEventS2C{FriendEventReason reason=1; uint64 by_player_id=2; int64 ts_ms=3}`（:228-240） | `Empty`（:266） | 表中没有这一条，缺省 3/s（`xm-gate/.../session/MessageLimit.java:13`） |

**嵌套消息**
- `FriendEntry{uint64 friend_player_id=1; int64 since_ms=2; int64 last_active_ms=3; bool is_online=4; string name=5; uint32 level=6; uint32 class_id=7; uint32 gender=8; string appearance_id=9; uint32 zone_id=10}`（:58-69）
- `FriendRequest{uint64 from_player_id=1; uint64 to_player_id=2; int64 request_time_ms=3; FriendRequestStatus status=4}`（:71-76）
  - 状态枚举：`UNKNOWN=0 / PENDING=1 / ACCEPTED=2 / REJECTED=3`（:48-53）。
- `BlockEntry{uint64 blocked_player_id=1; int64 since_ms=2}`（:172-175）
- `RecommendEntry{candidate_player_id=1; mutual_friends=2; is_online=3; last_active_ms=4; name=5; level=6; class_id=7; gender=8; appearance_id=9; zone_id=10}`（:199-210）
- `FriendEventReason{UNSPECIFIED=0; REQUEST_RECEIVED=1; REQUEST_ACCEPTED=2}`（:228-232）

**两条形状纪律**
- 成功应答**不设置** `error_message`，序列化后可以是 0 字节。
  - Java gate 按应答类型决定是否回包：只要不是 `Empty` 就一律回，哪怕 0 字节（`xm-gate/.../session/MessageRoutes.java:47-51`；`ClientDispatcher.java:336-357`）。
- 客户端判成功的方式是 `tip == null || tip.Id == 0`（`mmorpg-client/Assets/Scripts/App/DevTeamInvitationDriver.cs:374-378`；`mmorpg-client/Assets/Scripts/Game/Team/TeamInvitationDirectory.cs:270-274`）。

### 0.3 tip 码表

**好友段**（`generated/code/proto/tip/friend_error_tip.proto:12-30`；Java 同号在 `xm-table/src/main/proto/tip/friend_error_tip.proto:12-30`，Java 写法 `com.game.table.FriendErrorTip.friend_error.kFriendXxx_VALUE`）

| 码 | 枚举 | 业务含义 | 用在哪些 RPC |
|---|---|---|---|
| 15000 | kFriendCannotAddSelf | 不能加自己 | AddFriend |
| 15001 | kFriendAlreadyFriends | 已是好友 | AddFriend |
| 15002 | kFriendListFull | **我的**好友列表满 | AddFriend、AcceptFriend |
| 15003 | kFriendRequestAlreadySent | 申请已发过 | AddFriend |
| 15004 | kFriendTargetListFull | **对方的**好友列表满 | AddFriend、AcceptFriend |
| 15005 | kFriendNoPendingRequest | 没有待处理申请 | AcceptFriend、RejectFriend |
| 15006 | kFriendTooManyPending | 我的出站申请过多 | AddFriend |
| 15007 | kFriendBlocked | 存在拉黑关系（不分方向） | AddFriend、AcceptFriend |
| 15008 | kFriendBlockListFull | 黑名单满 | Block |
| 15009 | kFriendTargetInboxFull | 对方收件箱满 | AddFriend |

**通用段**（Java 写法 `CommonErrorTip.common_error.kXxx_VALUE`，见 `xm-table/src/main/proto/tip/common_error_tip.proto:18,22,24,28,32,40`）

| 码 | 枚举 | 说明 |
|---|---|---|
| 1003 | kServiceUnavailable | 本域唯一的 **fault** 码（`go/friend/internal/constants/constants.go:110-122`） |
| 1005 | kInvalidParameter | |
| 1006 | kFeatureUnavailable | Java login 对"契约里有、服务没实现"的消息号回它，friend 用不到 |
| 1008 | kRateLimitExceeded | |
| 1010 | kMessageSizeExceeded | gate 回的，见 §7.4 |
| 1014 | kRequestMessageParseError | 见 §7.4 |

**常量映射**（`go/friend/internal/constants/constants.go:47-80,90-123`）
- 好友段：`ErrCannotAddSelf=15000, ErrAlreadyFriends=15001, ErrFriendListFull=15002, ErrRequestAlreadySent=15003, ErrTargetFriendListFull=15004, ErrNoPendingRequest=15005, ErrTooManyPending=15006, ErrBlocked=15007, ErrBlockListFull=15008, ErrTargetInboxFull=15009`
- 通用段：`ErrInvalidParameter=1005, ErrRateLimited=1008, ErrStorage=1003`
- 身份缺失刻意**不用** 1012 kPlayerNotFoundInSession：它是 fault 码，会触发告警（constants.go:91-99；`go/friend/internal/session/session.go:72-75`）。

**最容易写反的一处：好友数满的两个哨兵按"角色"命名，不是按"我 / 对方"命名。** 唯一事实源是 `go/friend/internal/data/friend_repo.go:23-45`；单测 `TestFriendsFullTipsAreNotInterchangeable`（`go/friend/internal/logic/friend_logic_test.go:646`）钉住了这张表。

| data 层哨兵 | AddFriend（me 是 Sender） | AcceptFriend（me 是 Acceptor） |
|---|---|---|
| ErrSenderFriendsFull | **15002** `"your friend list full"` | **15004** `"sender's friend list full"` |
| ErrAcceptorFriendsFull | **15004** `"target's friend list full"` | **15002** `"your friend list full"` |

**`Parameters` 的写法**
- 每个 tip 都带一句固定英文，写法是 `tipErr(id, msg) = TipInfoMessage{id, parameters:[msg]}`（`go/friend/internal/logic/friend_logic.go:58-60`）。
- 客户端和 robot 只看 `Id`。Java 照抄原句，方便逐字节对拍；§2 给出每个发生点的原句。

### 0.4 配置默认值与常量

**可配置项**

| 项（基线键） | 默认 | 校验 / 说明 | 出处 |
|---|---|---|---|
| `Timeout`（zrpc 服务端整体超时） | 4000 ms | 必须在 [1000, 4000]。上限 = 路由服 ForwardTimeoutMs 5000 − 1000 | `config.go:25-36,279-282`；`friend.yaml:13` |
| `InBandReplyReserve` | 500 ms | 常量 | `config.go:38-44` |
| 整请求预算 `RequestBudget` | 3500 ms | = Timeout − 500 | `config.go:226-231` |
| `Friend.MaxFriends` | 200 | 必须 >0；硬天花板 300（`maxFriendsCeiling`），因为推荐的二度查询开销按 MaxFriends² 增长 | `config.go:150,360-365,403-419` |
| `Friend.MaxPendingRequests`（出站） | 50 | >0 | `config.go:154` |
| `Friend.MaxIncomingRequests`（入站） | 200 | >0 | `config.go:159` |
| `Friend.MaxBlocks` | 200 | >0 | `config.go:162` |
| `Friend.RecommendDefaultLimit` | 10 | >0，并且 ≤ RecommendMaxLimit | `config.go:165,350-353` |
| `Friend.RecommendMaxLimit` | 20 | >0；硬天花板 20 | `config.go:170,356-359,401` |
| `Friend.RecommendMaxExclude` | 64 | >0 | `config.go:174` |
| `Friend.RequestQuotaPerMinute` | 10 | >0；窗口固定 60 s，不可配 | `config.go:179`；`rate_quota.go:35-40` |
| `Friend.ListReadHardLimit` | 1000 | >0；同时是在线 MGET 的分批大小 | `config.go:183`；`friend_logic.go:173-174` |
| `Friend.CacheTTL` | 30m | 必须 >0（≤0 等于永不过期，不一致时无法自愈） | `config.go:185-188,366-371` |
| `Friend.Sweep.Mode` | report_only | 只能是 `report_only` 或 `delete`；空串拒启（整个 Sweep 段没写时 go-zero 不回填 default） | `config.go:204-208,373-379` |
| `Friend.Sweep.Interval` | 5m | >0 | `config.go:211,380-383` |
| `Friend.Sweep.RetentionDays` | 7 | >0；也是容量行回收的保留期 | `config.go:217,384-388` |
| `Friend.Sweep.BatchLimit` | 1000 | >0 | `config.go:223,389-393` |
| `MySQL.MaxOpenConn / MaxIdleConn` | 20 / 5 | >0 | `config.go:123-124,314-317` |

两层对 0 值的约定不一样：
- 启动校验拒绝 0（config.go:327-348）。
- 存储层与配额层仍把 **0 当作"不限"**，作为防御：`friend_repo.go:431,443,458,561-564`；`block_repo.go:79,130`；`rate_quota.go:67-71`。

**代码常量**

| 常量 | 值 | 出处 |
|---|---|---|
| 容量守卫最多跑几遍 `capacityGuardMaxAttempts` | 3 | `friend_repo.go:709-711` |
| ensure 遇 1213 最多跑几遍 `ensureDeadlockMaxAttempts` | 3 | `friend_repo.go:713-721` |
| 推送超时 `pushTimeout` | 1500 ms（从请求 ctx 派生） | `push.go:42-49` |
| Kafka WriteTimeout | 5 s | `servicecontext.go:45-49` |
| data_service 客户端 | Timeout 1500 ms，NonBlock，etcd 键 `dataservice.rpc` | `servicecontext.go:139-144` |
| 在线读 MGET 兜底分批 | 256 | `session_reader.go:49-52` |
| 在线读失败日志间隔 | 10 s | `session_reader.go:54-60` |
| 资料补全 / 在线目录 SCAN 批 | 64 | `online_directory.go:24` |
| 在线目录每页最多扫几轮 | 4 | `online_directory.go:25` |
| 在线目录单批 key 上限 | 1024 | `online_directory.go:26` |
| 在线目录 data 层页长上限（生产走不到） | 50 | `online_directory.go:27` |
| 游标最大字节数 | 48 | `online_directory.go:50` |
| 游标 offset 上限 | 1024 | `online_directory.go:61-62` |
| query 最大 rune 数 | 64 | `online_directory.go:80` |
| 在线目录配额 | 每人每 60 s 60 页，阈值写死 | `logic/online_directory.go:26,35` |
| 随机推荐的锚点窗口 `RecommendAnchorWindow` | 1024 | `recommend_repo.go:226-262` |
| sweep 单轮预算 | `min(Interval, 30s)` | `sweep.go:77-82,253-260` |
| 保留期上限 | 36500 天 | `sweep_repo.go:89-92` |
| `BatchGetPlayerName` 单次上限 | 500 个 id | `proto/data_service/data_service.proto:494-498` |

**Java 侧会碰到的现成上限**

| 项 | 值 | 出处 |
|---|---|---|
| gate 单包上限 | 整个 ClientRequest 1 KB | `ClientDispatcher.java:70-71` |
| gate 单会话排队上限 | 64，超出即断开 | `xm-gate/src/main/resources/application.yaml:55`；`ClientDispatcher.java:223-229` |
| gate 的 Dubbo consumer 超时 | 5000 ms | `application.yaml:70-73` |
| Redisson 单条命令最坏阻塞 | 4.2 s | `docs/design/architecture.md:415-417` |

Java 配置建议放在 `xm.friend.*` 下，默认值照上表，启动时校验规则也照上表。

### 0.5 全局规则（所有 RPC 适用）

1. **业务结果一律走应答体**（in-band）。handler 恒为 `return &Resp{ErrorMessage: tip}, nil`，本包里不存在 `return nil, err`（`friend_logic.go:6-13`）。
2. **依赖故障统一回 in-band 1003**，参数 `"storage unavailable"`，同时打一条 ERROR 日志 `[friend] <方法> 依赖故障: <err>`。原始错误只进日志（`friend_logic.go:229-236`）。
3. **"我"只从会话里取**，请求体里没有自己的 id（`friend.proto:25-28,80-84`；`friend_logic.go:15-17`）。目标 id 来自请求体。
4. **每个方法入口都套整请求预算**（3500 ms）。本次请求里的 MySQL、Redis、data_service、Kafka 全部共用这一个 ctx（`friend_logic.go:200-213`）。
5. **提交之后发生的任何失败都不改变 RPC 结果**。缓存失效失败、推送失败都只记日志或指标（`friend_repo.go:116-118,1008-1021`；`push.go:12-21`）。
6. **data 层遇到 id 为 0 或 a==b 时回 `errInvalidPlayerPair`**。它刻意**不是哨兵**，会落到 1003 fault（`friend_repo.go:71-79`）。所以 logic 层必须在入口挡住所有"0 / 自己"的入参，否则客户端伪造一个包就能刷出 fault 告警（`friend_logic.go:381-385,557-562`）。

---

## 1 存储模型

### 1.1 四张表

**事实源与建表方式**
- 事实源：`proto/friend/friend_table.proto`。Java 侧已有同步副本 `xm-proto/src/main/proto/proto/friend/friend_table.proto`。
- 建表约束：
  - proto2mysql 对 uint64 / uint32 一律生成 `NOT NULL DEFAULT 0`，主键列和 status 列也不例外（`go/friend/internal/data/friend_repo_mysql_test.go:330-342`）。
  - **四张表都没有 UNIQUE KEY**（`friend_table.proto:11-12`）。下面"守卫之后只用主键点锁"这条纪律以此为前提，见 §1.4 第 6 条。
- DDL 形状（测试夹具与生产一致，夹具里的索引名是自取的；`friend_repo_mysql_test.go:349-378`）：

```sql
CREATE TABLE friend (
  player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  friend_player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  since_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (player_id, friend_player_id),
  KEY (friend_player_id));                                   -- friend_table.proto:34-48

CREATE TABLE friend_request (
  from_player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  to_player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  request_time_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
  status INT UNSIGNED NOT NULL DEFAULT 0,                   -- 1 pending / 2 accepted / 3 rejected
  updated_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (from_player_id, to_player_id),
  KEY (to_player_id, status),
  KEY (status, updated_ms));                                 -- friend_table.proto:52-88

CREATE TABLE friend_capacity (
  player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  friend_count INT UNSIGNED NOT NULL DEFAULT 0,
  created_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (player_id),
  KEY (friend_count, created_ms));                           -- friend_table.proto:93-129

CREATE TABLE friend_block (
  player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  blocked_player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  since_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (player_id, blocked_player_id),
  KEY (blocked_player_id));                                  -- friend_table.proto:133-145
```

- status 的常量定义：`friend_repo.go:85-89`。
- `(friend_count, created_ms)` 这条索引只在 CREATE TABLE 时建出。已存在的表重跑迁移不会补建，必须手工 ALTER，否则回收的候选读每轮都会全表扫（`friend_table.proto:102-108`；`sweep_repo.go:33-38`）。

**列的语义（以代码为准，proto 注释里有过时的地方，见 §9.3）**

- **`friend` 表**
  - 一对好友存成双向两行，同一事务里同增同减（`friend_table.proto:31-33`）。
  - `since_ms` 由 AcceptFriend 在拿到守卫的那一遍里取一次 now，两个方向共用（`friend_repo.go:573,602-606`）。
  - 写入用 `INSERT IGNORE`：已存在的方向保留原 since_ms，也不重复计数。

- **`friend_request` 表**
  - 每对 (from, to) 至多一行。
  - `request_time_ms` 只有 AddFriend 的 upsert 写它。复用终态行重新申请时**刷新**成本次时刻（`friend_repo.go:467-480`）。
  - `updated_ms` 表示"状态最后一次变更的时刻"。凡是改 status 的语句都必须**在同一句里**写它，共五处（`friend_table.proto:70-87`）：
    1. Add 的 upsert（`friend_repo.go:476-480`）
    2. Accept 的正向 UPDATE（`:577-579`）
    3. Accept 的反向 UPDATE（`:595-597`）
    4. Reject 的 CAS（`:643-645`）
    5. Block 取消两个方向的 pending（`block_repo.go:175-180,282`）
  - 漏写 updated_ms 会让这一行停在 0，sweep 在 delete 模式下会把它当成早已过期。

- **`friend_capacity` 表**
  - `friend_count` 是权威好友数，必须与 `friend` 表的边数在同一事务里同增同减（`friend_table.proto:114`）。
    - +1 只发生在 Accept 真插入一条边时（`friend_repo.go:616-621`）。
    - −1 只发生在 `deleteFriendEdges` 真删掉一条边时（`:945-955`）。
  - `created_ms` 表示"本行被（重新）建出、或最近一次 friend_count 减少的时刻"，回收的保留期从这一刻起算。写它的只有两处（`friend_repo.go:1039-1046`）：
    1. ensure 的 INSERT；行已存在时 ODKU 什么都不改，**不刷新**。
    2. 减计数的那条 UPDATE。
  - 加 1 时不刷新 created_ms。
  - 减计数时为什么也要刷新：ensure 的 COUNT 和 INSERT 是两条各自自动提交的语句，并不原子。不刷新的话，可能出现这样的交错（`friend_repo.go:180-186`）：
    1. ensure 读到 COUNT=1；
    2. RemoveFriend 提交，计数行变成 0；
    3. 回收删掉这一行；
    4. 陈旧的 INSERT (P, 1) 落地。
    结果计数被永久写大 1：上限少 1、零报错、无法自愈。

- **`friend_block` 表**
  - 单向关系。
  - `since_ms` 由 Block 在闭包里取一次。重复拉黑时 ODKU 什么都不改，不刷新（`block_repo.go:106-109,143-153`）。

### 1.2 各 RPC 的事务形态

| RPC | 事务形态 | 事务外前置 | 会不会补容量行（ensure） |
|---|---|---|---|
| AddFriend | 守卫事务（RC） | 只有 Redis 频控 | **一定会**，双方都补；目标 id 不存在也补；ensure 已自动提交，即使事务因 15007 等原因回滚也已补上（`friend_repo.go:363-368`） |
| AcceptFriend | 守卫事务 | 普通读，确认 pending 存在（`friend_repo.go:500-510`） | 只在 pending 存在时补 |
| RejectFriend | 单条自动提交的 UPDATE（CAS） | — | 不补 |
| RemoveFriend | 守卫事务 | 普通读，确认好友边存在（`friend_repo.go:669-685`） | 只在边存在时补 |
| Block | 守卫事务 | 普通读，查名额（`block_repo.go:64-94`） | 只在名额未满或已经拉黑过时补 |
| Unblock | 单条自动提交的 DELETE | — | 不补 |
| 三个列表读、推荐 | 自动提交的普通读，不加锁 | — | 不补 |
| sweep | 候选普通读 + 逐行自动提交 DELETE | — | — |

### 1.3 守卫事务骨架 `runGuardedWrite`

Add / Accept / Remove / Block 四条写路径共用唯一一份骨架：`friend_repo.go:101-117`（总说明）、`:751-797`（实现）。

```
[事务外] 各方法自己的前置判定（普通读，不随重试重跑）
runGuardedWrite(a, b, body)：至多 3 遍
  runGuardedWriteOnce：
    ① ensureFriendCapacityRows(a, b)
       事务外；按 player_id 升序；逐个玩家自动提交
    ② BeginTx(READ COMMITTED)                          friend_repo.go:799-802
    ③ lockCapacityRows(a, b)
       升序逐个执行 SELECT friend_count FROM friend_capacity WHERE player_id=? FOR UPDATE
       → 得到 counts map
    ④ body(tx, counts)
    ⑤ Commit
    每一遍结束都 defer Rollback（Commit 之后再 Rollback 是无害的空操作）
[提交后] invalidateCachesAfterCommit(keys...)；推送由 logic 层在 repo 返回 nil 之后做
```

**重试规则**
- **只有**守卫缺行（`errCapacityRowsMissing`，用 `%w` 包装）会让整遍重来：回到事务外重新 ensure，再跑一遍（`friend_repo.go:751-770`）。
  - 第 1、2 遍缺行时打一条 `[friend] WARN 容量守卫缺行(多半是 sweep 回收竞态)...` 日志（`:759-765`）。
  - 第 3 遍仍缺行，就把哨兵原样上抛，logic 定性为 1003（fail-closed）。
- **为什么 3 遍严格够用**（`friend_repo.go:153-166`）：
  - 一次写至多涉及两行守卫行；
  - 回收是这张表唯一的删除方；
  - 刚被 ensure 重建的行 created_ms 是当前时刻，不可能再被回收；
  - 所以每行至多被删一次。前提是各副本墙钟偏差小于 RetentionDays。
- **重跑是安全的**：缺行只可能出在事务的第一条语句（守卫），此时 body 还没执行、事务没有副作用（`:742-747`）。
- **body 里的任何错误都原样上抛、不重试**，包括 1213 和各类哨兵。事务内的一次 1213 就是一次玩家可见的 1003（`:743-745`）。
- **body 的约束**：一次 runGuardedWrite 里至多执行一次。但 body 返回 nil 之后 Commit 仍可能失败，所以 body 里只许有本事务之内的副作用（`:727-733`）。

**守卫缺行的判定**
- 遇到第一个缺行就返回，后面的行不再锁（`friend_repo.go:819-833`）。
- **缺行绝不能当作 0 继续写**，否则好友硬上限会被凭空放宽一轮（`:804-815`）。
- Java 注意：MyBatis 返回 `Integer null` 时必须识别为缺行，不许落成 0。

**ensure 的实现**（`friend_repo.go:1052-1109`）
- 对每个玩家（`ascendingUniqueIDs` 升序、去重；超过两个 id 时只取前两个，见 `:970-989`），执行两条各自自动提交的语句：

```sql
SELECT COUNT(*) FROM friend WHERE player_id = ?            -- 普通一致性读，得到权威边数
INSERT INTO friend_capacity (player_id, friend_count, created_ms) VALUES (?, ?, now)
  ON DUPLICATE KEY UPDATE player_id = player_id             -- 行已存在时空操作；常量见 :848-851
```

- **初值绝不猜 0**，只能取 friend 表的权威边数（`:1094-1098`）。回归用例：`TestMissingCapacityRowUsesAuthoritativeFriendCount`（`friend_repo_mysql_test.go:856`）、`TestGuardedWrites_SucceedAfterCapacityRowsWereReclaimed`（`:1072`）。
- **用 ODKU 而不用 INSERT IGNORE**（`:1085-1092`）。主键是回收留下、尚未 purge 的 delete-marked 记录时：
  - INSERT IGNORE 做重复键检查取 **S** 锁。两个排队的 ensure 先都拿到 S、再都要升 X，就会互等成 1213（真库复现过）。
  - ODKU 直接取 **X**，等待者只是排队，成不了环。
- **1213 纵深防御**：只认错误号 1213（`isMySQLDeadlock`，`:1111-1117`，不做文本匹配）。
  - 对单个玩家把 "COUNT + INSERT" **整对**重跑，至多 3 遍（`:1055-1076`）。COUNT 必须一起重跑，否则写下去的是陈旧边数。
  - 每遍之前先检查 ctx 是否已结束（`:1056-1063`）。
  - 进程内计数 `ensureDeadlockRetries` 在正常运行时应恒为 0（`:723-725`）。
  - 用尽后原样上抛，logic 定性为 1003。

### 1.4 锁序与 SQL 纪律

改任何写路径之前必须读完 `friend_repo.go:101-224`。

1. **ensure 必须在事务外**（`friend_repo.go:122-123`）。放进事务的话，多个事务各持有自己刚插入的新行、又都去抢同一个接收者的行，会形成 insert-intention 死锁。
2. **容量守卫必须是事务里的第一把锁**（`:125-129`）。拿到守卫之前不做任何锁定读：未命中的锁定读锁的是间隙，间隙是跨玩家对共享的。
3. **AddFriend 与 AcceptFriend 曾互为 ABBA**（`:131-140`）。裁定是全局统一"守卫最先"：Accept 对申请行的 `FOR UPDATE` 已经下移到守卫之后。回归用例：`TestAddFriendAndAcceptFriendOnSamePair`（`friend_guard_lock_order_mysql_test.go:286`）。
4. **守卫行就是"这一对玩家"的串行化载体**（`:142-145`）。Add / Accept / Remove / Block 锁的是同一对容量行，所以两两互斥。RC 下探针自己挡不住并发插入，挡住并发的是守卫。**新增任何写路径都必须先拿守卫。**
5. **回收逐行、自动提交、按主键删**。任一时刻至多持有一把守卫锁，持锁时不再等别的锁，所以进不了等待环。不许把回收改成一条批量 `DELETE ... LIMIT`：那样会按二级索引序锁多行，与写路径的升序取锁反向（`:147-186`）。
6. **守卫之后的锁定读 / 锁定写，一律写成完整主键的等值点查 / 点更新**，不许用 OR / IN / 前缀范围（`:188-207`）。
   - 真库实证过三种坏情况：
     - OR 形的锁定读被规划成二级覆盖索引全扫描，锁到别的玩家对上，导致 1213；
     - `IN(...) FOR UPDATE` 被规划成 PRIMARY 全索引扫描，等于按玩家号把写路径串行化；
     - Block 的 OR 形 UPDATE 走 status 前缀，扫全服 pending 行。
   - 前提是这四张表**没有 UNIQUE KEY**；将来新增唯一键必须回来重新核对。
   - 确定性回归：`TestLockingStatementsArePrimaryKeyPointLookups`（`friend_guard_lock_order_mysql_test.go:1073`）对 5 条生产 SQL 常量逐条 EXPLAIN，要求：
     - `key=PRIMARY`；
     - `key_len` 用满主键（单列 8，双列 16）；
     - SELECT 的 access type 为 `const`，UPDATE / DELETE 为 `range` 或 `const`。

**隔离级别：READ COMMITTED**（`friend_repo.go:209-229`）
- 事务用 `BeginTx(RC)`。
- **连接池级别也设成 RC**。事务外的自动提交语句（ensure、Unblock 的 DELETE、Reject 的 UPDATE、sweep）不经过 BeginTx，隔离级别来自连接会话。基线 DSN 带 `transaction_isolation='READ-COMMITTED'` 和 `sql_mode='STRICT_TRANS_TABLES'`（`go/friend/internal/svc/servicecontext.go:170-193`）。
- 为什么不用 RR：RR 的间隙锁会让"同一玩家并发拉黑 16 个不同目标"这类只碰不同行的事务成环。
- RC 的代价：同一事务内每条语句各取一份新快照，所以判定读必须在守卫之后。
- 前提：`binlog_format=ROW`（`:221-224`）。

**事务内的取锁序列**

守卫之后都只锁本对玩家的主键行。同一对玩家的事务已经被守卫串行化，所以下面这些锁的先后**不构成**跨事务环。不拿守卫的写者只有 Unblock、Reject、sweep，它们都是"单条、按主键、持一把锁、不等别的锁"。

| RPC | 守卫（升序两行）之后依次执行 |
|---|---|
| AddFriend | `block(from,to)` FOR UPDATE → `block(to,from)` FOR UPDATE → `friend(from,to)` FOR UPDATE → `friend(to,from)` FOR UPDATE → `friend_request(from,to)` FOR UPDATE → [出站 COUNT，**普通读**] → [入站 COUNT，**普通读**] → upsert `friend_request(from,to)` |
| AcceptFriend | `block(from,to)` → `block(to,from)` → `friend_request(from,to)` FOR UPDATE → UPDATE (from,to) → UPDATE (to,from) → INSERT IGNORE `friend(from,to)` → [插入了才执行] capacity(from)+1 → INSERT IGNORE `friend(to,from)` → [插入了才执行] capacity(to)+1 |
| RemoveFriend | DELETE `friend(me,t)` → [删到了才执行] capacity(me)−1 → DELETE `friend(t,me)` → [删到了才执行] capacity(t)−1 |
| Block | `friend_block(me,t)` FOR UPDATE → [未命中时] COUNT（普通读）→ ODKU 插入 `friend_block(me,t)` → 与 Remove 相同的删边 / 减计数序列 → UPDATE `friend_request(me,t)` → UPDATE `friend_request(t,me)` |

`docs/porting/inventory/social.md:95` 写的"容量行 → 拉黑行 → 好友边 → 申请行"对 Accept、Block 不成立（Accept 先锁申请行、后写好友边）。真正的不变量是"**守卫最先 + 守卫之后只用主键点锁**"。

### 1.5 SQL 全目录

| # | 语句 | 用在哪里 | 锁 / 事务 | 出处 |
|---|---|---|---|---|
| S1 | `SELECT COUNT(*) FROM friend WHERE player_id = ?` | ensure | 自动提交，普通读 | friend_repo.go:1100-1101 |
| S2 | `INSERT INTO friend_capacity (player_id, friend_count, created_ms) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE player_id = player_id` | ensure | 自动提交，X 锁 | :850-851,1104-1105 |
| S3 | `SELECT friend_count FROM friend_capacity WHERE player_id = ? FOR UPDATE` | 守卫 | 事务内 | :843 |
| S4 | `SELECT 1 FROM friend_block WHERE player_id = ? AND blocked_player_id = ? FOR UPDATE` | Add② / Accept②（双向两次）、Block②（单向，block_repo.go:266-268 内联同形） | 事务内 | :844,875-886 |
| S5 | `SELECT 1 FROM friend WHERE player_id = ? AND friend_player_id = ? FOR UPDATE` | Add③（双向两次） | 事务内 | :845,891-902 |
| S6 | `SELECT status FROM friend_request WHERE from_player_id=? AND to_player_id=? FOR UPDATE` | Add④、Accept③ | 事务内 | :397-399,549-551 |
| S7 | `SELECT COUNT(*) FROM friend_request WHERE from_player_id=? AND status=?`（status=1） | Add⑤ | 事务内**普通读** | :433-435 |
| S8 | `SELECT COUNT(*) FROM friend_request WHERE to_player_id=? AND status=?`（status=1） | Add⑥ | 事务内**普通读** | :445-447 |
| S9 | `INSERT INTO friend_request (from_player_id, to_player_id, request_time_ms, status, updated_ms) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE status=VALUES(status), request_time_ms=VALUES(request_time_ms), updated_ms=VALUES(updated_ms)` | Add⑧；用 VALUES() 是为了 TiDB 可移植 | 事务内 | :467-482 |
| S10 | `SELECT 1 FROM friend_request WHERE from_player_id=? AND to_player_id=? AND status=?`（1） | Accept 事务外前置 | 自动提交 | :503-505 |
| S11 | `UPDATE friend_request SET status=?, updated_ms=? WHERE from_player_id=? AND to_player_id=? AND status=?`（2, now, from, to, 1） | Accept⑤，`RowsAffected != 1` 时拒 | 事务内 | :577-589 |
| S12 | 同 S11，参数为 (2, now, **to, from**, 1) | Accept⑥，不检查 RowsAffected | 事务内 | :595-599 |
| S13 | `INSERT IGNORE INTO friend (player_id, friend_player_id, since_ms) VALUES (?, ?, ?)` | Accept⑦；>1 行时报错 | 事务内 | :603-615 |
| S14 | `UPDATE friend_capacity SET friend_count = friend_count + 1 WHERE player_id = ?` | Accept⑦，只在 S13 插入 1 行时执行 | 事务内 | :617-619 |
| S15 | `UPDATE friend_request SET status=?, updated_ms=? WHERE from_player_id=? AND to_player_id=? AND status=?`（3, now, from, me, 1） | Reject；0 行 → 15005 | 自动提交 | :643-655 |
| S16 | `SELECT 1 FROM friend WHERE (player_id=? AND friend_player_id=?) OR (player_id=? AND friend_player_id=?) LIMIT 1` | Remove 事务外前置（普通读，OR 在这里无锁所以允许） | 自动提交 | :905-918 |
| S17 | `DELETE FROM friend WHERE player_id=? AND friend_player_id=?` | Remove / Block 删边 | 事务内 | :936-937 |
| S18 | `UPDATE friend_capacity SET friend_count = friend_count - 1, created_ms = ? WHERE player_id = ? AND friend_count > 0` | 只在 S17 删到 1 行时执行；0 行 → ERROR 日志，**不回滚** | 事务内 | :945-964 |
| S19 | `SELECT COUNT(*), COUNT(CASE WHEN blocked_player_id = ? THEN 1 END) FROM friend_block WHERE player_id = ?`（参数 target, me） | Block 事务外前置 | 自动提交 | block_repo.go:79-94 |
| S20 | `SELECT COUNT(*) FROM friend_block WHERE player_id=?` | Block③ | 事务内**普通读** | :130-141 |
| S21 | `INSERT INTO friend_block (player_id, blocked_player_id, since_ms) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE player_id = player_id` | Block④ | 事务内 | :33-35,143-153 |
| S22 | `UPDATE friend_request SET status = ?, updated_ms = ? WHERE from_player_id = ? AND to_player_id = ? AND status = ?`（3, now, a, b, 1） | Block⑥，两个方向各一次；不许合成一条 OR | 事务内 | :168-180,278-282 |
| S23 | `DELETE FROM friend_block WHERE player_id=? AND blocked_player_id=?` | Unblock | 自动提交 | :213-215 |
| S24 | `SELECT friend_player_id, since_ms FROM friend WHERE player_id = ? LIMIT ?` | GetFriendList 回源 | 自动提交 | friend_repo.go:1226-1252 |
| S25 | `SELECT from_player_id, to_player_id, request_time_ms, status FROM friend_request WHERE to_player_id = ? AND status = ? LIMIT ?` | GetPendingRequests 回源 | 自动提交 | :1254-1279 |
| S26 | `SELECT blocked_player_id, since_ms FROM friend_block WHERE player_id=? LIMIT ?` | ListBlocks | 自动提交 | block_repo.go:235-256 |
| S27 | mutual 推荐 SQL | 推荐 | 自动提交 | 见 §5.1 |
| S28 | `SELECT MIN(player_id), MAX(player_id) FROM friend` 与锚点 SQL | 推荐 | 自动提交 | 见 §5.1 |
| S29 | 终态申请清理 | sweep | 自动提交 | 见 §6 |
| S30 | 零好友容量行回收 | sweep | 自动提交 | 见 §6 |

### 1.6 并发不变量与回归场景（Java 测试必须钉住）

**四条不变量**（`friend_repo_mysql_test.go:221-294`，`assertFriendInvariants`）
1. 每个容量行的 friend_count 等于该玩家在 friend 表里的边数。
2. 不存在"既是好友又有任一方向拉黑"的一对。
3. 有拉黑关系的一对之间不残留 pending。
4. 已是好友的一对之间不残留 pending。

**锁序场景**（全程不得出现 1213；场景表见 `friend_guard_lock_order_mysql_test.go:39-56`）

| 编号 | 场景 |
|---|---|
| (a) | 16 个申请人 → 同一个 target |
| (a') | 16 个申请人 → 各自不同的 target |
| (b) | 同一玩家并发拉黑 16 个目标 |
| (c) | 同一对玩家的 Block 与 Accept 交错 |
| (d) | 同一对玩家的 Add 与 Accept |
| (e) | 预置交叉 pending，两个不相干的 pair 并发 Add（`:403`） |
| (f) | 回收与四条写路径并发（`:528`） |
| (g) | 回收的 DELETE 压着 X 锁时，两个 ensure 排队（`:753`） |
| (h) | Unblock 压着 X 锁时，Block④ 与另一个 Unblock 排队（`:975`） |

**业务上限场景**

| 用例 | 出处 |
|---|---|
| `TestAcceptFriend_ConcurrentHardLimit` | friend_repo_mysql_test.go:467 |
| 出站 / 入站 / 双方好友数上限都是权威判定 | :555, :586, :606 |
| 拉黑两个方向都拒；重复申请 / 已是好友 | :629, :649 |
| Add 写 updated_ms；Accept 收敛反向 pending；Accept 遇拉黑拒绝 | :670, :700, :730 |
| `TestRunGuardedWrite_ExhaustedMissingRowsFailClosed`：重试用尽后 fail-closed，且 body 一次都没执行 | :1152 |
| created_ms 只写一次、减计数时刷新 | :906, :956 |
| Block 的幂等 / 名额 / 删边减计数 / 两个方向取消 pending / 缓存失效 / Unblock 不恢复好友 | block_repo_mysql_test.go:24-213 |

### 1.7 Java 存储层落地

**表与建表**
- 表放 xm_java，表名、列、主键、二级索引、"无唯一键"都与 §1.1 一致。
- 走 pbmysql（`docs/porting/roadmap.md:50-53`）。在建的 `xm-pbmysql` 模块的选项号与 `proto/db/proto_option.proto` 一致（`OptionTableName = 500001` 等，见 `xm-pbmysql/src/main/java/com/game/pbmysql/DescriptorOptions.java:31-55`），可以直接读同步来的 `friend_table.proto`。
- 但这些 proto 属于契约同步产物，不许手改；是否让 Java 存储结构直接依赖它是待决项（§10.3）。

**连接池**
- friend 进程用**自己的 Druid 池**，会话级 RC：
  - `default-transaction-isolation: 2`，或在 URL 里加 `sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=3`；
  - 同时加 `useAffectedRows=true`，与 xm-data 一致（`xm-data/src/main/resources/application.yaml:14-15`）。
- login 那边是 RR（`xm-player-store/.../PlayerStore.java:154-166`），不能共用。
- 本稿用到的 UPDATE 都会真改值，在 found-rows 下结果也一样，但没必要留这个坑。

**事务实现**
- `runGuardedWrite` 必须是一个**非事务**方法：
  - ensure 走自动提交语句，**不许**被外层 `@Transactional` 吞进同一个事务，否则违反锁序第 1 条；
  - 再用编程式 `TransactionTemplate`（`ISOLATION_READ_COMMITTED`）执行"守卫 → body"；
  - 守卫缺行时抛专用异常回滚，外层循环重试，至多 3 遍；
  - 业务拒绝用枚举结果加回滚，不靠异常文本。
- Spring 的事务 timeout 粒度是**秒**。预算控制改用 JDBC `queryTimeout`、`socketTimeout` 和会话 `innodb_lock_wait_timeout`（§7.3）。

**分层**（领域对象 + XxxService，不用 ECS 命名）
- `FriendService`：入口校验、频控、tip 映射、推送。
- `FriendStore`：守卫事务与 SQL。业务拒绝返回**枚举**，依赖故障抛异常、上层定性为 1003。
  - Sender / Acceptor 两个"好友数满"保留成两个枚举值，在 Add / Accept 两处**分别**映射。
  - 0 / self 入参由 store 抛 `IllegalArgumentException`，上层定性为 1003 并打 ERROR 日志；logic 层保证客户端触发不到它。

**uint64 与 Java long**（见 §9.1 第 25 条）
- 所有 id 都要按无符号处理：
  - 绑定参数用 `Long.toUnsignedString` 或 `BigInteger`，或者写一个 MyBatis TypeHandler；
  - 排序用 `Long.compareUnsigned`；
  - 读 BIGINT UNSIGNED 列时按无符号解析。

**1213 识别**
- 只认 `SQLException.getErrorCode() == 1213`，沿 cause 链向下找。

---

## 2 每个 RPC 的处理流程

**总表**

| RPC | 入口校验 → 限流 | 事务 | 提交后失效的缓存键 | 推送 |
|---|---|---|---|---|
| AddFriend（234） | 身份 → target=0 → self → **每分钟配额** | 守卫 | `req(target)` | REQUEST_RECEIVED 推给 target，by=me |
| AcceptFriend（238） | 身份 → from=0 → self | 前置 + 守卫 | `list(from)`、`list(me)`、`req(me)`、`req(from)` | REQUEST_ACCEPTED 推给 from，by=me |
| RejectFriend（232） | 身份 → from=0 → self | 单条 CAS | `req(me)`（只在成功时） | 无（刻意） |
| RemoveFriend（11） | 身份 → target=0 | 前置 + 守卫 | `list(me)`、`list(target)`（只在边存在时） | 无 |
| Block（7） | 身份 → target=0 或 self | 前置 + 守卫 | `list(me)`、`list(t)`、`req(me)`、`req(t)`（幂等命中时也失效） | 无 |
| Unblock（236） | 身份 → target=0 或 self | 单条 DELETE | 无 | 无 |
| GetFriendList（12） | 身份 | 读缓存 / DB + 在线 + 资料 | — | — |
| GetPendingRequests（230） | 身份 | 读缓存 / DB | — | — |
| ListBlocks（2） | 身份 | 直读 DB | — | — |
| RecommendFriends（119） | 身份 → exclude>64 →（在线目录才有：输入校验 → 每分钟 60 页） | 纯读 | — | — |

除 AddFriend 和在线目录之外，其余方法唯一的频率限制就是 gate 的 MessageLimiter（§0.2）。

**"缺会话身份"的两种形态**
- 下面各 RPC 第 1 步写的 in-band 1005 `"missing session identity"`，只对"不带会话元数据的内部直连"可达（`friend_logic.go:215-227`）。
- 真实客户端在会话 `player_id=0`（还没进游戏）时，会被会话拦截器判为坏头，返回 `Unauthenticated`，客户端看到的是**信封 1003**（§7.1）。

### 2.1 AddFriend（234）

代码：`friend_logic.go:250-316`、`friend_repo.go:357-493`、`rate_quota.go`。

**入口校验，严格按此顺序，全部在任何 I/O 之前**

| 步 | 条件 | 结果 | 出处 |
|---|---|---|---|
| 0 | 套 3500 ms 预算 | — | :252-253 |
| 1 | 取不到会话身份 | 1005 `"missing session identity"` | :258-261 |
| 2 | `target_player_id == 0` | 1005 `"target_player_id required"` | :262-267 |
| 3 | `target == me` | **15000** `"cannot add yourself"` | :268-270 |
| 4 | 频控超出 | **1008** `"too many friend requests, retry later"` | :271-275 |

- 第 2 步必须在第 3 步之前：身份改从会话取之后 me 永远非 0，"目标为 0"不再顺带被"加自己"挡住（单测 `TestAddFriendRejectsSelfAndZeroTarget`，friend_logic_test.go:451）。
- 第 2、3 步不消耗配额。
- logic 层**不做任何事务外业务预检**（:277-278）。

**第 4 步：频控**（`rate_quota.go:61-107`）
- 必须在一切副作用之前。它限的是**尝试**次数：之后因 15003 / 15007 等被拒、甚至存储故障的那一次，也已经消耗了配额（单测 `TestRequestQuotaRejectsAfterLimit`，friend_logic_test.go:812）。
- 键：FriendRedis 上的 `friend:{rq:<pid>}`（:53-59）。
- 脚本（必须是一条 Lua，:42-51；ARGV[1] = `"60"`，:81-82）：

```lua
local n = redis.call("INCR", KEYS[1])
if n == 1 or redis.call("TTL", KEYS[1]) == -1 then
  redis.call("EXPIRE", KEYS[1], ARGV[1])
end
return n
```

  - 分两次调用 INCR 和 EXPIRE 的话，中间崩溃会留下永不过期的计数器，该玩家从此永远发不出申请。
  - `TTL == -1` 分支用来自愈存量的无 TTL 计数器。
- 窗口是固定窗口，不是滑动窗口。窗口交界处最坏能放过 2×阈值（:35-40）。
- 判定：`count > limit` 才拒（第 10 次恰好用完、仍放行），指标计 `rejected`；放行计 `allowed`（:98-106）。
- **fail-open**（放行并计 `error`）的情形：FriendRedis 句柄为 nil（:72-79）、EVAL 出错（:81-87）、返回值不是 int64（:88-96）。
- `limit == 0` 也放行，但**不计指标**（:66-71）。
- 为什么 fail-open：配额是防刷不是防作弊，硬上限都在 MySQL 里 fail-closed；Redis 抖一下就让全服加不了好友，代价不可接受（:10-21）。

**事务**：`runGuardedWrite(me, target)`，ensure 会补**双方**的容量行。body 内严格按此顺序：

| 步 | 动作 | 失败结果 | 出处 |
|---|---|---|---|
| ② | `blockedEitherWay(me, target)`：两次 S4，先 (me,t) 后 (t,me)，命中即停 | **15007** `"cannot send friend request"`。两个方向同码、文案中性，不泄露是谁拉黑了谁（friend_logic.go:287-290） | friend_repo.go:374-382 |
| ③ | `friendEdgeExistsForUpdate`：两次 S5；任一方向有边即算 | **15001** `"already friends"`。单边残留也拒，不靠再发申请去修 | :384-392 |
| ④ | S6：行存在且 status==1 → 拒；行是终态（2/3）或不存在 → 继续；其他错误 → 1003 | **15003** `"request already sent"`。**不刷新时间**，防止反复点击把自己顶到对方列表最前面 | :394-410 |
| ⑤ | 仅当 MaxPendingRequests>0：S7 普通读，`outgoing >= MaxPendingRequests` 时拒 | **15006** `"too many pending friend requests"` | :431-442 |
| ⑥ | 仅当 MaxIncomingRequests>0：S8 普通读，`incoming >= MaxIncomingRequests` 时拒 | **15009** `"target inbox full"` | :443-453 |
| ⑦ | 仅当 MaxFriends>0，用守卫读回的 counts：先判 `counts[me] >= Max`，再判 `counts[target] >= Max` | 前者 **15002** `"your friend list full"`，后者 **15004** `"target's friend list full"`（friend_logic.go:303-306）。双方都查：一条注定失败的申请挂着，比当场拒更糟 | :455-465 |
| ⑧ | `now` 在闭包里取一次；执行 S9 upsert | 出错 → 1003 | :467-482 |

- **⑤⑥ 刻意用普通读、不加 FOR UPDATE**（`friend_repo.go:411-430`）。
  - 为什么普通读在这里就是权威值：RC 下每条语句都取新快照；能让 pending 计数**变大**的语句全仓只有 ⑧，而它必须先持有对应玩家的守卫行，我们此刻正握着。
  - 为什么加 FOR UPDATE 反而会死锁：两条 COUNT 的锁集分别是"from=A 的行"和"to=T 的行"，会跨玩家对交叉，导致 1213，按 player_id 排序也解不掉。
  - 由此得到一条**必须被后来者保住的不变量**：任何会让 pending 计数增加的写路径，都必须先持有对应玩家的守卫行。

**提交后**
1. 只失效 `req(target)`（`friend_repo.go:489-491`）。申请行落在接收者的收件箱里，申请人这边没有出站列表的缓存视图。
2. **然后**推送 REQUEST_RECEIVED 给 target，by=me（`friend_logic.go:311-313`）。
3. 打 Info 日志，回空应答（:314-315）。

**边界**
- 对方已经向我申请过：**不会**自动成为好友，双方各挂一条 pending。之后任一方 Accept 时，反向那条会被一并收敛（§2.2 ⑥）。
- 目标 id 不存在：照样成功，并建出双方的容量行。
- 被拒之后重新申请：复用终态行，request_time_ms 刷新。
- 出站 pending 永不过期（sweep 只清终态行），也没有撤回接口。发起方要消掉自己的出站 pending，唯一途径是 Block（会把两个方向的 pending 置 rejected），之后再 Unblock。
- 检查优先级：拉黑 > 已是好友 > 已申请 > 出站满 > 入站满 > 我满 > 对方满。

### 2.2 AcceptFriend（238）

代码：`friend_logic.go:318-363`、`friend_repo.go:495-632`。

**入口校验**

| 步 | 条件 | 结果 |
|---|---|---|
| 1 | 取不到会话身份 | 1005 `"missing session identity"` |
| 2 | `from_player_id == 0` | 1005 `"from_player_id required"`（:330-335）。不挡的话会被伪装成 15005 |
| 3 | `from == me` | 1005 `"cannot accept your own request"`（:336-340）。**不是** 15000 |

- 没有业务频控。
- repo 的参数顺序是 `(from, me)`：接受者永远是会话里的 me，不能由请求体指定，否则客户端可以替别人通过申请（:342-344）。

**事务外前置**：S10 普通读（`friend_repo.go:500-510`）
- 无行 → **15005** `"no pending friend request"`，并且**不 ensure**。目的是防止无申请的调用制造容量空行。
- 其他错误 → 1003。
- 这一步不随守卫缺行的重试重跑；重跑那一遍由事务内的 ③ 兜住。

**事务**：`runGuardedWrite(from, me)`。body 内顺序：

| 步 | 动作 | 失败结果 | 出处 |
|---|---|---|---|
| ② | `blockedEitherWay(from, me)`。没有这一步，"Block 删边"与"Accept 插边"可以交错出"既是好友又被拉黑" | **15007** `"cannot accept friend request"` | :530-538 |
| ③ | S6（**必须在守卫之后**）：无行或 status≠1 → 拒 | **15005** | :540-559 |
| ④ | 仅当 maxFriends>0：先判 `counts[from] >= Max`，再判 `counts[me] >= Max` | 前者 **15004** `"sender's friend list full"`，后者 **15002** `"your friend list full"`（friend_logic.go:351-354）。**映射与 AddFriend 相反** | :561-571 |
| ⑤ | `now` 取一次；S11；`RowsAffected != 1` → 拒（防御性门禁） | **15005** | :573-589 |
| ⑥ | S12 反向收敛，0 行是常态，不检查 RowsAffected。不收敛的话会在 from 的收件箱里留下孤儿 pending | 出错 → 1003 | :591-599 |
| ⑦ | 依次处理 (from,me)、(me,from)：S13；inserted==1 时执行 S14；inserted>1 → 1003。SQL 里**没有**上限条件，完全依赖 ④ 和守卫 | 出错 → 1003 | :601-623 |

- ②③④ 的先后决定了错误码优先级：拉黑 > 申请不存在 > 申请人满 > 接受者满。

**提交后**
1. 失效 4 个键：`list(from)`、`list(me)`、`req(me)`、`req(from)`（`friend_repo.go:511-519,630`）。最后一个不能漏，因为反向 pending 落在 from 的收件箱里。
2. 推送 REQUEST_ACCEPTED 给 **from**（原申请人），by=me（`friend_logic.go:359-360`）。
3. 回空应答。

**边界**
- **拉黑之后去 Accept，正常拿到的是 15005，不是 15007**：Block 会在同一把守卫里把两个方向的 pending 置为终态，所以事务外前置先回 15005（`friend_repo_mysql_test.go:742-746`）。15007 只在"前置检查与守卫之间插进一次 Block"的竞态，或数据被直接改过时出现；此时双方的容量行已被 ensure。
- **互相申请时两人都点同意**：第一个 Accept 已把两条都置成 accepted，第二个在事务外前置就拿到 15005。
- 前置之后、守卫之前被对方 Reject：ensure 已做，③ 回 15005。

### 2.3 RejectFriend（232）

代码：`friend_logic.go:365-400`、`friend_repo.go:634-658`。

**入口校验**

| 步 | 条件 | 结果 |
|---|---|---|
| 1 | 取不到会话身份 | 1005 `"missing session identity"` |
| 2 | from==0 | 1005 `"from_player_id required"` |
| 3 | from==me | 1005 `"cannot reject your own request"`（:381-386）。不挡的话会落到 data 层的 errInvalidPlayerPair，变成 1003 假告警 |

**数据层**：S15 单条自动提交 CAS。不开事务、不拿守卫、不 ensure、不检查拉黑——它只会让 pending 变少。
- `RowsAffected == 0` → **15005** `"no pending friend request"`，**不失效缓存**。
- 其他错误 → 1003。

**提交后**
- 只失效 `req(me)`（:640,656）。
- **刻意不推送**：避免社交尴尬，也不暴露拒绝方刚刚在线（`friend_logic.go:396-397`）。

### 2.4 RemoveFriend（11）

代码：`friend_logic.go:402-427`、`friend_repo.go:660-700`。

**入口校验**

| 步 | 条件 | 结果 |
|---|---|---|
| 1 | 取不到会话身份 | 1005 `"missing session identity"` |
| 2 | target==0 | 1005 `"target_player_id required"`（:414-419） |

- logic 层**不检查 target==me**。

**数据层**
1. `me == target` → 直接返回成功，**不做任何 I/O**（:662-664）。
2. 事务外前置：S16 普通读（此时还没有守卫，不许锁定读）。
   - 无行 → 成功（幂等），**不 ensure、不失效缓存**（`TestRemoveFriend_NonFriendDoesNotCreateCapacityRows`，friend_repo_mysql_test.go:759）。
   - 漏判只可能发生在"边正好在这一瞬间由 Accept 提交"时，等价于删除发生在成为好友之前，按幂等返回成功即可（:675-678）。
3. `runGuardedWrite(me, target)`，body 只做 `deleteFriendEdges(me, target, now)`。`now` 在闭包里取，属于真正拿到守卫的那一遍（:687-694）。

**`deleteFriendEdges`**（`friend_repo.go:920-968`，与 Block 共用）
- 对 (a,b)、(b,a) 依次执行 S17；删到 1 行就执行 S18。
- S18 影响 0 行时打 ERROR 日志 `[friend] friend_count 下溢被拦截...`，但**不 fail-closed**：边已经删了，回滚反而把一次正确的删好友变成失败。

**提交后**
- 失效 `list(me)`、`list(target)`（:687,698）。
- 不推送（`friend_logic.go:424`）。
- 回空应答。

**边界**
- "不是好友"、"删自己"、"目标不存在"都回成功；可能的错误只有 1003 / 1005。
- 不清 friend_request 里的 accepted 行（由 sweep 清）；之后再加好友时走 upsert 复用。

### 2.5 Block（7）

代码：`friend_logic.go:508-541`、`block_repo.go:37-196`。

**入口校验**

| 步 | 条件 | 结果 |
|---|---|---|
| 1 | 取不到会话身份 | 1005 `"missing session identity"` |
| 2 | `target==0 \|\| target==me` | 1005 `"target_player_id invalid"`（:524-529）。不用 15000，因为那个文案是"不能加自己为好友" |

- **没有业务频控**：Block 是唯一没有每分钟配额的写路径（`block_repo.go:69-70`），只有 gate 5/s。

**事务外前置**：仅当 maxBlocks>0 时执行 S19（`block_repo.go:79-94`）
- `alreadyBlocked == 0 && blocked >= maxBlocks` → **15008** `"block list full"`，**不 ensure**。
- 已拉黑过的目标**不能**按名额拒：重复拉黑是幂等成功，而且还要靠后面的 ⑤⑥ 收敛残留。

**事务**：`runGuardedWrite(me, target)`
- 锁**双方**而不只是 me：⑤ 要减 target 的计数；而且只有锁住 target，才能与"以 target 为发起方的 Add / Accept"互斥（:100-105）。
- body 内顺序：

| 步 | 动作 | 失败结果 |
|---|---|---|
| — | `nowMs` 在闭包里取一次，拉黑时间与取消申请的 updated_ms 共用（:106-109） | — |
| ② | `blockExistsForUpdate(me, target)`：**单向** S4（:111-119,258-276）。不能换成双向判定：A 拉黑了 B 会让"B 拉黑 A"被当成重复而不写 | — |
| ③ | ② 未命中且 maxBlocks>0：S20 普通读，`>= maxBlocks` → 拒（:120-141） | **15008** |
| ④ | ② 未命中时执行 S21 ODKU（:143-153）。不用 INSERT IGNORE：这一行可能是 Unblock 刚删、未 purge 的记录，S→X 升级会与排队中的 Unblock 互等成 1213 | 出错 → 1003 |
| ⑤ | `deleteFriendEdges(me, target, nowMs)`：**② 命中也照样执行**，用来收敛历史残留（:156-166） | 出错 → 1003 |
| ⑥ | 依次处理 (me,t)、(t,me)，执行 S22（:168-180） | 出错 → 1003 |

- ③ 用普通读的理由与 Add ⑤⑥ 相同：能让计数变大的只有 Block 自己，而它必须持有同一个 me 的守卫（:121-129）。
- ② 命中时照常提交，**不走**名额校验。

**提交后**
- 失效 4 个键：`list(me)`、`list(t)`、`req(me)`、`req(t)`。**幂等命中时也失效**（:187-194；:111-113）。
- **不推送**：被拉黑者不该知道自己被拉黑了（`friend_logic.go:508-511`；`block_repo.go:55-56`）。
- 回空应答。

**边界**
- 目标 id 不存在时照样成功：会写 friend_block 行（受 MaxBlocks 封顶）并补容量行。
- 反复"Block + Unblock"换目标，可以绕过事务外的名额探针持续造容量行和缓存代次键（`block_repo.go:74-78`），只能靠 gate 限频挡。

### 2.6 Unblock（236）

代码：`friend_logic.go:543-570`、`block_repo.go:198-221`。

**入口校验**

| 步 | 条件 | 结果 |
|---|---|---|
| 1 | 取不到会话身份 | 1005 `"missing session identity"` |
| 2 | `target==0 \|\| target==me` | 1005 `"target_player_id invalid"`（:556-562）。self 必须在这里挡，否则会被刷成 1003 假告警 |

**数据层**：S23 单条自动提交 DELETE，不开事务、不拿守卫。
- 删不到行 = 成功（幂等）。
- 错误 → 1003。
- RC 下删不到行时不加任何锁。

**提交后**
- 不失效任何缓存：黑名单不缓存，也不改好友边（:218-220）。
- **不恢复好友关系**（`friend_logic.go:567`）。
- 不推送。

**已知的良性竞态**：与 AddFriend 并发时，AddFriend 的拉黑探针可能在解除前一瞬读到"仍被拉黑"，回 15007；玩家重试即可（`block_repo.go:207-208`）。

### 2.7 GetFriendList（12）

代码：`friend_logic.go:429-477`。**严格按此顺序**：

| # | 步骤 | 失败结果 | 出处 |
|---|---|---|---|
| 1 | 套预算 | — | :431-432 |
| 2 | 取会话身份 | 1005 `"missing session identity"` | :434-439 |
| 3 | `Repo.GetFriendList(me)`：版本化缓存（§3），未命中时读 S24 | **任何** error → 1003 `"storage unavailable"`，`friends` 为空 | :441-444 |
| 4 | 按列表顺序收集 friend id | — | :446-449 |
| 5 | `onlineStatuses`：列表为空时不碰 Redis；Sessions==nil → 报错；否则调 `BatchOnlineStatus`（§4.1） | err≠nil（**哪怕带着部分结果**）→ 1003，`friends` 为空 | :240-248,450-453 |
| 6 | 逐条组 `FriendEntry{friend_player_id, since_ms}`；map 里有该 id → `is_online=true, last_active_ms=last_active_ts`；没有 → 留零值 | — | :455-470 |
| 7 | `FillFriendProfiles`（§4.2）填 name / level / class_id / gender / appearance_id / zone_id | **失败只打 ERROR** `[friend] 好友展示资料补全失败: %v`，照常返回 | :471-475 |
| 8 | 回 `{friends}`，不带 error_message | — | :476 |

- 无排序保证、无分页、最多 1000 条。
- 在线态与 last_active **每次现取，绝不进缓存**（`friend_repo.go:231-236`）。
- 为什么在线读失败必须回错：客户端靠 `is_online` 禁止邀请离线好友，"在线状态未知"不能当成离线（`friend_logic.go:125-131,238-239`；`TeamInvitationDirectory.cs:186-187`）。
- 单测：`TestGetFriendListFillsOnlineFromSessions`（friend_logic_test.go:1034）、`TestGetFriendListRejectsWhenSessionsUnavailable`（:1058）。

### 2.8 GetPendingRequests（230）

代码：`friend_logic.go:479-506`。
1. 套预算。
2. 取会话身份，失败 → 1005。
3. `Repo.GetPendingRequests(me)`：版本化缓存，键 `req`；未命中时读 S25（`to=me AND status=1`）。任何 error → 1003。
4. 映射成 `FriendRequest{from, to, request_time_ms, status = FriendRequestStatus(int32)}`。status 恒为 1，数值原样转换（单测 friend_logic_test.go:1086）。
5. 回 `{requests}`。

- **只回入站**。没有"我发出的申请"列表接口。
- 不读在线、不补资料、不推送。
- `request_time_ms` 是最近一次发起的时刻。

### 2.9 ListBlocks（2）

代码：`friend_logic.go:574-600`、`block_repo.go:223-256`。
1. 套预算。
2. 取会话身份，失败 → 1005。
3. S26 **直读 MySQL、不缓存**；error → 1003。
4. 映射成 `BlockEntry{blocked_player_id, since_ms}`。
5. 回 `{blocks}`。

- 为什么不缓存：低频读，且写后必须立刻可见（`block_repo.go:223-229`）。
- **刻意不带在线状态**：否则拉黑任意 id 就能探测对方是否在线（`friend_logic.go:598`；单测 :1071 断言 Sessions 一次都没被调用）。
- 也不补昵称（`friend.proto:171-175`）。
- 只列 `player_id = me` 的行；**拉黑我的人不出现**。

### 2.10 RecommendFriends（119）的入口

代码：`recommend.go:55-90`，结构见 §5。

| # | 检查 | 失败结果 | 出处 |
|---|---|---|---|
| 1 | 套预算 | — | :60-61 |
| 2 | 取会话身份 | 1005 `"missing session identity"`，**不带** online_directory | :69-72 |
| 3 | `len(exclude_player_ids) > 64`（int 比较，等于 64 放行） | 1005 `"too many exclude_player_ids"`；**不截断**、**不带** online_directory、不碰存储 | :77-79；单测 friend_logic_test.go:706 |
| 4 | `limit = recommendLimit(req.limit, 10, 20)`：0 → 10；>20 → 20；maxLimit==0 时不钳（只在单测里出现） | 不报错 | :32-41,80 |
| 5 | `online_only` 为 true → 在线目录（§5.2）；否则 → 普通推荐（§5.1） | — | :81-83 |

### 2.11 NotifyFriendEvent（235）被客户端上行调用

- 会话白名单只收 10 个 C2S 方法，**不含 235**（`session.go:48-63`）。
  - 带会话调用 235 → `PermissionDenied`（`:101-105`）；会话 player_id=0 时先被判坏头 → `Unauthenticated`（`:96-100`）。两者在客户端看到的都是**信封 1003**。
- server 侧内嵌 `UnimplementedClientPlayerFriendServer` 作为第二道防线，但真正的安全保证是白名单（`go/friend/internal/server/friend_server.go:19-40`）。
- robot friend-smoke 第 6 步断言这里拿到**信封 tip == 1003**（`robot/friend_smoke_scenario.go:412-440`）。
- **Java 注意**：gate 会把 235 当作客户端服务方法转给 friend（`MessageRoutes.java:35-45`）。friend 必须对 235 回 `ClientReply{tip_id=1003}`。
  - 235 的应答类型是 Empty，gate 只在 tip≠0 时回包（`ClientDispatcher.java:339-343`）。
  - 照抄 login 派发器的默认行为会回 1006（`xm-login/.../dispatch/ClientMessageDispatcher.java:121-130`），robot 第 6 步就会失败。

---

## 3 缓存

### 3.1 键与值

| 用途 | 基线键 | 出处 |
|---|---|---|
| 好友列表 | `friend:{f:<pid>}:list:v3` | friend_repo.go:290-292 |
| 入站申请 | `friend:{f:<pid>}:req:v3` | :294-296 |
| 代次键 | `<数据键>:generation`（直接追加后缀，hash tag 随之带过来） | :319-322 |

- hash tag `{f:<pid>}` 保证两条 Lua 动到的两个键同 slot，Cluster 下不会 CROSSSLOT。版本号用于改形状时直接换键，不做原地兼容（:279-289；单测 `TestCacheKeysAreClusterSafe`，friend_repo_mysql_test.go:1253）。
- 缓存放在 **FriendRedis**。没配时回落到共享库，并打 WARN（`servicecontext.go:149-168`）。staging/prod 要求独立实例，且 `maxmemory-policy=noeviction`（`config.go:88-95`；`friend.yaml:74-79`）。
- **值**是 JSON 数组（:237-247）：
  - 好友：`[{"friend_player_id":u64,"since_ms":i64}]`
  - 申请：`[{"from_player_id","to_player_id","request_time_ms","status"}]`
- 缓存里存的是**已经按 LIMIT 截断**的列表（:254-257）。
- 空列表归一成 `[]`，序列化为 `"[]"` 而不是空串，这样才能区分"未命中"与"命中空列表"（:1248-1250,1275-1277；单测 friend_cache_test.go:120）。
- 数据键 TTL = CacheTTL，以 PX 毫秒传入（:1207）；**代次键没有 TTL**，由 INCR 建出，永久存在。

### 3.2 两条 Lua

```lua
-- 失效：KEYS[1]=代次键, KEYS[2]=数据键                 friend_repo.go:301-305
redis.call("INCR", KEYS[1])
redis.call("DEL", KEYS[2])
return 1

-- 回填：KEYS 同上；ARGV[1]=读到的代次, ARGV[2]=payload, ARGV[3]=ttl 毫秒   :307-317
local generation = redis.call("GET", KEYS[1])
if not generation then generation = "0" end
if generation ~= ARGV[1] then return 0 end
if tonumber(ARGV[3]) > 0 then redis.call("SET", KEYS[2], ARGV[2], "PX", ARGV[3])
else redis.call("SET", KEYS[2], ARGV[2]) end
return 1
```

- 代次按**字符串**比较；所有 ARGV 都显式转成字符串（:1199-1200）。
- 写路径**必须**先 INCR 代次，不能只 DEL。只 DEL 的话，"写之前读到旧快照的回填"可以在写之后落地。

### 3.3 读算法 `loadVersionedFriendCache`

代码：`friend_repo.go:1143-1216`。严格按此顺序：

1. `GET 数据键`：
   - Redis 报错 → **返回错误（1003）**；
   - 空串 → 未命中（go-zero 的 GetCtx 把 Nil 吞成 `""`）；
   - **JSON 解码失败 → 返回错误（1003）**；
   - 否则命中，直接返回（:1150-1172）。
2. 未命中 → 取**该键专属**的进程内互斥锁。基线用 `sync.Map` 存 key → `*sync.Mutex`，永不回收（:1121-1132,1173-1175）。
3. 持锁后**再读一次**数据键，语义同第 1 步（:1176-1178）。
4. `GET 代次键`：报错 → 返回错误；空串 → **必须补成 `"0"`**，否则回填脚本恒返回 0，缓存永远写不进去且零报错（:1180-1190；单测 friend_cache_test.go:45）。**代次必须在 SQL 之前读。**
5. 调 loader 读 MySQL（S24 / S25）；错误原样返回 → 1003（:1191-1194）。
6. 序列化 JSON；出错 → 返回错误。
7. `EVAL 回填`。**EVAL 失败只打 ERROR** `[friend] 回填缓存失败,本次直接返回 MySQL 权威结果 key=%s: %v`，照常返回 MySQL 结果；脚本返回 0（代次变了、放弃回填）不是错误（:1201-1214）。
8. 返回 loader 的值。读路径**从不创建代次键**。

**正确性论证**（Java 必须保住；`friend_repo.go:1134-1137`；单测 friend_cache_test.go:143、friend_repo_mysql_test.go:1224）
- 读者先读代次 g、再读库；写者在**提交后**原子地 INCR + DEL。
- 写者在读者快照之后提交时：
  - INCR 早于回填 → 回填被拒；
  - INCR 晚于回填 → DEL 删掉旧回填。
- 所以写者失效之后不会残留旧快照。

**故障语义**

| 环节 | 故障 | 结果 |
|---|---|---|
| 读数据键 | Redis 报错，或 JSON 坏 | 1003（fail-closed，防止 Redis 挂掉时全服列表读压到 MySQL；`friend_repo.go:1138-1142`） |
| 读代次键 | Redis 报错 | 1003 |
| MySQL | 任何错误 | 1003 |
| 回填 EVAL | 任何错误 | 只打日志，返回 MySQL 结果 |
| 写后失效 | 任何错误 | 只打日志，该键最多陈旧一个 CacheTTL |

### 3.4 失效矩阵

所有失效都在**事务提交之后**做。写路径返回哨兵或错误时，在失效之前就已返回，不做失效。

- `invalidateCaches` 对每个键顺序执行一次失效脚本，错误用 `errors.Join` 合并（`friend_repo.go:991-1006`）。
- `invalidateCachesAfterCommit` 恒不返回错误，只打 ERROR `[friend] 写已提交但缓存失效失败(该键最多陈旧一个 CacheTTL)keys=%v: %v`（`:1008-1021`）。
  - 理由：把失效失败上抛，会让一次成功的写被定性成 1003。客户端会重试（撞 15003），推送也被跳过，还多一条假告警。
- 失效用的是请求 ctx（受预算约束）。

| 写路径 | 失效的键 | 出处 |
|---|---|---|
| AddFriend 成功 | `req(target)` | friend_repo.go:489-491 |
| AcceptFriend 成功 | `list(from)`、`list(me)`、`req(me)`、`req(from)` | :511-519,630 |
| RejectFriend 成功 | `req(me)`；0 行时不失效 | :640,653-656 |
| RemoveFriend | `list(me)`、`list(target)`；不是好友或删自己时不失效 | :662-664,683-687,698 |
| Block | `list(me)`、`list(t)`、`req(me)`、`req(t)`；幂等命中时也失效 | block_repo.go:187-194 |
| Unblock、sweep、推荐、在线目录 | 无 | block_repo.go:218-220 |

**时序要求**：基线里失效发生在 repo 内部、推送之前（`friend_repo.go:489-491` 早于 `friend_logic.go:311-313`）。Java 必须保住"**失效完成（或超时）之后才推送**"。否则对方收到推送立即去拉 230 / 12，可能命中旧缓存，最长陈旧 30 分钟。

### 3.5 Java 落地

**键**（经 `xm-discovery/.../RedisKeys.java` 新增方法；pid 一律用 `Long.toUnsignedString`）

| 用途 | 键 |
|---|---|
| 好友列表 | `xm:friend:{<pid>}:list` |
| 入站申请 | `xm:friend:{<pid>}:req` |
| 代次键 | `<数据键>:gen` |
| 申请配额 | `xm:friend:{<pid>}:quota` |
| 在线目录配额 | `xm:friend:{<pid>}:dir-quota` |

- 同一玩家的键共用 hash tag `{<pid>}`：现在是单机 Redis（DB 12，`xm-login/src/main/resources/application.yaml:74`），这样写零成本，将来上 Cluster 也安全。

**脚本调用**
- 两条 Lua 照 §3.2，用单条 `RScript.evalAsync`：遇到 NOSCRIPT 会自动重载。**不要用 RBatch**（`PlayerPresenceDirectory.java:84-89`）。

**读算法**照 §3.3，有三处 Java 写法：
- 单飞用 `ConcurrentHashMap<String, CompletableFuture<?>>`，完成即移除，不泄漏。等待者在剩余预算内限时等待，到时回 1003；等到后**重新走一遍读流程**，不直接复用领头者的结果。
- 值用 JSON，与基线同形。空列表必须序列化为非空串。
- 回填失败只记日志和计数。

**失效**
- 在提交后、推送前、回应答前**等待完成**，用剩余预算做上界。
- 同一会话的下一个请求由 gate 串行发出，等失效完成才能保证"写后立刻读"读到新值。

**Redis 要求**
- 生产 Redis 必须是 `noeviction` 或 `volatile-*`，否则代次键被淘汰会引入 ABA 旧快照（§9.1 第 13 条）。
- 代次键可以改成"唯一值 + TTL"，见 §10.1 D6。

---

## 4 在线状态、展示资料与推送

### 4.1 在线状态（基线 `go/friend/internal/data/session_reader.go`）

**数据源**
- 键：`player:session:<id>`，无 hash tag、无版本前缀，是跨运行时契约，**只能经 SharedRedis 读**（:28-35,47；`friend_logic.go:171-174`）。
- 值：`player_locator.PlayerSession`，用到其中的 `player_id=1, session_id=2, gate_id=3, gate_instance_id=4, state=10, last_active_ts=11`。
  - state 取值：`UNKNOWN=0 / ONLINE=1 / DISCONNECTING=2`（30 s 重连租约）/ `OFFLINE=3`（`proto/player_locator/player_locator.proto:27-44`）。
- `last_active_ts` 的真实语义是"**EnterGame 建会话或重连的时刻**"，不是最近一次操作的时刻（`go/login/internal/logic/pkg/sessionmanager/session_manager.go:258`；`go/player_locator/internal/logic/reconnectlogic.go:74`）。

**`BatchOnlineStatus` 算法**（:96-145，单批处理 `readChunk` 见 :159-231）
1. 入参为空 → `(nil, nil)`。读取器或句柄为 nil → 报错。
2. 去重、剔除 0，保持首次出现顺序；全被剔除 → `(nil, nil)`，不发 MGET（:109-125）。
3. 按 `ListReadHardLimit` 分批 MGET（传 0 时兜底 256）。某批失败只记下 firstErr，**继续后面的批**（:129-143）。
4. 每批内按以下顺序判定：

| 情况 | 指标 | 结果 | 出处 |
|---|---|---|---|
| MGET 报错 | 本批每个 id 计 error | 本批失败 | :168-175 |
| 返回条数 ≠ key 数 | 本批每个 id 计 error | 整批失败（防止下标错位，把 A 的状态贴到 B） | :176-186 |
| 值为空串 | offline | 不是错误 | :191-196 |
| proto 解码失败 | error | 记 firstErr，**continue** | :197-208 |
| `session.player_id ≠ 查询 id` | error | 记 firstErr，continue | :209-217 |
| `state ≠ ONLINE`（含 DISCONNECTING） | offline | — | :218-223 |
| 其他 | ok | `states[id] = {Online:true, LastActiveMs:last_active_ts}` | :224-228 |

5. 返回"**只含 ONLINE 玩家的 map**"和 firstErr。err≠nil 时 map 里可能有部分健康条目（:96-100）。

**两个入口**
- `FillOnlineStatus` 吞掉错误，只给**普通推荐**用（:147-155）。
- `GetFriendList` 用带错误的 `BatchOnlineStatus`。

**指标与日志**
- 每个**去重后的 id** 计一次 `friend_online_lookup_total{outcome}`；调用方不得再计（:41-44）。
- 失败日志每 10 s 至多一条，用原子 CAS 控制（:233-250）。

**Java 对应**
- 在线目录：`xm:presence:{id}`，值 `PlayerPresence{player_id=1, zone_id=2, gate_node_id=3, gate_instance_id=4, session_id=5, online_since_ms=6, owner_epoch=7}`（`xm-discovery/src/main/proto/xm/discovery/presence.proto:13-24`）。
  - TTL 60 s，gate 是唯一写者：scene 确认进场后写入，场景绑定结束时删除（`docs/design/architecture.md:125-140`；`RedisKeys.java:41-47`）。
  - **没有 state 字段**：条目存在就等于在游戏里。断线即删，等价于基线把 DISCONNECTING 判为离线。
- `last_active_ms` 用 `online_since_ms` 填：gate 在进场确认时写入时钟值（`xm-gate/.../presence/GatePresence.java:51-60`）。
  - robot 断言在线好友的 `last_active_ms ≠ 0`（`robot/friend_smoke_scenario.go:335-337`），所以必须填。
- **presence 的 `zone_id` 是 gate 所在的区，不是玩家的 home zone**（GatePresence.java:54），不能拿来做区过滤或填 FriendEntry.zone_id。
- **必须新增严格批量读**。现有 `findAll` / `findAllAsync` 有三个问题（`PlayerPresenceDirectory.java:117-170`）：
  - 把解码失败、身份不符**降级为离线**，只打 WARN（:154-170），与基线的"error → 1003"不同；
  - 一次 MGET 全部键，不去重、不剔 0、不分批；
  - 同步版会阻塞，最坏 4.2 s。
- 建议新增 `findAllStrictAsync(ids)`：
  - 去重、剔 0，按 ListReadHardLimit 分批；
  - 解码失败或身份不符时异常完成，但同时带回健康条目；
  - 调用方用剩余预算设超时。
  - GetFriendList 用严格版；普通推荐用现有降级版。
- 损坏的条目最多存活一个 TTL（60 s）：gate 续期时发现值不是自己写的就不续（`PlayerPresenceDirectory.java:52-61`），所以严格读造成的 1003 能自愈。

### 4.2 展示资料补全

基线代码：`go/friend/internal/data/friend_profiles.go:17-82`。
- 每次调用都现建 `OnlineDirectory{Redis: SharedRedis, DataService}`（`friend_logic.go:472`）。
- 入参为空 → 直接返回；Redis 句柄为 nil → 报错。
- **每批 64 人**，每批内按顺序：
  1. MGET `PlayerAllData:<id>`。前缀是 proto 全名 `PlayerAllData`，该 proto 没有 package（`proto/common/database/player_cache.proto:30`）。
     - **MGET 报错 → 立刻返回错误，后续批次全部不处理**（:37-40）；条数不符 → 报错（:41-43）。
  2. 逐条处理：值非空、**且**能解码、**且** `player_database_data.player_id == id`，才填 `name / level / class_id(uint32_pb_component.class) / gender / appearance_id`（:47-54）。解码失败或身份不符**静默跳过**。
  3. 名字仍为空的 id 记入 missingNames（:55-57）。
  4. `DataService == nil` → `continue`，跳过补名和 zone（:59-61）。
  5. missingNames 非空 → 调 `BatchGetPlayerName`（单次 ≤500，缺席的 id 不在 map 里）；报错 → 立刻返回（:62-72）。
  6. **每批无条件**对本批全部 id 调 `BatchGetPlayerHomeZone`；报错 → 立刻返回。`zone_id = map[id]`，缺席的 id 为 0（:73-79）。
- 出错后的形状：前 k−1 批完整，第 k 批可能只填了一部分，之后的批**完全没填**。is_online、since_ms 不受影响。
- Unity 会**无条件**用 `zone_id` 覆盖本地缓存，0 也照写（`TeamInvitationDirectory.cs:183`）。

**Java 对应**
- `xm_java.player` 表直接就有 `name / level / class_id / gender / appearance_id / zone_id`，其中 zone_id 即 home zone（`xm-player-store/src/main/resources/db/xm-player-schema.sql:11-33`）。
- `PlayerStore` 目前只有单查 `findPlayer`（`PlayerStore.java:99-101`），需新增批量读，每批 ≤64：

```sql
SELECT player_id, name, level, class_id, gender, appearance_id, zone_id FROM player WHERE player_id IN (…)
```

- 失败语义照基线：找不到的行字段留零值；查询失败 → 打 ERROR，返回已填的部分，**不回 tip**。
- `level` 是 INT UNSIGNED，写进 uint32 之前要做截断检查。
- 等级只在写回或周期存盘时更新，在线玩家可能滞后，属于登记差异（§10.1 D4）。

### 4.3 推送（基线 `go/friend/internal/logic/push.go`）

**触发点只有两个**（:22-24）
- AddFriend 成功 → 推给 **target**：`REQUEST_RECEIVED`，by=me。
- AcceptFriend 成功 → 推给原申请人 **from**：`REQUEST_ACCEPTED`，by=me。
- Reject、Remove、Block、Unblock、三个读 RPC、推荐都**不推**。

**三条纪律**（:12-21）
1. 只在 MySQL 提交之后、事务之外推；
2. 失败只打日志、计指标，函数没有返回值，绝不影响 RPC 结果；
3. 不推给操作者本人。

**消息与语义**
- 语义是 **at-most-once**：没有重试、回执、离线补推（:3-10）。客户端必须靠拉 230 / 12 兜底。
- 消息体：`FriendEventS2C{reason, by_player_id, ts_ms = 服务端时钟}`（:119-124），不带列表。

**`pushFriendEvent` 的执行流程与指标结局**（:81-160；指标 `friend_push_total{reason, outcome}`）

| # | 条件 | 结局 | 出处 |
|---|---|---|---|
| 1 | reason 未知 | ERROR 日志，**不计指标**，不推 | :82-87,66-75 |
| 2 | 收件人为 0 | error | :88-92 |
| 3 | KafkaWriter 或 GateCommandBuilder 为 nil | error（"推送整个关着"必须在指标上看得见） | :94-103 |
| 4 | 读 `player:session:<to>` 时 Redis 报错，或**解码失败** | error（解码失败不当离线） | :105-111,162-182 |
| 5 | 键不存在，或 state≠ONLINE（含 DISCONNECTING） | offline | :112-117 |
| 6 | 序列化失败 | error | :119-129 |
| 7 | `kafkautil.PushToPlayer` 失败。推送 ctx = 请求 ctx 派生 + 1500 ms；空 gate_instance_id、非数字 gate_id 都 fail-closed | error | :131-158；`go/shared/kafkautil/gate_push.go:50-78` |
| 8 | 成功写入 Kafka | ok | :159 |

- 单条读 `loadPlayerSession` **不校验** `session.player_id == to`，这一点与批量读不同。
- 推送在回应答**之前**同步执行，耗时受请求预算封顶。
- 封装方式：`MessageContent{message_id=235, serialized_message, id=0}` → `PushToPlayerEvent{session_id, message_content}` → `GateCommand{event_id=PushToPlayerEvent, target_gate_id, target_instance_id, payload}`（`go/friend/internal/kafka/gate_command_builder.go:28-51`）。

**Java 对应**
- 调用 `PlayerPushes.pushToPlayer(target, MessageContent{message_id=235, serialized_message=FriendEventS2C 字节, id=0})`（`xm-discovery/.../presence/PlayerPushes.java:52-58`）。
- 通道：查 `xm:presence` → 把 `GatePush` 发布到 `xm:gate-push:{zone}:{gate}`（:97-105；`RedisKeys.java:126-132`）。gate 在会话 EventLoop 上做玩家栅栏 `!closed && !closing && presenceOnline && scenePlayerId==target` 之后才下发（`ClientDispatcher.java:559-582`）。
- 指标结局映射：

| PlayerPushes 结局 | outcome |
|---|---|
| SENT | ok |
| OFFLINE | offline |
| GATE_UNREACHABLE | error（基线写进 Kafka 即记 ok；Java 能看见"没有订阅者"即已丢，记 error 更诚实；登记为 D3） |
| stage 异常完成或超时（`orTimeout(1500ms)`） | error |
| 未知 reason | 不推、不计 |

- 时序：**提交 → 失效完成 → 发起推送**。推送 fire-and-forget，用 `whenComplete` 计指标，不阻塞、也不改变应答。

---

## 5 推荐与在线目录

### 5.1 普通推荐（`online_only=false`）

代码：`recommend.go:84-148`、`recommend_repo.go`。

**流程**
1. `limit == 0`：只有两个阈值都被配成 0 才走得到，Validate 会先拒掉。若真走到，打 ERROR 并回**空成功**（:84-90）。
2. 排除集 = `req.exclude_player_ids ++ [me]`（:96-98）。
3. 策略链固定两级、顺序固定：`mutual` → `random`。
   - 每级请求的数量 `want = limit − 已选中数`，只补缺口；已经凑满就跳过后面的级（:101-124）。
   - 每级结果按返回顺序追加，并把选中的 id 追加进排除集，后一级不会重复（:133-137）。
4. **任一级报错 → 立刻回 1003** `"recommend candidates unavailable"`，ERROR 日志带策略名。**不做部分降级**：第一级失败不跑第二级，也不返回已选中的部分（:125-132）。
5. 组装 `RecommendEntry{candidate_player_id, mutual_friends}`，再补在线态：走 `FillOnlineStatus`，**任何失败都降级为离线**（:166-184）。
   - name / level 等展示字段**不填**；`online_directory=false`；`next_cursor=""`（:140-148）。
6. 服务端不留状态。"换一批"靠客户端回传 exclude；同一请求重放结果不同，这是设计（:53-54）。

**mutual：二度好友**（`recommend_repo.go:194-224`）

```sql
SELECT f2.friend_player_id, COUNT(*) AS mutual
FROM friend f1 FORCE INDEX (PRIMARY)
STRAIGHT_JOIN friend f2 FORCE INDEX (PRIMARY) ON f2.player_id = f1.friend_player_id
WHERE f1.player_id = ?
  AND f2.friend_player_id <> ?
  AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend f FORCE INDEX (PRIMARY)
        WHERE f.player_id = ? AND f.friend_player_id = f2.friend_player_id)
  AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_block b_out FORCE INDEX (PRIMARY)
        WHERE b_out.player_id = ? AND b_out.blocked_player_id = f2.friend_player_id)
  AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_block b_in FORCE INDEX (PRIMARY)
        WHERE b_in.player_id = f2.friend_player_id AND b_in.blocked_player_id = ?)
  AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_request r_out FORCE INDEX (PRIMARY)
        WHERE r_out.from_player_id = ? AND r_out.to_player_id = f2.friend_player_id AND r_out.status = 1)
  AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_request r_in FORCE INDEX (PRIMARY)
        WHERE r_in.from_player_id = f2.friend_player_id AND r_in.to_player_id = ? AND r_in.status = 1)
  [AND f2.friend_player_id NOT IN (?,…)]
GROUP BY f2.friend_player_id
ORDER BY mutual DESC, RAND()
LIMIT ?
```

- 参数顺序：me ×7 → exclude… → limit。limit 按 int64 传（:213-223）。
- exclude 为空时不拼 `NOT IN`，空的 `NOT IN ()` 是语法错误（:81-99）。
- 排序：共同好友数降序，同数随机。
- **承重写法，不得改动**（:107-141）：
  - `STRAIGHT_JOIN` 钉住"先 f1 后 f2"；
  - 五条 NOT EXISTS 按方向拆开，每条都是完整主键等值；
  - `SEMIJOIN(FIRSTMATCH)` 是承重的，`FORCE INDEX(PRIMARY)` 是防线；
  - 外层列一律写 `f2.friend_player_id`。裸列名会先解析到子查询自己的表上，排除静默失效。
- 读数上界 ≤ 8R + 2F + 2 + limit。默认上限下 ≤ 320,422；按 MaxFriends 的平方增长，所以 MaxFriends 封顶 300（:143-163）。

**random：随机锚点兜底**（`recommend_repo.go:281-395`）
1. `SELECT MIN(player_id), MAX(player_id) FROM friend`；出错 → 报错 → 1003。
2. 任一为 NULL 或 `max == 0` → 返回空候选，**不是错误**（:282-295）。
3. `pivot = lo`；`hi > lo` 时 `pivot = lo + Uint64N(hi − lo + 1)`，宽度溢出为 0 时退回 lo（:296-305）。
4. 锚点 SQL（:363-378）：

```sql
SELECT c.player_id, 0 AS mutual
FROM (SELECT DISTINCT player_id FROM friend FORCE INDEX (PRIMARY)
      WHERE player_id >= ? ORDER BY player_id LIMIT ?) AS c       -- pivot, 1024
WHERE c.player_id <> ?
  AND NOT EXISTS (… friend f      WHERE f.player_id = ? AND f.friend_player_id = c.player_id)
  AND NOT EXISTS (… friend_block b_out WHERE b_out.player_id = ? AND b_out.blocked_player_id = c.player_id)
  AND NOT EXISTS (… friend_block b_in  WHERE b_in.player_id = c.player_id AND b_in.blocked_player_id = ?)
  AND NOT EXISTS (… friend_request r_out WHERE r_out.from_player_id = ? AND r_out.to_player_id = c.player_id AND r_out.status = 1)
  AND NOT EXISTS (… friend_request r_in  WHERE r_in.from_player_id = c.player_id AND r_in.to_player_id = ? AND r_in.status = 1)
  [AND c.player_id NOT IN (?,…)]
ORDER BY c.player_id
LIMIT ?
```

   - 每条子查询同样带 `/*+ SEMIJOIN(FIRSTMATCH) */` 和 `FORCE INDEX (PRIMARY)`。
   - 参数：pivot → 1024 → me ×6 → exclude… → limit（:383-395）。
- 结果按 player_id **升序**，`mutual_friends` 恒为 0。
- **不回绕、不越窗**：pivot 靠近尾部，或窗口里被排除的人太多时，返回少于 want，可能为空（:274-280）。
- 窗口 1024 的预算账（:229-241；守账测试 `go/friend/internal/config/config_test.go:128-149`）：
  - 有上限的排除最多占 1 + 200 + 200 + 50 + 200 + 64 + 19 = 734 个；
  - 再装下 20 个合格者，至少需要 754；
  - 多出的位置留给没有上限的"拉黑我的人"。
- **剩余风险**：派生表能不能只读 pivot 之后，依赖优化器对 `player_id >= ?` 选 range 访问；统计声称表只有 ≤1 行时会退化成从索引开头扫（:35-46）。

**排除语义**（两级一致）
- 排除：自己、调用方 exclude、已是我的好友、我拉黑的、拉黑我的、我发出的 pending、发给我的 pending（:68-70）。
- **纯读**：不进事务、不加锁、不持守卫；三张表之间没有快照一致性，允许轻微陈旧。**禁止把这些查询复用为 AddFriend 的前置判定**（:19-26）。
- 候选池只有"至少有一条好友边的玩家"，新服推荐为空（:63-66）。
- `rows.Err()` 必须检查，否则断连会被当成"没有候选"（:419-423）。

**Java 落地**
- 两条 SQL **逐字照搬**，包括提示和字面量 `status = 1`。
- exclude 用 MyBatis `<foreach>` 拼 `NOT IN`，空列表时不拼。所有 id 按无符号绑定。
- pivot 用 `lo + nextLong(hi − lo + 1)`（雪花 id 小于 2^63）；宽度 ≤0 时退回 lo。
- 任一 SQL 异常 → 1003 `"recommend candidates unavailable"`，不返回部分结果。
- 这些写法 H2 不支持，SQL 测试必须连真 MySQL（`-Dxm.it.mysql`，`AGENTS.md` §4）。
- 在线态用 `PlayerPresenceDirectory.findAllAsync`，任何失败都按离线处理。

### 5.2 在线目录（`online_only=true`）

代码：`go/friend/internal/logic/online_directory.go:14-48`、`go/friend/internal/data/online_directory.go`。

**检查顺序**（排在 §2.10 第 1–4 步之后；从这里开始，**所有响应都带 `online_directory=true`**，logic 文件 :15-17）

| # | 步骤 | 失败结果 | 出处 |
|---|---|---|---|
| a | `ValidateDirectoryInput(cursor, query)`，在计数**之前**，非法输入不占额度 | 1005 `"invalid online directory cursor or query"` | logic :18-20；data :75-84 |
| b | FriendRedis 为 nil（只在单测里出现） | 1003 `"online directory limiter unavailable"` | logic :21-24 |
| c | 配额 EVAL 出错（**fail-closed**） | 1003 `"online directory limiter unavailable"` | logic :25-30 |
| d | 返回值不是 int64 | 1003 `"online directory limiter invalid"` | logic :31-34 |
| e | `count > 60` | 1008 `"online directory rate limited"` | logic :35-37 |
| f | `List` 返回 `ErrInvalidDirectoryInput`（二次校验） | 1005 `"invalid online directory parameters"` | logic :38-42 |
| g | `List` 的其他任何错误 | 1003 `"online directory unavailable"` + ERROR 日志 | logic :43-46 |
| h | 成功 | `{online_directory=true, candidates, next_cursor}` | logic :47 |

**配额**
- 键：FriendRedis 上的 `friend:{directory:<caller>}`。
- 脚本与申请配额相同，ARGV 为 `"60"`，阈值 60 写死。
- 固定窗口；被拒的请求也会 INCR；计数发生在 home zone 查询之前，后续依赖失败的请求也占额度。
- 与申请配额相反：这里出故障时 **fail-closed**。
- 基线**没有**为它计任何 friend_* 指标。

**游标**（data :38-73）
- 格式：`v1:<scan>:<offset>`。
- 解析规则：
  - 空串 → `{0,0}`；
  - 长度 >48 **字节** → 非法；
  - `strings.Split(raw, ":")` 必须恰好得到 3 段，且第 1 段是 `v1`；
  - 第 2 段 `ParseUint(10, 64)`：只接受十进制数字，不接受符号，允许前导 0，越界非法；
  - 第 3 段 `ParseUint(10, 16)`，且 ≤1024。
- 编码：`scan==0 && offset==0` → `""`；否则 `v1:%d:%d`。所以 `v1:0:N`（N>0）是合法的产出；`v1:0:0` 能被解析但永远不会被产出。
- 测试钉住的非法例子：`"bad"`、`"v1:-1:0"`、`"v1:18446744073709551616:0"`、`"v1:1:65535"`、49 个字符（`go/friend/internal/data/online_directory_test.go:104`）。
- **Java 注意**：
  - `Long.parseUnsignedLong` 接受前导 `+`，而且通过 `Character.digit` 接受非 ASCII 数字（如全角、阿拉伯-印度数字），必须先校验"非空且全是 ASCII 数字"；
  - `String.split(":")` 会丢掉尾部空段（`"v1:1:2:"` 会被误判合法），必须用 `split(":", -1)`；
  - 长度按 UTF-8 字节数算。

**query**
- 校验的是原始串（未 trim）：合法 UTF-8，且 rune 数 ≤64（data :80-82）。
  - proto3 string 的非法 UTF-8 在 Go 和 Java 两边都会让**整个请求解析失败**，所以"非法 UTF-8 → 1005"这一支实际走不到。
- 归一化：`strings.ToLower(strings.TrimSpace(query))`；归一化后为空 = 不过滤（:112）。
- 匹配：`name = TrimSpace(profile.name)`；保留条件是 `Contains(ToLower(name), q) || Contains(十进制(id), q)`（:202,208-210）。
- **Java 精确对齐**：
  - TrimSpace 要按 Go `unicode.IsSpace` 的集合：`\t \n \v \f \r`、空格、U+0085、U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。**不要**用 `String.strip()`。
  - 小写逐码点用 `Character.toLowerCase(int)`（简单映射）。**不要**用 `String.toLowerCase(Locale.ROOT)`。
  - **不要**复用 `PlayerStore.nameKey`：它做 NFKC，会改变匹配语义（`PlayerStore.java:75-80`）。

**枚举与过滤算法**（data :86-227）
1. 再次校验；SharedRedis 或 DataService 为 nil → 报错（:87-92）。
2. limit：data 层把 0 → 12、>50 → 50，**生产走不到**。入口已经钳到 [1,20]，有效页长 = req.limit 或默认 10（:94-99）。
3. 调用者的 home zone：`BatchGetPlayerHomeZone([caller])`。报错 → 报错；`zone == 0` → 报错 "归属区未知"。最终都是 1003，fail-closed，不返回跨区玩家（:100-107）。
4. `skip = {caller} ∪ exclude`（:108-111）。
5. 最多 4 轮，每轮：
   - 先检查 ctx，到期 → 报错（:115-117）；
   - `SCAN pos.scan MATCH "player:session:*" COUNT 64`；返回键数 >1024 → 报错（:118-124）；
   - **键按字符串字典序排序**（`"…:10"` 排在 `"…:9"` 前，:125）；
   - `start = min(pos.offset, len(keys))`：上下线让批次变短时整批跳过；offset 只作用于本请求的第一轮（:126-129）；
   - `readEntries(keys[start:])`（:159-227）：
     - 解析 id；解析失败、id==0、在 skip 中 → 不读；
     - 一次 MGET 每个 id 的两个键：会话键与 `PlayerAllData:<id>`；MGET 报错或条数不符 → 报错；
     - 逐个过滤，任一不满足即丢弃（**不报错**）：
       1. 两个值都非空且都能解码；
       2. `session.player_id == id`；
       3. `state == ONLINE`；
       4. `profile.player_database_data.player_id == id`；
       5. trim 后 name 非空；
       6. `class != 0`；
       7. 匹配 query。
     - **等级可为 0**，原样返回、不伪造（:203-205；单测 `online_directory_unknown_level_test.go:16`）。
     - 通过者生成条目：`{candidate_player_id=id, is_online=true, last_active_ms=last_active_ts, name=trim 后的名字, level, class_id, gender, appearance_id, zone_id=调用者 zone, mutual_friends=0}`（:211）。
     - 本轮有通过者时，调一次 `BatchGetPlayerHomeZone(通过者)`；报错 → 报错；home zone ≠ 调用者 zone（含 0 和缺席）的删掉（:214-225）。
   - 按排序后的键顺序发放：条目缺失或 id 已在 skip 中 → 跳过；发放后加入 skip，实现页内去重（:134-140）。
6. **不排除好友，也不排除任一方向的拉黑关系**：整条路径不读 MySQL。

**`next_cursor` 规则**（:141-155）
- 在第 i 个键处凑满 limit：
  - 若 `i+1 < len(keys)` → `v1:<本轮 SCAN 用的游标>:<i+1>`；
  - 否则 → `encode(next, 0)`（next==0 时为 `""`）。
- 本轮没凑满：`pos = {next, 0}`；`next == 0` → 直接返回，`next_cursor = ""`，即使不足 limit。
- 4 轮用完仍没凑满、也没遍历完 → `next_cursor = encode(最后的 next, 0)`，**本页可以为空**（单测 :123）。
- 不承诺跨页快照，可能跳过或重复玩家；客户端按 uint64 id 去重，刷新时从空游标重新开始（:38-40）。

**客户端用法**（`mmorpg-client/Assets/Scripts/Game/Team/TeamInvitationDirectory.cs`）
- 请求：每页 `Limit=20, OnlineOnly=true, Cursor, Query`，不发 exclude（:20,197-198）；请求间隔 ≥2.5 s（:21,163-164）。
- 本地先拒 `Query.Trim().Length > 64`，按 UTF-16 长度（:114-117）。
- 应答处理顺序：
  1. 先看 error_message，非 0 → 失败（:203,270-274）；
  2. 再要求 `online_directory == true`，否则判定为"旧服"（:205）；
  3. 丢弃 `is_online=false` 的条目（:209）；
  4. **`next_cursor` 非空且等于请求游标 → 判定分页卡死**（:221-222）。
- 信封 tip 为 1005 / 1006 / 1008 / 1010 视为确定拒绝；其余（含 1003）要求重连（:247,261-268）。
- mmorpg robot 不覆盖 online_only。

**Java 落地**
- **枚举源**：对 `xm:presence:*` 做 SCAN，算法、游标格式、4 轮、64、1024 上限、字典序、offset 与 next_cursor 规则逐条照搬。
  - Redisson 公开 API 不暴露 SCAN 游标，可以在 Lua 里执行 `SCAN <cursor> MATCH xm:presence:* COUNT 64`，或走低层命令执行器。
  - Java 条目"存在、能解码、player_id 一致"就算在线。
- **资料与 home zone**：每轮一条 `SELECT player_id, zone_id, name, class_id, gender, appearance_id, level FROM player WHERE player_id IN (…)`。
  - 调用者的 home zone 也从 player 行取：行不存在或 zone_id==0 → 1003。
  - 过滤条件照搬：trim 后 name 非空、class_id≠0、home zone 相等、匹配 query。
- `last_active_ms = online_since_ms`。
- **配额键** `xm:friend:{<pid>}:dir-quota`：阈值 60、窗口 60 s、fail-closed、先校验后计数，全部照搬。
- **每页开销**：Redis ≤4 次 SCAN + ≤4 次 MGET + 1 次 EVAL；MySQL 1 次（调用者）+ ≤4 次批查。每轮开始检查截止时刻。
- SCAN 的成本隐患见 §9.1 第 20 条。

---

## 6 清理（sweep）

代码：`go/friend/internal/logic/sweep.go`、`go/friend/internal/data/sweep_repo.go`。

**节拍**
- 在 `metrics.Start` 之后启动 `StartSweep(ctx, deps)`（`go/friend/friend.go:183,199`）。SweepStore 为 nil 或 Interval≤0 → ERROR 日志，不启动（sweep.go:107-118）。
- 先等一个 `[0, Interval)` 的随机抖动，再进入 `safego.Loop`（sweep.go:123-136）：
  - ticker 驱动，首轮发生在"抖动 + 一个 Interval"之后；
  - 每轮在同一个 goroutine 里同步执行，不会叠轮；
  - 单轮 panic 只丢这一轮，计 `safego_panic_total{point="friend.request_sweep"}`（`go/shared/safego/safego.go:110-142`）。
- 多副本各跑各的，不选主（sweep.go:31-35）。停机不等它（friend.go:196-198）。
- 每轮超时 `min(Interval, 30s)`，两段共用（sweep.go:147-150,253-260）。
- 每轮的组织方式：
  - Mode 不是两个合法值之一时打**一次** ERROR，两段照常调用（SQL 侧只数不删，sweep.go:158-161）；
  - 先清终态申请，再回收容量行；**前一段失败不影响后一段**（sweep.go:139-165）。

**截止点**（`sweep_repo.go:98-121`）
- `batchLimit ≤ 0` → 报错；`retentionDays ∉ [1, 36500]` → 报错。
- `cutoff = nowMs − retentionDays × 86,400,000`。
- `cutoff ≤ 0` → ERROR 日志"检查进程时钟"，返回 `(0, 0, nil)`。负值与无符号列比较会匹配所有行，所以绝不能传下去。

**终态申请段**（`sweep_repo.go:138-285`）

1. 有界计数：

```sql
SELECT COUNT(*) FROM (SELECT 1 FROM friend_request WHERE status IN (2,3) AND updated_ms < ? LIMIT ?) AS bounded
```

   - 必须写 `IN (2,3)` 而不是 `<> 1`：这样 `updated_ms` 才是 `(status, updated_ms)` 索引的区间上界。status=0 的行永不清（:134-137,194-199）。
2. `pending == 0` 或 mode ≠ delete → 返回 `(pending, 0)`（:149-151）。
3. delete 模式的保险（:153-177,214-224）：

```sql
SELECT COUNT(*) FROM (SELECT 1 FROM friend_request WHERE status IN (2,3) AND updated_ms = 0 LIMIT ?) AS bounded
```

   - 结果 >0 → 打 ERROR，日志里带存量修法 `UPDATE friend_request SET updated_ms=request_time_ms WHERE status IN (2, 3) AND updated_ms=0`；返回 `(pending, 0, nil)`，**不删**。
4. 候选**普通读**，读完先关闭结果集：

```sql
SELECT from_player_id, to_player_id FROM friend_request WHERE status IN (?, ?) AND updated_ms < ? LIMIT ?
```

5. 逐行、**自动提交**、按完整主键删，WHERE 里做提交点复核：

```sql
DELETE FROM friend_request WHERE from_player_id = ? AND to_player_id = ? AND status IN (?, ?) AND updated_ms < ?
```

   - 每行之前检查 ctx；出错立刻返回**已删行数** + 错误（:264-285）。
   - 不许改成批量 DELETE：批量删先锁二级索引、后锁主键，与 upsert 的"先主键"反序，会 1213（:226-239）。
- **pending 永不清**（:13-18）。

**零好友容量行段**（`sweep_repo.go:287-388`）

1. 候选普通读，不加锁、不开事务，走 `(friend_count, created_ms)` 索引：

```sql
SELECT player_id FROM friend_capacity WHERE friend_count = 0 AND created_ms < ? LIMIT ?
```

2. `idle == 0` 或 mode ≠ delete → 返回 `(idle, 0)`。
3. 逐行处理：先检查 ctx（到期 → 返回 `(idle, deleted, err)`）；再执行

```sql
DELETE FROM friend_capacity WHERE player_id = ? AND friend_count = 0 AND created_ms < ?
```

   - 自动提交，WHERE 做提交点复核；累加 RowsAffected。
4. **没有** updated_ms=0 那种保险：created_ms=0 的存量零好友行可以直接回收（:65-66,297-298）。
- 任一时刻至多持一把守卫锁、不再等别的锁，所以不会与写路径成环。写路径缺行由 `runGuardedWrite` 吸收（:51-73）。

**指标与日志**（sweep.go:174-251）
- 两个 Gauge：`friend_sweep_pending_rows{mode}`、`friend_sweep_idle_capacity_rows{mode}`。两个 mode 启动时都预建为 0。
- 刷新纪律：
  - 只在合法 mode、只在成功时刷；
  - **每轮都刷，包括 0**；
  - 未知 mode 不刷；失败分支不刷。
- 值受 BatchLimit 封顶：等于 BatchLimit 只说明"≥ 一批"。
- 日志：
  - 终态段：delete 模式下 deleted>0 → Info；`pending>0 && deleted==0` → ERROR "WARN…"；report_only 且 pending>0 → ERROR "WARN…"。
  - 容量段：delete 模式下 deleted>0 → Info；delete 模式下"看到但没删"**不告警**；report_only 且 idle>0 → ERROR "WARN…"。
  - 失败时带"看到 N 行、已删 M 行"。
- sweep 不需要失效任何缓存。

**Java 落地**
- 用 `ScheduledExecutorService`：首次延迟 = 随机 `[0, Interval)` + Interval，之后按 Interval。
- 用 `scheduleWithFixedDelay`，或单线程加"上一轮没完成就跳过"，保证**不叠轮**。
- **每轮自己 catch Throwable**：未捕获的异常会让 `scheduleAtFixedRate` 静默停掉后续执行。
- 每轮设截止时刻，逐行删之前检查；JDBC 语句设 queryTimeout。
- SQL 逐字照搬。保留 updated_ms=0 保险和"默认 report_only"。
- 即使暂不移植容量行回收，`runGuardedWrite` 的缺行重试也照样保留，用例也照写。

---

## 7 服务 plumbing

### 7.1 基线拦截器链

执行顺序由外到内（`go/friend/friend.go:222-223,348-373`）：

1. **go-zero 自带拦截器**，含 Timeout 4000 ms 的超时拦截器，位于最外层。到点直接回 `DeadlineExceeded`；这就是要留 500 ms 回包余量的原因（`config.go:38-44`）。
2. **grpcstats**：被关停、被拒绝的请求也计数。
3. **killswitch**：命中规则即回 gRPC status（默认 `Unavailable`），不解码会话、不进业务（`go/shared/killswitch/killswitch.go:188-202`；`rule.go:24-32`）。
4. **session**（`go/friend/internal/session/session.go:84-125`）：
   - 没有 `x-session-detail-bin` → 当作内部调用**放行**（:90-94）；
   - 有但解不开（base64 → `SessionDetails`，**player_id==0 也算坏头**）→ `Unauthenticated`（:96-100,112-125）；
   - 方法不在白名单 → `PermissionDenied`（:101-105）；
   - 通过 → 把会话放进 ctx（:106）。
5. **serverbase**：只观测，读应答里的 `error_message.id`，按 Tip 表的 fault 列定性。

### 7.2 身份与方法准入

**基线**
- `requireCaller` 拿不到身份时打 ERROR，回 in-band 1005（`friend_logic.go:215-227`）。这一路只有不带 metadata 的内部直连走得到。
- 客户端在 EnterGame 之前发任何 friend 消息 → 会话拦截器判坏头 → **信封 1003**。

**Java**
- 身份 = `ClientCall.session.player_id`（`xm-api/src/main/proto/xm/api/client_call.proto:10-21`，player_id 在第 19 行）。
  - gate 在应用 EnterScene 指令时设置它，未进游戏为 0（`ClientDispatcher.java:406,699-709`）。
- 准入按**消息号**做，不能按服务名：
  - 10 个 C2S 消息号放行；
  - 235 → `tip_id=1003`；
  - `!call.hasSession() || player_id == 0` → `tip_id=1003`，打 ERROR；
  - 其余不认识的号 → 1013（契约里没有）或 1006（契约里有），沿用 login 的写法（`ClientMessageDispatcher.java:121-130`）。

### 7.3 超时与预算

**基线**
- 整请求业务预算 3500 ms；路由服转发超时 5000 ms。
- 为什么 friend 必须先于路由服超时：路由服如果先超时，客户端看到失败，但写已经落库，客户端一重试就撞 15003（`config.go:25-31`）。

**Java**
- gate 调后端的 Dubbo 超时是 5000 ms（`xm-gate/src/main/resources/application.yaml:70-73`）。friend 每个请求以"收到时刻 + 3500 ms"为截止时刻。
- **JDBC**：
  - 每条语句 `queryTimeout = ceil(剩余秒)`；
  - `socketTimeout` ≤ 4000 ms；
  - 会话 `innodb_lock_wait_timeout=3`（默认 50 s，远超预算）。
- **Redisson**：一律用 async API 加 `orTimeout(剩余预算)`。同步调用最坏要阻塞 4.2 s，已超过预算（`docs/design/architecture.md:415-417`）。
  - 申请配额超时 → 按 error 处理，fail-open。
  - 目录配额超时 → 1003，fail-closed。
- **线程**：阻塞 I/O 放在有界工作池里，照 `xm-login/.../dispatch/LoginWorkerPool.java` 的固定线程 + 有界队列 + AbortPolicy。
  - 不许在 Dubbo I/O 线程上做阻塞 I/O（`AGENTS.md` §3）。
  - 队列满 → in-band 1003 应答体（照 login 的 `route.failure`，`ClientMessageDispatcher.java:104-109,183-188`）。
- **Dubbo retries**：gate 对 friend 的 `@DubboReference` **必须显式写 `retries = 0`**，写路径不幂等。
  - 先例：`xm-gateway/.../GatewayDubboConfiguration.java:32`、`xm-login/.../LoginConfiguration.java:78`。
  - 现有 login 引用没有写 retries（`xm-gate/.../GateConfiguration.java:69-73`），是否一并修是待决项。

### 7.4 错误映射：基线客户端所见 → Java `ClientReply`

| 情形 | 基线客户端看到 | Java friend 返回 | gate 动作 |
|---|---|---|---|
| 成功 | 应答体，不带 error_message，可能 0 字节 | `body` 可为空，`tip_id=0` | 回包，`message_id` 与 `id` 回显请求（`ClientDispatcher.java:336-357`） |
| 业务拒绝或依赖故障 | 应答体 `error_message{id, [msg]}` | `body = XxxResponse{error_message}`，`tip_id=0` | 同上 |
| 会话 player_id=0 | **信封 1003** | `tip_id=1003` | 信封 |
| 上行 235 | **信封 1003** | `tip_id=1003` | 信封（Empty 应答只在 tip≠0 时回） |
| 请求体解析失败 | 信封 1003（gRPC Internal 经路由服翻译） | 建议 `tip_id=1003`，与基线对齐；login 的惯例是 1014（`ClientMessageDispatcher.java:170-178`），见 D11 | 信封 |
| 工作队列满 | 无直接对应（预算到期 → in-band 1003） | in-band 1003 应答体 | 回包 |
| friend 超时或不可用 | 信封 1003（路由服） | Dubbo future 异常 | gate 推 23{1003}，与 login 同形（`ClientDispatcher.java:326-328`），这是已有差异 |
| killswitch | 信封 1003 | gate 按客户端方法查（批次 1.3 补上，见 architecture.md §4.15） | 信封 1003 |
| 超频 | 信封 1008 | — | gate 拦截，计非法包（`ClientDispatcher.java:204-211`） |
| 包体超过 1 KB | 信封 1010 | — | gate 拦截（`ClientDispatcher.java:198-203`） |

### 7.5 gate 接入 friend 域

- 在 `DubboGroups` 加 `FRIEND = "friend"`。
- 在 `MessageRoutes.SERVICE_BACKENDS` 加 `"ClientPlayerFriend" → DubboGroups.FRIEND`（`MessageRoutes.java:27-29`）。
- `ClientDispatcher.dispatch` 目前把非 login / scene 域推成 23{1003}（:267-281）。要按 `callLogin` 的形状加一个通用后端分支：回调回到会话 EventLoop，`xm_gate_backend_calls_seconds` 加 `backend=friend`。
- **不要占用会话唯一的 `inFlight` 位**。
  - login 调用在途时，该会话的所有后续请求都排队（`ClientSession.java:67`；`ClientDispatcher.java:257-265,290`）。
  - friend 最坏要 5 s；移动等 scene 消息排满 `max-pending-requests=64`（`application.yaml:55`）就会**断开连接**（`ClientDispatcher.java:223-229`）。
  - 建议 friend 域用独立的按域在途队列：friend 请求之间仍串行，保证写后读；但不阻塞 scene 和 login。
  - 基线的 C++ gate 在路由模式下本来就不做按会话串行（`client_message_processor.cpp:804-819`）。

---

## 8 指标

### 8.1 基线

代码：`go/friend/internal/metrics/metrics.go`。label 全是有限枚举，player_id 只进日志（:5-10）；`/metrics` 在 `:9180`（`friend.yaml:39`）。

| 指标 | 类型 | label | 触发 | 出处 |
|---|---|---|---|---|
| `friend_push_total` | Counter | `reason` = request_received / request_accepted；`outcome` = ok / offline / error | §4.3 | :63-70,112-116 |
| `friend_rate_quota_total` | Counter | `outcome` = allowed / rejected / error（error 是配额 fail-open 的唯一信号，**必须配告警**） | §2.1 第 4 步 | :72-78,118-124 |
| `friend_online_lookup_total` | Counter | `outcome` = ok / offline / error，每个去重后的 id 计一次 | §4.1 | :80-87,126-130 |
| `friend_sweep_pending_rows` | Gauge | `mode` | §6 | :89-96 |
| `friend_sweep_idle_capacity_rows` | Gauge | `mode` | §6 | :98-107 |
| `rpc_inband_fault_total{method,source,code}`、`rpc_inband_reject_total{method,source}`、`rpc_duration_seconds{method,status}`（ok / biz_reject / fault / unknown_code / transport_error） | serverbase | — | 每个 RPC；1003 记 fault，其他码记 reject | `go/shared/serverbase/metrics.go:21-56`；constants.go:250-268 |
| `killswitch_blocked_total`、`safego_panic_total{point}` | 共享 | — | — | friend.yaml:34-38 |

- 指标在 `metrics.Start` 里注册，并**预建全部 label 组合**（:146-180）：push 6 条、quota 3 条、online 3 条、两个 Gauge 各 2 个 mode。
  - 不预建的话，"从未发生"的序列根本不存在，`rate(...{outcome="error"}) > 0` 这类告警既不报警也不报错。
- 在线目录配额、缓存命中 / 回填 / 失效、守卫重试都**没有**指标，只有日志或进程内计数。

### 8.2 Java 建议

约定：一个 `FriendMetrics` 类，Micrometer，启动时注册全部组合。不以 player_id / zone 作 label（`docs/design/architecture.md:528,546-549`；`AGENTS.md` §5）。前缀随进程名，独立进程 xm-friend → `xm_friend_`。

| 指标 | 类型 | label |
|---|---|---|
| `xm_friend_requests_seconds` | Timer（与 gate / login 同一套 SLO 桶） | `method` = ClientPlayerFriend.方法名 / unknown；`result` = ok / business_error / internal_error / overloaded / bad_request / unauthenticated / forbidden / unsupported |
| `xm_friend_pushes_total` | Counter | `reason` = request_received / request_accepted；`outcome` = ok / offline / error |
| `xm_friend_request_quota_total` | Counter | `outcome` = allowed / rejected / error |
| `xm_friend_online_lookups_total` | Counter | `outcome` = ok / offline / error |
| `xm_friend_sweep_pending_rows`、`xm_friend_sweep_idle_capacity_rows` | Gauge | `mode` = report_only / delete |
| `executor_*{name="friend-worker"}` | 线程池标准指标 | — |

`result` 各取值的含义：
- business_error：应答体里是非 1003 的 tip；
- internal_error：应答体里是 1003；
- overloaded：工作队列满；
- bad_request：请求体解析失败；
- unauthenticated：player_id=0；
- forbidden：上行 235；
- unsupported：不认识的消息号。

Java 增项（都是低基数，基线没有）：
- `xm_friend_directory_quota_total{outcome}`
- `xm_friend_cache_total{cache=list/pending, result=hit/miss/fill_skipped/fill_failed/error}`
- `xm_friend_cache_invalidation_failures_total`
- `xm_friend_guard_retries_total{kind=missing_row/ensure_deadlock}`
- `xm_friend_count_underflows_total`

---

## 9 隐患与边界清单

### 9.1 隐患（基线自身，或移植时会踩的）

**并发与锁**

1. **事务内的 1213 不重试**，直接是玩家可见的 1003。只有事务外的 ensure 有重试（`friend_repo.go:743-747,1052-1083`）。
2. **pending 计数必须保持普通读**。改成 FOR UPDATE 会在不相干的两对玩家之间成环（`friend_repo.go:411-430`）。新增任何会让 pending 变多的写路径，都必须先拿守卫。
3. **守卫之后只许完整主键点锁**。新增 UNIQUE KEY 会推翻这一前提（`friend_repo.go:202-204`），pbmysql 生成表时必须确认没有唯一键。
4. **连接池必须是 RC**。只有事务设 RC 不够：自动提交语句会落回 RR，拿到间隙锁和 next-key 锁（`servicecontext.go:177-186`）。

**容量行与计数**

5. **AddFriend 对任意 target 都会补容量行**，目标不存在也补，事务回滚后也还在（`friend_repo.go:363-368`）。增长速度由配额（10/min，fail-open）挡；存量靠 sweep 回收。
   - **Block + Unblock 换目标可以绕过名额探针**，只有 gate 5/s 挡（`block_repo.go:74-78`）。
6. **计数下溢被拦住时不回滚**，只打 ERROR。说明计数已与边数脱节，需要人工排查（`friend_repo.go:945-964`）。
7. **陈旧 ensure 导致计数偏大**，靠"减计数时刷新 created_ms"挡住。前提是陈旧窗口跨不过整个 RetentionDays（`friend_repo.go:180-186`）。

**缓存**

8. **缓存 JSON 解码失败是 fail-closed**：一个坏值会让该玩家的 12 / 230 持续回 1003，直到写路径失效或 30 分钟 TTL 到期（`friend_repo.go:1163-1166`）。
9. **失效失败最多陈旧 30 分钟**，且没有指标，只有日志（`friend_repo.go:1008-1021`）。
10. **失效必须先于推送完成**，否则对方收到推送后可能拉到旧列表（§3.4）。
11. **回填锁不感知超时**：Go 的 `sync.Mutex` 等待者会越过自己的预算；锁条目永不回收（`friend_repo.go:1121-1132`）。
12. **代次键永久存在且由客户端驱动无界增长**。
    - 每个被失效过的 pid 会留下 1–2 个无 TTL 的代次键，从不删除（`friend_repo.go:301-305`）。
    - AddFriend 对任意不存在的 target id 也会 INCR `req(target)` 的代次键（:489-491）；Block 会给 target 建 `list` 和 `req` 两个代次键（`block_repo.go:189-194`）。
    - sweep 不清这些键。见 D6。
13. **代次键被淘汰会引入 ABA**：读者读到 "1"，代次键被淘汰，两次 INCR 后又回到 "1"，旧快照就能落地。基线靠 FriendRedis 设 noeviction 避免（`config.go:88-95`）。Java 的单 Redis 必须是 noeviction 或 volatile-*，或者采用 D6。

**在线读、资料与推送**

14. **在线读的部分结果不能用**：err≠nil 时 map 里仍有健康条目，GetFriendList 必须整体回 1003（`friend_logic.go:450-453`）。
15. **身份校验不一致**：批量在线读校验 player_id，单条推送读 `loadPlayerSession` 不校验（`push.go:167-181`）。Java presence 两处都校验，更严。
16. **资料补全按批中断**：data_service 或 Redis 抖一下，第 k 批之后的好友全都没有名字 / 等级 / zone，客户端会把 zone 覆盖成 0（§4.2）。
17. **推送是 at-most-once**；DISCONNECTING 状态不推；空 gate_instance_id 和非数字 gate id 都 fail-closed（`push.go:3-10,112-117`；`gate_push.go:53-66`）。

**推荐与在线目录**

18. **随机推荐依赖优化器选 range 访问**；统计陈旧时会退化成全扫，没有运行期兜底（`recommend_repo.go:35-46`）。
19. **在线目录配额 fail-closed**；被拒的请求也计数；非法输入不计数。它的阈值 60/分钟与 gate 对 119 的 1/s 叠加生效。
20. **在线目录的 SCAN 成本与整个 Redis DB 的键数成正比**，不只与在线人数有关。Java 的 DB 12 装着全部 `xm:*` 键，加上 friend 自己的缓存和代次键，会让每页更容易是空页。客户端支持"空页 + 非空游标"，但体验会变差。备选方案见 §10.3。
21. **在线目录不排除好友，也不排除任一方向的拉黑**：被我拉黑的人、拉黑了我的人都会出现（data/online_directory.go 全程不读 MySQL）。

**包体与协议**

22. **单包大小**：1000 条带 name / appearance 的 FriendEntry 可能超过 64 KB 帧上限（`xm-net/.../client/ClientFrames.java:34`）。MaxFriends ≤300 时正常数据量不会触发，但 LIMIT 1000 是防御值。
23. **RecommendFriendsRequest 可能超过 1 KB**：64 个 exclude（约 9–10 字节 × 64）+ 48 字节游标 + 64 个四字节 rune 的 query + 外层 ClientRequest，接近 1 KB 上限。超了 gate 回信封 1010，并计非法包（`ClientDispatcher.java:198-203`）。Unity 不发 exclude，所以影响限于 robot 和第三方客户端。
24. **列表 LIMIT 不带 ORDER BY**：被截断时返回哪一部分取决于执行计划。入站申请通常按 from_player_id 升序，不是按申请时间（`friend_repo.go:1221-1224`）。

**Java 专有**

25. **uint64 与 Java long**：客户端可以发 ≥ 2^63 的 target 或 exclude id。Java long 是负数，按有符号绑定进 BIGINT UNSIGNED 列，在 STRICT 模式下会报越界 → 1003 fault 告警；基线 Go 能正常处理。所以所有 id 必须按无符号绑定与解析（§1.7）。
26. **gate 唯一的 inFlight 位**：friend 照搬 callLogin 会阻塞 scene 消息，最坏导致断线（§7.5）。

### 9.2 边界速查

| 场景 | 结果 |
|---|---|
| 自己加自己 | 15000；不消耗配额 |
| 自己拉黑自己、解除自己 | 1005 `"target_player_id invalid"` |
| 删自己 | 成功，不做任何 I/O |
| 同意自己、拒绝自己 | 1005 |
| target 或 from 为 0 | 1005 |
| AddFriend 时对方已向我申请 | 双方各一条 pending，不会自动成为好友 |
| Accept 时反向 pending 存在 | 一并置为 accepted |
| 拉黑后去 Accept | 正常 15005；竞态时 15007 |
| 同时满足"拉黑"与"已申请" | 15007 |
| 重复 Block | 成功，不占名额，仍做删边、取消 pending、失效缓存 |
| Unblock 未拉黑的人 | 成功 |
| Unblock | 不恢复好友 |
| RemoveFriend 非好友 | 成功，不 ensure、不失效 |
| Reject 不存在的申请 | 15005，不失效 |
| 在 EnterGame 之前调用任何 friend 消息 | 信封 1003 |
| 上行 235 | 信封 1003 |
| 推荐 limit=0 或 >20 | 取 10 / 20；exclude 恰好 64 条放行，65 条拒 |
| 在线目录的 `v1:0:0` | 可以解析，永远不会被产出；`v1:0:N` 是合法产出 |

### 9.3 基线注释或文档与代码不一致的地方（以代码为准）

| 位置 | 说法 | 实际 |
|---|---|---|
| `proto/friend/friend_table.proto:65` | request_time_ms 是"首次发起时刻" | 重新申请会刷新（friend_repo.go:467-480） |
| `friend_table.proto:116` | ensure 用 INSERT IGNORE | 实际是 ODKU（friend_repo.go:848-851） |
| `friend_table.proto:50-51` | 重复点加好友是"REPLACE 语义" | pending 时回 15003，终态时 upsert |
| `friend_table.proto:136` | AddFriend 按 blocked_player_id 索引判拉黑 | 现在是双向主键点查 |
| `friend_repo.go:346-349` | "logic 层保留的预检" | logic 层没有任何事务外预检（friend_logic.go:277-278） |
| `friend_repo.go:986-987` | "守卫的 len 校验会报错" | lockCapacityRows 里没有长度校验（:820-836） |
| `friend_logic.go:76` | 引用 friend_repo.go:66-72 | 实际在 :71-79 |
| `friend_logic.go:93,302` | 引用 friend_repo.go:27-40 | 实际在 :28-41 |
| `config.go:176` | RequestQuotaPerMinute 是"滑窗配额" | 是固定窗口（rate_quota.go:38） |
| `config.go:219-220`、`friend.yaml:164-165` | BatchLimit 是"一次 DELETE 的行数上限 / 长事务" | 现在是候选读 + 逐行删 |
| `sweep_repo.go:61-63` | 自动提交语句"走连接默认隔离级别而不是 RC" | DSN 已把整个池设成 RC（servicecontext.go:177-193） |
| `session_reader.go:248` | 日志"N 名玩家本次按离线返回" | GetFriendList 走严格读、回 1003，并不按离线返回 |
| `block_repo.go:76-78` | 待办：Block 要进 MessageLimiter | 已完成（messagelimiter.json:394-404） |
| `friend_guard_lock_order_mysql_test.go:24-26` | 守卫之后的一切判定读都要 FOR UPDATE | pending 计数与黑名单计数是刻意用普通读的 |
| `friend_logic_test.go:869-875` | rate_quota 的 error 序列没有预建 | 已预建（metrics.go:169-171） |
| `robot/friend_smoke_scenario.go:249` | RemoveFriend 对非好友回 NoPending 家族的码 | 回成功 |
| `robot/friend_smoke_scenario.go:328-332` | 会话读失败会 fail-open 成"全部离线" | GetFriendList 已改为严格读、回 1003 |

### 9.4 对三份分区规格与 `docs/porting/inventory/social.md` 的勘误

**对三份分区规格**
1. gate 限频不全。原稿只列了 5/s 的六个消息号。实际上 12 / 230 / 2 是 **10/s**，119 是 **1/s**，235 不在表里、缺省 3/s（messagelimiter.json:350-410）。
2. Block 的事务内锁序缺了 ④ ODKU 插入，以及"删一条边就紧跟着改一次计数"的交错（§1.4 表）。
3. 漏了"失效必须先于推送"这个时序约束（§3.4）。
4. 漏了代次键由客户端驱动的永久增长（§9.1 第 12 条）。
5. 漏了两个 Java 隐患：gate 唯一的 inFlight 位（§7.5）、uint64 绑定（§9.1 第 25 条）。
6. 漏了 119 请求可能超过 1 KB（§9.1 第 23 条），也漏了在线目录 SCAN 在 Java 单 DB 上的成本（§9.1 第 20 条）。
7. 原稿之间的 Redis 键名和指标前缀不一致。统一为 `xm:friend:{<pid>}:*` 与 `xm_friend_*`（§3.5、§8.2）。
8. 原稿没提 presence 的 `zone_id` 是 gate 的区、不是 home zone（§4.1）。
9. 原稿没提 Java 游标解析要防非 ASCII 数字（§5.2）。

**对 social.md**
1. `:13`、`:30` 引用的 `ClientDispatcher.java:254` 已经移到 `:274-279`。
2. `:28` 写"会话缺失 → in-band 1005"；对客户端来说实际是**信封 1003**。
3. `:42`、`:54` 写 "java: missing"，已过时：`PlayerPresenceDirectory`、`PlayerPushes`、`GatePushSubscriber` 都已存在（`PARITY.md:54-55`）。现在缺的是严格批量读和 friend 本身。
4. `:52` 写"身份不符算离线"，不准确：解码失败和身份不符都是 **error**（GetFriendList 回 1003），只有空值和非 ONLINE 才是离线。
5. `:91` 写"1213 重试 3 次"：只适用于事务外的 ensure；事务内的 1213 不重试。
6. `:95` 写的锁序"容量行 → 拉黑行 → 好友边 → 申请行"对 Accept、Block 不成立。真正的不变量是"守卫最先 + 主键点锁"。
7. `:114` 需要补充：Accept 的事务外前置先于拉黑判定，所以拉黑之后去 Accept 正常拿到的是 15005。
8. `:138` 需要补充：资料补全失败是**按批中断**。
9. `:198` 有四处要改：
   - "online_directory 恒为 true（含出错时）"：身份缺失和 exclude 超限这两个拒绝**不带** online_directory；
   - "非法 UTF-8 → 1005" 实际走不到；
   - 在线目录**也包含拉黑关系的玩家**；
   - 页长是默认 10、上限 20，data 层的 12 / 50 是死代码。
10. `:215` 建议"可以简化 updated_ms=0 保险"：本稿建议**保留**。

---

## 10 Java 版建议的有意差异

### 10.1 建议采纳（理由充分）

| 编号 | 差异 | 理由 | 客户端可见？ | 是否需要两版同改 |
|---|---|---|---|---|
| D1 | 在线状态源改为 `xm:presence`：带 TTL，gate 写，没有 DISCONNECTING 状态 | Java 架构已定（`PARITY.md:54`，architecture.md §4.3）；断线即离线，与基线把 DISCONNECTING 判为离线等价 | 轻微：EnterGame 到场景确认之间的秒级窗口里，is_online 为 false | 否，已登记 |
| D2 | `last_active_ms` 用 `online_since_ms` 填 | 与基线语义（上线 / 重连时刻）相同；robot 要求非 0 | 否（语义等价） | 否 |
| D3 | 推送走 Redis pub/sub + 玩家栅栏；`GATE_UNREACHABLE` 记 error | Java 架构已定（`PARITY.md:55`）；栅栏防止推错人 | 否 | 否，已登记；指标口径需登记 |
| D4 | 展示资料来自 `xm_java.player` 表，不用 PlayerAllData 缓存和 data_service | Java 没有这两套组件；表里字段齐全，name 恒非空，zone_id 恒为 home zone | 是：Java 的 name / zone 更完整；level 到存盘才更新，在线玩家可能滞后；新角色 level 是 1 不是 0 | 登记 PARITY 即可 |
| D5 | 在线目录的枚举源改为 SCAN `xm:presence:*`，home zone 取自 player 行 | 数据源变了，算法、游标、过滤与形状逐条保持 | 否（形状一致） | 登记 |
| D6 | 代次键改为"失效时 `SET gen <UUID> PX 2×CacheTTL` 再 `DEL data`"，回填时比较字符串，代次缺失按 "0" | 消除 §9.1 第 12 条（永久键无界增长）和第 13 条（ABA）。唯一值不会重现，ABA 不可能发生；代次键只在超过 2×CacheTTL 没有写入时才过期，远大于单次读的耗时，过期也无害 | 否（内部） | 否（可选；不采纳就必须要求 Redis 用 noeviction） |
| D7 | 回填单飞改为可回收、限时等待 | 基线的锁不感知超时、条目不回收（§9.1 第 11 条） | 否 | 否 |
| D8 | gate 给 friend 域独立的在途队列 | 避免 friend 阻塞 scene、排满 64 而断线（§7.5） | 否 | 否 |
| D9 | GetFriendList 用严格在线读 | 这是**对齐**而非差异；Java 需要新增 `findAllStrictAsync` | 否 | 否 |

### 10.2 列出但不建议采纳（记录决策）

- **D10：AddFriend / Block 前先查 player 表，校验目标是否存在**。
  - 理由：能挡住"任意 id 造容量行、造代次键"。
  - **客户端可见**：基线对不存在的 id 回成功。改成拒绝要定 tip：1005 有现成文案；新开好友段码则要先改 mmorpg。
  - 不建议的原因：mmorpg friend 没有玩家名册，D-14 又禁止跨库访问（`recommend_repo.go:63-66`），基线**做不到**这一点。这会形成永久的单边差异，违背双版本纪律（`AGENTS.md` §1）。
  - 结论：首批保持基线行为（回成功并补行），靠配额、gate 限频、sweep 回收和 D6 控制增长。
- **D11：请求体解析失败回 1014（login 惯例）**。
  - 基线的 friend 在这种情况下客户端看到的是信封 1003。
  - 建议 friend 跟随基线回 1003；若要统一成 1014，需登记为客户端可见差异。

### 10.3 待用户拍板的决策

1. friend 四张表的事实源：
   - 直接用同步来的 `xm-proto/.../friend/friend_table.proto` 经 pbmysql 建表——这些是契约同步产物、禁止手改，Java 存储会随 mmorpg 漂移；
   - 还是 Java 自有一份同形的 proto。
2. 进程形态：独立的 xm-friend（路线图写的是"每个服务一个进程"），还是与 chat 合并成 xm-social。这决定了指标前缀。
3. 生产 Redis 的 `maxmemory-policy` 能否定为 noeviction 或 volatile-*；以及是否采纳 D6。
4. gate 现有的 login Dubbo 引用要不要一并补上 `retries = 0`（`GateConfiguration.java:70`）。
5. 是否给在线目录维护专用的在线索引（例如按区的 SET，用 SSCAN），以避免全 DB 的 SCAN 成本（§9.1 第 20 条）。
   - 代价是多一个写者，而且 gate 不知道玩家的 home zone。
   - 首批建议先用 SCAN。
6. 是否首批就移植 sweep 的容量行回收。默认是 report_only；不移植的话，缺行重试路径实际走不到，但骨架和用例建议照样保留。
7. 列表 SQL 是否追加与索引同序的 `ORDER BY`：不引入 filesort，又能让截断结果确定。若追加，属于内部差异。