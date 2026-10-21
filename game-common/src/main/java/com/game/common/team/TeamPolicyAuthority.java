package com.game.common.team;

import com.game.common.player.PlayerRouteAuthority;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

/** Team与Match共用同一SQL策略原件；硬规则与实际allowCrossZone不能各造一份配置真相。 */
public final class TeamPolicyAuthority {
    private TeamPolicyAuthority(){}
    public record Contract(byte[] original,boolean allowCrossZone){
        public Contract{original=original.clone();}
        @Override public byte[] original(){return original.clone();}
    }
    public static byte[] canonicalContract(boolean allow){return (TeamPolicyContract.hardRules()+"|allow-cross-zone="+allow).getBytes(StandardCharsets.UTF_8);}
    private static byte[] digest(byte[] raw){try{return MessageDigest.getInstance("SHA-256").digest(raw);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static Contract load(Connection c,int timeout)throws SQLException{
        try(var q=c.prepareStatement("SELECT contract,allow_cross_zone,contract_digest FROM team_policy WHERE id=1")){
            q.setQueryTimeout(timeout);try(var r=q.executeQuery()){
                if(!r.next())throw new SQLException("缺少Team初次SQL准入策略原件");String flag=r.getString(2);byte[] raw=r.getBytes(1),hash=r.getBytes(3);
                if(!"0".equals(flag)&&!"1".equals(flag)||raw==null||hash==null||hash.length!=32)throw new SQLException("Team策略typed投影或摘要缺失");boolean allow="1".equals(flag);
                if(!MessageDigest.isEqual(hash,digest(raw))||!Arrays.equals(raw,canonicalContract(allow)))throw new SQLException("Team策略与唯一硬规则/实际跨区投影不符");return new Contract(raw,allow);
            }
        }
    }
    /** 仅Team正规初始化首次插入；有原件只能逐字核对，不覆写、不替缺typed投影补权。 */
    public static void ensure(Connection c,boolean actualAllow,int timeout)throws SQLException{
        if(c.getAutoCommit())throw new SQLException("Team初次策略需要事务");PlayerRouteAuthority.requireMeta(c,timeout,true);byte[] raw=canonicalContract(actualAllow);
        try(var q=c.prepareStatement("INSERT INTO team_policy(id,contract,allow_cross_zone,contract_digest) VALUES(1,?,?,?) ON DUPLICATE KEY UPDATE id=id")){
            q.setQueryTimeout(timeout);q.setBytes(1,raw);q.setInt(2,actualAllow?1:0);q.setBytes(3,digest(raw));q.executeUpdate();
        }
        var original=load(c,timeout);if(original.allowCrossZone()!=actualAllow||!Arrays.equals(original.original(),raw))throw new SQLException("Team副本实际配置与初次准入策略不同");
    }
}
