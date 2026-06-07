package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.BroadcastGeminiRefreshTriggerType;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiResumptionRequestedEvent;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiRefreshRequestedEvent;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiResponseService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiToolCallService;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.doAnswer;
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

    @Mock
    private BroadcastGeminiToolCallService broadcastGeminiToolCallService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(broadcastGeminiToolCallService.buildTalkingStateFunctionDeclaration()).thenReturn(buildTalkingStateFunctionDeclaration());
        lenient().when(broadcastGeminiToolCallService.buildResponseEmotionFunctionDeclaration()).thenReturn(buildResponseEmotionFunctionDeclaration());
        geminiLiveWebSocketHandler = new GeminiLiveWebSocketHandler(
                objectMapper,
                sessionRegistry,
                broadcastGeminiResponseService,
                applicationEventPublisher,
                broadcastGeminiToolCallService,
                DIALOGUE_MODEL,
                SYSTEM_PROMPT,
                null,
                null
        );
    }

    @Test
    @DisplayName("Gemini setup 메시지 전송 성공 - AUDIO modality(only)와 outputAudioTranscription, systemInstruction, functionDeclaration을 포함한다")
    void Gemini_setup_메시지_전송_성공() throws Exception {
        // given
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        given(session.getId()).willReturn("gemini-1");
        given(session.getAttributes()).willReturn(attributes);

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
        assertThat(setupNode.get("tools")).hasSize(1);
        assertThat(setupNode.get("tools").get(0).get("functionDeclarations")).hasSize(2);
        assertThat(setupNode.get("tools").get(0).get("functionDeclarations").get(0).get("name").asText()).isEqualTo("set_talking_state");
        assertThat(setupNode.get("tools").get(0).get("functionDeclarations").get(0)
                .get("parameters").get("properties").get("isTalking").get("type").asText()).isEqualTo("boolean");
        assertThat(setupNode.get("tools").get(0).get("functionDeclarations").get(0)
                .get("parameters").get("properties").get("isTalking").get("enum")).isNull();
        assertThat(setupNode.get("tools").get(0).get("functionDeclarations").get(1).get("name").asText()).isEqualTo("set_response_emotion");
        assertThat(setupNode.has("outputAudioTranscription")).isTrue();
        assertThat(geminiLiveWebSocketHandler.getSetupCompleteFuture()).isNotNull();
        assertThat(geminiLiveWebSocketHandler.getSetupFailureDiagnostics()).contains("lastSentSetupPayload=");
    }

    @Test
    @DisplayName("Gemini sessionResumptionUpdate 수신 성공 - bundle에 resumption metadata를 저장한다")
    void Gemini_sessionResumptionUpdate_수신_성공() throws Exception {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), "stream-1");
        given(geminiSession.getId()).willReturn("gemini-1");
        given(geminiSession.getAttributes()).willReturn(attributes);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        String payload = """
                {
                  "sessionResumptionUpdate": {
                    "newHandle": "resume-1",
                    "resumable": true
                  }
                }
                """;

        // when
        geminiLiveWebSocketHandler.handleTextMessage(geminiSession, new TextMessage(payload));

        // then
        assertThat(bundle.getLatestGeminiResumptionHandle()).isEqualTo("resume-1");
        assertThat(bundle.isLatestGeminiResumable()).isTrue();
        assertThat(bundle.getLatestGeminiResumptionUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Gemini remote close 시 resumption 성공하면 cleanup을 실행하지 않는다")
    void Gemini_remote_close_resumption_성공_cleanup_미실행() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), "stream-1");
        given(geminiSession.getId()).willReturn("gemini-1");
        given(geminiSession.getAttributes()).willReturn(attributes);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);
        bundle.incrementRequestFlight();
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);
        doAnswer(invocation -> {
            BroadcastGeminiResumptionRequestedEvent event = invocation.getArgument(0);
            event.getResumed().set(true);
            return null;
        }).when(applicationEventPublisher).publishEvent(any(BroadcastGeminiResumptionRequestedEvent.class));

        // when
        geminiLiveWebSocketHandler.afterConnectionClosed(geminiSession, CloseStatus.SERVER_ERROR);

        // then
        verify(applicationEventPublisher, times(1)).publishEvent(any(BroadcastGeminiResumptionRequestedEvent.class));
        assertThat(bundle.getRequestFlightCountValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("Gemini remote close 시 resumption 실패하면 기존 cleanup을 실행한다")
    void Gemini_remote_close_resumption_실패_cleanup_실행() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), "stream-1");
        given(geminiSession.getId()).willReturn("gemini-1");
        given(geminiSession.getAttributes()).willReturn(attributes);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);
        bundle.markRefreshRequested();
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        doAnswer(invocation -> {
            BroadcastGeminiResumptionRequestedEvent event = invocation.getArgument(0);
            event.getFallbackExecuted().set(true);
            event.getFallbackCleanup().run();
            return false;
        }).when(applicationEventPublisher).publishEvent(any(BroadcastGeminiResumptionRequestedEvent.class));

        // when
        geminiLiveWebSocketHandler.afterConnectionClosed(geminiSession, CloseStatus.SERVER_ERROR);

        // then
        verify(applicationEventPublisher, times(1)).publishEvent(any(BroadcastGeminiResumptionRequestedEvent.class));
        verify(applicationEventPublisher, times(1)).publishEvent(any(BroadcastGeminiRefreshRequestedEvent.class));
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
                aryEq(new byte[0]),
                eq(Emotion.TALKING)
        );

        verify(broadcastGeminiResponseService, times(1)).forwardStreamingChunk(
                eq(geminiSession),
                eq("stream-1"),
                eq(1L),
                eq(1L),
                eq("하세요"),
                aryEq("audio".getBytes(StandardCharsets.UTF_8)),
                eq(Emotion.TALKING)
        );

        assertThat(geminiLiveWebSocketHandler.getTurnAccumulator()).isNull();
        verify(broadcastGeminiResponseService, times(1)).handleCompletedTurnAsync(
                eq(geminiSession),
                eq("stream-1"),
                eq(1L),
                eq(1L),
                eq("안녕하세요"),
                eq(Emotion.TALKING),
                eq(bundle)
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
        verify(broadcastGeminiResponseService, never()).forwardStreamingChunk(any(), any(), any(), any(), any(), any(), any());
        verify(broadcastGeminiResponseService, never()).handleCompletedTurnAsync(any(), any(), any(), any(), any(), any(), any());
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

    @Test
    @DisplayName("Gemini toolCall 수신 성공 - ToolCallService로 위임하고 스트리밍 응답은 전달하지 않는다")
    void Gemini_toolCall_수신_성공_ToolCallService로_위임() throws Exception {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.getId()).willReturn("gemini-1");
        given(geminiSession.isOpen()).willReturn(true);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), "stream-1");
        given(geminiSession.getAttributes()).willReturn(attributes);

        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);

        String payload = """
                {
                  "toolCall": {
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
                }
                """;

        given(broadcastGeminiToolCallService.handleToolCall(eq(geminiSession), eq("stream-1"), any()))
                .willReturn(new BroadcastGeminiToolCallService.ToolCallHandleResult(true, false, null));

        // when
        geminiLiveWebSocketHandler.handleTextMessage(
                geminiSession,
                new TextMessage(payload)
        );

        // then
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(broadcastGeminiToolCallService, times(1)).handleToolCall(eq(geminiSession), eq("stream-1"), captor.capture());
        assertThat(captor.getValue().get("functionCalls")).hasSize(1);
        assertThat(captor.getValue().get("functionCalls").get(0).get("name").asText()).isEqualTo("set_talking_state");
        verify(broadcastGeminiResponseService, never()).forwardStreamingChunk(any(), any(), any(), any(), any(), any(), any());
        verify(broadcastGeminiResponseService, never()).handleCompletedTurnAsync(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Gemini toolCall 수신 실패 - streamId가 없으면 ToolCallService로 위임하지 않는다")
    void Gemini_toolCall_수신_실패_streamId_없음() throws Exception {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.getId()).willReturn("gemini-1");
        given(geminiSession.getAttributes()).willReturn(new HashMap<>());

        String payload = """
                {
                  "toolCall": {
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
                }
                """;

        // when
        geminiLiveWebSocketHandler.handleTextMessage(geminiSession, new TextMessage(payload));

        // then
        verify(broadcastGeminiToolCallService, never()).handleToolCall(any(), any(), any());
        verify(broadcastGeminiResponseService, never()).forwardStreamingChunk(any(), any(), any(), any(), any(), any(), any());
        verify(broadcastGeminiResponseService, never()).handleCompletedTurnAsync(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Gemini emotion toolCall 수신 성공 - 감정 업데이트를 전달하고 이후 스트리밍은 계속 처리한다")
    void Gemini_emotion_toolCall_수신_성공() throws Exception {
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

        String emotionPayload = """
                {
                  "toolCall": {
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
                }
                """;
        String contentPayload = """
                {
                  "serverContent": {
                    "outputTranscription": {
                      "text": "안녕"
                    }
                  }
                }
                """;

        given(broadcastGeminiToolCallService.handleToolCall(eq(geminiSession), eq("stream-1"), any()))
                .willReturn(new BroadcastGeminiToolCallService.ToolCallHandleResult(false, true, Emotion.HAPPY));

        // when
        geminiLiveWebSocketHandler.handleTextMessage(geminiSession, new TextMessage(emotionPayload));
        geminiLiveWebSocketHandler.handleTextMessage(geminiSession, new TextMessage(contentPayload));

        // then
        verify(broadcastGeminiResponseService, times(1))
                .forwardEmotionUpdate(geminiSession, "stream-1", 1L, 1L, Emotion.HAPPY);
        verify(broadcastGeminiResponseService, times(1))
                .forwardStreamingChunk(geminiSession, "stream-1", 1L, 1L, "안녕", new byte[0], Emotion.HAPPY);
    }

    @Test
    @DisplayName("Gemini 세션 종료 처리 성공 - refresh 요청 상태면 refresh 이벤트를 발행한다")
    void Gemini_세션_종료_처리_성공_refresh_event_publish() {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(generation)
                .status(WebSocketSessionBundleStatus.REFRESHING)
                .build();
        bundle.markRefreshRequested();
        bundle.incrementRequestFlight();

        // when
        geminiLiveWebSocketHandler.handleGeminiSessionTerminated(broadcastStreamId, generation, bundle);

        // then
        assertThat(bundle.getRequestFlightCountValue()).isZero();
        verify(applicationEventPublisher, times(1))
                .publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) -> {
                    if (!(event instanceof BroadcastGeminiRefreshRequestedEvent)) {
                        return false;
                    }
                    BroadcastGeminiRefreshRequestedEvent refreshEvent = (BroadcastGeminiRefreshRequestedEvent) event;
                    return broadcastStreamId.equals(refreshEvent.broadcastStreamId())
                            && generation == refreshEvent.generation()
                            && refreshEvent.triggerType() == BroadcastGeminiRefreshTriggerType.SESSION_TERMINATED;
                }));
    }

    @Test
    @DisplayName("Gemini 세션 종료 처리 성공 - refresh 요청 상태가 아니면 refresh 이벤트를 발행하지 않는다")
    void Gemini_세션_종료_처리_성공_no_event_without_refresh_request() {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(generation)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.incrementRequestFlight();

        // when
        geminiLiveWebSocketHandler.handleGeminiSessionTerminated(broadcastStreamId, generation, bundle);

        // then
        assertThat(bundle.getRequestFlightCountValue()).isZero();
        verify(applicationEventPublisher, never())
                .publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) -> event instanceof BroadcastGeminiRefreshRequestedEvent));
    }

    private ObjectNode buildTalkingStateFunctionDeclaration() {
        ObjectNode functionDeclarationNode = objectMapper.createObjectNode();
        functionDeclarationNode.put("name", "set_talking_state");
        functionDeclarationNode.put("description", "Set the broadcast character talking state when the streamer was not speaking to the AI.");

        ObjectNode parametersNode = functionDeclarationNode.putObject("parameters");
        parametersNode.put("type", "object");

        ObjectNode propertiesNode = parametersNode.putObject("properties");
        ObjectNode isTalkingNode = propertiesNode.putObject("isTalking");
        isTalkingNode.put("type", "boolean");
        isTalkingNode.put("description", "Whether the AI character should remain in talking mode.");

        parametersNode.putArray("required").add("isTalking");
        return functionDeclarationNode;
    }

    private ObjectNode buildResponseEmotionFunctionDeclaration() {
        ObjectNode functionDeclarationNode = objectMapper.createObjectNode();
        functionDeclarationNode.put("name", "set_response_emotion");
        functionDeclarationNode.put("description", "Set the current response emotion before generating the spoken answer.");

        ObjectNode parametersNode = functionDeclarationNode.putObject("parameters");
        parametersNode.put("type", "object");

        ObjectNode propertiesNode = parametersNode.putObject("properties");
        ObjectNode emotionNode = propertiesNode.putObject("emotion");
        emotionNode.put("type", "string");
        emotionNode.putArray("enum").add("DEFAULT").add("TALKING").add("HAPPY").add("ANGRY").add("TIRED").add("SAD").add("FEAR");

        parametersNode.putArray("required").add("emotion");
        return functionDeclarationNode;
    }
}
