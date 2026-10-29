package com.game.data.tools;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class RetireZoneHotStateTest {
    private StandardEnvironment environment(Map<String,Object> properties,Map<String,Object> variables) {
        var environment=new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,new MapPropertySource("systemProperties",properties));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,new SystemEnvironmentPropertySource("systemEnvironment",variables));
        return environment;
    }
    @Test void 同服务默认DB12_标准环境变量与系统属性优先级一致() {
        assertThat(RetireZoneHotState.redisDatabase(environment(Map.of(),Map.of()))).isEqualTo(12);
        assertThat(RetireZoneHotState.redisDatabase(environment(Map.of(),Map.of("GAME_REDIS_DATABASE","7")))).isEqualTo(7);
        assertThat(RetireZoneHotState.redisDatabase(environment(Map.of("game.redis.database","4"),Map.of("GAME_REDIS_DATABASE","7")))).isEqualTo(4);
        assertThat(RetireZoneHotState.redisDatabase(environment(Map.of("game.redis.database","0"),Map.of()))).isZero();
    }
    @Test void 负数或非整数配置拒绝_不静默连DB0() {
        assertThatThrownBy(()->RetireZoneHotState.redisDatabase(environment(Map.of(),Map.of("GAME_REDIS_DATABASE","-1")))).hasMessageContaining("不能为负");
        assertThatThrownBy(()->RetireZoneHotState.redisDatabase(environment(Map.of(),Map.of("GAME_REDIS_DATABASE","bad")))).isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }
}
