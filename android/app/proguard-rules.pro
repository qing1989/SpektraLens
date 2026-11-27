# Keep all JNI native entrypoints and signatures used by bridge.cpp
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class app.spektrafilm.NativeEngine { *; }

# Keep custom View constructors used by Android inflation / reflection
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Preserve readable stack traces for diagnostics
-keepattributes SourceFile,LineNumberTable

# compileOnly annotations in exifinterface
-dontwarn androidx.annotation.**
