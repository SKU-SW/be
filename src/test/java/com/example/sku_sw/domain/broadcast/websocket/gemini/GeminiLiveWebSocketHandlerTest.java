package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GeminiLiveWebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private GeminiLiveWebSocketHandler geminiLiveWebSocketHandler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        geminiLiveWebSocketHandler = new GeminiLiveWebSocketHandler(objectMapper);
        ReflectionTestUtils.setField(geminiLiveWebSocketHandler, "dialogueModel", "gemini-3.1-flash-live-preview");
    }

    @Test
    @DisplayName("Gemini setup 메시지 전송 성공 - AUDIO modality와 outputAudioTranscription을 포함한다")
    void Gemini_setup_메시지_전송_성공() throws Exception {
        // given
        WebSocketSession session = mock(WebSocketSession.class);
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
        assertThat(setupNode.has("outputAudioTranscription")).isTrue();
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture(session)).isNotNull();
    }

    @Test
    @DisplayName("Gemini outputTranscription 누적 성공 - 분할된 텍스트와 오디오를 turnComplete까지 누적한다")
    void Gemini_outputTranscription_누적_성공() throws Exception {
        // given
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("gemini-1");

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
                session,
                new TextMessage(firstPayload)
        );

        geminiLiveWebSocketHandler.handleTextMessage(
                session,
                new TextMessage(secondPayload)
        );

        // then
        GeminiLiveWebSocketHandler.GeminiTurnAccumulator accumulator = geminiLiveWebSocketHandler.getTurnAccumulator(session);

        assertThat(accumulator).isNotNull();
        assertThat(accumulator.getAccumulatedText()).isEqualTo("안녕하세요");
        assertThat(accumulator.getAudioBytes()).isEqualTo("audio".getBytes(StandardCharsets.UTF_8));
        assertThat(accumulator.isTurnComplete()).isTrue();
    }

    @Test
    @DisplayName("Gemini setupComplete 수신 성공 - binary frame으로 와도 future가 완료된다")
    void Gemini_setupComplete_수신_성공_binary_frame() throws Exception {
        // given
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("gemini-1");
        geminiLiveWebSocketHandler.afterConnectionEstablished(session);

        // when
        geminiLiveWebSocketHandler.handleBinaryMessage(
                session,
                new BinaryMessage("{\"setupComplete\":{}}".getBytes(StandardCharsets.UTF_8))
        );

        // then
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture(session)).isNotNull();
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture(session).isDone()).isTrue();
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture(session).isCompletedExceptionally()).isFalse();
    }
}
