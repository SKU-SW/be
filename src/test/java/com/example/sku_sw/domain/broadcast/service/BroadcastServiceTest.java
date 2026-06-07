package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.auth.service.AuthService;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterImageRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
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
import com.example.sku_sw.domain.character.repository.CharacterRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private BroadcastDialogueCompactionService broadcastDialogueCompactionService;

    @Mock
    private FastApiUtil fastApiUtil;

    @Mock
    private BroadcastStatsRepository broadcastStatsRepository;

    @Mock
    private ChatRedisUtil chatRedisUtil;

    @Mock
    private BroadcastKeywordsRepository broadcastKeywordsRepository;

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
}
