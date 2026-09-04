package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastUserRedisDto;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelResDto;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastStartAfterServiceTest {

    @InjectMocks
    private BroadcastStartAfterService broadcastStartAfterService;

    @Mock
    private BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;

    @Mock
    private BroadcastStartCompensationService broadcastStartCompensationService;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private ChatRedisUtil chatRedisUtil;

    @Mock
    private FastApiUtil fastApiUtil;

    @Test
    @DisplayName("방송 시작 후속 처리 성공 - Redis와 FastAPI 연결 후 WebSocket 타임아웃 등록")
    void 방송_시작_후속_처리_성공() {
        // given
        String broadcastStreamId = "stream-success";
        BroadcastCharacterRedisDto characterRedisDto = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("테스트 캐릭터")
                .build();
        BroadcastUserRedisDto userRedisDto = BroadcastUserRedisDto.builder()
                .sessionKey("session-key")
                .channelId("channel-id")
                .build();

        given(chatRedisUtil.subscribeChannelPattern("channel-id")).willReturn("channel-name");
        given(fastApiUtil.connectChzzkRedisChannel(new FastApiChzzkRedisChannelReqDto(
                broadcastStreamId,
                "session-key",
                "channel-name"
        ))).willReturn(FastApiChzzkRedisChannelResDto.builder()
                .broadcastStreamId(broadcastStreamId)
                .sessionKey("session-key")
                .channelName("channel-name")
                .status("연결 성공")
                .build());

        // when
        broadcastStartAfterService.processBroadcastStartAfterCommit(
                broadcastStreamId,
                characterRedisDto,
                userRedisDto
        );

        // then
        assertThat(userRedisDto.getChannelName()).isEqualTo("channel-name");
        verify(broadcastRedisUtil, times(1)).setBroadcastCharacterValue(broadcastStreamId, characterRedisDto);
        verify(broadcastRedisUtil, times(1)).initializeSummarySlot(broadcastStreamId);
        verify(broadcastRedisUtil, times(1)).setBroadcastUserValue(broadcastStreamId, userRedisDto);
        verify(broadcastConnectionTimeoutService, times(1)).registerConnectionTimeout(broadcastStreamId);
        verify(broadcastStartCompensationService, never()).abnormalTerminateBroadcast(broadcastStreamId);
        verify(sessionRegistry, never()).disconnect(broadcastStreamId);
    }

    @Test
    @DisplayName("방송 시작 후속 처리 실패 - 연결된 외부 자원 정리 및 방송 비정상 종료")
    void 방송_시작_후속_처리_실패_외부_자원_정리와_비정상_종료() {
        // given
        String broadcastStreamId = "stream-failure";
        BroadcastCharacterRedisDto characterRedisDto = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("테스트 캐릭터")
                .build();
        BroadcastUserRedisDto userRedisDto = BroadcastUserRedisDto.builder()
                .sessionKey("session-key")
                .channelId("channel-id")
                .build();

        given(chatRedisUtil.subscribeChannelPattern("channel-id")).willReturn("channel-name");
        given(fastApiUtil.connectChzzkRedisChannel(new FastApiChzzkRedisChannelReqDto(
                broadcastStreamId,
                "session-key",
                "channel-name"
        ))).willReturn(FastApiChzzkRedisChannelResDto.builder()
                .broadcastStreamId(broadcastStreamId)
                .sessionKey("session-key")
                .channelName("channel-name")
                .status("연결 성공")
                .build());
        willThrow(new IllegalStateException("타임아웃 등록 실패"))
                .given(broadcastConnectionTimeoutService)
                .registerConnectionTimeout(broadcastStreamId);

        // when
        broadcastStartAfterService.processBroadcastStartAfterCommit(
                broadcastStreamId,
                characterRedisDto,
                userRedisDto
        );

        // then
        verify(broadcastStartCompensationService, times(1)).abnormalTerminateBroadcast(broadcastStreamId);

        ArgumentCaptor<FastApiChzzkRedisChannelReqDto> disconnectRequestCaptor =
                ArgumentCaptor.forClass(FastApiChzzkRedisChannelReqDto.class);
        verify(fastApiUtil, times(1)).disconnectChzzkRedisChannel(disconnectRequestCaptor.capture());
        assertThat(disconnectRequestCaptor.getValue().broadcastStreamId()).isEqualTo(broadcastStreamId);
        assertThat(disconnectRequestCaptor.getValue().sessionKey()).isEqualTo("session-key");
        assertThat(disconnectRequestCaptor.getValue().channelName()).isEqualTo("channel-name");

        verify(chatRedisUtil, times(1)).unsubscribeChannelPattern("channel-id");
        verify(broadcastRedisUtil, times(1)).deleteBroadcastCharacterValue(broadcastStreamId);
        verify(broadcastRedisUtil, times(1)).deleteBroadcastUserValue(broadcastStreamId);
        verify(broadcastRedisUtil, times(1)).deleteBroadcastInfo(broadcastStreamId);
        verify(sessionRegistry, times(1)).disconnect(broadcastStreamId);
    }

    @Test
    @DisplayName("방송 상태 보상 실패 - 외부 자원 정리는 계속 수행")
    void 방송_상태_보상_실패_외부_자원_정리_계속_수행() {
        // given
        String broadcastStreamId = "stream-compensation-failure";
        BroadcastCharacterRedisDto characterRedisDto = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .build();
        BroadcastUserRedisDto userRedisDto = BroadcastUserRedisDto.builder()
                .sessionKey("session-key")
                .channelId("channel-id")
                .build();

        given(chatRedisUtil.subscribeChannelPattern("channel-id")).willReturn("channel-name");
        willThrow(new IllegalStateException("FastAPI 연결 실패"))
                .given(fastApiUtil)
                .connectChzzkRedisChannel(new FastApiChzzkRedisChannelReqDto(
                        broadcastStreamId,
                        "session-key",
                        "channel-name"
                ));
        willThrow(new IllegalStateException("DB 보상 실패"))
                .given(broadcastStartCompensationService)
                .abnormalTerminateBroadcast(broadcastStreamId);

        // when
        broadcastStartAfterService.processBroadcastStartAfterCommit(
                broadcastStreamId,
                characterRedisDto,
                userRedisDto
        );

        // then
        verify(chatRedisUtil, times(1)).unsubscribeChannelPattern("channel-id");
        verify(broadcastRedisUtil, times(1)).deleteBroadcastCharacterValue(broadcastStreamId);
        verify(broadcastRedisUtil, times(1)).deleteBroadcastUserValue(broadcastStreamId);
        verify(broadcastRedisUtil, times(1)).deleteBroadcastInfo(broadcastStreamId);
        verify(sessionRegistry, times(1)).disconnect(broadcastStreamId);
        verify(broadcastConnectionTimeoutService, never()).registerConnectionTimeout(broadcastStreamId);
    }
}
