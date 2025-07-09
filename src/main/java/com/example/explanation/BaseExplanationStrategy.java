package com.example.explanation;

import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseExplanationStrategy implements ExplanationStrategy {
    
    protected static final Logger LOGGER = LoggerFactory.getLogger(BaseExplanationStrategy.class);
    protected OpenlletReasoner reasoner;
    protected OWLOntology ontology;
    protected OWLDataFactory dataFactory;
    protected ShortFormProvider shortFormProvider;
    
    public void initialize(OpenlletReasoner reasoner) {
        this.reasoner = reasoner;
        this.ontology = reasoner.getRootOntology();
        this.dataFactory = ontology.getOWLOntologyManager().getOWLDataFactory();
        this.shortFormProvider = new SimpleShortFormProvider();
    }
    
    protected ExplanationPath createPath(List<OWLAxiom> axioms, String description, ExplanationType type) {
        int complexity = calculateComplexity(axioms);
        return new ExplanationPath(axioms, description, type, complexity);
    }
    
    protected int calculateComplexity(List<OWLAxiom> axioms) {
        // Simple complexity calculation based on number of axioms
        return axioms.size();
    }
    
    protected String renderAxiom(OWLAxiom axiom) {
        if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
            OWLSubObjectPropertyOfAxiom subPropAx = (OWLSubObjectPropertyOfAxiom) axiom;
            return shortFormProvider.getShortForm(subPropAx.getSubProperty().asOWLObjectProperty()) +
                   " SubPropertyOf " +
                   shortFormProvider.getShortForm(subPropAx.getSuperProperty().asOWLObjectProperty());
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            OWLInverseObjectPropertiesAxiom invAx = (OWLInverseObjectPropertiesAxiom) axiom;
            return shortFormProvider.getShortForm(invAx.getFirstProperty().asOWLObjectProperty()) +
                   " InverseOf " +
                   shortFormProvider.getShortForm(invAx.getSecondProperty().asOWLObjectProperty());
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom propAx = (OWLObjectPropertyAssertionAxiom) axiom;
            return shortFormProvider.getShortForm(propAx.getSubject().asOWLNamedIndividual()) +
                   " " +
                   shortFormProvider.getShortForm(propAx.getProperty().asOWLObjectProperty()) +
                   " " +
                   shortFormProvider.getShortForm(propAx.getObject().asOWLNamedIndividual());
        }
        return axiom.toString();
    }
    
    protected List<String> renderAxioms(List<OWLAxiom> axioms) {
        List<String> rendered = new ArrayList<>();
        for (OWLAxiom axiom : axioms) {
            rendered.add(renderAxiom(axiom));
        }
        return rendered;
    }
} 