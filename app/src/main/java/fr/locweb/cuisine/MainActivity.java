package fr.locweb.cuisine;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * LocWeb Cuisine — la tablette de cuisine d'un restaurant client.
 *
 * Une seule chose en plus d'un navigateur : envoyer les tickets a
 * l'imprimante a tickets (ESC/POS) par le reseau du restaurant, sans
 * fenetre « Imprimer ». La page Cuisine (sur le site du client) appelle
 * window.LocWebImprimante.imprimer(adresse, port, ticketEnBase64).
 *
 * L'appli est la meme pour tous les clients : le nom, l'adresse de la page
 * et l'icone viennent du fichier clients/<client>.json au moment de la
 * construction (voir le README).
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler fil = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        // L'ecran reste allume tant que l'appli est ouverte : les commandes doivent se voir.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                       // le code du snack reste memorise
        s.setMediaPlaybackRequiresUserGesture(false);       // la sonnerie des nouvelles commandes
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Imprimante(), "LocWebImprimante");

        final Uri depart = Uri.parse(BuildConfig.START_URL);
        web.setWebViewClient(new WebViewClient() {
            // On reste sur le site du client ; tout autre lien (itineraire, telephone...) s'ouvre a cote.
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (depart.getHost() != null && depart.getHost().equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* rien a ouvrir */ }
                return true;
            }

            // Pas de reseau au demarrage : on reessaie tout seul toutes les 15 secondes.
            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) fil.postDelayed(() -> web.reload(), 15000);
            }
        });
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (etat != null) web.restoreState(etat); else web.loadUrl(BuildConfig.START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle etat) {
        super.onSaveInstanceState(etat);
        web.saveState(etat);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /** Ce que la page Cuisine peut demander a la tablette. */
    private class Imprimante {

        /** Version du pont : la page sait ainsi qu'elle tourne dans l'appli. */
        @JavascriptInterface
        public String version() { return "1"; }

        /**
         * Envoie un ticket (octets ESC/POS en base64) a l'imprimante, port 9100
         * en general. Renvoie « ok » ou le message d'erreur a afficher.
         * Appele hors du fil principal : le reseau y est permis.
         */
        @JavascriptInterface
        public String imprimer(String adresse, int port, String base64) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(adresse.trim(), port > 0 ? port : 9100), 4000);
                s.setSoTimeout(5000);
                OutputStream o = s.getOutputStream();
                o.write(Base64.decode(base64, Base64.DEFAULT));
                o.flush();
                return "ok";
            } catch (Exception e) {
                return "Imprimante injoignable (" + adresse + ") : " + e.getMessage();
            } finally {
                try { s.close(); } catch (Exception ignore) { }
            }
        }
    }
}
