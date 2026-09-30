package io.github.jbburns.manyfold.jdbc.internal.proxy;

import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;

/** One savepoint per backend, presented as a single savepoint. */
final class ManyfoldSavepoint implements Savepoint {

  private final List<Savepoint> savepoints;

  ManyfoldSavepoint(List<Savepoint> savepoints) {
    this.savepoints = List.copyOf(savepoints);
  }

  List<Savepoint> savepoints() {
    return savepoints;
  }

  @Override
  public int getSavepointId() throws SQLException {
    return savepoints.get(0).getSavepointId();
  }

  @Override
  public String getSavepointName() throws SQLException {
    return savepoints.get(0).getSavepointName();
  }
}
