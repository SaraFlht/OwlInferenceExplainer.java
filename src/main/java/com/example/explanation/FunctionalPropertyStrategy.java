package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.List;

public class FunctionalPropertyStrategy extends BaseExplanationStrategy {
    
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
        
        // Check functional property axioms
        for (OWLFunctionalObjectPropertyAxiom funcAx : ontology.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY)) {
            OWLObjectProperty property = funcAx.getProperty().asOWLObjectProperty();
            
            // Find all property assertions where this individual is the subject
            for (OWLNamedIndividual object : ontology.getIndividualsInSignature()) {
                OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, object);
                if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                    // Check if the object is of the required class
                    OWLAxiom objClassAssertion = dataFactory.getOWLClassAssertionAxiom(targetClass, object);
                    if (ontology.containsAxiom(objClassAssertion) || reasoner.isEntailed(objClassAssertion)) {
                        List<OWLAxiom> path = new ArrayList<>();
                        path.add(funcAx);
                        path.add(propAssertion);
                        path.add(objClassAssertion);
                        
                        String description = "Functional property inference: " + 
                                          shortFormProvider.getShortForm(property) + 
                                          " is functional and " + 
                                          shortFormProvider.getShortForm(object) + 
                                          " is of type " + 
                                          shortFormProvider.getShortForm(targetClass);
                        
                        paths.add(createPath(path, description, ExplanationType.FUNCTIONAL_PROPERTY));
                    }
                }
            }
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 50;
    }
} 