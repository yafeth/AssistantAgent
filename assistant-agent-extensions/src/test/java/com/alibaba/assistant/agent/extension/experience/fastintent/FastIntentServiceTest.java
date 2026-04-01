package com.alibaba.assistant.agent.extension.experience.fastintent;

import com.alibaba.assistant.agent.extension.experience.model.FastIntentConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastIntentServiceTest {

    private final FastIntentService service = new FastIntentService(List.of());

    @Test
    void messagePrefixShouldTrimByDefault() {
        FastIntentConfig.Condition condition = new FastIntentConfig.Condition();
        condition.setType("message_prefix");
        condition.setValue("帮我诊断这个缺陷");

        FastIntentConfig.MatchExpression expression = new FastIntentConfig.MatchExpression();
        expression.setCondition(condition);

        FastIntentContext context = new FastIntentContext(
                "\n帮我诊断这个缺陷，缺陷ID：71410746",
                List.of(),
                Map.of(),
                null,
                null
        );

        assertTrue(service.matches(expression, context));
    }

    @Test
    void messagePrefixShouldRespectExplicitNoTrim() {
        FastIntentConfig.Condition condition = new FastIntentConfig.Condition();
        condition.setType("message_prefix");
        condition.setValue("帮我诊断这个缺陷");
        condition.setTrim(false);

        FastIntentConfig.MatchExpression expression = new FastIntentConfig.MatchExpression();
        expression.setCondition(condition);

        FastIntentContext context = new FastIntentContext(
                "\n帮我诊断这个缺陷，缺陷ID：71410746",
                List.of(),
                Map.of(),
                null,
                null
        );

        assertFalse(service.matches(expression, context));
    }
}
