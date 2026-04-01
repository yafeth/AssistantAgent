package com.alibaba.assistant.agent.extension.experience.fastintent;

import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.FastIntentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * FastIntentService - 条件匹配与命中选择
 */
public class FastIntentService {

    private static final Logger log = LoggerFactory.getLogger(FastIntentService.class);

    private final Map<String, FastIntentConditionMatcher> matcherByType;

    public FastIntentService(List<FastIntentConditionMatcher> matchers) {
        Map<String, FastIntentConditionMatcher> map = new HashMap<>();
        if (!CollectionUtils.isEmpty(matchers)) {
            for (FastIntentConditionMatcher matcher : matchers) {
                if (matcher != null && StringUtils.hasText(matcher.getType())) {
                    map.put(matcher.getType(), matcher);
                }
            }
        }
        // built-ins (if not overridden)
        map.putIfAbsent("message_prefix", new MessagePrefixMatcher());
        map.putIfAbsent("message_regex", new MessageRegexMatcher());
        map.putIfAbsent("metadata_exists", new MetadataExistsMatcher());
        map.putIfAbsent("metadata_equals", new MetadataEqualsMatcher());
        map.putIfAbsent("metadata_in", new MetadataInMatcher());
        map.putIfAbsent("state_equals", new StateEqualsMatcher());
        map.putIfAbsent("tool_arg_equals", new ToolArgEqualsMatcher());
        this.matcherByType = Collections.unmodifiableMap(map);
    }

    public Optional<Experience> selectBestMatch(List<Experience> candidates, FastIntentContext context) {
        if (CollectionUtils.isEmpty(candidates) || context == null) {
            return Optional.empty();
        }

        List<Experience> matched = new ArrayList<>();
        for (Experience exp : candidates) {
            FastIntentConfig cfg = exp != null ? exp.getFastIntentConfig() : null;
            if (cfg == null || !cfg.isEnabled()) {
                continue;
            }
            if (matches(cfg.getMatch(), context)) {
                matched.add(exp);
            }
        }

        if (matched.isEmpty()) {
            return Optional.empty();
        }

        matched.sort((e1, e2) -> {
            int p1 = Optional.ofNullable(e1.getFastIntentConfig()).map(FastIntentConfig::getPriority).orElse(0);
            int p2 = Optional.ofNullable(e2.getFastIntentConfig()).map(FastIntentConfig::getPriority).orElse(0);
            if (p1 != p2) {
                return Integer.compare(p2, p1);
            }
            // updatedAt desc
            if (e1.getUpdatedAt() != null && e2.getUpdatedAt() != null) {
                int t = e2.getUpdatedAt().compareTo(e1.getUpdatedAt());
                if (t != 0) {
                    return t;
                }
            }
            // id asc
            String id1 = e1.getId() != null ? e1.getId() : "";
            String id2 = e2.getId() != null ? e2.getId() : "";
            return id1.compareTo(id2);
        });

        Experience best = matched.get(0);
        log.info("FastIntentService#selectBestMatch - reason=found matched experience: id={}, type={}, title={}",
                best.getId(), best.getType(), best.getTitle());
        return Optional.of(best);
    }

    public boolean matches(FastIntentConfig.MatchExpression expr, FastIntentContext context) {
        if (expr == null) {
            return false;
        }

        // NOT
        if (expr.getNot() != null) {
            return !matches(expr.getNot(), context);
        }

        // AND
        if (!CollectionUtils.isEmpty(expr.getAllOf())) {
            for (FastIntentConfig.MatchExpression child : expr.getAllOf()) {
                if (!matches(child, context)) {
                    return false;
                }
            }
            return true;
        }

        // OR
        if (!CollectionUtils.isEmpty(expr.getAnyOf())) {
            for (FastIntentConfig.MatchExpression child : expr.getAnyOf()) {
                if (matches(child, context)) {
                    return true;
                }
            }
            return false;
        }

        // Atom
        if (expr.getCondition() != null) {
            return matchesAtom(expr.getCondition(), context);
        }

        return false;
    }

    private boolean matchesAtom(FastIntentConfig.Condition condition, FastIntentContext context) {
        if (condition == null || !StringUtils.hasText(condition.getType())) {
            return false;
        }
        FastIntentConditionMatcher matcher = matcherByType.get(condition.getType());
        if (matcher == null) {
            log.warn("FastIntentService#matchesAtom - reason=unknown condition type: {}", condition.getType());
            return false;
        }
        try {
            return matcher.matches(condition, context);
        } catch (Exception e) {
            log.warn("FastIntentService#matchesAtom - reason=matcher failed type={}, error={}",
                    condition.getType(), e.getMessage());
            return false;
        }
    }

    /* ---------------- built-in matchers ---------------- */

    static class MessagePrefixMatcher implements FastIntentConditionMatcher {
        @Override
        public String getType() {
            return "message_prefix";
        }

        @Override
        public boolean matches(FastIntentConfig.Condition condition, FastIntentContext context) {
            String input = context.getInput();
            if (!StringUtils.hasText(input) || !StringUtils.hasText(condition.getValue())) {
                return false;
            }
            boolean trim = condition.getTrim() == null || Boolean.TRUE.equals(condition.getTrim());
            boolean ignoreCase = Boolean.TRUE.equals(condition.getIgnoreCase());
            String s = trim ? input.trim() : input;
            String prefix = condition.getValue();
            if (ignoreCase) {
                return s.toLowerCase().startsWith(prefix.toLowerCase());
            }
            return s.startsWith(prefix);
        }
    }

    static class MessageRegexMatcher implements FastIntentConditionMatcher {
        @Override
        public String getType() {
            return "message_regex";
        }

        @Override
        public boolean matches(FastIntentConfig.Condition condition, FastIntentContext context) {
            String input = context.getInput();
            if (!StringUtils.hasText(input) || !StringUtils.hasText(condition.getPattern())) {
                return false;
            }
            Pattern p = Pattern.compile(condition.getPattern());
            return p.matcher(input).find();
        }
    }

    static class MetadataExistsMatcher implements FastIntentConditionMatcher {
        @Override
        public String getType() {
            return "metadata_exists";
        }

        @Override
        public boolean matches(FastIntentConfig.Condition condition, FastIntentContext context) {
            if (!StringUtils.hasText(condition.getKey())) {
                return false;
            }
            return context.getConfigMetadata().containsKey(condition.getKey());
        }
    }

    static class MetadataEqualsMatcher implements FastIntentConditionMatcher {
        @Override
        public String getType() {
            return "metadata_equals";
        }

        @Override
        public boolean matches(FastIntentConfig.Condition condition, FastIntentContext context) {
            if (!StringUtils.hasText(condition.getKey()) || !StringUtils.hasText(condition.getValue())) {
                return false;
            }
            Object v = context.getConfigMetadata().get(condition.getKey());
            if (v == null) {
                return false;
            }
            return condition.getValue().equals(String.valueOf(v));
        }
    }

    static class MetadataInMatcher implements FastIntentConditionMatcher {
        @Override
        public String getType() {
            return "metadata_in";
        }

        @Override
        public boolean matches(FastIntentConfig.Condition condition, FastIntentContext context) {
            if (!StringUtils.hasText(condition.getKey()) || CollectionUtils.isEmpty(condition.getValues())) {
                return false;
            }
            Object v = context.getConfigMetadata().get(condition.getKey());
            if (v == null) {
                return false;
            }
            String s = String.valueOf(v);
            return condition.getValues().contains(s);
        }
    }

    static class StateEqualsMatcher implements FastIntentConditionMatcher {
        @Override
        public String getType() {
            return "state_equals";
        }

        @Override
        public boolean matches(FastIntentConfig.Condition condition, FastIntentContext context) {
            if (!StringUtils.hasText(condition.getKey()) || !StringUtils.hasText(condition.getValue())) {
                return false;
            }
            Object v = context.stateValue(condition.getKey()).orElse(null);
            if (v == null) {
                return false;
            }
            return condition.getValue().equals(String.valueOf(v));
        }
    }

    static class ToolArgEqualsMatcher implements FastIntentConditionMatcher {
        @Override
        public String getType() {
            return "tool_arg_equals";
        }

        @Override
        public boolean matches(FastIntentConfig.Condition condition, FastIntentContext context) {
            if (!StringUtils.hasText(condition.getKey()) || !StringUtils.hasText(condition.getValue())) {
                return false;
            }
            Object v = context.getToolRequest().get(condition.getKey());
            if (v == null) {
                return false;
            }
            return condition.getValue().equals(String.valueOf(v));
        }
    }
}

