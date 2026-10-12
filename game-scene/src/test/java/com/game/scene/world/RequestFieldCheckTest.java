package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.CurrencyComp;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.MyNestedMessage;
import com.game.proto.MyProtoMessage;
import com.game.proto.ProtoFieldCheckerTestPB;
import com.game.proto.ProtoFieldCheckerTestSubPB;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** 用例对照基线 proto_field_checker 的测试消息（MyProtoMessage / ProtoFieldCheckerTestPB 随契约同步来）。 */
class RequestFieldCheckTest {

    @Test
    void 有符号整数为负_单值_repeated_嵌套子消息都拦() {
        assertThat(RequestFieldCheck.violation(MyProtoMessage.newBuilder().setSingleInt32Field(-1).build())).isNotNull();
        assertThat(RequestFieldCheck.violation(MyProtoMessage.newBuilder().setSingleInt64Field(-1).build())).isNotNull();
        assertThat(RequestFieldCheck.violation(MyProtoMessage.newBuilder().addRepeatedInt32Field(3).addRepeatedInt32Field(-2)
                .build())).contains("repeated_int32_field");
        assertThat(RequestFieldCheck.violation(MyProtoMessage.newBuilder().addRepeatedInt64Field(-9).build())).isNotNull();
        assertThat(RequestFieldCheck.violation(MyProtoMessage.newBuilder()
                .setNestedMessage(MyNestedMessage.newBuilder().setNestedInt64Field(-5)).build()))
                .contains("nested_message.nested_int64_field");
        assertThat(RequestFieldCheck.violation(GmAddCurrencyRequest.newBuilder().setAmount(-5).build())).isNotNull();
    }

    @Test
    void 无符号字段高位置位不算负数_零与正数放行() {
        assertThat(RequestFieldCheck.violation(GmAddCurrencyRequest.newBuilder().setCurrencyType(-1).setAmount(0).build()))
                .as("uint32 0xFFFFFFFF 在 Java 里读成 -1，但不是有符号字段").isNull();
        assertThat(RequestFieldCheck.violation(CurrencyComp.newBuilder().addValues(-1L).build())).isNull();
        assertThat(RequestFieldCheck.violation(MyProtoMessage.newBuilder().setSingleInt32Field(7)
                .setNestedMessage(MyNestedMessage.getDefaultInstance()).build())).isNull();
    }

    @Test
    void repeated元素数超过20拦_恰好20放行_递归进非repeated子消息() {
        assertThat(RequestFieldCheck.violation(CurrencyComp.newBuilder()
                .addAllValues(Collections.nCopies(RequestFieldCheck.MAX_REPEATED, 1L)).build())).isNull();
        assertThat(RequestFieldCheck.violation(CurrencyComp.newBuilder()
                .addAllValues(Collections.nCopies(RequestFieldCheck.MAX_REPEATED + 1, 1L)).build())).contains("values");
        assertThat(RequestFieldCheck.violation(ProtoFieldCheckerTestPB.newBuilder().setSubMessage(
                ProtoFieldCheckerTestSubPB.newBuilder().addAllItems(Collections.nCopies(21, "x"))).build()))
                .contains("sub_message.items");
        assertThat(RequestFieldCheck.violation(GetCurrencyListResponse.newBuilder().setCurrency(CurrencyComp.newBuilder()
                .addAllBlockedTypes(Collections.nCopies(21, 1))).build())).contains("currency.blocked_types");
    }
}
