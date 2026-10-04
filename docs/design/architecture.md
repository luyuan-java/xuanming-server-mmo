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
| `xm-common` | 库 | 雪花 ID、节点号租约、gate 令牌签名、时间源等无框架公共件 |
| `xm-net` | 库 | Netty：客户端帧编解码（兼容 C++ `ProtobufCodec`）、节点链路编解码 |
| `xm-pbmysql` | 库（纯 JDBC） | proto → MySQL 表映射（用户自有 proto2mysql 的 Java 实现）：建表 DDL、只扩不缩的结构同步、按消息 CRUD（§7） |
| `xm-api` | 库 | Dubbo 服务接口、调用方鉴权过滤器（§4.1）与内部 protobuf 消息（包 `xm.api`，只在 Java 版内部使用） |
| `xm-discovery` | 库 | Redis（Redisson）上的节点号租约、游戏节点在线目录、玩家在线目录与服务端推送（§4.3） |
| `xm-player-store` | 库 | 账号 / 玩家持久化（MyBatis）与玩家归属围栏 |
| `xm-gateway-store` | 库 | 区服目录 / 白名单 / 登录公告的库表与读写（MyBatis）：xm-gateway 建表并读，xm-data 运维接口写 |
| `xm-audit` | 库 | 资产审计管线的共享件：Java 自有的流水消息格式（包 `xm.audit`）、Kafka topic 规格（带代次后缀）与启动期核对（§4.5） |
| `xm-gateway` | 进程（Spring Boot Web + Dubbo 调用方） | 区服列表（区服目录 + 健康探测）、分配 gate 并签发 gate 令牌（区服准入）、HTTP 登录与刷新令牌（经 Dubbo 调 xm-login）、登录公告 |
| `xm-login` | 进程（Spring Boot + Dubbo） | 登录（含 access / refresh 令牌、设备数上限）、建角、进游戏、离开 / 断线 |
| `xm-friend` | 进程（Spring Boot + Dubbo） | 好友：申请 / 同意 / 拒绝 / 删除 / 列表 / 黑名单与好友事件推送（Dubbo group `friend`，端口 20883；四张表经 xm-pbmysql，§4.13） |
| `xm-chat` | 进程（Spring Boot + Dubbo） | 聊天 v1：世界频道与私聊的发言（校验、幂等、限速、落历史）与拉取历史（Dubbo group `chat`，端口 20884；数据只在 Redis，§4.14） |
| `xm-team` | 进程（Spring Boot + Dubbo） | 组队：建队 / 申请 / 邀请 / 离队 / 踢人 / 转让 / 解散、队伍快照与邀请推送（Dubbo group `team`，端口 20885；权威数据只在 Redis，§4.16） |
| `xm-guild` | 进程（Spring Boot + Dubbo） | 帮会核心：建 / 查 / 退 / 解散 / 公告 / 任免 / 踢人 / 转让 / 申请审批 / 推送 220 / 排行（Dubbo group `guild`，端口 20886；四张表经 xm-pbmysql，快照缓存与排行在 Redis，§4.17） |
| `xm-scene-manager` | 进程（Spring Boot + Dubbo） | 场景目录：玩家该进哪个场景节点的哪个场景 |
| `xm-gate` | 进程（Spring Boot + Netty） | 客户端接入、会话、按消息号路由、下行推送 |
| `xm-scene` | 进程（Spring Boot + Netty） | 场景与玩家逻辑（单线程拥有场景状态） |
| `xm-data` | 进程（Spring Boot Web） | 审计与运维数据服务：消费审计 topic、幂等落 MySQL；带令牌的运维接口：查询（§4.5）、全服产出封禁（§4.6）、区服目录 / 白名单 / 登录公告（§7） |

依赖方向单向：进程模块 → `xm-api` / `xm-net` / `xm-player-store` / `xm-gateway-store` / `xm-discovery` → `xm-common` / `xm-proto` / `xm-table`。

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
- 上行只接受 `ClientTokenVerifyRequest`（首包握手）与 `ClientRequest`；下行是 `ClientTokenVerifyResponse` 与 `MessageContent`。
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
  调用本身失败（超时 / 后端不可用）gate 推 23 `{1003}`。
- 是否回包由 gate 按契约里该方法的应答类型决定，不看 body 是否为空：非 `Empty` 应答一律回包（哪怕 0 字节），
  `Empty` 应答只在 `tip_id≠0` 时回包；会话指令无论回不回包都执行。
- 服务对服务的调用用各自的类型化接口（如 `SceneDirectoryService`；`AccountLoginService`：xm-gateway 的 HTTP 登录 / 刷新令牌调 xm-login，
  参数与返回值直接用客户端契约里的 loginpb 消息，调用方不重试——刷新是一次性轮换，重试会拿已作废的 refresh 再打一次）。
- `ClientMessageService.abandonEnter(AbandonedEnter)`：gate 确定一条 `EnterScene` 指令的 `PlayerEnter` 从未写上链路
  （会话已在关闭、链路层已关、建链失败）时通知 login，login 带 epoch 围栏释放这次夺得的归属（见 §7），玩家不必等租约过期。
- **调用方鉴权（必需）**：Dubbo 把 `127.*` 视为无效绑定地址，`dubbo.protocol.host=127.0.0.1` 实际监听 `0.0.0.0`
  （`DUBBO_IP_TO_BIND` 也不接受回环地址），login（20881）/ scene-manager（20882）的端口**无法只绑本机**，
  而 `ClientMessageService` 完全信任调用方填的 `SessionContext`。所以 Java 版进程间的每次 Dubbo 调用都要带鉴权附件：
  共享密钥 `XM_DUBBO_SECRET`（xm-login / xm-scene-manager / xm-friend / xm-chat / xm-team / xm-guild / xm-gate / xm-gateway 必填，缺失即拒绝启动），
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
- **推送**（`PlayerPushes`）：查在线目录 → 把 `xm.discovery.GatePush{gate_instance_id, targets[(session_id, player_id)], message_content | kick_tip_id}`
  发布到该 gate 的 pub/sub 频道 `xm:gate-push:{zone}:{gate 节点号}`；多人推送按 gate 分组、每个 gate 一条。
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
  `OptionIsPlayerService` 的客户端服务、请求类型与契约一致、没有重复——任一不符启动即失败。
- **处理器契约**（`PlayerRequestHandler` + `PlayerCall`）：在场景逻辑线程上调用；经 `call.reply(...)` 回应答，`message_id` 同请求、
  `id` 回显请求号；每条请求至多回一次，应答类型必须与契约一致，`Empty` 应答的方法不能回。处理器没回、回错类型、抛异常
  （记 ERROR，逻辑线程继续）时由分发补回 `kFeatureUnavailable`(1006)，客户端不会卡在等应答上。没有处理器的方法同样回 1006
  （`Empty` 应答的方法静默忽略）。
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
  库里那份是旧的）拍 LOGIN，离场 / 停服写回时拍 LOGOUT（与写回**同一份** `PlayerSave`）；失去归属的移除不写回、也不拍。
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
- **保留期**：`xm.data.retention.transaction-log` / `player-snapshot`，缺省 0 = 永久保留（同 mmorpg）；设了就每小时分批 DELETE。
- **运维查询**（xm-data 管理端口 18106，缺省只绑本机）：`GET /admin/transaction-log?player=&since=&until=&limit=`，按（时间、流水号）升序；
  `GET /admin/player-snapshots?player=&since=&until=&limit=` 给快照元数据与玩法数据字节数（不回本体）。uint64 字段输出为十进制字符串。鉴权（过滤器只按容器规范化后的路径 `/admin/*` 生效，`/admin;x/`、`/%61dmin/` 之类绕不过）：
  共享令牌 `XM_ADMIN_TOKEN`（请求头 `X-Xm-Admin-Token`，常数时间比较，未配置一律 503）+ 必填
  操作人 `X-Xm-Operator`（UTF-8，1–64 字符、不含控制字符）；每次调用（含处理中抛异常的，按 500 记）都记运维审计日志 `xm.audit.admin`。本机切片脚本没设令牌时生成一个写进 `run/xm-admin-token`。

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
  `xm_scene_gain_blocked_total`。xm-data 的 Redis 客户端第一次用到才连：Redis 不可用时这三个接口回 503，审计消费不受影响。
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
- **写入口只有 `BagService`**（`com.game.scene.bag`）：冻结闸（随 5.2，基线回 1005；目前放行）→ 全服物品禁发（1005）→
  `Bag.add` 整批原子（规划 → 一次铸齐号 → 淘汰 → 并堆 → 切新实例；任何失败零写入）→ 每个配置一条入包流水 + 物品获取异常检测，
  每个被淘汰的实例一条销毁流水。战斗中禁止扣减之类的闸放在各玩法入口（基线 D48：结算在战斗标记还挂着时应用）。
  物品 guid 来自全服 `SceneGuids`（§9），一批要的号先一次铸齐，铸不出来回 6004。
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
- **号与随机**：宝宝号用与物品同一个全服号源（`SceneGuids`）；资质随机数只在逻辑线程上用。写闸（冻结 / 战斗中）随 5.2 / 6.3 接入。

### 4.12 通用资产通道（scene 侧）

mmorpg：`asset_op_system.cpp` + `asset_op_ledger.cpp` + `asset_op_auth.cpp`（scene），调用方 go guild / trade。用途：别的服务给**在线**
玩家扣货币、发货币与物品，每条恰好生效一次。Java 版 scene 侧在 `com.game.scene.asset`，调用方随 4.5（帮会经济）/ 4.x（交易）接入：

- **契约**（`xm-api` `xm.api.AssetOpRequest / AssetOpResponse`，Java 内部自有格式，字段语义与数值同基线）：请求带 (player_id, 流, 流纪元, seq)、
  关联号、流水原因、一包资产、调用方签名；应答是结局 APPLIED（partial）/ REJECTED / RETRY / NOT_HERE / UNKNOWN + 原因 tip + durable。
  调用方只在 APPLIED / REJECTED 且 durable 时终结这条 seq，否则用同一 seq 重查；RETRY / NOT_HERE / UNKNOWN 都没记账、不得终结：RETRY / NOT_HERE 稍后照常重投，UNKNOWN（信封畸形、验签失败、纪元过期、跳号过远、seq 滑出窗口）要告警转人工。
- **账本** `AssetOpLedger`（领域对象，存 `player_state.asset_ledger`，与资产同一份记录、同一次围栏写，不存在「资产落了账本没落」）：
  每条流 1024 位窗口（seen / applied 两组位）+ 拒绝原因环 + 部分发放名单，规则逐条同基线；加载时校验，不自洽就原样保留并让该玩家
  的通道 fail-closed（RETRY 27005），绝不把坏账本当空账本（那会把已应用的 seq 重新看成未见、二次扣款）。
- **验签** `AssetOpAuth`：每个调用方一把密钥、独占两条流（guild：1 / 2，trade：3 / 4；SYSTEM_CREDIT 没有合法调用方），密钥只从环境变量
  `XM_ASSET_OP_SECRET_GUILD / _TRADE` 注入；规范串格式与基线逐字节相同。
- **流程** `AssetOpService.handle`（逻辑线程）：信封 / 流方向 / 流水原因白名单 → 验签 → 找人 → 账本损坏 → 分类（已见只读答复；滑出窗口 /
  跳号 / 旧纪元回 UNKNOWN 0 不猜结局）→ 归属围栏（owner_epoch 为 0 回 RETRY 27003）→ 中止占位 → 包内容（确定性失败记 REJECTED 27004）
  → 扣款（余额不足记 REJECTED 27000）/ 发放（货币预检 → 物品整批 → 货币）。闸只在这一层，不下沉到 `CurrencyService` / `BagService`。
- **durable**：只看最近一次确认落库的快照（`ScenePlayer.persistedState()`）里有没有这条结局，不另建水位；记账后立刻
  `SceneWorld.requestSave`，不在调用里等落盘；已见未 durable 的重查 500 ms 内至多补存一次（限频时刻挂在账本实例上，不持久化）。
- **入口** `AssetOpEndpoint`：任意线程调用，投递到逻辑线程、future 带回结局；`SceneNode.assetOps()` 暴露给以后的跨进程传输。
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
  （只跟随、不再扇出）。跨节点 / 跨 zone 不跟随（同基线）。不发、不收 scene 刷新事件（scene 不缓存 TeamId，进场时现读）。

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
  上行 8（UpdateGuildScore）/ 220 回信封 1003；4.5 / 4.6 的 10 个号暂回 in-band 1006。
- **推送 220**（`GuildChangedS2C{kind, guild_id, actor, target}`）经 `PlayerPushes`，失效缓存之后发，失败不影响回包；
  申请入帮通知帮主有 60 s 冷却（`SET NX`）。guild_id 由全服雪花租约（`NodeTypes.GUILD`）发，失败回 in-band 14008。

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
  写回都交给存储线程池之后才关池，并用剩余预算等落库；预算用完写回还没开始执行就取消它并记一条 ERROR（带大致人数），
  存储池没排空就 `shutdownNow` 并逐条记下被丢弃的写回（player_id / epoch / 场景 / 坐标）。实现与测试：`SceneShutdown`。
- 场景对象：普通 Java 领域对象，不用 ECS（实体 / 组件 / 系统）：玩家的各玩法状态是 `ScenePlayer` 持有的字段对象（`Wallet`、`PlayerAttributes` ……），
  规则在对应的服务类里（`AttributeService` 等），基线的 entt 组件 / 系统按「领域对象 + 服务」翻译，不照搬。

## 6. 服务发现与部署 profile

- **游戏节点在线目录放 Redis**（`xm-discovery` 的 `NodeDirectory`）：gate / scene 每 5s 写一条 TTL 15s 的条目
  （`GateNodeInfo` / `SceneNodeInfo`，含人数、场景列表、链路地址）。gateway 按它挑 gate，scene-manager 按它分配场景，
  gate 按它连 scene。节点异常退出后最多一个 TTL 才从目录消失，读方必须把连接失败当作不可用处理。
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
- 节点类型名（`gate` / `scene` / `login`，Redis 键段）只有一个出处：`xm-discovery` 的 `NodeTypes`。
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
- **proto 声明的表（xm-pbmysql）**：行本身就是一条 protobuf message 的表（好友、邮件、帮会等社交服务；
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
- **写回失败**：scene 对可恢复的瞬时故障（取不到连接、连接断开、锁等待 / 查询超时）在 5s 预算内退避重试最多 3 次；
  最终失败、线程池拒绝、停服丢弃都记 ERROR（带 player_id / epoch / 场景 / 坐标，供人工修复）并计数。
  scene 的 JDBC URL 带 `connectTimeout=3000&socketTimeout=10000`（与 login 同口径），库卡死时存储线程不会被无限挂住。
  **在线周期存盘**（`xm.scene.save-interval`，缺省 300s，同基线）：每秒一个槽，`player_id` 对周期取模等于槽号的玩家到期，
  与上次确认落库的快照相同就跳过，不同才写（带围栏、不释放；要求归属未释放，所以迟到的在线存盘盖不过最终写回）。
  进程被 kill 时丢的是最近一次在线存盘之后的增量。最终写回失败（重试用尽）时盘上至少是最近一次在线存盘的状态。

- **玩家位置与短线重连**（`PlayerLocationDirectory`，Redis `xm:location:{player_id}`）：持有归属的 scene 是唯一写者，
  每次写带本次进场内单调递增的序号与完整的此刻状态，按 (epoch, 序号) 只收更新的写（Redisson 的命令会乱序）——进场 / 换场景写当前场景实例
  （TTL 60 s，在线每 20 s 一槽续期）、断线（连接断开、gate 链路断开）写成 30 s 重连租约、LeaveGame 写成登出墓碑；被接管 / 失去归属 / 停服不动（新持有者覆盖或按 TTL 消失）。写都是异步的，不阻塞逻辑线程，
  写不上只退化成按首登落点。login 进游戏时读：有本 zone 的记录（在线顶号、断线租约内重连）就请 scene-manager 送回原实例
  （还在就不看人数直接用，不在了按原地图），没有（首登、干净登出、租约过期）落默认主世界（World 表第一行）；
  夺到归属后复查一次（LeaveGame 紧跟 EnterGame 时墓碑可能晚到）。gate 在 LeaveGame 在途 / 排队时断线也按主动离开通知 scene。
  基线在 zone 内只按 location 定 zone、落默认主世界，回原实例是 Java 的有意差异（PARITY「短线重连与落点」⑥）。
  重连不复用内存实例：断线照常最终写回并释放，重连按新 epoch 从库重载（与基线「退出存盘在途时取消退出」不同，见 PARITY）。

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

## 9. ID

- `player_id`：雪花（41 位毫秒 / 10 位 worker / 12 位序号），worker 为 `xm-login` 的节点号；只由 `xm-login` 产生。
- `session_id`（uint32，仅服务端内部）：`[gate 节点号 15 位][序号 17 位]`，跳过 0 与在用号。
- 物品 uuid、资产流水号 `tx_id`、玩家快照号 `snapshot_id`：雪花，共用一个 `SceneGuids`，worker 取自场景节点占的**全服**号段租约（`NodeTypes.SCENE_GUID`，作用域 0；不论审计开不开都占）——
  场景节点自己的租约按 zone 分，两个 zone 的第一台 scene 会拿到同一个 worker、发出相同的号，落库按主键去重就会静默吞掉一条。
  停服时先发完审计队列再交还这个租约（反过来别的实例可能拿到同一个 worker 发重号）。
- `team_id`、`guild_id`：雪花，worker 取各自服务的全服租约（`NodeTypes.TEAM` / `NodeTypes.GUILD`，作用域 0）。
- **不移植号段服务**（基线 data_service `AllocateIdSegment` + 各节点的号段客户端，盘点 id-segment-allocator / guid-segment-alloc）：
  Java 的永久号一律「雪花 + 节点号租约」——发号前检查租约仍有效、时钟回拨拒发，worker 不重叠由租约保证；
  客户端只要求这些号非 0、唯一（uint64），不依赖号段的值域（基线号段值域 < 2^55，与存量雪花号不相交——Java 没有存量号段号，无此约束）。
  场景内的临时 id（实体、场景实例）用场景节点自己的雪花（`SceneWorld.nextId`）。

## 10. 首批不做（后续批次）

跨 zone、战斗等玩法系统、Kafka 事件、GM 管理接口（除远程停机外），
服务级限流 / 熔断（Sentinel）、合服与 TiDB 数据层。（周期存盘已于 2026-10-02 补上，短线重连已于 2026-10-04 补上，见 §7；货币与 GM 客户端指令闸见 §4.4；登录排队与开服限流见 §8；
  gate 排空与 GM 签名停机见 §6。）
（低基数运行指标五个进程都已接入，见 §11。）
顶号已按 §7 第 2 步实现（旧连接收 23 {2017} 后断开，不发 34）。进度逐项登记在 `PARITY.md`。

## 11. 可观测性（指标）

每个进程用 **Micrometer** 记指标，经 **Spring Boot Actuator** 以 Prometheus 文本格式导出（选型见 tech-stack.md）。
指标名与标签只在每个进程的一个类里定义，业务代码只调语义方法：gate `GateMetrics`、login `LoginMetrics`、
scene-manager `SceneDirectoryProvider`、gateway `AssignGateMetrics`、scene `SceneMetrics`、friend `FriendMetrics`、chat `ChatMetrics`、team `TeamMetrics`、guild `GuildMetrics`。

| 进程 | 抓取地址（默认） | 说明 |
|---|---|---|
| xm-gateway | `http://127.0.0.1:18105/actuator/prometheus` | 管理专用端口，默认只绑本机（`XM_MANAGEMENT_ADDRESS`）；对外的 18081 只有 `/api` |
| xm-login | `http://127.0.0.1:18101/actuator/prometheus` | 管理专用端口 |
| xm-friend | `http://127.0.0.1:18107/actuator/prometheus` | 管理专用端口 |
| xm-chat | `http://127.0.0.1:18108/actuator/prometheus` | 管理专用端口 |
| xm-team | `http://127.0.0.1:18109/actuator/prometheus` | 管理专用端口 |
| xm-guild | `http://127.0.0.1:18110/actuator/prometheus` | 管理专用端口 |
| xm-scene-manager | `http://127.0.0.1:18102/actuator/prometheus` | 管理专用端口 |
| xm-gate | `http://127.0.0.1:18103/actuator/prometheus` | 管理专用端口 |
| xm-scene | `http://127.0.0.1:18104/actuator/prometheus` | 管理专用端口；同机多个 scene 实例要各用 `SERVER_PORT` 错开（与链路端口一样） |
| xm-data | `http://127.0.0.1:18106/actuator/prometheus` | Web 进程：同一端口上还有带令牌的运维接口 `/admin/**`（§4.5），默认只绑本机 |

- **非 Web 进程的管理端口**：gate / login / friend / chat / team / guild / scene-manager / scene 的业务端口是 Netty / Dubbo，为管理端点另起一个只挂 actuator 的
  Tomcat（`web-application-type: servlet`，4 个线程，`shutdown: immediate`），这个端口上没有业务接口。
  默认只绑 `127.0.0.1`（与 mmorpg 开发环境的 Prometheus 端口同口径），跨机抓取用 `XM_MANAGEMENT_ADDRESS` 指定内网地址；
  端口用 `SERVER_PORT` 覆盖，Windows 上避开保留端口段 50060–50159。actuator 只暴露 `health` 与 `prometheus` 两个端点。
  gate / scene 的这个端口上另有 GM 签名停机的 `GET /gm/identity` 与 `POST /gm/graceful-shutdown`（§6）：它们只收本机来的请求，
  为跨机抓取放宽 `XM_MANAGEMENT_ADDRESS` 不会把它们一起暴露（确需远程调用时另设 `XM_GM_ALLOW_REMOTE=true`）。
- **公共标签**：`application=<进程名>`。实例由 Prometheus 的抓取目标区分，不在进程里加实例标签。
- **基数约束**（AGENTS.md §5）：不以 player_id / session_id / 账号 / IP / zone_id 作标签。消息维度只用 `服务.方法`
  （`MessageIdRegistry` 的客户端白名单，有界），不认识的消息号一律归 `unknown`——公网流量造不出新的时间序列；
  scene 的场景维度只用场景配置号 `scene_config`（场景只在启动时按配置表建出，之后不再新增），不用场景实例号 / 实体号 /
  gate 节点号 / 链路号；其余标签都是代码里的枚举。
- **延迟**：Timer 用固定的 SLO 桶（gate / login 5ms～10s 共 11 个，scene-manager 1ms～1s 共 8 个，scene 逻辑线程内
  0.1ms～1s 共 12 个、含一帧预算 50ms，scene 存储写 5ms～10s 共 11 个），不开百分位直方图。
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
| gate | `xm_gate_disconnects_total` | Counter | `reason`=handshake_timeout / handshake_rejected / no_handshake / illegal_packets / pending_overflow / write_buffer_full / invalid_frame / session_id_exhausted / server_directive / kicked / scene_link_down / server_kick | gate 主动断开的连接（客户端自己断开、停服 / 丢租约的批量关闭不计） |
| gate | `xm_gate_pushes_total` | Counter | `kind`=message / kick，`result`=delivered / no_session / not_bound / stale_instance / invalid | 服务端推送（§4.3）对每个目标会话的结局：已下发 / 会话号已不存在 / 会话不在游戏里或玩家对不上（栅栏）/ 指向别的 gate 实例 / 格式不对 |
| gate | `xm_gate_backend_calls_seconds` | Timer | `backend`=login / friend / chat，`method`=handle / sessionClosed / abandonEnter（friend / chat 只有 handle），`result`=ok / error | 对后端的 Dubbo 调用耗时（带 tip 的应答算 ok；超时 / 不可用算 error） |
| gate | `xm_gate_link_frames_total` | Counter | `direction`=out / in，`type`=链路帧类型（hello / player_enter / client_forward / to_client ……） | gate ↔ scene 链路帧（out = 已写上链路，排队中不算） |
| gate | `xm_gate_link_dropped_total` | Counter | `reason`=lease_invalid / queue_full / link_failed / unavailable | 没发出去的链路帧 |
| gate | `xm_gate_link_events_total` | Counter | `event`=connecting / ready / connect_failed / down | 链路状态变化 |
| login | `xm_login_requests_seconds` | Timer | `method`=Login / CreatePlayer / EnterGame / LeaveGame / Disconnect / unrouted，`result`=ok / business_error / internal_error / overloaded / bad_request / unsupported | 每个客户端请求的耗时与结果（含工作队列排队）；business_error = 应答体带 `error_message` |
| login | `xm_login_owner_claims_seconds` | Timer | `outcome`=claimed / waited / timeout / not_found / error | EnterGame 夺取归属这一步（§7）：第一次夺到 / 请持有者让出后夺到（顶号、快速重进）/ 等不到回 2005 / 角色已不存在 / 故障 |
| login | `xm_login_owner_takeover_requests_total` | Counter | — | 请持有者让出的次数（每次重试都会再请一次） |
| login | `xm_login_backend_calls_seconds` | Timer | `backend`=scene-manager，`method`=assign，`result`=ok / rejected / error | 场景分配调用耗时 |
| login | `xm_login_players_created_total` | Counter | — | 新建成功的角色（应答丢失后的重试命中已建角色不计） |
| login | `xm_login_abandoned_enters_total` | Counter | `result`=released / stale / failed / overloaded / invalid | gate 通知进场未送达后代为释放归属的结果 |
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
| scene-manager | `xm_scene_manager_assign_seconds` | Timer | `result`=ok / no_scene / bad_request / rejected / error | 场景分配结果与耗时（error = 场景目录不可读） |
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
| scene | `xm_scene_moves_total` | Counter | `result`=accepted / clamped / corrected / invalid | 移动上行（134 / 132 / 131）的裁决，每条恰好计一次：原样接受 / 超额度截断但偏差 ≤ 0.5m / 截断且回了 137 / 含非有限值或坐标超出世界范围（±1e7 m）丢弃 |
| scene | `xm_scene_skill_releases_total` | Counter | `result`=ok / unknown_skill / invalid_target / cooldown / uninterruptible / state_rejected | 放技能（84）的裁决，每条恰好计一次（§4.9）；不带技能号（客户端可控）与玩家号 |
| scene | `xm_scene_skill_interrupts_total` | Counter | — | 放技能打断了进行中的施法（推了 33）的次数 |
| scene | `xm_scene_aoi_changes_total` | Counter | `change`=enter / leave | 视野变化通知，一对（观察者, 目标）计一次：enter = 进场的 47 条目与给旁人的 21、帧内 47 条目；leave = 离场 / 换场景的 51、帧内 64 条目。离场者自己的列表静默清空，不计 |
| scene | `xm_scene_storage_writes_seconds` | Timer | `op`=save / release / progress，`result`=released / saved / fenced / failed / rejected | 玩家数据写（写回并释放 / 只释放 / 在线存盘）的结局与耗时（含瞬时故障重试），每个写任务恰好计一次：已落库并释放 / 已落库未释放（在线存盘）/ 围栏拒绝（不是故障）/ 重试用尽或非瞬时故障 / 存储线程池拒绝（耗时记 0）。停服时 `shutdownNow` 丢弃的写只进 ERROR 日志 |
| scene | `xm_scene_periodic_saves_total` | Counter | `result`=written / unchanged / in_flight / deferred | 周期存盘对每个到期玩家的处理，每人每次到期恰好计一次：有变化提交了在线存盘 / 与上次落库相同跳过 / 上一次还在途跳过 / 存储线程池积压（排队 ≥ 线程数 × 2）推到下个周期 |
| scene | `executor_*{name="scene-storage"}` | Micrometer 标准线程池指标 | — | 存储线程池排队（`executor_queued_tasks`）/ 剩余容量 / 活跃 / 完成数 |
| scene | `xm_scene_gate_links` | Gauge | — | 接入本节点的 gate 链路连接数（含握手中） |
| scene | `xm_scene_link_frames_total` | Counter | `direction`=in / out，`type`=链路帧类型 | gate ↔ scene 链路帧（in = 从链路收到，含握手帧；out = 已交给链路写出，含 hello_ack） |
| scene | `xm_scene_team_follow_total` | Counter | `result`=followed / same_scene / not_in_team / projection_missing / not_member / leader_not_on_node / is_leader / stale / read_error | 进场 / 换场景后的组队跟随检查（§4.16；基线只有 team_follow_skipped 日志行） |
| scene | `xm_scene_link_dropped_total` | Counter | `reason`=link_gone / write_buffer_full | 没发出去的 scene → gate 链路帧：链路已注销 / 已断开；出站缓冲越过高水位（随即断链） |
| scene | `xm_scene_link_backpressure_pauses_total` | Counter | — | 逻辑线程积压到 `link-max-pending-frames`、暂停读取某条链路的次数（§4.2 背压） |
| scene | `xm_scene_gain_block_entries` | Gauge | — | 本节点当前生效的全服产出封禁条目数（§4.6） |
| scene | `xm_scene_gain_block_sync_age_seconds` | Gauge | — | 距上次成功从 Redis 同步封禁名单的秒数；同步一直失败时持续增长（名单可能过时，要告警） |
| scene | `xm_scene_gain_block_sync_failures_total` | Counter | — | 封禁名单同步失败次数（沿用上次的名单） |
| scene | `xm_scene_gain_blocked_total` | Counter | `category`=currency | 被全服产出封禁拒绝的获取 |
| scene | `xm_scene_gain_anomalies_total` | Counter | `category`=currency，`currency_type`=币种号 | 获取异常告警：滑动窗口内次数或累计量越线，每次越线计一次（玩家号只进日志 `xm.audit.anomaly`） |

未覆盖（后续）：Druid 连接池指标（Spring Boot 只认 Hikari / DBCP2 / Tomcat 等连接池的元数据）；
Dubbo 与 Redisson 自带指标未接入；scene 的进场加载（成功 / 失败 / 耗时）未单独计，进场失败目前只有 WARN 日志。
