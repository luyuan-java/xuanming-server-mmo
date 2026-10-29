package com.game.data.merge.retire;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** 运维审批的外部升级清单；摘要绑定原字节，不能把一份清单挪用于另一命名空间或区。 */
public record RetirementDeployment(String namespace,int sourceZone,String releaseCommit,Set<String> nodeTypes,String digest) {
    public RetirementDeployment { nodeTypes=Set.copyOf(nodeTypes); }
    public static RetirementDeployment read(Path path,String approvedDigest)throws IOException {
        byte[] bytes;
        try(var input=Files.newInputStream(path)){bytes=input.readNBytes(65_537);}
        if(bytes.length>65_536)throw new IllegalArgumentException("部署证据超过64KiB");
        return parse(bytes,approvedDigest);
    }
    public static RetirementDeployment parse(byte[] bytes,String approvedDigest)throws IOException {
        if(approvedDigest==null||!approvedDigest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("必须指定已审部署证据SHA-256");
        String digest;
        try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        if(!MessageDigest.isEqual(digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),approvedDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("部署证据摘要不符");
        var props=new Properties();props.load(new ByteArrayInputStream(bytes));
        Set<String> expected=Set.of("namespace","source-zone","release-commit","directory-protocol","node-types","old-writers-stopped");
        if(!props.stringPropertyNames().equals(expected))throw new IllegalArgumentException("部署证据字段不完整或含未知项");
        String ns=props.getProperty("namespace"),commit=props.getProperty("release-commit");
        if(!ns.matches("[a-z][a-z0-9]{0,27}")||!commit.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("命名空间或发布commit无效");
        if(!"2".equals(props.getProperty("directory-protocol"))||!"true".equals(props.getProperty("old-writers-stopped")))
            throw new IllegalArgumentException("必须证明全部旧写者已退出且部署支持目录v2");
        int zone=Integer.parseUnsignedInt(props.getProperty("source-zone"));if(zone==0)throw new IllegalArgumentException("全局zone0不可退休");
        Set<String> types=new TreeSet<>();for(String type:props.getProperty("node-types").split(",",-1)){
            if(!type.matches("[a-z][a-z0-9-]{0,63}")||!types.add(type))throw new IllegalArgumentException("目录类型无效或重复");
        }
        return new RetirementDeployment(ns,zone,commit,types,digest);
    }
}
