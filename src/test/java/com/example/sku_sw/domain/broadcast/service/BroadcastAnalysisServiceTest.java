package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.enums.BroadcastDataStatus;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.repository.BroadcastAnalysisRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.global.util.GeminiUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastAnalysisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private BroadcastRepository broadcastRepository;

    @Mock
    private BroadcastDialogueRepository broadcastDialogueRepository;

    @Mock
    private BroadcastAnalysisRepository broadcastAnalysisRepository;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private GeminiUtil geminiUtil;

    private BroadcastAnalysisService broadcastAnalysisService;

    @BeforeEach
    void setUp() {
        broadcastAnalysisService = new BroadcastAnalysisService(
                broadcastRepository,
                broadcastDialogueRepository,
                broadcastAnalysisRepository,
                broadcastRedisUtil,
                geminiUtil,
                objectMapper
        );
    }

    @Test
    @DisplayName("analysisBroadcastDialogues - Redis summary를 저장하고 catchPhrase의 주체와 상황 설명을 유지한다")
    void analysisBroadcastDialogues_uses_redis_summary_and_structured_catch_phrases() {
        Broadcast broadcast = Broadcast.builder()
                .id(1L)
                .streamId("stream-1")
                .startedAt(LocalDateTime.of(2026, 6, 28, 9, 0, 0))
                .terminatedAt(LocalDateTime.of(2026, 6, 28, 10, 0, 0))
                .build();
        BroadcastDialogue streamerDialogue = BroadcastDialogue.builder()
                .id(10L)
                .cursorId(10L)
                .subject(DialogueSubject.STREAMER)
                .content("오늘 진짜 레전드네요")
                .createdAt(LocalDateTime.of(2026, 6, 28, 9, 5, 0))
                .broadcast(broadcast)
                .build();
        BroadcastDialogue viewerDialogue = BroadcastDialogue.builder()
                .id(11L)
                .cursorId(11L)
                .subject(DialogueSubject.VIEWER)
                .content("레전드")
                .createdAt(LocalDateTime.of(2026, 6, 28, 9, 6, 0))
                .broadcast(broadcast)
                .build();

        BroadcastInfoRedisDto summary = BroadcastInfoRedisDto.buildSummaryDto(
                "Redis summary text",
                BroadcastDataStatus.ACTIVE
        );

        String geminiResponse = """
                {
                  "majorContent": "메인 콘텐츠",
                  "majorMoodWithViewers": "분위기",
                  "totalAnalysis": "종합 분석",
                  "catchPhrases": [
                    {
                      "content": "레전드",
                      "subject": "VIEWER",
                      "situationAnalysis": "시청자가 놀라며 반복한 표현"
                    },
                    {
                      "content": "형님",
                      "subject": "STREAMER",
                      "situationAnalysis": "스트리머가 농담처럼 여러 번 부른 표현"
                    }
                  ],
                  "timeLines": [
                    {
                      "content": "오프닝",
                      "startTime": "2026-06-28 09:00:00",
                      "endTime": "2026-06-28 09:10:00"
                    }
                  ]
                }
                """;

        given(broadcastRepository.findByStreamId("stream-1")).willReturn(Optional.of(broadcast));
        given(broadcastAnalysisRepository.existsByBroadcast(broadcast)).willReturn(false);
        given(broadcastDialogueRepository.findByBroadcastOrderByCreatedAtAsc(broadcast))
                .willReturn(List.of(streamerDialogue, viewerDialogue));
        given(broadcastRedisUtil.getSummary("stream-1")).willReturn(summary);
        given(geminiUtil.analyzeBroadcastDialogues(anyString())).willReturn(Mono.just(geminiResponse));

        broadcastAnalysisService.analysisBroadcastDialogues("stream-1");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(geminiUtil).analyzeBroadcastDialogues(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).doesNotContain("\"summary\"");
        assertThat(promptCaptor.getValue()).contains("\"catchPhrases\"");

        ArgumentCaptor<BroadcastAnalysis> analysisCaptor = ArgumentCaptor.forClass(BroadcastAnalysis.class);
        verify(broadcastAnalysisRepository).save(analysisCaptor.capture());
        BroadcastAnalysis savedAnalysis = analysisCaptor.getValue();
        assertThat(savedAnalysis.getSummary()).isEqualTo("Redis summary text");
        assertThat(savedAnalysis.getCatchPhrases()).hasSize(2);
        assertThat(savedAnalysis.getCatchPhrases().get(0).getContent()).isEqualTo("레전드");
        assertThat(savedAnalysis.getCatchPhrases().get(0).getSubject()).isEqualTo(DialogueSubject.VIEWER);
        assertThat(savedAnalysis.getCatchPhrases().get(0).getSituationAnalysis()).isEqualTo("시청자가 놀라며 반복한 표현");
        assertThat(savedAnalysis.getCatchPhrases().get(1).getSubject()).isEqualTo(DialogueSubject.STREAMER);
    }
}
