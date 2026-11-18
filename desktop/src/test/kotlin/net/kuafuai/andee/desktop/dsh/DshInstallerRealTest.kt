package net.kuafuai.andee.desktop.dsh

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Runs the real npm into a throwaway prefix. Spends the network and a minute; only with ANDEE_DSH_INSTALL_LIVE=<folder>. */
class DshInstallerRealTest {
    @Test fun aRealInstallLandsWhereTheAppLooksAndStarts() {
        val dir = System.getenv("ANDEE_DSH_INSTALL_LIVE")
        assumeTrue(dir != null)
        val prefix = File(dir!!)
        val i = DshInstaller(prefix = prefix, found = { DshWorkers.Bin("${prefix.path}/bin/dsh", DshInstaller.Node.find()!!.dir + ":/usr/bin:/bin") })
        println("COMMAND: " + i.commandLine())
        val r = i.install()
        println("OUTCOME: $r")
        assertTrue(r.toString(), r is DshInstaller.Outcome.Installed)
        assertTrue(File(prefix, "bin/dsh").canExecute())
    }
}
