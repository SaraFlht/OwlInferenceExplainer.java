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
        this.manager = OWLManager.createOWLOntologyManager();
        this.dataFactory = manager.getOWLDataFactory();
        this.shortFormProvider = new SimpleShortFormProvider();
    }

    @Override
    public void loadOntology(String filePath) throws Exception {
        LOGGER.info("Loading ontology from {}", filePath);

        if (ontology != null) {
            manager.removeOntology(ontology);
            ontology = null;
        }

        try {
            this.ontology = manager.loadOntologyFromOntologyDocument(new File(filePath));
            LOGGER.info("Loaded ontology: {} classes, {} individuals, {} object properties",
                    ontology.getClassesInSignature().size(),
                    ontology.getIndividualsInSignature().size(),
                    ontology.getObjectPropertiesInSignature().size());
        } catch (OWLOntologyAlreadyExistsException e) {
            LOGGER.warn("Ontology with same ID already exists, removing it and trying again");
            OWLOntologyID id = e.getOntologyID();
            if (manager.contains(id)) {
                manager.removeOntology(manager.getOntology(id));
            }
            this.ontology = manager.loadOntologyFromOntologyDocument(new File(filePath));
        }
    }

    // ADD THESE NEW METHODS
    /**
     * Always returns the full IRI in angle brackets, ready for SPARQL
     */
    public String getFullIRI(OWLEntity entity) {
        return "<" + entity.getIRI().toString() + ">";
    }

    /**
     * Gets the short form (for display purposes only)
     */
    public String getDisplayName(OWLEntity entity) {
        return shortFormProvider.getShortForm(entity);
    }

    /**
     * Normalizes any IRI string to full format with angle brackets
     */
    public String normalizeIRI(String iri) {
        if (iri == null || iri.isEmpty()) return "";

        String cleaned = iri.trim();
        if (cleaned.startsWith("<") && cleaned.endsWith(">")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return "<" + cleaned + ">";
        }

        // Get base IRI from ontology
        IRI ontologyIRI = ontology.getOntologyID().getOntologyIRI().orElse(null);
        if (ontologyIRI != null) {
            String baseIRI = ontologyIRI.toString();
            if (!baseIRI.endsWith("#") && !baseIRI.endsWith("/")) {
                baseIRI += "#";
            }
            return "<" + baseIRI + cleaned + ">";
        }

        return "<" + cleaned + ">";
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