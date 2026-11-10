# IPTV Player

Lecteur IPTV pour Android (téléphone, tablette et Android TV) compatible avec l'API **Xtream Codes**.

> Cette application ne fournit aucun contenu : elle sert uniquement à lire un abonnement IPTV auquel vous avez légitimement accès.

## Fonctionnalités

- **Connexion Xtream Codes** : URL du serveur + identifiants, ou collage direct d'un lien M3U `get.php?username=…&password=…`
- **Profils** : plusieurs comptes enregistrés, chacun avec ses favoris, sa progression et son historique
- **Direct, Films, Séries** : catégories, recherche, favoris (appui long)
- **Guide TV (EPG)** : programme en cours / suivant sous chaque chaîne et grille horaire sur 8 h
- **Accueil** : « Continuer à regarder » et chaînes récentes
- **Fiche film** : résumé, casting, réalisation, bande-annonce, reprise de lecture
- **Lecteur ExoPlayer** : HLS, MPEG-TS, MP4, MKV ; pistes audio et sous-titres ; repli automatique `.ts` → `.m3u8`
- **Zapping** : boutons suivant/précédent, touches CH+/CH- et « dernière chaîne » de la télécommande
- **Picture-in-Picture** avec commandes lecture/pause et chaîne suivante/précédente

## Stack

Kotlin · Jetpack Compose (Material 3) · Media3 / ExoPlayer · OkHttp · kotlinx.serialization · Coil

## Compiler

Prérequis : JDK 17 et le SDK Android (API 36).

```bash
./gradlew assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

## Structure

```
app/src/main/kotlin/fr/pelis/iptv/
├── data/      API Xtream, modèles, profils, progression/historique
├── player/    Lecteur plein écran + PiP
└── ui/        Écrans Compose (profils, accueil, guide, fiche film, séries)
```

## Confidentialité

Les identifiants sont stockés uniquement sur l'appareil (stockage privé de l'application, sauvegarde Android désactivée).
