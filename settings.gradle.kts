pluginManagement {
    repositories {
        if (providers.gradleProperty("useChinaMirrors").orNull != "false") {
            maven("https://maven.aliyun.com/repository/google") {
                name = "AliyunGoogle"
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.google\\.android.*")
                    includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                }
            }
            maven("https://maven.aliyun.com/repository/central") { name = "AliyunCentral" }
            maven("https://maven.aliyun.com/repository/gradle-plugin") { name = "AliyunPlugins" }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("useChinaMirrors").orNull != "false") {
            maven("https://maven.aliyun.com/repository/google") {
                name = "AliyunGoogle"
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.google\\.android.*")
                    includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                }
            }
            maven("https://maven.aliyun.com/repository/central") { name = "AliyunCentral" }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ahao-clicker"
include(":app", ":core")

if (providers.gradleProperty("acceptance").orNull == "true") {
    include(":acceptance-target")
    project(":acceptance-target").projectDir = file("tools/acceptance-target")
}
