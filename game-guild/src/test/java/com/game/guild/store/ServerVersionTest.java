package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * 启动期版本下限（基线 server_version_test.go:13-65）：8.0.28 拒、8.0.29 过；修订号按数值比（8.0.3 &lt; 8.0.29）；发行版后缀忽略；
 * TiDB 按字样放行；MariaDB 与解析不出的版本串一律拒绝（fail-closed）。
 */
class ServerVersionTest {

    @ParameterizedTest(name = "{0}: \"{1}\" → {2}")
    @CsvSource(delimiter = '|', value = {
            "8.0.28 拒                          | 8.0.28                  | false",
            "8.0.29 过                          | 8.0.29                  | true",
            "8.0.30 过                          | 8.0.30                  | true",
            "修订号按数值比 8.0.3 拒             | 8.0.3                   | false",
            "修订号按数值比 8.0.100 过           | 8.0.100                 | true",
            "发行版后缀 8.0.35-0ubuntu 过        | 8.0.35-0ubuntu          | true",
            "发行版后缀 8.0.35-0ubuntu0.22.04.1  | 8.0.35-0ubuntu0.22.04.1 | true",
            "发行版后缀 8.0.28-log 拒            | 8.0.28-log              | false",
            "8.4 LTS 过                         | 8.4.3                   | true",
            "9.x 创新版 过                      | 9.1.0                   | true",
            "5.7.44 拒                          | 5.7.44                  | false",
            "TiDB 放行                          | 8.0.11-TiDB-v7.5.1      | true",
            "MariaDB 拒                         | 10.11.6-MariaDB         | false",
            "只有两段 拒                         | 8.0                     | false",
            "段为空 拒                           | 8..29                   | false",
            "无法解析 拒                         | unknown                 | false",
    })
    void 版本判定(String name, String version, boolean ok) {
        String rejection = ServerVersion.rejection(version);
        if (ok) {
            assertThat(rejection).as(name).isNull();
        } else {
            assertThat(rejection).as(name).isNotNull().contains("8.0.29");
        }
    }

    @Test
    void 前后空白与空串() {
        assertThat(ServerVersion.rejection(" 8.0.29\n")).isNull();
        assertThat(ServerVersion.rejection("")).contains("8.0.29");
        assertThat(ServerVersion.rejection(null)).contains("8.0.29");
    }

    @Test
    void 只取开头的主次修订_第四段与后缀忽略() {
        assertThat(ServerVersion.parseMySqlVersion("8.0.35-0ubuntu0.22.04.1")).containsExactly(8, 0, 35);
        assertThat(ServerVersion.parseMySqlVersion("8.4.3.7")).containsExactly(8, 4, 3);
        assertThat(ServerVersion.parseMySqlVersion("v8.0.29")).as("开头不是数字").isNull();
    }
}
