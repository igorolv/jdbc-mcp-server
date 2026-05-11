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
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryColumnRef;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryFeatures;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryInspection;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryJoin;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryOrderBy;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryParameter;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryPredicate;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QuerySelectItem;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryTableRef;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryWarning;

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

    public QueryInspection inspect(String sql) {
        if (sql == null || sql.isBlank()) {
            return QueryInspection.error("SQL is empty");
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

            return new QueryInspection(
                    true,
                    null,
                    model.statementType,
                    model.explain,
                    dedupeTables(model.tables),
                    model.aliases,
                    new ArrayList<>(model.cteNames),
                    model.selectItems,
                    model.joins,
                    model.predicates,
                    model.orderBy,
                    dedupeColumns(model.columns),
                    model.parameters,
                    features(model),
                    model.warnings,
                    model.normalizedSql);
        } catch (JSQLParserException | RuntimeException e) {
            return QueryInspection.error(rootMessage(e));
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
                String alias = item.getAliasName();
                boolean star = item.toString().contains("*");
                if (item.toString().contains("*")) {
                    model.hasSelectStar = true;
                }
                List<QueryColumnRef> cols = collectColumns(item.getExpression(), model, "select");
                model.selectItems.add(new QuerySelectItem(
                        item.toString(),
                        alias,
                        star ? Boolean.TRUE : null,
                        cols.isEmpty() ? null : cols));
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
                String on = null;
                List<String> using = null;
                if (join.getOnExpression() != null) {
                    on = join.getOnExpression().toString();
                    collectColumns(join.getOnExpression(), model, "join");
                    extractJoinPairs(join, join.getOnExpression(), model);
                }
                if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
                    using = join.getUsingColumns().stream().map(Column::getColumnName).toList();
                    for (Column c : join.getUsingColumns()) addColumn(c, model, "join");
                }
                boolean conditionless = false;
                if (join.isSimple() || join.isCross() ||
                        (join.getOnExpression() == null &&
                                (join.getUsingColumns() == null || join.getUsingColumns().isEmpty()))) {
                    conditionless = true;
                    model.warnings.add(warning("join_without_condition",
                            "Join has no ON/USING condition: " + join));
                }
                model.joins.add(new QueryJoin(
                        joinType(join),
                        String.valueOf(join.getRightItem()),
                        on,
                        using,
                        conditionless ? Boolean.TRUE : null));
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
                QueryTableRef ref = tableRef(table, source);
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
     * pair as a structured JoinPair. Non-equality conjuncts (BETWEEN, function calls,
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
            List<QueryColumnRef> cols = collectColumns(part, model, scope);
            if (part instanceof LikeExpression like && like.getRightExpression() != null) {
                String rhs = like.getRightExpression().toString();
                if (rhs.startsWith("'%")) {
                    model.warnings.add(warning("leading_wildcard_like",
                        "LIKE predicate starts with a wildcard and usually cannot use a normal B-tree index: " + part));
                }
            }
            model.predicates.add(new QueryPredicate(
                    scope,
                    part.toString(),
                    simpleType(part),
                    cols.isEmpty() ? null : cols));
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

    private List<QueryColumnRef> collectColumns(Expression expression, QueryModel model, String context) {
        if (expression == null) return List.of();
        List<QueryColumnRef> found = new ArrayList<>();
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S ctx) {
                QueryColumnRef c = addColumn(column, model, context);
                found.add(c);
                return null;
            }

            @Override
            public <S> Void visit(JdbcParameter parameter, S ctx) {
                model.parameters.add(new QueryParameter(
                        "positional",
                        null,
                        parameter.toString(),
                        parameter.getIndex()));
                return null;
            }

            @Override
            public <S> Void visit(JdbcNamedParameter parameter, S ctx) {
                model.parameters.add(new QueryParameter(
                        "named",
                        parameter.getName(),
                        parameter.toString(),
                        null));
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

    private QueryColumnRef addColumn(Column column, QueryModel model, String context) {
        QueryColumnRef c = new QueryColumnRef(
                column.getColumnName(),
                column.getTableName() != null && !column.getTableName().isBlank() ? column.getTableName() : null,
                column.toString(),
                context);
        model.columns.add(c);
        return c;
    }

    private void collectOrderBy(List<OrderByElement> elements, QueryModel model, String source) {
        if (elements == null) return;
        for (OrderByElement element : elements) {
            List<QueryColumnRef> cols = List.of();
            if (element.getExpression() != null) {
                cols = collectColumns(element.getExpression(), model, "order_by");
            }
            model.orderBy.add(new QueryOrderBy(
                    element.toString(),
                    source,
                    cols.isEmpty() ? null : cols));
        }
    }

    private QueryTableRef tableRef(Table table, String source) {
        return new QueryTableRef(
                table.getSchemaName(),
                table.getName(),
                table.getFullyQualifiedName(),
                aliasName(table),
                source);
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

    private static List<QueryTableRef> dedupeTables(Collection<QueryTableRef> rows) {
        return dedupeItems(rows, row -> row.fullName() + "|" + row.alias());
    }

    private static List<QueryColumnRef> dedupeColumns(Collection<QueryColumnRef> rows) {
        return dedupeItems(rows, row -> row.context() + "|"
                + row.qualifier() + "." + row.name());
    }

    private static <T> List<T> dedupeItems(Collection<T> rows,
                                          java.util.function.Function<T, String> keyFn) {
        Set<String> seen = new LinkedHashSet<>();
        List<T> out = new ArrayList<>();
        for (T row : rows) {
            if (seen.add(keyFn.apply(row).toLowerCase(Locale.ROOT))) out.add(row);
        }
        return out;
    }

    private static QueryFeatures features(QueryModel model) {
        return new QueryFeatures(
                model.hasSelectStar,
                model.hasSetOperation,
                model.hasGroupBy,
                model.hasLimit,
                model.hasOffset,
                model.hasSelectInto,
                model.hasForUpdate,
                new ArrayList<>(model.functions));
    }

    private static QueryWarning warning(String code, String message) {
        return new QueryWarning(code, message);
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
        public final List<QueryTableRef> tables = new ArrayList<>();
        public final Map<String, String> aliases = new LinkedHashMap<>();
        public final Set<String> cteNames = new LinkedHashSet<>();
        public final List<QuerySelectItem> selectItems = new ArrayList<>();
        public final List<QueryJoin> joins = new ArrayList<>();
        public final List<QueryPredicate> predicates = new ArrayList<>();
        public final List<QueryOrderBy> orderBy = new ArrayList<>();
        public final List<QueryColumnRef> columns = new ArrayList<>();
        public final List<QueryParameter> parameters = new ArrayList<>();
        public final Set<String> functions = new LinkedHashSet<>();
        public final List<QueryWarning> warnings = new ArrayList<>();
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
            for (QueryTableRef table : tables) {
                String n = table.name();
                if (n == null) continue;
                if (!cteNames.contains(n)) names.add(n);
            }
            return names;
        }
    }
}
