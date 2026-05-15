package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiResponseService;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GeminiLiveWebSocketHandlerTest {

    private static final String DIALOGUE_MODEL = "gemini-3.1-flash-live-preview";
    private static final String SYSTEM_PROMPT = "테스트 시스템 프롬프트";

    private ObjectMapper objectMapper;
    private GeminiLiveWebSocketHandler geminiLiveWebSocketHandler;

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private BroadcastGeminiResponseService broadcastGeminiResponseService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        geminiLiveWebSocketHandler = new GeminiLiveWebSocketHandler(
                objectMapper,
                sessionRegistry,
                broadcastGeminiResponseService,
                DIALOGUE_MODEL,
                SYSTEM_PROMPT
        );
    }

    @Test
    @DisplayName("Gemini setup 메시지 전송 성공 - AUDIO modality와 outputAudioTranscription, systemInstruction을 포함한다")
    void Gemini_setup_메시지_전송_성공() throws Exception {
        // given
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        given(session.getId()).willReturn("gemini-1");

        // when
        geminiLiveWebSocketHandler.afterConnectionEstablished(session);

        // then
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        JsonNode setupNode = payload.get("setup");

        assertThat(setupNode.get("model").asText()).isEqualTo("models/gemini-3.1-flash-live-preview");
        assertThat(setupNode.get("generationConfig").get("responseModalities")).hasSize(1);
        assertThat(setupNode.get("generationConfig").get("responseModalities").get(0).asText()).isEqualTo("AUDIO");
        assertThat(setupNode.get("systemInstruction").get("parts").get(0).get("text").asText()).isEqualTo(SYSTEM_PROMPT);
        assertThat(setupNode.has("outputAudioTranscription")).isTrue();
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture()).isNotNull();
    }

    @Test
    @DisplayName("Gemini turnComplete 수신 성공 - 텍스트 누적 및 오디오 청크 스트리밍 후 완료 응답을 전달한다")
    void Gemini_turnComplete_수신_성공() throws Exception {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.getId()).willReturn("gemini-1");
        given(geminiSession.isOpen()).willReturn(true);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), "stream-1");
        given(geminiSession.getAttributes()).willReturn(attributes);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);

        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        String audioBase64 = Base64.getEncoder().encodeToString("audio".getBytes(StandardCharsets.UTF_8));
        String firstPayload = """
                {
                  "serverContent": {
                    "outputTranscription": {
                      "text": "안녕"
                    }
                  }
                }
                """;
        String secondPayload = """
                {
                  "serverContent": {
                    "outputTranscription": {
                      "text": "하세요"
                    },
                    "modelTurn": {
                      "parts": [
                        {
                          "inlineData": {
                            "data": "%s"
                          }
                        }
                      ]
                    },
                    "turnComplete": true
                  }
                }
                """.formatted(audioBase64);

        // when
        geminiLiveWebSocketHandler.handleTextMessage(
                geminiSession,
                new TextMessage(firstPayload)
        );

        geminiLiveWebSocketHandler.handleTextMessage(
                geminiSession,
                new TextMessage(secondPayload)
        );

        // then
        verify(broadcastGeminiResponseService, times(1)).forwardStreamingChunk(
                eq(geminiSession),
                eq("stream-1"),
                eq(1L),
                eq(1L),
                eq("안녕"),
                eq(new byte[0])
        );

        verify(broadcastGeminiResponseService, times(1)).forwardStreamingChunk(
                eq(geminiSession),
                eq("stream-1"),
                eq(1L),
                eq(1L),
                eq("하세요"),
                eq("audio".getBytes(StandardCharsets.UTF_8))
        );

        assertThat(geminiLiveWebSocketHandler.getTurnAccumulator()).isNull();
        verify(broadcastGeminiResponseService, times(1)).handleCompletedTurnAsync(
                eq(geminiSession),
                eq("stream-1"),
                eq(1L),
                eq(1L),
                eq("안녕하세요")
        );
    }

    @Test
    @DisplayName("Gemini turnComplete 수신 실패 - broadcastStreamId가 없으면 청크 스트리밍과 완료 응답을 전달하지 않는다")
    void Gemini_turnComplete_수신_실패_streamId_없음() throws Exception {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.getId()).willReturn("gemini-1");
        given(geminiSession.getAttributes()).willReturn(new HashMap<>());

        String payload = """
                {
                  "serverContent": {
                    "outputTranscription": {
                      "text": "안녕"
                    },
                    "turnComplete": true
                  }
                }
                """;

        // when
        geminiLiveWebSocketHandler.handleTextMessage(
                geminiSession,
                new TextMessage(payload)
        );

        // then
        assertThat(geminiLiveWebSocketHandler.getTurnAccumulator()).isNull();
        verify(broadcastGeminiResponseService, never()).forwardStreamingChunk(any(), any(), any(), any(), any(), any());
        verify(broadcastGeminiResponseService, never()).handleCompletedTurnAsync(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Gemini setupComplete 수신 성공 - binary frame으로 와도 future가 완료된다")
    void Gemini_setupComplete_수신_성공_binary_frame() throws Exception {
        // given
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        given(session.getId()).willReturn("gemini-1");
        geminiLiveWebSocketHandler.afterConnectionEstablished(session);

        // when
        geminiLiveWebSocketHandler.handleBinaryMessage(
                session,
                new BinaryMessage("{\"setupComplete\":{}}".getBytes(StandardCharsets.UTF_8))
        );

        // then
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture()).isNotNull();
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture().isDone()).isTrue();
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture().isCompletedExceptionally()).isFalse();
    }
}
