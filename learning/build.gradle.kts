plugins {
    application
}

dependencies {
    api(project(":core"))
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

application {
    mainClass = "dev.musebots.learning.Main"
}

// Run from the repository root, so a path such as data/corpus.json means the same thing here as in a terminal.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// The script that reads the Weimar Jazz Database is Python (standard library only) and has its own tests. They run as part
// of `test`; where python3 is not installed this says so and is skipped.
val pythonTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the tests of the corpus extraction script in tools/ (needs python3)"
    workingDir = projectDir
    inputs.dir("tools")
    commandLine("sh", "-c",
        "command -v python3 >/dev/null 2>&1 || { echo 'python3 not found: SKIPPING the corpus script tests'; exit 0; }; " +
            "python3 -m unittest discover -s tools -p 'test_*.py'")
}

tasks.named("test") {
    dependsOn(pythonTest)
}
