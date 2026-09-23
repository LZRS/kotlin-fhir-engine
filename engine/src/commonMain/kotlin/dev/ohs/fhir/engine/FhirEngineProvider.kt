/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.engine

import dev.ohs.fhir.engine.db.impl.DatabaseImpl
import dev.ohs.fhir.engine.impl.FhirEngineImpl
import dev.ohs.fhir.engine.index.ResourceIndexer
import dev.ohs.fhir.engine.index.SearchParamDefinition
import dev.ohs.fhir.engine.index.SearchParamDefinitionsProviderImpl
import dev.ohs.fhir.engine.sync.DataSource
import dev.ohs.fhir.engine.sync.FhirDataStore
import dev.ohs.fhir.engine.sync.getDataStore
import dev.ohs.fhir.engine.sync.remote.FhirHttpDataSource
import dev.ohs.fhir.engine.sync.remote.KtorHttpService

/**
 * Provides singleton access to the [FhirEngine] instance.
 *
 * Initialize with [init] before calling [getInstance]. On Android, pass the application `Context`
 * as `platformContext`. On Desktop and iOS, the parameter is ignored.
 *
 * ```
 * // Initialize (once, e.g. in Application.onCreate on Android)
 * FhirEngineProvider.init(FhirEngineConfiguration())
 *
 * val fhirEngine = FhirEngineProvider.getInstance(context)
 * ```
 */
object FhirEngineProvider {
  private var configuration: FhirEngineConfiguration? = null
  private var fhirEngine: FhirEngine? = null
  private var dataSource: DataSource? = null
  private var fhirDataStore: FhirDataStore? = null
  private var platformContext: Any = Unit
  private var searchParamProvider: SearchParamDefinitionsProviderImpl? = null

  /**
   * Initializes the [FhirEngineProvider] with the given [configuration].
   *
   * This must be called before [getInstance]. Calling it again after initialization will throw an
   * [IllegalStateException].
   */
  fun init(configuration: FhirEngineConfiguration, platformContext: Any = Unit) {
    check(this.configuration == null) { "FhirEngineProvider has already been initialized." }
    this.configuration = configuration
    this.platformContext = platformContext
    this.fhirDataStore =
      FhirDataStore(getDataStore(platformContext, configuration.storageDirectory))
  }

  fun isInitialized() = configuration != null

  fun isNotInitialized() = !isInitialized()

  /**
   * Returns the [FhirEngine] instance, creating it if necessary.
   *
   * @param platformContext Platform-specific context. On Android, this should be the application
   *   `Context`. On Desktop and iOS, pass `Unit` or omit.
   */
  fun getInstance(platformContext: Any = Unit): FhirEngine {
    val config =
      checkNotNull(configuration) {
        "FhirEngineProvider not initialized. Call FhirEngineProvider.init() first."
      }
    val context = if (platformContext == Unit) this.platformContext else platformContext
    if (fhirEngine == null) {
      fhirEngine = buildFhirEngine(context, config)
    }
    return fhirEngine!!
  }

  /**
   * Returns the [DataSource] instance, or `null` if no [ServerConfiguration] was provided.
   *
   * Only available after [init] has been called.
   */
  @PublishedApi
  internal fun getDataSource(): DataSource? {
    checkNotNull(configuration) {
      "FhirEngineProvider not initialized. Call FhirEngineProvider.init() first."
    }
    return dataSource
  }

  fun getFhirDataStore(): FhirDataStore =
    checkNotNull(fhirDataStore) {
      "FhirEngineProvider not initialized. Call FhirEngineProvider.init() first."
    }

  /**
   * Returns the [SearchParamDefinitionsProvider] created when the [FhirEngine] was built (which
   * includes any custom search parameters), or `null` if the engine hasn't been created yet. Used
   * by `XFhirQueryTranslator`.
   */
  internal fun getSearchParamProvider(): SearchParamDefinitionsProviderImpl? = searchParamProvider

  /**
   * Closes the database and the data source, and returns the provider to its uninitialized state.
   *
   * [init] must be called again before the next [getInstance], and any [FhirEngine] held from
   * before the reset must be discarded.
   *
   * Intended for tests and benchmarks, which need each run to start from a known engine. Two things
   * outlive a reset, and a caller depending on either being cleared has to arrange it some other
   * way:
   * - **Web cannot be reset in one page.** Closing there wedges the SQLite Web Worker, so
   *   [canCloseDatabaseOnReset] keeps the connection open; the first worker then holds the
   *   exclusive OPFS sync access handle, and a later [init] plus [getInstance] against a persistent
   *   store blocks on the reopen. Only an in-memory store (`testMode`) survives the round trip. A
   *   browser gets a cold engine by reloading the page.
   * - **The persisted data store is not cleared.** Every platform caches the underlying `DataStore`
   *   in a process-level singleton, so sync watermarks written before a reset are still there after
   *   it. A test needing them gone needs its own `storageDirectory`.
   *
   * Everywhere but web the database is closed, so an engine held across a reset fails on its next
   * call rather than writing to a database nothing owns.
   */
  fun reset() {
    if (canCloseDatabaseOnReset()) (fhirEngine as? FhirEngineImpl)?.closeDatabase()
    // Holds an HttpClient with its own engine and thread pool, which nulling the field alone would
    // leak once per init/reset cycle.
    dataSource?.close()
    fhirEngine = null
    dataSource = null
    configuration = null
    platformContext = Unit
    searchParamProvider = null
    fhirDataStore = null
  }

  private fun buildFhirEngine(
    platformContext: Any,
    config: FhirEngineConfiguration,
  ): FhirEngine {
    val searchParamDefinitionsProvider =
      SearchParamDefinitionsProviderImpl(customParams = buildCustomParamsMap(config))
    searchParamProvider = searchParamDefinitionsProvider
    val resourceIndexer = ResourceIndexer(searchParamDefinitionsProvider)
    val database =
      DatabaseImpl(
        platformContext,
        resourceIndexer,
        config.storageDirectory,
        inMemory = config.testMode,
      )

    config.serverConfiguration?.let { serverConfig ->
      dataSource =
        FhirHttpDataSource(
          KtorHttpService.builder(serverConfig.baseUrl, serverConfig.networkConfiguration)
            .setAuthenticator(serverConfig.authenticator)
            .setHttpLogger(serverConfig.httpLogger)
            .build(),
        )
    }

    return FhirEngineImpl(database)
  }

  private fun buildCustomParamsMap(
    config: FhirEngineConfiguration,
  ): Map<String, List<SearchParamDefinition>> {
    val params = config.customSearchParameters ?: return emptyMap()
    return params.groupBy { it.path.substringBefore(".") }
  }
}
