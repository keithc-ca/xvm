plugins {
    id("org.xtclang.build.xdk.versioning")
    alias(libs.plugins.xtc)
}

dependencies {
    xdkJavaTools(libs.javatools)
    xtcModule(libs.xdk.ecstasy)
    xtcModule(libs.xdk.convert)
    xtcModule(libs.xdk.crypto)
    xtcModule(libs.xdk.json)
    xtcModule(libs.xdk.net)
}
