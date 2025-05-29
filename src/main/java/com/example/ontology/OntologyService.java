package com.example.ontology;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.util.ShortFormProvider;

public interface OntologyService {
    void loadOntology(String filePath) throws Exception;
    OWLOntology getOntology();
    OWLDataFactory getDataFactory();
    ShortFormProvider getShortFormProvider();

    // NEW METHODS FOR CONSISTENT IRI HANDLING
    String getFullIRI(OWLEntity entity);
    String getDisplayName(OWLEntity entity);
    String normalizeIRI(String iri);
}