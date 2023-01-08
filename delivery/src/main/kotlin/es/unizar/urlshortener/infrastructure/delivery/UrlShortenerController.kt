package es.unizar.urlshortener.infrastructure.delivery

import com.google.common.net.HttpHeaders.CONTENT_TYPE
import es.unizar.urlshortener.core.ClickProperties
import es.unizar.urlshortener.core.ClickRepositoryService
import es.unizar.urlshortener.core.NotValidatedYetException
import es.unizar.urlshortener.core.ShortUrlProperties
import es.unizar.urlshortener.core.ShortUrlRepositoryService
import es.unizar.urlshortener.core.UrlNotReachableException
import es.unizar.urlshortener.core.UrlNotSafeException
import es.unizar.urlshortener.core.usecases.CreateShortUrlUseCase
import es.unizar.urlshortener.core.usecases.GetQRUseCase
import es.unizar.urlshortener.core.usecases.LogClickUseCase
import es.unizar.urlshortener.core.usecases.RedirectUseCase
import es.unizar.urlshortener.core.usecases.ShowShortUrlInfoUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.apache.http.entity.ContentType.APPLICATION_JSON
import org.apache.http.entity.ContentType.IMAGE_PNG
import org.springframework.core.io.ByteArrayResource
import org.springframework.hateoas.server.mvc.linkTo
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import javax.servlet.http.HttpServletRequest

private const val CODE_200: Int = 200
private const val CODE_1000: Long = 1000

/**
 * The specification of the controller.
 */
interface UrlShortenerController {

    /**
     * Redirects and logs a short url identified by its [id].
     *
     * **Note**: Delivery of use cases [RedirectUseCase] and [LogClickUseCase].
     */
    fun redirectTo(id: String, request: HttpServletRequest): ResponseEntity<Void>

    /**
     * Creates a short url from details provided in [data].
     *
     * **Note**: Delivery of use case [CreateShortUrlUseCase].
     */
    fun shortener(data: ShortUrlDataIn, request: HttpServletRequest): ResponseEntity<ShortUrlInfo>

    /**
     * Generates a QR code given a short identified by its [hash].
     *
     * **Note**: Delivery of use case [GetQRUseCase].
     */
    fun generateQR(hash: String, request: HttpServletRequest): ResponseEntity<ByteArrayResource>

    /**
     * Shows relevant information about a short url identified by its [id].
     *
     * **Note**: Delivery of use case [ShowShortUrlInfoUseCase].
     */
    fun showShortUrlInfo(id: String, request: HttpServletRequest): ResponseEntity<ShortUrlInfo>
}

/**
 * Data required to create a short url.
 */
@Schema(description = "Data received from the users request.")
data class ShortUrlDataIn(
    @Schema(description = "URL to be shortened.")
    val url: String,
    val sponsor: String? = null,
    @Schema(description = "Value of the custom URL that the user wants. Empty if the user doesn't want a custom URL.")
    val customUrl: String
)

/**
 * Data returned after the creation of a short url.
 */
@Schema(description = "Data sent as a response to the users request of creating a URL")
data class ShortUrlDataOut(

    val url: URI? = null,
    val properties: Map<String, Any> = emptyMap()
)

/**
 * Data returned to /api/link/{id} request.
 */
@Schema(
    description = "Data sent as a response to the request coming from different endpoints (/api/link and /api/link{id})"
)
data class ShortUrlInfo(
    @Schema(description = "Url associated to the response")
    val url: String = "",
    @Schema(description = "Properties that the shortened URL has: Safe, reachble, sponsor...")
    val properties: Map<String, Any> = emptyMap(),
    @Schema(description = "Actions that can be done with the shortened url")
    val actions: Map<String, Any> = emptyMap()
)

/**
 * The implementation of the controller.
 *
 * **Note**: Spring Boot is able to discover this [RestController] without further configuration.
 */
@RestController
class UrlShortenerControllerImpl(
    val redirectUseCase: RedirectUseCase,
    val logClickUseCase: LogClickUseCase,
    val createShortUrlUseCase: CreateShortUrlUseCase,
    val shortUrlRepository: ShortUrlRepositoryService,
    val getQRUseCase: GetQRUseCase,
    val userAgentInfo: UserAgentInfo,
    val showShortUrlInfoUseCase: ShowShortUrlInfoUseCase,
    val clickRepositoryService: ClickRepositoryService
) : UrlShortenerController {

    @Operation(
        summary = "Redirect (if possible to a web page)",
        description = "Given a certain hash id, redirects (if possible) to the web page associated to that hash id."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Redirection completed succesfully"),
            ApiResponse(
                responseCode = "400",
                description = "Redirection not available because the url si not reachable"
            ),
            ApiResponse(responseCode = "403", description = "Redirection forbidden because the url si not safe"),
        ]
    )
    @GetMapping("/{id:(?!api|index).*}")
    override fun redirectTo(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Void> =
        redirectUseCase.redirectTo(id).let {
            val a = userAgentInfo.getBrowser(request.getHeader("User-Agent"))
            val b = userAgentInfo.getOS(request.getHeader("User-Agent"))
            logClickUseCase.logClick(id, ClickProperties(ip = request.remoteAddr, browser = a, platform = b))
            val h = HttpHeaders()
            h.set("Browser", a)
            h.set("OS", b)
            if (!shortUrlRepository.everythingChecked(id)) {
                throw NotValidatedYetException(id)
            } else if (!shortUrlRepository.isSafe(id)) { /** 403 Forbidden */
                print("Excepcion no segura")
                throw UrlNotSafeException(id)
            } else if (!shortUrlRepository.isReachable(id)) { /** 400 Bad Request y cabecera Retry-After */
                throw UrlNotReachableException(id)
            } else if (shortUrlRepository.hasSponsor(id)) {
                /** La URI recortada existe, se puede hacer redireccion y tiene publicidad */
                h.location = URI.create(it.target)
                ResponseEntity<Void>(h, HttpStatus.valueOf(CODE_200))
            } else {
                h.location = URI.create(it.target)
                ResponseEntity<Void>(h, HttpStatus.valueOf(it.mode))
            }
        }

    @Operation(
        summary = "Create a shortened URL",
        description = "Given a certain url, returns a shortened url or an error if it's not safe or reachable."
    )
    @PostMapping("/api/link", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    override fun shortener(data: ShortUrlDataIn, request: HttpServletRequest): ResponseEntity<ShortUrlInfo> =
        createShortUrlUseCase.create(
            url = data.url,
            data = ShortUrlProperties(
                ip = request.remoteAddr,
                sponsor = data.sponsor
            ),
            customUrl = data.customUrl
        ).let {
            try {
                /** sleep for one second */
                Thread.sleep(CODE_1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            print("data.customUrl es: ${data.customUrl}")
            if (!shortUrlRepository.everythingChecked(it.hash)) {
                throw NotValidatedYetException(data.url)
            } else if (!shortUrlRepository.isSafe(it.hash)) { // ///////////////////!!!!!!!!!!!!!Devolver 400 no 403
                print("Excepcion no segura: -----> " + it.hash)
                throw UrlNotSafeException(data.url)
            } else if (!shortUrlRepository.isReachable(it.hash)) {
                throw UrlNotReachableException(data.url)
            } else {
                val h = HttpHeaders()
                val url = linkTo<UrlShortenerControllerImpl> { redirectTo(it.hash, request) }.toUri()
                h.contentType = MediaType.APPLICATION_JSON
                h.location = url
                val lengthHash = it.hash.length
                val apilink = url.toString().substring(0, url.toString().length - lengthHash)
                val auxiliar = true
                val urlCustom = linkTo<UrlShortenerControllerImpl> { redirectTo(data.customUrl, request) }.toUri()
                val response = ShortUrlInfo(
                    url = it.redirection.target,
                    properties = mapOf(
                        "safe" to if (it.properties.safe != null) it.properties.safe as Any else auxiliar,
                        "reachable" to if (it.properties.reachable != null) {
                            it.properties.reachable as Any
                        } else {
                            auxiliar
                        },
                        "country" to if (it.properties.country != null) it.properties.country as Any else "",
                        "created" to it.created,
                        "owner" to if (it.properties.owner != null) it.properties.owner as Any else "",
                        "ip" to if (it.properties.ip != null) it.properties.ip as Any else "",
                        "sponsor" to if (it.properties.sponsor != null) it.properties.sponsor as Any else ""
                    ),
                    actions = mapOf(
                        "redirect" to "$url",
                        "qr" to ("$url/qr"),
                        "information" to ("${apilink}api/link/${it.hash}"),
                        "redirectCustomUrl" to if (data.customUrl != "") "$urlCustom" else "",
                        "qrCustomUrl" to if (data.customUrl != "") "$urlCustom/qr" else "",
                        "informationCustomUrl" to (
                            if (data.customUrl != "") ("${apilink}api/link/${data.customUrl}") else ""
                            ),

                    )
                )
                ResponseEntity<ShortUrlInfo>(response, h, HttpStatus.CREATED)
            }
        }

    @Operation(
        summary = "Return the qr for a given hash",
        description = "Given a certain hash id, returns the qr code (if the code exists)."
    )
    @GetMapping("/{hash}/qr")
    override fun generateQR(@PathVariable hash: String, request: HttpServletRequest):
        ResponseEntity<ByteArrayResource> =
        getQRUseCase.generateQR(hash).let {
            /** URI not reachable or safe */
            if (!shortUrlRepository.isReachable(hash)) {
                throw shortUrlRepository.findByKey(hash)?.redirection?.let { it1 ->
                    UrlNotReachableException(
                        it1.target
                    )
                }!!
            }
            /** URI exists but isn't safe */
            else if (!shortUrlRepository.isSafe(hash)) {
                throw shortUrlRepository.findByKey(hash)?.redirection?.let { it1 -> UrlNotSafeException(it1.target) }!!
            }
            val h = HttpHeaders()
            h.set(CONTENT_TYPE, IMAGE_PNG.toString())
            ResponseEntity<ByteArrayResource>(it, h, HttpStatus.OK)
        }

    @Operation(
        summary = "Return info for the given hash",
        description = "Given a certain hash id, returns the information of the url associated to the hash."
    )
    @GetMapping("/api/link/{id}")
    override fun showShortUrlInfo(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<ShortUrlInfo> =
        showShortUrlInfoUseCase.showShortUrlInfo(id).let {
            val h = HttpHeaders()
            h.set(CONTENT_TYPE, APPLICATION_JSON.toString())
            val url = linkTo<UrlShortenerControllerImpl> { redirectTo(it.hash, request) }.toUri()

            /** URI not reachable or safe */
            if (!it.properties.reachable!!) {
                throw UrlNotReachableException(it.redirection.target)
            }
            /** URI exists but isn't safe */
            else if (!it.properties.safe!!) {
                throw UrlNotSafeException(it.redirection.target)
            }
            val clicks = clickRepositoryService.getInfo(id)
            val browsers = mutableListOf<String>()
            val platforms = mutableListOf<String>()
            for (click in clicks) {
                click.properties.browser?.let { it1 -> browsers.add(it1) }
                click.properties.platform?.let { it1 -> platforms.add(it1) }
            }

            val lengthHash = it.hash.length
            val apilink = url.toString().substring(0, url.toString().length - lengthHash)
            val response = ShortUrlInfo(
                url = it.redirection.target,
                properties = mapOf(
                    "hash" to it.hash,
                    "safe" to it.properties.safe as Any,
                    "reachable" to it.properties.reachable as Any,
                    "country" to if (it.properties.country != null) it.properties.country as Any else "",
                    "created" to it.created,
                    "owner" to if (it.properties.owner != null) it.properties.owner as Any else "",
                    "ip" to if (it.properties.ip != null) it.properties.ip as Any else "",
                    "sponsor" to if (it.properties.sponsor != null) it.properties.sponsor as Any else "",
                    "browsers" to browsers.toSet().toList(),
                    "platforms" to platforms.toSet().toList()
                ),
                actions = mapOf<String, Any>(
                    "redirect" to "$url",
                    "qr" to ("$url/qr"),
                    "information" to ("${apilink}api/link/${it.hash}")
                )
            )
            ResponseEntity<ShortUrlInfo>(response, h, HttpStatus.OK)
        }
}
