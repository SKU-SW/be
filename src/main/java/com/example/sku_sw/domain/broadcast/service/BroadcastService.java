package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.auth.dto.AuthChzzkAuthUrlResDto;
import com.example.sku_sw.domain.auth.enums.AuthErrorCode;
import com.example.sku_sw.domain.auth.service.AuthService;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterInfoResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterImageRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueCursorItemResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastTerminateResDto;
import com.example.sku_sw.domain.broadcast.dto.CharacterPersonaInfoResDto;
import com.example.sku_sw.domain.broadcast.dto.CurrentStreamInfoResDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.exception.ChzzkReauthRequiredException;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.util.BroadcastStreamIdGenerator;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketHandler;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.entity.CharacterImageDetail;
import com.example.sku_sw.domain.character.entity.CharacterTriggerWord;
import com.example.sku_sw.domain.character.enums.CharacterErrorCode;
import com.example.sku_sw.domain.character.repository.CharacterRepository;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.response.CursorSliceResponse;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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
    private final BroadcastWebSocketHandler broadcastWebSocketHandler;
    private final BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;
    private final BroadcastDialogueCompactionService broadcastDialogueCompactionService;

    private static final DateTimeFormatter BROADCAST_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss");

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
    @Transactional(noRollbackFor = ChzzkReauthRequiredException.class)
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
            8. Redis 저장용 DTO 생성 및 커밋 후 저장 예약
            - 방송 시작 DB 커밋이 확정된 이후 Redis에 방송 캐릭터 정보를 저장한다.
         */
        BroadcastCharacterRedisDto redisDto = buildBroadcastCharacterRedisDto(character);
        registerBroadcastRedisSaveAfterCommit(savedBroadcast.getStreamId(), redisDto);

        /*
            9. ResponseDto 생성
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
        CursorSliceResponse<BroadcastDialogueCursorItemResDto> dialogueSlice = buildCurrentStreamDialogueSlice(
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
    public CursorSliceResponse<BroadcastDialogueCursorItemResDto> getBroadcastDialoguesByCursor(
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
        CursorSliceResponse<BroadcastDialogueCursorItemResDto> result = buildCurrentStreamDialogueSlice(
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

        List<BroadcastCharacterImageRedisDto> characterImages = character.getCharacterImage().getImageDetails()
                .stream()
                .sorted(Comparator.comparingLong(CharacterImageDetail::getId))
                .map(imageDetail -> BroadcastCharacterImageRedisDto.builder()
                        .emotion(imageDetail.getEmotion())
                        .imageUrl(imageDetail.getImageUrl())
                        .build())
                .toList();

        BroadcastCharacterRedisDto result = BroadcastCharacterRedisDto.builder()
                .characterId(character.getId())
                .characterName(character.getName())
                .characterGender(character.getGender())
                .characterTriggerWords(characterTriggerWords)
                .characterVoiceAgeGroup(character.getVoiceType().getAgeGroup())
                .characterVoiceTtsId(character.getVoiceType().getTtsId())
                .characterImagePreset(character.getCharacterImage().getPreset())
                .characterImages(characterImages)
                .characterSpeechStyle(character.getCharacterPersona().getSpeechStyle())
                .characterPersonality(character.getCharacterPersona().getPersonality())
                .isTalking(false)
                .build();

        log.info("[BroadcastService] buildBroadcastCharacterRedisDto() - END | characterId: {}", character.getId());
        return result;
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
                    .voiceTypeId(broadcast.getCharacter().getVoiceType().getId())
                    .characterImageUrl(extractCharacterImageUrl(broadcast.getCharacter()))
                    .characterPersona(CharacterPersonaInfoResDto.builder()
                            .presetType(broadcast.getCharacter().getCharacterPersona().getPresetType())
                            .speechStyle(broadcast.getCharacter().getCharacterPersona().getSpeechStyle())
                            .personality(broadcast.getCharacter().getCharacterPersona().getPersonality())
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
                .voiceTypeId(character.getVoiceType().getId())
                .characterImageUrl(extractCharacterImageUrl(character))
                .characterPersona(CharacterPersonaInfoResDto.builder()
                        .presetType(character.getCharacterPersona().getPresetType())
                        .speechStyle(character.getCharacterPersona().getSpeechStyle())
                        .personality(character.getCharacterPersona().getPersonality())
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
    private CursorSliceResponse<BroadcastDialogueCursorItemResDto> buildCurrentStreamDialogueSlice(
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

        CursorSliceResponse<BroadcastDialogueCursorItemResDto> result = CursorSliceResponse.<BroadcastDialogueCursorItemResDto>builder()
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
     * 현재 방송 대화 투영 객체를 응답 DTO로 변환
     * @param dialogue : 대화 투영 객체
     * @return : 방송 대화 커서 응답 DTO
     */
    private BroadcastDialogueCursorItemResDto toBroadcastDialogueCursorItemResDto(BroadcastDialogueCursorProjection dialogue) {
        return BroadcastDialogueCursorItemResDto.builder()
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
        return character.getCharacterImage().getImageDetails().stream()
                .sorted(Comparator.comparingLong(CharacterImageDetail::getId))
                .map(CharacterImageDetail::getImageUrl)
                .findFirst()
                .orElse("");
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
     */
    private void registerBroadcastRedisSaveAfterCommit(String broadcastStreamId, BroadcastCharacterRedisDto redisDto) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    broadcastRedisUtil.setBroadcastCharacterValue(broadcastStreamId, redisDto);
                    broadcastRedisUtil.initializeSummarySlot(broadcastStreamId);
                    // Redis 저장 성공 시 WebSocket 연결 타임아웃 등록
                    broadcastConnectionTimeoutService.registerConnectionTimeout(broadcastStreamId);
                } catch (Exception e) {
                    log.error("[BroadcastService] 방송 캐릭터 정보 Redis 저장 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
                }
            }
        });
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
                try {
                    broadcastDialogueCompactionService.compactRemainingDialogues(broadcastStreamId);
                    broadcastRedisUtil.deleteBroadcastCharacterValue(broadcastStreamId);
                    broadcastRedisUtil.deleteBroadcastInfo(broadcastStreamId);
                } catch (Exception e) {
                    log.error("[BroadcastService] 방송 캐릭터 정보 Redis 삭제 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
                }

                broadcastWebSocketHandler.disconnect(
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

}
