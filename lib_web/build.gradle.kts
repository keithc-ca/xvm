plugins {
    id("org.xtclang.build.xdk.versioning")
    id("org.xtclang.xtc-plugin")
}

dependencies {
    xdkJavaTools(libs.javatools)
    xtcModule(libs.xdk.ecstasy)
    xtcModule(libs.xdk.aggregate)
    xtcModule(libs.xdk.collections)
    xtcModule(libs.xdk.convert)
    xtcModule(libs.xdk.crypto)
    xtcModule(libs.xdk.json)
    xtcModule(libs.xdk.net)
    xtcModule(libs.xdk.sec)
}

