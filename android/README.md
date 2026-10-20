# ScoreCast — live multisport (Android)

Filmer un match en direct (Twitch, YouTube, Facebook, Kick, TikTok, Instagram ou serveur RTMP),
avec un tableau de score aux couleurs du club incrusté dans la vidéo, piloté depuis un 2e téléphone.
Regroupe Foot Live et Volley Live, et ajoute le basket et le hand.

## Parcours

1. **Sport** : football, volley-ball, basket-ball, handball.
2. **Club** : nom, couleur principale et secondaire (palette large + couleur libre), logo.
   Plusieurs clubs peuvent être enregistrés.
3. **Plateforme** de diffusion (mémorisée par club).
4. **Connexion** : clé de diffusion (et adresse du serveur si la plateforme la fournit) ;
   Twitch : connexion du compte facultative pour mettre à jour le titre du live.
5. **Match** : équipe adverse et sa couleur, compétition, durée des périodes → caméra.

Raccourci « Reprendre » sur l'accueil : même club, même sport, même plateforme.

## Écrans & vidéos

Aucune vidéo n'est fournie. Sans import, les écrans d'intro, de pause et de fin sont générés
aux couleurs du club (logo, équipes, score) et le changement d'écran est direct.
Chaque club peut importer : intro, écran de pause / mi-temps, écran de fin, transition, musique d'attente.

## Règles gérées

| Sport  | Score | Chrono | Spécifique |
|--------|-------|--------|------------|
| Foot   | buts (+ buteurs) | monte, temps additionnel | cartons rouges, prolongations, tirs au but |
| Volley | points | — | sets, service, tie-break à 15 |
| Basket | +1 / +2 / +3 | décompte (dixièmes sous 1 min), arrêt auto | 4 quarts-temps, prolongations 5 min, fautes d'équipe (BONUS à 5), temps morts 2 + 3 + 1 |
| Hand   | buts | monte, arrêt auto à 30:00 | exclusions 2' (décompte en temps de jeu), 3 temps morts |

## Télécommande

Page web servie par le téléphone qui filme (port 8080, code PIN, QR code) : rien à installer,
fonctionne aussi sur iPhone. Elle s'adapte au sport et prend les couleurs des équipes.
Les deux téléphones doivent être sur le même Wi-Fi, ou la télécommande connectée au point
d'accès du téléphone qui filme.

## Compilation

L'APK est compilé par GitHub Actions (`.github/workflows/android.yml`) et publié dans la release
`scorecast` du dépôt.
