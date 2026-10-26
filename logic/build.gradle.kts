plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

val asciiBuild = System.getenv("SOSTAV_BUILD")
if (!asciiBuild.isNullOrBlank()) {
    layout.buildDirectory.set(file(asciiBuild))
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
