package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.List;

public class DirectAssertionStrategy extends BaseExplanationStrategy {
    
    @Override
    public boolean canExplain(OWLAxiom axiom) {
        return ontology.containsAxiom(axiom);
    }
    
    @Override
    public List<ExplanationPath> explain(OWLAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        
        if (ontology.containsAxiom(axiom)) {
            List<OWLAxiom> axiomList = new ArrayList<>();
            axiomList.add(axiom);
            
            String description = "Directly asserted: " + renderAxiom(axiom);
            paths.add(createPath(axiomList, description, ExplanationType.DIRECT_ASSERTION));
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 1; // Highest priority - check direct assertions first
    }
} 