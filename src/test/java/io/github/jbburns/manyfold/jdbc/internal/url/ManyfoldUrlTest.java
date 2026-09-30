package io.github.jbburns.manyfold.jdbc.internal.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManyfoldUrlTest {

  private static final String PROD = "jdbc:postgresql://prod-host:5432/app";
  private static final String DEV = "jdbc:postgresql://dev-host:5432/app";

  private static ManyfoldUrl parse(String url) throws ManyfoldException {
    return ManyfoldUrl.parse(url, new Properties());
  }

  @Test
  void namedBackendsInUrlOrder() throws Exception {
    ManyfoldUrl url = parse("jdbc:manyfold:prod=" + PROD + " || dev=" + DEV);

    assertThat(url.backends())
        .containsExactly(new BackendSpec("prod", PROD), new BackendSpec("dev", DEV));
    assertThat(url.options()).isEqualTo(Options.DEFAULTS);
  }

  @Test
  void unnamedBackendsGetNamesDerivedFromTheirUrl() throws Exception {
    ManyfoldUrl url = parse("jdbc:manyfold:" + PROD + "||" + DEV);

    assertThat(url.backends())
        .extracting(BackendSpec::name)
        .containsExactly("postgresql://prod-host:5432/app", "postgresql://dev-host:5432/app");
  }

  @Test
  void namedAndUnnamedBackendsMix() throws Exception {
    ManyfoldUrl url = parse("jdbc:manyfold:" + PROD + " || dev=" + DEV);

    assertThat(url.backends())
        .extracting(BackendSpec::name)
        .containsExactly("postgresql://prod-host:5432/app", "dev");
  }

  @Test
  void singleBackendIsAllowed() throws Exception {
    assertThat(parse("jdbc:manyfold:" + PROD).backends()).hasSize(1);
  }

  @Test
  void backendUrlsKeepTheirOwnDelimitersAndParameters() throws Exception {
    String sqlServer = "jdbc:sqlserver://host;databaseName=x;encrypt=true";
    String mysql = "jdbc:mysql://host/db?a=1&b=2";
    String h2 = "jdbc:h2:mem:one;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    ManyfoldUrl url = parse("jdbc:manyfold:a=" + sqlServer + " || b=" + mysql + " || " + h2);

    assertThat(url.backends()).extracting(BackendSpec::url).containsExactly(sqlServer, mysql, h2);
  }

  @Test
  void optionsPrecedeTheFirstBackend() throws Exception {
    ManyfoldUrl url =
        parse("jdbc:manyfold:sourceColumn=origin;readOnly=false;prod=" + PROD + " || " + DEV);

    assertThat(url.options()).isEqualTo(new Options("origin", false));
    assertThat(url.backends()).extracting(BackendSpec::name).first().isEqualTo("prod");
  }

  @Test
  void optionKeysAreCaseInsensitive() throws Exception {
    ManyfoldUrl url = parse("jdbc:manyfold:READONLY=FALSE;SourceColumn=src;" + PROD);

    assertThat(url.options()).isEqualTo(new Options("src", false));
  }

  @Test
  void optionsFromPropertiesApplyWhenUrlHasNone() throws Exception {
    Properties props = new Properties();
    props.setProperty("manyfold.readOnly", "false");
    props.setProperty("manyfold.sourceColumn", "origin");

    ManyfoldUrl url = ManyfoldUrl.parse("jdbc:manyfold:" + PROD, props);

    assertThat(url.options()).isEqualTo(new Options("origin", false));
  }

  @Test
  void urlOptionsWinOverProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty("manyfold.readOnly", "false");

    ManyfoldUrl url = ManyfoldUrl.parse("jdbc:manyfold:readOnly=true;" + PROD, props);

    assertThat(url.options().readOnly()).isTrue();
  }

  @Test
  void prefixIsCaseInsensitive() throws Exception {
    assertThat(parse("JDBC:Manyfold:" + PROD).backends()).hasSize(1);
    assertThat(ManyfoldUrl.accepts("JDBC:MANYFOLD:x")).isTrue();
    assertThat(ManyfoldUrl.accepts("jdbc:postgresql://x")).isFalse();
    assertThat(ManyfoldUrl.accepts(null)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:postgresql://x",
        "jdbc:manyfold:",
        "jdbc:manyfold:   ",
        "jdbc:manyfold:" + PROD + " || ",
        "jdbc:manyfold:" + PROD + " || || " + DEV,
        "jdbc:manyfold:prod=" + PROD + " || prod=" + DEV,
        "jdbc:manyfold:prod=" + PROD + " || PROD=" + DEV,
        "jdbc:manyfold:bogus=1;" + PROD,
        "jdbc:manyfold:readOnly=maybe;" + PROD,
        "jdbc:manyfold:sourceColumn=;" + PROD,
        "jdbc:manyfold:readOnly=true" + PROD,
        "jdbc:manyfold:=" + PROD,
        "jdbc:manyfold:not-a-url",
        "jdbc:manyfold:" + PROD + " || readOnly=false;" + DEV,
      })
  void malformedUrlsAreRejected(String url) {
    assertThatThrownBy(() -> parse(url))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageStartingWith("Invalid manyfold URL")
        .extracting(e -> ((ManyfoldException) e).getSQLState())
        .isEqualTo(ManyfoldException.STATE_CONNECTION_FAILURE);
  }

  @Test
  void errorMessagesNeverContainCredentials() {
    String url = "jdbc:manyfold:bogus=1;jdbc:postgresql://alice:s3cret@host/app?password=s3cret";

    assertThatThrownBy(() -> parse(url)).hasMessageNotContaining("s3cret");
  }

  @Test
  void redactedFormDropsCredentials() throws Exception {
    ManyfoldUrl url = parse("jdbc:manyfold:jdbc:postgresql://alice:s3cret@host/app");

    assertThat(url.redacted()).doesNotContain("s3cret").contains("postgresql://host/app");
    assertThat(url.toString()).isEqualTo(url.redacted());
  }

  @Test
  void backendPropertiesPassThroughEverythingExceptManyfoldKeys() throws Exception {
    Properties props = new Properties();
    props.setProperty("user", "shared");
    props.setProperty("password", "shared-pw");
    props.setProperty("ssl", "true");
    props.setProperty("manyfold.readOnly", "false");
    props.setProperty("manyfold.dev.user", "dev-user");
    props.setProperty("manyfold.dev.password", "dev-pw");
    props.setProperty("manyfold.dev.driver", "org.example.Driver");
    ManyfoldUrl url = ManyfoldUrl.parse("jdbc:manyfold:prod=" + PROD + " || dev=" + DEV, props);

    Properties prod = url.backendProperties(url.backends().get(0), props);
    Properties dev = url.backendProperties(url.backends().get(1), props);

    assertThat(prod)
        .containsOnly(
            entry("user", "shared"), entry("password", "shared-pw"), entry("ssl", "true"));
    assertThat(dev)
        .containsOnly(entry("user", "dev-user"), entry("password", "dev-pw"), entry("ssl", "true"));
    assertThat(ManyfoldUrl.backendProperty(url.backends().get(1), props, "driver"))
        .isEqualTo("org.example.Driver");
    assertThat(ManyfoldUrl.backendProperty(url.backends().get(0), props, "driver")).isNull();
  }

  @Test
  void backendPropertyNamesMatchCaseInsensitively() throws Exception {
    Properties props = new Properties();
    props.setProperty("manyfold.Prod.User", "u");
    ManyfoldUrl url = ManyfoldUrl.parse("jdbc:manyfold:prod=" + PROD, props);

    assertThat(url.backendProperties(url.backends().get(0), props))
        .containsOnly(entry("user", "u"));
  }

  private static java.util.Map.Entry<Object, Object> entry(String k, String v) {
    return java.util.Map.entry(k, v);
  }
}
