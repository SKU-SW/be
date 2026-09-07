package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.auth.dto.AuthChzzkAuthUrlResDto;
import com.example.sku_sw.domain.auth.enums.AuthErrorCode;
import com.example.sku_sw.domain.auth.service.AuthService;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastUserRedisDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.exception.ChzzkReauthRequiredException;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.entity.CharacterPersona;
import com.example.sku_sw.domain.character.entity.CharacterTriggerWord;
import com.example.sku_sw.domain.character.entity.CharacterVrm;
import com.example.sku_sw.domain.character.enums.CharacterAppearanceType;
import com.example.sku_sw.domain.character.enums.CharacterErrorCode;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.character.repository.CharacterPersonaRepository;
import com.example.sku_sw.domain.character.repository.CharacterRepository;
import com.example.sku_sw.domain.character.repository.CharacterTriggerWordRepository;
import com.example.sku_sw.domain.character.repository.CharacterVrmRepository;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelResDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateResDto;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import com.example.sku_sw.domain.setting.entity.BroadcastSetting;
import com.example.sku_sw.domain.setting.repository.BroadcastSettingRepository;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.IntegrationTestSupport;
import com.example.sku_sw.global.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

class BroadcastStartServiceTest extends IntegrationTestSupport {

    private static final String ACCESS_TOKEN = "access-token";
    private static final String REFRESH_TOKEN = "refresh-token";
    private static final String SESSION_KEY = "session-key";
    private static final String CHANNEL_ID = "channel-id";
    private static final String CHANNEL_NAME = "Chat:" + CHANNEL_ID + ".message";

    @Autowired
    private BroadcastStartService broadcastStartService;

    @Autowired
    private BroadcastRepository broadcastRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private CharacterPersonaRepository characterPersonaRepository;

    @Autowired
    private CharacterTriggerWordRepository characterTriggerWordRepository;

    @Autowired
    private CharacterVrmRepository characterVrmRepository;

    @Autowired
    private BroadcastSettingRepository broadcastSettingRepository;

    @Autowired
    private BroadcastRedisUtil broadcastRedisUtil;

    @Autowired
    private ChatRedisUtil chatRedisUtil;

    @Autowired
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Autowired
    @Qualifier("broadcastStringRedisTemplate")
    private StringRedisTemplate broadcastStringRedisTemplate;

    @Autowired
    @Qualifier("chatStringRedisTemplate")
    private StringRedisTemplate chatStringRedisTemplate;

    @MockitoBean
    private FastApiUtil fastApiUtil;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;

    private final List<String> startedStreamIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clearTestState();

        given(fastApiUtil.createChzzkSession(any(FastApiChzzkSessionCreateReqDto.class)))
                .willAnswer(invocation -> {
                    FastApiChzzkSessionCreateReqDto request = invocation.getArgument(0);
                    return Mono.just(FastApiChzzkSessionCreateResDto.builder()
                            .broadcastStreamId(request.broadcastStreamId())
                            .attemptId(request.attemptId())
                            .sessionKey(SESSION_KEY)
                            .channelId(CHANNEL_ID)
                            .build());
                });
        given(fastApiUtil.connectChzzkRedisChannel(any(FastApiChzzkRedisChannelReqDto.class)))
                .willAnswer(invocation -> {
                    FastApiChzzkRedisChannelReqDto request = invocation.getArgument(0);
                    return FastApiChzzkRedisChannelResDto.builder()
                            .broadcastStreamId(request.broadcastStreamId())
                            .sessionKey(request.sessionKey())
                            .channelName(request.channelName())
                            .status("연결 성공")
                            .build();
                });
    }

    @AfterEach
    void tearDown() {
        clearTestState();
    }

    @Test
    @DisplayName("방송 시작 성공 - DB와 Redis에 방송 시작 상태 저장")
    void 방송_시작_성공_DB와_Redis에_방송_시작_상태_저장() {
        // given
        User user = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);
        saveBroadcastSetting(user, false);

        // when
        BroadcastStartResDto result = broadcastStartService.startBroadcast(user.getId(), character.getId());
        startedStreamIds.add(result.broadcastStreamId());

        // then
        Broadcast broadcast = broadcastRepository.findByStreamId(result.broadcastStreamId()).orElseThrow();
        BroadcastCharacterRedisDto characterRedisDto =
                broadcastRedisUtil.getBroadcastCharacterDto(result.broadcastStreamId());
        BroadcastUserRedisDto userRedisDto = broadcastRedisUtil.getBroadcastUserDto(result.broadcastStreamId());
        BroadcastInfoRedisDto summary = broadcastRedisUtil.getSummary(result.broadcastStreamId());

        assertThat(broadcast.getStatus()).isEqualTo(BroadcastStatus.BROADCASTING);
        assertThat(broadcast.getCharacter().getId()).isEqualTo(character.getId());
        assertThat(broadcast.getStartedAt()).isNotNull();
        assertThat(result.broadcastStartedAt()).matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}:\\d{2}:\\d{2}");

        assertThat(characterRedisDto.getCharacterId()).isEqualTo(character.getId());
        assertThat(characterRedisDto.getCharacterTriggerWords())
                .containsExactly("첫 번째 호출어", "두 번째 호출어");
        assertThat(characterRedisDto.getCharacterImagePreset()).startsWith("vrm-preset-");
        assertThat(characterRedisDto.getCharacterImages()).hasSize(Emotion.values().length);

        assertThat(userRedisDto.getSessionKey()).isEqualTo(SESSION_KEY);
        assertThat(userRedisDto.getChannelId()).isEqualTo(CHANNEL_ID);
        assertThat(userRedisDto.getChannelName()).isEqualTo(CHANNEL_NAME);
        assertThat(userRedisDto.getAiProactiveToChat()).isFalse();
        assertThat(chatRedisUtil.hasChannelListener(CHANNEL_NAME)).isTrue();
        assertThat(summary.subject()).isEqualTo(DialogueSubject.SYSTEM_SUMMARY);
    }

    @Test
    @DisplayName("방송 시작 성공 - 방송 설정이 없으면 AI 선제 채팅 활성화")
    void 방송_시작_성공_방송_설정_없으면_AI_선제_채팅_활성화() {
        // given
        User user = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);

        // when
        BroadcastStartResDto result = broadcastStartService.startBroadcast(user.getId(), character.getId());
        startedStreamIds.add(result.broadcastStreamId());

        // then
        BroadcastUserRedisDto userRedisDto = broadcastRedisUtil.getBroadcastUserDto(result.broadcastStreamId());
        assertThat(userRedisDto.getAiProactiveToChat()).isTrue();
    }

    @Test
    @DisplayName("방송 시작 실패 - 존재하지 않는 사용자")
    void 방송_시작_실패_존재하지_않는_사용자() {
        // given
        Long missingUserId = Long.MAX_VALUE;

        // when
        CustomException exception = catchThrowableOfType(
                CustomException.class,
                () -> broadcastStartService.startBroadcast(missingUserId, 1L));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(CharacterErrorCode.USER_NOT_FOUND);
        assertNoNewBroadcastState();
    }

    @Test
    @DisplayName("방송 시작 실패 - 치지직 인증 토큰 없음")
    void 방송_시작_실패_치지직_인증_토큰_없음() {
        // given
        User user = saveUnauthorizedUser();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);

        // when
        CustomException exception = catchThrowableOfType(
                CustomException.class,
                () -> broadcastStartService.startBroadcast(user.getId(), character.getId()));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(BroadcastErrorCode.CHZZK_AUTH_REQUIRED);
        assertNoNewBroadcastState();
    }

    @Test
    @DisplayName("방송 시작 실패 - 사용자가 선택하지 않은 캐릭터")
    void 방송_시작_실패_사용자가_선택하지_않은_캐릭터() {
        // given
        User user = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacter(user);

        // when
        CustomException exception = catchThrowableOfType(
                CustomException.class,
                () -> broadcastStartService.startBroadcast(user.getId(), character.getId()));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(BroadcastErrorCode.BROADCAST_CHARACTER_NOT_SELECTED);
        assertNoNewBroadcastState();
    }

    @Test
    @DisplayName("방송 시작 실패 - 다른 사용자가 소유한 캐릭터")
    void 방송_시작_실패_다른_사용자가_소유한_캐릭터() {
        // given
        User owner = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacter(owner);
        User requester = saveAuthorizedUser();
        selectCharacter(requester, character);

        // when
        CustomException exception = catchThrowableOfType(
                CustomException.class,
                () -> broadcastStartService.startBroadcast(requester.getId(), character.getId()));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(CharacterErrorCode.CHARACTER_NOT_FOUND);
        assertNoNewBroadcastState();
    }

    @Test
    @DisplayName("방송 시작 실패 - 캐릭터가 이미 방송 중")
    void 방송_시작_실패_캐릭터가_이미_방송_중() {
        // given
        User user = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);
        broadcastRepository.save(Broadcast.startBroadcast("existing-stream", character));

        // when
        CustomException exception = catchThrowableOfType(
                CustomException.class,
                () -> broadcastStartService.startBroadcast(user.getId(), character.getId()));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(BroadcastErrorCode.CHARACTER_ALREADY_BROADCASTING);
        assertThat(broadcastRepository.count()).isEqualTo(1L);
        assertThat(broadcastRedisSize()).isZero();
    }

    @Test
    @DisplayName("방송 시작 실패 - FastAPI 치지직 세션 연결 실패 시 트랜잭션 롤백")
    void 방송_시작_실패_FastAPI_치지직_세션_연결_실패_시_트랜잭션_롤백() {
        // given
        User user = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);
        given(fastApiUtil.createChzzkSession(any(FastApiChzzkSessionCreateReqDto.class)))
                .willReturn(Mono.error(new CustomException(BroadcastErrorCode.CHZZK_SESSION_CONNECT_FAILED)));

        // when
        CustomException exception = catchThrowableOfType(
                CustomException.class,
                () -> broadcastStartService.startBroadcast(user.getId(), character.getId()));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(BroadcastErrorCode.CHZZK_SESSION_CONNECT_FAILED);
        assertNoNewBroadcastState();
    }

    @Test
    @DisplayName("방송 시작 실패 - 3D 캐릭터 VRM 정보 없음 시 트랜잭션 롤백")
    void 방송_시작_실패_3D_캐릭터_VRM_정보_없음_시_트랜잭션_롤백() {
        // given
        User user = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacterWithoutVrm(user);
        selectCharacter(user, character);

        // when
        CustomException exception = catchThrowableOfType(
                CustomException.class,
                () -> broadcastStartService.startBroadcast(user.getId(), character.getId()));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(CharacterErrorCode.CHARACTER_VRM_NOT_FOUND);
        assertNoNewBroadcastState();
    }

    @Test
    @DisplayName("방송 시작 성공 - 만료된 Access Token 갱신 후 방송 시작")
    void 방송_시작_성공_만료된_Access_Token_갱신_후_방송_시작() {
        // given
        User user = saveUserWithExpiredAccessToken();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);
        willAnswer(invocation -> {
            User target = invocation.getArgument(0);
            target.updateChzzkAuthTokens(
                    "renewed-access-token",
                    "renewed-refresh-token",
                    LocalDateTime.now().plusHours(1),
                    LocalDateTime.now().plusDays(1)
            );
            return null;
        }).given(authService).refreshChzzkAccessToken(any(User.class));

        // when
        BroadcastStartResDto result = broadcastStartService.startBroadcast(user.getId(), character.getId());
        startedStreamIds.add(result.broadcastStreamId());

        // then
        User savedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(savedUser.getChzzkAuthAccessToken()).isEqualTo("renewed-access-token");
        assertThat(broadcastRepository.findByStreamId(result.broadcastStreamId())).isPresent();
        assertThat(broadcastRedisUtil.hasBroadcastCharacterValue(result.broadcastStreamId())).isTrue();
    }

    @Test
    @DisplayName("방송 시작 실패 - Refresh Token 무효 시 재인증 요구")
    void 방송_시작_실패_Refresh_Token_무효_시_재인증_요구() {
        // given
        User user = saveUserWithExpiredAccessToken();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);
        AuthChzzkAuthUrlResDto authUrlResDto = AuthChzzkAuthUrlResDto.builder()
                .authUrl("https://chzzk.example/reauth")
                .build();
        willThrow(new CustomException(AuthErrorCode.CHZZK_AUTH_REFRESH_TOKEN_INVALID))
                .given(authService).refreshChzzkAccessToken(any(User.class));
        given(authService.createChzzkAuthUrl(user.getId())).willReturn(authUrlResDto);

        // when
        ChzzkReauthRequiredException exception = catchThrowableOfType(
                ChzzkReauthRequiredException.class,
                () -> broadcastStartService.startBroadcast(user.getId(), character.getId()));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(BroadcastErrorCode.CHZZK_AUTH_REAUTH_REQUIRED);
        assertThat(exception.getAuthUrlResDto()).isEqualTo(authUrlResDto);
        assertNoNewBroadcastState();
    }

    @Test
    @DisplayName("방송 시작 보상 성공 - AfterCommit 실패 시 방송 비정상 종료 및 Redis 정리")
    void 방송_시작_보상_성공_AfterCommit_실패_시_방송_비정상_종료_및_Redis_정리() {
        // given
        User user = saveAuthorizedUser();
        Character character = saveThreeDimensionalCharacter(user);
        selectCharacter(user, character);
        willThrow(new CustomException(BroadcastErrorCode.CHZZK_REDIS_CHANNEL_CONNECT_FAILED))
                .given(fastApiUtil).connectChzzkRedisChannel(any(FastApiChzzkRedisChannelReqDto.class));

        // when
        BroadcastStartResDto result = broadcastStartService.startBroadcast(user.getId(), character.getId());
        startedStreamIds.add(result.broadcastStreamId());

        // then
        Broadcast broadcast = broadcastRepository.findByStreamId(result.broadcastStreamId()).orElseThrow();
        assertThat(broadcast.getStatus()).isEqualTo(BroadcastStatus.ABNORMAL_TERMINATED);
        assertThat(broadcast.getTerminatedAt()).isNotNull();
        assertThat(broadcastRedisUtil.hasBroadcastCharacterValue(result.broadcastStreamId())).isFalse();
        assertThat(broadcastRedisUtil.hasBroadcastUserValue(result.broadcastStreamId())).isFalse();
        assertThat(broadcastStringRedisTemplate.hasKey("BroadcastInfo:" + result.broadcastStreamId())).isFalse();
        assertThat(chatRedisUtil.hasChannelListener(CHANNEL_NAME)).isFalse();
        assertThat(sessionRegistry.hasSessionBundle(result.broadcastStreamId())).isFalse();
    }

    private User saveAuthorizedUser() {
        return saveUserWithTokens(
                ACCESS_TOKEN,
                REFRESH_TOKEN,
                LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusDays(1)
        );
    }

    private User saveUnauthorizedUser() {
        String uniqueValue = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .name("테스트 사용자")
                .email(uniqueValue + "@example.com")
                .hashedPassword("hashed-password")
                .build());
    }

    private User saveUserWithExpiredAccessToken() {
        return saveUserWithTokens(
                ACCESS_TOKEN,
                REFRESH_TOKEN,
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusDays(1)
        );
    }

    private User saveUserWithTokens(
            String accessToken,
            String refreshToken,
            LocalDateTime accessTokenExpiresAt,
            LocalDateTime refreshTokenExpiresAt
    ) {
        String uniqueValue = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .name("테스트 사용자")
                .email(uniqueValue + "@example.com")
                .hashedPassword("hashed-password")
                .chzzkApiAuthorized(true)
                .chzzkAuthAccessToken(accessToken)
                .chzzkAuthRefreshToken(refreshToken)
                .chzzkAuthAccessTokenExpiresAt(accessTokenExpiresAt)
                .chzzkAuthRefreshTokenExpiresAt(refreshTokenExpiresAt)
                .build());
    }

    private Character saveThreeDimensionalCharacter(User user) {
        CharacterVrm characterVrm = characterVrmRepository.save(CharacterVrm.builder()
                .presetId("vrm-preset-" + UUID.randomUUID())
                .gender(Gender.FEMALE)
                .name("테스트 VRM")
                .thumbnailUrl("thumbnail.png")
                .vrmUrl("character.vrm")
                .build());
        return saveThreeDimensionalCharacter(user, characterVrm);
    }

    private Character saveThreeDimensionalCharacterWithoutVrm(User user) {
        return saveThreeDimensionalCharacter(user, null);
    }

    private Character saveThreeDimensionalCharacter(User user, CharacterVrm characterVrm) {
        Character character = characterRepository.save(Character.builder()
                .user(user)
                .name("테스트 캐릭터")
                .gender(Gender.FEMALE)
                .characterAppearanceType(CharacterAppearanceType.THREE_D)
                .characterVrm(characterVrm)
                .build());

        characterPersonaRepository.save(CharacterPersona.builder()
                .character(character)
                .presetType(PresetType.FRIENDLY_CHATTER)
                .build());
        characterTriggerWordRepository.saveAll(List.of(
                CharacterTriggerWord.builder()
                        .character(character)
                        .word("두 번째 호출어")
                        .sortOrder(2)
                        .build(),
                CharacterTriggerWord.builder()
                        .character(character)
                        .word("첫 번째 호출어")
                        .sortOrder(1)
                        .build()
        ));
        return character;
    }

    private void selectCharacter(User user, Character character) {
        user.updateSelectedCharacterId(character.getId());
        userRepository.save(user);
    }

    private void saveBroadcastSetting(User user, boolean aiProactiveToChat) {
        broadcastSettingRepository.save(BroadcastSetting.builder()
                .user(user)
                .aiProactiveToChat(aiProactiveToChat)
                .build());
    }

    private void assertNoNewBroadcastState() {
        assertThat(broadcastRepository.count()).isZero();
        assertThat(broadcastRedisSize()).isZero();
    }

    private long broadcastRedisSize() {
        Long size = broadcastStringRedisTemplate.execute(
                (RedisCallback<Long>) connection -> connection.serverCommands().dbSize());
        return size == null ? 0L : size;
    }

    private void clearTestState() {
        chatRedisUtil.unsubscribeChannelPattern(CHANNEL_ID);
        startedStreamIds.forEach(sessionRegistry::disconnect);
        startedStreamIds.clear();

        broadcastStringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        chatStringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });

        broadcastRepository.deleteAllInBatch();
        broadcastSettingRepository.deleteAllInBatch();
        characterTriggerWordRepository.deleteAllInBatch();
        characterPersonaRepository.deleteAllInBatch();
        characterRepository.deleteAllInBatch();
        characterVrmRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }
}
