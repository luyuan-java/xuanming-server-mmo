# xuanming-server-mmo（Java 版）

MMORPG 服务器的 **Java 实现**，与 C++/Go 版（[luyuan-cpp/xuanming-server-mmo](https://github.com/luyuan-cpp/xuanming-server-mmo)）双版本并行演进：
同一个客户端可以连两个版本，服务端内部按各自语言的惯用方式实现。两版的对齐状态见 [PARITY.md](PARITY.md)。

## 架构

```
客户端 ──HTTP──▶ xm-gateway（分配 gate、签发令牌）
   │
   └──TCP（与 C++ 版逐字节兼容的帧）──▶ xm-gate ──Dubbo Triple──▶ xm-login ──Dubbo──▶ xm-scene-manager
                                          │                          │
                                          └──Netty 长连接──▶ xm-scene ┴──MyBatis──▶ MySQL
                                                                         Redisson ──▶ Redis（节点目录 / 节点号租约）
```

- **gate**：Netty 接入客户端，令牌握手，按消息号路由：玩家服务转给玩家所在的 scene 节点，其余转给对应后端的 Dubbo 服务。
- **scene**：单个逻辑线程拥有全部场景状态；I/O 线程只解码，数据库读写在有界线程池上执行，结果投回逻辑线程。
- **login / scene-manager**：无状态 Dubbo 服务。玩家数据写回带 `owner_epoch` 围栏，保证同一时刻只有一个写者。
- 详细设计：[docs/design/architecture.md](docs/design/architecture.md)；选型与理由：[docs/design/tech-stack.md](docs/design/tech-stack.md)。

## 模块

| 模块 | 说明 |
|---|---|
| `xm-proto` | 客户端契约 proto（从 C++/Go 版同步）与消息号注册表 |
| `xm-table` | 配置表（导表器生成）|
| `xm-net` | Netty 编解码：客户端帧、节点间链路 |
| `xm-common` | 雪花 ID、令牌签名等无框架公共件 |
| `xm-api` | 服务间契约：Dubbo 接口与内部 protobuf 消息 |
| `xm-discovery` | Redis 上的节点号租约与节点在线目录 |
| `xm-player-store` | 账号 / 玩家持久化与归属围栏 |
| `xm-gateway` `xm-gate` `xm-login` `xm-scene-manager` `xm-scene` | 各进程 |

## 技术栈

Java 21 · Spring Boot 3.5 · Netty · Apache Dubbo 3.3（Triple + protobuf）· Nacos · Redisson · MyBatis · Druid · Protobuf 4 · Kafka（后续批次）。
选型规则：第三方库优先 GitHub ≥ 2 万 star、同类取 star 最高者，偏离须写明理由。

## 快速开始

前置：JDK 21，本地 MySQL（3306）与 Redis（6379）。

```bash
./mvnw -B install                     # 构建并运行全部单元测试

export XM_MYSQL_PASSWORD=...          # 本地 MySQL root 密码
export XM_GATE_TOKEN_SECRET=...       # gateway 签发 / gate 校验令牌的共享密钥
export XM_LOGIN_DEV_PASSWORD=...      # 开发模式登录口令
export XM_NODE_LINK_SECRET=...        # gate ↔ scene 链路握手密钥
tools/local/start-slice.sh            # 按依赖顺序启动 5 个进程（local profile，Dubbo 直连，不需要 Nacos）
tools/local/stop-slice.sh
```

客户端入口：`POST http://127.0.0.1:18081/api/assign-gate`，body 为 `{"zone_id":1}`。

## 同步客户端契约

```bash
java tools/ContractSync.java --mmorpg <C++/Go 版仓库根目录>
```

契约相关文件是同步产物，不要手改；同步来源的 commit 记录在 `contract/SOURCE.properties`。
