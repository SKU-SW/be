package com.example.sku_sw.domain.broadcast.entity;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방송 분석 결과 Entity
 * - 1회 방송에 대한 분석 결과 (주요 주제, 시청자와의 주요 분위기, 요약, 종합 분석)
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "broadcast_analysis")
public class BroadcastAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 주요 내용
     */
    @Column(name = "major_content", nullable = false, length = 100)
    private String majorContent;

    /**
     * 시청자와의 주요 분위기
     */
    @Column(name = "major_mood_with_viewers", nullable = false, length = 200)
    private String majorMoodWithViewers;

    /**
     * 요약
     */
    @Column(nullable = false, length = 500)
    private String summary;

    /**
     * 종합 분석
     */
    @Column(name = "total_analysis", nullable = false, length = 500)
    private String totalAnalysis;

    /**
     * 방송 (1:1 관계)
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id", nullable = false, unique = true)
    private Broadcast broadcast;

    /**
     * 캐치프레이즈 목록
     */
    @OneToMany(mappedBy = "broadcastAnalysis", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CatchPhrase> catchPhrases = new ArrayList<>();

    /**
     * 타임라인 목록
     */
    @OneToMany(mappedBy = "broadcastAnalysis", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TimeLine> timeLines = new ArrayList<>();

    // ======================================
    // [생성 메서드]
    // ======================================

    /**
     * BroadcastAnalysis 생성
     *
     * @param broadcast              방송
     * @param majorContent           주요 내용
     * @param majorMoodWithViewers   시청자와의 주요 분위기
     * @param summary                요약
     * @param totalAnalysis          종합 분석
     * @return 생성된 BroadcastAnalysis
     */
    public static BroadcastAnalysis create(Broadcast broadcast, String majorContent,
                                           String majorMoodWithViewers, String summary,
                                           String totalAnalysis) {
        return BroadcastAnalysis.builder()
                .broadcast(broadcast)
                .majorContent(majorContent)
                .majorMoodWithViewers(majorMoodWithViewers)
                .summary(summary)
                .totalAnalysis(totalAnalysis)
                .build();
    }

    // ======================================
    // [연관관계 편의 메서드]
    // ======================================

    /**
     * CatchPhrase 추가 (양방향 연관관계 설정)
     *
     * @param catchPhrase 추가할 CatchPhrase
     */
    public void addCatchPhrase(CatchPhrase catchPhrase) {
        this.catchPhrases.add(catchPhrase);
        catchPhrase.assignBroadcastAnalysis(this);
    }

    /**
     * TimeLine 추가 (양방향 연관관계 설정)
     *
     * @param timeLine 추가할 TimeLine
     */
    public void addTimeLine(TimeLine timeLine) {
        this.timeLines.add(timeLine);
        timeLine.assignBroadcastAnalysis(this);
    }
}
