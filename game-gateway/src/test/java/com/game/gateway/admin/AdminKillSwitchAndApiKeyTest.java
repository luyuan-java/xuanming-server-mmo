package com.game.gateway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

/** 网关运维热关停管理接口与管理口令闸回归（对齐主仓 AdminKillSwitchControllerTest 与 AdminApiKeyFilterTest）。 */
class AdminKillSwitchAndApiKeyTest {

    private static final String UUID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_2 = "22222222-2222-2222-2222-222222222222";

    private static final class MemoryStore implements KillSwitchRuleStore {
        final Map<String, String> rules = new HashMap<>();
        final Map<String, Set<String>> nodes = new HashMap<>();

        @Override
        public Map<String, String> list() {
            return Map.copyOf(rules);
        }

        @Override
        public void put(String relativeKey, String value) {
            rules.put(relativeKey, value);
        }

        @Override
        public long delete(String relativeKey) {
            return rules.remove(relativeKey) != null ? 1L : 0L;
        }

        @Override
        public Set<String> registeredNodeUuids(String nodeType) {
            return nodes.getOrDefault(nodeType, Set.of());
        }
    }

    @Test
    void 管理口令闸按规范化路径防分号绕过_空口令全拒_写操作强制操作人_机器口令限表目录() throws Exception {
        // 1. 未配口令：任何 /admin/** 全部 401（fail-closed）
        AdminApiKeyFilter emptyKey = new AdminApiKeyFilter("");
        MockHttpServletRequest r1 = new MockHttpServletRequest("GET", "/admin/killswitch");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        emptyKey.doFilter(r1, resp1, new MockFilterChain());
        assertThat(resp1.getStatus()).isEqualTo(401);

        // 2. 分号路径参数绕过拦截（/admin;x=1/killswitch 仍命中管理面）
        String adminKey = "super-secret-admin-key-0123456789";
        String machineKey = "machine-table-bundle-key-0123456789";
        AdminApiKeyFilter filter = new AdminApiKeyFilter(adminKey, machineKey);

        MockHttpServletRequest bypass = new MockHttpServletRequest("GET", "/admin;x=1/killswitch");
        bypass.setServletPath("/admin/killswitch");
        MockHttpServletResponse bypassResp = new MockHttpServletResponse();
        filter.doFilter(bypass, bypassResp, new MockFilterChain());
        assertThat(bypassResp.getStatus()).isEqualTo(401);

        // 3. 正确人工口令 GET 放行，PUT 缺 X-Admin-Operator 拒 400，带 table-bundle 拒 400，带合规操作人放行
        MockHttpServletRequest getOk = new MockHttpServletRequest("GET", "/admin/killswitch");
        getOk.addHeader("X-Admin-Key", adminKey);
        MockHttpServletResponse getResp = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(getOk, getResp, (req, res) -> called.set(true));
        assertThat(called.get()).isTrue();

        MockHttpServletRequest putNoOp = new MockHttpServletRequest("PUT", "/admin/killswitch");
        putNoOp.addHeader("X-Admin-Key", adminKey);
        MockHttpServletResponse putNoOpResp = new MockHttpServletResponse();
        filter.doFilter(putNoOp, putNoOpResp, new MockFilterChain());
        assertThat(putNoOpResp.getStatus()).isEqualTo(400);

        MockHttpServletRequest putSpoofMachine = new MockHttpServletRequest("PUT", "/admin/killswitch");
        putSpoofMachine.addHeader("X-Admin-Key", adminKey);
        putSpoofMachine.addHeader("X-Admin-Operator", "table-bundle");
        MockHttpServletResponse putSpoofResp = new MockHttpServletResponse();
        filter.doFilter(putSpoofMachine, putSpoofResp, new MockFilterChain());
        assertThat(putSpoofResp.getStatus()).isEqualTo(400);

        // 4. 机器口令访问 /admin/killswitch 拒 403；访问 /admin/tables 且操作人为 table-bundle 放行
        MockHttpServletRequest machineWrongPath = new MockHttpServletRequest("PUT", "/admin/killswitch");
        machineWrongPath.setServletPath("/admin/killswitch");
        machineWrongPath.addHeader("X-Admin-Key", machineKey);
        machineWrongPath.addHeader("X-Admin-Operator", "table-bundle");
        MockHttpServletResponse machineWrongResp = new MockHttpServletResponse();
        filter.doFilter(machineWrongPath, machineWrongResp, new MockFilterChain());
        assertThat(machineWrongResp.getStatus()).isEqualTo(403);

        MockHttpServletRequest machineOk = new MockHttpServletRequest("POST", "/admin/tables/publish");
        machineOk.setServletPath("/admin/tables/publish");
        machineOk.addHeader("X-Admin-Key", machineKey);
        machineOk.addHeader("X-Admin-Operator", "table-bundle");
        AtomicBoolean machineCalled = new AtomicBoolean();
        filter.doFilter(machineOk, new MockHttpServletResponse(), (req, res) -> machineCalled.set(true));
        assertThat(machineCalled.get()).isTrue();
    }

    @Test
    void 运维热关停接口支持全局与节点类型与实例三级范围并核对在线发现() {
        MemoryStore store = new MemoryStore();
        store.nodes.put("SceneNodeService", Set.of(UUID_1));
        AdminKillSwitchController ctrl = new AdminKillSwitchController(store);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/admin/killswitch");
        req.addHeader("X-Admin-Operator", "ops-alice");

        // 1. 节点类型无在线节点 -> 400
        assertThatThrownBy(() -> ctrl.put(
                new AdminKillSwitchController.RuleRequest("*", "GateNodeService", null, true, "test", 0), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no node is registered");

        // 2. 实例不在线 -> 400；给 instance 不给 node_type -> 400
        assertThatThrownBy(() -> ctrl.put(
                new AdminKillSwitchController.RuleRequest("*", "SceneNodeService", UUID_2, true, "test", 0), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no SceneNodeService node");
        assertThatThrownBy(() -> ctrl.put(
                new AdminKillSwitchController.RuleRequest("*", null, UUID_1, true, "test", 0), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("instance requires node_type");

        // 3. 在线 type 与 instance 写入成功并可列举、删除，写坏的值在 list 里标 valid=false
        var v1 = ctrl.put(new AdminKillSwitchController.RuleRequest("ClientPlayerChat/*", "SceneNodeService", null, true, "降级", 14), req);
        assertThat(v1.key()).isEqualTo("type/SceneNodeService/ClientPlayerChat/*");
        assertThat(v1.scope()).isEqualTo("type");
        assertThat(v1.valid()).isTrue();

        var v2 = ctrl.put(new AdminKillSwitchController.RuleRequest("*", "SceneNodeService", UUID_1, true, "摘单节点", 0), req);
        assertThat(v2.key()).isEqualTo("instance/" + UUID_1 + "/*");
        assertThat(v2.scope()).isEqualTo("instance");

        store.rules.put("ClientPlayerFriend/AddFriend", "{broken");
        var all = ctrl.list();
        assertThat(all).anySatisfy(v -> {
            assertThat(v.key()).isEqualTo("ClientPlayerFriend/AddFriend");
            assertThat(v.valid()).isFalse();
        });

        var del = ctrl.delete("type/SceneNodeService/ClientPlayerChat/*", req);
        assertThat(del.deleted()).isEqualTo(1L);
    }
}
