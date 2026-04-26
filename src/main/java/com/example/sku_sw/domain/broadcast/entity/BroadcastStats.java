package com.example.sku_sw.domain.broadcast.entity;

import com.example.sku_sw.global.entity.BaseTimeEntity;
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
 * 방송 통계 Entity
 * - 방송 진행 도중 1분마다 방송 통계를 기록
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "broadcast_stats")
public class BroadcastStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 지난 1분 평균 시청자 수
     */
    @Column(name = "avg_viewer_num", nullable = false)
    private Integer avgViewerNum;

    /**
     * 지난 1분 총 발생 채팅 수
     */
    @Column(name = "total_chat_num", nullable = false)
    private Integer totalChatNum;

    /**
     * 긍정 채팅 수
     */
    @Column(name = "positive_chat_count", nullable = false)
    private Integer positiveChatCount;

    /**
     * 중립 채팅 수
     */
    @Column(name = "neutral_chat_count", nullable = false)
    private Integer neutralChatCount;

    /**
     * 부정 채팅 수
     */
    @Column(name = "negative_chat_count", nullable = false)
    private Integer negativeChatCount;

    /**
     * 통계가 집계된 시각 (1분 단위)
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
}
