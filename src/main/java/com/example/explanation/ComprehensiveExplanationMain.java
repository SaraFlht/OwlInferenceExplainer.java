package com.example.explanation;

import com.example.ontology.DefaultOntologyService;
import com.example.ontology.OntologyService;
import com.example.output.HybridOutputService;
import com.example.output.OutputService;
import com.example.query.QueryGenerationService;
import com.example.query.SparqlQueryGenerationService;
import com.example.reasoning.PelletReasoningService;
import com.example.reasoning.ReasoningService;
import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Main class to test the comprehensive explanation service
 */
public class ComprehensiveExplanationMain {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ComprehensiveExplanationMain.class);
    
    public static void main(String[] args) {
        try {
            LOGGER.info("=== Starting Comprehensive Explanation Service Test ===");
            
            // Initialize services
            OntologyService ontologyService = new DefaultOntologyService();
            ReasoningService reasoningService = new PelletReasoningService();
            QueryGenerationService queryService = new SparqlQueryGenerationService();
            OutputService outputService = new HybridOutputService();
            
            // Set paths
            String ontologiesDir = "src/main/resources/ontologies/family_1hop_tbox/";
            String csvFilePath = "SPARQL_questions_sampling.csv";
            String jsonFilePath = "explanations.json";
            
            // Check if files exist
            LOGGER.info("Checking input files...");
            checkFileExists(ontologiesDir, "Ontologies directory");
            checkFileExists(csvFilePath, "CSV queries file");
            checkFileExists(jsonFilePath, "JSON explanations file");
            
            // Load ontology
            LOGGER.info("Loading ontology...");
            File ontologyDir = new File(ontologiesDir);
            File[] ontologyFiles = ontologyDir.listFiles((dir, name) -> name.endsWith(".ttl"));
            
            if (ontologyFiles == null || ontologyFiles.length == 0) {
                throw new RuntimeException("No ontology files found in: " + ontologiesDir);
            }
            
            // Load the first ontology for testing
            File firstOntology = ontologyFiles[0];
            LOGGER.info("Loading ontology: " + firstOntology.getName());
            
            ontologyService.loadOntology(firstOntology.getAbsolutePath());
            reasoningService.initializeReasoner(ontologyService.getOntology());
            OpenlletReasoner reasoner = reasoningService.getReasoner();
            
            // Initialize the comprehensive explanation service
            LOGGER.info("Initializing comprehensive explanation service...");
            ComprehensiveExplanationService explanationService = 
                ExplanationServiceFactory.createAndInitializeService(reasoner);
            
            // Initialize the explanation tagger
            LOGGER.info("Initializing explanation tagger...");
            ExplanationTagger tagger = new ExplanationTagger();
            

            
            // Test all strategies
            LOGGER.info("Testing all explanation strategies...");
            ComprehensiveExplanationTest.testAllStrategies(reasoner);
            
            // Test specific examples from the family ontology
            LOGGER.info("Testing specific family ontology examples...");
            testFamilyOntologyExamples(explanationService, reasoner);
            
            // Test with existing data
            LOGGER.info("Testing with existing explanation data...");
            testWithExistingData(explanationService, reasoner, csvFilePath, jsonFilePath);
            
            // Test explanation tagging
            LOGGER.info("Testing explanation tagging...");
            testExplanationTagging(explanationService, tagger, reasoner);
            
            LOGGER.info("=== Comprehensive Explanation Service Test Complete ===");
            
        } catch (Exception e) {
            LOGGER.error("Error during comprehensive explanation test: " + e.getMessage(), e);
        }
    }
    
    private static void testFamilyOntologyExamples(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing family ontology examples...");
        
        // Test 1: Sibling relationship
        testSiblingRelationship(service, reasoner);
        
        // Test 2: Parent-child relationship
        testParentChildRelationship(service, reasoner);
        
        // Test 3: Uncle relationship (property chain)
        testUncleRelationship(service, reasoner);
        
        // Test 4: Class hierarchy (Man -> Person)
        testClassHierarchy(service, reasoner);
    }
    
    private static void testSiblingRelationship(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing sibling relationship...");
        
        // Find isSiblingOf property
        for (OWLObjectProperty prop : reasoner.getRootOntology().getObjectPropertiesInSignature()) {
            if (prop.getIRI().getFragment().equals("isSiblingOf")) {
                // Find individuals that are siblings
                for (OWLNamedIndividual ind1 : reasoner.getRootOntology().getIndividualsInSignature()) {
                    for (OWLNamedIndividual ind2 : reasoner.getRootOntology().getIndividualsInSignature()) {
                        if (!ind1.equals(ind2)) {
                            OWLAxiom siblingAx = reasoner.getRootOntology().getOWLOntologyManager()
                                    .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(prop, ind1, ind2);
                            
                            if (reasoner.isEntailed(siblingAx)) {
                                LOGGER.info("Found sibling relationship: {} isSiblingOf {}", 
                                          ind1.getIRI().getFragment(), ind2.getIRI().getFragment());
                                
                                List<ExplanationPath> explanations = service.explainInference(siblingAx);
                                LOGGER.info("Found {} explanation paths", explanations.size());
                                
                                for (int i = 0; i < explanations.size(); i++) {
                                    ExplanationPath path = explanations.get(i);
                                    LOGGER.info("Path {}: {} (Type: {})", i + 1, 
                                              path.getDescription(), path.getType().getDisplayName());
                                    LOGGER.info("  Axioms: {}", path.getAxioms().size());
                                }
                                return;
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No sibling relationships found");
    }
    
    private static void testParentChildRelationship(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing parent-child relationship...");
        
        // Find hasChild property
        for (OWLObjectProperty prop : reasoner.getRootOntology().getObjectPropertiesInSignature()) {
            if (prop.getIRI().getFragment().equals("hasChild")) {
                // Find parent-child relationships
                for (OWLNamedIndividual parent : reasoner.getRootOntology().getIndividualsInSignature()) {
                    for (OWLNamedIndividual child : reasoner.getRootOntology().getIndividualsInSignature()) {
                        if (!parent.equals(child)) {
                            OWLAxiom hasChildAx = reasoner.getRootOntology().getOWLOntologyManager()
                                    .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(prop, parent, child);
                            
                            if (reasoner.isEntailed(hasChildAx)) {
                                LOGGER.info("Found parent-child relationship: {} hasChild {}", 
                                          parent.getIRI().getFragment(), child.getIRI().getFragment());
                                
                                List<ExplanationPath> explanations = service.explainInference(hasChildAx);
                                LOGGER.info("Found {} explanation paths", explanations.size());
                                
                                for (int i = 0; i < explanations.size(); i++) {
                                    ExplanationPath path = explanations.get(i);
                                    LOGGER.info("Path {}: {} (Type: {})", i + 1, 
                                              path.getDescription(), path.getType().getDisplayName());
                                    LOGGER.info("  Axioms: {}", path.getAxioms().size());
                                }
                                return;
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No parent-child relationships found");
    }
    
    private static void testUncleRelationship(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing uncle relationship (property chain)...");
        
        // Find isUncleOf property
        for (OWLObjectProperty prop : reasoner.getRootOntology().getObjectPropertiesInSignature()) {
            if (prop.getIRI().getFragment().equals("isUncleOf")) {
                // Find uncle relationships
                for (OWLNamedIndividual uncle : reasoner.getRootOntology().getIndividualsInSignature()) {
                    for (OWLNamedIndividual niece : reasoner.getRootOntology().getIndividualsInSignature()) {
                        if (!uncle.equals(niece)) {
                            OWLAxiom uncleAx = reasoner.getRootOntology().getOWLOntologyManager()
                                    .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(prop, uncle, niece);
                            
                            if (reasoner.isEntailed(uncleAx)) {
                                LOGGER.info("Found uncle relationship: {} isUncleOf {}", 
                                          uncle.getIRI().getFragment(), niece.getIRI().getFragment());
                                
                                List<ExplanationPath> explanations = service.explainInference(uncleAx);
                                LOGGER.info("Found {} explanation paths", explanations.size());
                                
                                for (int i = 0; i < explanations.size(); i++) {
                                    ExplanationPath path = explanations.get(i);
                                    LOGGER.info("Path {}: {} (Type: {})", i + 1, 
                                              path.getDescription(), path.getType().getDisplayName());
                                    LOGGER.info("  Axioms: {}", path.getAxioms().size());
                                }
                                return;
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No uncle relationships found");
    }
    
    private static void testClassHierarchy(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing class hierarchy...");
        
        // Find Man class
        for (OWLClass clazz : reasoner.getRootOntology().getClassesInSignature()) {
            if (clazz.getIRI().getFragment().equals("Man")) {
                // Find individuals that are men
                for (OWLNamedIndividual individual : reasoner.getRootOntology().getIndividualsInSignature()) {
                    OWLAxiom manAx = reasoner.getRootOntology().getOWLOntologyManager()
                            .getOWLDataFactory().getOWLClassAssertionAxiom(clazz, individual);
                    
                    if (reasoner.isEntailed(manAx)) {
                        LOGGER.info("Found man individual: {}", individual.getIRI().getFragment());
                        
                        List<ExplanationPath> explanations = service.explainInference(manAx);
                        LOGGER.info("Found {} explanation paths", explanations.size());
                        
                        for (int i = 0; i < explanations.size(); i++) {
                            ExplanationPath path = explanations.get(i);
                            LOGGER.info("Path {}: {} (Type: {})", i + 1, 
                                      path.getDescription(), path.getType().getDisplayName());
                            LOGGER.info("  Axioms: {}", path.getAxioms().size());
                        }
                        return;
                    }
                }
            }
        }
        
        LOGGER.info("No man individuals found");
    }
    
    private static void testWithExistingData(ComprehensiveExplanationService service, OpenlletReasoner reasoner, 
                                           String csvFilePath, String jsonFilePath) {
        LOGGER.info("Testing with existing explanation data...");
        
        // This would integrate with your existing data processing
        // For now, just log that we're ready to process existing data
        LOGGER.info("Ready to process existing data from: {} and {}", csvFilePath, jsonFilePath);
        LOGGER.info("This would involve parsing the existing explanations and comparing with new ones");
    }
    
    private static void checkFileExists(String path, String description) {
        File file = new File(path);
        if (!file.exists()) {
            throw new RuntimeException(description + " not found at: " + path);
        }
        LOGGER.info("✓ " + description + " found: " + path);
    }
    
    private static void testExplanationTagging(ComprehensiveExplanationService service, 
                                             ExplanationTagger tagger,
                                             OpenlletReasoner reasoner) {
        LOGGER.info("=== Testing Explanation Tagging ===");
        
        // Print tag descriptions
        LOGGER.info("Tag descriptions:");
        Map<String, String> tagDescriptions = ExplanationTagger.getTagDescriptions();
        for (Map.Entry<String, String> entry : tagDescriptions.entrySet()) {
            LOGGER.info("  {}: {}", entry.getKey(), entry.getValue());
        }
        
        // Test tagging on various relationships
        testTaggingOnRelationship(service, tagger, reasoner, "isSiblingOf", "sibling");
        testTaggingOnRelationship(service, tagger, reasoner, "hasChild", "parent-child");
        testTaggingOnRelationship(service, tagger, reasoner, "isUncleOf", "uncle");
        testTaggingOnRelationship(service, tagger, reasoner, "hasSpouse", "spouse");
        
        // Test class hierarchy tagging
        testClassHierarchyTagging(service, tagger, reasoner);
    }
    
    private static void testTaggingOnRelationship(ComprehensiveExplanationService service,
                                                ExplanationTagger tagger,
                                                OpenlletReasoner reasoner,
                                                String propertyName,
                                                String relationshipType) {
        LOGGER.info("Testing tagging for {} relationships...", relationshipType);
        
        // Find the property
        OWLObjectProperty targetProperty = null;
        for (OWLObjectProperty prop : reasoner.getRootOntology().getObjectPropertiesInSignature()) {
            if (prop.getIRI().getFragment().equals(propertyName)) {
                targetProperty = prop;
                break;
            }
        }
        
        if (targetProperty == null) {
            LOGGER.info("Property {} not found", propertyName);
            return;
        }
        
        // Find some relationships of this type
        int foundCount = 0;
        for (OWLNamedIndividual ind1 : reasoner.getRootOntology().getIndividualsInSignature()) {
            for (OWLNamedIndividual ind2 : reasoner.getRootOntology().getIndividualsInSignature()) {
                if (!ind1.equals(ind2)) {
                    OWLAxiom relationshipAx = reasoner.getRootOntology().getOWLOntologyManager()
                            .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(targetProperty, ind1, ind2);
                    
                    if (reasoner.isEntailed(relationshipAx)) {
                        LOGGER.info("Found {} relationship: {} {} {}", 
                                  relationshipType, ind1.getIRI().getFragment(), propertyName, ind2.getIRI().getFragment());
                        
                        List<ExplanationPath> explanations = service.explainInference(relationshipAx);
                        List<String> tags = tagger.tagExplanations(explanations);
                        
                        LOGGER.info("  Found {} explanation paths with tags: {}", explanations.size(), tags);
                        
                        // Generate and print summary
                        Map<String, Object> summary = TaggingSummary.generateSummary(explanations, tagger);
                        TaggingSummary.printSummary(summary);
                        

                        
                        // Show detailed analysis for each explanation
                        for (int i = 0; i < explanations.size(); i++) {
                            ExplanationPath path = explanations.get(i);
                            String tag = tags.get(i);
                            Map<String, Object> analysis = tagger.analyzeExplanation(path);
                            
                            LOGGER.info("  Path {}: {} (Tag: {})", i + 1, path.getDescription(), tag);
                            LOGGER.info("    Type: {}, Complexity: {}", 
                                      analysis.get("type"), analysis.get("complexity"));
                            
                            @SuppressWarnings("unchecked")
                            Map<String, Boolean> features = (Map<String, Boolean>) analysis.get("features");
                            if (features != null) {
                                List<String> activeFeatures = features.entrySet().stream()
                                    .filter(Map.Entry::getValue)
                                    .map(Map.Entry::getKey)
                                    .collect(Collectors.toList());
                                LOGGER.info("    Active features: {}", activeFeatures);
                            }
                        }
                        
                        foundCount++;
                        if (foundCount >= 2) break; // Limit to 2 examples per relationship type
                    }
                }
            }
            if (foundCount >= 2) break;
        }
        
        if (foundCount == 0) {
            LOGGER.info("No {} relationships found", relationshipType);
        }
    }
    
    private static void testClassHierarchyTagging(ComprehensiveExplanationService service,
                                                ExplanationTagger tagger,
                                                OpenlletReasoner reasoner) {
        LOGGER.info("Testing class hierarchy tagging...");
        
        // Find some class hierarchy relationships
        for (OWLClass subClass : reasoner.getRootOntology().getClassesInSignature()) {
            for (OWLClass superClass : reasoner.getRootOntology().getClassesInSignature()) {
                if (!subClass.equals(superClass)) {
                    OWLAxiom subClassAx = reasoner.getRootOntology().getOWLOntologyManager()
                            .getOWLDataFactory().getOWLSubClassOfAxiom(subClass, superClass);
                    
                    if (reasoner.isEntailed(subClassAx)) {
                        LOGGER.info("Found class hierarchy: {} SubClassOf {}", 
                                  subClass.getIRI().getFragment(), superClass.getIRI().getFragment());
                        
                        List<ExplanationPath> explanations = service.explainInference(subClassAx);
                        List<String> tags = tagger.tagExplanations(explanations);
                        
                        LOGGER.info("  Found {} explanation paths with tags: {}", explanations.size(), tags);
                        
                        for (int i = 0; i < explanations.size(); i++) {
                            ExplanationPath path = explanations.get(i);
                            String tag = tags.get(i);
                            LOGGER.info("  Path {}: {} (Tag: {})", i + 1, path.getDescription(), tag);
                        }
                        
                        return; // Just test one class hierarchy
                    }
                }
            }
        }
        
        LOGGER.info("No class hierarchy relationships found");
    }
} 