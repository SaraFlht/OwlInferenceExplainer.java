package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Comprehensive test demonstrating all explanation strategies
 */
public class ComprehensiveExplanationTest {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ComprehensiveExplanationTest.class);
    
    public static void testAllStrategies(OpenlletReasoner reasoner) {
        ComprehensiveExplanationService service = ExplanationServiceFactory.createAndInitializeService(reasoner);
        
        LOGGER.info("=== Testing All Explanation Strategies ===");
        
        // Test 1: Direct Assertion
        testDirectAssertion(service, reasoner);
        
        // Test 2: SubProperty Inference
        testSubPropertyInference(service, reasoner);
        
        // Test 3: Inverse Property Inference
        testInversePropertyInference(service, reasoner);
        
        // Test 4: Property Chain Inference
        testPropertyChainInference(service, reasoner);
        
        // Test 5: Equivalent Property Inference
        testEquivalentPropertyInference(service, reasoner);
        
        // Test 6: Class Hierarchy Inference
        testClassHierarchyInference(service, reasoner);
        
        // Test 7: Equivalent Class Inference
        testEquivalentClassInference(service, reasoner);
        
        // Test 8: Domain/Range Inference
        testDomainRangeInference(service, reasoner);
        
        // Test 9: Functional Property Inference
        testFunctionalPropertyInference(service, reasoner);
        
        LOGGER.info("=== All Strategy Tests Complete ===");
    }
    
    private static void testDirectAssertion(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Direct Assertion Strategy...");
        
        // Find a directly asserted property assertion
        for (OWLAxiom axiom : reasoner.getRootOntology().getAxioms()) {
            if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
                OWLObjectPropertyAssertionAxiom propAx = (OWLObjectPropertyAssertionAxiom) axiom;
                List<ExplanationPath> explanations = service.explainInference(propAx);
                
                for (ExplanationPath path : explanations) {
                    if (path.getType() == ExplanationType.DIRECT_ASSERTION) {
                        LOGGER.info("Found direct assertion: {}", path.getDescription());
                        return;
                    }
                }
            }
        }
        
        LOGGER.info("No direct assertions found");
    }
    
    private static void testSubPropertyInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing SubProperty Strategy...");
        
        // Test isSiblingOf inference (should be explained via isBrotherOf or isSisterOf)
        for (OWLObjectProperty prop : reasoner.getRootOntology().getObjectPropertiesInSignature()) {
            if (prop.getIRI().getFragment().equals("isSiblingOf")) {
                // Find individuals that are siblings
                for (OWLNamedIndividual ind1 : reasoner.getRootOntology().getIndividualsInSignature()) {
                    for (OWLNamedIndividual ind2 : reasoner.getRootOntology().getIndividualsInSignature()) {
                        if (!ind1.equals(ind2)) {
                            OWLAxiom siblingAx = reasoner.getRootOntology().getOWLOntologyManager()
                                    .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(prop, ind1, ind2);
                            
                            if (reasoner.isEntailed(siblingAx)) {
                                List<ExplanationPath> explanations = service.explainInference(siblingAx);
                                
                                for (ExplanationPath path : explanations) {
                                    if (path.getType() == ExplanationType.SUBPROPERTY) {
                                        LOGGER.info("Found subproperty inference: {}", path.getDescription());
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No subproperty inferences found");
    }
    
    private static void testInversePropertyInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Inverse Property Strategy...");
        
        // Test inverse property relationships like hasChild/isChildOf
        for (OWLObjectProperty prop : reasoner.getRootOntology().getObjectPropertiesInSignature()) {
            if (prop.getIRI().getFragment().equals("hasChild")) {
                // Find parent-child relationships
                for (OWLNamedIndividual parent : reasoner.getRootOntology().getIndividualsInSignature()) {
                    for (OWLNamedIndividual child : reasoner.getRootOntology().getIndividualsInSignature()) {
                        if (!parent.equals(child)) {
                            OWLAxiom hasChildAx = reasoner.getRootOntology().getOWLOntologyManager()
                                    .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(prop, parent, child);
                            
                            if (reasoner.isEntailed(hasChildAx)) {
                                List<ExplanationPath> explanations = service.explainInference(hasChildAx);
                                
                                for (ExplanationPath path : explanations) {
                                    if (path.getType() == ExplanationType.INVERSE_PROPERTY) {
                                        LOGGER.info("Found inverse property inference: {}", path.getDescription());
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No inverse property inferences found");
    }
    
    private static void testPropertyChainInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Property Chain Strategy...");
        
        // Test property chains like isUncleOf (isBrotherOf o isParentOf)
        for (OWLObjectProperty prop : reasoner.getRootOntology().getObjectPropertiesInSignature()) {
            if (prop.getIRI().getFragment().equals("isUncleOf")) {
                // Find uncle relationships
                for (OWLNamedIndividual uncle : reasoner.getRootOntology().getIndividualsInSignature()) {
                    for (OWLNamedIndividual niece : reasoner.getRootOntology().getIndividualsInSignature()) {
                        if (!uncle.equals(niece)) {
                            OWLAxiom uncleAx = reasoner.getRootOntology().getOWLOntologyManager()
                                    .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(prop, uncle, niece);
                            
                            if (reasoner.isEntailed(uncleAx)) {
                                List<ExplanationPath> explanations = service.explainInference(uncleAx);
                                
                                for (ExplanationPath path : explanations) {
                                    if (path.getType() == ExplanationType.PROPERTY_CHAIN) {
                                        LOGGER.info("Found property chain inference: {}", path.getDescription());
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No property chain inferences found");
    }
    
    private static void testEquivalentPropertyInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Equivalent Property Strategy...");
        
        // Look for equivalent properties in the ontology
        for (OWLEquivalentObjectPropertiesAxiom equiv : reasoner.getRootOntology().getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES)) {
            for (OWLObjectPropertyExpression prop : equiv.getProperties()) {
                if (prop.isOWLObjectProperty()) {
                    OWLObjectProperty objectProp = prop.asOWLObjectProperty();
                    
                    // Find individuals connected by this property
                    for (OWLNamedIndividual ind1 : reasoner.getRootOntology().getIndividualsInSignature()) {
                        for (OWLNamedIndividual ind2 : reasoner.getRootOntology().getIndividualsInSignature()) {
                            if (!ind1.equals(ind2)) {
                                OWLAxiom propAx = reasoner.getRootOntology().getOWLOntologyManager()
                                        .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(objectProp, ind1, ind2);
                                
                                if (reasoner.isEntailed(propAx)) {
                                    List<ExplanationPath> explanations = service.explainInference(propAx);
                                    
                                    for (ExplanationPath path : explanations) {
                                        if (path.getType() == ExplanationType.EQUIVALENT_PROPERTY) {
                                            LOGGER.info("Found equivalent property inference: {}", path.getDescription());
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No equivalent property inferences found");
    }
    
    private static void testClassHierarchyInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Class Hierarchy Strategy...");
        
        // Test class hierarchy inferences (e.g., Man is a subclass of Person)
        for (OWLClass clazz : reasoner.getRootOntology().getClassesInSignature()) {
            if (clazz.getIRI().getFragment().equals("Man")) {
                // Find individuals that are men
                for (OWLNamedIndividual individual : reasoner.getRootOntology().getIndividualsInSignature()) {
                    OWLAxiom manAx = reasoner.getRootOntology().getOWLOntologyManager()
                            .getOWLDataFactory().getOWLClassAssertionAxiom(clazz, individual);
                    
                    if (reasoner.isEntailed(manAx)) {
                        List<ExplanationPath> explanations = service.explainInference(manAx);
                        
                        for (ExplanationPath path : explanations) {
                            if (path.getType() == ExplanationType.CLASS_HIERARCHY) {
                                LOGGER.info("Found class hierarchy inference: {}", path.getDescription());
                                return;
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No class hierarchy inferences found");
    }
    
    private static void testEquivalentClassInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Equivalent Class Strategy...");
        
        // Look for equivalent classes in the ontology
        for (OWLEquivalentClassesAxiom equiv : reasoner.getRootOntology().getAxioms(AxiomType.EQUIVALENT_CLASSES)) {
            for (OWLClassExpression classExpr : equiv.getClassExpressions()) {
                if (classExpr.isOWLClass()) {
                    OWLClass clazz = classExpr.asOWLClass();
                    
                    // Find individuals of this class
                    for (OWLNamedIndividual individual : reasoner.getRootOntology().getIndividualsInSignature()) {
                        OWLAxiom classAx = reasoner.getRootOntology().getOWLOntologyManager()
                                .getOWLDataFactory().getOWLClassAssertionAxiom(clazz, individual);
                        
                        if (reasoner.isEntailed(classAx)) {
                            List<ExplanationPath> explanations = service.explainInference(classAx);
                            
                            for (ExplanationPath path : explanations) {
                                if (path.getType() == ExplanationType.EQUIVALENT_CLASS) {
                                    LOGGER.info("Found equivalent class inference: {}", path.getDescription());
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No equivalent class inferences found");
    }
    
    private static void testDomainRangeInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Domain/Range Strategy...");
        
        // Test domain/range inferences
        for (OWLObjectPropertyDomainAxiom domainAx : reasoner.getRootOntology().getAxioms(AxiomType.OBJECT_PROPERTY_DOMAIN)) {
            OWLClass domainClass = domainAx.getDomain().asOWLClass();
            OWLObjectProperty property = domainAx.getProperty().asOWLObjectProperty();
            
            // Find individuals in the domain
            for (OWLNamedIndividual individual : reasoner.getRootOntology().getIndividualsInSignature()) {
                OWLAxiom domainAx2 = reasoner.getRootOntology().getOWLOntologyManager()
                        .getOWLDataFactory().getOWLClassAssertionAxiom(domainClass, individual);
                
                if (reasoner.isEntailed(domainAx2)) {
                    List<ExplanationPath> explanations = service.explainInference(domainAx2);
                    
                    for (ExplanationPath path : explanations) {
                        if (path.getType() == ExplanationType.DOMAIN_RANGE) {
                            LOGGER.info("Found domain/range inference: {}", path.getDescription());
                            return;
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No domain/range inferences found");
    }
    
    private static void testFunctionalPropertyInference(ComprehensiveExplanationService service, OpenlletReasoner reasoner) {
        LOGGER.info("Testing Functional Property Strategy...");
        
        // Test functional property inferences
        for (OWLFunctionalObjectPropertyAxiom funcAx : reasoner.getRootOntology().getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY)) {
            OWLObjectProperty property = funcAx.getProperty().asOWLObjectProperty();
            
            // Find individuals that have this functional property
            for (OWLNamedIndividual individual : reasoner.getRootOntology().getIndividualsInSignature()) {
                for (OWLNamedIndividual object : reasoner.getRootOntology().getIndividualsInSignature()) {
                    if (!individual.equals(object)) {
                        OWLAxiom propAx = reasoner.getRootOntology().getOWLOntologyManager()
                                .getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(property, individual, object);
                        
                        if (reasoner.isEntailed(propAx)) {
                            // Check if the object has a specific type
                            for (OWLClass clazz : reasoner.getRootOntology().getClassesInSignature()) {
                                OWLAxiom classAx = reasoner.getRootOntology().getOWLOntologyManager()
                                        .getOWLDataFactory().getOWLClassAssertionAxiom(clazz, object);
                                
                                if (reasoner.isEntailed(classAx)) {
                                    List<ExplanationPath> explanations = service.explainInference(classAx);
                                    
                                    for (ExplanationPath path : explanations) {
                                        if (path.getType() == ExplanationType.FUNCTIONAL_PROPERTY) {
                                            LOGGER.info("Found functional property inference: {}", path.getDescription());
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        LOGGER.info("No functional property inferences found");
    }
} 