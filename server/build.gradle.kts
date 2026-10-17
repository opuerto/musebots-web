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

// The browser code is tested with Node's built-in test runner (no npm install needed). It runs as part of `test` so a
// broken page cannot go unnoticed; where Node is not installed it says so and is skipped.
val jsTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the browser tests in src/test/js (needs Node 20+)"
    workingDir = projectDir
    inputs.dir("src/test/js")
    inputs.dir("src/main/resources/public")
    commandLine("sh", "-c",
        "command -v node >/dev/null 2>&1 || { echo 'node not found: SKIPPING the browser tests'; exit 0; }; " +
            "node --test src/test/js/*.test.mjs")
}

tasks.named("test") {
    dependsOn(jsTest)
}
