plugins { id("org.jetbrains.kotlin.jvm"); `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":audio-api"))
    implementation("javazoom:jlayer:1.0.1")
    implementation("com.tianscar.javasound:jaad:0.9.4")
    testImplementation("junit:junit:4.13.2")
}

if (!providers.gradleProperty("portableOnly").isPresent && file("/data/data/com.termux/files/usr/bin/clang").isFile) {
    tasks.withType<Test>().configureEach {
        dependsOn(":audio-processing:buildOpenMptNative")
        val nativeDir = project(":audio-processing").layout.buildDirectory.dir("openmptNative")
        inputs.dir(nativeDir)
        jvmArgs("-Djava.library.path=${nativeDir.get().asFile}/arm64-v8a")
    }
}
