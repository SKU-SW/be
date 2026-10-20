package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiResumptionReadyEvent;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.chat.dto.ChzzkChatMessageDto;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiRequestServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private BroadcastGeminiRequestService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastGeminiRequestService(objectMapper, sessionRegistry, taskScheduler, applicationEventPublisher);
        ReflectionTestUtils.setField(service, "liveFirstResumptionTimeoutMs", 3000L);
    }


    // ──────────────────────────────────────────────
    // processClientMessage — generating streamer path (unchanged behavior)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("getFirstResumptionEvent - request-flight를 증가시키고 raw clientContent 메시지를 전송한다")
    void getFirstResumptionEvent_성공() throws Exception {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        WebSocketSession geminiSession = mock(WebSocketSession.class);
        com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler handler = mock(com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendControlToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(bundle.getGeminiHandler()).willReturn(handler);
        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        // when
        service.getFirstResumptionEvent(broadcastStreamId, generation);

        // then
        verify(handler, times(1)).markFirstResumptionEventStarted();
        verify(bundle, times(1)).incrementRequestFlight();
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(java.time.Instant.class));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession).sendMessage(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.has("clientContent")).isTrue();
        assertThat(payload.get("clientContent").get("turnComplete").asBoolean()).isTrue();
        assertThat(payload.get("clientContent").get("turns").get(0).get("parts").get(0).get("text").asText())
                .contains("FIRST_RESUMPTION_EVENT");
    }

    @Test
    @DisplayName("getFirstResumptionEvent - 전송 실패 시 request-flight를 감소시키고 CustomException을 던진다")
    void getFirstResumptionEvent_실패시_requestFlight_decrement() throws Exception {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        WebSocketSession geminiSession = mock(WebSocketSession.class);
        com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler handler = mock(com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendControlToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(bundle.getGeminiHandler()).willReturn(handler);
        given(bundle.getRequestFlightCountValue()).willReturn(1);
        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);
        doThrow(new RuntimeException("send failed")).when(geminiSession).sendMessage(any(TextMessage.class));

        // when & then
        assertThatThrownBy(() -> service.getFirstResumptionEvent(broadcastStreamId, generation))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BroadcastErrorCode.GEMINI_RESPONSE_FAILED);

        verify(handler, times(1)).markFirstResumptionEventStarted();
        verify(handler, times(1)).clearFirstResumptionEventInProgress();
        verify(bundle, times(1)).incrementRequestFlight();
        verify(bundle, times(1)).decrementRequestFlight();
        verify(applicationEventPublisher, times(1)).publishEvent(any(BroadcastGeminiResumptionReadyEvent.class));
    }

    @Test
    @DisplayName("getFirstResumptionEvent - timeout 시 ready 이벤트를 발행한다")
    void getFirstResumptionEvent_timeout시_ready_이벤트_발행() throws Exception {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        WebSocketSession geminiSession = mock(WebSocketSession.class);
        com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler handler =
                mock(com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendControlToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(bundle.getGeminiHandler()).willReturn(handler);
        given(bundle.getRequestFlightCountValue()).willReturn(1);
        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);

        // when
        service.getFirstResumptionEvent(broadcastStreamId, generation);
        verify(taskScheduler, times(1)).schedule(timeoutCaptor.capture(), any(java.time.Instant.class));
        timeoutCaptor.getValue().run();

        // then
        verify(handler, times(1)).clearFirstResumptionEventInProgress();
        verify(handler, times(1)).clearAccumulator();
        verify(bundle, times(1)).decrementRequestFlight();
        verify(applicationEventPublisher, times(1)).publishEvent(any(BroadcastGeminiResumptionReadyEvent.class));
    }
}
