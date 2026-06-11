package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 핸드셰이크 인터셉터
 * - 클라이언트의 WebSocket 연결 요청을 가로채어 JWT 검증, 사용자 검증, 방송 존재 검증을 수행한다.
 * - 검증이 완료되면 userId와 broadcastStreamId를 WebSocketSession 속성에 저장하여 Handler로 전달한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastHandshakeInterceptor implements HandshakeInterceptor {
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final BroadcastRepository broadcastRepository;

    /**
     * 핸드셰이크 전 실행되는 검증 로직
     * - JWT 토큰 유효성 검증
     * - 사용자 존재 여부 검증
     * - 방송 streamId 존재 여부 및 활성 상태 검증
     */
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        log.info("[BroadcastHandshakeInterceptor] beforeHandshake() - START | uri: {}", request.getURI());
        log.info("[BroadcastHandshakeInterceptor] beforeHandshake() - Origin | origin: {}", request.getHeaders().getOrigin());

        /*
            1. Access Token 추출 및 검증
            - Authorization 헤더 또는 쿼리 파라미터에서 토큰을 추출한다.
         */
        String token = resolveAccessToken(request);
        if (token == null) {
            log.error("[BroadcastHandshakeInterceptor] beforeHandshake() - Access token not found");
            throw new CustomException(BroadcastErrorCode.TOKEN_AUTHORIZATION_FAILED);
        }

        if (!jwtUtil.validateToken(token)) {
            log.error("[BroadcastHandshakeInterceptor] beforeHandshake() - Invalid JWT token");
            throw new CustomException(BroadcastErrorCode.TOKEN_AUTHORIZATION_FAILED);
        }

        /*
            2. 토큰 타입 검증 (ACCESS 토큰만 허용)
         */
        if (jwtUtil.getTokenType(token) != com.example.sku_sw.global.security.module.JwtTokenType.ACCESS) {
            log.error("[BroadcastHandshakeInterceptor] beforeHandshake() - Token type is not ACCESS");
            throw new CustomException(BroadcastErrorCode.TOKEN_AUTHORIZATION_FAILED);
        }

        /*
            3. userId 추출 및 사용자 존재 여부 검증
         */
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (!userRepository.existsById(userId)) {
            log.error("[BroadcastHandshakeInterceptor] beforeHandshake() - User not found | userId: {}", userId);
            throw new CustomException(BroadcastErrorCode.USER_NOT_FOUND);
        }

        /*
            4. broadcastStreamId 추출 및 방송 존재 여부 검증
         */
        String broadcastStreamId = resolveBroadcastStreamId(request);
        if (broadcastStreamId == null) {
            log.error("[BroadcastHandshakeInterceptor] beforeHandshake() - broadcastStreamId not found");
            throw new CustomException(BroadcastErrorCode.NEED_BROADCAST_STREAM_ID);
        }

        Broadcast broadcast = broadcastRepository.findByStreamIdAndStatus(broadcastStreamId, BroadcastStatus.BROADCASTING)
                .orElseThrow(() -> {
                    log.error("[BroadcastHandshakeInterceptor] beforeHandshake() - Broadcast not found or not active | streamId: {}", broadcastStreamId);
                    return new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND);
                });

        /*
            5. WebSocketSession 속성에 검증된 정보 저장
            - Handler에서 해당 속성들을 읽어 사용할 수 있다.
         */
        attributes.put(WebSocketAttributes.USER_ID.getValue(), userId);
        attributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), broadcastStreamId);
        attributes.put(WebSocketAttributes.CHARACTER_ID.getValue(), broadcast.getCharacter().getId());

        log.info("[BroadcastHandshakeInterceptor] beforeHandshake() - END | userId: {}, streamId: {}", userId, broadcastStreamId);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // 핸드셰이크 후 추가 처리 필요 없음
    }

    /**
     * Access Token 추출
     * - Authorization: Bearer <token> 헤더 우선
     * - 쿼리 파라미터 accessToken fallback
     */
    private String resolveAccessToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return request.getURI().getQuery() != null
                ? java.util.Arrays.stream(request.getURI().getQuery().split("&"))
                .filter(param -> param.startsWith("accessToken="))
                .map(param -> param.substring("accessToken=".length()))
                .findFirst()
                .orElse(null)
                : null;
    }

    /**
     * broadcastStreamId 추출
     * - 쿼리 파라미터에서 추출
     */
    private String resolveBroadcastStreamId(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return null;
        }

        return java.util.Arrays.stream(query.split("&"))
                .filter(param -> param.startsWith("broadcastStreamId="))
                .map(param -> param.substring("broadcastStreamId=".length()))
                .findFirst()
                .orElse(null);
    }
}
