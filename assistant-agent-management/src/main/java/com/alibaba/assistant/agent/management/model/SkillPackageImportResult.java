package com.alibaba.assistant.agent.management.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 包导入结果。
 *
 * <p>包含导入成功的经验信息、已处理的文件、被跳过的文件及原因、以及警告信息。
 * 用于向用户清晰反馈导入过程中每个文件的处理状态。
 */
public class SkillPackageImportResult {

    /**
     * 导入后生成的经验 ID
     */
    private String importedId;

    /**
     * 导入后的经验预览
     */
    private ExperienceVO experience;

    /**
     * 成功处理的文件路径列表
     */
    private List<String> processedFiles = new ArrayList<>();

    /**
     * 被跳过的文件及原因
     */
    private List<SkippedFile> skippedFiles = new ArrayList<>();

    /**
     * 警告信息列表
     */
    private List<String> warnings = new ArrayList<>();

    public SkillPackageImportResult() {
    }

    /**
     * 被跳过的文件信息
     */
    public static class SkippedFile {
        private String path;
        private String reason;

        public SkippedFile() {
        }

        public SkippedFile(String path, String reason) {
            this.path = path;
            this.reason = reason;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    // --- Getters and Setters ---

    public String getImportedId() {
        return importedId;
    }

    public void setImportedId(String importedId) {
        this.importedId = importedId;
    }

    public ExperienceVO getExperience() {
        return experience;
    }

    public void setExperience(ExperienceVO experience) {
        this.experience = experience;
    }

    public List<String> getProcessedFiles() {
        return processedFiles;
    }

    public void setProcessedFiles(List<String> processedFiles) {
        this.processedFiles = processedFiles != null ? processedFiles : new ArrayList<>();
    }

    public List<SkippedFile> getSkippedFiles() {
        return skippedFiles;
    }

    public void setSkippedFiles(List<SkippedFile> skippedFiles) {
        this.skippedFiles = skippedFiles != null ? skippedFiles : new ArrayList<>();
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings != null ? warnings : new ArrayList<>();
    }

    public void addProcessedFile(String path) {
        this.processedFiles.add(path);
    }

    public void addSkippedFile(String path, String reason) {
        this.skippedFiles.add(new SkippedFile(path, reason));
    }

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public boolean hasSkippedFiles() {
        return skippedFiles != null && !skippedFiles.isEmpty();
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}
