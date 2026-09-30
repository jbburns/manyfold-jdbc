package io.github.jbburns.manyfold.jdbc.internal.url;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
