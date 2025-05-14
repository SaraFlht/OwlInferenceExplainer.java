package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLClass;

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
}