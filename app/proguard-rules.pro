-keepattributes *Annotation*
-keep class org.readium.** { *; }
-dontwarn org.slf4j.**

# Kotlin Serialization keeps serializers through generated references. Retain package
# models as a defensive rule for manifests received from desktop Novel Voice Studio.
-keep,includedescriptorclasses class com.novelvoice.reader.core.packageformat.model.**$$serializer { *; }
-keepclassmembers class com.novelvoice.reader.core.packageformat.model.** {
    *** Companion;
}
