# K8s 开区编排、发布制品与门禁、日志 / 告警（批次 7.6）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`（`git log -1` 核对，与 `contract/SOURCE.properties` 的 `mmorpg.commit` 相同），本地是 `D:\work\mmorpg` 的稀疏克隆。
> 本稿用到的 `deploy/**`、`tools/scripts/**`（含 `lib/`、`tests/`）、`.github/workflows/**`、`docs/ops/**`、`docs/design/**`、`java/gateway_node/**`、`go/*/etc/**`、
> `cpp/libs/engine/**` 都已检出。缺的目录与处理：
> - **`bin/` 没有检出**。`k8s_deploy.ps1` 与 `release_preflight.ps1` 都读 `bin/etc/base_deploy_config.yaml`，用 `git show HEAD:bin/etc/base_deploy_config.yaml` 读到：
>   `:5-6`（KeepaliveInterval 1 s、NodeTTLSeconds 180）、`:25`（`LogLevel: 2` 即 WARN）、`:92-93`（命令 topic 256 分区、第 2 代）、`:110`（`GateTokenSecret` 是仓库里的开发值）、
>   `:126`（GateMaxConnections 20000）、`:136`（BattleMaxConnections 4096）。
> - **`third_party/` 没有检出**。「muduo 日志滚动不删旧文件」只引用了脚本注释（`mm:tools/scripts/k8s_deploy.ps1:267-268`），没有核对源码；不影响任何 Java 结论。
> - Unity 客户端是另一个稀疏克隆 `D:\work\mmorpg-client`，本批用不到：7.6 不改客户端契约，只改 gate / battle 通告地址的**取值**。assign-gate 应答形状见
>   `docs/reference/mmorpg-client-contract-robot.md:77`（`gate_ip / gate_port / token_*`）。
> - **没有哪条结论因为目录缺失而定不下来。**
>
> **Java 侧**：以 2026-10-05 的工作区为准，HEAD `9fde7d8`。已提交：5.2（`6b28e9d`）、7.1a（`37dd8dc`：三个 workflow、`deploy/compose/infra.yaml`、`deploy/docker/Dockerfile`、根 pom 的 build-info）、
> 7.2a。工作区里未提交的并行改动：6.2（新模块 `xm-battle`，根 pom 已登记）、5.3 的 scene-manager 部分（`InstanceIdIssuer`、`SceneIdAllocator`）、gate 限频重构（`xm-net/.../limit`）。
> **7.1b 还没落地**：12 个 `application.yaml` 的 actuator 暴露面仍是 `health,prometheus`；没有探针分组；通告地址等仍写死（如 `xm-gate application.yaml:44`、`xm-scene application.yaml:73`、`:81`）。
> 这些文件提交后行号会漂移，引用时同时写类名或配置键。`docs/design/architecture.md` 被并行批次持续修改，对它一律写「§节」，必要时加「编写时行号」。
>
> **路径约定**：`mm:` 开头的路径在 mmorpg 里，其余在 Java 仓库里。`xm-<模块> application.yaml:N` 指该模块 `src/main/resources/application.yaml` 的第 N 行。
>
> **盘点 id**：deploy-k8s-zone-orchestration（`docs/porting/inventory/java-infra.md:333-343`）、release-packaging（`:345-355`）、observability-logs-alerts（`:357-367`）、
> release-preflight（`docs/porting/inventory/tools.md:446-456`）。关联：k8s-zone-deploy（`tools.md:410-420`）、gate-drain-ops（`:422-432`）、zone-rollback-kafka-reset（`:434-444`）、
> agones-*（`java-infra.md:285-307`）。路线图 `docs/porting/roadmap.md` 7.6 行。
>
> **本稿的来历**：由三份分区稿（K8s 编排盘点与设计；发布制品、门禁、日志、告警的盘点与设计；Java 映射稿）与上一版合并稿（本文件的前一版，未入库）合并而成。
> 分稿之间、分稿与上一版之间矛盾的地方，都回到代码重新核对过，结论列在 §0.6。全程只读，除本文件外没有改任何文件。
>
> **实测**（2026-10-05，全部只读）：
> 1. star 数由分稿用 GitHub API 匿名实测：helm 30.3K、kustomize 12.2K、k3s 34.1K、minikube 32.2K、kind 15.5K、argo-cd 24.3K、helmfile 5.2K、agones 7.1K、
>    prometheus 66.4K、prometheus-operator 10.0K、alertmanager 8.6K、VictoriaMetrics 17.8K、grafana 77.1K、loki 29.0K、elasticsearch 78.2K、ClickHouse 50.3K、SigNoz 32.3K、
>    openobserve 22.3K、OpenSearch 13.8K、vector 22.7K、logstash 15.0K、fluentd 13.6K、elastic/beats 12.7K、fluent-bit 8.1K、otel-collector 7.6K、grafana/alloy 3.6K、
>    traefik 65.1K、ingress-nginx 19.5K、external-secrets 6.9K、kubeconform 3.2K、trivy 38.2K、cosign 6.3K、syft 9.6K、goreleaser 16.1K、jreleaser 1.2K、
>    flatten-maven-plugin 223、logstash-logback-encoder 2.5K、kubernetes-client/java 4.0K、fabric8 kubernetes-client 3.7K。编辑复核时匿名额度已用尽，没有重测。
> 2. Spring Boot 3.5.16（`pom.xml:49`）：`spring-boot-3.5.16.jar` 的 `META-INF/spring-configuration-metadata.json` 里有 `logging.structured.format.console`、
>    `logging.structured.json.{add,include,exclude,rename,customizer}`、`logging.structured.json.stacktrace.{max-length,max-throwable-depth,root,printer,include-hashes,include-common-frames}`（编辑用 unzip 复核）；
>    分稿 javap 证实 `DefaultLogbackConfiguration` 读 `${CONSOLE_LOG_STRUCTURED_FORMAT:-}` 后用 `hasLength` 判断，**空值就是文本格式**。仓库里没有任何 `logback*.xml`。
> 3. `spring-boot-loader-tools-3.5.16` 里有 `BuildPropertiesWriter$NullAdditionalPropertyValueException`（编辑复核）：值为 null 的 additionalProperty 会让构建失败（§0.6 #9）。
> 4. 基线计数：`mm:deploy/k8s/scene-manager-alerts.yaml` 19 条 `- alert:`（`:55 :79 :103 :116 :137 :155 :175 :249 :281 :306 :321 :344 :364 :402 :436 :455 :471 :489 :511`），
>    `owner-epoch-alerts.yaml` 2 条（`:40`、`:60`）；`k8s_deploy.ps1` 5583 行、`lib/k8s_client_entry.ps1` 2086 行、`k8s_gate_drain.ps1` 1237 行、`k8s_zone_rollback.ps1` 862 行、
>    `release_preflight.ps1` 656 行、`lib/release_common.ps1` 706 行、`.github/workflows/release.yml` 442 行、`mysql.yaml` 182 行。
> 5. Java 代码里的 `"xm.…"` 字面量约 180 个（含前缀片段）。§4.6 中标为 7.6 的每条告警，引用的指标名与标签值都对照过定义类（§11.1）。

---

## 0 概览与范围

### 0.1 结论速览

| # | 问题 | 结论 |
|---|---|---|
| 1 | Java 版上不上 K8s（盘点开放问题 7，`java-infra.md:389`；7.1 Q10 留给本批） | **上 K8s，不上 Agones**。正确性靠 CI 里一次性 k3s 集群上的真部署 + robot 证明；compose 整栈（7.1）继续作为单机演示形态 |
| 2 | 清单怎么生成 | **Helm**（30.3K，同类最高）：一个 library chart + 三个 chart，只管声明式部分。命令式步骤（区服目录、排空、等待、门禁）放 Java 运维 CLI 模块 **`xm-ops`**，它只调 `helm` / `kubectl` 和 xm-data 的运维接口。5583 行的 PowerShell 生成器不移植 |
| 3 | 哪些东西按 zone 部署 | **只有 xm-gate、xm-scene**。login、gateway、scene-manager、社交五服、data、battle、match 都全局一份（`zone-travel-spec.md:821-834`）。一个 zone = 一个 Helm release `xm-zone-<id>`。多 zone 以 5.4 的 X16 为前置 |
| 4 | namespace | 一个环境一个应用 namespace（缺省 `xuanming-java`），放全局服务和全部 zone；依赖放 `<ns>-infra`，只给 dev / CI / 演示用，生产外接托管的 MySQL / Redis / Kafka |
| 5 | 服务发现 | **K8s Service DNS + Dubbo 直连 URL**（`tri://xm-login:20881`，与 compose 同名）；K8s 上不部署 Nacos。按节点的调用（gate→scene 链路、资产通道、match→battle 控制面）照旧从 Redis 节点目录取 Pod IP |
| 6 | gate / battle 对外入口 | StatefulSet + 每序号一个 Service（`publishNotReadyAddresses: true`）+ PDB `maxUnavailable: 0`，与基线 external 同形。通告地址由 Java 进程按 Pod 序号自己算（四种暴露模式，§5.5）。**同一 zone 的每个 gate 必须有独立的客户端地址** |
| 7 | scene | 单池 Deployment，不建 Service。滚动 `maxSurge: 100% / maxUnavailable: 0`，每个玩家至多被疏散一次（基线每次 scene 发版都断开玩家，B19）。PDB `maxUnavailable: 1`。宽限期由模板按 5.5 的 C2 公式求下限，不低于 60 s |
| 8 | 探针 | 全部打 actuator 分组端点，管理端口绑 0.0.0.0。**gate / battle 的 readiness 不叠加「排空中 / 准入关闭」**（否则每序号 Service 会把持票重连挡在门外，§1.6.3），只表示「已开始服务、未开始停机」；scene readiness 叠加「身份有效、不在疏散、有 ACTIVE 频道」。liveness 叠加「节点号租约永久丢失」与 scene / battle「逻辑线程卡死 30 s」 |
| 9 | Pod 模板硬约束 | 每个 Pod 写 **`enableServiceLinks: false`**（K8s 注入的 `<SVC>_PORT=tcp://…` 落在我们自己的 `XM_` 前缀里，§0.6 #22）、`automountServiceAccountToken: false`、只读根文件系统、非 root、memory limit 必填 |
| 10 | 秘密 | 只进 Secret，按进程最小授权用 `secretKeyRef` 注入，缺键时 Pod 卡在 `CreateContainerConfigError`；值不进 Helm values、不进 ConfigMap，由 `xm-ops deploy secrets` 从不入库的 env 文件经 stdin 建 |
| 11 | 配置 | 镜像里的 `application.yaml` 是唯一配置源；K8s 只用环境变量覆盖占位符（7.1 §6.4 的规则），不挂配置文件遮蔽镜像（基线 node-config 的做法） |
| 12 | 版本号与制品 | `vX.Y.Z[-pre]`，规则同基线。Maven `project.version` 不动，发布号经 build-info 的 `build.release`（**缺省 `unreleased`，不能是空串**）、OCI `version` label 与 `/app/BUILD_INFO` 注入；镜像 tag `vX.Y.Z-<sha12>`。用户手动触发的 `release.yml` 产出 manifest、发布说明、sha256sums、images.tar；**用户**另行触发 `release-push.yml` 推 `ghcr.io/luyuan-java/<模块>`，按 digest 部署。CI 和 AI 都不打 git tag |
| 13 | 门禁 | 两层。① 进程启动自检 `ProductionGate`（在任何端口打开之前）：prod 运行模式下秘密缺失 / 过短 / 占位串 / 跨信任域复用、开发口令登录开着、GM 远程开着、DEBUG 日志、危险 actuator 端点、MySQL 用 root，任一项拒启。② 部署前 `xm-ops deploy preflight`：tag 不可变、Secret 齐全、values 自洽、NodePort 段、制品与 digest 一致。退出码 0 / 1 / 2 都真能走到 |
| 14 | 日志 | Spring Boot 自带的结构化日志（`logstash` 格式，字段同基线 gateway）；容器里缺省 JSON，本机仍是文本。采集用 **Vector**（22.7K，同类最高）DaemonSet 读 stdout。存储用 **Loki**（对 §2 的偏离，**需用户确认**，Q40）：缺省保留 7 天，`xm.audit.*` 审计流 30 天。打开 JSON 日志必须同批修好审计兜底回灌（J10） |
| 15 | 告警 | 纯 Prometheus 规则文件 + `promtool test rules`；不做日志告警；zone 维度来自抓取目标标签，`job` 必须等于进程自带的 `application` 标签（有守卫与告警）。基线 21 条：11 条改写或合并、10 条不适用。Java 告警 88 条，A1–A64 随本批落地，其余随指标所属批次（§4.6） |
| 16 | 看板 | 6 个文件化看板（6.x 落地后加 1 个），数据源用模板变量，`allowUiUpdates: false`；静态守卫保证规则和看板引用的每个指标名都真实存在 |
| 17 | 验证 | 本机：单测与本机切片（§11.2）。CI：新 workflow `k8s.yml`（k3s 真部署 + robot + 运维演练 + 反例）、`ci.yml` 加 `obs-static`、`integration.yml` 整栈加观测冒烟、`release.yml` / `release-push.yml` 由用户试跑（§11.3） |
| 18 | 分批 | **7.6a**（不碰集群，7.1b 之后）：门禁、日志与回灌修复、指标与规则、看板、`xm-ops` 的 release / obs / preflight 纯逻辑、发布 workflow。**7.6b**（要集群；6.2、5.5 之后，双 zone 另等 5.4 的 X16）：Helm、通告地址序号化、battle 运维排空、`xm-ops deploy`、`k8s.yml` |

### 0.2 7.6 交付什么

1. **K8s 编排**：`deploy/helm/**`（xm-lib / xm-infra / xm-platform / xm-zone，加 values 与 zones）、NetworkPolicy、PDB、探针、宽限期。
2. **运维 CLI 模块 `xm-ops`**：`release`（check / manifest / verify / digests）、`deploy`（secrets / preflight / images / platform-up / zone-open / zone-close / status / gate-roll /
   gate-scale / battle-roll / battle-scale / rollback）、`obs`（check / export-fallback）。
3. **发布制品**：`CHANGELOG.md`、`.github/workflows/{release,release-push}.yml`、build-info 的 `build.release`。
4. **门禁**：xm-common 的 `ProductionGate` 与 `StartupBanner`；12 个进程（6.4 后 13 个）各一个显式调用点；`xm-ops deploy preflight`。
5. **观测**：12 个进程的结构化日志；`deploy/observability/**`（Prometheus 规则与单测、抓取配置、Loki、Vector、Grafana 看板）；`deploy/compose/observability.yaml`。
6. **Java 代码改动 J1–J15**（§5.14），全部不改客户端契约。
7. **CI**：`k8s.yml`；`ci.yml` 加 `obs-static`；`integration.yml` 的 stack job 加观测冒烟；整栈冒烟步骤抽成 `deploy/ci/stack-smoke.sh`，供 `release.yml` 复用。
8. **文档与对账**：tech-stack、architecture §6 / §11、`docs/ops/{k8s-runbook,release,alerts}.md`、PARITY 新增四行并修订若干行、盘点注记、AGENTS §4 提案（交用户过目）。

### 0.3 与 7.1 的边界

| 项 | 7.1 已定或交付 | 7.6 做什么 | 7.6 对 7.1 决定的调整 |
|---|---|---|---|
| compose | `infra.yaml`（7.1a 已提交）/ `stack.yaml`（7.1b），`XM_RUN_MODE` 缺省 dev | 加 `observability.yaml`（与 stack 同项目叠加）。生产形态走 K8s，不另出生产 compose | stack 的模块漂移守卫（`deploy-ci-spec.md:746`）排除观测服务；pom 侧排除 xm-ops |
| 镜像 | 参数化 Dockerfile、快照 tag `<sha12>`、不推送（`deploy-ci-spec.md:598-606`、Q1） | 发布轨 tag、推 GHCR（用户触发）、只读根文件系统、`ENV XM_LOG_FORMAT=logstash` | 无 |
| 版本 | `build.version` = `project.version`，另带 40 位 commit 与 contract；**刻意不覆盖 `version`**（`deploy-ci-spec.md:838`） | `build.release`（缺省 `unreleased`）、CHANGELOG、manifest | 7.1 §5.4 留下的「`-Drevision` 或 `versions:set`」（`:858`）两条都不用（Q29） |
| 工具 | 7.1 Q8 设想单文件 `tools/Release.java`（`deploy-ci-spec.md:606`、`:1036`） | 改为模块 `xm-ops`（Q28） | 同上，漂移守卫排除 xm-ops |
| 秘密 | 环境变量矩阵（`deploy-ci-spec.md:864-878`）、compose `:?`、CI 随机生成 | prod 启动自检、K8s Secret、轮换、最小权限 MySQL 账号 | 7.1 §6.2「生产账号由 7.6 定」（`:886`）→ `xm_app`（Q21） |
| 健康 | 探针分组、`info` 端点（7.1b） | 领域 readiness / liveness 指示器、`terminationGracePeriodSeconds` | 7.1 写「管理端口改绑 Pod IP」，改为 **0.0.0.0**：`kubectl port-forward` 在 Pod 网络里连的是回环地址，只绑 Pod IP 会让运维通道和 GM 接口都用不了；GM 接口自己按对端地址只收本机（architecture §6），放宽绑定不会把它暴露出去 |
| 网络 | compose 网络内按服务名互访 | NetworkPolicy、gate / battle 对外入口 | 盘点提的 `DUBBO_IP_TO_BIND`（`java-infra.md:342`）不需要：只做直连的进程不设通告地址时 Dubbo 本来就绑 0.0.0.0（`deploy-ci-spec.md:442-447`），入站由 NetworkPolicy 收口 |
| 观测 | 不做（`deploy-ci-spec.md:115`、`:148`） | 全部 | — |
| CI | ci / integration / contract-drift、TestReport | `k8s.yml`、`obs-static`、观测冒烟、发布 workflow | integration.yml 的 stack 步骤改为调用 `deploy/ci/stack-smoke.sh`（与 release.yml 共用） |

### 0.4 与并行批次的关系与前置

| 批次 | 当前状态 | 7.6 的依赖 / 接口 |
|---|---|---|
| 7.1b | 规格，未做 | **7.6a 必须等 7.1b**（stack、各进程地址占位符、探针分组、`info` 端点）提交之后 |
| 5.2 | 已提交 `6b28e9d` | `SceneMetrics.TRANSFERS`、`TRANSFER_POST_FREEZE_MUTATIONS` 已在代码里，对应告警 A63 / A64 随 7.6a 落地 |
| 5.3 | 规格 + 工作区（scene-manager 部分） | 镜像 / 实例告警 A65–A67 随 5.3 |
| 5.4 | 规格 | **X16（`CreatePlayer` 的归属区取会话 zone，`zone-travel-spec.md:1020`）是多 zone K8s 的硬前置**：全局只有一个 login，不做 X16 的话，zone 2 建的角色会记成 login 进程配置的 zone（`xm-login application.yaml:72`）。CI 双 zone 用例等它 |
| 5.5 | 规格 | scene readiness 的身份与疏散判据、宽限期公式 C2（`scene-drain-spec.md:1021`）、§7.3 告警（A70–A76） |
| 6.2 | 工作区（`xm-battle`） | battle 通告地址**已拆好**（`xm-battle application.yaml:61-65` 的 `advertise-port`、`client-advertise-host`，7.1 Q9 落地），7.6b 只补序号化（J2）；准入闸（`AdmissionPhase`）是运维排空（J6）的基础；`xm.battle.lease.lost` / `fingerprint.mismatch` 已注册（A77 / A78） |
| 6.3 / 6.4 | 规格 | 告警随各批落；xm-match 进 xm-platform chart |
| 7.2 | 7.2a 已提交，7.2b / c 规格 | 生产保留期（`data-ops-spec.md:1101` Q11）、运维作业告警（`:951-956`）、兜底回灌工具（J10 要改它）、整区回档（基线 `k8s_zone_rollback.ps1` 的 Java 对应） |
| 7.4 | 规格（`data-tools-spec.md`） | 交给 7.6 的：集群内压测与 robot 镜像（`:1044` Q35）、压测编排（`:1045` Q36）、巡检只读账号（`:1016` Q7）、巡检报告的历史靠日志采集（`:1015` Q6）。处置见 Q57、Q58 |
| 7.5 | 规格（`contract-navmesh-spec.md`） | CQ13（`:891`）：发布说明复用 7.5 的 `ContractGate`（模块 `xm-contract-check`）写契约面差异；落点是 `xm-ops release manifest`，见 Q56 |

**提议写进 AGENTS §4 的规则（Q49，交用户过目）**：从 7.6b 起，新增进程模块的批次同时登记 `stack.yaml`、`start-slice.sh`、`env.example`、`deploy/helm/xm-platform`（或 xm-zone）与 NetworkPolicy；
新增指标的批次同批提交告警规则与 promtool 用例；K8s Pod 一律 `enableServiceLinks: false`；K8s 与观测结论以 CI run 为准。

### 0.5 本机约束与结论来源

本机没有 Docker、Helm、kubectl、Go、python、node、PowerShell 7；有 JDK 21、Maven 和 GitHub Actions。结论按来源分三类：

- **本机可验**：Java 单测、本机切片（`tools/local/start-slice.sh`）、`xm-ops` 的纯逻辑（§11.2）。
- **只能 CI 验**：Helm 渲染与部署、k3s 上的 robot 与运维演练、promtool / Vector / Loki 配置校验、观测栈冒烟（§11.3）。结论按 7.1 §11.5 的办法用匿名 REST 读。
- **只能用户触发**：`release.yml` / `release-push.yml` 是 `workflow_dispatch`，需要令牌；GHCR 包的可见性由用户在网页上设。

基线 PowerShell 脚本的运行结论（如 §2.10 的 G1）全部来自读代码，推断处都单独标出。按 AGENTS §4「没有运行证据不得声称通过」，提交说明要写「K8s / compose / 观测栈未在本机运行，结论以 CI run `<id>` 为准」。

### 0.6 编辑复核：分稿之间的分歧与对上一版的更正

| # | 分歧或原说法 | 核对结果与取舍 |
|---|---|---|
| 1 | 上一版：HEAD `aa8b5b5`、7.1a 未提交；映射稿：HEAD `6b28e9d`、7.1a 在工作区 | HEAD 是 `9fde7d8`；7.1a 已提交（`37dd8dc`）；**7.1b 未落地**（12 个 yaml 的 exposure 仍是 `health,prometheus`，`xm-gate application.yaml:44` 通告地址写死） |
| 2 | 上一版 §1.2 引用 `mysql.yaml:171-195`、`:232-327`、`:268-269`、`:282-283`、`:299-305`、`:324` | 文件只有 182 行。Deployment 在 `:77-182`，readiness 在 `:161-172`，两个 PVC 在 `:17-40`；写死口令在 `:113-114`（root）、`:127-128`（appuser）、`:168-169`（readiness 参数）；initdb 挂载 `:144-153`、卷 `:180-182`（编辑逐行核对） |
| 3 | 上一版与映射稿：battle readiness =「准入 OPEN」，gate =「在接客」，探针领域判据照上一版 §5.6 | **K8s 稿正确**：external 形态下客户端连每序号 Service（`mm:tools/scripts/lib/k8s_client_entry.ps1:864-913`），Pod NotReady 时 endpoints 为空，新连接直接失败；基线没出事只因 C++ readiness 是纯 tcp（`k8s_deploy.ps1:1614-1615` 那句推理只对 podip 成立）。而 battle 票据在房间存活期内可反复重连（`battle-node-spec.md:197`），gate 排空期间也要接持票重连。**改为**：readiness 不叠加排空 / 准入；每序号 Service 设 `publishNotReadyAddresses: true`（§5.6） |
| 4 | 发布稿：发布工具用单文件 `tools/Release.java`（7.1 Q8、`tools.md:452`）；映射稿与上一版：模块 `xm-ops` | **模块**（Q28）：规则要 JUnit 覆盖（基线这部分的 pwsh 契约测试 2521 行，单文件工具没有测试位置）；要与进程共用 `ProductionGate`；要解析 YAML / JSON；要以库依赖调用 7.5 的 `ContractGate`。部署编排也在同一模块 |
| 5 | 发布稿与盘点（`tools.md:452`）：`ProductionGate` 放在 `ApplicationRunner` | `ApplicationRunner` 在上下文刷新、所有 `SmartLifecycle` 启动**之后**才跑，此时 Tomcat 管理端口、Netty、Dubbo 都已开端口。改为在每个进程的配置类里声明一个早于任何 `SmartLifecycle` 的 bean 显式调用（§5.11） |
| 6 | 发布稿 C4：assign-gate 失败率按 `reason!~"ok\|queueing\|ratelimit"` 算；且称 `AssignGateMetrics` 懒注册 | 那个写法把 503（`zone_maintenance` / `zone_closed` / `zone_not_open`，开关区流程本身就会产生）、429、400、404、410 都算成失败。只算 `code="500"`（`AssignGateResponse.java:48`、`:57-60`）。已知组合在构造函数里全部预注册（`AssignGateMetrics.java:33-63`），`computeIfAbsent` 只兜未知组合，不存在「第一次漏算」 |
| 7 | 发布稿 C5：握手拒绝把 `wrong_gate` 和 `bad_signature` 合在一条 | 分开（A21 / A22）：`bad_signature / expired / wrong_zone` 指向密钥轮换或时钟；`wrong_gate` 指向「多个 gate 共用对外地址」，处置完全不同（`GateMetrics.java:57-65`） |
| 8 | 发布稿 D4 用 `histogram_quantile`，上一版用 `le="0.05"` 桶比值 | 50 ms 是 SLO 桶边界（`SceneMetrics.java:89-92`），比值直接表达「超预算帧占比」，不受桶间插值影响。取**桶比值**（A27） |
| 9 | 上一版：`xm.build.release` 缺省空串 | 根 pom 用 `<additionalProperties><commit>${xm.build.commit}</commit>…`（`pom.xml:216-219`）。空元素插值后大概率注入为 null，`BuildPropertiesWriter` 遇到 null 抛 `NullAdditionalPropertyValueException`（实测 3）。缺省改为非空哨兵 **`unreleased`**，与 `commit` / `contract` 的 `unknown`（`pom.xml:63-64`）同一写法；「空元素 → null」由 V3 实证 |
| 10 | 上一版：battle 通告地址拆分是 7.6b 硬前置，「`xm-battle application.yaml:47-48` 一址两用」 | 已过时：6.2 工作区已有 `advertise-port: ${XM_BATTLE_ADVERTISE_PORT:0}`（`:62`）与 `client-advertise-host: ${XM_BATTLE_CLIENT_ADVERTISE_HOST:}`（`:65`）。J2 缩小为「port-base + 序号模板」 |
| 11 | 发布稿：Loki 流标签 `app`、审计流 `stream_kind="audit_fallback"`；上一版：`service`、`stream_class="audit"` | 取后者。代码里的审计 logger 不止兜底：`xm.audit.{fallback,anomaly,asset,admin,ops,poison}`（grep），运维审计行同样要留 30 天；`service` 与基线 Alloy 标签同名 |
| 12 | 打开 JSON 日志后的回灌修法：发布稿「`FallbackLines.parse` 识别 `{` 开头的行」；上一版与映射稿「`--format jsonl` + 按内容去重」 | **两者都要**（J10）：前者让 `kubectl logs` 转储也能直接回灌；后者解决从 Loki 重复导出时 `tx_id = 0` 的行重复入库（现去重键是「文件 SHA-256 + 行号」，`FallbackReplayer.java:28`、`:129`） |
| 13 | 发布稿：审计消费 lag 阈值 100000；上一版 10000 | 都没有基线数据。取 10000、标「未校准」（A60），7.4 压测后回填 |
| 14 | 发布稿：封禁名单同步滞后 > 120 s；上一版 > 60 s | 同步周期 10 s（`xm-scene application.yaml:99` `gain-block-refresh`），60 s = 6 个周期已足够区分抖动。取 60 s（A35） |
| 15 | K8s 稿 Q7：全局服务在逐个确认定时任务互斥前缺省 1 副本；上一版：生产起点 2 | 编辑逐个核对：`FriendSweep`「多副本各跑各的、不选主」（`FriendSweep.java:18-20`）；`AssetOpLoop` 每副本一个、主键 CAS 领取（`AssetOpLoop.java:46`）；`AssetOpCleanup` 删除幂等、首轮随机错开（`:15`）；`GuildRanks` 在 Redis 维护锁下写（`:41`）；`QueueDispatcher` 用 RLock 选主（`QueueDispatcherRunner.java:18`、`:41-45`）；`GateDrainMonitor` 每个 gateway 都跑、写入幂等（`:26`）；trade / team / chat 的启动类写明「可多副本、进程无状态」。**生产起点 2 副本**（data 除外），CI 另有 2 副本 job 实证（Q25） |
| 16 | 关区：K8s 稿逐个 gate 经 xm-data 排空；上一版与映射稿先停 gate | 区服置维护后 assign-gate 已经不再分配任何 gate，排空标记的唯一作用（不分配新玩家）已被覆盖，剩下的只是等人自己走（deadline 25 min）。改为「维护 → 可选公告并等 `--grace` → 停 gate」，效果相同、时间可控（§5.9） |
| 17 | 关区终态：K8s 稿 MAINTENANCE 或 CLOSED；映射稿与上一版 CLOSED | `--final maintenance\|closed`，**缺省 maintenance**：可逆；CLOSED（503 `zone_closed`）是产品决定（如 7.3 合服之后）。从不调 `DELETE /admin/zones/{id}`（H38） |
| 18 | 发布稿：`release.yml` 加 `push` 输入、推送 job 拿 `packages: write` | 拆成单独的 `release-push.yml`（映射稿与上一版）：用户先看过制品再推；权限只在那一个 workflow 里出现；推的就是冒烟过的那批镜像 |
| 19 | 发布稿：可选 `offline=true` 产出 `docker save \| gzip` | 发布 run 本来就产出 `images.tar`（push 要用），它就是离线包；不另设开关（Q33） |
| 20 | 发布稿：发布 workflow 重跑全部单测 | 改为要求**同一提交**的 `CI` 与 `Integration` 已成功（含单测与契约 `--check`），并用发布镜像重跑整栈冒烟。源码相同、构建戳不同：测源码的证据可复用，测镜像的证据不可复用（Q34） |
| 21 | K8s 稿：startupProbe 预算按 Java 租约 TTL 定，不照搬 300 s；上一版 180 s | 两者一致：基线 300 s 是因为同 Pod 重启 POD_IP 不变、要等 180 s 的 etcd 旧注册过期（`k8s_deploy.ps1:1611-1613`）。Java 新实例从 `[min,max]` 里 `setIfAbsent` 占一个空闲号（`NodeIdLease.java:82-98`），不等旧租约（TTL 15 s，`GateNode.java:75`）。startup 预算只覆盖冷启动（表加载、Dubbo 导出），取 180 s |
| 22 | 映射稿 F1（三稿中只有它提到） | 成立且比原稿更具体：kubelet 缺省给每个 Pod 注入同 namespace 每个 Service 的 `<名>_SERVICE_HOST`、`<名>_PORT=tcp://…`、`<名>_PORT_<n>_TCP_*`。我们的占位符 `XM_BATTLE_CLIENT_PORT`、`XM_BATTLE_RPC_PORT`、`XM_BATTLE_ADVERTISE_PORT`、`XM_GATE_ADVERTISE_PORT`、`XM_SCENE_ASSET_RPC_PORT`（grep）正好是 Service `xm-battle-client` / `xm-battle-rpc` / `xm-gate-advertise` / `xm-scene-asset-rpc` 的链接变量名，谁起了这样的 Service 名，对应进程就拿到 `tcp://10.x:…` 而启动失败。现在没有 `@ConfigurationProperties` 设 `ignoreUnknownFields=false`（grep），其他链接变量只是噪声。infra 里 Service `kafka` 会给 broker 注入 `KAFKA_PORT`，apache/kafka 镜像把 `KAFKA_*` 转成 broker 配置（推断，CI 首轮核实）。全部 Pod 写 `enableServiceLinks: false` |
| 23 | 映射稿 F2 的告警表达式 `count(… unless on(job) label_replace(…)) > 0` | 只按 `job` 匹配，两个 job 互换 application 时两边都被消掉、告警不响。改为两侧都按 `(job, application)` 匹配（A12） |
| 24 | 映射稿 F7：管理端口有两种绑定写法 | 成立：非 Web 进程的管理端口就是 `server.port` / `server.address`（如 `xm-gate application.yaml:17-19`），gateway 用 `management.server.*`（`xm-gateway application.yaml:39-43`）。模板**不得**设 `SERVER_PORT`（对 gate / scene 等改的是管理端口，探针与抓取注解会对不上），端口一律走 values 的 `managementPort` 同时生成 env、探针与注解 |
| 25 | 映射稿 F8 | 成立：Dockerfile 钉的 JRE 是 `…@sha256:000fd431…`（`deploy/docker/Dockerfile:12`），7.1 规格草图是 `7fd597bf…`（`deploy-ci-spec.md:513`）。「infra 镜像 digest 与 compose 逐字相同」守卫比对**文件**，不比对规格文本 |
| 26 | 上一版缺陷表 | 补上 K8s 稿新发现的 B3（MySQL 账号口令不同源）、B4（C++ 连 Redis 不做 AUTH）、B5（推荐 zones 文件把回退解析器读坏）、B12（external gate 宽限 30 s）、B13（gateway 每 zone 一份而数据全局）；B11 补「team 也不部署」（`k8s_deploy.ps1:143`） |
| 27 | 上一版：preflight 只有「扫仓库文件」的笼统结论 | 补上发布稿新发现的 G1（`secret.gate.login` 恒 FAIL，preflight 在提交状态的仓库上永远到不了 exit 0）与 G2（仓库提交的开发密钥能过检查），见 §2.10 |
| 28 | 上一版：告警 A61 / A62（5.2）随 5.2 | 5.2 已提交，两条随 7.6a 落地（本稿 A63 / A64） |
| 29 | 发布稿 D6 存储积压告警（上一版没有） | 采纳：`executor_queued_tasks` / `executor_queue_remaining_tasks`（`name="scene-storage"`）已注册（`SceneMetrics.java:547-552`），用占比而不是绝对值（A32） |
| 30 | 上一版：Dubbo 提供方宽限期 40 s | 按「preStop + 停机阶段 + 10 s」：Dubbo 提供方 5 + 20 + 10 = **35 s**（`xm-login application.yaml:17` 等）；gateway 5 + 10 + 10 = 25 s（`xm-gateway application.yaml:18`）；gate 没写停机阶段（缺省 30 s）取 40 s；battle / data 20 + 10 = 30 s |
| 31 | 上一版：`xm.login.mode` 写死 dev（`:84`） | 成立且是门禁重点（发布稿）：代码缺省是 prod（`LoginProperties.java:41`），但 yaml 写死 `dev`，与 `XM_RUN_MODE` 互不相干——`XM_RUN_MODE=prod` 的部署漏改它，开发口令登录照样开着。J3 改占位符、J8 门禁单独查 |
| 32 | 区服写入 | `POST /admin/zones` 的 `manualStatus` 为 null 时按 OPEN 写入（`ZoneAdminController.java:58`）。`xm-ops` 开区必须显式传 `manualStatus=1`（MAINTENANCE） |

---

## 1 基线 K8s 开区编排（mmorpg `26ceb70ca`）

### 1.1 入口、命令与门禁

- **命令**：`mm:tools/scripts/k8s_deploy.ps1` 提供 `zone-up|zone-down|zone-status|all-up|all-down|all-status|infra-up|infra-down|infra-status|infra-kafka-topics`（`:8`），分派在 `:5524-5582`；
  `dev_tools.ps1` 包装成 `k8s-*`（`:1419-1435`），zone 回滚在 `:1560`。发布路径 `k8s_image.ps1 release-zone|release-all`：门禁 → 构建 → 推送 → 调 `zone-up` / `all-up`（`k8s_image.ps1:456-472`）。
- **相关脚本**：集群外入口生成器与预检 `lib/k8s_client_entry.ps1`；gate 排空 `k8s_gate_drain.ps1`；区服回档 `k8s_zone_rollback.ps1`；集群内压测 `deploy/k8s/robot_stress.ps1`（`:1-45`）。
- **只有写操作过门禁**（`:5512-5522`），依次是：不可变 tag（`Assert-ImmutableReleaseImages`，`:365-379`，dev 不查）→ staging / prod 跑 `release_preflight.ps1`（`:388-405`；`-SkipPreflight` 只打警告）
  → 集群外入口的参数组合与集群现状预检（`Assert-ClientEntryDeployPreflight`，`:900`）→ 懒解析秘密（`Initialize-InjectedSecrets`，`:425-480`）→ C++ gRPC deadline 预算（`:662-735`）。
  `*-down` / `*-status` 不过门禁，排障与止血不会被挡（`:416-424`、`:5510`）。
- **tag**：没给就用 git 短 sha，脏树加 `-dirty`（`:318-336`）；显式给 NodeImage 时 Go / Java 的 tag 跟随它（`:338-346`）；拉取策略按 tag 是否可变推导（`:39-42`、`:348-350`）。
- **命名**：zone namespace = `<NamespacePrefix>-<ZoneName>`（`:1300-1303`；前缀缺省 `mmorpg-zone`，`:23`），infra namespace `mmorpg-infra`（`:24`）。`-ClusterId`（0..31，雪花 worker 段高 5 位）
  是集群常量，infra 与 zone 必须同值（`:13-22`），`dev_tools` 不透传它（`mm:deploy/k8s/AGENTS.md:83`）。
- **发布档位** `-ReleaseProfile dev|staging|prod`，缺省 dev（`:38`）。
- **离线验证**：`-DryRun` 把每份 YAML 打印在 `--- BEGIN/END MANIFEST ---` 之间（`:1275-1288`）；CI 跑 pwsh 契约测试（`tools/scripts/tests/k8s_deploy_contract.tests.ps1` 1088 行；`.github/workflows/deploy-config-tests.yml:73-114`）。

### 1.2 工作负载总表

**infra namespace（所有 zone 共用）**

| 组件 | 形态 | 探针 | PDB | 宽限 | resources | 出处 |
|---|---|---|---|---|---|---|
| etcd | StatefulSet×3，Parallel，每成员 8Gi | readiness / liveness HTTP | minAvailable 2 | 30 s | req 100m / 256Mi | `manifests/infra/etcd.yaml:58-62`、`:68-200`；镜像 `bitnamilegacy/etcd:latest`（`:114`） |
| redis | StatefulSet×1，8Gi，AOF，可选口令（Secret `redis-auth`） | startup / readiness（PONG 或 NOAUTH 都算）/ liveness tcp | minAvailable 1 | 30 s | req 250m / 512Mi | `redis.yaml:51-57`、`:63-155`、`:94-95`（requirepass） |
| redis-match-cluster | StatefulSet×6 + 建群 Job | 有 | 无 | 30 s | 有 | `redis-match-cluster.yaml:52-138`、`:144-200` |
| kafka | StatefulSet×1，20Gi，单 broker，`apache/kafka:latest` | startup / readiness API 级、liveness tcp | minAvailable 1 | 60 s | 300m / 1Gi ~ 2 / 2Gi | `kafka.yaml:85-91`、`:97-292`、`:129` |
| kafka-topic-init | Job，每次 infra-up 先删再建 | — | — | — | 有 | `kafka-topic-init.yaml:43-165`；`k8s_deploy.ps1:5187-5252` |
| mysql | Deployment×1，Recreate；20Gi RWO 数据卷 + 50Gi **RWX** 备份卷；口令写死 | 只有 readiness（`mysqladmin ping -h localhost`，走 socket），**无 liveness** | 无 | 缺省 | 200m / 512Mi ~ 1 / 1Gi | `mysql.yaml:17-40`、`:77-182`、`:161-172`；口令 `:113-114`、`:127-128`、`:168-169` |
| mysql-backup | CronJob `17 3 * * *`，口令写死，挂数据 PVC | — | — | — | 有 | `mysql-backup-cronjob.yaml:39`、`:61`、`:126-136`；**infra-up 不 apply 它**（`k8s_deploy.ps1:5339-5342`） |
| loki | Deployment×1，Recreate，10Gi，保留 168 h | readiness | 无 | — | 100m / 256Mi ~ 1Gi | `loki.yaml:26-34`、`:89`、`:127-170`；只在开了 sidecar 且没给外部 URL 时部署（`k8s_deploy.ps1:5339-5342`） |
| battle（全局池） | Deployment / hostPort Deployment / Agones Fleet 三选一 | startup tcp 2 s×150，readiness tcp | 无（Fleet 上 `eviction.safe: Never`） | 30 s | **无** | `k8s_deploy.ps1:1656-1677`、`:5254-5322`；`lib:988-1180`、`:1064-1065` |
| match / chat / client-rpc-router / trade / friend（Go） | Deployment×2，preferred 反亲和，带 prometheus 注解 | gRPC readiness / liveness | minAvailable 1 | 30 s + preStop `sleep 5` | 100m / 128Mi ~ 500m / 256Mi | `manifests/go-svc/*.yaml`；目录 `k8s_deploy.ps1:697-734` |
| trade-migrate / friend-migrate | Job，`podFailurePolicy`（K8s ≥ 1.26），`restartPolicy: Never` | — | — | — | 有 | `trade-migrate.yaml:22-59`；调度 `k8s_deploy.ps1:3818-4069` |

**zone namespace（每 zone 一套）**

| 组件 | 形态 | 探针 | PDB | 宽限 | resources | 出处 |
|---|---|---|---|---|---|---|
| node-config ConfigMap | base_deploy_config + game_config，只读整目录挂 `/app/bin/etc`，**遮住镜像里的配置** | — | — | — | — | `k8s_deploy.ps1:1328-1538`、`:1715` |
| gate（podip） | Deployment，缺省 2 | readiness tcp 18000；不加 startup / liveness（理由 `:1606-1616`） | **无** | 30 s | **无** | `:1618-1630`、`:1686-1746` |
| gate（external） | StatefulSet（Parallel / OnDelete）+ headless + 每序号 Service | 同上 | `maxUnavailable: 0`（D87） | 30 s（库缺省，调用方没传） | **无** | `lib/k8s_client_entry.ps1:764-962`、`:774`、`:825`；`k8s_deploy.ps1:2475-2505` |
| gate-entry Service | 只在 podip 且 gate 恰为 1 副本时生成（D90） | — | — | — | — | `lib:509-517`；`k8s_deploy.ps1:4994-4999` |
| scene（单池，或 world / instance 两池） | Deployment（单池缺省 4）或 Fleet | readiness tcp gRPC 口 | **无** | 60 s | **无** | `:1632-1654`；`:94`；计划在 `:4818-4868` |
| C++ 日志 sidecar | Alloy 原生 sidecar（initContainers + `restartPolicy: Always`） | — | — | — | 20m / 64Mi ~ 256Mi | `:46-58`、`:1559-1574`、`:1941-2027` |
| db | Deployment×1 | gRPC | 无 | 30 s + 5 s | 有 | `go-svc/db.yaml:24-70` |
| data-service | Deployment×1，Recreate（库全局，进程却每 zone 一份） | gRPC | 无 | 30 s | 有 | `go-svc/data-service.yaml:24-31`；`mm:deploy/k8s/AGENTS.md:85` |
| login | Deployment×2，反亲和 | gRPC | minAvailable 1 | 30 s + 5 s | 200m / 256Mi ~ 1 / 512Mi | `go-svc/login.yaml:24-115` |
| player-locator | Deployment×2，反亲和 | gRPC | minAvailable 1 | 30 s + 5 s | 有 | `go-svc/player-locator.yaml` |
| scene-manager | Deployment×2，Redis 选主，**无反亲和** | gRPC | minAvailable 1 | 30 s + 5 s | 100m / 128Mi ~ 500m / 256Mi | `go-svc/scene-manager.yaml:24-105`（多副本理由 `:25-31`） |
| gateway（Java） | Deployment×2，反亲和，`JAVA_TOOL_OPTIONS=-Xms64m -Xmx384m` | startup / readiness `/actuator/health/readiness`，liveness `…/liveness`；分组**不含** db / redis | minAvailable 1 | **没写宽限，没有 preStop** | 200m / 512Mi ~ 1 / 1Gi | `java-svc/gateway.yaml:24-115`（分组理由 `:63-68`） |
| gateway Ingress | 只在给了 host 时生成，只路由 `/api`；class 缺省 `nginx` | — | — | — | — | `k8s_deploy.ps1:4562-4628`，参数 `:211-226` |

**观察**：
- gateway 每 zone 一份，但它的数据是全局的（`mmorpg` 库的 `zone_config`、全局 Redis；ConfigMap `:4520-4548`）。多 zone 共用一个 Ingress host 时会在不同 namespace 出现同 host、同 `/api` 的多个 Ingress；
  host 用 `{zone}` 区分时客户端又只有一个 gateway 入口。基线没有处理这个矛盾（B13）。
- 全局 Go 服务按命令行 `-ZoneId`（缺省 101）注册 etcd（`:4442-4471`），battle 的 `battle-node-config` 也取这个值（`:5328-5330`）；注释说不影响路由（`:4459-4463`）。

### 1.3 一键开区、全量、关区、回滚实际做了什么

- **zone-up**（`Apply-Zone`，`:4870-5021`）：解析 scene 计划（`:4886-4891`）→ 建 namespace（`:4903`）→ external 下先本地渲染 gate 并 `--dry-run=server` 预演，在任何删除之前暴露跨 zone 的 nodePort 冲突
  （`:4911-4919`、`:2507-2533`）→ 删另一种形态的 gate，会踢人的删除要 `-AllowDisruptiveSwitch`（`:4925-4927`）→ Agones 模式建 SDK RBAC（`:4932-4935`）→ node-config 与 sidecar ConfigMap
  （`:4937-4945`）→ gate（`:4947-4953`）→ 逐个 scene 池，Agones 下加 FleetAutoscaler（`:4955-4992`）→ 按条件 gate-entry（`:4994-4999`）→ zone 内 Go 服务（ConfigMap → Secret → Deployment，
  `:5001`、`:4377-4436`）→ Java gateway（`:5003`）→ `-WaitReady` 时等待（`:5005-5006`、`:2635-2695`；Agones 下不等 scene，只打印命令，`:2660-2669`）→ 换形态时提示人工删旧对象（`:5008-5018`）。
- **all-up**：先 infra（除非 `-SkipInfra`）再逐个 zone（`:5551-5561`）。
- **infra-up**（`:5324-5474`）：先渲染 battle 配置，让密钥错误在写之前暴露（`:5326-5330`）→ apply etcd / redis / redis-match-cluster / kafka / mysql，条件满足加 loki（`:5339-5342`），
  其中三处「先删同名 Deployment 再 apply StatefulSet」是在迁移资源类型（`:5350-5410`）→ Redis Secret 先于 redis.yaml（`:5380-5403`）、mysql-init ConfigMap 先于 mysql（`:5412-5416`）→
  有未替换的 `__XXX__` 占位就拒绝（`:5434-5438`）→ topic-init Job → 依次等待（`:5444-5465`）→ battle 池与全局 Go 服务（`:5467-5473`）。
- **zone-down = `kubectl delete namespace`**（`Remove-Zone`，`:5023-5029`）：不维护、不排空、不踢人（`mm:deploy/k8s/AGENTS.md:56`）。**infra-down 删 infra namespace，PVC 一起没了**（`:5476-5479`；README `:440-441`）。
- **回滚**：用上一个好的 tag 重跑 `zone-up` / `all-up`（`mm:docs/ops/k8s-open-server-runbook.md:139-162`）；紧急关区就是 `zone-down`（`:164-169`）。数据级回档 `k8s_zone_rollback.ps1`：
  zone-down → 人工 PITR → 对 zone 的 Redis `FLUSHDB` → 重置 Kafka offset → zone-up，缺省 dry-run（`k8s_zone_rollback.ps1:1-40`）。
- **「一键开区」缺的几块**：`zone_<id>_db` 只在 MySQL PVC **首次** initdb 时生成（`:5049-5065`、`:5096-5186`；`mysql.yaml:144-153`），已有集群开新区只打一条告警（`:5061`）；
  区服目录 `zone_config` 只播种 zone 1（`mm:deploy/mysql-init/gateway_tables.sql:37-39`）；告警规则、备份 CronJob、scene-manager 的 Agones RBAC 都要手工 apply（`mm:deploy/k8s/AGENTS.md:137`）。

### 1.4 配置与秘密

- **配置单一真相靠生成器搬运**：`Get-AuthoritativeScalar` / `Get-AuthoritativeYamlBlock` 从各服务 `etc/*.yaml` 取值，取不到就抛错（`:496-560`）；node-config 搬运 `Etcd.NodeTTLSeconds`、`IdSegments`、
  `GrpcClient` 等（`:1342-1406`）。要这么费力，是因为 ConfigMap **遮住**镜像里的 `bin/etc`，漏一个键就是线上静默错误（`:1336-1340`）；topic 代号还要三方核对（`:1408-1428`）。
- **秘密来源**：环境变量 `MMORPG_*` 经 `Resolve-InjectedSecret`（`lib/release_common.ps1:679-708`）。dev 回落占位值；staging / prod 下缺失、命中占位串（`:648-660`）、长度不够一律拒绝；
  gate 令牌与 internal-auth ≥32 位，MySQL / Redis 口令 ≥12 位（`k8s_deploy.ps1:425-458`）。
- **但大部分秘密最后进了 ConfigMap 明文**：gate 令牌密钥（node-config `:1431`、login / scene-manager ConfigMap `:3292`、`:3397`、gateway `:4538`）、battle 票据密钥（`:1384`）、MySQL 口令
  （`:3165`、`:3605`、`:3720`）、Redis 口令（`:3110`、`:3134`、`:3224` 等）、gateway 数据源口令（`:4530`）。真正走 Secret 的只有 gateway 管理口令（`:291-305`、`:4603-4614`）、login 开发口令
  （`:286-292`）、Redis 服务端口令（`:5380-5403`）；资产通道密钥只登记了环境变量名，开关恒为 false（`:3651-3663`）。
- **轮换**：Pod 模板上的口令指纹注解让秘密一变就滚动（`:298-299`、`:4303-4330`）；sidecar 配置哈希注解同理（`:1893-1901`）。
- **模式参数不粘滞**：`-GateRouterMode`、`-ClientEntryMode` 和地址参数每次按本次取值生成，漏传就静默落回缺省；基线靠集群现状比对加 `-AllowDisruptiveSwitch` 兜一部分（`:166-178`、`:1000-1063`），
  `-GateRouterMode` 没有拦截（README 的 Optional Flags 段）。

### 1.5 扩缩容与滚动节奏

- **副本数**写在 zones 配置的 `replicas.{centre, gate, scene | scene_world, scene_instance}`（`zones.sample.yaml`、`zones.ops-recommended.yaml:1-20`、`zones.10zones.yaml`），可命令行覆盖（`:69-84`）。
  解析器在 `:4631-4816`；没装 powershell-yaml 时用逐行回退解析，正则 `^(scene_world|scene_instance|centre|gate|scene)\s*:\s*(\d+)$`（`:4698`），**行尾带注释就读不到**（B5）。
  `centre` 是遗留字段：解析、打印，不部署任何东西（`:4760`、`:4899`）。
- `-OpsProfile managed-cloud|bare-metal` 把 gate 抬到 ≥2、legacy scene ≥4、battle ≥2（`:770-798`），拆分池不抬（`:766-769`）。出现任一拆分键就生成 `scene-world` / `scene-instance`，旧 `scene` 要手工删（`:5008-5010`）。
- **没有 HPA**（全仓库 grep 不到 `HorizontalPodAutoscaler`）；只有 Agones 的 FleetAutoscaler（Counter 策略、需 beta 特性，`:100-130`、`:2294-2347`）。频道扩缩容在 scene-manager，管频道数不管 Pod 数。
- **滚动**：C++ gate / scene Deployment 不写 `strategy`，即 K8s 缺省 RollingUpdate 25% / 25%（`:1679-1746`）。新 scene 一监听 gRPC 口就算就绪，旧 scene 随即收 SIGTERM，在 60 s 宽限里 `exitAllPlayers`
  存盘后踢人（`:91-94` 注释）——**基线每次 scene 发版都断开玩家**（B19）。
- **external 下的 gate**：OnDelete + PDB 0，滚动只能走 `k8s_gate_drain.ps1`：标 `gate:<id>:draining` → 等 login 写 `drained` → 删 Pod → 清标记（`k8s_gate_drain.ps1:1-60`）；
  缩容后多余的每序号 Service 由 `Remove-StaleGateOrdinalServices` 删（`k8s_deploy.ps1:2535-2554`）。

### 1.6 探针、宽限期、PDB、亲和

#### 1.6.1 基线的探针纪律（值得照搬的部分）

- C++ 没注册 `grpc.health.v1`，一律 tcpSocket 探**真在监听**的端口，而且只在注册进 etcd 之后才 listen；readiness 只等于「已发布」，**不等于依赖门已过**（`:1606-1616`）。
- gate / scene 不加 liveness：tcp 看不出 EventLoop 卡死，加了只多一条杀容器的路（`:1610`）。
- startupProbe 预算必须越过 etcd 租约（180 s）：同一个 Pod 重启 POD_IP 不变，旧注册要等租约到期才消失；所以 battle 取 2 s×150 = 300 s（`:1611-1613`、`:1656-1677`）。
- gateway 的探针打 actuator 分组端点，不打复合的 `/actuator/health`：复合端点含 db / redis，共享依赖一挂所有副本同时被摘、被杀（`java-svc/gateway.yaml:63-68`）。

#### 1.6.2 PDB 与亲和

- Go 服务 2 副本、PDB minAvailable 1、preferred 反亲和；PDB 写在主清单里，因为脚本只 apply 目录里登记的主清单（`go-svc/login.yaml:100-115`、`scene-manager.yaml:92-105`）。
- scene-manager 没有反亲和（`scene-manager.yaml:40-41`）；db、data-service 单副本没有 PDB；scene、podip gate、battle Deployment 都没有 PDB。
- external gate 是 `maxUnavailable: 0`（`lib/k8s_client_entry.ps1:940-962`）：故意挡住 `kubectl drain` 与 cluster-autoscaler，维护前先排空。kafka / redis minAvailable 1，等于禁止自愿驱逐（`kafka.yaml:75-91`、`redis.yaml:47-57`）。

#### 1.6.3 一条基线推理在 external 形态下不成立

`:1614-1615` 写「readiness 失败不杀容器……login 从 etcd 取 POD_IP:18000 原样下发给客户端，不经过任何 Service，所以 gate NotReady 不会把玩家挡在门外」。这句话**只对 podip 成立**。
external 形态下客户端连的是每序号 Service（`lib:864-913`），Pod NotReady 时这个 Service 的 endpoints 为空，新连接直接失败；基线没出事只是因为 gate readiness 是纯 tcp（监听即 UP）。
**对 Java 的含义**：gate / battle 的 readiness 不能叠加「排空中」或「准入关闭」（§5.6）。

### 1.7 集群外入口（podip / external）

- **podip（缺省）**：login 下发 POD_IP，只有集群内 robot 连得上（`:908-913` 告警文案；README `:257-259`）。单一 gate-entry 只在 gate 恰为 1 副本时生成：票据绑 `gate_node_id`，多副本时 Service
  随机分流，约一半握手以 `token_gate_node_mismatch` 被拒（D90；README `:331-337`）。**Java 有同样的约束**：`GateTokens.verify` 比对自己的 gate 节点号（`xm-common/.../token/GateTokens.java:53`）。
- **external**：gate 是 StatefulSet + 每序号 Service（NodePort = base + 序号，或 LoadBalancer + 主机模板），`externalTrafficPolicy` 缺省 Local（D89）；通告地址由启动 shell 前缀从 `POD_NAME`
  算出序号再导出，算不出就 `exit 64`（`lib:477-507`），由 C++ 自报 `client_endpoint`；每个 zone 的 `gateNodePortBase` 段不能重叠（D88）；gateway 配了 Ingress 却没配可信代理时预检报错（D91）。
- **地址参数不粘滞的后果**：OnDelete 下现有 Pod 仍自报旧地址，每序号 Service 却立刻被改写（`:198-202`）。
- **状态**：以上全部**没有上过集群**（README `:378-381`）。

### 1.8 Agones（基线有，从未上集群）

- scene Fleet：「1 个 GameServer = 1 个 scene 进程 = N 个房间」（`mm:deploy/k8s/AGENTS.md:110`）；`portPolicy: None`、`scheduling: Packed`、没有 K8s 探针、健康交给 SDK（`:2124-2292`）；
  可选 `counters.rooms`（`:2159-2173`）与 FleetAutoscaler（`:2294-2347`）；SDK RBAC 每 namespace 一份（`:2373-2403`）。
- battle Fleet：`portPolicy: Dynamic`；排空靠 `allocationOverflow` 打 `mmorpg.io/drain`；`eviction.safe: Never`（`lib:988-1106`、`:1043`、`:1056`、`:1064-1065`）；health initialDelay 按启动最坏耗时自动抬高（`lib:553-604`）。
- 开关 `-SceneOrchestrator` / `-BattleOrchestrator`，缺省 deployment，不做自动探测（`:86-90`）。代价：Agones 写死 `restartPolicy: Never`，日志 sidecar 必须做成原生 sidecar（`:1941-1970`）。
- 状态：「阶段 B 已落码，未上集群」（`mm:deploy/k8s/AGENTS.md:109`）。

### 1.9 验证现状

- **离线**：`-DryRun` 渲染 + pwsh 契约测试（§1.1）。
- **实跑**：只在 kind（v0.33.0 / node v1.37.0）上跑过 A 档（infra）和 B 档（zone 内 Go 五服务 + Java gateway；C++ 用占位镜像，CrashLoop 属预期），查出 4 个真 bug（README `:718-760`、`:799-881`）。
- **从未运行过**：C++ 节点真实启动、external 入口、Agones、路由模式下含 gate 的链路、日志 sidecar 在真 zone 上的端到端（README `:378-381`；runbook `:225-228`）。

### 1.10 基线缺陷（Java 不照搬；建议 mmorpg 同修，不阻塞 Java）

| # | 缺陷 | 证据 | 来源 |
|---|---|---|---|
| B1 | 大部分秘密以明文进 ConfigMap，有 ConfigMap 读权限的主体都能拿到 gate 令牌与库口令 | §1.4 | 已知 |
| B2 | infra 清单写死口令：root `Mmorpg#2026db`、`appuser/apppass123` | `mysql.yaml:113-114`、`:127-128`、`:168-169`；`mysql-backup-cronjob.yaml:61` | 已知（行号已更正） |
| B3 | **MySQL 账号口令与注入口令不同源**：initdb 建的 `appuser` 口令写死 `apppass123`，而 staging / prod 下 `MMORPG_GATEWAY_DB_PASSWORD=apppass123` 命中占位串被拒。gateway 在非 dev 档必然认证失败，除非运维手工 `ALTER USER`，流程里没有这一步 | `mysql.yaml:125-128`；`release_common.ps1:659`；`k8s_deploy.ps1:455-458` | 新发现（读代码推断，未运行） |
| B4 | **C++ 连 Redis 不做 AUTH**：node-config 写 `zoneredis.password: ""`，`RedisManager` 没有 AUTH 路径；而 staging / prod 强制 Redis 口令 ≥12、服务端 `--requirepass`。非 dev 档 C++ gate / scene 的 Redis 命令会得到 NOAUTH | `k8s_deploy.ps1:1517-1520`、`:446-447`；`redis.yaml:94-95`；`cpp/libs/engine/thread_context/redis_manager.cpp`（grep 无 auth / password）；`node.cpp:541-557` | 新发现（读代码推断，未运行） |
| B5 | **运维推荐的 zones 文件会把回退解析器读坏**：`zones.ops-recommended.yaml:10-11` 行尾带注释，回退正则（`:4698`）匹配不上，zone 101 的两个拆分键被静默丢弃、落回单池 `scene`；而生产的 `StrictNodeTypeSeparation=true` 会让副本创建报 `ErrNoNodeForPurpose`。这个文件自己在 `:41-42` 警告过「行尾不写注释」，runbook 却指向它（`k8s-open-server-runbook.md:101`、`:132`、`:158`） | 同左 | 新发现（只在没装 powershell-yaml 时触发） |
| B6 | C++ Pod 一律不写 resources（BestEffort），节点有压力时第一批被驱逐 | `:1686-1746`；`lib:764-862` | 已知 |
| B7 | scene、podip gate、battle 没有 PDB；scene-manager 没有反亲和；db 单副本没有 PDB | §1.6.2 | 已知 |
| B8 | 开区不建新 zone 的库、不登记区服目录；关区就是删 namespace，不维护、不排空 | §1.3 | 已知 |
| B9 | 备份 CronJob 不在一键流程里，并且挂 MySQL 的 RWO 数据卷（`mysql-backup-cronjob.yaml:126-136` 自认只在同节点能用）；RWX 备份卷在 kind 上一直 Pending | 同左 | 已知 |
| B10 | 浮动 tag：`apache/kafka:latest`、`bitnamilegacy/etcd:latest` | `kafka.yaml:129`；`kafka-topic-init.yaml:61`；`etcd.yaml:114` | 已知 |
| B11 | **guild 与 team 都不在 K8s 上部署**，玩家可见的功能缺失 | `k8s_deploy.ps1:143`；`mm:deploy/k8s/AGENTS.md:90` | 补全（上一版只提 guild） |
| B12 | external gate 宽限期 30 s（库缺省、调用方没传），scene 是 60 s；`k8s_gate_drain` 弥补了一部分，但直接 `kubectl delete pod` 仍是 30 s | `lib:774`、`:825`；`k8s_deploy.ps1:2496-2497` | 新发现 |
| B13 | gateway 每 zone 一份、每 zone 一个 Ingress，而它的数据是全局的 | §1.2 观察；`:4562-4628` | 新发现 |
| B14 | 没有 NetworkPolicy（基线自己点名） | `k8s_deploy.ps1:3655-3658` | 已知 |
| B15 | 文档漂移：README `:17`、AGENTS `:24`、`:82` 说 redis 是单副本 Deployment，实际是 StatefulSet + PVC | `redis.yaml:63-70` | 已知 |
| B16 | login 比 player-locator 早起时 fatal 重启几次，靠后者就绪后自愈 | README `:872-875` | 已知 |
| B17 | 模式参数不粘滞 | §1.4 | 已知 |
| B18 | 遗留物：`go-svc/gateway.yaml` 从不部署且与 Java gateway 的 Service 同名；`$JavaSvcCatalogue.auth` 无清单（`:762`）；`centre` | `:697-734` | 已知 |
| B19 | 每次 scene 发版都断开 scene 上的玩家（缺省滚动 + `exitAllPlayers`） | §1.5 | 已知 |

---

## 2 发布制品与门禁（基线）

### 2.1 版本号与镜像 tag

| 项 | 基线规则 | 出处 |
|---|---|---|
| 两条轨 | 快照轨（无版本号）与发布轨（`vX.Y.Z[-pre]`） | `docs/ops/release-checklist.md:16-21` |
| 版本号正则 | `^v(0\|[1-9]\d*)\.(0\|[1-9]\d*)\.(0\|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$`；ECMAScript 语义（`\d` 只认 0-9）；含空白先拒（.NET 的 `$` 放过末尾换行）；必须小写 v | `lib/release_common.ps1:300`、`:318-338` |
| commit 口径 | `git rev-parse --short=12`，校验 `^[0-9a-f]{12}\z` | `:212`、`:303` |
| 脏树 | `git status --porcelain` 非空 | `:218-221` |
| 镜像 tag | 快照 `<sha12>`（脏树 `<sha12>-dirty`）；发布 `vX.Y.Z-<sha12>`，脏树 throw；超过 128 字符 throw | `:353-381` |
| 可变 tag 黑名单 | latest、dev、develop、main、master、stable、edge、prod、production、release、nightly、snapshot、current（不区分大小写）。理由：tag 被覆盖后 `rollout undo` 退回同一 digest | `:186-193`、`:232-256` |
| pull policy | 不可变 tag `IfNotPresent`，可变 tag `Always` | `:283-290` |
| 「四处一致」 | git tag = `-Version` = CHANGELOG 的 `## [X.Y.Z]` = 镜像自报版本（OCI label、`/app/BUILD_INFO`、启动版本行） | `CHANGELOG.md:13-14`；`release.yml:4-7` |
| 制品版本目录名 | 快照 `g<sha12>`（`-AllowDirty` 时加时间戳）；发布 `vX.Y.Z` | `publish_images.ps1:24`；`artifacts_lib.ps1:64-67` |
| Java gateway 的版本注入 | build-info `additionalProperties.version` 覆盖 `build.version`，依赖 spring-boot-loader-tools 3.4.3 的写入顺序，注释自承升级后可能静默失效 | `java/gateway_node/pom.xml:26-32`、`:155-171` |
| 镜像自报 | OCI label version / revision / created / source / title，另写 `/app/BUILD_INFO`（version、commit、built_at） | `deploy/k8s/Dockerfile.java-svc:78-86` |

### 2.2 制品根、布局与三条铁律

- **制品根**：`-ArtifactRoot` > `MMORPG_ARTIFACT_ROOT` > `<仓库父目录>/artifacts`，放在仓库外防止 GB 级 tar 被 `git add -A` 带进去；只读用途带 `-MustExist`（`artifacts_lib.ps1:69-121`）。
- **布局**：`<root>/{snapshots|releases}/images/<ver>/{images/*.tar, images-manifest.json, build-info.json, symbols/*.debug, sha256sums.txt}`，另有唯一可变的 `latest.json` 与 `releases/manifests/<vX.Y.Z>.{json,md}`
  （`publish_images.ps1:16-25`；`release-checklist.md:30-49`）。
- **铁律**：① 不可变，版本目录已存在就拒（`artifacts_lib.ps1:276-293`）；② 原子发布，写 `.tmp-<名>-<PID>` 再整体 `Directory.Move`，被抢先则报发布竞争（`:305-330`）；
  ③ sha256sums 覆盖目录内全部文件，清单外多出文件也算失败，格式 LF、序数排序、无 BOM（`:176-259`）。
- **表摘要**：顶层 `*.json` 按序数排序拼成 `"<sha>  <name>\n"` 再算 sha256；只认 `.json`，`.pb` 不在覆盖范围（`:415-466`）。

### 2.3 清单字段与流程

| 文件 | 字段 | 出处 |
|---|---|---|
| `images-manifest.json` | `[{family, service, ref, image_id, revision, file}]` | `publish_images.ps1:612-620` |
| `build-info.json` | version、channel、app_version、vcs、source_rev、commit、dirty、image_tag、families、image_count、tables_sha256、tables_file_count、published_at、machine、publisher | `:639-656` |
| release manifest `<v>.json` | version、notes、created_at、machine、publisher、source{commit, dirty}、images{version, path, image_tag, image_list, digests}、tables{sha256, file_count} | `make_release.ps1:275-296` |

- **`publish_images.ps1`**（`:7-14`）：版本戳（发布轨脏树拒）→ 目录已存在拒 → 各族构建 → `list-refs` 取期望清单 → 核对 OCI revision / version label → **逐个** `docker save` 并读回 tar 的 RepoTags
  自证（批量 save 层链相同的两个镜像会丢一个，`:28-29`）→ 上线前复查工作树（`:660-662`）→ sha256sums、rename、更新 `latest.json`（`:674-675`）。
- **`make_release.ps1`**：`.json` 已存在拒；说明来源 `-Notes` > `-NotesFile` > CHANGELOG 段；build-info 逐项核对（`:193-229`）；`-ImageDigestsFile` 必须恰好覆盖全部 ref 且同 repo（`:237-266`）；
  先写 `.md` 后写 `.json`（`:319-343`）；不打 tag，只打印命令（`:349`）。
- **离线交付**：`fetch_images.ps1` 复制前后各验一次 sha256sums 再 rename（`:6-30`）；`import_images.ps1` 缺 sha256sums 默认拒、逐个 `docker load` 后核对镜像 ID（`:6-27`）；
  `artifacts_retention.ps1` 只清快照轨、缺省保留 10 个、`latest.json` 指向的永远保留、缺省 dry-run、`releases/` 永不触碰（`:4-37`）。

### 2.4 `.github/workflows/release.yml`

- 只能手动触发（`:19-42`），`permissions: contents: read`（`:44-45`），全仓串行不取消（`:47-52`），超时 180 分钟（`:60`），输入一律经环境变量进脚本（`:61-65`）。
- 步骤：版本号、CHANGELOG 段、git tag 没被别的提交占用（`:97-148`）→ `tools/scripts/tests` 契约测试，目录缺失或一个测试都没有也判红（`:150-199`）→ 仓库外 replace 模块（`:201-356`）→
  工作树必须干净（`:358-375`）→ `publish_images.ps1` 与 `make_release.ps1`（`:377-394`）→ 上传制品保留 90 天（`:396-407`）→ Step Summary（`:409-442`）。
- **刻意不做**：不推镜像、不打 tag、不部署、不用 secret、不装 syft / cosign / trivy / goreleaser（`:12-17`）。**不调用 preflight**：全文只在 `:401` 的注释里提到它。

### 2.5 CHANGELOG 与追溯

- Keep a Changelog 1.1.0 + SemVer 2.0.0；发版把 `[Unreleased]` 改成 `## [X.Y.Z] - YYYY-MM-DD`（方括号里不带 v），再新开 `[Unreleased]`（`CHANGELOG.md:3-15`）。判定函数要求标题精确匹配（`1.2.3` 不命中
  `[1.2.3-rc.1]` 或 `[11.2.3]`）、同一版本只能一段、段落非空、链接定义行不算正文（`release_common.ps1:383-438`）。**基线从未出过版本**：只有 `## [Unreleased]`（`CHANGELOG.md:17`）。
- 五个证据点：Go 启动首行（`go/shared/buildinfo/buildinfo.go:71-72`）、gate 的 `[gate_version]` 行、`/app/BUILD_INFO`、OCI label、registry digest（`release-checklist.md:136-155`）。
- 回滚按上一版 manifest 的 digest（`:157-169`），库迁移只前进；但 `k8s_deploy.ps1` 渲染进清单的仍是 `repo:tag`（`:27-28`）。
- 基线自述：打包标准「P1–P4 已落码，**未编译、未运行、未按 §6 验证**」（`docs/design/release-packaging-standard-20260914.md:4`、`:256`），遗留缺口 7 条（`:246-254`）。

### 2.6 发布门禁 `tools/scripts/release_preflight.ps1`（656 行）

**语义**：判据是非空、非占位串、长度达标（密钥类缺省 ≥32）；「两处一致」对占位串免疫，所以不作主判据（`:7-14`）。文件或键缺失一律 FAIL，唯一例外是核实过「代码缺省即关」的开关
（`MissingPolicy=pass-off`，`:16-18`）。dev 档下密钥类、Kafka 类、镜像 tag 类降为 WARN（`:81`、`:316`）；只有 prod 拒 `-dirty`（`:82`）。退出码注释写 0 / 1 / 2（`:25-28`），全文只有 `exit 1`（`:649`）与 `exit 0`（`:656`）。

| 节 | 检查 id | 目标 | 判据 | dev / staging / prod |
|---|---|---|---|---|
| A | `secret.gate.{cpp,login,scenemgr,loginstack,gateway}` | 5 个文件的 GateTokenSecret（`:257-269`） | 非空、非占位、≥32；**键缺失任何档都 FAIL**（`:131`） | WARN / FAIL / FAIL |
| A | `secret.gate.consistency` | 上面 5 处 | 取值全部一致；可比对的不足 2 处也 FAIL | FAIL |
| A | `secret.admin.apikey` | gateway `admin.api-key` | ≥16 | 同密钥类 |
| B | `secret.mysql.{db,gateway}`、`secret.redis.{db,locator,login}` | 5 个 yaml | ≥12 | 同密钥类 |
| C | `image.tag.provided`；`image.tag.immutable`、`image.ref.immutable` | `-ImageTag` / `-ImageRef` | 必须传；不在黑名单；prod 不许 `-dirty` | FAIL；WARN / FAIL / FAIL |
| D | `kafka.brokers.{cpp,login,db,locator}`；`kafka.partition.match` | 4 个文件 | broker ≥3；login 与 db 的 PartitionCnt 相等 | 同密钥类；FAIL |
| E | `locator.lease.disconnect`、`locator.lease.etcd` | player_locator | ≥30 s | FAIL |
| F | `ratelimit.{zone.rps,zone.burst,ip.rps,queue.timeout,burst.ge.rps,enabled.declared}` | gateway `gate.rate-limit.*` | ≥1；≥1000 ms；burst ≥ rps；键必须存在，false 只 WARN | FAIL / WARN |
| G | `debug.jpa.showsql`、`debug.login.devpassword`、`debug.actuator.exposure`、`debug.cpp.loglevel` | gateway / login / base_deploy_config | 不得 true；不得含 `* env heapdump threaddump configprops beans shutdown loggers`；C++ `LogLevel ≥1` | FAIL |
| H | `release.{version,changelog,artifact.dir,artifact.sha256sums,buildinfo.version,buildinfo.clean,manifest,imagetag.commit}`；`release.buildinfo.commit` | 给了 `-ReleaseVersion` 时 | 版本、CHANGELOG 段、sha256sums、build-info；**manifest 的 digests 为空也 PASS**；commit ≠ HEAD 只 WARN | FAIL（commit 一项 WARN） |

各节出处：A `:252-289`，B `:291-299`，C `:301-338`，D `:340-363`，E `:365-374`，F `:376-410`，G `:412-448`，H `:452-610`，汇总 `:614-656`。
**调用方**：`k8s_deploy.ps1` 的 `Invoke-ReleasePreflight`（`:388-404`，调用 `:5514`；dev 直接返回；只传 `$NodeImage`；不带 `-ReleaseVersion`）、`k8s_image.ps1` 的 `Invoke-ReleaseGate`（`:175-196`）。

### 2.7 部署期的密钥注入门禁 `Resolve-InjectedSecret`

`release_common.ps1:679-706`：dev 可回落缺省值，staging / prod 遇到未设置、占位串、过短都 throw。占位串黑名单 11 个（`:648-660`）：`change-me-in-production-use-a-strong-random-key`、
`change-me-in-production`、`change-me`、`changeme`、`placeholder`、`todo`、`secret`、`password`、`root`、`123456`、`apppass123`。

| 环境变量 | 最小长度 | 出处 |
|---|---|---|
| `MMORPG_GATE_TOKEN_SECRET`、`MMORPG_INTERNAL_AUTH_SECRET`（dev 回落值刻意与 gate 不同） | 32 | `k8s_deploy.ps1:426-437` |
| `MMORPG_MYSQL_USER` / `_PASSWORD`、`MMORPG_GATEWAY_DB_USER` / `_PASSWORD` | 1 / 12 | `:442-458` |
| `MMORPG_REDIS_PASSWORD` | 12 | `:446-447` |
| `MMORPG_LOGIN_DEV_PASSWORD_SHARED_SECRET`（只允许 dev，无回落） | 1 | `:460-467` |
| `MMORPG_GATEWAY_ADMIN_API_KEY` | 32 | `:472-479` |
| `MMORPG_BATTLE_TOKEN_SECRET`（去空白后 ≥32 字节，且 ≠ gate） | 32 | `:1370-1378` |

### 2.8 基线门禁与发布工程的问题（Java 不照搬）

| # | 问题 | 证据 | 结论来源 |
|---|---|---|---|
| G1 | **`secret.gate.login` 在任何档位恒 FAIL，preflight 在提交状态的仓库上永远到不了 exit 0**。login.yaml 已把 gate 密钥迁到 `Secrets.GateToken.Value`（`go/login/etc/login.yaml:261-267`），顶层 `# GateTokenSecret: ""` 是注释行（`:298`），平面化解析把整行丢掉（`release_common.ps1:28-46`、`:85-91`），`Get-YamlScalar` 返回「键不存在」，`Test-SecretValue` 按 fail-safe 判 FAIL（`release_preflight.ps1:131`）。佐证：preflight 的契约测试刻意「只断 release.* 检查项，不断总退出码」（`release_common_version.tests.ps1:150-152`）。后果：staging / prod 部署只能加 `-SkipPreflight`，检查单要求的「必须 exit 0」做不到 | 读代码推断（本机无 pwsh 7） |
| G2 | **仓库里提交的开发密钥能过检查**：`local-dev-gate-token-secret-0123456789abcdef`（44 字符）出现在 `scene_manager_service.yaml:128`、`base_deploy_config.yaml:110`，不在黑名单里，`secret.gate.scenemgr` / `secret.gate.cpp` 判 PASS。教训：黑名单要覆盖仓库自己的开发值，或者开发值根本不进仓库 | 读代码推断 |
| G3 | 退出码 2 只在注释里，脚本自身出错与 FAIL 分不开 | §2.6 | 已核实 |
| G4 | 门禁检查的是**错误的数据源**：生产秘密在部署时经环境变量注入（`k8s_deploy.ps1:410-420` 注释），preflight 却读仓库 yaml | — | 已核实 |
| G5 | 自动调用只检查 C++ 镜像 tag（只传 `$NodeImage`，`k8s_deploy.ps1:399-400`），Go / Java 不经可变性检查 | — | 已核实 |
| G6 | manifest 的 digest 为空也 PASS；CI 出的 manifest digest 恒为空又不可变，事后补不进去 | `release-packaging-standard-20260914.md:249` 缺口 #4 | 已核实 |
| G7 | `release.yml` 没有单元测试门禁、不调 preflight | `:248` 缺口 #3；§2.4 | 已核实 |

---

## 3 日志采集（基线）

### 3.1 本机观测台 `mm:deploy/docker-compose.observability.yml`

| 组件 | 版本 | 要点 | 出处 |
|---|---|---|---|
| Loki | `grafana/loki:3.5.8` | `3100:3100`（0.0.0.0），卷 `loki-data` | `:19-33` |
| Alloy | `grafana/alloy:v1.10.0`，故意不升级：v1.19.2 有读取位置回归，重启后几乎整文件重读 | 只读挂 `../run/logs`、`../bin/logs` | `:11-15`、`:35-57` |
| Grafana | `grafana/grafana:13.2.1` | **匿名访问即 Admin**，admin/admin；数据源只有 Loki；看板允许 UI 改（`dashboards.yaml:12`） | `:59-87`（`:65-70`） |

### 3.2 Alloy 流水线 `deploy/observability/alloy/config.alloy`

- 标签：job（go_services / cpp_nodes / java）、lang、service、zone（只在文件名带 `zN_` 时有）、instance、stream、level（小写）、filename，外加 `env=local`（`:8-16`、`:24-31`）。
- Go：go-zero JSON 取 `@timestamp`；etcd zap 取 `ts`；go-redis 文本按 `America/New_York` 解析（`:39-170`）。C++：去 ANSI、四种行头多行合并、时区与级别映射（`:183-345`）。
  Java：JSON 行与 Spring 文本行都认，Maven / JVM 行头单独成条，logger / thread 进结构化元数据（`:348-468`）。三条流都 `ignore_older_than = "48h"`。

### 3.3 Loki 与 K8s

- `deploy/observability/loki/loki.yaml`：单进程、filesystem、tsdb v13（`:3-53`）；`reject_old_samples_max_age` 与 `retention_period` 都 168h（`:55-58`）；写入 16 MB/s、突发 32 MB、单流 8 MB、
  `max_query_series` 2000（`:62-66`）；compactor 开 retention（`:68-73`）。**ruler 只配了本地目录，没有 `alertmanager_url`**（`:78-83`），基线自认「不存在能生效的日志告警通路」
  （`deploy/k8s/owner-epoch-alerts.yaml:13-16`；`docs/design/guild-phase2/08-save-owner-fence.md:179`）。
- K8s：Loki 单副本 + 10Gi PVC、Recreate、limit 1Gi，改 ConfigMap 要手动 `rollout restart`（`manifests/infra/loki.yaml:1-10`、`:26-43`）。C++ 业务日志不进 stdout，用 Alloy 原生 sidecar 读共享 `node-logs` 卷
  （requests 20m / 64Mi、limit 256Mi、非 root、只读根，`k8s_deploy.ps1:1941-2026`）；配置哈希进 Pod 模板注解，改日志配置等于滚动 C++ 节点；业务容器清理循环只留最近 8 个文件，`emptyDir.sizeLimit` 2Gi（`:271-283`）。
- **Go / Java 在 K8s 上没有日志采集**：「还没有这个 DaemonSet，k8s 上的 Go / Java 日志只在 `kubectl logs` 里」（`docs/ops/grafana-loki-local-logs.md:530`）。

### 3.4 各进程的日志格式

- Java gateway：`logging.structured.format.console: logstash`，字段 `@timestamp / level / logger_name / thread_name / message / stack_trace`（`java/gateway_node/src/main/resources/application.yaml:133-140`）；
  liveness / readiness 分组不含 DB / Redis（`:113-131`）；actuator 只暴露 `health,info`，没有 prometheus（`:108-112`）。
- Go：go-zero logx 缺省 JSON 到 stdout。C++：muduo 文本，`LogLevel: 2` 即 WARN。

### 3.5 值班用的日志查询（不会自动告警）

`docs/ops/cross-zone-failure-test-runbook.md:822-831` 的三条 LogQL（`[AssetOp] blocked: owner_epoch unknown`、`[OwnerEpoch] … owner_epoch_unknown=[1-9]`、`save outran reconnect lease`，各自 10 分钟内出现即算）；
`release-checklist.md:272-281`（B.2）的 grep 关键字，其中 `panic: fatal: OOM Killed` 定为 P0。

---

## 4 看板与告警（逐条）

### 4.1 基线看板

| 看板 | 数据源 | 面板 |
|---|---|---|
| `deploy/k8s/scene-manager-dashboard.json`（uid `scene-manager-overview`，30 s） | `${DS_PROMETHEUS}`；变量 `zone_id` = `label_values(scene_manager_nodes_by_role, zone_id)`（`:44-57`） | 12 个：按角色的节点数（`:92`）、每节点人数 / 场景数 top10（`:108`、`:124`）、镜像共置命中率 stat（红 <0.5、绿 ≥0.8，`:139-166`）、镜像放置结局（`:181`）、销毁原因（`:197`）、EnterScene 拒绝（`:220`）、孤儿对账（`:236`）、源场景缺失（`:259`）、镜像去重（`:275`）、迁移原因 × 结局（`:297`）、迁移积压（`:312`） |
| `deploy/observability/grafana/dashboards/game-logs-overview.json`（uid `xm-game-logs`，10 s） | Loki（uid 写死） | 变量 job / service / zone / level / search；7 个：日志量（`:189`）、警告与错误趋势（`:242`）、错误条数（`:323`）、警告条数（`:385`）、有日志的服务数（`:447`）、错误最多的 topk(8)（`:497`）、日志流（`:551`） |
| `docs/ops/grafana-login-path-deprecation.json` | Prometheus（按名字） | `login_auth_path_total` 按 path（`:27`）、legacy 占比（`:50`）、按 auth_type（`:81`）；内嵌一条告警 |
| `docs/ops/grafana-rollback-audit.json` | 同上 | `data_service_rollback_total` 按 scope / outcome、players_affected、orphans_cleaned、跨场景切换 p95 与结局（`:36-163`）；注释里 3 条告警（`:18-21`） |

基线仓库没有部署任何 Prometheus；本机 Grafana 没有 Prometheus 数据源，后三个看板在基线自带的部署里用不上。

### 4.2 基线可部署告警（21 条，PrometheusRule CR，`interval: 30s`）

两个文件都要求 `kubectl apply -n observability` 到已有 kube-prometheus 的集群（`owner-epoch-alerts.yaml:6-11`；`scene-manager-alerts.yaml:3-11`）。级别：critical 呼人、warning 工作时间处理、info 只做看板注记
（`owner-epoch-alerts.yaml:18-21`）。下表 `SM` 是前缀 `scene_manager_` 的缩写。

| # | 告警 | 表达式 | 阈值 | for | 级别 | 出处 |
|---|---|---|---|---|---|---|
| 1 | DbStaleOwnerWriteRejected | `sum(increase(db_stale_owner_write_rejected_total[10m])) > 0` | >0 / 10m | 0m | warning | `owner-epoch-alerts.yaml:40-52` |
| 2 | DbOwnerEpochLegacyZero | `sum(db_owner_epoch_guard_total{outcome="legacy_zero"}) > 0`（用绝对值：CounterVec 首次出现时 `increase` 抓不到） | >0 | 0m | warning | `:54-72` |
| 3 | SceneManagerInstancePoolEmpty | `(count by (zone_id)(SM nodes_by_role > 0) unless on (zone_id) count by (zone_id)(SM nodes_by_role{role=~"instance\|instance_cross"} > 0)) or on (zone_id) (sum by (zone_id)(SM nodes_by_role) == 0)` | 0 节点 | 2m | critical | `scene-manager-alerts.yaml:55-77` |
| 4 | SceneManagerWorldPoolEmpty | 同上，role 换成 `main_world\|main_world_cross` | 0 节点 | 2m | critical | `:79-100` |
| 5 | SceneManagerWorldNodeSaturated | `max by (zone_id)(SM node_player_count{role=~"main_world.*"}) > 1800` | 1800 | 10m | warning | `:103-114` |
| 6 | SceneManagerLoadScoreDispersionLow | 主世界负载分变异系数 `stddev / avg < 0.1` | 0.1 | 15m | info | `:116-134` |
| 7 | SceneManagerRebalanceStalled | `max by (zone_id)(SM rebalance_pending{reason="node_gone"}) > 0` | >0 | 5m | warning | `:137-153` |
| 8 | SceneManagerRebalanceFailureRate | failed 迁移占比 `分子 / (分母 > 0) > 0.5` | 50% | 10m | warning | `:155-172` |
| 9 | SceneManagerMirrorColocationDegraded | 镜像共置命中率 < 0.5 | 50% | 30m | info | `:175-196` |
| 10 | SceneManagerEnterSceneHandoffAnomaly | `sum by (zone_id, reason)(rate(SM enter_scene_rejected_total{reason=~"handoff_pending_stale_marker\|handoff_pending_withdrawn\|epoch_conflict"}[5m])) > 0.05` | 0.05/s | 15m | warning | `:249-279` |
| 11 | SceneManagerHomeZoneLookupUnavailable | `home_zone_unavailable` 占归属区查询 > 0.5 | 50% | 5m | critical | `:281-304` |
| 12 | SceneManagerHomeZoneLookupRejecting | `home_zone_unavailable` 速率 > 0.05 | 0.05/s | 10m | warning | `:306-319` |
| 13 | SceneManagerHomeZoneUnmappedTravel | `home_zone_unmapped_travel` 速率 > 0.01 | 0.01/s | 10m | warning | `:321-342` |
| 14 | SceneManagerZoneTravelMapUnavailable | `reason=~"travel_map_unavailable\|pending_map_fallback"` 速率 > 0.01 | 0.01/s | 10m | warning | `:344-362` |
| 15 | SceneManagerEnterSceneSceneGone | `scene_gone` 速率 > 0.05 | 0.05/s | 10m | warning | `:364-381` |
| 16 | SceneManagerEnterSceneRollbackRedisError | `sum(increase(SM enter_scene_rollback_total{outcome="redis_error"}[10m])) > 0` | >0 / 10m | 无 | warning | `:402-423` |
| 17 | SceneManagerNodeDetachWithoutDeathMark | `sum by (zone_id)(increase(SM node_detach_deferred_total{outcome=~"expired\|abandoned"}[10m])) > 0` | >0 / 10m | 无 | warning | `:436-453` |
| 18 | SceneManagerSceneOrphanReconcileSpike | `sum by (zone_id)(rate(SM scene_orphans_reconciled_total[5m])) * 60 > 50` | 50 个/分钟 | 5m | warning | `:455-469` |
| 19 | SceneManagerMirrorSourceMissing | `sum by (zone_id)(rate(SM mirror_source_missing_total[5m])) > 0.01` | 0.01/s | 10m | warning | `:471-487` |
| 20 | SceneManagerInstanceDestroyCascadeAnomaly | cascade / 非 cascade 销毁速率比 > 10 | 10 倍 | 15m | warning | `:489-508` |
| 21 | SceneManagerUnknownSceneNodeType | `sum by (zone_id)(SM nodes_by_role{role="unknown"}) > 0` | >0 | 1m | warning | `:511-525` |

刻意不告警：`handoff_pending_no_marker`（生产配置下跨节点换图第一跳必然拿到，`:211-216`）；回滚结局 `marker_gone` / `plan_error`（没有基线，`:390-399`）。开新 zone、整 zone 维护前对该 zone 加 silence
（`:52-53`；`docs/ops/scene-node-role-split.md:206-216`）。

### 4.3 写在文档里、没有部署的告警

| 来源 | 告警 / 查询 | 表达式与阈值 | 级别 |
|---|---|---|---|
| `grafana-login-path-deprecation.json:97-113` | LegacyLoginPathTooHigh | legacy 登录路径占比 > 5%，for 10m | warning |
| `grafana-rollback-audit.json:18-21`（注释写「在 Grafana 里配」） | 回档失败 / 全服回档 / 跨场景切换失败 | failed 速率 > 0.01/s 5m；`scope='server'` 任意 1m；`outcome!='ok'` 占比 > 1% 10m | page |
| runbook `:822-831` | scene 三条 LogQL | §3.5 | 值班查询，ruler 没接线 |
| `docs/design/guild-phase2/92-handoff.md:967`（「建议，BK8s 落规则」） | 帮会 5 条 | `assetop_unknown_total`、`assetop_pending_oldest_age_seconds{stream="2"} > 86400`、`assetop_partial_total`、`guild_tx_deadlock_total`、缓存失效失败 / 资产孤儿持续上升 | 未定级 |
| `92-handoff.md:225-227` | 角色名孤儿 | `increase(login_create_player_name_orphan_total[10m]) > 0` | 未定级 |
| `docs/design/leaderboard-system.md:241-252`（草案，`rank-alerts.yaml` 不存在） | 排行榜 6 条 | RankConsumerStalled 等 | 见原文 |
| `release-checklist.md:259-270`（B.1）、`:361-366`（E.1） | 登录专项与回滚触发 | 登录成功率 <95% 5m、p99 >500 ms 1m、REPLACE >5%、TIME_WAIT、ListenOverflows、lag >1000；新路径成功率 <90%、p99 >2 s 5m、OOM / panic | — |
| `go/data_service/internal/metrics/metrics.go:92-94` | **注释说必须告警，但没有规则** | `data_service_kafka_consumer_up == 0`（积压超过 topic 保留期就真丢了） | — |

### 4.4 基线告警工程的问题（Java 不照搬）

1. 仓库里没有部署 Prometheus / Alertmanager，也没有 ServiceMonitor / PodMonitor；只有 chat、client-rpc-router、friend、match、trade 五个清单带 `prometheus.io/scrape`（grep）。scene_manager 与 db 告警写明的抓取前提单凭仓库不成立。
2. CI 不跑 promtool，只在文档写手工 `promtool check rules`（`08-save-owner-fence.md:254`）。
3. 出过一条「恒为空向量、永远不会响」的告警（SceneGone，`scene-manager-alerts.yaml:198-208`）。
4. 「懒注册的 CounterVec 首次出现时 `increase` 抓不到」踩过两次（`owner-epoch-alerts.yaml:57-59`；`92-handoff.md` §8.1）。
5. 多条阈值自写「代码从未编译、未实测，无基线」（`scene-manager-alerts.yaml:248`、`:401`、`:435`）。
6. 指标带 `node_id` 等实例标签；Java 进程里不加实例 / zone 标签（architecture §11）。

### 4.5 基线 21 条 → Java 逐条处置

Java 指标名有意与基线不同（PARITY「低基数运行指标」行），进程内也不带 `zone_id`（architecture §11），所以没有一条能原样复用。

| # | 基线 | Java 处置 | 理由 |
|---|---|---|---|
| 1 | DbStaleOwnerWriteRejected | **改写 → A30** | Java 的围栏拒绝在 scene 侧计 `xm_scene_storage_writes_seconds_count{result="fenced"}`；序列启动即注册（`SceneMetrics.StorageOp`），第一次也抓得到 |
| 2 | DbOwnerEpochLegacyZero | **不适用** | Java 从第一天就有 `WHERE owner_epoch = ?` 围栏，没有 epoch=0 的兼容窗口（`docs/design/db-migrations.md` M2） |
| 3 | SceneManagerInstancePoolEmpty | **不适用**（最近的对应是 5.3 的 A66） | Java 不分用途池（`dungeon-mirror-spec.md:922`），镜像恒与源场景共置 |
| 4 | SceneManagerWorldPoolEmpty | **改写 → A25 + A38**（另有 A42） | 「zone 有 gate 却没有活 scene」与「zone 没有 ACTIVE 频道」，zone 取自抓取目标标签 |
| 5 | SceneManagerWorldNodeSaturated | **改写 → A39** | 按 scene 目标汇总 `xm_scene_players`；阈值沿用 1800，未校准 |
| 6 | SceneManagerLoadScoreDispersionLow | **不适用** | Java 没有负载分，按「目录人数 + 软预占」挑最少的（PARITY「场景分配」行） |
| 7 | SceneManagerRebalanceStalled | **直译 → A45**（另有 A44） | `xm_scene_manager_rebalance_pending{reason="node_gone"}`（`WorldChannelMetrics.java:38`） |
| 8 | SceneManagerRebalanceFailureRate | **改写 → A46**（另有 A40 / A41） | Java 迁移结局只有计划 / 完成，失败体现在世界 tick 的 `error / fenced / conflict / no_lease`（`WorldChannelMetrics.java:54-64`）；比值写法沿用 |
| 9 | SceneManagerMirrorColocationDegraded | **不适用** | Java 镜像恒共置（`dungeon-mirror-spec.md:960`） |
| 10 | SceneManagerEnterSceneHandoffAnomaly | **改写 → A64**（5.2，已提交） | `xm_scene_transfers_total{result=~"lost_unknown\|fenced\|lease_too_short"}`（`SceneMetrics.TransferResult`）；阈值与 for 沿用 |
| 11–13 | SceneManagerHomeZone* 三条 | **不适用** | 归属区就是 `player.zone_id`，与玩家行同一行、恒有值（`zone-travel-spec.md:52`）。trade 的归属区读取另设 A58 |
| 14 | SceneManagerZoneTravelMapUnavailable | **改写 → A68 / A69**（5.4） | 指标由 5.4 引入（`zone-travel-spec.md:899`、`:904`） |
| 15 | SceneManagerEnterSceneSceneGone | **改写 → A47 / A48** | 从调用方看 assign 回 `no_scene` / `error` 的占比（`SceneDirectoryProvider.AssignResult`） |
| 16 | SceneManagerEnterSceneRollbackRedisError | **改写 → A20**（另有 A19） | Java 没有「Kafka 推路由 + Redis 回滚」；对应的「状态不明」是放弃进场后释放归属失败 |
| 17 | SceneManagerNodeDetachWithoutDeathMark | **不适用 → 由 5.5 的 A70–A73 替代** | 迟到的写由 epoch 围栏拒掉（`scene-drain-spec.md:1027-1029`） |
| 18 | SceneManagerSceneOrphanReconcileSpike | **不适用**（A44 覆盖） | 死节点的频道进入 missing 后由领导者重铺，没有孤儿对账 |
| 19 | SceneManagerMirrorSourceMissing | **改写 → A65**（5.3） | `xm.scene.mirror.requests{result="bad_source"}`（`dungeon-mirror-spec.md:713`） |
| 20 | SceneManagerInstanceDestroyCascadeAnomaly | **改写 → A67**（5.3） | `xm.scene.instance.lifecycle{event}`；比值 10 沿用 |
| 21 | SceneManagerUnknownSceneNodeType | **不适用** | Java 不分节点角色 |

**合计**：改写 / 直译 / 合并 11 条（#1、#4、#5、#7、#8、#10、#14、#15、#16、#19、#20），不适用 10 条（#2、#3、#6、#9、#11、#12、#13、#17、#18、#21）。

§4.3 的文档告警：回档三条 → 7.2 的 A85–A88 与 A64；scene 三条 LogQL → A29 / A30（Java 的 epoch 恒有值，`owner_epoch unknown` 不存在）；帮会五条 → A52–A57；登录路径占比、角色名孤儿、排行榜 → 不适用
（Java 没有 legacy 登录路径；建角与名字同事务；排行榜未移植）；B.1 / E.1 → A13–A15、A3；data_service 的 `consumer_up` → A59。

### 4.6 Java 告警清单（逐条）

**约定**：
- 「批次」列：`7.6` = 指标已在代码里（含已提交的 5.2），本批落规则；其余 = 指标由该批引入，规则与 promtool 用例**随该批一起落**（Q49）。7.6 落地 A1–A64。
- 「未校准」= 没有可照搬的基线数字或基线也没校准过，先按保守值上线，7.4 压测后回填（Q48）。
- `zone` / `instance` / `job` 都是抓取目标标签（§5.13）。scene-manager 跨 zone 共用，进程不打 zone 标签（`scene-channels-spec.md:552`），A42–A48 是全舰队粒度。
- 表格里的 `\|` 是转义，规则文件里写 `|`。

| id | 告警 | 表达式 | for | 级别 | 批次 | 依据 |
|---|---|---|---|---|---|---|
| A1 | XmCoreTargetDown | `up{job=~"xm-(gateway\|login\|scene-manager\|gate\|scene)"} == 0` | 2m | critical | 7.6 | 基线没有存活告警（`scene-manager-alerts.yaml:47-51` 自承盲区） |
| A2 | XmTargetDown | `up{job=~"xm-.+", job!~"xm-(gateway\|login\|scene-manager\|gate\|scene)"} == 0` | 5m | warning | 7.6 | 同上 |
| A3 | XmProcessRestarting | `changes(process_start_time_seconds{job=~"xm-.+"}[30m]) >= 3` | 0m | warning | 7.6 | 对应 B.2 的 OOM / Killed；不依赖 kube-state-metrics |
| A4 | XmJvmLiveDataHigh | `jvm_gc_live_data_size_bytes{job=~"xm-.+"} / jvm_gc_max_data_size_bytes > 0.85` | 15m | warning | 7.6 | GC 后存活数据，比原始 used 稳（堆上限是 limit 的 60%） |
| A5 | XmGcTimeHigh | `sum by (job, instance)(rate(jvm_gc_pause_seconds_sum{job=~"xm-.+"}[5m])) > 0.1` | 10m | warning | 7.6 | — |
| A6 | XmSceneGcPauseOverFrame | `max by (zone, instance)(max_over_time(jvm_gc_pause_seconds_max{job="xm-scene"}[5m])) > 0.05` | 10m | warning | 7.6 | 50 ms 帧预算 |
| A7 | XmErrorLogBurst | `sum by (job, instance)(increase(logback_events_total{job=~"xm-.+", level="error"}[10m])) > 50` | 0m | warning | 7.6 | 替代基线 B.2 的关键字 grep；**未校准**（H30） |
| A8 | XmClockSkew | `max by (job, instance)(abs(xm_redis_clock_offset_seconds)) > 1` | 5m | warning | 7.6 | 新指标（J9）；租约比较用墙钟，Dubbo 鉴权窗 ±60 s（盘点隐患③，`java-infra.md:342`） |
| A9 | XmKillswitchSyncFailing | `sum by (job, instance)(increase(xm_killswitch_sync_failures_total[10m])) > 3` | 0m | warning | 7.6 | 失联 60 s 后整体放行（`xm-gate application.yaml:37-39` 注释） |
| A10 | XmKillswitchLeftOn | `max by (job)(xm_killswitch_rules) > 0` | 6h | info | 7.6 | 止血阀忘了撤 |
| A11 | XmLogShipperErrors | `sum by (instance)(rate(vector_component_errors_total[5m])) > 0` | 10m | warning | 7.6 | 日志送不出去时审计兜底行只靠 Vector 磁盘缓冲（H12） |
| A12 | XmScrapeLabelMismatch | `group by (job, application)(process_start_time_seconds{job=~"xm-.+"}) unless group by (job, application)(label_replace(process_start_time_seconds{job=~"xm-.+"}, "job", "$1", "application", "(.*)"))` | 0m | warning | 7.6 | 重标写错时按 `job` 写的规则全部变成空向量；两侧按 `(job, application)` 匹配（§0.6 #23）；进程公共标签 `application`（12 个 yaml 的 `management.metrics.tags.application`） |
| A13 | XmAssignGateServerErrors | `sum(rate(xm_gateway_assign_gate_total{code="500"}[5m])) / (sum(rate(xm_gateway_assign_gate_total[5m])) > 0) > 0.05` | 5m | critical | 7.6 | B.1「成功率 95% 持续 5 分钟」；**503 是区服状态、100 / 429 是排队与限流，都不算**（§0.6 #6） |
| A14 | XmHttpLoginServerErrors | `sum(rate(xm_gateway_login_total{code="500"}[5m])) / (sum(rate(xm_gateway_login_total[5m])) > 0) > 0.05` | 5m | critical | 7.6 | `CODE_INTERNAL` 已预注册（`LoginHttpMetrics.java:22-31`） |
| A15 | XmLoginLatencyP99High | `histogram_quantile(0.99, sum by (le)(rate(xm_login_requests_seconds_bucket{method="Login"}[5m]))) > 0.5` | 5m | warning | 7.6 | B.1 p99 >500 ms（基线 for 1m，放宽降噪） |
| A16 | XmLoginOverloaded | `sum(rate(xm_login_requests_seconds_count{result="overloaded"}[5m])) > 0` | 5m | warning | 7.6 | 工作队列满，玩家收 1003 |
| A17 | XmLoginInternalErrors | `sum(rate(xm_login_requests_seconds_count{result="internal_error"}[5m])) / (sum(rate(xm_login_requests_seconds_count[5m])) > 0) > 0.01` | 10m | warning | 7.6 | — |
| A18 | XmOwnerClaimTimeouts | `sum(rate(xm_login_owner_claims_seconds_count{outcome="timeout"}[5m])) > 0.05` | 10m | warning | 7.6 | 玩家收 2005 |
| A19 | XmOwnerClaimErrors | `sum(increase(xm_login_owner_claims_seconds_count{outcome="error"}[10m])) > 0` | 0m | warning | 7.6 | `ClaimOutcome.ERROR` 已预注册 |
| A20 | XmAbandonedEnterReleaseFailed | `sum(increase(xm_login_abandoned_enters_total{result="failed"}[10m])) > 0` | 0m | warning | 7.6 | ≈ 基线 #16 |
| A21 | XmGateHandshakeRejects | `sum by (zone)(rate(xm_gate_handshakes_total{result=~"bad_signature\|expired\|wrong_zone"}[5m])) / (sum by (zone)(rate(xm_gate_handshakes_total[5m])) > 0) > 0.2` | 10m | warning | 7.6 | 密钥轮换不同步、时钟漂移（`GateMetrics.HandshakeResult`） |
| A22 | XmGateWrongGateHandshakes | `sum by (zone)(rate(xm_gate_handshakes_total{result="wrong_gate"}[5m])) > 0.1` | 10m | warning | 7.6 | 多个 gate 共用了对外地址（§5.5 硬约束、H25） |
| A23 | XmGateLinkConnectFailing | `sum by (zone)(rate(xm_gate_link_events_total{event="connect_failed"}[5m])) > 0.1` | 10m | warning | 7.6 | 链路密钥不一致、NetworkPolicy 拦了；**不用「链路数 == 0」**：链路按需建（`SceneLinkManager.java:85`） |
| A24 | XmGateSceneLinkUnavailable | `sum by (zone)(rate(xm_gate_client_requests_total{result="link_unavailable"}[5m])) > 0.1` | 5m | critical | 7.6 | 玩家请求到不了 scene；**未校准** |
| A25 | XmZoneGateWithoutScene | `count by (zone)(up{job="xm-gate"} == 1) unless on (zone) count by (zone)(up{job="xm-scene"} == 1)` | 2m | critical | 7.6 | ≈ 基线 #4 的「整 zone 全灭」分支；开区 / 维护前 silence |
| A26 | XmGateLinkDrops | `sum by (zone, instance)(rate(xm_gate_link_dropped_total{reason=~"queue_full\|link_failed"}[5m])) > 1` | 10m | warning | 7.6 | `GateMetrics.LinkDrop` |
| A27 | XmSceneTickOverBudget | `1 - (sum by (zone, instance)(rate(xm_scene_tick_seconds_bucket{le="0.05"}[5m])) / sum by (zone, instance)(rate(xm_scene_tick_seconds_count[5m]))) > 0.05` | 10m | warning | 7.6 | 50 ms 是 SLO 桶（`SceneMetrics.java:89-92`）；超预算帧占比 > 5% |
| A28 | XmSceneLogicWaitHigh | 同 A27 的写法，`xm_scene_logic_task_wait_seconds`，`le="0.25"`，阈值 0.05 | 5m | warning | 7.6 | 逻辑线程排队 |
| A29 | XmScenePlayerWriteLost | `sum by (zone, instance)(increase(xm_scene_storage_writes_seconds_count{op=~"save\|release\|progress", result=~"failed\|rejected"}[10m])) > 0` | 0m | **critical** | 7.6 | 这两个结局代码写明「写丢失，已记 ERROR 待人工修复」（`SceneMetrics.WriteResult`）；handoff / probe 的 failed 是「结局不明」，排除 |
| A30 | XmSceneWriteFenced | `sum by (zone)(increase(xm_scene_storage_writes_seconds_count{op=~"save\|release\|progress", result="fenced"}[10m])) > 0` | 0m | warning | 7.6 | ≈ 基线 #1；压测若显示是常态，降为 info |
| A31 | XmPeriodicSaveDeferred | `sum by (zone, instance)(rate(xm_scene_periodic_saves_total{result="deferred"}[10m])) > 0` | 15m | warning | 7.6 | 存储积压（`SceneMetrics.PeriodicSave.DEFERRED`） |
| A32 | XmSceneStorageBacklog | `max by (zone, instance)(executor_queued_tasks{job="xm-scene", name="scene-storage"} / (executor_queued_tasks{job="xm-scene", name="scene-storage"} + executor_queue_remaining_tasks{job="xm-scene", name="scene-storage"})) > 0.5` | 5m | warning | 7.6 | 存储池半满，再满就 REJECTED（写丢失）；指标 `SceneMetrics.java:547-552` |
| A33 | XmSceneLinkBackpressure | `sum by (zone, instance)(rate(xm_scene_link_backpressure_pauses_total[5m])) > 0.1` | 10m | warning | 7.6 | 盘点点名「gate 链路背压」（`java-infra.md:363`）；**未校准** |
| A34 | XmSceneAuditUndelivered | `sum by (zone, kind, result)(increase(xm_scene_audit_records_total{result!="acked"}[10m])) > 0` | 0m | warning | 7.6 | 记录进了兜底日志，要回灌（§5.12） |
| A35 | XmGainBlockSyncStale | `max by (zone, instance)(xm_scene_gain_block_sync_age_seconds) > 60` | 2m | warning | 7.6 | 同步周期 10 s（`xm-scene application.yaml:99`） |
| A36 | XmGainAnomalyDetected | `sum by (zone)(increase(xm_scene_gain_anomalies_total[10m])) > 0 or sum by (zone)(xm_scene_gain_anomalies_total unless xm_scene_gain_anomalies_total offset 10m) > 0` | 0m | warning | 7.6 | 动态标签、按需注册，用「新序列」写法补第一次（§4.8 第 3 条）；玩家号在 `xm.audit.anomaly` 日志 |
| A37 | XmSceneAssetOpErrors | `sum by (zone)(rate(xm_scene_asset_ops_total{outcome=~"error\|overloaded"}[5m])) > 0` | 10m | warning | 7.6 | 调用方会重投，持续出现才告 |
| A38 | XmZoneNoActiveChannel | `sum by (zone)(xm_scene_channels{state="active"}) == 0` | 2m | critical | 7.6 | ≈ 基线 #4 的「有节点无承载」分支（`SceneMetrics.java:490-496`） |
| A39 | XmScenePlayersHigh | `sum by (zone, instance)(xm_scene_players) > 1800` | 10m | warning | 7.6 | ≈ 基线 #5；**未校准** |
| A40 | XmChannelPlanRejected | `sum by (zone)(rate(xm_scene_channel_plan_applies_total{result="rejected"}[5m])) > 0` | 10m | warning | 7.6 | — |
| A41 | XmChannelPlanPollFailing | `sum by (zone, instance)(rate(xm_scene_channel_plan_poll_failures_total[5m])) > 0` | 5m | warning | 7.6 | — |
| A42 | XmZoneWithoutSceneNodes | `sum(rate(xm_scene_manager_world_ticks_total{result="no_nodes"}[2m])) > 0` | 2m | warning | 7.6 | `xm:world:zones` 里有 zone 没有活节点：scene 全挂，或退役 zone 没 SREM（`RedisKeys.worldZones` 注释）。玩家可见的情形由 A25 / A38 按 zone 报 critical |
| A43 | XmWorldLeaderGap | `count(count by (zone)(up{job="xm-scene"} == 1)) > sum(xm_scene_manager_world_leader_zones)` | 2m | warning | 7.6 | 有活 scene 的 zone 没人领导 |
| A44 | XmWorldChannelsMissing | `sum(xm_scene_manager_world_channels{state="missing"}) > 0` | 5m | warning | 7.6 | ≈ 基线 #7 / #18 |
| A45 | XmRebalanceNodeGonePending | `max(xm_scene_manager_rebalance_pending{reason="node_gone"}) > 0` | 5m | warning | 7.6 | 基线 #7 直译 |
| A46 | XmWorldTickFaults | `sum(rate(xm_scene_manager_world_ticks_total{result=~"error\|fenced\|conflict\|no_lease"}[5m])) / (sum(rate(xm_scene_manager_world_ticks_total{result!="not_leader"}[5m])) > 0) > 0.5` | 10m | warning | 7.6 | ≈ 基线 #8 |
| A47 | XmSceneAssignFailing | `sum(rate(xm_scene_manager_assign_seconds_count{result=~"error\|no_scene"}[5m])) / (sum(rate(xm_scene_manager_assign_seconds_count[5m])) > 0) > 0.05` | 10m | warning | 7.6 | ≈ 基线 #15 |
| A48 | XmSceneAssignErrors | 同上，只算 `result="error"`，阈值 0.5 | 5m | critical | 7.6 | 场景目录不可读 |
| A49 | XmServiceRequestFaults | `sum by (job)(rate({__name__=~"xm_(friend\|chat\|team\|guild\|trade)_requests_seconds_count", result=~"internal_error\|overloaded"}[5m])) / (sum by (job)(rate({__name__=~"xm_(friend\|chat\|team\|guild\|trade)_requests_seconds_count"}[5m])) > 0) > 0.05` | 10m | warning | 7.6 | chat 没有 overloaded 结局，正则照样适用 |
| A50 | XmFriendQuotaFailOpen | `sum(increase(xm_friend_request_quota_total{outcome="error"}[10m])) > 0` | 0m | warning | 7.6 | architecture §11 写「fail-open 的唯一信号，须配告警」 |
| A51 | XmFriendCountUnderflow | `sum(increase(xm_friend_count_underflows_total[1h])) > 0` | 0m | warning | 7.6 | 不变量被破坏 |
| A52 | XmGuildTxDeadlocks | `sum(increase(xm_guild_tx_deadlocks_total[10m])) > 0` | 0m | warning | 7.6 | `92-handoff.md:967`；计数含重试，**未校准** |
| A53 | XmGuildAssetOpStuck | `max by (stream)(xm_guild_assetop_pending_oldest_age_seconds) > 86400` | 0m | warning | 7.6 | 基线建议阈值 |
| A54 | XmGuildAssetOpUnknown | `sum(increase(xm_guild_assetop_unknown_total[10m])) > 0` | 0m | warning | 7.6 | 同上 |
| A55 | XmGuildAssetOpPartial | `sum(increase(xm_guild_assetop_partial_total[1h])) > 0` | 0m | warning | 7.6 | 部分发放，要人工补偿 |
| A56 | XmGuildCacheInvalidationFailing | `sum(increase(xm_guild_cache_invalidation_failures_total[1h])) > 0` | 30m | warning | 7.6 | 「持续上升」 |
| A57 | XmGuildAssetOrphans | `sum(increase(xm_guild_asset_orphans_total[1h])) > 0` | 30m | warning | 7.6 | 同上 |
| A58 | XmTradeHomeZoneLookupErrors | `sum(rate(xm_trade_home_zone_lookups_total{result="error"}[5m])) > 0.05` | 10m | warning | 7.6 | 最接近基线 #11–#13 的 Java 依赖 |
| A59 | XmAuditConsumerDown | `min by (consumer)(xm_data_kafka_consumer_up) == 0` | 5m | critical | 7.6 | 基线注释写「必须告警」却没有规则（`metrics.go:92-94`）；Java 消息留在 Kafka（保留 30 天），超期才真丢 |
| A60 | XmAuditConsumerLag | `max by (consumer)(xm_data_kafka_consumer_lag) > 10000` | 15m | warning | 7.6 | **未校准** |
| A61 | XmAuditRecordsSkipped | `sum by (consumer, outcome)(increase(xm_data_kafka_records_total{outcome=~"decode_error\|invalid"}[10m])) > 0` | 0m | warning | 7.6 | 毒丸进 `xm.audit.poison` |
| A62 | XmAuditDbInsertErrors | `sum by (consumer)(increase(xm_data_db_insert_errors_total[10m])) > 0` | 10m | warning | 7.6 | `DataMetrics` |
| A63 | XmTransferPostFreezeMutation | `sum(increase(xm_scene_transfer_post_freeze_mutations_total[10m])) > 0` | 0m | warning | 7.6（5.2 指标） | 应恒为 0（`scene-handoff-spec.md:1083`） |
| A64 | XmTransferAnomaly | `sum by (zone, result)(rate(xm_scene_transfers_total{result=~"lost_unknown\|fenced\|lease_too_short"}[5m])) > 0.05` | 15m | warning | 7.6（5.2 指标） | ≈ 基线 #10，阈值与 for 沿用 |
| A65 | XmMirrorBadSource | `sum(rate(xm_scene_mirror_requests_total{result="bad_source"}[5m])) > 0.01` | 10m | warning | 5.3 | 基线 #19 |
| A66 | XmMirrorRequestsRejected | `sum(rate(xm_scene_mirror_requests_total{result=~"not_accepting\|node_cap"}[5m])) > 0.05` | 10m | warning | 5.3 | 最接近基线 #3 |
| A67 | XmInstanceCascadeAnomaly | cascade / 非 cascade 销毁 > 10（`xm_scene_instance_lifecycle_total{event=~"destroyed_.*"}`） | 15m | warning | 5.3 | 基线 #20；事件名以 5.3 落码为准 |
| A68 | XmZoneTravelNoMap | `sum(rate(xm_scene_manager_travel_seconds_count{result="no_map"}[5m])) > 0.01` | 10m | warning | 5.4 | 基线 #14 |
| A69 | XmTravelPlacementFailed | `sum(increase(xm_scene_travel_placements_total{result="failed"}[10m])) > 0` | 0m | warning | 5.4 | — |
| A70 | XmSceneNodeIdentityTaken | `max by (zone, instance)(xm_scene_node_identity) == 2` | 0m | critical | 5.5 | `scene-drain-spec.md:1063-1065` |
| A71 | XmSceneNodeSuspended | `max by (zone, instance)(xm_scene_node_identity) == 1` | 30s | warning | 5.5 | 同上 |
| A72 | XmEvacuationBudgetExceeded | `sum(increase(xm_scene_node_evacuations_total{result="budget_exceeded"}[30m])) > 0` | 0m | warning | 5.5 | 同上 |
| A73 | XmWorldNodeStuck | `max(xm_scene_manager_world_nodes{state="stuck"}) > 0` | 5m | warning | 5.5 | 同上 |
| A74–A76 | XmRelocationDeferredHigh、XmRelocationStorageGateClosed、XmSceneLinkIdleTimeoutSpike | `xm_scene_channel_relocations_total{result=~"deferred.*"}`、`{result="deferred_storage"}` 持续增长、`xm_scene_link_idle_closes_total` 突增 | — | warning | 5.5 | 阈值由 5.5 落码时定 |
| A77 | XmBattleLeaseLost | `sum(increase(xm_battle_lease_lost_total[10m])) > 0` | 0m | warning | 6.2 | 关闸不作废房间，房间归零后要重启（`BattleNode.java:287`） |
| A78 | XmBattleFingerprintMismatch | `sum(increase(xm_battle_fingerprint_mismatch_total[10m])) > 0` | 0m | critical | 6.2 | scene 与 battle 配表不同版本，结算不可信 |
| A79 | 6.3 结算九条 | `not_durable`、`exhausted`（有目标）、`expired`、`ledger_evictions`、`drop_lost`、`pending_corrupt` 增量 > 0；`frozen{state=fighting}` 长时间不降；`recovery{retry}` 持续增长；`settlements{result=deferred_currency}` 持续出现 | — | warning | 6.3 | `scene-battle-spec.md:1067-1068` |
| A80–A83 | 匹配四条 | `xm_match_rating_consumer_paused == 1` 持续 10m；`xm_match_table_fingerprint_mismatches_total` 增长；`xm_match_matcher_rounds_total{result=~"paused_.*"}` 持续 10m；battle 池为空（critical） | — | 见左 | 6.4 | `match-spec.md:575`、`:1023` |
| A84 | 预留 | 6.5 观战 / 跨区 1V1 的告警由 6.5 规格定义 | — | — | 6.5 | `spectate-spec.md` |
| A85–A88 | 运维作业四条 | `xm_data_ops_fence_held > 0` 超过作业时限；divergence `check_failed\|truncated\|post_write_.*` 增长；`jobs_total{outcome="diverged_after_write"}` > 0（**critical**）；`interrupted`、`claims_total{outcome="timeout"}` 突增 | — | 见左 | 7.2b | `data-ops-spec.md:504`、`:951-956` |

### 4.7 Java 看板

全部放 `deploy/observability/grafana/dashboards/`，数据源用模板变量 `DS_PROMETHEUS` / `DS_LOKI`（能导入外部 Grafana；基线日志看板把 uid 写死，这点不照抄）；变量 `$zone = label_values(up{job="xm-scene"}, zone)`、
`$instance`；K8s 下以带 `grafana_dashboard: "1"` 标签的 ConfigMap 提供（同基线 `scene-manager-dashboard.json:2` 的约定），compose 走 provisioning；`allowUiUpdates: false`。

| uid | 对标基线 | 内容（只用已存在的指标） |
|---|---|---|
| `xm-overview` | — | `up` 按 job；重启次数；存活数据占比与 GC 时间；`logback_events_total{level=~"warn\|error"}`；在线人数 `sum(xm_gate_sessions_active)`、`sum by (zone)(xm_scene_players)`；正在响的 `ALERTS`；job / application 一致性（A12） |
| `xm-entry` | `grafana-login-path-deprecation.json`（只取结构） | assign-gate 按 code / reason；HTTP 登录按 endpoint / code；login 按方法 p50 / p99；归属夺取结局；排队放行；gate 握手、请求去向、断开原因、链路事件 |
| `xm-scene` | — | tick 超预算占比（50 ms 参考线）；逻辑队列与等待；存储写结局、存储池占用（A32）、周期存盘；审计结局；交出（5.2）；链路帧、丢弃与背压；频道（active / draining）与改派；封禁名单同步滞后 |
| `xm-scene-manager` | `scene-manager-dashboard.json` 的 Pool health / Rebalance 两行 | 频道按 scene_config / state；世界 tick 按 result；leader zones；rebalance；autoscale；assign 结局与 p99 |
| `xm-services` | — | 好友 / 聊天 / 组队 / 帮会 / 交易的请求结局与 p99、推送结局；帮会资产指令积压；审计消费 up / lag / 结局 / 入库错误与耗时；保留期删除；killswitch |
| `xm-logs`（Loki） | `game-logs-overview.json` 的 7 个面板 | 变量改为 service / zone / level / search；另加审计流面板 `{stream_class="audit"}` |
| `xm-battle-match` | — | 6.2–6.4 落地后加 |

### 4.8 规则工程纪律（对应基线 §4.4 的教训）

1. **每条规则都有 promtool 单测**：`deploy/observability/prometheus/tests/*.test.yaml` 至少一个「刚过阈值触发」和一个「刚不到不触发」的用例；CI 守卫：规则里的 alertname 集合 = 用例覆盖的集合。示例：

   ```yaml
   # deploy/observability/prometheus/tests/xm-zone.test.yaml
   rule_files: [../rules/xm-zone.rules.yaml]
   evaluation_interval: 30s
   tests:
     - interval: 30s
       input_series:
         - series: 'up{job="xm-gate",zone="2",instance="xm-gate-z2-0"}'
           values: '1x10'
         - series: 'up{job="xm-scene",zone="2",instance="xm-scene-z2-abc"}'
           values: '1 1 0x8'
       alert_rule_test:
         - eval_time: 4m
           alertname: XmZoneGateWithoutScene
           exp_alerts: [{exp_labels: {severity: critical, zone: "2"}}]
         - eval_time: 1m
           alertname: XmZoneGateWithoutScene
           exp_alerts: []
   ```

2. **指标目录守卫**（防「恒空告警」）：每个进程模块一个 `MetricsCatalogTest`，把本模块的 `*Metrics` 注册进 `PrometheusMeterRegistry`、抓一次，写出 `target/metrics-catalog.txt`（名字 + 启动即存在的标签组合）。
   `xm-ops obs check` 抽出规则与看板里所有 `xm_[a-z0-9_]+` 与引用的 `job`，逐个对照各模块目录与进程清单；JVM / 进程 / logback / executor 等 Actuator 自带指标走白名单。本机就能跑。
3. **「首次发生」**：`increase(...) > 0` 指向的固定标签组合必须启动即注册为 0（目录守卫检查）。带动态标签、无法预注册的（`xm_scene_gain_anomalies_total`、`xm_scene_gain_blocked_total`、`xm_killswitch_blocked_total`），
   规则一律补「新序列」分支 `x unless x offset <窗口>`（A36 的写法）。`xm_gateway_assign_gate_total` 已知组合全部预注册（§0.6 #6），比值型告警不受影响。
4. **新增指标的批次同批提交规则与 promtool 用例**（Q49）。7.6 只提交 7.6 提交时已存在指标的规则（A1–A64）。
5. **每条规则带** `severity`、`runbook_url: docs/ops/alerts.md#<告警名>`；级别口径同基线。

---

## 5 Java 设计

### 5.1 拓扑：进程归属对照与不移植项

| 基线 | 基线范围 | Java | Java 范围 | 说明 |
|---|---|---|---|---|
| C++ gate | zone | xm-gate | zone | StatefulSet |
| C++ scene（单池 / world / instance） | zone | xm-scene | zone | 单池 Deployment；不移植用途分池（`dungeon-mirror-spec.md:922`） |
| Go login | zone | xm-login | **全局** | 依赖 X16（`zone-travel-spec.md:828`、`:1020`） |
| Go scene-manager | zone | xm-scene-manager | **全局** | 按 zone 竞选领导者（`WorldChannelControlPlane`、`RedisKeys.worldZones`） |
| Java gateway | zone | xm-gateway | **全局**，一个 Ingress | 区服目录在库里；修 B13 |
| Go db（db_task 消费者） | zone | — | — | scene 直接写 MySQL，写入带 owner_epoch 围栏 |
| Go player-locator | zone | — | — | 在线目录与位置记录在 Redis（xm-discovery） |
| Go data-service | zone | xm-data | 全局 | 单副本 + Recreate（`data-ops-spec.md:143`） |
| Go match / chat / friend / trade | 全局 | xm-match（6.4）/ xm-chat / xm-friend / xm-trade | 全局 | — |
| guild、team（基线 K8s 不部署，B11） | — | xm-guild / xm-team | 全局，**要部署** | 玩家可见功能 |
| Go client-rpc-router | 全局 | — | — | gate 按服务语义经 Dubbo 直接路由（architecture §1） |
| C++ battle 池 | 全局 | xm-battle（6.2） | 全局 | StatefulSet |
| Kafka / Redis / MySQL | infra | 同 | infra（只 dev / CI / 演示） | 镜像与 `deploy/compose/infra.yaml` 同一 digest，CI 守卫逐字比对文件 |

**不移植**（都不是客户端可见）：

| 基线项 | 理由 |
|---|---|
| etcd | Java 的节点号、租约、目录都在 Redis（architecture §6）；ContractSync 也排除 `proto/etcd` |
| kafka-topic-init | broker 关自动建 topic，审计 topic 由生产方与消费方按规格自建并核对（`AuditTopicInitializer`；`deploy-ci-spec.md:301-303`） |
| redis-match-cluster | Java match 用单实例 Redis + 一个 hash tag（`match-spec.md:994`、`:1261`） |
| 每 zone 一个库、migrate Job | Java 只有一个库 `xm_java`，行按 zone_id 区分；建表在进程启动时做，pbmysql 整轮持 `GET_LOCK`（architecture §7），并发风险见 H9 |
| mysqldump CronJob | infra 只给 dev / CI / 演示；生产用带 PITR 的托管 MySQL（Q22） |
| C++ 日志 sidecar | Java 只写 stdout，节点级采集 |
| Agones（Fleet、FleetAutoscaler、SDK RBAC、计数） | 5.1 D14（`scene-channels-spec.md:1077`）、6.2 N4（`battle-node-spec.md:1464`）；agones 7.1K 不满足 §2。scene 与 battle 是常驻进程，有自己的租约、目录和准入 |
| gate-entry 单一 Service | 令牌绑 gate（握手有 `wrong_gate`），同基线 D90 |
| `centre`、Go gateway 清单、auth 条目 | 基线遗留物（B18） |
| 仓库外制品根、fetch / import / retention 脚本（约 1.3K 行 PowerShell） | 分发走 registry + 短期 Actions 制品；没有隔离网络的目标机（Q33） |
| 整区回档里的 `FLUSHDB` | Java 各 zone 共用一个 Redis 键空间（`xm:` 前缀），`FLUSHDB` 会清掉所有区，还会让 `xm:node-id-epoch:*` 回退（`tools.md:444`）；按区清理归 7.2b / 7.3 的工具 |

**Agones 能力在 Java 里的替代**：分配许可 → scene-manager 的频道计划与软预占（5.1）、battle 准入闸（6.2）；排空标签 → gate 排空标记（3.5）、scene 停服前疏散（5.5）、battle 运维排空（J6）；
loop 心跳停发 health → `logicLoop` liveness（J5）；FleetAutoscaler → 不做（Q19），频道数由 scene-manager 管。

### 5.2 namespace、chart 与 release

```
<ns>-infra   （缺省 xuanming-java-infra；只给 dev / CI / 演示）
  release xm-infra     chart deploy/helm/xm-infra      mysql / redis / kafka（StatefulSet + PVC）
<ns>         （缺省 xuanming-java；一个环境一个）
  release xm-platform  chart deploy/helm/xm-platform   xm-gateway xm-login xm-scene-manager xm-friend xm-chat xm-team xm-guild
                                                       xm-trade xm-data xm-battle [xm-match]，NetworkPolicy、PDB、可选 Ingress / PodMonitor / 规则包装
  release xm-zone-<id> chart deploy/helm/xm-zone       xm-gate-z<id>（StatefulSet + headless + 每序号 Service + PDB）、xm-scene-z<id>（Deployment + PDB）

deploy/helm/
  xm-lib/       type: library。Java 进程 Pod 模板契约（§5.4），所有 Java 工作负载只能经它生成
  xm-infra/ xm-platform/ xm-zone/   各带 values.schema.json
  values/       ci.yaml、prod.example.yaml（不含任何秘密）
  zones/        zone-<id>.yaml（文件名里的 id 必须等于文件内 zoneId，xm-ops 校验）
```

- **一个环境 = 一个应用 namespace = 一套 Redis / MySQL 库 / Kafka**。节点号租约与防护代次的键里没有环境段（`tools.md:416`），基线靠 `ClusterId` 区分（`k8s_deploy.ps1:13-22`），Java 靠物理隔离（Q54）。
- **全局服务和各 zone 同一个 namespace**：按 zone 部署的只有 gate / scene；Secret 只存一份、Service 用短名（与 compose 同名，环境变量两边一致）、NetworkPolicy 简单。
- **infra 单独一个 namespace**：误删应用 namespace 不会带走 PVC；NetworkPolicy 只放行来自应用 namespace 的流量。
- **每个 zone 一个 release**：开关一个 zone 不碰别的 zone；`helm history` / `helm rollback` 按 zone 粒度。配置全部入库（values + zones 文件），从根上消灭基线「参数不粘滞」（B17）。
- **标签**：`app.kubernetes.io/{name,instance,part-of=xuanming-java,version}`；zone 级资源另加 `xm/zone: "<id>"`（Prometheus 里是 `__meta_kubernetes_pod_label_xm_zone`）。

### 5.3 服务发现

- 每个 Dubbo 调用方用直连 URL 指向 Service 名：`tri://xm-login:20881`、`tri://xm-scene-manager:20882`、`tri://xm-friend:20883` … `tri://xm-match:20888`，就是 7.1 §6.4 那组 `XM_*_URL` 占位符。
- 只做直连的提供方（login、scene-manager、社交五服、match）**不设 `XM_ADVERTISE_HOST`**：保持缺省 127.0.0.1 时 Dubbo 把它当无效绑定、监听 0.0.0.0（`deploy-ci-spec.md:442-447`），Service 与 port-forward 都能连；入站由 NetworkPolicy 收口。
- 按节点直连的不经 Service：scene 的 `XM_ADVERTISE_HOST=$(POD_IP)`（链路 / 资产通道地址写进目录；资产通道因此只绑 Pod IP，H1），battle 的目录 `rpc_host=$(POD_IP)`。
- **不部署 Nacos**：xm-scene 的 `scene-manager-url` 本来只支持直连（7.1 Q7，`xm-scene application.yaml:107-111`）；少一个有状态组件；kube-proxy 已提供 L4 均衡与故障转移；Dubbo 每次调用都有 HMAC 鉴权。
- **代价**：Triple 跑在 HTTP/2 长连接上，只能按连接均衡，Dubbo 层的优雅下线通知也收不到（H14）。tech-stack 补一句「K8s 部署不用 Nacos，Service DNS 即发现；Nacos 只留给非 K8s 的多机部署」。

### 5.4 工作负载规格

**Pod 模板契约**（`xm-lib` 统一生成；以 xm-gate-z1 为例，细节以落码为准）：

```yaml
metadata:
  labels: {app.kubernetes.io/name: xm-gate, app.kubernetes.io/part-of: xuanming-java, xm/zone: "1"}
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "18103"                 # 由 values 的 managementPort 生成，与探针、env 同源（§0.6 #24）
    prometheus.io/path: /actuator/prometheus
    xm/secrets-revision: "<Secret 的 resourceVersion>"   # 只放版本号，不放任何由秘密内容算出的值
spec:
  enableServiceLinks: false                     # §0.6 #22：K8s 链接变量会落进 XM_ 前缀
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 40             # gate；其余见下表，scene 由模板按 C2 求下限
  securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, fsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: xm-gate
      image: ghcr.io/luyuan-java/xm-gate@sha256:<digest>      # xm-ops deploy images 从发布 manifest + digest 文件生成
      imagePullPolicy: IfNotPresent
      securityContext: {readOnlyRootFilesystem: true, allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
      env:
        - {name: XM_RUN_MODE, value: prod}
        - {name: XM_MANAGEMENT_ADDRESS, value: 0.0.0.0}
        - {name: XM_ZONE_ID, value: "1"}                      # J3 新占位符
        - {name: XM_LOG_FORMAT, value: logstash}
        - {name: POD_IP, valueFrom: {fieldRef: {fieldPath: status.podIP}}}
        - {name: XM_POD_NAME, valueFrom: {fieldRef: {fieldPath: metadata.name}}}
        - {name: JDK_JAVA_OPTIONS, value: "-XX:MaxRAMPercentage=60 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=2"}
        - {name: XM_DUBBO_SECRET, valueFrom: {secretKeyRef: {name: xm-secrets, key: XM_DUBBO_SECRET}}}     # 必需键不写 optional
        - {name: XM_GM_ADMIN_SECRET, valueFrom: {secretKeyRef: {name: xm-secrets, key: XM_GM_ADMIN_SECRET, optional: true}}}
      ports: [{name: client, containerPort: 11000}, {name: mgmt, containerPort: 18103}]
      startupProbe:   {httpGet: {path: /actuator/health/liveness,  port: mgmt}, periodSeconds: 5, failureThreshold: 36}
      livenessProbe:  {httpGet: {path: /actuator/health/liveness,  port: mgmt}, periodSeconds: 10, timeoutSeconds: 3, failureThreshold: 3}
      readinessProbe: {httpGet: {path: /actuator/health/readiness, port: mgmt}, periodSeconds: 5, failureThreshold: 3}
      resources: {requests: {cpu: 500m, memory: 1Gi}, limits: {memory: 1Gi}}   # memory limit 用 required，缺了渲染失败
      volumeMounts: [{name: tmp, mountPath: /tmp}, {name: home, mountPath: /home/xm}]
  volumes: [{name: tmp, emptyDir: {}}, {name: home, emptyDir: {}}]
```

**共同约定**：
- **镜像**：生产用 `ghcr.io/luyuan-java/<模块>@sha256:…`；也接受不可变 tag；CI 用本地导入的 `xuanming-java/<模块>:<sha12>`。
- **探针**（依赖 7.1b 打开 `management.endpoint.health.probes.enabled`）：startup 打 liveness 分组，5 s × 36 = 180 s，覆盖表加载与 Dubbo 导出（不照搬基线的 300 s，§0.6 #21）；
  分组只含 `livenessState` / `readinessState` 加本进程的领域指示器（§5.6），**不含 db / redis**（理由同基线 `java-svc/gateway.yaml:63-68`）。
- **管理端口** `XM_MANAGEMENT_ADDRESS=0.0.0.0`（§0.3）。模板不设 `SERVER_PORT`（§0.6 #24）。
- **资源**：memory request = limit，模板对 limit 用 `required`（H2）；CPU 只写 request。`JDK_JAVA_OPTIONS` 由模板拼 `-XX:ActiveProcessorCount=<max(2, ceil(CPU request))>`（不写 CPU limit 时 JVM 会按节点核数开线程池）。
- **调度**：`topologySpreadConstraints` maxSkew 1、`kubernetes.io/hostname`、`ScheduleAnyway`（相当于基线 preferred 反亲和，单节点 CI 也能调度）；`priorityClassName` 留值、缺省空（Q23）。
- **只读根文件系统**：镜像 `/app` 归 root，运行时可写的只有 `/tmp` 与 `/home/xm`（Tomcat 工作目录、Netty native 库、hsperfdata、Dubbo 文件缓存，`deploy-ci-spec.md:568-574`）；没有进程写文件日志。
- **停机**：有 ClusterIP 流量的提供方（gateway、Dubbo 提供方）加 `preStop: exec sleep 5`；宽限期 = preStop + `timeout-per-shutdown-phase` + 10 s（§0.6 #30）。scene / gate / battle / data 不加 preStop。

| 工作负载 | release / kind | 副本 CI / 生产起点 | 更新策略 | 端口与 Service | PDB | 宽限 / preStop | 内存 生产 / CI |
|---|---|---|---|---|---|---|---|
| xm-gateway | platform / Deployment | 1 / 2 | RollingUpdate maxSurge 1、maxUnavailable 0 | 18081 → `xm-gateway`（ClusterIP；可选 Ingress，只路由 `/api`）；管理 18105 | maxUnavailable 1 | 25 s / 5 s | 768Mi / 512Mi |
| xm-login | platform / Deployment | 1 / 2 | 同上 | 20881 → `xm-login`；18101 | maxUnavailable 1 | 35 s / 5 s | 768Mi / 512Mi |
| xm-scene-manager | platform / Deployment | 1 / 2 | 同上 | 20882 → `xm-scene-manager`；18102 | maxUnavailable 1 | 35 s / 5 s | 768Mi / 512Mi |
| xm-friend / chat / team / guild / trade | platform / Deployment | 各 1 / 各 2 | 同上 | 20883–20887 → 同名 Service；18107–18111 | maxUnavailable 1 | 35 s / 5 s | 768Mi / 512Mi |
| xm-data | platform / Deployment | 1 / 1 | **Recreate**（`data-ops-spec.md:143`） | 18106 → `xm-data`（ClusterIP，运维经 port-forward） | 无 | 30 s / 无 | 1Gi / 640Mi |
| xm-battle（6.2） | platform / StatefulSet，Parallel | 1 / 2 | **OnDelete**（battle-roll） | 12000 → 每序号 Service `xm-battle-<i>`（`publishNotReadyAddresses`）；21200 走 Pod IP；18112 | **maxUnavailable 0** | 30 s / 无 | 1Gi / 512Mi |
| xm-match（6.4） | platform / Deployment | 1 / 2 | RollingUpdate | 20888 → `xm-match`；18113（`match-spec.md:916`） | maxUnavailable 1 | 35 s / 5 s | 768Mi / 512Mi |
| xm-gate-z<id> | zone / StatefulSet，Parallel | z1 2、z2 1 / 2 | **OnDelete**（gate-roll） | 11000 → headless + 每序号 Service `xm-gate-z<id>-<i>`（`publishNotReadyAddresses`）；18103 | **maxUnavailable 0**（Q12） | 40 s / 无 | 1Gi / 512Mi |
| xm-scene-z<id> | zone / Deployment | z1 2、z2 1 / 2 | **maxSurge 100%、maxUnavailable 0**，minReadySeconds 10，progressDeadlineSeconds 600 | 链路 21000 / 资产通道 21100 走 Pod IP；18104；**不建 Service** | **maxUnavailable 1** | **≥ 60 s，按公式算**（J7） | 2Gi / 768Mi |
| mysql | infra / StatefulSet + PVC | 1 / —（生产托管） | RollingUpdate | 3306 → `mysql` | minAvailable 1 | 60 s | — / 768Mi |
| redis | infra / StatefulSet + PVC | 1 / — | 同上 | 6379 → `redis` | minAvailable 1 | 30 s | — / 256Mi |
| kafka | infra / StatefulSet + PVC，`fsGroup` | 1 / — | 同上 | 9092 → `kafka`（通告 headless FQDN） | minAvailable 1 | 60 s | — / 1Gi |

**infra 的补充约定**（只 dev / CI / 演示）：
- MySQL readiness 用 `mysqladmin ping --protocol=tcp`（socket 在 initdb 期间会谎报，`deploy-ci-spec.md:325-326`）；`MYSQL_DATABASE=xm_java`（xm-team 冷启动的坑，`:993`）；initdb 脚本用 Secret 里的口令建 `xm_app` 并只授 §7.5 的权限——**口令同源**（修 B2 / B3），CI 就用它跑，权限不够会当场暴露。
- Redis：`--appendonly yes --appendfsync everysec`、`maxmemory-policy noeviction`（里面是锁、租约、组队等权威数据），CI 也设 `--requirepass`，**每个 Java 进程都注入 `XM_REDIS_PASSWORD`**（修 B4 那一类：preflight 逐进程核对拿到了口令）；readiness `redis-cli ping`，PONG 或 NOAUTH 都算（照基线 `redis.yaml:108-135`）。
- Kafka：KRaft 单节点，关自动建 topic，副本因子 1，挂 PVC（`fsGroup` 取镜像运行用户 gid，CI 首轮核对，H8）；只改 replicas 不会带来冗余；探针里把 `KAFKA_HEAP_OPTS` 压小（基线 README `:1004-1008`）。
- 镜像 digest 与 `deploy/compose/infra.yaml` 逐字相同，CI 守卫比对文件（§0.6 #25）。infra Pod 同样 `enableServiceLinks: false`。

### 5.5 客户端入口（取值客户端可见，形状不变）

gate 与 battle 都由 values 的 `exposure.mode` 决定通告地址：

| 模式 | 通告主机 | 通告端口 | Service | 适用 |
|---|---|---|---|---|
| `podIP` | `$(POD_IP)` | 11000 / 12000 | 只有 headless | CI、集群内 robot（单节点 k3s 的宿主能路由到 Pod 网段）；**不得用于对外** |
| `hostIP` | Downward API `status.hostIP` | `portBase + 序号` | 每序号 NodePort，`externalTrafficPolicy: Local` | CI、节点 IP 可直达的裸金属：通告的就是 Pod 所在节点，Local 一定命中并保留客户端 IP |
| `publicHost` | 固定 `publicHost`（外部 L4 / IP，可带 `{ordinal}` / `{zone}`） | `portBase + 序号` | 每序号 NodePort，**`Cluster`** | 一个外部 L4 转发整段 NodePort：不一定打到 Pod 所在节点，所以不能用 Local |
| `loadBalancer` | 带 `{ordinal}` 的主机模板，如 `gate-{ordinal}.z{zone}.example.com` | `clientPort` | 每序号 LoadBalancer，Local | 托管云；DNS 由运维提供；成本见 H23 |

- **每序号 Service 一律 `publishNotReadyAddresses: true`**：endpoint 不随 readiness 摘除，排空、关准入、停机前段的持票重连都能进来（§1.6.3、§5.6）。按 EndpointSlice 控制器的规则，这类 Service 下正在终止的 Pod 也留在 endpoint 里（推断，CI C5 核实），gate 自己在停机时拒绝新握手。
- **硬约束**：同一 zone 的每个 gate 必须有自己的客户端地址。令牌绑 gate 节点号，共用地址会让握手回 `wrong_gate`；5.4 的「gate 目录同地址只留最新」还会把共用地址的 gate 合并掉（`zone-travel-spec.md:119`、`:606`）。
  schema 强制：副本 > 1 时 `loadBalancer` 的主机必须含 `{ordinal}`；NodePort 两种模式按序号错开端口。事后由 A22 兜底。
- **NodePort 段**：每个 zone 一段 `[portBase, portBase + portBlock)`（`portBlock` 缺省 10，副本数不得超过它），battle 一段；全部在 30000–32767 且互不重叠，`xm-ops deploy preflight` 读集群里已装的 release
  与本次 values 一起校验，再做 `--dry-run=server` 预演（照搬基线 `:2507-2533`、D88）。
- **组合禁令**：`publicHost` + `Local` 渲染失败（外部 L4 打不到时直接丢流量）；`hostIP` + `Cluster` 只 WARN（丢客户端真实 IP，限流按 IP 失真）。
- **Java 侧**（J1、J2）：按 `XM_POD_NAME` 末尾的 `-<n>` 解析序号（不依赖 `apps.kubernetes.io/pod-index`）；`advertise-port-base` 有值时通告端口 = base + 序号，与 `advertise-port` 互斥，同配即拒启；
  通告主机支持 `{ordinal}` / `{zone}`；需要序号却解析不出时拒启（fail-closed）。**不在 shell 里拼启动命令**：镜像是 exec 形式，java 必须是 PID 1 直接收 SIGTERM（7.1 §3.3）。
- **battle**：票据给客户端的地址用已有的 `XM_BATTLE_CLIENT_ADVERTISE_HOST` / `XM_BATTLE_ADVERTISE_PORT`（`xm-battle application.yaml:62-65`），目录 `rpc_host` 仍是 `xm.advertise-host` = Pod IP。
  battle 地址要对所有 zone 的客户端可达（`spectate-spec.md:347`）。
- **gateway**：Service 缺省 ClusterIP，可选 NodePort / LB；可选标准 `networking.k8s.io/v1` Ingress，只路由 `/api`，**不设缺省 `ingressClassName`**（基线缺省 nginx，`k8s_deploy.ps1:216`；
  ingress-nginx 社区版已宣布退役）。生成 Ingress 时 `gateway.trustedProxies` 必填（schema 强制），映射到 `xm.gateway.rate-limit.trusted-proxies`（`xm-gateway application.yaml:102`），否则全体玩家共用一个限流桶
  （同基线 D91）；Java 已显式关掉 `forward-headers-strategy`（`:9`）。

### 5.6 探针与可用性语义

| 进程 | readiness 额外判据 | liveness 额外判据 | 理由 |
|---|---|---|---|
| gate | `gateServing`：客户端端口已监听且已发布进目录；租约丢失或停机开始后 DOWN。**排空标记不影响** | `nodeLease`：节点号租约 `isLost()` | 租约丢失时 gate 停接客、关全部会话（`GateNode.java:272`），只剩重启能恢复。不用 `isValid()`：Redis 一抖动会把所有 gate 同时置 NotReady，正是基线 `gateway.yaml:63-68` 警告的共享依赖问题 |
| battle | `battleServing`：直连端口已监听、目录已发布（准入离开 NOT_STARTED）；停机开始后 DOWN。**运维排空 / 准入 CLOSED 不影响** | `logicLoop`；`nodeLease`：租约丢失**且**房间归零（现在只打一行「可安全重启」，`BattleNode.java:287`、`:308`） | 票据在房间存活期内可反复重连（`battle-node-spec.md:197`）；准入闸单向（`AdmissionPhase.java:3`），关准入后在打的局还要重连 |
| scene | `sceneServing`：身份 VALID（5.5 之前用租约 `isValid()`）、不在疏散、ACTIVE 频道 ≥ 1 | `logicLoop`：逻辑线程每 1 s 跑一次心跳任务，30 s 没跑就 DOWN | scene 没有 Service，readiness 只影响滚动节奏与 PDB；5.5 要求挂起 / 疏散时 DOWN（`scene-drain-spec.md:110`、`:679`）。TAKEN 时 5.5 疏散后自行退出（`:639`） |
| login、team、guild、trade、scene-manager、data、match | — | `nodeLease`：持有的发号租约 `isLost()` | 代码注释写明租约丢失「需要重启本进程」（`LoginConfiguration.java:212-221`、`TeamConfiguration.java:111-119`）；无效期间只拒发号，不进 readiness |
| gateway、chat、friend | — | — | — |

- 因为 gate / battle 的每序号 Service 设了 `publishNotReadyAddresses: true`，它们的 readiness 只影响：xm-ops 的「新 Pod 就绪」等待、PDB 计算、`kubectl rollout status`。
- 接法：每个判据是普通 `HealthIndicator` bean，经 `management.endpoint.health.group.{readiness,liveness}.include=<State>,<指示器>` 接入；scene-manager 的 `worldChannels`（`WorldChannelsHealthIndicator`）只给 `/actuator/health` 与告警（H3）。
- **liveness 阈值**：`logicLoop` 30 s + 探针 3 × 10 s，卡死后约 60 s 被重启。卡死的 scene 本来就存不了盘、服务不了人；损失与 kill -9 相同（周期存盘之后的增量），归属租约 30 s 后过期（Q14）。
- **Redis 长时间不可用**（> 租约 TTL 15 s）会让持租约进程的 liveness 依次 DOWN、重启、在 Redis 回来前 CrashLoopBackOff：它们本来就要重启才能恢复（H21、Q15）。

### 5.7 网络策略与安全上下文

**NetworkPolicy**（K8s 内置 API，生效与否取决于 CNI；k3s 自带控制器）：

| namespace | 规则 |
|---|---|
| 应用 ns | 缺省拒绝全部入站，再放行：① `part-of=xuanming-java` 的 Pod 之间全部端口（Dubbo 2088x、链路 21000、资产通道 21100、battle 控制面 21200；每次调用另有 HMAC）；② gate 11000、battle 12000 来自任意来源；③ gateway 18081 来自 Ingress controller 所在 namespace（选择器可配；Service 为 NodePort / LB 时放开）；④ 管理端口 181xx 来自监控 namespace（选择器可配），另可配 `probeSourceCIDRs` 给 kubelet 探针（多数 CNI 不拦宿主到 Pod，CI 实测） |
| infra ns | 只放行来自应用 ns 的流量 |
| 出站 | 不限制（DNS、托管依赖、GHCR 拉取都要；YAGNI） |

这同时堵住盘点隐患②「Dubbo 端口对整个集群开放」（`java-infra.md:342`）。port-forward 走 kubelet 进 Pod 网络回环，不受 NetworkPolicy 影响。

**securityContext**（Java 容器）：见 §5.4 模板。infra 容器沿用各镜像缺省，不强制只读根。

### 5.8 运维 CLI 模块 `xm-ops`

- **形态**：Maven 模块 `xm-ops`，包 `com.game.ops`，普通 `main`（`com.game.ops.OpsMain`），像 xm-robot 一样用 spring-boot-maven-plugin `repackage` 打可执行 jar；不是服务、不出镜像、不进 compose / chart；7.1 的模块漂移守卫把它与 xm-robot 一起排除。
- **依赖**：xm-common（`ProductionGate`、`RunMode`，门禁只有一份实现）；Jackson 与 SnakeYAML（版本由 Spring Boot BOM 管理）；JDK `HttpClient`、`ProcessBuilder`；7.5 落地后加 `xm-contract-check`（库依赖）。不引入 Kubernetes Java 客户端（4.0K / 3.7K）。
- **外部命令**：只调 `helm`、`kubectl`、`docker`、`git`、`gh`，一律 argv 形式，不经 shell；**秘密永不进 argv**，只经 stdin 或读文件；从不打印秘密、不碰 kubeconfig 凭据。
- **运维 HTTP**：自己拉起 `kubectl port-forward`（随机本地端口）到 xm-data 18106、gate 18103、battle 18112、scene 18104，用 `XM_ADMIN_TOKEN` 与 `X-Xm-Operator`（`AdminAuthFilter.java:27-28`）。
- **通用约定**：写操作前一次过完门禁；`--dry-run` 只打印将执行的命令；任一步失败即停，打印当前状态与人工补救命令；退出码 0 成功 / 1 判定失败 / 2 用法或工具自身错误。
- **可测性**：外部命令经 `CommandRunner` 接口，单测用假实现断言命令序列（金样），覆盖「拒绝路径在任何写之前退出」。

| 命令 | 作用 |
|---|---|
| `release check --version vX.Y.Z` | 版本号、CHANGELOG 段、工作树干净、tag 不存在或指向 HEAD、同一提交的 CI 与 Integration 已成功（`gh api`，§5.10） |
| `release manifest` / `release verify` / `release digests` | 写 manifest、发布说明、sha256sums；校验目录与 images.tar；推送后记 digest |
| `deploy secrets --env-file <f> --profile <p>` | 按 §7.2 矩阵校验并经 stdin 建 / 更新 Secret |
| `deploy preflight --profile <p> [--release-manifest m] [--digests d] [--zone-values …]` | §5.11 第二层全部检查，不写集群 |
| `deploy images --release-manifest m --digests d --out values-images.yaml` | 每个模块写成 `ref@sha256:…` |
| `deploy platform-up [--with-infra]`、`deploy zone-open`、`deploy zone-close`、`deploy status` | §5.9 |
| `deploy gate-roll` / `gate-scale` / `battle-roll` / `battle-scale` / `rollback` | §5.9 |
| `obs check` | 规则与看板引用的指标名对照各模块指标目录（§4.8 第 2 条） |
| `obs export-fallback --loki <url> --since … --until … --out <f.jsonl>` | 按固定、互不重叠的时间窗从 Loki 导出审计兜底行（J10） |

### 5.9 运维流程

| 流程 | 步骤（任一步失败即停） |
|---|---|
| **platform-up** | `deploy secrets` → 可选 `helm upgrade --install xm-infra … --wait` → preflight → `helm upgrade --install xm-platform … --wait`（`--set secretsRevision=<Secret resourceVersion>`）→ 等全局服务 readiness → 打印各 Pod `/actuator/info` 的 `build.commit` / `build.release`。gateway 的 seed-zones 关掉（J12） |
| **zone-open --zone Z --name N [--capacity C] [--status open\|preview --open-time T]** | ① 前置：xm-platform 就绪；目录里这个 zone 不存在或是 CLOSED / MAINTENANCE；X16 已落地（多 zone 时）。② 目录：不存在就 `POST /admin/zones` 并**显式**带 `manualStatus=1`（null 会按 OPEN 写，`ZoneAdminController.java:58`），存在就 `POST /admin/zones/Z/maintenance`（`:121`）；玩家此时拿到 503 `zone_maintenance`。③ `helm upgrade --install xm-zone-Z deploy/helm/xm-zone -f deploy/helm/zones/zone-Z.yaml --wait --timeout 10m`。④ 等每个 scene Pod Ready（含「有 ACTIVE 频道」）、每个 gate Pod Ready、`GET /admin/gates/Z` 列出全部 gate。⑤ 可选：白名单账号跑 robot 冒烟。⑥ `POST /admin/zones/Z/open`（`:131`），或 PUT 成 PREVIEW 带开放时刻（503 `zone_not_open`） |
| **zone-close --zone Z [--announce msg] [--grace dur] [--final maintenance\|closed]** | ① 目录置 MAINTENANCE（约 1 s 生效，排队中的轮询同样被拦）。② 可选公告并等 `--grace`（让玩家自己下线；不用逐 gate 排空，§0.6 #16）。③ **先停 gate**：gate StatefulSet 缩到 0（gate SIGTERM 停接客、关会话，scene 随会话断开逐个写回并记重连租约），等该 zone 各 scene 的 `xm_scene_players` 合计归零（上限 60 s）。顺序同 `stop-slice.sh`「先 gate 后 scene」，免得 scene 停机时还要疏散（`scene-drain-spec.md:869-870`）。④ `helm uninstall xm-zone-Z --wait`。⑤ `SREM xm:world:zones Z`（`RedisKeys.worldZones` 注释要求人工做；否则 A42 一直响）。⑥ `--final closed` 时 GET 后 PUT 成 CLOSED（503 `zone_closed`），缺省留在 MAINTENANCE。**不删任何玩家数据，不清其他 Redis 键，从不调 DELETE**（Q26） |
| **status [--zone Z]** | `kubectl get sts,deploy,pod,svc,pdb -l xm/zone=Z`、目录状态、各 gate 的节点号与在线数（`GET /admin/gates/Z`）、各 scene 频道数、`/actuator/info` 版本、`updateRevision ≠ currentRevision` 的 OnDelete 工作负载 |
| **gate-roll --zone Z [--ordinal i] [--force-last]** | 对 `controller-revision-hash` ≠ `updateRevision` 的 gate Pod 按序号倒序逐台：① port-forward 取 `GET /gm/identity`（区、节点号、实例）。② `GET /admin/gates/Z` 核对身份在目录里。③ `POST /admin/gates/drain {zone_id, node_id, ttl_sec}`；本区最后一个接客的 gate 回 409 `last_gate`，不带 `--force-last` 就停（`GateDrainAdminController.java:112-144`）。④ 轮询到 drained（`below_threshold` / `deadline`，gateway 判定循环每 5 s，deadline 缺省 25 min，`xm-gateway application.yaml:88-91`）；中途身份变了就撤回标记并中止。⑤ `kubectl delete pod`（不是驱逐，不受 PDB 0 限制）。⑥ 等新 Pod Ready、目录里出现新实例。⑦ `DELETE /admin/gates/drain/Z/<旧节点号>`（`:157`）。排空期间被排空的 gate 仍 Ready、仍在 endpoint 里，持票重连照常进来（§5.6）。这就是 PARITY gate 排空行的「Java 待做：K8s 滚动替换编排」 |
| **gate-scale --zone Z --replicas N** | 扩容直接改副本；缩容先按 gate-roll 的①–④排空最高的几个序号，再改副本，删掉多余的每序号 Service（同基线 `:2535-2554`） |
| **battle-roll [--ordinal i]** / **battle-scale** | ① `POST /admin/battle/drain`（J6：关准入、目录 `accepting=false`；readiness 不变，单向）。② 轮询 `xm_battle_rooms` 归零，上限整场期限 300 s（`battle-node-spec.md:195`）+ 60 s；超时就停下并报告，`--force` 才删（会作废在打的局）。③ 删 Pod，等 Ready |
| **scene 滚动**（`helm upgrade xm-zone-Z`） | 先起全部新 Pod（maxSurge 100%），新 Pod 拿到频道、readiness UP 后旧 Pod 一起收 SIGTERM；旧 Pod 按 5.5 把玩家疏散到新节点（缺省开，预算 15 s），剩下的写回。每个玩家至多被迁一次（缓解 5.5 R10，`scene-drain-spec.md:1106`；疏散目标只选 ACTIVE 且不在疏散的节点）。资源紧的集群可把 maxSurge 改 1（每台一次，最坏连续迁 N−1 次） |
| **全局服务滚动**（`helm upgrade xm-platform`） | Deployment 逐个 surge；battle 是 OnDelete，`helm upgrade --wait` 之后必须接 battle-roll（H5）；xm-data 是 Recreate，审计消费短暂暂停（H15） |
| **rollback --release R --revision N** | `helm rollback R N --wait`。镜像按 digest / 不可变 tag 引用，回滚真的换镜像（避开基线 `latest` + `IfNotPresent` 的坑，`tools.md:420`）。gate / battle 回滚后接 gate-roll / battle-roll。库结构只扩不缩：回滚前核对 manifest 的 `db_migration_head`，跨结构变更的回滚要人工评估 |

### 5.10 发布流水线

**发布身份**：
1. 版本号 `vX.Y.Z[-pre]`，规则同基线 §2.1，在 `xm-ops` 的 `com.game.ops.release.ReleaseRules` 里定义一次（`Pattern.matches` 整串匹配、数字段 `[0-9]`、拒空白、小写 v、无前导 0），workflow 只调它。
2. **Maven `project.version` 不动**（Q29）：根 pom 加属性 `<xm.build.release>unreleased</xm.build.release>`，作为 build-info 的 additionalProperty `release` 输出（**缺省非空**，§0.6 #9）；
   `/actuator/info` 显示 `build.release / commit / contract`；镜像构建参数 `XM_BUILD_VERSION=vX.Y.Z` 进 OCI `version` label 与 `/app/BUILD_INFO`（7.1 Dockerfile 已有这两个出口）。`StartupBanner` 与 `release` 校验把 `unreleased` 当快照。
3. 镜像 tag：快照 `<sha12>`（7.1，只在 CI 本地用，不推）；发布 `vX.Y.Z-<sha12>`，≤128；黑名单同基线；永不打 `latest`。
4. **启动版本行**（J8）：每个进程打一行 `service starting service=xm-gate release=v1.2.3 commit=<40> contract=<40> run_mode=prod`，对标 Go 的 `buildinfo.go:71-72`；`StartupBanner` 读 classpath 的 `META-INF/build-info.properties`。
5. **CHANGELOG.md**：中文，Keep a Changelog 1.1.0；段落规则逐条照抄基线 §2.5；每个功能批次在 `[Unreleased]` 下写「对部署和运维的影响」与需要的库迁移 `Mn`（Q30）。
6. **四处一致**：git tag（用户打）= workflow 输入 = CHANGELOG 段 = 镜像自报版本（OCI label、BUILD_INFO、`build.release`）。

**`.github/workflows/release.yml`**（`workflow_dispatch`，输入 `version`；`permissions: contents: read, actions: read`；全仓一个 concurrency group、不取消；`ubuntu-24.04`；action 按 SHA 钉死；输入经环境变量进脚本，同 7.1 §4.2）：
1. 检出（`fetch-depth: 0`、`persist-credentials: false`）→ setup-java 21 → 环境自检（docker、compose ≥ 2.20、磁盘）。
2. `./mvnw -B -ntp -pl xm-ops -am -DskipTests package`，然后 `xm-ops release check --version "$VERSION"`：版本号；CHANGELOG 恰好一段且非空；`git status --porcelain=v1 --untracked-files=all` 为空；
   `refs/tags/$VERSION` 不存在或指向 HEAD；**同一提交的 `CI` 与 `Integration` workflow 都已成功**。放在最前面，避免白跑长构建。
3. `./mvnw -B -ntp -DskipTests package -Dxm.build.commit=$GITHUB_SHA -Dxm.build.contract=<SOURCE> -Dxm.build.release=$VERSION`。单测不重跑（Q34）。
4. 用 7.1 的 Dockerfile 构建全部进程镜像（`XM_IMAGE_NAMESPACE=ghcr.io/luyuan-java`、tag `vX.Y.Z-<sha12>`）；镜像断言：revision = 40 位 SHA、version = vX.Y.Z、`com.game.contract.mmorpg-commit` = SOURCE、User = `10001:10001`、BUILD_INFO 正确；构建后再查一次工作树没变。
5. **用这批镜像跑整栈冒烟**（`deploy/ci/stack-smoke.sh`，与 integration.yml 共用：robot 第一期 + 停机断言）。被测的镜像就是要发布的镜像。
6. `docker save` 全部镜像进**一个** `images.tar`（层去重）；`xm-ops release manifest` 写 `release-manifest.json`、`RELEASE_NOTES.md`、`sha256sums.txt`（LF、序数排序、不覆盖已存在文件，规则同 `artifacts_lib.ps1:176-259`）；
   `xm-ops release verify` 自检 sha256sums 一致、tar 内 `manifest.json` 的 RepoTags 恰好覆盖全部模块（补基线「批量 save 丢镜像」的检查）。`helm package` 三个 chart（version = X.Y.Z，appVersion = vX.Y.Z）一起放进制品。
7. 上传两个制品：`xm-release-vX.Y.Z`（manifest、notes、sha256sums、chart 包，保留 90 天，同基线）与 `xm-release-vX.Y.Z-images`（images.tar，保留 7 天；它也是离线包，Q33）。
8. Step Summary 打印两条人工命令：`git tag vX.Y.Z <sha> && git push origin vX.Y.Z`，以及「触发 release-push.yml，填 run_id」。**不打 tag、不建 GitHub Release（会产生 tag）、不部署**（AGENTS §5，同基线 `release.yml:12-17`）。

**`.github/workflows/release-push.yml`**（用户看过制品后手动触发，输入 `version`、`run_id`；只有它拿 `packages: write`）：
1. 下载该 run 的两个制品 → `xm-ops release verify` → `docker load` → 每个镜像 ID 等于 manifest 里的 `image_id`（照 `import_images.ps1:17-23`）。
2. 对每个 ref 先 `docker manifest inspect`：**远端已存在就拒绝**（GHCR 不支持不可变 tag，只能由流程保证）。
3. `docker login ghcr.io`（`GITHUB_TOKEN` 经 stdin，用 runner 自带 CLI，不引入第三方 action）→ 逐个 push → `docker buildx imagetools inspect` 读回 digest（只认同 repo；同 repo 多个 digest 拒绝，规则同 `release_common.ps1:496-562`）。
4. `xm-ops release digests` 写 `digests-vX.Y.Z.json` 上传为制品（manifest 本身不可变，digest 另存，同基线 checklist；部署前 preflight H 节要求 digest 覆盖全部镜像，修 G6）。

**发布说明**：CHANGELOG 该段原文；需要的库迁移；「契约变更」一节由 7.5 的 `ContractGate` 以上一个发布 tag 的契约面为旧、当前为新算出（xm-ops 以库依赖调用，只取报告，不沿用它的退出码）；7.5 未落地时写「未生成」。

**manifest 字段**：`version`、`commit`（40 位）、`contract_commit`、`created_at`（提交时间，可复现）、`java` / `spring_boot` 版本、`tables{manifest_sha256, json_sha256, file_count}`（`json_sha256` 算法同基线：顶层 `*.json` 序数排序）、
`battle_fingerprint`（6.1 战斗配表指纹）、`db_migration_head`（`docs/design/db-migrations.md` 最新的 `Mn`）、`chart_version`、`images[{module, ref, image_id, labels}]`、`changelog`、`workflow{run_id, url}`。

**不做**：仓库外制品根；jar 进制品（约 1 GB、镜像里已有）；签名 / SBOM / 扫描 / 多架构（Q35）。

### 5.11 发布门禁（两层）

**第一层：运行期 `ProductionGate`（xm-common `com.game.common.ops`，纯函数，写法照 `BattleSecretPolicy`）**

- 每个进程在自己的配置类里显式声明一个**早于任何 `SmartLifecycle`** 的 bean 调用它（Tomcat 管理端口、Netty、Dubbo 都在 lifecycle 阶段开端口；`ApplicationRunner` 太晚，§0.6 #5）；12 个调用点（6.4 后 13 个），不用自动装配。
- 所有进程都读 `xm.run-mode: ${XM_RUN_MODE:prod}`（现在只有 gate、scene、trade、battle 读，grep；J8 补齐其余 8 个）；不认识的值按 prod 并 WARN（`RunMode.isRecognized`）。
- prod 下有 FAIL 就抛 `IllegalStateException` 拒启，日志只写变量名不写值，最后打一行 `[ProductionGate] mode=… pass=N warn=N fail=N`；dev / test 只 WARN（缺失类仍按现有代码拒启，如 `DubboCallAuth.java:56-57`）。
- 每个进程只检查它看得到的秘密（矩阵 §7.2）；长度按 UTF-8 字节、去 ASCII 首尾空白（口径同 `BattleSecretPolicy`）；占位串黑名单沿用基线 11 个（不区分大小写、去空白后比较），另拒单一字符重复与 Java 的开发缺省值。

| 检查 | 进程 | prod | 说明 |
|---|---|---|---|
| HMAC 类秘密存在、≥32 字节、非占位：`XM_DUBBO_SECRET`、`XM_GATE_TOKEN_SECRET`、`XM_NODE_LINK_SECRET`、`XM_BATTLE_TOKEN_SECRET`、`XM_ASSET_OP_SECRET_GUILD` / `_TRADE` | 各自 | FAIL | 现在 Dubbo / 链路密钥只查非空（`DubboCallAuth.java:56`、`NodeLinkAuth.java:46`） |
| 可选秘密设了就要 ≥32：`XM_ADMIN_TOKEN`、`XM_GM_ADMIN_SECRET` | data、trade、battle；gate、scene | FAIL | 不设时接口一律 503 / 拒绝，保持现状 |
| 口令 ≥12、非占位：`XM_MYSQL_PASSWORD`；`XM_REDIS_PASSWORD`（prod 必填） | 连库 / 连 Redis 的进程 | FAIL | 同基线 B 节；Redis 口令逐进程查，堵 B4 那一类 |
| `XM_MYSQL_USER` 未设或为 `root` | 连库进程 | FAIL | 缺省是 root（如 `xm-scene application.yaml:28`）；最小权限账号见 §7.5 |
| 秘密两两不同（进程可见的集合） | 全部 | FAIL | 把 `BattleSecretPolicy` 的同类判定推广到所有信任域（6.2 Q11，`battle-node-spec.md:1524`）；复用才是真风险，「几处一致」对占位串免疫 |
| `xm.login.mode` 必须是 `prod` | login | FAIL | yaml 改成 `${XM_LOGIN_MODE:dev}`（现写死 dev，`:84`），对应基线 `debug.login.devpassword`（§0.6 #31） |
| `XM_GM_ALLOW_REMOTE` ≠ true | gate、scene | FAIL | — |
| `logging.level.{root, com.game, org.apache.ibatis, org.apache.dubbo}` 不是 DEBUG / TRACE | 全部 | FAIL | 对应基线 `debug.jpa.showsql` / `debug.cpp.loglevel`：MyBatis 在 DEBUG 下打 SQL 与参数 |
| actuator exposure ⊆ {health, info, prometheus} | 全部 | FAIL | 对应基线 `debug.actuator.exposure`；危险端点另加 `heapdump`、`threaddump`、`env`、`loggers`、`configprops`、`beans`、`shutdown` |
| gateway 限流 rps ≥1、burst ≥ rps | gateway | FAIL（任何模式，启动校验） | 落码时核对现有属性类，没有就补 |
| `XM_GATEWAY_RATE_LIMIT_ENABLED=false` | gateway | WARN | — |

**第二层：部署期 `xm-ops deploy preflight`**（`--profile dev|staging|prod`；dev 下秘密类降为 WARN，同基线；读集群里的 Secret 只在内存比较，H10）

| 基线节 | Java 判据 | 级别 |
|---|---|---|
| A gate 密钥跨 5 文件一致 | **不适用**：所有进程读同一个 Secret 键，部署侧只有一个来源。改查强度、两两不同（含 battle ≠ gate） | FAIL |
| B 口令 | Secret 存在；每个启用进程按矩阵要的键都在（逐进程核对「确实拿到了」，K8s 稿对 B4 的教训）；强度同第一层；prod 下 `XM_MYSQL_USER ≠ root`、`XM_REDIS_PASSWORD` 必填 | FAIL |
| C tag | values 里每个镜像是 `@sha256:` 或匹配 `^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?-[0-9a-f]{12}$` / `^[0-9a-f]{12}$`；不在黑名单；prod 不许 `-dirty`；**全部模块**同一版本（修 G5）；`values.schema.json` 在 `helm lint/install` 时再拦一次 | FAIL |
| D Kafka broker 下界 | prod：`XM_KAFKA_BOOTSTRAP_SERVERS` ≥3 个地址且 `XM_AUDIT_REPLICATION_FACTOR` ≥3 | prod FAIL、staging WARN（Q38） |
| D 分区数一致 | **不适用**：进程启动时按规格核对 topic（`AuditTopicInitializer`） | — |
| E 租约下界 | **不适用**：Java 的租约是代码常量（`PlayerLocationDirectory` 30 s）并有启动校验（`scene-drain-spec.md:1015-1025` C1–C7） | — |
| F 限流 | prod：`gateway.rateLimit.enabled` 必须显式写（schema 要求）；配了 Ingress 时 `trustedProxies` 非空 | FAIL |
| G 调试开关 | prod：`runMode: prod`（同时关 GM 指令与 trade 播种）、`loginMode: prod`、不设 `XM_GM_ALLOW_REMOTE`、日志级别与 exposure 同第一层 | FAIL |
| H 制品与版本 | 给了 `--release-manifest` 时：manifest 版本 = 镜像 tag 版本；`--digests` 覆盖全部镜像且 digest 一致（修 G6）；全部镜像同一 revision；契约 commit 一致。部署后 `status` 再核对 `/actuator/info` 的 `build.release` | FAIL |
| 新增 | 渲染结果里每个 Pod 都有 `enableServiceLinks: false`；NodePort 段不重叠、在 30000–32767、副本 ≤ portBlock；暴露模式组合合法；每序号 Service 带 `publishNotReadyAddresses`；scene 宽限期 ≥ C2 下限；gate / battle PDB 与更新策略一致；data 保留期在 prod 为 0 时 WARN（Q51）；`XM_LOGIN_DEV_PASSWORD` 出现在 prod env 文件里 WARN | FAIL / WARN |

退出码：0 = 无 FAIL；1 = 有 FAIL；2 = 用法错误或工具自身错误（**显式实现**，修 G3）。

### 5.12 日志

- **格式**（J13）：12 个进程 yaml 都加

  ```yaml
  logging:
    structured:
      format:
        console: ${XM_LOG_FORMAT:}            # 空值 = 文本（实测 2）；本机切片与 run/logs 不变
      json:
        add:
          service: ${spring.application.name}
          # 只有 gate / scene 加 zone: ${xm.zone-id}（J3 之后带占位符）；login / gateway 也有 zone-id，但它们是全局进程，不加
        stacktrace:
          max-length: 8192                    # 低于 Loki 单行上限；落码时定
  ```

  Dockerfile 加 `ENV XM_LOG_FORMAT=logstash`，容器里缺省 JSON。选 `logstash` 不选 `ecs` / `gelf`：字段 `@timestamp / level / logger_name / thread_name / message / stack_trace` 与基线 gateway 同口径，
  基线的 Alloy 规则与值班 LogQL 都按这组字段写。代码不用 MDC（全仓 grep），JSON 顶层不会出现 player_id，玩家号只在消息正文里（AGENTS §5）。
- **采集**：Vector（Q39）。K8s 用 `kubernetes_logs` DaemonSet，按 `app.kubernetes.io/part-of=xuanming-java` 过滤，读 `/var/log/pods`，只读 hostPath，RBAC 只给 list / watch pods；compose 用 `docker_logs` source
  （只读挂 docker.sock，只用于 CI 与单机，H28）。VRL：`parse_json` 失败就回落 Spring 文本正则（照基线 `config.alloy:443-467`），文本格式的堆栈续行按行头合并；Loki sink 打开**磁盘缓冲**（H12）。
- **Loki 标签只用低基数字段**：`env`、`namespace`（K8s）、`service`（JSON 字段，缺省取 Pod 标签 `app.kubernetes.io/name` 或 compose 服务名）、`zone`（Pod 标签 `xm/zone`；全局进程填 `global`）、
  `level`（小写）、`stream_class`（`logger_name` 以 `xm.audit.` 开头为 `audit`，否则 `app`）。pod、logger、thread 留在行内（`| json` 查询）——pod 不作标签，因为每次滚动都换 pod 名、制造新流。CI 查 `/loki/api/v1/labels` 必须是白名单子集。
- **存储**：Loki 3.5.x（tag + digest 钉死），`retention_period: 168h`；`retention_stream: [{selector: '{stream_class="audit"}', priority: 1, period: 720h}]`，与审计 topic 30 天对齐（`data-ops-spec.md:194`）；
  compactor retention 与 `delete_request_store` 都要配（基线 `loki.yaml:68-73`）；不配 ruler。
- **审计兜底在 JSON 日志与 K8s 上的可回灌性**（J10，同批阻断项）：`xm.audit.fallback` 是 Kafka 没确认的流水唯一的回灌来源（`AuditFallbackLog.java:29-30`：WARN 级、`extra=` 在行尾）。现状的两处断点：
  ① `FallbackLines` 的正则 `… extra=(.*)` 取到行末（`FallbackLines.java:36-41`），JSON 行会把 `","logger_name":…}` 吃进 `extra`，`message` 里的引号与反斜杠也被转义了，结果是静默写错 `extra` 或判 Malformed；
  ② 去重键是「文件 SHA-256 + 行号」（`FallbackReplayer.java:28`、`:129`），只适合已轮转、不再写入的文件。容器日志由 kubelet 轮转（10Mi×5），K8s 上唯一的持久副本在 Loki。修法：
  - `FallbackLines.parse` 识别以 `{` 开头的行：用 Jackson 取 `logger_name` 与 `message`，logger 是 `xm.audit.fallback` 才把反转义后的 `message` 交给原正则；文本行解析不变。这样 `kubectl logs` 的转储也能直接回灌。
  - `AuditFallbackReplay` 加 `--format jsonl`：输入是 `xm-ops obs export-fallback` 按固定、互不重叠的时间窗从 Loki 导出的 `{stream_class="audit"} | json | logger_name="xm.audit.fallback"` 行（每行带 pod、`@timestamp`、`thread_name`、`message`）；
    去重键改为「pod + `@timestamp` + `thread_name` + message」的 SHA-256。推荐沿用 `audit_replay_line` 表：`file_sha256` 列存行摘要、`line_no` 存 0，不改表结构（落码时确认）。同一行被重复导出不会让 `tx_id = 0` 的行重复入库。
  - Kafka 不可用时兜底行是 WARN 级，`logback_events_total{level="warn"}` 会随之上升，属预期；A7 只看 ERROR，不受影响。
- **Grafana**：不开匿名；admin 口令 `${XM_GRAFANA_ADMIN_PASSWORD:?}`；compose 只发布到 127.0.0.1（同 7.1 D3）；数据源 Prometheus（uid `prometheus`）与 Loki（uid `loki`）；看板目录 `xuanming-java`、`allowUiUpdates: false`。
- **不做日志告警**：凡值得告警的事件都有计数器（`xm_scene_audit_records_total`、`xm_data_kafka_records_total`、`xm_scene_gain_anomalies_total`），通用的「ERROR 突增」用 Actuator 自带的 `logback_events_total`（A7）。

### 5.13 指标抓取与告警架构

- **抓取**：每个 Java Pod 打 `prometheus.io/*` 注解（基线 Go 服务同约定，`manifests/go-svc/chat.yaml:52`）；chart 可选生成 PodMonitor（不作为依赖）。重标规则（`deploy/observability/prometheus/scrape-k8s.example.yaml`）：
  `job` ← `app.kubernetes.io/name`（**必须等于进程的 `spring.application.name`**，即公共标签 `application`，由 A12 与 CI 守卫）、`zone` ← `__meta_kubernetes_pod_label_xm_zone`（全局进程没有）、`instance` ← Pod 名。
  compose 用静态目标 `xm-<模块>:<管理端口>`，给出相同的 job 与 zone。抓取 15 s，规则评估 30 s（同基线 `interval: 30s`）。
- **口径**：进程里仍不加 zone / 实例标签（architecture §11）；zone 是**部署层**的抓取目标标签，基数等于目标数。architecture §11 补这一句（Q46）。
- **规则载体**：纯 Prometheus `groups:` 文件 `deploy/observability/prometheus/rules/xm-*.rules.yaml` 是唯一来源；K8s 的 PrometheusRule 或 ConfigMap 包装由 xm-platform chart 从同一份文件生成（可选，缺省关）。
- **路由**：仓库只交付规则（`severity`、`runbook_url`）与 `docs/ops/alerts.md`；接收端由环境提供（Q42）。建议：critical → 呼人、warning → 频道、info → 丢弃；`group_by: [alertname, zone, job]`；A1 / A2 抑制同 instance 的其他告警；
  开区、整 zone 维护前 silence（同基线 `scene-node-role-split.md:206-216`；xm-ops 开关区时打印 silence 命令）。
- **CI 验证**不需要 Alertmanager：Prometheus 自己求值，`ALERTS{alertstate=…}` 就能看到 pending / firing（C9）。

### 5.14 Java 代码改动（随 7.6；全部不是客户端可见）

| id | 内容 | 模块 | 批次 |
|---|---|---|---|
| J1 | `PodOrdinal.parse(XM_POD_NAME)`（取最后一个 `-` 之后的十进制数，需要而取不到时拒启）；gate `xm.gate.advertise-port-base`（与 `advertise-port` 互斥）；通告主机支持 `{ordinal}` / `{zone}` | xm-common、xm-gate | 7.6b |
| J2 | battle 的 `advertise-port-base` 与主机模板（`client-advertise-host` 拆分已在 6.2，§0.6 #10） | xm-battle | 7.6b |
| J3 | 新占位符（缺省值等于现值）：`${XM_ZONE_ID:1}`（gate `:40`、scene `:65`）、`${XM_LOG_FORMAT:}`、`${XM_REDIS_PASSWORD:}`、`${XM_LOGIN_MODE:dev}`、`${XM_AUDIT_REPLICATION_FACTOR:1}`、`${XM_GATEWAY_GATE_DRAIN_DEADLINE:25m}`、`${XM_GATEWAY_TRUSTED_PROXIES:}`（逗号分隔，空串 → 空列表，V11 实测）、scene 的疏散预算 / 停服写回 / 审计发完三个时限（J7）。7.1b 已规划的通告地址 / 链路绑定 / Dubbo URL 占位符不重复 | 各 yaml | 7.6a |
| J4 | readiness 指示器 `gateServing`、`battleServing`、`sceneServing`（§5.6；gate / battle **不含**排空与准入） | xm-gate、xm-battle、xm-scene | 7.6a（scene 身份部分随 5.5） |
| J5 | liveness 指示器 `nodeLease`（xm-discovery 提供，收集本进程持有的 `NodeIdLease`）与 `logicLoop`（scene、battle） | xm-discovery、xm-scene、xm-battle | 7.6a |
| J6 | battle 运维排空：管理端口 `POST /admin/battle/drain`（运维令牌 + 操作人，任何运行模式可用，与 dev 接口区分）→ 逻辑线程上 `admission.close()`、目录继续发布但 `accepting=false`；新开战回 `NOT_ALLOCATABLE`（match 已有换节点处理），在打的局照常打完（6.2 Q15 当时推荐「有需要在 7.6 加」，`battle-node-spec.md:1536`）。**不改 readiness** | xm-battle | 7.6b |
| J7 | scene 宽限期只有一处真相：chart values 的 `evacuationBudget` / `shutdownSaveTimeout` / `auditFlushTimeout` 以环境变量传进程；模板按 C2 求下限（三者之和 + 10 s，取整且 ≥ 60 s），`terminationGracePeriodSeconds` 低于它就 `fail`；xm-scene 启动打出的「外部强杀期限须 ≥ N s」做同一份计算（`scene-drain-spec.md:1021`），CI 比对两者 | xm-scene、chart | 7.6a / 7.6b |
| J8 | `ProductionGate` + `StartupBanner`；全部进程读 `xm.run-mode`；12 个显式调用点（早于 `SmartLifecycle`） | xm-common、各进程 | 7.6a |
| J9 | xm-discovery 新增 `xm.redis.clock.offset`（秒，本机墙钟 − Redis `TIME`，扣 RTT/2，每 30 s）；告警依赖的固定标签组合补预注册；各进程 `MetricsCatalogTest` | xm-discovery、各进程 | 7.6a |
| J10 | 审计兜底回灌：`FallbackLines` 识别 JSON 行；`AuditFallbackReplay --format jsonl` 与内容去重键；`xm-ops obs export-fallback`（§5.12）。**必须与 J13 同一次提交** | xm-data、xm-ops | 7.6a |
| J11 | 根 pom `<xm.build.release>unreleased</xm.build.release>` 与 build-info `release` 项 | 根 pom | 7.6a |
| J12 | gateway `xm.gateway.seed-zones-enabled: ${XM_GATEWAY_SEED_ZONES_ENABLED:true}`；K8s 设 false，区服一律经 xm-ops 写。不用「环境变量覆盖成空列表」：Spring 列表跨来源不合并、用环境变量置空很难（`zone-travel-spec.md` §5.13）；把 status 改成 MAINTENANCE 仍会插入一个可能没部署的 zone 1 | xm-gateway | 7.6a |
| J13 | 12 个 yaml 的结构化日志配置；Dockerfile `ENV XM_LOG_FORMAT=logstash` | 各 yaml、`deploy/docker/Dockerfile` | 7.6a |
| J14 | 新模块 `xm-ops`（§5.8）；根 pom 登记；7.1 漂移守卫排除 | xm-ops | 7.6a（release / obs / preflight 纯逻辑）、7.6b（deploy 子命令） |
| J15 | 更正 `xm-scene application.yaml:21` 那句「停服写回要在这个上限之内完成」的注释（5.5 G16 已指出不成立），与 J7 同提交（若 5.5 先做则不重复） | xm-scene | 7.6a |

### 5.15 文件清单与分批

**7.6a**（7.1b 提交之后即可做，不碰集群）：
- J3、J4（gate / battle 部分）、J5、J7（进程侧）、J8–J13、J15；`xm-ops` 的 release、obs 与 preflight 纯逻辑；`CHANGELOG.md`；
- `deploy/observability/**`：`prometheus/{prometheus.compose.yaml, scrape-k8s.example.yaml, rules/, tests/}`、`loki/loki.yaml`、`vector/{vector-compose.yaml, vector-k8s.yaml, tests/}`、`grafana/{provisioning/, dashboards/}`；
- `deploy/compose/observability.yaml`（`docker compose -f stack.yaml -f observability.yaml`）；`deploy/ci/stack-smoke.sh`；
- `.github/workflows/{release.yml, release-push.yml}`；`ci.yml` 加 `obs-static`；`integration.yml` 的 stack 加观测冒烟；
- 文档：`docs/ops/{release.md, alerts.md}`，tech-stack、architecture §11。

**7.6b**（7.6a、6.2、5.5 提交之后；双 zone 用例另等 5.4 的 X16）：
- `deploy/helm/**`；J1、J2、J4（scene 身份部分）、J6、J7（chart 侧）；`xm-ops deploy` 全部子命令；`.github/workflows/k8s.yml`；
- 文档：`docs/ops/k8s-runbook.md`，architecture §6「K8s 部署 profile」（拓扑、发现、地址、探针、宽限期、可用性语义）。

### 5.16 登记与同步

- **PARITY 新增四行**（都写「mmorpg 已有，本批 Java 对齐（行为有意不同）」）：K8s 开区编排（`mm:deploy/k8s/**`、`k8s_deploy.ps1`、`k8s_gate_drain.ps1` ↔ `deploy/helm/**`、`xm-ops deploy`）；
  发布制品（`release.yml`、`publish_images.ps1` 等 ↔ `release.yml`、`release-push.yml`、`xm-ops release`）；发布门禁（`release_preflight.ps1` ↔ `ProductionGate` + preflight）；日志采集、告警与看板（`deploy/observability/**`、`deploy/k8s/*-alerts.yaml` ↔ `deploy/observability/**`）。
- **PARITY 修订**：gate 排空行的「Java 待做：K8s 滚动替换编排」→ 已完成（`gate-roll`）；「低基数运行指标」行补 zone 目标标签与 job == application；「节点客户端可达地址」行补四种暴露模式；「版本基线」表从首个发布起按 `vX.Y.Z` 登记（Q52）。
- **roadmap**：3.5 备注同步改；7.6 行写提交号。
- **tech-stack.md 新增**：Helm（30.3K）、k3s（34.1K，仅 CI）、Traefik（65.1K，随 k3s，仅 CI）、Prometheus（66.4K）、Grafana（77.1K）、Loki（29.0K，偏离说明）、Vector（22.7K）、GHCR；补「K8s 不用 Nacos」与「告警接收端由环境提供」。
- **盘点注记**（盘点是快照，只加注记）：`java-infra.md:340`（「无任何 K8s 资产」）、`:389` 开放问题 7 已答；`tools.md:416`、`:452`（Deploy / Release 改为 `xm-ops` 模块，ProductionGate 不放 `ApplicationRunner`）；`java-infra.md:363`（「gate 链路背压、assign-gate 失败率」已成 A33 / A13）。
- **建议 mmorpg 同修**（不阻塞 Java，交付说明写「mmorpg 待做（可选）」）：优先 B3、B4、B5、G1（都会让 staging / prod 部署直接失败或被迫跳过门禁），其次 B1、B2、B6、B11、G2、G5、G6；另有 B7、B9、B10、B12–B15、§4.4 的 1–2、K8s 上 Go / Java 日志无采集、Loki ruler 未接线。

---

## 6 选型（AGENTS §2；star 为分稿 2026-10-05 实测）

| 用途 | 选用 | star | 落选（star） | 说明 |
|---|---|---|---|---|
| 清单模板 | Helm 3 | 30.3K | Kustomize（12.2K，随 kubectl 内置，但 §2 认的内置只有 JDK 与 Spring Boot）；jsonnet / tanka / cue / ytt（都不到 1 万）；helmfile（5.2K）；纯 Java 生成器（等于重写 Helm） | values schema、release 历史、`helm rollback`、library chart 现成 |
| CI 用的 K8s | k3s（钉版本、核对 sha256） | 34.1K | minikube（32.2K）、kind（15.5K，基线 `mm:deploy/k8s/kind-config.yaml`） | 同类最高；自带 NetworkPolicy 控制器、Traefik、ServiceLB、local-path；单二进制；镜像经 `k3s ctr images import` 导入，不需要 registry |
| Ingress controller（仅 CI） | Traefik（随 k3s） | 65.1K | ingress-nginx（19.5K，已宣布退役） | 生产由集群决定，chart 只出标准 Ingress |
| GitOps / CD | 不引入 | argo-cd 24.3K | — | 没有常驻集群；部署由人经 `xm-ops` 触发（YAGNI） |
| 动态 GameServer | 不引入 | agones 7.1K | — | §5.1 |
| 运维 CLI | 自写模块 `xm-ops`（JDK + Boot BOM 管理的 Jackson / SnakeYAML） | — | Kubernetes Java 客户端（4.0K / 3.7K）；PowerShell | 只调 `helm` / `kubectl` 二进制 |
| 秘密管理 | K8s 内置 Secret，由 xm-ops 从 env 文件建 | 内置 | external-secrets（6.9K） | YAGNI；托管环境可另接 |
| 渲染校验 | `kubectl apply --dry-run=server`（打 k3s 真 API server） | 内置 | kubeconform（3.2K） | — |
| 指标存储与规则 | Prometheus（规则文件 + promtool） | 66.4K | prometheus-operator（10.0K，只做可选包装）、VictoriaMetrics（17.8K） | 规则以纯 rule-group YAML 为权威，可离线单测 |
| 告警接收 | 不作为仓库依赖（环境提供；建议 Alertmanager） | alertmanager 8.6K | Grafana 告警 | Q42 |
| 看板 | Grafana | 77.1K | — | — |
| 日志采集 | Vector | 22.7K | logstash（15.0K）、fluentd（13.6K）、elastic/beats（12.7K）、fluent-bit（8.1K）、otel-collector（7.6K）、Alloy（3.6K，基线）、Promtail（已弃用） | 同类最高；Java 只写 stdout，不要 sidecar |
| 日志存储 | Loki（**偏离 §2，需用户确认**） | 29.0K | Elasticsearch（78.2K）；ClickHouse（50.3K，通用 OLAP）、SigNoz（32.3K，整套观测平台）不是同类 | Q40 |
| 结构化日志 | Spring Boot 自带 `logging.structured` | Boot 自带 | logstash-logback-encoder（2.5K） | 字段与基线 gateway 同口径 |
| 镜像仓库 | GHCR（`ghcr.io/luyuan-java`，与 `git remote` 的 owner 一致） | — | Docker Hub | 与仓库同在 GitHub，`GITHUB_TOKEN` + `packages: write` 即可推；`org.opencontainers.image.source` 已指向本仓库（`deploy/docker/Dockerfile:57`） |
| 发布号写入 | build-info additionalProperty | Boot 自带 | versions-maven-plugin、flatten-maven-plugin（223）、`${revision}` | Q29 |
| 发布编排 | 自写 workflow + xm-ops | — | goreleaser（16.1K）、jreleaser（1.2K） | 规则少，自写可控 |
| 签名 / SBOM / 扫描 | 暂不做 | — | cosign（6.3K）、syft（9.6K）、trivy（38.2K，以后可先以「只报告」加） | Q35 |
| 数据库迁移 | 不引入，照旧按 `db-migrations.md` 手工 | — | Flyway、Liquibase（都不到 2 万） | manifest 记 `db_migration_head` |

---

## 7 秘密与配置

### 7.1 非秘密配置（K8s 环境变量）

规则同 7.1（`deploy-ci-spec.md:892-903`）：每个变量都必须在 `application.yaml` 里有同名占位符；表数据烤在镜像里（7.1 D11），不用 ConfigMap、不挂卷，K8s 上不支持 `XM_TABLE_DIR` 换表（Q53）。
与基线「把服务 yaml 整块搬进 ConfigMap、整目录遮蔽镜像」相比，不存在「漏一个键就静默出错」。

| 变量 | 值 | 用在 |
|---|---|---|
| `XM_RUN_MODE` | prod；CI 用 dev（要跑 GM 类 robot 场景） | 全部 |
| `XM_MANAGEMENT_ADDRESS` | `0.0.0.0` | 全部 |
| `XM_MYSQL_HOST` / `XM_MYSQL_PORT` / `XM_MYSQL_USER` | 托管地址；dev / CI 为 `mysql.<ns>-infra` / 3306；用户 `xm_app` | 8 个连库进程 |
| `XM_REDIS_ADDRESS` | 托管地址；dev / CI 为 `redis://redis.<ns>-infra:6379` | 全部 |
| `XM_KAFKA_BOOTSTRAP_SERVERS`、`XM_AUDIT_REPLICATION_FACTOR` | prod ≥3 个 broker、3；dev / CI `kafka.<ns>-infra:9092`、1 | scene、data |
| `XM_*_URL` | Service 名（§5.3） | 各 Dubbo 调用方 |
| `XM_ZONE_ID` | zone 号 | gate、scene |
| `XM_ADVERTISE_HOST` | scene：`$(POD_IP)`；gate：按暴露模式（§5.5）；battle：`$(POD_IP)`（目录 `rpc_host`） | scene、gate、battle |
| `XM_BATTLE_CLIENT_ADVERTISE_HOST` / `XM_BATTLE_ADVERTISE_PORT` | 按暴露模式 | battle |
| `XM_GATE_ADVERTISE_PORT_BASE` / `XM_BATTLE_ADVERTISE_PORT_BASE` | NodePort 两种模式下的 portBase | gate、battle |
| `XM_SCENE_LINK_BIND_HOST` | `0.0.0.0`（7.1b 占位符；现写死 `xm-scene application.yaml:81`） | scene |
| `XM_POD_NAME`、`POD_IP` | Downward API | gate、battle、scene |
| `XM_LOGIN_MODE` | prod；CI dev | login |
| `XM_GATEWAY_QUEUE_ENABLED`、`XM_GATEWAY_RATE_LIMIT_ENABLED` | true | gateway |
| `XM_GATEWAY_TRUSTED_PROXIES` | Ingress controller Pod 网段（CI：k3s 缺省 Pod 网段） | gateway |
| `XM_GATEWAY_SEED_ZONES_ENABLED` | false | gateway |
| `XM_GATEWAY_GATE_DRAIN_DEADLINE` | 25m；CI 60s | gateway |
| `XM_DATA_TXLOG_RETENTION` / `XM_DATA_SNAPSHOT_RETENTION` / `XM_DATA_GM_SNAPSHOT_RETENTION` | prod：180d / 90d / 0s（Q51） | data |
| scene 停机三时限（J7） | chart values 给，同时用于宽限期下限 | scene |
| `JDK_JAVA_OPTIONS` | 模板拼（§5.4） | 全部 |
| `XM_LOG_FORMAT` | 镜像缺省 `logstash` | 全部 |

**命名纪律**：Service 名不得与任何 `XM_*_PORT` / `XM_*_HOST` 占位符的前缀相撞（`xm-battle-client`、`xm-battle-rpc`、`xm-gate-advertise`、`xm-scene-asset-rpc` 禁用）；即便如此仍一律 `enableServiceLinks: false`（双保险）。

### 7.2 秘密矩阵与 Secret 对象

- **应用 namespace 一个 Secret `xm-secrets`**，键名等于环境变量名。每个容器只用 `secretKeyRef` 引用自己要的键；必需键**不写 `optional`**，缺键时 Pod 卡在 `CreateContainerConfigError`（同基线 `k8s_deploy.ps1:4178`）；
  只有本来就可选的 `XM_ADMIN_TOKEN`、`XM_GM_ADMIN_SECRET` 写 `optional: true`。
- **infra namespace 一个 Secret `xm-infra-secrets`**：MySQL root 口令、`xm_app` 口令（initdb 建账号用，与应用侧同一个值，由 xm-ops 同时写两个 Secret）、Redis 服务端口令。

| 键 | 注入的进程（在 7.1 §6.1 矩阵上增补） |
|---|---|
| `XM_MYSQL_PASSWORD`（`xm_app` 的口令） | login、friend、team、guild、trade、gateway、data、scene |
| `XM_REDIS_PASSWORD`（新增） | 全部 12 个进程（6.4 后加 match） |
| `XM_DUBBO_SECRET` | scene-manager、login、friend、chat、team、guild、trade、scene、gate、gateway、battle、match |
| `XM_GATE_TOKEN_SECRET` | gate、gateway。**不注入 battle**：「battle 密钥 ≠ gate 密钥」由 preflight 在部署侧比对（6.2 Q11，`battle-node-spec.md:1524`） |
| `XM_NODE_LINK_SECRET` | gate、scene |
| `XM_ASSET_OP_SECRET_GUILD` / `XM_ASSET_OP_SECRET_TRADE` | guild、scene / trade、scene（后者 4.8 用到时） |
| `XM_BATTLE_TOKEN_SECRET` | battle |
| `XM_ADMIN_TOKEN`（可选） | data、trade、battle |
| `XM_GM_ADMIN_SECRET`（可选） | gate、scene |
| `XM_LOGIN_DEV_PASSWORD` | login（只 dev / CI；prod env 文件里出现时 preflight WARN） |

### 7.3 生成、校验、注入

- 秘密值放在**不入库**的 env 文件（与 compose 的 `.env` 同格式），`xm-ops deploy secrets --env-file <f> --profile <p>`：按 `ProductionGate` 的规则逐键校验（缺失、占位串、长度、两两不同、battle ≠ gate），
  dev 档允许缺省时随机生成；生成 `stringData` 清单经 **stdin** 交给 `kubectl apply -f -`。值不进 argv、不进 Helm values、不落临时文件（H6）。
- CI 每次运行用 `openssl rand -hex 32` 随机生成并 `::add-mask::`（同 7.1 §6.3），写进 `$RUNNER_TEMP` 下的 env 文件。
- `deploy preflight` 读集群里现有的 Secret（`kubectl get secret -o json`，base64 解码只在内存里），与本次 values 一起判定（H10）。

### 7.4 轮换

- Pod 模板注解 `xm/secrets-revision: <Secret 的 resourceVersion>`（`xm-ops` 读出来经 `--set secretsRevision=…` 传给 Helm；借鉴基线 `:4303-4330`）。Secret 一变，所有 Deployment 滚动；gate / battle 是 OnDelete，要接 gate-roll / battle-roll。
  注解里只放版本号，不放任何由秘密内容算出来的东西（低熵口令的哈希前缀也能被离线猜，H6）。
- 共享密钥（`XM_DUBBO_SECRET`、`XM_GATE_TOKEN_SECRET`、`XM_NODE_LINK_SECRET`）没有双密钥过渡，轮换只能整体重启，排进维护窗口（H16）。

### 7.5 MySQL 账号

- 应用账号 **`xm_app`**：`GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON xm_java.* TO 'xm_app'@'%'`，不给 DROP、不给全局权限。启动期建表（`spring.sql.init`、pbmysql 只扩不缩）需要 CREATE / ALTER / INDEX；
  pbmysql 的 `GET_LOCK` 不需要额外权限；它打印的修复语句（含 `DROP INDEX`）本来就不执行（`SchemaPlanner.java:326`）。
- 库 `xm_java` 由 `MYSQL_DATABASE`（dev / CI）或 DBA（生产）预建，不再依赖 `createDatabaseIfNotExist`；带这个参数的 URL 在库已存在时是否要求库级 CREATE 权限，以 CI 实测为准（C4）。
- 需要 DROP 的结构变更由 DBA 账号按 `db-migrations.md` 手工做。7.1 的集成测试仍用 root 建临时库，不受影响。root 只给 MySQL 容器与 initdb 用；prod 运行模式下进程用 root 一律拒启（§5.11）。
- 巡检只读账号 `xm_ro`（只有 SELECT）只在 `prod.example.yaml` 留说明，不默认启用（Q58）。

### 7.6 生产值

| 项 | 值 | 出处 / 理由 |
|---|---|---|
| 审计保留期 | 流水 180 天、LOGIN / LOGOUT / PERIODIC 快照 90 天、GM / 安全快照永久 | `data-ops-spec.md:1101`（Q11）；满足「流水保留期 ≥ 快照保留期」（`:353-354`） |
| 审计 topic 副本因子 | 3，broker ≥3 | 基线 D 节同口径 |
| Redis | AOF everysec、口令、`noeviction`、持久卷或托管 | `scene-battle-spec.md:1139`；`xm:node-id-epoch:*` 不得回退 |
| MySQL | 托管、带 PITR、`xm_app` | Q21、Q22 |
| gateway | 排队与限流打开、`trustedProxies` 配齐、gate 排空 deadline 25 min | 同基线 |
| 日志保留 | 应用流 7 天、审计流 30 天 | §5.12 |

---

## 8 隐患与边界

| # | 隐患 | 对策 |
|---|---|---|
| H1 | **Dubbo 绑定规则**：通告地址设成 POD_IP 时 Dubbo 只绑这个 IP（`deploy-ci-spec.md:446`），scene 的资产通道在 Pod 内 127.0.0.1 上连不上 | 探针只打管理端口；只做直连的提供方不设通告地址 |
| H2 | 不写 memory limit 时 `MaxRAMPercentage` 按节点内存算堆 | 模板对 limit 用 `required`，CI 反例验证 |
| H3 | `worldChannels` 是全 zone 聚合判据，放进 scene-manager 的 readiness 会让一个 zone 的问题摘掉 scene-manager、全服登录不了 | 只用于 `/actuator/health` 与告警 |
| H4 | gate / battle 的 PDB 0 挡住 `kubectl drain` 与 cluster-autoscaler（同基线 D87） | 手册写清：维护节点前先 gate-roll / battle-roll；values 可改 1（Q12） |
| H5 | OnDelete 下 `helm upgrade --wait` 只等现有 Pod 就绪，新模板并没生效 | xm-ops 在 upgrade 后检查 `updateRevision ≠ currentRevision` 并接 gate-roll / battle-roll；`status` 显示；只跑 helm 的风险写进手册 |
| H6 | Helm 把渲染结果存成集群里的 Secret，`--set` 留在 shell 历史 | 秘密永不进 values；注解只放 resourceVersion |
| H7 | Redis 数据丢失会让 `xm:node-id-epoch` 回退，scene 重新接受旧 gate 的链路（architecture §6） | PVC + AOF；MySQL 与 Redis 只能一起重置（7.1 §2.8）；`helm uninstall xm-infra` 不删 PVC；xm-ops 不提供删 infra namespace 的命令；回档不做 `FLUSHDB`（K27） |
| H8 | Kafka 镜像非 root 运行，PVC 属主要靠 `fsGroup`（7.1 compose 因此没给 Kafka 挂卷，`deploy-ci-spec.md:355-356`） | uid / gid 在 CI 首轮核对 |
| H9 | 多副本同时冷启动：schema 初始化与 pbmysql 同步可能撞 MDL（基线因此改用 migrate Job，README `:488-489`） | pbmysql 整轮持 `GET_LOCK`；`CREATE TABLE IF NOT EXISTS` 幂等；CI 单独一个「2 副本冷启动」job；真出问题再加 Helm `pre-install` / `pre-upgrade` hook Job（单进程只建表模式） |
| H10 | preflight 要读 Secret 的值 | 只在内存比较，不落盘、不打印、不写进退出信息 |
| H11 | **容器换成 JSON 日志后**：① 按文本 grep 日志的脚本失效；② 审计兜底回灌把 JSON 尾巴吃进 `extra`；③ 从 Loki 重复导出会让 `tx_id = 0` 的行重复入库 | ① 落码时扫一遍 `deploy/`、`.github/` 里的 grep；②③ J10，与 J13 同一提交 |
| H12 | Loki 不可用时审计兜底行只靠 Vector 磁盘缓冲，缓冲满了照样丢 | 磁盘缓冲 + A11；按流保留要 compactor retention 与 `delete_request_store` 都配 |
| H13 | 时钟漂移：Dubbo 与链路鉴权窗 ±60 s、GM 300 s、租约比较用墙钟 | A8（J9）；节点 NTP 由环境保证（node_exporter 不到 2 万 star，不引入） |
| H14 | Dubbo 直连 ClusterIP：长连接要等服务端关闭才重连，滚动时有少量调用失败，gate 给客户端回 1003（客户端可见、短暂）；一个调用方的流量只落一个 Pod | preStop 5 s + Dubbo 停机等待；CI 统计滚动期间 1003 比例（C5）；负载均衡靠调用方数量摊开（Q25） |
| H15 | xm-data 单副本 + Recreate，换版本时审计消费与运维接口短暂中断 | 消息留在 Kafka（保留 30 天），scene 不受影响 |
| H16 | 共享密钥没有双密钥过渡 | 轮换 = 全服维护窗口 |
| H17 | CI 容量：runner 约 4 vCPU / 16 GB，要装 k3s、infra、10 个全局进程、两个 zone 的 gate / scene，scene 还要 surge | `values/ci.yaml` 压 limit（§5.4 CI 列，合计约 11 GiB，surge 时约 12.7 GiB）；2 副本验证放单独 job |
| H18 | 关区时 battle 里还有该 zone 的玩家：结算按 6.3 的发件箱投给 scene，关区后投递失败、重试，直到 zone 重开或租约到期（推断） | CI 加一条用例观察；6.3 已规定结算落到下一个持有者 |
| H19 | 区服目录与 Helm release 是两个真相来源 | 顺序由 xm-ops 保证（先维护再卸载、先就绪再开放）；手工跑 helm 的风险写进手册 |
| H20 | `publishNotReadyAddresses: true` 让 NotReady 的 gate / battle 也在 endpoint 里 | 客户端只按目录里的地址连，gate 未注册目录前不会被分配；停机中的 gate 自己拒握手；这是有意的（§5.6）。CI C5 核实终止中 Pod 的 endpoint 行为 |
| H21 | Redis 长时间不可用（> 租约 TTL 15 s）时，持租约进程 liveness 依次 DOWN、重启，在 Redis 回来前 CrashLoopBackOff | 有意如此（Q15）；scene 用 5.5 的挂起 / 复占模式，不在此列；A1 / A3 会响 |
| H22 | NodePort 段重叠或越界 | preflight 读已装 release 校验 + `--dry-run=server` 预演（同基线 D88） |
| H23 | LoadBalancer 每个 gate / battle 一个，成本随副本线性增长 | 托管云评估；自建推荐 NodePort + 外部 L4 |
| H24 | `publicHost` + Local 丢流量；`hostIP` + Cluster 丢客户端 IP | 前者渲染失败，后者 WARN |
| H25 | 共用对外地址 → 握手 `wrong_gate`，或被 5.4 的同地址去重合并掉 | schema 强制按序号区分；A22 兜底 |
| H26 | chart 不设缺省 Ingress class，使用者必须显式指定 | 手册写明；CI 指定 traefik |
| H27 | 运行期门禁是行为变化：没设运行模式、秘密又短、或用 root 连库的环境都会拒启 | CHANGELOG 与交付说明写清；start-slice 与 compose 缺省 dev |
| H28 | compose 里 Vector 挂 docker.sock 等于拿到宿主 root | 只用于 CI 与单机；K8s 用 DaemonSet、只读 hostPath、最小 RBAC |
| H29 | 按需注册的计数器在 `increase() > 0` 里漏第一次 | §4.8 第 3 条 |
| H30 | A7 会把三方库（Kafka、Dubbo、Redisson）的 ERROR 也算进去 | scene 已把 Kafka 压到 WARN（`xm-scene application.yaml:123-126`），其他进程按压测结果调；阈值标未校准 |
| H31 | scene-manager 的告警（A42–A48）没有 zone 维度 | runbook 写清如何从领导者日志定位 zone；玩家可见的情形由 A25 / A38 按 zone 报 |
| H32 | GHCR 首次推送的包可见性（默认私有还是继承仓库，不确定）；推 `.github/workflows/**` 需要凭据带 workflow 权限 | 用户在 GitHub 设置里确认；Claude 不经手凭据 |
| H33 | Docker Hub 匿名拉取限流（infra 镜像、promtool / Vector / Loki 镜像） | 全部钉 digest；遇 `toomanyrequests` 先重跑，频繁出现再考虑镜像代理 |
| H34 | `release.yml` / `release-push.yml` 只能用户触发 | 交付说明给出要点的按钮；Claude 用匿名 REST 读结论 |
| H35 | scene `maxSurge: 100%` 滚动时 zone 的 scene 资源翻倍 | 资源紧的集群把 maxSurge 改为 1 |
| H36 | 5.4 的 X16 之前，全局一个 login 会把非 zone 1 建的角色记成 zone 1 | 多 zone K8s 部署以 X16 为前置；CI 双 zone 用例等它 |
| H37 | battle-roll 每台最多等一整局（300 s）加余量 | 维护窗口里批量做；`--force` 才作废在打的局 |
| H38 | 区服目录行可被直接删除（`DELETE /admin/zones/{id}`，`ZoneAdminController.java:114` 不检查区内数据，`data-tools-spec.md:961` 已记） | `xm-ops` 关区只置 MAINTENANCE / CLOSED，从不调 DELETE；手册写明不要手工删 |
| H39 | K8s Service 链接变量污染 `XM_` / `KAFKA_` 前缀（§0.6 #22），只在 K8s 上出现，本机与 compose 都发现不了 | 全部 Pod `enableServiceLinks: false`；preflight 检查渲染结果；CI `printenv` 断言；Service 命名纪律（§7.1） |
| H40 | `job` 与 `application` 不一致时所有按 `job` 写的告警静默失效 | A12 + CI 守卫（C9） |

---

## 9 建议的有意差异（相对基线）

| # | 差异 | 基线 | Java | 理由 | 客户端可见？ |
|---|---|---|---|---|---|
| K1 | 编排工具 | 5.5k 行 PowerShell 生成器，替换占位后 apply | Helm（三 chart + library）+ `xm-ops` 模块 | §6 选型；工具用 Java 写（AGENTS §1）；声明式与命令式分开；配置入库天然「粘滞」 | 否 |
| K2 | 按 zone 的范围 | zone 内 9 类工作负载 | 只有 gate、scene | login / gateway / scene-manager 等不分 zone（X16）；基线每 zone 一份 gateway 只是重复了全局状态（B13） | 否 |
| K3 | namespace | 每 zone 一个；关区 = 删 namespace | 一个环境一个应用 namespace，每 zone 一个 release | Secret 只存一份；开关 zone 不碰别的；按 zone 回滚 | 否 |
| K4 | 发现 | etcd | K8s Service DNS + Dubbo 直连；不部署 Nacos | §5.3 | 否 |
| K5 | infra | 集群内 7 类组件，用于真实部署 | 3 个 StatefulSet，只 dev / CI / 演示；生产托管 | 数据安全、PITR | 否 |
| K6 | 不建库 | 开区按 zone 建 `zone_<id>_db` | 单库 `xm_java`，行按 zone_id 区分 | 存储方案不同（AGENTS §3） | 否 |
| K7 | scene | 可拆两池、Deployment / Fleet、无 PDB、无 resources、缺省滚动 | 单池 Deployment；先起全部新实例；PDB 1；宽限期按公式 | 5.3 不分池；5.5 疏散保证滚动不丢人（修 B19） | **是（行为）**：发版时玩家经历疏散换节点而不是掉线，口径同 5.5 已登记；契约不变 |
| K8 | gate / battle 入口 | podip / external；地址由 shell 或 SDK 给 | 四种模式，Java 按序号算端口与主机 | 无 shell 包装；保住 PID 1；可单测 | **是（只是取值）**：assign-gate 的 `gate_ip / gate_port`、124 重定向、battle 票据地址；形状不变 |
| K9 | readiness 与 Service | C++ tcp readiness；每序号 Service 按 readiness 摘 endpoint | 每序号 Service `publishNotReadyAddresses: true`；gate / battle readiness 不叠加排空 / 准入 | §1.6.3：保证排空与关准入期间持票重连可达 | 否（防止回退成可见故障） |
| K10 | Agones | scene / battle 可选 Fleet | 不引入 | §5.1 | 否 |
| K11 | 分发 | 仓库外制品根 + 离线 tar + fetch / import / retention | GHCR（用户触发推送）+ 短期 Actions 制品；按 digest 部署 | 没有离线目标机；GHCR 不支持不可变 tag，只能按 digest | 否 |
| K12 | 发布号注入 | 依赖插件写入顺序覆盖 `build.version`；Go 用 ldflags | Maven 版本不动，加 `build.release`（缺省 `unreleased`） | 不依赖插件实现细节（`java/gateway_node/pom.xml:155-160`） | 否 |
| K13 | 发布流水线 | 只跑 pwsh 契约测试，不推、不跑 preflight | 要求同提交 CI / Integration 已绿 + 对发布镜像整栈冒烟；推送拆到单独 workflow，只它拿 `packages: write`；推送后在同一流程记 digest | 修 G6、G7；最小权限 | 否 |
| K14 | manifest | 表摘要只算 json | 多出契约 commit、表 manifest 摘要、战斗指纹、迁移头、chart 版本 | 双版本追溯；回滚判断 | 否 |
| K15 | 门禁 | 扫仓库 yaml；「5 处一致」；退出码 2 未实现 | 进程启动自检 + 部署前检查 Secret / values / 制品；改查「两两不同」；退出码 2 显式实现 | 修 G1–G5；Java 秘密只从环境变量进（AGENTS §3）；拒启比发布前跑脚本可靠 | 否 |
| K16 | 日志 | C++ 文件 + Alloy sidecar；K8s 上 Go / Java 无采集；一律 7 天；pod 是标签 | stdout JSON + Vector；审计流 30 天；pod 进行内 | Alloy 不到 2 万 star；兜底日志是回灌来源；pod 作标签每次滚动都造新流 | 否 |
| K17 | 告警 | PrometheusRule CR、Go 指标名、进程打 `zone_id`、CI 不测、仓库不部署 Prometheus | 纯规则文件 + promtool + 目录守卫；zone 来自抓取目标标签；job == application 守卫；不做日志告警；compose 里部署 Prometheus 并在 CI 求值 | 可测试；不依赖 operator；守住基数约束 | 否 |
| K18 | 开区 | 不登记区服目录、不建库 | 先维护 → 就绪 → 冒烟 → 开放或预告；gateway 不再自动播种 | 修 B8 | **是（行为）**：新区先以维护 / 预告出现在区服列表（503 `zone_maintenance` / `zone_not_open`，码同基线），就绪后才开放；契约不变 |
| K19 | 关区 | 直接删 namespace（断线） | 先维护 → 可选公告等待 → 停 gate（断线）→ 卸载 → SREM → 留 MAINTENANCE（或 `--final closed`） | 修 B8 | **是（时序）**：先拦新登录再断线；之后 assign-gate 回 503 `zone_maintenance` / `zone_closed`，码同基线 |
| K20 | battle 滚动 | Agones drain 标签：先关新房、打完再回收 | 运维排空接口 + battle-roll | 没有 Agones；不做就会作废在打的局 | 否（在打的局照常结算） |
| K21 | 观测台安全 | 匿名即 Admin、admin/admin、发布到 0.0.0.0、看板 UI 可改 | 不开匿名、口令走 `:?`、只发布到 127.0.0.1、以文件为准 | AGENTS §3 | 否 |
| K22 | 秘密 | 大多数在 ConfigMap 明文；账号口令与注入值不同源 | 全部在 Secret，按进程最小授权；infra 账号口令与应用侧同源 | 修 B1–B4 | 否 |
| K23 | 配置 | ConfigMap 遮蔽镜像配置 | 只用 env 覆盖占位符 | 少一整类漂移 | 否 |
| K24 | 资源与安全 | C++ BestEffort；没有 NetworkPolicy；Service 链接变量缺省开启 | memory request = limit 必写；CPU request + ActiveProcessorCount；NetworkPolicy；`enableServiceLinks: false`；只读根 | 修 B6、B14；H39 | 否 |
| K25 | 探针 | C++ 只有 tcp readiness、无 liveness；startup 300 s | actuator 分组 + 领域 readiness + 租约 / 逻辑线程 liveness；startup 180 s | Java 看得到内部状态；Java 新实例不等旧租约 | 否 |
| K26 | 验证 | 离线渲染为主；kind 上部分实跑 | k3s 真部署两个 zone + robot + 运维演练，CI 每日 | 本机无 Docker，CI 是唯一执行者 | 否 |
| K27 | 数据回档 | `FLUSHDB` zone 的 Redis | 不做 `FLUSHDB`；按区清理归 7.2b / 7.3 的工具，排除 `xm:node-id-epoch:*` | Java 各 zone 共用一个 Redis 键空间 | 否 |

---

## 10 开放问题（各带推荐答案）

| # | 问题 | 推荐答案 | 理由 |
|---|---|---|---|
| Q1 | Java 版上不上 K8s / Agones | **上 K8s，不上 Agones** | 双版本纪律；基线有这条路径；compose 整栈留作单机演示 |
| Q2 | Helm、Kustomize，还是纯 YAML + Java 生成器 | **Helm** | 30.3K、同类最高；渲染、schema 校验、release 历史、回滚现成 |
| Q3 | CI 用什么集群 | **k3s**（钉版本） | 34.1K 同类最高；自带 NetworkPolicy、Traefik、ServiceLB、local-path |
| Q4 | K8s 里的注册中心 | **Service DNS + Dubbo 直连，不部署 Nacos** | §5.3；scene 本来只支持直连 |
| Q5 | infra 是否随 chart | **xm-infra 只给 dev / CI / 演示；生产外接托管** | 托管 MySQL / Redis / Kafka 是生产常态，地址都是环境变量 |
| Q6 | 一个 chart 还是多个 | **三个 + 一个 library** | 生命周期与 namespace 不同；zone 要能独立装卸 |
| Q7 | namespace 怎么分 | **一个环境一个应用 namespace + 一个 infra namespace；每 zone 一个 release** | §5.2 |
| Q8 | gate / battle 对外形态 | **StatefulSet + 每序号 Service（`publishNotReadyAddresses: true`），四种暴露模式；CI 用 hostIP（zone 1）与 podIP（zone 2）各跑一遍，publicHost / loadBalancer 只做渲染测试** | 令牌绑 gate；与基线 external 同形；保留源 IP |
| Q9 | gate / battle 的 readiness 语义 | **不叠加排空 / 准入；只表示「已开始服务、未开始停机」；readiness 只控制滚动节奏与 PDB** | §1.6.3：否则排空与关准入期间持票重连被挡在门外（更正上一版） |
| Q10 | 通告地址怎么算 | **Java 进程从 `XM_POD_NAME` 序号 + 模板计算，配置键沿用现有 advertise 系列**（J1 / J2） | 可单测；算错就拒启；保住 PID 1 |
| Q11 | scene 用 Deployment 还是 StatefulSet；滚动怎么配 | **Deployment，`maxSurge: 100%`、`maxUnavailable: 0`**（资源紧可改 1） | 身份来自 Redis 租约，与序号无关；scene 没有对外入口；100% surge 让每个玩家至多迁一次 |
| Q12 | gate 的 PDB 用 0 还是 1 | **缺省 0，values 可改 1** | 驱逐会不经排空断掉一整台 gate；代价是挡 drain / autoscaler（H4），同基线 D87 |
| Q13 | 跨 zone 的 nodePort 冲突 | **`zones/zone-<id>.yaml` 显式写 `portBase`，CLI 校验不重叠，再 `--dry-run=server` 预演** | 照搬基线 `:2507-2533` |
| Q14 | scene / battle 的 liveness 加不加逻辑线程卡死判据 | **加，30 s 阈值，约 60 s 后重启** | 基线 C++ 因 tcp 看不出卡死而不加（`:1610`）；Java 看得到逻辑线程 |
| Q15 | 节点号 / 发号租约永久丢失时 liveness 是否 DOWN | **是**（gate、battle（房间归零后）、login、team、guild、trade、scene-manager、data、match） | 代码注释写明「需要重启」；Redis 长时间不可用时的 CrashLoop 是可接受代价（H21） |
| Q16 | startupProbe 预算 | **180 s（5 s × 36）**，不照搬 300 s | Java 新实例占新号、不等旧租约（§0.6 #21）；只覆盖冷启动 |
| Q17 | 要不要 battle 运维排空（6.2 Q15） | **要**（J6），且不改 readiness | 否则每次发版都作废在打的局，客户端可见 |
| Q18 | 单节点房间上限 `xm.battle.max-rooms`（6.2 Q7 留给 7.6，`battle-node-spec.md:1516`） | **7.6 不加**，7.4 压测后再定 | 连接上限已兜住负载；没有数据定值 |
| Q19 | 上不上 HPA | **7.6 不上** | gate / scene / battle 缩容必须先排空 / 疏散，HPA 绕过这一步；全局服务没有压测数据（YAGNI） |
| Q20 | 上不上 NetworkPolicy | **上** | 修 B14；同 part-of 粗放行的维护成本低 |
| Q21 | MySQL 账号 | **`xm_app`（§7.5 权限）；prod 下用 root 拒启（两层）；infra initdb 用同一个 Secret 值建账号** | 7.1 §6.2 交来；最小权限；同源修 B3 |
| Q22 | 备份 CronJob | **不移植** | infra 只给 dev / CI；生产托管 PITR；基线自己有 B9 |
| Q23 | PriorityClass | **chart 留值、缺省空**；手册建议给 gate / scene / battle 配高优先级 | 集群级对象，托管集群常受限 |
| Q24 | 资源怎么配 | **memory request = limit；CPU 只写 request + ActiveProcessorCount；初值见 §5.4，7.4 压测后改** | CPU limit 的 CFS 节流造成 scene 帧尾延迟；不写 memory limit 堆按节点算 |
| Q25 | 哪些全局进程能多副本（K8s 稿 Q7） | **生产起点：gateway、login、scene-manager、社交五服、match 各 2；data 1（Recreate）；battle 2；CI 主 job 全 1，另有「2 副本」job 跑冷启动与社交 robot**；暴露问题的服务临时降 1 并登记 | §0.6 #15 已逐个核对定时任务的多副本设计；Triple 长连接下 ClusterIP 只起故障转移作用，不按请求均衡（H14） |
| Q26 | 关区顺序、终态；是否清 `xm:world:{z:Z}:*` 等键 | **维护 → 可选公告等待 → 先停 gate 再卸载 scene → 只 SREM `xm:world:zones` → 缺省留 MAINTENANCE（`--final closed` 可选）；不清别的键、不调 DELETE** | 先 gate 后 scene 免得 scene 停机时还要疏散；MAINTENANCE 可逆，CLOSED 是产品决定；按区清键并入整区回档（`tools.md:434-444`），必须排除 `xm:node-id-epoch:*` |
| Q27 | 开区时区服目录怎么写 | **xm-ops 经 xm-data `/admin/zones` 写，显式带 `manualStatus=1`；K8s 上关掉 gateway 的 seed-zones**（J12） | seed-zones 只在 gateway 启动时补缺，并以 OPEN 插入；`manualStatus` 为 null 也按 OPEN |
| Q28 | 发布 / 部署工具做成单文件还是模块（7.1 Q8、`tools.md:452`、盘点开放问题 6 `tools.md:597`） | **都进 `xm-ops` 模块**；7.6 的子命令是普通 `main`，不起 Spring 上下文；7.3 / 7.4 的合服、巡检子命令以后在同一模块里按需起 Spring | 要 JUnit 覆盖规则与状态机；共用 `ProductionGate`；解析 YAML / JSON；以库依赖调 `ContractGate`；一个运维入口比三个好找 |
| Q29 | 发布号要不要写进 Maven 版本 | **不写**，用 `build.release`（缺省 `unreleased`） | jar 不发布到 Maven 仓库；`versions:set` / flatten 不满足 §2；`${revision}` 让 install 出去的 pom 无法单独解析 |
| Q30 | CHANGELOG 与首个版本 | **中文 Keep a Changelog；每段写需要的库迁移 `Mn`；7.6 完成后由用户打第一个版本 `v0.1.0`** | 基线同口径；回滚判断需要迁移头 |
| Q31 | 推送放在哪 | **单独的 `release-push.yml`，用户看过制品后触发；远端已有同名 tag 就拒绝；平时不推快照** | 最小权限；与基线「人决定推送」一致；推的就是冒烟过的那批镜像 |
| Q32 | 镜像路径与可见性 | **`ghcr.io/luyuan-java/<模块>`；建议公开**（仓库公开，镜像里没有秘密，表数据也是公开契约），由用户首推后在网页上确认 | 不需要 pull secret；私有时 chart 支持 `imagePullSecrets`（只需 `read:packages` 令牌，由用户在集群外建） |
| Q33 | 离线包 | **不另做**：发布 run 的 `images.tar` 制品（保留 7 天）就是离线包；不做仓库外制品根与 fetch / import 脚本 | 没有隔离网络的目标机；YAGNI |
| Q34 | 发布 workflow 里要不要重跑全部测试 | **不重跑单测，要求同提交 CI / Integration 已绿；用发布镜像重跑整栈冒烟** | 测源码的证据可复用，测镜像的证据不可复用 |
| Q35 | 签名 / SBOM / 扫描 / 多架构 | **推迟；只出 amd64**；以后先以「只报告」方式加 trivy（38.2K 满足 §2） | 基线也不装（`release.yml:12-17`）；不影响正确性 |
| Q36 | prod 下秘密过短就拒启，算不算破坏性改动 | **算，而且要这样做**；dev / test 只 WARN | fail-closed；不配运行模式的部署本来就按 prod 跑（H27） |
| Q37 | `ProductionGate` 缺省按 prod 生效：没设运行模式的集成测试会不会被拒启 | 测试统一设 `xm.run-mode=test`；7.6a 先跑一遍全量 `./mvnw -B test` 摸清受影响面再提交 | 改动面一次看清 |
| Q38 | prod 要求 Kafka broker ≥3 | **prod FAIL、staging WARN**；单机演示用 staging 档 | 同基线，但不挡演示 |
| Q39 | 日志采集器 | **Vector** | 22.7K 同类最高；读 stdout 不要 sidecar |
| Q40 | 日志存储用 Loki（29.0K）还是 Elasticsearch（78.2K，严格按 §2 应选它） | **Loki，在 tech-stack 写偏离说明，需用户确认**。用户不同意时退一步：仓库不选存储，CI 里 Vector 写 file sink 做断言，回灌只读 JSONL | ES 堆至少 1–2 GB，而唯一的执行环境是已经跑着十几个 JVM 的 CI runner；按标签查询够用；值班查询与基线 LogQL 同构；Java 代码与存储解耦（回灌只读 JSONL） |
| Q41 | 观测栈装在哪 | **不进游戏 chart**：仓库给规则、看板、Vector / Loki 配置和 CI 用的 compose；集群里用各自官方 chart | 生命周期与游戏服无关；多数集群已有观测栈 |
| Q42 | 告警接收端 | **仓库只交付规则与路由约定，不部署也不依赖接收端；环境里建议用 Alertmanager** | alertmanager 8.6K 不满足 §2（视为 Prometheus 项目的配套组件，同组织、无 ≥2 万的替代）；CI 用 `ALERTS` 验证就够 |
| Q43 | 日志缺省格式与保留 | **容器里 JSON（镜像 ENV），本机文本；`xm.audit.*` 流 30 天，其余 7 天** | 采集要 JSON，开发要可读；与审计 topic 保留期对齐 |
| Q44 | K8s 上审计兜底怎么回灌 | **J10：解析器认 JSON 行；从 Loki 按固定时间窗导出 JSONL，按内容去重** | 容器日志会轮转，Loki 是唯一持久副本；按文件去重防不住重复导出 |
| Q45 | 规则按 `job` 还是按 `application` 聚合 | **按 `job`，加 job == application 守卫（A12 + CI）** | `up` 只有 `job`；只用一种写法，读规则的人不困惑 |
| Q46 | zone 维度从哪来 | **抓取目标标签**；architecture §11 补一句「进程不打 zone，部署层的目标标签可以」 | 守住基数约束 |
| Q47 | scene-manager 要不要按 zone 打标签 | **不加** | 守住项目已有决定；要改先改架构文档 |
| Q48 | 没有基线数据的阈值 | **语义对得上的沿用基线数值（1800、0.05/s、0.01/s、0.5、10×），标「未校准」，7.4 压测后回填** | 基线自己也没校准过 |
| Q49 | 新指标的告警由谁加；AGENTS 要不要写 | **引入指标的那一批同批加规则与 promtool 用例；连同「新进程模块同时登记 chart / NetworkPolicy」「K8s Pod 一律 `enableServiceLinks: false`」「K8s 与观测结论以 CI 为准」「CHANGELOG [Unreleased]」写进 AGENTS §4，交用户过目** | 防「指标有了、告警没有」；H39 只在 K8s 上出现 |
| Q50 | 要不要时钟偏差指标 | **要**（J9）；不引入 node_exporter | 租约依赖墙钟，这是最便宜的守卫 |
| Q51 | 生产保留期（`data-ops-spec.md:1101`） | **流水 180 天、LOGIN / LOGOUT / PERIODIC 快照 90 天、GM / 安全快照永久，写进 `values/prod.example.yaml`；prod 为 0 时 preflight WARN** | 采纳 7.2 的建议值；0 只是容量问题 |
| Q52 | PARITY 版本基线怎么写 | **首个发布起按 `vX.Y.Z ↔ mmorpg commit` 登记** | 现在每行都是 0.1.0-SNAPSHOT，区分不开 |
| Q53 | K8s 上要不要支持挂卷换表（7.1 §3.7） | **不支持**；换表 = 出新镜像 | 「镜像 = 代码 + 同一提交的表」才能追溯 |
| Q54 | 多个环境能不能共享一个 Redis | **不能**；一个环境一套 Redis / MySQL / Kafka | 租约与代次键里没有环境段（`tools.md:416`） |
| Q55 | k8s CI 的频率 | **静态检查每次 push；k3s 端到端每日 + `deploy/helm/**` / `deploy/observability/**` / `xm-ops/**` 变动时 + 手动** | 端到端约 30–40 分钟 |
| Q56 | 发布说明的契约差异（7.5 CQ13 设想由 `tools/Release.java` 复用 `ContractGate`） | **由 `xm-ops release manifest` 以库依赖调用 `ContractGate`**，比「上一个发布 tag → 当前」 | 工具形态已改为模块（Q28）；一份实现 |
| Q57 | 集群内压测、robot 镜像、压测编排（7.4 Q35 / Q36 交来） | **7.6 不做**：robot 在 runner 宿主上跑（经 Ingress 与 NodePort 已覆盖真实入口）；chart 留一个可选的集群内 robot Job 入口，缺省关；压测编排等 7.4 的 `summary.json` 落地后另起 | 7.1 Q6 / D18 同口径；YAGNI |
| Q58 | 巡检要不要只读从库 / 只读账号（7.4 Q7） | **7.6 只在 `prod.example.yaml` 留 `xm.data.consistency.datasource` 的位置与 `xm_ro` 说明，不默认启用** | 从库与否由环境决定 |
| Q59 | 基线的 B3 / B4 / B5 / G1 | **写成 mmorpg 待修项（建议同批修）；不阻塞 Java，Java 的 preflight 与 `ProductionGate` 把对应规则内建（口令同源、逐进程核对 Redis 口令、zones 文件解析器认行尾注释、门禁检查真实注入值）** | 这四个都会让基线 staging / prod 部署失败或被迫跳过门禁 |
| Q60 | 本机要不要装 helm / promtool | **不装**，全部交给 CI；需要加快迭代时再请用户批准下载 | 下载需用户批准 |

---

## 11 验证计划

### 11.1 已经做过的取证

- 本稿引用的基线 file:line 逐条读过原文；§0.6 的 32 条都回到代码核对过：`mysql.yaml` 行号；`release_common.ps1:648-660` 占位串与 `k8s_deploy.ps1:440-458` 回落值（B3）；`k8s_deploy.ps1:1517-1520` 与
  `redis_manager.cpp` 无 AUTH（B4）；`zones.ops-recommended.yaml:10-11`、`:41-42` 与回退正则 `:4698`（B5）；`login.yaml:261-267`、`:298` 与 `release_preflight.ps1:131`（G1）；`scene_manager_service.yaml:128`、`base_deploy_config.yaml:110`（G2）；
  `AssignGateMetrics` 预注册与 `AssignGateResponse` 的码；`GateMetrics` 的握手 / 链路枚举；`SceneMetrics` 的 SLO 桶、存储写结局、存储池 executor 指标、频道 gauge；`WorldChannelMetrics` 的 tick 结局；
  `SceneDirectoryProvider.AssignResult`；`LoginHttpMetrics` 预注册；`FallbackLines` 正则与 `FallbackReplayer` 去重键；`AuditFallbackLog` 的 logger 与级别；各定时任务的多副本注释；`xm-battle application.yaml` 的通告地址；
  `ZoneAdminController` 的缺省 OPEN 与端点行号；`GateDrainAdminController` 的 409；12 个 yaml 的 `management.metrics.tags.application`、停机阶段、运行模式读取；`ConfigurationProperties` 前缀与 `XM_*_PORT` 占位符；
  pom 的 build-info 写法；Boot 3.5.16 的结构化日志键与 `NullAdditionalPropertyValueException`；`git remote`（`luyuan-java`）；Dockerfile 的 JRE digest。
- §4.6 中 A1–A64 引用的指标名与标签值都对照过定义类。

### 11.2 本机验证（没有 Docker / Helm / kubectl）

| # | 批次 | 验什么 | 怎么验 / 判据 |
|---|---|---|---|
| V1 | 7.6a | `ProductionGate` | `./mvnw -B -pl xm-common -am test`：31 / 32 字节边界（去空白、按 UTF-8 计）；占位串大小写；单字符重复；两两相同；未设置；dev 只 WARN；运行模式写错按 prod；日志级别与 exposure 规则；汇总行不含秘密 |
| V2 | 7.6a | 各进程启动拒绝 | 每个进程模块一条启动测试：prod + 31 字节 `XM_DUBBO_SECRET` → 上下文在**任何端口绑定之前**失败、错误只含变量名；dev 能起并捕获 WARN；login 的 prod 运行模式 + `xm.login.mode=dev` 拒启 |
| V3 | 7.6a | build-info 缺省值（§0.6 #9） | scratch 副本里把 `xm.build.release` 设成空串跑 `./mvnw -pl xm-chat -am package`，**预期失败**（`NullAdditionalPropertyValueException`），证实推断；改成 `unreleased` 后 `build-info.properties` 有 `build.release=unreleased` |
| V4 | 7.6a | `ReleaseRules` | `./mvnw -B -pl xm-ops -am test`，照搬基线 `release_common_version.tests.ps1` 的断言：`V1.2.3`、`v01.2.3`、`v1.2.3\n`、全角数字、`1.2.3` 都拒，`v1.2.3-rc.1` 过；CHANGELOG 缺段 / 重复 / 空段 / 只有链接行都拒，`[1.2.3-rc.1]` 与 `[11.2.3]` 不命中 `1.2.3`；tag 128 过、129 拒，黑名单不区分大小写；sha256sums 缺文件、哈希不符、多文件、含 `..`、反斜杠、重复、CRLF 都判失败；退出码 0 / 1 / 2 各有用例 |
| V5 | 7.6a / b | `xm-ops` 编排逻辑 | 假 `CommandRunner` 断言 zone-open / zone-close / gate-roll / battle-roll 的命令序列与金样一致（zone-open 的目录请求带 `manualStatus=1`；zone-close 缺省不 PUT CLOSED、从不 DELETE）；NodePort 段重叠、越界、副本超 portBlock、宽限期低于公式、Secret 缺键、`publicHost`+Local、可变 tag、Pod 缺 `enableServiceLinks: false`、每序号 Service 缺 `publishNotReadyAddresses`，都在**任何写命令之前**以 1 退出；参数错 2；gate-roll 中途身份变化 → 撤回标记并中止；`zones` 文件行尾注释照样能解析（B5 的反例） |
| V6 | 7.6a | `release check` 实跑 | scratch 里克隆本仓库：干净 + 有 CHANGELOG 段 → 0；脏树、缺段、`v1.2`、tag 指向别的提交 → 1；参数错 → 2。不碰真实工作区 |
| V7 | 7.6b | 序号与通告地址 | `PodOrdinal`：`xm-gate-z101-3` → 3；`xm-gate`、`x--1` → 需要时拒启；base 30000 + 序号 3 → 30003；`advertise-port` 与 base 同配拒启；`{ordinal}` / `{zone}` 模板；battle 同理 |
| V8 | 7.6a | 可用性指示器 | 替身租约与时钟：gate 停机开始 → readiness DOWN，**打排空标记 → readiness 仍 UP**；租约 lost → liveness DOWN；scene 没有 ACTIVE 频道 / 疏散中 → readiness DOWN；`logicLoop` 卡 31 s → DOWN、29 s → UP；**battle 准入 CLOSED（运维排空）→ readiness 仍 UP**，停机开始 → DOWN；租约丢且房间归零 → liveness DOWN |
| V9 | 7.6a | 结构化日志 | `OutputCaptureExtension` 起最小上下文：`XM_LOG_FORMAT=logstash` 时含 `@timestamp`、`level`、`logger_name`、`thread_name`、`message`、`service`（gate / scene 另有 `zone`），`stack_trace` 截断到上限；为空时是文本 |
| V10 | 7.6a | 兜底回灌（J10） | 同一条记录分别以文本与 logstash JSON 两种形态写出，`extra` 含引号、反斜杠、`=`：两种形态解析出逐字段相同的 `TransactionLogRow`；logger 不是 `xm.audit.fallback` 的 JSON 行判 Other；JSONL 模式下同一行在两个文件里各出现一次只入库一次（含 `tx_id = 0`）；文本格式回归不变 |
| V11 | 7.6a | 指标目录与守卫 | `./mvnw test` 写出各模块 `target/metrics-catalog.txt`；`xm-ops obs check` 退出 0。**反例**：scratch 副本把一条规则改成不存在的指标名、或把 `increase()>0` 规则指向未预注册组合，退出 1 |
| V12 | 7.6a | 本机切片真实效果 | 起切片：①`XM_RUN_MODE=prod` 且秘密 16 字节 → 拒启，日志只有变量名；②各进程 `/actuator/health/{liveness,readiness}` UP，`/actuator/info` 的 `build.release` = `unreleased`；③`XM_GATEWAY_SEED_ZONES_ENABLED=false` 时空库不插 zone 1；`XM_GATEWAY_TRUSTED_PROXIES` 空串与 `10.42.0.0/16,10.43.0.0/16` 都能绑定；④`redis-cli DEL` gate 的租约键并等过一个 TTL → gate liveness DOWN；⑤`XM_POD_NAME=xm-gate-z1-1 XM_GATE_ADVERTISE_PORT_BASE=30000` → 目录里客户端端口 30001（robot smoke 此时连不上，证明下发的确是新地址），不设时行为不变；⑥再设一个伪造的 `XM_BATTLE_RPC_PORT=tcp://10.0.0.1:21200` 启 xm-battle → 启动失败（演示 H39 的故障形态） |
| V13 | 7.6a | 宽限期公式 | xm-scene 启动打出的下限 N 与 `xm-ops` 用同一组三时限算出的值相等，且 ≤ 60 |
| V14 | 7.6a | 时钟偏差指标 | 本机 Redis 下 `xm_redis_clock_offset_seconds` 绝对值 < 0.1 |
| V15 | 7.6b | battle 排空接口 | 无令牌 503、无操作人 400、prod 运行模式下仍可用；调用后 `xm_battle_admission_phase` = 2、目录 `accepting=false`、readiness 仍 UP；新开战 `NOT_ALLOCATABLE`，在打的房间照常结束、期间凭原票重连成功 |
| V16 | 7.6a | 全量回归 | `./mvnw -B test`（Q37：测试设 `xm.run-mode=test`） |

### 11.3 CI

所有 workflow 沿用 7.1 §4.2：`ubuntu-24.04`、`permissions: contents: read`（另有说明的除外）、只用 GitHub 官方 action 并按 SHA 钉死、下载的二进制与镜像钉版本并核对 sha256 / digest、不使用 repository secret（随机生成并 mask）、
每种失败转成 `::error` annotation（job 日志匿名读不到，7.1 §11.5）、失败时上传 `kubectl get events`、各 Pod 日志与 `describe` 为制品。

| # | workflow / job | 触发 | 判据 |
|---|---|---|---|
| C1 | `ci.yml` / build（扩展） | 同 7.1 | `verify` 之后跑 `xm-ops obs check`，退出 0 |
| C2 | `ci.yml` / `obs-static`（新） | 每次 push / PR | 钉 digest 的官方镜像跑工具：promtool `check rules` 与 `test rules`（alertname 集合 = 用例覆盖集合）；看板每个 expr 生成临时 recording rule 再 `check rules`；`loki -verify-config`；`vector validate --no-environment` 与 `vector test`（JSON 行、文本行、堆栈续行、`xm.audit.*` 路由到 `stream_class="audit"`；标签集里没有玩家号与 pod）；看板 JSON 可解析、uid 唯一 |
| C3 | `k8s.yml` / `k8s-static`（新） | 每次 push | 钉版本 helm；`helm lint` 三个 chart；按 `values/ci.yaml`、`values/prod.example.yaml` × 两个 zone 样例 × 四种暴露模式渲染；静态守卫：没有 `latest`、每个容器有 resources 与探针、ConfigMap 与 values 里没有秘密键名、每个 Pod `enableServiceLinks: false`、每序号 Service 带 `publishNotReadyAddresses`、pom 里每个进程模块都在 chart 里登记；**反例必须失败**：`tag: latest`、缺 memory limit、宽限期低于公式、`publicHost`+Local、loadBalancer 多副本主机不含 `{ordinal}`、配了 Ingress 没配 trustedProxies、删掉一个 `enableServiceLinks`；xm-infra 的镜像 digest 与 `deploy/compose/infra.yaml` 逐字相同 |
| C4 | `k8s.yml` / `k8s-e2e`（新） | 每日、路径命中的 push、手动 | ① 环境自检；② 装钉版本的 k3s 与 helm（核对 sha256），等节点 Ready；③ 打包、构建镜像，`docker save \| sudo k3s ctr -n k8s.io images import -`（infra 镜像同样导入）；④ 全部渲染结果 `kubectl apply --dry-run=server` 通过；⑤ 生成秘密 → `xm-ops deploy secrets`；`deploy preflight --profile dev` 退出 0，**同一份 values 用 `--profile prod` 必须退出 1**；⑥ `platform-up --with-infra`；`zone-open --zone 1`（hostIP，gate 2、scene 2）；`zone-open --zone 2`（podIP，gate 1、scene 1；等 X16）；⑦ 断言：全部 Pod Ready；每个 Pod `/actuator/info` 的 `build.commit = $GITHUB_SHA`；`kubectl exec <pod> -- printenv` 里没有 `XM_.*_SERVICE_HOST`、没有 `KAFKA_PORT`；xm-scene 日志里的宽限期下限 ≤ 渲染值；日志里没有 `Read-only file system`；Java 进程用 `xm_app` 连库全部起来；NetworkPolicy：`default` namespace 里的临时 Pod 连 `xm-login:20881` 失败，`monitoring` namespace 里的临时 Pod 能抓 `xm-gate-z1-0:18103/actuator/prometheus` |
| C5 | 同上 / 运维演练 | 同上 | **滚 gate**：robot `soak` 在线时 `gate-roll --zone 1`：排空期间持票断线重连成功（§1.6.3 的反例）；新 assign-gate 不分到正在排空的 gate；结束后标记清掉；新 Pod 通告地址与旧 Pod 相同。**滚 scene**：`helm upgrade xm-zone-1` 只改一个注解：`xm_scene_storage_writes_seconds_count{result=~"failed\|rejected"}` 增量为 0、robot 货币前后一致、每个玩家换节点不超过 1 次；旧 Pod 的「停服写回完成」行用时 < 宽限期。**battle-roll**（6.2 后）：关准入期间在打的局凭原票重连成功。**滚 login / friend**：期间 robot 的 1003 比例 ≤ 1%（H14）。**驱逐**：对 gate Pod 调 eviction API 回 429；对 scene 连续驱逐两次，第二次 429。**关区再开区**：`zone-close --zone 2` 期间 assign-gate 回 `zone_maintenance`；`--final closed` 时回 `zone_closed`；`zone-open --zone 2` 后同一账号登录，角色与货币都在。**Redis 重启**：删 `redis-0` 前后 `xm:node-id-epoch:*` 不回退，持租约进程按 Q15 重启后 robot 恢复。**回滚**：`helm upgrade` 改一个环境变量后 `rollback`，环境变量复原。**终止中的 gate**：`kubectl delete pod` 后的宽限期内，EndpointSlice 里仍有它（H20） |
| C6 | 同上 / GM 与运维通道 | 同上 | 经 `kubectl port-forward` 调 gate 的 `GET /gm/identity` 回 200（转发进来的是回环来源）；从另一个 Pod 直接调回 403 |
| C7 | 同上 / 反例 | 同上 | 删掉 Secret 里一个必需键后部署：对应 Pod `CreateContainerConfigError`，且 `xm-ops` 在此之前就拒绝；两个 zone 的 portBase 重叠：preflight 在写之前拒绝 |
| C8 | `k8s.yml` / `k8s-2replica`（新） | 每日、手动 | 空库冷启动：全局服务除 data 外全部 2 副本、zone 1（gate 1、scene 1），全部 Ready（H9）；robot 第一期 + friend / chat / team / guild / trade（B11 的反例：帮会、组队必须可用） |
| C9 | `integration.yml` / stack + 观测（扩展） | 同 7.1 | stack 叠加 `observability.yaml`。robot 第一期跑完后：Prometheus `/api/v1/targets` 全部 up、`/api/v1/rules` 全部 `health=ok`；`group by (job, application)(process_start_time_seconds)` 每一对 `job == application`（A12 不响）；规则与看板引用的每个指标至少有一条序列（按需注册的白名单除外）；`xm_gateway_assign_gate_total{code="0"}`、`xm_gate_handshakes_total{result="ok"}`、`xm_scene_audit_records_total{result="acked"}`、`xm_data_kafka_records_total{outcome="inserted"}` 都 > 0；`ALERTS{alertstate="firing"}` 为空。**故障注入**：`stop xm-chat` → 60 s 内 `up{job="xm-chat"}==0` 且 A2 pending；`stop xm-scene` 后 3 分钟内 A25 firing；停 Kafka 制造一条兜底审计 → Loki 里出现 `{stream_class="audit"}` 行，`xm-ops obs export-fallback` 导出、`AuditFallbackReplay --format jsonl` 回灌后 `transaction_log` 有这一行，再回灌一次不重复。Loki：`{service="xm-gate"}` 有数据且 `\| json` 能解析，`/labels` 是白名单子集；Grafana：6 个看板、2 个数据源 |
| C10 | `release.yml` 试跑（用户触发，`v0.1.0-rc.1`） | 手动 | `release check` 通过；镜像断言与整栈冒烟通过；制品有 manifest、notes、sha256sums、chart 包、images.tar；本机对下载的 manifest 与 sha256sums 跑 `xm-ops release verify` 退出 0。**反例**：CHANGELOG 没有对应段 → 第 2 步就失败 |
| C11 | `release-push.yml` 试跑（用户触发） | 手动 | load 后镜像 ID 都等于 manifest；digest 文件覆盖全部镜像且与 `imagetools inspect` 一致；`deploy preflight --profile prod --release-manifest --digests` 的 H 节通过；**同一版本再推一次必须被拒** |

`k8s.yml` 的骨架（细节以落码为准）：

```yaml
name: K8s
on:
  push: {branches: [main]}
  schedule: [{cron: '0 20 * * *'}]           # UTC 20:00，避开 integration 的 19:00
  workflow_dispatch:
permissions: {contents: read}
concurrency: {group: k8s-${{ github.sha }}, cancel-in-progress: false}
jobs:
  k8s-static:   {runs-on: ubuntu-24.04, timeout-minutes: 15}          # C3
  k8s-e2e:                                                              # C4–C7
    if: github.event_name != 'push' || <changed paths 命中 deploy/helm/**、deploy/observability/**、xm-ops/**、本文件>
    runs-on: ubuntu-24.04
    timeout-minutes: 75
  k8s-2replica: {if: github.event_name != 'push', runs-on: ubuntu-24.04, timeout-minutes: 60}   # C8
```

路径判断用 `git diff --name-only ${{ github.event.before }} ${{ github.sha }}` 在 job 内完成（不用 `paths:` 过滤，免得 required check 永远 pending，同基线 `go-modules-ci.yml:237-243` 的教训）。

### 11.4 robot 计划（只覆盖客户端可见的部分：地址取值、区服状态、断线与迁移）

robot 跑在 runner 宿主上：`--gateway http://127.0.0.1`（k3s 的 Traefik 经 ServiceLB 占宿主 80 端口，Ingress 链路被真实走一遍）；`--data-url`、`--scene-metrics-url` 经 port-forward。
除一个可选的新场景 `soak --seconds N`（保持在线、统计断线重连、1003 与每人换节点次数，配合滚动演练）外，全部复用现有场景，不改客户端契约。

| # | 场景 | 判据 |
|---|---|---|
| R1 | `smoke`、`token`、`movement`、`audit`（zone 1，hostIP） | assign-gate 的 `gate_ip` 是节点 InternalIP、`gate_port` = portBase + 序号；握手成功；`xm_gate_handshakes_total{result="wrong_gate"}` 保持 0 |
| R2 | 两个 gate 地址互不相同 | 多次 assign-gate 拿到两个不同端口；拿 gate-0 的票据去连 gate-1 回 `wrong_gate`（反例） |
| R3 | 排空期间重连 + `gate-roll` | 被排空 gate 上的会话断线后凭原票据在截止前重连成功；deadline 后断开，重走 assign-gate 落到另一个 gate；`currency` 前后一致 |
| R4 | scene 滚动（5.5 落地后） | 只有换场景推送，没有断线；收到的 79 / 23 口径同 5.5；货币不丢；每人换节点 ≤ 1 次 |
| R5 | `zones` 扩展 | PREVIEW 时 503 `zone_not_open`、MAINTENANCE 时 `zone_maintenance`、开放后 OK、`--final closed` 后 `zone_closed`；区服列表状态与目录一致；关掉 seed-zones 后新区先以维护出现 |
| R6 | `travel --zone 1 --visit-zone 2`（5.4 落地后） | 124 里的地址是 zone 2 某个 gate 的 Pod IP（podIP 模式）；回到 zone 1 后地址是 hostIP 模式的 |
| R7 | prod 口径（gate / scene / trade 运行模式 prod，login 保持 dev 以便 robot 登录） | `currency --expect-gm deny` 通过；trade 播种回 403；这些进程在 prod 下能起来，证明 `ProductionGate` 对合规配置（64 位随机秘密、`xm_app`、Redis 口令）放行 |
| R8 | `friend` / `chat` / `team` / `guild` / `trade`（`k8s-2replica` job） | 全局服务 2 副本下全部通过 |
| R9 | `queue` / `ratelimit`（经 Ingress，最后跑） | 配了 trustedProxies 后按真实对端 IP 计桶，不再全体共用一个桶 |
| R10 | `battle-smoke`（6.2 / 6.4 落地后） | 票据地址是 battle 序号对应的入口；`battle-roll` 期间在打的局凭原票重连成功、正常结算，新开局被派到其他节点 |

### 11.5 反例与守卫一览

| 守卫 | 在哪验证 |
|---|---|
| 秘密过短 / 占位 / 复用 → 拒启；login 开发口令 + prod → 拒启 | V1、V2、V12；C4 的 prod preflight 反例 |
| Secret 缺键 → `CreateContainerConfigError`，且工具先拒 | C7 |
| 缺 memory limit、宽限期低于公式、可变 tag、暴露模式组合非法、缺 `enableServiceLinks: false` → 渲染失败 | C3、V5 |
| Service 链接变量污染 | V12 ⑥、C4 `printenv` |
| 排空 / 关准入期间持票重连 | V8、V15、C5、R3、R10 |
| NodePort 段重叠 → preflight 拒 | V5、C7 |
| 规则引用不存在的指标、`increase()>0` 指向未预注册组合 | V11、C1 |
| 规则没有对应 promtool 用例 | C2 |
| job ≠ application | A12、C9 |
| 告警真的会响 / 健康时不响 | C9 |
| JSON 日志下兜底回灌正确、不重复 | V10、C9 |
| build-info 空值炸构建 | V3 |
| 同一版本二次推送被拒 | C11 |
| 模块清单漂移（xm-ops 不该出镜像；每个进程模块都在 chart 里） | 7.1 的守卫（排除 xm-ops 后仍相等）、C3 |

### 11.6 交付口径

- 本机只能验 V 项；K8s、compose、观测栈、发布 workflow 的结论都以 CI 为准，提交说明写「K8s / 观测栈未在本机运行，结论以 CI run `<id>` 为准」，并列出本机跑过的 V 项与结果。
- CI 结论按 7.1 §11.5 用匿名 REST 读；`release.yml` / `release-push.yml` 由用户点，Claude 只读结论。
- 规则与 compose / chart 只有推上去才能验证：CI 暴露的问题用后续提交修，标题写「批次 7.6a/b 修正：…」；不得用 `@Disabled` 或跳过 job 绕过。
- 交付说明写明 mmorpg 侧状态：「mmorpg 已有 K8s 编排 / 发布 / 门禁 / 日志告警；建议同修 B3、B4、B5、G1（可选，不阻塞 Java）」。
