package com.example.sku_sw.domain.broadcast.entity;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 캐치프레이즈 Entity
 * - 방송 분석 결과에 포함되는 캐치프레이즈 정보
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "catch_phrase")
public class CatchPhrase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 캐치프레이즈 내용
     */
    @Column(nullable = false)
    private String content;

    /**
     * 방송 분석 결과
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_analysis_id", nullable = false)
    private BroadcastAnalysis broadcastAnalysis;

    // ======================================
    // [생성 메서드]
    // ======================================

    /**
     * CatchPhrase 생성
     *
     * @param content 캐치프레이즈 내용
     * @return 생성된 CatchPhrase
     */
    public static CatchPhrase create(String content) {
        return CatchPhrase.builder()
                .content(content)
                .build();
    }

    // ======================================
    // [연관관계 편의 메서드]
    // ======================================

    /**
     * BroadcastAnalysis 할당 (양방향 연관관계 설정)
     *
     * @param broadcastAnalysis 할당할 BroadcastAnalysis
     */
    public void assignBroadcastAnalysis(BroadcastAnalysis broadcastAnalysis) {
        this.broadcastAnalysis = broadcastAnalysis;
    }
}
