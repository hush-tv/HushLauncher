package com.hushtv.launcher;

import android.service.dreams.DreamService;

/** The screensaver. What it shows is chosen in the launcher's settings; see {@link SaverView}. */
public final class ClockDream extends DreamService {
    /** True while the screensaver is on screen. */
    static volatile boolean running;
    private SaverView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true; // from the moment it is being set up, not only once it is showing
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(new Prefs(this).saverStyle() != 3); // the clock on black stays dim
        view = new SaverView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        if (view != null) view.start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        running = false;
        if (view != null) view.stop();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        if (view != null) view.stop(); // the dream can be torn down without onDreamingStopped
    }
}
