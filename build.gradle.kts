plugins {
    id("java")
}

group = "ru.baldenna"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

val jar by tasks.getting(Jar::class) {
    manifest {
        attributes["Main-Class"] = "ru.baldenna.Main"
    }

//    from(configurations
//        .runtime
////         .get() // uncomment this on Gradle 6+
////         .files
//        .map { if (it.isDirectory) it else zipTree(it) })
}