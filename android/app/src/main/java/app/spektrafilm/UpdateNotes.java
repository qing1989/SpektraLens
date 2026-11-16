// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain-text release notes with explicit line breaks and optional Markdown list/headings. */
final class UpdateNotes {
  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
  private static final Pattern ITEM = Pattern.compile("^([-*•]|[0-9]+[.)、])\\s+(.+)$");

  static final class Line {
    final String marker, text;
    final boolean heading;

    Line(String marker, String text, boolean heading) {
      this.marker = marker;
      this.text = text;
      this.heading = heading;
    }
  }

  static List<Line> parse(String notes) {
    List<Line> result = new ArrayList<>();
    if (notes != null) {
      for (String raw : notes.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
        String line = raw.trim();
        if (line.isEmpty()) continue;
        Matcher heading = HEADING.matcher(line), item = ITEM.matcher(line);
        if (heading.matches()) result.add(new Line("", heading.group(1), true));
        else if (item.matches()) {
          String marker = item.group(1);
          if (marker.equals("-") || marker.equals("*")) marker = "•";
          result.add(new Line(marker, item.group(2), false));
        } else result.add(new Line("", line, false));
      }
    }
    if (result.isEmpty()) result.add(new Line("", "此版本未提供更新说明。", false));
    return result;
  }
}
