# IPTV Player pour Windows

Réécriture en **Rust** de l'application Android, pour PC Windows et PC tactiles (x64 et ARM64).
Compatible avec l'API **Xtream Codes**.

> Cette application ne fournit aucun contenu : elle sert uniquement à lire un abonnement IPTV auquel vous avez légitimement accès.

## Fonctionnalités

- **Profils** : plusieurs comptes Xtream, chacun avec ses favoris, sa progression et son historique ; collage d'un lien M3U `get.php?username=…&password=…` (ou d'une adresse de lecture) pour tout remplir
- **Accueil** : « Continuer à regarder » et chaînes récentes avec le programme en cours
- **Direct, Films, Séries** : catégories, Récents, Favoris, Tout ; recherche tolérante aux accents (Ctrl+F)
- **Favoris** : étoile, clic droit ou appui long
- **Guide TV** : programme en cours / suivant sous chaque chaîne, grille horaire sur 8 h (glisser au doigt, Maj+molette, boutons)
- **Fiche film** : résumé, casting, réalisation, bande-annonce (navigateur), reprise de lecture
- **Fiche série** : saisons, épisodes, reprise et enchaînement des épisodes
- **Lecteur intégré (libmpv)** : HLS, MPEG-TS, MP4, MKV ; décodage matériel ; pistes audio et sous-titres ; repli automatique `.ts` → `.m3u8` ; reconnexion des directs coupés
- **Zapping** : chaîne suivante / précédente, « dernière chaîne », liste des chaînes avec filtre et guide
- **Plein écran** (F, double-clic) et **mini-lecteur** toujours au premier plan (remplace le Picture-in-Picture)
- **Chromecast** : découverte sur le réseau local, diffusion, pause / avance, retour sur le PC à la même position, avertissement pour le son Dolby / DTS
- **Sauvegarde** : export / import des favoris et de l'historique, **au même format que l'application Android** (fichiers échangeables)
- **Mise à jour automatique** depuis les releases GitHub
- **Interface PC et tactile** : mise en page qui s'adapte à la largeur, mode tactile automatique (cibles plus grandes) ou forcé, taille de l'interface réglable (Ctrl +/−)

Non repris (spécifiques au mode TV d'Android) : choix Téléviseur / Smartphone, navigation à la télécommande, configuration et sauvegarde par QR code depuis un téléphone.

### Raccourcis du lecteur

| Touche | Action |
| --- | --- |
| Espace / K | Lecture / pause |
| ← / → | Reculer 10 s / avancer 30 s |
| ↑ / ↓ | Volume |
| Page préc. / Page suiv. (ou Ctrl+↑/↓) | Chaîne précédente / suivante |
| Retour arrière | Dernière chaîne regardée |
| L | Liste des chaînes |
| F / F11, double-clic | Plein écran |
| M | Couper le son |
| Échap | Fermer la liste, quitter le plein écran, puis le lecteur |

## Installation

Décompressez `IPTV-Player-<version>-windows-x64.zip` (ou `-arm64` pour les PC Snapdragon) dans un dossier où vous pouvez écrire (par exemple `Documents\IPTV Player`), puis lancez `iptv-player.exe`. `libmpv-2.dll` doit rester à côté de l'exécutable.

## Compiler

Prérequis : Rust (stable) et Visual Studio Build Tools (C++).

```powershell
cargo test
cargo run
```

`libmpv-2.dll` doit être à côté de l'exécutable (`target\debug\`). Le script suivant compile la version release, télécharge libmpv ([builds publiques mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake)) et prépare l'archive :

```powershell
.\scripts\package.ps1              # architecture de ce PC
.\scripts\package.ps1 -Arch x64
```

La CI GitHub (`.github/workflows/windows.yml`) compile et teste les deux architectures ; sur un tag `vX.Y.Z` égal à la version de `Cargo.toml`, les archives sont jointes à la release (la mise à jour automatique les y cherche).

Diagnostic : la variable d'environnement `IPTV_MPV_LOG=chemin\mpv.log` écrit le journal de mpv (il contient les adresses des flux, donc vos identifiants).

## Structure

```
src/
├── main.rs      fenêtre, icône
├── app.rs       état et logique (profils, catalogue, recherche, lecture, sauvegarde, mise à jour)
├── screens.rs   écrans (profils, accueil, catalogue, guide, fiches, compte, mise à jour)
├── player.rs    lecteur : zapping, reprise, pistes, plein écran, mini-lecteur, Chromecast
├── mpv.rs       liaison libmpv (chargée dynamiquement) et rendu vidéo OpenGL / logiciel
├── cast.rs      Chromecast (mDNS + protocole Cast v2)
├── xtream.rs    API Xtream Codes, analyse des adresses et liens M3U
├── epg.rs       guide TV à la demande
├── images.rs    affiches et logos (téléchargement borné, réduction, oubli)
├── store.rs     profils chiffrés, réglages, données par profil
├── backup.rs    format de sauvegarde (identique à Android)
├── secret.rs    chiffrement DPAPI
└── updater.rs   mise à jour depuis GitHub
```

## Confidentialité et limites

- Les identifiants sont stockés dans `%APPDATA%\IPTV Player`, chiffrés avec DPAPI (liés à votre compte Windows) ; jamais en clair.
- Beaucoup de serveurs Xtream ne fonctionnent qu'en HTTP : l'application l'accepte mais le signale. Avec un serveur HTTPS, une redirection vers HTTP est refusée (API, images, et premier accès aux flux).
- Le protocole Xtream place les identifiants dans les URL des flux : ils sont donc transmis au Chromecast lors d'une diffusion.
- Sur ARM64, la libmpv publique est compilée sans OpenGL : la vidéo est alors rendue par mpv en mémoire puis affichée (décodage matériel conservé, un peu plus de calcul processeur).
- libmpv est distribuée sous licence GPL/LGPL (voir le projet mpv) ; elle est livrée telle quelle à côté de l'exécutable.
