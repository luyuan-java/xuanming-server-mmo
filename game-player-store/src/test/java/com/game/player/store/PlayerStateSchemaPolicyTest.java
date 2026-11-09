package com.game.player.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.*;

import com.game.contract.check.persist.PersistentSchemaLedger;
import com.game.contract.check.persist.PersistentSchemaSnapshot;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 用真实动态 descriptor 证明历史政策和当前可达闭包是两个维度；不写生产台账。 */
class PlayerStateSchemaPolicyTest {
    @TempDir Path directory;

    @Test void retiredMessageRetainsPolicyNeededToVerifyEarlierLegalAddition() throws Exception {
        var first = childSchema(false);
        var expanded = childSchema(true);
        var retired = retiredSchema("child");
        var allowed = Set.of("policy.Child");
        String genesis = writeGenesis(first);
        var second = PersistentSchemaLedger.append(directory, genesis, genesis, expanded, allowed);
        var third = PersistentSchemaLedger.append(directory, genesis, second.sha256(), retired, allowed);

        assertThat(retired.messages()).doesNotContainKey("policy.Child");
        assertThat(PlayerStateSchemaLedgerTest.verifyRegisteredSchema(directory, genesis, third.sha256(), retired, allowed)).isEqualTo(3);
        // 删除历史解冻条目会破坏第二代的合法性，不能用删策略来规避“当前不可达”。
        assertThatThrownBy(() -> PersistentSchemaLedger.verify(directory, genesis, third.sha256(), retired, Set.of()))
                .hasMessageContaining("FROZEN_MESSAGE_ADDITION");
    }

    @Test void retiredEnumRetainsItsIndependentNumericEvolutionPolicy() throws Exception {
        var first = enumSchema(false);
        var expanded = enumSchema(true);
        var retired = retiredSchema("choice");
        var allowed = Set.of("policy.Choice");
        String genesis = writeGenesis(first);
        var second = PersistentSchemaLedger.append(directory, genesis, genesis, expanded, allowed);
        var third = PersistentSchemaLedger.append(directory, genesis, second.sha256(), retired, allowed);

        assertThat(retired.enums()).isEmpty();
        assertThat(PlayerStateSchemaLedgerTest.verifyRegisteredSchema(directory, genesis, third.sha256(), retired, allowed)).isEqualTo(3);
        assertThatThrownBy(() -> PersistentSchemaLedger.verify(directory, genesis, third.sha256(), retired, Set.of()))
                .hasMessageContaining("FROZEN_ENUM_ADDITION");
    }

    @Test void unknownPolicyNameIsRejectedEvenWhenOtherNamesAreKnown() throws Exception {
        var current = simpleSchema();
        String genesis = writeGenesis(current);
        assertThat(PlayerStateSchemaLedgerTest.verifyRegisteredSchema(directory, genesis, genesis, current, Set.of("policy.Root"))).isEqualTo(1);
        assertThatThrownBy(() -> PlayerStateSchemaLedgerTest.verifyRegisteredSchema(directory, genesis, genesis, current,
                Set.of("policy.Root", "policy.Typo")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("policy.Typo");
    }

    @Test void newlyReachableCurrentTypeIsKnownButStillRequiresExplicitRegistration() throws Exception {
        var first = simpleSchema();
        String genesis = writeGenesis(first);
        var child = DescriptorProto.newBuilder().setName("Child").addField(field("id", 1, TYPE_UINT64));
        var root = root().addField(field("id", 1, TYPE_UINT64))
                .addField(field("child", 2, TYPE_MESSAGE).setTypeName(".policy.Child"));
        var current = capture(file().addMessageType(root).addMessageType(child));
        assertThat(current.messages()).containsKey("policy.Child");
        assertThatThrownBy(() -> PlayerStateSchemaLedgerTest.verifyRegisteredSchema(directory, genesis, genesis, current,
                Set.of("policy.Root", "policy.Child"))).hasMessage("SCHEMA_NOT_REGISTERED");
    }

    private String writeGenesis(PersistentSchemaSnapshot snapshot) throws Exception {
        byte[] bytes = PersistentSchemaLedger.genesisBytes(snapshot);
        Files.write(directory.resolve("0001.schema"), bytes);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static PersistentSchemaSnapshot simpleSchema() throws Exception {
        return capture(file().addMessageType(root().addField(field("id", 1, TYPE_UINT64))));
    }

    private static PersistentSchemaSnapshot childSchema(boolean expanded) throws Exception {
        var root = root().addField(field("child", 1, TYPE_MESSAGE).setTypeName(".policy.Child"));
        var child = DescriptorProto.newBuilder().setName("Child").addField(field("id", 1, TYPE_UINT64));
        if (expanded) child.addField(field("name", 2, TYPE_STRING));
        return capture(file().addMessageType(root).addMessageType(child));
    }

    private static PersistentSchemaSnapshot enumSchema(boolean expanded) throws Exception {
        var root = root().addField(field("choice", 1, TYPE_ENUM).setTypeName(".policy.Choice"));
        var choice = EnumDescriptorProto.newBuilder().setName("Choice")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("UNSPECIFIED").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("FIRST").setNumber(1));
        if (expanded) choice.addValue(EnumValueDescriptorProto.newBuilder().setName("SECOND").setNumber(2));
        return capture(file().addMessageType(root).addEnumType(choice));
    }

    private static PersistentSchemaSnapshot retiredSchema(String formerField) throws Exception {
        var root = root().addReservedName(formerField)
                .addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(1).setEnd(2));
        return capture(file().addMessageType(root));
    }

    private static DescriptorProto.Builder root() { return DescriptorProto.newBuilder().setName("Root"); }
    private static FileDescriptorProto.Builder file() {
        return FileDescriptorProto.newBuilder().setName("policy.proto").setPackage("policy").setSyntax("proto3");
    }
    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setLabel(LABEL_OPTIONAL).setType(type);
    }
    private static PersistentSchemaSnapshot capture(FileDescriptorProto.Builder file) throws Exception {
        return PersistentSchemaSnapshot.capture(FileDescriptor.buildFrom(file.build(), new FileDescriptor[0]).findMessageTypeByName("Root"));
    }
}
