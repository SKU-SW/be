package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.service.BroadcastConnectionTimeoutService;
import com.example.sku_sw.domain.broadcast.service.BroadcastDialogueCompactionService;
import com.example.sku_sw.domain.broadcast.service.BroadcastMessageService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiBootstrapService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiLiveService;
import com.example.sku_sw.domain.broadcast.util.BroadcastPromptBuilder;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BroadcastWebSocketStartIntegrationTest {

    private static final String BROADCAST_STREAM_ID = "stream-123";
    private static final Long USER_ID = 1L;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;

    @Mock
    private BroadcastMessageService broadcastMessageService;

    @Mock
    private BroadcastDialogueCompactionService broadcastDialogueCompactionService;

    @Mock
    private BroadcastRepository broadcastRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private BroadcastGeminiLiveService broadcastGeminiLiveService;

    @Mock
    private BroadcastPromptBuilder broadcastPromptBuilder;

    private ObjectMapper objectMapper;
    private BroadcastWebSocketSessionRegistry sessionRegistry;
    private BroadcastGeminiBootstrapService broadcastGeminiBootstrapService;
    private BroadcastWebSocketHandler broadcastWebSocketHandler;

    @BeforeEach
    void setUp() {
        // 테스트마다 독립적인 직렬화기와 세션 레지스트리를 새로 준비한다.
        objectMapper = new ObjectMapper();
        sessionRegistry = new BroadcastWebSocketSessionRegistry();

        // 실제 운영 흐름과 동일하게 bootstrap 서비스와 handler를 수동으로 조립한다.
        broadcastGeminiBootstrapService = new BroadcastGeminiBootstrapService(
                objectMapper,
                sessionRegistry,
                broadcastGeminiLiveService,
                broadcastRedisUtil,
                broadcastPromptBuilder
        );
        broadcastWebSocketHandler = new BroadcastWebSocketHandler(
                objectMapper,
                broadcastRedisUtil,
                broadcastConnectionTimeoutService,
                sessionRegistry,
                broadcastMessageService,
                broadcastGeminiBootstrapService,
                broadcastDialogueCompactionService,
                broadcastRepository,
                transactionTemplate
        );
    }

    @Test
    @DisplayName("방송 시작 실패 - Redis 방송 캐릭터 정보가 없으면 클라이언트 세션을 종료하고 bootstrap을 시작하지 않는다")
    void 방송_시작_실패_Redis_방송_캐릭터_정보_없음() throws Exception {
        // given
        // 연결 직후 핸들러가 읽는 사용자/스트림 속성만 가진 클라이언트 세션 mock을 만든다.
        WebSocketSession clientSession = mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        clientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(clientSession.getAttributes()).willReturn(clientAttributes);
        given(clientSession.isOpen()).willReturn(true);

        // Redis에 방송 캐릭터 정보가 없는 종료 경로를 구성한다.
        given(broadcastRedisUtil.hasBroadcastCharacterValue(BROADCAST_STREAM_ID)).willReturn(false);

        // when
        broadcastWebSocketHandler.afterConnectionEstablished(clientSession);

        // then
        verify(clientSession, times(1)).close(any(CloseStatus.class));
        verify(broadcastGeminiLiveService, never()).connectGeminiApiWebSocketAsync(any(), any());
        assertThat(sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID)).isNull();
    }

    @Test
    @DisplayName("방송 시작 실패 - Gemini bootstrap 예외 발생 시 에러 메시지 전송 후 세션을 종료한다")
    void 방송_시작_실패_Gemini_bootstrap_예외() throws Exception {
        // given
        // 연결 성공 이후 상태 메시지와 에러 메시지를 모두 받을 수 있는 클라이언트 세션 mock을 만든다.
        WebSocketSession clientSession = mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        clientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(clientSession.getAttributes()).willReturn(clientAttributes);
        given(clientSession.isOpen()).willReturn(true);

        // Gemini 비동기 연결이 즉시 예외로 끝나는 상황을 구성한다.
        CompletableFuture<WebSocketSession> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("gemini bootstrap failed"));
        given(broadcastRedisUtil.hasBroadcastCharacterValue(BROADCAST_STREAM_ID)).willReturn(true);
        stubPromptDependencies();
        given(broadcastGeminiLiveService.connectGeminiApiWebSocketAsync(any(), any())).willReturn(failedFuture);

        // when
        broadcastWebSocketHandler.afterConnectionEstablished(clientSession);

        // then
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(clientSession, times(2)).sendMessage(captor.capture());
        verify(clientSession, times(1)).close(any(CloseStatus.class));

        // 먼저 GEMINI_CONNECTING 상태가 나가고, 이후 에러 응답이 내려가는지 확인한다.
        List<TextMessage> messages = captor.getAllValues();
        JsonNode connectingPayload = objectMapper.readTree(messages.get(0).getPayload());
        JsonNode errorPayload = objectMapper.readTree(messages.get(1).getPayload());

        assertThat(connectingPayload.get("status").asText()).isEqualTo(WebSocketSessionBundleStatus.GEMINI_CONNECTING.name());
        assertThat(errorPayload.get("error").asText()).isEqualTo("ERROR");
        assertThat(errorPayload.get("message").asText()).isEqualTo(BroadcastErrorCode.GEMINI_RESPONSE_FAILED.getMessage());
        assertThat(sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID)).isNull();
    }

    @Test
    @DisplayName("방송 시작 예외 처리 - 이전 연결이 stale 상태가 되면 이전 Gemini 세션을 폐기하고 최신 세션만 READY 처리한다")
    void 방송_시작_예외처리_이전_연결_stale_상태_폐기() throws Exception {
        // given
        // 첫 번째 연결은 이후 새 연결에 의해 교체될 수 있도록 최소 속성만 가진 mock으로 구성한다.
        WebSocketSession firstClientSession = mock(WebSocketSession.class);
        Map<String, Object> firstClientAttributes = new HashMap<>();
        firstClientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        firstClientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(firstClientSession.getAttributes()).willReturn(firstClientAttributes);
        given(firstClientSession.isOpen()).willReturn(true);

        // 두 번째 연결은 최종적으로 READY 상태가 될 현재 세션으로 준비한다.
        WebSocketSession secondClientSession = mock(WebSocketSession.class);
        Map<String, Object> secondClientAttributes = new HashMap<>();
        secondClientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        secondClientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(secondClientSession.getAttributes()).willReturn(secondClientAttributes);
        given(secondClientSession.isOpen()).willReturn(true);

        // 첫 번째 Gemini 세션은 stale 완료 시 즉시 폐기되는지 검증 대상으로만 사용한다.
        WebSocketSession firstGeminiSession = mock(WebSocketSession.class);

        // 두 번째 Gemini 세션은 최종 bundle에 등록되는 현재 세션으로 사용한다.
        WebSocketSession secondGeminiSession = mock(WebSocketSession.class);

        // 두 연결의 bootstrap 완료 시점을 제어하기 위해 Future를 직접 준비한다.
        CompletableFuture<WebSocketSession> firstGeminiFuture = new CompletableFuture<>();
        CompletableFuture<WebSocketSession> secondGeminiFuture = new CompletableFuture<>();
        given(broadcastRedisUtil.hasBroadcastCharacterValue(BROADCAST_STREAM_ID)).willReturn(true);
        stubPromptDependencies();
        given(broadcastGeminiLiveService.connectGeminiApiWebSocketAsync(any(), any())).willReturn(firstGeminiFuture, secondGeminiFuture);

        // when
        broadcastWebSocketHandler.afterConnectionEstablished(firstClientSession);
        Long firstGeneration = (Long) firstClientSession.getAttributes().get(WebSocketAttributes.SESSION_GENERATION.getValue());

        broadcastWebSocketHandler.afterConnectionEstablished(secondClientSession);
        Long secondGeneration = (Long) secondClientSession.getAttributes().get(WebSocketAttributes.SESSION_GENERATION.getValue());

        // 첫 번째 bootstrap은 늦게 성공시켜 stale 처리되게 하고, 두 번째 bootstrap이 최종 READY를 만든다.
        firstGeminiFuture.complete(firstGeminiSession);
        secondGeminiFuture.complete(secondGeminiSession);

        // then
        verify(firstClientSession, atLeastOnce()).close(any(CloseStatus.class));
        verify(broadcastGeminiLiveService, times(1)).closeGeminiSessionQuietly(firstGeminiSession);

        // 최신 generation만 유지되고, 현재 bundle은 두 번째 Gemini 세션으로 READY 상태여야 한다.
        BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID);
        assertThat(currentBundle).isNotNull();
        assertThat(currentBundle.getGeneration()).isEqualTo(secondGeneration);
        assertThat(currentBundle.getGeneration()).isNotEqualTo(firstGeneration);
        assertThat(currentBundle.getStatus()).isEqualTo(WebSocketSessionBundleStatus.READY);
        assertThat(currentBundle.getGeminiSession()).isEqualTo(secondGeminiSession);
    }

    @Test
    @DisplayName("방송 시작 예외 처리 - 이전 연결의 Gemini bootstrap 실패가 늦게 도착해도 현재 번들은 유지된다")
    void 방송_시작_예외처리_이전_연결_bootstrap_실패_지연도착() throws Exception {
        // given
        // 첫 번째 연결은 이후 실패 이벤트가 늦게 도착할 대상이므로 현재 핸들러가 참조하는 속성만 준비한다.
        WebSocketSession firstClientSession = mock(WebSocketSession.class);
        Map<String, Object> firstClientAttributes = new HashMap<>();
        firstClientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        firstClientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(firstClientSession.getAttributes()).willReturn(firstClientAttributes);
        given(firstClientSession.isOpen()).willReturn(true);

        // 두 번째 연결은 실제 현재 bundle로 남아 있어야 하므로 상태 메시지를 보낼 수 있게 준비한다.
        WebSocketSession secondClientSession = mock(WebSocketSession.class);
        Map<String, Object> secondClientAttributes = new HashMap<>();
        secondClientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        secondClientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(secondClientSession.getAttributes()).willReturn(secondClientAttributes);
        given(secondClientSession.isOpen()).willReturn(true);

        // 첫 번째와 두 번째 bootstrap의 완료 순서를 제어하기 위한 Future를 준비한다.
        CompletableFuture<WebSocketSession> firstGeminiFuture = new CompletableFuture<>();
        CompletableFuture<WebSocketSession> secondGeminiFuture = new CompletableFuture<>();
        given(broadcastRedisUtil.hasBroadcastCharacterValue(BROADCAST_STREAM_ID)).willReturn(true);
        stubPromptDependencies();
        given(broadcastGeminiLiveService.connectGeminiApiWebSocketAsync(any(), any())).willReturn(firstGeminiFuture, secondGeminiFuture);

        // when
        broadcastWebSocketHandler.afterConnectionEstablished(firstClientSession);
        broadcastWebSocketHandler.afterConnectionEstablished(secondClientSession);
        Long secondGeneration = (Long) secondClientSession.getAttributes().get(WebSocketAttributes.SESSION_GENERATION.getValue());

        // 이전 연결의 실패가 나중에 도착하더라도 이미 교체된 bundle에는 영향이 없어야 한다.
        firstGeminiFuture.completeExceptionally(new RuntimeException("late bootstrap failure"));

        // then
        BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID);
        assertThat(currentBundle).isNotNull();
        assertThat(currentBundle.getGeneration()).isEqualTo(secondGeneration);
        assertThat(currentBundle.getStatus()).isEqualTo(WebSocketSessionBundleStatus.GEMINI_CONNECTING);
        assertThat(currentBundle.getGeminiSession()).isNull();

        // 늦게 도착한 첫 번째 실패 때문에 현재 세션이 닫히면 안 된다.
        verify(secondClientSession, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("방송 시작 대기 상태 - READY 전 텍스트 메시지를 보내면 상태 메시지만 응답하고 비즈니스 로직은 실행하지 않는다")
    void 방송_시작_대기상태_READY전_텍스트_메시지_수신() throws Exception {
        // given
        // READY 이전 bundle 조회와 상태 메시지 응답에 필요한 속성만 가진 클라이언트 세션을 준비한다.
        WebSocketSession clientSession = mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        clientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(clientSession.getAttributes()).willReturn(clientAttributes);
        given(clientSession.isOpen()).willReturn(true);

        // 현재 세션을 registry에 등록하고, 같은 generation으로 메시지를 보내는 상황을 만든다.
        sessionRegistry.registerClientSession(BROADCAST_STREAM_ID, clientSession);
        BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID);
        clientSession.getAttributes().put(WebSocketAttributes.SESSION_GENERATION.getValue(), currentBundle.getGeneration());

        // 사용자가 텍스트 메시지를 보냈지만 Gemini 연결은 아직 완료되지 않은 요청을 준비한다.
        TextMessage requestMessage = new TextMessage("{\"message\":\"안녕하세요\"}");

        // when
        broadcastWebSocketHandler.handleTextMessage(clientSession, requestMessage);

        // then
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(clientSession, times(1)).sendMessage(captor.capture());
        JsonNode statusPayload = objectMapper.readTree(captor.getValue().getPayload());

        // 비즈니스 로직 대신 GEMINI_CONNECTING 상태 안내만 내려가는지 확인한다.
        assertThat(statusPayload.get("status").asText()).isEqualTo(WebSocketSessionBundleStatus.GEMINI_CONNECTING.name());
        assertThat(statusPayload.get("message").asText()).isEqualTo(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY.getMessage());
        verifyNoInteractions(broadcastMessageService);
        verify(clientSession, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("전송 오류 발생 - 현재 generation bundle만 제거되고 Gemini 세션도 함께 종료된다")
    void 전송_오류_발생_현재_bundle과_Gemini_세션_정리() throws Exception {
        // given
        // transport error 처리 시 현재 bundle을 찾을 수 있도록 generation 속성을 가진 클라이언트 세션을 준비한다.
        WebSocketSession clientSession = mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        clientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(clientSession.getAttributes()).willReturn(clientAttributes);
        given(clientSession.isOpen()).willReturn(true);

        // bundle에 등록된 Gemini 세션은 종료 경로에서 실제 close 호출만 검증한다.
        WebSocketSession geminiSession = mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        // 현재 세션 bundle을 READY 상태로 만들어 transport error 정리 경로를 연다.
        sessionRegistry.registerClientSession(BROADCAST_STREAM_ID, clientSession);
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID);
        bundle.registerGeminiSession(geminiSession);
        bundle.updateStatus(WebSocketSessionBundleStatus.READY);
        clientSession.getAttributes().put(WebSocketAttributes.SESSION_GENERATION.getValue(), bundle.getGeneration());

        // when
        broadcastWebSocketHandler.handleTransportError(clientSession, new RuntimeException("transport error"));

        // then
        // 현재 generation bundle이 삭제되고, client/gemini 세션 모두 종료되어야 한다.
        assertThat(sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID)).isNull();
        verify(clientSession, atLeastOnce()).close(any(CloseStatus.class));
        verify(geminiSession, times(1)).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("클라이언트 연결 종료 - 현재 generation bundle 제거 후 Gemini 세션도 종료된다")
    void 클라이언트_연결_종료_현재_bundle_및_Gemini_세션_정리() throws Exception {
        // given
        // afterConnectionClosed는 세션 속성만 읽으므로 필요한 메타데이터만 가진 클라이언트 세션을 준비한다.
        WebSocketSession clientSession = mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        clientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(clientSession.getAttributes()).willReturn(clientAttributes);

        // bundle에 묶인 Gemini 세션은 종료 여부만 검증하면 되므로 open 상태만 설정한다.
        WebSocketSession geminiSession = mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        // 현재 generation bundle을 registry에 등록한 뒤 정상 종료 이벤트를 전달할 준비를 한다.
        sessionRegistry.registerClientSession(BROADCAST_STREAM_ID, clientSession);
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID);
        bundle.registerGeminiSession(geminiSession);
        bundle.updateStatus(WebSocketSessionBundleStatus.READY);
        clientSession.getAttributes().put(WebSocketAttributes.SESSION_GENERATION.getValue(), bundle.getGeneration());

        // when
        broadcastWebSocketHandler.afterConnectionClosed(clientSession, CloseStatus.NORMAL);

        // then
        // 현재 bundle이 정리되고, 연결된 Gemini 세션도 함께 닫혀야 한다.
        assertThat(sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID)).isNull();
        verify(geminiSession, times(1)).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("Ping 타임아웃 - 오래된 client session bundle을 제거하고 Gemini 세션도 종료한다")
    void Ping_타임아웃_bundle_및_Gemini_세션_정리() throws Exception {
        // given
        // ping 스케줄러가 lastPongAt과 generation을 읽을 수 있도록 필요한 속성만 가진 클라이언트 세션을 준비한다.
        WebSocketSession clientSession = mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.USER_ID.getValue(), USER_ID);
        clientAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), BROADCAST_STREAM_ID);
        given(clientSession.getAttributes()).willReturn(clientAttributes);
        given(clientSession.isOpen()).willReturn(true);

        // 현재 bundle에 연결된 Gemini 세션은 timeout 정리 시 close 호출만 확인한다.
        WebSocketSession geminiSession = mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        // pong 응답이 오래된 READY bundle을 구성해 timeout 정리 경로를 강제로 연다.
        sessionRegistry.registerClientSession(BROADCAST_STREAM_ID, clientSession);
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID);
        bundle.registerGeminiSession(geminiSession);
        bundle.updateStatus(WebSocketSessionBundleStatus.READY);
        clientSession.getAttributes().put(WebSocketAttributes.SESSION_GENERATION.getValue(), bundle.getGeneration());
        clientSession.getAttributes().put(WebSocketAttributes.LAST_PONG_AT.getValue(), Instant.now().minusSeconds(120));

        // when
        broadcastWebSocketHandler.pingActiveSessions();

        // then
        // timeout된 bundle이 제거되고, client/gemini 세션이 함께 종료되어야 한다.
        assertThat(sessionRegistry.getSessionBundle(BROADCAST_STREAM_ID)).isNull();
        verify(clientSession, atLeastOnce()).close(any(CloseStatus.class));
        verify(geminiSession, times(1)).close(any(CloseStatus.class));
    }

    private void stubPromptDependencies() {
        BroadcastInfoRedisDto summary = BroadcastInfoRedisDto.builder()
                .cursorId(0L)
                .content("오늘 방송 요약")
                .createdAt(LocalDateTime.now())
                .build();
        BroadcastCharacterRedisDto character = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("테스트 캐릭터")
                .build();

        given(broadcastRedisUtil.getBroadcastCharacterDto(BROADCAST_STREAM_ID)).willReturn(character);
        given(broadcastRedisUtil.getSummary(BROADCAST_STREAM_ID)).willReturn(summary);
        given(broadcastRedisUtil.getRecentActiveDialogues(BROADCAST_STREAM_ID, 50)).willReturn(List.of());
        given(broadcastPromptBuilder.buildBroadcastDialoguePrompt(character, summary, List.of())).willReturn("테스트 시스템 프롬프트");
    }
}
