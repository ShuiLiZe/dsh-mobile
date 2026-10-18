package io.github.sayach.dshmobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Released MainActivity shortcuts stay callable while aliases own new launcher icon entries. */
class LauncherManifestTest {
    @Test
    fun oldExplicitMainActivityAndNewAliasesRemainUsable() {
        val manifest = sourceFile("AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NAMESPACE, "name") == ".MainActivity" }
        assertEquals("true", main.getAttributeNS(ANDROID_NAMESPACE, "exported"))
        assertEquals(0, main.getElementsByTagName("intent-filter").length)
        val aliases = doc.getElementsByTagName("activity-alias")
        val entries = (0 until aliases.length).map { aliases.item(it) as Element }
        // A single DSH launcher alias under a fresh name: states persisted for the former
        // whale aliases must not be able to hide the only launcher entry after an upgrade.
        assertEquals(listOf(".LauncherDsh"), entries.map { it.getAttributeNS(ANDROID_NAMESPACE, "name") })
        val alias = entries.single()
        assertEquals("true", alias.getAttributeNS(ANDROID_NAMESPACE, "enabled"))
        assertEquals("@mipmap/ic_launcher", alias.getAttributeNS(ANDROID_NAMESPACE, "icon"))
        assertEquals(".MainActivity", alias.getAttributeNS(ANDROID_NAMESPACE, "targetActivity"))
        assertEquals("true", alias.getAttributeNS(ANDROID_NAMESPACE, "exported"))
        val intents = alias.getElementsByTagName("intent-filter")
        assertEquals(1, intents.length)
        val filter = intents.item(0) as Element
        assertEquals("android.intent.action.MAIN",
            (filter.getElementsByTagName("action").item(0) as Element).getAttributeNS(ANDROID_NAMESPACE, "name"))
        assertEquals("android.intent.category.LAUNCHER",
            (filter.getElementsByTagName("category").item(0) as Element).getAttributeNS(ANDROID_NAMESPACE, "name"))
    }

    @Test
    fun launcherIconIsTheAdaptiveDshMarkWithAThemedLayer() {
        val icon = sourceFile("res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(icon.contains("@drawable/ic_launcher_foreground"))
        assertTrue(icon.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\""))
        val source = sourceFile("java/io/github/sayach/dshmobile/MainActivity.kt").readText()
        assertTrue(source.contains("setOnClickListener { showDeviceListSettings() }"))
        assertTrue(!source.contains("setComponentEnabledSetting"))
    }

    private fun sourceFile(path: String): File {
        val directories = listOf("src/main", "app/src/main", "apps/mobile/android/app/src/main")
        return generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { base -> directories.asSequence().map { File(base, "$it/$path") } }
            .first { it.isFile }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
