package com.example.sku_sw.domain.broadcast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방송 키워드 Entity
 * - 방송 진행 도중 30초마다 방송 흐름에 맞는 주요 키워드를 기록
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "broadcast_keywords")
public class BroadcastKeywords {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 키워드 내용
     */
    @Column(name = "content", nullable = false)
    private String content;

    /**
     * 키워드가 기록된 시각
     */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /**
     * 방송 FK
     * - 해당 방송 데이터가 삭제되면 같이 삭제됨
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id", nullable = false)
    private Broadcast broadcast;

    // ======================================
    // [비즈니스 로직]
    // ======================================

    /**
     * BroadcastKeywords를 생성한다.
     * - 키워드는 정규화 후 저장되며, 정규화 결과 빈 문자열이면 null을 반환한다.
     * @param rawKeyword : 원본 키워드 문자열
     * @param broadcast : 방송 Entity
     * @return : 정규화된 BroadcastKeywords, 또는 null
     */
    public static BroadcastKeywords create(String rawKeyword, Broadcast broadcast) {
        String normalized = normalizeKeyword(rawKeyword);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        return BroadcastKeywords.builder()
                .content(normalized)
                .recordedAt(LocalDateTime.now())
                .broadcast(broadcast)
                .build();
    }

    /**
     * 키워드를 정규화한다.
     * - 앞뒤 공백 제거 및 내부 공백 collapse
     * - 한글, 영문, 숫자만 유지 (특수문자/이모지 제거)
     * @param raw : 원본 키워드
     * @return : 정규화된 키워드, 빈 문자열이면 null
     */
    public static String normalizeKeyword(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 앞뒤 공백 제거 및 내부 공백 collapse
        String trimmed = raw.strip().replaceAll("\\s+", " ");
        // 한글, 영문, 숫자, 공백만 유지
        String normalized = trimmed.replaceAll("[^\\p{IsHangul}a-zA-Z0-9 ]", "");
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.strip();
    }
}
