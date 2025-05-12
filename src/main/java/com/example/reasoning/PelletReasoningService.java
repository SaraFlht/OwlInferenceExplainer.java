package com.example.reasoning;

import openllet.owlapi.OpenlletReasoner;
import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PelletReasoningService implements ReasoningService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PelletReasoningService.class);
    private OpenlletReasoner reasoner;
    private OWLOntology ontology;

    @Override
    public void initializeReasoner(OWLOntology ontology) {
        this.ontology = ontology;
        LOGGER.info("Initializing Pellet reasoner");
        reasoner = new OpenlletReasonerFactory().createReasoner(ontology);
        LOGGER.info("Precomputing inferences");
        reasoner.precomputeInferences(
                InferenceType.CLASS_HIERARCHY,
                InferenceType.CLASS_ASSERTIONS,
                InferenceType.OBJECT_PROPERTY_ASSERTIONS,
                InferenceType.DATA_PROPERTY_ASSERTIONS,
                InferenceType.OBJECT_PROPERTY_HIERARCHY,
                InferenceType.SAME_INDIVIDUAL
        );
        LOGGER.info("Reasoning initialization complete");
    }

    @Override
    public boolean isConsistent() {
        return reasoner.isConsistent();
    }

    @Override
    public void reportUnsatisfiableClasses() {
        ontology.getClassesInSignature().stream()
                .filter(cls -> !reasoner.isSatisfiable(cls))
                .forEach(cls -> LOGGER.warn("Unsatisfiable class: {}", cls));
    }

    @Override
    public OpenlletReasoner getReasoner() {
        return reasoner;
    }
}
