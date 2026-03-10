/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.assistant.agent.autoconfigure.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 默认评估套件配置属性
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "spring.ai.alibaba.codeact.starter.evaluation")
public class DefaultEvaluationProperties {

    /**
     * 是否启用评估
     */
    private boolean enabled = true;

    /**
     * 是否启用用户输入增强
     */
    private boolean enhancedUserInputEnabled = true;

    /**
     * 经验检索配置
     */
    private ExperienceRetrievalConfig experience = new ExperienceRetrievalConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnhancedUserInputEnabled() {
        return enhancedUserInputEnabled;
    }

    public void setEnhancedUserInputEnabled(boolean enhancedUserInputEnabled) {
        this.enhancedUserInputEnabled = enhancedUserInputEnabled;
    }

    public ExperienceRetrievalConfig getExperience() {
        return experience;
    }

    public void setExperience(ExperienceRetrievalConfig experience) {
        this.experience = experience;
    }

    /**
     * @deprecated 不再区分阶段，使用 {@link #isEnabled()} 代替
     */
    @Deprecated
    public PhaseConfig getReactPhase() {
        PhaseConfig config = new PhaseConfig();
        config.setEnabled(this.enabled);
        config.setEnhancedUserInputEnabled(this.enhancedUserInputEnabled);
        return config;
    }

    /**
     * @deprecated 不再区分阶段
     */
    @Deprecated
    public void setReactPhase(PhaseConfig reactPhase) {
        this.enabled = reactPhase.isEnabled();
        this.enhancedUserInputEnabled = reactPhase.isEnhancedUserInputEnabled();
    }

    /**
     * @deprecated 已弃用
     */
    @Deprecated
    public PhaseConfig getCodeactPhase() {
        PhaseConfig config = new PhaseConfig();
        config.setEnabled(false);
        return config;
    }

    /**
     * @deprecated 已弃用
     */
    @Deprecated
    public void setCodeactPhase(PhaseConfig codeactPhase) {
        // no-op
    }

    /**
     * @deprecated 不再区分阶段
     */
    @Deprecated
    public static class PhaseConfig {

        private boolean enabled = true;
        private boolean enhancedUserInputEnabled = true;
        private boolean enhancedTaskInputEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnhancedUserInputEnabled() {
            return enhancedUserInputEnabled;
        }

        public void setEnhancedUserInputEnabled(boolean enhancedUserInputEnabled) {
            this.enhancedUserInputEnabled = enhancedUserInputEnabled;
        }

        public boolean isEnhancedTaskInputEnabled() {
            return enhancedTaskInputEnabled;
        }

        public void setEnhancedTaskInputEnabled(boolean enhancedTaskInputEnabled) {
            this.enhancedTaskInputEnabled = enhancedTaskInputEnabled;
        }
    }

    /**
     * 经验检索配置
     */
    public static class ExperienceRetrievalConfig {

        /**
         * 是否启用经验检索作为评估 Criterion
         */
        private boolean enabled = true;

        /**
         * 每种经验类型最多检索的数量
         */
        private int maxExperiencesPerType = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxExperiencesPerType() {
            return maxExperiencesPerType;
        }

        public void setMaxExperiencesPerType(int maxExperiencesPerType) {
            this.maxExperiencesPerType = maxExperiencesPerType;
        }
    }
}
