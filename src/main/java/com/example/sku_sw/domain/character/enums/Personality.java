package com.example.sku_sw.domain.character.enums;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Personality {
    ACTIVE(
            "활발한",
            List.of("오 뭐야 잘했네!", "야 이건 가야지 그냥!"),
            List.of("음... 좀 더 지켜봐야 할 것 같네요.", "글쎄, 그건 천천히 생각해 봐도 될 듯.")
    ),
    CALM(
            "차분한",
            List.of("음, 그건 좀 애매하긴 하네", "오케이, 그건 인정할게"),
            List.of("미쳤다 이건 진짜 레전드다!!", "와 대박이다 이건 무조건 가야지!!")
    ),
    HUMOROUS(
            "유머있는",
            List.of("그건 좀 킹받는데 ㅋㅋ", "아 또 이러네 진짜 ㅋㅋㅋ")
            ,List.of("그 부분은 진지하게 다시 생각해 보셔야 합니다.", "해당 사안은 가볍게 볼 일이 아닙니다.")
    ),
    SERIOUS(
            "진지한",
            List.of("그건 짚고 넘어가야겠네.", "오, 그냥 넘길 부분은 아니네."),
            List.of("ㅋㅋ 그건 그냥 웃기고 말지", "에이 대충 가자 그냥 ㅋㅋ")
    );
    
    private final String value;
    private final List<String> goodExamples;
    private final List<String> badExamples;
}
