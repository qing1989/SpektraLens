// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FilmStockPolicyTest {
  private static int checks;
  private static final Map<String, Double> DEFAULTS =
      Map.of(
          "kodak_tri_x_400", 1.70,
          "kodak_tmax_100", .74,
          "fujifilm_neopan_acros_100_ii", .64,
          "ilford_hp5_plus_400", 1.49);

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static void close(double expected, double actual, String message) {
    check(Math.abs(expected - actual) < 1e-9, message + ": " + expected + " != " + actual);
  }

  public static void main(String[] args) throws Exception {
    for (Map.Entry<String, Double> entry : DEFAULTS.entrySet()) {
      String film = entry.getKey();
      check(FilmStockPolicy.monochrome(film), film + " classification");
      check(FilmStockPolicy.canPrint(film, FilmStockPolicy.SILVER_PAPER), film + " silver pair");
      check(!FilmStockPolicy.canPrint(film, FilmStockPolicy.COLOR_PAPER), film + " refuses color");
      check(
          FilmStockPolicy.SILVER_PAPER.equals(
              FilmStockPolicy.paperFor(film, "", "kodak_2383")),
          film + " repairs stale paper");
      close(entry.getValue(), FilmStockPolicy.grainAmount(film, 1), film + " upstream default");
      close(0, FilmStockPolicy.grainAmount(film, 0), film + " grain off");
      double previous = 0;
      for (int step = 1; step <= 40; step++) {
        double amount = FilmStockPolicy.grainAmount(film, step * .05);
        check(amount > previous, film + " every slider step changes grain");
        check(amount <= 2, film + " engine limit");
        previous = amount;
      }
      Map<String, Object> wire =
          FilmStockPolicy.renderOverrides(film, "", "kodak_2383", 1);
      close(68, (Double) wire.get("y_filter_neutral"), film + " grade 2 yellow");
      close(0, (Double) wire.get("m_filter_neutral"), film + " grade 2 magenta");
      close(0, (Double) wire.get("c_filter_neutral"), film + " grade 2 cyan");
      close(0, (Double) wire.get("m_filter_shift"), film + " ignores color shifts");
      close(0, (Double) wire.get("y_filter_shift"), film + " ignores color shifts");
      check("".equals(wire.get("camera_filter")), film + " defaults to no lens filter");
      close(1, (Double) wire.get("print_exposure"), film + " grade 2 print exposure");
      check(Boolean.FALSE.equals(wire.get("grain_sublayers_active")), film + " single emulsion");
      check(Boolean.FALSE.equals(wire.get("extended_dynamic_range")), film + " EDR disabled");
      check(Boolean.TRUE.equals(wire.get("grain_active")), film + " default grain enabled");
      check(
          Boolean.FALSE.equals(
              FilmStockPolicy.renderOverrides(film, "", "", 0).get("grain_active")),
          film + " zero disables grain");
    }
    for (String film : new String[] {"kodak_portra_400", "fujifilm_provia_100f", ""}) {
      check(!FilmStockPolicy.monochrome(film), "Color classification");
      check(!FilmStockPolicy.canPrint(film, FilmStockPolicy.SILVER_PAPER), "No silver paper");
      check(
          "kodak_2383".equals(FilmStockPolicy.paperFor(film, "kodak_2383", "")),
          "Use target paper when missing");
      check(
          "kodak_2393".equals(FilmStockPolicy.paperFor(film, "kodak_2383", "kodak_2393")),
          "Preserve chosen color paper");
      check(
          "kodak_2383".equals(
              FilmStockPolicy.paperFor(film, "kodak_2383", FilmStockPolicy.SILVER_PAPER)),
          "Color round trip clears silver paper");
      for (int step = 0; step <= 40; step++)
        close(step * .05, FilmStockPolicy.grainAmount(film, step * .05), "Color unchanged");
      Map<String, Object> wire =
          FilmStockPolicy.renderOverrides(film, "kodak_2383", FilmStockPolicy.SILVER_PAPER, 1);
      check(!wire.containsKey("y_filter_neutral"), "Color uses its database filter pack");
      check(!wire.containsKey("y_filter_shift"), "Color edits retained");
      check(!wire.containsKey("camera_filter"), "Color suppresses hidden monochrome filters");
      check(!wire.containsKey("halation_headroom_ev"), "Only CineStill exposes headroom");
      check(
          Boolean.TRUE.equals(wire.get("extended_dynamic_range")),
          "Color papers enable calibrated EDR highlight expansion");
      check(
          !wire.containsKey("grain_sublayers_active"),
          "Color keeps the 0.4.6 grain-layer behavior unchanged");
    }
    Object[][] grades = {
      {"00", 0.0, 199.0, .5323},
      {"1", 0.0, 99.8, .8257},
      {"2", 0.0, 68.0, 1.0},
      {"4", 39.1, 0.0, 1.334},
      {"5", 151.7, 0.0, 1.3507}
    };
    for (Object[] grade : grades) {
      Map<String, Object> wire =
          FilmStockPolicy.renderOverrides(
              "kodak_tri_x_400", "", "", 1, "", (String) grade[0], -.5, 4);
      close((Double) grade[1], (Double) wire.get("m_filter_neutral"), grade[0] + " magenta");
      close((Double) grade[2], (Double) wire.get("y_filter_neutral"), grade[0] + " yellow");
      close(
          Math.pow(2, .5) * (Double) grade[3],
          (Double) wire.get("print_exposure"),
          grade[0] + " print exposure");
    }
    Map<String, String> filters =
        Map.of("w8", "w8", "w21", "w21", "w25", "w25", "w58", "w58", "w47", "w47");
    for (Map.Entry<String, String> filter : filters.entrySet()) {
      Map<String, Object> wire =
          FilmStockPolicy.renderOverrides(
              "kodak_tri_x_400", "", "", 1, filter.getKey(), "2", 0, 4);
      check(filter.getValue().equals(wire.get("camera_filter")), filter.getKey() + " is kept");
    }
    check(
        "".equals(
            FilmStockPolicy.renderOverrides(
                    "kodak_tri_x_400", "", "", 1, "w99", "2", 0, 4)
                .get("camera_filter")),
        "Unknown camera filter is rejected");
    check(
        !FilmStockPolicy.renderOverrides(
                "kodak_portra_400", "", "", 1, "w25", "5", 0, 8)
            .containsKey("camera_filter"),
        "Color suppresses camera filters");
    Map<String, Object> cineStill =
        FilmStockPolicy.renderOverrides(
            FilmStockPolicy.CINESTILL_800T, "kodak_2383", "kodak_2383", 1, "w25", "5", 0, 4);
    close(4, (Double) cineStill.get("halation_headroom_ev"), "CineStill default headroom");
    check(!cineStill.containsKey("camera_filter"), "CineStill stays a color film");
    close(
        8,
        FilmStockPolicy.halationHeadroom(FilmStockPolicy.CINESTILL_800T, 99),
        "Bound CineStill headroom");
    close(
        0,
        FilmStockPolicy.halationHeadroom(FilmStockPolicy.CINESTILL_800T, -1),
        "Bound negative headroom");
    close(
        4,
        FilmStockPolicy.halationHeadroom(FilmStockPolicy.CINESTILL_800T, Double.NaN),
        "Invalid headroom falls back");
    close(1.70, FilmStockPolicy.grainAmount("kodak_tri_x_400", Double.NaN), "Invalid grain");
    close(2, FilmStockPolicy.grainAmount("kodak_tri_x_400", 100), "Bound excessive grain");
    close(0, FilmStockPolicy.grainAmount("kodak_tri_x_400", -1), "Bound negative grain");
    if (args.length == 1) writeFixtures(Path.of(args[0]));
    System.out.println("FilmStockPolicy: " + checks + " checks passed");
  }

  private static void writeFixtures(Path output) throws Exception {
    Map<String, Map<String, Object>> films = new LinkedHashMap<>();
    for (String film : DEFAULTS.keySet())
      films.put(film, FilmStockPolicy.renderOverrides(film, "", "", 1));
    films.put(
        "kodak_portra_400",
        FilmStockPolicy.renderOverrides(
            "kodak_portra_400", FilmStockPolicy.COLOR_PAPER, FilmStockPolicy.COLOR_PAPER, 1));
    films.put(
        FilmStockPolicy.CINESTILL_800T,
        FilmStockPolicy.renderOverrides(
            FilmStockPolicy.CINESTILL_800T,
            "kodak_2383",
            "kodak_2383",
            1,
            "",
            "2",
            0,
            4));
    StringBuilder json = new StringBuilder("{");
    for (Map.Entry<String, Map<String, Object>> film : films.entrySet()) {
      if (json.length() > 1) json.append(',');
      json.append('"').append(film.getKey()).append("\":{");
      boolean first = true;
      for (Map.Entry<String, Object> field : film.getValue().entrySet()) {
        if (!first) json.append(',');
        first = false;
        json.append('"').append(field.getKey()).append("\":");
        if (field.getValue() instanceof String) json.append('"');
        json.append(field.getValue());
        if (field.getValue() instanceof String) json.append('"');
      }
      json.append('}');
    }
    Files.writeString(output, json.append('}').toString());
  }
}
