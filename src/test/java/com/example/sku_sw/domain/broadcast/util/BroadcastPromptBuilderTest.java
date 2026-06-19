package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastPromptHistoryContext;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastPromptBuilderTest {

    private final BroadcastPromptBuilder broadcastPromptBuilder = new BroadcastPromptBuilder();

    @Test
    @DisplayName("이전 방송 분석과 누적 유행어가 시작 프롬프트에 포함된다")
    void buildBroadcastDialoguePrompt_이전방송분석과유행어포함() {
        BroadcastCharacterRedisDto character = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("하준")
                .build();
        BroadcastInfoRedisDto summary = BroadcastInfoRedisDto.builder()
                .content("오늘은 게임과 잡담이 섞여 있었다.")
                .build();
        List<BroadcastInfoRedisDto> recentDialogues = List.of(
                BroadcastInfoRedisDto.builder()
                        .subject(DialogueSubject.STREAMER)
                        .content("지금 보스전 갑니다")
                        .build()
        );
        BroadcastPromptHistoryContext historyContext = new BroadcastPromptHistoryContext(
                List.of(new BroadcastPromptHistoryContext.RecentBroadcastAnalysis(
                        10L,
                        "stream-10",
                        LocalDateTime.of(2026, 6, 18, 20, 0),
                        "액션 게임 진행",
                        "시청자와 티키타카가 많음",
                        "초반 게임, 후반 잡담",
                        "집중감 있는 방송"
                )),
                List.of(new BroadcastPromptHistoryContext.HistoricalCatchPhrase(
                        20L,
                        "stream-20",
                        "무야호",
                        DialogueSubject.STREAMER,
                        "승리 직후 외친 멘트",
                        LocalDateTime.of(2026, 6, 17, 20, 0),
                        2L
                ))
        );

        String prompt = broadcastPromptBuilder.buildBroadcastDialoguePrompt(
                character,
                summary,
                recentDialogues,
                historyContext
        );

        assertThat(prompt).contains("이전 방송 분석 이력");
        assertThat(prompt).contains("누적 유행어 이력");
        assertThat(prompt).contains("액션 게임 진행");
        assertThat(prompt).contains("무야호");
        assertThat(prompt).contains("승리 직후 외친 멘트");
    }
}
