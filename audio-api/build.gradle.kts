plugins { id("org.jetbrains.kotlin.jvm"); `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("io.github.gretchycat.libinfinistream:stream-core:0.1.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
