package com.game.contract.check;

import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.UnknownFieldSet;
import com.game.contract.check.ContractCompatibility.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 从真实 protobuf 描述符构造跨版本夹具，覆盖 wire 相同但语义不同及历史不可豁免。 */
class ContractCompatibilityTest {
    static final String SHA = "a".repeat(40);
    static final String IDS = "0=ApiCall\n1=reserved:OldCall\n";
    static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
    static FileDescriptorProto.Builder app() {
        return FileDescriptorProto.newBuilder().setName("app.proto").setPackage("demo").setSyntax("proto3")
                .addDependency("options.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("Request").addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32)))
                .addMessageType(DescriptorProto.newBuilder().setName("Response"))
                .addService(ServiceDescriptorProto.newBuilder().setName("Api")
                        .setOptions(ServiceOptions.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                                .addField(400001, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build()))
                        .addMethod(MethodDescriptorProto.newBuilder().setName("Call").setInputType(".demo.Request").setOutputType(".demo.Response")));
    }
    static FileDescriptorSet set(FileDescriptorProto.Builder app) {
        FileDescriptorProto options = FileDescriptorProto.newBuilder().setName("options.proto").setSyntax("proto2")
                .addDependency("google/protobuf/descriptor.proto")
                .addExtension(field("OptionIsClientProtocolService", 400001, FieldDescriptorProto.Type.TYPE_BOOL).setExtendee(".google.protobuf.ServiceOptions"))
                .addExtension(field("OptionIsPlayerService", 400000, FieldDescriptorProto.Type.TYPE_BOOL).setExtendee(".google.protobuf.ServiceOptions"))
                .build();
        return FileDescriptorSet.newBuilder().addFile(FileDescriptorProto.getDescriptor().getFile().toProto())
                .addFile(options).addFile(app).build();
    }
    static Surface surface(FileDescriptorProto.Builder app) { return surface(app, IDS, ""); }
    static Surface surface(FileDescriptorProto.Builder app, String ids, String external) {
        return ContractCompatibility.read(set(app), ids, external, SHA, SHA);
    }
    static List<Finding> change(Consumer<FileDescriptorProto.Builder> mutation) {
        FileDescriptorProto.Builder next = app();
        mutation.accept(next);
        return ContractCompatibility.compare(surface(app()), surface(next));
    }
    static void has(List<Finding> findings, Severity severity, String code) {
        assertTrue(findings.stream().anyMatch(f -> f.severity() == severity && f.code().equals(code)), () -> findings.toString());
    }
    static DescriptorProto.Builder request(FileDescriptorProto.Builder app) { return app.getMessageTypeBuilder(0); }

    @Test void identicalAndDescriptorDeclarationOrderHaveIdenticalFingerprints() {
        Surface first = surface(app());
        FileDescriptorSet initial = set(app());
        List<FileDescriptorProto> reversed = new ArrayList<>(initial.getFileList());
        Collections.reverse(reversed);
        Surface reverse = ContractCompatibility.read(FileDescriptorSet.newBuilder().addAllFile(reversed).build(), IDS, "", SHA, SHA);
        assertEquals(first.canonical(), reverse.canonical());
        assertEquals(first.fingerprint(), reverse.fingerprint());
        assertTrue(ContractCompatibility.compare(first, reverse).isEmpty());
    }

    @Test void safeOptionalFieldAndEnumValueAdditionsPass() {
        List<Finding> fields = change(a -> request(a).addField(field("note", 2, FieldDescriptorProto.Type.TYPE_STRING)));
        has(fields, Severity.INFO, "FIELD_ADDED");
        assertTrue(fields.stream().noneMatch(f -> f.severity() == Severity.BREAK || f.severity() == Severity.ERROR));
        FileDescriptorProto.Builder old = withEnum();
        FileDescriptorProto.Builder next = old.clone();
        next.getEnumTypeBuilder(0).addValue(EnumValueDescriptorProto.newBuilder().setName("MORE").setNumber(2));
        has(ContractCompatibility.compare(surface(old), surface(next)), Severity.INFO, "ENUM_VALUE_ADDED");
    }

    @Test void sameWireTypeDoesNotMakeATypeChangeSafe() {
        for (FieldDescriptorProto.Type type : List.of(FieldDescriptorProto.Type.TYPE_BOOL, FieldDescriptorProto.Type.TYPE_UINT64,
                FieldDescriptorProto.Type.TYPE_SINT32)) {
            has(change(a -> request(a).getFieldBuilder(0).setType(type)), Severity.BREAK, "FIELD_TYPE_CHANGED");
        }
    }

    @Test void numberReuseAndCardinalityAreBreaking() {
        has(change(a -> request(a).getFieldBuilder(0).setName("credits")), Severity.BREAK, "FIELD_NUMBER_REUSED");
        has(change(a -> request(a).getFieldBuilder(0).setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)), Severity.BREAK, "FIELD_CARDINALITY_CHANGED");
    }

    @Test void deletionNeedsBothNumberAndNameReservations() {
        has(change(a -> request(a).clearField()), Severity.BREAK, "FIELD_REMOVED_UNRESERVED");
        has(change(a -> request(a).clearField().addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(1).setEnd(2))),
                Severity.BREAK, "FIELD_REMOVED_UNRESERVED");
        has(change(a -> request(a).clearField().addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(1).setEnd(2))
                .addReservedName("count")), Severity.WARN, "FIELD_REMOVED_RESERVED");
    }

    @Test void presenceChangesButSyntheticOneofNamesDoNot() {
        has(change(a -> {
            request(a).addOneofDecl(OneofDescriptorProto.newBuilder().setName("_count"));
            request(a).getFieldBuilder(0).setProto3Optional(true).setOneofIndex(0);
        }), Severity.BREAK, "FIELD_PRESENCE_CHANGED");
        FileDescriptorProto.Builder old = app();
        request(old).addOneofDecl(OneofDescriptorProto.newBuilder().setName("_count"));
        request(old).getFieldBuilder(0).setProto3Optional(true).setOneofIndex(0);
        FileDescriptorProto.Builder next = old.clone();
        request(next).getOneofDeclBuilder(0).setName("_renamed_synthetic");
        assertEquals(surface(old).fingerprint(), surface(next).fingerprint());
    }

    @Test void movingIntoRealOneofAndAddingChoiceFail() {
        has(change(a -> {
            request(a).addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"));
            request(a).getFieldBuilder(0).setOneofIndex(0);
        }), Severity.BREAK, "FIELD_ONEOF_CHANGED");
        FileDescriptorProto.Builder old = app();
        request(old).addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"));
        request(old).getFieldBuilder(0).setOneofIndex(0);
        FileDescriptorProto.Builder next = old.clone();
        request(next).addField(field("other", 2, FieldDescriptorProto.Type.TYPE_INT32).setOneofIndex(0));
        has(ContractCompatibility.compare(surface(old), surface(next)), Severity.BREAK, "ONEOF_MEMBER_ADDED");
    }

    @Test void mapAndPlainRepeatedEntryAreDifferentContracts() {
        FileDescriptorProto.Builder map = app();
        request(map).clearField().addNestedType(DescriptorProto.newBuilder().setName("ItemsEntry")
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(field("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(field("value", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".demo.Request.ItemsEntry")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED));
        FileDescriptorProto.Builder plain = map.clone();
        request(plain).getNestedTypeBuilder(0).clearOptions();
        has(ContractCompatibility.compare(surface(map), surface(plain)), Severity.BREAK, "FIELD_MAP_CHANGED");
        FileDescriptorProto.Builder changedKey = map.clone();
        request(changedKey).getNestedTypeBuilder(0).getFieldBuilder(0).setType(FieldDescriptorProto.Type.TYPE_INT32);
        has(ContractCompatibility.compare(surface(map), surface(changedKey)), Severity.BREAK, "FIELD_TYPE_CHANGED");
    }

    @Test void nestedRecursiveMessageClosureIncludesItsSemanticChanges() {
        FileDescriptorProto.Builder old = app();
        request(old).addNestedType(DescriptorProto.newBuilder().setName("Child")
                .addField(field("parent", 1, FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".demo.Request"))
                .addField(field("amount", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                .addField(field("child", 2, FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".demo.Request.Child"));
        FileDescriptorProto.Builder next = old.clone();
        request(next).getNestedTypeBuilder(0).getFieldBuilder(1).setType(FieldDescriptorProto.Type.TYPE_BOOL);
        List<Finding> diff = ContractCompatibility.compare(surface(old), surface(next));
        has(diff, Severity.BREAK, "FIELD_TYPE_CHANGED");
        assertTrue(diff.stream().anyMatch(f -> f.path().equals("message/demo.Request.Child/field/2")));
    }

    static FileDescriptorProto.Builder withEnum() {
        FileDescriptorProto.Builder app = app();
        app.addEnumType(EnumDescriptorProto.newBuilder().setName("State")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("NONE").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("LIVE").setNumber(1)));
        request(app).getFieldBuilder(0).setType(FieldDescriptorProto.Type.TYPE_ENUM).setTypeName(".demo.State");
        return app;
    }

    @Test void enumReuseAndDeletedUnreservedValuesFail() {
        FileDescriptorProto.Builder old = withEnum(), renamed = old.clone(), removed = old.clone();
        renamed.getEnumTypeBuilder(0).getValueBuilder(1).setName("DEAD");
        removed.getEnumTypeBuilder(0).removeValue(1);
        has(ContractCompatibility.compare(surface(old), surface(renamed)), Severity.BREAK, "ENUM_NUMBER_REUSED");
        has(ContractCompatibility.compare(surface(old), surface(removed)), Severity.BREAK, "ENUM_REMOVED_UNRESERVED");
        removed.getEnumTypeBuilder(0).addReservedRange(EnumDescriptorProto.EnumReservedRange.newBuilder().setStart(1).setEnd(1)).addReservedName("LIVE");
        has(ContractCompatibility.compare(surface(old), surface(removed)), Severity.WARN, "ENUM_REMOVED_RESERVED");
    }

    @Test void reservedHistoryCannotDisappearOrBeReused() {
        FileDescriptorProto.Builder old = app();
        request(old).addReservedName("retired").addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(5).setEnd(7));
        FileDescriptorProto.Builder next = old.clone();
        request(next).clearReservedRange().clearReservedName().addField(field("retired", 5, FieldDescriptorProto.Type.TYPE_STRING));
        List<Finding> diff = ContractCompatibility.compare(surface(old), surface(next));
        has(diff, Severity.ERROR, "RESERVED_RANGE_REMOVED");
        has(diff, Severity.ERROR, "RESERVED_NAME_REMOVED");
        has(diff, Severity.ERROR, "RESERVED_FIELD_REUSED");
    }

    @Test void adjacentReservationSplitsAreSemanticallyEqual() {
        FileDescriptorProto.Builder old = app(), next = app();
        request(old).addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(4).setEnd(8));
        request(next).addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(4).setEnd(6))
                .addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(6).setEnd(8));
        assertEquals(surface(old).fingerprint(), surface(next).fingerprint());
    }

    @Test void rpcInputOutputStreamingVisibilityAndOptionsAreChecked() {
        has(change(a -> a.getServiceBuilder(0).getMethodBuilder(0).setInputType(".demo.Response")), Severity.BREAK, "RPC_BINDING_CHANGED");
        has(change(a -> a.getServiceBuilder(0).getMethodBuilder(0).setOutputType(".demo.Request")), Severity.BREAK, "RPC_BINDING_CHANGED");
        has(change(a -> a.getServiceBuilder(0).getMethodBuilder(0).setClientStreaming(true)), Severity.BREAK, "RPC_BINDING_CHANGED");
        has(change(a -> a.getServiceBuilder(0).getMethodBuilder(0).setOptions(MethodOptions.newBuilder().setDeprecated(true))), Severity.BREAK, "RPC_OPTIONS_CHANGED");
        has(change(a -> a.getServiceBuilder(0).setOptions(ServiceOptions.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(400000, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build()))), Severity.BREAK, "RPC_VISIBILITY_CHANGED");
    }

    @Test void tombstoneHistoryAndLiveNumberCannotBeRewritten() {
        Surface old = surface(app());
        has(ContractCompatibility.compare(old, surface(app(), "0=ApiCall\n", "")), Severity.ERROR, "ID_HISTORY_REMOVED");
        has(ContractCompatibility.compare(old, surface(app(), "0=ApiCall\n1=reserved:Other\n", "")), Severity.ERROR, "ID_TOMBSTONE_CHANGED");
        has(ContractCompatibility.compare(old, surface(app(), "0=reserved:ApiCall\n1=ApiCall\n", "")), Severity.ERROR, "ID_TOMBSTONE_CHANGED");
        // 同名活方法获新号是原生成器允许的操作；它不能复活旧墓碑号。
        assertEquals(3, ContractCompatibility.parseIds("0=ApiCall\n1=reserved:ApiCall\n2=reserved:ApiCall\n").size());
    }

    @Test void externalExclusionsAreExplicitExactAndCannotHideResolvedMethods() {
        String ids = IDS + "5=ExternalCall\n";
        assertThrows(IllegalArgumentException.class, () -> surface(app(), ids, ""));
        Surface external = surface(app(), ids, "5\tExternalCall\t固定源证据：excluded.proto，Java 明确未同步\n");
        assertTrue(ContractCompatibility.compare(external, external).isEmpty());
        assertEquals(List.of(new ExternalMethod(5, "ExternalCall", "固定源证据：excluded.proto，Java 明确未同步")),
                ContractCompatibility.coverage(external).uncheckedMethods());
        assertThrows(IllegalArgumentException.class, () -> surface(app(), ids, "5\tWrongCall\t理由\n"));
        assertThrows(IllegalArgumentException.class, () -> surface(app(), IDS, "0\tApiCall\t试图隐藏\n"));
        assertThrows(IllegalArgumentException.class, () -> surface(app(), IDS, "1\tOldCall\t墓碑不是外部方法\n"));
    }

    @Test void formerlyResolvedInternalRpcCannotBeHiddenByNewExternalExclusion() {
        FileDescriptorProto.Builder oldApp = app().addService(ServiceDescriptorProto.newBuilder().setName("Internal")
                .addMethod(MethodDescriptorProto.newBuilder().setName("Call").setInputType(".demo.Request").setOutputType(".demo.Response")));
        String ids = IDS + "5=InternalCall\n";
        Surface old = surface(oldApp, ids, "");
        Surface next = surface(app(), ids, "5\tInternalCall\t新增排除不能降低已有覆盖\n");
        List<Finding> diff = ContractCompatibility.compare(old, next);
        has(diff, Severity.ERROR, "RPC_BECAME_UNRESOLVED");
        Finding hard = diff.stream().filter(f -> f.code().equals("RPC_BECAME_UNRESOLVED")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> ContractCheck.accepted(old.fingerprint() + "\t" + next.fingerprint()
                + "\t" + hard.id() + "\t试图批准", old, next, diff));
    }

    @Test void missingImportsReferencesDuplicateFilesAndEmptyRootsFailClosed() {
        FileDescriptorSet complete = set(app());
        assertThrows(IllegalArgumentException.class, () -> ContractCompatibility.read(
                complete.toBuilder().removeFile(0).build(), IDS, "", SHA, SHA));
        assertThrows(IllegalArgumentException.class, () -> ContractCompatibility.read(
                complete.toBuilder().addFile(complete.getFile(2)).build(), IDS, "", SHA, SHA));
        assertThrows(IllegalArgumentException.class, () -> surface(app().setSyntax("editions")));
        FileDescriptorProto.Builder missing = app();
        request(missing).getFieldBuilder(0).setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".demo.Missing");
        assertThrows(IllegalArgumentException.class, () -> surface(missing));
        FileDescriptorProto.Builder empty = app();
        empty.getServiceBuilder(0).clearOptions();
        assertThrows(IllegalArgumentException.class, () -> surface(empty));
    }

    @Test void malformedReservedCollisionOneofAndServiceOptionsFailClosed() {
        FileDescriptorProto.Builder collision = app();
        request(collision).addReservedName("count");
        assertThrows(IllegalArgumentException.class, () -> surface(collision));
        FileDescriptorProto.Builder oneof = app();
        request(oneof).getFieldBuilder(0).setOneofIndex(99);
        assertThrows(IllegalArgumentException.class, () -> surface(oneof));
        FileDescriptorProto.Builder option = app();
        option.getServiceBuilder(0).setOptions(ServiceOptions.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(400001, UnknownFieldSet.Field.newBuilder().addVarint(2).build()).build()));
        assertThrows(IllegalArgumentException.class, () -> surface(option));
    }

    @Test void proto2RequiredAdditionDeletionAndDefaultChangesBreak() {
        FileDescriptorProto.Builder old = app().setSyntax("proto2");
        FileDescriptorProto.Builder next = old.clone();
        request(next).addField(field("must", 2, FieldDescriptorProto.Type.TYPE_STRING).setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED));
        has(ContractCompatibility.compare(surface(old), surface(next)), Severity.BREAK, "FIELD_ADDED");
        request(old).getFieldBuilder(0).setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED);
        FileDescriptorProto.Builder removed = old.clone();
        request(removed).clearField().addReservedName("count")
                .addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(1).setEnd(2));
        has(ContractCompatibility.compare(surface(old), surface(removed)), Severity.BREAK, "FIELD_REMOVED_RESERVED");
        FileDescriptorProto.Builder defaultChanged = old.clone();
        request(defaultChanged).getFieldBuilder(0).setDefaultValue("7");
        has(ContractCompatibility.compare(surface(old), surface(defaultChanged)), Severity.BREAK, "FIELD_DEFAULT_CHANGED");
    }

    @Test void enumRetiredNumbersAndNamesAreNotReusable() {
        FileDescriptorProto.Builder old = withEnum();
        old.getEnumTypeBuilder(0).addReservedName("OLD").addReservedRange(EnumDescriptorProto.EnumReservedRange.newBuilder().setStart(5).setEnd(5));
        FileDescriptorProto.Builder next = old.clone();
        next.getEnumTypeBuilder(0).clearReservedName().clearReservedRange()
                .addValue(EnumValueDescriptorProto.newBuilder().setName("OLD").setNumber(5));
        has(ContractCompatibility.compare(surface(old), surface(next)), Severity.ERROR, "RESERVED_ENUM_REUSED");
    }

    @Test void duplicateConflictingAndMalformedCatalogRowsFail() {
        for (String ids : List.of("", "-1=ApiCall", "2147483648=ApiCall", "1=reserved:", "0=ApiCall\n0=reserved:OldCall",
                "0=ApiCall\n1=ApiCall", "0=ApiCall=extra", "0=Api Call")) {
            assertThrows(IllegalArgumentException.class, () -> ContractCompatibility.parseIds(ids), ids);
        }
    }

    @Test void rootsNeedAnIdEvenWhenAnotherLiveIdIsExternal() {
        assertThrows(IllegalArgumentException.class, () -> surface(app(), "3=OtherCall\n", "3\tOtherCall\t明确外部\n"));
    }

    @Test void unreferencedInternalMessagesDoNotBecomeClientClaims() {
        FileDescriptorProto.Builder a = app().addMessageType(DescriptorProto.newBuilder().setName("Internal")
                .addField(field("private_value", 1, FieldDescriptorProto.Type.TYPE_INT32)));
        FileDescriptorProto.Builder b = a.clone();
        b.getMessageTypeBuilder(2).getFieldBuilder(0).setType(FieldDescriptorProto.Type.TYPE_BOOL);
        assertTrue(ContractCompatibility.compare(surface(a), surface(b)).isEmpty());
        assertFalse(surface(a).messages().containsKey("demo.Internal"));
    }

    @Test void exactAcceptedBreakDoesNotAuthorizeAnyOtherBreakOrHardHistoryError() {
        Surface old = surface(app());
        FileDescriptorProto.Builder nextApp = app();
        request(nextApp).getFieldBuilder(0).setType(FieldDescriptorProto.Type.TYPE_BOOL);
        Surface next = surface(nextApp);
        List<Finding> findings = ContractCompatibility.compare(old, next);
        Finding change = findings.stream().filter(f -> f.code().equals("FIELD_TYPE_CHANGED")).findFirst().orElseThrow();
        String row = old.fingerprint() + "\t" + next.fingerprint() + "\t" + change.id() + "\t评审记录 #123，停止旧客户端后启用\n";
        assertEquals(1, ContractCheck.accepted(row, old, next, findings).size());
        assertThrows(IllegalArgumentException.class, () -> ContractCheck.accepted(row.replace(old.fingerprint(), "b".repeat(64)), old, next, findings));
        assertThrows(IllegalArgumentException.class, () -> ContractCheck.accepted(row + row, old, next, findings));
        Finding hard = new Finding(Severity.ERROR, "ID_TOMBSTONE_CHANGED", "message-id/1", "old", "new");
        assertThrows(IllegalArgumentException.class, () -> ContractCheck.accepted(old.fingerprint() + "\t" + next.fingerprint()
                + "\t" + hard.id() + "\t不能豁免", old, next, List.of(hard)));
    }
}
