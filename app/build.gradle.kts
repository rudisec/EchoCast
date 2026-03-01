plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rudisec.echocast"

    compileSdk = 34

    defaultConfig {
        applicationId = "com.rudisec.echocast"
        minSdk = 28
        targetSdk = 33
        versionCode = 6
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("RELEASE_KEYSTORE")
            storeFile = if (keystore != null) { File(keystore) } else { null }
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSPHRASE")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSPHRASE")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile != null
            } ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.10.0")
    
    // Coroutines for async tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    
    // Exclude conflicting Kotlin stdlib JDK versions
    configurations.all {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

android.applicationVariants.all {
    val variant = this
    val capitalized = variant.name.capitalize()
    val extraDir = File(buildDir, "extra")
    val variantDir = File(extraDir, variant.name)

    val moduleProp = tasks.register("moduleProp${capitalized}") {
        inputs.property("variant.applicationId", variant.applicationId)
        inputs.property("variant.name", variant.name)
        inputs.property("variant.versionCode", variant.versionCode)
        inputs.property("variant.versionName", variant.versionName)

        val outputFile = File(variantDir, "module.prop")
        outputs.file(outputFile)

        doLast {
            val props = LinkedHashMap<String, String>()
            props["id"] = variant.applicationId
            props["name"] = "EchoCast"
            props["version"] = "v${variant.versionName}"
            props["versionCode"] = variant.versionCode.toString()
            props["author"] = "rudisec"
            props["description"] = "Transform your phone calls. Play custom audio, sound effects, and your soundboard during calls. Simple, powerful, and always ready when you need it."

            outputFile.writeText(props.map { "${it.key}=${it.value}" }.joinToString("\n"))
        }
    }

    val permissionsXml = tasks.register("permissionsXml${capitalized}") {
        inputs.property("variant.applicationId", variant.applicationId)

        val outputFile = File(variantDir, "privapp-permissions-${variant.applicationId}.xml")
        outputs.file(outputFile)

        doLast {
            outputFile.writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <permissions>
                    <privapp-permissions package="${variant.applicationId}">
                        <permission name="android.permission.CONTROL_INCALL_EXPERIENCE" />
                        <permission name="android.permission.MODIFY_PHONE_STATE" />
                        <permission name="android.permission.INTERNET" />
                        <permission name="android.permission.ACCESS_NETWORK_STATE" />
                    </privapp-permissions>
                </permissions>
            """.trimIndent())
        }
    }

    val magiskDir = File(projectDir, "magisk")
    
    // Tasks to convert Magisk scripts to Unix line endings
    val convertUpdateBinary = tasks.register("convertUpdateBinary${capitalized}") {
        val scriptFile = File(magiskDir, "update-binary")
        val outputFile = File(variantDir, "update-binary")
        
        inputs.file(scriptFile)
        outputs.file(outputFile)
        
        doLast {
            val content = scriptFile.readText(Charsets.UTF_8)
            val unixContent = content.replace("\r\n", "\n").replace("\r", "\n")
            outputFile.writeText(unixContent, Charsets.UTF_8)
        }
    }
    
    val convertUpdaterScript = tasks.register("convertUpdaterScript${capitalized}") {
        val scriptFile = File(magiskDir, "updater-script")
        val outputFile = File(variantDir, "updater-script")
        
        inputs.file(scriptFile)
        outputs.file(outputFile)
        
        doLast {
            val content = scriptFile.readText(Charsets.UTF_8)
            val unixContent = content.replace("\r\n", "\n").replace("\r", "\n")
            outputFile.writeText(unixContent, Charsets.UTF_8)
        }
    }
    
    val convertServiceScript = tasks.register("convertServiceScript${capitalized}") {
        val scriptFile = File(magiskDir, "service.sh")
        val outputFile = File(variantDir, "service.sh")
        
        inputs.file(scriptFile)
        outputs.file(outputFile)
        
        doLast {
            val content = scriptFile.readText(Charsets.UTF_8)
            val unixContent = content.replace("\r\n", "\n").replace("\r", "\n")
            outputFile.writeText(unixContent, Charsets.UTF_8)
        }
    }
    
    val convertPostFsDataScript = tasks.register("convertPostFsDataScript${capitalized}") {
        val scriptFile = File(magiskDir, "post-fs-data.sh")
        val outputFile = File(variantDir, "post-fs-data.sh")
        
        inputs.file(scriptFile)
        outputs.file(outputFile)
        
        doLast {
            val content = scriptFile.readText(Charsets.UTF_8)
            val unixContent = content.replace("\r\n", "\n").replace("\r", "\n")
            outputFile.writeText(unixContent, Charsets.UTF_8)
        }
    }

    tasks.register<Zip>("zip${capitalized}") {
        inputs.property("variant.applicationId", variant.applicationId)
        inputs.property("variant.name", variant.name)
        inputs.property("variant.versionName", variant.versionName)

        archiveFileName.set("EchoCast-${variant.versionName}-${variant.name}.zip")
        destinationDirectory.set(File(destinationDirectory.asFile.get(), variant.name))

        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        dependsOn.add(variant.assembleProvider)
        dependsOn.add(moduleProp)
        dependsOn.add(permissionsXml)
        dependsOn.add(convertUpdateBinary)
        dependsOn.add(convertUpdaterScript)
        dependsOn.add(convertServiceScript)
        dependsOn.add(convertPostFsDataScript)

        from(moduleProp.get().outputs)
        
        from(permissionsXml.get().outputs) {
            into("system/etc/permissions")
        }
        
        from(variant.outputs.map { it.outputFile }) {
            into("system/priv-app/${variant.applicationId}")
            rename { fileName ->
                "app-${variant.name}.apk"
            }
        }

        from(convertUpdateBinary.get().outputs) {
            into("META-INF/com/google/android")
        }
        
        from(convertUpdaterScript.get().outputs) {
            into("META-INF/com/google/android")
        }
        
        // SELinux policy rules for network access
        from(File(magiskDir, "sepolicy.rule"))
        
        // Post-fs-data script - applies rules early in boot
        from(convertPostFsDataScript.get().outputs)
        
        // Service script - applies additional rules after boot
        from(convertServiceScript.get().outputs)
    }
}
