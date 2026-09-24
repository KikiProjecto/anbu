# JNI resolves these by name — R8 must not rename or strip them.
-keepclasseswithmembernames, includedescriptorclasses class * {
    native <methods>;
}
-keep class com.anbu.research.core.NativeLib { *; }
-keep interface com.anbu.research.core.TokenCallback { *; }
