package com.example.sku_sw.domain.broadcast.entity;

import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방송 분석 결과에 포함되는 유행어 Entity.
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
     * 유행어 또는 키워드 본문
     */
    @Column(nullable = false)
    private String content;

    /**
     * 유행어 주체
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DialogueSubject subject;

    /**
     * 유행어가 발생한 상황 설명
     */
    @Column(name = "situation_analysis", columnDefinition = "TEXT")
    private String situationAnalysis;

    /**
     * 방송 분석 결과
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_analysis_id", nullable = false)
    private BroadcastAnalysis broadcastAnalysis;

    /**
     * CatchPhrase 생성
     *
     * @param content 유행어 본문
     * @param subject 유행어 주체
     * @param situationAnalysis 유행어 발생 상황
     * @return 생성된 CatchPhrase
     */
    public static CatchPhrase create(String content, DialogueSubject subject, String situationAnalysis) {
        return CatchPhrase.builder()
                .content(content)
                .subject(subject)
                .situationAnalysis(situationAnalysis)
                .build();
    }

    /**
     * BroadcastAnalysis 연결
     *
     * @param broadcastAnalysis 대상 방송 분석 결과
     */
    public void assignBroadcastAnalysis(BroadcastAnalysis broadcastAnalysis) {
        this.broadcastAnalysis = broadcastAnalysis;
    }
}
