# Не обфусцируем классы официального AmneziaWG backend — там есть JNI-вызовы,
# завязанные на конкретные имена методов/классов.
-keep class org.amnezia.awg.** { *; }
-dontwarn org.amnezia.awg.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager
