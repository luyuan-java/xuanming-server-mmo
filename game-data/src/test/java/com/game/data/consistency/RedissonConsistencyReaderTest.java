package com.game.data.consistency;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedissonConsistencyReaderTest {
    @Test void scriptsHaveOnlyExplicitReadCommandsAndBoundedReply(){
        var command=Pattern.compile("redis\\.call\\('([A-Z]+)'");
        for(String script:List.of(RedissonConsistencyReader.SCAN,RedissonConsistencyReader.SSCAN,RedissonConsistencyReader.TYPE)) {
            var matcher=command.matcher(script);int count=0;
            while(matcher.find()){count++;assertThat(Set.of("SCAN","SSCAN","TYPE")).contains(matcher.group(1));}
            assertThat(count).isPositive();
        }
        assertThat(RedissonConsistencyReader.SCAN).contains("#r[2]>","#v>192");
        assertThat(RedissonConsistencyReader.SSCAN).contains("#r[2]>","#v>32");
    }
    @Test void unsupportedTopologyCannotBeClaimedComplete(){
        var client=mock(RedissonClient.class);var config=new Config();config.useClusterServers().addNodeAddress("redis://127.0.0.1:1");
        when(client.getConfig()).thenReturn(config);
        assertThat(new RedissonConsistencyReader(client,new RedisKeys(GameNamespace.of("nstest"))).completeTopology()).isFalse();
        verify(client).getConfig();
        verifyNoMoreInteractions(client);
    }
    @Test void expiredBudgetSendsNoRedisCommand(){
        var client=mock(RedissonClient.class);var budget=mock(ConsistencyBudget.class);
        when(budget.requestMillis()).thenThrow(new ConsistencyBudget.Failure("budget_exhausted"));
        var reader=new RedissonConsistencyReader(client,new RedisKeys(GameNamespace.of("nstest")));
        assertThatThrownBy(()->reader.scanRankKeys("0",1,budget)).hasMessage("budget_exhausted");
        verifyNoInteractions(client);
    }
}
