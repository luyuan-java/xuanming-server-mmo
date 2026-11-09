# 九图节庆地图：Java 隔离联调接入（2026-11-09）

本次只接固定 9 个 ID、15 套外观对应的同源导航。正式 `config-data/tables`、`config-data/navmesh/current_maps`、`contract/SOURCE.properties` 没有改动；客户端 `clientOnly=true`、正式传送门禁继续保留。没有部署、重启服务或登录远端。

## 输入身份

- Java 基线：`d948e110865bba3bcc56fd0b62456e271a5281b2`；本次代码尚未提交。
- 客户端基线：`dfe483ce139e042f2944d1e50b7e1d1d7642d5a8`。
- 冻结交接 `server-handoff-data.json` SHA256：`8c8678bf19ad568feda870834e23ea8fa7ffc2d51fb7ee90139c87e970bf0b51`。
- `FestivalMapNavigation` 逐图绑定交接 canonicalAppearance 的 `source.json` 原字节 SHA；source 再绑定 `walkmask.txt` 编码和 packed SHA。测试资源只保存这 18 份小文本原件及禁换行属性，不复制 PNG。
- C++ 生成目录：`data/scene_nav_bin/festival_maps/<paddedId>/`；Java 读取其原始 `source.json` 和 `walkmask.txt`。Java 本次沿用已有栅格裁决，不读取 C++ `scene.bin` 或 `.mask`，不宣称与 MSET 查询行为完全等价。

| padded ID | 模板 ID | 可走格 | 连通域 |
|---|---:|---:|---:|
| 01001 | 1001 | 250 | 1 |
| 01002 | 1002 | 309 | 1 |
| 05008 | 5008 | 2313 | 1 |
| 10000 | 10000 | 7268 | 2 |
| 19004 | 19004 | 48 | 1 |
| 21003 | 21003 | 3218 | 1 |
| 23006 | 23006 | 833 | 1 |
| 28007 | 28007 | 1481 | 1 |
| 35004 | 35004 | 2566 | 3 |

`10000` 画面接缝待修；`19004` 不开放其他林网；`35004` 不连桥。外观由客户端在已交付列表中选择，服务器不新增未交付外观。

## 配置与消费路径

`CurrentMapNavigation` 新增矩形几何参数，旧 150×150 三图构造和读取合同保持。坐标只在输入读取处从 Unity 转为服务器 `(z,x,y)`；网格查位使用世界原点和北到南 MSB 行序。移动逐格遍历，斜角须同时经过两个侧邻格；有效矩形使用半开边界，最后不足 2 m 的行列在实际边界阻挡，不能扩大为整格。

`ConfigSceneTables` 对固定九图模板及 `festival_maps` 路径专门校验完整导航映射，缺开发路径、错 ID、错 SHA、缺文件或出生点不符直接拒启，不退到 `SceneNavigation.NONE`。XLSX 十进制往返仅允许出生坐标 1e-9 m 内的表示差，并要求实际可走。

隔离开发启动须同时指定以下属性，且继续使用已有账号、凭据和隔离依赖配置；本文件不直接启动服务：

```text
game.run-mode=dev
game.table-dir=<C++正式导表器生成的隔离candidate/generated/tables>
game.scene.festival-test.enabled=true
game.scene.festival-test.navigation-root=<C++根>/data/scene_nav_bin/festival_maps
game.scene.festival-test.current-navigation-root=<Java根>/config-data/navmesh/current_maps
```

`enabled` 缺省 false；`prod`、缺失或未知运行模式即使显式开也拒启。现有正式 World/BaseScene 不因开关增加地图；必须指向另行审核的隔离导表产物。此消费路径不写同步产物，后续正式交付仍须经 `ContractSync` 明确扩展、成对提交与验收。

## 验证与未完成项

`build/festival-java-test-20261109-r2.log`：13 个 reactor 模块编译成功；20 项实际执行通过，真实候选表用例因未提供参数跳过 1 项。覆盖九图全部格、19004 48 格、10000/35004 跨域线段、末格有效裁切、负原点、斜角、NaN/Inf、坏 SHA/缺文件以及旧三图与 SceneWorld 移动回归。

`build/festival-java-test-20261109-r4.log`：2026-11-09 04:25:02 America/New_York 完成，退出码 0；13 个 reactor 模块编译成功，21 项测试、0 失败、0 错误、0 跳过。真实读取 `D:/luyuan/xuanming-server-mmo-cpp/build/festival-maps-20261109/tables-r2/candidate/generated/tables` 与 `D:/luyuan/xuanming-server-mmo-cpp/data/scene_nav_bin/festival_maps`，九图 World/模板号/导航/出生点配对通过，默认装配拒绝候选表。生产代码和测试的输入 SHA、五个 JUnit XML 汇总保存在 `build/festival-java-20261109-result.json`。复现命令为：

```powershell
mvn -B -o -pl game-scene -am test '-Dtest=CurrentMapNavigationTest,FestivalMapNavigationTest,FestivalTestPropertiesTest,ConfigSceneTablesTest,SceneWorldNavigationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' '-Dgame.festivalTestTables=<隔离candidate/generated/tables>' '-Dgame.festivalNavigationRoot=<C++根>/data/scene_nav_bin/festival_maps'
```

最初命令有 PowerShell 未加引号的 dotted `-D` 参数错误（r1）；尝试直接调用 `surefire:test` 未走生命周期导致 reactor 测试依赖未解析（r3）；均不是通过证据，日志保留。未运行真实 Java 服务登录、切图、断线恢复或双客户端协议联调，没有实机场景或视觉验收证据。

后续独立审查发现既有 `SceneWorld.teleportWithinScene` 只核世界范围，归队调用可绕过节庆平台限制。追加补丁只对固定九图要求 `CurrentMapNavigation` 已载入、起终点可走且同一四连通域；装配时预标记连通域，瞬移时不遍历地图。目标高度在 0.2 m 内归零，超出拒绝，旧三图的瞬移行为保持。`FestivalTeleportTest` 使用真实 10000 双域与 35004 三域安全格验证归队及管理入口，另覆盖域内落位、坏落点、缺导航与旧图行为；矩形测试补单侧阻挡的混合方向穿角。

追加补丁的 `build/festival-java-test-20261109-r5.log` 于 2026-11-09 04:43:22 America/New_York 完成，退出码 0；13 个 reactor 模块编译成功，66 项测试、0 失败、0 错误、0 跳过。仅在上方命令的 `-Dtest` 列表追加 `FestivalTeleportTest,TeleportWithinSceneTest,TeamFollowServiceTest`，保留真实候选表与导航参数；其中 3 项节庆瞬移、14 项通用瞬移、28 项组队回归均通过。11 份记录的代码输入前后 SHA 一致，8 份 JUnit XML 和 23 份真实候选表/原始导航文件 SHA 存于 `build/festival-java-20261109-r5-result.json`；`build/festival-java-20261109-result.json` 指向同一最新结果，旧 r4 摘要另保留为 `build/festival-java-20261109-r4-result.json`。本轮不扩展其他构建或服务启动，实机与联机边界仍如上所述。
