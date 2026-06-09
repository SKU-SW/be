package com.example.sku_sw.domain.broadcast.entity;

import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.global.entity.BaseTimeEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방송 Entity
 * - 1회 방송별 고유 정보를 저장
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "broadcast")
public class Broadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 방송별 고유 ID
     */
    @Column(name = "stream_id", nullable = false, unique = true, length = 16)
    private String streamId;

    /**
     * 방송 상태
     * - BROADCASTING: 방송중
     * - TERMINATED: 방송종료됨
     * - ABNORMAL_TERMINATED: 비정상종료됨
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private BroadcastStatus status = BroadcastStatus.BROADCASTING;

    /**
     * 방송 시작 시간
     */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /**
     * 방송 종료 시간
     */
    @Column(name = "terminated_at")
    private LocalDateTime terminatedAt;

    /**
     * AI 캐릭터 FK
     * - AI 캐릭터가 삭제되면 같이 삭제됨
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @OneToMany(mappedBy = "broadcast", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BroadcastDialogue> broadcastDialogueList = new ArrayList<>();

    @OneToMany(mappedBy = "broadcast", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BroadcastStats> broadcastStatsList = new ArrayList<>();

    @OneToMany(mappedBy = "broadcast", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BroadcastKeywords> broadcastKeywordsList = new ArrayList<>();

    @OneToOne(mappedBy = "broadcast", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private BroadcastAnalysis broadcastAnalysis;

    // ======================================
    // [비즈니스 로직]
    // ======================================

    public static Broadcast startBroadcast(String streamId, Character character){
        return Broadcast.builder()
                .streamId(streamId)
                .startedAt(LocalDateTime.now())
                .terminatedAt(null)
                .character(character)
                .build();
    }


    /**
     * 정상 방송 종료 처리
     */
    public void normalTerminate() {
        this.terminatedAt = LocalDateTime.now();
        this.status = BroadcastStatus.TERMINATED;
    }

    /**
     * 비정상 방송 종료 처리
     */
    public void abnormalTerminate() {
        this.terminatedAt = LocalDateTime.now();
        this.status = BroadcastStatus.ABNORMAL_TERMINATED;
    }
}
