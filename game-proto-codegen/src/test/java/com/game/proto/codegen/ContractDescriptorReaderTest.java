package com.game.proto.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.codegen.ContractDescriptorReader.ContractSchemaException;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceOptions;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractDescriptorReaderTest {

    @Test
    void 号表支持墓碑并拒绝重复与畸形行() throws Exception {
        Map<Integer, String> parsed = ContractDescriptorReader.parseIdTable(
                utf8("# comment\n0=A\n1=reserved:OldA\n2=OldA\n"), "message_id.txt");
        assertThat(parsed).containsExactly(Map.entry(0, "A"), Map.entry(2, "OldA"));

        assertThatThrownBy(() -> ContractDescriptorReader.parseIdTable(utf8("1=A\n1=reserved:B\n"), "t"))
                .isInstanceOf(ContractSchemaException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> ContractDescriptorReader.parseIdTable(utf8("1=reserved:\n"), "t"))
                .isInstanceOf(ContractSchemaException.class).hasMessageContaining("墓碑缺原名");
        assertThatThrownBy(() -> ContractDescriptorReader.parseIdTable(utf8("1=A\n2=A\n"), "t"))
                .isInstanceOf(ContractSchemaException.class).hasMessageContaining("键名重复");
        assertThatThrownBy(() -> ContractDescriptorReader.parseIdTable(utf8("badline\n"), "t"))
                .isInstanceOf(ContractSchemaException.class).hasMessageContaining("格式错误");
    }

    @Test
    void 常量与Handler方法命名规则稳定且避开Java关键字() {
        assertThat(ContractDescriptorReader.toUpperSnake("MatchServiceJoinQueue")).isEqualTo("MATCH_SERVICE_JOIN_QUEUE");
        assertThat(ContractDescriptorReader.toUpperSnake("S2CEnterScene")).isEqualTo("S2C_ENTER_SCENE");
        assertThat(ContractDescriptorReader.toHandlerMethodName("JoinQueue")).isEqualTo("joinQueue");
        assertThat(ContractDescriptorReader.toHandlerMethodName("Notify")).isEqualTo("notify_");
        assertThat(ContractDescriptorReader.toHandlerMethodName("Default")).isEqualTo("default_");
    }

    @Test
    void 客户端协议服务无法解析节点类型时编译期拒绝_不依赖proto目录() {
        FileDescriptorProto nodeProto = FileDescriptorProto.newBuilder()
                .setName("proto/common/base/node.proto")
                .setPackage("common.base")
                .addEnumType(EnumDescriptorProto.newBuilder().setName("eNodeType")
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("UnknownNodeService").setNumber(0))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("SceneNodeService").setNumber(3)))
                .build();
        FileDescriptorProto optProto = FileDescriptorProto.newBuilder()
                .setName("proto/db/proto_option.proto")
                .addExtension(FieldDescriptorProto.newBuilder()
                        .setName("OptionIsClientProtocolService").setNumber(400001)
                        .setExtendee(".google.protobuf.ServiceOptions").setType(FieldDescriptorProto.Type.TYPE_BOOL))
                .build();
        ServiceOptions clientOpt = ServiceOptions.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(400001, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build())
                .build();
        FileDescriptorProto badClientSvc = FileDescriptorProto.newBuilder()
                .setName("proto/scene/mystery.proto") // 刻意放在 scene 目录，验证不靠目录猜节点
                .setPackage("mysterypb")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.game.proto.mystery").setJavaMultipleFiles(true))
                .addMessageType(DescriptorProto.newBuilder().setName("Req"))
                .addMessageType(DescriptorProto.newBuilder().setName("Resp"))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("MysteryClient")
                        .setOptions(clientOpt)
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Ping").setInputType(".mysterypb.Req").setOutputType(".mysterypb.Resp")))
                .build();
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(nodeProto).addFile(optProto).addFile(badClientSvc).build();

        assertThatThrownBy(() -> ContractDescriptorReader.read(set, utf8("1=MysteryClientPing\n"), utf8("")))
                .isInstanceOf(ContractSchemaException.class)
                .hasMessageContaining("MysteryClient")
                .hasMessageContaining("不依赖 proto 目录");
    }

    private static ByteArrayInputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
