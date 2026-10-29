package com.game.data.consistency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立只读凭据只在服务端绑定，不沿公共数据源隐式降级。 */
@ConfigurationProperties("game.data.consistency")
public class ConsistencyProperties {
    private String jdbcUrl;
    private String username;
    private String password;
    public String getJdbcUrl(){return jdbcUrl;}
    public void setJdbcUrl(String jdbcUrl){this.jdbcUrl=jdbcUrl;}
    public String getUsername(){return username;}
    public void setUsername(String username){this.username=username;}
    public String getPassword(){return password;}
    public void setPassword(String password){this.password=password;}
    boolean configured(){return jdbcUrl!=null&&!jdbcUrl.isBlank()&&username!=null&&!username.isBlank();}
}
