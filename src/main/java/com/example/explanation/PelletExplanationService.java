package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import openllet.owlapi.explanation.PelletExplanation;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.InferenceType;
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
    // Thread-local variable to prevent infinite recursion
    private final ThreadLocal<Set<String>> currentExplanations = ThreadLocal.withInitial(HashSet::new);

    @Override
    public void initializeExplanations(OpenlletReasoner reasoner) {
        this.reasoner = reasoner;
        this.ontology = reasoner.getRootOntology();
        this.dataFactory = ontology.getOWLOntologyManager().getOWLDataFactory();
        this.shortFormProvider = new SimpleShortFormProvider();
        LOGGER.info("Initializing Pellet explanation service");
        PelletExplanation.setup();
        this.explanation = new PelletExplanation(reasoner);
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
            boolean any = false;
            OWLObjectProperty p = property.asOWLObjectProperty();
            OWLAxiom ax = dataFactory.getOWLObjectPropertyAssertionAxiom(p, subject, object);

            // Check if directly asserted
            if (ontology.containsAxiom(ax)) {
                sb.append("Directly asserted\n");
                explanationCache.put(cacheKey, "Directly asserted\n");
                return;
            }

            // Try to get Pellet's explanation first
            try {
                Set<OWLAxiom> pel = explanation.getEntailmentExplanation(ax);
                if (!pel.isEmpty()) {
                    sb.append("Explanations from Pellet:\n");
                    pel.forEach(a -> sb.append("  - ").append(renderAxiomWithShortNames(a)).append("\n"));
                    sb.append("\n");
                    any = true;
                }
            } catch (Exception e) {
                LOGGER.debug("Error getting explanation for property relationship", e);
            }

            // Check for inverse properties
            if (!any) {
                for (OWLInverseObjectPropertiesAxiom inv : ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
                    OWLObjectPropertyExpression p1 = inv.getFirstProperty(), p2 = inv.getSecondProperty();
                    if (p1.equals(property) || p2.equals(property)) {
                        OWLObjectPropertyExpression ip = p1.equals(property) ? p2 : p1;
                        OWLAxiom ia = dataFactory.getOWLObjectPropertyAssertionAxiom(ip.asOWLObjectProperty(), object, subject);

                        if (ontology.containsAxiom(ia) || reasoner.isEntailed(ia)) {
                            sb.append("Inverse-of:\n")
                                    .append("  - ").append(renderProp(property))
                                    .append(" InverseOf ").append(renderProp(ip))
                                    .append("\n");

                            sb.append("  - ").append(shortFormProvider.getShortForm(object))
                                    .append(" ").append(renderProp(ip))
                                    .append(" ").append(shortFormProvider.getShortForm(subject))
                                    .append("\n\n");

                            any = true;
                        }
                    }
                }
            }

            // Check for sub-properties
            if (!any) {
                walkSubProperties(subject, property, object, sb);
                if (sb.length() > 0) {
                    any = true;
                }
            }

            if (!any) {
                sb.append("No explanation for ")
                        .append(shortFormProvider.getShortForm(subject)).append(" ")
                        .append(renderProp(property)).append(" ")
                        .append(shortFormProvider.getShortForm(object)).append("\n");
            }

            // Cache this explanation for future use
            explanationCache.put(cacheKey, sb.toString());
        } finally {
            // Always remove from currently explaining set to prevent memory leaks
            currentlyExplaining.remove(cacheKey);
        }
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

        boolean any = false;
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

        // Try Pellet explanation
        try {
            Set<OWLAxiom> pel = explanation.getInstanceExplanation(individual, clazz);
            if (!pel.isEmpty()) {
                sb.append("Explanations from Pellet:\n");
                pel.forEach(a -> sb.append("  - ").append(renderAxiomWithShortNames(a)).append("\n"));
                sb.append("\n");
                any = true;
            } else {
                sb.append("\n");
            }
        } catch (Exception e) {
            LOGGER.debug("Error getting explanation for type inference", e);
            sb.append("\n");
        }

        // Check for equivalent classes
        for (OWLClass eq : reasoner.getEquivalentClasses(clazz).entities().collect(Collectors.toSet())) {
            if (eq.equals(clazz)) continue;
            if (ontology.containsAxiom(dataFactory.getOWLClassAssertionAxiom(eq, individual))) {
                sb.append("Equivalent class:\n")
                        .append("  - ").append(shortFormProvider.getShortForm(individual))
                        .append(" type ").append(shortFormProvider.getShortForm(eq))
                        .append("\n")
                        .append("  - ").append(shortFormProvider.getShortForm(eq))
                        .append(" EquivalentTo ").append(shortFormProvider.getShortForm(clazz))
                        .append("\n\n");
                any = true;
            }
        }

        // Check for subclasses
        for (OWLClass sub : reasoner.getSubClasses(clazz, false).entities().collect(Collectors.toSet())) {
            if (sub.isOWLNothing()) continue;
            if (ontology.containsAxiom(dataFactory.getOWLClassAssertionAxiom(sub, individual)) ||
                    reasoner.isEntailed(dataFactory.getOWLClassAssertionAxiom(sub, individual))) {
                sb.append("Subclass:\n")
                        .append("  - ").append(shortFormProvider.getShortForm(individual))
                        .append(" type ").append(shortFormProvider.getShortForm(sub))
                        .append("\n")
                        .append("  - ").append(shortFormProvider.getShortForm(sub))
                        .append(" subClassOf ").append(shortFormProvider.getShortForm(clazz))
                        .append("\n\n");
                any = true;
            }
        }

        // Check property domain/range - optimized with early returns
        checkDomainRangeForClass(individual, clazz, sb);

        if (!any) sb.append("No membership explanation found\n");

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

        boolean any = false;
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

        // Try Pellet explanation
        try {
            Set<OWLAxiom> pel = explanation.getSubClassExplanation(subClass, superClass);
            if (!pel.isEmpty()) {
                sb.append("Explanations from Pellet:\n");
                pel.forEach(a -> sb.append("  - ").append(renderAxiomWithShortNames(a)).append("\n"));
                sb.append("\n");
                any = true;
            } else {
                sb.append("\n");
            }
        } catch (Exception e) {
            LOGGER.debug("Error getting explanation for class relationship", e);
            sb.append("\n");
        }

        // Check for equivalent classes (limiting to a reasonable number)
        int count = 0;
        for (OWLClass eqSub : reasoner.getEquivalentClasses(subClass).entities()
                .limit(5) // Limit to 5 equivalent classes
                .collect(Collectors.toSet())) {
            if (eqSub.equals(subClass)) continue;
            if (reasoner.isEntailed(dataFactory.getOWLSubClassOfAxiom(eqSub, superClass))) {
                sb.append("Equivalent subclass:\n")
                        .append("  - ").append(shortFormProvider.getShortForm(subClass))
                        .append(" EquivalentTo ").append(shortFormProvider.getShortForm(eqSub))
                        .append("\n")
                        .append("  - ").append(shortFormProvider.getShortForm(eqSub))
                        .append(" subClassOf ").append(shortFormProvider.getShortForm(superClass))
                        .append("\n\n");
                any = true;
                if (++count >= 3) break; // Limit to 3 explanations
            }
        }

        // Find intermediate classes using a more efficient approach
        findIntermediateClasses(subClass, superClass, sb);

        if (!any) sb.append("No subsumption explanation found\n");

        // Cache this explanation for future use
        explanationCache.put(cacheKey, sb.toString());
    }

    @Override
    public int getExplanationSize(String explanation) {
        if (explanation == null || explanation.trim().isEmpty()) {
            return 0;
        }

        // Count only the lines that start with "  - " which indicate actual axioms
        return (int) Arrays.stream(explanation.split("\n"))
                .filter(line -> line.trim().startsWith("-"))
                .count();
    }

    // Strips common prefixes like "rdfs:" from an axiom string
    private String render(OWLAxiom a) {
        String axiomStr = a.toString();
        return axiomStr.replaceAll("([A-Za-z0-9]+:)", "");
    }

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

    private void walkSubProperties(OWLNamedIndividual subject,
                                   OWLObjectPropertyExpression property,
                                   OWLNamedIndividual object,
                                   StringBuilder sb) {
        var subs = reasoner.getSubObjectProperties(property, true);
        for (var subProp : subs.entities().collect(Collectors.toSet())) {
            OWLObjectProperty sub = subProp.asOWLObjectProperty();
            var ax = dataFactory.getOWLObjectPropertyAssertionAxiom(sub, subject, object);
            if (reasoner.isEntailed(ax)) {
                sb.append("Sub-property:\n")
                        .append("  - ").append(renderProp(sub))
                        .append(" SubPropertyOf ").append(renderProp(property))
                        .append("\n");

                // Check if this sub-property assertion is directly asserted
                if (ontology.containsAxiom(ax)) {
                    sb.append("  - ").append(shortFormProvider.getShortForm(subject))
                            .append(" ").append(renderProp(sub))
                            .append(" ").append(shortFormProvider.getShortForm(object))
                            .append("\n");
                }

                sb.append("\n");
            }
        }
    }

    private void checkDomainRangeForClass(OWLNamedIndividual individual,
                                          OWLClass clazz,
                                          StringBuilder sb) {
        boolean found = false;
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            // domain check
            if (reasoner.getObjectPropertyDomains(prop, false).containsEntity(clazz)) {
                for (OWLNamedIndividual obj : ontology.getIndividualsInSignature()) {
                    var ax = dataFactory.getOWLObjectPropertyAssertionAxiom(prop, individual, obj);
                    if (reasoner.isEntailed(ax)) {
                        sb.append("Property domain:\n")
                                .append("  - ").append(shortFormProvider.getShortForm(individual))
                                .append(" ").append(shortFormProvider.getShortForm(prop))
                                .append(" ").append(shortFormProvider.getShortForm(obj))
                                .append("\n")
                                .append("  - Domain(").append(shortFormProvider.getShortForm(prop))
                                .append(") = ").append(shortFormProvider.getShortForm(clazz))
                                .append("\n\n");
                        found = true;
                        break;
                    }
                }
            }
            if (found) break;
            // range check
            if (reasoner.getObjectPropertyRanges(prop, false).containsEntity(clazz)) {
                for (OWLNamedIndividual subj : ontology.getIndividualsInSignature()) {
                    var ax = dataFactory.getOWLObjectPropertyAssertionAxiom(prop, subj, individual);
                    if (reasoner.isEntailed(ax)) {
                        sb.append("Property range:\n")
                                .append("  - ").append(shortFormProvider.getShortForm(subj))
                                .append(" ").append(shortFormProvider.getShortForm(prop))
                                .append(" ").append(shortFormProvider.getShortForm(individual))
                                .append("\n")
                                .append("  - Range(").append(shortFormProvider.getShortForm(prop))
                                .append(") = ").append(shortFormProvider.getShortForm(clazz))
                                .append("\n\n");
                        found = true;
                        break;
                    }
                }
            }
            if (found) break;
        }
    }

    private void findIntermediateClasses(OWLClass subClass,
                                         OWLClass superClass,
                                         StringBuilder sb) {
        var directSupers = reasoner.getSuperClasses(subClass, true)
                .entities()
                .filter(c -> !c.equals(superClass) && !c.isOWLThing())
                .limit(5)
                .collect(Collectors.toSet());
        for (OWLClass mid : directSupers) {
            var ax = dataFactory.getOWLSubClassOfAxiom(mid, superClass);
            if (reasoner.isEntailed(ax)) {
                sb.append("Intermediate class:\n")
                        .append("  - ").append(shortFormProvider.getShortForm(subClass))
                        .append(" subClassOf ").append(shortFormProvider.getShortForm(mid))
                        .append("\n")
                        .append("  - ").append(shortFormProvider.getShortForm(mid))
                        .append(" subClassOf ").append(shortFormProvider.getShortForm(superClass))
                        .append("\n\n");
                return;
            }
        }
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
        }

        // Default to basic rendering for other axiom types
        return render(axiom);
    }
}