// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.*;

/** A generated reference target with texture, gradients and highlight patches. */
final class DemoImage {
  static Bitmap create() {
    int w = 1200, h = 900;
    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(b);
    Paint p = new Paint(3);
    p.setShader(new LinearGradient(0, 0, w, h, 0xffb9ced0, 0xffeee1c2, Shader.TileMode.CLAMP));
    c.drawRect(0, 0, w, h, p);
    p.setShader(null);
    p.setColor(0xffaebbb3);
    c.drawRect(0, 550, w, h, p);
    p.setColor(0xffe0c699);
    c.drawRect(0, 605, w, h, p);
    p.setColor(0xffd5b98a);
    c.drawOval(240, 690, 1020, 850, p);
    p.setShader(
        new LinearGradient(330, 300, 740, 780, 0xfff4eee0, 0xffc5b9a4, Shader.TileMode.CLAMP));
    c.drawRoundRect(365, 385, 720, 780, 28, 28, p);
    p.setShader(null);
    p.setColor(0xfffaf6e9);
    c.drawOval(365, 365, 720, 430, p);
    p.setColor(0xffaba48d);
    c.drawOval(390, 380, 695, 414, p);
    p.setColor(0xff526954);
    p.setStrokeWidth(13);
    c.drawLine(540, 399, 530, 163, p);
    c.drawLine(530, 230, 420, 140, p);
    c.drawLine(533, 290, 660, 180, p);
    p.setColor(0xff486348);
    c.save();
    c.rotate(-35, 442, 175);
    c.drawOval(374, 139, 512, 192, p);
    c.restore();
    p.setColor(0xff718562);
    c.save();
    c.rotate(-35, 615, 225);
    c.drawOval(544, 193, 690, 245, p);
    c.restore();
    p.setColor(0xff526c50);
    c.save();
    c.rotate(22, 524, 154);
    c.drawOval(485, 76, 548, 205, p);
    c.restore();
    p.setShader(
        new RadialGradient(
            865,
            610,
            158,
            new int[] {0xfff8bd55, 0xffd17c2c, 0xff9b4d20},
            new float[] {0, .65f, 1},
            Shader.TileMode.CLAMP));
    c.drawCircle(868, 667, 143, p);
    p.setShader(null);
    p.setColor(0xfff8f5e8);
    p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    p.setTextSize(39);
    c.drawText("STUDY OF LIGHT", 58, 90, p);
    p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    p.setTextSize(19);
    c.drawText("SPEKTRA  /  SYNTHETIC REFERENCE 01", 60, 127, p);
    int[] colors = {0xff211e1c, 0xff77736b, 0xfff6f1df, 0xffbd5e48, 0xffd9af61, 0xff4d7890};
    for (int i = 0; i < colors.length; i++) {
      p.setColor(colors[i]);
      c.drawRect(60 + i * 48, 785, 100 + i * 48, 825, p);
    }
    return b;
  }
}
