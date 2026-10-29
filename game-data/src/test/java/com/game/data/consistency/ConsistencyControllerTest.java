package com.game.data.consistency;

import com.game.data.admin.AdminAuthFilter;
import com.game.data.metrics.DataMetrics;
import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsistencyControllerTest {
    @Test void headersAloneCannotForgeTheFilterProof(){
        var service=mock(ConsistencyService.class);var controller=new ConsistencyController(service);
        var request=new MockHttpServletRequest();request.addHeader(AdminAuthFilter.OPERATOR_HEADER,"tester");request.addHeader(AdminAuthFilter.TOKEN_HEADER,"token");
        assertThatThrownBy(()->controller.check(null,request)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
    @Test void authenticatedOneShotReturnsExplicitUnknownExitHeader()throws Exception{
        var ns=GameNamespace.of("nstest");var controller=new ConsistencyController(new ConsistencyService(null,null,ns,new RedisKeys(ns)));
        var filter=new AdminAuthFilter("test-token",mock(DataMetrics.class));
        var request=new MockHttpServletRequest("POST",ConsistencyController.PATH);request.setServletPath(ConsistencyController.PATH);
        request.addHeader(AdminAuthFilter.OPERATOR_HEADER,"tester");request.addHeader(AdminAuthFilter.TOKEN_HEADER,"test-token");
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
            var response=controller.check(null,request);
            assertThat(response.getHeaders().getFirst("X-Game-Consistency-Exit")).isEqualTo("3");
            assertThat(response.getBody().finalized()).isTrue();
            assertThat(response.getBody().results()).allMatch(result->result.status()==ConsistencyReport.Status.UNKNOWN);
        });
    }
}
