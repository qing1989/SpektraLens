// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.List;

final class UpdateNotesTest {
  private static int checks;

  private static void check(boolean result) {
    checks++;
    if (!result) throw new AssertionError("release notes check " + checks);
  }

  static void run() {
    List<UpdateNotes.Line> lines =
        UpdateNotes.parse("## 本次更新\r\n\r\n- 第一项。\r\n- 第二项，包含 v0.4.3。\r\n");
    check(lines.size() == 3);
    check(lines.get(0).heading && lines.get(0).text.equals("本次更新"));
    check(lines.get(1).marker.equals("•") && lines.get(1).text.equals("第一项。"));
    check(lines.get(2).text.equals("第二项，包含 v0.4.3。"));
    lines = UpdateNotes.parse("1. 修复闪退\n2) 优化速度\n3、 保留参数\n* 支持列表\n• 支持圆点");
    check(lines.size() == 5);
    check(lines.get(0).marker.equals("1.") && lines.get(1).marker.equals("2)"));
    check(lines.get(2).marker.equals("3、"));
    check(lines.get(3).marker.equals("•") && lines.get(4).marker.equals("•"));
    lines = UpdateNotes.parse("修复甲。优化乙；保留丙。");
    check(lines.size() == 1); // Never guess item boundaries from Chinese punctuation.
    check(lines.get(0).text.equals("修复甲。优化乙；保留丙。"));
    lines = UpdateNotes.parse("第一项\n第二项\r第三项");
    check(lines.size() == 3 && lines.get(2).text.equals("第三项"));
    check(UpdateNotes.parse(null).get(0).text.equals("此版本未提供更新说明。"));
    check(UpdateNotes.parse(" \n\r\n ").size() == 1);
    lines = UpdateNotes.parse("- **保留强调**与 `标识符`\nhttps://example.org/v0.4.3");
    check(lines.get(0).text.equals("**保留强调**与 `标识符`"));
    check(lines.get(1).text.equals("https://example.org/v0.4.3"));
    check(UpdateNotes.parse("- 条目\n".repeat(1000)).size() == 1000);
    System.out.println("UpdateNotesTest: " + checks + " checks passed");
  }
}
