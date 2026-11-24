package com.game.scene.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class SceneMaintenanceControllerTest {
    @Test void cutoffRejectsNewRootsAndWaitsForAcceptedFuture(){
        var gate=new SceneMaintenanceController.Gate();var accepted=gate.admit(new byte[]{1,2,3});
        String run=UUID.randomUUID().toString();byte[] fence=new byte[16];fence[0]=1;gate.close(run,fence);
        assertFalse(gate.settled());assertEquals(1,gate.unresolved());assertThrows(RejectedExecutionException.class,()->gate.admit(new byte[]{4}));
        accepted.complete(null);assertTrue(gate.settled());assertEquals(0,gate.unresolved());assertEquals(32,gate.frontier().length);
        assertThrows(IllegalStateException.class,()->gate.close(UUID.randomUUID().toString(),fence));
    }
    @Test void unknownCompletionNeverTurnsIntoSettledByRepeatedClose(){
        var gate=new SceneMaintenanceController.Gate();var accepted=gate.admit(new byte[]{5});byte[] fence=new byte[16];fence[0]=2;String run=UUID.randomUUID().toString();gate.close(run,fence);
        accepted.complete(new IllegalStateException("结局未知"));gate.close(run,fence);accepted.complete(null);
        assertFalse(gate.settled());assertEquals(1,gate.unresolved());
    }
    @Test void prepareOnlyNeverAcceptsAReplacementRoot(){var gate=new SceneMaintenanceController.Gate();gate.prepareOnly();assertThrows(RejectedExecutionException.class,()->gate.admit(new byte[0]));}
    @Test void freshPreparedBootOpensOnceAndOldCutCannotReopen(){
        var fresh=new SceneMaintenanceController.Gate();fresh.prepareOnly();assertTrue(fresh.settled());fresh.openPrepared();assertTrue(fresh.accepting());assertThrows(IllegalStateException.class,fresh::openPrepared);
        var accepted=fresh.admit(new byte[]{3});accepted.complete(null);byte[] fence=new byte[16];fence[0]=9;fresh.close(UUID.randomUUID().toString(),fence);assertTrue(fresh.settled());assertThrows(IllegalStateException.class,fresh::prepareOnly);assertThrows(IllegalStateException.class,fresh::openPrepared);
    }
    @Test void preparedUnknownCannotProduceAnOpenSource(){var gate=new SceneMaintenanceController.Gate();gate.prepareOnly();gate.recordUnknown();assertFalse(gate.settled());assertThrows(IllegalStateException.class,gate::openPrepared);}
    @Test void reusedSha256MatchesReferenceFrontierChain() throws Exception {
        var gate = new SceneMaintenanceController.Gate();
        byte[] expected = java.security.MessageDigest.getInstance("SHA-256").digest(new byte[0]);
        assertArrayEquals(expected, gate.frontier());
        byte[][] payloads = {new byte[]{1, 2, 3}, new byte[0], new byte[]{(byte) 0xff, 0x10}};
        for (int i = 0; i < payloads.length; i++) {
            gate.admit(payloads[i]).complete(null);
            var ref = java.security.MessageDigest.getInstance("SHA-256");
            ref.update(expected);
            ref.update(java.nio.ByteBuffer.allocate(8).putLong(i + 1L).array());
            ref.update(payloads[i]);
            expected = ref.digest();
            assertArrayEquals(expected, gate.frontier());
        }
    }
}

