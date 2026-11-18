// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Makes invalidation and delayed file publication mutually exclusive. */
final class ThumbnailWriteGate {
  private final Map<String, Long> versions = new HashMap<>(), writing = new HashMap<>();
  private long sequence;
  private boolean closed;

  synchronized long begin(String id) {
    long token = ++sequence;
    if (!closed) {
      versions.put(id, token);
      writing.put(id, token);
    }
    return token;
  }

  synchronized void invalidate(String id) {
    versions.remove(id);
    writing.remove(id);
  }

  synchronized boolean isCurrent(String id, long token) {
    return !closed && Long.valueOf(token).equals(versions.get(id));
  }

  synchronized boolean isWriting(String id) {
    return writing.containsKey(id);
  }

  synchronized boolean commit(String id, long token, BooleanSupplier allowed, BooleanSupplier publish) {
    return isCurrent(id, token) && allowed.getAsBoolean() && publish.getAsBoolean();
  }

  synchronized void finish(String id, long token) {
    if (Long.valueOf(token).equals(writing.get(id))) writing.remove(id);
  }

  synchronized void close() {
    closed = true;
    versions.clear();
    writing.clear();
  }
}
