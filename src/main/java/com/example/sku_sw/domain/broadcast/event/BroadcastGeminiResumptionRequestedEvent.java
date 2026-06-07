package com.example.sku_sw.domain.broadcast.event;

import lombok.Builder;
import lombok.Getter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.atomic.AtomicBoolean;

@Getter
@Builder
public class BroadcastGeminiResumptionRequestedEvent {

    private final WebSocketSession closedSession;
    private final CloseStatus closeStatus;
    private final Runnable fallbackCleanup;
    @Builder.Default
    private final AtomicBoolean resumed = new AtomicBoolean(false);
    @Builder.Default
    private final AtomicBoolean fallbackExecuted = new AtomicBoolean(false);
}
