package com.game.common.sql.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** 真实 TiDB 权限与 scope 回归；精确 GRANT 调用次数由独立代理测试覆盖。 */
class AuthoritySqlFixtureGrantCacheTiDbTest {
    @Test void newlyCreatedTablesAreWritableImmediatelyAndRemainWritableAcrossScopes()throws Exception{
        String root=System.getProperty("game.it.authority.jdbc",System.getProperty("game.it.mysql"));
        if(root==null||root.isBlank()){
            if(Boolean.getBoolean("game.it.require-tidb"))throw new IllegalStateException("game.it.authority.jdbc or game.it.mysql is required");
            Assumptions.assumeTrue(false,"true TiDB is not configured");
        }
        String user=System.getProperty("game.it.mysql.user","root"),password=System.getenv().getOrDefault("GAME_MYSQL_PASSWORD","");
        var options=AuthoritySqlFixture.Options.fromEnvironment(root,user,password,"game_grant_cache",false,List.of(1));
        try(var lease=AuthoritySqlFixture.beginUnpublished(options)){
            lease.withSchema((c,p,d)->{
                for(int i=0;i<12;i++)try(var statement=c.createStatement()){
                    statement.setQueryTimeout(5);
                    statement.execute("CREATE TABLE grant_probe_"+i+"(id INT PRIMARY KEY,original VARBINARY(8) NOT NULL) ENGINE=InnoDB");
                    assertEquals(1,statement.executeUpdate("INSERT INTO grant_probe_"+i+" VALUES(1,X'00ff07')"));
                }
                return null;
            });
            // 重复 CREATE IF NOT EXISTS 与新的 scope 仍走真实授权扫描，但不会重发已确认的授权。
            lease.withSchema((c,p,d)->{
                for(int i=0;i<12;i++)try(var statement=c.createStatement()){
                    statement.setQueryTimeout(5);statement.execute("CREATE TABLE IF NOT EXISTS grant_probe_"+i+"(id INT PRIMARY KEY,original VARBINARY(8) NOT NULL) ENGINE=InnoDB");
                    try(var rows=statement.executeQuery("SELECT original FROM grant_probe_"+i+" WHERE id=1")){
                        assertTrue(rows.next());assertArrayEquals(new byte[]{0,-1,7},rows.getBytes(1));assertFalse(rows.next());
                    }
                    assertEquals(1,statement.executeUpdate("UPDATE grant_probe_"+i+" SET original=X'0007ff' WHERE id=1"));
                }
                return null;
            });
            var cache=AuthoritySqlFixture.UnpublishedSchemaLease.class.getDeclaredField("grantedBusinessTables");cache.setAccessible(true);
            assertEquals(12,((Set<?>)cache.get(lease)).size());
        }
    }
}
