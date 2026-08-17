import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

base {
    archivesName.set("lootinjector-fabric")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation(project(":common"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val commonMainOutput = project(":common").extensions.getByType<SourceSetContainer>()["main"].output

tasks.jar {
    dependsOn(":common:classes")
    from(commonMainOutput)
}
