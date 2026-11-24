package com.game.contentguard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 内容安全服务配置（绑定 {@code game.content-guard.*}，非法值由 Spring Boot 启动期拒绝）。 */
@Validated
@ConfigurationProperties(prefix = "game.content-guard")
public record ContentGuardProperties(
        boolean requireLexicon,
        @Min(64) @Max(65_536) int maxTextBytes,
        String lexiconFile,
        @Valid PlatformProperties platform) {

    public ContentGuardProperties {
        if (maxTextBytes == 0) {
            maxTextBytes = 4096;
        }
        if (lexiconFile == null) {
            lexiconFile = "";
        }
        if (platform == null) {
            platform = new PlatformProperties(1500, 250, 2500, 150, "", "", "", "", "", "");
        }
    }

    public record PlatformProperties(
            @Min(50) int syncTimeoutMs,
            @Min(50) int worldTimeoutMs,
            @Min(50) int asyncTimeoutMs,
            @Min(50) int lookupTimeoutMs,
            String wechatAppId,
            String wechatAppSecret,
            String wechatBaseUrl,
            String douyinAppId,
            String douyinAppSecret,
            String douyinBaseUrl) {
        public PlatformProperties {
            if (syncTimeoutMs == 0) syncTimeoutMs = 1500;
            if (worldTimeoutMs == 0) worldTimeoutMs = 250;
            if (asyncTimeoutMs == 0) asyncTimeoutMs = 2500;
            if (lookupTimeoutMs == 0) lookupTimeoutMs = 150;
            if (wechatAppId == null) wechatAppId = "";
            if (wechatAppSecret == null) wechatAppSecret = "";
            if (wechatBaseUrl == null) wechatBaseUrl = "";
            if (douyinAppId == null) douyinAppId = "";
            if (douyinAppSecret == null) douyinAppSecret = "";
            if (douyinBaseUrl == null) douyinBaseUrl = "";
        }
    }
}
