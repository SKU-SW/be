package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastPromptHistoryContext;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.repository.BroadcastAnalysisRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.repository.CatchPhraseRepository;
import com.example.sku_sw.domain.character.entity.Character;
import com.example.sku_sw.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BroadcastPromptHistoryServiceTest {

    @Mock
    private BroadcastRepository broadcastRepository;

    @Mock
    private BroadcastAnalysisRepository broadcastAnalysisRepository;

    @Mock
    private CatchPhraseRepository catchPhraseRepository;

    @InjectMocks
    private BroadcastPromptHistoryService broadcastPromptHistoryService;

    @Test
    @DisplayName("유행어가 10개 이하이면 전체를 프롬프트 이력에 포함한다")
    void buildPromptHistoryContext_유행어10개이하면전체포함() {
        Broadcast currentBroadcast = createBroadcast(100L, "current-stream", 1L, LocalDateTime.of(2026, 6, 19, 20, 0));
        BroadcastAnalysis previousAnalysis = createBroadcastAnalysis(
                createBroadcast(90L, "previous-stream", 1L, LocalDateTime.of(2026, 6, 18, 20, 0)),
                "게임 방송",
                "편안함",
                "요약",
                "총평"
        );
        List<CatchPhrase> catchPhrases = List.of(
                createCatchPhrase(1L, "무야호", DialogueSubject.STREAMER, "하이라이트 장면"),
                createCatchPhrase(2L, "네이스", DialogueSubject.VIEWER, "채팅 반응")
        );

        given(broadcastRepository.findByStreamId("current-stream")).willReturn(Optional.of(currentBroadcast));
        given(broadcastAnalysisRepository.findRecentByUserIdAndBroadcastIdNot(eq(1L), eq(100L), any(Pageable.class)))
                .willReturn(List.of(previousAnalysis));
        given(catchPhraseRepository.findAllByUserIdAndBroadcastIdNotOrderByBroadcastStartedAtDesc(1L, 100L))
                .willReturn(catchPhrases);

        BroadcastPromptHistoryContext result = broadcastPromptHistoryService.buildPromptHistoryContext("current-stream");

        assertThat(result.recentBroadcastAnalyses()).hasSize(1);
        assertThat(result.historicalCatchPhrases()).hasSize(2);
        assertThat(result.historicalCatchPhrases())
                .extracting(BroadcastPromptHistoryContext.HistoricalCatchPhrase::content)
                .containsExactly("무야호", "네이스");
    }

    @Test
    @DisplayName("유행어가 10개를 초과하면 서로 다른 방송에서 중복된 문구만 포함한다")
    void buildPromptHistoryContext_유행어10개초과시중복문구만포함() {
        Broadcast currentBroadcast = createBroadcast(100L, "current-stream", 1L, LocalDateTime.of(2026, 6, 19, 20, 0));
        Broadcast broadcastA = createBroadcast(91L, "stream-a", 1L, LocalDateTime.of(2026, 6, 18, 20, 0));
        Broadcast broadcastB = createBroadcast(92L, "stream-b", 1L, LocalDateTime.of(2026, 6, 17, 20, 0));
        Broadcast broadcastC = createBroadcast(93L, "stream-c", 1L, LocalDateTime.of(2026, 6, 16, 20, 0));

        List<CatchPhrase> catchPhrases = List.of(
                createCatchPhrase(11L, "중복유행어", DialogueSubject.STREAMER, "가장 최근 장면", broadcastA),
                createCatchPhrase(12L, "중복유행어", DialogueSubject.VIEWER, "이전 장면", broadcastB),
                createCatchPhrase(13L, "또다른중복", DialogueSubject.VIEWER, "채팅 밈", broadcastA),
                createCatchPhrase(14L, "또다른중복", DialogueSubject.STREAMER, "반복 발언", broadcastC),
                createCatchPhrase(15L, "단일1", DialogueSubject.VIEWER, "상황1", broadcastA),
                createCatchPhrase(16L, "단일2", DialogueSubject.VIEWER, "상황2", broadcastA),
                createCatchPhrase(17L, "단일3", DialogueSubject.VIEWER, "상황3", broadcastA),
                createCatchPhrase(18L, "단일4", DialogueSubject.VIEWER, "상황4", broadcastA),
                createCatchPhrase(19L, "단일5", DialogueSubject.VIEWER, "상황5", broadcastA),
                createCatchPhrase(20L, "단일6", DialogueSubject.VIEWER, "상황6", broadcastA),
                createCatchPhrase(21L, "단일7", DialogueSubject.VIEWER, "상황7", broadcastA)
        );

        given(broadcastRepository.findByStreamId("current-stream")).willReturn(Optional.of(currentBroadcast));
        given(broadcastAnalysisRepository.findRecentByUserIdAndBroadcastIdNot(eq(1L), eq(100L), any(Pageable.class)))
                .willReturn(List.of());
        given(catchPhraseRepository.findAllByUserIdAndBroadcastIdNotOrderByBroadcastStartedAtDesc(1L, 100L))
                .willReturn(catchPhrases);

        BroadcastPromptHistoryContext result = broadcastPromptHistoryService.buildPromptHistoryContext("current-stream");

        assertThat(result.historicalCatchPhrases()).hasSize(2);
        assertThat(result.historicalCatchPhrases())
                .extracting(BroadcastPromptHistoryContext.HistoricalCatchPhrase::content)
                .containsExactly("또다른중복", "중복유행어");
    }

    @Test
    @DisplayName("중복 유행어 대표 항목은 가장 최근 방송의 정보를 사용한다")
    void buildPromptHistoryContext_중복유행어대표는가장최근방송정보사용() {
        Broadcast currentBroadcast = createBroadcast(100L, "current-stream", 1L, LocalDateTime.of(2026, 6, 19, 20, 0));
        Broadcast recentBroadcast = createBroadcast(91L, "stream-recent", 1L, LocalDateTime.of(2026, 6, 18, 20, 0));
        Broadcast oldBroadcast = createBroadcast(92L, "stream-old", 1L, LocalDateTime.of(2026, 6, 17, 20, 0));

        List<CatchPhrase> catchPhrases = List.of(
                createCatchPhrase(31L, "중복유행어", DialogueSubject.STREAMER, "최근 상황", recentBroadcast),
                createCatchPhrase(32L, "중복유행어", DialogueSubject.VIEWER, "이전 상황", oldBroadcast),
                createCatchPhrase(33L, "단일1", DialogueSubject.VIEWER, "상황1", recentBroadcast),
                createCatchPhrase(34L, "단일2", DialogueSubject.VIEWER, "상황2", recentBroadcast),
                createCatchPhrase(35L, "단일3", DialogueSubject.VIEWER, "상황3", recentBroadcast),
                createCatchPhrase(36L, "단일4", DialogueSubject.VIEWER, "상황4", recentBroadcast),
                createCatchPhrase(37L, "단일5", DialogueSubject.VIEWER, "상황5", recentBroadcast),
                createCatchPhrase(38L, "단일6", DialogueSubject.VIEWER, "상황6", recentBroadcast),
                createCatchPhrase(39L, "단일7", DialogueSubject.VIEWER, "상황7", recentBroadcast),
                createCatchPhrase(40L, "단일8", DialogueSubject.VIEWER, "상황8", recentBroadcast),
                createCatchPhrase(41L, "단일9", DialogueSubject.VIEWER, "상황9", recentBroadcast)
        );

        given(broadcastRepository.findByStreamId("current-stream")).willReturn(Optional.of(currentBroadcast));
        given(broadcastAnalysisRepository.findRecentByUserIdAndBroadcastIdNot(eq(1L), eq(100L), any(Pageable.class)))
                .willReturn(List.of());
        given(catchPhraseRepository.findAllByUserIdAndBroadcastIdNotOrderByBroadcastStartedAtDesc(1L, 100L))
                .willReturn(catchPhrases);

        BroadcastPromptHistoryContext result = broadcastPromptHistoryService.buildPromptHistoryContext("current-stream");

        assertThat(result.historicalCatchPhrases()).hasSize(1);
        assertThat(result.historicalCatchPhrases().get(0).streamId()).isEqualTo("stream-recent");
        assertThat(result.historicalCatchPhrases().get(0).subject()).isEqualTo(DialogueSubject.STREAMER);
        assertThat(result.historicalCatchPhrases().get(0).situationAnalysis()).isEqualTo("최근 상황");
        assertThat(result.historicalCatchPhrases().get(0).duplicateBroadcastCount()).isEqualTo(2L);
    }

    private Broadcast createBroadcast(Long id, String streamId, Long userId, LocalDateTime startedAt) {
        User user = User.builder()
                .id(userId)
                .name("user")
                .email("user@test.com")
                .hashedPassword("password")
                .build();
        Character character = Character.builder()
                .id(id)
                .user(user)
                .name("character")
                .build();
        return Broadcast.builder()
                .id(id)
                .streamId(streamId)
                .character(character)
                .startedAt(startedAt)
                .build();
    }

    private BroadcastAnalysis createBroadcastAnalysis(
            Broadcast broadcast,
            String majorContent,
            String majorMoodWithViewers,
            String summary,
            String totalAnalysis
    ) {
        return BroadcastAnalysis.builder()
                .broadcast(broadcast)
                .majorContent(majorContent)
                .majorMoodWithViewers(majorMoodWithViewers)
                .summary(summary)
                .totalAnalysis(totalAnalysis)
                .build();
    }

    private CatchPhrase createCatchPhrase(Long id, String content, DialogueSubject subject, String situationAnalysis) {
        Broadcast broadcast = createBroadcast(id + 1000, "stream-" + id, 1L, LocalDateTime.of(2026, 6, 18, 20, 0).minusMinutes(id));
        return createCatchPhrase(id, content, subject, situationAnalysis, broadcast);
    }

    private CatchPhrase createCatchPhrase(
            Long id,
            String content,
            DialogueSubject subject,
            String situationAnalysis,
            Broadcast broadcast
    ) {
        BroadcastAnalysis analysis = createBroadcastAnalysis(
                broadcast,
                "majorContent",
                "majorMoodWithViewers",
                "summary",
                "totalAnalysis"
        );
        return CatchPhrase.builder()
                .id(id)
                .content(content)
                .subject(subject)
                .situationAnalysis(situationAnalysis)
                .broadcastAnalysis(analysis)
                .build();
    }
}
