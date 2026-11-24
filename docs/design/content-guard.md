# 内容安全服务（`game-content-guard`）与改名/聊天治理设计方案

> 对应根索引 [AGENTS.md](../../AGENTS.md)、选型表 [tech-stack.md](tech-stack.md) 与 C++/Go 权威仓 `go/content_guard`、`proto/content_guard/content_guard.proto`。

## 1. 需求分析

1. **独立无状态内容安全微服务**：对应 Go `go/content_guard`（节点类型 `ContentGuardNodeService = 32`），对玩家填写的文字按场景（角色名、帮会名、宠物名、帮会公告、世界/队伍/私聊、私有标签、聚宝斋标题）给出 `PASS` / `MASKED` / `REJECT` 三种裁决与命中类别。
2. **两层防线**：
   - **本地词库 + 变体联系方式层**：NFKD 归一化 + 大小写折叠 + 2,481 字繁简转换（`t2s.txt`）+ 可跳过字符（空白/标点/符号/零宽/组合记号）跳过；压缩字典树支持 `sub` / `token` / `prefix` 三种匹配模式、6 个内置防冒充官方词与白名单区间豁免；联系方式启发式拦截外链 URL/域名、11 位手机号与 24 个微信/QQ/交易线索词（含汉字数字谐音折算）。
   - **外部平台审核层（策略扩展）**：按玩家渠道账号（`wx_` / `dy_` 或身份回查）路由到微信 `msgSecCheck v2` 或抖音 `antidirt`；名字/公告同步强检（不可用 fail-closed），世界频道支持短超时先行放行并置 `pending_platform_review = true`，由聊天服异步调 `CheckPlatformText` 复检撤回；队伍/私聊/私有标签不走外部平台。
3. **在线词库热替换与完备性校验**：支持 `#content_guard_lexicon 1` 文本格式解析与序列化，通过 `AtomicReference<Snapshot>` 无锁整份原子替换，不中断在途判定；生产开启 `require-lexicon = true` 时未加载正式词库则健康检查 `DOWN` 并拒收判定请求（fail-closed）。
4. **双端一致性**：必须 100% 通过 C++/Go 与 Java 共享的 20 条黄金向量（`vectors.json`），且接入 `game-api` 供 `game-login`、`game-guild`、`game-chat`、`game-scene` 调用。

## 2. 思想分析

- **对齐 C++/Go 架构，采用 Java 21 惯用实现**：
  - 节点边界与协议消息完全沿用契约 `proto/content_guard/content_guard.proto`，服务端实现编译期生成的 `ContentGuardHandler` 接口并经 Dubbo Triple 暴露 `com.game.api.ContentGuardService`（group `content-guard`），复用既有 `DubboCallAuth` HMAC 鉴权，不自造网络层。
  - **内存与 GC 友好**：字典树编译后压平成原始类型数组（`int[] first, int[] labels, int[] next, byte[][] term`），避免每节点一个 `HashMap` 造成百万级对象与 GC 扫描停顿；文本折叠同样使用原始 `int[]`/`boolean[]` 数组；快照切换采用不可变对象 + `AtomicReference`，旧快照在所有在途读结束后由 JVM GC 自然回收。
- **采用的设计模式与原因**：
  1. **策略模式（Strategy）**：`PlatformAdapter` 接口抽象外部平台审核（`WeChatAdapter`、`DouyinAdapter`），`PlatformReviewer` 按渠道分发，新增平台（如商业第三方内容审核）满足开闭原则（OCP），无需修改本地引擎与核心服务。
  2. **不可变快照（Immutable Snapshot）**：`WordMatcher` 与 `Snapshot` 编译后完全只读，热更新在后台编译完成后一次性 CAS 替换，零锁竞争、无半份词库可见性问题。
- **零新第三方依赖**：NFKD 用 JDK `java.text.Normalizer`，外部审核 HTTP 用 JDK 21 内置 `java.net.http.HttpClient`，JSON 用 Spring 自带 Jackson，身份 TTL 缓存用既有 Guava `Cache`（51.9K Star），指标用 Micrometer。

## 3. 设计分析

### 3.1 模块划分

- `game-api`：新增 `DubboGroups.CONTENT_GUARD = "content-guard"` 与 `com.game.api.ContentGuardService`（`checkText` / `checkPlatformText`）。
- `game-content-guard`（独立 Spring Boot + Dubbo Triple 进程，Dubbo 端口 `20919`，管理端口 `18119`）：
  - `com.game.contentguard.textnorm.TextNorm`：NFKD + 小写 + `t2s.txt` + 可跳过/组合记号判定。
  - `com.game.contentguard.wordlist.{WordCategory, MatchMode, Lexicon, WordMatcher}`：词库编解码与扁平字典树匹配器。
  - `com.game.contentguard.contact.ContactDetector`：外链/域名/手机号/变体联系方式检测器。
  - `com.game.contentguard.engine.ContentGuardEngine`：场景动作表、原子快照与原地按码点打码（连带组合记号）。
  - `com.game.contentguard.platform.{PlatformAdapter, WeChatAdapter, DouyinAdapter, PlatformReviewer}`：外部平台审核适配与路由。
  - `com.game.contentguard.service.ContentGuardRpcService`：实现 `ContentGuardService` 与生成接口 `ContentGuardHandler<Void>`，参数校验、就绪门禁与低基数 Micrometer 指标。
  - `com.game.contentguard.ContentGuardProperties` / `ContentGuardApplication`：配置绑定、文件词库加载（可选路径）与健康检查。

### 3.2 场景动作表

| 场景大类 | `TextScene` | `IMPERSONATION` | `ABUSE` | 其余敏感词 | 联系方式广告 | 外部平台审核 |
|---|---|---|---|---|---|---|
| `NAME` | `ROLE_NAME` / `GUILD_NAME` / `PET_NAME` / `TRADE_TITLE` | 拒绝 | 拒绝 | 拒绝 | 拒绝 | 同步强检 |
| `NOTICE` | `GUILD_ANNOUNCEMENT` | 忽略 | 拒绝 | 拒绝 | 拒绝 | 同步强检 |
| `CHAT` | `CHAT_WORLD` / `CHAT_TEAM` / `CHAT_PRIVATE` | 忽略 | 打码 `*` | 拒绝 | 拒绝 | 仅世界频道（支持短超时 pending） |
| `PRIVATE_LABEL` | `PRIVATE_LABEL` | 拒绝 | 拒绝 | 拒绝 | 不检测 | 不送外部 |

## 4. 漏洞分析与防错边界

1. **隐私与日志泄露**：玩家待检原文（聊天内容、名字）严禁写入日志、异常消息或 Prometheus 指标 label；日志仅输出字节长度、场景名、结局与类别。
2. **UTF-16 代理对（Emoji / 增补汉字）下标错位**：Java `String.charAt` 按 UTF-16 `char` 计，与 Go `rune`（Unicode 码点）不同。`TextNorm.fold` 严格按 `String.codePoints()`（`int[]`）处理并记录码点下标 `origIndex`，打码也按码点逐个替换为 `*` 再重组字符串，确保增补平面字符（如 Emoji）与组合记号（`Mn`/`Me`）行为与 Go 版逐字一致。
3. **超大文本 DoS**：入口先按 UTF-8 字节数核对 `maxTextBytes`（缺省 4096 字节），超限直接抛 `IllegalArgumentException`（对应 gRPC `InvalidArgument`），拒绝进入 NFKD 与正则匹配。
4. **未就绪放行风险**：当 `requireLexicon = true` 且 `lexiconVersion == 0` 时，`checkText` / `checkPlatformText` 明确拒绝（`IllegalStateException` 对应 `Unavailable`），绝不退化为仅内置词静默放行。
5. **外部凭据安全**：微信/抖音 `appSecret` 仅从环境变量注入，不入配置库、不打日志；HTTP 响应体读取限制上限 `64 KiB`，防止恶意上游撑爆内存；token 过期码（微信 `40001/40014/42001`、抖音 `401/40002/40020`）自动清缓存并至多重试 1 次。
6. **未知场景 / 未知类别 fail-closed**：`TEXT_SCENE_UNSPECIFIED` 或未知枚举值拒绝输入；未知场景大类一律按 `REJECT` 处理。

## 5. 步骤分析

1. 在 `game-api` 注册 `DubboGroups.CONTENT_GUARD` 与 `ContentGuardService`。
2. 创建 `game-content-guard` 模块并纳入根 `pom.xml`，复制 `t2s.txt`（2,481 对繁简字）与共享测试向量 `vectors.json`。
3. 实现 `textnorm`、`wordlist`、`contact`、`engine`、`platform`、`service` 与 Spring Boot 启动配置。
4. 编写完整单测：20 条共享 `vectors.json`、联系方式变体、UTF-16 代理对与组合记号、词库解析异常、平台适配器（本地 `HttpServer` 验证 token 刷新与重试）、RPC 服务校验与 `ContentGuardContractDispatcher` 派发。
5. 下一步（3b）：将 `ContentGuardService` 接入 `game-login`、`game-guild`（含 `RenameGuild` 439）、`game-chat`（含 `ReportChat` 433 / `RetractWorldChat` 434）、`game-scene`（含 `RenamePlayer` 428/429）。

## 6. 执行步骤

1. 落盘本设计文档 `docs/design/content-guard.md`。
2. 更新 `game-api` 与根 `pom.xml`，实现 `game-content-guard` 全部源码与资源。
3. 执行 `mvn -o -B -pl game-api,game-content-guard -am clean install` 验证编译与全部单测（含 20 条双端共享向量）。
4. 更新 `docs/design/architecture.md` 与 `PARITY.md`，提交并推送 `origin/main`。
