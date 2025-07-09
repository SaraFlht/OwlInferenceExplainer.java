// JenaOntologyService.java
package com.example.ontology;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;

public class JenaOntologyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JenaOntologyService.class);

    private OntModel ontModel;

    public OntModel loadOntology(String filePath) throws Exception {
        LOGGER.info("Loading ontology from {} using Jena", filePath);

        // Create ontology model
        ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        // Load the ontology file
        try (InputStream in = new FileInputStream(filePath)) {
            // Determine format based on file extension
            String format = determineFormat(filePath);
            ontModel.read(in, null, format);

            LOGGER.info("Loaded ontology with {} statements", ontModel.size());
            return ontModel;
        } catch (Exception e) {
            LOGGER.error("Error loading ontology: {}", e.getMessage(), e);
            throw e;
        }
    }

    private String determineFormat(String filePath) {
        String lowerPath = filePath.toLowerCase();
        if (lowerPath.endsWith(".ttl")) {
            return "TURTLE";
        } else if (lowerPath.endsWith(".rdf") || lowerPath.endsWith(".xml")) {
            return "RDF/XML";
        } else if (lowerPath.endsWith(".n3")) {
            return "N3";
        } else if (lowerPath.endsWith(".nt")) {
            return "N-TRIPLES";
        } else if (lowerPath.endsWith(".owl")) {
            return "RDF/XML"; // Default for .owl files
        } else {
            return "RDF/XML"; // Default format
        }
    }

    public OntModel getOntModel() {
        return ontModel;
    }
}