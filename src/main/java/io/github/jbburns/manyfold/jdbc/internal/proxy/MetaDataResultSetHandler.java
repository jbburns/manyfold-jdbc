package io.github.jbburns.manyfold.jdbc.internal.proxy;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ResultSet} returned by {@link java.sql.DatabaseMetaData}, wrapped so that it does not
 * hand out the vendor's statement (and through it the vendor's connection, which would bypass the
 * read-only guard). Everything else goes to the vendor result set unchanged.
 */
final class MetaDataResultSetHandler extends BaseHandler {

  private final ResultSet delegate;

  MetaDataResultSetHandler(ResultSet delegate) {
    this.delegate = delegate;
  }

  @Override
  protected @Nullable Object dispatch(Method method, Object[] args) throws Throwable {
    if (method.getName().equals("getStatement")) {
      return null;
    }
    return call(method, delegate, args);
  }

  @Override
  protected String describe() {
    return "ManyfoldMetaDataResultSet";
  }
}
