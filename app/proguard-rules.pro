# Règles R8 de l'application.
# Media3, OkHttp, Coil, Compose et kotlinx.serialization fournissent leurs propres règles.

# Modèles sérialisés en JSON (profils, progression, historique, session de lecture)
-keep,includedescriptorclasses class fr.pelis.iptv.data.**$$serializer { *; }
-keep,includedescriptorclasses class fr.pelis.iptv.player.**$$serializer { *; }
-keepclassmembers class fr.pelis.iptv.data.** {
    *** Companion;
}
-keepclassmembers class fr.pelis.iptv.player.** {
    *** Companion;
}
-keepclasseswithmembers class fr.pelis.iptv.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class fr.pelis.iptv.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google Cast : classe déclarée par son nom dans le manifeste
-keep class fr.pelis.iptv.cast.CastOptionsProvider { *; }
