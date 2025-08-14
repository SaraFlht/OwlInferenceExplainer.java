"""
SPARQL + TTL execution: Tests LLM ability to understand formal SPARQL queries with TTL ontology context
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
output_dir = Path(f"output/llm_reasoning_results/FamilyOWL/1hop/sparql_ttl_experiment_{mode_suffix}_{timestamp}")
output_dir.mkdir(parents=True, exist_ok=True)
print(f"📂 Output directory: {output_dir}")

# Configuration for SPARQL + TTL experiment
CONFIG = {
    'experiment_type': 'sparql_ttl',
    'description': 'SPARQL queries with TTL ontology context',
    'questions_csv': "output/FamilyOWL/1hop/test.csv",  # Updated path
    'ttl_ontology_dir': "src/main/resources/FamilyOWL_1hop",  # Updated to correct TTL directory
    'context_mode': 'ttl',
    'question_column': 'SPARQL Query',
    'models_used': MODELS_CONFIG,
    'max_workers': 3,
    'enhanced_metrics': True,
    'enable_deepeval': DEEPEVAL_CONFIG['enabled'],
    'test_mode': TEST_MODE
}

print("🚀 SPARQL + TTL EXPERIMENT")
print("=" * 60)
print(f"📋 Experiment: {CONFIG['description']}")
print(f"🧪 Mode: {'TEST' if TEST_MODE else 'PRODUCTION'}")
print(f"🤖 Models: {', '.join(CONFIG['models_used'].keys())}")
print(f"📊 DeepEval: {'ENABLED' if CONFIG['enable_deepeval'] else 'DISABLED'}")
print(f"📁 TTL Directory: {CONFIG['ttl_ontology_dir']}")
print("=" * 60)

# Verify TTL directory exists
ttl_dir = Path(CONFIG['ttl_ontology_dir'])
if not ttl_dir.exists():
    print(f"❌ Error: TTL directory does not exist: {ttl_dir}")
    print(f"Please check the path and ensure TTL files are available.")
    exit(1)

# Check available TTL files
ttl_files = list(ttl_dir.glob("*.ttl"))
print(f"📁 Found {len(ttl_files)} TTL files in {ttl_dir}:")
for ttl_file in ttl_files[:5]:  # Show first 5
    print(f"   - {ttl_file.name}")
if len(ttl_files) > 5:
    print(f"   ... and {len(ttl_files) - 5} more")

# Load SPARQL questions
print(f"\n📊 Loading SPARQL questions from: {CONFIG['questions_csv']}")
try:
    df = pd.read_csv(CONFIG['questions_csv'])
    print(f"✅ Total questions available: {len(df)}")
except FileNotFoundError:
    print(f"❌ Error: Questions CSV file not found: {CONFIG['questions_csv']}")
    print("Please run the Java pipeline first to generate the CSV file.")
    exit(1)

# Check Root Entity column and TTL file availability
print(f"\n🔍 Checking Root Entity to TTL file mapping...")
unique_root_entities = df['Root Entity'].unique()
print(f"📊 Found {len(unique_root_entities)} unique root entities")

missing_ttl_files = []
available_ttl_files = []

for root_entity in unique_root_entities:
    ttl_file_path = ttl_dir / f"{root_entity}.ttl"
    if ttl_file_path.exists():
        available_ttl_files.append(root_entity)
    else:
        missing_ttl_files.append(root_entity)

print(f"✅ TTL files available: {len(available_ttl_files)}")
print(f"❌ TTL files missing: {len(missing_ttl_files)}")

if missing_ttl_files:
    print(f"\n⚠️  Missing TTL files for root entities:")
    for missing in missing_ttl_files[:5]:  # Show first 5
        print(f"   - {missing}.ttl")
    if len(missing_ttl_files) > 5:
        print(f"   ... and {len(missing_ttl_files) - 5} more")

    # Filter DataFrame to only include available TTL files
    print(f"\n🔧 Filtering dataset to only include questions with available TTL files...")
    df = df[df['Root Entity'].isin(available_ttl_files)].copy()
    print(f"📊 Filtered dataset size: {len(df)} questions")

if len(df) == 0:
    print(f"❌ Error: No questions remain after filtering for available TTL files.")
    print(f"Please check that:")
    print(f"   1. TTL files exist in: {ttl_dir}")
    print(f"   2. Root Entity column in CSV matches TTL filenames (without .ttl extension)")
    exit(1)

# Apply test mode sampling
df_sample = get_sample_data(df)
CONFIG['total_questions'] = len(df_sample)

# Analyze SPARQL query patterns
def analyze_sparql_patterns(df, query_column):
    """Analyze patterns in SPARQL queries"""
    patterns = {
        'ASK_queries': 0,
        'SELECT_queries': 0,
        'binary_questions': 0,
        'multi_choice_questions': 0,
        'complex_patterns': 0
    }

    for idx, row in df.iterrows():
        query = str(row[query_column]) if query_column in df.columns else ""
        answer_type = str(row.get('Answer Type', '')).upper()

        if 'ASK' in query.upper():
            patterns['ASK_queries'] += 1
        elif 'SELECT' in query.upper():
            patterns['SELECT_queries'] += 1

        if answer_type == 'BIN':
            patterns['binary_questions'] += 1
        elif answer_type == 'MC':
            patterns['multi_choice_questions'] += 1

        # Count complex patterns
        if query.count('{') > 1 or 'UNION' in query.upper() or 'FILTER' in query.upper():
            patterns['complex_patterns'] += 1

    return patterns

sparql_patterns = analyze_sparql_patterns(df_sample, CONFIG['question_column'])
print(f"\n📊 SPARQL Query Analysis:")
for pattern, count in sparql_patterns.items():
    percentage = (count / len(df_sample)) * 100
    print(f"   {pattern.replace('_', ' ').title()}: {count} ({percentage:.1f}%)")

# Verify a few sample mappings
print(f"\n🔍 Sample Root Entity to TTL file verification:")
sample_entities = df_sample['Root Entity'].unique()[:3]
for entity in sample_entities:
    ttl_path = Path(CONFIG['ttl_ontology_dir']) / f"{entity}.ttl"
    status = "✅" if ttl_path.exists() else "❌"
    print(f"   {status} {entity} → {ttl_path.name}")

# Run SPARQL reasoning experiment
print(f"\n🤖 Running SPARQL reasoning experiment...")
start_time = time.time()

results_df, logs, detailed_metrics, deepeval_results = run_llm_reasoning(
    df_sample,
    ontology_base_path=CONFIG['ttl_ontology_dir'],
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
print(f"\n💾 Saving SPARQL experiment results...")
results_file = output_dir / "sparql_ttl_results.csv"
logs_file = output_dir / "sparql_ttl_logs.csv"
metrics_file = output_dir / "sparql_ttl_metrics.json"
config_file = output_dir / "experiment_config.json"

results_df.to_csv(results_file, index=False)
pd.DataFrame(logs).to_csv(logs_file, index=False)

with open(metrics_file, 'w') as f:
    json.dump(detailed_metrics, f, indent=2, default=str)

# Save experiment configuration and results
experiment_summary = {
    'config': CONFIG,
    'sparql_patterns': sparql_patterns,
    'experiment_time_seconds': experiment_time,
    'total_questions_processed': len(df_sample),
    'available_ttl_files': len(available_ttl_files),
    'missing_ttl_files': len(missing_ttl_files),
    'deepeval_results': deepeval_results if deepeval_results else {},
    'timestamp': datetime.now().isoformat()
}

with open(config_file, 'w') as f:
    json.dump(experiment_summary, f, indent=2, default=str)

print(f"\n✅ SPARQL + TTL experiment completed!")
print(f"📁 Results saved to: {output_dir}")
print(f"⏱️  Total time: {experiment_time:.1f}s ({experiment_time/60:.1f}min)")
print(f"📊 Processed {len(df_sample)} questions with TTL context")