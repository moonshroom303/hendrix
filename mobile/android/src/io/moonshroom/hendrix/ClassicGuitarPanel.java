package io.moonshroom.hendrix;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.Consumer;

final class ClassicGuitarPanel extends WebView {
    enum Part {
        CONTROLS("index.html", 720), STUDIO("studio.html", 460);

        final String file;
        final int height;
        Part(String file, int height) { this.file = file; this.height = height; }
    }
    private static final String STATE = "classicGuitarState";
    private static final String SILENCE = "{\"level\":-120,\"window\":0,\"bend\":0,\"attacks\":0,\"notes\":[]}";
    private static final String[] LINKS = {
            "https://moonshroom.moonshroom303.chatgpt.site/",
            "https://www.soundcloud.com/moonshroom303",
            "https://www.youtube.com/@moonshroom303",
            "https://shroomsloops-studio-231026.moonshroom303.chatgpt.site/#"
    };

    private final SharedPreferences preferences;
    private final Handler handler;
    private final Consumer<float[]> parametersChanged;
    private final Part part;
    private final String page;
    private String metrics = SILENCE;
    private boolean ready, closed;
    private int height;

    @SuppressLint("SetJavaScriptEnabled")
    ClassicGuitarPanel(Context context, Handler handler, Part part, Consumer<float[]> parametersChanged) {
        super(context);
        this.handler = handler;
        this.parametersChanged = parametersChanged;
        this.part = part;
        page = "file:///android_asset/classic/" + part.file;
        preferences = context.getSharedPreferences("Hendrix", Context.MODE_PRIVATE);
        height = pixels(part.height);
        setBackgroundColor(Color.rgb(2, 13, 19));
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setUseWideViewPort(true);
        addJavascriptInterface(new Bridge(), "HendrixClassic");
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !page.equals(request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (closed || !page.equals(url)) return;
                if (part == Part.STUDIO) {
                    evaluateJavascript("window.reportStudioSize(true)", null);
                    return;
                }
                ready = true;
                String saved = JSONObject.quote(preferences.getString(STATE, "{}"));
                evaluateJavascript("window.configureClassic(JSON.parse(" + saved + "))", null);
                evaluateJavascript("window.classicMetrics(" + metrics + ")", null);
            }
        });
        loadUrl(page);
    }

    float[] savedParameters() {
        try {
            return parameters(new JSONObject(preferences.getString(STATE, "{}")).optJSONArray("current"));
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static float[] parameters(JSONArray current) {
        if (current == null || current.length() != 20) return null;
        float[] values = new float[20];
        for (int index = 0; index < values.length; index++) {
            values[index] = (float)current.optDouble(index, Double.NaN);
            if (!Float.isFinite(values[index])) return null;
        }
        return values;
    }

    private int pixels(int points) { return Math.round(points * getResources().getDisplayMetrics().density); }

    void attach(LinearLayout parent) {
        if (getParent() instanceof android.view.ViewGroup old) old.removeView(this);
        parent.addView(this, new LinearLayout.LayoutParams(-1, height));
    }

    void updateMetrics(float[] values) {
        String next = SILENCE;
        if (values != null && values.length == 132) {
            try {
                JSONArray notes = new JSONArray();
                for (int index = 0; index < 128; index++) {
                    int velocity = (int)values[index + 4];
                    if (velocity > 0) notes.put(new JSONArray().put(index).put(velocity));
                }
                next = new JSONObject().put("level", values[0]).put("window", values[1])
                        .put("bend", values[2]).put("attacks", (long)values[3]).put("notes", notes).toString();
            } catch (JSONException ignored) {}
        }
        if (!metrics.equals(next)) {
            metrics = next;
            if (ready) evaluateJavascript("window.classicMetrics(" + metrics + ")", null);
        }
    }

    void closePanel() {
        closed = true;
        removeJavascriptInterface("HendrixClassic");
        if (getParent() instanceof android.view.ViewGroup parent) parent.removeView(this);
        destroy();
    }

    private final class Bridge {
        @JavascriptInterface public void post(String message) {
            if (message != null && message.length() < 64_000) handler.post(() -> receive(message));
        }
    }

    private void receive(String message) {
        if (closed || !page.equals(getUrl())) return;
        try {
            JSONObject payload = new JSONObject(message);
            switch (payload.optString("type")) {
                case "height":
                    int requested = payload.optInt("height");
                    if (requested >= 200 && requested <= 10000) {
                        int size = pixels(requested);
                        if (height != size) {
                            height = size;
                            if (getLayoutParams() != null) {
                                getLayoutParams().height = height;
                                requestLayout();
                            }
                        }
                    }
                    break;
                case "drag":
                    if (part != Part.CONTROLS) break;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(payload.optBoolean("active"));
                    break;
                case "state":
                    if (parametersChanged == null) break;
                    JSONObject state = payload.optJSONObject("state");
                    float[] values = state == null ? null : parameters(state.optJSONArray("current"));
                    if (values != null) {
                        preferences.edit().putString(STATE, state.toString()).apply();
                        parametersChanged.accept(values);
                    }
                    break;
                case "parameters":
                    if (parametersChanged == null) break;
                    float[] live = parameters(payload.optJSONArray("values"));
                    if (live != null) parametersChanged.accept(live);
                    break;
                case "link":
                    if (part != Part.STUDIO) break;
                    int index = payload.optInt("index", -1);
                    if (index >= 0 && index < LINKS.length) {
                        try { getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LINKS[index]))); }
                        catch (ActivityNotFoundException ignored) {}
                    }
                    break;
                default:
                    break;
            }
        } catch (JSONException ignored) {}
    }
}
