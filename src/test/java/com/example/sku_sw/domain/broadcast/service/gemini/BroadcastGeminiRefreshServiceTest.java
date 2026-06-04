package com.example.sku_sw.domain.broadcast.service.gemini;

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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiRefreshServiceTest {

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private BroadcastPromptBuilder broadcastPromptBuilder;

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
    @DisplayName("requestRefreshAfterCompaction - flight=0이면 즉시 tryStartRefresh를 호출하고 taskScheduler는 호출되지 않는다")
    void requestRefreshAfterCompaction_즉시시작() {
        // given: bundle with 0 in-flight requests
        String streamId = "stream-1";
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.isClientSessionOpen()).willReturn(true);
        given(bundle.getGeneration()).willReturn(1L);
        given(bundle.getRequestFlightCountValue()).willReturn(0);
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(bundle);

        // when
        refreshService.requestRefreshAfterCompaction(streamId);

        // then: refresh start is called immediately without scheduling a recheck
        verify(bundle, times(1)).markRefreshRequested();
        verify(bundle, times(1)).updateStatus(WebSocketSessionBundleStatus.REFRESHING);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - flight>0이면 재검사 작업을 taskScheduler에 예약한다")
    void requestRefreshAfterCompaction_지연시작_재검사예약() {
        // given: bundle with 1 in-flight request → must defer
        String streamId = "stream-1";
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.isClientSessionOpen()).willReturn(true);
        given(bundle.getGeneration()).willReturn(1L);
        given(bundle.getRequestFlightCountValue()).willReturn(1);
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(bundle);

        // when
        refreshService.requestRefreshAfterCompaction(streamId);

        // then: refresh markers are set and a deferred recheck is scheduled
        verify(bundle, times(1)).markRefreshRequested();
        verify(bundle, times(1)).updateStatus(WebSocketSessionBundleStatus.REFRESHING);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - deferred recheck Runnable이 tryStartRefresh를 호출한다 (비행 중이면 guard에서 중단)")
    void requestRefreshAfterCompaction_재검사Runnable_내부_tryStartRefresh_호출() {
        // The scheduled Runnable must invoke tryStartRefresh.
        // We verify by checking that tryStartRefresh's guard conditions are evaluated.
        // given: bundle with 1 in-flight request → must defer
        String streamId = "stream-1";
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.isClientSessionOpen()).willReturn(true);
        given(bundle.getGeneration()).willReturn(1L);
        given(bundle.getRequestFlightCountValue()).willReturn(1);
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(bundle);

        // tryStartRefresh 내부에서 사용할 stubs (guard 체크용)
        given(sessionRegistry.getSessionBundleIfCurrent(streamId, 1L)).willReturn(bundle);
        given(bundle.getGeminiSessionRefreshRequested()).willReturn(true);
        // 여전히 flight > 0 이므로 tryStartRefresh는 markRefreshInProgress를 호출하지 않고 종료됨

        // when
        refreshService.requestRefreshAfterCompaction(streamId);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));

        // execute the recheck runnable synchronously
        captor.getValue().run();

        // then: tryStartRefresh가 실제로 호출되어 guard 조건을 평가했음을 확인
        verify(bundle, atLeastOnce()).getGeminiSessionRefreshRequested();
        // flight > 0 이므로 markRefreshInProgress는 호출되면 안 됨
        verify(bundle, never()).markRefreshInProgress();
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - 번들이 null이면 종료한다")
    void requestRefreshAfterCompaction_bundle_null() {
        // given
        String streamId = "stream-1";
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(null);

        // when
        refreshService.requestRefreshAfterCompaction(streamId);

        // then
        verify(sessionRegistry, times(1)).getSessionBundle(streamId);
        verifyNoMoreInteractions(sessionRegistry);
        verify(taskScheduler, never()).schedule(any(), any());
    }

    @Test
    @DisplayName("requestRefreshAfterCompaction - 클라이언트 세션이 열려있지 않으면 종료한다")
    void requestRefreshAfterCompaction_client_session_not_open() {
        // given
        String streamId = "stream-1";
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.isClientSessionOpen()).willReturn(false);
        given(sessionRegistry.getSessionBundle(streamId)).willReturn(bundle);

        // when
        refreshService.requestRefreshAfterCompaction(streamId);

        // then
        verify(bundle, never()).markRefreshRequested();
        verify(taskScheduler, never()).schedule(any(), any());
    }

    @Test
    @DisplayName("tryStartRefresh - refresh 요청이 없으면 중복 시작하지 않는다")
    void tryStartRefresh_refresh_not_requested() {
        // given
        String streamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.getGeminiSessionRefreshRequested()).willReturn(false);
        given(sessionRegistry.getSessionBundleIfCurrent(streamId, generation)).willReturn(bundle);

        // when
        refreshService.tryStartRefresh(streamId, generation);

        // then
        verify(bundle, never()).markRefreshInProgress();
    }

    @Test
    @DisplayName("tryStartRefresh - flight count가 0보다 크면 시작하지 않는다")
    void tryStartRefresh_in_flight_remaining() {
        // given
        String streamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.getGeminiSessionRefreshRequested()).willReturn(true);
        given(bundle.getRequestFlightCountValue()).willReturn(1);
        given(sessionRegistry.getSessionBundleIfCurrent(streamId, generation)).willReturn(bundle);

        // when
        refreshService.tryStartRefresh(streamId, generation);

        // then
        verify(bundle, never()).markRefreshInProgress();
    }
}
