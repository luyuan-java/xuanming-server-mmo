# 协议编译期代码生成（`game-proto-codegen`）设计方案

> 对应根索引 [AGENTS.md](../../AGENTS.md) §1、选型表 [tech-stack.md](tech-stack.md) 与配置表代码生成 [config-tables.md](config-tables.md)。
> 本方案落地用户 2026-11-20 要求：**架构与流程对齐 C++ `protogen`（节点划分、消息号/事件号表、客户端路由表、每服务类型化 Handler 与派发器、Robot 类型化 RPC 桩），实现遵循 Java 21 标准做法（javac 注解处理器、接口 + 不可变值类型、GC 管理生命周期、零反射派发）**。

## 1. 需求分析

1. **消除手写契约表的漂移风险**：此前 `game-proto` 只在运行时经反射扫描 `contract.desc` 构建 `MessageIdRegistry`，各模块仍手写 `MessageRoutes.SERVICE_BACKENDS` / `DIRECT_ONLY_SERVICES` / `GATE_LOCAL_SERVICES`、`BattleMessageIds`、`PushMessageIds`、`MatchMethods`、`TeamMethods`、`GuildMethods`、`TradeMethods` 与 `game-robot` 的 `MessageIds`。每次同步契约后，删改方法或发号变更都要靠单测才能发现，缺乏编译期保障。
2. **对齐 C++ `protogen` 的生成面**：C++ / Go 侧 `tools/proto_generator/protogen` 从同一套 `.proto` 与 `message_id.txt` / `event_id.txt` 生成消息号常量、事件号常量、客户端路由表（`route_table.go` / `service_metadata.h`）、服务处理器骨架与派发器、Robot 类型化 RPC 桩。Java 侧必须具备等价的编译期生成流水线。
3. **严守不依赖 proto 目录结构与零外部依赖**：遵循 `AGENTS.md §1` 与 `tech-stack.md`，节点类型与包名只由 proto 选项（`OptionIsPlayerService`、`OptionIsClientProtocolService`、`OptionFileDefaultNode`、`OptionClientRpcKind`）和 proto `package` / `service` 语义决定，绝不读 proto 源文件路径目录；不引入任何新第三方依赖，仅用 JDK 21 `javax.annotation.processing` 与已有 `protobuf-java 4.35.1`。
4. **保持既有模块向后兼容**：`MessageIdRegistry` 继续保留（供需要 `MethodDescriptor` / 合成测试输入的用例使用），新增生成产物放在 `com.game.contract.generated.*`，新老代码平滑共存并逐步收口。

## 2. 思想分析

- **C++ `protogen` 与 Java 惯用做法的本质对齐**：
  - C++ 没有编译期反射，且接口虚函数与构建目标分离，因此 `protogen` 生成带 `// <<< BEGIN USER CODE` 的 `.h/.cpp` 骨架并由脚本回写源码树。
  - 在 Java 21 生态中，**源码树不存放生成物**（`target/generated-sources/annotations` 随编译产出），用 **策略/接口隔离（GoF Strategy + Interface Segregation）** 替代“用户代码段注释”：生成器产出 `<Service>Handler<C>` 接口，业务类直接 `implements` 该接口，由 Spring 构造器注入，完全交给 JVM GC 管理对象生命周期，不存在手写对象池或裸指针管理问题。
- **与仓库既有模式一致**：直接复用 `game-table-codegen` 验证成熟的 **javac 注解处理器模式**（`provided` 作用域、`proc=full`、`default-testCompile` `proc=none`），避免 Maven 多模块同 reactor 下 protoc JVM 插件解析不到未打包 jar 的坑（见 `ConfigTableProcessor` 注释）。
- **采用的设计模式与原因**：
  1. **值对象（Value Object / Command Descriptor）**：`ContractRpcMethod<Q, R>` 不可变 record 把 `messageId`、服务/方法名、gRPC 路径、`Parser<Q>`、`Parser<R>`、`hasResponse`、`rpcKind` 打包为单一编译期常量，供 `game-robot` 与测试直接 `conn.call(MatchServiceRpc.JOIN_QUEUE, req, timeout)`，消除“手写消息号 + 手写 Parser 错配”问题。
  2. **静态分派表（Generated Switch Dispatcher）**：`<Service>ContractDispatcher` 用编译期 `switch (messageId)` 替代运行时反射 `Method.invoke` / 手写 `Map<Integer, Route>`，兼顾 O(1) 分发与编译期类型安全。
  3. **接口隔离（ISP）**：每个契约服务独立生成 `<Service>Handler<C>` 接口（泛型上下文 `C` 适配 `SessionContext`、`PlayerCall`、`Deadline` 等各进程上下文），`CLIENT_RPC_PUSH` / `CLIENT_RPC_SERVER_ONLY` 方法提供默认空实现，避免业务层被迫实现推送占位。

## 3. 设计分析

### 3.1 流水线

```
mmorpg 契约 (proto/**, message_id.txt, event_id.txt)
        │ tools/ContractSync.java
        ▼
game-proto/src/main/proto/** + src/main/resources/contract/{message_id,event_id}.txt
        │ protobuf-maven-plugin (generate-sources)
        ├─▶ com.game.proto.** 消息类
        └─▶ target/classes/contract/contract.desc (includeImports + retainOptions)
                    │ javac 注解处理器 (game-proto-codegen: ContractProtoProcessor)
                    ▼
target/generated-sources/annotations/com/game/contract/generated/
  ├── ContractMessageIds.java          // 全部活消息号编译期常量 + nameOf(int)
  ├── ContractEventIds.java            // 全部活事件号编译期常量 + nameOf(int)
  ├── ContractNodeType.java            // 对齐 node.proto eNodeType（含直连/本地/Dubbo group 语义）
  ├── ContractClientRpcKind.java       // 对齐 proto_option.proto ClientRpcKind
  ├── ContractRouteEntry.java          // 不可变路由记录（messageId, service, method, fullMethod, nodeType, ...）
  ├── ContractRouteTable.java          // 编译期静态路由表（O(1) switch + clientRoutes / allRoutes / methodsOf）
  ├── ContractRpcMethod.java           // 泛型 RPC 方法描述 <Q extends Message, R extends Message>
  ├── rpc/<Service>Rpc.java            // 每服务类型化 RPC 桩常量（供 robot / 测试 / 调用方）
  └── service/
        ├── <Service>Handler.java      // 每服务类型化处理器接口 <C>
        └── <Service>ContractDispatcher.java // 每服务零反射 switch 派发器
```

### 3.2 节点类型解析规则（严格不依赖 proto 文件路径）

对每个服务 `S`（位于文件 `F`，proto 包 `P`），按以下优先级解析 `ContractNodeType`：
1. 服务标了 `OptionIsPlayerService = true` → `SCENE`（`SceneNodeService = 3`）；
2. 文件标了 `OptionFileDefaultNode = NODE_X`（非 `NODE_UNSPECIFIED`）→ 映射对应 `ContractNodeType`；
3. 按服务名 `S + "NodeService"` 精确匹配 `common.base.eNodeType`（如 `ContentGuard` → `ContentGuardNodeService`、`DataService` → `DataServiceNodeService`、`SceneManager` → `SceneManagerNodeService`、`PlayerInbox` → `PlayerInboxNodeService`）；
4. 按 proto 包名去掉末尾 `pb` 后转大驼峰再拼 `"NodeService"` 匹配 `eNodeType`（如 `loginpb` → `LoginNodeService`、`friendpb` → `FriendNodeService`、`guildpb` → `GuildNodeService`、`playerlocator` → `PlayerLocatorNodeService`、`playerstore` → `PlayerStoreNodeService`、`scene_node` → `SceneNodeService`）；
5. 均未命中 → `UNKNOWN`（仅允许内部多节点服务如 `RoleFenceParticipant`）；**若服务标了 `OptionIsClientProtocolService = true` 却解析为 `UNKNOWN`，编译期直接报错拒编**（对齐 C++ `protogen/internal/route_table.go:124`）。

### 3.3 `ClientRpcKind` 与 `hasResponse`

- 从方法选项扩展 `OptionClientRpcKind`（字段号从描述符集 `proto_option.proto` 按名查得，不写死）读取调用形态，缺省 `REQUEST`。
- `hasResponse` 与 `gate` 既有契约一致：输出消息全名不是 `Empty` 且不是 `google.protobuf.Empty`。

## 4. 漏洞分析与防错边界

1. **墓碑号与重复号**：`message_id.txt` / `event_id.txt` 中 `N=reserved:原名` 仅占号、不生成常量；活号与墓碑号冲突、活号重复、活方法名重复、墓碑缺原名一律在编译期报错失败。
2. **未同步的 `etcd` 服务号**：`message_id.txt` 含 `KV*` / `Watch*` / `Lease*` / `Cluster*` / `Maintenance*` / `Auth*`（Java 版不连 etcd，未同步其 proto）。生成器仅跳过在描述符集中找不到对应服务的键，并与 `MessageIdRegistry` 一致将它们记录在 `ContractRouteTable.UNRESOLVED_KEYS`；一旦某个已同步服务出现冲突或非法选项立即报编译错。
3. **protoc 内部类 / 外部类命名冲突**：proto 文件未设 `java_multiple_files = true` 时，消息位于外部类内部；嵌套消息使用 `.` 源码引用。生成器按 `FileDescriptorProto` 的 `java_package`、`java_multiple_files`、`java_outer_classname` 与冲突回退规则精确计算 Java 源码全限定名，保证 `javac` 当轮编译直接校验每个生成引用，不可能产出编译不过的悬空类名。
4. **扩展号不硬编码**：所有自定义 option（`OptionFileDefaultNode`、`OptionIsPlayerService`、`OptionIsClientProtocolService`、`OptionClientRpcKind`）均从 `FileDescriptorSet` 中扫描 `extension` 定义按名称反查字段号，再解析对应 `UnknownFieldSet`，避免扩展号变更导致静默失配。
5. **增量编译与测试重复生成**：`game-proto/pom.xml` 对 `default-compile` 开 `proc=full`，对 `default-testCompile` 设 `proc=none`（同 `game-table/pom.xml`），防止测试类路径出现重复生成类。
6. **高基数与安全边界**：生成代码纯静态只读、无可变全局状态、不暴露任何配置/密钥；`BattleClientPlayer` 在 `ContractNodeType.BATTLE` 显式标记 `directOnly = true`，`game-gate` 的直连拒绝闸保持不变。

## 5. 步骤分析

1. 新建编译期模块 `game-proto-codegen`（只依赖 `protobuf-java`），加入根 `pom.xml` `<modules>` 与 `<dependencyManagement>`，置位于 `game-proto` 之前。
2. 实现：
   - `ContractModel`（不可变数据结构：节点类型、RPC 方法、服务、事件）
   - `ContractDescriptorReader`（读 `contract.desc` + `message_id.txt` + `event_id.txt`，解析选项与节点归属，fail-closed 校验）
   - `ContractSourceGenerator`（生成 `ContractMessageIds`、`ContractEventIds`、`ContractNodeType`、`ContractClientRpcKind`、`ContractRouteEntry`、`ContractRouteTable`、`ContractRpcMethod`、`<Service>Rpc`、`<Service>Handler`、`<Service>ContractDispatcher`）
   - `ContractProtoProcessor`（javac 注解处理器 + `META-INF/services/javax.annotation.processing.Processor`）
3. 配置 `game-proto/pom.xml` 引入 `game-proto-codegen`（`provided`）并传递三个 `-Agame.proto.*` 编译参数。
4. 在 `game-proto` 编写单测，逐条对拍生成产物 `ContractRouteTable` / `ContractMessageIds` / `ContractEventIds` 与运行时 `MessageIdRegistry`、`event_id.txt`，并测试 `<Service>ContractDispatcher` 零反射分发与异常路径；在 `game-proto-codegen` 编写解析/失败边界单测。
5. 接入消费端：
   - `game-gate`：`MessageRoutes` 新增 `fromGenerated()` 与基于 `ContractRouteTable` 的校验，确认生成路由表与运行时表完全一致；
   - `game-robot`：`GameConnection` / `BattleDirectConnection` 增加 `call(ContractRpcMethod<Q, R>, Q, Duration)` 类型化调用重载。
6. 更新 `AGENTS.md §1` 与 `docs/design/architecture.md`、`PARITY.md`。

## 6. 执行步骤

1. 落盘本设计文档 `docs/design/proto-codegen.md`。
2. 创建 `game-proto-codegen` 模块代码与测试，接入根 `pom.xml` 与 `game-proto/pom.xml`。
3. 执行 `mvn -o -B -pl game-proto-codegen,game-proto -am clean test` 验证生成与单测。
4. 接入 `game-gate` 与 `game-robot`，执行 `mvn -o -B -pl game-proto-codegen,game-proto,game-gate,game-robot -am test` 并执行全量 `clean install -DskipTests` 确认 34 模块全部编译通过。
5. 更新 `AGENTS.md §1`、`PARITY.md`，提交并推送 `origin/main`。
