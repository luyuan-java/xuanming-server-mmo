# R1a：Java 离线发布原件

本文交付 `game-data` 内的 `ReleaseArtifacts` CLI 与原件库，作为 `full-migration-release-20261026.md` 的 R1 **离线子面**。没有创建 game-ops 新模块，没有引入新库，没有镜像上传、Git tag、部署或合并动作。代码按 Java 21 的 record、NIO、参数化子进程与已有 Jackson 实现。

2026-10-27 已按候选清单 `6b0ee7d6…` 的完整 SHA-256 校验接入独立 Java 工作树：19 个新文件及命名空间守卫的模块身份白名单补丁。静态复审修正了 Windows junction、目录遍历预算、启动 class 头和 JSON 字符串强制转换问题；36 个普通测试、5 个参数化方法尚未编译或运行。仓外候选与集成回执保留，不能套用旧构建结果。

## 基线复核与范围

- 读只读 C++ 基线 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5` 的发布版本语义；`vX.Y.Z[-pre]` 必须整串匹配、数字为 ASCII、主版本不接受前导零。长度上限 115，为未来 `-<sha12>` 镜像 tag 留 13 个字符。此工具不读取或创建 tag，也不检查 registry tag 是否已被占用。
- 当下 Java 是 **17 个常驻 Boot 服务 + robot**。原件恰好含 scene-manager、login、friend、chat、mail、rank、match、team、guild、mall、trade、data、inbox、scene、battle、gate、gateway 的 JAR。robot 计入源码 Boot 模块漂移检查，不作为常驻服务打包。实际模块 pom 集合有增删便拒绝，不能沿用旧规格的 12 进程清单。
- 根 pom 仍是 Java 21、Boot 3.5.16、Maven `0.1.0-SNAPSHOT`，`build-info` 只有 commit / contract 等已有字段；本子面不改 Maven 版本、不补写 JAR 的发布号。`build.release` 若存在必须匹配输入发布版本，缺失时显式记 `unavailable`，`notCovered` 保留该缺口。
- 当前 Dockerfile 已写 OCI version / revision / created / contract 标签及 `/app/BUILD_INFO`。本子面**不读取镜像**，不能把 jar 的 `build.commit` / `build.contract` 当作编译来源的密码学证明，更不能外推它与 OCI 层相同。镜像离线包、签名或可重复构建、registry digest 关联需要后续 R1b。
- 当前迁移文档头为 M14。原件存文档、现有 SQL 及 proto 原字节；`migrationDocumentHead` 仅从文档标题提取，`migrationPlans` 只存人工审查计划引用，不表示该库已经迁移或 persistent-schema ledger 已验证。
- 当前目录 v2 的 epoch / instance / retired 不支持任意混合版本滚动。计划仅接受 `directory-v2-epoch-instance-retired` + `maintenance-all-services`。route generation 必须由计划提供已有的非零规范 UUID；工具不随机生成、不连库写入、不证明旧写者已停止。

## 命令与退出码

沿用已有 game-data Boot JAR 的 PropertiesLauncher 工具入口（运行这些命令前须完成集成构建；本文不是运行证明）：

```powershell
$env:GAME_NAMESPACE = 'release1'
java -Dloader.main=com.game.data.tools.ReleaseArtifacts -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher create --source D:/release/source-clean --spec D:/release/plan.json --output-root D:/release/artifacts
java -Dloader.main=com.game.data.tools.ReleaseArtifacts -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher verify --bundle D:/release/artifacts/v2.10.0 --manifest-sha256 <从可信交付渠道获得的64位SHA256>
java -Dloader.main=com.game.data.tools.ReleaseArtifacts -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher preflight --bundle D:/release/artifacts/v2.10.0 --manifest-sha256 <同一SHA256>
```

命名空间统一经 `GameNamespaceBinder`，`-Dgame.namespace` 优先于 `GAME_NAMESPACE`，与计划必须一致。输出是一行 JSON；错误只输出受控错误代码，不含原路径、DSN、配置值、JSON 原文或异常链。

| 操作 | 结果与退出码 |
|---|---|
| create / verify 成功 | `offline=VERIFIED`、`productionPreflight=UNKNOWN`，退出 0；只说明离线原件自洽 |
| preflight 离线校验成功 | 同样输出 UNKNOWN，**退出 3**，不能作为部署放行信号 |
| 原件、来源、策略或 I/O 失败 | 退出 1；失败不中断回滚到旧版本、不自动清理占号目录 |
| 参数形状错误 | 退出 2 |

create 输出的 `manifestSha256` 必须由独立可信渠道传递；verify 强制要求外部 pin，不接受包内自报摘要充当真实性凭证。持有 pin 的校验可发现交付后的文件篡改；它不证明提供 pin 的人确实编译了所声明源码。

## 输入计划

见 `docs/porting/release-plan.example.json`。示例的提交哈希及 Secret / 迁移引用为占位资料，不能直接用于真实发布。严格 JSON：拒绝重复键、未知字段、缺字段、null、尾随对象及标量强制转换；字符串字段与嵌套 Map 值明确拒绝整数、浮点数和布尔值，不能靠 Jackson 自动转成字符串；文件上限 8 MiB、深度 32、字符串上限 256 Ki 字符。

| 字段 | 含义 |
|---|---|
| format / version | 格式 1；发布号必须对应 CHANGELOG 中唯一且有正文的 `## [X.Y.Z]` 或带日期标题 |
| javaCommit / contractCommit / mapsCommit | 40 位小写提交标识；后两项必须等于受管 `contract/SOURCE.properties` 的 `mmorpg.commit` / `maps.source.commit` |
| namespace / routeGeneration | 注入的部署命名空间与已有 route generation 计划；不读 live DB |
| directoryProtocol / rolloutStrategy | 当前唯一受支持的目录协议与维护窗口全服务升级计划 |
| jars | 恰好 17 个模块到源码根下 JAR 相对路径；目标名固定为 `jars/<module>.jar` |
| settings | 恰好 17 个模块的**选定门禁配置**；未知键拒绝；不是完整运行配置 |
| secretVersions | 固定的基础 Secret 引用集合，仅含版本 ID，不读环境秘密、不检查长度或双方真实注入一致性；完整按功能开关的 Secret 矩阵仍未完成 |
| migrationPlans | 恰好 15 个 SQL 进程的迁移审查引用；不能把引用 ID 当作执行证明 |

选定配置要求 prod；SQL 库名等于 namespace，账号为 `<namespace>_app`，显式 host:port，`sslMode=VERIFY_IDENTITY`、有限 connect/socket timeout，自动初始化关闭。当前相关 TiDB 强读开关为 true；login 的 dev 模式关闭；mall mock-payment 关闭；global match 开启且 require-ti-db=true；scene/data 审计开启，topic generation 一致，RF≥3、minISR≥2 且不超过 RF。生成的 properties 仅加入 `${GAME_MYSQL_PASSWORD}` 占位符，绝不写秘密值。

这些是计划内容的局部约束；Nacos / Spring 高优先级覆盖、Redis/Kafka/TiDB 真连接、全部功能开关、资源预算、进程授权和完整 Secret 引用矩阵没有证据，因此始终 UNKNOWN。配置文件名刻意为 `planned-config`，不会自动启动服务或被现有应用默认加载。

## 来源与文件协议

1. create 只接受源码仓库根、对应 HEAD、干净 `git status --porcelain=v1 --untracked-files=all --ignore-submodules=none`；拒绝 assume-unchanged / skip-worktree 等索引标志。Git 调用使用参数列表、15 秒预算、8 MiB 输出预算，不执行 shell 字符串、不继承 `GIT_DIR` / `GIT_INDEX_FILE` / `GIT_CONFIG_COUNT` 等重定向环境，也不执行 Git 写操作。
2. 从固定 HEAD 列出根 / 模块 pom、CHANGELOG、契约来源、契约资源及 proto、SQL、表、导航、应用 YAML、迁移文档与 Dockerfile。每个入包文件都核对 Git blob SHA-1 与原字节 SHA-256；最多 10,000 项、单文件 16 MiB、来源总量 128 MiB。**不是完整源码归档**，Java 源文件本身不复制。再次采样确认来源未变化后才能封口。
3. output-root 必须预先存在且在源码根之外。`CREATE_NEW` 占用 `<output-root>/<version>`；已有版本永不覆盖。JAR 复制前后核 SHA-256，保存本轮实际复制原件。失败保留未封口目录供人工审查；删除或重试新版本由操作者另行决定。
4. 包内文件路径仅允许受控相对段，拒绝 `..`、空段、反斜杠、冒号、Windows 保留设备名、尾点；逐层读 NOFOLLOW attributes，拒绝符号链接、junction 与其他特殊文件。递归清点以 visitor 流式处理，限制 32 层和 20,000 个文件/目录节点，大小写冲突也拒绝。
5. JAR 最大 512 MiB，ZIP 条目最多 100,000，条目名最多 2,048 字符，声明单项解压尺寸≤512 MiB、声明总解压尺寸≤2 GiB，重复条目及逃逸路径拒绝。读取两个固定元数据条目，每项实际解压≤64 KiB；根 JarLauncher.class 与合法 Start-Class 对应的 BOOT-INF/classes 项必须存在，各只读 8 字节头，核 magic、非 preview、major 45–65。永不提取条目路径到外部。其他条目不解压、不执行，因此没有对全部 class / nested JAR 的 CRC、可启动性或业务正确性作保证。构建元数据 properties 拒绝重复键；测试的最小 class 头也不冒充完整可执行 class。
6. `sha256sums.txt` 覆盖所有 payload 与 manifest；LF、路径序数排序、两空格分隔。manifest 包含每个 payload 的原字节长度 / SHA-256、来源 Git blob、17 项构建声明及固定 notCovered。最后才以 CREATE_NEW 写 manifest 并 force 文件；中断时缺失 / 截断 manifest 均不能 verify 成功。目录 fsync / 断电耐久性不作跨平台承诺。
7. verify 核对外部 pin、规范 JSON 原字节、所有文件集合与摘要、来源域、模块清单、配置、说明、JAR 元数据和精确 checksum 文本；拒绝额外 / 缺失文件与自行增加的 notCovered 结论。受控目录是本协议前提：NIO 检查与打开之间没有跨平台对抗恶意并发替换的目录句柄锁，不能把它当作敌对进程可写目录中的安全沙箱。

## 后续验收边界

源码已提供规则/JSON/DSN、不可变计划、17 服务包、固定外部 pin、重复版本、中断封口、篡改/额外文件、checksum CRLF、路径/ZIP/元数据预算、junction/symlink，以及临时 Git 仓库的脏树/错提交/索引旁路测试。JSON 反例覆盖顶层字符串、嵌套配置和 Secret / 迁移引用的三种标量强转；元数据预算分别覆盖声明超限和中央目录谎报 1 字节、实际解压超 64 KiB。Git 用例只使用 `@TempDir`，隔离用户配置、hooks、签名；没有网络。

候选整理期间没有执行 Maven、Java、容器、服务或这些测试；集成后必须记录实际 clean verify、测试报告及条件跳过项。符号链接测试在系统不授予该能力时显式跳过；junction 用例仅 Windows 执行。静态审查通过不等于编译通过。

尚未覆盖且不能标 R1 全完成：OCI 层 / BUILD_INFO / jar 关联、registry 不可变 digest、可重复构建或签名、CI 及真实客户端、契约语义兼容、持久 schema ledger、live SQL / Kafka、完整运行配置与 Secret / 资源矩阵、真实 workload 身份与停写证据、部署 / 排空 / 回滚、进程容量与 HA/RPO/RTO。R2–R7 保持原库存口径。
