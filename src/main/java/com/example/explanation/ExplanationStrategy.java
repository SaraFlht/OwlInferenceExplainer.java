package com.example.explanation;

import org.semanticweb.owlapi.model.OWLAxiom;
import java.util.List;

public interface ExplanationStrategy {
    /**
     * Check if this strategy can explain the given axiom
     */
    boolean canExplain(OWLAxiom axiom);
    
    /**
     * Generate explanations for the given axiom
     */
    List<ExplanationPath> explain(OWLAxiom axiom);
    
    /**
     * Get the priority of this strategy (lower numbers = higher priority)
     */
    default int getPriority() {
        return 100;
    }
} 