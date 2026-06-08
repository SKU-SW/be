package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.character.entity.CharacterImage;
import com.example.sku_sw.domain.character.entity.CharacterPersona;
import com.example.sku_sw.domain.character.entity.CharacterVrm;
import com.example.sku_sw.domain.character.enums.CharacterAppearanceType;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BroadcastRepositoryTest {

    @Autowired
    private BroadcastRepository broadcastRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("findActiveByUserId 성공 - 2D 캐릭터(characterImage 있음, characterVrm 없음)도 조회된다")
    void findActiveByUserId_성공_TWO_D() {
        // given
        User user = userRepository.save(User.builder()
                .name("테스트유저")
                .email("test2d@test.com")
                .hashedPassword("password")
                .build());

        CharacterImage characterImage = CharacterImage.builder()
                .gender(Gender.FEMALE)
                .preset("2d-preset")
                .build();
        em.persist(characterImage);

        Character character = Character.builder()
                .name("2D 캐릭터")
                .gender(Gender.FEMALE)
                .characterAppearanceType(CharacterAppearanceType.TWO_D)
                .user(user)
                .characterImage(characterImage)
                .characterVrm(null)
                .build();
        em.persist(character);

        CharacterPersona persona = CharacterPersona.builder()
                .character(character)
                .presetType(PresetType.FRIENDLY_CHATTER)
                .build();
        em.persist(persona);

        Broadcast broadcast = Broadcast.startBroadcast("stream-2d-test", character);
        broadcastRepository.save(broadcast);
        em.flush();
        em.clear();

        // when
        Optional<Broadcast> result = broadcastRepository.findActiveByUserId(user.getId(), BroadcastStatus.BROADCASTING);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getStreamId()).isEqualTo("stream-2d-test");
        assertThat(result.get().getCharacter().getCharacterImage()).isNotNull();
        assertThat(result.get().getCharacter().getCharacterVrm()).isNull();
    }

    @Test
    @DisplayName("findActiveByUserId 성공 - 3D 캐릭터(characterVrm 있음, characterImage 없음)도 조회된다")
    void findActiveByUserId_성공_THREE_D() {
        // given
        User user = userRepository.save(User.builder()
                .name("테스트유저3d")
                .email("test3d@test.com")
                .hashedPassword("password")
                .build());

        CharacterVrm characterVrm = CharacterVrm.builder()
                .presetId("vrm-preset-3d")
                .gender(Gender.MALE)
                .name("3D VRM")
                .thumbnailUrl("thumb.png")
                .vrmUrl("model.vrm")
                .build();
        em.persist(characterVrm);

        Character character = Character.builder()
                .name("3D 캐릭터")
                .gender(Gender.MALE)
                .characterAppearanceType(CharacterAppearanceType.THREE_D)
                .user(user)
                .characterImage(null)
                .characterVrm(characterVrm)
                .build();
        em.persist(character);

        CharacterPersona persona = CharacterPersona.builder()
                .character(character)
                .presetType(PresetType.HIGH_TENSION)
                .build();
        em.persist(persona);

        Broadcast broadcast = Broadcast.startBroadcast("stream-3d-test", character);
        broadcastRepository.save(broadcast);
        em.flush();
        em.clear();

        // when
        Optional<Broadcast> result = broadcastRepository.findActiveByUserId(user.getId(), BroadcastStatus.BROADCASTING);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getStreamId()).isEqualTo("stream-3d-test");
        assertThat(result.get().getCharacter().getCharacterVrm()).isNotNull();
        assertThat(result.get().getCharacter().getCharacterImage()).isNull();
    }
}
