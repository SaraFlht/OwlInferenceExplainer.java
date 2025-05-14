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

        // Create and configure reasoner factory
        OpenlletReasonerFactory factory = new OpenlletReasonerFactory();

        // Create the reasoner
        reasoner = factory.createReasoner(ontology);

        // Configure the knowledge base for explanations
        try {
            LOGGER.info("Configuring knowledge base for explanations");
            reasoner.getKB().setDoExplanation(true);

            // Optional: Increase tracing depth for more thorough explanations
            // Access through reflection since the API might not expose this directly
            java.lang.reflect.Method setTraceDepthMethod =
                    reasoner.getKB().getClass().getMethod("setExpDepth", int.class);
            if (setTraceDepthMethod != null) {
                setTraceDepthMethod.invoke(reasoner.getKB(), 15); // Increased from default
                LOGGER.info("Set explanation tracing depth to 15");
            }
        } catch (Exception e) {
            LOGGER.warn("Could not configure explanations: {}", e.getMessage());
        }

        // Prepare reasoner before precomputing
        LOGGER.info("Preparing reasoner");
        reasoner.prepareReasoner();

        // Precompute inferences
        LOGGER.info("Precomputing inferences");
        reasoner.precomputeInferences(
                InferenceType.CLASS_HIERARCHY,
                InferenceType.CLASS_ASSERTIONS,
                InferenceType.OBJECT_PROPERTY_ASSERTIONS,
                InferenceType.DATA_PROPERTY_ASSERTIONS,
                InferenceType.OBJECT_PROPERTY_HIERARCHY,
                InferenceType.DATA_PROPERTY_HIERARCHY, // Added
                InferenceType.DISJOINT_CLASSES,        // Added
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

    /**
     * Flush the reasoner's internal caches to free memory
     * Useful for very large ontologies
     */
    public void flushReasoner() {
        LOGGER.info("Flushing reasoner caches");
        reasoner.flush();
    }

    /**
     * Set maximum number of explanations to find per inference
     * @param maxExplanations The maximum number of explanations
     */
    public void setMaxExplanations(int maxExplanations) {
        try {
            java.lang.reflect.Method setMaxMethod =
                    reasoner.getKB().getClass().getMethod("setMaxExplanations", int.class);
            if (setMaxMethod != null) {
                setMaxMethod.invoke(reasoner.getKB(), maxExplanations);
                LOGGER.info("Set maximum explanations to {}", maxExplanations);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not set maximum explanations: {}", e.getMessage());
        }
    }
}