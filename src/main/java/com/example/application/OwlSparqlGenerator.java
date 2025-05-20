package com.example.application;

import com.example.ontology.OntologyService;
import com.example.query.QueryGenerationService;
import com.example.reasoning.ReasoningService;
import com.example.explanation.ExplanationService;
import com.example.output.OutputService;
import com.example.output.HybridOutputService;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@SpringBootApplication(scanBasePackages = "com.example")
public class OwlSparqlGenerator implements CommandLineRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(OwlSparqlGenerator.class);

    @Autowired
    private OntologyService ontologyService;
    @Autowired
    private ReasoningService reasoningService;
    @Autowired
    private ExplanationService explanationService;
    @Autowired
    private QueryGenerationService queryService;
    @Autowired
    private OutputService outputService;

    @Value("${output.csv.path:SPARQL_questions.csv}")
    private String csvOutputPath;

    @Value("${output.json.path:explanations.json}")
    private String jsonOutputPath;

    public static void main(String[] args) {
        SpringApplication.run(OwlSparqlGenerator.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // 1) Directory containing all your .ttl files
        String ontologiesDir = args.length > 0
                ? args[0]
                : "src/main/resources/ontologies/family_1hop_tbox";
        Path dir = Path.of(ontologiesDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + ontologiesDir);
        }

        // 2) Initialize one CSV + JSON writer - ONLY ONCE before the loop
        LOGGER.info("Initializing output service");
        outputService.initialize();

        // 3) Loop over each ontology file
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.ttl")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String rootEntity = filename.substring(0, filename.lastIndexOf('.'));

                // Tell HybridOutputService which file we're on
                ((HybridOutputService) outputService).setCurrentRootEntity(rootEntity);

                // Reset processed queries for the new ontology
                ((HybridOutputService) outputService).resetProcessedQueries();

                LOGGER.info("Loading ontology: {}", filename);
                ontologyService.loadOntology(file.toString());

                // Calculate TBox and ABox sizes
                OWLOntology ontology = ontologyService.getOntology();
                int tboxSize = countTBoxAxioms(ontology);
                int aboxSize = countABoxAxioms(ontology);
                LOGGER.info("Ontology sizes - TBox: {}, ABox: {}", tboxSize, aboxSize);

                // Set sizes in the output service
                ((HybridOutputService) outputService).setOntologySizes(tboxSize, aboxSize);

                LOGGER.info("Initializing reasoner for {}", filename);
                reasoningService.initializeReasoner(ontology);
                if (!reasoningService.isConsistent()) {
                    LOGGER.warn("Ontology {} is inconsistent", filename);
                    reasoningService.reportUnsatisfiableClasses();
                }

                LOGGER.info("Initializing explanation service");
                explanationService.initializeExplanations(reasoningService.getReasoner());

                LOGGER.info("Initializing query service");
                queryService.initialize(
                        ontology,
                        reasoningService.getReasoner(),
                        explanationService,
                        ontologyService.getDataFactory()
                );

                LOGGER.info("Generating queries for {}", filename);
                queryService.generatePropertyAssertionQueries(outputService);
                queryService.generateMembershipQueries(outputService);
                queryService.generateSubsumptionQueries(outputService);
            }
        }

        // 4) Close once to flush all CSV rows + write grouped JSON
        LOGGER.info("Closing output service");
        outputService.close();

        LOGGER.info("Done. CSV: {}, JSON: {}", csvOutputPath, jsonOutputPath);
    }

    /**
     * Count the number of TBox axioms in the ontology
     */
    private int countTBoxAxioms(OWLOntology ontology) {
        // Get all TBox axiom types
        Set<AxiomType<?>> tboxAxiomTypes = new HashSet<>();
        tboxAxiomTypes.addAll(AxiomType.TBoxAxiomTypes);

        // Count axioms of TBox types
        return (int) ontology.axioms()
                .filter(ax -> tboxAxiomTypes.contains(ax.getAxiomType()))
                .count();
    }

    /**
     * Count the number of ABox axioms in the ontology
     */
    private int countABoxAxioms(OWLOntology ontology) {
        // Get all ABox axiom types
        Set<AxiomType<?>> aboxAxiomTypes = new HashSet<>();
        aboxAxiomTypes.addAll(AxiomType.ABoxAxiomTypes);

        // Count axioms of ABox types
        return (int) ontology.axioms()
                .filter(ax -> aboxAxiomTypes.contains(ax.getAxiomType()))
                .count();
    }
}