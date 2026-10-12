package com.game.guild.asset.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

/** 非缺省部署命名空间（namespace 执行，{@code -Dgame.namespace=nstest}）下 assetopfix 读到的配置：库名随命名空间，定格的值与服务进程同口径。 */
@Tag("namespace")
class AssetOpFixNamespaceIsolationTest {

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    @Test
    void 配置里的库名与定格的命名空间都是nstest() throws Exception {
        StandardEnvironment env = AssetOpFixEnvironment.load(null);
        assertThat(env.getProperty("spring.datasource.url")).contains("3306/nstest?").doesNotContain("/game?");
        assertThat(AssetOpFixEnvironment.defineNamespace(env)).isEqualTo("nstest");
    }
}
