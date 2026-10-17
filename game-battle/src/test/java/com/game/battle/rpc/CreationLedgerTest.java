package com.game.battle.rpc;

import static org.junit.jupiter.api.Assertions.*;
import com.game.battle.*;
import com.game.proto.*;
import org.junit.jupiter.api.Test;

class CreationLedgerTest {
    private final CreationLedger ledger=new CreationLedger(new GlobalBattleProperties(true,2,"fixture-only-probe-key-00000000000"),new BattleIdentity(3,"instance","127.0.0.1",12000));
    private CreateBattleRequest request(){return CreateBattleRequest.newBuilder().setBattleId(7).setRequiredPhysicalRegion(2).setTargetInstanceId("instance")
            .setPlacementOperationId("operation").setAdmitBeforeMs(60_000).build();}
    @Test void exactRequestReplaysAndTombstoneBlocksLateCreate(){
        var r=request();assertTrue(ledger.admit(r,1000,false));ledger.created(r);assertTrue(ledger.admit(r,2000,true));
        assertFalse(ledger.admit(r.toBuilder().setSeed(4).build(),2000,true));
        ledger.destroy(DestroyBattleRequest.newBuilder().setBattleId(7).setTargetInstanceId("instance").setPlacementOperationId("operation").build(),2000,true);
        assertFalse(ledger.admit(r,3000,false));
        assertEquals(BattleCreationState.BATTLE_CREATION_ABORTED,ledger.query(QueryBattleCreationRequest.newBuilder().setBattleId(7)
                .setTargetInstanceId("instance").setPlacementOperationId("operation").build(),false).getState());
    }
    @Test void wrongRegionInstanceOrExpiredAdmissionFailClosed(){
        assertFalse(ledger.admit(request().toBuilder().setRequiredPhysicalRegion(1).build(),1000,false));
        assertFalse(ledger.admit(request().toBuilder().setTargetInstanceId("other").build(),1000,false));
        assertFalse(ledger.admit(request(),60_000,false));
        assertThrows(IllegalArgumentException.class,()->ledger.destroy(DestroyBattleRequest.newBuilder().setBattleId(7).setTargetInstanceId("other").setPlacementOperationId("operation").build(),1000,false));
    }
    @Test void lateCompensationPreservesCreatedRoomButAbsenceIsPermanentlyFenced(){
        var r=request();ledger.created(r);
        var abort=DestroyBattleRequest.newBuilder().setBattleId(7).setTargetInstanceId("instance").setPlacementOperationId("operation")
                .setReason("global_match_abort_if_absent").build();
        assertFalse(ledger.destroy(abort,2000,true));
        assertEquals(BattleCreationState.BATTLE_CREATION_PRESENT,ledger.query(QueryBattleCreationRequest.newBuilder().setBattleId(7)
                .setTargetInstanceId("instance").setPlacementOperationId("operation").build(),true).getState());
        assertTrue(ledger.destroy(abort,3000,false));
        assertFalse(ledger.admit(r,4000,false));
    }
    @Test void laterAdmissionsCannotForgetStillLiveRoomAfterOriginalAdmissionWindow(){
        var original=request().toBuilder().setDeadlineMs(300_000).build();ledger.created(original);
        var later=request().toBuilder().setBattleId(8).setPlacementOperationId("later-operation").setAdmitBeforeMs(240_000).setDeadlineMs(500_000).build();
        assertTrue(ledger.admit(later,200_000,false));
        assertEquals(BattleCreationState.BATTLE_CREATION_PRESENT,ledger.query(QueryBattleCreationRequest.newBuilder().setBattleId(7)
                .setTargetInstanceId("instance").setPlacementOperationId("operation").build(),true).getState());
        assertThrows(IllegalArgumentException.class,()->ledger.destroy(DestroyBattleRequest.newBuilder().setBattleId(7).setTargetInstanceId("instance")
                .setPlacementOperationId("different-operation").build(),200_000,true));
    }
    @Test void pendingResultKeepsLiveReceiptEvenBeyondOriginalBattleDeadline(){
        ledger.created(request().toBuilder().setDeadlineMs(300_000).build());
        var later=request().toBuilder().setBattleId(8).setPlacementOperationId("later").setAdmitBeforeMs(960_000).setDeadlineMs(1_200_000).build();
        assertTrue(ledger.admit(later,900_000,false,id->id==7));
        assertEquals(BattleCreationState.BATTLE_CREATION_PRESENT,ledger.query(QueryBattleCreationRequest.newBuilder().setBattleId(7)
                .setTargetInstanceId("instance").setPlacementOperationId("operation").build(),true).getState());
    }

}
