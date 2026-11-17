package com.hushtv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * For TVs that will not let another app be the home screen (Fire TV, and Google TV devices that
 * have no home-screen setting): whenever the built-in home screen comes to the front, open this
 * launcher instead. That makes the Home button (and start-up) land here. It can be switched on
 * from the TV's own Accessibility settings, so no computer is needed.
 *
 * It is declared to receive events from the built-in launchers only and cannot read screen content.
 * It does nothing until someone enables it as an accessibility service.
 */
public final class HomeRedirectService extends AccessibilityService {

    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    /** Built-in home screens: package, and a part of the home activity's class name. */
    private static final String[][] HOMES = {
            {AMAZON_HOME, "HomeActivity"},
            {"com.google.android.apps.tv.launcherx", "HomeActivity"},
            {"com.google.android.tvlauncher", "MainActivity"},
    };

    /** Redirecting is skipped until this time (elapsed-realtime ms), so Amazon's home can be used on purpose. */
    private static volatile long pausedUntil;

    private long windowStart;
    private int redirectsInWindow;

    /** Lets Amazon's home screen stay up for a while, e.g. after choosing "Amazon's home screen" in settings. */
    static void pause(long millis) {
        pausedUntil = SystemClock.elapsedRealtime() + millis;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = String.valueOf(event.getPackageName()), cls = String.valueOf(event.getClassName());
        // Built-in settings screens live in the same package as its home screen; only the home
        // screen itself is redirected. Opening Amazon's settings brings its home screen up for
        // an instant first, so the redirect has to happen straight away: the settings screen
        // then opens on top of this launcher. A delayed redirect would land on top of settings.
        for (String[] h : HOMES) if (h[0].equals(pkg) && cls.contains(h[1])) openLauncher();
    }

    private void openLauncher() {
        long now = SystemClock.elapsedRealtime();
        if (now < pausedUntil) return;

        // Safety valve: if we keep getting bounced back here (say this launcher is crashing),
        // stop for a minute so Amazon's home screen is usable and the service can be turned off.
        if (now - windowStart > 10_000) {
            windowStart = now;
            redirectsInWindow = 0;
        }
        if (++redirectsInWindow > 4) {
            pausedUntil = now + 60_000;
            return;
        }
        try {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // Nothing sensible to do: Amazon's home screen simply stays up.
        }
    }

    @Override
    public void onInterrupt() {
    }
}
