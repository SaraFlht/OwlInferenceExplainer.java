"""
NEW EXPERIMENT: Natural Language + Explanation Context
Tests LLM reasoning with NL questions + explanation context from highest-tag explanations
"""
import pandas as pd
import os
import time
import json
from pathlib import Path
from datetime import datetime
from api_calls import run_llm_reasoning, resume_failed_queries, calculate_model_performance_summary
from test_config import get_sample_data, MODELS_CONFIG, DEEPEVAL_CONFIG, TEST_MODE

# Navigate to project root
script_dir = Path(__file__).resolve().parent
project_root = script_dir.parent.parent
os.chdir(project_root)

print(f"Working directory set to: {os.getcwd()}")

# Create timestamped output directory
timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
mode_suffix = "test" if TEST_MODE else "full"
output_dir = Path(f"output/llm_reasoning_results/nl_explanations_experiment_{mode_suffix}_{timestamp}")
output_dir.mkdir(parents=True, exist_ok=True)
print(f"📂 Output directory: {output_dir}")

# Configuration for NL + Explanations experiment
CONFIG = {
    'experiment_type': 'nl_with_explanations',
    'description': 'Natural language questions with explanation context (highest tag complexity)',
    'questions_csv': "output/FamilyOWL/1hop/test.csv",  # Natural language questions
    'explanations_json': "output/Explanations.json",  # Your comprehensive explanations
    'verbalized_ontology_dir': "output/verbalized_ontologies_json",  # For additional context
    'context_mode': 'json',
    'question_column': 'Question',
    'models_used': MODELS_CONFIG,
    'max_workers': 3,
    'enhanced_metrics': True,
    'enable_deepeval': DEEPEVAL_CONFIG['enabled'],
    'test_mode': TEST_MODE,
    'explanation_strategy': 'highest_tags'  # Use explanation with most tags
}

print("🚀 NATURAL LANGUAGE + EXPLANATION CONTEXT EXPERIMENT")
print("=" * 60)
print(f"📋 Experiment: {CONFIG['description']}")
print(f"🧪 Mode: {'TEST' if TEST_MODE else 'PRODUCTION'}")
print(f"🤖 Models: {', '.join(CONFIG['models_used'].keys())}")
print(f"📊 DeepEval: {'ENABLED' if CONFIG['enable_deepeval'] else 'DISABLED'}")
print(f"🔍 Strategy: {CONFIG['explanation_strategy']}")
print("=" * 60)

# Load explanations
print(f"📊 Loading explanations from: {CONFIG['explanations_json']}")
with open(CONFIG['explanations_json'], 'r') as f:
    explanations_data = json.load(f)
print(f"📈 Total explanations available: {len(explanations_data)}")

# Load natural language questions
print(f"📊 Loading NL questions from: {CONFIG['questions_csv']}")
df = pd.read_csv(CONFIG['questions_csv'])
print(f"📈 Total questions available: {len(df)}")

# Apply test mode sampling
df_sample = get_sample_data(df)
CONFIG['total_questions'] = len(df_sample)

def select_best_explanation(explanations_list):
    """Select explanation with highest number of tags (most complex reasoning)"""
    if not explanations_list:
        return "No explanation available"

    best_explanation = ""
    max_tags = 0

    for explanation in explanations_list:
        if isinstance(explanation, list):
            # Count tags in explanation
            tag_count = sum(1 for item in explanation if isinstance(item, str) and item.startswith("TAG:"))
            if tag_count > max_tags:
                max_tags = tag_count
                # Convert to natural language
                steps = [item for item in explanation if not (isinstance(item, str) and item.startswith("TAG:"))]
                best_explanation = ". ".join(steps) if steps else ""

    return best_explanation if best_explanation else "Reasoning available but details unclear"

def create_triple_key(subject, predicate, object_val):
    """Create triple key matching the explanation format"""
    return f"{subject}|{predicate}|{object_val}"

def add_explanation_context(df, explanations_data):
    """Add explanation context to questions"""
    df['explanation_context'] = ""
    df['explanation_found'] = False
    df['explanation_complexity'] = 0

    for idx, row in df.iterrows():
        # Try to find matching explanation
        subject = row.get('Root Entity', '')
        predicate = row.get('Predicate', 'rdf:type')  # Default to type queries
        answer = row.get('Answer', '')

        # Try different triple key combinations
        possible_keys = [
            create_triple_key(subject, predicate, answer),
            create_triple_key(answer, predicate, subject),  # Reverse
            # Add more variations as needed
        ]

        explanation_found = False
        for key in possible_keys:
            if key in explanations_data:
                explanation_info = explanations_data[key]
                if 'explanations' in explanation_info:
                    best_explanation = select_best_explanation(explanation_info['explanations'])
                    df.at[idx, 'explanation_context'] = best_explanation
                    df.at[idx, 'explanation_found'] = True
                    df.at[idx, 'explanation_complexity'] = explanation_info.get('explanationCount', 0)
                    explanation_found = True
                    break

        if not explanation_found:
            df.at[idx, 'explanation_context'] = "No specific explanation available for this reasoning step."

    return df

# Add explanation context to questions
print(f"\n🔍 Adding explanation context to questions...")
df_with_explanations = add_explanation_context(df_sample, explanations_data)

explanations_found = df_with_explanations['explanation_found'].sum()
print(f"📊 Explanation Context Analysis:")
print(f"   Questions with explanations: {explanations_found}/{len(df_with_explanations)} ({explanations_found/len(df_with_explanations)*100:.1f}%)")
print(f"   Average explanation complexity: {df_with_explanations['explanation_complexity'].mean():.2f}")

# Custom prompting function for explanation-enhanced reasoning
def create_explanation_enhanced_prompt(question, ontology_context, explanation_context):
    """Create enhanced prompt with explanation context"""

    base_prompt = f"""You are an expert in ontological reasoning. You will be given:
1. A question about ontological relationships
2. Ontology context (facts and relationships)
3. Reasoning explanation context (how similar inferences can be made)

Use the reasoning explanation to guide your thinking process, then answer the question.

QUESTION: {question}

ONTOLOGY CONTEXT:
{ontology_context}

REASONING EXPLANATION CONTEXT:
{explanation_context}

INSTRUCTIONS:
- Study the reasoning explanation to understand the inference pattern
- Apply similar reasoning to the given question
- Show your reasoning process step by step
- Provide a clear, confident answer
"""

    return base_prompt

# Modified run_llm_reasoning for explanation context
def run_explanation_enhanced_reasoning(df, ontology_base_path, models, **kwargs):
    """Modified reasoning that includes explanation context in prompts"""

    # We'll need to modify the api_calls.py or create a custom version
    # For now, let's add explanation context to a new column and mention it should be used

    df['enhanced_context'] = df.apply(lambda row:
                                      f"ONTOLOGY: {row.get('ontology_context', 'Context loading...')}\n\n"
                                      f"REASONING GUIDANCE: {row['explanation_context']}",
                                      axis=1
                                      )

    return run_llm_reasoning(df, ontology_base_path, models, **kwargs)

# Run explanation-enhanced reasoning experiment
print(f"\nRunning Explanation-Enhanced reasoning experiment...")
start_time = time.time()

results_df, logs, detailed_metrics, deepeval_results = run_llm_reasoning(
    df_with_explanations,
    ontology_base_path=CONFIG['verbalized_ontology_dir'],
    models=CONFIG['models_used'],
    context_mode=CONFIG['context_mode'],
    show_qa=True,
    max_workers=CONFIG['max_workers'],
    question_column=CONFIG['question_column'],
    save_detailed_metrics=CONFIG['enhanced_metrics'],
    enable_deepeval=CONFIG['enable_deepeval']
)

experiment_time = time.time() - start_time

# Save results
print(f"\nSaving Explanation-Enhanced experiment results...")
results_file = output_dir / "nl_explanations_results.csv"
logs_file = output_dir / "nl_explanations_logs.csv"
metrics_file = output_dir / "nl_explanations_metrics.json"
config_file = output_dir / "experiment_config.json"
explanation_analysis_file = output_dir / "explanation_analysis.json"

results_df.to_csv(results_file, index=False)
pd.DataFrame(logs).to_csv(logs_file, index=False)

with open(metrics_file, 'w') as f:
    json.dump(detailed_metrics, f, indent=2, default=str)

# Save explanation analysis
explanation_analysis = {
    'total_questions': len(df_with_explanations),
    'explanations_found': int(explanations_found),
    'explanation_coverage': explanations_found / len(df_with_explanations),
    'complexity_stats': {
        'mean': float(df_with_explanations['explanation_complexity'].mean()),
        'std': float(df_with_explanations['explanation_complexity'].std()),
        'min': int(df_with_explanations['explanation_complexity'].min()),
        'max': int(df_with_explanations['explanation_complexity'].max())
    }
}

with open(explanation_analysis_file, 'w') as f:
    json.dump(explanation_analysis, f, indent=2)

# Save experiment configuration and results
experiment_summary = {
    'config': CONFIG,
    'explanation_analysis': explanation_analysis,
    'experiment_time_seconds': experiment_time,
    'total_questions_processed': len(df_with_explanations),
    'deepeval_results': deepeval_results if deepeval_results else {},
    'timestamp': datetime.now().isoformat()
}

with open(config_file, 'w') as f:
    json.dump(experiment_summary, f, indent=2, default=str)

print(f"Explanation-NL experiment completed!")
print(f"Results saved to: {output_dir}")
print(f"Total time: {experiment_time:.1f}s ({experiment_time/60:.1f}min)")
print(f"Explanation coverage: {explanation_analysis['explanation_coverage']:.1%}")