
package org.jetbrains.kotlin.jupyter

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.int
import com.beust.klaxon.string
import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.repl.LineResult
import org.jetbrains.kotlin.cli.jvm.repl.ReplInterpreter
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.utils.PathUtil
import java.util.concurrent.atomic.AtomicLong

fun main(vararg args: String) {
    try {
        val cfgFile = args[0]
        val cfgJson = Parser().parse(cfgFile) as JsonObject
        fun JsonObject.getInt(field: String): Int = int(field) ?: throw RuntimeException("Cannot find $field in $cfgFile")

        val sigScheme = cfgJson.string("signature_scheme")
        val key = cfgJson.string("key")

        kernelServer(ConnectionConfig(
                ports = JupyterSockets.values().map { cfgJson.getInt("${it.name}_port") }.toTypedArray(),
                transport = cfgJson.string("transport") ?: "tcp",
                signatureScheme = sigScheme ?: "hmac1-sha256",
                signatureKey = if (sigScheme == null || key == null) "" else key
        ))
    }
    catch (e: Exception) {
        log.error("exception running kernel with args: \"${args.joinToString()}\"", e)
    }
}

fun kernelServer(config: ConnectionConfig) {
    log.info("Starting server: $config")

    JupyterConnection(config).use { conn ->

        log.info("start listening")

        val executionCount = AtomicLong(1)
        val disposable = Disposable { }
        val messageCollector = PrintingMessageCollector(conn.iopubErr, MessageRenderer.WITHOUT_PATHS, false)
        val compilerConfiguration = CompilerConfiguration().apply {
            addJvmClasspathRoots(PathUtil.getJdkClassesRoots())
            put(CommonConfigurationKeys.MODULE_NAME, "jupyter")
            put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        }
        val repl = ReplInterpreter(disposable, compilerConfiguration, ReplForJupyterConfiguration(conn))

        while (!Thread.currentThread().isInterrupted) {

            try {
                conn.heartbeat.onData { send(it, 0) }
                conn.stdin.onData { logWireMessage(it) }
                conn.shell.onMessage { shellMessagesHandler(it, repl, executionCount) }
                // TODO: consider listening control on a separate thread, as recommended by the kernel protocol
                conn.control.onMessage { shellMessagesHandler(it, null, executionCount) }

                Thread.sleep(config.pollingIntervalMillis)
            }
            catch (e: InterruptedException) {
                log.info("Interrupted")
                break
            }
        }

        log.info("Shutdown server")
    }
}

fun JupyterConnection.Socket.shellMessagesHandler(msg: Message, repl: ReplInterpreter?, executionCount: AtomicLong) {
    when (msg.header!!["msg_type"]) {
        "kernel_info_request" ->
            send(makeReplyMessage(msg, "kernel_info_reply",
                    content = jsonObject(
                            "protocol_version" to protocolVersion,
                            "language" to "kotlin",
                            "language_version" to "1.1-SNAPSHOT"
                    )))
        "history_request" ->
            send(makeReplyMessage(msg, "history_reply",
                    content = jsonObject(
                            "history" to listOf<String>() // not implemented
                    )))
        "shutdown_request" -> {
            send(makeReplyMessage(msg, "shutdown_reply", content = msg.content))
            Thread.currentThread().interrupt()
        }
        "connect_request" ->
            send(makeReplyMessage(msg, "connection_reply",
                    content = jsonObject(JupyterSockets.values()
                                            .map { Pair("${it.name}_port", connection.config.ports[it.ordinal]) })))
        "execute_request" -> {
            val count = executionCount.getAndIncrement()
            val startedTime = ISO8601DateNow
            with (connection.iopub) {
                send(makeReplyMessage(msg, "status", content = jsonObject("execution_state" to "busy")))
                send(makeReplyMessage(msg, "execute_input", content = jsonObject(
                        "execution_count" to count,
                        "code" to msg.content["code"])))
                val res = connection.evalWithIO { repl?.eval(msg.content["code"].toString()) }
                val resStr = when (res) {
                    is LineResult.ValueResult -> res.valueAsString
                    is LineResult.UnitResult -> "Ok"
                    is LineResult.Error -> "Error: ${res.errorText}"
                    is LineResult.Incomplete -> "..."
                    null -> "no repl"
                }
                send(makeReplyMessage(msg, "execute_result", content = jsonObject(
                        "execution_count" to count,
                        "data" to JsonObject(jsonObject("text/plain" to resStr)),
                        "metadata" to JsonObject())))
                send(makeReplyMessage(msg, "status", content = jsonObject("execution_state" to "idle")))
            }
            send(makeReplyMessage(msg, "execute_reply",
                    metadata = jsonObject(
                            "dependencies_met" to true,
                            "engine" to msg.header["session"],
                            "status" to "ok",
                            "started" to startedTime),
                    content = jsonObject(
                            "status" to "ok",
                            "execution_count" to count,
                            "user_variables" to JsonObject(),
                            "payload" to listOf<String>(),
                            "user_expressions" to JsonObject())
                    ))
        }
        "is_complete_request" -> {
            send(makeReplyMessage(msg, "is_complete_reply", content = jsonObject("status" to "complete")))
        }
        else -> send(makeReplyMessage(msg, "unsupported_message_reply"))
    }
}

