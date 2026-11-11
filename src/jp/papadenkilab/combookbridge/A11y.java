package jp.papadenkilab.combookbridge;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/** Whether this app's accessibility service is currently enabled. */
public class A11y {
    public static boolean isEnabled(Context c) {
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (list != null) {
                    for (AccessibilityServiceInfo info : list) {
                        if (info.getId() != null && info.getId().contains(c.getPackageName())) return true;
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            String services = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services != null && services.contains(c.getPackageName())) return true;
        } catch (Exception ignored) { }
        return false;
    }
}
