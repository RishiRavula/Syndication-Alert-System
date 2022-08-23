import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.expediagroup.graphql.plugin.gradle.graphql

plugins {
    kotlin("jvm") version "1.6.21"
    application
    java
    id("com.expediagroup.graphql") version "5.4.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.discord4j:discord4j-core:3.2.2")
    implementation("com.expediagroup:graphql-kotlin-spring-client:5.4.1")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}

graphql {
    client {
        allowDeprecatedFields = true
        serializer = com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer.JACKSON
        packageName = "com.graphql.generated"
        endpoint = "https://api.tuned.com/graphql"
    }
}
