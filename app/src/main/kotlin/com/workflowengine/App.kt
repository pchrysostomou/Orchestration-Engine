package com.workflowengine

import com.workflowengine.server.module
import com.workflowengine.worker.WorkerMain
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    if (args.firstOrNull() == "worker") {
        WorkerMain.start()
        return
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = { module() })
        .start(wait = true)
}
