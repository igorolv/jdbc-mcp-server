package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.model.plan.PlanAnalysisSummary;
import ru.it_spectrum.ai.jdbc.mcp.model.plan.PlanNodeSummary;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAnalyzerTest {

    private PlanNode node(String type, String rel, Double cost, Long est, Long act,
                          Double actTime, Map<String, Object> raw, PlanNode... children) {
        List<PlanNode> kids = new ArrayList<>();
        Collections.addAll(kids, children);
        return new PlanNode(type, rel, cost, null, est, act, 1L, actTime,
                raw == null ? new LinkedHashMap<>() : raw, kids);
    }

    @Test
    void flagsLargeFullScan() {
        PlanNode scan = node("Seq Scan", "big_table", 1000.0, 500_000L, null, null, null);
        ParsedPlan p = new ParsedPlan("postgresql", scan, false, null, null, null);
        PlanAnalysisSummary summary = PlanAnalyzer.summarize(p);

        assertThat(summary.nodeCount()).isEqualTo(1);
        List<PlanNodeSummary> scans = summary.fullScans();
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).relation()).isEqualTo("big_table");
    }

    @Test
    void ignoresSmallFullScan() {
        PlanNode scan = node("Seq Scan", "small_table", 10.0, 50L, null, null, null);
        ParsedPlan p = new ParsedPlan("postgresql", scan, false, null, null, null);
        PlanAnalysisSummary summary = PlanAnalyzer.summarize(p);

        assertThat(summary.fullScans()).isEmpty();
    }

    @Test
    void flagsEstimationErrorWhenAnalyzed() {
        // Planner expected 10 rows, reality is 10000 — ratio ≈ 909, way over threshold.
        PlanNode leaf = node("Index Scan", "t", 10.0, 10L, 10_000L, 5.0, null);
        ParsedPlan p = new ParsedPlan("postgresql", leaf, true, null, null, null);
        PlanAnalysisSummary summary = PlanAnalyzer.summarize(p);

        List<PlanNodeSummary> errs = summary.estimationErrors();
        assertThat(errs).hasSize(1);
        assertThat(errs.get(0).ratio()).isGreaterThan(100.0);
        assertThat(errs.get(0).reason()).contains("underestimated");
    }

    @Test
    void skipsEstimationErrorWhenNotAnalyzed() {
        PlanNode leaf = node("Index Scan", "t", 10.0, 10L, 10_000L, 5.0, null);
        ParsedPlan p = new ParsedPlan("postgresql", leaf, false, null, null, null);
        PlanAnalysisSummary summary = PlanAnalyzer.summarize(p);
        assertThat(summary.estimationErrors()).isEmpty();
    }

    @Test
    void flagsRiskyNestedLoopWithLargeOuter() {
        PlanNode outer = node("Seq Scan", "a", 100.0, 50_000L, 50_000L, 10.0, null);
        PlanNode inner = node("Index Scan", "b", 5.0, 1L, 1L, 0.1, null);
        PlanNode nl = node("Nested Loop", null, 1000.0, 50_000L, 50_000L, 500.0, null, outer, inner);
        ParsedPlan p = new ParsedPlan("postgresql", nl, true, null, null, null);

        PlanAnalysisSummary summary = PlanAnalyzer.summarize(p);
        List<PlanNodeSummary> nls = summary.riskyNestedLoops();
        assertThat(nls).hasSize(1);
        assertThat(nls.get(0).outerRows()).isEqualTo(50_000L);
    }

    @Test
    void flagsExternalSortSpill() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("Sort Method", "external merge");
        raw.put("Sort Space Used", 32_000);
        PlanNode sort = node("Sort", null, 500.0, 1000L, 1000L, 50.0, raw);
        ParsedPlan p = new ParsedPlan("postgresql", sort, true, null, null, null);

        PlanAnalysisSummary summary = PlanAnalyzer.summarize(p);
        List<PlanNodeSummary> spills = summary.diskSpills();
        assertThat(spills).hasSize(1);
        assertThat(spills.get(0).sortMethod()).contains("external");
    }

    @Test
    void topExpensiveRanksByTimeWhenAnalyzed() {
        PlanNode cheap = node("Index Scan", "a", 10.0, 100L, 100L, 1.0, null);
        PlanNode pricey = node("Seq Scan", "b", 500.0, 1000L, 1000L, 42.0, null);
        PlanNode root = node("Append", null, 600.0, 1100L, 1100L, 43.0, null, cheap, pricey);
        ParsedPlan p = new ParsedPlan("postgresql", root, true, null, null, null);

        PlanAnalysisSummary summary = PlanAnalyzer.summarize(p);
        List<PlanNodeSummary> top = summary.topExpensiveNodes();
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).rankedBy()).isEqualTo("actual_total_time_ms");
        // The Append root aggregates children's time and wins.
        assertThat(top.get(0).nodeType()).isEqualTo("Append");
    }
}
