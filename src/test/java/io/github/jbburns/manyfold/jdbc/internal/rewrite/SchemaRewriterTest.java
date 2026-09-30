package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemaRewriterTest {

  private static String rewrite(String sql, String... pairs) throws ManyfoldException {
    if (pairs.length % 2 != 0) {
      throw new IllegalArgumentException("pairs must come in from/to couples");
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return new SchemaRewriter(map).apply(sql);
  }

  @Test
  void aQualifierIsReplacedAndTheRestOfTheStatementIsUntouched() throws Exception {
    assertThat(rewrite("select * from zone1_prod.orders o where o.id = 1", "zone1_prod", "z_dev"))
        .isEqualTo("select * from z_dev.orders o where o.id = 1");
  }

  @Test
  void aNameThatIsNotFollowedByADotIsNotAQualifier() throws Exception {
    String sql =
        "select zone1_prod, zone1_prod as x from t zone1_prod where zone1_prod = zone1_prod";
    assertThat(rewrite(sql, "zone1_prod", "z_dev")).isEqualTo(sql);
  }

  @Test
  void aColumnOrAliasFollowingADotIsNotReplacedButAQualifierInTheMiddleIs() throws Exception {
    assertThat(rewrite("select t.zone1_prod from t", "zone1_prod", "x"))
        .isEqualTo("select t.zone1_prod from t");
    assertThat(rewrite("select * from db.zone1_prod.orders", "zone1_prod", "x"))
        .isEqualTo("select * from db.x.orders");
  }

  @Test
  void threePartAndDoubleDotNamesQualify() throws Exception {
    assertThat(rewrite("select * from zone1_prod.dbo.orders", "zone1_prod", "z_dev"))
        .isEqualTo("select * from z_dev.dbo.orders");
    assertThat(rewrite("select * from zone1_prod..orders", "zone1_prod", "z_dev"))
        .isEqualTo("select * from z_dev..orders");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "select * from zone1_prod.orders|select * from orders",
        "select * from zone1_prod.dbo.orders|select * from dbo.orders",
        "select * from zone1_prod..orders|select * from orders",
        "select * from zone1_prod...orders|select * from orders",
        "select zone1_prod.orders.id from zone1_prod.orders|select orders.id from orders",
        "select zone1_prod.* from zone1_prod.orders|select * from orders",
        "select zone1_prod.a.zone1_prod.b from t|select a.b from t",
      })
  void anEmptyReplacementDeletesTheIdentifierAndEveryDotThatFollowsIt(String sql, String expected)
      throws Exception {
    assertThat(rewrite(sql, "zone1_prod", "")).isEqualTo(expected);
  }

  @Test
  void deletionLeavesANameWithoutADotAlone() throws Exception {
    assertThat(rewrite("select zone1_prod from zone1_prod", "zone1_prod", ""))
        .isEqualTo("select zone1_prod from zone1_prod");
  }

  @Test
  void anUnquotedSourceMatchesAnUnquotedIdentifierIgnoringCase() throws Exception {
    assertThat(
            rewrite("select * from ZONE1_PROD.orders join Zone1_Prod.t on 1=1", "zone1_prod", "d"))
        .isEqualTo("select * from d.orders join d.t on 1=1");
    assertThat(rewrite("select * from zone1_prod.orders", "ZONE1_PROD", "d"))
        .isEqualTo("select * from d.orders");
  }

  @Test
  void anUnquotedSourceDoesNotMatchAQuotedIdentifier() throws Exception {
    String sql = "select * from \"zone1_prod\".orders";
    assertThat(rewrite(sql, "zone1_prod", "d")).isEqualTo(sql);
  }

  @Test
  void aQuotedSourceMatchesOnlyAQuotedIdentifierWithExactlyThatText() throws Exception {
    assertThat(rewrite("select * from \"Zone1\".orders", "\"Zone1\"", "d"))
        .isEqualTo("select * from d.orders");
    String differentCase = "select * from \"ZONE1\".orders";
    assertThat(rewrite(differentCase, "\"Zone1\"", "d")).isEqualTo(differentCase);
    String unquoted = "select * from Zone1.orders";
    assertThat(rewrite(unquoted, "\"Zone1\"", "d")).isEqualTo(unquoted);
  }

  @Test
  void aQuotedSourceMayContainSpacesAndDoubledQuotes() throws Exception {
    assertThat(rewrite("select * from \"My \"\"Zone\".orders", "\"My \"\"Zone\"", "d"))
        .isEqualTo("select * from d.orders");
  }

  @Test
  void aPlainReplacementIsEmittedUnquotedAndAQuotedOneKeepsItsQuotes() throws Exception {
    assertThat(rewrite("select * from a.t", "a", "Dev2")).isEqualTo("select * from Dev2.t");
    assertThat(rewrite("select * from a.t", "a", "\"Dev 2\""))
        .isEqualTo("select * from \"Dev 2\".t");
    assertThat(rewrite("select * from \"a\".t", "\"a\"", "\"Dev\"\"2\""))
        .isEqualTo("select * from \"Dev\"\"2\".t");
  }

  @Test
  void deletingAQuotedQualifierWorksToo() throws Exception {
    assertThat(rewrite("select * from \"Zone1\".orders", "\"Zone1\"", ""))
        .isEqualTo("select * from orders");
  }

  @Test
  void singleQuotedStringsAreNeverTouched() throws Exception {
    String sql = "select 'zone1_prod.orders', 'it''s zone1_prod.x' from t";
    assertThat(rewrite(sql, "zone1_prod", "d")).isEqualTo(sql);
    assertThat(rewrite("select 'a' || zone1_prod.x from t", "zone1_prod", "d"))
        .isEqualTo("select 'a' || d.x from t");
  }

  @Test
  void commentsAreNeverTouched() throws Exception {
    String sql = "select 1 -- from zone1_prod.orders\nfrom /* zone1_prod.orders */ t";
    assertThat(rewrite(sql, "zone1_prod", "d")).isEqualTo(sql);
    assertThat(rewrite(sql + " join zone1_prod.x", "zone1_prod", "d")).endsWith("join d.x");
  }

  @Test
  void bracketedAndBacktickedIdentifiersAreNeverTouched() throws Exception {
    String sql = "select [zone1_prod].orders, `zone1_prod`.orders, [a zone1_prod.b] from t";
    assertThat(rewrite(sql, "zone1_prod", "d")).isEqualTo(sql);
  }

  @Test
  void whitespaceBeforeTheDotMeansItIsNotAQualifier() throws Exception {
    String sql = "select * from zone1_prod .orders";
    assertThat(rewrite(sql, "zone1_prod", "d")).isEqualTo(sql);
    String after = "select * from zone1_prod. orders";
    assertThat(rewrite(after, "zone1_prod", "d")).isEqualTo("select * from d. orders");
  }

  @Test
  void aNameThatMerelyContainsTheSourceIsNotMatched() throws Exception {
    String sql = "select * from my_zone1_prod.a join zone1_prod2.b join x$zone1_prod.c";
    assertThat(rewrite(sql, "zone1_prod", "d")).isEqualTo(sql);
  }

  @Test
  void dollarAndHashInsideAnIdentifierAreKept() throws Exception {
    assertThat(rewrite("select * from z$1.t join z$2.u", "z$1", "d"))
        .isEqualTo("select * from d.t join z$2.u");
  }

  @Test
  void severalMappingsApplyInOneStatementAndAnIdentifierIsRewrittenOnlyOnce() throws Exception {
    assertThat(
            rewrite(
                "select * from zone1_prod.orders o join zone2_prod.customers c"
                    + " on c.id = o.customer_id join zone1_prod.items i on 1=1",
                "zone1_prod",
                "zone1_dev2",
                "zone2_prod",
                "zone2_dev2"))
        .isEqualTo(
            "select * from zone1_dev2.orders o join zone2_dev2.customers c"
                + " on c.id = o.customer_id join zone1_dev2.items i on 1=1");
    // A replacement is not fed back into the map.
    assertThat(rewrite("select * from a.t join b.u", "a", "b", "b", "c"))
        .isEqualTo("select * from b.t join c.u");
  }

  @Test
  void aStatementWithoutSubstitutionsOrMatchesIsReturnedAsTheSameInstance() throws Exception {
    String sql = "select * from other.t";
    assertThat(new SchemaRewriter(Map.of()).apply(sql)).isSameAs(sql);
    assertThat(rewrite(sql, "zone1_prod", "d")).isSameAs(sql);
  }

  @Test
  void aStatementWithQuotingThatCannotBeInterpretedIsRefused() {
    assertThatThrownBy(() -> rewrite("select 'a\\'b' from zone1_prod.t", "zone1_prod", "d"))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("backslash")
        .extracting(e -> ((ManyfoldException) e).getSQLState())
        .isEqualTo("42000");
    assertThatThrownBy(() -> rewrite("select 'unterminated from zone1_prod.t", "zone1_prod", "d"))
        .isInstanceOf(ManyfoldException.class);
    assertThatThrownBy(() -> rewrite("select 1 # c\nfrom zone1_prod.t", "zone1_prod", "d"))
        .isInstanceOf(ManyfoldException.class);
  }

  @Test
  void aStatementWithUnsafeQuotingIsAcceptedWhenThereIsNothingToSubstitute() throws Exception {
    String sql = "select $$x$$ from t";
    assertThat(new SchemaRewriter(Map.of()).apply(sql)).isSameAs(sql);
  }

  @Test
  void invalidIdentifiersAndDuplicateSourcesAreRejectedByTheConstructor() {
    assertThatThrownBy(() -> new SchemaRewriter(Map.of("a.b", "c")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SchemaRewriter(Map.of("a", "b c")))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, String> twice = new LinkedHashMap<>();
    twice.put("a", "x");
    twice.put("A", "y");
    assertThatThrownBy(() -> new SchemaRewriter(twice))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
