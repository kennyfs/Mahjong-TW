# kotlinx.serialization and the JNI engine: the library's own rules keep the serializers; the engine's native methods
# are found by name.
-keepclasseswithmembernames class * { native <methods>; }
