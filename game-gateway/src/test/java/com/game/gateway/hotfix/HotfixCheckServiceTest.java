package com.game.gateway.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class HotfixCheckServiceTest {
    private static final String PATCH = "https://cdn.example.invalid/patch.zip";

    private static HotfixCheckService service(String latest, String minimum) {
        return new HotfixCheckService(new HotfixProperties(Map.of("android",
                new HotfixProperties.Release(latest, minimum, PATCH, "修复说明"))));
    }

    private static HotfixCheckResponse check(HotfixCheckService service, String version) {
        return service.check(new HotfixCheckRequest(version, "android"));
    }

    @Test
    void 未知平台或未配置发布线不编造版本_平台精确匹配() {
        var empty = new HotfixCheckService(new HotfixProperties(null));
        assertThat(check(empty, "1.0")).isEqualTo(new HotfixCheckResponse(false, false, null, null, null));
        for (String unknown : new String[] {"ios", "Android", " android "}) {
            assertThat(service("2", "1").check(new HotfixCheckRequest("0", unknown)))
                    .isEqualTo(new HotfixCheckResponse(false, false, null, null, null));
        }
    }

    @Test
    void 数字段比较_补零_前导零_不把高版本降级() {
        assertThat(check(service("1.10.0", null), "1.9.9").needUpdate()).isTrue();
        assertThat(check(service("1.2.0.1", null), "1.2").needUpdate()).isTrue();
        assertThat(check(service("999999999.999999999.999999999.999999999", null), "999999999.0").needUpdate()).isTrue();
        for (String current : new String[] {"1.2", "01.002.000", "1.2.0.0", "1.2.0.1", "2"}) {
            assertThat(check(service("1.2.0", "1.1"), current))
                    .isEqualTo(new HotfixCheckResponse(false, false, null, "1.2.0", null));
        }
    }

    @Test
    void 低于最新但不低于强更线只建议更新() {
        for (String current : new String[] {"1.2", "1.2.1"}) {
            assertThat(check(service("1.2.3", "1.2.0"), current))
                    .isEqualTo(new HotfixCheckResponse(true, false, PATCH, "1.2.3", "修复说明"));
        }
    }

    @Test
    void 低于最小版本强更_没有最小版本不强更() {
        assertThat(check(service("1.2.3", "1.2"), "1.1.999").forceUpdate()).isTrue();
        assertThat(check(service("1.2.3", "1.2.3"), "1.2.2").forceUpdate()).isTrue();
        var noMinimum = check(service("1.2.3", null), "0");
        assertThat(noMinimum.needUpdate()).isTrue();
        assertThat(noMinimum.forceUpdate()).isFalse();
    }

    @Test
    void 非法版本在未知平台也拒绝_不接受空白后缀或超界数字段() {
        var service = service("2", null);
        for (String value : new String[] {null, "", " ", "1.0 ", "+1", "-1", "1.0-beta", "1.0+build", "v1",
                ".1", "1.", "1..2", "1.2.3.4.5", "1234567890", "１.２", "1\n"}) {
            var failure = assertThrows(ResponseStatusException.class,
                    () -> service.check(new HotfixCheckRequest(value, "unknown")), value);
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void 缺请求或平台拒绝() {
        var service = service("2", null);
        assertThat(assertThrows(ResponseStatusException.class, () -> service.check(null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        for (String platform : new String[] {null, "", "\t "}) {
            assertThat(assertThrows(ResponseStatusException.class,
                    () -> service.check(new HotfixCheckRequest("1", platform))).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void 发布线不可被调用方修改_平台各自独立() {
        var source = new HashMap<String, HotfixProperties.Release>();
        source.put("android", new HotfixProperties.Release("3", "2", PATCH, null));
        source.put("ios", new HotfixProperties.Release("1", null, "https://cdn.example.invalid/ios", null));
        var properties = new HotfixProperties(source);
        var service = new HotfixCheckService(properties);
        source.clear();
        assertThrows(UnsupportedOperationException.class, () -> properties.platforms().clear());
        assertThat(check(service, "1").forceUpdate()).isTrue();
        assertThat(service.check(new HotfixCheckRequest("1", "ios")))
                .isEqualTo(new HotfixCheckResponse(false, false, null, "1", null));
    }
}
