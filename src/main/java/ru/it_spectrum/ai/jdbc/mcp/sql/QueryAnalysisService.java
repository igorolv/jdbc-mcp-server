package ru.it_spectrum.ai.jdbc.mcp.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL AST inspection based on JSqlParser. This is deliberately informational: the
 * security-oriented {@link ReadOnlyGuard} remains the enforcement layer.
 */
@Service
public class QueryAnalysisService {

    public Map<String, Object> inspect(String sql) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sql == null || sql.isBlank()) {
            out.put("parseable", false);
            out.put("error", "SQL is empty");
            return out;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            QueryModel model = new QueryModel();
            model.statementType = simpleType(statement);
            model.normalizedSql = statement.toString();

            if (statement instanceof ExplainStatement explain) {
                model.explain = true;
                Select explained = explain.getStatement();
                if (explained != null) {
                    inspectSelect(explained, model);
                }
            } else if (statement instanceof Select select) {
                inspectSelect(select, model);
            } else {
                model.warnings.add(warning("non_select_statement",
                        "JSqlParser parsed this as " + model.statementType + ", not as SELECT/EXPLAIN."));
            }

            out.put("parseable", true);
            out.put("statementType", model.statementType);
            out.put("explain", model.explain);
            out.put("tables", dedupeTables(model.tables));
            out.put("aliases", model.aliases);
            out.put("cteNames", new ArrayList<>(model.cteNames));
            out.put("selectItems", model.selectItems);
            out.put("joins", model.joins);
            out.put("predicates", model.predicates);
            out.put("orderBy", model.orderBy);
            out.put("columns", dedupeColumns(model.columns));
            out.put("parameters", model.parameters);
            out.put("features", features(model));
            out.put("warnings", model.warnings);
            out.put("normalizedSql", model.normalizedSql);
            return out;
        } catch (JSQLParserException | RuntimeException e) {
            out.put("parseable", false);
            out.put("error", rootMessage(e));
            return out;
        }
    }

    public QueryModel model(String sql) throws JSQLParserException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        QueryModel model = new QueryModel();
        model.statementType = simpleType(statement);
        model.normalizedSql = statement.toString();
        if (statement instanceof ExplainStatement explain) {
            model.explain = true;
            if (explain.getStatement() != null) inspectSelect(explain.getStatement(), model);
        } else if (statement instanceof Select select) {
            inspectSelect(select, model);
        }
        return model;
    }

    private void inspectSelect(Select select, QueryModel model) {
        collectWithItems(select, model);
        select.accept(new SelectVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(PlainSelect plain, S context) {
                inspectPlainSelect(plain, model);
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect parenthesed, S context) {
                if (parenthesed.getSelect() != null) inspectSelect(parenthesed.getSelect(), model);
                return null;
            }

            @Override
            public <S> Void visit(SetOperationList set, S context) {
                model.hasSetOperation = true;
                if (set.getSelects() != null) {
                    for (Select item : set.getSelects()) inspectSelect(item, model);
                }
                return null;
            }

            @Override
            public <S> Void visit(WithItem<?> withItem, S context) {
                inspectWithItem(withItem, model);
                return null;
            }
        }, null);

        collectOrderBy(select.getOrderByElements(), model, "select");
        if (select.getLimit() != null || select.getFetch() != null) model.hasLimit = true;
        if (select.getOffset() != null) model.hasOffset = true;
        if (select.getForClause() != null || select.getForUpdateTable() != null) model.hasForUpdate = true;
    }

    private void inspectPlainSelect(PlainSelect plain, QueryModel model) {
        registerFromItem(plain.getFromItem(), model, "from");

        if (plain.getSelectItems() != null) {
            for (SelectItem<?> item : plain.getSelectItems()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("expression", item.toString());
                if (item.getAliasName() != null) entry.put("alias", item.getAliasName());
                if (item.toString().contains("*")) {
                    model.hasSelectStar = true;
                    entry.put("star", true);
                }
                List<Map<String, Object>> cols = collectColumns(item.getExpression(), model, "select");
                if (!cols.isEmpty()) entry.put("columns", cols);
                model.selectItems.add(entry);
            }
        }

        if (plain.getWhere() != null) {
            addPredicate("where", plain.getWhere(), model);
        }
        if (plain.getHaving() != null) {
            addPredicate("having", plain.getHaving(), model);
        }

        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                registerFromItem(join.getRightItem(), model, "join");
                Map<String, Object> j = new LinkedHashMap<>();
                j.put("type", joinType(join));
                j.put("rightItem", String.valueOf(join.getRightItem()));
                if (join.getOnExpression() != null) {
                    j.put("on", join.getOnExpression().toString());
                    collectColumns(join.getOnExpression(), model, "join");
                    extractJoinPairs(join, join.getOnExpression(), model);
                }
                if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
                    List<String> cols = join.getUsingColumns().stream().map(Column::getColumnName).toList();
                    j.put("using", cols);
                    for (Column c : join.getUsingColumns()) addColumn(c, model, "join");
                }
                if (join.isSimple() || join.isCross() ||
                        (join.getOnExpression() == null &&
                                (join.getUsingColumns() == null || join.getUsingColumns().isEmpty()))) {
                    j.put("conditionless", true);
                    model.warnings.add(warning("join_without_condition",
                            "Join has no ON/USING condition: " + join));
                }
                model.joins.add(j);
            }
        }

        collectOrderBy(plain.getOrderByElements(), model, "plain_select");
        if (plain.getGroupBy() != null) model.hasGroupBy = true;
        if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) model.hasSelectInto = true;
        if (plain.getIntoTempTable() != null) model.hasSelectInto = true;
    }

    private void collectWithItems(Select select, QueryModel model) {
        if (select.getWithItemsList() == null) return;
        for (WithItem<?> item : select.getWithItemsList()) {
            inspectWithItem(item, model);
        }
    }

    private void inspectWithItem(WithItem<?> item, QueryModel model) {
        if (item == null) return;
        if (item.getAliasName() != null) model.cteNames.add(item.getAliasName());
        if (item.getSelect() != null && item.getSelect().getSelect() != null) {
            inspectSelect(item.getSelect().getSelect(), model);
        }
    }

    private void registerFromItem(FromItem item, QueryModel model, String source) {
        if (item == null) return;
        item.accept(new FromItemVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Table table, S context) {
                Map<String, Object> ref = tableRef(table, source);
                model.tables.add(ref);
                String alias = aliasName(table);
                if (alias != null) model.aliases.put(alias, table.getFullyQualifiedName());
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect select, S context) {
                if (select.getSelect() != null) inspectSelect(select.getSelect(), model);
                if (select.getAlias() != null) model.aliases.put(select.getAlias().getName(), "(subquery)");
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedFromItem parenthesed, S context) {
                registerFromItem(parenthesed.getFromItem(), model, source);
                if (parenthesed.getJoins() != null) {
                    for (Join join : parenthesed.getJoins()) registerFromItem(join.getRightItem(), model, "join");
                }
                return null;
            }
        }, null);
    }

    /**
     * Walks the AND-conjuncts of a JOIN ON-expression and records each {@code Column = Column}
     * pair as a structured {@link JoinPair}. Non-equality conjuncts (BETWEEN, function calls,
     * literal comparisons) are intentionally ignored — they remain visible as predicates but
     * do not contribute to the "observed equi-join" evidence used by the usage catalog.
     */
    private void extractJoinPairs(Join join, Expression onExpression, QueryModel model) {
        for (Expression part : splitAnd(onExpression)) {
            if (part instanceof EqualsTo eq
                    && eq.getLeftExpression() instanceof Column left
                    && eq.getRightExpression() instanceof Column right) {
                QueryModel.JoinPair pair = new QueryModel.JoinPair(
                        joinType(join),
                        left.getTableName(), left.getColumnName(),
                        right.getTableName(), right.getColumnName(),
                        onExpression.toString());
                model.joinPairs.add(pair);
            }
        }
    }

    private void addPredicate(String scope, Expression expression, QueryModel model) {
        for (Expression part : splitAnd(expression)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("scope", scope);
            p.put("expression", part.toString());
            p.put("operator", simpleType(part));
            List<Map<String, Object>> cols = collectColumns(part, model, scope);
            if (!cols.isEmpty()) p.put("columns", cols);
            if (part instanceof LikeExpression like && like.getRightExpression() != null) {
                String rhs = like.getRightExpression().toString();
                if (rhs.length() >= 2 && rhs.startsWith("'%")) {
                    model.warnings.add(warning("leading_wildcard_like",
                            "LIKE predicate starts with a wildcard and usually cannot use a normal B-tree index: " + part));
                }
            }
            model.predicates.add(p);
        }
    }

    private List<Expression> splitAnd(Expression expression) {
        List<Expression> out = new ArrayList<>();
        if (expression instanceof AndExpression and) {
            out.addAll(splitAnd(and.getLeftExpression()));
            out.addAll(splitAnd(and.getRightExpression()));
        } else {
            out.add(expression);
        }
        return out;
    }

    private List<Map<String, Object>> collectColumns(Expression expression, QueryModel model, String context) {
        if (expression == null) return List.of();
        List<Map<String, Object>> found = new ArrayList<>();
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S ctx) {
                Map<String, Object> c = addColumn(column, model, context);
                found.add(c);
                return null;
            }

            @Override
            public <S> Void visit(JdbcParameter parameter, S ctx) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", "positional");
                p.put("text", parameter.toString());
                if (parameter.getIndex() != null) p.put("index", parameter.getIndex());
                model.parameters.add(p);
                return null;
            }

            @Override
            public <S> Void visit(JdbcNamedParameter parameter, S ctx) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", "named");
                p.put("name", parameter.getName());
                p.put("text", parameter.toString());
                model.parameters.add(p);
                return null;
            }

            @Override
            public <S> Void visit(Function function, S ctx) {
                model.functions.add(function.getName());
                return super.visit(function, ctx);
            }

            @Override
            protected <S> Void visitBinaryExpression(BinaryExpression expression, S ctx) {
                return super.visitBinaryExpression(expression, ctx);
            }
        }, null);
        return dedupeColumns(found);
    }

    private Map<String, Object> addColumn(Column column, QueryModel model, String context) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", column.getColumnName());
        if (column.getTableName() != null && !column.getTableName().isBlank()) {
            c.put("qualifier", column.getTableName());
        }
        c.put("text", column.toString());
        c.put("context", context);
        model.columns.add(c);
        return c;
    }

    private void collectOrderBy(List<OrderByElement> elements, QueryModel model, String source) {
        if (elements == null) return;
        for (OrderByElement element : elements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("expression", element.toString());
            row.put("source", source);
            if (element.getExpression() != null) {
                List<Map<String, Object>> cols = collectColumns(element.getExpression(), model, "order_by");
                if (!cols.isEmpty()) row.put("columns", cols);
            }
            model.orderBy.add(row);
        }
    }

    private Map<String, Object> tableRef(Table table, String source) {
        Map<String, Object> ref = new LinkedHashMap<>();
        if (table.getSchemaName() != null) ref.put("schema", table.getSchemaName());
        ref.put("name", table.getName());
        ref.put("fullName", table.getFullyQualifiedName());
        String alias = aliasName(table);
        if (alias != null) ref.put("alias", alias);
        ref.put("source", source);
        return ref;
    }

    private static String aliasName(Table table) {
        return table.getAlias() == null ? null : table.getAlias().getName();
    }

    private static String simpleType(Object value) {
        return value == null ? null : value.getClass().getSimpleName();
    }

    private static String joinType(Join join) {
        if (join.isCross()) return "CROSS";
        if (join.isFull()) return "FULL";
        if (join.isLeft()) return "LEFT";
        if (join.isRight()) return "RIGHT";
        if (join.isInner() || join.isInnerJoin()) return "INNER";
        if (join.isSimple()) return "SIMPLE";
        return "JOIN";
    }

    private static List<Map<String, Object>> dedupeTables(Collection<Map<String, Object>> rows) {
        return dedupeMaps(rows, row -> String.valueOf(row.get("fullName")) + "|" + row.get("alias"));
    }

    private static List<Map<String, Object>> dedupeColumns(Collection<Map<String, Object>> rows) {
        return dedupeMaps(rows, row -> String.valueOf(row.get("context")) + "|"
                + String.valueOf(row.get("qualifier")) + "." + row.get("name"));
    }

    private static List<Map<String, Object>> dedupeMaps(Collection<Map<String, Object>> rows,
                                                       java.util.function.Function<Map<String, Object>, String> keyFn) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (seen.add(keyFn.apply(row).toLowerCase(Locale.ROOT))) out.add(row);
        }
        return out;
    }

    private static Map<String, Object> features(QueryModel model) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("selectStar", model.hasSelectStar);
        features.put("setOperation", model.hasSetOperation);
        features.put("groupBy", model.hasGroupBy);
        features.put("limitOrFetch", model.hasLimit);
        features.put("offset", model.hasOffset);
        features.put("selectInto", model.hasSelectInto);
        features.put("forUpdate", model.hasForUpdate);
        features.put("functions", new ArrayList<>(model.functions));
        return features;
    }

    private static Map<String, Object> warning(String code, String message) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", code);
        w.put("message", message);
        return w;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }

    public static final class QueryModel {
        public String statementType;
        public String normalizedSql;
        public boolean explain;
        public boolean hasSelectStar;
        public boolean hasSetOperation;
        public boolean hasGroupBy;
        public boolean hasLimit;
        public boolean hasOffset;
        public boolean hasSelectInto;
        public boolean hasForUpdate;
        public final List<Map<String, Object>> tables = new ArrayList<>();
        public final Map<String, String> aliases = new LinkedHashMap<>();
        public final Set<String> cteNames = new LinkedHashSet<>();
        public final List<Map<String, Object>> selectItems = new ArrayList<>();
        public final List<Map<String, Object>> joins = new ArrayList<>();
        public final List<Map<String, Object>> predicates = new ArrayList<>();
        public final List<Map<String, Object>> orderBy = new ArrayList<>();
        public final List<Map<String, Object>> columns = new ArrayList<>();
        public final List<Map<String, Object>> parameters = new ArrayList<>();
        public final Set<String> functions = new LinkedHashSet<>();
        public final List<Map<String, Object>> warnings = new ArrayList<>();
        public final List<JoinPair> joinPairs = new ArrayList<>();

        /**
         * A single equi-join column pair {@code leftQualifier.leftColumn = rightQualifier.rightColumn}
         * extracted from a JOIN ON expression. Qualifiers are raw alias/table names as written in
         * the SQL — resolution to physical tables happens at a higher layer.
         */
        public record JoinPair(
                String joinType,
                String leftQualifier,
                String leftColumn,
                String rightQualifier,
                String rightColumn,
                String onText
        ) {
        }

        public Set<String> physicalTableNames() {
            Set<String> names = new LinkedHashSet<>();
            for (Map<String, Object> table : tables) {
                Object name = table.get("name");
                if (name == null) continue;
                String n = String.valueOf(name);
                if (!cteNames.contains(n)) names.add(n);
            }
            return names;
        }
    }
}
