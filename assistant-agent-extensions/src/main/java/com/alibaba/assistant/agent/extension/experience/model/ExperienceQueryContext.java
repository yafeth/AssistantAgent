package com.alibaba.assistant.agent.extension.experience.model;

import org.springframework.util.StringUtils;

/**
 * 经验查询上下文
 * Hook基于OverAllState/RunnableConfig构造的上下文信息
 *
 * @author Assistant Agent Team
 */
public class ExperienceQueryContext {

    /**
     * 用户查询内容（用于经验检索）
     */
    private String userQuery;

    /**
     * 租户ID
     */
    private String tenantId;

    public ExperienceQueryContext() {
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = StringUtils.hasText(tenantId) ? tenantId.trim() : null;
    }

}
