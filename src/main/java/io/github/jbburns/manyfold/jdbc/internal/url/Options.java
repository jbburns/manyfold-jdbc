package io.github.jbburns.manyfold.jdbc.internal.url;

import io.github.jbburns.manyfold.jdbc.Manyfold;
import java.util.Objects;

/** Driver-level options: the name of the injected provenance column and read-only mode. */
public final class Options {

  /** Option key for the source column name, in the URL or as {@code manyfold.sourceColumn}. */
  public static final String SOURCE_COLUMN = "sourceColumn";

  /** Option key for read-only mode, in the URL or as {@code manyfold.readOnly}. */
  public static final String READ_ONLY = "readOnly";

  /** The defaults: {@code source_database}, read-only. */
  public static final Options DEFAULTS = new Options(Manyfold.DEFAULT_SOURCE_COLUMN, true);

  private final String sourceColumn;
  private final boolean readOnly;

  /**
   * Creates the options.
   *
   * @param sourceColumn name of the injected provenance column
   * @param readOnly whether statements that can modify data are refused
   */
  public Options(String sourceColumn, boolean readOnly) {
    this.sourceColumn = sourceColumn;
    this.readOnly = readOnly;
  }

  /**
   * The provenance column name.
   *
   * @return name of the injected provenance column
   */
  public String sourceColumn() {
    return sourceColumn;
  }

  /**
   * Whether the driver is read-only.
   *
   * @return true if statements that can modify data are refused
   */
  public boolean readOnly() {
    return readOnly;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Options)) {
      return false;
    }
    Options other = (Options) o;
    return readOnly == other.readOnly && sourceColumn.equals(other.sourceColumn);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourceColumn, readOnly);
  }

  @Override
  public String toString() {
    return "Options[sourceColumn=" + sourceColumn + ", readOnly=" + readOnly + "]";
  }
}
