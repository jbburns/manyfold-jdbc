package io.github.jbburns.manyfold.jdbc.internal.url;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RedactTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "jdbc:postgresql://alice:s3cret@host:5432/app | jdbc:postgresql://host:5432/app",
        "jdbc:postgresql://host/app?user=a&password=s3cret | jdbc:postgresql://host/app?user=a&password=***",
        "jdbc:sqlserver://host;databaseName=x;password=s3cret;encrypt=true | jdbc:sqlserver://host;databaseName=x;password=***;encrypt=true",
        "jdbc:mysql://host/db?PASSWORD=s3cret | jdbc:mysql://host/db?PASSWORD=***",
        "jdbc:oracle:thin:@host:1521/svc | jdbc:oracle:thin:@host:1521/svc",
        "jdbc:h2:mem:one;DB_CLOSE_DELAY=-1 | jdbc:h2:mem:one;DB_CLOSE_DELAY=-1",
        "jdbc:oracle:thin:scott/tiger@//h:1521/svc | jdbc:oracle:thin:@//h:1521/svc",
        "jdbc:oracle:thin:scott/tiger@h:1521:orcl | jdbc:oracle:thin:@h:1521:orcl",
        "jdbc:postgresql://u:p@ss@h/db | jdbc:postgresql://h/db",
        "jdbc:postgresql://u:p@ss@h/db?a=b@c | jdbc:postgresql://h/db?a=b@c",
        "jdbc:postgresql://h/db?sslpassword=s3cret | jdbc:postgresql://h/db?sslpassword=***",
        "jdbc:sqlserver://h;trustStorePassword=s3cret;encrypt=true | jdbc:sqlserver://h;trustStorePassword=***;encrypt=true",
        "jdbc:x://h/db?a=b&keyStorePassword=x | jdbc:x://h/db?a=b&keyStorePassword=***",
        "jdbc:x://h;secret=x | jdbc:x://h;secret=***",
        "jdbc:x://h;token=x;a=b | jdbc:x://h;token=***;a=b",
        "jdbc:x://h;accessToken=x | jdbc:x://h;accessToken=***",
        "jdbc:x://h;password={a;b};encrypt=true | jdbc:x://h;password=***;encrypt=true",
        "jdbc:x://h;PWD={a;b} | jdbc:x://h;PWD=***",
      })
  void stripsCredentials(String input, String expected) {
    assertThat(Redact.url(input)).isEqualTo(expected);
  }

  @Test
  void passwordRedactionStopsAtTheBackendDelimiter() {
    String url = "jdbc:manyfold:a=jdbc:x://h/db?password=s3cret || b=jdbc:x://h/db2";

    assertThat(Redact.url(url))
        .isEqualTo("jdbc:manyfold:a=jdbc:x://h/db?password=*** || b=jdbc:x://h/db2");
  }

  @Test
  void nullIsRenderedNotThrown() {
    assertThat(Redact.url(null)).isEqualTo("null");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:oracle:thin:scott/s3cret@//h:1521/svc",
        "jdbc:postgresql://u:s3cret@x@h/db",
        "jdbc:postgresql://h/db?sslpassword=s3cret",
        "jdbc:sqlserver://h;trustStorePassword=s3cret",
        "jdbc:x://h;password={s3cret;more}",
        "jdbc:x://h;token=s3cret",
        "jdbc:x://h;secret=s3cret",
      })
  void defaultNameAndToStringsNeverContainTheSecret(String url) {
    assertThat(BackendSpec.defaultName(url)).doesNotContain("s3cret");
    assertThat(new BackendSpec("n", url).toString()).doesNotContain("s3cret").contains("n");
  }

  @Test
  void backendSpecToStringIsRedacted() {
    assertThat(new BackendSpec("prod", "jdbc:postgresql://u:s3cret@h/db?password=s3cret"))
        .hasToString("BackendSpec[name=prod, url=jdbc:postgresql://h/db?password=***]");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "jdbc:postgresql://u:p@host:5432/app?ssl=true | postgresql://host:5432/app",
        "jdbc:h2:mem:one;DB_CLOSE_DELAY=-1 | h2:mem:one",
        "jdbc:sqlserver://host;databaseName=x | sqlserver://host",
        "JDBC:MySQL://host/db | MySQL://host/db",
        "jdbc:sqlite::memory: | sqlite::memory:",
      })
  void defaultNameIsTheDatabaseLocatorWithoutSecretsOrParameters(String url, String expected) {
    assertThat(BackendSpec.defaultName(url)).isEqualTo(expected);
  }
}
