// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.theater;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The theater panel's look - theme, holiday accent and how much weather falls over the lobby -
 * as Home Assistant entities on this kiosk's ESPHome device. Reads and writes the panel's
 * /api/look; the panel saves through the same path as its admin page, so open panels follow at
 * once. Polls so a change made on the admin page shows up here too.
 */
public final class TheaterPanelPlugin implements KioskPlugin {
    private static final String[] THEMES = {"Classic", "Sofa"};
    private static final String[] THEME_IDS = {"classic", "sofa"};
    private static final String[] ACCENTS = {"Auto", "None", "Halloween", "Thanksgiving", "Christmas", "New Year", "Valentine's", "Birthday", "Winter", "Spring", "Summer", "Fall"};
    private static final String[] ACCENT_IDS = {"auto", "none", "halloween", "thanksgiving", "christmas", "newyear", "valentines", "birthday", "winter", "spring", "summer", "fall"};
    private static final String[] WEATHER = {"Off", "25%", "50%", "75%", "100%"};
    private static final int[] WEATHER_VALUES = {0, 25, 50, 75, 100};

    private PluginHost host;
    private volatile boolean alive;
    private volatile String panelUrl = "";
    private volatile String panelKey = "";
    private volatile int pollSeconds = 10;
    private ScheduledExecutorService poller;
    private ExecutorService writer;
    private String lastError;

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        alive = true;
        readSettings(settings);
        host.publishSelect("theme", "Theme", THEMES, null);
        host.publishSelect("accent", "Holiday accent", ACCENTS, null);
        host.publishSelect("weather", "Accent weather", WEATHER, null);
        host.publishTextSensor("active_accent", "Active accent", null);
        writer = Executors.newSingleThreadExecutor();
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(this::poll, 0, pollSeconds, TimeUnit.SECONDS);
    }

    @Override public void configure(Map<String, Object> settings) {
        readSettings(settings);
        if (poller != null) { poller.shutdownNow(); }
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(this::poll, 0, pollSeconds, TimeUnit.SECONDS);
    }

    private void readSettings(Map<String, Object> settings) {
        Object url = settings.get("panelUrl");
        Object key = settings.get("panelKey");
        Object poll = settings.get("pollSeconds");
        panelUrl = url == null ? "" : String.valueOf(url).trim().replaceAll("/+$", "");
        panelKey = key == null ? "" : String.valueOf(key).trim();
        pollSeconds = poll instanceof Number ? Math.max(5, Math.min(120, ((Number) poll).intValue())) : 10;
    }

    /** A Home Assistant command: change one thing on the panel, then show what the panel says. */
    @Override public void onEvent(String event, Map<String, Object> payload) {
        if (!alive) return;
        final Map<String, Object> change = new LinkedHashMap<>();
        Object option = payload.get("option");
        if (event.equals("select.theme")) change.put("theme", idFor(THEMES, THEME_IDS, option));
        else if (event.equals("select.accent")) change.put("accent", idFor(ACCENTS, ACCENT_IDS, option));
        else if (event.equals("select.weather")) {
            int i = indexOf(WEATHER, option);
            if (i >= 0) change.put("intensity", WEATHER_VALUES[i]);
        }
        if (change.isEmpty()) return;
        writer.execute(() -> {
            try { apply(request("POST", new JSONObject(change).toString())); }
            catch (Exception e) { fail("Could not change the panel: " + e.getMessage()); }
        });
    }

    private void poll() {
        if (!alive) return;
        if (panelUrl.isEmpty()) { fail("Set the panel URL in the plugin's settings."); return; }
        try { apply(request("GET", null)); }
        catch (Exception e) { fail("Panel unreachable: " + e.getMessage()); }
    }

    /** Publishes what the panel reports. Entities are only re-published when something changed. */
    private synchronized void apply(JSONObject look) {
        if (!alive) return;
        String theme = labelFor(THEME_IDS, THEMES, look.optString("theme", ""));
        String accent = labelFor(ACCENT_IDS, ACCENTS, look.optString("accent", ""));
        int intensity = look.optInt("intensity", -1);
        String weather = null;
        for (int i = 0; i < WEATHER_VALUES.length; i++) if (WEATHER_VALUES[i] == nearest(intensity)) weather = WEATHER[i];
        String active = labelFor(ACCENT_IDS, ACCENTS, look.optString("active", ""));
        host.publishSelect("theme", "Theme", THEMES, theme);
        host.publishSelect("accent", "Holiday accent", ACCENTS, accent);
        host.publishSelect("weather", "Accent weather", WEATHER, weather);
        host.publishTextSensor("active_accent", "Active accent", active == null ? "None" : active);
        if (lastError != null) { lastError = null; }
        host.status("Theme " + theme + ", accent " + accent + (active != null ? " (" + active + " now)" : "") + ", weather " + (weather == null ? "?" : weather) + ".", false);
    }

    private void fail(String message) {
        if (!alive || message.equals(lastError)) return;
        lastError = message;
        host.status(message, true);
    }

    private JSONObject request(String method, String body) throws Exception {
        String sep = panelUrl.contains("?") ? "&" : "?";
        String url = panelUrl + "/api/look" + (panelKey.isEmpty() ? "" : sep + "key=" + URLEncoder.encode(panelKey, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = c.getResponseCode();
        StringBuilder text = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(status < 400 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) text.append(line);
        }
        if (status == 401) throw new Exception("the panel key was refused");
        if (status >= 400) {
            String why = text.length() > 0 ? new JSONObject(text.toString()).optString("error", "HTTP " + status) : "HTTP " + status;
            throw new Exception(why);
        }
        return new JSONObject(text.toString());
    }

    private static int nearest(int value) {
        if (value < 0) return -1;
        int best = WEATHER_VALUES[0];
        for (int v : WEATHER_VALUES) if (Math.abs(v - value) < Math.abs(best - value)) best = v;
        return best;
    }
    private static int indexOf(String[] xs, Object x) { for (int i = 0; i < xs.length; i++) if (xs[i].equals(x)) return i; return -1; }
    private static String idFor(String[] labels, String[] ids, Object label) { int i = indexOf(labels, label); return i < 0 ? null : ids[i]; }
    private static String labelFor(String[] ids, String[] labels, String id) { int i = indexOf(ids, id); return i < 0 ? null : labels[i]; }

    @Override public void execute(String command, Map<String, Object> arguments) {
        if (command.equals("refresh")) { if (poller != null) poller.execute(this::poll); return; }
        throw new IllegalArgumentException("Unknown command " + command);
    }

    @Override public void stop() {
        alive = false;
        if (poller != null) poller.shutdownNow();
        if (writer != null) writer.shutdownNow();
    }
}
