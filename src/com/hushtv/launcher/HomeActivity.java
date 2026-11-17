package com.hushtv.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: a static wallpaper, a clock, and one row of app tiles.
 * Up reaches the corner buttons for all apps and settings; Menu also opens all apps.
 * Hold OK on a tile for its options.
 */
public final class HomeActivity extends Activity {

    private static final int NONE = 0, DRAWER = 1, SETTINGS = 2;

    private Apps apps;
    private Prefs prefs;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private List<Apps.App> all = new ArrayList<>();
    private boolean appsDirty = true, resumed;
    private volatile int loadGeneration;
    private int screenW, screenH, overlay = NONE;

    private ImageView wallpaperView, wallpaperBack;
    private TextView cornerLabel;
    private LinearLayout iconCol;
    private LinearLayout clockBox, topBar, iconBox;
    private IconView vpnIcon, netIcon, appsIcon, gearIcon;
    private ConnectivityManager connectivity;
    private String vpnLogoFor;
    private TextView clockView, dateView, greetView, weatherText, weatherWords, weatherRange;
    private LinearLayout cornerBox, weatherRow;
    private WeatherView weatherIcon;
    private Weather weather;
    private boolean weatherBusy;
    /** True while the tiles are faded away because nobody has touched the remote. */
    private boolean ambient;
    private boolean nightNow;
    private final Runnable goAmbient = this::enterAmbient;
    private RowScroll rowScroll;
    private LinearLayout row;
    private FrameLayout drawer, settings;
    private GridLayout drawerGrid;
    private ScrollView drawerScroll;
    private LinearLayout settingsList;
    private TextView settingsTitle, settingsHint;
    /** The settings category being shown, or null for the category list. */
    private String settingsPage;
    /** The setting whose list of choices is being shown, or null. */
    private Prefs.Setting settingsOption;
    /** True while the settings panel is showing the list of apps to open at start-up. */
    private boolean settingsBootPick;
    /** Set after the first press on "Restore settings"; the second press does it. */
    private boolean restoreArmed;
    /** Likewise for "Reset launcher settings". */
    private boolean resetArmed;
    /** Whether this is the launcher's first appearance since the TV started. */
    private boolean bootPending;
    /**
     * Which picture browser the panel is showing: 0 none, 1 wallpapers, 2 adding a picture from
     * the device, 3 icon packs, 4 one tile's picture.
     */
    private int settingsGallery;
    private Apps.App galleryApp;
    private String packBefore;
    private volatile int packGeneration;
    private String wallpaperBefore;
    private volatile int wallpaperGeneration;
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    /** Where Back from settings returns to: the home row or the drawer. */
    private int settingsFrom = NONE;
    private FrameLayout menuLayer;
    private LinearLayout menuList;
    private TextView menuTitle, moveHint, noticeView, drawerCount;
    private TileView menuTile;
    private StarsView starsView;
    private SkyView skyView;
    private GlowView glowView;
    private android.animation.ValueAnimator glowFollow;
    /** The scene on show while the wallpaper follows the time of day. */
    private String autoScene;
    /** How long the next wallpaper change fades for. */
    private int wallpaperFadeMs = 420;
    private final Runnable hideNotice = () -> noticeView.animate().alpha(0f)
            .translationY(-screenH * 0.012f).setDuration(260)
            .withEndAction(() -> noticeView.setVisibility(View.GONE)).start();
    private boolean menuOpen;
    private Apps.App menuApp;
    /** Package of the home tile being carried along the row, or null. */
    private String moving;
    private boolean drawerDirty = true;
    private boolean prebuildQueued, warmedUp, warming;
    private float scrollPos, scrollVel, scrollTarget;
    private boolean springRunning;
    private long lastFrameNanos;
    private String lastFocusedPkg;

    /**
     * Holds the row and lets it be wider than the screen. It has no scrolling of its own:
     * the row is moved by the spring in {@link #glideTo}.
     */
    private static final class RowScroll extends FrameLayout {
        RowScroll(Context c) {
            super(c);
        }

        @Override
        protected void measureChildWithMargins(View child, int parentWidthSpec, int widthUsed,
                                               int parentHeightSpec, int heightUsed) {
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    getChildMeasureSpec(parentHeightSpec, 0, child.getLayoutParams().height));
        }
    }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
            rotateWallpaperIfDue();
            if (prefs.nightDim() && isNight() != nightNow) applyWallpaper();
            updateStars(); // night may have fallen, or the weather changed
            if (prefs.wallpaperAuto() && !sceneForNow().equals(autoScene)) {
                wallpaperFadeMs = 2600; // the scene changes with the hour, slowly
                applyWallpaper();
            }
            refreshWeather();
            if (connectivity != null) updateStatusIcons(); // keeps the Wi-Fi bars current
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            runOnUiThread(HomeActivity.this::updateStatusIcons);
        }

        @Override
        public void onLost(Network network) {
            runOnUiThread(HomeActivity.this::updateStatusIcons);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            runOnUiThread(HomeActivity.this::updateStatusIcons);
        }
    };

    /** Shows how the Shield is connected, and a key while a VPN is up. */
    private void updateStatusIcons() {
        boolean vpn = false, ethernet = false, wifi = false;
        for (Network n : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(n);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
        }
        // The key shows whenever a VPN app is installed: bright while connected, faint while not.
        Intent vpnApp = vpnApp();
        String vpnPkg = vpnApp == null ? null : vpnApp.getPackage();
        if (vpnPkg == null ? vpnLogoFor != null : !vpnPkg.equals(vpnLogoFor)) {
            vpnLogoFor = vpnPkg; // only work the logo out again when the VPN app changes
            vpnIcon.setMask(vpnPkg == null ? null : vpnLogo(vpnPkg));
        }
        vpnIcon.setVisibility(vpn || vpnApp != null ? View.VISIBLE : View.GONE);
        vpnIcon.setDim(!vpn);
        vpnIcon.setFocusable(overlay == NONE && !menuOpen && vpnIcon.getVisibility() == View.VISIBLE);
        netIcon.setType(ethernet ? IconView.ETHERNET : wifi ? IconView.WIFI : IconView.OFFLINE);
        if (wifi && !ethernet) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                int rssi = wm.getConnectionInfo().getRssi();
                netIcon.setBars(android.net.wifi.WifiManager.calculateSignalLevel(rssi, 3) + 1);
            } catch (RuntimeException e) {
                netIcon.setBars(3); // strength unavailable: show the plain connected icon
            }
        }
    }

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The system sends a burst of these while it boots. Wait for the burst to end, then
            // swap the tiles in quietly rather than replaying the entrance each time.
            appsDirty = true;
            row.removeCallbacks(quietReload);
            row.postDelayed(quietReload, 1000);
        }
    };

    private final Runnable quietReload = () -> {
        if (resumed && appsDirty && !isDestroyed()) loadApps(false);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        apps = new Apps(this);
        prefs = new Prefs(this);
        weather = new Weather(this);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        getWindow().setBackgroundDrawable(null);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(buildLayout());
        applyIntentConfig(getIntent());
        applyLayoutSettings();
        applyWallpaper();
        if (!setupDone() && !getSharedPreferences("home", MODE_PRIVATE).getBoolean("setup_shown", false)) {
            getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("setup_shown", true).apply();
            getWindow().getDecorView().postDelayed(() -> {
                showOverlay(SETTINGS);
                settingsPage = "Get started";
                buildSettingsList(null);
            }, 900);
        }
        bootPending = apps.firstRunThisBoot(
                Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packages);
        background.shutdown();
        thumbs.shutdown();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        getWindow().getDecorView().postDelayed(updateCheck, 5000);
        Updater.failed = () -> row.post(() -> {
            if (isDestroyed()) return;
            notice("The update wasn't installed");
            refreshUpdateRow();
        });
        if (!setupDone() && overlay == NONE && getSharedPreferences("home", MODE_PRIVATE).getBoolean("setup_shown", false)
                && !isHomeScreen()) {
            getWindow().getDecorView().postDelayed(() -> {
                if (overlay != NONE) return;
                showOverlay(SETTINGS);
                settingsPage = "Get started";
                buildSettingsList(null);
            }, 700);
        }
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, time);
        updateClock();
        rotateWallpaperIfDue();
        if (appsDirty) loadApps();
        else playEntrance();
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback);
        updateStatusIcons();
        leaveAmbient();
        restartAmbientTimer();
        updateStars();
        if (prefs.wallpaperAuto() && autoScene != null && !sceneForNow().equals(autoScene)) applyWallpaper();
        refreshWeather();
        if (bootPending) {
            bootPending = false;
            openBootApp();
        }
    }

    /** Opens the app chosen in Settings, System, "Open at start-up", once per start of the TV. */
    private void openBootApp() {
        final String pkg = apps.bootApp();
        if (pkg == null || isDemo()) return;
        // A short wait lets the home screen appear first, so Back from the app lands somewhere tidy.
        row.postDelayed(() -> {
            if (!resumed || overlay != NONE || menuOpen || isDestroyed()) return;
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) startSafely(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        updateStars();
        row.removeCallbacks(goAmbient);
        getWindow().getDecorView().removeCallbacks(updateCheck);
        Updater.failed = null;
        unregisterReceiver(tick);
        connectivity.unregisterNetworkCallback(networkCallback);
    }

    // ---------------------------------------------------------------- memory

    private static final long PROCESS_STARTED = android.os.SystemClock.elapsedRealtime();

    /**
     * The first time the launcher draws after the TV starts or the app is updated, the Shield's
     * graphics driver has to build its drawing programs from scratch, and it keeps about 50 MB
     * for itself afterwards that it will not give back. A later start reuses the saved programs
     * and never pays that. So once the launcher is out of sight behind another app, and only if
     * it is carrying that extra weight, it ends its own process; the next press of Home starts
     * it fresh and light. At most twice per start of the TV, and never on Fire TV, where the
     * Home redirect lives in this process.
     */
    private boolean started;
    private final Runnable slimDown = () -> {
        if (started || isFireTv() || ClockDream.running || SaverPreview.showing) return;
        if (android.os.SystemClock.elapsedRealtime() - PROCESS_STARTED < 45_000) return;
        if (android.os.Debug.getNativeHeapAllocatedSize() < 60L * 1024 * 1024) return;
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (!apps.maySlimDown(boot)) return;
        android.os.Process.killProcess(android.os.Process.myPid());
    };

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        row.removeCallbacks(slimDown);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        row.removeCallbacks(slimDown);
        row.postDelayed(slimDown, 20_000);
    }

    /** Pressing Home while already here closes any panel and returns to the first tile. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        closeMenu(false);
        if (moving != null) endMove();
        showOverlay(NONE);
        lastFocusedPkg = null;
        focusRow();
        if (applyIntentConfig(intent)) {
            applyLayoutSettings();
            applyWallpaper();
            loadApps();
        }
    }

    /**
     * Lets the owner configure the launcher from a computer, e.g.
     * {@code adb shell am start -n com.hushtv.launcher/.HomeActivity --es wallpaper file:/path --es icon_pack dir:Name}.
     */
    private boolean applyIntentConfig(Intent intent) {
        if (!testHooksAllowed()) return false;
        boolean changed = false;
        String wallpaper = intent.getStringExtra("wallpaper");
        if (wallpaper != null) {
            apps.setWallpaper(wallpaper);
            changed = true;
        }
        // For testing from a computer, where a held OK press can't be sent: --es open_menu <package>
        final String menuFor = intent.getStringExtra("open_menu");
        if (menuFor != null) {
            row.post(() -> {
                for (Apps.App app : apps.homeApps(all)) {
                    if (app.pkg.equals(menuFor)) {
                        lastFocusedPkg = app.pkg;
                        showHomeTileMenu(app);
                    }
                }
            });
        }
        // Likewise for the tile picture chooser: --es open_tile_picker <package>
        final String pickerFor = intent.getStringExtra("open_tile_picker");
        if (pickerFor != null) {
            row.post(() -> {
                for (Apps.App app : apps.homeApps(all)) {
                    if (app.pkg.equals(pickerFor)) showTileImagePicker(app);
                }
            });
        }
        // For tools/backup.sh and tools/restore.sh: --ez backup true, --ez restore true
        if (intent.getBooleanExtra("backup", false)) {
            intent.removeExtra("backup");
            try {
                Backup.save(this);
            } catch (IOException e) {
                notice("Couldn't save the backup");
            }
        }
        if (intent.getBooleanExtra("restore", false)) {
            intent.removeExtra("restore");
            try {
                Backup.restore(this);
                changed = true;
            } catch (IOException e) {
                notice("Couldn't read the backup");
            }
        }
        // For testing a held button from a computer, which adb cannot do: --es hold "R3000,L3000"
        // holds Right for three seconds, then Left, sending the same repeats a remote would.
        final String page = intent.getStringExtra("open_settings");
        if (page != null) {
            intent.removeExtra("open_settings");
            getWindow().getDecorView().postDelayed(() -> {
                showOverlay(SETTINGS);
                settingsPage = page.isEmpty() ? null : page;
                buildSettingsList(null);
            }, 600);
        }
        final String hold = intent.getStringExtra("hold");
        if (hold != null) {
            intent.removeExtra("hold");
            row.postDelayed(() -> playHold(hold.split(","), 0), 600);
        }
        // Sets the weather town without the on-screen keyboard: --es weather_place "London"
        String town = intent.getStringExtra("weather_place");
        if (town != null) {
            intent.removeExtra("weather_place");
            setWeatherPlace(town);
        }
        String sky = intent.getStringExtra("sky");
        if (sky != null) {
            intent.removeExtra("sky");
            final int k = sky.equals("rain") ? Weather.RAIN : sky.equals("snow") ? Weather.SNOW : sky.equals("storm") ? Weather.STORM
                    : sky.equals("fog") ? Weather.FOG : sky.equals("cloud") ? Weather.CLOUD : Weather.CLEAR;
            row.post(() -> {
                skyView.set(k, prefs.skyStrength(), 1f);
                skyView.setRunning(true);
            });
        }
        String pack = intent.getStringExtra("icon_pack");
        if (pack != null) {
            apps.setIconPack(pack.equals("none") ? null : pack);
            changed = true;
        }
        return changed;
    }

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false); // lets a tile's glow spill past the row's bounds

        // Two layers, so a new wallpaper can fade in over the one it replaces.
        wallpaperBack = new ImageView(this);
        wallpaperBack.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(wallpaperBack, match());
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(wallpaperView, match());
        starsView = new StarsView(this, screenW, screenH);
        starsView.setVisibility(View.GONE);
        root.addView(starsView, match());
        skyView = new SkyView(this, screenW, screenH);
        skyView.setVisibility(View.GONE);
        root.addView(skyView, match());
        glowView = new GlowView(this, screenW, screenH);
        glowView.setAlpha(0f);
        root.addView(glowView, match());

        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockView = text(screenH * 0.068f, 0xFAFFFFFF, "sans-serif-light");
        dateView = text(screenH * 0.018f, 0xBFFFFFFF, "sans-serif-medium");
        dateView.setLetterSpacing(0.14f);
        dateView.setPadding(0, (int) (screenH * 0.006f), 0, 0);
        // A soft shadow keeps the clock readable over a bright part of a wallpaper.
        clockView.setShadowLayer(screenH * 0.014f, 0f, screenH * 0.002f, 0x99000000);
        dateView.setShadowLayer(screenH * 0.008f, 0f, screenH * 0.001f, 0xB3000000);
        clockBox.addView(clockView);
        clockBox.addView(dateView);

        // Corner buttons: VPN, network, all apps, and settings. Each one opens the thing it shows.
        int icon = (int) (screenH * 0.046f);
        vpnIcon = new IconView(this, IconView.VPN, icon);
        netIcon = new IconView(this, IconView.OFFLINE, icon);
        vpnIcon.setClickable(true);
        vpnIcon.setContentDescription("VPN");
        vpnIcon.setOnClickListener(v -> openVpn());
        netIcon.setClickable(true);
        netIcon.setContentDescription("Network");
        netIcon.setOnClickListener(v -> openNetworkSettings());
        appsIcon = new IconView(this, IconView.APPS, icon);
        appsIcon.setFocusable(true);
        appsIcon.setClickable(true);
        appsIcon.setContentDescription("All apps");
        appsIcon.setOnClickListener(v -> showOverlay(DRAWER));
        gearIcon = new IconView(this, IconView.GEAR, icon);
        gearIcon.setFocusable(true);
        gearIcon.setClickable(true);
        gearIcon.setContentDescription("Launcher settings");
        gearIcon.setOnClickListener(v -> showOverlay(SETTINGS));
        iconBox = new LinearLayout(this);
        iconBox.setOrientation(LinearLayout.HORIZONTAL);
        iconBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(screenH);
        pillBg.setColor(0x660B0D12); // dark glass with a fine light edge: reads on any wallpaper
        pillBg.setStroke(Math.max(1, (int) (screenH * 0.0014f)), 0x33FFFFFF);
        iconBox.setBackground(pillBg);
        int pillPad = (int) (screenH * 0.008f);
        iconBox.setPadding(pillPad * 2, pillPad, pillPad, pillPad);
        // The name of the selected corner button appears under the tray.
        cornerLabel = text(screenH * 0.019f, 0xE6FFFFFF, "sans-serif-medium");
        cornerLabel.setShadowLayer(screenH * 0.008f, 0f, screenH * 0.001f, 0xB3000000);
        cornerLabel.setPadding(0, (int) (screenH * 0.010f), 0, 0);
        cornerLabel.setAlpha(0f);
        final String[] cornerNames = {"VPN", "Network", "All apps", "Settings"};
        View[] corner = {vpnIcon, netIcon, appsIcon, gearIcon};
        for (int i = 0; i < corner.length; i++) {
            final String name = cornerNames[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(icon, icon);
            lp.rightMargin = (int) (screenW * 0.006f);
            iconBox.addView(corner[i], lp);
            corner[i].setOnFocusChangeListener((v, has) -> {
                v.invalidate();
                if (has) cornerLabel.setText(name);
                // Losing focus to a neighbour is followed at once by that neighbour gaining it.
                cornerLabel.animate().alpha(has ? 1f : 0f).setDuration(has ? 140 : 90).start();
            });
        }
        iconCol = new LinearLayout(this);
        iconCol.setOrientation(LinearLayout.VERTICAL);
        iconCol.setGravity(Gravity.CENTER_HORIZONTAL);
        iconCol.addView(iconBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        iconCol.addView(cornerLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams iconsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconsLp.rightMargin = (int) (screenW * 0.014f);
        iconsLp.topMargin = (int) (screenH * 0.006f); // lines the tray up with the clock's digits
        topBar.addView(iconCol, iconsLp);
        topBar.addView(clockBox);
        greetView = text(screenH * 0.036f, 0xE6FFFFFF, "sans-serif-light");
        greetView.setShadowLayer(screenH * 0.012f, 0f, screenH * 0.002f, 0x99000000);
        // Under the greeting, built like the clock: a weather picture and the temperature in large
        // light type, with the conditions and the town and day's range in two small lines beside them.
        weatherIcon = new WeatherView(this, (int) (screenH * 0.058f));
        weatherText = text(screenH * 0.058f, 0xFAFFFFFF, "sans-serif-light");
        weatherText.setShadowLayer(screenH * 0.014f, 0f, screenH * 0.002f, 0x99000000);
        weatherText.setPadding((int) (screenW * 0.007f), 0, (int) (screenW * 0.010f), 0);
        weatherWords = text(screenH * 0.024f, 0xF0FFFFFF, "sans-serif");
        weatherWords.setShadowLayer(screenH * 0.010f, 0f, screenH * 0.002f, 0x99000000);
        weatherRange = text(screenH * 0.018f, 0xA6FFFFFF, "sans-serif-medium");
        weatherRange.setLetterSpacing(0.14f);
        weatherRange.setShadowLayer(screenH * 0.008f, 0f, screenH * 0.001f, 0xB3000000);
        weatherRange.setPadding(0, (int) (screenH * 0.005f), 0, 0);
        LinearLayout weatherLines = new LinearLayout(this);
        weatherLines.setOrientation(LinearLayout.VERTICAL);
        weatherLines.addView(weatherWords);
        weatherLines.addView(weatherRange);
        weatherRow = new LinearLayout(this);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.addView(weatherIcon);
        weatherRow.addView(weatherText);
        weatherRow.addView(weatherLines);
        weatherRow.setPadding(0, (int) (screenH * 0.012f), 0, 0);
        cornerBox = new LinearLayout(this);
        cornerBox.setOrientation(LinearLayout.VERTICAL);
        cornerBox.addView(greetView);
        cornerBox.addView(weatherRow);
        root.addView(cornerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        rowScroll = new RowScroll(this);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setFocusable(false);
        rowScroll.addView(row);
        root.addView(rowScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        root.addView(buildDrawer(), match());
        root.addView(buildSettings(), match());
        root.addView(buildMenu(), match());

        moveHint = text(screenH * 0.024f, 0xE6FFFFFF, "sans-serif-medium");
        moveHint.setText("Left and right move the tile.  OK puts it down.");
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setCornerRadius(screenH);
        hintBg.setColor(0xCC12151A);
        moveHint.setBackground(hintBg);
        moveHint.setPadding((int) (screenW * 0.016f), (int) (screenH * 0.012f), (int) (screenW * 0.016f), (int) (screenH * 0.012f));
        moveHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.topMargin = (int) (screenH * 0.055f);
        root.addView(moveHint, hintLp);

        // Short messages ("Settings backed up") appear here, in the launcher's own style.
        noticeView = text(screenH * 0.025f, 0xF2FFFFFF, "sans-serif-medium");
        noticeView.setBackground(cardBackground(screenH));
        noticeView.setPadding((int) (screenW * 0.020f), (int) (screenH * 0.015f), (int) (screenW * 0.020f), (int) (screenH * 0.015f));
        noticeView.setMaxWidth((int) (screenW * 0.5f));
        noticeView.setGravity(Gravity.CENTER);
        noticeView.setVisibility(View.GONE);
        noticeView.setElevation(screenH * 0.02f);
        FrameLayout.LayoutParams noticeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        noticeLp.topMargin = (int) (screenH * 0.19f);
        root.addView(noticeView, noticeLp);
        return root;
    }

    /** Positions and sizes everything that the settings can change, then rebuilds the row. */
    private void applyLayoutSettings() {
        applyLayoutSettings(false);
    }

    private int rowGeneration;

    /**
     * @param inBackground draw the new tiles off the main thread and swap them in when ready.
     *                     Used while stepping through settings, so each step stays smooth.
     */
    private void applyLayoutSettings(boolean inBackground) {
        int tileW = (int) (screenW * prefs.tileWidthFrac());
        int tileH = tileW * 9 / 16;
        int pad = (int) (screenW * 0.045f);
        int headroom = (int) (tileH * 0.30f); // room for the selected tile to grow without clipping
        row.setPadding(pad, headroom, pad, headroom);
        FrameLayout.LayoutParams rowLp = (FrameLayout.LayoutParams) rowScroll.getLayoutParams();
        int labelH = prefs.labelMode() == 0 ? 0 : (int) (tileH * 0.30f);
        int lowest = screenH - (int) (screenH * 0.04f) - labelH - (int) (tileH * (1f + (prefs.zoom() - 1f) / 2f));
        rowLp.topMargin = Math.min((int) (screenH * prefs.rowTopFrac()), lowest) - headroom;
        rowScroll.setLayoutParams(rowLp);

        int mode = prefs.clockMode();
        clockBox.setVisibility(mode == 0 ? View.GONE : View.VISIBLE);
        dateView.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
        boolean left = prefs.clockLeft();
        clockBox.setGravity(left ? Gravity.START : Gravity.END);
        FrameLayout.LayoutParams clockLp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        clockLp.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
        clockLp.topMargin = (int) (screenH * 0.055f);
        clockLp.leftMargin = pad;
        // While settings are open, a right-hand clock sits just left of the panel instead of under it.
        clockLp.rightMargin = pad + (overlay == SETTINGS ? (int) (screenW * PANEL) : 0);
        topBar.setLayoutParams(clockLp);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.068f * prefs.clockScale());
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.018f * Math.min(1.15f, prefs.clockScale()));
        // The greeting takes the top corner the clock isn't using.
        FrameLayout.LayoutParams greetLp = (FrameLayout.LayoutParams) cornerBox.getLayoutParams();
        greetLp.gravity = Gravity.TOP | (left ? Gravity.END : Gravity.START);
        greetLp.topMargin = (int) (screenH * 0.066f);
        greetLp.leftMargin = pad;
        // With the clock on the left this sits on the right, clear of the settings panel.
        greetLp.rightMargin = pad + (overlay == SETTINGS ? (int) (screenW * PANEL) : 0);
        cornerBox.setLayoutParams(greetLp);
        cornerBox.setGravity(left ? Gravity.END : Gravity.START);
        updateCorner();
        restartAmbientTimer();
        iconCol.setVisibility(prefs.statusIcons() ? View.VISIBLE : View.GONE);
        ThumbView.accent = prefs.accent();
        glowView.setStrength(prefs.colourGlow());
        if (prefs.colourGlow() == 0) glowView.setAlpha(0f);
        updateStars();
        updateClock();
        final int generation = ++rowGeneration;
        if (!inBackground || all.isEmpty()) {
            buildRow();
            return;
        }
        final List<Apps.App> from = all;
        background.execute(() -> {
            if (generation != rowGeneration) return; // a newer change is already queued
            final List<TileView> tiles = makeRowTiles(from);
            runOnUiThread(() -> {
                if (generation == rowGeneration && !isDestroyed()) buildRow(tiles);
            });
        });
    }

    private View buildDrawer() {
        drawer = new FrameLayout(this);
        // The wallpaper shows through, dimmed: least at the top, most where the grid is densest.
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB8000000, 0xE0000000, 0xF0000000});
        drawer.setBackground(scrim);
        drawer.setVisibility(View.INVISIBLE);

        LinearLayout column = panelColumn();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(screenH * 0.040f, 0xFFFFFFFF, "sans-serif-light");
        title.setText("All apps");
        header.addView(title);
        drawerCount = text(screenH * 0.018f, 0x99FFFFFF, "sans-serif-medium");
        drawerCount.setLetterSpacing(0.12f);
        drawerCount.setAllCaps(true);
        drawerCount.setPadding((int) (screenW * 0.014f), (int) (screenH * 0.010f), 0, 0);
        header.addView(drawerCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill("Launcher settings", v -> showOverlay(SETTINGS)));
        header.addView(pill("Android settings", v -> startSafely(new Intent(Settings.ACTION_SETTINGS))));
        column.addView(header);

        TextView hint = text(screenH * 0.020f, 0x80FFFFFF, "sans-serif");
        hint.setText("Hold OK on an app to add it to the home row, remove it, or change its picture.");
        hint.setPadding(0, (int) (screenH * 0.008f), 0, (int) (screenH * 0.035f));
        column.addView(hint);

        drawerGrid = new GridLayout(this);
        drawerGrid.setClipChildren(false);
        drawerGrid.setClipToPadding(false);
        column.addView(drawerGrid);

        drawerScroll = panelScroll(column);
        drawer.addView(drawerScroll, match());
        return drawer;
    }

    /** Width of the settings panel, as a fraction of the screen. */
    private static final float PANEL = 0.36f;

    /** A panel on the right; the home screen stays visible beside it so changes preview live. */
    private View buildSettings() {
        settings = new FrameLayout(this);
        settings.setVisibility(View.GONE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padX = (int) (screenW * 0.020f);
        column.setPadding(padX, (int) (screenH * 0.065f), padX, (int) (screenH * 0.08f));
        settingsTitle = text(screenH * 0.046f, 0xFFFFFFFF, "sans-serif-light");
        settingsTitle.setPadding((int) (screenW * 0.010f), 0, 0, 0);
        column.addView(settingsTitle);
        settingsHint = text(screenH * 0.022f, 0x99FFFFFF, "sans-serif");
        settingsHint.setPadding((int) (screenW * 0.010f), (int) (screenH * 0.010f), 0, (int) (screenH * 0.028f));
        column.addView(settingsHint);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        column.addView(settingsList);
        ScrollView scroll = panelScroll(column);
        GradientDrawable bg = new GradientDrawable();
        // Solid: bright tiles showed faintly through anything less.
        bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        bg.setColors(new int[]{0xFF151921, 0xFF0A0C10});
        bg.setStroke(Math.max(1, (int) (screenH * 0.0014f)), 0x1FFFFFFF);
        float r = screenH * 0.03f;
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        scroll.setBackground(bg);
        settings.addView(scroll, new FrameLayout.LayoutParams((int) (screenW * PANEL),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        return settings;
    }

    private LinearLayout panelColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padX = (int) (screenW * 0.055f);
        column.setPadding(padX, (int) (screenH * 0.07f), padX, (int) (screenH * 0.08f));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        return column;
    }

    private ScrollView panelScroll(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipChildren(false);
        sv.setFocusable(false);
        sv.addView(content);
        return sv;
    }

    /** The settings categories: name, one-line summary, and glyph. */
    private static final Object[][] PAGES = {
            {"Tiles", "Size, position, corners, finish, icon pack", IconView.TILES},
            {"Selection", "Zoom, outline, glow, dimming, animation", IconView.SELECTION},
            {"Clock", "Clock, greeting, weather and corner buttons", IconView.CLOCK},
            {"Wallpaper", "Picture, dimming and rotation", IconView.IMAGE},
            {"Screensaver", "Pictures, clock, timing and preview", IconView.MOON},
            {"All apps", "The app drawer", IconView.APPS},
            {"Privacy", "Tracker blocking and ad settings", IconView.VPN},
            {"System", "Android settings, start-up app, backup, reset", IconView.GEAR},
    };

    /**
     * Shows one of three levels: the categories, the settings in {@link #settingsPage}, or the
     * choices for {@link #settingsOption}. {@code focusTag} names the row to select afterwards.
     */
    private void buildSettingsList(String focusTag) {
        settingsList.removeAllViews();
        if (settingsGallery == 3) {
            buildPackBrowser();
            return;
        }
        if (settingsGallery == 4) {
            buildTilePicker();
            return;
        }
        if (settingsGallery != 0) {
            buildGallery();
            return;
        }
        if (settingsBootPick) {
            settingsTitle.setText("Open at start-up");
            settingsHint.setText("Opens by itself each time the TV starts. Back cancels.");
            final String current = apps.bootApp();
            settingsList.addView(row(-1, "Nothing", "Stay on the home screen",
                    current == null ? "\u2713" : "", "boot:", v -> pickBootApp(null)));
            for (final Apps.App app : all) {
                if (app.component == null) continue;
                settingsList.addView(row(-1, app.label, null, app.pkg.equals(current) ? "\u2713" : "",
                        "boot:" + app.pkg, v -> pickBootApp(app.pkg)));
            }
            focusTagged(focusTag != null ? focusTag : "boot:" + (current == null ? "" : current));
            return;
        }
        if (settingsOption != null) {
            final Prefs.Setting s = settingsOption;
            settingsTitle.setText(s.title);
            settingsHint.setText("Choose one. Back cancels.");
            int current = prefs.get(s);
            for (int i = 0; i < s.options.length; i++) {
                final int index = i;
                settingsList.addView(row(-1, s.options[i], null, i == current ? "\u2713" : "", "opt" + i, v -> {
                    prefs.set(s, index);
                    settingChanged(s);
                    settingsOption = null;
                    buildSettingsList(s.key);
                }));
            }
            focusTagged(focusTag != null ? focusTag : "opt" + current);
            return;
        }
        if (settingsPage == null) {
            settingsTitle.setText("Settings");
            settingsHint.setText("Changes show on the home screen as you make them.");
            if (!setupDone()) settingsList.addView(row(IconView.APPS, "Get started", "A few quick steps to make Hush yours",
                    "\u203A", "Get started", v -> {
                        settingsPage = "Get started";
                        buildSettingsList(null);
                    }));
            for (Object[] page : PAGES) {
                final String name = (String) page[0];
                settingsList.addView(row((Integer) page[2], name, (String) page[1], "\u203A", name, v -> {
                    settingsPage = name;
                    buildSettingsList(null);
                }));
            }
            focusTagged(focusTag);
            return;
        }
        settingsTitle.setText(settingsPage);
        settingsHint.setText(settingsPage.equals("System") ? "Tools for the launcher and the TV."
                : "OK shows the choices. Left and right step through them.");
        if (settingsPage.equals("Wallpaper")) {
            settingsList.addView(row(-1, "Choose wallpaper", null, wallpaperName(), "wallpaper", v -> {
                wallpaperBefore = currentWallpaper();
                settingsGallery = 1;
                buildSettingsList(null);
            }));
        }
        for (Prefs.Setting s : Prefs.ALL) {
            if (s.group.equals(settingsPage)) settingsList.addView(settingRow(s));
        }
        if (settingsPage.equals("Clock")) {
            settingsList.addView(row(-1, "Weather town", "Sent to Open-Meteo to look up its weather",
                    weather.place() == null ? "Not set" : weather.place(), "weather_place",
                    v -> askWeatherPlace()));
        }
        if (settingsPage.equals("Screensaver")) {
            settingsList.addView(row(-1, "Preview", "See it now. Any button closes it", "\u203A", "saver_preview",
                    v -> startSafely(new Intent(this, SaverPreview.class))));
            settingsList.addView(row(-1, "Use as the TV's screensaver",
                    "Opens the TV's list. Choose Hush Launcher there", "\u203A", "dream",
                    v -> openScreensaverSettings()));
        }
        if (settingsPage.equals("Tiles")) settingsList.addView(iconPackRow());
        if (settingsPage.equals("Privacy")) buildPrivacyRows();
        if (settingsPage.equals("Get started")) buildSetupRows();
        if (settingsPage.equals("System")) {
            settingsList.addView(updateRow());
            settingsList.addView(row(-1, "Android settings", "Network, display, apps and more", "\u203A",
                    "android", v -> startSafely(new Intent(Settings.ACTION_SETTINGS))));
            settingsList.addView(row(-1, "Hush Home button", "For TVs without a home-screen setting. Turned on or off in Accessibility",
                    redirectEnabled() ? "On" : "Off", "redirect", v -> openAccessibility()));
            if (isFireTv()) {
                settingsList.addView(row(-1, "Amazon's home screen", "Opens it and leaves it alone for 5 minutes",
                        "\u203A", "amazon", v -> {
                            HomeRedirectService.pause(5 * 60_000);
                            Intent amazon = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                    .setPackage(HomeRedirectService.AMAZON_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startSafely(amazon);
                        }));
            }
            settingsList.addView(row(-1, "Open at start-up", "An app to open each time the TV starts",
                    bootAppName(), "boot", v -> {
                        settingsBootPick = true;
                        buildSettingsList(null);
                    }));
            final File backup = Backup.file(this);
            settingsList.addView(row(-1, "Back up settings", backup.isFile()
                    ? "Last saved " + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
                            .format(new Date(backup.lastModified()))
                    : "Home row, options, wallpaper and icon choices", "", "backup", v -> {
                        try {
                            Backup.save(this);
                            notice("Settings backed up");
                        } catch (IOException e) {
                            notice("Couldn't save the backup");
                        }
                        buildSettingsList("backup");
                    }));
            if (backup.isFile()) {
                settingsList.addView(row(-1, "Restore settings", restoreArmed
                        ? "Replaces the current settings. Press OK again to go ahead"
                        : "Put back what was last backed up", "", "restore", v -> {
                            if (!restoreArmed) {
                                restoreArmed = true;
                                resetArmed = false;
                                buildSettingsList("restore");
                                return;
                            }
                            restoreArmed = false;
                            restoreBackup();
                            buildSettingsList("restore");
                        }));
            }
            settingsList.addView(row(-1, "Reset launcher settings", resetArmed
                    ? "Clears every option. Press OK again to go ahead" : "Back to how it started", "",
                    "reset", v -> {
                        if (!resetArmed) {
                            resetArmed = true;
                            restoreArmed = false;
                            buildSettingsList("reset");
                            return;
                        }
                        resetArmed = false;
                        prefs.reset();
                        applyLayoutSettings();
                        applyWallpaper();
                        buildSettingsList("reset");
                    }));
        }
        if (focusTag != null || !settingsPage.equals("Get started")) focusTagged(focusTag);
    }

    private void pickBootApp(String pkg) {
        apps.setBootApp(pkg);
        settingsBootPick = false;
        buildSettingsList("boot");
    }

    private String bootAppName() {
        String pkg = apps.bootApp();
        if (pkg == null) return "Nothing";
        for (Apps.App app : all) if (app.pkg.equals(pkg)) return app.label;
        return "Not installed";
    }

    /** Reads the backup file and applies it to the screen. */
    private boolean restoreBackup() {
        try {
            Backup.restore(this);
        } catch (IOException e) {
            notice("Couldn't read the backup");
            return false;
        }
        applyLayoutSettings();
        applyWallpaper();
        loadApps(false);
        notice("Settings restored");
        return true;
    }

    /** True on Fire TV devices, where Amazon's launcher can't be replaced as the home screen. */
    private boolean isFireTv() {
        try {
            getPackageManager().getPackageInfo(HomeRedirectService.AMAZON_HOME, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether the Home redirect is currently enabled as an accessibility service. */
    private boolean redirectEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    /** Selects the row tagged {@code tag}, or the first row if there is none. */
    private void focusTagged(String tag) {
        if (warming) return;
        settingsList.post(() -> {
            View target = tag == null ? null : settingsList.findViewWithTag(tag);
            if (target == null && settingsList.getChildCount() > 0) target = settingsList.getChildAt(0);
            if (target != null) target.requestFocus();
        });
    }

    /**
     * One settings row: optional glyph (-1 for none), a title with an optional second line, and
     * a value or chevron on the right. {@link #valueOf} returns the right-hand text for updating.
     */
    private LinearLayout row(int glyph, String title, String sub, String value, String tag,
                             View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int px = (int) (screenW * 0.010f), py = (int) (screenH * 0.017f);
        r.setPadding(px, py, px, py);
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag(tag);
        if (glyph >= 0) {
            int size = (int) (screenH * 0.042f);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
            ilp.rightMargin = (int) (screenW * 0.011f);
            r.addView(new IconView(this, glyph, size), ilp);
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        final TextView name = text(screenH * 0.030f, 0xFFD6D9DE, "sans-serif");
        name.setText(title);
        words.addView(name);
        if (sub != null) {
            TextView second = text(screenH * 0.021f, 0x99FFFFFF, "sans-serif");
            second.setText(sub);
            second.setPadding(0, (int) (screenH * 0.006f), 0, 0);
            words.addView(second);
        }
        r.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView right = text(screenH * 0.026f, 0xB3FFFFFF, "sans-serif-medium");
        right.setText(value);
        right.setPadding((int) (screenW * 0.008f), 0, 0, 0);
        right.setId(android.R.id.text2);
        r.addView(right);
        final android.graphics.drawable.Drawable focused = focusPlate();
        r.setOnFocusChangeListener((v, has) -> {
            r.setBackground(has ? focused : null);
            name.setTextColor(has ? 0xFFFFFFFF : 0xFFD6D9DE);
            right.setTextColor(has ? 0xFFFFFFFF : 0xB3FFFFFF);
        });
        if (onClick != null) r.setOnClickListener(onClick);
        return r;
    }

    /** The highlight behind a selected row: a soft plate with a short bar in the outline colour. */
    private android.graphics.drawable.Drawable focusPlate() {
        GradientDrawable plate = new GradientDrawable();
        plate.setCornerRadius(screenH * 0.016f);
        plate.setColor(0xFF262B34);
        plate.setStroke(Math.max(1, (int) (screenH * 0.0014f)), 0x24FFFFFF);
        GradientDrawable bar = new GradientDrawable();
        bar.setCornerRadius(screenH);
        bar.setColor(prefs.accent());
        android.graphics.drawable.LayerDrawable both =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{plate, bar});
        both.setLayerWidth(1, Math.max(3, (int) (screenH * 0.0045f)));
        both.setLayerGravity(1, Gravity.START | Gravity.CENTER_VERTICAL);
        int inset = (int) (screenH * 0.014f);
        both.setLayerInset(1, Math.max(2, (int) (screenH * 0.003f)), inset, 0, inset);
        return both;
    }

    private static TextView valueOf(View row) {
        return row.findViewById(android.R.id.text2);
    }

    /** Settings → System row: shows the version and what an update check found; OK checks or installs. */
    private View updateRow() {
        String now = Updater.installedVersion(this);
        String sub, value;
        if (Updater.state == 1) { sub = "Checking\u2026"; value = ""; }
        else if (Updater.state == 2) { sub = "Downloading\u2026 " + Updater.progress + "%"; value = ""; }
        else if (Updater.updateAvailable(this)) { sub = "Version " + Updater.latestVersion + " is ready. Press OK to install"; value = "\u2191"; }
        else if (Updater.state == 3) { sub = Updater.error + ". Press OK to try again"; value = ""; }
        else { sub = Updater.latestVersion == null ? "Press OK to check for a newer version" : "Up to date"; value = "v" + now; }
        return row(-1, "Updates", sub, value, "update", v -> {
            if (Updater.updateAvailable(this)) {
                if (Updater.ensureAllowed(this)) Updater.install(this, this::refreshUpdateRow);
                else notice("Allow Hush to install updates, then press OK again");
            } else Updater.check(this, true, this::refreshUpdateRow);
        });
    }

    private void refreshUpdateRow() {
        if (overlay == SETTINGS && "System".equals(settingsPage) && settingsOption == null && settingsGallery == 0)
            buildSettingsList("update");
    }

    private final Runnable updateCheck = this::maybeCheckForUpdate;

    /** Daily background check; a quiet notice when a new version appears. */
    private void maybeCheckForUpdate() {
        if (!prefs.autoUpdate()) return;
        final boolean had = Updater.updateAvailable(this);
        Updater.check(this, false, () -> {
            if (isDestroyed()) return;
            if (!had && Updater.updateAvailable(this))
                notice("Hush " + Updater.latestVersion + " is available: Settings \u2192 System \u2192 Updates");
            refreshUpdateRow();
        });
    }

    /** Set when a Get started step opened another screen; Back from there returns to the guide. */
    private boolean guideReturn;
    private String lastGuidePlace;

    private boolean setupDone() {
        return getSharedPreferences("home", MODE_PRIVATE).getBoolean("setup_done", false);
    }

    /** True when the TV opens Hush for the Home button, by setting or through the Home button service. */
    private boolean isHomeScreen() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName)) return true;
        return redirectEnabled();
    }

    /** Where this TV keeps Accessibility, in its own menu words. */
    private String accessibilityPath() {
        if (isFireTv()) return "Settings \u2192 Accessibility";
        boolean googleTv;
        try {
            getPackageManager().getPackageInfo("com.google.android.apps.tv.launcherx", 0);
            googleTv = true;
        } catch (Exception e) {
            googleTv = false;
        }
        return googleTv ? "Settings \u2192 System \u2192 Accessibility"
                : "Settings \u2192 Device Preferences \u2192 Accessibility";
    }

    /** Opens Accessibility if the TV allows it; otherwise its Settings, with the path to follow. */
    private void openAccessibility() {
        Intent a11y = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(a11y, 0);
        boolean real = r != null && r.activityInfo != null && !r.activityInfo.packageName.contains("stubs");
        notice("Turn on \u201CHush Home button\u201D" + (real ? "" : ": " + accessibilityPath()));
        startSafely(real ? a11y : new Intent(Settings.ACTION_SETTINGS));
    }

    /** First-run guide: each step opens the place where it is done. */
    private void buildSetupRows() {
        settingsHint.setText("Each step is optional. You can come back here from Settings.");
        boolean home = isHomeScreen();
        final boolean tried = getSharedPreferences("home", MODE_PRIVATE).getBoolean("home_setting_tried", false);
        Intent pickHome = new Intent(Settings.ACTION_HOME_SETTINGS);
        final boolean hasPicker = pickHome.resolveActivity(getPackageManager()) != null && !isFireTv();
        final boolean useButton = !hasPicker || tried;
        String sub = home ? "Done: the Home button opens Hush"
                : useButton ? (tried ? "The TV kept its own home screen. Turn on \u201CHush Home button\u201D instead: " : "Turn on \u201CHush Home button\u201D: ")
                        + accessibilityPath()
                : "Opens the TV's home-screen setting. Choose Hush, then press Back";
        settingsList.addView(row(-1, "1. Make Hush your home screen", sub,
                home ? "\u2713" : "\u203A", "setup_home", v -> {
                    if (!useButton) {
                        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_setting_tried", true).apply();
                        startSafely(new Intent(Settings.ACTION_HOME_SETTINGS));
                    } else openAccessibility();
                }));
        settingsList.addView(row(-1, "2. Choose your apps", "Hold OK on any app to add it to the row or take it off",
                "\u203A", "setup_apps", v -> {
                    showOverlay(DRAWER);
                    guideReturn = true;
                    notice("Hold OK on an app to add it to your row");
                }));
        settingsList.addView(row(-1, "3. Pick a wallpaper", "Built-in scenes or your own pictures", "\u203A",
                "setup_wall", v -> {
                    guideReturn = true;
                    settingsPage = "Wallpaper";
                    wallpaperBefore = currentWallpaper();
                    settingsGallery = 1;
                    buildSettingsList(null);
                }));
        settingsList.addView(row(-1, "4. Show the weather", weather.place() == null ? "Type your town" : weather.place(),
                weather.place() == null ? "\u203A" : "\u2713", "setup_weather", v -> {
                    prefs.set("weather", 1);
                    refreshWeather();
                    askWeatherPlace();
                }));
        settingsList.addView(row(-1, "5. Privacy", "Tracker blocking and ad settings", "\u203A", "setup_privacy", v -> {
            settingsPage = "Privacy";
            buildSettingsList(null);
        }));
        settingsList.addView(row(-1, "Finish", "Hide this guide", "", "setup_done", v -> {
            getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("setup_done", true).apply();
            showOverlay(NONE);
        }));
        if (settingsList.getChildCount() > 0) focusTagged(!home ? "setup_home" : "setup_apps");
    }

    /** Tracker blocking (Private DNS) and a shortcut to the TV's own ad settings. */
    private void buildPrivacyRows() {
        settingsHint.setText("Hush itself collects nothing. With blocking on, the TV's address lookups go to the service you pick.");
        if (!Privacy.supported()) {
            settingsList.addView(row(-1, "Tracker blocking", "Needs Android 9 or newer", "", "dns0", null));
        } else {
        final boolean allowed = Privacy.canChange(this);
        final int now = Privacy.current(this);
        if (!allowed) settingsList.addView(row(-1, "Tracker blocking: one-time setup",
                "Android only allows it after one command from a computer or AI assistant. Steps: github.com/hush-tv/HushLauncher#privacy",
                "", "dns_setup", null));
        if (!allowed) settingsList.addView(row(-1, "No computer?",
                "Set your router's DNS to AdGuard (94.140.14.14 and 94.140.15.15). That blocks for every device at home",
                "", "dns_router", null));
        else for (int i = 0; i < Privacy.SERVICES.length; i++) {
            final int index = i;
            String[] svc = Privacy.SERVICES[i];
            String title = i == 0 ? "Don't block" : (i == 3 ? "Block malware with " : "Block trackers with ") + svc[0];
            if (i == 1) title += " (recommended)";
            String sub = svc[1];
            settingsList.addView(row(-1, title, sub, index == now ? "\u2713" : "", "dns" + i, v -> {
                if (!Privacy.set(this, index, ok -> {
                    if (ok) notice(index == 0 ? "Tracker blocking off"
                            : (index == 3 ? "Blocking malware with " : "Blocking trackers with ") + Privacy.SERVICES[index][0]);
                    else notice(Privacy.SERVICES[index][0] + " couldn't be reached here, so the TV's previous setting is back");
                    if ("Privacy".equals(settingsPage) && settingsOption == null) buildSettingsList("dns" + index);
                })) notice("Run this from a computer with adb first: " + Privacy.GRANT_COMMAND);
                else if (index > 0) notice("Checking the connection\u2026");
            }));
        }
        if (now == -1) settingsList.addView(row(-1, "Custom DNS in use", "Set outside Hush, left as it is", "\u2713", "dnsx", null));
        }
        settingsList.addView(row(-1, "Ad personalisation", "Opens Google's ad settings, to reset or limit your ad ID",
                "\u203A", "ads", v -> {
                    Intent ads = new Intent("com.google.android.gms.settings.ADS_PRIVACY");
                    startSafely(ads.resolveActivity(getPackageManager()) != null ? ads
                            : new Intent(Settings.ACTION_PRIVACY_SETTINGS));
                }));
        settingsList.addView(row(-1, "Usage and diagnostics", "Android's privacy page: stop sending usage data to Google",
                "\u203A", "privacy", v -> {
                    Intent priv = new Intent(Settings.ACTION_PRIVACY_SETTINGS);
                    android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(priv, 0);
                    if (r != null && r.activityInfo != null && !r.activityInfo.packageName.contains("stubs")) startSafely(priv);
                    else {
                        notice("Open " + (accessibilityPath().contains("System") ? "Settings \u2192 Privacy" : "Settings \u2192 Device Preferences")
                                + " \u2192 Usage & diagnostics and turn it off");
                        startSafely(new Intent(Settings.ACTION_SETTINGS));
                    }
                }));
    }

    /** Applies a changed setting to the home screen straight away. */
    private void settingChanged(Prefs.Setting s) {
        if (s.key.equals("wp_dim") || s.key.equals("wp_blur") || s.key.equals("night_dim")
                || s.key.equals("wp_auto")) applyWallpaper();
        else applyLayoutSettings(true);
        if (s.key.equals("weather")) refreshWeather();
        if (s.key.equals("sky") || s.key.equals("weather") || s.key.equals("stars")) updateStars();
        // Bigger tiles than the artwork was loaded for: load it again at the new size.
        if (neededArtWidth() > apps.artWidth()) loadApps(false);
    }

    /** A setting: OK opens its list of choices, left and right step through them in place. */
    private View settingRow(Prefs.Setting s) {
        final LinearLayout r = row(-1, s.title, null, s.options[prefs.get(s)], s.key, v -> {
            settingsOption = s;
            buildSettingsList(null);
        });
        r.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                prefs.step(s, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                valueOf(r).setText(s.options[prefs.get(s)]);
                settingChanged(s);
                return true;
            }
            return false;
        });
        return r;
    }

    /** The current wallpaper's name, for the row that opens the picker. */
    /** Night scenes for each part of the day, as {hour it starts, picture}. */
    private static final Object[][] DAY = {
            {1, "northern_lights"}, {5, "blue_hour"}, {8, "emerald_pines"}, {12, "alpine_peaks"},
            {16, "ember_dunes"}, {19, "dusk_ridge"}, {22, "moonrise"}};

    private static String sceneForNow() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String scene = (String) DAY[DAY.length - 1][1]; // before 1am it is still last night's
        for (Object[] part : DAY) if (hour >= (Integer) part[0]) scene = (String) part[1];
        return "asset:" + scene + ".jpg";
    }

    /** The wallpaper to show: the one chosen, or the scene for this time of day. */
    private String currentWallpaper() {
        return prefs.wallpaperAuto() ? sceneForNow() : apps.wallpaper();
    }

    private String wallpaperName() {
        if (prefs.wallpaperAuto()) return "Time of day";
        String value = apps.wallpaper();
        if (value.startsWith("asset:")) return prettyName(value.substring(6));
        if (value.startsWith("file:")) return prettyName(new File(value.substring(5)).getName());
        return "Plain black";
    }

    private TextView pill(String label, View.OnClickListener onClick) {
        TextView t = text(screenH * 0.022f, 0xFFE6E8EC, "sans-serif-medium");
        t.setText(label);
        int px = (int) (screenW * 0.014f), py = (int) (screenH * 0.012f);
        t.setPadding(px, py, px, py);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(screenH);
        idle.setColor(0xFF1B1D22);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(screenH);
        focused.setColor(0xFFFFFFFF);
        t.setBackground(idle);
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(has ? focused : idle);
            t.setTextColor(has ? 0xFF000000 : 0xFFE6E8EC);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (screenW * 0.010f);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(float sizePx, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---------------------------------------------------------------- content

    /** Reads the installed apps and their artwork off the UI thread, only when something changed. */
    private void loadApps() {
        loadApps(true);
    }

    /** The widest any tile is drawn with the current settings, enlarged for selection. */
    private int neededArtWidth() {
        int columns = prefs.columns();
        int drawerW = (screenW - 2 * (int) (screenW * 0.055f) - (int) (screenW * 0.016f) * (columns - 1)) / columns;
        return (int) Math.ceil(Math.max(screenW * prefs.tileWidthFrac() * prefs.zoom(), drawerW * 1.07f));
    }

    /** @param entrance false to swap the new tiles in without the rising animation */
    private void loadApps(final boolean entrance) {
        appsDirty = false;
        final boolean demo = isDemo();
        apps.setArtWidth(neededArtWidth());
        final int generation = ++loadGeneration;
        background.execute(() -> {
            // Skip this load if a newer one was queued behind it (e.g. stepping quickly through icon packs).
            if (generation != loadGeneration) return;
            final List<Apps.App> loaded = demo ? apps.demoApps() : apps.loadAll();
            // The tiles' pictures are drawn here too, so the screen never waits on them.
            final List<TileView> tiles = makeRowTiles(loaded);
            runOnUiThread(() -> {
                if (generation != loadGeneration || isDestroyed()) return;
                all = loaded;
                buildRow(tiles);
                if (overlay == NONE && entrance) row.post(HomeActivity.this::playEntrance);
                else releaseHeldWallpaper(false);
                if (overlay == DRAWER) buildDrawerGrid();
                else if (overlay == NONE) focusRow();
            });
        });
    }

    /**
     * Coming home: the tiles rise into place one after another and the clock fades in.
     * Only alpha and vertical position are used, which nothing else on the row animates.
     */
    private void playEntrance() {
        if (overlay != NONE || menuOpen || moving != null || prefs.animMillis() == 0) {
            releaseHeldWallpaper(false);
            return;
        }
        final float rise = screenH * 0.035f;
        // Almost invisible rather than fully hidden, so the tiles are drawn (and anything the
        // graphics system has to set up for them is done) before they start to move.
        // Stopping a tile's animations also stops its zoom and the sideways shuffle that makes
        // room for it, part-way through. Put both straight to where they were heading.
        int selected = row.indexOfChild(row.getFocusedChild());
        for (int i = 0; i < row.getChildCount(); i++) {
            TileView v = (TileView) row.getChildAt(i);
            v.animate().cancel();
            v.setScaleX(v.restScale());
            v.setScaleY(v.restScale());
            v.setTranslationX(selected >= 0 && i > selected ? Math.round((prefs.zoom() - 1f) * v.getWidth()) : 0f);
            v.setAlpha(0.01f);
            v.setTranslationY(rise);
        }
        topBar.animate().cancel();
        topBar.setAlpha(0.01f);
        final int generation = ++entranceGeneration;
        afterFrames(3, () -> {
            if (generation != entranceGeneration || isDestroyed()) return;
            for (int i = 0; i < row.getChildCount(); i++) {
                View v = row.getChildAt(i);
                v.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(i, 9) * 32L).setDuration(360)
                        .setInterpolator(TileView.EASE).withEndAction(() -> v.animate().setStartDelay(0)).start();
            }
            topBar.animate().alpha(1f).setDuration(420).start();
            releaseHeldWallpaper(true);
        });
    }

    private int entranceGeneration;
    /** On a cold start the wallpaper waits, unseen, so it can fade in together with the tiles. */
    private boolean wallpaperHeld;

    private void releaseHeldWallpaper(boolean fade) {
        if (!wallpaperHeld) return;
        wallpaperHeld = false;
        wallpaperView.animate().cancel();
        if (fade) wallpaperView.animate().alpha(1f).setDuration(420).start();
        else wallpaperView.setAlpha(1f);
    }

    /** Runs something once the given number of frames have been drawn. */
    private void afterFrames(final int frames, final Runnable then) {
        android.view.Choreographer.getInstance().postFrameCallback(t -> {
            if (frames <= 1) then.run();
            else afterFrames(frames - 1, then);
        });
    }

    /** Preview mode with placeholder tiles: {@code am start --ez demo true}. Never saves the home row. */
    private boolean isDemo() {
        return testHooksAllowed() && getIntent().getBooleanExtra("demo", false);
    }

    /** The computer hooks above only work in a debuggable build (./build.sh --debuggable), so other apps can't use them. */
    private boolean testHooksAllowed() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void buildRow() {
        buildRow(makeRowTiles(all));
    }

    /**
     * Makes the home row's tiles, including drawing each one's picture. Safe to call off the
     * UI thread: nothing here touches the screen until the tiles are added to the row.
     */
    private List<TileView> makeRowTiles(List<Apps.App> from) {
        List<TileView> tiles = new ArrayList<>();
        int tileW = (int) (screenW * prefs.tileWidthFrac());
        for (Apps.App app : apps.homeApps(from)) {
            TileView tile = new TileView(this, app, prefs, tileW, prefs.labelMode());
            tile.setOnClickListener(v -> launch(app));
            tile.setOnLongClickListener(v -> {
                showHomeTileMenu(app);
                return true;
            });
            tile.onFocused = () -> {
                lastFocusedPkg = app.pkg;
                // Wait for layout on a freshly built row so the tile's position is known.
                if (tile.isLaidOut()) glideTo(tile);
                else tile.post(() -> glideTo(tile));
                tile.post(() -> glowTo(tile));
            };
            // If the selection has left the row altogether (up to the corner buttons), the tiles
            // close the gap the enlarged tile needed, so the row rests evenly spaced.
            tile.onBlurred = () -> row.post(() -> {
                if (row.getFocusedChild() == null && !menuOpen) {
                    closeRowGap();
                    glowOff();
                }
            });
            tiles.add(tile);
        }
        TileView.bake(tiles);
        return tiles;
    }

    private void buildRow(List<TileView> tiles) {
        drawerDirty = true; // whatever changed the row (apps, packs, settings) changes the drawer too
        row.removeAllViews();
        int gap = (int) (screenW * prefs.gapFrac());
        for (TileView tile : tiles) {
            tile.growFromLeft();
            tile.setMoving(tile.app.pkg.equals(moving));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            row.addView(tile, lp);
        }
        if (overlay == NONE) focusRow();
        queueDrawerPrebuild();
    }

    /**
     * Builds the all-apps grid ahead of time, once the home screen has been idle for a moment,
     * so opening it doesn't have to draw thirty tiles on the spot.
     */
    private void queueDrawerPrebuild() {
        if (prebuildQueued) return;
        prebuildQueued = true;
        row.postDelayed(() -> android.os.Looper.myQueue().addIdleHandler(() -> {
            prebuildQueued = false;
            if (drawerDirty && overlay == NONE && !menuOpen && moving == null && !all.isEmpty()
                    && !isDestroyed()) {
                buildDrawerGrid();
                warmUp();
                tidyMemorySoon();
            }
            return false;
        }), 1500);
    }

    /**
     * Loading every app's artwork leaves a lot of finished-with picture data behind (each app's
     * full-size banner, and working copies of the tiles). Android only clears it when the app's
     * own small heap fills up, which here is almost never, so it is asked to once things settle.
     */
    private void tidyMemorySoon() {
        row.postDelayed(() -> background.execute(() -> {
            System.gc();
            System.runFinalization();
            System.gc();
        }), 4000);
    }

    /**
     * The first time a panel is ever drawn, the graphics system has one-off setup to do, which
     * showed up as a stutter on first opening all apps or settings. Once, while idle, both are
     * drawn for a moment at 1% opacity (invisible in practice) so that cost is already paid.
     */
    private void warmUp() {
        if (warmedUp || overlay != NONE) return;
        warmedUp = true;
        warming = true; // stops the settings list from taking the selection while it is built
        drawer.setAlpha(0.01f);
        drawer.setVisibility(View.VISIBLE);
        settingsPage = null;
        settingsOption = null;
        settingsBootPick = false;
        settingsGallery = 0;
        buildSettingsList(null);
        settings.setAlpha(0.01f);
        settings.setVisibility(View.VISIBLE);
        row.postDelayed(() -> {
            warming = false;
            boolean drawerInUse = overlay == DRAWER || (overlay == SETTINGS && settingsFrom == DRAWER);
            if (!drawerInUse) {
                drawer.setVisibility(View.INVISIBLE);
                drawer.setAlpha(1f);
            }
            if (overlay != SETTINGS) {
                settings.setVisibility(View.GONE);
                settingsList.removeAllViews();
            }
            settings.setAlpha(1f);
        }, 120);
    }

    /**
     * Slides the row so the selected tile lands on a fixed spot at the left, and moves the tiles
     * after it aside to make room for its zoom. The highlight therefore never moves on screen.
     */
    private long lastSideStep;

    private void endSweep() {
        TileView.sweeping = false;
        View now = row.getFocusedChild();
        if (now instanceof TileView) {
            ((TileView) now).settle();
            glideTo((TileView) now);
            glowTo((TileView) now);
        }
    }

    private void playHold(final String[] steps, final int at) {
        if (at >= steps.length || isDestroyed() || !resumed) return;
        final int code = steps[at].startsWith("L") ? KeyEvent.KEYCODE_DPAD_LEFT : steps[at].startsWith("U")
                ? KeyEvent.KEYCODE_DPAD_UP : steps[at].startsWith("D") ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_RIGHT;
        final long millis = Long.parseLong(steps[at].substring(1));
        final long down = android.os.SystemClock.uptimeMillis();
        dispatchKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0));
        row.postDelayed(new Runnable() {
            int repeat;
            @Override
            public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - down < millis) {
                    // Android waits 400 ms before the first repeat, then sends one every 50 ms.
                    dispatchKeyEvent(new KeyEvent(down, now, KeyEvent.ACTION_DOWN, code, ++repeat));
                    row.postDelayed(this, 50);
                } else {
                    dispatchKeyEvent(new KeyEvent(down, now, KeyEvent.ACTION_UP, code, 0));
                    row.postDelayed(() -> playHold(steps, at + 1), 500);
                }
            }
        }, 400);
    }

    private void closeRowGap() {
        int ms = prefs.animMillis();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (ms == 0) v.setTranslationX(0f);
            else v.animate().translationX(0f).setStartDelay(0).setDuration(ms).setInterpolator(TileView.EASE).start();
        }
    }

    private void glideTo(TileView tile) {
        int index = row.indexOfChild(tile);
        // Whole pixels: a tile resting on a fraction of a pixel is drawn blurred.
        float room = TileView.sweeping ? 0f : Math.round((prefs.zoom() - 1f) * tile.getWidth());
        int ms = TileView.sweeping ? 0 : prefs.animMillis();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            float tx = i > index ? room : 0f;
            if (ms == 0) {
                v.animate().cancel();
                v.setTranslationX(tx);
            } else v.animate().translationX(tx).setStartDelay(0).setDuration(ms).setInterpolator(TileView.EASE).start();
        }
        // Normally the selected tile sits at a fixed spot on the left. Near the end of the row
        // the row stops with its last tile at the screen's right edge, and the highlight travels.
        View last = row.getChildAt(row.getChildCount() - 1);
        float maxScroll = Math.max(0f, last.getRight() + room + row.getPaddingLeft() - screenW);
        scrollTarget = Math.min(tile.getLeft() - row.getPaddingLeft(), maxScroll);
        if (prefs.scrollStiffness() == 0f || !row.isLaidOut()) {
            scrollPos = scrollTarget;
            scrollVel = 0f;
            row.setTranslationX(-scrollPos);
        } else if (!springRunning) {
            springRunning = true;
            lastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(spring);
        }
    }

    /**
     * A critically damped spring, stepped once per display frame. Unlike a fixed-length animation
     * it keeps its speed when the target changes, so quick presses and a held button scroll as
     * one continuous movement.
     */
    private final Choreographer.FrameCallback spring = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            float dt = lastFrameNanos == 0 ? 1f / 60f : Math.min(0.034f, (nanos - lastFrameNanos) / 1e9f);
            lastFrameNanos = nanos;
            float w = prefs.scrollStiffness();
            if (w == 0f) w = 17f;
            float x = scrollPos - scrollTarget;
            float decay = (float) Math.exp(-w * dt);
            float k = scrollVel + w * x;
            scrollPos = scrollTarget + (x + k * dt) * decay;
            scrollVel = (scrollVel - k * w * dt) * decay;
            if (Math.abs(scrollPos - scrollTarget) < 0.3f && Math.abs(scrollVel) < 2f) {
                scrollPos = Math.round(scrollTarget);
                scrollVel = 0f;
                springRunning = false;
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
            row.setTranslationX(-scrollPos);
        }
    };

    /** Moves between the corner buttons that are showing; stops at either end. */
    private void stepCorner(int delta) {
        int at = iconBox.indexOfChild(iconBox.getFocusedChild());
        for (int i = at + delta; i >= 0 && i < iconBox.getChildCount(); i += delta) {
            View v = iconBox.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE && v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
    }

    /** The installed VPN app, if there is exactly one that can be opened; otherwise null. */
    private Intent vpnApp() {
        Intent found = null;
        for (android.content.pm.ResolveInfo ri : getPackageManager()
                .queryIntentServices(new Intent("android.net.VpnService"), 0)) {
            String pkg = ri.serviceInfo.packageName;
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            if (found != null && !pkg.equals(found.getPackage())) return null; // more than one: don't guess
            found = launch.setPackage(pkg);
        }
        return found;
    }

    /**
     * The VPN app's own logo as a single-colour shape for the corner tray, or null if it can't
     * be separated from its background. Modern app icons keep the logo on its own layer.
     */
    private Bitmap vpnLogo(String pkg) {
        try {
            android.graphics.drawable.Drawable icon = getPackageManager().getApplicationIcon(pkg);
            if (!(icon instanceof android.graphics.drawable.AdaptiveIconDrawable)) return null;
            android.graphics.drawable.Drawable logo =
                    ((android.graphics.drawable.AdaptiveIconDrawable) icon).getForeground();
            if (logo == null) return null;
            int size = 144;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            // The logo layer has a wide empty margin; draw it oversized so the logo fills the bitmap.
            int extra = size / 3;
            logo.setBounds(-extra, -extra, size + extra, size + extra);
            logo.draw(new Canvas(bmp));
            int[] px = new int[size * size];
            bmp.getPixels(px, 0, size, 0, 0, size, size);
            int solid = 0;
            for (int p : px) if ((p >>> 24) > 128) solid++;
            float covered = solid / (float) px.length;
            // Nearly empty or nearly full means there is no usable outline.
            return covered < 0.04f || covered > 0.80f ? null : bmp;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Opens the VPN app, or Android's VPN settings when there isn't one clear app to open. */
    private void openVpn() {
        Intent app = vpnApp();
        if (app != null) {
            app.setPackage(null);
            startSafely(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            startSafely(new Intent("android.net.vpn.SETTINGS"));
        }
    }

    private void openNetworkSettings() {
        Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
        if (wifi.resolveActivity(getPackageManager()) != null) startSafely(wifi);
        else startSafely(new Intent(Settings.ACTION_SETTINGS));
    }

    /** Moves the selection one tile along the row; stops at either end. */
    private void moveSelection(int delta) {
        int count = row.getChildCount();
        if (count == 0) return;
        int index = row.indexOfChild(row.getFocusedChild());
        int next = index < 0 ? 0 : Math.max(0, Math.min(count - 1, index + delta));
        if (next != index) row.getChildAt(next).requestFocus();
    }

    private void buildDrawerGrid() {
        // Rebuilding removes the selected tile, so note which app it was and select it again after.
        View focused = drawerGrid.getFocusedChild();
        final String keep = focused instanceof TileView ? ((TileView) focused).app.pkg : null;
        drawerGrid.removeAllViews();
        int columns = prefs.columns();
        drawerGrid.setColumnCount(columns);
        int usable = screenW - 2 * (int) (screenW * 0.055f);
        int gap = (int) (screenW * 0.016f);
        int tileW = (usable - gap * (columns - 1)) / columns;
        drawerDirty = false;
        // The apps on the home row, in the row's order, then everything else by name: under Watch,
        // Listen, Play and Apps and tools if grouping is on, otherwise in one list.
        List<Apps.App> home = apps.homeApps(all), rest = new ArrayList<>(all);
        rest.removeAll(home);
        drawerCount.setText(all.size() + " apps  \u00B7  " + home.size() + " on the home row");
        List<List<Apps.App>> lists = new ArrayList<>();
        List<String> names = new ArrayList<>();
        lists.add(home);
        names.add("ON YOUR HOME ROW");
        if (prefs.drawerGroups()) {
            for (int g = 0; g < Apps.GROUP_NAMES.length; g++) {
                List<Apps.App> in = new ArrayList<>();
                for (Apps.App a : rest) if (a.group == g) in.add(a);
                lists.add(in);
                names.add(Apps.GROUP_NAMES[g]);
            }
        } else {
            lists.add(rest);
            names.add(home.isEmpty() ? "ALL APPS" : "EVERYTHING ELSE");
        }
        boolean first = true;
        for (int group = 0; group < lists.size(); group++) {
            List<Apps.App> list = lists.get(group);
            if (list.isEmpty()) continue;
            TextView heading = text(screenH * 0.019f, 0xB3FFFFFF, "sans-serif-medium");
            heading.setLetterSpacing(0.16f);
            heading.setText(names.get(group));
            heading.setPadding(0, first ? 0 : (int) (screenH * 0.020f), 0, (int) (screenH * 0.018f));
            first = false;
            GridLayout.LayoutParams hlp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, columns));
            drawerGrid.addView(heading, hlp);
            for (int i = 0; i < list.size(); i++) {
                final Apps.App app = list.get(i);
                TileView tile = new TileView(this, app, prefs, tileW, 2, 1.25f, false);
                tile.capZoom(1.07f); // neighbours are close in the grid, so the selected tile grows less
                tile.setOnClickListener(v -> launch(app));
                tile.setOnLongClickListener(v -> {
                    showDrawerTileMenu(app);
                    return true;
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(i % columns));
                lp.rightMargin = (i % columns == columns - 1) ? 0 : gap;
                lp.bottomMargin = (int) (screenH * 0.030f);
                drawerGrid.addView(tile, lp);
            }
        }
        List<TileView> made = new ArrayList<>();
        for (int i = 0; i < drawerGrid.getChildCount(); i++) {
            if (drawerGrid.getChildAt(i) instanceof TileView) made.add((TileView) drawerGrid.getChildAt(i));
        }
        TileView.bake(made);
        if (keep != null && overlay == DRAWER) {
            drawerGrid.post(() -> {
                for (int i = 0; i < drawerGrid.getChildCount(); i++) {
                    if (!(drawerGrid.getChildAt(i) instanceof TileView)) continue;
                    TileView t = (TileView) drawerGrid.getChildAt(i);
                    if (t.app.pkg.equals(keep)) {
                        t.requestFocus();
                        return;
                    }
                }
                focusFirst(drawerGrid);
            });
        }
    }

    private void focusRow() {
        row.post(() -> {
            View target = null;
            for (int i = 0; i < row.getChildCount(); i++) {
                TileView t = (TileView) row.getChildAt(i);
                if (t.app.pkg.equals(lastFocusedPkg)) target = t;
            }
            if (target == null && row.getChildCount() > 0) target = row.getChildAt(0);
            if (target != null) target.requestFocus();
        });
    }

    private static void focusFirst(ViewGroup group) {
        group.post(() -> {
            for (int i = 0; i < group.getChildCount(); i++) {
                View v = group.getChildAt(i);
                if (v.isFocusable()) {
                    v.requestFocus();
                    return;
                }
            }
        });
    }

    private void updateClock() {
        Date now = new Date();
        if (prefs.clock12h()) {
            // The am/pm marker sits small beside the digits.
            String time = new SimpleDateFormat("h:mm", Locale.getDefault()).format(now);
            String half = new SimpleDateFormat("a", Locale.getDefault()).format(now).toLowerCase(Locale.getDefault());
            android.text.SpannableString s = new android.text.SpannableString(time + " " + half);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.36f), time.length(), s.length(), 0);
            clockView.setText(s);
        } else {
            clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        }
        dateView.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)
                .toUpperCase(Locale.getDefault()));
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        greetView.setText(hour < 5 ? "Good night" : hour < 12 ? "Good morning"
                : hour < 18 ? "Good afternoon" : "Good evening");
    }

    /** Shows or hides the greeting and the weather, and fills the weather in from the last reading. */
    private void updateCorner() {
        if (overlay == SETTINGS && "Get started".equals(settingsPage) && settingsOption == null && settingsGallery == 0
                && settingsList.findViewWithTag("setup_weather") != null && weather.place() != null
                && !String.valueOf(weather.place()).equals(lastGuidePlace)) {
            lastGuidePlace = weather.place();
            settingsList.post(() -> buildSettingsList("setup_weather"));
        }
        // Stays up while settings are open, so switching these on and off shows straight away.
        boolean drawerUp = overlay == DRAWER || (overlay == SETTINGS && settingsFrom == DRAWER);
        cornerBox.setVisibility(drawerUp ? View.GONE : View.VISIBLE);
        greetView.setVisibility(prefs.greeting() ? View.VISIBLE : View.GONE);
        boolean show = prefs.weather() && weather.hasReading();
        if (show && weatherRow.getVisibility() != View.VISIBLE) {
            weatherRow.setAlpha(0f); // arrives with a fade rather than popping in
            weatherRow.animate().alpha(1f).setDuration(420).start();
        }
        weatherRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        weatherIcon.set(weather.kind(), weather.day());
        updateStars();
        weatherText.setText(degrees(weather.tempC()));
        weatherWords.setText(weather.words());
        weatherRange.setText((weather.place() + "  \u00B7  H " + degrees(weather.highC()) + "  L "
                + degrees(weather.lowC())).toUpperCase(Locale.getDefault()));
    }

    private String degrees(float celsius) {
        return Math.round(prefs.fahrenheit() ? celsius * 9f / 5f + 32f : celsius) + "\u00B0";
    }

    /** Fetches the weather if it is switched on and the last reading has gone old. */
    private void refreshWeather() {
        if (!prefs.weather() || weatherBusy || !weather.due()) return;
        weatherBusy = true;
        new Thread(() -> {
            try {
                weather.refresh();
            } catch (IOException | RuntimeException ignored) {
                // No connection, or the service is down: keep the last reading until it goes stale.
            }
            runOnUiThread(() -> {
                weatherBusy = false;
                if (!isDestroyed()) updateCorner();
            });
        }, "weather").start();
    }

    /** Looks up a typed town name and, if it exists, shows its weather from now on. */
    private void setWeatherPlace(final String typed) {
        if (typed == null || typed.trim().isEmpty()) return;
        new Thread(() -> {
            String found = null;
            boolean failed = false;
            try {
                found = weather.choosePlace(typed);
                if (found != null) weather.refresh();
            } catch (IOException | RuntimeException e) {
                failed = true;
            }
            final String name = found;
            final boolean offline = failed && name == null;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                notice(offline ? "Couldn't reach the weather service"
                        : name == null ? "Couldn't find a town called " + typed.trim()
                        : "Weather set to " + name);
                updateCorner();
                if (overlay == SETTINGS && "Clock".equals(settingsPage) && settingsOption == null) {
                    buildSettingsList("weather_place");
                }
            });
        }, "weather").start();
    }

    /** The dark plate with a fine light edge that the launcher's cards and messages sit on. */
    private GradientDrawable cardBackground(float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(0xF2171A20);
        bg.setStroke(Math.max(1, (int) (screenH * 0.0014f)), 0x2EFFFFFF);
        return bg;
    }

    /** Shows a short message for a few seconds, then fades it away. */
    private void notice(final String message) {
        runOnUiThread(() -> {
            if (isDestroyed() || noticeView == null) return;
            noticeView.removeCallbacks(hideNotice);
            noticeView.animate().cancel();
            noticeView.setText(message);
            // Centred in the part of the screen the settings panel leaves free.
            noticeView.setTranslationX(overlay == SETTINGS ? -screenW * PANEL / 2f : 0f);
            if (noticeView.getVisibility() != View.VISIBLE) {
                noticeView.setAlpha(0f);
                noticeView.setTranslationY(screenH * 0.012f);
                noticeView.setVisibility(View.VISIBLE);
            }
            noticeView.animate().alpha(1f).translationY(0f).setDuration(220).start();
            noticeView.postDelayed(hideNotice, 2200 + Math.min(2800, message.length() * 45L));
        });
    }

    /** Asks for the town with the TV's on-screen keyboard. */
    private void askWeatherPlace() {
        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        FrameLayout back = new FrameLayout(this);
        back.setBackgroundColor(0x99000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (screenW * 0.022f);
        card.setPadding(pad, (int) (screenH * 0.036f), pad, (int) (screenH * 0.032f));
        card.setBackground(cardBackground(screenH * 0.030f));
        card.setElevation(screenH * 0.03f);

        TextView title = text(screenH * 0.038f, 0xFFFFFFFF, "sans-serif-light");
        title.setText("Weather town");
        card.addView(title);
        TextView hint = text(screenH * 0.021f, 0x99FFFFFF, "sans-serif");
        hint.setText("Type a town or city, then press the keyboard's done key. Back cancels.");
        hint.setPadding(0, (int) (screenH * 0.008f), 0, (int) (screenH * 0.024f));
        card.addView(hint);

        final android.widget.EditText box = new android.widget.EditText(this);
        box.setSingleLine(true);
        box.setHint("Town or city");
        box.setHintTextColor(0x66FFFFFF);
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.032f);
        box.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        GradientDrawable field = new GradientDrawable();
        field.setCornerRadius(screenH * 0.016f);
        field.setColor(0xFF262B34);
        field.setStroke(Math.max(2, (int) (screenH * 0.0028f)), prefs.accent());
        box.setBackground(field);
        int fx = (int) (screenW * 0.012f), fy = (int) (screenH * 0.016f);
        box.setPadding(fx, fy, fx, fy);
        if (weather.place() != null) {
            box.setText(weather.place());
            box.selectAll(); // so typing a new town replaces the old one
        }
        card.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // In the upper part of the screen, clear of the keyboard that rises from the bottom.
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams((int) (screenW * 0.42f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cardLp.topMargin = (int) (screenH * 0.12f);
        back.addView(card, cardLp);
        dialog.setContentView(back);
        box.setOnEditorActionListener((v, action, event) -> {
            setWeatherPlace(box.getText().toString());
            dialog.dismiss();
            return true;
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        dialog.show();
        box.requestFocus();
    }

    /** Between ten at night and seven in the morning. */
    private static boolean isNight() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 22 || hour < 7;
    }

    // ---------------------------------------------------------------- ambient

    /** Starts the idle countdown again; called on every key press and whenever the screen changes. */
    private void restartAmbientTimer() {
        row.removeCallbacks(goAmbient);
        long wait = prefs.ambientMillis();
        if (wait > 0 && resumed) row.postDelayed(goAmbient, wait);
    }

    /** Nobody has touched the remote for a while: fade the tiles and corner buttons away. */
    private void enterAmbient() {
        if (ambient || !resumed || overlay != NONE || menuOpen || moving != null) return;
        ambient = true;
        updateStars();
        rowScroll.animate().alpha(0f).setDuration(1200).start();
        glowView.animate().alpha(0f).setDuration(1200).start();
        iconCol.animate().alpha(0f).setDuration(1200).start();
    }

    /** Brings the tiles back. Returns true if they had been hidden. */
    private boolean leaveAmbient() {
        if (!ambient) return false;
        ambient = false;
        updateStars();
        rowScroll.animate().alpha(1f).setDuration(260).start();
        if (row.getFocusedChild() != null && prefs.colourGlow() != 0) glowView.animate().alpha(1f).setDuration(260).start();
        iconCol.animate().alpha(1f).setDuration(260).start();
        return true;
    }

    // ---------------------------------------------------------------- panels

    private void showOverlay(int which) {
        if (which == overlay) return;
        if (which == NONE && guideReturn && overlay == DRAWER) {
            guideReturn = false;
            showOverlay(NONE);
            showOverlay(SETTINGS);
            settingsPage = "Get started";
            buildSettingsList("setup_apps");
            return;
        }
        if (overlay == SETTINGS && settingsGallery != 0) closeGallery(); // drop any unsaved preview
        if (which == SETTINGS) {
            settingsFrom = overlay;
            settingsPage = null;
            settingsOption = null;
            settingsBootPick = false;
            restoreArmed = false;
            resetArmed = false;
            settingsGallery = 0;
        }
        overlay = which;
        applyFocusability();
        // The drawer stays up behind the settings panel when settings were opened from it.
        boolean drawerShown = which == DRAWER || (which == SETTINGS && settingsFrom == DRAWER);
        // Hidden but still laid out, so that showing it has no measuring or layout left to do.
        drawer.setVisibility(drawerShown ? View.VISIBLE : View.INVISIBLE);
        // The drawer sits on the dimmed wallpaper, so the home row and clock step aside for it.
        rowScroll.setVisibility(drawerShown ? View.INVISIBLE : View.VISIBLE);
        topBar.setVisibility(drawerShown ? View.INVISIBLE : View.VISIBLE);
        updateCorner();
        if (which != NONE) {
            // No tile is selected now, so close the gap that was left for the selected tile's zoom.
            for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).animate().translationX(0f).setStartDelay(0).start();
        }
        settings.setVisibility(which == SETTINGS ? View.VISIBLE : View.GONE);
        if (which != SETTINGS) settingsList.removeAllViews(); // lets go of any thumbnails it was showing
        if (which == NONE) queueDrawerPrebuild();
        FrameLayout.LayoutParams barLp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        barLp.rightMargin = (int) (screenW * 0.045f) + (which == SETTINGS ? (int) (screenW * PANEL) : 0);
        topBar.setLayoutParams(barLp);
        FrameLayout.LayoutParams cornerLp = (FrameLayout.LayoutParams) cornerBox.getLayoutParams();
        cornerLp.rightMargin = cornerLp.leftMargin + (which == SETTINGS ? (int) (screenW * PANEL) : 0);
        cornerBox.setLayoutParams(cornerLp);
        int ms = prefs.animMillis();
        if (which == DRAWER) {
            if (drawerDirty) buildDrawerGrid();
            drawerScroll.scrollTo(0, 0);
            if (ms > 0) {
                drawer.setAlpha(0f);
                drawerScroll.setTranslationY(screenH * 0.04f);
                drawer.animate().alpha(1f).setDuration(ms).start();
                drawerScroll.animate().translationY(0f).setDuration(ms + 60).setInterpolator(TileView.EASE).start();
            } else {
                drawer.setAlpha(1f);
            }
            focusFirst(drawerGrid);
        } else if (which == SETTINGS) {
            warming = false;
            settings.setAlpha(1f);
            buildSettingsList(null);
            View panel = settings.getChildAt(0);
            if (ms > 0) {
                panel.setTranslationX(screenW * PANEL);
                panel.animate().translationX(0f).setDuration(ms + 60).setInterpolator(TileView.EASE).start();
            } else {
                panel.setTranslationX(0f);
            }
        } else {
            focusRow();
        }
    }

    /** Only the layer the user is actually in may hold focus; everything behind it is blocked. */
    private void applyFocusability() {
        boolean home = overlay == NONE && !menuOpen;
        row.setDescendantFocusability(home ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        drawer.setDescendantFocusability(overlay == DRAWER && !menuOpen
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gearIcon.setFocusable(home);
        appsIcon.setFocusable(home);
        netIcon.setFocusable(home);
        vpnIcon.setFocusable(home && vpnIcon.getVisibility() == View.VISIBLE);
    }

    // ---------------------------------------------------------------- menu

    /** One entry in a tile's menu. */
    private static final class MenuItem {
        final String label;
        final Runnable action;

        MenuItem(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private View buildMenu() {
        menuLayer = new FrameLayout(this);
        menuLayer.setBackgroundColor(0x66000000);
        menuLayer.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (screenW * 0.012f);
        card.setPadding(pad, (int) (screenH * 0.030f), pad, (int) (screenH * 0.024f));
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        bg.setColors(new int[]{0xFA171B23, 0xFA0D0F14});
        bg.setStroke(Math.max(1, (int) (screenH * 0.0014f)), 0x29FFFFFF);
        bg.setCornerRadius(screenH * 0.028f);
        card.setBackground(bg);
        card.setElevation(screenH * 0.03f);
        menuTitle = text(screenH * 0.036f, 0xFFFFFFFF, "sans-serif-light");
        menuTitle.setPadding((int) (screenW * 0.010f), 0, 0, (int) (screenH * 0.016f));
        card.addView(menuTitle);
        menuList = new LinearLayout(this);
        menuList.setOrientation(LinearLayout.VERTICAL);
        card.addView(menuList);
        menuLayer.addView(card, new FrameLayout.LayoutParams((int) (screenW * 0.26f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return menuLayer;
    }

    /** Shows a small menu over whatever is on screen. Back closes it without doing anything. */
    private void showMenu(String title, Apps.App about, List<MenuItem> items) {
        menuApp = about;
        menuTitle.setText(title);
        menuList.removeAllViews();
        for (final MenuItem item : items) {
            menuList.addView(row(-1, item.label, null, "", null, v -> {
                closeMenu(false);
                item.action.run();
            }));
        }
        menuOpen = true;
        applyFocusability();
        menuLayer.setVisibility(View.VISIBLE);
        View card = menuLayer.getChildAt(0);
        placeMenu(card, about);
        // The tile the menu is for keeps its selected look while the selection is in the menu.
        menuTile = null;
        android.view.ViewGroup tiles = overlay == DRAWER ? drawerGrid : overlay == NONE ? row : null;
        for (int i = 0; about != null && tiles != null && i < tiles.getChildCount(); i++) {
            View v = tiles.getChildAt(i);
            if (v instanceof TileView && ((TileView) v).app.pkg.equals(about.pkg)) menuTile = (TileView) v;
        }
        if (menuTile != null) menuTile.setHeld(true);
        int ms = prefs.animMillis();
        if (ms > 0) {
            menuLayer.setAlpha(0f);
            menuLayer.animate().alpha(1f).setDuration(ms).start();
            // Grows out of the tile it belongs to as the screen behind dims.
            card.setScaleX(0.90f);
            card.setScaleY(0.90f);
            card.animate().scaleX(1f).scaleY(1f).setDuration(ms + 40).setInterpolator(TileView.EASE).start();
        }
        focusFirst(menuList);
    }

    /** @param refocus put the selection back on the tile the menu was for (false when an action follows) */
    /**
     * Puts the menu beside the tile it is for: above a tile in the lower half of the screen,
     * below one in the upper half, lined up with the tile's left edge where there is room.
     */
    private void placeMenu(View card, Apps.App about) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        View tile = null;
        android.view.ViewGroup from = overlay == DRAWER ? drawerGrid : overlay == NONE ? row : null;
        for (int i = 0; about != null && from != null && i < from.getChildCount(); i++) {
            View v = from.getChildAt(i);
            if (v instanceof TileView && ((TileView) v).app.pkg.equals(about.pkg)) tile = v;
        }
        if (tile == null || !tile.isLaidOut()) {
            lp.gravity = Gravity.CENTER;
            lp.leftMargin = lp.topMargin = 0;
            card.setLayoutParams(lp);
            card.setPivotX(lp.width / 2f);
            return;
        }
        card.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int cardH = card.getMeasuredHeight(), gap = (int) (screenH * 0.022f), edge = (int) (screenW * 0.03f);
        int[] at = new int[2];
        tile.getLocationOnScreen(at);
        float shownW = tile.getWidth() * tile.getScaleX(), shownH = tile.getHeight() * tile.getScaleY();
        float tileTop = at[1], tileBottom = at[1] + shownH;
        boolean above = tileTop + shownH / 2f > screenH / 2f;
        int top = above ? (int) (tileTop - gap - cardH) : (int) (tileBottom + gap);
        int left = Math.max(edge, Math.min(at[0], screenW - edge - lp.width));
        // No room above or below (a tall menu by a tile in the middle): sit beside the tile.
        if (top < edge || top + cardH > screenH - edge) {
            top = Math.max(edge, Math.min((int) (tileTop + shownH / 2f - cardH / 2f), screenH - edge - cardH));
            left = at[0] + shownW + gap + lp.width <= screenW - edge ? (int) (at[0] + shownW + gap)
                    : (int) (at[0] - gap - lp.width);
            left = Math.max(edge, left);
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        card.setLayoutParams(lp);
        card.setPivotX(Math.max(0f, Math.min(lp.width, at[0] + shownW / 2f - left)));
        card.setPivotY(above ? cardH : 0f);
    }

    private void closeMenu(boolean refocus) {
        if (!menuOpen) return;
        menuOpen = false;
        if (menuTile != null) menuTile.setHeld(false);
        menuTile = null;
        menuLayer.setVisibility(View.GONE);
        applyFocusability();
        if (!refocus) return;
        if (overlay == DRAWER) focusDrawerTile(menuApp == null ? null : menuApp.pkg);
        else if (overlay == NONE) focusRow();
    }

    private void focusDrawerTile(final String pkg) {
        drawerGrid.post(() -> {
            for (int i = 0; i < drawerGrid.getChildCount(); i++) {
                if (!(drawerGrid.getChildAt(i) instanceof TileView)) continue;
                TileView t = (TileView) drawerGrid.getChildAt(i);
                if (t.app.pkg.equals(pkg)) {
                    t.requestFocus();
                    return;
                }
            }
            focusFirst(drawerGrid);
        });
    }

    // ---------------------------------------------------------------- moving a tile

    /** Picks a home tile up: left and right carry it along the row, OK or Back puts it down. */
    private void startMove(Apps.App app) {
        moving = app.pkg;
        lastFocusedPkg = app.pkg;
        moveHint.setVisibility(View.VISIBLE);
        buildRow();
    }

    private void endMove() {
        moving = null;
        moveHint.setVisibility(View.GONE);
        buildRow();
    }

    private void carry(int delta) {
        List<String> home = apps.homePackages();
        List<String> shown = installedHome();
        int at = shown.indexOf(moving), to = at + delta;
        if (at < 0 || to < 0 || to >= shown.size()) return;
        swap(home, moving, shown.get(to));
        saveHome(home);
        buildRow();
    }

    private void fadeIn(View v) {
        int ms = prefs.animMillis();
        if (ms == 0) {
            v.setAlpha(1f);
            return;
        }
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ms).withLayer().start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        restartAmbientTimer();
        if (ambient) {
            // The first press only brings the tiles back, whatever the button.
            if (e.getAction() == KeyEvent.ACTION_UP) leaveAmbient();
            return true;
        }
        // A held direction button repeats about twenty times a second: faster than each tile's
        // animation can play, so everything is restarted mid-way and it looks rushed. The repeats
        // are thinned to a steady pace that lets each step finish most of its movement.
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) {
            int held = e.getKeyCode();
            if (held == KeyEvent.KEYCODE_DPAD_LEFT || held == KeyEvent.KEYCODE_DPAD_RIGHT
                    || held == KeyEvent.KEYCODE_DPAD_UP || held == KeyEvent.KEYCODE_DPAD_DOWN) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastSideStep < prefs.holdStepMillis()) return true;
                lastSideStep = now;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            if (menuOpen) {
                // Only up, down and OK mean anything in a menu; Back closes it.
                if (code == KeyEvent.KEYCODE_BACK) closeMenu(true);
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MENU) return true;
                return super.dispatchKeyEvent(e);
            }
            if (moving != null) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) carry(-1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) carry(1);
                else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_BACK) endMove();
                boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                        || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                        || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU;
                if (ours) return true;
            }
            boolean onCorner = iconBox.hasFocus();
            if (overlay == NONE && onCorner) {
                // On the corner buttons: left and right move between them, Down or Back returns
                // to the row, Menu opens all apps, and OK falls through to press the button.
                // Anything else (OK, volume, media keys) is left alone.
                if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) {
                    focusRow();
                    return true;
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    stepCorner(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                } else if (code == KeyEvent.KEYCODE_MENU) {
                    showOverlay(DRAWER);
                    return true;
                } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    return true;
                }
            } else if (overlay == NONE && (code == KeyEvent.KEYCODE_MENU
                    || (code == KeyEvent.KEYCODE_DPAD_DOWN && prefs.downOpensDrawer()))) {
                showOverlay(DRAWER);
                return true;
            } else if (overlay == NONE && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true; // nothing below the row
            } else if (overlay == NONE && code == KeyEvent.KEYCODE_DPAD_UP) {
                // With the corner buttons hidden, Up is the only way left to reach all apps.
                if (prefs.statusIcons()) appsIcon.requestFocus();
                else showOverlay(DRAWER);
                return true;
            }
            if (overlay == NONE && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                moveSelection(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                if (overlay == SETTINGS && settingsGallery == 2) {
                    settingsGallery = 1; // back from "add a picture" to the gallery
                    buildSettingsList(null);
                } else if (overlay == SETTINGS && settingsGallery == 1) {
                    closeGallery();
                    buildSettingsList("wallpaper");
                } else if (overlay == SETTINGS && settingsGallery == 3) {
                    closeGallery();
                    buildSettingsList("icon_pack");
                } else if (overlay == SETTINGS && settingsGallery == 4) {
                    showOverlay(settingsFrom); // the tile picker was opened straight from a tile
                } else if (overlay == SETTINGS && settingsBootPick) {
                    settingsBootPick = false;
                    buildSettingsList("boot");
                } else if (overlay == SETTINGS && settingsOption != null) {
                    String key = settingsOption.key; // back to the setting it came from
                    settingsOption = null;
                    buildSettingsList(key);
                } else if (overlay == SETTINGS && settingsPage != null) {
                    String page = settingsPage; // back to the category it came from
                    settingsPage = null;
                    restoreArmed = false;
                    resetArmed = false;
                    buildSettingsList(page);
                } else if (overlay == SETTINGS) showOverlay(settingsFrom);
                else if (overlay == DRAWER) showOverlay(NONE);
                return true; // Back never leaves the home screen.
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // The home screen has nowhere to go back to.
    }

    // ---------------------------------------------------------------- actions

    private void launch(Apps.App app) {
        if (app.component == null) return; // placeholder tile
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(app.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startSafely(i);
    }

    /**
     * Opens the system's screensaver chooser. Android TV devices don't all answer the standard
     * request for it (the Shield doesn't), so the TV settings app's own screen is tried next.
     */
    private void openScreensaverSettings() {
        Intent[] tries = {
                new Intent(Settings.ACTION_DREAM_SETTINGS),
                new Intent().setClassName("com.android.tv.settings",
                        "com.android.tv.settings.device.display.daydream.DaydreamActivity"),
        };
        for (Intent i : tries) {
            try {
                HomeRedirectService.pause(3000);
                startActivity(i);
                return;
            } catch (RuntimeException ex) {
                // Not on this device; try the next one.
            }
        }
        notice("This TV keeps its screensaver choice in Android settings, under Device Preferences");
    }

    private void startSafely(Intent i) {
        // On Fire TV, system settings open by way of Amazon's home screen. Hold the Home redirect
        // off for a moment so it can't cover the screen that was just asked for.
        String action = i.getAction();
        if (action != null && action.startsWith("android.settings.")) HomeRedirectService.pause(3000);
        try {
            startActivity(i);
        } catch (RuntimeException ex) {
            notice("Couldn't open that");
        }
    }

    private void showHomeTileMenu(final Apps.App app) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("Open", () -> launch(app)));
        items.add(new MenuItem("Move", () -> startMove(app)));
        items.add(new MenuItem("Tile picture", () -> showTileImagePicker(app)));
        items.add(new MenuItem("Remove from home row", () -> {
            List<String> home = apps.homePackages();
            home.remove(app.pkg);
            saveHome(home);
            buildRow();
        }));
        items.add(new MenuItem("App info", () -> appInfo(app)));
        showMenu(app.label, app, items);
    }

    private void showDrawerTileMenu(final Apps.App app) {
        final boolean onHome = installedHome().contains(app.pkg);
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("Open", () -> launch(app)));
        items.add(new MenuItem(onHome ? "Remove from home row" : "Add to home row", () -> {
            List<String> home = apps.homePackages();
            if (onHome) home.remove(app.pkg);
            else if (!home.contains(app.pkg)) home.add(app.pkg);
            saveHome(home);
            buildRow();
            focusDrawerTile(app.pkg);
            notice(onHome ? "Removed from home row" : "Added to home row");
        }));
        items.add(new MenuItem("Tile picture", () -> showTileImagePicker(app)));
        items.add(new MenuItem("App info", () -> appInfo(app)));
        showMenu(app.label, app, items);
    }

    /** Swaps two entries of the saved home list, leaving any not-currently-installed ones where they are. */
    private static void swap(List<String> home, String a, String b) {
        int i = home.indexOf(a), j = home.indexOf(b);
        if (i >= 0 && j >= 0) java.util.Collections.swap(home, i, j);
    }

    /** Saves the home row, except in preview mode, whose placeholder apps must not replace the real list. */
    private void saveHome(List<String> home) {
        if (!isDemo()) apps.saveHome(home);
    }

    /** The home row as currently shown: the saved list without packages that aren't installed. */
    private List<String> installedHome() {
        List<String> out = new ArrayList<>();
        for (Apps.App a : apps.homeApps(all)) out.add(a.pkg);
        return out;
    }

    private void appInfo(Apps.App app) {
        startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.pkg, null)));
    }

    // ---------------------------------------------------------------- images

    /** Images in this folder can be chosen as the wallpaper. */
    private File wallpaperDir() {
        return dir("wallpapers");
    }

    /** Images in this folder can be chosen as a tile's picture. */
    private File tileDir() {
        return dir("tiles");
    }

    private File dir(String name) {
        File d = new File(getExternalFilesDir(null), name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static List<File> images(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f);
        }
        return out;
    }

    private static String prettyName(String file) {
        int dot = file.lastIndexOf('.');
        String base = (dot > 0 ? file.substring(0, dot) : file).replace('_', ' ').replace('-', ' ');
        return base.isEmpty() ? file : Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    /** Opens the settings panel on the picture choices for one app's tile. */
    private void showTileImagePicker(Apps.App app) {
        showOverlay(SETTINGS);
        galleryApp = app;
        settingsGallery = 4;
        buildSettingsList(null);
    }

    /**
     * Picture choices for one tile: follow the icon pack, the app's own banner, that app's
     * picture from each pack that has one, and anything in the tiles folder.
     */
    private void buildTilePicker() {
        final Apps.App app = galleryApp;
        settingsTitle.setText(app.label);
        settingsHint.setText("Choose this tile's picture.");
        final List<String[]> choices = new ArrayList<>(); // {saved value or null, name, picture to show}
        choices.add(new String[]{null, "Follow icon pack", null});
        choices.add(new String[]{Apps.OWN, "App's own", null});
        for (String[] pack : apps.packChoices()) {
            File f = apps.packFile(pack[0], app.pkg);
            if (f != null) choices.add(new String[]{f.getAbsolutePath(), pack[1], "file:" + f.getAbsolutePath()});
        }
        for (File f : images(tileDir())) {
            choices.add(new String[]{f.getAbsolutePath(), prettyName(f.getName()), "file:" + f.getAbsolutePath()});
        }
        final GridLayout grid = thumbGrid();
        int thumbW = thumbWidth(2);
        final String current = apps.tileImage(app.pkg);
        View toFocus = null;
        for (int i = 0; i < choices.size(); i++) {
            final String value = choices.get(i)[0];
            final String picture = choices.get(i)[2];
            final ThumbView thumb = new ThumbView(this, choices.get(i)[1], thumbW);
            boolean isCurrent = value == null ? current == null : value.equals(current);
            thumb.setChecked(isCurrent);
            if (isCurrent) toFocus = thumb;
            thumb.setOnClickListener(v -> {
                apps.setTileImage(app.pkg, value);
                apps.loadCustomInto(app);
                for (int k = 0; k < grid.getChildCount(); k++) ((ThumbView) grid.getChildAt(k)).setChecked(false);
                thumb.setChecked(true);
                lastFocusedPkg = app.pkg;
                buildRow();
                if (settingsFrom == DRAWER) buildDrawerGrid();
            });
            addThumb(grid, thumb, i, 2);
            if (picture != null) loadThumb(thumb, picture);
            else if (Apps.OWN.equals(value)) thumb.setBitmap(app.banner != null ? app.banner : app.icon);
            else thumb.setBitmap(app.packArt != null ? app.packArt : app.banner != null ? app.banner : app.icon);
        }
        settingsList.addView(grid);
        final View target = toFocus != null ? toFocus : grid.getChildAt(0);
        settingsList.post(target::requestFocus);
    }

    // ---- shared thumbnail-grid pieces

    private GridLayout thumbGrid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding((int) (screenW * 0.010f), 0, 0, 0);
        return grid;
    }

    /** Width of one thumbnail when {@code columns} of them share the panel. */
    private int thumbWidth(int columns) {
        int gap = (int) (screenW * 0.010f);
        int inner = (int) (screenW * PANEL) - 2 * (int) (screenW * 0.020f) - 2 * (int) (screenW * 0.010f);
        return (inner - gap * (columns - 1)) / columns;
    }

    private void addThumb(GridLayout grid, ThumbView thumb, int index, int columns) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.rightMargin = index % columns == columns - 1 ? 0 : (int) (screenW * 0.010f);
        lp.bottomMargin = (int) (screenH * 0.012f);
        grid.addView(thumb, lp);
    }

    /** Decodes a small copy of a picture off the UI thread and shows it in the thumbnail. */
    private void loadThumb(final ThumbView thumb, final String value) {
        Bitmap kept = THUMBS.get(value);
        if (kept != null) {
            thumb.setBitmap(kept); // already made on an earlier visit: no work, no waiting
            return;
        }
        thumbs.execute(() -> {
            // Below normal priority, so making thumbnails never competes with drawing frames.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Bitmap bmp = null;
            try {
                bmp = decode(value, 320, 180);
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            }
            final Bitmap result = Apps.gpuOnly(bmp);
            if (result != null) synchronized (THUMBS) { THUMBS.put(value, result); }
            runOnUiThread(() -> thumb.setBitmap(result));
        });
    }

    /** Gallery thumbnails kept between visits; each is small (320 x 180). */
    private static final android.util.LruCache<String, Bitmap> THUMBS = new android.util.LruCache<>(40);

    // ---- icon packs

    /**
     * One card per icon pack: its name, how many of your apps it covers, and the start of your
     * home row drawn in that style. Moving onto a card previews it on the real home row.
     */
    private void buildPackBrowser() {
        settingsTitle.setText("Icon pack");
        settingsHint.setText("OK keeps a pack. Back leaves it as it was.");
        final List<Apps.App> home = apps.homeApps(all);
        final String current = apps.iconPack();
        View toFocus = null;
        for (final String[] pack : apps.packChoices()) {
            final String value = pack[0];
            boolean isCurrent = value == null ? current == null : value.equals(current);
            String coverage;
            if (value == null) {
                coverage = "Each app's own banner";
            } else if (value.startsWith(Apps.DIR_PACK)) {
                int total = 0, onHome = 0;
                for (Apps.App a : all) if (apps.packFile(value, a.pkg) != null) total++;
                for (Apps.App a : home) if (apps.packFile(value, a.pkg) != null) onHome++;
                coverage = onHome + " of " + home.size() + " home tiles, " + total + " of " + all.size() + " apps";
            } else {
                coverage = "Installed icon pack";
            }
            final LinearLayout card = row(-1, shortPackName(pack[1]), coverage, isCurrent ? "\u2713" : "",
                    "pack:" + value, null);
            // Put the title row and a strip of sample tiles into one focusable card.
            final LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setFocusable(true);
            box.setClickable(true);
            box.setTag("pack:" + value);
            card.setFocusable(false);
            card.setClickable(false);
            card.setTag(null);
            card.setDuplicateParentStateEnabled(true);
            box.addView(card);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            int px = (int) (screenW * 0.010f);
            strip.setPadding(px, 0, px, (int) (screenH * 0.016f));
            int count = Math.min(4, home.size());
            int thumbW = thumbWidth(4);
            for (int i = 0; i < count; i++) {
                Apps.App a = home.get(i);
                ThumbView t = new ThumbView(this, "", thumbW);
                t.setFocusable(false);
                t.setClickable(false);
                File f = apps.packFile(value, a.pkg);
                if (f != null) loadThumb(t, "file:" + f.getAbsolutePath());
                else t.setBitmap(a.banner != null ? a.banner : a.icon);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = i == count - 1 ? 0 : (int) (screenW * 0.010f);
                strip.addView(t, lp);
            }
            box.addView(strip);
            final android.graphics.drawable.Drawable focused = focusPlate();
            box.setOnFocusChangeListener((v, has) -> {
                box.setBackground(has ? focused : null);
                if (has) previewPack(value);
            });
            box.setOnClickListener(v -> {
                apps.setIconPack(value);
                packBefore = value;
                for (int k = 0; k < settingsList.getChildCount(); k++) {
                    View other = settingsList.getChildAt(k);
                    valueOf(other).setText(other == box ? "\u2713" : "");
                }
            });
            settingsList.addView(box);
            if (isCurrent) toFocus = box;
        }
        final View target = toFocus != null ? toFocus : settingsList.getChildAt(0);
        settingsList.post(target::requestFocus);
    }

    private String previewPackValue;
    private final Runnable previewPackRunnable = () -> applyPackAsync(previewPackValue);

    /** Shows a pack on the home row without saving it, shortly after the selection settles. */
    private void previewPack(String value) {
        previewPackValue = value;
        settingsList.removeCallbacks(previewPackRunnable);
        settingsList.postDelayed(previewPackRunnable, 200);
    }

    /** Re-dresses the tiles in {@code pack} off the UI thread, then redraws the row (and drawer). */
    private void applyPackAsync(final String pack) {
        final int generation = ++packGeneration;
        final List<Apps.App> target = all;
        background.execute(() -> {
            if (generation != packGeneration) return;
            apps.applyPack(target, pack);
            runOnUiThread(() -> {
                if (generation != packGeneration || target != all || isDestroyed()) return;
                buildRow();
                if (overlay == DRAWER || settingsFrom == DRAWER) buildDrawerGrid();
            });
        });
    }

    private int currentPackIndex(List<String[]> choices) {
        String current = apps.iconPack();
        for (int i = 0; i < choices.size(); i++) {
            String v = choices.get(i)[0];
            if (v == null ? current == null : v.equals(current)) return i;
        }
        return 0;
    }

    /** Left and right step through the icon packs; OK lists them all. */
    private View iconPackRow() {
        final LinearLayout[] holder = new LinearLayout[1];
        final Runnable show = () -> {
            List<String[]> choices = apps.packChoices();
            valueOf(holder[0]).setText(shortPackName(choices.get(currentPackIndex(choices))[1]));
        };
        holder[0] = row(-1, "Icon pack", null, "", "icon_pack", v -> {
            packBefore = apps.iconPack();
            settingsGallery = 3;
            buildSettingsList(null);
        });
        show.run();
        holder[0].setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                List<String[]> choices = apps.packChoices();
                int n = choices.size();
                int next = ((currentPackIndex(choices) + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)) % n + n) % n;
                apps.setIconPack(choices.get(next)[0]);
                show.run();
                applyPackAsync(apps.iconPack());
                return true;
            }
            return false;
        });
        return holder[0];
    }

    /** The picker spells out "Standard (apps' own banners)"; the row only has room for the first word. */
    private static String shortPackName(String name) {
        int paren = name.indexOf(" (");
        return paren > 0 ? name.substring(0, paren) : name;
    }

    /** Every wallpaper that can be chosen, as {value, name}: plain black, the built-in set, then your own. */
    private List<String[]> wallpaperChoices() {
        List<String[]> out = new ArrayList<>();
        // Your own pictures first, then the scenes, then the plain dark ones.
        for (File f : images(wallpaperDir())) {
            out.add(new String[]{"file:" + f.getAbsolutePath(), prettyName(f.getName())});
        }
        List<String> plain = Arrays.asList("aurora.jpg", "carbon.jpg", "ember.jpg", "graphite.jpg",
                "midnight.jpg", "mist.jpg");
        try {
            String[] bundled = getAssets().list("wallpapers");
            if (bundled != null) {
                Arrays.sort(bundled);
                java.util.Set<String> mine = new java.util.HashSet<>();
                for (String[] o : out) mine.add(o[1]);
                for (String name : bundled) {
                    if (!plain.contains(name) && !mine.contains(prettyName(name))) {
                        out.add(new String[]{"asset:" + name, prettyName(name)});
                    }
                }
                for (String name : bundled) {
                    if (plain.contains(name)) out.add(new String[]{"asset:" + name, prettyName(name)});
                }
            }
        } catch (IOException ignored) {
        }
        out.add(new String[]{"none", "Plain black"});
        return out;
    }

    /** Pictures the device knows about (internal storage and any USB drive), widest first, as {value, name}. */
    private List<String[]> devicePictures() {
        List<String[]> out = new ArrayList<>();
        String[] columns = {android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.WIDTH};
        Uri base = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (android.database.Cursor c = getContentResolver().query(base, columns,
                android.provider.MediaStore.Images.Media.WIDTH + " >= 1280", null,
                android.provider.MediaStore.Images.Media.WIDTH + " DESC")) {
            while (c != null && c.moveToNext() && out.size() < 60) {
                String name = c.getString(1);
                out.add(new String[]{"content:" + android.content.ContentUris.withAppendedId(base, c.getLong(0)),
                        name == null ? "Picture" : prettyName(name)});
            }
        } catch (RuntimeException e) {
            // No permission or no media database: the gallery just shows nothing found.
        }
        return out;
    }

    /**
     * The wallpaper gallery (and, in mode 2, pictures found on the device to add to it).
     * Moving onto a thumbnail previews it on the home screen; OK keeps it; Back puts back
     * the wallpaper that was set before the gallery opened.
     */
    private void buildGallery() {
        final boolean adding = settingsGallery == 2;
        settingsTitle.setText(adding ? "Add a picture" : "Wallpaper");
        settingsHint.setText(adding ? "OK copies it into your wallpapers and sets it."
                : "OK keeps a wallpaper. Back leaves it as it was.");
        final List<String[]> choices = adding ? devicePictures() : wallpaperChoices();
        int thumbW = thumbWidth(2);
        final GridLayout grid = thumbGrid();
        final String current = currentWallpaper();
        View toFocus = null;
        for (int i = 0; i < choices.size(); i++) {
            final String value = choices.get(i)[0];
            final ThumbView thumb = new ThumbView(this, choices.get(i)[1], thumbW);
            thumb.setChecked(value.equals(current));
            if (value.equals(current)) toFocus = thumb;
            thumb.setOnFocusChangeListener((v, has) -> {
                if (has) previewWallpaper(value);
            });
            thumb.setOnClickListener(v -> {
                if (adding) addPicture(value, thumb.getContentDescription().toString());
                else {
                    apps.setWallpaper(value);
                    prefs.set("wp_auto", 0); // choosing a picture by hand stops it following the clock
                    wallpaperBefore = value;
                    for (int k = 0; k < grid.getChildCount(); k++) ((ThumbView) grid.getChildAt(k)).setChecked(false);
                    thumb.setChecked(true);
                }
            });
            addThumb(grid, thumb, i, 2);
            if (!value.equals("none")) loadThumb(thumb, value);
        }
        settingsList.addView(grid);
        if (adding && choices.isEmpty()) {
            settingsList.addView(row(-1, "No pictures found", "Put images on a USB stick or in Downloads", "",
                    "none", null));
        }
        if (!adding) {
            settingsList.addView(row(-1, "Add a picture", "From this device or a USB stick", "\u203A",
                    "add", v -> openDevicePictures()));
        }
        final View target = toFocus != null ? toFocus : grid.getChildCount() > 0 ? grid.getChildAt(0)
                : settingsList.getChildAt(settingsList.getChildCount() - 1);
        settingsList.post(target::requestFocus);
    }

    private String previewValue;
    private final Runnable previewRunnable = () -> renderWallpaper(previewValue);

    /** Shows a wallpaper on the home screen without saving it, shortly after the selection settles. */
    private void previewWallpaper(String value) {
        previewValue = value;
        settingsList.removeCallbacks(previewRunnable);
        // Waits until the selection rests, so moving through the gallery never also re-draws
        // the full-size wallpaper underneath on every step.
        settingsList.postDelayed(previewRunnable, 340);
    }

    /** Leaves the gallery, putting back the saved wallpaper if a preview was showing. */
    private void closeGallery() {
        settingsList.removeCallbacks(previewRunnable);
        settingsList.removeCallbacks(previewPackRunnable);
        int was = settingsGallery;
        settingsGallery = 0;
        if (was == 1 || was == 2) applyWallpaper();
        if (guideReturn && was == 1) {
            guideReturn = false;
            settingsPage = "Get started";
        }
        if (was == 3) applyPackAsync(apps.iconPack());
    }

    private void openDevicePictures() {
        String permission = android.os.Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES" : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, 1);
            return;
        }
        settingsGallery = 2;
        buildSettingsList(null);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != 1 || overlay != SETTINGS) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openDevicePictures();
        } else {
            notice("Without that permission the launcher can't see your pictures.");
        }
    }

    /** Copies a picture found on the device into the launcher's wallpapers folder and sets it. */
    private void addPicture(final String value, final String name) {
        background.execute(() -> {
            String safe = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
            final File dest = new File(wallpaperDir(), (safe.isEmpty() ? "Picture" : safe) + ".jpg");
            boolean ok = false;
            try (InputStream in = open(value); java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                ok = true;
            } catch (IOException | RuntimeException e) {
                dest.delete();
            }
            final boolean copied = ok;
            runOnUiThread(() -> {
                if (!copied) {
                    notice("Couldn't copy that picture");
                    return;
                }
                apps.setWallpaper("file:" + dest.getAbsolutePath());
                wallpaperBefore = apps.wallpaper();
                if (overlay == SETTINGS) {
                    settingsGallery = 1;
                    buildSettingsList(null);
                }
                applyWallpaper();
            });
        });
    }

    /** Moves to the next of your own images when the chosen interval has passed. */
    private void rotateWallpaperIfDue() {
        long every = prefs.rotateMillis();
        if (every == 0) return;
        List<File> mine = images(wallpaperDir());
        if (mine.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (apps.lastRotation() == 0) {
            apps.setLastRotation(now); // just switched on: wait one full interval first
            return;
        }
        if (now - apps.lastRotation() < every) return;
        int idx = -1;
        for (int i = 0; i < mine.size(); i++) {
            if (("file:" + mine.get(i).getAbsolutePath()).equals(apps.wallpaper())) idx = i;
        }
        apps.setWallpaper("file:" + mine.get((idx + 1) % mine.size()).getAbsolutePath());
        apps.setLastRotation(now);
        applyWallpaper();
    }

    /** Shows the saved wallpaper. */
    private void applyWallpaper() {
        String value = currentWallpaper();
        autoScene = prefs.wallpaperAuto() ? value : null;
        renderWallpaper(value);
    }

    /**
     * Decodes a wallpaper off the UI thread and bakes the blur, the dimming and the lower shade
     * into one screen-sized bitmap, so the home screen draws a single background layer per frame.
     */
    private void renderWallpaper(final String value) {
        nightNow = isNight();
        final int dim = Math.min(0xC0, prefs.wallpaperDimAlpha() + (prefs.nightDim() && nightNow ? 0x48 : 0));
        final int blur = prefs.wallpaperBlur();
        final boolean matchAccent = prefs.accentFromWallpaper();
        final int generation = ++wallpaperGeneration;
        background.execute(() -> {
            if (generation != wallpaperGeneration) return; // a newer request is already queued
            Bitmap out = null;
            try {
                Bitmap src = value.equals("none") ? null : decode(value, screenW, screenH);
                if (src != null) {
                    out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(out);
                    float scale = Math.max(screenW / (float) src.getWidth(), screenH / (float) src.getHeight());
                    float w = src.getWidth() * scale, h = src.getHeight() * scale;
                    float l = (screenW - w) / 2f, t = (screenH - h) / 2f;
                    Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                    c.drawBitmap(src, null, new RectF(l, t, l + w, t + h), p);
                    src.recycle();
                    Prefs.wallpaperAccent = accentOf(out);
                    if (blur > 1) {
                        // Halve the picture repeatedly, then double it back up step by step.
                        // Going in steps keeps edges smooth; one big jump leaves visible blocks.
                        List<Bitmap> steps = new ArrayList<>();
                        Bitmap level = out;
                        for (int f = 2; f <= blur; f *= 2) {
                            // Drawn into a fresh bitmap, which (unlike a scaled copy) can be drawn on later.
                            Bitmap half = Bitmap.createBitmap(Math.max(1, screenW / f),
                                    Math.max(1, screenH / f), Bitmap.Config.ARGB_8888);
                            new Canvas(half).drawBitmap(level, null,
                                    new RectF(0, 0, half.getWidth(), half.getHeight()), p);
                            steps.add(half);
                            level = half;
                        }
                        for (int i = steps.size() - 1; i > 0; i--) {
                            Bitmap from = steps.get(i), to = steps.get(i - 1);
                            new Canvas(to).drawBitmap(from, null, new RectF(0, 0, to.getWidth(), to.getHeight()), p);
                        }
                        c.drawBitmap(steps.get(0), null, new RectF(0, 0, screenW, screenH), p);
                        for (Bitmap b : steps) b.recycle();
                    }
                    if (dim > 0) c.drawColor(dim << 24);
                    Paint shade = new Paint();
                    // A gentle shade under the tiles, and a faint one behind the clock.
                    shade.setShader(new LinearGradient(0, screenH * 0.55f, 0, screenH,
                            new int[]{0x00000000, 0x59000000, 0xA6000000}, null, Shader.TileMode.CLAMP));
                    c.drawRect(0, screenH * 0.55f, screenW, screenH, shade);
                    shade.setShader(new LinearGradient(0, 0, 0, screenH * 0.22f,
                            0x47000000, 0x00000000, Shader.TileMode.CLAMP));
                    c.drawRect(0, 0, screenW, screenH * 0.22f, shade);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                out = null;
            }
            if (out == null) Prefs.wallpaperAccent = 0xFFFFFFFF;
            final Bitmap result = Apps.gpuOnly(out);
            runOnUiThread(() -> {
                if (generation != wallpaperGeneration) return;
                showWallpaper(result); // null = plain black
                // Stars twinkle only over the built-in night scenes, which have a sky for them.
                starsOk = value.startsWith("asset:") && SCENE_NAMES.contains(value.substring(6)) && blur <= 1;
                updateStars();
                // Tiles drawn before this wallpaper was known carry the old colour.
                if (matchAccent && !all.isEmpty() && row.getChildCount() > 0) {
                    ThumbView.accent = prefs.accent();
                    buildRow();
                }
            });
        });
    }

    /**
     * Picks one colour that belongs to a picture: the hue its most vivid areas agree on, made
     * light enough to work as an outline. A picture with no real colour gives white.
     */
    static int accentOf(Bitmap picture) {
        Bitmap small = Bitmap.createScaledBitmap(picture, 32, 18, true);
        float[] hsv = new float[3];
        double x = 0, y = 0, weight = 0;
        for (int py = 0; py < small.getHeight(); py++) {
            for (int px = 0; px < small.getWidth(); px++) {
                android.graphics.Color.colorToHSV(small.getPixel(px, py), hsv);
                double w = hsv[1] * hsv[1] * hsv[2];
                x += Math.cos(Math.toRadians(hsv[0])) * w;
                y += Math.sin(Math.toRadians(hsv[0])) * w;
                weight += w;
            }
        }
        if (small != picture) small.recycle();
        if (weight < 6 || Math.hypot(x, y) / weight < 0.25) return 0xFFFFFFFF;
        float hue = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
        return android.graphics.Color.HSVToColor(new float[]{hue, 0.50f, 1f});
    }

    private static final List<String> SCENE_NAMES = Arrays.asList("dusk_ridge.jpg", "moonrise.jpg",
            "northern_lights.jpg", "blue_hour.jpg", "ember_dunes.jpg", "emerald_pines.jpg",
            "alpine_peaks.jpg", "still_water.jpg", "city_lights.jpg");
    private boolean starsOk;

    private void updateStars() {
        int sky = prefs.weather() && weather.hasReading() ? weather.kind() : Weather.CLEAR;
        // Stars come out at night under a clear or broken sky, as they would outside.
        boolean night = prefs.weather() && weather.hasReading() ? !weather.day() : isNight();
        boolean starry = sky == Weather.CLEAR || sky == Weather.PARTLY;
        starsView.setRunning(starsOk && prefs.stars() && resumed && night && starry);
        skyView.set(sky, prefs.skyStrength(), prefs.weather() && weather.hasReading() ? weather.amount() : 1f);
        skyView.setRunning(resumed && !ambient);
    }

    // ---------------------------------------------------------------- colour glow

    /** Moves the light behind the row to a tile and turns it that tile's colour. */
    private void glowTo(final TileView tile) {
        if (prefs.colourGlow() == 0 || TileView.sweeping) return;
        glowView.tintTo(tile.tint, glowView.getAlpha() < 0.05f ? 0 : 520);
        glowView.animate().alpha(overlay == NONE && !ambient ? 1f : 0f).setDuration(380).start();
        // The row is still sliding, so keep the light on the tile until everything has settled.
        if (glowFollow != null) glowFollow.cancel();
        glowFollow = android.animation.ValueAnimator.ofFloat(0f, 1f);
        glowFollow.setDuration(1100);
        final int[] at = new int[2];
        glowFollow.addUpdateListener(a -> {
            if (!tile.isAttachedToWindow()) return;
            tile.getLocationOnScreen(at);
            glowView.moveTo(at[0] + tile.getWidth() * tile.getScaleX() / 2f,
                    at[1] + tile.getHeight() * tile.getScaleY() * 0.38f);
        });
        glowFollow.start();
    }

    private void glowOff() {
        glowView.animate().alpha(0f).setDuration(380).start();
    }

    /** Puts a finished wallpaper on screen, fading it in over the one before. */
    private void showWallpaper(Bitmap next) {
        try {
            showWallpaperNow(next);
        } finally {
            wallpaperFadeMs = 420;
        }
    }

    private void showWallpaperNow(Bitmap next) {
        wallpaperView.animate().cancel();
        wallpaperBack.animate().cancel();
        android.graphics.drawable.Drawable old = wallpaperView.getDrawable();
        if (old == null && next != null && all.isEmpty() && prefs.animMillis() != 0) {
            // Cold start: the tiles aren't ready yet. Hold the picture back so nothing is
            // animating while they are prepared; the entrance fades it in with them.
            wallpaperBack.setImageDrawable(null);
            wallpaperView.setAlpha(0.01f);
            wallpaperView.setImageBitmap(next);
            wallpaperHeld = true;
            return;
        }
        wallpaperHeld = false;
        if (prefs.animMillis() == 0 || !resumed || (old == null && next == null)) {
            wallpaperBack.setImageDrawable(null);
            wallpaperView.setAlpha(1f);
            wallpaperView.setImageBitmap(next);
            return;
        }
        wallpaperBack.setImageDrawable(old);
        wallpaperBack.setAlpha(1f);
        wallpaperView.setImageBitmap(next);
        if (next == null) {
            // Going to plain black: there is nothing to fade in, so the old picture fades out.
            wallpaperBack.animate().alpha(0f).setDuration(wallpaperFadeMs)
                    .withEndAction(() -> wallpaperBack.setImageDrawable(null)).start();
        } else {
            wallpaperView.setAlpha(0f);
            wallpaperView.animate().alpha(1f).setDuration(wallpaperFadeMs)
                    .withEndAction(() -> wallpaperBack.setImageDrawable(null)).start();
        }
    }

    /** Opens a wallpaper value: a bundled asset, a file, or a picture known to the device. */
    private InputStream open(String value) throws IOException {
        if (value.startsWith("asset:")) return getAssets().open("wallpapers/" + value.substring(6));
        if (value.startsWith("file:")) return new FileInputStream(value.substring(5));
        if (value.startsWith("content:")) {
            InputStream in = getContentResolver().openInputStream(Uri.parse(value.substring(8)));
            if (in != null) return in;
        }
        throw new IOException("Unknown wallpaper: " + value);
    }

    /** Decodes a picture at roughly the size it will be shown, however large the file is. */
    private Bitmap decode(String value, int targetW, int targetH) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(value)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = open(value)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }
}
