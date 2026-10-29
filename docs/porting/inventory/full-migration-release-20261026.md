# 发布、K8s、水平扩展与告警能力复核（2026-10-26）

## 1. 范围与证据口径

本轮固定原版基线为 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`。所有原版证据来自 `git show` / `git ls-tree`，未读取其他会话的工作区修改作为定案，未修改 C++ 仓库的代码、配置或文档。下文 `原版:` 路径均相对此提交，行号也是此提交的行号。

Java 证据来自 `D:\luyuan\wt-port-next`，从 `03a0708d` 接续的当前工作区，包含本轮尚待统一构建的改动。下文 `Java:` 路径相对 Java 仓库，行号是本次读取时的定位辅助，以类 / 方法名为稳定锚点。本文只盘点和提出接缝，不新增 Java 代码、依赖或部署实现。

结论：原版已有可执行的 K8s 编排、容量门禁、镜像离线制品、发布预检、排空与回档脚本，以及 PrometheusRule 和部分 dashboard。Java 已有业务层多副本围栏和排空能力，本轮补齐了开发 / CI 整栈；尚无可交付的生产 K8s 清单、发布制品流水线、运维编排 CLI 与告警安装链。不能将「源码存在」「静态检查通过」「生产故障演练通过」合并成一个状态。

本文复核期间没有运行 Maven、Java 编译、Docker 容器、K8s、Helm 或生产变更。协调者已报告的 Compose 配置解析、18 个 Boot 模块守卫、缺配置反例和 shell 语法检查见 `docs/porting/java-stack-20261026.md`；这些不是实际整栈通过的证据。

## 2. 原版实际能力与 Java 差额

| 能力 | 固定原版的实现证据 | Java 当前实际接线 | 真剩余与边界 |
|---|---|---|---|
| 全服务制品和部署目录 | `deploy/k8s/manifests/` 有 infra、Go 微服务、Java gateway；`tools/scripts/k8s_deploy.ps1` 生成分区节点清单 | `deploy/docker/Dockerfile:14–64` 为全部 Boot 模块生成非 root、JRE digest 固定、含 OCI / BUILD_INFO 的镜像；`deploy/compose/stack.yaml` 为 17 服务及 robot 提供装配 | 缺生产 K8s 部署清单、发布时的真实对象核验、镜像仓库 digest 核对。不能把开发 Compose 当作生产 HA |
| 冷库、迁移和初始化顺序 | `deploy/k8s/manifests/go-svc/` 有 data-service、friend、guild、mail、player-inbox、rank、trade 等迁移 Job 及 route-tool Job；部署脚本有迁移门禁测试 | `deploy/compose/tidb/init.sh` 执行已有玩家、路由、商城结构；`stack.yaml:36–48` 的 `route-init` 调 `InitializeRouteAuthority`，由显式 `GAME_ROUTE_GENERATION`、区号和真实 SQL 玩家建立原件，服务依赖完成 | 缺生产一次性迁移 Job 和迁移前后版本门禁；不能把初始化空库流程当成已有库升级 / 回滚流程。DDL、route generation、部署 revision 应成为三个独立原件 |
| 运行模式、密钥和资源预算 | `release_preflight.ps1:257–450` 核验多服务 gate 密钥一致性、密码强度、不可变镜像、broker 下界、分区一致性、租约和限流参数、关闭开发开关；`k8s_deploy.ps1:1922–2916` 有 SQL / Redis 连接峰值账本 | Spring 显式配置、命名空间隔离、生产 TiDB 强读、自有链路 / 资产 / 商城分域密钥；`deploy/ci/check-stack.py` 检查 Boot 模块、镜像原件、DSN、命名空间和开发栈密钥 | 缺生产 Secret 引用矩阵、轮换演练、按最大副本数 + surge 的 SQL / Redis / Kafka 连接预算。现有 stack 固定 `GAME_RUN_MODE=dev`，其通过不证明 prod 门禁完整 |
| 不可变发布和离线交付 | `make_release.ps1:279–332` 写源码提交、dirty、镜像清单 / digest、表摘要及说明，以最后原子创建 JSON 作为不可变发布哨兵；`release_preflight.ps1:468` 起校验 CHANGELOG、sha256sums、build-info 和制品一致性 | 镜像已有 commit / contract / version / created 标签，CI 生成本轮构建原件；普通 CI 与 contract-drift 工作流已存在 | 缺 Java release manifest、制品目录不可覆盖、离线镜像包校验、源码 / 契约 / 表 / 导航摘要关联、生产 preflight 和可重放发布记录。sha12 标签本身不等于 registry 中的不可变 digest |
| 发布流水线 | `.github/workflows/release.yml:20,377–403` 为手动触发的镜像离线包 + manifest artifact 流水线，默认不含缺 Linux staging 的 C++ 族；头注释明确不安装 syft / cosign / trivy | `.github/workflows/integration.yml` 新增 stack job：构建 jar / 镜像、启动 TiDB 整栈并跑客户端；暂无 release 工作流 | 不应宣称原版有自动线上发布、签名或 SBOM 完整链。Java 先移植现有制品与门禁能力，再另行决定增强；本次不推镜像、打 tag 或部署 |
| 场景频道的逻辑扩缩容 | `go/scene_manager/internal/logic/world_autoscale.go` 和相关测试；与进程编排是独立层 | `WorldAutoscaler.java:17,92–125` 已按人数规划频道扩缩；`WorldChannelControlPlane.java:45–59` 生产启动控制面，`WorldChannelCoordinator.java:140–287` 分区选主、租约、版本写入、缺席正面证据；本轮加疏散与 hash 余量规则 | 已有的是频道容量控制。它不创建 / 删除 Pod。不能再将频道扩缩容全部标「未做」，也不能将其等同完整进程水平扩展 |
| 进程房间容量和自动扩缩 | `go/scene_manager/internal/agones/k8s_allocator.go:99–193,273–291` 通过 GameServerAllocation 原子选取 / 增计数，房间释放用 resourceVersion 围栏；`k8s_deploy.ps1:7166,7338–7398` 生成 Fleet / Counter FleetAutoscaler，容量 = 副本数 × 经压测给出的每进程房间上限 | 未找到 Java K8s / Agones 客户端、Fleet、HPA 或相应部署产物；现有 scene-manager 的 SQL / Redis 权威关系不依赖 Kubernetes | 需补等价的进程容量观察、目标副本数规划、容量预留 / 回收与实例身份核验。Java 不必采用 Agones；禁止把 C++ Go SDK 和 Counter 当成第二份玩家归属权威 |
| 入口与单实例可达性 | 原版 `tools/scripts/lib/k8s_client_entry.ps1` 与 `k8s_deploy.ps1` 分 external / podip / agones，脚本对客户端入口形态有契约测试 | gate / battle 通告地址已与监听地址分开；Compose 默认通告容器 DNS，宿主需显式覆盖；Netty 节点身份绑定 instance / lease epoch | 缺 K8s 每实例可达地址和稳定外部路由。不能把多个 gate 或 battle 放在一个随机负载均衡地址后，却继续向客户端签发指定节点票据 |
| gate 排空及替换 | `k8s_gate_drain.ps1:1172–1264`：读 Pod UID / IP 与目录唯一身份、验证其他可用节点、带原件标记、等待 drained、复核身份后可选删除 Pod、清自己标记、等待替代 Pod；无 `-DeletePod` 时只排空 | `GateDrainAdminController.java:113–153` 已接管理面标记，gateway 监测排空；GateNode 有 stop / 等待会话退出 | 缺把管理 API、目标 Pod UID、节点 instance / epoch、时间预算、替换和失败恢复串起来的 Java 运维工具。原版该脚本也明确不能直接用于 StatefulSet 缩副本，不应扩大其保证 |
| scene 疏散与租约恢复 | 原版 scene / scene-manager 生命周期和部署 grace 配置已存在；容量原件与节点身份受围栏 | 本轮 `SceneNode.java:634–650,703–712,1086–1122` 实现身份挂起、同步频道计划后恢复、疏散与停机预算；readiness 要求有效身份、已同步且有活动世界频道；`SceneEvacuationAdminController` 和目录 `evacuating / lease_epoch` 生产接线已落盘 | 缺 K8s preStop / SIGTERM / readiness / PDB / rollout budget 实际组合验收。现有单元测试不证明 Kubernetes 删除和长 GC 时不会双持有 |
| 多 broker 与扩容门禁 | `k8s_deploy.ps1:4536–4645`：只允许 1 或 ≥3 broker，生产档默认 3，RF3/minISR2，PDB；拒绝 1↔多 broker 和原地缩容，扩 broker 不假装已迁移分区；`:4730–5157` 有磁盘 / 保留预算 | 本轮 `AuditProperties.validateFor`、`AuditTopicDurability` 和 scene/data 工厂已接 prod RF≥3/minISR≥2，逐分区真实最小 RF，保留更强现有 minISR；`DataNode:225–226` 关闭自动提交，`ConsumerLoop:187` 落库后 commit | 缺生产 broker / topic 部署、容量和 retention 闸、扩容副本迁移验收。`game-data` 是审计消费者，不能照抄原版 Go `db_task` 保存分区数量给它设业务架构 |
| Redis / SQL HA 拓扑 | 原版有 etcd 3 副本、Kafka 多 broker 渲染、Redis HA / 按用途拓扑和危险切换拒绝；`redis-ha.yaml` 有 Sentinel、PDB、min-replicas-to-write，但模板不是零丢失证明；原版 README 仍含单副本基础模板 | `StrongTiDbConnectionPolicy.java:16–18,63–78` 生产会话强读 / 悲观 RC；本轮 mail/rank 补 prod 拒关闭；Java 节点租约、SQL owner epoch、计划版本与永久退休标记有各自围栏 | 新 TiDB Compose 是单 PD / TiKV / TiDB 开发夹具，Redis/Kafka 也是开发形态。缺生产多故障域配置、Redis 故障恢复后的 epoch / retired 水位不倒退证明、TiDB 多数派与网络隔离演练 |
| 发布回滚与整区数据回档 | `k8s_zone_rollback.ps1:2603–2630,2777–3004` 核 live 配置和工具 commit、停写与 Kafka log-end 静止、PITR 声明、inventory/purge/verify、显式丢弃积压；还有镜像 revision 回退路径 | Java 有 `RollbackAdminController` / `RollbackService` / `RollbackWriter` 的玩家回档与资产分歧检查、SQL 归属围栏、`RetireZoneHotState` 的永久退役屏障 | 不等于整区 PITR 或部署 rollback。缺 Java 部署 revision 回滚、schema / contract 兼容矩阵，以及适合 SQL 权威架构的整区恢复编排。不得搬用 Go db_task offset-to-latest 或粗删 Redis |
| 指标、告警、日志 | `deploy/k8s/*-alerts.yaml` 有 scene、审计、route、owner epoch、持久 schema、SQL / Redis、mail/rank/inbox 等规则；`scene-manager-dashboard.json`；可选 Loki / C++ 日志 sidecar | Java 各模块 Micrometer + Actuator；`WorldChannelMetrics`、`DataMetrics` 等有低基数指标，默认管理地址本机，stack 显式绑 0.0.0.0 | 尚无 Java PrometheusRule / scrape / dashboard / 告警测试 / 日志部署产物；不能将 Go 指标规则简单改前缀后使用。原版规则也需外部 Prometheus / Monitor，部分 scene 告警仍只有人工 Loki 查询 |

## 3. 必须单列的观测差额

原版 `deploy/k8s/data-service-audit-alerts.yaml:8–23,90–153` 明确依赖 broker 视角的组级 `lag_max_partition`、`retention_gap`、周期探测和探测失败信号：同组多实例取 max，保留期缺口用窗口最大值且不等持续两分钟；探测关闭或指标不存在时规则可能完全无声。它不是「有 consumer lag 就足够」的模板。

Java `game-data/src/main/java/com/game/data/consume/ConsumerLoop.java:284–319` 当前在运行时读取 assigned 分区的 `currentLag`，暂停期间从 endOffsets / committed 计算本实例总积压，异常时保留陈值并写 debug。`DataMetrics.java:23,69–74` 只有总 lag 与 consumer-up 等；`DataNode.java:226` 配置 `auto.offset.reset=earliest`。尚无 beginning offset 对照已提交位点的缺口证据、组级最大分区积压和探测新鲜度指标。因此：

1. 后续须增加独立、有界、可取消的 broker 组级审计探测；不可在消费线程里加入无界管理请求，也不可把没有观测到的值写成健康零。
2. 原始证据应包含 topic generation、分区、committed / beginning / end offset；分区原件用于日志和诊断，不把 player_id 等作为 label。告警需区分无提交历史、合法新组与已确认数据缺口。
3. 是否在检测到已丢失审计证据后停止消费者 / 暂停相关运维操作，应由 Java 的审计完整性契约明确，不能只加一个红色 dashboard 后继续宣称可可靠回档。
4. 规则安装和 scrape 对齐之前必须有 `absent` / stale 的覆盖，不能仅测试指标上升；监控本身失联也须可见。

## 4. Java 发布与水平扩展的接缝

### 4.1 声明式资源与命令式操作分开

沿用旧 `ops-release-spec.md` 中「Java 业务与运维工具、声明式部署文件各负其责」的方向，不复制 PowerShell 大型生成器，不引入 Go SDK。建议后续写入范围：`deploy/helm/`（或经复核的 K8s 模板目录）、Java `game-ops` 模块、`.github/workflows/release.yml`、`deploy/observability/` 和各模块必要观测接口。它们都尚未由本文创建；模块 / 库选型仍须按仓库依赖规则核验。

声明式部分负责镜像 digest、端口、资源、Secret refs、DNS、拓扑分散、PDB、安全上下文、探针和 rollout budget。Java CLI 通过显式参数和注入依赖调用 kubectl / helm 子进程及现有 game-data 管理 API；收集结构化原件，不在字符串里拼接含秘密的 shell 命令。预检默认只读，apply 使用与预检同一份带摘要计划，失败不得静默继续。

不能直接沿用旧文档的「12 进程、无整栈、所有健康都是通用探针、5.5 尚未实现」前提。现在是 17 服务 + robot；scene 领域 readiness 已落盘，其他进程的 readiness 仍应逐项核定。`ops-release-spec.md:106–114` 的前置状态与旧 `remaining-ops-java-20261026.md` 的 7.1b 盘点行需在正式实施 7.6 前以本轮证据更新。

### 4.2 扩容是容量执行，SQL 玩家归属始终独立

- 业务层继续由现有 scene-manager 控制面维护频道计划、逻辑容量和租约；K8s 只能提供可用进程容量，不能以 Pod Ready、目录缺席或 stuck grace 直接接管玩家 SQL owner。
- 新增一个窄的进程容量适配接口：读取带 revision 的部署形态、计划期望副本数、等待新节点以实际 instance / lease epoch 登记；不在场景逻辑线程做集群 I/O。总房间、频道、玩家、CPU 与 Pod 数的单位分别定义，禁止把频道数当副本数。
- 副本下限 / 上限、每进程容量、surge、扩缩冷却和缩容最大并发均显式配置；每进程容量需压测证据。不能拿默认 5 个队员或某张地图频道数推算全服节点容量。
- 缩容必须先选择明确实例，阻止新分配并执行已有疏散 / 排空，再验证该原件的终态，最后按 Kubernetes UID / resourceVersion 条件删除或缩目标。目标已换代就重新规划；不得将旧原件的 drained 用于新进程。
- gate / battle 的持票重连端口要保持原实例可达，直到协议允许结束；scene readiness 退出与客户端重连入口不是同一个开关。K8s `publishNotReadyAddresses`、PDB 与终止宽限期的组合必须真实验证，不能只照搬旧规格表。
- data / match / rank / mail / guild 等服务是否能横向增加实例，以生产调度锁、SQL CAS、消费组和幂等写点为证据；为每个服务列出最大副本数与连接账本，不能因 Spring bean 无状态便统一设 HPA。

### 4.3 发布原件与兼容门禁

发布记录至少关联：Java 提交、受管契约提交 / 地图独立提交、表与导航摘要、模块及镜像 digest 清单、数据库 schema 版本、route generation、topic generation / 分区 / RF / minISR、节点目录协议、Secret 引用版本、运行模式与资源计划摘要。秘密值不进制品、日志或报告。

本轮目录 v2 是现实发布约束：`NodeDirectory.java:31–33` 的非零 zone 写入 v2，旧读者看不到新条目；现实现默认切写，不能宣称支持任意混合版本滚动。当前可接受的落地方式是维护窗口全量升级，或先另行实现并验收明确的 reader-first 过渡版本。v2 的 epoch / instance 围栏、条件删除、永久 retired / 版本水位必须贯穿 release / rollback；旧二进制不支持屏障时禁止放回已退役区。

`RetireZoneHotState` 的 deployment manifest 中 old-writers-stopped 是运维声明，工具无法仅凭该声明证明集群内不存在未知旧写者。发布工具必须记录所有实际 workload / image / directory protocol，并保存旧进程终止证据。永久退休不自动 reopen；禁止靠删 retired 标记实现回滚。部署镜像回退、玩家回档、整区 PITR、合服回滚、永久退役是不同操作，不能共享一个笼统的 rollback 按钮。

### 4.4 生产拓扑与初始化

开发 `stack.yaml` 保留 dev、本地全球匹配开关与单中间件是明确边界。生产模板要显式要求 TiDB 强读、可承受单故障域失效的元数据 / 存储副本、Kafka RF / minISR 及持久卷，拒绝把 dev 旁路带入 prod。Redis HA 的副本存在不证明高水位不倒退；节点 epoch、频道版本、永久退役等必须列入恢复证明。

首次安装、可重复迁移、已存在库升级分别编排；显式 route generation 在重启时复用，绝不每次 release 自动生成。各域的 schema owner 与迁移锁清晰定义，生产凭据最小化。Secret 轮换应核双方接受窗口、票据最大寿命、旧版本退出时点；不能同时改所有密钥而把合法重连全拒绝。

## 5. 可独立实施的顺序与验收

| 优先级 / 包 | 具体交付 | 前置与验收门槛 |
|---|---|---|
| P0 / R1 原件与 preflight | Java 发布 manifest / 校验器、不可变离线包、配置及 live 只读门禁 | 错 commit / contract / 表摘要、脏发布、重复版本、可变镜像、缺密钥、namespace / DSN 错配、prod dev旁路、RF不足、迁移版本缺失均在 apply 前失败；重试不覆盖旧制品；命令不泄密 |
| P0 / R2 声明式生产装配 | 全部 17 服务和必要一次性 Job 的 K8s 资源、入口、探针、Secret / NetworkPolicy、PDB / grace 与资源账本 | 静态模块完整性 + 服务端 dry-run；冷库真部署；合法重启复用 route generation；错世代拒启；管理面不经公网；CPU / 内存 / SQL / Redis 峰值计入最大副本与 rollout surge |
| P0 / R3 排空与滚动发布 | Java 工具协调 game-data 排空 / 疏散、原实例等待、条件替换、失败报告 | 两副本中一台滚动时新登录 / 重连 / 同区切线 / 战斗回归可用；旧 epoch 迟到被拒；租约丢失与长 GC 不双写；最后节点 / 后备全疏散拒绝；超时保留失败证据，不强接管 SQL |
| P0 / R4 审计完整性与告警 | 组级 lag / retention gap / 探测失效信号、PrometheusRule / scrape / rule tests | SQL提交前崩溃重放、提交后位点未提交重放不重复入账；broker 少副本拒启；minISR失守不误称持久；保留期裁掉未消费数据可定位且告警；指标缺失 / 陈值 / 换主 / 恢复均覆盖 |
| P1 / R5 进程容量扩缩 | 显式进程容量适配、部署 revision 围栏、实例级缩容与指标 | 同一规划仅一个有效执行者；重复扩容幂等；扩容新节点就绪前不分配；缩容途中重启 / 失联 / Pod替换不能删新实例；频道逻辑扩缩与 Pod执行一致但不共享玩家权威 |
| P1 / R6 发布与恢复演练 | 生产模式多副本 CI、旧新版本兼容矩阵、部署回滚 / 备份恢复 runbook、完整证据归档 | 杀一个 broker / TiDB节点 / Redis主及网络隔离；明确 RPO/RTO 和允许拒绝窗口；恢复后 epoch / retired 不倒退，SQL owner 唯一、资产原件可追溯、终局不重复；没有此证据不得标 HA 验收完成 |
| P1 / R7 其余观测与容量 | route / owner epoch / 节点恢复 / scene延迟 / 分配池 / 任务失败 / 数据库连接 / Redis / rank-mail等告警、dashboard、日志采集 | 按 Java 实际指标名和标签编写 promtool 输入；健康、触发、恢复、无数据、换 leader、滚动 surge 均验；禁止 player_id label；日志保留、脱敏、告警接收通道均有真实触发证据 |

R1 / R2 可在业务接口冻结后独立实施；R3 依赖本轮 5.5 生命周期和目录 v2 的构建 / 真 Redis 结果；R4 需先补 §3 指标，不能先交「绿色」规则；R5 依赖 R3 及容量压测；R6 只有在生产模式下才算有效。各包均不要求照搬 Agones 或新引 Go 架构。

## 6. 已存在的测试接缝与本轮未运行项

可复用 Java 测试入口包括：

- `game-audit/.../AuditPropertiesTest`、`AuditDurabilityTest`：prod / dev、真实最小 RF、OWN / VERIFY_ONLY、保持较强 minISR；scene / data 工厂测试保证不是只有工具类校验。
- `game-discovery/.../NodeIdLeaseRecoveryTest`、`NodeDirectoryContractTest`、`ZoneDirectoryRedisIntegrationTest`：租约挂起 / 恢复、旧 epoch、条件删除、v2退休屏障。
- `game-scene-manager/.../WorldEvacuationPlannerTest`、`WorldChannelConfigurationTest`、`WorldChannelRedisIntegrationTest`：疏散补建、缺席与正面租约证据、长短宽限、hash 缩容余量、生产控制面装配。
- `game-scene/.../SceneEvacuationLifecycleTest`、`world/NodeEvacuationLifecycleTest`、`storage/FinalSaveRecoveryTest`：停机疏散和 final-save 恢复；仍需实际 Pod 终止组合验证。
- `game-data/.../consume/ConsumerLoopTest`、`merge/retire/RetiredZoneRedisIntegrationTest`、`tools/RetireZoneHotStateTest`：消费重放、永久退休与 CLI 配置接线；不代替 Kafka 保留期缺口或生产 Redis HA 演练。
- `deploy/ci/stack-smoke.sh:29–47`：六个真实客户端场景 `smoke / movement / reconnect / bag / currency / attribute`，再核 17 服务健康。它未运行本轮新增的全部团队、帮会、频道、全球匹配或 HA 场景；不能从六场景通过外推完整迁移通过。

本次没有执行上述测试。尚待协调者统一构建、CI 整栈、真实中间件和 K8s 演练分别记录命令、版本、退出状态与报告路径。本文不修改 PARITY 完成状态，也不宣称 7.6 已完成。

## 2026-10-27 接续实现记录

本库存保留原盘点口径。审计 broker 组级探针已按候选哈希接入独立 Java 工作树：实际消费组/代次、保留缺口下界、Unknown/过期/停止、内存只读管理口及 39 个测试源码，见 `../audit-group-probe-20261027.md`；尚未编译/运行此批测试或真实 Kafka 验收。没有因此改变消费者提交、补上持久缺口原件或安装告警。

R1a 离线发布原件工具已按候选清单接入 Java 工作树，见 `../release-offline-20261027.md`：17 服务 JAR、固定源码/契约与计划配置原件、不可覆盖版本目录、manifest 外部摘要校验；36 个普通测试和 5 个参数化方法尚未运行。它取代尚未执行的 v4 构建冻结，需纳入 v5；源码/制品文件校验不等于镜像 registry、live SQL 或部署就绪证明，preflight 始终 UNKNOWN。R1b 镜像原件和 R2–R7 的生产装配、排空编排、进程容量执行、观测和 HA/RPO0 实验继续按上文逐项交付。
