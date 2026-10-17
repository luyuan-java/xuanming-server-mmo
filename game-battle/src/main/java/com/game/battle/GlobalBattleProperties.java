package com.game.battle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 物理机房由部署固定，客户端与 match 均不能替执行端改变它。 */
@ConfigurationProperties("game.battle.global-match")
public record GlobalBattleProperties(boolean enabled,int physicalRegion,String probeSecret) {
    public static final GlobalBattleProperties DISABLED=new GlobalBattleProperties(false,0,"");
    public GlobalBattleProperties {
        probeSecret=probeSecret==null?"":probeSecret;
        if(enabled&&(physicalRegion==0||probeSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32))
            throw new IllegalArgumentException("全球 battle 缺少物理机房或 MATCH_PROBE_SECRET");
    }
}
