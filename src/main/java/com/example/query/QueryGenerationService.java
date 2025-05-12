package com.example.query;

import com.example.output.OutputService;

public interface QueryGenerationService {
    void initialize(org.semanticweb.owlapi.model.OWLOntology ontology,
                    openllet.owlapi.OpenlletReasoner reasoner,
                    com.example.explanation.ExplanationService explanationService,
                    org.semanticweb.owlapi.model.OWLDataFactory dataFactory);
    void generatePropertyAssertionQueries(OutputService outputService);
    void generateMembershipQueries(OutputService outputService);
    void generateSubsumptionQueries(OutputService outputService);
}
