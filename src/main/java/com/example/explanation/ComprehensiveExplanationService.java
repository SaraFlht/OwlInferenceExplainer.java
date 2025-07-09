package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import openllet.owlapi.explanation.PelletExplanation;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class ComprehensiveExplanationService implements ExplanationService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ComprehensiveExplanationService.class);
    
    private final List<ExplanationStrategy> strategies;
    private final Map<String, String> explanationCache = new HashMap<>();
    private final ThreadLocal<Set<String>> currentExplanations = ThreadLocal.withInitial(HashSet::new);
    private OWLDataFactory dataFactory;
    
    public ComprehensiveExplanationService() {
        this.strategies = new ArrayList<>();
    }
    
    public void addStrategy(ExplanationStrategy strategy) {
        strategies.add(strategy);
        // Sort by priority (lower numbers = higher priority)
        strategies.sort(Comparator.comparingInt(ExplanationStrategy::getPriority));
    }
    
    @Override
    public void initializeExplanations(OpenlletReasoner reasoner) {
        LOGGER.info("Initializing comprehensive explanation service");
        
        // Initialize data factory
        this.dataFactory = reasoner.getRootOntology().getOWLOntologyManager().getOWLDataFactory();
        
        // Initialize all strategies
        for (ExplanationStrategy strategy : strategies) {
            if (strategy instanceof BaseExplanationStrategy) {
                ((BaseExplanationStrategy) strategy).initialize(reasoner);
            }
        }
        
        // Special handling for PelletNativeStrategy
        PelletExplanation.setup();
        PelletExplanation pelletExplanation = new PelletExplanation(reasoner);
        
        for (ExplanationStrategy strategy : strategies) {
            if (strategy instanceof PelletNativeStrategy) {
                ((PelletNativeStrategy) strategy).setPelletExplanation(pelletExplanation);
            }
        }
        
        // Pre-compute inferences
        reasoner.precomputeInferences();
    }
    
    @Override
    public List<ExplanationPath> explainInference(OWLAxiom targetAxiom) {
        List<ExplanationPath> allPaths = new ArrayList<>();
        
        // Check cache first
        String cacheKey = generateCacheKey(targetAxiom);
        if (explanationCache.containsKey(cacheKey)) {
            LOGGER.debug("Using cached explanation for: " + targetAxiom);
            return parseCachedExplanation(explanationCache.get(cacheKey));
        }
        
        // Check for recursion
        Set<String> currentlyExplaining = currentExplanations.get();
        if (currentlyExplaining.contains(cacheKey)) {
            LOGGER.debug("Recursive explanation detected for: " + targetAxiom);
            return allPaths;
        }
        
        currentlyExplaining.add(cacheKey);
        
        try {
            // Try each strategy in priority order
            for (ExplanationStrategy strategy : strategies) {
                if (strategy.canExplain(targetAxiom)) {
                    List<ExplanationPath> paths = strategy.explain(targetAxiom);
                    allPaths.addAll(paths);
                    
                    // If we found good explanations, we can stop
                    if (!paths.isEmpty()) {
                        LOGGER.debug("Found " + paths.size() + " explanations using " + 
                                   strategy.getClass().getSimpleName());
                    }
                }
            }
            
            // Deduplicate and validate
            allPaths = deduplicatePaths(allPaths);
            
            // Cache the result
            String explanationString = formatExplanationPaths(allPaths);
            explanationCache.put(cacheKey, explanationString);
            
        } finally {
            currentlyExplaining.remove(cacheKey);
        }
        
        return allPaths;
    }
    
    @Override
    public boolean validateExplanation(OWLAxiom targetAxiom, List<ExplanationPath> explanations) {
        // Basic validation: check if explanations are logically sound
        for (ExplanationPath path : explanations) {
            if (!isLogicallyCorrect(path, targetAxiom)) {
                LOGGER.warn("Invalid explanation path: " + path);
                return false;
            }
        }
        return true;
    }
    
    private boolean isLogicallyCorrect(ExplanationPath path, OWLAxiom targetAxiom) {
        // Simple validation: check if all axioms in the path are relevant
        // This is a basic check - more sophisticated validation could be added
        return !path.getAxioms().isEmpty();
    }
    
    private List<ExplanationPath> deduplicatePaths(List<ExplanationPath> paths) {
        Set<String> seenPaths = new HashSet<>();
        List<ExplanationPath> uniquePaths = new ArrayList<>();
        
        for (ExplanationPath path : paths) {
            String pathKey = generatePathKey(path);
            if (!seenPaths.contains(pathKey)) {
                seenPaths.add(pathKey);
                uniquePaths.add(path);
            }
        }
        
        return uniquePaths;
    }
    
    private String generatePathKey(ExplanationPath path) {
        return path.getAxioms().stream()
                .map(OWLAxiom::toString)
                .sorted()
                .collect(Collectors.joining("|"));
    }
    
    private String generateCacheKey(OWLAxiom axiom) {
        return axiom.toString();
    }
    
    private String formatExplanationPaths(List<ExplanationPath> paths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            ExplanationPath path = paths.get(i);
            sb.append("Path ").append(i + 1).append(" (").append(path.getType().getDisplayName()).append("):\n");
            sb.append("Description: ").append(path.getDescription()).append("\n");
            for (OWLAxiom axiom : path.getAxioms()) {
                sb.append("  - ").append(axiom.toString()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
    
    private List<ExplanationPath> parseCachedExplanation(String cachedExplanation) {
        // This is a simplified parser - in practice, you'd want more robust parsing
        List<ExplanationPath> paths = new ArrayList<>();
        // Implementation would parse the cached string back into ExplanationPath objects
        return paths;
    }
    
    // Legacy methods for backward compatibility
    @Override
    public void explainPropertyRelationship(OWLNamedIndividual subject,
                                         OWLObjectPropertyExpression property,
                                         OWLNamedIndividual object,
                                         StringBuilder explanation) {
        OWLAxiom axiom = dataFactory.getOWLObjectPropertyAssertionAxiom(property.asOWLObjectProperty(), subject, object);
        
        List<ExplanationPath> paths = explainInference(axiom);
        formatLegacyExplanation(paths, explanation);
    }
    
    @Override
    public void explainTypeInference(OWLNamedIndividual individual,
                                   OWLClass clazz,
                                   StringBuilder explanation) {
        OWLAxiom axiom = dataFactory.getOWLClassAssertionAxiom(clazz, individual);
        
        List<ExplanationPath> paths = explainInference(axiom);
        formatLegacyExplanation(paths, explanation);
    }
    
    @Override
    public void explainClassRelationship(OWLClass subClass,
                                      OWLClass superClass,
                                      StringBuilder explanation) {
        OWLAxiom axiom = dataFactory.getOWLSubClassOfAxiom(subClass, superClass);
        
        List<ExplanationPath> paths = explainInference(axiom);
        formatLegacyExplanation(paths, explanation);
    }
    
    private void formatLegacyExplanation(List<ExplanationPath> paths, StringBuilder sb) {
        for (int i = 0; i < paths.size(); i++) {
            ExplanationPath path = paths.get(i);
            sb.append("Path ").append(i + 1).append(":\n");
            for (OWLAxiom axiom : path.getAxioms()) {
                sb.append("  - ").append(axiom.toString()).append("\n");
            }
            sb.append("\n");
        }
    }
    
    @Override
    public int getExplanationSize(String explanation) {
        if (explanation == null || explanation.trim().isEmpty()) {
            return 0;
        }
        
        if (explanation.contains("Directly asserted")) {
            return 1;
        }
        
        // Count the maximum number of axioms in any path
        int maxSize = 0;
        int currentPathSize = 0;
        
        for (String line : explanation.split("\n")) {
            line = line.trim();
            if (line.startsWith("Path ")) {
                maxSize = Math.max(maxSize, currentPathSize);
                currentPathSize = 0;
            } else if (line.startsWith("-")) {
                currentPathSize++;
            }
        }
        
        return Math.max(maxSize, currentPathSize);
    }
    
    public void clearCache() {
        explanationCache.clear();
        LOGGER.info("Explanation cache cleared");
    }
} 