package com.example.sku_sw.domain.broadcast.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 타임라인 Entity
 * - 방송 분석 결과에 포함되는 타임라인 정보 (구간별 내용, 시작/종료 시간)
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "time_line")
public class TimeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 타임라인 내용
     */
    @Column(nullable = false)
    private String content;

    /**
     * 구간 시작 시간
     */
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /**
     * 구간 종료 시간
     */
    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

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
     * TimeLine 생성
     *
     * @param content   타임라인 내용
     * @param startTime 구간 시작 시간
     * @param endTime   구간 종료 시간
     * @return 생성된 TimeLine
     */
    public static TimeLine create(String content, LocalDateTime startTime, LocalDateTime endTime) {
        return TimeLine.builder()
                .content(content)
                .startTime(startTime)
                .endTime(endTime)
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
