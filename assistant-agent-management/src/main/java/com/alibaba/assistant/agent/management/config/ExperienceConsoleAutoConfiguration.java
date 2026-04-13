package com.alibaba.assistant.agent.management.config;

import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceToolInvocationClassifier;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceRepository;
import com.alibaba.assistant.agent.management.controller.ConsoleExceptionHandler;
import com.alibaba.assistant.agent.management.controller.ExperienceManagementController;
import com.alibaba.assistant.agent.management.controller.SkillExchangeController;
import com.alibaba.assistant.agent.management.controller.TenantController;
import com.alibaba.assistant.agent.management.controller.ToolSourceController;
import com.alibaba.assistant.agent.management.internal.InMemorySkillExchangeService;
import com.alibaba.assistant.agent.management.internal.InMemoryToolSourceBrowser;
import com.alibaba.assistant.agent.management.internal.RepositoryBackedExperienceManagementService;
import com.alibaba.assistant.agent.management.internal.SkillPackageParser;
import com.alibaba.assistant.agent.management.spi.ExperienceManagementService;
import com.alibaba.assistant.agent.management.spi.SkillExchangeService;
import com.alibaba.assistant.agent.management.spi.TenantListProvider;
import com.alibaba.assistant.agent.management.spi.ToolSourceBrowser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExperienceConsoleProperties.class)
@ConditionalOnProperty(prefix = "experience.console", name = "enabled", havingValue = "true")
@Import({
        ExperienceManagementController.class,
        ToolSourceController.class,
        SkillExchangeController.class,
        TenantController.class,
        ConsoleExceptionHandler.class
})
public class ExperienceConsoleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ExperienceManagementService.class)
    public ExperienceManagementService repositoryBackedExperienceManagementService(
            ExperienceRepository repository,
            @Autowired(required = false) ExperienceToolInvocationClassifier toolInvocationClassifier) {
        return new RepositoryBackedExperienceManagementService(repository, toolInvocationClassifier);
    }

    @Bean
    @ConditionalOnMissingBean(ToolSourceBrowser.class)
    public ToolSourceBrowser inMemoryToolSourceBrowser() {
        return new InMemoryToolSourceBrowser();
    }

    @Bean
    @ConditionalOnMissingBean(SkillExchangeService.class)
    public SkillExchangeService inMemorySkillExchangeService(ExperienceRepository repository) {
        return new InMemorySkillExchangeService(repository);
    }

    @Bean
    @ConditionalOnMissingBean(SkillPackageParser.class)
    public SkillPackageParser skillPackageParser() {
        return new SkillPackageParser();
    }

    @Bean
    @ConditionalOnMissingBean(TenantListProvider.class)
    public TenantListProvider defaultTenantListProvider() {
        return List::of;
    }

    @Bean
    public ConsoleWebMvcConfigurer consoleWebMvcConfigurer(ExperienceConsoleProperties properties) {
        return new ConsoleWebMvcConfigurer(properties);
    }
}
