package com.game.discovery.serviceauth;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 邮件/榜控制面的唯一签名规则，与Go shared/serviceauth相同；密钥只由工厂从固定环境变量读取。 */
public final class ServiceCallSignatures {
    private static final HexFormat HEX=HexFormat.of();
    private static final long SKEW_MS=300_000L;
    private final Map<String,String> secrets;
    private final LongSupplier clock;
    public ServiceCallSignatures(Map<String,String> secrets,LongSupplier clock){
        if(secrets==null||secrets.isEmpty()||clock==null)throw new IllegalArgumentException("内部角色签名配置缺失");
        secrets.forEach((caller,secret)->validate(caller,secret));this.secrets=Map.copyOf(secrets);this.clock=clock;
    }
    private static void validate(String caller,String secret){if(caller==null||!caller.matches("[a-z][a-z0-9_-]{0,31}")||secret==null||secret.strip().getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("内部调用密钥未配置或不足32字节");}
    public static String digest(Message request){
        if(request==null)throw new IllegalArgumentException("内部请求缺失");var builder=request.toBuilder();var auth=builder.getDescriptorForType().findFieldByName("auth");if(auth!=null)builder.clearField(auth);Message clean=builder.build();byte[] bytes=new byte[clean.getSerializedSize()];var output=CodedOutputStream.newInstance(bytes);output.useDeterministicSerialization();try{clean.writeTo(output);output.checkNoSpaceLeft();return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(IOException|GeneralSecurityException failure){throw new IllegalStateException("内部请求摘要失败",failure);}
    }
    private static String canonical(String method,String caller,long stamp,String digest){if(method==null||!method.startsWith("/")||method.indexOf('\n')>=0||method.indexOf('\r')>=0||stamp<=0)throw new IllegalArgumentException("内部请求签名参数无效");return "v1\n"+method+"\n"+caller+"\n"+stamp+"\n"+digest;}
    private static byte[] mac(String text,String secret){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.strip().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException failure){throw new IllegalStateException("内部请求签名失败",failure);}}
    @SuppressWarnings("unchecked")
    public static <T extends Message>T sign(String method,T request,String caller,String secret,long stamp){
        validate(caller,secret);String canonical=canonical(method,caller,stamp,digest(request));var builder=request.toBuilder();var field=builder.getDescriptorForType().findFieldByName("auth");if(field==null||field.getJavaType()!=Descriptors.FieldDescriptor.JavaType.MESSAGE)throw new IllegalArgumentException("内部请求无auth");var proof=builder.newBuilderForField(field);set(proof,"caller",caller);set(proof,"timestamp_ms",stamp);set(proof,"signature_hex",HEX.formatHex(mac(canonical,secret)));builder.setField(field,proof.build());return (T)builder.build();
    }
    private static void set(Message.Builder builder,String name,Object value){var field=builder.getDescriptorForType().findFieldByName(name);if(field==null)throw new IllegalArgumentException("内部凭据字段缺失");builder.setField(field,value);}
    public String verify(String method,Message request){
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("内部请求已取消");var field=request.getDescriptorForType().findFieldByName("auth");if(field==null)throw new SecurityException("内部调用无权访问");var proof=(Message)request.getField(field);String caller=(String)proof.getField(proof.getDescriptorForType().findFieldByName("caller"));Object rawStamp=proof.getField(proof.getDescriptorForType().findFieldByName("timestamp_ms"));String signature=(String)proof.getField(proof.getDescriptorForType().findFieldByName("signature_hex"));long stamp=(Long)rawStamp;String secret=secrets.get(caller);long now=clock.getAsLong();if(secret==null||stamp<=0||now<0||stamp>now+SKEW_MS||now-stamp>SKEW_MS||!signature.matches("[0-9a-f]{64}"))throw new SecurityException("内部调用无权访问");byte[] expected=mac(canonical(method,caller,stamp,digest(request)),secret);if(!MessageDigest.isEqual(expected,HEX.parseHex(signature)))throw new SecurityException("内部调用无权访问");if(Thread.currentThread().isInterrupted())throw new IllegalStateException("内部请求已取消");return caller;
    }
}
