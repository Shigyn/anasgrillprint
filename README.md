# Applis Cuisine LocWeb

L'appli de la tablette de cuisine d'un restaurant client : elle affiche sa
page Cuisine (commandes en direct, sonnerie, boutons Prête / Remise, pause,
plats épuisés) et **imprime les tickets toute seule** sur l'imprimante à
tickets du restaurant, par le réseau, sans fenêtre « Imprimer ».

Un seul code pour tous les clients. **Chaque restaurant a son appli, à son
nom et à son icône**, construite depuis sa fiche dans `clients/`.

## Ajouter un restaurant

1. Une icône carrée de 512 px en PNG dans `clients/`, par exemple `clients/ksm.png`.
2. Une fiche `clients/ksm.json` :

```json
{
  "nom": "KSM Burger",
  "id": "fr.locweb.cuisine.ksm",
  "url": "https://exemple.fr/cuisine/",
  "icone": "ksm.png"
}
```

3. Envoyer sur `main`. GitHub construit toutes les applis et publie une
   release avec un `.apk` par restaurant (page **Releases** du dépôt).

`id` doit être unique et **ne plus jamais changer** pour un restaurant :
c'est ce qui permet les mises à jour par-dessus l'appli installée.

## Ce que la page Cuisine doit faire

L'appli expose `window.LocWebImprimante` :
- `version()` : présent si la page tourne dans l'appli ;
- `imprimer(adresse, port, ticketBase64)` : envoie les octets ESC/POS à
  l'imprimante (port 9100 en général) ; renvoie `"ok"` ou le message d'erreur.

Voir `anas-grill/assets/cuisine.js` (mode d'impression « Appli »).

## Installer sur la tablette

1. Brancher l'imprimante à la box du restaurant (câble réseau) et noter son
   adresse : imprimante éteinte, garder FEED appuyé en l'allumant, elle
   imprime ses réglages (ex. `192.168.1.87`).
2. Sur la tablette Android, ouvrir la page Releases de ce dépôt et
   télécharger l'apk du restaurant ; accepter l'installation.
3. Ouvrir l'appli, taper le code du snack, puis **Réglages** : impression
   « Appli », adresse de l'imprimante, **Ticket de test**.
4. Réglages Android : désactiver l'économie de batterie pour l'appli,
   laisser la tablette branchée.

## La clé de signature

Hors du dépôt : `LocWeb/cles-android/anasgrillprint.p12` et son mot de
passe (`anasgrillprint-mot-de-passe.txt`). Trois secrets GitHub :
`CLE_ANDROID_BASE64`, `CLE_ANDROID_MDP`, `CLE_ANDROID_ALIAS`
(`anasgrillprint`). **La perdre empêche toute mise à jour** : Android refuse
une version signée par une autre clé. À sauvegarder ailleurs que sur le poste.
