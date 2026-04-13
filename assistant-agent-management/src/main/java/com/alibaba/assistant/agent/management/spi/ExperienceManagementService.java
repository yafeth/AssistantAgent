package com.alibaba.assistant.agent.management.spi;

import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.management.model.ExperienceCreateRequest;
import com.alibaba.assistant.agent.management.model.ExperienceListQuery;
import com.alibaba.assistant.agent.management.model.ExperienceUpdateRequest;
import com.alibaba.assistant.agent.management.model.ExperienceVO;
import com.alibaba.assistant.agent.management.model.PageResult;

import java.util.List;
import java.util.Map;

public interface ExperienceManagementService {

    PageResult<ExperienceVO> list(ExperienceListQuery query);

    List<ExperienceVO> search(String keyword, ExperienceType type, int topK);

    ExperienceVO getById(String id);

    String create(ExperienceCreateRequest request);

    void update(String id, ExperienceUpdateRequest request);

    void delete(String id);

    Map<ExperienceType, Long> countByType();
}
