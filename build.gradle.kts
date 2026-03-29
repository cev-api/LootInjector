plugins {
    base
}

allprojects {
    group = "dev.cevapi.lootinjector"
    version = "0.0.2"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
