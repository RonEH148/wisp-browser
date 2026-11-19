plugins {
    id("tvbro.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.phlox.tvwebbrowser.common"
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    //Lumen: lets the remote's cursor send real mouse clicks (MotionEvent.setActionButton is hidden)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    testImplementation(libs.junit)
}
