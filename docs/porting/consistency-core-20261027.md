# 7.4 一次性只读一致性巡检核心

本实现已于 2026-10-27 接入独立工作树 `D:/luyuan/wt-port-next`，来源为仓外审查候选 `D:/luyuan/tools/review-port-next/next-candidates/consistency`。对照固定 C++/Go `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`，以 Java 当前 SQL schema 为权威。新增源码与测试尚未编译执行，不是构建或上线验收声明；C++ 与 Java main 均未修改。

## 本批闭环

`POST /admin/consistency/check` 在现有 game-data 管理面同步执行一次只读巡检。它复用 `AdminAuthFilter` 的令牌、操作人与审计，控制器再次核验过滤器私有认证标记；完成响应带 `X-Game-Consistency-Exit`。`com.game.data.tools.ConsistencyCheck` 是 HTTP 薄客户端，沿既有 PropertiesLauncher 启动，复用项目已有Jackson严格解析完整报告，不启动Spring、不持有 SQL/Redis 凭据。令牌只读环境变量，禁止重定向、限制响应为256KiB；请求失败或响应未完整到达退出3。

CLI的sendAsync future等待覆盖到body subscriber完成，使用16分钟绝对总截止；超时同时取消future与订阅者（包括响应头迟到竞态），finally调用shutdownNow，至多在剩余预算内等待2秒，不调用可无限等待的HttpClient.close。HTTP200与退出头不足以通过：还要严格UTF-8、JSON完整且无尾随内容、必填字段、finalized、请求范围、状态/coverage与退出码一致。此调整取代旧规格的JDK单文件入口，避免自写JSON解析器；仍然只是HTTP客户端，不是直连库工具。

此批没有实现持久 `runs`、分布式单飞、调度、Redis 报告缓存或专属巡检指标。这些仍属于后续全量目标；本地互斥只限制单个实例，响应明确 `no_durable_run`，不得解释为跨副本单飞。同步报告在调用方丢失后不能查询恢复。管理员应保存收到的 JSON；丢响应时必须按 UNKNOWN 处理。

首批核心的 11 项如下；[第二批扩展](consistency-extra-20261027.md) 接入后，默认清单共 20 项，包含 1 项明确保留 UNKNOWN 的部分规则：

| ID | 实际检查 | 边界 |
|---|---|---|
| route.meta | 唯一 id=1、规范 UUID、64 分片、writable=0/1 | writable=0 合法；route.fences 未实现 |
| zone.player | player→zone_config 与 zone_merged_into | 配置 CLOSED/MAINTENANCE 仍是已知区；zone=0/缺区/退休引用 BLOCK |
| zone.guild | guild→zone_config 与 zone_merged_into | 同上，不查询 guild_player_state 哨兵 |
| zone.guild_rank | SSCAN 索引 + SCAN 物理区榜键、TYPE、SQL 区身份 A→Redis→SQL B | 维护锁存在 UNKNOWN；只支持已配置为单节点的连接；重复键去重；消失计 transient；索引不一致 UNKNOWN |
| account.player_account | player.account→account 同语句反连接，owner_revision>0 | 不 SELECT 账号内容、密码或令牌，不复制 Go account_role/player_route |
| friend.orphan | friend/request/block 两端按组合主键反连接 | 好友跨区合法；完整扫描后仅 friend 边孤儿占比≥5%升级 BLOCK；缺表和部分扫描不计算全量比率 |
| mail.state_orphan | 按不同 mail_id 分页反连接 mail_message | 状态行数量不是分母；不核收件人存在；不读正文/附件 |
| match.expired_unrecoverable | formation 驱动 LEFT JOIN completion，state2/3/4、SQL now 与60秒宽限，缺前置或协议/实例/操作不匹配 | 缺completion能检出；COMPLETE/ABORT排除；无Create合法退出区别处理；合法pending形状因尚未共享完整Create绑定验证为 UNKNOWN(deep_validation_unavailable) |
| player.state_orphan | player→state 缺/NULL，state→player 反向孤儿 | 空 protobuf bytes 合法 |
| own.fence | saved_epoch>owner_epoch | SQL十进制比较，覆盖UINT64高半区 |
| friend.symmetry | 反向好友边存在 | 两次确认稳定缺失则 BLOCK；事务并发变化复核，不据首次候选报告坏边 |

全部登记项在 `ConsistencyService.CATALOG`；请求了未实现项时显式 UNKNOWN(not_implemented)，未请求项列入 omitted。匹配正文深读、route.fences、退休原件/目录/资产水位、其他旧27项及拓扑扫描没有被伪装为完成。匹配 SQL 只读取 Create 长度；不把终局960KiB上限冒充Create协议上限。

## 连接和预算

专用 `JdbcConsistencyReader` 内部持有 Druid 池，不作为 DataSource bean 注册，不借用公共 data 池。生产模式必须 TiDB；使用既有 `StrongTiDbConnectionPolicy` 安装每物理连接策略，每次借出会话重新证明并核对 `DATABASE() == GameNamespace.value()`。dev/test允许MySQL；H2只用于SQL形状测试，不算强读证据。URL禁止 `createDatabaseIfNotExist` 和自定义 `sessionVariables`，不会执行领域建表/播种/业务补偿。

需要运维配置专用仅SELECT账号；实际 GRANT 与 TiDB 会话、MySQL/TiDB查询计划须在真实环境另验。不是把 `Connection.setReadOnly` 当作权限审计。连接取得、元数据、证明、prepare/绑定、execute、每次next/解码与关闭都传播同一个单调截止；请求前设置剩余网络超时。清理超时无法设置则 abort/discard，禁止旧无限超时下继续close；prepare/execute返回时预算已尽也隔离连接，避免尚未交给调用方的资源泄漏。

默认全轮15分钟、单项60秒、单次请求最多2秒；页256（最大2000）、单项最多20000行/键。请求只能收紧预算。第一页包含零号行，使用DECIMAL游标保留UINT64高半区；先取固定upper，随后keyset分页。空源表也执行依赖列/表探测，避免缺域假PASS。SQL/Redis失败只报固定reasonCode，不输出异常原消息、DSN或绑定值。

确认候选延迟2秒后点查原件；第二次失败是UNKNOWN，变好/消失是transient，仍坏但原件变化是UNKNOWN(concurrent_change)。这是RC时间区间观察，不是单时刻全库快照；游标后新增或低于游标的并发新ID可能留到下轮。只有扫描完成且无未知时 countExact=true。已确认BLOCK/WARN可与PARTIAL覆盖同时存在，退出码不会抹掉覆盖缺口。

Redis只使用既有已初始化单例，不启动它的懒连接或取得会prune的业务目录。原始Lua仅TYPE/SCAN/SSCAN，READ_ONLY模式，不创建 RMapCache、不写任何Redis键。SCAN的COUNT是提示，Lua另设返回数量和字节长度边界，超出则UNKNOWN。Redis请求可能在客户端超时后迟到完成，但只有无副作用读；没有异步后台SQL任务。未初始化Redis为UNKNOWN(redis_not_ready)。

## 报告与CLI

每项含 PASS/WARN/BLOCK/UNKNOWN、COMPLETE/PARTIAL/NONE、countExact、scannedCount、confirmedBad、transientCount、reasonCode、最多16个样例及SQL时间区间。样例和游标/upper用每轮随机盐SHA-256，原ID、账号、邮件和PB正文不出报告。salt不出报告，不能跨run关联玩家。

CLI退出码：0=本次选中项完整且未达阈值；1=确认BLOCK（或fail-on=warn时WARN）；2=本地参数错误；3=无阈值级发现但有UNKNOWN/部分覆盖，或请求/完整响应不可得。1与3并存时取1，JSON仍保留unknown coverage。

```powershell
$env:GAME_ADMIN_TOKEN = '<由运维安全注入>'
java -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar `
  -Dloader.main=com.game.data.tools.ConsistencyCheck `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  --url http://127.0.0.1:18106 --operator operator --fail-on warn
```

上述命令是待验用法，本批没有执行。

## 已接入的配置

实现位于 `com.game.data.consistency`，配置与控制器由 game-data 自动扫描。CLI 位于 `com.game.data.tools.ConsistencyCheck`，使用已有 PropertiesLauncher 和 Jackson；没有新增依赖。`AdminAuthFilter.opOf` 已增加固定 `consistency` 分类。

`application.yaml` 的 `game.data.consistency` 绑定 `GAME_DATA_CONSISTENCY_JDBC_URL`、`GAME_DATA_CONSISTENCY_USERNAME`、`GAME_DATA_CONSISTENCY_PASSWORD`。凭据缺失时依赖 SQL 的项明确 UNKNOWN，不复用 root 或公共池。部署须提供专用只读账号；URL 不能带 `createDatabaseIfNotExist`。Java 的 schema 与源版不同，不照抄其表名或行结构。

`PARITY.md` 与总清单已记录核心和扩展的范围与待验证状态。原候选及清单保存在仓外供核查；持久运行记录、调度、剩余清单不在本核心的完成声明内。

## 测试候选与仍需验收

八个 JUnit 类目前合计 57 个 @Test（以源码统计为准）：匹配形状 13、service 16、SQL 计划 6、JDBC 10、Redis 3、controller 2、CLI 6、配置装配 1。根构建报告决定最终执行数量。覆盖最后 next 失败、已扫页断线、候选复核失败、并发修好/仍坏变动、空表缺依赖、空榜仍须 SQL 身份与依赖列证明、扫描上限、高半区游标、盐脱敏、物理榜无索引、榜类型修好但区引用仍坏不得 PASS、未知拓扑、MySQL 生产拒绝、TiDB follower 拒绝、namespace 错、借连接/绑定/prepare 预算耗尽、清理 abort、两份独立 byte[] 相等、认证标记、无结束 body/响应头迟到取消、响应超限、空/截断/非法 UTF-8/尾随 JSON 与错误退出头。配置测试另验证缺凭据时不借业务数据源、不初始化懒 Redis。

H2用例只检查Java表投影上的SQL语义，显式进程内内存库，不读取真实DB配置。没有条件真实环境测试自动连库；真MySQL/TiDB SELECT账号、EXPLAIN（非ANALYZE）、Redis单节点/Cluster、分布式并发、HTTP断线与生产完整预算验收仍待专门窗口。必须由根代理合入后统一编译和测试，候选当前没有“测试通过”证据。
