package com.game.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 用仓库里的 application.yaml 整体起一次（RedissonClient 用替身，不连 Redis），确认默认配置能绑定、能对外服务，
 * 以及管理端点按配置暴露在<b>独立的管理端口</b>上、对外端口看不到（{@link AutoConfigureObservability} 打开测试里默认关闭的 Prometheus 导出）。
 * 用真实端口起服务（主端口与管理端口都随机），与生产的「管理端口独立、只绑本机」同形。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
// xm.zone-id 显式钉住，免得开发机上的 XM_ZONE_ID 环境变量改掉默认区服。
@TestPropertySource(properties = {GatewayConfiguration.TOKEN_SECRET_ENV + "=boot-test-secret", "xm.zone-id=1",
        "management.server.port=0",
        // MySQL 换成 MySQL 兼容模式的内存库：建表脚本与启动播种照常执行
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:gwapp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class GatewayApplicationTest {

    @MockitoBean
    private RedissonClient redisson;

    @Autowired
    private ZoneDirectory zones;

    @Autowired
    private MockMvc mvc;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    private static HttpResponse<String> httpGet(int port, String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void 默认配置_一区开放且推荐() throws Exception {
        ZoneRow zone = zones.find(1).orElseThrow();
        assertThat(zone.status()).as("按配置播种").isEqualTo(ZoneManualStatus.OPEN);
        assertThat(zone.recommended()).isTrue();

        mvc.perform(get("/api/server-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones[0].zone_id").value(1))
                .andExpect(jsonPath("$.zones[0].recommended").value(true));
    }

    @Test
    void 管理端点_health与prometheus可访问_assign_gate结局按业务码计数() throws Exception {
        mvc.perform(post("/api/assign-gate").contentType(MediaType.APPLICATION_JSON).content("{\"zone_id\":99}"))
                .andExpect(status().isOk());

        HttpResponse<String> health = httpGet(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        HttpResponse<String> prometheus = httpGet(managementPort, "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        String scrape = prometheus.body();

        // 对外端口上看不到管理端点
        assertThat(managementPort).isNotEqualTo(serverPort);
        assertThat(httpGet(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);

        assertThat(scrape)
                .containsPattern("xm_gateway_assign_gate_total\\{application=\"xm-gateway\",code=\"404\","
                        + "reason=\"zone_not_found\"} 1(\\.0)?")
                .contains("xm_gateway_assign_gate_total{application=\"xm-gateway\",code=\"0\",reason=\"ok\"} 0")
                .doesNotContain("zone_id");
    }
}
