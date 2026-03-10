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

import com.alibaba.assistant.agent.evaluation.model.EvaluationCriterion;

import java.util.Collections;
import java.util.List;

/**
 * 评估 Criterion 提供者接口
 *
 * <p>实现此接口可以向默认评估套件添加自定义 Criterion。
 * 这是一个模板模式的接口，Example 层只需实现此接口提供 Criterion 定义即可。
 *
 * <p>使用方式：
 * <pre>
 * &#64;Component
 * public class MyCustomCriterionProvider implements EvaluationCriterionProvider {
 *     &#64;Override
 *     public List&lt;EvaluationCriterion&gt; getCriteria() {
 *         return List.of(
 *             EvaluationCriterionBuilder.create("my_criterion")
 *                 .description("My custom criterion")
 *                 .resultType(ResultType.TEXT)
 *                 .workingMechanism("...")
 *                 .build()
 *         );
 *     }
 * }
 * </pre>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface EvaluationCriterionProvider {

    /**
     * 获取自定义 Criterion 列表
     *
     * @return Criterion 列表，返回空列表表示不添加
     */
    default List<EvaluationCriterion> getCriteria() {
        return Collections.emptyList();
    }

    /**
     * @deprecated 不再区分阶段，使用 {@link #getCriteria()} 代替
     */
    @Deprecated
    default List<EvaluationCriterion> getReactPhaseCriteria() {
        return getCriteria();
    }

    /**
     * @deprecated 已弃用，使用 {@link #getCriteria()} 代替
     */
    @Deprecated
    default List<EvaluationCriterion> getCodeActPhaseCriteria() {
        return Collections.emptyList();
    }
}
