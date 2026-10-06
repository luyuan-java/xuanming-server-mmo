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
  （服务名 → 后端，首批只有 `ClientPlayerLogin` → login）；表里没有的回「暂不支持」。

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
| `xm-api` | 库 | Dubbo 服务接口、调用方鉴权过滤器（§4.1）与内部 protobuf 消息（包 `xm.api`，只在 Java 版内部使用） |
| `xm-discovery` | 库 | Redis（Redisson）上的节点号租约、游戏节点在线目录、玩家在线目录与服务端推送（含同一会话按序的批量推送 `MessageBatch`，§4.3） |
| `xm-player-store` | 库 | 账号 / 玩家持久化（MyBatis）与玩家归属围栏（含跨节点换图的原子交出与探测，§7 第 6 步） |
| `xm-gateway-store` | 库 | 区服目录 / 白名单 / 登录公告的库表与读写（MyBatis）：xm-gateway 建表并读，xm-data 运维接口写 |
| `xm-audit` | 库 | 资产审计管线的共享件：Java 自有的流水消息格式（包 `xm.audit`）、Kafka topic 规格（带代次后缀）与启动期核对（§4.5） |
| `xm-gateway` | 进程（Spring Boot Web + Dubbo 调用方） | 区服列表（区服目录 + 健康探测）、分配 gate 并签发 gate 令牌（区服准入）、HTTP 登录与刷新令牌（经 Dubbo 调 xm-login）、登录公告 |
| `xm-login` | 进程（Spring Boot + Dubbo） | 登录（含 access / refresh 令牌、设备数上限）、建角、进游戏、离开 / 断线 |
| `xm-friend` | 进程（Spring Boot + Dubbo） | 好友：申请 / 同意 / 拒绝 / 删除 / 列表 / 黑名单与好友事件推送（Dubbo group `friend`，端口 20883；四张表经 xm-pbmysql，§4.13） |
| `xm-chat` | 进程（Spring Boot + Dubbo） | 聊天 v1：世界频道与私聊的发言（校验、幂等、限速、落历史）与拉取历史（Dubbo group `chat`，端口 20884；数据只在 Redis，§4.14） |
| `xm-team` | 进程（Spring Boot + Dubbo） | 组队：建队 / 申请 / 邀请 / 离队 / 踢人 / 转让 / 解散、队伍快照与邀请推送（Dubbo group `team`，端口 20885；权威数据只在 Redis，§4.16） |
| `xm-guild` | 进程（Spring Boot + Dubbo） | 帮会核心：建 / 查 / 退 / 解散 / 公告 / 任免 / 踢人 / 转让 / 申请审批 / 推送 220 / 排行（Dubbo group `guild`，端口 20886；四张表经 xm-pbmysql，快照缓存与排行在 Redis，§4.17） |
| `xm-trade` | 进程（Spring Boot + Dubbo） | 聚宝斋只读面：浏览 196 / 详情 197 / 收藏 198 / 货架 200 + dev 播种（Dubbo group `trade`，端口 20887，管理端口 18111；两张表经 xm-pbmysql，§4.20） |
| `xm-scene-manager` | 进程（Spring Boot + Dubbo） | 场景分配（玩家该进哪个场景节点的哪个**主世界频道**，软预占；断线重连可回原实例）+ 每个 zone 的主世界频道计划（领导者维护，§4.19）+ 在线换图选跨节点目标（`selectSwitchTarget`，只选不铸 epoch，§8.1）+ 镜像 / 副本实例取号（`createInstance`，只发全服 scene_id、放置恒为发起节点，无状态，§4.21） |
| `xm-gate` | 进程（Spring Boot + Netty） | 客户端接入、会话、按消息号路由、下行推送（含按序批量推送，§4.3）；跨节点换图时按 scene 的改绑指令把会话改绑到目标节点（§4.2）。不承载任何战斗上行（战斗走 battle 直连，§4.22） |
| `xm-scene` | 进程（Spring Boot + Netty） | 场景与玩家逻辑（单线程拥有场景状态）；跨节点换图的源端（选目标、冻结、交出归属）与目标端（交出进场，§8.1）；镜像 / 副本实例（节点自有：63 建镜像、空闲回收与级联、dev / test 管理口建 / 毁副本，§4.21） |
| `xm-battle` | 进程（Spring Boot + Netty + Dubbo 提供方） | battle 节点（批次 6.2）：回合制战斗房间的生命周期（驱动 `xm-battle-engine`）、客户端直连面（端口 12000）、票据签发与补签、推送出口（直连 / 经 gate 回落）、准入与停机、观战的房间侧；控制面 `BattleNodeService`（Dubbo group `battle-node`，端口 21200，按节点直连），管理端口 18112（含 dev / test 建房接口）；不用 MySQL（§4.22） |
| `xm-data` | 进程（Spring Boot Web + Dubbo 调用方） | 审计与运维数据服务：消费审计 topic、幂等落 MySQL；带令牌的运维接口：流水查询、GM 快照 / 差异、物品追溯、批量回收 dry-run（§4.5，批次 7.2a），运维作业框架、GM 回档与整区维护前快照（§4.5.1，批次 7.2b），全服产出封禁（§4.6），区服目录 / 白名单 / 登录公告（§7）。运维作业表经 xm-pbmysql。玩家数据：平时只读（自有只读 Mapper）；回档时以运维身份夺取归属、带 `owner_epoch` 围栏写（复用 xm-player-store 的 `PlayerStore` / `PlayerMapper`，§7 第 7 步）。回档的帮会资产检查是 `GuildInternalService` 的 Dubbo 调用方（编程式引用、只直连，写开关关闭时不起 Dubbo） |

依赖方向单向：进程模块 → `xm-api` / `xm-net` / `xm-player-store` / `xm-gateway-store` / `xm-discovery` / `xm-battle-engine` → `xm-common` / `xm-proto` / `xm-table`。

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
  调用本身失败（超时 / 后端不可用）：login 推 23 `{1003}`；friend / chat / team / guild 等后端域回带请求 id 的信封 1003（同基线路由服 forwardlogic，客户端当场按信封错误处理，不必等自己的请求超时）。
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
  共享密钥 `XM_DUBBO_SECRET`（xm-login / xm-scene-manager / xm-friend / xm-chat / xm-team / xm-guild / xm-scene / xm-gate / xm-gateway / xm-battle 必填，缺失即拒绝启动），
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
  不会像基线无 TTL 的会话键那样永远停在 ONLINE；代价是正常离线后续期批次与删除交错时，条目可能多留至多一个 TTL（推送有玩家栅栏兜底）。
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
- **冻结策略**（批次 5.2，§8.1）：每个方法注册时声明 `FreezePolicy`（`SceneFeature.Registrar.on` 的五参数形式；四参数形式缺省 REJECT，
  以后新加而没声明的方法冻结中一律拒绝）。玩家处于跨节点换图的冻结中（FREEZING）时，分发入口在 GM 闸与找处理器之后、调用处理器之前裁决：
  ALLOW / READ_ONLY / GATED 照常进处理器（GATED 由服务闸回基线码），REJECT 回应答内 `error_message{1005}`（`Empty` 应答的静默丢），
  DROP 静默丢。DROP 只有场景核心的移动上行能声明：功能模块声明 DROP、有应答的方法声明 DROP 都启动即失败。选目标中（RESOLVING）不冻结、全部放行。
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
- **批次 6.3 追加**：夺权之后、账本差集之前批量查战斗锁，锁在或读失败的玩家不写（对应基线回档对战斗中玩家回 1005）；代码随 6.3 提交，见 data-ops-spec §13.3。

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
  已最优（按相邻格比较）时什么都不改、changed=false——狂点整理不刷流水、不触发存盘。
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
  `MissionService.onMonsterKilled` 等回合制战斗结算（6.3）接入。纯规则在 `ConditionRules`（比较符、参数匹配、累计封顶 / 持有覆盖）。
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

### 4.10 当前气血 / 法力与基础复活

- **存储**：`player_state.vitals`（`Vitals{health, mana}`，uint64）。满血满蓝时不写，读回来没有这段就按上限回满——上限没被调高时
  与写出结果一样，改表调高上限后满血玩家读档到新上限（基线保留旧绝对值，PARITY「死亡 / 复活」②）；新号与老存档也走这条；不满时写出。
  二级属性（上限）仍每次加载重算，不落库；成长属性不存：护甲 / 速度同基线每次重算（速度取二级属性），力量 / 抗性 / 暴击用到时由职业表即时算出。
- **加载**（`AttributeService.initializeOnLoad`）：存档值按新上限往下夹（旧上限视为 0，同基线 RescaleCurrent：活着的只夹不补），
  再套纯规则 `PlayerRevive.reviveIfDead`：气血 0（阵亡或没有记录）→ 按上限回满；活着（含残血）不动。
- **写入点**：今天只有属性重算会改当前值（升级补增量、其余按比例）；回合制战斗结算的回写与结算复活随 6.3，同用 `PlayerRevive`。
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
- **号与随机**：宝宝号用与物品同一个全服号源（§9）；资质随机数只在逻辑线程上用。写闸：跨节点换图冻结中回 1005（批次 5.2，§8.1），战斗中随 6.3 接入。

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
  或玩家处于跨节点换图的冻结中，回 RETRY 27003，什么都不记——含中止占位；选目标中照常处理，§8.1）→ 中止占位 → 包内容（确定性失败记 REJECTED 27004）
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
  （没有副本开放组队，6.4 接匹配后换真实端口）。
- **场景跟随**（xm-scene `TeamFollowService`）：玩家进场 / 换场景之后，在逻辑线程外读一次成员关系（一段只读 Lua：索引 + 投影），
  回到逻辑线程处理——不是队长且队长在本节点另一个场景实例 → 同步 `switchScene` 到队长所在实例；队长自己进场 → 对本节点其他成员各检查一次
  （只跟随、不再扇出）。跨节点 / 跨 zone 不跟随（同基线）；自己有在途的跨节点换图（选目标中或冻结中）时不跟随（`switching`，§8.1）。不发、不收 scene 刷新事件（scene 不缓存 TeamId，进场时现读）。

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
  的 12 个号一律推 23 {1003}、不计非法包、不断连（`MessageRoutes.SERVICE_BACKENDS` 永不加入它）。battle 直连每局一条，参战与观战共用一个槽，承载握手、
  四条战斗 RPC（149 提交行动 / 140 拉状态 / 162 自动战斗 / 165 退出观战）与全部战斗帧（139 / 150 / 158 / 161 / 166）；没有活直连的战斗帧直接丢弃、不回落 gate。
- **线程**：一条 `battle-logic` 线程（单线程 `NioEventLoopGroup`）既是直连面唯一的 Netty I/O EventLoop，又独占全部房间、直连会话状态（是否已验证、限频器、
  非法包计数、握手定时器）与房间计时器——同基线单 loop，帧顺序与「应答之后才关」不需要跨线程排队。boss 线程 1 条只 accept；Dubbo 提供方线程只读准入闸（原子量）、
  占在途位后投递，future 在 `battle-rpc-reply` 上完成；管理 Tomcat（4 线程）上的 dev 接口做完 Redis 查询后走同一条投递路径。逻辑线程上不做阻塞 I/O，出站端口一律异步。
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
  `ActivityResultSink` / `BattleResultSink`（只在真打完的局发）。6.2 的缺省实现只记日志与计数（`@ConditionalOnMissingBean`，6.3 / 6.4 加 bean 替换成真实传输）；
  dev 房间（`RoomOrigin.DEV`）永不投递结算与结果事件。
- **节点身份、目录与准入**：节点号租约 `NodeTypes.BATTLE`、作用域 0（基线 battle 是全局池，不分 zone），实例 id 是每次启动生成的 UUID；目录与租约丢失见 §6。
  准入闸 `NOT_STARTED → OPEN → CLOSED`（`AdmissionGate`，CLOSED 是终态）。
  - **启动**：门禁（票据密钥策略、`XM_DUBBO_SECRET`、prod 下 `max-connections = 0`、指纹模式与握手期限取值）→ 加载七张战斗表与 `TableBattleData`、解析 4 个上行号与
    7 个下行号（缺号拒启）→ 占租约 → 起线程 → 导出 Dubbo → 绑直连端口 → 在逻辑线程开闸 → 首次发布目录 → 打「节点已就绪」。先开闸后进目录，基线「已发布、未开闸」的窗口不存在。
    actuator 的 health 比就绪早约 2.5 s 报 UP，本机切片等 21200 / 12000 端口、`xm_battle_admission_phase = 1` 与就绪日志。
  - **停机**（只由 Spring 驱动：`BattleApplication` 设 `dubbo.shutdownHook.listenIgnore=true`，否则 Dubbo 自己的 JVM 钩子会在 SIGTERM 时抢先销毁控制面）：
    摘目录 → 在逻辑线程的**同一个任务**里关闸并作废全部房间（观众收 166 ABORTED，参战者不收帧）→ 停监听、有界等待优雅关闭的直连排空（`shutdown-flush-timeout` 1 s，
    修基线观众的 166 被强关吞掉）、强关剩余连接 → 撤 Dubbo 导出（期间进来的 createBattle 回 NOT_ALLOCATABLE）→ 停线程 → 交还租约。
- **dev / test 管理口**（管理端口 18112，`POST /admin/battle/dev/{create,destroy,issue-ticket,add-observer,remove-observer}`，请求 / 应答是契约 protobuf 二进制）：6.4 之前用它端到端验收。
  鉴权同 xm-trade 播种（`BattleAdminAuthFilter`）；运行模式不是 dev / test 回 403（先于解析请求体），请求体坏 400，快照路由补不全 422（gate 字段取在线目录，scene 字段取
  位置记录（只认 `o`）+ scene 目录的实例 id），节点没在运行 / Redis 读失败 503，等结果超过 5 s 回 504。经它建的房间标 `DEV`。
- **存储**：不用 MySQL，房间是纯内存的（节点崩溃 = 在打的战斗全部作废，由 6.3 scene 的 reaper 按期限解冻）；Redis 只写租约与目录，只读在线目录（大厅回落），
  dev 接口另读位置记录与 scene 目录。
- **配置**（`xm.battle.*`）：`client-port` 12000、`client-bind-host` 0.0.0.0、`advertise-port` 0、`client-advertise-host` 空、`rpc-port` 21200、`max-connections` 4096、
  `handshake-timeout` 10 s（客户端可见、不建议改，范围 (0, 60 s]）、`illegal-packet-threshold` 50、`table-fingerprint-mode` warn（off / warn / enforce，写错拒启）、
  `rpc-max-inflight` 256、`shutdown-flush-timeout` 1 s；Redis 库 12。

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
  table_fingerprint}`。读方（6.4 的 match）只从 `accepting = true` 的条目里挑；目录最多滞后 5 s，所以 NOT_ALLOCATABLE 后换节点重试一次仍要保留。
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
  低于客户端 HTTP 超时 5s（Redisson 自带默认值下可达二十多秒）。

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
- **proto 声明的表（xm-pbmysql）**：行本身就是一条 protobuf message 的表（好友、邮件、帮会等社交服务，以及 xm-data 的运维作业表（§4.5）；
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
- Redis：全部键带前缀 `xm:`，默认 DB 12（与 mmorpg 的开发数据隔离）。
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
- battle 节点的实例 id 是每次进程启动生成的 UUID（进票据与目录，同号的新进程不接受旧票）；battle_id 由 match 发（6.4），dev 管理口由调用方给。

## 10. 首批不做（后续批次）

跨 zone、战斗的匹配与 scene 侧冻结 / 结算（随 6.4 / 6.3；battle 节点已于 2026-10-05 补上，见 §4.22）、Kafka 事件、GM 管理接口（远程停机与 xm-data 运维面除外；GM 快照 / 差异 / 物品追溯 / 回收 dry-run 已于 2026-10-05 补上，见 §4.5；运维作业框架、回档与整区维护前快照已于 2026-10-05 补上，见 §4.5.1；回收执行、欠款、精确回收随 7.2c），
服务级限流 / 熔断（Sentinel）、合服与 TiDB 数据层。（周期存盘已于 2026-10-02 补上，短线重连已于 2026-10-04 补上，见 §7；货币与 GM 客户端指令闸见 §4.4；登录排队与开服限流见 §8；
  gate 排空与 GM 签名停机见 §6；同 zone 跨节点换图与归属交接已于 2026-10-05 补上，见 §7 第 6 步与 §8.1——跨 zone 传送仍待做；
  镜像 / 副本实例与空闲回收已于 2026-10-05 补上，见 §4.21。）
（低基数运行指标全部进程都已接入，见 §11。）
顶号已按 §7 第 2 步实现（旧连接收 23 {2017} 后断开，不发 34）。进度逐项登记在 `PARITY.md`。

## 11. 可观测性（指标）

每个进程用 **Micrometer** 记指标，经 **Spring Boot Actuator** 以 Prometheus 文本格式导出（选型见 tech-stack.md）。
指标名与标签只在每个进程的一个类里定义，业务代码只调语义方法：gate `GateMetrics`、login `LoginMetrics`、
scene-manager `SceneDirectoryProvider`、gateway `AssignGateMetrics`、scene `SceneMetrics`、friend `FriendMetrics`、chat `ChatMetrics`、team `TeamMetrics`、guild `GuildMetrics`、trade `TradeMetrics`、data `DataMetrics`、battle `BattleMetrics`。

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

- **非 Web 进程的管理端口**：gate / login / friend / chat / team / guild / trade / scene-manager / scene / battle 的业务端口是 Netty / Dubbo，为管理端点另起一个只挂 actuator 的
  Tomcat（`web-application-type: servlet`，4 个线程，`shutdown: immediate`），这个端口上没有业务接口（trade / scene / battle 另有 dev / test 专用的管理接口，见上表）。
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
  其余标签都是代码里的枚举（scene 管理口的 `status` 是 HTTP 状态码）。
- **延迟**：Timer 用固定的 SLO 桶（gate / login 5ms～10s 共 11 个，scene-manager 1ms～1s 共 8 个，scene 逻辑线程内
  0.1ms～1s 共 12 个、含一帧预算 50ms，scene 存储写 5ms～10s 共 11 个，scene 跨节点换图冻结时长 5ms～20s 共 13 个，battle 逻辑线程上的回合结算 0.1ms～1s 共 12 个、
  battle 控制面 1ms～1s 共 10 个），不开百分位直方图。
- **scene 的线程所有权**（§5）：抓取线程不读场景状态。在线人数由逻辑线程在人数变化后推送绝对值；逻辑线程队列长度、
  gate 链路连接数、存储线程池状态由线程安全的计数（Netty 任务队列、`ChannelGroup`、`ThreadPoolExecutor`）直接读。
- **JVM / 进程 / Tomcat / HTTP 请求**等通用指标由 Actuator 自带（`jvm_*`、`process_*`、`http_server_requests_*` ……）。

业务指标（Prometheus 名；计数器带 `_total`，Timer 导出 `_seconds_count` / `_sum` / `_max` / `_bucket`）：

| 进程 | 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|---|
| gate | `xm_gate_sessions_active` | Gauge | — | 当前会话数（含未握手、正在收尾的） |
| gate | `xm_gate_scene_links` | Gauge | — | 到各 scene 节点的链路数（建链中 + 就绪） |
| gate | `xm_gate_handshakes_total` | Counter | `result`=ok / bad_signature / bad_payload / wrong_gate / wrong_zone / expired / timeout / missing | 令牌握手结果（missing = 没握手就发业务包） |
| gate | `xm_gate_client_requests_total` | Counter | `route`=login / scene / unsupported / unknown，`method`=服务.方法 / unknown，`result`=forwarded / not_in_scene / link_unavailable / unsupported / unknown_message / oversized / rate_limited / gm_rejected / killed / overflow / dropped | 已握手会话上每个请求在 gate 的最终去向，恰好计一次。C++ 的「非法包」= unknown_message + oversized + rate_limited + gm_rejected；限频拒绝 = rate_limited |
| gate | `xm_gate_client_invalid_frames_total` | Counter | `reason`=invalid_length / checksum / invalid_name_len / unknown_type / parse | 解码层非法帧（随即断开） |
| gate | `xm_gate_disconnects_total` | Counter | `reason`=handshake_timeout / handshake_rejected / no_handshake / illegal_packets / pending_overflow / write_buffer_full / invalid_frame / session_id_exhausted / server_directive / kicked / scene_link_down / server_kick / transfer_failed | gate 主动断开的连接（客户端自己断开、停服 / 丢租约的批量关闭不计）；transfer_failed = 跨节点换图改绑之后没能落到目标节点，或改绑指令内容非法（§8.1；源节点探测判失去时经 `PlayerKicked` 断开，计 kicked） |
| gate | `xm_gate_pushes_total` | Counter | `kind`=message / kick，`result`=delivered / no_session / not_bound / stale_instance / invalid | 服务端推送（§4.3）对每个目标会话的结局：已下发 / 会话号已不存在 / 会话不在游戏里或玩家对不上（栅栏）/ 指向别的 gate 实例 / 格式不对。按序批量推送（`MessageBatch`，批次 6.2）计 kind=message、每个目标只计一次：全部送出才算 delivered，空批或任一条损坏算 invalid |
| gate | `xm_gate_backend_calls_seconds` | Timer | `backend`=login / friend / chat，`method`=handle / sessionClosed / abandonEnter（friend / chat 只有 handle），`result`=ok / error | 对后端的 Dubbo 调用耗时（带 tip 的应答算 ok；超时 / 不可用算 error） |
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
| team | `xm_team_matches_total` | Counter | `outcome`=rejected / internal / unknown_code / success / gather_failed / ticket_failed | 整队开战（4.3 只会出现 rejected，6.4 接开战） |
| team | `xm_team_cross_zone_allowed` | Gauge | — | 是否允许跨区组队（多实例取值不一致时用它观察） |
| team | `executor_*{name="team-worker"}`、`{name="team-push"}` | Micrometer 标准线程池指标 | — | 请求工作池 / 推送执行器 |
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
| data | `xm_data_ops_players_total` | Counter | `kind`=rollback，`outcome`=restored / player_online / player_busy / player_not_found / snapshot_not_found / no_snapshot / created_after_target / snapshot_gone / state_invalid / unknown_sections / fence_lost / id_unavailable / failed / not_executed / rejected / cancelled | 回档作业逐玩家的结局（即 `ops_job_player.outcome`，RESTORED 记作 restored）；取值是代码里的固定集合，首次用到时才注册。批次 6.3 追加 in_battle（data-ops-spec §13.3） |
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
| scene | `xm_scene_moves_total` | Counter | `result`=accepted / clamped / corrected / invalid / frozen | 移动上行（134 / 132 / 131）的裁决，每条恰好计一次：原样接受 / 超额度截断但偏差 ≤ 0.5m / 截断且回了 137 / 含非有限值或坐标超出世界范围（±1e7 m）丢弃 / 跨节点换图冻结中静默丢（§8.1） |
| scene | `xm_scene_skill_releases_total` | Counter | `result`=ok / unknown_skill / invalid_target / cooldown / uninterruptible / state_rejected | 放技能（84）的裁决，每条恰好计一次（§4.9）；不带技能号（客户端可控）与玩家号 |
| scene | `xm_scene_skill_interrupts_total` | Counter | — | 放技能打断了进行中的施法（推了 33）的次数 |
| scene | `xm_scene_aoi_changes_total` | Counter | `change`=enter / leave | 视野变化通知，一对（观察者, 目标）计一次：enter = 进场的 47 条目与给旁人的 21、帧内 47 条目；leave = 离场 / 换场景的 51、帧内 64 条目。离场者自己的列表静默清空，不计 |
| scene | `xm_scene_storage_writes_seconds` | Timer | `op`=save / release / progress / handoff / probe，`result`=released / saved / fenced / failed / rejected / handed_off / lease_too_short / not_committed / lost | 玩家数据写（写回并释放 / 只释放 / 在线存盘 / 交出 / 交出探测，§7 第 6 步）的结局与耗时（含瞬时故障重试），每个写任务恰好计一次：已落库并释放 / 已落库未释放（在线存盘）/ 围栏拒绝（不是故障）/ 重试用尽或非瞬时故障 / 存储线程池拒绝（耗时记 0）/ 已交出（含探测认出自己的提交）/ 剩余租约不足没提交 / 探测确认没提交 / 探测判定不了或读到已失去。只注册各 op 可能出现的组合：save / release = released / fenced / failed / rejected，progress = saved / fenced / failed / rejected，handoff = handed_off / lease_too_short / fenced / failed / rejected（failed / rejected 是结局不明、交给探测，不算写丢失），probe = handed_off / not_committed / lost / rejected。停服时 `shutdownNow` 丢弃的写只进 ERROR 日志 |
| scene | `xm_scene_periodic_saves_total` | Counter | `result`=written / unchanged / in_flight / deferred | 周期存盘对每个到期玩家的处理，每人每次到期恰好计一次：有变化提交了在线存盘 / 与上次落库相同跳过 / 上一次还在途跳过 / 存储线程池积压（排队 ≥ 线程数 × 2）推到下个周期 |
| scene | `executor_*{name="scene-storage"}` | Micrometer 标准线程池指标 | — | 存储线程池排队（`executor_queued_tasks`）/ 剩余容量 / 活跃 / 完成数 |
| scene | `xm_scene_gate_links` | Gauge | — | 接入本节点的 gate 链路连接数（含握手中） |
| scene | `xm_scene_link_frames_total` | Counter | `direction`=in / out，`type`=链路帧类型 | gate ↔ scene 链路帧（in = 从链路收到，含握手帧；out = 已交给链路写出，含 hello_ack） |
| scene | `xm_scene_team_follow_total` | Counter | `result`=followed / same_scene / not_in_team / projection_missing / not_member / leader_not_on_node / leader_scene_draining / switching / is_leader / stale / read_error | 进场 / 换场景后的组队跟随检查（§4.16；基线只有 team_follow_skipped 日志行） |
| scene | `xm_scene_channels{state}`、`xm_scene_channel_plan_applies_total{result}`、`xm_scene_channel_plan_poll_failures_total`、`xm_scene_channel_relocations_total{result}` | Gauge / Counter | `state`=active / draining；`result`=applied / rejected / skipped_lease，或 same_map / default_world / blocked / enter_redirect / switching | 本节点频道数（批次 5.3 起只计主世界频道，实例另见 `xm_scene_instances`）、计划应用、拉计划失败、排空改派（§4.19；switching = 玩家在跨节点换图的冻结中，这次不改派） |
| scene | `xm_scene_instances` | Gauge | `kind`=mirror / dungeon，`state`=active / reclaiming / draining | 本节点的镜像 / 副本实例数（§4.21）：承载中 / 回收宽限中 / 级联或显式销毁排空中；逻辑线程在变化后推绝对值，6 个组合启动即注册 |
| scene | `xm_scene_instance_lifecycle_total` | Counter | `kind`=mirror / dungeon，`event`=created / rejected / reclaim_started / revived / cascade_started / destroyed_idle / destroyed_cascade / destroyed_admin | 实例生命周期事件：建好 / 建实例被拒（不建、记 ERROR）/ 空置满超时进入回收宽限 / 宽限中复活 / 源已销毁转级联 / 三种销毁。勾稽：每个 created 最终恰好对应一个 destroyed_*（或停服）；`mirror_resolves{created}` = `instance_lifecycle{kind="mirror",event="created"}` |
| scene | `xm_scene_mirror_requests_total` | Counter | `result`=accepted / bad_source / bad_mirror_config / source_draining / not_accepting / node_cap / creator_cap | 63 镜像分支的同步结局，每条镜像请求恰好计一次（3014 / 全 0 在分支之前，不计）：受理回 `{0}`，其余各回 3005 |
| scene | `xm_scene_mirror_resolves_total` | Counter | `result`=created / rejected / error / stale / wrong_node / source_moved / create_rejected | 镜像取号结果回到逻辑线程后的结局，每次受理恰好计一次：建好换入 / scene-manager 拒绝（23 {3023}）/ 调用失败、超时、租约无效、没装配（23 {1003}）/ 玩家已离开或在途已作废（丢弃，号作废）/ 号落在别的节点（23 {3023}，不该出现）/ 玩家已不在源、源已不在或在排空、停止接客、达上限（23 {3023}）/ 本地重号或建实例被拒（23 {3023}）。勾稽：`mirror_requests{accepted}` = Σ`mirror_resolves` + 在途数 |
| scene | `xm_scene_admin_requests_total` | Counter | `op`=instance_create / instance_destroy / other，`status`=HTTP 状态码 | scene 管理端口 `/admin/**`（dev / test 建 / 毁副本，§4.21）的调用，鉴权失败也计；首次用到时才注册 |
| scene | `xm_scene_switch_resolves_total` | Counter | `result`=local / remote / same / rejected / error / stale | 63 远端去向经 scene-manager 选目标的结果（§8.1），每次恰好计一次（本节点直接解析掉的 63 不计）：落在本节点本地换 / 别的节点（冻结、交出）/ 就是当前场景 / 拒绝或指向本节点却不在、在排空（23 {3023}）/ 调用失败、兜底超时、应答残缺（23 {1003}）/ 回来时实例已离开或令牌不符（丢弃） |
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
| battle | `xm_battle_scene_events_total` | Counter | `kind`=confirm / settlement，`result`=logged / sent / skipped / error | battle → scene 的确认事件与结算；6.2 的缺省端口只记日志（logged），dev 房间的结算计 skipped；sent / error 随 6.3 的真实传输 |
| battle | `xm_battle_results_total` | Counter | `channel`=plain / activity，`result`=logged / sent / error | 对局结果事件（只在真打完的局发）；6.2 只有 logged，sent / error 随 6.3 / 6.4 |
| battle | `xm_battle_rpc_seconds` | Timer | `method`=createBattle / destroyBattle / issueBattleTicket / addObserver / removeObserver，`result`=ok / business_error / not_allocatable / error | 控制面提供方从收到到 future 完成的耗时（含逻辑线程排队，桶 1ms～1s）；error = 投递被拒、在途超限、处理中抛异常（future 异常完成） |
| battle | `xm_battle_logic_pending_tasks` | Gauge | — | 逻辑线程（兼直连 I/O）的任务队列长度 |
| battle | `xm_battle_admission_phase` | Gauge | — | 建房准入闸阶段：0 not_started / 1 open / 2 closed（本机切片据它等就绪） |
| battle | `xm_battle_lease_lost_total` | Counter | — | 节点号租约丢失（关闸、停发布、不作废房间，§6） |

未覆盖（后续）：Druid 连接池指标（Spring Boot 只认 Hikari / DBCP2 / Tomcat 等连接池的元数据）；
Dubbo 与 Redisson 自带指标未接入；scene 的进场加载（成功 / 失败 / 耗时）未单独计，进场失败目前只有 WARN 日志。
