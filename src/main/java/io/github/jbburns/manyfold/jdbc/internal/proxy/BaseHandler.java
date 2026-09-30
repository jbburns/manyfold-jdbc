package io.github.jbburns.manyfold.jdbc.internal.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * Common ground for the dynamic-proxy handlers.
 *
 * <p>Handles {@link Object} methods by identity, answers {@code unwrap} and {@code isWrapperFor}
 * for the proxy itself only (so no backend object ever leaks), and unwraps reflective exceptions so
 * callers see the vendor's own exception.
 */
abstract class BaseHandler implements InvocationHandler {

  private static final Object[] NO_ARGS = new Object[0];

  @Override
  @SuppressWarnings("ReferenceEquality") // equals on a proxy is identity by design
  public final @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args)
      throws Throwable {
    Object[] arguments = args == null ? NO_ARGS : args;
    if (method.getDeclaringClass() == Object.class) {
      return switch (method.getName()) {
        case "equals" -> proxy == arguments[0];
        case "hashCode" -> System.identityHashCode(proxy);
        default -> describe();
      };
    }
    switch (method.getName()) {
      case "unwrap" -> {
        Class<?> iface = (Class<?>) arguments[0];
        if (iface.isInstance(proxy)) {
          return proxy;
        }
        throw new SQLException(describe() + " is not a wrapper for " + iface.getName());
      }
      case "isWrapperFor" -> {
        return ((Class<?>) arguments[0]).isInstance(proxy);
      }
      default -> {
        return dispatch(method, arguments);
      }
    }
  }

  /** Routes one interface method. Exceptions propagate to the caller unchanged. */
  protected abstract @Nullable Object dispatch(Method method, Object[] args) throws Throwable;

  /** A short description for {@code toString()} and messages. */
  protected abstract String describe();

  /** Invokes the interface method on a backend object, rethrowing the backend's own exception. */
  protected static @Nullable Object call(Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
