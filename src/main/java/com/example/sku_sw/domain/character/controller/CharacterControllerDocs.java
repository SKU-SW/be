package com.example.sku_sw.domain.character.controller;

import com.example.sku_sw.domain.character.dto.CharacterCreateReqDto;
import com.example.sku_sw.domain.character.dto.CharacterDetailResDto;
import com.example.sku_sw.domain.character.dto.CharacterListResDto;
import com.example.sku_sw.domain.character.dto.CharacterSelectReqDto;
import com.example.sku_sw.domain.character.dto.CharacterSelectResDto;
import com.example.sku_sw.domain.character.dto.CharacterSettingsResDto;
import com.example.sku_sw.domain.character.dto.CharacterUpdateReqDto;
import com.example.sku_sw.global.response.GlobalResponse;
import com.example.sku_sw.global.response.CursorSliceResponse;
import com.example.sku_sw.global.response.SliceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Character", description = "캐릭터 관련 API")
@RequestMapping("/api/v1/characters")
public interface CharacterControllerDocs {

    @Operation(summary = "AI 캐릭터 생성", description = """
            사용자가 설정한 정보를 바탕으로 새로운 AI 캐릭터를 생성합니다.
            
            [ 입력 데이터 ]
            1. `characterName`: 캐릭터 이름
            2. `triggerWords`: 호출어 리스트 -> **json에서 호출어를 []로 리스트로 감싸서 호출해야합니다**
            3. `gender`: 성별 (MALE | FEMALE)
            4. `voiceTypeId`: 캐릭터 목소리 PK (현재 선택된 Gender와 동일한 성별의 목소리 PK를 선택해야합니다)
            5. `characterImageId`: 캐릭터 이미지 PK (현재 선택된 Gender와 동일한 성별의 목소리 PK를 선택해야합니다)
            6. `characterPersona`: 캐릭터 페르소나
                1) `presetType`: 캐틱터 프리셋 타입
                - FRIENDLY_CHATTER | HIGH_TENSION | PLAYFUL_TEASER | PROFESSIONAL_MANAGER | ROLEPLAY_EXPERT | CUSTOM
                2) `speechStyle`: 캐릭터 말투
                - FRIENDLY_INFORMAL | POLITE_FORMAL | PLAYFUL_INFORMAL | BROADCAST_EXAGGERATED
                3) `personality`: 캐릭터 성격
                - ACTIVE | CALM | HUMOROUS | SERIOUS
            
            [ 캐릭터 페르소나 상세 설명 ]
                (1) `FRIENDLY_CHATTER` (저스트 채팅 / 소통 특화)
                    가장 무난하고 편안하게 오디오를 채워주는 든든한 국밥 같은 포지션입니다.
                    - **말투:** `FRIENDLY_INFORMAL` (친근한 반말)
                    - **성격:** `HUMOROUS` (유머러스)
                    - **특징:** 스트리머의 말에 적당한 딴지도 걸고, 밈(Meme)도 자연스럽게 소화하며 티키타카를 이어갑니다.
            
                (2) `HIGH_TENSION` (리액션 / 하이라이트 특화)
                    텐션이 떨어질 때 방송 분위기를 멱살 잡고 끌어올려 주는 포지션입니다.
                    - **말투:** `BROADCAST_EXAGGERATED` (방송용 과장체)
                    - **성격:** `ACTIVE` (활발함)
                    - **특징:** 리액션이 크고 감정 표현이 풍부합니다. 게임에서 이겼을 때 극도로 환호하거나, 엄청난 리액션을 보여줍니다.
            
                (3) `PLAYFUL_TEASER` (게임 특화 / 훈수 및 티배깅)
                    시청자들을 대신해서 스트리머를 긁거나(Teasing) 팩트 폭력을 날리는 얄미운 포지션입니다.
                    - **말투:** `PLAYFUL_INFORMAL` (장난기 섞인 반말)
                    - **성격:** `HUMOROUS` (유머러스)
                    - **특징:** 스트리머가 게임에서 실수했을 때 놓치지 않고 놀리며 시청자들의 웃음을 유발합니다.
            
                (4) `PROFESSIONAL_MANAGER` (정보 전달 / 차분한 진행)
                    선 넘는 채팅을 진정시키거나, 게임 스토리를 조용히 요약해 주는 비서 같은 포지션입니다.
                    - **말투:** `POLITE_FORMAL` (깍듯한 존댓말)
                    - **성격:** `CALM` (차분함)
                    - **특징:** 흥분하지 않고 스트리머를 깍듯하게 보좌하며, 정보 전달이나 공지사항을 안내할 때 유용합니다.
            
                (5) `ROLEPLAY_EXPERT` (스토리 게임 / 롤플레잉 특화)
                    게임 속 캐릭터나 세계관에 완전히 동화되어 진지하게 상황에 임하는 포지션입니다.
                    - **말투:** `POLITE_FORMAL` (깍듯한 존댓말)
                    - **성격:** `SERIOUS` (진지함)
                    - **특징:** 농담보다는 상황의 심각성이나 분위기에 집중하여 몰입감을 높여줍니다.
            
                (6) `CUSTOM` (사용자 커스텀 페르소나)
                    사용자가 임의로 설정한 성격, 말투로 결정된 페르소나이다
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "캐릭터 생성 완료", content = @Content(schema = @Schema(implementation = CharacterDetailResDto.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    ResponseEntity<GlobalResponse<Void>> createCharacter(
            @Valid @RequestBody CharacterCreateReqDto characterCreateReqDto
    );

    @Operation(summary = "AI 캐릭터 리스트 조회", description = """
            현재 사용자가 선택한 AI 캐릭터의 정보와 사용자가 생성한 AI 캐릭터 전체 목록을 조회한다.

            - page = 1 인 경우, 사용자가 선택한 캐릭터가 리스트 최상단에 표시된다.
            - page = 2 이상인 경우, 기존과 동일한 pagination 동작을 따른다.
            - 선택한 캐릭터가 없으면 기존과 동일하게 조회된다.
            
            [Query String]
            - `page`: 요청할 페이지 번호 (1 ~ n)
            - `size`: 한 페이지당 조회할 데이터 개수
            
            [Request Body]
            - `content`: 실제 의미 있는 데이터
                [각 캐릭터별 정보 블록]
                - `characterId`: 생성한 AI 캐릭터 PK
                - `characterName`: 설정할 AI 캐릭터 이름
                - `gender`: AI 캐릭터 성별 (`MALE` | `FEMALE`)
                - `characterImageUrl`: 캐릭터 외형 이미지 URL
                - `triggerWords`: AI 캐릭터를 호출할 호출어 리스트 (최대 3개)
                - `presetType`: AI 캐릭터 프리셋 타입
                (`FRIENDLY_CHATTER` | `HIGH_TENSION` | `PLAYFUL_TEASER` | `PROFESSIONAL_MANAGER` |  `ROLEPLAY_EXPERT` | `CUSTOM`)
                - `isSelected`: 현재 선택된 AI 캐릭터인지 여부
                (`true` | `false`)
            - `currentPage`: 현재 페이지 번호
            - `pageSize`: 한 페이지당 데이터 개수
            - `hasNext`: 다음 페이지 존재 여부
            
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "캐릭터 리스트 조회 완료", content = @Content(schema = @Schema(implementation = SliceResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    ResponseEntity<GlobalResponse<SliceResponse<CharacterListResDto>>> getCharacterList(
            @Parameter(description = "요청할 페이지 번호 (1 ~ n)")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "한 페이지당 조회할 데이터 개수")
            @RequestParam(defaultValue = "10") int size
    );

    @Operation(summary = "특정 AI 캐릭터 상세 조회", description = """
            특정 AI 캐릭터의 상세 정보를 조회한다.
            
            [ Path Variable ]
            - ```characterId```: 조회할 AI 캐릭터의 고유 PK
            
            [ Response Body ]
            - `characterId`: AI 캐릭터 고유 PK
            - `characterName`: AI 캐릭터 이름
            - `triggerWords`: AI 캐릭터 호출어 리스트 (최대 3개)
            - `gender`: AI 캐릭터 성별 (`MALE` | `FEMALE`)
            - `voiceTypeId`: 목소리 종류 PK
            - `characterImageUrl`: 캐릭터 외형 이미지 URL
            - `characterPersona`: AI 캐릭터 페르소나 정보
                - `presetType`: AI 캐릭터 프리셋 타입
                (`FRIENDLY_CHATTER`|`HIGH_TENSION`|`PLAYFUL_TEASER`|`PROFESSIONAL_MANAGER`|`ROLEPLAY_EXPERT`|`CUSTOM`)
                - `speechStyle`: AI 캐릭터 말투
                (`FRIENDLY_INFORMAL`|`POLITE_FORMAL`|`PLAYFUL_INFORMAL`|`BROADCAST_EXAGGERATED`)
                - `personality`: AI 캐릭터 성격
                (`ACTIVE`|`CALM`|`HUMOROUS`|SERIOUS)
            - `isSelected`: 현재 선택된 캐릭터 여부
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "캐릭터 상세 조회 완료", content = @Content(schema = @Schema(implementation = CharacterDetailResDto.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{characterId}")
    ResponseEntity<GlobalResponse<CharacterDetailResDto>> getCharacterDetail(
            @PathVariable Long characterId
    );

    @Operation(summary = "캐릭터 수정 페이지용 설정 옵션 조회", description = """
            AI 캐릭터 생성 및 수정 페이지에서 각 블록(목소리, 이미지, 페르소나 등)에 필요한 선택 가능한 설정 옵션 정보를 조회한다.
            
            [ Response Body ]
            - `voiceTypes`: 선택 가능한 목소리 종류 목록
                [각 목소리별 정보]
                - `voiceTypeId`: 목소리 종류 PK
                - `label`: 목소리 표시 이름 ( 노인 | 중년 | 청년 | 청소년 )
                - `gender`: 성별 (`MALE` | `FEMALE`)
                - `ageGroup`: 목소리 연령대 코드
                - `testUrl`: 목소리 예시 url
            - `characterImages`: 선택 가능한 캐릭터 이미지 목록
                [각 이미지별 정보]
                - `imageId`: 이미지 PK
                - `gender`: 성별 (`MALE`|`FEMALE`)
                - `name`: 이미지 캐릭터 이름
                - `imageUrl`: 이미지 URL
            - `presetTypes`: 선택 가능한 프리셋 타입 목록
                `FRIENDLY_CHATTER`|`HIGH_TENSION`|`PLAYFUL_TEASER`|`PROFESSIONAL_MANAGER`|`ROLEPLAY_EXPERT`|`CUSTOM`
            - `speechStyles`: 선택 가능한 말투 목록
                `FRIENDLY_INFORMAL`|`POLITE_FORMAL`|`PLAYFUL_INFORMAL`|`BROADCAST_EXAGGERATED`
            - `personalities`: 선택 가능한 성격 목록
                `ACTIVE`|`CALM`|`HUMOROUS`|SERIOUS
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "설정 옵션 조회 완료", content = @Content(schema = @Schema(implementation = CharacterSettingsResDto.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/settings")
    ResponseEntity<GlobalResponse<CharacterSettingsResDto>> getSettings();

    @Operation(summary = "특정 AI 캐릭터 정보 수정", description = """
            특정 AI 캐릭터의 정보를 수정한다. 요청 바디의 모든 필드를 전달하여 덮어쓴다.

            [Path Variable]
            - `characterId`: 수정할 AI 캐릭터의 PK
            
            [Request Body]
            - `characterName`: 설정할 AI 캐릭터 이름
            - `triggerWords`: AI 캐릭터를 호출할 호출어 리스트 (최대 3개) -> **json에서 호출어를 []로 리스트로 감싸서 호출해야합니다
            - `gender`: AI 캐릭터 성별 (`MALE` | `FEMALE`)
            - `voiceTypeId`: 목소리 종류 PK
            - `characterImageId`: 캐릭터 외형 이미지 PK
            - `characterPersona`: AI 캐릭터 페르소나 정보
                - `presetType`: AI 캐릭터 프리셋 타입
                `FRIENDLY_CHATTER` | `HIGH_TENSION` | `PLAYFUL_TEASER` | `PROFESSIONAL_MANAGER` |  `ROLEPLAY_EXPERT` | `CUSTOM`
                - `speechStyle`: AI 캐릭터 말투
                `FRIENDLY_INFORMAL` | `POLITE_FORMAL` | `PLAYFUL_INFORMAL` | `BROADCAST_EXAGGERATED`
                - `personality`: AI 캐릭터 성격
                `ACTIVE` | `CALM` | `HUMOROUS` | `SERIOUS`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "캐릭터 수정 완료", content = @Content(schema = @Schema(implementation = CharacterDetailResDto.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/{characterId}")
    ResponseEntity<GlobalResponse<Void>> updateCharacter(
            @PathVariable Long characterId,
            @Valid @RequestBody CharacterUpdateReqDto characterUpdateReqDto
    );

    @Operation(summary = "특정 AI 캐릭터 선택/선택해제", description = """
            특정 AI 캐릭터를 활성 캐릭터로 선택하거나 선택을 해제한다.
            한 번에 하나의 캐릭터만 선택될 수 있으며, 다른 캐릭터를 선택하면 기존 선택 캐릭터는 자동으로 선택 해제된다.
            이미 선택된 캐릭터를 다시 요청하면 선택이 해제된다.
            
            [Path Variable]
            - `characterId`: 선택/선택해제할 AI 캐릭터의 고유 PK
            
            [Request Body]
            - `isSelected`: 선택 여부 (true | false)
            
            [Response Body]
            - `selectedCharacterId`: 선택된 캐릭터 PK (없으면 null)
            - `deselectedCharacterId`: 선택 취소된 캐릭터 PK (없으면 null)
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "캐릭터 선택 완료", content = @Content(schema = @Schema(implementation = CharacterSelectResDto.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{characterId}")
    ResponseEntity<GlobalResponse<CharacterSelectResDto>> selectCharacter(
            @PathVariable Long characterId,
            @Valid @RequestBody CharacterSelectReqDto characterSelectReqDto
    );

    @Operation(summary = "특정 AI 캐릭터 삭제", description = """
            특정 AI 캐릭터를 삭제한다.
            
            [Path Variable]
            - `characterId`: 삭제할 AI 캐릭터 PK
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "캐릭터 삭제 완료", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{characterId}")
    ResponseEntity<GlobalResponse<Void>> deleteCharacter(
            @PathVariable Long characterId
    );
}
