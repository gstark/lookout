plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("app.lookout.desktop.DesktopMainKt")
}

// The runner reads the answers to prompts from the terminal.
tasks.named<JavaExec>("run") { standardInput = System.`in` }

dependencies {
    implementation(project(":server"))
    implementation(project(":imaging-awt"))
}
