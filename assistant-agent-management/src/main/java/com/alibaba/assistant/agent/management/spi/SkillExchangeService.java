package com.alibaba.assistant.agent.management.spi;

import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.management.model.ExperienceVO;
import com.alibaba.assistant.agent.management.model.SkillPackage;
import com.alibaba.assistant.agent.management.model.SkillPackageImportResult;

public interface SkillExchangeService {

    String importSkill(String skillMarkdown);

    String exportSkill(String experienceId);

    String exportAllSkills(ExperienceType type);

    ExperienceVO previewSkillImport(String skillMarkdown);

    /**
     * 导入 skill 包（文件夹/tgz/zip 解析后的结构）。
     *
     * <p>框架层默认实现会处理 SKILL.md 和 scripts/；
     * 业务层实现可选择跳过不支持的文件类型并在结果中给出提示。
     *
     * @param skillPackage 解析后的 skill 包
     * @return 导入结果，包含处理/跳过的文件明细
     */
    default SkillPackageImportResult importSkillPackage(SkillPackage skillPackage) {
        // 默认实现：退化为仅导入 SKILL.md 文本
        SkillPackageImportResult result = new SkillPackageImportResult();
        if (skillPackage.hasSkillMd()) {
            String id = importSkill(skillPackage.getSkillMdContent());
            result.setImportedId(id);
            result.addProcessedFile("SKILL.md");
        } else {
            result.addWarning("No SKILL.md found in package");
        }
        return result;
    }

    /**
     * 预览 skill 包导入结果（不实际持久化）。
     *
     * @param skillPackage 解析后的 skill 包
     * @return 导入预览结果
     */
    default SkillPackageImportResult previewSkillPackageImport(SkillPackage skillPackage) {
        SkillPackageImportResult result = new SkillPackageImportResult();
        if (skillPackage.hasSkillMd()) {
            ExperienceVO vo = previewSkillImport(skillPackage.getSkillMdContent());
            result.setExperience(vo);
            result.addProcessedFile("SKILL.md");
        } else {
            result.addWarning("No SKILL.md found in package");
        }
        return result;
    }
}
