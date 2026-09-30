package io.github.jbburns.manyfold.jdbc.internal.url;

import io.github.jbburns.manyfold.jdbc.Manyfold;

/**
 * Driver-level options.
 *
 * @param sourceColumn name of the injected provenance column
 * @param readOnly whether statements that can modify data are refused
 */
public record Options(String sourceColumn, boolean readOnly) {

  /** Option key for the source column name, in the URL or as {@code manyfold.sourceColumn}. */
  public static final String SOURCE_COLUMN = "sourceColumn";

  /** Option key for read-only mode, in the URL or as {@code manyfold.readOnly}. */
  public static final String READ_ONLY = "readOnly";

  /** The defaults: {@code source_database}, read-only. */
  public static final Options DEFAULTS = new Options(Manyfold.DEFAULT_SOURCE_COLUMN, true);
}
