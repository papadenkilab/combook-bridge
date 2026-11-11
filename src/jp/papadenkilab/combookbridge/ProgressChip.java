package jp.papadenkilab.combookbridge;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A small floating progress chip shown over the target app while the
 * Papotto writer runs ("書込 6/11 ✓5 ・ 体温 12:31"), so the user can
 * follow along without switching back to this app. Uses an accessibility
 * overlay (same mechanism as the Lukumi filler's progress display), so
 * NO "draw over other apps" permission is needed - the window is added
 * through the accessibility service's context. Not touchable, so it
 * never interferes with the automated taps.
 */
public class ProgressChip {
    private static TextView view;
    private static WindowManager wm;

    public static boolean canShow(Context c) {
        return true; // accessibility overlay: no separate permission needed
    }

    public static void show(Context c, String text) {
        try {
            if (view == null) {
                wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                view = new TextView(c);
                view.setTextColor(Color.WHITE);
                view.setTextSize(14);
                view.setPadding(30, 16, 30, 16);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xD91F1F24);
                bg.setCornerRadius(30);
                view.setBackground(bg);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = 110;
                wm.addView(view, lp);
            }
            view.setText(text);
            if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            view = null;
        }
    }

    public static void hide(Context c) {
        try {
            if (view != null && wm != null) wm.removeView(view);
        } catch (Exception e) {
            // already gone
        }
        view = null;
        wm = null;
    }
}
