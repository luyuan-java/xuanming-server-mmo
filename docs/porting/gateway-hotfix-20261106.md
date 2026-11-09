# 客户端热更检查移植（2026-11-06）

## 复核基线

只读对照 C++/Go 仓库固定提交 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 的 Java 网关：

- `java/gateway_node/src/main/java/com/game/gateway/service/HotfixCheckService.java`：已按平台配置发布线，不再是恒回 `1.0.0` 的桩。
- 同目录的 `config/HotfixProperties.java`、`controller/HotfixCheckController.java`、`dto/HotfixCheckRequest.java`、`dto/HotfixCheckResponse.java`。
- `src/main/resources/application.yaml` 的全局 Jackson `SNAKE_CASE` 配置；`src/test/java/com/game/gateway/service/HotfixCheckServiceTest.java` 的版本及配置边界。

本仓库旧 `docs/porting/inventory/java-infra.md` 的 `gateway-hotfix-check` 描述是历史盘点，不能继续据此实现恒假应答。此次未修改源仓库或同步产物。

## Java 实现

`game-gateway` 新增按功能组织的 `com.game.gateway.hotfix` 包。使用不可变 record 保存请求、应答、发布线、数字版本；构造器注入服务，`HotfixConfiguration` 由正式 `GatewayConfiguration` 导入，Spring Boot 在启动时绑定并校验。无新依赖、无数据库/Redis/RPC 调用、无后台线程。

客户端接口保持 `POST /api/hotfix-check`：

```json
{"platform":"android","client_version":"1.1.9"}
```

```json
{"need_update":true,"force_update":true,"patch_url":"https://cdn.example.invalid/a.zip","latest_version":"1.2.3","changelog":"修复说明"}
```

- 平台按原字符串精确匹配，不转换大小写或修剪。
- 版本允许一至四个 ASCII 十进制段，每段一至九位。按整数段比较，缺段补零，前导零合法；`1.10 > 1.9`，`1.2 == 1.2.0`。不接受后缀、符号、空白或第五段。
- 未配置平台返回两个 `false`，省略全部可空字段；即使平台未知，也先拒绝非法请求版本。
- 客户端等于或高于最新版本时不更新，仍返回配置中的最新版本原文，省略补丁与说明。
- 低于最新版本才提供补丁。严格低于 `min-version` 才强制更新；等于最小版本不强更，不配最小版本也不强更。
- 请求缺字段、非法版本或缺请求体为 HTTP 400；正常应答 HTTP 200，无额外 `code` 信封。沿用网关全局 snake_case，空值不序列化。

配置使用本版的 `game.gateway.hotfix.platforms`，不同于原版内部键 `hotfix.platforms`：

```yaml
game:
  gateway:
    hotfix:
      platforms:
        android:
          latest-version: "1.2.3"
          min-version: "1.2.0"
          patch-url: "https://cdn.example.invalid/a.zip"
          changelog: "修复说明"
```

缺省发布线为空。最新版本必须合法，最小版本若配置则必须合法且不高于最新版本，补丁地址不可为空白；额外拒绝无法由合法请求命中的空平台配置。地址按基线只检查非空，不伪称已经验证下载可用性。配置与集合均为不可变快照，改配置后重新发布服务；此接口不下载、签名、安装补丁，也未接动态刷新。

## 验证与边界

新增 16 个 JUnit 测试方法源码：服务 7、真实配置绑定 5、HTTP 切片 4。覆盖可选/强制更新、边界版本、未知平台、不可变快照、错误配置拒启、snake_case、字段省略与 HTTP 400。原 `GatewayConfigurationTest` 断言全部保留，仅追加正式服务装配断言。

按用户要求，其他重型构建运行时不编译、不执行测试。本项当前只有源码与静态复核，尚无编译或测试通过证据；客户端真实补丁下载流程不在本项验收范围。没有提交、推送或合并。
