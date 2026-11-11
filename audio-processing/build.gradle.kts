plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.infinifolder.audio.processing"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    androidResources { noCompress += "sf3" }
    if (!(System.getenv("TERMUX_VERSION") != null)) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    }
    if ((System.getenv("TERMUX_VERSION") != null)) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("openmptNative"))
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("vlcPcmNative"))
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("smafNative"))
    }
}

if ((System.getenv("TERMUX_VERSION") != null)) {
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
    val buildSmafNative = tasks.register<Exec>("buildSmafNative") {
        inputs.file("src/main/cpp/smaf_jni.cpp")
        inputs.file("src/main/cpp/build-smaf.sh")
        inputs.dir("src/main/cpp/third_party/smaf")
        outputs.dir(layout.buildDirectory.dir("smafNative"))
        commandLine("sh", file("src/main/cpp/build-smaf.sh"),
            layout.buildDirectory.dir("smafNative").get().asFile, System.getProperty("java.home"))
    }
    tasks.named("preBuild").configure { dependsOn(buildOpenMptNative, buildVlcPcmNative, buildSmafNative) }
    tasks.withType<Test>().configureEach {
        dependsOn(buildOpenMptNative)
        inputs.dir(layout.buildDirectory.dir("openmptNative"))
        jvmArgs("-Djava.library.path=${layout.buildDirectory.dir("openmptNative/arm64-v8a").get().asFile}")
    }
}

dependencies {
    api(project(":audio-core"))
    implementation(project(":audio-guitarpro"))
    val localVlc = providers.gradleProperty("libVlcAar").orNull
    if (localVlc == null) implementation("org.videolan.android:libvlc-all:3.7.7")
    else implementation(files(localVlc))
    testImplementation("junit:junit:4.13.2")
}
