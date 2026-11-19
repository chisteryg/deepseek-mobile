pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 国内网络兜底：上面失败时再走镜像
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "DeepSeekMobile"
include(":app")
