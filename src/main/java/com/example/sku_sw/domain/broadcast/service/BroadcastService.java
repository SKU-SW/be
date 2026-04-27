package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastTerminateResDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastStreamIdGenerator;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketHandler;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.enums.CharacterErrorCode;
import com.example.sku_sw.domain.character.repository.CharacterRepository;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;

import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastService {

    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStreamIdGenerator streamIdGenerator;
    private final BroadcastWebSocketHandler broadcastWebSocketHandler;

    /**
     * AI 캐릭터 방송 시작
     * - 사용자가 선택한 캐릭터로 방송을 시작한다.
     * - 선택되지 않은 캐릭터거나 이미 방송 중인 경우 예외를 발생시킨다.
     * - User row에 Write Lock을 걸어 동시 요청을 직렬화한다.
     *
     * @param userId : 방송을 시작하는 사용자 ID
     * @param characterId : 방송을 시작할 캐릭터 ID
     * @return : 방송 시작 응답 DTO (streamId, startedAt)
     */
    @Transactional
    public BroadcastStartResDto startBroadcast(Long userId, Long characterId) {
        log.info("[BroadcastService] startBroadcast() - START | userId: {}, characterId: {}", userId, characterId);

        /*
            1. User row Write Lock 획득
            - 동시 요청 직렬화를 위해 비관적 쓰기 락을 사용한다.
         */
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.USER_NOT_FOUND));

        /*
            2. 선택된 캐릭터 검증
            - 선택된 캐릭터가 없거나, 요청한 캐릭터가 선택된 캐릭터가 아닌 경우 예외를 발생시킨다.
         */
        if (user.getSelectedCharacterId() == null || !user.getSelectedCharacterId().equals(characterId)) {
            throw new CustomException(BroadcastErrorCode.BROADCAST_CHARACTER_NOT_SELECTED);
        }

        /*
            3. 캐릭터 조회 및 소유권 검증
            - characterId와 userId로 캐릭터를 조회하고, 존재하지 않으면 CHARACTER_NOT_FOUND 예외를 발생시킨다.
         */
        Character character = characterRepository.findByIdAndUserId(characterId, userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        /*
            4. 해당 캐릭터 방송 중 여부 확인
            - 이미 BROADCASTING 상태인 방송이 있으면 CHARACTER_ALREADY_BROADCASTING 예외를 발생시킨다.
         */
        if (broadcastRepository.existsByCharacterIdAndStatus(characterId, BroadcastStatus.BROADCASTING)) {
            throw new CustomException(BroadcastErrorCode.CHARACTER_ALREADY_BROADCASTING);
        }

        /*
            5. 고유 streamId 생성
            - 16자리 영숫자 랜덤 ID를 생성하고, 중복 시 재생성한다.
         */
        String streamId = streamIdGenerator.generate();

        /*
            6. Broadcast 엔티티 생성 및 저장
            - 생성한 streamId와 character로 Broadcast 객체를 생성하고 저장한다.
         */
        Broadcast broadcast = Broadcast.startBroadcast(streamId, character);
        Broadcast savedBroadcast = broadcastRepository.save(broadcast);

        /*
            7. ResponseDto 생성
            - 저장된 Broadcast의 streamId와 startedAt을 포맷하여 응답 DTO를 생성한다.
         */
        BroadcastStartResDto result = BroadcastStartResDto.builder()
                .broadcastStreamId(savedBroadcast.getStreamId())
                .broadcastStartedAt(savedBroadcast.getStartedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")))
                .build();

        log.info("[BroadcastService] startBroadcast() - END | streamId: {}", streamId);
        return result;
    }

    /**
     * 현재 사용자가 선택한 캐릭터의 진행 중인 방송 종료
     * - 사용자의 selectedCharacterId 기준으로 진행 중인 방송을 조회한다.
     * - 방송 상태를 TERMINATED로 변경하고 WebSocket 세션 종료를 예약한다.
     *
     * @param userId : 방송을 종료하는 사용자 ID
     * @return : 방송 종료 응답 DTO
     */
    @Transactional
    public BroadcastTerminateResDto terminateCurrentBroadcast(Long userId) {
        log.info("[BroadcastService] terminateCurrentBroadcast() - START | userId: {}", userId);

        /*
            1. User row Write Lock 획득
            - 동시 요청 직렬화를 위해 비관적 쓰기 락을 사용한다.
         */
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.USER_NOT_FOUND));

        /*
            2. 선택된 캐릭터 검증
            - 선택된 캐릭터가 없으면 예외를 발생시킨다.
         */
        Long selectedCharacterId = user.getSelectedCharacterId();
        if (selectedCharacterId == null) {
            throw new CustomException(BroadcastErrorCode.ACTIVE_BROADCAST_NOT_FOUND);
        }

        /*
            3. 활성 방송 조회
            - 사용자의 선택 캐릭터에 대한 진행 중인 방송을 조회한다.
         */
        Broadcast broadcast = broadcastRepository.findActiveByUserIdAndCharacterId(
                        userId,
                        selectedCharacterId,
                        BroadcastStatus.BROADCASTING
                )
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.ACTIVE_BROADCAST_NOT_FOUND));

        /*
            4. 방송 정상 종료 처리
            - DB 상태를 TERMINATED로 변경하고 종료 시간을 기록한다.
         */
        broadcast.normalTerminate();

        /*
            5. ResponseDto 생성
         */
        BroadcastTerminateResDto result = BroadcastTerminateResDto.builder()
                .terminatedBroadcastStreamId(broadcast.getStreamId())
                .broadcastStatus(broadcast.getStatus())
                .broadcastTerminatedAt(broadcast.getTerminatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")))
                .build();

        /*
            6. WebSocket 세션 종료 예약
            - 트랜잭션 커밋 후에 WebSocket 연결을 종료한다.
            - 트랜잭션이 커밋되기 이전에 WebSocket 연결을 종료하면, DB에는 종료되지 않은 상태로 롤백되지만 WebSocket은 연결이 종료되어버리게 되므로 데이터가 다르게 설정된다.
         */
        registerDisconnectAfterCommit(broadcast.getStreamId());

        log.info("[BroadcastService] terminateCurrentBroadcast() - END | streamId: {}", broadcast.getStreamId());
        return result;
    }

    /**
     * 트랜잭션 커밋 후 WebSocket 세션 종료 예약
     * - DB 변경이 확정된 후에 외부 사이드 이펙트(WebSocket 종료)를 실행한다.
     *
     * @param broadcastStreamId : 종료할 방송 스트림 ID
     */
    private void registerDisconnectAfterCommit(String broadcastStreamId) {
        /*
            TransactionSynchronizationManager : Spring에서 트랜잭션 생명주기 이벤트에 콜백을 등록할 수 있게 해주는 유틸리티 클래스
            - 트랜잭션의 특정 시점에 원하는 코드를 실행할 수 있다.
         */
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcastWebSocketHandler.disconnect(
                        broadcastStreamId,
                        CloseStatus.NORMAL.withReason("Broadcast terminated")
                );
            }
        });
    }

}
