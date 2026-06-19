package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.service.BroadcastPromptHistoryService;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.util.BroadcastPromptBuilder;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.global.util.GeminiUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiRefreshServiceTest {

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private BroadcastPromptBuilder broadcastPromptBuilder;

    @Mock
    private BroadcastPromptHistoryService broadcastPromptHistoryService;

    @Mock
    private GeminiUtil geminiUtil;

    @Mock
    private BroadcastGeminiBootstrapService broadcastGeminiBootstrapService;

    @Mock
    private BroadcastGeminiRequestService broadcastGeminiRequestService;

    @Mock
    private BroadcastGeminiLiveService broadcastGeminiLiveService;

    @Mock
    private TaskScheduler taskScheduler;

    @InjectMocks
    private BroadcastGeminiRefreshService refreshService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshService, "redisBroadcastDialogueMaxNum", 50);
        ReflectionTestUtils.setField(refreshService, "redisMaxRefreshRetryCount", 3);
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - flight가 0이면 즉시 refresh를 시작한다")
    void requestRefreshAfterCompaction_즉시시작() {
        String streamId = "stream-1";
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.isClientSessionOpen()).willReturn(true);
        given(bundle.getGeneration()).willReturn(1L);
        given(bundle.getRequestFlightCountValue()).willReturn(0);
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(bundle);

        refreshService.requestRefreshAfterCompaction(streamId);

        verify(bundle, times(1)).markRefreshRequested();
        verify(bundle, times(1)).updateStatus(WebSocketSessionBundleStatus.REFRESHING);
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - flight가 남아 있으면 refresh를 시작하지 않는다")
    void requestRefreshAfterCompaction_비행중이면보류() {
        String streamId = "stream-1";
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.isClientSessionOpen()).willReturn(true);
        given(bundle.getRequestFlightCountValue()).willReturn(1);
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(bundle);

        refreshService.requestRefreshAfterCompaction(streamId);

        verify(bundle, never()).markRefreshRequested();
        verify(bundle, never()).updateStatus(WebSocketSessionBundleStatus.REFRESHING);
        verify(taskScheduler, never()).schedule(any(), any(Instant.class));
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - 번들이 없으면 종료한다")
    void requestRefreshAfterCompaction_bundle_null() {
        String streamId = "stream-1";
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(null);

        refreshService.requestRefreshAfterCompaction(streamId);

        verify(sessionRegistry, times(1)).getSessionBundle(streamId);
        verifyNoMoreInteractions(sessionRegistry);
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - 클라이언트 세션이 닫혀 있으면 종료한다")
    void requestRefreshAfterCompaction_client_session_not_open() {
        String streamId = "stream-1";
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.isClientSessionOpen()).willReturn(false);
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(bundle);

        refreshService.requestRefreshAfterCompaction(streamId);

        verify(bundle, never()).markRefreshRequested();
        verify(taskScheduler, never()).schedule(any(), any(Instant.class));
    }

    @Test
    @DisplayName("tryStartRefresh - refresh 요청이 없으면 시작하지 않는다")
    void tryStartRefresh_refresh_not_requested() {
        String streamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.getGeminiSessionRefreshRequested()).willReturn(new AtomicBoolean(false));
        given(sessionRegistry.getSessionBundleIfCurrent(streamId, generation)).willReturn(bundle);

        refreshService.tryStartRefresh(streamId, generation);

        verify(bundle, never()).markRefreshInProgress();
    }

    @Test
    @DisplayName("tryStartRefresh - flight count가 남아 있으면 시작하지 않는다")
    void tryStartRefresh_in_flight_remaining() {
        String streamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.getGeminiSessionRefreshRequested()).willReturn(new AtomicBoolean(true));
        given(bundle.getRequestFlightCountValue()).willReturn(1);
        given(sessionRegistry.getSessionBundleIfCurrent(streamId, generation)).willReturn(bundle);

        refreshService.tryStartRefresh(streamId, generation);

        verify(bundle, never()).markRefreshInProgress();
    }

    @Test
    @DisplayName("handleRefreshSuccess - replay 대상이 없으면 first resumption event를 요청한다")
    void handleRefreshSuccess_replay없음_first_resumption_event요청() {
        String streamId = "stream-1";
        long generation = 1L;
        Long snapshotCursorId = 10L;
        WebSocketSession oldGeminiSession = mock(WebSocketSession.class);
        WebSocketSession newGeminiSession = mock(WebSocketSession.class);
        given(newGeminiSession.getId()).willReturn("new-session");

        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(sessionRegistry.getSessionBundleIfCurrent(streamId, generation)).willReturn(bundle);
        given(bundle.getGeminiHandler()).willReturn(null);
        given(bundle.getClientSession()).willReturn(mock(WebSocketSession.class));
        given(broadcastGeminiLiveService.consumePendingHandler("new-session")).willReturn(null);
        given(broadcastRedisUtil.getActiveDialoguesAfterCursor(streamId, snapshotCursorId)).willReturn(List.of());

        refreshService.handleRefreshSuccess(streamId, generation, oldGeminiSession, newGeminiSession, snapshotCursorId);

        verify(bundle, times(1)).registerGeminiSession(newGeminiSession, null);
        verify(bundle, times(1)).updateStatus(WebSocketSessionBundleStatus.READY);
        verify(bundle, times(1)).clearRefreshRequested();
        verify(bundle, times(1)).clearRefreshInProgress();
        verify(bundle, times(1)).resetRefreshRetryCount();
        verify(bundle, times(1)).clearRefreshSnapshotCursorId();
        verify(broadcastGeminiRequestService, times(1)).getFirstResumptionEvent(streamId, generation);
    }
}
