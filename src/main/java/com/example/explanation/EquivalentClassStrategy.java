package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.List;

public class EquivalentClassStrategy extends BaseExplanationStrategy {
    
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
        
        // Check all equivalent class axioms
        for (OWLEquivalentClassesAxiom equiv : ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES)) {
            if (equiv.getClassExpressions().contains(targetClass)) {
                for (OWLClassExpression equivClass : equiv.getClassExpressions()) {
                    if (!equivClass.equals(targetClass) && equivClass.isOWLClass()) {
                        OWLClass otherClass = equivClass.asOWLClass();
                        OWLAxiom otherClassAssertion = dataFactory.getOWLClassAssertionAxiom(otherClass, individual);
                        
                        if (ontology.containsAxiom(otherClassAssertion) || reasoner.isEntailed(otherClassAssertion)) {
                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(equiv);
                            path.add(otherClassAssertion);
                            
                            String description = "Equivalent class inference: " + 
                                              shortFormProvider.getShortForm(otherClass) + 
                                              " EquivalentTo " + 
                                              shortFormProvider.getShortForm(targetClass);
                            
                            paths.add(createPath(path, description, ExplanationType.EQUIVALENT_CLASS));
                        }
                    }
                }
            }
        }
        
        return paths;
    }
    
    private List<ExplanationPath> explainSubClassOf(OWLSubClassOfAxiom axiom) {
        List<ExplanationPath> paths = new ArrayList<>();
        OWLClass subClass = axiom.getSubClass().asOWLClass();
        OWLClass superClass = axiom.getSuperClass().asOWLClass();
        
        // Check for equivalent classes that could explain the subsumption
        for (OWLEquivalentClassesAxiom equiv : ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES)) {
            if (equiv.getClassExpressions().contains(subClass)) {
                for (OWLClassExpression equivClass : equiv.getClassExpressions()) {
                    if (!equivClass.equals(subClass) && equivClass.isOWLClass()) {
                        OWLClass otherClass = equivClass.asOWLClass();
                        OWLAxiom otherSubClassAx = dataFactory.getOWLSubClassOfAxiom(otherClass, superClass);
                        
                        if (ontology.containsAxiom(otherSubClassAx) || reasoner.isEntailed(otherSubClassAx)) {
                            List<OWLAxiom> path = new ArrayList<>();
                            path.add(equiv);
                            path.add(otherSubClassAx);
                            
                            String description = "Equivalent class inference for subsumption: " + 
                                              shortFormProvider.getShortForm(otherClass) + 
                                              " EquivalentTo " + 
                                              shortFormProvider.getShortForm(subClass) + 
                                              " SubClassOf " + 
                                              shortFormProvider.getShortForm(superClass);
                            
                            paths.add(createPath(path, description, ExplanationType.EQUIVALENT_CLASS));
                        }
                    }
                }
            }
        }
        
        return paths;
    }
    
    @Override
    public int getPriority() {
        return 35;
    }
} 