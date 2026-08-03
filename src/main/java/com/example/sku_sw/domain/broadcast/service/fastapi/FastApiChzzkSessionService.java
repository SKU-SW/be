package com.example.sku_sw.domain.broadcast.service.fastapi;

import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateResDto;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * FastAPI의 치지직 세션 관련 비즈니스 로직들이 위치한 클래스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FastApiChzzkSessionService {

    private final FastApiUtil fastApiUtil;

    /**
     * FastAPI에 Chzzk 세션 연결을 요청한다.
     * - 요청과 응답의 방송 스트림 ID 및 시도 ID 일치 여부를 검증한다.
     * - 세션 키 또는 채널 ID가 없으면 예외를 발생시킨다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param accessToken : Chzzk Auth Access Token
     * @return : FastAPI Chzzk 세션 생성 응답
     */
    public FastApiChzzkSessionCreateResDto connectChzzkSession(String broadcastStreamId, String accessToken) {
        log.info("[FastApiChzzkSessionService] Chzzk 세션 연결 | connectChzzkSession() - START | streamId: {}", broadcastStreamId);

        // 1. 시도 ID용 UUID 생성
        String attemptId = UUID.randomUUID().toString();
        FastApiChzzkSessionCreateReqDto request = new FastApiChzzkSessionCreateReqDto(
                broadcastStreamId,
                attemptId,
                accessToken
        );
        // 2. 치지직 세션 생성
        FastApiChzzkSessionCreateResDto result = fastApiUtil.createChzzkSession(request)
                .block();
        // 3. 치지직 세션 생성 응답값 검증
        validateChzzkSessionCreateResponse(broadcastStreamId, attemptId, result);

        log.info("[FastApiChzzkSessionService] Chzzk 세션 연결됨 | connectChzzkSession() - END | streamId: {}, channelId: {}",
                broadcastStreamId, result.channelId());
        return result;
    }

    /**
     * FastAPI Chzzk 세션 생성 응답이 요청과 일치하는지 검증한다.
     * @param broadcastStreamId : 요청한 방송 스트림 ID
     * @param attemptId : 요청 시도 ID
     * @param response : FastAPI Chzzk 세션 생성 응답
     */
    private void validateChzzkSessionCreateResponse(
            String broadcastStreamId,
            String attemptId,
            FastApiChzzkSessionCreateResDto response
    ) {
        log.debug("[FastApiChzzkSessionService] Chzzk 세션 응답 검증 | validateChzzkSessionCreateResponse() - START | streamId: {}, attemptId: {}",
                broadcastStreamId, attemptId);

        if (response == null) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
        }
        if (!broadcastStreamId.equals(response.broadcastStreamId())) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
        }
        if (!attemptId.equals(response.attemptId())) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_ATTEMPT_MISMATCH);
        }
        if (!StringUtils.hasText(response.sessionKey()) || !StringUtils.hasText(response.channelId())) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
        }

        log.debug("[FastApiChzzkSessionService] Chzzk 세션 응답 검증 완료 | validateChzzkSessionCreateResponse() - END | streamId: {}, attemptId: {}",
                broadcastStreamId, attemptId);
    }
}
