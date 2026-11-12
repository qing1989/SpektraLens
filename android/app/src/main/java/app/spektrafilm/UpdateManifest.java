// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.io.*;
import java.net.URI;
import java.security.*;
import java.util.*;

/** Update policy and bounded JSON parsing, independent of Android. */
public final class UpdateManifest {
  public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
  public final int versionCode, minSdk;
  public final String versionName, sha256, releaseNotes;
  public final long sizeBytes;
  public final List<String> urls;

  private UpdateManifest(Map<?, ?> m) {
    versionCode = integer(m.get("versionCode"), 1, Integer.MAX_VALUE);
    versionName = required(m.get("versionName"));
    sha256 = required(m.get("sha256"));
    if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256");
    sizeBytes = m.containsKey("sizeBytes") ? number(m.get("sizeBytes"), 1, MAX_APK_BYTES) : MAX_APK_BYTES;
    minSdk = m.containsKey("minSdk") ? integer(m.get("minSdk"), 1, Integer.MAX_VALUE) : 1;
    releaseNotes = m.get("releaseNotes") instanceof String ? (String) m.get("releaseNotes") : "";
    List<String> candidates = new ArrayList<>();
    candidates.add(https(required(m.get("apkUrl"))));
    if (m.get("apkUrlMirrors") instanceof List<?>) {
      for (Object url : (List<?>) m.get("apkUrlMirrors")) {
        try { candidates.add(https(required(url))); } catch (IllegalArgumentException ignored) { }
        if (candidates.size() >= 9) break;
      }
    }
    urls = Collections.unmodifiableList(candidates);
  }

  public static UpdateManifest parse(String json) {
    if (json.length() > 65536) throw new IllegalArgumentException("manifest too large");
    Json parser = new Json(json);
    Object value = parser.value(0);
    parser.space();
    if (!(value instanceof Map) || parser.pos != json.length()) throw new IllegalArgumentException("JSON object required");
    return new UpdateManifest((Map<?, ?>) value);
  }

  public boolean isNewerThan(long installedCode) { return versionCode > installedCode; }
  public static String https(String url) {
    try {
      URI uri = new URI(url);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getFragment() != null) throw new Exception();
      return url;
    } catch (Exception e) { throw new IllegalArgumentException("HTTPS URL required"); }
  }
  public static boolean verify(File file, String expected) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new FileInputStream(file)) {
        byte[] buffer = new byte[32768];
        int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      StringBuilder hex = new StringBuilder();
      for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
      return hex.toString().equals(expected);
    } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  private static String required(Object v) {
    if (!(v instanceof String) || ((String) v).trim().isEmpty()) throw new IllegalArgumentException("string required");
    return (String) v;
  }
  private static long number(Object v, long min, long max) {
    if (!(v instanceof Long) || (Long) v < min || (Long) v > max) throw new IllegalArgumentException("integer out of range");
    return (Long) v;
  }
  private static int integer(Object v, int min, int max) { return (int) number(v, min, max); }

  /** Small strict JSON reader: unknown fields are consumed, never interpreted as policy. */
  private static final class Json {
    final String text; int pos;
    Json(String text) { this.text = text; }
    void space() { while (pos < text.length() && " \r\n\t".indexOf(text.charAt(pos)) >= 0) pos++; }
    char take() { if (pos >= text.length()) throw new IllegalArgumentException("truncated JSON"); return text.charAt(pos++); }
    void expect(char c) { space(); if (take() != c) throw new IllegalArgumentException("invalid JSON"); }
    Object value(int depth) {
      if (depth > 24) throw new IllegalArgumentException("JSON nesting");
      space(); char c = take();
      if (c == '"') {
        StringBuilder s = new StringBuilder();
        while ((c = take()) != '"') {
          if (c < 32) throw new IllegalArgumentException("control character");
          if (c == '\\') {
            c = take();
            switch (c) {
              case '"': case '\\': case '/': break;
              case 'b': c = '\b'; break; case 'f': c = '\f'; break;
              case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
              case 'u':
                if (pos + 4 > text.length()) throw new IllegalArgumentException("unicode");
                c = (char) Integer.parseInt(text.substring(pos, pos + 4), 16); pos += 4; break;
              default: throw new IllegalArgumentException("escape");
            }
          }
          s.append(c);
        }
        return s.toString();
      }
      if (c == '{' || c == '[') {
        boolean object = c == '{'; char end = object ? '}' : ']';
        Map<String, Object> map = new LinkedHashMap<>(); List<Object> list = new ArrayList<>();
        space(); if (pos < text.length() && text.charAt(pos) == end) { pos++; return object ? map : list; }
        while (true) {
          if (object) {
            Object key = value(depth + 1);
            if (!(key instanceof String) || map.containsKey(key)) throw new IllegalArgumentException("object key");
            expect(':'); map.put((String) key, value(depth + 1));
          } else list.add(value(depth + 1));
          space(); char separator = take(); if (separator == end) break;
          if (separator != ',') throw new IllegalArgumentException("separator");
        }
        return object ? map : list;
      }
      int start = pos - 1;
      while (pos < text.length() && ",]} \r\n\t".indexOf(text.charAt(pos)) < 0) pos++;
      String token = text.substring(start, pos);
      if (token.equals("null")) return null;
      if (token.equals("true")) return Boolean.TRUE;
      if (token.equals("false")) return Boolean.FALSE;
      if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("number");
      if (token.contains(".") || token.contains("e") || token.contains("E")) return Double.valueOf(token);
      return Long.valueOf(token);
    }
  }
}
