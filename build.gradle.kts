buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    configurations.all {
        resolutionStrategy {
            force("com.android.tools:r8:8.3.37")
        }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.0")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
    }
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
