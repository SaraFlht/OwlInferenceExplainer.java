package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.List;

public class EquivalentPropertyStrategy extends BaseExplanationStrategy {
    
    @Override
    public boolean canExplain(OWLAxiom axiom) {
        return axiom instanceof OWLObjectPropertyAssertionAxiom;
    }
    
    @Override
    public List<ExplanationPath> explain(OWLAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        
        if (!(axiom instanceof OWLObjectPropertyAssertionAxiom)) {
            return paths;
        }
        
        OWLObjectPropertyAssertionAxiom propAssertion = (OWLObjectPropertyAssertionAxiom) axiom;
        OWLObjectPropertyExpression targetProperty = propAssertion.getProperty();
        OWLNamedIndividual subject = propAssertion.getSubject().asOWLNamedIndividual();
        OWLNamedIndividual object = propAssertion.getObject().asOWLNamedIndividual();
        
        // Check all equivalent property axioms
        for (OWLEquivalentObjectPropertiesAxiom equiv : ontology.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES)) {
            if (equiv.getProperties().contains(targetProperty)) {
                for (OWLObjectPropertyExpression equivProp : equiv.getProperties()) {
                    if (!equivProp.equals(targetProperty)) {
                        OWLAxiom equivAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(
                                equivProp.asOWLObjectProperty(), subject, object);
                        
                        if (ontology.containsAxiom(equivAssertion) || reasoner.isEntailed(equivAssertion)) {
                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(equiv);
                            path.add(equivAssertion);
                            
                            String description = "Equivalent property inference: " + 
                                              shortFormProvider.getShortForm(equivProp.asOWLObjectProperty()) + 
                                              " EquivalentTo " + 
                                              shortFormProvider.getShortForm(targetProperty.asOWLObjectProperty());
                            
                            paths.add(createPath(path, description, ExplanationType.EQUIVALENT_PROPERTY));
                        }
                    }
                }
            }
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 25;
    }
} 