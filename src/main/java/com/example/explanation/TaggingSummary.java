package com.example.explanation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class to generate tagging summaries for sets of explanations
 */
public class TaggingSummary {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TaggingSummary.class);
    
    /**
     * Generate a summary of tags for a set of explanations
     */
    public static Map<String, Object> generateSummary(List<ExplanationPath> explanations, 
                                                    ExplanationTagger tagger) {
        Map<String, Object> summary = new HashMap<>();
        
        // Tag all explanations
        List<String> tags = tagger.tagExplanations(explanations);
        
        // Count tag frequencies
        Map<String, Integer> tagFrequency = new HashMap<>();
        for (String tag : tags) {
            tagFrequency.put(tag, tagFrequency.getOrDefault(tag, 0) + 1);
        }
        
        // Count individual feature frequencies
        Map<String, Integer> featureFrequency = new HashMap<>();
        for (ExplanationPath path : explanations) {
            Map<String, Object> analysis = tagger.analyzeExplanation(path);
            @SuppressWarnings("unchecked")
            Map<String, Boolean> features = (Map<String, Boolean>) analysis.get("features");
            if (features != null) {
                for (Map.Entry<String, Boolean> entry : features.entrySet()) {
                    if (entry.getValue()) {
                        featureFrequency.put(entry.getKey(), 
                                          featureFrequency.getOrDefault(entry.getKey(), 0) + 1);
                    }
                }
            }
        }
        
        // Calculate complexity statistics
        List<Integer> complexities = explanations.stream()
            .map(ExplanationPath::getComplexity)
            .collect(Collectors.toList());
        
        double avgComplexity = complexities.stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
        
        int maxComplexity = complexities.stream()
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
        
        int minComplexity = complexities.stream()
            .mapToInt(Integer::intValue)
            .min()
            .orElse(0);
        
        // Build summary
        summary.put("totalExplanations", explanations.size());
        summary.put("tags", tags);
        summary.put("tagFrequency", tagFrequency);
        summary.put("featureFrequency", featureFrequency);
        summary.put("avgComplexity", avgComplexity);
        summary.put("maxComplexity", maxComplexity);
        summary.put("minComplexity", minComplexity);
        summary.put("complexityRange", maxComplexity - minComplexity);
        
        return summary;
    }
    
    /**
     * Print a formatted summary
     */
    public static void printSummary(Map<String, Object> summary) {
        LOGGER.info("=== Explanation Tagging Summary ===");
        LOGGER.info("Total explanations: {}", summary.get("totalExplanations"));
        
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) summary.get("tags");
        LOGGER.info("All tags: {}", tags);
        
        @SuppressWarnings("unchecked")
        Map<String, Integer> tagFrequency = (Map<String, Integer>) summary.get("tagFrequency");
        LOGGER.info("Tag frequency:");
        tagFrequency.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(entry -> LOGGER.info("  {}: {}", entry.getKey(), entry.getValue()));
        
        @SuppressWarnings("unchecked")
        Map<String, Integer> featureFrequency = (Map<String, Integer>) summary.get("featureFrequency");
        LOGGER.info("Feature frequency:");
        featureFrequency.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(entry -> LOGGER.info("  {}: {}", entry.getKey(), entry.getValue()));
        
        LOGGER.info("Complexity statistics:");
        LOGGER.info("  Average: {:.2f}", summary.get("avgComplexity"));
        LOGGER.info("  Range: {} - {}", summary.get("minComplexity"), summary.get("maxComplexity"));
        LOGGER.info("  Range size: {}", summary.get("complexityRange"));
    }
    
    /**
     * Generate a simple tag string for a set of explanations
     * Returns a comma-separated list of unique tags
     */
    public static String generateTagString(List<ExplanationPath> explanations, 
                                        ExplanationTagger tagger) {
        List<String> tags = tagger.tagExplanations(explanations);
        Set<String> uniqueTags = new HashSet<>(tags);
        List<String> sortedTags = new ArrayList<>(uniqueTags);
        Collections.sort(sortedTags);
        return String.join(",", sortedTags);
    }
    
    /**
     * Check if a set of explanations contains specific features
     */
    public static Map<String, Boolean> checkFeatures(List<ExplanationPath> explanations,
                                                   ExplanationTagger tagger) {
        Map<String, Boolean> features = new HashMap<>();
        
        // Initialize all features to false
        Map<String, String> descriptions = ExplanationTagger.getTagDescriptions();
        for (String tag : descriptions.keySet()) {
            features.put(tag, false);
        }
        
        // Check each explanation for features
        for (ExplanationPath path : explanations) {
            Map<String, Object> analysis = tagger.analyzeExplanation(path);
            @SuppressWarnings("unchecked")
            Map<String, Boolean> pathFeatures = (Map<String, Boolean>) analysis.get("features");
            if (pathFeatures != null) {
                for (Map.Entry<String, Boolean> entry : pathFeatures.entrySet()) {
                    if (entry.getValue()) {
                        features.put(entry.getKey(), true);
                    }
                }
            }
        }
        
        return features;
    }
} 