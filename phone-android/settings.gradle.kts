// App de BYDLink para el móvil Android: abre app.bydhack.com a pantalla completa (Trusted Web Activity).
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "BYDLinkMovil"
include(":app")
