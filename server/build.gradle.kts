plugins {
    application
}

dependencies {
    implementation(project(":agents"))
    implementation("io.javalin:javalin:6.7.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass = "dev.musebots.server.Main"
}
