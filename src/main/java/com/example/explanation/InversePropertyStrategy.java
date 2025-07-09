package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InversePropertyStrategy extends BaseExplanationStrategy {
    
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
        
        // Check all inverse property axioms in the ontology
        for (OWLInverseObjectPropertiesAxiom inv : ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
            OWLObjectPropertyExpression p1 = inv.getFirstProperty(), p2 = inv.getSecondProperty();
            
            if (p1.equals(targetProperty) || p2.equals(targetProperty)) {
                OWLObjectPropertyExpression inverseProp = p1.equals(targetProperty) ? p2 : p1;
                OWLAxiom inverseAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(
                        inverseProp.asOWLObjectProperty(), object, subject);
                
                if (ontology.containsAxiom(inverseAssertion) || reasoner.isEntailed(inverseAssertion)) {
                    List<OWLAxiom> path = new ArrayList<>();
                    path.add(inv);
                    path.add(inverseAssertion);
                    
                    String description = "Inverse property inference: " + 
                                      shortFormProvider.getShortForm(inverseProp.asOWLObjectProperty()) + 
                                      " InverseOf " + 
                                      shortFormProvider.getShortForm(targetProperty.asOWLObjectProperty());
                    
                    paths.add(createPath(path, description, ExplanationType.INVERSE_PROPERTY));
                }
            }
        }
        
        // Also check for inverse property expressions
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            Set<OWLObjectInverseOf> invExpressions = new HashSet<>();
            
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
                if (invOf.getInverse().equals(targetProperty) || prop.equals(targetProperty)) {
                    OWLObjectProperty inverseProp = invOf.getInverse().equals(targetProperty) ?
                            prop : invOf.getInverse().asOWLObjectProperty();
                    
                    OWLAxiom inverseAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(
                            inverseProp, object, subject);
                    
                    if (ontology.containsAxiom(inverseAssertion) || reasoner.isEntailed(inverseAssertion)) {
                        OWLAxiom invAxiom = dataFactory.getOWLInverseObjectPropertiesAxiom(targetProperty, inverseProp);
                        List<OWLAxiom> path = new ArrayList<>();
                        path.add(invAxiom);
                        path.add(inverseAssertion);
                        
                        String description = "Inverse property inference: " + 
                                          shortFormProvider.getShortForm(inverseProp) + 
                                          " InverseOf " + 
                                          shortFormProvider.getShortForm(targetProperty.asOWLObjectProperty());
                        
                        paths.add(createPath(path, description, ExplanationType.INVERSE_PROPERTY));
                    }
                }
            }
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 20;
    }
} 