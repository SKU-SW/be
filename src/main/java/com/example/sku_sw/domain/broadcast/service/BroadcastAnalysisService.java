package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastAnalysisGeminiResDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import com.example.sku_sw.domain.broadcast.entity.TimeLine;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.repository.BroadcastAnalysisRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastAnalysisService {

    private static final DateTimeFormatter ANALYSIS_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BroadcastRepository broadcastRepository;
    private final BroadcastDialogueRepository broadcastDialogueRepository;
    private final BroadcastAnalysisRepository broadcastAnalysisRepository;
    private final GeminiUtil geminiUtil;
    private final ObjectMapper objectMapper;

    /**
     * 방송 대화 기록을 기반으로 방송 분석 데이터를 생성하는 함수
     * - 숫자형 streamId 요청이 들어온 경우 문자열 streamId 분석 로직으로 위임한다.
     * @param broadcastStreamId : 방송 스트림 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analysisBroadcastDialogues(Long broadcastStreamId) {
        log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - START | streamId: {}", broadcastStreamId);

        /*
            1. 방송 ID 기반 조회
            - Long 타입 요청은 Broadcast PK 기준으로 조회한 뒤 실제 streamId 분석 로직으로 위임한다.
         */
        Broadcast broadcast = broadcastRepository.findById(broadcastStreamId)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND));
        analysisBroadcastDialogues(broadcast.getStreamId());

        log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - END | streamId: {}", broadcastStreamId);
    }

    /**
     * 방송 대화 기록을 기반으로 방송 분석 데이터를 생성하는 함수
     * - streamId로 방송을 조회하고, 연결된 BroadcastDialogue 전체를 Gemini 분석 모델에 전달한다.
     * - Gemini JSON 응답을 BroadcastAnalysis, CatchPhrase, TimeLine Entity로 변환해 저장한다.
     * @param broadcastStreamId : 방송 스트림 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analysisBroadcastDialogues(String broadcastStreamId) {
        log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - START | streamId: {}", broadcastStreamId);

        /*
            1. 방송 조회 및 중복 분석 방지
            - 이미 분석 데이터가 존재하면 중복 저장하지 않는다.
         */
        Broadcast broadcast = broadcastRepository.findByStreamId(broadcastStreamId)
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND));
        if (broadcastAnalysisRepository.existsByBroadcast(broadcast)) {
            log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - END | streamId: {}, action: already_exists", broadcastStreamId);
            return;
        }

        /*
            2. 분석 대상 대화 조회
            - 방송 종료 직전 Redis 잔여 대화가 저장된 DB 데이터를 생성 시간순으로 조회한다.
         */
        List<BroadcastDialogue> dialogues = broadcastDialogueRepository.findByBroadcastOrderByCreatedAtAsc(broadcast);
        if (dialogues.isEmpty()) {
            log.info("[BroadcastAnalysisService] analysisBroadcastDialogues() - END | streamId: {}, action: no_dialogue", broadcastStreamId);
            return;
        }

        /*
            3. Gemini 분석 요청 및 응답 파싱
            - 필드별 의미와 작성 기준이 포함된 프롬프트를 생성하고 JSON 응답을 DTO로 변환한다.
         */
        String prompt = createAnalysisPrompt(broadcast, dialogues);
        String response = geminiUtil.analyzeBroadcastDialogues(prompt).block();
        BroadcastAnalysisGeminiResDto analysisResponse = parseAnalysisResponse(response);

        /*
            4. 분석 Entity 저장
            - BroadcastAnalysis를 루트로 저장하고 cascade로 CatchPhrase/TimeLine을 함께 저장한다.
         */
        BroadcastAnalysis broadcastAnalysis = BroadcastAnalysis.create(
                broadcast,
                truncate(analysisResponse.majorContent(), 100),
                truncate(analysisResponse.majorMoodWithViewers(), 200),
                truncate(analysisResponse.summary(), 500),
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
     * 방송 분석 프롬프트를 생성하는 함수
     * - 시스템 프롬프트에 각 응답 필드의 의미와 작성 기준을 포함한다.
     * - 대화 기록은 시간, 주체, 내용 형식으로 제공한다.
     * @param broadcast : 분석 대상 방송
     * @param dialogues : 분석 대상 방송 대화 목록
     * @return : Gemini 분석 프롬프트
     */
    private String createAnalysisPrompt(Broadcast broadcast, List<BroadcastDialogue> dialogues) {
        log.info("[BroadcastAnalysisService] createAnalysisPrompt() - START | streamId: {}, dialogueSize: {}",
                broadcast.getStreamId(), dialogues.size());

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("당신은 인터넷 방송 대화 데이터를 분석하는 방송 분석 전문가입니다.\n")
                .append("아래 방송 대화 기록을 기반으로 방송의 주요 컨텐츠, 시청자와의 분위기, 당일 방송 요약, 종합 분석, 유행어, 타임라인을 JSON 형식으로 작성하세요.\n")
                .append("반드시 JSON만 반환하세요. Markdown, 설명문, 코드블록은 반환하지 마세요.\n\n")
                .append("[필드별 의미와 작성 기준]\n")
                .append("1. majorContent\n")
                .append("- 의미: 해당 방송에서 가장 중심이 되었던 컨텐츠, 주제, 활동에 대한 설명입니다.\n")
                .append("- 기준: 단순 키워드 나열이 아니라 게임, 소통, 일상 토크, 합방 등 방송의 주된 흐름을 한 문장으로 설명하세요. 부수적인 짧은 대화는 주요 컨텐츠로 판단하지 마세요. 100자 이하로 작성하세요.\n")
                .append("2. majorMoodWithViewers\n")
                .append("- 의미: 스트리머와 시청자 사이의 전반적인 분위기와 상호작용 방식에 대한 분석입니다.\n")
                .append("- 기준: 친근함, 장난, 티키타카, 갈등, 놀림, 응원, 차분함, 혼란스러움 등 반복적으로 나타나는 분위기를 기준으로 작성하세요. 일회성 발언이 아닌 전체 상호작용 흐름을 분석하세요. 200자 이하로 작성하세요.\n")
                .append("3. summary\n")
                .append("- 의미: 당일 방송 전체 내용을 압축한 요약입니다.\n")
                .append("- 기준: 방송 초반, 중반, 후반의 주요 흐름이 드러나도록 작성하세요. 세부 채팅 하나하나가 아니라 의미 있는 사건과 흐름 중심으로, 대화 기록에 근거해 500자 이하로 작성하세요.\n")
                .append("4. totalAnalysis\n")
                .append("- 의미: 방송 전체에 대한 최종 분석과 총평입니다.\n")
                .append("- 기준: 방송의 특징, 강점, 시청자 반응, 분위기, 컨텐츠 전환 등을 종합적으로 평가하세요. 단순 요약이 아니라 분석 결과를 작성하고, 기록에 없는 내용은 추측하지 마세요. 500자 이하로 작성하세요.\n")
                .append("5. catchPhrases\n")
                .append("- 의미: 해당 방송에서 반복적으로 사용된 유행어, 밈, 대표 문구 목록입니다.\n")
                .append("- 기준: 사람들이 다 같이, 반복적으로, 높은 빈도로 사용한 단어 또는 문장만 포함하세요. 특정 상황에서 반복 등장한 표현만 포함하고, 단순 감탄사/일반 인사/흔한 채팅 표현/한두 명만 사용한 표현은 제외하세요. 명확한 유행어가 없으면 빈 배열을 반환하세요.\n")
                .append("6. timeLines\n")
                .append("- 의미: 방송 중 주요 컨텐츠나 분위기가 바뀐 구간을 시간순으로 나눈 목록입니다.\n")
                .append("- 기준: 주요 컨텐츠 내용이 확연히 바뀌었을 때만 새 항목을 만드세요. 사소한 대화 주제 변화마다 나누지 마세요. startTime/endTime은 실제 대화 기록 시간 범위 안에서 yyyy-MM-dd HH:mm:ss 형식으로 작성하세요.\n\n")
                .append("[반환 JSON 형식]\n")
                .append("{\n")
                .append("  \"majorContent\": \"100자 이하 문자열\",\n")
                .append("  \"majorMoodWithViewers\": \"200자 이하 문자열\",\n")
                .append("  \"summary\": \"500자 이하 문자열\",\n")
                .append("  \"totalAnalysis\": \"500자 이하 문자열\",\n")
                .append("  \"catchPhrases\": [\"반복적으로 사용된 유행어\"],\n")
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
     * Gemini 분석 응답 JSON 문자열을 DTO로 변환하는 함수
     * - Markdown 코드블록이 포함되어도 JSON 부분만 추출해 파싱한다.
     * @param response : Gemini 분석 응답 문자열
     * @return : 분석 응답 DTO
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
     * BroadcastAnalysis에 CatchPhrase 목록을 추가하는 함수
     * @param broadcastAnalysis : 캐치프레이즈를 추가할 방송 분석 Entity
     * @param catchPhrases : 캐치프레이즈 문자열 목록
     */
    private void addCatchPhrases(BroadcastAnalysis broadcastAnalysis, List<String> catchPhrases) {
        log.info("[BroadcastAnalysisService] addCatchPhrases() - START | catchPhraseSize: {}",
                catchPhrases != null ? catchPhrases.size() : 0);

        if (catchPhrases != null) {
            catchPhrases.stream()
                    .filter(content -> content != null && !content.isBlank())
                    .map(content -> CatchPhrase.create(content.trim()))
                    .forEach(broadcastAnalysis::addCatchPhrase);
        }

        log.info("[BroadcastAnalysisService] addCatchPhrases() - END | catchPhraseSize: {}",
                broadcastAnalysis.getCatchPhrases().size());
    }

    /**
     * BroadcastAnalysis에 TimeLine 목록을 추가하는 함수
     * @param broadcastAnalysis : 타임라인을 추가할 방송 분석 Entity
     * @param timeLines : Gemini 분석 응답 타임라인 DTO 목록
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
     * Gemini 응답 문자열에서 JSON 객체 영역을 추출하는 함수
     * @param response : Gemini 원본 응답
     * @return : JSON 문자열
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
     * LocalDateTime을 분석 프롬프트 시간 문자열로 변환하는 함수
     * @param dateTime : 변환할 시간
     * @return : yyyy-MM-dd HH:mm:ss 형식 문자열
     */
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(ANALYSIS_TIME_FORMATTER);
    }

    /**
     * 분석 응답 시간 문자열을 LocalDateTime으로 변환하는 함수
     * @param dateTime : yyyy-MM-dd HH:mm:ss 형식 문자열
     * @return : LocalDateTime
     */
    private LocalDateTime parseDateTime(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) {
            throw new IllegalStateException("타임라인 시간이 비어있습니다.");
        }
        return LocalDateTime.parse(dateTime.trim(), ANALYSIS_TIME_FORMATTER);
    }

    /**
     * 문자열을 최대 길이에 맞춰 자르는 함수
     * @param value : 대상 문자열
     * @param maxLength : 최대 길이
     * @return : 최대 길이 이하 문자열
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
}
