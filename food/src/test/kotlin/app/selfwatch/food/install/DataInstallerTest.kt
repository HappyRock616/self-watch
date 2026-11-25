/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.install

import app.selfwatch.db.SettingKeys
import app.selfwatch.food.FoodsDbFixture
import app.selfwatch.food.source.FoodDbFormat
import app.selfwatch.food.source.FoodsDbHandle
import app.selfwatch.food.source.FoodsDbSource
import app.selfwatch.model.fake.FakeHttp
import app.selfwatch.model.net.HttpResponse
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataInstallerTest {
    private val http = FakeHttp()
    private val store = MemoryVersionStore()
    private lateinit var dir: File
    private lateinit var work: File
    private lateinit var scope: CoroutineScope
    private lateinit var handle: FoodsDbHandle
    private lateinit var installer: FoodDbInstaller

    private class MemoryVersionStore : VersionStore {
        var v: Int? = null

        override suspend fun get(): Int? = v

        override suspend fun set(version: Int?) {
            v = version
        }
    }

    @Before
    fun setUp() {
        val files = RuntimeEnvironment.getApplication().filesDir
        dir = File(files, "fooddata")
        work = File(files, "work")
        dir.deleteRecursively()
        work.deleteRecursively()
        work.mkdirs()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        handle = FoodsDbHandle(File(dir, "foods.db"), scope)
        installer = FoodDbInstaller(http, store, dir, MANIFEST_URL, appVersion = 3, handle = handle)
    }

    @After
    fun tearDown() {
        runBlocking { handle.close() }
        scope.cancel()
    }

    /** A gzipped fixture with [dataVersion] (and optional breakage), as served bytes. */
    private fun release(dataVersion: Int, applicationId: Int? = null, userVersion: Int = FoodDbFormat.SCHEMA_VERSION, rows: Boolean = true): ByteArray {
        val db = File(work, "build-$dataVersion-${applicationId ?: 0}-$userVersion-$rows.db")
        if (applicationId != null) {
            FoodsDbFixture.buildFoods(db, applicationId = applicationId, userVersion = userVersion, withRows = rows, dataVersion = dataVersion)
        } else {
            FoodsDbFixture.buildFoods(db, userVersion = userVersion, withRows = rows, dataVersion = dataVersion)
        }
        val gz = File(work, db.name + ".gz")
        FoodsDbFixture.gzip(db, gz)
        return gz.readBytes()
    }

    private fun manifest(dataVersion: Int, bytes: ByteArray, schema: Int = FoodDbFormat.SCHEMA_VERSION, minApp: Int = 1, sha: String? = null): String {
        val f = File(work, "m.bin").apply { writeBytes(bytes) }
        val hash = sha ?: DataInstaller.sha256(f)
        return """{"schema_version":$schema,"data_version":$dataVersion,"min_app_version":$minApp,"file":"foods.db.gz",
            "url":"$FILE_URL","sha256":"$hash","size_gz":${bytes.size},"size_db":1,"rows":11,"built_at":"2026-11-22T21:30:00Z"}"""
    }

    private fun serve(manifestJson: String, body: ByteArray) {
        http.enqueue(200, manifestJson)
        http.enqueue(Outcome.Ok(HttpResponse(200, emptyMap(), body)))
    }

    private suspend fun installedDataVersion(): String? = FoodsDbSource(handle).meta()["data_version"]

    private suspend fun installFirst(): ByteArray {
        val v1 = release(20261122)
        serve(manifest(20261122, v1), v1)
        val check = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertEquals(Outcome.Ok(20261122), installer.install(check.manifest))
        return v1
    }

    @Test
    fun installsAndUpdatesThroughTheManifest() = runBlocking {
        assertFalse(installer.isInstalled)
        assertNull(installer.installedVersion())
        installFirst()
        assertTrue(installer.isInstalled)
        assertEquals(20261122, store.v)
        assertEquals("20261122", installedDataVersion())
        assertEquals(listOf(NetFeature.FOOD_DATA, NetFeature.FOOD_DATA), http.requests().map { it.feature })
        assertEquals(listOf(MANIFEST_URL, FILE_URL), http.requests().map { it.url })
        assertFalse(File(dir, "foods.db.new").exists())
        assertTrue(File(dir, "tmp").listFiles().isNullOrEmpty())

        // Same version again: up to date.
        http.enqueue(200, manifest(20261122, ByteArray(1)))
        assertEquals(UpdateCheck.UpToDate, (installer.checkForUpdate() as Outcome.Ok).value)

        // A newer release replaces the file while the handle was open.
        assertTrue(FoodsDbSource(handle).search("big mac", 5).isNotEmpty())
        val v2 = release(20261201)
        serve(manifest(20261201, v2), v2)
        val check = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertEquals(Outcome.Ok(20261201), installer.install(check.manifest))
        assertEquals("20261201", installedDataVersion())
        assertEquals(20261201, installer.installedVersion())
    }

    @Test
    fun badChecksumKeepsTheOldFile() = runBlocking {
        installFirst()
        val before = File(dir, "foods.db").readBytes()
        val v2 = release(20261201)
        val m = DataManifest.parse(manifest(20261201, v2, sha = "0".repeat(64))) as Outcome.Ok
        http.enqueue(Outcome.Ok(HttpResponse(200, emptyMap(), v2)))
        val r = installer.install(m.value)
        assertEquals(Outcome.Fail(SwError.BadInput("checksum mismatch")), r)
        assertKept(before)
    }

    @Test
    fun wrongApplicationIdKeepsTheOldFile() = runBlocking {
        installFirst()
        val before = File(dir, "foods.db").readBytes()
        val bad = release(20261201, applicationId = 1234)
        serve(manifest(20261201, bad), bad)
        val check = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertTrue(installer.install(check.manifest) is Outcome.Fail)
        assertKept(before)
    }

    @Test
    fun wrongSchemaKeepsTheOldFile() = runBlocking {
        installFirst()
        val before = File(dir, "foods.db").readBytes()
        // The manifest says schema 3: not offered.
        val v2 = release(20261201)
        http.enqueue(200, manifest(20261201, v2, schema = 3))
        assertEquals(UpdateCheck.NeedsNewerApp(3, 1), (installer.checkForUpdate() as Outcome.Ok).value)
        http.enqueue(200, manifest(20261201, v2, minApp = 9))
        assertEquals(UpdateCheck.NeedsNewerApp(2, 9), (installer.checkForUpdate() as Outcome.Ok).value)
        // The file itself says user_version 3 although the manifest says 2.
        val lying = release(20261201, userVersion = 3)
        serve(manifest(20261201, lying), lying)
        val check = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertEquals(Outcome.Fail(SwError.BadInput("user_version 3")), installer.install(check.manifest))
        // An empty food table fails the row check.
        val empty = release(20261202, rows = false)
        serve(manifest(20261202, empty), empty)
        val check2 = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertEquals(Outcome.Fail(SwError.BadInput("no rows")), installer.install(check2.manifest))
        // The manifest's data_version must match the file's.
        val mismatch = release(20261203)
        serve(manifest(20261299, mismatch), mismatch)
        val check3 = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertTrue(installer.install(check3.manifest) is Outcome.Fail)
        assertKept(before)
    }

    @Test
    fun interruptedDownloadKeepsTheOldFile() = runBlocking {
        installFirst()
        val before = File(dir, "foods.db").readBytes()
        val v2 = release(20261201)
        // The connection drops.
        http.enqueue(200, manifest(20261201, v2))
        http.enqueue(Outcome.Fail(SwError.Io("connection reset")))
        val check = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertEquals(Outcome.Fail(SwError.Io("connection reset")), installer.install(check.manifest))
        assertKept(before)
        // Half the bytes arrive (the checksum catches it).
        http.enqueue(Outcome.Ok(HttpResponse(200, emptyMap(), v2.copyOf(v2.size / 2))))
        assertTrue(installer.install(check.manifest) is Outcome.Fail)
        assertKept(before)
        // A truncated gzip with a matching checksum fails to unpack.
        val half = v2.copyOf(v2.size / 2)
        serve(manifest(20261201, half), half)
        val check2 = (installer.checkForUpdate() as Outcome.Ok).value as UpdateCheck.Available
        assertEquals(Outcome.Fail(SwError.BadInput("unpack failed")), installer.install(check2.manifest))
        assertKept(before)
        // Blocked by the firewall, or the server errors.
        http.blocked.add(NetFeature.FOOD_DATA)
        assertEquals(Outcome.Fail(SwError.Blocked(NetFeature.FOOD_DATA)), installer.checkForUpdate())
        assertEquals(Outcome.Fail(SwError.Blocked(NetFeature.FOOD_DATA)), installer.install(check.manifest))
        http.blocked.clear()
        http.enqueue(500, "oops")
        assertTrue(installer.checkForUpdate() is Outcome.Fail)
        http.enqueue(200, "{not json")
        assertTrue(installer.checkForUpdate() is Outcome.Fail)
        assertKept(before)
    }

    @Test
    fun loadFromFileAcceptsGzipOrPlainAndRemoveDeletes() = runBlocking {
        val plain = File(work, "picked.db")
        FoodsDbFixture.buildFoods(plain, dataVersion = 20261111)
        assertEquals(Outcome.Ok(20261111), installer.installFrom(plain.inputStream()))
        assertEquals(20261111, store.v)

        val gz = release(20261212)
        assertEquals(
            Outcome.Fail(SwError.BadInput("checksum mismatch")),
            installer.installFrom(gz.inputStream(), expectedSha256 = "a".repeat(64)),
        )
        assertEquals("20261111", installedDataVersion())
        val sha = DataInstaller.sha256(File(work, "x.gz").apply { writeBytes(gz) })
        assertEquals(Outcome.Ok(20261212), installer.installFrom(gz.inputStream(), expectedSha256 = sha.uppercase()))
        assertEquals("20261212", installedDataVersion())

        val junk = "definitely not a database".toByteArray()
        assertTrue(installer.installFrom(junk.inputStream()) is Outcome.Fail)
        assertEquals("20261212", installedDataVersion())

        installer.remove()
        assertFalse(installer.isInstalled)
        assertNull(store.v)
        assertTrue(FoodsDbSource(handle).search("big mac", 5).isEmpty())
    }

    @Test
    fun recipeBookUsesItsOwnApplicationId() = runBlocking {
        val recipeStore = MemoryVersionStore()
        val book = RecipeBookInstaller(http, recipeStore, dir, "https://example.org/recipes-manifest.json", appVersion = 1)
        val db = File(work, "recipes.db")
        FoodsDbFixture.buildRecipes(db, dataVersion = 20261201)
        assertEquals(Outcome.Ok(20261201), book.installFrom(db.inputStream()))
        assertTrue(File(dir, "recipes.db").isFile)
        assertEquals(20261201, recipeStore.v)

        // A foods.db is not a recipe book.
        val foods = File(work, "foods-as-recipes.db")
        FoodsDbFixture.buildFoods(foods)
        assertTrue(book.installFrom(foods.inputStream()) is Outcome.Fail)
        assertEquals(20261201, recipeStore.v)
        http.enqueue(200, "{}")
        book.checkForUpdate()
        assertEquals(NetFeature.RECIPE_BOOK, http.requests().last().feature)
        assertEquals("recipes.db.installed_version", RecipeBookInstaller.KEY_INSTALLED_VERSION)
        assertEquals("food.db.installed_version", SettingKeys.FOOD_DB_INSTALLED_VERSION)
    }

    @Test
    fun manifestParsing() {
        val ok = DataManifest.parse(
            """{"schema_version":1,"data_version":20261122,"min_app_version":1,"file":"foods.db.gz","url":"https://x/foods.db.gz",
            "sha256":"${"AB".repeat(32)}","size_gz":24123456,"size_db":81234567,"rows":123456,"built_at":"2026-11-22T21:30:00Z"}""",
        ) as Outcome.Ok
        assertEquals("ab".repeat(32), ok.value.sha256)
        assertEquals(24123456L, ok.value.sizeGz)
        assertTrue(DataManifest.parse("""{"schema_version":1,"data_version":1,"url":"u","sha256":"short"}""") is Outcome.Fail)
        assertTrue(DataManifest.parse("""{"data_version":1,"url":"u","sha256":"${"a".repeat(64)}"}""") is Outcome.Fail)
        assertTrue(DataManifest.parse("[]") is Outcome.Fail)
    }

    private fun assertKept(before: ByteArray) {
        assertArrayEquals("old foods.db kept", before, File(dir, "foods.db").readBytes())
        assertEquals(20261122, store.v)
        assertFalse(File(dir, "foods.db.new").exists())
        assertTrue(File(dir, "tmp").listFiles().isNullOrEmpty())
    }

    private companion object {
        const val MANIFEST_URL = "https://example.org/foods-manifest.json"
        const val FILE_URL = "https://example.org/foods.db.gz"
    }
}
