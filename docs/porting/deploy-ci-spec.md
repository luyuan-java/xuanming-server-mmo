# 本地编排、镜像构建与 CI（批次 7.1）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。本地副本是 `D:\work\mmorpg` 的浅稀疏克隆。本稿用到的
> `deploy/**`、`.github/**`、`tools/scripts/**`、`java/gateway_node/**`、`dev.bat` 都已检出；`robot/Dockerfile`、`bin/etc/**` 没有检出，按
> `git show HEAD:<path>` 读；`third_party/**`（子模块）没有检出，本稿也用不到；`deploy/k8s/runtime/linux/` 被 `.gitignore` 忽略，本来就不在仓库里。
> **没有因为目录缺失而无法下结论的地方。**
>
> **Java 侧**：以 2026-10-05 的工作区为准（HEAD `aa8b5b5`）。有两批改动正在并行进行：
> - 批次 5.2 在 `xm-scene`、`xm-gate`、`xm-scene-manager`、`xm-player-store`、`tools/local/start-slice.sh` 等处有未提交的改动；
> - 批次 6.2 的新模块 `xm-battle` 还没入库，但根 `pom.xml` 的工作区里已经加了 `<module>xm-battle</module>`。
>
> 这些文件的行号在提交后可能小幅漂移，所以引用时同时写出配置键或类名。
>
> **盘点 id**：deploy-local-compose（`docs/porting/inventory/java-infra.md:309-319`）、deploy-container-images（`:321-331`）、ci-workflows（`:369-379`）、
> container-image-build（`docs/porting/inventory/tools.md:398-408`）。路线图在 `docs/porting/roadmap.md:91`。
>
> **路径怎么读**
> - 以 `deploy/`、`.github/`、`tools/scripts/`、`java/gateway_node/`、`dev.bat` 开头的路径在 mmorpg 里。
> - 以 `xm-`、`docs/`、`tools/`（`tools/scripts/` 除外）、`contract/`、`config-data/` 开头的路径，以及 `PARITY.md`、`AGENTS.md`、`README.md`、`pom.xml`、`mvnw`，
>   都在 `D:\work\xuanming-server-mmo` 里。
> - `xm-<模块> application.yaml:N` 指该模块 `src/main/resources/application.yaml` 的第 N 行。
>
> **本稿的来历**：由两份分区稿合并而成。一份是基线盘点（compose、镜像、脚本、CI、秘密），一份是 Java 设计（两个 compose 文件、参数化 Dockerfile、
> 三个 workflow、验证计划）。两稿互相矛盾、或与代码不符的地方，都回到代码重新核对过，更正列在 §1.10。本稿只读代码，除本文件外没有改任何文件。
>
> **本稿做的实测**（2026-10-05，全部是只读操作）：
> 1. 在 Java 仓库执行 `java tools/ContractSync.java --mmorpg D:/work/mmorpg --check`，退出码 0，输出「契约一致：mmorpg@26ceb70ca5771c5ab055c2ec7144c1e55f32c528」。
> 2. 匿名访问 GitHub REST：两个仓库都是 public。对 mmorpg，runs、jobs、`check-runs/{id}/annotations` 都能匿名读到，`actions/jobs/{id}/logs` 返回 **403**。
>    Java 仓库 `actions/workflows` 的 `total_count` 为 0，也就是还没有任何 workflow。
> 3. mmorpg 自己的 CI 在 `26ceb70ca` 这个提交上的结论：
>    - `Login Path Tests`（push）failure，红的 job 是 `go-zero login tests`，Java gateway 那个 job 是绿的；
>    - `Go 全模块门禁`（push）failure，有 7 个模块腿红；
>    - `C++ 构建门禁`（每日定时）连续 5 天 failure。
>
>    这里只看了结论。日志匿名读不到，所以没有判断失败原因。
> 4. 用 GitHub API 查的 star 数：moby 72.1K、docker/compose 38.3K、Jib 14.4K、hadolint 12.5K、Testcontainers 8.7K、docker/build-push-action 5.4K、
>    actionlint 4.3K、buildpacks/pack 3.0K、git-commit-id-maven-plugin 1.7K、paketo-buildpacks/java 151。
> 5. 用 Docker Hub API 查了四个 tag 及其 index digest：`mysql:8.4.11`、`redis:8.10.2`、`apache/kafka:4.3.1`、`eclipse-temurin:21.0.12.1_1-jre-noble`，
>    digest 写在 §2.4 和 §3.3。这些版本与开发机上实装的本地进程逐个相同（Kafka 4.3.1 另见 `docs/design/tech-stack.md:15`）。
>    Temurin noble JRE 的 Dockerfile 装了 curl 和 wget，并设了 `LANG/LC_ALL=en_US.UTF-8`（adoptium/containers `21/jre/ubuntu/noble/Dockerfile:26-33`）。
> 6. GitHub 官方 action 的最新版本（releases/latest）：
>    - `actions/checkout` v7.0.1，commit `3d3c42e5aac5ba805825da76410c181273ba90b1`；
>    - `actions/setup-java` v6.0.1，commit `de7274f081f381c8f8158605e0321c36c376e2e6`；
>    - `actions/upload-artifact` v7.0.1，commit `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`。
>
>    三者的 `runs.using` 都是 `node24`。

---

## 0 概览与范围

### 0.1 结论速览

| 问题 | 结论 |
|---|---|
| compose 只起依赖，还是连 Java 进程一起起 | **两个文件**。`deploy/compose/infra.yaml` 只有 MySQL、Redis、Kafka 三个依赖；`deploy/compose/stack.yaml` 用 `include` 引入 infra，再加全部 Java 进程（第二个 scene 用 profile 控制，可选）。本机没有 Docker，所以这两个文件**唯一的执行者是 CI**：集成测试 job 用 infra，整栈冒烟 job 用 stack。有 Docker 的机器上可以「infra + start-slice.sh」，也可以直接起整栈 |
| 镜像怎么构建 | **所有进程模块共用一个参数化 Dockerfile**（`--build-arg MODULE=xm-gate`）。jar 由 CI 宿主上的 Maven 一次打齐；Dockerfile 只用 Spring Boot 自带的 `-Djarmode=tools extract --layers` 解包，再组装运行层。不用 Jib（14.4K star）。不用 Buildpacks：虽然 goal 是 Spring Boot 自带的，但实际干活的是 Paketo（buildpacks/pack 3.0K、paketo-buildpacks/java 151） |
| 基础镜像 | `eclipse-temurin:21.0.12.1_1-jre-noble`，钉 digest。与本机 JDK 21.0.12.1+1 是同一个构建；glibc，自带 curl 和 bash |
| JVM 参数 | 镜像缺省 `JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"`，不写死 Xmx |
| 健康检查 | 所有进程打开 readiness / liveness 探针并暴露 `info`。compose 的 healthcheck 要同时满足：readiness 为 UP，且业务端口**按服务名**能连上；scene 另加「主世界频道 ≥ 1」。镜像本身不写 `HEALTHCHECK` |
| 秘密 | 只从环境变量进。compose 中必填的写 `${VAR:?}`，可选的写 `${VAR:-}`；每个进程只拿到自己用的那几个。CI 每次运行随机生成并 mask，**不使用任何 GitHub secret** |
| 构建信息 | 根 pom 给 spring-boot-maven-plugin 加 `build-info`，写入 git commit 与契约来源 commit（都是 40 位），经 `/actuator/info` 暴露。镜像另打 OCI label，并写 `/app/BUILD_INFO` |
| 镜像名与 tag | `${XM_IMAGE_NAMESPACE:-xuanming-java}/<模块名>:<12 位 sha>`，永远不打 `latest`。7.1 只构建、只冒烟，**不推送** |
| CI | 三个 workflow：`ci.yml`（构建 + 单测、契约 `--check`）、`integration.yml`（真依赖集成测试、整栈冒烟）、`contract-drift.yml`（每天对比 mmorpg main，**只报告、不判红**） |
| 结论怎么看 | 不需要 Docker 的在本机验（§11.2）。用到 Docker 的都只能靠 CI，结论通过 GitHub REST **匿名**读取。job 日志匿名读不到（403），所以每一种失败都必须转成 annotation |
| 分批 | **7.1a**：CI 构建与契约、漂移报告、`infra.yaml` 加集成测试 job、`TestReport`、mvnw 可执行位。**7.1b**：build-info、配置占位符与探针、Dockerfile、`stack.yaml` 与整栈冒烟。7.1b 要等 5.2 提交以后再做，因为两者会改同一批 yaml |

### 0.2 7.1 交付什么

1. **本地编排**：`deploy/compose/infra.yaml`（依赖）和 `deploy/compose/stack.yaml`（整栈）。秘密走 `.env`，该文件不入库，仓库里只放 `env.example`。
2. **镜像**：一个参数化 Dockerfile 加 `.dockerignore`。镜像带 build-info、OCI label 和 `/app/BUILD_INFO`，并约定好 tag 规则，作为 7.6 直接复用的接口（§3.8、§5）。
3. **CI**：三个 workflow、一个 Maven 问题匹配器、一个单文件工具 `tools/TestReport.java`。
4. **配置**：进容器之前所有写死的地址都要能被覆盖（§6.4），只加占位符，缺省值保持现在的值，本机行为不变。另外打开 readiness / liveness 探针和 `info` 端点。
5. **文档与对账**：tech-stack、architecture、AGENTS、README、roadmap、PARITY，以及盘点里过时说法的更正（§7.3）。

### 0.3 与 7.6 的边界

| 项 | 7.1 | 7.6 |
|---|---|---|
| compose | 依赖与整栈，供开发和 CI 使用，`XM_RUN_MODE` 缺省 dev | 是否另出生产形态的 compose 或 K8s，由 7.6 定 |
| 镜像 | 构建；本机 / CI 冒烟；快照 tag `<sha12>` | 推送 registry（GHCR）、发布轨 tag `vX.Y.Z-<sha12>`、多架构、签名与扫描、只读根文件系统 |
| 版本 | `build.version` = `project.version`（0.1.0-SNAPSHOT），另带 commit 和契约 commit | 版本号方案、CHANGELOG、`tools/Release.java` 的 preflight、制品目录与离线包 |
| 秘密 | 环境变量名矩阵（§6.1）；compose 写 `:?`；CI 随机生成 | prod 档位启动自检（缺失、过短、命中占位串时拒启）、K8s Secret、轮换 |
| 健康 | readiness / liveness 探针、`info`；scene「频道已铺好」暂由 compose healthcheck 判 | 把 scene 的频道判据改成正式的 readiness `HealthIndicator`；`terminationGracePeriodSeconds`；管理端口绑 Pod IP |
| 网络 | compose 网络内服务名互访；宿主侧只发布到 127.0.0.1 | `DUBBO_IP_TO_BIND`、NetworkPolicy、gate / battle 对外入口 |
| 观测 | 不做 | 结构化日志、告警规则、看板、日志采集编排（基线 `deploy/docker-compose.observability.yml` 归这里） |
| CI | 构建、单测、契约、集成测试、整栈冒烟、漂移报告 | 手动触发的发布 workflow，参照基线 `release.yml:12-17`：不推送、不打 tag、不用 secret |

### 0.4 与并行批次的关系

- **5.2**（跨节点换图）正在修改 `xm-scene` / `xm-scene-manager` 的 `application.yaml` 和 `tools/local/start-slice.sh`。7.1b 也要改这几个 yaml（§6.4），所以必须等 5.2 提交以后再做。
- **6.2**（`xm-battle`）会新增一个进程模块，带来新的秘密 `XM_BATTLE_TOKEN_SECRET` 和新的端口 12000 / 21200 / 18112。整栈冒烟里的「模块清单漂移守卫」（§4.5 第 4 步）会强制要求把它登记进 `stack.yaml`。
  **规则**：从 7.1b 起，任何新增进程模块的批次，都要同时登记 `stack.yaml`、`start-slice.sh` 和 `deploy/compose/env.example`（秘密矩阵见 §6.1）。
- **7.1a 不依赖上面两批**，可以先做。

### 0.5 本机约束与结论来源

本机没有 Docker 和 Go。compose 文件和 Dockerfile 在本机既不能执行，也不能做静态校验（§10 Q3 建议不另装工具）。结论分两类：

- 不需要 Docker 的项目都在本机验证（§11.2）；
- 需要 Docker 的只能在推送 main 之后，用匿名 REST 读取 CI 结论（§11.5）。

按 AGENTS §4「没有运行证据时不得声称通过」，提交说明里要明确写「compose / 镜像未在本机运行，结论以 CI run `<id>` 为准」。

---

## 1 基线盘点（mmorpg `26ceb70ca`）

### 1.1 docker compose 文件清单

| 文件 | 服务 | 怎么起 | 用途 |
|---|---|---|---|
| `deploy/docker-compose.yml` | kafka、kafka-topic-init、kafka-ui、redis、redis-cluster-0..5 与 redis-cluster-init（profile `redis-cluster`）、mysql、nacos、etcd | 两种方式：① `dev.bat:448` 执行 `up -d`（`:457` 是 down），起全部缺省服务；② `tools/scripts/start_game.ps1:444-445` 加 `--profile redis-cluster`，只起 etcd / redis / mysql / kafka / redis-cluster-*，带 `--no-recreate --pull never`，然后在 `:448-452` 逐个等就绪。topic-init 由 `:197` 单独跑 | 本机基础设施。游戏进程（C++ / Go / Java）都跑在宿主上，不进容器 |
| `deploy/docker-compose.login-stack.yml` | sandbox-mock、login（Go）、gateway（Java） | `up -d --build`，前提是基础设施已经起好 | 用 Linux 容器跑登录链，做 1k / 2k / 5k 压测；不含 C++ gate / scene |
| `deploy/docker-compose.observability.yml` | loki、alloy、grafana（固定项目名 `xuanming-observability`，`:16`） | `dev.bat:465` / `:478` | 采集宿主机上的文件日志。归 7.6 |
| `deploy/docker-compose.tidb.yml` | pd、tikv、tidb `v8.5.2`（`:15/30/47`） | 手动 | TiDB 试点，与 Java 无关（roadmap「不移植」） |
| `java/springboot_satoken_auth_starter/docker-compose.yml` | mysql:8.0、redis:7 | 没有任何脚本调用 | starter 库的遗留文件 |

### 1.2 `deploy/docker-compose.yml` 逐个服务

- **kafka**（`:5-54`）：`apache/kafka:latest`（`:6`），单节点 KRaft。
  - 双监听：容器内 `INTERNAL://kafka:29092`，宿主 `EXTERNAL://localhost:9092`（`:16-18`）。
  - 资源可以用环境变量覆盖；broker 缺省 retention 30 min，理由是要大于 C++ 消费者的 `max.poll.interval.ms`（`:25-51`）。
  - 自动建 topic 打开，且 `KAFKA_NUM_PARTITIONS=1`（`:44`）。
  - 为了绕开 Windows 上的权限问题，用 `user: root:root` 运行（`:54`）。
- **kafka-topic-init**（`:82-196`）：一次性容器。预建控制面命令 topic（256 分区）和审计 topic（6 / 3 分区、保留 30 天），然后**读回断言**，分区数或 retention 不符就 `exit 1`（`:140-146`、`:154-161`）。
  注释写明：自动建 topic 打开时，必须赶在任何生产者之前先跑它（`:66-81`）。
- **kafka-ui**（`:199-214`）：`latest`，宿主端口 8080。
- **redis**（`:217-235`）：`redis:latest`，开 AOF、`maxmemory 8gb`、`allkeys-lfu`，不设口令。
- **redis-cluster**（`:237-386`）：profile 控制，3 主 3 从，只给 Go match 服务用。
- **mysql**（`:389-408`）：
  - 镜像 `mysql:latest`（`:390`）；
  - root 口令和 appuser 口令都明文写在文件里（`:395-398`）；
  - 把 `./mysql-init` 挂成 initdb 目录（`:402`），里面只建库和授权，不建业务表；
  - healthcheck 是 `mysqladmin ping -h localhost`（`:405`），走的是 socket。
- **nacos**（`:411-438`）：`latest`，鉴权 token 和数据库口令都明文（`:423`、`:429`）。基线里**没有任何服务使用 Nacos**，`start_game.ps1` 也不起它。
- **etcd**（`:441-470`）：`bitnamilegacy/etcd:latest`（`:444`），`ALLOW_NONE_AUTHENTICATION=yes`（`:450`）。
- 所有端口都发布到 0.0.0.0（如 `:8-10`、`:220-221`、`:392-393`）；每个服务都写死了 `container_name`（如 `:7`、`:219`、`:391`）。

### 1.3 依赖版本总表（基线 vs Java）

| 组件 | 基线 compose | 基线 K8s | Java 现状 / 需求 |
|---|---|---|---|
| MySQL | `mysql:latest`（`:390`） | `mysql:8.0`（`deploy/k8s/manifests/infra/mysql.yaml:109`） | 必需。库 `xm_java`；8 个进程连接它（§2.2）。本机 8.4.11 |
| Redis | `redis:latest`（`:218`） | `redis:7.2` | 必需。DB 12，集成测试 DB 13。本机 8.10.2 |
| Kafka | `apache/kafka:latest`（`:6`） | 同左 | 必需，用于资产审计 xm-scene → xm-data。本机 4.3.1 KRaft（`tech-stack.md:15`）。topic 由进程自己按规格建（§2.3） |
| etcd / Nacos / Redis Cluster / kafka-ui / TiDB | 有 | 部分有 | 都不需要（§2.3） |
| Loki / Alloy / Grafana | 钉了 tag | `loki:3.5.8` | 归 7.6 |

### 1.4 镜像（Dockerfile）

| Dockerfile | build context | 构建层 | 运行层 | 出处 |
|---|---|---|---|---|
| `deploy/k8s/Dockerfile.java-svc` | `java/<svc>/` | `eclipse-temurin:23.0.2_7-jdk@sha256:…`，用 apt 装 maven，在容器内跑 `mvn package` | `eclipse-temurin:23.0.2_7-jre-alpine@sha256:…`；uid 10001；`java -jar /app/service.jar` | `:25-51`、`:54-63`、`:88-90` |
| `deploy/k8s/Dockerfile.go-svc` | `go/` 加命名上下文 `tables` | `golang:1.26.5-alpine@…` | `alpine:3.20.10@…`；uid 10001 | `:58`、`:144-162`、`:175-180` |
| `Dockerfile.cpp` / `Dockerfile.runtime` / `Dockerfile.robot` / `Dockerfile.sandbox-mock` | 仓库根 / `go/` | C++ 源码编译 / 预编译二进制 / Go | `ubuntu:24.04@…` / `alpine@…`；uid 10001 | 与 Java 无关 |

**各镜像的共同约定**（Java 沿用）：

- 基础镜像写成 `tag@sha256:<index digest>`，并在 Dockerfile 头部写明更新方法（`Dockerfile.java-svc:17-22`、`Dockerfile.go-svc:20-24`）。
- 运行用户是固定数字 uid / gid 10001，因为 K8s 的 `runAsNonRoot` 只认数字（`Dockerfile.runtime:30-34`）。运行时不需要写的目录保持 root 所有（`Dockerfile.go-svc:146-162`）。
- 版本类 ARG 放在运行阶段的末尾，避免一改版本就击穿缓存（`Dockerfile.java-svc:71-76`）。
- 每个镜像都打 OCI label（version / revision / created / source / title），并写一份 `/app/BUILD_INFO`（`Dockerfile.java-svc:78-86`）。
- 不写 `HEALTHCHECK`；ENTRYPOINT 用 exec 形式，让业务进程成为 PID 1、直接收 SIGTERM（`Dockerfile.go-svc:217-220`）。
- 不用 `# syntax=`，也不用 `RUN --mount`（`Dockerfile.go-svc:25-31`）。
- 表数据：Go 镜像经命名上下文烤进镜像。原因是早先镜像里没有表，服务一起来就 CrashLoop（`Dockerfile.go-svc:51-57`）。Java gateway 镜像不需要表。

### 1.5 构建、打标、发布脚本（PowerShell）

- **单族镜像脚本**：`tools/scripts/java_svc_image.ps1`、`go_svc_image.ps1`、`k8s_image.ps1`，命令有 `build|push|release|list|list-refs`（`java_svc_image.ps1:35`）。缺省 registry 是 `ghcr.io/luyuancpp`（`:38`）。
- **tag 规则**（`tools/scripts/lib/release_common.ps1`）：
  - **镜像 tag**：快照轨是 `<12 位 sha>`，脏树加 `-dirty`（`:364-366`）；发布轨是 `vX.Y.Z-<sha12>`，脏树直接抛错（`:371-373`），长度超过 128 也抛错（`:377-379`）。
  - **可变 tag 黑名单**：latest、dev、main、stable、prod、release、nightly、snapshot 等（`:190-193`）。
  - **为什么不默认 latest**：`rollout undo` 会退回到同一个 digest，回滚等于没做（`java_svc_image.ps1:39-40`）。
- **离线制品**（`publish_images.ps1`、`make_release.ps1`、`fetch_images.ps1`、`import_images.ps1`、`artifacts_retention.ps1`）：
  - 制品目录名：快照轨用 `g<sha12>`（`publish_images.ps1:24`、`:400`），和镜像 tag 不是一回事。
  - 附带 `images-manifest.json`、`build-info.json`、`sha256sums.txt`。
  - **以上整块归 7.6**。
- 整个仓库里**没有 `docker login`**，也没有任何 workflow 推镜像。推送靠人在本机用自己的凭据完成。

### 1.6 版本戳与 build-info

- **Java（gateway_node）**：
  - `pom.xml:31-32` 定义了 `mmorpg.build.version`（缺省 `${project.version}`）和 `mmorpg.build.commit`（缺省 `unknown`）。
  - spring-boot-maven-plugin 的 `build-info` goal 用 `additionalProperties` 覆盖 version，并加上 commit（`:154-171`）。
  - 注释明说这次覆盖依赖 Spring Boot 3.4.3 的写入顺序，升级后可能**静默失效**（`:155-160`）。
  - 镜像构建时把 build-arg 传给 `mvn -D…`（`Dockerfile.java-svc:45-51`）。
- **镜像层**：OCI label 的 `source` 都指向 `https://github.com/luyuan-cpp/xuanming-server-mmo`；另写 `/app/BUILD_INFO`，三行：`version=`、`commit=`、`built_at=`（`Dockerfile.java-svc:78-86`）。
- **Go**：用 `-X shared/buildinfo.*` 注入，加 `-trimpath`（`Dockerfile.go-svc:130-139`）。
- **已知缺口**：C++ 运行镜像上的 revision 是「打包时的仓库 commit」，不一定是二进制的编译 commit（`Dockerfile.runtime:16-17`）。

### 1.7 CI workflows（`.github/workflows/`，都跑在 `ubuntu-latest`）

| workflow | 触发 | 内容 | 要点 |
|---|---|---|---|
| `cpp-build-ci.yml` | push / PR（paths 过滤）、每日 cron、手动 | 构建脚本契约（每个 PR 都跑）+ 容器里编译 C++（PR 上不阻塞） | 新的重型 job 先不阻塞，连续绿了再收紧（`:27-34`）；「新增子工程忘了同步清单」是真实发生过的漂移（`:18-21`） |
| `go-modules-ci.yml` | push main **不加 paths 过滤**、PR 带 paths | 自动发现 go.mod，矩阵 build + test，汇总 job | push main 不过滤的理由（`:24-28`）；带 paths 的 required check 会永远 pending（`:237-243`） |
| `login-path-tests.yml` | push main 不过滤、PR 带 paths | Java gateway `./mvnw -B --no-transfer-progress test`（JDK **23**）+ go/login + robot | 不连真依赖（`:11-14`）；`-count=1` 是因为 go test 缓存曾造成「假通过」（`:88-89`） |
| `exporter-tests.yml` | push / PR（paths） | 导表器、无漂移校验，最后编译 `java/config_node`（JDK 21） | 「mvnw 历史上是 0644，显式交给 bash」（`:362-363`） |
| `deploy-config-tests.yml` | push / PR（paths） | pwsh 跑 `tools/scripts/tests/*.ps1` | 目录不存在时给 warning 并放行（`:98-106`） |
| `include-cleaner-ci.yml` | 只在 PR | clang-tidy | — |
| `release.yml` | 只能手动 | 环境自检 → 校验 → 工作树必须干净 → 构建镜像 → 生成制品 → upload-artifact | 不推镜像、不打 tag、不部署、不用 secret（`:12-17`）；输入一律经环境变量进脚本（`:61-65`）；环境自检（`:83-95`）；脏树拒绝（`:360-375`） |
| `cr.yml` | **所有** PR | `anc95/ChatGPT-CodeReview` 把 PR diff 发给 `api.openai.com`（`:16-23`） | 唯一用 secret 的 workflow（`:18-19`） |

**基线 CI 的实际状态**：在 `26ceb70ca` 上，Go 门禁和登录链门禁都是红的，C++ 门禁连续 5 天红（见文件头实测第 3 条）。这正是 `cpp-build-ci.yml:27-34` 警告过的局面：长期红的门禁没人看。Java 版的对策是重型 job 分期加入（§4.5），漂移检查只报告（§4.6）。

### 1.8 秘密

- **compose 里明文写死**：
  - MySQL root 口令和 appuser 口令（`docker-compose.yml:395-398`）；
  - Nacos token 和数据库口令（`:423`、`:429`）；
  - Grafana 用 admin/admin，且匿名访问即 Admin（`docker-compose.observability.yml:65-70`）。
- **唯一一处 fail-fast**：login-stack 写成 `${LOGIN_DEV_PASSWORD_SHARED_SECRET:?…}`（`docker-compose.login-stack.yml:103`），没注入时 compose 在启动前就失败。
- **gateway 自带配置**：`password: apppass123`，`token-secret` 是占位串（`java/gateway_node/src/main/resources/application.yaml:13`、`:33`）。
- **K8s 侧**：`Resolve-InjectedSecret` 在 staging / prod 档位下，遇到秘密缺失、命中占位串或长度不够就抛错（`release_common.ps1:679-708`）。占位串黑名单见 `:648-660`。

### 1.9 基线自身的漂移（Java 不照搬）

1. compose 多处用 `latest`，与镜像侧「digest 钉死」的纪律矛盾，也与 K8s 用的版本不一致（MySQL：compose 是 latest，K8s 是 8.0）。
2. login-stack 里 gateway 连的是 `…/gateway` 库（`docker-compose.login-stack.yml:124`），但 initdb 没有建这个库，gateway 的表建在 `mmorpg` 库里（`deploy/mysql-init/gateway_tables.sql:4`）。这是按代码推断的，没有运行验证过。
3. Java 版本三处不一致：gateway 用 JDK 23，config_node 和 exporter-tests 用 21。Java 版统一用 21（Java 仓库 `pom.xml:42-43`）。
4. registry 命名空间不一致：缺省是 `ghcr.io/luyuancpp`（旧用户名），OCI source 写的却是 `luyuan-cpp`。`release.yml:213` 自己也提醒过：旧用户名靠 GitHub 重定向，一旦被别人抢注就会断。
5. `Dockerfile.java-svc` 用 apt 装的 maven，没用 mvnw（`:30`）；`EXPOSE 5555 5556` 疑似遗留端口（`:69`）。
6. Kafka 开着自动建 topic 且只建 1 个分区，与分区契约冲突，只能靠 topic-init 抢先跑来兜底（`docker-compose.yml:44`、`:66-81`）。
7. CI 门禁有空洞：`deploy-config-tests` 在目录缺失时直接放行；`compile-nodes` 在 PR 上不阻塞；cr.yml 把公开仓库的 diff 发给第三方。
8. 发布链路的标准文档自己写着「未编译、未运行、未按 §6 验证」（`docs/design/release-packaging-standard-20260914.md:4`）。

### 1.10 对分区稿与盘点的更正（编辑复核）

| # | 原说法 | 核对结果 |
|---|---|---|
| 1 | 盘点稿：MySQL 镜像在 `docker-compose.yml:391` | 实际在 `:390`，`:391` 是 `container_name` |
| 2 | 盘点稿：「12 个模块配了 spring-boot-maven-plugin，含 xm-robot」 | HEAD 上是 12 个（11 个进程 + xm-robot）。工作区里多了未入库的 `xm-battle`（6.2），一共 13 个。所以镜像清单不能写死，必须用漂移守卫（§4.5） |
| 3 | 盘点稿：Buildpacks（`spring-boot:build-image`）可以算 Spring Boot 内置 | goal 确实是 Boot 自带的，但构建逻辑和运行时 JRE 都来自 Paketo builder（buildpacks/pack 3.0K、paketo-buildpacks/java 151），不满足 AGENTS §2 |
| 4 | 盘点稿与 `java-infra.md:331` 第 ③ 条：表数据不要烤进镜像 | 那条把「表数据」和「秘密」混在了一起。表数据是随提交走的契约，烤进镜像（单独一层，约 82 KB）。秘密永远不进镜像（§3.7） |
| 5 | 设计稿：healthcheck 用 `</dev/tcp/127.0.0.1/<Dubbo 端口>` 探活 | 只要 `xm.advertise-host` 是非回环的主机名，Dubbo 就**只绑该地址**，127.0.0.1 上没有监听（§2.6）。改为按服务名探活，并且只给真正需要的进程设通告地址 |
| 6 | 设计稿：xm-team 只依赖 mysql / redis 就绪 | `xm-team application.yaml:19` 的 URL 不带 `createDatabaseIfNotExist`，并且 Druid 有 `initial-size: 2`，冷库上会因为库不存在而起不来。对策：在 infra.yaml 里设 `MYSQL_DATABASE: xm_java` 预建库（§2.4） |
| 7 | 设计稿：action 用 `@v5` | 已有更新的 major：checkout v7.0.1、setup-java v6.0.1、upload-artifact v7.0.1，都是 node24。按 commit SHA 钉死（§4.2） |
| 8 | 设计稿：漂移 workflow 判红，排除 SOURCE.properties 那一行后比较 | 改为**只报告**（§10 Q5）。判定方法改用 ContractSync 自带的 `--commit <已记录的 sha>`，不用解析输出（§4.6） |
| 9 | 设计稿：`concurrency` 按 ref 分组并取消进行中的运行 | main 上每个提交都要有结论，push 改为按 sha 分组（§4.2） |
| 10 | 设计稿：`ContractSync.java:686-688` | 实际是 `:685-688`（`listFiles` 在目录不存在时抛错） |
| 11 | 设计稿：「基线吃过类似的亏，见 `login-path-tests.yml:88-89`」 | 那里说的是 go test 缓存导致的假通过，与「IT 静默跳过」是同一类问题，但不是同一件事 |
| 12 | 设计稿：OCI revision 写 40 位、build-info 写 12 位 | 统一用 40 位；只有 tag 用 12 位（§5） |
| 13 | 盘点稿：在 7.1 加一个手动触发的 workflow 推 GHCR | 推迟到 7.6（§10 Q1） |
| 14 | 盘点稿：Nacos、kafka-ui 用 profile 设为可选 | 不移植（§2.3） |
| 15 | `java-infra.md:350`：快照轨是 `g<sha12>` | `g<sha12>` 是**制品目录名**；**镜像 tag** 是不带 g 的 `<sha12>`（§1.5） |
| 16 | `java-infra.md:315`、`tools.md:392`、`tools.md:598`：「tech-stack 写集成测试用仓库自带的 docker compose」 | 这是旧说法。`tech-stack.md:27` 现在写的是「连本机进程，按 `-Dxm.it.*` 开启」，Testcontainers（8.7K）已经落选。本批让 CI 的集成测试用 compose 起依赖，tech-stack 要补一行（§7.3） |
| 17 | `java-infra.md:327`：只有 5 个进程模块 | 现在是 11 个，6.2 落地后是 12 个 |
| 18 | `tools.md:392` 提议用 `deploy/local/compose.yaml` | 本稿改用 `deploy/compose/{infra,stack}.yaml` |
| 19 | `README.md:38` 写「Kafka（后续批次）」；`:43` 的前置条件里没有 Kafka | 过时。Kafka 早已用于资产审计（`start-slice.sh:10` 已经把它列为前置条件） |
| 20 | `xm-gateway application.yaml:36-38` 的注释说管理端点在 18081 | 与 `:41-43` 的实际配置（18105）不符，属于过时注释。顺手修 |

---

## 2 本地编排（compose）

### 2.1 Java 进程、端口与就绪判据

端口的出处：architecture.md §2 / §11、`tools/local/start-slice.sh:83-103`、各 `application.yaml`。

| 进程 | 业务端口 | 管理端口 | 整栈冒烟时发布到宿主 127.0.0.1 的端口 | 就绪判据（除 readiness 之外） |
|---|---|---|---|---|
| xm-scene-manager | Dubbo 20882 | 18102 | — | `xm-scene-manager:20882` 能连上 |
| xm-login | Dubbo 20881 | 18101 | — | `xm-login:20881` 能连上 |
| xm-friend / chat / team / guild / trade | Dubbo 20883 / 20884 / 20885 / 20886 / 20887 | 18107 / 18108 / 18109 / 18110 / 18111 | 18111（robot 播种） | 各自的 Dubbo 端口能按服务名连上 |
| xm-data | — | 18106（Web + `/admin/**`） | 18106（robot 的运维接口） | — |
| xm-scene | 链路 21000、资产通道 21100 | 18104 | 18104（robot guard 读指标） | `xm-scene:21000`、`xm-scene:21100` 都能连上，且 `xm_scene_channels{state="active"} ≥ 1`（等价于 `start-slice.sh:134-154`） |
| xm-gate | 客户端 11000 | 18103 | 11000 | `xm-gate:11000` 能连上 |
| xm-gateway | HTTP 18081（`/api`） | 18105 | 18081 | `xm-gateway:18081` 能连上 |
| xm-battle（6.2，未入库） | 直连 12000、控制面 Dubbo 21200 | 18112 | 12000、18112 | 以 6.2 落地时的版本为准 |

### 2.2 进容器之前必须能覆盖的地址

- **JDBC host 写死在 URL 串里，共 8 处**：xm-login `:21`、xm-friend `:19`、xm-team `:19`、xm-guild `:31`、xm-trade `:23`、xm-gateway `:22`、xm-data `:15`、xm-scene `:27`。
  各进程的 URL 参数各不相同：guild / trade / friend 带 `sessionVariables`，data 带 `socketTimeout=30000` 和 `useAffectedRows`，team 没有 `createDatabaseIfNotExist`。
  所以**不能**照基线那样用 `SPRING_DATASOURCE_URL` 整串覆盖（`docker-compose.login-stack.yml:124`），那样每个容器的参数会各自分叉。只把 host 和 port 换成占位符。
- **Redis 地址**：11 个 yaml 都写死 `redis://127.0.0.1:6379`（如 xm-scene `:76`、xm-gate `:48`），没有占位符；xm-battle 同样如此。
- **Dubbo 直连 URL**：
  - xm-gate 有 6 个（`:51-60`）；
  - xm-gateway 的 `login-url` 在 `:65`；
  - xm-login 的 `scene-manager-url` 在 `:79`；
  - xm-scene 已经有 `XM_SCENE_MANAGER_URL` 占位符（`:109`）。
- **通告地址**：`xm.advertise-host: 127.0.0.1` 出现在 9 个进程里，另有 xm-battle。它在不同进程里的作用不同，见 §2.6。
  scene 的 `link-bind-host: 127.0.0.1`（`:81`）在容器里必须改成 0.0.0.0。
- **管理端口**：缺省只绑 127.0.0.1（architecture.md:766-771）。
  - 容器内的 healthcheck 走 127.0.0.1，不受影响。
  - 宿主要访问时，在容器里设 `XM_MANAGEMENT_ADDRESS=0.0.0.0`，宿主侧只发布到 127.0.0.1。
  - GM 停机接口只接受本机请求。经端口映射进来的请求，源地址是 bridge 网关，会被拒绝。这是期望的行为。
- **表目录**：`xm.table-dir: config-data/tables` 是相对路径。镜像的 `WORKDIR /app` 下有 `/app/config-data/tables`，**不用改配置**。

### 2.3 决策

1. **两个文件**：
   - `infra.yaml` 只放依赖，缺省端口等于各进程 `application.yaml` 里的缺省值，都发布到 127.0.0.1。起好以后 `start-slice.sh` 一行都不用改；CI 的集成测试 job 也用它。
   - `stack.yaml` 先 `include: [infra.yaml]`，再加全部 Java 进程；profile `two-scenes` 下另有 `xm-scene-2`，给 5.2 的跨节点换图用。这是让镜像真正跑起来的唯一途径，也是「单机演示」这种部署形态。
2. **不移植的**：
   - etcd：Java 不用。ContractSync 自己都排除了 `proto/etcd`（`tools/ContractSync.java:59-60`）。
   - Nacos：compose 走 local profile 的 Dubbo 直连。scene 的 `scene-manager-url` 本来就只支持直连（xm-scene `:107-109`），所以 nacos profile 在整栈里跑不通。注册中心随 7.6 一起定。
   - Redis Cluster：随 6.4 匹配。
   - kafka-ui、TiDB（roadmap「不移植」）、观测栈（7.6）。
   - **topic-init**：审计 topic 由生产方和消费方在启动时按规格自己建，并核对分区数。scene 用 `CREATE_AND_VERIFY`（`xm-scene/.../AuditPipeline.java:126`），data 用 `OWN`（`xm-data/.../DataNode.java:119`）。
     `xm-audit/.../AuditTopicInitializer.java:15` 写明：broker 开着自动建 topic 时，生产方在核对通过前不能发送。所以 broker **关掉自动建 topic** 就够了。
     Java 只用审计这两个 topic；`__consumer_offsets` 是内部 topic，不受这个开关影响。
3. **项目名固定为 `name: xuanming-java`，不写 `container_name`**。基线的项目名缺省取目录名，并且写死了 `kafka` / `redis` / `mysql` 这些容器名。两版不得共享存储（architecture.md:27），只能靠项目名把卷隔开。
4. **宿主端口可以改**：用 `XM_MYSQL_HOST_PORT`、`XM_REDIS_HOST_PORT`、`XM_KAFKA_HOST_PORT`，避免与开发机上已经在跑的本地进程或基线的 compose 冲突。

### 2.4 `deploy/compose/infra.yaml`（草图）

```yaml
# Java 版依赖：MySQL / Redis / Kafka（KRaft 单节点）。端口只发布到宿主 127.0.0.1，缺省端口 = 各进程 application.yaml 的缺省，
# 起好后 tools/local/start-slice.sh 不用改任何配置。秘密只从环境变量读（${VAR:?} 缺失即拒绝启动）。
# 镜像钉 tag + index digest（2026-10-05 Docker Hub API 查得）。更新：docker buildx imagetools inspect <image:tag> 取 Digest 行，
# 改这里后推送，看 integration.yml 的 it / stack 两个 job 是否绿。
name: xuanming-java
services:
  mysql:
    image: mysql:8.4.11@sha256:80f4933e3835f9dc4d35a28ec500d7986cb4414e6c6821c5461239cb7beb8995
    environment:
      MYSQL_ROOT_PASSWORD: ${XM_MYSQL_PASSWORD:?需要环境变量 XM_MYSQL_PASSWORD}
      # 预建库：xm-team 的 URL 不带 createDatabaseIfNotExist（xm-team application.yaml:19），冷库上先起它会失败
      MYSQL_DATABASE: xm_java
    ports: ["127.0.0.1:${XM_MYSQL_HOST_PORT:-3306}:3306"]
    volumes: [mysql-data:/var/lib/mysql]
    healthcheck:
      # 走 TCP：初始化阶段的临时 mysqld 关着网络（skip-networking），走 socket 的 ping 会提前报健康（基线 :405 走 socket）
      test: ["CMD-SHELL", "mysqladmin ping --protocol=tcp -h127.0.0.1 -uroot -p\"$$MYSQL_ROOT_PASSWORD\" --silent"]
      interval: 3s
      timeout: 3s
      retries: 60
  redis:
    image: redis:8.10.2@sha256:7ef5b5cec96495a04ca7feff88a9492efeab8053fb284d24bdd73344c9245a48
    command: ["redis-server", "--appendonly", "yes"]   # 持久化：xm:node-id-epoch:* 不得回退（architecture.md:585）
    ports: ["127.0.0.1:${XM_REDIS_HOST_PORT:-6379}:6379"]
    volumes: [redis-data:/data]
    healthcheck: {test: ["CMD", "redis-cli", "ping"], interval: 3s, timeout: 3s, retries: 30}
  kafka:
    image: apache/kafka:4.3.1@sha256:ccd1314e47ec76909e01f86308b4dcf2064f19f7c89759234322314b0e319e26
    environment:                       # 设了任一 KAFKA_* 就要给全单节点 KRaft 的必需项（写法同基线 :11-24）
      CLUSTER_ID: <用 kafka-storage.sh random-uuid 生成一次后写死>
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENERS: INTERNAL://:19092,HOST://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,HOST://127.0.0.1:${XM_KAFKA_HOST_PORT:-9092}
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,HOST:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"   # 审计 topic 由进程按规格建（AuditTopicInitializer），不要 topic-init
      KAFKA_HEAP_OPTS: "-Xms256m -Xmx256m"
    ports: ["127.0.0.1:${XM_KAFKA_HOST_PORT:-9092}:9092"]
    # 不挂卷：镜像以非 root 运行，命名卷挂到镜像里不存在的目录时属主是 root（基线为此改成 root 运行，:54）；
    # topic 由进程启动时重建，开发与 CI 可以接受
    healthcheck:
      # 走 INTERNAL 监听：HOST 监听通告的是宿主端口，改了 XM_KAFKA_HOST_PORT 后在容器里连不上
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:19092 >/dev/null 2>&1"]
      interval: 5s
      timeout: 10s
      retries: 30
volumes: {mysql-data: {}, redis-data: {}}
```

宿主上的进程（`start-slice.sh` 和集成测试）连 `127.0.0.1:9092`，容器里的进程连 `kafka:19092`，所以必须配双监听，做法与基线 `:16-18` 相同。

### 2.5 `deploy/compose/stack.yaml`（草图）

```yaml
name: xuanming-java
include: [infra.yaml]

x-java: &java
  restart: "no"                       # 不掩盖崩溃循环：fail-closed 的进程退出就应该看得见
  stop_grace_period: 30s              # ≥ 各进程的 spring.lifecycle.timeout-per-shutdown-phase（login / team / data 20s）
  deploy: {resources: {limits: {memory: 768m}}}   # 设了上限，MaxRAMPercentage 才有意义
x-java-env: &java-env
  XM_RUN_MODE: ${XM_RUN_MODE:-dev}    # 同 start-slice.sh:30；进程自身缺省 prod
  XM_MANAGEMENT_ADDRESS: 0.0.0.0      # 只在容器网卡上；宿主侧只发布到 127.0.0.1
  XM_REDIS_ADDRESS: redis://redis:6379
  XM_MYSQL_HOST: mysql
  XM_KAFKA_BOOTSTRAP_SERVERS: kafka:19092
x-ready: &ready {interval: 3s, timeout: 5s, retries: 60, start_period: 120s}

services:
  xm-login:
    <<: *java
    image: ${XM_IMAGE_NAMESPACE:-xuanming-java}/xm-login:${XM_IMAGE_TAG:-local}
    build: {context: ../.., dockerfile: deploy/docker/Dockerfile, args: {MODULE: xm-login}}
    environment:
      <<: *java-env
      XM_SCENE_MANAGER_URL: tri://xm-scene-manager:20882
      XM_MYSQL_PASSWORD: ${XM_MYSQL_PASSWORD:?需要 XM_MYSQL_PASSWORD}
      XM_DUBBO_SECRET: ${XM_DUBBO_SECRET:?需要 XM_DUBBO_SECRET}
      XM_LOGIN_DEV_PASSWORD: ${XM_LOGIN_DEV_PASSWORD:?需要 XM_LOGIN_DEV_PASSWORD}
    healthcheck:
      <<: *ready
      test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:18101/actuator/health/readiness >/dev/null && bash -c '</dev/tcp/xm-login/20881'"]
    depends_on: {mysql: {condition: service_healthy}, redis: {condition: service_healthy}}

  xm-scene:
    <<: *java
    stop_grace_period: 45s            # timeout-per-shutdown-phase 30s（xm-scene application.yaml:22）+ 余量；缺省 10s 会在停服写回中途 SIGKILL
    deploy: {resources: {limits: {memory: 1g}}}
    image: ${XM_IMAGE_NAMESPACE:-xuanming-java}/xm-scene:${XM_IMAGE_TAG:-local}
    build: {context: ../.., dockerfile: deploy/docker/Dockerfile, args: {MODULE: xm-scene}}
    environment:
      <<: *java-env
      XM_ADVERTISE_HOST: xm-scene               # 节点目录里的链路 / 资产通道地址，gate / guild 按它连
      XM_SCENE_LINK_BIND_HOST: 0.0.0.0          # 链路不加密：只在 compose 网络内可达，不发布到宿主
      XM_SCENE_MANAGER_URL: tri://xm-scene-manager:20882
      XM_MYSQL_PASSWORD: ${XM_MYSQL_PASSWORD:?}
      XM_DUBBO_SECRET: ${XM_DUBBO_SECRET:?}
      XM_NODE_LINK_SECRET: ${XM_NODE_LINK_SECRET:?}
      XM_ASSET_OP_SECRET_GUILD: ${XM_ASSET_OP_SECRET_GUILD:?}
      XM_GM_ADMIN_SECRET: ${XM_GM_ADMIN_SECRET:-}
    ports: ["127.0.0.1:18104:18104"]
    healthcheck:
      <<: *ready
      test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:18104/actuator/health/readiness >/dev/null && bash -c '</dev/tcp/xm-scene/21000 && </dev/tcp/xm-scene/21100' && curl -fsS http://127.0.0.1:18104/actuator/prometheus | awk '/^xm_scene_channels\\{.*state=\"active\"/ {if ($$NF+0 >= 1) ok=1} END {exit !ok}'"]
    depends_on:
      xm-login: {condition: service_healthy}          # player 表由 login 建（scene 的 sql.init 是 never，:36-39）
      xm-scene-manager: {condition: service_healthy}  # 主世界频道计划由 scene-manager 领导者铺
      kafka: {condition: service_healthy}
```

其余服务按同一个模板写。发布的端口一律绑 127.0.0.1，healthcheck 一律是「readiness + 按服务名连业务端口」：

| 服务 | 额外的非秘密环境变量 | 秘密（§6.1） | 发布端口 | depends_on（都是 healthy） |
|---|---|---|---|---|
| xm-scene-manager | — | DUBBO | — | redis |
| xm-friend / guild / trade | — | MYSQL、DUBBO；guild 加 ASSET_OP_SECRET_GUILD；trade 加 ADMIN_TOKEN（可选） | trade 18111 | mysql、redis |
| xm-team | — | MYSQL、DUBBO | — | mysql（xm_java 由 `MYSQL_DATABASE` 预建）、redis |
| xm-chat | — | DUBBO | — | redis |
| xm-data | — | MYSQL、ADMIN_TOKEN（可选） | 18106 | mysql、redis、kafka |
| xm-gate | `XM_ADVERTISE_HOST=${XM_GATE_PUBLIC_HOST:-127.0.0.1}`；`XM_LOGIN_URL` / `XM_FRIEND_URL` / `XM_CHAT_URL` / `XM_TEAM_URL` / `XM_GUILD_URL` / `XM_TRADE_URL`（`tri://<服务名>:<端口>`） | DUBBO、GATE_TOKEN、NODE_LINK、GM（可选） | `${XM_GATE_PUBLISH_IP:-127.0.0.1}:11000:11000` | xm-scene、xm-login、五个社交服务 |
| xm-gateway | `XM_LOGIN_URL`、`XM_GATEWAY_QUEUE_ENABLED=true`、`XM_GATEWAY_RATE_LIMIT_ENABLED=true`（同 `start-slice.sh:68-70`） | MYSQL、DUBBO、GATE_TOKEN | `${XM_GATE_PUBLISH_IP:-127.0.0.1}:18081:18081` | xm-gate、xm-login、mysql |
| xm-scene-2（profile `two-scenes`） | 与 xm-scene 相同，只是 `XM_ADVERTISE_HOST=xm-scene-2`，并复用 xm-scene 的镜像（不写 `build`） | 同 xm-scene | `127.0.0.1:18114:18104` | 同 xm-scene |
| xm-battle（6.2 落地后） | 通告地址见 §10 Q9 | DUBBO、BATTLE_TOKEN、GATE_TOKEN（可选，用于「两把密钥不能相同」的检查）、ADMIN_TOKEN（可选） | 12000、18112 | redis |

### 2.6 通告地址与 Dubbo 的绑定规则

- **Dubbo 只做直连的进程**（login、scene-manager、friend、chat、team、guild、trade）：在 local profile 下，`xm.advertise-host` 只喂给 `dubbo.protocol.host`（如 xm-login `:132`）。
  - 保持缺省值 127.0.0.1 时，Dubbo 把它当作无效的绑定地址，改为监听 0.0.0.0（architecture.md §4.1）。调用方按服务名直连照样能通。
  - 改成服务名（例如 `xm-login`）时，Dubbo 不再认为它无效，会**只绑这个名字解析出的容器 IP**，容器里的 127.0.0.1 上就没有监听了。这一半按 Dubbo 3.3 的绑定规则推断，architecture.md 记录的只是回环那一半，要在 §11.2 V5 用非回环主机名在本机复核。
  - **结论**：compose 不给这些进程设 `XM_ADVERTISE_HOST`。healthcheck 一律按服务名探测，两种绑定方式下都成立。
- **xm-scene**：通告地址写进节点目录，作为链路地址和资产通道地址（`SceneNode.java:393` 把它交给 `SceneAssetRpcServer`），gate 和 guild 按它去连。所以必须设成服务名。资产通道因此只绑容器 IP，链路由 `XM_SCENE_LINK_BIND_HOST=0.0.0.0` 控制。
- **xm-gate**：通告地址只作为下发给客户端的地址（`GateNode.java:254`）；Netty 绑全部网卡（`GateNode.java:214`）。缺省 `127.0.0.1:11000`，宿主上的 robot 经发布端口连进来。
  做远程演示时，改 `XM_GATE_PUBLIC_HOST` 和 `XM_GATE_PUBLISH_IP`，这正是 PARITY 第 50 行「通告地址 ≠ 监听地址」要解决的场景。
- **两个 scene 不需要错开容器内的端口**：它们各有各的容器 IP，节点目录里写的是 `xm-scene-2:21000`。只有发布到宿主的端口要错开。`start-slice.sh:97-103` 那套错端口的约定在容器里用不上。
- **xm-battle（6.2）**：通告地址同时用于票据里给客户端的主机和目录里的 `rpc_host`（xm-battle `application.yaml` 的 `advertise-host` 注释）。
  在端口映射的部署形态下，这两者需要不同的值：客户端要能从宿主访问的地址，`rpc_host` 要容器网络内的服务名。这是 6.2 的接口问题，见 §10 Q9。

### 2.7 用法

```bash
# 有 Docker 的机器，只起依赖（之后照常 tools/local/start-slice.sh）：
cp deploy/compose/env.example deploy/compose/.env   # 填值；.env 不入库
docker compose -f deploy/compose/infra.yaml up -d --wait
# 整栈（镜像要先由 Maven 打出 jar）：
./mvnw -B -DskipTests package
docker compose -f deploy/compose/stack.yaml up -d --build --wait            # 加 --profile two-scenes 起第二个 scene
docker compose -f deploy/compose/stack.yaml down                            # 保留卷
docker compose -f deploy/compose/stack.yaml down -v                         # 连 MySQL / Redis 卷一起清（见 §2.8）
```

### 2.8 卷与重置

- 只有 MySQL 和 Redis 两个命名卷，Kafka 不挂卷。
- **MySQL 和 Redis 必须一起重置**，原因有两条：
  - Redis 里有一部分权威数据和 MySQL 是配套的，比如组队数据只在 Redis（architecture.md §4.16），而帮会、好友的缓存和排行对应 MySQL 里的表。只清一边，两边就对不上了。
  - 进程还在运行时清 Redis，会让 `xm:node-id-epoch:*` 回退，scene 就会重新接受旧 gate 的链路（architecture.md:585-586，`tools.md:444`）。

  所以要么在停机状态下 `down -v` 全部清掉，要么一个都不清。**禁止单独删 redis-data。**
- Kafka 不挂卷，`down` 之后在途的审计消息就丢了。开发和 CI 可以接受这一点，因为已经落进 MySQL 的流水不受影响。

---

## 3 镜像构建

### 3.1 选型（AGENTS §2；star 为 2026-10-05 实测）

| 用途 | 选用 | star | 落选（star） | 说明 |
|---|---|---|---|---|
| 镜像构建 | Dockerfile（Docker / BuildKit，moby 72.1K）+ Spring Boot 自带的 `jarmode=tools` 分层解包 | 72.1K / Boot 自带 | Jib（14.4K）；Buildpacks（pack 3.0K、paketo-buildpacks/java 151，见 §1.10 #3） | JVM 参数、运行用户、层的顺序都由自己控制，与基线约定一致（uid 10001、OCI label、BUILD_INFO）。Buildpacks 每次要拉约 1 GB 的 builder，还会用它的内存计算器改写 `-Xmx` |
| 编排 | Docker Compose v2（docker/compose 38.3K） | 38.3K | — | `include` 要求 Compose ≥ 2.20 |
| 基础镜像 | Eclipse Temurin 21 JRE（Ubuntu noble） | — | alpine（基线 `Dockerfile.java-svc:54`）、Paketo 缺省的 Liberica | 与本机 JDK 是同一发行版的同一个构建；glibc；自带 curl 和 bash |
| 依赖镜像 | `mysql:8.4.11`、`redis:8.10.2`、`apache/kafka:4.3.1`，都钉 digest | redis 76.6K、kafka 33.9K、mysql-server 12.4K | — | 存储选型早已定下，这里只定版本，与开发机上的版本相同 |
| Dockerfile / workflow 静态检查 | 不引入 | — | hadolint（12.5K）、actionlint（4.3K） | 都不到 2 万 star。workflow 语法由 GitHub 在推送时校验 |

### 3.2 构建流程

1. 在宿主（CI）上执行 `./mvnw -B -ntp -DskipTests package -Dxm.build.commit=… -Dxm.build.contract=…`，一次打出全部 Boot jar。
2. 执行 `docker compose -f deploy/compose/stack.yaml build --build-arg …`。BuildKit 会并行构建，每个镜像里只有 COPY 和一次解包。

不照搬基线「在容器里跑 mvn」（`Dockerfile.java-svc:25-51`），理由有三：

- 多模块 reactor 要构建 11 到 12 次；
- 容器里没有 `~/.m2` 缓存，而基线的发布标准禁用 `RUN --mount=type=cache`（`Dockerfile.go-svc:25-31`）；
- 镜像只会在 CI 里构建，而 CI 本来就装好了 JDK，也有 Maven 缓存。

可追溯性靠 CI 每次都是全新检出来保证（§4），不靠容器内构建。

### 3.3 `deploy/docker/Dockerfile`（草图）

```dockerfile
# 一个 Dockerfile 服务全部 Spring Boot 进程模块：docker build --build-arg MODULE=xm-gate -f deploy/docker/Dockerfile .
# 构建上下文 = 仓库根；.dockerignore 只放行 */target/*.jar 与 config-data/tables。jar 先由宿主 Maven 打出（-DskipTests package）。
# 基础镜像钉 index digest（2026-10-05 Docker Hub API 查得，与本机 JDK 21.0.12.1+1 同一构建）。
# 更新方法：docker buildx imagetools inspect <image:tag> 取 Digest 行替换 @sha256，推送后看 integration.yml / stack 是否绿。
# 不用 `# syntax=`、不用 RUN --mount（同基线 Dockerfile.go-svc:25-31）。
ARG JRE_IMAGE=eclipse-temurin:21.0.12.1_1-jre-noble@sha256:7fd597bf48c8bb13a7a3fb227f8366dec0423f78775d4b4f7e30409204af8627

FROM ${JRE_IMAGE} AS layers
ARG MODULE
WORKDIR /x
# 只会匹配一个文件（repackage 把原始包改名为 .jar.original）；匹配到多个时 COPY 直接报错，不会悄悄选错
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination out

FROM ${JRE_IMAGE}
# 非 root（uid / gid 10001，同基线各镜像）。home 必须可写：Dubbo 3 往 ${user.home}/.dubbo 写文件缓存
RUN groupadd --system --gid 10001 xm \
 && useradd --system --uid 10001 --gid xm --create-home --home-dir /home/xm --shell /usr/sbin/nologin xm
WORKDIR /app
# 层顺序 = 变化频率从低到高：三方依赖 → loader → 表数据 → 本仓库各库模块（SNAPSHOT）→ 进程模块自身
COPY --from=layers /x/out/dependencies/ ./
COPY --from=layers /x/out/spring-boot-loader/ ./
COPY config-data/tables/ ./config-data/tables/
COPY --from=layers /x/out/snapshot-dependencies/ ./
COPY --from=layers /x/out/application/ ./
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
# 版本戳 ARG 放在运行阶段末尾，只让下面几层随提交失效（同基线 Dockerfile.java-svc:71-76）
ARG MODULE
ARG XM_BUILD_VERSION=unknown
ARG XM_BUILD_COMMIT=unknown
ARG XM_BUILD_CONTRACT=unknown
ARG XM_BUILD_CREATED=unknown
LABEL org.opencontainers.image.title="${MODULE}" \
      org.opencontainers.image.version="${XM_BUILD_VERSION}" \
      org.opencontainers.image.revision="${XM_BUILD_COMMIT}" \
      org.opencontainers.image.created="${XM_BUILD_CREATED}" \
      org.opencontainers.image.source="https://github.com/luyuan-java/xuanming-server-mmo" \
      com.game.contract.mmorpg-commit="${XM_BUILD_CONTRACT}"
RUN printf 'module=%s\nversion=%s\ncommit=%s\ncontract=%s\ncreated=%s\n' \
      "${MODULE}" "${XM_BUILD_VERSION}" "${XM_BUILD_COMMIT}" "${XM_BUILD_CONTRACT}" "${XM_BUILD_CREATED}" > /app/BUILD_INFO
USER 10001:10001
# exec 形式：java 是 PID 1，直接收 SIGTERM 走 Spring 优雅停机；不写 HEALTHCHECK（编排层负责）
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- 解包按 Spring Boot 3.3 起参考文档 *Efficient Container Images* 的写法。`jarmode=tools` 取代了已弃用的 `layertools`；不加 `--launcher`，解出来的是一个 `app.jar`，加上 Class-Path 指向的 `lib/`。
- 解包之后，`META-INF/build-info.properties` 位于 `app.jar` 的 classpath 根，`/actuator/info` 照样能读到。

### 3.4 `.dockerignore`（放在仓库根，白名单写法）

```
*
!xm-*/target/xm-*.jar
!config-data/tables
```

- 库模块的 jar 也会进构建上下文，但不会被 COPY，合计只有几 MB，可以接受。
- `.jar.original` 不以 `.jar` 结尾，不会被带进去。
- 写错时，构建会直接报 `failed to compute cache key: "/<路径>": not found`，不会悄悄漏掉文件（基线 `.dockerignore` 文件头记录过同样的现象），首轮 stack 构建就能发现。

### 3.5 运行用户、可写目录、停机信号

- `/app` 归 root 所有，运行用户只读（同基线 `Dockerfile.go-svc:146-162` 的属主划分）。
- 可写的只有两处：
  - `/tmp`：Tomcat 工作目录、Netty native 库的解压位置、hsperfdata；
  - `/home/xm`：Dubbo 文件缓存。基线把 home 设成 `/app`（`Dockerfile.java-svc:63`），在这里不能照抄。
- 停机：java 就是 PID 1，不需要 tini。compose 的 `stop_grace_period` 见 §2.5。scene 必须设 45 s，这一点由 §4.5 的停机断言来验证。

### 3.6 JVM 参数（镜像缺省值；compose 或 K8s 用 `JDK_JAVA_OPTIONS` 整串替换）

- **用 `JDK_JAVA_OPTIONS`，不用 `JAVA_TOOL_OPTIONS`**：前者只作用于 `java` 启动器，不会污染 jcmd 之类的工具；exec 形式的入口也不需要 shell 来展开变量。基线是写死 `JAVA_TOOL_OPTIONS=-Xms64m -Xmx384m`（`deploy/k8s/manifests/java-svc/gateway.yaml:57-58`）。
- **`-XX:MaxRAMPercentage=60`**：堆大小随容器 limit 伸缩。剩下的 40% 留给 Netty 直接内存（gate、scene、Dubbo Triple 都跑在 Netty 上）、metaspace 和线程栈。生产上怎么配，由 7.6 结合压测数据定。
- **`-XX:+UseG1GC` 要显式写**：容器 limit 小于 1792 MB 或者少于 2 个 CPU 时，JVM 认为自己不在 server-class 机器上，会选 SerialGC，扛不住 scene 每帧 50 ms 的预算。
- **`-XX:+ExitOnOutOfMemoryError`**：OOM 之后直接退出，不带病继续跑。scene 丢的是最近一次在线存盘之后的增量，这与 kill -9 的代价相同，architecture.md:672 已经接受了这个代价。
- **不加的参数**：
  - HeapDump：`/tmp` 是临时目录，GB 级的 dump 会把磁盘写满；
  - CDS / AOT：训练运行时要连 Redis / MySQL，7.1 不值得做；
  - `TZ`：游戏日按固定的 +8 计算（`xm-common/.../GameDay.java:28`），日志时间用 UTC。

### 3.7 表数据进镜像

表数据**烤进镜像**，单独成一层（`config-data/tables` 一共 36 个文件，约 82 KB，文件名全部小写，Linux 上不存在基线 Go 镜像遇到的大小写问题）。理由：

- 表数据是随提交走的契约，「镜像 = 代码 + 同一个提交的表」，才能追溯；
- 基线 Go 镜像也是这样做的（`Dockerfile.go-svc:51-57`）；
- 运行时照样校验 manifest 的 sha256；
- 需要临时换表时，仍可以挂一个卷，再用 `XM_TABLE_DIR` 指过去（7.6 决定）。

**秘密永远不进镜像。**

### 3.8 镜像名与 tag（留给 7.6 的接口）

- **镜像名**：`${XM_IMAGE_NAMESPACE:-xuanming-java}/<模块名>`，例如 `xuanming-java/xm-gate`。与基线的 `mmorpg-*` 明显区分，两版即使推到同一个 registry 也不会混。7.6 推 GHCR 时把 namespace 改成 `ghcr.io/luyuan-java`；`org.opencontainers.image.source` 这个 label 能让 GHCR 把包关联回本仓库。
- **tag**：
  - 快照轨：12 位 sha，脏树加 `-dirty`。CI 每次全新检出，永远不脏。
  - 发布轨：`v<X.Y.Z>-<sha12>`，脏树直接拒绝，长度不超过 128，归 7.6。
  - compose 在本机的缺省 tag 是 `local`，只在本机用，永远不推送。
  - **任何时候都不打 `latest`**，也不打基线黑名单里的其他可变 tag（`release_common.ps1:190-193`）。
- **7.1 不写 `tools/Images.java`**：快照 tag 在 workflow 里算（三行 shell）。发布轨的完整规则（拒绝脏树、可变 tag 黑名单、digest 清单）由 7.6 的 `tools/Release.java` 实现（§10 Q8）。

### 3.9 7.1 不做（归 7.6）

推送到 registry、发布轨 tag、只读根文件系统、多架构、镜像签名与扫描、CHANGELOG、preflight、robot 镜像（§10 Q6）。

---

## 4 CI 门禁（GitHub Actions）

### 4.1 布局与触发

| workflow / job | 触发 | 内容 | 判定 | 批次 |
|---|---|---|---|---|
| `ci.yml` / build | push main、PR、手动；`paths-ignore: docs/**, **/*.md` | `./mvnw -B -ntp verify` + `TestReport` | 红绿 | 7.1a |
| `ci.yml` / contract | 同上 | 稀疏检出 mmorpg 在 SOURCE 里记录的那个提交，再跑 `ContractSync --check` | 红绿 | 7.1a |
| `integration.yml` / it | push main、每日 UTC 19:00（北京时间 03:00）、手动 | 用 infra compose 起依赖，`verify -Dxm.it.*`，再跑 `TestReport --require-it-executed` | 红绿 | 7.1a |
| `integration.yml` / stack | 同上 | package → 模块漂移守卫 → compose build → 镜像断言 → `up --wait` → info 断言 → robot → 停机断言 → `down -v` | 红绿；robot 场景分期加入 | 7.1b |
| `contract-drift.yml` / drift | 每日、手动 | 对比 mmorpg main HEAD | **只报告**（warning + Summary，job 始终绿） | 7.1a |

测试读的文件里没有 `docs/` 下的内容（核对过：测试只读 `../config-data/tables`），所以纯文档提交跳过 CI 是安全的。

### 4.2 共同约定

- `runs-on: ubuntu-24.04` 写死版本，不用 `ubuntu-latest`。
- 权限：`permissions: contents: read`。不需要 `checks: write`，因为 workflow 命令产生的 annotation 不需要这个权限。
- 每个 job 都设 `timeout-minutes`；checkout 一律加 `persist-credentials: false`。
- **concurrency**：`group: <workflow>-${{ github.event_name == 'pull_request' && github.ref || github.sha }}`，`cancel-in-progress: true`。
  push 按提交分组，main 上每个提交都会跑完、都有结论；PR 按分支分组，新提交会取消旧的运行。
  这与基线按 ref 取消（`login-path-tests.yml:38-41`）不同，原因是本仓库每完成一个功能就直接推 main，需要逐个提交的运行证据（AGENTS §4、§5）。
- **action**：只用 GitHub 官方的 `actions/checkout`、`actions/setup-java`、`actions/upload-artifact`，并按 commit SHA 钉死，行尾注释写版本号（文件头实测第 6 条）。
  docker 直接用 runner 预装的 CLI，不引入 `docker/*` 或其他第三方 action。
- **用到 Docker 的 job，第一步先做环境自检**：`docker version`、`docker compose version`（要求 ≥ 2.20）、`nproc`、`free -m`、`df -h /`，仿照基线 `release.yml:83-95`。
- **失败必须变成 annotation**：job 日志匿名返回 403。凡是会失败的步骤，都先输出 `::error file=…,line=…,title=…::原因` 再退出。GitHub 对每个 step 能产生的 error annotation 数量有上限（约 10 条），所以要汇总以后再输出。
- **Maven 问题匹配器**：`.github/maven-problem-matcher.json` 用 `::add-matcher::` 注册（这是 Actions 内置功能）。它把以下两类输出转成 annotation：
  - `[ERROR] <file>.java:[行,列] <消息>`（编译错误）；
  - `[ERROR] Failed to execute goal …`。

  这样编译失败时，匿名也能读到原因。

### 4.3 `ci.yml`（草图）

```yaml
name: CI
on:
  push: {branches: [main], paths-ignore: ['docs/**', '**/*.md']}
  pull_request: {branches: [main]}
  workflow_dispatch:
permissions: {contents: read}
concurrency:
  group: ci-${{ github.event_name == 'pull_request' && github.ref || github.sha }}
  cancel-in-progress: true
jobs:
  build:
    name: 构建与单测
    runs-on: ubuntu-24.04
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with: {persist-credentials: false}
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with: {distribution: temurin, java-version: '21', cache: maven}
      - run: echo "::add-matcher::.github/maven-problem-matcher.json"
      # 全新检出不需要 clean；verify 不往 ~/.m2 装本仓库 SNAPSHOT，maven 缓存不被污染
      - run: ./mvnw -B -ntp verify
      - if: always()
        run: java tools/TestReport.java --summary "$GITHUB_STEP_SUMMARY" --annotate
      - if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with: {name: surefire-reports, path: '**/target/surefire-reports/', retention-days: 14}
  contract:
    name: 契约一致性（ContractSync --check）
    runs-on: ubuntu-24.04
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with: {persist-credentials: false}
      - id: src
        run: |
          c=$(sed -n 's/^mmorpg\.commit=//p' contract/SOURCE.properties)
          [[ "$c" =~ ^[0-9a-f]{40}$ ]] || { echo "::error file=contract/SOURCE.properties::mmorpg.commit 不是 40 位 sha：$c"; exit 1; }
          echo "commit=$c" >> "$GITHUB_OUTPUT"
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1  （mmorpg 公开，不需要令牌）
        with:
          repository: luyuan-cpp/xuanming-server-mmo
          ref: ${{ steps.src.outputs.commit }}
          path: .mmorpg
          fetch-depth: 1
          filter: blob:none
          persist-credentials: false
          sparse-checkout: |
            proto
            data/schema
            generated/code/proto
            generated/tables
            cpp/generated/table/code/constants
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with: {distribution: temurin, java-version: '21'}
      - run: |
          java tools/ContractSync.java --mmorpg .mmorpg --check > contract.out 2>&1 || {
            echo "::error file=contract/SOURCE.properties::$(head -n 8 contract.out | sed ':a;N;$!ba;s/\n/%0A/g')"; exit 1; }
          cat contract.out
```

- 稀疏检出的目录**正好是 ContractSync 会读的那 5 个**（`tools/ContractSync.java:128-139`；`:449` 读的 `tip` / `operator` 子目录也在 `generated/code/proto` 下面）。
  将来工具多读了别的目录，这一步会因为「目录不存在」直接抛错（`:685-688`），不会悄悄通过。
- `.mmorpg` 位于工作区内（checkout 只能检出到工作区下面），不在 `--check` 的受管路径里，不影响比对。本机也把 `.mmorpg/` 加进 `.gitignore`。
- mmorpg 的 `.gitattributes` 规定文本一律用 LF，也没有 LFS。Linux 上检出的字节与 Windows 上检出、经 ContractSync 归一化之后的结果一致。首轮如果出现「不同」，先排查这一点。

### 4.4 `integration.yml` / it（7.1a）

1. 环境自检。
2. 生成本次运行的秘密：`v=$(openssl rand -hex 32); echo "::add-mask::$v"; echo "XM_MYSQL_PASSWORD=$v" >> "$GITHUB_ENV"`。
3. compose 静态检查：
   - `docker compose -f deploy/compose/infra.yaml config -q` 必须成功；
   - **反例**：`env -u XM_MYSQL_PASSWORD docker compose -f deploy/compose/infra.yaml config -q` 必须**失败**，否则输出 `::error` 并判红，以此证明 `:?` 生效。
4. `docker compose -f deploy/compose/infra.yaml up -d --wait --wait-timeout 180`。失败时，把 `docker compose ps` 的状态和不健康服务日志的最后 30 行编码进 `::error`。
5. `./mvnw -B -ntp verify -Dxm.it.redis=redis://127.0.0.1:6379 -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306 -Dxm.it.kafka=127.0.0.1:9092`。
   - 测试从环境变量读 `XM_MYSQL_PASSWORD`，`xm.it.mysql.user` 缺省是 root。MySQL 集成测试各自建临时库，root 有这个权限。
   - xm-guild / xm-scene 等模块需要的测试用密钥，由 surefire 注入（如 `xm-api/pom.xml:58-60`、`xm-guild/pom.xml:110-113`），不需要另给。
   - 不加 `-T`：各模块的集成测试共用 Redis 的 DB 13 和同一个 broker，必须串行跑。
6. `java tools/TestReport.java --summary … --annotate --require-it-executed`。49 个带 `@EnabledIfSystemProperty(named = "xm.it.*")` 的类（按注解数是 redis 32、mysql 19、kafka 1）里，只要有一个整类都被跳过，就判红。这样能防止「标志传错、集成测试全部静默跳过、结果却是绿的」。
7. 失败时上传 `docker compose logs --no-color --timestamps` 的输出和 surefire 报告；无论成败（`always()`）都执行 `down -v`。

**为什么不用 `services:` 服务容器**：

- 本机没有 Docker，compose 文件除了 CI 不会在任何地方执行，让集成测试用它，等于每天自测一遍这个文件；
- 依赖版本、关闭自动建 topic、Kafka 双监听这些配置只写一处；
- 服务容器的定义会是第二份，基线 compose 与 K8s 版本漂移（§1.9 #1）就是这样产生的。

### 4.5 `integration.yml` / stack（7.1b）

1. **环境自检；生成秘密**：§6.1 里的全部变量都随机生成并 mask，`XM_BATTLE_TOKEN_SECRET` 和 `XM_GATE_TOKEN_SECRET` 各自独立生成。
2. **生成构建戳**：
   - `XM_IMAGE_TAG=${GITHUB_SHA::12}`
   - `XM_BUILD_COMMIT=$GITHUB_SHA`
   - `XM_BUILD_CONTRACT` 取自 `SOURCE.properties`
   - `XM_BUILD_CREATED=$(git log -1 --format=%cI)`：用提交时间而不是墙钟时间，同一个提交两次构建得到相同的 label
   - `XM_BUILD_VERSION=0.1.0-SNAPSHOT`：取 `project.version`
3. **打包**：`./mvnw -B -ntp -DskipTests package -Dxm.build.commit=$GITHUB_SHA -Dxm.build.contract=$XM_BUILD_CONTRACT`。
4. **模块清单漂移守卫**：把 pom 里声明了 `<goal>repackage</goal>` 的模块去掉 xm-robot，得到集合 A；把 `docker compose -f deploy/compose/stack.yaml config --services` 的结果去掉 mysql / redis / kafka，得到集合 B（未激活 profile 的服务本来就不会列出）。A 必须等于 B。
   6.2 新增 xm-battle 时，如果漏写 compose，这一步就判红。这类漂移基线也出过，见 `cpp-build-ci.yml:18-21`。
5. **构建镜像**：`docker compose -f deploy/compose/stack.yaml build --build-arg XM_BUILD_VERSION=… --build-arg XM_BUILD_COMMIT=… --build-arg XM_BUILD_CONTRACT=… --build-arg XM_BUILD_CREATED=…`。
6. **镜像断言**，每个镜像都要满足：
   - `docker image inspect` 读出的 `org.opencontainers.image.revision` 等于 `$GITHUB_SHA`；
   - `.Config.User` 等于 `10001:10001`；
   - `docker run --rm --entrypoint cat <img> /app/BUILD_INFO` 输出的 `commit=` 正确。
7. **启动**：`docker compose -f deploy/compose/stack.yaml up -d --wait --wait-timeout 300`。失败时的处理同 it 第 4 步。
8. **info 断言**：对每个服务执行 `docker compose exec -T <svc> curl -fsS http://127.0.0.1:<管理端口>/actuator/info`，返回里的 `build.commit` 必须等于 `$GITHUB_SHA`，`build.contract` 必须等于 SOURCE 里记录的值。
9. **在 runner 宿主上跑 robot**：`java -jar xm-robot/target/xm-robot-*.jar <场景>`，环境变量里带上 `XM_LOGIN_DEV_PASSWORD`、`XM_ADMIN_TOKEN`、`XM_ASSET_OP_SECRET_GUILD`；robot 缺省的地址正好对上发布出来的端口（`RobotOptions.java:82`、`:98-103`）。每个场景失败时，都把最后 20 行输出编码进 `::error title=robot <场景>`。场景**分三期**加入，前一期连续绿了才加下一期，口径同 `cpp-build-ci.yml:27-34`：
   - **第一期**：smoke、token、movement、audit、trade。覆盖 gateway HTTP → gate TCP → login → scene → Kafka → xm-data 的全部跳点。
   - **第二期**：reconnect、currency、bag、features、skill、pet、friend、chat、team、guild、guild-economy、guard、attribute（约 70 s）。
   - **第三期**：zones、queue、drain、killswitch，最后跑 ratelimit（它会消耗 IP 令牌桶，经端口映射进来的请求都来自同一个 bridge 网关 IP）。5.2 落地后，在 `--profile two-scenes` 下加跑 cross-node。
10. **停机断言**：执行 `docker compose stop xm-scene`，然后用 `docker inspect -f '{{.State.ExitCode}}'` 检查退出码。退出码**不得是 137**（被 SIGKILL）；正常优雅退出应当是 143。这一步证明 45 s 的宽限期够 scene 完成停服写回。
11. **收尾**：失败时上传 compose 日志、robot 输出和 `images.json`（每个镜像的模块 / ref / id / 大小 / revision，几 KB，保留 30 天）；无论成败都执行 `down -v`。

### 4.6 `contract-drift.yml`（只报告）

```bash
recorded=$(sed -n 's/^mmorpg\.commit=//p' contract/SOURCE.properties)
head=$(git -C .mmorpg rev-parse HEAD)          # .mmorpg 是不带 ref 的稀疏检出 = mmorpg main HEAD，目录同 §4.3
if [ "$head" = "$recorded" ]; then echo "mmorpg main 仍是 $head，无漂移" >> "$GITHUB_STEP_SUMMARY"; exit 0; fi
# 用 --commit 冒充已记录的提交：SOURCE.properties 里的 commit 行于是相同，剩下的差异全是契约内容本身的变化
if java tools/ContractSync.java --mmorpg .mmorpg --commit "$recorded" --check > drift.out 2>&1; then
  echo "mmorpg 已前进到 $head，但契约派生物不变，无需同步" >> "$GITHUB_STEP_SUMMARY"
else
  echo "::warning title=契约漂移::mmorpg 已前进到 $head，契约派生物有变化：运行 ContractSync 同步、两边同批提交（AGENTS §1）"
  { echo "### 契约漂移（mmorpg $head）"; echo '```'; cat drift.out; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
exit 0
```

- `--commit` 是工具文档里本来就有的选项（`tools/ContractSync.java:54`、`:89`），这里**不需要改工具，也不需要解析输出**。如果 mmorpg HEAD 的产物让同步本身抛错（例如出现了没有 schema 的新表），同样按漂移报告处理。
- **只报告、不判红**：Java 版有意在批次边界才同步契约，落后一段时间是常态。如果判红，这个 job 会长期红，最终没人看（基线 C++ 门禁的现状，§1.7）。
  warning 级别的 annotation 匿名可读，`::warning` 那一行就够提醒了。
- 注意：公开仓库的定时 workflow 在仓库 60 天没有活动后会被 GitHub 自动停用。本仓库提交频繁，风险很低。

### 4.7 缓存与制品

| 项 | 做法 |
|---|---|
| Maven | `setup-java` 的 `cache: maven`，key 是所有 `pom.xml` 的哈希；protoc 也在 `~/.m2` 里，一起被缓存。用 `verify`，本仓库的 SNAPSHOT 不会进缓存 |
| Maven Wrapper | 发行包不缓存（`distributionType=only-script`，约 9 MB，每次直接从 Maven Central 下载；runner 上不需要本机那套 aliyun 的 `-s`） |
| Docker 层 | 不缓存：Dockerfile 只有 COPY 和解包。接 gha 缓存需要第三方 action，不划算 |
| 制品 | 失败时传 surefire 报告和 compose 日志（14 天）；stack 每次都传 `images.json`（30 天）。**不传 jar，不传镜像**：没有消费者，发布归 7.6。注意：制品要登录才能下载，只有用户看得到 |
| Step Summary | 每个模块的 tests / failures / errors / skipped 数，IT 实际执行的类数，镜像清单。网页上公开可见，但 REST 读不到 |

### 4.8 `tools/TestReport.java`（JDK 21 单文件，本机也能跑）

- **输入**：`*/target/surefire-reports/TEST-*.xml`，用 JDK 自带的 XML 解析器读，不引入任何依赖。
- **`--summary <文件>`**：写出每个模块的 Markdown 汇总表。
- **`--annotate`**：为每个失败或出错的用例输出一行 `::error file=<模块>/src/test/java/<包路径>.java,line=<栈里本类的那一行>,title=<类>.<方法>::<消息首行>`。
  最多输出 9 条，超出部分合并成第 10 条：「另有 N 个失败用例，见 Summary / 制品」。
- **`--require-it-executed`**：扫描 `*/src/test/java/**` 中带 `@EnabledIfSystemProperty(named = "xm.it.` 的类。只要有一个类没有对应的 XML，或者它的用例全被跳过，就列出这些类并以退出码 1 结束。
- **没有任何报告时**（比如编译阶段就失败了）：退出码 0，只在 Summary 里写「无测试报告」。失败原因交给 Maven 问题匹配器去报。
- **退出码**：0 通过；1 有失败，或者有 IT 被静默跳过；2 用法错误。

### 4.9 安全面

- 不使用任何 repository secret。fork 来的 PR 只拿到只读令牌，什么也拿不到；随机秘密只在一次运行里存在。
- **不移植 cr.yml**：它会把 PR diff 发给第三方 LLM 端点（`cr.yml:16-23`）。本仓库是公开的，而且挂在简历上（`java-infra.md:379` 第 ① 条）。
- 只要 workflow 有输入参数，就一律经环境变量进脚本，`ref` 先用正则校验再使用，参照 `release.yml:61-65`。

---

## 5 版本与构建信息

### 5.1 build-info（根 `pom.xml`）

```xml
<!-- properties：CI / 镜像构建用 -D 覆盖；本机构建取缺省（命令行 -D 优先于 pom 属性） -->
<xm.build.commit>unknown</xm.build.commit>
<xm.build.contract>unknown</xm.build.contract>

<!-- pluginManagement 里已有的 spring-boot-maven-plugin（pom.xml:197-201）追加： -->
<executions>
  <execution>
    <id>build-info</id>
    <goals><goal>build-info</goal></goals>
    <configuration>
      <additionalProperties>
        <commit>${xm.build.commit}</commit>
        <contract>${xm.build.contract}</contract>
      </additionalProperties>
    </configuration>
  </execution>
</executions>
```

- 13 个模块（含 xm-robot 和 xm-battle）都声明了这个插件。各自的 `repackage` execution（如 `xm-gate/pom.xml:105-111`）会与这里的 `build-info` 合并，模块的 pom 不用动；库模块没有声明这个插件，不受影响。
- 结果：`META-INF/build-info.properties` 里有 `build.version`（即 `project.version`）、`build.time`、`build.commit`、`build.contract`；`/actuator/info` 出现 `build` 段（由 Spring Boot 的 `ProjectInfoAutoConfiguration` 自动装配）。
- **与基线的区别**：
  1. 不覆盖 `version`。基线靠 3.4.3 写入顺序的那个 hack 覆盖（`java/gateway_node/pom.xml:155-169`），升级 Spring Boot 后可能静默失效。Java 的发布版本号方案由 7.6 定。
  2. 多一个 `contract` 字段。运维能直接看到一个进程用的是哪一版客户端契约，这是双版本特有的需求。
  3. commit 写 40 位。
- **不用 git-commit-id 插件**（1.7K star，不满足 §2）。commit 由 CI 用 `-D` 传进来就够了。

### 5.2 端点

- 11 个 yaml（加上 xm-battle）的 `management.endpoints.web.exposure.include` 从 `health,prometheus` 改为 `health,info,prometheus`，并加 `management.endpoint.health.probes.enabled: true`，得到 `/actuator/health/readiness` 和 `/actuator/health/liveness`。
- `info` 只有 build 段：Boot 3 缺省不开 env / java / os 这些贡献者；而且它在只绑本机的管理端口上。
- architecture.md §11 那句「actuator 只暴露 health 与 prometheus 两个端点」要同步改。

### 5.3 镜像层

- OCI label：`title`（模块名）、`version`、`revision`（40 位）、`created`（提交时间）、`source`（`https://github.com/luyuan-java/xuanming-server-mmo`），另有 `com.game.contract.mmorpg-commit`。
- `/app/BUILD_INFO` 有 5 行：`module=`、`version=`、`commit=`、`contract=`、`created=`。

### 5.4 留给 7.6 的接口

- 7.6 生成制品清单时，应读同一组字段：模块、版本、40 位 commit、契约 commit、提交时间、镜像 ref、digest。
- 发布轨的 `XM_BUILD_VERSION` 改成 `vX.Y.Z`，镜像 tag 改成 `vX.Y.Z-<sha12>`。
- 在根 pom 用 `-Drevision` 或 `versions:set` 设版本号，由 7.6 定。

---

## 6 秘密与配置

### 6.1 秘密矩阵（compose 按进程最小授权）

出处：`start-slice.sh:3-9`、`:22-26`，各个 yaml 文件头的注释，以及 `System.getenv` 和 `@Value("${XM_…}")` 的读取点（例如 `xm-login/.../LoginConfiguration.java:117`、`:144-145`）。

| 变量 | 谁需要 | 缺失时 | compose 写法 |
|---|---|---|---|
| `XM_MYSQL_PASSWORD` | mysql 容器（root 口令）、login、friend、team、guild、trade、gateway、data、scene；集成测试 | 连接被拒，启动失败 | `:?` |
| `XM_DUBBO_SECRET` | scene-manager、login、friend、chat、team、guild、trade、scene、gate、gateway、battle | 拒启 | `:?` |
| `XM_GATE_TOKEN_SECRET` | gate、gateway；battle 可选（用来检查两把密钥不相同） | 拒启 | `:?` |
| `XM_NODE_LINK_SECRET` | gate、scene | 拒启 | `:?` |
| `XM_LOGIN_DEV_PASSWORD` | login（`xm.login.mode` 缺省 dev，见 xm-login `:84`）、robot | 拒启 | `:?` |
| `XM_ASSET_OP_SECRET_GUILD` | guild、scene、robot guild-economy；去掉首尾空白后至少 32 字节 | guild 拒启，scene 验签全部失败 | `:?` |
| `XM_BATTLE_TOKEN_SECRET`（6.2） | battle；至少 32 字节，且不得与 gate 的密钥相同 | 拒启 | `:?` |
| `XM_ADMIN_TOKEN` | data、trade、battle（dev 接口）、robot | `/admin/**` 一律回 503 | `:-`（可选） |
| `XM_GM_ADMIN_SECRET` | gate、scene | GM 停机接口一律拒绝 | `:-`（可选） |

### 6.2 注入方式

- compose 里的秘密都写成 `${VAR:?需要 VAR}` 或 `${VAR:-}`，值来自 shell 环境，或者来自**不入库**的 `deploy/compose/.env`（compose 会自动读项目目录下的 `.env`）。
- `.gitignore` 加上 `deploy/compose/.env`。仓库里只放 `deploy/compose/env.example`，只列变量名和用途，不写值。
- 生成值的方法写在 README 里：`openssl rand -hex 32`，得到 64 个十六进制字符，满足 32 字节的下限。
- 与基线明文写死（§1.8）不同，这是有意差异，对应 AGENTS §3「秘密只从环境变量注入」。
- compose 里的 MySQL 用 root。各进程要用 root 是因为它们需要 CREATE 权限：`createDatabaseIfNotExist`、schema init、pbmysql 建表；集成测试还要建临时库。生产用的最小权限账号由 7.6 定。

### 6.3 CI

每次运行都执行 `v=$(openssl rand -hex 32); echo "::add-mask::$v"; echo "<变量>=$v" >> "$GITHUB_ENV"`，生成的值只在这一次运行里存在，不使用任何 GitHub secret。

### 6.4 配置占位符改动清单（只加占位符，缺省值等于现在的值）

| # | 改什么 | 新值 | 涉及 |
|---|---|---|---|
| 1 | JDBC URL 的 host 和 port | `jdbc:mysql://${XM_MYSQL_HOST:127.0.0.1}:${XM_MYSQL_PORT:3306}/xm_java?<原参数原样保留>` | §2.2 列出的 8 处 |
| 2 | Redis 地址 | `${XM_REDIS_ADDRESS:redis://127.0.0.1:6379}` | 11 个 yaml，加上 xm-battle |
| 3 | 通告地址 | `${XM_ADVERTISE_HOST:127.0.0.1}` | 9 个 yaml，加上 xm-battle（compose 只给 scene / gate / battle 设值，§2.6） |
| 4 | scene 链路绑定 | `link-bind-host: ${XM_SCENE_LINK_BIND_HOST:127.0.0.1}` | xm-scene `:81` |
| 5 | Dubbo 直连 URL | `${XM_LOGIN_URL:tri://127.0.0.1:20881}` 等；login 的 `scene-manager-url` 与 scene 共用 `XM_SCENE_MANAGER_URL` | xm-gate `:51-60`、xm-gateway `:65`、xm-login `:79` |
| 6 | 探针与 info | `management.endpoint.health.probes.enabled: true`；exposure 加上 `info` | 11 个 yaml，加上 xm-battle |

**规则**：compose 里设的每一个非秘密变量，都必须在 `application.yaml` 里有同名的占位符。

- Spring 的宽松绑定虽然也能把 `XM_ADVERTISE_HOST` 绑到 `xm.advertise-host`，但靠的是「横线转下划线」这条兼容规则，读配置的人不容易查到。显式占位符可以 grep，也能在本机做反向验证（§11.2 V5）。
- `xm-login application.yaml:68` 的注释已经写着「用 XM_ADVERTISE_HOST 覆盖」，这正好说明这个变量名早就是事实约定。
- 现有的 `XM_TABLE_DIR` 也是靠宽松绑定生效的，不在本批改动范围内，保持不变。

---

## 7 Java 落地清单

### 7.1 批次 7.1a（不依赖 5.2，可以先做）

| 文件 | 内容 |
|---|---|
| `mvnw` | 文件模式改为 100755。只改 mvnw，不碰 `tools/local/*.sh`，原因见 §8 #2 |
| `.github/workflows/ci.yml` | build + contract（§4.3） |
| `.github/workflows/integration.yml` | 先只有 it job（§4.4） |
| `.github/workflows/contract-drift.yml` | §4.6 |
| `.github/maven-problem-matcher.json` | §4.2 |
| `deploy/compose/infra.yaml`、`deploy/compose/env.example` | §2.4、§6.2 |
| `tools/TestReport.java` | §4.8 |
| `.gitignore` | 加 `.mmorpg/`、`deploy/compose/.env` |

### 7.2 批次 7.1b（5.2 提交之后）

| 文件 | 内容 |
|---|---|
| 根 `pom.xml` | build-info execution 和两个属性（§5.1） |
| 11 个进程的 `application.yaml`（以及 xm-battle，如果 6.2 已经落地） | §6.4 的占位符、探针、info；顺手修 xm-gateway `:36-38` 的过时注释 |
| `deploy/docker/Dockerfile`、`.dockerignore` | §3.3、§3.4 |
| `deploy/compose/stack.yaml` | §2.5 |
| `.github/workflows/integration.yml` | 加 stack job，robot 场景先只放第一期（§4.5） |
| `tools/local/start-slice.sh`、`stop-slice.sh` | 文件模式改为 100755（5.2 已经提交，不会误带改动进来） |

### 7.3 文档改动（两批各改自己那一部分）

- **tech-stack.md**：加「镜像构建 / 编排 / 基础镜像 / 依赖镜像版本 / CI」几行，附 star 数；在「测试」行补一句：「CI 的集成测试用 `deploy/compose/infra.yaml` 起依赖」。
- **architecture.md**：
  - §6 补一个部署 profile「容器」，内容包括通告地址与 Dubbo 的绑定规则、宽限期；
  - §11 把「只暴露 health 与 prometheus」改成「health / info / prometheus + 探针」。
- **AGENTS.md §4**：
  - 补 compose 命令；
  - 补「推送后用匿名 REST 读 CI 结论」（§11.5）；
  - 补「compose / 镜像结论以 CI 为准」的口径；
  - 补「新增进程模块要同时登记 stack.yaml」。

  AGENTS.md 是协作守则，交付说明里要把这几处改动单独列出来，请用户过目。
- **README.md**：
  - `:38` 把「Kafka（后续批次）」去掉；
  - `:43` 的前置条件补上 Kafka，并写明「也可以用 `deploy/compose/infra.yaml` 起依赖」；
  - 补一节「CI」。
- **roadmap.md:91**：标上状态，写 7.1a / 7.1b 的提交号；如果有修正提交，写最后一次变绿的那个提交。
- **盘点**：在 `java-infra.md:315`、`:327`、`:350`，`tools.md:392`、`:598` 加更正注记（§1.10 #15–#18）。盘点是快照，只加注记，不改原文。

### 7.4 PARITY 新增三行

mmorpg 侧早就有这些东西，交付说明里写「mmorpg 已有，本批 Java 对齐」。

1. **本地编排**（`deploy/docker-compose.yml`、`docker-compose.login-stack.yml` ↔ `deploy/compose/{infra,stack}.yaml`）：已对齐（行为有意不同）。有意差异见 §9 D1–D6。
2. **服务镜像与构建信息**（`deploy/k8s/Dockerfile.java-svc` 等 ↔ `deploy/docker/Dockerfile` + build-info）：已对齐（行为有意不同），见 D7–D14。
3. **CI 门禁**（`.github/workflows/*` ↔ 三个 workflow）：已对齐（行为有意不同），见 D15–D17。

### 7.5 提交纪律

- roadmap 规定「一批一个提交」，但 compose、Dockerfile、workflow 只有推上去才能验证。CI 暴露出来的问题用后续提交来修，标题写「批次 7.1a/b 修正：…」。不得用 `@Disabled` 草草绕过平台相关的测试失败。
- 每次推送之后，按 §11.5 读取结论，并把 run id 写进交付说明。

---

## 8 隐患与边界

1. **`mvnw` 在 git 里是 100644**（`git ls-files -s mvnw`），Linux runner 上执行 `./mvnw` 会直接报 Permission denied，CI 第一步就会失败。
   基线有过同样的事：`java/config_node/mvnw` 也是 100644，`exporter-tests.yml:362-363` 改用 `bash ./mvnw` 绕过去；而 `java/gateway_node/mvnw` 是 100755。
   Java 版直接修正文件模式。修完以后如果模式又被弄丢，CI 会立即报红，不会悄悄漏过去。
2. **`git update-index --chmod=+x` 会把工作区的内容一起暂存**。`tools/local/start-slice.sh` 现在有 5.2 未提交的改动，在 7.1a 里给它加可执行位，会把 5.2 的半成品带进 7.1a 的提交。所以 7.1a 只改 `mvnw`，两个 `.sh` 留到 7.1b 再改。
3. **测试第一次在 Linux 上跑**。已经扫过：代码里没有 `systemDefault()`、`Locale.getDefault()`、写死的路径分隔符；表文件名全是小写；测试按 `../config-data/tables` 相对路径读表。即便如此，第一轮仍可能暴露平台差异，要预留修正提交。runner 的时区是 UTC，游戏日固定按 +8 计算，不受影响。
4. **推送 `.github/workflows/**` 需要凭据带 `workflow` 权限**。如果 GitHub 拒绝推送（提示缺 workflow scope），要由用户重新授权；Claude 不经手任何凭据。
5. **Dubbo 的绑定规则**（§2.6）：通告地址一旦设成非回环的主机名，Dubbo 就只绑这一个 IP。所以 healthcheck 一律按服务名探测；Dubbo 只做直连的进程在 compose 里不设通告地址。
6. **停机宽限期**：compose 缺省 10 s，K8s 缺省 30 s，都不够 scene 的 30 s 停机阶段（xm-scene `:22`）用。scene 设 45 s，由 §4.5 第 10 步验证。7.6 的 `terminationGracePeriodSeconds` 同样要调。
7. **Dubbo 文件缓存**：Dubbo 3 往 `${user.home}/.dubbo` 写缓存，所以镜像给了一个可写的 `/home/xm`。首轮冒烟时要在日志里搜一遍有没有权限类的告警。
8. **Kafka 不挂卷**：`down` 之后在途的审计消息就丢了，开发和 CI 可以接受（§2.8）。以后如果要持久化，先解决卷的属主问题。
9. **MySQL 和 Redis 必须一起重置**（§2.8）。
10. **端口冲突**：开发机上已经有本地进程占着 3306 / 6379 / 9092，基线 compose 也是发布到 0.0.0.0 的同一组端口。在这样的机器上同时起 infra，端口会撞，用 `XM_*_HOST_PORT` 改开。
11. **runner 容量**：公开仓库的标准 runner 规格以环境自检的输出为准，预计 4 vCPU、16 GB。整栈有 11 到 12 个 JVM，内存上限合计约 9.5 GB，再加 infra 约 1.5 GB。
    `start_period` 设为 120 s，`--wait-timeout` 设为 300 s。首轮如果超时，先看自检输出，再调整 limit。
12. **ContractSync 读的目录变了**：稀疏检出的目录列表要跟着改，否则 contract job 会因为「目录不存在」报红。这种失败很明显，不会悄悄通过。
13. **digest 钉死以后**：上游重推 tag 不会影响我们，但安全补丁也要手动更新，流程同基线 `Dockerfile.java-svc:17-20`。
    Docker Hub 对匿名拉取有频率限制，遇到 `toomanyrequests` 时先重跑。如果频繁出现，再考虑镜像代理，归 7.6。
14. **失败只能通过 annotation 看到**：job 日志匿名读不到；制品要登录才能下载，只有用户看得到；Step Summary 只能在网页上看。所以每一种失败路径都必须变成 `::error`，并且总数不超过上限（§4.2、§4.8）。
15. **xm-battle 的通告地址一址两用**（§2.6）。不解决的话，6.2 在 compose 里只能二选一：要么客户端连不上，要么控制面连不上。
16. **xm-team 冷启动**：xm-team 的 URL 不带 `createDatabaseIfNotExist`，靠 infra 里的 `MYSQL_DATABASE: xm_java` 兜住。在不带 initdb 的环境（比如 7.6 的托管数据库）里，还得另外保证 login 先起来。
17. **`XM_RUN_MODE=dev`**：stack 缺省用 dev，GM 指令和 trade 播种都是开着的。stack 是开发 / CI 用的东西，端口只发布到 127.0.0.1。要对外演示，必须改用 `XM_RUN_MODE=prod`，并重新评估要发布哪些端口。
18. **通过端口映射进来的客户端 IP 都是 bridge 网关**：gateway 的 IP 限流和排队会把所有宿主请求看成同一个 IP。冒烟时的 ratelimit 场景依赖这一点，所以放在最后跑（§4.5 第 9 步）。生产入口的真实 IP 由 7.6 处理（`xm.gateway.rate-limit.trusted-proxies`）。
19. **并行批次**：5.2 和 6.2 会改变 stack 的形状。漂移守卫只管模块清单；端口、秘密、依赖关系仍然要由新增模块的那一批自己登记（§0.4 的规则）。

---

## 9 建议的有意差异（相对基线）

| # | 差异 | 基线 | Java | 理由 |
|---|---|---|---|---|
| D1 | 依赖范围 | Kafka + topic-init + kafka-ui、Redis + Cluster、MySQL + initdb、Nacos、etcd | 只有 MySQL、Redis、Kafka | Java 不用 etcd 和 Nacos 服务端；集群版 Redis 随 6.4 |
| D2 | 依赖版本 | 多处 `latest` | tag + digest，与开发机版本相同 | 可复现；避免 compose 与 K8s 的版本漂移 |
| D3 | 端口发布 | 0.0.0.0 | 127.0.0.1，可通过变量改 | 开发依赖不应对局域网开放 |
| D4 | 秘密 | 明文写在 compose / yaml 里 | `:?` 必填、`.env` 不入库、CI 随机生成 | AGENTS §3 |
| D5 | Kafka topic | auto-create 打开 + topic-init 抢先预建 | auto-create 关闭，进程按规格自建并核对 | 让「分区契约」由进程自己保证，不靠启动顺序 |
| D6 | 整栈 | 只有 login-stack 里的 gateway 进容器 | 全部 Java 进程都进 compose | 镜像只有跑起来才算验证过；同时也是单机演示的形态 |
| D7 | Dockerfile | 每个服务一个 context，在容器里跑 mvn | 一个参数化 Dockerfile，jar 由 CI 宿主打出 | 多模块 reactor；缓存；镜像只在 CI 构建 |
| D8 | 基础镜像 | temurin 23 JRE alpine | temurin 21 JRE noble（glibc） | LTS，与本机同一构建；Java 版统一用 21 |
| D9 | JVM | `-Xmx384m` 写死 | `MaxRAMPercentage=60` + G1 + ExitOnOOM | 随 limit 伸缩；小容器下也不会退化到 SerialGC |
| D10 | home | `/app` | 可写的 `/home/xm` | Dubbo 文件缓存 |
| D11 | 表数据 | Java 镜像不带表（Go 镜像带） | 单独一层烤进镜像 | 契约随提交走；与 Go 镜像同理 |
| D12 | build-info | 用 hack 覆盖 version；commit 12 位 | 不覆盖 version；多一个 contract；commit 40 位 | 不依赖插件实现细节；双版本需要知道契约来源 |
| D13 | OCI created | 构建时间 | 提交时间 | 同一个提交两次构建得到相同的 label，层缓存可复用 |
| D14 | 镜像名 | `ghcr.io/luyuancpp/mmorpg-*` | `xuanming-java/<模块>`，namespace 可配置 | 两版不混；不依赖旧用户名的重定向 |
| D15 | CI 内容 | 只跑单测；没有 IT；有 cr.yml | 单测 + 真依赖 IT + 整栈冒烟 + 契约 `--check` + 漂移报告；不移植 cr.yml；不用第三方 action；action 钉 SHA；runner 钉 ubuntu-24.04 | 本机没有 Docker，CI 是唯一能执行这些东西的地方；公开仓库不外发 diff |
| D16 | concurrency | 按 ref 取消 | push 按 sha（不取消），PR 按 ref 取消 | 直接推 main 的工作流里，每个提交都需要结论 |
| D17 | 漂移 | — | 每天只报告，不判红 | Java 有意在批次边界同步；常红的门禁会被无视 |
| D18 | robot 镜像 | 有（用于集群内压测） | 不出 | robot 在 runner 宿主上直接跑；压测归 7.4 / 7.6 |

---

## 10 开放问题（各带推荐答案）

| # | 问题 | 推荐答案 | 理由 |
|---|---|---|---|
| Q1 | 7.1 要不要把镜像推到 GHCR | **不推**，留给 7.6 | 现在没有消费者；推送意味着公开镜像，是发布决策；需要 `packages: write`。镜像名和 label 已经为 GHCR 准备好了（§3.8） |
| Q2 | it / stack 每次 push main 都跑，还是只跑定时任务 | **每次 push main 都跑，外加每日定时**；push 按 sha 分组，不取消 | 公开仓库的标准 runner 不计费；每个功能提交都需要运行证据 |
| Q3 | 要不要在本机下载 compose 独立二进制，只用来做 `config` 静态校验 | **不下载** | 需要用户另外批准下载；CI 第一步就是 `config -q`，发现问题后用修正提交解决，代价很小 |
| Q4 | 是否同意拆成 7.1a / 7.1b 两批 | **同意** | 7.1a 不碰 5.2 正在改的 yaml，可以马上做；7.1b 等 5.2 提交以后再做 |
| Q5 | 漂移 workflow 判红还是只报告 | **只报告**（warning + Summary） | 落后于 mmorpg 是常态；常红的门禁会被无视（基线 C++ 门禁的现状） |
| Q6 | robot 要不要出镜像 | **不出** | 冒烟时直接在 runner 宿主上跑 jar；集群内压测归 7.4 / 7.6 |
| Q7 | Nacos 要不要进 compose（用 profile 控制） | **不进** | scene 的跨节点选目标只支持直连（xm-scene `:107-109`），nacos profile 在整栈里跑不通。注册中心随 7.6 的部署形态一起定 |
| Q8 | 要不要写 `tools/Images.java` 来编排构建和打 tag | **7.1 不写** | 快照 tag 三行 shell 就够了；发布轨规则（拒绝脏树、可变 tag 黑名单、digest 清单）由 7.6 的 `tools/Release.java` 统一实现（YAGNI） |
| Q9 | xm-battle 的通告地址同时给客户端和 `rpc_host` 用，怎么拆 | **在 6.2 加 `xm.battle.client-advertise-host`（缺省等于 `xm.advertise-host`）**，票据用它，目录里的 `rpc_host` 继续用 `xm.advertise-host` | 端口映射或 NodePort 部署时这两个地址必然不同。gate 已经有同类的拆分（`advertise-port` 与 `client-port`）。这是 6.2 的接口，要转告 6.2 的负责人 |
| Q10 | Java 版最终上不上 K8s（盘点开放问题 7） | **7.1 不决定** | compose 整栈先作为单机演示形态；7.6 再在 K8s 与「compose + 单机」之间定。7.1 交付的探针、宽限期、环境变量矩阵在两种形态下都能用 |
| Q11 | stack 的 `XM_RUN_MODE` 缺省用 dev 还是 prod | **dev**（与 `start-slice.sh:30` 一致） | robot 的 GM 类场景和 trade 播种都需要 dev；`XM_RUN_MODE=prod docker compose … up` 可以验证生产口径 |
| Q12 | AGENTS.md §4 要不要增加 CI 相关的口径 | **要加**，交付说明里单独列出，请用户过目 | 「推送后读 CI 结论」「compose / 镜像结论以 CI 为准」「新增进程模块要登记 stack.yaml」，都是以后每一批都要遵守的协作规则 |

---

## 11 验证计划

### 11.1 已经做过的取证

- 本稿引用的基线 file:line 都逐条读过原文。§1.10 的 20 条更正都回到代码核对过。
- `ContractSync --check` 对本地的 mmorpg 跑出退出码 0。
- 匿名 REST 的可读范围、基线 CI 的结论、star 数、Docker Hub 的 tag 与 digest、action 的版本和 SHA，见文件头实测第 2–6 条。
- 测试平台相关性的扫描（§8 #3）；Kafka 的使用面只有审计这两个 topic（§2.3）。

### 11.2 本机验证（没有 Docker）

本机的依赖用 `bash D:/work/.tools/with-backends.sh <命令>` 起停。

| # | 批次 | 验什么 | 怎么验 / 判据 |
|---|---|---|---|
| V1 | 7.1a | 可执行位 | `git update-index --chmod=+x mvnw` 之后，`git ls-files -s mvnw` 显示 100755，且 `git diff --cached --stat` 里只有模式变化 |
| V2 | 7.1a | 全量构建不受影响 | `./mvnw -B install` 通过，单测数不少于上一批 |
| V3 | 7.1a | TestReport | 本机 `./mvnw test` 之后运行，汇总数与 Maven 的 `Results` 一致。在 scratch 目录里造一份失败的 XML 和 12 个失败用例，输出的 `::error` 行恰好 10 条。不带 `-Dxm.it.*` 时，`--require-it-executed` 退出码为 1，并列出 49 个类；带上本机 Redis / MySQL / Kafka 时退出码为 0 |
| V4 | 7.1a | 问题匹配器的正则 | 用 `grep -E` 和 Java 的 `Pattern` 各匹配一条造出来的 `[ERROR] /x/A.java:[12,5] msg` 和 `[ERROR] Failed to execute goal …`，抽出的文件、行、消息都正确 |
| V5 | 7.1a | 契约检查与稀疏目录 | 做一份新的 `git clone --filter=blob:none --no-checkout --depth 1`，`git sparse-checkout set` 那 5 个目录，检出 SOURCE 里记录的提交，`--check` 退出码为 0，证明这 5 个目录刚好够用。**反例**：把仓库复制一份到 scratch，改一个受管文件，用 `--repo <scratch>` 跑 `--check`，退出码为 1。漂移模式：对同一个检出加 `--commit <recorded>`，退出码为 0。都不碰真实工作区 |
| V6 | 7.1b | 构建信息 | `./mvnw -B install -Dxm.build.commit=$(git rev-parse HEAD) -Dxm.build.contract=<SOURCE 里的 sha>`；`unzip -p xm-gate/target/xm-gate-*.jar BOOT-INF/classes/META-INF/build-info.properties` 里有 40 位的 `build.commit` 和 `build.contract`。不带 `-D` 时两者都是 `unknown` |
| V7 | 7.1b | 分层解包后能启动（等价于容器里的启动方式） | 对每个 Boot jar 跑 `java -Djarmode=tools -jar <jar> list-layers`，都列出 4 层。挑两个（scene-manager、login）`extract --layers`，把各层合并到一个目录，复制一份 `config-data/tables` 进去，在这个目录下执行 `java -jar app.jar`，`/actuator/health/readiness` 和 `/actuator/info` 都正常 |
| V8 | 7.1b | JVM 参数能被接受 | `JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" java -version` 正常，stderr 里有 `Picked up JDK_JAVA_OPTIONS` |
| V9 | 7.1b | 新占位符（正向） | 把所有新变量设成「意思相同、字面不同」的值（如 `localhost`），跑本机切片，robot smoke / token / movement / audit 都通过 |
| V10 | 7.1b | 新占位符（反向，证明绑定确实生效） | 每类变量各设一个必然失败的值：`XM_REDIS_ADDRESS=redis://127.0.0.1:1` 进程启动失败；`XM_MYSQL_PORT=1` 进程启动失败；gate 设 `XM_LOGIN_URL=tri://127.0.0.1:1` 时 robot smoke 登录得到 1003；`XM_SCENE_LINK_BIND_HOST=0.0.0.0` 时 `netstat` 显示 21000 监听在 0.0.0.0 |
| V11 | 7.1b | Dubbo 绑定规则（§2.6） | 给 scene 设 `XM_ADVERTISE_HOST=<本机局域网 IP 或可解析的主机名>`。`netstat -ano` 应显示 21100 只绑在这个 IP 上（不是 0.0.0.0）；缺省 127.0.0.1 时应显示 0.0.0.0。结论回填 architecture.md §6 |
| V12 | 7.1b | 探针 | 每个进程的 `/actuator/health/readiness`、`/liveness` 都返回 UP |

### 11.3 CI 验收（逐个 job）

| job | 判据 |
|---|---|
| build | 绿；用例总数与本机一致；跳过数等于本机跳过数，也就是全部 IT 类再加上原有的跳过。这是**这套测试第一次在 Linux 上跑**，平台相关的失败在后续提交里修，不能 `@Disabled` |
| contract | 绿；输出「契约一致：mmorpg@26ceb70ca5771c5ab055c2ec7144c1e55f32c528」 |
| it | 绿；`config -q` 的反例确实失败了；`--require-it-executed` 通过；跳过数比 build 少了整整一组 IT 类 |
| drift | 绿；Summary 里写着「无漂移」或者「已前进到 X」 |
| stack | `--wait` 在时限内完成；镜像断言、所有 info 断言、robot 第一期全部通过；停机断言的退出码不是 137；漂移守卫通过 |

### 11.4 反例与守卫的验证方式

- **`:?` 生效**：it job 的第 3 步每次都会跑一遍反例（§4.4）。
- **契约不一致判红**：在本机 scratch 上验（V5）。不为验证专门推送临时分支：推送和删除远端分支会产生噪声，也需要额外授权。
- **IT 被静默跳过时判红**：本机验（V3）。
- **模块漂移守卫**：本机先验证集合 A 的计算（对 `*/pom.xml` 做 `grep -l '<goal>repackage</goal>'`，结果去掉 xm-robot，应当等于 §2.1 的进程清单）。集合 B 的计算只能在 CI 上跑。6.2 落地时这个守卫会被真实地触发一次；如果 6.2 那一批漏登记，它应当判红。这一点在交付说明里写明。
- **停机宽限期**：stack 的第 10 步。

### 11.5 怎么读 CI 结论（不需要令牌，也不需要 gh）

```
curl -s "https://api.github.com/repos/luyuan-java/xuanming-server-mmo/actions/runs?head_sha=<sha>"            # 每个 workflow 的 status / conclusion
curl -s "https://api.github.com/repos/luyuan-java/xuanming-server-mmo/actions/runs/<run_id>/jobs"            # 每个 job、每个 step 的 conclusion
curl -s "https://api.github.com/repos/luyuan-java/xuanming-server-mmo/check-runs/<job_id>/annotations"       # ::error / ::warning（TestReport、问题匹配器、robot）
```

- job 日志匿名访问返回 403（已实测）。所以失败信息必须变成 annotation；compose 日志放进制品，由用户在浏览器里下载查看。
- `workflow_dispatch` 需要令牌，Claude 触发不了。要重跑，只能推送新提交，或者请用户在网页上点。
- 交付说明里写明：本机跑过的 V 项及其结果，加上 CI 的 run id 与各 job 的结论。CI 还在跑或者是红的时候，**不得写「通过」**。
