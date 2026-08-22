# Nothing is shrunk today (isMinifyEnabled = false for both build types).
# These rules exist so that turning R8 on later does not silently break JGit.
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-keep class org.eclipse.jgit.** { *; }
-keepclassmembers class * implements java.io.Serializable { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class dev.opencode.mobile.**$$serializer { *; }
-keepclassmembers class dev.opencode.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class dev.opencode.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
