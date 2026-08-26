package com.example.sku_sw.domain.broadcast.service.fastapi;

import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateResDto;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import com.example.sku_sw.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class FastApiChzzkSessionServiceTest {

    @Mock
    private FastApiUtil fastApiUtil;

    @InjectMocks
    private FastApiChzzkSessionService fastApiChzzkSessionService;

    @Test
    void 방송_스트림과_일치하는_유효한_치지직_세션을_반환한다() {
        given(fastApiUtil.createChzzkSession(any()))
                .willAnswer(invocation -> {
                    FastApiChzzkSessionCreateReqDto request = invocation.getArgument(0);
                    return Mono.just(FastApiChzzkSessionCreateResDto.builder()
                            .broadcastStreamId(request.broadcastStreamId())
                            .attemptId(request.attemptId())
                            .sessionKey("session-key")
                            .channelId("channel-id")
                            .build());
                });

        FastApiChzzkSessionCreateResDto result = fastApiChzzkSessionService.connectChzzkSession(
                "broadcast-stream-id",
                "access-token"
        );

        assertThat(result.broadcastStreamId()).isEqualTo("broadcast-stream-id");
        assertThat(result.sessionKey()).isEqualTo("session-key");
        assertThat(result.channelId()).isEqualTo("channel-id");
    }

    @Test
    void 세션_응답의_방송_스트림이_요청과_다르면_예외가_발생한다() {
        given(fastApiUtil.createChzzkSession(any()))
                .willAnswer(invocation -> {
                    FastApiChzzkSessionCreateReqDto request = invocation.getArgument(0);
                    return Mono.just(FastApiChzzkSessionCreateResDto.builder()
                            .broadcastStreamId("another-stream-id")
                            .attemptId(request.attemptId())
                            .sessionKey("session-key")
                            .channelId("channel-id")
                            .build());
                });

        assertThatThrownBy(() -> fastApiChzzkSessionService.connectChzzkSession(
                "broadcast-stream-id",
                "access-token"
        ))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
    }

    @Test
    void 세션_키나_채널_ID가_없으면_예외가_발생한다() {
        given(fastApiUtil.createChzzkSession(any()))
                .willAnswer(invocation -> {
                    FastApiChzzkSessionCreateReqDto request = invocation.getArgument(0);
                    return Mono.just(FastApiChzzkSessionCreateResDto.builder()
                            .broadcastStreamId(request.broadcastStreamId())
                            .attemptId(request.attemptId())
                            .sessionKey(" ")
                            .channelId("channel-id")
                            .build());
                });

        assertThatThrownBy(() -> fastApiChzzkSessionService.connectChzzkSession(
                "broadcast-stream-id",
                "access-token"
        ))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
    }
}
