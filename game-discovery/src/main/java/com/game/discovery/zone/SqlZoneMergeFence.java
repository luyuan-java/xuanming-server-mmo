package com.game.discovery.zone;

import com.game.common.player.PlayerRouteAuthority;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/** SQL围栏预检读侧。它不代替领域提交事务内的meta/fence/P锁。 */
public final class SqlZoneMergeFence implements ZoneMergeFence {
    private final DataSource source;
    private final Executor executor;
    public SqlZoneMergeFence(DataSource source,Executor executor){this.source=Objects.requireNonNull(source);this.executor=Objects.requireNonNull(executor);}
    @Override public boolean inProgress(int zone)throws Exception{
        try(var connection=source.getConnection()){return PlayerRouteAuthority.fenced(connection,zone,3);}
    }
    @Override public CompletionStage<Boolean> inProgressAsync(int zone){
        return CompletableFuture.supplyAsync(()->{try{return inProgress(zone);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}},executor);
    }
}
