   package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import openllet.owlapi.explanation.PelletExplanation;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PelletExplanationService implements ExplanationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PelletExplanationService.class);
    private PelletExplanation explanation;
    private OWLOntology ontology;
    private OpenlletReasoner reasoner;
    private OWLDataFactory dataFactory;
    private ShortFormProvider shortFormProvider;
    private final Map<String, String> explanationCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<String>> currentExplanations = ThreadLocal.withInitial(HashSet::new);

    // Maximum depth for recursive explanation searches
    private static final int MAX_EXPLANATION_DEPTH = 50;

    // Maximum number of explanation paths to find per inference
    private static final int MAX_EXPLANATIONS_PER_INFERENCE = 50;

    // Store all discovered explanation paths (for internal use)
    private final Map<String, List<List<OWLAxiom>>> allExplanationPaths = new ConcurrentHashMap<>();

    @Override
    public void initializeExplanations(OpenlletReasoner reasoner) {
        this.reasoner = reasoner;
        this.ontology = reasoner.getRootOntology();
        this.dataFactory = ontology.getOWLOntologyManager().getOWLDataFactory();
        this.shortFormProvider = new SimpleShortFormProvider();
        LOGGER.info("Initializing Pellet explanation service");
        PelletExplanation.setup();
        this.explanation = new PelletExplanation(reasoner);


        // Pre-compute common inferences to improve response time
        LOGGER.info("Precomputing inferences to improve explanation performance");
        reasoner.precomputeInferences();
    }

    @Override
    public void explainPropertyRelationship(OWLNamedIndividual subject,
                                            OWLObjectPropertyExpression property,
                                            OWLNamedIndividual object,
                                            StringBuilder sb) {
        // Create a cache key for this explanation
        String cacheKey = "prop|" + subject.getIRI() + "|" + property + "|" + object.getIRI();

        // Check if we're already explaining this (recursive case)
        Set<String> currentlyExplaining = currentExplanations.get();
        if (currentlyExplaining.contains(cacheKey)) {
            sb.append("Recursive explanation detected\n");
            return;
        }

        // Check if we have this explanation cached
        String cachedExplanation = explanationCache.get(cacheKey);
        if (cachedExplanation != null) {
            sb.append(cachedExplanation);
            return;
        }

        // Mark this as being currently explained to prevent recursion
        currentlyExplaining.add(cacheKey);

        try {
            OWLObjectProperty p = property.asOWLObjectProperty();
            OWLAxiom ax = dataFactory.getOWLObjectPropertyAssertionAxiom(p, subject, object);

            // Check if directly asserted
            if (ontology.containsAxiom(ax)) {
                sb.append("Directly asserted\n");
                explanationCache.put(cacheKey, "Directly asserted\n");
                return;
            }

            // Get all explanation paths
            List<List<OWLAxiom>> allPaths = new ArrayList<>();
            boolean foundAny = false;

            // Get Pellet explanations with enhanced methods
            Set<List<OWLAxiom>> pelletPaths = new HashSet<>();
            Set<Set<OWLAxiom>> allPelletExplanations = getAllPelletExplanations(ax);
            for (Set<OWLAxiom> explanation : allPelletExplanations) {
                pelletPaths.add(new ArrayList<>(explanation));
            }

            if (!pelletPaths.isEmpty()) {
                formatExplanations(pelletPaths, "Explanations from Pellet", sb);
                allPaths.addAll(pelletPaths);
                foundAny = true;
            }

            // If we already have good explanations from Pellet, we might not need to search more
            if (!foundAny || pelletPaths.size() < 10) {
                // Look for explanations through various mechanisms
                Set<List<OWLAxiom>> inversePaths = findInversePropertyPaths(subject, property, object);
                if (!inversePaths.isEmpty()) {
                    formatExplanations(inversePaths, "Inverse-of paths", sb);
                    allPaths.addAll(inversePaths);
                    foundAny = true;
                }

                Set<List<OWLAxiom>> subPropPaths = findSubPropertyPaths(subject, property, object);
                if (!subPropPaths.isEmpty()) {
                    formatExplanations(subPropPaths, "Sub-property paths", sb);
                    allPaths.addAll(subPropPaths);
                    foundAny = true;
                }

                Set<List<OWLAxiom>> chainPaths = findPropertyChainPaths(subject, property, object);
                if (!chainPaths.isEmpty()) {
                    formatExplanations(chainPaths, "Property chain paths", sb);
                    allPaths.addAll(chainPaths);
                    foundAny = true;
                }

                Set<List<OWLAxiom>> equivPaths = findEquivalentPropertyPaths(subject, property, object);
                if (!equivPaths.isEmpty()) {
                    formatExplanations(equivPaths, "Equivalent property paths", sb);
                    allPaths.addAll(equivPaths);
                    foundAny = true;
                }

                // ADD THIS BLOCK HERE - START
                Set<List<OWLAxiom>> complexPaths = findComplexCombinedExplanations(subject, property, object);
                if (!complexPaths.isEmpty()) {
                    formatExplanations(complexPaths, "Combined multi-step paths", sb);
                    allPaths.addAll(complexPaths);
                    foundAny = true;
                }
                // ADD THIS BLOCK HERE - END
            }

            if (!foundAny) {
                sb.append("No explanation found for ")
                        .append(shortFormProvider.getShortForm(subject)).append(" ")
                        .append(renderProp(property)).append(" ")
                        .append(shortFormProvider.getShortForm(object)).append("\n");
            } else {
                // Store all found explanation paths for potential future use
                allExplanationPaths.put(cacheKey, allPaths);
            }

            // Cache this explanation for future use
            explanationCache.put(cacheKey, sb.toString());
        } finally {
            // Always remove from currently explaining set to prevent memory leaks
            currentlyExplaining.remove(cacheKey);
        }
    }

    /**
     * Find complex explanations by combining different explanation types
     * This method attempts to find more complete explanations by combining
     * different types of inference paths that may contribute to the same relationship
     */
    private Set<List<OWLAxiom>> findComplexCombinedExplanations(OWLNamedIndividual subject,
                                                                OWLObjectPropertyExpression property,
                                                                OWLNamedIndividual object) {
        Set<List<OWLAxiom>> paths = new HashSet<>();
        Set<String> processedPaths = new HashSet<>(); // To avoid duplicates

        // Get all basic paths from different explanation mechanisms
        Set<List<OWLAxiom>> inversePaths = findInversePropertyPaths(subject, property, object);
        Set<List<OWLAxiom>> subPropPaths = findSubPropertyPaths(subject, property, object);
        Set<List<OWLAxiom>> chainPaths = findPropertyChainPaths(subject, property, object);
        Set<List<OWLAxiom>> equivPaths = findEquivalentPropertyPaths(subject, property, object);

        // Add all direct paths (making a copy to avoid modifying the original sets)
        addUniquePaths(paths, processedPaths, inversePaths);
        addUniquePaths(paths, processedPaths, subPropPaths);
        addUniquePaths(paths, processedPaths, chainPaths);
        addUniquePaths(paths, processedPaths, equivPaths);

        // Track individuals reachable via different properties for multi-step paths
        Map<OWLObjectPropertyExpression, Set<OWLNamedIndividual>> reachableIndividuals = new HashMap<>();

        // Find intermediate individuals reachable from subject via any property
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            NodeSet<OWLNamedIndividual> individuals = reasoner.getObjectPropertyValues(subject, prop);
            if (individuals != null && !individuals.isEmpty()) {
                Set<OWLNamedIndividual> indSet = new HashSet<>();
                individuals.entities().forEach(indSet::add);
                reachableIndividuals.put(prop, indSet);
            }
        }

        // Phase 1: Try combining subProperty + chain combinations
        for (List<OWLAxiom> subPath : subPropPaths) {
            if (subPath.isEmpty()) continue;

            OWLAxiom subPropAxiom = getSubPropertyAxiom(subPath);
            if (subPropAxiom instanceof OWLSubObjectPropertyOfAxiom) {
                OWLSubObjectPropertyOfAxiom subPropOf = (OWLSubObjectPropertyOfAxiom) subPropAxiom;
                OWLObjectPropertyExpression subProp = subPropOf.getSubProperty();

                // For each chain that could connect with this subproperty
                for (List<OWLAxiom> chainPath : chainPaths) {
                    if (chainPath.isEmpty()) continue;

                    // Find chain axiom and check if the properties can be connected
                    for (OWLAxiom axiom : chainPath) {
                        if (axiom instanceof OWLSubPropertyChainOfAxiom) {
                            OWLSubPropertyChainOfAxiom chainAx = (OWLSubPropertyChainOfAxiom) axiom;

                            // Check if this chain could connect with the subproperty
                            if (canConnectSubPropertyToChain(subProp, chainAx)) {
                                // Create a new combined path
                                List<OWLAxiom> combinedPath = new ArrayList<>();
                                combinedPath.addAll(subPath);
                                combinedPath.addAll(chainPath);
                                addUniquePath(paths, processedPaths, combinedPath);
                            }
                        }
                    }
                }
            }
        }

        // Phase 2: Try combining multiple property steps to find longer paths
        Set<OWLNamedIndividual> intermediates = findPotentialIntermediateIndividuals(subject, property, object);

        for (OWLNamedIndividual intermediate : intermediates) {
            // Skip subject and object themselves
            if (intermediate.equals(subject) || intermediate.equals(object)) {
                continue;
            }

            // Find paths from subject to intermediate
            for (OWLObjectProperty prop1 : ontology.getObjectPropertiesInSignature()) {
                if (reasoner.isEntailed(dataFactory.getOWLObjectPropertyAssertionAxiom(prop1, subject, intermediate))) {
                    // Find paths from intermediate to object
                    for (OWLObjectProperty prop2 : ontology.getObjectPropertiesInSignature()) {
                        if (reasoner.isEntailed(dataFactory.getOWLObjectPropertyAssertionAxiom(prop2, intermediate, object))) {
                            // Try to find explanation for this multi-step path
                            List<OWLAxiom> multiStepPath = new ArrayList<>();

                            // Add the direct property assertions
                            OWLAxiom firstStep = dataFactory.getOWLObjectPropertyAssertionAxiom(prop1, subject, intermediate);
                            OWLAxiom secondStep = dataFactory.getOWLObjectPropertyAssertionAxiom(prop2, intermediate, object);
                            multiStepPath.add(firstStep);
                            multiStepPath.add(secondStep);

                            // Try to find an axiom that connects these properties to the target property
                            addConnectingAxioms(multiStepPath, prop1, prop2, property);

                            // Add this multi-step path
                            addUniquePath(paths, processedPaths, multiStepPath);
                        }
                    }
                }
            }
        }

        // Phase 3: Try combining equivalent property + other path types
        for (List<OWLAxiom> equivPath : equivPaths) {
            if (equivPath.isEmpty()) continue;

            // For each equivalent property, try to find additional inferences
            for (OWLAxiom axiom : equivPath) {
                if (axiom instanceof OWLEquivalentObjectPropertiesAxiom) {
                    OWLEquivalentObjectPropertiesAxiom equivAx = (OWLEquivalentObjectPropertiesAxiom) axiom;

                    // Get all the equivalent properties
                    for (OWLObjectPropertyExpression equivProp : equivAx.getProperties()) {
                        if (equivProp.equals(property)) continue; // Skip the property itself

                        // For this equivalent property, look for chains or sub-properties
                        for (List<OWLAxiom> otherPath : chainPaths) {
                            List<OWLAxiom> combinedPath = new ArrayList<>();
                            combinedPath.add(axiom); // Add the equivalence axiom
                            combinedPath.addAll(otherPath); // Add the chain path
                            addUniquePath(paths, processedPaths, combinedPath);
                        }

                        // Try combining with sub-property paths too
                        for (List<OWLAxiom> otherPath : subPropPaths) {
                            List<OWLAxiom> combinedPath = new ArrayList<>();
                            combinedPath.add(axiom); // Add the equivalence axiom
                            combinedPath.addAll(otherPath); // Add the sub-property path
                            addUniquePath(paths, processedPaths, combinedPath);
                        }
                    }
                }
            }
        }

        // Phase 4: Try combining inverse property + chain combinations
        for (List<OWLAxiom> invPath : inversePaths) {
            if (invPath.isEmpty()) continue;

            OWLAxiom invPropAxiom = getInversePropertyAxiom(invPath);
            if (invPropAxiom instanceof OWLInverseObjectPropertiesAxiom) {
                OWLInverseObjectPropertiesAxiom invPropOf = (OWLInverseObjectPropertiesAxiom) invPropAxiom;

                // Get the inverse property
                OWLObjectPropertyExpression firstProp = invPropOf.getFirstProperty();
                OWLObjectPropertyExpression secondProp = invPropOf.getSecondProperty();
                OWLObjectPropertyExpression invProp = firstProp.equals(property) ? secondProp : firstProp;

                // Try combining with chains
                for (List<OWLAxiom> chainPath : chainPaths) {
                    if (chainPath.isEmpty()) continue;

                    // Create a new combined path
                    List<OWLAxiom> combinedPath = new ArrayList<>();
                    combinedPath.addAll(invPath);
                    combinedPath.addAll(chainPath);
                    addUniquePath(paths, processedPaths, combinedPath);
                }
            }
        }

        // Limit results to prevent excessive memory use
        return limitResults(paths, MAX_EXPLANATIONS_PER_INFERENCE);
    }

    /**
     * Helper method to find potential intermediate individuals that might be part of a multi-step path
     */
    private Set<OWLNamedIndividual> findPotentialIntermediateIndividuals(OWLNamedIndividual subject,
                                                                         OWLObjectPropertyExpression property,
                                                                         OWLNamedIndividual object) {
        Set<OWLNamedIndividual> candidates = new HashSet<>();

        // Add individuals reachable from subject via any property
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            NodeSet<OWLNamedIndividual> individuals = reasoner.getObjectPropertyValues(subject, prop);
            if (individuals != null) {
                individuals.entities().forEach(candidates::add);
            }
        }

        // Also check individuals that can reach object via any property
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            // Use inverse of property to find individuals that can reach object
            Set<OWLNamedIndividual> fromInds = new HashSet<>();
            for (OWLNamedIndividual ind : ontology.getIndividualsInSignature()) {
                if (reasoner.isEntailed(dataFactory.getOWLObjectPropertyAssertionAxiom(prop, ind, object))) {
                    fromInds.add(ind);
                }
            }
            candidates.addAll(fromInds);
        }

        return candidates;
    }

    /**
     * Try to find axioms that connect two properties to the target property
     */
    /**
     * Try to find axioms that connect two properties to the target property
     * Improved to avoid adding artificial axioms
     */
    private void addConnectingAxioms(List<OWLAxiom> path, OWLObjectPropertyExpression prop1,
                                     OWLObjectPropertyExpression prop2, OWLObjectPropertyExpression targetProp) {
        // Look for property chain axioms
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiom instanceof OWLSubPropertyChainOfAxiom) {
                OWLSubPropertyChainOfAxiom chainAx = (OWLSubPropertyChainOfAxiom) axiom;

                // Check if this chain connects prop1 and prop2 to targetProp
                if (chainAx.getSuperProperty().equals(targetProp)) {
                    List<OWLObjectPropertyExpression> chain = chainAx.getPropertyChain();

                    // Check if the chain matches our properties
                    if (chain.size() == 2 &&
                            chain.get(0).equals(prop1) &&
                            chain.get(1).equals(prop2)) {
                        path.add(axiom);
                        return;
                    }
                }
            }
        }

        // Check if prop1 is a sub-property of targetProp
        if (reasoner.isEntailed(dataFactory.getOWLSubObjectPropertyOfAxiom(prop1, targetProp))) {
            OWLAxiom subProp1 = dataFactory.getOWLSubObjectPropertyOfAxiom(prop1, targetProp);
            path.add(subProp1);
        }

        // Check if prop2 is a sub-property of targetProp
        if (reasoner.isEntailed(dataFactory.getOWLSubObjectPropertyOfAxiom(prop2, targetProp))) {
            OWLAxiom subProp2 = dataFactory.getOWLSubObjectPropertyOfAxiom(prop2, targetProp);
            path.add(subProp2);
        }

    }

    /**
     * Check if a sub-property can connect with a property chain
     */
    private boolean canConnectSubPropertyToChain(OWLObjectPropertyExpression subProp, OWLSubPropertyChainOfAxiom chainAx) {
        List<OWLObjectPropertyExpression> chain = chainAx.getPropertyChain();

        // Check if the sub-property appears in the chain
        return chain.contains(subProp);
    }

    /**
     * Extract a sub-property axiom from a path, if present
     */
    private OWLAxiom getSubPropertyAxiom(List<OWLAxiom> path) {
        for (OWLAxiom axiom : path) {
            if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
                return axiom;
            }
        }
        return null;
    }

    /**
     * Extract an inverse property axiom from a path, if present
     */
    private OWLAxiom getInversePropertyAxiom(List<OWLAxiom> path) {
        for (OWLAxiom axiom : path) {
            if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
                return axiom;
            }
        }
        return null;
    }

    /**
     * Add a path to the result set if it's not already present (by comparing the axiom sets)
     */
    /**
     * Add a path to the result set if it's not already present and ensure
     * no duplicate axioms exist within the path itself
     */
    private void addUniquePath(Set<List<OWLAxiom>> paths, Set<String> processedPaths, List<OWLAxiom> path) {
        // Skip empty paths
        if (path == null || path.isEmpty()) {
            return;
        }

        // 1. Remove duplicate axioms within the path itself
        List<OWLAxiom> uniqueAxioms = new ArrayList<>();
        Set<String> seenAxioms = new HashSet<>();

        for (OWLAxiom axiom : path) {
            String axiomStr = axiom.toString();
            if (!seenAxioms.contains(axiomStr)) {
                uniqueAxioms.add(axiom);
                seenAxioms.add(axiomStr);
            }
        }

        // 2. Skip paths with reflexive subPropertyOf axioms (P SubPropertyOf P)
        boolean hasReflexiveSubPropertyAxiom = false;
        for (OWLAxiom axiom : uniqueAxioms) {
            if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
                OWLSubObjectPropertyOfAxiom subPropAx = (OWLSubObjectPropertyOfAxiom) axiom;
                if (subPropAx.getSubProperty().equals(subPropAx.getSuperProperty())) {
                    hasReflexiveSubPropertyAxiom = true;
                    break;
                }
            }
        }

        if (hasReflexiveSubPropertyAxiom) {
            return; // Skip this path entirely
        }

        // 3. Remove artificially added annotation assertions
        List<OWLAxiom> cleanedAxioms = uniqueAxioms.stream()
                .filter(ax -> !(ax instanceof OWLAnnotationAssertionAxiom &&
                        ax.toString().contains("explanation#comment")))
                .collect(Collectors.toList());

        // Don't add empty paths
        if (cleanedAxioms.isEmpty()) {
            return;
        }

        // 4. Create a unique key for this deduplicated path
        String pathKey = generatePathKey(cleanedAxioms);

        // 5. Check if we already have an equivalent path
        if (!processedPaths.contains(pathKey)) {
            paths.add(cleanedAxioms);  // Add the deduplicated path
            processedPaths.add(pathKey);
        }
    }

    /**
     * Add all paths from source to destination, checking for duplicates
     */
    private void addUniquePaths(Set<List<OWLAxiom>> destPaths, Set<String> processedPaths, Set<List<OWLAxiom>> sourcePaths) {
        for (List<OWLAxiom> path : sourcePaths) {
            addUniquePath(destPaths, processedPaths, path);
        }
    }

    /**
     * Generate a unique key for a path to avoid duplicates
     */
    private String generatePathKey(List<OWLAxiom> path) {
        // Sort axioms by their string representation to ensure consistent ordering
        List<String> axiomStrings = new ArrayList<>();
        for (OWLAxiom axiom : path) {
            axiomStrings.add(axiom.toString());
        }
        Collections.sort(axiomStrings);

        // Join all axiom strings with a separator
        return String.join("|", axiomStrings);
    }

    /**
     * Limit the number of paths to avoid excessive memory usage
     */
    private Set<List<OWLAxiom>> limitResults(Set<List<OWLAxiom>> paths, int maxPaths) {
        if (paths.size() <= maxPaths) {
            return paths;
        }

        // If we have too many paths, prioritize shorter ones
        List<List<OWLAxiom>> sortedPaths = new ArrayList<>(paths);
        sortedPaths.sort(Comparator.comparingInt(List::size));

        Set<List<OWLAxiom>> limitedPaths = new HashSet<>();
        for (int i = 0; i < maxPaths && i < sortedPaths.size(); i++) {
            limitedPaths.add(sortedPaths.get(i));
        }

        return limitedPaths;
    }

    @Override
    public void explainTypeInference(OWLNamedIndividual individual,
                                     OWLClass clazz,
                                     StringBuilder sb) {
        // Create a cache key for this explanation
        String cacheKey = "type|" + individual.getIRI() + "|" + clazz.getIRI();

        // Check if we have this explanation cached
        String cachedExplanation = explanationCache.get(cacheKey);
        if (cachedExplanation != null) {
            sb.append(cachedExplanation);
            return;
        }

        OWLAxiom ax = dataFactory.getOWLClassAssertionAxiom(clazz, individual);

        // Check if directly asserted
        if (ontology.containsAxiom(ax)) {
            sb.append("Directly asserted\n");
            explanationCache.put(cacheKey, "Directly asserted\n");
            return;
        }

        // Verify entailment
        if (!reasoner.isEntailed(ax)) {
            sb.append("Not entailed\n");
            explanationCache.put(cacheKey, "Not entailed\n");
            return;
        }

        // Get all explanation paths
        List<List<OWLAxiom>> allPaths = new ArrayList<>();
        boolean foundAny = false;

        // Get Pellet explanations with enhanced methods
        Set<List<OWLAxiom>> pelletPaths = new HashSet<>();
        Set<Set<OWLAxiom>> allPelletExplanations = getAllPelletExplanations(ax);
        for (Set<OWLAxiom> explanation : allPelletExplanations) {
            pelletPaths.add(new ArrayList<>(explanation));
        }

        if (!pelletPaths.isEmpty()) {
            formatExplanations(pelletPaths, "Explanations from Pellet", sb);
            allPaths.addAll(pelletPaths);
            foundAny = true;
        }

        // If we already have good explanations from Pellet, we might not need to search more
        if (!foundAny || pelletPaths.size() < 10) {
            // Get explanations through other mechanisms
            Set<List<OWLAxiom>> equivClassPaths = findEquivalentClassPaths(individual, clazz);
            if (!equivClassPaths.isEmpty()) {
                formatExplanations(equivClassPaths, "Equivalent class paths", sb);
                allPaths.addAll(equivClassPaths);
                foundAny = true;
            }

            Set<List<OWLAxiom>> subClassPaths = findSubClassPaths(individual, clazz);
            if (!subClassPaths.isEmpty()) {
                formatExplanations(subClassPaths, "Subclass paths", sb);
                allPaths.addAll(subClassPaths);
                foundAny = true;
            }

            Set<List<OWLAxiom>> domainRangePaths = findDomainRangePaths(individual, clazz);
            if (!domainRangePaths.isEmpty()) {
                formatExplanations(domainRangePaths, "Property domain/range paths", sb);
                allPaths.addAll(domainRangePaths);
                foundAny = true;
            }
        }

        if (!foundAny) {
            sb.append("No membership explanation found\n");
        } else {
            // Store all found explanation paths for potential future use
            allExplanationPaths.put(cacheKey, allPaths);
        }

        // Cache this explanation for future use
        explanationCache.put(cacheKey, sb.toString());
    }

    @Override
    public void explainClassRelationship(OWLClass subClass,
                                         OWLClass superClass,
                                         StringBuilder sb) {
        // Create a cache key for this explanation
        String cacheKey = "subclass|" + subClass.getIRI() + "|" + superClass.getIRI();

        // Check if we have this explanation cached
        String cachedExplanation = explanationCache.get(cacheKey);
        if (cachedExplanation != null) {
            sb.append(cachedExplanation);
            return;
        }

        OWLAxiom ax = dataFactory.getOWLSubClassOfAxiom(subClass, superClass);

        // Check if directly asserted
        if (ontology.containsAxiom(ax)) {
            sb.append("Directly asserted\n");
            explanationCache.put(cacheKey, "Directly asserted\n");
            return;
        }

        // Verify entailment
        if (!reasoner.isEntailed(ax)) {
            sb.append("Not entailed\n");
            explanationCache.put(cacheKey, "Not entailed\n");
            return;
        }

        // Get all explanation paths
        List<List<OWLAxiom>> allPaths = new ArrayList<>();
        boolean foundAny = false;

        // Get Pellet explanations with enhanced methods
        Set<List<OWLAxiom>> pelletPaths = new HashSet<>();
        Set<Set<OWLAxiom>> allPelletExplanations = getAllPelletExplanations(ax);
        for (Set<OWLAxiom> explanation : allPelletExplanations) {
            pelletPaths.add(new ArrayList<>(explanation));
        }

        if (!pelletPaths.isEmpty()) {
            formatExplanations(pelletPaths, "Explanations from Pellet", sb);
            allPaths.addAll(pelletPaths);
            foundAny = true;
        }

        // If we already have good explanations from Pellet, we might not need to search more
        if (!foundAny || pelletPaths.size() < 10) {
            // Get explanations through other mechanisms
            Set<List<OWLAxiom>> equivClassPaths = findEquivalentClassesForSubsumption(subClass, superClass);
            if (!equivClassPaths.isEmpty()) {
                formatExplanations(equivClassPaths, "Equivalent class paths", sb);
                allPaths.addAll(equivClassPaths);
                foundAny = true;
            }

            Set<List<OWLAxiom>> intermediatePaths = findIntermediateClassPaths(subClass, superClass);
            if (!intermediatePaths.isEmpty()) {
                formatExplanations(intermediatePaths, "Intermediate class paths", sb);
                allPaths.addAll(intermediatePaths);
                foundAny = true;
            }
        }

        if (!foundAny) {
            sb.append("No subsumption explanation found\n");
        } else {
            // Store all found explanation paths for potential future use
            allExplanationPaths.put(cacheKey, allPaths);
        }

        // Cache this explanation for future use
        explanationCache.put(cacheKey, sb.toString());
    }

    @Override
    public int getExplanationSize(String explanation) {
        if (explanation == null || explanation.trim().isEmpty()) {
            return 0;
        }

        if (explanation.contains("Directly asserted")) {
            return 1;
        }

        // Count only the lines that start with "  - " which indicate actual axioms
        // We're calculating the maximum path length here
        int maxSize = 0;
        int currentPathSize = 0;
        boolean countingPath = false;

        for (String line : explanation.split("\n")) {
            line = line.trim();
            if (line.startsWith("Path ") || line.startsWith("Explanations from")
                    || line.startsWith("Inverse-of") || line.startsWith("Sub-property")
                    || line.startsWith("Equivalent class") || line.startsWith("Subclass")
                    || line.startsWith("Property domain") || line.startsWith("Property chain")
                    || line.startsWith("Intermediate class")) {
                // We're starting a new path, save the current count
                if (countingPath && currentPathSize > 0) {
                    maxSize = Math.max(maxSize, currentPathSize);
                }
                currentPathSize = 0;
                countingPath = true;
            } else if (line.startsWith("-")) {
                currentPathSize++;
            } else if (line.isEmpty() && countingPath) {
                // End of a path section
                maxSize = Math.max(maxSize, currentPathSize);
                currentPathSize = 0;
            }
        }

        // Check the last path if we were still counting
        if (countingPath && currentPathSize > 0) {
            maxSize = Math.max(maxSize, currentPathSize);
        }

        return maxSize;
    }

    /**
     * Generic method to find explanations for any type of axiom
     * This reduces code duplication across different types of explanations
     */
    private Set<List<OWLAxiom>> findExplanations(OWLAxiom axiom) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Check if directly asserted
        if (ontology.containsAxiom(axiom)) {
            List<OWLAxiom> directPath = Collections.singletonList(axiom);
            paths.add(directPath);
            return paths;
        }

        // Check if entailed
        if (!reasoner.isEntailed(axiom)) {
            return paths; // Empty set - no explanations
        }

        // Try to get Pellet explanation
        try {
            Set<OWLAxiom> singleExpl;

            if (axiom instanceof OWLClassAssertionAxiom) {
                OWLClassAssertionAxiom classAssertion = (OWLClassAssertionAxiom) axiom;
                singleExpl = explanation.getInstanceExplanation(
                        classAssertion.getIndividual().asOWLNamedIndividual(),
                        classAssertion.getClassExpression().asOWLClass());
            } else if (axiom instanceof OWLSubClassOfAxiom) {
                OWLSubClassOfAxiom subClassAxiom = (OWLSubClassOfAxiom) axiom;
                singleExpl = explanation.getSubClassExplanation(
                        subClassAxiom.getSubClass().asOWLClass(),
                        subClassAxiom.getSuperClass().asOWLClass());
            } else {
                singleExpl = explanation.getEntailmentExplanation(axiom);
            }

            if (singleExpl != null && !singleExpl.isEmpty()) {
                paths.add(new ArrayList<>(singleExpl));
            }
        } catch (Exception e) {
            LOGGER.debug("Error getting Pellet explanations for: " + axiom, e);
        }

        return paths;
    }

    /**
     * Optimized method to format and append explanations to a StringBuilder
     */
    private void formatExplanations(Set<List<OWLAxiom>> explanationPaths,
                                    String sectionTitle,
                                    StringBuilder sb) {
        if (explanationPaths.isEmpty()) return;

        sb.append(sectionTitle).append(":\n");
        int pathCount = 1;

        for (List<OWLAxiom> path : explanationPaths) {
            // Always output a path header, even for a single path
            sb.append("Path ").append(pathCount++).append(":\n");
            for (OWLAxiom axiom : path) {
                sb.append("  - ").append(renderAxiomWithShortNames(axiom)).append("\n");
            }
            sb.append("\n");
        }
    }

    /**
     * Find all paths that explain a property relationship through inverse properties
     */
    private Set<List<OWLAxiom>> findInversePropertyPaths(OWLNamedIndividual subject,
                                                         OWLObjectPropertyExpression property,
                                                         OWLNamedIndividual object) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Check all inverse property axioms in the ontology
        for (OWLInverseObjectPropertiesAxiom inv : ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
            OWLObjectPropertyExpression p1 = inv.getFirstProperty(), p2 = inv.getSecondProperty();
            if (p1.equals(property) || p2.equals(property)) {
                OWLObjectPropertyExpression inverseProp = p1.equals(property) ? p2 : p1;
                OWLAxiom inverseAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(
                        inverseProp.asOWLObjectProperty(), object, subject);

                if (ontology.containsAxiom(inverseAssertion) || reasoner.isEntailed(inverseAssertion)) {
                    List<OWLAxiom> path = new ArrayList<>();
                    path.add(inv);
                    path.add(inverseAssertion);
                    paths.add(path);
                }
            }
        }

        // Also check for inverse property expressions
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            // Collect all inverse expressions
            Set<OWLObjectInverseOf> invExpressions = new HashSet<>();

            // Use iterator to avoid stream operations
            for (OWLOntology ont : ontology.getImportsClosure()) {
                for (OWLInverseObjectPropertiesAxiom ax : ont.getInverseObjectPropertyAxioms(prop)) {
                    if (ax.getFirstProperty().equals(prop) && !ax.getSecondProperty().isAnonymous()) {
                        invExpressions.add(dataFactory.getOWLObjectInverseOf(ax.getSecondProperty().asOWLObjectProperty()));
                    } else if (ax.getSecondProperty().equals(prop) && !ax.getFirstProperty().isAnonymous()) {
                        invExpressions.add(dataFactory.getOWLObjectInverseOf(ax.getFirstProperty().asOWLObjectProperty()));
                    }
                }
            }

            for (OWLObjectInverseOf invOf : invExpressions) {
                if (invOf.getInverse().equals(property) || prop.equals(property)) {
                    OWLObjectProperty inverseProp = invOf.getInverse().equals(property) ?
                            prop : invOf.getInverse().asOWLObjectProperty();

                    OWLAxiom inverseAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(
                            inverseProp, object, subject);

                    if (ontology.containsAxiom(inverseAssertion) || reasoner.isEntailed(inverseAssertion)) {
                        OWLAxiom invAxiom = dataFactory.getOWLInverseObjectPropertiesAxiom(property, inverseProp);
                        List<OWLAxiom> path = new ArrayList<>();
                        path.add(invAxiom);
                        path.add(inverseAssertion);
                        paths.add(path);
                    }
                }
            }
        }

        return paths;
    }

    /**
     * Find all paths that explain a property relationship through subproperties
     */
    private Set<List<OWLAxiom>> findSubPropertyPaths(OWLNamedIndividual subject,
                                                     OWLObjectPropertyExpression property,
                                                     OWLNamedIndividual object) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Get all subproperties of the given property
        Set<OWLObjectPropertyExpression> subProperties = new HashSet<>();
        reasoner.getSubObjectProperties(property, false).entities()
                .forEach(p -> subProperties.add(p));

        // For each subproperty, check if there is an assertion between subject and object
        reasoner.getSubObjectProperties(property, false).entities()
                .forEach(subProp -> {
                    if (subProp.isAnonymous()) return;

                    OWLObjectProperty subProperty = subProp.asOWLObjectProperty();
                    OWLAxiom subPropertyAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(subProperty, subject, object);

                    if (ontology.containsAxiom(subPropertyAssertion) || reasoner.isEntailed(subPropertyAssertion)) {
                        // Find the subPropertyOf axiom
                        boolean found = false;
                        for (OWLAxiom ax : ontology.getAxioms()) {
                            if (ax instanceof OWLSubObjectPropertyOfAxiom) {
                                OWLSubObjectPropertyOfAxiom subPropAx = (OWLSubObjectPropertyOfAxiom) ax;
                                if (subPropAx.getSubProperty().equals(subProperty) &&
                                        subPropAx.getSuperProperty().equals(property)) {
                                    List<OWLAxiom> path = new ArrayList<>();
                                    path.add(subPropAx);
                                    path.add(subPropertyAssertion);
                                    paths.add(path);
                                    found = true;
                                    break;
                                }
                            }
                        }

                        // If we couldn't find the explicit axiom, create it
                        if (!found) {
                            OWLAxiom subPropertyAxiom = dataFactory.getOWLSubObjectPropertyOfAxiom(
                                    subProperty, property.asOWLObjectProperty());

                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(subPropertyAxiom);
                            path.add(subPropertyAssertion);
                            paths.add(path);
                        }
                    }
                });

        return paths;
    }

    /**
     * Find all paths that explain a property relationship through property chains
     * This method can handle chains of any length without hardcoding for specific sizes
     */
    private Set<List<OWLAxiom>> findPropertyChainPaths(OWLNamedIndividual subject,
                                                       OWLObjectPropertyExpression property,
                                                       OWLNamedIndividual object) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Get all property chain axioms from the ontology
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiom instanceof OWLSubPropertyChainOfAxiom) {
                OWLSubPropertyChainOfAxiom chainAxiom = (OWLSubPropertyChainOfAxiom) axiom;
                if (chainAxiom.getSuperProperty().equals(property)) {
                    List<OWLObjectPropertyExpression> chain = chainAxiom.getPropertyChain();

                    // Start a search for possible chains
                    findChainPath(subject, object, chainAxiom, chain, 0, new ArrayList<>(), paths);
                }
            }
        }

        return paths;
    }

    /**
     * Recursively find paths through property chains
     * @param startIndividual The starting individual for this chain segment
     * @param endIndividual The target end individual
     * @param chainAxiom The property chain axiom
     * @param chain The list of properties in the chain
     * @param chainIndex Current position in the chain
     * @param currentPath Current path of axioms collected so far
     * @param resultPaths Set of complete paths found
     */
    private void findChainPath(OWLNamedIndividual startIndividual,
                               OWLNamedIndividual endIndividual,
                               OWLSubPropertyChainOfAxiom chainAxiom,
                               List<OWLObjectPropertyExpression> chain,
                               int chainIndex,
                               List<OWLAxiom> currentPath,
                               Set<List<OWLAxiom>> resultPaths) {

        // Base case: we've reached the end of the chain
        if (chainIndex >= chain.size()) {
            // Check if we've reached the target end individual
            if (startIndividual.equals(endIndividual)) {
                // Found a complete path! Add the chain axiom at the beginning
                List<OWLAxiom> completePath = new ArrayList<>();
                completePath.add(chainAxiom); // Add the chain axiom first
                completePath.addAll(currentPath); // Add all property assertions
                resultPaths.add(completePath);
            }
            return;
        }

        // Get the current property in the chain
        OWLObjectPropertyExpression currentProp = chain.get(chainIndex);

        // Prevent deep recursion for large ontologies
        if (currentPath.size() > MAX_EXPLANATION_DEPTH * 2) {
            return;
        }

        // Limit the number of paths we find to avoid performance issues
        if (resultPaths.size() >= MAX_EXPLANATIONS_PER_INFERENCE) {
            return;
        }

        // Find all objects connected to startIndividual via currentProp
        Set<OWLNamedIndividual> nextIndividuals = new HashSet<>();

        // Use reasoner to find connected individuals
        NodeSet<OWLNamedIndividual> propertyValues = reasoner.getObjectPropertyValues(startIndividual, currentProp);
        propertyValues.entities().forEach(ind -> nextIndividuals.add(ind));

        // For each possible next individual
        for (OWLNamedIndividual nextIndividual : nextIndividuals) {
            OWLAxiom link = dataFactory.getOWLObjectPropertyAssertionAxiom(
                    currentProp.asOWLObjectProperty(), startIndividual, nextIndividual);

            // Create updated path
            List<OWLAxiom> updatedPath = new ArrayList<>(currentPath);
            updatedPath.add(link);

            // Recursively find the rest of the chain
            findChainPath(nextIndividual, endIndividual, chainAxiom, chain, chainIndex + 1, updatedPath, resultPaths);
        }

        // Special case: if this is the last property in chain and we have a direct link to the end individual
        if (chainIndex == chain.size() - 1) {
            OWLAxiom finalLink = dataFactory.getOWLObjectPropertyAssertionAxiom(
                    currentProp.asOWLObjectProperty(), startIndividual, endIndividual);

            if (ontology.containsAxiom(finalLink) || reasoner.isEntailed(finalLink)) {
                List<OWLAxiom> updatedPath = new ArrayList<>(currentPath);
                updatedPath.add(finalLink);

                List<OWLAxiom> completePath = new ArrayList<>();
                completePath.add(chainAxiom);
                completePath.addAll(updatedPath);
                resultPaths.add(completePath);
            }
        }
    }

    /**
     * Find all paths that explain a property relationship through equivalent properties
     */
    private Set<List<OWLAxiom>> findEquivalentPropertyPaths(OWLNamedIndividual subject,
                                                            OWLObjectPropertyExpression property,
                                                            OWLNamedIndividual object) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Get all equivalent properties of the given property
        Set<OWLObjectPropertyExpression> equivProperties = new HashSet<>();
        reasoner.getEquivalentObjectProperties(property).entities()
                .forEach(p -> equivProperties.add(p));

        // For each equivalent property, check if there is an assertion between subject and object
        reasoner.getEquivalentObjectProperties(property).entities()
                .forEach(equivProp -> {
                    if (equivProp.equals(property) || equivProp.isAnonymous()) return;

                    OWLObjectProperty equivProperty = equivProp.asOWLObjectProperty();
                    OWLAxiom equivAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(equivProperty, subject, object);

                    if (ontology.containsAxiom(equivAssertion) || reasoner.isEntailed(equivAssertion)) {
                        // Find explicit equivalentProperty axiom if it exists
                        boolean found = false;
                        for (OWLAxiom ax : ontology.getAxioms()) {
                            if (ax instanceof OWLEquivalentObjectPropertiesAxiom) {
                                OWLEquivalentObjectPropertiesAxiom equivAx = (OWLEquivalentObjectPropertiesAxiom) ax;

                                // Check if both properties are in this axiom
                                Set<OWLObjectPropertyExpression> props = new HashSet<>();
                                equivAx.properties().forEach(p -> props.add(p));

                                if (props.contains(property) && props.contains(equivProperty)) {
                                    List<OWLAxiom> path = new ArrayList<>();
                                    path.add(equivAx);
                                    path.add(equivAssertion);
                                    paths.add(path);
                                    found = true;
                                    break;
                                }
                            }
                        }

                        // If we couldn't find the explicit axiom, create it
                        if (!found) {
                            OWLAxiom equivAxiom = dataFactory.getOWLEquivalentObjectPropertiesAxiom(
                                    property.asOWLObjectProperty(), equivProperty);

                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(equivAxiom);
                            path.add(equivAssertion);
                            paths.add(path);
                        }
                    }
                });

        return paths;
    }

    /**
     * Find all paths that explain a type assertion through equivalent classes
     */
    private Set<List<OWLAxiom>> findEquivalentClassPaths(OWLNamedIndividual individual, OWLClass clazz) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Get all equivalent classes of the given class
        Set<OWLClass> equivClasses = new HashSet<>();
        reasoner.getEquivalentClasses(clazz).entities()
                .forEach(c -> {
                    if (!c.equals(clazz)) {
                        equivClasses.add(c);
                    }
                });

        // For each equivalent class, check if the individual is asserted or inferred to be of that type
        reasoner.getEquivalentClasses(clazz).entities()
                .forEach(c -> {
                    if (c.equals(clazz)) return;

                    OWLClass equivClass = c;
                    OWLAxiom equivAssertion = dataFactory.getOWLClassAssertionAxiom(equivClass, individual);

                    if (ontology.containsAxiom(equivAssertion) || reasoner.isEntailed(equivAssertion)) {
                        // Find explicit equivalentClasses axiom if it exists
                        boolean found = false;
                        for (OWLAxiom ax : ontology.getAxioms()) {
                            if (ax instanceof OWLEquivalentClassesAxiom) {
                                OWLEquivalentClassesAxiom equivAx = (OWLEquivalentClassesAxiom) ax;

                                // Check if the axiom contains both classes
                                Set<OWLClass> classes = new HashSet<>();
                                for (OWLClassExpression ce : equivAx.getClassExpressions()) {
                                    if (!ce.isAnonymous()) {
                                        classes.add(ce.asOWLClass());
                                    }
                                }

                                if (classes.contains(clazz) && classes.contains(equivClass)) {
                                    List<OWLAxiom> path = new ArrayList<>();
                                    path.add(equivAx);
                                    path.add(equivAssertion);
                                    paths.add(path);
                                    found = true;
                                    break;
                                }
                            }
                        }

                        // If we couldn't find the explicit axiom, create it
                        if (!found) {
                            OWLAxiom equivAxiom = dataFactory.getOWLEquivalentClassesAxiom(clazz, equivClass);

                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(equivAxiom);
                            path.add(equivAssertion);
                            paths.add(path);
                        }
                    }
                });

        return paths;
    }

    /**
     * Find all paths that explain a type assertion through subclasses
     */
    private Set<List<OWLAxiom>> findSubClassPaths(OWLNamedIndividual individual, OWLClass clazz) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Get all subclasses of the given class
        Set<OWLClass> subClasses = new HashSet<>();
        reasoner.getSubClasses(clazz, false).entities()
                .forEach(c -> {
                    if (!c.isOWLNothing()) {
                        subClasses.add(c);
                    }
                });

        // For each subclass, check if the individual is asserted or inferred to be of that type
        reasoner.getSubClasses(clazz, false).entities()
                .forEach(c -> {
                    if (c.isOWLNothing()) return;

                    OWLClass subClass = c;
                    OWLAxiom subClassAssertion = dataFactory.getOWLClassAssertionAxiom(subClass, individual);

                    if (ontology.containsAxiom(subClassAssertion) || reasoner.isEntailed(subClassAssertion)) {
                        // Find explicit subClassOf axiom if it exists
                        boolean found = false;
                        for (OWLAxiom ax : ontology.getAxioms()) {
                            if (ax instanceof OWLSubClassOfAxiom) {
                                OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;
                                if (subClassAx.getSubClass().equals(subClass) &&
                                        subClassAx.getSuperClass().equals(clazz)) {
                                    List<OWLAxiom> path = new ArrayList<>();
                                    path.add(subClassAx);
                                    path.add(subClassAssertion);
                                    paths.add(path);
                                    found = true;
                                    break;
                                }
                            }
                        }

                        // If we couldn't find the explicit axiom, create it
                        if (!found) {
                            OWLAxiom subClassAxiom = dataFactory.getOWLSubClassOfAxiom(subClass, clazz);

                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(subClassAxiom);
                            path.add(subClassAssertion);
                            paths.add(path);
                        }
                    }
                });

        return paths;
    }

    /**
     * Find all paths that explain a type assertion through domain/range axioms
     */
    private Set<List<OWLAxiom>> findDomainRangePaths(OWLNamedIndividual individual, OWLClass clazz) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Check domain axioms - if individual is the subject of a property with domain clazz
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            // Check if the class is in the domain of any property
            NodeSet<OWLClass> domains = reasoner.getObjectPropertyDomains(prop, false);
            if (domains.containsEntity(clazz)) {
                // Look for property assertions with this individual as subject
                for (OWLNamedIndividual obj : ontology.getIndividualsInSignature()) {
                    OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(prop, individual, obj);
                    if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                        // Find explicit domain axiom if it exists
                        boolean found = false;
                        for (OWLAxiom ax : ontology.getAxioms()) {
                            if (ax instanceof OWLObjectPropertyDomainAxiom) {
                                OWLObjectPropertyDomainAxiom domainAx = (OWLObjectPropertyDomainAxiom) ax;
                                if (domainAx.getProperty().equals(prop) &&
                                        domainAx.getDomain().equals(clazz)) {
                                    List<OWLAxiom> path = new ArrayList<>();
                                    path.add(domainAx);
                                    path.add(propAssertion);
                                    paths.add(path);
                                    found = true;
                                    break;
                                }
                            }
                        }

                        // If we couldn't find the explicit axiom, create it
                        if (!found) {
                            OWLAxiom domainAxiom = dataFactory.getOWLObjectPropertyDomainAxiom(prop, clazz);

                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(domainAxiom);
                            path.add(propAssertion);
                            paths.add(path);
                        }
                    }
                }
            }

            // Check range axioms - if individual is the object of a property with range clazz
            NodeSet<OWLClass> ranges = reasoner.getObjectPropertyRanges(prop, false);
            if (ranges.containsEntity(clazz)) {
                // Look for property assertions with this individual as object
                for (OWLNamedIndividual subj : ontology.getIndividualsInSignature()) {
                    OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(prop, subj, individual);
                    if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                        // Find explicit range axiom if it exists
                        boolean found = false;
                        for (OWLAxiom ax : ontology.getAxioms()) {
                            if (ax instanceof OWLObjectPropertyRangeAxiom) {
                                OWLObjectPropertyRangeAxiom rangeAx = (OWLObjectPropertyRangeAxiom) ax;
                                if (rangeAx.getProperty().equals(prop) &&
                                        rangeAx.getRange().equals(clazz)) {
                                    List<OWLAxiom> path = new ArrayList<>();
                                    path.add(rangeAx);
                                    path.add(propAssertion);
                                    paths.add(path);
                                    found = true;
                                    break;
                                }
                            }
                        }

                        // If we couldn't find the explicit axiom, create it
                        if (!found) {
                            OWLAxiom rangeAxiom = dataFactory.getOWLObjectPropertyRangeAxiom(prop, clazz);

                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(rangeAxiom);
                            path.add(propAssertion);
                            paths.add(path);
                        }
                    }
                }
            }
        }

        return paths;
    }

    /**
     * Find paths that explain a subsumption relationship through equivalent classes
     */
    private Set<List<OWLAxiom>> findEquivalentClassesForSubsumption(OWLClass subClass, OWLClass superClass) {
        Set<List<OWLAxiom>> paths = new HashSet<>();

        // Check if any equivalent class of subClass is a subclass of superClass
        Set<OWLClass> equivToSub = new HashSet<>();
        reasoner.getEquivalentClasses(subClass).entities()
                .forEach(c -> {
                    if (!c.equals(subClass)) {
                        equivToSub.add(c);
                    }
                });

        // Limit to 5 equivalent classes to avoid explosion
        int count = 0;
        for (OWLClass equivClass : equivToSub) {
            if (count++ >= 5) break;

            if (reasoner.isEntailed(dataFactory.getOWLSubClassOfAxiom(equivClass, superClass))) {
                // Find explicit equivalentClasses axiom if it exists
                OWLAxiom equivAxiom = null;
                for (OWLAxiom ax : ontology.getAxioms()) {
                    if (ax instanceof OWLEquivalentClassesAxiom) {
                        OWLEquivalentClassesAxiom equivAx = (OWLEquivalentClassesAxiom) ax;

                        // Check if the axiom contains both classes
                        Set<OWLClass> classes = new HashSet<>();
                        for (OWLClassExpression ce : equivAx.getClassExpressions()) {
                            if (!ce.isAnonymous()) {
                                classes.add(ce.asOWLClass());
                            }
                        }

                        if (classes.contains(subClass) && classes.contains(equivClass)) {
                            equivAxiom = equivAx;
                            break;
                        }
                    }
                }

                // If we couldn't find the explicit axiom, create it
                if (equivAxiom == null) {
                    equivAxiom = dataFactory.getOWLEquivalentClassesAxiom(subClass, equivClass);
                }

                // Find explicit subClassOf axiom if it exists
                OWLAxiom subClassAxiom = null;
                for (OWLAxiom ax : ontology.getAxioms()) {
                    if (ax instanceof OWLSubClassOfAxiom) {
                        OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;
                        if (subClassAx.getSubClass().equals(equivClass) &&
                                subClassAx.getSuperClass().equals(superClass)) {
                            subClassAxiom = subClassAx;
                            break;
                        }
                    }
                }

                // If we couldn't find the explicit axiom, create it
                if (subClassAxiom == null) {
                    subClassAxiom = dataFactory.getOWLSubClassOfAxiom(equivClass, superClass);
                }

                List<OWLAxiom> path = new ArrayList<>();
                path.add(equivAxiom);
                path.add(subClassAxiom);
                paths.add(path);
            }
        }

        // Check if subClass is a subclass of any equivalent class of superClass
        Set<OWLClass> equivToSuper = new HashSet<>();
        reasoner.getEquivalentClasses(superClass).entities()
                .forEach(c -> {
                    if (!c.equals(superClass)) {
                        equivToSuper.add(c);
                    }
                });

        // Limit to 5 equivalent classes to avoid explosion
        count = 0;
        for (OWLClass equivClass : equivToSuper) {
            if (count++ >= 5) break;

            if (reasoner.isEntailed(dataFactory.getOWLSubClassOfAxiom(subClass, equivClass))) {
                // Find explicit equivalentClasses axiom if it exists
                OWLAxiom equivAxiom = null;
                for (OWLAxiom ax : ontology.getAxioms()) {
                    if (ax instanceof OWLEquivalentClassesAxiom) {
                        OWLEquivalentClassesAxiom equivAx = (OWLEquivalentClassesAxiom) ax;

                        // Check if the axiom contains both classes
                        Set<OWLClass> classes = new HashSet<>();
                        for (OWLClassExpression ce : equivAx.getClassExpressions()) {
                            if (!ce.isAnonymous()) {
                                classes.add(ce.asOWLClass());
                            }
                        }

                        if (classes.contains(superClass) && classes.contains(equivClass)) {
                            equivAxiom = equivAx;
                            break;
                        }
                    }
                }

                // If we couldn't find the explicit axiom, create it
                if (equivAxiom == null) {
                    equivAxiom = dataFactory.getOWLEquivalentClassesAxiom(superClass, equivClass);
                }

                // Find explicit subClassOf axiom if it exists
                OWLAxiom subClassAxiom = null;
                for (OWLAxiom ax : ontology.getAxioms()) {
                    if (ax instanceof OWLSubClassOfAxiom) {
                        OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;
                        if (subClassAx.getSubClass().equals(subClass) &&
                                subClassAx.getSuperClass().equals(equivClass)) {
                            subClassAxiom = subClassAx;
                            break;
                        }
                    }
                }

                // If we couldn't find the explicit axiom, create it
                if (subClassAxiom == null) {
                    subClassAxiom = dataFactory.getOWLSubClassOfAxiom(subClass, equivClass);
                }

                List<OWLAxiom> path = new ArrayList<>();
                path.add(equivAxiom);
                path.add(subClassAxiom);
                paths.add(path);
            }
        }

        return paths;
    }

    /**
     * Enhanced method to get multiple explanations from Pellet for any axiom type
     */
    private Set<Set<OWLAxiom>> getAllPelletExplanations(OWLAxiom axiom) {
        Set<Set<OWLAxiom>> results = new HashSet<>();

        try {
            // First, try to use the reflection approach to get multiple explanations
            try {
                if (axiom instanceof OWLClassAssertionAxiom) {
                    OWLClassAssertionAxiom classAssertion = (OWLClassAssertionAxiom) axiom;
                    OWLNamedIndividual individual = classAssertion.getIndividual().asOWLNamedIndividual();
                    OWLClass clazz = classAssertion.getClassExpression().asOWLClass();

                    // Try to access the multiple explanation method via reflection
                    try {
                        java.lang.reflect.Method getExplanationsMethod = explanation.getClass().getMethod(
                                "getInstanceExplanations", OWLNamedIndividual.class, OWLClass.class, int.class);

                        @SuppressWarnings("unchecked")
                        Set<Set<OWLAxiom>> moreExplanations =
                                (Set<Set<OWLAxiom>>) getExplanationsMethod.invoke(
                                        explanation, individual, clazz, MAX_EXPLANATIONS_PER_INFERENCE);

                        if (moreExplanations != null && !moreExplanations.isEmpty()) {
                            results.addAll(moreExplanations);
                            LOGGER.debug("Found {} multiple explanations via reflection", moreExplanations.size());
                            return results; // Return early if we got good explanations
                        }
                    } catch (Exception e) {
                        LOGGER.debug("getInstanceExplanations method reflection failed: " + e.getMessage());
                    }
                } else if (axiom instanceof OWLSubClassOfAxiom) {
                    OWLSubClassOfAxiom subClassAxiom = (OWLSubClassOfAxiom) axiom;
                    OWLClass subClass = subClassAxiom.getSubClass().asOWLClass();
                    OWLClass superClass = subClassAxiom.getSuperClass().asOWLClass();

                    // Try to access the multiple explanation method via reflection
                    try {
                        java.lang.reflect.Method getExplanationsMethod = explanation.getClass().getMethod(
                                "getSubClassExplanations", OWLClass.class, OWLClass.class, int.class);

                        @SuppressWarnings("unchecked")
                        Set<Set<OWLAxiom>> moreExplanations =
                                (Set<Set<OWLAxiom>>) getExplanationsMethod.invoke(
                                        explanation, subClass, superClass, MAX_EXPLANATIONS_PER_INFERENCE);

                        if (moreExplanations != null && !moreExplanations.isEmpty()) {
                            results.addAll(moreExplanations);
                            LOGGER.debug("Found {} multiple explanations via reflection", moreExplanations.size());
                            return results; // Return early if we got good explanations
                        }
                    } catch (Exception e) {
                        LOGGER.debug("getSubClassExplanations method reflection failed: " + e.getMessage());
                    }
                }

                // Generic approach for any axiom type
                try {
                    java.lang.reflect.Method getExplanationsMethod = explanation.getClass().getMethod(
                            "getExplanations", OWLAxiom.class, int.class);

                    @SuppressWarnings("unchecked")
                    Set<Set<OWLAxiom>> moreExplanations =
                            (Set<Set<OWLAxiom>>) getExplanationsMethod.invoke(
                                    explanation, axiom, MAX_EXPLANATIONS_PER_INFERENCE);

                    if (moreExplanations != null && !moreExplanations.isEmpty()) {
                        results.addAll(moreExplanations);
                        LOGGER.debug("Found {} generic multiple explanations via reflection", moreExplanations.size());
                        return results; // Return early if we got good explanations
                    }
                } catch (Exception e) {
                    LOGGER.debug("getExplanations method reflection failed: " + e.getMessage());
                }
            } catch (Exception e) {
                LOGGER.debug("Error in reflection approach: " + e.getMessage());
            }

            // If reflection failed, try the standard API approach
            try {
                Set<OWLAxiom> singleExpl;

                if (axiom instanceof OWLClassAssertionAxiom) {
                    OWLClassAssertionAxiom classAssertion = (OWLClassAssertionAxiom) axiom;
                    singleExpl = explanation.getInstanceExplanation(
                            classAssertion.getIndividual().asOWLNamedIndividual(),
                            classAssertion.getClassExpression().asOWLClass());
                } else if (axiom instanceof OWLSubClassOfAxiom) {
                    OWLSubClassOfAxiom subClassAxiom = (OWLSubClassOfAxiom) axiom;
                    singleExpl = explanation.getSubClassExplanation(
                            subClassAxiom.getSubClass().asOWLClass(),
                            subClassAxiom.getSuperClass().asOWLClass());
                } else {
                    singleExpl = explanation.getEntailmentExplanation(axiom);
                }

                if (singleExpl != null && !singleExpl.isEmpty()) {
                    results.add(new HashSet<>(singleExpl));
                    LOGGER.debug("Found single explanation with size {}", singleExpl.size());
                }
            } catch (Exception e) {
                LOGGER.debug("Error getting Pellet explanations for: " + axiom, e);
            }

        } catch (Exception e) {
            LOGGER.debug("Error in getAllPelletExplanations: " + e.getMessage());
        }

        return results;
    }



    /**
     * Find explanations through domain and range axioms for class assertions
     */
    private Set<Set<OWLAxiom>> findDomainRangeExplanationsForClass(OWLNamedIndividual individual, OWLClass clazz) {
        Set<Set<OWLAxiom>> results = new HashSet<>();

        try {
            // Check for domain axioms - individual as subject
            for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                NodeSet<OWLClass> domains = reasoner.getObjectPropertyDomains(prop, false);
                if (domains.containsEntity(clazz)) {
                    // Find all assertions with this individual as subject
                    for (OWLNamedIndividual obj : ontology.getIndividualsInSignature()) {
                        OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(prop, individual, obj);
                        if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                            // Find domain axiom
                            OWLAxiom domainAxiom = dataFactory.getOWLObjectPropertyDomainAxiom(prop, clazz);

                            // Create an explanation
                            Set<OWLAxiom> explanation = new HashSet<>();
                            explanation.add(domainAxiom);
                            explanation.add(propAssertion);
                            results.add(explanation);

                            // Look for property hierarchies
                            reasoner.getSubObjectProperties(prop, false).entities().forEach(subProp -> {
                                if (subProp.isAnonymous()) return;

                                OWLObjectProperty subProperty = subProp.asOWLObjectProperty();
                                OWLAxiom subPropAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(subProperty, individual, obj);
                                if (ontology.containsAxiom(subPropAssertion) || reasoner.isEntailed(subPropAssertion)) {
                                    OWLAxiom subPropAxiom = dataFactory.getOWLSubObjectPropertyOfAxiom(subProperty, prop);

                                    Set<OWLAxiom> subPropExplanation = new HashSet<>();
                                    subPropExplanation.add(domainAxiom);
                                    subPropExplanation.add(subPropAxiom);
                                    subPropExplanation.add(subPropAssertion);
                                    results.add(subPropExplanation);
                                }
                            });
                        }
                    }
                }

                // Check for range axioms - individual as object
                NodeSet<OWLClass> ranges = reasoner.getObjectPropertyRanges(prop, false);
                if (ranges.containsEntity(clazz)) {
                    // Find all assertions with this individual as object
                    for (OWLNamedIndividual subj : ontology.getIndividualsInSignature()) {
                        OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(prop, subj, individual);
                        if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                            // Find range axiom
                            OWLAxiom rangeAxiom = dataFactory.getOWLObjectPropertyRangeAxiom(prop, clazz);

                            // Create an explanation
                            Set<OWLAxiom> explanation = new HashSet<>();
                            explanation.add(rangeAxiom);
                            explanation.add(propAssertion);
                            results.add(explanation);

                            // Look for property hierarchies
                            reasoner.getSubObjectProperties(prop, false).entities().forEach(subProp -> {
                                if (subProp.isAnonymous()) return;

                                OWLObjectProperty subProperty = subProp.asOWLObjectProperty();
                                OWLAxiom subPropAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(subProperty, subj, individual);
                                if (ontology.containsAxiom(subPropAssertion) || reasoner.isEntailed(subPropAssertion)) {
                                    OWLAxiom subPropAxiom = dataFactory.getOWLSubObjectPropertyOfAxiom(subProperty, prop);

                                    Set<OWLAxiom> subPropExplanation = new HashSet<>();
                                    subPropExplanation.add(rangeAxiom);
                                    subPropExplanation.add(subPropAxiom);
                                    subPropExplanation.add(subPropAssertion);
                                    results.add(subPropExplanation);
                                }
                            });

                            // Look for inverse properties
                            reasoner.getInverseObjectProperties(prop).entities().forEach(invProp -> {
                                if (invProp.isAnonymous()) return;

                                OWLObjectProperty inverseProp = invProp.asOWLObjectProperty();
                                OWLAxiom invPropAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(inverseProp, individual, subj);
                                if (ontology.containsAxiom(invPropAssertion) || reasoner.isEntailed(invPropAssertion)) {
                                    OWLAxiom invPropAxiom = dataFactory.getOWLInverseObjectPropertiesAxiom(prop, inverseProp);

                                    Set<OWLAxiom> invPropExplanation = new HashSet<>();
                                    invPropExplanation.add(rangeAxiom);
                                    invPropExplanation.add(invPropAxiom);
                                    invPropExplanation.add(invPropAssertion);
                                    results.add(invPropExplanation);
                                }
                            });
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in findDomainRangeExplanationsForClass: " + e.getMessage());
        }

        return results;
    }

    /**
     * Find explanations through functional properties
     */
    private Set<Set<OWLAxiom>> findFunctionalPropertyExplanations(OWLNamedIndividual individual, OWLClass clazz) {
        Set<Set<OWLAxiom>> results = new HashSet<>();

        try {
            // Check all functional properties
            for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                if (reasoner.isEntailed(dataFactory.getOWLFunctionalObjectPropertyAxiom(prop))) {
                    // Check if this property has range that involves the class
                    NodeSet<OWLClass> ranges = reasoner.getObjectPropertyRanges(prop, false);
                    if (ranges.containsEntity(clazz)) {
                        // Find all assertions with this individual as subject
                        for (OWLNamedIndividual obj : ontology.getIndividualsInSignature()) {
                            OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(prop, individual, obj);
                            if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                                // Check if the object is of the required class
                                OWLAxiom objClassAssertion = dataFactory.getOWLClassAssertionAxiom(clazz, obj);
                                if (ontology.containsAxiom(objClassAssertion) || reasoner.isEntailed(objClassAssertion)) {
                                    // Found an explanation via functional property
                                    OWLAxiom funcAxiom = dataFactory.getOWLFunctionalObjectPropertyAxiom(prop);
                                    OWLAxiom rangeAxiom = dataFactory.getOWLObjectPropertyRangeAxiom(prop, clazz);

                                    Set<OWLAxiom> explanation = new HashSet<>();
                                    explanation.add(funcAxiom);
                                    explanation.add(rangeAxiom);
                                    explanation.add(propAssertion);
                                    results.add(explanation);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in findFunctionalPropertyExplanations: " + e.getMessage());
        }

        return results;
    }

    /**
     * Find explanations through property hierarchies
     */
    private Set<Set<OWLAxiom>> findPropertyHierarchyExplanations(OWLNamedIndividual individual, OWLClass clazz) {
        Set<Set<OWLAxiom>> results = new HashSet<>();

        try {
            // Check all properties to find property chains that lead to the class
            for (OWLObjectProperty prop1 : ontology.getObjectPropertiesInSignature()) {
                for (OWLObjectProperty prop2 : ontology.getObjectPropertiesInSignature()) {
                    // Look for a chain: prop1 o prop2 -> someProperty where someProperty has range/domain clazz
                    for (OWLAxiom ax : ontology.getAxioms()) {
                        if (ax instanceof OWLSubPropertyChainOfAxiom) {
                            OWLSubPropertyChainOfAxiom chainAx = (OWLSubPropertyChainOfAxiom) ax;
                            List<OWLObjectPropertyExpression> chain = chainAx.getPropertyChain();

                            // Check for 2-step chains
                            if (chain.size() == 2 &&
                                    chain.get(0).equals(prop1) &&
                                    chain.get(1).equals(prop2)) {

                                OWLObjectPropertyExpression targetProp = chainAx.getSuperProperty();

                                // Check if targetProp has domain/range of clazz
                                boolean isDomainOrRange = false;
                                if (reasoner.getObjectPropertyDomains(targetProp, false).containsEntity(clazz) ||
                                        reasoner.getObjectPropertyRanges(targetProp, false).containsEntity(clazz)) {
                                    isDomainOrRange = true;
                                }

                                if (isDomainOrRange) {
                                    // Look for instances connected through this chain
                                    for (OWLNamedIndividual middle : ontology.getIndividualsInSignature()) {
                                        OWLAxiom firstLink = dataFactory.getOWLObjectPropertyAssertionAxiom(
                                                prop1.asOWLObjectProperty(), individual, middle);

                                        for (OWLNamedIndividual end : ontology.getIndividualsInSignature()) {
                                            OWLAxiom secondLink = dataFactory.getOWLObjectPropertyAssertionAxiom(
                                                    prop2.asOWLObjectProperty(), middle, end);

                                            if ((ontology.containsAxiom(firstLink) || reasoner.isEntailed(firstLink)) &&
                                                    (ontology.containsAxiom(secondLink) || reasoner.isEntailed(secondLink))) {

                                                // Found a valid chain path
                                                Set<OWLAxiom> explanation = new HashSet<>();
                                                explanation.add(chainAx);
                                                explanation.add(firstLink);
                                                explanation.add(secondLink);

                                                // Add domain/range axiom for the target property
                                                if (reasoner.getObjectPropertyDomains(targetProp, false).containsEntity(clazz)) {
                                                    explanation.add(dataFactory.getOWLObjectPropertyDomainAxiom(
                                                            targetProp.asOWLObjectProperty(), clazz));
                                                } else {
                                                    explanation.add(dataFactory.getOWLObjectPropertyRangeAxiom(
                                                            targetProp.asOWLObjectProperty(), clazz));
                                                }

                                                results.add(explanation);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in findPropertyHierarchyExplanations: " + e.getMessage());
        }

        return results;
    }

    /**
     * Find explanations through class restrictions (someValuesFrom, allValuesFrom, etc.)
     */
    private Set<Set<OWLAxiom>> findClassRestrictionExplanations(OWLNamedIndividual individual, OWLClass clazz) {
        Set<Set<OWLAxiom>> results = new HashSet<>();

        try {
            // Check all subclass axioms for restrictions that might lead to the class
            for (OWLAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
                OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;

                // Check if the superclass expression is our target class
                if (subClassAx.getSuperClass().equals(clazz)) {
                    OWLClassExpression subClass = subClassAx.getSubClass();

                    // Check if the individual is an instance of the subclass
                    if (reasoner.isEntailed(dataFactory.getOWLClassAssertionAxiom(subClass, individual))) {
                        // Found a potential explanation via subclass
                        Set<OWLAxiom> explanation = new HashSet<>();
                        explanation.add(subClassAx);
                        explanation.add(dataFactory.getOWLClassAssertionAxiom(subClass, individual));
                        results.add(explanation);
                    }
                }

                // Check for property restrictions in the superclass that might involve our target class
                if (subClassAx.getSuperClass().getClassExpressionType().equals(ClassExpressionType.OBJECT_SOME_VALUES_FROM)) {
                    OWLObjectSomeValuesFrom someRestriction = (OWLObjectSomeValuesFrom) subClassAx.getSuperClass();

                    // Check if the filler is our target class
                    if (someRestriction.getFiller().equals(clazz)) {
                        OWLObjectPropertyExpression prop = someRestriction.getProperty();
                        OWLClassExpression domain = subClassAx.getSubClass();

                        // Check if the individual is in the domain
                        if (reasoner.isEntailed(dataFactory.getOWLClassAssertionAxiom(domain, individual))) {
                            // Look for objects connected through this property
                            for (OWLNamedIndividual obj : ontology.getIndividualsInSignature()) {
                                OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(
                                        prop.asOWLObjectProperty(), individual, obj);

                                if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                                    // Found an explanation via someValuesFrom restriction
                                    Set<OWLAxiom> explanation = new HashSet<>();
                                    explanation.add(subClassAx);
                                    explanation.add(dataFactory.getOWLClassAssertionAxiom(domain, individual));
                                    explanation.add(propAssertion);
                                    explanation.add(dataFactory.getOWLClassAssertionAxiom(clazz, obj));
                                    results.add(explanation);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in findClassRestrictionExplanations: " + e.getMessage());
        }

        return results;
    }

    /**
     * Find all paths explaining a subsumption relationship through intermediate classes
     */
    private Set<List<OWLAxiom>> findIntermediateClassPaths(OWLClass subClass, OWLClass superClass) {
        Set<List<OWLAxiom>> paths = new HashSet<>();
        Queue<PathWithIntermediate> queue = new LinkedList<>();
        Set<OWLClass> visited = new HashSet<>();

        // Initialize with direct superclasses
        Set<OWLClass> directSupers = new HashSet<>();
        reasoner.getSuperClasses(subClass, true).entities()
                .forEach(c -> {
                    if (!c.equals(superClass) && !c.isOWLThing()) {
                        directSupers.add(c);
                    }
                });

        for (OWLClass mid : directSupers) {
            // Find explicit subClassOf axiom for the first link if it exists
            OWLAxiom firstLink = null;
            for (OWLAxiom ax : ontology.getAxioms()) {
                if (ax instanceof OWLSubClassOfAxiom) {
                    OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;
                    if (subClassAx.getSubClass().equals(subClass) &&
                            subClassAx.getSuperClass().equals(mid)) {
                        firstLink = subClassAx;
                        break;
                    }
                }
            }

            // If we couldn't find the explicit axiom, create it
            if (firstLink == null) {
                firstLink = dataFactory.getOWLSubClassOfAxiom(subClass, mid);
            }

            List<OWLAxiom> path = new ArrayList<>();
            path.add(firstLink);
            queue.add(new PathWithIntermediate(path, mid));
            visited.add(mid);
        }

        // BFS to find all paths through the class hierarchy
        while (!queue.isEmpty() && paths.size() < MAX_EXPLANATIONS_PER_INFERENCE) {
            PathWithIntermediate current = queue.poll();
            OWLClass currentClass = current.intermediateClass;
            List<OWLAxiom> currentPath = current.path;

            // Check if we found a path to superClass
            if (reasoner.isEntailed(dataFactory.getOWLSubClassOfAxiom(currentClass, superClass))) {
                // Find explicit subClassOf axiom for the final link if it exists
                OWLAxiom finalLink = null;
                for (OWLAxiom ax : ontology.getAxioms()) {
                    if (ax instanceof OWLSubClassOfAxiom) {
                        OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;
                        if (subClassAx.getSubClass().equals(currentClass) &&
                                subClassAx.getSuperClass().equals(superClass)) {
                            finalLink = subClassAx;
                            break;
                        }
                    }
                }

                // If we couldn't find the explicit axiom, create it
                if (finalLink == null) {
                    finalLink = dataFactory.getOWLSubClassOfAxiom(currentClass, superClass);
                }

                List<OWLAxiom> completePath = new ArrayList<>(currentPath);
                completePath.add(finalLink);
                paths.add(completePath);
                continue; // Skip adding more intermediate classes for this path
            }

            // Add next level of superclasses, but limit depth to avoid explosion
            if (currentPath.size() < MAX_EXPLANATION_DEPTH) {
                Set<OWLClass> nextSupers = new HashSet<>();
                reasoner.getSuperClasses(currentClass, true).entities()
                        .forEach(c -> {
                            if (!c.equals(superClass) && !c.isOWLThing() && !visited.contains(c)) {
                                nextSupers.add(c);
                            }
                        });

                for (OWLClass next : nextSupers) {
                    // Find explicit subClassOf axiom for this link if it exists
                    OWLAxiom nextLink = null;
                    for (OWLAxiom ax : ontology.getAxioms()) {
                        if (ax instanceof OWLSubClassOfAxiom) {
                            OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;
                            if (subClassAx.getSubClass().equals(currentClass) &&
                                    subClassAx.getSuperClass().equals(next)) {
                                nextLink = subClassAx;
                                break;
                            }
                        }
                    }

                    // If we couldn't find the explicit axiom, create it
                    if (nextLink == null) {
                        nextLink = dataFactory.getOWLSubClassOfAxiom(currentClass, next);
                    }

                    List<OWLAxiom> newPath = new ArrayList<>(currentPath);
                    newPath.add(nextLink);
                    queue.add(new PathWithIntermediate(newPath, next));
                    visited.add(next);
                }
            }
        }

        return paths;
    }

    // Helper class for BFS path finding
    private static class PathWithIntermediate {
        List<OWLAxiom> path;
        OWLClass intermediateClass;

        PathWithIntermediate(List<OWLAxiom> path, OWLClass intermediateClass) {
            this.path = path;
            this.intermediateClass = intermediateClass;
        }
    }

    /**
     * Rendering methods for better readability
     */
    private String renderProp(OWLObjectPropertyExpression property) {
        if (property.isAnonymous()) {
            return property.toString();
        } else {
            String shortForm = shortFormProvider.getShortForm(property.asOWLObjectProperty());
            return removePrefix(shortForm);
        }
    }

    private String removePrefix(String predicateWithPrefix) {
        if (predicateWithPrefix == null) return "";
        String[] prefixes = {"rdfs:", "rdf:", "owl:", "xsd:", "foaf:", "dc:", "skos:"};
        for (String p : prefixes) {
            if (predicateWithPrefix.startsWith(p)) {
                return predicateWithPrefix.substring(p.length());
            }
        }
        int idx = predicateWithPrefix.indexOf(':');
        if (idx > 0 && idx < predicateWithPrefix.length() - 1) {
            return predicateWithPrefix.substring(idx + 1);
        }
        return predicateWithPrefix;
    }

    /**
     * Render an axiom using short names for better readability
     */
    private String renderAxiomWithShortNames(OWLAxiom axiom) {
        if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom propAx = (OWLObjectPropertyAssertionAxiom) axiom;
            return shortFormProvider.getShortForm(propAx.getSubject().asOWLNamedIndividual()) +
                    " " + shortFormProvider.getShortForm(propAx.getProperty().asOWLObjectProperty()) +
                    " " + shortFormProvider.getShortForm(propAx.getObject().asOWLNamedIndividual());
        } else if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom classAx = (OWLClassAssertionAxiom) axiom;
            return shortFormProvider.getShortForm(classAx.getIndividual().asOWLNamedIndividual()) +
                    " type " + shortFormProvider.getShortForm(classAx.getClassExpression().asOWLClass());
        } else if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) axiom;
            if (subClassAx.getSubClass().isAnonymous() || subClassAx.getSuperClass().isAnonymous()) {
                return axiom.toString().replaceAll("([A-Za-z0-9]+:)", "");
            }
            return shortFormProvider.getShortForm(subClassAx.getSubClass().asOWLClass()) +
                    " subClassOf " + shortFormProvider.getShortForm(subClassAx.getSuperClass().asOWLClass());
        } else if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
            OWLSubObjectPropertyOfAxiom subPropAx = (OWLSubObjectPropertyOfAxiom) axiom;
            return shortFormProvider.getShortForm(subPropAx.getSubProperty().asOWLObjectProperty()) +
                    " SubPropertyOf " +
                    shortFormProvider.getShortForm(subPropAx.getSuperProperty().asOWLObjectProperty());
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            OWLInverseObjectPropertiesAxiom invAx = (OWLInverseObjectPropertiesAxiom) axiom;
            return shortFormProvider.getShortForm(invAx.getFirstProperty().asOWLObjectProperty()) +
                    " InverseOf " +
                    shortFormProvider.getShortForm(invAx.getSecondProperty().asOWLObjectProperty());
        } else if (axiom instanceof OWLEquivalentClassesAxiom) {
            OWLEquivalentClassesAxiom eqAx = (OWLEquivalentClassesAxiom) axiom;
            StringBuilder sb = new StringBuilder();

            // Manual iteration through class expressions
            boolean first = true;
            for (OWLClassExpression ce : eqAx.getClassExpressions()) {
                if (!first) sb.append(" EquivalentTo ");
                first = false;
                if (ce.isAnonymous()) {
                    sb.append(ce.toString().replaceAll("([A-Za-z0-9]+:)", ""));
                } else {
                    sb.append(shortFormProvider.getShortForm(ce.asOWLClass()));
                }

                // Just show the first two expressions - to avoid too much complexity
                if (!first) break;
            }

            return sb.toString();
        } else if (axiom instanceof OWLEquivalentObjectPropertiesAxiom) {
            OWLEquivalentObjectPropertiesAxiom eqAx = (OWLEquivalentObjectPropertiesAxiom) axiom;
            StringBuilder sb = new StringBuilder();

            // Manual iteration through property expressions
            boolean first = true;
            for (OWLObjectPropertyExpression pe : eqAx.getProperties()) {
                if (!first) sb.append(" EquivalentTo ");
                first = false;
                if (pe.isAnonymous()) {
                    sb.append(pe.toString());
                } else {
                    sb.append(shortFormProvider.getShortForm(pe.asOWLObjectProperty()));
                }

                // Just show the first two expressions
                if (!first) break;
            }

            return sb.toString();
        } else if (axiom instanceof OWLObjectPropertyDomainAxiom) {
            OWLObjectPropertyDomainAxiom domainAx = (OWLObjectPropertyDomainAxiom) axiom;
            return "Domain(" + shortFormProvider.getShortForm(domainAx.getProperty().asOWLObjectProperty()) +
                    ") = " + shortFormProvider.getShortForm(domainAx.getDomain().asOWLClass());
        } else if (axiom instanceof OWLObjectPropertyRangeAxiom) {
            OWLObjectPropertyRangeAxiom rangeAx = (OWLObjectPropertyRangeAxiom) axiom;
            return "Range(" + shortFormProvider.getShortForm(rangeAx.getProperty().asOWLObjectProperty()) +
                    ") = " + shortFormProvider.getShortForm(rangeAx.getRange().asOWLClass());
        } else if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            OWLSubPropertyChainOfAxiom chainAx = (OWLSubPropertyChainOfAxiom) axiom;
            StringBuilder sb = new StringBuilder();

            boolean first = true;
            for (OWLObjectPropertyExpression pe : chainAx.getPropertyChain()) {
                if (!first) sb.append(" o ");
                first = false;
                if (pe.isAnonymous()) {
                    sb.append(pe.toString());
                } else {
                    sb.append(shortFormProvider.getShortForm(pe.asOWLObjectProperty()));
                }
            }
            sb.append(" SubPropertyOf ");
            sb.append(shortFormProvider.getShortForm(chainAx.getSuperProperty().asOWLObjectProperty()));
            return sb.toString();
        }

        // Default case - use basic rendering
        return axiom.toString().replaceAll("([A-Za-z0-9]+:)", "");
    }

    /**
     * Utility method to clear the cache if needed for memory management
     */
    public void clearCache() {
        explanationCache.clear();
        allExplanationPaths.clear();
        LOGGER.info("Explanation cache cleared");
    }

    @Override
    public List<ExplanationPath> explainInference(OWLAxiom targetAxiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        
        // Check if directly asserted
        if (ontology.containsAxiom(targetAxiom)) {
            List<OWLAxiom> axiomList = new ArrayList<>();
            axiomList.add(targetAxiom);
            paths.add(new ExplanationPath(axiomList, "Directly asserted", ExplanationType.DIRECT_ASSERTION, 1));
            return paths;
        }
        
        // Check if entailed
        if (!reasoner.isEntailed(targetAxiom)) {
            return paths; // Empty list - no explanations
        }
        
        // Use existing explanation methods to generate paths
        StringBuilder sb = new StringBuilder();
        
        if (targetAxiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom propAx = (OWLObjectPropertyAssertionAxiom) targetAxiom;
            explainPropertyRelationship(
                propAx.getSubject().asOWLNamedIndividual(),
                propAx.getProperty(),
                propAx.getObject().asOWLNamedIndividual(),
                sb
            );
        } else if (targetAxiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom classAx = (OWLClassAssertionAxiom) targetAxiom;
            explainTypeInference(
                classAx.getIndividual().asOWLNamedIndividual(),
                classAx.getClassExpression().asOWLClass(),
                sb
            );
        } else if (targetAxiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) targetAxiom;
            explainClassRelationship(
                subClassAx.getSubClass().asOWLClass(),
                subClassAx.getSuperClass().asOWLClass(),
                sb
            );
        }
        
        // Parse the generated explanation string into paths
        // This is a simplified approach - in practice, you'd want to modify the existing methods
        // to return ExplanationPath objects directly
        String explanation = sb.toString();
        if (!explanation.isEmpty()) {
            // Create a single path for now
            List<OWLAxiom> axiomList = new ArrayList<>();
            // Parse axioms from the explanation string
            // This is simplified - you'd want proper parsing
            paths.add(new ExplanationPath(axiomList, "Generated explanation", ExplanationType.PELLET_NATIVE, axiomList.size()));
        }
        
        return paths;
    }

    @Override
    public boolean validateExplanation(OWLAxiom targetAxiom, List<ExplanationPath> explanations) {
        // Basic validation: check if explanations are logically sound
        for (ExplanationPath path : explanations) {
            if (path.getAxioms().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}