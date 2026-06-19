package com.example.sku_sw.domain.broadcast.mapper;

import com.example.sku_sw.domain.broadcast.dto.BroadcastAnalysisResDto;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import com.example.sku_sw.domain.broadcast.entity.TimeLine;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastAnalysisMapperTest {

    private final BroadcastAnalysisMapper broadcastAnalysisMapper = new BroadcastAnalysisMapper();

    @Test
    @DisplayName("toBroadcastAnalysisResDto - catchPhrases의 주체와 상황 설명을 함께 매핑한다")
    void toBroadcastAnalysisResDto_maps_structured_catch_phrases() {
        BroadcastAnalysis broadcastAnalysis = BroadcastAnalysis.builder()
                .majorContent("메인")
                .majorMoodWithViewers("분위기")
                .summary("요약")
                .totalAnalysis("분석")
                .build();
        broadcastAnalysis.addCatchPhrase(CatchPhrase.create(
                "레전드",
                DialogueSubject.VIEWER,
                "시청자가 놀라며 반복한 표현"
        ));
        broadcastAnalysis.addTimeLine(TimeLine.create(
                "오프닝",
                LocalDateTime.of(2026, 6, 28, 9, 0, 0),
                LocalDateTime.of(2026, 6, 28, 9, 10, 0)
        ));

        BroadcastAnalysisResDto result = broadcastAnalysisMapper.toBroadcastAnalysisResDto(broadcastAnalysis);

        assertThat(result.catchPhrases()).hasSize(1);
        assertThat(result.catchPhrases().get(0).content()).isEqualTo("레전드");
        assertThat(result.catchPhrases().get(0).subject()).isEqualTo(DialogueSubject.VIEWER);
        assertThat(result.catchPhrases().get(0).situationAnalysis()).isEqualTo("시청자가 놀라며 반복한 표현");
    }
}
