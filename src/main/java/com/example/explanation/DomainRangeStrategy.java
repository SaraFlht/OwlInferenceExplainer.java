package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.List;

public class DomainRangeStrategy extends BaseExplanationStrategy {
    
    @Override
    public boolean canExplain(OWLAxiom axiom) {
        return axiom instanceof OWLClassAssertionAxiom;
    }
    
    @Override
    public List<ExplanationPath> explain(OWLAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        
        if (!(axiom instanceof OWLClassAssertionAxiom)) {
            return paths;
        }
        
        OWLClassAssertionAxiom classAssertion = (OWLClassAssertionAxiom) axiom;
        OWLNamedIndividual individual = classAssertion.getIndividual().asOWLNamedIndividual();
        OWLClass targetClass = classAssertion.getClassExpression().asOWLClass();
        
        // Check domain axioms
        for (OWLObjectPropertyDomainAxiom domainAx : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_DOMAIN)) {
            OWLClass domainClass = domainAx.getDomain().asOWLClass();
            if (domainClass.equals(targetClass)) {
                OWLObjectProperty property = domainAx.getProperty().asOWLObjectProperty();
                
                // Find all property assertions where this individual is the subject
                for (OWLNamedIndividual object : ontology.getIndividualsInSignature()) {
                    OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, object);
                    if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                        List<OWLAxiom> path = new ArrayList<>();
                        path.add(domainAx);
                        path.add(propAssertion);
                        
                        String description = "Domain inference: " + 
                                          shortFormProvider.getShortForm(property) + 
                                          " has domain " + 
                                          shortFormProvider.getShortForm(targetClass);
                        
                        paths.add(createPath(path, description, ExplanationType.DOMAIN_RANGE));
                    }
                }
            }
        }
        
        // Check range axioms
        for (OWLObjectPropertyRangeAxiom rangeAx : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_RANGE)) {
            OWLClass rangeClass = rangeAx.getRange().asOWLClass();
            if (rangeClass.equals(targetClass)) {
                OWLObjectProperty property = rangeAx.getProperty().asOWLObjectProperty();
                
                // Find all property assertions where this individual is the object
                for (OWLNamedIndividual subject : ontology.getIndividualsInSignature()) {
                    OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, individual);
                    if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                        List<OWLAxiom> path = new ArrayList<>();
                        path.add(rangeAx);
                        path.add(propAssertion);
                        
                        String description = "Range inference: " + 
                                          shortFormProvider.getShortForm(property) + 
                                          " has range " + 
                                          shortFormProvider.getShortForm(targetClass);
                        
                        paths.add(createPath(path, description, ExplanationType.DOMAIN_RANGE));
                    }
                }
            }
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 45;
    }
} 