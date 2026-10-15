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

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /** L'adresse IPv4 de la tablette sur le reseau actif. */
    private String monAdresse() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) return la.getAddress().getHostAddress();
            }
        } catch (Exception e) { /* pas de reseau */ }
        return null;
    }

    /**
     * Les imprimantes du reseau local (meme /24), 64 adresses essayees a la fois.
     * Pour chaque appareil qui ecoute sur le port 9100 :
     *  - s'il ecoute aussi sur 631 (IPP), c'est une imprimante de bureau : on
     *    ne lui envoie RIEN (sinon elle pourrait sortir une page blanche) ;
     *  - sinon on lui demande son etat (DLE EOT 1) : seules les imprimantes a
     *    tickets ESC/POS repondent, par un octet de la forme 0xx1xx10 ;
     *  - si c'en est une, on lui demande son nom (GS I 67), quand elle le donne.
     * Renvoie [{ip, ticket, bureau, nom}].
     */
    private List<JSONObject> scanner() {
        List<JSONObject> trouves = new ArrayList<>();
        String moi = monAdresse();
        if (moi == null) return trouves;
        String base = moi.substring(0, moi.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(64);
        List<Future<JSONObject>> essais = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            final String ip = base + i;
            if (ip.equals(moi)) continue;
            essais.add(pool.submit(() -> sonder(ip)));
        }
        for (Future<JSONObject> f : essais) {
            try { JSONObject r = f.get(6, TimeUnit.SECONDS); if (r != null) trouves.add(r); } catch (Exception ignore) { }
        }
        pool.shutdownNow();
        return trouves;
    }

    private boolean ouvert(String ip, int port, int delai) {
        Socket s = new Socket();
        try { s.connect(new InetSocketAddress(ip, port), delai); return true; }
        catch (Exception e) { return false; }
        finally { try { s.close(); } catch (Exception ignore) { } }
    }

    private JSONObject sonder(String ip) {
        if (!ouvert(ip, 9100, 600)) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("ip", ip);
            boolean bureau = ouvert(ip, 631, 400);
            o.put("bureau", bureau);
            o.put("ticket", false);
            o.put("nom", "");
            if (bureau) return o;
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(ip, 9100), 800);
                s.setSoTimeout(800);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                out.write(new byte[] { 0x10, 0x04, 0x01 });   // DLE EOT 1 : etat de l'imprimante
                out.flush();
                int b = in.read();
                boolean ticket = b >= 0 && (b & 0x93) == 0x12;
                o.put("ticket", ticket);
                if (ticket) {
                    out.write(new byte[] { 0x1D, 0x49, 0x43 }); // GS I 67 : nom du modele
                    out.flush();
                    StringBuilder nom = new StringBuilder();
                    try {
                        for (int k = 0; k < 40; k++) {
                            int c = in.read();
                            if (c <= 0) break;
                            if (c >= 0x20 && c < 0x7F && c != 0x5F) nom.append((char) c);
                        }
                    } catch (Exception fini) { /* pas de nom : ce n'est pas grave */ }
                    o.put("nom", nom.toString().trim());
                }
            } catch (Exception e) { /* pas de reponse : on la laisse « inconnue » */ }
            finally { try { s.close(); } catch (Exception ignore) { } }
        } catch (Exception e) { return null; }
        return o;
    }

    /** Ce que la page Cuisine peut demander a la tablette. */
    private class Imprimante {

        /** Version du pont : la page sait ainsi qu'elle tourne dans l'appli. */
        @JavascriptInterface
        public String version() { return "2"; }

        /**
         * Cherche les imprimantes a tickets du reseau local : tout appareil qui
         * repond sur le port 9100 (le port des imprimantes ESC/POS). Ne bloque
         * pas la page : la liste arrive ensuite dans window[rappel](liste).
         */
        @JavascriptInterface
        public void chercher(final String rappel) {
            if (rappel == null || !rappel.matches("[A-Za-z0-9_]{1,40}")) return;
            new Thread(() -> {
                JSONArray liste = new JSONArray();
                for (JSONObject o : scanner()) liste.put(o);
                fil.post(() -> web.evaluateJavascript("window." + rappel + "(" + liste.toString() + ")", null));
            }).start();
        }

        /** L'adresse de la tablette sur le reseau du restaurant (vide si pas de reseau). */
        @JavascriptInterface
        public String adresseLocale() {
            String a = monAdresse();
            return a == null ? "" : a;
        }

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
