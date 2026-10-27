package com.game.match.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.game.common.sql.WriterAuthority;
import com.game.match.lifecycle.MatchLifecycle;
import com.game.player.store.state.SqlWriterMaintenanceReceipt;
import com.google.protobuf.ByteString;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchMaintenanceControllerTest {
    @TempDir Path directory;
    @Test void incompleteRootCoverageCannotForgeSuccessfulStopReport()throws Exception{
        byte[] identity=new byte[16];identity[0]=1;Path source=directory.resolve("source");Files.write(source,new byte[]{1,2});
        WriterAuthority boot=mock(WriterAuthority.class);when(boot.writerKey()).thenReturn(identity);when(boot.instanceId()).thenReturn(identity);
        MatchLifecycle lifecycle=mock(MatchLifecycle.class);when(lifecycle.writerBoot()).thenReturn(boot);var controller=new MatchMaintenanceController(lifecycle);
        var expected=SqlWriterMaintenanceReceipt.IssuerClosure.newBuilder().setIssuerId(ByteString.copyFrom(identity)).setInstanceId(ByteString.copyFrom(identity)).setPhysicalWriterKey(ByteString.copyFrom(identity))
                .setLogicalRole("match-source").setPermanentRevivalFenceId(ByteString.copyFrom(identity)).addSourceEvidence(SqlWriterMaintenanceReceipt.SourcePin.newBuilder().setAbsolutePath(source.toString()).setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))).build();
        String run=UUID.randomUUID().toString();assertThrows(IllegalStateException.class,()->controller.close(run,expected.toByteArray()));
        assertThrows(IllegalStateException.class,()->controller.settled(run,expected.toByteArray()));verify(lifecycle,never()).maintenanceStopAndReport(anyString(),any());
    }
}
