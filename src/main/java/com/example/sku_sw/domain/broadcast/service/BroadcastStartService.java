package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.auth.dto.AuthChzzkAuthUrlResDto;
import com.example.sku_sw.domain.auth.enums.AuthErrorCode;
import com.example.sku_sw.domain.auth.service.AuthService;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterImageRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastUserRedisDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.exception.ChzzkReauthRequiredException;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.service.fastapi.FastApiChzzkSessionService;
import com.example.sku_sw.domain.broadcast.util.BroadcastStreamIdGenerator;
import com.example.sku_sw.domain.character.entity.*;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.enums.CharacterAppearanceType;
import com.example.sku_sw.domain.character.enums.CharacterErrorCode;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.example.sku_sw.domain.character.repository.CharacterRepository;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateResDto;
import com.example.sku_sw.domain.setting.entity.BroadcastSetting;
import com.example.sku_sw.domain.setting.repository.BroadcastSettingRepository;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastStartService {

    private final AuthService authService;
    private final BroadcastStartAfterService broadcastStartAfterService;

    private final BroadcastRepository broadcastRepository;
    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final BroadcastStreamIdGenerator streamIdGenerator;
    private final BroadcastSettingRepository broadcastSettingRepository;

    private final FastApiChzzkSessionService fastApiChzzkSessionService;

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
        log.info("[BroadcastStartService] 방송 시작됨 | startBroadcast() - START | userId: {}, characterId: {}", userId, characterId);
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
        validateSelectedCharacter(user, characterId);

        /*
            4. 캐릭터 조회 및 소유권 검증
            - characterId와 userId로 캐릭터를 조회하고, 존재하지 않으면 CHARACTER_NOT_FOUND 예외를 발생시킨다.
         */
        Character character = findOwnedBroadcastCharacter(userId, characterId);

        /*
            5. 해당 캐릭터 방송 중 여부 확인
            - 이미 BROADCASTING 상태인 방송이 있으면 CHARACTER_ALREADY_BROADCASTING 예외를 발생시킨다.
         */
        validateCharacterNotBroadcasting(characterId);

        /*
            6. Broadcast 엔티티 생성 및 저장
            - Broadcast 객체를 생성하고 저장한다.
         */
        Broadcast savedBroadcast = createAndSaveBroadcast(character);

        /*
            7. FastAPI에 치지직 세션 연결 요청
            - DB 저장 후 FastAPI에 세션 연결을 동기 요청한다.
            - 실패 시 예외를 발생시켜 트랜잭션을 롤백한다.
         */
        FastApiChzzkSessionCreateResDto fastApiResponse = fastApiChzzkSessionService.connectChzzkSession(
                savedBroadcast.getStreamId(),
                user.getChzzkAuthAccessToken()
        );

        /*
            8. Redis 저장용 DTO 생성 및 커밋 후 저장 예약
            - 방송 시작 DB 커밋이 확정된 이후 Redis에 방송 캐릭터/사용자 정보를 저장한다.
         */
        registerBroadcastRedisSaveAfterCommit(
                savedBroadcast.getStreamId(),
                userId,
                character,
                fastApiResponse
        );

        /*
            9. ResponseDto 생성
            - 저장된 Broadcast의 streamId와 startedAt을 포맷하여 응답 DTO를 생성한다.
         */
        BroadcastStartResDto result = BroadcastStartResDto.builder()
                .broadcastStreamId(savedBroadcast.getStreamId())
                .broadcastStartedAt(savedBroadcast.getStartedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")))
                .build();

        log.info("[BroadcastStartService] 방송 시작됨 | startBroadcast() - END | streamId: {}", savedBroadcast.getStreamId());
        return result;
    }


    /**
     * 방송 시작 요청 캐릭터가 사용자가 선택한 캐릭터인지 검증한다.
     * @param user : 비관적 쓰기 잠금을 획득한 사용자 엔티티
     * @param characterId : 방송을 시작할 캐릭터 ID
     */
    private void validateSelectedCharacter(User user, Long characterId) {
        log.debug("[BroadcastStartService] 선택 캐릭터 검증됨 | validateSelectedCharacter() - START | userId: {}, characterId: {}",
                user.getId(), characterId);

        if (!user.isSelectedCharacter(characterId)) {
            throw new CustomException(BroadcastErrorCode.BROADCAST_CHARACTER_NOT_SELECTED);
        }

        log.debug("[BroadcastStartService] 선택 캐릭터 검증됨 | validateSelectedCharacter() - END | userId: {}, characterId: {}",
                user.getId(), characterId);
    }

    /**
     * 방송 시작에 필요한 캐릭터를 조회하고 사용자 소유 여부를 검증한다.
     * @param userId : 캐릭터 소유 사용자 ID
     * @param characterId : 조회할 캐릭터 ID
     * @return : 방송 Redis 초기화에 필요한 연관관계가 조회된 캐릭터 엔티티
     */
    private Character findOwnedBroadcastCharacter(Long userId, Long characterId) {
        log.debug("[BroadcastStartService] 방송 캐릭터 조회됨 | findOwnedBroadcastCharacter() - START | userId: {}, characterId: {}",
                userId, characterId);

        Character character = characterRepository.findBroadcastRedisCharacterByIdAndUserId(characterId, userId)
                .orElseThrow(() -> new CustomException(CharacterErrorCode.CHARACTER_NOT_FOUND));

        log.debug("[BroadcastStartService] 방송 캐릭터 조회됨 | findOwnedBroadcastCharacter() - END | userId: {}, characterId: {}",
                userId, characterId);
        return character;
    }

    /**
     * 해당 캐릭터의 진행 중인 방송 존재 여부를 검증한다.
     * @param characterId : 방송 상태를 확인할 캐릭터 ID
     */
    private void validateCharacterNotBroadcasting(Long characterId) {
        log.debug("[BroadcastStartService] 방송 중복 여부 검증됨 | validateCharacterNotBroadcasting() - START | characterId: {}", characterId);

        if (broadcastRepository.existsByCharacterIdAndStatus(characterId, BroadcastStatus.BROADCASTING)) {
            throw new CustomException(BroadcastErrorCode.CHARACTER_ALREADY_BROADCASTING);
        }

        log.debug("[BroadcastStartService] 방송 중복 여부 검증됨 | validateCharacterNotBroadcasting() - END | characterId: {}", characterId);
    }

    /**
     * 고유 streamId로 방송 엔티티를 생성하고 저장한다.
     * @param character : 방송을 진행할 캐릭터 엔티티
     * @return : 저장된 방송 엔티티
     */
    private Broadcast createAndSaveBroadcast(Character character) {
        log.debug("[BroadcastStartService] 방송 엔티티 저장됨 | createAndSaveBroadcast() - START | characterId: {}", character.getId());

        String streamId = streamIdGenerator.generate();
        Broadcast broadcast = Broadcast.startBroadcast(streamId, character);
        Broadcast savedBroadcast = broadcastRepository.save(broadcast);

        log.debug("[BroadcastStartService] 방송 엔티티 저장됨 | createAndSaveBroadcast() - END | streamId: {}", savedBroadcast.getStreamId());
        return savedBroadcast;
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
        log.debug("[BroadcastStartService] Chzzk 인증 준비 확인됨 | ensureChzzkAuthReadyForBroadcast() - START | userId: {}", userId);
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
            log.debug("[BroadcastStartService] Chzzk 인증 토큰 상태 확인됨 | ensureChzzkAuthReadyForBroadcast() - accessExpired: {}, refreshExpired: {}, accessExpiresAt: {}, refreshExpiresAt: {}",
                    user.isChzzkAuthAccessTokenExpired(),
                    user.isChzzkAuthRefreshTokenExpired(),
                    user.getChzzkAuthAccessTokenExpiresAt(),
                    user.getChzzkAuthRefreshTokenExpiresAt());
            log.debug("[BroadcastStartService] Chzzk 인증 준비 완료 | ensureChzzkAuthReadyForBroadcast() - END | userId: {}",
                    userId);
            return;
        }

        try {
            /*
                3. 치지직 Access Token 재발급 시도
                - 저장된 Refresh Token으로 치지직 Access / Refresh Token 재발급을 시도한다.
             */
            log.debug("[BroadcastStartService] Chzzk Access Token 갱신 시도됨 | ensureChzzkAuthReadyForBroadcast() - TRY_REFRESH");
            authService.refreshChzzkAccessToken(user);
            log.debug("[BroadcastStartService] Chzzk 인증 준비 완료 | ensureChzzkAuthReadyForBroadcast() - END | userId: {}", userId);
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
     * 캐릭터 엔티티를 방송 Redis 저장 DTO로 변환하는 함수
     * @param character : 방송 캐릭터 엔티티
     * @return : 방송 캐릭터 Redis DTO
     */
    private BroadcastCharacterRedisDto buildBroadcastCharacterRedisDto(Character character) {
        log.debug("[BroadcastStartService] 방송 캐릭터 Redis DTO 생성됨 | buildBroadcastCharacterRedisDto() - START | characterId: {}", character.getId());

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

        log.debug("[BroadcastStartService] 방송 캐릭터 Redis DTO 생성됨 | buildBroadcastCharacterRedisDto() - END | characterId: {}", character.getId());
        return result;
    }

    /**
     * FastAPI Chzzk 세션 응답과 방송 설정을 방송 사용자 Redis DTO로 변환한다.
     * @param fastApiResponse : FastAPI Chzzk 세션 생성 응답
     * @param aiProactiveToChat : AI 선제적 채팅 사용 여부
     * @return : 방송 사용자 Redis DTO
     */
    private BroadcastUserRedisDto buildBroadcastUserRedisDto(
            FastApiChzzkSessionCreateResDto fastApiResponse,
            boolean aiProactiveToChat
    ){
        log.debug("[BroadcastStartService] 방송 사용자 Redis DTO 생성됨 | buildBroadcastUserRedisDto() - START | channelId: {}, aiProactiveToChat: {}",
                fastApiResponse.channelId(), aiProactiveToChat);

        BroadcastUserRedisDto result = BroadcastUserRedisDto.builder()
                .sessionKey(fastApiResponse.sessionKey())
                .channelId(fastApiResponse.channelId())
                .channelName(null)
                .aiProactiveToChat(aiProactiveToChat)
                .isStreamerSilent(false)
                .build();

        log.debug("[BroadcastStartService] 방송 사용자 Redis DTO 생성 완료 | buildBroadcastUserRedisDto() - END | channelId: {}",
                result.getChannelId());
        return result;
    }

    /**
     * 트랜잭션 커밋 후 방송 캐릭터 정보를 Redis에 저장하도록 예약
     * - DB 변경이 확정된 후에 Redis 저장을 수행해 데이터 불일치를 줄인다.
     * - Redis 저장 성공 시 WebSocket 연결 타임아웃 작업을 등록한다.
     * - Redis 저장 실패 시 타임아웃을 등록하지 않는다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param userId : 방송 사용자 ID
     * @param character : 방송 캐릭터 엔티티
     * @param fastApiResponse : FastAPI 치지직 세션 생성 응답
     */
    private void registerBroadcastRedisSaveAfterCommit(
            String broadcastStreamId,
            Long userId,
            Character character,
            FastApiChzzkSessionCreateResDto fastApiResponse
    ) {
        log.debug("[BroadcastStartService] 커밋 후 Redis 저장 예약됨 | registerBroadcastRedisSaveAfterCommit() - START | streamId: {}",
                broadcastStreamId);

        /*
            1. Redis 저장용 DTO 생성
            - 트랜잭션 동기화를 등록하기 전에 캐릭터와 사용자 Redis 정보를 생성한다.
         */
        BroadcastCharacterRedisDto redisDto = buildBroadcastCharacterRedisDto(character);
        boolean aiProactiveToChat = broadcastSettingRepository.findByUserId(userId)
                .map(BroadcastSetting::isAiProactiveToChat)
                .orElse(true);
        BroadcastUserRedisDto broadcastUserRedisDto = buildBroadcastUserRedisDto(fastApiResponse, aiProactiveToChat);

        /*
            2. 트랜잭션 커밋 후 방송 시작 후속 처리 등록
            - DB 커밋이 확정된 뒤 별도 서비스에서 Redis와 FastAPI 후속 처리를 수행한다.
         */
        log.info(
                "[BroadcastStartService] registerBroadcastRedisSaveAfterCommit | threadName: {}, threadId: {}",
                Thread.currentThread().getName(),
                Thread.currentThread().threadId()
        );
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcastStartAfterService.processBroadcastStartAfterCommit(
                        broadcastStreamId,
                        redisDto,
                        broadcastUserRedisDto
                );
            }
        });

        log.debug("[BroadcastStartService] 커밋 후 Redis 저장 예약 완료 | registerBroadcastRedisSaveAfterCommit() - END | streamId: {}",
                broadcastStreamId);
    }

}
