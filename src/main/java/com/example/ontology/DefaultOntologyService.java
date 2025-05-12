package com.example.ontology;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;

import java.io.File;

public class DefaultOntologyService implements OntologyService {
    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory dataFactory;
    private ShortFormProvider shortFormProvider;

    public DefaultOntologyService() {
        // Create the manager and data factory up front
        this.manager = OWLManager.createOWLOntologyManager();
        this.dataFactory = manager.getOWLDataFactory();
        this.shortFormProvider = new SimpleShortFormProvider();
    }

    @Override
    public void loadOntology(String filePath) throws Exception {
        // Load the ontology document (TTL, RDF/XML, etc.) from disk
        this.ontology = manager.loadOntologyFromOntologyDocument(new File(filePath));

        // Optional: log counts
        System.out.printf(
                "Loaded ontology: %d classes, %d individuals, %d object properties%n",
                ontology.getClassesInSignature().size(),
                ontology.getIndividualsInSignature().size(),
                ontology.getObjectPropertiesInSignature().size()
        );
    }

    @Override
    public OWLOntology getOntology() {
        return ontology;
    }

    @Override
    public OWLDataFactory getDataFactory() {
        return dataFactory;
    }

    @Override
    public ShortFormProvider getShortFormProvider() {
        return shortFormProvider;
    }
}
