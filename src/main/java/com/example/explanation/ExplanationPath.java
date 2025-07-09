package com.example.explanation;

import org.semanticweb.owlapi.model.OWLAxiom;
import java.util.List;

public class ExplanationPath {
    private final List<OWLAxiom> axioms;
    private final String description;
    private final ExplanationType type;
    private final int complexity;
    
    public ExplanationPath(List<OWLAxiom> axioms, String description, ExplanationType type, int complexity) {
        this.axioms = axioms;
        this.description = description;
        this.type = type;
        this.complexity = complexity;
    }
    
    public List<OWLAxiom> getAxioms() {
        return axioms;
    }
    
    public String getDescription() {
        return description;
    }
    
    public ExplanationType getType() {
        return type;
    }
    
    public int getComplexity() {
        return complexity;
    }
    
    @Override
    public String toString() {
        return "ExplanationPath{" +
                "type=" + type +
                ", complexity=" + complexity +
                ", description='" + description + '\'' +
                ", axioms=" + axioms +
                '}';
    }
} 