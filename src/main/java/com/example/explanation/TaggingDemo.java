package com.example.explanation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Simple demo to show how tagging works
 */
public class TaggingDemo {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TaggingDemo.class);
    
    public static void main(String[] args) {
        LOGGER.info("=== Tagging Demo ===");
        
        // Create sample explanations that would be found in real ontologies
        List<List<String>> sampleExplanations = createSampleExplanations();
        
        // Show how tagging works
        for (int i = 0; i < sampleExplanations.size(); i++) {
            List<String> explanation = sampleExplanations.get(i);
            String tag = generateTagForExplanation(explanation);
            
            LOGGER.info("Explanation {}: {}", i + 1, explanation);
            LOGGER.info("  Tag: {}", tag);
            LOGGER.info("  Tag meaning: {}", explainTag(tag));
            LOGGER.info("");
        }
        
        LOGGER.info("=== Demo Complete ===");
    }
    
    private static List<List<String>> createSampleExplanations() {
        List<List<String>> explanations = new ArrayList<>();
        
        // Example 1: Transitive property (T)
        explanations.add(Arrays.asList(
            "TransitiveObjectProperty(<//www.example.com/genealogy.owl#isSiblingOf>)",
            "henrietta_sarah_green_1873 hasSister elizabeth_quincey_green_1886",
            "henrietta_sarah_green_1873 hasBrother frank_reginald_green_1883"
        ));
        
        // Example 2: Hierarchy (H)
        explanations.add(Arrays.asList(
            "SubPropertyOf(<//www.example.com/genealogy.owl#hasBrother> <//www.example.com/genealogy.owl#isSiblingOf>)",
            "henrietta_sarah_green_1873 hasBrother william_henry_hutchinson_green_1875"
        ));
        
        // Example 3: Complex (C) + Symmetry (S)
        explanations.add(Arrays.asList(
            "SymmetricObjectProperty(<//www.example.com/genealogy.owl#isSiblingOf>)",
            "FunctionalObjectProperty(<//www.example.com/genealogy.owl#hasSpouse>)",
            "frank_reginald_green_1883 isSiblingOf elizabeth_quincey_green_1886"
        ));
        
        // Example 4: Multiple features (THC)
        explanations.add(Arrays.asList(
            "TransitiveObjectProperty(<//www.example.com/genealogy.owl#isSiblingOf>)",
            "SubPropertyOf(<//www.example.com/genealogy.owl#hasBrother> <//www.example.com/genealogy.owl#isSiblingOf>)",
            "FunctionalObjectProperty(<//www.example.com/genealogy.owl#hasSpouse>)",
            "henrietta_sarah_green_1873 hasBrother william_henry_hutchinson_green_1875"
        ));
        
        return explanations;
    }
    
    /**
     * Generate a tag for an explanation based on its axioms
     */
    private static String generateTagForExplanation(List<String> axioms) {
        Set<String> tags = new HashSet<>();
        
        for (String axiom : axioms) {
            // Check for transitivity
            if (axiom.contains("TransitiveObjectProperty") || axiom.contains("transitive")) {
                tags.add("T");
            }
            
            // Check for role hierarchies
            if (axiom.contains("SubPropertyOf") || axiom.contains("SubClassOf")) {
                tags.add("H");
            }
            
            // Check for inverse roles
            if (axiom.contains("InverseObjectProperty") || axiom.contains("inverse")) {
                tags.add("I");
            }
            
            // Check for cardinality restrictions
            if (axiom.contains("MinCardinality") || axiom.contains("MaxCardinality") || 
                axiom.contains("ExactCardinality") || axiom.contains("cardinality")) {
                tags.add("R");
            }
            
            // Check for complex roles
            if (axiom.contains("FunctionalObjectProperty") || axiom.contains("ReflexiveObjectProperty") ||
                axiom.contains("IrreflexiveObjectProperty") || axiom.contains("SymmetricObjectProperty")) {
                tags.add("C");
            }
            
            // Check for nominals
            if (axiom.contains("ObjectOneOf") || axiom.contains("DataOneOf") || axiom.contains("nominal")) {
                tags.add("N");
            }
            
            // Check for symmetry
            if (axiom.contains("SymmetricObjectProperty") || axiom.contains("symmetric")) {
                tags.add("S");
            }
        }
        
        // Sort tags alphabetically for consistent ordering
        List<String> sortedTags = new ArrayList<>(tags);
        Collections.sort(sortedTags);
        
        return String.join("", sortedTags);
    }
    
    private static String explainTag(String tag) {
        if (tag.isEmpty()) return "No complexity features detected";
        
        List<String> meanings = new ArrayList<>();
        for (char c : tag.toCharArray()) {
            switch (c) {
                case 'T': meanings.add("Transitivity"); break;
                case 'H': meanings.add("Hierarchy"); break;
                case 'I': meanings.add("Inverse roles"); break;
                case 'R': meanings.add("Cardinality restrictions"); break;
                case 'C': meanings.add("Complex roles"); break;
                case 'N': meanings.add("Nominals"); break;
                case 'S': meanings.add("Symmetry"); break;
            }
        }
        return String.join(", ", meanings);
    }
} 