package app.mizan.service

import java.util.concurrent.CountDownLatch

/**
 * Entry point for the reference service.
 *
 * ```text
 * ./gradlew :service:run --args="--port 8080 --host 127.0.0.1"
 * ```
 *
 * The service speaks plain HTTP. The Android client only writes to an HTTPS
 * URL, so put a TLS terminator in front of it before pointing a device at it.
 */
fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        println(ServiceOptions.help())
        return
    }
    val options = ServiceOptions.parse(args)
    val service = MizanService(
        ServiceConfig(
            host = options.host,
            port = options.port,
            allowSimulationHeader = options.allowSimulate,
        ),
    )
    val port = service.start()
    println("MIZAN reference service listening on http://" + options.host + ":" + port)
    println("  POST /v1/sessions    sign in with a configured account")
    println("  POST /v1/executions  decide and perform a governed ERP write")
    println("  GET  /v1/health      liveness and counters")
    println("The bundled accounts are labeled demo accounts. Replace them before any deployment.")
    Runtime.getRuntime().addShutdownHook(Thread { service.stop() })
    CountDownLatch(1).await()
}

data class ServiceOptions(
    val host: String,
    val port: Int,
    val allowSimulate: Boolean,
) {
    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8080

        fun parse(args: Array<String>): ServiceOptions {
            var host = DEFAULT_HOST
            var port = DEFAULT_PORT
            var allowSimulate = true
            var index = 0
            while (index < args.size) {
                val value = args.getOrNull(index + 1)
                when (args[index]) {
                    "--host" -> {
                        if (value != null) host = value
                        index++
                    }
                    "--port" -> {
                        val parsed = value?.toIntOrNull()
                        if (parsed != null) port = parsed
                        index++
                    }
                    "--no-simulate" -> allowSimulate = false
                    else -> Unit
                }
                index++
            }
            return ServiceOptions(host, port, allowSimulate)
        }

        fun help(): String = """
            MIZAN reference service

              --host <address>   bind address (default 127.0.0.1)
              --port <number>    bind port (default 8080, 0 picks a free port)
              --no-simulate      ignore the X-Mizan-Simulate header
              --help             this text
        """.trimIndent()
    }
}
