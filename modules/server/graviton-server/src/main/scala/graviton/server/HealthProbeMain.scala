package graviton.server

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

object HealthProbeMain:
  def main(args: Array[String]): Unit =
    val target   = args.headOption.getOrElse("http://127.0.0.1:8081/api/health/ready")
    val client   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    val request  = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(4)).GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.discarding())
    if response.statusCode() < 200 || response.statusCode() >= 300 then
      throw new IllegalStateException(s"health probe failed with HTTP ${response.statusCode()}")
