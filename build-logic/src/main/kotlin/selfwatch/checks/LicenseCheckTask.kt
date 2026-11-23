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
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * G07: every library that ships inside the app must have a licence on the allow-list.
 *
 * Walks the resolved [configurationName] (by default `releaseRuntimeClasspath`), downloads each
 * library's POM (and parent POMs when the licence is inherited), and checks every
 * `<license><name>` against the regular expressions in [allowList]. A library passes when
 * at least one of its licences matches (dual-licensed libraries are fine if one option is allowed).
 * Fails on a library with no licence or none allowed. Writes a report of all libraries.
 *
 * Run: `./gradlew :app:licenseCheck`. Not part of `build` because it resolves POMs at execution
 * time, which the configuration cache does not support; CI runs it as its own job.
 */
abstract class LicenseCheckTask : DefaultTask() {

    @get:Input
    abstract val configurationName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowList: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        notCompatibleWithConfigurationCache("Resolves POM files through the project at execution time.")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val allowed = allowList.get().asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Regex(it, RegexOption.IGNORE_CASE) }

        @Suppress("DEPRECATION") // project access at execution is accepted: task opts out of the configuration cache
        val conf = project.configurations.getByName(configurationName.get())
        val modules = conf.incoming.resolutionResult.allComponents
            .map { it.id }
            .filterIsInstance<ModuleComponentIdentifier>()
            .sortedBy { "${it.group}:${it.module}" }

        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (m in modules) {
            val gav = "${m.group}:${m.module}:${m.version}"
            val licences = licencesOf(m.group, m.module, m.version, depth = 0)
            val ok = licences.any { name -> allowed.any { it.containsMatchIn(name) } }
            val shown = licences.ifEmpty { listOf("<no license in POM>") }.joinToString(" | ")
            lines += "${if (ok) "ok  " else "FAIL"} $gav  $shown"
            if (!ok) failures += "$gav  ($shown)"
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n", postfix = "\n"))
        logger.lifecycle("License check: ${modules.size} libraries, report at ${out.path}")
        if (failures.isNotEmpty()) {
            throw GradleException(
                "Libraries with a license outside the allow-list (${allowList.get().asFile.name}):\n" +
                    failures.joinToString("\n") { "  $it" },
            )
        }
    }

    /** Licence names from the POM, following `<parent>` when the POM has none. */
    private fun licencesOf(group: String, name: String, version: String, depth: Int): List<String> {
        if (depth > 6) return emptyList()
        val pom = resolvePom(group, name, version) ?: return emptyList()
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(pom)
        val project = doc.documentElement
        val licences = child(project, "licenses")?.let { children(it, "license") }.orEmpty()
            .mapNotNull { lic -> child(lic, "name")?.textContent?.trim() ?: child(lic, "url")?.textContent?.trim() }
            .filter { it.isNotEmpty() }
        if (licences.isNotEmpty()) return licences
        val parent = child(project, "parent") ?: return emptyList()
        val pg = child(parent, "groupId")?.textContent?.trim() ?: return emptyList()
        val pa = child(parent, "artifactId")?.textContent?.trim() ?: return emptyList()
        val pv = child(parent, "version")?.textContent?.trim() ?: return emptyList()
        return licencesOf(pg, pa, pv, depth + 1)
    }

    private fun resolvePom(group: String, name: String, version: String): File? {
        @Suppress("DEPRECATION")
        val p = project
        val dep = p.dependencies.create("$group:$name:$version@pom")
        val detached = p.configurations.detachedConfiguration(dep).apply { isTransitive = false }
        return runCatching { detached.resolve().firstOrNull() }
            .onFailure { logger.warn("licenseCheck: could not resolve the POM of $group:$name:$version (" +
                "a missing entry in gradle/verification-metadata.xml looks like this): ${it.message}") }
            .getOrNull()
    }

    private fun child(e: Element, tag: String): Element? = children(e, tag).firstOrNull()

    private fun children(e: Element, tag: String): List<Element> {
        val nodes = e.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }
}
