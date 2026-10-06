# 选型表（Java 版）

规则（用户 2026-09-29 定）：按 Java 最标准的方式做，不照抄 C++/Go；第三方库优先 GitHub ≥ 2 万 star，
同类候选取 **star 最高**者。JDK 与 Spring 自带组件（Jackson、Micrometer、JUnit 等随 Spring Boot 管理的依赖）
视为事实标准，不受 star 门槛约束。偏离这条规则必须在本表写明原因。

star 数为 2026-09-29 GitHub API 实测（CI、本地编排、依赖镜像、镜像构建、构建信息这几行是 2026-10-05 实测，批次 7.1a）。

| 用途 | 选用 | star | 落选（star） | 说明 |
|---|---|---|---|---|
| 应用框架 / 装配 | Spring Boot 3.5.16 | 81.5K | — | Dubbo 3.3.6 只支持 Boot 3.x，因此不上 Boot 4 |
| 网络（gate / scene / 节点链路） | Netty（Boot 管理的 4.1.x） | 35.1K | Vert.x (14.7K) | gate 客户端面与节点间长连接都在 Netty 上 |
| 服务间 RPC | Apache Dubbo 3.3.6（Triple 协议） | 41.6K | grpc-java (12.1K) | 接口参数与返回值都用 protobuf 消息，Triple 直接走 protobuf 序列化。xm-data 自批次 7.2b 起是 `GuildInternalService`（group guild）的调用方（回档的帮会资产检查）：编程式引用（xm-api 的 `IsolatedDubboModule`，同 xm-scene / xm-guild 的资产通道客户端），只依赖 dubbo 核心包、不引入 dubbo-spring-boot-starter，只读与 dry-run 的进程不起 Dubbo；只支持直连 `xm.dubbo.guild-url`，nacos 发现未接。不新增第三方库。批次 6.3 起 xm-scene 在资产 RPC 端口上多导出一个 `SceneBattleService`（group scene-battle，`register = false`），xm-battle 是它的调用方：按 Redis 节点目录直连的编程式引用（xm-api 的 `NodeRpcClients<S>`，`retries = 0`），两端用的都是已有的 dubbo 核心包，同样不新增第三方库 |
| 注册中心 / 配置中心 | Nacos | 33.4K | ZooKeeper (12.8K)、jetcd (1.2K) | 只做 Dubbo 注册中心；`local` profile 用 Dubbo 直连，不需要 Nacos 服务端（见 architecture.md §6） |
| 消息队列 | Apache Kafka，官方客户端 `kafka-clients`（版本由 Spring Boot BOM 管理，当前 3.9.2；本机 broker 4.3.1 KRaft） | 33.9K | RocketMQ (22.6K) | 资产审计管线（architecture.md §4.5）。不用 spring-kafka 的监听容器 / KafkaTemplate：线程归属与「落库成功才提交位点」要显式掌握 |
| Redis 客户端 | Redisson 3.50.0（核心包） | 24.4K | Jedis (12.4K)、Lettuce (5.8K) | 不用 redisson-spring-boot-starter：它会带进 Lettuce |
| SQL 映射 | MyBatis（mybatis-spring-boot-starter 3.0.5） | 20.4K | MyBatis-Plus (17.5K)、Hibernate | |
| proto → MySQL 表映射 | xm-pbmysql（用户自有 proto2mysql 的 Java 实现，对齐 Go v0.2.0） | — | MyBatis 手写 DDL / Mapper | 用户自有库，不受 star 门槛约束；行就是 protobuf message 的表（好友 / 邮件 / 帮会等社交服务，表选项写在 proto 上，与 mmorpg `proto/db` 的表消息同一套写法）由它生成建表 DDL（与 Go 版逐字节相同）、只扩不缩地同步结构、按消息做 CRUD。纯 JDBC、不依赖 Spring，事务由调用方传入的 `Connection` 掌握；`player` / `player_state` 仍走 MyBatis。xm-data 的运维作业表（`ops_job` 等，批次 7.2a）也用它；批次 7.2b 起 `ops_active`（全集群单飞槽）/ `ops_job_player`（逐玩家明细）启用、`ops_job` 加 `cancel_requested`（启动时只扩不缩地补列），与 MyBatis 的 Mapper（玩家归属 / 快照 / 流水）共用同一条事务连接；已有的 `transaction_log` / `player_snapshot` 仍是 MyBatis 手写表。用法与边界见 architecture.md §7 |
| 连接池 | Druid（druid-spring-boot-3-starter 1.2.28） | 28.2K | HikariCP (21.2K) | 同类取 star 最高 |
| 限流 / 熔断 | Sentinel | 23.1K | resilience4j (10.8K) | 进程内限流 / 熔断，后续批次（gateway 的开服限流见下一行） |
| 开服限流（gateway 多副本共享的令牌桶） | Redis Lua 令牌桶（经 Redisson `RScript`，不加依赖） | — | Bucket4j（2.6K，基线用它 + Redis）、Sentinel 集群流控 | Bucket4j 不到 2 万 star；Sentinel 的集群流控要另起 token server，按 IP 的热点参数限流是进程内统计，多副本下不是一个桶。一段 Lua 原子地「按流逝时间补充、取一个」就是分布式令牌桶（与 Bucket4j 的贪心补充同算法），冷却用 `SET NX PX`。见 PARITY「开服限流」行 |
| 通用工具 / 本地缓存 | Guava 33.4.0-jre | 51.9K | Caffeine (17.9K) | |
| 注册中心之外的节点在线目录 | Redis（Redisson `RMapCache`，条目带 TTL） | — | — | 见 architecture.md §6 |
| 序列化（协议） | Protobuf 4.35.1 | 72.1K | — | 与 mmorpg 的 protoc 35.1 同代，生成代码对运行时有强校验 |
| JSON | Jackson（Spring 自带） | — | fastjson 1.x (25.6K) | **偏离说明**：fastjson 1.x 已停止维护且有 autotype 远程代码执行漏洞史；按 AGENTS.md 取舍顺序「安全 > 其他」，改用 Spring 自带的 Jackson。例外：把 protobuf 消息转成 JSON 用 protobuf 自带的 `protobuf-java-util`（`JsonFormat`，与 protobuf-java 同一项目、同一版本，根 BOM 管理），目前只用在 xm-data 快照详情里把 `player_state` 给运维看（批次 7.2a） |
| 指标 | Micrometer（`micrometer-core`）+ `micrometer-registry-prometheus`，经 `spring-boot-starter-actuator` 导出（版本均由 Spring Boot 3.5.16 BOM 管理：Micrometer 1.15.12、Prometheus Java client 1.3.10） | — | Dropwizard Metrics、直接用 Prometheus client | 属于「Spring Boot 管理的依赖」，不受 star 门槛约束。端点、端口与指标清单见 architecture.md §11。gate / login / scene-manager / scene 本身不是 Web 进程，为管理端点引入 `spring-boot-starter-web`（Tomcat，只挂 actuator，4 个线程、默认只绑本机）；不引入 Spring 之外的 HTTP 服务器 |
| 测试 | JUnit 5 / Mockito / AssertJ / Awaitility（spring-boot-starter-test 自带） | — | Testcontainers (8.7K) | 集成测试连本机进程（Redis / MySQL / Kafka），按 `-Dxm.it.*` 开启，缺省跳过；Kafka 用客户端自带的 MockProducer / MockConsumer 做单测。CI 的集成测试用 `deploy/compose/infra.yaml` 起同样版本的依赖（批次 7.1a，见下面 CI 行）。执行器是 Maven 自带的 maven-surefire-plugin（缺省绑定，不是新依赖）：批次 6.3 起根 pom 的 pluginManagement 把它钉在 3.2.5（就是此前 Maven 缺省解析出来的版本，行为不变），并设测试进程时限 `forkedProcessTimeoutInSeconds = 1800`——某条用例无限等待（例如对永不完成的 future 调 `join()`）时构建在 30 分钟后失败，而不是把测试进程永远挂住；各模块自己的 surefire `configuration`（如测试用的环境变量）与它合并。另：JUnit 5 的 `@Timeout` 缺省是 SAME_THREAD，到时只对测试线程发一次中断，对 `CompletableFuture.join()` 无效；要靠它兜「无限等待」的回归必须显式写 `SEPARATE_THREAD`（`TeamDisplayTest` 的做法） |
| 测试用内存数据库（仅 test 作用域） | H2（Spring Boot BOM 管理的版本，当前 2.3.232） | — | HSQLDB、Derby | xm-player-store 的 `PlayerStoreSqlTest` 以 MySQL 兼容模式跑生产建表脚本与 Mapper，验证归属夺权 / 释放 / 续约与建角上限的 SQL 和事务语义，不需要外部 MySQL。按本表规则属于「随 Spring Boot 管理的依赖」，不受 star 门槛约束；只进测试 classpath。H2 的重键错误码与 MySQL 不同，「撞名」分支仍由替身测试覆盖 |
| 口令哈希（Argon2id，生产口令认证） | BouncyCastle `bcprov-jdk18on` 1.86（只用 `Argon2BytesGenerator`） | 2.7K | spring-security-crypto 的 `Argon2PasswordEncoder`（Spring Security 9.6K，内部同样调 BouncyCastle）、Password4j (0.4K)、argon2-jvm (0.4K，要本机 libargon2) | **偏离说明**：JDK 没有 Argon2，Java 生态也没有 ≥ 2 万 star 的 Argon2 实现；BouncyCastle 是 JDK 之外的 Java 密码学事实标准（Spring Security 的 Argon2 也基于它），纯 Java、无本机库。不经 Spring Security 包一层：PHC 串的解析策略（参数上下限、拒绝填充与重复键）要与 mmorpg 逐条对齐，自己编解码更直接。见 PARITY「生产口令认证」行 |
| CI | GitHub Actions；只用 GitHub 官方 action：`actions/checkout` v7.0.1、`actions/setup-java` v6.0.1、`actions/upload-artifact` v7.0.1，都按 commit SHA 钉死 | — | 第三方 action（如 docker/build-push-action 5.4K）、Testcontainers (8.7K)；静态检查 hadolint (12.5K) / actionlint (4.3K) | 仓库托管平台自带，不受 star 门槛约束（批次 7.1a，deploy-ci-spec §4）。runner 钉 ubuntu-24.04；docker 直接用 runner 预装的 CLI；测试汇总与「IT 真的执行了」的检查是自写的 `tools/TestReport.java`（JDK 单文件，不加依赖）。hadolint / actionlint 不到 2 万 star，不引入（workflow 语法由 GitHub 在推送时校验） |
| 本地编排 / CI 依赖 | Docker Compose v2 | 38.3K | — | `deploy/compose/infra.yaml` 起 MySQL / Redis / Kafka（批次 7.1a）；7.1b 的整栈 `stack.yaml` 用 `include`，要求 Compose ≥ 2.20 |
| 依赖镜像 | `mysql:8.4.11`、`redis:8.10.2`、`apache/kafka:4.3.1`，钉 tag + 多架构 index digest | redis 76.6K、kafka 33.9K、mysql-server 12.4K | — | 存储选型早已定下，这里只定版本：与开发机上实装的版本相同。更新方法写在 `infra.yaml` 文件头 |
| 镜像构建 | Dockerfile（Docker / BuildKit，moby 72.1K）+ Spring Boot 自带的 `-Djarmode=tools extract --layers` 分层解包 | 72.1K / Boot 自带 | Jib (14.4K)、Buildpacks（buildpacks/pack 3.0K、paketo-buildpacks/java 151） | 一个参数化 Dockerfile 服务全部进程模块，jar 由宿主 Maven 打出（deploy-ci-spec §3）。Buildpacks 的 goal 虽然是 Boot 自带的，实际干活的是 Paketo builder，不满足 star 门槛，而且会用它的内存计算器改写 `-Xmx` |
| 基础镜像 | `eclipse-temurin:21.0.12.1_1-jre-noble`（钉 index digest） | — | alpine（基线 `Dockerfile.java-svc`）、Paketo 缺省的 Liberica | 与开发机 JDK 21.0.12.1+1 是同一发行版的同一个构建；glibc，自带 curl 和 bash（compose healthcheck 要用） |
| 构建信息 | spring-boot-maven-plugin 的 `build-info` goal（Boot 自带） | — | git-commit-id-maven-plugin (1.7K) | commit 与契约来源由 CI / 镜像构建用 `-D` 传入（批次 7.1a，deploy-ci-spec §5） |
| ECS | 不引入，也不手写 ECS 模式（无 ≥2 万 star 的 Java ECS 库；Java 版没有 entt，按领域对象 + 服务实现） | — | Artemis-odb、Ashley（均 < 3K） | 场景用普通领域对象，见 architecture.md §5 |

## 传递依赖说明

- Dubbo 3.3.6 会传递引入 fastjson2（alibaba/fastjson2，约 4K star），用于其内部元数据 / 配置序列化。本仓库代码不直接使用它，
  JSON 一律用 Jackson（protobuf 消息转 JSON 用 protobuf 自带的 `JsonFormat`，见 JSON 行）；Dubbo 的业务序列化走 protobuf（接口参数与返回值都是 protobuf 消息）。

## 版本约束

- protobuf-java 必须与 protoc 同版本（4.35.1），升级时两者一起动，并同步检查 mmorpg 的 protoc 版本。protobuf-java-util 与 protobuf-java 同版本（根 pom 同一个属性）。
- 依赖镜像、基础镜像与 GitHub 官方 action 都钉 digest / commit SHA。升级方法写在各文件头（`deploy/compose/infra.yaml`、`deploy/docker/Dockerfile`、`.github/workflows/ci.yml`）。
  依赖镜像的版本要与开发机上实装的进程一起动。
- Netty、Jackson、Micrometer（含 micrometer-registry-prometheus 及其 Prometheus client）、Actuator、Tomcat、mysql-connector-j、
  kafka-clients 由 Spring Boot BOM 统一管理，不单独指定版本。
- Dubbo 由 dubbo-bom 管理。Dubbo 发布支持 Spring Boot 4 之前，Spring Boot 停在 3.5.x。
- maven-surefire-plugin 的版本（3.2.5）与测试进程时限（1800 s）在根 pom 的 pluginManagement 里统一给，模块里不再单独写版本（见「测试」行）。
