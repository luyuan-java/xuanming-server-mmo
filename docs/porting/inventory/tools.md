# mmorpg 工具链（tools/** + robot/**）功能清单与 Java 版状态

> 基线：mmorpg `26ceb70ca`（稀疏检出，generated/code 等未检出）；Java：xuanming-server-mmo `7ab4d9b` + 工作区（xm-table-codegen 已加入）。
> 状态口径：done / partial / missing / not_applicable。size：S ≈ 300–800 Java 行，M ≈ 800–1500，L ≈ 1500–3000，XL > 3000（应再拆）。

## 区域概述

mmorpg 的工具链分四块：① **表工具** `tools/data_table_exporter`（Python，约 7k 行非模板代码）：权威 schema（`data/schema/*_table.proto`）+ xlsx → json / `.pb` / `manifest.json`、tip 与 operator 枚举、六种语言的表管理器、位序与具名常量，导表期 fail-closed 校验主键 / 外键 / tip 引用；② **协议工具** `tools/proto_generator/protogen`（Go，约 10.5k 行）：消息号 / 事件号发号（`message_id.txt` / `event_id.txt`）、C++ 处理器骨架与注册表、Go 路由表、Go robot / Unity 客户端处理器桩、属性同步代码、DB 建表 SQL；③ **运维 / 开发工具**：合服 `merge_zone`（Go，约 18k 行含测试）、跨区引用巡检、导航网格烘焙（C++/Recast）、战斗美术程序化生成（Go，客户端资产）、`tools/scripts` 下约 24k 行 PowerShell（本地起服、k8s 部署 / 排空 / 回滚、镜像与发布门禁、压测编排、C++ 卫生检查）与第三方补丁；④ **robot**（Go，约 13.5k 行 + 5k 行 logic）：压测 AI、登录场景套件、数据一致性压测与十余个玩法冒烟场景。
Java 版已经用 `tools/ContractSync.java` 接住了「两版共享的契约产物」（proto、消息号 / 事件号、表 schema 与数据、tip / operator 枚举、具名常量），用 `xm-table-codegen`（javac 注解处理器）替代了导表器产出的 Java 管理器，用 `xm-robot` 做了 smoke / movement 两个探针；其余工具基本缺位。
建议原则：**契约生产者只留一个**（导表器、消息号发号器继续在 mmorpg，Java 只消费并做更强的校验），Java 侧把「消费 + 校验 + 代码生成」做成编译期 Java 工具（与 xm-table-codegen 同构），运维工具按 Java 版自己的存储模型重做（单库 `xm_java`，不需要搬库 / 落点那一套）。

## 功能清单

### table-exporter-pipeline — 导表主流程（xlsx + 权威 schema → json / .pb / manifest / 多语言部署）
- mmorpg: tools/data_table_exporter/run.py、core/orchestrator.py、core/table_source.py、core/schema_proto.py、core/excel_reader.py、core/generators/{json_gen,proto_gen,binary_gen}.py、core/file_utils.py（md5_copy / report_orphans）、exporter_config.yaml
- client messages: none（产物 tip_text.json、各表 json 被 Unity / UE 客户端读）
- tables: 全部 34 张有数据的表 + GuildActivity（只有 schema）
- depends on: 权威 schema `data/schema/*_table.proto` + `cfg_options.proto`；protoc；openpyxl / jinja2 / protobuf(Python)
- behavior: 按 sheet 名派发——有权威 schema 走 schema-first（xlsx 第 1 行列名绑定，2–4 行空，5 行中文说明由 schema 注释投影），没有就退回读 xlsx 第 2–5 行旧表头；`cfg_owner` 不是 server/common 的列不进 `.pb`；`0` / `-1` / 空单元格视为「无引用」。流水线：读表 → 外键 → tip 引用 → 主键 → json → proto → operator/tip 枚举 → protoc(C++/Go/Java/C#/Python) → `.pb`（由 json 经 Python pb2 序列化）→ 管理器 / comp / 表 id / 常量 / 位序 → manifest（必须最后）→ md5 部署；任何部署失败或目标树出现源树已无的「陈旧产物」都以非零退出，拒绝报 DONE。
- internal: 产物落 `generated/tables/*.pb|*.json|manifest.json|tip_text.json`；Java 版通过 ContractSync 只取 `.pb` + manifest + tip_text.json。Java 若要「Java 版导表器」，它必须成为两版**唯一**的生产者（替换 Python），否则表数据出现两个真源，违背「共享契约不许分叉」；验收判据可直接复用 `tools/sandbox_export.py` 的逐字节比对。设计草案：Java 21 单模块 CLI（xlsx 读用 Apache POI 或 EasyExcel，需先按 §2 查 star）、schema 用 protoc 描述符集（与 xm-table-codegen 共用 TableSchemaReader）、`.pb` 用 DynamicMessage 直接写（省掉 json→pb2 中转）、模板换成 Java 代码生成器。
- java: not_applicable — Java 消费上游产物（tools/ContractSync.java `syncTableData`），不自建导表；若用户坚持 Java 版导表器，属于 mmorpg 侧改造（XL，需拆 读表 / 校验 / 序列化 / 多语言生成 / 部署 五块）
- size: XL
- robot: none（robot 启动时读 Skill / Class 表 json 自动取 skill_ids）
- hazards: ① `.pb` 是 json 经 pb2 `json_format` 转出来的，json 的数字 / 文本互认规则决定 `.pb` 内容，换实现必须逐字节对比；② `core/state/**` 与 `mapping/**` 是与权威 `state/**` 分叉的陈旧副本（tip_enum_ids.json 三份 md5 互不相同），只有 `exporter_config.yaml: state_dir: ./state` 生效，误用会重新发号；③ 部署是 md5 覆盖不删除，布局变化后旧文件靠 report_orphans 报错而不是自动清理；④ C#/Python/UE 产物也从同一流水线出，Java 版改动导表器会波及客户端。

### table-typed-access-codegen — 表访问代码生成（主键 / 二级键 / 多值键 / 索引）
- mmorpg: tools/data_table_exporter/core/generators/config_gen.py、templates/{cpp,go,java,csharp,python,ue}_config*.j2、*_all_table.*.j2
- client messages: none
- tables: 全部
- depends on: table-exporter-pipeline（schema）、contract-sync
- behavior: 生成 `FindById` / `FindAllById`（主键 `cfg_multi`）/ 二级键 Map / 多值键 / 索引；生成代码注释写明「只存 id，不存行指针」——C++ 旧快照在 `Load()` 时析构，存指针即野指针。
- internal: Java 已不再同步导表器的 83 个 Java 管理器（全局单例、非 volatile 快照），改为 `xm-table-codegen`（注解处理器读 protoc 描述符集）生成 `ConfigTables` 不可变快照 + `<Sheet>Rows`；返回值不可变、`Optional` / `List`；`cfg_multi` 主键编译期就没有取单行方法。
- java: done — xm-table-codegen/src/main/java/com/game/table/codegen/{ConfigTableProcessor,TableSchemaReader,TableSourceGenerator,JavaNames,TableSchema}.java（≈900 行）；docs/design/config-tables.md
- size: M
- robot: none
- hazards: 二级唯一键 `cfg_key` 重复时 C++ `emplace` 先写胜出、Go 后写覆盖，两端不一致（测试表 TestMultiKey 的 string_key 有重复 aa / bb）；Java 取表序第一行并告警（与 C++ 一致）。Java 生成器尚未处理 `cfg_expr_*`、`cfg_bit_index`、`cfg_tip_ref`、`cfg_composite`（见各自条目）。

### table-load-validation — 表数据校验（主键 / 外键 / manifest / 未知字段）
- mmorpg: core/primary_key.py、core/foreign_key.py（导表期，失败 `sys.exit(1)` 且产出不落盘）；加载端无校验
- client messages: none
- tables: 全部；外键关系见 schema 的 `cfg_fk` / `cfg_gfk`
- depends on: table-typed-access-codegen
- behavior: 主键重复且未声明 `cfg_multi` → 失败；外键：目标表 / 列不存在 → error，取值找不到 → error，目标表无行 → warning；`0`、`-1`、空串不参与；数字与文本互认。
- internal: Java 在运行时 `ConfigTables.load` 再做一遍（纵深防御）：manifest 存在且格式对、每张表文件 sha256 与行数对得上、行里没有 schema 未声明字段、manifest 里没有 schema 不认识的表、主键唯一、外键全部命中（失配列前 50 条），任一不过抛 `TableLoadException`，不返回半成品；schema 有而 manifest 无的表按空表 + 告警。
- java: done — xm-table/src/main/java/com/game/table/load/{TableSource,TableIndexes,ForeignKeyProblems,TableLoadException}.java；单测 xm-table/src/test/java/com/game/table/ConfigTablesTest.java
- size: M
- robot: none
- hazards: mmorpg 导表器不校验 `cfg_key` 唯一（PARITY 已登记 mmorpg 待做）；mmorpg 的 Go / C++ 加载端不校验 sha256 / 行数，Java 是唯一在加载时拦「半新半旧」的一端。

### table-manifest-batch-identity — 表批次身份（version / content_digest 上报与跨进程一致性）
- mmorpg: core/manifest.py（产出 `generated/tables/manifest.json`：schema_version、单调 version、content_digest、source_rev{commit,data_dirty}、每表 rows + 源表 sha256 + 产物 sha256）
- client messages: none
- tables: 全部
- depends on: table-load-validation
- behavior: 内容不变则 digest 不变、version 不动、文件不重写；内容一变 version+1。readme 建议「加载端启动时上报 version + content_digest，两端 digest 不同即判半新半旧、拒绝进入对局」——mmorpg 各端尚未实现。
- internal: Java 已用 manifest 逐文件校验 sha256 / 行数，但**不读 version / content_digest**，各 Java 进程（gate / login / scene-manager / scene）之间无法发现「各自加载了不同批次」。Java 待做：`ConfigTables.batch()` 暴露 version + digest；启动日志打印；Micrometer info 指标（`xm_config_tables_info{version,digest_prefix}`，低基数）；gate↔scene 链路握手 `LinkHello` 带 digest 前 16 位，不一致记 ERROR（是否拒链待定）。
- java: partial — 校验在 xm-table/src/main/java/com/game/table/load/TableSource.java；version / content_digest 未使用（grep `content_digest` 无结果）
- size: S
- robot: none
- hazards: `source_rev.data_dirty` 取不到按 true；`generated_at` 不在 digest 里，不能用它判断新旧。

### table-expression-columns — 表达式列（cfg_expr_type / cfg_expr_param）
- mmorpg: templates/cpp_config.h.j2（`ExcelExpression<T>`、`Get<Col>(tableId)` / `Set<Col>Param(...)`）、cpp/libs/engine/config/table_expression.h（exprtk）；Go 模板不生成表达式访问
- client messages: none（结果影响伤害 / 回血，经战斗推送间接可见）
- tables: Skill.damage（`100*level` / `1000*level` / `10000*level`，参数 level）、Buff.health_regeneration（`0.013*level*health`，参数 level、health）、Buff.bonus_damage（常量串，如 `66`）
- depends on: table-typed-access-codegen；技能结算 / buff 系统（Java 未做）
- behavior: 字符串列存公式，按声明的参数名求值，返回 `cfg_expr_type`（double）；表里写常量也走同一求值；exprtk 额外注册了 `random()`（[0,1]，全局 `rand()`）。
- internal: Java 生成器目前只把这些列当普通 string 暴露。设计：xm-table 内手写一个小型表达式编译器（递归下降，支持数字字面量、+ − * / % ^、括号、一元负号、声明过的参数名、白名单函数 min/max/abs/floor/ceil/random），`ConfigTables.load` 时把每行公式预编译成不可变 AST，**解析失败或引用未声明参数即加载失败**；codegen 生成无状态访问器 `double damage(SkillTable row, double level)`（参数按 `cfg_expr_param` 顺序成为方法形参）。`random()` 需要注入 RandomGenerator，不用全局随机。不引入 SpEL（能力过大，且语义与 exprtk 不同）。用 mmorpg 现有数据做 C++ / Java 求值对拍单测。
- java: missing — xm-table-codegen 不认 `cfg_expr_type` / `cfg_expr_param`（TableSchemaReader 无对应分支）
- size: M
- robot: none
- hazards: C++ 侧 ① `Value()` 每次调用都重新 `parser.compile`，热路径上解析公式；② 编译失败不检查，返回值未定义；③ `SetParam` 参数个数不符时静默忽略，沿用上一次的参数；④ 先 `Set...Param` 再 `Get...` 的两步 API 共享快照里的可变状态，不可重入、非线程安全；⑤ Go 完全不支持表达式列，Go 服务读到的是原串。

### table-bit-index — 位序（存档位图下标，cfg_bit_index）
- mmorpg: core/generators/bit_index_gen.py、templates/{cpp_bit_index.h,go_bit_index.go}.j2、state/mapping/table_index_mapping/{mission,reward}_mapping.json；消费方 cpp/libs/modules/mission/comp/mission_comp.h（`std::bitset<kMissionMaxBitIndex>`）、reward_comp.h（`RewardBitset`）
- client messages: none（任务 / 奖励领取状态经各自协议间接可见）
- tables: Mission.id（17 条，1→0 … 17→16）、Reward.id（7 条，1→0 … 7→6）
- depends on: table-exporter-pipeline；任务 / 奖励系统（Java 未做）
- behavior: id → 位序只增不改、永不复用；删掉的 id 在 mapping 里保留占位；新 id 追加到最大位之后；状态文件缺失 / 损坏 / 重复位一律中止导表，只有 `--bitindex-bootstrap`（表从未发布过）才允许从空初始化；位图容量 = max(表内最大位序, 已分配最大位 + 1)，只增不减。
- internal: Java 有自己的库，但位图语义同样「一经落库不可变」。最省事且安全的做法是复用上游 mapping：ContractSync 增加同步 `tools/data_table_exporter/state/mapping/table_index_mapping/*.json` → `xm-table/src/main/resources/contract/bit_index/`；xm-table-codegen 对标了 `cfg_bit_index` 的表生成 `MissionBitIndex.of(id)` / `capacity()`；加载期校验表里每个 id 都有位序、位序无重复。Java 存档里的位图（BitSet → bytes）以此为下标。
- java: missing — Java 未同步 mapping，codegen 无位序（grep `bit_index` 在 Java 代码中无结果）
- size: S
- robot: none
- hazards: 若 Java 自己按行序推位序，删一行就整体错位（静默改变存量玩家的任务完成 / 奖励领取状态）；mapping 的三个副本（state / core/state / mapping）目前一致，但只有 state/ 生效。

### table-tip-ref-validation — 表内 tip 码引用校验（cfg_tip_ref）
- mmorpg: core/generators/enum_gen.py `validate_tip_references`（导表期、生成前 fail-closed）
- client messages: 间接——表里的 tip 码最终出现在 `TipInfoMessage.id`（如 23 SceneClientPlayerCommonSendTipToClient、各应答的 error_message）
- tables: MessageLimiter.tip_message、SkillPermission.skill_type（`cfg_tip_ref`）；以及所有字段名含 `tip` 的数值列（自动纳入）
- depends on: tip-code-axis、table-load-validation
- behavior: 表内每个 tip 引用必须是 0 或一个已分配的 tip 码，否则整批不产出；防止 tip 码轴重排后表里的旧数字静默变成 unknown。
- internal: Java 加载期没有这条校验（只信上游）。设计：xm-table-codegen 识别 `cfg_tip_ref` 与名字含 tip 的数值列；`ConfigTables.load` 用同步来的 tip 枚举描述符（`xm-table/src/main/proto/tip/*.proto` 生成的类）建码集合，失配即 `TableLoadException`。gate 的限频表（TableMessageLimits）直接用 tip_message 回客户端，是现有消费点。
- java: missing — TableSchemaReader 不处理 `cfg_tip_ref`
- size: S
- robot: none
- hazards: 「字段名含 tip 自动纳入」是隐式规则，Java 若只认 option 会漏掉名字约定的列；两端口径需一致。

### tip-code-axis — tip 码轴（分段发号、文案、故障分类）
- mmorpg: core/generators/enum_gen.py（`generate_tip_enums`、`_assign_tip_ids`、`_check_tip_axis`、`_generate_tip_segments` / `_generate_tip_faults` / `_generate_tip_text`）、templates/{tip_enum.proto,tip_segments.go,tip_faults.go}.j2、state/mapping/tip_enum_ids/tip_enum_ids.json、data/tip/Tip.xlsx；消费 go/shared/serverbase/tipcode.go（`TipVerdict`：OK / Unknown(码表漂移) / Fault / BizReject）
- client messages: 所有带 `TipInfoMessage` 的应答与 23 推送（客户端按 id 查文案）
- tables: Tip.xlsx（组头 `//common_error base=1000 width=1000`；`fault` 列）、产物 tip_text.json
- depends on: table-exporter-pipeline
- behavior: 每组声明段 base/width，只在本组段内找空位，段满中止；state 只增不减（删行不回收号）；自检：段不重叠、码不越界、已发号不变、枚举名全局唯一（tip proto 无 package）、码全局唯一；`fault` 列必须存在且取值闭集，标 1 的码是服务端内部故障（只影响告警分级，不进 state）；tip_text.json = 码 → 中文文案。
- internal: Java 已同步 tip 枚举 proto（`com.game.table.*ErrorTip`，如 gate 的 `CommonErrorTip.common_error.kServiceUnavailable_VALUE`）与 tip_text.json；**没有**段表 / 故障表。Java 待做：导表器另出一份语言无关的 `tip_meta.json`（段 + fault 集合）由 ContractSync 同步，或 ContractSync 从 Go 产物 `faults.go` / `segments.go` 解析（较脆）；Java 侧做 `TipVerdicts.classify(code)`，供 Dubbo 调用日志 / 指标按 ok / biz_reject / fault / unknown 打低基数标签。
- java: partial — 枚举与 tip_text.json 已同步（tools/ContractSync.java `syncTableEnums`、`syncTableData`）；tip_text.json 在 Java 代码中无消费者；故障 / 段分类 missing
- size: S
- robot: Go robot 断言具体 tip 码（如 attribute-smoke 的 kAttributeNothingToChange）
- hazards: tip 码是客户端可见契约，Java 永远不能自己发 tip 码；scene_manager / data_service 有各自从低位独立发号的私有码表，不能用 TipVerdict 判。

### operator-enums — Operator 枚举（Operator.xlsx → *_operator.proto）
- mmorpg: core/generators/enum_gen.py `generate_operator_enums`、templates/operator_enum.proto.j2、state/operator/id_pool.json
- client messages: 间接（ability / bag / scene 三组操作码，出现在资产流水等 proto 字段里）
- tables: Operator.xlsx（第 18 行起，`//组名` 分组）
- depends on: table-exporter-pipeline
- behavior: 每个枚举名首次出现时取 `max(已有)+1`，id 跨组共用一个池。
- internal: Java 原样同步 `xm-table/src/main/proto/operator/{ability,bag,scene}_operator.proto`。
- java: done — tools/ContractSync.java `syncTableEnums`
- size: S
- robot: none
- hazards: operator 的 state 读取是 **fail-open**：`_load_json` 在文件损坏时只记 error 返回 `{}`，随后整池重新从 1 发号并覆盖 state 文件——与 tip 轴的 fail-closed 不一致；若这些号已写进流水 / 审计库，含义会静默改变。建议 mmorpg 改为与 tip 轴同样严格。

### table-named-constants — 具名行 id 常量与表 id 枚举（constants_name 列 / constant_tables）
- mmorpg: core/generators/constants_gen.py、core/generators/table_id_gen.py、templates/{cpp,go,java}_constants.*.j2；`exporter_config.yaml: constant_tables: [GlobalVariable]`
- client messages: none
- tables: GlobalVariable、ActorActionState、ActorActionCombatState（有 constants_name 列的表）
- depends on: table-exporter-pipeline
- behavior: xlsx 的 `constants_name` 列（策划列，不进 `.pb`）给行 id 起名，生成 `constexpr uint32_t kGlobalVariable_kAbnormalLogout = 1;` 一类常量；GlobalVariable 另出逐行表 id 枚举。
- internal: Java 由 ContractSync 用正则解析 C++ 产物 `cpp/generated/table/code/constants/*.h` 生成 `com.game.table.TableConstants`（9 个常量），非单行 / 不认识的行即报错。改进项：让导表器出语言无关的 `constants.json`，ContractSync 不再解析 C++ 头文件（C++ 模板一改格式 Java 同步就断）。
- java: done — xm-table/src/main/java/com/game/table/TableConstants.java（同步产物）、tools/ContractSync.java `syncTableConstants`
- size: S
- robot: none
- hazards: 常量来源是 xlsx 的非 schema 列，不受权威 schema 字段号约束；Java 依赖 C++ 生成格式（`constexpr uint32_t k<表>_<名> = N;`）。

### table-ecs-comp-gen — 表列组件生成（C++ ECS 组件 / Go comp / Java comp record）
- mmorpg: core/generators/comp_gen.py、templates/{cpp_table_comp.h,go_table_comp.go,java_table_comp.java}.j2；`TableSchema.scalar_comp_columns` / `repeated_comp_arrays`
- client messages: none
- tables: 所有有服务端标量列 / 标量数组的表
- depends on: table-typed-access-codegen
- behavior: 每个服务端标量列生成一个单值组件、每个标量数组生成组件 + 值索引，供 C++ entt 直接挂到实体上。
- internal: Java 场景用普通对象 + 组件表、不引入 ECS 库（tech-stack.md「ECS 不引入」），行对象本身不可变可直接引用 id。
- java: not_applicable — Java 不需要；导表器的 `_validate_java_code_outputs` 还在要求 `*TableComp.java` 存在于 mmorpg 的 java/config_node 部署目录，与本仓库无关
- size: S
- robot: none
- hazards: mmorpg 的 Java 产物（java/config_node）与本仓库的 Java 版是两回事，不要混用。

### table-pb-inspect — 表数据查看 / 对比工具（pb2json）
- mmorpg: tools/data_table_exporter/pb2json.py（`.pb` → 可读 JSON，单表 / 全部）
- client messages: none
- tables: 全部
- depends on: table-typed-access-codegen
- behavior: 运维 / 策划排查「线上加载的到底是什么数据」。
- internal: Java 设计：xm-table 加一个无 Spring 的命令行入口 `java -cp xm-table.jar com.game.table.tool.TableDump <Sheet|--all> [--dir config-data/tables] [--diff <另一个目录>]`，复用 `ConfigTables.load` 的校验（先校验再输出），用 protobuf-java-util `JsonFormat` 输出；`--diff` 按主键逐行比较两批数据（发版前看改了哪些行）。
- java: missing
- size: S
- robot: none
- hazards: 直接读 `.pb` 不经 manifest 校验会把损坏文件当正常数据展示，入口必须走 load 校验。

### table-multi-client-outputs — 客户端表产物（C# / UE / Python 管理器）
- mmorpg: templates/{csharp,ue,python}_*.j2、exporter_config.yaml `languages.csharp/ue/python`（C# 部署到 ../mmorpg-client/Assets/Scripts/Table/Generated）
- client messages: none
- tables: 全部（按 owner 过滤）
- depends on: table-exporter-pipeline
- behavior: Unity 客户端读 C# 管理器 + proto；UE 读 json（不引 protobuf）；Python 暂不部署。
- internal: 客户端是两版共用的，表产物只能来自一个生产者。
- java: not_applicable — 客户端侧
- size: L
- robot: none
- hazards: Java 若自建导表器，也必须产出 C# / UE 产物，否则客户端与服务端表不同源。

### table-schema-migration-tools — 表结构迁移 / 索引 / 沙盒验收工具
- mmorpg: tools/data_table_exporter/tools/{bootstrap_schema,verify_schema_parity,strip_header_rows,gen_schema_index,sandbox_export}.py、migrate_xlsx.py、tools/scripts/{friend_xlsx_patch,guild_b5a_xlsx_patch}.py、smoke_test.py、tests/
- client messages: none
- tables: 全部
- depends on: table-exporter-pipeline
- behavior: 2026-09-03 已完成「xlsx 表头 → 权威 schema」迁移（bootstrap / parity / strip 三件是一次性工具）；gen_schema_index 维护 data/AGENTS.md 的表索引（`--check` 防腐）；sandbox_export 是导表器改造的长期验收判据（沙盒导一次，与仓内产物逐字节比，对仓库只读）；两个 xlsx_patch 是一次性改表脚本。
- internal: Java 侧对应物是 `java tools/ContractSync.java --check`（只比对同步产物）与 xm-table 单测。
- java: not_applicable — 上游一次性 / 上游专用；若将来 Java 版导表器替代 Python，sandbox_export 的比对口径要一并移植
- size: M
- robot: none
- hazards: tools/AGENTS.md 引用的 `tools/scripts/chaos_test.ps1` 在仓库中不存在（文档腐坏）。

### message-id-allocation — 消息号发号（message_id.txt）
- mmorpg: tools/proto_generator/protogen/internal/generator/cpp/service_register_info.go（`ReadMessageIdFile` / `InitMessageId` / `WriteMessageIdFile`）、internal/message_id.go（Go 常量）、proto/message_id.txt（244 行，0–243 连续）
- client messages: 全部（消息号本身就是客户端契约；Unity 客户端的 HandlerRegistry 与 Go robot 的 message_id.go 都把号编进代码）
- tables: none
- depends on: 全部 proto 服务定义
- behavior: 键 = 服务名 + 方法名；文件里已有的键保持原号；新方法优先填 [0, 方法总数) 内的空洞，再追加到 max+1；写回时只写当前存在的方法。
- internal: Java 只读不发号：`MessageIdRegistry`（xm-proto）运行时读 `contract/message_id.txt` + `contract/contract.desc`，按「服务裸名 + 方法」对号；robot / scene / gate 全部用 `requireId(服务, 方法)` 启动时解析，不写死数字。
- java: not_applicable — 发号器留在 mmorpg（单一生产者）；消费侧 done（xm-proto/src/main/java/com/game/contract/MessageIdRegistry.java）
- size: S
- robot: Go robot 用生成的 `game.<Service><Method>MessageId` 常量
- hazards: ① **删除的方法号会被复用**：WriteMessageIdFile 不写已删方法，下一轮它变成空洞，新方法捡走——旧客户端发旧号会落到新方法上（事件号有墓碑，消息号没有）；② 多个空洞配多个新方法时，填洞顺序取决于 Go map 迭代（随机），同一批 proto 两次生成可能得到不同号；③ message_id.txt 同时登记了服务器内部 gRPC（如 `0=KVCompact`、`3=dbTest`），号空间与客户端共用。Java 侧对策见 contract-change-report。

### event-id-allocation — 事件号发号（event_id.txt，含墓碑）
- mmorpg: protogen/internal/generator/cpp/event_id.go（514 行 + 387 行测试）、event.go（C++ 事件处理器生成）、internal/message_id.go `WriteGoEventId`；proto/event_id.txt（49 行，当前无墓碑）
- client messages: none（服务端内部 Kafka / 进程内事件）
- tables: none
- depends on: proto 事件定义（含 `package contracts.kafka;` 的 Kafka 契约事件）
- behavior: 事件删除 / 改名后原号写成墓碑 `N=reserved:<原名>`，永不复用；一轮新增墓碑超过 4 个即中止（多半是事件目录配空），`PROTOGEN_ALLOW_MASS_EVENT_TOMBSTONE=1` 才放行；`contracts_kafka` 目录没配而文件里有 Kafka 活事件 → 中止，避免把它们全部转墓碑。
- internal: Java 已同步 `xm-proto/src/main/resources/contract/event_id.txt`（SOURCE.properties 记 sha256），但没有消费者——Kafka 在 Java 版是「后续批次」。Java 接 Kafka 时需要：解析 event_id.txt（墓碑行跳过但占号）→ `EventIdRegistry`（号 ↔ 消息类型），Spring Kafka 监听器按号派发；与 client-handler-codegen 同一个注解处理器可顺带生成事件号常量。注意 Java 版不与 C++/Go 混部，Kafka 主题与事件号只在 Java 内部用，是否沿用 mmorpg 事件号是 Java 自己的决定（沿用最省事）。
- java: partial — 文件已同步（tools/ContractSync.java），无解析 / 派发
- size: S
- robot: none
- hazards: 墓碑行若被当成普通行解析，会生成非法常量（Go 侧曾因此全编译失败）；Java 解析器要显式识别 `reserved:` 前缀。

### contract-sync — 契约同步工具（Java 版专有，ContractSync.java）
- mmorpg: 无对应（mmorpg 是契约生产方）；Java 版 tools/ContractSync.java（553 行，JDK 21 单文件运行）
- client messages: 全部客户端可见 proto 与消息号
- tables: 全部 schema、数据、tip / operator 枚举、具名常量
- depends on: message-id-allocation、table-exporter-pipeline（上游产物）
- behavior: 同步 `proto/**`（排除 proto/etcd/）只注入 java_package / outer_classname / multiple_files；权威表 schema 的非服务端列改写成 `reserved`（多行声明直接报错）；tip / operator proto 原样；C++ 常量头 → TableConstants；message_id.txt / event_id.txt；`.pb` + manifest.json + tip_text.json；写 `contract/SOURCE.properties`（commit、文件计数、两个注册表的 sha256）；`--check` 在临时目录重放同步并逐文件比对，不一致退出码 1；文本统一 LF。同步后必须 `./mvnw clean install`（protoc 增量生成不清旧类）。
- internal: 已是 Java 版协议 / 表工具的入口。优化方向见 contract-change-report、contract-tamper-check；另需扩展同步面：位序 mapping（table-bit-index）、tip 段 / 故障元数据（tip-code-axis）、导航网格 `data/scene_nav_bin/*.bin`（navmesh-bake-and-query）。
- java: done — tools/ContractSync.java、contract/SOURCE.properties
- size: M
- robot: none
- hazards: `--check` 需要 mmorpg 工作树，Java 仓库没有 CI（无 .github/），目前全靠人工跑；`clean install` 这一步不做会让删除 / 改名的 proto 类残留并掩盖未迁移的 import（AGENTS.md §4 已记 2026-09-29 实例）。

### contract-change-report — 契约变更报告与兼容性闸（Java 版新增）
- mmorpg: 无对应（mmorpg 没有任何「消息号含义变更」检测；proto 兼容性靠人工）
- client messages: 全部
- tables: 全部 schema（字段号 / 类型）
- depends on: contract-sync、message-id-allocation
- behavior: 每次同步前后对比，输出人能读的报告并对危险变更 fail-closed：① 消息号含义变更（同一号从 A.方法 变成 B.方法 = 上游复用了已删方法的号）→ 默认失败，需 `--accept-id-reuse <号>` 显式放行；② 方法删除 / 新增 / 请求应答类型变更；③ proto 线格式不兼容：同一消息字段号改类型、字段号被复用、`reserved` 被删除；④ 表 schema 字段号复用、主键 / 外键变更；⑤ 表数据按主键的行级增删改计数。
- internal: 设计：ContractSync 增加 `--report` 阶段，用新旧两份 FileDescriptorSet（protoc `--descriptor_set_out` 已在 xm-proto 构建里产出 `contract/contract.desc`；旧的取 git HEAD 版本或同步前的副本）做纯 Java 描述符 diff（protobuf-java 自带 `DescriptorProtos`，不需第三方）；表数据复用 TableDump 的逐行 diff。报告写到标准输出 + `contract/CHANGES.md`（追加），PARITY 版本基线行可直接引用。
- java: missing
- size: M
- robot: none
- hazards: 上游消息号复用是确定存在的机制（见 message-id-allocation），Java 版目前只能在运行时「按新表解析」，复用不会被任何一步发现。

### contract-tamper-check — 契约产物防手改（离线自校验）
- mmorpg: 无对应
- client messages: 全部（受保护的是同步产物）
- tables: 全部同步产物
- depends on: contract-sync
- behavior: AGENTS.md §1 规定同步产物不许手改，但目前只有带 mmorpg 工作树的 `--check` 能发现手改；没有 mmorpg 的机器 / CI 上手改 proto 或 `.pb` 不会被任何构建步骤拦下（`.pb` 有 manifest sha 兜底，proto / message_id / TableConstants 没有）。
- internal: 设计：ContractSync 在 SOURCE.properties（或旁边的 `contract/MANIFEST.sha256`）里记录每个受管文件的 sha256；xm-proto 加一个单测（或 Maven `validate` 阶段的小插件）重算比对，不一致即构建失败并提示「重新同步，不要手改」。纯 JDK（MessageDigest），约 150 行。
- java: missing — SOURCE.properties 只记 commit、计数与两个注册表的 sha256
- size: S
- robot: none
- hazards: 记录的 sha 必须基于 LF 规范化后的字节（ContractSync 已统一写 LF），否则 Windows 检出 CRLF 会误报；`.gitattributes` 需对这些路径固定 `eol=lf`。

### client-handler-codegen — 客户端消息处理与分派代码生成（Java 版 xm-proto-codegen）
- mmorpg: protogen internal/{handler_gen,register_gen,player_service_gen,replied_handler_gen,grpc_handler_gen,code_parser}.go（C++ 处理器骨架、按标记保留手写代码段、注册表与 player service 实例表）、internal/generator/go/{robot_handler,robot_case}.go（Go robot 每个 S2C 一个处理器文件 + 总 switch）、internal/generator/unity/unity_client_handler.go（Unity partial class 桩 + HandlerRegistry，带消息号）
- client messages: 全部客户端服务（23 个 proto 文件含 `OptionIsClientProtocolService`，18 个客户端服务、14 个 `OptionIsPlayerService`）
- tables: none
- depends on: message-id-allocation、contract-sync
- behavior: 客户端可见行为不变；生成物保证「每个方法都有处理入口、号与类型一一对应」。
- internal: Java 现状：scene `ClientRequestHandler.onClientForward` 是按消息号的 if-else 链（`ids.moveSync()` …），号由 `SceneMessageIds` / robot `MessageIds` 在启动时用字符串 `requireId("SceneSkillClientPlayer","ListSkills")` 解析，拼错到启动才发现；login 有自己的 dispatcher；新方法默认回 1006 / 丢弃。设计（与 xm-table-codegen 同构）：新模块 `xm-proto-codegen`（javac 注解处理器，编译期读 `contract.desc` + `message_id.txt`）生成 ① `ClientMessageIds`：每个方法一个 `public static final int`（javadoc 带服务 / 方法 / 请求应答类型），契约里删掉的方法即编译失败；② 每个客户端服务一个处理器接口（如 `SceneSkillClientPlayerHandler { ListSkillsResponse listSkills(PlayerCall c, ListSkillsRequest r); }`，Empty 应答方法返回 void）；③ 按服务生成 `switch(messageId)` 分派器（解析请求体 → 调接口 → 按应答类型决定回不回包）；④ 处理器实现类标 `@ClientService` 注解，处理器在编译期检查「每个 player service 方法要么有实现，要么显式列在 `@Unsupported`」；⑤ 同一套描述符给 xm-robot 生成类型化客户端 API（`client.listSkills(req)` 返回 `CompletableFuture<ListSkillsResponse>`）与推送分派。号仍以同步来的 message_id.txt 为唯一来源，只是从「运行时解析」挪到「编译期生成」，契约同步后 `clean install` 即刷新。
- java: missing — 现为手写：xm-scene/src/main/java/com/game/scene/world/ClientRequestHandler.java、xm-login/src/main/java/com/game/login/dispatch/ClientMessageDispatcher.java、xm-robot/src/main/java/com/game/robot/client/MessageIds.java
- size: L
- robot: xm-robot 全部场景（改用生成的类型化 API）
- hazards: 消息号会漂移（上游复用空洞），生成常量只能来自本次同步的 message_id.txt，不能手抄进源码；C++ 生成器用「标记之间的手写代码段」回写同一文件，Java 不要照搬（生成接口 + 手写实现类，生成物只进 target/generated-sources）。

### client-route-table — 客户端消息路由表（消息号 → 后端）
- mmorpg: protogen internal/route_table.go（生成 go/client_rpc_router/generated/pb/game/route_table.go：message_id → gRPC 全限定方法、目标节点类型、是否客户端协议；跳过 DB 域与 cc_generic_services 的 Scene 服务）
- client messages: 全部非 scene 客户端服务（登录、好友、公会、组队、交易、聊天、战斗等）
- tables: none
- depends on: client-handler-codegen、message-id-allocation
- behavior: gate 收到客户端包后按表把原始字节转给对应服务；不在表里的号拒绝。
- internal: Java gate 用 `MessageRoutes.of(registry)` 运行时建表：`OptionIsPlayerService` → scene；其余查手写 `SERVICE_BACKENDS`（目前只有 `ClientPlayerLogin` → login Dubbo group）；没有的回「服务不可用」。每接一个新后端要改这张 Map。可并入 xm-proto-codegen：在 proto 的服务级 option 或一个 Java 侧的 `routes.properties`（服务名 → Dubbo group）上生成并在编译期检查覆盖率。
- java: partial — xm-gate/src/main/java/com/game/gate/session/MessageRoutes.java（手写 Map，按服务语义路由）
- size: S
- robot: none
- hazards: mmorpg 的路由服按 proto 目录 / 节点类型选目标，Java 规定按服务语义，不看目录（architecture.md §1），这条差异要保持。

### attribute-sync-codegen — 属性同步代码生成（OptionAttributeSync）
- mmorpg: protogen internal/generator/cpp/options/message_table.go（扩展号 700000 `OptionAttributeSync` 的回调）、internal/template/attribute_sync.{h,cpp}.tmpl → cpp/libs/services/scene/generated/attribute/；proto/scene/player_state_attribute_sync.proto（6 个带该 option 的消息）
- client messages: 66 ScenePlayerSyncSyncBaseAttribute（ActorBaseAttributesS2C）、65 SyncAttribute2Frames、55 SyncAttribute5Frames、82 SyncAttribute10Frames、68 SyncAttribute30Frames、75 SyncAttribute60Frames（S2C push）
- tables: none
- depends on: 视野（AOI）、属性系统
- behavior: 每个标了 option 的消息生成「字段号 → 脏位」的 bitset（大小 = 最大字段号 + 1）与按脏位拼消息的代码；按 2 / 5 / 10 / 30 / 60 帧档位分频下发。
- internal: Java 手写了 66 的脏字段同步（ScenePlayer，偶数帧、只带脏字段、发给看得见的人），其余 5 档（65/55/82/68/75）Java 从不发（PARITY 已记）。设计：在 xm-proto-codegen 里对带 `OptionAttributeSync` 的消息生成 `<Msg>DirtyTracker`（`long`/`BitSet` 脏位、`markX()`、`buildDelta(source)`、`clear()`），按字段描述符生成，不用运行时反射；分频调度仍由 scene 手写。只有当 Java 开始发 65/55/82/68/75 时才值得做。
- java: partial — 66 手写于 xm-scene/src/main/java/com/game/scene/world/ScenePlayer.java；其余 5 档未实现、无生成器
- size: M
- robot: xm-robot movement 断言 66 的内容与收件人
- hazards: 生成器以「最大字段号 + 1」定 bitset 大小，字段号稀疏时浪费但正确；Java 若用 `long` 存脏位，字段号 > 63 要退回 BitSet。

### db-schema-codegen — 玩家存储表结构与加载代码生成
- mmorpg: protogen internal/generator/go/db_model.go（`GenerateDBResource`：用 github.com/luyuancpp/proto2mysql 从 DB proto 生成 `mysql_database_table.sql` 写进每个 Go gRPC 服务的 model 目录；`generated/data/mysql_database_table_list.json` 供 merge_zone 使用）、internal/generator/cpp/player_data.go + player_data_loader.{h,cpp}.tmpl（`OptionIsPlayerDatabase` 消息 → C++ 玩家数据加载器）
- client messages: none
- tables: none（proto/common/database/*.proto）
- depends on: 玩家持久化
- behavior: proto 消息 = 一张表（主键 / 列由 proto2mysql 推导），玩家数据整块按消息存取。
- internal: Java 版自有库 `xm_java`：手写 `xm-player-store/src/main/resources/db/xm-player-schema.sql` + MyBatis Mapper，存量库迁移写在 docs/design/db-migrations.md（M1、M2）。Java 惯用做法不从 proto 自动生成 DDL（列类型 / 索引需要人决定，迁移要可审计）；如需迁移工具，Flyway / Liquibase 都不到 2 万 star，按 §2 应继续手写版本化 SQL，或在 tech-stack.md 写明偏离理由。
- java: not_applicable — 按 Java 方式手写（xm-player-store）；玩家数据增长后的「组件 blob 存储」设计属于持久化区域
- size: M
- robot: none
- hazards: proto2mysql 由 proto 推导表结构，proto 字段改名 / 改类型会直接改 DDL，生产库只能靠人工迁移；merge_zone 依赖它产出的表清单，表清单与真实库不一致时合服会漏表。

### proto-gen-native-internals — proto-gen 的 C++ / Go / Unity 内部产物
- mmorpg: protogen cmd/pipeline.go 各阶段：`prototools.CopyProtoToGenDir`、`goGen.AddGoPackageToProtoDir`、`cppGen.BuildProtocCpp` / `goGen.BuildUnifiedGoProto`（protoc 调用）、`cppGen.GenNodeUtil`（proto_util）、`cppGen.GenerateAllEventHandlers`（C++ 事件处理器）、`cppGen.GenerateGateKafkaCommandRouter`（contracts.kafka 的 `GateCommand` oneof → 事件，字段同名自动拷贝）、`cppGen.CppGrpcCallClient`（异步 gRPC 客户端）、`internal.GenerateServiceConstants`、internal/lua/lua.go（无调用方，死代码）、Unity 处理器桩
- client messages: none（Unity 桩除外，它只是客户端侧生成物）
- tables: none
- depends on: event-id-allocation、message-id-allocation
- behavior: 全部是 C++ / Go 进程内部的胶水代码。
- internal: Java 对应物：protoc 由 Maven 插件在 xm-proto / xm-table / xm-api 构建时调用；服务间调用是手写 Dubbo 接口（xm-api，参数返回值都是 protobuf）；节点链路是 Netty protobuf 编解码；Java gate 的 Kafka 命令（踢人等，mmorpg 的 GateCommand）尚未做，做时用 Spring Kafka `@KafkaListener` + 事件号派发，不生成路由代码。
- java: not_applicable
- size: M
- robot: none
- hazards: GateKafkaCommandRouter 只在 GateCommand 与事件消息字段类型完全一致时自动赋值，不一致只打 WARN 跳过该字段——生成物会静默少拷字段。

### navmesh-bake-and-query — 导航网格烘焙与服务器侧查询
- mmorpg: tools/navmesh_baker/navmesh_baker.cpp（893 行，Recast/Detour ue5navmesh，`dtReal=double`）+ test_baker.py；产物 data/scene_nav_bin/{main,tianyong,dungeon,mirror,donghai,lanxian,penglai}_scene.bin（80–175 KB）；运行时 cpp/libs/services/scene/spatial/system/{recast.cpp LoadNavMesh, nav_query.h}
- client messages: 137 SceneMovementClientPlayerNotifyMoveAck（有导航时撞墙 / 离网格回 137 纠偏）、21 / 79 的进场落位（有导航时 SnapToMesh）
- tables: BaseScene.nav_bin_file、spawn_x/y/z
- depends on: 移动同步、进场落位
- behavior: 输入三选一：客户端 painted-city 源文件里内嵌的 150×150、单格 2 m 可走位图（`--painted-city`）、同格式 base64 mask、OBJ 三角网；cs=0.25 / ch=0.2 / agent_radius=0；地面 quad 内缩 + painted-city 默认跳过 `rcFilterLedgeSpans`，使网格边界与 mask 格线精确重合；`--probe`（Unity 坐标）对出生点做 findNearestPoly，不在网格上就不写文件、非零退出。坐标换轴 nav=(server.y, server.z, server.x)。运行时：SnapToMesh（水平 ±2 m、垂直 ±4 m）、ValidateMove（射线夹持，返回阻挡点）、FindPath（拉直路径）；没有导航的场景 fail-open。
- internal: Java 现状：xm-scene 没有导航，用 MoveGuard 令牌桶 + 世界范围 ±1e7 m 校验，首登落 (180,200,0)（docs/reference/mmorpg-client-contract-scene.md §69）。Java **不需要自己的烘焙器**（烘焙产物是与客户端 mask 一一对应的数据，复用即可），需要的是运行时查询。两条路：① ContractSync 同步 `data/scene_nav_bin/*.bin`，Java 手写 MSET v1 读取器 + Detour 查询子集（findNearestPoly / raycast / findPath，按 UE5 double 布局解析 dtMeshHeader、poly、detail mesh、BV 树）——L，且 star 规则下没有可用库（recast4j 远低于 2 万 star）；② 既然 painted-city 场景的网格与 2 m 位图精确重合，Java 直接用位图做格子导航（DDA 射线、BFS / A* 寻路、最近可走格吸附），烘焙器在 mmorpg 侧额外导出一份 mask（或 ContractSync 从 .bin 反推不可行），M，语义差异只在格线上 0.25 m 量级——需与 C++ 对拍。真 3D（--obj）场景出现前推荐 ②。
- java: missing — Java 无导航查询，也未同步 nav bin（grep navmesh / recast 在 Java 代码中无结果）
- size: L
- robot: xm-robot movement `--expect-jump correct|accept|auto` 覆盖「有 / 无导航」两种纠偏口径
- hazards: ① MSET 文件是 C++ 结构体整块 fwrite，布局依赖编译器 / ABI / `dtReal=double`，Java 解析必须逐字段按偏移读、做小端；② 2026-09-08 的「每秒 4 次回拉」问题（ledge 过滤让网格内缩 0.25 m）说明网格边界与客户端 mask 的微小差异就会引发客户端 / 服务器反复纠偏，Java 任何实现都要和客户端 `IsPaintingWalkable` 对拍；③ 基线无导航场景完全不校验位移，Java 的 MoveGuard 已更严（PARITY 有意差异）。

### battle-art-gen — 回合制战斗美术程序化生成（客户端资产）
- mmorpg: tools/battle_art_gen/*.go（约 6.5k 行）：`-mode battle`（特效 / UI / 数字字集 / buff 图标，buff id 取自 generated/tables/Buff.json）、`-mode characters`（22 张立绘 → 256 格 8 帧动作条）、`-mode monsters`（程序化怪物帧条 + 地台）；写 mmorpg-client/Assets/Resources/Battle/ 与 ART_MANIFEST.json
- client messages: none
- tables: Buff（只读 id）
- depends on: 无服务端依赖
- behavior: 同 seed 逐字节一致；只写 .png / .json，不写 Unity .meta。
- internal: 纯客户端资产管线。
- java: not_applicable — 服务端无关；客户端两版共用，不需要 Java 版
- size: L
- robot: none
- hazards: README 用法里的路径写成 `E:\work\xuanming-server-mmo\tools\battle_art_gen`，指的是 mmorpg 仓库（GitHub 名 xuanming-server-mmo），与本 Java 仓库同名易混淆。

### merge-zone-core — 合服主流程（源区并入目标区）
- mmorpg: tools/merge_zone/{main,merge_run,fence,manifest,preflight,player_rows,player_blob_migrate,guild_step,trade_step,merged_into,scene_hot_state,post_merge_stamp}.go（约 6k 行非测试 + 约 6k 行测试）；dev_tools.ps1 `merge-zone`
- client messages: 间接——26 EnterGame 应答的 `post_merge_notice_ts` / `force_rename_required`（见 post-merge-login-signals）
- tables: none（库表清单来自 generated/data/mysql_database_table_list.json）
- depends on: 登录 / 归属围栏、scene-manager、公会、交易（Java 未做）
- behavior: 停服窗口内按序执行：只读预检 → 读既有清单（续跑）→ 打广播围栏 `merge:in_progress:{src|dst}`（TTL = 运行预算 + 30 min，至少 1 h，每 ttl/3 续期，按 run_id 用 Lua 释放；data_service 注册玩家与 guild 建帮会前 EXISTS 它）→ 收集源区玩家 → 空集合守卫 → 源区无活节点 / Kafka 无积压 / 无锁 / 无会话 / 不在活队伍 → 公会重名断言（冲突一个字节都不写）→ 落点检查 → `merge:merged_into` 环检测 → **任何写之前落盘清单**（dry-run 只写 `.dryrun.json`）→ 玩家行（copy 或 pin 模式）→ data Redis blob → guild.zone_id 改写 → trade_listing.market_zone 改写 → guild_rank ZSET 合并（在 guild 的 maintenance_lock 内 MULTI/EXEC）→ 先写 merged_into 再逐键 CAS `player:zone src→dst`（必须在玩家行之后）→ 清 scene_manager 源区热状态 → 打 `player_merge_notice:{pid}`。清单写出后失败：围栏保留，按指引核对后用原命令续跑。
- internal: Java 版存储模型简单得多：单库 `xm_java`，`player.zone_id` 一列（名字全服唯一 `uk_player_name_key`，不会重名；角色上限按账号全局计数）。Java 设计：新进程模块 `xm-ops`（Spring Boot 非 Web CLI，复用 xm-player-store / xm-discovery），`merge --src --dst [--dry-run]`：Redisson 围栏 `xm:merge:in-progress:{zone}`（login 建角、将来 guild 建帮会检查）→ 预检（节点目录里源 / 目标区无在线节点、无未释放归属 `owner_released=0`）→ 清单 JSON 落盘 → 按清单分批 `UPDATE player SET zone_id=? WHERE player_id IN (...) AND zone_id=?`（幂等、可续跑）→ 将来的 guild / trade / rank 步骤 → 合服提示键 → 释放围栏。所有键经 RedisKeys。
- java: missing
- size: L
- robot: none（mmorpg 有 integration_test.go，Java 用 H2 + 假 Redis 做同等测试）
- hazards: ① mapping Redis 一定是 DB 0（go-zero RedisConf 没有 DB 字段，yaml 写 DB 会被忽略；写错库 = fence 与 remap 静默无效）；② 「玩家行在 mapping 之前」是正确性的一部分，mapping 一改玩家就被路由到目标区；③ 步骤 5 跑完后再扫描就找不到这批人，续跑只能读清单；④ 围栏只防「新建」，不防已在线玩家，依赖停服。

### merge-zone-audit-unmerge — 合服审计、验证与按清单撤销
- mmorpg: tools/merge_zone/{audit_resources,audit_checks,unmerge,backfill_home_zone,capability_check}.go；dev_tools.ps1 `merge-zone-audit` / `merge-zone-unmerge` / `merge-zone-capability-check`
- client messages: none
- tables: none
- depends on: merge-zone-core
- behavior: `-mode audit`（只读）：在线 / 玩家锁 / Kafka 积压 / 源区节点 / 玩家重名 / 好友与好友请求 / 公会成员等资源计数，block / warn 两档；`-verify-merged`：mapping 已排空、清单映射正确、主行在预期库、源区公会已排空、公会榜 ZSET、热状态已清、围栏已释放。`-mode unmerge`：按清单**逐对象**反序撤销（先清合服提示 → 路由改回 src → 删 merged_into → 公会榜 / 交易 / 公会改回 → 删目标库里与源库逐字节相同的拷贝行），目标区原住民不动。`-backfill-home-zone`：给存量玩家回填 `player:zone`（2026-09-08 前建号从不写映射，不回填就合不过去）。`capability-check`：逐 zone 报告 go/db 能力标记 present / missing / unreadable，退出码 0/1/2。
- internal: Java 设计（同在 `xm-ops`）：`merge-audit --src --dst`（只读报告 + 退出码，block 即非零）、`merge-verify --manifest`、`unmerge --manifest`（反序：清提示键 → `UPDATE player SET zone_id=src WHERE player_id IN 清单 AND zone_id=dst` → 将来的公会 / 交易反向步骤）。Java 不需要 backfill（zone_id 是建角时写进 player 行的必填列）与 capability-check（没有按落点选库的 go/db）。
- java: missing
- size: M
- robot: none
- hazards: mmorpg 的 audit 读 friend 独占库 `mmorpg_friend` 需要单独 DSN（库名只做形状校验后拼进 SQL）；unmerge 只适用于「合服刚跑完、还没开服」，开服后撤销会丢目标区新数据，Java 版要在工具里显式检查「目标区自合服后无登录」。

### storage-placement-relocate — 玩家存储落点与在线搬库
- mmorpg: tools/merge_zone/{relocate,relocate_run,relocate_manifest,placement_ops,placement_codec,pin_placement,storage_audit}.go；dev_tools.ps1 `merge-zone-relocate` / `-relocate-abort` / `-pin-placement` / `-storage-audit`；docs/design/player-storage-placement.md
- client messages: none
- tables: none
- depends on: mmorpg 的 per-zone 库（zone_N_db）、go/db Kafka 写链路、`player:placement:{id}` 落点记录
- behavior: 冻结式搬库 S→T（冻结 → 等在途写 → 事务拷贝 → 逐字节比对 → CAS 切换，失败解冻）；pin 模式合服只改归属不搬行；存储审计按落点统计冷副本。
- internal: 这是为 mmorpg「每区一个库 + Kafka 异步写」架构服务的；Java 版单库 `xm_java` + 同步写回 + owner_epoch 围栏，没有落点概念。将来 Java 若分库，应采用 ShardingSphere 一类方案另行设计（star 需复核），不移植这套。
- java: not_applicable — Java 存储模型不同（architecture.md：不与 C++/Go 混部、单库）
- size: L
- robot: none
- hazards: 冻结正确性依赖 go/db 识别冻结记录并延后写；任何不认识落点记录的旧版 go/db 在搬库期间写入会写回旧库——所以才有 capability 标记（90 s 心跳）这一层。

### post-merge-login-signals — 合服后首次进游戏的提示与强制改名信号
- mmorpg: tools/merge_zone/post_merge_stamp.go（写 `player_merge_notice:{pid}` / `player_force_rename:{pid}`）、go/login/internal/logic/clientplayerlogin/entergamelogic.go `consumePostMergeFlags`、proto/login/login.proto `EnterGameResponse.post_merge_notice_ts = 3`、`force_rename_required = 4`
- client messages: 26 ClientPlayerLoginEnterGame（C2S，应答字段 3 / 4）
- tables: none
- depends on: merge-zone-core、登录
- behavior: 合服后第一次成功 EnterGame：`post_merge_notice_ts`（合服时刻毫秒）> 0 → 客户端弹一次「已合服」提示，login 读后删键（只出现一次）；`force_rename_required` 键存在即 true，login **不删**（由将来的改名 RPC 删，否则关 UI 即可绕过）；名字全服唯一，正常运营不会置位。旧客户端忽略两字段。读键失败不阻断登录。
- internal: Java：xm-login 的 EnterGame 应答目前不填这两个字段；做法：合服工具写 `RedisKeys.mergeNotice(pid)`，login 进游戏成功路径上 `getAndDelete`（Redisson `RBucket.getAndDelete`，原子），force_rename 只读不删。
- java: missing — xm-login 未处理（grep post_merge 无结果）
- size: S
- robot: none
- hazards: Go 实现是 pipeline GET 再 pipeline DEL（非原子），两个并发 EnterGame 可能都看到提示；DEL 在应答送达前执行，应答丢失则提示永久丢失。Java 用原子 getAndDelete 修前者，后者可接受。

### data-consistency-check — 跨区引用一致性巡检
- mmorpg: tools/data_consistency_check/main.go（524 行）
- client messages: none
- tables: none
- depends on: merge-zone-core；公会、好友（Java 未做）
- behavior: 每周 / 每次合服后跑，只报告不修复，输出 markdown，存在 block 级即非零退出。检查：`guild.zone_id` 指向不存在的 zone、`guild_rank:zone:*` 孤儿榜、player → account 关联、`friend.friend_player_id` 孤儿；存活 zone 集合从 mapping Redis 推断；info / warn / block 三档。
- internal: Java 设计：`xm-ops check-consistency`，存活 zone 取 xm-gateway 的区服配置（不是从 Redis 推断），检查 `player.zone_id` ∈ 存活 zone、`player.account` 在 account 表、`owner_released=0` 且租约早已过期的「悬挂归属」、将来的公会 / 好友外键；只读 SQL + 报告。可同时挂成 Spring `@Scheduled` 任务 + 指标 `xm_consistency_bad_rows{check}`（check 名是低基数）。
- java: missing
- size: S
- robot: none
- hazards: mmorpg 版把库名拼进 SQL（只做 `^[A-Za-z0-9_]{1,64}$` 形状校验）；从 Redis 推断「存活 zone」会把暂时无人的区当成已下线。

### local-dev-orchestration — 本地起服 / 停服 / 状态（含多区、多 gate / scene）
- mmorpg: tools/scripts/{dev_tools.ps1 dev-start/dev-start-exe/dev-start-zones/dev-stop/dev-status/dev-robot-zones, go_services.ps1, cpp_nodes.ps1, start_game.ps1, start_mprocs.ps1, dev_mprocs_proc.ps1}、tools/dev/mprocs{,.2g4s,.go-only,.cpp-only}.yaml、仓库根 `启动服务器.cmd`
- client messages: none
- tables: none
- depends on: 全部服务进程
- behavior: 一键拉起 Docker 依赖（MySQL / Redis / etcd / Kafka）→ 6 个 Go 服务 → Java 网关 → gate / scene / battle，逐个等就绪；PID 记录核对可执行文件完整路径；关窗口服务继续跑、再次双击复用；mprocs 一个终端里看全部日志、单进程重启；`dev-start-zones` 起多区（默认 1,2）给跨区冒烟 / 压测。
- internal: Java 现状：tools/local/start-slice.sh / stop-slice.sh（bash，5 个进程顺序启动、等端口、PID / 日志在 run/，环境变量注入 5 个秘密），只有单区、1 gate 1 scene，MySQL / Redis 须手工先起；tech-stack.md 说「集成测试用仓库自带的 docker compose」但仓库里没有 compose 文件。Java 设计：① `deploy/local/compose.yaml`（MySQL 8、Redis 7、可选 Nacos / Kafka，数据卷命名）；② 单文件 Java 启动器 `java tools/Dev.java start|stop|status|logs [--zones 1,2] [--gates N] [--scenes N]`（JDK 21，ProcessBuilder，跨 Windows / Linux，不依赖 bash / PowerShell；端口按 zone / 序号偏移；就绪检查走 actuator health；秘密只从环境变量读，缺失即拒启）；③ 多实例时 xm-gate / xm-scene 端口与节点号来自参数。
- java: partial — tools/local/start-slice.sh、tools/local/stop-slice.sh（单区、bash only、无依赖编排）
- size: M
- robot: xm-robot 与 Go robot 都依赖它先起好
- hazards: Windows 下 Java NIO 选择器的临时目录问题（xm-robot README 记录的 `-Djdk.net.unixdomain.tmpdir`）同样会影响启动器拉起的服务；PID 复用要核对进程命令行，否则会误杀别的进程（mmorpg 已核对完整路径）。

### container-image-build — 服务镜像构建与发布
- mmorpg: tools/scripts/{k8s_image.ps1, go_svc_image.ps1, java_svc_image.ps1, publish_images.ps1, fetch_images.ps1, import_images.ps1, k8s_stage_runtime.ps1}、仓库根 Dockerfile；dev_tools.ps1 `k8s-build-image` / `go-svc-build-images` / `java-svc-build-image` / `*-push-*`
- client messages: none
- tables: none
- depends on: release-preflight
- behavior: 不可变 tag（`v1.2.3-<12 位 commit>`，脏树拒绝；开发档 12 位 sha、脏树带 `-dirty`）；推送后把 registry digest 记进 JSON；离线环境 fetch / import 镜像包；C++ 运行时文件分阶段准备。
- internal: Java 设计：每个进程模块用 Spring Boot 自带的 `spring-boot:build-image`（Buildpacks，分层 jar；属于 Spring Boot 自带组件）或统一的分层 Dockerfile；tag 规则沿用「版本-commit、脏树拒绝」；`java tools/Images.java build|push` 只做编排并输出 digest 清单。表数据（config-data/tables）作为镜像的一层或 ConfigMap 挂载，启动时仍由 manifest 校验。
- java: missing — 仓库无 Dockerfile / 镜像构建配置
- size: M
- robot: none
- hazards: mmorpg 的 java_svc_image.ps1 构建的是 mmorpg 自己的 java/gateway_node，不是本仓库；镜像里不得打包任何秘密（Java 秘密只从环境变量注入）。

### k8s-zone-deploy — Kubernetes 部署（基础设施 / 区服上下线 / 全量发布）
- mmorpg: tools/scripts/k8s_deploy.ps1（5583 行）、lib/k8s_client_entry.ps1（2086 行）、lib/release_common.ps1、deploy/k8s/**、deploy/docker-compose*.yml；dev_tools.ps1 `k8s-infra-up|down|status`、`k8s-zone-up|down|status`、`k8s-all-*`、`k8s-build-all`、`k8s-exposure-preflight`、`k8s-release-zone|all`
- client messages: none（客户端入口形态：podip / external、NodePort / LoadBalancer、gate 通告地址）
- tables: none
- depends on: container-image-build、release-preflight
- behavior: 每区一个 namespace，全局 infra namespace（Kafka StatefulSet + PVC、预建 topic Job、Loki）；集群号 0..31 是部署级常量（snowflake worker 段高 5 位），infra 与 zone 必须同值；release 档位 dev / staging / prod：staging / prod 密钥必须从环境变量注入、tag 必须不可变，否则 fail-closed；C++ 节点挂 Alloy sidecar 采集文件日志；gate 可为 StatefulSet（external 入口）。
- internal: Java 设计：不移植 5.5k 行 PowerShell。用 Helm chart（`deploy/helm/xm`，Helm 远超 2 万 star）描述 xm-gateway / login / scene-manager / gate / scene + MySQL / Redis / Nacos /（后续）Kafka，values 按 zone 分；zone 上下线 = `helm upgrade --install xm-zone-<id>`；需要逻辑的部分（档位门禁、秘密存在性、tag 不可变检查、按 zone 计算节点号区间）放进单文件 Java 工具 `java tools/Deploy.java zone-up|zone-down|status|infra-up --zone N --profile prod`，它只生成 values 并调用 helm / kubectl。Java 节点号来自 Redis 租约（xm-discovery），不需要 cluster 号参数，但多集群共享 Redis 时需要等价的隔离键。
- java: missing — 仓库无 deploy/、Helm、k8s 清单
- size: L
- robot: none
- hazards: mmorpg 曾因 `:latest` + `IfNotPresent` 导致 `rollout undo` 退回同一镜像（现在强制不可变 tag）；infra-down 会连 PVC 一起删，Kafka topic 需重建，否则首条消息自动建成 1 分区。

### gate-drain-ops — gate 排空与滚动替换
- mmorpg: tools/scripts/k8s_gate_drain.ps1（1237 行，含 tests/k8s_gate_drain.tests.ps1）；login 的 GateDrain 判定循环（Interval 5 s / Deadline 25 min）
- client messages: 间接——被排空 gate 上的玩家在 deadline 后被断开重连（重新走 assign-gate）
- tables: none
- depends on: k8s-zone-deploy、gateway 分配 gate、gate 节点目录
- behavior: 读 zone 的 login ConfigMap 取 Redis / etcd 地址与判定参数；按 podIP 在 etcd 找 gate 的 node_id（0 条或多条都报错）；确认本区还有其他非排空候选（全部排空时 login 会忽略标记）；一次原子 EVAL 写 `gate:<id>:draining`（NX EX、值为 Redis TIME 秒）；轮询 `gate:<id>:drained`（below_threshold | deadline）；中途标记消失 / 被改写 / 进程换了 node_id → 撤回本次标记并中止；`-DeletePod` 才删 Pod，删后等旧 etcd 记录消失再清两个标记。
- internal: Java 版前置是 xm-gateway 支持「排空中的 gate 不再分配」与 gate 的在线人数上报（节点目录 `GateNodeInfo` 已有在线数）。Java 设计：节点目录加 `draining` 状态（Redisson，键经 RedisKeys），gateway 选 gate 时跳过；xm-gate 暴露 actuator 端点 `POST /actuator/drain`（只绑本机 / 集群内）与 `drained` 状态；`java tools/Deploy.java gate-drain --zone N --gate <pod>` 编排「标记 → 等在线数降到阈值或 deadline → 删 Pod → 清标记」，保留 mmorpg 的「最后一个候选不许排空」与「身份复核」规则。
- java: missing — xm-gateway 无排空概念（PARITY：排队 / 限流 / 管理接口待做）
- size: M
- robot: none
- hazards: node_id 全局复用最小空闲号，旧标记不清会让之后复用同一 node_id 的 gate 分不到玩家；Java 节点号也是租约复用，同样要在删 Pod 后清标记。

### zone-rollback-kafka-reset — 整区回档与 Kafka 位点重置
- mmorpg: tools/scripts/k8s_zone_rollback.ps1（862 行）、kafka_offset_reset.ps1（200 行，包 `kafka-consumer-groups.sh`，默认 dry-run，`-ToDatetime` / `-ToEarliest` / `-ToLatest` / `-DeleteAndRecreateTopic`）；docs/design/zone_data_rollback.md
- client messages: none
- tables: none
- depends on: k8s-zone-deploy；Kafka 存盘链路
- behavior: 预检（集群现状与部署默认值一致、zone-up 参数先 DryRun）→ zone-down（删 namespace）→ 等消费 LAG 归零 → **人工** MySQL PITR（暂停等确认）→ 数据 Redis FLUSHDB → `db_task_zone_<id>` 位点重置 → zone-up；无 `-Apply` 一律只预告。
- internal: Java 版写回是同步 MySQL（无 Kafka 存盘链），回档 = 停区 → PITR → 清 `xm:` 下该区的 Redis 键（节点目录、会话、归属缓存）→ 起区；Kafka 位点重置只在 Java 引入 Kafka 事件后才需要，届时直接用 Kafka 自带 CLI，不另写工具。Java 只需一份 runbook + `xm-ops redis-purge --zone N --dry-run`（按 RedisKeys 前缀 SCAN 删除，默认预演）。
- java: missing — 无回档 runbook 与按区 Redis 清理工具；Kafka 位点重置部分在 Java 引入 Kafka 前 not_applicable
- size: S
- robot: none
- hazards: Redis FLUSHDB 是整库操作，Java 所有键都在 `xm:` 前缀下、各区共用一个 Redis 时绝不能 FLUSHDB，只能按区前缀删；而且 `xm:node-id-epoch:*`（节点号租约防护代次，不过期、严格递增，architecture.md）必须排除在清理范围外，删掉会让代次回退、旧 gate 的链路重新被 scene 接受。

### release-preflight — 发布门禁、版本与制品管理
- mmorpg: tools/scripts/{release_preflight.ps1 (656 行), make_release.ps1, artifacts_retention.ps1, deploy-staging.sh, build_linux.sh}、lib/{release_common,artifacts_lib,assetop_dev_secret}.ps1、CHANGELOG.md、tests/{make_release,release_common_version,artifacts_*}.tests.ps1
- client messages: none
- tables: none
- depends on: container-image-build
- behavior: 门禁把发布检查单变成可执行判据：密钥类配置不得为空、不得等于已知占位串（如 `change-me-in-production-use-a-strong-random-key`）、长度 ≥ 32；文件 / 键缺失一律 FAIL（只有已核实默认关断的开关允许缺失）；给 `-ReleaseVersion` 时再查版本号、CHANGELOG 段落、制品 sha256sums、build-info 版本与脏树、镜像 tag 与 commit 对应；退出码 0 / 1(FAIL) / 2(脚本错误)。制品保留只清 `snapshots/images` 下过期快照，`releases/` 永不触碰。
- internal: Java 秘密只从环境变量注入（XM_MYSQL_PASSWORD、XM_GATE_TOKEN_SECRET、XM_LOGIN_DEV_PASSWORD、XM_NODE_LINK_SECRET、XM_DUBBO_SECRET），门禁对象变成「部署环境里这些变量是否存在、够长、不是开发值」以及「prod 档位下开发口令登录必须关闭」。Java 设计：每个进程启动时自检（Spring `ApplicationRunner`：prod profile 下秘密缺失 / 过短 / 命中占位串即拒启，fail-closed，比发布前脚本更可靠）+ 单文件 `java tools/Release.java preflight|make --version X.Y.Z`（Maven 版本、CHANGELOG 段落、`git status` 干净、生成 build-info：Spring Boot `build-info` goal 自带）。
- java: missing — 无 CHANGELOG / 发布脚本；秘密注入已统一走环境变量
- size: M
- robot: none
- hazards: mmorpg 的占位串散在 7 个文件里且三处默认值相同，「两边一致」判据对占位串免疫——Java 版不要用「配置一致」做门禁。

### stress-orchestration — 压测编排、指标快照与汇总
- mmorpg: tools/scripts/{stress_round19.ps1, stress_snap.ps1, stress_summarize.ps1 (568 行), test_stress_summarize.ps1, stress-linux-tier.sh, gateway-mock-stress.sh}；robot `etc/robot.stress-*.yaml`（50 / 100 / 200 / 500 / 5k / 三区）
- client messages: 登录链路 48 / 14 / 26、技能 / 换场景（robot AI）
- tables: none
- depends on: robot-stress-ai、local-dev-orchestration、各服务 Prometheus 端点
- behavior: 分阶段（plan 默认只打印 → baseline → wipe（需 `-WipeData` 双开关）→ build → start → run → summarize）；在 ramp-end / steady-mid / steady-end 抓 login `:9101`、scene_manager `:9150`、db `:9160` 的 Prometheus 文本；汇总成 ≤2 KB 的二维表（每分钟 conn / login_ok / enter_ok / enter_fail / msg/s，进游戏分阶段均值，建角分阶段，Kafka lag）；Windows 开发机约 500 机器人上限（Hyper-V 端口保留、单 broker Kafka），大规模走 Linux staging；gateway-mock-stress 只压 HTTP 登录。
- internal: Java 指标名与 Go 不同（architecture.md §11：`xm_gate_*`、`xm_scene_*` 等），stress_summarize 不能直接复用。Java 设计：`xm-robot stress --count N --ramp 60s --duration 10m --profile stress`（见 robot-stress-ai）内置定时抓取 Java 各进程 `/actuator/prometheus`（18081 / 18101–18104）存进运行目录，结束时用 Java 解析 Prometheus 文本格式（简单行解析，不引新库）生成同样形状的汇总表；destructive 的 wipe 只针对 `xm_java` 库与 `xm:` 前缀，默认 plan。
- java: missing
- size: M
- robot: Go robot stress 模式（PARITY：曾用 Go robot 对 Java 版跑 75 s stress AI）
- hazards: stress_summarize 用正则解析 robot 的统计行格式（robot/metrics/stats.go），两处格式必须同步；wipe 会顺带销毁正在跑的其他实验（脚本注释明确警告）。

### currency-crash-window — 货币变更崩溃窗口验证
- mmorpg: tools/scripts/currency_crash_window.ps1（413 行，A / B / C / D 四个用例）、robot/currency_crash_window_scenario.go（mode `currency-crash-snapshot`）、robot/etc/robot.currency-crash.yaml；docs/notes/currency-crash-window-verification.md
- client messages: 37 SceneCurrencyClientPlayerGmAddCurrency（C2S）、登录链路、货币读取
- tables: none
- depends on: 货币系统、GM 指令、周期存盘（Java 未做）
- behavior: 每个用例两段：①登录 → 读余额 → GmAddCurrency 10000 → 再读 → 不 LeaveGame 退出；kill -9 scene（或 D 用例正常离开）→ 重启；②登录 → 读余额；lost = ①后余额 − ②前余额，与「应丢 / 应保留」期望比对，结果写回文档附录。
- internal: Java 写回是同步落库 + owner_epoch 围栏，「两次周期存盘之间被杀」的窗口取决于 Java 的写回策略（目前离场 / 被接管 / 停服写回，无周期存盘——architecture.md 列为待做）。Java 设计：做货币后，xm-robot 加 `crash-window` 场景（单账号快照 JSON），编排用 `java tools/Dev.java` 的 kill / restart 子命令，四个用例同构；断言放 Java 单文件驱动里，不写回文档。
- java: missing
- size: S
- robot: Go `currency-crash-snapshot`
- hazards: 用例之间默认清空 redis / mysql / kafka 位点，会破坏同机其他实验；每个用例换账号防串扰。

### repo-hygiene-native-tools — C++ / 仓库卫生与第三方补丁（不移植）
- mmorpg: tools/scripts/{check_no_raw_pointer_member.ps1, lib/no_raw_pointer_project.ps1, third_party/setup_no_raw_pointer_check.ps1, iwyu_run.{ps1,sh}, run_cpp_tests.ps1, verify_full_chain.ps1, verify_grpc_client_build.ps1, third_party/build_grpc.ps1, normalize_names.ps1, tree.ps1, git_stats.{ps1,sh}, slice_qdao_fgui_assets.py}、dev_tools.ps1 `naming-audit` / `naming-apply` / `tree` / `git-stats` / `no-raw-pointer-setup` / `iwyu-run` / `third-party-grpc-build`、tools/patches/{boost,grpc,librdkafka,ue5navmesh}、tools/proto（废弃的 pbgen 包）、tools/docs（命名迁移审计）
- client messages: none
- tables: none
- depends on: none
- behavior: 裸指针成员检查器（固定版本 LLVM 23.1.1 开发包 + SHA256 校验 + MSBuild 钩子）、include-what-you-use、MSVC 全链路构建、gRPC 第三方构建、proto-gen 命名迁移、目录树 / git 统计、FairyGUI 资源切图（客户端）、第三方子模块本地补丁存档（boost 源码分卷 zip + 恢复脚本）。
- internal: Java 对应物是 Maven 生态自带能力：`maven-enforcer-plugin`（依赖收敛、禁用依赖如 fastjson 1.x、Java 21）、编译器 `-Xlint:all -Werror` 可选、单测即门禁；需要「禁止某些写法」（如在 Netty I/O 线程上做阻塞 I/O、绕过 RedisKeys 拼键）时用 ArchUnit 一类架构测试（star 需按 §2 复核，否则写普通 JUnit 扫描）。
- java: not_applicable — C++ / 第三方专用；Java 侧可选的架构约束测试另行立项
- size: M
- robot: none
- hazards: tools/README.md 与 tools/AGENTS.md 描述的 `tools/robot/`、`contracts/`、`data_service/`、`scene_manager/`、`github.com/` 子目录不在版本库里（`git ls-files tools` 只有 archived / battle_art_gen / data_consistency_check / data_table_exporter / dev / docs / merge_zone / navmesh_baker / patches / proto / proto_generator / scripts），属文档腐坏或被忽略的生成目录；`tools/archived/`（8 个文件：旧构建脚本、muduo Linux overlay）在库但本次稀疏检出未包含，未审阅。

### robot-core-client — 机器人客户端底座（连接 / 认证 / 排队 / 重定向 / 统计）
- mmorpg: robot/{main.go, gate.go, login.go, http_assign_gate.go, http_login.go, http_client.go}、robot/pkg/{client,client_registry,redirect}.go、robot/metrics/stats.go、robot/logic/handler/**（多数由 proto-gen 生成）、robot/logic/gameobject/player.go
- client messages: 48 Login、14 CreatePlayer、26 EnterGame、17 LeaveGame、58 Disconnect、127 RefreshToken（C2S）；23 SendTipToClient、124 RedirectToGate、34 KickPlayer（S2C push）
- tables: Skill、Class（启动时读 json 自动取 skill_ids）
- depends on: 网关 assign-gate / 排队、gate 握手、登录
- behavior: assign-gate（含排队：`queue_token` + `/queue-status` 轮询，统计 q_entered / q_admitted / q_expired / 平均等待 / 最大名次）；认证方式 password / SA-Token（`/api/login`）/ access_token 复用；gate 令牌过期自动重取（最多 20 次）；登录失败指数退避（3 s 起、封顶 30 s、5 次）；跟随 124 重定向到新 gate 重登（token 只认证这条 TCP，不是会话转移）；每 report_interval 打一行固定格式统计；退出时导出 behavior_test_results.{csv,jsonl}。
- internal: Java xm-robot 已有：assign-gate（JDK HttpClient + Jackson）、TCP + 首帧握手（复用 xm-net 编解码）、开发口令登录、请求应答等待（Inbox）、消息号运行时解析、退出码即结论。缺：排队轮询、access_token / RefreshToken（Java login 也还没有）、124 重定向（Java 未做跨 gate 重定向）、周期统计行与行为导出、按账号退避重试。Java 设计：在 xm-robot `client` 包补 `QueueAwareAssigner`、`RedirectFollower`、`RobotStats`（同一统计行格式便于对比两版）；随 client-handler-codegen 换成类型化 API。
- java: partial — xm-robot/src/main/java/com/game/robot/client/{AssignGateClient,GameConnection,RobotClient,Inbox,MessageIds}.java
- size: M
- robot: 所有模式的公共底座
- hazards: Go robot 的退出码不反映登录链路结果（xm-robot README 已指出），Java 版保持「退出码即结论」；Go 的重登在角色列表为空时会自动建角，跨区场景会假绿（travel-smoke 专门覆盖了这一点）。

### robot-stress-ai — 压测机器人 AI（权重档 / LLM 决策）
- mmorpg: robot/logic/ai/{robot_ai,action,llm}.go、robot/main.go 默认 stress 分支（每 50 ms 起一个、SIGINT 停）、robot/etc/robot{,.behavioral,.stress-*}.yaml
- client messages: 技能释放（ReleaseSkill）、移动（134 / 132 / 131）、换场景（EnterScene）、聊天
- tables: Skill
- depends on: robot-core-client、技能 / 移动 / 换场景 / 聊天
- behavior: 动作 cast_skill / move / switch_scene / chat / idle 按档位加权随机：stress = 技能 85 + 换场景 15，behavioral = 45 / 25 / 20 / 5 / 5，另有 default 档；可选 LLMAdvisor（OpenAI 兼容接口，5 s 超时，把位置 / 场景 / 技能列表发给模型决定下一步，仅用于 1–10 个机器人的行为建模）；每个动作记录成功 / 耗时进行为日志。
- internal: Java 设计：`xm-robot stress`：虚拟线程（Java 21）每机器人一条，Netty 连接共享 EventLoopGroup；`Profile` 权重表与 Go 同名同值；动作只用 Java 已实现的消息（技能、移动、场景内换图），未实现的动作在 profile 里置 0 并在启动时打印；LLM 决策不移植（非压测必需；若要做，按 §2 选型并通过环境变量注入密钥）。
- java: missing — xm-robot 只有 smoke / movement
- size: M
- robot: Go stress（曾对 Java 版跑 75 s：skill=57，scene_switch=15）
- hazards: stress 档没有 move，压不到移动广播与 AOI；Java 版的帧预算瓶颈恰在出生点人群的视野计算（PARITY 视野行），Java stress 档应包含移动。

### robot-login-test-suite — 登录链路场景套件（login-test 模式，22 例）
- mmorpg: robot/login_test_scenarios.go（1373 行）、robot/login_test.go、robot/main.go `mode: login-test`
- client messages: 48 Login、14 CreatePlayer、26 EnterGame、17 LeaveGame、58 Disconnect、127 RefreshToken（C2S）；23 SendTipToClient（S2C push，被顶号时收到）
- tables: none
- depends on: robot-core-client；登录 / 顶号 / 断线（Java 部分已做）
- behavior: NormalLogin、LoginLogoutCycle、WrongPassword、DuplicateEnterGame、DuplicateLoginRequest、AccountDisplacement、ConcurrentSameAccount、RapidReconnect、DisconnectDuringLogin、DisconnectDuringEnter、RapidDisconnectReconnect、AccessTokenReconnect、DifferentAccountSequential、LeaveAndReEnter、LeaveAndReLogin、RapidLoginSpam、MessageBeforeLogin、LoginStuckDetection、BatchConcurrentLogin、SkillCast、SceneSwitch、MultiRobotBehavior（+ CurrencyCrashWindow）；顺序执行、打印汇总、导出行为 CSV / JSONL。
- internal: Java xm-robot 的 smoke 只覆盖 NormalLogin 形状；Java 已实现但没有端到端探针的：2028（重复 EnterGame）、顶号收 23 {2017} 后断开、进场失败回到已登录、LeaveGame 保留账号（与基线有意不同，PARITY 已记）、未握手发业务包被拒（gate 指标 `missing`）。Java 设计：`xm-robot login-suite`，每例独立账号（前缀 + 运行标签），断言写成表驱动；AccessTokenReconnect / RefreshToken 两例在 Java 实现 access token 前标 SKIP 而不是 PASS；LeaveAndReLogin 按 Java 口径断言并在报告里注明差异。
- java: partial — 只有 xm-robot/src/main/java/com/game/robot/scenario/SmokeScenario.java；顶号、2028 等由服务端单测覆盖（如 xm-login EnterGameHandlerTest），无 robot 端到端
- size: M
- robot: Go login-test
- hazards: Go 套件的部分用例依赖 Go / C++ 基线行为（LeaveGame 后须重新 Login、并发进场回 2005），Java 行为有意不同的地方要按 Java 口径断言，不能把 Go 的期望照抄。

### robot-movement-aoi-probe — 移动与视野端到端探针（Java 版专有）
- mmorpg: 无专门场景（Go robot 只在 AI 里发 move；契约来源 docs/reference/mmorpg-client-contract-{movement,aoi}.md）
- client messages: 134 MoveStart、132 MoveSync、131 MoveStop（C2S，Empty 应答不回包）；137 NotifyMoveAck、66 SyncBaseAttribute、21 / 47 进入视野、64 / 51 离开视野、79 NotifyEnterScene（S2C push）
- tables: BaseScene（出生点）
- depends on: robot-core-client、移动、AOI、属性同步
- behavior: 两个新账号同场景；A 每 250 ms 一条 Start → Sync×3（末条 30 m/s）→ Stop，B 每条都收到 A 的 66（速度截到 10 m/s、Stop 全零速度、位置在路径上、rotation = 上报值），停后 1 s 静默，A 无回包无 137；A 断开 → B 收 51 → A 重进，位置 = 停止点；前跳 200 m：`--expect-jump correct|accept|auto` 校验 137 的 input_seq 回显、偏差 > 0.5 m、server_time_ms 为 UTC 毫秒、信封 id=0，重登后落盘位置 = 裁决位置。
- internal: 已实现。可反向贡献给 mmorpg（Go robot 没有等价场景），用于核对 C++ 基线与 Java 的有意差异。
- java: done — xm-robot/src/main/java/com/game/robot/scenario/{MovementScenario,MoveAssertions,ExpectJump,Vec3}.java
- size: M
- robot: xm-robot movement
- hazards: 固定 `--run-tag` 复用账号时，跳跃会移动 A 的存档位置，A、B 可能不再同处出生点（README 已注明）。

### robot-data-stress — 数据一致性压测（登录 → 游玩 → 登出循环 + 校验器）
- mmorpg: robot/data_stress.go（mode `data-stress`，robot.data_stress.yaml）、go/db/cmd/{data_stress,verifier}；docs/design/data-consistency-stress-testing.md（L2 正常路径 / L3 全链路 / L4 kill-restart 混沌）
- client messages: 登录链路、离开游戏
- tables: none
- depends on: robot-core-client、玩家存盘链路
- behavior: N 个账号并行、每账号 R 轮；每次干净登出后在 Redis 写 `verify:enrolled:*` / `verify:expected:player_database:<pid>`（期望值 = 轮数）；verifier 检查 MySQL 行存在且属于该玩家；混沌模式反复 kill / 重启 db 消费者后仍须收敛。
- internal: Java 的风险点不同：同步写回 + owner_epoch 围栏 + 归属租约 / 释放标记（PARITY「玩家数据归属」行），要验证的是「scene 被杀 / 网络分区 / 顶号竞争下，最终落库的是最新归属者的数据、旧写者被围栏拒掉、没有永久悬挂的归属」。Java 设计：`xm-robot data-stress`：每轮进场后做一次可观测的状态变更（移动到可计算的坐标 = 轮号编码），登出后由 robot 自己经只读 JDBC（可选）或下次登录的 21 位置校验「最后一轮的位置被持久化」；kill / restart 编排复用 `java tools/Dev.java`；结果写运行目录 JSON。
- java: missing
- size: M
- robot: Go data-stress
- hazards: Go 版期望值只是轮数而不是逐次写版本，verifier 实际只能查「行存在」，查不出「内容落后一轮」；Java 版用位置编码轮号可以查出内容新旧。tools/AGENTS.md 说明的 `chaos_test.ps1` 编排脚本在仓库里不存在。

### robot-social-smokes — 社交玩法冒烟（聊天 / 好友 / 公会 + 公会经济 / 组队 / 聚宝斋交易）
- mmorpg: robot/{chat_smoke_scenario.go (526), friend_smoke_scenario.go (755), guild_smoke_scenario.go (1084), guild_economy_smoke.go (626), team_smoke_scenario.go (1278), trade_smoke_scenario.go (876)}.go、robot/etc/{chat,friend,guild,team,trade}_smoke.yaml
- client messages: ClientPlayerChat（28 PullChatHistory、61 SendChat）；ClientPlayerFriend（11 个：2,7,11,12,119,230,232,234–236,238，含 NotifyFriendEvent 推送）；GuildService（28 个方法，如 15 CreateGuild、53 DonateToGuild、76 UpgradeGuild）；ClientPlayerTeam（15 个，201–215 段，含 NotifyTeamEvent / NotifyTeamInvite / NotifyTeamSnapshot 推送）；ClientPlayerJubaozhai（196,197,198,200）
- tables: GuildLevel、GuildDonate、GuildShop、GuildRule、Item
- depends on: robot-core-client；Java 版的聊天 / 好友 / 公会 / 组队 / 交易服务（均未做，gate 现回「服务不可用」）
- behavior: 每个场景输出一行 `<NAME>_SMOKE_OK …` 并以退出码表结论。chat：两 zone 两机器人，世界 / 私聊、600 字节超长回 kMessageSizeExceeded、同 request_id 幂等；friend：三机器人加 / 拒 / 删 / 拉黑 / 推荐与推送；guild：五个同区 + 一个别区，按 zone 隔离、管理审批 M1–M10、推送，经济段捐献 → 升级 → 兑换（按游戏日 UTC+8 05:00 计数，full / degraded 两模式）；team：四机器人建队 / 邀请 / 申请 / 踢人 / 转让 / 匹配、换场景与战斗推送按「动作前打标记、之后扫描」认领；trade：上架 / 浏览 / 详情 / 收藏，按本轮 nonce 找自己的商品。
- internal: Java 设计：每个玩法做完后在 xm-robot 加同名场景，沿用「OK 行 + 退出码」约定；推送认领采用 team-smoke 的「标记 + 扫描」模式，写进 robot 底座（Inbox 已有收件队列，可直接扩展）。
- java: missing — 对应玩法 Java 未做
- size: L
- robot: Go chat / friend / guild / team / trade smoke
- hazards: friend-smoke 文件头写明消息号常量名是「按规律推导、待 proto-gen 后核对」的；guild 经济计数清不掉，同一游戏日只能完整跑一次；trade 的 scope 是进程级配置，两种 scope 要改配置重启各跑一次；Go 的 sync.Once 一次性信号在同一会话打多场战斗时会读到旧信号（team-smoke 已绕开）。

### robot-progression-smokes — 成长玩法冒烟（属性加点 / 宝宝 / 背包与任务）
- mmorpg: robot/{attribute_smoke_scenario.go (580), pet_smoke_scenario.go (601), features_smoke_scenario.go (549), features_battle_smoke.go (205)}.go、robot/logic/gameobject/player_features.go、robot/etc/{attribute,pet}_smoke.yaml
- client messages: SceneAttributeClientPlayer（9 个，167–175，含 175 GmSetPlayerLevel）、ScenePetClientPlayer（9 个，181–189）、SceneBagClientPlayer（191 GetBag、192 SortBag）、SceneMissionClientPlayer（193–195）、37 GmAddCurrency
- tables: AttributePool、AttributeDimension、AttributeRule、AttributeAllocRatio、AttributeAutoPlan、Pet、PetRule、Item、Mission、Reward
- depends on: robot-core-client；Java 的属性加点 / 宝宝 / 背包 / 任务 / 货币 / GM（均未做）
- behavior: attribute：等级 1 → 30 属性点恰好 +145（每级 5 点、不落库按等级换算）、自动加点只算不落、提交后剩余归零二级属性变大、重发同一目标值判「无变化」不重复扣点、只增不减、建方案精确扣金、方案互不串档（切换冷却）、下线重登全部一致；pet：池隔离（角色面板无宝宝池）、宝宝等级随主人、加点规则同上；features-smoke：默认只读，整理背包与每个任务动作需显式开关，报告只含 id / 计数 / 状态，不含凭据与包体。
- internal: Java 设计：玩法完成后对应场景移植；features-smoke 的「默认只读、写操作逐项显式开启」约定保留。
- java: missing — 对应玩法 Java 未做
- size: M
- robot: Go attribute / pet / features smoke
- hazards: 场景开头先「洗点 / 等级归 1 / 发金币」保证可重复运行，依赖 GM 指令；Java 若不开放 GM 接口需要另一种测试夹具（例如测试库直写）。

### robot-battle-travel-smokes — 战斗 / 观战 / 跨区匹配 / 跨区传送冒烟
- mmorpg: robot/{battle_smoke_scenario.go (389), battle_smoke_cross_zone_scenario.go (311), battle_direct_conn.go (243), features_battle_smoke.go, travel_smoke_scenario.go (956), travel_smoke_wire.go}.go、robot/etc/{battle_smoke,battle_smoke_cross_zone,travel_smoke}.yaml
- client messages: BattleClientPlayer（12 个：139,140,143,144,149,150,158,161,162,165,166,177，含 NotifyBattleStart / NotifyTurnResult / NotifyBattleEnd / GetBattleState / SetAutoBattle）、MatchService（10 个：148,151–154,156,157,163,164,179）、226 SceneSceneClientPlayerTravelToZone、124 RedirectToGate（S2C push）
- tables: Dungeon、Monster、Skill、Buff
- depends on: robot-core-client（重定向跟随）；Java 的战斗节点、匹配、跨区传送（均未做；PARITY：战斗直连、重定向待做）
- behavior: battle：A 排 PVE_SOLO 自动战斗，B `WatchBattle(battle_id=0)` 随机观战，断言观战分配与 A 同局、握手快照从直连到达、观战回合 ≥ 1；战斗帧只走与 battle 节点的直连（gate 拒绝战斗上行）；cross-zone：两 zone 两机器人 1V1 匹配进同一局、gate 地址不同；travel：TravelToZone 往返，必须登记连接注册表供 124 反查，重登必须按 player_id 严格选角（防自动建角假绿）。
- internal: Java 设计：战斗与跨区功能落地后移植；Java robot 的直连需要第二条 `GameConnection` 与分配消息里的通告地址（PARITY「节点客户端可达地址」行：battle 分配的通告地址随功能一起做）。
- java: missing
- size: M
- robot: Go battle / battle cross-zone / travel smoke
- hazards: travel 场景列出四个「不照做就假绿或卡死」的坑（未注册连接导致 124 静默超时、通用重登自动建角等），Java 版底座设计时要避免同类全局可变状态（Go 的 `SetRedirectRelogin` 是进程全局）。

## Open questions

1. **「Java 版工具」的边界**：导表器与消息号 / 事件号发号器是两版共享契约的唯一生产者。若用户要求它们也有 Java 版，Java 版必须**替换** mmorpg 的 Python / Go 实现（两版同用一个生产者，含 C# / UE 客户端产物），这是 mmorpg 侧的 XL 改造；否则建议 Java 只做「消费 + 更强校验 + 编译期代码生成」（contract-sync、contract-change-report、contract-tamper-check、client-handler-codegen、table-* 系列）。需要用户拍板。
2. **消息号复用**：mmorpg 删除方法后号会被新方法复用、填洞顺序随 Go map 迭代而定。是否请 mmorpg 给 message_id.txt 加与 event_id.txt 同样的墓碑（契约层修复，mmorpg 待做）？在此之前 Java 侧靠 contract-change-report 拦截。
3. **语言无关的导表元数据**：是否请 mmorpg 导表器额外产出 `tip_meta.json`（段 + fault 集合）、`constants.json`（具名常量）、位序 mapping 的发布副本，让 ContractSync 不再解析 C++ 头文件 / Go 生成文件？
4. **位序**：Java 存档位图是否直接复用 mmorpg 的 `state/mapping/table_index_mapping/*.json`（推荐，只增不改的纪律已由上游保证），还是 Java 自建一份？
5. **导航网格**：Java 选「Detour 读取器 + 查询移植」（L，按 UE5 double 结构体布局解析 MSET）还是「直接用 2 m 可走位图做格子导航」（M，需要 mmorpg 把 mask 作为数据导出到 data/，目前 mask 只存在于客户端仓库的 TianyongPaintedCity.cs 与 `--mask-base64` 输入文件）？两者都要与客户端 `IsPaintingWalkable` 对拍。
6. **运维工具形态**：合服 / 巡检 / Redis 清理是否放进一个新的进程模块 `xm-ops`（Spring Boot 非 Web CLI，复用 xm-player-store / xm-discovery）；开发 / 部署 / 发布编排是否统一用 JDK 21 单文件程序（`java tools/Dev.java`、`Deploy.java`、`Release.java`，与 ContractSync 同风格，免第三方 CLI 库——picocli 不到 2 万 star）？
7. **Java 仓库缺 CI 与 compose**：tech-stack.md 写「集成测试用仓库自带的 docker compose」，但仓库里没有 compose 文件，也没有 .github/。是否新增最小 CI（`./mvnw -B test` + 契约防手改校验）与 `deploy/local/compose.yaml`？
8. **角色与 zone**：Java login 按账号列出全部角色（`SELECT * FROM player WHERE account = ?`，不按 zone 过滤），角色上限也按账号全局计。合服在 Java 里因此只是改 `zone_id`；但这与 mmorpg 的「角色属于区」口径是否一致，需要登录区域的清单确认。
9. **表达式列语义子集**：exprtk 支持的运算远多于现有数据用到的（`*`、常量、参数名）。Java 求值器支持哪些运算 / 函数（`^`、`%`、`min/max`、`random()`）需要与策划约定，超出子集的公式在加载期拒绝。
10. **Kafka 事件号**：Java 引入 Kafka 时是否沿用 mmorpg 的 event_id.txt（已同步但无消费者），还是 Java 内部事件自建注册表？
11. **未审阅部分**：`tools/archived/`（在库但稀疏检出未包含）、`robot/config/`（同上，robot 的配置默认值与 LoadTables 细节未核对）、`go/db/cmd/{data_stress,verifier}`（数据一致性压测的另一半，在 go/ 下）。
12. **mmorpg 侧值得登记为「mmorpg 待做」的工具缺陷**：operator id 池读取 fail-open（损坏即从 1 重新发号）；C++ 表达式列每次求值都重新编译、编译失败不检查、参数个数不符静默沿用旧值；Go 不支持表达式列；导表器 `core/state/**` 与 `mapping/**` 是与权威 `state/**` 分叉的陈旧副本；tools/AGENTS.md 引用不存在的 `chaos_test.ps1`；合服提示的 GET / DEL 非原子。
