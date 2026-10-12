# xuanming-server-mmo（Java 版）

MMORPG 服务器的 **Java 实现**，与 C++/Go 版（[luyuan-cpp/xuanming-server-mmo](https://github.com/luyuan-cpp/xuanming-server-mmo)）双版本并行演进：
同一个客户端可以连两个版本，服务端内部按各自语言的惯用方式实现。两版的对齐状态见 [PARITY.md](PARITY.md)。

## 架构

```
客户端 ──HTTP──▶ game-gateway（分配 gate、签发令牌）
   │
   ├──TCP（与 C++ 版逐字节兼容的帧）──▶ game-gate ──Dubbo Triple──▶ game-login ──Dubbo──▶ game-scene-manager
   │                                      │                             │
   │                                      ├──Netty 长连接──▶ game-scene ┴──MyBatis──▶ MySQL
   │                                      └──Dubbo──▶ game-friend / game-chat / game-team / game-guild / game-trade / game-match
   │
   └──TCP（同一种帧，凭票据握手，不经 gate）──▶ game-battle ──Dubbo（战斗确认、结算）──▶ game-scene

game-match ──Dubbo（备战 / 取消）──▶ game-scene        game-match ──Dubbo（建房 / 销毁 / 补签 / 登记与清退观众）──▶ game-battle
game-team  ──Dubbo（整队开战：预检、建票、开局）──▶ game-match
game-scene ──Kafka（资产流水、玩家快照）──▶ game-data（落库；带令牌的运维 HTTP 接口）
game-battle ──Kafka（对局结果）──▶ game-match（评分入 MySQL）
Redis（Redisson）：节点目录 / 节点号租约 / 玩家在线目录与推送 / 战斗锁与待结算记录 / 排队队列、票据与战斗落点记录 / 观战标记与可观战索引
```

- **gate**：Netty 接入客户端，令牌握手，按消息号路由：玩家服务转给玩家所在的 scene 节点，其余转给对应后端的 Dubbo 服务。
- **scene**：单个逻辑线程拥有全部场景状态；I/O 线程只解码，数据库读写在有界线程池上执行，结果投回逻辑线程。
- **login / scene-manager**：无状态 Dubbo 服务。玩家数据写回带 `owner_epoch` 围栏，保证同一时刻只有一个写者。
- **friend / chat / team / guild / trade**：社交与经济后端，各自一个 Dubbo 服务进程，由 gate 按消息号转发；给在线玩家的推送查 Redis 在线目录后发到玩家所在的 gate。
- **match**：排队、凑单与开局。票据和队列在 Redis（一组可重放的 Lua），凑单按队列加锁、锚点加评分容差挑人；每次开局一个虚拟线程，逐人让 scene 备战、让 battle 建房，失败按固定次序补偿；
  评分落 MySQL，对局结果由 battle 经 Kafka 发来；另有切磋、战斗票据补签，以及整队开战（game-team 调）与帮会活动开战的内部接口。
  观战的 match 侧也在这里：指定或随机观战、可观战列表、开局前把观众清退出旧场；观战请求每个一条虚拟线程、有在途上限，按战斗落点记录里的地址直接调 battle 登记观众（房间侧——首帧、每回合的观众帧、收尾——在 battle）。
  匹配池不分区：两个区的玩家可以凑成同一局 1V1，各自的结算回到各自区的 scene。
- **battle**：回合制战斗房间。客户端凭票据直连 game-battle，不经 gate（大厅连接上的战斗上行由 gate 当场拒绝）；确认与结算经 Dubbo 发给 scene，结算先落 Redis 再投递，未销账的定时重投；打完的对局结果发到 Kafka。
- **data**：scene 把资产流水与玩家快照发到 Kafka，game-data 消费后幂等落库，并提供带令牌的运维接口（流水查询、GM 快照与差异、回档）。
- 详细设计：[docs/design/architecture.md](docs/design/architecture.md)；选型与理由：[docs/design/tech-stack.md](docs/design/tech-stack.md)。

## 模块

| 模块 | 说明 |
|---|---|
| `game-proto` | 客户端契约 proto（从 C++/Go 版同步）与消息号注册表 |
| `game-table` | 配置表：同步来的权威 schema 与表数据；`ConfigTables` 由 `game-table-codegen` 编译期生成，加载时校验 sha256 / 行数 / 外键 |
| `game-table-codegen` | 配置表代码生成器（javac 注解处理器，只在编译期用）|
| `game-net` | Netty 编解码：客户端帧、节点间链路 |
| `game-common` | 雪花 ID、令牌签名等无框架公共件 |
| `game-battle-engine` | 回合制战斗确定性引擎（纯库，不依赖框架） |
| `game-pbmysql` | proto → MySQL 表映射：建表、结构同步、按消息读写 |
| `game-api` | 服务间契约：Dubbo 接口与内部 protobuf 消息；匹配的跨进程时限常量 |
| `game-discovery` | Redis 上的节点号租约、节点目录、玩家在线目录与服务端 → 玩家推送；回合制战斗的键与脚本；全部 Redis 键名（含匹配的） |
| `game-player-store` | 账号 / 玩家持久化与归属围栏 |
| `game-gateway-store` | 区服目录 / 白名单 / 登录公告的库表与读写 |
| `game-audit` | 资产审计管线的共享件：流水消息格式与 Kafka topic 规格（对局结果 topic 的规格也在这里） |
| `game-gateway` `game-gate` `game-login` `game-scene-manager` `game-scene` | 接入与场景进程：分配 gate 与 HTTP 登录、客户端接入与路由、登录建角、场景分配、场景与玩家逻辑 |
| `game-friend` `game-chat` `game-team` `game-guild` `game-trade` | 社交与经济后端进程（Dubbo 服务）：好友、聊天、组队、帮会、聚宝斋只读面 |
| `game-match` | 匹配进程（Dubbo 服务）：排队、凑单、开局管线与补偿、战斗票据补签、评分、切磋，整队开战与帮会活动开战的 match 侧，观战的 match 侧（观战、可观战列表、开局前清退观众） |
| `game-battle` | battle 节点进程：回合制战斗房间，客户端凭票据直连（不经 gate），结算经发件箱投给 scene，对局结果经 Kafka 发给 match |
| `game-data` | 审计与运维数据服务进程：资产流水与玩家快照经 Kafka 落库，带令牌的运维接口（查询、GM 快照与差异、回档） |
| `game-robot` | 端到端探针客户端：按客户端契约跑各验收场景，不是服务端进程 |

各模块的职责、端口与线程模型见 [docs/design/architecture.md](docs/design/architecture.md) §2。

## 技术栈

Java 21 · Spring Boot 3.5 · Netty · Apache Dubbo 3.3（Triple + protobuf）· Nacos · Redisson · MyBatis · Druid · Protobuf 4 · Apache Kafka（kafka-clients：资产流水与玩家快照，game-scene 生产、game-data 消费；对局结果，game-battle 生产、game-match 消费）。
选型规则：第三方库优先 GitHub ≥ 2 万 star、同类取 star 最高者，偏离须写明理由。

## 快速开始

前置：JDK 21，本地 MySQL（3306）、Redis（6379）与 Kafka（9092）。

```bash
./mvnw -B install                     # 构建并运行全部单元测试

export GAME_MYSQL_PASSWORD=...        # 本地 MySQL root 密码
export GAME_GATE_TOKEN_SECRET=...     # gate 令牌共享密钥：gateway 签普通令牌、scene-manager 签跨 zone 重定向票据、gate 校验（三个进程原值相同，scene-manager 缺它拒启）
export GAME_LOGIN_DEV_PASSWORD=...    # 开发模式登录口令
export GAME_NODE_LINK_SECRET=...      # gate ↔ scene 链路握手密钥
export GAME_DUBBO_SECRET=...          # Dubbo 调用方鉴权密钥（各进程读同一个值）
# export GAME_NAMESPACE=game          # 可选：部署命名空间（缺省 game，名字与以前完全相同）；同一套 MySQL / Redis / Kafka 上起第二个游戏时换一个值
tools/local/start-slice.sh            # 按依赖顺序启动 13 个进程（local profile，Dubbo 直连，不需要 Nacos）
tools/local/stop-slice.sh             # 哪种切片都用同一条命令停（只看 PID 文件）

GAME_SCENE_NODES=2 tools/local/start-slice.sh   # 区 1 再起一个 scene 节点（跨节点换图）
GAME_ZONES=2 tools/local/start-slice.sh         # 再起区 2 的 scene 与 gate，共 15 个进程（跨区 1V1）；两个开关可以同时用
```

> **从改名前的版本升级**（2026-10-12 起 `xm` 前缀统一改为 `game`）：先用改名前版本的 `stop-slice.sh` 停掉旧切片（新脚本只认 `run/pids/game-*.pid`；
> 或 `for p in run/pids/xm-*.pid; do kill "$(cat "$p")"; done`），删掉 `run/xm-*`；`export` 的 `XM_*` 全部换成 `GAME_*`；
> 本机 MySQL 库与 Redis 一起重建或一起迁移（库 `xm_java` → `game`、键前缀 `xm:` → `game:`；compose 项目名 `xuanming-java` → `game-java`，旧卷用 `docker compose -p xuanming-java -f deploy/compose/infra.yaml down -v` 清掉，见 `docs/design/db-migrations.md` M12）。新旧进程不能混跑。

> **部署命名空间** `GAME_NAMESPACE`（可选，缺省 `game`）：Redis 键前缀 `<ns>:`、Kafka topic / 消费组 `<ns>-`、MySQL 库 `<ns>` 都取它，
> 多个游戏（或多套部署）因此能共用同一套中间件而互不串数据。缺省值下所有名字与以前逐字节相同。合法值 `[a-z][a-z0-9]{0,27}`
> （1–28 个字符，不能是 mysql / sys / public），非法即拒启（Spring Boot 的标准绑定校验报告）；推荐经环境变量给（命令行工具与服务进程同口径绑定，
> `-Dgame.namespace` 两边也都认），一个部署的全部进程与命令行工具必须同值，定下后不能改。
> 起切片时写 `GAME_NAMESPACE=t1 tools/local/start-slice.sh`：脚本先校验；本检出已有切片在跑就拒绝（先 `stop-slice.sh`）；再把生效的值记进 `run/game-namespace`
> （`battle-crash-window.sh` 读回核对，`stop-slice.sh` 停完后删掉）；设成空串不会被换成 `game`，照样拒绝。在 export 过 `GAME_NAMESPACE` 的 shell 里照样可以跑
> `./mvnw test`（surefire 不把它传给测试进程），但不要给 mvn 传 `-Dgame.namespace`（AGENTS.md §4）。**库名必须等于命名空间**：连库的服务进程与三个命令行工具（AuditFallbackReplay、assetopfix、PasswordAdmin）启动时核对 `DATABASE()`，
> 库名不叫 `game` 的存量环境升级前先看 [db-migrations.md M13](docs/design/db-migrations.md)。
> nacos profile 下 Nacos namespace 不随它自动变：多个部署共用一个 Nacos 时在 `GAME_NACOS_ADDR` 里带 `?namespace=<ID>`（或用 Dubbo 的配置项 `dubbo.registry.parameters.namespace`）
> 显式配置，并先在 Nacos 建好 ID 等于该值的命名空间；不设则落在 public（与以前相同）。环境变量 `DUBBO_REGISTRY_PARAMETERS_NAMESPACE` Dubbo 不认，别用。
> 这是命名隔离，不是安全边界；细节见 [architecture.md §6.2](docs/design/architecture.md)。

进程清单与启动顺序以 `tools/local/start-slice.sh` 的 `SERVICES` 为准（scene-manager、login、friend、chat、match、team、guild、trade、data、scene、gate、gateway、battle；`GAME_SCENE_NODES=2` 时多起一个 scene 节点 `game-scene-2`）。
`GAME_ZONES=2` 时在这十三项之外再起区 2 的两个实例（它们不在 `SERVICES` 里，各自紧跟区 1 的同类启动）：`game-scene-z2`（节点链路 21010、资产通道 21110、管理端口 18115）与 `game-gate-z2`（客户端 11010、管理端口 18123），其余进程两个区共用；
区 2 的 scene / gate 节点号也是 1 号（节点号按区分配，与区 1 同号是有意的）。区 2 在区服列表里的状态由脚本跟着 `GAME_ZONES` 改：=2 时置为开放（库里还没有这个区就建出来），=1 时置为维护，脚本等区服列表反映出来才报「全部就绪」。
区 2 的客户端入口是 `assign-gate` 带 `{"zone_id":2}`。
次序里有一条硬约束：静态直连的 Dubbo 提供方要先于调用方启动，所以 game-match（Dubbo 20888、管理端口 18113）排在 game-team 与 game-gate 之前；运行中单独重启某个后端后，调用方到它的连接约一分钟才重连上。
运维令牌、GM 停机密钥、帮会资产指令签名密钥、战斗票据密钥没设环境变量时由脚本生成到 `run/`；日志在 `run/logs/`。

端到端验收用 `game-robot`（按客户端契约跑登录、移动、组队、帮会、匹配与战斗、观战、跨区 1V1、跨 zone 传送、战斗结算、GM 回档等场景，退出码即结论），在同一个 shell 里执行：
`java -jar game-robot/target/game-robot-*.jar smoke`；场景与选项见 `java -jar game-robot/target/game-robot-*.jar --help` 与 [game-robot/README.md](game-robot/README.md)。
双 zone 切片上：`smoke --zone 2` 核对区 2 能登录进场，`battle-cross-zone --zone 1 --visit-zone 2` 跑跨区 1V1（要 dev 运行模式，切片缺省就是）；
`travel --zone 1 --visit-zone 2`、`travel-abandon`、`travel-long` 跑跨 zone 传送 226 / 重定向 124（批次 5.4），`team` / `guild` / `reconnect` 带 `--cross-zone require` 跑它们的跨区步骤（缺省 `auto`：有第二个区才跑）——
前置、步骤与结果行见 [game-robot/README.md](game-robot/README.md)。5.4 的这些场景**还没有在真切片上跑过**（只在 robot 的假服务端上跑过单测），状态见 PARITY「跨 zone 传送（226）与重定向（124）」行。

客户端入口：`POST http://127.0.0.1:18081/api/assign-gate`，body 为 `{"zone_id":1}`。

## 同步客户端契约

```bash
java tools/ContractSync.java --mmorpg <C++/Go 版仓库根目录>
java tools/ContractSync.java --mmorpg <C++/Go 版仓库根目录> --check   # 只检查是否一致（CI 用）
```

契约相关文件是同步产物，不要手改；同步来源的 commit 记录在 `contract/SOURCE.properties`。
