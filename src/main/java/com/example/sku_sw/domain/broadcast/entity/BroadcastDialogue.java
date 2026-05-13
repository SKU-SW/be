package com.example.sku_sw.domain.broadcast.entity;

import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.global.entity.BaseTimeEntity;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방송 대화 기록 Entity
 * - 해당 방송 레코드와 연결된 하루 방송의 대화를 저장
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(
        name = "broadcast_dialogue",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_broadcast_dialogue_broadcast_cursor", columnNames = {"broadcast_id", "cursor_id"})
        }
)
public class BroadcastDialogue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Redis에서 생성된 각 대화별 고유 Cursor용 ID값
     * 데이터가 Redis에 쌓였을 때 BroadcastDialogue에 자동으로 저장되게되는데, 그때 Redis에 담긴 cursorId도 저장됨
     */
    @Column(name = "cursor_id", nullable = false)
    private Long cursorId;

    /**
     * 대화 주체
     * - STREAMER: 스트리머
     * - AI_CHARACTER: AI 캐릭터
     * - VIEWER: 시청자
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DialogueSubject subject;

    /**
     * 대화 내용 (문장 1개)
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 대화 생성 시간
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 방송 FK
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id", nullable = false)
    private Broadcast broadcast;
}
