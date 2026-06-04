package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
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

    private BroadcastGeminiRequestService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastGeminiRequestService(objectMapper, sessionRegistry);
    }

    // ──────────────────────────────────────────────
    // sendViewerChatRequest — non-generating context path
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("sendViewerChatRequest - request-flight를 증가시키지 않고 clientContent/turnComplete:false 메시지를 전송한다")
    void sendViewerChatRequest_성공() throws Exception {
        // given
        ChzzkChatMessageDto message = new ChzzkChatMessageDto(
                "stream-1", "channel-1", "테스트유저", "common_chat_user", "안녕하세요", 1000L
        );

        WebSocketSession geminiSession = mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        // when
        service.sendViewerChatRequest(message);

        // then
        // request-flight가 증가되지 않아야 함
        verify(bundle, never()).incrementRequestFlight();
        verify(bundle, never()).decrementRequestFlight();

        // 전송된 메시지 구조 검증
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.has("clientContent")).isTrue();
        assertThat(payload.has("realtimeInput")).isFalse();
        assertThat(payload.get("clientContent").get("turnComplete").asBoolean()).isFalse();
        assertThat(payload.get("clientContent").get("turns").get(0).get("role").asText()).isEqualTo("user");
        // common_chat_user -> (시청자, ...)
        assertThat(payload.get("clientContent").get("turns").get(0).get("parts").get(0).get("text").asText())
                .isEqualTo("(시청자, 테스트유저)안녕하세요");
    }

    @Test
    @DisplayName("sendViewerChatRequest - 번들이 null이면 전송 없이 로깅 후 종료한다")
    void sendViewerChatRequest_skip_when_bundle_null() {
        // given
        ChzzkChatMessageDto message = new ChzzkChatMessageDto(
                "stream-1", "channel-1", "테스트유저", null, "안녕하세요", 1000L
        );
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(null);

        // when
        service.sendViewerChatRequest(message);

        // then
        verify(sessionRegistry, times(1)).getSessionBundle("stream-1");
        verifyNoMoreInteractions(sessionRegistry);
        // 아무 전송도 일어나지 않음
    }

    @Test
    @DisplayName("sendViewerChatRequest - canSendToGemini가 false면 전송 없이 로깅 후 종료한다")
    void sendViewerChatRequest_skip_when_cannot_send() {
        // given
        ChzzkChatMessageDto message = new ChzzkChatMessageDto(
                "stream-1", "channel-1", "테스트유저", "streamer", "안녕하세요", 1000L
        );
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendToGemini()).willReturn(false);
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        // when
        service.sendViewerChatRequest(message);

        // then
        verify(bundle, never()).getGeminiSession();
        verify(bundle, never()).incrementRequestFlight();
    }

    @Test
    @DisplayName("sendViewerChatRequest - 전송 실패 시 예외를 던지지 않고 로깅만 수행한다")
    void sendViewerChatRequest_전송실패_예외미발생() throws Exception {
        // given
        ChzzkChatMessageDto message = new ChzzkChatMessageDto(
                "stream-1", "channel-1", "테스트유저", null, "메시지", 1000L
        );

        WebSocketSession geminiSession = mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        doThrow(new RuntimeException("send failed")).when(geminiSession).sendMessage(any(TextMessage.class));

        // when — 예외가 발생하지 않아야 함
        service.sendViewerChatRequest(message);

        // then
        verify(bundle, never()).decrementRequestFlight(); // flight를 증가시키지 않았으므로 감소도 없음
    }

    @Test
    @DisplayName("sendViewerChatRequest - streamer 역할 메시지는 (스트리머) 접두어로 전송한다")
    void sendViewerChatRequest_streamer_prefix() throws Exception {
        // given
        ChzzkChatMessageDto message = new ChzzkChatMessageDto(
                "stream-1", "channel-1", "스트리머님", "streamer", "오늘 방송 시작합니다", 1000L
        );

        WebSocketSession geminiSession = mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        // when
        service.sendViewerChatRequest(message);

        // then
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession).sendMessage(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        String text = payload.get("clientContent").get("turns").get(0).get("parts").get(0).get("text").asText();
        assertThat(text).isEqualTo("(스트리머)오늘 방송 시작합니다");
    }

    // ──────────────────────────────────────────────
    // processClientMessage — generating streamer path (unchanged behavior)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("processClientMessage - request-flight를 증가시키고 realtimeInput.text 메시지를 전송한다")
    void processClientMessage_성공() throws Exception {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        BroadcastCharacterRedisDto character = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("테스트 캐릭터")
                .build();
        String clientMessage = "안녕하세요 스트리머입니다";

        WebSocketSession geminiSession = mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        // when
        service.processClientMessage(broadcastStreamId, generation, character, clientMessage);

        // then
        verify(bundle, times(1)).incrementRequestFlight();

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.has("realtimeInput")).isTrue();
        assertThat(payload.has("clientContent")).isFalse();
        assertThat(payload.get("realtimeInput").get("text").asText())
                .isEqualTo("(스트리머)안녕하세요 스트리머입니다");
    }

    @Test
    @DisplayName("processClientMessage - 전송 실패 시 request-flight를 감소시키고 CustomException을 던진다")
    void processClientMessage_실패시_requestFlight_decrement() {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        BroadcastCharacterRedisDto character = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("테스트 캐릭터")
                .build();
        String clientMessage = "안녕하세요";

        WebSocketSession geminiSession = mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendToGemini()).willReturn(true);
        given(bundle.getGeminiSession()).willReturn(geminiSession);
        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        doThrow(new RuntimeException("send failed")).when(geminiSession).sendMessage(any(TextMessage.class));

        // when & then
        assertThatThrownBy(() ->
                service.processClientMessage(broadcastStreamId, generation, character, clientMessage)
        )
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", BroadcastErrorCode.GEMINI_RESPONSE_FAILED);

        verify(bundle, times(1)).incrementRequestFlight();
        verify(bundle, times(1)).decrementRequestFlight();
    }
}
