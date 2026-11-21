plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("app.lookout.desktop.DesktopMainKt")
}

dependencies {
    implementation(project(":server"))
    implementation(project(":imaging-awt"))
}
