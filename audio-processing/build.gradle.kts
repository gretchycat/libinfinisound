plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.infinifolder.audio.processing"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    androidResources { noCompress += "bz2" }
    if (!file("/data/data/com.termux/files/usr/bin/clang").isFile) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    }
    if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("openmptNative"))
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("vlcPcmNative"))
    }
}

if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
    val buildOpenMptNative = tasks.register<Exec>("buildOpenMptNative") {
        inputs.dir("src/main/cpp")
        outputs.dir(layout.buildDirectory.dir("openmptNative"))
        commandLine("sh", file("src/main/cpp/build-openmpt.sh"),
            layout.buildDirectory.dir("openmptNative").get().asFile, System.getProperty("java.home"))
    }
    val buildVlcPcmNative = tasks.register<Exec>("buildVlcPcmNative") {
        inputs.dir("src/main/cpp")
        outputs.dir(layout.buildDirectory.dir("vlcPcmNative"))
        commandLine("sh", file("src/main/cpp/build-vlc-pcm.sh"),
            layout.buildDirectory.dir("vlcPcmNative").get().asFile, System.getProperty("java.home"))
    }
    tasks.named("preBuild").configure { dependsOn(buildOpenMptNative, buildVlcPcmNative) }
    tasks.withType<Test>().configureEach {
        dependsOn(buildOpenMptNative)
        inputs.dir(layout.buildDirectory.dir("openmptNative"))
        jvmArgs("-Djava.library.path=${layout.buildDirectory.dir("openmptNative/arm64-v8a").get().asFile}")
    }
}

dependencies {
    api(project(":audio-core"))
    val localVlc = providers.gradleProperty("libVlcAar").orNull
    if (localVlc == null) implementation("org.videolan.android:libvlc-all:3.7.7")
    else implementation(files(localVlc))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}
