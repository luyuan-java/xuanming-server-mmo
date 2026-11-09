package com.game.gateway.hotfix;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.gateway.assign.AssignGateMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 使用正式 Jackson 配置、Controller、配置绑定与服务，核对客户端 HTTP 契约。 */
@WebMvcTest(controllers = HotfixCheckController.class, properties = {
        "game.gateway.hotfix.platforms.android.latest-version=1.10.0",
        "game.gateway.hotfix.platforms.android.min-version=1.2",
        "game.gateway.hotfix.platforms.android.patch-url=https://cdn.example.invalid/a.zip",
        "game.gateway.hotfix.platforms.android.changelog=修复说明"
})
@Import(HotfixConfiguration.class)
class HotfixCheckHttpTest {
    @Autowired private MockMvc mvc;
    // WebMvcTest 仍扫描 ControllerAdvice；保留正式 advice 的适用范围，只补齐它的指标依赖。
    @MockitoBean private AssignGateMetrics assignGateMetrics;

    @Test
    void 请求与响应均为下划线字段_强更返回补丁与说明() throws Exception {
        mvc.perform(post("/api/hotfix-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"android\",\"client_version\":\"1.1.9\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.need_update").value(true))
                .andExpect(jsonPath("$.force_update").value(true))
                .andExpect(jsonPath("$.latest_version").value("1.10.0"))
                .andExpect(jsonPath("$.patch_url").value("https://cdn.example.invalid/a.zip"))
                .andExpect(jsonPath("$.changelog").value("修复说明"))
                .andExpect(jsonPath("$.needUpdate").doesNotExist())
                .andExpect(jsonPath("$.latestVersion").doesNotExist());
    }

    @Test
    void 已是最新仅返回布尔值与最新版本() throws Exception {
        mvc.perform(post("/api/hotfix-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"android\",\"client_version\":\"1.10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.need_update").value(false))
                .andExpect(jsonPath("$.force_update").value(false))
                .andExpect(jsonPath("$.latest_version").value("1.10.0"))
                .andExpect(jsonPath("$.patch_url").doesNotExist())
                .andExpect(jsonPath("$.changelog").doesNotExist());
    }

    @Test
    void 未知平台不产生虚构版本或空字段() throws Exception {
        mvc.perform(post("/api/hotfix-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ios\",\"client_version\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.need_update").value(false))
                .andExpect(jsonPath("$.force_update").value(false))
                .andExpect(jsonPath("$.latest_version").doesNotExist());
    }

    @Test
    void 非法请求回400_不伪装不用更新() throws Exception {
        for (String body : new String[] {"{}", "null", "{", "", "{\"platform\":\"android\"}",
                "{\"client_version\":\"1\"}", "{\"platform\":\"ios\",\"client_version\":\"bad\"}"}) {
            mvc.perform(post("/api/hotfix-check").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
