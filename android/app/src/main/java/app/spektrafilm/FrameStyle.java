// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/** Saved IDs shared with the native border renderer. */
enum FrameStyle {
  NONE("none", "无边框", "无边框"),
  BLACK_135("135_black", "135 齿孔 · 黑色", "135 齿孔"),
  WARM_135("135_warm", "135 齿孔 · 暖色", "135 暖色"),
  SILVER_135("135_bw_silver", "135 银盐黑白", "银盐黑白"),
  SLIDE("slide_mount", "35mm 档案正片夹", "正片夹"),
  MEDIUM_120("120_medium", "120 中画幅", "120 中画幅"),
  SHEET("4x5_sheet", "4×5 大画幅", "4×5 页片"),
  WHITE("clean_white", "白色留边", "白色留边"),
  INSTANT("instant", "即时相纸", "即时相纸");

  /** Single-photo matte. Match film_border.cpp; the light table uses the scene beneath instead. */
  static final int BACKING_COLOR = 0xffdfd8cc;
  private static final FrameStyle[] ALL = values();
  final String id, label, cardLabel;

  FrameStyle(String id, String label, String cardLabel) {
    this.id = id;
    this.label = label;
    this.cardLabel = cardLabel;
  }

  static FrameStyle fromId(String id) {
    for (FrameStyle style : ALL) if (style.id.equals(id)) return style;
    return NONE;
  }

  boolean film() {
    return this != NONE && this != WHITE && this != INSTANT;
  }

  boolean legacyStrip() {
    return this == BLACK_135 || this == WARM_135;
  }

  boolean acetate() {
    return film() && this != SLIDE;
  }

  static Bitmap flatten(Bitmap image) {
    Bitmap flat = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
    flat.setDensity(image.getDensity());
    Canvas canvas = new Canvas(flat);
    canvas.drawColor(BACKING_COLOR);
    canvas.drawBitmap(image, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
    flat.setHasAlpha(false);
    return flat;
  }
}
