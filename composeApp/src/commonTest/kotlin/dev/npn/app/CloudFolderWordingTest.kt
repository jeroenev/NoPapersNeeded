package dev.npn.app

import dev.npn.app.ui.settings.CloudOAuthApps
import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.app.ui.settings.WebDav
import dev.npn.app.ui.settings.cloudFolderLabel
import dev.npn.app.ui.settings.plainSetup
import dev.npn.core.sync.RcloneCatalog
import dev.npn.core.sync.RcloneOption
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.sync.SetupStep
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The cloud folder in the user's words: the provider's name and folders, and WebDAV's own form. */
class CloudFolderWordingTest : MainDispatcherTest() {
    @Test
    fun aCloudFolderReadsAsItsProviderAndFolders() {
        cloudFolderLabel("drive-3fa9c1:NoPapersNeeded") shouldBe "Google Drive › NoPapersNeeded"
        cloudFolderLabel("onedrive-a1b2c3:Papers/Home") shouldBe "Microsoft OneDrive › Papers › Home"
        // A bare connection string names its type; its URL's colons are not the path's.
        cloudFolderLabel(":webdav,url=https://cloud.example.com/dav:NoPapersNeeded") shouldBe
            "WebDAV / Nextcloud › NoPapersNeeded"
        // A remote named some other way keeps its name.
        cloudFolderLabel("mine:Docs") shouldBe "mine › Docs"
    }

    /** First run offers the sign-in providers and WebDAV; the providers that need rclone's options wait. */
    @Test
    fun onlyProvidersSetUpInTheUsersOwnTermsLead() {
        val plain =
            RcloneCatalog.CHOICES.map {
                RcloneProvider(it.type, it.name, "", emptyList(), it.browserSignIn, it.advanced)
            }
        plain.filter { it.plainSetup }.map { it.type } shouldContainExactly
            listOf("drive", "onedrive", "dropbox", "box", "pcloud", "webdav")
    }

    /** The address a Nextcloud or ownCloud user knows becomes their server's WebDAV address. */
    @Test
    fun aServerAddressBecomesItsWebDavAddress() {
        fun url(values: Map<String, String>) = WebDav.params(values)[WebDav.URL]
        // An email login is not the user id a remote.php/dav/files/ address needs.
        url(mapOf("url" to "cloud.example.com/", "vendor" to "nextcloud", "user" to "me@example.com")) shouldBe
            "https://cloud.example.com/remote.php/webdav"
        url(mapOf("url" to "https://oc.example.com", "vendor" to "owncloud")) shouldBe
            "https://oc.example.com/remote.php/webdav"
        url(
            mapOf(
                "url" to "https://cloud.example.com/remote.php/dav/files/me/",
                "vendor" to "nextcloud",
                "user" to "me",
            ),
        ) shouldBe
            "https://cloud.example.com/remote.php/dav/files/me/"
        url(mapOf("url" to "http://nas.local/dav", "vendor" to "other")) shouldBe "http://nas.local/dav"
    }

    /** The WebDAV form starts on Nextcloud and hands rclone the address it made. */
    @Test
    fun theWebDavFormSignsInWithTheServersWebDavAddress() =
        runTest(dispatcher) {
            val setup =
                FakeCloudSetup().apply {
                    providersResult =
                        listOf(
                            RcloneProvider(
                                type = "webdav",
                                name = "WebDAV / Nextcloud",
                                description = "",
                                options =
                                    listOf(
                                        "url",
                                        "vendor",
                                        "user",
                                        "pass",
                                    ).map { RcloneOption(it, "", "string") },
                            ),
                        )
                    beginResult = { SetupStep.Done(it.name) }
                }
            val env = FakeAppEnvironment(backgroundScope, cloudSetup = setup)
            val vm = AppViewModel(env, newRemoteName = { "$it-1" }, oauthApps = CloudOAuthApps.NONE)
            collecting(vm.state)
            runCurrent()

            vm.cloudSetup.start()
            runCurrent()
            vm.cloudSetup.chooseProvider(
                vm.cloudSetupState.value
                    .shouldBeInstanceOf<CloudSetupState.Providers>()
                    .providers
                    .single(),
            )
            vm.cloudSetupState.value
                .shouldBeInstanceOf<CloudSetupState.Form>()
                .values[WebDav.VENDOR] shouldBe WebDav.NEXTCLOUD
            vm.cloudSetup.setValue(WebDav.URL, "cloud.example.com")
            vm.cloudSetup.setValue(WebDav.USER, "me")
            vm.cloudSetup.setValue(WebDav.PASS, "secret")
            vm.cloudSetup.submit()
            runCurrent()

            setup.begins.single().params shouldBe
                mapOf(
                    "url" to "https://cloud.example.com/remote.php/webdav",
                    "vendor" to "nextcloud",
                    "user" to "me",
                    "pass" to "secret",
                )
        }
}
