# kotlinx.serialization：保留生成的序列化器
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ink.kuke.chat.**$$serializer { *; }
