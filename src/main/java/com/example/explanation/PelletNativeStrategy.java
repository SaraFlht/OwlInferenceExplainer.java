package com.example.explanation;

import openllet.owlapi.explanation.PelletExplanation;
import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PelletNativeStrategy extends BaseExplanationStrategy {
    
    private PelletExplanation pelletExplanation;
    
    public void setPelletExplanation(PelletExplanation pelletExplanation) {
        this.pelletExplanation = pelletExplanation;
    }
    
    @Override
    public boolean canExplain(OWLAxiom axiom) {
        // Can explain any axiom that Pellet can explain
        return true;
    }
    
    @Override
    public List<ExplanationPath> explain(OWLAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        
        if (pelletExplanation == null) {
            LOGGER.warn("PelletExplanation not initialized");
            return paths;
        }
        
        try {
            Set<Set<OWLAxiom>> pelletExplanations = new HashSet<>();
            
            if (axiom instanceof OWLClassAssertionAxiom) {
                OWLClassAssertionAxiom classAssertion = (OWLClassAssertionAxiom) axiom;
                Set<OWLAxiom> singleExpl = pelletExplanation.getInstanceExplanation(
                        classAssertion.getIndividual().asOWLNamedIndividual(),
                        classAssertion.getClassExpression().asOWLClass());
                if (singleExpl != null && !singleExpl.isEmpty()) {
                    pelletExplanations.add(singleExpl);
                }
            } else if (axiom instanceof OWLSubClassOfAxiom) {
                OWLSubClassOfAxiom subClassAxiom = (OWLSubClassOfAxiom) axiom;
                Set<OWLAxiom> singleExpl = pelletExplanation.getSubClassExplanation(
                        subClassAxiom.getSubClass().asOWLClass(),
                        subClassAxiom.getSuperClass().asOWLClass());
                if (singleExpl != null && !singleExpl.isEmpty()) {
                    pelletExplanations.add(singleExpl);
                }
            } else {
                Set<OWLAxiom> singleExpl = pelletExplanation.getEntailmentExplanation(axiom);
                if (singleExpl != null && !singleExpl.isEmpty()) {
                    pelletExplanations.add(singleExpl);
                }
            }
            
            // Convert Pellet explanations to our format
            for (Set<OWLAxiom> pelletExpl : pelletExplanations) {
                List<OWLAxiom> axiomList = new ArrayList<>(pelletExpl);
                String description = "Pellet native explanation with " + axiomList.size() + " axioms";
                paths.add(createPath(axiomList, description, ExplanationType.PELLET_NATIVE));
            }
            
        } catch (Exception e) {
            LOGGER.debug("Error getting Pellet explanations for: " + axiom, e);
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 100; // Lowest priority - use as fallback
    }
} 