# Règles R8 de l'application.
# Media3, OkHttp, Coil, Compose et kotlinx.serialization fournissent leurs propres règles.

# Modèles sérialisés en JSON (profils, progression, historique)
-keep,includedescriptorclasses class fr.pelis.iptv.data.**$$serializer { *; }
-keepclassmembers class fr.pelis.iptv.data.** {
    *** Companion;
}
-keepclasseswithmembers class fr.pelis.iptv.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google Cast : classe déclarée par son nom dans le manifeste
-keep class fr.pelis.iptv.cast.CastOptionsProvider { *; }
