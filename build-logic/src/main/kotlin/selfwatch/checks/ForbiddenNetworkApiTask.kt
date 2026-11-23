/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package selfwatch.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * G09: only the `:net` module may talk to the network. Fails when production Kotlin or Java
 * sources in any other module mention java.net URL/HTTP/socket classes, OkHttp, or javax.net.ssl.
 * Test sources are exempt. Wired into the root `check`, so `./gradlew build` runs it.
 * Run alone: `./gradlew forbiddenNetworkApi`.
 */
abstract class ForbiddenNetworkApiTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** Empty marker so the task is up to date when no source changed. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val root = rootDirectory.get().asFile
        val hits = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                FORBIDDEN.firstOrNull { it.containsMatchIn(code) }?.let {
                    hits += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                }
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(hits.joinToString("\n"))
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Network APIs are only allowed in :net (all traffic goes through its firewall).\n" +
                    hits.joinToString("\n") { "  $it" },
            )
        }
    }

    companion object {
        val FORBIDDEN = listOf(
            Regex("""\bjava\.net\.(\*|URL\b|URLConnection\b|HttpURLConnection\b|Socket\b|ServerSocket\b|DatagramSocket\b|SocketAddress\b|InetSocketAddress\b|Proxy\b|http\.)"""),
            Regex("""\bHttpURLConnection\b"""),
            Regex("""\bHttpsURLConnection\b"""),
            Regex("""\bokhttp3?\b""", RegexOption.IGNORE_CASE),
            Regex("""\bjavax\.net\.(ssl\b|\*|SocketFactory\b)"""),
        )
    }
}
