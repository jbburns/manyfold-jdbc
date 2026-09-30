package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManyfoldExceptionTest {

  private static final int EXECUTE_FAILED = Statement.EXECUTE_FAILED;

  private static final class VendorSyntaxError extends SQLSyntaxErrorException {
    private static final long serialVersionUID = 1L;

    VendorSyntaxError() {
      super("vendor", "42S02", 42102);
    }
  }

  private static final List<SQLException> STANDARD_SUBTYPES =
      List.of(
          new SQLFeatureNotSupportedException("m", "0A000", 1),
          new SQLTimeoutException("m", "HYT00", 2),
          new SQLIntegrityConstraintViolationException("m", "23505", 3),
          new SQLSyntaxErrorException("m", "42000", 4),
          new SQLDataException("m", "22001", 5),
          new SQLTransientConnectionException("m", "08S01", 6),
          new SQLNonTransientConnectionException("m", "08001", 7),
          new SQLInvalidAuthorizationSpecException("m", "28000", 8),
          new SQLTransactionRollbackException("m", "40001", 9),
          new SQLRecoverableException("m", "08006", 10));

  @Test
  void everyStandardSubtypeSurvivesWithStateCodeAndCause() {
    for (SQLException vendor : STANDARD_SUBTYPES) {
      SQLException wrapped = ManyfoldException.backendFailed("dev", vendor);

      assertThat((Throwable) wrapped)
          .as(vendor.getClass().getSimpleName())
          .isExactlyInstanceOf(vendor.getClass());
      assertThat(wrapped.getMessage()).isEqualTo("Backend 'dev' failed: m");
      assertThat(wrapped.getSQLState()).isEqualTo(vendor.getSQLState());
      assertThat(wrapped.getErrorCode()).isEqualTo(vendor.getErrorCode());
      assertThat(wrapped.getCause()).isSameAs(vendor);
    }
  }

  @Test
  void aSubclassOfAStandardSubtypeIsReportedAsThatStandardSubtype() {
    VendorSyntaxError vendor = new VendorSyntaxError();

    SQLException wrapped = ManyfoldException.backendFailed("prod", vendor);

    assertThat((Throwable) wrapped).isExactlyInstanceOf(SQLSyntaxErrorException.class);
    assertThat(wrapped.getCause()).isSameAs(vendor);
  }

  @Test
  void batchUpdateExceptionKeepsItsUpdateCounts() {
    BatchUpdateException vendor =
        new BatchUpdateException("batch", "23000", 9, new int[] {1, 2, EXECUTE_FAILED});

    SQLException wrapped = ManyfoldException.backendFailed("dev", vendor);

    assertThat((Throwable) wrapped).isExactlyInstanceOf(BatchUpdateException.class);
    BatchUpdateException batch = (BatchUpdateException) wrapped;
    assertThat(batch.getUpdateCounts()).containsExactly(1, 2, EXECUTE_FAILED);
    assertThat(batch.getLargeUpdateCounts()).containsExactly(1L, 2L, EXECUTE_FAILED);
    assertThat(batch.getSQLState()).isEqualTo("23000");
    assertThat(batch.getErrorCode()).isEqualTo(9);
    assertThat(batch.getCause()).isSameAs(vendor);
  }

  @Test
  void largeBatchCountsBeyondIntRangeAreCopied() {
    BatchUpdateException vendor =
        new BatchUpdateException("batch", "23000", 9, new long[] {6_000_000_000L}, null);

    BatchUpdateException wrapped =
        (BatchUpdateException) ManyfoldException.backendFailed("dev", vendor);

    assertThat(wrapped.getLargeUpdateCounts()).containsExactly(6_000_000_000L);
  }

  @Test
  void aNullMessageUsesTheCauseClassName() {
    SQLException wrapped = ManyfoldException.backendFailed("dev", new SQLException((String) null));
    SQLException unchecked = ManyfoldException.backendFailed("dev", new IllegalStateException());

    assertThat(wrapped.getMessage())
        .isEqualTo("Backend 'dev' failed: " + SQLException.class.getName());
    assertThat(unchecked.getMessage())
        .isEqualTo("Backend 'dev' failed: " + IllegalStateException.class.getName());
  }

  @Test
  void otherSqlExceptionsAndUncheckedExceptionsBecomeManyfoldExceptions() {
    SQLException plain = new SQLException("plain", "42000", 11);
    IllegalStateException unchecked = new IllegalStateException("bug");

    SQLException fromPlain = ManyfoldException.backendFailed("a", plain);
    SQLException fromUnchecked = ManyfoldException.backendFailed("b", unchecked);

    assertThat((Throwable) fromPlain).isExactlyInstanceOf(ManyfoldException.class);
    assertThat(fromPlain.getSQLState()).isEqualTo("42000");
    assertThat(fromPlain.getErrorCode()).isEqualTo(11);
    assertThat((Throwable) fromUnchecked).isExactlyInstanceOf(ManyfoldException.class);
    assertThat(fromUnchecked.getSQLState()).isNull();
    assertThat(fromUnchecked.getCause()).isSameAs(unchecked);
  }

  @Test
  void theVendorsNextExceptionChainIsCopiedWithoutBeingMutated() {
    SQLSyntaxErrorException vendor = new SQLSyntaxErrorException("first", "42000", 1);
    SQLException second = new SQLException("second", "23000", 2);
    SQLException third = new SQLException("third", "HY000", 3);
    vendor.setNextException(second);
    second.setNextException(third);

    SQLException wrapped = ManyfoldException.backendFailed("dev", vendor);
    wrapped.setNextException(ManyfoldException.backendFailed("prod", new SQLException("later")));

    SQLException next = wrapped.getNextException();
    assertThat((Throwable) next).hasMessage("second");
    assertThat(next.getSQLState()).isEqualTo("23000");
    assertThat(next.getErrorCode()).isEqualTo(2);
    SQLException afterNext = next.getNextException();
    assertThat((Throwable) afterNext).hasMessage("third");
    assertThat((Throwable) afterNext.getNextException()).hasMessage("Backend 'prod' failed: later");
    // The vendor's own chain is untouched.
    assertThat((Object) third.getNextException()).isNull();
  }

  @Test
  void aVendorMessageThatEchoesTheConnectionStringDoesNotLeakThePassword() {
    SQLException vendor =
        new SQLException(
            "cannot connect to jdbc:x://h/db?user=a&password=s3cret&ssl=true", "08001");
    IllegalStateException unchecked =
        new IllegalStateException("bad url jdbc:postgresql://alice:s3cret@h/db");

    SQLException wrapped = ManyfoldException.backendFailed("prod", vendor);
    SQLException wrappedUnchecked = ManyfoldException.backendFailed("prod", unchecked);

    assertThat(wrapped.getMessage())
        .isEqualTo(
            "Backend 'prod' failed: cannot connect to jdbc:x://h/db?user=a&password=***&ssl=true");
    assertThat(wrappedUnchecked.getMessage()).doesNotContain("s3cret");
    assertThat(wrapped.getSQLState()).isEqualTo("08001");
    assertThat(wrapped.getCause()).isSameAs(vendor);
  }
}
