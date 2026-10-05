# K8s 开区编排、发布制品与门禁、日志 / 告警（批次 7.6）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties` 的 `mmorpg.commit` 相同。本地副本是 `D:\work\mmorpg` 的稀疏克隆。本稿用到的
> `deploy/**`、`tools/scripts/**`（含 `lib/`、`tests/`）、`docs/ops/**`、`docs/design/**`、`.github/workflows/**`、`java/gateway_node/**`、`go/shared/**` 都已检出。
> - **唯一缺的目录是 `bin/`**（不在稀疏检出里）。`k8s_deploy.ps1` 与 `release_preflight.ps1` 都读 `bin/etc/*.yaml`，用 `git show HEAD:bin/etc/base_deploy_config.yaml` 读到：
>   `:5-6` KeepaliveInterval 1 s、NodeTTLSeconds 180；`:92-93` 命令 topic 256 分区、第 2 代；`:126` GateMaxConnections 20000。
> - Unity 客户端（`D:\work\mmorpg-client`，单独的稀疏克隆）本批用不到：7.6 不改客户端契约，只改 gate / battle 通告地址的**取值**。assign-gate 应答形状见
>   `docs/reference/mmorpg-client-contract-robot.md:77`（`gate_ip / gate_port / token_*`）。
> - **没有因为目录缺失而定不下来的结论。**
>
> **Java 侧**：以 2026-10-05 的工作区为准，HEAD `aa8b5b5`。未提交的并行改动有 5.2（xm-scene / xm-gate / xm-scene-manager）、6.2（新模块 `xm-battle`）、
> 7.1a / 7.1b（`.github/`、`deploy/compose/`、`deploy/docker/Dockerfile`、根 pom 的 build-info）、7.2（xm-data 运维）。这些文件提交后行号会漂移，引用时同时写类名或配置键。
> `docs/design/architecture.md` 在本稿编写期间仍被并行批次修改（从 876 行涨到 1017 行），对它一律写「§节 + 编写时的行号」。
>
> **路径约定**：以 `mm:` 开头的路径在 mmorpg 里，其余在 Java 仓库里。`xm-<模块> application.yaml:N` 指该模块 `src/main/resources/application.yaml` 的第 N 行。
>
> **盘点 id**：deploy-k8s-zone-orchestration（`docs/porting/inventory/java-infra.md:333-343`）、release-packaging（`:345-355`）、observability-logs-alerts（`:357-367`）、
> release-preflight（`docs/porting/inventory/tools.md:446-456`）。关联条目：k8s-zone-deploy（`tools.md:410-420`）、gate-drain-ops（`:422-432`）、
> zone-rollback-kafka-reset（`:434-444`）、gateway-ops-hardening（`java-infra.md:201-211`）、agones-*（`:285-307`）。路线图 `docs/porting/roadmap.md:96`。
>
> **本稿的来历**：由三份分区稿合并而成：K8s 编排盘点与设计；发布制品、门禁、日志、告警的盘点与设计；Java 映射稿。三稿互相矛盾、或与代码不符的地方，
> 都回到代码重新核对过，更正列在 §0.6。全程只读，除本文件外没有改任何文件。
>
> **实测**（2026-10-05，全部只读）：
> 1. star 数由分稿用 GitHub API 实测：helm 30.3K、kustomize 12.2K、k3s 34.1K、minikube 32.2K、kind 15.5K、agones 7.1K、prometheus 66.4K、
>    prometheus-operator 10.0K、alertmanager 8.6K、grafana 77.1K、loki 29.0K、elasticsearch 78.2K、kibana 21.3K、vector 22.7K、logstash 15.0K、
>    fluentd 13.6K、elastic/beats 12.7K、fluent-bit 8.1K、otel-collector 7.6K、grafana/alloy 3.6K、traefik 65.1K、ingress-nginx 19.5K、
>    external-secrets 6.9K、kubeconform 3.2K、trivy 38.2K、cosign 6.3K、syft 9.6K、goreleaser 16.1K、jreleaser 1.2K、versions-maven-plugin 367、
>    flatten-maven-plugin 223、logstash-logback-encoder 2.5K、kubernetes-client/java 4.0K、fabric8 kubernetes-client 3.7K。
>    编辑复核时匿名额度已经用尽（`/rate_limit` 的 core 为 60/60），没有重测；凡是结论依赖 star 的地方都标了出处。
> 2. 本机已构建的 Boot jar 每个 79–98 MB，12 个进程 jar 合计约 1.0 GB；`config-data/tables` 约 82 KB。
> 3. 基线计数：`mm:deploy/k8s/scene-manager-alerts.yaml` 有 19 条 `- alert:`，`owner-epoch-alerts.yaml` 有 2 条；
>    `k8s_deploy.ps1` 5583 行、`lib/k8s_client_entry.ps1` 2086 行、`release_preflight.ps1` 656 行、`lib/release_common.ps1` 706 行、
>    `k8s_gate_drain.ps1` 1237 行、`.github/workflows/release.yml` 442 行。
> 4. Java 代码里注册的 `xm.*` 指标名共 180 个（`grep '"xm\.[a-z0-9_.]+"'`），本稿每条 7.6 告警引用的指标名与标签值都对照过定义类（§4.6）。

---

## 0 概览与范围

### 0.1 结论速览

| # | 问题 | 结论 |
|---|---|---|
| 1 | Java 版上不上 K8s（盘点开放问题 7，`java-infra.md:389`；7.1 Q10 留给本批） | **上 K8s，不上 Agones**。Helm chart 加 Java 运维 CLI 模块 `xm-ops`；正确性靠 CI 里一次性 k3s 集群上的真部署 + robot 证明。compose 整栈（7.1）继续作为单机演示形态 |
| 2 | 清单怎么生成 | **Helm**（30.3K，同类最高；Kustomize 12.2K）：三个 chart 加一个 library chart，只管声明式部分。命令式步骤（区服目录、排空、等待、门禁）放在 `xm-ops`，它只调 `helm` / `kubectl` 和 xm-data 的运维接口。5583 行的 PowerShell 生成器不移植 |
| 3 | 哪些东西按 zone 部署 | **只有 xm-gate、xm-scene**。login、gateway、scene-manager、社交五服、data、battle、match 都是全局一份（`zone-travel-spec.md:821-834`）。一个 zone = 一个 Helm release `xm-zone-<id>` |
| 4 | namespace | 每个环境一个应用 namespace（缺省 `xuanming-java`），放全局服务和全部 zone；依赖单独放 `<ns>-infra`，而且只给 dev / CI / 演示用。生产外接托管的 MySQL / Redis / Kafka |
| 5 | 服务发现 | **K8s Service DNS + Dubbo 直连 URL**（`tri://xm-login:20881`，与 compose 同名）；K8s 上不部署 Nacos。按节点的调用（gate→scene 链路、资产通道、match→battle 控制面）照旧从 Redis 节点目录取 Pod IP |
| 6 | gate / battle 对外入口 | StatefulSet + 每序号一个 Service + PDB `maxUnavailable: 0`，与基线 external 同形。通告地址由 Java 进程按 Pod 序号自己算（四种模式，§5.5）。**同一 zone 的每个 gate 必须有自己独立的客户端地址** |
| 7 | scene | 单池 Deployment。滚动 `maxSurge: 100% / maxUnavailable: 0`：先起全部新实例再停旧实例，每个玩家至多被疏散一次。PDB `maxUnavailable: 1`。宽限期由模板按 5.5 的 C2 公式求下限，不低于 60 s |
| 8 | 探针 | 全部打 actuator 分组端点，管理端口在容器里绑 0.0.0.0。readiness 叠加领域判据：gate「在接客」、scene「身份有效 + 不在疏散 + 有 ACTIVE 频道」、battle「准入开着」。liveness 叠加「节点号租约永久丢失」和 scene / battle「逻辑线程卡死 30 s」 |
| 9 | 秘密 | 只进 Secret，按进程最小授权用 `secretKeyRef` 注入，缺键时 Pod 卡在 `CreateContainerConfigError`；值不进 Helm values，由 `xm-ops deploy secrets` 从不入库的 env 文件经 stdin 建 |
| 10 | 配置 | 镜像里的 `application.yaml` 是唯一配置源；K8s 只用环境变量覆盖占位符（7.1 §6.4 的规则），不挂配置文件 |
| 11 | 版本号与制品 | `vX.Y.Z[-pre]`，规则同基线。Maven 的 `project.version` 不动，发布号经 build-info 的 `build.release`、OCI `version` label 和 `/app/BUILD_INFO` 注入；镜像 tag `vX.Y.Z-<sha12>`。手动触发的 `release.yml` 产出 manifest、发布说明、sha256sums、images.tar；**用户**另行触发 `release-push.yml` 推 GHCR。CI 和 AI 都不打 git tag |
| 12 | 门禁 | 两层。① 进程启动自检 `ProductionGate`：prod 运行模式下，秘密缺失 / 过短 / 占位串 / 跨信任域复用、开发口令登录开着、GM 远程开着、DEBUG 日志、危险 actuator 端点、MySQL 用 root，任一项都拒启。② 部署前 `xm-ops deploy preflight`：tag 不可变、Secret 齐全、values 自洽、制品一致。退出码 0 / 1 / 2 三者都真能走到 |
| 13 | 日志 | Spring Boot 自带的结构化日志（`logstash` 格式，字段同基线 gateway）；容器里缺省 JSON，本机仍是文本。采集用 **Vector**（22.7K，同类最高），读 stdout，不要 sidecar。存储用 **Loki**（对 §2 的偏离，需用户确认，Q37）：缺省保留 7 天，审计流 30 天 |
| 14 | 告警 | 纯 Prometheus 规则文件 + `promtool test rules` 单测；不做日志告警；zone 维度由抓取目标标签给出。基线 21 条里 11 条改写或合并成 Java 告警、10 条不适用；Java 告警约 85 条（A1–A60 随本批落地），其余按指标所属的批次分期落地（§4.6） |
| 15 | 看板 | 6 个文件化看板（6.x 落地后再加 1 个），数据源用模板变量；静态守卫保证规则和看板里引用的每个指标名都真实存在、并在启动时注册 |
| 16 | 验证 | 本机：单测与本机切片（§11.2）。CI：新 workflow `k8s.yml`（k3s 真部署两个 zone + robot + 运维演练 + 反例）、`ci.yml` 加 `obs-static`、`integration.yml` 整栈加观测冒烟、`release.yml` 试跑（§11.3） |
| 17 | 分批 | **7.6a**（不碰集群，7.1b 之后）：门禁、日志、指标与规则、看板、`xm-ops` 的 release / obs、发布 workflow。**7.6b**（要集群；6.2、5.5 与 5.4 的 X16 之后）：Helm、通告地址、battle 排空、`xm-ops deploy`、`k8s.yml` |

### 0.2 7.6 交付什么

1. **K8s 编排**：`deploy/helm/**`（xm-lib / xm-infra / xm-platform / xm-zone，加 values 与 zones）、NetworkPolicy、PDB、探针、宽限期。
2. **运维 CLI 模块 `xm-ops`**：`release`（check / manifest / verify / digests）、`deploy`（secrets / preflight / images / platform-up / zone-open / zone-close / status /
   gate-roll / gate-scale / battle-roll / battle-scale / rollback）、`obs`（check / export-fallback）。
3. **发布制品**：`CHANGELOG.md`、`.github/workflows/{release,release-push}.yml`、build-info 的 `build.release`。
4. **门禁**：xm-common 的 `ProductionGate` 与 `StartupBanner`；12 个进程（6.4 落地后 13 个）各一个显式调用点；`xm-ops deploy preflight`。
5. **观测**：12 个进程的结构化日志；`deploy/observability/**`（Prometheus 规则与单测、抓取配置、Loki、Vector、Grafana 看板）；`deploy/compose/observability.yaml`。
6. **Java 代码改动 J1–J14**（§5.14），全部不改客户端契约。
7. **CI**：`k8s.yml`；`ci.yml` 加 `obs-static` job；`integration.yml` 的 stack job 加观测冒烟；整栈冒烟步骤抽成 `deploy/ci/stack-smoke.sh`，供 `release.yml` 复用。
8. **文档与对账**：tech-stack、architecture §6 / §11、`docs/ops/{k8s-runbook,release,alerts}.md`、PARITY 新增四行并修订两行、盘点注记、AGENTS §4 提案（交用户过目）。

### 0.3 与 7.1 的边界

| 项 | 7.1 已定或交付 | 7.6 做什么 | 7.6 对 7.1 决定的调整 |
|---|---|---|---|
| compose | `infra.yaml` / `stack.yaml`，`XM_RUN_MODE` 缺省 dev | 加 `observability.yaml`（与 stack 同项目叠加）。生产形态走 K8s，不另出生产 compose | stack 的模块漂移守卫（`deploy-ci-spec.md:746`）排除观测服务；pom 侧的集合 A 排除 xm-ops |
| 镜像 | 参数化 Dockerfile、快照 tag `<sha12>`、不推送 | 发布轨 tag、推 GHCR（用户触发）、只读根文件系统、`ENV XM_LOG_FORMAT=logstash` | 无 |
| 版本 | `build.version` = `project.version`，另带 40 位 commit 与 contract | `build.release`、CHANGELOG、manifest | 7.1 §5.4 留下的「`-Drevision` 或 `versions:set`」两条都不用（Q26） |
| 工具 | 7.1 Q8 设想单文件 `tools/Release.java` | 改为模块 `xm-ops`（Q25） | 同上，漂移守卫排除 xm-ops |
| 秘密 | 环境变量矩阵（`deploy-ci-spec.md:868-878`）、compose `:?`、CI 随机生成 | prod 启动自检、K8s Secret、轮换、最小权限 MySQL 账号 | 7.1 §6.2「生产账号由 7.6 定」→ `xm_app`；compose infra 加可选应用账号，供 prod 运行模式演示（Q18） |
| 健康 | 探针分组、`info` 端点 | 领域 readiness / liveness 指示器、`terminationGracePeriodSeconds` | 7.1 §0.3 写「管理端口绑 Pod IP」，改为 **0.0.0.0**：`kubectl port-forward` 在 Pod 网络里连的是回环地址，只绑 Pod IP 会让运维通道和 GM 接口都用不了；GM 接口自己按对端地址只收本机（architecture §6，编写时 `:622`），放宽绑定不会把它暴露出去 |
| 网络 | compose 网络内按服务名互访 | NetworkPolicy、gate / battle 对外入口 | 7.1 §0.3 列的 `DUBBO_IP_TO_BIND` 不需要：只做直连的进程不设通告地址时 Dubbo 本来就绑 0.0.0.0（`deploy-ci-spec.md:444-447`），入站由 NetworkPolicy 收口 |
| 观测 | 不做 | 全部 | — |
| CI | ci / integration / contract-drift、TestReport | `k8s.yml`、`obs-static`、观测冒烟、发布 workflow | integration.yml 的 stack 步骤改为调用 `deploy/ci/stack-smoke.sh`（与 release.yml 共用，不各写一份） |

### 0.4 与并行批次的关系与前置

| 批次 | 当前状态 | 7.6 的依赖 / 接口 |
|---|---|---|
| 7.1a / 7.1b | 工作区，未提交 | 7.6a 必须等 7.1b（build-info、Dockerfile、stack、占位符）提交之后 |
| 5.2 | 工作区 | 告警 A61 / A62 的指标已在 `SceneMetrics`（`TRANSFERS`、`TRANSFER_POST_FREEZE_MUTATIONS`），随 5.2 提交一起生效 |
| 5.3 | 规格 | 镜像 / 实例相关告警随 5.3 落 |
| 5.4 | 规格 | **X16（`CreatePlayer` 的归属区取会话 zone，`zone-travel-spec.md:1020`）是多 zone K8s 的硬前置**：全局只有一个 login，不做 X16 的话，zone 2 建的角色会记成 login 进程配置的 zone（`xm-login application.yaml:72`、`LoginConfiguration.java:250`）。CI 的双 zone 用例要等它 |
| 5.5 | 规格 | scene readiness 的身份与疏散判据、宽限期公式 C2（`scene-drain-spec.md:1021`）、§7.3 告警（`:1063-1066`） |
| 6.2 | 工作区（xm-battle） | **battle 通告地址拆分（7.1 Q9，`deploy-ci-spec.md:1037`）是 7.6b 硬前置**；准入闸（`AdmissionPhase`）是运维排空接口的基础 |
| 6.3 / 6.4 | 规格 | 告警随各批落；xm-match 进 xm-platform chart |
| 7.2 | 工作区 | 生产保留期（`data-ops-spec.md:1101` Q11）、运维作业告警（`:951-956`）、兜底回灌工具（J10 要改它） |
| 7.4 | 规格（`data-tools-spec.md`） | 交给 7.6 的：集群内压测与 robot 镜像（`:1044` Q35）、压测编排（`:1045` Q36）、巡检只读账号（`:1016` Q7）、巡检报告的历史靠日志采集（`:1015` Q6）。处置见 Q55、Q56 |
| 7.5 | 规格（`contract-navmesh-spec.md`） | CQ13（`:891`）：发布说明复用 7.5 的 `ContractGate`（模块 `xm-contract-check`）写「上一个发布 → 当前」的契约面差异；7.6 的落点是 `xm-ops release manifest`（不是 7.5 设想的 `tools/Release.java`），见 Q54 |

**提议写进 AGENTS §4 的规则（Q45，交用户过目）**：从 7.6b 起，任何新增进程模块的批次，都要同时登记 `stack.yaml`、`start-slice.sh`、`env.example`、
`deploy/helm/xm-platform`（或 xm-zone）和 NetworkPolicy；任何新增指标的批次，同批提交告警规则和 promtool 用例。

### 0.5 本机约束与结论来源

本机没有 Docker、Helm、kubectl、Go、python、node；有 JDK 21、Maven 和 GitHub Actions。结论按来源分三类：

- **本机可验**：Java 单测、本机切片（`tools/local/start-slice.sh`）、`xm-ops` 的纯逻辑（§11.2）。
- **只能 CI 验**：Helm 渲染与部署、k3s 上的 robot 与运维演练、promtool / Vector / Loki 配置校验、观测栈冒烟、镜像推送（§11.3）。结论按 7.1 §11.5 的办法用匿名 REST 读取。
- **只能用户触发**：`release.yml` / `release-push.yml` 是 `workflow_dispatch`，需要令牌，Claude 触发不了；GHCR 包的可见性由用户在网页上设。

按 AGENTS §4「没有运行证据不得声称通过」，提交说明要写明「K8s / compose / 观测栈未在本机运行，结论以 CI run `<id>` 为准」。

### 0.6 编辑复核：对分稿的更正

| # | 分稿原说法 | 核对结果 |
|---|---|---|
| 1 | 映射稿：基线 `release.yml` 的脏树检查在 `:239-247`、上传制品在 `:277-288` | 实际是 `:358-375`（`工作树必须干净`）和 `:396-407`（`retention-days: 90` 在 `:407`） |
| 2 | 发布稿：`release.yml` 全文没有 `release_preflight` 字样 | 只在 `:401` 的注释里出现过一次，**没有调用**。结论（发布 workflow 不跑 preflight）不变 |
| 3 | 发布稿与映射稿：assign-gate 失败率告警按 `code=~"500\|503"` 算 | **503 是区服状态**：`zone_maintenance` / `zone_closed` / `zone_not_open`（`AssignGateResponse.java:43-60`、`AssignGateMetrics.java:33-59`），开关区流程本身就会产生；算进去会在每次维护时响 critical。只算 `code="500"`（`no_gate_available`、`gate_directory_unavailable`、`zone_admission_unavailable`、`queue_unavailable`、`internal_error`） |
| 4 | 映射稿：`XmGateSceneLinksDown`（`xm_gate_scene_links == 0` 即告警） | gate → scene 链路是**按需建的**（`SceneLinkManager.send` 里 `computeIfAbsent`，`SceneLinkManager.java:72-90`），没人在线时 0 条链路是常态。改为看 `xm_gate_link_events_total{event="connect_failed"}`（A22） |
| 5 | 映射稿：CI 集群选 minikube（k3s 没测到 star） | K8s 稿实测 k3s 34.1K > minikube 32.2K > kind 15.5K；k3s 还自带 NetworkPolicy 控制器、Traefik、ServiceLB、local-path。选 **k3s**（Q3） |
| 6 | K8s 稿 `tools/Zone.java`、发布稿 `tools/Release.java`（单文件），映射稿 `xm-ops` 模块 | 合并为一个模块 **`xm-ops`**：要解析 YAML / JSON、要与进程共用 `ProductionGate`、要 JUnit 覆盖状态机（Q25）。盘点 `tools.md:332`、`:440`、`:597` 已把合服、巡检、按区清 Redis 规划进同名模块 |
| 7 | 发布稿：基线版本号正则用 `\z` 结尾 | 版本号正则（`release_common.ps1:300`）用的是 `$`，靠先拒含空白的输入（`:326-328`）堵住「`$` 放过末尾换行」；`\z` 用在 commit 正则（`:303`）。Java 用 `Pattern.matches`（整串匹配）+ `[0-9]`，两个坑都不存在 |
| 8 | 映射稿：scene 身份 TAKEN 且写回完成后 liveness 置 BROKEN | 不需要：5.5 规定 TAKEN 时疏散后 `ProcessExit.exitSoon()` 自行退出（`scene-drain-spec.md:639`），容器退出即由 K8s 重启 |
| 9 | 映射稿：login 的 readiness 加「发号租约有效」 | login 的租约只管建角（无效时 `CreatePlayer` 回 2020，登录 / 进游戏不受影响，`LoginConfiguration.java:212-221`）。放进 readiness 会让一次 Redis 抖动把 login 全部摘掉。改为：readiness 不看它；租约**永久丢失**（`isLost()`，代码注释「需要重启」）时 liveness DOWN |
| 10 | 发布稿：所有 `increase(...) > 0` 告警都靠「启动即注册为 0」 | `xm_scene_gain_anomalies_total` 带动态标签（`category`、`currency_type`），在 `increment()` 时才注册（`SceneMetrics.java:595`），无法预注册。这类计数器的告警改用「新序列」写法（§4.8 第 3 条） |
| 11 | K8s 稿与映射稿：battle 排空有 `DELETE …/drain` 撤销 | 准入闸「只朝一个方向走，CLOSED 是终态」（`AdmissionPhase.java:3`、`:10`）。排空是单向的；要撤销就重启 Pod |
| 12 | 发布稿与映射稿：容器里换成 JSON 日志只影响 grep 脚本 | 还会**弄坏审计兜底回灌**：`FallbackLines` 用 `extra=(.*)` 取到行末（`FallbackLines.java` 的 `TRANSACTION` 正则），JSON 行会把 `","logger_name":…` 吃进 `extra`；去重键又是「文件 SHA-256 + 行号」，从 Loki 重复导出会让 `tx_id=0` 的行重复入库。见 J10、H11 |
| 13 | 三稿都没提 | gateway 启动时按 `seed-zones` 把库里没有的区**以 OPEN 状态**插进区服目录（`xm-gateway application.yaml:69-74`）。K8s 上 gateway 先于 zone 起来，会出现「列表里开放、却没有 gate」的区。K8s 关掉播种（J12） |
| 14 | 映射稿：gate PDB `maxUnavailable: 1` | 取 K8s 稿与基线的 **0**（D87，`mm:tools/scripts/lib/k8s_client_entry.ps1:940`）：驱逐会不经排空就断掉一整台 gate 的玩家。可在 values 里改（Q10） |
| 15 | K8s 稿：Dubbo 提供方宽限期一律 30 s | 这些进程的 `timeout-per-shutdown-phase` 是 20 s（如 `xm-login application.yaml:17`），加 preStop 5 s 已到 25 s。按「preStop + 停机阶段 + 10 s」取 **40 s**；gateway 停机阶段 10 s（`xm-gateway application.yaml:18`）取 30 s；gate 没写停机阶段（缺省 30 s）取 40 s |
| 16 | 发布稿：「13 个进程」 | 现在是 **12 个**进程模块（声明 `repackage` 的 13 个模块去掉 xm-robot），6.4 的 xm-match 落地后 13 个 |
| 17 | 发布稿：基线 21 条「改写或合并 10 条、不适用 11 条」 | 逐条重排（§4.5）：**改写或合并 11 条，不适用 10 条**。#3（实例池空）不适用（Java 不分用途池），#4（主世界池空）改写为 A24 + A36 |
| 18 | 各稿引用的 architecture.md 行号 | 文件在编写期间从 876 行涨到 1017 行，§6 之后整体后移约 140 行。本稿统一写「§节 + 编写时行号」 |
| 19 | 映射稿：`deploy/k8s/README.md` 的 D90 与基线 `release.yml:12-17` 等 | 已逐条核对，无误 |
| 20 | 发布稿：`J3 XmOldGenHigh` 用 `jvm_memory_used_bytes{id="G1 Old Gen"}` | 改用 GC 之后的存活数据 `jvm_gc_live_data_size_bytes / jvm_gc_max_data_size_bytes`（映射稿写法）：原始 used 呈锯齿，阈值不好定 |
| 21 | 映射稿：`XmOwnerClaimErrors` 引用 `outcome="error"` | 核对 `LoginMetrics.ClaimOutcome` 有 `ERROR`，成立 |
| 22 | K8s 稿：读 gate 身份用 `kubectl exec … curl` | 改用 `kubectl port-forward` + JDK `HttpClient`：不依赖镜像里有 curl，对端地址在 Pod 内是回环，GM 身份接口的「只收本机」照样成立（§11.3 C6 实测） |

---

## 1 基线 K8s 开区编排（mmorpg `26ceb70ca`）

### 1.1 入口与门禁

- **命令**：`mm:tools/scripts/k8s_deploy.ps1` 提供 `zone-up|zone-down|zone-status|all-up|all-down|all-status|infra-up|infra-down|infra-status|infra-kafka-topics`（`:8`），
  `dev_tools.ps1` 包装成 `k8s-*`（`dev_tools.ps1:1422-1434`、`:1560`）。
- **相关脚本**：集群外入口的生成器与预检 `lib/k8s_client_entry.ps1`；gate 排空 `k8s_gate_drain.ps1`；区服回档 `k8s_zone_rollback.ps1`（862 行）；
  集群内压测 `deploy/k8s/robot_stress.ps1`（`:1-45`）。
- **只有写操作过门禁**（`:5511-5522`），`*-down` / `*-status` 不过，排障时不会被挡。门禁依次是：不可变 tag（`:365-379`）→ staging / prod 下跑
  `release_preflight.ps1`（`:388-405`）→ 集群外入口的组合与集群现状预检 → 懒解析秘密（`:421-424`）→ C++ gRPC deadline 预算（`:5512-5522`）。
- **tag 与拉取策略**：镜像 tag 缺省用 git 短 sha；显式给 NodeImage 时 Go / Java 的 tag 跟随它（`:318-346`）；拉取策略按 tag 是否可变推导（`:39-42`、`:348-350`）。
- **命名**：zone namespace 是 `<NamespacePrefix>-<ZoneName>`（`:1300-1303`，前缀缺省 `mmorpg-zone`，`:23`）；infra namespace `mmorpg-infra`（`:24`）。
  `-ClusterId` 是 0..31 的集群常量，即雪花 worker 段高 5 位，infra 与 zone 必须同值（`:13-22`）。
- **发布档位** `-ReleaseProfile dev|staging|prod`，缺省 dev（`:38`）。

### 1.2 工作负载总表

**infra namespace（全部 zone 共用）**

| 组件 | 形态 | 探针 | PDB | 宽限 / preStop | resources | 出处 |
|---|---|---|---|---|---|---|
| etcd | StatefulSet×3，Parallel，每成员 8Gi PVC | readiness `/health`；liveness `/health?serializable=true` | minAvailable 2 | 30 s | req 100m / 256Mi | `manifests/infra/etcd.yaml:57-65`、`:67-200` |
| redis | StatefulSet×1 + 8Gi PVC，AOF，可选口令（Secret `redis-auth`） | startup / readiness `redis-cli ping`，PONG 或 NOAUTH 都算就绪；liveness tcp | minAvailable 1 | 30 s | 只写 req 250m / 512Mi | `redis.yaml:47-60`、`:62-155`；Secret 在 `k8s_deploy.ps1:5380-5403` |
| redis-match-cluster | StatefulSet×6 + 建群 Job | — | — | — | — | `redis-match-cluster.yaml:52-59`、`:144-200` |
| kafka | StatefulSet×1 + 20Gi PVC，镜像 `apache/kafka:latest` | startup / readiness 走 API 级探测（探针里压小堆）；liveness tcp | minAvailable 1 | — | req 300m / 1Gi，lim 2 / 2Gi | `kafka.yaml:85`、`:97-129`；README `:976-1032` |
| kafka-topic-init | Job，每次 infra-up 先删再建 | — | — | — | — | `kafka-topic-init.yaml:43-61`；`k8s_deploy.ps1:5448` |
| mysql | Deployment×1，Recreate；20Gi 数据 PVC + 50Gi RWX 备份 PVC；口令明文 | 只有 readiness `mysqladmin ping -h localhost`（走 socket）；**无 liveness** | 无 | 默认 | req 200m / 512Mi，lim 1 / 1Gi | `mysql.yaml:171-195`、`:232-327` |
| mysql-backup | CronJob 03:17 UTC，口令明文，挂数据 PVC | — | — | — | — | `mysql-backup-cronjob.yaml:39`、`:55-64`、`:126-129`；**infra-up 不 apply 它**（`k8s_deploy.ps1:5340-5343`） |
| loki | Deployment×1 + 10Gi PVC，保留 168 h | — | — | — | req 100m / 256Mi，lim 1Gi | `loki.yaml:26-34`、`:89`、`:127-170` |
| battle（全局池） | Deployment / hostPort Deployment / Agones Fleet 三选一 | startup tcp 2 s×150；readiness tcp | 无（Fleet 上 `eviction.safe: Never`） | 30 s | **无** | `k8s_deploy.ps1:1656-1677`、`:5254-5322`；`lib/k8s_client_entry.ps1:988-1180` |
| match / chat / client-rpc-router / trade / friend（Go） | Deployment×2，反亲和 preferred | grpc readiness / liveness | minAvailable 1 | 30 s + preStop `sleep 5` | req 100m / 128Mi，lim 500m / 256Mi | `manifests/go-svc/*.yaml`；目录 `k8s_deploy.ps1:697-734` |
| trade-migrate / friend-migrate | Job，`podFailurePolicy`（K8s ≥ 1.26） | — | — | — | — | `trade-migrate.yaml:22-59`；README `:556-586` |

**zone namespace（每 zone 一套）**

| 组件 | 形态 | 探针 | PDB | 宽限 | resources | 出处 |
|---|---|---|---|---|---|---|
| node-config ConfigMap | base_deploy_config + game_config，只读整目录挂 `/app/bin/etc`，**遮蔽镜像里的配置** | — | — | — | — | `k8s_deploy.ps1:1328-1365`、`:1713-1716`；README `:896-907` |
| gate | podip：Deployment（缺省 2）；external：StatefulSet（Parallel / OnDelete）+ headless + 每序号 Service | readiness tcpSocket；不加 startup / liveness（理由 `:1604-1616`：tcp 看不出 EventLoop 卡死） | external 下 `maxUnavailable: 0`（D87）；podip 下无 | 30 s | **无** | `k8s_deploy.ps1:1618-1630`、`:4947-4953`；`lib:764-962` |
| gate-entry Service | 只在 podip 且 gate 恰为 1 副本时生成（D90） | — | — | — | — | `k8s_deploy.ps1:4994-4999`；`lib:509`；README `:332-337` |
| scene（单池或 world / instance 两池） | Deployment（单池缺省 4）或 Fleet + FleetAutoscaler | readiness tcpSocket；Fleet 上不加 K8s 探针 | **无** | 60 s（`:94`、`:4977`） | **无** | `k8s_deploy.ps1:1632-1654`、`:4955-4992` |
| C++ 日志 sidecar | Alloy 原生 sidecar（initContainers + `restartPolicy: Always`） | — | — | — | req 20m / 64Mi，lim 256Mi | `k8s_deploy.ps1:46-58`、`:1559-1574` |
| db | Deployment×1 | grpc | 无 | 30 s + 5 s | 100m / 128Mi ~ 500m / 512Mi | `go-svc/db.yaml:24`、`:41-70` |
| data-service | Deployment×1，Recreate | grpc | 无 | 30 s | — | `go-svc/data-service.yaml:24-31` |
| login | Deployment×2，反亲和 | grpc | minAvailable 1 | 30 s + 5 s | 200m / 256Mi ~ 1 / 512Mi | `go-svc/login.yaml:24-115` |
| player-locator | Deployment×2，反亲和 | grpc | minAvailable 1 | 30 s + 5 s | — | `go-svc/player-locator.yaml:30-102` |
| scene-manager | Deployment×2，Redis 选主，**无反亲和** | grpc | minAvailable 1 | 30 s + 5 s | 100m / 128Mi ~ 500m / 256Mi | `go-svc/scene-manager.yaml:139-220` |
| gateway（Java） | Deployment×2，反亲和，`JAVA_TOOL_OPTIONS=-Xms64m -Xmx384m` | startup / readiness `/actuator/health/readiness`，liveness `…/liveness`；分组**不含** db / redis | minAvailable 1 | **未写宽限，无 preStop** | 200m / 512Mi ~ 1 / 1Gi | `java-svc/gateway.yaml:24-115`（分组理由 `:63-68`） |
| gateway Ingress | 只在给了 host 时生成，只路由 `/api`；class 缺省 `nginx` | — | — | — | — | `k8s_deploy.ps1:211-223`、`:216`、`:4597-4626` |

### 1.3 一键开区、全量、关区实际做了什么

- **zone-up**（`Apply-Zone`，`k8s_deploy.ps1:4870-5021`）：解析 scene 计划（`:4818-4868`）→ 建 namespace → external 模式先本地渲染 gate 并做服务端
  dry-run，提前暴露 nodePort 冲突（`:4910-4919`）→ 删另一种形态的 gate，会踢人的删除要 `-AllowDisruptiveSwitch`（`:4921-4927`）→ Agones 模式建 SDK RBAC
  （`:4929-4935`、`:2373-2403`）→ node-config 与日志 sidecar 的 ConfigMap → gate → 逐个 scene 池（Agones 模式加 FleetAutoscaler）→ 按条件建 gate-entry →
  zone 内 Go 服务（ConfigMap → Secret → Deployment，`:4071`、`:4377`、`:4121`）→ Java gateway（`:4562-4628`）→ `-WaitReady` 时依次等待；Agones 模式不等，只打印命令
  （`:2635-2695`、`:2660-2669`）→ 换模式时提示人工删旧对象（`:5008-5018`）。
- **all-up**：先 infra（除非 `-SkipInfra`）再逐个 zone（`:5551-5561`）。
- **infra-up**（`:5324-5474`）：先渲染 battle 的 ConfigMap，让密钥错误发生在任何写之前 → apply 5 个清单（含三处「先删同名 Deployment 再 apply StatefulSet」的
  kind 迁移）→ topic-init Job → 等待 → battle 池 → 全局 Go 服务。
- **zone-down = `kubectl delete namespace`**（`:5023-5029`）：不打维护标记、不排空、不踢人（AGENTS `:56`、README `:440`）；玩家数据在 infra 的 MySQL，删 namespace 碰不到。
  `infra-down` 删 infra namespace，PVC 一起删（README `:441`、`:463-464`）。
- **回滚**：用旧 tag 重跑 `zone-up` / `all-up`（`docs/ops/k8s-open-server-runbook.md:139-162`）；紧急关区就是 `zone-down`（`:164-169`）。
- **「一键开区」并不完整**：
  - `zone_<id>_db` 只在 MySQL PVC 首次 initdb 时按 zones 配置建（`k8s_deploy.ps1:5049-5065`、`:5096-5186`；`mysql.yaml:299-305`），已有集群新开 zone 只打一条告警（`:5061`）；
  - 区服目录 `zone_config` 只播种了 zone 1（`deploy/mysql-init/gateway_tables.sql:37-39`），新 zone 不会出现在区服列表里（README `:869-871`）；
  - 告警规则、备份 CronJob、scene-manager 的 Agones RBAC 都不在一键流程里，要手工 apply（AGENTS `:137`；`docs/ops/mysql-backup-pitr-runbook.md:28`）。

### 1.4 配置与秘密

- **配置**：从各服务 `etc/*.yaml` 读权威标量或整块搬运（`:496-508`、`:528+`）。node-config 遮蔽镜像配置，漏写一个键就是线上静默错误，生成器为此全面 fail-closed（README `:896-907`、`:944-947`）。
- **秘密来源**：环境变量 `MMORPG_*` 经 `Resolve-InjectedSecret`（`lib/release_common.ps1:679`）；dev 回落占位值，staging / prod 下缺失、占位串、过短一律拒绝（`k8s_deploy.ps1:425-480`）。
- **但大部分秘密最后进了 ConfigMap 明文**：gate 令牌密钥（node-config `:1431`、gateway ConfigMap `:4538`）、gateway 数据源口令（`:4530`）、各 Go 服务的 MySQL 口令
  （如 `:3165`、`:3605`、`:3720`）、Redis 口令（如 `:3110`、`:3134`）、battle 票据密钥（`battle-node-config`，`:1370`）。真正走 Secret 的只有 gateway 管理口令
  （`:291-305`、`:4597-4614`）、login 开发口令（`:224-228`）、Redis 服务端口令（`:5380-5403`）、`AssetOp.SecretEnv`（`:3659-3662`）。
- **infra 清单里写死的口令**：`mysql.yaml:268-269`、`:282-283`、`:324`；`mysql-backup-cronjob.yaml:61-64`。
- **轮换**：Pod 模板上的口令指纹注解让秘密一变就滚动（`:298-299`、`:4303`）。

### 1.5 扩缩容

- 副本数写在 zones 配置的 `replicas.{centre, gate, scene | scene_world, scene_instance}`（`zones.sample.yaml`、`zones.ops-recommended.yaml:22-42`、`zones.10zones.yaml`），可用命令行覆盖（`:69-84`）。
- `-OpsProfile managed-cloud|bare-metal` 把 gate 抬到 ≥2、scene ≥4、battle ≥2（`:770-798`），拆分池不抬（`:766-769`）。
- `centre` 是遗留字段：解析、打印（`:4760`、`:4899`），不部署任何工作负载；README 的「centre=1」（`:262`）已失效。
- **没有 HPA**；只有 Agones 高密度模式的 FleetAutoscaler（Counter 策略、需 beta 特性，`:100-130`、`:2294-2347`）。频道扩缩容在 scene-manager，管频道数不管 Pod 数。
- zones YAML 用自写的逐行回退解析器，行尾不能带注释（`:4642-4700`；README `:411`）。

### 1.6 集群外入口

- **podip**：login 下发 POD_IP，只有集群内的 robot 连得上（README `:257`、`:381-382`）。单个 gate-entry 只在副本数为 1 时生成：票据绑 `gate_node_id`，多副本时 Service
  随机分流、约一半握手以 `token_gate_node_mismatch` 被拒（README `:332-337`，D90）。
- **external**：gate 是 StatefulSet + 每序号一个 Service（NodePort = base + 序号，`:201`；或 LoadBalancer + 主机模板，`:192`），`externalTrafficPolicy` 缺省 Local（`:204`），
  gate 自报客户端可达地址（`lib:764-909`）；各 zone 的 `gateNodePortBase` 段不能重叠（README `:409-412`，D88）；OnDelete + PDB 0，滚动只能走
  `k8s_gate_drain.ps1`：标 draining → 等 drained → 删 Pod（`lib:755-757`、`:947-957`；`k8s_gate_drain.ps1:1-50`）。
- 地址参数不粘滞：OnDelete 下现有 Pod 仍自报旧地址，每序号 Service 却会立刻改写（`:193-194`）。
- gateway 配了 Ingress 却没配可信代理时预检报错（`:221-227`；README `:400`，D91），否则全体玩家共用一个限流桶。
- 以上全部**未上过集群**（README `:381`、`:433-434`）。

### 1.7 Agones（基线有，Java 不移植）

- 模型是「1 个 GameServer = 1 个 scene 进程 = N 个房间」，`-SceneOrchestrator agones` 打开，缺省关（`:91`；AGENTS `:109-112`）。
- scene Fleet：`portPolicy: None`，无 K8s 探针，健康交给 SDK（`:2184-2272`）；`counters.rooms`（`:2159-2173`）；FleetAutoscaler（`:2294-2347`）；每 namespace 一份 SDK RBAC（`:2373-2403`）。
- battle Fleet：`portPolicy: Dynamic`；`allocationOverflow` 打 `mmorpg.io/drain` 标签排空；`eviction.safe: Never`；EventLoop 心跳 10 s 不更新就停发 health（`lib:965-1095`；AGENTS `:116-127`）。
- 状态是「阶段 B 已落码，未上集群」（AGENTS `:109`）。

### 1.8 验证现状

- **离线**：`-DryRun` 渲染（README `:107`、`:1084-1099`）+ `tools/scripts/tests/k8s_deploy_contract.tests.ps1`（1088 行）等 pwsh 契约测试，CI 在 `deploy-config-tests.yml:73-90` 跑。
- **实跑**：只在 kind 上跑过 A 档（infra）和 B 档（zone 里 Go 五个服务 + Java gateway，C++ 用占位镜像），查出 4 个真 bug（README `:718-881`、`:826-841`）。
- **从未在真 zone 上跑过**：C++ 节点、external 入口、Agones、路由模式、日志 sidecar 端到端（README `:378-379`、`:754`；runbook `:225-228`；AGENTS `:109`）。

### 1.9 基线缺陷与文档漂移（Java 不照搬；建议 mmorpg 同修，不阻塞 Java）

| # | 缺陷 | 出处 |
|---|---|---|
| B1 | 秘密写进 ConfigMap 明文，任何有 ConfigMap 读权限的主体都能拿到 gate 令牌密钥和库口令 | §1.4 |
| B2 | infra 清单写死口令 | §1.4 |
| B3 | C++ Pod 一律不写 resources，是 BestEffort，节点有压力时第一批被驱逐 | `k8s_deploy.ps1:1680-1733`、`lib:806-849`、`:2184-2272` |
| B4 | scene 与 podip 下的 gate 没有 PDB；scene-manager 没有反亲和；db 单副本无 PDB | `scene-manager.yaml:150-156` |
| B5 | 开区不建新 zone 的库，也不登记区服目录 | §1.3 |
| B6 | 备份 CronJob 不在一键流程里，并且挂 MySQL 的 RWO 数据 PVC（`mysql-backup-cronjob.yaml:126-129`），不同节点就挂不上（按 RWO 语义推断）；RWX 备份 PVC 在 kind 上一直 Pending（README `:794`） | — |
| B7 | 浮动 tag：`apache/kafka:latest`（`kafka.yaml:129`）、`bitnamilegacy/etcd:latest`（`etcd.yaml:114`） | — |
| B8 | 遗留物：`go-svc/gateway.yaml` 从不部署（`:697-734` 目录里没有）且与 Java gateway 的 Service 同名；`$JavaSvcCatalogue.auth` 无清单（`:762`）；`centre`；guild 无清单（README `:526`） | — |
| B9 | 文档漂移：README `:17`、AGENTS `:24`、`:82` 说 redis 是单副本 Deployment，实际早是 StatefulSet + PVC（`redis.yaml:62-70`） | — |
| B10 | 关区 = 删 namespace，不经过维护和排空 | §1.3 |
| B11 | 模式参数不粘滞，重跑漏传就静默落回缺省值（AGENTS `:36-37`、`:40`），只有一部分由 `-AllowDisruptiveSwitch` 兜住 | — |
| B12 | 没有 NetworkPolicy，基线自己点名是缺口 | `k8s_deploy.ps1:3655-3658` |
| B13 | login 早于 player-locator 起时 fatal 重启多次，靠后者就绪后自愈 | README `:874-876` |

---

## 2 发布制品与门禁（基线）

### 2.1 两条轨与版本号规则

| 项 | 基线 | 出处 |
|---|---|---|
| 版本号正则 | `^v(0\|[1-9]\d*)\.(0\|[1-9]\d*)\.(0\|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$`；ECMAScript 语义（`\d` 只认 0-9，堵全角数字）；含空白先拒（因为 `$` 放过末尾换行）；必须小写 v，不用不区分大小写的 `-match` | `mm:tools/scripts/lib/release_common.ps1:300`、`:318-338` |
| commit 口径 | 12 位小写 hex，正则用 `\z` | `:303`；`git rev-parse --short=12` 在 `:212` |
| 脏树 | `git status --porcelain` 非空即脏；快照 tag 加 `-dirty` | `:218-221` |
| 镜像 tag | 快照 `<sha12>`（脏树 `<sha12>-dirty`）；发布 `vX.Y.Z-<sha12>`，脏树 throw；超过 128 字符 throw | `:353-381` |
| 可变 tag 黑名单 | latest、dev、develop、main、master、stable、edge、prod、production、release、nightly、snapshot、current（不区分大小写）。理由：覆盖后 `rollout undo` 退回同一 digest | `:186-193`、`:243-246` |
| pull policy | 不可变 tag 用 `IfNotPresent`，可变 tag 用 `Always` | `:283-290` |
| 制品版本目录名 | 快照 `g<sha12>`（脏树另加时间戳）；发布 `vX.Y.Z` | `artifacts_lib.ps1:64-67`；`publish_images.ps1:393-401` |
| 「四处一致」 | git tag = `-Version` = CHANGELOG 的 `## [X.Y.Z]` = 镜像自报版本（OCI label、`/app/BUILD_INFO`） | `mm:CHANGELOG.md:13-14`；`release.yml:4-7` |

### 2.2 制品目录与三条铁律

- **制品根**：`-ArtifactRoot` > `MMORPG_ARTIFACT_ROOT` > `<仓库父目录>/artifacts`，放在仓库外，防止 GB 级 tar 被 `git add -A` 带进去（`artifacts_lib.ps1:74-113`）。
- **布局**：`<root>/{snapshots|releases}/images/<ver>/{images/*.tar, images-manifest.json, build-info.json, symbols/*.debug, sha256sums.txt}`，另有可变的 `latest.json`
  指针和 `releases/manifests/<vX.Y.Z>.json|.md`（`:10-15`；`publish_images.ps1:16-25`）。
- **铁律**（`artifacts_lib.ps1:17-20`）：① 不可变，目录已存在就拒（`:276-293`）；② 原子发布，同级 `.tmp-<leaf>-<PID>` 写完再 `Directory.Move`（`:305-330`）；
  ③ sha256sums 覆盖全部文件，格式 LF、序数排序、无 BOM，与 `sha256sum -c` 兼容（`:176-194`、`:211-259`）。
- **表摘要**：顶层 `*.json` 按序数排序拼成 `"<sha>  <name>\n"` 再算一次 sha256，只认 `.json`（`:415-466`）。
- **publish 流程**（`publish_images.ps1:7-14`）：版本戳（发布轨脏树一律拒，`:376-381`）→ 目录已存在即拒（`:414-430`）→ 构建 → 核对 revision / version label（`:547`、`:572`）
  → **逐个** `docker save` 并读回 RepoTags 自证（批量 save 层链相同的镜像会丢一个，`:28-29`）→ 上线前复查工作树（`:662`）→ 更新 latest（`:675`）。

### 2.3 清单字段与 `make_release.ps1`

| 文件 | 字段 | 出处 |
|---|---|---|
| `images-manifest.json` | `[{family, service, ref, image_id, revision, file}]` | `publish_images.ps1:18-19`、`:612-620` |
| `build-info.json` | version、channel、app_version、vcs、source_rev、commit、dirty、image_tag、families、image_count、tables_sha256、tables_file_count、published_at、machine、publisher | `:639-656` |
| release manifest `<vX.Y.Z>.json` | version、notes、created_at、machine、publisher、source{commit, dirty}、images{version, path, image_tag, image_list, digests}、tables{sha256, file_count} | `make_release.ps1:275-296` |

校验：版本号合法（`:100-104`）；`.json` 存在即拒（`:119-122`）；发布说明来源 `-Notes` > `-NotesFile` > CHANGELOG 段（`:128-152`）；镜像目录过 sha256sums（`:177-182`）；
build-info 身份与目录名、`app_version`、12 位 commit、非 dirty、镜像条数、表摘要逐项核对（`:193-229`）；digest 文件必须恰好覆盖全部 ref 且同 repo（`:237-266`）；
先写 `.md` 后写 `.json`，`.json` 用不覆盖的 Move（`:319-343`）；最后打印 `git tag` 命令交给人执行（`:349`）。

### 2.4 离线交付与快照保留

- `fetch_images.ps1`：源目录先过 sha256sums → 核对 build-info → 复制到 `.fetching-*` → 再校验 → rename（`:6-30`）。
- `import_images.ps1`：缺 sha256sums 默认拒；逐个 `docker load` 后核对镜像 ID，兼顾 containerd 存储下 `.Id` 口径不同（`:6-27`）。
- `artifacts_retention.ps1`：只清 `snapshots/images` 下合规目录，缺省保留 10 个，latest 指向的永远保留，`.tmp-*` 超 24 h 只 WARN，缺省 dry-run，`releases/` 永不触碰（`:4-29`、`:37`）。

### 2.5 `.github/workflows/release.yml`

- 只能手动触发，输入 `version`、`families`（缺省 `go,java`）、`proto2mysql_ref`（`:19-42`）；`permissions: contents: read`（`:44-45`）；全仓串行、不取消（`:47-52`）；
  超时 180 min（`:58-60`）；输入一律经环境变量进脚本（`:61-65`）。
- 步骤：释放磁盘（`:67`）→ 全量检出（`persist-credentials: false`）→ 环境自检（`:83-95`）→ 版本号、CHANGELOG、git tag、镜像族（`:97-148`）→
  `tools/scripts/tests` 契约测试，目录缺失或一个测试都没有就判红（`:150-199`）→ 仓库外 replace 模块（`:201-356`）→ 工作树必须干净（`:358-375`）→
  `publish_images.ps1`（`:377-385`）→ `make_release.ps1`（`:387-394`）→ 上传制品，保留 90 天（`:396-407`）→ Step Summary（`:409-442`）。
- **刻意不做**：不推镜像、不打 tag、不部署、不用 secret、不装 syft / cosign / trivy / goreleaser（`:12-17`）。

### 2.6 CHANGELOG 约定

Keep a Changelog 1.1.0 + SemVer 2.0.0（`mm:CHANGELOG.md:3-5`）：发版把 `[Unreleased]` 改成 `## [X.Y.Z] - YYYY-MM-DD`（方括号里不带 v），再在上面新开 `[Unreleased]`（`:9-10`）；
段落到下一个 `## ` 为止，空或找不到都拒（`:11-12`）；只写对部署和运维的影响（`:15`）。判定函数 `Test-ChangelogReleaseSection`：标题精确匹配（`1.2.3` 不命中
`[1.2.3-rc.1]` 或 `[11.2.3]`），重复段落算失败，链接定义行不算正文（`release_common.ps1:383-438`）。

### 2.7 版本追溯与回滚

- 五个证据点（`docs/ops/release-checklist.md:136-144`）：Go 启动首行 `service starting service=… version=… commit=…`（`go/shared/buildinfo/buildinfo.go:71-72`、`:110-120`）、
  gate 的 `[gate_version]` 行、`/app/BUILD_INFO`、OCI label、registry digest；闭环核对顺序见 `:146-155`。
- 回滚按上一版 manifest 和 digest（`:157-169`）；**数据不随代码回滚**，迁移只前进（`:169`）。

### 2.8 发布门禁 `mm:tools/scripts/release_preflight.ps1`

**语义**：判据是非空、非占位串、长度达标；「两处一致」对占位串免疫，所以不用（`:7-14`）。文件或键缺失一律 FAIL，只有核实过「缺省即关」的开关允许缺失
（`MissingPolicy=pass-off`，`:16-18`、`:215-250`）。dev 档下密钥类、Kafka 类、镜像 tag 类降级为 WARN（`:81`、`:316`）；只有 prod 拒绝 `-dirty` tag（`:82`）。
退出码注释写 0 / 1 / 2（`:25-28`），但全文只有 `exit 1`（`:649`）和 `exit 0`（`:656`），脚本自身错误靠 `$ErrorActionPreference='Stop'`（`:72`）以非 0 退出，**与 FAIL 分不开**。
YAML 只读解析器只支持块式子集（`release_common.ps1:65-134`）。

| 节 | 检查 id | 目标 | 判据 | dev / staging / prod | 出处 |
|---|---|---|---|---|---|
| A | `secret.gate.{cpp,login,scenemgr,loginstack,gateway}` | 5 个文件里的 GateTokenSecret | 非空、非占位、≥32 | WARN / FAIL / FAIL | `:257-269` |
| A | `secret.gate.consistency` | 上面 5 处 | 取值全部一致；可比对的不足 2 处也 FAIL | FAIL | `:271-285` |
| A | `secret.admin.apikey` | gateway `admin.api-key` | ≥16 | 同密钥类 | `:288` |
| B | `secret.mysql.{db,gateway}`、`secret.redis.{db,locator,login}` | 5 个 yaml | ≥12 | 同上 | `:294-299` |
| C | `image.tag.provided` | `-ImageTag` / `-ImageRef` | 必须传 | FAIL | `:305-312` |
| C | `image.tag.immutable`、`image.ref.immutable` | tag | 不在黑名单；prod 下不是 `-dirty` | WARN / FAIL / FAIL | `:318-338` |
| D | `kafka.brokers.{cpp,login,db,locator}` | 4 个文件 | broker 数 ≥3 | 同密钥类 | `:344-348` |
| D | `kafka.partition.match` | login 与 db 的 PartitionCnt | 完全一致 | FAIL | `:351-363` |
| E | `locator.lease.disconnect`、`locator.lease.etcd` | player_locator | ≥30 s | FAIL | `:369-373` |
| F | `ratelimit.{zone.rps,zone.burst,ip.rps}`、`ratelimit.queue.timeout`、`ratelimit.burst.ge.rps`、`ratelimit.enabled.declared` | gateway | ≥1；≥1000 ms；burst ≥ rps；键必须存在、false 时 WARN | FAIL / WARN | `:382-410` |
| G | `debug.jpa.showsql`、`debug.login.devpassword`、`debug.actuator.exposure`、`debug.cpp.loglevel` | gateway / login / base_deploy_config | 不得 true；不得含 `* env heapdump threaddump configprops beans shutdown loggers`；C++ 日志级别 ≥1 | FAIL | `:417-447` |
| H | `release.version`、`release.changelog`、`release.artifact.dir`、`.sha256sums`、`release.buildinfo.{version,clean,commit}`、`release.manifest`、`release.imagetag.commit` | 给 `-ReleaseVersion` 时 | 版本合法；CHANGELOG 恰好一段；sha256sums 一致；build-info 版本与非脏；commit≠HEAD 只 WARN；manifest 合法 JSON（**digests 为空也 PASS**）；tag 形如 `<v>-<sha12>` 且 sha 等于 build-info | FAIL（commit 一项 WARN） | `:466-608` |

### 2.9 部署期的密钥注入门禁 `Resolve-InjectedSecret`

逻辑在 `release_common.ps1:679-706`：dev 可回落占位值，staging / prod 遇到未设置、占位串、过短都 throw。占位串黑名单（`:648-660`）：`change-me-in-production-use-a-strong-random-key`、
`change-me-in-production`、`change-me`、`changeme`、`placeholder`、`todo`、`secret`、`password`、`root`、`123456`、`apppass123`。

| 环境变量 | 最小长度 | 出处 |
|---|---|---|
| `MMORPG_GATE_TOKEN_SECRET`、`MMORPG_INTERNAL_AUTH_SECRET`（dev 回落值刻意与 gate 不同） | 32 | `k8s_deploy.ps1:426-437` |
| `MMORPG_MYSQL_USER` / `_PASSWORD`、`MMORPG_GATEWAY_DB_USER` / `_PASSWORD` | 1 / 12 | `:442-455` |
| `MMORPG_REDIS_PASSWORD` | 12 | `:446-447` |
| `MMORPG_LOGIN_DEV_PASSWORD_SHARED_SECRET`（只允许 dev，无回落） | 1 | `:460-467` |
| `MMORPG_GATEWAY_ADMIN_API_KEY` | 32 | `:472-479` |
| `MMORPG_BATTLE_TOKEN_SECRET`（去空白后 ≥32 字节，且 ≠ gate） | 32 | `:1370-1378` |

### 2.10 基线自述缺口与本稿的观察

- 发布打包标准自写「P1–P4 已落码，**未编译、未运行、未按 §6 验证**」（`docs/design/release-packaging-standard-20260914.md:4`、`:256`），遗留缺口 7 条（`:246-254`），
  其中 #3 发布 workflow 无单元测试门禁（`:248`）、#4 preflight 缺「digest 已记录」（`:249`）与 Java 相关。
- **B-R1**：`release.yml` 不调用 preflight（§0.6 #2）；preflight 只在 `k8s_image.ps1:175-196` 和 `k8s_deploy.ps1:387-404` 的 staging / prod 档自动调用，且不带 `-ReleaseVersion`，
  H 节只靠人手动跑（`release-checklist.md:111`）。
- **B-R2**：`k8s_deploy.ps1:399-400` 自动调 preflight 时只传 `$NodeImage`，Go / Java 镜像 tag 不经可变性检查。
- **B-R3**：退出码 2 只在注释里（§2.8）。
- **B-R4**：基线的秘密写在仓库 yaml 里，所以 preflight 是「扫仓库文件」；Java 的秘密只从环境变量进（AGENTS §3），这套判据要整体改写，不能照搬。
- 契约测试共 2521 行（`release_common_version.tests.ps1` 446 行等），只在 `deploy-config-tests.yml`（paths 触发，`:29-62`）与 `release.yml` 里跑。

---

## 3 日志采集（基线）

### 3.1 本机观测台 `mm:deploy/docker-compose.observability.yml`

| 组件 | 版本 | 要点 | 出处 |
|---|---|---|---|
| loki | `grafana/loki:3.5.8` | `3100:3100`（0.0.0.0），卷 `loki-data` | `:19-33` |
| alloy | `grafana/alloy:v1.10.0`，故意不升级：v1.19.2 有读取位置回归，重启几乎整文件重读 | 只读挂 `../run/logs`、`../bin/logs`；调试页 12345 | `:11-15`、`:35-57` |
| grafana | `grafana/grafana:13.2.1` | **匿名访问即 Admin**，账号 admin/admin | `:59-87`（`:65-70`） |

项目名固定 `xuanming-observability`（`:16`）；容器日志 json-file 20m×3。

### 3.2 Alloy 流水线 `deploy/observability/alloy/config.alloy`

- 标签：job（go_services / cpp_nodes / java）、lang、service、zone（只在文件名带 `zN_` 时有）、instance、stream、level（小写）、filename，外加 `env=local`（`:8-16`、`:24-31`）。
- Go：go-zero JSON 用 `@timestamp`；etcd zap 用 `ts`；go-redis 文本按 `America/New_York` 解析（`:39-170`）。
- C++：去 ANSI；四种行头的多行合并（muduo、glog、librdkafka、Assertion）；时区与级别映射（`:183-345`）。
- Java：同时认 logstash JSON（`@timestamp / level / logger_name / thread_name`，`:348-356`）与 Spring 文本；Maven / JVM 行头单独成条；logger / thread 放结构化元数据（`:358-468`）。
- 三条流都 `ignore_older_than = "48h"`（`:45`、`:189`、`:365`）。

### 3.3 Loki `deploy/observability/loki/loki.yaml`（K8s ConfigMap 同口径）

single-binary、filesystem、tsdb v13（`:3-53`）；`reject_old_samples_max_age` 与 `retention_period` 都是 168h（`:55-58`）；ingestion 16 MB/s、burst 32 MB、单流 8 MB、
`max_query_series` 2000（`:62-66`）；compactor 开 retention（`:68-73`）；pattern_ingester 开（`:75-76`）。**ruler 只配了本地目录，没有 `alertmanager_url`，规则目录也没挂**
（`:78-83`），基线自己承认「不存在能生效的日志告警通路」（`deploy/k8s/owner-epoch-alerts.yaml:13-16`；`docs/design/guild-phase2/08-save-owner-fence.md:179`）。

### 3.4 K8s

- Loki 单副本 + 10Gi PVC，Recreate，limit 1Gi；改 ConfigMap 后要手动 `rollout restart`（`manifests/infra/loki.yaml:1-10`、`:26-43`、`:133-170`）；只在开了 sidecar 且没给外部 URL 时部署（`k8s_deploy.ps1:5341-5343`）。
- C++ 业务日志不进 stdout（muduo 的 `LogToConsole` 包在 `#ifdef WIN32` 里，README `:328`），所以用 Alloy 原生 sidecar 读共享卷（`docs/ops/grafana-loki-local-logs.md:312-352`）；配置哈希进 Pod 模板注解，改配置 = 滚动 C++ 节点（`:436-444`）。
- **Go / Java 在 K8s 上没有采集**：「还没有这个 DaemonSet，只在 `kubectl logs` 里」（`:530-532`）。
- Grafana 数据源只有 Loki（`grafana/provisioning/datasources/loki.yaml:5-10`）；看板允许在 UI 里改（`dashboards/dashboards.yaml:12`）。

### 3.5 Java gateway 的结构化日志与探针

`logging.structured.format.console: logstash`，字段 `@timestamp / level / logger_name / thread_name / message / stack_trace`（`mm:java/gateway_node/src/main/resources/application.yaml:133-140`）；
liveness / readiness 分组不含外部依赖（`:108-131`）；actuator 只暴露 `health,info`，**没有 prometheus**（`:112`）。

### 3.6 值班用的 LogQL（不会自动告警）

`docs/ops/cross-zone-failure-test-runbook.md:820-830` 的三条：`[AssetOp] blocked: owner_epoch unknown`、`[OwnerEpoch] … owner_epoch_unknown=[1-9]`、`save outran reconnect lease`，各自 10 分钟内出现即算。
另有 `release-checklist.md:272-283`（B.2）的 grep 错误关键字；C++ ThreadMonitor 只打 WARN 日志（`docs/design/thread-count-monitoring.md:14-21`）。

---

## 4 看板与告警（逐条）

### 4.1 基线看板

| 看板 | 数据源 | 结构 | 面板与表达式 |
|---|---|---|---|
| `mm:deploy/k8s/scene-manager-dashboard.json`（uid `scene-manager-overview`，refresh 30s） | `${DS_PROMETHEUS}` 模板变量（`:26-40`）；变量 `zone_id` = `label_values(scene_manager_nodes_by_role, zone_id)`（`:50-52`） | 5 行 12 个面板 | Pool health：`sum by (zone_id, role)(scene_manager_nodes_by_role)`（`:92`）、`topk(10, …_node_player_count)`（`:108`）、`topk(10, …_node_scene_count)`（`:124`）；Mirror lifecycle：命中率 stat（红 <0.5 / 黄 0.5 / 绿 ≥0.8，`:149-166`）、`mirror_colocate_total`（`:181`）、`instance_destroyed_total`（`:197`）；Concurrency：`enter_scene_rejected_total`（`:220`）、`scene_orphans_reconciled_total`（`:236`）；Mirror integrity：`mirror_source_missing_total`（`:259`）、`mirror_dedup_total`（`:275`）；Rebalance：`rebalance_migrations_total`（`:297`）、`rebalance_pending`（`:312`） |
| `mm:deploy/observability/grafana/dashboards/game-logs-overview.json`（uid `xm-game-logs`，refresh 10s） | Loki | 变量 job / service / zone / level / search（`:23-150`），7 个面板 | 各服务日志量（`:189`）、警告 / 错误趋势（`:242`）、错误条数（`:323`）、警告条数（`:385`）、有日志的服务数（`:447`）、`topk(8,…)` 错误最多的服务（`:497`）、日志流（`:551`） |
| `mm:docs/ops/grafana-login-path-deprecation.json` | Prometheus | 3 个面板 | `login_auth_path_total` 按 path、legacy 占比、按 auth_type（`:27`、`:50`、`:81`）；内嵌一条告警（§4.3） |
| `mm:docs/ops/grafana-rollback-audit.json` | Prometheus | 6 个面板 | `data_service_rollback_total` 按 scope / outcome、players_affected、orphans_cleaned、cross_scene p95 与 outcome（`:36-163`）；注释里 3 条告警（§4.3） |

没有任何基线部署会自动导入后三者以外的看板：scene-manager 看板靠 kube-prometheus 的 sidecar 导入（`:2`），而本机 Grafana 没有 Prometheus 数据源。

### 4.2 基线可部署告警（21 条，PrometheusRule CR，`interval: 30s`）

两个文件都要求 `kubectl apply -n observability` 到已有 kube-prometheus 的集群（`owner-epoch-alerts.yaml:6-11`、`scene-manager-alerts.yaml:3-11`）。
级别约定：critical 呼人、warning 工作时间处理、info 只做看板注记（`owner-epoch-alerts.yaml:18-21`）。

| # | 告警 | 表达式 | for | 级别 | 出处 |
|---|---|---|---|---|---|
| 1 | DbStaleOwnerWriteRejected | `sum(increase(db_stale_owner_write_rejected_total[10m])) > 0` | 0m | warning | `owner-epoch-alerts.yaml:40-52` |
| 2 | DbOwnerEpochLegacyZero | `sum(db_owner_epoch_guard_total{outcome="legacy_zero"}) > 0`（绝对值：CounterVec 首次出现时 `increase` 漏第一次） | 0m | warning | `:54-72` |
| 3 | SceneManagerInstancePoolEmpty | 有节点的 zone 里没有 `role=~"instance\|instance_cross"` 的节点，或该 zone 节点总数为 0 | 2m | critical | `scene-manager-alerts.yaml:55-77` |
| 4 | SceneManagerWorldPoolEmpty | 同上，`role=~"main_world\|main_world_cross"` | 2m | critical | `:79-100` |
| 5 | SceneManagerWorldNodeSaturated | `max by (zone_id)(…_node_player_count{role=~"main_world.*"}) > 1800` | 10m | warning | `:103-114` |
| 6 | SceneManagerLoadScoreDispersionLow | 主世界负载分的变异系数 < 0.1 | 15m | info | `:116-134` |
| 7 | SceneManagerRebalanceStalled | `max by (zone_id)(…_rebalance_pending{reason="node_gone"}) > 0` | 5m | warning | `:137-153` |
| 8 | SceneManagerRebalanceFailureRate | failed 迁移占比 `分子 / (分母 > 0) > 0.5` | 10m | warning | `:155-172` |
| 9 | SceneManagerMirrorColocationDegraded | 镜像共置命中率 < 0.5 | 30m | info | `:175-196` |
| 10 | SceneManagerEnterSceneHandoffAnomaly | `enter_scene_rejected_total{reason=~"handoff_pending_stale_marker\|handoff_pending_withdrawn\|epoch_conflict"}` 速率 > 0.05 | 15m | warning | `:249-279` |
| 11 | SceneManagerHomeZoneLookupUnavailable | `home_zone_unavailable` 占归属区查询 > 0.5 | 5m | critical | `:281-304` |
| 12 | SceneManagerHomeZoneLookupRejecting | `home_zone_unavailable` 速率 > 0.05 | 10m | warning | `:306-319` |
| 13 | SceneManagerHomeZoneUnmappedTravel | `home_zone_unmapped_travel` 速率 > 0.01 | 10m | warning | `:321-342` |
| 14 | SceneManagerZoneTravelMapUnavailable | `reason=~"travel_map_unavailable\|pending_map_fallback"` 速率 > 0.01 | 10m | warning | `:344-362` |
| 15 | SceneManagerEnterSceneSceneGone | `scene_gone` 速率 > 0.05 | 10m | warning | `:364-381` |
| 16 | SceneManagerEnterSceneRollbackRedisError | `sum(increase(…_enter_scene_rollback_total{outcome="redis_error"}[10m])) > 0` | — | warning | `:402-423` |
| 17 | SceneManagerNodeDetachWithoutDeathMark | `…_node_detach_deferred_total{outcome=~"expired\|abandoned"}` 10 分钟增量 > 0 | — | warning | `:436-453` |
| 18 | SceneManagerSceneOrphanReconcileSpike | 孤儿对账每分钟 > 50 | 5m | warning | `:455-469` |
| 19 | SceneManagerMirrorSourceMissing | 速率 > 0.01 | 10m | warning | `:471-487` |
| 20 | SceneManagerInstanceDestroyCascadeAnomaly | cascade / 非 cascade 销毁 > 10 | 15m | warning | `:489-508` |
| 21 | SceneManagerUnknownSceneNodeType | `nodes_by_role{role="unknown"} > 0` | 1m | warning | `:511-525` |

刻意不告警：`handoff_pending_no_marker`（生产配置下跨节点换图第一跳必然拿到，`:211-216`）；回滚结局 `marker_gone` / `plan_error`（没有基线，`:390-399`）。
新 zone 开服、整 zone 维护前要对该 zone 加 silence（`:52-53`，步骤见 `docs/ops/scene-node-role-split.md:207-214`）。

### 4.3 写在文档里、没有部署的告警

| 告警 / 查询 | 表达式与阈值 | 出处 | 状态 |
|---|---|---|---|
| LegacyLoginPathTooHigh | legacy 登录路径占比 > 5%，持续 10m，warning | `grafana-login-path-deprecation.json:97-113` | JSON 片段，注明「贴进 alert-rules.yaml」 |
| 回档失败率 / 全服回档 / 跨场景失败占比 | failed 速率 > 0.01/s 5m；`scope='server'` 任意 1m；`outcome!='ok'` 占比 > 1% 10m；均 page | `grafana-rollback-audit.json:18-21` | 注释写「在 Grafana 里配」 |
| scene 三条 LogQL | §3.6 | runbook `:820-830` | 值班固定查询，ruler 没接线 |
| 帮会建议 5 条 | `assetop_unknown_total`、`assetop_pending_oldest_age_seconds{stream="2"} > 86400`、`assetop_partial_total`、`guild_tx_deadlock_total`、缓存失效失败 / 资产孤儿持续上升 | `docs/design/guild-phase2/92-handoff.md:967` | 「建议，BK8s 落规则」 |
| 角色名孤儿 | `increase(login_create_player_name_orphan_total[10m]) > 0`（需先预置 0，`:196-198`） | `92-handoff.md:225-227`、`:240` | 没有落规则 |
| 排行榜 ①–⑥ | RankConsumerStalled 等 6 条 | `docs/design/leaderboard-system.md:241-252` | 草案，`rank-alerts.yaml` 不存在 |
| 发布检查单 B.1 / 回滚触发 E.1 | 登录成功率 <95% 5m、p99 >500 ms 1m、REPLACE 占比 >5%、TIME_WAIT、ListenOverflows、lag >1000…；新路径成功率 <90%、p99 >2 s、OOM / panic | `docs/ops/release-checklist.md:259-270`、`:361-366` | 历史清单（2026-05 HTTP 登录专项） |

### 4.4 基线自身的问题（Java 不照搬）

1. 仓库里**没有部署任何 Prometheus / Alertmanager**，搜不到 `prom/prometheus` 镜像或 `kind: Prometheus`；告警文件依赖外部 kube-prometheus。
2. 只有 chat、client-rpc-router、friend、match、trade 五个清单带 `prometheus.io/scrape` 注解；`scene-manager.yaml`、`db.yaml`、`login.yaml` 都没有，仓库里也没有 ServiceMonitor / PodMonitor，
   单凭仓库，告警文件写明的抓取前提不成立（按 grep 推断）。
3. CI 里没有 promtool，只在文档写了手工 `promtool check rules`（`08-save-owner-fence.md:254`）。
4. 多条阈值自写「代码从未编译、未实测，无基线」（`scene-manager-alerts.yaml:248`、`:401`、`:435`）。
5. 出过一条「恒为空向量、永远不会响」的告警（SceneGone 那条，`:198-208`）。
6. 「CounterVec 第一次出现时 `increase` 抓不到」踩过两次（`owner-epoch-alerts.yaml:57-59`；`92-handoff.md:196-198`）。

### 4.5 基线 21 条 → Java 逐条处置

Java 指标名有意与基线不同（PARITY「低基数运行指标」行），进程内也不带 `zone_id` 标签（architecture §11，编写时 `:914`），所以没有一条能原样复用。

| # | 基线 | Java 处置 | 理由 |
|---|---|---|---|
| 1 | DbStaleOwnerWriteRejected | **改写 → A29** | Java 的围栏拒绝在 scene 侧计 `xm_scene_storage_writes_seconds_count{result="fenced"}` |
| 2 | DbOwnerEpochLegacyZero | **不适用** | Java 从第一天就有 `WHERE owner_epoch = ?` 围栏，没有 epoch=0 的兼容窗口（`docs/design/db-migrations.md` M2；`xm-player-store/.../xm-player-schema.sql:25`） |
| 3 | SceneManagerInstancePoolEmpty | **不适用**（最近的对应是 5.3 的 A64） | Java 不分用途池（`dungeon-mirror-spec.md:117` 延续 D15；`:922`），镜像恒与源场景共置 |
| 4 | SceneManagerWorldPoolEmpty | **改写 → A24 + A36**（另有 A40） | 「zone 有 gate 却没有活 scene」与「zone 没有 ACTIVE 频道」，zone 取自抓取目标标签 |
| 5 | SceneManagerWorldNodeSaturated | **改写 → A37** | 按 scene 目标汇总 `xm_scene_players`；阈值沿用 1800，未校准 |
| 6 | SceneManagerLoadScoreDispersionLow | **不适用** | Java 没有负载分，按「目录人数 + 软预占」挑最少的（PARITY「场景分配」行） |
| 7 | SceneManagerRebalanceStalled | **直译 → A43**（另有 A42） | `xm_scene_manager_rebalance_pending{reason="node_gone"}` 存在（`WorldChannelMetrics.java:38`） |
| 8 | SceneManagerRebalanceFailureRate | **改写 → A44**（另有 A38 / A39） | Java 的迁移结局只有计划 / 完成，失败体现在世界 tick 的 `error / fenced / conflict / no_lease`；比值写法沿用基线 |
| 9 | SceneManagerMirrorColocationDegraded | **不适用** | Java 镜像恒共置（`dungeon-mirror-spec.md:960`） |
| 10 | SceneManagerEnterSceneHandoffAnomaly | **改写 → A62**（5.2） | `xm_scene_transfers_total{result=~"lost_unknown\|fenced\|lease_too_short"}`；阈值与 for 沿用 |
| 11–13 | SceneManagerHomeZone* 三条 | **不适用** | 归属区就是 `player.zone_id`，与玩家行同一行、恒有值（`zone-travel-spec.md:52`），没有外部查询依赖。trade 的归属区读取另设 A56 |
| 14 | SceneManagerZoneTravelMapUnavailable | **改写 → A66 / A67**（5.4） | `xm_scene_manager_travel_seconds{result="no_map"}`、`xm_scene_travel_placements_total{result="failed"}`（`zone-travel-spec.md:899`、`:904`） |
| 15 | SceneManagerEnterSceneSceneGone | **改写 → A45 / A46** | 从调用方视角看 assign 回 `no_scene` / `error` 的占比 |
| 16 | SceneManagerEnterSceneRollbackRedisError | **改写 → A19**（另有 A18） | Java 没有「Kafka 推路由 + Redis 回滚」；对应的「状态不明」是放弃进场后释放归属失败 |
| 17 | SceneManagerNodeDetachWithoutDeathMark | **不适用 → 由 5.5 的 A68–A71 替代** | Java 不靠 `death_at` 屏障，迟到的写由 epoch 围栏拒掉（`scene-drain-spec.md:1027-1029`） |
| 18 | SceneManagerSceneOrphanReconcileSpike | **不适用**（A42 覆盖） | 死节点的频道进入 missing 后由领导者重铺，没有孤儿对账 |
| 19 | SceneManagerMirrorSourceMissing | **改写 → A63**（5.3） | `xm.scene.mirror.requests{result="bad_source"}`（`dungeon-mirror-spec.md:713`）；阈值沿用 0.01/s |
| 20 | SceneManagerInstanceDestroyCascadeAnomaly | **改写 → A65**（5.3） | `xm.scene.instance.lifecycle{event}`；比值沿用 10 |
| 21 | SceneManagerUnknownSceneNodeType | **不适用** | Java 不分节点角色 |

**合计**：改写 / 直译 / 合并 11 条（#1、#4、#5、#7、#8、#10、#14、#15、#16、#19、#20），不适用 10 条（#2、#3、#6、#9、#11、#12、#13、#17、#18、#21）。

§4.3 的文档告警：回档三条由 7.2 的 A82–A85 与 5.2 的 A62 覆盖；scene 三条 LogQL 由 A28 / A29 覆盖（Java 的 epoch 恒有值，`owner_epoch unknown` 不存在）；
帮会五条 → A50–A55；登录路径占比、角色名孤儿、排行榜 → 不适用（Java 没有 legacy 登录路径；建角与名字同事务；排行榜未移植）；B.1 / E.1 → A12–A14、A3。

### 4.6 Java 告警清单（逐条）

**约定**：
- 「批次」列：`7.6` = 指标已在代码里，本批落规则；其余 = 指标由该批引入，规则和 promtool 用例**随该批一起落**（Q45）。7.6 落地的是 A1–A60。
- 「未校准」= 没有可照搬的基线数字或基线自己也没校准过，先按保守值上线，7.4 压测后回填（Q44）。
- 标签 `zone` / `instance` / `job` 都是抓取目标标签（§5.13）。scene-manager 跨 zone 共用，进程不打 zone 标签（`scene-channels-spec.md:552`），所以 A40–A46 是全舰队粒度。
- 表达式里的 `\|` 是表格转义，规则文件里写 `|`。

| id | 告警 | 表达式 | for | 级别 | 批次 | 依据 |
|---|---|---|---|---|---|---|
| A1 | XmCoreTargetDown | `up{job=~"xm-(gateway\|login\|scene-manager\|gate\|scene)"} == 0` | 2m | critical | 7.6 | 基线自承的盲区（`scene-manager-alerts.yaml:49-51`） |
| A2 | XmTargetDown | `up{job=~"xm-.+", job!~"xm-(gateway\|login\|scene-manager\|gate\|scene)"} == 0` | 5m | warning | 7.6 | 同上 |
| A3 | XmProcessRestarting | `changes(process_start_time_seconds{job=~"xm-.+"}[30m]) >= 3` | 0m | warning | 7.6 | 崩溃循环判据，不依赖 kube-state-metrics |
| A4 | XmJvmLiveDataHigh | `jvm_gc_live_data_size_bytes{job=~"xm-.+"} / jvm_gc_max_data_size_bytes > 0.85` | 15m | warning | 7.6 | GC 后存活数据，比原始 used 稳 |
| A5 | XmGcTimeHigh | `sum by (job, instance)(rate(jvm_gc_pause_seconds_sum{job=~"xm-.+"}[5m])) > 0.1` | 10m | warning | 7.6 | — |
| A6 | XmSceneGcPauseOverFrame | `max by (zone, instance)(max_over_time(jvm_gc_pause_seconds_max{job="xm-scene"}[5m])) > 0.05` | 10m | warning | 7.6 | 50 ms 帧预算（architecture §11） |
| A7 | XmErrorLogBurst | `sum by (job, instance)(increase(logback_events_total{job=~"xm-.+", level="error"}[10m])) > 50` | 0m | warning | 7.6 | 替代基线 B.2 的关键字 grep；**未校准**（H29） |
| A8 | XmClockSkew | `max by (job, instance)(abs(xm_redis_clock_offset_seconds)) > 1` | 5m | warning | 7.6 | 新指标（J9）；租约比较用墙钟（architecture §7，编写时 `:689`），Dubbo 鉴权窗 ±60 s |
| A9 | XmKillswitchSyncFailing | `sum by (job, instance)(increase(xm_killswitch_sync_failures_total[10m])) > 3` | 0m | warning | 7.6 | 失联 60 s 后整体放行（`xm-gate application.yaml` killswitch 段注释） |
| A10 | XmKillswitchLeftOn | `max by (job)(xm_killswitch_rules) > 0` | 6h | info | 7.6 | 止血阀忘了撤 |
| A11 | XmLogShipperErrors | `sum by (instance)(rate(vector_component_errors_total[5m])) > 0` | 10m | warning | 7.6 | 日志送不出去时审计兜底行只靠 Vector 磁盘缓冲（H12） |
| A12 | XmAssignGateServerErrors | `sum(rate(xm_gateway_assign_gate_total{code="500"}[5m])) / (sum(rate(xm_gateway_assign_gate_total[5m])) > 0) > 0.05` | 5m | critical | 7.6 | B.1「成功率 95% 持续 5 分钟」（`release-checklist.md:261`）；**503 是区服状态、100 / 429 是排队与限流，都不算**（§0.6 #3） |
| A13 | XmHttpLoginServerErrors | `sum(rate(xm_gateway_login_total{code="500"}[5m])) / (sum(rate(xm_gateway_login_total[5m])) > 0) > 0.05` | 5m | critical | 7.6 | 同上；`code="500"` 已预注册（`LoginHttpMetrics.java:22-31`） |
| A14 | XmLoginLatencyP99High | `histogram_quantile(0.99, sum by (le)(rate(xm_login_requests_seconds_bucket{method="Login"}[5m]))) > 0.5` | 5m | warning | 7.6 | B.1 p99 >500 ms（基线 for 1m，这里放宽降噪） |
| A15 | XmLoginOverloaded | `sum(rate(xm_login_requests_seconds_count{result="overloaded"}[5m])) > 0` | 5m | warning | 7.6 | 工作队列满，玩家收 1003 |
| A16 | XmLoginInternalErrors | `sum(rate(xm_login_requests_seconds_count{result="internal_error"}[5m])) / (sum(rate(xm_login_requests_seconds_count[5m])) > 0) > 0.01` | 10m | warning | 7.6 | — |
| A17 | XmOwnerClaimTimeouts | `sum(rate(xm_login_owner_claims_seconds_count{outcome="timeout"}[5m])) > 0.05` | 10m | warning | 7.6 | 玩家收 2005 |
| A18 | XmOwnerClaimErrors | `sum(increase(xm_login_owner_claims_seconds_count{outcome="error"}[10m])) > 0` | 0m | warning | 7.6 | `ClaimOutcome.ERROR` 已预注册 |
| A19 | XmAbandonedEnterReleaseFailed | `sum(increase(xm_login_abandoned_enters_total{result="failed"}[10m])) > 0` | 0m | warning | 7.6 | ≈ 基线 #16 |
| A20 | XmGateHandshakeRejects | `sum by (zone)(rate(xm_gate_handshakes_total{result=~"bad_signature\|expired\|wrong_zone"}[5m])) / (sum by (zone)(rate(xm_gate_handshakes_total[5m])) > 0) > 0.2` | 10m | warning | 7.6 | 密钥轮换不同步、时钟漂移 |
| A21 | XmGateWrongGateHandshakes | `sum by (zone)(rate(xm_gate_handshakes_total{result="wrong_gate"}[5m])) > 0.1` | 10m | warning | 7.6 | 多个 gate 共用了一个对外地址（§5.5 硬约束、H24） |
| A22 | XmGateLinkConnectFailing | `sum by (zone)(rate(xm_gate_link_events_total{event="connect_failed"}[5m])) > 0.1` | 10m | warning | 7.6 | 链路密钥不一致、NetworkPolicy 拦了；替代映射稿的「链路数为 0」（§0.6 #4） |
| A23 | XmGateSceneLinkUnavailable | `sum by (zone)(rate(xm_gate_client_requests_total{result="link_unavailable"}[5m])) > 0.1` | 5m | critical | 7.6 | 玩家请求到不了 scene；**未校准** |
| A24 | XmZoneGateWithoutScene | `count by (zone)(up{job="xm-gate"} == 1) unless on (zone) count by (zone)(up{job="xm-scene"} == 1)` | 2m | critical | 7.6 | ≈ 基线 #4 的「整 zone 全灭」分支；开区前对新 zone 加 silence（`xm-ops` 的开区流程里 gate 与 scene 同时起，通常不会触发） |
| A25 | XmGateLinkDrops | `sum by (zone, instance)(rate(xm_gate_link_dropped_total{reason=~"queue_full\|link_failed"}[5m])) > 1` | 10m | warning | 7.6 | — |
| A26 | XmSceneTickOverBudget | `1 - (sum by (zone, instance)(rate(xm_scene_tick_seconds_bucket{le="0.05"}[5m])) / sum by (zone, instance)(rate(xm_scene_tick_seconds_count[5m]))) > 0.05` | 10m | warning | 7.6 | 50 ms 桶（`SceneMetrics.LOGIC_BUCKETS`）；超预算的帧占比 > 5% |
| A27 | XmSceneLogicWaitHigh | 同上，`xm_scene_logic_task_wait_seconds` | 10m | warning | 7.6 | 逻辑线程排队 |
| A28 | XmScenePlayerWriteLost | `sum by (zone, instance)(increase(xm_scene_storage_writes_seconds_count{op=~"save\|release\|progress", result=~"failed\|rejected"}[10m])) > 0` | 0m | **critical** | 7.6 | 这两个结局代码里明写「写丢失，已记 ERROR 待人工修复」（`SceneMetrics.WriteResult`）；handoff / probe 的 failed 是「结局不明」，不算丢失，排除 |
| A29 | XmSceneWriteFenced | `sum by (zone)(increase(xm_scene_storage_writes_seconds_count{op=~"save\|release\|progress", result="fenced"}[10m])) > 0` | 0m | warning | 7.6 | ≈ 基线 #1；5.2 之后若压测显示是常态，降为 info |
| A30 | XmPeriodicSaveDeferred | `sum by (zone, instance)(rate(xm_scene_periodic_saves_total{result="deferred"}[10m])) > 0` | 15m | warning | 7.6 | 存储积压 |
| A31 | XmSceneLinkBackpressure | `sum by (zone, instance)(rate(xm_scene_link_backpressure_pauses_total[5m])) > 0.1` | 10m | warning | 7.6 | architecture §4.2 背压；**未校准** |
| A32 | XmSceneAuditUndelivered | `sum by (zone, kind, result)(increase(xm_scene_audit_records_total{result!="acked"}[10m])) > 0` | 0m | warning | 7.6 | 记录进了兜底日志，要回灌（architecture §4.5，编写时 `:219`） |
| A33 | XmGainBlockSyncStale | `max by (zone, instance)(xm_scene_gain_block_sync_age_seconds) > 60` | 2m | warning | 7.6 | 同步周期 10 s；文档写「要告警」（architecture，编写时 `:254`） |
| A34 | XmGainAnomalyDetected | `sum by (zone)(increase(xm_scene_gain_anomalies_total[10m])) > 0 or sum by (zone)(xm_scene_gain_anomalies_total unless xm_scene_gain_anomalies_total offset 10m) > 0` | 0m | warning | 7.6 | 动态标签、按需注册（`SceneMetrics.java:595`），用「新序列」写法补第一次（§4.8 第 3 条）；玩家号在 `xm.audit.anomaly` 日志 |
| A35 | XmSceneAssetOpErrors | `sum by (zone)(rate(xm_scene_asset_ops_total{outcome=~"error\|overloaded"}[5m])) > 0` | 10m | warning | 7.6 | 调用方会重投，持续出现才告 |
| A36 | XmZoneNoActiveChannel | `sum by (zone)(xm_scene_channels{state="active"}) == 0` | 2m | critical | 7.6 | ≈ 基线 #4 的「有节点无承载」分支 |
| A37 | XmScenePlayersHigh | `sum by (zone, instance)(xm_scene_players) > 1800` | 10m | warning | 7.6 | ≈ 基线 #5；**未校准** |
| A38 | XmChannelPlanRejected | `sum by (zone)(rate(xm_scene_channel_plan_applies_total{result="rejected"}[5m])) > 0` | 10m | warning | 7.6 | — |
| A39 | XmChannelPlanPollFailing | `sum by (zone, instance)(rate(xm_scene_channel_plan_poll_failures_total[5m])) > 0` | 5m | warning | 7.6 | — |
| A40 | XmZoneWithoutSceneNodes | `sum(rate(xm_scene_manager_world_ticks_total{result="no_nodes"}[2m])) > 0` | 2m | warning | 7.6 | `xm:world:zones` 里有 zone 没有活节点：scene 全挂，或退役 zone 没 SREM（`RedisKeys.worldZones` 注释）。玩家可见的情形由 A24 / A36 按 zone 报 critical |
| A41 | XmWorldLeaderGap | `count(count by (zone)(up{job="xm-scene"} == 1)) > sum(xm_scene_manager_world_leader_zones)` | 2m | warning | 7.6 | 有活 scene 的 zone 没人领导；补基线盲区 |
| A42 | XmWorldChannelsMissing | `sum(xm_scene_manager_world_channels{state="missing"}) > 0` | 5m | warning | 7.6 | ≈ 基线 #7 / #18 |
| A43 | XmRebalanceNodeGonePending | `max(xm_scene_manager_rebalance_pending{reason="node_gone"}) > 0` | 5m | warning | 7.6 | 基线 #7 直译 |
| A44 | XmWorldTickFaults | `sum(rate(xm_scene_manager_world_ticks_total{result=~"error\|fenced\|conflict\|no_lease"}[5m])) / (sum(rate(xm_scene_manager_world_ticks_total{result!="not_leader"}[5m])) > 0) > 0.5` | 10m | warning | 7.6 | ≈ 基线 #8，比值写法同基线 |
| A45 | XmSceneAssignFailing | `sum(rate(xm_scene_manager_assign_seconds_count{result=~"error\|no_scene"}[5m])) / (sum(rate(xm_scene_manager_assign_seconds_count[5m])) > 0) > 0.05` | 10m | warning | 7.6 | ≈ 基线 #15 |
| A46 | XmSceneAssignErrors | 同上，只算 `result="error"`，阈值 0.5 | 5m | critical | 7.6 | 场景目录不可读 |
| A47 | XmServiceRequestFaults | `sum by (job)(rate({__name__=~"xm_(friend\|chat\|team\|guild\|trade)_requests_seconds_count", result=~"internal_error\|overloaded"}[5m])) / (sum by (job)(rate({__name__=~"xm_(friend\|chat\|team\|guild\|trade)_requests_seconds_count"}[5m])) > 0) > 0.05` | 10m | warning | 7.6 | chat 没有 overloaded 结局，正则照样适用 |
| A48 | XmFriendQuotaFailOpen | `sum(increase(xm_friend_request_quota_total{outcome="error"}[10m])) > 0` | 0m | warning | 7.6 | 文档写「fail-open 的唯一信号，须配告警」（architecture §11，编写时 `:948`） |
| A49 | XmFriendCountUnderflow | `sum(increase(xm_friend_count_underflows_total[1h])) > 0` | 0m | warning | 7.6 | 不变量被破坏 |
| A50 | XmGuildTxDeadlocks | `sum(increase(xm_guild_tx_deadlocks_total[10m])) > 0` | 0m | warning | 7.6 | 基线建议（`92-handoff.md:967`）；计数含重试（`GuildMetrics.java:264-266`），**未校准**，压测后可能改为速率阈值 |
| A51 | XmGuildAssetOpStuck | `max by (stream)(xm_guild_assetop_pending_oldest_age_seconds) > 86400` | 0m | warning | 7.6 | 基线建议阈值 |
| A52 | XmGuildAssetOpUnknown | `sum(increase(xm_guild_assetop_unknown_total[10m])) > 0` | 0m | warning | 7.6 | 同上 |
| A53 | XmGuildAssetOpPartial | `sum(increase(xm_guild_assetop_partial_total[1h])) > 0` | 0m | warning | 7.6 | 同上 |
| A54 | XmGuildCacheInvalidationFailing | `sum(increase(xm_guild_cache_invalidation_failures_total[1h])) > 0` | 0m | warning | 7.6 | 同上 |
| A55 | XmGuildAssetOrphans | `sum(increase(xm_guild_asset_orphans_total[1h])) > 0` | 0m | warning | 7.6 | 同上 |
| A56 | XmTradeHomeZoneLookupErrors | `sum(rate(xm_trade_home_zone_lookups_total{result="error"}[5m])) > 0.05` | 10m | warning | 7.6 | 最接近基线 #11–#13 的 Java 依赖 |
| A57 | XmAuditConsumerDown | `min by (consumer)(xm_data_kafka_consumer_up) == 0` | 5m | warning | 7.6 | `DataMetrics.CONSUMER_UP`；消息留在 Kafka（保留 30 天），不丢 |
| A58 | XmAuditConsumerLag | `max by (consumer)(xm_data_kafka_consumer_lag) > 10000` | 15m | warning | 7.6 | **未校准**（B.1 的 1000 针对 gate topic） |
| A59 | XmAuditRecordsSkipped | `sum by (consumer, outcome)(increase(xm_data_kafka_records_total{outcome=~"decode_error\|invalid"}[10m])) > 0` | 0m | warning | 7.6 | 毒丸进 `xm.audit.poison` |
| A60 | XmAuditDbInsertErrors | `sum by (consumer)(increase(xm_data_db_insert_errors_total[10m])) > 0` | 10m | warning | 7.6 | `DataMetrics.java:73` |
| A61 | XmTransferPostFreezeMutation | `sum(increase(xm_scene_transfer_post_freeze_mutations_total[10m])) > 0` | 0m | warning | 5.2 | 应恒为 0（`scene-handoff-spec.md:1083`） |
| A62 | XmTransferAnomaly | `sum by (zone, result)(rate(xm_scene_transfers_total{result=~"lost_unknown\|fenced\|lease_too_short"}[5m])) > 0.05` | 15m | warning | 5.2 | ≈ 基线 #10，阈值与 for 沿用 |
| A63 | XmMirrorBadSource | `sum(rate(xm_scene_mirror_requests_total{result="bad_source"}[5m])) > 0.01` | 10m | warning | 5.3 | 基线 #19 |
| A64 | XmMirrorRequestsRejected | `sum(rate(xm_scene_mirror_requests_total{result=~"not_accepting\|node_cap"}[5m])) > 0.05` | 10m | warning | 5.3 | 最接近基线 #3 |
| A65 | XmInstanceCascadeAnomaly | cascade 销毁 / 非 cascade 销毁 > 10（`xm_scene_instance_lifecycle_total{event=~"destroyed_.*"}`） | 15m | warning | 5.3 | 基线 #20；事件名以 5.3 落码为准 |
| A66 | XmZoneTravelNoMap | `sum(rate(xm_scene_manager_travel_seconds_count{result="no_map"}[5m])) > 0.01` | 10m | warning | 5.4 | 基线 #14 |
| A67 | XmTravelPlacementFailed | `sum(increase(xm_scene_travel_placements_total{result="failed"}[10m])) > 0` | 0m | warning | 5.4 | — |
| A68 | XmSceneNodeIdentityTaken | `max by (zone, instance)(xm_scene_node_identity) == 2` | 0m | critical | 5.5 | `scene-drain-spec.md:1063-1065` |
| A69 | XmSceneNodeSuspended | `max by (zone, instance)(xm_scene_node_identity) == 1` | 30s | warning | 5.5 | 同上 |
| A70 | XmEvacuationBudgetExceeded | `sum(increase(xm_scene_node_evacuations_total{result="budget_exceeded"}[30m])) > 0` | 0m | warning | 5.5 | 同上 |
| A71 | XmWorldNodeStuck | `max(xm_scene_manager_world_nodes{state="stuck"}) > 0` | 5m | warning | 5.5 | 同上 |
| A72–A74 | XmRelocationDeferredHigh、XmRelocationStorageGateClosed、XmSceneLinkIdleTimeoutSpike | `xm_scene_channel_relocations_total{result=~"deferred.*"}`、`{result="deferred_storage"}` 持续增长、`xm_scene_link_idle_closes_total` 突增 | — | warning | 5.5 | 阈值由 5.5 落码时定 |
| A75 | XmBattleLeaseLost | `sum(increase(xm_battle_lease_lost_total[10m])) > 0` | 0m | warning | 6.2 | 关闸不作废房间，要安排重启 |
| A76 | XmBattleFingerprintMismatch | `sum(increase(xm_battle_fingerprint_mismatch_total[10m])) > 0` | 0m | warning | 6.2 | scene 与 battle 配表不同版本 |
| A77 | 6.3 结算九条 | `not_durable`、`exhausted`（有目标）、`expired`、`ledger_evictions`、`drop_lost`、`pending_corrupt` 增量 > 0；`frozen{state=fighting}` 长时间不降；`recovery{retry}` 持续增长；`settlements{result=deferred_currency}` 持续出现 | — | warning | 6.3 | `scene-battle-spec.md:1067-1068` |
| A78–A81 | 匹配四条 | `xm_match_rating_consumer_paused == 1` 持续 10m；`xm_match_table_fingerprint_mismatches_total` 增长；`xm_match_matcher_rounds_total{result=~"paused_.*"}` 持续 10m；battle 池为空（critical） | — | 见左 | 6.4 | `match-spec.md:575`、`:1023` |
| A82–A85 | 运维作业四条 | `xm_data_ops_fence_held > 0` 超过作业时限；divergence `check_failed\|truncated\|post_write_.*` 增长；`jobs_total{outcome="diverged_after_write"}` > 0（**critical**）；`interrupted`、`claims_total{outcome="timeout"}` 突增 | — | 见左 | 7.2 | `data-ops-spec.md:504`、`:951-956` |

### 4.7 Java 看板

全部放 `deploy/observability/grafana/dashboards/`，Prometheus 数据源用模板变量 `DS_PROMETHEUS`（缺省 `prometheus`，能导入外部 Grafana，同基线 `:26-40` 做法）；
变量 `$zone = label_values(up{job="xm-scene"}, zone)`、`$instance`；K8s 下以 ConfigMap + 标签 `grafana_dashboard: "1"` 提供（同基线 `scene-manager-dashboard.json:2` 的约定），compose 里走 provisioning。

| uid | 内容 |
|---|---|
| `xm-overview` | `up` 按 job；重启次数；存活数据占比与 GC 时间；`logback_events_total{level=~"warn\|error"}` 速率；在线人数 `sum(xm_gate_sessions_active)`、`sum by (zone)(xm_scene_players)`；正在响的 `ALERTS` |
| `xm-entry` | assign-gate 按 code / reason；HTTP 登录按 endpoint / code；login 按方法的 p50 / p99；归属夺取结局；排队放行；gate 握手、请求去向、断开原因、链路事件 |
| `xm-scene` | tick 超预算占比（50 ms 参考线）；逻辑队列与等待；存储写结局与耗时、周期存盘；审计结局；交出（5.2）；链路帧、丢弃与背压；频道（active / draining）与改派；实例（5.3）；封禁名单同步滞后 |
| `xm-scene-manager` | 对标基线 Pool health / Rebalance：频道按 scene_config / state；世界 tick 按 result；leader zones；rebalance 待处理与迁移；autoscale；assign 结局与 p99 |
| `xm-services` | 好友 / 聊天 / 组队 / 帮会 / 交易的请求结局与 p99、推送结局；帮会资产指令最老待处理时长；审计消费 up / lag / 结局；killswitch |
| `xm-logs`（Loki） | 移植 `game-logs-overview` 的 7 个面板，变量改为 service / zone / level / search；另加审计流面板 `{stream_class="audit"}` |
| `xm-battle-match` | 6.2–6.4 落地后加 |

### 4.8 规则工程纪律（对应基线的教训）

1. **每条规则都有 promtool 单测**：`deploy/observability/prometheus/tests/*.test.yaml` 里至少一个「刚过阈值会触发」和一个「刚不到阈值不触发」的用例；
   CI 守卫：规则文件里的 alertname 集合 = 用例覆盖的 alertname 集合。示例：

   ```yaml
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

2. **指标目录守卫**（防基线 §4.4 第 5 条「恒空告警」）：每个进程模块一个 `MetricsCatalogTest`，把本模块的 `*Metrics` 注册进 `PrometheusMeterRegistry`、抓一次，写出
   `target/metrics-catalog.txt`（名字 + 启动时就存在的标签组合）。`xm-ops obs check` 抽出规则与看板里所有 `xm_[a-z0-9_]+` 和引用的 `job`，逐个对照各模块目录与进程清单；
   JVM / 进程 / logback / executor 等 Actuator 自带指标走白名单。本机就能跑。
3. **「首次发生」**：用 `increase(...) > 0` 的计数器，所有固定标签组合必须启动即注册为 0（目录守卫检查）。带动态标签、无法预注册的（目前只有 `xm_scene_gain_anomalies_total`、
   `xm_scene_gain_blocked_total`、`xm_killswitch_blocked_total`），规则一律补「新序列」分支 `x unless x offset <窗口>`（A34 的写法），躲开基线 §4.4 第 6 条。
   比值型告警（A12、A47 等）不受影响。
4. **新增指标的批次同批提交规则和 promtool 用例**（提议写进 AGENTS §4，Q45）。7.6 只提交 7.6 提交时已经存在的指标对应的规则（A1–A60）。
5. **每条规则带** `severity`、`runbook_url: docs/ops/alerts.md#<告警名>`；级别口径同基线：critical 呼人、warning 工作时间、info 只做注记。

---

## 5 Java 设计

### 5.1 拓扑：进程归属对照与不移植项

| 基线 | 基线范围 | Java | Java 范围 | 说明 |
|---|---|---|---|---|
| C++ gate | zone | xm-gate | zone | StatefulSet |
| C++ scene（单池 / world / instance） | zone | xm-scene | zone | 单池 Deployment；不移植用途分池（`dungeon-mirror-spec.md:922`） |
| Go login | zone | xm-login | **全局** | 一个 login 服务全部 zone（`zone-travel-spec.md:1020`、`:1056`），依赖 X16 |
| Go scene-manager | zone | xm-scene-manager | **全局** | 按 zone 竞选领导者（`RedisKeys.worldZones`） |
| Java gateway | zone | xm-gateway | **全局** | 区服目录在库里（architecture §7，编写时 `:638`） |
| Go db（db_task 消费者） | zone | — | — | scene 直接写 MySQL，写入带 owner_epoch 围栏 |
| Go player-locator | zone | — | — | 在线目录与位置记录在 Redis（xm-discovery） |
| Go data-service | zone | xm-data | 全局 | 单副本 + Recreate（`data-ops-spec.md:143`） |
| Go match / chat / friend / trade | 全局 | xm-match（6.4）/ xm-chat / xm-friend / xm-trade | 全局 | — |
| — | — | xm-team / xm-guild | 全局 | 基线 K8s 上根本没部署 guild |
| Go client-rpc-router | 全局 | — | — | gate 按服务语义经 Dubbo 直接路由（architecture §1） |
| C++ battle 池 | 全局 | xm-battle（6.2） | 全局 | StatefulSet |
| Kafka / Redis / MySQL | infra | 同 | infra（只 dev / CI / 演示） | 镜像与 7.1 compose 同一 digest（`deploy-ci-spec.md:316-359`），CI 守卫逐字比对 |

**不移植**（都不是客户端可见）：

| 基线项 | 理由 |
|---|---|
| etcd | Java 的节点号、租约、目录都在 Redis（architecture §6）；ContractSync 也排除了 `proto/etcd` |
| kafka-topic-init | broker 关掉自动建 topic，审计 topic 由生产方和消费方按规格自建并核对（`deploy-ci-spec.md:301-303`） |
| redis-match-cluster | Java 的 match 用单实例 Redis + 一个 hash tag（`match-spec.md:994`、`:1261`） |
| 每 zone 一个库、migrate Job | Java 只有一个库 `xm_java`，行按 zone_id 区分；建表在进程启动时做，pbmysql 整轮持 `GET_LOCK`（architecture §7，编写时 `:657`），并发风险见 H9 |
| mysqldump CronJob | infra 只给 dev / CI / 演示；生产用带 PITR 的托管 MySQL（Q19） |
| C++ 日志 sidecar 与 Loki 的部署耦合 | Java 只写 stdout，节点级采集 |
| Agones（Fleet、FleetAutoscaler、SDK RBAC、高密度计数） | 5.1 D14（`scene-channels-spec.md:1077`）、6.2 N4（`battle-node-spec.md:1464`）；agones 7.1K 也不满足 §2。scene 与 battle 是常驻进程，有自己的租约、目录和准入 |
| gate-entry 单一 Service | 令牌绑 gate（握手有 `wrong_gate`），与基线 D90 同理 |
| `centre`、Go gateway 清单、auth 条目 | 基线遗留物（B8） |
| 仓库外制品根、离线 tar、fetch / import / retention 脚本（约 1.3K 行 PowerShell） | 分发走 registry + 短期 Actions 制品；没有隔离网络的目标机（Q30） |

**Agones 能力在 Java 里的替代**：分配许可 → scene-manager 的频道计划与软预占（5.1）、battle 准入闸（6.2）；排空标签 → gate 排空标记（3.5）、scene 停服前疏散（5.5）、
battle 运维排空（J6）；loop 心跳停发 health → `logicLoop` liveness（J5）；FleetAutoscaler → 不做（Q16），频道数由 scene-manager 管。

### 5.2 namespace、chart 与 release

```
<ns>-infra   （缺省 xuanming-java-infra；只给 dev / CI / 演示）
  release xm-infra    chart deploy/helm/xm-infra      mysql / redis / kafka（StatefulSet + PVC）
<ns>         （缺省 xuanming-java；一个环境一个）
  release xm-platform chart deploy/helm/xm-platform   xm-gateway xm-login xm-scene-manager xm-friend xm-chat xm-team xm-guild
                                                      xm-trade xm-data xm-battle [xm-match]，以及 NetworkPolicy、PDB、可选 Ingress / PodMonitor / 规则包装
  release xm-zone-<id> chart deploy/helm/xm-zone      xm-gate-z<id>（StatefulSet + headless + 每序号 Service + PDB）、xm-scene-z<id>（Deployment + PDB）

deploy/helm/
  xm-lib/       type: library。Java 进程的公共模板：工作负载骨架、探针、securityContext、env、secretKeyRef、注解、资源与 JVM 参数
  xm-infra/ xm-platform/ xm-zone/   各带 values.schema.json
  values/       ci.yaml、prod.example.yaml（不含秘密）
  zones/        zone-<id>.yaml（每个 zone 一份；文件名里的 id 必须等于文件内 zoneId，xm-ops 校验）
```

- **一个环境 = 一个应用 namespace = 一套 Redis / MySQL 库 / Kafka**。不支持多个环境共享一个 Redis：节点号租约与防护代次的键里没有「集群 / 环境」段（`tools.md:416`），基线靠 `ClusterId` 区分（`k8s_deploy.ps1:22`），Java 靠物理隔离（Q50）。
- **为什么全局服务和各 zone 放同一个 namespace**：按 zone 部署的只有 gate / scene；Secret 只存一份、Service 用短名（与 compose 同名，环境变量两边一致），NetworkPolicy 简单。
- **为什么 infra 单独一个 namespace**：误删应用 namespace 不会带走 PVC（沿用基线分隔理由）；NetworkPolicy 只放行来自应用 namespace 的流量。
- **为什么每个 zone 一个 release**：开关一个 zone 不碰别的 zone；`helm history` / `helm rollback` 按 zone 粒度。
- **标签**：`app.kubernetes.io/{name,instance,part-of=xuanming-java,version}`；zone 级资源另加 `xm/zone: "<id>"`（Prometheus 里是 `__meta_kubernetes_pod_label_xm_zone`）。

### 5.3 服务发现

- 每个 Dubbo 调用方用直连 URL 指向 Service 名：`tri://xm-login:20881`、`tri://xm-scene-manager:20882`、`tri://xm-friend:20883` … `tri://xm-match:20888`，就是 7.1 §6.4 那组 `XM_*_URL` 占位符。
- 只做直连的提供方（login、scene-manager、社交五服、match）**不设 `XM_ADVERTISE_HOST`**：保持缺省 127.0.0.1 时 Dubbo 把它当无效绑定、监听 0.0.0.0（`deploy-ci-spec.md:444-447`），Service 与 port-forward 都能连；入站由 NetworkPolicy 收口。
- 按节点直连的不经 Service：scene 的 `XM_ADVERTISE_HOST=$(POD_IP)`（链路 / 资产通道地址写进目录；资产通道因此只绑 Pod IP，H1），battle 的目录 `rpc_host=$(POD_IP)`。
- **不部署 Nacos 的理由**：xm-scene 的 `scene-manager-url` 本来就只支持直连（7.1 Q7）；少一个有状态组件；kube-proxy 已提供 L4 均衡与故障转移；Dubbo 每次调用都有 HMAC 鉴权（PARITY「Dubbo 调用方鉴权」行），不依赖注册中心隔离。
- **代价**：Triple 跑在 HTTP/2 长连接上，只能做到按连接均衡；Dubbo 层的优雅下线通知也收不到（H14）。tech-stack 要补一句「K8s 部署不用 Nacos，Service DNS 即发现；Nacos 只留给非 K8s 的多机部署」。

### 5.4 工作负载规格

**所有 Java 容器的共同约定**（由 xm-lib 模板统一生成）：

- **镜像**：生产用 `ghcr.io/luyuan-java/<模块>@sha256:…`（由 `xm-ops deploy images` 从发布 manifest + digest 文件生成）；也接受不可变 tag。`imagePullPolicy: IfNotPresent`。CI 用本地导入的 `xuanming-java/<模块>:<sha12>`。
- **探针**（7.1b 已打开 `management.endpoint.health.probes.enabled`）：startup 打 `/actuator/health/liveness`，period 5 s × failureThreshold 36 = 180 s 预算（覆盖表加载与 Dubbo 导出）；
  liveness 打 `…/liveness`，period 10 s、timeout 3 s、failureThreshold 3；readiness 打 `…/readiness`，period 5 s、failureThreshold 3。
  分组只含 `livenessState` / `readinessState` 加本进程的领域指示器（§5.6），**不含 db / redis**，理由同基线 `java-svc/gateway.yaml:63-68`。
- **管理端口** `XM_MANAGEMENT_ADDRESS=0.0.0.0`：kubelet 探针从节点打 Pod IP、运维从 port-forward 打回环，两者都要（盘点隐患④；§0.3）。
- **资源**：memory request = limit，模板对 limit 用 `required`，缺了渲染失败（H2）；CPU 只写 request。`JDK_JAVA_OPTIONS` 由模板拼：
  `-XX:MaxRAMPercentage=60 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=<max(2, ceil(CPU request))>`（不写 CPU limit 时 JVM 会按节点核数开线程池）。
- **调度**：`topologySpreadConstraints`，maxSkew 1、`kubernetes.io/hostname`、`ScheduleAnyway`（相当于基线的 preferred 反亲和，单节点 CI 也能调度）；`priorityClassName` 留值、缺省空（Q20）。
- **注入**：Downward API 给 `POD_IP`、`XM_POD_NAME`；秘密见 §7。
- **停机**：有 Service 流量的提供方（gateway、Dubbo 提供方）加 `preStop: exec sleep 5`，等 kube-proxy 摘 endpoint；宽限期 = preStop + `timeout-per-shutdown-phase` + 10 s 余量（§0.6 #15）。
  scene / gate / battle / data 不加 preStop（没有需要等摘除的 ClusterIP 流量，免得占宽限期）。

| 工作负载 | release / kind | 副本 CI / 生产起点 | 更新策略 | 端口与 Service | readiness 额外判据 | liveness 额外判据 | PDB | 宽限 / preStop | 内存 生产 / CI |
|---|---|---|---|---|---|---|---|---|---|
| xm-gateway | platform / Deployment | 1 / 2 | RollingUpdate maxSurge 1、maxUnavailable 0 | 18081 → Service `xm-gateway`（ClusterIP；可选 Ingress，只路由 `/api`）；管理 18105 | — | — | maxUnavailable 1 | 30 s / 5 s | 768Mi / 512Mi |
| xm-login | platform / Deployment | 1 / 2 | 同上 | 20881 → `xm-login`；18101 | — | 租约永久丢失 | maxUnavailable 1 | 40 s / 5 s | 768Mi / 512Mi |
| xm-scene-manager | platform / Deployment | 1 / 2 | 同上 | 20882 → `xm-scene-manager`；18102 | —（`worldChannels` 不进，H3） | 发号租约永久丢失 | maxUnavailable 1 | 40 s / 5 s | 768Mi / 512Mi |
| xm-friend / chat / team / guild / trade | platform / Deployment | 各 1 / 各 2 | 同上 | 20883–20887 → 同名 Service；18107–18111 | — | team / guild / trade：租约永久丢失 | maxUnavailable 1 | 40 s / 5 s | 768Mi / 512Mi |
| xm-data | platform / Deployment | 1 / 1 | **Recreate**（`data-ops-spec.md:143`） | 18106 → `xm-data`（ClusterIP，运维经 port-forward） | — | 租约永久丢失（若持有） | 无 | 30 s / 无 | 1Gi / 640Mi |
| xm-battle（6.2） | platform / StatefulSet，Parallel | 1 / 2 | **OnDelete**（先排空再删，§5.9） | 12000 → 每序号 Service `xm-battle-<i>`；21200 走 Pod IP（目录）；18112 | 准入 OPEN | 逻辑线程卡死；租约丢失且房间归零 | **maxUnavailable 0** | 30 s / 无 | 1Gi / 512Mi |
| xm-match（6.4） | platform / Deployment | 1 / 2 | RollingUpdate | 20888 → `xm-match`；18113（`match-spec.md:916`） | — | 租约永久丢失 | maxUnavailable 1 | 40 s / 5 s | 768Mi / 512Mi |
| xm-gate-z<id> | zone / StatefulSet，Parallel | z1 2、z2 1 / 2 | **OnDelete**（gate-roll，§5.9） | 11000 → headless + 每序号 Service `xm-gate-z<id>-<i>`；18103 | 在接客 | 节点号租约丢失 | **maxUnavailable 0**（Q10） | 40 s / 无 | 1Gi / 512Mi |
| xm-scene-z<id> | zone / Deployment | z1 2、z2 1 / 2 | **maxSurge 100%、maxUnavailable 0**，minReadySeconds 10，progressDeadlineSeconds 600（Q9） | 链路 21000 / 资产通道 21100 走 Pod IP（目录）；18104；**不建 Service** | 身份 VALID、不在疏散、ACTIVE 频道 ≥1 | 逻辑线程卡死（TAKEN 时进程自行退出） | **maxUnavailable 1** | **≥ 60 s，模板按公式算**（J7） | 2Gi / 768Mi |
| mysql | infra / StatefulSet + PVC | 1 / —（生产托管） | RollingUpdate（单副本即重建） | 3306 → `mysql` | `mysqladmin ping --protocol=tcp`（socket 在 initdb 期间谎报，`deploy-ci-spec.md:325-326`） | tcp | minAvailable 1 | 60 s | — / 768Mi |
| redis | infra / StatefulSet + PVC | 1 / — | 同上 | 6379 → `redis` | `redis-cli ping`，PONG 或 NOAUTH（照基线 `redis.yaml:108-135`） | tcp | minAvailable 1 | 30 s | — / 256Mi |
| kafka | infra / StatefulSet + PVC，`fsGroup` | 1 / — | 同上 | 9092 → `kafka`（通告 headless FQDN） | `kafka-broker-api-versions.sh`，探针里把 `KAFKA_HEAP_OPTS` 压小（基线 README `:1004-1008`） | tcp | minAvailable 1 | 60 s | — / 1Gi |

**infra 的补充约定**（只 dev / CI / 演示）：
- **Redis**：`--appendonly yes --appendfsync everysec`（`scene-battle-spec.md:1139`），`maxmemory-policy noeviction`（里面是锁、租约、组队等权威数据），CI 也设 `--requirepass`，顺带演练 `XM_REDIS_PASSWORD` 注入路径。
- **MySQL**：`MYSQL_DATABASE=xm_java`（xm-team 冷启动的坑，`deploy-ci-spec.md:993`），initdb 脚本用 Secret 里的口令建应用账号 `xm_app` 并只授 §7.5 的权限——CI 就用它跑，权限不够会当场暴露。
- **Kafka**：KRaft 单节点，关自动建 topic，副本因子 1，挂 PVC（`fsGroup` 取镜像运行用户的 gid，首轮 CI 核对，H8）。只改 replicas 不会带来冗余（基线 README `:995-999`）。
- 镜像 digest 与 `deploy/compose/infra.yaml` 逐字相同，CI 守卫比对（不再出现基线 compose 与 K8s 版本漂移，`deploy-ci-spec.md:219`）。

### 5.5 客户端入口（取值客户端可见，形状不变）

gate 与 battle 都由 values 的 `exposure.mode` 决定通告地址：

| 模式 | 通告主机 | 通告端口 | Service | 适用 |
|---|---|---|---|---|
| `podIP` | `$(POD_IP)` | 11000 / 12000 | 只有 headless | CI、集群内 robot（单节点 k3s 的宿主能路由到 Pod 网段）；**不得用于对外** |
| `hostIP` | Downward API `status.hostIP` | `portBase + 序号` | 每序号 NodePort，`externalTrafficPolicy: Local` | CI、节点 IP 可直达的裸金属：通告的就是 Pod 所在节点，Local 一定命中，并保留客户端 IP |
| `publicHost` | 固定的 `publicHost`（外部 L4 / IP，可带 `{ordinal}` / `{zone}`） | `portBase + 序号` | 每序号 NodePort，**`Cluster`** | 一个外部 L4 转发整段 NodePort：L4 不一定打到 Pod 所在节点，所以不能用 Local |
| `loadBalancer` | 带 `{ordinal}` 的主机模板，如 `gate-{ordinal}.z{zone}.example.com` | `clientPort` | 每序号 LoadBalancer，Local | 托管云；DNS 由运维提供；成本见 H22 |

- **硬约束**：同一 zone 的每个 gate 必须有自己的客户端地址。令牌绑 gate 节点号，共用地址会让握手回 `wrong_gate`；5.4 的「gate 目录同地址只留最新」还会把共用地址的 gate 合并掉（`zone-travel-spec.md:119`、`:606`）。
  schema 强制：副本 > 1 时 `loadBalancer` 的主机必须含 `{ordinal}`；NodePort 两种模式按序号错开端口。事后由 A21 兜底。
- **NodePort 段**：每个 zone 一段 `[portBase, portBase + portBlock)`（`portBlock` 缺省 10，副本数不得超过它），battle 一段；全部落在 30000–32767 且互不重叠，`xm-ops deploy preflight` 读集群里已装的 release 与本次 values 一起校验（同基线 D88）。
- **组合禁令**：`publicHost` + `Local` 渲染失败（外部 L4 打不到时直接丢流量）；`hostIP` + `Cluster` 只 WARN（丢客户端真实 IP，限流按 IP 失真）。
- **Java 侧**（J1、J2）：按 `XM_POD_NAME` 末尾的 `-<n>` 解析序号（任何 K8s 版本都能用，不依赖 `apps.kubernetes.io/pod-index`）；`xm.gate.advertise-port-base` 有值时通告端口 = base + 序号，与 `advertise-port` 互斥，同配即拒启；
  通告主机支持 `{ordinal}` / `{zone}`；解析不出序号而又配了 base 或模板时拒启（fail-closed）。不在 shell 里拼启动命令：镜像是 exec 形式，java 必须是 PID 1 直接收 SIGTERM（7.1 §3.3）。
- **battle**：必须先拆出 `xm.battle.client-advertise-host`（票据给客户端用），目录 `rpc_host` 仍是 `xm.advertise-host` = Pod IP（7.1 Q9；`xm-battle application.yaml:47-48` 现在一址两用）。battle 地址要对所有 zone 的客户端可达（`spectate-spec.md:347`）。
- **gateway**：Service 缺省 ClusterIP，可选 NodePort / LB；可选标准 `networking.k8s.io/v1` Ingress，只路由 `/api`，**不设缺省 `ingressClassName`**（基线缺省 nginx，`k8s_deploy.ps1:216`；
  据映射稿引用的 kubernetes.io 公告，ingress-nginx 社区版已宣布退役）。生成 Ingress 时 `gateway.trustedProxies` 必填（schema 强制），映射到 `xm.gateway.rate-limit.trusted-proxies`（`xm-gateway application.yaml:102`），
  否则全体玩家共用一个限流桶（同基线 D91）；Java 已显式关掉 `forward-headers-strategy`（`:9`）。

### 5.6 探针与可用性语义

| 进程 | readiness 额外判据 | liveness 额外判据 | 理由 |
|---|---|---|---|
| gate | `gateAccepting`：还在接客（租约丢失或停机开始后 DOWN） | `nodeLease`：节点号租约 `isLost()` | 租约丢失时 gate 停接客、关全部会话（`GateNode.java:272-282`），只剩重启能恢复。**不用 `isValid()`**：Redis 一抖动就会把所有 gate 的每序号 Service 同时摘空，正是基线 `gateway.yaml:63-68` 警告的共享依赖问题 |
| scene | `sceneServing`：身份 VALID（5.5 之前用租约 `isValid()`）、不在疏散、ACTIVE 频道 ≥ 1 | `logicLoop`：逻辑线程每 1 s 跑一次心跳任务，30 s 没跑就 DOWN | scene 没有 Service，readiness 只影响滚动节奏与 PDB 计算；5.5 要求挂起 / 疏散时 DOWN（`scene-drain-spec.md:110`、`:679`）。TAKEN 时 5.5 疏散后自行退出，不靠 liveness |
| battle | `battleAdmission`：准入 OPEN（运维排空、租约丢失、停机都会关） | `logicLoop`；`nodeLease`：租约丢失**且**房间归零（现在只打一行「可安全重启」，`BattleNode.java:312-317`） | 准入闸只朝一个方向走（`AdmissionPhase.java:3`） |
| login、team、guild、trade、scene-manager、data、match | — | `nodeLease`：任何持有的发号租约 `isLost()` | 代码注释写明租约丢失「需要重启本进程」（`LoginConfiguration.java:212-221`、`TeamConfiguration.java:111-119`）；无效期间只拒发号，不影响其他请求，所以不进 readiness |
| gateway、chat、friend | — | — | — |

- 接法：每个判据是普通 `HealthIndicator` bean，经 `management.endpoint.health.group.{readiness,liveness}.include=<State>,<指示器>` 接入；scene-manager 已有的 `worldChannels`（`WorldChannelsHealthIndicator`）只给 `/actuator/health` 与告警用。
- **liveness 阈值**：`logicLoop` 30 s，加探针 failureThreshold 3 × 10 s，卡死后约 60 s 被重启。卡死的 scene 本来就存不了盘、也服务不了人；重启时写回依赖逻辑线程，大概率在宽限期末被 SIGKILL，
  损失与 kill -9 相同（周期存盘之后的增量，architecture §7 已接受），归属租约 30 s 后过期，玩家可在别处重进（Q11）。
- **Redis 长时间不可用**（> 租约 TTL 15 s）会让这些进程的租约判丢，liveness 依次 DOWN、重启、在 Redis 回来之前 CrashLoopBackOff；这是有意的：它们本来就要重启才能恢复，K8s 只是替人做了（H20、Q12）。

### 5.7 网络策略与安全上下文

**NetworkPolicy**（K8s 内置 API，是否生效取决于 CNI；k3s 自带控制器）：

| namespace | 规则 |
|---|---|
| 应用 ns | 缺省拒绝全部入站，再放行：① 带 `app.kubernetes.io/part-of=xuanming-java` 的 Pod 之间全部端口（Dubbo 2088x、链路 21000、资产通道 21100、battle 控制面 21200；每次调用另有 HMAC，这一层防别的租户）；② gate 11000、battle 12000 来自任意来源；③ gateway 18081 来自 Ingress controller 所在 namespace（选择器可配；Service 是 NodePort / LB 时放开任意来源）；④ 管理端口 181xx 来自监控 namespace（选择器可配），另可配 `probeSourceCIDRs`（节点网段）给 kubelet 探针用——多数 CNI 不拦宿主到 Pod 的流量，CI 实测（§11.3） |
| infra ns | 只放行来自应用 ns 的流量 |
| 出站 | 不限制（DNS、托管依赖、GHCR 拉取都要；YAGNI） |

这同时堵住了盘点隐患②「Dubbo 端口对整个集群开放」（`java-infra.md:342`）。port-forward 走 kubelet 进 Pod 网络的回环，不受 NetworkPolicy 影响。

**securityContext**（Java 容器）：`runAsNonRoot`、uid / gid 10001（7.1 镜像约定）、`readOnlyRootFilesystem: true`，`/tmp` 与 `/home/xm` 挂 emptyDir（Tomcat 工作目录、Netty native 库、hsperfdata、
Dubbo 文件缓存，`deploy-ci-spec.md:568-574`）、`allowPrivilegeEscalation: false`、drop ALL、`seccompProfile: RuntimeDefault`、`automountServiceAccountToken: false`。infra 容器沿用各镜像缺省，不强制只读根。

### 5.8 运维 CLI 模块 `xm-ops`

- **形态**：Maven 模块 `xm-ops`，包 `com.game.ops`，普通 `main`（`com.game.ops.OpsMain`），像 xm-robot 一样用 spring-boot-maven-plugin `repackage` 打成可执行 jar（`xm-robot/pom.xml:73-80` 同法）；
  不是服务、不出镜像、不进 compose / chart。7.1 的模块漂移守卫把它与 xm-robot 一起排除。
- **依赖**：xm-common（`ProductionGate`、`RunMode`，保证门禁只有一份实现）；Jackson 与 SnakeYAML（版本由 Spring Boot BOM 管理，视为事实标准）；JDK `HttpClient`、`ProcessBuilder`。不引入 Kubernetes Java 客户端（官方 4.0K、fabric8 3.7K 都不到 2 万）。
- **外部命令**：只调 `helm`、`kubectl`、`docker`、`git`、`gh`，一律 argv 形式，不经 shell；**秘密永不进 argv**，只经 stdin 或读文件；从不打印秘密、不碰 kubeconfig 凭据。
- **运维 HTTP**：自己拉起 `kubectl port-forward`（随机本地端口）到 xm-data 18106、gate 18103、battle 18112、scene 18104，用 `XM_ADMIN_TOKEN` 与 `X-Xm-Operator`（`AdminAuthFilter.java:27-28`）。
- **通用约定**：写操作前一次过完门禁；`--dry-run` 只打印将执行的命令；任一步失败即停，并打印当前状态与人工补救命令；退出码 0 成功 / 1 判定失败 / 2 用法或工具自身错误。
- **可测性**：外部命令经 `CommandRunner` 接口，单测用假实现断言命令序列（金样），覆盖拒绝路径「在任何写之前退出」。

| 命令 | 作用 |
|---|---|
| `release check --version vX.Y.Z` | 版本号、CHANGELOG 段、工作树干净、tag 不存在或指向 HEAD、同一提交的 CI 与 Integration 已成功（经 `gh api`，§5.10） |
| `release manifest` / `release verify` / `release digests` | 写 manifest、发布说明、sha256sums；校验目录与 images.tar；推送后记 digest |
| `deploy secrets --env-file <f> --profile <p>` | 按 §7.2 矩阵校验并经 stdin 建 / 更新 Secret |
| `deploy preflight --profile <p> [--release-manifest m] [--digests d] [--zone-values …]` | §5.11 第二层全部检查，不写集群 |
| `deploy images --release-manifest m --digests d --out values-images.yaml` | 每个模块写成 `ref@sha256:…` |
| `deploy platform-up [--with-infra]`、`deploy zone-open`、`deploy zone-close`、`deploy status` | §5.9 |
| `deploy gate-roll` / `gate-scale` / `battle-roll` / `battle-scale` / `rollback` | §5.9 |
| `obs check` | 规则与看板引用的指标名对照各模块指标目录（§4.8 第 2 条） |
| `obs export-fallback --loki <url> --since … --until … --out <f.jsonl>` | 从 Loki 导出审计兜底行，供 xm-data 回灌工具读（J10） |

### 5.9 运维流程

| 流程 | 步骤（任一步失败即停） |
|---|---|
| **platform-up** | `deploy secrets` → 可选 `helm upgrade --install xm-infra … --wait` → preflight → `helm upgrade --install xm-platform … --wait`（`--set secretsRevision=<Secret resourceVersion>`）→ 等全部全局服务 readiness → 打印各 Pod `/actuator/info` 的 `build.commit` / `build.release`。gateway 的 seed-zones 关掉（J12） |
| **zone-open --zone Z --name N [--capacity C] [--status open\|preview --open-time T]** | ① 前置：xm-platform 就绪；目录里这个 zone 不存在或是 CLOSED / MAINTENANCE。② 目录：不存在就 `POST /admin/zones`（`manual_status=1` MAINTENANCE），存在就 `POST /admin/zones/Z/maintenance`（`ZoneAdminController.java:89-131`）；玩家此时拿到 503 `zone_maintenance`（码同基线）。③ `helm upgrade --install xm-zone-Z deploy/helm/xm-zone -f deploy/helm/zones/zone-Z.yaml --wait --timeout 10m`。④ 等每个 scene Pod Ready（含「有 ACTIVE 频道」）、每个 gate Pod Ready、`GET /admin/gates/Z` 列出全部 gate。⑤ `POST /admin/zones/Z/open`，或 PUT 成 PREVIEW 带开放时刻（503 `zone_not_open`） |
| **zone-close --zone Z [--announce msg] [--grace dur]** | ① 目录置 MAINTENANCE（约 1 s 生效，排队中的轮询同样被拦）。② 可选公告并等 `--grace`。③ **先停 gate**：把 gate StatefulSet 缩到 0（gate SIGTERM 停接客、关会话，scene 随会话断开逐个写回并记重连租约），等该 zone 各 scene 的 `xm_scene_players` 合计归零（上限 60 s）。顺序同 `stop-slice.sh:7`「先 gate 后 scene」，避免 scene 停机时还要疏散（`scene-drain-spec.md:869-870`）。④ `helm uninstall xm-zone-Z --wait`。⑤ `SREM xm:world:zones Z`（`RedisKeys.worldZones` 注释要求人工做；否则 A40 一直响）。⑥ GET 后 PUT 成 CLOSED（`manual_status=2`；之后 assign-gate 回 503 `zone_closed`，码同基线）。**不删任何玩家数据，不清其他 Redis 键**（Q23） |
| **status [--zone Z]** | `kubectl get sts,deploy,pod,svc,pdb -l xm/zone=Z`、目录状态、各 gate 的节点号与在线数（`GET /admin/gates/Z`）、各 scene 频道数、`/actuator/info` 版本 |
| **gate-roll --zone Z [--ordinal i] [--force-last]** | 对 `controller-revision-hash` ≠ `updateRevision` 的 gate Pod 按序号倒序逐台：① port-forward 取 `GET /gm/identity`（区、节点号、实例）。② `GET /admin/gates/Z` 核对身份在目录里。③ `POST /admin/gates/drain {zone_id, node_id, ttl_sec}`；本区最后一个接客的 gate 服务端回 409 `last_gate`，不带 `--force-last` 就停（`GateDrainAdminController.java:20-33`）。④ 轮询直到 drained（`below_threshold` / `deadline`，gateway 判定循环每 5 s 一轮，deadline 缺省 25 min，`xm-gateway application.yaml` gate-drain 段）；中途发现身份变了就撤回标记并中止。⑤ `kubectl delete pod`（不是驱逐，不受 PDB 0 限制）。⑥ 等新 Pod Ready、目录里出现新实例。⑦ `DELETE /admin/gates/drain/Z/<旧节点号>`。比基线 `k8s_gate_drain.ps1` 简单：身份接口直接给出节点号与实例，不用拿 podIP 去 etcd 反查。这就是 PARITY 第 99 行的「Java 待做：K8s 滚动替换编排」 |
| **gate-scale --zone Z --replicas N** | 扩容直接改副本；缩容先按 gate-roll 的①–④排空最高的几个序号，再改副本 |
| **battle-roll [--ordinal i]** / **battle-scale** | ① `POST /admin/battle/drain`（J6：关准入、目录 `accepting=false`、readiness DOWN；单向）。② 轮询 `xm_battle_rooms` 归零，上限整场期限 300 s（`battle-node-spec.md:195`）+ 60 s；超时就停下并报告，`--force` 才删（会作废在打的局，`battle-node-spec.md:1194-1196`）。③ 删 Pod，等 Ready |
| **scene 滚动**（`helm upgrade xm-zone-Z`） | Deployment 先起全部新 Pod（maxSurge 100%），新 Pod 拿到频道、readiness UP 后旧 Pod 一起收 SIGTERM；旧 Pod 按 5.5 把玩家疏散到新节点（缺省开，预算 15 s），剩下的写回。每个玩家至多被迁一次（缓解 5.5 R10，`scene-drain-spec.md:1106`）。资源紧张的集群可把 maxSurge 改为 1（每台一次，最坏连续迁 N−1 次） |
| **全局服务滚动**（`helm upgrade xm-platform`） | Deployment 逐个 surge；battle 是 OnDelete，`helm upgrade --wait` 之后必须接 `battle-roll`（H5）；xm-data 是 Recreate，审计消费短暂暂停（H15） |
| **rollback --release R --revision N** | `helm rollback R N --wait`。镜像按 digest / 不可变 tag 引用，回滚真的会换镜像（基线 `latest` + `IfNotPresent` 踩过的坑，`tools.md:420`）。gate / battle 回滚后要接 gate-roll / battle-roll。库结构只扩不缩：回滚前核对 manifest 的 `db_migration_head`，跨越了结构变更的回滚要人工评估 |

### 5.10 发布流水线

**发布身份**：
1. 版本号 `vX.Y.Z[-pre]`，规则同基线 §2.1，在 `xm-ops` 的 `com.game.ops.release.ReleaseRules` 里定义一次（`Pattern.matches` 整串匹配、数字段用 `[0-9]`、拒空白、小写 v、无前导 0），workflow 只调它。
2. **Maven 的 `project.version` 不动**（Q26）：根 pom 加属性 `xm.build.release`（缺省空串），作为 build-info 的 additionalProperty `release` 输出，`/actuator/info` 显示 `build.release / commit / contract`；
   镜像构建参数 `XM_BUILD_VERSION=vX.Y.Z` 进 OCI `version` label 与 `/app/BUILD_INFO`（7.1 已有这两个出口）。快照构建 `build.release` 为空。
3. 镜像 tag：快照 `<sha12>`（7.1）；发布 `vX.Y.Z-<sha12>`，长度 ≤128；黑名单同基线；永不打 `latest`。
4. **启动版本行**（J8）：每个进程打一行 `service starting service=xm-gate release=v1.2.3 commit=<40> contract=<40> run_mode=prod`，对标 Go 的 `buildinfo.go:71-72`；由 xm-common 的 `StartupBanner` 读
   classpath 上的 `META-INF/build-info.properties`（只用 `java.util.Properties`）。
5. **CHANGELOG.md**：中文，Keep a Changelog 1.1.0；段落规则逐条照抄基线 §2.6；每个功能批次在 `[Unreleased]` 下写「对部署和运维的影响」与需要的库迁移 `Mn`（Q27）。
6. **四处一致**：git tag（用户打）= workflow 输入 = CHANGELOG 段 = 镜像自报版本（OCI label、BUILD_INFO、`build.release`）。

**`.github/workflows/release.yml`**（`workflow_dispatch`，输入 `version`；`permissions: contents: read, actions: read`；全仓一个 concurrency group、不取消；`ubuntu-24.04`；action 按 SHA 钉死；输入经环境变量进脚本，约定同 7.1 §4.2）：
1. 检出（`fetch-depth: 0`、`persist-credentials: false`）→ setup-java 21 → 环境自检（docker、compose ≥ 2.20、磁盘）。
2. `./mvnw -B -ntp -pl xm-ops -am -DskipTests package`，然后 `xm-ops release check --version "$VERSION"`：版本号；CHANGELOG 恰好一段且非空；`git status --porcelain=v1 --untracked-files=all` 为空；
   `refs/tags/$VERSION` 不存在或指向 HEAD；**同一提交的 `CI` 与 `Integration` workflow 都已成功**（`gh api` + `GITHUB_TOKEN`，`actions: read`）。放在最前面，避免白跑长构建。
3. `./mvnw -B -ntp -DskipTests package -Dxm.build.commit=$GITHUB_SHA -Dxm.build.contract=<SOURCE> -Dxm.build.release=$VERSION`。单测不重跑：源码与 CI 已验证的提交相同，只有构建戳不同（Q32）。
4. 用 7.1 的 Dockerfile 构建全部进程镜像，`XM_IMAGE_NAMESPACE=ghcr.io/luyuan-java`、tag `vX.Y.Z-<sha12>`；镜像断言：revision = 40 位 SHA、version = vX.Y.Z、`com.game.contract.mmorpg-commit` = SOURCE、User = `10001:10001`、BUILD_INFO 正确、全部同一提交。
5. **用这批镜像跑整栈冒烟**（`deploy/ci/stack-smoke.sh`，与 integration.yml 共用：robot 第一期 + 停机断言）。被测的镜像就是要发布的镜像——构建戳不同的镜像必须重新冒烟。
6. `docker save` 全部镜像进**一个** `images.tar`（层去重）；`xm-ops release manifest` 写 `release-manifest.json`、`RELEASE_NOTES.md`、`sha256sums.txt`（LF、序数排序、不覆盖已存在文件，规则同 `artifacts_lib.ps1:176-194`）；
   `xm-ops release verify` 自检：sha256sums 一致、tar 内 `manifest.json` 的 RepoTags 恰好覆盖全部模块（补基线「批量 save 丢镜像」的检查）。`helm package` 三个 chart（version = X.Y.Z，appVersion = vX.Y.Z）一起放进制品。
7. 上传两个制品：`xm-release-vX.Y.Z`（manifest、notes、sha256sums、chart 包，保留 90 天，同基线）与 `xm-release-vX.Y.Z-images`（images.tar，保留 7 天，只给 push 用）。
8. Step Summary 打印两条人工命令：`git tag vX.Y.Z <sha> && git push origin vX.Y.Z`，以及「触发 release-push.yml，填 run_id」。**不打 tag、不建 GitHub Release（建 Release 会产生 tag）、不部署**（AGENTS §5，同基线 `release.yml:12-17`）。

**`.github/workflows/release-push.yml`**（用户看过制品后手动触发，输入 `version`、`run_id`；只有它拿 `packages: write`）：
1. 下载该 run 的两个制品 → `xm-ops release verify` → `docker load` → 每个镜像 ID 等于 manifest 里的 `image_id`（照 `import_images.ps1:17-23`）。
2. 对每个 ref 先 `docker manifest inspect`：**远端已存在就拒绝**（tag 不可覆盖）。
3. `docker login ghcr.io`（`GITHUB_TOKEN` 经 stdin，用 runner 自带 CLI，不引入第三方 action）→ 逐个 push → 读回 digest（只认同 repo；同 repo 多个 digest 拒绝，规则同 `release_common.ps1:496-562`）。
4. `xm-ops release digests` 写 `digests-vX.Y.Z.json` 上传为制品。manifest 本身不可变，digest 另存（同基线 checklist `:96-99`）。

**发布说明**（`RELEASE_NOTES.md`）：CHANGELOG 该段原文；需要的库迁移；「契约变更」一节由 7.5 的 `ContractGate` 以上一个发布 tag 的契约面为旧、当前为新算出（xm-ops 以库依赖调用 `xm-contract-check`，只取报告，不沿用它的退出码 0 / 2 / 3）；7.5 未落地时这一节写「未生成」。

**manifest 字段**：`version`、`commit`（40 位）、`contract_commit`、`created_at`（提交时间，可复现）、`java` / `spring_boot` 版本、`tables{manifest_sha256, file_count}`、
`battle_fingerprint`（6.1 战斗配表指纹，scene 与 battle 同版本才可信）、`db_migration_head`（`docs/design/db-migrations.md` 最新的 `Mn`，判断回滚能否跨结构变更）、`chart_version`、
`images[{module, ref, image_id, labels}]`、`changelog`（该段原文）、`workflow{run_id, url}`。

**不做**：离线 tar 分发与仓库外制品根（Q30）；jar 进制品（约 1 GB、镜像里已有）；签名 / SBOM / 扫描 / 多架构（Q31）。

### 5.11 发布门禁（两层）

**第一层：运行期 `ProductionGate`（xm-common `com.game.common.ops`，纯函数，写法照 `BattleSecretPolicy`）**

- 每个进程在自己的配置类里显式声明一个早于任何 `SmartLifecycle` 的 bean 调用它（Tomcat 管理端口、Netty、Dubbo 都在 lifecycle 阶段才开端口，这时还没开）；12 个调用点（6.4 后 13 个），不用自动装配，符合「显式依赖」。
- 所有进程都读 `xm.run-mode: ${XM_RUN_MODE:prod}`（现在只有 gate、scene、trade、battle 读，J8 补齐 login、gateway、data、scene-manager、friend、chat、team、guild）；不认识的值按 prod 并 WARN（`RunMode.isRecognized`）。
- prod 下有 FAIL 就抛 `IllegalStateException` 拒启，日志只写变量名不写值；dev / test 只 WARN（缺失类仍按现有代码拒启，如 `DubboCallAuth.java:56-57`）。
- 每个进程只检查它看得到的秘密（矩阵见 §7.2）；长度按 UTF-8 字节、去 ASCII 首尾空白（口径同 `BattleSecretPolicy`）；占位串黑名单沿用基线 §2.9（不区分大小写、去空白后比较），另加 Java 的开发缺省值。

| 检查 | 进程 | prod | 说明 |
|---|---|---|---|
| HMAC 类秘密存在、≥32 字节、非占位：`XM_DUBBO_SECRET`、`XM_GATE_TOKEN_SECRET`、`XM_NODE_LINK_SECRET`、`XM_BATTLE_TOKEN_SECRET`、`XM_ASSET_OP_SECRET_GUILD` / `_TRADE` | 各自 | FAIL | 现在 Dubbo / 链路密钥只查非空（`DubboCallAuth.java:56`、`NodeLinkAuth.java:46`） |
| 可选秘密设了就要 ≥32：`XM_ADMIN_TOKEN`、`XM_GM_ADMIN_SECRET` | data、trade、battle；gate、scene | FAIL | 不设时接口一律 503 / 拒绝，保持现状 |
| 口令 ≥12、非占位：`XM_MYSQL_PASSWORD`；`XM_REDIS_PASSWORD`（prod 必填） | 连库 / 连 Redis 的进程 | FAIL | 同基线 B 节 |
| `XM_MYSQL_USER` 未设或为 `root` | 连库进程 | FAIL | 缺省值是 root（如 `xm-login application.yaml:22`）；最小权限账号见 §7.5（Q18） |
| 秘密两两不同（进程可见的集合） | 全部 | FAIL | 落实 6.2 Q11（`battle-node-spec.md:1512-1513`）；复用才是真风险，「几处一致」对占位串免疫 |
| `xm.login.mode` 必须是 `prod` | login | FAIL | yaml 改成 `${XM_LOGIN_MODE:dev}`（现写死 dev，`:84`），对应基线 `debug.login.devpassword` |
| `XM_GM_ALLOW_REMOTE` ≠ true | gate、scene | FAIL | — |
| `logging.level.{root, com.game, org.apache.ibatis, org.apache.dubbo}` 不是 DEBUG / TRACE | 全部 | FAIL | 对应基线 `debug.jpa.showsql` / `debug.cpp.loglevel`：MyBatis 在 DEBUG 下打 SQL 与参数 |
| actuator exposure ⊆ {health, info, prometheus} | 全部 | FAIL | 对应基线 `debug.actuator.exposure` |
| gateway 限流 rps ≥1、burst ≥ rps | gateway | FAIL（任何模式，启动校验） | 落码时核对现有属性类有没有，没有就补 |
| `XM_GATEWAY_RATE_LIMIT_ENABLED=false` | gateway | WARN | — |

**第二层：部署期 `xm-ops deploy preflight`**（`--profile dev|staging|prod`；dev 下秘密类降为 WARN，同基线 `release_preflight.ps1:84-86`；读集群里的 Secret 只在内存比较，H10）

| 基线节 | Java 判据 | 级别 |
|---|---|---|
| A gate 密钥跨 5 文件一致 | **不适用**：所有进程读同一个 Secret 键，部署侧只有一个来源。改查强度、两两不同（含 battle ≠ gate） | FAIL |
| B 口令 | Secret 存在；每个启用进程按矩阵要的键都在；强度同第一层；prod 下 `XM_MYSQL_USER ≠ root`、`XM_REDIS_PASSWORD` 必填 | FAIL |
| C tag 必须提供且不可变 | values 里每个镜像是 `@sha256:` 或匹配 `^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?-[0-9a-f]{12}$` / `^[0-9a-f]{12}$`；不在黑名单；prod 不许 `-dirty`；全部模块同一个版本。`values.schema.json` 在 `helm lint/install` 时再拦一次 | FAIL |
| D Kafka broker 下界 | prod：`XM_KAFKA_BOOTSTRAP_SERVERS` ≥3 个地址且 `XM_AUDIT_REPLICATION_FACTOR` ≥3 | prod FAIL、staging WARN（Q34） |
| D 分区数一致 | **不适用**：进程启动时按规格核对 topic（`AuditTopicInitializer`） | — |
| E 租约下界 | **不适用**：Java 的租约是代码常量（`PlayerLocationDirectory.java:49` 30 s）并有启动校验（`scene-drain-spec.md:1015-1025` C1–C7） | — |
| F 限流 | prod：`gateway.rateLimit.enabled` 必须显式写（schema 要求）；配了 Ingress 时 `trustedProxies` 非空 | FAIL |
| G 调试开关 | prod：`runMode: prod`（同时关 GM 指令与 trade 播种）、`loginMode: prod`、不设 `XM_GM_ALLOW_REMOTE`、日志级别与 exposure 同第一层 | FAIL |
| H 制品与版本 | 给了 `--release-manifest` 时：manifest 版本 = 镜像 tag 版本；`--digests` 覆盖全部镜像且 digest 一致（补基线缺口 #4）；全部镜像同一 revision；契约 commit 一致。部署后 `status` 再核对 `/actuator/info` 的 `build.release` | FAIL |
| 新增 | NodePort 段不重叠、在 30000–32767、副本 ≤ portBlock；暴露模式组合合法；scene 宽限期 ≥ C2 下限；gate / battle PDB 与更新策略一致；data 保留期在 prod 为 0 时 WARN（Q47）；`XM_LOGIN_DEV_PASSWORD` 出现在 prod env 文件里 WARN | FAIL / WARN |

退出码：0 = 无 FAIL；1 = 有 FAIL；2 = 用法错误或工具自身错误（**显式实现**，补基线 B-R3）。

### 5.12 日志

- **格式**（J13）：12 个进程 yaml 都加
  `logging.structured.format.console: ${XM_LOG_FORMAT:}`（空值 = 文本，本机切片与 `run/logs` 不变）、`logging.structured.json.add.service: ${spring.application.name}`；
  gate / scene 另加 `logging.structured.json.add.zone: ${xm.zone-id}`（login / gateway 也有 `zone-id`，但它们是全局进程，不加）；`logging.structured.json.stacktrace.max-length` 设上限，免得超过 Loki 单行上限。
  Dockerfile 加 `ENV XM_LOG_FORMAT=logstash`，容器里缺省 JSON。`json.add` / `stacktrace.*` 是 Spring Boot 3.5 引入的键，「空值回落文本」也要实测，都以 V8 在 3.5.16 上的结果为准。
  字段同基线 gateway：`@timestamp / level / logger_name / thread_name / message / stack_trace`。代码不用 MDC（全仓 grep 过），JSON 顶层不会出现 player_id，玩家号只在消息正文里（AGENTS §5）。
- **采集**：Vector（Q36）。compose 用 `docker_logs` source（只读挂 docker.sock，只用于 CI 与单机，H27）；K8s 用 `kubernetes_logs` DaemonSet，按 `app.kubernetes.io/part-of=xuanming-java` 过滤，读 `/var/log/pods`，RBAC 只给 list / watch pods。
  解析用 VRL：`parse_json` 失败就回落 Spring 文本正则（照基线 `config.alloy:443-467`）；文本格式的堆栈续行按 `:389-393` 的行头合并。Loki sink 打开**磁盘缓冲**（H12）。
- **Loki 标签只用低基数字段**：`env`、`namespace`（K8s）、`service`（JSON 字段，缺省取 Pod 标签 `app.kubernetes.io/name` 或 compose 服务名）、`zone`（取自 Pod 标签 `xm/zone` 或 compose 容器标签；全局进程填 `global`，避免模板渲染缺键）、
  `level`（小写）、`stream_class`（`logger_name` 以 `xm.audit.` 开头时为 `audit`，否则 `app`）。pod、logger、thread 留在行内，查询用 `| json`。CI 查 `/loki/api/v1/labels`，必须是白名单子集（AGENTS §5 的精神延伸到日志）。
- **存储**：Loki 3.5.x（tag + digest 钉死），`retention_period: 168h`；`retention_stream: [{selector: '{stream_class="audit"}', priority: 1, period: 720h}]`，与审计 topic 30 天对齐（`data-ops-spec.md:194`）；
  compactor retention 与 `delete_request_store` 都要配（基线 `loki.yaml:68-73`，H12）；不配 ruler。
- **审计兜底在 K8s 上的可回灌性**（J10，§0.6 #12）：`xm.audit.fallback` 是 Kafka 没确认的流水唯一的回灌来源（architecture §4.5，编写时 `:219`）。容器日志由 kubelet 轮转，K8s 上唯一的持久副本在 Loki。
  所以：① `xm-ops obs export-fallback` 按固定、互不重叠的时间窗从 Loki 导出 `{stream_class="audit"} | json | logger_name="xm.audit.fallback"` 为 JSONL（每行带 pod、`@timestamp`、`message`）；
  ② xm-data 的 `AuditFallbackReplay` 加 `--format jsonl`，从 `message` 取原文再交给 `FallbackLines`；③ JSONL 模式的去重键改为「pod + `@timestamp` + message 的 SHA-256」，不再是「文件 SHA-256 + 行号」，
  同一行被重复导出也不会让 `tx_id = 0` 的行重复入库。
- **Grafana**：不开匿名；admin 口令 `${XM_GRAFANA_ADMIN_PASSWORD:?}`；compose 只发布到 127.0.0.1（同 7.1 D3）；数据源 Prometheus（uid `prometheus`）与 Loki（uid `loki`）；看板目录 `xuanming-java`、`allowUiUpdates: false`，以文件为准（基线是 true，`dashboards.yaml:12`）。
- **不做日志告警**：凡值得告警的事件 Java 都有计数器（审计兜底 `xm_scene_audit_records_total`、毒丸 `xm_data_kafka_records_total`、获取异常 `xm_scene_gain_anomalies_total`），通用的「ERROR 突增」用 Actuator 自带的
  `logback_events_total`（A7）。基线「没有能生效的日志告警通路」的缺口在 Java 里不存在，也就不需要 Loki ruler。

### 5.13 指标抓取与告警架构

- **抓取**：每个 Java Pod 打注解 `prometheus.io/scrape: "true"`、`prometheus.io/port: "<管理端口>"`、`prometheus.io/path: /actuator/prometheus`（基线 Go 服务同约定，`manifests/go-svc/chat.yaml:52`）；chart 可选生成 PodMonitor（不作为依赖）。
  重标规则（`deploy/observability/prometheus/scrape-k8s.example.yaml`）：`job` ← `app.kubernetes.io/name`、`zone` ← `__meta_kubernetes_pod_label_xm_zone`（全局进程没有这个标签，就不带 zone）、`instance` ← Pod 名。
  compose 用静态目标 `xm-<模块>:<管理端口>`，给出相同的 job 与 zone（前提是 7.1 在容器里设了 `XM_MANAGEMENT_ADDRESS=0.0.0.0`）。抓取间隔 15 s，规则评估 30 s（同基线 `interval: 30s`）。
- **口径**：进程里仍不加 zone / 实例标签（architecture §11，编写时 `:913-914`）；zone 是**部署层**的抓取目标标签，基数等于目标数。architecture §11 要补这一句（Q42）。
- **规则载体**：纯 Prometheus `groups:` 文件 `deploy/observability/prometheus/rules/xm-*.rules.yaml` 是唯一来源；K8s 的 PrometheusRule 或 ConfigMap 包装由 xm-platform chart 从同一份文件生成（可选，缺省关）。
- **路由**：仓库只交付规则（`severity` 标签、`runbook_url`）和 `docs/ops/alerts.md`；Alertmanager 或别的接收端由环境提供（Q38）。建议的路由：critical → 呼人、warning → 频道、info → 丢弃；`group_by: [alertname, zone, job]`；
  `XmCoreTargetDown` / `XmTargetDown` 抑制同 instance 的其他告警；新 zone 开服、整 zone 维护前 silence（同基线 `scene-node-role-split.md:207-214`）。
- **CI 验证**不需要 Alertmanager：Prometheus 自己求值，`ALERTS{alertstate=…}` 就能看到 pending / firing（§11.3 C9）。

### 5.14 Java 代码改动（随 7.6；全部不是客户端可见）

| id | 内容 | 模块 | 批次 |
|---|---|---|---|
| J1 | `PodOrdinal.parse(XM_POD_NAME)`（取最后一个 `-` 之后的十进制数，取不到而又需要时拒启）；gate `xm.gate.advertise-port-base`（与 `advertise-port` 互斥）；通告主机支持 `{ordinal}` / `{zone}` | xm-common、xm-gate | 7.6b |
| J2 | battle 拆出 `xm.battle.client-advertise-host`（票据用）+ `advertise-port-base`；目录 `rpc_host` 仍用 `xm.advertise-host`。若 6.2 提交时已做，本项只补 port-base | xm-battle | 6.2 / 7.6b 前置 |
| J3 | 新占位符（缺省值等于现值）：`${XM_ZONE_ID:1}`（gate `:40`、scene `:65`）、`${XM_LOG_FORMAT:}`、`${XM_REDIS_PASSWORD:}`、`${XM_LOGIN_MODE:dev}`、`${XM_AUDIT_REPLICATION_FACTOR:1}`（`AuditProperties`）、`${XM_GATEWAY_GATE_DRAIN_DEADLINE:25m}`、`${XM_GATEWAY_TRUSTED_PROXIES:}`（逗号分隔，空串 → 空列表，V11 实测）、scene 的疏散预算 / 停服写回 / 审计发完三个时限（J7） | 各 yaml | 7.6a |
| J4 | readiness 指示器 `gateAccepting`、`sceneServing`、`battleAdmission`（§5.6）；scene 的身份判据随 5.5 | xm-gate、xm-scene、xm-battle | 7.6a（scene 身份部分随 5.5） |
| J5 | liveness 指示器 `nodeLease`（xm-discovery 提供，收集本进程持有的 `NodeIdLease`）与 `logicLoop`（scene、battle） | xm-discovery、xm-scene、xm-battle | 7.6a |
| J6 | battle 运维排空：管理端口 `POST /admin/battle/drain`（运维令牌 + 操作人，**任何运行模式可用**，与 dev 接口区分）→ 逻辑线程上 `admission.close()`、目录继续发布但 `accepting=false`；新开战回 `NOT_ALLOCATABLE`（match 已有换节点处理），在打的局照常打完结算（6.2 Q15 当时推荐「有需要在 7.6 加」，K8s 滚动需要它） | xm-battle | 7.6b |
| J7 | scene 宽限期只有一处真相：chart values 的 `evacuationBudget` / `shutdownSaveTimeout` / `auditFlushTimeout` 以环境变量传进程；模板按 C2 求下限（三者之和 + 10 s，取整且 ≥ 60 s），`terminationGracePeriodSeconds` 低于它就 `fail`；xm-scene 启动打出的「外部强杀期限须 ≥ N s」做同一份计算（`scene-drain-spec.md:1021`），CI 比对两者 | xm-scene、chart | 7.6a（进程侧）/ 7.6b（chart） |
| J8 | `ProductionGate` + `StartupBanner`；全部进程读 `xm.run-mode`；12 个显式调用点 | xm-common、各进程 | 7.6a |
| J9 | 指标：xm-discovery 新增 `xm.redis.clock.offset`（秒，本机墙钟 − Redis `TIME`，扣 RTT/2，每 30 s）；告警依赖的固定标签组合补预注册；各进程 `MetricsCatalogTest` | xm-discovery、各进程 | 7.6a |
| J10 | 审计兜底回灌支持 JSONL（`--format jsonl`）与内容去重键；`xm-ops obs export-fallback` | xm-data、xm-ops | 7.6a |
| J11 | 根 pom `xm.build.release` 属性与 build-info `release` 项 | 根 pom | 7.6a |
| J12 | gateway `xm.gateway.seed-zones-enabled: ${XM_GATEWAY_SEED_ZONES_ENABLED:true}`；K8s 设 false，区服一律经 xm-ops 写 | xm-gateway | 7.6a |
| J13 | 12 个 yaml 的结构化日志配置；Dockerfile `ENV XM_LOG_FORMAT=logstash` | 各 yaml、`deploy/docker/Dockerfile` | 7.6a |
| J14 | 新模块 `xm-ops`（§5.8）；根 pom 登记；7.1 漂移守卫排除 | xm-ops | 7.6a（release / obs / preflight 纯逻辑）、7.6b（deploy 子命令） |

### 5.15 文件清单与分批

**7.6a**（7.1b 提交之后即可做，不碰集群）：
- J3、J4（gate / battle 部分）、J5、J7（进程侧）、J8–J13；`xm-ops` 的 release、obs 与 preflight 的纯逻辑；`CHANGELOG.md`；
- `deploy/observability/**`：`prometheus/{prometheus.compose.yaml, scrape-k8s.example.yaml, rules/, tests/}`、`loki/loki.yaml`、`vector/{vector-compose.yaml, vector-k8s.yaml, tests/}`、`grafana/{provisioning/, dashboards/}`；
- `deploy/compose/observability.yaml`（与 stack 同项目叠加：`docker compose -f stack.yaml -f observability.yaml`）；`deploy/ci/stack-smoke.sh`；
- `.github/workflows/{release.yml, release-push.yml}`；`ci.yml` 加 `obs-static`；`integration.yml` 的 stack 加观测冒烟；
- 文档：`docs/ops/{release.md, alerts.md}`，tech-stack、architecture §11。

**7.6b**（7.6a、6.2（含 J2）、5.5 提交之后；双 zone 用例另等 5.4 的 X16）：
- `deploy/helm/**`；J1、J2、J4（scene 身份部分）、J6、J7（chart 侧）；`xm-ops deploy` 全部子命令；`.github/workflows/k8s.yml`；
- 文档：`docs/ops/k8s-runbook.md`，architecture §6「K8s 部署 profile」（拓扑、发现、地址、探针、宽限期、可用性语义）。

### 5.16 登记与同步

- **PARITY 新增四行**（都写「mmorpg 已有，本批 Java 对齐（行为有意不同）」）：K8s 开区编排（`mm:deploy/k8s/**`、`k8s_deploy.ps1`、`k8s_gate_drain.ps1` ↔ `deploy/helm/**`、`xm-ops deploy`，K1–K8、K16–K18、K20–K25）；
  发布制品（`release.yml`、`publish_images.ps1` 等 ↔ `release.yml`、`release-push.yml`、`xm-ops release`，K9–K12）；发布门禁（`release_preflight.ps1` ↔ `ProductionGate` + preflight，K13）；
  日志采集、告警与看板（`deploy/observability/**`、`deploy/k8s/*-alerts.yaml` ↔ `deploy/observability/**`，K14–K15、K19）。
- **PARITY 修订**：第 99 行 gate 排空的「Java 待做：K8s 滚动替换编排」→ 已完成（`gate-roll`）；「低基数运行指标」行补 zone 目标标签；「节点客户端可达地址」行补四种暴露模式；「版本基线」表从首个发布起按 `vX.Y.Z` 登记（Q48）。
- **roadmap**：3.5 的备注同步改；7.6 行写提交号。
- **tech-stack.md 新增**：Helm（30.3K）、k3s（34.1K，仅 CI）、Traefik（65.1K，随 k3s，仅 CI；生产 Ingress controller 由集群决定）、Prometheus（66.4K）、Grafana（77.1K）、Loki（29.0K，偏离说明）、
  Vector（22.7K）、GHCR；补「K8s 不用 Nacos」与「告警接收端由环境提供」两句。
- **盘点注记**（盘点是快照，只加注记）：`java-infra.md:340`（「无任何 K8s 资产」）、`:389` Q7 已答（Q1）；`tools.md:416`、`:452`（Deploy / Release 改为 `xm-ops` 模块）；`java-infra.md:363`（「gate 链路背压、assign-gate 失败率」已成规则 A31 / A12）。
- **建议 mmorpg 同修**（不阻塞 Java，交付说明写「mmorpg 待做（可选）」）：B1、B3 优先；另有 B2、B4、B6、B7、B8、B9、B12、B-R1–B-R3、§4.4 的 1–3（没有部署 Prometheus、抓取注解缺失、promtool 不进 CI）、K8s 上 Go / Java 日志无采集、Loki ruler 未接线。

---

## 6 选型（AGENTS §2；star 为分稿 2026-10-05 实测）

| 用途 | 选用 | star | 落选（star） | 说明 |
|---|---|---|---|---|
| 清单模板 | Helm 3 | 30.3K | Kustomize（12.2K，虽随 kubectl 内置，按「同类取最高」落选）；纯 Java 生成器（等于重写一个 Helm）；基线的手写替换 | values schema、release 历史、`helm rollback`、library chart 都是现成的 |
| CI 用的 K8s | k3s（钉版本、核对 sha256） | 34.1K | minikube（32.2K）、kind（15.5K，基线 `mm:deploy/k8s/kind-config.yaml`） | 同类最高；自带 NetworkPolicy 控制器、Traefik、ServiceLB、local-path，单节点就能覆盖 Ingress、NodePort、PVC、策略；单二进制装在 runner 上，镜像经 `k3s ctr images import` 导入，不需要 registry |
| Ingress controller（仅 CI） | Traefik（随 k3s） | 65.1K | ingress-nginx（19.5K，已宣布退役） | 生产由集群决定，chart 只出标准 `networking.k8s.io/v1` Ingress |
| 动态 GameServer | 不引入 | agones 7.1K | — | §5.1：常驻进程自带租约、目录、准入；star 也不够 |
| 运维 CLI | 自写模块 `xm-ops`（JDK + Boot BOM 管理的 Jackson / SnakeYAML） | — | Kubernetes Java 客户端（官方 4.0K、fabric8 3.7K）；PowerShell | 只调 `helm` / `kubectl`，不需要客户端库 |
| 秘密管理 | K8s 内置 Secret，由 xm-ops 从 env 文件建 | 内置 | external-secrets（6.9K） | YAGNI；托管云环境可另接 |
| 渲染结果校验 | `kubectl apply --dry-run=server`（打 k3s 真 API server） | 内置 | kubeconform（3.2K） | — |
| 指标存储与规则 | Prometheus（规则文件 + promtool） | 66.4K | prometheus-operator（10.0K，只做可选包装）、VictoriaMetrics（17.8K）、Thanos（14.2K） | 规则以纯 rule-group YAML 为权威，可离线单测 |
| 告警接收 | 不作为仓库依赖（环境提供；建议 Alertmanager） | alertmanager 8.6K | Grafana 告警 | Q38 |
| 看板 | Grafana | 77.1K | — | — |
| 日志采集 | Vector | 22.7K | logstash（15.0K）、fluentd（13.6K）、elastic/beats（12.7K）、fluent-bit（8.1K）、otel-collector（7.6K）、Alloy（3.6K，基线）、Promtail（已弃用） | 同类最高；Java 只写 stdout，不要 sidecar |
| 日志存储 | Loki（**偏离 §2，需用户确认**） | 29.0K | Elasticsearch（78.2K） | Q37 |
| 结构化日志 | Spring Boot 自带 `logging.structured` | Boot 自带 | logstash-logback-encoder（2.5K） | 字段与基线 gateway 同口径 |
| 镜像仓库 | GHCR | — | Docker Hub | 与仓库同在 GitHub，`GITHUB_TOKEN` + `packages: write` 即可推 |
| 发布号写入 | build-info additionalProperty | Boot 自带 | versions-maven-plugin（367）、flatten-maven-plugin（223）、`${revision}` | Q26 |
| 发布编排 | 自写 workflow + xm-ops | — | goreleaser（16.1K）、jreleaser（1.2K） | 规则少，自写更可控 |
| 签名 / SBOM / 扫描 | 暂不做 | — | cosign（6.3K）、syft（9.6K）、trivy（38.2K） | Q31 |
| 数据库迁移 | 不引入，照旧按 `db-migrations.md` 手工 | — | Flyway、Liquibase（都不到 2 万） | manifest 记 `db_migration_head` |

---

## 7 秘密与配置

### 7.1 非秘密配置（K8s 环境变量）

规则同 7.1（`deploy-ci-spec.md:903`）：每个变量都必须在 `application.yaml` 里有同名占位符；表数据烤在镜像里（7.1 D11），不用 ConfigMap、不挂卷，K8s 上不支持 `XM_TABLE_DIR` 换表（Q49）。
与基线「把服务 yaml 整块搬进 ConfigMap、整目录遮蔽镜像」相比，不存在「漏一个键就静默出错」（基线 README `:896-907`）。

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
| `XM_BATTLE_CLIENT_ADVERTISE_HOST` | 按暴露模式 | battle |
| `XM_GATE_ADVERTISE_PORT_BASE` / `XM_BATTLE_ADVERTISE_PORT_BASE` | NodePort 两种模式下的 portBase | gate、battle |
| `XM_SCENE_LINK_BIND_HOST` | `0.0.0.0` | scene |
| `XM_POD_NAME`、`POD_IP` | Downward API | gate、battle、scene |
| `XM_LOGIN_MODE` | prod；CI dev | login |
| `XM_GATEWAY_QUEUE_ENABLED`、`XM_GATEWAY_RATE_LIMIT_ENABLED` | true | gateway |
| `XM_GATEWAY_TRUSTED_PROXIES` | Ingress controller Pod 网段（CI：k3s 缺省 Pod 网段） | gateway |
| `XM_GATEWAY_SEED_ZONES_ENABLED` | false | gateway |
| `XM_GATEWAY_GATE_DRAIN_DEADLINE` | 25m；CI 60s | gateway |
| `XM_DATA_TXLOG_RETENTION` / `XM_DATA_SNAPSHOT_RETENTION` / `XM_DATA_GM_SNAPSHOT_RETENTION` | prod：180d / 90d / 0s（Q47） | data |
| scene 停机三时限（J7） | 由 chart values 给，同时用于宽限期下限 | scene |
| `JDK_JAVA_OPTIONS` | 模板拼（§5.4） | 全部 |
| `XM_LOG_FORMAT` | 镜像缺省 `logstash` | 全部 |

### 7.2 秘密矩阵与 Secret 对象

- **应用 namespace 一个 Secret `xm-secrets`**，键名等于环境变量名。每个容器只用 `env[].valueFrom.secretKeyRef` 引用自己要的键；必需键**不写 `optional`**，缺键时 Pod 卡在 `CreateContainerConfigError`，
  而不是带着空密钥起来（同基线 `k8s_deploy.ps1:4178`）；只有本来就可选的 `XM_ADMIN_TOKEN`、`XM_GM_ADMIN_SECRET` 写 `optional: true`。
- **infra namespace 一个 Secret `xm-infra-secrets`**：MySQL root 口令、`xm_app` 口令（initdb 建账号用）、Redis 服务端口令。

| 键 | 注入的进程（在 7.1 §6.1 矩阵上增补） |
|---|---|
| `XM_MYSQL_PASSWORD`（`xm_app` 的口令） | login、friend、team、guild、trade、gateway、data、scene |
| `XM_REDIS_PASSWORD`（新增） | 全部 12 个进程（6.4 后加 match） |
| `XM_DUBBO_SECRET` | scene-manager、login、friend、chat、team、guild、trade、scene、gate、gateway、battle、match |
| `XM_GATE_TOKEN_SECRET` | gate、gateway。**不注入 battle**：「battle 密钥 ≠ gate 密钥」由 preflight 在部署侧比对（6.2 Q11 推荐的分工） |
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

- Pod 模板注解 `xm/secrets-revision: <Secret 的 resourceVersion>`（`xm-ops` 读出来经 `--set secretsRevision=…` 传给 Helm）。Secret 一变，所有 Deployment 滚动；gate / battle 是 OnDelete，要接 gate-roll / battle-roll。
  注解里只有版本号，不放任何由秘密内容算出来的东西（低熵口令的哈希前缀也能被离线猜，H6）。
- 共享密钥（`XM_DUBBO_SECRET`、`XM_GATE_TOKEN_SECRET`、`XM_NODE_LINK_SECRET`）没有双密钥过渡，轮换只能整体重启，排进维护窗口（H16）。

### 7.5 MySQL 账号

- 应用账号 **`xm_app`**：`GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON xm_java.* TO 'xm_app'@'%'`，不给 DROP、不给全局权限。
  启动期建表（`spring.sql.init`、pbmysql 只扩不缩的同步，architecture §7）需要 CREATE / ALTER / INDEX；pbmysql 的 `GET_LOCK` 不需要额外权限；pbmysql 打印的修复语句（含 `DROP INDEX`）本来就不执行（`SchemaPlanner.java:326` 只是建议）。
- 库 `xm_java` 由 `MYSQL_DATABASE`（dev / CI）或 DBA（生产）预建，不再依赖 `createDatabaseIfNotExist`；带这个参数的 URL 在库已存在时是否要求库级 CREATE 权限，以 CI 实测为准（§11.3 C4）。
- 需要 DROP 的结构变更由 DBA 账号按 `db-migrations.md` 手工做。7.1 的集成测试仍用 root 建临时库，不受影响。
- root 只给 MySQL 容器与 initdb 用；prod 运行模式下进程用 root 一律拒启（§5.11）。

### 7.6 生产值

| 项 | 值 | 出处 / 理由 |
|---|---|---|
| 审计保留期 | 流水 180 天、LOGIN / LOGOUT / PERIODIC 快照 90 天、GM / 安全快照永久 | `data-ops-spec.md:1101`（Q11）；满足启动约束「流水保留期 ≥ 快照保留期」（`:353-354`） |
| 审计 topic 副本因子 | 3，broker ≥3 | 基线 D 节同口径 |
| Redis | AOF everysec、口令、`noeviction`、持久卷或托管 | `scene-battle-spec.md:1139`；`xm:node-id-epoch:*` 不得回退 |
| MySQL | 托管、带 PITR、`xm_app` | Q18、Q19 |
| gateway | 排队与限流打开、`trustedProxies` 配齐、gate 排空 deadline 25 min | 同基线 |

---

## 8 隐患与边界

| # | 隐患 | 对策 |
|---|---|---|
| H1 | **Dubbo 绑定规则**：通告地址设成 POD_IP 时 Dubbo 只绑这个 IP（`deploy-ci-spec.md:446`），scene 的资产通道在 Pod 内 127.0.0.1 上连不上 | 探针只打管理端口；只做直连的提供方不设通告地址 |
| H2 | 不写 memory limit 时 `MaxRAMPercentage` 按节点内存算堆 | 模板对 limit 用 `required`，CI 反例验证 |
| H3 | `worldChannels` 是全 zone 的聚合判据，放进 scene-manager 的 readiness，一个 zone 出问题就会把 scene-manager 摘掉、全服登录不了 | 只用于 `/actuator/health` 与告警 |
| H4 | gate / battle 的 PDB 0 挡住 `kubectl drain` 与 cluster-autoscaler（同基线 D87） | 运维手册写清：维护节点前先 gate-roll / battle-roll；values 可改 1（Q10） |
| H5 | OnDelete 下 `helm upgrade --wait` 只等现有 Pod 就绪，新模板并没生效 | xm-ops 在 upgrade 后检查 `updateRevision ≠ currentRevision` 并提示或接 gate-roll / battle-roll；只跑 helm 的风险写进手册 |
| H6 | Helm 把渲染结果存成集群里的 Secret，`--set` 留在 shell 历史 | 秘密永不进 values；注解只放 resourceVersion |
| H7 | Redis 数据丢失会让 `xm:node-id-epoch` 回退，scene 重新接受旧 gate 的链路（architecture §6，编写时 `:608`） | PVC + AOF；MySQL 与 Redis 只能一起重置（7.1 §2.8）；`helm uninstall xm-infra` 不删 PVC，xm-ops 不提供删 infra namespace 的命令 |
| H8 | Kafka 镜像非 root 运行，PVC 属主要靠 `fsGroup`（7.1 compose 因此没给 Kafka 挂卷，`deploy-ci-spec.md:355-356`） | uid / gid 在 CI 首轮核对 |
| H9 | 多副本同时冷启动：schema 初始化与 pbmysql 同步可能撞 MDL（基线因此改用 migrate Job，README `:488-489`） | pbmysql 整轮持 `GET_LOCK`（architecture §7）；`CREATE TABLE IF NOT EXISTS` 幂等；CI 单独一个「2 副本冷启动」job；真出问题再加 Helm `pre-install` / `pre-upgrade` hook Job，用单进程只建表模式 |
| H10 | preflight 要读 Secret 的值 | 只在内存比较，不落盘、不打印、不写进退出信息 |
| H11 | **容器换成 JSON 日志后**：① 按文本 grep 日志的脚本失效；② 审计兜底回灌工具把 JSON 尾巴吃进 `extra`；③ 从 Loki 重复导出同一行会让 `tx_id = 0` 的行重复入库 | ① 落码时扫一遍 `deploy/`、`.github/` 里的 grep（7.1 的 healthcheck 用指标，不受影响）；②③ J10 |
| H12 | Loki 不可用时审计兜底行只靠 Vector 磁盘缓冲，缓冲满了照样丢 | 磁盘缓冲 + A11；Loki 按流保留要 compactor retention 与 `delete_request_store` 都配 |
| H13 | 时钟漂移：Dubbo 与链路鉴权窗 ±60 s、GM 300 s、租约比较用墙钟（architecture §7，编写时 `:689`） | A8（J9 新指标）；节点 NTP 由环境保证 |
| H14 | Dubbo 直连 ClusterIP：长连接要等服务端关闭才重连，滚动时有少量调用失败，gate 给客户端回 1003（客户端可见、短暂）；一个调用方的流量只落一个 Pod | preStop 5 s + Dubbo 停机等待；CI 统计滚动期间 1003 比例（C5）；负载均衡靠调用方数量摊开（Q22） |
| H15 | xm-data 单副本 + Recreate，换版本时审计消费与运维接口短暂中断 | 消息留在 Kafka（保留 30 天），scene 不受影响 |
| H16 | 共享密钥没有双密钥过渡 | 轮换 = 全服维护窗口 |
| H17 | CI 容量：runner 约 4 vCPU / 16 GB，要装 k3s、infra、10 个全局进程、两个 zone 的 gate / scene，scene 还要 surge | `values/ci.yaml` 压 limit（§5.4 CI 列，合计约 11 GiB，surge 时约 12.7 GiB）；首轮超时先看环境自检；2 副本验证放单独 job |
| H18 | 关区时 battle 里还有该 zone 的玩家：结算按 6.3 的发件箱投给 scene，关区后投递失败、重试，直到 zone 重开或租约到期（推断） | CI 加一条用例观察；6.3 已规定结算落到下一个持有者 |
| H19 | 区服目录与 Helm release 是两个真相来源 | 顺序由 xm-ops 保证（先维护再卸载、先就绪再开放）；手工跑 helm 的风险写进手册 |
| H20 | Redis 长时间不可用（> 租约 TTL 15 s）时，持有租约的进程 liveness 依次 DOWN、重启，在 Redis 回来前 CrashLoopBackOff | 有意如此（它们本来就要重启才能恢复，Q12）；scene 用 5.5 的挂起 / 复占模式，不在此列；告警 A1 / A3 会响 |
| H21 | NodePort 段重叠或越界 | preflight 读已装 release 校验（同基线 D88） |
| H22 | LoadBalancer 每个 gate / battle 一个，成本随副本线性增长 | 托管云评估；自建推荐 NodePort + 外部 L4 |
| H23 | `publicHost` + Local 丢流量；`hostIP` + Cluster 丢客户端 IP | 前者渲染失败，后者 WARN |
| H24 | 共用对外地址 → 握手 `wrong_gate`，或被 5.4 的同地址去重合并掉 | schema 强制按序号区分；A21 兜底 |
| H25 | chart 不设缺省 Ingress class，使用者必须显式指定 | 手册写明；CI 指定 traefik |
| H26 | 运行期门禁是行为变化：任何没设运行模式、秘密又短、或用 root 连库的环境都会拒启 | CHANGELOG 与交付说明写清；start-slice 与 compose 缺省 dev（`start-slice.sh:29-30`） |
| H27 | compose 里 Vector 挂 docker.sock 等于拿到宿主 root | 只用于 CI 与单机；K8s 用 DaemonSet、只读 hostPath、RBAC 只给 list / watch pods |
| H28 | 按需注册的计数器在 `increase() > 0` 里漏第一次（基线 §4.4 第 6 条） | §4.8 第 3 条：能预注册的预注册，动态标签用新序列写法 |
| H29 | A7 会把三方库（Kafka、Dubbo、Redisson）的 ERROR 也算进去 | scene 已把 Kafka 压到 WARN（`xm-scene application.yaml:121-124`），其他进程按压测结果调；阈值标未校准 |
| H30 | scene-manager 的告警（A40–A46）没有 zone 维度 | runbook 写清如何从领导者日志定位 zone；玩家可见的情形由 A24 / A36 按 zone 报 |
| H31 | GHCR 首次推送的包可见性（按记忆默认私有，不确定）；推 `.github/workflows/**` 需要凭据带 workflow 权限（7.1 §8 #4） | 用户在 GitHub 设置里确认；Claude 不经手凭据 |
| H32 | Docker Hub 匿名拉取限流（infra 镜像、promtool / Vector / Loki 镜像） | 全部钉 digest；遇到 `toomanyrequests` 先重跑，频繁出现再考虑镜像代理 |
| H33 | `release.yml` / `release-push.yml` 只能用户触发 | 交付说明给出要点的按钮；Claude 用匿名 REST 读结论 |
| H34 | scene `maxSurge: 100%` 滚动时 zone 的 scene 资源翻倍 | 资源紧的集群把 maxSurge 改为 1（每个玩家最坏被迁 N−1 次，5.5 R10） |
| H35 | 5.4 的 X16 之前，全局一个 login 会把非 zone 1 建的角色记成 zone 1 | 多 zone K8s 部署以 X16 为前置（§0.4） |
| H36 | battle-roll 每台最多等一整局（300 s）加余量 | 维护窗口里批量做；`--force` 才作废在打的局 |
| H37 | 区服目录行可被直接删除（`DELETE /admin/zones/{id}`，`ZoneAdminController.java:114-119` 不检查区内数据，`data-tools-spec.md:961` 已记） | `xm-ops` 关区只置 CLOSED，**从不调用 DELETE**；手册写明不要手工删 |

---

## 9 建议的有意差异（相对基线）

| # | 差异 | 基线 | Java | 理由 | 客户端可见？ |
|---|---|---|---|---|---|
| K1 | 编排工具 | 5.5k 行 PowerShell 生成器，替换占位后 apply | Helm（三 chart + library）+ `xm-ops` 模块 | §2 选型；工具用 Java 写（AGENTS §1）；schema、rollback 现成 | 否 |
| K2 | 按 zone 的范围 | zone 内 9 类工作负载 | 只有 gate、scene | login / gateway / scene-manager 等不分 zone（X16） | 否 |
| K3 | namespace | 每 zone 一个；关区 = 删 namespace | 一个环境一个应用 namespace，每 zone 一个 release | Secret 只存一份；开关 zone 不碰别的；按 zone 回滚 | 否 |
| K4 | 发现 | etcd | K8s Service DNS + Dubbo 直连；不部署 Nacos | §5.3 | 否 |
| K5 | infra | 集群内 7 类组件，用于真实部署 | 3 个 StatefulSet，只 dev / CI / 演示；生产托管 | 数据安全、PITR | 否 |
| K6 | scene | 可拆两池、Deployment / Fleet、无 PDB、无 resources | 单池 Deployment；先起全部新实例；PDB 1；宽限期按公式 | 5.3 不分池；5.5 疏散保证滚动不丢人 | **是**（局部）：滚动时玩家被疏散改派，口径同 5.5 已登记 |
| K7 | gate / battle 入口 | podip / external；地址由 shell 或 SDK 给 | 四种模式，Java 按序号算端口与主机 | 无 shell 包装；保住 PID 1 | **是（只是取值）**：assign-gate 的 `gate_ip / gate_port`、124 重定向、battle 票据地址；形状不变 |
| K8 | Agones | scene / battle 可选 Fleet | 不引入 | §5.1 | 否 |
| K9 | 分发 | 仓库外制品根 + 离线 tar + fetch / import / retention | GHCR（用户触发推送）+ 短期 Actions 制品；按 digest 部署 | 没有离线目标机 | 否 |
| K10 | 发布号注入 | 依赖插件写入顺序覆盖 `build.version`；Go 用 ldflags | Maven 版本不动，加 `build.release` | 不依赖插件实现细节（`java/gateway_node/pom.xml:155-160`） | 否 |
| K11 | 发布流水线 | 只跑 pwsh 契约测试，不推、不跑 preflight | 要求同提交 CI / Integration 已绿 + 对发布镜像整栈冒烟；推送拆到单独 workflow，只它拿 `packages: write` | 补基线缺口 #3；最小权限 | 否 |
| K12 | manifest | 表摘要只算 json | 多出契约 commit、表 manifest 摘要、战斗指纹、迁移头、chart 版本 | 双版本追溯；回滚判断 | 否 |
| K13 | 门禁 | 扫仓库 yaml；「5 处一致」；退出码 2 未实现 | 进程启动自检 + 部署前检查 Secret / values / 制品；改查「两两不同」；退出码 2 显式实现 | Java 秘密只从环境变量进（AGENTS §3）；复用才是真风险 | 否 |
| K14 | 日志 | C++ 文件 + Alloy sidecar；K8s 上 Go / Java 无采集；一律 7 天 | stdout JSON + Vector；审计流 30 天 | Alloy 不到 2 万 star；兜底日志是回灌来源 | 否 |
| K15 | 告警 | PrometheusRule CR、Go 指标名、进程打 `zone_id`、CI 不测、仓库不部署 Prometheus | 纯规则文件 + promtool + 目录守卫；zone 来自抓取目标标签；不做日志告警；compose 里部署 Prometheus 并在 CI 求值 | 可测试；不依赖 operator；高基数约束；吸取恒空告警教训 | 否 |
| K16 | 开区 | 不登记区服目录（新区不出现在列表里）、不建库 | 先维护 → 就绪 → 开放或预告；单库不用建库；gateway 不再自动播种 | 基线 B5 | **是**：新区先以维护 / 预告出现在区服列表（503 `zone_maintenance` / `zone_not_open`，码同基线），就绪后才开放 |
| K17 | 关区 | 直接删 namespace（断线） | 先维护 → 先停 gate（断线）→ 卸载 → SREM → CLOSED | 基线 B10 | **是**（时序）：先拦新登录再断线；之后 assign-gate 回 503 `zone_closed`，码同基线 |
| K18 | battle 滚动 | Agones drain 标签：先关新房、打完再回收 | 运维排空接口 + battle-roll | 没有 Agones；不做就会作废在打的局 | 否（在打的局照常结算） |
| K19 | 观测台安全 | 匿名即 Admin、admin/admin、发布到 0.0.0.0、看板 UI 可改 | 不开匿名、口令走 `:?`、只发布到 127.0.0.1、以文件为准 | AGENTS §3 | 否 |
| K20 | 秘密 | 大多数在 ConfigMap 明文 | 全部在 Secret，按进程最小授权 | 基线 B1 | 否 |
| K21 | 配置 | ConfigMap 遮蔽镜像配置 | 只用 env 覆盖占位符 | 少一整类漂移 | 否 |
| K22 | 资源 | C++ BestEffort | memory request = limit，必须写；CPU request + ActiveProcessorCount | JVM 按 limit 算堆（H2） | 否 |
| K23 | NetworkPolicy | 无 | 有 | 基线 B12 | 否 |
| K24 | 探针 | C++ 只有 tcp readiness、无 liveness | actuator 分组 + 领域 readiness + 租约 / 逻辑线程 liveness | Java 看得到内部状态 | 否 |
| K25 | 验证 | 离线渲染为主；kind 上部分实跑 | k3s 真部署两个 zone + robot + 运维演练，CI 每日 | 本机无 Docker，CI 是唯一执行者 | 否 |

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
| Q8 | gate / battle 对外形态 | **StatefulSet + 每序号 Service，四种暴露模式；CI 用 hostIP（zone 1）与 podIP（zone 2）各跑一遍，publicHost / loadBalancer 只做渲染测试** | 令牌绑 gate；与基线 external 同形；保留源 IP |
| Q9 | scene 用 Deployment 还是 StatefulSet；滚动怎么配 | **Deployment，`maxSurge: 100%`、`maxUnavailable: 0`**（资源紧可改 1） | 身份来自 Redis 租约，与序号无关；scene 没有对外入口；100% surge 让每个玩家至多迁一次（5.5 R10） |
| Q10 | gate 的 PDB 用 0 还是 1 | **缺省 0，values 可改 1** | 驱逐会不经排空断掉一整台 gate；代价是挡 drain / autoscaler（H4），同基线 D87 |
| Q11 | scene / battle 的 liveness 加不加逻辑线程卡死判据 | **加，30 s 阈值，约 60 s 后重启** | 基线 C++ 因 tcp 探针看不出卡死而不加（`k8s_deploy.ps1:1610`），Agones 形态用了 10 s 心跳；Java 看得到逻辑线程，卡死的节点既存不了盘也服务不了人 |
| Q12 | 节点号 / 发号租约永久丢失时 liveness 是否 DOWN | **是**（gate、battle（房间归零后）、login、team、guild、trade、scene-manager、data、match） | 代码注释写明「需要重启」；K8s 替人做；Redis 长时间不可用时的 CrashLoop 是可接受代价（H20） |
| Q13 | 要不要 battle 运维排空（6.2 Q15） | **要**（J6） | 否则每次发版都会作废在打的局，客户端可见 |
| Q14 | 单节点房间上限 `xm.battle.max-rooms`（6.2 Q7 留给 7.6） | **7.6 不加**，7.4 压测后再定 | 连接上限已兜住负载；没有数据定值 |
| Q15 | battle 管理端口挂 GM 签名停机（6.2 §7.11 可选项） | **不做** | K8s 走 SIGTERM + battle-roll；非 K8s 部署用 SIGTERM 亦可 |
| Q16 | 上不上 HPA | **7.6 不上** | gate / scene / battle 有状态，扩缩必须先排空；全局服务没有压测数据（YAGNI） |
| Q17 | 上不上 NetworkPolicy | **上** | 基线 B12；同 part-of 粗放行的维护成本低 |
| Q18 | MySQL 账号 | **`xm_app`（§7.5 权限）；prod 运行模式下用 root 一律拒启（两层）；compose infra 加可选应用账号供 prod 演示** | 7.1 §6.2 把这个决定交给 7.6；最小权限；CI 用它跑能暴露权限缺口 |
| Q19 | 备份 CronJob | **不移植** | infra 只给 dev / CI；生产托管 PITR；基线的 CronJob 自己就有 B6 |
| Q20 | PriorityClass | **chart 留值、缺省空**；手册建议给 gate / scene / battle 配高优先级 | 集群级对象，托管集群常受限 |
| Q21 | 资源怎么配 | **memory request = limit；CPU 只写 request + ActiveProcessorCount；初值见 §5.4，7.4 压测后改** | CPU limit 的 CFS 节流会造成 scene 帧尾延迟；不写 memory limit 堆会按节点算 |
| Q22 | 副本数与多副本安全 | **生产起点：gateway、login、scene-manager、社交五服、match 各 2；data 1（Recreate）；battle 2；CI 主 job 全部 1，另有「2 副本」job 跑冷启动与社交 robot**；暴露问题的服务临时降 1 并登记 | 各服务按设计支持多实例（选主、按队列加锁、雪花租约各占一号），但没在多副本下逐个实测过（`spectate-spec.md:776` 也留给 7.6） |
| Q23 | 关区顺序；是否清 `xm:world:{z:Z}:*` 等键 | **先停 gate 再卸载 scene；只 SREM `xm:world:zones`，不清别的键** | 先 gate 后 scene 免得 scene 停机时还要疏散（同 `stop-slice.sh:7`）；重开时规划器会把死节点的记录重铺；按区清键并入整区回档（`tools.md:434-444`），必须排除 `xm:node-id-epoch:*` |
| Q24 | 开区时区服目录怎么写 | **xm-ops 经 xm-data `/admin/zones` 写；K8s 上关掉 gateway 的 seed-zones** | seed-zones 只在 gateway 启动时补缺，并以 OPEN 插入（§0.6 #13） |
| Q25 | 编排逻辑放单文件工具还是模块（盘点 `tools.md:597` 开放问题 6 设想「合服 / 巡检 / Redis 清理进 `xm-ops` 模块，部署 / 发布用单文件工具」） | **部署与发布也进 `xm-ops` 模块**；7.6 的子命令是普通 `main`，不起 Spring 上下文，7.3 / 7.4 的合服、巡检子命令以后在同一模块里按需起 Spring | 要解析 YAML / JSON、要共用 `ProductionGate`、要 JUnit 覆盖状态机；单文件工具没有单测；一个运维入口比三个好找 |
| Q26 | 发布号要不要写进 Maven 版本 | **不写**，用 `build.release` | jar 不发布到 Maven 仓库；`versions:set` / flatten 不满足 §2，`${revision}` 让本地 install 出去的 pom 无法单独解析，每次发版还要改 30 个 pom |
| Q27 | CHANGELOG 与首个版本 | **中文 Keep a Changelog；每段写需要的库迁移 `Mn`；7.6 完成后由用户打第一个版本 `v0.1.0`** | 基线同口径；回滚判断需要迁移头 |
| Q28 | 推送放在哪 | **单独的 `release-push.yml`，用户看过制品后触发；远端已有同名 tag 就拒绝；平时不推快照** | 最小权限；与基线「人决定推送」一致；推的就是被冒烟过的那批镜像 |
| Q29 | 镜像路径与可见性 | **沿用 7.1：`ghcr.io/luyuan-java/<模块>`；建议公开**（仓库公开，镜像里没有秘密，表数据也是公开契约），由用户决定 | 7.1 §3.8 已为此准备好 label；模块名带 `xm-` 前缀，在用户命名空间下不冲突 |
| Q30 | 离线包与 jar 包 | **都不做** | 没有隔离网络的目标机；jar 约 1 GB、镜像里已有 |
| Q31 | 签名 / SBOM / 扫描 / 多架构 | **推迟；只出 amd64** | 基线也不装（`release.yml:12-15`）；以后可用 GitHub 官方的 `actions/attest-build-provenance` |
| Q32 | 发布 workflow 里要不要重跑全部测试 | **不重跑单测，要求同提交 CI / Integration 已绿；但用发布镜像重跑整栈冒烟** | 源码相同、构建戳不同：测源码的证据可复用，测镜像的证据不可复用 |
| Q33 | prod 下秘密过短就拒启，算不算破坏性改动 | **算，而且要这样做**；dev / test 只 WARN | fail-closed；不配运行模式的部署本来就按 prod 跑（H26） |
| Q34 | prod 要求 Kafka broker ≥3 | **prod FAIL、staging WARN**；单机演示用 staging 档 | 同基线，但不挡演示 |
| Q35 | Redis 口令 | **加显式占位符；prod 必填 ≥12** | 同基线 B 节；现在只能靠宽松绑定 |
| Q36 | 日志采集器 | **Vector** | 22.7K 同类最高；读 stdout 不要 sidecar |
| Q37 | 日志存储用 Loki（29.0K）还是 Elasticsearch（78.2K，严格按 §2 应选它） | **Loki，在 tech-stack 写偏离说明，需用户确认** | ES 至少要 1–2 GB 堆，而唯一的执行环境是已经跑着十几个 JVM 的 CI runner；按标签查询够用，不需要全文索引；值班查询与基线 LogQL 同构；Grafana 原生支持 |
| Q38 | 告警接收端 | **仓库只交付规则与路由约定，不部署也不依赖接收端；环境里建议用 Alertmanager** | alertmanager 8.6K 不满足 §2，Grafana 告警没法离线测；CI 用 Prometheus 自己的 `ALERTS` 验证就够 |
| Q39 | 观测栈装在哪 | **不放进游戏 chart**：仓库给规则、看板、Vector / Loki 配置和 CI 用的 compose；集群里的 Prometheus、Grafana、Loki、Vector 用各自官方 chart | 生命周期与游戏服无关；多数集群已有观测栈 |
| Q40 | 日志缺省格式与审计流保留 | **容器里 JSON（镜像 ENV），本机文本；审计流 30 天，其余 7 天** | 采集要 JSON，开发要可读；与审计 topic 保留期对齐 |
| Q41 | K8s 上审计兜底怎么回灌 | **从 Loki 按固定时间窗导出 JSONL，回灌工具读 JSONL、按内容去重**（J10） | 容器日志会轮转，Loki 是唯一持久副本；按文件去重防不住重复导出 |
| Q42 | zone 维度从哪来 | **抓取目标标签**；architecture §11 补一句「进程不打 zone，部署层的目标标签可以」 | 守住基数约束（architecture §11） |
| Q43 | scene-manager 要不要按 zone 打标签 | **不加** | 守住项目已有决定；要改先改架构文档 |
| Q44 | 没有基线数据的阈值 | **语义对得上的沿用基线数值（1800、0.05/s、0.01/s、0.5、10×），标「未校准」，7.4 压测后回填** | 基线自己也没校准过 |
| Q45 | 新指标的告警由谁加；AGENTS 要不要写 | **引入指标的那一批同批加规则与 promtool 用例；连同「新增进程模块同时登记 chart / NetworkPolicy」「K8s 与观测结论以 CI 为准」「CHANGELOG [Unreleased]」写进 AGENTS §4，交用户过目** | 防「指标有了、告警没有」 |
| Q46 | 要不要时钟偏差指标 | **要**（J9） | 租约依赖墙钟，这是最便宜的守卫 |
| Q47 | 生产保留期（`data-ops-spec.md:1101`） | **流水 180 天、LOGIN / LOGOUT / PERIODIC 快照 90 天、GM / 安全快照永久，写进 `values/prod.example.yaml`；prod 为 0 时 preflight WARN** | 采纳 7.2 的建议值；0 只是容量问题，不是安全问题 |
| Q48 | PARITY 版本基线怎么写 | **首个发布起按 `vX.Y.Z ↔ mmorpg commit` 登记** | 现在每行都是 0.1.0-SNAPSHOT，区分不开 |
| Q49 | K8s 上要不要支持挂卷换表（7.1 §3.7） | **不支持**；换表 = 出新镜像 | 「镜像 = 代码 + 同一提交的表」才能追溯 |
| Q50 | 多个环境能不能共享一个 Redis | **不能**；一个环境一套 Redis / MySQL / Kafka | 租约与代次键里没有环境段（`tools.md:416`） |
| Q51 | k8s CI 的频率 | **静态检查每次 push；k3s 端到端每日 + `deploy/helm/**` / `deploy/observability/**` / `xm-ops/**` 变动时 + 手动** | 端到端约 30–40 分钟 |
| Q52 | 本机要不要装 helm / promtool | **不装**，全部交给 CI；需要加快迭代时再请用户批准下载 | 下载需用户批准 |
| Q53 | Druid 连接池指标 | **推迟**（architecture §11「未覆盖」） | — |
| Q54 | 发布说明的契约差异（7.5 CQ13 设想由 `tools/Release.java` 复用 `ContractGate`） | **由 `xm-ops release manifest` 以库依赖调用 `ContractGate`**，比「上一个发布 tag → 当前」 | 工具形态已改为模块（Q25）；一份实现 |
| Q55 | 集群内压测、robot 镜像、压测编排（7.4 Q35 / Q36 交来） | **7.6 不做**：robot 继续在 runner 宿主上跑（经 Ingress 与 NodePort 已覆盖真实入口）；压测编排等 7.4 的 `summary.json` 落地后另起一行 roadmap | 7.1 Q6 / D18 同口径（不出 robot 镜像）；YAGNI |
| Q56 | 巡检要不要只读从库 / 只读账号（7.4 Q7） | **7.6 只在 `prod.example.yaml` 里留 `xm.data.consistency.datasource` 的位置与 `xm_ro`（只有 SELECT）账号说明，不默认启用**；有从库的环境再配 | 生产 MySQL 托管，从库与否由环境决定 |

---

## 11 验证计划

### 11.1 已经做过的取证

- 本稿引用的基线 file:line 逐条读过原文；§0.6 的 22 条更正都回到代码核对过（`release.yml` 步骤行号、`AssignGateResponse` 的码、`SceneLinkManager.send` 的按需建链、`AdmissionPhase` 的终态、
  `FallbackLines` 的正则、gateway 的 seed-zones、login / team 租约语义、`GateNode.onLeaseLost`、`BattleNode.reportDrainedAfterLeaseLoss`、各 yaml 的停机阶段与运行模式）。
- §4.6 中标为「7.6」的 60 条告警，引用的指标名与标签值逐个对照了定义类（`SceneMetrics`、`GateMetrics`、`WorldChannelMetrics`、`SceneDirectoryProvider`、`AssignGateMetrics`、
  `LoginHttpMetrics`、`LoginMetrics`、`FriendMetrics`、`GuildMetrics`、`TradeMetrics`、`DataMetrics`、`RedisKillSwitchSync`）。
- 基线告警与清单计数见文件头实测。

### 11.2 本机验证（没有 Docker / Helm / kubectl）

| # | 批次 | 验什么 | 怎么验 / 判据 |
|---|---|---|---|
| V1 | 7.6a | `ProductionGate` | `./mvnw -B -pl xm-common -am test`：31 / 32 字节边界（去空白、按 UTF-8 计）；占位串大小写；两两相同；未设置；dev 只 WARN；运行模式写错按 prod；日志级别与 exposure 规则 |
| V2 | 7.6a | 各进程启动拒绝 | 每个进程模块一条启动测试：prod 模式 + 31 字节 `XM_DUBBO_SECRET` → 上下文在任何端口绑定前失败、错误只含变量名；dev 模式能起并捕获到 WARN；login 的 prod + `xm.login.mode=dev` 拒启 |
| V3 | 7.6a | `ReleaseRules` | `./mvnw -B -pl xm-ops -am test`，照搬基线 `release_common_version.tests.ps1` 的断言：`V1.2.3`、`v01.2.3`、`v1.2.3\n`、全角数字、`1.2.3` 都拒，`v1.2.3-rc.1` 过；CHANGELOG 缺段 / 重复 / 空段 / 只有链接行都拒，`[1.2.3-rc.1]` 与 `[11.2.3]` 不命中 `1.2.3`；tag 128 过、129 拒，黑名单不区分大小写；sha256sums 缺文件、哈希不符、多文件、含 `..`、反斜杠、重复、CRLF 都判失败 |
| V4 | 7.6a / b | `xm-ops` 编排逻辑 | 假 `CommandRunner` 断言 zone-open / zone-close / gate-roll / battle-roll 的命令序列与金样一致；NodePort 段重叠、越界、副本超 portBlock、宽限期低于公式、Secret 缺键、`publicHost`+Local、可变 tag，都在**任何写命令之前**以 1 退出；参数错 2；gate-roll 中途身份变化 → 撤回标记并中止 |
| V5 | 7.6a | `release check` 实跑 | 在 scratch 里克隆本仓库：干净 + 有 CHANGELOG 段 → 0；脏树、缺段、`v1.2`、tag 指向别的提交 → 1；参数错 → 2。不碰真实工作区 |
| V6 | 7.6b | 序号与通告地址 | `PodOrdinal`：`xm-gate-z101-3` → 3；`xm-gate`、`x--1` → 需要时拒启；base 30000 + 序号 3 → 30003；`advertise-port` 与 base 同配拒启；`{ordinal}` / `{zone}` 模板 |
| V7 | 7.6a | 可用性指示器 | 替身租约与替身时钟：gate 停接客 → readiness DOWN；租约 lost → liveness DOWN；scene 没有 ACTIVE 频道 / 疏散中 → readiness DOWN；`logicLoop` 卡住 31 s → DOWN、29 s → UP；battle 准入关闭 → readiness DOWN，租约丢且房间归零 → liveness DOWN |
| V8 | 7.6a | 结构化日志 | 用 `OutputCaptureExtension` 起最小上下文：`XM_LOG_FORMAT=logstash` 时输出含 `@timestamp`、`level`、`logger_name`、`thread_name`、`message`、`service`（gate / scene 另有 `zone`），异常的 `stack_trace` 被截断到上限；`XM_LOG_FORMAT` 为空时是文本。确认 3.5.16 的 `json.add` / `stacktrace` 键真的生效 |
| V9 | 7.6a | 指标目录与守卫 | `./mvnw test` 写出各模块 `target/metrics-catalog.txt`；`java -jar xm-ops/target/xm-ops-*.jar obs check` 退出 0。**反例**：scratch 副本里把一条规则改成不存在的指标名、或把一条 `increase()>0` 规则指向未预注册的组合，退出 1 |
| V10 | 7.6a | 兜底回灌 JSONL | 造一份 JSONL（含 `tx_id = 0` 与非 0 的行、带引号与反斜杠的 `extra`）：解析出的 `extra` 与原文一致；同一行在两个文件里各出现一次 → 只入库一次；文本格式回归不变 |
| V11 | 7.6a | 本机切片真实效果 | `with-backends.sh` 起切片：①`XM_RUN_MODE=prod` 且秘密 16 字节 → 进程拒启，日志只有变量名；②各进程 `/actuator/health/{liveness,readiness}` UP，`/actuator/info` 的 `build.release` 为空；③`XM_GATEWAY_SEED_ZONES_ENABLED=false` 时空库不插 zone 1；`XM_GATEWAY_TRUSTED_PROXIES` 空串与 `10.42.0.0/16,10.43.0.0/16` 都能绑定；④`redis-cli DEL` gate 的租约键并等过一个 TTL → gate liveness DOWN；⑤`XM_POD_NAME=xm-gate-z1-1 XM_GATE_ADVERTISE_PORT_BASE=30000` → 目录里客户端端口 30001（robot smoke 此时连不上，证明下发的确实是新地址），不设时行为不变 |
| V12 | 7.6a | 宽限期公式 | xm-scene 启动打出的下限 N 与 `xm-ops` 用同一组三时限算出的值相等，且 ≤ 60 |
| V13 | 7.6a | 时钟偏差指标 | 本机 Redis 下 `xm_redis_clock_offset_seconds` 绝对值 < 0.1 |
| V14 | 7.6b | battle 排空接口 | 无令牌 503、无操作人 400、prod 运行模式下仍可用；调用后 `xm_battle_admission_phase` = 2、目录 `accepting=false`、readiness DOWN；新开战 `NOT_ALLOCATABLE`，在打的房间照常结束 |

### 11.3 CI

所有 workflow 沿用 7.1 §4.2 的约定：`ubuntu-24.04`、`permissions: contents: read`（另有说明的除外）、只用 GitHub 官方 action 并按 SHA 钉死、下载的二进制与镜像钉版本并核对 sha256 / digest、
不使用 repository secret（随机生成并 mask）、每种失败都转成 `::error` annotation（job 日志匿名读不到，7.1 §11.5）、失败时上传 `kubectl get events`、各 Pod 日志与 `describe` 为制品。

| # | workflow / job | 触发 | 判据 |
|---|---|---|---|
| C1 | `ci.yml` / build（扩展） | 同 7.1 | `verify` 之后跑 `xm-ops obs check`（读各模块指标目录），退出 0 |
| C2 | `ci.yml` / `obs-static`（新） | 每次 push / PR | 用钉 digest 的官方镜像跑工具：`prom/prometheus` 的 promtool `check rules` 与 `test rules`（alertname 集合 = 用例覆盖集合）；看板每个 expr 生成临时 recording rule 再 `check rules`；`grafana/loki` 的 `-verify-config`；`timberio/vector` 的 `validate --no-environment` 与 `vector test`（JSON 行、文本行、堆栈续行、审计 logger 路由；断言标签集里没有玩家号）；看板 JSON 可解析、uid 唯一 |
| C3 | `k8s.yml` / `k8s-static`（新） | 每次 push | 下载钉版本的 helm；`helm lint` 三个 chart；按 `values/ci.yaml`、`values/prod.example.yaml` × 两个 zone 样例 × 四种暴露模式渲染；**反例必须失败**：`tag: latest`、缺 memory limit、宽限期低于公式、`publicHost`+Local、loadBalancer 多副本主机不含 `{ordinal}`、配了 Ingress 没配 trustedProxies；xm-infra 的镜像 digest 与 `deploy/compose/infra.yaml` 逐字相同 |
| C4 | `k8s.yml` / `k8s-e2e`（新） | 每日、路径命中的 push、手动 | ① 环境自检；② 装钉版本的 k3s 与 helm（核对 sha256），等节点 Ready；③ 打包、构建镜像，`docker save \| sudo k3s ctr -n k8s.io images import -`（infra 镜像同样导入）；④ 全部渲染结果 `kubectl apply --dry-run=server` 通过；⑤ 生成秘密 → `xm-ops deploy secrets`；`deploy preflight --profile dev` 退出 0，**同一份 values 用 `--profile prod` 必须退出 1**；⑥ `platform-up --with-infra`；`zone-open --zone 1`（hostIP，gate 2、scene 2）；`zone-open --zone 2`（podIP，gate 1、scene 1；等 5.4 X16）；⑦ 断言：全部 Pod Ready；每个 Pod 的 `/actuator/info` 里 `build.commit = $GITHUB_SHA`；xm-scene 日志里的宽限期下限 ≤ 渲染值；日志里没有 `Read-only file system`；Java 进程用 `xm_app` 连库全部起来（权限够）；NetworkPolicy：`default` namespace 里的临时 Pod 连 `xm-login:20881` 失败，`monitoring` namespace 里的临时 Pod 能抓 `xm-gate-z1-0:18103/actuator/prometheus`，探针不受策略影响（Pod 能 Ready 即证明） |
| C5 | 同上 / 运维演练 | 同上 | **滚 gate**：robot `soak` 在线时 `gate-roll --zone 1`：在线会话断线后重连成功，排空期间新 assign-gate 不分到正在排空的 gate，结束后标记清掉。**滚 scene**：`helm upgrade xm-zone-1` 只改一个注解：`xm_scene_storage_writes_seconds_count{result=~"failed\|rejected"}` 增量为 0、robot 货币余额前后一致；后台 `kubectl logs -f` 采到旧 Pod 的「停服写回完成」行（被 SIGKILL 就不会有）且用时 < 宽限期。**滚 login / friend**：期间 robot 的 1003 比例 ≤ 1%（H14）。**驱逐**：对 gate Pod 调 eviction API 回 429；对 scene 连续驱逐两次，第二次 429。**关区再开区**：`zone-close --zone 2` 期间 assign-gate 先回 `zone_maintenance` 后回 `zone_closed`；`zone-open --zone 2` 后同一账号登录，角色与货币都在。**Redis 重启**：删 `redis-0` 前后 `xm:node-id-epoch:*` 不回退，持租约进程按 Q12 重启后 robot 恢复。**回滚**：`helm upgrade` 改一个环境变量后 `rollback`，环境变量复原 |
| C6 | 同上 / GM 与运维通道 | 同上 | 经 `kubectl port-forward` 调 gate 的 `GET /gm/identity` 回 200（证明转发进来的是回环来源）；从另一个 Pod 直接调回 403 |
| C7 | 同上 / 反例 | 同上 | 删掉 Secret 里一个必需键后部署：对应 Pod `CreateContainerConfigError`，且 `xm-ops` 在此之前就拒绝；nodePortBase 重叠：preflight 拒绝 |
| C8 | `k8s.yml` / `k8s-2replica`（新） | 每日、手动 | 空库冷启动：全局服务除 data / battle 外全部 2 副本、zone 1（gate 1、scene 1），全部 Ready（H9）；robot 第一期 + friend / chat / team / guild / trade |
| C9 | `integration.yml` / stack + 观测（扩展） | 同 7.1 | stack 叠加 `observability.yaml`。robot 第一期跑完后：Prometheus `/api/v1/targets` 全部 up、`/api/v1/rules` 全部 `health=ok` 无 `lastError`；规则与看板引用的每个指标至少有一条序列（按需注册的白名单除外）；`xm_gateway_assign_gate_total{code="0"}`、`xm_gate_handshakes_total{result="ok"}`、`xm_scene_audit_records_total{result="acked"}`、`xm_data_kafka_records_total{outcome="inserted"}` 都 > 0；`ALERTS{alertstate="firing"}` 为空。**故障注入**：`stop xm-chat` → 60 s 内 `up{job="xm-chat"}==0` 且 A2 进入 pending；7.1 的停机断言 `stop xm-scene` 之后 3 分钟内 A24 变 firing。Loki：`{service="xm-gate"}` 有数据且 `\| json` 能解析，`/labels` 是白名单子集，审计流 `{stream_class="audit"}` 存在（trade 播种会写 `xm.audit.*`，具体 logger 落码时核对）；Grafana：6 个看板、2 个数据源 |
| C10 | `release.yml` 试跑（用户触发，`v0.1.0-rc.1`） | 手动 | `release check` 通过（CHANGELOG 段、CI / Integration 已绿）；镜像断言与整栈冒烟通过；制品里有 manifest、notes、sha256sums、chart 包、images.tar；本机对下载的 manifest 与 sha256sums 跑 `xm-ops release verify` 退出 0。**反例**：CHANGELOG 没有对应段 → 在第 2 步就失败，不进入构建 |
| C11 | `release-push.yml` 试跑（用户触发） | 手动 | load 后镜像 ID 都等于 manifest；digest 文件覆盖全部镜像；`docker buildx imagetools inspect` 读到的 digest 一致；`deploy preflight --profile prod --release-manifest --digests` 的 H 节通过；**同一版本再推一次必须被拒** |

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

robot 跑在 runner 宿主上：`--gateway http://127.0.0.1`（k3s 的 Traefik 经 ServiceLB 占宿主 80 端口，整条 Ingress 链路被真实走一遍）；`--data-url`、`--scene-metrics-url` 经 port-forward。
除一个可选的新场景 `soak --seconds N`（保持在线、统计断线重连与 1003，配合滚动演练）外，全部复用现有场景，不改客户端契约。

| # | 场景 | 判据 |
|---|---|---|
| R1 | `smoke`、`token`、`movement`、`audit`（zone 1，hostIP） | assign-gate 的 `gate_ip` 是节点 InternalIP、`gate_port` = portBase + 序号；握手成功；`xm_gate_handshakes_total{result="wrong_gate"}` 保持 0 |
| R2 | 两个 gate 地址互不相同 | 多次 assign-gate 拿到两个不同端口；拿 gate-0 的票据去连 gate-1 回 `wrong_gate`（反例） |
| R3 | `drain` + `gate-roll` | 长连会话在 deadline 后断开，重走 assign-gate 落到另一个 gate；`currency` 前后一致 |
| R4 | scene 滚动（5.5 落地后） | 玩家被疏散，收到的 79 / 23 口径同 5.5；货币不丢 |
| R5 | `zones` 扩展 | PREVIEW 时 503 `zone_not_open`、MAINTENANCE 时 `zone_maintenance`、开放后 OK、关区后 `zone_closed`；区服列表状态与负载档与目录一致；关掉 seed-zones 后新区先以维护出现 |
| R6 | `travel --zone 1 --visit-zone 2`（5.4 落地后） | 124 里的地址是 zone 2 某个 gate 的 Pod IP（podIP 模式）；回到 zone 1 后地址是 hostIP 模式的 |
| R7 | prod 口径（gate / scene / trade 的运行模式 prod，login 保持 dev 以便 robot 登录） | `currency --expect-gm deny` 通过；trade 播种回 403；这些进程在 prod 运行模式下能起来，顺带证明 `ProductionGate` 对合规配置（64 位随机秘密、`xm_app`、Redis 口令）放行 |
| R8 | `friend` / `chat` / `team` / `guild` / `trade`（`k8s-2replica` job） | 全局服务 2 副本下全部通过 |
| R9 | `queue` / `ratelimit`（经 Ingress，最后跑） | 配了 trustedProxies 后按真实对端 IP 计桶，不再全体共用一个桶 |
| R10 | `battle-smoke`（6.2 / 6.4 落地后） | 票据地址是 battle 序号对应的入口；`battle-roll` 期间在打的局正常结算 |

### 11.5 反例与守卫一览

| 守卫 | 在哪验证 |
|---|---|
| 秘密过短 / 占位 / 复用 → 拒启 | V1、V2、V11；C4 的 prod preflight 反例 |
| Secret 缺键 → `CreateContainerConfigError`，且工具先拒 | C7 |
| 缺 memory limit、宽限期低于公式、可变 tag、暴露模式组合非法 → 渲染失败 | C3 |
| NodePort 段重叠 → preflight 拒 | V4、C7 |
| 规则引用不存在的指标、`increase()>0` 指向未预注册组合 | V9、C1 |
| 规则没有对应 promtool 用例 | C2 |
| 告警真的会响 / 健康时不响 | C9 |
| 同一版本二次推送被拒 | C11 |
| 模块清单漂移（xm-ops 不该出镜像） | 7.1 的守卫（排除 xm-ops 后仍相等） |

### 11.6 交付口径

- 本机只能验 V 项；K8s、compose、观测栈、发布 workflow 的结论都以 CI 为准，提交说明写「K8s / 观测栈未在本机运行，结论以 CI run `<id>` 为准」，并列出本机跑过的 V 项与结果。
- CI 结论按 7.1 §11.5 用匿名 REST 读；`release.yml` / `release-push.yml` 由用户点，Claude 只读结论。
- 规则与 compose / chart 只有推上去才能验证：CI 暴露的问题用后续提交修，标题写「批次 7.6a/b 修正：…」；不得用 `@Disabled` 或跳过 job 绕过。
