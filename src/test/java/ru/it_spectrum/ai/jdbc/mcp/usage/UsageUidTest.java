package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UsageUidTest {

    @Test
    void buildsUidWithUnit() {
        String uid = UsageUid.build("SHOP", "reports/customers/CustomerCard.xdo", "CUST");
        assertThat(uid).isEqualTo("SHOP/reports/customers/CustomerCard.xdo#CUST");
    }

    @Test
    void buildsUidWithoutUnit() {
        assertThat(UsageUid.build("SHOP", "manual/ad-hoc.sql", null))
                .isEqualTo("SHOP/manual/ad-hoc.sql");
        assertThat(UsageUid.build("SHOP", "manual/ad-hoc.sql", ""))
                .isEqualTo("SHOP/manual/ad-hoc.sql");
    }

    @Test
    void parsesUidWithUnit() {
        UsageUid.Parts parts = UsageUid.parse("SHOP/reports/customers/CustomerCard.xdo#CUST");
        assertThat(parts.dataSource()).isEqualTo("SHOP");
        assertThat(parts.sourcePath()).isEqualTo("reports/customers/CustomerCard.xdo");
        assertThat(parts.sourceUnit()).isEqualTo("CUST");
    }

    @Test
    void parsesUidWithoutUnit() {
        UsageUid.Parts parts = UsageUid.parse("SHOP/manual/ad-hoc.sql");
        assertThat(parts.dataSource()).isEqualTo("SHOP");
        assertThat(parts.sourcePath()).isEqualTo("manual/ad-hoc.sql");
        assertThat(parts.sourceUnit()).isEqualTo("");
    }

    @Test
    void buildAndParseRoundtripPreservesAllPieces() {
        String built = UsageUid.build("SHOP", "com/example/shop/dao/OrderDao.java", "findByCustomer");
        UsageUid.Parts parts = UsageUid.parse(built);
        assertThat(parts.dataSource()).isEqualTo("SHOP");
        assertThat(parts.sourcePath()).isEqualTo("com/example/shop/dao/OrderDao.java");
        assertThat(parts.sourceUnit()).isEqualTo("findByCustomer");
    }

    @Test
    void rejectsSlashInDataSource() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("bad/source", "x", null))
                .withMessageContaining("dataSource");
    }

    @Test
    void rejectsHashInDataSource() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("bad#source", "x", null))
                .withMessageContaining("dataSource");
    }

    @Test
    void rejectsHashInPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("ds", "bad#path", null))
                .withMessageContaining("sourcePath");
    }

    @Test
    void rejectsSlashInUnit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("ds", "x", "bad/unit"))
                .withMessageContaining("sourceUnit");
    }

    @Test
    void rejectsHashInUnit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("ds", "x", "bad#unit"))
                .withMessageContaining("sourceUnit");
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("", "x", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("ds", "", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("ds", "  ", null));
    }

    @Test
    void parseRejectsBlankUid() {
        assertThatIllegalArgumentException().isThrownBy(() -> UsageUid.parse(""));
    }

    @Test
    void parseRejectsUidWithoutSlash() {
        assertThatIllegalArgumentException().isThrownBy(() -> UsageUid.parse("just-data-source"));
    }
}
