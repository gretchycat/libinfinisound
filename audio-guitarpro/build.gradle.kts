plugins { id("org.jetbrains.kotlin.jvm"); `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
sourceSets.main { java.srcDir("third_party/tuxguitar/src") }
dependencies { implementation(project(":audio-core")) }
