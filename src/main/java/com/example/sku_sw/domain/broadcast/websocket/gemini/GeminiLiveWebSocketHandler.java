package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.BroadcastGeminiRefreshTriggerType;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiRefreshRequestedEvent;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiResponseService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiToolCallService;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Gemini Live API WebSocket Handler
 * - setup 메시지를 전송하고 setupComplete 응답을 추적한다.
 * - Gemini 응답의 텍스트를 turn 단위로 누적하고, 오디오는 청크 단위로 즉시 전달한다.
 */
@Slf4j
public class GeminiLiveWebSocketHandler extends AbstractWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastGeminiResponseService broadcastGeminiResponseService;
    private final BroadcastGeminiToolCallService broadcastGeminiToolCallService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final String dialogueModel;
    private final String systemPrompt;
    private final CompletableFuture<Void> setupCompleteFuture = new CompletableFuture<>();

    private volatile String lastSentSetupPayload;
    private volatile String lastReceivedPayload;
    private volatile String lastGeminiErrorPayload;
    private volatile CloseStatus lastCloseStatus;

    private GeminiTurnAccumulator turnAccumulator;
    private long turnSequence = 0L;

    public GeminiLiveWebSocketHandler(
            ObjectMapper objectMapper,
            BroadcastWebSocketSessionRegistry sessionRegistry,
            BroadcastGeminiResponseService broadcastGeminiResponseService,
            BroadcastGeminiToolCallService broadcastGeminiToolCallService,
            ApplicationEventPublisher applicationEventPublisher,
            String dialogueModel,
            String systemPrompt
    ) {
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
        this.broadcastGeminiResponseService = broadcastGeminiResponseService;
        this.broadcastGeminiToolCallService = broadcastGeminiToolCallService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.dialogueModel = dialogueModel;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Gemini WebSocket Session 연결이 성공했을 때
     * @param session Gemini WebSocket Session
     *
     * @throws Exception
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode setupNode = payload.putObject("setup");
        setupNode.put("model", "models/" + dialogueModel);

        ObjectNode generationConfigNode = setupNode.putObject("generationConfig");
        ArrayNode responseModalitiesNode = generationConfigNode.putArray("responseModalities");
        responseModalitiesNode.add("AUDIO");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemInstructionNode = setupNode.putObject("systemInstruction");
            ArrayNode partsNode = systemInstructionNode.putArray("parts");
            partsNode.addObject().put("text", systemPrompt);
        }

        ArrayNode toolsNode = setupNode.putArray("tools");
        ObjectNode toolNode = toolsNode.addObject();
        ArrayNode functionDeclarationsNode = toolNode.putArray("functionDeclarations");
        functionDeclarationsNode.add(broadcastGeminiToolCallService.buildTalkingStateFunctionDeclaration());

        setupNode.putObject("outputAudioTranscription");

        String setupPayload = objectMapper.writeValueAsString(payload);
        lastSentSetupPayload = setupPayload;
        session.sendMessage(new TextMessage(setupPayload));

        log.info("[GeminiLiveWebSocketHandler] afterConnectionEstablished() - Setup message sent | sessionId: {}, payload: {}",
                session.getId(), abbreviate(setupPayload));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        handleIncomingPayload(session, message.getPayload(), "text");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteBuffer payloadBuffer = message.getPayload();
        byte[] payloadBytes = new byte[payloadBuffer.remaining()];
        payloadBuffer.get(payloadBytes);

        handleIncomingPayload(session, new String(payloadBytes, StandardCharsets.UTF_8), "binary");
    }

    private void handleIncomingPayload(WebSocketSession session, String payload, String frameType) throws Exception {
        log.debug("[GeminiLiveWebSocketHandler] handleIncomingPayload() - Received {} frame | sessionId: {}, payload: {}",
                frameType, session.getId(), payload);
        lastReceivedPayload = payload;

        JsonNode rootNode = objectMapper.readTree(payload);

        if (rootNode.has("setupComplete")) {
            setupCompleteFuture.complete(null);
            log.info("[GeminiLiveWebSocketHandler] handleTextMessage() - Setup complete | sessionId: {}", session.getId());
            return;
        }

        if (rootNode.has("error")) {
            lastGeminiErrorPayload = payload;
            if (!setupCompleteFuture.isDone()) {
                setupCompleteFuture.completeExceptionally(new IllegalStateException(rootNode.get("error").toString()));
            }
            log.error("[GeminiLiveWebSocketHandler] handleTextMessage() - Gemini error received | sessionId: {}, payload: {}",
                    session.getId(), payload);
            return;
        }

        JsonNode toolCallNode = rootNode.get("toolCall");
        if (toolCallNode != null) {
            handleToolCall(session, toolCallNode);
            return;
        }

        JsonNode serverContentNode = rootNode.get("serverContent");
        if (serverContentNode == null) {
            log.debug("[GeminiLiveWebSocketHandler] handleTextMessage() - Non-serverContent message | sessionId: {}, payload: {}",
                    session.getId(), payload);
            return;
        }

        GeminiTurnAccumulator accumulator = getOrCreateTurnAccumulator();

        String textChunk = extractOutputTranscription(serverContentNode);
        if (textChunk != null && !textChunk.isBlank()) {
            accumulator.appendText(textChunk);
        }

        byte[] audioChunk = extractAudioChunk(serverContentNode);
        logServerContentSummary(session, serverContentNode, accumulator.getTurnNumber(), textChunk, audioChunk);

        if ((textChunk != null && !textChunk.isBlank()) || audioChunk.length > 0) {
            dispatchStreamingChunk(session, accumulator.getTurnNumber(), textChunk, audioChunk);
        }

        JsonNode turnCompleteNode = serverContentNode.get("turnComplete");
        if (turnCompleteNode != null && turnCompleteNode.asBoolean(false)) {
            CompletedGeminiTurn completedTurn = new CompletedGeminiTurn(
                    accumulator.getTurnNumber(),
                    accumulator.getAccumulatedText()
            );
            clearAccumulator();

            dispatchCompletedTurn(session, completedTurn);

            log.info("[GeminiLiveWebSocketHandler] handleTextMessage() - Turn complete | sessionId: {}, turnNumber: {}, textLength: {}",
                    session.getId(), completedTurn.turnNumber(), completedTurn.accumulatedText().length());
        }
    }

    private void dispatchStreamingChunk(WebSocketSession geminiSession, Long turnNumber, String textChunk, byte[] audioChunk) {
        String broadcastStreamId = resolveBroadcastStreamId(geminiSession);
        if (broadcastStreamId == null) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchStreamingChunk() - Broadcast stream not found | sessionId: {}, turnNumber: {}",
                    geminiSession.getId(), turnNumber);
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || !bundle.canProcessGeminiResponse()) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchStreamingChunk() - Bundle not ready | streamId: {}, sessionId: {}, turnNumber: {}",
                    broadcastStreamId, geminiSession.getId(), turnNumber);
            return;
        }

        if (bundle.getGeminiSession() != geminiSession) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchStreamingChunk() - Gemini session mismatch | streamId: {}, sessionId: {}, turnNumber: {}",
                    broadcastStreamId, geminiSession.getId(), turnNumber);
            return;
        }

        broadcastGeminiResponseService.forwardStreamingChunk(
                geminiSession,
                broadcastStreamId,
                bundle.getGeneration(),
                turnNumber,
                textChunk,
                audioChunk
        );
    }

    private void dispatchCompletedTurn(WebSocketSession geminiSession, CompletedGeminiTurn completedTurn) {
        String broadcastStreamId = resolveBroadcastStreamId(geminiSession);
        if (broadcastStreamId == null) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchCompletedTurn() - Broadcast stream not found | sessionId: {}, turnNumber: {}",
                    geminiSession.getId(), completedTurn.turnNumber());
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || !bundle.canProcessGeminiResponse()) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchCompletedTurn() - Bundle not ready | streamId: {}, sessionId: {}, turnNumber: {}",
                    broadcastStreamId, geminiSession.getId(), completedTurn.turnNumber());
            return;
        }

        if (bundle.getGeminiSession() != geminiSession) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchCompletedTurn() - Gemini session mismatch | streamId: {}, sessionId: {}, turnNumber: {}",
                    broadcastStreamId, geminiSession.getId(), completedTurn.turnNumber());
            return;
        }

        broadcastGeminiResponseService.handleCompletedTurnAsync(
                geminiSession,
                broadcastStreamId,
                bundle.getGeneration(),
                completedTurn.turnNumber(),
                completedTurn.accumulatedText(),
                bundle
        );
    }

    private String resolveBroadcastStreamId(WebSocketSession geminiSession) {
        Object broadcastStreamIdAttribute = geminiSession.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        if (broadcastStreamIdAttribute == null) {
            return null;
        }

        return broadcastStreamIdAttribute.toString();
    }

    private String extractOutputTranscription(JsonNode serverContentNode) {
        JsonNode outputTranscriptionNode = serverContentNode.get("outputTranscription");
        if (outputTranscriptionNode == null) {
            return null;
        }

        JsonNode textNode = outputTranscriptionNode.get("text");
        if (textNode == null || textNode.isNull()) {
            return null;
        }

        return textNode.asText();
    }

    private byte[] extractAudioChunk(JsonNode serverContentNode) {
        JsonNode modelTurnNode = serverContentNode.get("modelTurn");
        if (modelTurnNode == null) {
            return new byte[0];
        }

        JsonNode partsNode = modelTurnNode.get("parts");
        if (partsNode == null || !partsNode.isArray()) {
            return new byte[0];
        }

        int totalLength = 0;
        byte[][] decodedChunks = new byte[partsNode.size()][];
        int chunkCount = 0;

        for (JsonNode partNode : partsNode) {
            JsonNode inlineDataNode = partNode.get("inlineData");
            if (inlineDataNode == null) {
                continue;
            }

            JsonNode dataNode = inlineDataNode.get("data");
            if (dataNode == null || dataNode.isNull()) {
                continue;
            }

            byte[] audioBytes = Base64.getDecoder().decode(dataNode.asText());
            decodedChunks[chunkCount++] = audioBytes;
            totalLength += audioBytes.length;
        }

        if (totalLength == 0) {
            return new byte[0];
        }

        byte[] mergedAudioChunk = new byte[totalLength];
        int offset = 0;
        for (int i = 0; i < chunkCount; i++) {
            byte[] audioBytes = decodedChunks[i];
            System.arraycopy(audioBytes, 0, mergedAudioChunk, offset, audioBytes.length);
            offset += audioBytes.length;
        }

        return mergedAudioChunk;
    }

    private void handleToolCall(WebSocketSession geminiSession, JsonNode toolCallNode) {
        String broadcastStreamId = resolveBroadcastStreamId(geminiSession);
        if (broadcastStreamId == null) {
            log.warn("[GeminiLiveWebSocketHandler] handleToolCall() - Broadcast stream not found | sessionId: {}",
                    geminiSession.getId());
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || !bundle.canProcessGeminiResponse()) {
            log.warn("[GeminiLiveWebSocketHandler] handleToolCall() - Bundle not ready | streamId: {}, sessionId: {}",
                    broadcastStreamId, geminiSession.getId());
            return;
        }

        if (bundle.getGeminiSession() != geminiSession) {
            log.warn("[GeminiLiveWebSocketHandler] handleToolCall() - Gemini session mismatch | streamId: {}, sessionId: {}",
                    broadcastStreamId, geminiSession.getId());
            return;
        }

        broadcastGeminiToolCallService.handleToolCall(geminiSession, broadcastStreamId, toolCallNode);
    }

    private void logServerContentSummary(
            WebSocketSession session,
            JsonNode serverContentNode,
            Long turnNumber,
            String textChunk,
            byte[] audioChunk
    ) {
        boolean hasOutputTranscription = serverContentNode.has("outputTranscription");
        boolean hasModelTurn = serverContentNode.has("modelTurn");
        boolean turnComplete = serverContentNode.path("turnComplete").asBoolean(false);
        boolean generationComplete = serverContentNode.path("generationComplete").asBoolean(false);
        int textChunkLength = textChunk == null ? 0 : textChunk.length();
        int audioChunkLength = audioChunk == null ? 0 : audioChunk.length;

        if (!hasOutputTranscription && !hasModelTurn && !turnComplete && !generationComplete) {
            return;
        }

        log.info("[GeminiLiveWebSocketHandler] handleIncomingPayload() - Parsed serverContent | sessionId: {}, turnNumber: {}, hasOutputTranscription: {}, textChunkLength: {}, hasModelTurn: {}, audioChunkLength: {}, turnComplete: {}, generationComplete: {}",
                session.getId(),
                turnNumber,
                hasOutputTranscription,
                textChunkLength,
                hasModelTurn,
                audioChunkLength,
                turnComplete,
                generationComplete);
    }

    private GeminiTurnAccumulator getOrCreateTurnAccumulator() {
        if (turnAccumulator == null) {
            turnAccumulator = new GeminiTurnAccumulator(nextTurnNumber());
        }
        return turnAccumulator;
    }

    private long nextTurnNumber() {
        turnSequence += 1L;
        return turnSequence;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        if (!setupCompleteFuture.isDone()) {
            setupCompleteFuture.completeExceptionally(exception);
        }
        clearAccumulator();
        handleTerminatedGeminiTurn(session);

        log.error("[GeminiLiveWebSocketHandler] handleTransportError() - Transport error | sessionId: {}, diagnostics: {}",
                session.getId(), getSetupFailureDiagnostics(), exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        lastCloseStatus = status;
        if (!setupCompleteFuture.isDone()) {
            setupCompleteFuture.completeExceptionally(new IllegalStateException("Gemini setup not completed before close. status=" + status));
            log.error("[GeminiLiveWebSocketHandler] afterConnectionClosed() - Session closed before setupComplete | sessionId: {}, diagnostics: {}",
                    session.getId(), getSetupFailureDiagnostics());
        }
        clearAccumulator();
        handleTerminatedGeminiTurn(session);

        log.info("[GeminiLiveWebSocketHandler] afterConnectionClosed() - Session closed | sessionId: {}, status: {}",
                session.getId(), status);
    }

    private void handleTerminatedGeminiTurn(WebSocketSession session) {
        String broadcastStreamId = resolveBroadcastStreamId(session);
        if (broadcastStreamId == null) {
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || bundle.getGeminiSession() != session) {
            return;
        }

        handleGeminiSessionTerminated(broadcastStreamId, bundle.getGeneration(), bundle);
    }

    /**
     * Gemini 세션 종료 시 남아있는 request-flight 요청을 정리한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param bundle : 현재 세션 번들
     */
    public void handleGeminiSessionTerminated(String broadcastStreamId, Long generation, BroadcastWebSocketSessionBundle bundle) {
        log.info("[GeminiLiveWebSocketHandler] handleGeminiSessionTerminated() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        /*
            1. 종료된 Gemini 세션의 request-flight 요청을 모두 정리한다.
            - transport error 또는 close 시점에는 더 이상 응답이 오지 않으므로 in-flight를 0으로 초기화한다.
         */
        bundle.resetRequestFlight();

        /*
            2. refresh 요청 상태라면 refresh 재평가 이벤트를 발행한다.
            - 기존 세션 종료로 request-flight가 0이 되었으므로 refresh 진행 조건을 다시 평가한다.
          */
        if (bundle.getGeminiSessionRefreshRequested()) {
            applicationEventPublisher.publishEvent(BroadcastGeminiRefreshRequestedEvent.builder()
                    .broadcastStreamId(broadcastStreamId)
                    .generation(generation)
                    .triggerType(BroadcastGeminiRefreshTriggerType.SESSION_TERMINATED)
                    .build());
        }

        log.info("[GeminiLiveWebSocketHandler] handleGeminiSessionTerminated() - END | streamId: {}, generation: {}",
                broadcastStreamId, generation);
    }

    public CompletableFuture<Void> getSetupCompleteFuture() {
        return setupCompleteFuture;
    }

    public String getSetupFailureDiagnostics() {
        return String.format(
                "lastCloseStatus=%s, lastReceivedPayload=%s, lastGeminiErrorPayload=%s, lastSentSetupPayload=%s",
                lastCloseStatus,
                abbreviate(lastReceivedPayload),
                abbreviate(lastGeminiErrorPayload),
                abbreviate(lastSentSetupPayload)
        );
    }

    public GeminiTurnAccumulator getTurnAccumulator() {
        return turnAccumulator;
    }

    public GeminiTurnAccumulator clearAccumulator() {
        GeminiTurnAccumulator previousAccumulator = turnAccumulator;
        turnAccumulator = null;
        return previousAccumulator;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "null";
        }

        int maxLength = 2000;
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength) + "...(truncated)";
    }

    public record CompletedGeminiTurn(
            Long turnNumber,
            String accumulatedText
    ) {
    }

    public static class GeminiTurnAccumulator {
        private final Long turnNumber;
        private final StringBuilder accumulatedText = new StringBuilder();

        public GeminiTurnAccumulator(Long turnNumber) {
            this.turnNumber = turnNumber;
        }

        public void appendText(String text) {
            accumulatedText.append(text);
        }

        public Long getTurnNumber() {
            return turnNumber;
        }

        public String getAccumulatedText() {
            return accumulatedText.toString();
        }
    }
}
