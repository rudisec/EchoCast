plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rudisec.echocast"

    compileSdk = 33

    defaultConfig {
        applicationId = "com.rudisec.echocast"
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_1_8)
        targetCompatibility(JavaVersion.VERSION_1_8)
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.4.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.4.1")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("com.google.android.material:material:1.6.1")
}

android.applicationVariants.all {
    val variant = this
    val capitalized = variant.name.capitalize()
    val extraDir = File(buildDir, "extra")
    val variantDir = File(extraDir, variant.name)

    val moduleProp = tasks.register("moduleProp${capitalized}") {
        val outputFile = File(variantDir, "module.prop")
        outputs.file(outputFile)

        doLast {
            val props = LinkedHashMap<String, String>()
            props["id"] = variant.applicationId
            props["name"] = "EchoCast"
            props["version"] = "v${variant.versionName}"
            props["versionCode"] = variant.versionCode.toString()
            props["author"] = "rudisec"
            props["description"] = "Play audio files during your phone calls"
            props["updateJson"] = "https://github.com/rudisec/EchoCast/releases/latest/download/update.json"
            props["support"] = "https://github.com/rudisec/EchoCast/issues"

            outputFile.writeText(props.map { "${it.key}=${it.value}" }.joinToString("\n"))
        }
    }

    val permissionsXml = tasks.register("permissionsXml${capitalized}") {
        val outputFile = File(variantDir, "privapp-permissions-${variant.applicationId}.xml")
        outputs.file(outputFile)

        doLast {
            outputFile.writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <permissions>
                    <privapp-permissions package="${variant.applicationId}">
                        <permission name="android.permission.CONTROL_INCALL_EXPERIENCE" />
                        <permission name="android.permission.MODIFY_PHONE_STATE" />
                    </privapp-permissions>
                </permissions>
            """.trimIndent())
        }
    }

    tasks.register<Zip>("zip${capitalized}") {
        archiveFileName.set("EchoCast-1.0.zip")
        destinationDirectory.set(File(destinationDirectory.asFile.get(), variant.name))

        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        dependsOn.add(variant.assembleProvider)

        from(moduleProp.get().outputs)
        from(permissionsXml.get().outputs) {
            into("system/etc/permissions")
        }
        from(variant.outputs.map { it.outputFile }) {
            into("system/priv-app/${variant.applicationId}")
        }

        val magiskDir = File(projectDir, "magisk")

        for (script in arrayOf("update-binary", "updater-script")) {
            from(File(magiskDir, script)) {
                into("META-INF/com/google/android")
            }
        }

        from(File(rootDir, "LICENSE"))
        from(File(rootDir, "README.md"))
    }
}