import org.gradle.api.tasks.SourceSetContainer

plugins {
    java
}

base {
    archivesName.set("lootinjector-paper")
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:${property("paper_api_version")}")
}

tasks {
    val commonMainOutput = project(":common").extensions.getByType<SourceSetContainer>()["main"].output

    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        dependsOn(":common:classes")
        from(commonMainOutput)
    }

}
