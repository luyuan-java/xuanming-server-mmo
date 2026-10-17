package com.game.match.global;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.*;
import com.game.match.support.MatchTips;
import com.game.proto.match.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.function.Supplier;

/** 两个客户端入口无论是否启用全球匹配都注册；单区旧配置明确回 required=false。 */
public final class ProbeHandlers {
    private ProbeHandlers(){}
    public static final class Plan implements MatchMethodHandler{
        private final Supplier<GlobalMatchService> global;
        public Plan(Supplier<GlobalMatchService> global){this.global=global;}
        public String method(){return MatchMethods.GET_NETWORK_PROBE_PLAN;}
        public Reply handle(SessionContext session,ByteString body,Deadline deadline)throws InvalidProtocolBufferException{
            GetNetworkProbePlanRequest.parseFrom(body);var service=global.get();
            return Reply.body(service==null?GetNetworkProbePlanResponse.getDefaultInstance():service.plan(session,deadline));}
        public Reply onOverload(){return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);}
    }
    public static final class Commit implements MatchMethodHandler{
        private final Supplier<GlobalMatchService> global;
        public Commit(Supplier<GlobalMatchService> global){this.global=global;}
        public String method(){return MatchMethods.COMMIT_NETWORK_PROBE;}
        public Reply handle(SessionContext session,ByteString body,Deadline deadline)throws InvalidProtocolBufferException{
            var request=CommitNetworkProbeRequest.parseFrom(body);var service=global.get();
            if(service==null)return onOverload();
            try{return Reply.body(service.commit(session,request,deadline));}catch(Exception e){return onOverload();}}
        public Reply onOverload(){return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);}
    }
}
