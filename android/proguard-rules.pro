# As the desktop package does: keep everything except the unused Material icons, which are most of
# the code's size. No obfuscation or optimization, so nothing that reflection, JNI (ONNX Runtime)
# or the JavaScript engine NewPipe uses can break.
-dontobfuscate
-dontoptimize
-dontwarn **
-dontnote **
-keepattributes *
-keep class !androidx.compose.material.icons.**, ** { *; }
