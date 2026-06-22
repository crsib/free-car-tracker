# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep @Serializable classes' synthesized serializers
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers,allowshrinking class * {
    public static ** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keep class <1> { *; }

# Room generated
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Tink (via androidx.security-crypto) references optional ErrorProne annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# osmdroid (map) — keep its classes and silence optional/desktop-only deps it references.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Play Services location is an optional runtime dependency (absent on no-GMS head units).
-dontwarn com.google.android.gms.**
