package com.example.application;

import com.example.ontology.OntologyService;
import com.example.query.QueryGenerationService;
import com.example.reasoning.ReasoningService;
import com.example.explanation.ExplanationService;
import com.example.output.OutputService;
import com.example.output.HybridOutputService;
import com.example.tracking.GlobalQueryTracker; // ADD THIS IMPORT
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PostConstruct; // ADD THIS IMPORT
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

    @Value("${output.csv.path:SPARQL_questions_1hop.csv}")
    private String csvOutputPath;

    @Value("${output.json.path:explanations_1hop.json}")
    private String jsonOutputPath;

    public static void main(String[] args) {
        SpringApplication.run(OwlSparqlGenerator.class, args);
    }

    // ADD THIS METHOD
    @PostConstruct
    public void initialize() {
        // Reset global tracker at the start of processing all ontologies
        GlobalQueryTracker.reset();
        LOGGER.info("Application initialized - Global query tracker reset");
    }

    @Override
    public void run(String... args) throws Exception {
        String ontologiesDir = args.length > 0
                ? args[0]
                : "src/main/resources/ontologies/family_1hop_tbox";
        Path dir = Path.of(ontologiesDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + ontologiesDir);
        }

        LOGGER.info("Initializing output service");
        outputService.initialize();

        int processedCount = 0; // ADD COUNTER

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.ttl")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String rootEntity = filename.substring(0, filename.lastIndexOf('.'));

                ((HybridOutputService) outputService).setCurrentRootEntity(rootEntity);

                LOGGER.info("Loading ontology: {}", filename);
                ontologyService.loadOntology(file.toString());

                OWLOntology ontology = ontologyService.getOntology();
                int tboxSize = countTBoxAxioms(ontology);
                int aboxSize = countABoxAxioms(ontology);
                LOGGER.info("Ontology sizes - TBox: {}, ABox: {}", tboxSize, aboxSize);

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

                processedCount++; // INCREMENT COUNTER

                // ADD PROGRESS LOGGING
                if (processedCount % 50 == 0) {
                    GlobalQueryTracker.logStats();
                    GlobalQueryTracker.logMemoryUsage();
                }
            }
        }

        LOGGER.info("Closing output service");
        outputService.close();

        // ADD FINAL STATS
        LOGGER.info("Processing complete! Processed {} ontologies", processedCount);
        GlobalQueryTracker.logStats();

        LOGGER.info("Done. CSV: {}, JSON: {}", csvOutputPath, jsonOutputPath);
    }

    private int countTBoxAxioms(OWLOntology ontology) {
        Set<AxiomType<?>> tboxAxiomTypes = new HashSet<>();
        tboxAxiomTypes.addAll(AxiomType.TBoxAxiomTypes);
        return (int) ontology.axioms()
                .filter(ax -> tboxAxiomTypes.contains(ax.getAxiomType()))
                .count();
    }

    private int countABoxAxioms(OWLOntology ontology) {
        Set<AxiomType<?>> aboxAxiomTypes = new HashSet<>();
        aboxAxiomTypes.addAll(AxiomType.ABoxAxiomTypes);
        return (int) ontology.axioms()
                .filter(ax -> aboxAxiomTypes.contains(ax.getAxiomType()))
                .count();
    }
}