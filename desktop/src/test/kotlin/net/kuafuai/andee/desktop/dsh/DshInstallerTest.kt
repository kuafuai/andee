package net.kuafuai.andee.desktop.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DshInstallerTest {
    private val prefix = Files.createTempDirectory("npm-global").toFile()
    private val node = DshInstaller.Node("/fake/bin/npm", "/fake/bin")
    private val bin = DshWorkers.Bin("${prefix.path}/bin/dsh", "/fake/bin")

    private fun installer(
        run: (List<String>, Map<String, String>, Long) -> DshInstaller.Result,
        found: () -> DshWorkers.Bin? = { bin },
        locate: () -> DshInstaller.Node? = { node },
    ) = DshInstaller(locate, prefix, run, found)

    @Test fun theCommandIsOneFixedLineAndItIsShownBeforeItRuns() {
        val i = installer({ _, _, _ -> error("nothing runs for commandLine()") })
        assertEquals("npm install -g --prefix ${prefix.path} @deepseek-ai/dsh", i.commandLine())
    }

    @Test fun anInstallIsReportedOnlyWhenTheAppCanFindDshAndItStarts() {
        val ran = mutableListOf<List<String>>()
        val i = installer({ cmd, _, _ ->
            ran += cmd
            if (cmd.last() == "--version") DshInstaller.Result(0, "0.2.2-rc.1\n") else DshInstaller.Result(0, "added 330 packages")
        })
        val r = i.install() as DshInstaller.Outcome.Installed
        assertEquals("0.2.2-rc.1", r.version)
        // npm with exactly these arguments, in the user's own prefix: nothing taken from anywhere else.
        assertEquals(listOf("/fake/bin/npm", "install", "-g", "--prefix", prefix.path, "@deepseek-ai/dsh"), ran[0])
        assertEquals(listOf(bin.dsh, "--version"), ran[1])
    }

    @Test fun npmSayingItSucceededIsNotEnough_ThisIsTheBugThatWasReported() {
        // The install "worked" but left dsh somewhere the app does not look: the Mac still said it was missing.
        val i = installer({ _, _, _ -> DshInstaller.Result(0, "added 1 package") }, found = { null })
        val r = i.install() as DshInstaller.Outcome.Failed
        assertTrue(r.why, r.why.contains("not where this app looks"))
    }

    @Test fun anInstalledDshThatDoesNotStartIsAFailure() {
        val i = installer({ cmd, _, _ -> if (cmd.last() == "--version") DshInstaller.Result(1, "") else DshInstaller.Result(0, "ok") })
        assertTrue(i.install() is DshInstaller.Outcome.Failed)
    }

    @Test fun aFailedNpmSaysWhyAndKeepsTheTailOfItsOutput() {
        val noisy = (1..40).joinToString("\n") { "line $it" } + "\nnpm error code EACCES"
        val i = installer({ _, _, _ -> DshInstaller.Result(243, noisy) })
        val r = i.install() as DshInstaller.Outcome.Failed
        assertTrue(r.why.contains("243"))
        assertTrue(r.output.contains("EACCES"))
        assertTrue("only the tail", !r.output.contains("line 1\n"))
    }

    @Test fun aTimeoutIsAFailureNotAHang() {
        val i = installer({ _, _, _ -> DshInstaller.Result(-1, "still downloading", timedOut = true) })
        assertTrue((i.install() as DshInstaller.Outcome.Failed).why.contains("did not finish"))
    }

    @Test fun noNodeMeansNothingIsRun() {
        var ran = false
        val i = installer({ _, _, _ -> ran = true; DshInstaller.Result(0, "") }, locate = { null })
        assertEquals(DshInstaller.Outcome.NoNode, i.install())
        assertTrue("npm was never started", !ran)
    }

    @Test fun theAppLooksWhereTheInstallPutsIt() {
        // Bin.find's fallback list names the installer's prefix, so install and detection cannot drift apart again.
        val src = File("src/main/kotlin/net/kuafuai/andee/desktop/dsh/DshWorkers.kt").readText()
        assertTrue(src.contains("DshInstaller.PREFIX.path + \"/bin/dsh\""))
    }
}
