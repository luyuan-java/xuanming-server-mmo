# 通用导航读取库：固定 MSET / UE ABI（2026-10-27）

本批交付 `game-navigation` 的纯 JDK 21 读取库及测试源，**尚未编译或运行 Java 测试**。根代理已逐文件核对候选 SHA-256 后接入独立 Java 工作树，并将模块加入根 pom。外部候选和集成回执保留供审查；C++ 工作树、受管导航/表/协议产物与 `SOURCE.properties` 未改。此阶段只做文件修改与静态检查，尚无本模块编译或测试运行证据。

本候选补上 7.5 的 MSET 解析与结构校验基础，不表示完整导航能力已迁完。现有 main / penglai / donghai 三图保守位图及 15 个配置复用保持原状；配置 4、17–21 的场景导航接入仍未完成。

## 1. 固定事实源与 ABI

- 根 C++ / Go：`92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`。
- 根树 gitlink `third_party/ue5navmesh`：`aacf2f82af2e24c56474855fce057018928735bb`，本地固定对象可读取。
- 所有源码事实用 `git show <固定提交>:<路径>` 读取，不以子模块或根仓库当前工作树为证据。
- 先读 `docs/porting/inventory/full-migration-contract-nav-20261026.md` 和旧 `contract-navmesh-spec.md`；旧文档的几何数量、旧墙坐标、未证明 ABI 推断不作为本批金样。

| 固定来源 | 本次核定的事实 |
|---|---|
| 根 `cpp/libs/services/scene/spatial/system/recast.cpp` 的 `NavMeshSetHeader` / `NavMeshTileHeader` / `LoadNavMesh` | MSET magic 为 `0x4d534554`，版本 1；原生结构写入，外头 96 字节，tile 记录头 16 字节 |
| 根 `tools/navmesh_baker/navmesh_baker.cpp` 的 `SaveNavMesh` | 头部 memset 清零后 fwrite；保存的是已 addTile 的 tile data，不是未经装载的 builder 缓冲 |
| 根 `tools/navmesh_baker/CMakeLists.txt:3` | baker 与 runtime 共用 UE 源，`dtReal=double`，`WITH_NAVMESH_*` 均未定义 |
| 子模块 `Public/Detour/DetourLargeWorldCoordinates.h:7` | 大世界坐标未禁用，`dtReal` 为 double；本 profile 所有序列化坐标、尺寸、半径、量化因子均为 IEEE754 64 位，不含 float 坐标段 |
| 子模块 `Public/Detour/DetourNavMesh.h:35`、`:90`、`:200`、`:236`、`:247`、`:260`、`:308`、`:371` | 默认 `USE_64BIT_ADDRESS=1`；tile 版本 7；各结构的固定字段顺序如下 |
| 子模块 `Public/Detour/DetourCommon.h:527` | double profile 的 `dtAlign(x)=(x+7)&~7`，对数组段总长对齐，不是把每个 detail mesh 对齐 |
| 子模块 `Private/Detour/DetourNavMesh.cpp:861`、`:1716`、`:1768` | 引用位宽由声明容量计算；拒重复坐标和已占用槽；实际段顺序与 builder 一致 |
| 子模块 `Private/Detour/DetourNavMeshBuilder.cpp:473`、`:503`、`:562` | BV 分配 2*n 个节点，全缓冲清零；二叉树实际写 2*n-1 个，最后一个全零节点合法 |

### 外层和字段偏移

仅支持 **little-endian / double / uint64 / 无 segment、cluster 扩展** 的固定 ABI，不把版本号相同的其它原生布局猜成此格式。

MSET 头共 96 字节：

| 偏移 | 内容 |
|---:|---|
| 0 / 4 / 8 | int32 magic / version / numTiles |
| 12 | 4 个零填充字节 |
| 16 / 24 / 32 / 40 | double walkableHeight / walkableRadius / walkableClimb / bvQuantFactor |
| 48 | double origin[3] |
| 72 / 80 | double tileWidth / tileHeight |
| 88 / 92 | int32 maxTiles / maxPolys |

每个 tile 记录是 `uint64 tileRef, int32 dataSize, 4 个零填充字节, dataSize 字节 tile data`。不接受原版 loader 的“零 ref 或零 size 就提前 break”；声明的 tile 必须全部完整解析，最后必须 EOF。

tile 头共 80 字节：

| 偏移 | 内容 |
|---:|---|
| 0 / 2 / 4 / 6 | u16 version / layer / polyCount / vertCount |
| 8 / 12 | int32 x / y（本库命名 z，表示水平网格第二轴） |
| 16 / 18 / 20 / 22 | u16 maxLinkCount / detailMeshCount / detailVertCount / detailTriCount |
| 24 / 26 / 28 / 30 | u16 bvNodeCount / offMeshConCount / offMeshBase / 零填充 |
| 32 / 56 | double bmin[3] / bmax[3] |

其后顺序与未对齐单项大小：vertices=24、polygons=32、links=16、detailMeshes=6、detailVertices=24、detailTriangles=4、BV=16、pointOffMesh=72。**每个数组段的总字节数独立 align8**。长度用 long 算完并与 dataSize 严格相等后才分配对象。

polygon：u32 firstLink、u16 vertices[6]、u16 neighbors[6]、u16 flags、u8 vertexCount、u8 areaAndType。detail：u16 vertexBase、u16 triangleBase、u8 vertexCount、u8 triangleCount。detail triangle：3 个 u8 顶点索引与 u8 edgeFlags，固定 Recast 生产者有效标志 mask 为 `0x15`。

BV：u16 bmin[3]、u16 bmax[3]、int32 index；非负为 ground polygon，负数为子树节点数的相反数。point off-mesh：double pos[6]、double radius、double snapHeight、u32 userId、u16 polygon、u8 side、u8 flags；side=`0x80` 为内部，0..7 为方向；flags 的 `0x80/0x40` 分别是双向/廉价区域。负 snapHeight 合法，表示用 step height。`addTile` 可投影 navVerts 端点，因此不强制 con.pos 与 navVerts 相等。

### 引用、容量与运行时缓存

`tileBits=ceil(log2(maxTiles))`、`polyBits=ceil(log2(maxPolys))`、`saltBits=min(31,64-tileBits-polyBits)`，salt 至少 5 位。编码为 `salt << (tileBits+polyBits) | slot << polyBits | polygonIndex`。tileRef 的 polygon 位必须为零、salt 非零、slot 小于容量、未用高位为零。重复槽位即使换 salt 也拒绝；重复 `(x,z,layer)` 拒绝。

声明容量不触发相应大小数组分配。本实现支持正值到 `2^30`，拒绝 nextPow2 溢出和不足 salt 位；实际计数受 u16 和显式资源预算共同限制。启用 BV 时 groundCount*2 必须可由 u16 表示，亦即 groundCount 不超过 32767。

`polygon.firstLink` 和整段 links 是 **装载时重建的缓存**；子模块 endian 转换也明确跳过它们。候选跳过其内容，不把可能陈旧的 serialized links 当已验证引用，也不向 API 暴露它们。保留静态 link 容量并核对固定 builder 的 `ground 边数 + 2*portal 边数`。

静态 neis=0 为 wall，普通正值为同 tile 的 1-based polygon 引用，要求反向共享边一致；`0x8000|{0,2,4,6}` 是 x+/z+/x-/z- portal 方向，须在对应 tile 平面。portal **不是已解析的跨 tile 引用**：固定源码找到邻 tile 才建立链接，缺邻 tile 可合法无连接。此库保留方向，不声称已执行跨 tile portal 匹配、层间链接或 off-mesh 吸附。

## 2. API 与失败边界

```java
NavMesh mesh = new MsetReader().read(path);
NavMesh.Tile tile = mesh.tiles().getFirst();
long ref = tile.polygonReference(0);
NavMesh.PolygonLocation polygon = mesh.resolve(ref).orElseThrow();
NavMesh.Vec3 point = tile.detailVertex(0, 0);
```

支持 Path、调用方拥有的 InputStream、byte[]；InputStream 不被关闭，Path 包装流会关闭，byte[] 被防御复制。无全局注册、无逐 tile 回调、无共享可变查询工作区。方法只在全部 tile 与尾部校验完成后返回完整 NavMesh；失败抛 `NavMeshFormatException`（IOException 子类），含稳定 Code 与绝对字节 offset。失败不会影响同一个读取器的下一次调用。

所有对外集合由 `List.copyOf/Map.copyOf` 封装，嵌套值对象不可变；NavMesh 构造器仅包内使用。`resolve` 验证规范位、salt、槽位与 polygon 上界。Java long 保留 uint64 位模式；将来输出 JSON 时不能当 JavaScript 安全整数裸写，应使用无符号字符串。

默认限额：32 MiB 文件、8 MiB 单 tile、4096 个实际 tile、262144 个 polygon、524288 个主/细节顶点总数、1048576 个 detail triangle；每个数值须 finite 且绝对值不超过 1e9。流只多读 1 字节确认超限。Limits 可显式配置，不以输入声称的容量提升预算。

校验包括：未知版本、截断/负长度/额外字节、保留填充非零、重复 tile、容量/引用、计数交叉约束、全部 double 的 NaN/Inf、tile 网格/AABB、polygon 顶点/凸性/自交、内部邻接/portal 方向、detail 分段和顶点索引、重复/反向/退化三角、内部三角边配对/曲面缺口/面积一致、BV 二叉结构/escape/完整叶覆盖/父子 AABB/叶几何范围、point off-mesh 的本地结构。BV 使用显式栈，避免恶意深树递归栈溢出。

BV 的合法全零容量尾节点被验证后移除；API 的 bvTree 仅含有效 `2*n-1` 节点。BV=0 合法。BV 叶覆盖 ground 三轴和细节三角引用点的 Y，量化重算容差 1e-6；不额外要求源码未生成的 detail X/Z BV 覆盖。网格/portal 边界比较使用 1e-8 或 8 ULP；detail 水平拓扑使用 1e-8，面积使用 perimeter*1e-8 或 64 ULP。每条 detail edge 的边界位还按固定 Recast `getEdgeFlags` 的端点到同一 polygon 边的距离平方 `< double(float(0.001f * 0.001f))` 规则核验，不能把合法 mask 内的全零标志直接当正确。本库比 C++ addTile 更严格；固定源支持任意外部 detail 输入的 memcpy，不等于这些输入都能通过本库严格曲面校验。

## 3. 六份固定产物的只读取证

本次用轻量 Python 进程在内存里读取 `git show 92952c84...:data/scene_nav_bin/<文件>`，未将 C++ 目录或 binary 复制进候选，未执行 C++。摘要另见 `navigation-reader-evidence-20261027.json`。

| 文件 | 字节 | tile | polygon | 主顶点 | detail triangle |
|---|---:|---:|---:|---:|---:|
| main_scene.bin | 121728 | 55 | 599 | 1134 | 994 |
| penglai_scene.bin | 113936 | 40 | 551 | 1117 | 1000 |
| donghai_scene.bin | 124888 | 47 | 616 | 1189 | 1075 |
| lanxian_scene.bin | 102088 | 45 | 486 | 1006 | 900 |
| dungeon_scene.bin | 175024 | 61 | 848 | 1676 | 1534 |
| mirror_scene.bin | 175024 | 61 | 848 | 1676 | 1534 |

六文件均无 extra detail vertices 和 off-mesh；dungeon/mirror 字节相同，合计五份不同内容。它们均 `height=1.8,radius=0,climb=0.35,bvQuantFactor=4,tileWidth=tileHeight=32,maxPolys=1048576`；maxTiles 为 main/donghai/dungeon/mirror 的 128 与 penglai/lanxian 的 64。实际 portal 标志只有上述四个方向。长度、内部反向邻接、主顶点 AABB、网格坐标、BV 容量尾零以及独立镜像的主要结构校验均符合固定布局。这是**取证脚本结果，不是 Java 运行证据**。

配置复用仍按库存：main=1、5–16；penglai=2；donghai=3；lanxian=4；dungeon=17–19；mirror=20–21。没有把未被这些 BaseScene 引用的额外二进制自动注册进场景。

## 4. 接入与后续验证

`game-navigation/`、本文及证据 JSON 已经接入，根 pom 已应用 module 声明。固定六份二进制已从固定 git 对象导出至仓外 `D:/luyuan/tools/review-port-next/fixed-nav-92952c84`，逐文件大小与 SHA-256 一致；这是输入准备，不是 Java 解析通过。后续全量应显式给出该目录，使固定产物用例实际执行。

本批没有场景消费者，不增加任何生产依赖或第三方库；测试使用仓内已有的 `spring-boot-starter-test`。

后续解除冻结且明确分配构建时段后，才运行（离线，不安装依赖）：

```powershell
./mvnw -o -B -pl game-navigation -am test
./mvnw -o -B -pl game-navigation -am test '-Dgame.it.navmesh.fixedDir=<固定六文件导出目录>'
```

`MsetReaderTest` 有 30 个人工合法/损坏测试：手工两三角网格、无 BV、多 layer、point off-mesh、extra detail vertex、每个截断前缀、长度/配额、引用位与 salt、重复 tile、NaN/Inf、AABB、邻接、portal、detail/BV/off-mesh 损坏、五角星自交、重复三角/缺半面、不可变性及流所有权。后续独立静态复核补入两个边界阈值用例：固定源的 `rcSqr(0.001f)` 是 float 平方后转 double；实现保留该平方比较，而非 `hypot < 0.001f`，避免误拒绝两种阈值之间的合法标志。`FixedNavMeshArtifactsTest` 是第 31 个测试，未给目录时显式跳过；给目录后逐个核对完整 SHA-256、尺寸/计数并解析所有 polygon ref，路径内任一当前版文件不能冒充固定版本。未实际运行时不能写“31 项通过”。

外部候选根目录的 `manifest.json` 记录九个交付文件的 SHA-256，以及根 pom 修改前 SHA-256。`patches/root-pom.patch` 已对当前独立工作树执行只读 `git apply --check`；此检查只证明补丁可应用，不证明 Java 编译或测试通过。此前六 blob 的只读取证仍保留，不把后续新增的平方阈值用例算作已运行。

## 5. 尚未交付的原版能力

- 最近点、区域过滤、同距选择、BV 候选顺序、raycast、跨 tile portal 匹配与量化、层间/off-mesh 动态连接。
- findPath/findStraightPath、partial path 语义和线程独立查询工作区。
- scene 注入、按文件复用/按场景出生点探针、剩余 6 个配置的导航接入、MoveGuard/外推/robot 验收。不得顺手改变现有先扣额度再夹持且不退款的约定。
- Java 导航生成工具及 OBJ/mask/painted-city 离线校验；读取已有 bin 不代表已有烘焙器。
- C++/Java 查询行为金样与精确差分；没有运行原版可执行程序，不将旧墙坐标当当前结果。
- big-endian、float/32-bit ref、其它 MSET/tile 版本、segment/cluster 扩展，以及来源未核实的 ABI。版本相同也不自动放行其它 profile。

本库是服务端导航产物消费的基础，并非客户端完整契约门禁；持久 schema 台账也不在本批范围。
