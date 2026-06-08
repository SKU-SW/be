package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.BroadcastGeminiRefreshTriggerType;
import com.example.sku_sw.domain.broadcast.enums.GeminiSessionCloseReason;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiResumptionRequestedEvent;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiRefreshRequestedEvent;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiResponseService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiToolCallService;
import com.example.sku_sw.domain.character.enums.Emotion;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Gemini Live API WebSocket Handler
 * - setup 메시지를 전송하고 setupComplete 응답을 추적한다.
 * - Gemini 응답의 텍스트를 turn 단위로 누적하고, 오디오는 청크 단위로 즉시 전달한다.
 * - AI 응답 인터럽트를 지원하여 클라이언트 요청으로 Gemini 응답을 중단할 수 있다.
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
    private final String voiceName;
    private final String resumptionHandle;
    private final CompletableFuture<Void> setupCompleteFuture = new CompletableFuture<>();

    private volatile String lastSentSetupPayload;
    private volatile String lastSentPayload;
    private volatile String lastReceivedPayload;
    private volatile String lastGeminiErrorPayload;
    private volatile CloseStatus lastCloseStatus;
    private volatile CloseStatus lastLocalCloseStatus;
    private volatile GeminiSessionCloseReason lastLocalCloseReason;
    private volatile Instant connectedAt;
    private volatile Instant lastReceivedAt;
    private volatile Instant lastSentAt;
    private volatile Instant lastServerContentAt;
    private volatile Instant lastGeminiErrorAt;
    private volatile Instant lastLocalCloseRequestedAt;
    private volatile String lastReceivedFrameType;
    private volatile String lastParsedEventType;
    private volatile String lastSentEventType;
    private volatile boolean geminiSessionFirstResumptionEventInProgress;
    private volatile boolean geminiSessionFirstResumptionEnd;

    private GeminiTurnAccumulator turnAccumulator;
    private long turnSequence = 0L;

    public GeminiLiveWebSocketHandler(
            ObjectMapper objectMapper,
            BroadcastWebSocketSessionRegistry sessionRegistry,
            BroadcastGeminiResponseService broadcastGeminiResponseService,
            BroadcastGeminiToolCallService broadcastGeminiToolCallService,
            ApplicationEventPublisher applicationEventPublisher,
            String dialogueModel,
            String systemPrompt,
            String voiceName,
            String resumptionHandle
    ) {
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
        this.broadcastGeminiResponseService = broadcastGeminiResponseService;
        this.broadcastGeminiToolCallService = broadcastGeminiToolCallService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.dialogueModel = dialogueModel;
        this.systemPrompt = systemPrompt;
        this.voiceName = voiceName;
        this.resumptionHandle = resumptionHandle;
    }

    /**
     * Gemini WebSocket Session 연결이 성공했을 때
     * @param session Gemini WebSocket Session
     *
     * @throws Exception
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        connectedAt = Instant.now();
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
        functionDeclarationsNode.add(broadcastGeminiToolCallService.buildResponseEmotionFunctionDeclaration());

        setupNode.putObject("outputAudioTranscription");

        if (voiceName != null && !voiceName.isBlank()) {
            ObjectNode speechConfigNode = generationConfigNode.putObject("speechConfig");
            ObjectNode voiceConfigNode = speechConfigNode.putObject("voiceConfig");
            ObjectNode prebuiltVoiceConfigNode = voiceConfigNode.putObject("prebuiltVoiceConfig");
            prebuiltVoiceConfigNode.put("voiceName", voiceName);
        }

        // sessionResumption 이벤트가 오도록 설정
        ObjectNode sessionResumptionNode = setupNode.putObject("sessionResumption");
        if (resumptionHandle != null && !resumptionHandle.isBlank()) {
            sessionResumptionNode.put("handle", resumptionHandle);
        } else {
            ObjectNode historyConfigNode = setupNode.putObject("historyConfig");
            historyConfigNode.put("initialHistoryInClientContent", true);
        }

        String setupPayload = objectMapper.writeValueAsString(payload);
        lastSentSetupPayload = setupPayload;
        recordOutboundMessage("SETUP", setupPayload);
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
        lastReceivedAt = Instant.now();
        lastReceivedFrameType = frameType;
        lastReceivedPayload = payload;

        // 0. Json Tree 생성
        JsonNode rootNode = objectMapper.readTree(payload);

        /*
            1. Gemini WebSocket Session SetUp 요청이 완료된 경우
            - 별도의 작업을 수행하지 않고, setup 완료 여부를 결정하는 CompleteFuture 객체를 종료시킨다.
         */
        if (rootNode.has("setupComplete")) {
            lastParsedEventType = "SETUP_COMPLETE";
            setupCompleteFuture.complete(null);
            log.info("[GeminiLiveWebSocketHandler] handleTextMessage() - Setup complete | sessionId: {}", session.getId());
            return;
        }

        // 2. 에러가 반환되었을 경우 처리
        if (rootNode.has("error")) {
            lastParsedEventType = "GEMINI_ERROR";
            lastGeminiErrorAt = Instant.now();
            lastGeminiErrorPayload = payload;
            if (!setupCompleteFuture.isDone()) {
                setupCompleteFuture.completeExceptionally(new IllegalStateException(rootNode.get("error").toString()));
            }
            log.error("[GeminiLiveWebSocketHandler] handleTextMessage() - Gemini error received | sessionId: {}, payload: {}",
                    session.getId(), payload);
            return;
        }

        /*
            3. sessionResumptionUpdate를 Gemini WebSocket이 포함된 Session Bundle에 Update
            - Gemini Live WebSocket session은 일정 시간 이상 WebSocket으로 데이터를 보내지 않으면 자동으로 세션을 종료한다
            - 이때, gemini가 보내는 resumptionUpdate 이벤트의 값을 통해 기존 세션에 다시 연결할 수 있다.
            - 각 WebSocket Session이 포함되는 Session Bundle 해당 resumptionUpdate 이벤트 값을 저장해놓는다.
         */
        if (rootNode.has("sessionResumptionUpdate")) {
            lastParsedEventType = "SESSION_RESUMPTION_UPDATE";
            handleSessionResumptionUpdate(session, rootNode.get("sessionResumptionUpdate"));
            return;
        }

        /*
            4. Gemini Response에 Tool Call 결과가 온 경우 처리 (동기적 실행)
            - true가 반환되는 경우 - set_talking_state 함수가 실행되어 대화 종료
            - false가 반한되는 경우 - set_response_emotion 함수가 실행되어, 사용자에게 Emotion 메타 데이터 전송
              (이후 serverContent 또한 정상적으로 클라이언트에게 보낸다.)
         */
        JsonNode toolCallNode = rootNode.get("toolCall");
        if (toolCallNode != null) {
            // 만약 Gemini Session First Resumption이 현재 진행 중인 경우, Tool Call 처리를 수행하지 않는다.
            if (geminiSessionFirstResumptionEventInProgress) {
                lastParsedEventType = "TOOL_CALL_SUPPRESSED_FIRST_RESUMPTION";
                log.info("[GeminiLiveWebSocketHandler] handleIncomingPayload() - Tool call suppressed during first resumption event | sessionId: {}",
                        session.getId());
                return;
            }
            lastParsedEventType = "TOOL_CALL";
            if (handleToolCall(session, toolCallNode)) {
                return;
            }
        }

        /*
             ５. Gemini가 생성한 주요 응답 데이터 처리
            - textChunk, audioChunk를 추출하여 바로 클라이언트에게 보낸다.
            - 이때, textChunk값은 accumulator에 저장해둔다.
            - 인터럽트 중이면 text append와 client chunk 송신을 금지한다.
         */
        JsonNode serverContentNode = rootNode.get("serverContent");
        if (serverContentNode == null) {
            lastParsedEventType = "NON_SERVER_CONTENT";
            log.debug("[GeminiLiveWebSocketHandler] handleTextMessage() - Non-serverContent message | sessionId: {}, payload: {}",
                    session.getId(), payload);
            return;
        }
        // 만약 Gemini Session First Resumption이 현재 진행 중인 경우, 
        if (geminiSessionFirstResumptionEventInProgress) {
            lastParsedEventType = "SERVER_CONTENT_SUPPRESSED_FIRST_RESUMPTION";
            handleSuppressedFirstResumptionServerContent(session, serverContentNode);
            return;
        }

        lastParsedEventType = "SERVER_CONTENT";
        lastServerContentAt = Instant.now();

        /*
            ５-1. Gemini가 인터럽트를 승인(interrupted:true)한 경우 처리
            - VOICE_INTERRUPTED 메타데이터 전송, accumulator clear, request-flight 감소를 수행한다.
         */
        JsonNode interruptedNode = serverContentNode.get("interrupted");
        if (interruptedNode != null && interruptedNode.asBoolean(false)) {
            lastParsedEventType = "SERVER_CONTENT_INTERRUPTED";
            handleInterruptedAcknowledged(session);
            return;
        }

        GeminiTurnAccumulator accumulator = getOrCreateTurnAccumulator();

        // ５-2. 인터럽트 중이 아닐 때만 현재 accumulator에 text를 append한다.
        String textChunk = extractOutputTranscription(serverContentNode);
        if (textChunk != null && !textChunk.isBlank()) {
            if (accumulator.canAppend()) {
                accumulator.appendText(textChunk);
            } else {
                log.debug("[GeminiLiveWebSocketHandler] handleIncomingPayload() - Text append blocked (interrupt in progress) | sessionId: {}, turnNumber: {}",
                        session.getId(), accumulator.getTurnNumber());
            }
        }

        byte[] audioChunk = extractAudioChunk(serverContentNode);
        logServerContentSummary(session, serverContentNode, accumulator.getTurnNumber(), textChunk, audioChunk);

        // ５-3. 인터럽트 중이 아닐 때만 client chunk를 송신한다.
        if (!accumulator.isInterruptingOrInterrupted()
                && ((textChunk != null && !textChunk.isBlank()) || audioChunk.length > 0)) {
            dispatchStreamingChunk(session, accumulator.getTurnNumber(), textChunk, audioChunk, accumulator.getEmotion());
        }

        /*
             6. Gemini가 이번 turn 응답을 종료했다고 신호한 경우 처리
            - 인터럽트 중이면 turnComplete를 무시하고 처리하지 않는다.
            - 정상 흐름일 때만 accumulator 데이터로 CompletedGeminiTurn 객체 생성 후 처리한다.
         */
        JsonNode turnCompleteNode = serverContentNode.get("turnComplete");
        if (turnCompleteNode != null && turnCompleteNode.asBoolean(false)) {
            lastParsedEventType = "SERVER_CONTENT_TURN_COMPLETE";
            if (accumulator.isInterruptingOrInterrupted()) {
                log.debug("[GeminiLiveWebSocketHandler] handleIncomingPayload() - turnComplete ignored (interrupt in progress) | sessionId: {}, turnNumber: {}",
                        session.getId(), accumulator.getTurnNumber());
                return;
            }

            accumulator.markCompleted();
            CompletedGeminiTurn completedTurn = new CompletedGeminiTurn(
                    accumulator.getTurnNumber(),
                    accumulator.getAccumulatedText(),
                    accumulator.getEmotion()
            );
            clearAccumulator();

            dispatchCompletedTurn(session, completedTurn);

            log.info("[GeminiLiveWebSocketHandler] handleTextMessage() - Turn complete | sessionId: {}, turnNumber: {}, textLength: {}",
                    session.getId(), completedTurn.turnNumber(), completedTurn.accumulatedText().length());
        }
    }

    private void dispatchStreamingChunk(WebSocketSession geminiSession, Long turnNumber, String textChunk, byte[] audioChunk, Emotion emotion) {
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
                audioChunk,
                emotion
        );
    }

    /**
     * Gemini가 serverContent.interrupted == true로 최종 인터럽트를 승인했을 때(Gemini가 더이상 응답을 생성하지 않을 때) 호출하는 함수
     * - VOICE_INTERRUPTED 메타데이터 전송
     * - accumulator clear
     * - request-flight 감소
     * @param geminiSession Gemini WebSocket 세션
     */
    private void handleInterruptedAcknowledged(WebSocketSession geminiSession) {
        /*
            1. 현재 GeminiLiveWebSocketHandler가 갖고 있는 turnAccumulator 검증
            - turnAccumulator가 null인지, interrupt중인지 interrupt가 종료된 상태인지
         */
        if (turnAccumulator == null) {
            log.warn("[GeminiLiveWebSocketHandler] handleInterruptedAcknowledged() - No active accumulator | sessionId: {}",
                    geminiSession.getId());
            return;
        }
        if (!turnAccumulator.isInterruptingOrInterrupted()) {
            log.warn("[GeminiLiveWebSocketHandler] handleInterruptedAcknowledged() - Interrupt ack ignored because turn is not interrupted | sessionId: {}, turnNumber: {}, status: {}",
                    geminiSession.getId(), turnAccumulator.getTurnNumber(), turnAccumulator.getStatus());
            return;
        }

        /*
            2. Interrupt 시킬 현재 Turn Number와 Session Bundle을 가져온다. 
         */
        Long currentTurnNumber = turnAccumulator.getTurnNumber();
        String broadcastStreamId = resolveBroadcastStreamId(geminiSession);
        if (broadcastStreamId == null) {
            log.warn("[GeminiLiveWebSocketHandler] handleInterruptedAcknowledged() - Broadcast stream not found | sessionId: {}",
                    geminiSession.getId());
            return;
        }
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || bundle.getGeminiSession() != geminiSession) {
            log.warn("[GeminiLiveWebSocketHandler] handleInterruptedAcknowledged() - Bundle mismatch | sessionId: {}, streamId: {}",
                    geminiSession.getId(), broadcastStreamId);
            return;
        }

        /*
            3. 클라이언트에게 VOICE_INTERRUPTED 메타데이터를 전송하고, Session Bundle의 request-flight 값을 감소시킨다.
            - Redis에 인터럽트된 텍스트 데이터 저장은 BroadcastWebSocketHandler에서 이미 수행되었기 때문에, 클라이언트로의 메타데이터 전송과 flight 감소만 수행한다.
         */
        broadcastGeminiResponseService.handleInterruptedTurn(
                geminiSession,
                broadcastStreamId,
                bundle.getGeneration(),
                turnAccumulator,
                bundle
        );

        // 4. 쓸모가 다한 기존 Interrupted Accumulator를 Clear 시킨다.
        clearAccumulator();

        log.info("[GeminiLiveWebSocketHandler] handleInterruptedAcknowledged() - Interrupt acknowledged | streamId: {}, turnNumber: {}",
                broadcastStreamId, currentTurnNumber);
    }

    /**
     * Gemini 응답 turn이 완료되었을 때 실행하는 함수
     * 비동기적으로 클라이언트에게 해당 응답 turn의 메타데이터를 보낸다.
     * @param geminiSession
     * @param completedTurn
     */
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
                completedTurn.emotion(),
                bundle
        );
    }

    /**
     * Gemini Response에 Emotion이 왔을 때 수행할 작업
     * - 해당 gemini Session에 해당하는 bundle을 조회하고 클라이언트에게 감정 데이터를 보낸다.
     * @param geminiSession
     * @param turnNumber
     * @param emotion
     */
    private void dispatchEmotionUpdate(WebSocketSession geminiSession, Long turnNumber, Emotion emotion) {
        String broadcastStreamId = resolveBroadcastStreamId(geminiSession);
        if (broadcastStreamId == null) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchEmotionUpdate() - Broadcast stream not found | sessionId: {}, turnNumber: {}",
                    geminiSession.getId(), turnNumber);
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || !bundle.canProcessGeminiResponse()) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchEmotionUpdate() - Bundle not ready | streamId: {}, sessionId: {}, turnNumber: {}",
                    broadcastStreamId, geminiSession.getId(), turnNumber);
            return;
        }

        if (bundle.getGeminiSession() != geminiSession) {
            log.warn("[GeminiLiveWebSocketHandler] dispatchEmotionUpdate() - Gemini session mismatch | streamId: {}, sessionId: {}, turnNumber: {}",
                    broadcastStreamId, geminiSession.getId(), turnNumber);
            return;
        }

        broadcastGeminiResponseService.forwardEmotionUpdate(
                geminiSession,
                broadcastStreamId,
                bundle.getGeneration(),
                turnNumber,
                emotion
        );
    }

    /**
     * First Resumption 진행 중에 ServerContent 이벤트가 온 경우의 처리 함수
     * @param geminiSession
     * @param serverContentNode
     */
    private void handleSuppressedFirstResumptionServerContent(WebSocketSession geminiSession, JsonNode serverContentNode) {
        log.info("[GeminiLiveWebSocketHandler] handleSuppressedFirstResumptionServerContent() - START | sessionId: {}",
                geminiSession.getId());

        /*
            1. first resumption event 진행 중 수신한 serverContent는 일반 응답처럼 처리하지 않는다.
            - 클라이언트 스트리밍, Redis 저장, turnComplete metadata 전송을 모두 생략한다.
         */
        JsonNode interruptedNode = serverContentNode.get("interrupted");
        if (interruptedNode != null && interruptedNode.asBoolean(false)) {
            finishFirstResumptionEvent(geminiSession, "SUPPRESSED_INTERRUPTED");
            log.info("[GeminiLiveWebSocketHandler] handleSuppressedFirstResumptionServerContent() - END | sessionId: {}, action: interrupted",
                    geminiSession.getId());
            return;
        }

        JsonNode turnCompleteNode = serverContentNode.get("turnComplete");
        if (turnCompleteNode != null && turnCompleteNode.asBoolean(false)) {
            finishFirstResumptionEvent(geminiSession, "SUPPRESSED_TURN_COMPLETE");
            log.info("[GeminiLiveWebSocketHandler] handleSuppressedFirstResumptionServerContent() - END | sessionId: {}, action: turn_complete",
                    geminiSession.getId());
            return;
        }

        log.info("[GeminiLiveWebSocketHandler] handleSuppressedFirstResumptionServerContent() - END | sessionId: {}, action: chunk_suppressed",
                geminiSession.getId());
    }

    private void finishFirstResumptionEvent(WebSocketSession geminiSession, String reason) {
        BroadcastWebSocketSessionBundle bundle = resolveBundle(geminiSession);
        if (bundle != null && bundle.getGeminiSession() == geminiSession && bundle.getRequestFlightCountValue() > 0) {
            broadcastGeminiResponseService.handleGeminiTurnFinished(
                    resolveBroadcastStreamId(geminiSession),
                    bundle.getGeneration(),
                    bundle
            );
        }
        clearAccumulator();
        clearFirstResumptionEventInProgress();
        log.info("[GeminiLiveWebSocketHandler] finishFirstResumptionEvent() - Finished | sessionId: {}, reason: {}, end: {}",
                geminiSession != null ? geminiSession.getId() : null, reason, geminiSessionFirstResumptionEnd);
    }

    private String resolveBroadcastStreamId(WebSocketSession geminiSession) {
        if (geminiSession == null || geminiSession.getAttributes() == null) {
            return null;
        }
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

    private boolean handleToolCall(WebSocketSession geminiSession, JsonNode toolCallNode) {
        String broadcastStreamId = resolveBroadcastStreamId(geminiSession);
        if (broadcastStreamId == null) {
            log.warn("[GeminiLiveWebSocketHandler] handleToolCall() - Broadcast stream not found | sessionId: {}",
                    geminiSession.getId());
            return true;
        }

        // 1. Session Bundle 객체 가져오기 & 검증
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || !bundle.canProcessGeminiResponse()) {
            log.warn("[GeminiLiveWebSocketHandler] handleToolCall() - Bundle not ready | streamId: {}, sessionId: {}",
                    broadcastStreamId, geminiSession.getId());
            return true;
        }
        if (bundle.getGeminiSession() != geminiSession) {
            log.warn("[GeminiLiveWebSocketHandler] handleToolCall() - Gemini session mismatch | streamId: {}, sessionId: {}",
                    broadcastStreamId, geminiSession.getId());
            return true;
        }

        // 2. Tool Call을 처리한 결과를 담은 DTO 객체를 가져온다. 해당 Tool Call 처리는 동기적으로 수행된다.
        BroadcastGeminiToolCallService.ToolCallHandleResult result = broadcastGeminiToolCallService.handleToolCall(
                geminiSession,
                broadcastStreamId,
                toolCallNode
        );
        /*
             3. Tool Call 처리 결과에 Emotion 처리 결과가 있는 경우
            - accumulator에 emotion 결과를 설정 (결과에 emotion이 없는 경우 DEFAULT로 fallback 설정
            - 인터럽트 중이 아니면 해당 Gemini 응답에 Emotion이 왔을 때 수행할 작업을 수행한다.
              (인퍼럽트 중이어도 emotion 값은 업데이트하되, 클라이언트에게 전송하는 emotion metadata만 막는다.)
         */
        if (result.responseEmotionHandled()) {
            GeminiTurnAccumulator accumulator = getOrCreateTurnAccumulator();
            Emotion resolvedEmotion = result.emotion() == null ? Emotion.DEFAULT : result.emotion();
            accumulator.updateEmotion(resolvedEmotion);

            // 인터럽트 중이 아닐 때만 emotion metadata를 클라이언트에 전송한다.
            if (!accumulator.isInterruptingOrInterrupted()) {
                dispatchEmotionUpdate(geminiSession, accumulator.getTurnNumber(), resolvedEmotion);
            } else {
                log.debug("[GeminiLiveWebSocketHandler] handleToolCall() - Emotion dispatch skipped (interrupt in progress) | sessionId: {}, turnNumber: {}",
                        geminiSession.getId(), accumulator.getTurnNumber());
            }
            return false;
        }

        return result.talkingStateHandled();
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
        clearFirstResumptionEventInProgress();
        clearAccumulator();
        handleTerminatedGeminiTurn(session);

        log.error("[GeminiLiveWebSocketHandler] handleTransportError() - Transport error | sessionId: {}, diagnostics: {}",
                session.getId(), getTerminationDiagnostics(session, "TRANSPORT_ERROR", exception), exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        lastCloseStatus = status;
        if (!setupCompleteFuture.isDone()) {
            setupCompleteFuture.completeExceptionally(new IllegalStateException("Gemini setup not completed before close. status=" + status));
            log.error("[GeminiLiveWebSocketHandler] afterConnectionClosed() - Session closed before setupComplete | sessionId: {}, diagnostics: {}",
                    session.getId(), getTerminationDiagnostics(session, "CLOSED_BEFORE_SETUP_COMPLETE", null));
        }

        clearFirstResumptionEventInProgress();

        if (lastLocalCloseReason != null) {
            cleanupClosedGeminiSession(session);
            log.info("[GeminiLiveWebSocketHandler] afterConnectionClosed() - Session closed by local request | sessionId: {}, status: {}, diagnostics: {}",
                    session.getId(), status, getTerminationDiagnostics(session, "LOCAL_CLOSE", null));
            return;
        }

        BroadcastGeminiResumptionRequestedEvent event = BroadcastGeminiResumptionRequestedEvent.builder()
                .closedSession(session)
                .closeStatus(status)
                .fallbackCleanup(() -> cleanupClosedGeminiSession(session))
                .build();
        applicationEventPublisher.publishEvent(event);

        log.warn("[GeminiLiveWebSocketHandler] afterConnectionClosed() - Session closed by remote/unknown cause | sessionId: {}, status: {}, diagnostics: {}",
                session.getId(), status, getTerminationDiagnostics(session, "REMOTE_OR_UNKNOWN_CLOSE", null));
    }

    public boolean hasActiveAccumulator() {
        return turnAccumulator != null;
    }

    private void cleanupClosedGeminiSession(WebSocketSession session) {
        clearAccumulator();
        handleTerminatedGeminiTurn(session);
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
        if (bundle.getGeminiSessionRefreshRequested().get()) {
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

    /**
     * Gemini WebSocket outbound 메시지 전송 시각과 payload를 기록한다.
     * @param eventType : outbound 이벤트 타입
     * @param payload : outbound payload
     */
    public void recordOutboundMessage(String eventType, String payload) {
        lastSentAt = Instant.now();
        lastSentEventType = eventType;
        lastSentPayload = payload;
    }

    /**
     * Gemini keepalive ping 전송 시각을 session attribute에 기록한다.
     */
    public void markLocalClose(CloseStatus closeStatus, GeminiSessionCloseReason closeReason) {
        lastLocalCloseRequestedAt = Instant.now();
        lastLocalCloseStatus = closeStatus;
        lastLocalCloseReason = closeReason;
    }

    public String getSetupFailureDiagnostics() {
        return getTerminationDiagnostics(null, "SETUP_FAILURE_DIAGNOSTICS", null);
    }

    /**
     * Gemini 세션 종료/실패 시점의 진단 정보를 문자열로 반환한다.
     * @param session : Gemini WebSocket 세션
     * @param terminationTrigger : 종료 트리거
     * @param throwable : 예외(없으면 null)
     * @return : 종료 진단 문자열
     */
    public String getTerminationDiagnostics(WebSocketSession session, String terminationTrigger, Throwable throwable) {
        BroadcastWebSocketSessionBundle bundle = resolveBundle(session);
        Long generation = resolveGeneration(session, bundle);
        String sessionId = session != null ? session.getId() : null;
        String streamId = resolveBroadcastStreamId(session);
        String clientSessionId = resolveClientSessionId(bundle);
        Instant now = Instant.now();

        return String.format(
                "trigger=%s, streamId=%s, generation=%s, sessionId=%s, clientSessionId=%s, isCurrentGeminiSession=%s, bundleStatus=%s, requestFlightCount=%s, refreshRequested=%s, refreshInProgress=%s, refreshRetryCount=%s, connectedAt=%s, lastReceivedAt=%s, lastSentAt=%s, lastServerContentAt=%s, lastGeminiErrorAt=%s, lastLocalCloseRequestedAt=%s, sessionAgeMs=%s, idleSinceReceiveMs=%s, idleSinceSendMs=%s, terminationPhase=%s, lastReceivedFrameType=%s, lastParsedEventType=%s, lastSentEventType=%s, lastCloseStatus=%s, lastLocalCloseStatus=%s, lastLocalCloseReason=%s, throwableType=%s, throwableMessage=%s, lastReceivedPayload=%s, lastGeminiErrorPayload=%s, lastSentPayload=%s, lastSentSetupPayload=%s",
                terminationTrigger,
                streamId,
                generation,
                sessionId,
                clientSessionId,
                isCurrentGeminiSession(bundle, session),
                bundle != null ? bundle.getStatus() : null,
                bundle != null ? bundle.getRequestFlightCountValue() : null,
                bundle != null ? bundle.isGeminiSessionRefreshRequested() : null,
                bundle != null ? bundle.getGeminiSessionRefreshInProgress() : null,
                bundle != null ? bundle.getRefreshRetryCountValue() : null,
                connectedAt,
                lastReceivedAt,
                lastSentAt,
                lastServerContentAt,
                lastGeminiErrorAt,
                lastLocalCloseRequestedAt,
                calculateDurationMillis(connectedAt, now),
                calculateDurationMillis(lastReceivedAt, now),
                calculateDurationMillis(lastSentAt, now),
                resolveTerminationPhase(bundle),
                lastReceivedFrameType,
                lastParsedEventType,
                lastSentEventType,
                lastCloseStatus,
                lastLocalCloseStatus,
                lastLocalCloseReason,
                throwable != null ? throwable.getClass().getName() : null,
                throwable != null ? throwable.getMessage() : null,
                abbreviate(lastReceivedPayload),
                abbreviate(lastGeminiErrorPayload),
                abbreviate(lastSentPayload),
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

    private BroadcastWebSocketSessionBundle resolveBundle(WebSocketSession session) {
        String broadcastStreamId = resolveBroadcastStreamId(session);
        if (broadcastStreamId == null) {
            return null;
        }
        return sessionRegistry.getSessionBundle(broadcastStreamId);
    }

    private Long resolveGeneration(WebSocketSession session, BroadcastWebSocketSessionBundle bundle) {
        if (bundle != null) {
            return bundle.getGeneration();
        }
        if (session == null || session.getAttributes() == null) {
            return null;
        }

        Object generationValue = session.getAttributes().get(WebSocketAttributes.SESSION_GENERATION.getValue());
        if (generationValue instanceof Long longValue) {
            return longValue;
        }
        if (generationValue instanceof Number number) {
            return number.longValue();
        }
        if (generationValue instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveClientSessionId(BroadcastWebSocketSessionBundle bundle) {
        if (bundle == null || bundle.getClientSession() == null) {
            return null;
        }
        return bundle.getClientSession().getId();
    }

    private boolean isCurrentGeminiSession(BroadcastWebSocketSessionBundle bundle, WebSocketSession session) {
        return bundle != null && session != null && bundle.getGeminiSession() == session;
    }

    private String resolveTerminationPhase(BroadcastWebSocketSessionBundle bundle) {
        if (!setupCompleteFuture.isDone()) {
            return "SETUP";
        }
        if (bundle == null || bundle.getStatus() == null) {
            return "UNKNOWN";
        }
        return bundle.getStatus().name();
    }

    private Long calculateDurationMillis(Instant start, Instant end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }

    /**
     * Gemini로부터 Session Resumption Update 이벤트가 왔을 때, 해당 데이터를 파싱해 해당 Gemini Session이 포함된 Session Bundle에 저장한다.
     * @param session
     * @param sessionResumptionUpdateNode
     */
    private void handleSessionResumptionUpdate(WebSocketSession session, JsonNode sessionResumptionUpdateNode) {
        String broadcastStreamId = resolveBroadcastStreamId(session);
        if (broadcastStreamId == null || sessionResumptionUpdateNode == null) {
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || bundle.getGeminiSession() != session) {
            return;
        }

        /*
            1. Json newHandle 필드
            - 새롭게 Gemini Session을 생성할 때, 해당 값을 Setup에 지정해주면 남아있는 기존 세션에 다시 연결할 수 있다.
         */
        String newHandle = null;
        JsonNode newHandleNode = sessionResumptionUpdateNode.get("newHandle");
        if (newHandleNode != null && !newHandleNode.isNull()) {
            newHandle = newHandleNode.asText();
        }

        /*
            2. Json resumable 필드
            - 해당 Session이 재연결이 가능한지 여부를 담고 있는 Boolean 필드
         */
        boolean resumable = sessionResumptionUpdateNode.path("resumable").asBoolean(false);
        bundle.updateGeminiSessionResumptionMetadata(newHandle, resumable, Instant.now());
        if (!geminiSessionFirstResumptionEnd) {
            geminiSessionFirstResumptionEnd = true;
            if (geminiSessionFirstResumptionEventInProgress) {
                finishFirstResumptionEvent(session, "FIRST_SESSION_RESUMPTION_UPDATE");
            }
        }

        log.info("[GeminiLiveWebSocketHandler] handleSessionResumptionUpdate() - Resumption metadata updated | streamId: {}, generation: {}, sessionId: {}, resumable: {}, handle: {}",
                broadcastStreamId,
                bundle.getGeneration(),
                session.getId(),
                resumable,
                newHandle);
    }

    public void markFirstResumptionEventStarted() {
        geminiSessionFirstResumptionEventInProgress = true;
        geminiSessionFirstResumptionEnd = false;
    }

    public void clearFirstResumptionEventInProgress() {
        geminiSessionFirstResumptionEventInProgress = false;
    }

    public boolean isGeminiSessionFirstResumptionEventInProgress() {
        return geminiSessionFirstResumptionEventInProgress;
    }

    public boolean isGeminiSessionFirstResumptionEnd() {
        return geminiSessionFirstResumptionEnd;
    }

    public record CompletedGeminiTurn(
            Long turnNumber,
            String accumulatedText,
            Emotion emotion
    ) {
    }

    /**
     * Gemini 응답 turn 단위 데이터 누적기
     * - 텍스트와 감정을 누적하고, turn의 상태를 추적한다.
     * - 인터럽트 상태 전환을 지원한다 (STREAMING → INTERRUPTING → INTERRUPTED → clear).
     */
    public static class GeminiTurnAccumulator {

        /** Gemini 응답 turn의 현재 상태 */
        public enum GeminiTurnStatus {
            STREAMING,      // 정상 스트리밍 중
            INTERRUPTING,   // 인터럽트 요청 접수 (처리 중)
            INTERRUPTED,    // 인터럽트 완료 (Gemini 전송까지 완료)
            COMPLETED       // 정상 turn 완료
        }

        private final Long turnNumber;
        private final StringBuilder accumulatedText = new StringBuilder();
        private Emotion emotion = Emotion.TALKING;
        private volatile GeminiTurnStatus status = GeminiTurnStatus.STREAMING;
        private volatile Long interruptedCursorId;
        private volatile String interruptedText;

        public GeminiTurnAccumulator(Long turnNumber) {
            this.turnNumber = turnNumber;
        }

        public void appendText(String text) {
            accumulatedText.append(text);
        }

        public void updateEmotion(Emotion emotion) {
            this.emotion = emotion == null ? Emotion.DEFAULT : emotion;
        }

        public Long getTurnNumber() {
            return turnNumber;
        }

        public String getAccumulatedText() {
            return accumulatedText.toString();
        }

        public Emotion getEmotion() {
            return emotion;
        }

        // ---- 인터럽트 관련 상태 메서드 ----

        /**
         * 현재 인터럽트가 진행 중이거나 완료된 상태인지 확인한다.
         * @return INTERRUPTING 또는 INTERRUPTED 상태이면 true
         */
        public boolean isInterruptingOrInterrupted() {
            return status == GeminiTurnStatus.INTERRUPTING || status == GeminiTurnStatus.INTERRUPTED;
        }

        /**
         * text append가 가능한 상태인지 확인한다.
         * @return STREAMING 상태이면 true (정상 스트리밍 중에만 append 허용)
         */
        public boolean canAppend() {
            return status == GeminiTurnStatus.STREAMING;
        }

        /** 상태를 INTERRUPTING으로 전환한다. */
        public void markInterrupting() {
            this.status = GeminiTurnStatus.INTERRUPTING;
        }

        /** 상태를 INTERRUPTED로 전환한다. */
        public void markInterrupted() {
            this.status = GeminiTurnStatus.INTERRUPTED;
        }

        /** 상태를 COMPLETED로 전환한다. */
        public void markCompleted() {
            this.status = GeminiTurnStatus.COMPLETED;
        }

        /**
         * 현재 상태를 반환한다.
         * @return GeminiTurnStatus 값
         */
        public GeminiTurnStatus getStatus() {
            return status;
        }

        /**
         * 인터럽트로 Redis에 저장된 대화 cursorId를 설정한다.
         * @param cursorId BroadcastInfo cursorId
         */
        public void setInterruptedCursorId(Long cursorId) {
            this.interruptedCursorId = cursorId;
        }

        /**
         * 인터럽트로 Redis에 저장된 대화 cursorId를 반환한다.
         * @return BroadcastInfo cursorId
         */
        public Long getInterruptedCursorId() {
            return interruptedCursorId;
        }

        /**
         * 인터럽트로 저장된 최종 텍스트("[응답 중단됨]" 포함)를 설정한다.
         * @param text 저장된 인터럽트 텍스트
         */
        public void setInterruptedText(String text) {
            this.interruptedText = text;
        }

        /**
         * 인터럽트로 저장된 최종 텍스트를 반환한다.
         * @return 인터럽트 텍스트 (null 가능)
         */
        public String getInterruptedText() {
            return interruptedText;
        }
    }
}
