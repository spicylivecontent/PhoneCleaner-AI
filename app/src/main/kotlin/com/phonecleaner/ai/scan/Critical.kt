package com.phonecleaner.ai.scan

/**
 * Packages that are never analysed and can never be selected for removal.
 * Removing any of these can stop the phone from booting or make it unusable.
 */
object Critical {
    private val exact = setOf(
        "android",
        // Android core
        "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.server.telecom",
        "com.android.shell", "com.android.externalstorage", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.keychain", "com.android.certinstaller",
        "com.android.se", "com.android.nfc", "com.android.mms.service", "com.android.location.fused",
        "com.android.inputdevices", "com.android.intentresolver", "com.google.android.intentresolver",
        "com.android.documentsui", "com.google.android.documentsui", "com.android.launcher3",
        "com.android.emergency", "com.android.dynsystem", "com.android.localtransport",
        "com.android.proxyhandler", "com.android.sharedstoragebackup", "com.android.vpndialogs",
        "com.android.companiondevicemanager", "com.android.mtp", "com.android.managedprovisioning",
        "com.android.devicelockcontroller", "com.android.stk", "com.android.simappdialog",
        "com.android.backupconfirm", "com.android.captiveportallogin", "com.google.android.captiveportallogin",
        "com.android.webview", "com.google.android.webview", "com.google.android.trichromelibrary",
        "com.android.contacts", "com.android.dialer", "com.google.android.dialer",
        "com.android.incallui", "com.android.mms", "com.android.updater", "com.android.settings.intelligence",
        "com.android.statementservice", "com.android.credentialmanager", "com.android.safetycenter.resources",
        "com.android.healthconnect.controller", "com.google.android.healthconnect.controller",
        "com.android.ondevicepersonalization.services", "com.android.adservices.api",
        "com.android.rkpdapp", "com.google.android.rkpdapp", "com.android.virtualmachine.res",
        "com.android.uwb.resources", "com.android.nearby.halfsheet", "com.android.sdksandbox",
        "com.google.android.sdksandbox", "com.android.federatedcompute.services",
        // Google core
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.ext.services", "com.google.android.ext.shared", "com.android.ext.services",
        "com.google.android.configupdater", "com.google.android.partnersetup",
        "com.google.android.setupwizard", "com.google.android.onetimeinitializer",
        "com.google.android.tts", "com.google.android.inputmethod.latin",
        // Xiaomi / HyperOS core
        "com.miui.securitycenter", "com.miui.home", "com.miui.core", "com.miui.system", "com.miui.rom",
        "com.miui.securitycore", "com.miui.securityadd", "com.miui.powerkeeper", "com.miui.guardprovider",
        "com.miui.packageinstaller", "com.miui.aod", "com.miui.notification", "com.miui.daemon",
        "com.miui.face", "com.miui.miwallpaper", "com.miui.systemui.carriers.overlay",
        "com.miui.settings.rro.device.config.overlay", "com.miui.securityinputmethod",
        "com.miui.misound", "com.miui.audiomonitor", "com.miui.cit", "com.miui.cloudbackup",
        "com.miui.cloudservice", "com.miui.micloudsync", "com.miui.contentcatcher", "com.miui.catcherpatch",
        "com.miui.privacycomputing", "com.miui.wmsvc", "com.miui.phrase", "com.miui.screenshot",
        "com.miui.mishare.connectivity", "com.miui.voicetrigger", "com.miui.uireporter",
        "com.xiaomi.finddevice", "com.xiaomi.account", "com.xiaomi.xmsf", "com.xiaomi.xmsfkeeper",
        "com.xiaomi.bluetooth", "com.xiaomi.location.fused", "com.xiaomi.misettings",
        "com.xiaomi.metoknlp", "com.xiaomi.micloud.sdk", "com.xiaomi.simactivate.service",
        "com.xiaomi.powerchecker", "com.xiaomi.aiasst.service", "com.xiaomi.security.onetrack",
        "com.xiaomi.xaee", "com.xiaomi.trustservice", "com.xiaomi.devauth", "com.xiaomi.otrpbroker",
        "com.lbe.security.miui", "com.android.thememanager", "com.mi.android.globallauncher",
        "com.miui.systemui.overlay.devices.android", "com.xiaomi.registration",
        // Shizuku: removing it would cut off this app's own access.
        "moe.shizuku.privileged.api",
    )

    private val prefixes = listOf(
        "com.android.internal.", "com.android.overlay", "com.android.systemui.", "com.android.providers.",
        "com.google.android.providers.", "com.android.networkstack", "com.google.android.networkstack",
        "com.android.theme.", "android.", "com.qualcomm.", "com.qti.", "vendor.", "com.mediatek.",
        "com.mtk.", "com.unisoc.", "com.sprd.", "com.google.android.overlay.", "com.android.bluetooth",
        "com.google.android.bluetooth", "com.android.wifi", "com.google.android.wifi",
        "com.android.cellbroadcast", "com.google.android.cellbroadcast", "com.android.ons",
        "com.android.hotspot2", "com.android.modulemetadata", "com.google.android.modulemetadata",
        "com.google.mainline.", "com.android.carrierconfig", "com.android.connectivity",
        "com.google.android.connectivity", "com.android.tethering", "com.google.android.tethering",
        "com.fingerprints.", "com.goodix.", "org.codeaurora.", "com.wapi.", "com.miui.rom.",
        "com.miui.overlay", "com.miui.system.", "com.miui.core.", "com.android.settings.overlay",
        "com.android.server.", "com.android.role", "com.android.appsearch", "com.google.android.appsearch",
        "com.android.permission", "com.android.os.", "com.android.art", "com.google.android.art",
        "com.android.media", "com.google.android.media", "com.android.uwb", "com.android.nfc",
        "com.android.telephony", "com.android.ims", "org.ifaa.", "com.tencent.soter",
    )

    fun isCore(pkg: String): Boolean = pkg in exact || prefixes.any { pkg.startsWith(it) }
}
