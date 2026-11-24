# 独立 Web GM 管理后台微服务设计（`game-gm-admin`）

## 需求分析
将权威仓 `xuanming-server-mmo-cpp/java/gm_admin` 提供的 Web GM 后台能力完整移植到 Java 独立服务端，提供独立运行的 Spring Boot 3.5 管理后台进程（`game-gm-admin`），彻底消除对 JeecgBoot / Shiro / gRPC / etcd 与已废弃 `springboot_satoken_auth_starter` 的依赖：
1. **管理员鉴权与细粒度 RBAC**：支持基于常量时间比对的操作员令牌认证与 15 项细粒度权限（区服只读、玩家只读、个人/系统/活动邮件拟稿与发送、审计查询、7 项在线玩家 GM 处置、战斗剧本审核），收紧管理员用户名字符集校验（仅允许 `[A-Za-z0-9._@-]`，禁止 `|` 与控制字符，≤64 UTF-8 字节）。
2. **两阶段冻结命令账本与不可变审计**：沿用 `gm_command` / `gm_command_audit` 表结构，将「拟稿冻结（DRAFT）」与「执行发送（SENDING → DELIVERED / EXECUTED / REJECTED / UNKNOWN）」解耦；支持 `(operator_name, client_request_id)` 幂等重放与参数篡改冲突拦截（409），绝不在数据库事务内执行网络 RPC。
3. **Java 原生服务集成**：
   - 玩家查询经 `PlayerProfiles` + `PlayerHomeZones` + `PlayerPresenceDirectory` + `PlayerLocationDirectory`；
   - 个人/全服/活动三段式邮件经 `MailAdminService`（`ServiceCallSignatures` HMAC-SHA256 `admin` 签名）；
   - 战斗剧本运营审核经 `MatchInternalService`（387/388，`ops` 签名）；
   - 区服只读概览经 Gateway `/admin/zones`（仅允许 HTTPS 或本机回环 HTTP，256 KiB 响应上限，禁用重定向）；
   - 在线玩家 7 项 GM 命令（加/扣/封/解货币、改等级 1..85、发宝宝、发物品 1..99）保留 5 分钟草稿过期与 `UNKNOWN` 禁止重发护栏。

## 设计分析
- **端口与部署隔离**：业务 HTTP 端口默认 `18128`，Actuator 管理端口 `18129`，Dubbo 仅作消费方（不暴露业务客户端协议端口）。
- **核心模块划分**：
  - `GmAdminAuth` / `GmPrincipal`：请求头令牌解析（`Authorization: Bearer ...` 或 `X-Gm-Token`）+ 常量时间比对 + 用户名格式与 RBAC 权限校验；
  - `MailCampaignManifest`：与 `game-mail` / Go `campaign.go` 逐字节一致的 1000 人分片与 `v1\n` SHA-256 摘要计算；
  - `GmCommandJournal`：`TransactionTemplate(REQUIRES_NEW)` 封装草稿创建、行锁租约抢占（邮件 60s / 活动 120s）与终态落盘审计；执行外部发送前显式校验 `!TransactionSynchronizationManager.isActualTransactionActive()`；
  - `GmGamePort` / `DubboGmGamePort`：隔离协议组装、签名（`ServiceCallSignatures` 与 `gm-player-command-v1`）及下游调用失败归类（明确拒绝 `outcomeUnknown=false` vs 传输/未知状态 `outcomeUnknown=true`）；
  - `GmController` / `GmPlayerCommandController`：严格 JSON 解析（拒绝未知字段、重复键、尾随 token 与超长请求体）并输出统一 `{success, code, message, result}` 响应。

## 思想分析
- **草稿即冻结原件**：所有涉及资产发放或玩家状态修改的 GM 操作必须先落盘冻结，再按草稿 ID 执行，天然隔离浏览器重复点击与前端篡改。
- **宁留 `UNKNOWN` 不盲目重发**：邮件类依托 `MailAdminService` 的 `request_id` 跨进程幂等允许安全重投；在线玩家直接 GM 无跨进程幂等表，一旦进入 `SENDING` 后结局未知必须停留在 `UNKNOWN`，由人工核对后决定，杜绝双倍发奖/双倍扣款。
- **最小依赖与原生适配**：完全复用 `game-api` / `game-proto` / `game-common` / `game-discovery` 与 Spring Boot 3.5 + JDBC，零引入 JeecgBoot / Shiro / gRPC 依赖。

## 漏洞分析
1. **SSRF 风险**：`gatewayUrl` 若放任任意 URL 可被用于内网探测 → 构造期强制校验仅允许 `https` 或本机回环 `http`，禁用重定向，固定拼接 `/admin/zones` 并限制响应体 ≤256 KiB。
2. **管理员名称注入签名分隔符**：`gm-player-command-v1` 签名串使用 `|` 分隔 → 认证层与命令执行层双重拦截 `|`、控制字符、超长与非 `[A-Za-z0-9._@-]` 字符。
3. **JSON 重复键 / 未知字段绕过**：Jackson 默认覆盖重复键 → 启用 `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`、拒绝尾随 token 与白名单字段检查。
4. **越权探测草稿存在性**：`GmPlayerCommandController.send` 先校验调用者至少拥有一项玩家 GM 权限，避免无权用户枚举命令 ID。
5. **事务内长耗时 RPC 拖垮连接池**：`GmCommandJournal.sendInternal` 显式断言不在活跃事务中执行发送回调，三步拆分（抢租约事务 → 事务外 RPC → 回写结果与审计事务）。

## 步骤分析
1. 在根 `pom.xml` 注册 `game-gm-admin` 模块并配置依赖与 `application.yaml` / `db/gm-schema.sql`。
2. 实现 `GmAdminProperties`、`GmPrincipal`、`GmAdminAuth`、`MailCampaignManifest` 与 `GmCommandJournal`。
3. 实现 `GmGamePort`、`DubboGmGamePort`、`GmController`、`GmPlayerCommandController` 与 `GmAdminConfiguration`。
4. 编写 `MailCampaignManifestTest`、`GmCommandJournalTest`（H2 事务与并发/租约/TTL/未知结局）与 `GmAdminHttpTest`（MockMvc 鉴权、全端点与回环 Gateway 测试）。
5. 执行 `mvn -o -B install -pl game-gm-admin` 与门禁校验，更新 `PARITY.md` 并提交推送。

## 执行步骤
1. 已完成 C++ 仓 `java/gm_admin` 全量代码审阅与 `game-api`（`MailAdminService` / `MatchInternalService`）能力对照。
2. 创建 `game-gm-admin` 模块源码、配置与 DDL，并接入根 `pom.xml`。
3. 编写并运行全套单测与 Spring MockMvc 测试，全部通过后安装并提交推送。
