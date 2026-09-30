package com.game.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.gateway.zone.Zone;
import com.game.gateway.zone.ZoneCatalog;
import com.game.gateway.zone.ZoneStatus;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 用仓库里的 application.yaml 整体起一次（RedissonClient 用替身，不连 Redis），确认默认配置能绑定、能对外服务。 */
@SpringBootTest
@AutoConfigureMockMvc
// xm.zone-id 显式钉住，免得开发机上的 XM_ZONE_ID 环境变量改掉默认区服。
@TestPropertySource(properties = {GatewayConfiguration.TOKEN_SECRET_ENV + "=boot-test-secret", "xm.zone-id=1"})
class GatewayApplicationTest {

    @MockitoBean
    private RedissonClient redisson;

    @Autowired
    private ZoneCatalog zones;

    @Autowired
    private MockMvc mvc;

    @Test
    void 默认配置_一区开放且推荐() throws Exception {
        Zone zone = zones.find(1).orElseThrow();
        assertThat(zone.status()).isEqualTo(ZoneStatus.OPEN);
        assertThat(zone.recommended()).isTrue();

        mvc.perform(get("/api/server-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones[0].zone_id").value(1))
                .andExpect(jsonPath("$.zones[0].recommended").value(true));
    }
}
