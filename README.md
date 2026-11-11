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
- **Android TV** : au premier démarrage, choix Téléviseur ou Smartphone / tablette ; en mode TV, navigation complète à la télécommande (focus visible, menu latéral, appui long sur OK pour les favoris, zapping haut/bas)
- **Mise à jour automatique** : au démarrage (ou depuis le menu Compte), l'application vérifie la dernière release GitHub, la télécharge et lance l'installateur Android
- **Chromecast** : envoi de la lecture vers la TV (récepteur Google par défaut) et retour sur le téléphone à la même position

## Stack

Kotlin · Jetpack Compose (Material 3) · Media3 / ExoPlayer · OkHttp · kotlinx.serialization · Coil

## Compiler

Prérequis : JDK 17 et le SDK Android (API 36).

```bash
./gradlew assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

Tests unitaires et lint (également lancés par GitHub Actions à chaque push) :

```bash
./gradlew testDebugUnitTest lintDebug
```

### Signature des releases

Les releases sont signées avec la clé décrite dans `keystore.properties` (fichier non versionné, à placer à la racine) :

```properties
storeFile=chemin/vers/iptv-player-release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Sans ce fichier, `assembleRelease` produit un APK **non signé**. La mise à jour automatique exige que chaque version soit signée avec la même clé : le fichier `.keystore` doit être sauvegardé.

## Structure

```
app/src/main/kotlin/fr/pelis/iptv/
├── data/      API Xtream, modèles, profils, progression/historique
├── player/    Lecteur plein écran + PiP
└── ui/        Écrans Compose (profils, accueil, guide, fiche film, séries)
```

## Confidentialité et limites

- Les identifiants sont stockés uniquement sur l'appareil, chiffrés (AES-GCM) avec une clé de l'Android Keystore ; la sauvegarde Android est désactivée.
- Beaucoup de serveurs Xtream ne fonctionnent qu'en HTTP : l'application l'accepte mais le signale, car identifiants et vidéos circulent alors en clair. Préférez `https://` quand le fournisseur le propose.
- Le protocole Xtream place les identifiants dans les URL des flux : ils sont donc transmis au Chromecast lors d'un Cast.
- Chromecast : le récepteur Google par défaut ne décode généralement pas le son Dolby (AC3/E-AC3) ou DTS des fichiers MKV/MP4 ; l'application prévient dans ce cas.
