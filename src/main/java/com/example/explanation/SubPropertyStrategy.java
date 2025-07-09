package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SubPropertyStrategy extends BaseExplanationStrategy {
    
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
        
        // Get all subproperties of the target property
        Set<OWLObjectPropertyExpression> subProperties = new HashSet<>();
        reasoner.getSubObjectProperties(targetProperty, false).entities()
                .forEach(p -> subProperties.add(p));
        
        // For each subproperty, check if there is an assertion between subject and object
        for (OWLObjectPropertyExpression subProp : subProperties) {
            if (subProp.isAnonymous()) continue;
            
            OWLObjectProperty subProperty = subProp.asOWLObjectProperty();
            OWLAxiom subPropertyAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(subProperty, subject, object);
            
            if (ontology.containsAxiom(subPropertyAssertion) || reasoner.isEntailed(subPropertyAssertion)) {
                // Find the subPropertyOf axiom
                OWLAxiom subPropertyAxiom = dataFactory.getOWLSubObjectPropertyOfAxiom(subProperty, targetProperty.asOWLObjectProperty());
                
                List<OWLAxiom> path = new ArrayList<>();
                path.add(subPropertyAxiom);
                path.add(subPropertyAssertion);
                
                String description = "Subproperty inference: " + 
                                  shortFormProvider.getShortForm(subProperty) + 
                                  " SubPropertyOf " + 
                                  shortFormProvider.getShortForm(targetProperty.asOWLObjectProperty());
                
                paths.add(createPath(path, description, ExplanationType.SUBPROPERTY));
            }
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 10;
    }
} 