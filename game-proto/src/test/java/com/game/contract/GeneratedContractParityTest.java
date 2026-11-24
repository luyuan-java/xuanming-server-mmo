package com.game.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.generated.ContractClientRpcKind;
import com.game.contract.generated.ContractEventIds;
import com.game.contract.generated.ContractMessageIds;
import com.game.contract.generated.ContractNodeType;
import com.game.contract.generated.ContractRouteEntry;
import com.game.contract.generated.ContractRouteTable;
import com.game.contract.generated.rpc.ContentGuardRpc;
import com.game.contract.generated.rpc.MatchServiceRpc;
import com.game.contract.generated.service.ContentGuardContractDispatcher;
import com.game.contract.generated.service.ContentGuardHandler;
import com.game.proto.content_guard.CheckTextRequest;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;

/**
 * 核对编译期生成产物（game-proto-codegen）与运行时契约注册表逐条一致，并验证零反射派发器。
 */
class GeneratedContractParityTest {

    @Test
    void 编译期路由表与运行时MessageIdRegistry逐条一致() {
        MessageIdRegistry runtime = MessageIdRegistry.loadFromClasspath();
        assertThat(ContractRouteTable.allRoutes()).hasSize(runtime.all().size());
        assertThat(ContractRouteTable.UNRESOLVED_KEYS).containsExactlyElementsOf(runtime.unresolvedKeys());

        for (MessageMethod method : runtime.all()) {
            ContractRouteEntry entry = ContractRouteTable.lookup(method.messageId());
            assertThat(entry).as(method.key()).isNotNull();
            assertThat(entry.serviceName()).isEqualTo(method.serviceName());
            assertThat(entry.methodName()).isEqualTo(method.methodName());
            assertThat(entry.fullMethod()).isEqualTo("/" + method.method().getService().getFullName() + "/" + method.methodName());
            assertThat(entry.clientService()).isEqualTo(method.clientService());
            assertThat(entry.playerService()).isEqualTo(method.playerService());
            assertThat(entry.gmCommand()).isEqualTo(method.gmCommand());
            String respName = method.responsePrototype().getDescriptorForType().getFullName();
            boolean expectedHasResp = !respName.equals("Empty") && !respName.equals("google.protobuf.Empty");
            assertThat(entry.hasResponse()).as(method.key()).isEqualTo(expectedHasResp);
            assertThat(ContractMessageIds.keyOf(method.messageId())).isEqualTo(method.key());

            if (method.clientService()) {
                assertThat(ContractRouteTable.clientRoute(method.messageId())).isEqualTo(entry);
                assertThat(entry.nodeType()).as(method.key() + " 必须解析到明确节点类型")
                        .isNotEqualTo(ContractNodeType.UNKNOWN);
            } else {
                assertThat(ContractRouteTable.clientRoute(method.messageId())).isNull();
            }
        }
    }

    @Test
    void 关键节点类型与事件号常量和契约一致() {
        assertThat(ContractRouteTable.lookup(ContractMessageIds.BATTLE_CLIENT_PLAYER_SUBMIT_BATTLE_ACTION).nodeType())
                .isEqualTo(ContractNodeType.BATTLE);
        assertThat(ContractRouteTable.lookup(ContractMessageIds.BATTLE_CLIENT_PLAYER_SUBMIT_BATTLE_ACTION).directOnly())
                .isTrue();
        assertThat(ContractRouteTable.lookup(ContractMessageIds.GATE_CLIENT_SPECTATE_SUBSCRIBE_SPECTATE).nodeType())
                .isEqualTo(ContractNodeType.GATE);
        assertThat(ContractRouteTable.lookup(ContractMessageIds.GATE_CLIENT_SPECTATE_SUBSCRIBE_SPECTATE).gateLocal())
                .isTrue();
        assertThat(ContractRouteTable.lookup(ContractMessageIds.CLIENT_PLAYER_LOGIN_LOGIN).nodeType())
                .isEqualTo(ContractNodeType.LOGIN);
        assertThat(ContractRouteTable.lookup(ContractMessageIds.CLIENT_PLAYER_FRIEND_ADD_FRIEND).nodeType())
                .isEqualTo(ContractNodeType.FRIEND);
        assertThat(ContractRouteTable.lookup(ContractMessageIds.GUILD_SERVICE_CREATE_GUILD).nodeType())
                .isEqualTo(ContractNodeType.GUILD);
        assertThat(ContractRouteTable.lookup(ContractMessageIds.CLIENT_PLAYER_PAYMENT_LIST_PAY_CHANNELS).nodeType())
                .isEqualTo(ContractNodeType.PAYMENT);
        assertThat(ContractRouteTable.lookup(ContractMessageIds.CONTENT_GUARD_CHECK_TEXT).nodeType())
                .isEqualTo(ContractNodeType.CONTENT_GUARD);
        assertThat(ContractRouteTable.lookup(ContractMessageIds.ROLE_FENCE_PARTICIPANT_PURGE_REMOVED_ROLE).nodeType())
                .isEqualTo(ContractNodeType.UNKNOWN);

        assertThat(ContractEventIds.BATTLE_CONFIRMED_EVENT).isEqualTo(45);
        assertThat(ContractEventIds.PLAYER_INBOX_NOTIFY_EVENT).isEqualTo(49);
        assertThat(ContractEventIds.nameOf(45)).isEqualTo("BattleConfirmedEvent");
        assertThat(ContractEventIds.nameOf(0)).as("墓碑事件号不进活表").isNull();
    }

    @Test
    void 类型化Rpc桩与零反射ContractDispatcher端到端往返() throws Exception {
        assertThat(MatchServiceRpc.JOIN_QUEUE.messageId()).isEqualTo(ContractMessageIds.MATCH_SERVICE_JOIN_QUEUE);
        assertThat(MatchServiceRpc.JOIN_QUEUE.hasResponse()).isTrue();
        assertThat(MatchServiceRpc.CANCEL_QUEUE.hasResponse()).isFalse();
        assertThat(MatchServiceRpc.NOTIFY_CHALLENGE_INVITE.rpcKind()).isEqualTo(ContractClientRpcKind.PUSH);

        ContentGuardHandler<String> handler = new ContentGuardHandler<>() {
            @Override
            public CheckTextResponse checkText(String ctx, CheckTextRequest req) {
                return CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_PASS)
                        .setMaskedText(ctx + ":" + req.getText())
                        .build();
            }

            @Override
            public CheckTextResponse checkPlatformText(String ctx, CheckTextRequest req) {
                return CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_REJECT).build();
            }
        };

        assertThat(ContentGuardContractDispatcher.handles(ContentGuardRpc.CHECK_TEXT.messageId())).isTrue();
        assertThat(ContentGuardContractDispatcher.handles(MatchServiceRpc.JOIN_QUEUE.messageId())).isFalse();

        CheckTextRequest req = CheckTextRequest.newBuilder()
                .setScene(TextScene.TEXT_SCENE_CHAT_WORLD).setText("hello").build();
        Message resp = ContentGuardContractDispatcher.dispatch(
                ContentGuardRpc.CHECK_TEXT.messageId(), "ctx1", req.toByteString(), handler);
        assertThat(resp).isInstanceOf(CheckTextResponse.class);
        assertThat(((CheckTextResponse) resp).getMaskedText()).isEqualTo("ctx1:hello");

        assertThat(ContentGuardContractDispatcher.dispatch(
                MatchServiceRpc.JOIN_QUEUE.messageId(), "ctx1", ByteString.EMPTY, handler)).isNull();
    }
}
