package com.example.application;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.example.ontology.OntologyService;
import com.example.reasoning.ReasoningService;
import com.example.explanation.ExplanationService;
import com.example.query.QueryGenerationService;
import com.example.output.OutputService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication(scanBasePackages = "com.example")
public class OwlSparqlGenerator implements CommandLineRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(OwlSparqlGenerator.class);

    @Autowired private OntologyService ontologyService;
    @Autowired private ReasoningService reasoningService;
    @Autowired private ExplanationService explanationService;
    @Autowired private QueryGenerationService queryService;
    @Autowired private OutputService outputService;

    @Value("${output.csv.path:SPARQL_questions.csv}")
    private String csvOutputPath;

    @Value("${output.json.path:explanations.json}")
    private String jsonOutputPath;

    public static void main(String[] args) {
        SpringApplication.run(OwlSparqlGenerator.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // Use the first arg if present, otherwise fall back to the bundled resource
        String ontologyPath;
        if (args.length >= 1) {
            ontologyPath = args[0];
        } else {
            LOGGER.info("No ontology path given; using default from resources.");
            ontologyPath = "src/main/resources/Thing_ada_rachel_heath_1868.ttl";
        }

        LOGGER.info("Loading ontology from: {}", ontologyPath);
        ontologyService.loadOntology(ontologyPath);

        LOGGER.info("Initializing reasoner");
        reasoningService.initializeReasoner(ontologyService.getOntology());
        if (!reasoningService.isConsistent()) {
            LOGGER.warn("WARNING: Ontology is inconsistent");
            reasoningService.reportUnsatisfiableClasses();
        }

        LOGGER.info("Initializing explanation service");
        explanationService.initializeExplanations(reasoningService.getReasoner());

        LOGGER.info("Initializing query service");
        queryService.initialize(
                ontologyService.getOntology(),
                reasoningService.getReasoner(),
                explanationService,
                ontologyService.getDataFactory()
        );

        LOGGER.info("Initializing output service");
        outputService.initialize();

        // Generate all queries
        LOGGER.info("Generating property assertion queries");
        queryService.generatePropertyAssertionQueries(outputService);

        LOGGER.info("Generating membership queries");
        queryService.generateMembershipQueries(outputService);

        LOGGER.info("Generating subsumption queries");
        queryService.generateSubsumptionQueries(outputService);

        outputService.close();
        LOGGER.info("Done. Queries written to CSV: {}, Explanations written to JSON: {}",
                csvOutputPath, jsonOutputPath);
    }
}