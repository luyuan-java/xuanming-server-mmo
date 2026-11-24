# 充值支付微服务（`game-payment`）设计文档

> 对应 C++/Go 权威版 `go/payment`、`go/shared/antiaddiction`、`proto/payment/payment.proto` 与 `payment_table.proto`（含 2026-11-20 审计修复项）：向客户端提供 `ClientPlayerPayment`（396–399）RPC 入口，向外部支付渠道提供 `/notify/{channel}` HTTP 回调入口，并以 `payment` 角色通过 HMAC-SHA256 签名与 `game-mall` 完成订单只读、付款记账、修订同步与退款闭环。

---

## 一、需求分析

1. **客户端协议面（`ClientPlayerPayment`，消息号 396–399，经 `game-gate` 路由至 Dubbo group `payment`）**：
   - `ListPayChannels`（396）：按发行平台（`android_app`/`ios_app`/`wechat_minigame`/`douyin_minigame`/`desktop`）返回开放且已启用的渠道列表（按配置展示序）。
   - `CreatePayIntent`（397）：核对渠道/平台、向商城只读订单冻结条款、按合规闸执行实名与未成年人消费限额预占、落库 `payment_intent`（每单至多一条、渠道首次锁定），并返回对应渠道 SDK 调起参数（微信 APP / 支付宝 APP / DevPay / 微信小游戏 / 抖音小游戏 / Apple IAP / Google Play）。
   - `GetPayIntent`（398）：买家查询自身订单支付意图；处于 `CREATED` 时按最小间隔限频触发一次主动查单并落账。
   - `SubmitPurchase`（399）：处理商店渠道（App Store / Google Play）客户端上报的交易号或购买令牌，向商店服务端核实后记账，并准确返回 `finish_transaction`（Google 仅在已进入商城记账链路时允许结束/确认，冲突或转人工时返回 `false` 保留 Google 3 天自动退款）。
2. **渠道回调与后台闭环**：
   - HTTP 入口 `GET/POST /notify/{channel}`：处理微信小游戏 / 抖音小游戏配置握手与全部 7 渠道异步通知；验签后仅写本服务 SQL 库即返回渠道约定应答，不阻塞在商城可用性上。
   - 5 个后台租约推进器：到期查单与关单（`sweepIntents`）、付款与修订推送商城（`pushMall`）、商城退款待办执行与回执/转人工（`processRefunds`）、Google Play 消耗收尾（`finalize`）、App Store 通知历史与 Google Play 作废列表游标补漏（`pollHistory`）。
3. **国内未成年人防沉迷充值限额合规闸（国新出发〔2019〕34 号 + 《未成年人网络保护条例》）**：
   - 支持 `none` / `realname` / `overseas` 三档；生产模式下 `none` 禁止启用 App 真钱渠道（微信支付/支付宝/Apple/Google），小游戏渠道依赖平台侧实名与限额。
   - 在 `game-common` 沉淀无 I/O 纯规则 `AntiAddictionRules`（`pi` 26 进制出生日期解码、北京时间周岁、可玩日历 20:00–21:00、单次/单日/每月消费上限、GB 11643 身份证校验），供 `game-payment` 与后续 `game-login` / `game-gate` 共用。
   - 未满 8 周岁禁止充值（31009）；8–15 周岁单次/单日 50 元、每月 200 元；16–17 周岁单次/单日 100 元、每月 400 元；多账号按 `SHA-256("spender|" + pi)` 前 16 字节合并计额，库中不存 `pi` 原文。
4. **2026-11-20 安全审计硬约束**：
   - Google OIDC JWKS 拉取必须在锁外执行、同批并发未命中合并为单次请求（singleflight），并在最近成功拉取 60 秒内对未知 `kid` 直接拒签，防随机 `kid` 触发 JWKS 刷新 DoS。
   - Apple StoreKit 2 价格毫单位（`×1000`）按 ISO-4217 币种小数位精确折算（0 位小数币种 `÷1000`，3 位小数币种 `÷1`，常规 2 位小数币种 `÷10`）。
   - `sameFact` 重复事实比对必须校验 `paidAmountMinor` 与 `paidCurrency`（两方非零/非空时必须一致），拦截金额或币种篡改冲突。
   - 后台最老待推进年龄指标对时钟回拨做无符号防下溢保护（`nowMs > oldestCreated` 才相减）。

---

## 二、设计分析

1. **模块划分与依赖边界**：
   - `game-common`：新增 `com.game.common.compliance.AntiAddictionRules` 纯规则类（零外部依赖）。
   - `game-api`：新增 `DubboGroups.PAYMENT = "payment"`。
   - 新增独立 Spring Boot 进程模块 `game-payment`（直接依赖 `game-discovery` 以满足部署命名空间自动配置与 `NamespaceLiteralGuardTest` R5 守卫）：
     - Dubbo Triple 端口 `20920`（group `payment`），对外渠道回调 HTTP 端口 `18122`，Actuator 独立管理端口 `18125`（默认仅绑 `127.0.0.1`）。
     - 入口 `PaymentRpcService` 复用 `game-proto-codegen` 编译期生成的 `ClientPlayerPaymentHandler` 与零反射 `ClientPlayerPaymentContractDispatcher`。
   - `game-gate`：将 `ClientPlayerPayment` 接入 `MessageRoutes.SERVICE_BACKENDS` / `NODE_BACKENDS`、`GateConfiguration`（`@DubboReference(group = DubboGroups.PAYMENT, ...)`，`handle` 零重试 + 超时倒计时传播）与 `application.yaml` / `application-nacos.yaml`。
2. **数据存储与并发一致性（`payment-authority.sql`）**：
   - 4 张表：`payment_intent`、`payment_spend`、`payment_txn`、`payment_channel_cursor`，全部自然键整型派生主键，不依赖外部发号器。
   - 普通状态推进与后台认领走单语句条件 `UPDATE`（以旧 `next_check_ms` / `next_attempt_ms` / `refund_next_ms` / `finalize_next_ms` / `fact_revision` 作乐观 CAS 租约，多副本安全水平扩展）。
   - 未成年人额度预占（`reserveIntent`）、未成年人付款原子落账（`recordMinorPaymentTxn`）、关闭意图复活复核（`reviveMinorIntent`）全部运行在 `READ COMMITTED` 显式事务内，严格按锁序：`payment_spend` 行锁 → `payment_intent` `FOR UPDATE` → `payment_txn` `FOR UPDATE`，杜绝跨请求 TOCTOU 与提前占额。
3. **跨模块通信与零新增依赖**：
   - 所有 HTTP 渠道通信与商城 HMAC 客户端均采用 JDK 21 标准 `java.net.http.HttpClient`（禁重定向、有限流响应体读取上限），所有加密/验签（RSA-SHA256、ES256 `SHA256withECDSAinP1363Format`、AES-256-GCM、AES-256-CBC、HMAC-SHA256、SHA-1、X.509 链验证）均采用 JDK 21 标准 JCA/JCE 与既有 Jackson，零新增第三方库。

---

## 三、思想分析

- **策略模式（Strategy）与接口隔离（ISP）**：
  - `PaymentChannel` 定义全部 7 个渠道的统一协议操作（`prepare`/`query`/`close`/`refund`/`parseNotification`/`writeAck`），而商店回执验证（`ReceiptChannel`）、消耗收尾（`Finalizer`）、历史补漏（`HistoryPoller`）、配置握手（`Handshaker`）拆成细粒度可选能力接口，避免让支付宝/微信支付实现无意义的空方法。
  - `RealNameReader` 与 `MallClient` 采用薄接口隔离，方便生产接 Dubbo/HTTP、单测直连本地回环或内存桩。
- **不可变消息与事件驱动（Actor 隔离）**：
  - 跨线程/跨服务边界仅传递不可变 `record` 与 Protobuf 对象；`PaymentWorkers` 通过非阻塞信号槽（`Kick`）唤醒商城推送器，写库与远端 I/O 解耦。
- **单一事实源与最小完整实现（Ponytail）**：
  - 未成年人已用额度不维护冗余计数器，直接由 `payment_intent` 行状态机（`CREATED` 占全额、`PAID` 按 `paid_ms` 归入北京月/日、`CLOSED` 释放）在行锁下派生，杜绝计数漂移。
  - 仅支持微信支付公钥模式（`PUB_KEY_ID_`）与支付宝 RSA2 公钥模式，不引入多余证书轮换框架。

---

## 四、漏洞分析

1. **退款通知先于付款通知到达导致先发货后发现已退款**：
   - 规避：`revoke` 在 `payment_txn` 查无此行时写入 `fact_revision = 1`、`mall_state = ORPHAN` 的墓碑行；迟到付款命中墓碑直接按重复吞掉，不推商城、不发货。
2. **未成年人并发建单或意图刚过期关闭与付款回调竞态绕过月/日限额**：
   - 规避：`reserveIntent` 与 `recordMinorPaymentTxn` 共用 `payment_spend` 行锁，并在 `READ COMMITTED` 下 `FOR UPDATE` 读取该主体所有在途与本月已付意图；意图已关闭且迟到付款超限时在同事务内标 `ORPHAN` 转人工，不恢复意图、不发货。
3. **Google Play OIDC 公钥随机 `kid` 引发外部 JWKS 刷新 DoS**：
   - 规避：锁外抓取 + `CompletableFuture` 单飞合并 + `googleCertsMinRefetch = 60s` 冷却期内未知 `kid` 直接拒签（`401`）；网络抓取失败包装为临时不可用（`500`）让 Pub/Sub 重投而不误报伪造签名。
4. **微信小游戏 `session_key` 明文落库泄露与传输错误泄露敏感 URL 参数**：
   - 规避：`session_key` 使用 `SHA-256("payment:wechat-minigame:session-key-seal:v1\n" + appSecret)` 派生密钥经 AES-256-GCM 加密后存入 `channel_ref`，意图关闭或已付即清空；所有 HTTP 传输错误只记录 `method + host + cause`，不回显带 `secret`/`access_token`/`purchaseToken` 的完整 URL。
5. **商店渠道金额篡改或币种小数位误算**：
   - 规避：Apple 价格按 ISO-4217 精确折算，同币种价格与订单冻结价不符直接转 `ORPHAN`；`sameFact` 校验 `paidAmountMinor`/`paidCurrency`/`storeAmountMinor`，不一致返冲突且 Google 不发 `finish_transaction`。
6. **配置误启导致生产开放 `devpay`、明文 HTTP 回调或裸奔跳过防沉迷**：
   - 规避：`PaymentProperties.validate(...)` 启动期 fail-fast：非 `dev`/`test` 模式禁止 `devpay`、禁止覆盖微信/支付宝网关、禁止微信小游戏沙箱 `env=1`、禁止 HTTP 回调基址、`minor_spending_gate=none` 时禁止启用 App 真钱渠道、禁止跨平台乱挂渠道（如 iOS 挂支付宝/微信）。

---

## 五、步骤分析

1. **公共规则与契约常量**：
   - 在 `game-common` 新增 `AntiAddictionRules` 及其单测 `AntiAddictionRulesTest`。
   - 在 `game-api` 新增 `DubboGroups.PAYMENT`。
2. **创建 `game-payment` 模块与存储层**：
   - 创建 `game-payment/pom.xml` 并注册进根 `pom.xml`；编写 `payment-authority.sql`、`PaymentKeys`、`PaymentRecords`、`PaymentStore`、`JdbcPaymentStore`。
3. **商城客户端与 7 渠道协议适配器**：
   - 实现 `MallClient` / `HttpMallClient`（HMAC v1 签名、全接口支持）。
   - 实现 `ChannelCrypto` 及全部 7 个渠道（`DevPayChannel`、`WechatPayChannel`、`AlipayChannel`、`WechatMinigameChannel`、`DouyinMinigameChannel`、`AppleChannel`、`GoogleChannel`）。
4. **核心服务、后台推进器、RPC 与 HTTP 回调装配**：
   - 实现 `PaymentService`、`PaymentWorkers`、`PaymentMetrics`、`PaymentTips`、`PaymentRpcService`、`PaymentNotifyController`、`PaymentProperties`、`PaymentConfiguration`、`PaymentApplication` 及 `application.yaml` / `application-nacos.yaml`。
5. **接入 `game-gate` 与全仓守卫**：
   - 更新 `MessageRoutes`、`GateConfiguration`、`game-gate` yaml、`MessageRoutesTest`、`GateConfigurationTest` 与 `game-robot` `NamespaceLiteralGuardTest`。
6. **验证与对账交付**：
   - 编写覆盖核心状态机、未成年人限额并发/迟到/复活、退款与修订翻转、7 渠道验签/加解密/HTTP 回环（含 Apple ISO-4217 小数位、Google JWKS 60s 冷却防 DoS、微信小游戏 AES 加密推送）、配置 fail-fast 的全套单测，验证 `game-common`、`game-api`、`game-payment`、`game-gate` 及 `NamespaceLiteralGuardTest` 全绿，登记 `PARITY.md` 并提交推送。

---

## 六、执行步骤（最终确定版）

严格按步骤 1 → 6 依次落地；每模块就绪后即运行离线 Maven 编译与定向单测，全部通过后更新 `PARITY.md` 并推送 `origin/main`。
