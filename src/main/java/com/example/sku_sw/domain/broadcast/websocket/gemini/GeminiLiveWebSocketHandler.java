package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiResponseService;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
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
    private final String dialogueModel;
    private final String systemPrompt;
    private final CompletableFuture<Void> setupCompleteFuture = new CompletableFuture<>();

    private GeminiTurnAccumulator turnAccumulator;
    private long turnSequence = 0L;

    public GeminiLiveWebSocketHandler(
            ObjectMapper objectMapper,
            BroadcastWebSocketSessionRegistry sessionRegistry,
            BroadcastGeminiResponseService broadcastGeminiResponseService,
            String dialogueModel,
            String systemPrompt
    ) {
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
        this.broadcastGeminiResponseService = broadcastGeminiResponseService;
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

        setupNode.putObject("outputAudioTranscription");

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));

        log.info("[GeminiLiveWebSocketHandler] afterConnectionEstablished() - Setup message sent | sessionId: {}", session.getId());
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

        JsonNode rootNode = objectMapper.readTree(payload);

        if (rootNode.has("setupComplete")) {
            setupCompleteFuture.complete(null);
            log.info("[GeminiLiveWebSocketHandler] handleTextMessage() - Setup complete | sessionId: {}", session.getId());
            return;
        }

        if (rootNode.has("error")) {
            if (!setupCompleteFuture.isDone()) {
                setupCompleteFuture.completeExceptionally(new IllegalStateException(rootNode.get("error").toString()));
            }
            log.error("[GeminiLiveWebSocketHandler] handleTextMessage() - Gemini error received | sessionId: {}, payload: {}",
                    session.getId(), payload);
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
        if (bundle == null || !bundle.isReady() || !bundle.isGeminiSessionOpen()) {
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
        if (bundle == null || !bundle.isReady() || !bundle.isGeminiSessionOpen()) {
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
                completedTurn.accumulatedText()
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

        log.error("[GeminiLiveWebSocketHandler] handleTransportError() - Transport error | sessionId: {}, error: {}",
                session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (!setupCompleteFuture.isDone()) {
            setupCompleteFuture.completeExceptionally(new IllegalStateException("Gemini setup not completed before close. status=" + status));
        }
        clearAccumulator();

        log.info("[GeminiLiveWebSocketHandler] afterConnectionClosed() - Session closed | sessionId: {}, status: {}",
                session.getId(), status);
    }

    public CompletableFuture<Void> getSetupCompleteFuture() {
        return setupCompleteFuture;
    }

    public GeminiTurnAccumulator getTurnAccumulator() {
        return turnAccumulator;
    }

    public GeminiTurnAccumulator clearAccumulator() {
        GeminiTurnAccumulator previousAccumulator = turnAccumulator;
        turnAccumulator = null;
        return previousAccumulator;
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
