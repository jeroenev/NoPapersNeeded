# Keep rules for code that R8 cannot see being used. Libraries with consumer rules need none here.

# JNA's native dispatch library looks its Java peers up by name through JNI (Native, Pointer,
# NativeLong, LastErrorException, callbacks), and the AAR ships no consumer rules. Without these
# the exclusive-rename syscall behind verified journal publication (LinuxNoReplaceMove) fails.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
# JNA's desktop window helpers (Native$AWT) reference AWT, which Android does not have.
-dontwarn java.awt.**

# Optional dependencies that are referenced but never shipped: pdfbox-android's JPEG 2000 codec
# (JP2 images are not decoded) and Ktor's IntelliJ debugger detection (JVM-only management API).
-dontwarn com.gemalto.jp2.**
-dontwarn java.lang.management.**

# ML Kit discovers its components from manifest meta-data and instantiates each registrar by
# reflection. R8 full mode (the AGP default) keeps the class names but not their no-arg
# constructors, so text recognition finds no component and opening a library fails.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
