import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.stream.Collectors;

public class SmallOntologyExtractor {

    public static void main(String[] args) throws Exception {
        String inputFile = "src/main/resources/OWL2DL-1.owl";
        String outputDir1hop = "src/main/resources/OWL2DL-1_1hop/";
        String outputDir2hop = "src/main/resources/OWL2DL-1_2hop/";

        // Create output directories
        new File(outputDir1hop).mkdirs();
        new File(outputDir2hop).mkdirs();

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();

        // Load ontology
        System.out.println("Loading ontology...");
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new File(inputFile));
        System.out.println("Loaded " + ontology.getAxiomCount() + " axioms");

        // Get all individuals
        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();
        System.out.println("Found " + individuals.size() + " individuals");

        // Extract TBox once
        Set<OWLAxiom> tboxAxioms = extractTBox(ontology);
        System.out.println("TBox contains " + tboxAxioms.size() + " axioms");

        // Process 1-hop extractions
        System.out.println("\nProcessing 1-hop extractions...");
        int[] results1hop = processExtractions(ontology, individuals, tboxAxioms, 1, outputDir1hop, manager, factory);

        // Process 2-hop extractions
        System.out.println("\nProcessing 2-hop extractions...");
        int[] results2hop = processExtractions(ontology, individuals, tboxAxioms, 2, outputDir2hop, manager, factory);

        // Final summary
        System.out.println("\n" + "=".repeat(50));
        System.out.println("EXTRACTION COMPLETE");
        System.out.println("=".repeat(50));
        System.out.println("1-hop: " + results1hop[0] + " successful, " + results1hop[1] + " failed");
        System.out.println("2-hop: " + results2hop[0] + " successful, " + results2hop[1] + " failed");
        System.out.println("Output directories:");
        System.out.println("  1-hop: " + outputDir1hop);
        System.out.println("  2-hop: " + outputDir2hop);
    }

    private static int[] processExtractions(OWLOntology ontology, Set<OWLNamedIndividual> individuals,
                                            Set<OWLAxiom> tboxAxioms, int hops, String outputDir,
                                            OWLOntologyManager manager, OWLDataFactory factory) {
        int processed = 0;
        int failed = 0;
        long startTime = System.currentTimeMillis();

        for (OWLNamedIndividual individual : individuals) {
            try {
                // Extract ABox for this individual (n-hop)
                Set<OWLAxiom> aboxAxioms = extractABoxForIndividual(ontology, individual, hops);

                // Combine TBox and ABox
                Set<OWLAxiom> allAxioms = new HashSet<>(tboxAxioms);
                allAxioms.addAll(aboxAxioms);

                // Create new ontology
                IRI moduleIRI = IRI.create("http://example.org/extracted/" + hops + "hop/" + getLocalName(individual.getIRI()));
                OWLOntology moduleOntology = manager.createOntology(allAxioms, moduleIRI);

                // Add ontology annotations
                addOntologyMetadata(manager, factory, moduleOntology, hops);

                // Generate filename
                String individualName = getLocalName(individual.getIRI());
                Set<String> types = getIndividualTypes(ontology, individual);
                String typePrefix = types.isEmpty() ? "Thing" :
                        types.stream().limit(2).collect(Collectors.joining("_"));

                String filename = sanitizeFilename(typePrefix + "_" + individualName) + ".ttl";
                File outputFile = new File(outputDir, filename);

                // Save as Turtle
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    manager.saveOntology(moduleOntology, new TurtleDocumentFormat(), fos);
                }

                // Cleanup
                manager.removeOntology(moduleOntology);

                processed++;
                if (processed % 100 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    System.out.println("Processed " + processed + " individuals (" + hops + "-hop) in " + (elapsed/1000) + "s");
                }

            } catch (Exception e) {
                failed++;
                System.err.println("Error processing " + individual + " (" + hops + "-hop): " + e.getMessage());
                if (failed <= 5) {
                    e.printStackTrace();
                }
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("Completed " + hops + "-hop processing: " + processed + " successful, " + failed + " failed in " + (totalTime/1000) + "s");

        return new int[]{processed, failed};
    }

    private static Set<OWLAxiom> extractTBox(OWLOntology ontology) {
        Set<OWLAxiom> tboxAxioms = new HashSet<>();
        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();

        for (OWLAxiom axiom : ontology.getAxioms()) {
            // Skip axioms that directly reference individuals
            if (Collections.disjoint(axiom.getIndividualsInSignature(), individuals)) {
                tboxAxioms.add(axiom);
            }
        }

        return tboxAxioms;
    }

    private static Set<OWLAxiom> extractABoxForIndividual(OWLOntology ontology,
                                                          OWLNamedIndividual individual, int hops) {
        Set<OWLAxiom> aboxAxioms = new HashSet<>();
        Set<OWLNamedIndividual> visited = new HashSet<>();
        Set<OWLNamedIndividual> currentLevel = new HashSet<>();
        currentLevel.add(individual);

        for (int hop = 0; hop < hops; hop++) {
            Set<OWLNamedIndividual> nextLevel = new HashSet<>();

            for (OWLNamedIndividual ind : currentLevel) {
                if (visited.contains(ind)) continue;
                visited.add(ind);

                // Get all axioms referencing this individual
                Set<OWLAxiom> referencingAxioms = ontology.getReferencingAxioms(ind);
                aboxAxioms.addAll(referencingAxioms);

                // Find connected individuals for next hop
                for (OWLAxiom axiom : referencingAxioms) {
                    Set<OWLNamedIndividual> axiomsIndividuals = axiom.getIndividualsInSignature();
                    for (OWLNamedIndividual otherInd : axiomsIndividuals) {
                        if (!visited.contains(otherInd) && !otherInd.equals(ind)) {
                            nextLevel.add(otherInd);
                        }
                    }
                }
            }

            currentLevel = nextLevel;

            // If no more individuals to explore, break early
            if (currentLevel.isEmpty()) {
                break;
            }
        }

        return aboxAxioms;
    }

    private static void addOntologyMetadata(OWLOntologyManager manager, OWLDataFactory factory,
                                            OWLOntology ontology, int hops) throws OWLOntologyChangeException {
        // Add label
        OWLAnnotation labelAnnotation = factory.getOWLAnnotation(
                factory.getRDFSLabel(),
                factory.getOWLLiteral("OWL2Bench Individual Subgraph (" + hops + "-hop)", "en")
        );
        manager.applyChange(new AddOntologyAnnotation(ontology, labelAnnotation));

        // Add comment
        OWLAnnotation commentAnnotation = factory.getOWLAnnotation(
                factory.getRDFSComment(),
                factory.getOWLLiteral("A Benchmark for OWL 2 Ontologies. Individual-centered " + hops + "-hop extraction.")
        );
        manager.applyChange(new AddOntologyAnnotation(ontology, commentAnnotation));

        // Add version info
        OWLAnnotation versionAnnotation = factory.getOWLAnnotation(
                factory.getOWLVersionInfo(),
                factory.getOWLLiteral("OWL2Bench, ver 10 April, 2020")
        );
        manager.applyChange(new AddOntologyAnnotation(ontology, versionAnnotation));
    }

    private static Set<String> getIndividualTypes(OWLOntology ontology, OWLNamedIndividual individual) {
        // Convert Stream to Collection and then process
        return EntitySearcher.getTypes(individual, ontology)
                .filter(cls -> cls instanceof OWLClass)
                .map(cls -> getLocalName(((OWLClass) cls).getIRI()))
                .filter(name -> !name.equals("NamedIndividual"))
                .collect(Collectors.toSet());
    }

    private static String getLocalName(IRI iri) {
        String iriString = iri.toString();
        if (iriString.contains("#")) {
            return iriString.substring(iriString.indexOf("#") + 1);
        } else if (iriString.contains("/")) {
            return iriString.substring(iriString.lastIndexOf("/") + 1);
        }
        return iriString;
    }

    private static String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_")
                .substring(0, Math.min(filename.length(), 100));
    }
}