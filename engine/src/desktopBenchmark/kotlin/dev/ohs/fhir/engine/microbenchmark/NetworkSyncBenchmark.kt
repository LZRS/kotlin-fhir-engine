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
package dev.ohs.fhir.engine.microbenchmark

import dev.ohs.fhir.engine.NetworkConfiguration
import dev.ohs.fhir.engine.db.impl.fhirJsonParser
import dev.ohs.fhir.engine.sync.download.UrlDownloadRequest
import dev.ohs.fhir.engine.sync.remote.FhirHttpDataSource
import dev.ohs.fhir.engine.sync.remote.KtorHttpService
import dev.ohs.fhir.engine.sync.upload.request.BundleUploadRequest
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Uri
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * What a sync costs the client per page, with the network taken out.
 *
 * The server is a [MockEngine] answering from a pre-encoded body, so no socket is opened and no
 * latency is included. What is left is the engine's own share of a page: building the request,
 * running it through Ktor's pipeline, and parsing the response into resources. On an on-device run
 * a page of 100 took about 1.6 s end to end, nearly all of it waiting; this is the part that
 * remains when the waiting is removed, and the only part the engine can improve.
 *
 * [pageSize] is the server's `_count`. Both arms scale with it, so read them per resource.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class NetworkSyncBenchmark {

  @Param("100", "500") var pageSize: Int = 0

  private lateinit var dataSource: FhirHttpDataSource
  private lateinit var downloadRequest: UrlDownloadRequest
  private lateinit var uploadRequest: BundleUploadRequest

  @Setup
  fun setUp() {
    // Encoded once. Building it inside the mock would put the server's serialization into every
    // measurement of the client's parsing.
    val downloadBody = fhirJsonParser.encodeToString(searchSetOf(pageSize))
    val uploadBody =
      fhirJsonParser.encodeToString(
        Bundle(
          id = "transaction-response",
          type = Enumeration(value = Bundle.BundleType.Transaction_Response),
        ),
      )

    val service =
      KtorHttpService.Builder(BASE_URL, NetworkConfiguration())
        .build(
          engine =
            MockEngine { request ->
              respond(
                content = if (request.method.value == "GET") downloadBody else uploadBody,
                status = HttpStatusCode.OK,
                headers =
                  headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
              )
            },
        )
    dataSource = FhirHttpDataSource(service)

    downloadRequest = UrlDownloadRequest(url = "Patient?_count=$pageSize")
    uploadRequest = BundleUploadRequest(resource = transactionOf(pageSize))

    // A page that parsed to nothing would leave both arms measuring an empty round trip.
    val downloaded = runBlocking { dataSource.download(downloadRequest) }
    check(downloaded is Bundle && downloaded.entry.size == pageSize) {
      "the mocked page parsed to ${(downloaded as? Bundle)?.entry?.size} entries, expected $pageSize"
    }
  }

  /** A page of search results: request, pipeline, and a parse of every resource in the bundle. */
  @Benchmark
  fun downloadPage(blackhole: Blackhole) =
    blackhole.consume(runBlocking { dataSource.download(downloadRequest) })

  /** The other direction: serializing a transaction bundle and parsing the response. */
  @Benchmark
  fun uploadBundle(blackhole: Blackhole) =
    blackhole.consume(runBlocking { dataSource.upload(uploadRequest) })

  private fun searchSetOf(entries: Int) =
    Bundle(
      id = "page",
      type = Enumeration(value = Bundle.BundleType.Searchset),
      entry =
        (0 until entries).map {
          Bundle.Entry(
            fullUrl = Uri(value = "$BASE_URL${EngineBenchmarkDatabase.patientId(it)}"),
            resource = EngineBenchmarkDatabase.patient(it) as Resource,
          )
        },
    )

  private fun transactionOf(entries: Int) =
    Bundle(
      id = "upload",
      type = Enumeration(value = Bundle.BundleType.Transaction),
      entry =
        (0 until entries).map {
          Bundle.Entry(resource = EngineBenchmarkDatabase.patient(it) as Resource)
        },
    )

  private companion object {
    const val BASE_URL = "http://localhost/fhir/"
  }
}
