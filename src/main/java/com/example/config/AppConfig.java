package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.ontology.DefaultOntologyService;
import com.example.ontology.OntologyService;
import com.example.reasoning.PelletReasoningService;
import com.example.reasoning.ReasoningService;
import com.example.explanation.ExplanationService;
import com.example.explanation.PelletExplanationService;
import com.example.query.QueryGenerationService;
import com.example.query.SparqlQueryGenerationService;
import com.example.tracking.GlobalQueryTracker; // ADD THIS IMPORT

@Configuration
public class AppConfig {

    @Bean
    public OntologyService ontologyService() {
        return new DefaultOntologyService();
    }

    @Bean
    public ReasoningService reasoningService() {
        return new PelletReasoningService();
    }

    @Bean
    public ExplanationService explanationService() {
        return new PelletExplanationService();
    }

    @Bean
    public QueryGenerationService queryService(OntologyService ontologyService) { // ADD PARAMETER
        SparqlQueryGenerationService service = new SparqlQueryGenerationService();
        service.setOntologyService(ontologyService); // INJECT ONTOLOGY SERVICE
        return service;
    }

    // ADD THIS BEAN
    @Bean
    public GlobalQueryTracker globalQueryTracker() {
        return new GlobalQueryTracker();
    }
}