# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# Called back from native code (latency_threads.c)
-keep class com.limelight.binding.video.LatencyThreads {*;}

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# KeyConfigHelper - keep classes and fields for Gson
-keep class com.limelight.utils.KeyConfigHelper {*;}
-keep class com.limelight.utils.KeyConfigHelper$ShortcutFile {*;}
-keep class com.limelight.utils.KeyConfigHelper$Shortcut {*;}

# Profiles
-keep class com.limelight.profiles.ProfilesManager$ProfilesData {*;}
-keep class com.limelight.profiles.SettingsProfile {*;}

# Game metadata cache - read and written through Gson reflection
-keep class com.limelight.shell.data.GameMetadataStore$CachedMetadata {*;}
-keep class com.limelight.shell.data.GameMetadata {*;}
-keep class com.limelight.shell.data.MetadataCandidate {*;}
-keep class com.limelight.shell.data.GameGenre {*;}
-keep class com.limelight.shell.data.HdrEvidence {*;}
-keep class com.limelight.shell.data.CompletionTime {*;}
-keep class com.limelight.shell.data.NativeHdr {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**