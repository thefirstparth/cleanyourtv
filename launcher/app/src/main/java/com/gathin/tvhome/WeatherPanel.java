package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather corner on Home, read from house-of-1400's four-city weather feed (/api/live/wx; the site caches it at its
 * edge for 15 minutes, so asking often costs it nothing). config.xml weather_url (empty: off, nothing is ever
 * requested) and weather_cities (the feed's city ids, in the order shown).
 *
 * When it asks (the rules of tv-vibehack's Refresh, for a TV nobody can fix remotely):
 * - each time Home appears, unless it asked under two minutes ago;
 * - while Home stays on screen: every 15 minutes with a good copy, every minute after a failed request, and twice
 *   8 seconds apart straight after a failure;
 * - as soon as the network comes back after a failed request (after a power cut the router is often slower than the TV).
 * Each request gives up after 8 s to connect and 12 s to read, on its own background thread.
 *
 * What it shows: the last good answer, kept in a file so it survives a restart. Over 30 minutes old it says
 * "Updated 3:10 PM"; over 6 hours old it is hidden rather than show the wrong day's weather. Nothing here may take
 * Home down: every step that reads the feed or draws it is caught, and a failure only hides the corner.
 */
final class WeatherPanel {
    private static final String TAG = "WeatherPanel";
    private static final long MIN = 60_000L;
    private static final long HOME_AGAIN_MS = 2 * MIN, GOOD_EVERY_MS = 15 * MIN, FAILED_EVERY_MS = MIN, QUICK_MS = 8_000L;
    private static final int QUICK_TRIES = 2;
    private static final long UPDATED_AFTER_MS = 30 * MIN, HIDE_AFTER_MS = 6 * 60 * MIN;
    private static final int WARM = 0xFFFFB27A, COOL = 0xFF8EC9FF, WHITE = 0xFFFFFFFF, DIM = 0xC7FFFFFF, SOFT = 0xA6FFFFFF;

    private final Context ctx;
    private final String url;
    private final String[] ids;
    private final float density;
    private final Typeface light, regular, medium;
    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinearLayout view;
    private final LinearLayout cols;
    private final TextView updated;

    private JSONObject value;      // the last good answer, or null
    private long fetchedAt;        // when it was received
    private long askedAt;          // when the last request went out
    private boolean failed;        // the last request failed
    private boolean fetching;
    private int quickTries;
    private boolean resumed;
    private boolean shown, wanted;
    private float lastDockTop, lastTopBottom, lastLeft, lastRight;
    private ConnectivityManager.NetworkCallback netWatch;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long now = System.currentTimeMillis();
            if (now - askedAt >= (failed ? FAILED_EVERY_MS : GOOD_EVERY_MS)) ask();
            redrawIfAged();
            main.postDelayed(this, MIN);
        }
    };
    private final Runnable quick = () -> {
        if (resumed && failed) ask();
    };

    WeatherPanel(Context ctx, FrameLayout root) {
        this.ctx = ctx;
        this.url = ctx.getString(R.string.weather_url).trim();
        this.ids = ctx.getResources().getStringArray(R.array.weather_cities);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.light = ctx.getResources().getFont(R.font.outfit_light);
        this.regular = ctx.getResources().getFont(R.font.outfit_regular);
        this.medium = ctx.getResources().getFont(R.font.outfit_medium);
        this.file = new File(ctx.getFilesDir(), "weather.json");
        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        view.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updated = text(14, regular, SOFT);
        updated.setPadding(0, (int) (10 * density), 0, 0);
        updated.setVisibility(View.GONE);
        view.addView(updated, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (enabled()) {
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            loadSaved();
        }
    }

    boolean enabled() {
        return !url.isEmpty() && ids.length > 0;
    }

    // ---- when to ask ----------------------------------------------------------------------------------------------

    /** Home appeared. */
    void onResume() {
        if (!enabled()) return;
        resumed = true;
        quickTries = 0;
        redrawIfAged();
        if (System.currentTimeMillis() - askedAt >= HOME_AGAIN_MS || failed) ask();
        main.removeCallbacks(tick);
        main.postDelayed(tick, MIN);
        watchNetwork(true);
    }

    /** Home left the screen: no requests while an app is in front. */
    void onPause() {
        resumed = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(quick);
        watchNetwork(false);
    }

    private void ask() {
        if (fetching) return;
        fetching = true;
        askedAt = System.currentTimeMillis();
        try {
            io.execute(() -> {
                JSONObject got = null;
                try {
                    JSONObject j = new JSONObject(get(url));
                    if (j.optBoolean("ok")) got = j.optJSONObject("value");
                } catch (Throwable e) {
                    Log.w(TAG, "fetch failed: " + e);
                }
                if (got != null && pick(got).isEmpty()) got = null;   // none of our cities in it: keep the old copy
                final JSONObject answer = got;
                final long at = System.currentTimeMillis();
                if (answer != null) save(answer, at);
                main.post(() -> answered(answer, at));
            });
        } catch (Throwable e) {
            fetching = false;
            failed = true;
        }
    }

    private void answered(JSONObject answer, long at) {
        fetching = false;
        if (answer != null) {
            failed = false;
            quickTries = 0;
            value = answer;
            fetchedAt = at;
            draw();
            return;
        }
        failed = true;
        redrawIfAged();
        if (resumed && quickTries < QUICK_TRIES) {
            quickTries++;
            main.removeCallbacks(quick);
            main.postDelayed(quick, QUICK_MS);
        }
    }

    private void watchNetwork(boolean on) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            if (on && netWatch == null) {
                netWatch = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) {
                        // the TV is back online: a failed or never-made request is asked again a moment later,
                        // once DNS and the route are up
                        main.postDelayed(() -> {
                            if (resumed && (failed || value == null) && System.currentTimeMillis() - askedAt > 5_000L) ask();
                        }, 3_000L);
                    }
                };
                cm.registerDefaultNetworkCallback(netWatch, main);
            } else if (!on && netWatch != null) {
                cm.unregisterNetworkCallback(netWatch);
                netWatch = null;
            }
        } catch (Throwable e) {
            netWatch = null;
        }
    }

    // ---- where it sits --------------------------------------------------------------------------------------------

    /**
     * The dock is focused: the panel spans the dock's width, one equal column per city with its lines centred, and
     * sits centred between the top bar and the dock band.
     */
    void show(float dockTopInRoot, float topBarBottom, float dockLeft, float dockRight) {
        wanted = true;
        lastDockTop = dockTopInRoot;
        lastTopBottom = topBarBottom;
        lastLeft = dockLeft;
        lastRight = dockRight;
        if (!enabled() || cols.getChildCount() == 0) return;
        try {
            view.setVisibility(View.VISIBLE);
            if (view.getWidth() == 0 || view.getHeight() == 0) {
                view.post(this::place);
            } else {
                place();
            }
            if (!shown) view.animate().alpha(1f).setDuration(260).start();
            shown = true;
        } catch (Throwable e) {
            hideNow();
        }
    }

    private void place() {
        int width = Math.max(0, Math.round(lastRight - lastLeft));
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (width > 0 && lp != null && lp.width != width) {
            lp.width = width;
            view.setLayoutParams(lp);
            view.post(this::place);   // measured again at the new width before it is placed
            return;
        }
        float band = lastDockTop - 16 * density;   // the dock band's own padding above its tiles
        float gap = 12 * density, room = band - lastTopBottom - 2 * gap, h = view.getHeight();
        // A day with a warning or rain line under every city can be taller than the room: shrink the whole block to
        // fit rather than run into the clock or the dock.
        float scale = h > room && room > 0 ? room / h : 1f;
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(0f);
        view.setScaleX(scale);
        view.setScaleY(scale);
        float y = lastTopBottom + gap + Math.max(0f, (room - h * scale) / 2f);
        view.setX(lastLeft);
        view.setY(y);
    }

    /** Focus left the dock (the grid scrolls up under the top bar): the panel steps aside. */
    void hide() {
        wanted = false;
        if (!shown) return;
        shown = false;
        view.animate().alpha(0f).setDuration(160).start();
    }

    private void hideNow() {
        shown = false;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    /** The copy got older while Home stayed up: show its time, or hide it once it is too old to believe. */
    private void redrawIfAged() {
        if (value != null) draw();
    }

    private void draw() {
        try {
            long age = System.currentTimeMillis() - fetchedAt;
            List<JSONObject> list = pick(value);
            if (list.isEmpty() || age > HIDE_AFTER_MS || age < -HIDE_AFTER_MS) {
                cols.removeAllViews();
                hideNow();
                return;
            }
            render(list);
            updated.setText("Updated " + new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(fetchedAt)));
            updated.setVisibility(age > UPDATED_AFTER_MS ? View.VISIBLE : View.GONE);
            if (wanted) {
                view.setVisibility(View.VISIBLE);
                view.post(() -> show(lastDockTop, lastTopBottom, lastLeft, lastRight));
            }
        } catch (Throwable e) {
            Log.w(TAG, "draw failed: " + e);
            cols.removeAllViews();
            hideNow();
        }
    }

    /** The configured cities, in the configured order. */
    private List<JSONObject> pick(JSONObject v) {
        List<JSONObject> list = new ArrayList<>();
        JSONArray all = v == null ? null : v.optJSONArray("cities");
        for (String id : ids) {
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject c = all.optJSONObject(i);
                if (c != null && id.trim().equals(c.optString("id"))) list.add(c);
            }
        }
        return list;
    }

    private void render(List<JSONObject> list) {
        cols.removeAllViews();
        int warm = extreme(list, true), cool = extreme(list, false);
        for (int i = 0; i < list.size(); i++) {
            JSONObject c = list.get(i);
            JSONObject now = c.optJSONObject("now");
            JSONArray days = c.optJSONArray("days");
            JSONObject today = days != null && days.length() > 0 ? days.optJSONObject(0) : null;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

            TextView city = text(15, medium, SOFT);
            city.setLetterSpacing(0.16f);
            city.setText(c.optString("name").toUpperCase(Locale.ENGLISH));
            col.addView(city);

            Double t = num(now, "temp");
            if (t != null) {
                TextView temp = text(52, light, i == warm ? WARM : i == cool ? COOL : WHITE);
                temp.setText(Math.round(t) + "°");
                temp.setIncludeFontPadding(false);
                temp.setPadding(0, (int) (4 * density), 0, (int) (6 * density));
                col.addView(temp);
            }
            StringBuilder feel = new StringBuilder();
            Double f = num(now, "feels"), rh = num(now, "rh");
            if (f != null) feel.append("Feels ").append(Math.round(f)).append('°');
            if (rh != null) feel.append(feel.length() > 0 ? "   ·   " : "").append("Humidity ").append(Math.round(rh)).append('%');
            if (feel.length() > 0) col.addView(line(feel.toString(), DIM));
            Double hi = num(today, "hi"), lo = num(today, "lo");
            if (hi != null && lo != null) col.addView(line("High " + Math.round(hi) + "°   ·   Low " + Math.round(lo) + "°", DIM));

            JSONObject air = c.optJSONObject("air");
            Double aqi = num(air, "v");
            if (aqi != null) {
                String cat = air.optString("cat", "");
                col.addView(line("AQI " + Math.round(aqi) + (cat.isEmpty() ? "" : "   ·   " + cat), aqiColour(cat)));
            }
            String word = word(c);
            if (!word.isEmpty()) {
                TextView w = text(16, medium, c.optJSONObject("alert") != null ? WARM : WHITE);
                w.setText(word);
                w.setPadding(0, (int) (4 * density), 0, 0);
                col.addView(w);
            }
            cols.addView(col, lp);
        }
    }

    /** An IMD warning, or the feed's rain chip ("Rain now", "Rain 4 PM"); air is on its own AQI line. */
    private static String word(JSONObject c) {
        JSONObject alert = c.optJSONObject("alert");
        if (alert != null) {
            String t = alert.optString("type", "");
            return t.isEmpty() ? "IMD warning" : t;
        }
        JSONObject chip = c.optJSONObject("chip");
        if (chip == null || chip.optString("kind", "").startsWith("air")) return "";
        return chip.optString("text", "");
    }

    /** India's CPCB categories, as the feed names them: muted tints that lean towards the white text around them. */
    private static int aqiColour(String cat) {
        switch (cat.toLowerCase(Locale.ENGLISH)) {
            case "good": return 0xFFB4D3B6;
            case "satisfactory": return 0xFFCCD7B0;
            case "moderate": return 0xFFDDD2AE;
            case "poor": return 0xFFE0BFA3;
            case "very poor": return 0xFFDDA9A3;
            case "severe": return 0xFFD49AA2;
            default: return DIM;
        }
    }

    /** The index of the single warmest (or coolest) city now; -1 with fewer than two readings or a tie. */
    private static int extreme(List<JSONObject> list, boolean warmest) {
        int best = -1, count = 0;
        long bestV = 0;
        boolean tie = false;
        for (int i = 0; i < list.size(); i++) {
            Double t = num(list.get(i).optJSONObject("now"), "temp");
            if (t == null) continue;
            long v = Math.round(t);
            count++;
            if (best < 0 || (warmest ? v > bestV : v < bestV)) {
                best = i;
                bestV = v;
                tie = false;
            } else if (v == bestV) {
                tie = true;
            }
        }
        return count < 2 || tie ? -1 : best;
    }

    /** A finite number from the feed, or null (missing, null or not a number). */
    private static Double num(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        double d = o.optDouble(key, Double.NaN);
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private TextView line(String s, int colour) {
        TextView t = text(16, regular, colour);
        t.setText(s);
        t.setPadding(0, (int) (4 * density), 0, 0);
        return t;
    }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setSingleLine(true);
        t.setShadowLayer(6 * density, 0, 0, 0x66000000);
        return t;
    }

    // ---- the feed and its saved copy ------------------------------------------------------------------------------

    private void loadSaved() {
        try (InputStream in = new FileInputStream(file)) {
            JSONObject keep = new JSONObject(read(in));
            JSONObject v = keep.optJSONObject("value");
            if (v != null) {
                value = v;
                fetchedAt = keep.optLong("at");
                draw();
            }
        } catch (Throwable ignored) {
            // no saved copy yet, or a damaged one: wait for the feed
        }
    }

    private void save(JSONObject v, long at) {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("at", at).put("value", v).toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable e) {
            return;   // a full disk only costs the offline copy
        }
        if (!tmp.renameTo(file)) tmp.delete();
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return read(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > 2_000_000) throw new IllegalStateException("feed too large");
        }
        return out.toString("UTF-8");
    }
}
