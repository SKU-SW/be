package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiToolCallServiceTest {

    private ObjectMapper objectMapper;
    private BroadcastGeminiToolCallService broadcastGeminiToolCallService;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private WebSocketSession geminiSession;

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        broadcastGeminiToolCallService = new BroadcastGeminiToolCallService(objectMapper, broadcastRedisUtil, sessionRegistry);
    }

    @Test
    @DisplayName("Function declaration 생성 성공")
    void Function_declaration_생성_성공() {
        // given

        // when
        ObjectNode result = broadcastGeminiToolCallService.buildTalkingStateFunctionDeclaration();

        // then
        assertThat(result.get("name").asText()).isEqualTo("set_talking_state");
        assertThat(result.get("description").asText())
                .isEqualTo("Set the broadcast character talking state when the streamer was not speaking to the AI.");
        assertThat(result.get("parameters").get("type").asText()).isEqualTo("object");
        assertThat(result.get("parameters").get("properties").get("isTalking").get("type").asText()).isEqualTo("boolean");
        assertThat(result.get("parameters").get("properties").get("isTalking").get("enum")).isNull();
        assertThat(result.get("parameters").get("required").get(0).asText()).isEqualTo("isTalking");
    }

    @Test
    @DisplayName("response emotion function declaration 생성 성공")
    void response_emotion_function_declaration_생성_성공() {
        // given

        // when
        ObjectNode result = broadcastGeminiToolCallService.buildResponseEmotionFunctionDeclaration();

        // then
        assertThat(result.get("name").asText()).isEqualTo("set_response_emotion");
        assertThat(result.get("parameters").get("properties").get("emotion").get("type").asText()).isEqualTo("string");
        assertThat(result.get("parameters").get("properties").get("emotion").get("enum")).hasSize(Emotion.values().length);
        assertThat(result.get("parameters").get("required").get(0).asText()).isEqualTo("emotion");
    }

    @Test
    @DisplayName("set_talking_state false 처리 성공")
    void set_talking_state_false_처리_성공() throws Exception {
        // given
        JsonNode toolCallNode = objectMapper.readTree("""
                {
                  "functionCalls": [
                    {
                      "id": "fc-123",
                      "name": "set_talking_state",
                      "args": {
                        "isTalking": false
                      }
                    }
                  ]
                }
                """);

        // when
        broadcastGeminiToolCallService.handleToolCall(geminiSession, "stream-1", toolCallNode);

        // then
        verify(broadcastRedisUtil, times(1)).updateBroadcastCharacterIsTalking("stream-1", false);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());

        JsonNode responseNode = objectMapper.readTree(captor.getValue().getPayload());
        JsonNode functionResponseNode = responseNode.get("toolResponse").get("functionResponses").get(0);
        assertThat(functionResponseNode.get("id").asText()).isEqualTo("fc-123");
        assertThat(functionResponseNode.get("name").asText()).isEqualTo("set_talking_state");
        assertThat(functionResponseNode.get("response").get("success").asBoolean()).isTrue();
        assertThat(functionResponseNode.get("response").get("isTalking").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("set_response_emotion 처리 성공")
    void set_response_emotion_처리_성공() throws Exception {
        // given
        JsonNode toolCallNode = objectMapper.readTree("""
                {
                  "functionCalls": [
                    {
                      "id": "fc-456",
                      "name": "set_response_emotion",
                      "args": {
                        "emotion": "HAPPY"
                      }
                    }
                  ]
                }
                """);

        // when
        BroadcastGeminiToolCallService.ToolCallHandleResult result = broadcastGeminiToolCallService.handleToolCall(geminiSession, "stream-1", toolCallNode);

        // then
        assertThat(result.responseEmotionHandled()).isTrue();
        assertThat(result.emotion()).isEqualTo(Emotion.HAPPY);
        verify(broadcastRedisUtil, never()).updateBroadcastCharacterIsTalking(eq("stream-1"), eq(false));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());
        JsonNode responseNode = objectMapper.readTree(captor.getValue().getPayload());
        JsonNode functionResponseNode = responseNode.get("toolResponse").get("functionResponses").get(0);
        assertThat(functionResponseNode.get("name").asText()).isEqualTo("set_response_emotion");
        assertThat(functionResponseNode.get("response").get("success").asBoolean()).isTrue();
        assertThat(functionResponseNode.get("response").get("emotion").asText()).isEqualTo("HAPPY");
    }

    @Test
    @DisplayName("set_response_emotion 처리 성공 - invalid emotion이면 DEFAULT로 fallback")
    void set_response_emotion_처리_성공_invalid_emotion_default_fallback() throws Exception {
        // given
        JsonNode toolCallNode = objectMapper.readTree("""
                {
                  "functionCalls": [
                    {
                      "id": "fc-789",
                      "name": "set_response_emotion",
                      "args": {
                        "emotion": "JOYFUL"
                      }
                    }
                  ]
                }
                """);

        // when
        BroadcastGeminiToolCallService.ToolCallHandleResult result = broadcastGeminiToolCallService.handleToolCall(geminiSession, "stream-1", toolCallNode);

        // then
        assertThat(result.responseEmotionHandled()).isTrue();
        assertThat(result.emotion()).isEqualTo(Emotion.DEFAULT);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());
        JsonNode responseNode = objectMapper.readTree(captor.getValue().getPayload());
        JsonNode functionResponseNode = responseNode.get("toolResponse").get("functionResponses").get(0);
        assertThat(functionResponseNode.get("response").get("emotion").asText()).isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("set_talking_state 처리 실패 - isTalking 누락")
    void set_talking_state_처리_실패_isTalking_누락() throws Exception {
        // given
        JsonNode toolCallNode = objectMapper.readTree("""
                {
                  "functionCalls": [
                    {
                      "id": "fc-123",
                      "name": "set_talking_state",
                      "args": {
                      }
                    }
                  ]
                }
                """);

        // when
        broadcastGeminiToolCallService.handleToolCall(geminiSession, "stream-1", toolCallNode);

        // then
        verify(broadcastRedisUtil, never()).updateBroadcastCharacterIsTalking(eq("stream-1"), eq(false));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());

        JsonNode responseNode = objectMapper.readTree(captor.getValue().getPayload());
        JsonNode functionResponseNode = responseNode.get("toolResponse").get("functionResponses").get(0);
        assertThat(functionResponseNode.get("response").get("success").asBoolean()).isFalse();
        assertThat(functionResponseNode.get("response").get("reason").asText()).isEqualTo("isTalking boolean arg is required");
    }

    @Test
    @DisplayName("set_talking_state 처리 실패 - true는 지원하지 않음")
    void set_talking_state_처리_실패_true는_지원하지_않음() throws Exception {
        // given
        JsonNode toolCallNode = objectMapper.readTree("""
                {
                  "functionCalls": [
                    {
                      "id": "fc-123",
                      "name": "set_talking_state",
                      "args": {
                        "isTalking": true
                      }
                    }
                  ]
                }
                """);

        // when
        broadcastGeminiToolCallService.handleToolCall(geminiSession, "stream-1", toolCallNode);

        // then
        verify(broadcastRedisUtil, never()).updateBroadcastCharacterIsTalking(eq("stream-1"), eq(false));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());

        JsonNode responseNode = objectMapper.readTree(captor.getValue().getPayload());
        JsonNode functionResponseNode = responseNode.get("toolResponse").get("functionResponses").get(0);
        assertThat(functionResponseNode.get("response").get("success").asBoolean()).isFalse();
        assertThat(functionResponseNode.get("response").get("reason").asText()).isEqualTo("isTalking=true is not supported");
    }

    @Test
    @DisplayName("알 수 없는 functionCall 수신 실패 - 실패 응답 반환")
    void 알_수_없는_functionCall_수신_실패_실패_응답_반환() throws Exception {
        // given
        JsonNode toolCallNode = objectMapper.readTree("""
                {
                  "functionCalls": [
                    {
                      "id": "fc-123",
                      "name": "unknown_function",
                      "args": {
                        "value": false
                      }
                    }
                  ]
                }
                """);

        // when
        broadcastGeminiToolCallService.handleToolCall(geminiSession, "stream-1", toolCallNode);

        // then
        verify(broadcastRedisUtil, never()).updateBroadcastCharacterIsTalking(eq("stream-1"), eq(false));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(geminiSession, times(1)).sendMessage(captor.capture());

        JsonNode responseNode = objectMapper.readTree(captor.getValue().getPayload());
        JsonNode functionResponseNode = responseNode.get("toolResponse").get("functionResponses").get(0);
        assertThat(functionResponseNode.get("name").asText()).isEqualTo("unknown_function");
        assertThat(functionResponseNode.get("response").get("success").asBoolean()).isFalse();
        assertThat(functionResponseNode.get("response").get("reason").asText()).isEqualTo("Unsupported function call");
    }
}
