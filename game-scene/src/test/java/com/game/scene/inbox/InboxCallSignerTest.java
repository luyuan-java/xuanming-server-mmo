package com.game.scene.inbox;

import static org.assertj.core.api.Assertions.*;

import com.game.api.inbox.InboxCallContract;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.common.base.RoleAdmissionIdentity;
import com.game.proto.player_inbox.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InboxCallSignerTest {
    private static final String SECRET = "scene-inbox-unit-test-only-secret-0123456789";
    private final InboxCallSigner signer = new InboxCallSigner(SECRET, () -> 1000);
    private final ServiceCallSignatures verifier = new ServiceCallSignatures(Map.of(InboxCallContract.CALLER, SECRET), () -> 1000);

    private RoleAdmissionIdentity admission() {
        return RoleAdmissionIdentity.newBuilder().setPlayerId(7).setOwnerEpoch(5).setOwnerRevision(3)
                .setGeneration(5).setLoginGeneration(4).setSceneInstanceId("scene-original").setSceneNodeId("9")
                .setSceneId(23).setZoneId(1).setSessionId(11).build();
    }

    private FetchPlayerInboxRequest fetch() {
        return FetchPlayerInboxRequest.newBuilder().setPlayerId(7).setOwnerEpoch(5).setAdmission(admission())
                .setDomainValue(1).setCorrelationId(31).setMaxItems(16).setSceneNodeId(9).build();
    }

    @Test
    void Fetch签名覆盖完整原准入及游标_不能换域重放Ack() {
        var signed = signer.sign(fetch());
        assertThat(signed.getAdmission()).isEqualTo(admission());
        assertThat(verifier.verify(InboxCallContract.FETCH, signed)).isEqualTo(InboxCallContract.CALLER);
        assertThatThrownBy(() -> verifier.verify(InboxCallContract.ACK, signed)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> verifier.verify(InboxCallContract.FETCH,
                signed.toBuilder().setConsumerDecidedThrough(99).build())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> verifier.verify(InboxCallContract.FETCH, signed.toBuilder()
                .setAdmission(admission().toBuilder().setSessionId(22)).build())).isInstanceOf(SecurityException.class);
    }

    @Test
    void Ack原结局与准入均入签名_原件缺失或字段不符不发出() {
        var unsigned = AckPlayerInboxRequest.newBuilder().setPlayerId(7).setOwnerEpoch(5).setAdmission(admission())
                .setDomainValue(1).setCorrelationId(32).setSceneNodeId(9).addDecisions(
                        PlayerInboxDecisionReport.newBuilder().setSeq(1).setRequestId(41)).build();
        var signed = signer.sign(unsigned);
        assertThat(verifier.verify(InboxCallContract.ACK, signed)).isEqualTo(InboxCallContract.CALLER);
        assertThatThrownBy(() -> verifier.verify(InboxCallContract.ACK, signed.toBuilder().clearDecisions().build()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> signer.sign(fetch().toBuilder().clearAdmission().build())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> signer.sign(unsigned.toBuilder().setPlayerId(8).build())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> new InboxCallSigner("short", () -> 1000)).isInstanceOf(IllegalArgumentException.class);
    }
}
