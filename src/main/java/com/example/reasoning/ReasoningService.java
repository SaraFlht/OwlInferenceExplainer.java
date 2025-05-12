package com.example.reasoning;

import org.semanticweb.owlapi.model.OWLOntology;
import openllet.owlapi.OpenlletReasoner;

public interface ReasoningService {
    void initializeReasoner(OWLOntology ontology);
    boolean isConsistent();
    void reportUnsatisfiableClasses();
    OpenlletReasoner getReasoner();
}
