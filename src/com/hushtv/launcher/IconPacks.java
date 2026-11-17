package com.hushtv.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads standard Android icon packs: apps that announce themselves with a launcher-theme
 * intent and ship an appfilter.xml mapping each app's component to one of the pack's drawables.
 */
final class IconPacks {

    /** Intents that icon packs conventionally declare. */
    static final String[] THEME_ACTIONS = {
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
    };

    /** Installed packs as package name to display name. */
    static Map<String, String> installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> out = new LinkedHashMap<>();
        for (String action : THEME_ACTIONS) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (out.containsKey(pkg)) continue;
                CharSequence label = ri.activityInfo.applicationInfo.loadLabel(pm);
                out.put(pkg, label == null ? pkg : label.toString());
            }
        }
        return out;
    }

    private final Resources res;
    private final String packPkg;
    private final Map<String, String> byComponent = new HashMap<>();
    private final Map<String, String> byPackage = new HashMap<>();

    /** @throws Exception if the pack is missing or has no readable appfilter.xml */
    IconPacks(Context ctx, String packPkg) throws Exception {
        this.packPkg = packPkg;
        this.res = ctx.getPackageManager().getResourcesForApplication(packPkg);
        XmlPullParser xml;
        InputStream stream = null;
        int id = res.getIdentifier("appfilter", "xml", packPkg);
        if (id != 0) {
            xml = res.getXml(id);
        } else {
            stream = res.getAssets().open("appfilter.xml");
            xml = XmlPullParserFactory.newInstance().newPullParser();
            xml.setInput(stream, "UTF-8");
        }
        try {
            int ev = xml.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                // One malformed entry skips that entry, not the whole pack.
                try {
                    if (ev == XmlPullParser.START_TAG && "item".equals(xml.getName())) addItem(xml);
                } catch (RuntimeException e) {
                    // skip it
                }
                try {
                    ev = xml.next();
                } catch (org.xmlpull.v1.XmlPullParserException | RuntimeException e) {
                    break; // the rest of the file is unreadable; keep what was read so far
                }
            }
        } finally {
            if (stream != null) stream.close();
            if (xml instanceof android.content.res.XmlResourceParser) ((android.content.res.XmlResourceParser) xml).close();
        }
    }

    private void addItem(XmlPullParser xml) {
        String component = xml.getAttributeValue(null, "component");
        String drawable = xml.getAttributeValue(null, "drawable");
        if (component == null || drawable == null) return;
        // "ComponentInfo{package/activity}"
        int open = component.indexOf('{'), slash = component.indexOf('/'), close = component.indexOf('}');
        if (open < 0 || slash < open || close < slash) return;
        String pkg = component.substring(open + 1, slash);
        String cls = component.substring(slash + 1, close);
        if (cls.startsWith(".")) cls = pkg + cls;
        byComponent.put(pkg + "/" + cls, drawable);
        if (!byPackage.containsKey(pkg)) byPackage.put(pkg, drawable);
    }

    int size() {
        return byPackage.size();
    }

    /** The pack's icon for this app, or null if the pack doesn't cover it. */
    Bitmap icon(Apps.App app) {
        String name = null;
        if (app.component != null) {
            name = byComponent.get(app.component.getPackageName() + "/" + app.component.getClassName());
        }
        if (name == null) name = byPackage.get(app.pkg); // phone packs list the phone entry point, not the TV one
        if (name == null) return null;
        int id = res.getIdentifier(name, "drawable", packPkg);
        if (id == 0) id = res.getIdentifier(name, "mipmap", packPkg);
        if (id == 0) return null;
        try {
            Drawable d = res.getDrawable(id, null);
            Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 256, 256);
            d.draw(new Canvas(bmp));
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
