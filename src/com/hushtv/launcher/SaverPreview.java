package com.hushtv.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/** Shows the screensaver straight away from settings. Any button closes it. */
public final class SaverPreview extends Activity {
    /** True while the preview is on screen. */
    static volatile boolean showing;
    private SaverView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showing = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        view = new SaverView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
        view.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        showing = false;
        view.stop();
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
