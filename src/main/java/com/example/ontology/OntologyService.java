package com.example.ontology;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.util.ShortFormProvider;

public interface OntologyService {
    /**
     * Load an ontology from the given file path (e.g. a .ttl file).
     */
    void loadOntology(String filePath) throws Exception;

    /**
     * @return the loaded OWLOntology
     */
    OWLOntology getOntology();

    /**
     * @return the OWLDataFactory for creating axioms/entities
     */
    OWLDataFactory getDataFactory();

    /**
     * @return a ShortFormProvider for turning IRIs into human-readable names
     */
    ShortFormProvider getShortFormProvider();
}
