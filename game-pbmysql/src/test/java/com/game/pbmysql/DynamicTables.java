package com.game.pbmysql;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.UnknownFieldSet;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用：运行时拼装消息描述符（不经 protoc），用来覆盖 unknown fields 里的选项、各种非法定义。 */
final class DynamicTables {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private DynamicTables() {
    }

    static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type, FieldOptions options) {
        return field(name, number, type).toBuilder().setOptions(options).build();
    }

    /** proto3 文件里的一个消息。 */
    static Descriptor message(String name, MessageOptions options, FieldDescriptorProto... fields) {
        DescriptorProto.Builder msg = DescriptorProto.newBuilder().setName(name).setOptions(options);
        for (FieldDescriptorProto f : fields) {
            msg.addField(f);
        }
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("dyn_" + SEQ.incrementAndGet() + ".proto")
                .setPackage("dyn")
                .setSyntax("proto3")
                .addMessageType(msg)
                .build();
        try {
            return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName(name);
        } catch (DescriptorValidationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Descriptor message(String name, FieldDescriptorProto... fields) {
        return message(name, MessageOptions.getDefaultInstance(), fields);
    }

    /** 选项定义没被链接时的形态：选项只以 unknown fields 的 wire 记录存在。 */
    static UnknownFieldSet.Builder unknown() {
        return UnknownFieldSet.newBuilder();
    }

    static UnknownFieldSet.Field strings(String... values) {
        UnknownFieldSet.Field.Builder f = UnknownFieldSet.Field.newBuilder();
        for (String v : values) {
            f.addLengthDelimited(ByteString.copyFromUtf8(v));
        }
        return f.build();
    }

    static UnknownFieldSet.Field varints(long... values) {
        UnknownFieldSet.Field.Builder f = UnknownFieldSet.Field.newBuilder();
        for (long v : values) {
            f.addVarint(v);
        }
        return f.build();
    }
}
