package com.example.sku_sw.domain.setting.entity;

import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "broadcast_setting")
public class BroadcastSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    @Builder.Default
    private boolean aiProactiveToChat = true;

    // =========================================
    // [ 비즈니스 함수 ]
    // =========================================

    /**
     * AI 선제 반응 설정 값을 수정한다.
     * @param aiProactiveToChat : 설정할 AI 선제 반응 값
     */
    public void updateAiProactiveToChat(boolean aiProactiveToChat) {
        this.aiProactiveToChat = aiProactiveToChat;
    }

    /**
     * 방송 설정을 초기값으로 되돌린다.
     * - aiProactiveToChat = true
     */
    public void init() {
        this.aiProactiveToChat = true;
    }
}
