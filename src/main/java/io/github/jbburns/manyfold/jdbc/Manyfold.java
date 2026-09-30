package io.github.jbburns.manyfold.jdbc;

/** Constants and version information shared by the driver and its callers. */
public final class Manyfold {

  /** The driver's name as reported through {@code DatabaseMetaData.getDriverName()}. */
  public static final String NAME = "manyfold-jdbc";

  /** Prefix every manyfold URL starts with. */
  public static final String URL_PREFIX = "jdbc:manyfold:";

  /** Separator between backend URLs inside a manyfold URL. */
  public static final String BACKEND_DELIMITER = "||";

  /** Default name of the injected provenance column. */
  public static final String DEFAULT_SOURCE_COLUMN = "source_database";

  private static final String VERSION = readVersion();
  private static final int MAJOR = versionPart(0);
  private static final int MINOR = versionPart(1);

  private Manyfold() {}

  /**
   * The driver version from the jar manifest, or {@code 0.0.0-dev} when running from classes.
   *
   * @return the version string
   */
  public static String version() {
    return VERSION;
  }

  /** The major version number, or 0 when unknown. */
  public static int majorVersion() {
    return MAJOR;
  }

  /** The minor version number, or 0 when unknown. */
  public static int minorVersion() {
    return MINOR;
  }

  private static String readVersion() {
    Package pkg = Manyfold.class.getPackage();
    String version = pkg == null ? null : pkg.getImplementationVersion();
    return version == null || version.isBlank() ? "0.0.0-dev" : version;
  }

  private static int versionPart(int index) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)").matcher(VERSION);
    if (!m.find()) {
      return 0;
    }
    try {
      return Integer.parseInt(m.group(index + 1));
    } catch (NumberFormatException e) {
      return 0; // a digit run too long for an int
    }
  }
}
