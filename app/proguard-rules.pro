# Règles de la version optimisée (R8).

# VLC appelle ses classes Java depuis son code natif (JNI) : on ne les renomme pas.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
