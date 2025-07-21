"""
evaluation.py
-------------
Streamlined automated evaluation of LLM responses with a focus on accuracy,
complexity correlation, and error analysis, generating separate reports.
"""
import pandas as pd
import numpy as np
import ast
import matplotlib.pyplot as plt
import seaborn as sns
import json

# --- Utility Functions ---
def parse_response_to_list(response_str):
    if pd.isna(response_str): return []
    response_str = str(response_str).strip()
    if not response_str: return []
    try:
        parsed = ast.literal_eval(response_str)
        return [str(item).lower().strip() for item in (parsed if isinstance(parsed, list) else [parsed])]
    except (ValueError, SyntaxError):
        if response_str.upper() in ['TRUE', 'FALSE']: return [response_str.lower()]
        return [item.strip().lower() for item in response_str.split(',') if item.strip()]

# --- Core Evaluation Logic ---
def exact_match_evaluation(answer_str, response_str):
    gold = set(parse_response_to_list(answer_str))
    pred = set(parse_response_to_list(response_str))
    return {'n_correct': len(gold & pred), 'n_gold': len(gold), 'n_pred': len(pred)}

def compute_metrics(match_result):
    n_correct, n_gold, n_pred = match_result['n_correct'], match_result['n_gold'], match_result['n_pred']
    precision = n_correct / n_pred if n_pred > 0 else 0.0
    recall = n_correct / n_gold if n_gold > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    union_size = n_gold + n_pred - n_correct
    accuracy = n_correct / union_size if union_size > 0 else 1.0
    return precision, recall, f1, accuracy

# --- Tag Complexity Functions ---
def add_tag_complexity_to_df(df, explanation_json_path, query_col="SPARQL Query"):
    with open(explanation_json_path, 'r') as f: data = json.load(f)
    sparql_to_complexity = {}
    for entity_data in data.values():
        for triple_data in entity_data.values():
            all_raw_lengths = [len(line[4:].strip()) for exp in triple_data.get("explanations", []) for line in exp if line.startswith("TAG:")]
            valid_tag_lengths = [length for length in all_raw_lengths if length > 0]
            if valid_tag_lengths:
                complexity = {'shortest': min(valid_tag_lengths), 'longest': max(valid_tag_lengths)}
                for sparql_query in triple_data.get("sparqlQueries", []): sparql_to_complexity[sparql_query] = complexity
    df['shortest_tag_complexity'] = df[query_col].map(lambda q: sparql_to_complexity.get(q, {}).get('shortest', 0)).astype(int)
    df['longest_tag_complexity'] = df[query_col].map(lambda q: sparql_to_complexity.get(q, {}).get('longest', 0)).astype(int)
    print(f"Tag complexity added. Found data for {sum(df['longest_tag_complexity'] > 0)} out of {len(df)} queries.")
    return df

# --- Evaluation Function ---
def evaluate_model_responses(df, model_cols, answer_col="Answer"):
    for model in model_cols:
        metrics = df.apply(lambda row: compute_metrics(exact_match_evaluation(row[answer_col], row[model])), axis=1)
        df[[f"{model}_precision", f"{model}_recall", f"{model}_f1", f"{model}_accuracy"]] = pd.DataFrame(metrics.tolist(), index=df.index)
    return df

# --- Visualization Functions ---

def plot_overall_scores(df, model_cols, out_prefix):
    """Report 1: Overall mean scores and match type distribution."""
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle('Overall Performance and Match Types', fontsize=16, weight='bold')

    # Plot 1: Overall Scores (Acc, F1, P, R)
    overall_metrics = []
    for model in model_cols:
        model_name = model.replace('_response', '')
        overall_metrics.extend([
            {'Model': model_name, 'Metric': 'Accuracy', 'Score': df[f"{model}_accuracy"].mean()},
        ])
    sns.barplot(data=pd.DataFrame(overall_metrics), x='Model', y='Score', hue='Model', ax=axes[0])
    axes[0].set_title('Overall Accuracy Scores')
    axes[0].tick_params(axis='x', rotation=15)
    axes[0].set_ylim(0, 1)

    # Plot 2: Match Type Distribution
    match_data = []
    for model in model_cols:
        accuracies = df[f"{model}_accuracy"]
        match_data.extend([
            {'Model': model.replace('_response', ''), 'Match Type': 'Perfect (Acc=1)', 'Count': (accuracies == 1.0).sum()},
            {'Model': model.replace('_response', ''), 'Match Type': 'Partial (0<Acc<1)', 'Count': ((accuracies > 0.0) & (accuracies < 1.0)).sum()},
            {'Model': model.replace('_response', ''), 'Match Type': 'Zero (Acc=0)', 'Count': (accuracies == 0.0).sum()}
        ])
    sns.barplot(data=pd.DataFrame(match_data), x='Model', y='Count', hue='Match Type', ax=axes[1])
    axes[1].set_title('Match Type Distribution')
    axes[1].tick_params(axis='x', rotation=15)

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_overall_scores.png", dpi=300)
    plt.close()

def plot_type_analysis(df, model_cols, out_prefix):
    """Report 2: Accuracy breakdown by Task and Answer Type."""
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle('Performance by Task and Answer Type', fontsize=16, weight='bold')

    # Plot 1: Accuracy by Task Type
    if 'Task Type' in df.columns:
        task_perf_df = df.melt(id_vars=['Task Type'], value_vars=[f"{m}_accuracy" for m in model_cols], var_name='Model', value_name='Accuracy')
        task_perf_df['Model'] = task_perf_df['Model'].str.replace('_accuracy', '').str.replace('_response', '')
        sns.barplot(data=task_perf_df, x='Task Type', y='Accuracy', hue='Model', ax=axes[0])
        axes[0].set_title('Accuracy by Task Type')

    # Plot 2: Accuracy by Answer Type
    if 'Answer Type' in df.columns:
        ans_type_perf_df = df.melt(id_vars=['Answer Type'], value_vars=[f"{m}_accuracy" for m in model_cols], var_name='Model', value_name='Accuracy')
        ans_type_perf_df['Model'] = ans_type_perf_df['Model'].str.replace('_accuracy', '').str.replace('_response', '')
        sns.barplot(data=ans_type_perf_df, x='Answer Type', y='Accuracy', hue='Model', ax=axes[1])
        axes[1].set_title('Accuracy by Answer Type')

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_type_analysis.png", dpi=300)
    plt.close()

def plot_error_rate_analysis(df, model_cols, out_prefix):
    """Report 3: Error rate heatmaps for shortest and longest tag complexity with 3 degrees."""
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle('Error Rate vs. Tag Complexity', fontsize=16, weight='bold')

    def _plot_heatmap(complexity_col, ax):
        df_nonzero = df[df[complexity_col] > 0].copy()
        if df_nonzero.empty:
            ax.text(0.5, 0.5, 'No Data', ha='center', va='center'); return

        bin_labels = ['Low', 'Mid', 'High']
        df_nonzero['Complexity Bin'] = pd.qcut(df_nonzero[complexity_col].rank(method='first'), q=3, labels=bin_labels)
        long_format_df = df_nonzero.melt(id_vars=['Complexity Bin'], value_vars=[f"{m}_accuracy" for m in model_cols],
                                         var_name='Model', value_name='Accuracy')
        long_format_df['Model'] = long_format_df['Model'].str.replace('_accuracy', '').str.replace('_response', '')
        long_format_df['Error Rate'] = 1 - long_format_df['Accuracy']
        pivot_data = long_format_df.groupby(['Model', 'Complexity Bin'], observed=True)['Error Rate'].mean().unstack()
        sns.heatmap(pivot_data, annot=True, fmt='.3f', cmap='Reds', ax=ax)
        ax.set_xlabel("Complexity Bin")

    _plot_heatmap('longest_tag_complexity', axes[0])
    axes[0].set_title('Error Rate by Longest Tag Complexity')
    _plot_heatmap('shortest_tag_complexity', axes[1])
    axes[1].set_title('Error Rate by Shortest Tag Complexity')

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_error_rate_heatmaps.png", dpi=300)
    plt.close()

def plot_correlation_analysis(df, model_cols, out_prefix):
    """Report 4: Correlation of all scores with tag complexity."""
    fig, axes = plt.subplots(1, 2, figsize=(16, 8))
    fig.suptitle('Metric Correlation with Tag Complexity', fontsize=16, weight='bold')

    def _plot_correlation_bars(complexity_col, ax):
        correlation_cols = [complexity_col]
        for model in model_cols:
            correlation_cols.extend([f"{model}_accuracy", f"{model}_f1", f"{model}_precision", f"{model}_recall"])
        corr_matrix = df[correlation_cols].corr()
        complexity_corr = corr_matrix.get(complexity_col, pd.Series()).drop(complexity_col, errors='ignore')
        if complexity_corr.empty:
            ax.text(0.5, 0.5, 'No Data', ha='center', va='center'); return

        complexity_corr_df = complexity_corr.reset_index(name='Correlation').rename(columns={'index': 'Metric'})
        complexity_corr_df['Model'] = complexity_corr_df['Metric'].apply(lambda x: x.split('_response')[0])
        complexity_corr_df['Metric Type'] = complexity_corr_df['Metric'].apply(lambda x: x.split('_')[-1]).str.capitalize()
        sns.barplot(data=complexity_corr_df, x='Correlation', y='Model', hue='Metric Type', ax=ax, orient='h')
        ax.axvline(x=0, color='black', linestyle='--', alpha=0.7)

    _plot_correlation_bars('longest_tag_complexity', axes[0])
    axes[0].set_title('Correlation with Longest Tag')
    _plot_correlation_bars('shortest_tag_complexity', axes[1])
    axes[1].set_title('Correlation with Shortest Tag')

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_correlations.png", dpi=300)
    plt.close()

# --- Main Function ---
def main(results_csv, explanation_json, out_prefix="eval"):
    """Main evaluation pipeline."""
    sns.set_theme(style="whitegrid")
    print("🚀 Starting evaluation pipeline...")

    print("1. Loading and preparing data...")
    df = pd.read_csv(results_csv)
    model_cols = sorted([col for col in df.columns if col.endswith('_response')])
    print(f"   - Found models: {model_cols}")

    print("2. Adding tag complexity from explanations...")
    df = add_tag_complexity_to_df(df, explanation_json, query_col="SPARQL Query")

    print("3. Evaluating model responses...")
    df = evaluate_model_responses(df, model_cols, answer_col="Answer")

    output_csv = f"{out_prefix}_results_with_metrics.csv"
    df.to_csv(output_csv, index=False)
    print(f"4. Saved detailed results to: {output_csv}")

    print("5. Generating and saving visualization reports...")
    plot_overall_scores(df, model_cols, out_prefix)
    plot_type_analysis(df, model_cols, out_prefix)
    plot_error_rate_analysis(df, model_cols, out_prefix)
    plot_correlation_analysis(df, model_cols, out_prefix)

    print(f"✅ Analysis complete! Four report images have been saved with prefix '{out_prefix}'.")

if __name__ == "__main__":
    RESULTS_CSV = "C:/Users/saraf/IdeaProjects/owl-inference-explainer/output/llm_reasoning_results/family_1hop_results_sparql_ttl.csv"
    EXPLANATION_JSON = "C:/Users/saraf/IdeaProjects/owl-inference-explainer/output/explanations_1hop.json"
    OUTPUT_PREFIX = "C:/Users/saraf/IdeaProjects/owl-inference-explainer/output/llm_reasoning_results/family_1hop_sparql_evaluation"
    main(results_csv=RESULTS_CSV, explanation_json=EXPLANATION_JSON, out_prefix=OUTPUT_PREFIX)