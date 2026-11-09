package com.game.contract.check.persist;

import static org.assertj.core.api.Assertions.*;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label.*;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.*;

import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentSchemaLedgerTest {
    @TempDir Path temporary;
    private static final Set<String> OPEN = Set.of("persist.Root", "persist.Child", "persist.Choice");

    @Test void realDescriptorRoundTripAndDeclarationOrderAreCanonical() throws Exception {
        var first = schema(root().addField(field("b", 2, TYPE_STRING)).addField(field("a", 1, TYPE_UINT64)));
        var next = schema(root().addField(field("a", 1, TYPE_UINT64)).addField(field("b", 2, TYPE_STRING)));
        assertThat(next.bytes()).isEqualTo(first.bytes());
        assertThat(PersistentSchemaSnapshot.parse(first.bytes())).isEqualTo(first);
        assertThat(first.messages()).containsOnlyKeys("persist.Root");
    }

    @Test void missingOrEmptyLedgerNeverBootstraps() throws Exception {
        var shape = schema(root().addField(field("a", 1, TYPE_UINT64)));
        assertThatThrownBy(() -> PersistentSchemaLedger.verify(temporary.resolve("absent"), pin(shape), pin(shape), shape, OPEN))
                .hasMessage("LEDGER_REQUIRED");
        assertThatThrownBy(() -> PersistentSchemaLedger.append(temporary, pin(shape), pin(shape), shape, OPEN)).hasMessage("LEDGER_EMPTY");
        try (var files = Files.list(temporary)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void actualSnapshotMustBeRegisteredEvenForLegalAddition() throws Exception {
        var before = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var after = schema(root().addField(field("a", 1, TYPE_UINT64)).addField(field("b", 2, TYPE_STRING)));
        Path directory = genesis(before);
        assertThatThrownBy(() -> PersistentSchemaLedger.verify(directory, pin(before), pin(before), after, OPEN)).hasMessage("SCHEMA_NOT_REGISTERED");
        var added = PersistentSchemaLedger.append(directory, pin(before), pin(before), after, OPEN);
        assertThat(added.generation()).isEqualTo(2);
        assertThat(PersistentSchemaLedger.verify(directory, pin(before), added.sha256(), after, OPEN)).isEqualTo(2);
        assertThat(Files.readAllBytes(directory.resolve("0001.schema"))).isEqualTo(PersistentSchemaLedger.genesisBytes(before));
    }

    @Test void snapshotWithoutChangeDoesNotConsumeAnotherGeneration() throws Exception {
        var before = schema(root().addField(field("a", 1, TYPE_UINT64)));
        Path directory = genesis(before);
        assertThatThrownBy(() -> PersistentSchemaLedger.append(directory, pin(before), pin(before), before, OPEN)).hasMessage("SCHEMA_UNCHANGED");
        assertThat(Files.exists(directory.resolve("0002.schema"))).isFalse();
    }

    @Test void frozenTypeCannotAddField() throws Exception {
        var before = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var after = schema(root().addField(field("a", 1, TYPE_UINT64)).addField(field("b", 2, TYPE_STRING)));
        assertThat(codes(before, after, Set.of())).contains("FROZEN_MESSAGE_ADDITION");
        assertThat(codes(before, after, OPEN)).isEmpty();
    }

    @Test void exactWireTypeLabelNameAndJsonIdentityCannotChange() throws Exception {
        var before = schema(root().addField(field("a", 1, TYPE_UINT64)));
        for (var replacement : List.of(field("a", 1, TYPE_INT64), field("a", 1, TYPE_UINT64).setLabel(LABEL_REPEATED),
                field("renamed", 1, TYPE_UINT64), field("a", 1, TYPE_UINT64).setJsonName("other"))) {
            assertThat(codes(before, schema(root().addField(replacement)), OPEN)).contains("FIELD_CHANGED");
        }
    }

    @Test void scalarPresenceAndOneofMembershipCannotChange() throws Exception {
        var before = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var optional = root().addOneofDecl(OneofDescriptorProto.newBuilder().setName("_a"))
                .addField(field("a", 1, TYPE_UINT64).setOneofIndex(0).setProto3Optional(true));
        assertThat(codes(before, schema(optional), OPEN)).contains("FIELD_CHANGED");
        var oneof = root().addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
                .addField(field("a", 1, TYPE_UINT64).setOneofIndex(0));
        assertThat(codes(before, schema(oneof), OPEN)).contains("FIELD_CHANGED");
    }

    @Test void addingMemberToExistingRealOneofIsRejectedEvenWhenUnfrozen() throws Exception {
        var first = root().addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
                .addField(field("a", 1, TYPE_UINT64).setOneofIndex(0));
        var second = first.clone().addField(field("b", 2, TYPE_STRING).setOneofIndex(0));
        assertThat(codes(schema(first), schema(second), OPEN)).contains("EXISTING_ONEOF_ADDITION");
    }

    @Test void independentSyntheticOptionalOneofCanBeAddedToProvenType() throws Exception {
        var before = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var after = schema(root().addField(field("a", 1, TYPE_UINT64))
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_b"))
                .addField(field("b", 2, TYPE_STRING).setOneofIndex(0).setProto3Optional(true)));
        assertThat(codes(before, after, OPEN)).isEmpty();
    }

    @Test void addingEarlierOneofNameDoesNotRenumberExistingIdentity() throws Exception {
        var first = root().addOneofDecl(OneofDescriptorProto.newBuilder().setName("z"))
                .addField(field("a", 1, TYPE_STRING).setOneofIndex(0));
        var second = first.clone().addOneofDecl(OneofDescriptorProto.newBuilder().setName("a"))
                .addField(field("b", 2, TYPE_STRING).setOneofIndex(1));
        assertThat(codes(schema(first), schema(second), OPEN)).isEmpty();
    }

    @Test void packedAndProto2DefaultChangesAreRejected() throws Exception {
        var unpacked = field("a", 1, TYPE_UINT64).setLabel(LABEL_REPEATED)
                .setOptions(FieldOptions.newBuilder().setPacked(false));
        var packed = unpacked.clone().setOptions(FieldOptions.newBuilder().setPacked(true));
        assertThat(codes(schema(root().addField(unpacked)), schema(root().addField(packed)), OPEN)).contains("FIELD_CHANGED");
        var first = schema("proto2", root().addField(field("a", 1, TYPE_UINT64).setDefaultValue("1")));
        var second = schema("proto2", root().addField(field("a", 1, TYPE_UINT64).setDefaultValue("2")));
        assertThat(codes(first, second, OPEN)).contains("FIELD_CHANGED");
    }

    @Test void newRequiredFieldIsNotACompatibleAddition() throws Exception {
        var before = schema("proto2", root().addField(field("a", 1, TYPE_UINT64)));
        var after = schema("proto2", root().addField(field("a", 1, TYPE_UINT64)).addField(field("b", 2, TYPE_STRING).setLabel(LABEL_REQUIRED)));
        assertThat(codes(before, after, OPEN)).contains("REQUIRED_FIELD_ADDITION");
    }

    @Test void removedFieldNeedsBothNumberAndNameReservation() throws Exception {
        var before = schema(root().addField(field("a", 1, TYPE_UINT64)));
        assertThat(codes(before, schema(root()), OPEN)).contains("FIELD_REMOVED_WITHOUT_RESERVED");
        var reserved = root().addReservedRange(range(1, 2));
        assertThat(codes(before, schema(reserved), OPEN)).contains("FIELD_REMOVED_WITHOUT_RESERVED");
        assertThat(codes(before, schema(reserved.addReservedName("a")), OPEN)).isEmpty();
    }

    @Test void retiredNumberAndNameRemainUnavailableAcrossLaterGenerations() throws Exception {
        var first = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var retired = schema(root().addReservedRange(range(1, 2)).addReservedName("a"));
        var reused = schema(root().addField(field("a", 1, TYPE_UINT64)));
        assertThat(PersistentSchemaLedger.compare(List.of(first, retired), reused, OPEN)).extracting(PersistentSchemaLedger.Violation::code)
                .contains("FIELD_RESERVED_REUSED", "MESSAGE_RESERVED_REMOVED");
        var reusedName = schema(root().addReservedRange(range(1, 2)).addField(field("a", 2, TYPE_UINT64)));
        assertThat(codes(retired, reusedName, OPEN)).contains("FIELD_RESERVED_REUSED");
    }

    @Test void unreachableChildKeepsHistoricalConstraintsWhenReachedAgain() throws Exception {
        var child = DescriptorProto.newBuilder().setName("Child").addField(field("id", 1, TYPE_UINT64));
        var firstRoot = root().addField(field("child", 1, TYPE_MESSAGE).setTypeName(".persist.Child"));
        var first = schema("proto3", firstRoot, child);
        var retired = schema(root().addReservedRange(range(1, 2)).addReservedName("child"));
        assertThat(codes(first, retired, OPEN)).isEmpty();
        var revivedRoot = root().addReservedRange(range(1, 2)).addReservedName("child")
                .addField(field("restored_child", 2, TYPE_MESSAGE).setTypeName(".persist.Child"));
        var changedChild = child.clone().setField(0, field("id", 1, TYPE_STRING));
        assertThat(PersistentSchemaLedger.compare(List.of(first, retired), schema("proto3", revivedRoot, changedChild), OPEN))
                .extracting(PersistentSchemaLedger.Violation::code).contains("FIELD_CHANGED");
    }

    @Test void mapKeyAndValueShapeCannotChange() throws Exception {
        var first = mapSchema(TYPE_UINT32, TYPE_UINT64);
        var second = mapSchema(TYPE_STRING, TYPE_UINT64);
        assertThat(codes(first, second, OPEN)).contains("FIELD_CHANGED");
        assertThat(codes(first, mapSchema(TYPE_UINT32, TYPE_STRING), OPEN)).contains("FIELD_CHANGED");
    }

    @Test void enumAdditionsAreFrozenUnlessNumericRoundTripHasEvidence() throws Exception {
        var first = enumSchema(enumeration().addValue(value("READY", 1)));
        var added = enumSchema(enumeration().addValue(value("READY", 1)).addValue(value("FUTURE", 2)));
        assertThat(codes(first, added, Set.of("persist.Root"))).contains("FROZEN_ENUM_ADDITION");
        assertThat(codes(first, added, OPEN)).isEmpty();
    }

    @Test void enumNumberReuseNameRemovalAndDefaultChangesAreRejected() throws Exception {
        var first = enumSchema(enumeration().addValue(value("READY", 1)));
        assertThat(codes(first, enumSchema(enumeration().addValue(value("READY", 2))), OPEN)).contains("ENUM_VALUE_CHANGED");
        assertThat(codes(first, enumSchema(enumeration().addValue(value("OTHER", 1))), OPEN))
                .contains("ENUM_NUMBER_REUSED", "ENUM_VALUE_REMOVED_WITHOUT_RESERVED");
        var changedDefault = EnumDescriptorProto.newBuilder().setName("Choice").addValue(value("NEW_DEFAULT", 0)).addValue(value("READY", 1));
        assertThat(codes(first, enumSchema(changedDefault), OPEN)).contains("ENUM_SEMANTICS_CHANGED");
    }

    @Test void enumRetirementRequiresNumberAndNameAndCannotBeReused() throws Exception {
        var first = enumSchema(enumeration().addValue(value("READY", 1)));
        var retired = enumSchema(enumeration().addReservedRange(EnumDescriptorProto.EnumReservedRange.newBuilder().setStart(1).setEnd(1))
                .addReservedName("READY"));
        assertThat(codes(first, retired, OPEN)).isEmpty();
        assertThat(PersistentSchemaLedger.compare(List.of(first, retired), first, OPEN)).extracting(PersistentSchemaLedger.Violation::code)
                .contains("ENUM_RESERVED_REUSED", "ENUM_RESERVED_REMOVED");
    }

    @Test void corruptedGenesisAndRemovedTailFailPinnedHistory() throws Exception {
        var first = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var second = schema(root().addField(field("a", 1, TYPE_UINT64)).addField(field("b", 2, TYPE_STRING)));
        Path directory = genesis(first);
        var added = PersistentSchemaLedger.append(directory, pin(first), pin(first), second, OPEN);
        Files.delete(directory.resolve("0002.schema"));
        assertThatThrownBy(() -> PersistentSchemaLedger.read(directory, pin(first), added.sha256())).hasMessage("LEDGER_HEAD_CHANGED");
        Files.write(directory.resolve("0001.schema"), PersistentSchemaLedger.genesisBytes(second));
        assertThatThrownBy(() -> PersistentSchemaLedger.read(directory, pin(first), pin(first))).hasMessage("LEDGER_GENESIS_CHANGED");
    }

    @Test void trustedPrefixDetectsTailRemovalEvenIfCandidateHeadWasChanged() throws Exception {
        var first = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var second = schema(root().addField(field("a", 1, TYPE_UINT64)).addField(field("b", 2, TYPE_STRING)));
        Path directory = genesis(first);
        var added = PersistentSchemaLedger.append(directory, pin(first), pin(first), second, OPEN);
        var trusted = PersistentSchemaLedger.read(directory, pin(first), added.sha256());
        Files.delete(directory.resolve("0002.schema"));
        var truncated = PersistentSchemaLedger.read(directory, pin(first), pin(first));
        assertThatThrownBy(() -> PersistentSchemaLedger.requirePrefix(trusted, truncated)).hasMessage("LEDGER_HISTORY_REMOVED");
        PersistentSchemaLedger.requirePrefix(truncated, trusted);
    }

    @Test void holesExtraFilesAndWrongPredecessorDoNotPass() throws Exception {
        var first = schema(root().addField(field("a", 1, TYPE_UINT64)));
        Path directory = genesis(first);
        Files.writeString(directory.resolve("other.txt"), "not a generation");
        assertThatThrownBy(() -> PersistentSchemaLedger.read(directory, pin(first), pin(first))).hasMessage("LEDGER_UNEXPECTED_FILE");
        Files.delete(directory.resolve("other.txt"));
        Files.write(directory.resolve("0003.schema"), PersistentSchemaLedger.genesisBytes(first));
        assertThatThrownBy(() -> PersistentSchemaLedger.read(directory, pin(first), pin(first))).hasMessage("LEDGER_GENERATION_GAP");
        Files.move(directory.resolve("0003.schema"), directory.resolve("0002.schema"));
        assertThatThrownBy(() -> PersistentSchemaLedger.read(directory, pin(first), pin(first))).hasMessage("LEDGER_CHAIN");
    }

    @Test void invalidSnapshotReferencesOneofNumbersAndUnreachableTypesAreRejected() throws Exception {
        var good = schema(root().addField(field("a", 1, TYPE_UINT64)));
        var shape = good.messages().get("persist.Root");
        for (var invalid : List.of(shape.proto().toBuilder().setField(0, field("a", 0, TYPE_UINT64)).build(),
                shape.proto().toBuilder().setField(0, field("a", 1, TYPE_UINT64).setOneofIndex(9)).build(),
                shape.proto().toBuilder().setField(0, field("a", 1, TYPE_MESSAGE).setTypeName(".persist.Missing")).build())) {
            var messages = new TreeMap<>(good.messages());
            messages.put("persist.Root", new PersistentSchemaSnapshot.MessageShape("proto3", invalid));
            assertThatThrownBy(() -> new PersistentSchemaSnapshot("persist.Root", messages, good.enums())).isInstanceOf(IllegalArgumentException.class);
        }
        var extra = new TreeMap<>(good.messages());
        extra.put("persist.Unreachable", new PersistentSchemaSnapshot.MessageShape("proto3", DescriptorProto.newBuilder().setName("Unreachable").build()));
        assertThatThrownBy(() -> new PersistentSchemaSnapshot("persist.Root", extra, good.enums())).hasMessage("UNREACHABLE_TYPES");
    }

    @Test void nonCanonicalSnapshotAndOversizePayloadAreRejected() throws Exception {
        var good = schema(root().addField(field("a", 1, TYPE_UINT64)));
        byte[] bytes = new String(good.bytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\n", "\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PersistentSchemaSnapshot.parse(bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PersistentSchemaSnapshot.parse(new byte[PersistentSchemaLedger.MAX_SNAPSHOT_BYTES + 1])).hasMessage("SNAPSHOT_SIZE");
    }

    private Path genesis(PersistentSchemaSnapshot shape) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "ledger");
        Files.write(directory.resolve("0001.schema"), PersistentSchemaLedger.genesisBytes(shape));
        return directory;
    }
    private static String pin(PersistentSchemaSnapshot shape) { return PersistentSchemaSnapshot.sha256(PersistentSchemaLedger.genesisBytes(shape)); }
    private static List<String> codes(PersistentSchemaSnapshot old, PersistentSchemaSnapshot next, Set<String> allowed) {
        return PersistentSchemaLedger.compare(List.of(old), next, allowed).stream().map(PersistentSchemaLedger.Violation::code).toList();
    }
    static DescriptorProto.Builder root() { return DescriptorProto.newBuilder().setName("Root"); }
    static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type).setLabel(LABEL_OPTIONAL);
    }
    private static DescriptorProto.ReservedRange.Builder range(int start, int end) { return DescriptorProto.ReservedRange.newBuilder().setStart(start).setEnd(end); }
    static PersistentSchemaSnapshot schema(DescriptorProto.Builder root) throws Exception { return schema("proto3", root); }
    static PersistentSchemaSnapshot schema(String syntax, DescriptorProto.Builder root, DescriptorProto.Builder... extra) throws Exception {
        var file = FileDescriptorProto.newBuilder().setName("state.proto").setPackage("persist").setSyntax(syntax).addMessageType(root);
        for (var message : extra) file.addMessageType(message);
        return PersistentSchemaSnapshot.capture(FileDescriptor.buildFrom(file.build(), new FileDescriptor[0]).findMessageTypeByName("Root"));
    }
    private static PersistentSchemaSnapshot mapSchema(FieldDescriptorProto.Type key, FieldDescriptorProto.Type value) throws Exception {
        var entry = DescriptorProto.newBuilder().setName("ValuesEntry").setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(field("key", 1, key)).addField(field("value", 2, value));
        return schema(root().addNestedType(entry).addField(field("values", 1, TYPE_MESSAGE)
                .setLabel(LABEL_REPEATED).setTypeName(".persist.Root.ValuesEntry")));
    }
    private static EnumValueDescriptorProto.Builder value(String name, int number) { return EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number); }
    private static EnumDescriptorProto.Builder enumeration() { return EnumDescriptorProto.newBuilder().setName("Choice").addValue(value("UNSPECIFIED", 0)); }
    private static PersistentSchemaSnapshot enumSchema(EnumDescriptorProto.Builder enumeration) throws Exception {
        var file = FileDescriptorProto.newBuilder().setName("state.proto").setPackage("persist").setSyntax("proto3")
                .addMessageType(root().addField(field("choice", 1, TYPE_ENUM).setTypeName(".persist.Choice"))).addEnumType(enumeration);
        return PersistentSchemaSnapshot.capture(FileDescriptor.buildFrom(file.build(), new FileDescriptor[0]).findMessageTypeByName("Root"));
    }
}
