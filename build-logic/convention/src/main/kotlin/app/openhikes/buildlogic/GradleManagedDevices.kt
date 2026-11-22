package app.openhikes.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ManagedVirtualDevice

@Suppress("UnstableApiUsage")
internal fun configureGradleManagedDevices(commonExtension: CommonExtension) {
    commonExtension.testOptions.managedDevices {
        val phone = localDevices.create("pixelPhoneApi36") {
            device = "Pixel 8"
            sdkVersion = 36
            systemImageSource = "aosp-atd"
            pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
        }
        val tablet = localDevices.create("pixelTabletApi36") {
            device = "Pixel Tablet"
            sdkVersion = 36
            systemImageSource = "aosp-atd"
            pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
        }
        groups.create("allDevices") {
            targetDevices.add(phone)
            targetDevices.add(tablet)
        }
    }
}
