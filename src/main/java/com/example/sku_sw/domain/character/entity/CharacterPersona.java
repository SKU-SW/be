package com.example.sku_sw.domain.character.entity;

import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
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
@Table(name = "character_persona")
public class CharacterPersona {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PresetType presetType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpeechStyle speechStyle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Personality personality;

    // ===============================================
    // [비즈니스 로직]
    // ===============================================
    public void updateCharacterPersona(PresetType presetType, SpeechStyle speechStyle, Personality personality) {
        this.presetType = presetType;
        this.speechStyle = speechStyle;
        this.personality = personality;
    }

}
