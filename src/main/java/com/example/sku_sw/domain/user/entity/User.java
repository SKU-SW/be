package com.example.sku_sw.domain.user.entity;

import com.example.sku_sw.domain.user.enums.RegisterType;
import com.example.sku_sw.domain.user.enums.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class User {
    private static final long CHZZK_TOKEN_EXPIRY_BUFFER_SECONDS = 60L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String hashedPassword;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserRole role = UserRole.ROLE_USER;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RegisterType registerType = RegisterType.EMAIL;

    @Column(name = "selected_character_id")
    private Long selectedCharacterId;

    @Column(nullable = false)
    @Builder.Default
    private boolean chzzkApiAuthorized = false;

    @Column(length = 2000)
    private String chzzkAuthAccessToken;

    @Column(length = 2000)
    private String chzzkAuthRefreshToken;

    private LocalDateTime chzzkAuthAccessTokenExpiresAt;

    private LocalDateTime chzzkAuthRefreshTokenExpiresAt;

    // =========================================
    // [ 비즈니스 함수 ]
    // =========================================
    public static User createUser(String name, String email, String hashedPassword, RegisterType registerType) {
        return User.builder()
                .name(name)
                .email(email)
                .hashedPassword(hashedPassword)
                .registerType(registerType == null ? RegisterType.EMAIL : registerType)
                .build();
    }

    public void updateSelectedCharacterId(Long selectedCharacterId) {
        this.selectedCharacterId = selectedCharacterId;
    }

    public void updateChzzkAuthTokens(
            String chzzkAuthAccessToken,
            String chzzkAuthRefreshToken,
            LocalDateTime chzzkAuthAccessTokenExpiresAt,
            LocalDateTime chzzkAuthRefreshTokenExpiresAt
    ) {
        this.chzzkApiAuthorized = true;
        this.chzzkAuthAccessToken = chzzkAuthAccessToken;
        this.chzzkAuthRefreshToken = chzzkAuthRefreshToken;
        this.chzzkAuthAccessTokenExpiresAt = chzzkAuthAccessTokenExpiresAt;
        this.chzzkAuthRefreshTokenExpiresAt = chzzkAuthRefreshTokenExpiresAt;
    }

    public void clearChzzkAuthTokens() {
        this.chzzkApiAuthorized = false;
        this.chzzkAuthAccessToken = null;
        this.chzzkAuthRefreshToken = null;
        this.chzzkAuthAccessTokenExpiresAt = null;
        this.chzzkAuthRefreshTokenExpiresAt = null;
    }

    public boolean isChzzkAuthAccessTokenExpired() {
        return isExpired(chzzkAuthAccessTokenExpiresAt);
    }

    public boolean isChzzkAuthRefreshTokenExpired() {
        return isExpired(chzzkAuthRefreshTokenExpiresAt);
    }

    public boolean hasChzzkAuthTokens() {
        return chzzkApiAuthorized && hasText(chzzkAuthAccessToken) && hasText(chzzkAuthRefreshToken);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isExpired(LocalDateTime expiresAt) {
        return expiresAt == null || !expiresAt.isAfter(LocalDateTime.now().plusSeconds(CHZZK_TOKEN_EXPIRY_BUFFER_SECONDS));
    }
}
