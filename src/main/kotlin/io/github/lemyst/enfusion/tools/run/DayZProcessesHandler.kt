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
 * A DayZ server and its clients, or either alone, in one *Run* tab. They are started one after the
 * other off the EDT: each may first wait (the clients, for the server to be up) and prepare (write
 * its Goldberg identity, which the next must not overwrite before it is read). With several
 * processes each line of output says whose it is. *Stop* stops them all, and the run ends when the
 * last one exits.
 */
class DayZProcessesHandler(private val processes: List<Process>) : ProcessHandler() {

    /**
     * A process to start, [delayMs] after the previous one. [prepare] runs just before it, off the
     * EDT, and returns a line for the console.
     */
    class Process(val name: String, val command: GeneralCommandLine, val delayMs: Long = 0, val prepare: () -> String? = { null })

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
            for (process in processes) {
                if (stopping) break
                val prefix = if (processes.size > 1) "[${process.name}] " else ""
                if (process.delayMs > 0) {
                    say("${prefix}Starting in ${process.delayMs / 1000.0} s.")
                    val until = System.currentTimeMillis() + process.delayMs
                    while (!stopping && System.currentTimeMillis() < until) Thread.sleep(100)
                    if (stopping) break
                }
                try {
                    process.prepare()?.let { say(prefix + it) }
                    if (stopping) break
                    val child = KillableColoredProcessHandler(process.command)
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
                    LOG.info("Could not start ${process.name}", e)
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
        // While processes are still being started, the first one exiting does not end the run.
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
        val LOG = logger<DayZProcessesHandler>()
    }
}
