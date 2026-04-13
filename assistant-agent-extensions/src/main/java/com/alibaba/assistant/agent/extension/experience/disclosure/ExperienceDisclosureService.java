package com.alibaba.assistant.agent.extension.experience.disclosure;

import com.alibaba.assistant.agent.extension.experience.config.ExperienceExtensionProperties;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.ExperienceCandidateCard;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.DirectExperienceGrounding;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.GroupedExperienceCandidates;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.PrefetchedExperienceSnapshot;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.ReadExpResponse;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.SearchExpResponse;
import com.alibaba.assistant.agent.extension.experience.model.DisclosureStrategy;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQuery;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQueryContext;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceProvider;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceRepository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.PrefetchStatus;

/**
 * Core retrieval service behind experience progressive disclosure.
 *
 * <p>It turns repository/provider data into grouped lightweight candidate cards for
 * prefetch/search and full-detail payloads for {@code read_exp}, while preserving
 * tenant filtering, disclosure strategy, and TOOL invocation metadata.
 */
public class ExperienceDisclosureService {

    private static final int DEFAULT_COMMON_LIMIT = 3;
    private static final int SNIPPET_LENGTH = 280;
    private static final int DIRECT_CONTENT_MAX_LENGTH = 500;
    private static final double DIRECT_CONFIDENCE_THRESHOLD = 0.8D;

    private final ExperienceProvider experienceProvider;
    private final ExperienceRepository experienceRepository;
    private final ExperienceExtensionProperties properties;
    private final ExperienceToolInvocationClassifier toolInvocationClassifier;

    public ExperienceDisclosureService(ExperienceProvider experienceProvider,
                                       ExperienceRepository experienceRepository,
                                       ExperienceExtensionProperties properties,
                                       ExperienceToolInvocationClassifier toolInvocationClassifier) {
        this.experienceProvider = experienceProvider;
        this.experienceRepository = experienceRepository;
        this.properties = properties;
        this.toolInvocationClassifier = toolInvocationClassifier;
    }

    public PrefetchedExperienceSnapshot prefetch(String query, ExperienceQueryContext context) {
        PrefetchedExperienceSnapshot snapshot = new PrefetchedExperienceSnapshot();
        snapshot.setQuery(query);
        if (!StringUtils.hasText(query)) {
            snapshot.setStatus(PrefetchStatus.SKIPPED);
            return snapshot;
        }
        List<Experience> commonExperiences = queryByType(ExperienceType.COMMON, query, DEFAULT_COMMON_LIMIT, context);
        List<Experience> reactExperiences = queryByType(ExperienceType.REACT, query, resolveActionLimit(), context);
        List<Experience> toolExperiences = queryByType(ExperienceType.TOOL, query, resolveActionLimit(), context);
        snapshot.setCandidates(searchGrouped(commonExperiences, reactExperiences, toolExperiences));
        snapshot.setDirectGroundings(extractDirectGroundings(commonExperiences, reactExperiences, toolExperiences));
        snapshot.setStatus(PrefetchStatus.COMPLETED);
        return snapshot;
    }

    public SearchExpResponse search(String query, Integer commonLimit, Integer reactLimit, Integer toolLimit,
                                    ExperienceQueryContext context) {
        SearchExpResponse response = new SearchExpResponse();
        response.setQuery(query);
        List<Experience> commonExperiences = queryByType(
                ExperienceType.COMMON, query, normalizeLimit(commonLimit, DEFAULT_COMMON_LIMIT), context);
        List<Experience> reactExperiences = queryByType(
                ExperienceType.REACT, query, normalizeLimit(reactLimit, resolveActionLimit()), context);
        List<Experience> toolExperiences = queryByType(
                ExperienceType.TOOL, query, normalizeLimit(toolLimit, resolveActionLimit()), context);
        response.setCandidates(searchGrouped(commonExperiences, reactExperiences, toolExperiences));
        response.setDirectGroundings(extractDirectGroundings(commonExperiences, reactExperiences, toolExperiences));
        return response;
    }

    public ReadExpResponse read(String id) {
        ReadExpResponse response = new ReadExpResponse();
        response.setId(id);
        if (!StringUtils.hasText(id)) {
            response.setFound(false);
            return response;
        }
        Optional<Experience> experienceOptional = experienceRepository.findById(id);
        if (experienceOptional.isEmpty()) {
            response.setFound(false);
            return response;
        }

        Experience experience = experienceOptional.get();
        response.setFound(true);
        response.setExperienceType(experience.getType());
        response.setTitle(experience.getName());
        response.setDescription(experience.getDescription());
        response.setContent(experience.getContent());
        response.setDisclosureStrategy(experience.getDisclosureStrategy() != null
                ? experience.getDisclosureStrategy().name() : null);
        response.setScore(resolveScore(experience));
        response.setAssociatedTools(new ArrayList<>(experience.getAssociatedTools()));
        response.setRelatedExperiences(new ArrayList<>(experience.getRelatedExperiences()));
        response.setArtifact(experience.getArtifact());

        if (experience.getType() == ExperienceType.TOOL) {
            response.setToolInvocationPath(toolInvocationClassifier.classify(experience));
            response.setCallableToolName(toolInvocationClassifier.resolveCallableToolName(experience));
        }
        return response;
    }

    private GroupedExperienceCandidates searchGrouped(List<Experience> commonExperiences,
                                                      List<Experience> reactExperiences,
                                                      List<Experience> toolExperiences) {
        GroupedExperienceCandidates candidates = new GroupedExperienceCandidates();
        candidates.setCommonCandidates(toCards(commonExperiences));
        candidates.setReactCandidates(toCards(reactExperiences));
        candidates.setToolCandidates(toCards(toolExperiences));
        return candidates;
    }

    private List<Experience> queryByType(ExperienceType type, String query, int limit, ExperienceQueryContext context) {
        ExperienceQuery experienceQuery = new ExperienceQuery(type);
        experienceQuery.setText(query);
        experienceQuery.setLimit(limit);
        if (type == ExperienceType.COMMON) {
            experienceQuery.setDisclosureStrategy(DisclosureStrategy.DIRECT);
        }
        return experienceProvider.query(experienceQuery, context);
    }

    private List<ExperienceCandidateCard> toCards(List<Experience> experiences) {
        List<ExperienceCandidateCard> cards = new ArrayList<>();
        for (Experience experience : experiences) {
            ExperienceCandidateCard card = new ExperienceCandidateCard();
            card.setId(experience.getId());
            card.setExperienceType(experience.getType());
            card.setTitle(experience.getName());
            card.setDescription(experience.getDescription());
            card.setSnippet(snippet(experience.getContent()));
            card.setDisclosureStrategy(experience.getDisclosureStrategy() != null
                    ? experience.getDisclosureStrategy().name() : null);
            card.setScore(resolveScore(experience));
            card.setAssociatedTools(new ArrayList<>(experience.getAssociatedTools()));
            card.setRelatedExperiences(new ArrayList<>(experience.getRelatedExperiences()));
            if (experience.getType() == ExperienceType.TOOL) {
                card.setToolInvocationPath(toolInvocationClassifier.classify(experience));
                card.setCallableToolName(toolInvocationClassifier.resolveCallableToolName(experience));
            }
            cards.add(card);
        }
        return cards;
    }

    private List<DirectExperienceGrounding> extractDirectGroundings(List<Experience> commonExperiences,
                                                                    List<Experience> reactExperiences,
                                                                    List<Experience> toolExperiences) {
        List<DirectExperienceGrounding> groundings = new ArrayList<>();
        appendDirectGroundings(groundings, commonExperiences);
        appendDirectGroundings(groundings, reactExperiences);
        appendDirectGroundings(groundings, toolExperiences);
        return groundings;
    }

    private void appendDirectGroundings(List<DirectExperienceGrounding> groundings, List<Experience> experiences) {
        if (experiences == null || experiences.isEmpty()) {
            return;
        }
        for (Experience experience : experiences) {
            Double score = resolveScore(experience);
            String disclosureStrategy = experience.getDisclosureStrategy() != null
                    ? experience.getDisclosureStrategy().name() : null;
            if (!isDirectGroundingEligible(disclosureStrategy, experience.getContent(), score)) {
                continue;
            }
            DirectExperienceGrounding grounding = new DirectExperienceGrounding();
            grounding.setId(experience.getId());
            grounding.setExperienceType(experience.getType());
            grounding.setTitle(experience.getName());
            grounding.setDescription(experience.getDescription());
            grounding.setContent(experience.getContent());
            grounding.setDisclosureStrategy(disclosureStrategy);
            grounding.setScore(score);
            if (experience.getType() == ExperienceType.TOOL) {
                grounding.setToolInvocationPath(toolInvocationClassifier.classify(experience));
                grounding.setCallableToolName(toolInvocationClassifier.resolveCallableToolName(experience));
            }
            groundings.add(grounding);
        }
    }

    public boolean isDirectGroundingEligible(ReadExpResponse response) {
        if (response == null || !response.isFound()) {
            return false;
        }
        return isDirectGroundingEligible(response.getDisclosureStrategy(), response.getContent(), response.getScore());
    }

    public DirectExperienceGrounding toDirectGrounding(ReadExpResponse response) {
        if (!isDirectGroundingEligible(response)) {
            return null;
        }
        DirectExperienceGrounding grounding = new DirectExperienceGrounding();
        grounding.setId(response.getId());
        grounding.setExperienceType(response.getExperienceType());
        grounding.setTitle(response.getTitle());
        grounding.setDescription(response.getDescription());
        grounding.setContent(response.getContent());
        grounding.setDisclosureStrategy(response.getDisclosureStrategy());
        grounding.setScore(response.getScore());
        grounding.setToolInvocationPath(response.getToolInvocationPath());
        grounding.setCallableToolName(response.getCallableToolName());
        return grounding;
    }

    public boolean isDirectGroundingEligible(DirectExperienceGrounding grounding) {
        if (grounding == null) {
            return false;
        }
        return isDirectGroundingEligible(grounding.getDisclosureStrategy(), grounding.getContent(), grounding.getScore());
    }

    private boolean isDirectGroundingEligible(String disclosureStrategy, String content, Double score) {
        if (!DisclosureStrategy.DIRECT.name().equals(disclosureStrategy)) {
            return false;
        }
        if (!StringUtils.hasText(content) || content.length() > DIRECT_CONTENT_MAX_LENGTH) {
            return false;
        }
        return score == null || score >= DIRECT_CONFIDENCE_THRESHOLD;
    }

    private Double resolveScore(Experience experience) {
        if (experience == null || experience.getMetadata() == null) {
            return null;
        }
        return experience.getMetadata().getConfidence();
    }

    private int resolveActionLimit() {
        return Math.max(1, properties.getMaxItemsPerQuery());
    }

    private int normalizeLimit(Integer requestedLimit, int fallback) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return fallback;
        }
        return requestedLimit;
    }

    private String snippet(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        if (content.length() <= SNIPPET_LENGTH) {
            return content;
        }
        return content.substring(0, SNIPPET_LENGTH) + "...";
    }
}
