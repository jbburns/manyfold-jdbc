package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectivesTest {

  private static final List<String> NAMES = List.of("prod", "dev2");

  private static String lines(String... lines) {
    return String.join("\n", lines);
  }

  private static Directives.Parsed parse(String sql) throws ManyfoldException {
    return Directives.parse(sql, NAMES);
  }

  @Test
  void aSingleDirectiveMapsIdentifiersForOneBackendAndIsStripped() throws Exception {
    Directives.Parsed parsed =
        parse("-- manyfold dev2: zone1_prod=zone1_dev2, zone2_prod=zone2_dev2\nselect 1");

    assertThat(parsed.sql()).isEqualTo("select 1");
    assertThat(parsed.substitutions().get(0)).isEmpty();
    assertThat(parsed.substitutions().get(1))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("zone1_prod", "zone1_dev2", "zone2_prod", "zone2_dev2"));
  }

  @Test
  void severalDirectivesInSeparateCommentsAndForSeveralBackendsAreCollected() throws Exception {
    Directives.Parsed parsed =
        parse(
            lines(
                "-- manyfold dev2: a=b",
                "-- manyfold prod: c=d",
                "/* manyfold dev2: e=f */",
                "select 1"));

    assertThat(parsed.sql()).isEqualTo("\nselect 1");
    assertThat(parsed.substitutions().get(0)).isEqualTo(Map.of("c", "d"));
    assertThat(parsed.substitutions().get(1)).isEqualTo(Map.of("a", "b", "e", "f"));
  }

  @Test
  void theBlockFormIsAccepted() throws Exception {
    Directives.Parsed parsed = parse("/* manyfold dev2: zone1_prod=zone1_dev2 */ select 1");

    assertThat(parsed.sql()).isEqualTo(" select 1");
    assertThat(parsed.substitutions().get(1)).isEqualTo(Map.of("zone1_prod", "zone1_dev2"));
  }

  @Test
  void aBlockDirectiveMayContainLineBreaks() throws Exception {
    Directives.Parsed parsed = parse("/* manyfold dev2:\n  a=b,\n  c=d\n*/select 1");

    assertThat(parsed.sql()).isEqualTo("select 1");
    assertThat(parsed.substitutions().get(1)).isEqualTo(Map.of("a", "b", "c", "d"));
  }

  @Test
  void ordinaryLeadingCommentsAreLeftAlone() throws Exception {
    Directives.Parsed parsed =
        parse("-- report for finance\n-- manyfold dev2: a=b\n/* note */\nselect 1");

    assertThat(parsed.sql()).isEqualTo("-- report for finance\n/* note */\nselect 1");
    assertThat(parsed.substitutions().get(1)).isEqualTo(Map.of("a", "b"));
  }

  @Test
  void aCommentWhoseFirstWordMerelyStartsWithManyfoldIsOrdinary() throws Exception {
    String sql = "-- manyfolds are nice\n-- manyfold_x: a=b\nselect 1";

    Directives.Parsed parsed = parse(sql);

    assertThat(parsed.sql()).isSameAs(sql);
    assertThat(parsed.substitutions()).allSatisfy(map -> assertThat(map).isEmpty());
  }

  @Test
  void aStatementWithoutDirectivesIsReturnedUnchanged() throws Exception {
    String sql = "-- just a comment\nselect * from a.t";

    Directives.Parsed parsed = parse(sql);

    assertThat(parsed.sql()).isSameAs(sql);
    assertThat(parsed.substitutions()).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "select 1 -- manyfold dev2: a=b",
        "select /* manyfold dev2: a=b */ 1",
        "select 1 /* manyfold nosuch: a=b */",
        "select 1\n-- manyfold nosuch: a=b\nfrom t",
        "(/* manyfold nosuch: a=b */ select 1)",
      })
  void aManyfoldCommentAfterTheFirstTokenIsNotADirective(String sql) throws Exception {
    Directives.Parsed parsed = parse(sql);

    assertThat(parsed.sql()).isSameAs(sql);
    assertThat(parsed.substitutions()).allSatisfy(map -> assertThat(map).isEmpty());
  }

  @Test
  void theBackendNameAndTheKeywordAreMatchedIgnoringCase() throws Exception {
    Directives.Parsed parsed = parse("-- MANYFOLD DEV2: a=b\n-- Manyfold Prod : c=d\nselect 1");

    assertThat(parsed.substitutions().get(1)).isEqualTo(Map.of("a", "b"));
    assertThat(parsed.substitutions().get(0)).isEqualTo(Map.of("c", "d"));
  }

  @Test
  void aBackendNameMayContainColons() throws Exception {
    List<String> names = List.of("postgresql://h:5432/db", "x");

    Directives.Parsed parsed =
        Directives.parse("-- manyfold postgresql://h:5432/db: a=b\nselect 1", names);

    assertThat(parsed.substitutions().get(0)).isEqualTo(Map.of("a", "b"));
  }

  @Test
  void emptyAndQuotedReplacementsAreAccepted() throws Exception {
    Directives.Parsed parsed =
        parse("-- manyfold dev2: zone1_prod=, \"Old Zone\"=\"New \"\"Zone\", \"a,b\"=c\nselect 1");

    assertThat(parsed.substitutions().get(1))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("zone1_prod", "", "\"Old Zone\"", "\"New \"\"Zone\"", "\"a,b\"", "c"));
  }

  @Test
  void anUnquotedAndAQuotedSourceOfTheSameTextAreDifferentIdentifiers() throws Exception {
    Directives.Parsed parsed = parse("-- manyfold dev2: a=x, \"a\"=y\nselect 1");

    assertThat(parsed.substitutions().get(1)).isEqualTo(Map.of("a", "x", "\"a\"", "y"));
  }

  @Test
  void identifiersMayContainDollarAndHash() throws Exception {
    Directives.Parsed parsed = parse("-- manyfold dev2: z$1=z#2\nselect 1");

    assertThat(parsed.substitutions().get(1)).isEqualTo(Map.of("z$1", "z#2"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // unknown backend
        "manyfold staging: a=b",
        "manyfold  : a=b",
        // malformed
        "manyfold",
        "manyfold: a=b",
        "manyfold dev2 a=b",
        "manyfold dev2:",
        "manyfold dev2: a",
        "manyfold dev2: a=b,",
        "manyfold dev2: a=b,,c=d",
        "manyfold dev2: \"a=b",
        // invalid <from>
        "manyfold dev2: 1a=b",
        "manyfold dev2: a.b=c",
        "manyfold dev2: =b",
        "manyfold dev2: \"\"=b",
        "manyfold dev2: [a]=b",
        "manyfold dev2: `a`=b",
        "manyfold dev2: a b=c",
        // invalid <to>
        "manyfold dev2: a=b.c",
        "manyfold dev2: a=1b",
        "manyfold dev2: a='b'",
        "manyfold dev2: a=b c",
        "manyfold dev2: a=b;drop table t",
        "manyfold dev2: a=b=c",
        "manyfold dev2: a=\"b\"\"",
        "manyfold dev2: a=[b]",
        // the same <from> twice for one backend
        "manyfold dev2: a=b, a=c",
        "manyfold dev2: a=b, A=c",
        "manyfold dev2: \"A\"=b, \"A\"=c",
      })
  void aBadDirectiveIsRefusedWithSqlState42000AndQuotesTheDirective(String directive) {
    for (String sql :
        List.of("-- " + directive + "\nselect 1", "/*  " + directive + "\n */ select 1")) {
      assertThatThrownBy(() -> parse(sql))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Invalid manyfold directive '")
          .hasMessageContaining("'" + directive + "'")
          .extracting(e -> ((ManyfoldException) e).getSQLState())
          .isEqualTo("42000");
    }
  }

  @Test
  void theSameSourceInTwoCommentsIsRefusedAndQuotesTheSecondDirective() {
    assertThatThrownBy(() -> parse("-- manyfold dev2: a=b\n-- manyfold dev2: A=c\nselect 1"))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("'manyfold dev2: A=c'")
        .hasMessageContaining("more than once")
        .extracting(e -> ((ManyfoldException) e).getSQLState())
        .isEqualTo("42000");
  }

  @Test
  void aDirectiveThatIsNotClosedOrIsNotACommentToEveryDatabaseIsRefused() {
    assertThatThrownBy(() -> parse("/* manyfold dev2: a=b\nselect 1"))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("'manyfold dev2: a=b\nselect 1'")
        .hasMessageContaining("not closed");
    assertThatThrownBy(() -> parse("--manyfold dev2: a=b\nselect 1"))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("'manyfold dev2: a=b'")
        .hasMessageContaining("followed by a space")
        .extracting(e -> ((ManyfoldException) e).getSQLState())
        .isEqualTo("42000");
  }

  @Test
  void theUnknownBackendMessageListsTheKnownOnes() {
    assertThatThrownBy(() -> parse("-- manyfold staging: a=b\nselect 1"))
        .hasMessageContaining("unknown backend 'staging'")
        .hasMessageContaining("prod, dev2");
  }

  @Test
  void aBadDirectiveIsNotSilentlyIgnoredEvenWithAnOrdinaryCommentBeforeIt() {
    assertThatThrownBy(() -> parse("-- hello\n/* other */\n-- manyfold nope: a=b\nselect 1"))
        .isInstanceOf(ManyfoldException.class);
  }

  @Test
  void planAppliesEachBackendsSubstitutionsToTheStrippedText() throws Exception {
    BackendSql plan =
        Directives.plan(
            "-- manyfold dev2: zone1_prod=zone1_dev2\nselect * from zone1_prod.orders", NAMES);

    assertThat(plan.sqlFor(0)).isEqualTo("select * from zone1_prod.orders");
    assertThat(plan.sqlFor(1)).isEqualTo("select * from zone1_dev2.orders");
    assertThat(plan.original()).startsWith("-- manyfold");
  }

  @Test
  void planLeavesABackendWithoutDirectivesWithTheStrippedStatement() throws Exception {
    BackendSql plan = Directives.plan("/* manyfold dev2: a=b */select a.x from a.t", NAMES);

    assertThat(plan.sqlFor(0)).isEqualTo("select a.x from a.t");
    assertThat(plan.sqlFor(1)).isEqualTo("select b.x from b.t");
  }

  @Test
  void sentIsNullWhenNoSubstitutionWasAppliedAndOtherwiseHoldsOnlyTheSubstitutedTexts()
      throws Exception {
    assertThat(Directives.plan("select 1", NAMES).sent()).isNull();
    // A directive that is only stripped, or that matches nothing, is not a substitution.
    assertThat(Directives.plan("-- manyfold dev2: zz=yy\nselect * from a.t", NAMES).sent())
        .isNull();

    List<String> sent = Directives.plan("-- manyfold dev2: a=b\nselect * from a.t", NAMES).sent();

    assertThat(sent).containsExactly(null, "select * from b.t");
  }

  @Test
  void planRefusesAStatementItCannotRewriteSafely() {
    assertThatThrownBy(() -> Directives.plan("-- manyfold dev2: a=b\nselect $$x$$ from a.t", NAMES))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("dollar quoting")
        .extracting(e -> ((ManyfoldException) e).getSQLState())
        .isEqualTo("42000");
  }
}
