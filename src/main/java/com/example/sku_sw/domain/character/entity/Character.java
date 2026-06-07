package com.example.sku_sw.domain.character.entity;

import com.example.sku_sw.domain.character.enums.CharacterAppearanceType;
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
import jakarta.persistence.OrderBy;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "appearance_type", nullable = false)
    private CharacterAppearanceType characterAppearanceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_image_id")
    private CharacterImage characterImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_vrm_id")
    private CharacterVrm characterVrm;

    @OneToOne(mappedBy = "character", cascade = CascadeType.ALL, orphanRemoval = true)
    private CharacterPersona characterPersona;

    @OneToMany(mappedBy = "character", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<CharacterTriggerWord> triggerWords = new ArrayList<>();

    // ======================================
    // [비즈니스 로직]
    // ======================================

    /**
     * 캐릭터 정보 수정 (2D / 3D 공용)
     * @param name : 수정할 캐릭터 이름
     * @param gender : 수정할 캐릭터 성별
     * @param characterImage : 수정할 2D 캐릭터 이미지 (3D인 경우 null)
     * @param characterVrm : 수정할 3D 캐릭터 VRM (2D인 경우 null)
     */
    public void updateCharacter(String name, Gender gender, CharacterImage characterImage, CharacterVrm characterVrm) {
        this.name = name;
        this.gender = gender;
        this.characterImage = characterImage;
        this.characterVrm = characterVrm;
    }

    /**
     * 캐릭터 정보 수정 (2D 전용)
     * @param name : 수정할 캐릭터 이름
     * @param gender : 수정할 캐릭터 성별
     * @param characterImage : 수정할 2D 캐릭터 이미지
     */
    public void updateCharacter(String name, Gender gender, CharacterImage characterImage) {
        this.name = name;
        this.gender = gender;
        this.characterImage = characterImage;
    }
}
