package com.game.gateway.hotfix;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 按客户端 platform 精确匹配的发布线；缺省无发布线，绑定时校验并固定不可变快照。 */
@ConfigurationProperties("game.gateway.hotfix")
public record HotfixProperties(Map<String, Release> platforms) {
    public HotfixProperties {
        platforms = platforms == null ? Map.of() : Map.copyOf(platforms);
        if (platforms.keySet().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("game.gateway.hotfix.platforms 不允许空平台名");
        }
    }

    /** minVersion 缺省表示不强制更新；patchUrl 仅传回已配置地址，网关不下载补丁。 */
    public record Release(String latestVersion, String minVersion, String patchUrl, String changelog) {
        public Release {
            NumericVersion latest = NumericVersion.parse(latestVersion);
            if (minVersion != null && NumericVersion.parse(minVersion).compareTo(latest) > 0) {
                throw new IllegalArgumentException("热更 min-version 不得高于 latest-version");
            }
            if (patchUrl == null || patchUrl.isBlank()) {
                throw new IllegalArgumentException("热更 patch-url 必填");
            }
        }
    }
}
