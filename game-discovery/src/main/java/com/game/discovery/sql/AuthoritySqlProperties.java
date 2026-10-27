package com.game.discovery.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** normal强读只能在显式dev/test fixture关闭；启动grant与principal始终必需。 */
@ConfigurationProperties("game.sql-authority")
public class AuthoritySqlProperties {
    private boolean requireTidb=true;
    public boolean isRequireTidb(){return requireTidb;}
    public void setRequireTidb(boolean requireTidb){this.requireTidb=requireTidb;}
}

