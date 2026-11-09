# Java 惯用实现与剩余移植核对（2026-11-06）

本次只读核对 C++ 已提交对象 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 和 `9aaedde23`，Java 为 `D:/luyuan/wt-port-next`、HEAD `0b578ea7` 加当前接续源码。没有写入 C++、同步契约或共享表/地图。下面的库调查不构成依赖升级，也不代表全部功能已移植或通过测试。

## 标准库与现有依赖优先

| C++ 侧职责 | Java 采用方式 | 本次判断 |
|---|---|---|
| STL 容器、局部查询工作区 | JDK `List/Map/Set/PriorityQueue`、record、不可变集合副本 | 现有导航已经采用；不加容器或 ECS 框架。 |
| 宽整数精确乘除、uint64 饱和边界 | JDK `BigInteger` 加仓库无符号工具 | JDK 已提供不可变任意精度整数；无需移植 C++ 扩展整数或引入数值库。官方依据：[Java 21 BigInteger](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigInteger.html)。 |
| 周期巡检、延迟任务、取消与关闭 | JDK `ScheduledExecutorService`，由组件持有并明确关闭 | 调度能力标准库已有；分布式单飞和持久运行记录是业务合同，不能靠换调度库自动获得。官方依据：[Java 21 ScheduledExecutorService](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html)。 |
| 运维 HTTP 与只读外部探针 | JDK `HttpClient` 或已有 Spring HTTP 客户端 | JDK 支持连接复用和异步请求；调用方仍要显式超时、限体及资源释放，不另引 HTTP 框架。官方依据：[Java 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)。 |
| muduo 网络与回调所有权 | 既有 Netty + 明确 EventLoop 所有权 | 不机械复制 C++ weak_ptr/线程本地单例；场景状态仍归逻辑线程，I/O 回调投递回去。 |
| Redis、RPC、应用装配 | 既有 Redisson、Dubbo、Spring Boot | 延续现有栈；本轮技能与导航适配没有新增第三方依赖。 |
| Recast/UE 导航读取与查询 | 现有纯 Java 固定 ABI 读取及不可变查询 | 保留已建立的 double/uint64 格式合同；服务器坐标适配本批独立实现，见 [导航接续](navigation-server-coordinates-20261106.md)。 |

GitHub star 在 2026-11-06 通过官方仓库 API 读取如下；只是当日采用度快照，不是漏洞、安全或兼容证明。所有列出仓库均 `archived=false`。

| 仓库 | stars | 最近 push（UTC） | 本次用途 |
|---|---:|---|---|
| [spring-projects/spring-boot](https://github.com/spring-projects/spring-boot) | 81,738 | 2026-11-06 | 继续使用现有 Boot BOM/装配 |
| [google/guava](https://github.com/google/guava) | 51,923 | 2026-11-05 | 已有依赖；JDK 能完成的功能先用 JDK |
| [apache/dubbo](https://github.com/apache/dubbo) | 41,572 | 2026-11-03 | 继续现有 Triple 业务 RPC |
| [netty/netty](https://github.com/netty/netty) | 35,072 | 2026-11-06 | 继续现有网络层 |
| [redisson/redisson](https://github.com/redisson/redisson) | 24,422 | 2026-11-04 | 继续现有 Redis 适配 |
| [recastnavigation/recastnavigation](https://github.com/recastnavigation/recastnavigation) | 8,164 | 2026-02-27 | C++ 上游参考，不引为 Java 运行依赖 |
| [fabric8io/kubernetes-client](https://github.com/fabric8io/kubernetes-client) | 3,685 | 2026-11-04 | K8s 执行层候选，未新增；当前移植批次不需它 |
| [recast4j/recast4j](https://github.com/recast4j/recast4j) | 293 | 2026-11-02 | 可调查烘焙用途，但未引入，不假称高 star 或现成 UE 格式替代 |

这些具体数字来自 `https://api.github.com/repos/<owner>/<repo>` 的 `stargazers_count` / `pushed_at` / `archived`。API 快照保存于仓外 `tools/review-port-next/java-20261106/java-native-library-stars-20261106.json`。既有 tech-stack 的历史 star 数不冒称本日重新测得；本表也未重验其所有版本兼容结论。

`recast4j` 官方 [NavMeshParams](https://github.com/recast4j/recast4j/blob/main/detour/src/main/java/org/recast4j/detour/NavMeshParams.java) 使用 `float[]` 原点及 float tile 尺寸，其 [MeshSetReader](https://github.com/recast4j/recast4j/blob/main/detour/src/main/java/org/recast4j/detour/io/MeshSetReader.java) 读取自己的参数结构。当前游戏是 UE double/uint64 固定 ABI，不能因都叫 MSET 就直接替换。若后续采用它做 Java 烘焙，应先验证格式转换、精度、可复现产物及许可，再记录为何偏离仓库高 star 优先规则；本次不作该变更。

## 可继续实施的真实差距

| 范围 | 已核实的 Java 状态 | 下一闭环 |
|---|---|---|
| 通用导航生产接入 | `game-navigation` 已有读取/最近点/射线/路径；本批新增服务器坐标适配。`game-scene` 仍无该模块依赖，`ConfigSceneTables` 只绑定 main/penglai/donghai 的 mask | 保留现有三图策略，正规导入并验证其余网格来源，接配置 4、17–21；明确进场/交接落位与上报/外推行为，补场景回归。 |
| 导航生成能力 | `ContractSync` 消费权威产物，现有 Java 查询库不会生成导航 | mask/OBJ 输入、烘焙、逐格/出生点验证、可复现产物另列工具任务。不能把能够加载文件写成会烘焙。 |
| 持久技能与被动/快照 | 旧场景只保存技能 ID，职业引擎已可消费真实重数 | 本次由技能任务负责存档、职业种子、修习、被动属性和真实重数快照；本报告不先记完成。最新 C++ 技能读入口对缺职业/等级已 fail-closed，并已通知该任务。 |
| 生产容量与发布 | Java 频道扩缩存在，生产 Pod 容量预约/归还、迁移 Job、真实发布预检仍未形成完整链 | 不把玩家软预留、频道数或 Compose 当成进程房间容量；沿现有接口实现 Java 容量端口及真实后端。 |
| 巡检和契约治理 | 单次巡检/异常过期观察/积压观察及语义工具已有源码 | 持久 run、分布式单飞、其余规则、完整客户端面及真实历史门禁仍单列；父任务正在接 PlayerState 台账。 |

## 相对上一固定源的新功能差距

最新 `9aaedde23` 比 `993b6da9` 还有以下具体变化，Java 当前受管契约中未找到对应方法或字段；这是额外待移植项，不能用上一固定源的覆盖率冲销。

1. **近期战斗结果与已读**：`proto/scene/player_battle_result.proto`，消息号 371/372；结果与奖励同一份存档提交后可见，最近 10 局，按 result_seq 排序与 battle_id 去重。Java 未有这份客户端契约或其生产入口。
2. **临时背包取回**：`RetrieveTemporaryItems`，消息号 374；单件/全取二选一，全或无搬实例，传送忙碌拒绝，战斗中允许腾出容量。Java 当前 `BagService` 有临时背包落点，尚无该取回入口。
3. **装备强化重放保护**：`EnhanceEquipRequest.expected_level`、`client_request_id` 和应答 `replayed`；同键原参数优先回放、键换参数拒绝。Java 同步的 `player_bag.proto` 尚缺这些字段，不能只在内部加去重后宣称客户端闭环。
4. **服务器轨迹组队跟随**：`TeamFollowC2S / TeamFollowStateS2C`，消息号 381/382；Java `TeamFollowService` 当前是权威成员关系读取与跨场景/远距离归队，不是服务器每 tick 沿队长轨迹驱动。

这些共享客户端变更必须从固定 C++ 已提交对象经正规 `ContractSync` 导入并审查，不能手改受管 proto/消息号，也不需要改 C++。本报告没有运行同步、生成或测试，亦未把 C++ 未提交工作区作为新的事实源。
