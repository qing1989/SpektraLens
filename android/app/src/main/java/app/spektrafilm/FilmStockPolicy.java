// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class FilmStockPolicy {
  static final String SILVER_PAPER = "ilford_multigrade_iv_rc";
  static final String COLOR_PAPER = "kodak_portra_endura";
  static final String CINESTILL_800T = "cinestill_800t";
  static final String DEFAULT_PAPER_GRADE = "2";
  private static final Map<String, Double> OWN_GRAIN;
  private static final Set<String> CAMERA_FILTERS;

  static {
    Map<String, Double> grains = new LinkedHashMap<>();
    grains.put("kodak_tri_x_400", 1.70);
    grains.put("kodak_tmax_100", .74);
    grains.put("fujifilm_neopan_acros_100_ii", .64);
    grains.put("ilford_hp5_plus_400", 1.49);
    OWN_GRAIN = Collections.unmodifiableMap(grains);
    CAMERA_FILTERS =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(
                java.util.Arrays.asList("w8", "w21", "w25", "w58", "w47")));
  }

  static boolean monochrome(String film) {
    return OWN_GRAIN.containsKey(film);
  }

  static boolean cinestill800t(String film) {
    return CINESTILL_800T.equals(film);
  }

  static boolean canPrint(String film, String paper) {
    return monochrome(film) == SILVER_PAPER.equals(paper);
  }

  static String paperFor(String film, String target, String selected) {
    if (monochrome(film)) return SILVER_PAPER;
    if (selected != null && !selected.isEmpty() && canPrint(film, selected)) return selected;
    return target != null && !target.isEmpty() && canPrint(film, target) ? target : COLOR_PAPER;
  }

  static double grainAmount(String film, double strength) {
    double amount = Double.isFinite(strength) ? Math.max(0, Math.min(2, strength)) : 1;
    double own = OWN_GRAIN.getOrDefault(film, 1.0);
    return own > 1 && amount > 1
        ? own + (amount - 1) * (2 - own)
        : own * amount;
  }

  static String cameraFilter(String film, String selected) {
    if (!monochrome(film)) return "";
    return selected != null && CAMERA_FILTERS.contains(selected) ? selected : "";
  }

  static String paperGrade(String film, String selected) {
    if (!monochrome(film)) return DEFAULT_PAPER_GRADE;
    return selected != null
            && (selected.equals("00")
                || selected.equals("1")
                || selected.equals("2")
                || selected.equals("4")
                || selected.equals("5"))
        ? selected
        : DEFAULT_PAPER_GRADE;
  }

  static double paperExposure(String film, String selected) {
    if (!monochrome(film)) return 1;
    return switch (paperGrade(film, selected)) {
      case "00" -> .5323;
      case "1" -> .8257;
      case "4" -> 1.334;
      case "5" -> 1.3507;
      default -> 1;
    };
  }

  static double halationHeadroom(String film, double value) {
    if (!cinestill800t(film)) return 0;
    return Double.isFinite(value) ? Math.max(0, Math.min(8, value)) : 4;
  }

  static Map<String, Object> renderOverrides(
      String film, String target, String paper, double grainStrength) {
    return renderOverrides(film, target, paper, grainStrength, "", DEFAULT_PAPER_GRADE, 0, 4);
  }

  static Map<String, Object> renderOverrides(
      String film,
      String target,
      String paper,
      double grainStrength,
      String selectedCameraFilter,
      String selectedPaperGrade,
      double printBrightness,
      double headroomEv) {
    Map<String, Object> result = new LinkedHashMap<>();
    boolean silver = monochrome(film);
    double grain = grainAmount(film, grainStrength);
    result.put("print_stock", paperFor(film, target, paper));
    result.put("grain_amount", grain);
    result.put("grain_active", grain > 0);
    result.put("extended_dynamic_range", !silver);
    double exposure = Double.isFinite(printBrightness) ? Math.pow(2, -printBrightness) : 1;
    if (silver) {
      String grade = paperGrade(film, selectedPaperGrade);
      switch (grade) {
        case "00" -> {
          result.put("m_filter_neutral", 0.0);
          result.put("y_filter_neutral", 199.0);
        }
        case "1" -> {
          result.put("m_filter_neutral", 0.0);
          result.put("y_filter_neutral", 99.8);
        }
        case "4" -> {
          result.put("m_filter_neutral", 39.1);
          result.put("y_filter_neutral", 0.0);
        }
        case "5" -> {
          result.put("m_filter_neutral", 151.7);
          result.put("y_filter_neutral", 0.0);
        }
        default -> {
          result.put("m_filter_neutral", 0.0);
          result.put("y_filter_neutral", 68.0);
        }
      }
      exposure *= paperExposure(film, grade);
      result.put("c_filter_neutral", 0.0);
      result.put("m_filter_shift", 0.0);
      result.put("y_filter_shift", 0.0);
      result.put("grain_sublayers_active", false);
      result.put("camera_filter", cameraFilter(film, selectedCameraFilter));
    } else {
      // Explicitly reset monochrome-only overrides so switching B&W -> Color in a reused
      // engine session never carries over a lens filter or disabled color grain sublayers.
      result.put("grain_sublayers_active", true);
      result.put("camera_filter", "");
    }
    result.put("print_exposure", exposure);
    if (cinestill800t(film)) result.put("halation_headroom_ev", halationHeadroom(film, headroomEv));
    return result;
  }
}
