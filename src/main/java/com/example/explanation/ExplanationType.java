package com.example.explanation;

public enum ExplanationType {
    DIRECT_ASSERTION("Direct Assertion"),
    SUBPROPERTY("Subproperty Inference"),
    INVERSE_PROPERTY("Inverse Property"),
    PROPERTY_CHAIN("Property Chain"),
    CLASS_HIERARCHY("Class Hierarchy"),
    EQUIVALENT_CLASS("Equivalent Class"),
    EQUIVALENT_PROPERTY("Equivalent Property"),
    DOMAIN_RANGE("Domain/Range"),
    FUNCTIONAL_PROPERTY("Functional Property"),
    PELLET_NATIVE("Pellet Native"),
    COMPLEX_COMBINED("Complex Combined");
    
    private final String displayName;
    
    ExplanationType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
} 