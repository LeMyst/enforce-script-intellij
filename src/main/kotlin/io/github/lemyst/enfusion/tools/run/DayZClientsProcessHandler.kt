package io.github.lemyst.enfusion.tools.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One or more DayZ clients in one *Run* tab. They are started one after the other off the EDT:
 * each may first wait (for a server started with it) and write its Goldberg identity, which the next
 * must not overwrite before it is read. With several clients each line of output says whose it is.
 * *Stop* stops them all, and the run ends when the last one exits.
 */
class DayZClientsProcessHandler(
    private val clients: List<Client>,
    private val waitMs: Long,
) : ProcessHandler() {

    /** A client to start: [prepare] runs just before, off the EDT, and returns a line for the console. */
    class Client(val name: String, val command: GeneralCommandLine, val prepare: () -> String?)

    private val started = CopyOnWriteArrayList<ProcessHandler>()

    @Volatile
    private var stopping = false

    @Volatile
    private var launching = true

    @Volatile
    private var lastExit = 0

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread(::launch)
    }

    private fun launch() {
        try {
            if (waitMs > 0) {
                say("Waiting ${waitMs / 1000.0} s before starting.")
                val until = System.currentTimeMillis() + waitMs
                while (!stopping && System.currentTimeMillis() < until) Thread.sleep(100)
            }
            for (client in clients) {
                if (stopping) break
                val prefix = if (clients.size > 1) "[${client.name}] " else ""
                try {
                    client.prepare()?.let { say(prefix + it) }
                    if (stopping) break
                    val child = KillableColoredProcessHandler(client.command)
                    child.addProcessListener(object : ProcessListener {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            notifyTextAvailable(prefix + event.text, outputType)
                        }

                        override fun processTerminated(event: ProcessEvent) {
                            lastExit = event.exitCode
                            finishIfDone()
                        }
                    })
                    started += child
                    child.startNotify()
                } catch (e: Exception) {
                    LOG.info("Could not start ${client.name}", e)
                    notifyTextAvailable("${prefix}Could not start: ${e.message}\n", ProcessOutputTypes.STDERR)
                }
            }
        } catch (_: InterruptedException) {
            // Stopped while waiting.
        } finally {
            launching = false
            finishIfDone()
        }
    }

    private fun say(line: String) = notifyTextAvailable("$line\n", ProcessOutputTypes.SYSTEM)

    @Synchronized
    private fun finishIfDone() {
        // While clients are still being started, the first one exiting does not end the run.
        if (launching || isProcessTerminated) return
        if (started.all { it.isProcessTerminated }) notifyProcessTerminated(lastExit)
    }

    override fun destroyProcessImpl() {
        stopping = true
        started.forEach { it.destroyProcess() }
        finishIfDone()
    }

    override fun detachProcessImpl() {
        stopping = true
        started.forEach { it.detachProcess() }
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    private companion object {
        val LOG = logger<DayZClientsProcessHandler>()
    }
}
