package com.game.match.admin;

import com.game.match.lifecycle.MatchLifecycle;
import com.game.player.store.state.SqlWriterMaintenanceReceipt.IssuerClosure;
import com.google.protobuf.ByteString;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** /admin原令牌及操作人过滤器保护的维护入口；成功报告只由真实排空状态产生。 */
@RestController
@RequestMapping("/admin/match/maintenance")
public final class MatchMaintenanceController {
    private final MatchLifecycle lifecycle;
    private byte[] revival=new byte[0];
    public MatchMaintenanceController(MatchLifecycle lifecycle){this.lifecycle=lifecycle;}
    @PostMapping(value="/close-roots",consumes="application/x-protobuf",produces="application/x-protobuf")
    public synchronized byte[] close(@RequestParam String operationId,@RequestBody byte[] original)throws Exception{
        var expected=IssuerClosure.parseFrom(original);verify(expected);lifecycle.closeMaintenanceRoots(operationId);
        if(!lifecycle.rootsSettled())throw new IllegalStateException("根publication仍未闭合");
        revival=expected.getPermanentRevivalFenceId().toByteArray();
        return report(expected);
    }
    @PostMapping(value="/stop-and-report",consumes="application/x-protobuf",produces="application/x-protobuf")
    public synchronized byte[] settled(@RequestParam String operationId,@RequestBody byte[] original)throws Exception{
        var expected=IssuerClosure.parseFrom(original);verify(expected);
        if(!Arrays.equals(revival,expected.getPermanentRevivalFenceId().toByteArray()))throw new IllegalStateException("永久revival原件不符");
        if(!lifecycle.maintenanceStopAndReport(operationId,Duration.ofSeconds(60)))throw new IllegalStateException("Match仍有未决原件或relay未join");
        return report(expected);
    }
    @PostMapping(value="/prepare-source",consumes="application/x-protobuf",produces="application/x-protobuf")
    public byte[] prepare(@RequestBody byte[] input)throws Exception{return lifecycle.prepareMaintenanceSource(com.game.player.store.state.SqlWriterMaintenanceReceipt.IssuerReadiness.parseFrom(input)).toByteArray();}
    @PostMapping(value="/open-source",consumes="application/x-protobuf",produces="application/x-protobuf")
    public byte[] open(@RequestBody byte[] input)throws Exception{return lifecycle.openMaintenanceSource(com.game.player.store.state.SqlWriterMaintenanceReceipt.SourceOpenAck.parseFrom(input)).toByteArray();}
    private byte[] report(IssuerClosure expected){return expected.toBuilder().setAcceptingClosedMs(lifecycle.rootsClosedMs()).setAcceptedSettledMs(System.currentTimeMillis()).setAcceptedFrontierDigest(ByteString.copyFrom(lifecycle.rootsFrontier())).setUnresolvedPublications(0).build().toByteArray();}
    private void verify(IssuerClosure expected)throws Exception{
        var boot=lifecycle.writerBoot();
        if(boot==null||expected.getIssuerId().isEmpty()||expected.getPermanentRevivalFenceId().size()!=16
                ||!MessageDigest.isEqual(boot.instanceId(),expected.getInstanceId().toByteArray())
                ||!MessageDigest.isEqual(boot.writerKey(),expected.getPhysicalWriterKey().toByteArray())
                ||expected.getLogicalRole().isBlank()||expected.getSourceEvidenceCount()==0)throw new IllegalArgumentException("Match P0身份原件不符");
        for(var pin:expected.getSourceEvidenceList()){
            Path path=Path.of(pin.getAbsolutePath());if(!path.isAbsolute()||pin.getSha256().size()!=32||!Files.isRegularFile(path))throw new IllegalArgumentException("source pin非法");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)hash.update(block,0,n);}
            if(!MessageDigest.isEqual(hash.digest(),pin.getSha256().toByteArray()))throw new IllegalStateException("source pin已漂移");
        }
    }
}
