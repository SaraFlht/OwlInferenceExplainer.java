"""
Natural Language + Verbalized Ontologies: Tests LLM reasoning with human-readable questions and context
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
output_dir = Path(f"output/llm_reasoning_results/nl_verbalized_experiment_{mode_suffix}_{timestamp}")
output_dir.mkdir(parents=True, exist_ok=True)
print(f"📂 Output directory: {output_dir}")

# Configuration for NL + Verbalized experiment
CONFIG = {
    'experiment_type': 'nl_verbalized',
    'description': 'Natural language questions with verbalized JSON ontology context',
    'questions_csv': "output/FamilyOWL/1hop/test.csv",  # Generated from SPARQL-to-NL conversion
    'verbalized_ontology_dir': "output/verbalized_ontologies/FamilyOWL_1hop",  # Verbalized ontologies
    'context_mode': 'json',
    'question_column': 'Question',  # Natural language questions
    'models_used': MODELS_CONFIG,
    'max_workers': 3,
    'enhanced_metrics': True,
    'enable_deepeval': DEEPEVAL_CONFIG['enabled'],
    'test_mode': TEST_MODE
}

print("NATURAL LANGUAGE + VERBALIZED ONTOLOGIES EXPERIMENT")
print("=" * 60)
print(f"Experiment: {CONFIG['description']}")
print(f"Mode: {'TEST' if TEST_MODE else 'PRODUCTION'}")
print(f"Models: {', '.join(CONFIG['models_used'].keys())}")
print(f"DeepEval: {'ENABLED' if CONFIG['enable_deepeval'] else 'DISABLED'}")
print("=" * 60)

# Load natural language questions
print(f"Loading NL questions from: {CONFIG['questions_csv']}")
df = pd.read_csv(CONFIG['questions_csv'])
print(f"Total questions available: {len(df)}")

# Apply test mode sampling
df_sample = get_sample_data(df)
CONFIG['total_questions'] = len(df_sample)

# Analyze question patterns
def analyze_nl_patterns(df):
    """Analyze patterns in natural language questions"""
    patterns = {
        'binary_questions': 0,
        'multi_choice_questions': 0,
        'membership_questions': 0,
        'property_questions': 0,
        'complex_questions': 0
    }

    for idx, row in df.iterrows():
        question = str(row.get('Question', '')).lower()
        answer_type = str(row.get('Answer Type', '')).lower()
        task_type = str(row.get('Task Type', '')).lower()

        if answer_type == 'bin':
            patterns['binary_questions'] += 1
        elif answer_type == 'mc':
            patterns['multi_choice_questions'] += 1

        if 'membership' in task_type:
            patterns['membership_questions'] += 1
        elif 'property' in task_type:
            patterns['property_questions'] += 1

        # Detect complex questions (longer, multiple clauses)
        if len(question.split()) > 10 or ' and ' in question or ' or ' in question:
            patterns['complex_questions'] += 1

    return patterns

nl_patterns = analyze_nl_patterns(df_sample)
print(f"\nNatural Language Question Analysis:")
for pattern, count in nl_patterns.items():
    percentage = (count / len(df_sample)) * 100
    print(f"   {pattern.replace('_', ' ').title()}: {count} ({percentage:.1f}%)")

# Run NL reasoning experiment
print(f"\nRunning Natural Language reasoning experiment...")
start_time = time.time()

results_df, logs, detailed_metrics, deepeval_results = run_llm_reasoning(
    df_sample,
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
print(f"\nSaving NL experiment results...")
results_file = output_dir / "nl_verbalized_results.csv"
logs_file = output_dir / "nl_verbalized_logs.csv"
metrics_file = output_dir / "nl_verbalized_metrics.json"
config_file = output_dir / "experiment_config.json"

results_df.to_csv(results_file, index=False)
pd.DataFrame(logs).to_csv(logs_file, index=False)

with open(metrics_file, 'w') as f:
    json.dump(detailed_metrics, f, indent=2, default=str)

# Save experiment configuration and results
experiment_summary = {
    'config': CONFIG,
    'nl_patterns': nl_patterns,
    'experiment_time_seconds': experiment_time,
    'total_questions_processed': len(df_sample),
    'deepeval_results': deepeval_results if deepeval_results else {},
    'timestamp': datetime.now().isoformat()
}

with open(config_file, 'w') as f:
    json.dump(experiment_summary, f, indent=2, default=str)

print(f"Natural Language + Verbalized experiment completed!")
print(f"Results saved to: {output_dir}")
print(f"Total time: {experiment_time:.1f}s ({experiment_time/60:.1f}min)")