import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("fabric-loom") version "1.13-SNAPSHOT"
}

base {
    archivesName.set("lootinjector-fabric")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation(fabricApi.module("fabric-command-api-v2", property("fabric_api_version").toString()))
    modImplementation(fabricApi.module("fabric-events-interaction-v0", property("fabric_api_version").toString()))
    modImplementation(fabricApi.module("fabric-lifecycle-events-v1", property("fabric_api_version").toString()))
    modImplementation(fabricApi.module("fabric-loot-api-v3", property("fabric_api_version").toString()))
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
