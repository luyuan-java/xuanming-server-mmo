package com.game.payment.notify;

import com.game.payment.PaymentMetrics;
import com.game.payment.channel.ChannelTypes.AckKind;
import com.game.payment.channel.ChannelTypes.AckResponse;
import com.game.payment.channel.ChannelTypes.ChannelException;
import com.game.payment.channel.ChannelTypes.HttpNotificationRequest;
import com.game.payment.channel.ChannelTypes.Notification;
import com.game.payment.channel.PaymentChannel;
import com.game.payment.core.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付渠道异步回调与配置握手 HTTP 入口：{@code GET/POST /notify/{channel}}（对齐 Go {@code payment/internal/notify}）。
 * 验签并只写本库后立即按渠道约定格式应答，不受商城可用性阻塞。
 */
@RestController
public final class PaymentNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotifyController.class);
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final PaymentService service;
    private final PaymentMetrics metrics;

    public PaymentNotifyController(PaymentService service, PaymentMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping("/notify/{channel}")
    public ResponseEntity<String> handshake(@PathVariable("channel") String channelName, HttpServletRequest request) {
        PaymentChannel ch = service.channelByName(channelName);
        if (ch == null) {
            return ResponseEntity.notFound().build();
        }
        if (ch instanceof PaymentChannel.Handshaker handshaker) {
            AckResponse res = handshaker.handshake(toHttpRequest(request, new byte[0]));
            if (res != null) {
                return toEntity(res);
            }
        }
        return ResponseEntity.status(405).build();
    }

    @PostMapping("/notify/{channel}")
    public ResponseEntity<String> notify(@PathVariable("channel") String channelName, HttpServletRequest request) {
        PaymentChannel ch = service.channelByName(channelName);
        if (ch == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            metrics.countNotification(ch.name(), "rejected");
            return toEntity(ch.ack(AckKind.REJECTED));
        }
        if (body.length > MAX_BODY_BYTES) {
            metrics.countNotification(ch.name(), "rejected");
            return toEntity(ch.ack(AckKind.REJECTED));
        }
        Notification note;
        try {
            note = ch.parseNotification(toHttpRequest(request, body));
        } catch (ChannelException e) {
            if (e.kind() == ChannelException.Kind.TEMP_UNAVAILABLE) {
                log.error("[payment] verifying {} notification temporarily unavailable: {}", ch.name(), e.getMessage());
                metrics.countNotification(ch.name(), "retry");
                return toEntity(ch.ack(AckKind.RETRY));
            }
            if (e.kind() != ChannelException.Kind.BAD_SIGNATURE) {
                log.error("[payment] parsing {} notification failed: {}", ch.name(), e.getMessage());
            }
            metrics.countNotification(ch.name(), "rejected");
            return toEntity(ch.ack(AckKind.REJECTED));
        }
        try {
            service.handleNotification(ch, note);
        } catch (ChannelException | RuntimeException e) {
            log.error("[payment] handling {} notification failed, asking channel to retry: {}", ch.name(), e.getMessage());
            metrics.countNotification(ch.name(), "retry");
            return toEntity(ch.ack(AckKind.RETRY));
        }
        metrics.countNotification(ch.name(), "handled");
        return toEntity(ch.ack(AckKind.OK));
    }

    private static HttpNotificationRequest toHttpRequest(HttpServletRequest request, byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String n = names.nextElement();
            headers.put(n, request.getHeader(n));
        }
        Map<String, String> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) {
                query.put(k, v[0]);
            }
        });
        return new HttpNotificationRequest(request.getMethod(), headers, query, body);
    }

    private static ResponseEntity<String> toEntity(AckResponse ack) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(ack.status());
        if (ack.contentType() != null && !ack.contentType().isEmpty()) {
            b.contentType(MediaType.parseMediaType(ack.contentType()));
        }
        return ack.body() == null || ack.body().isEmpty() ? b.build() : b.body(ack.body());
    }
}
