package com.example.sku_sw.global.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import lombok.Builder;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.data.domain.Slice;

@Builder
@JsonPropertyOrder({"content", "size", "hasNext", "nextCursor"})
@Schema(title = "CursorSliceResponse Dto", description = "커서 기반 무한스크롤 응답 Record")
public record CursorSliceResponse<T>(
        @Schema(description = "조회된 데이터 리스트")
        List<T> content,

        @Schema(description = "요청한 조회 크기")
        int size,

        @Schema(description = "다음 데이터 존재 여부")
        boolean hasNext,

        @Schema(description = "다음 조회에 사용할 마지막 엔티티 PK")
        Long nextCursor
) {

    /**
     * Slice<Entity> -> mapper(Entity) -> Dto -> CursorSliceResponse<Dto>로 변환하는 함수.
     * @param slice Slice<Entity> 객체
     * @param mapper Slice에 들어있는 Entity를 Dto로 변환하는 mapper 함수
     * @param nextCursor 다음 조회에 사용할 마지막 엔티티 PK
     * @return 해당 Slice의 정보들이 담긴 CursorSliceResponse<Dto>
     * @param <E> Entity
     * @param <T> Dto
     */
    public static <E, T> CursorSliceResponse<T> of(Slice<E> slice, Function<E, T> mapper, Long nextCursor) {
        List<T> content = slice.getContent().stream().map(mapper).toList();

        return CursorSliceResponse.<T>builder()
                .content(content)
                .size(slice.getSize())
                .hasNext(slice.hasNext())
                .nextCursor(nextCursor)
                .build();
    }
}
