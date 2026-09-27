# LiteRT-LM uses JNI callbacks and reflection into its Kotlin classes.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
