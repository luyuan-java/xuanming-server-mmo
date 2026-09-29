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

**服务端内部全部按 Java 惯用方式重做**：服务间 RPC 用 Dubbo，注册发现用 Nacos，存储用 MyBatis + Redisson，
节点间长连接用 Netty 自带的 protobuf 编解码。Java 版有自己的库表与 Redis 键空间，**不与 C++/Go 版混部、不共享存储**。

## 2. 进程与模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `xm-proto` | 库（同步产物） | 客户端契约 proto；`MessageIdRegistry`（消息号 ↔ 服务 / 方法 / 请求应答类型） |
| `xm-table` | 库（同步产物） | 配置表 proto + 导表器生成的 `com.game.table` 表管理器 |
| `xm-common` | 库 | 雪花 ID、节点号租约、gate 令牌签名、时间源等无框架公共件 |
| `xm-net` | 库 | Netty：客户端帧编解码（兼容 C++ `ProtobufCodec`）、节点链路编解码 |
| `xm-api` | 库 | Dubbo 服务接口与内部 protobuf 消息（包 `xm.internal`，只在 Java 版内部使用） |
| `xm-discovery` | 库 | 服务发现接缝：Nacos 实现 + 静态配置实现 |
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
  `ClientReply` 带应答体或 tip 码，以及给 gate 的会话指令（绑定账号、进入场景等）。
  后端内部按消息号派发到具体处理方法。
- 服务对服务的调用用各自的类型化接口（如 `SceneDirectoryService`）。

### 4.2 gate ↔ scene 节点链路（长连接、双向）

- gate 主动连每个 scene 节点，一条 TCP 长连接双向复用：gate 发玩家进出与客户端消息，scene 推下行消息。
- 编解码用 Netty 自带的 `ProtobufVarint32FrameDecoder` / `ProtobufVarint32LengthFieldPrepender`，
  帧内是 `xm.internal.NodeLinkFrame`（oneof 信封）。下行携带的是已序列化的 `MessageContent` 字节，gate 原样用客户端帧下发。
- 链路上第一帧是握手（带节点身份与 zone），不匹配即断开。

## 5. 线程模型

- **gate**：Netty I/O 线程处理编解码与会话；同一会话的消息按到达顺序转发（会话绑定到 channel 的 EventLoop）。
  Dubbo 调用异步完成，回调通过 `channel.eventLoop().execute(...)` 回到会话所在线程。
- **scene**：**一个逻辑线程拥有全部场景状态**（Netty `DefaultEventLoop`）。I/O 线程只做解码，把消息投递给逻辑线程；
  阻塞 I/O（MySQL / Redis）在有界的存储线程池上执行，结果再投递回逻辑线程。场景状态只在逻辑线程读写，不加锁。
- 场景对象：普通 Java 对象 + 按实体组织的组件字段，不引入 ECS 库（无达标的库，见选型表）。

## 6. 服务发现与部署 profile

- 接缝 `ServiceDiscovery`：注册本节点（类型、zone、节点号、地址、元数据如在线人数），按类型 + zone 查询实例列表。
- `nacos` profile：Nacos 实现；Dubbo 注册中心同为 Nacos。
- `local` profile：静态实现（地址写在配置里）；Dubbo 用直连 URL。本地开发与端到端测试用它，不依赖 Nacos 服务端。
- 节点号（gate 的 session 高位、雪花 worker）由 Redis 租约分配：`SET NX PX` 占号，定时续期，续期失败即自我下线。

## 7. 存储

- MySQL 库 `xm_java`（与 mmorpg 的库隔离）：`account`、`player`；建表脚本在 `xm-player-store/src/main/resources/db/schema.sql`。
- Redis：全部键带前缀 `xm:`，默认 DB 12（与 mmorpg 的开发数据隔离）。
- **玩家归属围栏**：进场景时 `player.owner_epoch` 原子自增，拿到新 epoch 的 scene 才是该玩家数据的唯一写者；
  所有写回都带 `WHERE owner_epoch = ?`，旧 epoch 的写入影响 0 行即判定被夺权，丢弃并告警。

## 8. 登录进场景调用链（首批竖切）

1. 客户端 `POST /api/assign-gate` → `xm-gateway`：按 zone 取 gate 列表，选在线人数最少者，签 `GateTokenPayload`（TTL 600s）。
2. 客户端 TCP 连 gate，首包 `ClientTokenVerifyRequest` → gate 本地验签（常数时间比较）。
3. `Login(48)` → gate → `xm-login`：鉴权（开发模式口令）、取 / 建账号，回角色列表；`ClientReply` 指示 gate 把账号绑到会话。
4. 角色为空时 `CreatePlayer(14)` → `xm-login`：按配表生成名字与默认职业，写 `player` 表，回角色列表。
5. `EnterGame(26)` → `xm-login`：校验角色归属 → `SceneDirectoryService` 选场景 → 自增 `owner_epoch` →
   回 `EnterGameResponse`，并指示 gate「把会话绑定到该玩家并送进 (场景节点, 场景)」。
6. gate 通过节点链路发 `PlayerEnter` → scene 加载玩家（存储线程池）→ 逻辑线程建玩家、进场景 →
   下发 `NotifyEnterScene(79)` 等初始同步。
7. 进场后的客户端消息（如 `ListSkills(77)`）→ gate 按消息号路由到玩家所在 scene 节点 → scene 逻辑线程处理并回包。
8. 断线 / 离开：gate 通知 scene `PlayerLeave`（scene 写回并移除玩家）与 `xm-login`（清理登录态）。

## 9. ID

- `player_id`：雪花（41 位毫秒 / 10 位 worker / 12 位序号），worker 为 `xm-login` 的节点号；只由 `xm-login` 产生。
- `session_id`（uint32，仅服务端内部）：`[gate 节点号 15 位][序号 17 位]`，跳过 0 与在用号。

## 10. 首批不做（后续批次）

排队、顶号 / 重连、跨 zone、战斗、背包 / 任务 / 货币等玩法系统、Kafka 事件、GM / 管理接口、限流（Sentinel）、
合服与 TiDB 数据层。进度逐项登记在 `PARITY.md`。
