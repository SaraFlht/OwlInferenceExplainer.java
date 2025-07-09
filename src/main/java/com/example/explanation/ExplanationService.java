package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLAxiom;

import java.util.List;

public interface ExplanationService {
    void initializeExplanations(OpenlletReasoner reasoner);
    void explainPropertyRelationship(OWLNamedIndividual subject,
                                     OWLObjectPropertyExpression property,
                                     OWLNamedIndividual object,
                                     StringBuilder explanation);
    void explainTypeInference(OWLNamedIndividual individual,
                              OWLClass clazz,
                              StringBuilder explanation);
    void explainClassRelationship(OWLClass subClass,
                                  OWLClass superClass,
                                  StringBuilder explanation);
    int getExplanationSize(String explanation);
    
    /**
     * Generate explanations for a specific inference
     * Returns a list of explanation paths, each path being a complete logical chain
     */
    List<ExplanationPath> explainInference(OWLAxiom targetAxiom);
    
    /**
     * Validate explanation against known correct result
     */
    boolean validateExplanation(OWLAxiom targetAxiom, List<ExplanationPath> explanations);
}