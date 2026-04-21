package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class NamedParameterRewriter {

    private NamedParameterRewriter() {
    }

    public static PreparedSql rewrite(String sql, Map<String, ?> namedParams) {
        ParsedSql parsedSql = NamedParameterUtils.parseSqlStatement(sql);
        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        if (namedParams != null) {
            namedParams.forEach(paramSource::addValue);
        }
        String rewrittenSql = NamedParameterUtils.substituteNamedParameters(parsedSql, paramSource);
        Object[] values = NamedParameterUtils.buildValueArray(parsedSql, paramSource, null);

        List<Object> orderedParams = new ArrayList<>(values.length);
        for (Object value : values) {
            orderedParams.add(value);
        }
        return new PreparedSql(rewrittenSql, orderedParams);
    }

    public record PreparedSql(String sql, List<Object> params) {
    }
}
