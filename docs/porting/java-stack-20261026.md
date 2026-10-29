# Java 整栈编排接续（2026-10-26）

对照 C++ `92952c84b`，Java 从 `03a0708d` 接续。旧 7.1 规格按早期 12 个进程编写；当前为 17 个服务和一个 robot 工具。此交付是开发与 CI 编排，生产高可用、跨区域故障演练及 K8s 发布仍单独验收。

## 实际接线

- `deploy/compose/stack.yaml` 登记根 reactor 的全部 Boot 产物；`check-stack.py` 从各模块 pom 读取真实名单，漏服务、错构建原件、可变标签、错命名空间或数据库均拒绝。
- 17 个服务共用同一 TiDB 数据库，库名与 `GAME_NAMESPACE` 相同，账号为 `<ns>_app`。保留各域的 TiDB 强读门禁，不退回旧模块的 MySQL 3306 默认。
- TiDB 使用固定基线 8.5.2，PD/TiKV/TiDB 镜像均按 Docker Registry 2026-10-26 实际 manifest 摘要固定。单 PD/TiKV 仅作开发夹具；不能证明多数副本耐久性。
- `tidb-init` 显式执行已有玩家、路由结构和商城全新安装结构。`route-init` 使用现有 Java `InitializeRouteAuthority`，从真实 SQL 玩家与明确区号建立原件；`GAME_ROUTE_GENERATION` 必须为另存的非零 UUID，重启复用，已有不一致或冻结世代拒绝，不自动补权。
- 场景 RPC、节点链路、rank→mail、交易→商城与 gateway→login 等均使用 Compose 服务名。秘密按调用双方最小范围注入；商城支付、网关、交付与管理四域独立。
- 商城启用独立管理端口 18121，两条 GET 健康探针不走交易 HMAC；交易与支付路径仍验签。scene readiness 同时要求身份有效、频道同步完成、未疏散且有未排空的世界频道。
- 开发栈开启交易持久调用链，但不扩大物品、宠物、币种与收件箱的受信白名单。支付 mock 默认关闭。全球匹配生产形态需要外部满足 RF/minISR 的 Kafka，当前单 Kafka 夹具使用本地模式，不能冒充全球匹配验收。
- 本地 `start-slice.sh` 同步登记商城与各域开发密钥，统一 TiDB 连接；启动前仍须显式执行所需 SQL 迁移和路由初始化。`stop-slice.sh` 保留 50 秒退出预算。

## 使用

1. 编译全部 jar。复制 `deploy/compose/env.example` 为同目录不入库的 `.env`，填入实际构建提交、契约提交、提交前 12 位镜像标签、部署命名空间、独立路由世代和各个随机密钥。数据库开发口令要求 64 位随机十六进制。
2. `docker compose -f deploy/compose/stack.yaml --profile tools config --quiet`
3. `docker compose -f deploy/compose/stack.yaml --profile tools build`
4. `docker compose -f deploy/compose/stack.yaml up -d --wait --wait-timeout 600`
5. `docker compose -f deploy/compose/stack.yaml --profile tools run --rm --no-deps game-robot smoke --gateway http://game-gateway:18081`

默认 gate/battle 通告的是容器网络服务名，供容器内 robot 使用。宿主客户端需显式设置可达的 `GAME_GATE_ADVERTISE_HOST` / `GAME_BATTLE_CLIENT_ADVERTISE_HOST`；不要把容器服务名直接交给网络外的客户端。只有客户端入口发布到宿主 127.0.0.1，管理口不发布。

## 自动验证与证据边界

`integration.yml` 增加独立 stack job，生成每次运行的新密钥与空库路由世代，构建真实镜像、启动全栈、运行 smoke/movement/reconnect/bag/currency/attribute 六个真实客户端场景，核验 17 个进程仍健康。失败保留服务端与 robot 日志；退出只清理这次 CI 项目。

本机已执行 Compose 配置解析、18 个 Boot 模块核对、缺命名空间/链路密钥/商城模块反例、五个 shell 脚本语法检查。未启动容器，未跑 CI 整栈或高可用演练；全量 Maven 属本轮统一冻结后的独立验证。不能用这些静态结果宣称真实整栈通过。
