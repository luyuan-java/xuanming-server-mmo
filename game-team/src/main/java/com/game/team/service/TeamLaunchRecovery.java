package com.game.team.service;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.team.StartTeamMatchRequest;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 永久开局的有界恢复；多副本可重放同一原件，失败更新轮转时间，不被首批坏项饿死。 */
public final class TeamLaunchRecovery implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(TeamLaunchRecovery.class);
    private final AuthorityJdbc sql;private final TeamService service;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("team-launch-recovery").daemon(true).factory());
    private final ExecutorService attempts=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),Thread.ofPlatform().name("team-launch-attempt-",0).daemon(true).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore permits=new Semaphore(2);
    private final java.util.Set<String> inflight=ConcurrentHashMap.newKeySet();
    public TeamLaunchRecovery(DataSource source,TeamService service){this.sql=new AuthorityJdbc(source);this.service=service;}
    public void start(){worker.scheduleWithFixedDelay(this::round,1000,1000,TimeUnit.MILLISECONDS);}
    private record Pending(String request,SessionContext actor,byte[] original){}
    void round(){
        try{
            if(permits.availablePermits()==0)return;Deadline round=Deadline.after(3000);
            var rows=sql.read(round,c->c.list("SELECT l.request_id,l.actor_player_id,l.owner_account,l.owner_revision,l.request_proto FROM team_launch l JOIN team_authority t ON t.team_id=l.team_id AND t.active_launch_id=l.request_id WHERE l.active=1 ORDER BY l.updated_ms,l.request_id LIMIT 2",r->{
                return new Pending(r.getString(1),SessionContext.newBuilder().setPlayerId(r.getLong(2)).setAccount(r.getString(3)).setOwnerRevision(r.getLong(4)).build(),r.getBytes(5));
            }));
            for(var row:rows){if(Thread.currentThread().isInterrupted())return;
                if(!inflight.add(row.request()))continue;if(!permits.tryAcquire()){inflight.remove(row.request());return;}
                try{
                    // 尝试之前轮转。未知/耗尽预算也不饿死后续原件；这里只改恢复投影，不改变名单/业务锁。
                    boolean active=sql.tx(round,c->c.update("UPDATE team_launch SET updated_ms=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) WHERE request_id=? AND active=1 AND EXISTS(SELECT 1 FROM team_authority t WHERE t.team_id=team_launch.team_id AND t.active_launch_id=team_launch.request_id)",row.request())==1);
                    if(!active){inflight.remove(row.request());permits.release();continue;}
                    attempts.execute(()->{try{service.startTeamMatch(row.actor(),StartTeamMatchRequest.parseFrom(row.original()),Deadline.after(3000));}
                        catch(Exception pending){log.warn("原Team开局恢复未完成，保留原件");}
                        finally{inflight.remove(row.request());permits.release();}});
                }catch(Exception rejected){inflight.remove(row.request());permits.release();throw rejected;}
            }
        }catch(Exception failure){log.warn("Team开局恢复轮次未完成，保留原件",failure);}
    }
    public void close(){worker.shutdownNow();attempts.shutdownNow();}
}
