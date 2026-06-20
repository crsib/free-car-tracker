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
