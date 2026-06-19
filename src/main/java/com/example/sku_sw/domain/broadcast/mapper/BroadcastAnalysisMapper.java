package com.example.sku_sw.domain.broadcast.mapper;

import com.example.sku_sw.domain.broadcast.dto.BroadcastAnalysisResDto;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import com.example.sku_sw.domain.broadcast.entity.TimeLine;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class BroadcastAnalysisMapper {

    private static final DateTimeFormatter ANALYSIS_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * BroadcastAnalysis Entity를 BroadcastAnalysisResDto로 변환하는 함수
     *
     * @param broadcastAnalysis 변환할 방송 분석 Entity
     * @return 변환된 BroadcastAnalysisResDto
     */
    public BroadcastAnalysisResDto toBroadcastAnalysisResDto(BroadcastAnalysis broadcastAnalysis) {
        if (broadcastAnalysis == null) {
            return null;
        }

        return BroadcastAnalysisResDto.builder()
                .majorContent(broadcastAnalysis.getMajorContent())
                .majorMoodWithViewers(broadcastAnalysis.getMajorMoodWithViewers())
                .summary(broadcastAnalysis.getSummary())
                .totalAnalysis(broadcastAnalysis.getTotalAnalysis())
                .catchPhrases(broadcastAnalysis.getCatchPhrases().stream()
                        .map(this::toCatchPhraseResDto)
                        .toList())
                .timeLines(broadcastAnalysis.getTimeLines().stream()
                        .map(this::toTimeLineResDto)
                        .toList())
                .build();
    }

    /**
     * CatchPhrase Entity를 CatchPhraseResDto로 변환하는 함수
     *
     * @param catchPhrase 변환할 방송 유행어 Entity
     * @return 변환된 CatchPhraseResDto
     */
    public BroadcastAnalysisResDto.CatchPhraseResDto toCatchPhraseResDto(CatchPhrase catchPhrase) {
        return BroadcastAnalysisResDto.CatchPhraseResDto.builder()
                .content(catchPhrase.getContent())
                .subject(catchPhrase.getSubject())
                .situationAnalysis(catchPhrase.getSituationAnalysis())
                .build();
    }

    /**
     * TimeLine Entity를 TimeLineResDto로 변환하는 함수
     *
     * @param timeLine 변환할 방송 분석 타임라인 Entity
     * @return 변환된 TimeLineResDto
     */
    public BroadcastAnalysisResDto.TimeLineResDto toTimeLineResDto(TimeLine timeLine) {
        return BroadcastAnalysisResDto.TimeLineResDto.builder()
                .content(timeLine.getContent())
                .startTime(timeLine.getStartTime().format(ANALYSIS_DATE_TIME_FORMATTER))
                .endTime(timeLine.getEndTime().format(ANALYSIS_DATE_TIME_FORMATTER))
                .build();
    }
}
