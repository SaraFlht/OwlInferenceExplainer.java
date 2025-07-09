package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.List;

public class PropertyChainStrategy extends BaseExplanationStrategy {
    
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
        
        // Get all property chain axioms from the ontology
        for (OWLAxiom ax : ontology.getAxioms()) {
            if (ax instanceof OWLSubPropertyChainOfAxiom) {
                OWLSubPropertyChainOfAxiom chainAxiom = (OWLSubPropertyChainOfAxiom) ax;
                if (chainAxiom.getSuperProperty().equals(targetProperty)) {
                    List<OWLObjectPropertyExpression> chain = chainAxiom.getPropertyChain();
                    
                    // Try to find a path through the chain
                    List<OWLAxiom> chainPath = findChainPath(subject, object, chainAxiom, chain, 0, new ArrayList<>());
                    if (!chainPath.isEmpty()) {
                        String description = "Property chain inference: " + 
                                          shortFormProvider.getShortForm(targetProperty.asOWLObjectProperty()) + 
                                          " via chain of " + chain.size() + " properties";
                        
                        paths.add(createPath(chainPath, description, ExplanationType.PROPERTY_CHAIN));
                    }
                }
            }
        }
        
        return paths;
    }
    
    private List<OWLAxiom> findChainPath(OWLNamedIndividual startIndividual,
                                        OWLNamedIndividual endIndividual,
                                        OWLSubPropertyChainOfAxiom chainAxiom,
                                        List<OWLObjectPropertyExpression> chain,
                                        int chainIndex,
                                        List<OWLAxiom> currentPath) {
        
        // Base case: we've reached the end of the chain
        if (chainIndex >= chain.size()) {
            if (startIndividual.equals(endIndividual)) {
                // Found a complete path! Add the chain axiom at the beginning
                List<OWLAxiom> completePath = new ArrayList<>();
                completePath.add(chainAxiom);
                completePath.addAll(currentPath);
                return completePath;
            }
            return new ArrayList<>();
        }
        
        // Get the current property in the chain
        OWLObjectPropertyExpression currentProp = chain.get(chainIndex);
        
        // Find all objects connected to startIndividual via currentProp
        for (OWLNamedIndividual nextIndividual : ontology.getIndividualsInSignature()) {
            OWLAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(
                    currentProp.asOWLObjectProperty(), startIndividual, nextIndividual);
            
            if (ontology.containsAxiom(propAssertion) || reasoner.isEntailed(propAssertion)) {
                List<OWLAxiom> newPath = new ArrayList<>(currentPath);
                newPath.add(propAssertion);
                
                // Recursively find the rest of the chain
                List<OWLAxiom> result = findChainPath(nextIndividual, endIndividual, chainAxiom, chain, chainIndex + 1, newPath);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        
        return new ArrayList<>();
    }
    
    @Override
    public int getPriority() {
        return 30;
    }
} 