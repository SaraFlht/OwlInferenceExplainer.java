package com.example.ontology;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class DefaultOntologyService implements OntologyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOntologyService.class);

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
        LOGGER.info("Loading ontology from {}", filePath);

        // First, remove any existing ontologies to avoid ID conflicts
        if (ontology != null) {
            manager.removeOntology(ontology);
            ontology = null;
        }

        try {
            // Load the ontology document (TTL, RDF/XML, etc.) from disk
            this.ontology = manager.loadOntologyFromOntologyDocument(new File(filePath));

            LOGGER.info("Loaded ontology: {} classes, {} individuals, {} object properties",
                    ontology.getClassesInSignature().size(),
                    ontology.getIndividualsInSignature().size(),
                    ontology.getObjectPropertiesInSignature().size());
        } catch (OWLOntologyAlreadyExistsException e) {
            // Handle the case where the ontology ID already exists
            LOGGER.warn("Ontology with same ID already exists, removing it and trying again");
            OWLOntologyID id = e.getOntologyID();
            if (manager.contains(id)) {
                manager.removeOntology(manager.getOntology(id));
            }

            // Try loading again
            this.ontology = manager.loadOntologyFromOntologyDocument(new File(filePath));
            LOGGER.info("Loaded ontology (second attempt): {} classes, {} individuals, {} object properties",
                    ontology.getClassesInSignature().size(),
                    ontology.getIndividualsInSignature().size(),
                    ontology.getObjectPropertiesInSignature().size());
        }
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