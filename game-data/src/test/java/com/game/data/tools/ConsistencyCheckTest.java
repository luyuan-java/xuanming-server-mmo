package com.game.data.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.game.data.consistency.ConsistencyReport;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsistencyCheckTest {
    @Test void deadlineAfterHeadersCancelsNeverEndingBodyAndFuture(){
        var body=new ConsistencyCheck.LimitedBody(32);var control=new ConsistencyCheck.BodyControl();control.install(body);
        var subscription=mock(Flow.Subscription.class);body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap("{\"partial\":".getBytes(StandardCharsets.UTF_8))));
        var response=new CompletableFuture<Object>();
        assertThatThrownBy(()->ConsistencyCheck.await(response,control,System.nanoTime()-1)).isInstanceOf(TimeoutException.class);
        assertThat(response.isCancelled()).isTrue();assertThat(body.getBody().toCompletableFuture().isCancelled()).isTrue();
        verify(subscription).cancel();
    }
    @Test void timeoutBeforeHeadersCancelsLateSubscriber(){
        var control=new ConsistencyCheck.BodyControl();control.cancel();
        var body=new ConsistencyCheck.LimitedBody(32);control.install(body);
        var subscription=mock(Flow.Subscription.class);body.onSubscribe(subscription);
        verify(subscription).cancel();verify(subscription,never()).request(anyLong());
    }
    @Test void responseSizeLimitCancelsSubscription(){
        var body=new ConsistencyCheck.LimitedBody(2);var subscription=mock(Flow.Subscription.class);body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();verify(subscription).cancel();
    }
    @Test void emptyTruncatedTrailingAndInvalidUtf8NeverPass()throws Exception{
        byte[] valid=valid();String text=new String(valid,StandardCharsets.UTF_8);
        for(String bad:List.of("",text.substring(0,text.length()-1),text+"{}"))
            assertThatThrownBy(()->ConsistencyCheck.validate(bad.getBytes(StandardCharsets.UTF_8),0,false,List.of("route.meta"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->ConsistencyCheck.validate(new byte[]{(byte)0xc3},0,false,List.of("route.meta")))
                .isInstanceOf(CharacterCodingException.class);
    }
    @Test void validReportNeedsMatchingExitAndRequestedScope()throws Exception{
        byte[] bytes=valid();
        assertThat(ConsistencyCheck.validate(bytes,0,false,List.of("route.meta"))).contains("finalized");
        assertThatThrownBy(()->ConsistencyCheck.validate(bytes,3,false,List.of("route.meta"))).hasMessage("exit_mismatch");
        assertThatThrownBy(()->ConsistencyCheck.validate(bytes,0,false,List.of("zone.player"))).hasMessage("incomplete_report");
    }
    @Test void missingFinalizedOrPrimitiveNullCannotBecomeDefaultSuccess()throws Exception{
        String json=new String(valid(),StandardCharsets.UTF_8);
        for(String changed:List.of(json.replace("\"finalized\":true,",""),json.replace("\"finalized\":true","\"finalized\":null")))
            assertThatThrownBy(()->ConsistencyCheck.validate(changed.getBytes(StandardCharsets.UTF_8),0,false,List.of("route.meta"))).isInstanceOf(Exception.class);
    }
    private static byte[] valid()throws Exception{
        var result=new ConsistencyReport.Result("route.meta",ConsistencyReport.Status.PASS,ConsistencyReport.Coverage.COMPLETE,true,
                1,0,0,List.of(),List.of(),null,null,1L,2L);
        var report=new ConsistencyReport(UUID.randomUUID().toString(),"nstest",Instant.EPOCH,Instant.EPOCH.plusSeconds(1),true,
                "bounded",List.of("route.meta"),List.of("route.meta"),List.of(),List.of(result),0);
        return new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsBytes(report);
    }
}
