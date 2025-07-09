package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;

public class ExplanationServiceFactory {
    
    /**
     * Create a comprehensive explanation service with all available strategies
     */
    public static ComprehensiveExplanationService createComprehensiveService() {
        ComprehensiveExplanationService service = new ComprehensiveExplanationService();
        
        // Add strategies in priority order (lower numbers = higher priority)
        service.addStrategy(new DirectAssertionStrategy());           // Priority 1
        service.addStrategy(new SubPropertyStrategy());              // Priority 10
        service.addStrategy(new InversePropertyStrategy());          // Priority 20
        service.addStrategy(new EquivalentPropertyStrategy());       // Priority 25
        service.addStrategy(new PropertyChainStrategy());           // Priority 30
        service.addStrategy(new EquivalentClassStrategy());         // Priority 35
        service.addStrategy(new ClassHierarchyStrategy());          // Priority 40
        service.addStrategy(new DomainRangeStrategy());             // Priority 45
        service.addStrategy(new FunctionalPropertyStrategy());       // Priority 50
        service.addStrategy(new PelletNativeStrategy());            // Priority 100 (fallback)
        
        return service;
    }
    
    /**
     * Create a comprehensive explanation service and initialize it with the reasoner
     */
    public static ComprehensiveExplanationService createAndInitializeService(OpenlletReasoner reasoner) {
        ComprehensiveExplanationService service = createComprehensiveService();
        service.initializeExplanations(reasoner);
        return service;
    }
} 