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
| `xm-table` | 库（同步产物） | 配置表 proto + 导表器生成的 `com.game.table` 表管理器 |
| `xm-common` | 库 | 雪花 ID、节点号租约、gate 令牌签名、时间源等无框架公共件 |
| `xm-net` | 库 | Netty：客户端帧编解码（兼容 C++ `ProtobufCodec`）、节点链路编解码 |
| `xm-api` | 库 | Dubbo 服务接口、调用方鉴权过滤器（§4.1）与内部 protobuf 消息（包 `xm.api`，只在 Java 版内部使用） |
| `xm-discovery` | 库 | Redis（Redisson）上的节点号租约与游戏节点在线目录 |
| `xm-player-store` | 库 | 账号 / 玩家持久化（MyBatis）与玩家归属围栏 |
| `xm-gateway` | 进程（Spring Boot Web） | 区服列表、分配 gate 并签发 gate 令牌 |
| `xm-login` | 进程（Spring Boot + Dubbo） | 登录、建角、进游戏、离开 / 断线 |
| `xm-scene-manager` | 进程（Spring Boot + Dubbo） | 场景目录：玩家该进哪个场景节点的哪个场景 |
| `xm-gate` | 进程（Spring Boot + Netty） | 客户端接入、会话、按消息号路由、下行推送 |
| `xm-scene` | 进程（Spring Boot + Netty） | 场景与玩家逻辑（单线程拥有场景状态） |

依赖方向单向：进程模块 → `xm-api` / `xm-net` / `xm-player-store` / `xm-discovery` → `xm-common` / `xm-proto` / `xm-table`。

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
- 服务对服务的调用用各自的类型化接口（如 `SceneDirectoryService`）。
- `ClientMessageService.abandonEnter(AbandonedEnter)`：gate 确定一条 `EnterScene` 指令的 `PlayerEnter` 从未写上链路
  （会话已在关闭、链路层已关、建链失败）时通知 login，login 带 epoch 围栏释放这次夺得的归属（见 §7），玩家不必等租约过期。
- **调用方鉴权（必需）**：Dubbo 把 `127.*` 视为无效绑定地址，`dubbo.protocol.host=127.0.0.1` 实际监听 `0.0.0.0`
  （`DUBBO_IP_TO_BIND` 也不接受回环地址），login（20881）/ scene-manager（20882）的端口**无法只绑本机**，
  而 `ClientMessageService` 完全信任调用方填的 `SessionContext`。所以 Java 版进程间的每次 Dubbo 调用都要带鉴权附件：
  共享密钥 `XM_DUBBO_SECRET`（xm-login / xm-scene-manager / xm-gate 必填，缺失即拒绝启动），
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

## 5. 线程模型

- **gate**：Netty I/O 线程处理编解码与会话；同一会话的消息按到达顺序转发（会话绑定到 channel 的 EventLoop）。
  Dubbo 调用异步完成，回调通过 `channel.eventLoop().execute(...)` 回到会话所在线程。
  **按消息号限频**（C++ `MessageLimiter` 同义）：每个会话、每个消息号一个滑动窗口，上限取 MessageLimiter 表
  （`xm.table-dir` 下的 `messagelimiter.pb`，与 mmorpg 同一份配表），表里没有的缺省每秒 3 条；超频回
  `MessageContent{message_id, id, error_message{1008 kRateLimitExceeded}}`、不转发、计非法包（到阈值断开）。
- **scene**：**一个逻辑线程拥有全部场景状态**（Netty `DefaultEventLoop`）。I/O 线程只做解码，把消息投递给逻辑线程
  （每条链路有积压上限，见 §4.2）；阻塞 I/O（MySQL / Redis）在有界的存储线程池上执行，结果再投递回逻辑线程。
  场景状态只在逻辑线程读写，不加锁。
- **scene 停服**（`SceneNode.release`）：摘目录 → 停监听 → 停接管订阅与续约 → 断开全部 gate 链路（不再有新帧进来）→
  把「写回全部在场玩家」投递到逻辑线程，**用整个停服预算**（`xm.scene.shutdown-save-timeout`）等它执行完 →
  写回都交给存储线程池之后才关池，并用剩余预算等落库；预算用完写回还没开始执行就取消它并记一条 ERROR（带大致人数），
  存储池没排空就 `shutdownNow` 并逐条记下被丢弃的写回（player_id / epoch / 场景 / 坐标）。实现与测试：`SceneShutdown`。
- 场景对象：普通 Java 对象 + 按实体组织的组件字段，不引入 ECS 库（无达标的库，见选型表）。

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
- Redis 客户端超时（`xm.redis.connect-timeout-ms` / `timeout-ms` / `retry-attempts` / `retry-delay-ms`，
  默认 2000 / 2000 / 1 / 200）：单条命令最坏阻塞 = (重试 + 1) × 响应超时 + 重试 × 间隔 = 4.2s，
  低于客户端 HTTP 超时 5s（Redisson 自带默认值下可达二十多秒）。

## 7. 存储

- MySQL 库 `xm_java`（与 mmorpg 的库隔离）：`account`、`player`；建表脚本在 `xm-player-store/src/main/resources/db/xm-player-schema.sql`
  （只 `CREATE TABLE IF NOT EXISTS`，存量库的结构变更按 [db-migrations.md](db-migrations.md) 手工迁移）。
- 玩家名全服唯一且大小写 / 全半角不敏感：唯一索引 `uk_player_name_key` 建在 `name_key` 上，键只由 `PlayerStore.nameKey` 计算
  （NFKC → 去首尾空白 → `Locale.ROOT` 小写）。建角撞到主键（`player_id` 重号）是不变量被破坏，抛异常，不报「重名」。
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
  首批没有周期存盘：进程被 kill 时本次在线期间的增量（换图后的地图与坐标）会丢。

## 8. 登录进场景调用链（首批竖切）

1. 客户端 `POST /api/assign-gate` → `xm-gateway`：按 zone 取 gate 列表，选在线人数最少者，签 `GateTokenPayload`（TTL 600s）。
2. 客户端 TCP 连 gate，首包 `ClientTokenVerifyRequest` → gate 本地验签（常数时间比较）。
3. `Login(48)` → gate → `xm-login`：鉴权（开发模式口令）、取 / 建账号，回角色列表；`ClientReply` 指示 gate 把账号绑到会话。
4. 角色为空时 `CreatePlayer(14)` → `xm-login`：按配表生成名字与默认职业，写 `player` 表，回角色列表。
5. `EnterGame(26)` → `xm-login`：会话已绑定玩家（不论是不是同一角色）→ 2028（基线进游戏成功即删登录会话）；
   校验角色归属 → `SceneDirectoryService` 选场景 → 夺取归属（§7：上一个写者没释放就请它让出并等待，等不到回 2005）→
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

## 10. 首批不做（后续批次）

排队、短线重连（30s 断线租约、回到原位置）、跨 zone、战斗、背包 / 任务 / 货币等玩法系统、Kafka 事件、GM / 管理接口、
服务级限流 / 熔断（Sentinel）、周期存盘、合服与 TiDB 数据层。
（低基数运行指标五个进程都已接入，见 §11。）
顶号已按 §7 第 2 步实现（旧连接收 23 {2017} 后断开，不发 34）。进度逐项登记在 `PARITY.md`。

## 11. 可观测性（指标）

每个进程用 **Micrometer** 记指标，经 **Spring Boot Actuator** 以 Prometheus 文本格式导出（选型见 tech-stack.md）。
指标名与标签只在每个进程的一个类里定义，业务代码只调语义方法：gate `GateMetrics`、login `LoginMetrics`、
scene-manager `SceneDirectoryProvider`、gateway `AssignGateMetrics`、scene `SceneMetrics`。

| 进程 | 抓取地址（默认） | 说明 |
|---|---|---|
| xm-gateway | `http://127.0.0.1:18081/actuator/prometheus` | 与客户端 `/api` 同一端口；面向公网部署时必须分开（`MANAGEMENT_SERVER_PORT` + `MANAGEMENT_SERVER_ADDRESS` 绑内网，或入口网关拦掉 `/actuator`） |
| xm-login | `http://127.0.0.1:18101/actuator/prometheus` | 管理专用端口 |
| xm-scene-manager | `http://127.0.0.1:18102/actuator/prometheus` | 管理专用端口 |
| xm-gate | `http://127.0.0.1:18103/actuator/prometheus` | 管理专用端口 |
| xm-scene | `http://127.0.0.1:18104/actuator/prometheus` | 管理专用端口；同机多个 scene 实例要各用 `SERVER_PORT` 错开（与链路端口一样） |

- **非 Web 进程的管理端口**：gate / login / scene-manager / scene 的业务端口是 Netty / Dubbo，为管理端点另起一个只挂 actuator 的
  Tomcat（`web-application-type: servlet`，4 个线程，`shutdown: immediate`），这个端口上没有业务接口。
  默认只绑 `127.0.0.1`（与 mmorpg 开发环境的 Prometheus 端口同口径），跨机抓取用 `XM_MANAGEMENT_ADDRESS` 指定内网地址；
  端口用 `SERVER_PORT` 覆盖，Windows 上避开保留端口段 50060–50159。只暴露 `health` 与 `prometheus` 两个端点。
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
| gate | `xm_gate_client_requests_total` | Counter | `route`=login / scene / unsupported / unknown，`method`=服务.方法 / unknown，`result`=forwarded / not_in_scene / link_unavailable / unsupported / unknown_message / oversized / rate_limited / overflow / dropped | 已握手会话上每个请求在 gate 的最终去向，恰好计一次。C++ 的「非法包」= unknown_message + oversized + rate_limited；限频拒绝 = rate_limited |
| gate | `xm_gate_client_invalid_frames_total` | Counter | `reason`=invalid_length / checksum / invalid_name_len / unknown_type / parse | 解码层非法帧（随即断开） |
| gate | `xm_gate_disconnects_total` | Counter | `reason`=handshake_timeout / handshake_rejected / no_handshake / illegal_packets / pending_overflow / write_buffer_full / invalid_frame / session_id_exhausted / server_directive / kicked / scene_link_down | gate 主动断开的连接（客户端自己断开、停服 / 丢租约的批量关闭不计） |
| gate | `xm_gate_backend_calls_seconds` | Timer | `backend`=login，`method`=handle / sessionClosed / abandonEnter，`result`=ok / error | 对 login 的 Dubbo 调用耗时（带 tip 的应答算 ok；超时 / 不可用算 error） |
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
| scene-manager | `xm_scene_manager_assign_seconds` | Timer | `result`=ok / no_scene / bad_request / rejected / error | 场景分配结果与耗时（error = 场景目录不可读） |
| gateway | `xm_gateway_assign_gate_total` | Counter | `code`=0 / 400 / 404 / 500 / 503，`reason`=ok / 应答体 `error` 文案 | assign-gate 结局（含请求体不合法、未预期异常被兜底的路径）；8 个已知组合启动即注册 |
| scene | `xm_scene_players` | Gauge | `scene_config`=场景配置号 | 该配置下的在线玩家数（同配置各频道合计；加载中的进场不算）。建场景即注册、初值 0 |
| scene | `xm_scene_logic_pending_tasks` | Gauge | — | 逻辑线程待执行的任务数（链路帧、存储回调、归属事件；不含定时的帧任务）。上界 ≈ gate 数 × `link-max-pending-frames` |
| scene | `xm_scene_logic_task_wait_seconds` | Timer | — | 逻辑任务从投递到开始执行的排队等待（经 `SceneNode.runOnLogic` 投递的全部任务；同步的目录 / 归属快照不计） |
| scene | `xm_scene_logic_task_run_seconds` | Timer | — | 逻辑任务的执行耗时（抛异常的也计） |
| scene | `xm_scene_tick_seconds` | Timer | — | 一帧的耗时（外推 + 视野刷新 + 广播），20 FPS、每帧一次；超过 50ms 桶即超预算 |
| scene | `xm_scene_broadcast_seconds` | Timer | `kind`=view_changes / attribute_sync | 帧内广播阶段（组包、序列化、交给链路）：view_changes = 47 / 64，每帧一次；attribute_sync = 66，偶数帧一次（均为全部场景合计） |
| scene | `xm_scene_moves_total` | Counter | `result`=accepted / clamped / corrected / invalid | 移动上行（134 / 132 / 131）的裁决，每条恰好计一次：原样接受 / 超额度截断但偏差 ≤ 0.5m / 截断且回了 137 / 含非有限值丢弃 |
| scene | `xm_scene_aoi_changes_total` | Counter | `change`=enter / leave | 视野变化通知，一对（观察者, 目标）计一次：enter = 进场的 47 条目与给旁人的 21、帧内 47 条目；leave = 离场 / 换场景的 51、帧内 64 条目。离场者自己的列表静默清空，不计 |
| scene | `xm_scene_storage_writes_seconds` | Timer | `op`=save / release，`result`=released / fenced / failed / rejected | 玩家数据写（写回并释放 / 只释放）的结局与耗时（含瞬时故障重试），每个写任务恰好计一次：已落库并释放 / 围栏拒绝（不是故障）/ 重试用尽或非瞬时故障 / 存储线程池拒绝（耗时记 0）。停服时 `shutdownNow` 丢弃的写只进 ERROR 日志 |
| scene | `executor_*{name="scene-storage"}` | Micrometer 标准线程池指标 | — | 存储线程池排队（`executor_queued_tasks`）/ 剩余容量 / 活跃 / 完成数 |
| scene | `xm_scene_gate_links` | Gauge | — | 接入本节点的 gate 链路连接数（含握手中） |
| scene | `xm_scene_link_frames_total` | Counter | `direction`=in / out，`type`=链路帧类型 | gate ↔ scene 链路帧（in = 从链路收到，含握手帧；out = 已交给链路写出，含 hello_ack） |
| scene | `xm_scene_link_dropped_total` | Counter | `reason`=link_gone / write_buffer_full | 没发出去的 scene → gate 链路帧：链路已注销 / 已断开；出站缓冲越过高水位（随即断链） |
| scene | `xm_scene_link_backpressure_pauses_total` | Counter | — | 逻辑线程积压到 `link-max-pending-frames`、暂停读取某条链路的次数（§4.2 背压） |

未覆盖（后续）：Druid 连接池指标（Spring Boot 只认 Hikari / DBCP2 / Tomcat 等连接池的元数据）；
Dubbo 与 Redisson 自带指标未接入；scene 的进场加载（成功 / 失败 / 耗时）未单独计，进场失败目前只有 WARN 日志。
