package io.github.jbburns.manyfold.jdbc;

/** Constants shared by the driver and its callers. */
public final class Manyfold {

  /** Prefix every manyfold URL starts with. */
  public static final String URL_PREFIX = "jdbc:manyfold:";

  /** Separator between backend URLs inside a manyfold URL. */
  public static final String BACKEND_DELIMITER = "||";

  /** Default name of the injected provenance column. */
  public static final String DEFAULT_SOURCE_COLUMN = "source_database";

  private Manyfold() {}
}
