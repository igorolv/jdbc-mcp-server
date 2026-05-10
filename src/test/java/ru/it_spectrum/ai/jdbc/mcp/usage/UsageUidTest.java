package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UsageUidTest {

    @Test
    void buildsUidWithUnit() {
        String uid = UsageUid.build("dao", "reports/customers/CustomerCard.xdo", "CUST");
        assertThat(uid).isEqualTo("dao/reports/customers/CustomerCard.xdo#CUST");
    }

    @Test
    void buildsUidWithoutUnit() {
        assertThat(UsageUid.build("dao", "manual/ad-hoc.sql", null))
                .isEqualTo("dao/manual/ad-hoc.sql");
        assertThat(UsageUid.build("dao", "manual/ad-hoc.sql", ""))
                .isEqualTo("dao/manual/ad-hoc.sql");
    }

    @Test
    void parsesUidWithUnit() {
        UsageUid.Parts parts = UsageUid.parse("dao/reports/customers/CustomerCard.xdo#CUST");
        assertThat(parts.sourceKind()).isEqualTo("dao");
        assertThat(parts.sourcePath()).isEqualTo("reports/customers/CustomerCard.xdo");
        assertThat(parts.sourceUnit()).isEqualTo("CUST");
    }

    @Test
    void parsesUidWithoutUnit() {
        UsageUid.Parts parts = UsageUid.parse("dao/manual/ad-hoc.sql");
        assertThat(parts.sourceKind()).isEqualTo("dao");
        assertThat(parts.sourcePath()).isEqualTo("manual/ad-hoc.sql");
        assertThat(parts.sourceUnit()).isEqualTo("");
    }

    @Test
    void buildAndParseRoundtripPreservesAllPieces() {
        String built = UsageUid.build("dao", "com/example/shop/dao/OrderDao.java", "findByCustomer");
        UsageUid.Parts parts = UsageUid.parse(built);
        assertThat(parts.sourceKind()).isEqualTo("dao");
        assertThat(parts.sourcePath()).isEqualTo("com/example/shop/dao/OrderDao.java");
        assertThat(parts.sourceUnit()).isEqualTo("findByCustomer");
    }

    @Test
    void rejectsSlashInSourceKind() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("bad/kind", "x", null))
                .withMessageContaining("sourceKind");
    }

    @Test
    void rejectsHashInSourceKind() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("bad#kind", "x", null))
                .withMessageContaining("sourceKind");
    }

    @Test
    void rejectsHashInPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("kind", "bad#path", null))
                .withMessageContaining("sourcePath");
    }

    @Test
    void rejectsSlashInUnit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("kind", "x", "bad/unit"))
                .withMessageContaining("sourceUnit");
    }

    @Test
    void rejectsHashInUnit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("kind", "x", "bad#unit"))
                .withMessageContaining("sourceUnit");
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("", "x", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("kind", "", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UsageUid.build("kind", "  ", null));
    }

    @Test
    void parseRejectsBlankUid() {
        assertThatIllegalArgumentException().isThrownBy(() -> UsageUid.parse(""));
    }

    @Test
    void parseRejectsUidWithoutSlash() {
        assertThatIllegalArgumentException().isThrownBy(() -> UsageUid.parse("just-source-kind"));
    }
}