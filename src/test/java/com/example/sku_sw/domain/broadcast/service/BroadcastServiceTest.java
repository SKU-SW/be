package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.auth.service.AuthService;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterImageRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDayStatsResDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastStats;
import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.mapper.BroadcastAnalysisMapper;
import com.example.sku_sw.domain.broadcast.repository.BroadcastAnalysisRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastKeywordsRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastStatsRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.util.BroadcastStreamIdGenerator;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.entity.CharacterImage;
import com.example.sku_sw.domain.character.entity.CharacterImageDetail;
import com.example.sku_sw.domain.character.entity.CharacterPersona;
import com.example.sku_sw.domain.character.entity.CharacterTriggerWord;
import com.example.sku_sw.domain.character.entity.CharacterVrm;
import com.example.sku_sw.domain.character.enums.CharacterAppearanceType;
import com.example.sku_sw.domain.character.enums.CharacterErrorCode;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.character.repository.CharacterImageDetailRepository;
import com.example.sku_sw.domain.character.repository.CharacterRepository;
import com.example.sku_sw.domain.character.repository.CharacterTriggerWordRepository;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BroadcastServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private BroadcastRepository broadcastRepository;

    @Mock
    private BroadcastDialogueRepository broadcastDialogueRepository;

    @Mock
    private AuthService authService;

    @Mock
    private BroadcastStreamIdGenerator streamIdGenerator;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;

    @Mock
    private BroadcastDialoguePersistenceService broadcastDialoguePersistenceService;

    @Mock
    private BroadcastAnalysisService broadcastAnalysisService;

    @Mock
    private FastApiUtil fastApiUtil;

    @Mock
    private BroadcastStatsRepository broadcastStatsRepository;

    @Mock
    private ChatRedisUtil chatRedisUtil;

    @Mock
    private BroadcastKeywordsRepository broadcastKeywordsRepository;

    @Mock
    private BroadcastAnalysisRepository broadcastAnalysisRepository;

    @Mock
    private BroadcastAnalysisMapper broadcastAnalysisMapper;

    @Mock
    private CharacterTriggerWordRepository characterTriggerWordRepository;

    @Mock
    private CharacterImageDetailRepository characterImageDetailRepository;

    @InjectMocks
    private BroadcastService broadcastService;

    @Test
    @DisplayName("buildBroadcastCharacterRedisDto 성공 - 2D 캐릭터는 CharacterImageDetail 기반으로 Redis 이미지를 생성한다")
    void buildBroadcastCharacterRedisDto_성공_TWO_D() {
        // given
        Character character = Character.builder()
                .id(1L)
                .name("2D 캐릭터")
                .gender(Gender.FEMALE)
                .characterAppearanceType(CharacterAppearanceType.TWO_D)
                .characterImage(CharacterImage.builder()
                        .id(10L)
                        .gender(Gender.FEMALE)
                        .preset("image-preset")
                        .imageDetails(List.of(
                                CharacterImageDetail.builder()
                                        .id(2L)
                                        .emotion(Emotion.HAPPY)
                                        .imageUrl("happy.png")
                                        .build(),
                                CharacterImageDetail.builder()
                                        .id(1L)
                                        .emotion(Emotion.DEFAULT)
                                        .imageUrl("default.png")
                                        .build()
                        ))
                        .build())
                .characterPersona(CharacterPersona.builder()
                        .id(100L)
                        .presetType(PresetType.FRIENDLY_CHATTER)
                        .build())
                .triggerWords(List.of(
                        CharacterTriggerWord.builder().id(2L).word("둘째").sortOrder(2).build(),
                        CharacterTriggerWord.builder().id(1L).word("첫째").sortOrder(1).build()
                ))
                .build();

        // when
        BroadcastCharacterRedisDto result = ReflectionTestUtils.invokeMethod(
                broadcastService,
                "buildBroadcastCharacterRedisDto",
                character
        );

        // then
        assertThat(result.getCharacterImagePreset()).isEqualTo("image-preset");
        assertThat(result.getCharacterTriggerWords()).containsExactly("첫째", "둘째");
        assertThat(result.getCharacterImages())
                .extracting(BroadcastCharacterImageRedisDto::emotion)
                .containsExactly(Emotion.DEFAULT, Emotion.HAPPY);
        assertThat(result.getCharacterImages())
                .extracting(BroadcastCharacterImageRedisDto::imageUrl)
                .containsExactly("default.png", "happy.png");
    }

    @Test
    @DisplayName("buildBroadcastCharacterRedisDto 성공 - 3D 캐릭터는 Emotion 전체를 imageUrl null로 Redis 이미지에 저장한다")
    void buildBroadcastCharacterRedisDto_성공_THREE_D() {
        // given
        Character character = Character.builder()
                .id(2L)
                .name("3D 캐릭터")
                .gender(Gender.MALE)
                .characterAppearanceType(CharacterAppearanceType.THREE_D)
                .characterVrm(CharacterVrm.builder()
                        .id(20L)
                        .presetId("vrm-preset")
                        .gender(Gender.MALE)
                        .name("VRM")
                        .thumbnailUrl("thumb.png")
                        .vrmUrl("model.vrm")
                        .build())
                .characterPersona(CharacterPersona.builder()
                        .id(101L)
                        .presetType(PresetType.HIGH_TENSION)
                        .build())
                .triggerWords(List.of(
                        CharacterTriggerWord.builder().id(3L).word("호출어").sortOrder(1).build()
                ))
                .build();

        // when
        BroadcastCharacterRedisDto result = ReflectionTestUtils.invokeMethod(
                broadcastService,
                "buildBroadcastCharacterRedisDto",
                character
        );

        // then
        assertThat(result.getCharacterImagePreset()).isEqualTo("vrm-preset");
        assertThat(result.getCharacterImages()).hasSize(Emotion.values().length);
        assertThat(result.getCharacterImages())
                .extracting(BroadcastCharacterImageRedisDto::emotion)
                .containsExactly(Emotion.values());
        assertThat(result.getCharacterImages())
                .extracting(BroadcastCharacterImageRedisDto::imageUrl)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("buildBroadcastCharacterRedisDto 실패 - 3D 캐릭터에 CharacterVrm이 없으면 예외가 발생한다")
    void buildBroadcastCharacterRedisDto_실패_THREE_D_CharacterVrm_없음() {
        // given
        Character character = Character.builder()
                .id(3L)
                .name("잘못된 3D 캐릭터")
                .gender(Gender.MALE)
                .characterAppearanceType(CharacterAppearanceType.THREE_D)
                .characterPersona(CharacterPersona.builder()
                        .id(102L)
                        .presetType(PresetType.PLAYFUL_TEASER)
                        .build())
                .triggerWords(List.of())
                .build();

        // when & then
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                broadcastService,
                "buildBroadcastCharacterRedisDto",
                character
        ))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CharacterErrorCode.CHARACTER_VRM_NOT_FOUND));
    }

    @Test
    @DisplayName("extractCharacterImageUrl 성공 - 3D 캐릭터는 VRM 썸네일 URL을 반환한다")
    void extractCharacterImageUrl_성공_THREE_D() {
        // given
        Character character = Character.builder()
                .id(4L)
                .name("3D 썸네일 캐릭터")
                .gender(Gender.MALE)
                .characterAppearanceType(CharacterAppearanceType.THREE_D)
                .characterVrm(CharacterVrm.builder()
                        .id(21L)
                        .presetId("vrm-preset-2")
                        .gender(Gender.MALE)
                        .name("VRM 2")
                        .thumbnailUrl("thumbnail-3d.png")
                        .vrmUrl("avatar-3d.vrm")
                        .build())
                .build();

        // when
        String result = ReflectionTestUtils.invokeMethod(
                broadcastService,
                "extractCharacterImageUrl",
                character
        );

        // then
        assertThat(result).isEqualTo("thumbnail-3d.png");
    }

    @Test
    @DisplayName("하루 방송 통계 조회 성공 - 채팅 분석 정보를 전체 방송 통계 기준으로 반환한다")
    void 하루_방송_통계_조회_성공_채팅_분석_정보_반환() {
        // given
        Long userId = 1L;
        Long broadcastId = 10L;
        LocalDateTime startedAt = LocalDateTime.of(2026, 6, 9, 14, 0);
        LocalDateTime terminatedAt = LocalDateTime.of(2026, 6, 9, 14, 25);

        CharacterVrm characterVrm = CharacterVrm.builder()
                .id(20L)
                .presetId("vrm-preset")
                .gender(Gender.FEMALE)
                .name("VRM")
                .thumbnailUrl("thumbnail.png")
                .vrmUrl("model.vrm")
                .build();
        Character character = Character.builder()
                .id(5L)
                .name("테스트 캐릭터")
                .gender(Gender.FEMALE)
                .characterAppearanceType(CharacterAppearanceType.THREE_D)
                .characterVrm(characterVrm)
                .characterPersona(CharacterPersona.builder()
                        .id(30L)
                        .presetType(PresetType.FRIENDLY_CHATTER)
                        .build())
                .build();
        Broadcast broadcast = Broadcast.builder()
                .id(broadcastId)
                .streamId("stream1234567890")
                .status(BroadcastStatus.TERMINATED)
                .startedAt(startedAt)
                .terminatedAt(terminatedAt)
                .character(character)
                .build();

        BroadcastStats firstStats = BroadcastStats.builder()
                .id(1L)
                .avgViewerNum(10)
                .totalChatNum(100)
                .positiveChatCount(60)
                .neutralChatCount(30)
                .negativeChatCount(10)
                .recordedAt(startedAt.plusMinutes(1))
                .broadcast(broadcast)
                .build();
        BroadcastStats secondStats = BroadcastStats.builder()
                .id(2L)
                .avgViewerNum(12)
                .totalChatNum(100)
                .positiveChatCount(40)
                .neutralChatCount(40)
                .negativeChatCount(20)
                .recordedAt(startedAt.plusMinutes(11))
                .broadcast(broadcast)
                .build();
        BroadcastStats thirdStats = BroadcastStats.builder()
                .id(3L)
                .avgViewerNum(15)
                .totalChatNum(50)
                .positiveChatCount(50)
                .neutralChatCount(10)
                .negativeChatCount(0)
                .recordedAt(startedAt.plusMinutes(21))
                .broadcast(broadcast)
                .build();

        given(broadcastRepository.findByIdAndUserId(broadcastId, userId)).willReturn(Optional.of(broadcast));
        given(characterTriggerWordRepository.findAllByCharacterIdOrderBySortOrderAsc(character.getId())).willReturn(List.of());
        given(broadcastDialogueRepository.findByBroadcastIdOrderByCursorIdDesc(broadcastId, org.springframework.data.domain.PageRequest.of(0, 5))).willReturn(List.of());
        given(broadcastAnalysisRepository.findByBroadcast_Id(broadcastId)).willReturn(Optional.empty());
        given(broadcastStatsRepository.findByBroadcastAndRecordedAtBetween(broadcast, startedAt, terminatedAt))
                .willReturn(List.of(firstStats, secondStats, thirdStats));
        given(broadcastKeywordsRepository.findTop10KeywordsByBroadcast(broadcast))
                .willReturn(List.of("롤", "ㅋㅋㅋ", "대박"));
        ReflectionTestUtils.setField(broadcastService, "cloudfrontDomain", "https://cdn.test");

        // when
        BroadcastDayStatsResDto result = broadcastService.getBroadcastDayStats(userId, broadcastId);

        // then
        assertThat(result.chatAnalysisInfo().publicOpinion().positiveChatCount()).isEqualTo(150);
        assertThat(result.chatAnalysisInfo().publicOpinion().neutralChatCount()).isEqualTo(80);
        assertThat(result.chatAnalysisInfo().publicOpinion().negativeChatCount()).isEqualTo(30);
        assertThat(result.chatAnalysisInfo().publicOpinion().totalChatCount()).isEqualTo(260);
        assertThat(result.chatAnalysisInfo().publicOpinion().positiveRatio()).isEqualTo(57.7);
        assertThat(result.chatAnalysisInfo().aiPartnerTendency()).isEqualTo(AiCharacterTendency.POSITIVE);
        assertThat(result.chatAnalysisInfo().sentimentFlow()).hasSize(3);
        assertThat(result.chatAnalysisInfo().sentimentFlow())
                .extracting(item -> item.timeLabel())
                .containsExactly("14:00", "14:10", "14:20");
        assertThat(result.chatAnalysisInfo().topKeywords()).containsExactly("롤", "ㅋㅋㅋ", "대박");
    }
}
