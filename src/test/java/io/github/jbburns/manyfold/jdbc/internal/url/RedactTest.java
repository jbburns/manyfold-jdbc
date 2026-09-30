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
        "jdbc:db2://h:50000/db:password=s3cret; | jdbc:db2://h:50000/db:password=***;",
        "jdbc:db2://h:50000/db:user=a;password=s3cret;sslConnection=true; | jdbc:db2://h:50000/db:user=a;password=***;sslConnection=true;",
        "jdbc:x://h;apikey=s3cret | jdbc:x://h;apikey=***",
        "jdbc:x://h?private_key_base64=s3cret&a=b | jdbc:x://h?private_key_base64=***&a=b",
        "jdbc:x://h?sslkey=/etc/s3cret.pem | jdbc:x://h?sslkey=***",
        "jdbc:x://h;Credentials=s3cret | jdbc:x://h;Credentials=***",
        "jdbc:x://h;authentication=s3cret;a=b | jdbc:x://h;authentication=***;a=b",
        "jdbc:x://h;password=my secret;a=b | jdbc:x://h;password=***;a=b",
        "jdbc:x://h?password=my secret&a=b | jdbc:x://h?password=***&a=b",
        "jdbc:x://h;password=my secret | jdbc:x://h;password=***",
        "jdbc:x://h;password={my ;secret};a=b | jdbc:x://h;password=***;a=b",
        "jdbc:x://h;password={unterminated;s3cret | jdbc:x://h;password=***",
        "jdbc:x://u:s3cret?x;y@h/db | jdbc:x://h/db",
        "jdbc:x://u:pa?ss;w@rd@h:1/db?a=b | jdbc:x://h:1/db?a=b",
        "jdbc:sqlserver://h;databaseName=x;password=p@ss;a=b | jdbc:sqlserver://h;databaseName=x;password=***;a=b",
      })
  void stripsCredentials(String input, String expected) {
    assertThat(Redact.url(input)).isEqualTo(expected);
  }

  @Test
  void anUnbracedValueMayContainSpacesButStopsAtTheBackendDelimiter() {
    String url = "jdbc:manyfold:a=jdbc:x://h/db?password=my secret || b=jdbc:x://h/db2";

    assertThat(Redact.url(url))
        .isEqualTo("jdbc:manyfold:a=jdbc:x://h/db?password=*** || b=jdbc:x://h/db2")
        .doesNotContain("secret");
    assertThat(Redact.url("a=jdbc:x://h;pwd=my secret|b=jdbc:x://h"))
        .isEqualTo("a=jdbc:x://h;pwd=***|b=jdbc:x://h");
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
        "jdbc:db2://h:50000/db:password=s3cret;",
        "jdbc:db2://h:50000/db:user=a;password=s3cret",
        "jdbc:x://h;apikey=s3cret",
        "jdbc:x://h?private_key_base64=s3cret",
        "jdbc:x://h?sslkey=s3cret",
        "jdbc:x://h;credentials=s3cret",
        "jdbc:x://h;authToken=s3cret",
        "jdbc:x://h;password=my s3cret phrase",
        "jdbc:x://h;password=my s3cret phrase;a=b",
        "jdbc:x://u:s3cret?x;y@h/db",
        "jdbc:x://u:x?s3cret;y@h/db",
        "jdbc:x://h;password={unterminated;s3cret",
        "jdbc:sqlserver://h;databaseName=x;password=p@s3cret",
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
