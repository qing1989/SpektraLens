// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Latest recipe per photo, one operation at a time, with revocable tokens for delayed writes. */
final class ThumbnailWorkQueue {
  static final class Job {
    final String id, params;
    final long version;

    Job(String id, String params, long version) {
      this.id = id;
      this.params = params;
      this.version = version;
    }
  }

  private final LinkedHashMap<String, Job> pending = new LinkedHashMap<>();
  private final Map<String, Long> versions = new HashMap<>();
  private Job active;
  private long sequence;

  synchronized boolean contains(String id, String params) {
    Job job = pending.get(id);
    if (job == null && active != null && active.id.equals(id)) job = active;
    return job != null && isCurrent(job) && job.params.equals(params);
  }

  synchronized boolean offer(String id, String params) {
    if (contains(id, params)) return false;
    Job job = new Job(id, params, ++sequence);
    versions.put(id, job.version);
    pending.put(id, job);
    return true;
  }

  synchronized Job poll(ToIntFunction<String> priority) {
    if (active != null) return null;
    Job best = null;
    int rank = Integer.MAX_VALUE;
    for (Job job : pending.values()) {
      int value = priority.applyAsInt(job.id);
      if (best == null || value < rank) {
        best = job;
        rank = value;
      }
    }
    if (best != null) pending.remove(best.id);
    return active = best;
  }

  synchronized boolean isCurrent(Job job) {
    return job != null && Long.valueOf(job.version).equals(versions.get(job.id));
  }

  synchronized void finish(Job job) {
    if (active == job) active = null;
  }

  synchronized void retry(Job job) {
    if (isCurrent(job)) pending.put(job.id, job);
    finish(job);
  }

  synchronized void invalidate(String id) {
    pending.remove(id);
    versions.remove(id);
  }

  synchronized void clear() {
    pending.clear();
    versions.clear();
  }

  synchronized int pendingCount() {
    return pending.size();
  }
}
