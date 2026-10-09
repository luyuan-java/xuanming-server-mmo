# Java 版架构

> 本仓库是 mmorpg（C++ 节点 + Go 微服务）的 Java 版，两版并行演进（mmorpg `AGENTS.md` §12）。
> 选型见 [tech-stack.md](tech-stack.md)，对账见根目录 `PARITY.md`。

## 1. 边界：什么必须兼容，什么按 Java 方式重做

**必须与 mmorpg 逐字节兼容的只有客户端契约**，这样同一个 UE 客户端和 Go robot 能连两个版本：

- 客户端 ↔ gate 的 TCP 帧格式与握手（§3）；
- `proto/` 里客户端可见的消息、`message_id.txt` 的消息号、tip 码；
- 配置表数据（导表器产物）；
- 客户端访问的 HTTP 接口（区服列表、分配 gate）的 JSON 形状。

这些都来自 mmorpg，由 `tools/ContractSync.java` 同步进来（记录源 commit 于 `contract/SOURCE.properties`），**不许手改**。

**Java 版的目录、模块、工具全部按 Java 标准做法自行组织，不对应 mmorpg 的目录结构**（用户 2026-09-29 定；mmorpg 之后也会整理目录）。
唯一例外是同步来的契约 proto 源文件：按源仓库相对路径原样存放（import 路径写在源文件里），视作第三方快照。
Java 代码不得依赖这套目录，具体做法：

- 生成类的 Java 包名只由 proto `package` 声明决定：无 package → `com.game.proto`；有 package → `com.game.proto.<package>`
  （去掉 Go 风格的 `pb` 后缀，`loginpb` → `com.game.proto.login`）。
- gate 的路由按服务语义决定：标了 `OptionIsPlayerService` 的客户端服务由 scene 处理；其余客户端服务查 gate 的显式路由表
  （服务名 → 后端，`MessageRoutes.SERVICE_BACKENDS`；首批只有 `ClientPlayerLogin` → login，现在是七个后端域：login / friend / chat / team / guild / trade / match，
  批次 6.4 接入 `MatchService` → match 之后契约里已没有未接入的客户端服务）；表里没有的回「暂不支持」，战斗服务 `BattleClientPlayer` 只走直连、在大厅连接上当场拒绝（§4.22）。

**服务端内部全部按 Java 惯用方式重做**：服务间 RPC 用 Dubbo，注册发现用 Nacos，存储用 MyBatis + Redisson，
节点间长连接用 Netty 自带的 protobuf 编解码。Java 版有自己的库表与 Redis 键空间，**不与 C++/Go 版混部、不共享存储**。

## 2. 进程与模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `xm-proto` | 库（同步产物） | 客户端契约 proto；`MessageIdRegistry`（消息号 ↔ 服务 / 方法 / 请求应答类型） |
| `xm-table` | 库 | 配置表：同步来的权威 schema（`cfg_*` option）与表数据；`com.game.table.ConfigTables` 与各表 `<Sheet>Rows` 编译期生成；手写运行时 `com.game.table.load`（manifest 校验、解析）。见 [config-tables.md](config-tables.md) |
| `xm-table-codegen` | 库（编译期） | javac 注解处理器：读 protoc 描述符集，按 schema 生成类型安全的表访问代码；不进运行时 |
| `xm-common` | 库 | 雪花 ID、节点号租约、gate 令牌签名、battle 直连票据签名与密钥强度判定（`BattleTickets` / `BattleSecretPolicy`）、时间源、无符号数换算（`Unsigned`）等无框架公共件 |
| `xm-net` | 库 | Netty：客户端帧编解码（兼容 C++ `ProtobufCodec`，逐帧分发，§3）、节点链路编解码；按消息号限频（`com.game.net.limit`，读 MessageLimiter 表，gate 与 battle 直连面共用） |
| `xm-battle-engine` | 库（纯 Java） | 回合制战斗确定性引擎（批次 6.1）：开局、行动校验、出手序、普攻 / 防御 / 逃跑、技能、buff 回合化、道具、掉落、结算、快照，随机数逐位照搬 mt19937_64；战斗配表指纹。不依赖 Spring / Netty / Redis / 日志，单线程对象（由 6.2 的房间串行驱动）；6.3 的 scene 只用 `BattleRules` 与指纹。规格 `docs/porting/battle-engine-spec.md` |
| `xm-pbmysql` | 库（纯 JDBC） | proto → MySQL 表映射（用户自有 proto2mysql 的 Java 实现）：建表 DDL、只扩不缩的结构同步、按消息 CRUD（§7） |
| `xm-api` | 库 | Dubbo 服务接口、调用方鉴权过滤器（§4.1）与内部 protobuf 消息（包 `xm.api`，只在 Java 版内部使用）；按节点直连的通用 Dubbo 客户端 `com.game.api.rpc.NodeRpcClients<S>`（批次 6.3 的 battle → scene 先用到，§4.23）；批次 6.4 的匹配端口 `MatchTeamService` / `MatchInternalService`（消息在 `xm/api/match_control.proto`）、跨进程时限常量 `com.game.api.match.MatchBudgets` 与预算附件 `MatchRpcAttachments`（§4.24） |
| `xm-discovery` | 库 | Redis（Redisson）上的节点号租约、游戏节点在线目录、玩家在线目录与服务端推送（含同一会话按序的批量推送 `MessageBatch`，§4.3）；回合制战斗的 Redis 键、全部 Lua 与跨进程常量（`com.game.discovery.battle`：`BattleRedis`、`BattleLockReader`，scene 与 battle 共用，§4.23）；匹配的键名（`RedisKeys` 的 `match*` 一组，全部带 hash tag `{match}`；批次 6.5 加观战的两个键 `matchWatching` / `matchWatchable`）与节点类型 `NodeTypes.MATCH`（§4.24；匹配的 Lua 在 xm-match 自己的包里）；批次 6.5 的 `com.game.discovery.battle.BattleRoutings`（在线目录 → 观众路由的 gate 部分，xm-match 的 163 与 xm-battle 的 dev 接口共用，§4.24.1） |
| `xm-player-store` | 库 | 账号 / 玩家持久化（MyBatis）与玩家归属围栏（含跨节点换图的原子交出与探测，§7 第 6 步） |
| `xm-gateway-store` | 库 | 区服目录 / 白名单 / 登录公告的库表与读写（MyBatis）：xm-gateway 建表并读，xm-data 运维接口写 |
| `xm-audit` | 库 | 资产审计管线的共享件：Java 自有的流水消息格式（包 `xm.audit`）、Kafka topic 规格（带代次后缀）与启动期核对（§4.5）；批次 6.4 起对局结果 topic 的规格件 `BattleResultTopics` 也放在这里（xm-battle 生产、xm-match 消费，§4.24） |
| `xm-gateway` | 进程（Spring Boot Web + Dubbo 调用方） | 区服列表（区服目录 + 健康探测）、分配 gate 并签发 gate 令牌（区服准入）、HTTP 登录与刷新令牌（经 Dubbo 调 xm-login）、登录公告 |
| `xm-login` | 进程（Spring Boot + Dubbo） | 登录（含 access / refresh 令牌、设备数上限）、建角、进游戏、离开 / 断线 |
| `xm-friend` | 进程（Spring Boot + Dubbo） | 好友：申请 / 同意 / 拒绝 / 删除 / 列表 / 黑名单与好友事件推送（Dubbo group `friend`，端口 20883；四张表经 xm-pbmysql，§4.13） |
| `xm-chat` | 进程（Spring Boot + Dubbo） | 聊天 v1：世界频道与私聊的发言（校验、幂等、限速、落历史）与拉取历史（Dubbo group `chat`，端口 20884；数据只在 Redis，§4.14） |
| `xm-team` | 进程（Spring Boot + Dubbo） | 组队：建队 / 申请 / 邀请 / 离队 / 踢人 / 转让 / 解散、队伍快照与邀请推送（Dubbo group `team`，端口 20885；权威数据只在 Redis，§4.16）；批次 6.4 起整队开战（211）持开战锁与编排，预检 / 建票 / 开局经 Dubbo 调 xm-match 的 `MatchTeamService`（§4.24） |
| `xm-guild` | 进程（Spring Boot + Dubbo） | 帮会核心：建 / 查 / 退 / 解散 / 公告 / 任免 / 踢人 / 转让 / 申请审批 / 推送 220 / 排行（Dubbo group `guild`，端口 20886；四张表经 xm-pbmysql，快照缓存与排行在 Redis，§4.17） |
| `xm-trade` | 进程（Spring Boot + Dubbo） | 聚宝斋只读面：浏览 196 / 详情 197 / 收藏 198 / 货架 200 + dev 播种（Dubbo group `trade`，端口 20887，管理端口 18111；两张表经 xm-pbmysql，§4.20） |
| `xm-match` | 进程（Spring Boot + Dubbo） | 匹配（批次 6.4）：排队 157 / 148 / 153、凑单、开局管线（备战 → 建房 → 置 ready，失败补偿）、战斗票据补签 179、评分与对局结果回流、切磋 152 / 151、帮会活动开战与整队开战的 match 侧（Dubbo group `match`，端口 20888，管理端口 18113（含 dev / test 管理口）；排队与票据、切磋、落点记录在 Redis（全部 `xm:{match}:*`），评分两张表经 xm-pbmysql，消费 Kafka 的对局结果 topic；是 xm-scene `SceneBattleService` 与 xm-battle `BattleNodeService` 的 Dubbo 调用方，§4.24）。批次 6.5 起补上观战的 match 侧：163 WatchBattle / 164 可观战列表、观战标记与可观战索引（同在 `xm:{match}:*`）、开局前清退观众与开局后公开，按落点记录的地址直拨 xm-battle 的 `addObserver` / `removeObserver`（§4.24.1） |
| `xm-scene-manager` | 进程（Spring Boot + Dubbo） | 场景分配（玩家该进哪个场景节点的哪个**主世界频道**，软预占；断线重连可回原实例）+ 每个 zone 的主世界频道计划（领导者维护，§4.19）+ 在线换图选跨节点目标（`selectSwitchTarget`，只选不铸 epoch，§8.1）+ 镜像 / 副本实例取号（`createInstance`，只发全服 scene_id、放置恒为发起节点，无状态，§4.21） |
| `xm-gate` | 进程（Spring Boot + Netty） | 客户端接入、会话、按消息号路由、下行推送（含按序批量推送，§4.3）；跨节点换图时按 scene 的改绑指令把会话改绑到目标节点（§4.2）。不承载任何战斗上行（战斗走 battle 直连，§4.22） |
| `xm-scene` | 进程（Spring Boot + Netty） | 场景与玩家逻辑（单线程拥有场景状态）；跨节点换图的源端（选目标、冻结、交出归属）与目标端（交出进场，§8.1）；镜像 / 副本实例（节点自有：63 建镜像、空闲回收与级联、dev / test 管理口建 / 毁副本，§4.21）；回合制战斗的 scene 侧（批次 6.3：备战冻结与在途闸、结算应用与账本、进场恢复，Dubbo 提供方 `SceneBattleService`，§4.23） |
| `xm-battle` | 进程（Spring Boot + Netty + Dubbo 提供方） | battle 节点（批次 6.2）：回合制战斗房间的生命周期（驱动 `xm-battle-engine`）、客户端直连面（端口 12000）、票据签发与补签、推送出口（直连 / 经 gate 回落）、准入与停机、观战的房间侧；控制面 `BattleNodeService`（Dubbo group `battle-node`，端口 21200，按节点直连），管理端口 18112（含 dev / test 建房接口）；不用 MySQL（§4.22）。批次 6.3 起是 scene `SceneBattleService` 的 Dubbo 调用方：确认的传输、结算发件箱、活动结果通道（§4.23）。批次 6.4 起控制面的调用方是 xm-match，对局结果经 Kafka 发布（`KafkaBattleResultSink`，topic `xm-battle-result-g<代次>`，§4.22、§4.24） |
| `xm-data` | 进程（Spring Boot Web + Dubbo 调用方） | 审计与运维数据服务：消费审计 topic、幂等落 MySQL；带令牌的运维接口：流水查询、GM 快照 / 差异、物品追溯、批量回收 dry-run（§4.5，批次 7.2a），运维作业框架、GM 回档与整区维护前快照（§4.5.1，批次 7.2b），全服产出封禁（§4.6），区服目录 / 白名单 / 登录公告（§7）。运维作业表经 xm-pbmysql。玩家数据：平时只读（自有只读 Mapper）；回档时以运维身份夺取归属、带 `owner_epoch` 围栏写（复用 xm-player-store 的 `PlayerStore` / `PlayerMapper`，§7 第 7 步）。回档的帮会资产检查是 `GuildInternalService` 的 Dubbo 调用方（编程式引用、只直连，写开关关闭时不起 Dubbo） |

依赖方向单向：进程模块 → `xm-api` / `xm-net` / `xm-player-store` / `xm-gateway-store` / `xm-discovery` / `xm-battle-engine` → `xm-common` / `xm-proto` / `xm-table`。

本机切片（`tools/local/start-slice.sh`，local profile）缺省把上表 13 个进程模块各起一个实例。两个开关只多起实例、不多出模块：`XM_SCENE_NODES=2` 在区 1 再起一个场景节点 `xm-scene-2`（§8.1）；
`XM_ZONES=2`（批次 6.5）再起区 2 的一个场景节点 `xm-scene-z2` 与一个 gate `xm-gate-z2`，其余进程两个区共用——它们要么不分 zone（xm-match、xm-battle、社交与经济后端、xm-data），要么按请求、会话或位置记录里的 zone 行事（xm-gateway 按请求的区选 gate，xm-login 按会话的区选 scene，xm-scene-manager 对每个区各自维护频道计划）。端口与区 2 的区服状态见 §6。

## 3. 客户端协议（兼容面）

与 C++ `cpp/libs/engine/core/network/codec/codec.{h,cpp}` 逐字节一致：

```
[int32 len][int32 nameLen][typeName(nameLen 字节, 末字节为终止符)][protobuf body][int32 adler32]
```

- 整数全部大端；`len = 4 + nameLen + bodyLen + 4`，合法范围 `[10, 65536]`；`nameLen` 合法范围 `[2, len-8]`。
- adler32 初值 1，覆盖 `nameLen` 字段 + typeName + body。
- 解码只剥掉 typeName 的**最后 1 字节**（Go robot 写的是空格，C++ 写的是 `\0`），剩下的按 protobuf **全名**查类型。
- 下发时 typeName 写全名 + `\0`。
- 任何非法帧：丢弃缓冲并关闭连接，不回包。
- **逐帧分发**（同 C++ `codec.cpp:164-191`，批次 6.2 起）：每次至多解一帧，交给处理器处理完（应答已写出）才解下一帧；
  会话已决定关闭时，剩余字节直接丢弃、不解析、不计非法帧（`ClientFrameDecoder` 的 `keepDecoding` 钩子）。所以同一次读里先到的
  合法帧照常处理、回包，后面的坏帧才导致断开。
- 上行只接受 `ClientTokenVerifyRequest`（首包握手）与 `ClientRequest`；下行是 `ClientTokenVerifyResponse` 与 `MessageContent`。
  battle 直连面用同一套帧格式，上行只接受 `BattleTokenVerifyRequest` 与 `ClientRequest`，下行是 `BattleTokenVerifyResponse` 与 `MessageContent`（§4.22）。
- 握手签名：`HMAC-SHA256(gate_token_secret, GateTokenPayload 字节)` 的 **64 字节小写 hex ASCII**（不是 proto 注释里的 32 字节原值）。

实现：`xm-net` 的 `ClientFrameDecoder` / `ClientFrameEncoder`，单测固化 golden bytes。

## 4. 服务间通信

### 4.1 Dubbo（请求 / 应答）

- 协议 Triple；接口在 `xm-api`，**参数与返回值都是 protobuf 消息**，Triple 直接走 protobuf 序列化，不需要 Hessian。
- 客户端消息的转发统一用一个接口：`ClientMessageService.handle(ClientCall) -> ClientReply`。
  `ClientCall` 带消息号、请求体、会话上下文（gate 节点、session、已认证账号、已绑定玩家）；
  `ClientReply` 带应答体、传输层 tip 码，以及给 gate 的会话指令（绑定账号、进入场景、关闭会话、离开游戏）。
  后端内部按消息号派发到具体处理方法。
- 失败分层（客户端契约）：**业务错误写进应答体自己的 `error_message`**（放在 `ClientReply.body` 里）；
  `ClientReply.tip_id` 只表示传输层失败（消息号不认识、请求体解析失败），gate 放进 `MessageContent.error_message`；
  调用本身失败（超时 / 后端不可用）：login 推 23 `{1003}`；friend / chat / team / guild / trade / match 等后端域回带请求 id 的信封 1003（同基线路由服 forwardlogic，客户端当场按信封错误处理，不必等自己的请求超时）。
- 是否回包由 gate 按契约里该方法的应答类型决定，不看 body 是否为空：非 `Empty` 应答一律回包（哪怕 0 字节），
  `Empty` 应答只在 `tip_id≠0` 时回包；会话指令无论回不回包都执行。
- 服务对服务的调用用各自的类型化接口（如 `SceneDirectoryService`；`AccountLoginService`：xm-gateway 的 HTTP 登录 / 刷新令牌调 xm-login，
  参数与返回值直接用客户端契约里的 loginpb 消息，调用方不重试——刷新是一次性轮换，重试会拿已作废的 refresh 再打一次）。
- `ClientMessageService.abandonEnter(AbandonedEnter)`：gate 确定一条 `PlayerEnter`（`EnterScene` 指令的登录进场，或跨节点换图的交出进场）
  从未写上链路（会话已在关闭、链路层已关、建链失败——会话已关闭 / 已从会话表释放也照样通知），或收到对不上会话绑定（或内容非法）的
  `PlayerTransfer`（§4.2）时通知 login，login 带 epoch 围栏释放这次的归属（见 §7），玩家不必等租约过期。
- **调用方鉴权（必需）**：Dubbo 把 `127.*` 视为无效绑定地址，`dubbo.protocol.host=127.0.0.1` 实际监听 `0.0.0.0`
  （`DUBBO_IP_TO_BIND` 也不接受回环地址），login（20881）/ scene-manager（20882）的端口**无法只绑本机**，
  而 `ClientMessageService` 完全信任调用方填的 `SessionContext`。所以 Java 版进程间的每次 Dubbo 调用都要带鉴权附件：
  共享密钥 `XM_DUBBO_SECRET`（xm-login / xm-scene-manager / xm-friend / xm-chat / xm-team / xm-guild / xm-trade / xm-match / xm-scene / xm-gate / xm-gateway / xm-battle 必填，缺失即拒绝启动），
  调用方附 `xm-auth-ts`（Unix 秒）与 `xm-auth-mac = HMAC-SHA256(secret, "接口全名|方法名|ts")` 的 64 字节小写 hex，
  提供方常数时间比较 MAC、再验 ts 与本地时钟相差 ≤ 60s，不过即抛 `RpcException(AUTHORIZATION)`，不进入业务代码，
  对外原因只有一句「调用方鉴权失败」。算法唯一出处 `xm-common` 的 `DubboCallAuth`，过滤器在 `xm-api` 的
  `com.game.api.auth`（Dubbo SPI 自动激活，只管 `com.game.*` 接口；Dubbo 内置的元数据 / 健康检查服务不改业务状态，放行）。
  残余风险：MAC 不覆盖请求体，能截获内网流量的人可在 60s 窗口内把附件嫁接到别的请求上重放——传输安全仍靠内网隔离；
  生产用 `DUBBO_IP_TO_BIND` 绑内网网卡并配防火墙。
  不用 Dubbo 自带的 `TokenFilter`：它用 `String.equals` 比较；随机 token 要靠注册中心下发（注册中心可读即泄露）；
  直连时 token 要拼进 URL，会进配置与日志。

### 4.2 gate ↔ scene 节点链路（长连接、双向）

- gate 主动连每个 scene 节点，一条 TCP 长连接双向复用：gate 发玩家进出与客户端消息，scene 推下行消息。
- 编解码用 Netty 自带的 `ProtobufVarint32FrameDecoder` / `ProtobufVarint32LengthFieldPrepender`，
  帧内是 `xm.internal.NodeLinkFrame`（oneof 信封）。下行携带的是已序列化的 `MessageContent` 字节，gate 原样用客户端帧下发。
- 链路上第一帧是握手 `LinkHello`（带节点身份、zone 与 gate 节点号租约的防护代次 `lease_epoch`），不匹配即断开。
- **握手鉴权**：gate 与 scene 共享密钥（环境变量 `XM_NODE_LINK_SECRET`，两个进程都必填，缺失即拒绝启动）。
  `LinkHello` 带 `auth_timestamp`（Unix 秒，每次建链现取）与
  `auth_mac = HMAC-SHA256(secret, "gate_node_id|gate_instance_id|zone_id|lease_epoch|auth_timestamp")` 的 64 字节小写 hex ASCII
  （实现唯一出处 `xm-common` 的 `NodeLinkAuth`）。scene 先验 MAC（常数时间比较）、再验时间戳与本地时钟相差 ≤ 60s，
  任一不过回 `LinkHelloAck{accepted=false}` 并断开，对外原因只有一句「链路鉴权失败」。
  残余风险：链路不加密，60s 窗口内截获的握手可重放——这一层只挡不知道密钥的连接方，传输安全仍靠内网隔离
  （scene 链路端口默认只绑本机）。
- **同一 gate 节点号只保留一条链路**，新链路顶替旧链路（旧链路上的玩家按断线写回），前提是新链路的 `lease_epoch`
  **不低于**现有链路的；更低的是丢了节点号租约的旧 gate 进程，scene 回 `accepted=false`（「gate 节点号租约代次过期」）。
  gate 侧在租约无效（丢失或续期滞后，`NodeIdLease.isValid()` 为假）时不新建链路、建链时也不握手（§6）。
- **进场结果与踢出**：`PlayerEnterResult` 回显进场的 `owner_epoch`，gate 只认会话当前绑定的那次进场；
  `PlayerKicked`（scene → gate）表示 scene 已把会话上的玩家移出场景（归属被接管或本实例失去归属，该写回的已写回），
  gate 解绑场景（不再发 `PlayerLeave`）、推 23 `{2017}` 后关闭连接（本里程碑不发 34）。
- **跨节点换图改绑**（批次 5.2，§8.1）：`PlayerTransfer{session_id, player_id, from_epoch, to_epoch, target_scene_node_id, target_scene_id}`
  （scene → gate；字段 7 预留给 5.4 跨 zone 重定向）表示源 scene 已提交交出（§7 第 6 步）、移除了实例。它走源节点到 gate 的**同一条链路**，
  与发给该会话的其余下行保持先后（交出之前的应答照常先到）。gate 在会话线程上核对（节点, 链路代次, 玩家, `from_epoch`）：
  对得上就改绑到（目标节点, `to_epoch`）——**不发** `PlayerLeave`（源节点已移除）、**不撤**在线目录——再向目标节点发
  `PlayerEnter{…, transfer = true}`；对不上（会话已关 / 正在关、绑定已变）或路由层已找不到会话，就 `abandonEnter(player, to_epoch)`
  由 login 释放（同一会话已绑在 `to_epoch` 上的重复帧只计过期、不放弃）；绑定对得上而内容非法（目标节点为 0、`to_epoch` 不大于 `from_epoch`）
  推 23 `{3023}` 后断开、保留旧绑定，`to_epoch` 更大时同样放弃它。`PlayerEnter.transfer = true` 让目标节点不拍 LOGIN 快照、
  计交出进场指标、进场后立即续约一次；交出进场失败时 gate 推 23 `{tip}` 后断开（不回「已登录、未进游戏」，§8 第 6 步只管登录进场）。
  改绑之后源节点迟到的 `ToClient` / `PlayerKicked{E}` 因绑定已变被丢弃。
- **gate 侧收帧规则**：`ToClient` / `PlayerEnterResult` / `PlayerKicked` / `PlayerTransfer` 只要这条链路**握手成功过**就投递（含刚被别的线程
  判死、通道还没关的那一刻），过期帧交给会话线程按绑定过滤——判死时若只丢下行而照常投递改绑，会话会丢掉 `PlayerTransfer` 之前的应答、
  改绑后在目标节点上继续跑。从没握手成功的链路上这四种帧一律丢弃（对端身份没验过）。
- **背压与缓冲上界**：scene 每条链路「已投递给逻辑线程、还没执行完」的帧数到 `xm.scene.link-max-pending-frames`（默认 1 万）
  即暂停读这条链路（autoRead=false），降到一半恢复——逻辑线程的任务队列因此有界（链路数 × 上限），压力沿 TCP 传回 gate。
  两端链路 channel 的写缓冲水位是 8MB / 32MB：越过高水位说明对端长时间读不动，直接断链（其上玩家按断线写回、会话关闭），
  不丢单帧（丢 21 / 51 会让客户端场景状态永久错乱），也不让出站缓冲无限增长。

### 4.3 玩家在线目录与服务端推送（任意服务 → 在线玩家）

mmorpg 用 `player_locator` 会话键 + Kafka gate 命令 topic（`PushToPlayer` / `BroadcastToPlayers` / `KickPlayer`）；Java 版：

- **在线目录**（`xm-discovery` 的 `PlayerPresenceDirectory`）：Redis `xm:presence:{player_id}`，值 `xm.discovery.PlayerPresence`
  （zone、gate 节点号与实例、会话号、上线时刻、这次进场的归属 epoch），**TTL 60s**。gate 是唯一写者：scene 确认进场（`PlayerEnterResult` 成功）后写，
  场景绑定结束（离开游戏、进场失败、被踢、链路断开、断线——都经 `ClientDispatcher.unbindScene`）时**值仍是自己写的才删**（Lua），
  在线期间每 20s 一批续期（值是自己的就延长；键丢了就补回；已被别的会话覆盖不动；每条一个异步脚本调用——
  不用 Redisson 批处理，它在运维 SCRIPT FLUSH 后遇到 NOSCRIPT 不会重载脚本）。同一玩家在同一 gate 上两次登录的确认可能乱序到达，
  gate 本地表以归属 epoch 更高者为准，旧登录的迟到确认不覆盖、旧会话被踢也不会撤掉新会话的条目；跨 gate 时旧登录迟到写入的条目
  会被它自己随后的撤销删掉，新会话的续期在 20s 内补回。gate 进程死掉后条目最多一个 TTL 消失，
  不会像基线无 TTL 的会话键那样永远停在 ONLINE。发往 Redis 的写没有执行次序的保证，`GatePresence` 为此做两件事（2026-10-06）：
  同一名玩家的写入与撤销排成一队、上一条有了结局才发下一条（进场后立刻断线时，撤销不会跑到写入前面变成空操作）；
  续期在途时下线或换了会话的条目，续期回来后按值再撤销一次（续期脚本对「键不在」会补回，可能把刚删的条目补回来）。
  此前这两种交错都会让已下线的玩家在目录里多留至多一个 TTL。仍可能多留的只剩 Redis 写失败（撤销没执行成）的情形，推送有玩家栅栏兜底。
  条目只表示「此刻在游戏里」，不承载断线租约 / 顶号（那些由归属协议负责，§7）。读者：任何服务（`find` / `findAll` 及异步版）。
- **推送**（`PlayerPushes`）：查在线目录 → 把 `xm.discovery.GatePush{gate_instance_id, targets[(session_id, player_id)], message_content | kick_tip_id | message_batch}`
  发布到该 gate 的 pub/sub 频道 `xm:gate-push:{zone}:{gate 节点号}`；多人推送按 gate 分组、每个 gate 一条。
  `message_batch`（批次 6.2，`pushAllToPlayer`）是同一会话按序下发的多条 `MessageContent`（battle 大厅公告 177 → 143）：一次查目录、一次发布；
  gate 先整批解析，空批或任一条损坏整条丢；对每个目标在会话 EventLoop 的**同一个任务**里逐条过玩家栅栏下发，第一条被拒就停
  （客户端只会收到从头开始的连续一段），每个目标只计一次。
  gate（`GatePushSubscriber`）在 Redisson 线程上只解析：实例不符（节点号被复用前的旧条目）整条丢；再投递到每个目标会话的 EventLoop，
  **玩家栅栏**：会话已确认进场、在游戏里的正是目标玩家、没在关闭，才下发 `MessageContent`（推送 id 为 0）或推 23 {tip} 后断开
  （断线流程照常：离场写回、通知 login）。基线 Kafka 版推送没有玩家栅栏（会话号复用 / 换角色时可能推错人）。
- **语义**：至多一次（玩家不在线不推；gate 掉线、频道抖动、玩家恰好下线都丢），业务方以客户端拉取兜底，推送失败不回传成业务失败——
  与基线一致。不选 Kafka：推送要求低延迟、按节点寻址、丢了可接受，Redis pub/sub（已用于 §7 的接管请求）足够；Kafka 留给需要持久与重放的审计 / 流水。

### 4.4 进场后的客户端请求：scene 侧按功能注册、GM 闸两道锁

- **分发**（`ClientRequestHandler`）：gate 转来的 `ClientForward` 先过会话 / player_id / 消息号 / 请求体校验，
  再过字段规模与负数校验（`RequestFieldCheck`，同基线 `ProtoFieldChecker`：任一 repeated / map 字段元素数 > 20、
  任一有符号整数为负，只递归进非 repeated 子消息）——不过的都静默丢弃、不回包；然后按消息号找处理器。处理器由**功能模块**注册（`SceneFeature`：货币 `CurrencyFeature`、属性 `AttributeFeature`、背包 `BagFeature`、任务 `MissionFeature`、活动 `ActivityFeature`、放技能 `SkillFeature`、宝宝 `PetFeature`，以后的玩法同样各成一个），
  场景核心（移动、技能列表 77、换场景、场景信息）由 `ClientRequestHandler` 自己注册。注册时校验：方法在契约里、属于标了
  `OptionIsPlayerService` 的客户端服务、请求类型与契约一致、没有重复——任一不符启动即失败。所以 96–117 所在的 `SceneRollbackClientPlayer`
  （是玩家服务，但不是客户端协议服务）注册不了，伪造的 `ClientForward` 带这些号直接丢弃；这组 GM 指令的语义在 xm-data 运维面实现（§4.5）。
  回归测试 gate `GmRollbackMessagesUnroutableTest`、scene `GmRollbackRpcNotRegisteredTest`（批次 7.2a）。
- **处理器契约**（`PlayerRequestHandler` + `PlayerCall`）：在场景逻辑线程上调用；经 `call.reply(...)` 回应答，`message_id` 同请求、
  `id` 回显请求号；每条请求至多回一次，应答类型必须与契约一致，`Empty` 应答的方法不能回。处理器没回、回错类型、抛异常
  （记 ERROR，逻辑线程继续）时由分发补回 `kFeatureUnavailable`(1006)，客户端不会卡在等应答上。没有处理器的方法同样回 1006
  （`Empty` 应答的方法静默忽略）。
- **冻结策略**（批次 5.2，§8.1）：每个方法注册时声明 `FreezePolicy`（`SceneFeature.Registrar.on` 的六参数形式，与下一条的战斗策略一并声明；四参数形式两者都缺省 REJECT，
  以后新加而没声明的方法冻结中一律拒绝）。玩家处于跨节点换图的冻结中（FREEZING）时，分发入口在 GM 闸与找处理器之后、调用处理器之前裁决：
  ALLOW / READ_ONLY / GATED 照常进处理器（GATED 由服务闸回基线码），REJECT 回应答内 `error_message{1005}`（`Empty` 应答的静默丢），
  DROP 静默丢。DROP 只有场景核心的移动上行能声明：功能模块声明 DROP、有应答的方法声明 DROP 都启动即失败。选目标中（RESOLVING）不冻结、全部放行。
- **战斗策略**（批次 6.3，§4.23）：每个方法同时声明 `BattlePolicy`（ALLOW / GATED / DROP / STOP_ONLY / REJECT，缺省 REJECT）。不在交出冻结中而处于回合制战斗在途
  （`ScenePlayer.inBattle()`：备战或战斗中）时按它裁决：GATED 进处理器由服务闸回基线码，DROP / STOP_ONLY 只给移动上行，REJECT 回应答内 1005。两种冻结互斥，先判哪个不影响结果。
- **运行模式**（`xm-common` 的 `RunMode`，环境变量 `XM_RUN_MODE`，每个进程各读自己的）：`dev` / `test` 放行 GM 类客户端指令，
  其余（含未设、写错）一律按 `prod` 拒绝；取值大小写不敏感、去首尾空白，别名同基线（development / local / testing /
  production / release / live），写错的值启动时打 WARN。进程缺省 prod（部署链不设即拒绝）；`tools/local/start-slice.sh`
缺省设 dev（同基线 `start_game.ps1`），`XM_RUN_MODE=prod` 可在本机验证生产行为。
- **GM 指令的判据**（`MessageMethod.gmCommand()`）：方法名以 `Gm` / `Debug` / `Test` 开头、紧跟一个大写字母。按名字而不是号表：
  新加的 GM 方法不需要任何人记得登记。
  - **第一道锁在 gate**（`ClientDispatcher`，排在体积与限频之后，被拒的包照样占限频额度）：推 23 `{1006}`、计非法包（到阈值断开）、
    不转发；计入 `xm_gate_client_requests_total{result="gm_rejected"}`，逐条只打 DEBUG（防日志放大）。
  - **第二道锁在 scene 分发入口**：GM 方法在 scene 的运行模式不放行时回应答内 `error_message{1006}`、不调处理器（打 WARN——
    走到这里说明 gate 与 scene 的运行模式不一致，或有人绕开了 gate）。
- **资产流水**（`AssetAudit`）：货币变动一律经 `CurrencyService`（唯一入口），成功后记一条（玩家、币种、增减、前后余额、原因）；
  生产实现 `KafkaAssetAudit` 经审计管线发往 Kafka（§4.5），`xm.audit.enabled=false` 时只写本地日志 `xm.audit.asset`。
- **玩法的加载钩子与连带推送**：`SceneWorld` 在玩家实例建好、进场景之前调用 `PlayerInitializer`（进场与接管旧实例都走；
  抛异常按进场失败处理并释放归属）——玩法在这里按配表规整恢复出来的状态、算派生值（属性系统：清掉表里已删的维度、按等级收敛超量分配、
  算二级属性）。处理器需要按顺序先推一条再回应答时用 `PlayerCall.push`（信封 id 0，客户端按推送处理），例如 GM 设等级先推 170 面板。
  配置表在 Spring 里是一份不可变快照（`ConfigTables` bean），各玩法的视图（`SceneTables`、`AttributeTables`）都从它构建。
- **属性加点**（`AttributeFeature` + `AttributeService`，规则同基线 `PlayerAttributeSystem`，纯规则在 `AttributeRules`）：
  方案与已分配点存 `player_state.attribute`（只存非 0；从没动过的不写）；点数总量按等级与表现算，二级属性
  不落库、每次加载重算；当前气血 / 法力落 `player_state.vitals`（满血满蓝不写），进场按新上限往下夹，阵亡或没有记录的回满（§4.10）。等级仍在 `player.level` 列，读存档时压回上限 85
  （`PlayerLevels`）。写操作成功回全量面板、拒绝只回 tip；170 只在等级变化后推（基线唯一的推送点）。

### 4.5 资产审计管线（scene → Kafka → xm-data → MySQL）

mmorpg：scene 的 `TransactionLogSystem` 发 Kafka `transaction_log_topic_g<N>`，go/data_service 攒批落全局库。Java 版有自己的格式、
topic 与存储（不与 Go 混部）：

- **格式与 topic**（`xm-audit`）：Java 自有 proto `xm.audit.TransactionLogRecord`（原因沿用 mmorpg TransactionType 的数值，
  Java 独有的从 1001 起；时间是毫秒；`kind` 区分货币 / 物品——币种 0 是金币，只看币种分不出来）。topic `xm-transaction-log-g<代次>`，
  6 分区、保留 30 天且不限大小（显式声明，不继承 broker 默认）。代次 `XM_AUDIT_TOPIC_GENERATION` 两端必须一致；分区数是契约，
  改分区数就升代次换新 topic，绝不原地扩（会让同一玩家的键换分区、打乱顺序）。分区键 = 扣减方玩家号，否则获得方（无符号十进制），
  同一玩家的流水进同一分区、按产生顺序。
- **启动期核对**（`AuditTopicInitializer`）：两端都「缺就按规格建、存在就核对分区数」，不符拒绝启动；xm-data（topic 的主人）还把
  保留策略校正到规格并读回。第一次核对在启动线程上同步做（契约不符才能拒绝启动）；Kafka 不可达（含地址解析不了）时最多等
  `xm.audit.init-timeout`（缺省 10s）后告警并照常启动，后台每 30 秒重试；scene 在核对通过前不发送（broker 若开着自动建 topic，
  会建出默认分区数、契约永久失配），这期间的流水只进兜底日志。
- **第三个 topic 不属于审计，但复用同一套规格件**（批次 6.4）：对局结果 `xm-battle-result-g<代次>`（`BattleResultTopics`：3 分区、保留 7 天，key = battle_id，value = 契约 `BattleResultEvent`；
  代次 `XM_BATTLE_RESULT_TOPIC_GENERATION` 与审计的代次各管各的）。xm-battle 生产（§4.22）、xm-match 消费后入评分（§4.24，topic 的主人）；两端的核对、「核对通过前不发」、兜底日志与
  「可恢复故障暂停不跳过」都照本节的模式。`AuditTopicInitializer.ensure` 为它加了一个带「代次环境变量名」的重载（报错文案指向正确的变量），旧签名的行为不变。
- **scene 生产**（`AuditPipeline`）：逻辑线程只组装不可变草稿、投进有界队列（不阻塞、不抛异常——钱包已改，处理器再失败只会让客户端
  误以为没成）；发号、序列化、`producer.send`（可能因元数据 / 缓冲阻塞到 `max.block.ms`）都在专用的 `scene-audit` 线程上，
  单线程保住同一玩家的顺序。生产者幂等（acks=all），第一次核对通过时才建（地址解析不了时构造器就会抛，不能挡住启动）；
  send 同步抛 KafkaException 说明生产者已不可用（致命状态 / 已关闭），丢弃后由下一次 30 秒核对重建。号来自全服号段租约（§9）。没被 Kafka 确认的每条记录（队列满 / 发不出号 /
  未核对 / 发送或投递失败 / 停服没发完）都完整写进兜底日志 `xm.audit.fallback` 并计 `xm_scene_audit_records_total{result}`——
  资产照改，流水不丢（同 mmorpg：审计尽力而为、不影响玩法；mmorpg 失败只记一行 ERROR，Java 记全量可回灌）。
  停服：写回之后（逻辑线程已停）有界发完队列、关生产者，最后交还发号租约。
- **玩家快照**（`PlayerSnapshots` → `KafkaPlayerSnapshots`，同一条管线）：进场成功（回了进场结果之后，用内存状态——接管旧实例时
  库里那份是旧的）拍 LOGIN，离场 / 停服写回时拍 LOGOUT（与写回**同一份** `PlayerSave`）；失去归属的移除不写回、也不拍；
  跨节点换图的交出（源节点）与交出进场（目标节点）都不拍（不是登录 / 登出，冻结快照已在库里，§8.1）。
  `xm.audit.PlayerSnapshotRecord` 带快照号（同一个雪花）、触发原因、zone、owner_epoch、等级、场景、坐标和玩法数据原样字节，
  发 `xm-player-snapshot-g<代次>`（3 分区，键 = 玩家号，保留同流水）。组装与序列化在审计线程上；序列化后超过
  `xm.scene.snapshot-max-bytes`（缺省 1000000，须低于生产者 / broker 的 1MB 单条上限）就丢弃、记 ERROR、计 `result=oversize`。
  没被确认的只把元数据写进兜底日志（玩法数据不进日志）。快照是回档素材，丢一份不影响玩法；周期存盘不拍。
- **xm-data 消费**（`ConsumerLoop`，每个 topic 一条线程一个 KafkaConsumer）：一次拉取的记录在**一个**库事务里按位点顺序落库
  （`INSERT ... ON DUPLICATE KEY UPDATE` 主键幂等，不用 INSERT IGNORE——严格模式下它会把数据错误降成警告），落库成功才
  `commitSync` 位点，崩在两者之间只会重放。可恢复的库故障：暂停全部分区（继续 poll 保住组成员身份）、退避 1s→30s 原批次重试，
  不提交、不跳过——宁可积压不丢；数据错误（SQLState 22 / 23）逐行隔离，坏行写毒丸日志 `xm.audit.poison` 后跳过；解不出 / 字段非法的
  记录跳过并计数。重平衡收走了待落库批次的分区就整批作废（位点没提交，新主人会重新拿到）。可多实例（同组分摊分区）。
  快照落 `player_snapshot`（主键快照号、玩法数据 MEDIUMBLOB），单条可达约 1MB，所以拉取（50）与多行 INSERT 分块（10）都小。
- **保留期**：`xm.data.retention.transaction-log` / `player-snapshot` / `gm-snapshot`，缺省 0 = 永久保留（同 mmorpg）；设了就每小时分批 DELETE。
  快照按原因分两类清（批次 7.2a）：`player-snapshot` 只清 LOGIN / LOGOUT / PERIODIC；`gm-snapshot` 清 PRE_MAINTENANCE / GM_MANUAL /
  PRE_ROLLBACK / PRE_GM_EDIT（运维与安全快照是撤销依据，缺省永久）；PRE_TRADE 和不认识的原因从不清。删行要求 `time_ms` 与 `ingested_at`
  都早于截止时刻。启动校验：流水保留期为 0，或者「上下线快照保留期不为 0 且流水保留期不短于它」，否则拒绝启动（回档 / 回收窗口内要能用流水解释差异）。
- **运维查询**（xm-data 管理端口 18106，缺省只绑本机）：`GET /admin/transaction-log` 的参数有 `player`（可选）、`kind`、`currencyType`（0 = 金币有效）、
  `itemConfigId`、`itemUuid`、`reasons`（多值）、半开毫秒窗口 `since` / `until`、`limit ≤ 1000`、键集游标 `after=<timeMs>:<txId>`。下一页游标放在应答头
  `X-Xm-Next-Cursor`，应答体仍是数组。结果按（时间、流水号）升序，不回总数。必须能走索引：不给玩家时要带 `itemUuid`、`kind + itemConfigId`
  或 `kind + currencyType` 之一，否则窗口不超过 `xm.data.ops.max-window`（7 d）；玩家条件拆成 from / to 两条索引查询再归并。
  `GET /admin/player-snapshots?player=&since=&until=&limit=&cause=&order=` 给快照元数据（含 operator / note / 原因名）与玩法数据字节数（不回本体）。
  uint64 字段输出为十进制字符串。鉴权（过滤器只按容器规范化后的路径 `/admin/*` 生效，`/admin;x/`、`/%61dmin/` 之类绕不过）：
  共享令牌 `XM_ADMIN_TOKEN`（请求头 `X-Xm-Admin-Token`，常数时间比较，未配置一律 503）+ 必填
  操作人 `X-Xm-Operator`（UTF-8，1–64 字符、不含控制字符）；每次调用（含处理中抛异常的，按 500 记）都记运维审计日志 `xm.audit.admin`。本机切片脚本没设令牌时生成一个写进 `run/xm-admin-token`。
- **GM 运维面**（规格 `docs/porting/data-ops-spec.md`；批次 7.2a 做了只读与不需要栅栏的部分，列在下面；批次 7.2b 的作业框架、离线栅栏、回档与整区维护前快照见 §4.5.1；
  回收执行、欠款、精确回收随 7.2c）：对应基线 data_service 的运维 RPC，以及 scene 侧
  102–117 的空桩（Java 不在 scene 注册这组方法，§4.4，PARITY「scene 侧 GM 102–117」行）。都在 `/admin/**` 下、经同一个过滤器鉴权，
  出错时回 `{code, message}`（`OpsErrorAdvice`；code 取基线常量名去掉前缀，不是客户端契约）：
  - `POST /admin/player-snapshots`：手工快照（GM_MANUAL / PRE_MAINTENANCE），必须带 `Idempotency-Key` 与 reason。在一个事务里读 `player` 行与
    `player_state` 原字节、插快照（带 operator / note）、写一条终态作业行；不夺权、不踢人。`time_ms` = 内容所代表的时刻（`player_state.updated_at`），
    拍摄时刻记在 `ingested_at`，`owner_epoch` = `saved_epoch`。应答里的 `online` 表示仍有 scene 持有归属（未释放且租约未过期）；这时快照可能比内存落后最多一个存盘周期。
  - `GET /admin/player-snapshots/{id}?includeState=true`：详情，`player_state` 经 protobuf-java-util `JsonFormat` 转成 JSON，不认识的字段给出路径与原始字节。
  - `GET /admin/players/{player}/snapshot-diff?snapshot=<号>` 或 `?atMs=<毫秒>`：结构化差异。按时刻选源时只认 LOGIN / LOGOUT / PERIODIC / PRE_MAINTENANCE /
    GM_MANUAL；安全快照记录的是「被覆盖之前」的状态，只能按号显式选。「当前」= 已落盘状态。输出分块：player 行；货币与欠款；物品（按配置聚合，加逐实例）；
    宝宝；其余玩法段（protobuf 反射逐字段比较，至多 500 条）；转移证据（按 uuid 与转移类原因查快照之后的流水，`restorable` 只作提示）；
    资产通道账本差集（`LedgerDiff`）。快照早于流水保留期的下界时 `evidenceComplete=false`：只认正面证据，其余记为未知（null / INCOMPLETE）。
  - `GET /admin/items/{uuid}/trace`：物品追溯，按 `(item_uuid, time_ms)` 升序、游标翻页；最后一页给提示 HELD_BY / DESTROYED / MERGED（只是提示，不找当前持有者）。
  - `POST /admin/recalls`：批量回收 dry-run（`dryRun=false` 回 501，执行随 7.2c）。只匹配获得方的行，金币也能作目标，tx_id 去重。匹配超过
    `xm.data.recall.max-rows`（缺省 10000）就回 422 `result_truncated`，什么也不改，另写一条作业审计行。按已落盘状态估算可回收量与缺口；
    落库完整性闸（`ingestComplete`）随 7.2c。
  - **发号**（`OpsIds`）：快照号、作业号、回档的安全快照号与回档流水号（7.2b；回收流水号随 7.2c）必须与 scene 同取 `NodeTypes.SCENE_GUID` 全服租约池（§9）。
    租约由 `data-ops-ids` 线程在启动后后台申领、不挡启动（Redis 不可用时审计消费照常，每 10 s 重试）；租约无效时写接口回 503 `id_unavailable`；Web 服务器停下之后才交还。
  - **运维作业表**（库 `xm_java`，由 xm-pbmysql 按 `xm-data/src/main/proto/xm/data/ops_tables.proto` 在启动时建表，只扩不缩）：`ops_job`（主键 job_id，唯一键
    idem_key）、`ops_job_event`、`ops_job_player`、`ops_active`（全集群单飞槽）、`recall_source`（回收去重）、`audit_replay_line`。7.2a 只写 `ops_job`、
    `ops_job_event`（回收截断的审计）与 `audit_replay_line`，`recall_source` 只读；7.2b 起 `ops_active`、`ops_job_player` 启用，`ops_job` 加 `cancel_requested` 列
    （§4.5.1）；`recall_source` 的写入随 7.2c。`transaction_log` 新加三条索引
    （`idx_txlog_item` / `idx_txlog_currency` / `idx_txlog_uuid`），`player_snapshot` 新加 operator / note 两列（db-migrations M8）；`player` 加 `idx_player_zone`（M9，7.2b）。
  - **玩家数据的读与写**：只读路径（快照、差异、回收 dry-run、回档计划）走 xm-data 自己的只读 Mapper（`PersistedPlayerMapper`）。`PlayerStoreAutoConfiguration` 仍被排除；
    批次 7.2b 起 xm-data 自己定义 `PlayerStore` bean、`@MapperScan` 补上 `PlayerMapper`，与本服务的 Mapper 共用同一个 `SqlSessionFactory` 与数据源——
    写玩家数据只有回档一条路径，必须先以运维身份夺到归属、带 epoch 围栏写（§4.5.1、§7 第 7 步）。Druid `max-active` 调到 8。
  - **兜底日志回灌**：离线工具 `com.game.data.tools.AuditFallbackReplay`（用法见类注释）把 scene 写进 `xm.audit.fallback` 的流水行补进 `transaction_log`。
    原号的行按主键 ODKU 幂等；`tx_id=0` 的行在 `SCENE_GUID` 池占一个 worker 发新号；每行登记到 `audit_replay_line`，同一文件重跑会跳过；
    快照兜底行只有元数据，不回灌。

#### 4.5.1 运维作业框架、离线栅栏与回档（xm-data，批次 7.2b）

mmorpg：data_service 的 `RollbackPlayer / Zone / All` 同步执行，先持跨服务离线栅栏、再写 STARTED 审计；栅栏在生产中从未接线，三个 RPC 恒回 16，
读写的也不是 scene 的权威数据。Java 版真正执行，对齐的是基线的安全门与设计意图；规格与逐条出处见 `docs/porting/data-ops-spec.md` §4 / §7.4 / §13
（有意差异 D1–D13、D18 见 PARITY「GM 回档」行）。客户端可见的只有两件既有行为：运维持有归属期间进游戏回 2005；被运维踢下线收 23 {2017}。

- **作业框架**（`com.game.data.ops`：`OpsJobService` 受理与查询、`OpsJobRunner` 执行、`JobContext`、`OpsJobStore`）：长操作不绑定 HTTP 请求。
  - **受理**（Tomcat 线程）：校验 → 必填的 `Idempotency-Key`（同键、同种类、同请求指纹回原作业，异参 409 `idempotency_conflict`）→ 发作业号 → **一个事务**插
    `ops_active` + `ops_job`（QUEUED）+ STARTED 事件，任一失败 503、零变更 → 202 `{jobId}`。STARTED 先于任何夺权、踢人、写数据。
  - **单飞槽**：`ops_active` 只有一行（`slot = 1`），插入撞主键 = 全集群已有作业在跑 → 409 `ops_busy`（带在跑的作业号）。单飞在库里，不在进程里，多副本也只有一个作业。
  - **状态机**：QUEUED → RUNNING → SUCCEEDED / PARTIAL / REJECTED / FAILED / DIVERGED_AFTER_WRITE / CANCELLED / INTERRUPTED。开始执行先在一个事务里刷新心跳
    （槽必须仍属于它）并 QUEUED → RUNNING，不成立就一步也不执行。作业行一律按列、带 `status IN (QUEUED, RUNNING)` 条件更新：不覆盖别的副本写的取消标志，
    不把清扫器写的 INTERRUPTED 改回去；谁把作业行改成终态谁记结局指标。
  - **心跳**：`data-ops-fence` 线程每 5 s 更新 `ops_active.heartbeat_ms`（取 `GREATEST(旧值 + 1, now)`，`useAffectedRows=true` 下同毫秒也能数出 1 行）。
    更新不到行 = 槽已被收走：作业体在下一个检查点（阶段边界、等待中每 200 ms）自停，之后不再写任何玩家。
  - **清扫**：每个副本的 `data-ops-sweeper` 每 5 s 看一次；心跳超过 60 s 的槽在**一个事务**里按心跳值 CAS 删除 → 未终结的作业行改 INTERRUPTED → 追加 INTERRUPTED 事件，
    任一步失败整体回滚、下一拍重来。作业已终结、只是槽没让出的只收回槽。**不自动续跑**：由人看明细决定、用新幂等键重提。
  - **RESULT 与收尾**：RESULT 事件与终态作业行同一个事务，失败重试至多 30 s；写不进就停心跳、不让槽，交给清扫器。RESULT 之后才释放归属，最后让出单飞槽。
  - **取消**：`POST /admin/ops-jobs/{id}/cancel` 置 `ops_job.cancel_requested`（执行线程读库，请求可以落在别的副本上）；只在第一笔写之前生效，幂等。
  - **事件与审计**：`ops_job_event` 只追加（STARTED / PLANNED / CLAIMED / CHECK / ACCEPTED / WRITE / RECHECK / RESULT / INTERRUPTED），同时镜像到日志 `xm.audit.ops`；
    逐玩家明细在 `ops_job_player`（PLANNED → 终态是条件更新，必须恰好改到 1 行）。
- **离线栅栏 = 归属夺权**（`com.game.data.ops.fence.AdminOwnership`）：不发明新协议，xm-data 是归属协议里的又一个写者（§7 第 7 步）。
  `PlayerStore.claimOwnership` 只在「已释放或租约过期」时成功，判定原子，没有「先查在线再动手」的窗口。在线玩家缺省拒绝（`player_online`）；`ifOnline=kick` 时
  往 `xm:owner-takeover` 发让出请求（`RedisTakeoverRequests`，与 login 顶号同一频道同一消息），持有者写回、释放、推 23 {2017} 后断开，xm-data 退避重试
  （50 ms 起翻倍、封顶 800 ms，每次重发）。多人时分两轮：第一轮全部不踢地夺，把在线的一起发让出请求；第二轮逐个等，全轮共用一个 `claim-wait`（35 s，长于租约 30 s），
  夺不到记 `player_busy`。持有期间 `data-ops-fence` 每 10 s 批量续约，续不上的记失去、之后不写也不写墓碑。释放 = 先写位置墓碑、再带围栏释放；
  墓碑失败只告警（TTL 60 s 兜底），释放失败等租约过期。
- **回档**（`com.game.data.rollback`；`POST /admin/rollbacks`，对应基线 99 / 100 / 101 / 112）：
  - **请求**：`scope=players`（1–100 人；按 `snapshotId`（只限单人）或 `targetTimeMs`）或 `scope=zones`（区号或 `allZones`，只能按时刻）。不给 `sections` = FULL，
    给空列表 400。`reason` 必填；非 dry-run 要写开关 `xm.data.ops.enabled`（缺省 false，关闭回 503 `ops_disabled`）。
  - **流程**（`RollbackJob`，`data-ops` 线程）：计划（逐人选快照并钉进明细，执行时不重选；按时刻选源不含安全快照）→ 夺权 → 账本差集 → 沉降（缺省 30 s）→ 帮会检查 →
    回收逆转检查 → 裁决（放行先写 ACCEPTED 事件，写不进零写入）→ 按 player_id 升序逐人写 → 等 10 s 后写后复查（持有归属期间）→ RESULT → 墓碑、释放。
    连续 3 人写失败或号源失效即停；写后复查发现新终结的帮会指令 → DIVERGED_AFTER_WRITE（告警，不自动撤销）。
  - **恢复范围**（`RestoreBuilder`，纯函数）：FULL 把等级、场景、坐标与整份 `player_state` 换成快照内容，只保留现档的 GM 封禁币种；SECTIONS 以现档为底换选中的段，段名
    level / position / facing / attribute / vitals / mission / assets。`assets` 不可拆：按描述符取 `PlayerState` 里除 facing / attribute / mission / vitals 以外的全部字段
    （货币、背包、宝宝、资产通道账本、战斗结算账本，以及以后新加的段）——账本必须与资产同记录、同一次写；`mission` 必须与 `assets` 同选。
    SECTIONS 选了 `assets` 而两边顶层未知字段不同 → 这名玩家不写（`unknown_sections`）。
  - **写事务**（`RollbackWriter`，每人一个 MySQL 事务）：`selectOwnerForUpdate` 确认仍是我们的 epoch 且未释放 → 读现档与钉住的快照 → 插 PRE_ROLLBACK 安全快照
    （内容 = 被覆盖之前的已落盘状态）→ `PlayerStore.saveStateHeld` 带围栏覆盖写 → 插 TX_ROLLBACK_RESTORE（16）流水（每个变化的币种、每个变化的物品实例一行，关联号 = 作业号）→
    明细 PLANNED → RESTORED。四件事同生共死；MyBatis 与 pbmysql 用 Spring 事务绑定的同一条连接。撤销 = 以明细里的 `preSnapshotId` 再回档一次。
  - **三道资产分歧检查**（内联在 `RollbackJob`，都默认拒绝）：帮会检查（`GuildDivergenceGate`，经 Dubbo 问 xm-guild 快照之后已终结为已应用的指令；只认 OK，
    没装配、非 OK、超时、超预算一律 `check_failed`、放行无效）；账本差集（`LedgerDiff`，快照与现档的资产通道账本逐流比较，立即可得）；回收逆转（快照之后原因 17 / 19 的
    扣减流水，7.2c 之前恒干净）。帮会 / 账本的分歧与不可证明可用 `acceptDivergence` 放行，回收逆转用 `acceptRecallReversal`。
  - **整区 / 全服**：目标 = `player.zone_id` 属于这些区的玩家（`idx_player_zone`）；每个区必须是 MAINTENANCE 或 CLOSED，否则 409 `zone_open`；一律 kick；
    任何一人夺不到 → 全部释放、零写入，REJECTED `zone_not_quiescent`；没有快照的玩家只报告（`no_snapshot` / `created_after_target`）；超过 10000 人 422 `plan_too_large`，不自动拆批。
  - **dry-run**（`dryRun=true`，同步 200，只读、不夺权、不要写开关）：给计划、按已落盘现档预演的恢复内容与账本差集；不做帮会检查，整区不校验维护态。
- **整区维护前快照**（`ZoneSnapshotService`，`POST /admin/zone-snapshots`）：作业（统一审计与单飞），按区分批（每批 500 人一个事务）拍 PRE_MAINTENANCE 快照；
  不夺权、不踢人、不要写开关。
- **查询**：`GET /admin/ops-jobs?status=&kind=&limit=`、`GET /admin/ops-jobs/{id}`（含事件）、`GET /admin/ops-jobs/{id}/players?after=&limit=`。
- **帮会检查的调用方**（`DubboGuildInternalClient`）：编程式 `IsolatedDubboModule` 引用（同 xm-scene / xm-guild 的资产通道客户端，`retries = 0`），第一次检查时才建；
  只支持直连 `xm.dubbo.guild-url`（为空 = 没装配），nacos 发现未接。写开关打开而缺 `XM_DUBBO_SECRET` 拒绝启动。
- **配置**（环境变量）：`xm.data.ops.enabled`（`XM_DATA_OPS_ENABLED`）、`claim-wait` 35 s（`XM_DATA_OPS_CLAIM_WAIT`）、`min-target-age` 5 min（`XM_DATA_OPS_MIN_TARGET_AGE`）、
  `max-players-per-job` 10000、`job-timeout` 30 min、`heartbeat` 5 s、`stale-after` 60 s；`xm.data.rollback.guild.settle` 30 s（`XM_DATA_ROLLBACK_SETTLE`）、
  `recheck-delay` 10 s（`XM_DATA_ROLLBACK_RECHECK_DELAY`）、`check-budget` / `recheck-budget` 120 s、`clock-skew-margin` 300 s、`call-timeout` 10 s；
  `xm.dubbo.guild-url`（`XM_DUBBO_GUILD_URL`，缺省 `tri://127.0.0.1:20886`）。本机切片脚本打开写开关并调小沉降与复查等待。
- **回档前查战斗锁**（批次 6.3 落地，`com.game.data.rollback.BattleLockGate`；data-ops-spec §13.3）：夺权挡不住战斗中的玩家——Java 断线即写回并释放，战斗在 xm-battle 继续，
  `reject` 也能夺到战斗锁仍在的离线玩家，`kick` 的顶号通路不看是否在战斗。所以两轮夺权之后、账本差集之前，对全部已夺到的玩家批量读一次 `xm:battle:{pid}:lock`
  （xm-discovery `BattleLockReader.existsAll`，每块 500 人、全程共用一个截止时刻 `xm.data.ops.battle-lock-wait` 5 s）：锁在 → 逐玩家结局 `in_battle`（对应基线回档对战斗中玩家回 1005）；
  读不到 → `battle_lock_unknown`（fail-closed）。两种都不写、无条件（不走 `acceptDivergence`）；单人 / 多人里被挡的人当场释放、其余继续，整区有一人被挡即 `zone_not_quiescent`。
  dry-run 在请求线程上同样读一次并逐人标出。Redis 客户端每次读锁时才取，装配期不连接。它读的是咨询性的读路由，部署约束见 §6。

### 4.6 资产防护：全服产出封禁与获取异常检测

mmorpg：`GainBlockService` 的全服名单是 thread_local 集合、没有任何写入口（生产上不可用）；`AnomalyDetector` 阈值写死、告警发一个
没有消费者的 Kafka topic。Java 版把两者做成能用的：

- **全服产出封禁**（紧急止血：名单上的币种 / 物品所有人都不得再获得）：名单在 Redis Hash `xm:gain-block:{currency|item}`（字段 = 币种号 / 物品配置号，值 =
  操作人 / 时刻 / 原因 JSON），全服一份、不分 zone。唯一写者是 xm-data 运维接口：`GET /admin/gain-blocks`、
  `PUT /admin/gain-blocks/{currency|item}/{id}?reason=`（幂等）、`DELETE /admin/gain-blocks/{currency|item}/{id}?reason=`（幂等），鉴权同 §4.5，
  改动另记一行 `xm.audit.admin`（带原因）。写完发 pub/sub `xm:gain-block-changed`（尽力而为）。
  scene 的 `GainBlockSync` 在专用单线程 `scene-gain-block` 上读名单（阻塞 I/O 不上逻辑线程；单线程串行，旧快照盖不过新的），
  不可变快照 `GlobalGainBlocks` 投递到逻辑线程换进 `CurrencyService` 与 `BagService`。触发：启动时同步读一次（读不到拒绝启动；第一份在接受 gate
  链路之前排进逻辑线程）、收到通知就重读（重读期间的多次通知合并成一次）、`xm.scene.gain-block-refresh`（缺省 10s）周期兜底
  （通知会丢）。重读失败沿用上次名单，计 `xm_scene_gain_block_sync_failures_total`，`xm_scene_gain_block_sync_age_seconds`
  随之增长（要告警）。判定顺序同基线 AddCurrency：参数（1005）→ 全服封禁 → 本人封禁（都是 27005）→ 入账；被全服名单拒绝的计
  `xm_scene_gain_blocked_total`。xm-data 的 Redis 客户端是懒加载的，不挡启动（批次 7.2a 起由发号线程在启动后后台连、连不上每 10 s 重试，§4.5）：Redis 不可用时这三个接口回 503，审计消费不受影响。
  物品被全服名单拒绝回 1005（基线物品口径，与币种的 27005 不同），判在入包规则之前（数量为 0 也回 1005）。
- **获取异常检测**（`GainAnomalyDetector`，只告警、不拦截）：每玩家 × 每币种 / 每物品配置一个滑动窗口（两类分开存；挂在场景内的玩家实例上，不持久化，
  离开 / 换实例即清空），成功加币 / 入包后记一次（量取请求数额）。阈值 `xm.scene.anomaly.*`（缺省同基线：600 秒内超过 50 次或累计超过
  100000），可按币种 / 物品配置覆盖（`xm.scene.anomaly.currency.<币种>.*`、`.item.<配置号>.*`；物品告警的 currency_type 填 none）；某一维填 0 只关这一维。越线时告警一次（日志 `xm.audit.anomaly`
  带玩家号 + `xm_scene_gain_anomalies_total{category, currency_type}` 不带玩家号），仍在线外的后续获取不重复告警，某次获取到来时窗口已回到线内（含过期清空）就重新武装。
  累计量饱和于 `Long.MAX_VALUE`（不回绕）。

### 4.7 背包（四个固定包、批量入包、整理、持久化）

mmorpg：`cpp/libs/modules/bag/*`（实例层 / 布局层 / 准入 / 淘汰，规划 → 预留 → 提交）+ `BagService` 编排 + `bag_marshal` 落盘。Java 版：

- **领域对象**（`com.game.scene.player`，只在逻辑线程上读写）：`PlayerBags` 持四个 `Bag`——人物背包（100）、仓库（200）、装备栏（10）、
  临时格（200），编号即协议 / 存档里的 bag_type。`Bag` 是实例（guid → 物品）+ 格子（格子号 → 物品）两层，实例数 ≤ 容量。
  扁平包 first-fit；装备栏按 `Item.equip_kind` ↔ `EquipSlot` 行分桶（同部位的几种配置共享槽位预算，槽号须小于容量），永不淘汰、永不重排；
  临时格满了按（入包序号, guid）淘汰最早的实例，先算到不动点（被挤掉的零头不能再并入）再动手。
- **写入口只有 `BagService`**（`com.game.scene.bag`）：冻结闸（跨节点换图冻结中回 1005，同基线，§8.1）→ 全服物品禁发（1005）→
  `Bag.add` 整批原子（规划 → 一次铸齐号 → 淘汰 → 并堆 → 切新实例；任何失败零写入）→ 每个配置一条入包流水 + 物品获取异常检测，
  每个被淘汰的实例一条销毁流水。战斗中禁止扣减之类的闸放在各玩法入口（基线 D48：结算在战斗标记还挂着时应用）。
  物品 guid 来自全服号源（`LeaseGatedSnowflake`，§9），一批要的号先一次铸齐，铸不出来回 6004。
- **整理**（192）：只允许人物背包与仓库；合并同配置零头、回收空实例（各记一条数量 0 的销毁流水）、按（配置升序、数量降序、入包先后）重铺到 0..n-1；
  已最优（按相邻格比较）时什么都不改、changed=false——狂点整理不刷流水、不触发存盘。回合制战斗在途时 192 回 1005（批次 6.3，闸在 `BagFeature.sortBag`、类型合法之后；`BagService` 本身不判战斗在途）。
- **战斗结算用的两个入口**（批次 6.3，§4.23）：`BagService.removeClamped`（每个配置按实际持有夹紧、扣不满不报错，按格子号升序抽取，每个被抽的实例记一条销毁流水）与
  `mergeOnly`（只合并不重排，同基线 `MergeAndCompact(kMergeOnly)`）。两者都不判战斗在途——结算要在战斗冻结还没摘时写背包。
- **持久化**：`player_state.bag`（`BagState` / `BagItemState`，Java 自有格式），按 (bag_type, 格子) 升序写出（周期存盘按值比对），
  容量只在不是缺省值时才存，各层不认识的字段原样带回。加载分两步：构造玩家实例时只原样收下（构造不能抛），进场景前
  `BagService.initializeOnLoad` 按配表规整——结构性损坏（guid 为 0 / 全 1 或重复、物品数超过容量）抛异常按进场失败处理（fail-closed，
  坏档不会被当成空包存回去），格子问题重新落位，不认识的 bag_type 原样隔离写回。与位置、货币同一份记录、同一次写（围栏写回）。

### 4.8 任务、条件与活动列表

mmorpg：`cpp/libs/modules/{mission,condition}/**` + `PlayerMissionSystem`（接取闸 / 回填 / 领奖）+ `PlayerActivityScheduleSystem` +
`player_feature_snapshot`（列表）。Java 版（`com.game.scene.mission`，只在逻辑线程上调用）：

- **配表视图 `MissionTables`**（不可变）：任务行、条件行、奖励（按基线 BuildRewardItems 合并成物品 → 数量）、活动排期。表快照不变，
  所以基线每次接取都要重算的**静态闸**在加载时一次算好：条件是否有事实来源（击杀 1 / 等级 6 / 完成任务 8，其余类别与有时效的条件 1003）、
  击杀条件有没有打得到的怪（副本 × 怪物表，空列表 = 任何可达怪物）、比较符 / 目标 / 计数方式是否合法（1002 / 1003）。只依赖玩家状态的判定
  留给服务，错误码先后同基线。表是同步来的契约，加载只对用不上的数据告警、不拒绝启动。
- **事实与推进**：事实是 `MissionFact(类别, 参数, 量, 只推进某任务)`（不复用同步来的 proto 类型）。来源：GM 175 成功后的等级事实
  （`AttributeFeature` 的等级连带，推 170 之后、回应答之前，等级没变也发）、接取回填、完成任务事实；击杀事实的入口
  `MissionService.onMonsterKilled` 由回合制战斗结算调用（批次 6.3，§4.23：结算里每只被击败的怪一条事实）。纯规则在 `ConditionRules`（比较符、参数匹配、累计封顶 / 持有覆盖）。
  玩家身上的派生索引（类别 → 关注它的进行中任务、被占用的类型）不持久化，加载时重建、接取 / 完成时维护。
- **完成后的连锁同步执行**：一次操作（接取 / 一条事实）开一个有界工作队列，在本次操作结束前排空：同一条事实按任务号升序扇出，
  本条事实完成的任务按任务号升序处理（腾出类型 → 记完成 → 有奖励置待领、自动领奖的立即领，失败保留待领 →「完成任务 X」事实与后续任务排队）；
  先排空全部事实再逐个走完整接取闸接后续任务（接取失败只记日志）——否则后续任务的接取回填与在途的完成事实会重复计数。
  基线把这三件事塞进事件队列而线上从不派发，Java 按设计意图做（PARITY 记为有意差异）。
- **领奖**：领奖闸只看资格（不看背包空间，同基线），物品经 `BagService` 整批进人物背包，流水原因 `QUEST_REWARD`（TX_QUEST_REWARD = 7，
  extra 带任务号），成功才清待领；失败（满包 6006、发不出号 6004、全服禁发 1005）保留领奖资格。
- **持久化**：`player_state.mission`（`MissionState`：进行中（每格进度、接取时间）/ 已完成 / 待领，都按任务号无符号升序写出），
  不认识的字段原样带回，从没接过任务的不写。存档不校验：格数与表不符的任务永不推进（同基线），表里删掉的任务照样显示（configured=false）、
  不占类型、不再推进。
- **客户端**：`MissionFeature`（193 / 194 / 195 都回完整列表，失败只回 tip）、`ActivityFeature`（190：任务类型 2 的行按排期算状态，
  窗口 `[start, end)` 按 uint64 无符号比较，开放时再跑接取闸决定能否参与）。列表每行都跑只读的接取 / 领奖闸，一次请求只读一次时钟。

### 4.9 放技能（校验链、施法阶段、冷却）

mmorpg：`combat/skill/system/skill.cpp`（ReleaseSkill → CheckSkillPrerequisites → 70 → 前摇定时器）+ 行为 / 战斗状态系统。Java 版（`com.game.scene.skill`，只在逻辑线程上调用）：

- **配表视图 `SkillTables`**：技能行（类型位、目标方式位、能否打断、前摇 / 后摇 / 引导时长换成纳秒、冷却组）、冷却组时长、
  行为互斥表、战斗状态表、技能许可表。表是同步来的契约，加载只告警。
- **运行态 `PlayerSkillState`**（挂在场景内的玩家实例上，不持久化，接管 / 重新进场即清空）：进行中的一次施法（阶段 + 截止时刻）、
  冷却组开始时刻、行为状态、战斗状态（只有实时 buff 会写，两版线上都恒空）。
- **`SkillService.release`**：顺序同基线（技能存在且已拥有 → 目标 → 冷却 → 施法阶段 → 技能许可 → 行为互斥 → 战斗状态互斥），
  纯规则在 `SkillRules`。阶段**不起定时器**：截止时刻存在玩家身上，下次放技能时按截止时刻顺次结算（前摇 → 引导 → 后摇，
  每段从上一段的截止时刻起算），阶段进行中时新技能可打断则推 33 并取消旧施法，否则 7000。冷却、后摇、引导按设计意图生效
  （基线线上只有前摇生效，PARITY 记为有意差异）。70 / 33 发给施法者本人 + 看得见他的人（`SceneWorld.broadcastToSelfAndWatchers`），
  给施法者的顺序 33 → 70 → 84 应答。目标按场景实体号在本节点上找人（`SceneWorld.playerByEntity`，任意场景，同基线）。
- **伤害**：纯公式 `com.game.common.combat.CombatDamageRules`（xm-common，实时与回合制共用）已移植；实时技能命中（伤害、效果 buff、
  死亡）两版都不生效，等气血同步、死亡复活一起设计。
- **回合制战斗在途**（批次 6.3，§4.23）：施法者在途回 7004（排在技能存在且已拥有的 1001 之后）；指定目标的技能，目标是本节点玩家但在途回 7002
  （范围 / 无目标技能不看目标是否在途，同基线）。

### 4.10 当前气血 / 法力与基础复活

- **存储**：`player_state.vitals`（`Vitals{health, mana}`，uint64）。满血满蓝时不写，读回来没有这段就按上限回满——上限没被调高时
  与写出结果一样，改表调高上限后满血玩家读档到新上限（基线保留旧绝对值，PARITY「死亡 / 复活」②）；新号与老存档也走这条；不满时写出。
  二级属性（上限）仍每次加载重算，不落库；成长属性不存：护甲 / 速度同基线每次重算（速度取二级属性），力量 / 抗性 / 暴击用到时由职业表即时算出。
- **加载**（`AttributeService.initializeOnLoad`）：存档值按新上限往下夹（旧上限视为 0，同基线 RescaleCurrent：活着的只夹不补），
  再套纯规则 `PlayerRevive.reviveIfDead`：气血 0（阵亡或没有记录）→ 按上限回满；活着（含残血）不动。
- **写入点**：属性重算（升级补增量、其余按比例），以及回合制战斗结算（批次 6.3，§4.23）：气血、法力取结算终值，各自在派生上限 > 0 时夹到上限，
  再套同一个 `PlayerRevive.reviveIfDead`（只在气血为 0 时回满）；不推 170 / 66（同基线）。备战时气血为 0 的玩家被拒（1006）。
  实时伤害两版都不可达，没有实时死亡状态。

### 4.11 宝宝

mmorpg：`player_pet.cpp`（PetSystem）+ `pet_rules.h` + `player_pet_handler.cpp`。Java 版：

- **领域对象** `com.game.scene.player.PlayerPets`（只在逻辑线程上读写）：实例按获得顺序（号、种类、名字、等级、已分配、资质、
  当前气血法力、获得时间）+ 出战号；存 `player_state.pets`，没有宝宝时整段省略。二级属性与点数总量不落库、不缓存，需要时现算
  （资质 + 已分配 + 等级是唯一真相）。
- **规则** `com.game.scene.pet`：纯规则 `PetRules`（等级跟随主人并受种类上限夹、维度值、二级属性按资质放大、成长率）；
  `PetTables`（Pet / PetRule + 宝宝池 owner_type=1 的维度，按维度号升序——资质槽位按这个顺序对应）；`PetService` 是宝宝的唯一写入口，
  点数总量 / 目标已分配校验 / 自动加点 / 按比例保持气血复用角色的 `AttributeRules`；`PetFeature` 注册 181–189。
- **主人等级连带**：`AttributeFeature` 的等级连带在推 170 之后先让 `PetFeature` 重算全部宝宝并推 184（没有宝宝也推空列表），
  再发任务等级事实，最后回 175 应答（同基线升级事件顺序）。
- **号与随机**：宝宝号用与物品同一个全服号源（§9）；资质随机数只在逻辑线程上用。写闸（`PetService.checkWritable`）：跨节点换图冻结中回 1005（批次 5.2，§8.1），
  回合制战斗在途回 26008（批次 6.3，六个写方法；自动加点 188 只给建议、不过闸）。
- **参战**（批次 6.3，§4.23）：`PetService.buildBattleSnapshot` 给出战宝宝拍快照（没有出战 / 气血 0 / 缺表行的不带；技能先种类后已学、不做可施放过滤，同基线）；
  `applyBattleSettlement` 回写气血法力（按现算上限夹，阵亡回满），有宝宝条目时推一次 184、先于 150。这两条不经写闸——应用结算时战斗冻结还没摘。

### 4.12 通用资产通道（scene 侧）

mmorpg：`asset_op_system.cpp` + `asset_op_ledger.cpp` + `asset_op_auth.cpp`（scene），调用方 go guild / trade。用途：别的服务给**在线**
玩家扣货币、发货币与物品，每条恰好生效一次。Java 版 scene 侧在 `com.game.scene.asset`，调用方帮会经济（4.5，§4.18）已接入、交易随写侧批次：

- **契约**（`xm-api` `xm.api.AssetOpRequest / AssetOpResponse`，Java 内部自有格式，字段语义与数值同基线）：请求带 (player_id, 流, 流纪元, seq)、
  关联号、流水原因、一包资产、调用方签名；应答是结局 APPLIED（partial）/ REJECTED / RETRY / NOT_HERE / UNKNOWN + 原因 tip + durable。
  调用方只在 APPLIED / REJECTED 且 durable 时终结这条 seq，否则用同一 seq 重查；RETRY / NOT_HERE / UNKNOWN 都没记账、不得终结：RETRY / NOT_HERE 稍后照常重投，UNKNOWN（信封畸形、验签失败、纪元过期、跳号过远、seq 滑出窗口）要告警转人工。
- **账本** `AssetOpLedger`（领域对象，存 `player_state.asset_ledger`，与资产同一份记录、同一次围栏写，不存在「资产落了账本没落」）：
  每条流 1024 位窗口（seen / applied 两组位）+ 拒绝原因环 + 部分发放名单，规则逐条同基线；加载时校验，不自洽就原样保留并让该玩家
  的通道 fail-closed（RETRY 27005），绝不把坏账本当空账本（那会把已应用的 seq 重新看成未见、二次扣款）。
- **验签** `AssetOpAuth`：每个调用方一把密钥、独占两条流（guild：1 / 2，trade：3 / 4；SYSTEM_CREDIT 没有合法调用方），密钥只从环境变量
  `XM_ASSET_OP_SECRET_GUILD / _TRADE` 注入；规范串格式与基线逐字节相同。
- **流程** `AssetOpService.handle`（逻辑线程）：信封 / 流方向 / 流水原因白名单 → 验签 → 找人 → 账本损坏 → 分类（已见只读答复；滑出窗口 /
  跳号 / 旧纪元回 UNKNOWN 0 不猜结局；已见的 seq 在跨节点换图冻结中照常只读答复，但不触发补存）→ 归属围栏（owner_epoch 为 0，
  或玩家处于跨节点换图的冻结中，回 RETRY 27003，什么都不记——含中止占位；选目标中照常处理，§8.1）→ 中止占位 → 回合制战斗在途（批次 6.3，§4.23：扣款 / 发放回 RETRY 27002、不记账，
  调用方稍后重投；27003 优先，中止占位照常放行）→ 包内容（确定性失败记 REJECTED 27004）
  → 扣款（余额不足记 REJECTED 27000）/ 发放（货币预检 → 物品整批 → 货币）。闸只在这一层，不下沉到 `CurrencyService` / `BagService`。
- **durable**：只看最近一次确认落库的快照（`ScenePlayer.persistedState()`）里有没有这条结局，不另建水位；记账后立刻
  `SceneWorld.requestSave`，不在调用里等落盘；已见未 durable 的重查 500 ms 内至多补存一次（限频时刻挂在账本实例上，不持久化）。
- **入口** `AssetOpEndpoint`：任意线程调用，投递到逻辑线程、future 带回结局。
- **跨进程传输**（批次 4.5）：scene 在 `xm.scene.asset-rpc-port`（缺省 21100）上以独立的 Dubbo 模块导出 `SceneAssetOpService`
  （debit / abortDebit / credit，group `scene-asset`，Triple，不注册、调用方按节点直连，`retries=0`），请求交逻辑线程处理、应答在
  `scene-asset-reply` 执行器上完成，在途上限 `xm.scene.asset-op-max-inflight`（缺省 256，超出即回过载）；导出先于目录条目带上
  rpc_host / rpc_port，停服时先摘目录、写回玩家再撤导出。两层鉴权：请求体 HMAC（规范串的唯一出处是 xm-api `AssetOpSignatures`）+
  Dubbo 调用方 MAC（`XM_DUBBO_SECRET`，xm-scene 从此必填）。调用方按 `xm:location` 定位（只认 `o`；`l` / `x` / 缺失 → 本地 NOT_HERE）
  再查 scene 目录拿 rpc 地址（`SceneAssetLocator`）。已落盘账本的只读分类器在 xm-player-store（`PersistedAssetLedger`），调用方离线读用它。
- **同一端口上的第二个服务**（批次 6.3，§4.23）：导出类改名 `com.game.scene.rpc.SceneRpcServer`——一个 `IsolatedDubboModule`、一个 `ProtocolConfig`、两个 `ServiceConfig`：
  资产通道 `SceneAssetOpService`（group `scene-asset`）与回合制战斗入口 `SceneBattleService`（group `scene-battle`，备战 / 取消 / 确认 / 结算四个方法），
  都是 `register = false`、调用方按节点目录直连、`retries = 0`。两个服务各有自己的在途上限（`asset-op-max-inflight` / `battle-rpc-max-inflight`，缺省都是 256），
  共用回写执行器 `scene-asset-reply`；任一个导出失败就把已导出的撤掉、整体失败。启停顺序不变（导出成功后才写目录；停服先摘目录、写回玩家、再撤导出）。
  配置键、Dubbo 应用名与回写执行器名都没改，运维配置与指标序列不变。
- **接管守卫**（`SceneWorld` 进场）：本节点接管失去归属的旧实例时，库里仍是旧实例最近一次落库的样子才沿用旧内存，否则以库为准——
  别的节点期间写过库（含已回报 durable 的资产结局）时，沿用旧内存会把它们盖掉。
- **补缴欠款**（`Wallet.Debt`，休眠：没有挂欠款的入口，同基线）：加币时未冻结、未过期的欠款先抵 min(收入, 剩余)，还清即删；
  流水先记 +收入、再记 −抵扣（TX_DEFERRED_CLAWBACK），共用关联号、前后余额首尾相接。

### 4.13 好友服务（xm-friend）

mmorpg：`go/friend`（go-zero gRPC，经 client_rpc_router 转发）。Java 版是独立进程 xm-friend，规格与逐条出处见
[docs/porting/friend-spec.md](../porting/friend-spec.md)：

- **路由**：gate 按消息号所属服务 `ClientPlayerFriend` 经 Dubbo group `friend` 调 `ClientMessageService.handle`
  （`@DubboReference` 对 `handle` 显式 `retries = 0`：写路径不幂等）。gate 给每个后端域一条**按会话的在途队列**：
  同一会话的好友请求之间仍串行（写后读读到新值），但不占 login / scene 的在途位——好友最坏 5 s 的调用不会让移动等消息排满
  `max-pending-requests` 而断线（mmorpg 的 C++ gate 路由模式本来就不按会话串行）。
- **准入**（`FriendDispatcher`，按消息号）：10 个 C2S 方法放行；上行 235（`NotifyFriendEvent` 是服务端推送）与会话还没绑定玩家
  （`player_id = 0`）回信封 1003（基线是会话拦截器拒绝、路由服翻成信封 1003）；请求体解析失败回信封 1003；工作队列满 / 处理器异常回
  in-band 1003。「我」只取会话里的 `player_id`，入口挡住目标为 0 / 自己（否则落到存储层的非法玩家对、变成 1003 假告警）。
- **线程**：Dubbo 线程只投递；处理在 `friend-worker` 有界池上（阻塞 JDBC、限时等 Redis 结果），每个请求以受理时刻 + 3.5 s 为截止
  （须先于 gate 的 5 s Dubbo 超时：否则客户端看到失败而写已落库，重试撞「已申请」）。截止时刻一路传到 JDBC：每条语句的查询超时取剩余预算，
  预算用完不再发语句、不再重试，守卫事务提交前再查一次（过了就回滚）。gate 给后端队列里的请求带<b>入队时</b>的会话身份快照
  （排队期间离开游戏 / 换角色进游戏，不会把排着的好友请求算到新角色头上）。
- **存储**（`JdbcFriendStore`，四张表 `friend` / `friend_request` / `friend_capacity` / `friend_block` 由 Java 自有的
  `xm/friend/friend_tables.proto` 经 xm-pbmysql 建，形状同 mmorpg `friend_table.proto`，**没有唯一键**）：连接池会话级 READ COMMITTED；
  Add / Accept / Remove / Block 共用「守卫事务」——事务外按玩家号升序补容量行（权威边数 COUNT + ODKU，1213 整对重跑 ≤ 3 遍）→ RC 事务里
  第一把锁是升序的容量行 `FOR UPDATE`（缺行回滚重补 ≤ 3 遍，用尽 fail-closed）→ 之后只用完整主键的等值点查 / 点更新 → 业务拒绝回滚。
  这让同一对玩家的写路径两两互斥、跨玩家对不成环（测试钉住 16 并发的各类交错不出 1213、四条不变量成立）。
- **缓存**（`FriendCache`）：好友列表 / 入站申请缓存在 Redis（`xm:friend:{pid}:list|req`，JSON、TTL 30 min），读者先读代次再回源、
  回填时比较代次；写者提交后把代次写成「唯一值 + Redis 服务器时间」（带 2 × TTL 过期，Redisson 重发同一段 EVAL 也不会写回旧值）并删数据键，
  读者遇到代次缺失时自己建一个、回填把缺失当成不符——没有永久的代次计数器、不怕淘汰或重发引起的 ABA；
  同一键的回源单飞、等待者限时。读失败 / 值坏 → 1003（不让 Redis 故障把全服列表读压到 MySQL）；回填 / 失效失败只记日志与指标。
  **失效完成之后才推送**：对方收到推送立即拉取时读到新值。黑名单不缓存（直读）。
- **在线与资料**：好友在线态每次现取 `xm:presence`（严格批量读：有一个条目读失败或损坏整体回 1003，「在线状态未知」不能当离线），
  `last_active_ms` 用 `online_since_ms`；展示资料（名字 / 等级 / 职业 / 性别 / 外观 / home zone）批量读 `xm_java.player`（每批 64，
  失败只记日志、照常返回）。
- **推送**：只有发申请（推给对方 REQUEST_RECEIVED）与同意（推给原申请人 REQUEST_ACCEPTED），经 `PlayerPushes`（§4.3），
  至多一次、fire-and-forget；拒绝 / 删除 / 拉黑不推（不暴露拒绝方在线、被拉黑者不该知道）。
- **配额**：每人每分钟 10 次发申请（INCR + EXPIRE 一段 Lua，固定窗口）；Redis 出错放行（防刷不是防作弊，硬上限都在 MySQL 里）。
- **推荐**（RecommendFriends，`RecommendService`）：普通推荐两级——好友的好友（共同好友数降序、同数随机）→ 随机锚点兜底
  （在 friend 表真实的 MIN / MAX 之间取锚点，只看锚点起 1024 个去重玩家的窗口，不回绕）；两条 SQL 逐字照搬基线（`STRAIGHT_JOIN`、
  按方向拆开的 NOT EXISTS、`SEMIJOIN(FIRSTMATCH)` + `FORCE INDEX (PRIMARY)`），扫描量与全服 pending 数、「拉黑我的人数」无关；
  任一级出错回 1003、不做部分降级；在线态读不到就当离线。exclude 超过 64 条回 1005（不截断）。
- **在线目录**（`online_only`）：在一段只读 Lua 里 `SCAN xm:presence:* COUNT 64`，每页至多 4 轮、单批 ≤ 1024 个键、键按字典序，
  游标 `v1:<scan>:<offset>`；条目存在、能解码、玩家号一致就算在线，资料与 home zone 取 player 表（名字为空、职业为 0、与调用者 home zone
  不同的不列）；每人每分钟 60 页（故障时拒绝）；先校验游标与 query 再计额度。不排除好友与拉黑关系（同基线）。
- **清理**（`FriendSweep`，缺省 `report_only` 只数不删）：终态好友申请（过保留期、`status IN (2,3)`）与零好友容量行，候选普通读后
  逐行按主键自动提交删（WHERE 复核条件），存在 `updated_ms = 0` 的终态行时只数不删；首轮随机抖动、固定延迟不叠轮、单轮预算 min(间隔, 30 s)。

### 4.14 聊天服务（xm-chat）

mmorpg：`go/chat`（v1：世界频道 + 私聊）。Java 版是独立进程 xm-chat（Dubbo group `chat`，端口 20884），gate 经同一套按会话的后端队列转发：

- **数据只在 Redis**：世界频道 `xm:chat:{world}:log`（全服一条）、私聊 `xm:chat:{p:<小号>:<大号>}:log`（双方同一把键）——LIST、新在前，
  每次写入「LPUSH + LTRIM 到 200 条 + 续期 7 天」一段 Lua；发言幂等键 `xm:chat:{req:<pid>}:<request_id>`、限速计数 `xm:chat:{rl:<pid>}`。
  历史是尽力而为的窗口（同基线：Redis 重启即清空），生产 Redis 须 `noeviction`。
- **发言**（61）：先做全部纯校验（会话 → 消息 → 频道 → 内容 ≤ 512 字节且 trim 后非空 → request_id ≤ 64 字节），再按 幂等 → 限速 → 写入 碰 Redis：
  幂等键先占成 `pending:<随机 token>`（SET NX EX 60），写入成功后改 `done`，占住之后任何一步失败都释放（值相等才删）；同 request_id 重发读到
  done 回成功、读到 pending 回 1008；限速每人每秒 5 条。发言人取会话、时刻由服务端盖、内容存 trim 后的值；不推送（各自拉取）。
- **拉取**（28）：LRANGE 最近 N 条（0 取 20，上限 50），坏条目跳过，按 send_time_ms 稳定倒序；私聊键由（会话里的自己, peer）决定。
- **错误语义同基线**：业务结果全部 in-band（tip 不带参数）；会话没绑定玩家回 in-band 1005（chat 的会话拦截器放行到逻辑层）；
  队伍 / 系统 / 未指定频道 1006；Redis 故障 1003。处理全程异步（只发 Redis 异步命令），不占 Dubbo 线程、不需要工作线程池。

### 4.15 按方法热关停（运维止血阀）

mmorpg：`go/shared/killswitch`（etcd `/mmorpg/killswitch/<规则键>`，各 Go 服务的 gRPC 拦截器）。Java 版：

- **规则源**：Redis 哈希 `xm:killswitch`（字段 = 规则键，值 = 规则），经 xm-data 运维接口 `GET / PUT / DELETE /admin/killswitch` 读写（带令牌、进审计日志）。
  打开 `xm.killswitch.enabled` 的进程（gate / login / scene-manager / friend / chat）每秒全量读一次（`RedisKillSwitchSync`），
  整份替换进程内快照（`com.game.common.killswitch.KillSwitch`，原子引用，热路径只读）。字段名经 `KillSwitch.normalizePattern`
  去首尾空白与开头的斜杠（读规则、运维接口写 / 删都用它；几个原始字段规范化后同名时规范形字段胜出、记 ERROR；DELETE 把同名的原始字段全删）；
  值同基线：空 = 放行，JSON `{"deny":bool,"reason":"…","code":0–16}`（顶层值之后还有内容算写坏），或 true / 1 / on / deny / yes 与
  false / 0 / off / no / allow；写坏的值整条忽略、打 ERROR。
- **失联**：读失败保留上一份快照；距上次成功同步超过 `xm.killswitch.stale-after`（缺省 60 s）整份作废、全放行（同基线 fail-open：止血阀不能变成停服开关）。
- **匹配**（同基线顺序，首个命中即定）：`pkg.Service/Method` → `Service/Method` → `pkg.Service/*` → `Service/*` → `*`；精确规则写 `deny=false` 可以把自己从通配里豁免。
- **两处检查**：
  - gate 按客户端方法查（`/friendpb.ClientPlayerFriend/AddFriend` 这种全名，由消息号路由表带出），位置在体积 / 限频 / GM 闸之后、转发之前，
    对 login / scene / friend / chat 全部客户端方法生效；命中回信封 1003、不计非法包、结果计 `killed`。
  - Dubbo 提供方过滤器（`KillSwitchProviderFilter`，在鉴权之前）按本仓库接口方法查（`com.game.api.SceneDirectoryService/assign`、
    `AccountLoginService/*`，方法名区分大小写），命中抛 `RpcException(FORBIDDEN)`、不进业务。**不查 `ClientMessageService.handle`**：
    那是 gate 转发客户端消息的通道，客户端方法已在 gate 上查过；再按它查一次，全局 `*` 会把 gate 已精确豁免的客户端方法再拦下来。
- **指标**：`xm_killswitch_rules`、`xm_killswitch_sync_failures_total`、`xm_killswitch_blocked_total{method}`（方法名来自路由表 / 接口定义，有界）。

### 4.16 组队服务（xm-team）与场景跟随

mmorpg：`go/match/internal/team`（与匹配同进程）+ C++ scene `player_team`。Java 版是独立进程 xm-team（Dubbo group `team`，端口 20885，
管理端口 18109），gate 经同一套按会话的后端队列转发 `ClientPlayerTeam` 的 12 个请求（`handle` 不重试）。规格与逐条出处见
`docs/porting/team-spec.md`：

- **数据只在 Redis**，四类键统一 hash tag `{team}`（一段 Lua 要原子写记录、投影与多名玩家的索引）：权威记录 `xm:{team}:rec:<tid>`
  （Hash：`ver` 版本号 + `pb` = xm-team 自有 proto `TeamRecord`）、投影 `xm:{team}:info:<tid>`（`xm.discovery.TeamInfo`，scene 读）、
  玩家索引 `xm:{team}:player:<pid>`（`tid` + 成员关系版本 `epoch`）、被邀请人反查 `xm:{team}:invite:<pid>`（ZSET）。空闲 24 h 过期。
- **读 → 纯规则 → 按 ver CAS 写**：`TeamStore.mutate` 一次 S_READ（索引 + 记录 + Redis TIME）→ `TeamRules`（纯函数，不读墙钟）→
  S_COMMIT（ver CAS、新成员不在别队、保留成员索引未错位、被邀请人待处理邀请上限，判定段只读、拒绝不写）；冲突累计 3 次回 4029，
  保留成员索引错位时先提交 HEALED 修复再重算，孤儿索引自愈。七段 Lua 与基线 scripts.go 同义，ByteArrayCodec（pb 是任意字节）。
- **唯一时钟源是 Redis TIME**：记录里的时间、过期、视图的 `server_time_ms` 都取它；客户端按 `(membership_epoch, version)` 排序视图。
- **在线四态**：`xm:presence` 在 → ONLINE；否则 `xm:location` 的状态 `o` / `l`（重连租约）→ PRESENT；没有位置或 `x` → ABSENT；
  读失败 / 损坏 → UNKNOWN（逐成员 fail-closed）。只有 ABSENT 触发惰性转让队长（30 s 重连宽限内不转）。
- **展示资料与 home zone** 读 `xm_java.player`（`PlayerProfiles`，好友共用）；team_id 由全服雪花租约（`NodeTypes.TEAM`）发。
- **推送**经 `PlayerPushes`：213 队伍快照（每个收件人一份视图：epoch 是收件人自己的、申请 / 邀请列表只给队长）、215 邀请、203 事件；
  调用者本人不收自己这次提交的快照（以回包为准）；推送在 `team-push` 执行器上按落盘顺序串行，整批预算 3 s，至多一次。
- **错误语义同基线**：业务结果全部 in-band（team 段 tip，parameters 只放十进制玩家号）；会话没绑定玩家回 in-band 4001（不是信封 1003：
  客户端遇到信封错误会停用本连接的组队）；依赖故障 / 过载回 in-band 4030；请求体解析失败回信封 1003。整队开战（211）在 4.3 恒回 4027
  （没有副本开放组队）；批次 6.4 已换成真实端口，见下一条。
- **整队开战（211，批次 6.4；team-spec §5.5，match-spec §7.3–§7.6）**：xm-team 持有开战锁与编排，预检、建票、开局交给 xm-match（§4.24）。`TeamBattlePort`（实现 `MatchTeamBattle`，
  引用装配在 `TeamDubboConfiguration`：group `match`、`retries = 0`、直连 `xm.dubbo.match-url`）对应 `MatchTeamService` 的四个方法。流程同基线：队长与锁检查（4018 / 4023）→
  `checkTeamMatch`（副本人数 4027 / 4028 与逐成员预检 4024 / 4025 / 4026 合成一次调用，判定次序不变；非 0 结果带本轮读到的同源视图；应答里带开战锁时长）→ 钉版本提交开战锁
  （5 人 101 s，至多 3 轮、耗尽 4029）→ `createTeamTickets`（ticket id 由 xm-team 每人生成一个 UUID；成员已有别的票回 4026[该成员]）→ 回 STARTING 视图、其余成员收 MATCH_STARTED →
  `runTeamGather`（长挂的异步 RPC，调用级超时 = 开战锁时长，不占工作线程）→ 结果到达后在有界执行器 `team-match-end`（`xm.team.match-end-threads` 4、`match-end-queue-capacity` 1024）上清锁，
  全员收 MATCH_ENDED 或 MATCH_FAILED。前三个方法在 `team-worker` 上阻塞，每跳超时 = min(3 s, 剩余请求预算)，同一个值经附件 `xm-budget-ms` 下传；应答枚举首值 `UNSPECIFIED`，与传输失败同样处理。
  跨进程才有的故障面（基线 match 与 team 同进程）：xm-match 调不通 / 过载 / 发号租约无效 → 4030；建票结果不明 → 先 `releaseTeamTickets` 再回 4030；`runTeamGather` 传输失败 → 推 MATCH_FAILED、
  计 `gather_unknown`，**不退票**（开局可能仍在跑，票据由它收尾或按 matched TTL 自愈）；xm-team 自己优雅停机时（停机标志 `TeamShutdown`）在途开局的结果不明不清锁、不推 MATCH_FAILED，锁自然过期。
  4025 只由 xm-match 的预检读战斗锁给出，xm-team 不另读（另读会改变「谁先被报出来」）。
- **场景跟随**（xm-scene `TeamFollowService`）：玩家进场 / 换场景之后，在逻辑线程外读一次成员关系（一段只读 Lua：索引 + 投影），
  回到逻辑线程处理——不是队长且队长在本节点另一个场景实例 → 同步 `switchScene` 到队长所在实例；队长自己进场 → 对本节点其他成员各检查一次
  （只跟随、不再扇出）。跨节点 / 跨 zone 不跟随（同基线）；自己有在途的跨节点换图（选目标中或冻结中）时不跟随（`switching`，§8.1；经 `SceneWorld.switchInFlight` 判，
  过期的选目标槽当场作废、不再挡）。不发、不收 scene 刷新事件（scene 不缓存 TeamId，进场时现读）。
  **战斗守卫**（批次 6.3，§4.23）：读成员关系的同时并行发一条战斗锁的 EXISTS，两个结果一起回到逻辑线程；自己有战斗冻结（`in_battle`）、锁存在或读锁失败
  （`battle_lock`，fail-closed）都不跟随。冻结解除后由 `onBattleFreezeCleared` 补一次检查（只跟随、不扇出）：取消备战 / reaper 判废删掉锁之后，以及销账放掉锁之后；
  删锁 / 销账的结局回来时发起它的实例已经换掉（同 epoch 重进）的，只要锁确实是这一次删掉 / 放掉的，就补给现任实例（它没有战斗冻结时）。
- **队伍视图的 `in_battle`**（批次 6.3，收掉 team-spec D10）：`TeamDisplay` 对一次视图构建的全体成员、申请人、被邀请人、邀请人批量读战斗锁（`BattleLockReader.existsAll`，
  每人一条 EXISTS 并发发出，与在线读共用请求预算）。锁存在即为 true——备战中、战斗中、已结算待销账都算；任何一条读失败、超时、被中断，整批按 false，只记一行 WARN，
  不让 RPC 失败（同基线「MGET 失败 → 全 false」）。锁是 Hash：对它 `MGET` 回 nil 而不报错，照搬基线的 MGET 写法会让 `in_battle` 静默恒为 false，所以读者一律经 `BattleLockReader`。

### 4.17 帮会核心（xm-guild）

mmorpg：`go/guild`。Java 版是独立进程 xm-guild（Dubbo group `guild`，端口 20886，管理端口 18110），gate 把 `guildpb.GuildService` 的
28 个消息号经按会话的后端队列转过来（`handle` 不重试）。规格与逐条出处见 `docs/porting/guild-spec.md`：

- **权威数据在 MySQL**（`xm_java` 库四张表：`guild` / `guild_player_state` / `guild_member` / `guild_application`，Java 自有
  `xm/guild/guild_tables.proto` 经 xm-pbmysql 建表，DDL 与 Go proto2mysql 逐字节相同），业务 SQL 手写；连接会话级 RC、
  `innodb_lock_wait_timeout=1`、`useAffectedRows=true`。所有写走同一个事务基座 `GuildTx`：原始 JDBC 事务、按固定锁序（G → S → M → A，
  同表内按无符号 id 升序）`FOR UPDATE`，授权一律对锁住的行判；1213 整体重跑 ≤ 3 次、1205 / 子预算（1500 ms，解散 2500 ms）用完 /
  COMMIT 结果不明都归一为写冲突 14021；建帮与申请的「全局插入守卫」靠 `guild_player_state` 行与哨兵行 0。
- **Redis 只做缓存与排行**：帮会快照与玩家→帮会映射是版本化 cache-aside（代次 = UUID + Redis TIME，回填遇代次不符即放弃，按键单飞），
  提交之后先失效（失败的键后台按 100 / 400 / 1600 ms 重试）再推送；授权从不看缓存。排行 ZSET（全服 + 各区 + 区索引）由 MySQL 的
  `score` 列权威重建（启动时全量重建、维护锁 30 s + 续期）。
- **前置顺序同基线**：会话 → 归属区（`player.zone_id`）→ 合服闸门（4.4 恒放行）→ 业务前置 → 事务；请求体里的 player_id / zone_id 一律忽略。
- **错误语义**：业务拒绝 in-band（帮会段 tip，parameters 是基线的英文原因串）；依赖故障 / 处理器异常回**信封 1003**（同基线：客户端随之隔离帮会模块）；
  过载（工作队列满、排队超预算）回 in-band 14021「帮会操作繁忙」（客户端遇到任何信封错误会停用帮会直到重登，暂时过载不能用信封）；
  上行 8（UpdateGuildScore）/ 220 回信封 1003；4.6 的 5 个活动号暂回 in-band 1006。
- **推送 220**（`GuildChangedS2C{kind, guild_id, actor, target}`）经 `PlayerPushes`，失效缓存之后发，失败不影响回包；
  申请入帮通知帮主有 60 s 冷却（`SET NX`）。guild_id 由全服雪花租约（`NodeTypes.GUILD`）发，失败回 in-band 14008。

### 4.18 帮会经济（捐献 / 升级 / 商店）与资产指令投递

mmorpg：`go/guild` 的 economy_* / asset_store + `go/shared/assetop`。规格与逐条出处见 `docs/porting/guild-economy-spec.md`：

- **资产指令账本在帮会库**（`xm_java` 追加三张表：`guild_player_op_seq` 每人每流的下一个 seq、`guild_asset_op` 指令行（发件箱 + 结局）、
  `guild_daily_counter` 每日 / 每周用量，表定义同样在 `guild_tables.proto` 经 xm-pbmysql）。捐献 / 兑换在一个事务里预留（扣次数、
  分配 seq、写 PENDING 行，周期键用 `GameDay` 且一个请求只取一次 now），提交后**同步投递**一次（预算 min(2500 ms, 剩余 − 1000 ms)，
  不足 300 ms 不投、交给循环）：定位玩家所在 scene → 签名 → 调 `SceneAssetOpService` → 只在 APPLIED / REJECTED 且 durable 时终结
  （帮会资金 / 帮贡 / 次数的对侧账在终结事务里一起记），RETRY / NOT_HERE 留给循环重投，UNKNOWN 粘性告警转人工。
  同步投递不占 guild-worker：Dubbo 异步调用 → 定时重查 → 在有界的 `guild-asset-settle` 执行器上落库 → 回 worker 装配应答。
- **重投循环** `AssetOpLoop`：每 2 s 认领到期行（租约令牌 + 10 s 租约），8 条 worker 并发投递，指数退避 ±20% 抖动、上限 60 s，
  传输失败不覆盖上次的真实结局（E12），反复失败且玩家离线时直读 MySQL 里已落盘的账本判定结局（E8）；超过 1 h 的行转毒行等人工
  （`AssetOpFixMain` CLI：list / resolve）。清理任务按保留期删终态行与旧计数。离帮 / 被踢 / 解散时把该玩家未决捐献的截止提前。
- **升级**不经资产通道（只动帮会资金与等级）；**内部查询** `GuildInternalService.listAppliedAssetOpsSince` 给回档分歧检查用（批次 7.2b 已接入：xm-data 的 `GuildDivergenceGate`，§4.5.1），
  错误用应答内结果码表达。推送：资金变化 9 / 升级 10 / 发放完成 13（只在循环终结时推，同步当场终结的以回包为准）。

### 4.19 场景实例与主世界频道（批次 5.1）

mmorpg：`go/scene_manager` 的 world_init / world_autoscale / world_rebalance / orphan_cleanup + C++ scene 的 CreateScene / DestroyScene /
BeginSceneDrain。规格与逐条出处见 `docs/porting/scene-channels-spec.md`：

- **频道计划是唯一权威**：每个 zone 一份，存 Redis（`xm:world:{z:<zone>}:ch` 哈希，值是 `xm.api.WorldChannel`：scene_id、地图、节点号、
  频道位、ACTIVE / DRAINING），配期望频道数、冷却与只增的版本号 `…:ver`。scene-manager 每个 zone 选一个领导者（`…:leader`，
  `SET NX PX 30 s`、专用线程每 10 s 续期、距上次成功续期不足 2/3 TTL 才算领导者），每 5 s 一拍：只读 Lua 取快照 + 读 scene 目录 →
  纯函数 `WorldChannelPlanner` 算出差量 → 一段「领导令牌 + 版本号 CAS」的 Lua 原子写入并推进版本号（新值 = max(旧值 + 1, 快照的 Redis TIME 毫秒)，Redis 丢了最后一次写入后重写也不会与丢失的那一版撞号；被围栏 / 冲突就放弃本拍）。
- **铺设**：缺省「每个在线节点每张主世界地图至少一个频道」（5.2 上线后仍是缺省：这样只带地图的 63 永远在本节点完成，跨节点只由显式 scene_id 触发，§8.1；
  按落点哈希的模式已实现、按配置启用）；
  期望频道数缺省 1、可按地图覆盖；自动扩缩容缺省关；节点离开目录 20 s 后它的频道转移 / 删除；排空超时回到 ACTIVE。
  scene_id 由领导者用 `NodeTypes.SCENE_MANAGER` 全服租约上的雪花发，租约无效时本拍不新建（批次 5.3 起发号租约是独立 bean `SceneIdAllocator`，
  每个副本都申领、与实例取号共用，`leader-eligible = false` 的副本也能发实例号；租约确认丢失时控制面让出领导锁）。
- **scene 节点拉计划**（不需要 scene-manager → scene 的调用）：`ChannelPlanFollower` 在 `scene-sched` 上每秒读版本号，变了才读整份，
  交逻辑线程 `applyChannelPlan`（建本节点该有的频道、标排空；同号重建保证节点重启后玩家位置里的 scene_id 仍有效），应用后立刻补发目录
  （`SceneEntry.draining`、`applied_plan_version`）。排空：逐个玩家改派到本节点同地图人数最少的 ACTIVE 频道，没有就改到默认主世界，
  再没有就留在原地；空了（且没有在途进场）才销毁。领导者看到节点已应用且目录里没了才删计划里的记录。
- **分配**（`SceneAssigner` / `ChannelSelector`）：只在目录里的 ACTIVE 频道里挑人数（目录人数 + 软预占）最少者；软预占是按频道的 ZSET
  （TTL 缺省 10 s，启动校验它不短于 login 的归属等待），进场成功或失败都释放；显式指定排空中的频道一律拒绝。
- **63（场景内换场景）**：只带当前地图时挑本节点该地图人数最少的 ACTIVE 频道（打平留在原地）；目标是排空中的频道回 3023；
  显式 scene_id 不在本节点时走跨节点换图（批次 5.2，§8.1）。组队跟随不把队员拉进排空中的频道；排空改派跳过冻结中的玩家
  （交出结局出来后实例要么已离开、要么解冻后再改派）。
- **只管主世界频道**（批次 5.3）：计划里只有 `kind = WORLD`；镜像 / 副本是节点自有的实例（§4.21），`ChannelKind` 的 MIRROR / DUNGEON
  只出现在目录与 `createInstance` 里，计划里出现一律拒绝。镜像与源同配置号，所以凡是「按地图挑频道」的地方——scene-manager 的 `ChannelSelector`
  （登录分配、按地图选跨节点目标、软预占）、节点的「计划外即排空」、`leastLoadedActive`（排空改派、进场重定向、只带地图的 63）——都只认主世界频道
  （唯一判法 `ChannelKinds.isWorldChannel`，UNSPECIFIED 按 WORLD 读，兼容旧节点）；身在实例里只带当前地图的 63 必去主世界频道、不留在实例。
  规划器每拍从目录推导镜像源（`DirectoryMirrorSources`：任一节点上有以它为源的镜像，含排空中的），缩容与再平衡跳过镜像源；`xm_scene_channels` 只计主世界频道。
  滚动升级须先升 scene-manager、再升 scene（旧 scene-manager 没有种类过滤，会把登录分进新节点上的镜像）。

### 4.20 聚宝斋只读面（xm-trade，批次 4.7）

mmorpg：`go/trade`（P1 浏览 / 详情 / 收藏 / 货架 + 内部播种）。Java 版是独立进程 xm-trade（Dubbo group `trade`，端口 20887，管理端口 18111），
gate 把 `ClientPlayerJubaozhai` 的 4 个消息号（196 浏览、197 详情、198 收藏、200 货架）经按会话的后端队列转过来（`handle` 不重试）。
规格与逐条出处见 `docs/porting/trade-spec.md`：

- **存储**：`xm_java` 库两张表 `trade_listing` / `trade_favorite`，Java 自有 `xm/trade/trade_tables.proto` 经 xm-pbmysql 建（DDL 与 Go proto2mysql
  逐字节相同）；业务 SQL 逐字照搬 `listing_repo.go`。连接会话级 RC、`innodb_lock_wait_timeout=2`、`useAffectedRows=true`；每次调用的上限
  `min(2000 ms, 剩余预算)`，读语句带 `MAX_EXECUTION_TIME`，取连接有界。收藏写入用 `INSERT … ON DUPLICATE KEY UPDATE`（`INSERT IGNORE` 在 MySQL 8.4
  会与并发删除死锁，测试复现过 1213），撞 1213 / 1205 / 9007 时整条重跑一次（共 2 次）。**4.7 不新增任何业务 Redis 键**（Redis 只用于发号租约与热关停）。
- **规则同基线**：展示阶段（公示 / 寄售 / 已结束）与买家可见性（卖家豁免）、市场范围 zone / global（zone 下客户端的 `zone_filter` 被忽略）、
  搜索规范化（标题 LIKE 转义、纯数字按编号查）、分页（页码 0 视为 1、页长 0 取缺省、超长钳到上限、超过末页钳到末页、OFFSET 封顶）、排序与并列次序、
  收藏软上限 100（计数包括已看不见的收藏）、tip 码 20000 段且不带 parameters。归属区读 `player.zone_id`（xm-common `PlayerHomeZones`，帮会 / 组队共用）。
- **准入与错误**（`TradeDispatcher`）：解析先于身份检查（同 grpc-go）；解析失败、会话没有玩家、上行 199 → 信封 1003；工作池（16 线程、队列 1024）满或排队
  超预算 → in-band 1003；存储 / 归属区故障 → in-band 1003；整请求预算 3500 ms（基线 Timeout − 500）。gate 调 trade 失败或超时回带请求号的信封 1003（§4.1）。
- **listing_id** 用全服雪花（`NodeTypes.TRADE`，作用域 0），租约在启动时申领、失败拒启。
- **dev 播种**：管理端口 `POST /admin/trade/seed-listing`（请求 / 应答是 protobuf 二进制 `SeedListingRequest / SeedListingResponse`，头 `X-Xm-Admin-Token`
  常数时间比对、`X-Xm-Operator` 必填、每次写审计行）；接口总注册，运行模式不是 dev / test 一律 403。gate 不收 199（经 gate 发来无回包）。
  robot `trade` 用它造四条不同阶段的商品做端到端。
- **写侧**（上架托管、下单、支付、交付、回退）不在本批：基线 P2 资产托管通道默认关闭且没有调用方，P3–P6 只有设计；随 mmorpg 定下 P3 契约后同批做（路线图 4.8）。

### 4.21 镜像与副本实例（批次 5.3）

mmorpg：scene 的 63 镜像分支（`player_scene_handler.cpp`、`player_scene.cpp` RequestEnterMirrorScene、`sm_reply.cpp` 应答驱动的自动进场）+ `go/scene_manager` 的
createscenelogic（建实例）/ instance_lifecycle（空闲回收、级联）。客户端能走到的实例链路只有 63 镜像分支一条，副本（Dungeon 表）在 scene 侧只是 79 里的字段。
规格与逐条出处见 `docs/porting/dungeon-mirror-spec.md`（有意差异 D1–D19 见 PARITY「副本 / 镜像场景与实例空闲回收」行）：

- **节点自有 + 目录登记**：实例（`SceneKind` MIRROR / DUNGEON）是承载节点内存里的临时场景，不进 5.1 的频道计划、也不建 scene-manager 侧的 Redis 登记表，
  随进程消亡（节点重启不重建）。节点目录的 `SceneEntry` 带 `kind = 5`、`source_scene_id = 6`（镜像的源，同节点上的主世界频道），**目录就是唯一的登记**；
  建立 / 进入回收 / 复活 / 级联 / 销毁时立即补发（`SceneDirectoryPublisher.requestPublishNow` 单飞：标脏 + 至多一个发布任务，不加锁、不碰 Redis，逻辑线程上可直接调）。
  `mirror_config_id` / `dungeon_config_id` / `creators` 不进目录。实例的存在与人数以承载节点内存为准。
- **实例取号**（`SceneDirectoryService.createInstance`，scene-manager `InstanceIdIssuer`）：无状态，不读写 Redis（发号租约除外）、不调 scene；参数错回 3005，
  发起节点不接新实例回 3000（5.5 的钩子，现在恒接），放置恒为发起节点（镜像恒与源同节点），号来自 `NodeTypes.SCENE_MANAGER` 全服租约（§9）；租约无效以异常完成 future。
  scene 侧由 `SceneManagerSwitchTargets` 复用 5.2 的同一个 Dubbo 引用（`retries=0`、3 s，本地兜底 `switch-resolve-timeout` 4 s），结果投递回逻辑线程。
- **63 镜像分支**（逻辑线程）：准入顺序 3014 → 三个号全 0 3005 → `mirror_config_id ≠ 0 且 scene_id = 0` 进镜像分支 → 3008 → 去向（§8.1）。同步校验依次是
  当前场景不是主世界频道（镜像的镜像、副本作源）→ `mirror_config_id` 不在 Mirror 表（按 uint32 查）→ 源在排空 → 节点停止接客 → 本节点实例数达 `max-per-node`
  → 本人创建的实例数达 `max-per-creator`，一律回 3005。受理回 `{0}`，玩家进 5.2 的 RESOLVING 槽（`PlayerSwitch.mirrorCreate`，永不冻结；期间 63 回 3014、组队跟随跳过）取号。
  结果回来先按引用核对（实例已离开 / 令牌已换就丢弃，号作废，任何地方都不留幽灵镜像）：调用失败 / 超时 → 23 {1003}；拒绝 → 23 {3023}；号落在别的节点（5.3 不会出现）
  → 23 {3023} + ERROR；复核不过（玩家已不在源、源在排空、停止接客、达上限）或本地拒建 → 23 {3023}；成功 → `createInstance` → `switchScene`（源场景旁人 51、
  本人 79 / 21、有旁人时 47，同图保留坐标）。没装配取号时应答 `{0}` 后 23 {1003}。
- **建实例的唯一入口** `SceneWorld.createInstance(InstanceSpec)`：拒绝号为 0、本地重号（号全服唯一，重号即发号器坏了）、镜像的源不在本地 / 不是主世界频道 / 在排空、
  副本地图与 `Dungeon.scene_id` 不符、达上限、停止接客。79 / 31 的 `scene_info` 只由 `InstanceSpec.toInfo()` 构造：镜像 `{源 conf, 新号, mirror_config_id, 0, {创建者: true}}`，
  副本 `{Dungeon.scene_id, 新号, 0, dungeon_config_id, {}}`。`creators` 不做准入（同基线），任何人可按号加入：同节点同步换，别的节点走 §8.1 的交出。
- **空闲回收**（`maintainScenes`，挂在每秒一次的逻辑任务上：级联兜底 → 空闲判定 → 排空推进与销毁）：实例没人、也没有指向它的在途进场时，从变空那一刻起计时；
  满超时（镜像 `mirror-idle-timeout` 30 s，0 回落 `idle-timeout`；副本 `idle-timeout` 300 s，0 = 不自动回收）进入**回收宽限**（`DrainCause.IDLE`，`reclaim-grace` 30 s，
  目录报 draining）：scene-manager 不再把新玩家导向它，本地新进入一律不放行（63 回 3023、组队跟随跳过）；宽限内在途进场（登录重连、5.2 显式加入）到达即**复活**
  （玩家状态初始化成功之后才复活，初始化失败不给空实例续命）；宽限满且仍空、没有在途进场才销毁。宽限兜住跨节点的在途进场（5.2 最坏约 23 s，登录路径更短），
  超过宽限才到的进场照现有路径失败。人数、在途进场、销毁同在一个逻辑线程，不需要基线那套「人数为 0 才删」的 Lua CAS。
- **级联与显式销毁**：主世界频道在 `destroyScene` 里被销毁时，以它为源、还没在级联 / 显式销毁中的镜像（含回收宽限中的）转 `CASCADE`，同一次推进里居民改派到
  本节点同图主世界频道（坐标保留、无 tip），空了销毁；每秒再兜底检查一次「源不在本地或不是主世界频道」。显式销毁 `destroyInstance` 只经 dev 管理口：不在本地 3000、
  主世界频道或 0 → 3005、已在级联 / 显式销毁中 → 0，否则转 `ADMIN` 排空后销毁（镜像居民去同图主世界频道，副本居民去默认主世界出生点）。
  `Scene` 的 `DrainCause{NONE, PLAN, IDLE, CASCADE, ADMIN}` 只决定何时销毁与能否复活，`draining()` 语义不变。
- **副本与 dev 管理口**：副本没有客户端入口（同基线），scene 侧不刷怪、不计时、不校验队伍人数（PVE 在 battle 节点，§4.22）。只能经 scene 管理端口（缺省 18104）上
  dev / test 专用的接口建 / 毁：`POST /admin/scene/instance/create`（`CreateDungeonInstanceRequest{dungeon_config_id}` → `{tip_id, scene_id, scene_config_id, scene_node_id}`）与
  `POST /admin/scene/instance/destroy`（`DestroyInstanceRequest{scene_id}` → `{tip_id}`），protobuf 二进制（`xm-api` 的 `scene_admin.proto`）。鉴权同 xm-trade 播种
  （`SceneAdminAuthFilter`：`XM_ADMIN_TOKEN` 常数时间比对、未配 503、错 401，`X-Xm-Operator` 必填、每次写审计行），运行模式不是 dev / test 一律 403（先于解析请求体）。
  建副本的 HTTP 线程最多等 `switch-resolve-timeout` + 2 s，超时就撤掉结果：逻辑线程据此不取号、不建，或建好交不回去就当场销毁——不留没人知道号的副本。
- **重连与存盘**：`SceneAssigner` 的原实例分支种类无关——断线重连回到还在、且不在回收宽限 / 排空中的实例，否则按原地图（镜像的地图是源地图，落同图主世界频道；
  副本落默认主世界）。实例里存盘的 conf 就是实例的地图、坐标是当时位置，不新增持久化字段；同节点换入实例不涉及归属，`owner_epoch` 围栏不受影响。
- **配置**（`xm.scene.instance.*`，绑定时校验、不满足拒启）：`mirror-idle-timeout` 30 s、`idle-timeout` 300 s、`reclaim-grace` 30 s（≥ 10 s，不短于软预占 TTL /
  login 归属等待）、`max-per-node` 200（1..10000）、`max-per-creator` 3（1..100）；前三项可用 `XM_SCENE_MIRROR_IDLE_TIMEOUT` / `XM_SCENE_INSTANCE_IDLE_TIMEOUT` /
  `XM_SCENE_INSTANCE_RECLAIM_GRACE` 覆盖。本机切片为 robot 提速缺省把镜像空置超时调到 5 s、回收宽限调到 10 s（副本不动）。
- **目录发布窗口**：镜像建好之后要等一次补发才进目录（本机实测 25 ms 以上），窗口里别的节点按号加入回 23 {3023}，可重试（robot `cross-node` 对这一结局重试）。

### 4.22 battle 节点（批次 6.2）

mmorpg：`cpp/nodes/battle`（单 muduo loop：`BattleRoomManager` 房间、`BattleClientEdge` 客户端直连面、gRPC `BattleNode` 控制面、准入闸、Agones；直连之外的出站全走 Kafka）。
Java 版是独立进程 xm-battle，规格与逐条出处见 `docs/porting/battle-node-spec.md`（有意差异 N1–N21、照搬的基线怪癖 B1–B9 见 PARITY「battle 节点」行）。
客户端可见的部分（帧、握手、闸门顺序、拒绝串、tip、时限、推送与线上顺序）与基线逐字节相同：

- **两条连接**：大厅连接（gate）承载登录、scene、match（含补签 179），只接收大厅公告 177 / 143 的回落，**不承载任何战斗上行**——gate 对 `BattleClientPlayer`
  的 12 个号一律推 23 {1003}、不计非法包、不断连（`MessageRoutes.SERVICE_BACKENDS` 永不加入它；批次 6.3 起由 `MessageRoute.directOnly` 在 GM 闸之后、
  热关停与待处理队列之前当场拒绝，回到基线的位置，§4.23）。battle 直连每局一条，参战与观战共用一个槽，承载握手、
  四条战斗 RPC（149 提交行动 / 140 拉状态 / 162 自动战斗 / 165 退出观战）与全部战斗帧（139 / 150 / 158 / 161 / 166）；没有活直连的战斗帧直接丢弃、不回落 gate。
- **线程**：一条 `battle-logic` 线程（单线程 `NioEventLoopGroup`）既是直连面唯一的 Netty I/O EventLoop，又独占全部房间、直连会话状态（是否已验证、限频器、
  非法包计数、握手定时器）与房间计时器——同基线单 loop，帧顺序与「应答之后才关」不需要跨线程排队。boss 线程 1 条只 accept；Dubbo 提供方线程只读准入闸（原子量）、
  占在途位后投递，future 在 `battle-rpc-reply` 上完成；管理 Tomcat（4 线程）上的 dev 接口做完 Redis 查询后走同一条投递路径。逻辑线程上不做阻塞 I/O，出站端口一律异步。
  批次 6.3 起另有一条 `battle-outbox` 线程独占两个发件箱（逻辑线程只把结算交给它），一条守护线程 `battle-scene-sweep` 清扫到 scene 的直连客户端缓存（§4.23、§5）。
  批次 6.4 起再加一条守护线程 `battle-result-out`：对局结果事件的序列化与 Kafka 发送只在它上面（见下「出站端口」）。
- **直连面**（`com.game.battle.edge`，端口 `xm.battle.client-port` 12000，对客户端开放）：`EdgeInboundGuard` → xm-net `ClientFrameDecoder`（只收 `BattleTokenVerifyRequest` /
  `ClientRequest`，逐帧分发，§3）→ 编码器 → `BattleEdgeHandler`（每连接一个 `DirectSession`：PENDING → VERIFIED → CLOSING）。
  - 握手前：连接数达 `max-connections`（缺省 4096，0 = 硬上限 65535，只许 dev / test）即关；10 s 内没握手、握手前发 `ClientRequest`、首帧是别的合法类型都直接关，不回包。
  - 握手：已验证的连接再握手回旧 battle_id（不看新票）→ 验签 → 解析 → 字段判定（身份 → 节点 → 实例 → 过期 → 角色，最便宜的先拒）→ 按票上角色查名单；
    拒绝回逐字照抄的拒绝串、FIN、0.1 s 后强关；成功先回应答，观众再推 161。重连顶替旧连接：旧的立即关、不发帧，它迟到的断开不摘新连接。
  - 已验证后：体积 1024 B（1010）→ 限频（MessageLimiter 表，1008）→ 白名单（1005）→ 体解析（1005），各计一次非法包，阈值 50（0 = 只计不断）；写缓冲越过 2 MiB 即关。
    处理器只返回应答，由直连面在处理器返回**之后**写出，所以处理中引发的推送（139 / 150 / 158 / 166）一定先于这次请求的应答。
  - 关闭：直连面自己发起的（握手被拒、非法帧 / 非法包、写缓冲满）当场进关闭中；房间发起的优雅关闭（终局、销毁 / 作废、165、清退观众）当场脱离房间，
    本次读处理完或排在当前任务之后才进关闭中（同一次读里后面的请求照常回包）。之后先空写、写完再 `shutdownOutput` 发 FIN（直接 `shutdownOutput` 会丢缓冲里的终局包），
    对端不读时 1 s 后强关兜底。
- **票据**（xm-common `BattleTickets` + `BattleTicketIssuer`）：`BattleTicketPayload{battle_id, player_id, battle_node_id = 租约节点号, battle_instance_id = 进程 UUID,
  expire_at_ms = 房间期限, role}` 只序列化一次，同一份字节既拿去签名（HMAC-SHA256 的 64 字节小写 hex，密钥 `XM_BATTLE_TOKEN_SECRET`）又放进下发的 `token_payload`；
  验签用原字节、常数时间、大小写敏感，补签出的票与开局票逐字节相同。密钥任何运行模式都必填，去首尾空白后不足 32 字节或与 `XM_GATE_TOKEN_SECRET`（进程看得见时）相同：
  prod 拒启、dev / test 只 WARN（`BattleSecretPolicy`）。177 里的直连地址取 `xm.battle.client-advertise-host`（空 = `xm.advertise-host`）与 `advertise-port`（0 = 直连端口）。
  节点重启换 UUID，同号的新进程不接受旧票。
- **房间**（`com.game.battle.room`，只在逻辑线程）：`BattleRoomService` 是全部入口（建房、销毁、作废全部、补签、加减观众、四条上行、挂接 / 摘除直连），`RoomTable`
  是唯一增删口（hooks 维护房间数）。建房按基线顺序判定、拒绝即零副作用（不插表、不装计时器、不推送、不发确认），幂等命中不比较内容、不重推；插表后依次装整场期限、
  装第一回合（6000 ms，装填那一刻全员就绪则 2000 ms），再按 player_id 无符号升序逐人「177 + 143 → 确认」，最后每 10 s 补发确认、共 17 次。计时器回调按 id 重查后
  再比房间对象身份（防 Destroy 后同 id 重建时打到新房间）；`resolveRound` 可能当场删房，应答在调用之前填好。整场期限强制 DRAW、没有终局 139。视角裁剪靠类型强制：
  推送与应答只接受 `ViewerState`，观众版回合帧每回合只序列化一次。观战每房 20 人、参战观战单槽互斥。
- **推送出口**（`com.game.battle.push`）：`PushPolicy` 同基线——战斗帧只走活直连、否则丢弃（按消息号采样日志）；大厅公告有活直连就直写，否则交 `PresenceLobbyAnnouncer`
  → `PlayerPushes.pushAllToPlayer`（按在线目录的**当前**会话、一条 `MessageBatch` 保 177 → 143 的顺序、经 gate 玩家栅栏，§4.3）。至多一次，丢了靠 6.3 的 144 → 客户端 179 补签兜底。
- **控制面**：`com.game.api.BattleNodeService`（createBattle / destroyBattle / issueBattleTicket / addObserver / removeObserver），`BattleRpcServer` 用编程式 `IsolatedDubboModule` 导出
  （group `battle-node`，`register = false`，端口 `xm.battle.rpc-port` 21200；调用方按目录的 rpc 地址直连、`retries = 0`），参数与返回值直接用契约生成类。
  createBattle 回 `xm.api.CreateBattleResult{admission, reason, response}`：`ADMITTED` 时 `response` 是契约 `CreateBattleResponse` 的字节；`NOT_ALLOCATABLE`（not_started /
  closed / overloaded 在 Dubbo 线程判，closed_in_loop 在逻辑线程复核）保证没建房、没推送、没发确认，调用方不发 destroy、换节点重试一次；`UNSPECIFIED` 读方按传输失败处理。
  在途上限 `rpc-max-inflight`（256）。其余四个方法的业务错误都在应答体里。调用方 MAC（`XM_DUBBO_SECRET`，必填）与热关停过滤器照常生效（§4.1、§4.15）。
- **出站端口**（`com.game.battle.port`，逻辑线程只调接口，实现必须异步）：`SceneBattleEvents.confirm`（建房首发 + 补发）、`SettlementSink`（收尾时逐人排在本人 150 之后）、
  `ActivityResultSink` / `BattleResultSink`（只在真打完的局发）。6.2 的缺省实现只记日志与计数；**批次 6.3 起**前三个由 `SceneTransport` 给出真实实现
  （确认经 Dubbo 发往 scene、结算进发件箱、活动结果进持久通道，§4.23）；**批次 6.4 起** `BattleResultSink` 的缺省实现是 Kafka 生产方 `KafkaBattleResultSink`（下一条），
  日志实现只剩测试在用；测试提供同类型的 bean 时缺省实现让位（`@ConditionalOnMissingBean`）。
  发布端口带通道：`BattleResultSink.publish(event, Channel{PLAIN, ACTIVITY})`，普通局由逻辑线程发 PLAIN，活动局由 `battle-outbox` 首发并重发 ACTIVITY。
  dev 房间（`RoomOrigin.DEV`）永不投递结算与结果事件；`DEV_GATHER` 房间（快照来自 scene 的真实备战）照常确认、照常结算，只是不发结果事件；只有控制面建的房间（`RoomOrigin.MATCH`）真正打完才发结果事件。
- **对局结果的 Kafka 生产**（批次 6.4，`com.game.battle.port.kafka`；match-spec §5.4）：topic `xm-battle-result-g<代次>`（规格件 xm-audit `BattleResultTopics`：3 分区、保留 7 天），
  key = battle_id 的无符号十进制，value = 契约 `BattleResultEvent` 的字节；消费方是 xm-match 的评分（§4.24），4.6 起 xm-guild 用自己的消费组读活动局。模式同 scene 的审计管线（§4.5）：
  `publish` 任意线程可调（实际是 `battle-logic` 与 `battle-outbox` 两条），只把不可变的事件投进有界队列（1024），不阻塞、不抛；序列化与 `producer.send`（会因元数据或缓冲阻塞到 `max.block.ms`，
  所以不能在逻辑线程上调）只在 `battle-result-out` 上，单线程保证同一条发布线程交来的事件按次序发出；生产者幂等、`acks = all`。**核对通过之前不发**：建 bean 时在启动线程上同步核对一次 topic
  （battle 是生产方：缺就按规格建、存在只核对分区数；保留期由主人 xm-match 校正），分区数不符拒绝启动，Kafka 不可达至多等 `init-timeout` 后照常启动；之后没有定时器，
  下一条事件到来且距上次核对满 30 s 才在发送线程上再核对一次。**不丢**：没被 Kafka 确认的每条事件（队列满、未核对、发送失败、投递失败、停机没发完）都把完整字节写进兜底日志
  `xm.battle.result.fallback`（可回灌；回灌工具还没有），每次 `publish` 恰好计一次 `xm_battle_result_events_total{result}`。活动局在 4.6 的消费方销账之前一局最多 31 条相同消息，消费方必须按 battle_id 幂等。
- **节点身份、目录与准入**：节点号租约 `NodeTypes.BATTLE`、作用域 0（基线 battle 是全局池，不分 zone），实例 id 是每次启动生成的 UUID；目录与租约丢失见 §6。
  准入闸 `NOT_STARTED → OPEN → CLOSED`（`AdmissionGate`，CLOSED 是终态）。
  - **启动**：门禁（票据密钥策略、`XM_DUBBO_SECRET`、prod 下 `max-connections = 0`、指纹模式与握手期限取值）→ 加载七张战斗表与 `TableBattleData`、解析 4 个上行号与
    7 个下行号（缺号拒启）→ 占租约 → 起线程 → 导出 Dubbo → 绑直连端口 → 在逻辑线程开闸 → 首次发布目录 → 打「节点已就绪」。先开闸后进目录，基线「已发布、未开闸」的窗口不存在。
    actuator 的 health 比就绪早约 2.5 s 报 UP，本机切片等 21200 / 12000 端口、`xm_battle_admission_phase = 1` 与就绪日志。
  - **停机**（只由 Spring 驱动：`BattleApplication` 设 `dubbo.shutdownHook.listenIgnore=true`，否则 Dubbo 自己的 JVM 钩子会在 SIGTERM 时抢先销毁控制面）：
    摘目录 → 在逻辑线程的**同一个任务**里关闸并作废全部房间（观众收 166 ABORTED，参战者不收帧）→ 停监听、有界等待优雅关闭的直连排空（`shutdown-flush-timeout` 1 s，
    修基线观众的 166 被强关吞掉）、强关剩余连接 → 撤 Dubbo 导出（期间进来的 createBattle 回 NOT_ALLOCATABLE）→ 停线程 → 交还租约。
    节点停完之后 Spring 才销毁 `SceneTransport`（批次 6.3）：结算发件箱有界等待在途的落库与首投（`outbox-drain-timeout`，缺省 3 s）→ 停 `battle-outbox` → 停清扫线程 →
    销毁到 scene 的直连客户端；内存里的重投名单丢弃，记录留在 Redis，由 scene 的 rescue / 进场恢复兜底。
    批次 6.4 起最后关 `KafkaBattleResultSink`：等发送队列发完、在途确认回来，至多 3 s，超出的事件写兜底日志（`SceneTransport` 的活动结果通道经它发布、依赖它，所以 `SceneTransport` 先销毁）。
- **dev / test 管理口**（管理端口 18112，`POST /admin/battle/dev/{create,destroy,issue-ticket,add-observer,remove-observer}`，请求 / 应答是契约 protobuf 二进制）：6.4 之前用它端到端验收；
  6.4 之后真排队由 xm-match 建房（robot `battle-smoke`，§4.24），这组接口保留给不依赖匹配的房间与直连面用例（robot `battle` / `battle-edge`）。
  鉴权同 xm-trade 播种（`BattleAdminAuthFilter`）；运行模式不是 dev / test 回 403（先于解析请求体），请求体坏 400，快照路由补不全 422（gate 字段取在线目录，scene 字段取
  位置记录（只认 `o`）+ scene 目录的实例 id），节点没在运行 / Redis 读失败 503，等结果超过 5 s 回 504。经它建的房间标 `DEV`。
  批次 6.3 加 `POST /admin/battle/dev/gather`（按 player_id 升序逐人经 `SceneBattleService` 真实备战、取 scene 出的快照；`CREATE` 模式再进程内建房，标 `DEV_GATHER`；
  任一人失败就对已备战和结局不明的人补发取消、回 422）与 `dev/cancel-prepare`，6.4 之前用它验收 scene 侧的冻结与结算（§4.23）。
  6.4 之后它仍保留：robot `battle-settle` 与两个 kill -9 故障变体要「只备战不建房」和可控的建房时机，继续经它开局；这类房间不发结果事件，不会进评分。
- **存储**：不用 MySQL，房间是纯内存的（节点崩溃 = 在打的战斗全部作废，由 scene 的 reaper 按期限解冻，已落库的结算由 scene 的 rescue / 进场恢复应用，§4.23）。
  Redis 写：租约与目录；批次 6.3 起另写待结算记录、「已被取代」时的已销账墓碑与活动结果的持久副本。Redis 读：在线目录（大厅回落）、位置记录与 scene 目录
  （结算 / 确认的定位、直连客户端缓存的清扫、dev 接口）、战斗锁（只在「已被取代」的判定脚本里读）。
  批次 6.4 起是 Kafka 的生产方（对局结果 topic，见上）；Kafka 不可达不影响建房与战斗，只是结果事件进兜底日志、评分不更新。
- **配置**（`xm.battle.*`）：`client-port` 12000、`client-bind-host` 0.0.0.0、`advertise-port` 0、`client-advertise-host` 空、`rpc-port` 21200、`max-connections` 4096、
  `handshake-timeout` 10 s（客户端可见、不建议改，范围 (0, 60 s]）、`illegal-packet-threshold` 50、`table-fingerprint-mode` warn（off / warn / enforce，写错拒启）、
  `rpc-max-inflight` 256、`shutdown-flush-timeout` 1 s；批次 6.3 加 `scene-rpc-timeout` 5 s（确认 / 结算调用超时，必须大于 Redis 单条命令最坏耗时 4.2 s，否则拒启）与
  `outbox-drain-timeout` 3 s（停机时等在途落库与首投，0 = 不等），绑定在单独的 `SceneTransportProperties`；Redis 库 12。
  批次 6.4 加 `xm.battle.result.{bootstrap-servers, topic-generation, replication-factor, init-timeout}`（`BattleResultProperties`；缺省 `${XM_KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:9092}` /
  `${XM_BATTLE_RESULT_TOPIC_GENERATION:1}` / 1 / 10 s，非法值在绑定阶段拒启；代次必须与 xm-match 的 `xm.match.kafka.topic-generation` 一致，副本数只在创建 topic 时用，两边要配成相同）。

### 4.23 回合制战斗的 scene 侧：冻结、在途闸、结算与发件箱（批次 6.3）

mmorpg：scene 的 `battle/system/player_battle.{h,cpp}`（PrepareBattle / CancelBattlePrepare / ConfirmBattle / ApplySettlement、登录钩子、reaper）与结算账本，battle 的
`settlement_outbox.h`、`battle_room_manager.cpp` 的落库与重投、活动结果通道；battle ↔ scene 走 Kafka scene-cmd 与 gRPC `SceneNodeGrpc`。Java 版规格与逐条出处见
`docs/porting/scene-battle-spec.md`（有意差异 D1–D36、照搬的基线怪癖 B1–B17 见 PARITY「scene 侧战斗冻结与在途闸」「回合制战斗结算链路」「战斗活动结果持久通道」三行）。
客户端可见的部分——144 / 150 / 184 / 23 的消息与推送时机、各在途闸的码与判定次序、快照经 battle 进入 `BattleActorState` 的内容、结算落地后的面板数值——与基线相同。

- **根本前提与传输**：Java 断线即写回、释放、移除实例，重连从库新建实例（§7），内存里的冻结活不过任何一次断线。所以**战斗锁是承重的**，不只是咨询性的：
  备战时先把锁写成功再回 match，写失败就拒绝；锁独占，被别的局占着就拒绝。传输是 scene 在资产 RPC 端口上再导出的 `SceneBattleService`（§4.12；`prepareBattle` /
  `cancelBattlePrepare` / `confirmBattle` / `applySettlement`，信封 `SceneBattleCall{target_instance_id, player_id, body = 契约消息字节}`，应答状态 HANDLED / NOT_HERE / DEFERRED / OVERLOADED，
  结算另带 APPLIED / ALREADY_APPLIED / DISCARDED）。提供方 `SceneBattleProvider` 在 Dubbo 线程上占在途名额、核对实例、解析后投递逻辑线程，应答在 `scene-asset-reply` 上完成；
  要等异步 Redis 的分支最坏约 4.2 s（一条脚本的最坏耗时，§6），所以 battle 侧的调用超时缺省 5 s 并在启动时校验（xm-match 的备战 / 取消调用按裁决保持 3 s，超时按结局不明补发取消，§4.24）。
- **状态模型**（`com.game.scene.battle`，只在逻辑线程读写，不持久化）：`PlayerBattle` 挂在 `ScenePlayer` 上——冻结 `BattleFreeze`（battle_id、battle 节点号、阶段 PREPARING / FIGHTING、
  两个期限、`preparedHere`「这一局的 144 不必再推」、写锁在途、延后的取消、续锁是否已确认、rescue 在途）、进场恢复状态（PENDING / READY / RETRY）、恢复代际、本实例已销账集合。
  唯一谓词 `ScenePlayer.inBattle()` = 有冻结；`frozen()` 仍只表示 5.2 的交出冻结。改冻结的唯一入口是 `PlayerBattleService.clearFreeze(player, LockAction)`
  （只删备战锁 / 不看阶段地删（reaper 判废专用）/ 保留锁）。所有异步回调先核对「实例仍是这个、冻结对象没换」再动状态。
- **备战 / 取消 / 确认**：备战按基线次序判定（参数为 0 或期限超出 7 天 1005 → 不在本节点 1004 → 换图在途 / 已有冻结 / 恢复未就绪或账本有未落盘条目 / 气血 0 都是 1006），
  拒绝即零痕迹；通过后组快照（`BattleSnapshots`，含出战宝宝与战斗配表指纹）、挂 PREPARING 冻结并停步（旁观者收到速度为 0 的 66）、写锁，**锁写成功才回快照**；
  被占回 1006，Redis 出错 / 超时回 1003 并尽力只删备战锁。取消：离线一段 Lua；在线 PREPARING 当场解冻、只删备战锁；已 FIGHTING 的拒绝（同基线）；写锁在途时延后到写锁完成。
  删锁与写锁都是异步的：这把备战锁从写上到删掉之间可能被一次进场恢复读到（同 epoch 重进最常见），所以本节点发出的「只删备战锁」有了结局时，现任实例上按这把锁重建出来的
  PREPARING 冻结一并摘掉（`dropPreparingRebuiltFromLock`），不留一把没有锁的冻结到备战期限。
  确认：PREPARING → FIGHTING 并把锁续到正式期限；在线而没有冻结的「迟到确认」用一段 Lua 核对锁并标 F，据锁重建 FIGHTING。144 只在需要时推（重连 / 顶号 / 按锁重建），
  全部经 `pushReconnectOnce`，同一个冻结对象上恰好一次，PREPARING 永不推。
- **在途闸**：客户端入口按方法声明的 `BattlePolicy`（§4.4）裁决，码与次序照基线：63 → 3023（先于换图在途的 3014）；84 → 施法者 7004 / 目标 7002；属性六个写方法 25011；
  宝宝六个写方法 26008；192 → 1005；134 / 132 静默丢、131 只把速度清零；资产通道 RETRY 27002；没声明的新方法缺省 1005。只读查询、自动加点建议、GM 货币、任务照常（基线不闸，Java 也不加）。
  闸**不下沉**到 `CurrencyService` / `BagService` / `MissionService`：结算应用时冻结还没摘，下沉会把结算自己拦住。`InBattleGateMatrixTest` 遍历注册表钉住这张表，新方法不进表即失败。
- **与交出冻结、组队跟随的互斥**（两种冻结始终互斥）：备战拒绝换图在途的玩家（选目标中 / 冻结中；过了期限的选目标槽当场作废）；`SceneWorld.beginRemoteSwitch` 拒绝在途玩家，
  选目标结果回来时再复查（回 NONE、推 23 {3023}）；交出冻结期间到达的结算回 DEFERRED、零副作用，确认只标锁不挂冻结，销账结果不改账本；交出没提交、原地解冻之后**重跑完整的进场恢复**。
  排空 / 级联改派与帧外推都跳过在途玩家。组队跟随读锁与补跟随见 §4.16。
- **Redis 键**（全部经 `RedisKeys`；前三个共用 hash tag `{<pid>}`，销账、取代、落库三段 Lua 跨键同槽）：

  | 键 | 形态 | 写者 | TTL |
  |---|---|---|---|
  | `xm:battle:{<pid>}:lock` | Hash：`b` battle_id、`n` battle 节点号、`s` 阶段 `P` / `F`、`d` 战斗期限、`p` 备战期限（锁与上下文合一，同生共死） | 只有 scene | 期限剩余 + 60 s；结算应用后至少 180 s |
  | `xm:battle:{<pid>}:settlement` | Hash：每局一个字段（字段名 = battle_id 的无符号十进制），值 = 契约 `BattleSettlementEvent` 字节 | battle 落库；scene 只删字段 | 7 天，每次写刷新 |
  | `xm:battle:{<pid>}:settled:<battle_id>` | String：这一局「已销账」的墓碑（Java 独有） | 销账的两段 Lua | 600 s |
  | `xm:battle:activity-result:<battle_id>` | String：契约 `BattleResultEvent` 字节 | battle | 7 天（消费方 4.6 消费后删） |

- **脚本**（xm-discovery `BattleRedis`：16 段 Lua、返回码与跨进程常量的唯一出处，scene 与 battle 共用，`ByteArrayCodec`）：scene 用 `PREPARE_LOCK`、`CONFIRM`、`CANCEL_OFFLINE`
  （兼「只删备战锁」）、`DELETE_IF_MATCH`（只给 reaper 判废）、`TOUCH`（重建复核）、`HOLD`（只延不缩）、`ACK`（销账与放锁同一段，并写墓碑）、`ENTER_READ`（锁与待结算记录同一时刻的快照）、
  `READ_IF_OURS`、`READ_LOCK_BATTLE`、`DELETE_SETTLEMENT_FIELD`；battle 用 `STORE_SETTLEMENT`、`PROBE_SETTLEMENT`、`ACK_IF_SUPERSEDED`、`STORE_ACTIVITY_RESULT`、`PROBE_ACTIVITY_RESULT`。
  纪律：每一次删锁 / 续锁 / 删记录都按 battle_id 条件执行；脚本只碰经 `KEYS` 传入的键。Redisson 配的是 `retryAttempts = 1`，超时的 EVAL 会被原样重发，而且两条各自发出的脚本
  谁先执行、回调谁先回来都没有保证，所以每段脚本都按「可被重放、可被重排」设计：同局且仍是 `P` 的 `PREPARE_LOCK` 按成功；`TOUCH` 单调（锁已是 `F` 时不降回 `P`、不缩 TTL，回 2）；
  取消与备战失败只删备战锁（已标 `F` 的不删）；销账之后才落地的落库被墓碑挡下（回 -1），不会把记录重新造出来。回整数的脚本得到空回复按异常处理——没问到结论不当成任何结论。
  **全部脚本以 `READ_WRITE` 执行（读主库）**：读到「没有锁」「没有记录」会直接驱动丢弃与判废。`BattleLockReader.exists / existsAll` 是另一类：咨询性的读，走普通读路由，
  给队伍视图、组队跟随、xm-data 的回档闸与 xm-match（排队、切磋、凑单校验、成员预检，§4.24）用（部署约束见 §6）。
- **账本与 durable 判据**：`BattleLedger` 存 `player_state.battle_ledger`（字段 9，§7），只登记「已应用、销账未确认」的局，与货币 / 背包 / 宝宝 / 任务同一次带围栏的写。
  两条判据分开：「这一局应用过没有」看内存账本；「可以销账」看最近一次确认落库的快照里有没有这一条（`ScenePlayer.persistedState()`，与资产通道同一条判据，§4.12）。
  销账的唯一入口 `PlayerBattleService.writeOff`：账本没有（判废或重复投递）→ 立即 `ACK`；有且已落盘 → `ACK`；有但没落盘 → `requestSave`，落盘回调再判。
  `ACK` 成功回来才从账本摘掉。加载校验失败（0 号、重复、超过 64 条）原样带回、fail-closed：该玩家的结算一律延后、备战回 1006，绝不当空账本用。
- **结算到达与应用**：实例不符或玩家不在 → NOT_HERE（battle 下一轮重新定位）；进场恢复未完成或在交出冻结中 → DEFERRED；冻结是别的局 → 丢弃并销账；没有冻结 → 读锁，
  锁是本局才应用，否则丢弃并销账。应用（`BattleSettlementService`，逻辑线程一个任务内同步完成，顺序同基线）：账本命中即返回 → **金币先行**（失败整笔延后，此前零副作用）→
  气血法力回写与复活（§4.10）→ 宝宝（§4.11）→ 消耗（按持有夹紧、格子号升序）→ 掉落（主包放不下进临时格，仍放不下丢失并 ERROR，不让整笔失败）→ 击杀事实（§4.8）→ 登记账本
  （金币一旦入账就一定登记）→ `requestSave`。四条应用路径（在线、按锁、进场、rescue）只有一个收口 `applyAndFinish`：把锁至少保持 180 s → 摘冻结、保留锁 → `writeOff` → 本次新应用才推 150。
- **进场恢复闸与代际**（登录 / 重连 / 顶号 / 交出进场都跑，挂在 `SceneWorld.onPlayerLoaded` 末尾）：新实例的恢复状态初值 PENDING，开新一代、发 `ENTER_READ`；
  待结算记录按 battle_id 无符号升序逐条应用（遇到延后的就停，坏字段只删该字段）；锁指向一场没有结算记录的在途局就按锁重建冻结并 `TOUCH` 复核（重建出 FIGHTING 的推 144，
  一定在 79 / 21 / 47 之后）；账本里其余条目进场即销账；然后 READY。READY 之前结算回 DEFERRED、备战回 1006（在途闸不受影响，只看冻结）。读失败或有延后的记录 → RETRY，由 reaper 重跑。
  过期读的两道防线：恢复读带**代际号**，回调只认最新一代；发出 `ACK` 的局记进**本实例已销账集合**（容量 64、先进先出），之后任何一份更早的读把它带回来都不再应用、不按锁重建——
  否则「快照早于销账、回调晚于账本摘除」会把已销账的局再发一次奖。同 epoch 沿用旧实例时冻结复制成新对象一并沿用（写锁在途的不沿用），旧实例的已销账集合也跟过来；
  `preparedHere` 在会话没变时照抄、会话变了才清掉，沿用时推不推 144 只看副本的这一位与阶段：已推过或本会话备战的不再推（同会话重复进场不多推一条，同基线），
  会话变了或还欠着一条的 FIGHTING 当场推，PREPARING 一律等确认升级。
- **reaper**（逻辑线程上的定时任务，缺省每 30 s，`xm.scene.battle.reaper-interval` 只许调小）：PREPARING 过了备战期限只摘冻结、锁留到 TTL；FIGHTING 过了期限再加 10 s 宽限先 rescue——
  读本局的待结算记录，有就应用（battle 落库后崩溃、超时平局与期限赛跑都能到账），没有才判废删锁；读出错不判废，过了期限 + 60 s 才放弃。账本排空只对 READY 的玩家做，RETRY 的只重跑恢复。
- **battle 侧发件箱与重投**（xm-battle `com.game.battle.outbox.SettlementOutbox`，独占 `battle-outbox` 线程）：**先落库、后投递**；落库失败或没有结论只投一次、不登记（`not_durable`，告警）；
  落库回 -1（墓碑在）按已销账处理。首投与重投都按位置记录 + scene 目录定位（`SceneAssetLocator`，只认 `o`），带实例 id 围栏。每 10 s 一轮：探测记录还在不在（不在 = scene 已销账，摘除）→
  还在就重投，12 次重投、第 13 轮用尽；本轮没有目标（玩家离线）时另判「已被取代」（`ACK_IF_SUPERSEDED`）；探测 / 定位出错不计次，登记超过 10 min 仍没结论按用尽摘除。
  投递应答只计数、不改名单：scene 应用之后要等落盘才能销账，「已应用」不等于「可以停止重投」。停机有界排空（§4.22）；节点崩溃名单丢失，由 scene 的 rescue（在线）与进场恢复（离线）兜底。
  到 scene 的直连客户端（`NodeRpcClients<SceneBattleService>`）按节点缓存，`SceneClientSweeper` 每 60 s 对照 scene 目录销毁已下线 / 换实例的节点的客户端。
- **确认的传输**（`DubboSceneBattleEvents`）：按快照路由查 scene 目录，实例相符就发往它；不符（备战节点已重启 / 下线）回落到定位器、发往玩家现在的实例，定位不到就不发。补发节奏仍是 6.2 的 17 次。
- **活动结果通道**（`ActivityResultOutbox`，同一条 `battle-outbox` 线程）：活动局的结果先 SET 持久副本、再经 `BattleResultSink` 以 ACTIVITY 通道发布，之后每 10 s 探测副本还在不在，
  没被消费就重发、30 次用尽。销账方是 4.6 的帮会活动消费方与巡检器，两版现在都不存在。批次 6.4 起发布走真实的 Kafka 传输（§4.22），xm-match 也提供了活动开战的 match 侧与 dev / test 管理口（§4.24）：
  dev / test 环境里能开出活动局、事件真的进 topic，只是没有人销账，重发用尽后摘除（记一条 ERROR）、副本留到 TTL；生产上调用方（xm-guild）还没有，整条通道仍不可达。
  4.6 的消费方必须按 battle_id 幂等，并把不认识的 (guild, activity) 当终态销账。
- **gate 拒绝战斗上行**（xm-gate）：`MessageRoute.directOnly`（按服务裸名 `BattleClientPlayer` 置位，12 个号）在 `ClientDispatcher` 里排在 GM 闸之后、热关停之前：当场推 23 {1003}，
  不进待处理队列与后端队列、不计非法包、不断连，计 `result=battle_rejected`。闸序与基线逐段相同；被拒的战斗号照样占限频额度。
- **配置**：`xm.scene.battle-rpc-max-inflight` 256、`xm.scene.battle.reaper-interval` 30 s（0 < 值 ≤ 30 s，本机切片调到 2 s）；`xm.battle.scene-rpc-timeout` 5 s、`xm.battle.outbox-drain-timeout` 3 s（§4.22）。
  跨进程数值（锁余量 60 s、应用后保持 180 s、重投 10 s × 12、宽限 10 s、墓碑 600 s、发件箱条目上限 10 min、账本容量 64）都是 `BattleRedis` 的代码常量，不开放配置，约束关系由单测钉住。
- **已知残余**（规格 §10.5）：账本满 64 被淘汰的局若再被投递会重复发奖（只在 Redis 长期不可用时）；金币入账失败（封禁 / 溢出）让整笔延后，在线时多半最后被判废（同基线）；
  Redis 丢数据会丢在途结算与锁（部署要求开 AOF）；咨询性读的主从约束见 §6。冻结侧另有几处有界的窄窗口（备战写锁晚到会把锁留到备战 TTL；确认事件的期限没有上界；
  同 epoch 沿用一个复核还在途的重建冻结时新实例不再复核），都由期限与 reaper 兜底。

### 4.24 匹配（xm-match，批次 6.4）

mmorpg：`go/match`（排队 / 凑单 / gather / 补签 / 评分 / 切磋 / 帮会活动开战，与组队同进程；票据与队列在 Redis、评分在 Redis 两层幂等 Lua、结果事件走 Kafka `match-results`、推送走 Kafka gate-cmd）。
Java 版是独立进程 xm-match，规格与逐条出处见 `docs/porting/match-spec.md`（有意差异 M1–M33、照搬的基线怪癖见 PARITY「匹配」「切磋」「战斗票据补签 179」「帮会活动开战」四行，
整队开战的 xm-team 一侧见 §4.16 与 PARITY「组队」行）。客户端可见的部分——10 个消息号的应答形状、判定顺序、tip 码与 `parameters[0]` 的中文串、推送与顺序、可见时限、分队与站位顺序——与基线逐字节相同；
服务端内部（进程划分、传输、键空间、脚本形态、评分存储、线程模型）按 Java 方式重做。
观战的 match 侧（163 / 164、观战标记、可观战索引、开局前清退观众）与跨区 1V1 随批次 6.5 补上，单列在本节末的 §4.24.1；下面各条里标「6.5 起」的是它对 6.4 的改动。

- **进程与入口**：Spring Boot servlet 应用（Servlet 容器只挂 actuator 与 dev 管理口）+ Dubbo Triple 提供方，Dubbo 端口 20888（`XM_MATCH_RPC_PORT`）、管理端口 18113（`SERVER_PORT`）。
  group `match` 上导出三个接口：`ClientMessageService`（gate 把 `MatchService` 的 10 个号 148 / 151 / 152 / 153 / 154 / 156 / 157 / 163 / 164 / 179 整体转来，`xm.dubbo.match-url`，`handle` 不重试）、
  `MatchTeamService`（xm-team 的整队开战端口，§4.16）、`MatchInternalService`（帮会活动开战，4.6 的 xm-guild 调）。它自己是 xm-scene `SceneBattleService`（备战 / 取消）与 xm-battle
  `BattleNodeService`（建房 / 销毁 / 补签）的调用方，都按 Redis 节点目录直连、`retries = 0`，用到时才建引用——启动不依赖 xm-scene / xm-battle 在场。
- **派发与应答规则**（`dispatch.MatchDispatcher`，每个号一个 `MatchMethodHandler`，十个方法缺任何一个处理器拒启）：身份只取 `SessionContext.player_id`，请求体里的 player_id 一律忽略；
  Dubbo 线程解析后投递到有界工作池 `match-worker`，截止 = 受理时刻 + `request-budget`（4500 ms，含排队）；156 / 154 不涉及 I/O，当场回（6.4 期间 163 / 164 也是当场回的临时应答——in-band 1006 / 空列表；6.5 起换成真处理器，不再当场回）。
  处理器可以经 `MatchMethodHandler.executor()` 自带执行器（缺省 null = 共用 `match-worker`；6.5 的 163 用它跑在虚拟线程上，§4.24.1）：派发器每次派发读一次，执行器拒收与「轮到执行时预算已用完」都走该处理器的 `onOverload()`，与工作池满同一个口径。
  业务结果全部 in-band；148 的应答是 Empty，成功不回包；请求体解析失败、未知号、处理器抛未分类异常回信封 1003；工作池满（163 是在途已满）或排队超预算时 157 / 152 / 151 / 179 / 163 回 in-band 16004 `服务器繁忙,请稍后再试`，
  只能用信封的 148 / 153 / 164 回信封 1003。gate 调 xm-match 失败或超时同样是带请求号的信封 1003。
- **Redis 键**（全部经 `RedisKeys`，共用一个 hash tag `{match}`：票据与队列同槽，入队、取消、弹组、回队首、切磋的发起与消费都各用一段 Lua 原子完成；只有 xm-match 写这些键）：

  | 键 | 形态 | TTL |
  |---|---|---|
  | `xm:{match}:index` | SET：活跃队列的注册集（成员 = 队列键全文，代替 SCAN） | — |
  | `xm:{match}:queue:<mode>:<config>` | LIST：等待序，成员 = player_id；队尾入队、回队首用 LPUSH。`config` 不校验，每个值一条队列 | — |
  | `xm:{match}:rank:<mode>:<config>` | ZSET：队列的评分镜像（分数 = 入队时评分 × 100），成员集合恒等于队列的 | — |
  | `xm:{match}:lock:<mode>:<config>` | STRING：凑单锁（值 = 实例 id，按持有者释放）；只是效率手段，正确性靠弹组脚本 | 10 s |
  | `xm:{match}:ticket:<pid>` | HASH：`ticket` / `mode` / `config` / `state` / `enqueued_at_ms` / `zone_id` / `queue_key` / `rating_centi`，另有 `team_id` / `battle_id` / `not_before_ms`；每人至多一张 | queued 6 h、matched 按人数 42–96 s、ready 60 s |
  | `xm:{match}:pop:<token>`、`xm:{match}:requeue:<token>` | STRING：弹组 / 回队首的重放标记（Java 独有） | 60 s |
  | `xm:{match}:challenge:<id>`、`challenge-target:<pid>`、`challenge-done:<id>` | 切磋记录（HASH）、目标的待应答占坑（STRING）、消费墓碑（HASH，Java 独有） | 60 s |
  | `xm:{match}:battle:<battle_id>` | HASH：战斗落点记录，`a` = 第几次尝试、`pb` = xm-match 自有 proto `BattlePlacement`；6.5 起同时充当观战记录 | 360 s |
  | `xm:{match}:watching:<pid>` | STRING：观战标记（批次 6.5，§4.24.1），值 = `<battle_id>:<16 位小写 hex nonce>`，一律按值删 | 360 s |
  | `xm:{match}:watchable` | ZSET：可观战索引（批次 6.5，§4.24.1），成员 = battle_id 无符号十进制，分数 = 落点的 `created_at_ms` | —（读路径与清扫按分数判过期） |

  只读别人的键：在线目录 `xm:presence:*`、位置记录 `xm:location:*`、节点目录 `xm:nodes:battle:0` 与 `xm:nodes:scene:<zone>`、战斗锁（只经 `BattleLockReader` 做 EXISTS，咨询性读，权威在 scene 的备战写锁，§4.23）。
- **脚本与可重放**：票据与队列共 15 段 Lua（`ticket.TicketScripts`，由 `RedissonTicketStore` 执行）——`S_STATUS`、`S_HEAL`、`S_JOIN`、`S_CREATE_GROUP`、`S_CANCEL`、`S_SNAPSHOT`、`S_DROP`、`S_POP`、
  `S_READY`、`S_EXTEND`、`S_DEL`、`S_DEL_GROUP`、`S_REQUEUE`、`S_PRUNE`、`S_LOCK_RELEASE`；落点的 `S_PLACE` 在 `RedissonPlacementStore`，切磋的三段在 `ChallengeScripts`，观战的 11 段在 `spectate.SpectateScripts`（6.5，§4.24.1）。全部脚本（含只读的）读主库；
  脚本只碰经 `KEYS` 传入的键；时间一律取 Redis `TIME`（等待时长夹到 ≥ 0）。Redisson 超时会把 EVAL 原样重发（§6），每段可变脚本都按可重放设计：建票按票号认出「这就是我上次写的」；
  弹组与回队首按调用方给的 token 写标记——这两段的 CAS 条件在第一次执行之后还能重新成立（弹出的人会回队首、回了队首的人会再被弹出），只看票据状态认不出重放；其余各段靠 CAS 条件不再成立。
  所有票据写都带 ticket id 做 CAS；先判定（只读）后写，入队类脚本先写队列后写票。调用在发出之前截止已过时不发命令、直接抛，所以补偿路径上的票据调用一律给新的截止。
- **票据状态机**：`queued`（在队列里，6 h）→ 被弹组置 `matched`（TTL = `MatchBudgets.matchedTicketTtlSeconds(n)`：42 / 48 / 54 / 60 / 66 s，10 人 96 s，就是 scene 的备战期限）→ 开局成功置 `ready`
  并写 battle_id（60 s）→ 过期；开局失败时幸存者回 `queued`（回队首、`enqueued_at_ms` 不动）、肇事者删票。PVE_SOLO、整队、活动不入队，直接建 `matched` 票；切磋不建票。
  GetQueueStatus 把三态映射成 QUEUED / MATCHED / READY，没有票是 NOT_QUEUED。残留的 ready 票与「是 queued 却不在队列里」的孤儿票在下次排队 / 预检时按票号自愈。
- **排队 157 / 148 / 153**（`queue.QueueService`）：157 的判定顺序同基线（身份 → 模式与人数 → 战斗锁 → 票据自愈 → 位置 → 建票）；Java 另在读位置之后、建票之前加三道闸（发号租约已丢失、PVE_SOLO 且租约无效、
  PVE_SOLO 且开局许可为 0，都回 16004、不建票），不改变前面各步的先后；入队前经 `match-db` 读评分（失败回落 1500）。148 的各种结局对客户端都是成功；153 的 `queued_seconds` 用同一次读出的 Redis 时间算。
- **凑单**（`matcher.MatcherRunner` / `QueueMatcher` / `GroupPicker` / `Tolerance`）：单线程 `match-matcher` 每 500 ms 一轮（`scheduleWithFixedDelay`，每轮 `try/catch Throwable`）。
  每轮先判三种暂停（battle 目录里没有可分配的条目或读失败 / 发号租约无效 / 开局许可已满；暂停的一轮不读注册集、不抢锁），再读注册集，对每条队列抢锁（10 s）→ 读长度 →
  取前 256 人的快照与各人票据 → 按「锚点 + 评分容差」挑一组（至多 32 个有效锚点；候选是快照里除锚点外的全部成员，评分模式按分差升序稳定排序；容差 100 起、每等 5 s 加 100、封顶 1000、等满 90 s 不限）→
  `S_POP` 原子弹组（核对每人仍在队列、仍是 queued、票号相符、退避已到，全部满足才摘出并置 matched）→ **弹出之后第一句就是把计划交给开局管线**。校验时有战斗锁的删票出局，位置是重连租约的本轮跳过、
  登出或无记录的删票出局，无肇事者失败后带退避（`requeue-backoff` 2 s）回队首的票暂不参与。预算：一条队列的读与剔除共用一个等于锁 TTL 的截止，剩余不足十分之一时正常收手；
  弹组每次尝试 2.5 s 的独立预算，结局不明用同一个 token 重发一次，所以一条队列最坏耗时 = 锁 TTL + 5 s。凑不满且非空的队列每 30 s 做一次只看票据的残项清理。
  多实例按队列加锁并行，抢不到锁的实例把自己那份队列 gauge 置 0（看板按实例求和）。
- **开局管线**（`gather.GatherPipeline.run(GatherPlan)`，五个入口——凑单、PVE_SOLO、切磋、整队、活动——汇入同一条管线，失败策略由入口给：回队首 / 全删 / 没有票）：每次开局跑在一个**虚拟线程**上
  （`VirtualThreadGatherLauncher`，`match-gather-N`），代码写成直线式、每跳 `future.get(剩余预算)`，与基线逐段对照；全局在途上限 `gather-max-inflight`（256），拿不到许可即 `overloaded`、无副作用。
  步骤：发号（或用活动预发的号）→ 从节点目录随机挑一个可分配的 battle 节点，同一次 Redis 时钟读数定出战斗期限（+ 300 s，即 177 的 `expire_at_ms`）与备战期限（+ matched TTL）→ `GatherHooks.beforePrepare`
  （开局前清退观众；6.4 是空操作，6.5 起由 `SpectateGatherHooks` 实现，§4.24.1）→ 分队（5V5 重读评分蛇形分队，其余按下标）→ 按成员顺序**逐人串行**定位持有者并 `prepareBattle`（3 s）→ 配表指纹比对（`table-fingerprint-mode`）→ 种子与
  `created_at_ms` → **建房之前写落点记录**（写不进去不开局）→ `createBattle`（5 s；节点级拒绝 `NOT_ALLOCATABLE` 时按（节点号，实例）排除后换节点重试一次并改写落点）→ 全员 `S_READY` → 补写落点 →
  `GatherHooks.onStarted`（6.5 起：把这一场登记进可观战索引）。钩子抛异常只记日志、不影响开局，失败路径不调 `onStarted`。177 / 143 由 battle 发（§4.22），match 不发。
- **补偿**（`gather.Compensation`，固定次序：续期 matched 票 → 对「已冻结 ∪ 备战结局不明」的人逐个 `cancelBattlePrepare` → 按入口策略处置票据 → 删预写的落点记录）：备战的应答是 tip ≠ 0、`NOT_HERE`、`OVERLOADED`
  时该玩家是肇事者且不发取消（scene 保证零痕迹）；传输失败、超时等结局不明的同样判肇事者，但补发取消。取消发回备战时记下的那个节点，不按位置重新解析（Java 断线即移除实体，重新解析会漏发）。
  建房的结局分四类：节点级拒绝与「已受理但明确拒绝」（`create_rejected`）不发销毁；**请求确定没有送达**（`RpcFailures` 判 `NOT_SENT` 且地址是本次目标，`create_not_sent`）不发销毁、直接按没建房补偿；
  其余（超时、应答残缺、连上之后断开）按「可能已建房」先 `destroyBattle`，成功才解冻，销毁也失败则不解冻、不动票据、保留落点记录（`create_failed_room_alive`，由备战期限与 matched TTL 自愈）。
  备战 / 取消的调用超时是 3 s，小于 scene 一条写锁脚本的最坏耗时 4.2 s——按裁决不加长：超时即结局不明、补发取消，scene 在写锁在途时收到的取消会延后到写锁完成再删锁（§4.23）。
- **出站与直连客户端缓存**（`port.NodeCalls` / `NodeClientCache`）：三份 `NodeRpcClients`——`scene-battle`（备战 / 取消）、`battle-node`（建房 / 销毁）、`battle-placement`（补签直拨专用，只按地址缓存）。
  拆开的原因：底层缓存的键只有地址，同一地址上实例号不同就销毁旧引用重建，被销毁的引用上在途的调用当场失败；补签拿落点记录里的旧实例号、开局拿目录里的新实例号，共用一份时 battle 原地址重启后
  两边会互相顶掉对方正在用的引用。补偿的取消与回滚的销毁带着「先前记下的目标」发，经 `callRemembered` 复用该地址上现有的引用。守护线程 `match-rpc-sweep` 每 60 s 清掉空闲 ≥ 360 s 的地址。
- **落点记录与补签 179**（`placement.*`、`reissue.BattleTicketReissue`）：落点记录比基线多存 battle 的实例号与 rpc 地址，`S_PLACE` 单调写（尝试号 ≥ 已存值才写），`created_at_ms` 取 Redis `TIME`、
  `player_names` 是角色名按成员顺序（6.5 的观战列表直接用；落点 HASH 的字段名与损坏判据在 `placement.PlacementRecords`，179 只看 `pb`，观战读路径另核对 `a` 字段）。补签在工作线程上按**记录里的地址**直拨 `issueBattleTicket`（至多 3 s），调通了原样透传 battle 的裁决；没有记录回 1005；
  判房间已死回 1005 要三条证据同时成立——直拨的请求确定没有送达、目录里同号节点已换实例、对原地址的 TCP 建连探测明确被拒绝 / 不可达（`TcpConnectProbe`，上限 300 ms）；其余没调通一律 1003。
  先直拨而不先查目录，是因为 battle 丢租约后停止发布目录但不作废在打的房间（§6）；超时不判死，是因为 1005 会让客户端永久放弃这一局。6.5 的观众 RPC 复用 `PlacementDialer`，并给它与 `BattleNodes.lookup` 各加了一个带硬截止（`Deadline`）的重载（§4.24.1）；179 仍走不带截止的旧重载，行为不变。
- **评分存储与对局结果流**（`rating.*`）：规则照搬基线（K = 32、队伍取平均分、平局 0.5、回合打满 30 按平局、只计 1V1 / 5V5、下限 0、两位小数、新号 1500）。存储是 MySQL 两张表（xm-pbmysql 启动时自建，
  db-migrations M11）：`match_rating`（每人一行，评分 × 100 与局数）、`match_rating_applied`（每局一行的入账标记）。`RatingStore.apply` 一局一笔事务：先插入账标记（撞主键 = 已入账，回滚）→
  按玩家号升序补出缺失的行并 `SELECT … FOR UPDATE` → 算 Elo → 逐人更新 → 提交；死锁 / 锁等待超时整笔重跑至多 3 次。一局要么全员落账、要么都不落账。
  对局结果来自 Kafka topic `xm-battle-result-g<代次>`（xm-battle 生产，§4.22；主人是 xm-match：缺就建 3 分区、核对分区数、把保留期校正到 7 天）。`BattleResultConsumer` 一条专用线程 `match-rating-consumer`，
  消费组 `xm-match-rating`，从最早位点开始、关自动提交，逐条入账成功才 `commitSync`；可恢复的库故障暂停全部分区、1 s → 30 s 退避后重试原记录、不跳过（gauge `xm_match_rating_consumer_paused`）；
  解不出的消息与被数据库判为数据错误的局写毒丸日志 `xm.match.rating.poison` 后跳过（分别计 `decode_error` / `rejected`，任何增量都该告警）。入账标记保留 30 天（`RatingCleanup`，每小时分批清）：
  必须比结果消息在 topic 里的最长寿命（保留 7 天 + 滚段周期约 7 天）活得久，否则从最早位点重放时会重复入账——改 topic 的保留期或声明 `segment.ms` 时要一起重算。
  读评分（排队与 5V5 分队）经 `JdbcRatingReader` 在有界的 `match-db` 池上执行，任何失败回落 1500、永不抛；虚拟线程里不直接跑 JDBC。评分不下发客户端。
- **切磋 152 / 151**（`challenge.*`）：发起时只做咨询性检查、不冻结任何人；`S_CH_INVITE` 把「目标的待应答占坑 + 写记录」一次原子完成，156 经 `PlayerPushes` 推给目标，确认发出（`SENT`）才算成功；
  应答时 `S_CH_CONSUME` 一次性消费记录（带请求 nonce 的墓碑让重发原样返回），并发的两条接受只有一条成功；接受后先给双方各推 154 true 再开局（名单 [发起者, 应战者]，不带票据、不计分），
  开局失败在 `match-push` 上各补推一次 154 false——先等两条 true 都有了结局再发，保住先后。
- **整队开战与帮会活动开战**：两个入口共用逐成员预检 `precheck.MemberPrecheck`（每人依次 在线 → 战斗锁 → 位置 → 票据，首个失败即结果，各入口自己映射成 team 段 tip 或活动的拒绝枚举）与原子建全员票的
  `team.GroupTickets`（`S_CREATE_GROUP`：任一人已有别的票就一张都不建，返回名单序第一个冲突者；结局不明时用独立 3 s 预算按本次票号回滚）。`MatchTeamService` 四个方法：`checkTeamMatch`（人数 + 预检，
  附开战锁时长）、`createTeamTickets`、`releaseTeamTickets`、`runTeamGather`（长挂，future 在开局结束时完成）；调用方把这一跳肯等多久经 Dubbo 附件 `xm-budget-ms` 带上，提供方的本地截止不晚于它，
  到达时已过期就什么都不写（建票回 `EXPIRED`）。`MatchInternalService.startActivityBattle` 的业务拒绝都在应答的 `reject` 里，battle_id 预先发号、同步回给调用方，开局异步；调用方（xm-guild）与活动结果的消费随 4.6。
- **启动门禁与停机**（`MatchConfiguration` / `lifecycle.MatchStartupChecks` / `MatchLifecycle`）：启动次序由 bean 依赖钉住——运行模式与 `xm.match.*` 自身校验 → `XM_DUBBO_SECRET` → 配置表
  （`pve-team-size-by-config-id` 的副本号必须在 Dungeon 表里）→ 预算断言（各跳超时不超过上限——备战 / 取消 / 销毁 / 补签 3 s、建房 5 s，matched TTL 表等于 42 / 48 / 54 / 60 / 66 / 96、5 人开战锁 ≤ 110 s、
  Redis 单条命令最坏耗时 ≤ 6100 ms）→ 占 `NodeTypes.MATCH` 发号租约 → 建评分表 → 导出 Dubbo → 起凑单 → 起观战清扫（6.5 起）→ 起评分消费（同步核对一次 topic：契约不符拒启，Kafka 不可达告警后照常启动、后台每 30 s 重试）。
  别的包的 bean 都是硬依赖，少装一包拒启。就绪日志「match 已就绪」是本机切片的就绪判据（脚本只认这个子串；6.5 起整句是「match 已就绪：Dubbo 已导出，凑单、观战清扫与评分消费已启动」）。停机：停凑单（等当前一轮）→ 撤 Dubbo 导出 → 排空 `match-worker`
  （6.5 起同时并行等在途的 163，至多 5 s）→ 停观战清扫（6.5 起，当场中断）→ 有界等待在途开局（10 s，超时放弃：
  票据按 matched TTL 自愈、scene 按备战期限解冻）→ 停评分消费 → 交还租约、关直连客户端。6.5 之后的次序与预算口径见 §4.24.1「停机」。启动的后两步在启动线程上、停机各步在关停线程上，两者可能同时在场：「判停机 → 起凑单」与「置停机 → 停凑单」经同一把锁串行，刚启动完就收到停机信号也不会留下一个还在弹组的凑单；
  评分消费的启动（要同步核对 topic）不放在锁里，起完发现停机已经走过就自己补停，这种情况下不打就绪日志。
- **发号租约的两种失效**（battle_id 与 challenge_id 取 `NodeTypes.MATCH` 上的雪花，§9）：**续期滞后**（Redis 抖动）时发号失败、凑单暂停（`paused_no_lease`）、PVE_SOLO / 整队 / 活动在建票之前就拒，
  其余入口照常，恢复后自动继续；**真正丢失**不会自愈——进程不退出，健康组件 `matchLease` 报 DOWN（`/actuator/health` 503）、`xm_match_lease_lost = 1`、一条 ERROR，派发层对 157 与 152 当场回 16004
  （不让票据入队后永不成局），凑单永久暂停，取消 / 查状态 / 补签照常；有编排层的环境靠健康检查重启，本机切片要手工重启。基线在这里是进程退出重启。
- **dev / test 管理口**（管理端口，Java 独有）：`GET /admin/match/dev/rating/{pid}`（robot 断言评分）与 `POST /admin/match/dev/activity-battle`（契约 protobuf 字节进出，与 Dubbo 提供方同一个实现，
  建的是正常房间）。鉴权同 xm-trade 播种接口（`MatchAdminAuthFilter`：令牌没配 503 → 令牌不符 401 → 操作人不合法 400 → 运行模式不是 dev / test 403），每次调用写审计日志并计数。
- **配置**（`xm.match.*`）：`worker.threads` 16 / `worker.queue` 1024、`request-budget` 4500 ms（须在 [500 ms, 4500 ms]，小于 gate 调它的 5 s）、`matcher.interval` 500 ms、`matcher.lock-ttl` 10 s、
  `ticket-ttl` 6 h（须大于 96 s）、`ready-ticket-ttl` 60 s、`challenge-ttl` 60 s、`rating.enabled` true、`rating.tolerance.*` 100 / 5 / 100 / 1000 / 90、`rating.draw-round-cap` 30 与按副本的覆盖表（缺省空）、
  `rating.consumer-group` `xm-match-rating`、`pve-team-size-by-config-id` `{1: 5}`、`table-fingerprint-mode` warn（off / warn / enforce，写错拒启）、`gather-max-inflight` 256、`requeue-backoff` 2 s（[0, 60 s]）、
  `kafka.{bootstrap-servers, topic-generation, replication-factor, init-timeout}`（`${XM_KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:9092}` / `${XM_BATTLE_RESULT_TOPIC_GENERATION:1}` / 1 / 10 s）；
  6.5 的 `spectate.sweep-interval` 10 s（> 0 且是整毫秒）与 `spectate.max-inflight` 128（≥ 1），不合法拒启。
  各跳超时、matched TTL 公式、补偿续期、开战锁余量、落点记录 TTL 是 `MatchBudgets` 的代码常量，不开放配置——它们出现在跨进程不等式里（battle 的确认补发窗口、scene 的锁余量、xm-team 的开战锁与收尾截止），
  改一处要连带核对，单测与启动断言直接引用。Redis 库 12，MySQL 库 `xm_java`。
- **已知残余**（规格末尾「遗留与残余风险」）：179 在共用工作池上同步等直拨至多 3 s，battle「连得上但不应答」时并发补签能占满工作池；开局选 battle 节点时同步读目录、没有本地截止；
  157 建票结局不明后的回滚没有独立预算；非 SQL 的确定性异常会让评分消费所在分区停住；按兜底日志回灌超过 30 天的旧结果会重复入账；`RpcFailures` 的判定依赖 Dubbo 3.3.6 的异常形态（失效方向安全）；
  256 个在途开局没有压测。部署约束（后端重启后的重连、读路由、代次一致）见 §6。

#### 4.24.1 观战的 match 侧与跨区 1V1（批次 6.5）

mmorpg：`go/match/internal/logic/{watchbattlelogic,listwatchablebattleslogic,spectate}.go`（163 / 164、观战标记、可观战索引）、`gather.go` 里的两个观战钩子、挂在凑单上的兜底清理。
Java 版放在 xm-match 的 `com.game.match.spectate` 包，不新增进程、不新增第三方依赖；规格与逐条出处见 `docs/porting/spectate-spec.md`（有意差异 W1–W20、X1–X8，照搬的基线怪癖 BW1–BW10 见 PARITY「观战」「跨区 1V1 匹配」两行）。
观战的房间侧——AddObserver / RemoveObserver、177(OBSERVER) / 161 / 158 / 166、165、每房 20 人——在 xm-battle（§4.22）；match 侧只有两个客户端号 163 WatchBattle 与 164 ListWatchableBattles，加上开局前清退观众。
客户端可见的部分——163 的判定顺序、16004 / 16014–16019 与九条 `parameters[0]`、成功只回 `battle_id`、164 的条数收口 / 排序 / 懒剔除后不补齐、换场与「重看同一场」的区别、
「先观战后排队允许、先排队后观战拒绝」的不对称互斥、开局前清退推 166 REMOVED——与基线逐字节相同；基线靠「先查后写 + 事后复查」拼出来的几处改成单段 Lua，差别只在竞态、故障、过载或很窄的时间窗里可见。

- **键**（与票据、落点记录同在 `{match}` tag，见上面的键表）：观战标记 `xm:{match}:watching:<pid>`——值带本次请求的 nonce（每次抢标记生成一个），删除一律按值，所以同一玩家的两条并发 163 不会删掉对方刚抢到的标记；
  它只是「可能在观战」的提示，TTL 360 s（≥ 战斗最长时限 300 s），同值重放不续期。可观战索引 `xm:{match}:watchable`——全服一把、不分 zone，没有 TTL，过期分界 = Redis `TIME` − 360 s（分数严格小于分界才算过期），选场、列表、清扫同一口径。
  观战记录就是 6.4 的落点记录（只读，外加两种有条件的删除）。战斗锁不在 `{match}` slot（6.3 拥有），所以锁检查进不了脚本，靠登记之后的复查兜底。
- **脚本**（11 段 Lua，`SpectateScripts`，由 `RedissonSpectateStore` 执行；全部以读写模式执行 = 读主库，时间取 Redis `TIME`；玩家号、战斗号、成员在 Lua 里只当字符串）：
  `S_W_ENTRY`（一次往返读「有没有票据」与标记值）、`S_W_ACQUIRE`（票据存在 → queued；标记值相同 → ok，即重放；标记存在 → busy；否则 `SET PX 360000` → ok——把基线「先查票据、后 SETNX」合成一步）、
  `S_W_RELEASE`（值相符才删）、`S_W_MARKS`（批读一组玩家的标记，开局清退用）、`S_W_READ`（原子取「成员是否在索引里、落点的 `a` 与 `pb`、TIME」）、`S_W_RECORDS`（批读落点，164 用）、
  `S_W_PICK`（只在未过期区间里按 Java 侧给的随机数取一个：过期成员不可能被挑中）、`S_W_EVICT`（四种模式——`invalid` 只摘成员；`missing` 落点不存在才摘、**永不删落点**；`dead` 落点的 `a` 等于给定 attempt 才连落点一起删，被改写过就不动；
  `stale` 成员的分数过期才连落点一起删）、`S_W_PUBLISH`（落点的 `a` 等于这次开局的 attempt 才 ZADD）、`S_W_SWEEP`（按分数摘过期成员，不动落点）、`S_W_LIST`。唯一的普通命令是清扫采样用的 ZCARD。
  两条顺序不变量：先有最终落点、再进索引；读到落点缺失时只摘成员、不删落点（落点可能是读之后才预写的，删掉它 179 就失去定位）。可变脚本都能被 Redisson 原样重发；只有 `S_W_ACQUIRE` 挡不住「调用方放弃并释放之后才到的那一遍重发」——
  存储在放弃等待而命令还在路上时，等它有了结局再按值释放一次，这只是收窄，漏掉的标记留到 TTL。
- **163 的流程**（`WatchBattleService`，直线式代码与基线逐行对照；判定顺序本身就是客户端契约）：身份 → `S_W_ENTRY`（持票，任意状态 → 16014）→ 战斗锁（有 → 16015）→ 收拾旧标记：值非法就删；显式重看同一场只删标记、**不发** RemoveObserver
  （否则给仍活着的旧会话推一条假的 166，重推 177 / 首帧由 battle 的幂等分支负责）；换场或随机先**同步**对旧场 RemoveObserver、再删标记（迟到的 Remove 会把随机重挑到同一场的新登记摘掉）→ 在线目录严格读（没有条目 → 16019）→
  选场循环（指定一轮；随机两轮、每轮至多 3 挑）：`S_W_READ` → `S_W_ACQUIRE`（**先写标记、后登记**，之后才开始的开局一定读得到并清退）→ `addObserver`。结局：成功则复查票据与战斗锁（两条虚拟线程并行；读票失败按无票，读锁失败或等超时按有锁），
  命中就异步发 RemoveObserver、删标记、回 16014；battle 回「房间不存在」或直拨判死 → 删标记，这一场在建房窗口内（读落点的那一刻还没公开，且距 `created_at_ms` 不超过 22.2 s——开局从写落点到最后一次建房的最坏耗时）不剔除，
  窗口外按 attempt 守护剔除、随机换下一轮；其它拒绝与「确定没送达」删标记、回 16018；**结局不明时保留标记**（battle 可能已经登记了这名观众，留着它下一次开局清退才摘得到他），不补发 Remove。
  观众路由（`BattleRouting` 的 session / gate 节点号 / gate 实例 / zone）经 `BattleRoutings.gatePart` 全部取在线目录，与 battle 推 177 的目标一致；scene 字段留空。
- **观众 RPC：直拨与判死**（`ObserverDialer` / `DefaultObserverDialer`，包着 6.4 的 `PlacementDialer`，用补签直拨那一份客户端缓存）：按**落点记录里的地址**直拨 battle，不重试、永不抛，结局四分——`Replied`（调通了，按 battle 的 tip 处理）、
  `Dead`、`NotDelivered`（请求确定没送达、但判不了死）、`Unknown`（超时、连上之后断开、对端回了传输层错误）。判死与 179 共用三条证据：请求确定没有送达 + 节点目录里同号节点已换了实例 + 对原地址的 TCP 建连探测明确被拒绝 / 不可达；
  先直拨而不先查目录的理由同 179——battle 丢租约后停止发布目录但不作废在打的房间，照样能登记观众。带硬截止的重载 `dial(placement, timeout, hardStop, call)`：进来时截止已过不发调用，交给出站口的超时 = min(超时, 截止的剩余)，
  本地等待不再另加余量，没送达之后的目录读（`BattleNodes.lookup(…, Deadline)`，至多 min(1 s, 剩余)）与探测都夹在截止之内，**到点一律按「没调通、不判死」返回**——时间不够永远不会被当成「这一局没了」。
  夹不住的只有「发调用」这一步本身：它在调用线程上同步执行，靠出站口不阻塞的契约（`NodeClientCache` 清扫销毁空闲引用时短暂占着同一把锁，没有实测；「锁内摘表、锁外销毁」归 7.6）。
- **164**（`WatchableListService`，在 `match-worker` 上，只有 Redis 操作）：`S_W_LIST` → 先凭成员与分数判非法 / 过期 → 其余一次 `S_W_RECORDS` 批读落点 → 按索引次序组摘要（`BattleWatchSummary` 从 `BattlePlacement` 原样映射）→ 应答组好之后一批异步剔除。
  读索引失败回信封 1003；批读失败或某条落点损坏只是跳过、不剔除（列表变短，不回 1003）；剔除后不补齐条数；空列表照常回包。
- **开局钩子**（`SpectateGatherHooks`，实现 6.4 的 `GatherHooks`，跑在开局的虚拟线程上；五个入口都经过）：`beforePrepare` 先一次批读全员的观战标记（至多 1 s；读失败整次跳过清退、标记不删），再按名单逐人串行——读落点 → 在就 RemoveObserver → 按读到的原值删标记，
  每人一个 3 s 的截止由这三步共用（matched TTL 公式里每人 3 s 的那一项就是它），所以 `beforePrepare` 的总上限 = 1 s + 有标记的人数 × 3 s；任何异常都吞掉，**永不阻断开局**，开局随后失败也不恢复观战。
  管线在这一步之前就失败的（发号失败、没有可分配的 battle 节点）与拿不到在途许可的过载收尾不调钩子。`onStarted` 在全员置 ready、落点同值补写之后调，按 attempt 公开（至多 1 s，尽力而为、不重试）；失败的场次永远不进索引。
- **清扫**（`SpectateSweeper`，守护线程 `match-spectate-sweeper`，`scheduleWithFixedDelay`、每轮 `try/catch Throwable`）：每 `spectate.sweep-interval`（10 s）一轮，`S_W_SWEEP` 摘掉过期成员并用 ZCARD 采样 `xm_match_watchable_battles`，两条命令各等至多 3 s；
  第一轮在一个间隔之后。读路径已按分数过滤，间隔只影响残留成员的数量（基线是每 500 ms 挂在凑单上）。多实例各跑各的、不抢锁，幂等。它自己不带生命周期，由 `MatchLifecycle` 启停。
- **线程与预算**：163 每个受理的请求一条虚拟线程（`SpectateExecutor`，全局在途上限 `spectate.max-inflight` 128）——163 最坏串两跳各 3 s 的 battle RPC，放在 16 线程的 `match-worker` 上，一个慢 battle 节点就能把排队与取消挤成过载。
  许可在 Dubbo 线程上当场试拿（不阻塞、不排队），拿不到即回 in-band 16004；拿到之后的任何出口都归还。整请求一个截止（受理时刻 + `request-budget` 4500 ms，小于 gate 调 match 的 5 s，保证 match 先给出 in-band 应答），每次 Redis 读写只等到它；
  两跳 battle RPC 各有更早的硬截止——换场的 Remove 是请求截止 − 1.2 s，登记的 Add 是请求截止 − 0.2 s，超时都取 min(3 s, 硬截止的剩余)；换场要发 Remove 而剩余不足 2.2 s、或登记前剩余不足 1 s 时不发一个注定超时的调用，直接回 16004。
  这两道门槛是代码常量，而 `request-budget` 的合法区间仍是 [500 ms, 4500 ms]：配到 2300 ms 以下启动时打一条 WARN（带旧标记的换场 / 随机观战会因预算不足回 16004，≤ 2200 ms 时恒回；低于 1100 ms 时凡走到登记的 163 都回 16004），不拒启。
  自我清退的 RemoveObserver 另起虚拟线程异步发出（固定 3 s，不占在途许可）；复查的两次读各一条虚拟线程。虚拟线程上不在 `synchronized` 里阻塞（一条集成用例用 JFR 的 `jdk.VirtualThreadPinned` 事件核对等 Redis 的路径不钉住载体线程）。线程表见 §5。
- **停机**（`MatchLifecycle`，日志「停机 n/6」）：停凑单 → 撤 Dubbo 导出 → **排空 `match-worker`（≤ 10 s）与等在途 163（≤ 5 s，辅助线程 `match-stop-watches`）并行** → 停观战清扫（当场中断手上那一轮：清扫是幂等的只删操作，做一半被打断无害）→
  等在途开局（≤ 10 s）→ 停评分消费 → 还租约。163 在自己的执行器上，工作池的排空管不到它，所以要单独等；并行与当场中断是为了加了观战之后第 3–5 步的设计预算仍是 20 s，与 6.4 相同。
  这 20 s 取值与 `spring.lifecycle.timeout-per-shutdown-phase` 相同，但它是设计预算，不是 Spring 的强制截断（Spring 不会打断同步的 `stop()`）；硬期限在进程外、从 SIGTERM 起算——本机切片是 `stop-slice.sh` 的 20 s，
  容器是按这个值推出来的宽限期 35 s（ops-release-spec §0.6 #30）。等不完的 163：已抢到的标记留到 TTL，客户端按 gate 的超时收到信封 1003。
- **跨区 1V1**：没有新的生产代码路径。排队池与 battle 池全局、zone 取位置记录（6.4）、确认与结算按 (zone, scene 节点号) 加实例或位置记录寻址（6.3）、大厅公告按带 zone 的在线目录寻址（6.2），组合起来就支持两个区的玩家凑成一局；
  xm-match 全局部署、不按 zone 起，每个区的 gate 把 `MatchService` 转给同一组 xm-match。Java 特有的坑是**同号节点**：scene / gate 的节点号按 zone 租约（§6），两个区的第一台都是 1 号，任何只按节点号查目录、拼频道、拼缓存键的代码都会把区 2 玩家的消息送到区 1 的同号节点，
  而实例过滤会把它丢掉而不是送错——症状是「锁停到 TTL」「公告丢失」。6.5 逐处核对了 6.2 / 6.3 / 6.4 的寻址代码（静态审计，没有发现只按节点号或会话号寻址的地方）并补了同号碰撞的测试钉；本机 `XM_ZONES=2` 的切片有意让区 2 的 scene / gate 也是 1 号，
  robot `battle-cross-zone` 在这个形态上跑。三种 zone 不要混用：位置记录的 zone（排队、开局、结算）、在线目录的 zone（推送、观众路由）、归属区（建角、存盘、组队；1V1 不读）。
- **已知残余**（规格末尾「遗留与残余风险」）：开局成功后 60 s 的 ready 残留期间 163 回 16014、已结束的战斗留在列表里直到被点中或创建满 360 s（两处都照搬基线，登记为两版同改候选）；自我清退异步，并发开局秒败后立刻重看同一场时新登记可能被迟到的 Remove 摘掉；
  切磋的备战晚于复查时玩家同时是旧场观众与新局参战者（两版相同，客户端靠改连兜底）；AddObserver 超时后 177 仍可能晚到；不是合法 UTF-8 的脏标记按值删不掉、只能等 TTL；battle 整体不可达时 163 每请求一条 ERROR，日志没有限频；
  清扫第一轮在一个间隔之后，启动后头 10 s `xm_match_watchable_battles` 读数为 0。

## 5. 线程模型

- **gate**：Netty I/O 线程处理编解码与会话；同一会话的消息按到达顺序转发（会话绑定到 channel 的 EventLoop）。
  Dubbo 调用异步完成，回调通过 `channel.eventLoop().execute(...)` 回到会话所在线程。
  **按消息号限频**（C++ `MessageLimiter` 同义）：每个会话、每个消息号一个滑动窗口，上限取 MessageLimiter 表
  （`xm.table-dir` 下的 `messagelimiter.pb`，与 mmorpg 同一份配表），表里没有的缺省每秒 3 条；超频回
  `MessageContent{message_id, id, error_message{1008 kRateLimitExceeded}}`、不转发、计非法包（到阈值断开）。
- **scene**：**一个逻辑线程拥有全部场景状态**（Netty `DefaultEventLoop`）。审计记录（资产流水、玩家快照）在专用的 `scene-audit` 线程上发往 Kafka（§4.5），
  逻辑线程只投递不可变草稿。I/O 线程只做解码，把消息投递给逻辑线程
  （每条链路有积压上限，见 §4.2）；阻塞 I/O（MySQL / Redis）在有界的存储线程池上执行，结果再投递回逻辑线程。
  场景状态只在逻辑线程读写，不加锁。
- **scene 场景帧**（`SceneTicker` + `SceneWorld.step`）：20 FPS 定时任务同样跑在逻辑线程上，与客户端消息串行。
  固定步长累加器（夹 ±1s、每次最多补 5 帧，多出的整帧时间只扣不补），帧内异常只记日志、不让定时任务停掉。
  每帧顺序：外推（速度非零的 `location += v × 0.05`，600 帧无任何客户端消息即停推）→ 视野刷新并发 47 / 64 →
  偶数帧属性同步 66 → 帧号 +1。移动上行（134 / 132 / 131）在两帧之间收到时当场裁决，位置变化登记到下一帧的视野刷新。
  视野索引（`ViewIndex`：20 m 方格 3×3 邻域 + 双向兴趣列表）每个场景一份、只归逻辑线程所有。
  重判节奏：静止的人位置一变（含刚停步、挂机停推）下一帧就按精确位置重判；移动中的人相对上次重判累计位移 ≥ 1 m 才重判，
  不足的留在待刷新名单里——进 / 出视野在移动中最多晚约 1 m 位移，换来人群里每人每帧一次 3×3 扫描降到约每走 1 m 一次。
  每次重判 O(3×3 格内人数 + 兴趣列表长度)，候选直接取格子里的兴趣状态（不按玩家查表），双方的表都满时连距离都不算。
  待刷新名单、候选缓冲、视野变化缓冲都复用，按条目取邻域走格子间的引用、不查表不装箱；帧内不新建集合，
  仅有的分配是集合迭代器与外推的新坐标（小对象）。没人移动、没有脏字段的帧只遍历一遍玩家、不发消息。
  出生点人群基准 `ViewCrowdBenchmarkTest`（默认跳过，`-Dxm.bench=true` 开启）：N 人全在出生点 15 m 内、1–9 m/s 随机走，
  2026-09-30 本机整帧均值 1000 人 42.7 ms → 4.2 ms（p99 8.8 ms）、2000 人 174.5 ms → 22.4 ms（p99 48.9 ms，
  其中偶数帧 66 扇出约 8 ms）；2000 人挤在 15 m 内的极端密度下尾延迟仍贴着 50 ms 预算，真要承载需分散出生点。
  帧里不做任何阻塞 I/O（写回 / 释放都交给存储线程池）。规则与客户端可见行为见
  `docs/reference/mmorpg-client-contract-{movement,aoi}.md` 与 PARITY「移动」「视野」「属性同步」各行。
- **scene 停服**（`SceneNode.release`）：摘目录 → 停监听 → 停接管订阅与续约 → 断开全部 gate 链路（不再有新帧进来）→
  把「写回全部在场玩家」投递到逻辑线程，**用整个停服预算**（`xm.scene.shutdown-save-timeout`）等它执行完 →
  写回都交给存储线程池之后、关池之前，先等在途的跨节点交出 / 探测的结局在逻辑线程上处理完（与落库同时进行、共用剩余预算；
  冻结中的玩家照常提交 `save(E)`，交出若在它之后提交，结局处理还要往池里提交「释放 E+1」，§8.1）；之后才关池，并用剩余预算等落库；
  预算用完写回还没开始执行就取消它并记一条 ERROR（带大致人数），交出结局没处理完也记 ERROR 照常关池，
  存储池没排空就 `shutdownNow` 并逐条记下被丢弃的写回（player_id / epoch / 场景 / 坐标）。实现与测试：`SceneShutdown`
  （交出与停服交错：`SceneShutdownHandOffTest`，真逻辑线程 + 真存储线程池 + 真仓库，按 `SceneNode` 的停服顺序）。
- **scene 实例**（批次 5.3，§4.21）：镜像 / 副本的建立、空闲回收、复活、级联、显式销毁全在逻辑线程（每秒一次的 `maintainScenes`），取号结果由 Dubbo 回调线程投递回来；
  目录补发只标脏、投到 `scene-sched`（单飞，至多占一条调度线程），逻辑线程不等 Redis。dev 管理口的 HTTP 线程只做鉴权与编解码，业务经 scene-manager future 与逻辑线程。
- **battle**（批次 6.2，§4.22）：一条 `battle-logic` 线程既是直连面唯一的 Netty I/O EventLoop，又独占全部房间、直连会话与房间计时器（同基线单 muduo loop）；
  boss 线程只 accept；Dubbo 提供方线程只读准入闸、投递，future 在 `battle-rpc-reply` 上完成；大厅公告经 Redisson 异步发布，结局只计数。逻辑线程上没有阻塞调用。
- **回合制战斗的 scene 侧与发件箱**（批次 6.3，§4.23）：
  - scene：`PlayerBattle` 与结算账本的全部读写、备战 / 取消 / 确认 / 结算应用 / 进场恢复都在逻辑线程；reaper 是逻辑线程上的定时任务（与场景帧串行）。Redis 脚本一律异步，
    Redisson 回调线程只把结果投递回逻辑线程，回来先核对实例与冻结对象；`SceneBattleService` 的 Dubbo 提供方线程只做在途名额、实例核对、解析与投递，
    应答在 `scene-asset-reply`（与资产通道共用）上完成。逻辑线程回调里的意外异常不许把状态卡住：进场恢复落到 RETRY，挂着应答的调用以异常完成。
  - battle：`battle-logic` 调出站端口只交任务、不阻塞；`battle-outbox`（单线程 `DefaultEventLoop`，守护线程）独占两个发件箱的名单与计时器，Redisson / Dubbo 的回调投递回它，
    活动局的结果也由它发布；`battle-scene-sweep`（守护线程，每 60 s）阻塞读 scene 节点目录、销毁已不在目录的节点的直连客户端——目录读是阻塞的，所以不放在发件箱或逻辑线程上；
    到 scene 的 Dubbo 引用在 `battle-scene-connect-*`（`NodeRpcClients` 自己的两条守护线程，建引用会同步建连、不能占调用线程）上建；dev gather 在管理 Tomcat 线程上做 Redis 查询与 Dubbo 调用，整体等待上限 10 s，不碰房间。
  - 读战斗锁的其它进程：xm-team 的工作线程 / 推送线程在请求预算内限时等批量读；xm-data 的回档前查锁由调用线程限时等（作业在 `data-ops`，dry-run 在 Tomcat 请求线程，§4.5.1）。
- **匹配**（批次 6.4，§4.24）：xm-match 没有逻辑线程，也不碰任何场景状态（scene 的改动都在 scene 逻辑线程上，由 `SceneBattleService` 的提供方投递执行）。

  | 线程 | 做什么 | 不得做什么 |
  |---|---|---|
  | Dubbo 提供方线程 | 解析调用、身份检查、投递到 `match-worker` 或处理器自带的执行器（6.5 的 163：当场试拿在途许可，拿不到就回过载应答）；156 / 154 当场回（163 / 164 只在 6.4 期间当场回）；`runTeamGather` 只登记 future 就返回 | 阻塞 I/O |
  | `match-worker`（固定 16 线程，队列 1024，满了拒收） | 客户端请求（163 除外；164 在这里）、`MatchTeamService` 的前三个方法、`MatchInternalService`；可以同步等 Redisson / JDBC / 补签的直拨，整请求预算 4500 ms | — |
  | `match-spectate-N`（**虚拟线程**，每个受理的 163 一条；全局在途上限 `spectate.max-inflight` 128，批次 6.5） | 163 的直线式流程：每次 Redis 读写与两跳观众 RPC 都在 future 上限时等；许可在线程体的任何出口归还 | 在 `synchronized` 块内阻塞 |
  | `match-spectate-recheck-N` / `match-spectate-evict-N`（虚拟线程，不占 163 的在途许可） | 163 登记成功后的复查（读票据、读战斗锁各一条，只活到请求截止）/ 自我清退的异步 RemoveObserver（固定 3 s） | — |
  | `match-spectate-sweeper`（单线程，守护；固定间隔 10 s） | 摘可观战索引里的过期成员、采样索引大小；每轮 `try/catch Throwable`；由 `MatchLifecycle` 启停，停机时当场中断 | — |
  | `match-stop-watches`（平台线程，只在停机时存在） | 停机时等在途的 163（至多 5 s），与停机线程排空 `match-worker` 并行 | — |
  | `match-matcher`（单线程，固定间隔 500 ms） | 读注册集 → 逐条队列抢锁 → 弹组 → 把计划交给开局管线；每轮 `try/catch Throwable`（JDK 调度器遇到一次异常就永久停表） | 跑开局本身 |
  | `match-gather-N`（**虚拟线程**，每次开局一个；全局信号量 256） | 直线式的开局管线，每跳在异步调用的 future 上限时等；6.5 起开局前清退观众与开局后公开（`SpectateGatherHooks`）也在这条线程上 | 在 `synchronized` 块内阻塞（JDK 21 会钉住载体线程）；直接跑 JDBC（经 `match-db`） |
  | `match-db`（平台线程池，8 线程、队列 256） | 读评分（排队、5V5 分队） | — |
  | `match-rating-consumer`（单线程） | Kafka poll → 解码 → MySQL 一笔事务 → 提交位点 | — |
  | `match-push`（2 线程、队列 1024） | `PlayerPushes` 的回调与计数；切磋开局失败后补推 154 false | 阻塞 |
  | `match-rating-init` / `match-rating-cleanup` | topic 核对的后台重试（每 30 s）/ 入账标记的保留期清理（每小时） | — |
  | `match-lease`、`match-rpc-sweep`（守护线程，60 s 一轮） | 发号租约续期；清三份直连客户端缓存里空闲 ≥ 360 s 的地址 | — |
  | `match-scene-connect-N` / `match-battle-connect-N` / `match-placement-connect-N` | 三份 `NodeRpcClients` 各自的建连线程（建引用会同步建连，不能占调用线程；每份至多 2 条） | — |
  | 管理 Tomcat（至多 4 线程） | actuator；dev 读评分口；dev 活动开战口在它上面执行（截止 = `request-budget`） | — |

  为什么开局用虚拟线程：一次开局是最长约 90 s 的串行 RPC 链，用平台线程池时 scene 卡住会把池子堵满，排队等线程的时间还会吃掉 matched TTL 的预算（TTL 从建票时起算）；改写成
  `CompletableFuture` 链又会失去与基线逐段对照的可读性。这是虚拟线程第一次用在服务端进程里（此前只有 xm-robot 探针与测试用过，tech-stack.md）。线程体的任何出口（含管线抛 `Error`）都恰好收场一次：还许可 → 记指标 → 完成 future。
  登记的两处例外（规格 §9.3）：开局选 battle 节点与凑单的目录概况经 `NodeDirectory.list` 走 Redisson 的同步读、没有本地截止（不持锁、不钉住，上界是 Redisson 自己的超时）；179 在共用的 `match-worker`
  上同步等直拨至多 3 s。
  **163：虚拟线程 + 在途上限；处理器可自带执行器**（批次 6.5，§4.24.1）。163 最坏要串两跳各 3 s 的 battle RPC（换场的 RemoveObserver、登记的 AddObserver），放在 16 线程的 `match-worker` 上，一个慢 battle 节点就能把 157 / 148 / 153 挤成过载；
  所以它与开局同一个执行模式：每个受理的请求一条虚拟线程、全局在途上限 128（`SpectateExecutor`）。接入方式是 `MatchMethodHandler.executor()`——缺省 null = 共用 `match-worker`，处理器返回自己的执行器时派发器把任务投给它；
  对执行器的约定是 `execute` 不阻塞 Dubbo 线程、受理不了（在途已满、已关闭）就抛 `RejectedExecutionException`（派发器据此调 `onOverload()`，与工作池满同一个口径）、受理了的任务恰好执行一次并由执行器自己归还许可；
  停机时「等在途任务」的手段由执行器的拥有者提供（163 是 `lifecycle.InflightWatches`），派发器与工作池的排空都不管它。截止、解析失败与未预期异常回信封 1003、请求计时都仍由派发器统一做；179 以后要挪出工作池可以用同一个钩子。
  连带的两处：xm-team 加锁之后的收尾（清开战锁、退票、推结果）在有界执行器 `team-match-end`（4 线程、队列 1024）上，211 的前三跳在 `team-worker` 上阻塞等 xm-match（每跳至多 3 s）；
  xm-battle 的对局结果在守护线程 `battle-result-out` 上序列化并发往 Kafka，`battle-logic` 与 `battle-outbox` 只往它的有界队列里投（§4.22）。
- **xm-data**（§4.5、§4.5.1）：没有 Netty 线程也没有逻辑线程，阻塞 JDBC / Redis 都在自己的专用线程上。每个审计 topic 一条消费线程；Tomcat 请求线程做鉴权、受理、
  同步只读查询与 dry-run；运维作业在单线程 `data-ops` 上执行（与全集群单飞一致，夺权、沉降等待、帮会 Dubbo 调用、逐人写事务都在这条线程上）；
  `data-ops-fence` 跑作业心跳与归属续约；`data-ops-sweeper` 清扫心跳过期的作业；`data-ops-ids` 申领与续期发号租约。四条 `data-ops*` 线程都是单线程、守护线程。
  持有表（玩家 → epoch）是并发表：夺权 / 释放在作业线程，续约在栅栏线程。
- 场景对象：普通 Java 领域对象，不用 ECS（实体 / 组件 / 系统）：玩家的各玩法状态是 `ScenePlayer` 持有的字段对象（`Wallet`、`PlayerAttributes` ……），
  规则在对应的服务类里（`AttributeService` 等），基线的 entt 组件 / 系统按「领域对象 + 服务」翻译，不照搬。

## 6. 服务发现与部署 profile

- **游戏节点在线目录放 Redis**（`xm-discovery` 的 `NodeDirectory`）：gate / scene 每 5s 写一条 TTL 15s 的条目
  （`GateNodeInfo` / `SceneNodeInfo`，含人数、场景列表、链路地址）。gateway 按它挑 gate，scene-manager 按它分配场景，
  gate 按它连 scene。节点异常退出后最多一个 TTL 才从目录消失，读方必须把连接失败当作不可用处理。
  scene 的场景条目 `SceneEntry` 带种类（`kind`：WORLD / MIRROR / DUNGEON；不认识这个字段的旧节点写出 UNSPECIFIED，按 WORLD 读）与镜像的源（`source_scene_id`），
  镜像 / 副本只登记在这里（批次 5.3，§4.21）。
- **battle 节点目录**（批次 6.2，§4.22）：`xm:nodes:battle:0`（作用域 0，全局池、不分 zone），每 5 s 写一条 TTL 15 s 的 `BattleNodeInfo{node_id, instance_id,
  rpc_host / rpc_port（Dubbo 导出成功后才写）, client_host / client_port（通告给客户端的直连地址）, accepting（准入闸开且租约有效）, room_count, connection_count,
  table_fingerprint}`。读方（xm-match 的开局管线与凑单，§4.24；每次现读、不缓存）只从 `accepting = true` 且 rpc 地址合法的条目里挑；目录最多滞后 5 s，所以 NOT_ALLOCATABLE 后换节点重试一次仍要保留。
  battle 进程异常退出后条目最多留 15 s：这段时间选中它的开局，建房请求确定没有送达时按没建房补偿（不走「销毁也连不上 → 不解冻」），分不清的仍按可能已建房处理。
  **battle 丢了租约**（与 gate 不同）：在逻辑线程上关准入闸、停止发布并尽力删条目（只删仍带本实例 id 的），**不作废**在打的房间——battle 不持有权威数据，
  票据带实例 id，结算按玩家与 battle_id 寻址；房间按期限或胜负自然结束，房间数归零时打「可安全重启」，进程保持存活但不可分配。
- **Nacos 只做 Dubbo 注册中心**（`nacos` profile）。`local` profile 下 Dubbo 用直连 URL，不需要 Nacos 服务端，
  本地开发与端到端测试用它。
- 节点号（gate 的 session 高位、雪花 worker）由 Redis 租约分配（`NodeIdLease`）：`SET NX PX` 占号，
  「值仍是本实例才续」的原子续期（每 TTL/3 一次）；号被夺或续期失败超过一个 TTL 即回调丢失，进程停止接客。
- `NodeIdLease.isValid()` = 未丢失 **且** 距最近一次成功续期不足 2/3 TTL（单调时钟，时刻取续期命令**发出前**）。
  Redis 端键至少存活到「该时刻 + TTL」，所以 `isValid()` 为真时立即用号，号一定仍归本实例；余下 1/3 TTL（5s）
  吸收判定到用号之间的停顿与时钟速率偏差。发号方（xm-login 的 `PlayerIdGenerator`）每次发号前检查它，
  为假即拒绝发号（续期恢复后自动恢复）。
- **防护代次**：每次占到号时 `INCR xm:node-id-epoch:{type}:{zone}:{id}`（不过期，严格递增），`NodeIdLease.leaseEpoch()`。
  gate 把它放进 `LinkHello`（进 MAC），scene 拒绝代次更低的链路（§4.2）。领不到代次就放弃这个号并启动失败。
- **gate 丢了租约**（fail-closed）：停止接客、停止上报目录、不再新建 scene 链路，并关闭全部现有会话（会话号高位就是这个节点号，
  新持有者会发出同样的会话号）；各会话照常走断线流程，经仍然就绪的旧链路让 scene 放掉玩家（写回）并通知 login。
- 节点类型名（`gate` / `scene` / `login` / `battle` 等，Redis 键段）只有一个出处：`xm-discovery` 的 `NodeTypes`。
- **gate 排空**（计划内缩容不卡玩家，同基线 gatedrain）：运维经 xm-data `POST /admin/gates/drain` 给 gate 的**当前实例**打排空标记
  （`xm:gate-draining:{zone}:{node}`，值 `Redis 服务器时间秒:实例 id`，必须带 TTL——打标记的人挂了容量不会永久蒸发；TTL 须长于
  gateway 的 deadline、至多 86400 s；打上之后本区没有接客的 gate 时不带 `force` 拒，这项检查与写在同一段 Lua 里）；
  xm-gateway 读 gate 目录时一次 MGET 叠加标记（只认同实例的，读不到按没人在排空放行），`GatePicker` 剔除排空中的、全部排空时忽略标记；
  每个 gateway 每 5 s 跑一轮判定（时间取 Redis 的 TIME）：在线 ≤ 阈值（0）或从打标记起等满 deadline（25 min）就写
  `xm:gate-drained:{zone}:{node}`（理由 below_threshold / deadline，TTL 不超过排空标记、只在标记仍是判定时那一份时写），
  不再满足时撤掉 drained，没在排空的清掉残留，旧实例留下的标记（节点号已被新实例复用）比较并删除。**只自动化判定、不踢人**：
  剩下的玩家靠「实例下线 → 重连 → assign-gate 已剔除它」改派；何时下线由运维看 drained 决定，下线后
  `DELETE /admin/gates/drain/{zone}/{node}` 清标记。
- **GM 签名停机**（同基线 Gate / Scene.GmGracefulShutdown）：gate / scene 管理端口上的 `GET /gm/identity`（回区、节点号、实例 id、
  方法名）与 `POST /gm/graceful-shutdown`，**只收本机来的请求**（`XM_GM_ALLOW_REMOTE=true` 才放开）。签名信封与原因全在请求头里
  （`X-Xm-Gm-Operator / Timestamp / Nonce / Signature / Reason`，原因百分号编码、至多 256 字符，没有请求体），HMAC-SHA256 签 canonical
  `方法名\n区:节点号:实例\n操作人\n时间戳\nnonce\n原因`（`GmRequestAuth` + `GmShutdownHandler`：密钥只从 `XM_GM_ADMIN_SECRET` 读、
  没配一律拒；时间窗 300 s；nonce 2 × 窗口内去重、表满即拒；绑区与实例——Java 节点号按区分配、重启后复用，只绑节点号的签名能拿去停
  别的区同号节点或重启后的新进程）。通过回 `affected_count`（gate 会话数 / scene 在线人数），应答写出后走正常停机
  （gate：停接客 → 关会话；scene：摘目录 → 断链 → 写回全部玩家）；拒绝只打 ERROR、回 403 不带原因。签名工具
  `java tools/GmShutdown.java --url --operator [--reason] [--expect-node]`（先取身份再签）。
- Redis 客户端超时（`xm.redis.connect-timeout-ms` / `timeout-ms` / `retry-attempts` / `retry-delay-ms`，
  默认 2000 / 2000 / 1 / 200）：单条命令最坏阻塞 = (重试 + 1) × 响应超时 + 重试 × 间隔 = 4.2s，
  低于客户端 HTTP 超时 5s（Redisson 自带默认值下可达二十多秒）。超时的命令会被原样重发一次（第一次可能已经执行），
  所以可变的 Lua 都要按「可被重放」设计（回合制战斗的脚本逐段写明了重放语义，§4.23）；等一条 Redis 脚本结局的 RPC，调用方超时要大于 4.2 s
  （`xm.battle.scene-rpc-timeout` 缺省 5 s，启动时与 `RedisProperties.worstCaseCommandMillis()` 比较）。
- **Redis 的部署约束**（回合制战斗落地后新增，批次 6.3）：
  - **现在是单机 Redis；上主从 / 集群之前有一件事必须先做**。驱动丢弃与判废的读（`BattleRedis` 的全部脚本）一律读主库；但 `BattleLockReader.exists / existsAll`
    是咨询性的读，走 Redisson 的普通读路由，主从部署下可能读到从库的旧值。队伍视图、组队跟随、xm-match 的排队 / 切磋 / 凑单校验 / 成员预检读到旧值只是显示或排队判断晚一拍
    （读到「没有锁」而实际有锁时，该玩家在开局的备战一步被 scene 拒绝，不会串局）；
    **xm-data 的回档闸（§4.5.1）是安全门**，读到从库的「没有锁」会放行一次本该拒绝的回档。二选一：把 Redisson 的 `readMode` 设成 `MASTER`，或给回档闸一个读主库的批量入口。
    同类的还有 battle 发件箱定位用的位置读（`PlayerLocationDirectory.findHolderAsync`，只读脚本）：读到过期位置最坏是多一轮 NOT_HERE 或无目标，下一轮纠正；
    全仓其余以只读方式执行的读届时一并审查。
  - 战斗锁、待结算记录、已销账墓碑三个键带同一个 `{<pid>}` hash tag，销账 / 取代 / 落库的跨键脚本在 Cluster 下同槽，不用改。
  - xm-scene、xm-battle、xm-team、xm-data、xm-match 必须指向**同一个 Redis 实例与库**（缺省都是 DB 12）：队伍视图与回档闸读的是各自进程的客户端，配成不同的库时读到的是空键空间，
    会把所有人当成不在战，没有自检。xm-match 另读在线目录、位置记录与 scene / battle 节点目录，同样要求同库。
  - Redis 要开 AOF（everysec；本地编排的 `infra.yaml` 已带 `--appendonly yes`）：实例丢数据会丢掉在途的结算记录与战斗锁，生产值随 7.6 的部署文档。
- **匹配的部署约束**（批次 6.4，§4.24）：
  - **读路由**：xm-match 的全部脚本（含只读的；6.5 的 11 段观战脚本同此）读主库；走普通读路由的只有凑单读注册集（`SMEMBERS`）与队列长度（`LLEN`）两处，以及 6.5 清扫采样可观战索引大小的 `ZCARD`（只进 gauge），主从部署下读到旧值的后果是凑单晚一轮、队列深度的 gauge 不实时，
    与上面战斗锁的咨询性读一起，在上主从 / 集群之前审查。全部 match 键共用 hash tag `{match}`，多键脚本在 Cluster 下同槽，但这也意味着匹配只落在一个分片上（写 QPS 很低，可以接受）。要求 Redis ≥ 7。
  - **后端重启后约 60 s 才重连上**（已知遗留，调参留到 7.6 与全部后端一起定）：Dubbo 3.3.6 的 Triple 客户端首次建连没连上、或断线后 1 s 的那一次重连落空之后，下一次重连排在约 60 s 之后
    （`dubbo.application.least-reconnect-duration` 缺省 60 s；xm-gate 的 `BackendReconnectTest` 用真 Triple 钉住现状）；gate 的七个后端引用与 xm-team → xm-match 的引用都没有调这个参数。运行中单独重启某个后端（含 xm-match 丢租约后的重启）时，
    后端回来之后约一分钟内 gate 对它的号回信封 1003、211 回 4030。本机切片按「提供方先于调用方」的次序启动（xm-match 排在 xm-team 与 xm-gate 之前）；运行中重启要么等一分钟，
    要么把调用方一起重启。实测把 `heartbeat` 与 `least-reconnect-duration` 都设成 1000 后 2–4 s 恢复，代价是空闲连接每秒一次 PING、后端长停顿时在途请求会被掐断。
    按节点目录直连的 `NodeRpcClients`（battle → scene、match → scene / battle）不受这条影响，它们的重连间隔已经压到 1 s。
  - **发号租约丢失要靠重启**：xm-match 真正丢了 `NodeTypes.MATCH` 租约不会自愈，`/actuator/health` 报 DOWN，编排层据此重启；没有编排层（本机切片）要手工重启。
  - **对局结果 topic**：xm-battle 与 xm-match 的代次（`XM_BATTLE_RESULT_TOPIC_GENERATION`）必须一致，不一致时结果发进没人消费的 topic、评分静默不更新；topic 由先启动的一方创建，
    副本数取它自己的配置（两边缺省都是 1，部署时配成相同）；分区数是契约，要改只能升代次、两个进程一起改。Kafka 不可达不拦这两个进程启动（各多等一个 `init-timeout`）。
  - **停机宽限**：xm-match 最坏停机时长约为 Dubbo 停服等待（缺省 10 s）+ 工作池排空 + 在途开局的 10 s，可能超过本机切片给每个进程的 20 s 而被强制结束；后果自愈
    （票据按 matched TTL 过期、scene 按备战期限解冻、租约按 TTL 过期）。生产的宽限期随 7.6。
    批次 6.5 加了观战之后，`MatchLifecycle.stop()` 里「排空工作池 → 停观战清扫 → 等在途开局」这几步的**设计预算仍是 20 s**，与 6.4 相同：等在途 163（≤ 5 s）与排空（≤ 10 s）并行，清扫当场中断（§4.24.1）。
    这个 20 s 与 `spring.lifecycle.timeout-per-shutdown-phase` 取值相同，但 Spring 不会拿它截断同步的 `stop()`；硬期限在进程外、从 SIGTERM 起算，还包含停凑单与撤 Dubbo 导出——本机切片是 `stop-slice.sh` 的 20 s，
    容器是按这个值推出来的宽限期（ops-release-spec §0.6 #30：5 + 20 + 10 = 35 s）。这几步守住 20 s，那条公式才继续成立。
  - **观战的多副本口径**（批次 6.5）：`xm_match_watchable_battles` 是每个实例各自采样同一把全服 ZSET，看板与告警取 `max`、不能求和；`xm_match_spectate_inflight` 是每实例的真实在途数，求和才有意义；清扫器每个实例各跑各的，幂等、不抢锁。
- **本机双 zone 切片**（批次 6.5，`tools/local/start-slice.sh`；这一块按 zone-travel-spec §5.13 提前落地，不含 5.4 的任何生产代码）：`XM_ZONES`（1 / 2，缺省 1，只有 `start-slice.sh` 读）= 2 时多起两个实例——
  `xm-scene-z2`（`--xm.zone-id=2`，链路 21010、资产通道 21110、管理端口 18115，排在区 1 的场景节点之后）与 `xm-gate-z2`（`--xm.zone-id=2`，客户端 11010、管理端口 18123，排在 xm-gate 之后），其余进程两个区共用；
  单 zone 13 个进程、双 zone 15 个，可与 `XM_SCENE_NODES=2` 同用（16 个）。`xm-gate-z2` 的 Dubbo 后端地址用缺省值，与区 1 的 gate 指向同一个 xm-match。节点号按 zone 租约，**区 2 的 scene / gate 也是 1 号**——与区 1 同号是有意的，
  只按节点号寻址的代码在这个形态下会暴露（§4.24.1「跨区 1V1」）。区 2 在区服目录（`zone_config`）里的那一行由脚本经 xm-data 的运维接口管，**状态跟随 `XM_ZONES`**：=2 时置 OPEN（库里还没有就建出来），=1 时置维护（库里没有就什么都不做）——
  区 2 建过一次就留在库里，不这样做的话单 zone 切片上会留下一个没有 gate 的 OPEN 区；脚本等 `GET /api/server-list` 反映出来（=2 时要区 2 是 OPEN 并且带负载档，说明 gateway 的健康探测已看到它的 gate）才报「全部就绪」。
  xm-gateway 的命令行两种形态下都不变。`stop-slice.sh` 不读 `XM_ZONES`、只看 PID 文件，区 2 的两个实例紧挨区 1 的同类先停；故障变体脚本 `battle-crash-window.sh` 只在区 1 上做。
  整栈（compose）的两 zone 形态与双 zone 进 CI 留给 7.1b。

### 6.1 本地编排、镜像与 CI（批次 7.1）

规格 `docs/porting/deploy-ci-spec.md`。本机没有 Docker：compose 文件与镜像只在 CI 上执行，结论以 CI 为准（读法见规格 §11.5）。

- **依赖编排**（7.1a）：`deploy/compose/infra.yaml` 只起依赖：MySQL 8.4.11、Redis 8.10.2（AOF）、Kafka 4.3.1（KRaft 单节点），都钉 tag 加 index digest。
  项目名 `xuanming-java`，不与基线 compose 混用；端口只发布到 127.0.0.1，缺省端口 = 各进程的缺省端口，所以起好之后照常用
  `tools/local/start-slice.sh` 在宿主上起 Java 进程，端口冲突时用 `XM_*_HOST_PORT` 改开。Kafka 有两个监听：宿主连 `127.0.0.1:9092`，容器内连 `kafka:19092`。
  关掉自动建 topic，审计 topic 由进程自建并核对（§4.5）。MySQL 预建 `xm_java`，服务器缺省排序规则 `utf8mb4_bin`、时区 `+00:00`（与开发机相同）。
  秘密只从环境变量或不入库的 `deploy/compose/.env` 读（模板 `env.example`），必填项写成 `${VAR:?}`，缺了 compose 在启动前就失败。
  重置时 MySQL 与 Redis 必须一起清（`down -v`）：单独清 Redis 会让 `xm:node-id-epoch:*` 回退（§6「防护代次」），也会让只存在 Redis 里的权威数据（组队等）与 MySQL 对不上。
- **镜像**（7.1a 落了 Dockerfile，首次构建随 7.1b）：一个参数化的 `deploy/docker/Dockerfile` 服务全部进程模块（`--build-arg MODULE=<模块>`）。
  jar 由宿主 Maven 一次打齐，镜像里只用 Spring Boot 的 `jarmode=tools` 分层解包。基础镜像 Temurin 21 JRE（Ubuntu noble）钉 digest；
  以非 root 运行（uid / gid 10001），home 是可写的 `/home/xm`（Dubbo 往 `${user.home}/.dubbo` 写缓存）。表数据单独一层烤进镜像
  （`xm.table-dir` 的缺省值相对 `/app`，运行时照样校验 manifest）。`JDK_JAVA_OPTIONS` 缺省按容器内存比例给堆，并用 G1、OOM 即退出。
  不写 `HEALTHCHECK`，由编排层负责。tag 用 `<sha12>`，从不打 `latest`；推送 registry 归 7.6。
- **构建信息**（7.1a）：根 pom 的 `build-info` 生成 `META-INF/build-info.properties`，内容是 `build.version` / `build.time`、40 位 `build.commit`
  与契约来源 `build.contract`。后两项由 CI 与镜像构建用 `-Dxm.build.commit` / `-Dxm.build.contract` 传入，本机构建是 `unknown`。
  镜像另有 OCI label 与 `/app/BUILD_INFO`。经 `/actuator/info` 暴露随 7.1b（§11）。
- **CI**（7.1a，GitHub Actions，`.github/workflows/`）：
  - `ci.yml`：构建 + 单测，加契约 `ContractSync --check`。
  - `integration.yml`：用 `infra.yaml` 起依赖跑 `-Dxm.it.*` 全量 verify，再用 `tools/TestReport.java --require-it-executed` 证明集成测试真的执行了（防止标志传错、全部静默跳过还是绿）。
  - `contract-drift.yml`：每天对比 mmorpg main，只报告，不判红。

  共同约定：runner 钉 ubuntu-24.04；权限只读；不用任何 repository secret（MySQL 口令每次随机生成）；官方 action 按 SHA 钉死。
  job 日志匿名读不到，所以每一种失败都转成 annotation（`::error`，`.github/maven-problem-matcher.json` 负责编译错误）。
- **7.1b 待做**：
  - `deploy/compose/stack.yaml`：include infra，加上全部 Java 进程，作为「容器」部署形态。
  - 各进程 `application.yaml`：加地址占位符（JDBC 主机端口、`XM_REDIS_ADDRESS`、`XM_ADVERTISE_HOST`、`XM_SCENE_LINK_BIND_HOST`、Dubbo 直连 URL），
    打开 readiness / liveness 探针，暴露 `info`。
  - CI 的 stack job。
  - 通告地址与 Dubbo 绑定规则：规格 §2.6 认为通告地址设成非回环主机名后 Dubbo 只绑那个 IP，要在本机验证。
  - 停机宽限期（scene 45 s）。

  后两项验证后写进本节。从 7.1b 起，新增进程模块的批次要同批登记 `stack.yaml`、`start-slice.sh` 与 `env.example`。
  批次 6.4 的 xm-match 已登记后两处（`start-slice.sh` 里排在 xm-chat 之后、xm-team 之前，`stop-slice.sh` 最先停它；本机切片共 13 个服务进程，双 scene 时 14 个），`stack.yaml` 落地时一并补上。
  批次 6.5 没有新增进程模块；`start-slice.sh` 多了 `XM_ZONES=2`（多起区 2 的 `xm-scene-z2` / `xm-gate-z2`，共 15 个进程，见 §6「本机双 zone 切片」），`stop-slice.sh`、`battle-crash-window.sh` 与 `env.example` 的说明同步；
  区 2 的五个端口只由脚本经命令行参数传入，xm-robot 的 `SliceScriptsTest` 把各模块 `application*.yaml` 的缺省端口纳入它们的冲突检查——新模块的缺省端口撞上时测试会红。整栈的两 zone 形态（覆盖文件、gateway 两个区一起播种）随 7.1b。

## 7. 存储

- MySQL 库 `xm_java`（与 mmorpg 的库隔离）：`account`、`player`、`player_state`（各玩法的持久化数据，protobuf `xm.storage.PlayerState`，与 `player` 行同事务、同围栏写入）；建表脚本在 `xm-player-store/src/main/resources/db/xm-player-schema.sql`
  （只 `CREATE TABLE IF NOT EXISTS`，存量库的结构变更按 [db-migrations.md](db-migrations.md) 手工迁移）。
- **区服目录 / 白名单 / 登录公告**（`zone_config`、`zone_whitelist`、`announcement`，同库）：建表脚本在
  `xm-gateway-store/src/main/resources/db/xm-gateway-schema.sql`，由 xm-gateway 启动时执行并按 `xm.gateway.seed-zones` 播种库里没有的区；
  运维经 xm-data 的 `/admin/zones`、`/admin/whitelist`、`/admin/announcements` 改（令牌 + 操作人 + 审计，管理端口缺省只绑本机）。
  xm-gateway 读：区服准入、HTTP 登录、区服列表走 `ZoneDirectory`（整表快照、1 s 过期，读失败也缓存 1 s，准入 fail-closed），
  区服列表的状态再叠加 `ZoneHealthProbe`（每 5 s 读 Redis 节点目录：无 gate DOWN、无 scene DEGRADED，负载档 = 在线 / capacity，
  快照 15 s 过期变 UNKNOWN）。时刻一律整数（开放 / 公告时刻 Unix 秒，创建 / 更新 Unix 毫秒）。
- 玩家名全服唯一且大小写 / 全半角不敏感：唯一索引 `uk_player_name_key` 建在 `name_key` 上，键只由 `PlayerStore.nameKey` 计算
  （NFKC → 去首尾空白 → `Locale.ROOT` 小写）。建角撞到主键（`player_id` 重号）是不变量被破坏，抛异常，不报「重名」。
- **proto 声明的表（xm-pbmysql）**：行本身就是一条 protobuf message 的表（好友、邮件、帮会等社交服务，xm-data 的运维作业表（§4.5），以及 xm-match 的评分两张表
  `match_rating` / `match_rating_applied`（批次 6.4，§4.24；Java 自有 proto `xm/match/match_tables.proto`，db-migrations M11）；
  mmorpg `proto/db`、`proto/friend/friend_table.proto`、`proto/guild/guild_db.proto` 里带 `OptionTableName` 的消息）
  不手写 DDL 与 Mapper，用 `com.game.pbmysql.PbMysql`（用户自有库 proto2mysql 的 Java 实现，对齐 Go v0.2.0）：
  1. **声明**：表名 / 主键 / 索引 / 唯一键 / 自增 / TiDB 选项写在 message option 上（本模块的 `proto2mysql/proto2mysql_option.proto`
     或 mmorpg 的 `proto/db/proto_option.proto`，按扩展字段号读，两套同号）；必要时注册时传 `TableOption` 覆盖。
     每个字段一列、列注释 `pb:N` 记字段号；标量按类型落列，子消息 / repeated / map 落 MEDIUMBLOB（wire 裸字节），
     Timestamp 落 `DATETIME(6)`（UTC、可空）；主键 / 唯一键里的 string / bytes 是 `VARCHAR(N) utf8mb4_0900_bin` / `VARBINARY(N)` 整列
     （默认 N = 191，`max_length` 可调）。真实 oneof、sint / fixed 类型、浮点主键、repeated Timestamp 在注册时就拒绝。
  2. **建表 / 升级**：服务启动时在一条自动提交的连接上 `syncAll`：表不存在就建；存在则**只扩不缩**——只 ADD 缺的列、索引、唯一键
     （以及线上完全没有的主键），从不 MODIFY / CHANGE / DROP。线上列比 proto 窄、缺 `pb:N` 注释、NULL / DEFAULT / 自增属性不同、
     同名索引或主键定义不同、按字段号认出的改名都抛 `SchemaDriftException`（列出差异与对齐所需语句，本表不执行任何 DDL），
     服务应当起不来、由人工迁移。整轮持 MySQL 咨询锁，多副本同时冷启动不会互相撞 DDL。字段号永不复用（删字段写 `reserved`），
     改名 / 改类型走 expand→migrate→contract。滚动发布期间旧副本重启只会看到「线上多出来的列 / 索引」，不会回退。
  3. **读写**：所有方法都接收调用方的 `Connection`，事务边界由业务代码掌握（同一事务里改多行、`findOneByPkForUpdate` 锁行）。
     语句全部显式列出列、参数绑定；读回按列名取值。`save` 是整行保存（备用唯一键撞上别的行时抛 `DuplicateKeyException`
     而不是改那一行），`upsert` 是单语句 ODKU（撞上别的行时静默不改），`insertIgnore` 只把 1062 解释成「已存在」。
     `updateByPk` 写整行（零值照写，与 Go 版只写已赋值字段的 `Update` 不同），只改部分列用 `updateFieldsByPk`。
  4. **注意**：`where` 参数是裸 SQL，取值一律走参数；uint64 / uint32 的大值（Java long / int 为负）作 `where` 参数时要用 `PbMysql.uint64(..)` / `PbMysql.uint32(..)` 换成无符号值
     （`findAllByKvIn` 知道列类型，自动换）。proto3 `optional` 的 presence 不落库（读回的 0 带 presence，与 Go 版相同）。
     阻塞 I/O：只能在存储线程池里调，不得在 Netty I/O 线程或场景逻辑线程上调（AGENTS.md §3 线程所有权）。
  `player` / `player_state`（归属围栏、按玩法拼装的状态载体）仍走 MyBatis + 手写脚本，不迁到 xm-pbmysql。
- **`player_state` 的分段**（`xm.storage.PlayerState`，每个玩法一个字段，字段号永不复用）：1 朝向、2 货币、3 属性加点、4 背包、5 任务、6 当前气血法力、7 宝宝、
  8 资产通道账本 `asset_ledger`（§4.12）、9 回合制战斗结算账本 `battle_ledger`（批次 6.3，§4.23：`BattleLedgerState{applied[]}`，每条 `{battle_id, applied_at_ms}`，
  按 battle_id 无符号升序写出；只登记「已应用、销账未确认」的局，稳态 0～1 条，上限 64）。两本账本都与资产在同一份记录里、随同一次带 `owner_epoch` 围栏的写落库——
  「这条指令 / 这一局已应用」与资产同生共死；各层不认识的字段原样带回，滚动升级或回滚期间旧版本不会把新段抹掉。加一段只登记字段号、不改表结构（db-migrations M10）。
  GM 回档把两本账本与货币 / 背包 / 宝宝一起算作不可拆的资产组（§4.5.1）。
- Redis：全部键带前缀 `xm:`，默认 DB 12（与 mmorpg 的开发数据隔离），键名只经 `com.game.discovery.RedisKeys` 生成；各类键在用到它的小节里说明。
  回合制战斗的四类键——战斗锁 `xm:battle:{<pid>}:lock`、待结算记录 `xm:battle:{<pid>}:settlement`、已销账墓碑 `xm:battle:{<pid>}:settled:<battle_id>`、
  活动结果副本 `xm:battle:activity-result:<battle_id>`——见 §4.23 的键表，部署约束见 §6。
  匹配的键（批次 6.4）全部以 `xm:{match}:` 开头、共用一个 hash tag——队列注册集 `index`、队列 `queue:<mode>:<config>`、评分镜像 `rank:…`、凑单锁 `lock:…`、票据 `ticket:<pid>`、
  弹组 / 回队首的重放标记 `pop:<token>` / `requeue:<token>`、切磋的 `challenge:<id>` / `challenge-target:<pid>` / `challenge-done:<id>`、战斗落点记录 `battle:<battle_id>`——见 §4.24 的键表；
  只有 xm-match 写它们，排队与票据的权威数据只在 Redis（评分在 MySQL）。
  观战的两类键（批次 6.5）同在这个 tag 下：观战标记 `xm:{match}:watching:<pid>`（STRING，360 s）与可观战索引 `xm:{match}:watchable`（ZSET，无 TTL，按分数判过期）——见 §4.24 的键表与 §4.24.1；
  它们只是提示与索引，丢了不影响战斗本身（标记丢了的后果是开局清退摘不到这名观众，索引丢了的后果是在打的场次不在列表里）。
- **每账号角色上限**（默认 5）由数据库保证：`PlayerStore.createPlayerWithinCap` 在一个事务里先 `SELECT ... FOR UPDATE`
  锁账号行（主键记录锁，无间隙锁）、再数角色、再逐个候选名插入。锁住之后才建立一致性读快照，所以一定看得到上一个持锁者
  已提交的插入；多个 login 实例并发建角也突破不了上限。每个事务只锁自己账号的一行，没有跨账号锁序，不引入死锁。
  login 进程内的 `accountsInFlight` 只用于快速回 2005。
- **玩家数据归属协议**（`PlayerStore`，唯一出处；列 `owner_epoch` / `owner_released` / `owner_lease_until`）：
  1. **夺权**（login 进游戏，分配到场景之后）：只有上一个写者**已释放**（`owner_released = 1`，最终写回已落库）或它的
     **租约已过期**（`owner_lease_until < now`）时，`owner_epoch` 加一、`owner_released = 0`、租约 = 现在 + 30s；
     否则返回「仍被持有」。拿到新 epoch 的 scene 实例是该玩家数据的唯一写者，加载到的一定是上一个写者写回后的状态。
     不变量的一般形式是**「归属持有者是唯一写者」**：持有者通常是 scene 实例，批次 7.2b 起也可以是 xm-data 的运维作业（第 7 步）。
  2. **仍被持有**：login 经 Redis pub/sub（`xm:owner-takeover`，消息 `xm.api.OwnerTakeover`）请持有者让出，
     在 `xm.login.owner-claim-wait`（默认 3s）内退避重试（100ms 起翻倍、封顶 800ms，每次重试都重发请求）；
     等不到回 2005 kLoginInProgress。持有该 epoch 的 scene 实例写回并释放、给旧会话发 `PlayerKicked`（23 {2017} 后断开）；
     还在加载中的进场取消并释放。这就是 Java 版的顶号，也覆盖了「LeaveGame 后立即 EnterGame」「断线后立即重连」时
     上一次离场写回还没落库的情形。
  3. **续约**：scene 每 10s（租约的 1/3）把内存里持有的归属批量续到现在 + 30s；续不上的（epoch 已被夺走、已释放）
     立即移除实例（不写回——只会被围栏拒绝）并踢掉会话。scene 死掉或与库失联超过 30s，别的会话可以强制夺权，
     代价是它自上次离场写回以来的增量丢失（首批只在离场时写回）。
  4. **释放**：玩家离开（主动 / 断线 / 链路断开 / 停服 / 被接管）一律「最终写回并释放」（带 epoch 围栏）；
     没进成的进场（scene 拒绝、加载中离开、链路断开时还在加载、被同会话的新进场取代）scene 只释放；
     `PlayerEnter` 从未写上链路的（gate 会话已在关闭、链路层已关、建链失败）gate 调 `abandonEnter` 由 login 释放。
     都带 epoch 围栏：旧写者碰不到新 epoch。唯一只能等租约过期的是「gate 进程在收到 login 应答后、发出 PlayerEnter 前崩溃」
     与「PlayerEnter 已写出但链路随即断开、scene 没收到」。
  5. 租约比较用各进程的墙钟（login 判过期、scene 续约）：两端时钟偏差必须远小于 30s（部署要求 NTP）。
  6. **交出**（跨节点换图，批次 5.2，§8.1；`PlayerStore.handOffOwnership`）：持有 E 的 scene 用**一笔事务**同时写回冻结快照、把归属交给下一代——
     `UPDATE player SET <快照>, owner_epoch = E+1, owner_released = 0, owner_lease_until = L_i
     WHERE player_id = ? AND owner_epoch = E AND owner_released = 0 AND owner_lease_until >= now_i + M`，同事务 upsert `player_state`
     （`saved_epoch = E`）。提交即证明源节点的最终状态已落库、从此只有 E+1 的持有者能写：旧 epoch 的迟到写（在线存盘、写回、续约、只释放）
     全被前面的围栏拒掉。E+1 在目标节点交出进场之前无人持有、也没人续约（gap）；目标节点加载时库里不是 E+1 就拒绝进场。
     - **安全边际 M**（`xm.scene.transfer-lease-margin`，缺省 15 s；启动校验 续约周期 10 s < M < 30 s − 10 s）：每次尝试开始时 E 至少还剩 M 的租约，
       冻结中照常续约，所以从第一次尝试起 M 之内不可能有人夺到 E 的下一代。剩余不足回 `LeaseTooShort`（什么都没改，原地解冻、推 23 {3023}）。
     - **租约值作本次标识**：每次尝试现取 `now_i`，写 `L_i = now_i + 30 s` 并记下。存储层重试时得到「epoch 不是 E」而行是
       (E+1, 未释放, 租约 ∈ 之前某次的 L_j) 就改判为自己的提交（第一次已提交、应答丢了）；与本次的 L_i 相同或是别的值都不改判。
     - **结局不明时探测**（重试用尽、非瞬时故障、线程池拒绝；`PlayerStore.probeOwnership`）：`SELECT owner_epoch, owner_released, owner_lease_until … FOR UPDATE`
       加锁读——它会等仍持有行锁的在途交出事务结束，所以读到 (E, 未释放) 时那笔一定已回滚、不会再提交。(E, 未释放) → 没提交（原地解冻）；
       (E+1, 未释放, 租约 ∈ {L_i}) → 已交出；别人的租约、已释放、玩家不在，或到截止（第一次尝试 + M − 2 s）仍判定不了 → 失去（fail-closed：
       移除实例、不写回、推 23 {3023} 后断开）。读失败在截止前退避重试（封顶 1 s），至少读一次；交出任务根本没执行（被池拒）时不读库、直接判没提交。
     - **时限**：交出事务（不含提交）与探测读都受 `xm.scene.transfer-probe-statement-timeout`（缺省 3 s，≥ 1 s 且小于 M）约束，经 Spring 事务超时逐次设置；
       行锁被占时尝试以超时失败（`QueryTimeoutException` 或 `TransactionTimedOutException`，事务已回滚），按瞬时故障重试 / 交给探测，而不是在冻结里一直等。
     - **冻结中的归属事件**：续约报失去 E、冻结前就在途的在线存盘回来被围栏拒，都不踢人（提交之后 E 当然续不上），由交出结局裁决；
       被请求让出 E（顶号）只记下，结局出来后处理。
     - **新 epoch 的租约** 30 s（同夺权）；目标节点交出进场成功后立即单独续约一次（`OwnerLeaseRenewer.renewSoon`），把余量拉回 20 s 以上。
     - **E+1 的释放方只有三个且互斥**：源节点（只在确知 `PlayerTransfer` 没发出时：冻结中会话离开 / 被顶号、实例已被停服写回等路径移出、
       链路已断或写失败、结局送不到逻辑线程）、gate 的 `abandonEnter`（只在确知 `PlayerEnter{E+1}` 没发出、或改绑指令对不上绑定 / 内容非法时）、目标节点（进场失败 / 离场）。
     - **残余**（都只能等 E+1 的租约过期，至多 30 s；库里是冻结快照，数据不丢）：
       R-J1 E+1 已提交、`PlayerTransfer` 已写进 socket，但 gate 进程随即崩溃（与第 4 步「PlayerEnter 已写出但链路随即断开」同级）；
       停服时在途交出 / 探测的结局在停服预算内没处理完（记 ERROR，§5「scene 停服」），或 `PlayerTransfer` 的异步写失败回调在存储线程池关闭之后才到（极少见）。
       R-J2 探测的锁等待一直超时（库端事务因网络分区迟迟不结束）→ 判失去、断开，可能误判一笔其实会回滚的交出（fail-closed，代价是玩家重登）。
       R-J3 gap 期间位置记录仍指向源节点（见下「玩家位置与短线重连」），资产通道多一次 NOT_HERE 重投。
       另外 M 与第 5 步同一前提：各进程墙钟偏差须远小于 M，启动校验只能约束配置、不能约束时钟。
  7. **运维夺权**（批次 7.2b，§4.5.1；`com.game.data.ops.fence.AdminOwnership`）：xm-data 的回档作业以普通写者的身份走同一套协议，不另立栅栏。
     - **取得**：调同一个 `PlayerStore.claimOwnership`（在 xm-data 自己的事务模板里），条件仍是「已释放或租约过期」，所以在线的、交出在途的（E+1 被持有，第 6 步）都夺不到；
       与第 6 步的交出互斥：交出事务要求 E 持有、未释放、租约够长，运维夺到之后源节点迟到的交出被围栏拒绝。
     - **在线玩家**：缺省不动（结果 `player_online`）；运维显式要求时发与第 2 步相同的让出请求，持有者照顶号处理（写回、释放、23 {2017}）。
       xm-data **不订阅**让出频道：运维持有期间玩家登录，login 发的让出请求没人处理，3 s 后回 2005，客户端稍后重试——这是运维夺权唯一新增的客户端可见面。
     - **持有与写**：每 10 s 续约（同第 3 步）；写玩家数据的事务开头加锁读归属，确认仍是自己的 epoch 且未释放才写（`saveStateHeld`，带围栏）。续不上的玩家之后不再写。
       xm-data 的 `PlayerStore` 用严格递增的毫秒时钟（`StrictClock`）：它的连接串带 `useAffectedRows=true`，同一毫秒内列值没变的写会数出 0 行、被误判为失去围栏。
     - **释放**：结果审计（RESULT）之后才释放；释放前先写位置墓碑（见下「玩家位置与短线重连」），再 `releaseOwnership`（带围栏）。释放失败等租约过期（≤ 30 s）。
       进程崩溃同样由租约兜底；每名玩家的写是单事务，不会半写。
- **写回失败**：scene 对可恢复的瞬时故障（取不到连接、连接断开、锁等待 / 查询超时）在 5s 预算内退避重试最多 3 次；
  最终失败、线程池拒绝、停服丢弃都记 ERROR（带 player_id / epoch / 场景 / 坐标，供人工修复）并计数。
  scene 的 JDBC URL 带 `connectTimeout=3000&socketTimeout=10000`（与 login 同口径），库卡死时存储线程不会被无限挂住。
  **在线周期存盘**（`xm.scene.save-interval`，缺省 300s，同基线）：每秒一个槽，`player_id` 对周期取模等于槽号的玩家到期，
  与上次确认落库的快照相同就跳过，不同才写（带围栏、不释放；要求归属未释放，所以迟到的在线存盘盖不过最终写回）。
  进程被 kill 时丢的是最近一次在线存盘之后的增量。最终写回失败（重试用尽）时盘上至少是最近一次在线存盘的状态。

- **玩家位置与短线重连**（`PlayerLocationDirectory`，Redis `xm:location:{player_id}`）：归属持有者是唯一写者（平时是持有归属的 scene；
  批次 7.2b 起运维夺权的 xm-data 在释放前也写一次，见本段末），
  每次写带本次进场内单调递增的序号与完整的此刻状态，按 (epoch, 序号) 只收更新的写（Redisson 的命令会乱序）——进场 / 换场景写当前场景实例
  （TTL 60 s，在线每 20 s 一槽续期）、断线（连接断开、gate 链路断开）写成 30 s 重连租约、LeaveGame 写成登出墓碑；被接管 / 失去归属 / 停服不动（新持有者覆盖或按 TTL 消失）。写都是异步的，不阻塞逻辑线程，
  写不上只退化成按首登落点。login 进游戏时读：有本 zone 的记录（在线顶号、断线租约内重连）就请 scene-manager 送回原实例
  （还在就不看人数直接用，不在了按原地图），没有（首登、干净登出、租约过期）落默认主世界（World 表第一行）；
  夺到归属后复查一次（LeaveGame 紧跟 EnterGame 时墓碑可能晚到）。gate 在 LeaveGame 在途 / 排队时断线也按主动离开通知 scene。
  基线在 zone 内只按 location 定 zone、落默认主世界，回原实例是 Java 的有意差异（PARITY「短线重连与落点」⑥）。
  重连不复用内存实例：断线照常最终写回并释放，重连按新 epoch 从库重载（与基线「退出存盘在途时取消退出」不同，见 PARITY）。
  **跨节点换图期间**（§8.1，归属 gap）：交出提交后源节点移除实例时不写位置，记录仍是源节点的 `o`(E)，直到目标节点交出进场写入 `o`(E+1)
  （新实例的序号从 1 起，epoch 更高直接覆盖）。gap 内重连按它回源场景——库里的冻结快照就是源场景的地图与坐标，结果一致；
  资产通道定位到源节点得 NOT_HERE、调用方重投，目标节点写入后自愈（R-J3）。源节点只在两种情形以 (E, 序号 + 1) 补写一次：
  冻结中会话离开（按是否主动写登出墓碑或重连租约），以及 `PlayerTransfer` 确知没发出（重连租约）；与 `PlayerTransfer` 在链路上交叉到达的
  `PlayerLeave` 靠交出墓碑（`xm.scene.transfer-tombstone-ttl`，缺省 30 s）同样补写一次。仍是「以自己写过的 epoch 写」，E+1 的写一定更新。
  不加「交出中」状态，也不写指向目标节点的提示（读者只有 login / 组队 / 资产通道三类，`o` / `l` / `x` 足够）。
  **运维夺权之后的墓碑**（批次 7.2b，§4.5.1）：被接管的 scene 不动位置记录，运维把在线玩家踢下线后会留下指向旧实例的 `o`(E)。xm-data 在释放归属之前以自己的 epoch
  写一次登出墓碑 `removeAsync(player, E', 1)`（这个 epoch 只有它写位置记录，序号从 1 起）；E' 更大，按 (epoch, 序号) 一定盖过旧记录，之后旧实例迟到的写被忽略。
  墓碑是异步的：作业收尾时并发发出、一起等至多 2 s，失败只告警并计 `xm_data_location_tombstones_total{result=error}`，由 TTL 60 s 兜底；续约已判失去的玩家不写墓碑。

## 8. 登录进场景调用链（首批竖切）

0. （可选，HTTP 登录路径）客户端 `POST /api/login` → `xm-gateway` → Dubbo `AccountLoginService` → `xm-login`：认证、取 / 建账号、
   签一对令牌、回角色列表（不绑会话、不查设备数）；令牌到期前 `POST /api/refresh-token` 轮换（不限流）。
1. 客户端 `POST /api/assign-gate` → `xm-gateway`：区服准入（§7）→ 按 zone 取 gate 列表，选在线人数最少者，签 `GateTokenPayload`（TTL 600s）。
   登录排队打开时（`xm.gateway.queue.enabled`）：队列空且快速通道原子占位成功才直接签，否则回 100 + 排队令牌，客户端轮询
   `POST /api/queue-status` 直到 0（带 gate 令牌）；放行由拿到 Redis 选主锁的那个 gateway 每秒按「容量 − 在线 − 未过期占位」从队头放（弹出与写放行槽同一段 Lua，按 50 个一段摊到各 gate；客户端取走放行槽后占位再留 15 s，等 gate 发布人数）。
   开服限流打开时（`xm.gateway.rate-limit.enabled`，`/api/login` 与不带排队令牌的 assign-gate，在区服准入之后）：分波未开放 → 100；
   IP 令牌桶（IPv6 按 /64）空 → 429 `IP_RATE_LIMIT`；区令牌桶空 → 100（`queue_source="ratelimit"`、`retry_after_ms`，客户端过一会
   原样重发；IP 令牌退回）；同一身份同一 IP 冷却中 → 429 `ACCOUNT_COOLDOWN`。两个桶一段 Lua 原子地判、时间取 Redis 的 TIME，
   冷却 `SET NX PX`（`xm:rl:*`，全部 gateway 共享）；Redis 出错放行，之后 5 s 内不再碰 Redis。
2. 客户端 TCP 连 gate，首包 `ClientTokenVerifyRequest` → gate 本地验签（常数时间比较）。
3. `Login(48)` → gate → `xm-login`：鉴权、账号锁、设备数上限（窗口内第 4 个连接 2024）、
   取 / 建账号、口令 / 三方登录签一对令牌（access token 登录不签），回角色列表；`ClientReply` 指示 gate 把账号绑到会话。
   鉴权按 `auth_type` 选（`LoginAuthenticator`，都在 login 工作线程上阻塞执行，任何失败回 2000）：
   - `""` / `password`：开发口令（`xm.login.mode=dev`，共享密钥 + 账号前缀）与生产口令（`xm.login.auth.password.enabled`，
     读 `account.password_hash` 的 Argon2id，KDF 并发槽限流、未知账号跑 dummy）二选一，同时打开拒绝启动，都没开 = 失败；
   - `access_token`：HTTP 登录或此前 TCP 登录签的 access token，账号取令牌里的；
   - `satoken` / `wechat` / `qq` / `netease`：`xm.login.auth.*` 配了才注册，`auth_token` 换账号（Sa-Token 读它自己的 Redis；
     微信 / QQ 调开放平台，5 s 超时）。秘密只从环境变量读。存量账号的口令哈希由离线工具 `PasswordAdmin` 写一次。
   设备数：同一账号「已绑定、没在游戏里」的连接至多 3 个（建角 / 进游戏前也续期，名单满回 2024），进场指令发出 / 会话结束时注销。
4. 角色为空时 `CreatePlayer(14)` → `xm-login`：按配表生成名字与默认职业，写 `player` 表，回角色列表。
5. `EnterGame(26)` → `xm-login`：会话已绑定玩家（不论是不是同一角色）→ 2028（基线进游戏成功即删登录会话）；
   校验角色归属 → 读玩家位置记录（§7）→ `SceneDirectoryService` 选场景（有记录回原实例，没有落默认主世界）→ 夺取归属（§7：上一个写者没释放就请它让出并等待，等不到回 2005）→
   回 `EnterGameResponse`，并指示 gate「把会话绑定到该玩家并送进 (场景节点, 场景)」。
6. gate 通过节点链路发 `PlayerEnter` → scene 加载玩家（存储线程池）→ 逻辑线程建玩家、进场景 →
   下发 `NotifyEnterScene(79)` 等初始同步。**进场失败**（scene 回 3023、建链失败、链路层已关）：gate 推 23 {3023}，
   会话回到「已登录、未进游戏」（玩家与场景绑定都清掉），客户端可在同一连接上重试 EnterGame（任一角色）或回选角建角。
   （只对登录进场；跨节点换图的交出进场失败是推 23 后断开，见 §8.1。）
7. 进场后的客户端消息（如 `ListSkills(77)`）→ gate 按消息号路由到玩家所在 scene 节点 → scene 逻辑线程处理并回包。
8. 离开游戏：`LeaveGame(17)` → `xm-login`：会话已绑定玩家时回会话指令 `UnbindPlayer`（不回包）→ gate 向玩家所在
   scene 发 `PlayerLeave{voluntary=true}`（scene 写回并释放归属、移除玩家），清掉会话上的玩家 / 场景绑定，**账号保留**，
   客户端可回选角再建角 / 进游戏。（与 mmorpg 的差异：mmorpg 的 LeaveGame 删除登录会话，之后 CreatePlayer / EnterGame
   回 2028 须重新 Login；robot 发完 LeaveGame 即断开，看不到这个差异。）紧接着的 EnterGame 若赶在离场写回落库之前，
   夺权会等它（§7 第 2 步），不会丢写回。
9. 断线：gate 通知 scene `PlayerLeave{voluntary=false}`（取进场时记下的玩家，不受关闭途中迟到的 UnbindPlayer 影响；
   scene 写回并释放、移除玩家）与 `xm-login`（`sessionClosed`）。
10. 顶号：同一角色从另一条连接 EnterGame → 夺权撞上仍被持有 → login 请持有者让出 → 旧实例写回释放、旧连接收到 23 {2017}
   后被关闭 → 新连接的夺权成功、正常进场。
11. 跨节点换图：`EnterScene(63)` 指定的 scene_id 在别的 scene 节点上 → 源节点选目标、冻结、交出归属 → gate 改绑 → 目标节点交出进场，见 §8.1。

### 8.1 跨节点换图与归属交接（批次 5.2）

mmorpg：scene 请 scene_manager `EnterScene` 两跳——第一跳回私有码 18（换手门）→ 源节点冻结、存盘、写 `player:{id}:handoff` 标记 → 第二跳 SM 在一段 Lua 里
复核标记、铸 epoch、写 location，再经 Kafka `RoutePlayerEvent` 让 gate 改绑；目标节点建实体前过 A2′，源节点取证裁决去留，冻结硬上限约 71 s。
Java 的归属与数据在同一行 MySQL 上，把「写回冻结快照」与「owner_epoch 加一」合成一笔事务（§7 第 6 步），scene-manager 只选目标、不进提交路径，
改绑经源节点的链路帧（§4.2）。规格与逐条出处见 `docs/porting/scene-handoff-spec.md`（有意差异 D1–D12 见 PARITY「跨节点换图与归属交接」行）。

```
client     gate G                       源 scene S                          scene-manager        MySQL                          目标 scene T
 63 ─────▶ 转发 ──ClientForward──────▶ 去向 Remote：回 {0}，进 RESOLVING
 ◀─63{0}──                              selectSwitchTarget ──Dubbo────────▶ 选 (T, t) + 软预占
                                        ◀──────────────────────────────────── {T, t}
                                        核对令牌 → 停下 → 冻结快照 → FREEZING
                                        HANDOFF ─────────────────────────────────────────────────▶ UPDATE … epoch = E+1, released = 0, lease = L_i
                                                                                                     WHERE epoch = E AND released = 0 AND lease ≥ now + M
                                        ◀─────────────────────────────────────────────────────── 已交出 (E+1)
                                        移除实例（S 旁人 51；不写回、不拍快照、不写位置）；留墓碑
           ◀── PlayerTransfer{E→E+1, T, t}（同一条链路，排在此前的下行之后）
 G：核对 (S, 代次, 玩家, E) → 改绑 (T, E+1)；不发 PlayerLeave、在线目录不撤
           ── PlayerEnter{t, E+1, transfer = true} ──────────────────────────────────────────────────────────────────────────────▶ 加载：库里 epoch == E+1 才进
 ◀──────── 79（scene_id = t）/ 21（新实体号）/ 47 ◀───────────────────────────────────────────────────────────────────────────── T 旁人 21；写位置 o(E+1)
           ◀── PlayerEnterResult{0, E+1} ──────────────────────────────────────────────────────────────────────────────────── 立即续约一次（renewSoon）
 G：在线目录按 E+1 重登
```

- **63 的去向**（`SceneWorld.resolveSwitchTarget` → `SwitchTarget{Local / Remote / Reject}`，Remote 不带节点号，节点由 scene-manager 定）：
  准入顺序同基线——在途换图（选目标中或冻结中，含建镜像取号中）3014 → 三个号全 0 3005 → 镜像分支（批次 5.3 起建镜像，§4.21）→ 就是当前场景 3008 → 去向。
  显式 scene_id 命中的可以是实例（镜像 / 副本）：本节点上的同步换（在回收宽限 / 排空中回 3023），别的节点上的照常走这里的交出；只带地图只认主世界频道（§4.19）。显式 scene_id 在本节点照旧同步换
  （§4.19，排空中 3023）；不在本节点 → Remote。只带地图时本节点有该图的 ACTIVE 频道就本地挑；没有而且是主世界地图才 Remote（per-node 覆盖下不会出现），
  不是主世界地图直接 3023。Remote 先回 `{0}`（「已受理」，与基线一致），玩家进 RESOLVING——**不冻结**，照常游玩，离场 / 断链 / 接管照现有逻辑处理。
- **选目标**（`SceneDirectoryService.selectSwitchTarget`，scene-manager `SwitchTargetSelector`）：显式 scene_id 在本 zone 的可用目录里找，找不到、
  在多个节点上重复、在排空都回 3000（`TIP_NO_SCENE`），指定的配置与该场景不符回 3005，**不回落**到按地图挑；只带地图时只认主世界地图，
  按「目录人数 + 软预占」挑、排除源场景。两种都写软预占（§4.19）；不铸 epoch、不写位置、不碰归属；目录 / 预占存储不可用时调用以异常完成。
  scene 侧 `SceneManagerSwitchTargets`：独立的 Dubbo 模块 + `ReferenceConfig`，直连 `xm.scene.scene-manager-url`（缺省 `tri://127.0.0.1:20882`，
  本批不接 Nacos），`retries=0`、`check=false`、3 s 超时；建引用与调用都在它自己的守护线程上（逻辑线程不碰 Dubbo），结果投递回逻辑线程，
  本地兜底超时 `xm.scene.switch-resolve-timeout`（缺省 4 s，须大于 3 s、不超过 30 s；也是 RESOLVING 的寿命，再加 1 s 兜底清槽）。
- **结果回到逻辑线程**先核对实例仍在、令牌仍是这一次（否则丢弃、计 `stale`）：调用失败 / 超时 / 应答残缺 → 23 {1003}；拒绝 → 23 {3023}；
  落在本节点 → 本地换（就是当前场景什么都不发；本节点上却不在或在排空 → 23 {3023}，绝不交给自己）；别的节点 → **冻结**：
  停下（旁人收到速度为 0 的 66）→ `toSave()` 拍冻结快照 → FREEZING → 存储线程池提交 `HANDOFF`（§7 第 6 步）。
- **冻结闸**（入口集中、缺省拒绝，只在 FREEZING 生效）：
  - 客户端请求按注册时声明的 `FreezePolicy`（§4.4）：ALLOW = 63（自己回 3014）、84（基线请求不拒、只在施法点 no-op；Java 施法运行态不持久化）；
    READ_ONLY = 43 / 77 / 54 / 167 / 191 / 193 / 190 / 181；GATED = 37 / 49 / 168 / 169 / 171 / 172 / 174 / 175 / 192 / 194 / 195 / 182 / 183 / 185–189，
    进处理器由服务闸回基线码（188 宝宝自动加点只算建议、不写，照常应答）；REJECT = 173 / 94 / 95 与以后没声明的方法，回应答内 1005；DROP = 134 / 132 / 131，静默丢。
  - 服务闸（纵深防御，按 `player.frozen()`）：`BagService` / `PetService` / `AttributeService` / `MissionService` 回 1005（任务事实丢弃），
    `CurrencyService` 加 / 扣 27003（参数校验之后、封禁检查之前，同基线顺序），GM 封禁 / 解封 1005。
  - 资产通道：未见的 seq 回 RETRY 27003、什么都不记（§4.12）；已见的照常只读答复，冻结中不补存。
  - 世界内部：在线周期存盘与 `requestSave` 按在途跳过；续约失去、在线存盘被围栏拒不踢人（§7 第 6 步）；组队跟随与排空改派跳过（`switching`）；
    冻结中的玩家不会被本地 `switchScene`（兜底记 ERROR）。
  - 事后检测：交出提交后比对 `toSave()` 与冻结快照，不同计 `xm_scene_transfer_post_freeze_mutations_total`（应恒为 0，非 0 说明有入口漏了闸）。
- **与回合制战斗互斥**（批次 6.3，§4.23）：63 的准入先判战斗在途（3023）、再判换图在途（3014）；发起交出的入口拒绝在途玩家，选目标结果回来、进入冻结之前再复查一次；
  冻结期间到达的战斗确认只标锁、不挂战斗冻结，结算回 DEFERRED、零副作用，资产通道 27003 优先于 27002；交出没提交、原地解冻之后重跑一次完整的战斗进场恢复。
- **冻结中到达的事件**：`PlayerLeave`（断线 / LeaveGame）与所在链路断开只记下「离开」（先报主动离开、后报链路断开时保持主动），
  被请求让出 E 只记下「被顶号」，都不提交第二笔写；实例与旁人视野保持到结局回来。被请求让出 E+1 源节点不持有、忽略。
- **结局**（每次冻结恰好终结一次，计 `xm_scene_transfers_total{result}`）：

| 结局 | 源节点 | 客户端 | `result` |
|---|---|---|---|
| 已交出，冻结中没有离开 / 顶号 | 移除实例（不写回、不拍快照）→ 留墓碑 → 发 `PlayerTransfer` | 旁人 51；本人 79 / 21 / 47 来自目标节点 | handed_off |
| 已交出，但链路已断 / 不可写（同步可知） | 释放 E+1、位置写重连租约（E, 序号 + 1） | 会话随链路断开关闭 | link_gone |
| 已交出，但 `PlayerTransfer` 交给链路后异步写失败 | 同上（`handed_off` 已计，改计 `xm_scene_link_dropped_total{reason="write_failed"}`） | 同上 | handed_off |
| 已交出，但冻结中会话离开 | 释放 E+1；按是否主动写登出墓碑或重连租约（E, 序号 + 1） | — | left |
| 已交出，但冻结中被顶号 | 释放 E+1；踢旧会话 2017 | 旧连接 23 {2017} 后断开 | taken_over |
| 剩余租约不足（没提交） | 原地解冻；有离开 / 顶号时按现有流程写回并释放 | 23 {3023}，留在原地 | lease_too_short |
| 结局不明，探测确认没提交 | 同上 | 23 {3023}，留在原地 | aborted_in_place |
| 被围栏拒（归属已被夺） | 移除（不写回），同失去归属 | 23 {2017} 后断开 | fenced |
| 结局不明，探测判定不了 / 读到已失去 | 移除（不写回） | 23 {3023} 后断开（gate 记为 `kicked`） | lost_unknown |
| 停服 | 照常提交 `save(E)`：与交出谁先都安全（save 先 → 交出被围栏拒；交出先 → save 被拒、结局处理释放 E+1，停服等它处理完才关存储池） | 随停服断开 | 按实际结局（交出先提交 left；save 先 fenced） |

- **gate 改绑**（`ClientDispatcher`，会话 EventLoop 上；§4.2）：改绑后置 `transferEntering`。目标节点确认进场 → 显式按 E+1 重登在线目录
  （同一会话、epoch 更高者覆盖）；目标节点拒绝 → 推 23 {tip} 后断开；改绑后 `send` 返回 0 → `abandonEnter(E+1)`、推 23 {3023} 后断开；
  交出进场帧没能送到（建链失败 / 排队溢出）→ 一律 `abandonEnter`（会话已关闭 / 已释放也照样），会话仍在这次进场上时推 23 {3023} 后断开；
  改绑指令绑定对得上但内容非法（目标节点为 0、`to_epoch` 不大于 `from_epoch`）→ 推 23 {3023} 后断开、保留旧绑定让正常断线流程通知源节点
  （`to_epoch` 更大时同时放弃它）。这些断开计 `xm_gate_disconnects_total{reason="transfer_failed"}`，不回大厅、不发 34；
  交出进场途中目标链路断开照现有逻辑关会话（`scene_link_down`，不推 tip）。改绑后的上行转发到目标节点，加载完成前目标节点按会话找不到玩家静默丢（带应答的请求收不到应答，与基线目标节点加载期同类）；
  断线 / LeaveGame 的 `PlayerLeave` 发往目标节点，加载中分支释放 E+1。
- **墓碑**（`TransferTombstone`，按会话记，`xm.scene.transfer-tombstone-ttl` 缺省 30 s，每秒清过期、链路断开时丢弃）只服务「gate 在收到
  `PlayerTransfer` 之前已发出 `PlayerLeave`」：这条 leave 到源节点时实例已不在，查到墓碑就按是否主动以 (E, 序号 + 1) 补写一次位置；
  gate 随后把这次改绑当过期并 `abandonEnter(E+1)`。墓碑**绝不**用来释放 E+1（源节点不知道 gate 是否已改绑）。
- **目标节点**：沿用进场流程（库里 epoch 不是 E+1 就 3023）；`transfer = true` 时不拍 LOGIN 快照、计 `xm_scene_transfer_enters_total`、
  进场后立即续约一次；落位用库里的配置号与坐标——同图保留坐标、换不同地图落出生点（与同节点换图、登录口径一致）；运行态（施法阶段、冷却、
  位移校验锚点、补存限频时刻、实体号）随新实例清空，同接管；照常写位置、触发组队跟随。
- **超时预算**：选目标 3 s（兜底 4 s）；交出写两次尝试之间 5 s 截止（至多 3 次）、单次事务 3 s 时限、提交受 socketTimeout 10 s；
  探测按「第一次尝试 + M − 2 s」截止（截止已过也至少读一次）；冻结总长正常情况下约一笔库事务（本机切片实测最长 243 ms），
  库故障时约 M（15 s）以内（取连接与提交不受事务时限约束，库网络分区时可更长，R-J2）；新 epoch 租约 30 s。从受理到出结论，除库网络分区外最坏约 37 s。
- **覆盖与测试**：per-node 覆盖保持缺省（§4.19），跨节点只由显式 scene_id 触发。本机切片 `XM_SCENE_NODES=2 tools/local/start-slice.sh`
  起两个 scene（链路端口 21000 / 21001、资产端口 21100 / 21101、管理端口 18104 / 18114），robot `cross-node` 端到端（详见 PARITY）。
  movement / team 两个 robot 假定 A、B 在同一个场景实例，在双节点切片上会被登录分到不同节点，应在单节点切片上跑。

## 9. ID

- `player_id`：雪花（41 位毫秒 / 10 位 worker / 12 位序号），worker 为 `xm-login` 的节点号；只由 `xm-login` 产生。
- `session_id`（uint32，仅服务端内部）：`[gate 节点号 15 位][序号 17 位]`，跳过 0 与在用号。
- 物品 uuid、资产流水号 `tx_id`、玩家快照号 `snapshot_id`：雪花，共用一个租约门控的雪花（xm-common `LeaseGatedSnowflake`），worker 取自场景节点占的**全服**号段租约（`NodeTypes.SCENE_GUID`，作用域 0；不论审计开不开都占）——
  场景节点自己的租约按 zone 分，两个 zone 的第一台 scene 会拿到同一个 worker、发出相同的号，落库按主键去重就会静默吞掉一条。
  停服时先发完审计队列再交还这个租约（反过来别的实例可能拿到同一个 worker 发重号）。
  xm-data 运维面直写的快照号、作业号（`OpsIds`），以及兜底日志回灌工具新发的流水号，也从同一个池占 worker（批次 7.2a，§4.5）；
  批次 7.2b 起回档的 PRE_ROLLBACK 安全快照号、TX_ROLLBACK_RESTORE 流水号与整区维护前快照号同样取自它（§4.5.1；号源无效时这一笔不写，`id_unavailable`）。
  雪花号不含节点类型位，另开租约类型会与 scene 的 worker 重叠，撞号的行被主键 ODKU 静默吞掉。`SCENE_GUID` 这个名字不改：改名等于换键空间，只能停服切换。
- `team_id`、`guild_id`：雪花，worker 取各自服务的全服租约（`NodeTypes.TEAM` / `NodeTypes.GUILD`，作用域 0）。
- **不移植号段服务**（基线 data_service `AllocateIdSegment` + 各节点的号段客户端，盘点 id-segment-allocator / guid-segment-alloc）：
  Java 的永久号一律「雪花 + 节点号租约」——发号前检查租约仍有效、时钟回拨拒发，worker 不重叠由租约保证；
  客户端只要求这些号非 0、唯一（uint64），不依赖号段的值域（基线号段值域 < 2^55，与存量雪花号不相交——Java 没有存量号段号，无此约束）。
  场景内的临时 id（实体）用场景节点自己的雪花（`SceneWorld.nextId`）。
- `scene_id`（主世界频道的身份，批次 5.1 起）：由 scene-manager 的 zone 领导者从全服租约（`NodeTypes.SCENE_MANAGER`，作用域 0）上的雪花发，
  写进频道计划；频道终生绑定一个 (zone, 节点号)，换节点 = 新号。节点用同一个节点号重启会按计划把同号频道重新建出来，玩家位置里记的 scene_id
  因此跨节点重启仍然有效。镜像 / 副本的实例号（批次 5.3）同样从这个全服租约发（`createInstance`，§4.21），发号租约是独立 bean `SceneIdAllocator`、
  每个 scene-manager 副本都申领（worker 号段 1..1023 全服共享）；实例不进计划，节点重启后不重建，位置里记的实例号找不到就按原地图落点。
  scene 节点不自己给实例发号（`SCENE_GUID` 与 `SCENE_MANAGER` 的 worker 号段各自申领，同毫秒同 worker 会撞号）。
- battle 节点的实例 id 是每次进程启动生成的 UUID（进票据与目录，同号的新进程不接受旧票）；battle_id 由 xm-match 发（批次 6.4），dev 管理口由调用方给。
  battle_id **必须随时间递增**（时间在高位的雪花号）：scene 进场恢复按 battle_id 无符号升序应用同一玩家的多条待结算记录，气血是终值，顺序就是语义（§4.23）；
  robot 用 dev 管理口时按「毫秒时间戳 × 1000 + 序号」取号。
- `battle_id`、`challenge_id`（批次 6.4，§4.24）：雪花，共用 xm-match 的 `MatchIds`，worker 取全服租约 `NodeTypes.MATCH`（作用域 0，worker [0, 1023]，租约 TTL 15 s，不发布目录）。
  发号前检查租约：续期滞后或已丢失时不发号（开局、切磋发起、活动开战按内部错误回，凑单暂停）。帮会活动开战的 battle_id 在开局之前预先发号、同步回给调用方。
  基线这三个号（含 team_id）是 match 进程的同一个发号器；Java 的 team_id 归 xm-team 的 `NodeTypes.TEAM`。
- 排队票号 `queue_ticket`：`UUID.randomUUID()` 的小写带连字符形式（同基线），客户端可见；整队开战的票号由 xm-team 每人生成一个，活动开战由 xm-match 生成。
  弹组 / 回队首的重放 token 与切磋应答的 nonce 也是随机 UUID，只在服务端内部用。

## 10. 首批不做（后续批次）

跨 zone、战斗的匹配（battle 节点已于 2026-10-05 补上，见 §4.22；scene 侧冻结 / 结算与结算发件箱已于 2026-10-06 补上，见 §4.23；匹配——排队、凑单、开局、补签、评分、切磋、整队开战与活动开战的 match 侧——已于 2026-10-08 补上，见 §4.24；观战的 match 侧与跨区 1V1 已于 2026-10-08 随批次 6.5 补上，见 §4.24.1——跨 zone 传送 226 / 重定向 124 仍属 5.4）、Kafka 事件、GM 管理接口（远程停机与 xm-data 运维面除外；GM 快照 / 差异 / 物品追溯 / 回收 dry-run 已于 2026-10-05 补上，见 §4.5；运维作业框架、回档与整区维护前快照已于 2026-10-05 补上，见 §4.5.1；回收执行、欠款、精确回收随 7.2c），
服务级限流 / 熔断（Sentinel）、合服与 TiDB 数据层。（周期存盘已于 2026-10-02 补上，短线重连已于 2026-10-04 补上，见 §7；货币与 GM 客户端指令闸见 §4.4；登录排队与开服限流见 §8；
  gate 排空与 GM 签名停机见 §6；同 zone 跨节点换图与归属交接已于 2026-10-05 补上，见 §7 第 6 步与 §8.1——跨 zone 传送仍待做；
  镜像 / 副本实例与空闲回收已于 2026-10-05 补上，见 §4.21。）
（低基数运行指标全部进程都已接入，见 §11。）
顶号已按 §7 第 2 步实现（旧连接收 23 {2017} 后断开，不发 34）。进度逐项登记在 `PARITY.md`。

## 11. 可观测性（指标）

每个进程用 **Micrometer** 记指标，经 **Spring Boot Actuator** 以 Prometheus 文本格式导出（选型见 tech-stack.md）。
指标名与标签只在每个进程的一个类里定义，业务代码只调语义方法：gate `GateMetrics`、login `LoginMetrics`、
scene-manager `SceneDirectoryProvider`、gateway `AssignGateMetrics`、scene `SceneMetrics`（回合制战斗的计数单列在 `SceneBattleMetrics`）、friend `FriendMetrics`、chat `ChatMetrics`、team `TeamMetrics`、guild `GuildMetrics`、trade `TradeMetrics`、data `DataMetrics`、battle `BattleMetrics`（两个发件箱在 `OutboxMetrics`）、match `MatchMetrics`（标签净化在 `MetricLabels`）。

| 进程 | 抓取地址（默认） | 说明 |
|---|---|---|
| xm-gateway | `http://127.0.0.1:18105/actuator/prometheus` | 管理专用端口，默认只绑本机（`XM_MANAGEMENT_ADDRESS`）；对外的 18081 只有 `/api` |
| xm-login | `http://127.0.0.1:18101/actuator/prometheus` | 管理专用端口 |
| xm-friend | `http://127.0.0.1:18107/actuator/prometheus` | 管理专用端口 |
| xm-chat | `http://127.0.0.1:18108/actuator/prometheus` | 管理专用端口 |
| xm-team | `http://127.0.0.1:18109/actuator/prometheus` | 管理专用端口 |
| xm-guild | `http://127.0.0.1:18110/actuator/prometheus` | 管理专用端口 |
| xm-trade | `http://127.0.0.1:18111/actuator/prometheus` | 管理专用端口；同一端口上还有 dev / test 专用的播种接口 `POST /admin/trade/seed-listing`（运维令牌，其他运行模式 403） |
| xm-scene-manager | `http://127.0.0.1:18102/actuator/prometheus` | 管理专用端口 |
| xm-gate | `http://127.0.0.1:18103/actuator/prometheus` | 管理专用端口 |
| xm-scene | `http://127.0.0.1:18104/actuator/prometheus` | 管理专用端口；同机多个 scene 实例要各用 `SERVER_PORT` 错开（与链路端口一样）；同一端口上还有 dev / test 专用的建 / 毁副本接口 `POST /admin/scene/instance/{create,destroy}`（运维令牌，其他运行模式 403，§4.21） |
| xm-data | `http://127.0.0.1:18106/actuator/prometheus` | Web 进程：同一端口上还有带令牌的运维接口 `/admin/**`（§4.5），默认只绑本机 |
| xm-battle | `http://127.0.0.1:18112/actuator/prometheus` | 管理专用端口；同一端口上还有 dev / test 专用的建房接口 `POST /admin/battle/dev/*`（运维令牌，其他运行模式 403，§4.22）。业务端口：客户端直连 12000（对客户端开放）、控制面 Dubbo 21200 |
| xm-match | `http://127.0.0.1:18113/actuator/prometheus` | 管理专用端口；同一端口上还有 dev / test 专用的 `GET /admin/match/dev/rating/{pid}` 与 `POST /admin/match/dev/activity-battle`（运维令牌 + 操作人，其他运行模式 403，§4.24）。业务端口：Dubbo 20888。`/actuator/health` 含组件 `matchLease`：发号租约真正丢失时报 DOWN（503），要重启进程 |

- **非 Web 进程的管理端口**：gate / login / friend / chat / team / guild / trade / match / scene-manager / scene / battle 的业务端口是 Netty / Dubbo，为管理端点另起一个只挂 actuator 的
  Tomcat（`web-application-type: servlet`，4 个线程，`shutdown: immediate`），这个端口上没有业务接口（trade / scene / battle / match 另有 dev / test 专用的管理接口，见上表）。
  scene 的建副本接口每次最多占一个 Tomcat 线程约 6 s（`switch-resolve-timeout` + 2 s），并发建副本可能暂时占满这 4 个线程（只在 dev / test；prod 在阻塞之前就回 403 / 503）。
  默认只绑 `127.0.0.1`（与 mmorpg 开发环境的 Prometheus 端口同口径），跨机抓取用 `XM_MANAGEMENT_ADDRESS` 指定内网地址；
  端口用 `SERVER_PORT` 覆盖，Windows 上避开保留端口段 50060–50159。actuator 只暴露 `health` 与 `prometheus` 两个端点。
  构建信息从批次 7.1a 起已经写进各进程 jar 的 `META-INF/build-info.properties`（§6.1）；`info` 端点与 readiness / liveness 探针要到 7.1b 才打开。
  gate / scene 的这个端口上另有 GM 签名停机的 `GET /gm/identity` 与 `POST /gm/graceful-shutdown`（§6）：它们只收本机来的请求，
  为跨机抓取放宽 `XM_MANAGEMENT_ADDRESS` 不会把它们一起暴露（确需远程调用时另设 `XM_GM_ALLOW_REMOTE=true`）。
- **公共标签**：`application=<进程名>`。实例由 Prometheus 的抓取目标区分，不在进程里加实例标签。
- **基数约束**（AGENTS.md §5）：不以 player_id / session_id / 账号 / IP / zone_id 作标签。消息维度只用 `服务.方法`
  （`MessageIdRegistry` 的客户端白名单，有界），不认识的消息号一律归 `unknown`——公网流量造不出新的时间序列；
  scene 的场景维度只用场景配置号 `scene_config`（频道按计划、镜像 / 副本按需建出，但取值仍受 BaseScene 表约束、有界；镜像居民计入源地图），
  不用场景实例号 / 实体号 / gate 节点号 / 链路号；battle 不用 battle_id / 会话 / IP / 节点号，消息维度只用 7 个下行与 4 个上行的方法名（白名单外的上行归 `other`）；
  match 不用玩家 / 战斗 / 切磋 / 队伍的号与 zone，并对客户端能控制的两个值做净化（`MetricLabels`）：`mode` 只取契约里已知的 `MatchMode` 枚举名（如 `MATCH_MODE_1V1`），其余记 `unknown`；
  `config` 只取 0 或 Dungeon 表里存在的副本号，其余记 `other`——`battle_config_id` 不校验（照搬基线，任何值都能开一条队列），但不能让它造出新的时间序列（基线这两个标签由客户端控制）；
  其余标签都是代码里的枚举（scene 管理口的 `status` 是 HTTP 状态码）。
- **延迟**：Timer 用固定的 SLO 桶（gate / login 5ms～10s 共 11 个，scene-manager 1ms～1s 共 8 个，scene 逻辑线程内
  0.1ms～1s 共 12 个、含一帧预算 50ms，scene 存储写 5ms～10s 共 11 个，scene 跨节点换图冻结时长 5ms～20s 共 13 个，battle 逻辑线程上的回合结算 0.1ms～1s 共 12 个、
  battle 控制面 1ms～1s 共 10 个，match 的客户端请求 5ms～10s 共 11 个、开局管线 10ms～120s 共 13 个——开局的耗时含失败后的补偿，可以到 90 s），不开百分位直方图。
- **scene 的线程所有权**（§5）：抓取线程不读场景状态。在线人数由逻辑线程在人数变化后推送绝对值；逻辑线程队列长度、
  gate 链路连接数、存储线程池状态由线程安全的计数（Netty 任务队列、`ChannelGroup`、`ThreadPoolExecutor`）直接读。
- **JVM / 进程 / Tomcat / HTTP 请求**等通用指标由 Actuator 自带（`jvm_*`、`process_*`、`http_server_requests_*` ……）。

业务指标（Prometheus 名；计数器带 `_total`，Timer 导出 `_seconds_count` / `_sum` / `_max` / `_bucket`）：

| 进程 | 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|---|
| gate | `xm_gate_sessions_active` | Gauge | — | 当前会话数（含未握手、正在收尾的） |
| gate | `xm_gate_scene_links` | Gauge | — | 到各 scene 节点的链路数（建链中 + 就绪） |
| gate | `xm_gate_handshakes_total` | Counter | `result`=ok / bad_signature / bad_payload / wrong_gate / wrong_zone / expired / timeout / missing | 令牌握手结果（missing = 没握手就发业务包） |
| gate | `xm_gate_client_requests_total` | Counter | `route`=login / scene / 其余后端域（friend / chat / team / guild / trade / match）/ unsupported / unknown，`method`=服务.方法 / unknown，`result`=forwarded / not_in_scene / link_unavailable / unsupported / unknown_message / oversized / rate_limited / gm_rejected / battle_rejected / killed / overflow / dropped | 已握手会话上每个请求在 gate 的最终去向，恰好计一次。C++ 的「非法包」= unknown_message + oversized + rate_limited + gm_rejected；限频拒绝 = rate_limited。battle_rejected（批次 6.3）= 战斗服务 `BattleClientPlayer` 的 12 个号发到了大厅连接上，GM 闸之后当场推 23 {1003}（`route`=unsupported、`method`=BattleClientPlayer.<方法名>；不计非法包、不断连，战斗上行不再计 unsupported）。这 12 条序列在装配时预建为 0，是请求计数器里唯一预建的一组；持续增长说明有旧客户端或误走大厅的战斗上行，要告警 |
| gate | `xm_gate_client_invalid_frames_total` | Counter | `reason`=invalid_length / checksum / invalid_name_len / unknown_type / parse | 解码层非法帧（随即断开） |
| gate | `xm_gate_disconnects_total` | Counter | `reason`=handshake_timeout / handshake_rejected / no_handshake / illegal_packets / pending_overflow / write_buffer_full / invalid_frame / session_id_exhausted / server_directive / kicked / scene_link_down / server_kick / transfer_failed | gate 主动断开的连接（客户端自己断开、停服 / 丢租约的批量关闭不计）；transfer_failed = 跨节点换图改绑之后没能落到目标节点，或改绑指令内容非法（§8.1；源节点探测判失去时经 `PlayerKicked` 断开，计 kicked） |
| gate | `xm_gate_pushes_total` | Counter | `kind`=message / kick，`result`=delivered / no_session / not_bound / stale_instance / invalid | 服务端推送（§4.3）对每个目标会话的结局：已下发 / 会话号已不存在 / 会话不在游戏里或玩家对不上（栅栏）/ 指向别的 gate 实例 / 格式不对。按序批量推送（`MessageBatch`，批次 6.2）计 kind=message、每个目标只计一次：全部送出才算 delivered，空批或任一条损坏算 invalid |
| gate | `xm_gate_backend_calls_seconds` | Timer | `backend`=login / friend / chat / team / guild / trade / match（`MessageRoutes.SERVICE_BACKENDS` 的后端域），`method`=handle / sessionClosed / abandonEnter（login 以外的后端只有 handle），`result`=ok / error | 对后端的 Dubbo 调用耗时（带 tip 的应答算 ok；超时 / 不可用算 error）。后端重启后约 60 s 的重连窗口里（§6）对应 `backend` 的 error 会持续增长 |
| gate | `xm_gate_link_frames_total` | Counter | `direction`=out / in，`type`=链路帧类型（hello / player_enter / client_forward / to_client / player_transfer ……） | gate ↔ scene 链路帧（out = 已写上链路，排队中不算） |
| gate | `xm_gate_link_dropped_total` | Counter | `reason`=lease_invalid / queue_full / link_failed / unavailable | 没发出去的链路帧 |
| gate | `xm_gate_link_events_total` | Counter | `event`=connecting / ready / connect_failed / down | 链路状态变化 |
| gate | `xm_gate_scene_transfers_total` | Counter | `result`=rebound / stale / orphan / invalid / link_unavailable / undeliverable / entered / enter_failed | 跨节点换图改绑指令（scene 的 `PlayerTransfer`，§4.2 / §8.1）在 gate 的结局：每条指令恰好计 rebound（已改绑并发出交出进场）/ stale（会话线程判过期，放弃新 epoch）/ orphan（路由层找不到会话，放弃新 epoch）/ invalid（帧内容非法，断开）之一；每次 rebound 至多再计 entered / enter_failed / link_unavailable / undeliverable 之一（之间断线、离开游戏、目标链路断开的不计；undeliverable 也含会话已断开后才报未送达的）。勾稽：scene `transfers{handed_off}` − `link_dropped{write_failed}` ≈ rebound + stale + orphan + invalid |
| login | `xm_login_requests_seconds` | Timer | `method`=Login / CreatePlayer / EnterGame / LeaveGame / Disconnect / unrouted，`result`=ok / business_error / internal_error / overloaded / bad_request / unsupported | 每个客户端请求的耗时与结果（含工作队列排队）；business_error = 应答体带 `error_message` |
| login | `xm_login_owner_claims_seconds` | Timer | `outcome`=claimed / waited / timeout / not_found / error | EnterGame 夺取归属这一步（§7）：第一次夺到 / 请持有者让出后夺到（顶号、快速重进）/ 等不到回 2005 / 角色已不存在 / 故障 |
| login | `xm_login_owner_takeover_requests_total` | Counter | — | 请持有者让出的次数（每次重试都会再请一次） |
| login | `xm_login_backend_calls_seconds` | Timer | `backend`=scene-manager，`method`=assign，`result`=ok / rejected / error | 场景分配调用耗时 |
| login | `xm_login_players_created_total` | Counter | — | 新建成功的角色（应答丢失后的重试命中已建角色不计） |
| login | `xm_login_abandoned_enters_total` | Counter | `result`=released / stale / failed / overloaded / invalid | gate 通知进场未送达后代为释放归属的结果（含跨节点换图的过期 / 孤儿改绑指令与交出进场未送达，§8.1） |
| login | `executor_*{name="login-worker"}` | Micrometer 标准线程池指标 | — | 工作线程池排队 / 活跃 / 完成数（队列满见 `xm_login_requests{result="overloaded"}`） |
| friend | `xm_friend_requests_seconds` | Timer | `method`=ClientPlayerFriend 的方法名 / unrouted，`result`=ok / business_error / internal_error / overloaded / bad_request / unauthenticated / forbidden / unsupported | 每个客户端请求的耗时与结果；internal_error = 应答体是 1003，forbidden = 上行 235，unauthenticated = 会话没绑定玩家 |
| friend | `xm_friend_pushes_total` | Counter | `reason`=request_received / request_accepted，`outcome`=ok / offline / error | 好友事件推送的结局（6 个组合启动即注册；error 含没有订阅者的 gate） |
| friend | `xm_friend_request_quota_total` | Counter | `outcome`=allowed / rejected / error | 发申请的每分钟配额；error = Redis 故障被放行（fail-open 的唯一信号，须配告警） |
| friend | `xm_friend_online_lookups_total` | Counter | `outcome`=ok / offline / error | 好友列表的在线状态读，每个去重后的玩家计一次 |
| friend | `xm_friend_cache_total` | Counter | `cache`=list / pending，`result`=hit / miss / fill_skipped / fill_failed / error | 列表缓存：fill_skipped = 代次变了放弃回填，error = 读失败或值坏（回 1003） |
| friend | `xm_friend_cache_invalidation_failures_total` | Counter | — | 写已提交但缓存失效失败的键数（该键最多陈旧一个 TTL） |
| friend | `xm_friend_guard_retries_total` | Counter | `kind`=missing_row / ensure_deadlock | 容量守卫的重试；正常运行时应恒为 0 |
| friend | `xm_friend_count_underflows_total` | Counter | — | 好友计数减到 0 以下被拦截（计数与边数脱节，需人工排查） |
| friend | `xm_friend_directory_quota_total` | Counter | `outcome`=allowed / rejected / error | 在线目录翻页的每分钟配额（error = Redis 故障被拒，fail-closed） |
| friend | `xm_friend_sweep_pending_rows`、`xm_friend_sweep_idle_capacity_rows` | Gauge | `mode`=report_only / delete | 清理看到的过期终态申请 / 零好友容量行（受 batch-limit 封顶；合法模式每轮都刷含 0，长期不变说明清理没在跑） |
| friend | `executor_*{name="friend-worker"}` | Micrometer 标准线程池指标 | — | 工作线程池状态（队列满见 `xm_friend_requests{result="overloaded"}`） |
| chat | `xm_chat_sends_total`、`xm_chat_pulls_total` | Counter | `channel`=world / private / team / system / unspecified / unknown，`outcome`=ok / duplicate / in_flight / no_session / bad_request / channel_unavailable / too_long / rate_limited / storage_error | 发言 / 拉取的结局（同基线 chat_send_total / chat_pull_total，全部组合启动即注册） |
| chat | `xm_chat_requests_seconds` | Timer | `method`=SendChat / PullChatHistory / unrouted，`result`=ok / business_error / internal_error / bad_request / unsupported | 每个客户端请求的耗时与结果 |
| team | `xm_team_requests_seconds` | Timer | `method`=ClientPlayerTeam 的方法名 / unrouted，`result`=ok / business_error / internal_error（tip 4030）/ overloaded / bad_request / unauthenticated / forbidden / unsupported | 每个客户端请求的耗时与结果（基线 team_rpc_total） |
| team | `xm_team_commit_retries_total` | Counter | `op`=方法名 | 版本冲突重算的次数（含修复提交的冲突） |
| team | `xm_team_heals_total` | Counter | `kind`=orphan_index / index_mismatch | 自愈：治孤儿索引 / 修复提交移出错位成员 |
| team | `xm_team_pushes_total` | Counter | `kind`=snapshot / invite / event / members_changed，`outcome`=ok / offline / error / skipped | 推送结局（213 / 215 / 203；members_changed = MEMBER_ONLINE 读成员时名单一直在变、放弃） |
| team | `xm_team_matches_total` | Counter | `outcome`=rejected / internal / unknown_code / success / gather_failed / ticket_failed / gather_unknown | 整队开战的终态，一次 211 恰好计一次（批次 6.4 接上开战，§4.16）：同步拒绝按 tip 计 rejected（业务码）/ internal（4030，含 xm-match 调不通、应答缺字段、建票结果不明）/ unknown_code；建票失败计 ticket_failed（4026[pid]）；已受理的在开局结果到达时计 success / gather_failed / gather_unknown。gather_unknown = `runTeamGather` 传输失败、结果不明（Java 独有：基线 match 与 team 同进程），照样推 MATCH_FAILED，持续出现要查 xm-match 与两者之间的网络 |
| team | `xm_team_cross_zone_allowed` | Gauge | — | 是否允许跨区组队（多实例取值不一致时用它观察） |
| team | `executor_*{name="team-worker"}`、`{name="team-push"}`、`{name="team-match-end"}` | Micrometer 标准线程池指标 | — | 请求工作池 / 推送执行器 / 整队开战加锁之后的收尾池（批次 6.4；队列满时放弃这次收尾、记 ERROR，开战锁靠自然过期） |
| guild | `xm_guild_requests_seconds` | Timer | `method`=GuildService 的方法名 / unrouted，`result`=ok / business_error / fault（in-band 14008）/ internal_error（信封 1003）/ overloaded / bad_request / unauthenticated / forbidden / unsupported | 每个客户端请求的耗时与结果（基线 serverbase 的 rpc_inband_* 与 rpc_duration_seconds） |
| guild | `xm_guild_pushes_total` | Counter | `kind`=13 个变更种类的小写串 / other，`outcome`=ok / offline / error / session_error | 推送 220 按收件人计 |
| guild | `xm_guild_tx_deadlocks_total`、`xm_guild_tx_budget_exceeded_total`、`xm_guild_tx_lock_wait_timeouts_total` | Counter | `op`=固定集合（create / set_role / kick / transfer / leave / apply / cancel / review / disband / announcement / verify_mapping / score / insert_guard 及 4.5 / 4.6 预留） | 事务死锁重跑 / 子预算用完 / 1205 |
| guild | `xm_guild_cache_invalidation_failures_total` | Counter | `op` | 提交后失效缓存，后台重试用尽 |
| guild | `xm_guild_cache_total` | Counter | `cache`=snapshot / mapping，`result`=hit / miss / fill_skipped / fill_failed / error | 帮会快照与玩家→帮会映射缓存 |
| guild | `xm_guild_profile_lookup_failures_total`、`xm_guild_online_lookups_total{outcome}`、`xm_guild_rank_ops_total{op,outcome}` | Counter | 见左 | 展示名读失败、在线批量读（ok / timeout / error）、排行写 / 删 / 重建（ok / lock_timeout / error） |
| guild | `executor_*{name="guild-worker"}` | Micrometer 标准线程池指标 | — | 请求工作池 |
| guild | `xm_guild_economy_requests_seconds`、`xm_guild_asset_sync_skipped_total`、`xm_guild_asset_orphans_total`、`xm_guild_asset_cleanup_deleted_total` | Timer / Counter | 见 `GuildMetrics`（method / result / kind 等固定枚举） | 经济五个 RPC；同步投递因预算不足跳过；终结时对侧行缺失；清理删行 |
| guild | `xm_guild_assetop_*`（rpc / rpc_duration / requery / outcome_flip / unknown / partial / claim / reschedule / reschedule_lost / finalize / ledger_read / manual_resolve / store_errors / pending_oldest_age_seconds）、`xm_guild_scene_resolve_total` | Counter / Timer / Gauge | `rpc`、`stream`、`outcome`、`origin`（sync / loop）等固定枚举，不含任何 id | 资产指令投递与重投循环（基线 assetop 指标）；定位 scene 的结局 |
| guild | `xm_guild_internal_list_applied_total{result}`、`xm_guild_internal_list_applied_rows` | Counter / 分布 | `result` | 内部查询 |
| trade | `xm_trade_requests_seconds` | Timer | `method`=BrowseListings / GetListingDetail / SetFavorite / GetMyShelf / unrouted，`result`=ok / business_error / internal_error / overloaded / bad_request / unauthenticated / forbidden / unsupported（启动时全部预建） | 每个客户端请求的耗时与结果（基线 grpcstats + serverbase 的 rpc_inband_*） |
| trade | `xm_trade_home_zone_lookups_total` | Counter | `result`=ok / unmapped / error | 归属区查询（基线 `trade_home_zone_lookup_total`） |
| trade | `xm_trade_seed_listings_total`、`xm_trade_admin_requests_total` | Counter | 前者 `result`=ok / rejected / error；后者 `op`=seed_listing / other、`status` | dev 播种（基线 `trade_seed_listing_total`）；管理口审计 |
| trade | `xm_trade_favorite_retries_total` | Counter | 无 | 收藏写入撞 1213 / 1205 / 9007 后整条重跑的次数（Java 增项） |
| data | `xm_data_kafka_records_total` | Counter | `consumer`=transaction_log / player_snapshot，`outcome`=inserted / duplicate / decode_error / invalid / rejected | 消费到的审计记录的结局，每条恰好计一次（§4.5；全部组合在登记消费者时注册） |
| data | `xm_data_kafka_consumer_up`、`xm_data_kafka_consumer_lag` | Gauge | `consumer` | 消费者是否在跑（topic 核对通过、轮询线程活着）；本实例分到的分区上还没落库的记录数之和（不是整个消费组的） |
| data | `xm_data_db_insert_seconds`、`xm_data_db_insert_errors_total` | Timer / Counter | `consumer` | 一次拉取的记录在一个事务里落库的耗时；落库失败的尝试（可恢复故障退避重试、不提交位点） |
| data | `xm_data_retention_deleted_total` | Counter | `table`=transaction_log / player_snapshot / player_snapshot_gm | 保留期清理删掉的行；player_snapshot_gm = 运维与安全快照（PRE_MAINTENANCE / GM_MANUAL / PRE_ROLLBACK / PRE_GM_EDIT，批次 7.2a） |
| data | `xm_data_admin_requests_total` | Counter | `op`=transaction_log / player_snapshots / players / items / recalls / rollbacks / zone_snapshots / ops_jobs（后三个批次 7.2b）/ killswitch / gain_blocks / zones / announcements / gates / whitelist / other，`result`=HTTP 状态码 | 运维接口请求，每次调用恰好计一次（含鉴权拒绝）；带路径参数的接口按前缀归类，任意路径不会变成标签值 |
| data | `xm_data_snapshot_admin_total` | Counter | `cause`=gm_manual / pre_maintenance，`result`=ok / replayed / player_not_found / id_unavailable / idempotency_conflict / db_error | 运维直写快照的结局（批次 7.2a）；标签取值是固定集合，首次用到时才注册（不是启动时预建） |
| data | `xm_data_ops_jobs_total`、`xm_data_ops_job_seconds` | Counter / Timer | `kind`=rollback / zone_snapshot（清扫时读不到作业行记 unknown）；Counter 另有 `outcome`=succeeded / partial / rejected / failed / diverged_after_write / cancelled / interrupted | 运维作业的结局与从开始执行到结局的耗时（批次 7.2b，§4.5.1）。谁把作业行改成终态谁记，同一作业只计一次（清扫器改的由清扫器记 interrupted）。首次用到时才注册。interrupted / diverged_after_write 增长要告警 |
| data | `xm_data_ops_jobs_running`、`xm_data_ops_fence_held` | Gauge | — | 本实例正在执行的作业数；此刻以运维身份持有归属的玩家数。装配时预注册，空闲应为 0。`fence_held` 持续不为 0 且超过 `job-timeout` = 作业卡住、玩家进不了游戏（要告警） |
| data | `xm_data_ops_players_total` | Counter | `kind`=rollback，`outcome`=restored / player_online / player_busy / in_battle / battle_lock_unknown / player_not_found / snapshot_not_found / no_snapshot / created_after_target / snapshot_gone / state_invalid / unknown_sections / fence_lost / id_unavailable / failed / not_executed / rejected / cancelled（共 18 个） | 回档作业逐玩家的结局（即 `ops_job_player.outcome`，RESTORED 记作 restored）；取值是代码里的固定集合 `RollbackJob.PLAYER_OUTCOMES`，批次 6.3 起**装配时全部预建为 0**（xm-data 其余计数仍是首次用到才注册）。in_battle / battle_lock_unknown 是回档前查战斗锁的两个结局（§4.5.1）：前者是规则拒绝，后者是读不到锁（Redis 故障，fail-closed），增长要告警 |
| data | `xm_data_ops_claims_total`、`xm_data_ops_fence_lost_total` | Counter | 前者 `outcome`=claimed / kicked（这一次夺权调用里重发过让出请求之后才夺到）/ online（在线、没有踢）/ timeout / not_found / error；后者无标签 | 运维夺取归属的结局；持有期间续约失败的人数（之后不再对他写）。`timeout` 突增 = 有活着的写者不让出 |
| data | `xm_data_rollback_divergence_check_total` | Counter | `source`=guild / ledger / recall；`result`：guild = clean / divergence / unprovable / check_failed / post_write_clean / post_write_diverged / post_write_failed，ledger = clean / divergence / unprovable，recall = clean / divergence | 回档的三道资产分歧检查，每个作业每道至多一次（写后复查另计一次）。`check_failed` 与 `post_write_*` 里的 diverged / failed 算故障，要告警；divergence / unprovable 是规则拒绝 |
| data | `xm_data_rollback_divergence_rows_total`、`xm_data_rollback_guild_check_seconds` | Counter / Timer | 前者 `source`=guild / ledger、`accepted`=true（只在带原因放行时累加）；后者无标签 | 被放行的分歧行数；一次帮会检查（含翻页）的耗时 |
| data | `xm_data_location_tombstones_total` | Counter | `result`=ok / stale（已有更新的写）/ error | 运维释放归属前写的位置墓碑（§7「玩家位置与短线重连」） |
| scene | `xm_scene_asset_ops_total` | Counter | `rpc`=debit / abort_debit / credit，`outcome`=applied / rejected / retry / not_here / unknown / overloaded / error | 资产指令应答结局（全部组合启动即注册） |
| scene | `xm_scene_asset_ops_inflight`、`executor_*{name="scene-asset-reply"}` | Gauge / 线程池 | — | 资产指令在途数与应答执行器 |
| scene-manager | `xm_scene_manager_assign_seconds` | Timer | `result`=ok / no_scene / bad_request / rejected / error | 场景分配结果与耗时（error = 场景目录不可读） |
| scene-manager | `xm_scene_manager_switch_seconds` | Timer | `result`=ok / not_found / draining / bad_request / error | 在线换图选跨节点目标（`selectSwitchTarget`，§8.1）的结果与耗时（含一次目录读与软预占；error = 目录 / 预占存储不可用，scene 推 23 {1003}）；五个结果启动即注册，桶同 assign |
| scene-manager | `xm_scene_manager_instance_seconds` | Timer | `kind`=mirror / dungeon / other（请求里的种类不合法），`result`=ok / bad_request / node_unavailable / no_lease / error | 镜像 / 副本实例取号（`createInstance`，§4.21）的结果与耗时（不碰 Redis，只发号）：node_unavailable = 发起节点不接新实例（5.5 钩子，现在不会出现）；no_lease = 发号租约无效、error = 意外异常（两者都以异常完成，scene 推 23 {1003}）；3 × 5 个组合启动即注册，桶同 assign |
| scene-manager | `xm_scene_manager_world_leader_zones`、`xm_scene_manager_world_ticks_total{result}`、`xm_scene_manager_world_tick_seconds` | Gauge / Counter / Timer | `result`=ok / not_leader / fenced / conflict / no_lease / no_nodes / error | 本副本领导的 zone 数；频道计划每拍的结局与耗时（§4.19） |
| scene-manager | `xm_scene_manager_world_channels{scene_config,state}`、`xm_scene_manager_world_autoscale_total{action,outcome}`、`xm_scene_manager_rebalance_pending{reason}`、`xm_scene_manager_rebalance_migrations_total{reason,outcome}` | Gauge / Counter | `scene_config`=World 表地图号 / other；`state`=active / draining / missing；其余固定枚举 | 频道计划的规模与变动（不带 zone / 节点 / 场景号标签） |
| 开了热关停的进程 | `xm_killswitch_rules` | Gauge | — | 当前生效的规则条数（快照作废后为 0） |
| 开了热关停的进程 | `xm_killswitch_sync_failures_total` | Counter | — | 从 Redis 读规则失败的次数（§4.15） |
| 开了热关停的进程 | `xm_killswitch_blocked_total` | Counter | `method`=被拒的方法（gate：客户端 服务.方法；Dubbo：接口短名/方法） | 被热关停拒绝的调用 |
| gateway | `xm_gateway_assign_gate_total` | Counter | `code`=0 / 100 / 400 / 404 / 410 / 429 / 500 / 503，`reason`=ok / queueing（登录排队）/ ratelimit（限流排队）/ 应答体 `error` 文案 | assign-gate 结局（含请求体不合法、未预期异常被兜底的路径）；16 个已知组合启动即注册（`xm_gateway_queue_status_total` 同形，8 个） |
| gateway | `xm_gateway_queue_admits_total` | Counter | — | 登录排队放行的人数（只有拿到选主锁的那个 gateway 在放行，见「登录排队」） |
| gateway | `xm_gateway_login_total` | Counter | `endpoint`=login / refresh，`code`=0 / 100 / 401 / 429 / 500 | HTTP 登录 / 刷新令牌的结局（应答体业务码；100 / 429 只有 login 会出现）；8 个已知组合启动即注册 |
| scene | `xm_scene_players` | Gauge | `scene_config`=场景配置号 | 该配置下的在线玩家数（同配置各频道合计；加载中的进场不算）。建场景即注册、初值 0 |
| scene | `xm_scene_logic_pending_tasks` | Gauge | — | 逻辑线程待执行的任务数（链路帧、存储回调、归属事件；不含定时的帧任务）。上界 ≈ gate 数 × `link-max-pending-frames` |
| scene | `xm_scene_logic_task_wait_seconds` | Timer | — | 逻辑任务从投递到开始执行的排队等待（经 `SceneNode.runOnLogic` 投递的全部任务；同步的目录 / 归属快照不计） |
| scene | `xm_scene_logic_task_run_seconds` | Timer | — | 逻辑任务的执行耗时（抛异常的也计） |
| scene | `xm_scene_tick_seconds` | Timer | — | 一帧的耗时（外推 + 视野刷新 + 广播），20 FPS、每帧一次；超过 50ms 桶即超预算 |
| scene | `xm_scene_broadcast_seconds` | Timer | `kind`=view_changes / attribute_sync | 帧内广播阶段（组包、序列化、交给链路）：view_changes = 47 / 64，每帧一次；attribute_sync = 66，偶数帧一次（均为全部场景合计） |
| scene | `xm_scene_moves_total` | Counter | `result`=accepted / clamped / corrected / invalid / frozen / in_battle | 移动上行（134 / 132 / 131）的裁决，每条恰好计一次：原样接受 / 超额度截断但偏差 ≤ 0.5m / 截断且回了 137 / 含非有限值或坐标超出世界范围（±1e7 m）丢弃 / 跨节点换图冻结中静默丢（§8.1）/ 回合制战斗在途（134 / 132 静默丢，131 只清速度，§4.23） |
| scene | `xm_scene_skill_releases_total` | Counter | `result`=ok / unknown_skill / invalid_target / cooldown / uninterruptible / state_rejected / caster_in_battle / target_in_battle | 放技能（84）的裁决，每条恰好计一次（§4.9）；不带技能号（客户端可控）与玩家号。后两个取值（批次 6.3）按回码拆开：施法者在回合制战斗中 7004 / 指定的目标在战斗中 7002 |
| scene | `xm_scene_skill_interrupts_total` | Counter | — | 放技能打断了进行中的施法（推了 33）的次数 |
| scene | `xm_scene_aoi_changes_total` | Counter | `change`=enter / leave | 视野变化通知，一对（观察者, 目标）计一次：enter = 进场的 47 条目与给旁人的 21、帧内 47 条目；leave = 离场 / 换场景的 51、帧内 64 条目。离场者自己的列表静默清空，不计 |
| scene | `xm_scene_storage_writes_seconds` | Timer | `op`=save / release / progress / handoff / probe，`result`=released / saved / fenced / failed / rejected / handed_off / lease_too_short / not_committed / lost | 玩家数据写（写回并释放 / 只释放 / 在线存盘 / 交出 / 交出探测，§7 第 6 步）的结局与耗时（含瞬时故障重试），每个写任务恰好计一次：已落库并释放 / 已落库未释放（在线存盘）/ 围栏拒绝（不是故障）/ 重试用尽或非瞬时故障 / 存储线程池拒绝（耗时记 0）/ 已交出（含探测认出自己的提交）/ 剩余租约不足没提交 / 探测确认没提交 / 探测判定不了或读到已失去。只注册各 op 可能出现的组合：save / release = released / fenced / failed / rejected，progress = saved / fenced / failed / rejected，handoff = handed_off / lease_too_short / fenced / failed / rejected（failed / rejected 是结局不明、交给探测，不算写丢失），probe = handed_off / not_committed / lost / rejected。停服时 `shutdownNow` 丢弃的写只进 ERROR 日志 |
| scene | `xm_scene_periodic_saves_total` | Counter | `result`=written / unchanged / in_flight / deferred | 周期存盘对每个到期玩家的处理，每人每次到期恰好计一次：有变化提交了在线存盘 / 与上次落库相同跳过 / 上一次还在途跳过 / 存储线程池积压（排队 ≥ 线程数 × 2）推到下个周期 |
| scene | `executor_*{name="scene-storage"}` | Micrometer 标准线程池指标 | — | 存储线程池排队（`executor_queued_tasks`）/ 剩余容量 / 活跃 / 完成数 |
| scene | `xm_scene_gate_links` | Gauge | — | 接入本节点的 gate 链路连接数（含握手中） |
| scene | `xm_scene_link_frames_total` | Counter | `direction`=in / out，`type`=链路帧类型 | gate ↔ scene 链路帧（in = 从链路收到，含握手帧；out = 已交给链路写出，含 hello_ack） |
| scene | `xm_scene_team_follow_total` | Counter | `result`=followed / same_scene / not_in_team / projection_missing / not_member / leader_not_on_node / leader_scene_draining / switching / is_leader / stale / read_error / in_battle / battle_lock | 进场 / 换场景后的组队跟随检查（§4.16；基线只有 team_follow_skipped 日志行）。批次 6.3：in_battle = 自己有战斗冻结；battle_lock = 自己的战斗锁存在或读锁失败（fail-closed），都不跟随 |
| scene | `xm_scene_channels{state}`、`xm_scene_channel_plan_applies_total{result}`、`xm_scene_channel_plan_poll_failures_total`、`xm_scene_channel_relocations_total{result}` | Gauge / Counter | `state`=active / draining；`result`=applied / rejected / skipped_lease，或 same_map / default_world / blocked / enter_redirect / switching / in_battle | 本节点频道数（批次 5.3 起只计主世界频道，实例另见 `xm_scene_instances`）、计划应用、拉计划失败、排空改派（§4.19；switching = 玩家在跨节点换图的冻结中，这次不改派；in_battle = 玩家在回合制战斗中，这次不改派、下次推进再看，§4.23） |
| scene | `xm_scene_instances` | Gauge | `kind`=mirror / dungeon，`state`=active / reclaiming / draining | 本节点的镜像 / 副本实例数（§4.21）：承载中 / 回收宽限中 / 级联或显式销毁排空中；逻辑线程在变化后推绝对值，6 个组合启动即注册 |
| scene | `xm_scene_instance_lifecycle_total` | Counter | `kind`=mirror / dungeon，`event`=created / rejected / reclaim_started / revived / cascade_started / destroyed_idle / destroyed_cascade / destroyed_admin | 实例生命周期事件：建好 / 建实例被拒（不建、记 ERROR）/ 空置满超时进入回收宽限 / 宽限中复活 / 源已销毁转级联 / 三种销毁。勾稽：每个 created 最终恰好对应一个 destroyed_*（或停服）；`mirror_resolves{created}` = `instance_lifecycle{kind="mirror",event="created"}` |
| scene | `xm_scene_mirror_requests_total` | Counter | `result`=accepted / bad_source / bad_mirror_config / source_draining / not_accepting / node_cap / creator_cap | 63 镜像分支的同步结局，每条镜像请求恰好计一次（3014 / 全 0 在分支之前，不计）：受理回 `{0}`，其余各回 3005 |
| scene | `xm_scene_mirror_resolves_total` | Counter | `result`=created / rejected / error / stale / wrong_node / source_moved / create_rejected | 镜像取号结果回到逻辑线程后的结局，每次受理恰好计一次：建好换入 / scene-manager 拒绝（23 {3023}）/ 调用失败、超时、租约无效、没装配（23 {1003}）/ 玩家已离开或在途已作废（丢弃，号作废）/ 号落在别的节点（23 {3023}，不该出现）/ 玩家已不在源、源已不在或在排空、停止接客、达上限（23 {3023}）/ 本地重号或建实例被拒（23 {3023}）。勾稽：`mirror_requests{accepted}` = Σ`mirror_resolves` + 在途数 |
| scene | `xm_scene_admin_requests_total` | Counter | `op`=instance_create / instance_destroy / other，`status`=HTTP 状态码 | scene 管理端口 `/admin/**`（dev / test 建 / 毁副本，§4.21）的调用，鉴权失败也计；首次用到时才注册 |
| scene | `xm_scene_switch_resolves_total` | Counter | `result`=local / remote / same / rejected / error / stale / in_battle | 63 远端去向经 scene-manager 选目标的结果（§8.1），每次恰好计一次（本节点直接解析掉的 63 不计）：落在本节点本地换 / 别的节点（冻结、交出）/ 就是当前场景 / 拒绝或指向本节点却不在、在排空（23 {3023}）/ 调用失败、兜底超时、应答残缺（23 {1003}）/ 回来时实例已离开或令牌不符（丢弃）/ 玩家在回合制战斗中（批次 6.3，§4.23）：选目标期间进了战斗（例如迟到的确认按锁重建了冻结），结果回来时中止换图、推 23 {3023}；发起交出的入口 `beginRemoteSwitch` 遇到在途玩家也计在这里（63 已先回 3023，那一支只是纵深防御） |
| scene | `xm_scene_transfers_total` | Counter | `result`=handed_off / lease_too_short / fenced / aborted_in_place / lost_unknown / left / taken_over / link_gone | 跨节点换图交出的结局（§8.1 结局表），每次冻结恰好终结一次。勾稽：`switch_resolves{remote}` ≈ Σ`transfers` + `transfers_in_flight`；handed_off 在 `PlayerTransfer` 交给链路时就计，之后异步写失败另计 `link_dropped{write_failed}` |
| scene | `xm_scene_transfer_freeze_seconds` | Timer | — | 从冻结到交出结局处理完的时长（与 `transfers` 同时记）；桶 5ms～20s |
| scene | `xm_scene_transfers_in_flight` | Gauge | — | 冻结中（交出在途）的玩家数，逻辑线程在变化后推绝对值 |
| scene | `xm_scene_transfer_enters_total` | Counter | `result`=ok / failed | 目标节点上交出进场（`PlayerEnter.transfer = true`）的结果；failed = 回了失败的进场结果（场景不在、停止接客、epoch 不符、加载 / 初始化失败），被离开 / 断链 / 接管 / 停服取消的不计 |
| scene | `xm_scene_frozen_rejections_total` | Counter | `kind`=request / asset_op / move | 冻结闸在入口挡掉的操作（§8.1）：REJECT 回 1005 / 资产通道未见 seq 回 RETRY 27003 / 移动上行静默丢。GATED 方法由服务闸回的基线码不计 |
| scene | `xm_scene_transfer_post_freeze_mutations_total` | Counter | — | 交出提交后发现冻结期间状态被改过（`toSave()` 与冻结快照不同）：**应恒为 0**，非 0 说明有入口漏了冻结闸，要告警 |
| scene | `xm_scene_link_dropped_total` | Counter | `reason`=link_gone / write_buffer_full / write_failed | 没发出去的 scene → gate 链路帧：链路已注销 / 已断开；出站缓冲越过高水位（随即断链）；交给链路后异步写失败（目前只有 `PlayerTransfer` 挂了写结果，源节点据此释放新 epoch） |
| scene | `xm_scene_link_backpressure_pauses_total` | Counter | — | 逻辑线程积压到 `link-max-pending-frames`、暂停读取某条链路的次数（§4.2 背压） |
| scene | `xm_scene_gain_block_entries` | Gauge | — | 本节点当前生效的全服产出封禁条目数（§4.6） |
| scene | `xm_scene_gain_block_sync_age_seconds` | Gauge | — | 距上次成功从 Redis 同步封禁名单的秒数；同步一直失败时持续增长（名单可能过时，要告警） |
| scene | `xm_scene_gain_block_sync_failures_total` | Counter | — | 封禁名单同步失败次数（沿用上次的名单） |
| scene | `xm_scene_gain_blocked_total` | Counter | `category`=currency | 被全服产出封禁拒绝的获取 |
| scene | `xm_scene_gain_anomalies_total` | Counter | `category`=currency，`currency_type`=币种号 | 获取异常告警：滑动窗口内次数或累计量越线，每次越线计一次（玩家号只进日志 `xm.audit.anomaly`） |
| scene | `xm_scene_battle_prepares_total` | Counter | `result`=ok / invalid / not_here / switching / in_battle / not_ready / dead / lock_held / redis_error / stale / cancelled | 回合制战斗备战的结论（批次 6.3，§4.23；本行起到 `gate_rejects` 的 scene 战斗计数都在构造时预建全部组合）：invalid 含期限超界；not_here 含组快照取不到路由；四种 1006 分开计（换图在途 / 已有冻结 / 恢复未就绪·账本未落盘·账本损坏 / 气血 0）；lock_held = 锁被占；redis_error = 写锁出错或超时（1003）；stale = 写锁回来时实例已换；cancelled = 写锁在途时收到取消 |
| scene | `xm_scene_battle_cancels_total` | Counter | `result`=cleared / idempotent / mismatch / rejected_fighting / deferred / offline_deleted / offline_rejected_fighting / offline_absent / offline_error | 取消备战的结论：在线解冻 / 在线没有冻结 / battle_id 不符或参数为 0 / 已 FIGHTING 拒绝 / 写锁在途延后；后四个是玩家不在本节点时那段 Lua 的结局 |
| scene | `xm_scene_battle_confirms_total` | Counter | `result`=upgraded / idempotent / reextended / mismatch / rebuilt / frozen_extended / offline_extended / offline_miss / ledger_hit / invalid / error | 开局确认的结论。**不是每条确认恰好计一次**：error 既计确认脚本失败，也计升级 / 再续之后的续锁失败，后者与同一条确认已计的 upgraded / reextended 叠加。rebuilt = 迟到确认按锁重建；frozen_extended = 交出冻结中只标锁 |
| scene | `xm_scene_battle_rebuilds_total` | Counter | `reason`=login / late_confirm / carried，`result`=rebuilt / reverted / ledger_hit / skipped_corrupt / skipped_pending / miss / error | 按锁重建冻结（进场恢复 / 迟到确认 / 同 epoch 沿用旧实例）。login 时锁指向的局已在待结算记录里就不重建，分三种：skipped_corrupt（坏字段）/ ledger_hit（本轮刚应用或账本命中——离线结算后登录的常态）/ skipped_pending（记录被延后或还没轮到）；reverted = 复核没命中而撤销，也计「按锁重建出备战冻结之后，那把备战锁被本节点在途的删除删掉、冻结一并摘掉」（一律记在 login 下，可与同一个冻结此前已计的 rebuilt 叠加）；error = 复核脚本失败（冻结保守保留） |
| scene | `xm_scene_battle_reconnect_hints_total` | Counter | `trigger`=confirm / late_confirm / login / carried | 推 144（重连提示）的来由；同一个冻结对象上恰好一次。carried = 同 epoch 沿用旧冻结时当场推的：换了会话，或会话没变而这一局的 144 还没推过（按锁重建后复核还在途） |
| scene | `xm_scene_battle_frozen` | Gauge | `state`=preparing / fighting | 当前战斗冻结中的玩家数（reaper 每轮数一遍）。fighting 长时间不降要告警 |
| scene | `xm_scene_battle_freeze_expired_total`、`xm_scene_battle_rescues_total` | Counter | 前者 `phase`=preparing / fighting；后者 `result`=applied / already_applied / deferred / miss / error / gave_up | reaper 按期限摘掉的冻结；FIGHTING 判废前读本局待结算记录（rescue）的结局：error = 读出错、下一轮再读，gave_up = 期限 + 60 s 仍读不到、直接判废 |
| scene | `xm_scene_battle_recovery_total` | Counter | `result`=ready / retry / error | 进场恢复的结局：retry = 恢复读失败或有延后的待结算记录（由 reaper 重跑）；error = 读回来了、处理快照时出了意外异常（正常恒为 0，非 0 是代码缺陷，要告警） |
| scene | `xm_scene_battle_rpc_total` | Counter | `method`=prepare / cancel / confirm / settlement，`result`=handled / not_here / deferred / overloaded / error | `SceneBattleService` 提供方的结局（要等 Redis 的分支在结局出来时才计）；overloaded = 在途超限，保证没进逻辑线程 |
| scene | `xm_scene_battle_settlements_total` | Counter | `path`=online / by_lock / login / rescue，`result`=applied / already_applied / discarded_invalid / discarded_mismatch / discarded_void / deferred_frozen / deferred_currency / deferred_recovering / deferred_lock_read / deferred_ledger / not_here | 结算到达的结论，按四条应用路径分：在线冻结匹配 / 没有冻结按锁 / 进场恢复 / rescue。already_applied 含「本实例已销账的局被过期读带回来」。deferred_currency 持续出现 = 该玩家金币入账一直被拒、开不了下一局，要 GM 处置 |
| scene | `xm_scene_battle_acks_total` | Counter | `trigger`=apply / persisted / reaper / login / discard，`result`=released / not_ours / deferred / error | 销账（`ACK` 脚本）：released = 删到了记录或锁；not_ours = 都不是本局；deferred = 还没落盘（压一次存盘）；error = 脚本失败（账本条目保留，之后重试） |
| scene | `xm_scene_battle_items_total`、`xm_scene_battle_ledger_evictions_total`、`xm_scene_battle_pending_corrupt_total`、`xm_scene_battle_exp_ignored_total` | Counter | 第一个 `kind`=consume_clamped / consume_rejected / drop_overflow / drop_lost；其余无标签 | 结算道具的异常路径（消耗按持有夹紧 / 非战斗道具拒扣 / 掉落改投临时格 / 两处都放不下丢失）；账本满 64 淘汰最旧项；待结算记录里的坏字段；结算里的经验（两版都没有经验系统，只记日志）。drop_lost、ledger_evictions、pending_corrupt 非 0 要告警 |
| scene | `xm_scene_battle_gate_rejects_total` | Counter | `gate`=enter_scene / skill / attribute / pet / bag_sort / asset / move / default | 回合制战斗在途闸挡掉的操作（§4.23），每条被拒的请求恰好计一次、只计在自己的闸上：63 / 84（施法者与目标）/ 属性六写 / 宝宝六写 / 192 / 资产通道 debit·credit / 移动上行 / 缺省 REJECT。定义在 `SceneMetrics`（各服务闸共用） |
| battle | `xm_battle_rooms` | Gauge | — | 本节点的房间数（房间表 hooks 维护的原子量） |
| battle | `xm_battle_room_creates_total` | Counter | `result`=ok / idempotent / invalid / fingerprint_reject / engine_reject / ticket_failed / not_allocatable | 建房结局，每次 createBattle 恰好计一次：新建 / 幂等命中 / 1005 / enforce 指纹不符 1006 / 引擎拒绝 1002 / 预签失败 1003 / 节点级拒绝（准入闸、在途超限，由控制面计） |
| battle | `xm_battle_room_ends_total` | Counter | `reason`=finished / deadline / destroyed / aborted | 房间结局，每间房移除时恰好计一次：分出胜负 / 整场期限强制平局 / DestroyBattle / 停机作废 |
| battle | `xm_battle_fingerprint_mismatch_total` | Counter | `mode`=warn / enforce | 战斗配表指纹不一致（warn 放行、enforce 拒绝；基线日志 battle_table_fingerprint_mismatch / reject） |
| battle | `xm_battle_rounds_total` | Counter | `trigger`=timer / all_ready / auto_flip | 回合结算的触发方式：窗口到期 / 149 使全员就绪（含全自动房间里的合法提交）/ 162 开自动造成翻转 |
| battle | `xm_battle_round_resolve_seconds` | Timer | — | 逻辑线程上一次「结算 + 广播」的耗时（桶 0.1ms～1s） |
| battle | `xm_battle_direct_connections` | Gauge | — | 直连数（含未握手） |
| battle | `xm_battle_handshakes_total` | Counter | `result`=ok / repeat / ticket_hmac_mismatch / ticket_payload_parse_failed / empty_identity / node_mismatch / instance_mismatch / expired / role_invalid / ticket_not_in_roster | 握手结局，每个握手包恰好计一次；repeat = 已验证的连接再握手（回旧 battle_id）；其余取值即基线采样日志的 reason |
| battle | `xm_battle_client_requests_total` | Counter | `method`=SubmitBattleAction / GetBattleState / SetAutoBattle / StopWatchBattle / other，`result`=ok / business_error / oversized / rate_limited / not_allowed / bad_body | 已验证直连上的每条 `ClientRequest` 恰好计一次：应答无错误码 / 应答体带业务 tip / 信封 1010 / 信封 1008 / 白名单外信封 1005 / 体解析失败信封 1005（后四种同时计非法包） |
| battle | `xm_battle_invalid_frames_total` | Counter | `reason`=invalid_length / checksum / invalid_name_len / unknown_type / parse | 解码层非法帧（随即断开，不回包；同 gate） |
| battle | `xm_battle_disconnects_total` | Counter | `reason`=handshake_timeout / handshake_rejected / request_before_verify / illegal_packets / write_buffer_full / invalid_frame / at_capacity / replaced / battle_closed / shutdown | 服务端主动断开的直连，每条连接至多计一次；replaced = 同一玩家的新直连顶替旧连接，battle_closed = 房间收尾 / 作废 / 退出观战 / 清退观众的优雅关闭 |
| battle | `xm_battle_pushes_total` | Counter | `category`=lobby / battle_frame，`route`=direct / via_gate / dropped，`message`=NotifyBattleAssigned / NotifyBattleStart / NotifyTurnResult / NotifyBattleEnd / NotifySpectateState / NotifySpectateTurnResult / NotifySpectateEnd | 房间推送的出口，每个收件人每条消息恰好计一次：大厅公告走直连或经 gate 回落，战斗帧走直连或丢弃（基线 battle_frame_dropped_no_direct） |
| battle | `xm_battle_lobby_push_outcomes_total` | Counter | `outcome`=sent / offline / gate_unreachable / error | 大厅公告经 gate 回落（`PlayerPushes.pushAllToPlayer`）的结局；error = Redis 故障、在线目录条目损坏等 |
| battle | `xm_battle_tickets_total` | Counter | `path`=create / observer / reissue，`result`=ok / failed | 签票：建房预签 / 观众（含幂等重签）/ 补签 |
| battle | `xm_battle_scene_events_total` | Counter | `kind`=confirm / settlement，`result`=logged / sent / rerouted / not_here / skipped / error | battle → scene 的确认事件与结算。批次 6.3 接上真实传输之后：确认每次必有一个计数结局——发往快照路由的实例计 sent，实例不符回落到定位器、发往玩家现在的实例计 rerouted，定位不到不发计 skipped；应答是 NOT_HERE 另计 not_here，传输失败 / 回调里的意外另计 error。结算 `result=sent` 表示房间把这份结算交给了发件箱（落库、投递、重投的结局看下面三行），dev 房间的结算计 skipped。logged 只在换回日志端口（测试）时出现 |
| battle | `xm_battle_results_total` | Counter | `channel`=plain / activity，`result`=logged / sent / error | 对局结果事件（只在真打完的局发），每次发布恰好计一次。批次 6.4 起发布端口是 Kafka 实现：sent = Kafka 已确认，error = 没能确认（完整字节已写进兜底日志 `xm.battle.result.fallback`）；logged 只在换回日志端口（测试）时出现。批次 6.3 起活动局的首发与每次重发计 `channel=activity`，plain 只统计普通局 |
| battle | `xm_battle_result_events_total` | Counter | `result`=sent / fallback / not_verified | 对局结果事件在 Kafka 传输上的结局（批次 6.4，§4.22），每次发布恰好计一次、不分通道：sent 与上一行的 sent 相等，上一行的 error = fallback + not_verified。fallback = 队列满 / 发送失败 / 投递失败 / 停机没发完；not_verified = topic 还没核对通过（Kafka 不可达、分区契约不符）——持续增长说明一直没接上，评分不更新，要告警 |
| battle | `xm_battle_settlement_outbox_total` | Counter | `event`=stored / not_durable / delivered / resend / skip_no_target / superseded / acked / exhausted / exhausted_offline / expired / probe_error / locate_error / serialize_failed / already_settled / fields_overflow（15 个，全部预建） | 结算发件箱（批次 6.3，§4.23）：stored 只在落库脚本真的写入时计；落库时这一局已销账（墓碑在）计 already_settled，两者互斥；delivered 是发出次数（首投 + 每次重投），首投数 = delivered − resend；acked = 探测到记录已不在（scene 已销账）；superseded = 玩家离线且锁已属于别的局；exhausted（有目标仍重投用尽）对 exhausted_offline（玩家离线的常态）；expired = 条目一直探测 / 定位出错、超过 10 min 摘除；fields_overflow = 落库后该玩家的待结算字段数超过 16（记录在堆积）。not_durable、exhausted、expired、fields_overflow 非 0 要告警 |
| battle | `xm_battle_settlement_outbox_entries` | Gauge | — | 结算发件箱里登记的条目数（发件箱线程推绝对值） |
| battle | `xm_battle_settlement_delivery_total` | Counter | `result`=applied / already_applied / discarded / deferred / not_here / overloaded / transport_error | 一次结算投递的应答；只计数、不改发件箱状态（销账以 scene 落盘为准） |
| battle | `xm_battle_activity_result_outbox_total` | Counter | `event`=stored / not_durable / resend / acked / exhausted / expired / probe_error / serialize_failed | 活动结果持久通道（§4.23）；消费方两版都还没有，线上不可达 |
| battle | `xm_battle_dev_gather_total` | Counter | `mode`=prepare_only / create / unknown，`result`=ok / prepare_failed / create_failed / forbidden / rejected | dev / test 管理接口 `dev/gather` 的结局（§4.22）：unknown = 解析请求体之前就被拒；forbidden = 运行模式不是 dev / test；rejected = 请求体非法或节点没在运行 |
| battle | `xm_battle_rpc_seconds` | Timer | `method`=createBattle / destroyBattle / issueBattleTicket / addObserver / removeObserver，`result`=ok / business_error / not_allocatable / error | 控制面提供方从收到到 future 完成的耗时（含逻辑线程排队，桶 1ms～1s）；error = 投递被拒、在途超限、处理中抛异常（future 异常完成） |
| battle | `xm_battle_logic_pending_tasks` | Gauge | — | 逻辑线程（兼直连 I/O）的任务队列长度 |
| battle | `xm_battle_admission_phase` | Gauge | — | 建房准入闸阶段：0 not_started / 1 open / 2 closed（本机切片据它等就绪） |
| battle | `xm_battle_lease_lost_total` | Counter | — | 节点号租约丢失（关闸、停发布、不作废房间，§6） |
| match | `xm_match_requests_seconds` | Timer | `method`=MatchService 的方法名 / unknown，`result`=ok / failed / overloaded / bad_request / unsupported / error | 每个客户端请求从受理到应答的耗时与结局（含工作队列排队；批次 6.4，§4.24；本行起 match 的常见标签组合都在启动时预建为 0）：ok = 处理器回了应答体（含 in-band 的业务拒绝与 in-band 16004）；failed = 处理器回了信封（148 / 153 的依赖故障，6.5 起还有 164 读索引失败）；overloaded = 工作池满或排队超预算（6.5 起 163 是在途已满或轮到执行时预算已用完）；bad_request = 请求体解析失败；unsupported = 不认识的号（`method`=unknown）；error = 处理器抛了未分类的异常。业务上的细分看下面各功能自己的计数 |
| match | `xm_match_join_queue_total` | Counter | `mode`=`MatchMode` 枚举名 / unknown，`outcome`=ok / in_battle / already_queued / mode_not_open / no_team_size / not_in_scene / internal / overloaded | 排队 157 的出口（同基线 match_join_queue_total；overloaded 是 Java 新增，工作池过载时记 `mode`=unknown）。发号租约丢失时派发层的拒收计 internal |
| match | `xm_match_queue_depth`、`xm_match_starved_anchor_wait_seconds` | Gauge | `mode`，`config`=0 / Dungeon 表里的副本号 / other | 队列长度；本轮凑不到候选的锚点里等得最久的秒数（0 = 没有，持续上升 = 有人在饿）。只有持有凑单锁的实例写实值，抢不到锁的实例把自己那份置 0，看板按实例求和；凑单暂停的轮次不动它（暂停期间最后一次的读数会滞留） |
| match | `xm_match_wait_seconds`、`xm_match_group_rating_spread` | 分布（固定桶） | `mode` | 成组时锚点已等的秒数（桶 1 / 2 / 5 / 10 / 20 / 30 / 45 / 60 / 120 / 300）；评分模式成组时组内最高分与最低分之差，单位评分点（桶 25 / 50 / 100 / 200 / 300 / 500 / 800 / 1000 / 1600；基线最前面的 0 桶去掉了，Micrometer 不接受 ≤ 0 的桶边界） |
| match | `xm_match_matcher_rounds_total` | Counter | `result`=ok / paused_no_battle / paused_no_lease / paused_saturated / error | 凑单每轮的结局：三种暂停都在抢锁之前判（battle 目录没有可分配的条目或读失败 / 发号租约无效 / 开局许可已满），队列原样保留；error = 注册集读失败、本轮有队列因故障提前结束或整轮抛了意外异常。paused_* 持续增长 = 这段时间排队的人都成不了局（进程日志每 10 s 告警一次） |
| match | `xm_match_requeued_total`、`xm_match_queue_dropped_total`、`xm_match_queue_anomalies_total` | Counter | 依次 `reason`=gather_offender / gather_no_offender；invalid / in_battle / offline；missing_score / repick_exhausted | 开局失败后回队首的人数（有肇事者时的幸存者 / 无肇事者时的全员）；凑单剔出队列的成员数（票据无效 / 排队期间进了别的战斗 / 已登出，存储确认摘掉了才计；凑不满队列的残项清理也计 invalid）；队列数据异常（评分镜像缺分 / 弹组被拒后的重挑次数用完），长期非 0 要排查 |
| match | `xm_match_gathers_total`、`xm_match_gather_seconds` | Counter / Timer | `mode`，`outcome`=success / internal / overloaded / no_battle_node / no_location / prepare_failed / fingerprint_mismatch / index_failed / not_allocatable / create_rejected / create_not_sent / create_failed / create_failed_room_alive | 开局管线的结局与耗时（从启动到结束，失败的含补偿；同基线 match_gather_total，overloaded / create_rejected / create_not_sent 是 Java 新增）。no_location / prepare_failed / fingerprint_mismatch 有肇事者，其余没有。create_failed_room_alive = 建房结局不明且销毁也失败，全员冻结到备战期限（最坏的一支，出现就要排查）；create_not_sent 成串出现 = 有 battle 节点刚死、目录条目还没过期 |
| match | `xm_match_gathers_inflight`、`xm_match_battle_nodes` | Gauge | 前者无标签；后者 `state`=accepting / not_accepting | 在途的开局数（上限 `gather-max-inflight`，不含过载收尾的）；battle 节点目录概况（凑单每轮刷新，读失败时两个数都清零）。accepting 为 0 时凑单暂停 |
| match | `xm_match_gather_zone_mix_total`、`xm_match_table_fingerprint_mismatches_total` | Counter | 前者 `mode`、`mix`=single / cross；后者 `fp_mode`=warn / enforce | 一局的成员同区还是跨区；成员之间战斗配表指纹不一致的组数（任何非 0 都说明有 scene 节点跑着不同版本的战斗表） |
| match | `xm_match_battle_ticket_reissues_total` | Counter | `result`=ok / rejected / not_found / no_session / internal / rpc_error / rpc_timeout / instance_changed | 补签 179 的出口：rejected = battle 核对名单后拒签（含它回的「房间不存在」）；not_found = 没有落点记录（1005）；instance_changed = 判死的三条证据都成立（1005）；rpc_timeout（超时、剩余预算不够拨号）与 rpc_error（其余没调通）回 1003；工作池过载不计 |
| match | `xm_match_challenges_total` | Counter | `stage`=invite / respond；`result`：invite 取 ok / internal / self / self_busy / target_busy / target_offline / pending / push_failed / overloaded，respond 取 internal / expired / not_target / declined / challenger_busy / responder_busy / accepted / overloaded | 切磋 152 / 151 的各出口（同基线，overloaded 是 Java 新增） |
| match | `xm_match_pushes_total` | Counter | `kind`=156 / 154，`outcome`=sent / offline / gate_unreachable / error | 切磋推送的结局（至多一次，sent 不代表客户端收到）；error = 推送的 stage 异常完成（Redis 故障、在线目录条目损坏） |
| match | `xm_match_activity_battles_total` | Counter | `kind`=guild_trial / none / unknown，`result`=started / invalid / offline / in_battle / not_ready / internal / gather_ok / gather_failed | 帮会活动开战：每个请求恰好一个同步出口（前六个），started 之后异步开局的终态另计 gather_ok / gather_failed |
| match | `xm_match_team_calls_total` | Counter | `method`=checkTeamMatch / createTeamTickets / releaseTeamTickets / runTeamGather；`result`：check 取 ok / dungeon_not_open / size_exceeded / member_offline / member_in_battle / member_not_ready / internal / overloaded，create 取 created / failed / expired / overloaded / error，release 取 ok / overloaded / error，gather 取 gather_ok / gather_failed | 整队开战端口各方法的结果（xm-team 一侧的终态见 `xm_team_matches_total`）。expired = 这次建票没有执行、什么都没写（到达时预算已过期、租约无效、参数不合法） |
| match | `xm_match_rating_updates_total`、`xm_match_rating_round_cap_draws_total` | Counter | 前者 `mode`、`outcome`=applied / duplicate / ignored / error / decode_error / rejected；后者 `mode` | 一条对局结果的入账结局（没有基线的 partial：一局一笔事务）；因回合打满被改按平局结算的计分局数。ignored = 不计分的局（PVE、切磋、活动局的每次重发）；error = 一次入账尝试失败，可恢复故障每次重试都计，本身不代表丢数据；**decode_error（解不出，`mode`=unknown）与 rejected（被数据库判为数据错误）是仅有的两种「一条结果被永久丢弃」，任何增量都该告警** |
| match | `xm_match_rating_consumer_paused`、`xm_match_lease_lost` | Gauge | — | 评分消费者因可恢复的库故障暂停中（1 = 暂停：评分整体停更，排队照常；持续为 1 要告警）；发号租约是否已真正丢失（1 = 不会自愈，开局类入口与排队一律拒，要重启进程） |
| match | `xm_match_admin_requests_total` | Counter | `op`=rating / activity_battle / other，`status`=HTTP 状态码 | 管理端口 `/admin/**` 的调用（含鉴权失败），dev 管理口的审计计数 |
| match | `xm_match_watch_battle_total` | Counter | `outcome`=ok / internal / queued / in_battle / already_watching / offline / no_battle / not_found / rejected / overloaded | 观战 163 的出口（批次 6.5，§4.24.1；同基线 watch_battle_total，overloaded 是 Java 新增；本行起六行的全部标签组合同样在启动时预建为 0）：进了处理流程的请求恰好记一个。internal = 16004（身份缺失、依赖故障、流程内剩余预算不够发下一跳）与未预期异常；overloaded = 在途已满或轮到执行时预算已用完（没进流程）；queued = 16014 的三个出口（入口持票、抢标记时发现有票、登记后复查命中票据或战斗锁）；already_watching **只计 16016**（基线把入口处收拾旧标记也算进去）；not_found / rejected 是 16018 的两种文案（「不存在或已结束」/「当前无法观战」）。请求体解析失败不计（只进 `xm_match_requests_seconds{result="bad_request"}`） |
| match | `xm_match_list_watchable_total` | Counter | `result`=ok / error / overloaded | 可观战列表 164 的出口：ok = 回了列表（含空列表与批读落点失败后变短的列表）；error = 读索引失败或未预期异常（信封 1003）；overloaded = 工作池满或排队超预算（信封 1003）。请求体解析失败不计 |
| match | `xm_match_spectate_evictions_total` | Counter | `reason`=enter_gather / rewatch / concurrent_queue，`result`=removed / no_record / invalid_mark / read_failed / rpc_failed | 清退观众的结局，每处理一条观战标记记一个（基线只有日志）：enter_gather = 开局前清退；rewatch = 163 入口收拾旧标记（换场 / 随机 / 脏标记；显式重看同一场只删标记、不计）；concurrent_queue = 163 的自我清退（登记后复查命中，或重看同一场时抢标记发现已有票据），在异步 RemoveObserver 的结局回来时才记。removed = RemoveObserver 调通（battle 幂等，房间里没有这名观众也算）；no_record = 没有落点或直拨判死；invalid_mark = 标记值解析不了；read_failed = 读落点失败或损坏（不发 RPC）；rpc_failed = 没送达或结局不明（开局清退时标记照删） |
| match | `xm_match_watchable_index_evictions_total`、`xm_match_watchable_anomalies_total` | Counter | 前者 `reason`=room_missing / dead_node / stale / missing_record / invalid_member / sweep；后者 `reason`=corrupt_record / record_read_failed / publish_failed / mark_read_failed | 从可观战索引里摘掉的成员数：163 的同步剔除（room_missing / dead_node / missing_record / invalid_member）只在存储回报真的摘了时计，164 的异步剔除（invalid_member / stale / missing_record）按发出的条数计，sweep 按清扫脚本返回的条数计。观战数据异常：corrupt_record = 163 / 164 读到损坏的落点（跳过、不剔除）；record_read_failed = 164 批读落点失败；publish_failed = 开局后登记索引失败（抛异常，或落点不在 / 已不是这一次的 attempt）；mark_read_failed = 开局清退读全员标记失败（整次跳过清退）。长期非 0 要排查 |
| match | `xm_match_observer_rpc_total` | Counter | `method`=add / remove，`result`=replied / dead / not_delivered / unknown | 发给 battle 的观众 RPC（`addObserver` / `removeObserver`）的结局，每次调用一个，异步的自我清退记在 remove：replied = 调通了（含 battle 的业务拒绝）；dead = 判死的三条证据都成立；not_delivered = 请求确定没送达但判不了死（含硬截止已到、超时 ≤ 0 时不发调用）；unknown = 超时、连上之后断开、对端回了传输层错误 |
| match | `xm_match_watchable_battles`、`xm_match_spectate_inflight` | Gauge | — | 可观战索引的成员数（清扫每轮采样一次 ZCARD，含已结束不足 360 s 的场次；第一轮在一个间隔之后，所以启动后头 10 s 读数为 0，采样失败时停在上一次的读数。**每个实例各自采样同一把全服 ZSET，多实例时看板与告警取 max、不能求和**）；在途的 163 数（上限 `spectate.max-inflight`；每实例的真实值，求和才有意义） |
| match | `executor_*{name="match-worker"}`、`{name="match-db"}`、`{name="match-push"}` | Micrometer 标准线程池指标 | — | 请求工作池（队列满见 `xm_match_requests_seconds{result="overloaded"}`）/ 读评分的池 / 推送回调执行器 |

未覆盖（后续）：Druid 连接池指标（Spring Boot 只认 Hikari / DBCP2 / Tomcat 等连接池的元数据）；
Dubbo 与 Redisson 自带指标未接入；scene 的进场加载（成功 / 失败 / 耗时）未单独计，进场失败目前只有 WARN 日志。
