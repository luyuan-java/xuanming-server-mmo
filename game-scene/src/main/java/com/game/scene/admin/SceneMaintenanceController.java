package com.game.scene.admin;

import com.game.scene.SceneNode;
import com.game.player.store.state.SqlWriterMaintenanceReceipt.IssuerClosure;
import com.google.protobuf.ByteString;
import java.security.*;
import java.nio.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 复用/admin令牌鉴权。关闭只针对同boot；没有TTL、重开或以停进程代替排空的入口。 */
@RestController
@RequestMapping("/admin/scene/maintenance")
public final class SceneMaintenanceController {
    private final SceneNode node;
    public SceneMaintenanceController(SceneNode node){this.node=node;}
    @PostMapping(value="/close-roots",consumes="application/x-protobuf",produces="application/x-protobuf")
    public byte[] close(@RequestParam String operationId,@RequestBody byte[] expected)throws Exception {
        return node.closeMaintenanceRoots(operationId,IssuerClosure.parseFrom(expected)).toByteArray();
    }
    @PostMapping(value="/stop-and-report",consumes="application/x-protobuf",produces="application/x-protobuf")
    public byte[] settled(@RequestParam String operationId,@RequestBody byte[] expected)throws Exception {
        return node.maintenanceStopAndReport(operationId,IssuerClosure.parseFrom(expected)).toByteArray();
    }
    @PostMapping(value="/prepare-source",consumes="application/x-protobuf",produces="application/x-protobuf")
    public byte[] prepare(@RequestBody byte[] original)throws Exception{return node.prepareMaintenanceSource(com.game.player.store.state.SqlWriterMaintenanceReceipt.IssuerReadiness.parseFrom(original)).toByteArray();}
    @PostMapping(value="/open-source",consumes="application/x-protobuf",produces="application/x-protobuf")
    public byte[] open(@RequestBody byte[] original)throws Exception{return node.openMaintenanceSource(com.game.player.store.state.SqlWriterMaintenanceReceipt.SourceOpenAck.parseFrom(original)).toByteArray();}
    /** 受理、cut和完成共用一把短锁；异步任务不会持锁等待自己。 */
    public static final class Gate {
        private boolean accepting=true,preparedOnly;
        private String operation="";
        private byte[] revival=new byte[0],frontier=emptyFrontier();
        private static byte[] emptyFrontier(){try{return MessageDigest.getInstance("SHA-256").digest(new byte[0]);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
        private long accepted,completed,unknown,closedMs;
        public synchronized void prepareOnly(){if(accepted!=0||!operation.isEmpty())throw new IllegalStateException("publication已受理或旧boot已cut");accepting=false;preparedOnly=true;closedMs=System.currentTimeMillis();}
        public synchronized void openPrepared(){if(!preparedOnly||accepting||!operation.isEmpty()||accepted!=0||completed!=0||unknown!=0)throw new IllegalStateException("只允许全新prepare-only boot第一次开放");preparedOnly=false;accepting=true;}
        public synchronized Lease admit(byte[] original){
            if(!accepting)throw new java.util.concurrent.RejectedExecutionException("维护publication已永久关闭");
            accepted=Math.addExact(accepted,1);
            try{MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(frontier);hash.update(ByteBuffer.allocate(8).putLong(accepted).array());hash.update(original);frontier=hash.digest();}
            catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            return new Lease(this);
        }
        public synchronized void close(String run,byte[] fence){
            if(!UUID.fromString(run).toString().equals(run)||fence.length!=16)throw new IllegalArgumentException("缺少同run永久revival原件");
            if(!operation.isEmpty()&&(!operation.equals(run)||!Arrays.equals(revival,fence)))throw new IllegalStateException("不得替换已固定的维护run");
            operation=run;revival=fence.clone();accepting=false;if(closedMs==0)closedMs=System.currentTimeMillis();
        }
        public synchronized boolean settled(){return !accepting&&accepted==completed&&unknown==0;}
        public synchronized void recordUnknown(){unknown=Math.addExact(unknown,1);notifyAll();}
        public synchronized long unresolved(){return Math.addExact(accepted-completed,unknown);}
        public synchronized long closedMs(){return closedMs;}
        public synchronized byte[] frontier(){return frontier.clone();}
        public synchronized boolean accepting(){return accepting;}
        private synchronized void finish(Throwable failure){completed=Math.addExact(completed,1);if(failure!=null)unknown=Math.addExact(unknown,1);notifyAll();}
    }
    public static final class Lease {
        private final Gate gate;private boolean done;
        private Lease(Gate gate){this.gate=gate;}
        public synchronized void complete(Throwable failure){if(done)return;done=true;gate.finish(failure);}
    }
}
