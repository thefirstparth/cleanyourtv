package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
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

/**
 * The weather corner above the dock, read from house-of-1400's four-city weather feed (/api/live/wx), which the site
 * caches for 15 minutes. config.xml weather_url (empty: off, nothing is ever requested) and weather_cities (the feed's
 * city ids, in the order shown). One request when Home appears, at most every ten minutes; the last good answer is
 * kept in a file, so Home still shows it (with its time) when the network or the site is down.
 */
final class WeatherPanel {
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final long STALE_MS = 30 * 60 * 1000L;
    private static final int WARM = 0xFFFFB27A, COOL = 0xFF8EC9FF, WHITE = 0xFFFFFFFF, DIM = 0xB3FFFFFF;

    private final Context ctx;
    private final String url;
    private final String[] ids;
    private final float density;
    private final Typeface light, regular, medium;
    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout view;
    private final LinearLayout cols;
    private final TextView asOf;
    private long fetchedAt;
    private boolean fetching;
    private boolean shown;
    /** The dock is focused (show() was called), so a first answer arriving later appears without a key press. */
    private boolean wanted;
    private float lastDockTop, lastLeft;

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
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        view.addView(cols);
        asOf = text(12, regular, DIM);
        asOf.setLetterSpacing(0.08f);
        asOf.setPadding(0, (int) (8 * density), 0, 0);
        asOf.setVisibility(View.GONE);
        view.addView(asOf);
        if (enabled()) root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    boolean enabled() {
        return !url.isEmpty() && ids.length > 0;
    }

    /** Home appeared: draw the saved copy at once, and ask the feed again when the copy is over ten minutes old. */
    void refresh() {
        if (!enabled()) return;
        if (fetchedAt == 0) loadSaved();
        if (fetching || System.currentTimeMillis() - fetchedAt < REFRESH_MS) return;
        fetching = true;
        new Thread(() -> {
            JSONObject got = null;
            try {
                JSONObject j = new JSONObject(get(url));
                if (j.optBoolean("ok")) got = j.optJSONObject("value");
            } catch (Exception e) {
                // offline or the site is down: the saved copy stays, marked with its time
                Log.w("WeatherPanel", "fetch failed: " + e);
            }
            final JSONObject value = got;
            final long at = System.currentTimeMillis();
            if (value != null) save(value, at);
            main.post(() -> {
                fetching = false;
                if (value != null) {
                    fetchedAt = at;
                    render(value);
                }
            });
        }, "weather").start();
    }

    /** The dock is focused: the panel sits above it, left-aligned with the clock. */
    void show(float dockTopInRoot, float left) {
        wanted = true;
        lastDockTop = dockTopInRoot;
        lastLeft = left;
        if (!enabled() || cols.getChildCount() == 0) return;
        view.setVisibility(View.VISIBLE);
        view.setX(left);
        float y = dockTopInRoot - 48 * density - view.getHeight();
        if (view.getHeight() == 0) {
            view.post(() -> view.setY(dockTopInRoot - 48 * density - view.getHeight()));
        } else {
            view.setY(y);
        }
        if (!shown) view.animate().alpha(1f).setDuration(260).start();
        shown = true;
    }

    /** Focus left the dock (the grid scrolled up under the top bar): the panel steps aside. */
    void hide() {
        wanted = false;
        if (!shown) return;
        shown = false;
        view.animate().alpha(0f).setDuration(160).start();
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    private void render(JSONObject value) {
        JSONArray all = value.optJSONArray("cities");
        List<JSONObject> list = new ArrayList<>();
        for (String id : ids) {
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject c = all.optJSONObject(i);
                if (c != null && id.trim().equals(c.optString("id"))) list.add(c);
            }
        }
        cols.removeAllViews();
        int warm = extreme(list, true), cool = extreme(list, false);
        for (int i = 0; i < list.size(); i++) {
            JSONObject c = list.get(i);
            JSONObject now = c.optJSONObject("now");
            JSONArray days = c.optJSONArray("days");
            JSONObject today = days != null && days.length() > 0 ? days.optJSONObject(0) : null;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(i == 0 ? 0 : (int) (56 * density));
            col.setMinimumWidth((int) (150 * density));

            TextView city = text(13, medium, DIM);
            city.setLetterSpacing(0.16f);
            city.setText(c.optString("name").toUpperCase(Locale.ENGLISH));
            col.addView(city);

            if (now != null && now.has("temp") && !now.isNull("temp")) {
                TextView temp = text(46, light, i == warm ? WARM : i == cool ? COOL : WHITE);
                temp.setText(Math.round(now.optDouble("temp")) + "°");
                temp.setIncludeFontPadding(false);
                temp.setPadding(0, (int) (2 * density), 0, (int) (2 * density));
                col.addView(temp);
            }
            StringBuilder feel = new StringBuilder();
            if (now != null && now.has("feels") && !now.isNull("feels")) feel.append("Feels ").append(Math.round(now.optDouble("feels"))).append('°');
            if (now != null && now.has("rh") && !now.isNull("rh")) {
                if (feel.length() > 0) feel.append("  ·  ");
                feel.append("Humidity ").append(now.optInt("rh")).append('%');
            }
            if (feel.length() > 0) col.addView(line(feel.toString()));
            if (today != null && today.has("hi") && today.has("lo") && !today.isNull("hi") && !today.isNull("lo")) {
                col.addView(line("H " + Math.round(today.optDouble("hi")) + "°  ·  L " + Math.round(today.optDouble("lo")) + "°"));
            }
            String word = word(c);
            if (!word.isEmpty()) {
                TextView w = text(14, medium, c.optJSONObject("alert") != null ? WARM : WHITE);
                w.setText(word);
                w.setPadding(0, (int) (4 * density), 0, 0);
                col.addView(w);
            }
            cols.addView(col, lp);
        }
        boolean stale = System.currentTimeMillis() - fetchedAt > STALE_MS;
        asOf.setText("AS OF " + new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(fetchedAt)).toUpperCase(Locale.ENGLISH));
        asOf.setVisibility(stale && !list.isEmpty() ? View.VISIBLE : View.GONE);
        if (list.isEmpty()) {
            view.setVisibility(View.GONE);
        } else if (wanted) {
            view.setVisibility(View.VISIBLE);
            // the columns' height changed: place the panel again once it has been measured
            view.post(() -> show(lastDockTop, lastLeft));
        }
    }

    /** An IMD warning beats the feed's chip ("Rain 4 PM", "Air very poor"). */
    private static String word(JSONObject c) {
        JSONObject alert = c.optJSONObject("alert");
        if (alert != null) {
            String t = alert.optString("type", "");
            return t.isEmpty() ? "IMD warning" : t;
        }
        JSONObject chip = c.optJSONObject("chip");
        return chip == null ? "" : chip.optString("text", "");
    }

    /** The index of the single warmest (or coolest) city now; -1 with fewer than two readings or a tie. */
    private static int extreme(List<JSONObject> list, boolean warmest) {
        int best = -1, count = 0;
        double bestV = 0;
        boolean tie = false;
        for (int i = 0; i < list.size(); i++) {
            JSONObject now = list.get(i).optJSONObject("now");
            if (now == null || !now.has("temp") || now.isNull("temp")) continue;
            long v = Math.round(now.optDouble("temp"));
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

    private TextView line(String s) {
        TextView t = text(14, regular, DIM);
        t.setText(s);
        return t;
    }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
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
                fetchedAt = keep.optLong("at");
                render(v);
            }
        } catch (Exception ignored) {
            // no saved copy yet
        }
    }

    private void save(JSONObject value, long at) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new JSONObject().put("at", at).put("value", value).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // a full disk only costs the offline copy
        }
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
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
