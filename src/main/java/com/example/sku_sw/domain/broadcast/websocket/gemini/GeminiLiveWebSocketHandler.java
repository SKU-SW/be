package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini Live API WebSocket Handler
 * - setup 메시지를 전송하고 setupComplete 응답을 추적한다.
 * - Gemini 응답의 텍스트/오디오를 turn 단위로 누적한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiLiveWebSocketHandler extends AbstractWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> setupCompleteFutureHashMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GeminiTurnAccumulator> turnAccumulatorHashMap = new ConcurrentHashMap<>();

    @Value("${gemini.api.dialogue-model}")
    private String dialogueModel;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        CompletableFuture<Void> setupCompleteFuture = new CompletableFuture<>();
        setupCompleteFutureHashMap.put(session.getId(), setupCompleteFuture);

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode setupNode = payload.putObject("setup");
        setupNode.put("model", "models/" + dialogueModel);

        ObjectNode generationConfigNode = setupNode.putObject("generationConfig");
        ArrayNode responseModalitiesNode = generationConfigNode.putArray("responseModalities");
        responseModalitiesNode.add("AUDIO");
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
            CompletableFuture<Void> setupCompleteFuture = getSetupCompleteFuture(session);
            if (setupCompleteFuture != null) {
                setupCompleteFuture.complete(null);
            }
            log.info("[GeminiLiveWebSocketHandler] handleTextMessage() - Setup complete | sessionId: {}", session.getId());
            return;
        }

        if (rootNode.has("error")) {
            CompletableFuture<Void> setupCompleteFuture = getSetupCompleteFuture(session);
            if (setupCompleteFuture != null) {
                setupCompleteFuture.completeExceptionally(new IllegalStateException(rootNode.get("error").toString()));
            }
            removeSetupCompleteFuture(session.getId());
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

        GeminiTurnAccumulator accumulator = turnAccumulatorHashMap.computeIfAbsent(session.getId(), sessionId -> new GeminiTurnAccumulator());
        appendOutputTranscription(serverContentNode, accumulator);

        JsonNode modelTurnNode = serverContentNode.get("modelTurn");
        if (modelTurnNode != null) {
            JsonNode partsNode = modelTurnNode.get("parts");
            if (partsNode != null && partsNode.isArray()) {
                for (JsonNode partNode : partsNode) {
                    JsonNode textNode = partNode.get("text");
                    if (textNode != null && !textNode.isNull()) {
                        accumulator.appendText(textNode.asText());
                    }

                    JsonNode inlineDataNode = partNode.get("inlineData");
                    if (inlineDataNode != null) {
                        JsonNode dataNode = inlineDataNode.get("data");
                        if (dataNode != null && !dataNode.isNull()) {
                            accumulator.appendAudio(Base64.getDecoder().decode(dataNode.asText()));
                        }
                    }
                }
            }
        }

        JsonNode turnCompleteNode = serverContentNode.get("turnComplete");
        if (turnCompleteNode != null && turnCompleteNode.asBoolean(false)) {
            accumulator.markTurnComplete();
            log.info("[GeminiLiveWebSocketHandler] handleTextMessage() - Turn complete | sessionId: {}, textLength: {}, audioSize: {}",
                    session.getId(), accumulator.getAccumulatedText().length(), accumulator.getAudioBytes().length);
        }
    }

    private void appendOutputTranscription(JsonNode serverContentNode, GeminiTurnAccumulator accumulator) {
        JsonNode outputTranscriptionNode = serverContentNode.get("outputTranscription");
        if (outputTranscriptionNode == null) {
            return;
        }

        JsonNode textNode = outputTranscriptionNode.get("text");
        if (textNode == null || textNode.isNull()) {
            return;
        }

        accumulator.appendText(textNode.asText());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        CompletableFuture<Void> setupCompleteFuture = getSetupCompleteFuture(session);
        if (setupCompleteFuture != null && !setupCompleteFuture.isDone()) {
            setupCompleteFuture.completeExceptionally(exception);
        }
        removeSetupCompleteFuture(session.getId());
        clearAccumulator(session.getId());

        log.error("[GeminiLiveWebSocketHandler] handleTransportError() - Transport error | sessionId: {}, error: {}",
                session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        CompletableFuture<Void> setupCompleteFuture = getSetupCompleteFuture(session);
        if (setupCompleteFuture != null && !setupCompleteFuture.isDone()) {
            setupCompleteFuture.completeExceptionally(new IllegalStateException("Gemini setup not completed before close. status=" + status));
        }
        removeSetupCompleteFuture(session.getId());
        clearAccumulator(session.getId());

        log.info("[GeminiLiveWebSocketHandler] afterConnectionClosed() - Session closed | sessionId: {}, status: {}",
                session.getId(), status);
    }

    public CompletableFuture<Void> getSetupCompleteFuture(WebSocketSession session) {
        return setupCompleteFutureHashMap.get(session.getId());
    }

    public void removeSetupCompleteFuture(String sessionId) {
        setupCompleteFutureHashMap.remove(sessionId);
    }

    public GeminiTurnAccumulator getTurnAccumulator(WebSocketSession session) {
        return turnAccumulatorHashMap.get(session.getId());
    }

    public GeminiTurnAccumulator clearAccumulator(String sessionId) {
        return turnAccumulatorHashMap.remove(sessionId);
    }

    public static class GeminiTurnAccumulator {
        private final StringBuilder accumulatedText = new StringBuilder();
        private final ByteArrayOutputStream audioOutputStream = new ByteArrayOutputStream();
        private boolean turnComplete;

        public GeminiTurnAccumulator() {
        }

        public void appendText(String text) {
            accumulatedText.append(text);
        }

        public void appendAudio(byte[] audioBytes) throws IOException {
            audioOutputStream.write(audioBytes);
        }

        public void markTurnComplete() {
            this.turnComplete = true;
        }

        public boolean isTurnComplete() {
            return turnComplete;
        }

        public String getAccumulatedText() {
            return accumulatedText.toString();
        }

        public byte[] getAudioBytes() {
            return audioOutputStream.toByteArray();
        }
    }
}
