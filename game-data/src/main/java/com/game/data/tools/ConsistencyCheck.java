package com.game.data.tools;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.game.common.GameNamespace;
import com.game.data.consistency.ConsistencyReport;
import com.game.data.consistency.ConsistencyService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** HTTP薄客户端，沿既有PropertiesLauncher启动。只读GAME_ADMIN_TOKEN，不接触SQL/Redis凭据。 */
public final class ConsistencyCheck {
    public static void main(String[] args){System.exit(run(args));}
    static int run(String[] args) {
        final Map<String,String> options;
        final URI uri;
        final String body;
        try {
            options=parse(args);
            URI base=URI.create(options.get("url"));
            if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
                    ||base.getRawPath()!=null&&!base.getRawPath().isEmpty()&&!base.getRawPath().equals("/"))
                throw new IllegalArgumentException();
            boolean local=List.of("localhost","127.0.0.1","[::1]","::1").contains(base.getHost());
            if(!base.getScheme().equals("https")&&!(local&&base.getScheme().equals("http")))throw new IllegalArgumentException();
            uri=base.resolve("/admin/consistency/check");
            String operator=options.get("operator");
            if(operator==null||!operator.matches("[\\x21-\\x7e]{1,64}"))throw new IllegalArgumentException();
            List<String> fields=new ArrayList<>();
            String checks=options.get("checks");
            if(checks!=null) {
                if(!checks.matches("[a-z_.]+(?:,[a-z_.]+)*"))throw new IllegalArgumentException();
                var ids=List.of(checks.split(","));
                if(!ConsistencyService.CATALOG.containsAll(ids)||new java.util.HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException();
                fields.add("\"checks\":[\""+checks.replace(",","\",\"")+"\"]");
            }
            String fail=options.getOrDefault("fail-on","block");
            if(!fail.equals("block")&&!fail.equals("warn"))throw new IllegalArgumentException();
            fields.add("\"failOnWarn\":"+fail.equals("warn"));
            body="{"+String.join(",",fields)+"}";
        } catch(RuntimeException error) {
            System.err.println("用法: -Dloader.main=com.game.data.tools.ConsistencyCheck ...PropertiesLauncher --url http://127.0.0.1:18106 --operator NAME [--checks id,id] [--fail-on warn|block]");
            return 2;
        }
        String token=System.getenv("GAME_ADMIN_TOKEN");
        if(token==null||token.isBlank()){System.err.println("未配置GAME_ADMIN_TOKEN");return 2;}
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(16);
        HttpClient client=null;
        CompletableFuture<HttpResponse<byte[]>> responseFuture=null;
        var subscriber=new BodyControl();
        try {
            client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(16))
                    .header("Content-Type","application/json").header("X-Game-Admin-Token",token)
                    .header("X-Game-Operator",options.get("operator"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            if(System.nanoTime()>=deadline)throw new java.util.concurrent.TimeoutException();
            responseFuture=client.sendAsync(request,ignored->{var limited=new LimitedBody(256*1024);subscriber.install(limited);return limited;});
            HttpResponse<byte[]> response=await(responseFuture,subscriber,deadline);
            if(response.statusCode()!=200){System.err.println("巡检无完整响应，HTTP="+response.statusCode());return 3;}
            String exit=response.headers().firstValue("X-Game-Consistency-Exit").orElse("");
            if(!exit.matches("[013]")){System.err.println("巡检响应缺完成协议");return 3;}
            List<String> expected=options.containsKey("checks")?List.of(options.get("checks").split(",")):ConsistencyService.CORE;
            String decoded=validate(response.body(),Integer.parseInt(exit),options.getOrDefault("fail-on","block").equals("warn"),expected);
            if(System.nanoTime()>=deadline)throw new java.util.concurrent.TimeoutException();
            System.out.println(decoded);
            return Integer.parseInt(exit);
        } catch(InterruptedException error){Thread.currentThread().interrupt();System.err.println("巡检中断，结果未知");return 3;}
        catch(Exception error){System.err.println("巡检响应不可用，结果未知");return 3;}
        finally {
            if(responseFuture!=null&&!responseFuture.isDone())responseFuture.cancel(true);
            subscriber.cancel();
            if(client!=null) {
                client.shutdownNow();
                long remaining=Math.min(TimeUnit.SECONDS.toNanos(2),deadline-System.nanoTime());
                if(remaining>0)try{client.awaitTermination(Duration.ofNanos(remaining));}
                catch(InterruptedException error){Thread.currentThread().interrupt();}
            }
        }
    }
    static <T>T await(CompletableFuture<T> future,BodyControl body,long deadline)throws Exception{
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new java.util.concurrent.TimeoutException();
            return future.get(remaining,TimeUnit.NANOSECONDS);
        }catch(Exception error){future.cancel(true);body.cancel();throw error;}
    }
    /** 完整严格UTF-8 + JSON + 完成/覆盖协议；不能仅凭HTTP状态和退出头返回0。 */
    static String validate(byte[] bytes,int headerExit,boolean failOnWarn,List<String> expected)throws Exception{
        String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        var json=new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        ConsistencyReport report=json.readValue(text,ConsistencyReport.class);
        if(report==null||!report.finalized()||!GameNamespace.isValid(report.namespace())||report.startedAt()==null||report.finishedAt()==null
                ||report.startedAt().isAfter(report.finishedAt())||report.observation()==null||!report.requested().equals(expected)
                ||report.results().size()!=expected.size()||!report.results().stream().map(ConsistencyReport.Result::check).toList().equals(expected))
            throw new IllegalStateException("incomplete_report");
        java.util.UUID.fromString(report.runId());
        for(var result:report.results()) {
            if(result.status()==null||result.coverage()==null||result.scannedCount()<0||result.confirmedBad()<0||result.transientCount()<0
                    ||result.countExact()!=(result.coverage()==ConsistencyReport.Coverage.COMPLETE)
                    ||result.status()==ConsistencyReport.Status.PASS&&(result.coverage()!=ConsistencyReport.Coverage.COMPLETE||result.confirmedBad()!=0)
                    ||result.status()==ConsistencyReport.Status.UNKNOWN&&result.coverage()==ConsistencyReport.Coverage.COMPLETE
                    ||(result.status()==ConsistencyReport.Status.WARN||result.status()==ConsistencyReport.Status.BLOCK)&&result.confirmedBad()==0)
                throw new IllegalStateException("invalid_result");
        }
        if(headerExit!=report.exitCode()||headerExit!=ConsistencyReport.exitCode(report.results(),failOnWarn))throw new IllegalStateException("exit_mismatch");
        return text;
    }
    static Map<String,String> parse(String[] args) {
        var result=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) {
            if(i+1>=args.length||!List.of("--url","--operator","--checks","--fail-on").contains(args[i]))throw new IllegalArgumentException();
            if(result.put(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException();
        }
        if(!result.containsKey("url")||!result.containsKey("operator"))throw new IllegalArgumentException();
        return result;
    }
    /** 防止总截止先到、响应头随后才创建订阅者的竞态。 */
    static final class BodyControl {
        private LimitedBody body;private boolean cancelled;
        synchronized void install(LimitedBody value){body=value;if(cancelled)value.cancel();}
        synchronized void cancel(){cancelled=true;if(body!=null)body.cancel();}
    }
    /** 总截止由await覆盖到onComplete；取消会终止订阅，内存读取也有独立上限。 */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean cancelled;
        LimitedBody(int limit){this.limit=limit;}
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public synchronized void onSubscribe(Flow.Subscription value){subscription=value;if(cancelled)value.cancel();else value.request(1);}
        synchronized void cancel(){cancelled=true;if(subscription!=null)subscription.cancel();result.cancel(true);}
        @Override public synchronized void onNext(List<ByteBuffer> buffers){
            if(cancelled||result.isDone())return;
            for(ByteBuffer buffer:buffers){
                if(buffer.remaining()>limit-data.size()){subscription.cancel();result.completeExceptionally(new IllegalStateException("response_limit"));return;}
                byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);data.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(data.toByteArray());}
    }
}
