package com.game.data.mall;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 商城地址和签名只由服务端装配；未启用时不影响已有运维接口。 */
@ConfigurationProperties("game.data.mall-admin")
public record MallAdminProperties(@DefaultValue("") String baseUrl,
                                  @DefaultValue("") String secret,
                                  @DefaultValue("mall-admin") String subject) {
    public MallAdminProperties {
        if (baseUrl == null) { baseUrl = ""; }
        if (secret == null) { secret = ""; }
        if (subject == null) { subject = "mall-admin"; }
        if (!baseUrl.isEmpty()) {
            URI target;
            try { target = URI.create(baseUrl); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("商城管理地址非法"); }
            String path = target.getRawPath();
            if (!("http".equals(target.getScheme()) || "https".equals(target.getScheme())) || target.getHost() == null
                    || target.getRawUserInfo() != null || target.getRawQuery() != null || target.getRawFragment() != null
                    || path == null || path.contains("%") || path.contains(";") || path.contains("..") || path.contains("//")) {
                throw new IllegalArgumentException("商城管理地址必须为无认证信息的固定HTTP地址");
            }
            int length = secret.getBytes(StandardCharsets.UTF_8).length;
            if (length < 32 || length > 4096 || !subject.matches("[a-z][a-z0-9-]{2,63}")) {
                throw new IllegalArgumentException("商城管理签名配置缺失或非法");
            }
        }
    }

    public boolean enabled() { return !baseUrl.isEmpty(); }
}
