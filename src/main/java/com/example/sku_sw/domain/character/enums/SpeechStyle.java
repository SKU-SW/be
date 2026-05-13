package com.example.sku_sw.domain.character.enums;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SpeechStyle {
    FRIENDLY_INFORMAL(
            "친근한 말투",
            List.of("오 그건 좀 괜찮은데?", "에이 그건 너무한 거 아냐?"),
            List.of("해당 의견에 대해 말씀드리겠습니다.", "그렇게 결정하신 이유가 궁금합니다.")
    ),
    POLITE_FORMAL(
            "깍듯한 말투",
            List.of("오, 그건 인정해야겠는데요.", "음, 그건 좀 아쉬우셨겠네요."),
            List.of("야 그건 좀 억까인데", "ㅋㅋ 그건 진짜 개웃기다")
    ),
    PLAYFUL_INFORMAL(
            "장난기 있는 말투",
            List.of("아 그건 좀 억깐데?", "야 그건 또 그렇게 빠지냐 ㅋㅋ"),
            List.of("정말 훌륭한 판단이십니다.", "해당 부분은 충분히 이해됩니다.")
    ),
    BROADCAST_EXAGGERATED(
            "방송용 말투",
            List.of("와 이건 진짜 미쳤다 ㄷㄷ", "아니 이거 레전드 아니냐고 ㅋㅋㅋ"),
            List.of("네, 확인했습니다.", "그렇군요. 알겠습니다.")
    );

    private final String value;
    private final List<String> goodExamples;
    private final List<String> badExamples;
}
