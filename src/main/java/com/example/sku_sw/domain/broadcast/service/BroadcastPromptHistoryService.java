package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastPromptHistoryContext;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.repository.BroadcastAnalysisRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.repository.CatchPhraseRepository;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 방송 시작 프롬프트용 이전 방송 이력 컨텍스트를 구성하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastPromptHistoryService {

    private static final int RECENT_ANALYSIS_LIMIT = 3;
    private static final int ALL_CATCH_PHRASE_LIMIT = 10;

    private final BroadcastRepository broadcastRepository;
    private final BroadcastAnalysisRepository broadcastAnalysisRepository;
    private final CatchPhraseRepository catchPhraseRepository;

    /**
     * 현재 방송과 같은 사용자의 이전 방송 분석/유행어 컨텍스트를 생성하는 함수.
     *
     * @param broadcastStreamId 현재 방송 streamId
     * @return 프롬프트 이력 컨텍스트
     */
    @Transactional(readOnly = true)
    public BroadcastPromptHistoryContext buildPromptHistoryContext(String broadcastStreamId) {
        log.info("[BroadcastPromptHistoryService] buildPromptHistoryContext() - START | streamId: {}", broadcastStreamId);

        /*
            1. 현재 방송과 사용자 정보를 조회한다.
            - 같은 사용자의 이전 방송만 포함해야 하므로 현재 방송 기준 userId를 먼저 확보한다.
         */
        Broadcast currentBroadcast = broadcastRepository.findByStreamId(broadcastStreamId)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND));
        Long currentBroadcastId = currentBroadcast.getId();
        Long userId = currentBroadcast.getCharacter().getUser().getId();

        /*
            2. 최근 3개의 이전 방송 분석을 조회한다.
            - 현재 방송은 제외하고, 같은 사용자 소유 방송 중 분석이 완료된 항목만 최신순으로 가져온다.
         */
        List<BroadcastPromptHistoryContext.RecentBroadcastAnalysis> recentBroadcastAnalyses =
                broadcastAnalysisRepository.findRecentByUserIdAndBroadcastIdNot(
                                userId,
                                currentBroadcastId,
                                PageRequest.of(0, RECENT_ANALYSIS_LIMIT)
                        ).stream()
                        .map(this::toRecentBroadcastAnalysis)
                        .toList();

        /*
            3. 이전 방송 유행어를 조회하고 노출 정책을 적용한다.
            - 전체 개수가 10개 이하면 모두 포함한다.
            - 10개를 초과하면 서로 다른 방송에서 2번 이상 등장한 문구만 남긴다.
         */
        List<CatchPhrase> catchPhrases = catchPhraseRepository.findAllByUserIdAndBroadcastIdNotOrderByBroadcastStartedAtDesc(
                userId,
                currentBroadcastId
        );
        List<BroadcastPromptHistoryContext.HistoricalCatchPhrase> historicalCatchPhrases =
                selectHistoricalCatchPhrases(catchPhrases);

        log.info("[BroadcastPromptHistoryService] buildPromptHistoryContext() - END | streamId: {}, recentAnalysisCount: {}, catchPhraseCount: {}",
                broadcastStreamId, recentBroadcastAnalyses.size(), historicalCatchPhrases.size());
        return new BroadcastPromptHistoryContext(recentBroadcastAnalyses, historicalCatchPhrases);
    }

    /**
     * BroadcastAnalysis 엔티티를 프롬프트 이력 DTO로 변환하는 함수.
     *
     * @param broadcastAnalysis 방송 분석 엔티티
     * @return 이전 방송 분석 DTO
     */
    private BroadcastPromptHistoryContext.RecentBroadcastAnalysis toRecentBroadcastAnalysis(BroadcastAnalysis broadcastAnalysis) {
        Broadcast broadcast = broadcastAnalysis.getBroadcast();
        return new BroadcastPromptHistoryContext.RecentBroadcastAnalysis(
                broadcast.getId(),
                broadcast.getStreamId(),
                broadcast.getStartedAt(),
                broadcastAnalysis.getMajorContent(),
                broadcastAnalysis.getMajorMoodWithViewers(),
                broadcastAnalysis.getSummary(),
                broadcastAnalysis.getTotalAnalysis()
        );
    }

    /**
     * 유행어 노출 정책에 맞춰 프롬프트용 목록을 추출하는 함수.
     *
     * @param catchPhrases 이전 방송 유행어 목록
     * @return 프롬프트에 포함할 유행어 목록
     */
    private List<BroadcastPromptHistoryContext.HistoricalCatchPhrase> selectHistoricalCatchPhrases(List<CatchPhrase> catchPhrases) {
        if (catchPhrases == null || catchPhrases.isEmpty()) {
            return List.of();
        }
        if (catchPhrases.size() <= ALL_CATCH_PHRASE_LIMIT) {
            return catchPhrases.stream()
                    .map(catchPhrase -> toHistoricalCatchPhrase(catchPhrase, 1L))
                    .toList();
        }

        Map<String, List<CatchPhrase>> catchPhraseGroupMap = catchPhrases.stream()
                .filter(catchPhrase -> catchPhrase.getContent() != null && !catchPhrase.getContent().isBlank())
                .collect(Collectors.groupingBy(CatchPhrase::getContent));

        return catchPhraseGroupMap.entrySet().stream()
                .map(entry -> toDuplicateHistoricalCatchPhrase(entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(BroadcastPromptHistoryContext.HistoricalCatchPhrase::duplicateBroadcastCount).reversed()
                        .thenComparing(BroadcastPromptHistoryContext.HistoricalCatchPhrase::startedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(BroadcastPromptHistoryContext.HistoricalCatchPhrase::content))
                .toList();
    }

    /**
     * 중복 유행어 그룹에서 대표 항목을 선택하는 함수.
     *
     * @param catchPhrases 같은 문구를 가진 유행어 목록
     * @return 대표 유행어 DTO, 중복 조건 미충족 시 null
     */
    private BroadcastPromptHistoryContext.HistoricalCatchPhrase toDuplicateHistoricalCatchPhrase(List<CatchPhrase> catchPhrases) {
        long duplicateBroadcastCount = catchPhrases.stream()
                .map(catchPhrase -> catchPhrase.getBroadcastAnalysis().getBroadcast().getId())
                .distinct()
                .count();
        if (duplicateBroadcastCount < 2) {
            return null;
        }

        CatchPhrase latestCatchPhrase = catchPhrases.stream()
                .max(Comparator.comparing(
                        catchPhrase -> catchPhrase.getBroadcastAnalysis().getBroadcast().getStartedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .orElseThrow();

        return toHistoricalCatchPhrase(latestCatchPhrase, duplicateBroadcastCount);
    }

    /**
     * CatchPhrase 엔티티를 프롬프트 이력 DTO로 변환하는 함수.
     *
     * @param catchPhrase 유행어 엔티티
     * @param duplicateBroadcastCount 중복 등장 방송 수
     * @return 이전 방송 유행어 DTO
     */
    private BroadcastPromptHistoryContext.HistoricalCatchPhrase toHistoricalCatchPhrase(
            CatchPhrase catchPhrase,
            long duplicateBroadcastCount
    ) {
        Broadcast broadcast = catchPhrase.getBroadcastAnalysis().getBroadcast();
        return new BroadcastPromptHistoryContext.HistoricalCatchPhrase(
                broadcast.getId(),
                broadcast.getStreamId(),
                catchPhrase.getContent(),
                catchPhrase.getSubject(),
                catchPhrase.getSituationAnalysis(),
                broadcast.getStartedAt(),
                duplicateBroadcastCount
        );
    }
}
