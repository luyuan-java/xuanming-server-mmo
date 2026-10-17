package com.game.match.global;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("game.match.rank-publisher")
public record GlobalRankProperties(String host,Integer port,String secret){
    public GlobalRankProperties{host=host==null?"127.0.0.1":host;port=port==null?20917:port;secret=secret==null?"":secret;}
}
