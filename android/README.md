# Volley Live — application Android

Filmer un match de volley en direct (Twitch, ou serveur RTMP perso) avec
un tableau de score incrusté dans la vidéo, mis à jour depuis un 2e téléphone.

## Principe

```
 Téléphone 1 (caméra, app Volley Live)            Téléphone 2 (télécommande)
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

1. Lancer l'app, coller la **clé de stream** Twitch (Tableau de bord des créateurs →
   Paramètres → Stream), saisir les noms des équipes et la compétition.
2. « Ouvrir la caméra » → l'aperçu s'affiche avec l'overlay.
3. Bouton **Télécommande** → scanner le QR code avec le 2e téléphone.
4. Bouton **● LIVE** pour démarrer le direct.

Sur la télécommande : gros boutons « +1 point » par équipe, −1, sets, « Fin du set »
(clignote en jaune quand un set est mathématiquement gagné : 25 pts / 15 au 5e set, 2 pts d'écart),
« Annuler » (annule la dernière action), réglages (noms, masquer l'overlay, nouveau match).

Le bouton **Score** sur le téléphone qui filme affiche aussi des boutons de secours.

### Titre du live Twitch

Avec Twitch, l'appli peut changer le titre du live au moment d'ouvrir la caméra :
« Compétition | DOMICILE vs VISITEUR ». Il faut une fois pour toutes :
1. créer une application sur https://dev.twitch.tv/console/apps (type de client **Public**,
   URL de redirection `http://localhost`, catégorie *Broadcaster Suite*) et copier son **Client ID** ;
2. dans Volley Live (plateforme Twitch), coller le Client ID puis « Connecter mon compte Twitch »
   et valider le code sur twitch.tv/activate.

### Scènes

Trois scènes, choisies depuis la télécommande (en haut) ou les boutons à gauche du téléphone qui filme :
- **Intro** : vidéo « Le match va commencer » en boucle (scène par défaut à l'ouverture de la caméra) ;
- **Match** : caméra + tableau de score ;
- **Fin** : vidéo « Le match est fini » avec les noms, les sets gagnés et le détail des sets.

Chaque changement de scène joue la transition ECVB (avec son bruitage) ; la scène change quand la transition couvre l'écran.

Son du live : en **Intro** et **Fin**, la musique (`res/raw/music.m4a`) remplace le micro, qui est coupé
(évite les droits d'auteur sur la musique de la salle pendant l'échauffement). En **Match**, le micro revient.
Le passage se fait en fondu ; la musique reprend là où elle s'était arrêtée.

Fichiers graphiques : `res/drawable-nodpi/scoreboard.png` (fond du tableau de score),
`res/raw/intro.mp4`, `res/raw/outro.mp4`, `res/raw/transition.mp4` (transition « côte à côte » : couleurs à gauche,
transparence à droite), `res/raw/transition_sfx.pcm` (bruitage), `res/raw/music.m4a`.
Polices : Bebas Neue et Josefin Sans (licence SIL Open Font License) dans `assets/fonts/`.

Bon à savoir :
- L'écran reste allumé pendant le live ; verrouiller le téléphone ou quitter l'app coupe le direct.
- 720p (≈ 2,5 Mb/s) est conseillé en 4G ; 1080p (≈ 4,5 Mb/s) sur une bonne connexion.
- En cas de coupure réseau, l'app retente automatiquement la connexion (10 essais, en alternant avec un serveur de secours pour Twitch). Si ça échoue, la raison exacte s'affiche à l'écran.

## Récupérer l'APK

Chaque push sur ce dossier lance le workflow GitHub Actions **Android APK**
(`.github/workflows/android.yml`). Dans l'onglet *Actions* du dépôt → dernier run →
section *Artifacts* → `VolleyLive-apk` (zip contenant l'APK). L'installer sur le téléphone
(autoriser « sources inconnues »).

L'APK est signé avec une clé fixe (`app/debug.keystore`) : les nouvelles versions
s'installent par-dessus les anciennes.

## Code

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Écran de réglages (plateforme, clé, équipes) |
| `StreamActivity.kt` | Caméra, live RTMP, overlay, boutons, QR code |
| `OverlayRenderer.kt` | Textes du tableau de score et de l'écran de fin |
| `SceneController.kt` | Scènes (intro / match / fin), vidéos et transition |
| `AlphaVideoFilterRender.kt` | Calque vidéo avec transparence (transition) |
| `AudioMixer.kt` | Son du live : micro, musique, bruitage |
| `TwitchApi.kt` | Connexion au compte Twitch et changement du titre du live |
| `ScoreManager.kt` / `ScoreState.kt` | État du match, actions, historique (annuler) |
| `RemoteServer.kt` | Serveur web de la télécommande (API `/api/state`, `/api/action`, `/api/config`) |
| `assets/remote.html` | Interface de la télécommande |

Streaming : [RootEncoder](https://github.com/pedroSG94/RootEncoder) · serveur : NanoHTTPD · QR : ZXing.

Compilation locale : ouvrir le dossier `android/` dans Android Studio, ou `./gradlew assembleRelease`.
