/*
 * Copyright © 2012 The Feign Authors (feign@commonhaus.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign.form;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.HeaderMap;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DynamicContentTypeTest {

  @TempDir Path directory;

  private HttpServer server;
  private UploadApi api;
  private File file;
  private final byte[] fileContents = "binary\u0000{literal}\r\ncafé".getBytes(UTF_8);
  private volatile byte[] requestBody;
  private volatile Map<String, List<String>> requestHeaders;

  @BeforeEach
  void startServer() throws IOException {
    file = Files.write(directory.resolve("upload.bin"), fileContents).toFile();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/upload",
        exchange -> {
          requestHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
          requestHeaders.putAll(exchange.getRequestHeaders());
          requestBody = exchange.getRequestBody().readAllBytes();
          byte[] response = "ok".getBytes(UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    api =
        Feign.builder()
            .encoder(new FormEncoder())
            .target(UploadApi.class, "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Content-Type", "content-type"})
  void headerMapSelectsMultipartEncoderForBody(String headerName) {
    assertThat(api.body(Map.of(headerName, "multipart/form-data"), Map.of("file", file)))
        .isEqualTo("ok");
    assertMultipartRequest();
  }

  @Test
  void headerMapSelectsMultipartEncoderForFormParameters() {
    assertThat(api.form(Map.of("Content-Type", "multipart/form-data"), file)).isEqualTo("ok");
    assertMultipartRequest();
  }

  @Test
  void parameterSelectsMultipartEncoder() {
    assertThat(api.parameter("multipart/form-data", file)).isEqualTo("ok");
    assertMultipartRequest();
  }

  @Test
  void headerMapDoesNotReplaceGeneratedBoundary() {
    assertThat(api.staticType(Map.of("Content-Type", "multipart/form-data"), "expanded", file))
        .isEqualTo("ok");
    assertMultipartRequest();
  }

  @Test
  void otherHeadersKeepLiteralAndMultipleValues() {
    Map<String, Object> headers =
        Map.of("X-Merged", List.of("{literal}", "second"), "X-Json", "{\"value\":1}");
    assertThat(api.staticType(headers, "expanded", file)).isEqualTo("ok");
    assertMultipartRequest();
    assertThat(requestHeaders.get("X-Merged")).containsExactly("expanded", "{literal}", "second");
    assertThat(requestHeaders.get("X-Json")).containsExactly("{\"value\":1}");
  }

  @Test
  void dynamicContentTypeKeepsUrlencodedCharset() {
    assertThat(
            api.body(
                Map.of("Content-Type", "application/x-www-form-urlencoded; charset=ISO-8859-1"),
                Map.of("name", "é")))
        .isEqualTo("ok");
    assertThat(new String(requestBody, ISO_8859_1)).isEqualTo("name=%E9");
    assertThat(requestHeaders.get("Content-Type"))
        .containsExactly("application/x-www-form-urlencoded; charset=ISO-8859-1");
  }

  private void assertMultipartRequest() {
    assertThat(requestHeaders.get("Content-Type")).hasSize(1);
    String contentType = requestHeaders.get("Content-Type").get(0);
    assertThat(contentType).startsWith("multipart/form-data; charset=UTF-8; boundary=");
    String boundary =
        contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
    String body = new String(requestBody, ISO_8859_1);
    assertThat(body)
        .startsWith("--" + boundary + "\r\n")
        .contains("Content-Disposition: form-data; name=\"file\"; filename=\"upload.bin\"")
        .contains("\r\n\r\n" + new String(fileContents, ISO_8859_1) + "\r\n")
        .endsWith("--" + boundary + "--\r\n");
  }

  interface UploadApi {
    @RequestLine("POST /upload")
    String body(@HeaderMap Map<String, Object> headers, Map<String, ?> body);

    @RequestLine("POST /upload")
    String form(@HeaderMap Map<String, Object> headers, @Param("file") File file);

    @RequestLine("POST /upload")
    @Headers("Content-Type: {contentType}")
    String parameter(@Param("contentType") String contentType, @Param("file") File file);

    @RequestLine("POST /upload")
    @Headers({"Content-Type: multipart/form-data", "X-Merged: {tag}"})
    String staticType(
        @HeaderMap Map<String, Object> headers, @Param("tag") String tag, @Param("file") File file);
  }
}
