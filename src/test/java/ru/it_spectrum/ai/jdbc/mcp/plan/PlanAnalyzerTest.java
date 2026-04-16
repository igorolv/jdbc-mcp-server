package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAnalyzerTest {

    private PlanNode node(String type, String rel, Double cost, Long est, Long act,
                          Double actTime, Map<String, Object> raw, PlanNode... children) {
        List<PlanNode> kids = new ArrayList<>();
        for (PlanNode c : children) kids.add(c);
        return new PlanNode(type, rel, cost, null, est, act, 1L, actTime,
                raw == null ? new LinkedHashMap<>() : raw, kids);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flagsLargeFullScan() {
        PlanNode scan = node("Seq Scan", "big_table", 1000.0, 500_000L, null, null, null);
        ParsedPlan p = new ParsedPlan("postgresql", scan, false, null, null, null);
        Map<String, Object> summary = PlanAnalyzer.summarize(p);

        assertThat(summary.get("node_count")).isEqualTo(1);
        List<Map<String, Object>> scans = (List<Map<String, Object>>) summary.get("full_scans");
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).get("relation")).isEqualTo("big_table");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignoresSmallFullScan() {
        PlanNode scan = node("Seq Scan", "small_table", 10.0, 50L, null, null, null);
        ParsedPlan p = new ParsedPlan("postgresql", scan, false, null, null, null);
        Map<String, Object> summary = PlanAnalyzer.summarize(p);

        assertThat((List<Map<String, Object>>) summary.get("full_scans")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void flagsEstimationErrorWhenAnalyzed() {
        // Planner expected 10 rows, reality is 10000 — ratio ≈ 909, way over threshold.
        PlanNode leaf = node("Index Scan", "t", 10.0, 10L, 10_000L, 5.0, null);
        ParsedPlan p = new ParsedPlan("postgresql", leaf, true, null, null, null);
        Map<String, Object> summary = PlanAnalyzer.summarize(p);

        List<Map<String, Object>> errs = (List<Map<String, Object>>) summary.get("estimation_errors");
        assertThat(errs).hasSize(1);
        assertThat(((Number) errs.get(0).get("ratio")).doubleValue()).isGreaterThan(100.0);
        assertThat((String) errs.get(0).get("reason")).contains("underestimated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsEstimationErrorWhenNotAnalyzed() {
        PlanNode leaf = node("Index Scan", "t", 10.0, 10L, 10_000L, 5.0, null);
        ParsedPlan p = new ParsedPlan("postgresql", leaf, false, null, null, null);
        Map<String, Object> summary = PlanAnalyzer.summarize(p);
        assertThat((List<Map<String, Object>>) summary.get("estimation_errors")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void flagsRiskyNestedLoopWithLargeOuter() {
        PlanNode outer = node("Seq Scan", "a", 100.0, 50_000L, 50_000L, 10.0, null);
        PlanNode inner = node("Index Scan", "b", 5.0, 1L, 1L, 0.1, null);
        PlanNode nl = node("Nested Loop", null, 1000.0, 50_000L, 50_000L, 500.0, null, outer, inner);
        ParsedPlan p = new ParsedPlan("postgresql", nl, true, null, null, null);

        Map<String, Object> summary = PlanAnalyzer.summarize(p);
        List<Map<String, Object>> nls = (List<Map<String, Object>>) summary.get("risky_nested_loops");
        assertThat(nls).hasSize(1);
        assertThat(((Number) nls.get(0).get("outer_rows")).longValue()).isEqualTo(50_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flagsExternalSortSpill() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("Sort Method", "external merge");
        raw.put("Sort Space Used", 32_000);
        PlanNode sort = node("Sort", null, 500.0, 1000L, 1000L, 50.0, raw);
        ParsedPlan p = new ParsedPlan("postgresql", sort, true, null, null, null);

        Map<String, Object> summary = PlanAnalyzer.summarize(p);
        List<Map<String, Object>> spills = (List<Map<String, Object>>) summary.get("disk_spills");
        assertThat(spills).hasSize(1);
        assertThat((String) spills.get(0).get("sort_method")).contains("external");
    }

    @Test
    @SuppressWarnings("unchecked")
    void topExpensiveRanksByTimeWhenAnalyzed() {
        PlanNode cheap = node("Index Scan", "a", 10.0, 100L, 100L, 1.0, null);
        PlanNode pricey = node("Seq Scan", "b", 500.0, 1000L, 1000L, 42.0, null);
        PlanNode root = node("Append", null, 600.0, 1100L, 1100L, 43.0, null, cheap, pricey);
        ParsedPlan p = new ParsedPlan("postgresql", root, true, null, null, null);

        Map<String, Object> summary = PlanAnalyzer.summarize(p);
        List<Map<String, Object>> top = (List<Map<String, Object>>) summary.get("top_expensive_nodes");
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).get("ranked_by")).isEqualTo("actual_total_time_ms");
        // The Append root aggregates children's time and wins.
        assertThat(top.get(0).get("node_type")).isEqualTo("Append");
    }
}
