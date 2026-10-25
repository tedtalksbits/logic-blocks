plugins {
    java
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.211-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.211-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.118.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
