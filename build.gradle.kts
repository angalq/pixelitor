/*
 * Generated by the Gradle 'init' task: 14/04/2024
 * Reviewed by: Angalq
 */

plugins {
    `application`
    `java`
    `java-library`
    `maven-publish`
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

dependencies {
    api(libs.org.swinglabs.swingx.swingx.all)
    api(libs.net.jafama.jafama)
    api(libs.com.twelvemonkeys.imageio.imageio.jpeg)
    api(libs.com.twelvemonkeys.imageio.imageio.tga)
    api(libs.com.twelvemonkeys.imageio.imageio.pnm)
    api(libs.com.twelvemonkeys.imageio.imageio.webp)
    api(libs.com.formdev.flatlaf)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.api)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.params)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.engine)
    testImplementation(libs.org.junit.vintage.junit.vintage.engine)
    testImplementation(libs.org.mockito.mockito.core)
    testImplementation(libs.org.assertj.assertj.swing.junit)
}

group = "pixelitor"
version = "4.3.1"
description = "pixelitor"
java.sourceCompatibility = JavaVersion.VERSION_21

application {
    mainClass = "pixelitor.Pixelitor"
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}
