
package com.monentreprise.tradertools;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "tt_prefs";
    private static final String CLE_PLANTAGE = "dernier_plantage";
    private static final int REQ_SAVE = 2001;
    private static final int REQ_OPEN = 2002;
    private static final int MAX_IMPORT = 10 * 1024 * 1024;   // 10 Mo
    private static final int MAX_HTTP = 3 * 1024 * 1024;      // 3 Mo
    private static final Pattern ID_OK = Pattern.compile("[A-Za-z0-9_]{1,32}");
    // Seuls ces serveurs de données de marché peuvent être interrogés par l'application
    private static final Set<String> HOTES_OK = new HashSet<>(Arrays.asList(
            "data-api.binance.vision", "query1.finance.yahoo.com", "query2.finance.yahoo.com", "stooq.com"));

    private WebView webView;
    private String sauvegardeEnAttente;
    private String mimeEnAttente;
    private final ExecutorService reseau = Executors.newFixedThreadPool(3);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        installerRapportPlantage();   // avant tout le reste, pour capter aussi une erreur de démarrage
        super.onCreate(savedInstanceState);

        try {
            webView = new WebView(this);
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowContentAccess(false);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return !url.startsWith("file:///android_asset/");
                }

                @RequiresApi(Build.VERSION_CODES.O)
                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    // Le moteur d'affichage a été arrêté par Android (mémoire) : on recharge sans fermer l'application
                    Toast.makeText(MainActivity.this, "Affichage rechargé", Toast.LENGTH_SHORT).show();
                    runOnUiThread(() -> recreate());
                    return true;
                }
            });

            // Sans WebChromeClient, alert() et confirm() ne s'affichent pas
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                    new AlertDialog.Builder(MainActivity.this).setMessage(message)
                            .setPositiveButton("OK", (d, w) -> result.confirm())
                            .setOnCancelListener(d -> result.cancel()).show();
                    return true;
                }

                @Override
                public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                    new AlertDialog.Builder(MainActivity.this).setMessage(message)
                            .setPositiveButton("OK", (d, w) -> result.confirm())
                            .setNegativeButton("Annuler", (d, w) -> result.cancel())
                            .setOnCancelListener(d -> result.cancel()).show();
                    return true;
                }
            });

            webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
            webView.loadUrl("file:///android_asset/index.html");
            setContentView(webView);
        } catch (Throwable t) {
            afficherErreurFatale(t);
            return;
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        afficherDernierPlantage();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        reseau.shutdownNow();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }

    // ---------- Détecteur de plantage : l'erreur exacte est affichée au lancement suivant ----------
    private void installerRapportPlantage() {
        final Thread.UncaughtExceptionHandler ancien = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String txt = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") "
                        + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + sw;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CLE_PLANTAGE, txt).commit();
            } catch (Throwable ignore) { }
            if (ancien != null) ancien.uncaughtException(th, ex);
        });
    }

    private void afficherDernierPlantage() {
        final String txt = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CLE_PLANTAGE, null);
        if (txt == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(CLE_PLANTAGE).apply();
        new AlertDialog.Builder(this)
                .setTitle("Le dernier lancement a planté")
                .setMessage(txt)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Copier", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("plantage", txt));
                    Toast.makeText(this, "Copié : collez-le dans votre message", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void afficherErreurFatale(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextView tv = new TextView(this);
        tv.setPadding(32, 48, 32, 32);
        tv.setTextColor(0xFFE2E8F0);
        tv.setBackgroundColor(0xFF0F172A);
        tv.setText("Impossible d'afficher l'application (composant WebView).\n"
                + "Mettez à jour « Android System WebView » dans le Play Store.\n\n" + sw);
        setContentView(tv);
    }

    /** Exécute du JavaScript dans la page, depuis n'importe quel thread. */
    private void js(final String code) {
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(code, null); });
    }

    private static String lire(InputStream in, int max) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new Exception("Réponse trop volumineuse");
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAVE) {
            boolean ok = false;
            if (res == RESULT_OK && data != null && data.getData() != null && sauvegardeEnAttente != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (os != null) {
                        os.write(sauvegardeEnAttente.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception e) {
                    ok = false;
                }
            }
            sauvegardeEnAttente = null;
            if (res == RESULT_OK) js("window.__onSaved(" + ok + ")");
        } else if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
            try (InputStream is = getContentResolver().openInputStream(data.getData())) {
                js("window.__onPicked(" + JSONObject.quote(lire(is, MAX_IMPORT)) + ")");
            } catch (Exception e) {
                js("window.__onPicked(null)");
            }
        }
    }

    /** Pont JavaScript <-> Android. */
    public class Bridge {
        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        /** Requête HTTPS vers un serveur de données autorisé (évite les blocages CORS de la WebView). */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            if (id == null || !ID_OK.matcher(id).matches()) return;
            reseau.execute(() -> {
                int code;
                String body;
                try {
                    URL u = new URL(url);
                    if (!"https".equals(u.getProtocol()) || !HOTES_OK.contains(u.getHost())) {
                        throw new SecurityException("Serveur non autorisé");
                    }
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TradeTools/1.0");
                    c.setRequestProperty("Accept", "application/json,text/csv,*/*");
                    code = c.getResponseCode();
                    body = lire(code >= 400 ? c.getErrorStream() : c.getInputStream(), MAX_HTTP);
                    c.disconnect();
                } catch (SecurityException e) {
                    code = -1;
                    body = String.valueOf(e.getMessage());
                } catch (Exception e) {
                    code = 0;
                    body = String.valueOf(e.getMessage());
                }
                js("window.__http('" + id + "'," + code + "," + JSONObject.quote(body) + ")");
            });
        }

        /** Enregistre un fichier texte choisi par l'utilisateur (sauvegarde JSON, export CSV). */
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void saveFile(final String filename, final String mime, final String content) {
            sauvegardeEnAttente = content;
            mimeEnAttente = (mime == null || mime.isEmpty()) ? "application/json" : mime;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mimeEnAttente);
                i.putExtra(Intent.EXTRA_TITLE, filename);
                startActivityForResult(i, REQ_SAVE);
            });
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void pickFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_OPEN);
            });
        }
    }
}
