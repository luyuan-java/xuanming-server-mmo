package com.game.scene.inbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.game.api.proto.SceneInboxNotify;
import com.game.scene.admin.SceneMaintenanceController;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SceneInboxProviderTest {
    @Test void acceptedNotificationRemainsInflightUntilLogicActuallyRuns(){
        InboxService service=mock(InboxService.class);ArrayDeque<Runnable> pending=new ArrayDeque<>();var gate=new SceneMaintenanceController.Gate();
        var provider=new SceneInboxProvider(()->service,"boot",pending::add).withMaintenance(gate);
        var request=SceneInboxNotify.newBuilder().setTargetInstanceId("boot").setPlayerId(7).setDomain(1).build();
        assertTrue(provider.notifyInbox(request).isDone());byte[] fence=new byte[16];fence[0]=1;gate.close(UUID.randomUUID().toString(),fence);
        assertFalse(gate.settled());assertTrue(provider.notifyInbox(request).isCompletedExceptionally());verifyNoInteractions(service);
        pending.remove().run();verify(service).onNotify(7,1);assertTrue(gate.settled());
    }
}
