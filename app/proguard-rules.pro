-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepclassmembers class app.mizan.data.local.** {
    <init>(...);
}

-dontwarn okhttp3.**
-dontwarn okio.**
