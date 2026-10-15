package fr.locweb.cuisine;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import android.os.Build;
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
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
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

    private static final String ACTION_MAJ = "fr.locweb.cuisine.MISE_A_JOUR";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!ACTION_MAJ.equals(intent.getAction())) return;
        int statut = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (statut == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android affiche « Mettre a jour cette appli ? » : un seul toucher.
            Intent confirmer = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmer != null) startActivity(confirmer);
            return;
        }
        String msg = statut == PackageInstaller.STATUS_SUCCESS ? "ok" : String.valueOf(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        appeler("__agMajEtat", JSONObject.quote(msg));
    }

    private void appeler(String fonction, String arg) {
        fil.post(() -> web.evaluateJavascript("window." + fonction + " && window." + fonction + "(" + arg + ")", null));
    }

    /** La derniere version publiee (releases GitHub du depot), pour CE restaurant. */
    private JSONObject derniereVersion() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + BuildConfig.DEPOT + "/releases/latest").openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] t = new byte[8192]; int n;
        while ((n = in.read(t)) > 0) b.write(t, 0, n);
        in.close();
        JSONObject r = new JSONObject(b.toString("UTF-8"));
        int version = Integer.parseInt(r.getString("tag_name").replaceAll("[^0-9]", ""));
        String url = "";
        JSONArray assets = r.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").equals(BuildConfig.CLIENT + "-cuisine.apk")) url = a.getString("browser_download_url");
        }
        JSONObject o = new JSONObject();
        o.put("version", version);
        o.put("actuelle", BuildConfig.VERSION_CODE);
        o.put("disponible", version > BuildConfig.VERSION_CODE && !url.isEmpty());
        o.put("url", url);
        return o;
    }

    /** Telecharge la nouvelle version et la confie a l'installateur Android. */
    private void installer(String url) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("maj.apk", 0, -1)) {
            byte[] t = new byte[65536]; int n;
            while ((n = in.read(t)) > 0) out.write(t, 0, n);
            session.fsync(out);
        }
        Intent retour = new Intent(this, MainActivity.class).setAction(ACTION_MAJ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int drapeaux = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
        PendingIntent pend = PendingIntent.getActivity(this, 0, retour, drapeaux);
        session.commit(pend.getIntentSender());
        session.close();
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
        public String version() { return "3"; }

        /** Le numero de version de l'appli installee. */
        @JavascriptInterface
        public int versionAppli() { return BuildConfig.VERSION_CODE; }

        /** Y a-t-il une nouvelle version ? Reponse dans window[rappel]({disponible, version, actuelle, url}). */
        @JavascriptInterface
        public void verifierMiseAJour(final String rappel) {
            if (rappel == null || !rappel.matches("[A-Za-z0-9_]{1,40}")) return;
            new Thread(() -> {
                String r;
                try { r = derniereVersion().toString(); } catch (Exception e) { r = "{\"erreur\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
                appeler(rappel, r);
            }).start();
        }

        /** Telecharge et installe la nouvelle version ; l'avancement arrive dans window.__agMajEtat(message). */
        @JavascriptInterface
        public void mettreAJour(final String url) {
            if (url == null || !url.startsWith("https://github.com/" + BuildConfig.DEPOT + "/")) return;
            new Thread(() -> {
                try { installer(url); }
                catch (Exception e) { appeler("__agMajEtat", JSONObject.quote("Erreur : " + e.getMessage())); }
            }).start();
        }

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
