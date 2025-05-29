package com.example.query;

import com.example.tracking.GlobalQueryTracker;
import com.example.ontology.OntologyService;
import com.example.output.OutputService;
import com.example.explanation.ExplanationService;
import com.example.output.HybridOutputService;
import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

/**
 * SPARQL query generator with consistent IRI handling and global deduplication
 */
public class SparqlQueryGenerationService implements QueryGenerationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SparqlQueryGenerationService.class);

    // Task type constants
    private static final String PROPERTY_ASSERTION = "Property Assertion";
    private static final String MEMBERSHIP = "Membership";
    private static final String SUBSUMPTION = "Subsumption";
    private static final String DIRECTLY_ASSERTED = "Directly asserted";

    private OWLOntology ontology;
    private OpenlletReasoner reasoner;
    private ExplanationService explanationService;
    private OWLDataFactory dataFactory;
    private OntologyService ontologyService;

    public void setOntologyService(OntologyService ontologyService) {
        this.ontologyService = ontologyService;
    }

    @Override
    public void initialize(OWLOntology ontology,
                           OpenlletReasoner reasoner,
                           ExplanationService explanationService,
                           OWLDataFactory dataFactory) {
        this.ontology = ontology;
        this.reasoner = reasoner;
        this.explanationService = explanationService;
        this.dataFactory = dataFactory;
        LOGGER.info("Initialized query generation service");
    }

    @Override
    public void generatePropertyAssertionQueries(OutputService outputService) {
        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();
        Set<OWLObjectProperty> properties = ontology.getObjectPropertiesInSignature();

        // For multi-choice queries
        Map<String, Map<String, String>> objectQueryAnswers = new ConcurrentHashMap<>();
        Map<String, OWLObjectProperty> queryProperties = new ConcurrentHashMap<>();

        individuals.parallelStream().forEach(subject -> {
            for (OWLObjectProperty property : properties) {
                NodeSet<OWLNamedIndividual> objects = reasoner.getObjectPropertyValues(subject, property);

                if (objects.isEmpty()) continue;

                // Multi-choice query - only create once per subject-property pair
                String objectQuery = "SELECT ?object WHERE { " +
                        ontologyService.getFullIRI(subject) + " " +
                        ontologyService.getFullIRI(property) + " ?object }";

                boolean queryAdded = false;

                for (OWLNamedIndividual object : objects.entities().collect(Collectors.toSet())) {
                    if (subject.equals(object)) continue;

                    String globalTripleKey = createGlobalTripleKey(subject, property, object);
                    if (!GlobalQueryTracker.addTriple(globalTripleKey)) {
                        continue;
                    }

                    boolean asserted = ontology.containsAxiom(
                            dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, object)
                    );

                    // Generate BINARY ASK query
                    generateBinaryAskQuery(subject, property, object, asserted, outputService);

                    // Add to multi-choice query only once
                    if (!queryAdded && GlobalQueryTracker.addQuery(objectQuery)) {
                        queryAdded = true;
                        objectQueryAnswers.computeIfAbsent(objectQuery, k -> new ConcurrentHashMap<>());
                        queryProperties.putIfAbsent(objectQuery, property);
                    }

                    // Add answer to multi-choice query
                    if (queryAdded) {
                        StringBuilder explSb = new StringBuilder();
                        if (asserted) {
                            explSb.append(DIRECTLY_ASSERTED);
                        } else {
                            explanationService.explainPropertyRelationship(subject, property, object, explSb);
                        }
                        String explanation = explSb.toString().trim();

                        objectQueryAnswers.get(objectQuery).put(
                                ontologyService.getFullIRI(object),
                                explanation
                        );
                    }
                }
            }
        });

        // Process multi-choice queries with the original task type
        processBatchQueries(objectQueryAnswers, queryProperties, PROPERTY_ASSERTION, outputService);
    }

    private void generateBinaryAskQuery(OWLNamedIndividual subject,
                                        OWLObjectProperty property,
                                        OWLNamedIndividual object,
                                        boolean asserted,
                                        OutputService outputService) {
        // Create proper ASK query (requirement #4)
        String sparql = "ASK WHERE { " +
                ontologyService.getFullIRI(subject) + " " +
                ontologyService.getFullIRI(property) + " " +
                ontologyService.getFullIRI(object) + " }";

        // Check global duplicates
        if (!GlobalQueryTracker.addQuery(sparql)) {
            return;
        }

        String explanation;
        int size;

        if (asserted) {
            explanation = DIRECTLY_ASSERTED;
            size = 1;
        } else {
            StringBuilder sb = new StringBuilder();
            explanationService.explainPropertyRelationship(subject, property, object, sb);
            explanation = sb.toString().trim();
            size = explanationService.getExplanationSize(explanation);
            if (size == 0 && !explanation.isEmpty()) size = 1;
        }

        try {
            outputService.writeBinaryQuery(
                    PROPERTY_ASSERTION,
                    sparql,  // Normalized ASK query
                    ontologyService.getDisplayName(property),
                    "true",  // ASK queries always have true/false answers
                    explanation,
                    size
            );
        } catch (IOException e) {
            LOGGER.error("Failed to write binary query for SPARQL: " + sparql, e);
        }
    }

    @Override
    public void generateMembershipQueries(OutputService outputService) {
        Map<String, Map<String, String>> typeQueryAnswers = new ConcurrentHashMap<>();

        ontology.getIndividualsInSignature().parallelStream().forEach(individual -> {
            Set<OWLClass> inferredTypes = reasoner.getTypes(individual, false)
                    .entities()
                    .collect(Collectors.toSet());

            // Only generate object queries - asking for types of individuals
            String multiQuery = "SELECT ?class WHERE { " +
                    ontologyService.getFullIRI(individual) + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?class }";

            if (!GlobalQueryTracker.addQuery(multiQuery)) {
                return; // Skip duplicate
            }

            Set<OWLClassExpression> assertedTypes = EntitySearcher.getTypes(individual, ontology)
                    .collect(Collectors.toSet());

            for (OWLClass type : inferredTypes) {
                if (type.isOWLThing() || type.isOWLNothing()) continue;

                // Create global triple key
                String globalTripleKey = createGlobalTripleKey(individual, null, type);

                if (!GlobalQueryTracker.addTriple(globalTripleKey)) {
                    continue;
                }

                boolean isAsserted = assertedTypes.contains(type);

                // Generate binary ASK query
                String binaryQuery = "ASK WHERE { " +
                        ontologyService.getFullIRI(individual) + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> " +
                        ontologyService.getFullIRI(type) + " }";

                if (GlobalQueryTracker.addQuery(binaryQuery)) {
                    generateBinaryMembershipQuery(individual, type, isAsserted, outputService);
                }

                // Collect for multi-choice
                typeQueryAnswers.computeIfAbsent(multiQuery, k -> new ConcurrentHashMap<>());

                StringBuilder sb = new StringBuilder();
                if (isAsserted) {
                    sb.append(DIRECTLY_ASSERTED);
                } else {
                    explanationService.explainTypeInference(individual, type, sb);
                }

                typeQueryAnswers.get(multiQuery).put(
                        ontologyService.getFullIRI(type),
                        sb.toString().trim()
                );
            }
        });

        // Process multi-choice queries
        processBatchQueries(typeQueryAnswers, new HashMap<>(), MEMBERSHIP, outputService);
    }

    private void generateBinaryMembershipQuery(OWLNamedIndividual individual,
                                               OWLClass type,
                                               boolean isAsserted,
                                               OutputService outputService) {
        String sparql = "ASK WHERE { " +
                ontologyService.getFullIRI(individual) + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> " +
                ontologyService.getFullIRI(type) + " }";

        String explanation;
        int size;

        if (isAsserted) {
            explanation = DIRECTLY_ASSERTED;
            size = 1;
        } else {
            StringBuilder sb = new StringBuilder();
            explanationService.explainTypeInference(individual, type, sb);
            explanation = sb.toString().trim();
            size = explanationService.getExplanationSize(explanation);
            if (size == 0 && !explanation.isEmpty()) size = 1;
        }

        try {
            outputService.writeBinaryQuery(
                    MEMBERSHIP,
                    sparql,
                    "type",
                    "true",
                    explanation,
                    size
            );
        } catch (IOException e) {
            LOGGER.error("Failed to write binary membership query for SPARQL: " + sparql, e);
        }
    }

    @Override
    public void generateSubsumptionQueries(OutputService outputService) {
        Set<OWLClass> relevantClasses = ontology.getClassesInSignature().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .collect(Collectors.toSet());

        relevantClasses.parallelStream().forEach(subClass -> {
            Set<OWLClass> superClasses = reasoner.getSuperClasses(subClass, false)
                    .entities()
                    .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                    .collect(Collectors.toSet());

            for (OWLClass superClass : superClasses) {
                String globalTripleKey = createGlobalTripleKey(subClass, null, superClass);

                if (!GlobalQueryTracker.addTriple(globalTripleKey)) {
                    continue;
                }

                boolean isAsserted = ontology.containsAxiom(
                        dataFactory.getOWLSubClassOfAxiom(subClass, superClass)
                );

                String sparql = "ASK WHERE { " +
                        ontologyService.getFullIRI(subClass) + " <http://www.w3.org/2000/01/rdf-schema#subClassOf> " +
                        ontologyService.getFullIRI(superClass) + " }";

                if (!GlobalQueryTracker.addQuery(sparql)) continue;

                String explanation;
                int size;

                if (isAsserted) {
                    explanation = DIRECTLY_ASSERTED;
                    size = 1;
                } else {
                    StringBuilder sb = new StringBuilder();
                    explanationService.explainClassRelationship(subClass, superClass, sb);
                    explanation = sb.toString().trim();
                    size = explanationService.getExplanationSize(explanation);
                    if (size == 0 && !explanation.isEmpty()) size = 1;
                }

                try {
                    outputService.writeBinaryQuery(
                            SUBSUMPTION,
                            sparql,
                            "subClassOf",
                            "true",
                            explanation,
                            size
                    );
                } catch (IOException e) {
                    LOGGER.error("Failed to write subsumption query for SPARQL: " + sparql, e);
                }
            }
        });
    }

    // HELPER METHODS
    private String createGlobalTripleKey(OWLEntity subject, OWLObjectProperty property, OWLEntity object) {
        String subjectIRI = ontologyService.getFullIRI(subject);
        String propertyIRI;

        if (property != null) {
            propertyIRI = ontologyService.getFullIRI(property);
        } else {
            // For membership and subsumption queries
            if (object instanceof OWLClass) {
                propertyIRI = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";
            } else {
                propertyIRI = "<http://www.w3.org/2000/01/rdf-schema#subClassOf>";
            }
        }

        String objectIRI = ontologyService.getFullIRI(object);

        return subjectIRI + "|" + propertyIRI + "|" + objectIRI;
    }

    private void processBatchQueries(Map<String, Map<String, String>> queryAnswersMap,
                                     Map<String, OWLObjectProperty> queryProperties,
                                     String taskType,
                                     OutputService outputService) {
        queryAnswersMap.forEach((sparql, answerExplanations) -> {
            // Calculate sizes for each explanation
            Map<String, Integer> sizeMap = new HashMap<>();
            for (Map.Entry<String, String> entry : answerExplanations.entrySet()) {
                String answerIri = entry.getKey();
                String explanation = entry.getValue();
                int size = explanationService.getExplanationSize(explanation);
                if (size == 0 && !explanation.isEmpty()) {
                    size = 1;
                }
                sizeMap.put(answerIri, size);
            }

            try {
                // Check if we should use the grouped method
                if (outputService instanceof HybridOutputService && answerExplanations.size() > 1) {
                    // Get all answers as a list
                    List<String> answers = new ArrayList<>(answerExplanations.keySet());

                    // Determine predicate name
                    String predicateName;
                    if (taskType.equals(PROPERTY_ASSERTION)) {
                        OWLObjectProperty property = queryProperties.get(sparql);
                        predicateName = property != null ? ontologyService.getDisplayName(property) : "property";
                    } else if (taskType.equals(MEMBERSHIP)) {
                        predicateName = "type";
                    } else {
                        predicateName = "subClassOf";
                    }

                    // Call the grouped method for multi-choice queries
                    ((HybridOutputService)outputService).writeGroupedMultiChoiceQuery(
                            taskType,
                            sparql,
                            predicateName,
                            answers,
                            answerExplanations,
                            sizeMap
                    );
                } else {
                    // Process each answer individually for other output services
                    for (Map.Entry<String, String> entry : answerExplanations.entrySet()) {
                        String answerIri = entry.getKey();
                        String explanation = entry.getValue();

                        // Extract short name from full IRI
                        String shortName = extractShortName(answerIri);
                        int size = sizeMap.get(answerIri);

                        // Determine predicate name
                        String predicateName;
                        if (taskType.equals(PROPERTY_ASSERTION)) {
                            OWLObjectProperty property = queryProperties.get(sparql);
                            predicateName = property != null ? ontologyService.getDisplayName(property) : "property";
                        } else if (taskType.equals(MEMBERSHIP)) {
                            predicateName = "type";
                        } else {
                            predicateName = "subClassOf";
                        }

                        outputService.writeMultiChoiceQuery(
                                taskType,
                                sparql,
                                predicateName,
                                shortName,
                                explanation,
                                size
                        );
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to write multi-choice query for SPARQL: " + sparql, e);
            }
        });
    }

    private String extractShortName(String fullIRI) {
        // Extract short name from full IRI format <http://example.com/onto#Name>
        if (fullIRI.startsWith("<") && fullIRI.endsWith(">")) {
            String iri = fullIRI.substring(1, fullIRI.length() - 1);
            if (iri.contains("#")) {
                return iri.substring(iri.lastIndexOf("#") + 1);
            } else if (iri.contains("/")) {
                return iri.substring(iri.lastIndexOf("/") + 1);
            }
        }
        return fullIRI;
    }
}