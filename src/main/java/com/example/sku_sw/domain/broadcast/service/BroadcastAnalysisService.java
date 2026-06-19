package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastAnalysisGeminiResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import com.example.sku_sw.domain.broadcast.entity.TimeLine;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.repository.BroadcastAnalysisRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.util.GeminiUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastAnalysisService {

    private static final DateTimeFormatter ANALYSIS_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BroadcastRepository broadcastRepository;
    private final BroadcastDialogueRepository broadcastDialogueRepository;
    private final BroadcastAnalysisRepository broadcastAnalysisRepository;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final GeminiUtil geminiUtil;
    private final ObjectMapper objectMapper;

    /**
     * Broadcast PK 기준으로 방송 분석을 시작하는 함수.
     *
     * @param broadcastStreamId 방송 PK
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analysisBroadcastDialogues(Long broadcastStreamId) {
        log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - START | streamId: {}", broadcastStreamId);

        Broadcast broadcast = broadcastRepository.findById(broadcastStreamId)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND));
        analysisBroadcastDialogues(broadcast.getStreamId());

        log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - END | streamId: {}", broadcastStreamId);
    }

    /**
     * streamId 기준으로 방송 분석을 실행하는 함수.
     *
     * @param broadcastStreamId 방송 streamId
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analysisBroadcastDialogues(String broadcastStreamId) {
        log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - START | streamId: {}", broadcastStreamId);

        // 1. 방송을 먼저 조회하고, 이미 분석된 방송이면 중복 처리를 막는다.
        Broadcast broadcast = broadcastRepository.findByStreamId(broadcastStreamId)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND));
        if (broadcastAnalysisRepository.existsByBroadcast(broadcast)) {
            log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - END | streamId: {}, action: already_exists", broadcastStreamId);
            return;
        }

        // 2. 분석 대상 대화를 시간순으로 읽어온다.
        List<BroadcastDialogue> dialogues = broadcastDialogueRepository.findByBroadcastOrderByCreatedAtAsc(broadcast);
        if (dialogues.isEmpty()) {
            log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - END | streamId: {}, action: no_dialogue", broadcastStreamId);
            return;
        }

        // 3. Gemini에 분석을 요청하고 응답 JSON을 DTO로 파싱한다.
        String prompt = createAnalysisPrompt(broadcast, dialogues);
        String response = geminiUtil.analyzeBroadcastDialogues(prompt).block();
        BroadcastAnalysisGeminiResDto analysisResponse = parseAnalysisResponse(response);

        // 4. summary는 Redis 0번 슬롯의 방송 요약을 그대로 사용한다.
        BroadcastInfoRedisDto summaryInfo = broadcastRedisUtil.getSummary(broadcastStreamId);

        // 5. 분석 결과와 Redis summary를 합쳐 저장한다.
        BroadcastAnalysis broadcastAnalysis = BroadcastAnalysis.create(
                broadcast,
                truncate(analysisResponse.majorContent(), 100),
                truncate(analysisResponse.majorMoodWithViewers(), 200),
                summaryInfo == null ? "" : truncate(summaryInfo.content(), 500),
                truncate(analysisResponse.totalAnalysis(), 500)
        );
        addCatchPhrases(broadcastAnalysis, analysisResponse.catchPhrases());
        addTimeLines(broadcastAnalysis, analysisResponse.timeLines());

        broadcastAnalysisRepository.save(broadcastAnalysis);

        log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - END | streamId: {}, dialogueSize: {}, catchPhraseSize: {}, timeLineSize: {}",
                broadcastStreamId,
                dialogues.size(),
                broadcastAnalysis.getCatchPhrases().size(),
                broadcastAnalysis.getTimeLines().size());
    }

    /**
     * Gemini 분석 프롬프트를 생성한다.
     *
     * @param broadcast 방송 정보
     * @param dialogues 방송 대화 목록
     * @return Gemini 요청 프롬프트
     */
    private String createAnalysisPrompt(Broadcast broadcast, List<BroadcastDialogue> dialogues) {
        log.info("[BroadcastAnalysisService] createAnalysisPrompt() - START | streamId: {}, dialogueSize: {}",
                broadcast.getStreamId(), dialogues.size());

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("당신은 스트리머 방송 분석가입니다.\n")
                .append("아래 방송 대화를 바탕으로 방송의 주요 콘텐츠, 시청자와의 분위기, 반복적으로 사용된 유행어와 타임라인을 JSON 형식으로 작성하세요.\n")
                .append("반드시 JSON만 반환하세요. Markdown, 설명문, 코드블록은 사용하지 마세요.\n\n")
                .append("[출력 필드]\n")
                .append("1. majorContent\n")
                .append("- 해당 방송에서 가장 중심이 된 콘텐츠를 한두 문장으로 요약하세요.\n")
                .append("- 반복적으로 나온 게임, 토크, 밈, 사건 등을 중심으로 작성하세요. 100자 이하로 작성하세요.\n")
                .append("2. majorMoodWithViewers\n")
                .append("- 스트리머와 시청자 사이의 전체적인 분위기와 상호작용 양상을 작성하세요.\n")
                .append("- 친근함, 텐션, 장난, 진지함, 반응 속도 같은 요소를 중심으로 200자 이하로 작성하세요.\n")
                .append("3. totalAnalysis\n")
                .append("- 방송 전체를 종합해 최종 분석을 작성하세요.\n")
                .append("- 방송의 특징, 강점, 시청자 반응, 분위기 전환 등을 포함해 500자 이하로 작성하세요.\n")
                .append("4. catchPhrases\n")
                .append("- 방송에서 반복적으로 사용된 유행어나 키워드를 객체 배열로 작성하세요.\n")
                .append("- 각 항목은 content, subject, situationAnalysis 필드를 가져야 합니다.\n")
                .append("- subject는 반드시 STREAMER 또는 VIEWER 중 하나여야 합니다.\n")
                .append("- situationAnalysis는 해당 표현이 어떤 상황에서 왜 반복되었는지 1~2문장으로 설명하고 200자 이하로 작성하세요.\n")
                .append("- 명확한 유행어가 없으면 빈 배열을 반환하세요.\n")
                .append("5. timeLines\n")
                .append("- 방송 중 중요한 장면이나 분위기 변화가 있었던 구간을 시간 순으로 작성하세요.\n")
                .append("- 각 항목은 content, startTime, endTime 필드를 가져야 하며 시간은 yyyy-MM-dd HH:mm:ss 형식으로 작성하세요.\n\n")
                .append("[반환 JSON 형식]\n")
                .append("{\n")
                .append("  \"majorContent\": \"100자 이하 문자열\",\n")
                .append("  \"majorMoodWithViewers\": \"200자 이하 문자열\",\n")
                .append("  \"totalAnalysis\": \"500자 이하 문자열\",\n")
                .append("  \"catchPhrases\": [\n")
                .append("    {\n")
                .append("      \"content\": \"유행어 또는 키워드\",\n")
                .append("      \"subject\": \"STREAMER 또는 VIEWER\",\n")
                .append("      \"situationAnalysis\": \"발생 상황 설명\"\n")
                .append("    }\n")
                .append("  ],\n")
                .append("  \"timeLines\": [\n")
                .append("    {\n")
                .append("      \"content\": \"해당 구간 방송 내용\",\n")
                .append("      \"startTime\": \"yyyy-MM-dd HH:mm:ss\",\n")
                .append("      \"endTime\": \"yyyy-MM-dd HH:mm:ss\"\n")
                .append("    }\n")
                .append("  ]\n")
                .append("}\n\n")
                .append("[방송 정보]\n")
                .append("- streamId: ").append(broadcast.getStreamId()).append("\n")
                .append("- startedAt: ").append(formatDateTime(broadcast.getStartedAt())).append("\n")
                .append("- terminatedAt: ").append(formatDateTime(broadcast.getTerminatedAt())).append("\n\n")
                .append("[대화 기록]\n");

        dialogues.forEach(dialogue -> promptBuilder
                .append("[").append(formatDateTime(dialogue.getCreatedAt())).append("] ")
                .append(dialogue.getSubject().name()).append(": ")
                .append(dialogue.getContent()).append("\n"));

        String prompt = promptBuilder.toString();
        log.info("[BroadcastAnalysisService] createAnalysisPrompt() - END | streamId: {}, promptLength: {}",
                broadcast.getStreamId(), prompt.length());
        return prompt;
    }

    /**
     * Gemini 분석 JSON 문자열을 DTO로 변환한다.
     *
     * @param response Gemini 응답 문자열
     * @return 분석 응답 DTO
     */
    private BroadcastAnalysisGeminiResDto parseAnalysisResponse(String response) {
        log.info("[BroadcastAnalysisService] parseAnalysisResponse() - START | responseLength: {}",
                response != null ? response.length() : 0);

        String json = extractJson(response);
        try {
            BroadcastAnalysisGeminiResDto result = objectMapper.readValue(json, BroadcastAnalysisGeminiResDto.class);
            log.info("[BroadcastAnalysisService] parseAnalysisResponse() - END");
            return result;
        } catch (JsonProcessingException e) {
            log.error("[BroadcastAnalysisService] parseAnalysisResponse() - Failed | response: {}", response, e);
            throw new IllegalStateException("방송 분석 응답 파싱에 실패했습니다.", e);
        }
    }

    /**
     * Gemini catchPhrase DTO 목록을 Entity에 추가한다.
     *
     * @param broadcastAnalysis 방송 분석 Entity
     * @param catchPhrases 유행어 DTO 목록
     */
    private void addCatchPhrases(BroadcastAnalysis broadcastAnalysis, List<BroadcastAnalysisGeminiResDto.CatchPhraseDto> catchPhrases) {
        log.info("[BroadcastAnalysisService] addCatchPhrases() - START | catchPhraseSize: {}",
                catchPhrases != null ? catchPhrases.size() : 0);

        if (catchPhrases != null) {
            catchPhrases.stream()
                    .map(this::toCatchPhraseEntity)
                    .filter(catchPhrase -> catchPhrase != null)
                    .forEach(broadcastAnalysis::addCatchPhrase);
        }

        log.info("[BroadcastAnalysisService] addCatchPhrases() - END | catchPhraseSize: {}",
                broadcastAnalysis.getCatchPhrases().size());
    }

    /**
     * Gemini catchPhrase DTO를 Entity로 변환한다.
     *
     * @param catchPhraseDto 유행어 DTO
     * @return 변환된 Entity
     */
    private CatchPhrase toCatchPhraseEntity(BroadcastAnalysisGeminiResDto.CatchPhraseDto catchPhraseDto) {
        if (catchPhraseDto == null) {
            return null;
        }

        String content = normalizeText(catchPhraseDto.content());
        String situationAnalysis = normalizeText(catchPhraseDto.situationAnalysis());
        DialogueSubject subject = parseCatchPhraseSubject(catchPhraseDto.subject());
        if (content == null || situationAnalysis == null || subject == null) {
            return null;
        }

        return CatchPhrase.create(content, subject, situationAnalysis);
    }

    /**
     * Gemini가 반환한 subject 값을 Entity enum으로 변환한다.
     *
     * @param subject subject 문자열
     * @return 지원 가능한 주체만 반환
     */
    private DialogueSubject parseCatchPhraseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }

        try {
            DialogueSubject parsedSubject = DialogueSubject.valueOf(subject.trim().toUpperCase(Locale.ROOT));
            if (parsedSubject == DialogueSubject.STREAMER || parsedSubject == DialogueSubject.VIEWER) {
                return parsedSubject;
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Gemini 분석 타임라인 DTO 목록을 Entity에 추가한다.
     *
     * @param broadcastAnalysis 방송 분석 Entity
     * @param timeLines 타임라인 DTO 목록
     */
    private void addTimeLines(BroadcastAnalysis broadcastAnalysis, List<BroadcastAnalysisGeminiResDto.TimeLineDto> timeLines) {
        log.info("[BroadcastAnalysisService] addTimeLines() - START | timeLineSize: {}",
                timeLines != null ? timeLines.size() : 0);

        if (timeLines != null) {
            timeLines.stream()
                    .filter(timeLine -> timeLine != null && timeLine.content() != null && !timeLine.content().isBlank())
                    .map(timeLine -> TimeLine.create(
                            truncate(timeLine.content(), 255),
                            parseDateTime(timeLine.startTime()),
                            parseDateTime(timeLine.endTime())
                    ))
                    .forEach(broadcastAnalysis::addTimeLine);
        }

        log.info("[BroadcastAnalysisService] addTimeLines() - END | timeLineSize: {}",
                broadcastAnalysis.getTimeLines().size());
    }

    /**
     * Gemini 응답에서 JSON 객체 영역만 추출한다.
     *
     * @param response Gemini 응답
     * @return JSON 문자열
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("방송 분석 응답이 비어있습니다.");
        }

        String trimmed = response.trim();
        int startIndex = trimmed.indexOf('{');
        int endIndex = trimmed.lastIndexOf('}');
        if (startIndex < 0 || endIndex < startIndex) {
            throw new IllegalStateException("방송 분석 응답에서 JSON 객체를 찾을 수 없습니다.");
        }
        return trimmed.substring(startIndex, endIndex + 1);
    }

    /**
     * LocalDateTime을 분석용 문자열 형식으로 변환한다.
     *
     * @param dateTime 변환할 시간
     * @return yyyy-MM-dd HH:mm:ss 문자열
     */
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(ANALYSIS_TIME_FORMATTER);
    }

    /**
     * 문자열 시간을 LocalDateTime으로 변환한다.
     *
     * @param dateTime yyyy-MM-dd HH:mm:ss 형식 문자열
     * @return LocalDateTime
     */
    private LocalDateTime parseDateTime(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) {
            throw new IllegalStateException("타임라인 시간이 비어있습니다.");
        }
        return LocalDateTime.parse(dateTime.trim(), ANALYSIS_TIME_FORMATTER);
    }

    /**
     * 문자열을 최대 길이에 맞춰 자른다.
     *
     * @param value 원본 문자열
     * @param maxLength 최대 길이
     * @return 잘린 문자열
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    /**
     * 공백 문자열을 null로 정규화한다.
     *
     * @param value 원본 문자열
     * @return 정규화된 문자열
     */
    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
