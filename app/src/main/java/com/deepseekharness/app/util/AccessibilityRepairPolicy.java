package com.deepseekharness.app.util;

import android.content.Context;

/** Single read point for the opt-in that lets a disconnect write the accessibility list. */
public final class AccessibilityRepairPolicy {
  private AccessibilityRepairPolicy() {}

  /**
   * Automatic repair is off unless the user turned it on: writing {@code
   * enabled_accessibility_services} on a vendor ROM can itself unbind the service and read back as
   * a fresh disconnect.
   */
  public static boolean automaticRepairEnabled(Context ctx) {
    return ctx != null && new com.deepseekharness.app.core.ConfigStore(ctx).isAccessibilityAutoRepairEnabled();
  }
}
