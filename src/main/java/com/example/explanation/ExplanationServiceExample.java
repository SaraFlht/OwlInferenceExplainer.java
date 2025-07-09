package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Example usage of the new explanation service architecture
 */
public class ExplanationServiceExample {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ExplanationServiceExample.class);
    
    public static void main(String[] args) {
        // Example of how to use the new comprehensive explanation service
        
        // 1. Create the service using the factory
        ComprehensiveExplanationService service = ExplanationServiceFactory.createComprehensiveService();
        
        // 2. Initialize with a reasoner (you would get this from your ontology loading)
        // OpenlletReasoner reasoner = ... // Your reasoner instance
        // service.initializeExplanations(reasoner);
        
        // 3. Generate explanations for an axiom
        // OWLAxiom targetAxiom = ... // Your target axiom
        // List<ExplanationPath> explanations = service.explainInference(targetAxiom);
        
        // 4. Validate the explanations
        // boolean isValid = service.validateExplanation(targetAxiom, explanations);
        
        // 5. Process the explanations
        // for (ExplanationPath path : explanations) {
        //     LOGGER.info("Explanation path: {}", path.getDescription());
        //     LOGGER.info("Type: {}", path.getType().getDisplayName());
        //     LOGGER.info("Complexity: {}", path.getComplexity());
        //     LOGGER.info("Axioms:");
        //     for (OWLAxiom axiom : path.getAxioms()) {
        //         LOGGER.info("  - {}", axiom);
        //     }
        // }
        
        LOGGER.info("Explanation service example ready");
    }
    
    /**
     * Example of how to use the new service with a specific axiom
     */
    public static void demonstrateExplanation(OpenlletReasoner reasoner, OWLAxiom targetAxiom) {
        ComprehensiveExplanationService service = ExplanationServiceFactory.createAndInitializeService(reasoner);
        
        List<ExplanationPath> explanations = service.explainInference(targetAxiom);
        
        LOGGER.info("Found {} explanation paths for axiom: {}", explanations.size(), targetAxiom);
        
        for (int i = 0; i < explanations.size(); i++) {
            ExplanationPath path = explanations.get(i);
            LOGGER.info("Path {}: {}", i + 1, path.getDescription());
            LOGGER.info("  Type: {}", path.getType().getDisplayName());
            LOGGER.info("  Complexity: {}", path.getComplexity());
            LOGGER.info("  Axioms:");
            for (OWLAxiom axiom : path.getAxioms()) {
                LOGGER.info("    - {}", axiom);
            }
        }
    }
} 