package com.example.sku_sw.domain.user.entity;

import com.example.sku_sw.domain.user.enums.RegisterType;
import com.example.sku_sw.domain.user.enums.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class User {
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
    
}
