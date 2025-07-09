package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.List;

public class ClassHierarchyStrategy extends BaseExplanationStrategy {
    
    @Override
    public boolean canExplain(OWLAxiom axiom) {
        return axiom instanceof OWLClassAssertionAxiom || axiom instanceof OWLSubClassOfAxiom;
    }
    
    @Override
    public List<ExplanationPath> explain(OWLAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        
        if (axiom instanceof OWLClassAssertionAxiom) {
            paths.addAll(explainClassAssertion((OWLClassAssertionAxiom) axiom));
        } else if (axiom instanceof OWLSubClassOfAxiom) {
            paths.addAll(explainSubClassOf((OWLSubClassOfAxiom) axiom));
        }
        
        return paths;
    }
    
    private List<ExplanationPath> explainClassAssertion(OWLClassAssertionAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        OWLNamedIndividual individual = axiom.getIndividual().asOWLNamedIndividual();
        OWLClass targetClass = axiom.getClassExpression().asOWLClass();
        
        // Check all subclass axioms where the superclass is our target class
        for (OWLAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) ax;
            if (subClassAx.getSuperClass().equals(targetClass)) {
                OWLClass subClass = subClassAx.getSubClass().asOWLClass();
                
                // Check if the individual is an instance of the subclass
                OWLAxiom subClassAssertion = dataFactory.getOWLClassAssertionAxiom(subClass, individual);
                if (ontology.containsAxiom(subClassAssertion) || reasoner.isEntailed(subClassAssertion)) {
                    List<OWLAxiom> path = new ArrayList<>();
                    path.add(subClassAx);
                    path.add(subClassAssertion);
                    
                    String description = "Class hierarchy inference: " + 
                                      shortFormProvider.getShortForm(subClass) + 
                                      " SubClassOf " + 
                                      shortFormProvider.getShortForm(targetClass);
                    
                    paths.add(createPath(path, description, ExplanationType.CLASS_HIERARCHY));
                }
            }
        }
        
        return paths;
    }
    
    private List<ExplanationPath> explainSubClassOf(OWLSubClassOfAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        OWLClass subClass = axiom.getSubClass().asOWLClass();
        OWLClass superClass = axiom.getSuperClass().asOWLClass();
        
        // Check for intermediate classes
        for (OWLAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            OWLSubClassOfAxiom intermediateAx = (OWLSubClassOfAxiom) ax;
            if (intermediateAx.getSubClass().equals(subClass) && 
                !intermediateAx.getSuperClass().equals(superClass)) {
                
                // Check if the intermediate class is a subclass of the target superclass
                OWLClass intermediateClass = intermediateAx.getSuperClass().asOWLClass();
                OWLAxiom intermediateSubClassAx = dataFactory.getOWLSubClassOfAxiom(intermediateClass, superClass);
                
                if (ontology.containsAxiom(intermediateSubClassAx) || reasoner.isEntailed(intermediateSubClassAx)) {
                    List<OWLAxiom> path = new ArrayList<>();
                    path.add(intermediateAx);
                    path.add(intermediateSubClassAx);
                    
                    String description = "Class hierarchy inference via intermediate class: " + 
                                      shortFormProvider.getShortForm(subClass) + 
                                      " SubClassOf " + 
                                      shortFormProvider.getShortForm(intermediateClass) + 
                                      " SubClassOf " + 
                                      shortFormProvider.getShortForm(superClass);
                    
                    paths.add(createPath(path, description, ExplanationType.CLASS_HIERARCHY));
                }
            }
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 40;
    }
} 