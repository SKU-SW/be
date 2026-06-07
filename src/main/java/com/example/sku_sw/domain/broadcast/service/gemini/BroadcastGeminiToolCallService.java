package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Arrays;

/**
 * Gemini Live Tool Call 처리 서비스
 * - Gemini setup payload에 포함할 function declaration을 생성한다.
 * - Gemini가 반환한 toolCall을 해석하고 Redis 상태를 갱신한 뒤 toolResponse를 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiToolCallService {

    private static final String FUNCTION_CALLS = "functionCalls";
    private static final String FUNCTION_RESPONSES = "functionResponses";
    private static final String TOOL_RESPONSE = "toolResponse";

    private static final String FUNCTION_SET_TALKING_STATE = "set_talking_state";
    private static final String FUNCTION_SET_RESPONSE_EMOTION = "set_response_emotion";
    private static final String ARG_IS_TALKING = "isTalking";
    private static final String ARG_EMOTION = "emotion";

    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_ARGS = "args";
    private static final String FIELD_RESPONSE = "response";
    private static final String FIELD_SUCCESS = "success";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_BROADCAST_STREAM_ID = "broadcastStreamId";

    private final ObjectMapper objectMapper;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;

    /**
     * Gemini setup payload에 포함할 talking state function declaration을 생성한다.
     * @return : talking state function declaration JSON
     */
    public ObjectNode buildTalkingStateFunctionDeclaration() {
        log.info("[BroadcastGeminiToolCallService] buildTalkingStateFunctionDeclaration() - START");

        /*
            1. Function declaration 기본 정보 생성
            - Gemini Live setup payload에 포함할 함수명과 설명을 정의한다.
         */
        ObjectNode functionDeclarationNode = objectMapper.createObjectNode();
        functionDeclarationNode.put(FIELD_NAME, FUNCTION_SET_TALKING_STATE);
        functionDeclarationNode.put(
                "description",
                "Set the broadcast character talking state when the streamer was not speaking to the AI."
        );

        /*
            2. Function parameter schema 생성
            - isTalking boolean 파라미터와 required 조건을 추가한다.
         */
        ObjectNode parametersNode = functionDeclarationNode.putObject("parameters");
        parametersNode.put("type", "object");

        ObjectNode propertiesNode = parametersNode.putObject("properties");
        ObjectNode isTalkingNode = propertiesNode.putObject(ARG_IS_TALKING);
        isTalkingNode.put("type", "boolean");
        isTalkingNode.put("description", "Whether the AI character should remain in talking mode.");

        ArrayNode requiredNode = parametersNode.putArray("required");
        requiredNode.add(ARG_IS_TALKING);

        log.info("[BroadcastGeminiToolCallService] buildTalkingStateFunctionDeclaration() - END | functionName: {}",
                FUNCTION_SET_TALKING_STATE);
        return functionDeclarationNode;
    }

    /**
     * Gemini setup payload에 포함할 response emotion function declaration을 생성한다.
     * @return : response emotion function declaration JSON
     */
    public ObjectNode buildResponseEmotionFunctionDeclaration() {
        log.info("[BroadcastGeminiToolCallService] buildResponseEmotionFunctionDeclaration() - START");

        /*
            1. Function declaration 기본 정보 생성
            - Gemini Live setup payload에 포함할 함수명과 설명을 정의한다.
         */
        ObjectNode functionDeclarationNode = objectMapper.createObjectNode();
        functionDeclarationNode.put(FIELD_NAME, FUNCTION_SET_RESPONSE_EMOTION);
        functionDeclarationNode.put(
                "description",
                "Set the current response emotion before generating the spoken answer."
        );

        /*
            2. Function parameter schema 생성
            - emotion string 파라미터와 enum, required 조건을 추가한다.
         */
        ObjectNode parametersNode = functionDeclarationNode.putObject("parameters");
        parametersNode.put("type", "object");

        ObjectNode propertiesNode = parametersNode.putObject("properties");
        ObjectNode emotionNode = propertiesNode.putObject(ARG_EMOTION);
        emotionNode.put("type", "string");
        emotionNode.put("description", "Emotion enum for the current spoken answer.");
        ArrayNode emotionEnumNode = emotionNode.putArray("enum");
        Arrays.stream(Emotion.values())
                .map(Enum::name)
                .forEach(emotionEnumNode::add);

        ArrayNode requiredNode = parametersNode.putArray("required");
        requiredNode.add(ARG_EMOTION);

        log.info("[BroadcastGeminiToolCallService] buildResponseEmotionFunctionDeclaration() - END | functionName: {}",
                FUNCTION_SET_RESPONSE_EMOTION);
        return functionDeclarationNode;
    }

    /**
     * Gemini Live에서 반환한 toolCall을 처리한다.
     * - functionCalls 배열을 순회하면서 지원하는 함수만 처리한다.
     * - 처리 결과는 Gemini Live로 toolResponse 형태로 반환한다.
     * @param geminiSession : Gemini WebSocket Session
     * @param broadcastStreamId : 방송 스트림 ID
     * @param toolCallNode : Gemini toolCall payload
     */
    public ToolCallHandleResult handleToolCall(WebSocketSession geminiSession, String broadcastStreamId, JsonNode toolCallNode) {
        log.info("[BroadcastGeminiToolCallService] handleToolCall() - START | streamId: {}", broadcastStreamId);

        /*
            1. functionCalls 배열 유효성 검증
            - toolCall에 functionCalls 배열이 없거나 비어 있으면 처리를 종료한다.
         */
        JsonNode functionCallsNode = toolCallNode.get(FUNCTION_CALLS);
        if (functionCallsNode == null || !functionCallsNode.isArray() || functionCallsNode.isEmpty()) {
            log.warn("[BroadcastGeminiToolCallService] handleToolCall() - Empty functionCalls | streamId: {}", broadcastStreamId);
            return ToolCallHandleResult.none();
        }

        /*
            2. Function call 순회 처리
            - set_talking_state는 전용 메서드로 위임하고, 그 외 함수는 실패 응답을 반환한다.
         */
        int processedCount = 0;
        boolean talkingStateHandled = false;
        boolean responseEmotionHandled = false;
        Emotion resolvedEmotion = null;
        for (JsonNode functionCallNode : functionCallsNode) {
            String functionCallId = extractTextField(functionCallNode, FIELD_ID);
            String functionName = extractTextField(functionCallNode, FIELD_NAME);

            if (FUNCTION_SET_TALKING_STATE.equals(functionName)) {
                handleSetTalkingStateFunctionCall(geminiSession, broadcastStreamId, functionCallNode);
                talkingStateHandled = true;
                processedCount += 1;
                continue;
            }

            if (FUNCTION_SET_RESPONSE_EMOTION.equals(functionName)) {
                resolvedEmotion = handleSetResponseEmotionFunctionCall(geminiSession, broadcastStreamId, functionCallNode);
                responseEmotionHandled = true;
                processedCount += 1;
                continue;
            }

            log.warn("[BroadcastGeminiToolCallService] handleToolCall() - Unsupported function | streamId: {}, functionCallId: {}, functionName: {}",
                    broadcastStreamId, functionCallId, functionName);
            sendToolResponse(
                    geminiSession,
                    functionCallId,
                    functionName,
                    buildFailureResponse("Unsupported function call")
            );
            processedCount += 1;
        }

        log.info("[BroadcastGeminiToolCallService] handleToolCall() - END | streamId: {}, processedCount: {}",
                broadcastStreamId, processedCount);
        return new ToolCallHandleResult(talkingStateHandled, responseEmotionHandled, resolvedEmotion);
    }

    /**
     * set_talking_state function call을 처리한다.
     * - args.isTalking 값을 검증한다.
     * - false인 경우 Redis의 방송 캐릭터 isTalking 값을 false로 갱신한다.
     * - 처리 결과는 Gemini Live로 toolResponse를 전송한다.
     * @param geminiSession : Gemini WebSocket Session
     * @param broadcastStreamId : 방송 스트림 ID
     * @param functionCallNode : 단건 function call payload
     */
    private void handleSetTalkingStateFunctionCall(
            WebSocketSession geminiSession,
            String broadcastStreamId,
            JsonNode functionCallNode
    ) {
        String functionCallId = extractTextField(functionCallNode, FIELD_ID);
        log.info("[BroadcastGeminiToolCallService] handleSetTalkingStateFunctionCall() - START | streamId: {}, functionCallId: {}",
                broadcastStreamId, functionCallId);

        /*
            1. Function args 검증
            - args.isTalking boolean 값이 존재하는지 확인하고, true 요청은 현재 정책상 실패 처리한다.
         */
        JsonNode argsNode = functionCallNode.get(FIELD_ARGS);
        Boolean isTalking = extractBooleanArg(argsNode, ARG_IS_TALKING);
        if (isTalking == null) {
            log.warn("[BroadcastGeminiToolCallService] handleSetTalkingStateFunctionCall() - Missing boolean arg | streamId: {}, functionCallId: {}",
                    broadcastStreamId, functionCallId);
            sendToolResponse(
                    geminiSession,
                    functionCallId,
                    FUNCTION_SET_TALKING_STATE,
                    buildFailureResponse("isTalking boolean arg is required")
            );
            return;
        }

        if (isTalking) {
            log.warn("[BroadcastGeminiToolCallService] handleSetTalkingStateFunctionCall() - Unsupported arg value | streamId: {}, functionCallId: {}, isTalking: {}",
                    broadcastStreamId, functionCallId, isTalking);
            sendToolResponse(
                    geminiSession,
                    functionCallId,
                    FUNCTION_SET_TALKING_STATE,
                    buildFailureResponse("isTalking=true is not supported")
            );
            return;
        }

        /*
            2. Redis 상태 갱신 및 성공 응답 반환
            - 방송 캐릭터의 isTalking 값을 false로 갱신하고 Gemini Live로 성공 응답을 전송한다.
         */
        try {
            broadcastRedisUtil.updateBroadcastCharacterIsTalking(broadcastStreamId, false);
            sendToolResponse(
                    geminiSession,
                    functionCallId,
                    FUNCTION_SET_TALKING_STATE,
                    buildSuccessResponse(false)
            );
            log.info("[BroadcastGeminiToolCallService] handleSetTalkingStateFunctionCall() - END | streamId: {}, functionCallId: {}, isTalking: false",
                    broadcastStreamId, functionCallId);
        } catch (Exception e) {
            log.error("[BroadcastGeminiToolCallService] handleSetTalkingStateFunctionCall() - Failed | streamId: {}, functionCallId: {}, error: {}",
                    broadcastStreamId, functionCallId, e.getMessage());
            sendToolResponse(
                    geminiSession,
                    functionCallId,
                    FUNCTION_SET_TALKING_STATE,
                    buildFailureResponse("Failed to update talking state")
            );
        }
    }

    /**
     * set_response_emotion function call을 처리한다.
     * - args.emotion 값을 파싱한다.
     * - 유효하지 않은 값이면 DEFAULT로 fallback한다.
     * - 처리 결과는 Gemini Live로 toolResponse를 전송한다.
     * @param geminiSession : Gemini WebSocket Session
     * @param broadcastStreamId : 방송 스트림 ID
     * @param functionCallNode : 단건 function call payload
     * @return : 검증 완료된 emotion 값
     */
    private Emotion handleSetResponseEmotionFunctionCall(
            WebSocketSession geminiSession,
            String broadcastStreamId,
            JsonNode functionCallNode
    ) {
        String functionCallId = extractTextField(functionCallNode, FIELD_ID);
        log.info("[BroadcastGeminiToolCallService] handleSetResponseEmotionFunctionCall() - START | streamId: {}, functionCallId: {}",
                broadcastStreamId, functionCallId);

        /*
            1. Function args 파싱 및 검증
            - args.emotion 문자열을 읽고 Emotion enum으로 변환한다.
            - 유효하지 않은 값이면 DEFAULT로 fallback한다.
         */
        JsonNode argsNode = functionCallNode.get(FIELD_ARGS);
        String emotionRaw = extractTextField(argsNode, ARG_EMOTION);
        Emotion resolvedEmotion = resolveEmotion(emotionRaw);
        if (emotionRaw == null) {
            log.warn("[BroadcastGeminiToolCallService] handleSetResponseEmotionFunctionCall() - Missing emotion arg, fallback DEFAULT | streamId: {}, functionCallId: {}",
                    broadcastStreamId, functionCallId);
        }

        /*
            2. Tool response 성공 응답 전송
            - fallback 여부와 상관없이 최종 반영값을 기준으로 성공 응답을 반환한다.
         */
        sendToolResponse(
                geminiSession,
                functionCallId,
                FUNCTION_SET_RESPONSE_EMOTION,
                buildEmotionSuccessResponse(resolvedEmotion)
        );

        log.info("[BroadcastGeminiToolCallService] handleSetResponseEmotionFunctionCall() - END | streamId: {}, functionCallId: {}, emotion: {}",
                broadcastStreamId, functionCallId, resolvedEmotion);
        return resolvedEmotion;
    }

    /**
     * Gemini Live로 toolResponse payload를 전송한다.
     * @param geminiSession : Gemini WebSocket Session
     * @param functionCallId : Gemini function call ID
     * @param functionName : Gemini function name
     * @param responseBody : function response body
     */
    private void sendToolResponse(
            WebSocketSession geminiSession,
            String functionCallId,
            String functionName,
            ObjectNode responseBody
    ) {
        if (functionCallId == null || functionCallId.isBlank() || functionName == null || functionName.isBlank()) {
            log.warn("[BroadcastGeminiToolCallService] sendToolResponse() - Missing function metadata | functionCallId: {}, functionName: {}",
                    functionCallId, functionName);
            return;
        }

        try {
            ObjectNode rootNode = objectMapper.createObjectNode();
            ObjectNode toolResponseNode = rootNode.putObject(TOOL_RESPONSE);
            ArrayNode functionResponsesNode = toolResponseNode.putArray(FUNCTION_RESPONSES);
            ObjectNode functionResponseNode = functionResponsesNode.addObject();
            functionResponseNode.put(FIELD_ID, functionCallId);
            functionResponseNode.put(FIELD_NAME, functionName);
            functionResponseNode.set(FIELD_RESPONSE, responseBody);
//            functionResponseNode.put("scheduling", "SILENT");
            String payload = objectMapper.writeValueAsString(rootNode);
            recordOutboundToolResponse(geminiSession, payload);

            geminiSession.sendMessage(new TextMessage(payload));
        } catch (Exception e) {
            log.error("[BroadcastGeminiToolCallService] sendToolResponse() - Failed | functionCallId: {}, functionName: {}, error: {}",
                    functionCallId, functionName, e.getMessage());
        }
    }

    /**
     * tool response 성공 body를 생성한다.
     * @param isTalking : 반영된 talking state
     * @return : 성공 response body
     */
    private ObjectNode buildSuccessResponse(boolean isTalking) {
        ObjectNode responseBody = objectMapper.createObjectNode();
        responseBody.put(FIELD_SUCCESS, true);
        responseBody.put(ARG_IS_TALKING, isTalking);
        return responseBody;
    }

    /**
     * emotion tool response 성공 body를 생성한다.
     * @param emotion : 반영된 감정값
     * @return : 성공 response body
     */
    private ObjectNode buildEmotionSuccessResponse(Emotion emotion) {
        ObjectNode responseBody = objectMapper.createObjectNode();
        responseBody.put(FIELD_SUCCESS, true);
        responseBody.put(ARG_EMOTION, emotion.name());
        return responseBody;
    }

    /**
     * tool response 실패 body를 생성한다.
     * @param reason : 실패 사유
     * @return : 실패 response body
     */
    private ObjectNode buildFailureResponse(String reason) {
        ObjectNode responseBody = objectMapper.createObjectNode();
        responseBody.put(FIELD_SUCCESS, false);
        responseBody.put(FIELD_REASON, reason);
        return responseBody;
    }

    private void recordOutboundToolResponse(WebSocketSession geminiSession, String payload) {
        if (geminiSession == null || geminiSession.getAttributes() == null) {
            return;
        }

        Object broadcastStreamIdValue = geminiSession.getAttributes().get(FIELD_BROADCAST_STREAM_ID);
        if (broadcastStreamIdValue == null) {
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamIdValue.toString());
        if (bundle == null || bundle.getGeminiSession() != geminiSession || bundle.getGeminiHandler() == null) {
            return;
        }

        bundle.getGeminiHandler().recordOutboundMessage("TOOL_RESPONSE", payload);
    }

    /**
     * argsNode에서 boolean 인자를 추출한다.
     * @param argsNode : function args node
     * @param fieldName : 추출할 field name
     * @return : boolean 값, 없거나 boolean이 아니면 null
     */
    private Boolean extractBooleanArg(JsonNode argsNode, String fieldName) {
        if (argsNode == null) {
            return null;
        }

        JsonNode fieldNode = argsNode.get(fieldName);
        if (fieldNode == null || fieldNode.isNull() || !fieldNode.isBoolean()) {
            return null;
        }

        return fieldNode.asBoolean();
    }

    /**
     * JsonNode의 텍스트 필드를 추출한다.
     * @param node : 대상 JsonNode
     * @param fieldName : 추출할 field name
     * @return : 텍스트 값, 없으면 null
     */
    private String extractTextField(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }

        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return null;
        }

        String value = fieldNode.asText();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 문자열 emotion 값을 Emotion enum으로 변환한다.
     * @param emotionRaw : Gemini가 전달한 emotion 문자열
     * @return : 검증된 emotion 값, 유효하지 않으면 DEFAULT
     */
    private Emotion resolveEmotion(String emotionRaw) {
        if (emotionRaw == null || emotionRaw.isBlank()) {
            return Emotion.DEFAULT;
        }

        try {
            return Emotion.valueOf(emotionRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[BroadcastGeminiToolCallService] resolveEmotion() - Invalid emotion, fallback DEFAULT | emotionRaw: {}",
                    emotionRaw);
            return Emotion.DEFAULT;
        }
    }

    public record ToolCallHandleResult(
            boolean talkingStateHandled,
            boolean responseEmotionHandled,
            Emotion emotion
    ) {
        public static ToolCallHandleResult none() {
            return new ToolCallHandleResult(false, false, null);
        }
    }
}
