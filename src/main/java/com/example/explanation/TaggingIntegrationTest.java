package com.example.explanation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Simple test to verify tagging integration with existing output
 */
public class TaggingIntegrationTest {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TaggingIntegrationTest.class);
    
    public static void main(String[] args) {
        LOGGER.info("=== Tagging Integration Test ===");
        
        // Test the tag generation logic
        testTagGeneration();
        
        LOGGER.info("=== Integration Test Complete ===");
    }
    
    private static void testTagGeneration() {
        LOGGER.info("Testing tag generation for different axiom types...");
        
        // Test different types of axioms
        testAxiomTagging("TransitiveObjectProperty(<//www.example.com/genealogy.owl#isSiblingOf>)", "T");
        testAxiomTagging("SubPropertyOf(<//www.example.com/genealogy.owl#isBrotherOf> <//www.example.com/genealogy.owl#isSiblingOf>)", "H");
        testAxiomTagging("InverseObjectProperty(<//www.example.com/genealogy.owl#hasChild> <//www.example.com/genealogy.owl#hasParent>)", "I");
        testAxiomTagging("MinCardinality(2 <//www.example.com/genealogy.owl#hasChild>)", "R");
        testAxiomTagging("FunctionalObjectProperty(<//www.example.com/genealogy.owl#hasSpouse>)", "C");
        testAxiomTagging("ObjectOneOf(<//www.example.com/genealogy.owl#john> <//www.example.com/genealogy.owl#mary>)", "N");
        testAxiomTagging("SymmetricObjectProperty(<//www.example.com/genealogy.owl#isSiblingOf>)", "S");
        
        // Test complex combinations
        testAxiomTagging("TransitiveObjectProperty(<//www.example.com/genealogy.owl#isSiblingOf>) SubPropertyOf(<//www.example.com/genealogy.owl#isBrotherOf> <//www.example.com/genealogy.owl#isSiblingOf>)", "HT");
        testAxiomTagging("FunctionalObjectProperty(<//www.example.com/genealogy.owl#hasSpouse>) SymmetricObjectProperty(<//www.example.com/genealogy.owl#isSiblingOf>)", "CS");
    }
    
    private static void testAxiomTagging(String axiom, String expectedTag) {
        List<String> axioms = Arrays.asList(axiom);
        String generatedTag = generateTagForExplanation(axioms);
        
        LOGGER.info("Axiom: {}", axiom);
        LOGGER.info("  Expected tag: {}", expectedTag);
        LOGGER.info("  Generated tag: {}", generatedTag);
        LOGGER.info("  Match: {}", generatedTag.equals(expectedTag) ? "✓" : "✗");
        LOGGER.info("");
    }
    
    /**
     * Generate a tag for an explanation based on its axioms
     * (Copy of the method from HybridOutputService for testing)
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
} 