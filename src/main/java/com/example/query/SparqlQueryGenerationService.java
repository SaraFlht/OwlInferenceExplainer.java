package com.example.query;

import com.example.output.OutputService;
import com.example.explanation.ExplanationService;
import com.example.output.HybridOutputService;
import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
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
 * SPARQL query generator, wired via QueryGenerationService.
 */
public class SparqlQueryGenerationService implements QueryGenerationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SparqlQueryGenerationService.class);

    // Task type constants
    private static final String PROPERTY_ASSERTION = "Property Assertion";
    private static final String MEMBERSHIP        = "Membership";
    private static final String SUBSUMPTION       = "Subsumption";
    private static final String DIRECTLY_ASSERTED = "Directly asserted";
    private static final String BINARY            = "Binary";
    private static final String MULTI_CHOICE      = "Multi Choice";

    private OWLOntology ontology;
    private OpenlletReasoner reasoner;
    private ExplanationService explanationService;
    private OWLDataFactory dataFactory;
    private SimpleShortFormProvider shortFormProvider;
    private final Set<String> generatedQueries = ConcurrentHashMap.newKeySet();

    @Override
    public void initialize(OWLOntology ontology,
                           OpenlletReasoner reasoner,
                           ExplanationService explanationService,
                           OWLDataFactory dataFactory) {
        this.ontology = ontology;
        this.reasoner = reasoner;
        this.explanationService = explanationService;
        this.dataFactory = dataFactory;
        this.shortFormProvider = new SimpleShortFormProvider();
        LOGGER.info("Initialized query generation service");
    }

    @Override
    public void generatePropertyAssertionQueries(OutputService outputService) {
        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();
        Set<OWLObjectProperty> properties = ontology.getObjectPropertiesInSignature();

        Map<String, Map<String, String>> subjectQueryAnswers = new ConcurrentHashMap<>();
        Map<String, Map<String, String>> objectQueryAnswers = new ConcurrentHashMap<>();
        Map<String, OWLObjectProperty> queryProperties = new ConcurrentHashMap<>();

        individuals.parallelStream().forEach(subject -> {
            for (OWLObjectProperty property : properties) {
                NodeSet<OWLNamedIndividual> objects = reasoner.getObjectPropertyValues(subject, property);
                for (OWLNamedIndividual object : objects.entities().collect(Collectors.toSet())) {
                    if (subject.equals(object)) continue;

                    String key = subject.getIRI() + "|" + property.getIRI() + "|" + object.getIRI();
                    if (!generatedQueries.add(key)) continue;

                    boolean asserted = ontology.containsAxiom(
                            dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, object)
                    );

                    generateBinaryPropertyQuery(subject, property, object, asserted, outputService);

                    StringBuilder explSb = new StringBuilder();
                    if (asserted) {
                        explSb.append(DIRECTLY_ASSERTED);
                    } else {
                        explanationService.explainPropertyRelationship(subject, property, object, explSb);
                    }
                    String explanation = explSb.toString().trim();

                    String subjectQuery = "SELECT ?subject WHERE { ?subject <" + property.getIRI() + "> <" + object.getIRI() + "> }";
                    subjectQueryAnswers.computeIfAbsent(subjectQuery, k -> new ConcurrentHashMap<>());
                    queryProperties.putIfAbsent(subjectQuery, property);
                    subjectQueryAnswers.get(subjectQuery).put(subject.getIRI().toString(), explanation);

                    String objectQuery = "SELECT ?object WHERE { <" + subject.getIRI() + "> <" + property.getIRI() + "> ?object }";
                    objectQueryAnswers.computeIfAbsent(objectQuery, k -> new ConcurrentHashMap<>());
                    queryProperties.putIfAbsent(objectQuery, property);
                    objectQueryAnswers.get(objectQuery).put(object.getIRI().toString(), explanation);
                }
            }
        });

        processBatchQueries(subjectQueryAnswers, queryProperties, PROPERTY_ASSERTION, outputService);
        processBatchQueries(objectQueryAnswers, queryProperties, PROPERTY_ASSERTION, outputService);
    }

    private void processBatchQueries(Map<String, Map<String, String>> queryAnswersMap,
                                     Map<String, OWLObjectProperty> queryProperties,
                                     String taskType,
                                     OutputService outputService) {
        queryAnswersMap.forEach((sparql, answerExplanations) -> {
            OWLObjectProperty property = queryProperties.get(sparql);
            if (property == null) return;

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

                    // Call the grouped method for multi-choice queries
                    ((HybridOutputService)outputService).writeGroupedMultiChoiceQuery(
                            taskType,
                            sparql,
                            shortFormProvider.getShortForm(property),
                            answers,
                            answerExplanations,
                            sizeMap
                    );
                } else {
                    // Process each answer individually for other output services
                    for (Map.Entry<String, String> entry : answerExplanations.entrySet()) {
                        String answerIri = entry.getKey();
                        String explanation = entry.getValue();

                        OWLNamedIndividual individual = dataFactory.getOWLNamedIndividual(IRI.create(answerIri));
                        String shortName = shortFormProvider.getShortForm(individual);

                        int size = sizeMap.get(answerIri);

                        outputService.writeMultiChoiceQuery(
                                taskType,
                                sparql,
                                shortFormProvider.getShortForm(property),
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

    private void generateBinaryPropertyQuery(OWLNamedIndividual subject,
                                             OWLObjectProperty property,
                                             OWLNamedIndividual object,
                                             boolean asserted,
                                             OutputService outputService) {
        String sparql = "ASK WHERE { <" + subject.getIRI() + "> <" + property.getIRI() + "> <" + object.getIRI() + "> }";
        if (!generatedQueries.add(sparql)) return;

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
                    sparql,
                    shortFormProvider.getShortForm(property),
                    "true",
                    explanation,
                    size
            );
        } catch (IOException e) {
            LOGGER.error("Failed to write binary query for SPARQL: " + sparql, e);
        }
    }

    @Override
    public void generateMembershipQueries(OutputService outputService) {
        // Collection for grouped membership queries
        Map<String, Map<String, String>> typeQueryAnswers = new ConcurrentHashMap<>();

        // Process membership queries with parallelism
        ontology.getIndividualsInSignature().parallelStream().forEach(individual -> {
            Set<OWLClass> inferredTypes = reasoner.getTypes(individual, false)
                    .entities()
                    .collect(Collectors.toSet());

            Set<OWLClassExpression> assertedTypes = EntitySearcher.getTypes(individual, ontology)
                    .collect(Collectors.toSet());

            for (OWLClass type : inferredTypes) {
                if (type.isOWLThing() || type.isOWLNothing()) continue;

                boolean isAsserted = assertedTypes.contains(type);

                // Generate binary membership query
                String binary = "ASK WHERE { <" + individual.getIRI() + "> rdf:type <" + type.getIRI() + "> }";
                if (generatedQueries.add(binary)) {
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
                                binary,
                                "type",
                                "true",
                                explanation,
                                size
                        );
                    } catch (IOException e) {
                        LOGGER.error("Failed to write binary membership query for SPARQL: " + binary, e);
                    }
                }

                // Collect data for multi-choice membership query
                String multi = "SELECT ?class WHERE { <" + individual.getIRI() + "> rdf:type ?class }";
                typeQueryAnswers.computeIfAbsent(multi, k -> new ConcurrentHashMap<>());

                StringBuilder sb = new StringBuilder();
                if (isAsserted) {
                    sb.append(DIRECTLY_ASSERTED);
                } else {
                    explanationService.explainTypeInference(individual, type, sb);
                }
                String explanation = sb.toString().trim();

                // Use the short name from the type instead of the IRI
                String shortTypeName = shortFormProvider.getShortForm(type);
                typeQueryAnswers.get(multi).put(type.getIRI().toString(), explanation);
            }
        });

        // Process grouped membership queries
        typeQueryAnswers.forEach((sparql, answerExplanations) -> {
            if (!generatedQueries.add(sparql)) return;

            // Calculate sizes for each explanation
            Map<String, Integer> sizeMap = new HashMap<>();
            Map<String, String> shortNameMap = new HashMap<>();

            for (Map.Entry<String, String> entry : answerExplanations.entrySet()) {
                String answerIri = entry.getKey();
                String explanation = entry.getValue();

                OWLClass typeClass = dataFactory.getOWLClass(IRI.create(answerIri));
                String shortName = shortFormProvider.getShortForm(typeClass);
                shortNameMap.put(answerIri, shortName);

                int size = explanationService.getExplanationSize(explanation);
                if (size == 0 && !explanation.isEmpty()) size = 1;
                sizeMap.put(answerIri, size);
            }

            try {
                // Check if we should use the grouped method
                if (outputService instanceof HybridOutputService && answerExplanations.size() > 1) {
                    // Get all answers as a list
                    List<String> answers = new ArrayList<>(answerExplanations.keySet());

                    // Convert explanations to use short names
                    Map<String, String> shortExplanations = new HashMap<>();
                    for (String key : answers) {
                        shortExplanations.put(key, answerExplanations.get(key));
                    }

                    // Call the grouped method for multi-choice queries
                    ((HybridOutputService)outputService).writeGroupedMultiChoiceQuery(
                            MEMBERSHIP,
                            sparql,
                            "type",
                            answers,
                            shortExplanations,
                            sizeMap
                    );
                } else {
                    // Process each answer individually for other output services
                    for (Map.Entry<String, String> entry : answerExplanations.entrySet()) {
                        String answerIri = entry.getKey();
                        String explanation = entry.getValue();
                        String shortName = shortNameMap.get(answerIri);
                        int size = sizeMap.get(answerIri);

                        outputService.writeMultiChoiceQuery(
                                MEMBERSHIP,
                                sparql,
                                "type",
                                shortName,
                                explanation,
                                size
                        );
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to write multi-choice membership query for SPARQL: " + sparql, e);
            }
        });
    }

    @Override
    public void generateSubsumptionQueries(OutputService outputService) {
        // Filter out owl:Thing and owl:Nothing for efficiency
        Set<OWLClass> relevantClasses = ontology.getClassesInSignature().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .collect(Collectors.toSet());

        // Process subsumption queries with parallelism
        relevantClasses.parallelStream().forEach(subClass -> {
            Set<OWLClass> superClasses = reasoner.getSuperClasses(subClass, false)
                    .entities()
                    .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                    .collect(Collectors.toSet());

            for (OWLClass superClass : superClasses) {
                boolean isAsserted = ontology.containsAxiom(
                        dataFactory.getOWLSubClassOfAxiom(subClass, superClass)
                );

                String sparql = "ASK { <" + subClass.getIRI() + "> rdfs:subClassOf <" + superClass.getIRI() + "> }";
                if (!generatedQueries.add(sparql)) continue;

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
}