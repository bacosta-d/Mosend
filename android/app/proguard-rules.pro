# Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.jvm.internal.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.* *;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-dontwarn coil.**

# Timber
-dontwarn timber.log.**

# Compose
-dontwarn androidx.compose.**

# Serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Keep model classes
-keep class com.filetransfer.coui.model.** { *; }
-keep class com.filetransfer.coui.network.** { *; }
-keep class com.filetransfer.coui.transfer.** { *; }

# Keep Application class
-keep class com.filetransfer.coui.FileTransferApplication { *; }

# Keep services
-keep class com.filetransfer.coui.transfer.TransferForegroundService { *; }
-keep class com.filetransfer.coui.network.DiscoveryService { *; }