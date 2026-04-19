package com.example.sku_sw.domain.character.entity;

import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "`character`")
public class Character extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voice_type_id", nullable = false)
    private VoiceType voiceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_image_id", nullable = false)
    private CharacterImage characterImage;

    @OneToOne(mappedBy = "character", cascade = CascadeType.ALL, orphanRemoval = true)
    private CharacterPersona characterPersona;

    @OneToMany(mappedBy = "character", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CharacterTriggerWord> triggerWords = new ArrayList<>();

    // ======================================
    // [비즈니스 로직]
    // ======================================
    public void updateCharacter(String name, Gender gender, VoiceType voiceType, CharacterImage characterImage) {
        this.name = name;
        this.gender = gender;
        this.voiceType = voiceType;
        this.characterImage = characterImage;
    }
}
