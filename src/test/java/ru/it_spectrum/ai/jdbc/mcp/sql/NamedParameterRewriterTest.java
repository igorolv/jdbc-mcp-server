package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamedParameterRewriterTest {

    @Test
    void rewritesRepeatedNamedParametersInOrder() {
        NamedParameterRewriter.PreparedSql prepared = NamedParameterRewriter.rewrite(
                "SELECT * FROM t WHERE a = :id OR b = :id OR c = :name",
                Map.of("id", 42, "name", "alice"));

        assertThat(prepared.sql()).isEqualTo("SELECT * FROM t WHERE a = ? OR b = ? OR c = ?");
        assertThat(prepared.params()).containsExactly(42, 42, "alice");
    }

    @Test
    void leavesPositionalSqlUntouchedWhenNoNamedParametersPresent() {
        NamedParameterRewriter.PreparedSql prepared = NamedParameterRewriter.rewrite(
                "SELECT * FROM t WHERE a = ?",
                Map.of());

        assertThat(prepared.sql()).isEqualTo("SELECT * FROM t WHERE a = ?");
        assertThat(prepared.params()).hasSize(1);
    }

    @Test
    void throwsWhenNamedParameterIsMissing() {
        assertThatThrownBy(() -> NamedParameterRewriter.rewrite(
                "SELECT * FROM t WHERE a = :missing",
                Map.of("other", 1)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rewritesInListExpressionWithNamedParameter() {
        // Regression test: `:code IN ('A', 'K', 'C')` should produce 3 bind values
        // (one per occurrence of :code), not misinterpret the ':' inside the string literals.
        NamedParameterRewriter.PreparedSql prepared = NamedParameterRewriter.rewrite(
                "SELECT 1 FROM dual WHERE (:code IN ('A', 'K', 'C') OR :code NOT IN ('A', 'K', 'C'))",
                Map.of("code", "A"));

        assertThat(prepared.sql()).isEqualTo("SELECT 1 FROM dual WHERE (? IN ('A', 'K', 'C') OR ? NOT IN ('A', 'K', 'C'))");
        assertThat(prepared.params()).containsExactly("A", "A");
    }

    @Test
    void rewritesComplexPermissionsQuery_allNamedParamsReplaced() {
        // Exact query from the ORA-17041 regression — full permissions query with
        // multiple named params and IN-expressions inside EXISTS subqueries.
        String sql = """
            select sf.system_function_id, sga.system_grid_id, sga.system_action_id, sa.sa_class_name
              from system_function sf, SYSTEM_FUNCTION_GRID sfg, SYSTEM_GRID_ACTION sga, SYSTEM_ACTION sa
             where sfg.system_function_id = sf.system_function_id
               and sga.system_grid_id = sfg.system_grid_id
               and sf.SUBSYSTEM_CODE = :SubSystemCode
               and sa.system_action_id = sga.system_action_id
               and ( (:SubSystemCode IN ('A', 'K', 'C')
                      or UPPER(sga.sga_params) like '%COMMONSUBSYSTEMFUNCTION%'
                      or UPPER(sa.sa_parameters) like '%COMMONSUBSYSTEMFUNCTION%')
                    and
                    (exists (select 1 from ROLE_FUNCTION_ACTION rfa
                             join EMP_ROLE_NN_USER eru on eru.EMP_ROLE_ID = rfa.EMP_ROLE_ID
                             where eru.USER_ID = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                     or
                     exists (select 1 from ROLE_FUNCTION_ACTION rfa
                             join INSERTED_ROLE ir on ir.EMP_EMP_ROLE_ID = rfa.EMP_ROLE_ID
                             join LIQ_BANK_USER_ROLE eru on eru.EMP_ROLE_ID = ir.EMP_ROLE_ID
                             where eru.USER_ID = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                     or
                     exists (select 1 from USER_GROUP_USER ugu
                             join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                             join INSERTED_ROLE ir on ir.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                             join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ir.EMP_EMP_ROLE_ID
                             join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                          and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                             where ugu.user_id = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                     or
                     exists (select 1 from USER_GROUP_USER ugu
                             join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                             join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ugr.emp_role_id
                             join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                          and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                             where ugu.user_id = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID))
               )
               or
               (:SubSystemCode not in ('A', 'K', 'C')
                and
                (exists (select 1 from ROLE_FUNCTION_ACTION rfa
                         join LIQ_BANK_USER_ROLE eru on eru.EMP_ROLE_ID = rfa.EMP_ROLE_ID
                         where eru.USER_ID = :UserId
                           and eru.LIQ_BANK_DIR_CODE = :LiqBankId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                 or
                 exists (select 1 from ROLE_FUNCTION_ACTION rfa
                         join INSERTED_ROLE ir on ir.EMP_EMP_ROLE_ID = rfa.EMP_ROLE_ID
                         join LIQ_BANK_USER_ROLE eru on eru.EMP_ROLE_ID = ir.EMP_ROLE_ID
                         where eru.USER_ID = :UserId
                           and eru.LIQ_BANK_DIR_CODE = :LiqBankId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                 or
                 exists (select 1 from USER_GROUP_USER ugu
                         join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                         join INSERTED_ROLE ir on ir.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                         join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ir.EMP_EMP_ROLE_ID
                         join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                      and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                         where ugu.user_id = :UserId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                 or
                 exists (select 1 from USER_GROUP_USER ugu
                         join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                         join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ugr.emp_role_id
                         join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                      and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                         where ugu.user_id = :UserId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID))
               )
            """;
        Map<String, Object> namedParams = Map.of(
                "SubSystemCode", "A",
                "UserId", "DUMMY_USER",
                "LiqBankId", 1);

        NamedParameterRewriter.PreparedSql prepared = NamedParameterRewriter.rewrite(
                "EXPLAIN PLAN FOR " + sql, namedParams);

        // All named parameters must be replaced with ? placeholders.
        assertThat(prepared.sql()).doesNotContain(":SubSystemCode");
        assertThat(prepared.sql()).doesNotContain(":UserId");
        assertThat(prepared.sql()).doesNotContain(":LiqBankId");
        assertThat(prepared.sql()).contains("?");

        // Verify: no remaining named params, reasonable param count (13 = 3 SubSystemCode + 7 UserId + 3 LiqBankId).
        assertThat(prepared.params()).hasSize(13);
    }
}
