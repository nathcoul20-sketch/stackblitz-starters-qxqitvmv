# Foot Live — application Android (Maybéléger FC)

Filmer un match de foot en direct (Twitch, ou serveur RTMP perso) avec un tableau de score
et un chrono incrustés dans la vidéo, pilotés depuis un 2e téléphone.
Version foot de Volley Live, aux couleurs du Maybéléger FC (jaune, noir, bleu nuit).
Elle s'installe à côté de Volley Live (identifiant `com.footlive.app`), sans la remplacer.

## Principe

```
 Téléphone 1 (caméra, app Foot Live)              Téléphone 2 (télécommande)
 ┌───────────────────────────────────┐            ┌──────────────────────────┐
 │ caméra + micro                    │   Wi-Fi /  │ navigateur web           │
 │   + overlay score (OpenGL)  ──────┼── point ───┤ http://<ip>:8080         │
 │   → encodage H.264/AAC            │  d'accès   │ gros boutons +1 / −1 ... │
 │   → RTMP(S) vers Twitch          │            └──────────────────────────┘
 │ serveur web intégré (port 8080)   │
 └───────────────────────────────────┘
```

- La **télécommande n'a rien à installer** : c'est une page web servie par le téléphone qui filme
  (fonctionne aussi depuis un iPhone). On s'y connecte en scannant le QR code.
- Les deux téléphones doivent être sur le **même réseau local** : même Wi-Fi, ou bien
  activer le **partage de connexion** sur le téléphone qui filme (il diffuse en 4G/5G et
  la télécommande se connecte à son point d'accès).
- Un **code PIN** à 4 chiffres protège la télécommande (inclus dans le QR code).

## Utilisation

1. Lancer l'app, coller la **clé de stream** Twitch, saisir les équipes, la compétition et la
   **durée d'une mi-temps** (45 min en seniors, moins chez les jeunes).
2. « Ouvrir la caméra » → l'aperçu s'affiche avec l'écran d'intro.
3. Bouton **Télécommande** → scanner le QR code avec le 2e téléphone.
4. Bouton **● LIVE** pour démarrer le direct.

### Télécommande

- **Chrono** : Départ / Pause / Reprendre, corrections −1 min, −10 s, +10 s, +1 min.
  Le chrono tourne sur le téléphone qui filme (la télécommande peut se déconnecter sans rien perdre).
  À la fin du temps réglementaire, il reste sur « 45:00 » et le **temps additionnel** s'affiche à côté (« +2:13 »).
- **But** : grand bouton par équipe. La minute est notée automatiquement (« 23' », « 45+2' ») et la télécommande
  propose de saisir le buteur (facultatif, modifiable ensuite dans « Buts du match »). Un bandeau « BUT ! »
  s'affiche 10 secondes sous le tableau de score.
- **Cartons rouges** : petits cartons à côté du nom de l'équipe.
- **Fin 1re mi-temps** : arrête le chrono, bascule sur l'écran **Mi-temps** et prépare la 2e mi-temps (départ à 45:00).
  **Fin du match** : arrête le chrono et affiche l'écran de **Fin** (score + buteurs).
- Réglages : noms, compétition, durée des mi-temps, masquer l'overlay, **Prolongations** (2 × 15 min, ou un tiers
  de la mi-temps chez les jeunes), **Tirs au but** (compteur affiché sous le tableau de score), nouveau match.
- **Annuler** défait la dernière action (but, carton, fin de période…) sans toucher au chrono.

Le bouton **Score** du téléphone qui filme affiche des boutons de secours (but, chrono, fin de période).

### Titre du live Twitch

Comme Volley Live : « Compétition | DOMICILE vs VISITEUR », après avoir connecté le compte Twitch
(Client ID d'une application créée sur https://dev.twitch.tv/console/apps, type **Public**).

### Écrans

Quatre écrans, choisis depuis la télécommande ou les boutons à gauche du téléphone qui filme :
- **Intro** : « Le match va commencer » en boucle (écran affiché à l'ouverture de la caméra) ;
- **Match** : caméra + tableau de score + chrono ;
- **Mi-temps** : score et buteurs ;
- **Fin** : « Fin du match », score, tirs au but éventuels et buteurs.

Chaque changement d'écran joue la transition Maybéléger FC (volets noir et jaune + blason).
En Intro, Mi-temps et Fin, la musique (`res/raw/music.m4a`) remplace le micro (évite les droits d'auteur
sur la musique de la buvette ou de la sono) ; en Match, le micro revient.

Fichiers graphiques (générés par `tools/visuels/`, voir plus bas) : `res/raw/intro.mp4`, `halftime.mp4`,
`outro.mp4` (boucles de 10 à 12 s), `res/raw/transition.mp4`. Le tableau de score est dessiné par le code
(`OverlayRenderer.kt`). Polices : Bebas Neue et Josefin Sans (licence SIL OFL) dans `assets/fonts/`.

### Modifier les visuels

```bash
cd tools/visuels
python3 hd_logo.py logo.png logo_mbl_hd.png   # agrandit le blason sans flou, fond transparent
./build_screens.sh                            # régénère intro / mi-temps / fin (Pillow + ffmpeg)
python3 overlay_preview.py ../../app/src/main/assets/fonts apercu.png   # aperçu du tableau de score
```
La transition se régénère avec `tools/transition/gen.py` (voir son README).

Bon à savoir :
- L'écran reste allumé pendant le live ; verrouiller le téléphone ou quitter l'app coupe le direct.
- 720p (≈ 2,5 Mb/s) est conseillé en 4G ; 1080p (≈ 4,5 Mb/s) sur une bonne connexion.
- En cas de coupure réseau, l'app retente automatiquement la connexion (10 essais).

## Récupérer l'APK

Chaque push sur ce dossier lance le workflow GitHub Actions **Android APK**
(`.github/workflows/android.yml`). Dans l'onglet *Actions* du dépôt → dernier run →
section *Artifacts* → `FootLive-apk` (zip contenant l'APK). L'installer sur le téléphone
(autoriser « sources inconnues »).

L'APK est signé avec une clé fixe (`app/debug.keystore`) : les nouvelles versions
s'installent par-dessus les anciennes.

## Code

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Écran de réglages (plateforme, clé, équipes) |
| `StreamActivity.kt` | Caméra, live RTMP, overlay, boutons, QR code |
| `OverlayRenderer.kt` | Tableau de score, chrono, bandeau « BUT ! », textes des écrans Mi-temps / Fin |
| `SceneController.kt` | Écrans (intro / match / mi-temps / fin), vidéos, transition, rafraîchissement du chrono |
| `AlphaVideoFilterRender.kt` | Calque vidéo avec transparence (transition) |
| `AudioMixer.kt` | Son du live : micro, musique, bruitage |
| `TwitchApi.kt` | Connexion au compte Twitch et changement du titre du live |
| `ScoreManager.kt` / `ScoreState.kt` | État du match (buts, chrono, périodes, cartons, TAB), actions, historique (annuler) |
| `RemoteServer.kt` | Serveur web de la télécommande (API `/api/state`, `/api/action`, `/api/config`) |
| `assets/remote.html` | Interface de la télécommande |

Streaming : [RootEncoder](https://github.com/pedroSG94/RootEncoder) · serveur : NanoHTTPD · QR : ZXing.

Compilation locale : ouvrir le dossier `android/` dans Android Studio, ou `./gradlew assembleRelease`.
