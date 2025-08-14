"""
Test configuration for LLM experiments
"""

# Test mode settings
TEST_MODE = True  # Set to False for full dataset
TEST_SAMPLE_SIZE = 100  # Number of questions to test with

# Model configuration
MODELS_CONFIG = {
    "gpt-4o-mini": "gpt-4o-mini",
    "deepseek-reasoner": "deepseek-reasoner",
    "llama-4-maverick": "meta-llama/llama-4-maverick"
}

# DeepEval settings
DEEPEVAL_CONFIG = {
    'enabled': True,
    'models_to_evaluate': list(MODELS_CONFIG.keys()),  # Evaluate all 3 models
    'sample_size_per_model': 50,  # Sample size for DeepEval per model
    'metrics': ['answer_relevancy', 'faithfulness', 'hallucination', 'bias', 'toxicity']
}

# Experiment types
EXPERIMENT_TYPES = {
    'sparql_ttl': 'SPARQL queries with TTL ontologies',
    'nl_verbalized': 'Natural language questions with JSON ontologies',
    'nl_with_explanations': 'Natural language questions with explanation context'
}

def get_sample_data(df, test_mode=TEST_MODE, sample_size=TEST_SAMPLE_SIZE):
    """Get sample data for testing or full data for production"""
    if test_mode and len(df) > sample_size:
        print(f"TEST MODE: Using first {sample_size} questions out of {len(df)}")
        return df.head(sample_size).copy()
    else:
        print(f"FULL MODE: Using all {len(df)} questions")
        return df.copy()