package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.auth.dto.AuthChzzkAuthUrlResDto;
import com.example.sku_sw.domain.auth.enums.AuthErrorCode;
import com.example.sku_sw.domain.auth.service.AuthService;
import com.example.sku_sw.domain.broadcast.dto.*;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelResDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateResDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.exception.ChzzkReauthRequiredException;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.TendencyVersion;
import com.example.sku_sw.domain.broadcast.entity.BroadcastStats;
import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.broadcast.mapper.BroadcastAnalysisMapper;
import com.example.sku_sw.domain.broadcast.repository.BroadcastAnalysisRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastKeywordsRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastStatsRepository;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.util.BroadcastStreamIdGenerator;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.entity.CharacterImage;
import com.example.sku_sw.domain.character.entity.CharacterImageDetail;
import com.example.sku_sw.domain.character.entity.CharacterTriggerWord;
import com.example.sku_sw.domain.character.entity.CharacterVrm;
import com.example.sku_sw.domain.character.enums.CharacterAppearanceType;
import com.example.sku_sw.domain.character.enums.CharacterErrorCode;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.example.sku_sw.domain.character.repository.CharacterImageDetailRepository;
import com.example.sku_sw.domain.character.repository.CharacterRepository;
import com.example.sku_sw.domain.character.repository.CharacterTriggerWordRepository;
import com.example.sku_sw.domain.setting.entity.BroadcastSetting;
import com.example.sku_sw.domain.setting.repository.BroadcastSettingRepository;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import com.example.sku_sw.global.response.CursorSliceResponse;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastService {

    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastDialogueRepository broadcastDialogueRepository;
    private final AuthService authService;
    private final BroadcastStreamIdGenerator streamIdGenerator;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;
    private final BroadcastDialoguePersistenceService broadcastDialoguePersistenceService;
    private final BroadcastAnalysisService broadcastAnalysisService;
    private final FastApiUtil fastApiUtil;
    private final BroadcastStatsRepository broadcastStatsRepository;
    private final ChatRedisUtil chatRedisUtil;
    private final BroadcastKeywordsRepository broadcastKeywordsRepository;
    private final CharacterTriggerWordRepository characterTriggerWordRepository;
    private final CharacterImageDetailRepository characterImageDetailRepository;
    private final BroadcastAnalysisRepository broadcastAnalysisRepository;
    private final BroadcastAnalysisMapper broadcastAnalysisMapper;
    private final BroadcastSettingRepository broadcastSettingRepository;
    private final BroadcastStreamerSilenceService streamerSilenceService;
    private final BroadcastProactiveChatService proactiveChatService;

    private static final DateTimeFormatter BROADCAST_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss");
    @Value("${spring.cloud.cloudfront.domain}")
    private String cloudfrontDomain;

    /**
     * AI 캐릭터 방송 시작
     * - 사용자가 선택한 캐릭터로 방송을 시작한다.
     * - 치지직 Auth Access Token / Refresh Token이 저장되어 있어야 한다.
     * - 선택되지 않은 캐릭터거나 이미 방송 중인 경우 예외를 발생시킨다.
     * - User row에 Write Lock을 걸어 동시 요청을 직렬화한다.
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
            2. 치지직 Auth 토큰 사용 가능 여부 확인
            - 치지직 Auth Access Token / Refresh Token이 모두 저장되어 있고 만료상태가 아니어야 방송을 시작할 수 있다.
         */
        ensureChzzkAuthReadyForBroadcast(userId, user);

        /*
            3. 선택된 캐릭터 검증
            - 선택된 캐릭터가 없거나, 요청한 캐릭터가 선택된 캐릭터가 아닌 경우 예외를 발생시킨다.
         */
        if (user.getSelectedCharacterId() == null || !user.getSelectedCharacterId().equals(characterId)) {
            throw new CustomException(BroadcastErrorCode.BROADCAST_CHARACTER_NOT_SELECTED);
        }

        /*
            4. 캐릭터 조회 및 소유권 검증
            - characterId와 userId로 캐릭터를 조회하고, 존재하지 않으면 CHARACTER_NOT_FOUND 예외를 발생시킨다.
         */
        Character character = characterRepository.findBroadcastRedisCharacterByIdAndUserId(characterId, userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        /*
            5. 해당 캐릭터 방송 중 여부 확인
            - 이미 BROADCASTING 상태인 방송이 있으면 CHARACTER_ALREADY_BROADCASTING 예외를 발생시킨다.
         */
        if (broadcastRepository.existsByCharacterIdAndStatus(characterId, BroadcastStatus.BROADCASTING)) {
            throw new CustomException(BroadcastErrorCode.CHARACTER_ALREADY_BROADCASTING);
        }

        /*
            6. 고유 streamId 생성
            - 16자리 영숫자 랜덤 ID를 생성하고, 중복 시 재생성한다.
         */
        String streamId = streamIdGenerator.generate();

        /*
            7. Broadcast 엔티티 생성 및 저장
            - 생성한 streamId와 character로 Broadcast 객체를 생성하고 저장한다.
         */
        Broadcast broadcast = Broadcast.startBroadcast(streamId, character);
        Broadcast savedBroadcast = broadcastRepository.save(broadcast);

        /*
            8. FastAPI에 치지직 세션 연결 요청
            - DB 저장 후 FastAPI에 세션 연결을 동기 요청한다.
            - 실패 시 예외를 발생시켜 트랜잭션을 롤백한다.
         */
        String attemptId = UUID.randomUUID().toString();
        FastApiChzzkSessionCreateReqDto fastApiRequest = new FastApiChzzkSessionCreateReqDto(
                savedBroadcast.getStreamId(),
                attemptId,
                user.getChzzkAuthAccessToken()
        );
        FastApiChzzkSessionCreateResDto fastApiResponse = fastApiUtil.createChzzkSession(fastApiRequest)
                .block();
        validateFastApiChzzkSessionCreateResDto(savedBroadcast, attemptId, fastApiResponse);

        /*
            9. Redis 저장용 DTO 생성 및 커밋 후 저장 예약
            - 방송 시작 DB 커밋이 확정된 이후 Redis에 방송 캐릭터/사용자 정보를 저장한다.
         */
        BroadcastCharacterRedisDto redisDto = buildBroadcastCharacterRedisDto(character);
        boolean aiProactiveToChat = broadcastSettingRepository.findByUserId(userId)
                .map(BroadcastSetting::isAiProactiveToChat)
                .orElse(true);
        BroadcastUserRedisDto broadcastUserRedisDto = buildBroadcastUserRedisDto(fastApiResponse, aiProactiveToChat);
        registerBroadcastRedisSaveAfterCommit(savedBroadcast.getStreamId(), redisDto, broadcastUserRedisDto);

        /*
            10. ResponseDto 생성
            - 저장된 Broadcast의 streamId와 startedAt을 포맷하여 응답 DTO를 생성한다.
         */
        BroadcastStartResDto result = BroadcastStartResDto.builder()
                .broadcastStreamId(savedBroadcast.getStreamId())
                .broadcastStartedAt(savedBroadcast.getStartedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")))
                .build();

        log.info("[BroadcastService] startBroadcast() - END | streamId: {}", streamId);
        return result;
    }


    private void validateFastApiChzzkSessionCreateResDto(Broadcast savedBroadcast, String attemptId, FastApiChzzkSessionCreateResDto fastApiResponse) {
        if (fastApiResponse == null) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
        }
        if (!savedBroadcast.getStreamId().equals(fastApiResponse.broadcastStreamId())) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
        }
        if (!attemptId.equals(fastApiResponse.attemptId())) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_ATTEMPT_MISMATCH);
        }
        if (!StringUtils.hasText(fastApiResponse.sessionKey()) || !StringUtils.hasText(fastApiResponse.channelId())) {
            throw new CustomException(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID);
        }
    }


    /**
     * 방송 시작 전 치지직 인증 토큰 상태를 점검하는 함수
     * - 치지직 Auth Access Token / Refresh Token 저장 여부를 확인한다.
     * - Access Token 만료 시 Refresh Token으로 재발급을 시도한다.
     * - Refresh Token도 만료되었거나 유효하지 않으면 재인증 URL과 함께 예외를 발생시킨다.
     * @param userId : 방송을 시작하는 사용자 ID
     * @param user : Write Lock을 획득한 사용자 엔티티
     */
    private void ensureChzzkAuthReadyForBroadcast(Long userId, User user) {
        log.info("[BroadcastService] ensureChzzkAuthReadyForBroadcast() - START | userId: {}", userId);
        /*
            1. 치지직 Auth 토큰 저장 & 인증 여부 확인
            - 치지직 Auth Access Token / Refresh Token이 모두 저장되어 있어야 방송을 시작할 수 있다.
         */
        if (!user.hasChzzkAuthTokens()) {
            throw new CustomException(BroadcastErrorCode.CHZZK_AUTH_REQUIRED);
        }

        /*
            2. 치지직 Auth Access Token 만료 여부 확인
            - Access Token이 아직 유효하면 추가 작업 없이 방송 시작 로직을 이어서 진행한다.
         */
        if (!user.isChzzkAuthAccessTokenExpired()) {
            log.info("[BroadcastService] ensureChzzkAuthReadyForBroadcast() - accessExpired: {}, refreshExpired: {}, accessExpiresAt: {}, refreshExpiresAt: {}",
                    user.isChzzkAuthAccessTokenExpired(),
                    user.isChzzkAuthRefreshTokenExpired(),
                    user.getChzzkAuthAccessTokenExpiresAt(),
                    user.getChzzkAuthRefreshTokenExpiresAt());
            return;
        }

        try {
            /*
                3. 치지직 Access Token 재발급 시도
                - 저장된 Refresh Token으로 치지직 Access / Refresh Token 재발급을 시도한다.
             */
            log.info("[BroadcastService] ensureChzzkAuthReadyForBroadcast() - TRY_REFRESH");
            authService.refreshChzzkAccessToken(user);
            log.info("[BroadcastService] ensureChzzkAuthReadyForBroadcast() - END | userId: {}", userId);
        } catch (CustomException e) {
            /*
                4. Refresh Token 만료/무효 시 재인증 요구
                - Refresh Token이 로컬 기준 만료되었거나 치지직이 무효 토큰으로 응답한 경우 재인증 URL을 반환한다.
             */
            if (user.isChzzkAuthRefreshTokenExpired() || e.getErrorCode() == AuthErrorCode.CHZZK_AUTH_REFRESH_TOKEN_INVALID) {
                user.clearChzzkAuthTokens();
                AuthChzzkAuthUrlResDto authUrlResDto = authService.createChzzkAuthUrl(userId);
                throw new ChzzkReauthRequiredException(authUrlResDto);
            }

            throw e;
        }
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
        registerBroadcastTerminateSideEffectsAfterCommit(broadcast.getStreamId());

        log.info("[BroadcastService] terminateCurrentBroadcast() - END | streamId: {}", broadcast.getStreamId());
        return result;
    }

    /**
     * 현재 진행 중인 방송 정보 조회
     * - 사용자의 진행 중인 방송을 조회하고, 방송 캐릭터 정보와 최신 대화 목록을 반환한다.
     * - 최신 대화는 Redis에서 우선 조회하고, 부족한 경우 DB에서 추가 조회한다.
     * - hasNext, nextCursor 계산을 위해 요청 개수보다 1개 더 조회한다.
     * @param userId : 조회하는 사용자 ID
     * @param size : 조회할 최신 방송 대화 데이터 개수
     * @return : 현재 진행 중인 방송 정보 응답 DTO
     */
    @Transactional(readOnly = true)
    public CurrentStreamInfoResDto getCurrentStreamInfo(Long userId, Integer size) {
        log.info("[BroadcastService] getCurrentStreamInfo() - START | userId: {}, size: {}", userId, size);

        /*
            1. 요청 size 정규화 및 활성 방송 조회
            - 잘못된 size 입력을 방지하기 위해 최소 1 이상으로 보정한다.
            - userId 기준 진행 중인 방송이 없으면 ACTIVE_BROADCAST_NOT_FOUND 예외를 발생시킨다.
         */
        int normalizedSize = Math.max(size == null ? 10 : size, 1);
        Broadcast activeBroadcast = broadcastRepository.findActiveByUserId(userId, BroadcastStatus.BROADCASTING)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.ACTIVE_BROADCAST_NOT_FOUND));

        /*
            2. 방송 캐릭터 정보 구성
            - Redis 캐릭터 스냅샷을 우선 사용하고, 조회 실패 시 DB 엔티티를 기반으로 fallback 응답을 생성한다.
         */
        BroadcastCharacterInfoResDto broadcastCharacterInfo = buildBroadcastCharacterInfo(activeBroadcast);

        /*
            3. Redis 최신 대화 조회
            - hasNext, nextCursor 계산을 위해 요청 개수보다 1개 더 조회한다.
         */
        int fetchSize = normalizedSize + 1;
        List<BroadcastInfoRedisDto> redisDialogues = broadcastRedisUtil.getRecentActiveDialogues(
                activeBroadcast.getStreamId(),
                fetchSize
        );

        /*
            4. DB 부족분 조회
            - Redis 데이터가 부족하면 DB에서 추가로 최신 대화를 조회한다.
            - Redis에 일부 데이터가 있으면 가장 작은 cursorId보다 작은 데이터만 조회한다.
         */
        List<BroadcastDialogue> dbDialogues = getDbDialoguesForCurrentStreamInfo(activeBroadcast, redisDialogues, fetchSize);

        /*
            5. Redis + DB 병합 후 CursorSliceResponse 생성
            - 응답 본문에는 최신 size개만 담고, 초과 1개는 hasNext/nextCursor 계산에만 사용한다.
         */
        CursorSliceResponse<BroadcastDialogueCursorResDto> dialogueSlice = buildCurrentStreamDialogueSlice(
                redisDialogues,
                dbDialogues,
                normalizedSize
        );

        /*
            6. ResponseDto 생성
         */
        CurrentStreamInfoResDto result = CurrentStreamInfoResDto.builder()
                .broadcastCharacterInfo(broadcastCharacterInfo)
                .dialogueSlice(dialogueSlice)
                .build();

        log.info("[BroadcastService] getCurrentStreamInfo() - END | streamId: {}, contentSize: {}, hasNext: {}, nextCursor: {}",
                activeBroadcast.getStreamId(),
                dialogueSlice.content().size(),
                dialogueSlice.hasNext(),
                dialogueSlice.nextCursor());
        return result;
    }

    /**
     * 현재 방송 대화 cursor 조회
     * - 사용자의 진행 중인 방송이 있다면 cursorId 기준으로 과거 대화 데이터를 조회한다.
     * - Redis에서 우선 조회하고, 부족한 경우 DB에서 추가 조회한다.
     * - hasNext, nextCursor 계산을 위해 요청 개수보다 1개 더 조회한다.
     * @param userId : 조회하는 사용자 ID
     * @param size : 조회할 방송 대화 데이터 개수
     * @param cursorId : 조회 시작 기준 cursorId
     * @param aiCharacterDialogue : AI 캐릭터 대화 조회 여부
     * @param streamerDialogue : 스트리머 대화 조회 여부
     * @param viewerDialogue : 시청자 채팅 조회 여부
     * @return : CursorSliceResponse<BroadcastDialogueCursorItemResDto>
     */
    @Transactional(readOnly = true)
    public CursorSliceResponse<BroadcastDialogueCursorResDto> getBroadcastDialoguesByCursor(
            Long userId,
            Integer size,
            Long cursorId,
            Boolean aiCharacterDialogue,
            Boolean streamerDialogue,
            Boolean viewerDialogue
    ) {
        log.info("[BroadcastService] getBroadcastDialoguesByCursor() - START | userId: {}, size: {}, cursorId: {}, aiCharacterDialogue: {}, streamerDialogue: {}, viewerDialogue: {}",
                userId, size, cursorId, aiCharacterDialogue, streamerDialogue, viewerDialogue);

        /*
            1. 요청값 정규화 및 대화 주체 필터 생성
            - size는 최소 1 이상으로 보정한다.
            - 대화 주체 필터가 하나도 선택되지 않은 경우 INVALID_DIALOGUE_FILTER 예외를 발생시킨다.
         */
        int normalizedSize = Math.max(size == null ? 10 : size, 1);
        Set<DialogueSubject> dialogueSubjects = buildDialogueSubjectSet(
                aiCharacterDialogue,
                streamerDialogue,
                viewerDialogue
        );

        /*
            2. 활성 방송 조회
            - userId 기준 진행 중인 방송이 없으면 ACTIVE_BROADCAST_NOT_FOUND 예외를 발생시킨다.
         */
        Broadcast activeBroadcast = broadcastRepository.findActiveByUserId(userId, BroadcastStatus.BROADCASTING)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.ACTIVE_BROADCAST_NOT_FOUND));

        /*
            3. Redis cursor 대화 조회
            - cursorId 이하이면서 주체 필터에 해당하는 최신 대화를 요청 개수보다 1개 더 조회한다.
         */
        int fetchSize = normalizedSize + 1;
        List<BroadcastInfoRedisDto> redisDialogues = broadcastRedisUtil.getActiveDialoguesByCursor(
                activeBroadcast.getStreamId(),
                cursorId,
                fetchSize,
                dialogueSubjects
        );

        /*
            4. DB 부족분 조회
            - Redis 결과가 부족하면 DB에서 동일 필터 조건으로 부족한 개수만큼 추가 조회한다.
         */
        List<BroadcastDialogue> dbDialogues = getDbDialoguesByCursor(
                activeBroadcast,
                redisDialogues,
                cursorId,
                fetchSize,
                dialogueSubjects
        );

        /*
            5. CursorSliceResponse 생성
            - 기존 현재 방송 정보 조회 API와 동일한 extra 1개 처리 규칙을 적용한다.
         */
        CursorSliceResponse<BroadcastDialogueCursorResDto> result = buildCurrentStreamDialogueSlice(
                redisDialogues,
                dbDialogues,
                normalizedSize
        );

        log.info("[BroadcastService] getBroadcastDialoguesByCursor() - END | streamId: {}, contentSize: {}, hasNext: {}, nextCursor: {}",
                activeBroadcast.getStreamId(),
                result.content().size(),
                result.hasNext(),
                result.nextCursor());
        return result;
    }

    /**
     * 현재 방송 채팅 통계 조회
     * - 최근 10분 동안의 BroadcastStats를 기반으로 여론 현황과 AI 파트너 성향을 계산한다.
     * - 감정 흐름 통계를 계산하여 구간별로 반환한다.
     * @param userId : 조회하는 사용자 ID
     * @param statsCriteria : 구간 간격 (분): 1 | 5 | 10
     * @param timeRange : 조회 범위: 1=1시간, 3=3시간, 0=전체
     * @return : 방송 채팅 통계 응답 DTO
     */
    @Transactional(readOnly = true)
    public BroadcastChatStatsResDto getBroadcastChatStats(Long userId, Integer statsCriteria, Integer timeRange) {
        log.info("[BroadcastService] getBroadcastChatStats() - START | userId: {}, statsCriteria: {}, timeRange: {}", userId, statsCriteria, timeRange);

        /*
            1. 활성 방송 조회
            - userId 기준 진행 중인 방송이 없으면 ACTIVE_BROADCAST_NOT_FOUND 예외를 발생시킨다.
         */
        Broadcast activeBroadcast = broadcastRepository.findActiveByUserId(userId, BroadcastStatus.BROADCASTING)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.ACTIVE_BROADCAST_NOT_FOUND));

        /*
            2. 최근 10분 통계 조회 (여론 현황용)
            - 현재 시각 기준 10분 전부터의 BroadcastStats를 조회한다.
         */
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since10min = now.minusMinutes(10);
        List<BroadcastStats> recentStatsList = broadcastStatsRepository.findByBroadcastAndRecordedAtAfter(activeBroadcast, since10min);

        /*
            3. 여론 현황 계산
            - 감정별 채팅 수와 비율 계산은 공통 함수를 사용한다.
         */
        BroadcastChatStatsResDto.PublicOpinionResDto publicOpinion = calculatePublicOpinion(recentStatsList);

        /*
            5. AI 파트너 성향 판별
            - Redis의 BroadcastCharacterRedisDto에서 tendency를 가져온다.
          */
        BroadcastCharacterRedisDto broadcastCharacterRedisDto = broadcastRedisUtil.getBroadcastCharacterDto(activeBroadcast.getStreamId());
        AiCharacterTendency tendency = broadcastCharacterRedisDto.getTendency();

        /*
            7. 감정 흐름 통계 계산
            7-1. timeRange에 따른 조회 시작 시각 결정
         */
        LocalDateTime flowSince;
        if (timeRange == 3) {
            flowSince = now.minusHours(3);
        } else if (timeRange == 0) {
            flowSince = activeBroadcast.getStartedAt();
        } else {
            flowSince = now.minusHours(1);
        }
        LocalDateTime flowUntil = now;

        /*
            7-2. 감정 흐름용 통계 조회
         */
        List<BroadcastStats> flowStatsList = broadcastStatsRepository.findByBroadcastAndRecordedAtBetween(activeBroadcast, flowSince, flowUntil);

        /*
            7-3. statsCriteria 분 단위로 구간별 통계 생성
          */
        List<BroadcastChatStatsResDto.SentimentFlowItemResDto> sentimentFlow = buildSentimentFlow(
                flowStatsList,
                flowSince,
                flowUntil,
                statsCriteria,
                false
        );

        /*
            9. 상위 10개 키워드 조회
         */
        List<String> topKeywords = broadcastKeywordsRepository.findTop10KeywordsByBroadcast(activeBroadcast);

        /*
            10. 최종 응답 DTO 생성
         */
        BroadcastChatStatsResDto result = BroadcastChatStatsResDto.builder()
                .publicOpinion(publicOpinion)
                .aiPartnerTendency(tendency)
                .sentimentFlow(sentimentFlow)
                .topKeywords(topKeywords)
                .build();

        log.info("[BroadcastService] getBroadcastChatStats() - END | streamId: {}, total: {}, tendency: {}, flowSize: {}, topKeywordsSize: {}",
                activeBroadcast.getStreamId(), publicOpinion.totalChatCount(), tendency, sentimentFlow.size(), topKeywords.size());
        return result;
    }

    /**
     * 채팅 통계 여론 현황 계산
     * - BroadcastStats 목록의 긍정/중립/부정 채팅 수를 합산하고 비율을 계산한다.
     * @param statsList : 여론 현황 계산 대상 통계 목록
     * @return : 여론 현황 응답 DTO
     */
    private BroadcastChatStatsResDto.PublicOpinionResDto calculatePublicOpinion(List<BroadcastStats> statsList) {
        log.info("[BroadcastService] calculatePublicOpinion() - START | statsSize: {}", statsList.size());

        /*
            1. 긍정/중립/부정 채팅 수 합계 계산
            - 통계 목록 전체를 대상으로 감정별 채팅 수를 합산한다.
         */
        int positiveSum = statsList.stream().mapToInt(BroadcastStats::getPositiveChatCount).sum();
        int neutralSum = statsList.stream().mapToInt(BroadcastStats::getNeutralChatCount).sum();
        int negativeSum = statsList.stream().mapToInt(BroadcastStats::getNegativeChatCount).sum();
        int totalSum = positiveSum + neutralSum + negativeSum;

        /*
            2. 여론 현황 DTO 생성
            - 전체가 0이면 모든 비율은 0.0으로 반환한다.
         */
        BroadcastChatStatsResDto.PublicOpinionResDto result = BroadcastChatStatsResDto.PublicOpinionResDto.builder()
                .positiveChatCount(positiveSum)
                .neutralChatCount(neutralSum)
                .negativeChatCount(negativeSum)
                .totalChatCount(totalSum)
                .positiveRatio(calculateRoundedRatio(positiveSum, totalSum))
                .neutralRatio(calculateRoundedRatio(neutralSum, totalSum))
                .negativeRatio(calculateRoundedRatio(negativeSum, totalSum))
                .build();

        log.info("[BroadcastService] calculatePublicOpinion() - END | total: {}", totalSum);
        return result;
    }

    /**
     * 비율 소수점 첫째 자리 반올림 계산
     * - 분모가 0이면 0.0을 반환한다.
     * @param value : 비율 계산 대상 값
     * @param total : 전체 값
     * @return : 소수점 첫째 자리까지 반올림된 비율
     */
    private double calculateRoundedRatio(int value, int total) {
        log.info("[BroadcastService] calculateRoundedRatio() - START | value: {}, total: {}", value, total);

        /*
            1. 비율 계산
            - 전체 값이 없는 경우 0.0으로 처리한다.
         */
        double result = total > 0 ? Math.round((value * 1000.0) / total) / 10.0 : 0.0;

        log.info("[BroadcastService] calculateRoundedRatio() - END | ratio: {}", result);
        return result;
    }

    /**
     * 최종 채팅 여론 기반 AI 파트너 성향 판별
     * - 긍정/부정 중 하나가 단독 최다이면 해당 성향을 반환하고, 중립 우세 또는 동률이면 중립을 반환한다.
     * @param publicOpinion : 성향 판별 기준 여론 현황
     * @return : AI 파트너 응답 성향
     */
    private AiCharacterTendency determineTendencyByPublicOpinion(BroadcastChatStatsResDto.PublicOpinionResDto publicOpinion) {
        log.info("[BroadcastService] determineTendencyByPublicOpinion() - START | total: {}", publicOpinion.totalChatCount());

        /*
            1. 감정별 채팅 수 조회
            - 최종 여론의 감정별 채팅 수를 기준으로 성향을 판별한다.
         */
        int positiveCount = publicOpinion.positiveChatCount();
        int neutralCount = publicOpinion.neutralChatCount();
        int negativeCount = publicOpinion.negativeChatCount();

        /*
            2. 단독 최다 감정 판별
            - 긍정 또는 부정이 단독 최다인 경우에만 해당 성향을 반환한다.
         */
        AiCharacterTendency result = AiCharacterTendency.NEUTRAL;
        if (positiveCount > neutralCount && positiveCount > negativeCount) {
            result = AiCharacterTendency.POSITIVE;
        }
        if (negativeCount > positiveCount && negativeCount > neutralCount) {
            result = AiCharacterTendency.NEGATIVE;
        }

        log.info("[BroadcastService] determineTendencyByPublicOpinion() - END | tendency: {}", result);
        return result;
    }

    /**
     * 채팅 감정 흐름 통계 생성
     * - 지정된 분 단위 시간 구간으로 BroadcastStats를 그룹핑하고 구간별 감정 비율을 계산한다.
     * @param flowStatsList : 감정 흐름 계산 대상 통계 목록
     * @param flowSince : 감정 흐름 조회 시작 시각
     * @param flowUntil : 감정 흐름 조회 종료 시각
     * @param intervalMinutes : 구간 간격(분)
     * @param includeEmptyWindows : 통계가 없는 빈 구간 포함 여부
     * @return : 감정 흐름 응답 DTO 목록
     */
    private List<BroadcastChatStatsResDto.SentimentFlowItemResDto> buildSentimentFlow(
            List<BroadcastStats> flowStatsList,
            LocalDateTime flowSince,
            LocalDateTime flowUntil,
            Integer intervalMinutes,
            boolean includeEmptyWindows
    ) {
        log.info("[BroadcastService] buildSentimentFlow() - START | statsSize: {}, intervalMinutes: {}, includeEmptyWindows: {}",
                flowStatsList.size(), intervalMinutes, includeEmptyWindows);

        /*
            1. 구간 계산 기준값 보정
            - 잘못된 구간 간격은 1분으로 보정한다.
         */
        int safeIntervalMinutes = intervalMinutes != null && intervalMinutes > 0 ? intervalMinutes : 1;
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
        List<BroadcastChatStatsResDto.SentimentFlowItemResDto> result = new ArrayList<>();

        /*
            2. 시간 구간별 감정 통계 생성
            - flowSince부터 flowUntil까지 intervalMinutes 단위로 통계를 그룹핑한다.
         */
        LocalDateTime windowStart = flowSince;
        int statsIndex = 0;
        while (windowStart.isBefore(flowUntil)) {
            LocalDateTime windowEnd = windowStart.plusMinutes(safeIntervalMinutes);
            int positiveSum = 0;
            int neutralSum = 0;
            int negativeSum = 0;
            boolean hasStats = false;

            while (statsIndex < flowStatsList.size()) {
                BroadcastStats stats = flowStatsList.get(statsIndex);
                LocalDateTime recordedAt = stats.getRecordedAt();
                if (recordedAt.isBefore(windowStart)) {
                    statsIndex++;
                    continue;
                }
                if (!recordedAt.isBefore(windowEnd)) {
                    break;
                }

                positiveSum += stats.getPositiveChatCount();
                neutralSum += stats.getNeutralChatCount();
                negativeSum += stats.getNegativeChatCount();
                hasStats = true;
                statsIndex++;
            }

            if (hasStats || includeEmptyWindows) {
                int total = positiveSum + neutralSum + negativeSum;
                result.add(BroadcastChatStatsResDto.SentimentFlowItemResDto.builder()
                        .timeLabel(windowStart.format(timeFormatter))
                        .positiveRatio(calculateRoundedRatio(positiveSum, total))
                        .neutralRatio(calculateRoundedRatio(neutralSum, total))
                        .negativeRatio(calculateRoundedRatio(negativeSum, total))
                        .build());
            }

            windowStart = windowEnd;
        }

        log.info("[BroadcastService] buildSentimentFlow() - END | flowSize: {}", result.size());
        return result;
    }

    /**
     * 캐릭터 엔티티를 방송 Redis 저장 DTO로 변환하는 함수
     * @param character : 방송 캐릭터 엔티티
     * @return : 방송 캐릭터 Redis DTO
     */
    private BroadcastCharacterRedisDto buildBroadcastCharacterRedisDto(Character character) {
        log.info("[BroadcastService] buildBroadcastCharacterRedisDto() - START | characterId: {}", character.getId());

        List<String> characterTriggerWords = character.getTriggerWords()
                .stream()
                .sorted(Comparator.comparingInt(CharacterTriggerWord::getSortOrder))
                .map(CharacterTriggerWord::getWord)
                .toList();

        List<BroadcastCharacterImageRedisDto> characterImages;
        String characterImagePreset;

        /*
            1. 캐릭터 외형 타입별 Redis 이미지 데이터 생성
            - 2D인 경우 CharacterImage / CharacterImageDetail 기반으로 emotion별 이미지를 구성한다.
            - 3D인 경우 CharacterVrm 기반으로 Emotion 전체 개수만큼 imageUrl=null 데이터를 구성한다.
         */
        if (character.getCharacterAppearanceType() == CharacterAppearanceType.TWO_D) {
            CharacterImage characterImage = character.getCharacterImage();
            if (characterImage == null) {
                throw new CustomException(CharacterErrorCode.CHARACTER_IMAGE_NOT_FOUND);
            }

            characterImages = characterImage.getImageDetails()
                    .stream()
                    .sorted(Comparator.comparingLong(CharacterImageDetail::getId))
                    .map(imageDetail -> BroadcastCharacterImageRedisDto.builder()
                            .emotion(imageDetail.getEmotion())
                            .imageUrl(imageDetail.getImageUrl())
                            .build())
                    .toList();
            characterImagePreset = characterImage.getPreset();
        } else if (character.getCharacterAppearanceType() == CharacterAppearanceType.THREE_D) {
            CharacterVrm characterVrm = character.getCharacterVrm();
            if (characterVrm == null) {
                throw new CustomException(CharacterErrorCode.CHARACTER_VRM_NOT_FOUND);
            }

            characterImages = java.util.Arrays.stream(Emotion.values())
                    .map(emotion -> BroadcastCharacterImageRedisDto.builder()
                            .emotion(emotion)
                            .imageUrl(null)
                            .build())
                    .toList();
            characterImagePreset = characterVrm.getPresetId();
        } else {
            throw new CustomException(CharacterErrorCode.INVALID_CHARACTER_APPEARANCE_TYPE);
        }

        BroadcastCharacterRedisDto result = BroadcastCharacterRedisDto.builder()
                .characterId(character.getId())
                .characterName(character.getName())
                .characterGender(character.getGender())
                .characterTriggerWords(characterTriggerWords)
                .characterImagePreset(characterImagePreset)
                .characterImages(characterImages)
                .characterPresetType(character.getCharacterPersona().getPresetType())
                .isTalking(false)
                .tendency(AiCharacterTendency.NEUTRAL)
                .tendencyAutoUpdate(true)
                .build();

        log.info("[BroadcastService] buildBroadcastCharacterRedisDto() - END | characterId: {}", character.getId());
        return result;
    }

    private BroadcastUserRedisDto buildBroadcastUserRedisDto(
            FastApiChzzkSessionCreateResDto fastApiResponse,
            boolean aiProactiveToChat
    ){
        return BroadcastUserRedisDto.builder()
                .sessionKey(fastApiResponse.sessionKey())
                .channelId(fastApiResponse.channelId())
                .channelName(null)
                .aiProactiveToChat(aiProactiveToChat)
                .isStreamerSilent(false)
                .build();
    }

    private FastApiChzzkRedisChannelReqDto buildFastApiChzzkRedisChannelReqDto(
            String broadcastStreamId,
            BroadcastUserRedisDto broadcastUserRedisDto
    ) {
        return new FastApiChzzkRedisChannelReqDto(
                broadcastStreamId,
                broadcastUserRedisDto.getSessionKey(),
                broadcastUserRedisDto.getChannelName()
        );
    }

    /**
     * 현재 방송 정보 조회용 방송 캐릭터 정보 응답 DTO 생성
     * @param broadcast : 진행 중인 방송 엔티티
     * @return : 방송 캐릭터 정보 응답 DTO
     */
    private BroadcastCharacterInfoResDto buildBroadcastCharacterInfo(Broadcast broadcast) {
        log.info("[BroadcastService] buildBroadcastCharacterInfo() - START | streamId: {}", broadcast.getStreamId());

        BroadcastCharacterInfoResDto result;
        try {
            BroadcastCharacterRedisDto redisDto = broadcastRedisUtil.getBroadcastCharacterDto(broadcast.getStreamId());
            result = BroadcastCharacterInfoResDto.builder()
                    .characterId(redisDto.getCharacterId())
                    .characterName(redisDto.getCharacterName())
                    .triggerWords(redisDto.getCharacterTriggerWords())
                    .gender(redisDto.getCharacterGender())
                    .characterImageUrl(extractCharacterImageUrl(broadcast.getCharacter()))
                    .characterPersona(CharacterPersonaInfoResDto.builder()
                            .presetType(broadcast.getCharacter().getCharacterPersona().getPresetType())
                            .build())
                    .build();
        } catch (CustomException exception) {
            if (exception.getErrorCode() != BroadcastErrorCode.BROADCAST_CHARACTER_REDIS_NOT_FOUND) {
                throw exception;
            }
            result = buildBroadcastCharacterInfoFromEntity(broadcast.getCharacter());
        }

        log.info("[BroadcastService] buildBroadcastCharacterInfo() - END | streamId: {}, characterId: {}",
                broadcast.getStreamId(), result.characterId());
        return result;
    }

    /**
     * DB 엔티티 기반 현재 방송 캐릭터 정보 응답 DTO 생성
     * @param character : 캐릭터 엔티티
     * @return : 방송 캐릭터 정보 응답 DTO
     */
    private BroadcastCharacterInfoResDto buildBroadcastCharacterInfoFromEntity(Character character) {
        log.info("[BroadcastService] buildBroadcastCharacterInfoFromEntity() - START | characterId: {}", character.getId());

        List<String> triggerWords = character.getTriggerWords().stream()
                .sorted(Comparator.comparingInt(CharacterTriggerWord::getSortOrder))
                .map(CharacterTriggerWord::getWord)
                .toList();

        BroadcastCharacterInfoResDto result = BroadcastCharacterInfoResDto.builder()
                .characterId(character.getId())
                .characterName(character.getName())
                .triggerWords(triggerWords)
                .gender(character.getGender())
                .characterImageUrl(extractCharacterImageUrl(character))
                .characterPersona(CharacterPersonaInfoResDto.builder()
                        .presetType(character.getCharacterPersona().getPresetType())
                        .build())
                .build();

        log.info("[BroadcastService] buildBroadcastCharacterInfoFromEntity() - END | characterId: {}", character.getId());
        return result;
    }

    /**
     * 현재 방송 정보 조회용 DB 대화 부족분 조회
     * @param broadcast : 진행 중인 방송 엔티티
     * @param redisDialogues : Redis에서 조회한 대화 목록
     * @param fetchSize : 최대 조회 개수 (size + 1)
     * @return : DB에서 조회한 대화 목록
     */
    private List<BroadcastDialogue> getDbDialoguesForCurrentStreamInfo(
            Broadcast broadcast,
            List<BroadcastInfoRedisDto> redisDialogues,
            int fetchSize
    ) {
        log.info("[BroadcastService] getDbDialoguesForCurrentStreamInfo() - START | broadcastId: {}, redisSize: {}, fetchSize: {}",
                broadcast.getId(), redisDialogues.size(), fetchSize);

        /*
            1. DB에서 추가로 조회해야할 데이터 개수 계산 
            - 만약 추가로 조회해야할 데이터가 없다면 함수 종료
         */
        int dbFetchSize = fetchSize - redisDialogues.size();
        if (dbFetchSize <= 0) {
            log.info("[BroadcastService] getDbDialoguesForCurrentStreamInfo() - END | action: skip_db_fetch");
            return List.of();
        }

        /*
            2. DB에서 추가로 조회해야할 데이터가 있다면 해당 데이터 개수만큼 조회
                1) Redis에서 조회한 데이터가 없다면 최신 데이터들 Cursor Id 기준 내림차순 조회
                2) Redis에서 조회한 데이터가 있다면, 해당 데이터들 중 cursorId가 가장 작은 (가장 예전 대화 데이터) cursorId보다 더 적은 데이터들만 내림차순 조회

         */
        PageRequest pageable = PageRequest.of(0, dbFetchSize);
        List<BroadcastDialogue> result;

        if (redisDialogues.isEmpty()) {
            result = broadcastDialogueRepository.findByBroadcastIdOrderByCursorIdDesc(broadcast.getId(), pageable);
        } else {
            Long oldestRedisCursorId = redisDialogues.getFirst().cursorId();
            result = broadcastDialogueRepository.findByBroadcastIdAndCursorIdLessThanOrderByCursorIdDesc(
                    broadcast.getId(),
                    oldestRedisCursorId,
                    pageable
            );
        }

        log.info("[BroadcastService] getDbDialoguesForCurrentStreamInfo() - END | resultSize: {}", result.size());
        return result;
    }

    /**
     * cursor 기반 방송 대화 조회용 DB 부족분 조회
     * @param broadcast : 진행 중인 방송 엔티티
     * @param redisDialogues : Redis에서 조회한 대화 목록
     * @param cursorId : 조회 시작 기준 cursorId
     * @param fetchSize : 최대 조회 개수 (size + 1)
     * @param dialogueSubjects : 조회할 대화 주체 목록
     * @return : DB에서 조회한 대화 목록
     */
    private List<BroadcastDialogue> getDbDialoguesByCursor(
            Broadcast broadcast,
            List<BroadcastInfoRedisDto> redisDialogues,
            Long cursorId,
            int fetchSize,
            Set<DialogueSubject> dialogueSubjects
    ) {
        log.info("[BroadcastService] getDbDialoguesByCursor() - START | broadcastId: {}, redisSize: {}, cursorId: {}, fetchSize: {}, dialogueSubjects: {}",
                broadcast.getId(), redisDialogues.size(), cursorId, fetchSize, dialogueSubjects);

        /*
            1. DB에서 추가로 조회해야할 데이터 개수 계산
            - 만약 추가로 조회해야할 데이터가 없다면 함수 종료
         */
        int dbFetchSize = fetchSize - redisDialogues.size();
        if (dbFetchSize <= 0) {
            log.info("[BroadcastService] getDbDialoguesByCursor() - END | action: skip_db_fetch");
            return List.of();
        }

        /*
            2. DB에서 추가로 조회해야할 데이터가 있다면 해당 데이터 개수만큼 조회
                1) Redis에서 조회한 데이터가 없다면 cursorId 이하 데이터들을 cursorId 기준 내림차순 조회
                2) Redis에서 조회한 데이터가 있다면 해당 데이터들 중 가장 작은 cursorId보다 더 적은 데이터들만 내림차순 조회
         */
        PageRequest pageable = PageRequest.of(0, dbFetchSize);
        List<BroadcastDialogue> result;

        if (redisDialogues.isEmpty()) {
            result = broadcastDialogueRepository.findByBroadcastIdAndCursorIdLessThanEqualAndSubjectInOrderByCursorIdDesc(
                    broadcast.getId(),
                    cursorId,
                    dialogueSubjects,
                    pageable
            );
        } else {
            Long oldestRedisCursorId = redisDialogues.getFirst().cursorId();
            result = broadcastDialogueRepository.findByBroadcastIdAndCursorIdLessThanAndSubjectInOrderByCursorIdDesc(
                    broadcast.getId(),
                    oldestRedisCursorId,
                    dialogueSubjects,
                    pageable
            );
        }

        log.info("[BroadcastService] getDbDialoguesByCursor() - END | resultSize: {}", result.size());
        return result;
    }

    /**
     * 현재 방송 정보 조회용 CursorSliceResponse 생성
     * @param redisDialogues : Redis 대화 목록
     * @param dbDialogues : DB 대화 목록
     * @param size : 응답에 포함할 최대 대화 개수
     * @return : CursorSliceResponse<BroadcastDialogueCursorItemResDto>
     */
    private CursorSliceResponse<BroadcastDialogueCursorResDto> buildCurrentStreamDialogueSlice(
            List<BroadcastInfoRedisDto> redisDialogues,
            List<BroadcastDialogue> dbDialogues,
            int size
    ) {
        log.info("[BroadcastService] buildCurrentStreamDialogueSlice() - START | redisSize: {}, dbSize: {}, size: {}",
                redisDialogues.size(), dbDialogues.size(), size);

        /*
            1. mergedDialogues 리스트에 DB 데이터 오름차순으로 변경 -> Redis 대화 데이터순으로 데이터를 추가한다

         */
        List<BroadcastDialogueCursorProjection> mergedDialogues = new ArrayList<>();
        dbDialogues.reversed().forEach(dialogue -> mergedDialogues.add(BroadcastDialogueCursorProjection.fromEntity(dialogue)));
        redisDialogues.forEach(dialogue -> mergedDialogues.add(BroadcastDialogueCursorProjection.fromRedis(dialogue)));

        /*
            2. 다음 대화가 계속해서 존재하는지 확인
            - 만약 대화가 존재하지 않거나 다음 데이터가 존재하지 않는 경우 nextCursor는 null
            - 다음 데이터가 계속 존재하는 경우 nextCursor는 추가로 가져온 데이터의 cursorId로 설정
         */
        boolean hasNext = mergedDialogues.size() > size;
        Long nextCursor;
        if(mergedDialogues.isEmpty() || !hasNext) nextCursor = null;
        else nextCursor = mergedDialogues.getFirst().cursorId();

        List<BroadcastDialogueCursorProjection> responseDialogues = hasNext
                ? mergedDialogues.stream()
                    .skip(1) // 1개의 값을 건너뜀. 현재 mergedDialogues에 들어있는 0번 인덱스의 값은 Cursor를 위해 추가로 가져온 데이터이므로 건너뛴다.
                    .toList()
                : mergedDialogues;

        CursorSliceResponse<BroadcastDialogueCursorResDto> result = CursorSliceResponse.<BroadcastDialogueCursorResDto>builder()
                .content(responseDialogues.stream()
                        .map(this::toBroadcastDialogueCursorItemResDto)
                        .toList())
                .size(size)
                .hasNext(hasNext)
                .nextCursor(nextCursor)
                .build();

        log.info("[BroadcastService] buildCurrentStreamDialogueSlice() - END | contentSize: {}, hasNext: {}, nextCursor: {}",
                result.content().size(), result.hasNext(), result.nextCursor());
        return result;
    }

    /**
     * 현재 방송 대화 임시 객체를 응답 DTO로 변환
     * @param dialogue : 대화 임시 객체
     * @return : 방송 대화 커서 응답 DTO
     */
    private BroadcastDialogueCursorResDto toBroadcastDialogueCursorItemResDto(BroadcastDialogueCursorProjection dialogue) {
        return BroadcastDialogueCursorResDto.builder()
                .cursorId(dialogue.cursorId())
                .subject(dialogue.subject())
                .content(dialogue.content())
                .createdAt(dialogue.createdAt().format(BROADCAST_DATE_TIME_FORMATTER))
                .build();
    }

    /**
     * 캐릭터 대표 이미지 URL 추출
     * @param character : 캐릭터 엔티티
     * @return : 대표 이미지 URL
     */
    private String extractCharacterImageUrl(Character character) {
        if (character.getCharacterAppearanceType() == CharacterAppearanceType.TWO_D) {
            CharacterImage characterImage = character.getCharacterImage();
            if (characterImage == null) {
                throw new CustomException(CharacterErrorCode.CHARACTER_IMAGE_NOT_FOUND);
            }

            return characterImage.getImageDetails().stream()
                    .sorted(Comparator.comparingLong(CharacterImageDetail::getId))
                    .map(CharacterImageDetail::getImageUrl)
                    .findFirst()
                    .orElse("");
        }

        if (character.getCharacterAppearanceType() == CharacterAppearanceType.THREE_D) {
            CharacterVrm characterVrm = character.getCharacterVrm();
            if (characterVrm == null) {
                throw new CustomException(CharacterErrorCode.CHARACTER_VRM_NOT_FOUND);
            }

            return characterVrm.getThumbnailUrl();
        }

        throw new CustomException(CharacterErrorCode.INVALID_CHARACTER_APPEARANCE_TYPE);
    }

    /**
     * 요청된 대화 필터를 DialogueSubject 집합으로 변환
     * @param aiCharacterDialogue : AI 캐릭터 대화 조회 여부
     * @param streamerDialogue : 스트리머 대화 조회 여부
     * @param viewerDialogue : 시청자 채팅 조회 여부
     * @return : 조회할 대화 주체 집합
     */
    private Set<DialogueSubject> buildDialogueSubjectSet(
            Boolean aiCharacterDialogue,
            Boolean streamerDialogue,
            Boolean viewerDialogue
    ) {
        EnumSet<DialogueSubject> dialogueSubjects = EnumSet.noneOf(DialogueSubject.class);
        if (Boolean.TRUE.equals(aiCharacterDialogue)) {
            dialogueSubjects.add(DialogueSubject.AI_CHARACTER);
        }
        if (Boolean.TRUE.equals(streamerDialogue)) {
            dialogueSubjects.add(DialogueSubject.STREAMER);
        }
        if (Boolean.TRUE.equals(viewerDialogue)) {
            dialogueSubjects.add(DialogueSubject.VIEWER);
        }

        if (dialogueSubjects.isEmpty()) {
            throw new CustomException(BroadcastErrorCode.INVALID_DIALOGUE_FILTER);
        }
        return dialogueSubjects;
    }

    /**
     * 트랜잭션 커밋 후 방송 캐릭터 정보를 Redis에 저장하도록 예약
     * - DB 변경이 확정된 후에 Redis 저장을 수행해 데이터 불일치를 줄인다.
     * - Redis 저장 성공 시 WebSocket 연결 타임아웃 작업을 등록한다.
     * - Redis 저장 실패 시 타임아웃을 등록하지 않는다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param redisDto : Redis에 저장할 방송 캐릭터 정보
     * @param broadcastUserRedisDto : Redis에 저장할 방송 사용자 정보
     */
    private void registerBroadcastRedisSaveAfterCommit(
            String broadcastStreamId,
            BroadcastCharacterRedisDto redisDto,
            BroadcastUserRedisDto broadcastUserRedisDto
    ) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String subscribedChannelId = null;
                boolean fastApiConnected = false;
                try {
                    /*
                        1. BroadcastCharacterValue, BroadcastUserValue, SummarySlot 초기화 & Redis Channel 구독
                     */
                    broadcastRedisUtil.setBroadcastCharacterValue(broadcastStreamId, redisDto);
                    broadcastRedisUtil.initializeSummarySlot(broadcastStreamId);
                    subscribedChannelId = broadcastUserRedisDto.getChannelId();
                    String channelName = chatRedisUtil.subscribeChannelPattern(subscribedChannelId);
                    broadcastUserRedisDto.setChannelName(channelName);
                    broadcastRedisUtil.setBroadcastUserValue(broadcastStreamId, broadcastUserRedisDto);

                    /*
                        2. FastApi로 Redis Channel로 구독 완료 요청
                        - 동기적으로 Redis Channel "FastAPI <-> Redis <-> Spring Boot" 연결 완료 응답 수신
                     */
                    FastApiChzzkRedisChannelResDto response = fastApiUtil.connectChzzkRedisChannel(
                            buildFastApiChzzkRedisChannelReqDto(broadcastStreamId, broadcastUserRedisDto)
                    );
                    fastApiConnected = "연결 성공".equals(response.status());

                    /*
                        3. FastApi 세션 연결과 FastApi와의 Pub Sub Redis 연결까지 완료한 뒤에, Connection Timeout을 등록한다.
                     */
                    broadcastConnectionTimeoutService.registerConnectionTimeout(broadcastStreamId);
                } catch (Exception e) {
                    /*
                        4. 위 과정에서 예외가 발생하면 방송 시작 afterCommit() 로직을 롤백한다.
                     */
                    log.error("[BroadcastService] 방송 캐릭터 정보 Redis 저장 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
                    rollbackBroadcastStartAfterCommit(broadcastStreamId, broadcastUserRedisDto, subscribedChannelId, fastApiConnected);
                }
            }
        });
    }

    /**
     * AfterCommit 과정에서 예외가 발생했을 때 과정을 RollBack 한다
     * @param broadcastStreamId
     * @param broadcastUserRedisDto
     * @param subscribedChannelId
     * @param fastApiConnected
     */
    private void rollbackBroadcastStartAfterCommit(
            String broadcastStreamId,
            BroadcastUserRedisDto broadcastUserRedisDto,
            String subscribedChannelId,
            boolean fastApiConnected
    ) {
        try {
            /*
                1. fastApi가 연결되어있고, BroadcastUser:broadcastStreamId에 channelName이 저장되어있는 경우
                - fastApi에게 Session Registry에 연결되어있는 ChzzkRedisChannel을 연결해제하도록 설정
                - fastApi의 ChzzkRedisChannel이 해제될 때까지 동기적으로 대기한다.
             */
            if (fastApiConnected && StringUtils.hasText(broadcastUserRedisDto.getChannelName())) {
                fastApiUtil.disconnectChzzkRedisChannel(
                        buildFastApiChzzkRedisChannelReqDto(broadcastStreamId, broadcastUserRedisDto)
                );
            }
        } catch (Exception e) {
            log.error("[BroadcastService] rollbackBroadcastStartAfterCommit() - FastAPI disconnect failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }

        try {
            /*
                2. fastApi가 Redis Channel 연결을 해제한 이후, Spring Boot의 Chat Redis 구독을 끊는다.
             */
            if (StringUtils.hasText(subscribedChannelId)) {
                chatRedisUtil.unsubscribeChannelPattern(subscribedChannelId);
            }
        } catch (Exception e) {
            log.error("[BroadcastService] rollbackBroadcastStartAfterCommit() - Chat unsubscribe failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }

        try {
            /*
                3. Broadcast Redis에 저장되어있는 값들을 삭제한다.
                - BroadcastCharacterValue
                - BroadcastUserValue
                - BroadcastInfo
             */
            broadcastRedisUtil.deleteBroadcastCharacterValue(broadcastStreamId);
            broadcastRedisUtil.deleteBroadcastUserValue(broadcastStreamId);
            broadcastRedisUtil.deleteBroadcastInfo(broadcastStreamId);
        } catch (Exception e) {
            log.error("[BroadcastService] rollbackBroadcastStartAfterCommit() - Redis rollback failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }
    }

    /**
     * 트랜잭션 커밋 후 방송 종료 부가 처리 예약
     * - DB 변경이 확정된 후에 Redis 삭제 및 WebSocket 종료를 수행한다.
     *
     * @param broadcastStreamId : 종료할 방송 스트림 ID
     */
    private void registerBroadcastTerminateSideEffectsAfterCommit(String broadcastStreamId) {
        log.debug("[BroadcastService] registerBroadcastTerminateSideEffectsAfterCommit() - START | broadcastStreamId: {}", broadcastStreamId);
        /*
            TransactionSynchronizationManager : Spring에서 트랜잭션 생명주기 이벤트에 콜백을 등록할 수 있게 해주는 유틸리티 클래스
            - 트랜잭션의 특정 시점에 원하는 코드를 실행할 수 있다.
         */
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                streamerSilenceService.cancel(broadcastStreamId);
                proactiveChatService.cancel(broadcastStreamId);
                BroadcastUserRedisDto broadcastUserRedisDto = null;
                boolean remainingDialoguesSaved = false;
                try {
                    // 1. Redis에 남아있는 대화 데이터를 DB에 저장
                    broadcastDialoguePersistenceService.saveRemainingRedisDialogues(broadcastStreamId);
                    remainingDialoguesSaved = true;
                } catch (Exception e) {
                    log.error("[BroadcastService] 방송 종료 잔여 대화 저장 중 오류 발생 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
                }

                if (remainingDialoguesSaved) {
                    try {
                        // 2. 동기적으로 방송 데이터 분석
                        broadcastAnalysisService.analysisBroadcastDialogues(broadcastStreamId);
                    } catch (Exception e) {
                        log.error("[BroadcastService] 방송 분석 중 오류 발생 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
                    }
                } else {
                    log.warn("[BroadcastService] 잔여 대화 저장 실패로 방송 분석을 건너뜁니다. | streamId: {}", broadcastStreamId);
                }

                try {
                    // 3. Redis에서 방송 User 정보 조회
                    broadcastUserRedisDto = broadcastRedisUtil.getBroadcastUserDto(broadcastStreamId);
                    // 4. 치지직 Redis 채널 연결 해제 로직
                    if (StringUtils.hasText(broadcastUserRedisDto.getChannelName())) {
                        fastApiUtil.disconnectChzzkRedisChannel(
                                buildFastApiChzzkRedisChannelReqDto(broadcastStreamId, broadcastUserRedisDto)
                        );
                    }
                    if (StringUtils.hasText(broadcastUserRedisDto.getChannelId())) {
                        chatRedisUtil.unsubscribeChannelPattern(broadcastUserRedisDto.getChannelId());
                    }
                } catch (Exception e) {
                    log.error("[BroadcastService] 방송 종료 정리 중 오류 발생 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
                } finally {
                    try { broadcastRedisUtil.deleteBroadcastCharacterValue(broadcastStreamId); }
                    catch (Exception e) { log.error("[BroadcastService] 방송 캐릭터 정보 Redis 삭제 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e); }
                    try { broadcastRedisUtil.deleteBroadcastUserValue(broadcastStreamId); }
                    catch (Exception e) { log.error("[BroadcastService] 방송 유저 정보 Redis 삭제 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e); }
                    try { broadcastRedisUtil.deleteBroadcastInfo(broadcastStreamId); }
                    catch (Exception e) { log.error("[BroadcastService] 방송 정보 Redis 삭제 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e); }
                }

                sessionRegistry.disconnect(
                        broadcastStreamId,
                        CloseStatus.NORMAL.withReason("Broadcast terminated")
                );
            }
        });
        log.debug("[BroadcastService] registerBroadcastTerminateSideEffectsAfterCommit() - END | broadcastStreamId: {}", broadcastStreamId);
    }

    @JsonPropertyOrder({"cursorId", "subject", "content", "createdAt"})
    private record BroadcastDialogueCursorProjection(
            Long cursorId,
            com.example.sku_sw.domain.broadcast.enums.DialogueSubject subject,
            String content,
            LocalDateTime createdAt
    ) {
        private static BroadcastDialogueCursorProjection fromRedis(BroadcastInfoRedisDto dialogue) {
            return new BroadcastDialogueCursorProjection(
                    dialogue.cursorId(),
                    dialogue.subject(),
                    dialogue.content(),
                    dialogue.createdAt()
            );
        }

        private static BroadcastDialogueCursorProjection fromEntity(BroadcastDialogue dialogue) {
            return new BroadcastDialogueCursorProjection(
                    dialogue.getCursorId(),
                    dialogue.getSubject(),
                    dialogue.getContent(),
                    dialogue.getCreatedAt()
            );
        }
    }

    /**
     * AI 캐릭터 편승 태도를 수정하는 함수
     * - version=AUTO: tendencyAutoUpdate를 true로 설정, tendency를 NEUTRAL로 리셋 (자동 판별 모드 전환)
     * - version=MANUAL: tendencyAutoUpdate를 false로 설정, tendency를 지정된 값으로 고정
     * @param userId : 요청 사용자 ID
     * @param reqDto : 편승 태도 수정 요청 DTO
     * @return : 이전 상태 정보를 담은 응답 DTO
     */
    public BroadcastTendencyUpdateResDto updateCharacterTendency(Long userId, BroadcastTendencyUpdateReqDto reqDto) {
        log.info("[BroadcastService] updateCharacterTendency() - START | userId: {}, version: {}, tendency: {}",
                userId, reqDto.version(), reqDto.tendency());

        /*
            1. 활성 방송 조회
            - userId 기준 진행 중인 방송이 없으면 ACTIVE_BROADCAST_NOT_FOUND 예외를 발생시킨다.
         */
        Broadcast activeBroadcast = broadcastRepository.findActiveByUserId(userId, BroadcastStatus.BROADCASTING)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.ACTIVE_BROADCAST_NOT_FOUND));

        /*
            2. version에 따라 Redis 원자 업데이트 수행
            - AUTO: tendencyAutoUpdate=true, tendency=NEUTRAL로 리셋
            - MANUAL: tendencyAutoUpdate=false, tendency=reqDto.tendency()로 고정
         */
        String[] prevValues;
        if (reqDto.version() == TendencyVersion.AUTO) {
            prevValues = broadcastRedisUtil.updateBroadcastCharacterTendencyAuto(activeBroadcast.getStreamId());
        } else {
            prevValues = broadcastRedisUtil.updateBroadcastCharacterTendencyManually(
                    activeBroadcast.getStreamId(), reqDto.tendency());
        }

        /*
            3. ResponseDto 생성
            - Lua 스크립트에서 반환된 이전 상태 값을 DTO로 매핑한다.
         */
        TendencyVersion prevVersion = "true".equals(prevValues[1]) ? TendencyVersion.AUTO : TendencyVersion.MANUAL;
        AiCharacterTendency prevTendency = AiCharacterTendency.valueOf(prevValues[0]);

        BroadcastTendencyUpdateResDto result = BroadcastTendencyUpdateResDto.builder()
                .prevVersion(prevVersion)
                .prevTendency(prevTendency)
                .build();

        log.info("[BroadcastService] updateCharacterTendency() - END | prevVersion: {}, prevTendency: {}",
                result.prevVersion(), result.prevTendency());
        return result;
    }

    /**
     * 달별 방송 기록 조회
     * - 해당 월의 방송 기록을 리스트로 조회한다.
     * - 각 방송의 방송 시간(broadcastTime)은 startedAt ~ terminatedAt(또는 현재 시각)의 차이로 계산된다.
     * - 방송 시간의 ms는 반올림되어 HH:MM:SS 형식으로 반환된다.
     * @param userId : 조회할 사용자 ID
     * @param year : 조회할 연도
     * @param month : 조회할 월
     * @return : BroadcastMonthResDto
     */
    @Transactional(readOnly = true)
    public BroadcastMonthResDto getBroadcastMonth(Long userId, int year, int month) {
        log.info("[BroadcastService] getBroadcastMonth() - START | userId: {}, year: {}, month: {}", userId, year, month);

        /*
            1. 해당 월의 방송 목록 조회
            - userId, year, month로 해당 월의 방송 목록을 조회한다.
        */
        List<Broadcast> broadcasts = broadcastRepository.findAllByUserIdAndMonth(userId, year, month);

        /*
            2. BroadcastMonthInfoResDto 리스트로 변환
            - 각 방송에 대해 day, broadcastId, characterId, characterName, broadcastStatus, broadcastTime을 매핑한다.
            - broadcastTime은 Duration으로 계산하며, ms는 반올림하여 HH:MM:SS 형식으로 포맷한다.
        */
        List<BroadcastMonthInfoResDto> infoList = broadcasts.stream()
                .map(broadcast -> {
                    LocalDateTime endTime = broadcast.getTerminatedAt() != null
                            ? broadcast.getTerminatedAt()
                            : LocalDateTime.now();
                    Duration duration = Duration.between(broadcast.getStartedAt(), endTime);

                    long totalSeconds = duration.getSeconds();
                    int nanos = duration.getNano();
                    if (nanos >= 500_000_000) {
                        totalSeconds++;
                    }

                    long hours = totalSeconds / 3600;
                    long minutes = (totalSeconds % 3600) / 60;
                    long seconds = totalSeconds % 60;
                    String broadcastTime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

                    return BroadcastMonthInfoResDto.create(
                            broadcast.getStartedAt().getDayOfMonth(),
                            broadcast.getId(),
                            broadcast.getCharacter().getId(),
                            broadcast.getCharacter().getName(),
                            broadcast.getStatus(),
                            broadcastTime
                    );
                })
                .toList();

        /*
            3. BroadcastMonthResDto 생성
            - 방송 정보 리스트, 조회 연도, 조회 월을 포함하는 응답 DTO를 생성한다.
        */
        BroadcastMonthResDto result = BroadcastMonthResDto.create(infoList, year, month);
        log.info("[BroadcastService] getBroadcastMonth() - END | resultSize: {}", infoList.size());
        return result;
    }

    /**
     * 방송 통계 조회
     * - 특정 방송의 통계 정보를 조회한다.
     * - 본인 방송만 조회 가능하며, broadcastId + userId 조건으로 검증한다.
     * - 캐릭터 정보, 방송 정보, 최근 5개 대화를 포함하여 반환한다.
     * @param userId : 조회하는 사용자 ID
     * @param broadcastId : 조회할 방송 ID
     * @return : 방송 통계 응답 DTO
     */
    @Transactional(readOnly = true)
    public BroadcastDayStatsResDto getBroadcastDayStats(Long userId, Long broadcastId) {
        log.info("[BroadcastService] getBroadcastDayStats() - START | userId: {}, broadcastId: {}", userId, broadcastId);

        /*
            1. 방송 단건 조회
            - broadcastId와 userId 조건으로 방송을 조회한다.
            - 존재하지 않거나 본인 방송이 아니면 BROADCAST_NOT_FOUND 예외를 발생시킨다.
         */
        Broadcast broadcast = broadcastRepository.findByIdAndUserId(broadcastId, userId)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND));

        /*
            2. 응답 하위 정보 구성
            - 캐릭터 정보, 방송 정보, 채팅 정보를 각각 별도 함수에서 구성한다.
         */
        BroadcastDayCharacterInfoResDto characterInfo = buildBroadcastDayCharacterInfo(broadcast.getCharacter());
        BroadcastDayBroadcastInfoResDto broadcastInfo = buildBroadcastDayBroadcastInfo(broadcast);
        BroadcastDayChatInfoResDto chatInfo = buildBroadcastDayChatInfo(broadcast);

        /*
            3. 최종 응답 DTO 생성
         */
        BroadcastDayStatsResDto result = BroadcastDayStatsResDto.builder()
                .characterInfo(characterInfo)
                .broadcastInfo(broadcastInfo)
                .chatAnalysisInfo(chatInfo)
                .build();

        log.info("[BroadcastService] getBroadcastDayStats() - END | broadcastId: {}, streamId: {}", broadcastId, broadcast.getStreamId());
        return result;
    }

    /**
     * 방송 통계 조회용 캐릭터 정보 구성
     * - 캐릭터 기본 정보, 대표 이미지 URL, 페르소나, 호출어 목록을 조합한다.
     * - 호출어와 2D 이미지 상세 정보는 별도 Repository로 조회한다.
     * @param character : 방송에 연결된 캐릭터 엔티티
     * @return : 방송 통계 캐릭터 정보 응답 DTO
     */
    private BroadcastDayCharacterInfoResDto buildBroadcastDayCharacterInfo(Character character) {
        log.info("[BroadcastService] buildBroadcastDayCharacterInfo() - START | characterId: {}", character.getId());

        /*
            1. 호출어 목록 조회
            - Character.triggerWords 컬렉션에 직접 접근하지 않고 Repository로 정렬 조회한다.
         */
        List<String> triggerWords = characterTriggerWordRepository.findAllByCharacterIdOrderBySortOrderAsc(character.getId())
                .stream()
                .map(CharacterTriggerWord::getWord)
                .toList();

        /*
            2. 대표 이미지 URL 조회
            - 2D 캐릭터는 CharacterImageDetailRepository로 첫 번째 이미지 상세를 조회한다.
            - 3D 캐릭터는 CharacterVrm thumbnailUrl을 사용한다.
         */
        String imageUrl = cloudfrontDomain + resolveBroadcastDayCharacterImageUrl(character);

        /*
            3. 캐릭터 정보 DTO 생성
         */
        BroadcastDayCharacterInfoResDto result = BroadcastDayCharacterInfoResDto.create(character.getName(), character.getGender(), imageUrl, character.getCharacterPersona().getPresetType(), triggerWords);

        log.info("[BroadcastService] buildBroadcastDayCharacterInfo() - END | characterId: {}", character.getId());
        return result;
    }

    /**
     * 방송 통계 조회용 캐릭터 대표 이미지 URL 조회
     * - 2D 캐릭터는 CharacterImageDetailRepository로 첫 번째 이미지 상세를 조회한다.
     * - 3D 캐릭터는 CharacterVrm thumbnailUrl을 반환한다.
     * @param character : 대표 이미지 URL을 조회할 캐릭터 엔티티
     * @return : 대표 이미지 URL
     */
    private String resolveBroadcastDayCharacterImageUrl(Character character) {
        log.info("[BroadcastService] resolveBroadcastDayCharacterImageUrl() - START | characterId: {}", character.getId());
        // 1. 2D 캐릭터인 경우
        if (character.getCharacterAppearanceType() == CharacterAppearanceType.TWO_D) {
            CharacterImage characterImage = character.getCharacterImage();
            if (characterImage == null) {
                throw new CustomException(CharacterErrorCode.CHARACTER_IMAGE_NOT_FOUND);
            }

            String result = characterImageDetailRepository.findFirstByCharacterImageIdOrderByIdAsc(characterImage.getId())
                    .map(CharacterImageDetail::getImageUrl)
                    .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_IMAGE_NOT_FOUND));

            log.info("[BroadcastService] resolveBroadcastDayCharacterImageUrl() - END | characterId: {}, appearanceType: {}",
                    character.getId(), character.getCharacterAppearanceType());
            return result;
        }
        // 2. 3D 캐릭터인 경우
        if (character.getCharacterAppearanceType() == CharacterAppearanceType.THREE_D) {
            CharacterVrm characterVrm = character.getCharacterVrm();
            if (characterVrm == null) {
                throw new CustomException(CharacterErrorCode.CHARACTER_VRM_NOT_FOUND);
            }

            String result = characterVrm.getThumbnailUrl();
            log.info("[BroadcastService] resolveBroadcastDayCharacterImageUrl() - END | characterId: {}, appearanceType: {}",
                    character.getId(), character.getCharacterAppearanceType());
            return result;
        }

        throw new CustomException(CharacterErrorCode.INVALID_CHARACTER_APPEARANCE_TYPE);
    }

    /**
     * 방송 통계 조회용 방송 정보 구성
     * - 방송 기본 정보, 최근 5개 대화 목록, 방송 분석 결과를 조합한다.
     * @param broadcast : 방송 엔티티
     * @return : 방송 통계 방송 정보 응답 DTO
     */
    private BroadcastDayBroadcastInfoResDto buildBroadcastDayBroadcastInfo(Broadcast broadcast) {
        log.info("[BroadcastService] buildBroadcastDayBroadcastInfo() - START | broadcastId: {}", broadcast.getId());

        /*
            1. 최근 5개 방송 대화 조회
            - cursorId 기준 내림차순으로 최신 대화 최대 5개를 조회한다.
         */
        List<BroadcastDialogue> lastFiveDialogues = broadcastDialogueRepository.findByBroadcastIdOrderByCursorIdDesc(
                broadcast.getId(), PageRequest.of(0, 5));

        /*
            2. BroadcastDialogueCursorResDto 변환
            - BroadcastDialogueCursorProjection.fromEntity() 후 기존 변환 함수를 재사용한다.
         */
        List<BroadcastDialogueCursorResDto> lastFiveDialogueDtos = lastFiveDialogues.stream()
                .map(dialogue -> toBroadcastDialogueCursorItemResDto(BroadcastDialogueCursorProjection.fromEntity(dialogue)))
                .toList();

        /*
            3. 방송 분석 결과 조회 및 DTO 변환
            - 분석 데이터가 아직 없으면 analysisResult는 null로 반환한다.
         */
        BroadcastAnalysisResDto analysisResult = broadcastAnalysisRepository.findByBroadcast_Id(broadcast.getId())
                .map(broadcastAnalysisMapper::toBroadcastAnalysisResDto)
                .orElse(null);

        /*
            4. 방송 정보 DTO 생성
            - 방송 기본 정보, 최근 대화, 분석 결과를 포함한다.
         */
        BroadcastDayBroadcastInfoResDto result = BroadcastDayBroadcastInfoResDto.create(
                        broadcast.getStreamId(),
                        broadcast.getStatus(),
                        broadcast.getStartedAt().format(BROADCAST_DATE_TIME_FORMATTER),
                        broadcast.getTerminatedAt() != null
                                ? broadcast.getTerminatedAt().format(BROADCAST_DATE_TIME_FORMATTER)
                                : null,
                        lastFiveDialogueDtos,
                        analysisResult
                );

        log.info("[BroadcastService] buildBroadcastDayBroadcastInfo() - END | broadcastId: {}, dialogueSize: {}, hasAnalysis: {}",
                broadcast.getId(), lastFiveDialogueDtos.size(), analysisResult != null);
        return result;
    }

    /**
     * 방송 통계 조회용 채팅 정보 구성
     * - 해당 방송 전체 채팅 통계를 기반으로 여론 현황, AI 파트너 성향, 감정 흐름, 상위 키워드를 구성한다.
     * @param broadcast : 방송 엔티티
     * @return : 방송 통계 채팅 정보 응답 DTO
     */
    private BroadcastDayChatInfoResDto buildBroadcastDayChatInfo(Broadcast broadcast) {
        log.info("[BroadcastService] buildBroadcastDayChatInfo() - START | broadcastId: {}", broadcast.getId());

        /*
            1. 방송 전체 통계 조회 범위 계산
            - 종료된 방송은 종료 시각까지, 진행 중인 방송은 현재 시각까지 조회한다.
         */
        LocalDateTime statsSince = broadcast.getStartedAt();
        LocalDateTime statsUntil = broadcast.getTerminatedAt() != null ? broadcast.getTerminatedAt() : LocalDateTime.now();

        /*
            2. 방송 전체 채팅 통계 조회
            - 하루 방송 통계의 채팅 정보는 특정 방송에 연결된 전체 BroadcastStats를 대상으로 한다.
         */
        List<BroadcastStats> broadcastStatsList = broadcastStatsRepository.findByBroadcastAndRecordedAtBetween(
                broadcast,
                statsSince,
                statsUntil
        );

        /*
            3. 여론 현황 및 AI 파트너 성향 계산
            - AI 파트너 성향은 최종 채팅 여론 분석 결과와 동일하게 판별한다.
         */
        BroadcastChatStatsResDto.PublicOpinionResDto publicOpinion = calculatePublicOpinion(broadcastStatsList);
        AiCharacterTendency aiPartnerTendency = determineTendencyByPublicOpinion(publicOpinion);

        /*
            4. 10분 고정 구간별 감정 흐름 생성
            - 하루 방송 통계에서는 요청 파라미터 없이 10분 간격으로 고정한다.
         */
        List<BroadcastChatStatsResDto.SentimentFlowItemResDto> sentimentFlow = buildSentimentFlow(
                broadcastStatsList,
                statsSince,
                statsUntil,
                10,
                true
        );

        /*
            5. 전체 방송 데이터 기준 상위 10개 키워드 조회
         */
        List<String> topKeywords = broadcastKeywordsRepository.findTop10KeywordsByBroadcast(broadcast);

        /*
            6. 채팅 정보 DTO 생성
            - BroadcastDayChatInfoResDto에 채팅 통계 필드를 직접 반환한다.
         */
        BroadcastDayChatInfoResDto result = BroadcastDayChatInfoResDto.builder()
                .publicOpinion(publicOpinion)
                .aiPartnerTendency(aiPartnerTendency)
                .sentimentFlow(sentimentFlow)
                .topKeywords(topKeywords)
                .build();

        log.info("[BroadcastService] buildBroadcastDayChatInfo() - END | broadcastId: {}, total: {}, tendency: {}, flowSize: {}, topKeywordsSize: {}",
                broadcast.getId(), publicOpinion.totalChatCount(), aiPartnerTendency, sentimentFlow.size(), topKeywords.size());
        return result;
    }

}
