# xuanming-server-mmo（Java 版）

MMORPG 服务器的 **Java 实现**，与 C++/Go 版（[luyuan-cpp/xuanming-server-mmo](https://github.com/luyuan-cpp/xuanming-server-mmo)）双版本并行演进：
同一个客户端可以连两个版本，服务端内部按各自语言的惯用方式实现。两版的对齐状态见 [PARITY.md](PARITY.md)。

## 架构

```
客户端 ──HTTP──▶ xm-gateway（分配 gate、签发令牌）
   │
   ├──TCP（与 C++ 版逐字节兼容的帧）──▶ xm-gate ──Dubbo Triple──▶ xm-login ──Dubbo──▶ xm-scene-manager
   │                                      │                          │
   │                                      ├──Netty 长连接──▶ xm-scene ┴──MyBatis──▶ MySQL
   │                                      └──Dubbo──▶ xm-friend / xm-chat / xm-team / xm-guild / xm-trade / xm-match
   │
   └──TCP（同一种帧，凭票据握手，不经 gate）──▶ xm-battle ──Dubbo（战斗确认、结算）──▶ xm-scene

xm-match ──Dubbo（备战 / 取消）──▶ xm-scene        xm-match ──Dubbo（建房 / 销毁 / 补签）──▶ xm-battle
xm-team  ──Dubbo（整队开战：预检、建票、开局）──▶ xm-match
xm-scene ──Kafka（资产流水、玩家快照）──▶ xm-data（落库；带令牌的运维 HTTP 接口）
xm-battle ──Kafka（对局结果）──▶ xm-match（评分入 MySQL）
Redis（Redisson）：节点目录 / 节点号租约 / 玩家在线目录与推送 / 战斗锁与待结算记录 / 排队队列、票据与战斗落点记录
```

- **gate**：Netty 接入客户端，令牌握手，按消息号路由：玩家服务转给玩家所在的 scene 节点，其余转给对应后端的 Dubbo 服务。
- **scene**：单个逻辑线程拥有全部场景状态；I/O 线程只解码，数据库读写在有界线程池上执行，结果投回逻辑线程。
- **login / scene-manager**：无状态 Dubbo 服务。玩家数据写回带 `owner_epoch` 围栏，保证同一时刻只有一个写者。
- **friend / chat / team / guild / trade**：社交与经济后端，各自一个 Dubbo 服务进程，由 gate 按消息号转发；给在线玩家的推送查 Redis 在线目录后发到玩家所在的 gate。
- **match**：排队、凑单与开局。票据和队列在 Redis（一组可重放的 Lua），凑单按队列加锁、锚点加评分容差挑人；每次开局一个虚拟线程，逐人让 scene 备战、让 battle 建房，失败按固定次序补偿；
  评分落 MySQL，对局结果由 battle 经 Kafka 发来；另有切磋、战斗票据补签，以及整队开战（xm-team 调）与帮会活动开战的内部接口。
- **battle**：回合制战斗房间。客户端凭票据直连 xm-battle，不经 gate（大厅连接上的战斗上行由 gate 当场拒绝）；确认与结算经 Dubbo 发给 scene，结算先落 Redis 再投递，未销账的定时重投；打完的对局结果发到 Kafka。
- **data**：scene 把资产流水与玩家快照发到 Kafka，xm-data 消费后幂等落库，并提供带令牌的运维接口（流水查询、GM 快照与差异、回档）。
- 详细设计：[docs/design/architecture.md](docs/design/architecture.md)；选型与理由：[docs/design/tech-stack.md](docs/design/tech-stack.md)。

## 模块

| 模块 | 说明 |
|---|---|
| `xm-proto` | 客户端契约 proto（从 C++/Go 版同步）与消息号注册表 |
| `xm-table` | 配置表：同步来的权威 schema 与表数据；`ConfigTables` 由 `xm-table-codegen` 编译期生成，加载时校验 sha256 / 行数 / 外键 |
| `xm-table-codegen` | 配置表代码生成器（javac 注解处理器，只在编译期用）|
| `xm-net` | Netty 编解码：客户端帧、节点间链路 |
| `xm-common` | 雪花 ID、令牌签名等无框架公共件 |
| `xm-battle-engine` | 回合制战斗确定性引擎（纯库，不依赖框架） |
| `xm-pbmysql` | proto → MySQL 表映射：建表、结构同步、按消息读写 |
| `xm-api` | 服务间契约：Dubbo 接口与内部 protobuf 消息；匹配的跨进程时限常量 |
| `xm-discovery` | Redis 上的节点号租约、节点目录、玩家在线目录与服务端 → 玩家推送；回合制战斗的键与脚本；全部 Redis 键名（含匹配的） |
| `xm-player-store` | 账号 / 玩家持久化与归属围栏 |
| `xm-gateway-store` | 区服目录 / 白名单 / 登录公告的库表与读写 |
| `xm-audit` | 资产审计管线的共享件：流水消息格式与 Kafka topic 规格（对局结果 topic 的规格也在这里） |
| `xm-gateway` `xm-gate` `xm-login` `xm-scene-manager` `xm-scene` | 接入与场景进程：分配 gate 与 HTTP 登录、客户端接入与路由、登录建角、场景分配、场景与玩家逻辑 |
| `xm-friend` `xm-chat` `xm-team` `xm-guild` `xm-trade` | 社交与经济后端进程（Dubbo 服务）：好友、聊天、组队、帮会、聚宝斋只读面 |
| `xm-match` | 匹配进程（Dubbo 服务）：排队、凑单、开局管线与补偿、战斗票据补签、评分、切磋，整队开战与帮会活动开战的 match 侧 |
| `xm-battle` | battle 节点进程：回合制战斗房间，客户端凭票据直连（不经 gate），结算经发件箱投给 scene，对局结果经 Kafka 发给 match |
| `xm-data` | 审计与运维数据服务进程：资产流水与玩家快照经 Kafka 落库，带令牌的运维接口（查询、GM 快照与差异、回档） |
| `xm-robot` | 端到端探针客户端：按客户端契约跑各验收场景，不是服务端进程 |

各模块的职责、端口与线程模型见 [docs/design/architecture.md](docs/design/architecture.md) §2。

## 技术栈

Java 21 · Spring Boot 3.5 · Netty · Apache Dubbo 3.3（Triple + protobuf）· Nacos · Redisson · MyBatis · Druid · Protobuf 4 · Apache Kafka（kafka-clients：资产流水与玩家快照，xm-scene 生产、xm-data 消费；对局结果，xm-battle 生产、xm-match 消费）。
选型规则：第三方库优先 GitHub ≥ 2 万 star、同类取 star 最高者，偏离须写明理由。

## 快速开始

前置：JDK 21，本地 MySQL（3306）、Redis（6379）与 Kafka（9092）。

```bash
./mvnw -B install                     # 构建并运行全部单元测试

export XM_MYSQL_PASSWORD=...          # 本地 MySQL root 密码
export XM_GATE_TOKEN_SECRET=...       # gateway 签发 / gate 校验令牌的共享密钥
export XM_LOGIN_DEV_PASSWORD=...      # 开发模式登录口令
export XM_NODE_LINK_SECRET=...        # gate ↔ scene 链路握手密钥
export XM_DUBBO_SECRET=...            # Dubbo 调用方鉴权密钥（各进程读同一个值）
tools/local/start-slice.sh            # 按依赖顺序启动 13 个进程（local profile，Dubbo 直连，不需要 Nacos）
tools/local/stop-slice.sh
```

进程清单与启动顺序以 `tools/local/start-slice.sh` 的 `SERVICES` 为准（scene-manager、login、friend、chat、match、team、guild、trade、data、scene、gate、gateway、battle；`XM_SCENE_NODES=2` 时多起一个 scene 节点）。
次序里有一条硬约束：静态直连的 Dubbo 提供方要先于调用方启动，所以 xm-match（Dubbo 20888、管理端口 18113）排在 xm-team 与 xm-gate 之前；运行中单独重启某个后端后，调用方到它的连接约一分钟才重连上。
运维令牌、GM 停机密钥、帮会资产指令签名密钥、战斗票据密钥没设环境变量时由脚本生成到 `run/`；日志在 `run/logs/`。

端到端验收用 `xm-robot`（按客户端契约跑登录、移动、组队、帮会、匹配与战斗、战斗结算、GM 回档等场景，退出码即结论），在同一个 shell 里执行：
`java -jar xm-robot/target/xm-robot-*.jar smoke`；场景与选项见 `java -jar xm-robot/target/xm-robot-*.jar --help` 与 [xm-robot/README.md](xm-robot/README.md)。

客户端入口：`POST http://127.0.0.1:18081/api/assign-gate`，body 为 `{"zone_id":1}`。

## 同步客户端契约

```bash
java tools/ContractSync.java --mmorpg <C++/Go 版仓库根目录>
java tools/ContractSync.java --mmorpg <C++/Go 版仓库根目录> --check   # 只检查是否一致（CI 用）
```

契约相关文件是同步产物，不要手改；同步来源的 commit 记录在 `contract/SOURCE.properties`。
