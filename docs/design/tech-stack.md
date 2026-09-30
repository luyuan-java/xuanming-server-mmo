# 选型表（Java 版）

规则（用户 2026-09-29 定）：按 Java 最标准的方式做，不照抄 C++/Go；第三方库优先 GitHub ≥ 2 万 star，
同类候选取 **star 最高**者。JDK 与 Spring 自带组件（Jackson、Micrometer、JUnit 等随 Spring Boot 管理的依赖）
视为事实标准，不受 star 门槛约束。偏离这条规则必须在本表写明原因。

star 数为 2026-09-29 GitHub API 实测。

| 用途 | 选用 | star | 落选（star） | 说明 |
|---|---|---|---|---|
| 应用框架 / 装配 | Spring Boot 3.5.16 | 81.5K | — | Dubbo 3.3.6 只支持 Boot 3.x，因此不上 Boot 4 |
| 网络（gate / scene / 节点链路） | Netty（Boot 管理的 4.1.x） | 35.1K | Vert.x (14.7K) | gate 客户端面与节点间长连接都在 Netty 上 |
| 服务间 RPC | Apache Dubbo 3.3.6（Triple 协议） | 41.6K | grpc-java (12.1K) | 接口参数与返回值都用 protobuf 消息，Triple 直接走 protobuf 序列化 |
| 注册中心 / 配置中心 | Nacos | 33.4K | ZooKeeper (12.8K)、jetcd (1.2K) | 只做 Dubbo 注册中心；`local` profile 用 Dubbo 直连，不需要 Nacos 服务端（见 architecture.md §6） |
| 消息队列 | Apache Kafka（spring-kafka） | 33.9K | RocketMQ (22.6K) | 首批竖切未用到，后续批次用 |
| Redis 客户端 | Redisson 3.50.0（核心包） | 24.4K | Jedis (12.4K)、Lettuce (5.8K) | 不用 redisson-spring-boot-starter：它会带进 Lettuce |
| SQL 映射 | MyBatis（mybatis-spring-boot-starter 3.0.5） | 20.4K | MyBatis-Plus (17.5K)、Hibernate | |
| 连接池 | Druid（druid-spring-boot-3-starter 1.2.28） | 28.2K | HikariCP (21.2K) | 同类取 star 最高 |
| 限流 / 熔断 | Sentinel | 23.1K | resilience4j (10.8K) | 后续批次 |
| 通用工具 / 本地缓存 | Guava 33.4.0-jre | 51.9K | Caffeine (17.9K) | |
| 注册中心之外的节点在线目录 | Redis（Redisson `RMapCache`，条目带 TTL） | — | — | 见 architecture.md §6 |
| 序列化（协议） | Protobuf 4.35.1 | 72.1K | — | 与 mmorpg 的 protoc 35.1 同代，生成代码对运行时有强校验 |
| JSON | Jackson（Spring 自带） | — | fastjson 1.x (25.6K) | **偏离说明**：fastjson 1.x 已停止维护且有 autotype 远程代码执行漏洞史；按 AGENTS.md 取舍顺序「安全 > 其他」，改用 Spring 自带的 Jackson |
| 指标 | Micrometer（`micrometer-core`）+ `micrometer-registry-prometheus`，经 `spring-boot-starter-actuator` 导出（版本均由 Spring Boot 3.5.16 BOM 管理：Micrometer 1.15.12、Prometheus Java client 1.3.10） | — | Dropwizard Metrics、直接用 Prometheus client | 属于「Spring Boot 管理的依赖」，不受 star 门槛约束。端点、端口与指标清单见 architecture.md §11。gate / login / scene-manager 本身不是 Web 进程，为管理端点引入 `spring-boot-starter-web`（Tomcat，只挂 actuator，4 个线程、默认只绑本机）；不引入 Spring 之外的 HTTP 服务器 |
| 测试 | JUnit 5 / Mockito / AssertJ（spring-boot-starter-test 自带） | — | Testcontainers (8.7K) | 集成测试用仓库自带的 docker compose |
| 测试用内存数据库（仅 test 作用域） | H2（Spring Boot BOM 管理的版本，当前 2.3.232） | — | HSQLDB、Derby | xm-player-store 的 `PlayerStoreSqlTest` 以 MySQL 兼容模式跑生产建表脚本与 Mapper，验证归属夺权 / 释放 / 续约与建角上限的 SQL 和事务语义，不需要外部 MySQL。按本表规则属于「随 Spring Boot 管理的依赖」，不受 star 门槛约束；只进测试 classpath。H2 的重键错误码与 MySQL 不同，「撞名」分支仍由替身测试覆盖 |
| ECS | 不引入（无 ≥2 万 star 的 Java ECS 库） | — | Artemis-odb、Ashley（均 < 3K） | 场景用普通对象 + 组件表，见 architecture.md §5 |

## 传递依赖说明

- Dubbo 3.3.6 会传递引入 fastjson2（alibaba/fastjson2，约 4K star），用于其内部元数据 / 配置序列化。本仓库代码不直接使用它，
  JSON 一律用 Jackson；Dubbo 的业务序列化走 protobuf（接口参数与返回值都是 protobuf 消息）。

## 版本约束

- protobuf-java 必须与 protoc 同版本（4.35.1），升级时两者一起动，并同步检查 mmorpg 的 protoc 版本。
- Netty、Jackson、Micrometer（含 micrometer-registry-prometheus 及其 Prometheus client）、Actuator、Tomcat、mysql-connector-j、
  spring-kafka 由 Spring Boot BOM 统一管理，不单独指定版本。
- Dubbo 由 dubbo-bom 管理。Dubbo 发布支持 Spring Boot 4 之前，Spring Boot 停在 3.5.x。
