package com.alibaba.assistant.agent.extension.experience.model;

import java.util.List;
import java.util.Set;

/**
 * 经验查询条件
 *
 * @author Assistant Agent Team
 */
public class ExperienceQuery {

    /**
     * 经验类型
     */
    private ExperienceType type;

    /**
     * 生效范围列表，查询时会按优先级顺序查找
     */
    private List<ExperienceScope> scopes;

    /**
     * 标签过滤
     */
    private Set<String> tags;

    /**
     * 模糊搜索文本
     */
    private String text;

    /**
     * 编程语言或自然语言
     */
    private String language;

    /**
     * 最多返回多少条，默认5条
     */
    private int limit = 5;

    /**
     * 经验候选召回策略。
     */
    private RetrievalMode retrievalMode = RetrievalMode.DEFAULT;

    /**
     * 排序方式
     */
    private OrderBy orderBy = OrderBy.UPDATED_AT;

    /**
     * 用户ID过滤
     */
    private String ownerId;

    /**
     * 项目ID过滤
     */
    private String projectId;

    /**
     * 排序枚举
     */
    public enum OrderBy {
        CREATED_AT,
        UPDATED_AT,
        SCORE
    }

    /**
     * 候选召回策略。
     */
    public enum RetrievalMode {
        /**
         * 默认策略，由 Provider 自行决定是否走向量召回或普通查询。
         */
        DEFAULT,
        /**
         * 全量扫描候选集，适用于 FastIntent 等需要穷举匹配的场景。
         */
        FULL_SCAN
    }

    public ExperienceQuery() {
    }

    public ExperienceQuery(ExperienceType type) {
        this.type = type;
    }

    public ExperienceType getType() {
        return type;
    }

    public void setType(ExperienceType type) {
        this.type = type;
    }

    public List<ExperienceScope> getScopes() {
        return scopes;
    }

    public void setScopes(List<ExperienceScope> scopes) {
        this.scopes = scopes;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public RetrievalMode getRetrievalMode() {
        return retrievalMode;
    }

    public void setRetrievalMode(RetrievalMode retrievalMode) {
        this.retrievalMode = retrievalMode;
    }

    public OrderBy getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(OrderBy orderBy) {
        this.orderBy = orderBy;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
}
