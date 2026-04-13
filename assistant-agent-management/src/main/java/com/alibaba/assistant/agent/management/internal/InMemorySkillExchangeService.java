package com.alibaba.assistant.agent.management.internal;

import com.alibaba.assistant.agent.extension.experience.model.DisclosureStrategy;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceRepository;
import com.alibaba.assistant.agent.management.model.ExperienceVO;
import com.alibaba.assistant.agent.management.model.SkillPackage;
import com.alibaba.assistant.agent.management.model.SkillPackageImportResult;
import com.alibaba.assistant.agent.management.spi.SkillExchangeService;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class InMemorySkillExchangeService implements SkillExchangeService {

    private final ExperienceRepository repository;

    public InMemorySkillExchangeService(ExperienceRepository repository) {
        this.repository = repository;
    }

    @Override
    public String importSkill(String skillMarkdown) {
        Experience exp = parseSkillMarkdown(skillMarkdown);
        Experience saved = repository.save(exp);
        return saved.getId();
    }

    @Override
    public String exportSkill(String experienceId) {
        Experience exp = repository.findById(experienceId)
                .orElseThrow(() -> new IllegalArgumentException("Experience not found: " + experienceId));
        return formatSkillMarkdown(exp);
    }

    @Override
    public String exportAllSkills(ExperienceType type) {
        List<Experience> experiences = repository.findAllByType(type);
        return experiences.stream()
                .map(this::formatSkillMarkdown)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Override
    public ExperienceVO previewSkillImport(String skillMarkdown) {
        Experience exp = parseSkillMarkdown(skillMarkdown);
        return ExperienceVO.fromExperience(exp);
    }

    @Override
    public SkillPackageImportResult importSkillPackage(SkillPackage skillPackage) {
        SkillPackageImportResult result = new SkillPackageImportResult();

        if (!skillPackage.hasSkillMd()) {
            result.addWarning("No SKILL.md found in package");
            return result;
        }

        // 解析 SKILL.md（复用已有逻辑）
        Experience exp = parseSkillMarkdown(skillPackage.getSkillMdContent());
        result.addProcessedFile("SKILL.md");

        // 用 package.json 元数据补充（如果 frontmatter 中未提供）
        if ((exp.getName() == null || exp.getName().isBlank()) && skillPackage.getName() != null) {
            exp.setName(skillPackage.getName());
        }
        if ((exp.getDescription() == null || exp.getDescription().isBlank()) && skillPackage.getDescription() != null) {
            exp.setDescription(skillPackage.getDescription());
        }
        if (skillPackage.getVersion() != null) {
            exp.getMetadata().setVersion(skillPackage.getVersion());
        }

        // 处理 scripts/
        if (skillPackage.hasScripts()) {
            for (Map.Entry<String, String> entry : skillPackage.getScripts().entrySet()) {
                result.addProcessedFile(entry.getKey());
            }
        }

        // 记录其他文件为跳过
        if (skillPackage.hasOtherFiles()) {
            for (String path : skillPackage.getOtherFiles().keySet()) {
                if ("package.json".equals(path)) {
                    result.addProcessedFile(path);
                } else {
                    result.addSkippedFile(path, "File type not yet supported");
                }
            }
        }

        Experience saved = repository.save(exp);
        result.setImportedId(saved.getId());
        result.setExperience(ExperienceVO.fromExperience(saved));

        return result;
    }

    @Override
    public SkillPackageImportResult previewSkillPackageImport(SkillPackage skillPackage) {
        SkillPackageImportResult result = new SkillPackageImportResult();

        if (!skillPackage.hasSkillMd()) {
            result.addWarning("No SKILL.md found in package");
            return result;
        }

        Experience exp = parseSkillMarkdown(skillPackage.getSkillMdContent());
        result.addProcessedFile("SKILL.md");

        // 用 package.json 元数据补充
        if ((exp.getName() == null || exp.getName().isBlank()) && skillPackage.getName() != null) {
            exp.setName(skillPackage.getName());
        }
        if ((exp.getDescription() == null || exp.getDescription().isBlank()) && skillPackage.getDescription() != null) {
            exp.setDescription(skillPackage.getDescription());
        }
        if (skillPackage.getVersion() != null) {
            exp.getMetadata().setVersion(skillPackage.getVersion());
        }

        result.setExperience(ExperienceVO.fromExperience(exp));

        // 分类列出所有文件
        if (skillPackage.hasScripts()) {
            for (String path : skillPackage.getScripts().keySet()) {
                result.addProcessedFile(path);
            }
        }
        if (skillPackage.hasOtherFiles()) {
            for (String path : skillPackage.getOtherFiles().keySet()) {
                if ("package.json".equals(path)) {
                    result.addProcessedFile(path);
                } else {
                    result.addSkippedFile(path, "File type not yet supported");
                }
            }
        }

        return result;
    }

    private String formatSkillMarkdown(Experience exp) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(nullSafe(exp.getName())).append("\n");
        sb.append("type: ").append(exp.getType() != null ? exp.getType().name() : "").append("\n");
        sb.append("description: ").append(nullSafe(exp.getDescription())).append("\n");
        sb.append("tags: [").append(formatTags(exp.getTags())).append("]\n");
        sb.append("---\n\n");
        sb.append("# ").append(nullSafe(exp.getName())).append("\n\n");
        sb.append(nullSafe(exp.getContent()));
        return sb.toString();
    }

    private Experience parseSkillMarkdown(String skillMarkdown) {
        Experience exp = new Experience();

        String frontmatter = "";
        String body = skillMarkdown;

        if (skillMarkdown.startsWith("---")) {
            int endIndex = skillMarkdown.indexOf("---", 3);
            if (endIndex > 0) {
                frontmatter = skillMarkdown.substring(3, endIndex).trim();
                body = skillMarkdown.substring(endIndex + 3).trim();
            }
        }

        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            int colonIndex = line.indexOf(':');
            if (colonIndex < 0) {
                continue;
            }
            String key = line.substring(0, colonIndex).trim();
            String value = line.substring(colonIndex + 1).trim();

            switch (key) {
                case "name" -> exp.setName(value);
                case "type" -> {
                    try {
                        exp.setType(ExperienceType.valueOf(value));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                case "description" -> exp.setDescription(value);
                case "tags" -> exp.setTags(parseTags(value));
                case "version" -> exp.getMetadata().setVersion(value);
                default -> { }
            }
        }

        // Skill 导入默认类型为 REACT
        if (exp.getType() == null) {
            exp.setType(ExperienceType.REACT);
        }

        // REACT 类型默认使用渐进式披露
        if (exp.getDisclosureStrategy() == null && exp.getType() == ExperienceType.REACT) {
            exp.setDisclosureStrategy(DisclosureStrategy.PROGRESSIVE);
        }

        // 标记来源
        exp.getMetadata().setSource("skill-import");

        // Strip leading "# title\n" from body if present
        if (body.startsWith("# ")) {
            int newlineIdx = body.indexOf('\n');
            if (newlineIdx > 0) {
                body = body.substring(newlineIdx + 1).trim();
            }
        }
        exp.setContent(body);

        return exp;
    }

    private Set<String> parseTags(String value) {
        Set<String> tags = new LinkedHashSet<>();
        // Remove surrounding brackets
        value = value.trim();
        if (value.startsWith("[")) {
            value = value.substring(1);
        }
        if (value.endsWith("]")) {
            value = value.substring(0, value.length() - 1);
        }
        for (String tag : value.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    private String formatTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (String tag : tags) {
            joiner.add(tag);
        }
        return joiner.toString();
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
